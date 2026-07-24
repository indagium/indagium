package com.openlog

import com.openlog.video.formatVideoTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure "mm:ss.mmm" transport-bar label formatting (video/VideoTimeFormat.kt, plan doc's Task B). */
class VideoTimeFormatTest {
    @Test
    fun formatsZero() {
        assertEquals("00:00.000", formatVideoTime(0))
    }

    @Test
    fun formatsSubMinutePositions() {
        assertEquals("00:01.234", formatVideoTime(1_234))
        assertEquals("00:59.999", formatVideoTime(59_999))
    }

    @Test
    fun formatsMinutesAndSeconds() {
        assertEquals("01:00.000", formatVideoTime(60_000))
        assertEquals("05:03.500", formatVideoTime(303_500))
    }

    @Test
    fun doesNotWrapMinutesIntoAnHoursSegment() {
        // 75 minutes, 3.5 seconds — stays "75:03.500", never switches to an "h:mm:ss" shape.
        assertEquals("75:03.500", formatVideoTime(75 * 60_000L + 3_500))
    }

    @Test
    fun treatsNegativeInputAsZero() {
        assertEquals("00:00.000", formatVideoTime(-500))
    }
}
