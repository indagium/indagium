package com.openlog.ui

import com.openlog.utils.FileTailer
import com.openlog.utils.parseLogcatLines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
    fun startTailing(tabId: String) {
        if (activeTails.containsKey(tabId)) return
        val t = appState.tab(tabId) ?: return
        val path = t.sourcePath ?: return
        val file = File(path)
        if (!file.isFile) return
        val tailer = FileTailer(file, onNewLines = { newLines -> appendTailedLines(tabId, newLines) })
        val job = tailer.start(scope)
        activeTails[tabId] = ActiveTail(tailer, job)
        appState.upTab(tabId) { it.copy(tailing = true) }
    }

    fun stopTailing(tabId: String) {
        activeTails.remove(tabId)?.job?.cancel()
        appState.upTab(tabId) { it.copy(tailing = false) }
        // Content-triggered autosave is suppressed while any tab is actively tailing (see the
        // LaunchedEffect in App.kt) to avoid rewriting a fast-growing logData every ~400ms —
        // explicitly save now that this tab has settled.
        appState.autosaveNow()
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
            val nextId = (t.logData.maxOfOrNull { it.id } ?: 0) + 1
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
                    cur.copy(
                        logData = nextData,
                        rmap = mkRmap(nextData),
                        analysis = cur.analysis.copy(
                            tagCounts = cur.analysis.tagCounts.toMutableMap().apply {
                                newEntries.forEach { merge(it.tag, 1, Int::plus) }
                            },
                            pending = true,
                        ),
                    )
                } else {
                    cur
                }
            }
        }
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
            val full = buildLogAnalysis(logData)
            ensureActive()
            appState.upTab(tabId) { it.copy(analysis = full) }
        }
    }
}
