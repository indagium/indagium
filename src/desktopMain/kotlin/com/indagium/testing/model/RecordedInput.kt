package com.indagium.testing.model

// The structured form of the inputs a step was recorded from, kept so a REPLAY step can approximate their path and timing: the gesture's path with
// the time of every sample (so a hold before the finger lifts survives), the gaps between inputs, and the screen they were made on.
// Everything is device independent: positions are fractions of the recorded screen, times are milliseconds. Pure data; the replay itself
// lives in testing/run/ReplayPlan.kt and testing/device/TestDeviceSession.kt.

const val MAX_RECORDED_INPUT_EVENTS = 40
const val MAX_RECORDED_INPUT_POINTS = 64
const val MAX_RECORDED_INPUT_DURATION_MS = 60_000L
const val MAX_RECORDED_INPUT_GAP_MS = 600_000L

/** The longest pause a replay keeps between two inputs (a recorded gap is a person's thinking time too) and the longest settle after the last. */
const val REPLAY_MAX_GAP_MS = 5_000L
const val REPLAY_MAX_SETTLE_MS = 3_000L
const val REPLAY_DEFAULT_SETTLE_MS = 1_000L
const val REPLAY_MIN_SETTLE_MS = 500L

/** One sample of a recorded touch: position as fractions (0..1) of the recorded screen, [tMs] milliseconds after the touch went down. */
data class TimedGesturePoint(val x: Double, val y: Double, val tMs: Long)

enum class RecordedTouchKind { TAP, LONG_PRESS, SWIPE }

/** One input of a recorded step. [gapBeforeMs] is the time between the end of the previous input of the step and the start of this one. */
sealed interface RecordedInputEvent {
    val gapBeforeMs: Long

    /**
     * A finger on the screen. [points] are in time order, the first at 0; [durationMs] is how long the finger stayed down (the last
     * point may be earlier than that: the finger rested there before it lifted). The source screen size and orientation are what the
     * fractions refer to; a replay refuses a device whose proportions differ.
     */
    data class Touch(
        val kind: RecordedTouchKind,
        val points: List<TimedGesturePoint>,
        val durationMs: Long,
        val sourceScreenWidth: Int? = null,
        val sourceScreenHeight: Int? = null,
        val orientationQuarterTurns: Int? = null,
        override val gapBeforeMs: Long = 0L,
    ) : RecordedInputEvent

    /** A hardware or navigation key, by the name `press_key` knows (BACK, HOME, RECENTS, POWER, ...). */
    data class Key(val key: String, override val gapBeforeMs: Long = 0L) : RecordedInputEvent

    /** The notification shade or quick settings opened, or either closed: the tool value of `open_system_panel`. */
    data class Panel(val panel: String, override val gapBeforeMs: Long = 0L) : RecordedInputEvent
}

/**
 * The recorded inputs of one step, in the order they were made. [settleMs] is the time that passed after the last of them before the
 * person did anything else; a replay waits (a bounded part of) it before it looks at the result. 0 means unknown.
 */
data class RecordedInput(val events: List<RecordedInputEvent>, val settleMs: Long = 0L)

/**
 * How long the finger stood still at the end before it lifted, in ms: from the first of the trailing samples that all lie within
 * [toleranceFraction] of the last one (of the screen size) up to the release. 0 when it moved until the end.
 */
fun RecordedInputEvent.Touch.holdBeforeReleaseMs(toleranceFraction: Double = HOLD_TOLERANCE_FRACTION): Long {
    val last = points.lastOrNull() ?: return 0L
    var first = points.lastIndex
    while (first > 0 && close(points[first - 1], last, toleranceFraction)) first--
    return (durationMs - points[first].tMs).coerceAtLeast(0L)
}

private const val HOLD_TOLERANCE_FRACTION = 0.015

private fun close(a: TimedGesturePoint, b: TimedGesturePoint, tolerance: Double): Boolean =
    kotlin.math.abs(a.x - b.x) <= tolerance && kotlin.math.abs(a.y - b.y) <= tolerance

/**
 * The first reason [this] cannot be replayed, or null when it can: empty, too many inputs, a coordinate outside the screen, samples that
 * go back in time, a duration out of bounds, a key `press_key` does not know. Used when a step is saved as REPLAY and again by the run.
 */
fun RecordedInput.problem(knownKeys: Set<String>, knownPanels: Set<String>): String? {
    if (events.isEmpty()) return "The recorded input has no inputs."
    if (events.size > MAX_RECORDED_INPUT_EVENTS) return "The recorded input has more than $MAX_RECORDED_INPUT_EVENTS inputs."
    return events.withIndex().firstNotNullOfOrNull { (index, event) ->
        val where = "Input ${index + 1}"
        when {
            event.gapBeforeMs !in 0..MAX_RECORDED_INPUT_GAP_MS -> "$where: its gap is out of bounds."
            event is RecordedInputEvent.Touch -> event.problem(where)
            event is RecordedInputEvent.Key && event.key !in knownKeys -> "$where: key '${event.key}' cannot be replayed."
            event is RecordedInputEvent.Panel && event.panel !in knownPanels -> "$where: panel '${event.panel}' cannot be replayed."
            else -> null
        }
    }
}

private fun RecordedInputEvent.Touch.problem(where: String): String? = when {
    points.isEmpty() || points.size > MAX_RECORDED_INPUT_POINTS -> "$where: a touch needs 1 to $MAX_RECORDED_INPUT_POINTS samples."
    durationMs !in 0..MAX_RECORDED_INPUT_DURATION_MS -> "$where: its duration is out of bounds."
    points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0.0..1.0 || it.y !in 0.0..1.0 } -> "$where: a sample is outside the screen."
    points.first().tMs != 0L -> "$where: the first sample must be at 0 ms."
    points.zipWithNext().any { (a, b) -> b.tMs < a.tMs } || points.last().tMs > durationMs -> "$where: its samples are not in time order."
    else -> null
}

/** How long a replay of this input takes, pauses and the settle after the last input included; the step's time allowance must cover it. */
fun RecordedInput.estimatedReplayMs(): Long {
    val inputs = events.sumOf { event ->
        val duration = (event as? RecordedInputEvent.Touch)?.durationMs ?: 0L
        event.gapBeforeMs.coerceIn(0L, REPLAY_MAX_GAP_MS) + duration
    }
    val settle = if (settleMs <= 0L) REPLAY_DEFAULT_SETTLE_MS else settleMs.coerceIn(REPLAY_MIN_SETTLE_MS, REPLAY_MAX_SETTLE_MS)
    return inputs + settle
}
