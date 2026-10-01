@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("MagicNumber")

package com.indagium

import com.indagium.ui.TAIL_ANALYSIS_DEBOUNCE_MS
import com.indagium.ui.TAIL_ANALYSIS_MAX_WAIT_MS
import com.indagium.ui.TailAnalysisDebouncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tail-analysis refresh is a cancel-and-relaunch debounce (1.5 s) while a capture batch arrives
 * every 1 s, so without a max-wait a continuous stream starves the refresh until the stream stops.
 * Virtual time keeps these deterministic.
 */
class TailAnalysisDebouncerTest {
    private val pollMs = 1_000L

    private fun TestScope.debouncer(maxWaitMs: Long, runs: MutableList<Long>) = TailAnalysisDebouncer(
        scope = backgroundScope,
        debounceMs = TAIL_ANALYSIS_DEBOUNCE_MS,
        maxWaitMs = maxWaitMs,
        nowMs = { currentTime },
    ) { runs += currentTime }

    /** One batch per poll interval for [seconds] virtual seconds, starting at t=0. */
    private suspend fun TestScope.streamBatches(d: TailAnalysisDebouncer, seconds: Int) {
        repeat(seconds) {
            d.onBatch("tab")
            advanceTimeBy(pollMs)
        }
    }

    // Reproduces the suspected bug: with no max-wait (the legacy behaviour) a batch every 1 s
    // postpones a 1.5 s debounce forever, so the refresh never runs during the whole stream.
    @Test
    fun withoutAMaxWaitAContinuousStreamNeverRunsTheRefresh() = runTest {
        val runs = mutableListOf<Long>()
        val d = debouncer(maxWaitMs = Long.MAX_VALUE, runs = runs)
        streamBatches(d, seconds = 120)
        assertTrue(runs.isEmpty(), "legacy cancel-and-relaunch starves under a continuous stream, ran at $runs")
    }

    @Test
    fun aContinuousStreamStillRefreshesOncePerMaxWaitWindow() = runTest {
        val runs = mutableListOf<Long>()
        val d = debouncer(TAIL_ANALYSIS_MAX_WAIT_MS, runs)
        streamBatches(d, seconds = 62)
        // First window starts at the first batch (t=0); the batch at t=30 s leaves the pending job
        // alone, which fires 1.5 s after the batch at t=29 s. The batch at t=31 s opens a fresh
        // window, so the next run is at 61.5 s.
        assertEquals(listOf(30_500L, 61_500L), runs)
    }

    @Test
    fun aQuietStreamStillCollapsesABurstIntoOneRefresh() = runTest {
        val runs = mutableListOf<Long>()
        val d = debouncer(TAIL_ANALYSIS_MAX_WAIT_MS, runs)
        streamBatches(d, seconds = 5)
        advanceTimeBy(10_000)
        assertEquals(1, runs.size)
        assertEquals(4_000L + TAIL_ANALYSIS_DEBOUNCE_MS, runs.single())
    }

    @Test
    fun cancelDropsThePendingRefresh() = runTest {
        val runs = mutableListOf<Long>()
        val d = debouncer(TAIL_ANALYSIS_MAX_WAIT_MS, runs)
        d.onBatch("tab")
        d.cancel("tab")
        advanceTimeBy(10_000)
        assertTrue(runs.isEmpty())
    }

    @Test
    fun tabsAreDebouncedIndependently() = runTest {
        val runs = mutableListOf<String>()
        val d = TailAnalysisDebouncer(backgroundScope, nowMs = { currentTime }) { runs += it }
        d.onBatch("a")
        advanceTimeBy(1_000)
        d.onBatch("b")
        advanceTimeBy(1_000)
        // "a" fires at 1500, "b" at 2500.
        assertEquals(listOf("a"), runs)
        advanceTimeBy(1_000)
        assertEquals(listOf("a", "b"), runs)
    }

    // The bug: a batch landing while the refresh was already RUNNING (past its debounce delay, reading
    // the rows as of its start) was silently dropped, and nothing rescheduled once the run finished.
    @Test
    fun aBatchDuringARunningRefreshQueuesAFollowUpForAfterItCompletes() = runTest {
        val starts = mutableListOf<Long>()
        val d = TailAnalysisDebouncer(backgroundScope, nowMs = { currentTime }) {
            starts += currentTime
            delay(5_000)
        }
        d.onBatch("tab")
        advanceTimeBy(1_600) // the refresh started at 1.5 s and runs until 6.5 s
        assertEquals(listOf(1_500L), starts)

        d.onBatch("tab") // lands mid-run: not part of the run's snapshot
        advanceTimeBy(20_000)

        // Follow-up: 1.5 s after the first run finished (6.5 s), and no more than one.
        assertEquals(listOf(1_500L, 8_000L), starts)
    }

    @Test
    fun aRunThatSawNoLaterBatchDoesNotQueueAFollowUp() = runTest {
        val starts = mutableListOf<Long>()
        val d = TailAnalysisDebouncer(backgroundScope, nowMs = { currentTime }) {
            starts += currentTime
            delay(5_000)
        }
        d.onBatch("tab")
        advanceTimeBy(30_000)
        assertEquals(listOf(1_500L), starts)
    }

    // Stop's final drain appends rows while a (max-wait) refresh is computing on an older snapshot.
    // Coverage must end up complete without any further batch arriving.
    @Test
    fun finalDrainDuringARefreshEndsWithTheWholeTabAnalysed() = runTest {
        var rows = 10
        var analysedThrough = 0
        val d = TailAnalysisDebouncer(backgroundScope, nowMs = { currentTime }) {
            val snapshot = rows // the refresh reads the rows as they are when it starts
            delay(5_000)
            analysedThrough = maxOf(analysedThrough, snapshot)
        }
        d.onBatch("tab")
        advanceTimeBy(2_000) // running, snapshot = 10 rows
        rows = 25 // Stop's drain appends 15 more rows ...
        d.onBatch("tab") // ... and reports its batch
        advanceTimeBy(5_000) // the first run completes: covers 10 of 25 rows
        assertEquals(10, analysedThrough)

        advanceTimeBy(20_000)
        assertEquals(25, analysedThrough, "the follow-up must cover the rows the first run missed")
    }

    @Test
    fun cancelDuringARunWithALaterBatchLeavesNoFollowUpAndNoEntry() = runTest {
        val starts = mutableListOf<Long>()
        val d = TailAnalysisDebouncer(backgroundScope, nowMs = { currentTime }) {
            starts += currentTime
            delay(5_000)
        }
        d.onBatch("tab")
        advanceTimeBy(2_000) // running
        d.onBatch("tab") // dirty
        d.cancel("tab") // the tab is closed
        advanceTimeBy(30_000)
        assertEquals(listOf(1_500L), starts, "a closed tab must not get a follow-up refresh")

        // The tab id can be reused afterwards: a fresh window starts normally.
        d.onBatch("tab")
        advanceTimeBy(2_000)
        assertEquals(2, starts.size)
    }

    // cancel() used to be a lock-free remove, so it could interleave with onBatch's check-then-act and
    // leave (or re-insert) a job for a tab that had just been closed. Real threads, real scheduler.
    @Test
    fun concurrentOnBatchAndCancelLeaveNoRefreshAfterTheFinalCancel() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runs = AtomicInteger()
        try {
            val d = TailAnalysisDebouncer(scope, debounceMs = 1, maxWaitMs = 5) { runs.incrementAndGet() }
            val batcher = thread { repeat(2_000) { d.onBatch("tab") } }
            val canceller = thread { repeat(2_000) { d.cancel("tab") } }
            batcher.join()
            canceller.join()
            d.cancel("tab") // the tab closes: nothing may run from here on
            Thread.sleep(100) // lets any in-flight run finish
            val settled = runs.get()
            Thread.sleep(150)
            assertEquals(settled, runs.get(), "a refresh ran for a tab after its final cancel")
        } finally {
            scope.cancel()
        }
    }
}
