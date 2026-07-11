package com.openlog.source

import com.openlog.utils.writeFileAtomically
import java.io.File
import java.util.Base64

private const val SOURCE_INDEX_MAGIC = "openLog2-source-index-v1"

// Base64-url (no padding) round-trip for any field that could otherwise contain a tab or newline
// (file paths, matcher regex patterns, method names) — same scheme as AppState's autosave format
// (String.b64()/unb64()), duplicated here rather than shared since those extensions are file-private.
private fun String.b64(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))

private fun String.unb64(): String = String(Base64.getUrlDecoder().decode(this), Charsets.UTF_8)

// "~" is never a valid non-empty base64-url output (the shortest non-empty encoding is 2 chars),
// so it's a safe empty-string sentinel — mirrors AppState's fieldToken()/fieldValue() pair.
private fun String.fieldToken(): String = if (isEmpty()) "~" else b64()

private fun String.fieldValue(): String = if (this == "~") "" else unb64()

private fun LogCallSite.toLine(): String = listOf(
    filePath.fieldToken(),
    tag.orEmpty().fieldToken(),
    methodName.fieldToken(),
    methodStartLine.toString(),
    methodEndLine.toString(),
    callLine.toString(),
    matcher.fieldToken(),
    literalLen.toString(),
).joinToString("\t")

private fun parseSiteLine(rest: String): LogCallSite? {
    val parts = rest.split("\t")
    if (parts.size < 8) return null
    return LogCallSite(
        filePath = parts[0].fieldValue(),
        tag = parts[1].fieldValue().takeIf { it.isNotBlank() },
        methodName = parts[2].fieldValue(),
        methodStartLine = parts[3].toInt(),
        methodEndLine = parts[4].toInt(),
        callLine = parts[5].toInt(),
        matcher = parts[6].fieldValue(),
        literalLen = parts[7].toInt(),
    )
}

private fun parseMetaLine(rest: String): Pair<String, FileMeta>? {
    val parts = rest.split("\t")
    if (parts.size < 3) return null
    return parts[0].fieldValue() to FileMeta(mtime = parts[1].toLong(), size = parts[2].toLong())
}

// Accumulates the sections while scanning line-by-line — every line is parsed independently under
// its own runCatching (see parseSourceIndexLines) so one truncated/garbled line never takes the
// rest of the file down with it.
private class ParseState {
    var version: Int? = null
    var builtAt: Long = 0L
    val roots = mutableListOf<String>()
    val fileMeta = mutableMapOf<String, FileMeta>()
    val sites = mutableListOf<LogCallSite>()
}

private fun ParseState.applyLine(line: String) {
    val field = line.substringBefore('\t')
    val rest = line.substringAfter('\t', "")
    when (field) {
        "version" -> version = rest.toInt()
        "builtAt" -> builtAt = rest.toLong()
        "root" -> roots += rest.fieldValue()
        "meta" -> parseMetaLine(rest)?.let { (path, meta) -> fileMeta[path] = meta }
        "site" -> parseSiteLine(rest)?.let { sites += it }
    }
}

private fun parseSourceIndexLines(lines: List<String>): SourceIndex? {
    if (lines.isEmpty() || lines.first() != SOURCE_INDEX_MAGIC) return null
    val state = ParseState()
    lines.drop(1).forEach { line -> runCatching { state.applyLine(line) } }
    val version = state.version ?: return null
    if (version != SOURCE_INDEX_VERSION) return null
    return SourceIndex(
        version = version,
        roots = state.roots,
        sites = state.sites,
        fileMeta = state.fileMeta,
        builtAt = state.builtAt,
    )
}

/** Disk persistence for a [SourceIndex] — a line-oriented, tab-separated text format mirroring the
 *  style of the app's `openLog2-cache-v1` autosave format: a magic header line, then typed
 *  `record\tfield...` lines (`root`, `meta`, `site`) making up the roots/fileMeta/sites sections.
 *  Free-text fields that could contain a tab or newline (file paths, matcher patterns, method
 *  names) are base64-url-encoded via [String.fieldToken] so they can never corrupt the line
 *  structure. */
object SourceIndexStore {
    fun save(index: SourceIndex, file: File) {
        writeFileAtomically(file) { writer ->
            writer.appendLine(SOURCE_INDEX_MAGIC)
            writer.appendLine("version\t${index.version}")
            writer.appendLine("builtAt\t${index.builtAt}")
            index.roots.forEach { root -> writer.appendLine("root\t${root.fieldToken()}") }
            index.fileMeta.forEach { (path, meta) ->
                writer.appendLine("meta\t${path.fieldToken()}\t${meta.mtime}\t${meta.size}")
            }
            index.sites.forEach { site -> writer.appendLine("site\t${site.toLine()}") }
        }
    }

    // Missing file, empty file, wrong/missing magic header, or a stored version that doesn't match
    // SOURCE_INDEX_VERSION all mean "no usable index" to the caller — every one of those collapses
    // to null rather than a distinct error, since the only correct response in every case is the
    // same: rebuild via SourceIndexer.build. Any hard read/parse failure is caught here too.
    fun load(file: File): SourceIndex? {
        if (!file.exists()) return null
        return runCatching { parseSourceIndexLines(file.readLines()) }.getOrNull()
    }
}
