package com.indagium.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureSyncAnchorEstimatorTest {
    @Test
    fun constantLatencyPicksTheFirstRowAsAnchorAtItsExactPredictedPosition() {
        // Every row lags the log by exactly 50ms of transport latency — the simplest possible case,
        // no jitter at all. The anchor should land on the first (lowest-ordinal) row, exactly at
        // logTimeMs + 50.
        val samples = listOf(
            CaptureSyncSample(ordinal = 1, logTimeMs = 1_000L, videoMs = 1_050L),
            CaptureSyncSample(ordinal = 2, logTimeMs = 2_000L, videoMs = 2_050L),
            CaptureSyncSample(ordinal = 3, logTimeMs = 3_000L, videoMs = 3_050L),
        )

        val anchor = estimateCaptureSyncAnchor(samples, videoDurationMs = 10_000L)

        assertEquals(CaptureSyncAnchor(row = 1, videoMs = 1_050L), anchor)
    }

    @Test
    fun jitteryLatencyWithARareOutlierStillEstimatesCloseToTheTrueOffset() {
        // 200 rows with a true transport latency of 300ms plus +/-10ms jitter, and exactly one
        // corrupted sample (a clock glitch) whose apparent offset is wildly negative. A raw minimum
        // would lock onto that one bad sample; the low-percentile estimate must not.
        val trueOffsetMs = 300L
        val jitterMs = longArrayOf(-10, -7, -4, -1, 2, 5, 8, 10)
        val goodSamples = (1..200).map { ordinal ->
            val logTimeMs = ordinal * 1_000L
            val jitter = jitterMs[ordinal % jitterMs.size]
            CaptureSyncSample(ordinal, logTimeMs, logTimeMs + trueOffsetMs + jitter)
        }
        val glitchRow = 201
        val glitchLogTimeMs = glitchRow * 1_000L
        val glitched = CaptureSyncSample(glitchRow, glitchLogTimeMs, glitchLogTimeMs - 50_000L)
        val samples = goodSamples + glitched

        val anchor = estimateCaptureSyncAnchor(samples, videoDurationMs = 400_000L)

        requireNotNull(anchor)
        val impliedOffset = anchor.videoMs - (samples.first { it.ordinal == anchor.row }.logTimeMs!!)
        assertTrue(
            impliedOffset in 285L..315L,
            "expected an offset close to the true 300ms latency, got $impliedOffset (anchor=$anchor)",
        )
    }

    @Test
    fun rowsWithoutTimestampsAreIgnoredForBothTheOffsetAndTheAnchorRow() {
        val samples = listOf(
            // No parsed timestamp (e.g. a brief/RAW-format row) but a known video position — must
            // not contribute an offset sample, and can never be picked as the anchor row.
            CaptureSyncSample(ordinal = 1, logTimeMs = null, videoMs = 900L),
            CaptureSyncSample(ordinal = 2, logTimeMs = 2_000L, videoMs = 2_100L),
            CaptureSyncSample(ordinal = 3, logTimeMs = 3_000L, videoMs = 3_100L),
        )

        val anchor = estimateCaptureSyncAnchor(samples, videoDurationMs = 10_000L)

        requireNotNull(anchor)
        assertEquals(2, anchor.row)
        assertEquals(2_100L, anchor.videoMs)
    }

    @Test
    fun noVideoAtAllYieldsNoAnchor() {
        val samples = listOf(
            CaptureSyncSample(ordinal = 1, logTimeMs = 1_000L, videoMs = null),
            CaptureSyncSample(ordinal = 2, logTimeMs = 2_000L, videoMs = null),
        )

        assertNull(estimateCaptureSyncAnchor(samples, videoDurationMs = null))
    }

    @Test
    fun emptySamplesYieldsNoAnchor() {
        assertNull(estimateCaptureSyncAnchor(emptyList()))
    }

    @Test
    fun anchorPrefersARowWhosePredictedPositionActuallyFallsInsideVideoCoverage() {
        // Row 1 was logged well before the video started (a real, common shape: the recorder starts
        // logcat slightly ahead of scrcpy) — its predicted position would be negative. Row 2 is the
        // first row that actually lands inside the video. The estimator should skip row 1 in favor
        // of row 2 even though row 1 has the lower ordinal.
        val samples = listOf(
            CaptureSyncSample(ordinal = 1, logTimeMs = 1_000L, videoMs = null),
            CaptureSyncSample(ordinal = 2, logTimeMs = 5_000L, videoMs = 200L),
            CaptureSyncSample(ordinal = 3, logTimeMs = 6_000L, videoMs = 1_200L),
        )

        val anchor = estimateCaptureSyncAnchor(samples, videoDurationMs = 5_000L)

        requireNotNull(anchor)
        assertEquals(2, anchor.row)
    }

    @Test
    fun fallsBackToTheFirstTimestampedRowClampedWhenNoPredictionLandsInsideCoverage() {
        val samples = listOf(
            CaptureSyncSample(ordinal = 1, logTimeMs = 1_000L, videoMs = 100L),
            CaptureSyncSample(ordinal = 2, logTimeMs = 2_000L, videoMs = 1_100L),
        )
        // True offset is ~-900ms (video started ~900ms into the log); with a tiny durationMs bound,
        // every row's prediction lands outside 0..durationMs. Still must return something sane
        // (never throw, never a negative/over-duration videoMs).
        val anchor = estimateCaptureSyncAnchor(samples, videoDurationMs = 50L)

        requireNotNull(anchor)
        assertEquals(1, anchor.row)
        assertTrue(anchor.videoMs in 0..50L)
    }
}
