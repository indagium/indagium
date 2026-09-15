package com.indagium.utils

import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogLevel
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Result of syntax detection and parsing. The old List<LogEntry> parser API remains available. */
data class ParsedLog(val format: LogFormat, val entries: List<LogEntry>)

private const val DLT_STORAGE_HEADER_SIZE = 16
private const val DLT_MAX_FRAME_SIZE = 0xffff
private const val DLT_V1 = 1
private const val DLT_STANDARD_HEADER_SIZE = 4
private const val DLT_EXTENDED_HEADER_SIZE = 10
private const val DLT_ID_SIZE = 4
private const val DLT_STORAGE_HEADER_BODY_SIZE = DLT_STORAGE_HEADER_SIZE - DLT_STANDARD_HEADER_SIZE
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
    return when (val kind = classifyLogContent(sample, atEof, fileName)) {
        LogContentKind.DLT_STORAGE -> ParsedLog(LogFormat.DLT, parseDltBinary(input, storage = true, startId = startId))
        LogContentKind.DLT_RAW -> ParsedLog(LogFormat.DLT, parseDltBinary(input, storage = false, startId = startId))
        LogContentKind.DLT_UNSUPPORTED_V2 -> throw IllegalArgumentException("DLT protocol v2 is not supported")
        LogContentKind.DLT_VIEWER_CSV, LogContentKind.DLT_VIEWER_TEXT -> parseViewerTextLog(input, startId, kind)
        LogContentKind.TEXT, LogContentKind.OTHER -> parseTextLog(input, startId)
    }
}

// The caller of parseDltContent asserts the input is DLT, which is exactly what a ".dlt" name tells
// the classifier: an identifiable headerless v2 frame then reports the v2 error instead of OTHER.
private const val ASSERTED_DLT_FILE_NAME = "input.dlt"

/** Strict binary-DLT entry point: same classifier as [parseLogContent], but never falls back to text. */
fun parseDltContent(stream: InputStream, startId: Int = 1): ParsedLog {
    val input = if (stream.markSupported()) stream else BufferedInputStream(stream, PARSE_LOG_CONTENT_BUFFER_BYTES)
    input.mark(CONTENT_SNIFF_BYTES + 1)
    val sample = input.readNBytes(CONTENT_SNIFF_BYTES)
    input.reset()
    val storage = when (classifyLogContent(sample, sample.size < CONTENT_SNIFF_BYTES, ASSERTED_DLT_FILE_NAME)) {
        LogContentKind.DLT_STORAGE -> true
        LogContentKind.DLT_RAW -> false
        LogContentKind.DLT_UNSUPPORTED_V2 -> throw IllegalArgumentException("DLT protocol v2 is not supported")
        else -> throw IllegalArgumentException("The input is not a valid DLT stream")
    }
    return ParsedLog(LogFormat.DLT, parseDltBinary(input, storage, startId))
}

// The DLT-viewer-vs-logcat decision is made once, by classifyLogContent, before either of these
// runs — parseLogContent dispatches to the right one directly instead of re-detecting here.
private fun parseTextLog(input: InputStream, startId: Int): ParsedLog =
    openLogTextReader(input).use { reader -> ParsedLog(LogFormat.LOGCAT, parseLogcatLines(reader.lineSequence(), startId)) }

private fun parseViewerTextLog(input: InputStream, startId: Int, kind: LogContentKind): ParsedLog =
    openLogTextReader(input).use { reader ->
        val lines = reader.lineSequence()
        val entries = if (kind == LogContentKind.DLT_VIEWER_CSV) {
            parseDltViewerCsv(lines, startId)
        } else {
            parseDltViewerAsciiLines(lines, startId)
        }
        ParsedLog(LogFormat.DLT, entries)
    }

// Reads until `target` is full or the stream ends; returns how many bytes actually landed (may be
// less than target.size on EOF — callers decide whether that's a clean end or a truncation).
private fun readAvailable(input: InputStream, target: ByteArray, offset: Int = 0): Int {
    var pos = offset
    while (pos < target.size) {
        val n = input.read(target, pos, target.size - pos)
        if (n < 0) break
        if (n == 0) continue
        pos += n
    }
    return pos - offset
}

private fun minimumFrameLength(htyp: Int): Int =
    DLT_STANDARD_HEADER_SIZE +
        (if (htyp and DLT_HTYP_WEID != 0) DLT_ID_SIZE else 0) +
        (if (htyp and DLT_HTYP_WSID != 0) UINT32_SIZE else 0) +
        (if (htyp and DLT_HTYP_WTMS != 0) UINT32_SIZE else 0) +
        (if (htyp and DLT_HTYP_UEH != 0) DLT_EXTENDED_HEADER_SIZE else 0)

private fun isStorageMagic(window: ByteArray): Boolean =
    window[0] == DLT_STORAGE_MAGIC[0] && window[1] == DLT_STORAGE_MAGIC[1] &&
        window[2] == DLT_STORAGE_MAGIC[2] && window[3] == DLT_STORAGE_MAGIC[3]

// Scans forward for the next "DLT\x01" storage magic, seeded with 0..3 bytes already pulled out
// of `input` (a bulk 4-byte probe that didn't match as a whole still needs its trailing bytes
// re-checked byte-by-byte — this resumes from them instead of re-reading the stream). Returns the
// byte offset (per input.count) where the magic begins, or null if the stream ends first. Leaves
// the stream positioned exactly after the matched magic on success.
private fun scanForStorageMagic(input: CountingInputStream, seed: ByteArray): Long? {
    val window = ByteArray(4)
    var filled = seed.size.coerceAtMost(4)
    seed.copyInto(window, 0, 0, filled)
    if (filled == 4 && isStorageMagic(window)) return input.count - 4
    while (true) {
        val b = input.read()
        if (b < 0) return null
        if (filled < 4) {
            window[filled] = b.toByte()
            filled++
        } else {
            window[0] = window[1]; window[1] = window[2]; window[2] = window[3]; window[3] = b.toByte()
        }
        if (filled == 4 && isStorageMagic(window)) return input.count - 4
    }
}

// Reads (and discards) the rest of the stream, purely to make the CountingInputStream's running
// count reflect "we consumed everything" for a raw-mode marker that reports the byte count of
// unparseable trailing data.
private fun drainRemaining(input: InputStream) {
    val buffer = ByteArray(PARSE_LOG_CONTENT_BUFFER_BYTES)
    while (input.read(buffer) >= 0) {
        // discard
    }
}

private data class DltDecodeResult(val entries: List<LogEntry>, val frameCount: Int)

/**
 * Tolerant DLT v1 decoder shared by [parseLogContent] and [parseDltContent]. Every row that can be
 * recovered is kept; corruption becomes a marker row (`LogEntry(id, "", LogLevel.W, "RAW", msg)`,
 * matching logcat's RAW convention) instead of aborting the whole parse. [storage] selects between
 * the 16-byte-storage-header-prefixed record format and the bare frame stream; storage mode can
 * resync past corrupt data by scanning for the next storage magic, raw mode cannot (there is no
 * landmark to resync to) and simply stops after reporting how much trailing data it couldn't use.
 * Throws only when zero frames were decoded — a stream that never yields anything usable is still
 * treated as invalid input, matching the non-tolerant behavior for non-DLT content.
 */
private fun parseDltBinary(input: InputStream, storage: Boolean, startId: Int): List<LogEntry> {
    val counting = CountingInputStream(input)
    val tagCache = HashMap<String, String>()
    val result = if (storage) decodeStorageStream(counting, startId, tagCache) else decodeRawStream(counting, startId, tagCache)
    require(result.frameCount > 0) { "The DLT stream contains no complete frames" }
    return result.entries
}

private fun decodeStorageStream(input: CountingInputStream, startId: Int, tagCache: HashMap<String, String>): DltDecodeResult {
    val entries = ArrayList<LogEntry>()
    var id = startId
    var frameCount = 0

    fun intern(value: String) = tagCache.getOrPut(value) { value }

    fun mark(message: String) { entries += LogEntry(id++, "", LogLevel.W, "RAW", message) }

    while (true) {
        val scanStart = input.count
        val probe = ByteArray(4)
        val probeRead = readAvailable(input, probe)
        if (probeRead == 0) return DltDecodeResult(entries, frameCount) // clean end between records
        if (probeRead < 4) {
            mark("DLT: truncated frame at offset $scanStart")
            return DltDecodeResult(entries, frameCount)
        }
        var magicStart = scanStart
        if (!isStorageMagic(probe)) {
            val found = scanForStorageMagic(input, probe.copyOfRange(1, 4))
            if (found == null) {
                mark("DLT: skipped ${input.count - scanStart} bytes of invalid data at offset $scanStart")
                return DltDecodeResult(entries, frameCount)
            }
            magicStart = found
            mark("DLT: skipped ${magicStart - scanStart} bytes of invalid data at offset $scanStart")
        }

        val header12 = ByteArray(DLT_STORAGE_HEADER_BODY_SIZE)
        val headerRead = readAvailable(input, header12)
        if (headerRead < header12.size) {
            mark("DLT: truncated frame at offset $magicStart")
            return DltDecodeResult(entries, frameCount)
        }
        val seconds = u32(header12, 0, littleEndian = true)
        val micros = u32(header12, UINT32_SIZE, littleEndian = true)
        val storageEcu = intern(asciiId(header12, UINT32_SIZE * 2, DLT_ID_SIZE))

        val frameHeaderStart = input.count
        val stdHeader = ByteArray(DLT_STANDARD_HEADER_SIZE)
        val stdRead = readAvailable(input, stdHeader)
        if (stdRead < DLT_STANDARD_HEADER_SIZE) {
            mark("DLT: truncated frame at offset $frameHeaderStart")
            return DltDecodeResult(entries, frameCount)
        }
        val htyp = stdHeader[DLT_HEADER_TYPE_OFFSET].toInt() and BYTE_MASK
        val version = htyp ushr DLT_VERSION_SHIFT
        val length = unsigned16BigEndian(stdHeader[DLT_LENGTH_MSB_OFFSET], stdHeader[DLT_LENGTH_LSB_OFFSET])
        if (version != DLT_V1 || length !in minimumFrameLength(htyp)..DLT_MAX_FRAME_SIZE) {
            // Invalid frame header: report exactly what we've consumed as garbage and resync —
            // the next loop iteration scans forward for the next storage magic from here.
            mark("DLT: skipped ${input.count - frameHeaderStart} bytes of invalid data at offset $frameHeaderStart")
            continue
        }
        val body = ByteArray(length - DLT_STANDARD_HEADER_SIZE)
        val bodyRead = readAvailable(input, body)
        if (bodyRead < body.size) {
            mark("DLT: truncated frame at offset $frameHeaderStart")
            return DltDecodeResult(entries, frameCount)
        }
        val entry = decodeFrameBody(htyp, body, storageEcu, seconds, micros, ::intern)
        entries += entry.copy(id = id++)
        frameCount++
    }
}

private fun decodeRawStream(input: CountingInputStream, startId: Int, tagCache: HashMap<String, String>): DltDecodeResult {
    val entries = ArrayList<LogEntry>()
    var id = startId
    var frameCount = 0

    fun intern(value: String) = tagCache.getOrPut(value) { value }

    fun mark(message: String) { entries += LogEntry(id++, "", LogLevel.W, "RAW", message) }

    while (true) {
        val frameHeaderStart = input.count
        val stdHeader = ByteArray(DLT_STANDARD_HEADER_SIZE)
        val stdRead = readAvailable(input, stdHeader)
        if (stdRead == 0) return DltDecodeResult(entries, frameCount) // clean end
        if (stdRead < DLT_STANDARD_HEADER_SIZE) {
            mark("DLT: truncated frame at offset $frameHeaderStart")
            return DltDecodeResult(entries, frameCount)
        }
        val htyp = stdHeader[DLT_HEADER_TYPE_OFFSET].toInt() and BYTE_MASK
        val version = htyp ushr DLT_VERSION_SHIFT
        val length = unsigned16BigEndian(stdHeader[DLT_LENGTH_MSB_OFFSET], stdHeader[DLT_LENGTH_LSB_OFFSET])
        if (version != DLT_V1 || length !in minimumFrameLength(htyp)..DLT_MAX_FRAME_SIZE) {
            // Raw mode has no landmark to resync to (no storage header to hunt for), so we can only
            // report how much unusable data trails and stop.
            drainRemaining(input)
            mark("DLT: skipped ${input.count - frameHeaderStart} bytes of invalid data at offset $frameHeaderStart")
            return DltDecodeResult(entries, frameCount)
        }
        val body = ByteArray(length - DLT_STANDARD_HEADER_SIZE)
        val bodyRead = readAvailable(input, body)
        if (bodyRead < body.size) {
            mark("DLT: truncated frame at offset $frameHeaderStart")
            return DltDecodeResult(entries, frameCount)
        }
        val entry = decodeFrameBody(htyp, body, null, null, null, ::intern)
        entries += entry.copy(id = id++)
        frameCount++
    }
}

// Parses everything after the standard header once its LEN-declared body has been read in full —
// no I/O here, so this can never see a truncated field: minimumFrameLength already guaranteed the
// optional fields and extended header fit inside `body`, and payload decoding never throws.
private fun decodeFrameBody(
    htyp: Int,
    body: ByteArray,
    storageEcu: String?,
    storageSeconds: Long?,
    storageMicros: Long?,
    intern: (String) -> String,
): LogEntry {
    val msbf = htyp and DLT_HTYP_MSBF != 0
    var offset = 0
    var ecu = storageEcu
    var sessionId: Long? = null
    var relativeTimestamp: Long? = null
    if (htyp and DLT_HTYP_WEID != 0) {
        ecu = intern(asciiId(body, offset, DLT_ID_SIZE)); offset += DLT_ID_SIZE
    }
    if (htyp and DLT_HTYP_WSID != 0) {
        sessionId = u32(body, offset, littleEndian = false); offset += UINT32_SIZE
    }
    if (htyp and DLT_HTYP_WTMS != 0) {
        relativeTimestamp = u32(body, offset, littleEndian = false); offset += UINT32_SIZE
    }
    var msin: Int? = null
    var noar = 0
    var apid: String? = null
    var ctid: String? = null
    if (htyp and DLT_HTYP_UEH != 0) {
        msin = body[offset].toInt() and BYTE_MASK
        noar = body[offset + 1].toInt() and BYTE_MASK
        apid = intern(asciiId(body, offset + UINT16_SIZE, DLT_ID_SIZE))
        ctid = intern(asciiId(body, offset + UINT16_SIZE + DLT_ID_SIZE, DLT_ID_SIZE))
        offset += DLT_EXTENDED_HEADER_SIZE
    }
    val payload = body.copyOfRange(offset, body.size)
    val severity = msin?.let { dltSeverity(it) } ?: LogLevel.I
    val timestamp = when {
        storageSeconds != null -> formatEpoch(storageSeconds, storageMicros ?: 0L)
        relativeTimestamp != null -> formatRelativeTimestamp(relativeTimestamp)
        else -> ""
    }
    val dltTs = storageSeconds ?: relativeTimestamp
    val source = when {
        storageSeconds != null -> "storage"
        relativeTimestamp != null -> "relative"
        else -> null
    }
    val tag = intern(
        listOfNotNull(ecu?.takeIf { it.isNotBlank() }, apid?.takeIf { it.isNotBlank() }, ctid?.takeIf { it.isNotBlank() })
            .joinToString("/").ifBlank { "DLT" },
    )
    return LogEntry(
        id = 0, ts = timestamp, level = severity, tag = tag,
        msg = decodeDltPayload(payload, noar, msin, msbf),
        pid = sessionId?.toInt() ?: 0,
        dltEcuId = ecu?.takeIf { it.isNotBlank() }, dltAppId = apid?.takeIf { it.isNotBlank() },
        dltContextId = ctid?.takeIf { it.isNotBlank() }, dltMessageType = msin?.let(::dltMessageTypeName),
        dltTimestamp = dltTs, dltTimestampSource = source,
    )
}

private const val TMSP_UNITS_PER_MILLISECOND = 10L
private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_HOUR = 3_600_000L
private const val HOURS_PER_DAY = 24L

// TMSP is DLT's headerless-frame uptime counter, in units of 0.1 ms. There's no storage header to
// anchor it to a date, so — like logcat's bare-time format — it renders as a wall-clock-shaped
// time of day, wrapping the way any elapsed-time-since-boot counter would past 24h.
private fun formatRelativeTimestamp(units: Long): String {
    val totalMillis = units / TMSP_UNITS_PER_MILLISECOND
    val hours = (totalMillis / MILLIS_PER_HOUR) % HOURS_PER_DAY
    val minutes = (totalMillis / MILLIS_PER_MINUTE) % (MILLIS_PER_HOUR / MILLIS_PER_MINUTE)
    val seconds = (totalMillis / MILLIS_PER_SECOND) % (MILLIS_PER_MINUTE / MILLIS_PER_SECOND)
    val millis = totalMillis % MILLIS_PER_SECOND
    return "%02d:%02d:%02d.%03d".format(Locale.ROOT, hours, minutes, seconds, millis)
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

// DLT v1 type info: low nibble is the standard scalar width (TYLE), then type flags begin at bit 4.
private const val TYPE_BOOL = 0x00000010
private const val TYPE_SINT = 0x00000020
private const val TYPE_UINT = 0x00000040
private const val TYPE_FLOA = 0x00000080
private const val TYPE_ARAY = 0x00000100
private const val TYPE_STRG = 0x00000200
private const val TYPE_RAWD = 0x00000400
private const val TYPE_VARI = 0x00000800
private const val TYPE_FIXP = 0x00001000
private const val TYPE_TRAI = 0x00002000
private const val TYPE_STRU = 0x00004000
private const val TYPE_LENGTH_MASK = 0x0f

// TYLE codes (type-info low nibble) and their scalar byte widths.
private const val TYLE_8BIT = 1
private const val TYLE_16BIT = 2
private const val TYLE_32BIT = 3
private const val TYLE_64BIT = 4
private const val TYLE_128BIT = 5
private const val WIDTH_8BIT = 1
private const val WIDTH_16BIT = 2
private const val WIDTH_32BIT = 4
private const val WIDTH_64BIT = 8
private const val WIDTH_128BIT = 16

/**
 * Decodes a verbose-mode payload into its `name=value [unit]`-style rendered text. Never throws:
 * a decode failure renders whatever arguments were successfully decoded so far, plus the
 * remaining undecoded bytes as `[undecodable payload 0x…]`. A non-verbose message (no VERB bit,
 * or headerless) is rendered as its raw message id plus best-effort text/hex of the rest.
 */
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
    for (i in 0 until argumentCount) {
        val parseStart = offset
        val parsed = try {
            parseVerboseArgument(payload, offset, msbf)
        } catch (_: IllegalArgumentException) {
            val tail = hex(payload.copyOfRange(parseStart, payload.size))
            return (rendered + "[undecodable payload $tail]").joinToString(" ")
        }
        rendered += parsed.text
        offset = parsed.nextOffset
        if (parsed.terminal) break // a non-final ARAY/STRU already consumed the rest as hex
    }
    if (offset < payload.size) {
        val tail = hex(payload.copyOfRange(offset, payload.size))
        return (rendered + "[undecodable payload $tail]").joinToString(" ")
    }
    return rendered.joinToString(" ")
}

private data class ParsedDltArgument(val text: String, val nextOffset: Int, val terminal: Boolean = false)

/**
 * One verbose-mode argument, per dlt-daemon's `dlt_user_log_write_generic_attr` wire layout (not
 * DLT Viewer's, which differs): numeric SINT/UINT/FLOA with VARI write both lengths before both
 * strings (`nameLen, unitLen, name, unit`), then FIXP's quantization/offset, then the value; BOOL
 * with VARI has no unit (`nameLen, name, value`); STRG/RAWD write their data length first, then
 * (if VARI) the name length/name, then the data; TRAI is STRG's layout without VARI ever applying.
 */
@Suppress("CyclomaticComplexMethod")
private fun parseVerboseArgument(bytes: ByteArray, start: Int, msbf: Boolean): ParsedDltArgument {
    requireRange(bytes, start, UINT32_SIZE, "type info")
    val type = readUnsigned(bytes, start, UINT32_SIZE, msbf).toInt()
    var offset = start + UINT32_SIZE
    val hasVari = type and TYPE_VARI != 0
    val hasFixp = type and TYPE_FIXP != 0
    return when {
        type and (TYPE_ARAY or TYPE_STRU) != 0 -> {
            // ARAY/STRU is a modifier combined with an element-type tag (e.g. "array of UINT8" is
            // TYPE_ARAY|TYPE_UINT|TYLE_8BIT), so it must be checked before any element-type branch
            // below or it would be misread as a plain scalar/string of that element type. A V1
            // array/structure isn't a self-contained byte-length-delimited value either way, so a
            // generic parser can't locate its own end: render every remaining payload byte as
            // deterministic hex and stop decoding further arguments — never throw, even when this
            // isn't the last one the frame claims (NOAR then simply goes unfulfilled).
            val raw = bytes.copyOfRange(offset, bytes.size)
            ParsedDltArgument(hex(raw), bytes.size, terminal = true)
        }
        type and TYPE_BOOL != 0 -> {
            var name: String? = null
            if (hasVari) {
                val n = readLengthPrefixed(bytes, offset, msbf, "attribute name"); offset = n.nextOffset; name = n.text
            }
            requireRange(bytes, offset, WIDTH_8BIT, "bool value")
            val value = readUnsigned(bytes, offset, WIDTH_8BIT, msbf) != 0L
            offset += WIDTH_8BIT
            val text = if (value) "true" else "false"
            ParsedDltArgument(name?.let { "$it=$text" } ?: text, offset)
        }
        type and (TYPE_SINT or TYPE_UINT or TYPE_FLOA) != 0 -> {
            var name: String? = null
            var unit: String? = null
            if (hasVari) {
                // Numeric VARI writes BOTH lengths before EITHER string (nameLen, unitLen, name,
                // unit) — unlike BOOL/STRG/RAWD's single length-then-its-own-data field, so this
                // can't reuse readLengthPrefixed as-is.
                val nameLen = readLength16(bytes, offset, msbf, "attribute name length"); offset = nameLen.nextOffset
                val unitLen = readLength16(bytes, offset, msbf, "attribute unit length"); offset = unitLen.nextOffset
                requireRange(bytes, offset, nameLen.value, "attribute name")
                val nameBytes = bytes.copyOfRange(offset, offset + nameLen.value); offset += nameLen.value
                name = (decodeText(nameBytes) ?: hex(nameBytes)).trimEnd('\u0000')
                requireRange(bytes, offset, unitLen.value, "attribute unit")
                val unitBytes = bytes.copyOfRange(offset, offset + unitLen.value); offset += unitLen.value
                unit = (decodeText(unitBytes) ?: hex(unitBytes)).trimEnd('\u0000')
            }
            var quantization: Float? = null
            var fixpOffset: Long? = null
            if (hasFixp) {
                requireRange(bytes, offset, UINT32_SIZE, "fixed-point quantization")
                quantization = Float.fromBits(readUnsigned(bytes, offset, UINT32_SIZE, msbf).toInt())
                offset += UINT32_SIZE
                val offsetWidth = when (type and TYPE_LENGTH_MASK) {
                    TYLE_8BIT, TYLE_16BIT, TYLE_32BIT -> WIDTH_32BIT
                    TYLE_64BIT -> WIDTH_64BIT
                    else -> throw IllegalArgumentException("Unsupported DLT FIXP scalar width")
                }
                requireRange(bytes, offset, offsetWidth, "fixed-point offset")
                fixpOffset = readSigned(bytes, offset, offsetWidth, msbf)
                offset += offsetWidth
            }
            val width = scalarWidth(type)
            requireRange(bytes, offset, width, "scalar argument")
            val text = if (quantization != null) {
                val raw = if (type and TYPE_SINT != 0) readSigned(bytes, offset, width, msbf) else readUnsigned(bytes, offset, width, msbf)
                (raw.toDouble() * quantization.toDouble() + (fixpOffset ?: 0L).toDouble()).toString()
            } else {
                renderScalar(bytes, offset, width, type, msbf)
            }
            offset += width
            val withAttribute = name?.let { n -> "$n=$text" + unit?.takeIf(String::isNotEmpty)?.let { " [$it]" }.orEmpty() } ?: text
            ParsedDltArgument(withAttribute, offset)
        }
        type and TYPE_STRG != 0 -> {
            val length = readLength16(bytes, offset, msbf, "string length"); offset = length.nextOffset
            var name: String? = null
            if (hasVari) {
                val n = readLengthPrefixed(bytes, offset, msbf, "attribute name"); offset = n.nextOffset; name = n.text
            }
            requireRange(bytes, offset, length.value, "string")
            val raw = bytes.copyOfRange(offset, offset + length.value); offset += length.value
            val text = decodeText(raw) ?: hex(raw)
            ParsedDltArgument(name?.let { "$it=$text" } ?: text, offset)
        }
        type and TYPE_RAWD != 0 -> {
            val length = readLength16(bytes, offset, msbf, "raw data length"); offset = length.nextOffset
            var name: String? = null
            if (hasVari) {
                val n = readLengthPrefixed(bytes, offset, msbf, "attribute name"); offset = n.nextOffset; name = n.text
            }
            requireRange(bytes, offset, length.value, "raw data")
            val raw = bytes.copyOfRange(offset, offset + length.value); offset += length.value
            val text = hex(raw)
            ParsedDltArgument(name?.let { "$it=$text" } ?: text, offset)
        }
        type and TYPE_TRAI != 0 -> {
            val length = readLength16(bytes, offset, msbf, "trace data length"); offset = length.nextOffset
            requireRange(bytes, offset, length.value, "trace data")
            val raw = bytes.copyOfRange(offset, offset + length.value); offset += length.value
            ParsedDltArgument(hex(raw), offset)
        }
        else -> throw IllegalArgumentException("Unsupported DLT verbose type 0x%08X".format(Locale.ROOT, type))
    }
}

private data class LengthField(val value: Int, val nextOffset: Int)

private fun readLength16(bytes: ByteArray, offset: Int, msbf: Boolean, label: String): LengthField {
    requireRange(bytes, offset, UINT16_SIZE, "$label length")
    val length = readUnsigned(bytes, offset, UINT16_SIZE, msbf).toInt()
    return LengthField(length, offset + UINT16_SIZE)
}

private data class LengthPrefixedValue(val text: String, val nextOffset: Int)

// Used only for the "length, then immediately the bytes" shape shared by VARI's name/unit fields —
// STRG/RAWD/TRAI data has a different field order (see parseVerboseArgument) and reads its length
// via readLength16 directly.
private fun readLengthPrefixed(bytes: ByteArray, offset: Int, msbf: Boolean, label: String): LengthPrefixedValue {
    val length = readLength16(bytes, offset, msbf, label)
    requireRange(bytes, length.nextOffset, length.value, label)
    val raw = bytes.copyOfRange(length.nextOffset, length.nextOffset + length.value)
    val text = (decodeText(raw) ?: hex(raw)).trimEnd('\u0000')
    return LengthPrefixedValue(text, length.nextOffset + length.value)
}

private fun scalarWidth(type: Int): Int = when (type and TYPE_LENGTH_MASK) {
    TYLE_8BIT -> WIDTH_8BIT
    TYLE_16BIT -> WIDTH_16BIT
    TYLE_32BIT -> WIDTH_32BIT
    TYLE_64BIT -> WIDTH_64BIT
    TYLE_128BIT -> WIDTH_128BIT
    else -> throw IllegalArgumentException("Unsupported DLT scalar width")
}

private fun renderScalar(bytes: ByteArray, offset: Int, width: Int, type: Int, msbf: Boolean): String = when {
    width > Long.SIZE_BYTES -> hex(bytes.copyOfRange(offset, offset + width))
    type and TYPE_SINT != 0 -> readSigned(bytes, offset, width, msbf).toString()
    type and TYPE_UINT != 0 -> readUnsigned(bytes, offset, width, msbf).toULong().toString()
    type and TYPE_FLOA != 0 && width == WIDTH_32BIT -> Float.fromBits(readUnsigned(bytes, offset, width, msbf).toInt()).toString()
    type and TYPE_FLOA != 0 && width == WIDTH_64BIT -> Double.fromBits(readUnsigned(bytes, offset, width, msbf)).toString()
    else -> hex(bytes.copyOfRange(offset, offset + width))
}

private fun readUnsigned(bytes: ByteArray, offset: Int, width: Int, msbf: Boolean): Long {
    requireRange(bytes, offset, width, "value")
    var value = 0L
    for (index in 0 until width) {
        val sourceIndex = if (msbf) index else width - SCALAR_INDEX_STEP - index
        val byte = bytes[offset + sourceIndex].toLong() and BYTE_MASK.toLong()
        value = (value shl BITS_PER_BYTE) or byte
    }
    return value
}

private const val SCALAR_INDEX_STEP = 1

private fun readSigned(bytes: ByteArray, offset: Int, width: Int, msbf: Boolean): Long {
    val value = readUnsigned(bytes, offset, width, msbf)
    val bits = width * BITS_PER_BYTE
    return if (bits == Long.SIZE_BITS || value and (1L shl (bits - SCALAR_INDEX_STEP)) == 0L) value else value or (-1L shl bits)
}

private fun requireRange(bytes: ByteArray, offset: Int, length: Int, label: String) {
    require(offset >= 0 && length >= 0 && offset <= bytes.size - length) { "Truncated DLT $label" }
}

private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()

private fun hex(bytes: ByteArray): String {
    val chars = CharArray(bytes.size * 2 + 2)
    chars[0] = '0'; chars[1] = 'x'
    for (i in bytes.indices) {
        val v = bytes[i].toInt() and BYTE_MASK
        val base = 2 + i * 2
        chars[base] = HEX_DIGITS[v ushr 4]
        chars[base + 1] = HEX_DIGITS[v and 0x0F]
    }
    return String(chars)
}

private fun decodeText(bytes: ByteArray): String? {
    var nul = 0
    while (nul < bytes.size && bytes[nul] != 0.toByte()) nul++
    if (nul == 0) return ""
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val decoded = try {
        decoder.decode(ByteBuffer.wrap(bytes, 0, nul)).toString()
    } catch (_: CharacterCodingException) {
        return null
    }
    val printable = decoded.count { it == '\t' || it == '\r' || it == '\n' || !it.isISOControl() }
    return decoded.takeIf { printable * TEXT_PERCENT_BASE >= decoded.length * TEXT_PRINTABLE_PERCENT }
}

private fun formatEpoch(seconds: Long, micros: Long): String = runCatching {
    Instant.ofEpochSecond(seconds, micros.coerceIn(0, MICROSECONDS_PER_SECOND - 1L) * NANOSECONDS_PER_MICROSECOND)
        .atZone(ZoneId.systemDefault()).toLocalTime().format(TIME_OUTPUT)
}.getOrDefault("")

// ── DLT Viewer text/CSV export parsing ────────────────────────────────────────────────────────
//
// Both forms share one column resolver (DltDetection.kt's resolveDltCsvColumns/resolveDltCsvHeader)
// and, for the ASCII form, one line parser (parseDltViewerAsciiLine below) with the classifier
// (isDltViewerAsciiLine) — so detection and parsing can never disagree about what a line means.
// Neither path ever drops a line: anything that isn't a recognised header/row/line becomes a RAW
// row instead, matching parseLogcatLines' convention (LogEntry(id, "", LogLevel.I, "RAW", line)).

// DLT Viewer ASCII export: single-space separated, an optional leading numeric index, then
// <date> <time> <uptime> <count> <ecu> <apid> <ctid> <sessionId> <type> <subtype> <mode> <#args>
// <payload...>. Only the anchor fields (date/time/uptime/type/mode) are validated strictly; the
// short id/count/subtype/payload fields are legitimately empty for some message types.
private val ASCII_DATE = Regex("""\d{4}/\d{2}/\d{2}""")
private val ASCII_TIME = Regex("""\d{2}:\d{2}:\d{2}\.\d{6}""")
private val ASCII_UPTIME = Regex("""\d+\.\d{4}""")
private val ASCII_TYPES = setOf("log", "app_trace", "nw_trace", "control")
private val ASCII_MODES = setOf("verbose", "non-verbose")

// date + time + uptime + count + ecu + apid + ctid + sessionId + type + subtype + mode + #args —
// the 12 fixed-position fields before the free-form payload (which may be entirely absent).
private const val ASCII_FIXED_FIELD_COUNT = 12

/**
 * Parses one DLT Viewer ASCII-export line, or returns null when [line] isn't one — the single
 * source of truth reused both here (real parsing, via [parseDltViewerAsciiLines]) and by
 * [isDltViewerAsciiLine] (the classifier's strict per-line check), so the two can never disagree.
 * [intern] lets the real parse path share one tag cache; detection passes the identity function
 * since it only cares whether the result is non-null.
 */
internal fun parseDltViewerAsciiLine(line: String, id: Int, intern: (String) -> String = { it }): LogEntry? {
    val tokens = line.split(' ')
    val idx = when {
        tokens.isNotEmpty() && ASCII_DATE.matches(tokens[0]) -> 0
        tokens.size > 1 && tokens[0].isNotEmpty() && tokens[0].all(Char::isDigit) && ASCII_DATE.matches(tokens[1]) -> 1
        else -> return null
    }
    if (tokens.size < idx + ASCII_FIXED_FIELD_COUNT) return null
    val time = tokens[idx + 1]
    val uptime = tokens[idx + 2]
    if (!ASCII_TIME.matches(time) || !ASCII_UPTIME.matches(uptime)) return null
    val ecu = tokens[idx + 4]
    val apid = tokens[idx + 5]
    val ctid = tokens[idx + 6]
    val session = tokens[idx + 7]
    val type = tokens[idx + 8]
    val subtype = tokens[idx + 9]
    val mode = tokens[idx + 10]
    if (type !in ASCII_TYPES || mode !in ASCII_MODES) return null
    val payloadStart = idx + ASCII_FIXED_FIELD_COUNT
    val payload = if (tokens.size > payloadStart) tokens.subList(payloadStart, tokens.size).joinToString(" ") else ""
    return buildDltViewerEntry(
        id = id, ts = normalizeViewerTimestamp(time), dltTimestamp = parseViewerUptime(uptime),
        ecu = ecu, app = apid, ctx = ctid, sessionId = session, type = type,
        level = resolveViewerLevel(hasSubtypeColumn = true, type = type, subtype = subtype),
        payload = payload, intern = intern,
    )
}

private fun parseDltViewerAsciiLines(lines: Sequence<String>, startId: Int): List<LogEntry> {
    val tagCache = HashMap<String, String>()

    fun intern(value: String) = tagCache.getOrPut(value) { value }
    var id = startId
    return lines.mapNotNull { raw ->
        if (raw.isBlank()) return@mapNotNull null
        val entry = parseDltViewerAsciiLine(raw.trim(), id, ::intern) ?: LogEntry(id, "", LogLevel.I, "RAW", raw.trimEnd())
        id++
        entry
    }.toList()
}

// Joins physical lines back into one logical CSV row whenever a quoted field's newline was split by
// the line reader — RFC-4180 quoted fields may embed literal newlines, and quote parity (doubled ""
// escapes contribute an even count) tells us whether we're still inside one. A stray unbalanced
// quote in a non-conforming export would otherwise glue the rest of the file into one row, so the
// join is capped: past the cap the buffered lines are emitted unjoined (each becomes a row or RAW).
private const val MAX_CSV_CONTINUATION_LINES = 64

private fun joinQuotedCsvLines(lines: Iterator<String>): Sequence<String> = sequence {
    val pending = ArrayDeque<String>()
    while (pending.isNotEmpty() || lines.hasNext()) {
        val physical = ArrayList<String>()
        physical += pending.removeFirstOrNull() ?: lines.next()
        var quotes = physical[0].count { it == '"' }
        while (quotes % 2 != 0 && physical.size <= MAX_CSV_CONTINUATION_LINES) {
            val next = pending.removeFirstOrNull() ?: (if (lines.hasNext()) lines.next() else break)
            physical += next
            quotes += next.count { it == '"' }
        }
        if (quotes % 2 != 0 && physical.size > MAX_CSV_CONTINUATION_LINES) {
            yield(physical[0])
            for (i in physical.lastIndex downTo 1) pending.addFirst(physical[i])
        } else {
            yield(physical.joinToString("\n"))
        }
    }
}

private fun parseDltViewerCsv(lines: Sequence<String>, startId: Int): List<LogEntry> {
    val logical = joinQuotedCsvLines(lines.iterator())
    val tagCache = HashMap<String, String>()

    fun intern(value: String) = tagCache.getOrPut(value) { value }
    var id = startId
    val entries = ArrayList<LogEntry>()

    val iterator = logical.iterator()
    var delimiter = ','
    var columns: DltCsvColumns? = null
    var headerFieldCount = 0
    while (iterator.hasNext() && columns == null) {
        val line = iterator.next()
        if (line.isBlank()) continue
        val resolved = resolveDltCsvHeader(line)
        if (resolved == null) {
            entries += LogEntry(id++, "", LogLevel.I, "RAW", line)
            continue
        }
        delimiter = resolved.first
        columns = resolved.second
        headerFieldCount = splitCsv(line, delimiter).size
    }
    val cols = columns ?: return entries // no header found anywhere in the file: keep whatever RAW rows we saw

    while (iterator.hasNext()) {
        val line = iterator.next()
        if (line.isBlank()) continue
        val fields = splitCsv(line, delimiter)
        if (fields.size < headerFieldCount) {
            entries += LogEntry(id++, "", LogLevel.I, "RAW", line)
            continue
        }
        val type = fields.getOrNull(cols.type)
        val subtype = fields.getOrNull(cols.subtype)
        val uptime = fields.getOrNull(cols.uptime)
        entries += buildDltViewerEntry(
            id = id++,
            ts = fields.getOrNull(cols.time)?.let(::normalizeViewerTimestamp).orEmpty(),
            dltTimestamp = uptime?.let(::parseViewerUptime),
            ecu = fields.getOrNull(cols.ecu), app = fields.getOrNull(cols.app), ctx = fields.getOrNull(cols.ctx),
            sessionId = fields.getOrNull(cols.session), type = type,
            level = resolveViewerLevel(hasSubtypeColumn = cols.subtype >= 0, type = type, subtype = subtype),
            payload = fields.getOrNull(cols.payload).orEmpty(), intern = ::intern,
        )
    }
    return entries
}

/**
 * Shared row -> [LogEntry] mapping for both viewer export forms. `dltMessageType` normalizes
 * `nw_trace` to `network_trace` to match the binary parser's [dltMessageTypeName]; `pid` is the
 * session id when it parses as a number; the tag joins ECU/APID/CTID like the binary parser does,
 * falling back to "DLT" when all three are blank.
 */
private fun buildDltViewerEntry(
    id: Int,
    ts: String,
    dltTimestamp: Long?,
    ecu: String?,
    app: String?,
    ctx: String?,
    sessionId: String?,
    type: String?,
    level: LogLevel,
    payload: String,
    intern: (String) -> String,
): LogEntry {
    val ecuId = ecu?.trim()?.takeIf(String::isNotEmpty)
    val appId = app?.trim()?.takeIf(String::isNotEmpty)
    val ctxId = ctx?.trim()?.takeIf(String::isNotEmpty)
    val typeNorm = type?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)
    val dltType = typeNorm?.let { if (it == "nw_trace") "network_trace" else it }
    val tag = intern(listOfNotNull(ecuId, appId, ctxId).joinToString("/").ifBlank { "DLT" })
    val pid = sessionId?.trim()?.toIntOrNull() ?: 0
    return LogEntry(
        id = id, ts = ts, level = level, tag = tag, msg = payload, pid = pid,
        dltEcuId = ecuId, dltAppId = appId, dltContextId = ctxId, dltMessageType = dltType,
        dltTimestamp = dltTimestamp, dltTimestampSource = "viewer",
    )
}

/**
 * Level per the plan: when a subtype column/field exists and the type is "log", map the subtype
 * word (fatal/error/warn/info/debug/verbose); otherwise, when the type value is itself a level
 * word (e.g. a custom "Type=ERROR" column), use that; otherwise I.
 */
private fun resolveViewerLevel(hasSubtypeColumn: Boolean, type: String?, subtype: String?): LogLevel {
    val typeNorm = type?.trim()?.lowercase(Locale.ROOT)
    if (hasSubtypeColumn && typeNorm == "log") {
        return subtype?.let(::viewerLevelOrNull) ?: LogLevel.I
    }
    return typeNorm?.let(::viewerLevelOrNull) ?: LogLevel.I
}

// DLT Viewer's CSV "Timestamp" / ASCII export's uptime field: seconds with exactly 4 fractional
// digits (0.1ms units — the same unit TMSP uses in the binary decoder, see formatRelativeTimestamp).
private const val UPTIME_UNITS_PER_SECOND = 10_000L
private const val UPTIME_FRACTION_DIGITS = 4

private fun parseViewerUptime(value: String): Long? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    val dot = trimmed.indexOf('.')
    if (dot < 0) return trimmed.toLongOrNull()?.let { it * UPTIME_UNITS_PER_SECOND }
    val whole = trimmed.substring(0, dot).toLongOrNull() ?: return null
    val fracDigits = trimmed.substring(dot + 1).filter(Char::isDigit)
    if (fracDigits.isEmpty()) return whole * UPTIME_UNITS_PER_SECOND
    val frac = fracDigits.padEnd(UPTIME_FRACTION_DIGITS, '0').take(UPTIME_FRACTION_DIGITS).toLongOrNull() ?: return null
    return whole * UPTIME_UNITS_PER_SECOND + frac
}

// internal (not private): reused by DltDetection.kt's CSV header/column resolver, so header
// parsing during detection can never drift from header/row parsing during the actual parse above.
// RFC-4180: quotes delimit a field (only when they open at the field's very start), "" inside a
// quoted field is a literal '"', and the delimiter/newlines inside quotes are literal too (embedded
// newlines are rejoined into one logical line by joinQuotedCsvLines before this ever sees them).
internal fun splitCsv(line: String, delimiter: Char): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var wasQuoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            quoted -> if (c == '"') {
                if (i + 1 < line.length && line[i + 1] == '"') {
                    current.append('"'); i++
                } else {
                    quoted = false
                }
            } else {
                current.append(c)
            }
            c == '"' && current.isEmpty() && !wasQuoted -> { quoted = true; wasQuoted = true }
            c == delimiter -> { out += if (wasQuoted) current.toString() else current.toString().trim(); current.clear(); wasQuoted = false }
            else -> current.append(c)
        }
        i++
    }
    out += if (wasQuoted) current.toString() else current.toString().trim()
    return out
}

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
