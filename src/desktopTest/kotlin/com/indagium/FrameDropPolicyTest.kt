package com.indagium

import com.indagium.video.shouldDropLateFrame
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * shouldDropLateFrame is the pacing decision behind two of the video-playback fixes: dropping a
 * frame that's fallen meaningfully behind the (real wall-clock) playback clock instead of always
 * paying for its conversion, and doing so without ever starving the picture of updates entirely.
 * Pure and FFmpeg-free by design (like PlaybackTimeline) so it's testable without native video
 * libraries — see its own KDoc in VideoPlayerController.kt.
 *
 * The literal thresholds below mirror FRAME_DROP_LATENESS_US (100_000L) and
 * MAX_CONSECUTIVE_FRAME_DROPS (15), which are private to VideoPlayerController.kt.
 */
class FrameDropPolicyTest {
    @Test
    fun aFrameWithinToleranceIsNeverDropped() {
        assertFalse(shouldDropLateFrame(latenessUs = 0, consecutiveDrops = 0))
        assertFalse(shouldDropLateFrame(latenessUs = -50_000, consecutiveDrops = 0)) // frame is early
        assertFalse(shouldDropLateFrame(latenessUs = 100_000, consecutiveDrops = 0)) // exactly at threshold
    }

    @Test
    fun aFrameMeaningfullyBehindTheClockIsDropped() {
        assertTrue(shouldDropLateFrame(latenessUs = 100_001, consecutiveDrops = 0))
        assertTrue(shouldDropLateFrame(latenessUs = 1_000_000, consecutiveDrops = 0))
    }

    @Test
    fun droppingStopsAfterTheSafetyValveSoThePictureAlwaysEventuallyUpdates() {
        // Still within the allowed run of consecutive drops -> keep dropping.
        assertTrue(shouldDropLateFrame(latenessUs = 1_000_000, consecutiveDrops = 14))
        // The 15th frame in a row is forced to show regardless of how late it is, so a persistently
        // overloaded pipeline (or a decode stall) can never leave the picture frozen indefinitely.
        assertFalse(shouldDropLateFrame(latenessUs = 1_000_000, consecutiveDrops = 15))
        assertFalse(shouldDropLateFrame(latenessUs = 1_000_000, consecutiveDrops = 100))
    }

    // This is the mechanism that makes setRate(2f) actually play faster instead of being a no-op:
    // currentClockUs() advances at 2x real wall-clock speed, so a decode pipeline whose per-frame
    // cost only just keeps up with the source's natural frame rate at 1x cannot keep up with a
    // rate-2x budget, and lateness grows every frame until frames start getting dropped to catch
    // back up. (The wait/sleep step that normally paces presentation is irrelevant once a pipeline
    // is genuinely behind, since it returns immediately for an already-late frame — this test
    // reproduces exactly that "already late on arrival" condition.)
    @Test
    fun rateTwoEventuallyDropsFramesForAPipelineThatOnlyJustKeepsUpAtRateOne() {
        val frameIntervalUs = 33_000L // 30fps source
        val pipelineCostUs = 33_000L // decode+convert cost per frame: exactly matches 1x budget
        val rate = 2

        var elapsedRealUs = 0L
        var consecutiveDrops = 0
        var droppedAnyFrame = false
        for (frameIndex in 1..20) {
            elapsedRealUs += pipelineCostUs
            val clockUs = elapsedRealUs * rate
            val frameTimestampUs = frameIndex * frameIntervalUs
            val latenessUs = clockUs - frameTimestampUs
            if (shouldDropLateFrame(latenessUs, consecutiveDrops)) {
                droppedAnyFrame = true
                consecutiveDrops++
            } else {
                consecutiveDrops = 0
            }
        }

        assertTrue(droppedAnyFrame)
    }

    // The mirror image at rate=0.5: the same pipeline cost that's borderline at 1x has DOUBLE the
    // real-time budget per source frame, so lateness trends negative (frames arrive early relative
    // to the slowed-down clock) and nothing needs to be dropped. Confirms the drop path introduced
    // for B does not regress the slow-down direction that already worked before this fix.
    @Test
    fun rateHalfNeverDropsFramesForTheSamePipeline() {
        val frameIntervalUs = 33_000L
        val pipelineCostUs = 33_000L
        val rate = 0.5

        var elapsedRealUs = 0L
        var consecutiveDrops = 0
        var droppedAnyFrame = false
        for (frameIndex in 1..20) {
            elapsedRealUs += pipelineCostUs
            val clockUs = (elapsedRealUs * rate).toLong()
            val frameTimestampUs = frameIndex * frameIntervalUs
            val latenessUs = clockUs - frameTimestampUs
            if (shouldDropLateFrame(latenessUs, consecutiveDrops)) {
                droppedAnyFrame = true
                consecutiveDrops++
            } else {
                consecutiveDrops = 0
            }
        }

        assertFalse(droppedAnyFrame)
    }
}
