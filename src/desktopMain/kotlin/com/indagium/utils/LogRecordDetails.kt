package com.indagium.utils

import com.indagium.model.LogEntry
import com.indagium.model.ParsedTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One labelled line of the details header (time, level, component, source lines). */
internal data class LogRecordDetailLine(val key: String, val label: String, val value: String)

/** What the "Record details" dialog shows for one row, with no UI types so it can be tested alone. */
internal data class LogRecordDetails(
    val summary: List<LogRecordDetailLine>,
    /** Every profile field in the row's own order. */
    val fields: List<Pair<String, String>>,
    val rawRecord: String?,
) {
    /** The fields as one JSON object (strings only), keys in row order, ready to paste. */
    val fieldsJson: String
        get() {
            val json: JsonObject = buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }
            return PRETTY_JSON.encodeToString(JsonObject.serializer(), json)
        }
}

/**
 * Whether [this] row has anything beyond its visible columns. A Logcat row has no metadata, and an
 * unparsed Logcat row shares an empty one, so neither offers the dialog.
 */
internal fun LogEntry.hasRecordDetails(): Boolean {
    val metadata = recordMetadata ?: return false
    return metadata.profileFields.isNotEmpty() || metadata.rawRecord != null
}

internal fun buildLogRecordDetails(entry: LogEntry): LogRecordDetails {
    val summary = mutableListOf<LogRecordDetailLine>()
    val originalTime = entry.parsedTime?.original?.takeIf(String::isNotBlank) ?: entry.ts.takeIf(String::isNotBlank)
    if (originalTime != null) summary += LogRecordDetailLine("time", "Time", originalTime)
    entry.parsedTime?.let(::describeParsedTime)?.let { summary += LogRecordDetailLine("parsed-time", "Parsed time", it) }
    summary += LogRecordDetailLine("level", "Level", describeLevel(entry))
    entry.tag.takeIf { it.isNotBlank() && it != "RAW" }?.let { summary += LogRecordDetailLine("component", "Component", it) }
    describeSourceLines(entry.sourceStartLine, entry.sourceEndLine)?.let { summary += LogRecordDetailLine("source", "Source", it) }
    return LogRecordDetails(summary, entry.profileFields.map { (key, value) -> key to value }, entry.rawRecord)
}

private fun describeLevel(entry: LogEntry): String {
    val original = entry.originalSeverity?.takeIf(String::isNotBlank)
    val normalized = entry.level.label
    return when {
        !entry.severityKnown -> if (original != null) "Unknown (original: $original)" else "Unknown"
        original != null && !original.equals(normalized, ignoreCase = true) && !original.equals(entry.level.key.toString(), ignoreCase = true) ->
            "$normalized (original: $original)"
        else -> normalized
    }
}

private fun describeSourceLines(start: Long?, end: Long?): String? = when {
    start == null -> null
    end == null || end == start -> "line $start"
    else -> "lines $start–$end"
}

private fun describeParsedTime(time: ParsedTime): String? {
    time.epochMillis?.let { millis -> return "${runCatching { Instant.ofEpochMilli(millis).toString() }.getOrDefault("$millis ms")} (absolute, UTC)" }
    time.localWallMillis?.let { millis ->
        val text = runCatching {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC).format(WALL_CLOCK)
        }.getOrDefault("$millis ms")
        return "$text (wall clock, no time zone)"
    }
    time.relativeMillis?.let { return "$it ms (relative)" }
    return time.localTime?.let { "$it (time of day)" }
}

private val WALL_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
private val PRETTY_JSON = Json { prettyPrint = true }
