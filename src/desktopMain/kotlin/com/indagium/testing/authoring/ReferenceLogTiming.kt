package com.indagium.testing.authoring

import kotlin.math.abs

// The timing a recorded step's reference log carries, as text and back. A reference log line of a recorded input has always held a
// "normalized path" (up to 10 samples, whole percents) and the total duration ("over 752 ms"); it now also holds
//
//   | timed path (ms from touch-down): (59.4%,98.2%)@0 -> (59.1%,95.0%)@180 -> (56.9%,56.1%)@752
//   | rested 332 ms on the last position before the finger lifted
//
// so an agent (or a person) can reproduce the gesture with the `gesture` tool: the path, when the finger was where, and the pause
// before the lift. A log written before this existed has no "timed path"; [parseReferenceGestures] then spaces the normalized path
// evenly over the duration it finds and says so ([ReferenceGesture.timing]).

/** A position of a recorded gesture in percent of the screen (0..100, one decimal) and the time after touch-down it was reached. */
internal data class TimedPercentPoint(val xPercent: Double, val yPercent: Double, val tMs: Long)

internal enum class ReferenceTiming(val wire: String) {
    /** The log has a "timed path": every sample carries its recorded time. */
    RECORDED("recorded"),

    /** An older log: the normalized path is spread evenly over the duration, which is a guess about the rhythm and the hold. */
    EVEN_SPACING_ASSUMED("even_spacing_assumed"),
}

/** One gesture read back from a reference log line. [holdMs] is the recorded rest before the lift (0 when unknown or none). */
internal data class ReferenceGesture(
    val inputNumber: Int?,
    val points: List<TimedPercentPoint>,
    val durationMs: Long,
    val holdMs: Long,
    val timing: ReferenceTiming,
)

private const val PERCENT = 100.0
private const val TENTH = 10.0

/** Below this a rest before the lift is not worth a sentence: the finger simply lifted. */
internal const val MIN_REPORTED_HOLD_MS = 80L

private fun percentText(value: Double): String = "%.1f".format(java.util.Locale.ROOT, value)

/** `(59.4%,98.2%)@0 -> (59.1%,95.0%)@180 -> ...` for samples given as fractions of the screen, or null when none has a time. */
internal fun formatTimedPath(points: List<TimedPercentPoint>): String? =
    points.takeIf { it.isNotEmpty() }?.joinToString(" -> ") { "(${percentText(it.xPercent)}%,${percentText(it.yPercent)}%)@${it.tMs}" }

/** The sentence for the rest before the lift, or null when the finger did not rest. */
internal fun formatHold(holdMs: Long): String? =
    holdMs.takeIf { it >= MIN_REPORTED_HOLD_MS }?.let { "rested $it ms on the last position before the finger lifted" }

private val TIMED_POINT = Regex("""\((\d+(?:\.\d+)?)%,\s*(\d+(?:\.\d+)?)%\)@(\d+)""")
private val PLAIN_POINT = Regex("""\((\d+(?:\.\d+)?)%,\s*(\d+(?:\.\d+)?)%\)""")
private val DURATION = Regex("""\bover (\d+) ms\b""")
private val HELD = Regex("""\bheld (\d+) ms\b""")
private val RESTED = Regex("""\brested (\d+) ms\b""")
private val LINE_NUMBER = Regex("""^\s*(\d+)\.\s""")
private const val TIMED_PATH_MARKER = "timed path"
private const val PLAIN_PATH_MARKER = "normalized path:"

/**
 * Every gesture a reference log describes (one line per recorded input), in order. A line with a "timed path" gives the recorded
 * times; a line with only a "normalized path" is spread evenly over its "over N ms" (or "held N ms") duration. Lines without a path,
 * and text that is not such a log, give nothing. Never throws.
 */
internal fun parseReferenceGestures(text: String): List<ReferenceGesture> = text.lineSequence().mapNotNull(::parseReferenceGestureLine).toList()

private fun parseReferenceGestureLine(line: String): ReferenceGesture? {
    val number = LINE_NUMBER.find(line)?.groupValues?.get(1)?.toIntOrNull()
    val timedStart = line.indexOf(TIMED_PATH_MARKER)
    if (timedStart >= 0) {
        val body = line.substring(timedStart).substringBefore(" | ")
        val points = TIMED_POINT.findAll(body).mapNotNull { match ->
            val x = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            val y = match.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            TimedPercentPoint(x, y, match.groupValues[3].toLongOrNull() ?: return@mapNotNull null)
        }.toList()
        if (points.isNotEmpty()) {
            val duration = DURATION.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: points.last().tMs
            val hold = RESTED.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val repeatedRelease = points.size > 1 && points.last().tMs >= duration &&
                abs(points[points.lastIndex - 1].xPercent - points.last().xPercent) <= REST_TOLERANCE_PERCENT &&
                abs(points[points.lastIndex - 1].yPercent - points.last().yPercent) <= REST_TOLERANCE_PERCENT
            val movement = if (hold > 0L && repeatedRelease) points.dropLast(1) else points
            return ReferenceGesture(number, movement, duration.coerceAtLeast(points.last().tMs), hold, ReferenceTiming.RECORDED)
        }
    }
    val plainStart = line.indexOf(PLAIN_PATH_MARKER)
    if (plainStart < 0) return null
    val body = line.substring(plainStart + PLAIN_PATH_MARKER.length).substringBefore(" | ")
    val plain = PLAIN_POINT.findAll(body).mapNotNull { match ->
        val x = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
        val y = match.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
        x to y
    }.toList()
    if (plain.isEmpty()) return null
    val duration = (DURATION.find(line) ?: HELD.find(line))?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val last = plain.lastIndex.coerceAtLeast(1)
    val spread = plain.mapIndexed { index, (x, y) -> TimedPercentPoint(x, y, Math.round(duration.toDouble() * index / last)) }
    return ReferenceGesture(number, spread, duration, 0L, ReferenceTiming.EVEN_SPACING_ASSUMED)
}

/**
 * The recorded samples as percent points (one decimal) with the times they were recorded at; null when a sample has no time (a
 * recording stored before times were kept) or no usable screen size: the log then keeps only its plain "normalized path".
 */
internal fun timedPercentPoints(path: List<RecordedPoint>): List<TimedPercentPoint>? {
    if (path.isEmpty() || path.any { it.screenWidth <= 0 || it.screenHeight <= 0 || it.tMs < 0L }) return null
    return path.map { point ->
        TimedPercentPoint(
            Math.round(point.x * PERCENT * TENTH / (point.screenWidth - 1).coerceAtLeast(1)) / TENTH,
            Math.round(point.y * PERCENT * TENTH / (point.screenHeight - 1).coerceAtLeast(1)) / TENTH,
            point.tMs,
        )
    }
}

/** How long the finger rested before the lift: the time from the first of the trailing samples within [tolerancePercent] of the last one to [durationMs]. */
internal fun restBeforeLiftMs(points: List<TimedPercentPoint>, durationMs: Long, tolerancePercent: Double = REST_TOLERANCE_PERCENT): Long {
    val last = points.lastOrNull() ?: return 0L
    var first = points.lastIndex
    while (first > 0 && abs(points[first - 1].xPercent - last.xPercent) <= tolerancePercent &&
        abs(points[first - 1].yPercent - last.yPercent) <= tolerancePercent
    ) {
        first--
    }
    return (durationMs - points[first].tMs).coerceAtLeast(0L)
}

// A nearby movement is still a movement; only repeated endpoint positions are a recorded rest.
private const val REST_TOLERANCE_PERCENT = 0.0
