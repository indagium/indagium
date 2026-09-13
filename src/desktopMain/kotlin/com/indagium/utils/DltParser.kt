package com.indagium.utils

import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogLevel
import java.io.BufferedInputStream
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Result of syntax detection and parsing. The old List<LogEntry> parser API remains available. */
data class ParsedLog(val format: LogFormat, val entries: List<LogEntry>)

private const val DLT_STORAGE_HEADER_SIZE = 16
private const val DLT_MAX_FRAME_SIZE = 0xffff
private const val DLT_V1 = 1
private const val DLT_V2 = 2
private const val DLT_STANDARD_HEADER_SIZE = 4
private const val DLT_EXTENDED_HEADER_SIZE = 10
private const val DLT_ID_SIZE = 4
private const val DLT_HEADER_TYPE_OFFSET = 0
private const val DLT_LENGTH_MSB_OFFSET = 2
private const val DLT_LENGTH_LSB_OFFSET = 3
private const val DLT_VERSION_SHIFT = 5
private const val DLT_HTYP_UEH = 0x01
private const val DLT_HTYP_MSBF = 0x02
private const val DLT_HTYP_WEID = 0x04
private const val DLT_HTYP_WSID = 0x08
private const val DLT_HTYP_WTMS = 0x10
private const val DLT_MSIN_VERB = 0x01
private const val BYTE_MASK = 0xff
private const val BITS_PER_BYTE = 8
private const val UINT16_SIZE = 2
private const val UINT32_SIZE = 4
private const val MICROSECONDS_PER_SECOND = 1_000_000L
private const val NANOSECONDS_PER_MICROSECOND = 1_000L
private const val TEXT_PRINTABLE_PERCENT = 85
private const val TEXT_PERCENT_BASE = 100
private val DLT_STORAGE_MAGIC = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 1)
private val DLT_STORAGE_MAGIC_V2 = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 2)
private val TIME_OUTPUT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

private const val PARSE_LOG_CONTENT_BUFFER_BYTES = 64 * 1024

/**
 * Parse a stream by content. Routes through the shared [classifyLogContent] classifier so this
 * can never disagree with [sniffCandidateContent]/[isLikelyDltSourceFile] about what a given
 * prefix of bytes means. [fileName], when known, is used only to gate an ambiguous headerless v2
 * frame (see [classifyLogContent]'s doc) — pass it whenever the caller has one.
 */
fun parseLogContent(stream: InputStream, startId: Int = 1, fileName: String? = null): ParsedLog {
    val input = if (stream.markSupported()) stream else BufferedInputStream(stream, PARSE_LOG_CONTENT_BUFFER_BYTES)
    input.mark(CONTENT_SNIFF_BYTES + 1)
    val sample = input.readNBytes(CONTENT_SNIFF_BYTES)
    input.reset()
    val atEof = sample.size < CONTENT_SNIFF_BYTES
    return when (classifyLogContent(sample, atEof, fileName)) {
        LogContentKind.DLT_STORAGE -> ParsedLog(LogFormat.DLT, parseDltBinary(input, storage = true, startId = startId))
        LogContentKind.DLT_RAW -> ParsedLog(LogFormat.DLT, parseDltBinary(input, storage = false, startId = startId))
        LogContentKind.DLT_UNSUPPORTED_V2 -> throw IllegalArgumentException("DLT protocol v2 is not supported")
        LogContentKind.DLT_VIEWER_CSV, LogContentKind.DLT_VIEWER_TEXT -> parseViewerTextLog(input, startId)
        LogContentKind.TEXT, LogContentKind.OTHER -> parseTextLog(input, startId)
    }
}

fun parseDltContent(stream: InputStream, startId: Int = 1): ParsedLog {
    val input = if (stream.markSupported()) stream else BufferedInputStream(stream)
    input.mark(32)
    val prefix = input.readNBytes(16)
    input.reset()
    val storageV1 = prefix.size >= DLT_STANDARD_HEADER_SIZE &&
        prefix.copyOfRange(0, DLT_STANDARD_HEADER_SIZE).contentEquals(DLT_STORAGE_MAGIC)
    val storageV2 = prefix.size >= DLT_STANDARD_HEADER_SIZE &&
        prefix.copyOfRange(0, DLT_STANDARD_HEADER_SIZE).contentEquals(DLT_STORAGE_MAGIC_V2)
    require(prefix.size >= DLT_STANDARD_HEADER_SIZE && (storageV1 || storageV2 || plausibleFrame(prefix) || isDltV2Header(prefix))) {
        "The input is not a valid DLT stream"
    }
    require(!(storageV2 || isDltV2Header(prefix))) { "DLT protocol v2 is not supported" }
    return ParsedLog(LogFormat.DLT, parseDltBinary(input, storageV1, startId))
}

private fun plausibleFrame(sample: ByteArray): Boolean {
    if (sample.size < DLT_STANDARD_HEADER_SIZE) return false
    val htyp = sample[DLT_HEADER_TYPE_OFFSET].toInt() and BYTE_MASK
    // DLT v1/v2 stores the version in bits 5..7. Reject arbitrary binary/text before looking at len.
    val version = htyp ushr DLT_VERSION_SHIFT
    if (version != DLT_V1) return false
    // LEN is part of the standard header and is always network byte order. MSBF applies to
    // typed payload values only.
    val len = unsigned16BigEndian(sample[DLT_LENGTH_MSB_OFFSET], sample[DLT_LENGTH_LSB_OFFSET])
    val minimum = DLT_STANDARD_HEADER_SIZE +
        (if (htyp and DLT_HTYP_WEID != 0) DLT_ID_SIZE else 0) +
        (if (htyp and DLT_HTYP_WSID != 0) UINT32_SIZE else 0) +
        (if (htyp and DLT_HTYP_WTMS != 0) UINT32_SIZE else 0) +
        (if (htyp and DLT_HTYP_UEH != 0) DLT_EXTENDED_HEADER_SIZE else 0)
    return len in minimum..DLT_MAX_FRAME_SIZE
}

private fun isDltV2Header(sample: ByteArray): Boolean =
    sample.size >= DLT_STANDARD_HEADER_SIZE &&
        ((sample[DLT_HEADER_TYPE_OFFSET].toInt() and BYTE_MASK) ushr DLT_VERSION_SHIFT) == DLT_V2

// The DLT-viewer-vs-logcat decision is made once, by classifyLogContent, before either of these
// runs — parseLogContent dispatches to the right one directly instead of re-detecting here.
private fun parseTextLog(input: InputStream, startId: Int): ParsedLog =
    openLogTextReader(input).use { reader -> ParsedLog(LogFormat.LOGCAT, parseLogcatLines(reader.lineSequence(), startId)) }

private fun parseViewerTextLog(input: InputStream, startId: Int): ParsedLog =
    openLogTextReader(input).use { reader -> ParsedLog(LogFormat.DLT, parseDltViewerLines(reader.lineSequence(), startId)) }

private fun readFully(input: InputStream, target: ByteArray): Boolean {
    var offset = 0
    while (offset < target.size) {
        val count = input.read(target, offset, target.size - offset)
        if (count < 0) return false
        if (count == 0) continue
        offset += count
    }
    return true
}

private fun parseDltBinary(input: InputStream, storage: Boolean, startId: Int): List<LogEntry> {
    val entries = ArrayList<LogEntry>()
    var id = startId
    var storageEcu: String? = null
    var sawFrame = false
    while (true) {
        if (storage) {
            val storageHeader = ByteArray(DLT_STORAGE_HEADER_SIZE)
            val first = input.read()
            if (first < 0) break
            storageHeader[0] = first.toByte()
            require(readFully(input, storageHeader, 1)) { "Truncated DLT storage header" }
            require(storageHeader.copyOfRange(0, DLT_STANDARD_HEADER_SIZE).contentEquals(DLT_STORAGE_MAGIC)) { "Invalid DLT storage header" }
            val seconds = u32(storageHeader, DLT_STANDARD_HEADER_SIZE, littleEndian = true)
            val micros = u32(storageHeader, DLT_STANDARD_HEADER_SIZE + UINT32_SIZE, littleEndian = true)
            storageEcu = asciiId(storageHeader, DLT_STANDARD_HEADER_SIZE + UINT32_SIZE * 2, DLT_ID_SIZE)
            val frame = readFrame(input, storageEcu, seconds, micros)
            entries += frame.copy(id = id++)
            sawFrame = true
        } else {
            val first = input.read()
            if (first < 0) break
            val frameHeader = ByteArray(DLT_STANDARD_HEADER_SIZE)
            frameHeader[0] = first.toByte()
            require(readFully(input, frameHeader, 1)) { "Truncated DLT frame header" }
            val frame = readFrame(input, null, null, null, frameHeader)
            entries += frame.copy(id = id++)
            sawFrame = true
        }
    }
    require(sawFrame) { "The DLT stream contains no complete frames" }
    return entries
}

private fun readFully(input: InputStream, target: ByteArray, offset: Int): Boolean {
    var pos = offset
    while (pos < target.size) {
        val n = input.read(target, pos, target.size - pos)
        if (n < 0) return false
        if (n == 0) continue
        pos += n
    }
    return true
}

private fun readFrame(
    input: InputStream,
    storageEcu: String?,
    storageSeconds: Long?,
    storageMicros: Long?,
    suppliedHeader: ByteArray? = null,
): LogEntry {
    val header = suppliedHeader ?: ByteArray(DLT_STANDARD_HEADER_SIZE).also {
        require(readFully(input, it)) { "Truncated DLT frame header" }
    }
    val htyp = header[DLT_HEADER_TYPE_OFFSET].toInt() and BYTE_MASK
    require((htyp ushr DLT_VERSION_SHIFT) == DLT_V1) {
        if ((htyp ushr DLT_VERSION_SHIFT) == DLT_V2) "DLT protocol v2 is not supported" else "Unsupported DLT protocol version"
    }
    val msbf = htyp and DLT_HTYP_MSBF != 0
    val length = unsigned16BigEndian(header[DLT_LENGTH_MSB_OFFSET], header[DLT_LENGTH_LSB_OFFSET])
    require(length in DLT_STANDARD_HEADER_SIZE..DLT_MAX_FRAME_SIZE) { "Invalid DLT frame length: $length" }
    val body = ByteArray(length - DLT_STANDARD_HEADER_SIZE)
    require(readFully(input, body)) { "Truncated DLT frame payload" }
    var offset = 0
    var ecu = storageEcu
    var relativeTimestamp: Long? = null
    if (htyp and DLT_HTYP_WEID != 0) {
        require(offset + DLT_ID_SIZE <= body.size) { "Truncated DLT ECU id" }
        ecu = asciiId(body, offset, DLT_ID_SIZE); offset += DLT_ID_SIZE
    }
    if (htyp and DLT_HTYP_WSID != 0) {
        require(offset + UINT32_SIZE <= body.size) { "Truncated DLT session id" }
        offset += UINT32_SIZE
    }
    if (htyp and DLT_HTYP_WTMS != 0) {
        require(offset + UINT32_SIZE <= body.size) { "Truncated DLT timestamp" }
        relativeTimestamp = u32(body, offset, littleEndian = false); offset += UINT32_SIZE
    }
    var msin: Int? = null
    var noar = 0
    var apid: String? = null
    var ctid: String? = null
    if (htyp and DLT_HTYP_UEH != 0) {
        require(offset + DLT_EXTENDED_HEADER_SIZE <= body.size) { "Truncated DLT extended header" }
        msin = body[offset].toInt() and BYTE_MASK
        noar = body[offset + 1].toInt() and BYTE_MASK
        apid = asciiId(body, offset + UINT16_SIZE, DLT_ID_SIZE)
        ctid = asciiId(body, offset + UINT16_SIZE + DLT_ID_SIZE, DLT_ID_SIZE)
        offset += DLT_EXTENDED_HEADER_SIZE
    }
    val payload = body.copyOfRange(offset, body.size)
    val severity = msin?.let { dltSeverity(it) } ?: LogLevel.I
    val timestamp = when {
        storageSeconds != null -> formatEpoch(storageSeconds, storageMicros ?: 0L)
        else -> ""
    }
    val dltTs = storageSeconds ?: relativeTimestamp
    val source = when {
        storageSeconds != null -> "storage"
        relativeTimestamp != null -> "relative"
        else -> null
    }
    val tag = listOfNotNull(ecu?.takeIf { it.isNotBlank() }, apid?.takeIf { it.isNotBlank() }, ctid?.takeIf { it.isNotBlank() })
        .joinToString("/").ifBlank { "DLT" }
    return LogEntry(
        id = 0, ts = timestamp, level = severity, tag = tag,
        msg = decodeDltPayload(payload, noar, msin, msbf),
        dltEcuId = ecu?.takeIf { it.isNotBlank() }, dltAppId = apid?.takeIf { it.isNotBlank() },
        dltContextId = ctid?.takeIf { it.isNotBlank() }, dltMessageType = msin?.let(::dltMessageTypeName),
        dltTimestamp = dltTs, dltTimestampSource = source,
    )
}

private fun unsigned16BigEndian(a: Byte, b: Byte): Int =
    ((a.toInt() and BYTE_MASK) shl BITS_PER_BYTE) or (b.toInt() and BYTE_MASK)

private fun u32(bytes: ByteArray, offset: Int, littleEndian: Boolean): Long {
    return readUnsigned(bytes, offset, UINT32_SIZE, msbf = !littleEndian)
}

private fun asciiId(bytes: ByteArray, offset: Int, length: Int): String =
    bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }

private fun dltSeverity(msin: Int): LogLevel {
    // VERB is bit 0, MSTP is bits 1..3, and MTIN is bits 4..7. MTIN only denotes a
    // severity for LOG frames; app/network/control frames use the neutral presentation level.
    if (((msin ushr 1) and 0x07) != 0) return LogLevel.I
    return when ((msin ushr 4) and 0x0f) {
        1 -> LogLevel.A // fatal
        2 -> LogLevel.E
        3 -> LogLevel.W
        4 -> LogLevel.I
        5 -> LogLevel.D
        6 -> LogLevel.V
        else -> LogLevel.I
    }
}

private fun dltMessageTypeName(msin: Int): String = when ((msin ushr 1) and 0x07) {
    0 -> "log"
    1 -> "app_trace"
    2 -> "network_trace"
    3 -> "control"
    else -> "unknown"
}

// DLT v1 type info: low nibble is the standard scalar width, then type flags begin at bit 4.
private const val TYPE_BOOL = 0x00000010
private const val TYPE_SINT = 0x00000020
private const val TYPE_UINT = 0x00000040
private const val TYPE_FLOA = 0x00000080
private const val TYPE_ARAY = 0x00000100
private const val TYPE_STRG = 0x00000200
private const val TYPE_RAWD = 0x00000400
private const val TYPE_VARI = 0x00000800
private const val TYPE_TRAI = 0x00002000
private const val TYPE_STRU = 0x00004000
private const val TYPE_LENGTH_MASK = 0x0f
private const val SCALAR_WIDTH_8 = 1
private const val SCALAR_WIDTH_16 = 2
private const val SCALAR_WIDTH_CODE_32 = 3
private const val SCALAR_WIDTH_32 = 4
private const val SCALAR_WIDTH_64 = 8
private const val SCALAR_WIDTH_CODE_128 = 5
private const val SCALAR_WIDTH_128 = 16

private fun decodeDltPayload(payload: ByteArray, argumentCount: Int, msin: Int?, msbf: Boolean): String {
    if (payload.isEmpty()) return ""
    // A message without an extended header cannot be verbose. In a non-verbose message the
    // first payload word is the message id; it is still useful without a FIBEX dictionary.
    if (msin == null || msin and DLT_MSIN_VERB == 0) {
        if (payload.size < UINT32_SIZE) return hex(payload)
        val messageId = readUnsigned(payload, DLT_HEADER_TYPE_OFFSET, UINT32_SIZE, msbf)
        val rest = payload.copyOfRange(UINT32_SIZE, payload.size)
        val rendered = decodeText(rest) ?: hex(rest)
        return "messageId=0x%08X%s".format(Locale.ROOT, messageId, if (rest.isEmpty()) "" else " $rendered")
    }
    var offset = 0
    val rendered = ArrayList<String>(argumentCount)
    repeat(argumentCount) {
        val parsed = parseVerboseArgument(payload, offset, msbf, isLastArgument = it == argumentCount - 1)
        rendered += parsed.text
        offset = parsed.nextOffset
    }
    require(offset == payload.size) { "Malformed DLT verbose payload: trailing ${payload.size - offset} bytes" }
    return rendered.joinToString(" ")
}

private data class ParsedDltArgument(val text: String, val nextOffset: Int)

private fun parseVerboseArgument(
    bytes: ByteArray,
    start: Int,
    msbf: Boolean,
    isLastArgument: Boolean,
): ParsedDltArgument {
    requireRange(bytes, start, UINT32_SIZE, "type info")
    val type = readUnsigned(bytes, start, UINT32_SIZE, msbf).toInt()
    var offset = start + UINT32_SIZE
    val attribute = if (type and TYPE_VARI != 0) {
        val name = readLengthPrefixed(bytes, offset, msbf, "attribute name")
        offset = name.nextOffset
        // DLT attribute metadata carries a name and unit. Empty units are legal.
        val unit = readLengthPrefixed(bytes, offset, msbf, "attribute unit")
        offset = unit.nextOffset
        name.text to unit.text
    } else {
        null
    }
    val text = when {
        type and TYPE_STRG != 0 -> {
            val value = readLengthPrefixed(bytes, offset, msbf, "string")
            offset = value.nextOffset
            decodeText(value.raw) ?: hex(value.raw)
        }
        type and TYPE_RAWD != 0 || type and TYPE_TRAI != 0 -> {
            val value = readLengthPrefixed(bytes, offset, msbf, if (type and TYPE_TRAI != 0) "trace data" else "raw data")
            offset = value.nextOffset
            hex(value.raw)
        }
        type and (TYPE_ARAY or TYPE_STRU) != 0 -> {
            // V1 arrays begin with a dimension count and one entry count per dimension; structures
            // nest arbitrary typed arguments. Neither is a self-contained byte-length-delimited
            // value, so a generic parser can identify their endpoint only when it is the final
            // top-level argument. Preserve every remaining byte as deterministic uppercase hex,
            // and reject an ambiguous non-final value rather than corrupting later offsets.
            require(isLastArgument) { "Unsupported DLT structured value before the final argument" }
            val value = bytes.copyOfRange(offset, bytes.size)
            offset = bytes.size
            hex(value)
        }
        type and (TYPE_BOOL or TYPE_SINT or TYPE_UINT or TYPE_FLOA) != 0 -> {
            val width = scalarWidth(type)
            requireRange(bytes, offset, width, "scalar argument")
            val value = renderScalar(bytes, offset, width, type, msbf)
            offset += width
            value
        }
        else -> throw IllegalArgumentException("Unsupported DLT verbose type 0x%08X".format(Locale.ROOT, type))
    }
    val withAttribute = attribute?.let { (name, unit) ->
        "$name=$text" + unit.takeIf(String::isNotEmpty)?.let { " [$it]" }.orEmpty()
    } ?: text
    return ParsedDltArgument(withAttribute, offset)
}

private data class LengthPrefixedValue(val text: String, val raw: ByteArray, val nextOffset: Int)

private fun readLengthPrefixed(bytes: ByteArray, offset: Int, msbf: Boolean, label: String): LengthPrefixedValue {
    requireRange(bytes, offset, UINT16_SIZE, "$label length")
    val length = readUnsigned(bytes, offset, UINT16_SIZE, msbf).toInt()
    requireRange(bytes, offset + UINT16_SIZE, length, label)
    val raw = bytes.copyOfRange(offset + UINT16_SIZE, offset + UINT16_SIZE + length)
    return LengthPrefixedValue((decodeText(raw) ?: hex(raw)).trimEnd('\u0000'), raw, offset + UINT16_SIZE + length)
}

private fun scalarWidth(type: Int): Int = when (type and TYPE_LENGTH_MASK) {
    SCALAR_WIDTH_8 -> SCALAR_WIDTH_8
    SCALAR_WIDTH_16 -> SCALAR_WIDTH_16
    SCALAR_WIDTH_CODE_32 -> SCALAR_WIDTH_32
    SCALAR_WIDTH_32 -> SCALAR_WIDTH_64
    SCALAR_WIDTH_CODE_128 -> SCALAR_WIDTH_128
    else -> throw IllegalArgumentException("Unsupported DLT scalar width")
}

private fun renderScalar(bytes: ByteArray, offset: Int, width: Int, type: Int, msbf: Boolean): String = when {
    width > Long.SIZE_BYTES -> hex(bytes.copyOfRange(offset, offset + width))
    type and TYPE_BOOL != 0 -> if (readUnsigned(bytes, offset, width, msbf) == 0L) "false" else "true"
    type and TYPE_SINT != 0 -> readSigned(bytes, offset, width, msbf).toString()
    type and TYPE_UINT != 0 -> readUnsigned(bytes, offset, width, msbf).toULong().toString()
    type and TYPE_FLOA != 0 && width == SCALAR_WIDTH_32 -> Float.fromBits(readUnsigned(bytes, offset, width, msbf).toInt()).toString()
    type and TYPE_FLOA != 0 && width == SCALAR_WIDTH_64 -> Double.fromBits(readUnsigned(bytes, offset, width, msbf)).toString()
    else -> hex(bytes.copyOfRange(offset, offset + width))
}

private fun readUnsigned(bytes: ByteArray, offset: Int, width: Int, msbf: Boolean): Long {
    requireRange(bytes, offset, width, "value")
    var value = 0L
    for (index in 0 until width) {
        val sourceIndex = if (msbf) index else width - SCALAR_WIDTH_8 - index
        val byte = bytes[offset + sourceIndex].toLong() and BYTE_MASK.toLong()
        value = (value shl BITS_PER_BYTE) or byte
    }
    return value
}

private fun readSigned(bytes: ByteArray, offset: Int, width: Int, msbf: Boolean): Long {
    val value = readUnsigned(bytes, offset, width, msbf)
    val bits = width * BITS_PER_BYTE
    return if (bits == Long.SIZE_BITS || value and (1L shl (bits - SCALAR_WIDTH_8)) == 0L) value else value or (-1L shl bits)
}

private fun requireRange(bytes: ByteArray, offset: Int, length: Int, label: String) {
    require(offset >= 0 && length >= 0 && offset <= bytes.size - length) { "Truncated DLT $label" }
}

private fun hex(bytes: ByteArray): String = "0x" + bytes.joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and BYTE_MASK) }

private fun decodeText(bytes: ByteArray): String? {
    val clean = bytes.takeWhile { it != 0.toByte() }.toByteArray()
    if (clean.isEmpty()) return ""
    val decoded = runCatching { clean.toString(Charsets.UTF_8) }.getOrNull() ?: return null
    if ('\uFFFD' in decoded) return null
    val printable = decoded.count { it == '\t' || it == '\r' || it == '\n' || !it.isISOControl() }
    return decoded.takeIf { printable * TEXT_PERCENT_BASE >= decoded.length * TEXT_PRINTABLE_PERCENT }
}

private fun formatEpoch(seconds: Long, micros: Long): String = runCatching {
    Instant.ofEpochSecond(seconds, micros.coerceIn(0, MICROSECONDS_PER_SECOND - 1L) * NANOSECONDS_PER_MICROSECOND)
        .atZone(ZoneId.systemDefault()).toLocalTime().format(TIME_OUTPUT)
}.getOrDefault("")

private fun parseDltViewerLines(lines: Sequence<String>, startId: Int): List<LogEntry> {
    val iterator = lines.iterator()
    var first: String? = null
    while (iterator.hasNext() && first == null) first = iterator.next().trim().takeIf(String::isNotEmpty)
    require(first != null) { "The DLT Viewer export contains no rows" }
    val header = first
    if (header.contains(',') || header.contains(';')) {
        val delimiter = if (header.count { it == ';' } > header.count { it == ',' }) ';' else ','
        return parseCsvDlt(header, iterator.asSequence(), delimiter, startId)
    }
    var id = startId
    return sequence {
        yield(header)
        while (iterator.hasNext()) yield(iterator.next())
    }.mapNotNull { parseViewerLine(it.trim(), 0) }
        .map { it.copy(id = id++) }
        .toList()
}

private fun parseCsvDlt(header: String, rows: Sequence<String>, delimiter: Char, startId: Int): List<LogEntry> {
    val headers = splitCsv(header, delimiter).map { it.trim().lowercase(Locale.ROOT) }
    val hasDltColumns = headers.any { it in setOf("ecu", "ecu id", "ecuid") } &&
        headers.any { it.contains("app") || it == "apid" } && headers.any { it.contains("context") || it == "ctid" }
    require(hasDltColumns) { "Not a DLT Viewer CSV export" }
    // Header matching is alias-aware but boundary-sensitive. Broad substring matching makes the
    // `text` message alias match `contextid`, so a canonical DLT Viewer row could silently expose
    // its context as the payload. Exact aliases win; the fallback only accepts aliases as complete
    // underscore/space-delimited words.
    val idx = { names: Set<String> ->
        headers.indexOfFirst { it in names }.takeIf { it >= 0 } ?: headers.indexOfFirst { h ->
            val words = h.split(Regex("[^a-z0-9]+"), limit = Int.MAX_VALUE).filter(String::isNotBlank)
            names.any { it in words }
        }
    }
    val time = idx(setOf("time", "timestamp", "date", "datetime"))
    val ecu = idx(setOf("ecu", "ecuid", "ecu_id"))
    val app = idx(setOf("apid", "appid", "app", "application"))
    val ctx = idx(setOf("ctid", "ctid", "context", "contextid", "context_id"))
    val type = idx(setOf("type", "level", "severity"))
    val msg = idx(setOf("payload", "message", "msg", "text"))
    var id = startId
    return rows.mapNotNull { line ->
        val fields = splitCsv(line, delimiter)
        if (fields.size < headers.size) return@mapNotNull null
        val level = fields.getOrNull(type)?.let(::viewerLevel) ?: LogLevel.I
        val ecuId = fields.getOrNull(ecu).orEmpty().ifBlank { null }
        val appId = fields.getOrNull(app).orEmpty().ifBlank { null }
        val contextId = fields.getOrNull(ctx).orEmpty().ifBlank { null }
        LogEntry(id++, normalizeViewerTimestamp(fields.getOrNull(time).orEmpty()), level,
            listOfNotNull(ecuId, appId, contextId).joinToString("/").ifBlank { "DLT" },
            fields.getOrNull(msg).orEmpty(), dltEcuId = ecuId, dltAppId = appId, dltContextId = contextId,
            dltMessageType = fields.getOrNull(type).orEmpty().ifBlank { null }, dltTimestampSource = "viewer")
    }.toList()
}

// internal (not private): reused by DltDetection.kt's CSV header sniff, so header parsing during
// detection can never drift from header parsing during the actual parse below.
internal fun splitCsv(line: String, delimiter: Char): List<String> {
    val out = mutableListOf<String>(); val current = StringBuilder(); var quoted = false
    line.forEach { c ->
        when {
            c == '"' -> quoted = !quoted
            c == delimiter && !quoted -> { out += current.toString().trim(); current.clear() }
            else -> current.append(c)
        }
    }
    out += current.toString().trim()
    return out
}

private val VIEWER_LINE = Regex(
    """^\[?([^\s\]]+(?:[ T][^\s\]]+)?)\]?\s+(?:\[([^\]]*)\]\s*)?(?:\[([^\]]*)\]\s*)?(?:\[([^\]]*)\]\s*)?(?:\[([^\]]*)\]\s*)?(.+)$""",
)

private fun parseViewerLine(line: String, id: Int): LogEntry? {
    val match = VIEWER_LINE.matchEntire(line)
    if (match == null) return parsePlainViewerLine(line, id)
    val timestamp = match.groupValues[1]
    if (!timestamp.any(Char::isDigit) || !timestamp.contains(':')) return null
    val fields = match.groupValues.drop(2).filter(String::isNotBlank)
    if (fields.size < 2) return null
    val typeIndex = fields.indexOfFirst { viewerLevelOrNull(it) != null }
    if (typeIndex < 0) return null
    val type = fields[typeIndex]; val message = fields.drop(typeIndex + 1).joinToString(" ").ifBlank { return null }
    val pre = fields.take(typeIndex)
    val ecu = pre.getOrNull(0); val app = pre.getOrNull(1); val ctx = pre.getOrNull(2)
    return LogEntry(id, normalizeViewerTimestamp(timestamp), viewerLevel(type), listOfNotNull(ecu, app, ctx).joinToString("/").ifBlank { "DLT" }, message,
        dltEcuId = ecu, dltAppId = app, dltContextId = ctx, dltMessageType = type, dltTimestampSource = "viewer")
}

// DLT Viewer's text export has appeared in both bracketed and whitespace-column forms over its
// releases. The column form keeps the timestamp as either one ISO/time token or two date+time
// tokens, followed by ECU/APID/CTID/severity and the free-form payload.
private fun parsePlainViewerLine(line: String, id: Int): LogEntry? {
    val tokens = line.trim().split(Regex("\\s+"), limit = 7)
    val timestamp: String
    val offset: Int
    if (tokens.firstOrNull()?.contains(':') == true) {
        timestamp = tokens[0]; offset = 1
    } else if (tokens.getOrNull(1)?.contains(':') == true) {
        timestamp = "${tokens[0]} ${tokens[1]}"; offset = 2
    } else {
        return null
    }
    if (tokens.size - offset < 5) return null
    val ecu = tokens[offset].takeIf { it.isNotBlank() }
    val app = tokens[offset + 1].takeIf { it.isNotBlank() }
    val ctx = tokens[offset + 2].takeIf { it.isNotBlank() }
    val type = tokens[offset + 3]
    val message = tokens.drop(offset + 4).joinToString(" ").ifBlank { return null }
    if (viewerLevelOrNull(type) == null) return null
    return LogEntry(id, normalizeViewerTimestamp(timestamp), viewerLevel(type), listOfNotNull(ecu, app, ctx).joinToString("/").ifBlank { "DLT" }, message,
        dltEcuId = ecu, dltAppId = app, dltContextId = ctx, dltMessageType = type, dltTimestampSource = "viewer")
}

private fun viewerLevel(value: String): LogLevel = viewerLevelOrNull(value) ?: LogLevel.I

private fun viewerLevelOrNull(value: String): LogLevel? = when (value.trim().lowercase(Locale.ROOT)) {
    "v", "verbose" -> LogLevel.V
    "d", "debug" -> LogLevel.D
    "i", "info", "information" -> LogLevel.I
    "w", "warn", "warning" -> LogLevel.W
    "e", "error" -> LogLevel.E
    "a", "f", "fatal", "assert" -> LogLevel.A
    else -> null
}

private fun normalizeViewerTimestamp(value: String): String {
    val clean = value.trim().removePrefix("[").removeSuffix("]").replace(',', '.')
    val time = clean.substringAfterLast('T').substringAfterLast(' ').takeIf { it.contains(':') } ?: return ""
    val parts = time.split(':'); if (parts.size < 3) return ""
    val sec = parts[2].substringBefore('.').toIntOrNull() ?: return ""
    val frac = parts[2].substringAfter('.', "").filter(Char::isDigit).padEnd(3, '0').take(3)
    return "%02d:%02d:%02d.%s".format(Locale.ROOT, parts[0].toIntOrNull() ?: return "", parts[1].toIntOrNull() ?: return "", sec, frac)
}
