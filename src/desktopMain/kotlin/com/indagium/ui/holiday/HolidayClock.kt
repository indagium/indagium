// Frame timing constants of the clock, not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalWindowInfo
import com.indagium.ui.*
import kotlinx.coroutines.delay

private const val CLOCK_FPS = 30
private const val NANOS_PER_SECOND = 1_000_000_000f
private const val MILLIS_PER_SECOND = 1000
private const val FRAME_SLACK_MS = 8

/** The frozen moment a still picture shows: glow on, eyes open, everything near the middle of its sway. */
internal const val HOLIDAY_STILL_TIME = 1.25f

/**
 * Seconds since the picture started, published at most [fps] times a second while [running]; otherwise [HOLIDAY_STILL_TIME] and no frame
 * loop at all. Read the value only inside draw code (a draw lambda re-draws on change, nothing recomposes).
 */
@Composable
internal fun rememberHolidayClock(running: Boolean, fps: Int = CLOCK_FPS): State<Float> {
    val time = remember { mutableFloatStateOf(HOLIDAY_STILL_TIME) }
    LaunchedEffect(running, fps) {
        if (!running) {
            time.floatValue = HOLIDAY_STILL_TIME
            return@LaunchedEffect
        }
        val minGapNanos = (NANOS_PER_SECOND / fps).toLong()
        val pauseMs = (MILLIS_PER_SECOND / fps - FRAME_SLACK_MS).coerceAtLeast(0).toLong()
        var start = -1L
        var last = Long.MIN_VALUE / 2
        while (true) {
            withFrameNanos { now ->
                if (start < 0) start = now
                if (now - last >= minGapNanos) {
                    last = now
                    time.floatValue = (now - start) / NANOS_PER_SECOND
                }
            }
            delay(pauseMs)
        }
    }
    return time
}

/** Whether the pictures should animate now: the setting is on, nothing is capturing/playing, and the window has focus. */
@Composable
internal fun holidayPicturesRunning(animate: Boolean): Boolean {
    val paused = LocalHolidayPaused.current
    val focused = LocalWindowInfo.current.isWindowFocused
    return animate && !paused && focused
}
