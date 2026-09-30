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

    @Test
    fun bufferedRowsFromAnEarlierDayNeverBecomeTheAnchor() {
        // Shaped like a capture started with "include earlier device logs": 5_000 buffered rows
        // spread over the previous afternoon (11:06 -> 18:41, no video position), then the live
        // session at 12:15:28 the next day. The timestamps carry no date and 18:41 -> 12:15 is only
        // a 6.4 h backwards step, which unrollLogTimeline does NOT read as a midnight rollover — so
        // the session's logTimeMs lands INSIDE the buffered block's numeric range, and buffered rows
        // just after 12:15:28 predict a perfectly valid-looking video position. Video started 984 ms
        // into the session; session rows come every 100 ms with a 40 ms transport lag.
        val bufferedCount = 5_000
        val bufferedStartMs = 11 * HOUR_MS + 6 * MINUTE_MS
        val bufferedSpanMs = 7 * HOUR_MS + 35 * MINUTE_MS
        val buffered = (1..bufferedCount).map { i ->
            CaptureSyncSample(
                ordinal = i,
                logTimeMs = bufferedStartMs + (i - 1) * bufferedSpanMs / (bufferedCount - 1),
                videoMs = null,
            )
        }
        val sessionStartLogMs = 12 * HOUR_MS + 15 * MINUTE_MS + 28_000L
        val session = (0 until 520).map { j ->
            val elapsed = j * 100L
            CaptureSyncSample(
                ordinal = bufferedCount + 1 + j,
                logTimeMs = sessionStartLogMs + elapsed,
                videoMs = (elapsed - 984L + 40L).takeIf { it >= 0 },
            )
        }
        val samples = buffered + session

        val anchor = estimateCaptureSyncAnchor(samples, videoDurationMs = 90_000L)

        requireNotNull(anchor)
        assertTrue(anchor.row > bufferedCount, "anchor must be a recorded session row, got row ${anchor.row}")
        val anchorSample = samples.first { it.ordinal == anchor.row }
        assertTrue(anchorSample.videoMs != null, "anchor row must have been recorded with a video position")
        // Session row j=10 (elapsed 1_000 ms) is the first recorded row: predicted video position 56 ms.
        assertEquals(bufferedCount + 1 + 10, anchor.row)
        assertEquals(anchorSample.videoMs, anchor.videoMs)
        assertTrue(anchor.videoMs in 0..2_000L, "predicted video position should be near the start, got ${anchor.videoMs}")
    }

    @Test
    fun aBufferedRowPredictingAnInRangePositionIsSkippedForTheFirstRecordedRow() {
        // The buffered row's (wrongly ordered) logTimeMs lands exactly where a recorded row would,
        // and it has the lowest ordinal — the old timestamp-only choice picked it.
        val samples = listOf(
            CaptureSyncSample(ordinal = 1, logTimeMs = 5_500L, videoMs = null),
            CaptureSyncSample(ordinal = 2, logTimeMs = 5_000L, videoMs = 100L),
            CaptureSyncSample(ordinal = 3, logTimeMs = 6_000L, videoMs = 1_100L),
        )

        val anchor = estimateCaptureSyncAnchor(samples, videoDurationMs = 5_000L)

        assertEquals(CaptureSyncAnchor(row = 2, videoMs = 100L), anchor)
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
    }
}
