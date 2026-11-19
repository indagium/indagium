package com.indagium.ui

import com.indagium.model.LogEntry
import com.indagium.model.LogDisplayField
import com.indagium.model.displaySeverityText
import com.indagium.utils.ProfileColumnLayout
import com.indagium.utils.ProfileDisplayTextProjection
import com.indagium.utils.ProfileRenderedFieldRange
import com.indagium.utils.ProfileTextOffsetSegment
import com.indagium.utils.boundedProfileCell
import com.indagium.utils.visibleLogLineText
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

internal fun dltGridLayout(split: Boolean, hasSession: Boolean): ProfileColumnLayout {
    val columns = buildList {
        add(LogDisplayField("timestamp", "Timestamp", ""))
        if (hasSession) add(LogDisplayField("session", "SID", ""))
        add(LogDisplayField("severity", "Level", ""))
        if (split) {
            add(LogDisplayField("ecu", "ECU", ""))
            add(LogDisplayField("apid", "APID", ""))
            add(LogDisplayField("ctid", "CTID", ""))
        } else {
            add(LogDisplayField("context", "ECU/APID/CTID", ""))
        }
        add(LogDisplayField("message", "Message", ""))
    }
    val fixed = mapOf("timestamp" to 14, "session" to 10, "severity" to 3, "ecu" to 4, "apid" to 4, "ctid" to 4, "context" to 14)
    return ProfileColumnLayout(columns, fixed.filterKeys { key -> columns.any { it.id == key } })
}

/** Align native DLT fields while translating Find/highlighter offsets from the canonical log line. */
internal fun dltGridProjection(entry: LogEntry, split: Boolean, widths: Map<String, Int>): ProfileDisplayTextProjection {
    val source = visibleLogLineText(entry)
    val text = StringBuilder()
    val segments = mutableListOf<ProfileTextOffsetSegment>()
    val fields = mutableListOf<ProfileRenderedFieldRange>()

    fun cell(id: String, value: String, sourceStart: Int = -1, sourceLength: Int = value.length) {
        if (text.isNotEmpty()) text.append("  ")
        val start = text.length
        val width = widths[id]
        val visible = if (width != null) boundedProfileCell(value, width) else value
        text.append(visible)
        if (sourceStart >= 0) segments += ProfileTextOffsetSegment(sourceStart, sourceStart + sourceLength, start, text.length)
        if (visible.isNotEmpty()) fields += ProfileRenderedFieldRange(id, start, text.length)
        if (width != null) text.append(" ".repeat((width - visible.length).coerceAtLeast(0)))
    }

    cell("timestamp", entry.ts, 0)
    if ("session" in widths) {
        val canonicalSid = entry.pid.takeIf { it > 0 }?.toString()?.padStart(5)
        val sid = entry.pid.takeIf { it != 0 }?.let(Integer::toUnsignedString)
        cell("session", sid.orEmpty(), if (canonicalSid != null) entry.ts.length + 2 else -1, canonicalSid?.length ?: 0)
    }

    val idChars = if (entry.pid > 0) 2 + entry.pid.toString().padStart(5).length + 1 + entry.tid.toString().padStart(5).length else 0
    val levelStart = entry.ts.length + idChars + 2
    cell("severity", entry.displaySeverityText, levelStart)
    val tagStart = levelStart + entry.displaySeverityText.length + 2
    if (split) {
        var sourceOffset = tagStart
        for ((id, value) in listOf("ecu" to entry.dltEcuId, "apid" to entry.dltAppId, "ctid" to entry.dltContextId)) {
            if (value.isNullOrEmpty()) {
                cell(id, "")
            } else {
                cell(id, value, sourceOffset)
                sourceOffset += value.length + 1
            }
        }
    } else {
        cell("context", entry.tag, tagStart)
    }
    val messageStart = (tagStart + entry.tag.length + 2).coerceAtMost(source.length)
    cell("message", entry.msg, messageStart)
    return ProfileDisplayTextProjection(text.toString(), sourceSegments = segments, renderedFields = fields)
}
