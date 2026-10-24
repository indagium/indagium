package com.indagium.ui

import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put

// The settings-JSON codec of a tiling layout tree, shared by every layout that lives in AppSettings. Shapes:
//   {"split":"row","w":1.6,"children":[ ... ]}   and   {"section":"open","w":1}
// plus an optional "dp":240 on any node that is pinned to a size (written only when set, so a layout that never pins stays
// byte-identical to what it was before pinning existed). The decoder never throws: anything malformed is dropped (null), and
// callers sanitize what is left against their id enum.

/** Nesting deeper than this is dropped on decode by default. */
internal const val TILE_LAYOUT_MAX_DEPTH: Int = 8

/** [node] as JSON; [keyOf] turns a leaf's id into its stable stored key. */
internal fun <K> tileLayoutJson(node: TileNode<K>, keyOf: (K) -> String): JsonObject = buildJsonObject {
    when (node) {
        is TileLeaf -> put("section", keyOf(node.section))
        is TileSplit -> put("split", node.axis.name.lowercase())
    }
    put("w", if (node.weight.isFinite()) node.weight else 1f)
    node.sizeDp?.takeIf { it.isFinite() }?.let { put("dp", it) }
    if (node is TileSplit) put("children", buildJsonArray { node.children.forEach { add(tileLayoutJson(it, keyOf)) } })
}

/**
 * Decodes one node; null when it is malformed, names a leaf [fromKey] does not know, or is nested deeper than [maxDepth].
 * A child that fails to decode is skipped, a split left without children is null.
 */
internal fun <K> tileLayoutNodeFromJson(
    element: JsonElement,
    fromKey: (String) -> K?,
    depth: Int = 0,
    maxDepth: Int = TILE_LAYOUT_MAX_DEPTH,
): TileNode<K>? {
    val o = element as? JsonObject
    if (o == null || depth >= maxDepth) return null
    val weight = (o["w"] as? JsonPrimitive)?.floatOrNull ?: 1f
    val sizeDp = (o["dp"] as? JsonPrimitive)?.floatOrNull
    val section = (o["section"] as? JsonPrimitive)?.contentOrNull
    if (section != null) return fromKey(section)?.let { TileLeaf(it, weight, sizeDp) }
    val axis = when ((o["split"] as? JsonPrimitive)?.contentOrNull) {
        "row" -> TileAxis.ROW
        "column" -> TileAxis.COLUMN
        else -> return null
    }
    val children = (o["children"] as? JsonArray)?.mapNotNull { tileLayoutNodeFromJson(it, fromKey, depth + 1, maxDepth) }.orEmpty()
    return children.takeIf { it.isNotEmpty() }?.let { TileSplit(axis, it, weight, sizeDp) }
}
