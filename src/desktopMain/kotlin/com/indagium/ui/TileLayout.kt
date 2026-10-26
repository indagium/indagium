package com.indagium.ui

import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import kotlin.math.max
import kotlin.math.min

// The generic tiling engine behind the New tab and the log view: pure tree operations, geometry and drop hit-testing over a
// TileNode<K> tree whose leaves are identified by K. No Compose imports on purpose: the host's custom Layout, the drop
// overlay and the preset mini-icons all read the same TileGeometry, and everything here is unit-tested without a UI.
// HomeLayout.kt binds K to HomeSectionId and keeps the New tab's names; the settings codec is TileLayoutJson.kt.
//
// Sizing along a split's axis. A child is one of:
// - collapsed: a leaf of a COLUMN split that is in the `collapsed` set; it takes exactly collapsedExtentDp;
// - pinned: it has a sizeDp; it takes that many dp. If that would leave the weighted children less than minTileDp each, all
//   pinned sizes scale down together. When no weighted child is left, the pinned sizes act as weights over what remains;
// - weighted: the rest share what remains by weight.
//
// Scrolling columns. A COLUMN split whose leaves all satisfy the caller's `scrolls` predicate (the log view: the side sections, never
// the log or Video) does not have to fit its viewport. Its children's heights are their sizeDp (pinned: at least scrollMinDp), the
// weighted ones share what the pinned ones leave (minTileDp each at the least), and when everything is pinned and shorter than the
// viewport the last tile that is not collapsed takes the rest. The column's content (the heights plus the gaps) may be taller than
// the viewport; the host then scrolls it, and TileGeometry says so through a TileScrollRegion. A resize there changes only the tile
// above the gap (the weighted siblings are first pinned at the height they have), so nothing else shrinks and the tiles below move. The
// last tile has a gap of its own below it (TileGeometrySpec.scrollEndHandle), so it can be resized too. When the tiles report what their
// content needs (a TileSizing, see TileContentSizing.kt) they are as tall as that, within bounds, instead of sharing by weight.
//
// A tile alone where a column would be. A tile that sits directly in the top-level ROW (the root, or a ROW nested in it) has no column
// around it, so it would be laid out full height with the card stretched over empty space and no handle to size it. The caller names such
// tiles with `loneColumn` (the log view: the side sections); normalizeTileLayout then keeps each of them in a one-child COLUMN, the same
// shape a column has when its other tiles are hidden, and that column scrolls and resizes like any other. The leaf's own sizeDp is
// along its parent's axis, so a lone leaf could not store a height: the wrapper takes the leaf's weight and width pin, and the leaf inside
// keeps the height.

/** Width (dp) of the page-edge drop bands along the four host edges. They win over the card under the pointer. */
const val TILE_PAGE_BAND_DP: Float = 28f

const val TILE_MIN_WEIGHT: Float = 0.05f
const val TILE_MAX_WEIGHT: Float = 20f
const val TILE_MIN_SIZE_DP: Float = 1f
const val TILE_MAX_SIZE_DP: Float = 8000f

// A scrolling column scrolls only when its content is taller than the viewport by more than this.
private const val TILE_SCROLL_EPSILON_DP: Float = 0.5f

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

/**
 * A card's [rect]; [scrollRegion] is the index in [TileGeometry.scrollRegions] of the scrolling column it sits in (its rect is then in
 * unscrolled content coordinates).
 */
data class TileCardRect<K>(val section: K, val rect: TileRect, val scrollRegion: Int? = null)

/**
 * The gap between two siblings of a split.
 *
 * @property path child indexes from the root to the split node that owns the gap (empty = the root).
 * @property index the index, within that split, of the child AFTER the gap (the pair resized is index - 1 and index).
 * @property axis the split's axis: for ROW the gap is a vertical strip, for COLUMN a horizontal one.
 * @property rect the gap strip itself, which is also the splitter hit area.
 * @property splitExtent the split's extent along [axis] in dp (its size minus all its gaps); it is what a resize needs to
 * turn a drag of d dp into weight terms.
 * @property resizable false when a neighbour is collapsed: dragging that gap does nothing (in a scrolling column: when the tile above is collapsed).
 * @property scrolling true when the split that owns the gap is a scrolling column: a resize changes only the tile above the gap. The gap
 * below the last tile of such a column (when it has one) has [index] = the number of children.
 * @property scrollRegion the index in [TileGeometry.scrollRegions] of the scrolling column the gap lies in (its rect is unscrolled).
 */
data class TileGap(
    val path: List<Int>,
    val index: Int,
    val axis: TileAxis,
    val rect: TileRect,
    val splitExtent: Float,
    val resizable: Boolean = true,
    val scrolling: Boolean = false,
    val scrollRegion: Int? = null,
)

/**
 * A scrolling column: [key] is its first leaf (stable while the column is), [viewport] the rect it shows and [contentHeight] the
 * height of its children plus gaps. Rects of the cards and gaps inside are in unscrolled content coordinates (the viewport's top is
 * content offset 0); the host shifts them by the scroll offset. [roomRightDp] is the width of the gap beyond the viewport's right edge
 * (the column gap to its neighbour, or the room the surface keeps at the host's edge): the column's scrollbar is drawn there, so
 * the cards themselves never give up any width for it.
 */
data class TileScrollRegion<K>(val key: K, val viewport: TileRect, val contentHeight: Float, val roomRightDp: Float = 0f) {
    /** How far the content can scroll (dp); 0 while it fits. */
    val maxOffset: Float get() = if (contentHeight - viewport.h > TILE_SCROLL_EPSILON_DP) contentHeight - viewport.h else 0f

    val scrolls: Boolean get() = maxOffset > 0f
}

data class TileGeometry<K>(val cards: List<TileCardRect<K>>, val gaps: List<TileGap>, val scrollRegions: List<TileScrollRegion<K>> = emptyList()) {
    fun cardOf(section: K): TileRect? = cards.firstOrNull { it.section == section }?.rect

    /**
     * The cards as they are on screen with each scrolling column scrolled by [offsetOf]: shifted, cut to the column's viewport, and
     * dropped when nothing of them shows. Gaps are left out. This is what drop hit-testing and the drop preview use.
     */
    fun visibleCards(offsetOf: (TileScrollRegion<K>) -> Float): TileGeometry<K> {
        if (scrollRegions.isEmpty()) return TileGeometry(cards, emptyList(), scrollRegions)
        val shown = cards.mapNotNull { card ->
            val region = card.scrollRegion?.let { scrollRegions.getOrNull(it) } ?: return@mapNotNull card
            val shifted = card.rect.copy(y = card.rect.y - offsetOf(region))
            val top = max(shifted.y, region.viewport.y)
            val bottom = min(shifted.bottom, region.viewport.bottom)
            if (bottom - top <= 0f) null else TileCardRect(card.section, shifted.copy(y = top, h = bottom - top))
        }
        return TileGeometry(shown, emptyList(), scrollRegions)
    }
}

/**
 * What [tileGeometry] needs besides the tree. [gapRowDp] separates side-by-side children (a ROW split), [gapColumnDp]
 * stacked ones (a COLUMN split). [minTileDp] is the least a weighted child is squeezed to by pinned siblings and the least a
 * resize leaves a tile along the split axis; [minPairShare] is the least share of a pair's total weight a resize leaves.
 * [scrollMinDp] / [scrollMaxDp] bound the height of a pinned tile in a scrolling column. [scrollEdgeRoomDp] is the room the surface keeps
 * beyond the host's right edge (outside the width the geometry is laid out in): a scrolling column that ends at that edge draws its scrollbar
 * there, one that has a neighbour draws it in the [gapRowDp] gap, so the cards of a column are the same width whether or not it overflows.
 * [scrollEndHandle] adds a gap below its last tile, the splitter that resizes that tile.
 */
data class TileGeometrySpec(
    val gapRowDp: Float,
    val gapColumnDp: Float,
    val collapsedExtentDp: Float = TILE_COLLAPSED_EXTENT_DP,
    val minTileDp: Float,
    val minPairShare: Float,
    val scrollMinDp: Float = minTileDp,
    val scrollMaxDp: Float = TILE_MAX_SIZE_DP,
    val scrollEdgeRoomDp: Float = 0f,
    val scrollEndHandle: Boolean = false,
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
 *
 * Tiles for which [loneColumn] holds are the exception to the single-child rule: wherever one sits alone where a column would be (a
 * direct child of the top-level ROW, or the root itself) it is kept in a one-child COLUMN split, which is created when it is missing, so
 * the tile behaves as a one-tile scrolling column and its height (a pin on the leaf inside) outlives later edits. The default holds for no tile.
 */
internal fun <K> normalizeTileLayout(node: TileNode<K>, loneColumn: (K) -> Boolean = { false }): TileNode<K>? =
    normalizeNode(node, loneColumn, top = true)?.let { wrapLoneTiles(it, loneColumn) }

// [top] is true while [node] is the root or a child of a ROW that is itself top: the places where a lone tile is a column of its own.
private fun <K> normalizeNode(node: TileNode<K>, loneColumn: (K) -> Boolean, top: Boolean): TileNode<K>? = when (node) {
    is TileLeaf -> node
    is TileSplit -> normalizeSplit(node, loneColumn, top)
}

private fun <K> normalizeSplit(split: TileSplit<K>, loneColumn: (K) -> Boolean, top: Boolean): TileNode<K>? {
    val children = split.children.mapNotNull { normalizeNode(it, loneColumn, top && split.axis == TileAxis.ROW) }
    return when {
        children.isEmpty() -> null
        children.size == 1 -> {
            val only = children.single()
            if (top && split.axis == TileAxis.COLUMN && only is TileLeaf && loneColumn(only.section)) {
                split.copy(children = children)
            } else {
                only.withTileSlot(split.weight, split.sizeDp)
            }
        }
        else -> split.copy(
            children = children.flatMap { if (it is TileSplit && it.axis == split.axis && it.sizeDp == null) flattenedChildren(it) else listOf(it) },
        )
    }
}

// Puts every lone tile (see normalizeTileLayout) that is not in a column yet into one: the column takes the leaf's weight and its pin
// (both along the parent's axis), the leaf inside is weighted. A COLUMN is left alone: its tiles share it.
private fun <K> wrapLoneTiles(node: TileNode<K>, loneColumn: (K) -> Boolean): TileNode<K> = when {
    node is TileLeaf && loneColumn(node.section) -> TileSplit(TileAxis.COLUMN, listOf(node.withTileSlot(1f, null)), node.weight, node.sizeDp)
    node is TileSplit && node.axis == TileAxis.ROW -> node.copy(children = node.children.map { wrapLoneTiles(it, loneColumn) })
    else -> node
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

/** The tree without [section], normalized (see [normalizeTileLayout] for [loneColumn]); null when [section] was the last one. */
internal fun <K> removeTile(root: TileNode<K>, section: K, loneColumn: (K) -> Boolean = { false }): TileNode<K>? =
    removeLeaf(root, section)?.let { normalizeTileLayout(it, loneColumn) }

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
 * A tile that sits alone in a one-child column (see [normalizeTileLayout]) counts as that column when something is dropped on its left or
 * right: the dragged tile goes beside the column, not into it.
 * Dropping on itself, or onto a tile that is not in the tree, returns [root] unchanged. The result is normalized with [loneColumn].
 */
internal fun <K> dropTile(root: TileNode<K>, dragged: K, target: TileDropTarget<K>, loneColumn: (K) -> Boolean = { false }): TileNode<K> {
    if (target is TileDropTarget.OnSection && target.section == dragged) return root
    val rest = removeTile(root, dragged, loneColumn) ?: return root
    val placed = when (target) {
        is TileDropTarget.OnSection ->
            if (target.section in tileLeafSections(rest)) insertAtSection(rest, target.section, dragged, target.edge) else return root
        is TileDropTarget.OnPage -> insertAtPageEdge(rest, dragged, target.edge)
    }
    return normalizeTileLayout(placed, loneColumn) ?: root
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
        val at = node.children.indexOfFirst { holdsOnly(it, target, edge.axis) }
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

// True when [node] is [target] or a one-child column around it (a lone tile, see normalizeTileLayout) that a drop along [axis] goes beside.
private fun <K> holdsOnly(node: TileNode<K>, target: K, axis: TileAxis): Boolean = when (node) {
    is TileLeaf -> node.section == target
    is TileSplit -> node.axis != axis && (node.children.singleOrNull() as? TileLeaf)?.section == target
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
internal fun <K> appendTile(root: TileNode<K>, section: K, loneColumn: (K) -> Boolean = { false }): TileNode<K> {
    val edge = if (root is TileSplit && root.axis == TileAxis.COLUMN) TileEdge.BOTTOM else TileEdge.RIGHT
    return dropTile(root, section, TileDropTarget.OnPage(edge), loneColumn)
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

// True when [split] is a column whose leaves all satisfy [scrolls]: it keeps its children's heights and scrolls instead of fitting.
private fun <K> isScrollingColumn(split: TileSplit<K>, scrolls: (K) -> Boolean): Boolean =
    split.axis == TileAxis.COLUMN && tileLeafSections(split).all(scrolls)

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
 *
 * With a [scrollRange] (the split is a scrolling column, [TileGap.scrolling]) only the tile above the gap changes: its sizeDp becomes its
 * current height plus [deltaDp] within the range, and every weighted sibling is pinned at the height it has now, so no other tile
 * changes size. A gap under a collapsed tile does nothing. The split's pinned heights are at least the range's start. A tile that has a
 * [sizing] stays within its own [TileSizing.minDp]..[TileSizing.maxDp] (inside the range), and only the siblings whose height depends on
 * the others are pinned.
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
    scrollRange: ClosedFloatingPointRange<Float>? = null,
    sizing: (K) -> TileSizing? = { null },
): TileNode<K> {
    if (extentDp <= 0f || !deltaDp.isFinite()) return root
    return updateSplitAt(root, path) { split ->
        if (scrollRange != null) {
            val plan = scrollPlan(split, extentDp, minSizeDp, scrollRange.start, collapsedExtentDp, collapsed, sizing)
            resizeScrolled(split, index, deltaDp, plan, scrollRange)
        } else {
            resizePair(split, index, deltaDp, minSizeDp, minPairShare, sizePlan(split, extentDp, minSizeDp, collapsedExtentDp, collapsed))
        }
    }
}

private fun <K> resizeScrolled(split: TileSplit<K>, index: Int, deltaDp: Float, plan: TileScrollPlan, range: ClosedFloatingPointRange<Float>): TileSplit<K> {
    val above = index - 1
    if (index !in 1..split.children.size || plan.collapsed[above]) return split
    val own = plan.sizing[above]
    val low = own?.minDp ?: range.start
    val high = own?.hiDp?.coerceAtMost(range.endInclusive) ?: range.endInclusive
    val resized = (plan.sizes[above] + deltaDp).coerceIn(low, max(low, high))
    return split.copy(
        children = split.children.mapIndexed { i, c ->
            when {
                i == above -> c.withTileSlot(c.weight, resized)
                plan.dependent[i] -> c.withTileSlot(c.weight, plan.sizes[i])
                else -> c
            }
        },
    )
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
 * (their total is kept). A pinned sibling without a default is left as it is. With [scrolling] only the sibling above the gap
 * is reset: to its default height, or to weighted when it has none.
 */
internal fun <K> resetTilePair(
    root: TileNode<K>,
    path: List<Int>,
    index: Int,
    scrolling: Boolean = false,
    defaultDp: (TileNode<K>) -> Float?,
): TileNode<K> =
    updateSplitAt(root, path) { split ->
        if (scrolling && index in 1..split.children.size) {
            // In a scrolling column the gap belongs to the tile above it: back to its default height, or back to weighted.
            split.copy(
                children = split.children.mapIndexed { i, c -> if (i == index - 1) c.withTileSlot(c.weight, defaultDp(c)) else c },
            )
        } else if (index !in 1 until split.children.size) {
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
 * siblings' new weights and sizes back into the matching siblings of [fullRoot]. Hidden siblings are untouched. A scrolling gap
 * ([TileGap.scrolling]) can change every visible sibling's pinned size, so all of them are written back.
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
    scrollMinDp: Float = minSizeDp,
    scrollMaxDp: Float = TILE_MAX_SIZE_DP,
    sizing: (K) -> TileSizing? = { null },
): TileNode<K> {
    if (!gap.resizable) return fullRoot
    val scrollRange = if (gap.scrolling) scrollMinDp..scrollMaxDp else null
    val resized = resizeTileSplit(
        projection.root, gap.path, gap.index, deltaDp, gap.splitExtent, minSizeDp, minPairShare, collapsed, collapsedExtentDp, scrollRange, sizing,
    )
    return if (gap.scrolling) writeBackSiblings(fullRoot, projection, gap, resized) else writeBackPair(fullRoot, projection, gap, resized)
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
    val reset = resetTilePair(projection.root, gap.path, gap.index, gap.scrolling, defaultDp)
    // A scrolling gap may be the one under the column's last tile, which has no sibling after it.
    return if (gap.scrolling) writeBackSiblings(fullRoot, projection, gap, reset) else writeBackPair(fullRoot, projection, gap, reset)
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

// Copies the weights and pinned sizes of every child of the split that owns [gap] in [changed] to its counterpart in [fullRoot].
private fun <K> writeBackSiblings(fullRoot: TileNode<K>, projection: TileProjection<K>, gap: TileGap, changed: TileNode<K>): TileNode<K> {
    val split = nodeAt(changed, gap.path) as? TileSplit ?: return fullRoot
    val projected = split.children.indices.associateBy { projection.fullIndexOf(gap.path, it) }
    return updateSplitAt(fullRoot, projection.fullPathOf(gap.path)) { full ->
        full.copy(
            children = full.children.mapIndexed { i, c ->
                projected[i]?.let { split.children[it] }?.let { c.withTileSlot(it.weight, it.sizeDp) } ?: c
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Geometry
// ---------------------------------------------------------------------------------------------------------------

/**
 * Card rects and gaps for [root] laid out in a [width] x [height] dp host. Leaves in [collapsed] that sit in a COLUMN split
 * shrink to [TileGeometrySpec.collapsedExtentDp]; the gaps next to them are not resizable. A COLUMN split whose leaves all
 * satisfy [scrolls] is a scrolling column (see the header comment): it keeps its children's heights, may be taller than its
 * rect, and is reported in [TileGeometry.scrollRegions]. Splits nested inside one are laid out normally in their children's rects. [sizing]
 * tells what the content of a leaf asks of its height in a scrolling column (null = not known).
 */
internal fun <K> tileGeometry(
    root: TileNode<K>,
    width: Float,
    height: Float,
    spec: TileGeometrySpec,
    collapsed: Set<K> = emptySet(),
    scrolls: (K) -> Boolean = { false },
    sizing: (K) -> TileSizing? = { null },
): TileGeometry<K> {
    val layout = TileLayoutRun(spec, collapsed, scrolls, sizing, width)
    layout.node(root, TileRect(0f, 0f, width, height), emptyList(), null)
    return TileGeometry(layout.cards, layout.gaps, layout.regions)
}

// One geometry computation: the settings it runs with and the lists it fills. [region] is the scrolling column a node sits in, if any.
private class TileLayoutRun<K>(
    val spec: TileGeometrySpec,
    val collapsed: Set<K>,
    val scrolls: (K) -> Boolean,
    val sizing: (K) -> TileSizing?,
    val hostWidth: Float,
) {
    val cards = mutableListOf<TileCardRect<K>>()
    val gaps = mutableListOf<TileGap>()
    val regions = mutableListOf<TileScrollRegion<K>>()

    fun node(node: TileNode<K>, rect: TileRect, path: List<Int>, region: Int?) {
        when (node) {
            is TileLeaf -> cards += TileCardRect(node.section, rect, region)
            is TileSplit -> if (region == null && isScrollingColumn(node, scrolls)) scrollingColumn(node, rect, path) else split(node, rect, path, region)
        }
    }

    private fun split(split: TileSplit<K>, rect: TileRect, path: List<Int>, region: Int?) {
        val horizontal = split.axis == TileAxis.ROW
        val gap = if (horizontal) spec.gapRowDp else spec.gapColumnDp
        val total = if (horizontal) rect.w else rect.h
        val extent = max(0f, total - gap * (split.children.size - 1))
        val plan = sizePlan(split, extent, spec.minTileDp, spec.collapsedExtentDp, collapsed)
        var cursor = if (horizontal) rect.x else rect.y
        split.children.forEachIndexed { i, child ->
            if (i > 0) {
                val strip = if (horizontal) TileRect(cursor, rect.y, gap, rect.h) else TileRect(rect.x, cursor, rect.w, gap)
                gaps += TileGap(path, i, split.axis, strip, extent, resizable = !plan.collapsed[i - 1] && !plan.collapsed[i], scrollRegion = region)
                cursor += gap
            }
            val size = plan.sizes[i]
            val childRect = if (horizontal) TileRect(cursor, rect.y, size, rect.h) else TileRect(rect.x, cursor, rect.w, size)
            node(child, childRect, path + i, region)
            cursor += size
        }
    }

    private fun scrollingColumn(split: TileSplit<K>, rect: TileRect, path: List<Int>) {
        val gap = spec.gapColumnDp
        val endGap = if (spec.scrollEndHandle) gap else 0f
        val gapTotal = gap * (split.children.size - 1) + endGap
        val extent = max(0f, rect.h - gapTotal)
        val plan = scrollPlan(split, extent, spec.minTileDp, spec.scrollMinDp, spec.collapsedExtentDp, collapsed, sizing)
        val region = regions.size
        // The scrollbar lives beyond the column's right edge: in the gap to the next column, or at the host's edge in the room kept there.
        val room = if (rect.right >= hostWidth - TILE_SCROLL_EPSILON_DP) spec.scrollEdgeRoomDp else spec.gapRowDp
        regions += TileScrollRegion(tileLeafSections(split).first(), rect, plan.sizes.sumOf { it.toDouble() }.toFloat() + gapTotal, room)
        val width = rect.w

        fun resizable(tile: Int): Boolean = !plan.collapsed[tile] && plan.sizing[tile]?.resizable != false

        var cursor = rect.y
        split.children.forEachIndexed { i, child ->
            if (i > 0) {
                val strip = TileRect(rect.x, cursor, width, gap)
                gaps += TileGap(path, i, split.axis, strip, extent, resizable(i - 1), scrolling = true, scrollRegion = region)
                cursor += gap
            }
            node(child, TileRect(rect.x, cursor, width, plan.sizes[i]), path + i, region)
            cursor += plan.sizes[i]
        }
        if (endGap > 0f) {
            val count = split.children.size
            val strip = TileRect(rect.x, cursor, width, gap)
            gaps += TileGap(path, count, split.axis, strip, extent, resizable(count - 1), scrolling = true, scrollRegion = region)
        }
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
