package com.indagium.ui

import com.indagium.model.AppSettings
import com.indagium.model.LogViewPresetId
import com.indagium.model.LogViewScope
import com.indagium.model.LogViewSectionId
import com.indagium.model.TileNode
import com.indagium.model.ToolbarPlacement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

// The log view's and the compare view's tiling settings (AppSettings.logViewLayout, logViewHidden, logViewCollapsed,
// logViewTilesLocked, lastLogViewPreset, compareLayout, compareHidden, lastComparePreset, compareSyncScroll), JSON only. The
// tree shape is the shared one of TileLayoutJson.kt, a leaf naming a LogViewSectionId key. They live here, not in
// AutosaveCodec.kt, which is already at detekt's per-file function limit. The decoder never throws: an absent or malformed layout
// is null (which means "derive Classic / Compare grid from the legacy sizes"), a present one is made consistent by
// sanitizeLogViewLayoutState, unknown ids are dropped, and anything else malformed falls back to the field's default.

/** Nesting deeper than this is dropped on decode (the sanitizer then re-adds the tiles that were lost with it). */
internal const val LOG_VIEW_LAYOUT_MAX_DEPTH: Int = 8

internal fun logViewTileTreeJson(node: TileNode<LogViewSectionId>): JsonObject = tileLayoutJson(node) { it.key }

internal fun logViewTileTreeFromJson(element: JsonElement?): TileNode<LogViewSectionId>? =
    element?.let { tileLayoutNodeFromJson(it, { key -> LogViewSectionId.fromKey(key) }, 0, LOG_VIEW_LAYOUT_MAX_DEPTH) }

private fun idsJson(ids: List<LogViewSectionId>): JsonArray = buildJsonArray { ids.forEach { add(JsonPrimitive(it.key)) } }

// The ids in a JSON array, unknown ones dropped; null when [element] is not an array (malformed: the caller uses its default).
private fun idsFromJson(element: JsonElement?): List<LogViewSectionId>? =
    (element as? JsonArray)?.mapNotNull { LogViewSectionId.fromKey((it as? JsonPrimitive)?.contentOrNull) }

private fun presetFromJson(element: JsonElement?, scope: LogViewScope, default: LogViewPresetId): LogViewPresetId {
    val name = (element as? JsonPrimitive)?.contentOrNull
    val id = LogViewPresetId.entries.firstOrNull { it.name == name }
    return if (id != null && LogViewLayoutPreset.of(id).scope == scope) id else default
}

/** Appends the log-view tiling keys to the settings object being built (see AutosaveCodec.settingsJson). */
internal fun JsonObjectBuilder.putLogViewLayoutSettings(settings: AppSettings) {
    settings.logViewLayout?.let { put("logViewLayout", logViewTileTreeJson(it)) }
    put("logViewHidden", idsJson(settings.logViewHidden))
    put("logViewCollapsed", idsJson(settings.logViewCollapsed))
    put("logViewTilesLocked", settings.logViewTilesLocked)
    put("lastLogViewPreset", settings.lastLogViewPreset.name)
    settings.compareLayout?.let { put("compareLayout", logViewTileTreeJson(it)) }
    put("compareHidden", idsJson(settings.compareHidden))
    put("lastComparePreset", settings.lastComparePreset.name)
    put("compareSyncScroll", settings.compareSyncScroll)
}

// One view's layout and hidden list, decoded and sanitized together. A layout that is absent or malformed stays null; the
// hidden list is then only cleaned (no tree to complete).
private fun JsonObject.layoutAndHidden(
    layoutKey: String,
    hiddenKey: String,
    scope: LogViewScope,
    defaultHidden: List<LogViewSectionId>,
    toolbarInLayout: Boolean,
): Pair<TileNode<LogViewSectionId>?, List<LogViewSectionId>> {
    val tree = logViewTileTreeFromJson(this[layoutKey])
    val hidden = idsFromJson(this[hiddenKey]) ?: defaultHidden
    if (tree == null) {
        return null to sanitizeLogViewLayoutState(null, hidden, scope, toolbarInLayout).second
    }
    return sanitizeLogViewLayoutState(tree, hidden, scope, toolbarInLayout).let { it.first to it.second }
}

// True when the settings object stores the toolbar as a tile of the layout; read from the raw key so the order the sections are decoded in
// does not matter (the toolbar tile is kept in a stored single-view tree only then).
private fun JsonObject.toolbarInLayout(): Boolean = (this["toolbarPlacement"] as? JsonPrimitive)?.contentOrNull == ToolbarPlacement.IN_LAYOUT.name

/**
 * [this] with the log-view tiling fields read from [o] (the parsed settings object); absent or malformed keys leave the
 * defaults of a fresh [AppSettings]. Called from AutosaveCodec.settingsFromJson.
 */
internal fun AppSettings.withLogViewLayoutSettingsFrom(o: JsonObject): AppSettings {
    val single = o.layoutAndHidden("logViewLayout", "logViewHidden", LogViewScope.SINGLE, logViewHidden, o.toolbarInLayout())
    val compare = o.layoutAndHidden("compareLayout", "compareHidden", LogViewScope.COMPARE, compareHidden, false)
    return copy(
        logViewLayout = single.first,
        logViewHidden = single.second,
        logViewCollapsed = idsFromJson(o["logViewCollapsed"])?.distinct().orEmpty(),
        logViewTilesLocked = (o["logViewTilesLocked"] as? JsonPrimitive)?.booleanOrNull ?: logViewTilesLocked,
        lastLogViewPreset = presetFromJson(o["lastLogViewPreset"], LogViewScope.SINGLE, lastLogViewPreset),
        compareLayout = compare.first,
        compareHidden = compare.second,
        lastComparePreset = presetFromJson(o["lastComparePreset"], LogViewScope.COMPARE, lastComparePreset),
        compareSyncScroll = (o["compareSyncScroll"] as? JsonPrimitive)?.booleanOrNull ?: compareSyncScroll,
    )
}
