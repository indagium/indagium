package com.openlog.utils

import java.util.Locale

/** Returned by [parseMillisOfDay] for a `ts` that isn't a parseable `HH:MM:SS[.fraction]` string —
 *  e.g. brief/RAW-format rows, which always carry `ts == ""` (see LogParser.kt). */
const val TS_UNKNOWN = -1L

private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_HOUR = 3_600_000L
private const val HOURS_PER_DAY = 24
private const val MAX_HOUR = 23
private const val MAX_MINUTE_OR_SECOND = 59
private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600

// A negative delta this large can only be a midnight rollover (00:00:00 wrapping back from
// 23:59:59), not a genuine backwards time jump — real backwards jumps (out-of-order merges, clock
// adjustments) are always much smaller than half a day in practice. Matches the same heuristic and
// the same accepted limitation documented on LogMerge.parseLogTimeOfDay: LogEntry.ts never carries
// a date (LogParser strips the MM-DD prefix), so there's no way to resolve a rollover exactly.
private const val ROLLOVER_THRESHOLD_MS = 12 * MILLIS_PER_HOUR

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

// Hand-rolled ASCII scan over LogEntry.ts's "HH:MM:SS" + optional ".<digits>" shape, in the spirit
// of LogParser.kt's parseThreadtimeFast — not LogMerge.kt's parseLogTimeOfDay, which is
// exception-driven via `runCatching { LocalTime.parse(...) }` and strict about exactly 3 fraction
// digits. This runs on every visible row on every recomposition (LogViewer.kt's LazyColumn row
// lambda), so it needs to be allocation-free and tolerant of the `\.\d+` (any digit count) the
// parser regexes actually accept — see LogParserTest for 1/3/6-digit fixtures this must also parse.
@Suppress("ReturnCount")
fun parseMillisOfDay(ts: String): Long {
    val n = ts.length
    if (n < 8) return TS_UNKNOWN
    for (i in intArrayOf(0, 1, 3, 4, 6, 7)) if (!ts[i].isAsciiDigit()) return TS_UNKNOWN
    if (ts[2] != ':' || ts[5] != ':') return TS_UNKNOWN
    val hh = (ts[0] - '0') * 10 + (ts[1] - '0')
    val mm = (ts[3] - '0') * 10 + (ts[4] - '0')
    val ss = (ts[6] - '0') * 10 + (ts[7] - '0')
    if (hh > MAX_HOUR || mm > MAX_MINUTE_OR_SECOND || ss > MAX_MINUTE_OR_SECOND) return TS_UNKNOWN
    var millis = (hh * SECONDS_PER_HOUR + mm * SECONDS_PER_MINUTE + ss) * MILLIS_PER_SECOND
    if (n == 8) return millis
    if (ts[8] != '.') return TS_UNKNOWN
    var i = 9
    var frac = 0L
    var digitsRead = 0
    // Only the first 3 fraction digits ever matter at millisecond resolution — collect those, then
    // keep scanning (without accumulating) so a longer fraction like ".123456" is still recognized
    // as valid and simply truncated, not rejected.
    while (i < n && ts[i].isAsciiDigit()) {
        if (digitsRead < 3) { frac = frac * 10 + (ts[i] - '0'); digitsRead++ }
        i++
    }
    if (digitsRead == 0 || i != n) return TS_UNKNOWN
    while (digitsRead < 3) { frac *= 10; digitsRead++ }
    return millis + frac
}

/** Delta from `prevTs` to `curTs` in milliseconds, or null if either side doesn't parse (blank
 *  ts on brief/RAW rows, or genuinely malformed input). Applies the midnight-rollover correction
 *  documented on [ROLLOVER_THRESHOLD_MS]; a small negative delta (e.g. from out-of-order merged
 *  sources) is returned as-is rather than clamped to zero — it's real, if surprising, data. */
fun deltaMillis(prevTs: String, curTs: String): Long? {
    val prev = parseMillisOfDay(prevTs)
    val cur = parseMillisOfDay(curTs)
    if (prev == TS_UNKNOWN || cur == TS_UNKNOWN) return null
    var delta = cur - prev
    if (delta < -ROLLOVER_THRESHOLD_MS) delta += HOURS_PER_DAY * MILLIS_PER_HOUR
    return delta
}

// Magnitude-only formatting shared by formatDelta/formatSignedDelta below — "0.140" below a
// minute, "1m02s" below an hour, "1h02m03s" beyond that (a rollover-corrected delta can
// theoretically span up to ~24h). Takes the already-absolute value; callers own the sign.
private fun formatMagnitude(absMs: Long): String = when {
    absMs < MILLIS_PER_MINUTE -> String.format(Locale.US, "%.3f", absMs / MILLIS_PER_SECOND.toDouble())
    absMs < MILLIS_PER_HOUR -> {
        val m = absMs / MILLIS_PER_MINUTE
        val s = (absMs % MILLIS_PER_MINUTE) / MILLIS_PER_SECOND
        String.format(Locale.US, "%dm%02ds", m, s)
    }
    else -> {
        val h = absMs / MILLIS_PER_HOUR
        val m = (absMs % MILLIS_PER_HOUR) / MILLIS_PER_MINUTE
        val s = (absMs % MILLIS_PER_MINUTE) / MILLIS_PER_SECOND
        String.format(Locale.US, "%dh%02dm%02ds", h, m, s)
    }
}

/** Formats a delta for the Δt gutter's gap-to-previous-visible-row mode: "+0.140" / "+2.651" /
 *  "+1m02s" / "+1h02m03s" depending on magnitude (see [formatMagnitude]). Negative deltas render
 *  with a "-" sign and their own magnitude, never clamped to zero — see [deltaMillis]. Always
 *  signed, including exact zero ("+0.000") — for the selected-line mode where an exact-zero
 *  "you are here" row needs to read differently, see [formatSignedDelta]. */
fun formatDelta(ms: Long): String {
    val sign = if (ms < 0) "-" else "+"
    return sign + formatMagnitude(kotlin.math.abs(ms))
}

/** Same magnitude formatting as [formatDelta], but the sign is OMITTED when [ms] is exactly zero
 *  ("0.000", not "+0.000"). Used for the Δt gutter's selected-line mode (LogViewer.kt): every row
 *  shows its signed offset from the selected line, and the selected row itself must read as a bare
 *  "0.000" anchor point — "+0.000" would look like a tiny forward gap rather than "you are here." */
fun formatSignedDelta(ms: Long): String {
    if (ms == 0L) return formatMagnitude(0L)
    return formatDelta(ms)
}

/** Which entry id anchors the Δt column's selected-line mode when [selected] holds more than one
 *  id — deterministically the LOWEST id. That's equivalent to "the first in display order" for
 *  any tab: folding/collapsing/sequences only ever HIDE rows, they never reorder them, so display
 *  order tracks ascending entry id everywhere in this app. Returns null (the caller's signal to
 *  fall back to the ordinary previous-visible-row gap) when nothing is selected. */
fun deltaAnchorId(selected: Set<Int>): Int? = selected.minOrNull()

// These two functions size the Δt gutter column (LogViewer.kt's rememberTimeDeltaChars,
// Theme.kt's timeDeltaColumnWidth) for the two DIFFERENT things that column can render, and they
// are deliberately NOT interchangeable — that distinction is the entire reason this comment
// exists. Anchor mode renders anchor-to-row (bounded by the two endpoints, an O(1) lookup).
// Gap mode renders row-to-PREVIOUS-row (bounded by the single largest ADJACENT gap anywhere in
// the file, which can only be found by scanning every consecutive pair — an O(n) pass). A prior
// version of this code used the file's total time SPAN (last ts − first ts) as a stand-in for
// both, on the theory that a span is always at least as large as any gap within it. That's true,
// but it's a wildly loose bound for gap mode specifically: a two-hour log where every consecutive
// row is milliseconds apart would size its column for "+120m00s" while every value actually drawn
// is "+0.005"-shaped — precisely the dead-space bug this pair of functions replaces. Do not
// collapse them back into one "total span" helper.

/** O(1) upper bound for the Δt gutter's ANCHOR-mode column width (a row is selected): every
 *  visible row's signed offset from [anchorTs] is bounded by the wider of (anchor to [firstTs])
 *  and (anchor to [lastTs]) — the true widest string that mode can ever draw is always at one of
 *  those two endpoints, never something in the middle. [firstTs]/[lastTs] are the tab's own first
 *  and last entries by parse order. */
fun widestAnchorDeltaMagnitudeMs(firstTs: String, lastTs: String, anchorTs: String): Long {
    val toFirst = deltaMillis(anchorTs, firstTs)?.let { kotlin.math.abs(it) } ?: 0L
    val toLast = deltaMillis(anchorTs, lastTs)?.let { kotlin.math.abs(it) } ?: 0L
    return maxOf(toFirst, toLast)
}

/** O(n) — the true widest magnitude among every CONSECUTIVE pair's delta in [ts] (parse order).
 *  This is the correct (and only correct) bound for the Δt gutter's GAP-mode column width, since
 *  gap mode renders exactly this quantity — ts[i] vs ts[i-1] — for every visible row. Unlike
 *  [widestAnchorDeltaMagnitudeMs], there is no O(1) shortcut: the largest adjacent gap can sit
 *  anywhere in the file, not just at the endpoints, so every pair must be checked. Callers on a
 *  multi-million-row tab MUST run this off the UI thread — see LogViewer.kt's
 *  rememberTimeDeltaChars, which mirrors ui/Minimap.kt's own off-thread pattern for exactly this
 *  reason. */
fun widestAdjacentGapMagnitudeMs(ts: List<String>): Long {
    var widest = 0L
    for (i in 1 until ts.size) {
        val gap = deltaMillis(ts[i - 1], ts[i])?.let { kotlin.math.abs(it) } ?: 0L
        if (gap > widest) widest = gap
    }
    return widest
}
