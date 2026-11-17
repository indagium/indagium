package com.indagium.utils

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.indagium.model.BUILTIN_LOG_FORMAT_PROFILES
import com.indagium.model.LogDisplayField
import com.indagium.model.LogEntry
import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormat
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.LogLevel
import com.indagium.model.LogRecordMetadata
import com.indagium.model.ParsedTime
import com.indagium.model.logFormatDisplayColumnDefinitions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.Reader
import java.io.StringReader
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val JSON_SECTION_STATUS = "indagium_section_status"
private const val METADATA_JSON_MAX_DEPTH = 64
private const val METADATA_JSON_MAX_VALUE_CHARS = 256 * 1024
private const val METADATA_JSON_MAX_RECORD_CHARS = 4 * 1024 * 1024
private const val METADATA_JSON_MAX_FIELDS = 128
private const val METADATA_JSON_NANOS_TO_MILLIS = 6
private const val METADATA_JSON_CANCELLATION_TOKENS = 128
private val METADATA_JSON_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT)
private val METADATA_JSON_FACTORY = JsonFactory.builder().streamReadConstraints(
    StreamReadConstraints.builder().maxNestingDepth(METADATA_JSON_MAX_DEPTH).maxStringLength(METADATA_JSON_MAX_VALUE_CHARS).build(),
).build()

internal fun metadataJsonProfile(): LogFormatProfile = BUILTIN_LOG_FORMAT_PROFILES.first { it.engine == LogFormatEngine.METADATA_JSON }

/** Recognizes packet metadata before a potentially large payload; the bounded prefix may end inside that payload. */
internal fun isMetadataJsonSample(text: String): Boolean {
    if (text.firstOrNull { !it.isWhitespace() } !in setOf('{', '[')) return false
    val sample = scanMetadataJson(StringReader(text), maxRecords = 1)
    return sample.packetMetadataSeen || sample.records.isNotEmpty() || sample.sectionStatus.isNotEmpty()
}

private data class MetadataJsonSummary(val value: JsonObject, val section: String, val startLine: Long, val endLine: Long)

private data class MetadataJsonPacket(val value: JsonObject, val section: String, val startLine: Long, val endLine: Long)

private data class MetadataJsonScan(
    val records: List<MetadataJsonPacket>, val diagnostics: List<String>, val lines: Long, val arrayPaths: Set<List<String>>,
    val sectionStatus: Map<String, JsonObject>, val summaries: List<MetadataJsonSummary>, val packetMetadataSeen: Boolean,
)

private data class MetadataJsonValue(val element: JsonElement, val chars: Int)

private class MetadataJsonSampleComplete : RuntimeException(null, null, false, false)

/**
 * Walk envelopes within bounded trees, extracting metadata records and object-valued sections.
 * Nested arrays and linked objects remain inside their containing record's original JSON tree.
 */
private class MetadataJsonScanner(private val parser: JsonParser, private val maxRecords: Int) {
    val records = mutableListOf<MetadataJsonPacket>()
    val summaries = mutableListOf<MetadataJsonSummary>()
    val diagnostics = mutableListOf<String>()
    val arrayPaths = linkedSetOf<List<String>>()
    var sectionStatus: Map<String, JsonObject> = emptyMap()
    var packetMetadataSeen = false
        private set
    private var tokens = 0

    private fun next(): JsonToken? {
        if (++tokens % METADATA_JSON_CANCELLATION_TOKENS == 0) checkParserCancellation()
        return parser.nextToken()
    }

    fun readDocument() {
        while (next() != null) readValue(emptyList())
    }

    private fun readValue(path: List<String>): MetadataJsonValue? = when (parser.currentToken()) {
        JsonToken.START_OBJECT -> readObject(path)
        JsonToken.START_ARRAY -> readArray(path)
        JsonToken.VALUE_STRING -> parser.text.let { MetadataJsonValue(JsonPrimitive(it), it.length) }
        JsonToken.VALUE_NULL -> MetadataJsonValue(kotlinx.serialization.json.JsonNull, 4)
        JsonToken.VALUE_TRUE -> MetadataJsonValue(JsonPrimitive(true), 4)
        JsonToken.VALUE_FALSE -> MetadataJsonValue(JsonPrimitive(false), 5)
        JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT ->
            parser.decimalValue.let { MetadataJsonValue(JsonPrimitive(it), it.toString().length) }
        else -> error("Expected a JSON value at line ${parser.currentLocation().lineNr}.")
    }

    private fun readObject(path: List<String>): MetadataJsonValue? {
        val startLine = parser.currentTokenLocation().lineNr.toLong()
        val values = linkedMapOf<String, JsonElement>()
        val firstRecord = records.size
        val firstSummary = summaries.size
        var recordMetadata = false
        var chars = 2
        var oversized = false
        while (next() != JsonToken.END_OBJECT) {
            require(parser.currentToken() == JsonToken.FIELD_NAME) { "Incomplete metadata JSON object." }
            val key = parser.currentName()
            require(next() != null) { "Incomplete metadata JSON value." }
            val child = readValue(path + key)
            if (key == "metadata" && child?.element is JsonObject && hasRecordMetadata(child.element)) recordMetadata = true
            chars += key.length + (child?.chars ?: 0)
            if (chars > METADATA_JSON_MAX_RECORD_CHARS) oversized = true
            if (oversized) values.clear() else if (child != null) values[key] = child.element
        }
        if (oversized) {
            discardOversizedObject(recordMetadata, firstRecord, firstSummary)
            return null
        }
        val value = JsonObject(values)
        if (maxRecords == 1 && path.lastOrNull() == "metadata" && hasRecordMetadata(value)) {
            packetMetadataSeen = true
            throw MetadataJsonSampleComplete()
        }
        if (path == listOf(JSON_SECTION_STATUS)) sectionStatus = value.mapNotNull { (key, child) ->
            (child as? JsonObject)?.let { key to it }
        }.toMap()
        if (!isMetadataRecord(value)) {
            if (path.size == 2 && path[0] != JSON_SECTION_STATUS) {
                summaries += MetadataJsonSummary(value, path.joinToString(" / "), startLine, parser.currentLocation().lineNr.toLong())
            }
            return MetadataJsonValue(value, chars)
        }
        discardNestedRecords(firstRecord, firstSummary)
        records += MetadataJsonPacket(value, path.joinToString(" / "), startLine, parser.currentLocation().lineNr.toLong())
        if (records.size >= maxRecords) throw MetadataJsonSampleComplete()
        return MetadataJsonValue(value, chars)
    }

    private fun discardOversizedObject(recordMetadata: Boolean, firstRecord: Int, firstSummary: Int) {
        if (recordMetadata) discardNestedRecords(firstRecord, firstSummary)
        if (recordMetadata || records.size == firstRecord) {
            diagnostics.addJsonDiagnostic("A JSON object exceeded the 4 MiB record limit; complete records remain available.")
        }
    }

    private fun discardNestedRecords(firstRecord: Int, firstSummary: Int) {
        records.subList(firstRecord, records.size).clear()
        summaries.subList(firstSummary, summaries.size).clear()
    }

    private fun readArray(path: List<String>): MetadataJsonValue? {
        arrayPaths += path
        val values = mutableListOf<JsonElement>()
        var chars = 2
        var oversized = false
        while (next() != JsonToken.END_ARRAY) {
            require(parser.currentToken() != null) { "Incomplete metadata JSON array." }
            val child = readValue(path)
            chars += child?.chars ?: 0
            if (chars > METADATA_JSON_MAX_RECORD_CHARS) oversized = true
            if (oversized) values.clear() else if (child != null) values += child.element
        }
        return if (oversized) null else MetadataJsonValue(JsonArray(values), chars)
    }
}

private fun isMetadataRecord(value: JsonObject): Boolean {
    val metadata = value["metadata"] as? JsonObject ?: return false
    return hasRecordMetadata(metadata)
}

private fun hasRecordMetadata(metadata: JsonObject): Boolean {
    val hasTime = metadata.text("system_time")?.toBigDecimalOrNull() != null || metadata.text("timestamp")?.toBigDecimalOrNull() != null
    val hasEventMetadata = "source" in metadata && "title" in metadata
    // Source and time can be null for generated records; retaining those rows is essential.
    return metadata.text("identity") != null && (hasTime || hasEventMetadata)
}

private fun scanMetadataJson(reader: Reader, maxRecords: Int = Int.MAX_VALUE): MetadataJsonScan {
    METADATA_JSON_FACTORY.createParser(reader).use { parser ->
        val scanner = MetadataJsonScanner(parser, maxRecords)
        try {
            scanner.readDocument()
        } catch (_: MetadataJsonSampleComplete) {
            // Detection only needs one complete metadata object or packet.
        } catch (failure: JsonProcessingException) {
            scanner.diagnostics.addJsonDiagnostic(
                "Metadata JSON is damaged or truncated at line ${parser.currentLocation().lineNr}: ${failure.originalMessage}",
            )
        } catch (failure: IllegalArgumentException) {
            scanner.diagnostics.addJsonDiagnostic(failure.message ?: "Incomplete metadata JSON document.")
        }
        return MetadataJsonScan(
            scanner.records, scanner.diagnostics, parser.currentLocation().lineNr.toLong(),
            scanner.arrayPaths, scanner.sectionStatus, scanner.summaries, scanner.packetMetadataSeen,
        )
    }
}

internal fun parseMetadataJson(
    reader: Reader,
    profile: LogFormatProfile = metadataJsonProfile(),
    startId: Int = 1,
    sourceClockId: String? = null,
    sourceLineOffset: Long = 0,
    sectionNameHint: String? = null,
): ParsedLog {
    val viewProfile = metadataJsonPresentationProfile(profile)
    val scanned = scanMetadataJson(reader)
    // BigDecimal avoids losing nanosecond ordering in 19-digit epoch values in metadata records.
    val ordered = scanned.records.sortedWith(compareBy(nullsLast()) { packet: MetadataJsonPacket ->
        (packet.value["metadata"] as JsonObject).text("system_time")?.toBigDecimalOrNull()
            ?: (packet.value["metadata"] as JsonObject).text("timestamp")?.toBigDecimalOrNull()
    })
    val compactor = ProfileRowCompactor()
    val packets = ordered.mapIndexed { index, packet ->
        if (index % METADATA_JSON_CANCELLATION_TOKENS == 0) checkParserCancellation()
        metadataJsonEntry(packet, startId + index, sourceClockId, sourceLineOffset, compactor, viewProfile, sectionNameHint)
    }
    val recordPaths = scanned.records.map { it.section }.toSet()
    val summaries = scanned.summaries.filter { summary ->
        recordPaths.none { it.isEmpty() || summary.section == it || summary.section.startsWith("$it / ") }
    }.mapIndexed { index, summary ->
        metadataJsonObjectEntry(summary, startId + packets.size + index, sourceLineOffset, viewProfile, compactor)
    }
    val entries = packets + summaries
    val sectionRows = entries.groupBy { it.profileFields.getValue("section") }
    val sectionParents = sectionRows.keys.filter { " / " in it }.map { it.substringBeforeLast(" / ") }.toSet()
    val sectionIds = linkedSetOf<String>().apply {
        scanned.arrayPaths.filter { path ->
            val section = path.joinToString(" / ")
            path.firstOrNull() != JSON_SECTION_STATUS &&
                (path.size == 2 || path.dropLast(1).joinToString(" / ") in sectionParents) &&
                recordPaths.none { it.isEmpty() || section.startsWith("$it / ") }
        }
            .mapTo(this) { it.joinToString(" / ") }
        addAll(sectionRows.keys)
        addAll(scanned.sectionStatus.keys)
    }
    val sections = sectionIds.map { id ->
        ParsedLogSection(
            id, id.substringAfterLast(" / "), id.substringBeforeLast(" / ", "JSON document"), sectionRows[id].orEmpty(),
            unavailableReason = scanned.sectionStatus[id]?.text("unavailable_reason"),
            decodeNotice = scanned.sectionStatus[id]?.text("decode_notice"),
            decodeSource = scanned.sectionStatus[id]?.text("decode_source"),
        )
    }
    val diagnostics = scanned.diagnostics + "Read ${entries.size} JSON records in ${sections.size} sections."
    return ParsedLog(
        LogFormat.LOGCAT, entries, viewProfile, diagnostics, sourceClockId, sourceLineOffset + scanned.lines, sections = sections,
    ).selectSection(null)
}

private fun metadataJsonObjectEntry(
    summary: MetadataJsonSummary, id: Int, sourceLineOffset: Long, profile: LogFormatProfile, compactor: ProfileRowCompactor,
): LogEntry {
    val title = summary.section.substringAfterLast(" / ") + " details"
    return LogEntry(
        id, "", LogLevel.V, "Metadata", title,
        recordMetadata = LogRecordMetadata(
            profileFields = mapOf("section" to summary.section, "source" to "JSON document", "identity" to "JSON.Metadata"),
            displayColumns = metadataJsonColumns(
                profile, mapOf("source" to "JSON document", "component" to "Metadata", "message" to title), emptyMap(), compactor,
            ),
            severityKnown = false, rawRecord = summary.value.toString(), usesProfilePresentation = true,
            sourceStartLine = sourceLineOffset + summary.startLine, sourceEndLine = sourceLineOffset + summary.endLine,
        ),
    )
}

private fun metadataJsonEntry(
    packet: MetadataJsonPacket,
    id: Int,
    sourceClockId: String?,
    sourceLineOffset: Long,
    compactor: ProfileRowCompactor,
    profile: LogFormatProfile,
    sectionNameHint: String?,
): LogEntry {
    val metadata = packet.value["metadata"] as JsonObject
    val identity = metadata.text("identity").orEmpty()
    val tag = identity
    val section = metadataJsonPacketSection(packet, sectionNameHint ?: sourceClockId)
    val source = metadata.text("source").orEmpty()
    val epoch = metadata.text("system_time")?.nanosToMillis()
    val relative = metadata.text("timestamp")?.nanosToMillis()
    val time = metadata.text("system_time_formatted") ?: epoch?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(METADATA_JSON_TIME)
    } ?: metadata.text("timestamp_formatted").orEmpty()
    val title = metadataJsonTitle(packet.value, metadata)
    val status = metadata.text("status")
    val hciStatus = (packet.value["Summary"] as? JsonObject)?.text("Status")?.takeIf { identity.endsWith("HCI.Event") }
    val failed = status?.lowercase(Locale.ROOT) in setOf("error", "failed", "failure") ||
        (hciStatus != null && hciStatus.lowercase(Locale.ROOT) !in setOf("success", "0", "0x00"))
    val fields = linkedMapOf("source" to source, "identity" to identity, "section" to section)
    flattenMetadataFields(packet.value, "", fields)
    metadata.text("timestamp_formatted")?.let { fields["relative_time"] = it }
    metadataJsonRawBytes(packet.value)?.let { fields["raw_bytes"] = it }
    val columnValues = mapOf(
        "timestamp" to time, "source" to source, "component" to tag, "message" to title, "section" to section,
        "severity" to if (failed) status ?: hciStatus.orEmpty() else "",
    )
    return LogEntry(
        id = id, ts = time, level = if (failed) LogLevel.E else LogLevel.V, tag = compactor.shared(tag), msg = title,
        recordMetadata = LogRecordMetadata(
            profileFields = compactor.compact(fields),
            displayColumns = metadataJsonColumns(profile, columnValues, fields, compactor),
            originalSeverity = if (failed) status ?: hciStatus else null,
            severityKnown = failed,
            parsedTime = ParsedTime(
                metadata.text("system_time") ?: metadata.text("timestamp").orEmpty(), epoch, time,
                if (epoch != null) "absolute-epoch" else sourceClockId, relativeMillis = if (epoch == null) relative else null,
            ),
            rawRecord = packet.value.toString(),
            sourceStartLine = sourceLineOffset + packet.startLine,
            sourceEndLine = sourceLineOffset + packet.endLine,
            usesProfilePresentation = true,
        ),
    )
}

private fun metadataJsonRawBytes(packet: JsonObject): String? = when (val raw = packet["Raw Bytes"]) {
    is JsonPrimitive -> raw.contentOrNull
    is JsonArray -> raw.takeIf { it.all { chunk -> chunk is JsonPrimitive && chunk.isString } }
        ?.joinToString("") { (it as JsonPrimitive).content }
    else -> null
}

private fun metadataJsonPacketSection(packet: MetadataJsonPacket, source: String?): String = packet.section.ifEmpty {
    source?.let { java.io.File(it).nameWithoutExtension }?.takeIf { it.isNotBlank() } ?: "Records"
}

private fun metadataJsonColumns(
    profile: LogFormatProfile, values: Map<String, String>, fields: Map<String, String>, compactor: ProfileRowCompactor,
): List<LogDisplayField> = logFormatDisplayColumnDefinitions(profile).map { definition ->
    val field = profile.attributes.firstOrNull { it.id == definition.id }?.source ?: definition.id
    compactor.column(definition.id, definition.label, values[definition.id] ?: fields[field].orEmpty())
}

private fun metadataJsonTitle(packet: JsonObject, metadata: JsonObject): String {
    metadata.text("indagium_display_title")?.let { return it }
    val summary = packet["Summary"] as? JsonObject
    val primary = summary?.let {
        listOf("Note", "opCode", "Subevent", "Code", "Channel ID").firstNotNullOfOrNull(it::text)
    }
    if (primary == null) {
        val upstream = packet["Upstream Packets"] as? JsonObject
        val channel = upstream?.findDecodedField("Channel ID")
        if (channel != null) return listOfNotNull(channel, upstream.findDecodedField("Operation")).joinToString(" — ")
        return summary?.entries?.filter { it.value is JsonPrimitive }?.joinToString(", ") {
            "${it.key}: ${(it.value as JsonPrimitive).content}"
        }?.takeIf(String::isNotBlank) ?: metadata.text("title") ?: metadata.text("identity").orEmpty()
    }
    val command = summary.text("Command")
    val status = summary.text("Status")?.takeUnless { it.lowercase(Locale.ROOT) in setOf("success", "0", "0x00") }
    return listOfNotNull(primary, command, status).joinToString(" — ")
}

private fun JsonObject.findDecodedField(key: String): String? =
    text(key) ?: values.filterIsInstance<JsonObject>().firstNotNullOfOrNull { it.findDecodedField(key) }

private fun flattenMetadataFields(value: JsonObject, prefix: String, fields: MutableMap<String, String>) {
    value.forEach { (key, child) ->
        if (fields.size >= METADATA_JSON_MAX_FIELDS) return
        val path = if (prefix.isEmpty()) key else "$prefix.$key"
        if (child is JsonObject) flattenMetadataFields(child, path, fields)
        else fields[path] = (if (child is JsonPrimitive) child.contentOrNull else child.toString()).orEmpty()
            .take(METADATA_JSON_MAX_VALUE_CHARS)
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun String.nanosToMillis(): Long? = runCatching {
    toBigDecimal().movePointLeft(METADATA_JSON_NANOS_TO_MILLIS).setScale(0, RoundingMode.HALF_UP).longValueExact()
}.getOrNull()

/** Preview callers already own a sequence of physical lines; avoid joining a whole export. */
internal fun sequenceReader(lines: Sequence<String>): Reader = object : Reader() {
    private val iterator = lines.iterator()
    private var current = ""
    private var offset = 0

    override fun read(buffer: CharArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (offset == current.length) {
            if (!iterator.hasNext()) return -1
            current = iterator.next() + if (iterator.hasNext()) "\n" else ""
            offset = 0
        }
        val count = minOf(len, current.length - offset)
        current.toCharArray(buffer, off, offset, offset + count)
        offset += count
        return count
    }

    override fun close() = Unit
}

private const val MAX_JSON_DIAGNOSTICS = 16

private fun MutableList<String>.addJsonDiagnostic(message: String) {
    if (size < MAX_JSON_DIAGNOSTICS && message !in this) add(message)
}

private const val TAG_VISIBILITY_REVISION = 2

/** Old built-in snapshots predate the format's hidden-TAG default; custom profiles keep their own choices. */
internal fun metadataJsonPresentationProfile(profile: LogFormatProfile): LogFormatProfile =
    if (profile.id == metadataJsonProfile().id && profile.engine == LogFormatEngine.METADATA_JSON && profile.revision < TAG_VISIBILITY_REVISION) {
        profile.copy(revision = TAG_VISIBILITY_REVISION, visibleColumns = profile.visibleColumns - LogFieldRole.COMPONENT)
    } else {
        profile
    }

/** Changes presentation without removing tag data used by filters and record details. */
internal fun withMetadataJsonColumns(entry: LogEntry, profile: LogFormatProfile): LogEntry {
    val metadata = entry.recordMetadata ?: return entry
    val values = metadata.displayColumns.associate { it.id to it.value }
    val columns = logFormatDisplayColumnDefinitions(profile).map { definition ->
        definition.copy(value = if (definition.id == "component") entry.tag else values[definition.id].orEmpty())
    }
    return entry.copy(recordMetadata = metadata.copy(displayColumns = columns))
}
