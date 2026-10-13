package com.indagium.testing.run

import com.indagium.testing.device.GESTURE_MAX_POINTS
import com.indagium.testing.model.RecordedInput
import com.indagium.testing.model.RecordedInputEvent
import com.indagium.testing.model.RecordedTouchKind
import com.indagium.testing.script.untrustedData
import kotlin.math.roundToInt

/** Numeric provenance available on every step reply, without requiring an example read or guessing sample times. */
internal fun recordedGestureGuidance(input: RecordedInput?): Map<String, Any?> {
    val gestures = input?.events?.mapIndexedNotNull { index, event ->
        val touch = (event as? RecordedInputEvent.Touch)?.takeIf { it.kind == RecordedTouchKind.SWIPE } ?: return@mapIndexedNotNull null
        // Keep release as a timed sample. No extra hold may be added, or the recording's endpoint rest would be doubled.
        val complete = if (touch.points.isNotEmpty() && touch.durationMs > touch.points.last().tMs) {
            touch.points + touch.points.last().copy(tMs = touch.durationMs)
        } else touch.points
        val indices = if (complete.size <= GESTURE_MAX_POINTS) complete.indices.toList() else {
            (0 until GESTURE_MAX_POINTS).map { (it.toDouble() * complete.lastIndex / (GESTURE_MAX_POINTS - 1)).roundToInt() }
        }
        mapOf(
            "input" to index + 1,
            "timing" to "recorded",
            "durationMs" to touch.durationMs,
            "restBeforeLiftMs" to 0L,
            "points" to indices.map { pointIndex ->
                val point = complete[pointIndex]
                mapOf("xPercent" to point.x * 100, "yPercent" to point.y * 100, "tMs" to point.tMs)
            },
        )
    }.orEmpty()
    return if (gestures.isEmpty()) emptyMap() else mapOf(
        "recordedGestures" to gestures,
        "recordedGesturesHowTo" to "Pixel = round(percent / 100 * (screenshot size - 1)). Pass points (not path) to gesture with " +
            "each recorded tMs unchanged and holdEndMs 0: these samples already include release and any endpoint rest. " +
            "Confirm the current screen matches the recorded control and orientation before using these coordinates.",
    )
}

internal fun LaneStepBrief.recordedGestureContext(): Map<String, Any?> {
    val data = buildMap<String, Any?> {
        gestureHint?.let { put("summary", it) }
        putAll(recordedGestureGuidance(recordedInput))
    }
    return if (data.isEmpty()) emptyMap() else untrustedData("recorded_gesture_hint", data)
}
