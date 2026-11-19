package com.indagium.ui

import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogTab
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val UPTIME_UNITS_PER_SECOND = 10_000.0
private val STORED_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

/**
 * What a DLT row's time cell can say on hover that the row cannot fit: the storage date (the row shows
 * the time of day only) and the ECU's uptime (TMSP), kept beside the storage time. Null for a non-DLT
 * row or one with nothing to add.
 */
internal fun dltTimeHint(entry: LogEntry, zone: ZoneId = ZoneId.systemDefault()): String? {
    if (entry.dltEcuId == null && entry.dltAppId == null && entry.dltContextId == null) return null
    val parts = buildList {
        when (entry.dltTimestampSource) {
            "storage" -> entry.dltTimestamp?.let { seconds ->
                runCatching { Instant.ofEpochSecond(seconds).atZone(zone).format(STORED_FORMAT) }.getOrNull()?.let { add("Stored $it") }
            }
            "relative", "viewer" -> entry.dltTimestamp?.let { add("ECU uptime ${uptimeSeconds(it)} s") }
        }
        entry.dltUptime?.let { add("ECU uptime ${uptimeSeconds(it)} s") }
    }
    return parts.distinct().joinToString(" · ").ifEmpty { null }
}

private fun uptimeSeconds(units: Long): String = String.format(Locale.ROOT, "%.4f", units / UPTIME_UNITS_PER_SECOND)

private const val DLT_HEADER_SAMPLE_ROWS = 2_000
private const val TID_FIELD_CHARS = 5

private val dltHeaderCache = object : LinkedHashMap<String, Pair<List<LogEntry>, String>>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<List<LogEntry>, String>>?): Boolean = size > 16
}

/**
 * The column header of a DLT tab, in the rows' own font like a profile tab's, so the labels sit over the
 * fields: TIMESTAMP, SID over the session (the pid field), nothing over the thread field (a DLT frame has
 * none), L over the level letter, ECU/APP/CTX over the tag, MESSAGE after the longest tag.
 * Mirrors the row text "ts  pid tid  L  ECU/APP/CTX: message". Null for any other tab.
 */
internal fun dltColumnHeader(tab: LogTab, showPidTid: Boolean, pidFieldChars: Int): String? {
    if (tab.logFormat != LogFormat.DLT || tab.logData.firstOrNull()?.recordMetadata?.usesProfilePresentation == true) return null
    val key = "${tab.id}|$showPidTid|$pidFieldChars"
    synchronized(dltHeaderCache) {
        dltHeaderCache[key]?.takeIf { it.first === tab.logData }?.let { return it.second }
    }
    val rows = tab.logData
    val sample = if (rows.size <= 2 * DLT_HEADER_SAMPLE_ROWS) {
        rows
    } else {
        rows.subList(0, DLT_HEADER_SAMPLE_ROWS) + rows.subList(rows.size - DLT_HEADER_SAMPLE_ROWS, rows.size)
    }
    val tsWidth = maxOf("TIMESTAMP".length, sample.maxOfOrNull { it.ts.length } ?: 0)
    val tagWidth = maxOf("ECU/APP/CTX".length, sample.maxOfOrNull { it.tag.length } ?: 0)
    val header = buildString {
        append("TIMESTAMP".padEnd(tsWidth))
        if (showPidTid && sample.any { it.pid > 0 }) {
            append("  ")
            append("SID".padEnd(pidFieldChars))
            append(" ".repeat(1 + TID_FIELD_CHARS))
        }
        append("  ")
        // The level is one letter followed by two spaces; "LVL" would run into the tag label, so the column is "L".
        append("L  ")
        append("ECU/APP/CTX".padEnd(tagWidth + 2))
        append("MESSAGE")
    }
    synchronized(dltHeaderCache) { dltHeaderCache[key] = rows to header }
    return header
}
