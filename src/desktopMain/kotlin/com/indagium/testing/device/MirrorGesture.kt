package com.indagium.testing.device

import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.capture.mirror.ScrcpyControlEncoder
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** The control protocol addresses the encoded video size, which may be smaller than the device screenshot. */
internal fun mirrorGesturePoints(points: List<GesturePoint>, deviceWidth: Int, deviceHeight: Int, width: Int, height: Int): List<GesturePoint> {
    val deviceAspect = deviceWidth.toDouble() / deviceHeight
    val streamAspect = width.toDouble() / height
    require(abs(deviceAspect / streamAspect - 1) <= MIRROR_ASPECT_TOLERANCE) {
        "The mirror and screenshot orientations differ; take a fresh screenshot before gesturing."
    }

    fun scale(value: Int, from: Int, to: Int) = (value.toDouble() * (to - 1) / (from - 1).coerceAtLeast(1)).roundToInt().coerceIn(0, to - 1)
    return points.map { GesturePoint(scale(it.x, deviceWidth, width), scale(it.y, deviceHeight, height), it.tMs) }
}

/** Uses the same continuous touch stream as the manual mirror, without launching an Android input process per sample. */
internal suspend fun playMirrorGesture(
    points: List<GesturePoint>,
    width: Int,
    height: Int,
    send: (ByteArray) -> Boolean,
    nowNanos: () -> Long = System::nanoTime,
    pause: suspend (Long) -> Unit = { delay(it) },
) {
    fun event(action: MirrorTouchAction, point: GesturePoint): ByteArray = ScrcpyControlEncoder.encode(
        MirrorControlCommand.Touch(
            action = action,
            pointerId = 1L,
            x = point.x,
            y = point.y,
            pressure = if (action == MirrorTouchAction.UP || action == MirrorTouchAction.CANCEL) 0f else 1f,
            screenWidth = width,
            screenHeight = height,
        ),
    )
    var released = false
    var last = points.first()
    try {
        check(send(event(MirrorTouchAction.DOWN, last))) { "The mirror touch stream rejected touch-down; the gesture was not repeated." }
        val start = nowNanos()
        for ((index, point) in points.drop(1).withIndex()) {
            val remaining = point.tMs - (nowNanos() - start) / NANOS_PER_MILLISECOND
            if (remaining > 0L) pause(remaining)
            last = point
            val action = if (index == points.size - 2) MirrorTouchAction.UP else MirrorTouchAction.MOVE
            check(send(event(action, point))) { "The mirror touch stream was interrupted; the gesture was not repeated." }
            released = action == MirrorTouchAction.UP
        }
    } finally {
        if (!released) withContext(NonCancellable) { runCatching { send(event(MirrorTouchAction.CANCEL, last)) } }
    }
}

private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val MIRROR_ASPECT_TOLERANCE = 0.05
