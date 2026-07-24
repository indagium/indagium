package com.openlog

import com.openlog.video.PlaybackTimeline
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the controller's transport contract without opening FFmpeg: decoded frames and clock ticks
 * may arrive late, but only a user seek can move the published position backwards.
 */
class PlaybackTimelineTest {
    @Test
    fun lateFrameCannotMovePlaybackPositionBackwards() {
        val timeline = PlaybackTimeline(initialUs = 2_000_000)

        assertEquals(2_250_000, timeline.advance(2_250_000))
        assertEquals(2_250_000, timeline.advance(2_100_000))
        assertEquals(2_250_000, timeline.positionUs)
    }

    @Test
    fun seekStartsANewTimelinePositionAndPlaybackAdvancesFromIt() {
        val timeline = PlaybackTimeline(initialUs = 5_000_000)

        assertEquals(1_000_000, timeline.seek(1_000_000))
        assertEquals(1_000_000, timeline.advance(900_000))
        assertEquals(1_033_000, timeline.advance(1_033_000))
    }

    @Test
    fun negativeTimestampsAreClampedToVideoStart() {
        val timeline = PlaybackTimeline(initialUs = -1)

        assertEquals(0, timeline.positionUs)
        assertEquals(0, timeline.advance(-500))
        assertEquals(0, timeline.seek(-1_000))
    }
}
