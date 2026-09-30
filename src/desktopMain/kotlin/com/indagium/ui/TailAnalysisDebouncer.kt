package com.indagium.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

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
 * [nowMs] is injectable so tests can drive it from a virtual clock.
 */
internal class TailAnalysisDebouncer(
    private val scope: CoroutineScope,
    private val debounceMs: Long = TAIL_ANALYSIS_DEBOUNCE_MS,
    private val maxWaitMs: Long = TAIL_ANALYSIS_MAX_WAIT_MS,
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    private val refresh: suspend (tabId: String) -> Unit,
) {
    private class Pending(val job: Job, val firstBatchAtMs: Long)

    // ConcurrentHashMap for the same cross-thread reason TailCoordinator's activeTails is one:
    // written from the flush coroutine, removed via cancel() from whichever thread closes the tab.
    // Schedule decisions themselves are serialized by the lock below.
    private val pending = ConcurrentHashMap<String, Pending>()
    private val lock = Any()

    fun onBatch(tabId: String) {
        synchronized(lock) {
            val now = nowMs()
            val existing = pending[tabId]
            val firstBatchAtMs = if (existing != null && existing.job.isActive) {
                if (now - existing.firstBatchAtMs >= maxWaitMs) return
                existing.job.cancel()
                existing.firstBatchAtMs
            } else {
                now
            }
            pending[tabId] = Pending(
                scope.launch {
                    delay(debounceMs)
                    refresh(tabId)
                },
                firstBatchAtMs,
            )
        }
    }

    fun cancel(tabId: String) {
        pending.remove(tabId)?.job?.cancel()
    }

    fun clear() {
        pending.values.forEach { it.job.cancel() }
        pending.clear()
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
