package com.indagium.ui

import com.indagium.model.HomeLayoutLeaf
import com.indagium.model.HomeLayoutNode
import com.indagium.model.HomeLayoutSplit
import com.indagium.model.HomeSectionId
import com.indagium.model.HomeSplitAxis
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put

// The New tab layout's settings-JSON codec (AppSettings.homeLayout / homeHiddenSections / homeLayoutTipDismissed). It lives
// here, not in AutosaveCodec.kt, which is already at detekt's per-file function limit. Shapes:
//   {"split":"row","w":1.6,"children":[ ... ]}   and   {"section":"open","w":1}
// The decoder never throws: anything malformed is dropped, then sanitizeHomeLayoutState (HomeLayout.kt) makes the rest
// consistent, so a hand-edited or corrupt file still yields a valid layout holding every section exactly once.

/** Nesting deeper than this is dropped on decode (the sanitizer then re-adds the sections that were lost with it). */
internal const val HOME_LAYOUT_MAX_DEPTH: Int = 8

/** The three home* settings, decoded together because sanitizing needs the layout and the hidden list at once. */
internal data class HomeLayoutSettings(
    val layout: HomeLayoutNode,
    val hidden: List<HomeSectionId>,
    val tipDismissed: Boolean,
)

internal fun homeLayoutJson(node: HomeLayoutNode): JsonObject = buildJsonObject {
    when (node) {
        is HomeLayoutLeaf -> put("section", node.section.key)
        is HomeLayoutSplit -> put("split", node.axis.name.lowercase())
    }
    put("w", if (node.weight.isFinite()) node.weight else 1f)
    if (node is HomeLayoutSplit) put("children", buildJsonArray { node.children.forEach { add(homeLayoutJson(it)) } })
}

internal fun homeHiddenSectionsJson(hidden: List<HomeSectionId>): JsonArray = buildJsonArray { hidden.forEach { add(JsonPrimitive(it.key)) } }

/** Decodes one node; null when it is malformed or nested deeper than [HOME_LAYOUT_MAX_DEPTH]. */
internal fun JsonElement.homeLayoutNodeFromJson(depth: Int = 0): HomeLayoutNode? {
    val o = this as? JsonObject
    if (o == null || depth >= HOME_LAYOUT_MAX_DEPTH) return null
    val weight = (o["w"] as? JsonPrimitive)?.floatOrNull ?: 1f
    val section = (o["section"] as? JsonPrimitive)?.contentOrNull
    if (section != null) return HomeSectionId.fromKey(section)?.let { HomeLayoutLeaf(it, weight) }
    val axis = when ((o["split"] as? JsonPrimitive)?.contentOrNull) {
        "row" -> HomeSplitAxis.ROW
        "column" -> HomeSplitAxis.COLUMN
        else -> return null
    }
    val children = (o["children"] as? JsonArray)?.mapNotNull { it.homeLayoutNodeFromJson(depth + 1) }.orEmpty()
    return children.takeIf { it.isNotEmpty() }?.let { HomeLayoutSplit(axis, it, weight) }
}

/** Reads and sanitizes the three settings from the settings object; absent or malformed keys give the defaults. */
internal fun JsonObject.homeLayoutSettingsFromJson(): HomeLayoutSettings {
    val layout = this["homeLayout"]?.homeLayoutNodeFromJson()
    val hidden = (this["homeHiddenSections"] as? JsonArray)
        ?.mapNotNull { HomeSectionId.fromKey((it as? JsonPrimitive)?.contentOrNull) }.orEmpty()
    val state = sanitizeHomeLayoutState(layout, hidden)
    return HomeLayoutSettings(
        layout = state.layout,
        hidden = state.hidden,
        tipDismissed = (this["homeLayoutTipDismissed"] as? JsonPrimitive)?.booleanOrNull ?: false,
    )
}
