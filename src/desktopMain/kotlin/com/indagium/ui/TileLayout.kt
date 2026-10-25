package com.indagium.ui

import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The generic tiling engine behind the New tab and the log view: pure tree operations, geometry and drop hit-testing over a
// TileNode<K> tree whose leaves are identified by K. No Compose imports on purpose: the host's custom Layout, the drop
// overlay, the size labels and the preset mini-icons all read the same TileGeometry, and everything here is unit-tested
// without a UI. HomeLayout.kt binds K to HomeSectionId and keeps the New tab's names; the settings codec is TileLayoutJson.kt.
//
// Sizing along a split's axis. A child is one of:
// - collapsed: a leaf of a COLUMN split that is in the `collapsed` set; it takes exactly collapsedExtentDp;
// - pinned: it has a sizeDp; it takes that many dp. If that would leave the weighted children less than minTileDp each, all
//   pinned sizes scale down together. When no weighted child is left, the pinned sizes act as weights over what remains;
// - weighted: the rest share what remains by weight.

/** Width (dp) of the page-edge drop bands along the four host edges. They win over the card under the pointer. */
const val TILE_PAGE_BAND_DP: Float = 28f

const val TILE_MIN_WEIGHT: Float = 0.05f
const val TILE_MAX_WEIGHT: Float = 20f
const val TILE_MIN_SIZE_DP: Float = 1f
const val TILE_MAX_SIZE_DP: Float = 8000f

/** Height (dp) a collapsed tile keeps along its COLUMN split: just its header. */
const val TILE_COLLAPSED_EXTENT_DP: Float = 32f

// A page-edge drop onto a root of the other axis keeps the old content at this weight against the dragged tile's 1.
private const val TILE_PAGE_EDGE_OLD_WEIGHT: Float = 1.6f

// Share of the host / of a card the drop preview covers.
private const val TILE_PAGE_PREVIEW_SHARE: Float = 0.38f
private const val TILE_SECTION_PREVIEW_SHARE: Float = 0.5f

/** Side of a host or card a tile is dropped on. */
enum class TileEdge(val axis: TileAxis, val leading: Boolean) {
    LEFT(TileAxis.ROW, true),
    RIGHT(TileAxis.ROW, false),
    TOP(TileAxis.COLUMN, true),
    BOTTOM(TileAxis.COLUMN, false),
}

sealed interface TileDropTarget<out K> {
    /** Dropped on [edge] of another visible tile. */
    data class OnSection<out K>(val section: K, val edge: TileEdge) : TileDropTarget<K>

    /** Dropped on [edge] of the whole host. */
    data class OnPage(val edge: TileEdge) : TileDropTarget<Nothing>
}

/** A rectangle in dp, origin top-left. The right and bottom edges are exclusive for [contains]. */
data class TileRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h

    fun contains(px: Float, py: Float): Boolean = px >= x && px < right && py >= y && py < bottom
}

data class TileCardRect<K>(val section: K, val rect: TileRect)

/**
 * The gap between two siblings of a split.
 *
 * @property path child indexes from the root to the split node that owns the gap (empty = the root).
 * @property index the index, within that split, of the child AFTER the gap (the pair resized is index - 1 and index).
 * @property axis the split's axis: for ROW the gap is a vertical strip, for COLUMN a horizontal one.
 * @property rect the gap strip itself, which is also the splitter hit area.
 * @property splitExtent the split's extent along [axis] in dp (its size minus all its gaps); it is what a resize needs to
 * turn a drag of d dp into weight terms.
 * @property resizable false when a neighbour is collapsed: dragging that gap does nothing.
 */
data class TileGap(
    val path: List<Int>,
    val index: Int,
    val axis: TileAxis,
    val rect: TileRect,
    val splitExtent: Float,
    val resizable: Boolean = true,
)

data class TileGeometry<K>(val cards: List<TileCardRect<K>>, val gaps: List<TileGap>) {
    fun cardOf(section: K): TileRect? = cards.firstOrNull { it.section == section }?.rect
}

/**
 * What [tileGeometry] needs besides the tree. [gapRowDp] separates side-by-side children (a ROW split), [gapColumnDp]
 * stacked ones (a COLUMN split). [minTileDp] is the least a weighted child is squeezed to by pinned siblings and the least a
 * resize leaves a tile along the split axis; [minPairShare] is the least share of a pair's total weight a resize leaves.
 */
data class TileGeometrySpec(
    val gapRowDp: Float,
    val gapColumnDp: Float,
    val collapsedExtentDp: Float = TILE_COLLAPSED_EXTENT_DP,
    val minTileDp: Float,
    val minPairShare: Float,
)

// ---------------------------------------------------------------------------------------------------------------
// Tree basics
// ---------------------------------------------------------------------------------------------------------------

internal fun <K> TileNode<K>.withTileWeight(weight: Float): TileNode<K> = withTileSlot(weight, sizeDp)

/** The node with a new [weight] and pinned [sizeDp] (null = weighted). */
internal fun <K> TileNode<K>.withTileSlot(weight: Float, sizeDp: Float?): TileNode<K> = when (this) {
    is TileLeaf -> copy(weight = weight, sizeDp = sizeDp)
    is TileSplit -> copy(weight = weight, sizeDp = sizeDp)
}

// The pinned size, or null when the node is weighted (absent or not a positive finite number).
private fun <K> TileNode<K>.pinnedDp(): Float? = sizeDp?.takeIf { it.isFinite() && it > 0f }

/** The leaves in the tree, depth first, in display order. */
internal fun <K> tileLeafSections(node: TileNode<K>): List<K> = when (node) {
    is TileLeaf -> listOf(node.section)
    is TileSplit -> node.children.flatMap { tileLeafSections(it) }
}

/** True when both trees have the same arrangement of leaves; weights and pinned sizes are ignored (active-preset highlight). */
internal fun <K> tileLayoutShapeEquals(a: TileNode<K>, b: TileNode<K>): Boolean = when {
    a is TileLeaf && b is TileLeaf -> a.section == b.section
    a is TileSplit && b is TileSplit ->
        a.axis == b.axis && a.children.size == b.children.size &&
            a.children.indices.all { tileLayoutShapeEquals(a.children[it], b.children[it]) }
    else -> false
}

/**
 * Tidies a tree: drops empty splits, collapses a single-child split into that child (which takes over the split's weight and
 * pinned size, being the one that sits in the parent's slot) and flattens a child split that has the same axis as its parent,
 * rescaling so the parent's shares are unchanged. A child split with a pinned size is never flattened: the pin would be lost.
 * Returns null when nothing is left.
 */
internal fun <K> normalizeTileLayout(node: TileNode<K>): TileNode<K>? = when (node) {
    is TileLeaf -> node
    is TileSplit -> normalizeSplit(node)
}

private fun <K> normalizeSplit(split: TileSplit<K>): TileNode<K>? {
    val children = split.children.mapNotNull { normalizeTileLayout(it) }
    return when {
        children.isEmpty() -> null
        children.size == 1 -> children.single().withTileSlot(split.weight, split.sizeDp)
        else -> split.copy(
            children = children.flatMap { if (it is TileSplit && it.axis == split.axis && it.sizeDp == null) flattenedChildren(it) else listOf(it) },
        )
    }
}

// A same-axis child split's children, the weighted ones rescaled so together they keep the weight the child split had.
private fun <K> flattenedChildren(child: TileSplit<K>): List<TileNode<K>> {
    val weighted = child.children.filter { it.sizeDp == null }
    val sum = weighted.sumOf { it.weight.toDouble() }.toFloat()
    return child.children.map {
        when {
            it.sizeDp != null -> it
            sum > 0f -> it.withTileWeight(it.weight * child.weight / sum)
            else -> it.withTileWeight(child.weight / weighted.size)
        }
    }
}

/** The tree without [section], normalized; null when [section] was the last one. */
internal fun <K> removeTile(root: TileNode<K>, section: K): TileNode<K>? =
    removeLeaf(root, section)?.let { normalizeTileLayout(it) }

private fun <K> removeLeaf(node: TileNode<K>, section: K): TileNode<K>? = when (node) {
    is TileLeaf -> if (node.section == section) null else node
    is TileSplit -> node.copy(children = node.children.mapNotNull { removeLeaf(it, section) })
}

// ---------------------------------------------------------------------------------------------------------------
// Drop
// ---------------------------------------------------------------------------------------------------------------

/**
 * Moves [dragged] onto [target]. The dragged tile is removed first, then:
 * - on a tile edge whose axis equals the target's parent split: inserted beside the target, splitting the target's weight in
 *   half (a pinned target keeps its size and the dragged tile is weighted);
 * - on a tile edge of the other axis: the target is wrapped in a new split of that axis, which takes the target's slot;
 * - on a page edge: added to the root (average weighted-sibling weight) when the root has that axis or is a single tile,
 *   otherwise a new root [old root 1.6, dragged 1] in edge order.
 * Dropping on itself, or onto a tile that is not in the tree, returns [root] unchanged. The result is normalized.
 */
internal fun <K> dropTile(root: TileNode<K>, dragged: K, target: TileDropTarget<K>): TileNode<K> {
    if (target is TileDropTarget.OnSection && target.section == dragged) return root
    val rest = removeTile(root, dragged) ?: return root
    val placed = when (target) {
        is TileDropTarget.OnSection ->
            if (target.section in tileLeafSections(rest)) insertAtSection(rest, target.section, dragged, target.edge) else return root
        is TileDropTarget.OnPage -> insertAtPageEdge(rest, dragged, target.edge)
    }
    return normalizeTileLayout(placed) ?: root
}

private fun <K> ordered(edge: TileEdge, dragged: TileNode<K>, other: TileNode<K>): List<TileNode<K>> =
    if (edge.leading) listOf(dragged, other) else listOf(other, dragged)

private fun <K> insertAtSection(node: TileNode<K>, target: K, dragged: K, edge: TileEdge): TileNode<K> = when (node) {
    is TileLeaf ->
        if (node.section == target) {
            TileSplit(edge.axis, ordered(edge, TileLeaf(dragged), node.withTileSlot(1f, null)), node.weight, node.sizeDp)
        } else {
            node
        }
    is TileSplit -> {
        val at = node.children.indexOfFirst { it is TileLeaf && it.section == target }
        if (at >= 0 && node.axis == edge.axis) {
            val old = node.children[at]
            val pair = if (old.sizeDp != null) {
                ordered(edge, TileLeaf(dragged), old)
            } else {
                ordered(edge, TileLeaf(dragged, old.weight / 2), old.withTileWeight(old.weight / 2))
            }
            node.copy(children = node.children.take(at) + pair + node.children.drop(at + 1))
        } else {
            node.copy(children = node.children.map { insertAtSection(it, target, dragged, edge) })
        }
    }
}

private fun <K> insertAtPageEdge(rest: TileNode<K>, dragged: K, edge: TileEdge): TileNode<K> {
    val base = when {
        rest is TileSplit && rest.axis == edge.axis -> rest
        rest is TileLeaf -> TileSplit(edge.axis, listOf(rest), rest.weight)
        else -> return TileSplit(edge.axis, ordered(edge, TileLeaf(dragged), rest.withTileSlot(TILE_PAGE_EDGE_OLD_WEIGHT, null)))
    }
    val average = base.children.filter { it.sizeDp == null }.map { it.weight }.average().takeIf { !it.isNaN() }?.toFloat() ?: 1f
    val added = TileLeaf(dragged, average)
    return base.copy(children = if (edge.leading) listOf(added) + base.children else base.children + added)
}

/** Adds [section] at the trailing page edge: right of a ROW root or a single tile, below a COLUMN root. */
internal fun <K> appendTile(root: TileNode<K>, section: K): TileNode<K> {
    val edge = if (root is TileSplit && root.axis == TileAxis.COLUMN) TileEdge.BOTTOM else TileEdge.RIGHT
    return dropTile(root, section, TileDropTarget.OnPage(edge))
}

// ---------------------------------------------------------------------------------------------------------------
// Sizing along a split
// ---------------------------------------------------------------------------------------------------------------

// How a split's [extent] is shared out; see the header comment. [weightedExtent] is what the weighted children share,
// [pinnedScale] the factor from a pinned child's sizeDp to its actual dp.
private class TileSizePlan(
    val sizes: List<Float>,
    val collapsed: List<Boolean>,
    val pinned: List<Boolean>,
    val pinnedScale: Float,
    val weightedExtent: Float,
)

private fun <K> sizePlan(split: TileSplit<K>, extent: Float, minTileDp: Float, collapsedExtentDp: Float, collapsedSet: Set<K>): TileSizePlan {
    val children = split.children
    val column = split.axis == TileAxis.COLUMN
    val isCollapsed = children.map { column && it is TileLeaf && it.section in collapsedSet }
    val isPinned = children.mapIndexed { i, c -> !isCollapsed[i] && c.pinnedDp() != null }
    val available = max(0f, extent - isCollapsed.count { it } * collapsedExtentDp)
    val pinnedSum = children.indices.filter { isPinned[it] }.sumOf { children[it].pinnedDp()!!.toDouble() }.toFloat()
    val weighted = children.indices.filter { !isCollapsed[it] && !isPinned[it] }
    val scale: Float
    val weightedExtent: Float
    if (weighted.isEmpty()) {
        scale = if (pinnedSum > 0f) available / pinnedSum else 0f
        weightedExtent = available
    } else {
        scale = if (pinnedSum > 0f) ((available - weighted.size * minTileDp) / pinnedSum).coerceIn(0f, 1f) else 1f
        weightedExtent = max(0f, available - pinnedSum * scale)
    }
    val weightSum = weighted.sumOf { children[it].weight.toDouble() }.toFloat()
    val sizes = children.indices.map { i ->
        when {
            isCollapsed[i] -> collapsedExtentDp
            isPinned[i] -> children[i].pinnedDp()!! * scale
            weightSum > 0f -> weightedExtent * children[i].weight / weightSum
            else -> weightedExtent / weighted.size
        }
    }
    return TileSizePlan(sizes, isCollapsed, isPinned, scale, weightedExtent)
}

// ---------------------------------------------------------------------------------------------------------------
// Resize
// ---------------------------------------------------------------------------------------------------------------

/**
 * Moves the gap before child [index] of the split at [path] by [deltaDp] (positive = towards the end). Only the two
 * neighbours change, each keeping at least [minPairShare] of the pair and [minSizeDp]:
 * - weighted with weighted: their weights move, their total staying constant;
 * - pinned with weighted: the pinned one's sizeDp changes (the weighted children absorb it, none going below [minSizeDp]);
 * - pinned with pinned: the dp moves between them, their total staying constant.
 * [extentDp] is [TileGap.splitExtent]; [collapsed] and [collapsedExtentDp] must be what the geometry was laid out with. A gap
 * next to a collapsed tile, and invalid input, return [root] unchanged.
 */
internal fun <K> resizeTileSplit(
    root: TileNode<K>,
    path: List<Int>,
    index: Int,
    deltaDp: Float,
    extentDp: Float,
    minSizeDp: Float,
    minPairShare: Float,
    collapsed: Set<K> = emptySet(),
    collapsedExtentDp: Float = TILE_COLLAPSED_EXTENT_DP,
): TileNode<K> {
    if (extentDp <= 0f || !deltaDp.isFinite()) return root
    return updateSplitAt(root, path) { split ->
        resizePair(split, index, deltaDp, minSizeDp, minPairShare, sizePlan(split, extentDp, minSizeDp, collapsedExtentDp, collapsed))
    }
}

private fun <K> updateSplitAt(node: TileNode<K>, path: List<Int>, update: (TileSplit<K>) -> TileSplit<K>): TileNode<K> {
    if (node !is TileSplit) return node
    if (path.isEmpty()) return update(node)
    val at = path.first()
    if (at !in node.children.indices) return node
    return node.copy(children = node.children.mapIndexed { i, c -> if (i == at) updateSplitAt(c, path.drop(1), update) else c })
}

private fun <K> nodeAt(root: TileNode<K>, path: List<Int>): TileNode<K>? =
    path.fold(root as TileNode<K>?) { node, at -> (node as? TileSplit)?.children?.getOrNull(at) }

private fun <K> resizePair(
    split: TileSplit<K>,
    index: Int,
    deltaDp: Float,
    minSizeDp: Float,
    minPairShare: Float,
    plan: TileSizePlan,
): TileSplit<K> {
    if (index !in 1 until split.children.size || plan.collapsed[index - 1] || plan.collapsed[index]) return split
    val beforePinned = plan.pinned[index - 1]
    val afterPinned = plan.pinned[index]
    val resized = when {
        !beforePinned && !afterPinned -> resizeWeightedPair(split, index, deltaDp, minSizeDp, minPairShare, plan)
        beforePinned && afterPinned -> resizePinnedPair(split, index, deltaDp, minSizeDp, plan)
        else -> resizeMixedPair(split, index, deltaDp, minSizeDp, plan)
    }
    return resized ?: split
}

private fun <K> TileSplit<K>.withPair(index: Int, before: TileNode<K>, after: TileNode<K>): TileSplit<K> =
    copy(children = children.mapIndexed { i, c -> if (i == index - 1) before else if (i == index) after else c })

private fun <K> resizeWeightedPair(split: TileSplit<K>, index: Int, deltaDp: Float, minSizeDp: Float, minPairShare: Float, plan: TileSizePlan): TileSplit<K>? {
    if (plan.weightedExtent <= 0f) return null
    val before = split.children[index - 1]
    val after = split.children[index]
    val pair = before.weight + after.weight
    val weightedSum = split.children.indices.filter { !plan.collapsed[it] && !plan.pinned[it] }
        .sumOf { split.children[it].weight.toDouble() }.toFloat()
    val weightPerDp = weightedSum / plan.weightedExtent
    val minWeight = max(pair * minPairShare, minSizeDp * weightPerDp)
    if (minWeight * 2 > pair) return null
    val newBefore = (before.weight + deltaDp * weightPerDp).coerceIn(minWeight, pair - minWeight)
    return split.withPair(index, before.withTileWeight(newBefore), after.withTileWeight(pair - newBefore))
}

private fun <K> resizePinnedPair(split: TileSplit<K>, index: Int, deltaDp: Float, minSizeDp: Float, plan: TileSizePlan): TileSplit<K>? {
    if (plan.pinnedScale <= 0f) return null
    val before = split.children[index - 1]
    val after = split.children[index]
    val beforeUnits = before.pinnedDp() ?: return null
    val afterUnits = after.pinnedDp() ?: return null
    val pair = beforeUnits + afterUnits
    val minUnits = minSizeDp / plan.pinnedScale
    if (minUnits * 2 > pair) return null
    val newBefore = (beforeUnits + deltaDp / plan.pinnedScale).coerceIn(minUnits, pair - minUnits)
    return split.withPair(index, before.withTileSlot(before.weight, newBefore), after.withTileSlot(after.weight, pair - newBefore))
}

private fun <K> resizeMixedPair(split: TileSplit<K>, index: Int, deltaDp: Float, minSizeDp: Float, plan: TileSizePlan): TileSplit<K>? {
    if (plan.pinnedScale <= 0f) return null
    val before = split.children[index - 1]
    val after = split.children[index]
    val pinnedIsBefore = plan.pinned[index - 1]
    val pinned = if (pinnedIsBefore) before else after
    val units = pinned.pinnedDp() ?: return null
    val weights = split.children.indices.filter { !plan.collapsed[it] && !plan.pinned[it] }.map { split.children[it].weight }
    val smallest = weights.min()
    if (smallest <= 0f) return null
    // The weighted children together may shrink to the point where the lightest of them is still minSizeDp.
    val leastWeightedExtent = minSizeDp * weights.sumOf { it.toDouble() }.toFloat() / smallest
    val growth = max(0f, plan.weightedExtent - leastWeightedExtent) / plan.pinnedScale
    val lowest = min(units, minSizeDp / plan.pinnedScale)
    val signed = if (pinnedIsBefore) deltaDp else -deltaDp
    val resizedUnits = (units + signed / plan.pinnedScale).coerceIn(lowest, units + growth)
    val moved = pinned.withTileSlot(pinned.weight, resizedUnits)
    return if (pinnedIsBefore) split.withPair(index, moved, after) else split.withPair(index, before, moved)
}

/**
 * Resets the two siblings around gap [index] of the split at [path] (a double-click on the splitter): a sibling that has a
 * default size ([defaultDp] non-null) is pinned to it, and when both are weighted with no default they get equal weights
 * (their total is kept). A pinned sibling without a default is left as it is.
 */
internal fun <K> resetTilePair(root: TileNode<K>, path: List<Int>, index: Int, defaultDp: (TileNode<K>) -> Float?): TileNode<K> =
    updateSplitAt(root, path) { split ->
        if (index !in 1 until split.children.size) {
            split
        } else {
            val before = split.children[index - 1]
            val after = split.children[index]
            val beforeDp = defaultDp(before)
            val afterDp = defaultDp(after)
            val equalize = before.sizeDp == null && after.sizeDp == null && beforeDp == null && afterDp == null
            val share = (before.weight + after.weight) / 2

            fun reset(node: TileNode<K>, dp: Float?): TileNode<K> = when {
                dp != null -> node.withTileSlot(node.weight, dp)
                equalize -> node.withTileWeight(share)
                else -> node
            }
            split.withPair(index, reset(before, beforeDp), reset(after, afterDp))
        }
    }

// ---------------------------------------------------------------------------------------------------------------
// Projection (hidden tiles)
// ---------------------------------------------------------------------------------------------------------------

/**
 * A layout tree with the invisible leaves pruned, and the way back to the full tree. Splits left empty are dropped but a split
 * left with one child is KEPT, so the paths of the projected tree stay as stable as possible while tiles come and go.
 */
class TileProjection<K> internal constructor(val root: TileNode<K>, private val childMap: Map<List<Int>, List<Int>>) {
    /** The path in the full tree of the split at [projPath] in the projected tree. */
    fun fullPathOf(projPath: List<Int>): List<Int> =
        projPath.indices.map { i -> childMap[projPath.take(i)]?.getOrNull(projPath[i]) ?: projPath[i] }

    /** The index in the full tree of child [index] of the split at [projPath] in the projected tree. */
    fun fullIndexOf(projPath: List<Int>, index: Int): Int = childMap[projPath]?.getOrNull(index) ?: index
}

/** [root] without the leaves [isVisible] rejects; null when none is visible. */
internal fun <K> projectTileLayout(root: TileNode<K>, isVisible: (K) -> Boolean): TileProjection<K>? {
    val childMap = mutableMapOf<List<Int>, List<Int>>()
    val pruned = prune(root, emptyList(), isVisible, childMap) ?: return null
    return TileProjection(pruned, childMap)
}

private fun <K> prune(node: TileNode<K>, path: List<Int>, isVisible: (K) -> Boolean, childMap: MutableMap<List<Int>, List<Int>>): TileNode<K>? =
    when (node) {
        is TileLeaf -> node.takeIf { isVisible(it.section) }
        is TileSplit -> {
            val kept = mutableListOf<TileNode<K>>()
            val origin = mutableListOf<Int>()
            node.children.forEachIndexed { i, child ->
                prune(child, path + kept.size, isVisible, childMap)?.let {
                    kept += it
                    origin += i
                }
            }
            if (kept.isEmpty()) {
                null
            } else {
                childMap[path] = origin
                node.copy(children = kept)
            }
        }
    }

/**
 * [resizeTileSplit] for a drag on a [gap] of the projected tree's geometry: resizes the projection, then writes the two
 * siblings' new weights and sizes back into the matching siblings of [fullRoot]. Hidden siblings are untouched.
 */
internal fun <K> resizeProjected(
    fullRoot: TileNode<K>,
    projection: TileProjection<K>,
    gap: TileGap,
    deltaDp: Float,
    minSizeDp: Float,
    minPairShare: Float,
    collapsed: Set<K> = emptySet(),
    collapsedExtentDp: Float = TILE_COLLAPSED_EXTENT_DP,
): TileNode<K> {
    if (!gap.resizable) return fullRoot
    val resized = resizeTileSplit(projection.root, gap.path, gap.index, deltaDp, gap.splitExtent, minSizeDp, minPairShare, collapsed, collapsedExtentDp)
    return writeBackPair(fullRoot, projection, gap, resized)
}

/**
 * [resetTilePair] for a double-click on a [gap] of the projected tree's geometry: resets the projection, then writes the two
 * siblings' new weights and sizes back into the matching siblings of [fullRoot]. Hidden siblings are untouched.
 */
internal fun <K> resetProjected(
    fullRoot: TileNode<K>,
    projection: TileProjection<K>,
    gap: TileGap,
    defaultDp: (TileNode<K>) -> Float?,
): TileNode<K> {
    if (!gap.resizable) return fullRoot
    return writeBackPair(fullRoot, projection, gap, resetTilePair(projection.root, gap.path, gap.index, defaultDp))
}

// Copies the weights and pinned sizes of the two siblings around [gap] in [changed] (a change of the projected tree) to their
// counterparts in [fullRoot].
private fun <K> writeBackPair(fullRoot: TileNode<K>, projection: TileProjection<K>, gap: TileGap, changed: TileNode<K>): TileNode<K> {
    val split = nodeAt(changed, gap.path) as? TileSplit ?: return fullRoot
    val before = split.children.getOrNull(gap.index - 1) ?: return fullRoot
    val after = split.children.getOrNull(gap.index) ?: return fullRoot
    val beforeAt = projection.fullIndexOf(gap.path, gap.index - 1)
    val afterAt = projection.fullIndexOf(gap.path, gap.index)
    return updateSplitAt(fullRoot, projection.fullPathOf(gap.path)) { full ->
        full.copy(
            children = full.children.mapIndexed { i, c ->
                when (i) {
                    beforeAt -> c.withTileSlot(before.weight, before.sizeDp)
                    afterAt -> c.withTileSlot(after.weight, after.sizeDp)
                    else -> c
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Geometry
// ---------------------------------------------------------------------------------------------------------------

/**
 * Card rects and gaps for [root] laid out in a [width] x [height] dp host. Leaves in [collapsed] that sit in a COLUMN split
 * shrink to [TileGeometrySpec.collapsedExtentDp]; the gaps next to them are not resizable.
 */
internal fun <K> tileGeometry(root: TileNode<K>, width: Float, height: Float, spec: TileGeometrySpec, collapsed: Set<K> = emptySet()): TileGeometry<K> {
    val cards = mutableListOf<TileCardRect<K>>()
    val gaps = mutableListOf<TileGap>()
    layoutNode(root, TileRect(0f, 0f, width, height), spec, collapsed, emptyList(), cards, gaps)
    return TileGeometry(cards, gaps)
}

private fun <K> layoutNode(
    node: TileNode<K>,
    rect: TileRect,
    spec: TileGeometrySpec,
    collapsed: Set<K>,
    path: List<Int>,
    cards: MutableList<TileCardRect<K>>,
    gaps: MutableList<TileGap>,
) {
    when (node) {
        is TileLeaf -> cards += TileCardRect(node.section, rect)
        is TileSplit -> layoutSplit(node, rect, spec, collapsed, path, cards, gaps)
    }
}

private fun <K> layoutSplit(
    split: TileSplit<K>,
    rect: TileRect,
    spec: TileGeometrySpec,
    collapsed: Set<K>,
    path: List<Int>,
    cards: MutableList<TileCardRect<K>>,
    gaps: MutableList<TileGap>,
) {
    val horizontal = split.axis == TileAxis.ROW
    val gap = if (horizontal) spec.gapRowDp else spec.gapColumnDp
    val total = if (horizontal) rect.w else rect.h
    val extent = max(0f, total - gap * (split.children.size - 1))
    val plan = sizePlan(split, extent, spec.minTileDp, spec.collapsedExtentDp, collapsed)
    var cursor = if (horizontal) rect.x else rect.y
    split.children.forEachIndexed { i, child ->
        if (i > 0) {
            val strip = if (horizontal) TileRect(cursor, rect.y, gap, rect.h) else TileRect(rect.x, cursor, rect.w, gap)
            gaps += TileGap(path, i, split.axis, strip, extent, resizable = !plan.collapsed[i - 1] && !plan.collapsed[i])
            cursor += gap
        }
        val size = plan.sizes[i]
        val childRect = if (horizontal) TileRect(cursor, rect.y, size, rect.h) else TileRect(rect.x, cursor, rect.w, size)
        layoutNode(child, childRect, spec, collapsed, path + i, cards, gaps)
        cursor += size
    }
}

/** Geometry with [id] alone, filling the whole host (a maximized tile). */
internal fun <K> maximizedTileGeometry(id: K, width: Float, height: Float): TileGeometry<K> =
    TileGeometry(listOf(TileCardRect(id, TileRect(0f, 0f, width, height))), emptyList())

/** Geometry with the tiles of [order] stacked top to bottom, each [width] x [cardHeight] dp with [gap] dp between (narrow hosts). */
internal fun <K> stackedTileGeometry(order: List<K>, width: Float, cardHeight: Float, gap: Float): TileGeometry<K> =
    stackedTileGeometry(order, width, order.map { cardHeight }, gap)

/** As above with a height of its own for each tile of [order] ([heights], the same length). */
internal fun <K> stackedTileGeometry(order: List<K>, width: Float, heights: List<Float>, gap: Float): TileGeometry<K> {
    var y = 0f
    return TileGeometry(
        order.mapIndexed { i, id ->
            TileCardRect(id, TileRect(0f, y, width, heights[i])).also { y += heights[i] + gap }
        },
        emptyList(),
    )
}

/** "50% × 62%": [card]'s size as a share of the host. */
internal fun tileSizeLabel(card: TileRect, hostWidth: Float, hostHeight: Float): String {
    fun percent(part: Float, whole: Float) = if (whole > 0f) (part / whole * 100).roundToInt() else 0
    return "${percent(card.w, hostWidth)}% × ${percent(card.h, hostHeight)}%"
}

// ---------------------------------------------------------------------------------------------------------------
// Drop hit-testing, preview
// ---------------------------------------------------------------------------------------------------------------

/**
 * What a pointer at ([x], [y]) (host dp) would drop [dragged] on. The page bands win; otherwise the card under the
 * pointer, with the edge nearest to it. Null outside the host, over a gap and over the dragged card itself.
 */
internal fun <K> tileDropTargetAt(
    geometry: TileGeometry<K>,
    hostWidth: Float,
    hostHeight: Float,
    x: Float,
    y: Float,
    dragged: K,
    bandDp: Float = TILE_PAGE_BAND_DP,
): TileDropTarget<K>? {
    if (x < 0f || y < 0f || x > hostWidth || y > hostHeight) return null
    pageBandEdge(hostWidth, hostHeight, x, y, bandDp)?.let { return TileDropTarget.OnPage(it) }
    val card = geometry.cards.firstOrNull { it.rect.contains(x, y) } ?: return null
    if (card.section == dragged) return null
    return TileDropTarget.OnSection(card.section, nearestEdge(card.rect, x, y))
}

private fun pageBandEdge(hostWidth: Float, hostHeight: Float, x: Float, y: Float, band: Float): TileEdge? =
    listOf(
        TileEdge.LEFT to x,
        TileEdge.RIGHT to hostWidth - x,
        TileEdge.TOP to y,
        TileEdge.BOTTOM to hostHeight - y,
    ).filter { it.second < band }.minByOrNull { it.second }?.first

private fun nearestEdge(rect: TileRect, x: Float, y: Float): TileEdge {
    val dx = (x - rect.x) / rect.w
    val dy = (y - rect.y) / rect.h
    return listOf(TileEdge.LEFT to dx, TileEdge.RIGHT to 1f - dx, TileEdge.TOP to dy, TileEdge.BOTTOM to 1f - dy)
        .minBy { it.second }.first
}

/** The area the dropped tile would take, for the accent overlay; null when the target tile is not laid out. */
internal fun <K> tileDropPreview(geometry: TileGeometry<K>, hostWidth: Float, hostHeight: Float, target: TileDropTarget<K>): TileRect? = when (target) {
    is TileDropTarget.OnPage -> edgeShare(TileRect(0f, 0f, hostWidth, hostHeight), target.edge, TILE_PAGE_PREVIEW_SHARE)
    is TileDropTarget.OnSection -> geometry.cardOf(target.section)?.let { edgeShare(it, target.edge, TILE_SECTION_PREVIEW_SHARE) }
}

private fun edgeShare(rect: TileRect, edge: TileEdge, share: Float): TileRect = when (edge) {
    TileEdge.LEFT -> rect.copy(w = rect.w * share)
    TileEdge.RIGHT -> rect.copy(x = rect.right - rect.w * share, w = rect.w * share)
    TileEdge.TOP -> rect.copy(h = rect.h * share)
    TileEdge.BOTTOM -> rect.copy(y = rect.bottom - rect.h * share, h = rect.h * share)
}

// ---------------------------------------------------------------------------------------------------------------
// Sanitizing
// ---------------------------------------------------------------------------------------------------------------

internal fun sanitizeTileWeight(weight: Float): Float =
    if (!weight.isFinite() || weight <= 0f) 1f else weight.coerceIn(TILE_MIN_WEIGHT, TILE_MAX_WEIGHT)

/** A pinned size that is not a positive finite number becomes null (weighted); the rest is clamped. */
internal fun sanitizeTileSizeDp(sizeDp: Float?): Float? =
    sizeDp?.takeIf { it.isFinite() && it > 0f }?.coerceIn(TILE_MIN_SIZE_DP, TILE_MAX_SIZE_DP)

/**
 * The tree with the first occurrence of each leaf kept (later duplicates dropped, [seen] collects them), weights and pinned
 * sizes sanitized and empty splits removed; null when nothing is left. Callers normalize the result.
 */
internal fun <K> sanitizeTileTree(node: TileNode<K>, seen: MutableSet<K>): TileNode<K>? = when (node) {
    is TileLeaf ->
        if (seen.add(node.section)) node.copy(weight = sanitizeTileWeight(node.weight), sizeDp = sanitizeTileSizeDp(node.sizeDp)) else null
    is TileSplit -> node.children.mapNotNull { sanitizeTileTree(it, seen) }.takeIf { it.isNotEmpty() }
        ?.let { node.copy(children = it, weight = sanitizeTileWeight(node.weight), sizeDp = sanitizeTileSizeDp(node.sizeDp)) }
}
