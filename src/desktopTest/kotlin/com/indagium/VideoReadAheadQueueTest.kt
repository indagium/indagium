package com.indagium

import com.indagium.video.audioBufferedAheadUs
import com.indagium.video.audioLineBufferClampWarning
import com.indagium.video.queuedVideoDurationAheadUs
import com.indagium.video.shouldDropLateFrame
import com.indagium.video.shouldReadAhead
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the pure decision points behind decode-ahead in video/VideoPlayerController.kt —
 * `shouldReadAhead`/`queuedVideoDurationAheadUs`/`audioBufferedAheadUs` (the read-ahead queue's fill
 * bound, now audio-aware — see `shouldReadAhead`'s KDoc for why the video-only soft bound alone let
 * audio starve periodically), `shouldDropLateFrame` as applied to a queue that's been allowed to
 * grow a real backlog (its existing coverage in FrameDropPolicyTest is per single frame, not per
 * drained queue), and `audioLineBufferClampWarning` (the SourceDataLine buffer-size readback). All
 * FFmpeg-free and decode-thread-free, like FrameDropPolicyTest/VideoAudioDropoutTest, for the same
 * reason: none of this needs a real grabber, a real audio device, or the controller's own thread to
 * be exercised.
 *
 * The literal thresholds below mirror MAX_QUEUED_VIDEO_FRAMES (16), MAX_QUEUED_VIDEO_DURATION_US
 * (400_000L), HARD_MAX_QUEUED_VIDEO_FRAMES (64), AUDIO_READ_AHEAD_TARGET_US (250_000L), and
 * AUDIO_BUFFER_CLAMP_WARNING_RATIO (0.9), which are private to VideoPlayerController.kt.
 *
 * `audioBufferedAheadUs` far above [AUDIO_READ_AHEAD_TARGET_US] (250_000L) below stands in for the
 * caller-side "fully satisfied" sentinel ([Long.MAX_VALUE], used for no-audio-stream/muted — see
 * their own section) without depending on that exact constant.
 */
private const val AUDIO_FULLY_SATISFIED_US = 1_000_000L

class VideoReadAheadQueueTest {
    // ── queuedVideoDurationAheadUs ────────────────────────────────────

    @Test
    fun aheadDurationIsTheGapBetweenTheClockAndTheNewestQueuedFrame() {
        assertEquals(500_000L, queuedVideoDurationAheadUs(currentClockUs = 1_000_000L, newestQueuedTimestampUs = 1_500_000L))
    }

    @Test
    fun aheadDurationIsZeroForAnEmptyOrJustCaughtUpQueue() {
        assertEquals(0L, queuedVideoDurationAheadUs(currentClockUs = 1_000_000L, newestQueuedTimestampUs = 1_000_000L))
    }

    @Test
    fun aheadDurationNeverGoesNegativeWhenTheClockHasAlreadyPassedTheNewestQueuedFrame() {
        // Can happen right after a burst of drops: the clock outran everything still queued.
        assertEquals(0L, queuedVideoDurationAheadUs(currentClockUs = 2_000_000L, newestQueuedTimestampUs = 1_500_000L))
    }

    // ── shouldReadAhead: video-only soft bound (audio already fully satisfied) ───────────────────
    // These mirror the pre-existing coverage of the video-only bound, now passing an audio term that
    // reads as "fully satisfied" so it never overrides the soft video bound below — i.e. the exact
    // behavior a no-audio-stream/muted file gets (see the dedicated section further down).

    @Test
    fun anEmptyQueueAlwaysReadsAheadAtLeastOnce() {
        // Otherwise the very first frame after play()/seek() could never get queued at all.
        assertTrue(shouldReadAhead(queuedFrameCount = 0, queuedDurationAheadUs = 0L, audioBufferedAheadUs = AUDIO_FULLY_SATISFIED_US))
    }

    @Test
    fun refillingStopsOnceTheFrameCountBoundIsReachedEvenWithBudgetLeftOnDuration() {
        assertFalse(shouldReadAhead(queuedFrameCount = 16, queuedDurationAheadUs = 0L, audioBufferedAheadUs = AUDIO_FULLY_SATISFIED_US))
        assertTrue(shouldReadAhead(queuedFrameCount = 15, queuedDurationAheadUs = 0L, audioBufferedAheadUs = AUDIO_FULLY_SATISFIED_US))
    }

    @Test
    fun refillingStopsOnceTheDurationBoundIsReachedEvenWithBudgetLeftOnCount() {
        // A low-frame-rate source could otherwise buffer several real seconds of video on the count
        // bound alone — this is what stops that.
        assertFalse(shouldReadAhead(queuedFrameCount = 1, queuedDurationAheadUs = 400_000L, audioBufferedAheadUs = AUDIO_FULLY_SATISFIED_US))
        assertTrue(shouldReadAhead(queuedFrameCount = 1, queuedDurationAheadUs = 399_999L, audioBufferedAheadUs = AUDIO_FULLY_SATISFIED_US))
    }

    @Test
    fun bothBoundsMustBeUnderTheirLimitToKeepReadingAhead() {
        assertTrue(shouldReadAhead(queuedFrameCount = 5, queuedDurationAheadUs = 100_000L, audioBufferedAheadUs = AUDIO_FULLY_SATISFIED_US))
        assertFalse(shouldReadAhead(queuedFrameCount = 20, queuedDurationAheadUs = 500_000L, audioBufferedAheadUs = AUDIO_FULLY_SATISFIED_US))
    }

    // This is the "no audio stream" / "muted by rate" guard, expressed directly at the pure-function
    // level rather than through FfmpegVideoPlayerController.currentAudioBufferedAheadUs (which has no
    // seam to unit test without a real grabber/audio device) — the caller substitutes exactly this
    // sentinel for both cases, so shouldReadAhead behaves for a video-only or rate-muted file exactly
    // as it did before this change: bounded purely by the soft video bound.
    @Test
    fun videoOnlyOrMutedAudioBehavesExactlyLikeTheOldVideoOnlyBound() {
        assertTrue(shouldReadAhead(queuedFrameCount = 0, queuedDurationAheadUs = 0L, audioBufferedAheadUs = Long.MAX_VALUE))
        assertFalse(shouldReadAhead(queuedFrameCount = 16, queuedDurationAheadUs = 0L, audioBufferedAheadUs = Long.MAX_VALUE))
        assertTrue(shouldReadAhead(queuedFrameCount = 15, queuedDurationAheadUs = 0L, audioBufferedAheadUs = Long.MAX_VALUE))
        assertFalse(shouldReadAhead(queuedFrameCount = 1, queuedDurationAheadUs = 400_000L, audioBufferedAheadUs = Long.MAX_VALUE))
    }

    // Even with an unbounded-looking audio sentinel, a video-only/muted file must never run away past
    // the hard cap either — Long.MAX_VALUE is always >= AUDIO_READ_AHEAD_TARGET_US, so it takes the
    // ordinary soft-bound branch, but this pins down that the hard-cap check still short-circuits
    // first regardless.
    @Test
    fun videoOnlyOrMutedAudioIsStillBoundedByTheHardCapAsABackstop() {
        assertFalse(shouldReadAhead(queuedFrameCount = 64, queuedDurationAheadUs = 0L, audioBufferedAheadUs = Long.MAX_VALUE))
    }

    // ── shouldReadAhead: the audio-priority override (the regression case) ───────────────────────

    // The bug this whole change fixes: refill used to stop the instant the video queue hit its soft
    // bound, even while audio sat well under its own target — starving the line for the duration of
    // whatever audio chunk sat just past the video packets that filled the queue. Audio being under
    // target must now override the soft video bound.
    @Test
    fun videoQueueAtSoftBoundButAudioBelowTargetKeepsReadingAhead() {
        assertTrue(
            shouldReadAhead(queuedFrameCount = 16, queuedDurationAheadUs = 400_000L, audioBufferedAheadUs = 0L),
        )
        assertTrue(
            shouldReadAhead(queuedFrameCount = 30, queuedDurationAheadUs = 900_000L, audioBufferedAheadUs = 249_999L),
        )
    }

    // Once audio reaches its target, the override lifts and the ordinary soft video bound applies
    // again — an already-at-bound queue stops.
    @Test
    fun videoQueueAtSoftBoundAndAudioAtOrAboveTargetStops() {
        assertFalse(shouldReadAhead(queuedFrameCount = 16, queuedDurationAheadUs = 0L, audioBufferedAheadUs = 250_000L))
        assertFalse(shouldReadAhead(queuedFrameCount = 1, queuedDurationAheadUs = 400_000L, audioBufferedAheadUs = 250_000L))
    }

    // The hard cap is an unconditional backstop: even while audio is completely unfed (0us buffered,
    // the worst case the override exists for), refill must still stop once the hard cap is hit.
    @Test
    fun hardCapStopsRefillingRegardlessOfHowStarvedAudioIs() {
        assertFalse(shouldReadAhead(queuedFrameCount = 64, queuedDurationAheadUs = 0L, audioBufferedAheadUs = 0L))
        assertTrue(shouldReadAhead(queuedFrameCount = 63, queuedDurationAheadUs = 0L, audioBufferedAheadUs = 0L))
    }

    // Audio just under target at a video queue count/duration that's WAY past the old soft bound but
    // still under the hard cap: the override keeps going all the way up to (but not past) the cap.
    @Test
    fun theOverrideCanPushTheQueueWellPastTheSoftBoundButNeverPastTheHardCap() {
        assertTrue(shouldReadAhead(queuedFrameCount = 40, queuedDurationAheadUs = 2_000_000L, audioBufferedAheadUs = 100_000L))
        assertFalse(shouldReadAhead(queuedFrameCount = 64, queuedDurationAheadUs = 2_000_000L, audioBufferedAheadUs = 100_000L))
    }

    // ── audioBufferedAheadUs: the bytes-to-microseconds conversion ───────────────────────────────

    @Test
    fun convertsBufferedBytesToMicrosecondsGivenSampleRateAndFrameSize() {
        // 48_000Hz stereo 16-bit: frameBytes = 2 channels * 2 bytes = 4. 48_000 bytes -> 12_000 frames
        // -> 12_000 / 48_000 s = exactly 250_000us (250ms) of buffered audio.
        assertEquals(250_000L, audioBufferedAheadUs(bufferedBytes = 48_000L, sampleRate = 48_000, frameBytes = 4))
        // Half that many bytes -> half the buffered duration.
        assertEquals(125_000L, audioBufferedAheadUs(bufferedBytes = 24_000L, sampleRate = 48_000, frameBytes = 4))
    }

    @Test
    fun roundsDownToAWholeSampleFrameRatherThanOverstatingAPartialOne() {
        // 3 bytes is 0.75 of one 4-byte frame at 48kHz stereo — not even one whole frame buffered, so
        // this must read as 0us rather than rounding up.
        assertEquals(0L, audioBufferedAheadUs(bufferedBytes = 3L, sampleRate = 48_000, frameBytes = 4))
    }

    @Test
    fun zeroBufferedBytesIsZeroMicroseconds() {
        assertEquals(0L, audioBufferedAheadUs(bufferedBytes = 0L, sampleRate = 48_000, frameBytes = 4))
    }

    @Test
    fun invalidSampleRateOrFrameSizeReturnsZeroRatherThanDividingByZero() {
        assertEquals(0L, audioBufferedAheadUs(bufferedBytes = 48_000L, sampleRate = 0, frameBytes = 4))
        assertEquals(0L, audioBufferedAheadUs(bufferedBytes = 48_000L, sampleRate = -1, frameBytes = 4))
        assertEquals(0L, audioBufferedAheadUs(bufferedBytes = 48_000L, sampleRate = 48_000, frameBytes = 0))
        assertEquals(0L, audioBufferedAheadUs(bufferedBytes = 48_000L, sampleRate = 48_000, frameBytes = -1))
    }

    @Test
    fun negativeBufferedBytesReturnsZeroRatherThanGoingNegative() {
        assertEquals(0L, audioBufferedAheadUs(bufferedBytes = -100L, sampleRate = 48_000, frameBytes = 4))
    }

    // ── shouldDropLateFrame, applied to draining a queue that built up a real backlog ──────────

    // Decode-ahead's whole point is to let the queue build a backlog across a stall (an expensive
    // keyframe, a GC pause) rather than losing audio feed during it. This simulates exactly that:
    // several queued frames, all now behind the clock by the time the pipeline catches up and starts
    // draining them, and confirms shouldDropLateFrame's existing per-frame policy still does the
    // right thing when applied at the queue's drain point — dropping the ones far enough behind,
    // never getting stuck, and still bounded by the same consecutive-drop safety valve.
    @Test
    fun drainingAQueueThatBuiltUpABacklogDropsOnlyTheFramesFarEnoughBehind() {
        val clockUs = 5_000_000L
        // Ten queued frames spaced 33ms apart (30fps), ending just behind the clock: the oldest is
        // ~330ms behind (comfortably over FRAME_DROP_LATENESS_US=100ms), the newest is only ~33ms
        // behind (within tolerance).
        val queuedTimestampsUs = (0 until 10).map { clockUs - 330_000L + it * 33_000L }

        var consecutiveDrops = 0
        var shown = 0
        var dropped = 0
        for (timestampUs in queuedTimestampsUs) {
            val latenessUs = clockUs - timestampUs
            if (shouldDropLateFrame(latenessUs, consecutiveDrops)) {
                dropped++
                consecutiveDrops++
            } else {
                shown++
                consecutiveDrops = 0
            }
        }

        assertTrue(dropped > 0, "expected the stale front of the backlog to be dropped")
        assertTrue(shown > 0, "expected the queue to catch back up to frames within tolerance")
        // The queue must still end up presenting its most recent, in-tolerance frames rather than
        // getting stuck showing only the first (stalest) one it finds acceptable.
        assertEquals(shown, queuedTimestampsUs.count { clockUs - it <= 100_000L })
    }

    @Test
    fun aPersistentlyOverloadedDrainNeverFreezesThePictureIndefinitely() {
        // 50 queued frames, ALL still far behind the clock (a pipeline that fell badly behind) —
        // the safety valve must still force one through at least every MAX_CONSECUTIVE_FRAME_DROPS.
        val clockUs = 10_000_000L
        val queuedTimestampsUs = (0 until 50).map { 0L } // every one is ~10s late

        var consecutiveDrops = 0
        var shown = 0
        for (timestampUs in queuedTimestampsUs) {
            val latenessUs = clockUs - timestampUs
            if (shouldDropLateFrame(latenessUs, consecutiveDrops)) {
                consecutiveDrops++
            } else {
                shown++
                consecutiveDrops = 0
            }
        }

        assertTrue(shown > 0, "expected the safety valve to force at least one frame through")
        // 50 frames / (15 drops + 1 shown) per cycle rounds to at least 3 forced-through frames.
        assertTrue(shown >= 3, "expected roughly one shown frame per 16-frame run, got $shown")
    }

    // ── audioLineBufferClampWarning ─────────────────────────────────────

    @Test
    fun noWarningWhenTheActualBufferIsAtOrAboveWhatWasRequested() {
        assertNull(audioLineBufferClampWarning(requestedBytes = 52_920, actualBytes = 52_920))
        assertNull(audioLineBufferClampWarning(requestedBytes = 52_920, actualBytes = 60_000))
    }

    @Test
    fun noWarningForOrdinaryRoundingToADeviceNativeChunkSize() {
        // Within 90% of requested — a few bytes/percent of rounding, not a meaningful clamp.
        assertNull(audioLineBufferClampWarning(requestedBytes = 52_920, actualBytes = 50_000))
    }

    @Test
    fun warnsWhenTheActualBufferIsMateriallySmallerThanRequested() {
        val message = audioLineBufferClampWarning(requestedBytes = 52_920, actualBytes = 4_096)
        assertTrue(message != null && message.contains("52920") && message.contains("4096"))
    }

    @Test
    fun noWarningWhenNothingMeaningfulWasEverRequested() {
        assertNull(audioLineBufferClampWarning(requestedBytes = 0, actualBytes = 100))
        assertNull(audioLineBufferClampWarning(requestedBytes = -1, actualBytes = 100))
    }
}
