package com.indagium.ui

import com.indagium.model.CustomLogViewLayout
import com.indagium.model.LogViewScope
import com.indagium.model.LogViewSectionId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID

internal const val LOG_VIEW_LAYOUT_FILE_FORMAT = "indagium-log-view-layout"
internal const val LOG_VIEW_LAYOUT_FILE_VERSION = 1
private const val CUSTOM_LAYOUT_ID_PREFIX = "custom-log-view-"

/** [base] made unique against [taken], case-insensitively. */
internal fun uniqueLogViewLayoutName(base: String, taken: Collection<String>): String {
    val cleaned = base.trim().ifBlank { "My layout" }.take(80)
    val used = taken.map { it.trim().lowercase() }.toSet()
    if (cleaned.lowercase() !in used) return cleaned
    var n = 2
    while ("$cleaned ($n)".lowercase() in used) n++
    return "$cleaned ($n)"
}

internal fun newCustomLogViewLayoutId(): String = CUSTOM_LAYOUT_ID_PREFIX + UUID.randomUUID()

private fun CustomLogViewLayout.toJsonObject(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    put("tree", logViewTileTreeJson(tree))
    put("filterPanelWidth", filterPanelWidth)
    put("annotationPanelWidth", annotationPanelWidth)
    put("rightSidebarSplit", rightSidebarSplit)
    put("hidden", buildJsonArray { hidden.forEach { add(JsonPrimitive(it.key)) } })
    put("collapsed", buildJsonArray { collapsed.forEach { add(JsonPrimitive(it.key)) } })
    put("tilesLocked", tilesLocked)
    put("filterVisible", filterVisible)
    put("annotationVisible", annotationVisible)
    put("aiPanelVisible", aiPanelVisible)
    put("videoPanelVisible", videoPanelVisible)
    filterBarVisible?.let { put("filterBarVisible", it) }
}

internal fun customLogViewLayoutsJson(layouts: List<CustomLogViewLayout>): JsonArray =
    buildJsonArray { layouts.forEach { add(it.toJsonObject()) } }

private fun readIds(element: JsonElement?, key: String, required: Boolean): List<LogViewSectionId>? {
    val array = element as? JsonArray ?: return if (required) null else emptyList()
    val ids = array.map { item ->
        val value = (item as? JsonPrimitive)?.contentOrNull ?: return null
        LogViewSectionId.fromKey(value)
    }.filterNotNull()
    return ids.distinct()
}

/** Portable layouts may come from a newer release: unknown section IDs are ignored and re-added by the sanitizer if applicable. */
private fun readCompatibleIds(element: JsonElement?, key: String): List<LogViewSectionId> {
    val array = element as? JsonArray ?: error("This layout has an invalid $key tile list.")
    return array.mapNotNull { item ->
        val value = (item as? JsonPrimitive)?.contentOrNull ?: error("This layout has an invalid $key tile list.")
        LogViewSectionId.fromKey(value)
    }.distinct()
}

/** Settings are tolerant: malformed custom entries are skipped and all accepted trees are sanitized. */
private fun customLogViewLayoutFromJson(element: JsonElement): CustomLogViewLayout? = runCatching {
    val o = element as? JsonObject ?: return null
    val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(80).orEmpty()
    if (name.isBlank()) return null
    val tree = logViewTileTreeFromJson(o["tree"]) ?: return null
    val hidden = readIds(o["hidden"], "hidden", required = false) ?: return null
    val collapsed = readIds(o["collapsed"], "collapsed", required = false) ?: return null
    val state = sanitizeLogViewLayoutState(
        tree,
        hidden,
        LogViewScope.SINGLE,
        toolbarInLayout = LogViewSectionId.TOOLBAR in tileLeafSections(tree),
    )
    fun float(key: String, fallback: Float): Float =
        ((o[key] as? JsonPrimitive)?.floatOrNull ?: fallback).takeIf { it.isFinite() } ?: fallback
    fun bool(key: String): Boolean = (o[key] as? JsonPrimitive)?.booleanOrNull ?: true
    CustomLogViewLayout(
        id = (o["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith(CUSTOM_LAYOUT_ID_PREFIX) }
            ?: newCustomLogViewLayoutId(),
        name = name,
        tree = state.first,
        filterPanelWidth = float("filterPanelWidth", 220f).coerceIn(80f, 2000f),
        annotationPanelWidth = float("annotationPanelWidth", 360f).coerceIn(80f, 2000f),
        rightSidebarSplit = float("rightSidebarSplit", 0.5f).coerceIn(0.1f, 0.9f),
        hidden = state.second,
        collapsed = collapsed.filter { it in tileLeafSections(state.first) && !it.neverHidden }.distinct(),
        tilesLocked = bool("tilesLocked"),
        filterVisible = bool("filterVisible"),
        annotationVisible = bool("annotationVisible"),
        aiPanelVisible = bool("aiPanelVisible"),
        videoPanelVisible = bool("videoPanelVisible"),
        filterBarVisible = (o["filterBarVisible"] as? JsonPrimitive)?.booleanOrNull,
    )
}.getOrNull()

internal fun customLogViewLayoutsFromJson(element: JsonElement?): List<CustomLogViewLayout> {
    val array = element as? JsonArray ?: return emptyList()
    val result = mutableListOf<CustomLogViewLayout>()
    array.forEach { item ->
        var decoded = customLogViewLayoutFromJson(item) ?: return@forEach
        val id = decoded.id.takeIf { candidate -> result.none { it.id == candidate } } ?: newCustomLogViewLayoutId()
        val name = uniqueLogViewLayoutName(decoded.name, result.map { it.name })
        decoded = decoded.copy(id = id, name = name)
        result += decoded
    }
    return result
}

private val logViewLayoutFileJson = Json { prettyPrint = true }

internal fun encodeLogViewLayoutFile(layout: CustomLogViewLayout): String =
    logViewLayoutFileJson.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("format", LOG_VIEW_LAYOUT_FILE_FORMAT)
        put("version", LOG_VIEW_LAYOUT_FILE_VERSION)
        put("layout", layout.toJsonObject())
    })

internal fun decodeLogViewLayoutFile(text: String): Result<CustomLogViewLayout> = runCatching {
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }
        .getOrElse { error("This file is not a log layout (it is not valid JSON).") }
    if ((root["format"] as? JsonPrimitive)?.contentOrNull != LOG_VIEW_LAYOUT_FILE_FORMAT) {
        error("This file is not an Indagium log layout.")
    }
    val version = (root["version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        ?: error("This layout file has no version number.")
    if (version > LOG_VIEW_LAYOUT_FILE_VERSION) {
        error("This layout was saved by a newer version of Indagium (file version $version). Update Indagium to import it.")
    }
    if (version != LOG_VIEW_LAYOUT_FILE_VERSION) error("This layout file version is not supported.")
    val layout = root["layout"] as? JsonObject ?: error("This layout file has no layout in it.")
    val name = (layout["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(80)
        ?.takeIf { it.isNotBlank() } ?: error("This layout has no name.")
    val treeElement = layout["tree"] ?: error("This layout has no tile arrangement.")
    val rawSections = strictTreeSections(treeElement) ?: error("This layout has an invalid tile arrangement.")
    val treeSections = rawSections.mapNotNull { LogViewSectionId.fromKey(it) }
    if (treeSections.any { it.scope == LogViewScope.COMPARE }) {
        error("This layout contains a tile that cannot be used in a single log view.")
    }
    if (treeSections.count { it == LogViewSectionId.TOOLBAR } > 1) error("This layout has an invalid toolbar tile.")
    val tree = logViewTileTreeFromJson(treeElement)
    val hidden = readCompatibleIds(layout["hidden"], "hidden")
    if (hidden.any { (it.scope != LogViewScope.SINGLE && it.scope != LogViewScope.BOTH) || it.neverHidden }) {
        error("This layout hides a tile that cannot be hidden in a single log view.")
    }
    val collapsed = readCompatibleIds(layout["collapsed"], "collapsed")
    if (collapsed.any { it.scope == LogViewScope.COMPARE || it.neverHidden }) error("This layout collapses a tile that cannot be collapsed.")
    fun float(key: String): Float {
        val value = (layout[key] as? JsonPrimitive)?.floatOrNull ?: error("This layout is missing $key.")
        if (!value.isFinite()) error("This layout has an invalid $key value.")
        return value
    }
    fun bool(key: String): Boolean = (layout[key] as? JsonPrimitive)?.booleanOrNull
        ?: error("This layout is missing $key.")
    val sanitized = sanitizeLogViewLayoutState(
        tree,
        hidden,
        LogViewScope.SINGLE,
        toolbarInLayout = LogViewSectionId.TOOLBAR in treeSections,
    )
    CustomLogViewLayout(
        id = newCustomLogViewLayoutId(),
        name = name,
        tree = sanitized.first,
        filterPanelWidth = float("filterPanelWidth").coerceIn(80f, 2000f),
        annotationPanelWidth = float("annotationPanelWidth").coerceIn(80f, 2000f),
        rightSidebarSplit = float("rightSidebarSplit").coerceIn(0.1f, 0.9f),
        hidden = sanitized.second,
        collapsed = collapsed.distinct(),
        tilesLocked = bool("tilesLocked"),
        filterVisible = bool("filterVisible"),
        annotationVisible = bool("annotationVisible"),
        aiPanelVisible = bool("aiPanelVisible"),
        videoPanelVisible = bool("videoPanelVisible"),
        filterBarVisible = (layout["filterBarVisible"] as? JsonPrimitive)?.booleanOrNull,
    )
}

/** Strict structural walk used only for standalone-file validation; settings migration remains tolerant. */
private fun strictTreeSections(element: JsonElement, depth: Int = 0): List<String>? {
    if (depth >= LOG_VIEW_LAYOUT_MAX_DEPTH) return null
    val o = element as? JsonObject ?: return null
    (o["section"] as? JsonPrimitive)?.contentOrNull?.let { return listOf(it) }
    val axis = (o["split"] as? JsonPrimitive)?.contentOrNull
    if (axis != "row" && axis != "column") return null
    val children = o["children"] as? JsonArray ?: return null
    if (children.isEmpty()) return null
    val sections = children.map { strictTreeSections(it, depth + 1) ?: return null }
    return sections.flatten()
}

