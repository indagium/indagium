package com.indagium.testing.authoring

import com.indagium.testing.model.MAX_RECORDED_INPUT_GAP_MS
import com.indagium.testing.model.RecordedInput
import com.indagium.testing.model.RecordedInputEvent
import com.indagium.testing.model.RecordedTouchKind
import com.indagium.testing.model.TimedGesturePoint

// Turns the raw rows of a recording into the structured RecordedInput a step keeps (testing/model/RecordedInput.kt): the gesture's
// samples with their times as fractions of the recorded screen, the gap before each input and the settle time after the last one.

/**
 * The recorded input of the step that stands for the rows [wantedIds] (its own row, or the raw rows a rewrite merged), in recorded
 * order; null when the step cannot be replayed: a row is not a replayable kind (typed text, rotation, screen on, an unnamed key, a row the
 * person wrote), a touch lost its screen size, or an input was withheld because it touched a password.
 */
internal fun buildRecordedInput(inputs: List<RewriteInput>, wantedIds: Set<String>, sensitivity: RewriteSensitivity): RecordedInput? {
    val sources = inputs.filter { it.row.id in wantedIds }
    if (sources.isEmpty() || sources.any { sensitivity.inputWithheld(it) }) return null
    val events = ArrayList<RecordedInputEvent>(sources.size)
    var previousEndMs: Long? = null
    for (source in sources) {
        val row = source.row
        val gap = previousEndMs?.let { (row.inputStartMs - it).coerceIn(0L, MAX_RECORDED_INPUT_GAP_MS) } ?: 0L
        events += eventOf(row, gap) ?: return null
        previousEndMs = row.inputAtMs
    }
    val last = sources.last()
    val next = inputs.firstOrNull { it.number > last.number && !it.fixed && it.row.kind != null }
    val settle = next?.let { (it.row.inputStartMs - last.row.inputAtMs).coerceIn(0L, MAX_RECORDED_INPUT_GAP_MS) } ?: 0L
    return RecordedInput(events, settle)
}

private fun eventOf(row: RecordedTestStep, gap: Long): RecordedInputEvent? = when (row.kind) {
    RecordedInputKind.TAP -> touchEvent(row, RecordedTouchKind.TAP, gap)
    RecordedInputKind.LONG_PRESS -> touchEvent(row, RecordedTouchKind.LONG_PRESS, gap)
    RecordedInputKind.SWIPE -> touchEvent(row, RecordedTouchKind.SWIPE, gap)
    RecordedInputKind.KEY, RecordedInputKind.BACK ->
        (row.keyName ?: "BACK".takeIf { row.kind == RecordedInputKind.BACK })?.let { RecordedInputEvent.Key(it, gap) }
    RecordedInputKind.NOTIFICATIONS -> RecordedInputEvent.Panel("notifications", gap)
    RecordedInputKind.QUICK_SETTINGS -> RecordedInputEvent.Panel("quick_settings", gap)
    RecordedInputKind.CLOSE_PANELS -> RecordedInputEvent.Panel("close", gap)
    else -> null
}

private fun touchEvent(row: RecordedTestStep, kind: RecordedTouchKind, gap: Long): RecordedInputEvent.Touch? {
    val path = row.gesturePath
    val first = path.firstOrNull() ?: return null
    if (path.any { it.screenWidth <= 0 || it.screenHeight <= 0 }) return null
    val duration = maxOf(row.durationMs ?: 0L, path.maxOf { it.tMs }.coerceAtLeast(0L))
    val times = sampleTimes(path, duration, kind)
    val points = path.mapIndexed { index, point ->
        TimedGesturePoint(
            (point.x.toDouble() / (point.screenWidth - 1).coerceAtLeast(1)).coerceIn(0.0, 1.0),
            (point.y.toDouble() / (point.screenHeight - 1).coerceAtLeast(1)).coerceIn(0.0, 1.0),
            times[index],
        )
    }
    return RecordedInputEvent.Touch(
        kind = kind,
        points = if (kind == RecordedTouchKind.SWIPE) points else listOf(points.last().copy(tMs = 0L)),
        durationMs = duration,
        sourceScreenWidth = first.screenWidth,
        sourceScreenHeight = first.screenHeight,
        orientationQuarterTurns = row.targetOrientationQuarterTurns,
        gapBeforeMs = gap,
    )
}

/**
 * When each sample was reached: the recorded times, first at 0 and never going back; a recording stored before times were kept has
 * none, and its samples are spread evenly over the duration (a guess, like the same fallback of the reference log).
 */
private fun sampleTimes(path: List<RecordedPoint>, durationMs: Long, kind: RecordedTouchKind): List<Long> {
    if (kind != RecordedTouchKind.SWIPE) return path.map { 0L }
    val recorded = path.all { it.tMs >= 0L }
    val last = path.lastIndex.coerceAtLeast(1)
    var previous = 0L
    return path.mapIndexed { index, point ->
        val raw = if (recorded) point.tMs else Math.round(durationMs.toDouble() * index / last)
        (if (index == 0) 0L else raw.coerceIn(previous, durationMs)).also { previous = it }
    }
}
