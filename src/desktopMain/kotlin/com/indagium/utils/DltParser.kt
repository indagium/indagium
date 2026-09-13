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
    return when (classifyLogContent(sample, atEof, fileName)) {
        LogContentKind.DLT_STORAGE -> ParsedLog(LogFormat.DLT, parseDltBinary(input, storage = true, startId = startId))
        LogContentKind.DLT_RAW -> ParsedLog(LogFormat.DLT, parseDltBinary(input, storage = false, startId = startId))
        LogContentKind.DLT_UNSUPPORTED_V2 -> throw IllegalArgumentException("DLT protocol v2 is not supported")
        LogContentKind.DLT_VIEWER_CSV, LogContentKind.DLT_VIEWER_TEXT -> parseViewerTextLog(input, startId)
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

private fun parseViewerTextLog(input: InputStream, startId: Int): ParsedLog =
    openLogTextReader(input).use { reader -> ParsedLog(LogFormat.DLT, parseDltViewerLines(reader.lineSequence(), startId)) }

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
        } catch (malformed: IllegalArgumentException) {
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
    } catch (malformed: CharacterCodingException) {
        return null
    }
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
