@file:Suppress("MagicNumber")

package com.indagium.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SAMPLE_RATE_HZ = 48_000
private const val FRAME_SIZE = 960 // 20ms at 48kHz, matching OpusPcmEncoder's own frame size
private const val CHUNK_DURATION_US = 20_000L
private const val TOLERANCE_MS = 50L

/**
 * Exercises [DeviceAudioContinuityClock] — the fix for the crackle heard in every real capture that
 * mixed microphone and device audio: scrcpy's own AudioRecord timestamps jitter by a few
 * milliseconds between consecutive 20ms Opus packets, and offering that jitter straight to
 * [TimelinePcmMixer] either sums the overlap or leaves a tiny silent gap — both audible as a click.
 */
class DeviceAudioContinuityClockTest {
    @Test
    fun jitteredPtsWithinToleranceSnapsToContiguousPlacement() {
        val clock = DeviceAudioContinuityClock(SAMPLE_RATE_HZ, toleranceUs = TOLERANCE_MS * 1_000L)
        // A realistic run of 20ms chunks whose reported pts jitters by a few ms around the true
        // schedule (device timestamp noise), never drifting outside the tolerance.
        val jitterUs = longArrayOf(0L, 3_000L, -2_000L, 4_000L, -5_000L, 1_000L, -1_000L, 2_500L)
        var trueScheduleUs = 0L
        var previousEndUs = -1L
        jitterUs.forEach { jitter ->
            val observedUs = trueScheduleUs + jitter
            val placedUs = clock.continuousStartUs(observedUs, FRAME_SIZE)
            assertEquals(trueScheduleUs, placedUs, "jitter of ${jitter}us must be absorbed, not passed through")
            assertTrue(placedUs >= previousEndUs, "consecutive chunks from the same source must never overlap themselves")
            previousEndUs = placedUs + CHUNK_DURATION_US
            trueScheduleUs += CHUNK_DURATION_US
        }
    }

    @Test
    fun forwardGapBeyondToleranceIsHonoredImmediatelyNotSteppedToward() {
        val clock = DeviceAudioContinuityClock(SAMPLE_RATE_HZ, toleranceUs = TOLERANCE_MS * 1_000L)
        assertEquals(0L, clock.continuousStartUs(0L, FRAME_SIZE))
        // The device stopped sending for 3 real seconds (a static screen, exactly like the video
        // gap on the player side) — the very next chunk's pts jumps forward by that whole gap and
        // must be honored in one step, not glued to the end of the silence over many buffers the
        // way a gradual (MonotonicMicrophoneSampleClock-style) resync would.
        val gapStartUs = 3_020_000L
        assertEquals(gapStartUs, clock.continuousStartUs(gapStartUs, FRAME_SIZE))
    }

    @Test
    fun slightlyDecreasingPtsWithinToleranceSnapsForwardInsteadOfOverlappingItself() {
        val clock = DeviceAudioContinuityClock(SAMPLE_RATE_HZ, toleranceUs = TOLERANCE_MS * 1_000L)
        assertEquals(0L, clock.continuousStartUs(0L, FRAME_SIZE))
        // A chunk whose own reported pts is slightly BEFORE where the previous one's contiguous
        // placement ended (jitter going backwards a couple of ms) must still land at the contiguous
        // boundary — summing it with the tail of the previous chunk is exactly the click this fixes.
        val decreasedObservedUs = CHUNK_DURATION_US - 2_000L
        assertEquals(CHUNK_DURATION_US, clock.continuousStartUs(decreasedObservedUs, FRAME_SIZE))
    }

    @Test
    fun largeBackwardJumpBeyondToleranceReAnchorsImmediately() {
        val clock = DeviceAudioContinuityClock(SAMPLE_RATE_HZ, toleranceUs = TOLERANCE_MS * 1_000L)
        assertEquals(1_000_000L, clock.continuousStartUs(1_000_000L, FRAME_SIZE))
        // A genuine backward discontinuity (a decoder/stream reset) is honored immediately, same as
        // a forward gap — this clock's only job is removing ORDINARY jitter, not guaranteeing
        // monotonicity against an anomaly; TimelinePcmMixer's own dedupe against what it has already
        // emitted is what protects the timeline from actually playing this out of order.
        assertEquals(200_000L, clock.continuousStartUs(200_000L, FRAME_SIZE))
    }

    @Test
    fun resetForgetsTheRunningExpectation() {
        val clock = DeviceAudioContinuityClock(SAMPLE_RATE_HZ, toleranceUs = TOLERANCE_MS * 1_000L)
        assertEquals(0L, clock.continuousStartUs(0L, FRAME_SIZE))
        clock.reset()
        // After a config/stream restart, a fresh low pts must be honored directly rather than being
        // judged as a huge backward jump relative to the OLD stream's running expectation.
        assertEquals(100_000L, clock.continuousStartUs(100_000L, FRAME_SIZE))
    }
}
