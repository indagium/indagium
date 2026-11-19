package com.indagium.utils

import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.concurrent.CancellationException

private const val MAX_ADDITIONAL_RECORD_CHARS = 256 * 1024
private const val MAX_LOGFMT_FIELDS = 128
private const val MAX_LOGFMT_VALUE_CHARS = 4096
internal const val MAX_CRI_REORDERED_CHARS = 2 * 1024 * 1024
internal const val MAX_CRI_REORDERED_RECORDS = 512
private const val SIGNATURE_SAMPLE_LINES = 24
private const val MINIMUM_SIGNATURE_MATCHES = 2
private const val SIGNATURE_PERCENT = 70
private const val PERCENT_BASE = 100
private const val JSON_SIGNATURE_WEIGHT = 4
private const val JOURNAL_SIGNATURE_WEIGHT = 5
private const val HTTP_SIGNATURE_WEIGHT = 3
private const val DMESG_SIGNATURE_WEIGHT = 2
private const val LOGFMT_SIGNATURE_WEIGHT = 2
private const val LOGFMT_KEY_LIMIT = 128
private const val LOGFMT_ESCAPE_HEX_DIGITS = 2
private const val LOGFMT_UNICODE_HEX_DIGITS = 4
private const val LOGFMT_CODE_POINT_HEX_DIGITS = 8
private const val MAX_UNICODE_CODE_POINT = 0x10ffff
private const val MIN_SURROGATE_CODE_POINT = 0xd800
private const val MAX_SURROGATE_CODE_POINT = 0xdfff
private const val NEWLINE_LENGTH = 1
private const val CRI_PARTIAL_TAG = "P"
private const val CRI_FINAL_TAG = "F"
private const val CRI_FRAGMENT_COUNT_BASE = 1
private const val PARSER_CANCELLATION_INTERVAL = 128L
private const val MAX_JSON_NESTING = 6
private const val JSON_ARRAY_VALUE_LIMIT = 128
private const val LOGFMT_ESCAPE_ALERT = 7
private const val LOGFMT_ESCAPE_FORM_FEED = 0x0c
private const val LOGFMT_ESCAPE_VERTICAL_TAB = 0x0b
private const val LOGFMT_ESCAPE_BACKSPACE = 8
private const val LOGFMT_ESCAPE_NEWLINE = 10
private const val LOGFMT_ESCAPE_CARRIAGE_RETURN = 13
private const val LOGFMT_ESCAPE_TAB = 9
private const val LOGFMT_ESCAPE_BACKSLASH = 92
private const val LOGFMT_ESCAPE_QUOTE = 34
private const val CRI_FIELD_COUNT = 4
private const val CRI_TIMESTAMP_INDEX = 0
private const val CRI_STREAM_INDEX = 1
private const val CRI_TAG_INDEX = 2
private const val CRI_PAYLOAD_INDEX = 3
private val CRI_STREAMS = setOf("stdout", "stderr")
private val CRI_FRAGMENT_TAGS = setOf("P", "F")
private val PINO_LEVELS = setOf(10, 20, 30, 40, 50, 60)
private val JOURNAL_PRIORITIES = 0..7
private val LOGFMT_TIME_KEYS = setOf("time", "timestamp", "ts")
private val LOGFMT_LEVEL_KEYS = setOf("level", "severity")
private val LOGFMT_MESSAGE_KEYS = setOf("msg", "message")

internal data class AdditionalLogProfileDetection(
    val profile: LogFormatProfile? = null,
    val ambiguous: Boolean = false,
    /** The profiles that tied for the best score; more than one only when [ambiguous]. */
    val tiedProfiles: List<LogFormatProfile> = emptyList(),
)

/** Finds only recognizable content signatures. Extensions are hints and never select a built-in. */
internal fun detectAdditionalLogProfile(sample: ByteArray): LogFormatProfile? =
    detectAdditionalLogProfileResult(sample).profile

internal fun detectAdditionalLogProfileResult(sample: ByteArray): AdditionalLogProfileDetection {
    val text = runCatching {
        openLogTextReader(ByteArrayInputStream(sample)).use { it.readText() }
    }.getOrDefault("")
    val lines = text.lineSequence().filter(String::isNotBlank).take(SIGNATURE_SAMPLE_LINES).toList()
    if (lines.isEmpty()) return AdditionalLogProfileDetection()
    val jsonRecords = scanJsonObjectRecordRaws(text)
    val candidates = detectJsonCandidates(jsonRecords) + listOfNotNull(
        detectHttpCandidate(lines),
        detectDmesgCandidate(lines),
        detectCriCandidate(lines),
        detectLogfmtCandidate(lines),
    )
    return chooseDetectionCandidate(candidates)
}

private fun detectJsonCandidates(records: List<String>): List<Pair<Int, LogFormatProfile>> {
    val rows = records.mapNotNull(::additionalJsonFields)
    if (rows.isEmpty()) return emptyList()
    return listOfNotNull(
        scoredCandidate("docker-json-file", rows, ::isDockerJsonRow, JSON_SIGNATURE_WEIGHT, rows.size),
        scoredCandidate("pino-json", rows, ::isPinoJsonRow, JSON_SIGNATURE_WEIGHT, rows.size),
        scoredCandidate("systemd-journal-json", rows, ::isJournalJsonRow, JOURNAL_SIGNATURE_WEIGHT, rows.size),
    )
}

private fun isDockerJsonRow(row: Map<String, String>): Boolean =
    row["log"] != null && row["stream"] in setOf("stdout", "stderr") && isIsoTimestamp(row["time"])

private fun isPinoJsonRow(row: Map<String, String>): Boolean =
    row["msg"] != null && row["time"]?.toLongOrNull() != null &&
        row["level"]?.toIntOrNull()?.let { it in PINO_LEVELS } == true &&
        (row["pid"]?.toIntOrNull() != null || row["name"] != null || row["hostname"] != null)

private fun isJournalJsonRow(row: Map<String, String>): Boolean =
    row["MESSAGE"] != null && row["PRIORITY"]?.toIntOrNull()?.let { it in JOURNAL_PRIORITIES } == true &&
        row["__REALTIME_TIMESTAMP"]?.toLongOrNull() != null &&
        (row["_SYSTEMD_UNIT"] != null || row["_PID"] != null || row["_HOSTNAME"] != null)

private fun scoredCandidate(
    profileId: String,
    rows: List<Map<String, String>>,
    signature: (Map<String, String>) -> Boolean,
    weight: Int,
    sampleSize: Int,
): Pair<Int, LogFormatProfile>? {
    val count = rows.count(signature)
    return count.takeIf { it >= minimumSignatureCount(sampleSize) }
        ?.let { it * weight to additionalProfile(profileId) }
}

private fun detectHttpCandidate(lines: List<String>): Pair<Int, LogFormatProfile>? =
    lines.count { HTTP_ACCESS_LOG_REGEX.matchEntire(it) != null }
        .takeIf { it >= minimumSignatureCount(lines.size) }
        ?.let { it * HTTP_SIGNATURE_WEIGHT to additionalProfile("http-common-combined") }

private fun detectDmesgCandidate(lines: List<String>): Pair<Int, LogFormatProfile>? =
    lines.count(DMESG_SIGNATURE::matches)
        .takeIf { it >= minimumSignatureCount(lines.size) }
        ?.let { it * DMESG_SIGNATURE_WEIGHT to additionalProfile("dmesg-boot-time") }

private fun detectCriCandidate(lines: List<String>): Pair<Int, LogFormatProfile>? =
    lines.count(::parseCriLineForDetection)
        .takeIf { it >= minimumSignatureCount(lines.size) }
        ?.let { it * JSON_SIGNATURE_WEIGHT to additionalProfile("kubernetes-cri") }

private fun detectLogfmtCandidate(lines: List<String>): Pair<Int, LogFormatProfile>? {
    val count = lines.count { raw -> parseLogfmtFields(raw)?.let(::hasLogfmtSignature) == true }
    return count.takeIf { it >= minimumSignatureCount(lines.size) }
        ?.let { it * LOGFMT_SIGNATURE_WEIGHT to additionalProfile("logfmt") }
}

private fun hasLogfmtSignature(fields: Map<String, String>): Boolean =
    fields.keys.any { it in LOGFMT_TIME_KEYS } &&
        fields.keys.any { it in LOGFMT_LEVEL_KEYS } &&
        fields.keys.any { it in LOGFMT_MESSAGE_KEYS }

private fun chooseDetectionCandidate(candidates: List<Pair<Int, LogFormatProfile>>): AdditionalLogProfileDetection {
    val bestScore = candidates.maxOfOrNull { it.first } ?: return AdditionalLogProfileDetection()
    val winners = candidates.filter { it.first == bestScore }.map { it.second }.distinctBy { it.id }
    return AdditionalLogProfileDetection(
        profile = winners.singleOrNull(),
        ambiguous = winners.size > 1,
        tiedProfiles = winners.takeIf { it.size > 1 }.orEmpty(),
    )
}

private fun additionalProfile(id: String): LogFormatProfile =
    ADDITIONAL_BUILTIN_LOG_FORMAT_PROFILES.first { it.id == id }

/** Parses one logfmt / Go slog text row at a time; malformed or oversized rows remain raw. */
@Suppress("LoopWithTooManyJumpStatements") // Each skipped row yields its raw record before continuing.
internal fun parseLogfmtProfile(
    lines: Sequence<String>,
    profile: LogFormatProfile,
    startId: Int = 1,
    sourceClockId: String? = null,
    sourceLineOffset: Long = 0,
): ParsedLog {
    require(profile.engine == LogFormatEngine.LOGFMT) { "The logfmt parser requires a LOGFMT profile." }
    val diagnostics = mutableListOf<String>()
    var sourceLineCount = sourceLineOffset
    val records = sequence {
        var lineNumber = sourceLineOffset
        for (raw in lines) {
            lineNumber++
            sourceLineCount = lineNumber
            if (lineNumber % PARSER_CANCELLATION_INTERVAL == 0L) checkParserCancellation()
            if (raw.isBlank()) continue
            if (raw.length > MAX_ADDITIONAL_RECORD_CHARS) {
                diagnostics.addLimited("An oversized logfmt record was retained as raw text.")
                yield(AdditionalProfileRecord(raw, sourceStartLine = lineNumber, sourceEndLine = lineNumber, parseFields = false))
                continue
            }
            val parsed = parseLogfmtFields(raw)
            if (parsed == null) {
                diagnostics.addLimited("A malformed logfmt record was retained as raw text.")
                yield(AdditionalProfileRecord(raw, sourceStartLine = lineNumber, sourceEndLine = lineNumber, parseFields = false))
                continue
            }
            val fields = linkedMapOf<String, String>()
            val fieldAliases = linkedMapOf<String, String>()
            parsed.forEach { (key, value) -> fields[key] = value }
            val aliases = mapOf(
                LogFieldRole.TIMESTAMP to listOf("time", "timestamp", "ts"),
                LogFieldRole.SEVERITY to listOf("level", "severity"),
                LogFieldRole.COMPONENT to listOf("logger", "component", "service"),
                LogFieldRole.MESSAGE to listOf("msg", "message"),
                LogFieldRole.PROCESS_ID to listOf("pid", "process_id"),
                LogFieldRole.THREAD_ID to listOf("tid", "thread", "thread_id"),
                LogFieldRole.HOST to listOf("hostname", "host"),
                LogFieldRole.SERVICE to listOf("service", "service.name"),
                LogFieldRole.REQUEST_ID to listOf("request_id", "requestId", "req_id"),
                LogFieldRole.TRACE_ID to listOf("trace_id", "traceId"),
                LogFieldRole.SOURCE to listOf("source", "caller", "file"),
            )
            profile.fieldMappings.forEach { (role, source) ->
                val key = source.takeIf(parsed::containsKey) ?: aliases[role]?.firstOrNull(parsed::containsKey)
                if (key != null) {
                    fields[role.name] = parsed.getValue(key)
                    fieldAliases[role.name] = key
                }
            }
            profile.attributes.forEach { attribute ->
                parsed[attribute.source]?.let {
                    fields[attribute.id] = it
                    fieldAliases[attribute.id] = attribute.source
                }
            }
            yield(
                AdditionalProfileRecord(
                    raw = raw,
                    fields = fields,
                    fieldAliases = fieldAliases,
                    sourceStartLine = lineNumber,
                    sourceEndLine = lineNumber,
                ),
            )
        }
    }
    val parsed = parseAdditionalRecords(records, profile, startId, sourceClockId, diagnostics)
    return parsed.copy(sourceLineCount = sourceLineCount)
}

/** Parses CRI rows, joins P/F fragments independently by stdout/stderr, and restores start order. */
internal fun parseCriProfile(
    lines: Sequence<String>,
    profile: LogFormatProfile,
    startId: Int = 1,
    sourceClockId: String? = null,
    sourceLineOffset: Long = 0,
): ParsedLog {
    require(profile.engine == LogFormatEngine.CRI) { "The CRI parser requires a CRI profile." }
    val diagnostics = mutableListOf<String>()
    var sourceLineCount = sourceLineOffset
    val orderedRecords = sequence {
        val assembler = CriRecordAssembler(diagnostics)
        var lineNumber = sourceLineOffset
        for (raw in lines) {
            lineNumber++
            sourceLineCount = lineNumber
            if (lineNumber % PARSER_CANCELLATION_INTERVAL == 0L) checkParserCancellation()
            assembler.accept(raw, lineNumber).forEach { yield(it) }
        }
        assembler.finish().forEach { yield(it) }
    }
    val parsed = parseAdditionalRecords(orderedRecords, profile, startId, sourceClockId, diagnostics)
    return parsed.copy(sourceLineCount = sourceLineCount)
}

internal class CriRecordAssembler(private val diagnostics: MutableList<String>) {
    private val pending = linkedMapOf<String, CriPending>()
    private val ready = mutableListOf<AdditionalProfileRecord>()
    private var readyChars = 0

    fun accept(raw: String, lineNumber: Long): List<AdditionalProfileRecord> {
        if (raw.isBlank()) return emptyList()
        if (raw.length > MAX_ADDITIONAL_RECORD_CHARS) {
            retainMalformed(raw, lineNumber, "An oversized CRI row was retained as raw text.")
        } else {
            val parsed = parseCriLine(raw)
            if (parsed == null) {
                retainMalformed(raw, lineNumber, "A malformed CRI row was retained as raw text.")
            } else {
                acceptParsed(parsed, raw, lineNumber)
            }
        }
        return enforceReorderBound() + flushSafe()
    }

    /** Seeds one partial stream from the row already shown by the initial whole-file parse. */
    fun seedPending(
        rawRecord: String,
        sourceStartLine: Long,
        sourceEndLine: Long,
        readyRecordCountAfter: Int,
        readyCharsAfter: Int,
    ): Boolean {
        if (sourceStartLine <= 0 || sourceEndLine < sourceStartLine ||
            readyRecordCountAfter > MAX_CRI_REORDERED_RECORDS || readyCharsAfter > MAX_CRI_REORDERED_CHARS
        ) return false
        val fragments = rawRecord.split('\n')
        val parsed = fragments.map(::parseCriLine)
        val rows = parsed.filterNotNull()
        val first = rows.firstOrNull() ?: return false
        if (rows.size != fragments.size || !isPartialCriStream(rows, first.stream) || first.stream in pending) return false
        val value = CriPending(first.stream, first.timestamp, first.tag, sourceStartLine)
        fragments.zip(rows).forEachIndexed { index, (fragment, row) ->
            // The exact physical positions of interleaved streams are intentionally not needed
            // to rebuild this stream's pending payload. Keep the original span endpoints and the
            // record's start order; appended lines carry their exact positions from here onward.
            value.append(fragment, row.payload, sourceStartLine + index)
        }
        value.endLine = sourceEndLine
        pending[first.stream] = value
        return true
    }

    private fun isPartialCriStream(rows: List<CriRow>, stream: String): Boolean =
        rows.first().tag == CRI_PARTIAL_TAG && rows.last().tag == CRI_PARTIAL_TAG && rows.all { it.stream == stream }

    fun finish(): List<AdditionalProfileRecord> {
        pending.keys.toList().forEach { emitPending(it, incomplete = true) }
        return ready.sortedBy { it.sourceStartLine }.also { ready.clear(); readyChars = 0 }
    }

    private fun retainMalformed(raw: String, lineNumber: Long, message: String) {
        diagnostics.addLimited(message)
        ready += AdditionalProfileRecord(
            raw = raw,
            sourceStartLine = lineNumber,
            sourceEndLine = lineNumber,
            parseFields = false,
        )
        readyChars += raw.length
    }

    private fun acceptParsed(parsed: CriRow, raw: String, lineNumber: Long) {
        val existing = pending[parsed.stream]
        if (existing == null) {
            startRecord(parsed, raw, lineNumber)
        } else if (existing.rawLines.length + NEWLINE_LENGTH + raw.length > MAX_ADDITIONAL_RECORD_CHARS) {
            emitPending(parsed.stream, incomplete = true)
            startRecord(parsed, raw, lineNumber)
        } else {
            appendFragment(existing, parsed, raw, lineNumber)
        }
    }

    private fun startRecord(parsed: CriRow, raw: String, lineNumber: Long) {
        val record = CriPending(parsed.stream, parsed.timestamp, parsed.tag, lineNumber)
            .append(raw, parsed.payload, lineNumber)
        if (parsed.tag == CRI_PARTIAL_TAG) {
            pending[parsed.stream] = record
        } else {
            addReady(record.toRecord())
        }
    }

    private fun appendFragment(existing: CriPending, parsed: CriRow, raw: String, lineNumber: Long) {
        existing.append(raw, parsed.payload, lineNumber)
        existing.lastTag = parsed.tag
        if (parsed.tag == CRI_FINAL_TAG) {
            pending.remove(parsed.stream)
            addReady(existing.toRecord())
        }
    }

    private fun addReady(record: AdditionalProfileRecord) {
        ready += record
        readyChars += record.bufferSize()
    }

    private fun emitPending(stream: String, incomplete: Boolean) {
        val value = pending.remove(stream) ?: return
        if (incomplete) {
            diagnostics.addLimited("An incomplete CRI record on $stream at source line ${value.startLine} was retained.")
        }
        addReady(value.toRecord())
    }

    private fun enforceReorderBound(): List<AdditionalProfileRecord> {
        val released = mutableListOf<AdditionalProfileRecord>()
        while ((readyChars > MAX_CRI_REORDERED_CHARS || ready.size > MAX_CRI_REORDERED_RECORDS) && pending.isNotEmpty()) {
            val oldest = pending.values.minByOrNull(CriPending::startLine) ?: break
            emitPending(oldest.stream, incomplete = true)
            released += flushSafe()
        }
        return released
    }

    private fun flushSafe(): List<AdditionalProfileRecord> {
        val earliestOpen = pending.values.minOfOrNull(CriPending::startLine)
        val releasable = ready.filter { earliestOpen == null || it.sourceStartLine < earliestOpen }
            .sortedBy(AdditionalProfileRecord::sourceStartLine)
        if (releasable.isEmpty()) return emptyList()
        val releaseIds = releasable.mapTo(mutableSetOf(), AdditionalProfileRecord::sourceStartLine)
        ready.removeAll { it.sourceStartLine in releaseIds }
        releasable.forEach { readyChars -= it.bufferSize() }
        return releasable
    }
}

private fun AdditionalProfileRecord.bufferSize(): Int =
    raw.length + (fields[LogFieldRole.MESSAGE.name]?.length ?: 0)

private data class CriRow(val timestamp: String, val stream: String, val tag: String, val payload: String)

private val CRI_FIELD_ALIASES = mapOf("TIMESTAMP" to "time", "STREAM" to "stream", "MESSAGE" to "message")

private class CriPending(
    val stream: String,
    val firstTimestamp: String,
    var lastTag: String,
    val startLine: Long,
    var endLine: Long = startLine,
    val rawLines: StringBuilder = StringBuilder(),
    val payload: StringBuilder = StringBuilder(),
) {
    fun append(raw: String, message: String, sourceLine: Long): CriPending {
        if (rawLines.isNotEmpty()) rawLines.append('\n')
        rawLines.append(raw)
        payload.append(message)
        endLine = sourceLine
        return this
    }

    fun toRecord(): AdditionalProfileRecord {
        val fields = linkedMapOf(
            "time" to firstTimestamp,
            "stream" to stream,
            "cri_tag" to lastTag,
            "message" to payload.toString(),
            LogFieldRole.TIMESTAMP.name to firstTimestamp,
            LogFieldRole.STREAM.name to stream,
            LogFieldRole.MESSAGE.name to payload.toString(),
            "fragment_count" to rawLines.count { it == '\n' }.plus(CRI_FRAGMENT_COUNT_BASE).toString(),
        )
        return AdditionalProfileRecord(
            raw = rawLines.toString(),
            fields = fields,
            fieldAliases = CRI_FIELD_ALIASES,
            timestamp = firstTimestamp,
            sourceStartLine = startLine,
            sourceEndLine = endLine,
        )
    }
}

private fun parseCriLine(raw: String): CriRow? {
    val fields = raw.split(' ', limit = CRI_FIELD_COUNT)
    if (fields.size != CRI_FIELD_COUNT) return null
    val timestamp = fields[CRI_TIMESTAMP_INDEX]
    val stream = fields[CRI_STREAM_INDEX]
    val tag = fields[CRI_TAG_INDEX]
    if (stream !in CRI_STREAMS) return null
    val resolvedTag = tag.split(':').firstOrNull { it in CRI_FRAGMENT_TAGS } ?: return null
    if (runCatching { Instant.parse(timestamp) }.isFailure) return null
    return CriRow(timestamp, stream, resolvedTag, fields[CRI_PAYLOAD_INDEX])
}

private fun parseCriLineForDetection(raw: String): Boolean = parseCriLine(raw) != null

private fun parseLogfmtFields(raw: String): Map<String, String>? {
    val result = linkedMapOf<String, String>()
    var index = 0
    while (index < raw.length) {
        while (raw.getOrNull(index)?.isWhitespace() == true) index++
        if (index >= raw.length) break
        val keyStart = index
        while (index < raw.length && !raw[index].isWhitespace() && raw[index] != '=') index++
        if (index == keyStart || raw.getOrNull(index) != '=') return null
        val key = raw.substring(keyStart, index)
        if (key.length > LOGFMT_KEY_LIMIT || result.size >= MAX_LOGFMT_FIELDS) return null
        val value = readLogfmtValue(raw, index + 1) ?: return null
        result[key] = value.value
        index = value.nextIndex
    }
    return result
}

private data class LogfmtValue(val value: String, val nextIndex: Int)

private fun readLogfmtValue(raw: String, start: Int): LogfmtValue? =
    if (raw.getOrNull(start) == '"') readQuotedLogfmtValue(raw, start + 1)
    else readBareLogfmtValue(raw, start)

private fun readBareLogfmtValue(raw: String, start: Int): LogfmtValue? {
    var end = start
    while (end < raw.length && !raw[end].isWhitespace()) end++
    val value = raw.substring(start, end)
    return value.takeIf { it.length <= MAX_LOGFMT_VALUE_CHARS }?.let { LogfmtValue(it, end) }
}

private fun readQuotedLogfmtValue(raw: String, start: Int): LogfmtValue? {
    val decoded = StringBuilder()
    var index = start
    var closed = false
    while (index < raw.length && !closed) {
        when (val char = raw[index++]) {
            '"' -> closed = true
            '\\' -> {
                val escape = decodeLogfmtEscape(raw, index) ?: return null
                decoded.append(escape.value)
                index = escape.nextIndex
            }
            else -> decoded.append(char)
        }
        if (decoded.length > MAX_LOGFMT_VALUE_CHARS) return null
    }
    if (!closed || raw.getOrNull(index)?.isWhitespace() == false) return null
    return LogfmtValue(decoded.toString(), index)
}

private data class LogfmtEscape(val value: String, val nextIndex: Int)

private fun decodeLogfmtEscape(raw: String, offset: Int): LogfmtEscape? {
    val escape = raw.getOrNull(offset) ?: return null
    val literal = logfmtSingleCharEscape(escape)
    if (literal != null) return LogfmtEscape(literal.toChar().toString(), offset + 1)
    return when (escape) {
        'x' -> appendEscapedCodeUnit(raw, offset + 1, LOGFMT_ESCAPE_HEX_DIGITS)
            ?.let { (value, end) -> LogfmtEscape(value.toString(), end) }
        'u' -> appendEscapedCodeUnit(raw, offset + 1, LOGFMT_UNICODE_HEX_DIGITS)
            ?.let { (value, end) -> LogfmtEscape(value.toString(), end) }
        'U' -> appendEscapedCodePoint(raw, offset + 1)
            ?.let { (value, end) -> LogfmtEscape(String(Character.toChars(value)), end) }
        else -> null
    }
}

private fun logfmtSingleCharEscape(escape: Char): Int? = when (escape) {
    'a' -> LOGFMT_ESCAPE_ALERT
    'b' -> LOGFMT_ESCAPE_BACKSPACE
    'f' -> LOGFMT_ESCAPE_FORM_FEED
    'n' -> LOGFMT_ESCAPE_NEWLINE
    'r' -> LOGFMT_ESCAPE_CARRIAGE_RETURN
    't' -> LOGFMT_ESCAPE_TAB
    'v' -> LOGFMT_ESCAPE_VERTICAL_TAB
    '\\' -> LOGFMT_ESCAPE_BACKSLASH
    '"' -> LOGFMT_ESCAPE_QUOTE
    else -> null
}

private fun appendEscapedCodeUnit(raw: String, offset: Int, digits: Int): Pair<Char, Int>? {
    val end = offset + digits
    if (end > raw.length) return null
    val value = raw.substring(offset, end).toIntOrNull(16) ?: return null
    return value.toChar() to end
}

private fun appendEscapedCodePoint(raw: String, offset: Int): Pair<Int, Int>? {
    val end = offset + LOGFMT_CODE_POINT_HEX_DIGITS
    if (end > raw.length) return null
    val value = raw.substring(offset, end).toLongOrNull(16)?.takeIf { it <= MAX_UNICODE_CODE_POINT }?.toInt() ?: return null
    if (value in MIN_SURROGATE_CODE_POINT..MAX_SURROGATE_CODE_POINT) return null
    return value to end
}

private fun additionalJsonFields(raw: String): Map<String, String>? = runCatching {
    val values = linkedMapOf<String, String>()

    fun visit(value: JsonObject, prefix: String, depth: Int) {
        if (depth > MAX_JSON_NESTING || values.size >= JSON_ARRAY_VALUE_LIMIT) return
        value.forEach { (key, child) ->
            val fullKey = if (prefix.isEmpty()) key else "$prefix.$key"
            when (child) {
                is JsonObject -> visit(child, fullKey, depth + 1)
                is JsonArray -> values[fullKey] = child.joinToString(prefix = "[", postfix = "]") { element ->
                    (element as? JsonPrimitive)?.contentOrNull ?: element.toString()
                }
                is JsonPrimitive -> child.contentOrNull?.let { values[fullKey] = it }
            }
        }
    }
    visit(Json.parseToJsonElement(raw).jsonObject, "", 0)
    values
}.getOrNull()

private fun isIsoTimestamp(value: String?): Boolean = value != null && runCatching { Instant.parse(value) }.isSuccess

private fun MutableList<String>.addLimited(message: String) {
    if (size < 30) add(message)
}

private fun minimumSignatureCount(sampleLineCount: Int): Int =
    if (sampleLineCount <= 1) 1 else maxOf(MINIMUM_SIGNATURE_MATCHES, sampleLineCount * SIGNATURE_PERCENT / PERCENT_BASE)

internal fun checkParserCancellation() {
    if (Thread.currentThread().isInterrupted) throw CancellationException("Log parsing was interrupted.")
}

private val DMESG_SIGNATURE = Regex("^\\[\\s*[0-9]+(?:\\.[0-9]+)?\\]\\s+.+$")
private val HTTP_ACCESS_LOG_REGEX by lazy { Regex(additionalProfile("http-common-combined").regexPattern) }

private val ADDITIONAL_BUILTIN_LOG_FORMAT_PROFILES = com.indagium.model.ADDITIONAL_BUILTIN_LOG_FORMAT_PROFILES
