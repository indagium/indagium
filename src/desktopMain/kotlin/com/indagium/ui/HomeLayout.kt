package com.indagium.ui

import com.indagium.model.AppSettings
import com.indagium.model.DEFAULT_HOME_LAYOUT
import com.indagium.model.HomeLayoutLeaf
import com.indagium.model.HomeLayoutNode
import com.indagium.model.HomeLayoutSplit
import com.indagium.model.HomeSectionId
import com.indagium.model.HomeSplitAxis
import kotlin.math.max
import kotlin.math.roundToInt

// The New tab's tiling layout: pure tree operations, geometry and drop hit-testing. No Compose imports on purpose: the
// host's custom Layout, the drop overlay, the size labels and the preset mini-icons all read the same HomeGeometry, and
// everything here is unit-tested without a UI. The settings codec lives in HomeLayoutSettingsJson.kt (AutosaveCodec.kt is
// at detekt's per-file function limit).

/** Width (dp) of the page-edge drop bands along the four host edges. They win over the card under the pointer. */
const val HOME_PAGE_BAND_DP: Float = 28f

/** A resize can never leave a neighbour pair with less than this share of the pair's total weight. */
const val HOME_MIN_PAIR_SHARE: Float = 0.18f

/** A resize can never shrink a section below this many dp along the split axis. */
const val HOME_MIN_SECTION_DP: Float = 200f

const val HOME_MIN_WEIGHT: Float = 0.05f
const val HOME_MAX_WEIGHT: Float = 20f

// A page-edge drop onto a root of the other axis keeps the old content at this weight against the dragged section's 1.
private const val HOME_PAGE_EDGE_OLD_WEIGHT: Float = 1.6f

// Share of the host / of a card the drop preview covers.
private const val HOME_PAGE_PREVIEW_SHARE: Float = 0.38f
private const val HOME_SECTION_PREVIEW_SHARE: Float = 0.5f

/** Side of a host or card a section is dropped on. */
enum class HomeEdge(val axis: HomeSplitAxis, val leading: Boolean) {
    LEFT(HomeSplitAxis.ROW, true),
    RIGHT(HomeSplitAxis.ROW, false),
    TOP(HomeSplitAxis.COLUMN, true),
    BOTTOM(HomeSplitAxis.COLUMN, false),
}

sealed interface HomeDropTarget {
    /** Dropped on [edge] of another visible section. */
    data class OnSection(val section: HomeSectionId, val edge: HomeEdge) : HomeDropTarget

    /** Dropped on [edge] of the whole page. */
    data class OnPage(val edge: HomeEdge) : HomeDropTarget
}

/** A rectangle in dp, origin top-left. The right and bottom edges are exclusive for [contains]. */
data class HomeRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h

    fun contains(px: Float, py: Float): Boolean = px >= x && px < right && py >= y && py < bottom
}

data class HomeCardRect(val section: HomeSectionId, val rect: HomeRect)

/**
 * The gap between two siblings of a split.
 *
 * @property path child indexes from the root to the split node that owns the gap (empty = the root).
 * @property index the index, within that split, of the child AFTER the gap (the pair resized is index - 1 and index).
 * @property axis the split's axis: for ROW the gap is a vertical strip, for COLUMN a horizontal one.
 * @property rect the gap strip itself, which is also the splitter hit area.
 * @property splitExtent the split's extent along [axis] in dp that is shared out by weights (its size minus all its gaps);
 * a drag of d dp is d / splitExtent * (the split's total child weight) in weight terms.
 */
data class HomeGap(
    val path: List<Int>,
    val index: Int,
    val axis: HomeSplitAxis,
    val rect: HomeRect,
    val splitExtent: Float,
)

data class HomeGeometry(val cards: List<HomeCardRect>, val gaps: List<HomeGap>) {
    fun cardOf(section: HomeSectionId): HomeRect? = cards.firstOrNull { it.section == section }?.rect
}

// ---------------------------------------------------------------------------------------------------------------
// Tree basics
// ---------------------------------------------------------------------------------------------------------------

internal fun HomeLayoutNode.withWeight(weight: Float): HomeLayoutNode = when (this) {
    is HomeLayoutLeaf -> copy(weight = weight)
    is HomeLayoutSplit -> copy(weight = weight)
}

/** The sections in the tree, depth first, in display order. */
internal fun homeLeafSections(node: HomeLayoutNode): List<HomeSectionId> = when (node) {
    is HomeLayoutLeaf -> listOf(node.section)
    is HomeLayoutSplit -> node.children.flatMap { homeLeafSections(it) }
}

/** True when both trees have the same arrangement of sections; weights are ignored (used to highlight the active preset). */
internal fun homeLayoutShapeEquals(a: HomeLayoutNode, b: HomeLayoutNode): Boolean = when {
    a is HomeLayoutLeaf && b is HomeLayoutLeaf -> a.section == b.section
    a is HomeLayoutSplit && b is HomeLayoutSplit ->
        a.axis == b.axis && a.children.size == b.children.size &&
            a.children.indices.all { homeLayoutShapeEquals(a.children[it], b.children[it]) }
    else -> false
}

/**
 * Tidies a tree: drops empty splits, collapses a single-child split into that child (keeping the split's weight) and
 * flattens a child split that has the same axis as its parent, rescaling so the parent's shares are unchanged.
 * Returns null when nothing is left.
 */
internal fun normalizeHomeLayout(node: HomeLayoutNode): HomeLayoutNode? = when (node) {
    is HomeLayoutLeaf -> node
    is HomeLayoutSplit -> normalizeSplit(node)
}

private fun normalizeSplit(split: HomeLayoutSplit): HomeLayoutNode? {
    val children = split.children.mapNotNull { normalizeHomeLayout(it) }
    return when {
        children.isEmpty() -> null
        children.size == 1 -> children.single().withWeight(split.weight)
        else -> split.copy(children = children.flatMap { if (it is HomeLayoutSplit && it.axis == split.axis) flattenedChildren(it) else listOf(it) })
    }
}

// A same-axis child split's children, rescaled so together they keep the weight the child split had.
private fun flattenedChildren(child: HomeLayoutSplit): List<HomeLayoutNode> {
    val sum = child.children.sumOf { it.weight.toDouble() }.toFloat()
    return child.children.map { it.withWeight(if (sum > 0f) it.weight * child.weight / sum else child.weight / child.children.size) }
}

/** The tree without [section], normalized; null when [section] was the last one. */
internal fun removeHomeSection(root: HomeLayoutNode, section: HomeSectionId): HomeLayoutNode? =
    removeLeaf(root, section)?.let { normalizeHomeLayout(it) }

private fun removeLeaf(node: HomeLayoutNode, section: HomeSectionId): HomeLayoutNode? = when (node) {
    is HomeLayoutLeaf -> if (node.section == section) null else node
    is HomeLayoutSplit -> node.copy(children = node.children.mapNotNull { removeLeaf(it, section) })
}

// ---------------------------------------------------------------------------------------------------------------
// Drop
// ---------------------------------------------------------------------------------------------------------------

/**
 * Moves [dragged] onto [target]. The dragged section is removed first, then:
 * - on a section edge whose axis equals the target's parent split: inserted beside the target, splitting the target's weight in half;
 * - on a section edge of the other axis: the target is wrapped in a new split of that axis, keeping the target's weight;
 * - on a page edge: added to the root (average sibling weight) when the root has that axis or is a single section,
 *   otherwise a new root [old root 1.6, dragged 1] in edge order.
 * Dropping on itself, or onto a section that is not in the tree, returns [root] unchanged. The result is normalized.
 */
internal fun dropHomeSection(root: HomeLayoutNode, dragged: HomeSectionId, target: HomeDropTarget): HomeLayoutNode {
    if (target is HomeDropTarget.OnSection && target.section == dragged) return root
    val rest = removeHomeSection(root, dragged) ?: return root
    val placed = when (target) {
        is HomeDropTarget.OnSection ->
            if (target.section in homeLeafSections(rest)) insertAtSection(rest, target.section, dragged, target.edge) else return root
        is HomeDropTarget.OnPage -> insertAtPageEdge(rest, dragged, target.edge)
    }
    return normalizeHomeLayout(placed) ?: root
}

private fun ordered(edge: HomeEdge, dragged: HomeLayoutNode, other: HomeLayoutNode): List<HomeLayoutNode> =
    if (edge.leading) listOf(dragged, other) else listOf(other, dragged)

private fun insertAtSection(node: HomeLayoutNode, target: HomeSectionId, dragged: HomeSectionId, edge: HomeEdge): HomeLayoutNode = when (node) {
    is HomeLayoutLeaf ->
        if (node.section == target) HomeLayoutSplit(edge.axis, ordered(edge, HomeLayoutLeaf(dragged), node.withWeight(1f)), node.weight) else node
    is HomeLayoutSplit -> {
        val at = node.children.indexOfFirst { it is HomeLayoutLeaf && it.section == target }
        if (at >= 0 && node.axis == edge.axis) {
            val half = node.children[at].weight / 2
            val pair = ordered(edge, HomeLayoutLeaf(dragged, half), node.children[at].withWeight(half))
            node.copy(children = node.children.take(at) + pair + node.children.drop(at + 1))
        } else {
            node.copy(children = node.children.map { insertAtSection(it, target, dragged, edge) })
        }
    }
}

private fun insertAtPageEdge(rest: HomeLayoutNode, dragged: HomeSectionId, edge: HomeEdge): HomeLayoutNode {
    val sameAxis = rest is HomeLayoutLeaf || (rest as HomeLayoutSplit).axis == edge.axis
    if (!sameAxis) {
        return HomeLayoutSplit(edge.axis, ordered(edge, HomeLayoutLeaf(dragged), rest.withWeight(HOME_PAGE_EDGE_OLD_WEIGHT)))
    }
    val base = rest as? HomeLayoutSplit ?: HomeLayoutSplit(edge.axis, listOf(rest), rest.weight)
    val average = base.children.map { it.weight }.average().toFloat()
    val added = HomeLayoutLeaf(dragged, average)
    return base.copy(children = if (edge.leading) listOf(added) + base.children else base.children + added)
}

/** Adds [section] at the trailing page edge: right of a ROW root or a single section, below a COLUMN root. */
internal fun appendHomeSection(root: HomeLayoutNode, section: HomeSectionId): HomeLayoutNode {
    val edge = if (root is HomeLayoutSplit && root.axis == HomeSplitAxis.COLUMN) HomeEdge.BOTTOM else HomeEdge.RIGHT
    return dropHomeSection(root, section, HomeDropTarget.OnPage(edge))
}

// ---------------------------------------------------------------------------------------------------------------
// Resize
// ---------------------------------------------------------------------------------------------------------------

/**
 * Moves the gap before child [index] of the split at [path] by [deltaDp] (positive = towards the end). Only the two
 * neighbours change and their total weight stays constant. Each keeps at least [HOME_MIN_PAIR_SHARE] of the pair and
 * [minSizeDp]. [extentDp] is [HomeGap.splitExtent]. Invalid input returns [root] unchanged.
 */
internal fun resizeHomeSplit(
    root: HomeLayoutNode,
    path: List<Int>,
    index: Int,
    deltaDp: Float,
    extentDp: Float,
    minSizeDp: Float = HOME_MIN_SECTION_DP,
): HomeLayoutNode {
    if (extentDp <= 0f || !deltaDp.isFinite()) return root
    return updateSplitAt(root, path) { split -> resizePair(split, index, deltaDp, extentDp, minSizeDp) }
}

private fun updateSplitAt(node: HomeLayoutNode, path: List<Int>, update: (HomeLayoutSplit) -> HomeLayoutSplit): HomeLayoutNode {
    if (node !is HomeLayoutSplit) return node
    if (path.isEmpty()) return update(node)
    val at = path.first()
    if (at !in node.children.indices) return node
    return node.copy(children = node.children.mapIndexed { i, c -> if (i == at) updateSplitAt(c, path.drop(1), update) else c })
}

private fun resizePair(split: HomeLayoutSplit, index: Int, deltaDp: Float, extentDp: Float, minSizeDp: Float): HomeLayoutSplit {
    if (index !in 1 until split.children.size) return split
    val before = split.children[index - 1]
    val after = split.children[index]
    val pair = before.weight + after.weight
    val weightPerDp = split.children.sumOf { it.weight.toDouble() }.toFloat() / extentDp
    val minWeight = max(pair * HOME_MIN_PAIR_SHARE, minSizeDp * weightPerDp)
    if (minWeight * 2 > pair) return split
    val newBefore = (before.weight + deltaDp * weightPerDp).coerceIn(minWeight, pair - minWeight)
    return split.copy(
        children = split.children.mapIndexed { i, c ->
            when (i) {
                index - 1 -> c.withWeight(newBefore)
                index -> c.withWeight(pair - newBefore)
                else -> c
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Geometry
// ---------------------------------------------------------------------------------------------------------------

/** Card rects and gaps for [root] laid out in a [width] x [height] dp host with [gap] dp between siblings. */
internal fun homeGeometry(root: HomeLayoutNode, width: Float, height: Float, gap: Float): HomeGeometry {
    val cards = mutableListOf<HomeCardRect>()
    val gaps = mutableListOf<HomeGap>()
    layoutNode(root, HomeRect(0f, 0f, width, height), gap, emptyList(), cards, gaps)
    return HomeGeometry(cards, gaps)
}

private fun layoutNode(
    node: HomeLayoutNode,
    rect: HomeRect,
    gap: Float,
    path: List<Int>,
    cards: MutableList<HomeCardRect>,
    gaps: MutableList<HomeGap>,
) {
    when (node) {
        is HomeLayoutLeaf -> cards += HomeCardRect(node.section, rect)
        is HomeLayoutSplit -> layoutSplit(node, rect, gap, path, cards, gaps)
    }
}

private fun layoutSplit(
    split: HomeLayoutSplit,
    rect: HomeRect,
    gap: Float,
    path: List<Int>,
    cards: MutableList<HomeCardRect>,
    gaps: MutableList<HomeGap>,
) {
    val horizontal = split.axis == HomeSplitAxis.ROW
    val total = if (horizontal) rect.w else rect.h
    val extent = max(0f, total - gap * (split.children.size - 1))
    val weightSum = split.children.sumOf { it.weight.toDouble() }.toFloat()
    var cursor = if (horizontal) rect.x else rect.y
    split.children.forEachIndexed { i, child ->
        if (i > 0) {
            val strip = if (horizontal) HomeRect(cursor, rect.y, gap, rect.h) else HomeRect(rect.x, cursor, rect.w, gap)
            gaps += HomeGap(path, i, split.axis, strip, extent)
            cursor += gap
        }
        val size = if (weightSum > 0f) extent * child.weight / weightSum else extent / split.children.size
        val childRect = if (horizontal) HomeRect(cursor, rect.y, size, rect.h) else HomeRect(rect.x, cursor, rect.w, size)
        layoutNode(child, childRect, gap, path + i, cards, gaps)
        cursor += size
    }
}

/** "50% × 62%": [card]'s size as a share of the host. */
internal fun homeSizeLabel(card: HomeRect, hostWidth: Float, hostHeight: Float): String {
    fun percent(part: Float, whole: Float) = if (whole > 0f) (part / whole * 100).roundToInt() else 0
    return "${percent(card.w, hostWidth)}% × ${percent(card.h, hostHeight)}%"
}

// ---------------------------------------------------------------------------------------------------------------
// Drop hit-testing, preview, label
// ---------------------------------------------------------------------------------------------------------------

/**
 * What a pointer at ([x], [y]) (host dp) would drop [dragged] on. The page bands win; otherwise the card under the
 * pointer, with the edge nearest to it. Null outside the host, over a gap and over the dragged card itself.
 */
internal fun homeDropTargetAt(
    geometry: HomeGeometry,
    hostWidth: Float,
    hostHeight: Float,
    x: Float,
    y: Float,
    dragged: HomeSectionId,
    bandDp: Float = HOME_PAGE_BAND_DP,
): HomeDropTarget? {
    if (x < 0f || y < 0f || x > hostWidth || y > hostHeight) return null
    pageBandEdge(hostWidth, hostHeight, x, y, bandDp)?.let { return HomeDropTarget.OnPage(it) }
    val card = geometry.cards.firstOrNull { it.rect.contains(x, y) } ?: return null
    if (card.section == dragged) return null
    return HomeDropTarget.OnSection(card.section, nearestEdge(card.rect, x, y))
}

private fun pageBandEdge(hostWidth: Float, hostHeight: Float, x: Float, y: Float, band: Float): HomeEdge? =
    listOf(
        HomeEdge.LEFT to x,
        HomeEdge.RIGHT to hostWidth - x,
        HomeEdge.TOP to y,
        HomeEdge.BOTTOM to hostHeight - y,
    ).filter { it.second < band }.minByOrNull { it.second }?.first

private fun nearestEdge(rect: HomeRect, x: Float, y: Float): HomeEdge {
    val dx = (x - rect.x) / rect.w
    val dy = (y - rect.y) / rect.h
    return listOf(HomeEdge.LEFT to dx, HomeEdge.RIGHT to 1f - dx, HomeEdge.TOP to dy, HomeEdge.BOTTOM to 1f - dy)
        .minBy { it.second }.first
}

/** The area the dropped section would take, for the accent overlay; null when the target section is not laid out. */
internal fun homeDropPreview(geometry: HomeGeometry, hostWidth: Float, hostHeight: Float, target: HomeDropTarget): HomeRect? = when (target) {
    is HomeDropTarget.OnPage -> edgeShare(HomeRect(0f, 0f, hostWidth, hostHeight), target.edge, HOME_PAGE_PREVIEW_SHARE)
    is HomeDropTarget.OnSection -> geometry.cardOf(target.section)?.let { edgeShare(it, target.edge, HOME_SECTION_PREVIEW_SHARE) }
}

private fun edgeShare(rect: HomeRect, edge: HomeEdge, share: Float): HomeRect = when (edge) {
    HomeEdge.LEFT -> rect.copy(w = rect.w * share)
    HomeEdge.RIGHT -> rect.copy(x = rect.right - rect.w * share, w = rect.w * share)
    HomeEdge.TOP -> rect.copy(h = rect.h * share)
    HomeEdge.BOTTOM -> rect.copy(y = rect.bottom - rect.h * share, h = rect.h * share)
}

internal fun homeSectionTitle(section: HomeSectionId): String = when (section) {
    HomeSectionId.OPEN -> "Open a log"
    HomeSectionId.CAPTURE -> "Capture"
    HomeSectionId.TESTS -> "Run tests"
}

/** The caption shown on the drop overlay. */
internal fun homeDropLabel(target: HomeDropTarget): String = when (target) {
    is HomeDropTarget.OnPage ->
        if (target.edge.axis == HomeSplitAxis.ROW) "Full height" else "Full width"
    is HomeDropTarget.OnSection -> {
        val title = homeSectionTitle(target.section)
        when (target.edge) {
            HomeEdge.LEFT -> "Left of $title"
            HomeEdge.RIGHT -> "Right of $title"
            HomeEdge.TOP -> "Above $title"
            HomeEdge.BOTTOM -> "Below $title"
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Presets
// ---------------------------------------------------------------------------------------------------------------

private fun homeLeaf(section: HomeSectionId, weight: Float = 1f) = HomeLayoutLeaf(section, weight)

private fun homeSplit(axis: HomeSplitAxis, vararg children: HomeLayoutNode, weight: Float = 1f) =
    HomeLayoutSplit(axis, children.toList(), weight)

/**
 * A ready-made arrangement. [layout] holds the visible sections, [hidden] the ones the preset hides, so
 * layout + hidden together hold every [HomeSectionId] exactly once.
 */
enum class HomeLayoutPreset(val label: String, val layout: HomeLayoutNode, val hidden: List<HomeSectionId> = emptyList()) {
    THREE_COLUMNS(
        "Three columns",
        homeSplit(HomeSplitAxis.ROW, homeLeaf(HomeSectionId.OPEN), homeLeaf(HomeSectionId.CAPTURE), homeLeaf(HomeSectionId.TESTS)),
    ),
    LOGS_ON_TOP(
        "Logs on top",
        homeSplit(
            HomeSplitAxis.COLUMN,
            homeLeaf(HomeSectionId.OPEN, 1.6f),
            homeSplit(HomeSplitAxis.ROW, homeLeaf(HomeSectionId.CAPTURE), homeLeaf(HomeSectionId.TESTS)),
        ),
    ),
    LOGS_LEFT_STACKED(
        "Logs left, others stacked",
        homeSplit(
            HomeSplitAxis.ROW,
            homeLeaf(HomeSectionId.OPEN, 1.6f),
            homeSplit(HomeSplitAxis.COLUMN, homeLeaf(HomeSectionId.CAPTURE), homeLeaf(HomeSectionId.TESTS)),
        ),
    ),
    DEFAULT("Default", DEFAULT_HOME_LAYOUT),
    LOGS_ONLY(
        "Logs only",
        homeLeaf(HomeSectionId.OPEN),
        listOf(HomeSectionId.CAPTURE, HomeSectionId.TESTS),
    ),
    ;

    /** True when [settings] currently show this preset's arrangement (weights and the order of the hidden list ignored). */
    internal fun isActiveIn(settings: AppSettings): Boolean =
        homeLayoutShapeEquals(layout, settings.homeLayout) && hidden.toSet() == settings.homeHiddenSections.toSet()
}

// ---------------------------------------------------------------------------------------------------------------
// Sanitizing
// ---------------------------------------------------------------------------------------------------------------

/** The visible arrangement plus the hidden sections; the pair [sanitizeHomeLayoutState] guarantees consistent. */
data class HomeLayoutState(val layout: HomeLayoutNode, val hidden: List<HomeSectionId>)

internal fun sanitizeHomeWeight(weight: Float): Float =
    if (!weight.isFinite() || weight <= 0f) 1f else weight.coerceIn(HOME_MIN_WEIGHT, HOME_MAX_WEIGHT)

private fun sanitizeNode(node: HomeLayoutNode, seen: MutableSet<HomeSectionId>): HomeLayoutNode? = when (node) {
    is HomeLayoutLeaf -> if (seen.add(node.section)) node.copy(weight = sanitizeHomeWeight(node.weight)) else null
    is HomeLayoutSplit -> node.children.mapNotNull { sanitizeNode(it, seen) }.takeIf { it.isNotEmpty() }
        ?.let { node.copy(children = it, weight = sanitizeHomeWeight(node.weight)) }
}

/**
 * Makes any [layout] and [hidden] list consistent: the first duplicate of a section wins, weights that are non-finite or
 * <= 0 become 1 and are clamped to [HOME_MIN_WEIGHT]..[HOME_MAX_WEIGHT], a section that is both in the tree and hidden
 * stays visible, sections neither in the tree nor hidden are appended at the trailing page edge (so a section added in a
 * later version shows up on its own), and an empty tree falls back to the default. At least one section is always visible.
 */
internal fun sanitizeHomeLayoutState(layout: HomeLayoutNode?, hidden: List<HomeSectionId>): HomeLayoutState {
    val seen = mutableSetOf<HomeSectionId>()
    val tree = layout?.let { sanitizeNode(it, seen) }?.let { normalizeHomeLayout(it) }
    val hiddenKept = hidden.distinct().filter { it !in seen }
    val missing = HomeSectionId.entries.filter { it !in seen && it !in hiddenKept }
    if (tree == null && (missing.size == HomeSectionId.entries.size || hiddenKept.size == HomeSectionId.entries.size)) {
        return HomeLayoutState(DEFAULT_HOME_LAYOUT, emptyList())
    }
    val start = tree ?: homeLeaf(missing.first())
    val rest = if (tree == null) missing.drop(1) else missing
    return HomeLayoutState(rest.fold(start) { acc, id -> appendHomeSection(acc, id) }, hiddenKept)
}

internal fun sanitizeHomeLayout(layout: HomeLayoutNode?, hidden: List<HomeSectionId>): HomeLayoutNode =
    sanitizeHomeLayoutState(layout, hidden).layout

// ---------------------------------------------------------------------------------------------------------------
// Settings mutators
// ---------------------------------------------------------------------------------------------------------------

/** Stores [layout] (sanitized against the hidden list). */
internal fun AppSettings.withHomeLayout(layout: HomeLayoutNode): AppSettings =
    sanitizeHomeLayoutState(layout, homeHiddenSections).let { copy(homeLayout = it.layout, homeHiddenSections = it.hidden) }

/** Hides [section]. Refuses (returns this) when it is not visible or is the last visible section. */
internal fun AppSettings.withHomeSectionHidden(section: HomeSectionId): AppSettings {
    if (section !in homeLeafSections(homeLayout) || homeLeafSections(homeLayout).size <= 1) return this
    val rest = removeHomeSection(homeLayout, section) ?: return this
    return copy(homeLayout = rest, homeHiddenSections = (homeHiddenSections + section).distinct())
}

/** Restores a hidden [section] at the trailing page edge; unchanged when it is not hidden. */
internal fun AppSettings.withHomeSectionShown(section: HomeSectionId): AppSettings {
    if (section !in homeHiddenSections) return this
    val hidden = homeHiddenSections - section
    val layout = if (section in homeLeafSections(homeLayout)) homeLayout else appendHomeSection(homeLayout, section)
    return copy(homeLayout = layout, homeHiddenSections = hidden)
}

internal fun AppSettings.withHomeLayoutPreset(preset: HomeLayoutPreset): AppSettings =
    copy(homeLayout = preset.layout, homeHiddenSections = preset.hidden)

internal fun AppSettings.withHomeLayoutTipDismissed(dismissed: Boolean): AppSettings =
    if (homeLayoutTipDismissed == dismissed) this else copy(homeLayoutTipDismissed = dismissed)
