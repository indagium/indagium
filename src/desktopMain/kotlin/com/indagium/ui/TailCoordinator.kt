package com.indagium.ui

import com.indagium.debug.AppLogger
import com.indagium.model.MessageCompositionState
import com.indagium.utils.ArchiveFormat
import com.indagium.utils.FileTailer
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.computeMessageTemplates
import com.indagium.utils.computeProcessNames
import com.indagium.utils.computeStackTraceGroups
import com.indagium.utils.detectArchiveFormat
import com.indagium.utils.isUtf16LogFile
import com.indagium.utils.mergeMessageTemplates
import com.indagium.utils.parseLogcatLines
import com.indagium.utils.passesFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// Debounce for the tailing-triggered full analysis refresh (P-04) — buildLogAnalysis costs as
// much as the initial parse on a large file, so re-running it on every ~500ms FileTailer batch
// would make a long tail session progressively more expensive. 1.5s comfortably outlasts
// FileTailer's default 500ms poll interval, so a sustained burst of batches collapses into one
// refresh shortly after the burst quiets down instead of one per batch.
private const val TAIL_ANALYSIS_DEBOUNCE_MS = 1_500L

// Extracted from AppState (Task 12 slice 2, mechanical — no behavior change): owns live file
// tailing — starting/stopping a FileTailer per tab, appending newly tailed lines into the owning
// AppState's tabs list, and debouncing the follow-up full analysis refresh each batch triggers.
// Synchronizes on AppState.stateLock (internal, not private, for exactly this reason) so its
// tabs-list writes stay atomic with every other stateLock-guarded mutation — see upTab's doc
// comment on AppState for the invariant this preserves.
internal class TailCoordinator(private val appState: AppState, private val scope: CoroutineScope) {
    private data class ActiveTail(val tailer: FileTailer, val job: Job)

    // ConcurrentHashMap (A-01): mutated from closeTabsById/startTailing/stopTailing (UI or
    // ControlServer/Ktor threads) and read/written by FileTailer's own scope flush coroutine.
    private val activeTails = ConcurrentHashMap<String, ActiveTail>()

    // Debounce jobs backing appendTailedLines' throttled analysis refresh — keyed by tabId, same
    // cancel-and-relaunch shape as AppState's autosaveInBackground. ConcurrentHashMap for the same
    // cross-thread reason as activeTails: written from the scope flush coroutine, removed via
    // cancelTailingFor from whichever thread closes/stops the tab.
    private val tailAnalysisJobs = ConcurrentHashMap<String, Job>()

    // Session-only (confirmed): tailing state never persists across a restart — tab.tailing
    // simply isn't written to the autosave token, so it always comes back false. Only tabs backed
    // by a real, currently-existing file path can be tailed (not a zip-extracted or merged tab).
    // startOffset/pollIntervalMs are a pass-through to FileTailer, added for a capture tab (Phase
    // 2b, not this change): a capture starts empty and must replay from byte 0 rather than the v1
    // default of "only new growth" (see FileTailer's own class doc), and a chatty live capture
    // wants a longer poll than the menu-driven default so each batch's full-list copy
    // (appendTailedLines' `cur.logData + newEntries`) and computeItems memo invalidation
    // (Filter.kt) don't fire twice as often as necessary. Defaults preserve every existing
    // caller's behavior unchanged (AppState.startTailing, the context menu, the MCP tools).
    @Suppress("ReturnCount") // Each early return is a separate, side-effect-free tailing precondition.
    fun startTailing(tabId: String, startOffset: Long? = null, pollIntervalMs: Long = 500) {
        if (activeTails.containsKey(tabId)) return
        val t = appState.tab(tabId) ?: return
        // DLT is a framed binary stream; FileTailer intentionally emits UTF-8 lines and cannot
        // preserve partial frames across polls. Until a framed incremental tailer exists, refuse
        // the action rather than appending corrupted RAW rows.
        if (t.logFormat == com.indagium.model.LogFormat.DLT) return
        val path = t.sourcePath ?: return
        val file = File(path)
        if (!file.isFile) return
        // A bare compressed log (foo.log.gz) is a real, currently-existing file, but appending
        // raw gzip bytes straight into logData as RAW entries via FileTailer would be nonsense —
        // there's no way to incrementally re-decompress "whatever got appended to the file since
        // last poll" the way a plain text file's new lines can just be read. Refuse to tail it.
        if (detectArchiveFormat(file) != ArchiveFormat.None) return
        // FileTailer reads appended bytes as UTF-8. A UTF-16 source would turn every other byte
        // into NULs and split lines at the wrong byte boundary, so static import supports it but
        // live watching remains unavailable until a streaming decoder is designed for it.
        if (isUtf16LogFile(file)) return
        val tailer = FileTailer(
            file,
            onNewLines = { newLines -> appendTailedLines(tabId, newLines) },
            pollIntervalMs = pollIntervalMs,
            startOffset = startOffset,
        )
        val job = tailer.start(scope)
        activeTails[tabId] = ActiveTail(tailer, job)
        appState.upTab(tabId) { it.copy(tailing = true) }
        AppLogger.info("tail", "Started tailing tab")
    }

    fun stopTailing(tabId: String) {
        activeTails.remove(tabId)?.job?.cancel()
        appState.upTab(tabId) { it.copy(tailing = false) }
        AppLogger.info("tail", "Stopped tailing tab")
        // Content-triggered autosave is suppressed while any tab is actively tailing (see the
        // LaunchedEffect in App.kt) to avoid rewriting a fast-growing logData every ~400ms —
        // explicitly save now that this tab has settled.
        appState.autosaveNow()
    }

    // Reads everything appended since the tailer's last poll and appends it synchronously, then
    // stops tailing. Needed because stopTailing()'s plain Job.cancel() can leave up to one poll
    // interval of already-written bytes unread — for a capture tab, tab.logData would then be
    // short of the row count in the capture's own mapping index, and CaptureTimelineIndex's
    // `row.ordinal in 1..logRowCount` guard would silently drop the tail of the video mapping
    // (log↔video sync would look fine and be wrong at the end of every recording).
    //
    // BLOCKS the calling thread — call only from ioScope (the intended caller, the capture-stop
    // path), never from the UI/AWT thread. See below for why.
    //
    // cancelAndJoin(), not a bare cancel(): cancel() returns as soon as cancellation is
    // *requested*, not once the tailer coroutine has actually stopped RUNNING. Inside FileTailer's
    // poll loop, `offset` is advanced BEFORE onNewLines (== appendTailedLines, which blocks on
    // appState.stateLock) is called. So a bare cancel() immediately followed by readToEndOfFile()
    // is racy: readToEndOfFile() can read from the tailer's already-advanced offset and then win
    // the race for stateLock (Java monitors aren't FIFO-fair), appending those "newer" bytes
    // BEFORE the still-in-flight poll iteration appends its "older" ones underneath it — reversing
    // their order in logData and breaking the strictly-increasing-id invariant
    // utils/EntryIdMap.kt's get() (dense-guess-then-binary-search) and utils/Filter.kt:219 depend
    // on, which this very change leans on harder (the maxOfOrNull → lastOrNull().id fix above).
    // There is no duplicate-read risk either way — offset only ever advances — the bug is purely
    // ordering. cancelAndJoin() blocks until the coroutine has fully exited: appendTailedLines
    // isn't a suspend fun, so cancellation can't preempt a call already in flight, and the poll
    // loop only rechecks isActive AFTER that call returns (at the do-while condition or the next
    // delay()) — so by the time cancelAndJoin() returns, any in-flight append has already landed
    // and released stateLock, and no further one can start. runBlocking is the same "cancel and
    // synchronously wait for shutdown" pattern ControlServer.kt already uses for its own
    // `runBlocking { session.close() }`; Dispatchers.IO's pool is elastic and built for exactly
    // this kind of short blocking wait (at most one file read + one appendTailedLines call).
    fun drainAndStopTailing(tabId: String) {
        activeTails[tabId]?.let { active ->
            runBlocking { active.job.cancelAndJoin() }
            val remaining = active.tailer.readToEndOfFile()
            if (remaining.isNotEmpty()) appendTailedLines(tabId, remaining)
        }
        stopTailing(tabId)
    }

    // Called from AppState.closeTabsById, inside its own synchronized(stateLock) block — plain
    // ConcurrentHashMap removals, safe whether or not the caller already holds the lock.
    fun cancelTailingFor(tabId: String) {
        activeTails.remove(tabId)?.job?.cancel()
        tailAnalysisJobs.remove(tabId)?.cancel()
    }

    fun clear() {
        activeTails.clear()
        tailAnalysisJobs.clear()
    }

    // Runs on whichever thread FileTailer's coroutine flushes from, unlike most upTab callers
    // which are UI-thread-only — wrapped in stateLock so a tailing flush can't race and lose an
    // update against any other stateLock-guarded tabs mutation, whether background (another tab's
    // tailing flush, an in-flight openFile/mergeTabs) or UI-thread (toggleGroup, selRow, ... — all
    // upTab callers, and upTab itself is stateLock-guarded).
    private fun appendTailedLines(tabId: String, newRawLines: List<String>) {
        if (newRawLines.isEmpty()) return
        synchronized(appState.stateLock) {
            val t = appState.tab(tabId) ?: return
            // Entry ids are strictly increasing by construction (LogParser assigns startId..n,
            // mergeLogs re-ids sequentially, and tailing itself only ever appends from max+1) — an
            // invariant already asserted and relied on at utils/EntryIdMap.kt's dense-guess-then-
            // binary-search get() and utils/Filter.kt:219. So the highest id is always the LAST
            // entry's id; scanning the whole (ever-growing) tab with maxOfOrNull on every batch was
            // O(n) per batch, O(n^2) over a long tail session. Do not "fix" this back to maxOfOrNull.
            val nextId = (t.logData.lastOrNull()?.id ?: 0) + 1
            val newEntries = parseLogcatLines(newRawLines.asSequence(), startId = nextId)
            appState.tabs = appState.tabs.map { cur ->
                if (cur.id == tabId) {
                    val nextData = cur.logData + newEntries
                    // logData/rmap/tagCounts stay immediate — cheap, and needed right away for
                    // correct display. The expensive crash/stack-trace scan is debounced below
                    // instead of re-running on every single tail batch (P-04); pending = true
                    // reuses the same "still analyzing" rendering FilterPanel/Filter.kt already
                    // have for a freshly-opened file (see buildLogAnalysis/pendingAnalysis).
                    //
                    // (PERF-6) tagCounts is updated incrementally from the existing map, not
                    // recomputed by regrouping the whole (ever-growing) nextData — a full rescan
                    // every ~500ms batch turned an hours-long tail session into an O(n^2) cost
                    // over the file's total line count. merge(..., Int::plus) adds the new
                    // batch's counts onto the running totals instead of the map `+` operator,
                    // which would overwrite rather than sum an existing tag's count.
                    //
                    // messageComposition is folded in the same way, but ONLY when a histogram
                    // already exists for this tab (Computed) — a tail flush must never trigger the
                    // on-demand initial scan (most sessions never open the panel) and must never
                    // wipe an existing one out. When it does exist, masking depends only on the
                    // individual line, so scanning just the new batch and unioning counts is exact.
                    // computeStackTraceGroups runs on the batch alone (cheap — a batch is at most a
                    // few hundred lines), so a trace straddling this batch boundary loses member-
                    // exclusion for its tail half until the debounced full buildLogAnalysis() below
                    // replaces `analysis` wholesale — bounded and self-healing, not worth a
                    // cross-batch trace-continuation scheme. buildLogAnalysis() itself no longer
                    // touches messageComposition (it lives outside `analysis` now), so that debounced
                    // replace can never discard what this merge just built.
                    //
                    // The batch is filtered by the SAME filter the existing histogram was built
                    // for before merging. The composition describes what the current view is made
                    // of, so folding in raw unfiltered lines would quietly mix filtered and
                    // unfiltered counts into one number. forFilter carries through unchanged: this
                    // merge extends an existing result, it does not answer a new question.
                    val existingComposition = cur.messageComposition
                    val nextComposition = if (existingComposition is MessageCompositionState.Computed) {
                        val forFilter = existingComposition.forFilter
                        val ctx = RegexEvaluationContext()
                        val admitted = newEntries.filter { passesFilter(it, forFilter, ctx) }
                        MessageCompositionState.Computed(
                            mergeMessageTemplates(
                                existingComposition.histogram,
                                computeMessageTemplates(admitted, computeStackTraceGroups(admitted)),
                            ),
                            forFilter,
                        )
                    } else {
                        existingComposition
                    }
                    cur.copy(
                        logData = nextData,
                        rmap = mkRmap(nextData),
                        // largeFileMode is normally decided once at open time from the file's
                        // on-disk byte length (AppState.kt's openFile, LARGE_FILE_MODE_BYTES) and
                        // never re-evaluated — but a tail has no file-length signal to hand, and a
                        // long-running tail (e.g. a live capture) can grow well past that threshold
                        // in row count alone. LARGE_FILE_MODE_ROWS is the row-count analogue,
                        // checked here on every batch. Deliberately one-way (`||`, never turns back
                        // off) — same as the byte-based decision it mirrors.
                        largeFileMode = cur.largeFileMode || nextData.size >= LARGE_FILE_MODE_ROWS,
                        messageComposition = nextComposition,
                        analysis = cur.analysis.copy(
                            tagCounts = cur.analysis.tagCounts.toMutableMap().apply {
                                newEntries.forEach { merge(it.tag, 1, Int::plus) }
                            },
                            // Merged, not recomputed-and-replaced, same rationale as tagCounts
                            // above (PERF-6): scanning only the new batch and unioning it onto the
                            // running map keeps this O(batch size), not O(whole ever-growing
                            // file), every ~500ms. `+` lets the new batch's names win on a pid
                            // collision (matches computeProcessNames' own last-writer-wins rule —
                            // see its doc), while pids from earlier batches not mentioned in this
                            // one keep their previously learned name instead of being dropped.
                            processNames = cur.analysis.processNames + computeProcessNames(newEntries),
                            pending = true,
                        ),
                    )
                } else {
                    cur
                }
            }
        }
        AppLogger.debug("tail", "Appended ${newRawLines.size} parsed diagnostic rows")
        scheduleTailAnalysisRefresh(tabId)
    }

    // Cancel-and-relaunch, same shape as AppState's autosaveInBackground: every new batch
    // supersedes the previous refresh before it runs, so a sustained burst collapses into one
    // full buildLogAnalysis() shortly after it quiets down rather than one per batch. Reads
    // logData fresh (not a captured snapshot) so it reflects everything appended by the time this
    // job actually runs, even across several superseded batches.
    private fun scheduleTailAnalysisRefresh(tabId: String) {
        tailAnalysisJobs[tabId]?.cancel()
        tailAnalysisJobs[tabId] = scope.launch {
            delay(TAIL_ANALYSIS_DEBOUNCE_MS)
            val logData = synchronized(appState.stateLock) { appState.tab(tabId)?.logData } ?: return@launch
            val issueRules = appState.settings.customIssueRules
            val full = buildLogAnalysis(logData, issueRules)
            ensureActive()
            appState.upTab(tabId) { current ->
                if (appState.settings.customIssueRules == issueRules && current.logData == logData) current.copy(analysis = full) else current
            }
        }
    }
}
