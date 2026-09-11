package com.indagium.utils

import com.indagium.model.LogEntry
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

/** The rollover-corrected gap from [from] to [to], both already-parsed millis-of-day values (see
 *  [parseMillisOfDay]). This is the one place the [ROLLOVER_THRESHOLD_MS] correction is written —
 *  [deltaMillis] (string in, string out) funnels through it, and so does any other caller that
 *  already has two parsed millis-of-day values on hand (e.g. `diagram3`'s delay-gap suggester,
 *  which measures a gap between two message timestamps with no string parsing involved) — so the
 *  midnight-wrap heuristic can't quietly drift out of sync between callers. A small negative
 *  result (e.g. from out-of-order merged sources) is returned as-is rather than clamped to zero —
 *  it's real, if surprising, data; see [deltaMillis]'s own doc for why that matters to a caller.
 *  NOT used by [widestAdjacentGapMagnitudeMs] — that function keeps its own inlined copy of this
 *  correction; see its own comment for why (it parses each entry's `ts` exactly once across a
 *  full pass, where routing through [deltaMillis] would parse every entry twice). */
fun elapsedMillisOfDay(from: Long, to: Long): Long {
    var delta = to - from
    if (delta < -ROLLOVER_THRESHOLD_MS) delta += HOURS_PER_DAY * MILLIS_PER_HOUR
    return delta
}

/** Delta from `prevTs` to `curTs` in milliseconds, or null if either side doesn't parse (blank
 *  ts on brief/RAW rows, or genuinely malformed input). Applies the midnight-rollover correction
 *  via [elapsedMillisOfDay]; a small negative delta (e.g. from out-of-order merged sources) is
 *  returned as-is rather than clamped to zero — it's real, if surprising, data. */
fun deltaMillis(prevTs: String, curTs: String): Long? {
    val prev = parseMillisOfDay(prevTs)
    val cur = parseMillisOfDay(curTs)
    if (prev == TS_UNKNOWN || cur == TS_UNKNOWN) return null
    return elapsedMillisOfDay(prev, cur)
}

// Cap on how many samples of each kind (applied/suppressed) unrollLogTimeline keeps — the Follow
// diagnostic dump (AppState.followDiagnostics) only ever shows a handful, and a pathological log
// with thousands of qualifying rows must not turn every reload into an unbounded list build.
private const val ROLLOVER_SAMPLE_CAP = 5

// How far unrollLogTimeline's lone-outlier check is willing to scan past a candidate-rollover row
// for the next PARSEABLE timestamp (skipping TS_UNKNOWN/brief/RAW rows). Bounded so a long run of
// unparseable rows can't turn the check into an O(n) scan per candidate; when the cap is hit
// without finding one, the lookahead is treated as unavailable (same as end-of-file) and the old
// unconditional-rollover behavior applies — never a false "it's fine" reading of missing data.
private const val ROLLOVER_LOOKAHEAD_SCAN_CAP = 200

/** (id, ts) pair identifying a row that either triggered a committed rollover or was suppressed as
 *  a lone outlier while unrolling a timeline — see [unrollLogTimeline]. Deliberately just id+ts,
 *  never message/tag: this exists to be printed in AppState's Follow diagnostic dump, which the
 *  user hands back over a confidential log. */
data class RolloverSample(val id: Int, val ts: String)

/**
 * Result of [unrollLogTimeline]: the day-unrolled elapsed timeline for one sequence of log entries,
 * plus the rollover diagnostics a caller needs to explain it. [byId] maps each mappable entry id (a
 * row with a parseable `ts`) to its unrolled elapsed milliseconds, in the same order as the input
 * entries — entries with an unparseable `ts` (`TS_UNKNOWN`) are simply absent. The remaining fields
 * mirror AppState's `LogElapsedIndex` rollover fields one for one — [rolloverAppliedCount]/
 * [rolloverAppliedSamples] and [rolloverSuppressedCount]/[rolloverSuppressedSamples] ALWAYS reflect
 * what the per-row classifier decided, regardless of [dayOffsetModelValid]; see
 * [unrollLogTimeline]'s doc comment for the algorithm, the lone-outlier guard, and what
 * [dayOffsetModelValid] means when false.
 */
data class UnrolledLogTimeline(
    val byId: Map<Int, Long>,
    val rolloverAppliedCount: Int,
    val rolloverAppliedSamples: List<RolloverSample>,
    val rolloverSuppressedCount: Int,
    val rolloverSuppressedSamples: List<RolloverSample>,
    val dayOffsetModelValid: Boolean,
)

// Forward scan from `fromIndex` for the next row with a parseable `ts`, skipping TS_UNKNOWN
// (blank ts on brief/RAW rows) — bounded by ROLLOVER_LOOKAHEAD_SCAN_CAP. Returns null when the
// scan runs off the end of the log or hits the cap first, either of which the caller treats as
// "no confirmation available."
private fun nextParseableMillis(data: List<LogEntry>, fromIndex: Int): Long? {
    val limit = minOf(data.size, fromIndex + ROLLOVER_LOOKAHEAD_SCAN_CAP)
    for (i in fromIndex until limit) {
        val millis = parseMillisOfDay(data[i].ts)
        if (millis != TS_UNKNOWN) return millis
    }
    return null
}

/**
 * Unfolds time-of-day timestamps in log order into an elapsed timeline. Unlike a one-off
 * `deltaMillis(anchor, row)`, this works in both directions around midnight and across
 * multiple rollovers while preserving small real backwards jumps. Rows without a timestamp
 * remain unmappable.
 *
 * A backwards jump past half a day is ambiguous on `ts` alone (no date survives parsing — see
 * parseMillisOfDay): it is either a genuine midnight rollover, or a single anomalous row (a
 * stale clock, a concatenated bugreport section on a different time base, an out-of-order merge
 * artifact) that has nothing to do with the next day. Committing +24h for the latter is
 * permanent and silent — every later row inherits the wrong offset, and Follow (which floors on
 * this timeline) then strands itself on the last row before the bad one for the rest of
 * playback, unable to ever reach anything after it. Distinguished here by looking one row
 * further: a genuine rollover's very next parseable row continues from the LOW post-jump time;
 * a lone outlier's next row instead resumes close to the PRE-jump baseline, as if the bad row
 * had never appeared. Only that second, confirming row makes the call — a single sample is
 * deliberately not enough to accuse a row of being fabricated. When no confirming row exists
 * (candidate is at/near end of file, or ROLLOVER_LOOKAHEAD_SCAN_CAP is exhausted by unparseable
 * rows), the old unconditional-rollover behavior applies, since there is nothing to demonstrate
 * it should be suppressed and the existing real-rollover tests must still pass on a
 * lookahead-free (2-row) fixture.
 *
 * A single accumulating `dayOffset` additionally assumes the log is ONE monotonically-advancing
 * capture — true for a plain logcat grab, false for an Android bug report, which concatenates
 * several buffers (main/system/radio/events/kernel) one after another. LogParser drops the
 * `------ ... LOG ------` separators between them (nothing survives to mark the boundary), and
 * each buffer restarts at its OWN earlier timestamp for potentially thousands of rows — too many
 * to be a lone outlier, so the guard above correctly commits the rollover. But that commit is
 * permanent: every row for the rest of the file inherits +24h, including once a later buffer
 * resumes at the ORIGINAL (correct) time-of-day, which now reads as tomorrow and can never again
 * be reached by a floor search. This is not a hypothetical — it is the confirmed mechanism behind
 * a real report where Follow held ~46s (later, ~24h) behind a correct target.
 *
 * Detecting this from `ts` alone (no date survives parsing) leans on one fact: a genuine midnight
 * crossing never sees the log's raw time-of-day climb back up to (or past) where it was just
 * before the crossing — that would take another ~24h of real capture. A concatenated buffer's
 * next segment can and does resume anywhere, including back above the pre-jump point, because its
 * clock has nothing to do with the previous buffer's. So: once ANY row after the first committed
 * rollover has a raw time-of-day exceeding that rollover's pre-jump baseline, or a SECOND
 * candidate rollover ever gets committed, the single-timeline assumption is treated as violated
 * for the WHOLE file and `dayOffset` is abandoned entirely — every row maps to its bare
 * millis-of-day instead (`dayOffsetModelValid = false`). Most real logcat captures span far less
 * than 24h, so raw time-of-day is then exactly the right timeline, multi-buffer or not.
 *
 * This does mean a log that both crosses midnight for real AND concatenates buffers (or crosses
 * midnight twice) cannot be told apart from a multi-buffer log by `ts` alone — genuinely
 * ambiguous, and resolved here in favor of raw time-of-day, which degrades far more gracefully for
 * Follow (a floor search that's merely non-monotonic in a few places) than a silent, permanent
 * +24h would (a floor search that's provably and unrecoverably wrong for the rest of the file).
 * Deliberately scoped to THIS timeline only, not [deltaMillis] or [com.indagium.utils.LogMerge]'s
 * own 12h heuristics: those compute one-off deltas between ADJACENT rows for display (the Δt
 * gutter, a merge sort key) — a wrong call there mislabels one row's shown delta, it does not
 * accumulate into a permanent, unbounded corruption of everything after it the way this timeline's
 * running `dayOffset` does, so the failure mode this guards against does not exist there.
 */
fun unrollLogTimeline(data: List<LogEntry>): UnrolledLogTimeline {
    val byId = LinkedHashMap<Int, Long>()
    var previousMillis: Long? = null
    var dayOffset = 0L
    // Same value as elapsedMillisOfDay's HOURS_PER_DAY * MILLIS_PER_HOUR, reused for consistency —
    // just spelled out with those named constants instead of the caller's original inline literal.
    val dayMs = HOURS_PER_DAY * MILLIS_PER_HOUR
    val rolloverSamples = mutableListOf<RolloverSample>()
    var rolloverCount = 0
    val suppressedSamples = mutableListOf<RolloverSample>()
    var suppressedCount = 0
    // Set on the FIRST committed (non-suppressed) rollover to that row's pre-jump baseline —
    // see the doc comment above. Once set, every later row (any buffer, any segment) is checked
    // against this SAME value for the rest of the file, not just the row that set it: a
    // concatenated buffer resuming above the original time-of-day is the tell, however many rows
    // later that resumption happens to land.
    var firstCommittedBaseline: Long? = null
    var modelInvalidated = false
    for (i in data.indices) {
        val entry = data[i]
        val millis = parseMillisOfDay(entry.ts)
        if (millis == TS_UNKNOWN) continue
        firstCommittedBaseline?.let { fcb -> if (millis > fcb) modelInvalidated = true }
        val baseline = previousMillis
        // Preserve LogTime.deltaMillis's established interpretation: only a backwards jump
        // larger than half a day is midnight-shaped at all. Small backwards jumps are real
        // out-of-order rows/clock corrections, not a fabricated next-day recording, and never
        // reach this branch.
        if (baseline != null && millis - baseline < -(dayMs / 2)) {
            val next = nextParseableMillis(data, i + 1)
            val resumesPreJumpBaseline = next != null && kotlin.math.abs(next - baseline) <= dayMs / 2
            val doesNotContinueFromThisRow = next != null && kotlin.math.abs(next - millis) > dayMs / 2
            if (resumesPreJumpBaseline && doesNotContinueFromThisRow) {
                // Lone outlier: leave dayOffset (and `previousMillis`, the comparison baseline
                // for the row after this one) untouched, so the next row is judged against the
                // same pre-jump baseline this row itself failed to continue from. This row still
                // gets an elapsed value (so it has *something* mappable), just an unreliable one
                // — expected to make the caller's `ascending` check false, which is exactly what
                // routes AppState's floor search to the already-existing linear-scan fallback
                // instead of silently trusting a corrupted binary search.
                suppressedCount++
                if (suppressedSamples.size < ROLLOVER_SAMPLE_CAP) suppressedSamples += RolloverSample(entry.id, entry.ts)
                byId[entry.id] = dayOffset + millis
                continue
            }
            dayOffset += dayMs
            rolloverCount++
            if (rolloverSamples.size < ROLLOVER_SAMPLE_CAP) rolloverSamples += RolloverSample(entry.id, entry.ts)
            if (firstCommittedBaseline == null) {
                firstCommittedBaseline = baseline
            } else {
                // A second committed rollover is itself invalidating, independent of whether
                // anything ever climbs back above the first baseline — see doc comment.
                modelInvalidated = true
            }
        }
        byId[entry.id] = dayOffset + millis
        previousMillis = millis
    }

    val dayOffsetModelValid = !modelInvalidated
    if (!dayOffsetModelValid) {
        // Discard the dayOffset-accumulated values and rebuild with the offset pinned at 0 for
        // every row — raw time-of-day, unconditionally. Only paid on this rare path: the common
        // (single-timeline) case above already built the real result in one pass.
        byId.clear()
        for (entry in data) {
            val millis = parseMillisOfDay(entry.ts)
            if (millis != TS_UNKNOWN) byId[entry.id] = millis
        }
    }

    return UnrolledLogTimeline(
        byId = byId,
        rolloverAppliedCount = rolloverCount,
        rolloverAppliedSamples = rolloverSamples,
        rolloverSuppressedCount = suppressedCount,
        rolloverSuppressedSamples = suppressedSamples,
        dayOffsetModelValid = dayOffsetModelValid,
    )
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

/** Formats a bare DURATION (never a delta — no sign, magnitude only; caller owns whether negative
 *  input can even occur) the way a human would say it out loud: `850ms` below a second, `4.2s`
 *  below a minute, `3m 05s` below an hour, `1h 02m` beyond that. Deliberately its own scale rather
 *  than reusing [formatMagnitude]: that one renders sub-minute as unitless `"42.000"` (fine
 *  *inside* a signed `+42.000` delta, where the sign plus gutter context supplies the unit, but
 *  read alone — a delay marker's label, a duration standing by itself in a sentence — it's not
 *  obviously seconds) and keeps `HH:MM:SS`-style zero-padding all the way down to milliseconds.
 *  Also deliberately NOT [formatDelta]/[formatSignedDelta]: those are always signed, and a
 *  duration is not a delta — there is no "before/after" side to point a sign at. And deliberately
 *  never `HH:MM:SS.mmm` at any band: that format implies a precision — down to the millisecond,
 *  no matter how large the value — that a coarse duration (e.g. a 30s-threshold delay-gap
 *  suggestion) doesn't actually have; the banded units here communicate roughly how precisely the
 *  value is known, the same way "about 3 minutes" reads differently from "180.000 seconds" even
 *  when they're the same duration. */
fun formatDuration(ms: Long): String {
    val absMs = kotlin.math.abs(ms)
    return when {
        absMs < MILLIS_PER_SECOND -> "${absMs}ms"
        absMs < MILLIS_PER_MINUTE -> String.format(Locale.US, "%.1fs", absMs / MILLIS_PER_SECOND.toDouble())
        absMs < MILLIS_PER_HOUR -> {
            val m = absMs / MILLIS_PER_MINUTE
            val s = (absMs % MILLIS_PER_MINUTE) / MILLIS_PER_SECOND
            String.format(Locale.US, "%dm %02ds", m, s)
        }
        else -> {
            val h = absMs / MILLIS_PER_HOUR
            val m = (absMs % MILLIS_PER_HOUR) / MILLIS_PER_MINUTE
            String.format(Locale.US, "%dh %02dm", h, m)
        }
    }
}

/** Formats a raw elapsed-timeline value — as produced by AppState's day-unrolled follow index,
 *  where midnight rollovers accumulate whole [HOURS_PER_DAY]-hour offsets rather than wrapping —
 *  back into an "HH:MM:SS.mmm" wall-clock string, by wrapping modulo 24h. Purely a display inverse
 *  of that unrolling (e.g. the Follow diagnostic dump's "computed mapped target" line); it does not
 *  recover which calendar day a multi-rollover value belonged to, only the time-of-day. A negative
 *  input (a target before the log's own start) wraps forward into the same 24h range rather than
 *  producing a negative clock string. */
fun formatElapsedAsClock(elapsedMs: Long): String {
    val dayMs = HOURS_PER_DAY * MILLIS_PER_HOUR
    val ms = ((elapsedMs % dayMs) + dayMs) % dayMs
    val hh = ms / MILLIS_PER_HOUR
    val mm = (ms % MILLIS_PER_HOUR) / MILLIS_PER_MINUTE
    val ss = (ms % MILLIS_PER_MINUTE) / MILLIS_PER_SECOND
    val mmm = ms % MILLIS_PER_SECOND
    return String.format(Locale.US, "%02d:%02d:%02d.%03d", hh, mm, ss, mmm)
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

/** O(n) — the true widest magnitude among every CONSECUTIVE pair's delta across [entries]' own
 *  `ts` fields (parse order). This is the correct (and only correct) bound for the Δt gutter's
 *  GAP-mode column width, since gap mode renders exactly this quantity — entries[i].ts vs
 *  entries[i-1].ts — for every visible row. Unlike [widestAnchorDeltaMagnitudeMs], there is no
 *  O(1) shortcut: the largest adjacent gap can sit anywhere in the file, not just at the
 *  endpoints, so every pair must be checked. Callers on a multi-million-row tab MUST run this off
 *  the UI thread — see LogViewer.kt's rememberTimeDeltaChars, which mirrors ui/Minimap.kt's own
 *  off-thread pattern for exactly this reason.
 *
 *  Takes [entries] directly (not a `List<String>` of timestamps) so a multi-million-row tab never
 *  materializes a second, equally huge list just to call this. Each entry's `ts` is parsed exactly
 *  ONCE — `prevMillis` carries the previous iteration's already-parsed value forward instead of
 *  going through [deltaMillis] (which would parse BOTH its arguments on every call, meaning every
 *  entry's ts got parsed twice over a full pass: once as "cur" for its own gap, again as "prev"
 *  for the next one). */
fun widestAdjacentGapMagnitudeMs(entries: List<LogEntry>): Long {
    if (entries.isEmpty()) return 0L
    var widest = 0L
    var prevMillis = parseMillisOfDay(entries[0].ts)
    for (i in 1 until entries.size) {
        val curMillis = parseMillisOfDay(entries[i].ts)
        if (prevMillis != TS_UNKNOWN && curMillis != TS_UNKNOWN) {
            var delta = curMillis - prevMillis
            if (delta < -ROLLOVER_THRESHOLD_MS) delta += HOURS_PER_DAY * MILLIS_PER_HOUR
            val gap = kotlin.math.abs(delta)
            if (gap > widest) widest = gap
        }
        prevMillis = curMillis
    }
    return widest
}
