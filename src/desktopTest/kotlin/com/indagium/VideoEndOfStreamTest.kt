package com.indagium

import com.indagium.video.shouldReportPlaybackStopped
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises [shouldReportPlaybackStopped] — the pure decision behind the "trailing audio outlives
 * the last video packet" fix in video/VideoPlayerController.kt (a scrcpy capture sends no video
 * while the screen is static, so its audio track can run for up to a second or more past the last
 * video packet). Kept independent of the decode thread/audio device, like VideoAudioDropoutTest and
 * FrameDropPolicyTest.
 */
class VideoEndOfStreamTest {
    @Test
    fun neverStopsBeforeTheGrabberIsExhaustedOrTheQueueHasDrained() {
        assertFalse(
            shouldReportPlaybackStopped(
                grabberExhausted = false,
                videoQueueEmpty = true,
                remainingAudioBufferedUs = 0L,
                clockUs = 10_000_000L,
                durationUs = 10_000L,
            ),
        )
        assertFalse(
            shouldReportPlaybackStopped(
                grabberExhausted = true,
                videoQueueEmpty = false,
                remainingAudioBufferedUs = 0L,
                clockUs = 10_000_000L,
                durationUs = 10_000L,
            ),
        )
    }

    @Test
    fun keepsPlayingWhileTrailingAudioIsStillBuffered() {
        // Grabber exhausted, queue drained, duration already reached — the old bug's exact
        // scenario: only remaining buffered audio should be able to hold this open.
        assertFalse(
            shouldReportPlaybackStopped(
                grabberExhausted = true,
                videoQueueEmpty = true,
                remainingAudioBufferedUs = 1L,
                clockUs = 11_574_000L,
                durationUs = 11_574_000L,
            ),
        )
    }

    @Test
    fun keepsPlayingUntilTheClockReachesTheDeclaredDurationEvenWithNoAudioLeft() {
        // The video-only-tail case: audio has drained (or there was never an audio stream), but the
        // container's own declared duration hasn't been reached yet — the slider should still climb
        // to the end instead of stalling on the last video frame's timestamp.
        assertFalse(
            shouldReportPlaybackStopped(
                grabberExhausted = true,
                videoQueueEmpty = true,
                remainingAudioBufferedUs = 0L,
                clockUs = 10_552_000L,
                durationUs = 11_574_000L,
            ),
        )
    }

    @Test
    fun stopsOnceAudioHasDrainedAndTheClockHasReachedTheDeclaredDuration() {
        assertTrue(
            shouldReportPlaybackStopped(
                grabberExhausted = true,
                videoQueueEmpty = true,
                remainingAudioBufferedUs = 0L,
                clockUs = 11_574_000L,
                durationUs = 11_574_000L,
            ),
        )
        // The clock is also allowed to have moved past the declared duration.
        assertTrue(
            shouldReportPlaybackStopped(
                grabberExhausted = true,
                videoQueueEmpty = true,
                remainingAudioBufferedUs = 0L,
                clockUs = 11_700_000L,
                durationUs = 11_574_000L,
            ),
        )
    }

    @Test
    fun stopsImmediatelyWhenNoDurationIsKnownAndAudioHasDrained() {
        // durationUs <= 0 means no known upper bound (VideoSeekReadiness.UNAVAILABLE/DISCOVERING) —
        // must not wait forever for a bound that will never arrive.
        assertTrue(
            shouldReportPlaybackStopped(
                grabberExhausted = true,
                videoQueueEmpty = true,
                remainingAudioBufferedUs = 0L,
                clockUs = 5_000_000L,
                durationUs = 0L,
            ),
        )
    }
}
