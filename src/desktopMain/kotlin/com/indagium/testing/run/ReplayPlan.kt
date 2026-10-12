package com.indagium.testing.run

import com.indagium.debug.DeviceScreenCoordinateSpace
import com.indagium.testing.device.DeviceInputAction
import com.indagium.testing.device.GESTURE_MAX_DURATION_MS
import com.indagium.testing.device.GESTURE_MAX_POINTS
import com.indagium.testing.device.GESTURE_MIN_DURATION_MS
import com.indagium.testing.device.GesturePoint
import com.indagium.testing.device.SystemPanel
import com.indagium.testing.device.replayProblemOf
import com.indagium.testing.model.REPLAY_DEFAULT_SETTLE_MS
import com.indagium.testing.model.REPLAY_MAX_GAP_MS
import com.indagium.testing.model.REPLAY_MAX_SETTLE_MS
import com.indagium.testing.model.REPLAY_MIN_SETTLE_MS
import com.indagium.testing.model.RecordedInput
import com.indagium.testing.model.RecordedInputEvent
import com.indagium.testing.model.RecordedTouchKind
import kotlin.math.abs
import kotlin.math.roundToInt

// Turns the RecordedInput of a REPLAY step into the device actions that reproduce it on THIS device: normalized positions scaled to the
// latest screenshot's pixels (the session then maps them onto device pixels exactly like tap and swipe do), a gesture's samples kept
// with their times, the recorded gaps between inputs kept (bounded). It refuses, with the reason in words, a device whose proportions
// differ from the recorded screen's: a replay on the wrong shape of screen would tap somewhere else.

/** How far (as a fraction) the device's width / height may differ from the recorded screen's before a replay is refused. */
internal const val REPLAY_ASPECT_TOLERANCE = 0.05

internal sealed interface ReplayPlan {
    /** [descriptions] has one short line per action, for the tool activity; [plannedMs] is how long injecting takes, pauses included. */
    data class Ready(
        val actions: List<DeviceInputAction>,
        val descriptions: List<String>,
        val settleMs: Long,
        val plannedMs: Long,
    ) : ReplayPlan

    /** The replay must not run; [reason] says why. A refusal is a problem of the step or the device, never retried. */
    data class Refused(val reason: String) : ReplayPlan
}

internal fun planReplay(input: RecordedInput, space: DeviceScreenCoordinateSpace): ReplayPlan {
    replayProblemOf(input)?.let { return ReplayPlan.Refused(it) }
    shapeProblem(input, space)?.let { return ReplayPlan.Refused(it) }
    val actions = ArrayList<DeviceInputAction>()
    val descriptions = ArrayList<String>()
    for ((index, event) in input.events.withIndex()) {
        val pause = input.events.getOrNull(index + 1)?.gapBeforeMs?.coerceIn(0L, REPLAY_MAX_GAP_MS) ?: 0L
        val (action, text) = actionFor(event, space, pause)
            ?: return ReplayPlan.Refused("Input ${index + 1} lasts longer than ${GESTURE_MAX_DURATION_MS / MILLIS_PER_SECOND} s and cannot be replayed.")
        actions += action
        descriptions += text
    }
    val planned = actions.sumOf { action -> action.delayMs + durationOf(action) }
    return ReplayPlan.Ready(actions, descriptions, settleFor(input), planned)
}

private const val MILLIS_PER_SECOND = 1_000L

internal fun settleFor(input: RecordedInput): Long =
    if (input.settleMs <= 0L) REPLAY_DEFAULT_SETTLE_MS else input.settleMs.coerceIn(REPLAY_MIN_SETTLE_MS, REPLAY_MAX_SETTLE_MS)

private fun durationOf(action: DeviceInputAction): Long = when (action) {
    is DeviceInputAction.Gesture -> action.points.lastOrNull()?.tMs ?: 0L
    else -> 0L
}

/** Null when every recorded touch was made on a screen of the device's proportions; otherwise what differs. */
private fun shapeProblem(input: RecordedInput, space: DeviceScreenCoordinateSpace): String? {
    val device = space.deviceWidth.toDouble() / space.deviceHeight
    for ((index, event) in input.events.withIndex()) {
        if (event !is RecordedInputEvent.Touch) continue
        val width = event.sourceScreenWidth
        val height = event.sourceScreenHeight
        if (width == null || height == null) return "Input ${index + 1} did not keep the size of the screen it was recorded on, so it cannot be scaled safely."
        val recorded = width.toDouble() / height
        if (abs(recorded / device - 1.0) > REPLAY_ASPECT_TOLERANCE) {
            return "Input ${index + 1} was recorded on a ${orientationWord(width, height)} screen (${width}x$height), but the device is " +
                "${orientationWord(space.deviceWidth, space.deviceHeight)} (${space.deviceWidth}x${space.deviceHeight}); rotate the device or record again."
        }
    }
    return null
}

private fun orientationWord(width: Int, height: Int): String = if (width > height) "landscape" else "portrait"

private fun actionFor(event: RecordedInputEvent, space: DeviceScreenCoordinateSpace, pause: Long): Pair<DeviceInputAction, String>? = when (event) {
    is RecordedInputEvent.Key -> DeviceInputAction.Key(event.key, pause) to "press_key ${event.key}"
    is RecordedInputEvent.Panel -> DeviceInputAction.Panel(SystemPanel.fromToolValue(event.panel), pause) to "open_system_panel ${event.panel}"
    is RecordedInputEvent.Touch -> touchAction(event, space, pause)
}

private fun touchAction(event: RecordedInputEvent.Touch, space: DeviceScreenCoordinateSpace, pause: Long): Pair<DeviceInputAction, String>? {
    val points = event.points.map { GesturePoint(toImage(it.x, space.imageWidth), toImage(it.y, space.imageHeight), it.tMs) }
    val first = points.first()
    val last = points.last()
    return when (event.kind) {
        RecordedTouchKind.TAP -> DeviceInputAction.Tap(last.x, last.y, pause) to "tap ${last.x},${last.y}"
        RecordedTouchKind.LONG_PRESS -> {
            val held = event.durationMs.coerceAtLeast(GESTURE_MIN_DURATION_MS)
            if (held > GESTURE_MAX_DURATION_MS) {
                null
            } else {
                DeviceInputAction.Gesture(listOf(first.copy(tMs = 0L), first.copy(tMs = held)), pause) to "long press ${first.x},${first.y} for $held ms"
            }
        }
        RecordedTouchKind.SWIPE -> {
            val total = event.durationMs.coerceAtLeast(points.last().tMs).coerceAtLeast(GESTURE_MIN_DURATION_MS)
            if (total > GESTURE_MAX_DURATION_MS) return null
            // The finger may rest on its last sample until it lifts: that hold is the point of recording the time of each sample.
            val timed = if (total > points.last().tMs) points + points.last().copy(tMs = total) else points
            val gesture = thin(timed)
            DeviceInputAction.Gesture(gesture, pause) to
                "gesture ${first.x},${first.y} -> ${last.x},${last.y} over $total ms (${gesture.size} points)"
        }
    }
}

/** Bound shell process overhead while keeping first, movement endpoint and lift; dense samples cannot run at their recorded cadence. */
internal fun thin(points: List<GesturePoint>): List<GesturePoint> {
    if (points.size <= 2) return points
    val duration = points.last().tMs
    val budget = (duration / MIN_MOTION_SAMPLE_INTERVAL_MS + 2).toInt().coerceIn(3, GESTURE_MAX_POINTS.coerceAtMost(MAX_REPLAY_MOTION_POINTS))
    if (points.size <= budget) return points
    val last = points.last()
    var movementEnd = points.lastIndex
    while (movementEnd > 0 && abs(points[movementEnd - 1].x - last.x) <= 3 && abs(points[movementEnd - 1].y - last.y) <= 3) {
        movementEnd--
    }
    val includesLift = movementEnd < points.lastIndex
    val moveBudget = budget - if (includesLift) 1 else 0
    val selected = (0 until moveBudget).map { i ->
        points[(i.toDouble() * movementEnd / (moveBudget - 1)).roundToInt()]
    }
    return (selected + if (includesLift) listOf(last) else emptyList()).distinct()
}

private const val MIN_MOTION_SAMPLE_INTERVAL_MS = 120L
private const val MAX_REPLAY_MOTION_POINTS = 16

/** The screenshot pixel a recorded fraction of the screen falls on: the inclusive mapping the session's own conversions invert. */
internal fun toImage(fraction: Double, imageSize: Int): Int =
    if (imageSize <= 1) 0 else (fraction.coerceIn(0.0, 1.0) * (imageSize - 1)).roundToInt().coerceIn(0, imageSize - 1)
