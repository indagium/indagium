package com.indagium.utils

import com.indagium.model.LogEntry
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class MergeSourceFile(val tag: String, val entries: List<LogEntry>)

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private const val NANOS_PER_MILLISECOND = 1_000_000L

// LogEntry.ts never carries a date: LogParser strips the MM-DD prefix during parsing for
// threadtime/time formats (see LogParserTest — ts is asserted as bare "HH:mm:ss.SSS"), and
// bare/brief/RAW formats never had one. A calendar-aware merge would need changing LogEntry's ts
// format app-wide, well beyond this task's scope — so v1 merges purely by time-of-day. This is
// correct for the common case (merging buffers from the same bug-report session, which span a
// short window on one day) and explicitly not correct across a midnight rollover or when sources
// are from different days — an accepted limitation, in the same spirit as the plan's own punt on
// cross-year/clock-skew correctness.
fun parseLogTimeOfDay(ts: String): LocalTime? = runCatching { LocalTime.parse(ts, TIME_FORMATTER) }.getOrNull()

// Concatenates every source's entries, sorts by time-of-day, and re-assigns id sequentially
// (LogTab.rmap and every id-based lookup — sequences, stack-trace groups, selection — assume ids
// are unique and tab-scoped, so a merged tab needs its own fresh id space, not the union of each
// source's 1..N range).
//
// Entries whose ts doesn't parse (brief-format, RAW fallback lines) carry forward the last known
// good timestamp from their OWN source file, keeping their original relative position next to
// where they actually occurred rather than scattering them to sort ties elsewhere. An entry
// before any known-good timestamp in its source sorts to the front of the whole merge — rare in
// practice, since real logcat buffers almost always start with a parseable line.
fun mergeLogs(sources: List<MergeSourceFile>): List<LogEntry> {
    data class Timed(val entry: LogEntry, val tag: String, val time: Long?)

    val timed = mutableListOf<Timed>()
    sources.forEach { source ->
        var lastKnown: Long? = null
        source.entries.forEach { entry ->
            val parsed = mergeTimeValue(entry, source.tag)
            if (parsed != null) lastKnown = parsed
            timed += Timed(entry, source.tag, parsed ?: lastKnown)
        }
    }

    // sortedWith/sortedBy are stable in Kotlin — ties (same time-of-day, or all-null before any
    // known-good timestamp) keep the order entries were appended above: source order, then
    // original within-source order.
    val ordered = timed.sortedWith(compareBy(nullsFirst()) { it.time })

    return ordered.mapIndexed { idx, t -> t.entry.copy(id = idx + 1, sourceTag = t.tag) }
}

private fun mergeTimeValue(entry: LogEntry, sourceClockId: String?): Long? {
    val parsed = entry.parsedTime ?: canonicalDltParsedTime(entry, sourceClockId)
    val profileClockSemantics = parsed != null || entry.rawRecord != null ||
        entry.dltTimestampSource in setOf("storage", "relative", "viewer") ||
        (entry.recordMetadata?.usesProfilePresentation == true && entry.recordMetadata.usesLegacyClockSemantics.not())
    if (profileClockSemantics) {
        return parsed?.epochMillis ?: parsed?.localWallMillis ?: parsed?.relativeMillis
            ?: parsed?.localTime?.let(::parseLogTimeOfDay)?.toNanoOfDay()?.div(NANOS_PER_MILLISECOND)
    }
    return parseLogTimeOfDay(entry.ts)?.toNanoOfDay()?.div(NANOS_PER_MILLISECOND)
}
