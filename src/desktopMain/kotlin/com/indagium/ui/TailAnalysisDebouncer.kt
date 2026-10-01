package com.indagium.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Debounce for the tailing-triggered full analysis refresh (P-04) — buildLogAnalysis costs as
// much as the initial parse on a large file, so re-running it on every ~500ms FileTailer batch
// would make a long tail session progressively more expensive. 1.5s comfortably outlasts
// FileTailer's default 500ms poll interval, so a sustained burst of batches collapses into one
// refresh shortly after the burst quiets down instead of one per batch.
internal const val TAIL_ANALYSIS_DEBOUNCE_MS = 1_500L

// Upper bound on how long a continuous stream can keep postponing the refresh. A live capture
// polls every CAPTURE_TAIL_POLL_INTERVAL_MS (1s) — shorter than the debounce — so a pure
// cancel-and-relaunch debounce never fires until the stream stops, leaving the crash/stack
// analysis "pending" for the whole recording. Once the first still-unserved batch is this old, new
// batches stop cancelling the pending refresh and let it run. The result still lands even though
// more rows were appended while it computed: TailCoordinator's mergeTailAnalysis applies a
// prefix-computed analysis to an append-only-extended tab.
internal const val TAIL_ANALYSIS_MAX_WAIT_MS = 30_000L

/**
 * Per-tab debounce with a max-wait for [TailCoordinator]'s analysis refresh: each batch cancels and
 * relaunches the tab's pending job (so a burst collapses into one refresh after it quiets down),
 * unless the pending job's first batch is already [maxWaitMs] old — then it is left alone and runs.
 * The next batch after that job completes starts a fresh window.
 *
 * A batch that arrives while the refresh is already RUNNING (it reads the rows as they were when it
 * started) cannot be folded into that run, and must not be dropped either: it marks the tab dirty,
 * and when the running refresh completes a follow-up refresh is scheduled. Dropping it left a tab
 * whose last batches landed mid-refresh (a capture Stop's final drain) with rows no refresh ever
 * covered, and, before coverage tracking, with `analysis.pending = true` forever.
 *
 * [onBatch], [cancel] and [clear] all decide under one lock, so a [cancel] for a closed tab can
 * neither lose to nor re-create an entry from a concurrent [onBatch]/completion.
 *
 * [nowMs] is injectable so tests can drive it from a virtual clock.
 */
internal class TailAnalysisDebouncer(
    private val scope: CoroutineScope,
    private val debounceMs: Long = TAIL_ANALYSIS_DEBOUNCE_MS,
    private val maxWaitMs: Long = TAIL_ANALYSIS_MAX_WAIT_MS,
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    private val refresh: suspend (tabId: String) -> Unit,
) {
    // One scheduled-or-running refresh. [running] flips once the debounce delay has elapsed and the
    // refresh body is about to read its snapshot; [dirty] records a batch that arrived after that.
    private class Pending(val firstBatchAtMs: Long) {
        var job: Job? = null
        var running = false
        var dirty = false
    }

    // Every read and write happens under [lock]; plain map, no ConcurrentHashMap needed.
    private val pending = HashMap<String, Pending>()
    private val lock = Any()

    fun onBatch(tabId: String) {
        synchronized(lock) {
            val now = nowMs()
            val existing = pending[tabId]
            when {
                existing == null -> schedule(tabId, now)
                existing.running -> existing.dirty = true
                now - existing.firstBatchAtMs >= maxWaitMs -> Unit // due: let it run
                else -> {
                    existing.job?.cancel()
                    schedule(tabId, existing.firstBatchAtMs)
                }
            }
        }
    }

    fun cancel(tabId: String) {
        synchronized(lock) { pending.remove(tabId)?.job?.cancel() }
    }

    fun clear() {
        synchronized(lock) {
            pending.values.forEach { it.job?.cancel() }
            pending.clear()
        }
    }

    // Caller holds [lock].
    private fun schedule(tabId: String, firstBatchAtMs: Long) {
        val entry = Pending(firstBatchAtMs)
        pending[tabId] = entry
        entry.job = scope.launch {
            delay(debounceMs)
            val proceed = synchronized(lock) {
                // Cancelled or replaced while the delay was ending: this run is no longer wanted.
                (pending[tabId] === entry).also { if (it) entry.running = true }
            }
            if (!proceed) return@launch
            try {
                refresh(tabId)
            } finally {
                synchronized(lock) {
                    if (pending[tabId] === entry) {
                        pending.remove(tabId)
                        // Batches that landed during the run were not part of its snapshot.
                        if (entry.dirty) schedule(tabId, nowMs())
                    }
                }
            }
        }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
