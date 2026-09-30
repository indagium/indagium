package com.indagium.ui

import com.indagium.debug.AppLogger
import com.indagium.model.LogAnalysis
import com.indagium.model.LogEntry
import com.indagium.model.LogTab
import com.indagium.model.MessageCompositionState
import com.indagium.utils.ArchiveFormat
import com.indagium.utils.FileTailer
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.appendLogEntries
import com.indagium.utils.computeMessageTemplates
import com.indagium.utils.computeProcessNames
import com.indagium.utils.computeStackTraceGroups
import com.indagium.utils.detectArchiveFormat
import com.indagium.utils.isUtf16LogFile
import com.indagium.utils.mergeMessageTemplates
import com.indagium.utils.parseLogcatLines
import com.indagium.utils.passesFilter
import com.indagium.utils.viewDefiningKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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

    // Per-tab debounce (with a max-wait so a continuous stream cannot postpone it forever) backing
    // appendTailedLines' throttled analysis refresh. Same cancel-and-relaunch shape as AppState's
    // autosaveInBackground; see TailAnalysisDebouncer.
    private val analysisDebouncer = TailAnalysisDebouncer(scope) { tabId -> refreshAnalysis(tabId) }

    // Session-only (confirmed): tailing state never persists across a restart — tab.tailing
    // simply isn't written to the autosave token, so it always comes back false. Only tabs backed
    // by a real, currently-existing file path can be tailed (not a zip-extracted or merged tab).
    // startOffset/pollIntervalMs are a pass-through to FileTailer, added for a capture tab (Phase
    // 2b, not this change): a capture starts empty and must replay from byte 0 rather than the v1
    // default of "only new growth" (see FileTailer's own class doc), and a chatty live capture
    // wants a longer poll than the menu-driven default so each batch's computeItems memo
    // invalidation (Filter.kt) and rmap/tab rebuild don't fire twice as often as necessary. (The
    // batch append itself is O(batch): appendTailedLines shares logData's backing array via
    // appendLogEntries instead of copying it.) Defaults preserve every existing
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
    //
    // [includeTrailingPartialLine] is for the capture-stop path only, called after the recorder has
    // stopped writing: its final line may have no trailing newline, yet the recorder indexes it as
    // a row, so the tab must show it too. The remaining lines are appended chunk by chunk (bounded
    // by FileTailer's chunk cap) rather than as one list.
    fun drainAndStopTailing(tabId: String, includeTrailingPartialLine: Boolean = false) {
        activeTails[tabId]?.let { active ->
            runBlocking { active.job.cancelAndJoin() }
            active.tailer.drainToEndOfFile(includeTrailingPartialLine) { batch -> appendTailedLines(tabId, batch) }
        }
        stopTailing(tabId)
    }

    // Called from AppState.closeTabsById, inside its own synchronized(stateLock) block — plain
    // ConcurrentHashMap removals, safe whether or not the caller already holds the lock.
    fun cancelTailingFor(tabId: String) {
        activeTails.remove(tabId)?.job?.cancel()
        analysisDebouncer.cancel(tabId)
    }

    fun clear() {
        activeTails.clear()
        analysisDebouncer.clear()
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
                    // appendLogEntries shares logData's backing array with the previous list when
                    // it can, so this is O(batch) instead of copying every existing row (a 5 MB
                    // humongous array per batch at 1.3M rows). The returned list is a NEW object
                    // each batch, so every `===` cache keyed on logData invalidates exactly as it
                    // did with the old `cur.logData + newEntries` copy.
                    val nextData = appendLogEntries(cur.logData, newEntries)
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
                    val nextCompositionRevision = cur.messageCompositionRevision + 1
                    val existingComposition = cur.messageComposition
                    val nextComposition = if (existingComposition is MessageCompositionState.Computed &&
                        existingComposition.forRevision == cur.messageCompositionRevision &&
                        existingComposition.forFilter == cur.filter.viewDefiningKey()
                    ) {
                        val forFilter = existingComposition.forFilter
                        val ctx = RegexEvaluationContext()
                        val admitted = newEntries.filter { passesFilter(it, forFilter, ctx) }
                        MessageCompositionState.Computed(
                            mergeMessageTemplates(
                                existingComposition.histogram,
                                computeMessageTemplates(admitted, computeStackTraceGroups(admitted)),
                            ),
                            forFilter,
                            nextCompositionRevision,
                        )
                    } else if (existingComposition is MessageCompositionState.Computing ||
                        existingComposition is MessageCompositionState.Computed
                    ) {
                        // A scan over previous rows (or an old computed histogram) must never
                        // remain visible as if it described this batch.
                        MessageCompositionState.NotComputed
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
                        messageCompositionRevision = nextCompositionRevision,
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

    // The debounce lives in TailAnalysisDebouncer (cancel-and-relaunch per batch, with a max-wait
    // so a continuous capture stream cannot starve it). Reads logData fresh (not a captured
    // snapshot) so it reflects everything appended by the time this job actually runs, even across
    // several superseded batches.
    private fun scheduleTailAnalysisRefresh(tabId: String) = analysisDebouncer.onBatch(tabId)

    private suspend fun refreshAnalysis(tabId: String) {
        val logData = synchronized(appState.stateLock) { appState.tab(tabId)?.logData } ?: return
        val issueRules = appState.settings.customIssueRules
        val full = buildLogAnalysis(logData, issueRules)
        currentCoroutineContext().ensureActive()
        appState.upTab(tabId) { current ->
            // A live capture appends a batch every second and buildLogAnalysis takes longer than
            // that on a large tab, so requiring "nothing appended since the snapshot" would discard
            // nearly every refresh. mergeTailAnalysis also accepts a result computed for a prefix
            // (append-only tailing keeps entry ids stable) and keeps the incrementally maintained
            // per-batch fields, so the refresh lands during a live capture. Its checks are O(1):
            // upTab runs this under stateLock and logData is a million-row list.
            val merged = if (appState.settings.customIssueRules == issueRules) {
                mergeTailAnalysis(current, logData, full)
            } else {
                null
            }
            if (merged != null) current.copy(analysis = merged) else current
        }
    }
}

/**
 * True when [current] is [snapshot] with rows appended (identical, or a strictly longer append-only
 * continuation), in O(1). Tailing only ever appends and entry ids strictly increase, so comparing
 * the snapshot's last row by identity with the same index of [current] is sufficient; it also works
 * across backing-store regrowth, where the two lists no longer share a store. An empty [snapshot]
 * is never considered extended.
 */
internal fun extendsSnapshot(current: List<LogEntry>, snapshot: List<LogEntry>): Boolean =
    snapshot.isNotEmpty() && current.size >= snapshot.size &&
        current[snapshot.size - 1] === snapshot[snapshot.size - 1]

/**
 * Decides what a full [buildLogAnalysis] result computed for [snapshot] does to [current]'s
 * analysis, or null to discard it.
 * - [current] still holds exactly the snapshot list: [full] as-is (complete, `pending = false`).
 * - [current] is the snapshot plus appended rows: [full]'s stack/crash/custom-issue data (valid for
 *   the prefix, ids are stable), but the incrementally maintained `tagCounts`/`processNames` (they
 *   already cover every current row) and `pending = true`, since the appended rows are not yet
 *   analysed.
 * - Anything else (logData replaced, or an empty snapshot): null, keep [current] untouched.
 */
internal fun mergeTailAnalysis(current: LogTab, snapshot: List<LogEntry>, full: LogAnalysis): LogAnalysis? = when {
    current.logData === snapshot -> full
    extendsSnapshot(current.logData, snapshot) && current.logData.size > snapshot.size -> full.copy(
        tagCounts = current.analysis.tagCounts,
        processNames = current.analysis.processNames,
        pending = true,
    )
    else -> null
}
