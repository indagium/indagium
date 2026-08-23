package com.indagium

import com.indagium.video.DecodedStateKind
import com.indagium.video.PlaybackTransition
import com.indagium.video.invalidatesDecodedState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises `invalidatesDecodedState` — the pure lookup table video/VideoPlayerController.kt's
 * `pause()`, `applyPendingSeek()`, `releaseAll()`, and the rate-change paths all defer to, so the
 * "is this transition a discontinuity?" decision lives in one tested place rather than four
 * separately-reasoned-about call sites. Pure and FFmpeg-free, like FrameDropPolicyTest, so this is
 * testable without a decode thread, an audio device, or a video file.
 *
 * This is the regression test for the specific bug fixed here: decode-ahead means the grabber sits
 * up to ~400ms ahead of the last frame actually shown (call that position C) in steady state. A
 * pause used to discard everything downstream of C (flush the audio line, clear pendingAudioTail,
 * clear the video queue) — so on resume, play() rebased the clock to C, but the first available
 * video frame was C+400ms and the just-flushed line had nothing buffered at all: a ~400ms frozen
 * picture, and audio jumping ahead of the video it should have accompanied. A pause is NOT a
 * discontinuity in the stream — only a seek (or a close) is; the fix is exactly the PAUSE row below
 * being false for both kinds, while SEEK/CLOSE stay true for both.
 */
class VideoPlaybackTransitionTest {
    @Test
    fun pauseInvalidatesNeitherTheVideoQueueNorAudioCarryover() {
        // This is the pause/resume-without-drift invariant: nothing decode-ahead already produced
        // becomes stale merely because the clock stopped advancing.
        assertFalse(invalidatesDecodedState(PlaybackTransition.PAUSE, DecodedStateKind.VIDEO_QUEUE))
        assertFalse(invalidatesDecodedState(PlaybackTransition.PAUSE, DecodedStateKind.AUDIO_CARRYOVER))
    }

    @Test
    fun seekInvalidatesBothTheVideoQueueAndAudioCarryover() {
        // A seek makes "the current position" mean something else entirely — anything already
        // decoded for the OLD position must not survive it.
        assertTrue(invalidatesDecodedState(PlaybackTransition.SEEK, DecodedStateKind.VIDEO_QUEUE))
        assertTrue(invalidatesDecodedState(PlaybackTransition.SEEK, DecodedStateKind.AUDIO_CARRYOVER))
    }

    @Test
    fun closeInvalidatesBothTheVideoQueueAndAudioCarryover() {
        // The whole pipeline is being torn down; nothing already decoded has anywhere left to play.
        assertTrue(invalidatesDecodedState(PlaybackTransition.CLOSE, DecodedStateKind.VIDEO_QUEUE))
        assertTrue(invalidatesDecodedState(PlaybackTransition.CLOSE, DecodedStateKind.AUDIO_CARRYOVER))
    }

    @Test
    fun rateChangeInvalidatesAudioCarryoverButNotTheVideoQueue() {
        // The one row where the two kinds genuinely diverge. Audio has no in-between state — it's
        // written verbatim at rate == 1 or muted entirely otherwise (setRate's KDoc) — so a carried-
        // over fragment written under the OLD rate assumption is simply wrong once the rate changes,
        // in either direction. A queued video frame has no such rate-dependent identity: it is still
        // the correct picture for its own timestamp regardless of playback speed — only whether
        // shouldDropLateFrame shows or skips it changes, not the frame's own validity.
        assertTrue(invalidatesDecodedState(PlaybackTransition.RATE_CHANGE, DecodedStateKind.AUDIO_CARRYOVER))
        assertFalse(invalidatesDecodedState(PlaybackTransition.RATE_CHANGE, DecodedStateKind.VIDEO_QUEUE))
    }

    @Test
    fun onlySeekAndCloseAreTrueDiscontinuitiesForTheVideoQueue() {
        val transitionsThatInvalidateTheQueue = PlaybackTransition.entries.filter {
            invalidatesDecodedState(it, DecodedStateKind.VIDEO_QUEUE)
        }
        assertEquals(setOf(PlaybackTransition.SEEK, PlaybackTransition.CLOSE), transitionsThatInvalidateTheQueue.toSet())
    }

    @Test
    fun everyTransitionExceptPauseInvalidatesAudioCarryover() {
        val transitionsThatInvalidateCarryover = PlaybackTransition.entries.filter {
            invalidatesDecodedState(it, DecodedStateKind.AUDIO_CARRYOVER)
        }
        assertEquals(
            setOf(PlaybackTransition.SEEK, PlaybackTransition.RATE_CHANGE, PlaybackTransition.CLOSE),
            transitionsThatInvalidateCarryover.toSet(),
        )
    }
}
