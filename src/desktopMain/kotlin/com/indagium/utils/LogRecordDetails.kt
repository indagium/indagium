package com.indagium.utils

import com.indagium.model.LogEntry
import com.indagium.model.LogFieldRole
import com.indagium.model.ParsedTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

private val ROLE_FIELD_KEYS = LogFieldRole.entries.associate { it.name to it.name.lowercase() }

internal fun isJsonRecord(raw: String?): Boolean {
    if (raw == null) return false
    val end = when (raw.firstOrNull { !it.isWhitespace() }) {
        '{' -> '}'
        '[' -> ']'
        else -> return false
    }
    if (raw.lastOrNull { !it.isWhitespace() } != end) return false
    return runCatching { Json.parseToJsonElement(raw) }
        .getOrNull().let { it is JsonObject || it is JsonArray }
}

/** Generated aliases are removed only when the parser retained their original field. */
internal fun distinctRecordFields(fields: Map<String, String>, aliases: Map<String, String> = emptyMap()): Map<String, String> =
    fields.filter { (key, value) -> value.isNotBlank() && aliases[key]?.let { fields[it] != value } != false }

private fun fieldKey(key: String, fields: Map<String, String>): String =
    ROLE_FIELD_KEYS[key]?.takeUnless { it in fields } ?: key

internal fun LogEntry.dltDetailFields(): Map<String, String> = buildMap {
    dltEcuId?.let { put("ECU", it) }
    dltAppId?.let { put("APID", it) }
    dltContextId?.let { put("CTID", it) }
    pid.takeIf { it != 0 }?.let { put("Session ID", Integer.toUnsignedString(it)) }
    dltMessageType?.let { put("Message type", it) }
    if (dltTimestampSource == "storage") {
        dltTimestamp?.let { put("Storage time", Instant.ofEpochSecond(it).plusMillis(ts.substringAfterLast('.').toLongOrNull() ?: 0).toString()) }
    }
    val ticks = dltUptime ?: dltTimestamp?.takeIf { dltTimestampSource in setOf("relative", "viewer") }
    ticks?.let { put("ECU uptime", "${java.math.BigDecimal.valueOf(it, 4).toPlainString()} s") }
}

private fun LogEntry.hasDltDetails(): Boolean =
    dltEcuId != null || dltAppId != null || dltContextId != null || dltTimestampSource != null || dltMessageType != null

internal fun LogEntry.recordInspectionFields(): Map<String, String> {
    if (hasDltDetails()) return dltDetailFields() + ("message" to msg)
    val fields = distinctRecordFields(profileFields, recordMetadata?.fieldAliases.orEmpty())
    return fields.mapKeys { (key, _) -> fieldKey(key, fields) }
}

internal fun LogEntry.recordInspectionSource(): String {
    val source = rawRecord
    if (source != null && isJsonRecord(source)) return source
    val fields = recordInspectionFields()
    if (fields.isEmpty()) return source ?: msg
    return buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }.toString()
}

internal fun LogEntry.hasRecordDetails(): Boolean {
    if (hasDltDetails()) return true
    val metadata = recordMetadata?.takeIf { it.usesProfilePresentation } ?: return false
    val raw = rawRecord ?: msg
    if (metadata.displayColumns.any { it.id != "message" && it.value.length > MAX_PROFILE_COLUMN_CHARS }) return true
    if (raw.length > LONG_RECORD_CHARS || raw.trimEnd().any { it == '\n' || it == '\r' } || isJsonRecord(raw)) return true
    val fields = distinctRecordFields(profileFields, metadata.fieldAliases)
    if (fields.isEmpty()) return false
    val aliases = metadata.fieldAliases
    val shown = metadata.displayColumns.filter { it.value.isNotBlank() }.flatMapTo(hashSetOf()) { column ->
        val roleKey = LogFieldRole.entries.firstOrNull { it.name.equals(column.id, ignoreCase = true) }?.name
        listOfNotNull(column.id, roleKey, aliases[column.id], roleKey?.let(aliases::get))
    }
    return fields.keys.any { it !in shown }
}

internal fun buildLogRecordDetails(entry: LogEntry): LogRecordDetails {
    val summary = mutableListOf<LogRecordDetailLine>()
    val originalTime = entry.parsedTime?.original?.takeIf(String::isNotBlank) ?: entry.ts.takeIf(String::isNotBlank)
    if (originalTime != null) summary += LogRecordDetailLine("time", "Time", originalTime)
    entry.parsedTime?.let(::describeParsedTime)?.let { summary += LogRecordDetailLine("parsed-time", "Parsed time", it) }
    summary += LogRecordDetailLine("level", "Level", describeLevel(entry))
    entry.tag.takeIf { it.isNotBlank() && it != "RAW" }?.let {
        summary += LogRecordDetailLine("component", if (entry.hasDltDetails()) "Context" else "Component", it)
    }
    describeSourceLines(entry.sourceStartLine, entry.sourceEndLine)?.let { summary += LogRecordDetailLine("source", "Source", it) }
    return LogRecordDetails(summary, entry.recordInspectionFields().toList(), entry.rawRecord)
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

/** A record longer than this is cut in its collapsed row (the row's preview cap), so its details add something. */
private const val LONG_RECORD_CHARS = 180
