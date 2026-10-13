package com.indagium.testing.device

// Faithful gestures: a finger that follows a path with timing, held where the path says, lifted when it says. The `input swipe` command
// cannot do that (constant speed, lifts at once), so a gesture is injected as `input touchscreen motionevent DOWN / MOVE / UP`
// commands. `motionevent` exists from Android 10 (API 29, the "Q" release: it was added to the `input` shell command with the
// `DOWN|UP|MOVE x y` form this file uses); older devices fall back to a straight `input swipe` and say so.
//
// Every `input` call starts a Java process (about 0.1 - 0.3 s on a phone), so a path is sent as ONE remote script and not as one adb
// call per point: the script times the first call, takes that as the cost of a call and shortens every pause by it, so the time between
// two injected events follows the recorded one and a hold before the lift stays a hold (to the resolution of /proc/uptime, 10 ms).
// A device whose clock cannot be read keeps the plain pauses. A point arriving late is never skipped: order and the final lift matter
// more than speed. The timing is best effort because each Android input process has variable latency.

/** A position of a gesture and the time, in milliseconds after the finger went down, the finger is there. */
internal data class GesturePoint(val x: Int, val y: Int, val tMs: Long)

/** How a gesture reached the device: the live mirror stream, individual motion commands, or a straight swipe. */
internal enum class GestureMode(val wire: String) {
    MIRROR_CONTROL("mirror_control"),
    MOTION_EVENTS("motionevent"),
    SWIPE_FALLBACK("swipe_fallback"),
}

/** What a gesture did: the points in screenshot pixels and in device pixels, how it was injected and anything the caller should know. */
internal data class GesturePlayResult(
    val imagePoints: List<GesturePoint>,
    val devicePoints: List<GesturePoint>,
    val mode: GestureMode,
    val durationMs: Long,
    val holdBeforeReleaseMs: Long,
    val note: String? = null,
)

/** The first Android API level that has `input motionevent`. */
internal const val MOTION_EVENT_MIN_API = 29
internal const val MOTION_DOWN_MARKER = "__indagium_motion_down_ok__"
internal const val GESTURE_MIN_POINTS = 2
internal const val GESTURE_MAX_POINTS = 40
internal const val GESTURE_MIN_DURATION_MS = 50L
internal const val GESTURE_MAX_DURATION_MS = 10_000L

/** How many ms one `input` call may take at most before the gesture's adb call is given up, per injected event. */
private const val GESTURE_EVENT_ALLOWANCE_MS = 1_500L
private const val GESTURE_TIMEOUT_SLACK_MS = 3_000L
private const val MAX_CALL_COST_CENTISECONDS = 60
private const val MILLIS_PER_SECOND = 1_000L
private const val HOLD_TOLERANCE_PIXELS = 3

/** The pause, in the units the script uses, a hold of this many points is detected with: trailing points this close are "still". */
internal fun gestureHoldMs(points: List<GesturePoint>, tolerancePixels: Int = HOLD_TOLERANCE_PIXELS): Long {
    val last = points.lastOrNull() ?: return 0L
    var first = points.lastIndex
    while (first > 0 && points[first - 1].isWithin(tolerancePixels, last)) first--
    return (last.tMs - points[first].tMs).coerceAtLeast(0L)
}

private fun GesturePoint.isWithin(tolerancePixels: Int, other: GesturePoint): Boolean =
    kotlin.math.abs(x - other.x) <= tolerancePixels && kotlin.math.abs(y - other.y) <= tolerancePixels

/** Why [points] cannot be a gesture, or null: count, time order, duration. Positions are checked against the screen by the caller. */
internal fun gestureProblem(points: List<GesturePoint>): String? {
    if (points.size !in GESTURE_MIN_POINTS..GESTURE_MAX_POINTS) {
        return "A gesture needs $GESTURE_MIN_POINTS to $GESTURE_MAX_POINTS points " +
            "(a press is two points at the same place, the second at the time it is lifted)."
    }
    if (points.first().tMs != 0L) return "The first point of a gesture must be at 0 ms."
    if (points.zipWithNext().any { (a, b) -> b.tMs < a.tMs }) return "Gesture points must be in time order (tMs must not decrease)."
    val total = points.last().tMs
    return if (total in GESTURE_MIN_DURATION_MS..GESTURE_MAX_DURATION_MS) {
        null
    } else {
        "A gesture must last between $GESTURE_MIN_DURATION_MS and $GESTURE_MAX_DURATION_MS ms (the last point's tMs, including any hold)."
    }
}

/** The adb time limit for playing [points]: their duration plus room for every `input` call. */
internal fun gestureTimeoutSeconds(points: List<GesturePoint>): Long {
    val ms = points.last().tMs + points.size * GESTURE_EVENT_ALLOWANCE_MS + GESTURE_TIMEOUT_SLACK_MS
    return (ms + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
}

/**
 * The remote shell script that injects [points] (device pixels, times in ms from touch-down, in order, the first at 0).
 *
 * Shape (one line, joined with `;`): two helper functions (`c` reads the device's uptime in centiseconds, `p N` sleeps N ms less the
 * measured cost `d` of one `input` call), then `DOWN`, a `p` pause, a `MOVE`, a `p` pause, ..., `UP`. Every call ends in `|| f=1` and
 * the script exits with `f`, so a failing call is reported but never stops the finger from being lifted.
 */
internal fun gestureShellScript(points: List<GesturePoint>): String {
    require(points.size >= GESTURE_MIN_POINTS) { "A gesture needs at least $GESTURE_MIN_POINTS points." }
    val parts = ArrayList<String>()
    parts += "d=0"
    parts += "c(){ read u r </proc/uptime; echo \${u%.*}\${u#*.}; }"
    parts += "p(){ g=\$((\$1-d*10)); [ \$g -gt 0 ] && sleep \$((g/1000)).\$((g%1000/100))\$((g%100/10))\$((g%10)); }"
    parts += "f=0"
    parts += "a=\$(c)"
    parts += "input touchscreen motionevent DOWN ${points.first().x} ${points.first().y} && echo $MOTION_DOWN_MARKER || f=1"
    parts += "b=\$(c)"
    parts += "d=\$((\${b:-0}-\${a:-0}))"
    parts += "[ \$d -lt 0 ] && d=0"
    parts += "[ \$d -gt $MAX_CALL_COST_CENTISECONDS ] && d=$MAX_CALL_COST_CENTISECONDS"
    var previous = points.first()
    val path = points.drop(1)
    for ((index, point) in path.withIndex()) {
        val isLast = index == path.lastIndex
        val gap = (point.tMs - previous.tMs).coerceAtLeast(0L)
        if (gap > 0L) parts += "p $gap"
        parts += motionEvent(if (isLast) "UP" else "MOVE", point)
        previous = point
    }
    parts += "exit \$f"
    return parts.joinToString("; ")
}

private fun motionEvent(action: String, point: GesturePoint): String =
    "input touchscreen motionevent $action ${point.x} ${point.y} || f=1"

/** The API 29-compatible command that puts an interrupted gesture's finger up. */
internal fun gestureCancelCommand(last: GesturePoint): List<String> =
    listOf("input", "touchscreen", "motionevent", "UP", last.x.toString(), last.y.toString())

/** The plain `input swipe` command that approximates [points] on a device without `motionevent`: first to last point over the whole time. */
internal fun gestureSwipeFallback(points: List<GesturePoint>, minMs: Int, maxMs: Int): List<String> {
    val first = points.first()
    val last = points.last()
    val duration = last.tMs.coerceIn(minMs.toLong(), maxMs.toLong())
    return listOf("input", "swipe", first.x.toString(), first.y.toString(), last.x.toString(), last.y.toString(), duration.toString())
}

/** The API level in the output of `getprop ro.build.version.sdk`, or null when it is not a number. */
internal fun parseSdkLevel(output: String): Int? = output.trim().lines().firstOrNull()?.trim()?.toIntOrNull()

/** True when the output of a gesture script shows the device does not know `motionevent` (the `input` usage text instead of a result). */
internal fun looksLikeMotionEventUnsupported(output: String): Boolean {
    val text = output.lowercase()
    return "unknown command" in text || ("usage: input" in text && "motionevent" !in text)
}

/** Never start a fallback swipe after this script says DOWN succeeded: that would inject the user's action twice. */
internal fun mayFallbackAfterMotionScript(output: String): Boolean =
    MOTION_DOWN_MARKER !in output && looksLikeMotionEventUnsupported(output)
