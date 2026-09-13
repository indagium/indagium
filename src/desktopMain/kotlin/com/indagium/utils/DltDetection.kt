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

// DLT Viewer CSV column resolution — the SINGLE resolver shared by classification (below) and the
// actual CSV parser (DltParser.kt's parseDltViewerCsv), so the two can never disagree about which
// column is which. Aliases verified against qdlt/fieldnames.cpp's default DLT Viewer export headers
// ("Ecuid","Apid"/"Apid Desc","Ctid"/"Ctid Desc","SessionId"/"SessionName", ...), plus a few common
// human-edited variants.
internal data class DltCsvColumns(
    val time: Int,
    val uptime: Int,
    val counter: Int,
    val ecu: Int,
    val app: Int,
    val ctx: Int,
    val session: Int,
    val type: Int,
    val subtype: Int,
    val payload: Int,
)

private val CSV_TIME_ALIASES = setOf("time", "date", "datetime")
private val CSV_UPTIME_ALIASES = setOf("timestamp")
private val CSV_COUNTER_ALIASES = setOf("count")
private val CSV_ECU_ALIASES = setOf("ecuid", "ecu", "ecu id", "ecu_id")
private val CSV_APP_ALIASES = setOf("apid", "apid desc", "appid", "app", "app_id", "application")
private val CSV_CTX_ALIASES = setOf("ctid", "ctid desc", "contextid", "context", "context_id")
private val CSV_SESSION_ALIASES = setOf("sessionid", "sessionname")
private val CSV_TYPE_ALIASES = setOf("type")
private val CSV_SUBTYPE_ALIASES = setOf("subtype", "level", "severity")
private val CSV_PAYLOAD_ALIASES = setOf("payload", "message", "msg", "text")

/** Delimiters tried, in order, when a CSV file's own delimiter isn't otherwise known. */
internal val DLT_CSV_DELIMITERS = listOf(',', ';', '\t')

/**
 * Resolves a header row's columns to the fields this app cares about, or null when the row isn't a
 * DLT Viewer CSV header. ecu+app+ctx must ALL be present (the same "must have all three ID columns"
 * bar the old presence-only check used) — every other column is optional and reads back -1 when
 * absent from the header.
 */
internal fun resolveDltCsvColumns(headers: List<String>): DltCsvColumns? {
    val norm = headers.map { it.trim().lowercase(Locale.ROOT) }
    fun idx(aliases: Set<String>) = norm.indexOfFirst { it in aliases }
    val ecu = idx(CSV_ECU_ALIASES)
    val app = idx(CSV_APP_ALIASES)
    val ctx = idx(CSV_CTX_ALIASES)
    if (ecu < 0 || app < 0 || ctx < 0) return null
    return DltCsvColumns(
        time = idx(CSV_TIME_ALIASES), uptime = idx(CSV_UPTIME_ALIASES), counter = idx(CSV_COUNTER_ALIASES),
        ecu = ecu, app = app, ctx = ctx, session = idx(CSV_SESSION_ALIASES),
        type = idx(CSV_TYPE_ALIASES), subtype = idx(CSV_SUBTYPE_ALIASES), payload = idx(CSV_PAYLOAD_ALIASES),
    )
}

/**
 * Tries each of [DLT_CSV_DELIMITERS] against [headerLine] (RFC-4180 aware, via [splitCsv]) and
 * returns the first one whose fields [resolveDltCsvColumns] accepts, paired with the resolved
 * columns. Used by both the classifier (to decide [LogContentKind.DLT_VIEWER_CSV]) and the CSV
 * parser (to know which delimiter the rest of the file uses) — one decision, shared.
 */
internal fun resolveDltCsvHeader(headerLine: String): Pair<Char, DltCsvColumns>? {
    for (delimiter in DLT_CSV_DELIMITERS) {
        val headers = splitCsv(headerLine, delimiter)
        if (headers.size < 2) continue
        val columns = resolveDltCsvColumns(headers) ?: continue
        return delimiter to columns
    }
    return null
}

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
 * 2. A NUL-free sample -> a DLT Viewer CSV/ASCII export, or plain [LogContentKind.TEXT].
 * 3. A chain of >= [MIN_CHAINED_FRAMES] structurally valid v1 frames from offset 0 (or, when
 *    [atEof], >= 1 frame that consumes the sample or ends in a truncated frame) -> [LogContentKind.DLT_RAW].
 * 4. Remaining text-like (UTF-16) content -> as rule 2.
 * 5. A lone byte with v2's version bits, name-gated to files ending in `.dlt` -> [LogContentKind.DLT_UNSUPPORTED_V2].
 * 6. Otherwise -> [LogContentKind.OTHER].
 */
internal fun classifyLogContent(sample: ByteArray, atEof: Boolean, fileName: String? = null): LogContentKind {
    if (hasStorageMagic(sample, version = 1)) return LogContentKind.DLT_STORAGE
    if (hasStorageMagic(sample, version = 2)) return LogContentKind.DLT_UNSUPPORTED_V2

    // A NUL-free sample is ASCII/UTF-8 text or non-DLT binary: every real frame header carries NULs
    // (LEN's high byte, padded ids), and NUL-free bytes 2..3 make LEN too large to chain in the
    // sample. Only a sample that contains NULs can be DLT — and it must be tested for frames before
    // the UTF-16 heuristic, which otherwise claims small NUL-dense frames as UTF-16 text.
    if (sample.none { it == 0.toByte() }) return classifyText(sample)

    val chain = walkRawV1Frames(sample)
    val completeOrTruncatedTail = chain.endOffset == sample.size || chain.stoppedAtTruncatedFrame
    if (chain.completeFrames >= MIN_CHAINED_FRAMES || (atEof && chain.completeFrames >= 1 && completeOrTruncatedTail)) {
        return LogContentKind.DLT_RAW
    }
    if (isLikelyTextSample(sample)) return classifyText(sample)
    if (isVersion2Header(sample) && fileName.hasDltExtension()) return LogContentKind.DLT_UNSUPPORTED_V2
    return LogContentKind.OTHER
}

private fun classifyText(sample: ByteArray): LogContentKind {
    val firstLine = firstNonBlankLines(sample, 1).firstOrNull()
    if (firstLine != null && resolveDltCsvHeader(firstLine) != null) return LogContentKind.DLT_VIEWER_CSV
    val lookahead = firstNonBlankLines(sample, ASCII_VIEWER_LOOKAHEAD_LINES)
    val matches = lookahead.count(::isDltViewerAsciiLine)
    val isAsciiViewer = if (lookahead.size <= 1) matches == 1 else matches >= ASCII_VIEWER_MIN_MATCHES
    return if (isAsciiViewer) LogContentKind.DLT_VIEWER_TEXT else LogContentKind.TEXT
}

private fun String?.hasDltExtension(): Boolean =
    this != null && substringAfterLast('/').substringAfterLast('.', "").equals("dlt", ignoreCase = true)

private fun hasStorageMagic(sample: ByteArray, version: Int): Boolean =
    sample.size >= STD_HEADER_SIZE && sample[0] == STORAGE_MAGIC[0] && sample[1] == STORAGE_MAGIC[1] &&
        sample[2] == STORAGE_MAGIC[2] && (sample[3].toInt() and BYTE_MASK) == version

private fun isVersion2Header(sample: ByteArray): Boolean =
    sample.isNotEmpty() && ((sample[0].toInt() and BYTE_MASK) ushr HTYP_VERSION_SHIFT) == 2

private data class RawFrameWalk(val completeFrames: Int, val endOffset: Int, val stoppedAtTruncatedFrame: Boolean)

/**
 * Walks chained v1 frames from offset 0 of a headerless raw stream, validating structure only —
 * this never decodes a payload. Stops at the first offset that isn't a complete, structurally
 * valid frame (or runs off the end of [sample]); [RawFrameWalk.endOffset] is where it stopped.
 */
private fun walkRawV1Frames(sample: ByteArray): RawFrameWalk {
    var offset = 0
    var frames = 0
    var truncated = false
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
        if (offset + len > sample.size) {
            truncated = true
            break
        }
        frames++
        offset += len
    }
    return RawFrameWalk(frames, offset, truncated)
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
 * Thin wrapper kept for direct unit tests (DltDetectionTest) — the real logic is
 * [resolveDltCsvHeader], the single CSV-header resolver shared with parsing.
 */
internal fun looksLikeDltCsvHeaderLine(line: String): Boolean = resolveDltCsvHeader(line) != null

/**
 * Thin wrapper kept for direct unit tests (DltDetectionTest) — the real logic, and the only ASCII
 * export line parser in the app, is [parseDltViewerAsciiLine] (DltParser.kt). Detection and parsing
 * share it so they can never disagree about what counts as a DLT Viewer ASCII-export line.
 */
internal fun isDltViewerAsciiLine(line: String): Boolean = parseDltViewerAsciiLine(line, 0) != null
