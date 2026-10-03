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
import com.indagium.utils.extendsSnapshot
import com.indagium.utils.isUtf16LogFile
import com.indagium.utils.mergeMessageTemplates
import com.indagium.utils.mergeTagPids
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

    // Tailers whose job was cancelled on purpose by pauseTailing (live capture log view paused at
    // critical heap pressure). The FileTailer instance is kept because it owns the read offset:
    // resumeTailing restarts that same instance, so the file's growth while paused is read exactly
    // once, with no duplicate and no gap. A tab is in at most one of activeTails/pausedTails.
    private val pausedTails = ConcurrentHashMap<String, FileTailer>()

    // Ownership of a tab's tailer is CLAIMED, never peeked: every transition (start, pause, resume, drain)
    // takes its decision under [ownership], and a claim moves the tailer out of the map it was in
    // before anything blocking happens. Without that a Pause (joining the tailer on ioScope) and a
    // Stop's drain could both think they owned it: drain saw neither map during the join, skipped the
    // drain, and the pause then published a stale pausedTails entry plus a tailPausedAtRow on a tab
    // Stop had already finalized.
    //  - [pausing]: a tailer pauseTailing has claimed and is joining. A concurrent drain/stop takes it
    //    over (removes it from here); the pause then finds its claim gone and gives up without touching
    //    the tab.
    //  - [stopping]: tabs whose drain is in flight; pause and start refuse them, which also keeps
    //    startTailing from racing the drain now that the active entry is claimed at its start.
    // [ownership] is held only for short non-blocking sections (it may call upTab inside, so the lock
    // order is ownership -> stateLock); nothing holding stateLock may take it, which is why
    // cancelTailingFor only touches the concurrent maps.
    private val ownership = Any()
    private val pausing = ConcurrentHashMap<String, ActiveTail>()
    private val stopping: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
        // A paused tab resumes through resumeTailing (from its saved offset); starting a fresh tailer
        // here would replay or skip rows. Cheap early exit; the authoritative check repeats under the
        // ownership lock below.
        if (isTailOwned(tabId)) return
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
        synchronized(ownership) {
            if (isTailOwned(tabId)) return
            val job = tailer.start(scope)
            activeTails[tabId] = ActiveTail(tailer, job)
            appState.upTab(tabId) { it.copy(tailing = true) }
        }
        AppLogger.info("tail", "Started tailing tab")
    }

    // Whether [tabId] already has a tailer in any state (running, being paused, paused, being drained).
    // A paused entry whose tab is gone, or whose tab carries no pause marker, is stale — pause publishes
    // both together under the ownership lock, so this only heals an earlier inconsistency — and is
    // dropped instead of blocking startTailing forever.
    private fun isTailOwned(tabId: String): Boolean {
        if (activeTails.containsKey(tabId) || pausing.containsKey(tabId) || tabId in stopping) return true
        if (!pausedTails.containsKey(tabId)) return false
        val tab = appState.tab(tabId)
        if (tab != null && tab.tailPausedAtRow != null) return true
        pausedTails.remove(tabId)
        return false
    }

    // A paused tab (see pauseTailing) is left paused: it is already not tailing, and its saved
    // tailer and truncation marker are what Resume and the capture strip rely on.
    fun stopTailing(tabId: String) {
        activeTails.remove(tabId)?.job?.cancel()
        // A pause still joining this tailer loses its claim and gives up.
        pausing.remove(tabId)?.job?.cancel()
        appState.upTab(tabId) { it.copy(tailing = false) }
        AppLogger.info("tail", "Stopped tailing tab")
        // Content-triggered autosave is suppressed while any tab is actively tailing (see the
        // LaunchedEffect in App.kt) to avoid rewriting a fast-growing logData every ~400ms —
        // explicitly save now that this tab has settled.
        appState.autosaveNow()
    }

    /** Whether [tabId]'s tailing is currently paused by [pauseTailing] (and can be resumed). */
    fun isPaused(tabId: String): Boolean = pausedTails.containsKey(tabId)

    // Stops appending rows to [tabId] WITHOUT reading anything further, keeping the FileTailer (and so
    // its offset) for resumeTailing. Used at CRITICAL heap pressure for a live capture: the recorder
    // keeps writing to disk, only the in-memory row view stops growing. Returns whether the tab was
    // actively tailing (false = nothing to pause, nothing changed).
    //
    // BLOCKS the calling thread for cancelAndJoin (see drainAndStopTailing for why a bare cancel is
    // not enough: an in-flight append must land, in order, before the tab is marked paused), so call
    // it from ioScope, never the UI thread, and never while holding stateLock (the in-flight append
    // takes it). tailPausedAtRow records the row count at the moment the tailer is quiescent.
    //
    // A pending debounced analysis refresh is deliberately NOT cancelled: it only reads the rows
    // already in memory, and letting it finish extends the analysis coverage over the last batches
    // (appendTailedLines records them as beyond analyzedThroughId). Cancelling it would leave them
    // unanalysed.
    //
    // [mayPause] is evaluated under the ownership lock at claim time, so the caller can veto a pause
    // that raced with a Stop (AppState passes "this capture is not finalizing"). A Stop whose drain
    // arrives while this pause is joining takes the tailer over and this call returns false, leaving the
    // tab to the drain; a Stop that arrives after the pause completed is "Stop while paused" (no drain).
    fun pauseTailing(tabId: String, mayPause: () -> Boolean = { true }): Boolean {
        val active = synchronized(ownership) {
            if (tabId in stopping || !mayPause()) return false
            val claimed = activeTails.remove(tabId) ?: return false
            pausing[tabId] = claimed
            claimed
        }
        runBlocking { active.job.cancelAndJoin() }
        synchronized(ownership) {
            // Gone = a drain/stop took the tailer over, or the tab was closed meanwhile.
            if (!pausing.remove(tabId, active)) return false
            if (appState.tab(tabId) == null) return false
            pausedTails[tabId] = active.tailer
            appState.upTab(tabId) { it.copy(tailing = false, tailPausedAtRow = it.logData.size) }
        }
        AppLogger.info("tail", "Paused tailing tab at heap pressure")
        return true
    }

    // Restarts a tab paused by pauseTailing from the saved offset (FileTailer.resume, NOT start, which
    // would replay from startOffset or skip to end-of-file). The file kept growing meanwhile; the first
    // poll catches up in bounded chunks. Returns whether the tab was paused. Does not block.
    fun resumeTailing(tabId: String): Boolean {
        synchronized(ownership) {
            val tailer = pausedTails.remove(tabId) ?: return false
            if (appState.tab(tabId) == null) return false
            val job = tailer.resume(scope)
            activeTails[tabId] = ActiveTail(tailer, job)
            appState.upTab(tabId) { it.copy(tailing = true, tailPausedAtRow = null) }
        }
        AppLogger.info("tail", "Resumed tailing tab")
        return true
    }

    /** Bytes the paused tailer for [tabId] has not read yet (file length minus its offset), or null if not paused. */
    fun pausedBacklogBytes(tabId: String): Long? = pausedTails[tabId]?.unreadBytes()

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
    //
    // A tab PAUSED by pauseTailing is the one exception: it is stopped WITHOUT draining. Its unread
    // backlog is exactly what memory pressure made us not load; appending it here would re-create the
    // out-of-memory situation the pause avoided. The paused tailer is just dropped, tailing stays
    // false, and tailPausedAtRow is kept so the tab still reads as a truncated prefix. (The capture
    // archive does not depend on this tab: finalization reads the session files on disk.)
    //
    // The tailer is CLAIMED atomically (moved out of activeTails, or taken over from an in-flight pause)
    // before the join, so neither a concurrent pause nor a second drain can also own it.
    fun drainAndStopTailing(tabId: String, includeTrailingPartialLine: Boolean = false) {
        var paused: FileTailer? = null
        val active = synchronized(ownership) {
            paused = pausedTails.remove(tabId)
            if (paused != null) {
                null
            } else {
                (activeTails.remove(tabId) ?: pausing.remove(tabId))?.also { stopping += tabId }
            }
        }
        if (paused != null) {
            AppLogger.info("tail", "Stopped a paused tab without draining its backlog")
            stopTailing(tabId)
            return
        }
        try {
            if (active != null) {
                runBlocking { active.job.cancelAndJoin() }
                active.tailer.drainToEndOfFile(includeTrailingPartialLine) { batch -> appendTailedLines(tabId, batch) }
            }
            stopTailing(tabId)
        } finally {
            stopping -= tabId
        }
    }

    // Called from AppState.closeTabsById, inside its own synchronized(stateLock) block — plain
    // ConcurrentHashMap removals, safe whether or not the caller already holds the lock.
    fun cancelTailingFor(tabId: String) {
        activeTails.remove(tabId)?.job?.cancel()
        pausing.remove(tabId)?.job?.cancel()
        pausedTails.remove(tabId)
        analysisDebouncer.cancel(tabId)
    }

    fun clear() {
        activeTails.clear()
        pausing.clear()
        pausedTails.clear()
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
                    // instead of re-running on every single tail batch (P-04). An analysis that
                    // already holds results (not pending) KEEPS them and records how far they
                    // reach (analyzedThroughId), so the analysed prefix stays visible while rows
                    // keep arriving and only the new rows read as unanalysed; a still-pending
                    // analysis (initial load not done) stays pending, as for a freshly-opened file
                    // (see buildLogAnalysis/pendingAnalysis).
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
                            // Unioned per tag, same incremental rationale: a tag newly seen on a
                            // restarted app's pid makes `tag:` rules pick up that pid's earlier rows.
                            tagPids = mergeTagPids(cur.analysis.tagPids, newEntries),
                            // Coverage stops at the rows that existed before this batch; keep an
                            // earlier (smaller) bound if the prefix was already partial.
                            analyzedThroughId = if (cur.analysis.pending) {
                                null
                            } else {
                                cur.analysis.analyzedThroughId ?: (cur.logData.lastOrNull()?.id ?: 0)
                            },
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
        // includeCounts = false: tagCounts/processNames/tagPids are maintained per batch and mergeTailAnalysis
        // takes the live maps, so computing them here again would be thrown away.
        val full = buildLogAnalysis(logData, issueRules, includeCounts = false)
        currentCoroutineContext().ensureActive()
        var discarded = false
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
            if (merged != null) {
                current.copy(analysis = merged)
            } else {
                discarded = true
                current
            }
        }
        // A discarded result (rules edited mid-compute, logData replaced) leaves the tab's analysis as
        // stale as before; nothing else would schedule another run once the batches stop. The
        // debouncer sees this call from inside the running job and queues a follow-up for after it.
        if (discarded && logData.isNotEmpty() && appState.tab(tabId) != null) scheduleTailAnalysisRefresh(tabId)
    }
}

/**
 * Decides what a full [buildLogAnalysis] result computed for [snapshot] does to [current]'s
 * analysis, or null to discard it. Either way the result's own `tagCounts`/`processNames`/`tagPids` are
 * ignored: [current]'s incrementally maintained maps already cover every current row (the tail
 * refresh does not even compute them).
 * - [current] still holds exactly the snapshot list: [full]'s stack/crash/custom-issue data,
 *   complete (`pending = false`, `analyzedThroughId = null`).
 * - [current] is the snapshot plus appended rows: the same data, valid for the prefix (ids are
 *   stable), with `pending = false` and `analyzedThroughId` = the snapshot's last id, so the
 *   analysed prefix's results are shown and only the appended rows count as unanalysed.
 * - Anything else (logData replaced, or an empty snapshot): null, keep [current] untouched.
 */
internal fun mergeTailAnalysis(current: LogTab, snapshot: List<LogEntry>, full: LogAnalysis): LogAnalysis? {
    val live = current.analysis
    return when {
        current.logData === snapshot -> full.copy(
            tagCounts = live.tagCounts,
            processNames = live.processNames,
            tagPids = live.tagPids,
            pending = false,
            analyzedThroughId = null,
        )
        extendsSnapshot(current.logData, snapshot) && current.logData.size > snapshot.size -> full.copy(
            tagCounts = live.tagCounts,
            processNames = live.processNames,
            tagPids = live.tagPids,
            pending = false,
            analyzedThroughId = snapshot.last().id,
        )
        else -> null
    }
}
