package com.indagium.capture

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import java.io.File
import java.time.Instant
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Opt-in build-cost benchmark for the v5 resolver plus navigation index on a synthetic single-segment
 * log. Run with `INDAGIUM_CAPTURE_INDEX_BENCHMARK=1` (and optionally
 * `INDAGIUM_CAPTURE_INDEX_BENCHMARK_ROWS=<n>`, default 1M; the default test heap needs a few million
 * rows at most); the default suite never asserts on time or heap, so it cannot flake.
 */
@Suppress("MagicNumber")
class CaptureTimelineIndexBenchmarkTest {
    @Test
    fun resolverAndIndexBuildCostOnSyntheticSingleSegmentLog() {
        if (System.getenv("INDAGIUM_CAPTURE_INDEX_BENCHMARK") != "1") return
        val rowCount = System.getenv("INDAGIUM_CAPTURE_INDEX_BENCHMARK_ROWS")?.toInt() ?: 1_000_000
        val entries = syntheticEntries(rowCount)
        val timeline = singleSegmentTimeline(rowCount)

        val heapBefore = settledUsedHeap()
        lateinit var resolver: CaptureTimingResolver
        lateinit var index: CaptureTimelineIndex
        val buildNs = measureNanoTime {
            resolver = CaptureTimingResolver.forTimeline(entries, timeline)
            index = CaptureTimelineIndex(timeline, entries.size, resolver)
        }
        val heapAfter = settledUsedHeap()

        assertEquals(0L, resolver.videoMsForOrdinal(1))
        assertEquals(rowCount - 1L, resolver.videoMsForOrdinal(rowCount))
        assertEquals(rowCount / 2, assertNotNull(index.floor(rowCount / 2 - 1L).point).ordinal)
        val metrics = "rows=$rowCount build_ms=${buildNs / 1_000_000.0} " +
            "retained_heap_mib=${(heapAfter - heapBefore) / (1024.0 * 1024.0)}"
        File("build/verification/capture-index").apply { mkdirs() }
            .resolve("metrics.txt").writeText(metrics)
        println("CAPTURE_INDEX_BENCHMARK $metrics")
    }

    private fun settledUsedHeap(): Long {
        repeat(4) {
            System.gc()
            Thread.sleep(100)
        }
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun syntheticEntries(rowCount: Int): List<LogEntry> = List(rowCount) { index ->
        val ms = index.toLong()
        val clock = "%02d:%02d:%02d.%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60, ms % 1_000)
        LogEntry(index + 1, clock, LogLevel.I, "Tag", "row")
    }

    private fun singleSegmentTimeline(rowCount: Int) = CaptureTimeline(
        rows = emptyList(),
        timelineDurationMs = rowCount.toLong(),
        videoOriginElapsedMs = 0L,
        syncSegments = listOf(
            CaptureSyncSegment(
                id = 0,
                firstRow = 1,
                lastRowExclusive = rowCount + 1,
                anchorLogTimeMs = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
                anchorCaptureElapsedMs = 0L,
                reliability = CaptureSyncReliability.RELIABLE,
                clockReference = CaptureClockReference(0L, null, 2026),
            ),
        ),
        videoDurationMs = rowCount.toLong(),
        sparseTiming = true,
    )
}
