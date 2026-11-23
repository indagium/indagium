package com.indagium.utils

import com.indagium.model.AnnBlock
import com.indagium.model.AnnotationLogBlockStyle
import com.indagium.model.LogEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import kotlinx.serialization.json.Json as KJson

// Pure helpers for "fields of a JSON record" in notes: finding a field by its path, freezing the chosen subtrees as compact JSON, and
// printing a record or the chosen fields for Markdown/HTML. UI-free; the field tree itself is ui/StructuredLogRecord.kt.

/** The most JSON text (all chosen fields together) one fields reference may freeze. A bigger selection is refused. */
internal const val RECORD_FIELDS_MAX_JSON_CHARS = 64 * 1024

/** The most lines of pretty-printed JSON an export carries for one record or one fields reference; the rest is summarised. */
internal const val RECORD_EXPORT_MAX_LINES = 200

/** A record bigger than this is never parsed for an export (the tree itself stops at 256 KiB). */
private const val RECORD_PARSE_MAX_CHARS = 1024 * 1024

private const val INDENT = "  "

/** The record's JSON as a tree, or null when it is not JSON, not an object or array, or too big to bother with. */
internal fun parseRecordJson(raw: String?): JsonElement? {
    if (raw == null || raw.length > RECORD_PARSE_MAX_CHARS) return null
    val first = raw.firstOrNull { !it.isWhitespace() }
    if (first != '{' && first != '[') return null
    // A parse error, or a record nested deeper than the stack allows, is "not a record we can show".
    return runCatching { KJson.parseToJsonElement(raw) }.getOrNull()?.takeIf { it is JsonObject || it is JsonArray }
}

/** The JSON text of [entry]'s record when it is a JSON object or array, else null. */
internal fun LogEntry.jsonRecordSource(): String? = rawRecord?.takeIf { parseRecordJson(it) != null }

/** The field at [path] (object keys, array indices written "[2]") under [root], or null when the record has no such field. */
internal fun jsonSubtreeAt(root: JsonElement, path: List<String>): JsonElement? {
    var node: JsonElement = root
    for (segment in path) {
        val parent = node
        node = when (parent) {
            is JsonObject -> parent[segment] ?: return null
            is JsonArray -> segment.removeSurrounding("[", "]").toIntOrNull()?.let { parent.getOrNull(it) } ?: return null
            else -> return null
        }
    }
    return node
}

private val RECORD_ARRAY_INDEX = Regex("\\[[0-9]+\\]")

/** Dot paths with array indices and quoted brackets for keys that would otherwise be read as path syntax. */
internal fun joinRecordPath(path: List<String>): String = buildString {
    path.forEachIndexed { index, segment ->
        when {
            RECORD_ARRAY_INDEX.matches(segment) -> append(segment)
            segment.isEmpty() || segment.any { it in ".[]" || it.isISOControl() } -> append('[').append(JsonPrimitive(segment)).append(']')
            else -> {
                if (index > 0) append('.')
                append(segment)
            }
        }
    }
}

internal fun String.recordFingerprint(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray()).toHexString()

/** Keep the visible record header and a locator when a note contains only selected fields. */
internal fun AnnBlock.LogRef.withSelectedRecordSnapshot(): AnnBlock.LogRef {
    if (recordFields.isNullOrEmpty()) return this
    val row = sourceEntries?.singleOrNull()
    return copy(
        sourceEntries = row?.let { listOf(LogEntry(it.id, it.ts, it.level, it.tag, msg = "")) },
        presentation = presentation?.copy(rows = emptyList()),
        sourceRecordFingerprint = sourceRecordFingerprint ?: row?.rawRecord?.recordFingerprint(),
    )
}

/** "metadata › bluetooth › accessory_address": how a note shows where a field sits. */
internal fun displayRecordPath(path: List<String>): String = path.joinToString(" › ")

/** What freezing a selection of fields gave. */
internal sealed interface RecordFieldsResult {
    data class Ok(val fields: List<AnnBlock.RecordFieldRef>) : RecordFieldsResult

    /** The chosen fields' JSON came to [chars], over [RECORD_FIELDS_MAX_JSON_CHARS]. */
    data class TooLarge(val chars: Int) : RecordFieldsResult

    /** The record is not JSON any more, or has none of the chosen fields. */
    data object Unavailable : RecordFieldsResult
}

/**
 * Freezes the fields at [paths] of [rawRecord] as [AnnBlock.RecordFieldRef]s, in the order given, each path once and no field
 * that lies inside another chosen one (it is already part of that one's subtree). The record is parsed once.
 */
internal fun recordFieldRefs(rawRecord: String?, paths: List<List<String>>): RecordFieldsResult {
    val root = parseRecordJson(rawRecord) ?: return RecordFieldsResult.Unavailable
    val distinct = paths.filter { it.isNotEmpty() }.distinct()
    val kept = distinct.filter { path -> distinct.none { other -> other.size < path.size && path.subList(0, other.size) == other } }
    val fields = kept.mapNotNull { path -> jsonSubtreeAt(root, path)?.let { AnnBlock.RecordFieldRef(path, it.toString()) } }
    if (fields.isEmpty()) return RecordFieldsResult.Unavailable
    val chars = fields.sumOf { it.json.length }
    return if (chars > RECORD_FIELDS_MAX_JSON_CHARS) RecordFieldsResult.TooLarge(chars) else RecordFieldsResult.Ok(fields)
}

/** A one-field JSON object, `{"accessory_address": <the field>}`, so the field tree can draw a chosen field as its own single row. */
internal fun recordFieldTreeSource(field: AnnBlock.RecordFieldRef): String =
    "{" + JsonPrimitive(field.path.lastOrNull().orEmpty()).toString() + ":" + field.json + "}"

/** What "Copy value" puts on the clipboard for [element]: a string's own text, anything else as compact JSON. */
internal fun recordValueText(element: JsonElement): String =
    if (element is JsonPrimitive && element.isString) element.content else element.toString()

private fun JsonElement.children(): Collection<JsonElement>? = when (this) {
    is JsonObject -> values
    is JsonArray -> this
    else -> null
}

/** Count an omitted subtree without rendering its values or indents, even when it is deeply nested. */
private fun countPrettyJsonLines(element: JsonElement): Int {
    val children = element.children()?.takeIf { it.isNotEmpty() } ?: return 1
    val stack = ArrayDeque<Iterator<JsonElement>>()
    stack.addLast(children.iterator())
    var count = 2
    while (stack.isNotEmpty()) {
        val siblings = stack.last()
        if (!siblings.hasNext()) {
            stack.removeLast()
            continue
        }
        val nested = siblings.next().children()
        if (nested.isNullOrEmpty()) {
            count++
        } else {
            count += 2
            stack.addLast(nested.iterator())
        }
    }
    return count
}

/** Formats only the preview's visible lines. Omitted subtrees contribute to the count without being serialized. */
internal fun prettyJsonLines(element: JsonElement): List<String> {
    val lines = ArrayList<String>()
    var omitted = 0

    fun appendLine(render: () -> String) {
        if (lines.size < RECORD_EXPORT_MAX_LINES) lines += render() else omitted++
    }

    fun write(node: JsonElement, depth: Int, key: String?, suffix: String) {
        if (lines.size >= RECORD_EXPORT_MAX_LINES) {
            omitted += countPrettyJsonLines(node)
            return
        }
        val pad = INDENT.repeat(depth)
        val prefix = key?.let { JsonPrimitive(it).toString() + ": " }.orEmpty()
        when {
            node is JsonObject && node.isNotEmpty() -> {
                lines += "$pad$prefix{"
                val last = node.entries.size - 1
                node.entries.forEachIndexed { index, (key, value) ->
                    write(value, depth + 1, key, if (index < last) "," else "")
                }
                appendLine { "$pad}$suffix" }
            }
            node is JsonArray && node.isNotEmpty() -> {
                lines += "$pad$prefix["
                val last = node.size - 1
                node.forEachIndexed { index, value -> write(value, depth + 1, null, if (index < last) "," else "") }
                appendLine { "$pad]$suffix" }
            }
            else -> lines += "$pad$prefix$node$suffix"
        }
    }
    write(element, 0, null, "")
    if (omitted > 0) lines += "… $omitted more lines"
    return lines
}

/** The pretty-printed, capped JSON of the record [raw], or null when it is not a JSON object or array. */
internal fun recordExportLines(raw: String?): List<String>? = parseRecordJson(raw)?.let(::prettyJsonLines)

/**
 * The JSON object of a fields reference as an export prints it: each chosen field's joined path ("metadata.bluetooth.accessory_address")
 * mapped to its value, capped like a record. A frozen field whose JSON cannot be read is left out.
 */
internal fun recordFieldsExportLines(fields: List<AnnBlock.RecordFieldRef>): List<String> {
    val values = LinkedHashMap<String, JsonElement>()
    fields.forEach { field ->
        runCatching { KJson.parseToJsonElement(field.json) }.getOrNull()?.let { values[joinRecordPath(field.path)] = it }
    }
    return prettyJsonLines(JsonObject(values))
}

/** A fenced `json` block of [lines] in the notes' Markdown: ```json, or Jira's `{code:json}` when the notes use the Jira wiki style. */
internal fun StringBuilder.appendJsonCodeBlock(style: AnnotationLogBlockStyle, lines: List<String>) {
    val (open, close) = if (style == AnnotationLogBlockStyle.JIRA_JAVA) "{code:json}" to "{code}" else "```json" to "```"
    appendLine(open)
    lines.forEach { appendLine(it) }
    appendLine(close)
}
