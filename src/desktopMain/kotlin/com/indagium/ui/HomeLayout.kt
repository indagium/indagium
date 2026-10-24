package com.indagium.ui

import com.indagium.model.AppSettings
import com.indagium.model.DEFAULT_HOME_LAYOUT
import com.indagium.model.HomeLayoutLeaf
import com.indagium.model.HomeLayoutNode
import com.indagium.model.HomeLayoutSplit
import com.indagium.model.HomeSectionId
import com.indagium.model.HomeSplitAxis

// The New tab's tiling layout. The pure engine (tree operations, geometry, drop hit-testing, sanitizing) is the generic
// TileLayout.kt; this file binds it to HomeSectionId, keeps the New tab's names as thin delegates, and holds what is specific
// to the New tab: titles, drop captions, presets and the settings mutators. The settings codec lives in
// HomeLayoutSettingsJson.kt (AutosaveCodec.kt is at detekt's per-file function limit).

/** Width (dp) of the page-edge drop bands along the four host edges. They win over the card under the pointer. */
const val HOME_PAGE_BAND_DP: Float = TILE_PAGE_BAND_DP

/** A resize can never leave a neighbour pair with less than this share of the pair's total weight. */
const val HOME_MIN_PAIR_SHARE: Float = 0.18f

/** A resize can never shrink a section below this many dp along the split axis. */
const val HOME_MIN_SECTION_DP: Float = 200f

const val HOME_MIN_WEIGHT: Float = TILE_MIN_WEIGHT
const val HOME_MAX_WEIGHT: Float = TILE_MAX_WEIGHT

/** Side of a host or card a section is dropped on. */
typealias HomeEdge = TileEdge

typealias HomeRect = TileRect
typealias HomeCardRect = TileCardRect<HomeSectionId>
typealias HomeGap = TileGap
typealias HomeGeometry = TileGeometry<HomeSectionId>

// A typealias cannot reach the nested OnSection / OnPage classes, which the New tab's call sites name, so the drop target
// stays a concrete type and converts to and from the generic one at the boundary.
sealed interface HomeDropTarget {
    /** Dropped on [edge] of another visible section. */
    data class OnSection(val section: HomeSectionId, val edge: HomeEdge) : HomeDropTarget

    /** Dropped on [edge] of the whole page. */
    data class OnPage(val edge: HomeEdge) : HomeDropTarget
}

private fun HomeDropTarget.toTile(): TileDropTarget<HomeSectionId> = when (this) {
    is HomeDropTarget.OnSection -> TileDropTarget.OnSection(section, edge)
    is HomeDropTarget.OnPage -> TileDropTarget.OnPage(edge)
}

private fun TileDropTarget<HomeSectionId>.toHome(): HomeDropTarget = when (this) {
    is TileDropTarget.OnSection -> HomeDropTarget.OnSection(section, edge)
    is TileDropTarget.OnPage -> HomeDropTarget.OnPage(edge)
}

private fun homeSpec(gap: Float) = TileGeometrySpec(gap, gap, minTileDp = HOME_MIN_SECTION_DP, minPairShare = HOME_MIN_PAIR_SHARE)

// ---------------------------------------------------------------------------------------------------------------
// Delegates to the generic engine
// ---------------------------------------------------------------------------------------------------------------

internal fun HomeLayoutNode.withWeight(weight: Float): HomeLayoutNode = withTileWeight(weight)

/** The sections in the tree, depth first, in display order. */
internal fun homeLeafSections(node: HomeLayoutNode): List<HomeSectionId> = tileLeafSections(node)

/** True when both trees have the same arrangement of sections; weights are ignored (used to highlight the active preset). */
internal fun homeLayoutShapeEquals(a: HomeLayoutNode, b: HomeLayoutNode): Boolean = tileLayoutShapeEquals(a, b)

/** See [normalizeTileLayout]. */
internal fun normalizeHomeLayout(node: HomeLayoutNode): HomeLayoutNode? = normalizeTileLayout(node)

/** The tree without [section], normalized; null when [section] was the last one. */
internal fun removeHomeSection(root: HomeLayoutNode, section: HomeSectionId): HomeLayoutNode? = removeTile(root, section)

/** Moves [dragged] onto [target]; see [dropTile]. */
internal fun dropHomeSection(root: HomeLayoutNode, dragged: HomeSectionId, target: HomeDropTarget): HomeLayoutNode =
    dropTile(root, dragged, target.toTile())

/** Adds [section] at the trailing page edge: right of a ROW root or a single section, below a COLUMN root. */
internal fun appendHomeSection(root: HomeLayoutNode, section: HomeSectionId): HomeLayoutNode = appendTile(root, section)

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
): HomeLayoutNode = resizeTileSplit(root, path, index, deltaDp, extentDp, minSizeDp, HOME_MIN_PAIR_SHARE)

/** Card rects and gaps for [root] laid out in a [width] x [height] dp host with [gap] dp between siblings. */
internal fun homeGeometry(root: HomeLayoutNode, width: Float, height: Float, gap: Float): HomeGeometry =
    tileGeometry(root, width, height, homeSpec(gap))

/** "50% × 62%": [card]'s size as a share of the host. */
internal fun homeSizeLabel(card: HomeRect, hostWidth: Float, hostHeight: Float): String = tileSizeLabel(card, hostWidth, hostHeight)

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
): HomeDropTarget? = tileDropTargetAt(geometry, hostWidth, hostHeight, x, y, dragged, bandDp)?.toHome()

/** The area the dropped section would take, for the accent overlay; null when the target section is not laid out. */
internal fun homeDropPreview(geometry: HomeGeometry, hostWidth: Float, hostHeight: Float, target: HomeDropTarget): HomeRect? =
    tileDropPreview(geometry, hostWidth, hostHeight, target.toTile())

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

internal fun sanitizeHomeWeight(weight: Float): Float = sanitizeTileWeight(weight)

/**
 * Makes any [layout] and [hidden] list consistent: the first duplicate of a section wins, weights that are non-finite or
 * <= 0 become 1 and are clamped to [HOME_MIN_WEIGHT]..[HOME_MAX_WEIGHT], a section that is both in the tree and hidden
 * stays visible, sections neither in the tree nor hidden are appended at the trailing page edge (so a section added in a
 * later version shows up on its own), and an empty tree falls back to the default. At least one section is always visible.
 */
internal fun sanitizeHomeLayoutState(layout: HomeLayoutNode?, hidden: List<HomeSectionId>): HomeLayoutState {
    val seen = mutableSetOf<HomeSectionId>()
    val tree = layout?.let { sanitizeTileTree(it, seen) }?.let { normalizeHomeLayout(it) }
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
