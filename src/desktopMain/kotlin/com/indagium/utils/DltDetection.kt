package com.indagium.utils

import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Content kinds a bounded byte sample can classify as. [DLT_STORAGE]/[DLT_RAW] are binary DLT v1
 * (with/without the storage-header wrapper); [DLT_UNSUPPORTED_V2] is an identifiable protocol v2
 * stream (storage-magic or a headerless frame gated by filename — see [classifyLogContent]);
 * [DLT_VIEWER_CSV]/[DLT_VIEWER_TEXT] are DLT Viewer text exports; [TEXT] is ordinary log text;
 * [OTHER] is anything else (binary, or text too ambiguous to call).
 */
internal enum class LogContentKind {
    DLT_STORAGE, DLT_RAW, DLT_UNSUPPORTED_V2, DLT_VIEWER_CSV, DLT_VIEWER_TEXT, TEXT, OTHER
}

/**
 * Bounded sample size read for content classification. Shared by every call site that needs to
 * decide what a log source is — [parseLogContent], [sniffCandidateContent]/[isLikelyDltSourceFile],
 * and [candidateKindFromContent] — so they can never disagree with each other about the same bytes.
 */
internal const val CONTENT_SNIFF_BYTES = 8 * 1024

/** True for any of the DLT_* kinds, as opposed to [LogContentKind.TEXT]/[LogContentKind.OTHER]. */
internal fun LogContentKind.isDlt(): Boolean =
    this != LogContentKind.TEXT && this != LogContentKind.OTHER

private const val STD_HEADER_SIZE = 4
private const val EXTENDED_HEADER_SIZE = 10
private const val ID_FIELD_SIZE = 4
private const val OPTIONAL_FIELD_SIZE = 4
private const val MAX_FRAME_LEN = 0xffff
private const val HTYP_UEH = 0x01
private const val HTYP_WEID = 0x04
private const val HTYP_WSID = 0x08
private const val HTYP_WTMS = 0x10
private const val HTYP_VERSION_SHIFT = 5
private const val BYTE_MASK = 0xff
private const val MIN_CHAINED_FRAMES = 3
private const val ASCII_VIEWER_LOOKAHEAD_LINES = 5
private const val ASCII_VIEWER_MIN_MATCHES = 2
private const val MSTP_MAX = 3
private const val PRINTABLE_ASCII_LOW = 0x20
private const val PRINTABLE_ASCII_HIGH = 0x7e

private val STORAGE_MAGIC = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte())

// DLT Viewer CSV header aliases. Phase 3 replaces this simple presence check with the real
// resolveDltCsvColumns column-index resolver, shared verbatim with parsing — kept as one function
// here so that swap touches a single spot instead of every call site.
private val CSV_ECU_ALIASES = setOf("ecuid", "ecu", "ecu id", "ecu_id")
private val CSV_APP_ALIASES = setOf("apid", "apid desc", "appid", "app", "app_id", "application")
private val CSV_CTX_ALIASES = setOf("ctid", "ctid desc", "contextid", "context", "context_id")

// DLT Viewer ASCII export: single-space separated, an optional leading numeric index, then
// <date> <time> <uptime> <count> <ecu> <apid> <ctid> <sessionId> <type> <subtype> <mode> <#args>
// <payload...>. Only the anchor fields (date/time/uptime/type/mode) are validated strictly; the
// short id/count/subtype fields are legitimately empty for some message types.
private val ASCII_DATE = Regex("""\d{4}/\d{2}/\d{2}""")
private val ASCII_TIME = Regex("""\d{2}:\d{2}:\d{2}\.\d{6}""")
private val ASCII_UPTIME = Regex("""\d+\.\d{4}""")
private val ASCII_TYPES = setOf("log", "app_trace", "nw_trace", "control")
private val ASCII_MODES = setOf("verbose", "non-verbose")

/**
 * The single source of truth for what a bounded prefix of a log source's bytes is. Every DLT
 * detector in the app ([parseLogContent], [sniffCandidateContent]/[isLikelyDltSourceFile],
 * [candidateKindFromContent]) routes through this so they can never disagree with each other —
 * that disagreement used to let ordinary archive binaries (nested zips, SQLite, protobuf) get
 * misclassified as DLT by one detector but not another.
 *
 * [sample] is a prefix of up to [CONTENT_SNIFF_BYTES] bytes, never a whole large file — callers
 * must not sniff more than that. [atEof] must be true when [sample] is the *entire* remaining
 * content (fewer bytes were available than the sniff budget); it lets a short, complete raw DLT
 * stream (no storage header) be accepted on fewer than the [MIN_CHAINED_FRAMES] threshold normally
 * required to rule out arbitrary binary that merely starts with a plausible-looking frame.
 * [fileName] is used only to gate an ambiguous, headerless v2 frame (rule 4 below) — a real DLT
 * storage header (`DLT\x01`/`DLT\x02`) is always unambiguous and never needs it.
 *
 * Rules, in order:
 * 1. `DLT\x01` storage magic -> [LogContentKind.DLT_STORAGE]; `DLT\x02` -> [LogContentKind.DLT_UNSUPPORTED_V2].
 * 2. Text-like content -> a DLT Viewer CSV/ASCII export, or plain [LogContentKind.TEXT].
 * 3. A chain of >= [MIN_CHAINED_FRAMES] structurally valid v1 frames from offset 0 (or a shorter
 *    chain that exactly consumes the whole sample when [atEof]) -> [LogContentKind.DLT_RAW].
 * 4. A lone byte with v2's version bits, name-gated to files ending in `.dlt` -> [LogContentKind.DLT_UNSUPPORTED_V2].
 * 5. Otherwise -> [LogContentKind.OTHER].
 */
internal fun classifyLogContent(sample: ByteArray, atEof: Boolean, fileName: String? = null): LogContentKind {
    if (hasStorageMagic(sample, version = 1)) return LogContentKind.DLT_STORAGE
    if (hasStorageMagic(sample, version = 2)) return LogContentKind.DLT_UNSUPPORTED_V2

    if (isLikelyTextSample(sample)) {
        val firstLine = firstNonBlankLines(sample, 1).firstOrNull()
        if (firstLine != null && looksLikeDltCsvHeaderLine(firstLine)) return LogContentKind.DLT_VIEWER_CSV
        val lookahead = firstNonBlankLines(sample, ASCII_VIEWER_LOOKAHEAD_LINES)
        val matches = lookahead.count(::isDltViewerAsciiLine)
        val isAsciiViewer = if (lookahead.size <= 1) matches == 1 else matches >= ASCII_VIEWER_MIN_MATCHES
        return if (isAsciiViewer) LogContentKind.DLT_VIEWER_TEXT else LogContentKind.TEXT
    }

    val chain = walkRawV1Frames(sample)
    if (chain.completeFrames >= MIN_CHAINED_FRAMES ||
        (atEof && chain.endOffset == sample.size && chain.completeFrames >= 1)
    ) {
        return LogContentKind.DLT_RAW
    }
    if (isVersion2Header(sample) && fileName.hasDltExtension()) return LogContentKind.DLT_UNSUPPORTED_V2
    return LogContentKind.OTHER
}

private fun String?.hasDltExtension(): Boolean =
    this != null && substringAfterLast('/').substringAfterLast('.', "").equals("dlt", ignoreCase = true)

private fun hasStorageMagic(sample: ByteArray, version: Int): Boolean =
    sample.size >= STD_HEADER_SIZE && sample[0] == STORAGE_MAGIC[0] && sample[1] == STORAGE_MAGIC[1] &&
        sample[2] == STORAGE_MAGIC[2] && (sample[3].toInt() and BYTE_MASK) == version

private fun isVersion2Header(sample: ByteArray): Boolean =
    sample.isNotEmpty() && ((sample[0].toInt() and BYTE_MASK) ushr HTYP_VERSION_SHIFT) == 2

private data class RawFrameWalk(val completeFrames: Int, val endOffset: Int)

/**
 * Walks chained v1 frames from offset 0 of a headerless raw stream, validating structure only —
 * this never decodes a payload. Stops at the first offset that isn't a complete, structurally
 * valid frame (or runs off the end of [sample]); [RawFrameWalk.endOffset] is where it stopped.
 */
private fun walkRawV1Frames(sample: ByteArray): RawFrameWalk {
    var offset = 0
    var frames = 0
    while (offset + STD_HEADER_SIZE <= sample.size) {
        val htyp = sample[offset].toInt() and BYTE_MASK
        if ((htyp ushr HTYP_VERSION_SHIFT) != 1) break
        val len = ((sample[offset + 2].toInt() and BYTE_MASK) shl 8) or (sample[offset + 3].toInt() and BYTE_MASK)
        val optionalFieldsSize = (if (htyp and HTYP_WEID != 0) ID_FIELD_SIZE else 0) +
            (if (htyp and HTYP_WSID != 0) OPTIONAL_FIELD_SIZE else 0) +
            (if (htyp and HTYP_WTMS != 0) OPTIONAL_FIELD_SIZE else 0)
        val minimum = STD_HEADER_SIZE + optionalFieldsSize + (if (htyp and HTYP_UEH != 0) EXTENDED_HEADER_SIZE else 0)
        if (len !in minimum..MAX_FRAME_LEN) break
        if (htyp and HTYP_UEH != 0) {
            val extOffset = offset + STD_HEADER_SIZE + optionalFieldsSize
            if (extOffset + EXTENDED_HEADER_SIZE > sample.size) break
            val msin = sample[extOffset].toInt() and BYTE_MASK
            val mstp = (msin ushr 1) and 0x07
            if (mstp > MSTP_MAX) break
            if (!printableIdsAt(sample, extOffset + 2)) break
        }
        if (offset + len > sample.size) break
        frames++
        offset += len
    }
    return RawFrameWalk(frames, offset)
}

/** APID (4 bytes) followed by CTID (4 bytes) — each byte must be printable ASCII or NUL. */
private fun printableIdsAt(sample: ByteArray, offset: Int): Boolean {
    for (i in 0 until ID_FIELD_SIZE * 2) {
        val b = sample[offset + i].toInt() and BYTE_MASK
        if (b != 0 && b !in PRINTABLE_ASCII_LOW..PRINTABLE_ASCII_HIGH) return false
    }
    return true
}

private fun firstNonBlankLines(sample: ByteArray, max: Int): List<String> = runCatching {
    openLogTextReader(ByteArrayInputStream(sample)).use { reader ->
        reader.lineSequence().filter(String::isNotBlank).take(max).toList()
    }
}.getOrDefault(emptyList())

/**
 * Phase 1 DLT Viewer CSV header check: exact case-insensitive column aliases. Kept as one function
 * so Phase 3 can replace it with the real resolveDltCsvColumns resolver (shared with parsing)
 * without touching [classifyLogContent].
 */
internal fun looksLikeDltCsvHeaderLine(line: String): Boolean {
    val trimmed = line.trim()
    if (',' !in trimmed && ';' !in trimmed) return false
    val delimiter = if (trimmed.count { it == ';' } > trimmed.count { it == ',' }) ';' else ','
    val headers = splitCsv(trimmed, delimiter).map { it.trim().lowercase(Locale.ROOT) }
    fun has(aliases: Set<String>) = headers.any { it in aliases }
    return has(CSV_ECU_ALIASES) && has(CSV_APP_ALIASES) && has(CSV_CTX_ALIASES)
}

/**
 * Strict DLT Viewer ASCII-export line matcher (single-space separated, optional leading index).
 * Phase 3 reuses this for the real line parser instead of the speculative bracketed/plain forms
 * it replaces.
 */
internal fun isDltViewerAsciiLine(line: String): Boolean {
    val tokens = line.split(' ')
    val idx = when {
        tokens.isNotEmpty() && ASCII_DATE.matches(tokens[0]) -> 0
        tokens.size > 1 && tokens[0].toIntOrNull() != null && ASCII_DATE.matches(tokens[1]) -> 1
        else -> return false
    }
    if (tokens.size < idx + 13) return false
    return ASCII_TIME.matches(tokens[idx + 1]) &&
        ASCII_UPTIME.matches(tokens[idx + 2]) &&
        tokens[idx + 8] in ASCII_TYPES &&
        tokens[idx + 10] in ASCII_MODES
}
