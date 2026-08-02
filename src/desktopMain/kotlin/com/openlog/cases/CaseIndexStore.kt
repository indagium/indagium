package com.openlog.cases

import com.openlog.utils.writeFileAtomically
import java.io.File
import java.util.Base64

private const val CASE_INDEX_MAGIC = "openLog2-case-index-v1"

// Minimum field count of one "record\t..." line (CaseRecord.toLine()'s original 11 tab-separated
// fields). filterSummary (index FILTER_SUMMARY_FIELD_INDEX) was appended later and is read via
// getOrNull — a shorter, pre-existing cache line still parses, it just decodes filterSummary as
// null ("not recorded"), same append-last discipline as the .ann token format (see
// Annotations.annotationsToken).
private const val RECORD_LINE_FIELD_COUNT = 11

// Appended after the original 11 fields (indices 0..10) — see CaseRecord.filterSummary's doc
// comment and RECORD_LINE_FIELD_COUNT above.
private const val FILTER_SUMMARY_FIELD_INDEX = 11

// Appended after filterSummary — see CaseRecord.fingerprint's doc comment. Same append-last,
// getOrNull-read discipline: an index-12-less cache line (written before this field existed)
// still parses, decoding fingerprint as null ("not recorded").
private const val FINGERPRINT_FIELD_INDEX = 12

// Base64-url (no padding) round-trip for any field that could otherwise contain a tab or newline
// (titles, issue descriptions, note text-derived tokens, file paths) — same scheme as
// source/SourceIndexStore.kt and AppState's autosave format, duplicated here rather than shared
// since those extensions are file-private in their own files.
private fun String.b64(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))

private fun String.unb64(): String = String(Base64.getUrlDecoder().decode(this), Charsets.UTF_8)

// "~" is never a valid non-empty base64-url output, so it's a safe empty-string sentinel —
// mirrors source/SourceIndexStore.kt's fieldToken()/fieldValue() pair.
private fun String.fieldToken(): String = if (isEmpty()) "~" else b64()

private fun String.fieldValue(): String = if (this == "~") "" else unb64()

private fun CaseRecord.toLine(): String = listOf(
    id.fieldToken(),
    title.fieldToken(),
    issueDescription.fieldToken(),
    sourcePath.orEmpty().fieldToken(),
    appVersion.fieldToken(),
    decisiveTags.joinToString(",").fieldToken(),
    tags.joinToString(",").fieldToken(),
    tokens.joinToString(",").fieldToken(),
    mdPath.orEmpty().fieldToken(),
    annPath.orEmpty().fieldToken(),
    backingPath.fieldToken(),
    // Appended — index 11. Old readers (parts.size check above stays at 11) never see this field;
    // never reorder. See CaseRecord.filterSummary's own doc comment.
    filterSummary.orEmpty().fieldToken(),
    // Appended — index 12. See CaseRecord.fingerprint's own doc comment.
    fingerprint.orEmpty().fieldToken(),
).joinToString("\t")

private fun parseRecordLine(rest: String): CaseRecord? {
    val parts = rest.split("\t")
    if (parts.size < RECORD_LINE_FIELD_COUNT) return null
    return CaseRecord(
        id = parts[0].fieldValue(),
        title = parts[1].fieldValue(),
        issueDescription = parts[2].fieldValue(),
        sourcePath = parts[3].fieldValue().takeIf { it.isNotBlank() },
        appVersion = parts[4].fieldValue(),
        decisiveTags = parts[5].fieldValue().split(",").filter { it.isNotBlank() },
        tags = parts[6].fieldValue().split(",").filter { it.isNotBlank() }.toSet(),
        tokens = parts[7].fieldValue().split(",").filter { it.isNotBlank() }.toSet(),
        mdPath = parts[8].fieldValue().takeIf { it.isNotBlank() },
        annPath = parts[9].fieldValue().takeIf { it.isNotBlank() },
        backingPath = parts[10].fieldValue(),
        filterSummary = parts.getOrNull(FILTER_SUMMARY_FIELD_INDEX)?.fieldValue()?.takeIf { it.isNotBlank() },
        fingerprint = parts.getOrNull(FINGERPRINT_FIELD_INDEX)?.fieldValue()?.takeIf { it.isNotBlank() },
    )
}

private fun parseMetaLine(rest: String): Pair<String, CaseFileMeta>? {
    val parts = rest.split("\t")
    if (parts.size < 3) return null
    return parts[0].fieldValue() to CaseFileMeta(mtime = parts[1].toLong(), size = parts[2].toLong())
}

// Accumulates the sections while scanning line-by-line — every line is parsed independently
// under its own runCatching (see parseCaseIndexLines) so one truncated/garbled line never takes
// the rest of the file down with it.
private class ParseState {
    var version: Int? = null
    var builtAt: Long = 0L
    val fileMeta = mutableMapOf<String, CaseFileMeta>()
    val records = mutableListOf<CaseRecord>()
}

private fun ParseState.applyLine(line: String) {
    val field = line.substringBefore('\t')
    val rest = line.substringAfter('\t', "")
    when (field) {
        "version" -> version = rest.toInt()
        "builtAt" -> builtAt = rest.toLong()
        "meta" -> parseMetaLine(rest)?.let { (path, meta) -> fileMeta[path] = meta }
        "record" -> parseRecordLine(rest)?.let { records += it }
    }
}

private fun parseCaseIndexLines(lines: List<String>): CaseIndex? {
    if (lines.isEmpty() || lines.first() != CASE_INDEX_MAGIC) return null
    val state = ParseState()
    lines.drop(1).forEach { line -> runCatching { state.applyLine(line) } }
    val version = state.version ?: return null
    if (version != CASE_INDEX_VERSION) return null
    return CaseIndex(version = version, records = state.records, fileMeta = state.fileMeta, builtAt = state.builtAt)
}

/** Disk persistence for a [CaseIndex] — a line-oriented, tab-separated text format modeled
 *  directly on [com.openlog.source.SourceIndexStore]: a magic header line, then typed
 *  `record\tfield...` lines making up the sections. Free-text fields that could contain a tab or
 *  newline are base64-url-encoded via [String.fieldToken] so they can never corrupt the line
 *  structure. */
object CaseIndexStore {
    fun save(index: CaseIndex, file: File) {
        writeFileAtomically(file) { writer ->
            writer.appendLine(CASE_INDEX_MAGIC)
            writer.appendLine("version\t${index.version}")
            writer.appendLine("builtAt\t${index.builtAt}")
            index.fileMeta.forEach { (path, meta) ->
                writer.appendLine("meta\t${path.fieldToken()}\t${meta.mtime}\t${meta.size}")
            }
            index.records.forEach { record -> writer.appendLine("record\t${record.toLine()}") }
        }
    }

    // Missing file, empty file, wrong/missing magic header, or a stored version that doesn't
    // match CASE_INDEX_VERSION all mean "no usable index" to the caller — every one of those
    // collapses to null rather than a distinct error, since the only correct response in every
    // case is the same: rebuild via CaseIndexer.build. Any hard read/parse failure is caught here too.
    fun load(file: File): CaseIndex? {
        if (!file.exists()) return null
        return runCatching { parseCaseIndexLines(file.readLines()) }.getOrNull()
    }
}
