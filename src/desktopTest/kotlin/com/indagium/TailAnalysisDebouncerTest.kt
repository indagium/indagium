@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("MagicNumber")

package com.indagium

import com.indagium.ui.TAIL_ANALYSIS_DEBOUNCE_MS
import com.indagium.ui.TAIL_ANALYSIS_MAX_WAIT_MS
import com.indagium.ui.TailAnalysisDebouncer
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
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
}
