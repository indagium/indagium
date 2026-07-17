package com.openlog.ui

import androidx.compose.ui.graphics.Color
import com.openlog.model.DEFAULT_KEYWORD_HIGHLIGHT_COLOR
import com.openlog.model.FilterMode
import com.openlog.model.LogLevel
import com.openlog.model.SavedFilter
import com.openlog.model.SavedFilterFolder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Extracted from AppState (Task 12 slice 4a, mechanical — no behavior change): the pure
// encode/decode/naming half of saved-filter import/export — JSON serialization, JSON parsing,
// and unique-name resolution. None of these touch tabs/stateLock/upTab, so unlike slices 1-3
// they don't need a back-reference to AppState — the couple that previously read AppState's
// savedFilters field implicitly (buildImportRows, ImportFilterReviewRow.withImportAction,
// uniqueFilterName, freshSavedFilterId) now take it as an explicit parameter instead.
// The dialog-flow half (saveFilter, requestLoadFilter, deleteSF, rename, ...) stays on AppState —
// it's tightly coupled to upFlt/tab()/~15 pieces of dialog state and isn't a mechanical win to
// move; see the plan's Task 12 slice notes.

private const val FILTER_LIBRARY_FORMAT = "openlog-saved-filter-library"
private const val FILTER_LIBRARY_VERSION = 2

internal data class DecodedFilterLibrary(
    val filters: List<SavedFilter>,
    val folders: List<SavedFilterFolder> = emptyList(),
)

internal fun exportFiltersList(
    filters: List<SavedFilter>,
    folders: List<SavedFilterFolder> = emptyList(),
    includeEmptyFolders: Boolean = false,
): String = buildString {
    val referencedFolderIds = filters.mapNotNullTo(linkedSetOf()) { it.folderId }
    val exportedFolders = if (includeEmptyFolders) folders else folders.filter { it.id in referencedFolderIds }
    appendLine("{")
    appendLine("  \"format\": \"$FILTER_LIBRARY_FORMAT\",")
    appendLine("  \"version\": $FILTER_LIBRARY_VERSION,")
    appendLine("  \"folders\": [")
    exportedFolders.forEachIndexed { index, folder ->
        append("    {\"id\": ${folder.id.jsonStr()}, \"name\": ${folder.name.jsonStr()}}")
        if (index < exportedFolders.lastIndex) appendLine(",") else appendLine()
    }
    appendLine("  ],")
    appendLine("  \"filters\": ")
    append(exportFilterArray(filters).prependIndent("  "))
    appendLine()
    append("}")
}

private fun exportFilterArray(filters: List<SavedFilter>): String = buildString {
    appendLine("[")
    filters.forEachIndexed { i, sf ->
        appendLine("  {")
        appendLine("    \"id\": \"${sf.id}\",")
        appendLine("    \"name\": ${sf.name.jsonStr()},")
        appendLine("    \"levels\": [${sf.levels.joinToString(",") { "\"${it.key}\"" }}],")
        appendLine("    \"mode\": \"${sf.mode.name}\",")
        appendLine("    \"activeTags\": [${sf.activeTags.joinToString(",") { it.jsonStr() }}],")
        appendLine("    \"excludeTags\": [${sf.excludeTags.joinToString(",") { it.jsonStr() }}],")
        appendLine("    \"kwText\": ${sf.kwText.jsonStr()},")
        appendLine("    \"kwRegex\": ${sf.kwRegex},")
        appendLine("    \"kwHighlightEnabled\": ${sf.kwHighlightEnabled},")
        appendLine("    \"kwHighlightColor\": ${sf.kwHighlightColor.value.toString().jsonStr()},")
        appendLine("    \"excludeKw\": ${sf.excludeKw.jsonStr()},")
        appendLine("    \"excludeKwRegex\": ${sf.excludeKwRegex},")
        appendLine(
            "    \"highlighters\": [${
                sf.highlighters.joinToString(",") {
                    it.highlighterToken().jsonStr()
                }
            }],"
        )
        appendLine("    \"seqOn\": ${sf.seqOn},")
        appendLine("    \"kwInTag\": ${sf.kwInTag.jsonStr()},")
        appendLine("    \"kwInTagRegex\": ${sf.kwInTagRegex},")
        appendLine("    \"pkgPrefixes\": [${sf.pkgPrefixes.joinToString(",") { it.jsonStr() }}],")
        appendLine("    \"excludePkgPrefixes\": [${sf.excludePkgPrefixes.joinToString(",") { it.jsonStr() }}],")
        appendLine("    \"pidTidFilter\": ${sf.pidTidFilter.jsonStr()},")
        appendLine("    \"sequences\": [${sf.sequences.joinToString(",") { it.sequenceToken().jsonStr() }}],")
        appendLine(
            "    \"messageRules\": [${
                sf.messageRules.joinToString(",") {
                    it.messageRuleToken().jsonStr()
                }
            }],"
        )
        // Keep this a string rather than JSON null: older import readers accept only strings,
        // arrays, and booleans, and an empty folder id naturally means Ungrouped.
        appendLine("    \"folderId\": ${sf.folderId.orEmpty().jsonStr()},")
        appendLine("    \"favorite\": ${sf.favorite}")
        append("  }")
        if (i < filters.lastIndex) appendLine(",") else appendLine()
    }
    append("]")
}

internal fun decodeFilters(json: String): Result<List<SavedFilter>> = runCatching {
    val root = Json.parseToJsonElement(json)
    val arrayJson = when (root) {
        is JsonArray -> json
        is JsonObject -> root["filters"]?.jsonArray?.toString() ?: "[]"
        else -> "[]"
    }
    val entries = arrayJson.jsonObjectArray().getOrThrow()
    entries.mapNotNull { obj ->
        val name = obj.stringField("name") ?: return@mapNotNull null
        val id = obj.stringField("id")?.takeIf { it.isNotBlank() } ?: "sf${System.currentTimeMillis()}_${name.hashCode()}"
        val levels =
            obj.stringArrayField("levels").mapNotNull { c -> LogLevel.entries.find { it.key.toString() == c } }
                .toSet()
        val mode =
            runCatching { FilterMode.valueOf(obj.stringField("mode") ?: "TAGS") }
                .getOrElse { FilterMode.TAGS }
        val activeTags = obj.stringArrayField("activeTags").toSet()
        val excludeTags = obj.stringArrayField("excludeTags").toSet()
        val kwText = obj.stringField("kwText") ?: ""
        val kwRegex = obj.booleanField("kwRegex")
        val kwHighlightEnabled = obj["kwHighlightEnabled"] != false
        val kwHighlightColor = obj.stringField("kwHighlightColor")
            ?.toULongOrNull()
            ?.let(::Color)
            ?: DEFAULT_KEYWORD_HIGHLIGHT_COLOR
        val excludeKw = obj.stringField("excludeKw") ?: ""
        val excludeKwRegex = obj.booleanField("excludeKwRegex")
        val highlighters = obj.stringArrayField("highlighters").mapNotNull { it.highlighterFromToken() }
        val seqOn = obj["seqOn"] != false
        val kwInTag = obj.stringField("kwInTag") ?: ""
        val kwInTagRegex = obj.booleanField("kwInTagRegex")
        val pkgPrefixes = obj.stringArrayField("pkgPrefixes").toSet()
        val excludePkgPrefixes = obj.stringArrayField("excludePkgPrefixes").toSet()
        val pidTidFilter = obj.stringField("pidTidFilter") ?: ""
        val sequences = obj.stringArrayField("sequences").mapNotNull { it.sequenceFromToken() }
        val messageRules = obj.stringArrayField("messageRules").mapNotNull { it.messageRuleFromToken() }
        SavedFilter(
            id, name,
            levels.ifEmpty { LogLevel.entries.toSet() }, activeTags, kwText, kwRegex, mode,
            excludeTags, excludeKw, excludeKwRegex, highlighters, seqOn,
            kwInTag, kwInTagRegex, pkgPrefixes, pidTidFilter, sequences, messageRules,
            excludePkgPrefixes, kwHighlightEnabled, kwHighlightColor,
            folderId = obj.stringField("folderId")?.takeIf { it.isNotBlank() },
            favorite = obj.booleanField("favorite"),
        )
    }
}

/** Reads both the legacy flat array and the v2 library envelope. */
internal fun decodeFilterLibrary(json: String): Result<DecodedFilterLibrary> = runCatching {
    val element = Json.parseToJsonElement(json)
    if (element is JsonArray) {
        return@runCatching DecodedFilterLibrary(decodeFilters(json).getOrThrow())
    }
    val obj = element.jsonObject
    val filters = obj["filters"]?.jsonArray?.let { decodeFilters(it.toString()).getOrThrow() } ?: emptyList()
    val folders = obj["folders"]?.jsonArray.orEmpty().mapNotNull { folderElement ->
        val folder = folderElement.jsonObject
        val id = folder["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val name = folder["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        if (id != null && name != null) SavedFilterFolder(id, name) else null
    }
    DecodedFilterLibrary(filters, folders)
}

internal fun buildImportRows(savedFilters: List<SavedFilter>, imported: List<SavedFilter>): List<ImportFilterReviewRow> {
    var usedNames = savedFilters.map { it.name.normalizedFilterName() }.toMutableSet()
    return imported.mapIndexed { idx, incoming ->
        val existing = savedFilters.firstOrNull { it.name.normalizedFilterName() == incoming.name.normalizedFilterName() }
        val rowId = "import_${System.nanoTime()}_$idx"
        when {
            existing != null && existing.sameFilterPayloadAs(incoming) ->
                ImportFilterReviewRow(
                    rowId = rowId,
                    incoming = incoming,
                    action = ImportFilterAction.SKIP,
                    resolvedName = existing.name,
                    targetId = existing.id,
                    skippedReason = "identical",
                )

            existing != null -> {
                val renamed = uniqueName(incoming.name + " (imported)", usedNames)
                usedNames += renamed.normalizedFilterName()
                ImportFilterReviewRow(
                    rowId = rowId,
                    incoming = incoming,
                    action = ImportFilterAction.RENAME,
                    resolvedName = renamed,
                    targetId = existing.id,
                )
            }

            else -> {
                val name = uniqueName(incoming.name, usedNames)
                usedNames += name.normalizedFilterName()
                ImportFilterReviewRow(
                    rowId = rowId,
                    incoming = incoming,
                    action = ImportFilterAction.ADD,
                    resolvedName = name,
                )
            }
        }
    }
}

internal fun ImportFilterReviewRow.withImportAction(savedFilters: List<SavedFilter>, action: ImportFilterAction): ImportFilterReviewRow =
    when (action) {
        ImportFilterAction.REPLACE -> if (targetId != null) copy(action = action) else this
        ImportFilterAction.SKIP -> copy(action = action)
        ImportFilterAction.RENAME ->
            copy(action = action, resolvedName = uniqueFilterName(savedFilters, incoming.name + " (imported)", targetId))
        ImportFilterAction.ADD -> if (targetId == null) copy(action = action) else this
    }

internal fun uniqueFilterName(savedFilters: List<SavedFilter>, baseName: String, targetId: String? = null): String {
    val used = savedFilters
        .filter { it.id != targetId }
        .map { it.name.normalizedFilterName() }
        .toMutableSet()
    return uniqueName(baseName.trim().ifBlank { "Imported filter" }, used)
}

internal fun uniqueNameAgainst(baseName: String, filters: List<SavedFilter>): String =
    uniqueName(baseName.trim().ifBlank { "Imported filter" }, filters.map { it.name.normalizedFilterName() }.toMutableSet())

internal fun uniqueName(baseName: String, usedNormalizedNames: MutableSet<String>): String {
    val cleanBase = baseName.trim().ifBlank { "Imported filter" }
    if (cleanBase.normalizedFilterName() !in usedNormalizedNames) return cleanBase
    var idx = 2
    while (true) {
        val candidate = "$cleanBase $idx"
        if (candidate.normalizedFilterName() !in usedNormalizedNames) return candidate
        idx += 1
    }
}

internal fun freshSavedFilterId(savedFilters: List<SavedFilter>): String = "sf${System.nanoTime()}_${savedFilters.size}"

private fun String.normalizedFilterName(): String = trim().lowercase()

private fun SavedFilter.sameFilterPayloadAs(other: SavedFilter): Boolean =
    copy(id = "", name = "") == other.copy(id = "", name = "")
