package com.indagium.utils

import com.indagium.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MAX_PROFILE_RECORD_CHARS = 256 * 1024
private const val MAX_PROFILE_FIELDS = 128
private const val MAX_CSV_HEADER_SCAN_LINES = 1000
private const val MAX_PROFILE_FIELD_VALUE_CHARS = 4096
private const val MAX_PROFILE_FIELD_KEY_CHARS = 128
private const val MAX_SHARED_FIELD_SCHEMAS = 4096
private const val MAX_SHARED_VALUE_CHARS = 32
private const val MAX_SHARED_VALUES = 65_536
private const val DEFAULT_PROFILE_PREVIEW_BYTES = 512 * 1024
private const val PROFILE_CANCELLATION_INTERVAL = 128L
private const val MAX_EPOCH_LITERAL_CHARS = 64
private const val MAX_EPOCH_PRECISION = 32
private const val MAX_EPOCH_SCALE = 32
private const val SIGNATURE_MATCH_SHARE = 0.7
private const val MIN_PREVIEW_BYTES = 1024
private const val MAX_PREVIEW_BYTES = 4 * 1024 * 1024
private const val MAX_SYSLOG_PRIORITY = 191
private const val SYSLOG_SEVERITIES_PER_FACILITY = 8
private const val MILLIS_PER_SECOND_DOUBLE = 1000.0
private const val MILLIS_PER_MICRO_DOUBLE = 1000.0
private const val NANOS_PER_MILLI_DOUBLE = 1_000_000.0
private const val MILLIS_PER_LOCAL_DAY = 86_400_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L
private val MILLIS_PER_SECOND_DECIMAL = BigDecimal("1000")
private val MICROSECONDS_TO_MILLIS = BigDecimal("0.001")
private val NANOSECONDS_TO_MILLIS = BigDecimal("0.000001")

/** Chooses only high-confidence content signatures; arbitrary JSON and CSV remain ordinary text. */
internal fun detectBuiltInLogProfile(sample: ByteArray, fileName: String?): LogFormatProfile? {
    val text = runCatching { openLogTextReader(ByteArrayInputStream(sample)).use { it.readText() } }.getOrDefault("")
    val lines = text.lineSequence().filter(String::isNotBlank).take(24).toList()
    if (lines.isEmpty()) return null
    val jsonObjects = lines.mapNotNull(::jsonObjectFields)
    if (jsonObjects.size >= maxOf(2, (lines.size * SIGNATURE_MATCH_SHARE).toInt()) &&
        jsonObjects.count(::hasRecognizedJsonLogFields) >= maxOf(2, (jsonObjects.size * SIGNATURE_MATCH_SHARE).toInt())
    ) return BUILTIN_LOG_FORMAT_PROFILES.first { it.engine == LogFormatEngine.JSON_LINES }
    val signatureThreshold = maxOf(2, (lines.size * SIGNATURE_MATCH_SHARE).toInt())
    if (lines.count { RFC5424_PREFIX_REGEX.find(it) != null && parseRfc5424StructuredData(it) != null } >= signatureThreshold) {
        return BUILTIN_LOG_FORMAT_PROFILES.first { it.engine == LogFormatEngine.SYSLOG_RFC5424 }
    }
    if (lines.count { RFC3164_REGEX.matchEntire(it) != null } >= maxOf(2, (lines.size * SIGNATURE_MATCH_SHARE).toInt())) {
        return BUILTIN_LOG_FORMAT_PROFILES.first { it.engine == LogFormatEngine.SYSLOG_RFC3164 }
    }
    if (fileName?.substringAfterLast('.', "")?.lowercase() in setOf("jsonl", "ndjson") &&
        jsonObjects.singleOrNull()?.let(::hasRecognizedJsonLogFields) == true
    ) return BUILTIN_LOG_FORMAT_PROFILES.first { it.engine == LogFormatEngine.JSON_LINES }
    return null
}

internal fun detectStructuredLogcatProfile(sample: ByteArray): LogFormatProfile? {
    val text = runCatching { openLogTextReader(ByteArrayInputStream(sample)).use { it.readText() } }.getOrDefault("")
    val lines = text.lineSequence().take(32).toList()
    if (lines.isEmpty()) return null
    val parsed = parseLogcatLines(lines.asSequence())
    return BUILTIN_LOG_FORMAT_PROFILES.firstOrNull { it.engine == LogFormatEngine.LOGCAT }
        ?.takeIf { parsed.any { entry -> entry.tag != "RAW" } }
}

internal fun detectAndroidDiagnosticProfile(sample: ByteArray): LogFormatProfile? {
    val text = runCatching { openLogTextReader(ByteArrayInputStream(sample)).use { it.readText() } }.getOrDefault("")
    val hasAnrHeader = text.lineSequence().take(64).any { ANDROID_ANR_HEADER_REGEX.matches(it.trim()) }
    val hasBugreportLogSection = text.contains("------ SYSTEM LOG (logcat", ignoreCase = true) ||
        text.contains("------ EVENT LOG (logcat", ignoreCase = true)
    return BUILTIN_LOG_FORMAT_PROFILES.firstOrNull { it.engine == LogFormatEngine.LOGCAT }
        ?.takeIf { hasAnrHeader || hasBugreportLogSection }
}

internal fun parseProfileLog(input: InputStream, profile: LogFormatProfile, startId: Int = 1, sourceClockId: String? = null): ParsedLog {
    validateLogFormatProfile(profile)
    return openLogTextReader(input).use { reader -> parseProfileSequence(reader.lineSequence(), profile, startId, sourceClockId) }
}

internal fun parseProfileLines(
    lines: List<String>,
    profile: LogFormatProfile,
    startId: Int = 1,
    sourceClockId: String? = null,
    sourceLineOffset: Long = 0,
): ParsedLog = parseProfileSequence(lines.asSequence(), profile, startId, sourceClockId, sourceLineOffset)

/** Parses a bounded, complete-record sample for editor preview without materializing the source. */
fun parseLogFormatPreview(
    input: InputStream,
    profile: LogFormatProfile,
    fileName: String? = null,
    sourceClockId: String? = null,
    maxBytes: Int = DEFAULT_PROFILE_PREVIEW_BYTES,
): ParsedLog {
    require(maxBytes in MIN_PREVIEW_BYTES..MAX_PREVIEW_BYTES) { "Preview sample must be between 1 KB and 4 MB." }
    val bytes = input.use { it.readNBytes(maxBytes + 1) }
    val truncated = bytes.size > maxBytes
    val sample = bytes.copyOf(minOf(bytes.size, maxBytes))
    if (profile.engine in setOf(LogFormatEngine.DLT, LogFormatEngine.LOGCAT)) {
        val parsed = parseLogContent(ByteArrayInputStream(sample), fileName = fileName, profile = profile, sourceClockId = sourceClockId)
        return if (truncated) parsed.copy(diagnostics = parsed.diagnostics + "Preview is limited to the first ${maxBytes / 1024} KB.") else parsed
    }
    val decoded = openLogTextReader(ByteArrayInputStream(sample)).use { it.readText() }
    val completeText = if (truncated) {
        val lastLine = decoded.lastIndexOf('\n')
        decoded.takeIf { lastLine >= 0 }?.substring(0, lastLine + 1).orEmpty()
    } else {
        decoded
    }
    val parsed = parseProfileSequence(completeText.lineSequence(), profile, 1, sourceClockId)
    return if (truncated) parsed.copy(diagnostics = parsed.diagnostics + "Preview is limited to the first ${maxBytes / 1024} KB.") else parsed
}

internal data class ProfileRecord(
    val raw: String,
    val fields: Map<String, String> = emptyMap(),
    val directSeverity: String? = null,
    val directTimestamp: String? = null,
    val parseFields: Boolean = true,
    val sourceStartLine: Long,
    val sourceEndLine: Long,
    val dayOfYear: Short = NO_DATE,
)

internal data class AdditionalProfileRecord(
    val raw: String,
    val fields: Map<String, String> = emptyMap(),
    val severity: String? = null,
    val timestamp: String? = null,
    val sourceStartLine: Long,
    val sourceEndLine: Long,
    val parseFields: Boolean = true,
)

internal fun parseAdditionalRecords(
    records: Sequence<AdditionalProfileRecord>,
    profile: LogFormatProfile,
    startId: Int = 1,
    sourceClockId: String? = null,
    diagnostics: List<String> = emptyList(),
): ParsedLog {
    validateLogFormatProfile(profile)
    val timeParser = ProfileTimeParser(profile, sourceClockId)
    val compactor = ProfileRowCompactor()
    val entries = records.mapIndexed { index, record ->
        if (index.toLong() % PROFILE_CANCELLATION_INTERVAL == 0L) checkParserCancellation()
        recordToEntry(startId + index, record.toProfileRecord(), profile, timeParser, compactor)
    }.toList()
    return ParsedLog(LogFormat.LOGCAT, entries, profile, diagnostics, sourceClockId)
}

/** The one conversion from an engine-specific record to the shared record shape (whole-file and incremental paths). */
internal fun AdditionalProfileRecord.toProfileRecord(): ProfileRecord = ProfileRecord(
    raw = raw,
    fields = fields,
    directSeverity = severity,
    directTimestamp = timestamp,
    parseFields = parseFields,
    sourceStartLine = sourceStartLine,
    sourceEndLine = sourceEndLine,
)

private fun parseProfileSequence(
    lines: Sequence<String>,
    profile: LogFormatProfile,
    startId: Int,
    sourceClockId: String?,
    sourceLineOffset: Long = 0,
): ParsedLog {
    validateLogFormatProfile(profile)
    require(sourceLineOffset >= 0) { "Source line offset cannot be negative." }
    val diagnostics = mutableListOf<String>()
    val lineCounter = ProfileLineCounter(sourceLineOffset)
    val countedLines = lineCounter.wrap(lines)
    if (profile.engine == LogFormatEngine.LOGCAT) {
        val entries = parseLogcatLines(countedLines, startId)
        return ParsedLog(LogFormat.LOGCAT, entries, profile, sourceClockId = sourceClockId, sourceLineCount = lineCounter.count)
    }
    if (profile.engine == LogFormatEngine.DLT) error("DLT must be dispatched before text profile parsing.")
    if (profile.engine == LogFormatEngine.LOGFMT) {
        return parseLogfmtProfile(countedLines, profile, startId, sourceClockId, sourceLineOffset)
            .copy(sourceLineCount = lineCounter.count)
    }
    if (profile.engine == LogFormatEngine.CRI) {
        return parseCriProfile(countedLines, profile, startId, sourceClockId, sourceLineOffset)
            .copy(sourceLineCount = lineCounter.count)
    }
    val records = when (profile.engine) {
        LogFormatEngine.PLAIN_TEXT -> physicalLines(countedLines, sourceLineOffset)
        LogFormatEngine.REGEX -> machineRecords(countedLines) { RegexRecordMachine(profile, diagnostics, sourceLineOffset) }
        LogFormatEngine.JSON_LINES -> jsonRecords(countedLines, profile, diagnostics, sourceLineOffset)
        LogFormatEngine.CSV -> machineRecords(countedLines) { CsvRecordMachine(profile, diagnostics, null, sourceLineOffset) }
        LogFormatEngine.SYSLOG_RFC5424 -> syslogRecords(countedLines, diagnostics, true, sourceLineOffset)
        LogFormatEngine.SYSLOG_RFC3164 -> syslogRecords(countedLines, diagnostics, false, sourceLineOffset)
        LogFormatEngine.LOGCAT, LogFormatEngine.DLT, LogFormatEngine.LOGFMT, LogFormatEngine.CRI -> error("Handled above.")
    }
    val timeParser = ProfileTimeParser(profile, sourceClockId)
    val compactor = ProfileRowCompactor()
    val entries = records.mapIndexed { index, record ->
        recordToEntry(startId + index, record, profile, timeParser, compactor)
    }.toList()
    if (diagnostics.size > 30) diagnostics.subList(30, diagnostics.size).clear()
    diagnostics += "Parsed ${entries.size} records with ${profile.name}; unmatched records remain available as raw text."
    return ParsedLog(LogFormat.LOGCAT, entries, profile, diagnostics, sourceClockId, lineCounter.count)
}

private class ProfileLineCounter(startAt: Long) {
    var count: Long = startAt
        private set

    fun wrap(lines: Sequence<String>): Sequence<String> = sequence {
        for (line in lines) {
            count++
            if (count % PROFILE_CANCELLATION_INTERVAL == 0L) checkParserCancellation()
            yield(line)
        }
    }
}

private val ANDROID_ANR_HEADER_REGEX = Regex("^-{3,}\\s*pid\\s+\\d+\\s+at\\s+.+\\s*-{3,}$", RegexOption.IGNORE_CASE)

private fun physicalLines(lines: Sequence<String>, sourceLineOffset: Long): Sequence<ProfileRecord> = sequence {
    var number = sourceLineOffset
    for (line in lines) {
        number++
        if (number % PROFILE_CANCELLATION_INTERVAL == 0L) checkParserCancellation()
        if (line.isNotBlank()) yield(ProfileRecord(line, sourceStartLine = number, sourceEndLine = number))
    }
}

/**
 * A record builder that is fed one physical line at a time and keeps what it needs between lines.
 * The whole-file parse and the tail's incremental parser both drive the same machines, so a split
 * file can never be read differently from a whole one.
 */
internal interface ProfileRecordMachine {
    /** Absolute number of the last physical line consumed; settable so a tail can resume at a known position. */
    var lineNumber: Long

    /** Consumes [line] and adds every record this line completed to [out]. */
    fun accept(line: String, out: MutableList<ProfileRecord>)

    /** Adds the record still held at the end of the input, if any. */
    fun finish(out: MutableList<ProfileRecord>)
}

private fun machineRecords(
    lines: Sequence<String>,
    newMachine: () -> ProfileRecordMachine,
): Sequence<ProfileRecord> = sequence {
    val machine = newMachine()
    val ready = ArrayList<ProfileRecord>(2)
    for (line in lines) {
        machine.accept(line, ready)
        for (record in ready) yield(record)
        ready.clear()
    }
    machine.finish(ready)
    for (record in ready) yield(record)
}

/** Regex records; with a start pattern a record ends only when the next start line arrives. */
internal class RegexRecordMachine(
    private val profile: LogFormatProfile,
    private val diagnostics: MutableList<String>,
    sourceLineOffset: Long,
) : ProfileRecordMachine {
    private val matcher = BoundedProfileRegexMatcher(
        profile.regexPattern.takeIf(String::isNotBlank) ?: error("Choose a record regex before using the Regex engine."),
    )
    private val boundary = profile.multilineStartPattern.takeIf(String::isNotBlank)?.let(::BoundedProfileRegexMatcher)
    private var current = StringBuilder()
    private var start = 0L
    private var end = 0L
    override var lineNumber: Long = sourceLineOffset

    override fun accept(line: String, out: MutableList<ProfileRecord>) {
        lineNumber++
        if (lineNumber % PROFILE_CANCELLATION_INTERVAL == 0L) checkParserCancellation()
        val begins = boundary?.groups(line) != null
        if (current.isNotEmpty() && (boundary == null || begins)) emitCurrent(parseAllowed = true, out)
        if (current.isEmpty()) start = lineNumber else current.append('\n')
        current.append(line)
        end = lineNumber
        if (current.length > MAX_PROFILE_RECORD_CHARS) emitCurrent(parseAllowed = false, out)
    }

    override fun finish(out: MutableList<ProfileRecord>) {
        emitCurrent(parseAllowed = true, out)
        if (matcher.timedOut || boundary?.timedOut == true) {
            diagnostics.addLimited("Profile regex reached its shared time budget; remaining records were kept as raw text.")
        }
    }

    private fun emitCurrent(parseAllowed: Boolean, out: MutableList<ProfileRecord>) {
        if (current.isEmpty()) return
        val raw = current.toString()
        val groups = if (parseAllowed) matcher.groups(raw) else null
        val fields = groups?.let { groupValues ->
            val mapped = linkedMapOf<String, String>()
            profile.fieldMappings.forEach { (role, source) ->
                source.toIntOrNull()?.let { index -> groupValues.getOrNull(index)?.let { mapped[role.name] = it } }
            }
            profile.attributes.forEach { attribute ->
                attribute.source.toIntOrNull()?.let { index -> groupValues.getOrNull(index)?.let { mapped[attribute.id] = it } }
            }
            mapped
        }.orEmpty()
        if (groups == null) diagnostics.addLimited("Regex did not match a record; original text was kept.")
        out += ProfileRecord(raw, fields, parseFields = groups != null, sourceStartLine = start, sourceEndLine = end)
        current = StringBuilder()
    }
}

@Suppress("LoopWithTooManyJumpStatements") // Each skip path yields its own raw record before continuing.
private fun jsonRecords(
    lines: Sequence<String>,
    profile: LogFormatProfile,
    diagnostics: MutableList<String>,
    sourceLineOffset: Long,
): Sequence<ProfileRecord> = sequence {
    var lineNumber = sourceLineOffset
    for (raw in lines) {
        lineNumber++
        if (lineNumber % PROFILE_CANCELLATION_INTERVAL == 0L) checkParserCancellation()
        if (raw.isBlank()) continue
        if (raw.length > MAX_PROFILE_RECORD_CHARS) {
            diagnostics.addLimited("An oversized JSON record was kept as raw text.")
            yield(ProfileRecord(raw, parseFields = false, sourceStartLine = lineNumber, sourceEndLine = lineNumber))
            continue
        }
        val flattened = jsonObjectFields(raw)
        if (flattened == null) {
            diagnostics.addLimited("An invalid JSON record was kept as raw text.")
            yield(ProfileRecord(raw, parseFields = false, sourceStartLine = lineNumber, sourceEndLine = lineNumber))
            continue
        }
        val mapped = linkedMapOf<String, String>()
        val aliases = mapOf(
            LogFieldRole.TIMESTAMP to listOf("timestamp", "time", "ts", "@timestamp", "datetime"),
            LogFieldRole.SEVERITY to listOf("level", "severity", "lvl", "log.level"),
            LogFieldRole.COMPONENT to listOf("logger", "tag", "component", "service", "name"),
            LogFieldRole.MESSAGE to listOf("message", "msg", "event", "log", "body"),
            LogFieldRole.PROCESS_ID to listOf("pid", "process_id", "processId"),
            LogFieldRole.THREAD_ID to listOf("tid", "thread", "thread_id", "threadId"),
        )
        LogFieldRole.entries.forEach { role ->
            val configured = profile.fieldMappings[role]
            val value = if (configured != null) {
                flattened[configured]
            } else {
                aliases[role]?.firstNotNullOfOrNull { key ->
                    flattened[key] ?: flattened.entries.firstOrNull { it.key.substringAfterLast('.') == key }?.value
                }
            }
            if (value != null) mapped[role.name] = value
        }
        profile.attributes.forEach { attribute -> flattened[attribute.source]?.let { mapped[attribute.id] = it } }
        flattened.forEach { (key, value) -> mapped.putIfAbsent(key, value) }
        yield(ProfileRecord(raw, mapped, sourceStartLine = lineNumber, sourceEndLine = lineNumber))
    }
}

/**
 * CSV records: a quoted cell may span lines, so a record is held until its quotes close. [initialHeaders]
 * lets a tail that starts mid-file use the header row it did not see.
 */
internal class CsvRecordMachine(
    private val profile: LogFormatProfile,
    private val diagnostics: MutableList<String>,
    initialHeaders: List<String>?,
    sourceLineOffset: Long,
) : ProfileRecordMachine {
    /** The header row once it has been read; never emitted as a record. */
    var headers: List<String>? = initialHeaders
        private set
    private var current = StringBuilder()
    private var quoteState = CsvQuoteState.FIELD_START
    private var startLine = 0L
    override var lineNumber: Long = sourceLineOffset

    @Suppress("ReturnCount") // Each early return ends one record path (oversized, still open, invalid, header).
    override fun accept(line: String, out: MutableList<ProfileRecord>) {
        lineNumber++
        if (lineNumber % PROFILE_CANCELLATION_INTERVAL == 0L) checkParserCancellation()
        if (current.isEmpty()) startLine = lineNumber else current.append('\n')
        current.append(line)
        val updatedState = updateCsvQuoteState(line, quoteState, profile.delimiter)
        quoteState = updatedState.state
        if (current.length > MAX_PROFILE_RECORD_CHARS) {
            diagnostics.addLimited("An oversized CSV record was kept as raw text.")
            out += ProfileRecord(current.toString(), parseFields = false, sourceStartLine = startLine, sourceEndLine = lineNumber)
            current = StringBuilder()
            quoteState = CsvQuoteState.FIELD_START
            return
        }
        if (updatedState.valid && quoteState == CsvQuoteState.QUOTED) return
        val raw = current.toString()
        current = StringBuilder()
        val cells = if (updatedState.valid) runCatching { parseCsvRow(raw, profile.delimiter) }.getOrNull() else null
        quoteState = CsvQuoteState.FIELD_START
        if (cells == null) {
            diagnostics.addLimited("An invalid CSV record was kept as raw text.")
            out += ProfileRecord(raw, parseFields = false, sourceStartLine = startLine, sourceEndLine = lineNumber)
            return
        }
        if (headers == null && profile.hasHeader) {
            headers = cells.map(String::trim)
            return
        }
        out += ProfileRecord(raw, mapCells(cells), sourceStartLine = startLine, sourceEndLine = lineNumber)
    }

    override fun finish(out: MutableList<ProfileRecord>) {
        if (current.isEmpty()) return
        diagnostics.addLimited("An unterminated CSV record was kept as raw text.")
        out += ProfileRecord(current.toString(), parseFields = false, sourceStartLine = startLine, sourceEndLine = lineNumber)
        current = StringBuilder()
        quoteState = CsvQuoteState.FIELD_START
    }

    private fun mapCells(cells: List<String>): Map<String, String> {
        val fields = if (profile.hasHeader) {
            headers.orEmpty().zip(cells).toMap()
        } else {
            cells.mapIndexed { index, cell -> index.toString() to cell }.toMap()
        }
        val mapped = linkedMapOf<String, String>()
        profile.fieldMappings.forEach { (role, source) ->
            val value = fields[source] ?: source.toIntOrNull()?.let { fields[it.toString()] }
            if (value != null) mapped[role.name] = value
        }
        profile.attributes.forEach { attribute -> fields[attribute.source]?.let { mapped[attribute.id] = it } }
        fields.forEach { (key, value) -> mapped.putIfAbsent(key, value) }
        return mapped
    }
}

/** Reads the header row from the start of a CSV source; null when the profile has none or none is found early. */
internal fun readCsvHeaderRow(lines: Sequence<String>, profile: LogFormatProfile): List<String>? {
    if (profile.engine != LogFormatEngine.CSV || !profile.hasHeader) return null
    val machine = CsvRecordMachine(profile, mutableListOf(), null, 0)
    val sink = ArrayList<ProfileRecord>(1)
    for (line in lines.take(MAX_CSV_HEADER_SCAN_LINES)) {
        machine.accept(line, sink)
        sink.clear()
        machine.headers?.let { return it }
    }
    return null
}

/** The machine a tail needs for [profile], or null for engines whose records are one physical line each. */
internal fun newTailRecordMachine(
    profile: LogFormatProfile,
    diagnostics: MutableList<String>,
    csvHeaders: List<String>?,
): ProfileRecordMachine? = when {
    profile.engine == LogFormatEngine.CSV -> CsvRecordMachine(profile, diagnostics, csvHeaders, 0)
    profile.engine == LogFormatEngine.REGEX && profile.multilineStartPattern.isNotBlank() ->
        RegexRecordMachine(profile, diagnostics, 0)
    else -> null
}

private fun syslogRecords(
    lines: Sequence<String>,
    diagnostics: MutableList<String>,
    rfc5424: Boolean,
    sourceLineOffset: Long,
): Sequence<ProfileRecord> = sequence {
    var lineNumber = sourceLineOffset
    for (raw in lines) {
        lineNumber++
        if (lineNumber % PROFILE_CANCELLATION_INTERVAL == 0L) checkParserCancellation()
        if (raw.isBlank()) continue
        val parsed = if (rfc5424) parseRfc5424StructuredData(raw) else parseRfc3164(raw)
        if (parsed == null) {
            diagnostics.addLimited("A syslog record with an invalid header was kept as raw text.")
            yield(ProfileRecord(raw, parseFields = false, sourceStartLine = lineNumber, sourceEndLine = lineNumber))
        } else {
            val (fields, severity, timestamp) = parsed
            yield(ProfileRecord(
                raw,
                fields,
                severity,
                timestamp,
                sourceStartLine = lineNumber,
                sourceEndLine = lineNumber,
                dayOfYear = fields["day_of_year"]?.toShortOrNull() ?: NO_DATE,
            ))
        }
    }
}

private fun parseRfc5424StructuredData(raw: String): Triple<Map<String, String>, String?, String?>? {
    val header = RFC5424_PREFIX_REGEX.find(raw) ?: return null
    val values = header.groupValues
    val rest = raw.substring(header.range.last + 1)
    val sdFields = linkedMapOf<String, String>()
    var cursor = 0
    if (rest.startsWith('-')) {
        cursor = 1
    } else {
        while (rest.getOrNull(cursor) == '[') {
            val blockStart = cursor + 1
            cursor++
            var escaped = false
            var closed = false
            var blockEnd = -1
            while (cursor < rest.length) {
                val char = rest[cursor++]
                if (escaped) escaped = false else when (char) {
                    '\\' -> escaped = true
                    ']' -> { closed = true; blockEnd = cursor - 1; break }
                }
            }
            if (!closed) return null
            if (!parseStructuredDataBlock(rest.substring(blockStart, blockEnd), sdFields)) return null
        }
        if (cursor == 0) return null
    }
    while (rest.getOrNull(cursor) == ' ') cursor++
    val priority = values[1].toIntOrNull()?.takeIf { it in 0..MAX_SYSLOG_PRIORITY } ?: return null
    val severity = SYSLOG_SEVERITY[priority % SYSLOG_SEVERITIES_PER_FACILITY]
    val timestamp = values[3].takeUnless { it == "-" }
    val fields = linkedMapOf(
        LogFieldRole.TIMESTAMP.name to timestamp.orEmpty(),
        LogFieldRole.COMPONENT.name to values[5].takeUnless { it == "-" }.orEmpty(),
        LogFieldRole.MESSAGE.name to rest.substring(cursor),
        LogFieldRole.HOST.name to values[4].takeUnless { it == "-" }.orEmpty(),
        "process_id" to values[6].takeUnless { it == "-" }.orEmpty(),
        "message_id" to values[7].takeUnless { it == "-" }.orEmpty(),
    )
    fields.putAll(sdFields)
    fields["structured_data"] = rest.take(cursor)
    if (timestamp == null) fields.remove(LogFieldRole.TIMESTAMP.name)
    return Triple(fields, severity, timestamp)
}

private fun parseRfc3164(raw: String): Triple<Map<String, String>, String?, String?>? {
    val match = RFC3164_REGEX.matchEntire(raw) ?: return null
    val values = match.groupValues
    val priority = values[1].toIntOrNull()
    val severity = priority?.let { SYSLOG_SEVERITY[it % 8] }
    val time = values[4]
    val timestamp = "${values[2]} ${values[3].trim()} $time"
    val component = values[6]
    val fields = linkedMapOf(
        LogFieldRole.TIMESTAMP.name to timestamp,
        LogFieldRole.COMPONENT.name to component,
        LogFieldRole.MESSAGE.name to values[8],
        LogFieldRole.HOST.name to values[5],
        "process_id" to values[7],
        "clock_time" to time,
    )
    val month = java.time.Month.entries.firstOrNull { it.name.startsWith(values[2].uppercase(Locale.ROOT)) }
    val day = values[3].trim().toIntOrNull()
    if (month != null && day != null) {
        val slot = runCatching { logDaySlot(month.value, day) }.getOrNull()
        if (slot != null) fields["day_of_year"] = slot.toString()
    }
    return Triple(fields, severity, timestamp)
}

private fun parseStructuredDataBlock(block: String, target: MutableMap<String, String>): Boolean {
    var cursor = 0
    while (cursor < block.length && !block[cursor].isWhitespace()) cursor++
    val sdId = block.substring(0, cursor).takeIf(String::isNotBlank) ?: return false
    while (cursor < block.length) {
        while (block.getOrNull(cursor)?.isWhitespace() == true) cursor++
        if (cursor >= block.length) break
        val keyStart = cursor
        while (cursor < block.length && block[cursor] != '=' && !block[cursor].isWhitespace()) cursor++
        if (block.getOrNull(cursor) != '=') return false
        val key = block.substring(keyStart, cursor++)
        val parsed = parseStructuredDataValue(block, cursor) ?: return false
        cursor = parsed.second
        if (target.size < MAX_PROFILE_FIELDS) target["sd.$sdId.$key"] = parsed.first
    }
    return true
}

private fun parseStructuredDataValue(block: String, start: Int): Pair<String, Int>? {
    if (block.getOrNull(start) != '"') return null
    val value = StringBuilder()
    var cursor = start + 1
    while (cursor < block.length) {
        when (val char = block[cursor++]) {
            '"' -> return value.toString() to cursor
            '\\' -> {
                val escaped = block.getOrNull(cursor++) ?: return null
                if (escaped !in STRUCTURED_DATA_ESCAPABLE) return null
                value.append(escaped)
            }
            else -> value.append(char)
        }
    }
    return null
}

@Suppress("CyclomaticComplexMethod") // Field-by-field record mapping; each branch is a separate optional source.
internal fun recordToEntry(
    id: Int,
    record: ProfileRecord,
    profile: LogFormatProfile,
    timeParser: ProfileTimeParser,
    compactor: ProfileRowCompactor,
): LogEntry {
    val fields = record.fields

    fun role(role: LogFieldRole): String? {
        val source = profile.fieldMappings[role]
        return if (source == null) fields[role.name] else fields[role.name] ?: fields[source]
    }
    val timeText = record.directTimestamp ?: role(LogFieldRole.TIMESTAMP)
    val severityText = (record.directSeverity ?: role(LogFieldRole.SEVERITY))?.let(compactor::shared)
    val mappedLevel = profile.severityMapping.entries.firstOrNull { it.key.equals(severityText, true) }?.value
    val (level, known) = if (mappedLevel != null) LogLevel.from(mappedLevel) to true else normalizeProfileSeverity(severityText)
    val pid = role(LogFieldRole.PROCESS_ID)?.toIntOrNull() ?: fields["process_id"]?.toIntOrNull() ?: 0
    val tid = role(LogFieldRole.THREAD_ID)?.toIntOrNull() ?: 0
    val parsedTime = timeText?.let { timeParser.parse(it, fields["clock_time"]) }
    val profileFields = compactor.compact(fields)
    val displayColumns = profile.visibleColumns.map { visibleRole ->
        val value = when (visibleRole) {
            LogFieldRole.TIMESTAMP -> timeText.orEmpty()
            LogFieldRole.SEVERITY -> severityText.orEmpty()
            LogFieldRole.COMPONENT -> role(visibleRole).orEmpty()
            LogFieldRole.MESSAGE -> role(visibleRole) ?: record.raw
            LogFieldRole.PROCESS_ID, LogFieldRole.THREAD_ID -> role(visibleRole).orEmpty()
            else -> role(visibleRole).orEmpty()
        }
        compactor.column(visibleRole.name.lowercase(), profileDisplayLabel(visibleRole), value)
    }.toMutableList()
    profile.attributes.filter { it.visibleByDefault }.forEach { attribute ->
        fields[attribute.id]?.let { value -> displayColumns += LogDisplayField(attribute.id, attribute.label, value) }
    }
    // Rows live as long as the tab: drop the growth slack a mutable list carries.
    val projectedColumns: List<LogDisplayField> = if (displayColumns.isEmpty()) emptyList() else displayColumns.toTypedArray().asList()
    val (startLine, endLine) = compactor.lineSpan(record.sourceStartLine, record.sourceEndLine)
    val metadata = LogRecordMetadata(
        profileFields = profileFields,
        displayColumns = projectedColumns,
        originalSeverity = severityText,
        severityKnown = record.parseFields && known,
        parsedTime = parsedTime,
        rawRecord = record.raw,
        sourceStartLine = startLine,
        sourceEndLine = endLine,
        processIdentity = role(LogFieldRole.PROCESS_ID),
        threadIdentity = role(LogFieldRole.THREAD_ID),
        usesProfilePresentation = true,
    )
    return LogEntry(
        id = id,
        ts = parsedTime?.localTime ?: timeText.orEmpty(),
        level = level,
        tag = compactor.shared(role(LogFieldRole.COMPONENT).orEmpty()),
        msg = if (record.parseFields) role(LogFieldRole.MESSAGE) ?: record.raw else record.raw,
        pid = pid,
        tid = tid,
        recordMetadata = metadata,
        dayOfYear = record.dayOfYear,
    )
}

/**
 * Stores one row's fields as two arrays. The key array is shared by every row with the same keys
 * in the same order, which for a structured log is nearly all of them, so a row pays for its values
 * only. A hash map per row cost several times the size of the source line.
 */
internal class CompactFieldMap(
    private val fieldKeys: Array<String>,
    private val fieldValues: Array<String>,
) : AbstractMap<String, String>() {
    override val size: Int get() = fieldKeys.size

    override fun containsKey(key: String): Boolean = fieldKeys.indexOf(key) >= 0

    override fun get(key: String): String? {
        val index = fieldKeys.indexOf(key)
        return if (index >= 0) fieldValues[index] else null
    }

    override val entries: Set<Map.Entry<String, String>>
        get() = object : AbstractSet<Map.Entry<String, String>>() {
            override val size: Int get() = fieldKeys.size

            override fun iterator(): Iterator<Map.Entry<String, String>> = object : Iterator<Map.Entry<String, String>> {
                private var next = 0

                override fun hasNext(): Boolean = next < fieldKeys.size

                override fun next(): Map.Entry<String, String> {
                    if (next >= fieldKeys.size) throw NoSuchElementException()
                    return java.util.AbstractMap.SimpleImmutableEntry(fieldKeys[next], fieldValues[next++])
                }
            }
        }
}

/** One per parse: shares key arrays between rows and applies the per-row field limits. */
internal class ProfileRowCompactor {
    private val schemas = HashMap<List<String>, Array<String>>()
    private val shortValues = HashMap<String, String>()
    private val emptyColumns = HashMap<String, LogDisplayField>()

    /** Levels, logger names, status codes and ids of a few characters repeat on most rows. */
    fun shared(value: String): String {
        if (value.length > MAX_SHARED_VALUE_CHARS) return value
        shortValues[value]?.let { return it }
        if (shortValues.size < MAX_SHARED_VALUES) shortValues[value] = value
        return value
    }

    /** A column with no value on this row is the same object on every such row. */
    fun column(id: String, label: String, value: String): LogDisplayField =
        if (value.isEmpty()) emptyColumns.getOrPut(id) { LogDisplayField(id, label, "") } else LogDisplayField(id, label, shared(value))

    /** A single-line record carries one boxed line number, not two equal ones. */
    fun lineSpan(start: Long, end: Long): Pair<Long?, Long?> {
        val boxedStart: Long? = start
        return boxedStart to if (end == start) boxedStart else end
    }

    fun compact(fields: Map<String, String>): Map<String, String> {
        if (fields.isEmpty()) return emptyMap()
        val count = minOf(fields.size, MAX_PROFILE_FIELDS)
        val rowKeys = ArrayList<String>(count)
        val rowValues = arrayOfNulls<String>(count)
        var index = 0
        for ((key, value) in fields) {
            if (index == count) break
            val shortKey = key.take(MAX_PROFILE_FIELD_KEY_CHARS)
            // Truncation can make two keys equal; the later one wins, as it did in a hash map.
            val existing = if (shortKey.length == key.length) -1 else rowKeys.indexOf(shortKey)
            if (existing >= 0) {
                rowValues[existing] = shared(value.take(MAX_PROFILE_FIELD_VALUE_CHARS))
                continue
            }
            rowKeys += shortKey
            rowValues[index++] = shared(value.take(MAX_PROFILE_FIELD_VALUE_CHARS))
        }
        val sharedKeys = schemas[rowKeys] ?: rowKeys.toTypedArray().also { created ->
            // A log whose rows all differ in shape gains nothing from sharing; stop collecting.
            if (schemas.size < MAX_SHARED_FIELD_SCHEMAS) schemas[rowKeys] = created
        }

        @Suppress("UNCHECKED_CAST")
        val values = (if (index == count) rowValues else rowValues.copyOf(index)) as Array<String>
        return CompactFieldMap(sharedKeys, values)
    }
}

private fun profileDisplayLabel(role: LogFieldRole): String = when (role) {
    LogFieldRole.TIMESTAMP -> "timestamp"
    LogFieldRole.SEVERITY -> "level"
    LogFieldRole.COMPONENT -> "tag"
    LogFieldRole.MESSAGE -> "message"
    LogFieldRole.PROCESS_ID -> "pid"
    LogFieldRole.THREAD_ID -> "tid"
    else -> role.name.lowercase().replace('_', ' ')
}

private fun normalizeProfileSeverity(value: String?): Pair<LogLevel, Boolean> = when (value?.uppercase(Locale.ROOT)) {
    "V", "TRACE", "FINEST" -> LogLevel.V to true
    "D", "DEBUG", "FINE" -> LogLevel.D to true
    "I", "INFO", "NOTICE" -> LogLevel.I to true
    "W", "WARN", "WARNING" -> LogLevel.W to true
    "E", "ERROR", "ERR" -> LogLevel.E to true
    "A", "F", "FATAL", "CRITICAL", "CRIT", "EMERG", "ALERT" -> LogLevel.A to true
    else -> LogLevel.V to false
}

internal class ProfileTimeParser(private val profile: LogFormatProfile, private val sourceClockId: String?) {
    private val zone = profile.timezone.takeIf(String::isNotBlank)?.let(ZoneId::of)
    private val clockIdentity = profile.relativeClock.takeIf {
        profile.timestampKind == ProfileTimestampKind.RELATIVE && it.isNotBlank()
    } ?: "${profile.timestampKind.name.lowercase()}:${sourceClockId ?: profile.id}"
    private val candidates = buildTimeFormatterCandidates(profile)

    fun parse(value: String, timeOnlyOverride: String? = null): ParsedTime = when (profile.timestampKind) {
        ProfileTimestampKind.EPOCH -> parseEpochTime(value, profile, zone, clockIdentity)
        ProfileTimestampKind.RELATIVE -> parseRelativeTime(value, profile, clockIdentity)
        else -> parseCalendarTime(
            value,
            timeOnlyOverride ?: value,
            candidates,
            zone,
            sourceClockId ?: profile.id,
            clockIdentity,
        )
    }
}

private fun parseEpochTime(
    value: String,
    profile: LogFormatProfile,
    zone: ZoneId?,
    clockIdentity: String,
): ParsedTime {
    val millis = runCatching {
        require(value.length <= MAX_EPOCH_LITERAL_CHARS) { "Epoch timestamp is too long." }
        val raw = BigDecimal(value)
        require(raw.precision() <= MAX_EPOCH_PRECISION) { "Epoch timestamp has too many digits." }
        require(raw.scale() in -MAX_EPOCH_SCALE..MAX_EPOCH_SCALE) { "Epoch timestamp exponent is outside the supported range." }
        val wholeMillis = raw.multiply(profile.epochUnit.toMillisMultiplier()).setScale(0, RoundingMode.DOWN)
        require(wholeMillis >= LONG_MIN_DECIMAL && wholeMillis <= LONG_MAX_DECIMAL) { "Epoch timestamp is outside the supported range." }
        wholeMillis.longValueExact()
    }.getOrNull() ?: return ParsedTime(value, clockIdentity = clockIdentity)
    val localTime = runCatching {
        Instant.ofEpochMilli(millis).atZone(zone ?: ZoneId.systemDefault()).format(TIME_OUTPUT)
    }.getOrNull()
    return ParsedTime(value, millis, localTime, "absolute-epoch")
}

private fun ProfileEpochUnit.toMillisMultiplier(): BigDecimal = when (this) {
    ProfileEpochUnit.SECONDS -> MILLIS_PER_SECOND_DECIMAL
    ProfileEpochUnit.MILLIS -> BigDecimal.ONE
    ProfileEpochUnit.MICROS -> MICROSECONDS_TO_MILLIS
    ProfileEpochUnit.NANOS -> NANOSECONDS_TO_MILLIS
}

private fun parseRelativeTime(value: String, profile: LogFormatProfile, clockIdentity: String): ParsedTime {
    val numeric = value.toDoubleOrNull()?.takeIf(Double::isFinite)
        ?.let { relativeMillis(it, profile.epochUnit) }
    val suffixed = RELATIVE_TIME_REGEX.matchEntire(value)?.let { match ->
        val unit = relativeTimeUnit(match.groupValues[2])
        match.groupValues[1].toDoubleOrNull()?.takeIf(Double::isFinite)?.let { relativeMillis(it, unit) }
    }
    val millis = numeric ?: suffixed
    return ParsedTime(value, localTime = null, clockIdentity = clockIdentity, relativeMillis = millis)
}

private fun relativeMillis(value: Double, unit: ProfileEpochUnit): Long? {
    val millis = unitToMillis(value, unit)
    if (!millis.isFinite() || millis < Long.MIN_VALUE.toDouble() || millis > Long.MAX_VALUE.toDouble()) return null
    return millis.toLong()
}

private fun relativeTimeUnit(suffix: String): ProfileEpochUnit = when (suffix.lowercase()) {
    "s", "sec" -> ProfileEpochUnit.SECONDS
    "us", "µs" -> ProfileEpochUnit.MICROS
    "ns" -> ProfileEpochUnit.NANOS
    else -> ProfileEpochUnit.MILLIS
}

private fun parseCalendarTime(
    original: String,
    value: String,
    candidates: List<DateTimeFormatter>,
    zone: ZoneId?,
    sourceClockId: String?,
    clockIdentity: String,
): ParsedTime = candidates.firstNotNullOfOrNull { formatter ->
    parseOffsetTime(original, value, formatter, zone)
        ?: parseLocalDateTime(original, value, formatter, zone, sourceClockId)
        ?: parseLocalTime(original, value, formatter, sourceClockId)
} ?: ParsedTime(original, clockIdentity = clockIdentity)

private fun parseOffsetTime(
    original: String,
    value: String,
    formatter: DateTimeFormatter,
    zone: ZoneId?,
): ParsedTime? = runCatching {
    val offset = OffsetDateTime.parse(value, formatter)
    val local = offset.atZoneSameInstant(zone ?: offset.offset).format(TIME_OUTPUT)
    ParsedTime(original, offset.toInstant().toEpochMilli(), local, "absolute-offset")
}.getOrNull()

private fun parseLocalDateTime(
    original: String,
    value: String,
    formatter: DateTimeFormatter,
    zone: ZoneId?,
    sourceClockId: String?,
): ParsedTime? = runCatching {
    val localDateTime = LocalDateTime.parse(value, formatter)
    val epoch = zone?.let { localDateTime.atZone(it).toInstant().toEpochMilli() }
    val wallMillis = if (zone == null) {
        localDateTime.toLocalDate().toEpochDay() * MILLIS_PER_LOCAL_DAY +
            localDateTime.toLocalTime().toNanoOfDay() / NANOS_PER_MILLISECOND
    } else {
        null
    }
    ParsedTime(
        original,
        epoch,
        localDateTime.format(TIME_OUTPUT),
        zone?.let { "zone:${it.id}" } ?: "local:${sourceClockId ?: ""}",
        localWallMillis = wallMillis,
    )
}.getOrNull()

private fun parseLocalTime(
    original: String,
    value: String,
    formatter: DateTimeFormatter,
    sourceClockId: String?,
): ParsedTime? = runCatching {
    val local = LocalTime.parse(value, formatter).format(TIME_OUTPUT)
    ParsedTime(original, null, local, "local:${sourceClockId ?: ""}")
}.getOrNull()

private fun buildTimeFormatterCandidates(profile: LogFormatProfile): List<DateTimeFormatter> {
    val custom = profile.timestampPattern.takeIf(String::isNotBlank)
        ?.let { DateTimeFormatter.ofPattern(it, Locale.ROOT) }
    return when (profile.timestampKind) {
        ProfileTimestampKind.CUSTOM -> listOfNotNull(custom)
        ProfileTimestampKind.TIME_OF_DAY -> TIME_OF_DAY_FORMATTERS
        else -> listOfNotNull(custom) + STANDARD_TIMESTAMP_FORMATTERS
    }
}

private fun unitToMillis(value: Double, unit: ProfileEpochUnit): Double = when (unit) {
    ProfileEpochUnit.SECONDS -> value * MILLIS_PER_SECOND_DOUBLE
    ProfileEpochUnit.MILLIS -> value
    ProfileEpochUnit.MICROS -> value / MILLIS_PER_MICRO_DOUBLE
    ProfileEpochUnit.NANOS -> value / NANOS_PER_MILLI_DOUBLE
}

private fun jsonObjectFields(raw: String): Map<String, String>? = runCatching {
    val objectValue = Json.parseToJsonElement(raw).jsonObject
    val values = linkedMapOf<String, String>()
    flattenJson(objectValue, "", values, 0)
    values
}.getOrNull()

private fun hasRecognizedJsonLogFields(fields: Map<String, String>): Boolean {
    val keys = fields.keys.map { it.substringAfterLast('.').lowercase(Locale.ROOT) }.toSet()
    val hasMessage = keys.any { it in setOf("message", "msg", "event", "log", "body") }
    val hasSignal = keys.any { it in setOf("timestamp", "time", "ts", "@timestamp", "level", "severity", "lvl") }
    return hasMessage && hasSignal
}

private fun flattenJson(value: JsonObject, prefix: String, target: MutableMap<String, String>, depth: Int) {
    if (depth > 6 || target.size >= MAX_PROFILE_FIELDS) return
    value.forEach { (key, child) ->
        val fullKey = if (prefix.isEmpty()) key else "$prefix.$key"
        when (child) {
            is JsonObject -> flattenJson(child, fullKey, target, depth + 1)
            is JsonPrimitive -> child.contentOrNull?.let { if (target.size < MAX_PROFILE_FIELDS) target[fullKey] = it }
            else -> Unit
        }
    }
}

private enum class CsvQuoteState { FIELD_START, UNQUOTED, QUOTED, AFTER_QUOTE }

private data class CsvQuoteScan(val state: CsvQuoteState, val valid: Boolean)

private fun updateCsvQuoteState(line: String, initial: CsvQuoteState, delimiter: Char): CsvQuoteScan {
    var state = initial
    var index = 0
    while (index < line.length) {
        if (index % PROFILE_CANCELLATION_INTERVAL.toInt() == 0) checkParserCancellation()
        when (state) {
            CsvQuoteState.FIELD_START -> state = when (line[index]) {
                '"' -> CsvQuoteState.QUOTED
                delimiter -> CsvQuoteState.FIELD_START
                else -> CsvQuoteState.UNQUOTED
            }
            CsvQuoteState.UNQUOTED -> when (line[index]) {
                delimiter -> state = CsvQuoteState.FIELD_START
                '"' -> return CsvQuoteScan(CsvQuoteState.FIELD_START, false)
            }
            CsvQuoteState.QUOTED -> if (line[index] == '"') {
                if (line.getOrNull(index + 1) == '"') index++ else state = CsvQuoteState.AFTER_QUOTE
            }
            CsvQuoteState.AFTER_QUOTE -> when (line[index]) {
                delimiter -> state = CsvQuoteState.FIELD_START
                else -> return CsvQuoteScan(CsvQuoteState.FIELD_START, false)
            }
        }
        index++
    }
    return CsvQuoteScan(state, true)
}

private fun parseCsvRow(text: String, delimiter: Char): List<String> {
    val values = mutableListOf<String>()
    val field = StringBuilder()
    var state = CsvQuoteState.FIELD_START
    var index = 0
    while (index < text.length) {
        if (index % PROFILE_CANCELLATION_INTERVAL.toInt() == 0) checkParserCancellation()
        val char = text[index]
        when (state) {
            CsvQuoteState.FIELD_START -> when (char) {
                '"' -> state = CsvQuoteState.QUOTED
                delimiter -> values += ""
                else -> { field.append(char); state = CsvQuoteState.UNQUOTED }
            }
            CsvQuoteState.UNQUOTED -> when (char) {
                delimiter -> { values += field.toString(); field.clear(); state = CsvQuoteState.FIELD_START }
                '"' -> error("Quote inside an unquoted CSV field.")
                else -> field.append(char)
            }
            CsvQuoteState.QUOTED -> when {
                char == '"' && text.getOrNull(index + 1) == '"' -> { field.append('"'); index++ }
                char == '"' -> state = CsvQuoteState.AFTER_QUOTE
                else -> field.append(char)
            }
            CsvQuoteState.AFTER_QUOTE -> when (char) {
                delimiter -> { values += field.toString(); field.clear(); state = CsvQuoteState.FIELD_START }
                else -> error("Unexpected text after a closing CSV quote.")
            }
        }
        index++
    }
    require(state != CsvQuoteState.QUOTED) { "Unclosed CSV quote." }
    values += field.toString()
    return values
}

private fun MutableList<String>.addLimited(message: String) {
    if (size < 30) add(message)
}

private val TIME_OUTPUT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT)
private val LONG_MIN_DECIMAL = BigDecimal.valueOf(Long.MIN_VALUE)
private val LONG_MAX_DECIMAL = BigDecimal.valueOf(Long.MAX_VALUE)
private val TIME_OF_DAY_FORMATTERS = listOf(
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT),
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT),
)
private val STANDARD_TIMESTAMP_FORMATTERS = listOf(
    DateTimeFormatter.ISO_DATE_TIME,
    DateTimeFormatter.ISO_OFFSET_DATE_TIME,
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT),
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT),
) + TIME_OF_DAY_FORMATTERS
private val SYSLOG_SEVERITY = listOf("EMERG", "ALERT", "CRIT", "ERROR", "WARNING", "NOTICE", "INFO", "DEBUG")
private val RELATIVE_TIME_REGEX = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*(ms|s|sec|us|µs|ns)$", RegexOption.IGNORE_CASE)
private val RFC5424_PREFIX_REGEX = Regex("^<([0-9]{1,3})>([1-9][0-9]?) ([^ ]+) ([^ ]+) ([^ ]+) ([^ ]+) ([^ ]+) ")
private val RFC3164_REGEX = Regex(
    "^(?:<([0-9]{1,3})>)?([A-Z][a-z]{2}) +([ 0-9][0-9]) ([0-9]{2}:[0-9]{2}:[0-9]{2}) " +
        "([^ ]+) ([^:\\[]+)(?:\\[([0-9]+)\\])?: ?(.*)$",
)
private val STRUCTURED_DATA_ESCAPABLE = setOf('\\', ']', '"')
