package com.indagium.ui

import com.indagium.model.LogEntry
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
