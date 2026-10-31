package com.indagium.ui

import com.indagium.model.AppSettings
import com.indagium.model.LogViewPresetId
import com.indagium.model.LogViewScope
import com.indagium.model.LogViewSectionId
import com.indagium.model.LogViewTileGroup
import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import com.indagium.model.ToolbarPlacement

// The log view's (and the compare view's) tiling layout, bound to the generic engine in TileLayout.kt: the presets and the
// legacy-size derivation, which tiles are applicable / visible, the sanitizer that keeps a stored tree complete, the settings
// mutators and the small text helpers (titles, drop captions, focus order). Pure on purpose, no Compose imports; the settings
// codec is LogViewLayoutSettingsJson.kt (AutosaveCodec.kt is at detekt's per-file function limit), the header summaries are
// LogViewTileSummaries.kt.
//
// A stored tree always holds EVERY tile of its scope exactly once. A tile the user hides stays in the tree (so it comes back
// where it was) and is named in the hidden list; invisible tiles (hidden, wrong group, not applicable) are pruned only for
// layout, with projectTileLayout. Presets list the tiles they hide separately; sanitizing re-inserts those at their default
// anchors. A side section that ends up alone where a column would be (a direct child of a row, outside the scrolling columns) is
// stored inside a one-tile column, which is what gives it a content-sized card, a resize handle and a place for its height (logViewLoneColumn).

/** Gap (dp) between side-by-side tiles. */
const val LOG_VIEW_GAP_ROW_DP: Float = 8f

/** Gap (dp) between stacked tiles. */
const val LOG_VIEW_GAP_COLUMN_DP: Float = 6f

/** Height (dp) of a tile's header strip, which is also what a collapsed tile keeps. */
const val LOG_VIEW_TILE_HEADER_DP: Float = 32f

/** Least a tile is squeezed to along its split axis, by pinned siblings or by a resize. */
const val LOG_VIEW_MIN_TILE_DP: Float = 72f

/** Below this host width (dp) the tiles stack into one scrolling column. */
const val LOG_VIEW_STACK_BELOW_DP: Float = 720f

/** Below this tile width (dp) a tile drops its secondary header controls (PID/TID and the like). */
const val LOG_TILE_NARROW_DP: Float = 520f

/** A resize never leaves a neighbour pair with less than this share of the pair's total weight. */
const val LOG_VIEW_MIN_PAIR_SHARE: Float = 0.1f

// Pinned sizes (dp) are kept within these when a layout is sanitized or derived.
internal const val LOG_VIEW_PIN_MIN_DP: Float = 80f
internal const val LOG_VIEW_PIN_MAX_DP: Float = 2000f

// Default pinned widths for the legacy sizes, used when a legacy value is not a usable number.
private const val DEFAULT_FILTER_WIDTH_DP: Float = 220f
private const val DEFAULT_RIGHT_WIDTH_DP: Float = 360f

// The Log level tile is pinned to a natural height in the Classic column: its body is a single row of level chips, so letting
// it share by weight would waste space the Tags tile needs. 80 is also the least a pinned size can be (see LOG_VIEW_PIN_MIN_DP).
private const val LOG_LEVEL_TILE_DP: Float = 80f

// Starting weights inside the filter column: the tiles with the most to show start taller.
private const val TAGS_WEIGHT: Float = 2.2f
private const val MESSAGE_RULES_WEIGHT: Float = 1.4f
private const val SAVED_FILTERS_WEIGHT: Float = 1.2f
private const val LOG_LEVEL_ROW_WEIGHT: Float = 0.8f

// Starting weights in the right column, where Notes and AI share by rightSidebarSplit.
private const val VIDEO_WEIGHT: Float = 0.8f
private const val DIAGRAMS_WEIGHT: Float = 0.6f

// Review: the log's share against Original and against the Sequences | Notes row.
private const val REVIEW_LOG_WEIGHT: Float = 1.75f

private const val EVEN_SHARE: Float = 0.5f

internal typealias LogViewNode = TileNode<LogViewSectionId>

internal val LOG_VIEW_FILTER_TILES: List<LogViewSectionId> = LogViewSectionId.entries.filter { it.group == LogViewTileGroup.FILTERS }

/** The tiles that hold the log itself: always visible, never hideable. */
internal val LogViewSectionId.isLogTile: Boolean
    get() = this == LogViewSectionId.LOG || this == LogViewSectionId.LOG_A || this == LogViewSectionId.LOG_B

/**
 * The tiles that may sit in a scrolling column (TileLayout.kt): the side sections and Video, plus the toolbar tile (content-sized to its wrapped
 * rows of buttons). A column that holds the log or an Original always fits its window. Video keeps its per-device height in capture tabs and can
 * resize independently in the sidebar; only the whole column scrolls, so its body stays bounded by the tile. A column with the toolbar tile in it
 * scrolls like any other, so the toolbar may scroll out of view there; moving it somewhere else is the user's choice.
 */
internal fun logViewScrollsInColumn(id: LogViewSectionId): Boolean = when (id) {
    LogViewSectionId.LOG, LogViewSectionId.ORIGINAL, LogViewSectionId.LOG_A, LogViewSectionId.LOG_B,
    LogViewSectionId.ORIGINAL_A, LogViewSectionId.ORIGINAL_B,
    -> false
    else -> true
}

/**
 * The tiles that are kept in a one-tile scrolling column of their own when they end up alone where a column would be (dropped beside the other
 * columns, say): the side sections, so such a tile is as tall as its content, can be resized and scrolls, like in a column of several. Not the
 * toolbar tile: alone it may be the tall narrow toolbar, which is not content sized.
 */
internal fun logViewLoneColumn(id: LogViewSectionId): Boolean =
    id != LogViewSectionId.TOOLBAR && id != LogViewSectionId.VIDEO && logViewScrollsInColumn(id)

/** The log view's lone tiles (above) and its scrolling columns, as the normalizing, dropping and removing of tiles take them. */
internal val LOG_VIEW_LONE_COLUMNS: TileLoneColumns<LogViewSectionId> = TileLoneColumns(::logViewLoneColumn, ::logViewScrollsInColumn)

/** The tiles that cannot be hidden: the log tiles and the toolbar tile (the toolbar must always be reachable). */
internal val LogViewSectionId.neverHidden: Boolean
    get() = isLogTile || this == LogViewSectionId.TOOLBAR

/** Geometry parameters of the log view: gaps, minimum tile size and the least pair share a resize leaves. */
internal val LOG_VIEW_TILE_SPEC: TileGeometrySpec = TileGeometrySpec(
    gapRowDp = LOG_VIEW_GAP_ROW_DP,
    gapColumnDp = LOG_VIEW_GAP_COLUMN_DP,
    collapsedExtentDp = LOG_VIEW_TILE_HEADER_DP,
    minTileDp = LOG_VIEW_MIN_TILE_DP,
    minPairShare = LOG_VIEW_MIN_PAIR_SHARE,
)

private fun LogViewSectionId.inScope(scope: LogViewScope): Boolean =
    this.scope == LogViewScope.BOTH || scope == LogViewScope.BOTH || this.scope == scope

// ---------------------------------------------------------------------------------------------------------------
// Presets and the legacy derivation
// ---------------------------------------------------------------------------------------------------------------

/**
 * The sizes the log view used before it was tiled (AppState.filterPanelWidth, annotationPanelWidth, rightSidebarSplit and
 * compareSplit). They still drive the layout while the user has not changed it, and seed every preset.
 */
data class LogViewLegacySizes(
    val filterPanelWidth: Float = DEFAULT_FILTER_WIDTH_DP,
    val annotationPanelWidth: Float = DEFAULT_RIGHT_WIDTH_DP,
    val rightSidebarSplit: Float = EVEN_SHARE,
    val compareSplit: Float = EVEN_SHARE,
)

private fun pinnedDp(value: Float, default: Float): Float =
    (if (value.isFinite() && value > 0f) value else default).coerceIn(LOG_VIEW_PIN_MIN_DP, LOG_VIEW_PIN_MAX_DP)

private fun share(value: Float): Float =
    (if (value.isFinite()) value else EVEN_SHARE).coerceIn(LOG_VIEW_MIN_PAIR_SHARE, 1f - LOG_VIEW_MIN_PAIR_SHARE)

private fun lvLeaf(id: LogViewSectionId, weight: Float = 1f, dp: Float? = null): LogViewNode = TileLeaf(id, weight, dp)

private fun lvRow(vararg children: LogViewNode, weight: Float = 1f, dp: Float? = null): LogViewNode = lvRowOf(children.toList(), weight, dp)

private fun lvCol(vararg children: LogViewNode, weight: Float = 1f, dp: Float? = null): LogViewNode = lvColOf(children.toList(), weight, dp)

private fun lvRowOf(children: List<LogViewNode>, weight: Float = 1f, dp: Float? = null): LogViewNode = TileSplit(TileAxis.ROW, children, weight, dp)

private fun lvColOf(children: List<LogViewNode>, weight: Float = 1f, dp: Float? = null): LogViewNode = TileSplit(TileAxis.COLUMN, children, weight, dp)

private fun filterTileWeight(id: LogViewSectionId): Float = when (id) {
    LogViewSectionId.TAGS -> TAGS_WEIGHT
    LogViewSectionId.MESSAGE_RULES -> MESSAGE_RULES_WEIGHT
    LogViewSectionId.SAVED_FILTERS -> SAVED_FILTERS_WEIGHT
    else -> 1f
}

// [ids] as leaves with the starting weights. [pinLevel]: Log level takes its natural height (a column); in a row it is a
// slightly lighter weight instead, since a pinned height means nothing there.
private fun filterLeaves(ids: List<LogViewSectionId>, pinLevel: Boolean): List<LogViewNode> = ids.map {
    when {
        it == LogViewSectionId.LOG_LEVEL && pinLevel -> lvLeaf(it, 1f, LOG_LEVEL_TILE_DP)
        it == LogViewSectionId.LOG_LEVEL -> lvLeaf(it, LOG_LEVEL_ROW_WEIGHT)
        else -> lvLeaf(it, filterTileWeight(it))
    }
}

private fun filterColumn(ids: List<LogViewSectionId>, widthDp: Float): LogViewNode =
    lvColOf(filterLeaves(ids, pinLevel = true), dp = widthDp)

private fun classicRight(sizes: LogViewLegacySizes): LogViewNode {
    val split = share(sizes.rightSidebarSplit)
    return lvCol(
        lvLeaf(LogViewSectionId.DIAGRAMS, DIAGRAMS_WEIGHT),
        lvLeaf(LogViewSectionId.VIDEO, VIDEO_WEIGHT),
        lvLeaf(LogViewSectionId.NOTES, split),
        lvLeaf(LogViewSectionId.AI, 1f - split),
        dp = pinnedDp(sizes.annotationPanelWidth, DEFAULT_RIGHT_WIDTH_DP),
    )
}

/**
 * Classic: today's log view. Filter column (pinned to filterPanelWidth) | Original above Log (equal weights) | right column
 * (pinned to annotationPanelWidth): Diagram library, Video, Notes and AI (weights from rightSidebarSplit). Inside the filter
 * column Tags, Message rules and Saved filters start taller; Log level is pinned to its natural height.
 */
internal fun classicLogViewLayout(sizes: LogViewLegacySizes): LogViewNode = lvRow(
    filterColumn(LOG_VIEW_FILTER_TILES, pinnedDp(sizes.filterPanelWidth, DEFAULT_FILTER_WIDTH_DP)),
    lvCol(lvLeaf(LogViewSectionId.ORIGINAL), lvLeaf(LogViewSectionId.LOG)),
    classicRight(sizes),
)

/**
 * Compare grid: the filter and right columns as in Classic, and two columns between them, A and B, each Original over Log;
 * their widths come from compareSplit. Written flat on purpose: a nested ROW inside the root ROW would be flattened by
 * normalizing anyway.
 */
internal fun compareGridLayout(sizes: LogViewLegacySizes): LogViewNode {
    val split = share(sizes.compareSplit)
    return lvRow(
        filterColumn(LOG_VIEW_FILTER_TILES, pinnedDp(sizes.filterPanelWidth, DEFAULT_FILTER_WIDTH_DP)),
        lvCol(lvLeaf(LogViewSectionId.ORIGINAL_A), lvLeaf(LogViewSectionId.LOG_A), weight = split),
        lvCol(lvLeaf(LogViewSectionId.ORIGINAL_B), lvLeaf(LogViewSectionId.LOG_B), weight = 1f - split),
        classicRight(sizes),
    )
}

/**
 * Compare columns: the same four tiles row-major instead of column-major, so A and B sit side by side in each row. Both
 * Originals share the top row, both Logs the bottom row, which keeps matching lines of A and B level with each other. A/B
 * widths come from compareSplit in both rows.
 */
internal fun compareColumnsLayout(sizes: LogViewLegacySizes): LogViewNode {
    val split = share(sizes.compareSplit)
    return lvRow(
        filterColumn(LOG_VIEW_FILTER_TILES, pinnedDp(sizes.filterPanelWidth, DEFAULT_FILTER_WIDTH_DP)),
        lvCol(
            lvRow(lvLeaf(LogViewSectionId.ORIGINAL_A, split), lvLeaf(LogViewSectionId.ORIGINAL_B, 1f - split)),
            lvRow(lvLeaf(LogViewSectionId.LOG_A, split), lvLeaf(LogViewSectionId.LOG_B, 1f - split)),
        ),
        classicRight(sizes),
    )
}

// Review: a narrower filter column without Sequences, the log over Sequences | Notes + Diagram library, and AI full height on
// the right. The middle column is written flat (Original, Log, row) because a nested COLUMN would be flattened by normalizing.
private fun reviewLayout(): LogViewNode = lvRow(
    filterColumn(LOG_VIEW_FILTER_TILES - LogViewSectionId.SEQUENCES, REVIEW_FILTER_DP),
    lvCol(
        lvLeaf(LogViewSectionId.ORIGINAL, 1f),
        lvLeaf(LogViewSectionId.LOG, REVIEW_LOG_WEIGHT),
        lvRow(
            lvLeaf(LogViewSectionId.SEQUENCES, 1f, REVIEW_SEQUENCES_DP),
            lvCol(lvLeaf(LogViewSectionId.NOTES), lvLeaf(LogViewSectionId.DIAGRAMS)),
        ),
    ),
    lvCol(lvLeaf(LogViewSectionId.VIDEO, VIDEO_WEIGHT), lvLeaf(LogViewSectionId.AI), dp = REVIEW_RIGHT_DP),
)

private const val REVIEW_FILTER_DP: Float = 250f
private const val REVIEW_SEQUENCES_DP: Float = 300f
private const val REVIEW_RIGHT_DP: Float = 360f
private const val FILTERS_TOP_ROW_DP: Float = 220f
private const val FILTERS_TOP_RIGHT_DP: Float = 340f
private const val FOCUSED_LOG_WEIGHT: Float = 2.2f
private const val ANALYSIS_FILTER_MAX_DP: Float = 220f
private const val ANALYSIS_ARTIFACTS_MAX_DP: Float = 300f

// Video, Notes and AI in one column of [widthDp] (the right column of the presets that leave the Diagram library out of it).
private fun notesColumn(split: Float, widthDp: Float): LogViewNode = lvCol(
    lvLeaf(LogViewSectionId.VIDEO, VIDEO_WEIGHT),
    lvLeaf(LogViewSectionId.NOTES, split),
    lvLeaf(LogViewSectionId.AI, 1f - split),
    dp = widthDp,
)

// Log focus keeps only the unfiltered/log pair on screen. The hidden tiles are still completed into their
// normal anchors by the sanitizer, so showing a group again restores a sensible arrangement.
private fun logFocusLayout(): LogViewNode {
    return lvCol(lvLeaf(LogViewSectionId.ORIGINAL), lvLeaf(LogViewSectionId.LOG, FOCUSED_LOG_WEIGHT))
}

// Filters on top: all filter tiles in a strip across the top, the log with Notes and AI below.
private fun filtersOnTopLayout(sizes: LogViewLegacySizes): LogViewNode {
    val split = share(sizes.rightSidebarSplit)
    return lvCol(
        lvRowOf(filterLeaves(LOG_VIEW_FILTER_TILES, pinLevel = false), dp = FILTERS_TOP_ROW_DP),
        lvRow(
            lvCol(lvLeaf(LogViewSectionId.ORIGINAL), lvLeaf(LogViewSectionId.LOG)),
            notesColumn(split, FILTERS_TOP_RIGHT_DP),
        ),
    )
}

// Filtering: the vertical filter rail sits beside a dominant log. Notes, AI, Video and the Diagram library stay parked
// at their normal anchors in the complete tree, but are hidden initially so the log gets the full remaining width.
private fun filteringLayout(sizes: LogViewLegacySizes): LogViewNode = lvRow(
    filterColumn(LOG_VIEW_FILTER_TILES, pinnedDp(sizes.filterPanelWidth, DEFAULT_FILTER_WIDTH_DP)),
    lvCol(lvLeaf(LogViewSectionId.ORIGINAL), lvLeaf(LogViewSectionId.LOG, FOCUSED_LOG_WEIGHT)),
)

// Analysis: the log and Original occupy the full center column. Sequences and Issues live in the independent, scrolling
// artifact column beside Notes, AI, Diagrams and Video, so they never take a fixed-height slice out of the log.
private fun analysisLayout(sizes: LogViewLegacySizes): LogViewNode {
    val analysisFilters = LOG_VIEW_FILTER_TILES - LogViewSectionId.SEQUENCES - LogViewSectionId.ISSUES
    val logColumn = lvCol(
        lvLeaf(LogViewSectionId.ORIGINAL),
        lvLeaf(LogViewSectionId.LOG, FOCUSED_LOG_WEIGHT),
    )
    val right = lvCol(
        lvLeaf(LogViewSectionId.SEQUENCES),
        lvLeaf(LogViewSectionId.ISSUES),
        lvLeaf(LogViewSectionId.DIAGRAMS, DIAGRAMS_WEIGHT),
        lvLeaf(LogViewSectionId.VIDEO, VIDEO_WEIGHT),
        lvLeaf(LogViewSectionId.NOTES, share(sizes.rightSidebarSplit)),
        lvLeaf(LogViewSectionId.AI, 1f - share(sizes.rightSidebarSplit)),
        dp = minOf(pinnedDp(sizes.annotationPanelWidth, DEFAULT_RIGHT_WIDTH_DP), ANALYSIS_ARTIFACTS_MAX_DP),
    )
    val filterWidth = minOf(pinnedDp(sizes.filterPanelWidth, DEFAULT_FILTER_WIDTH_DP), ANALYSIS_FILTER_MAX_DP)
    return lvRow(filterColumn(analysisFilters, filterWidth), logColumn, right)
}

private val DIAGRAMS_ONLY = setOf(LogViewSectionId.DIAGRAMS)
private val RIGHT_ARTIFACTS = setOf(LogViewSectionId.DIAGRAMS, LogViewSectionId.VIDEO, LogViewSectionId.NOTES, LogViewSectionId.AI)
private val FILTERING_HIDDEN = RIGHT_ARTIFACTS
private val LOG_FOCUS_HIDDEN = LOG_VIEW_FILTER_TILES.toSet() + RIGHT_ARTIFACTS

/** The tab-strip panel flags of the single log view: filter sidebar, filter bar above the log, Notes, Video and AI. */
data class LogViewPanels(val filter: Boolean, val filterBar: Boolean, val notes: Boolean, val video: Boolean, val ai: Boolean)

/**
 * A ready-made arrangement. [layout] is the tree, [hidden] the tiles the preset hides: layout + hidden together cover every
 * tile of [scope], and a hidden tile either keeps its place in [layout] (for example, Filters on top's Diagram library) or is left out of it (Log
 * focus's filter tiles), in which case sanitizing re-inserts it at its default anchor when the preset is applied.
 */
enum class LogViewLayoutPreset(
    val id: LogViewPresetId,
    val label: String,
    val scope: LogViewScope,
    val layout: (LogViewLegacySizes) -> TileNode<LogViewSectionId>,
    val hidden: Set<LogViewSectionId>,
) {
    CLASSIC(LogViewPresetId.CLASSIC, "Classic", LogViewScope.SINGLE, ::classicLogViewLayout, emptySet()),

    // Kept for settings written by older releases. These legacy presets remain decodable/resettable but aren't in the current menu.
    REVIEW(LogViewPresetId.REVIEW, "Review", LogViewScope.SINGLE, { reviewLayout() }, emptySet()),
    LOG_FOCUS(
        LogViewPresetId.LOG_FOCUS,
        "Log focus",
        LogViewScope.SINGLE,
        { logFocusLayout() },
        LOG_FOCUS_HIDDEN,
    ),
    FILTERS_ON_TOP(LogViewPresetId.FILTERS_ON_TOP, "Filters on top", LogViewScope.SINGLE, ::filtersOnTopLayout, DIAGRAMS_ONLY),
    COMPARE_GRID(LogViewPresetId.COMPARE_GRID, "Compare grid", LogViewScope.COMPARE, ::compareGridLayout, emptySet()),
    COMPARE_COLUMNS(LogViewPresetId.COMPARE_COLUMNS, "Compare rows", LogViewScope.COMPARE, ::compareColumnsLayout, emptySet()),
    FILTERING(LogViewPresetId.FILTERING, "Filtering", LogViewScope.SINGLE, ::filteringLayout, FILTERING_HIDDEN),
    ANALYSIS(LogViewPresetId.ANALYSIS, "Analysis", LogViewScope.SINGLE, ::analysisLayout, emptySet()),

    // Log focus with the regex filter bar above the log (the bar is a view flag, not a tile: see AppState.applyLogViewPreset).
    FILTER_BAR(LogViewPresetId.FILTER_BAR, "Filter bar", LogViewScope.SINGLE, { logFocusLayout() }, LOG_FOCUS_HIDDEN),
    ;

    /**
     * The five panel flags applying this preset sets (the one place they are listed). Null for the legacy Review and Filters on top
     * presets and the compare presets: applying those leaves Notes, Video and AI as they are (and sets the filter group and bar as
     * their tiles imply), and they are matched by arrangement alone.
     */
    val panels: LogViewPanels?
        get() = when (this) {
            CLASSIC -> LogViewPanels(filter = true, filterBar = false, notes = true, video = true, ai = false)
            LOG_FOCUS -> LogViewPanels(filter = false, filterBar = false, notes = false, video = false, ai = false)
            FILTER_BAR -> LogViewPanels(filter = false, filterBar = true, notes = true, video = false, ai = false)
            FILTERING -> LogViewPanels(filter = true, filterBar = false, notes = false, video = false, ai = false)
            ANALYSIS -> LogViewPanels(filter = true, filterBar = false, notes = true, video = false, ai = false)
            REVIEW, FILTERS_ON_TOP, COMPARE_GRID, COMPARE_COLUMNS -> null
        }

    /** True for the one single-view preset that also switches the filter bar above the log on. */
    val showsFilterBar: Boolean get() = panels?.filterBar == true

    companion object {
        fun of(id: LogViewPresetId): LogViewLayoutPreset = entries.first { it.id == id }

        /** The presets that apply to a [scope] view, in menu order. */
        fun forScope(scope: LogViewScope): List<LogViewLayoutPreset> = when (scope) {
            LogViewScope.SINGLE -> listOf(CLASSIC, LOG_FOCUS, FILTER_BAR, FILTERING, ANALYSIS)
            LogViewScope.COMPARE -> listOf(COMPARE_GRID, COMPARE_COLUMNS)
            LogViewScope.BOTH -> emptyList()
        }
    }
}

/**
 * The single log view's layout: the stored one, else Classic derived from the legacy [sizes]; with the toolbar tile in it exactly while
 * the toolbar is IN_LAYOUT (a stored tree that lacks it gets it at its default anchor, a stale one loses it), so the drawn layout
 * is right even before the next commit sanitizes the stored one.
 */
internal fun effectiveLogViewLayout(settings: AppSettings, sizes: LogViewLegacySizes): LogViewNode =
    withToolbarTile(settings.logViewLayout ?: LogViewLayoutPreset.CLASSIC.layout(sizes), settings.toolbarPlacement == ToolbarPlacement.IN_LAYOUT)

/** The compare view's layout: the stored one, else Compare grid derived from the legacy [sizes]. */
internal fun effectiveCompareLayout(settings: AppSettings, sizes: LogViewLegacySizes): LogViewNode =
    settings.compareLayout ?: LogViewLayoutPreset.COMPARE_GRID.layout(sizes)

// The tree without the hidden tiles, normalized; what a preset's own layout looks like when it is active.
private fun withoutHidden(tree: LogViewNode, hidden: Collection<LogViewSectionId>): LogViewNode? =
    hidden.fold(tree as LogViewNode?) { acc, id -> acc?.let { removeTile(it, id) } }

/**
 * True when [settings] currently show [preset]: the same arrangement of its visible tiles (weights and pinned sizes ignored)
 * and the same hidden set. For the Layout menu's highlight.
 */
internal fun logViewPresetIsActive(preset: LogViewLayoutPreset, settings: AppSettings, sizes: LogViewLegacySizes): Boolean {
    val compare = preset.scope == LogViewScope.COMPARE
    val tree = if (compare) effectiveCompareLayout(settings, sizes) else effectiveLogViewLayout(settings, sizes)
    val hidden = if (compare) settings.compareHidden else settings.logViewHidden
    if (hidden.toSet() != preset.hidden) return false
    val shown = withoutHidden(tree, hidden + LogViewSectionId.TOOLBAR) ?: return false
    val expected = withoutHidden(preset.layout(sizes), preset.hidden) ?: return false
    return tileLayoutShapeEquals(shown, expected)
}

// ---------------------------------------------------------------------------------------------------------------
// Applicability and visibility
// ---------------------------------------------------------------------------------------------------------------

/**
 * What decides whether a tile can be shown right now. [groupOn] answers the tab-strip toggles (Filter, Notes, AI, Video).
 * [filterBarVisible] is the regex filter bar that replaces the Tags tile, [filterModeTags] the filter panel's Tags mode.
 */
data class LogViewTileContext(
    val compare: Boolean,
    val showUnfiltered: Boolean,
    val leftShowUnfiltered: Boolean,
    val rightShowUnfiltered: Boolean,
    val filterBarVisible: Boolean,
    val filterModeTags: Boolean,
    val hasVideoPanel: Boolean,
    val toolbarInLayout: Boolean = false,
    val groupOn: (LogViewTileGroup) -> Boolean,
)

/** True when [id] has anything to show in the current state of the view (group toggles and the hidden list not considered). */
fun logViewTileApplicable(id: LogViewSectionId, ctx: LogViewTileContext): Boolean {
    val scopeOk = when (id.scope) {
        LogViewScope.SINGLE -> !ctx.compare
        LogViewScope.COMPARE -> ctx.compare
        LogViewScope.BOTH -> true
    }
    if (!scopeOk) return false
    return when (id) {
        LogViewSectionId.ORIGINAL -> ctx.showUnfiltered
        LogViewSectionId.ORIGINAL_A -> ctx.leftShowUnfiltered
        LogViewSectionId.ORIGINAL_B -> ctx.rightShowUnfiltered
        LogViewSectionId.VIDEO -> ctx.hasVideoPanel
        LogViewSectionId.TAGS -> !ctx.filterBarVisible
        LogViewSectionId.MESSAGE_RULES -> !ctx.filterBarVisible && ctx.filterModeTags
        LogViewSectionId.TOOLBAR -> ctx.toolbarInLayout
        else -> true
    }
}

/** Applicable, its group switched on and not in [hidden]; the log tiles and the toolbar tile ignore [hidden] (they cannot be hidden). */
fun logViewTileVisible(id: LogViewSectionId, ctx: LogViewTileContext, hidden: Collection<LogViewSectionId>): Boolean =
    logViewTileApplicable(id, ctx) &&
        (id.group == LogViewTileGroup.NONE || ctx.groupOn(id.group)) &&
        (id.neverHidden || id !in hidden)

/** How many applicable tiles of [scope] the hidden list hides (the Layout button's badge); tiles off through a group do not count. */
fun logViewHiddenBadgeCount(ctx: LogViewTileContext, hidden: Collection<LogViewSectionId>, scope: LogViewScope): Int =
    hidden.distinct().count { !it.neverHidden && it.inScope(scope) && logViewTileApplicable(it, ctx) }

// ---------------------------------------------------------------------------------------------------------------
// Sanitizing
// ---------------------------------------------------------------------------------------------------------------

/**
 * Where a missing tile is inserted: beside [first] on [second]'s edge. A filter tile follows its predecessor in declaration
 * order instead (see [anchorFor]).
 */
internal val LOG_VIEW_DEFAULT_ANCHORS: Map<LogViewSectionId, Pair<LogViewSectionId, TileEdge>> = mapOf(
    LogViewSectionId.ORIGINAL to (LogViewSectionId.LOG to TileEdge.TOP),
    LogViewSectionId.ORIGINAL_A to (LogViewSectionId.LOG_A to TileEdge.TOP),
    LogViewSectionId.ORIGINAL_B to (LogViewSectionId.LOG_B to TileEdge.TOP),
    LogViewSectionId.DIAGRAMS to (LogViewSectionId.NOTES to TileEdge.TOP),
    LogViewSectionId.VIDEO to (LogViewSectionId.NOTES to TileEdge.TOP),
    LogViewSectionId.AI to (LogViewSectionId.NOTES to TileEdge.BOTTOM),
)

// The nearest filter tile before [id] (insert below it), else the nearest after it (insert above it); null for other tiles.
private fun filterAnchor(id: LogViewSectionId, present: Set<LogViewSectionId>): Pair<LogViewSectionId, TileEdge>? {
    val at = LOG_VIEW_FILTER_TILES.indexOf(id)
    if (at < 0) return null
    LOG_VIEW_FILTER_TILES.take(at).lastOrNull { it in present }?.let { return it to TileEdge.BOTTOM }
    return LOG_VIEW_FILTER_TILES.drop(at + 1).firstOrNull { it in present }?.let { it to TileEdge.TOP }
}

private fun anchorFor(id: LogViewSectionId, present: Set<LogViewSectionId>): Pair<LogViewSectionId, TileEdge>? =
    filterAnchor(id, present) ?: LOG_VIEW_DEFAULT_ANCHORS[id]?.takeIf { it.first in present }

private fun keepScope(node: LogViewNode, scope: LogViewScope): LogViewNode? = when (node) {
    is TileLeaf -> node.takeIf { it.section.inScope(scope) }
    is TileSplit -> node.children.mapNotNull { keepScope(it, scope) }.takeIf { it.isNotEmpty() }?.let { node.copy(children = it) }
}

private fun clampPins(node: LogViewNode): LogViewNode = when (node) {
    is TileLeaf -> node.copy(sizeDp = node.sizeDp?.coerceIn(LOG_VIEW_PIN_MIN_DP, LOG_VIEW_PIN_MAX_DP))
    is TileSplit -> node.copy(children = node.children.map { clampPins(it) }, sizeDp = node.sizeDp?.coerceIn(LOG_VIEW_PIN_MIN_DP, LOG_VIEW_PIN_MAX_DP))
}

// [id] as a new child of the split that holds [anchor] as a direct child, on [edge]'s side of it, when that split runs along
// the edge's axis; null otherwise. The new tile takes the average weight of the weighted siblings, so inserting a run of
// tiles does not halve the weights again and again as dropTile would. Pinned siblings keep their size.
private fun insertAlongside(node: LogViewNode, anchor: LogViewSectionId, id: LogViewSectionId, edge: TileEdge): LogViewNode? {
    if (node !is TileSplit) return null
    val at = node.children.indexOfFirst { it is TileLeaf && it.section == anchor }
    if (at >= 0) {
        if (node.axis != edge.axis) return null
        val weights = node.children.filter { it.sizeDp == null }.map { it.weight }
        val added = TileLeaf(id, if (weights.isEmpty()) 1f else weights.average().toFloat())
        val index = if (edge.leading) at else at + 1
        return node.copy(children = node.children.take(index) + added + node.children.drop(index))
    }
    node.children.forEachIndexed { i, child ->
        insertAlongside(child, anchor, id, edge)?.let { return node.copy(children = node.children.take(i) + it + node.children.drop(i + 1)) }
    }
    return null
}

private fun insertAt(tree: LogViewNode, id: LogViewSectionId, anchor: Pair<LogViewSectionId, TileEdge>): LogViewNode =
    insertAlongside(tree, anchor.first, id, anchor.second)
        ?: dropTile(tree, id, TileDropTarget.OnSection(anchor.first, anchor.second), LOG_VIEW_LONE_COLUMNS)

private fun defaultPreset(scope: LogViewScope): LogViewLayoutPreset =
    if (scope == LogViewScope.COMPARE) LogViewLayoutPreset.COMPARE_GRID else LogViewLayoutPreset.CLASSIC

/**
 * Makes any [tree] and [hidden] list consistent for [scope] (SINGLE or COMPARE):
 * - the first duplicate of a tile wins, tiles of the other scope are dropped, weights are sanitized and pinned sizes clamped
 *   to 80..2000 dp;
 * - tiles missing from the tree are inserted beside their default anchor ([LOG_VIEW_DEFAULT_ANCHORS]: Original above Log,
 *   Diagram library above Notes, Video above Notes, AI below Notes, a filter tile below its predecessor in declaration order,
 *   Tags above the first filter tile), and appended at the trailing page edge when the anchor is not in the tree either;
 * - an empty tree falls back to the default preset (Classic / Compare grid);
 * - the log tiles and the toolbar tile are never hidden, and the hidden list keeps only distinct tiles of [scope] (a hidden tile
 *   stays in the tree);
 * - the toolbar tile ([LogViewSectionId.TOOLBAR]) belongs in a single view's tree exactly when [toolbarInLayout]: then a missing one is
 *   inserted slim above the first tile (the top of the left column), otherwise a stored one is removed.
 */
internal fun sanitizeLogViewLayoutState(
    tree: LogViewNode?,
    hidden: List<LogViewSectionId>,
    scope: LogViewScope,
    toolbarInLayout: Boolean = false,
): Pair<LogViewNode, List<LogViewSectionId>> {
    val wanted = LogViewSectionId.entries.filter { it.inScope(scope) && (it != LogViewSectionId.TOOLBAR || toolbarInLayout) }
    val seen = mutableSetOf<LogViewSectionId>()
    val cleaned = tree?.let { keepScope(it, scope) }?.let { sanitizeTileTree(it, seen) }?.let { normalizeTileLayout(it, LOG_VIEW_LONE_COLUMNS) }
        ?.let { clampPins(it) }
        ?.let { if (toolbarInLayout) it else removeTile(it, LogViewSectionId.TOOLBAR, LOG_VIEW_LONE_COLUMNS) }
    val start = cleaned ?: defaultPreset(scope).layout(LogViewLegacySizes())
    val present = tileLeafSections(start).toMutableSet()
    val complete = wanted.filter { it !in present }.fold(start) { acc, id ->
        val placed = if (id == LogViewSectionId.TOOLBAR) {
            withToolbarTile(acc, true)
        } else {
            anchorFor(id, present)?.let { insertAt(acc, id, it) } ?: appendTile(acc, id, LOG_VIEW_LONE_COLUMNS)
        }
        present += id
        placed
    }
    val hiddenKept = hidden.distinct().filter { it.inScope(scope) && !it.neverHidden }
    return complete to hiddenKept
}

// ---------------------------------------------------------------------------------------------------------------
// The toolbar tile
// ---------------------------------------------------------------------------------------------------------------

/** Pinned extent (dp) of the slim toolbar tile in a COLUMN (its height) and in a ROW (its width); also the least pin allows. */
const val TOOLBAR_TILE_COLUMN_DP: Float = 80f
const val TOOLBAR_TILE_ROW_DP: Float = 150f

/** [tree] with the toolbar tile pinned to its slim size in its parent's axis, unless the user has already sized it (a pinned leaf is left alone). */
internal fun pinToolbarTile(node: LogViewNode, parentAxis: TileAxis? = null): LogViewNode = when (node) {
    is TileLeaf ->
        if (node.section == LogViewSectionId.TOOLBAR && node.sizeDp == null && parentAxis != null) {
            node.copy(sizeDp = if (parentAxis == TileAxis.COLUMN) TOOLBAR_TILE_COLUMN_DP else TOOLBAR_TILE_ROW_DP)
        } else {
            node
        }
    is TileSplit -> node.copy(children = node.children.map { pinToolbarTile(it, node.axis) })
}

/**
 * [tree] with the toolbar tile present ([present]) or absent: a missing tile is inserted slim above the first tile (the top of the left
 * column, which is the top of the filter column in every preset that has one); an unwanted one is removed. Returns [tree] itself when
 * it already is right.
 */
internal fun withToolbarTile(tree: LogViewNode, present: Boolean): LogViewNode {
    val has = LogViewSectionId.TOOLBAR in tileLeafSections(tree)
    return when {
        present == has -> tree
        !present -> removeTile(tree, LogViewSectionId.TOOLBAR, LOG_VIEW_LONE_COLUMNS) ?: tree
        else -> {
            val first = tileLeafSections(tree).firstOrNull()
            val inserted = if (first != null) {
                insertAt(tree, LogViewSectionId.TOOLBAR, first to TileEdge.TOP)
            } else {
                appendTile(tree, LogViewSectionId.TOOLBAR, LOG_VIEW_LONE_COLUMNS)
            }
            pinToolbarTile(inserted)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Settings mutators
// ---------------------------------------------------------------------------------------------------------------

// The hidden list after hiding or showing [id]; null when the request is refused (a log tile or a tile of another scope).
private fun toggledHidden(hidden: List<LogViewSectionId>, id: LogViewSectionId, hide: Boolean, scope: LogViewScope): List<LogViewSectionId>? =
    if (id.neverHidden || !id.inScope(scope)) null else if (hide) (hidden + id).distinct() else hidden - id

/** Stores [tree] (sanitized against the hidden list) as the single log view's layout. */
internal fun AppSettings.withLogViewLayout(tree: TileNode<LogViewSectionId>): AppSettings =
    sanitizeLogViewLayoutState(tree, logViewHidden, LogViewScope.SINGLE, toolbarPlacement == ToolbarPlacement.IN_LAYOUT)
        .let { copy(logViewLayout = it.first, logViewHidden = it.second) }

/** Hides or shows [id] in the single log view. Refuses (returns this) for the Log tile and for compare-only tiles. */
internal fun AppSettings.withLogViewTileHidden(id: LogViewSectionId, hidden: Boolean): AppSettings =
    toggledHidden(logViewHidden, id, hidden, LogViewScope.SINGLE)?.let { copy(logViewHidden = it) } ?: this

/** Applies [preset] to the single log view: its layout (from [sizes]), its hidden tiles, and remembers it as the last preset. */
internal fun AppSettings.withLogViewPreset(preset: LogViewLayoutPreset, sizes: LogViewLegacySizes): AppSettings {
    if (preset.scope != LogViewScope.SINGLE) return this
    val state = sanitizeLogViewLayoutState(preset.layout(sizes), preset.hidden.toList(), LogViewScope.SINGLE, toolbarPlacement == ToolbarPlacement.IN_LAYOUT)
    val collapsed = if (preset == LogViewLayoutPreset.CLASSIC) {
        (logViewCollapsed + LogViewSectionId.DIAGRAMS).distinct()
    } else {
        logViewCollapsed
    }
    return copy(
        logViewLayout = state.first,
        logViewHidden = state.second,
        logViewCollapsed = collapsed,
        lastLogViewPreset = preset.id,
        lastCustomLogViewLayoutId = null,
    )
}

/** Collapses or expands [id] (to its header). The log tiles cannot collapse. */
internal fun AppSettings.withLogViewTileCollapsed(id: LogViewSectionId, collapsed: Boolean): AppSettings {
    if (id.neverHidden) return this
    val next = if (collapsed) (logViewCollapsed + id).distinct() else logViewCollapsed - id
    return if (next == logViewCollapsed) this else copy(logViewCollapsed = next)
}

internal fun AppSettings.withLogViewTilesLocked(locked: Boolean): AppSettings =
    if (logViewTilesLocked == locked) this else copy(logViewTilesLocked = locked)

/** Stores [tree] (sanitized against the hidden list) as the compare view's layout. */
internal fun AppSettings.withCompareLayout(tree: TileNode<LogViewSectionId>): AppSettings =
    sanitizeLogViewLayoutState(tree, compareHidden, LogViewScope.COMPARE).let { copy(compareLayout = it.first, compareHidden = it.second) }

/** Hides or shows [id] in the compare view. Refuses (returns this) for the Log tiles and for single-view-only tiles. */
internal fun AppSettings.withCompareTileHidden(id: LogViewSectionId, hidden: Boolean): AppSettings =
    toggledHidden(compareHidden, id, hidden, LogViewScope.COMPARE)?.let { copy(compareHidden = it) } ?: this

/** Applies the compare [preset]; see [withLogViewPreset]. */
internal fun AppSettings.withComparePreset(preset: LogViewLayoutPreset, sizes: LogViewLegacySizes): AppSettings {
    if (preset.scope != LogViewScope.COMPARE) return this
    val state = sanitizeLogViewLayoutState(preset.layout(sizes), preset.hidden.toList(), LogViewScope.COMPARE)
    val collapsed = (logViewCollapsed + LogViewSectionId.DIAGRAMS).distinct()
    return copy(
        compareLayout = state.first,
        compareHidden = state.second,
        logViewCollapsed = collapsed,
        lastComparePreset = preset.id,
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Titles, drop captions, focus order
// ---------------------------------------------------------------------------------------------------------------

private val TILE_TITLES: Map<LogViewSectionId, String> = mapOf(
    LogViewSectionId.TAGS to "Tags",
    LogViewSectionId.MESSAGE_RULES to "Message rules",
    LogViewSectionId.LOG_COMPOSITION to "Log composition",
    LogViewSectionId.HIGHLIGHTERS to "Highlighters",
    LogViewSectionId.LOG_LEVEL to "Log level",
    LogViewSectionId.SEQUENCES to "Sequences",
    LogViewSectionId.ISSUES to "Issues",
    LogViewSectionId.SAVED_FILTERS to "Saved filters",
    LogViewSectionId.LOG to "Log",
    LogViewSectionId.ORIGINAL to "Original",
    LogViewSectionId.LOG_A to "Log A",
    LogViewSectionId.LOG_B to "Log B",
    LogViewSectionId.ORIGINAL_A to "Original A",
    LogViewSectionId.ORIGINAL_B to "Original B",
    LogViewSectionId.NOTES to "Notes",
    LogViewSectionId.DIAGRAMS to "Diagram library",
    LogViewSectionId.AI to "AI assistant",
    LogViewSectionId.VIDEO to "Video",
    LogViewSectionId.TOOLBAR to "Toolbar",
)

/** The title a tile's header, drop caption and menu entry use; the table has an entry for every [LogViewSectionId] (a test walks them all). */
internal fun logViewTileTitle(id: LogViewSectionId): String = TILE_TITLES.getValue(id)

/** The caption shown on the drop overlay, worded like the New tab's. */
internal fun logViewDropLabel(target: TileDropTarget<LogViewSectionId>): String = when (target) {
    is TileDropTarget.OnPage -> if (target.edge.axis == TileAxis.ROW) "Full height" else "Full width"
    is TileDropTarget.OnSection -> {
        val title = logViewTileTitle(target.section)
        when (target.edge) {
            TileEdge.LEFT -> "Left of $title"
            TileEdge.RIGHT -> "Right of $title"
            TileEdge.TOP -> "Above $title"
            TileEdge.BOTTOM -> "Below $title"
        }
    }
}

/** A stop of the F6 panel cycle: all the filter tiles are one stop. */
enum class LogViewFocusPanel { FILTERS, ORIGINAL, LOG, ORIGINAL_A, LOG_A, ORIGINAL_B, LOG_B, NOTES, DIAGRAMS, AI, VIDEO }

internal fun LogViewSectionId.focusPanel(): LogViewFocusPanel = when (this) {
    LogViewSectionId.ORIGINAL -> LogViewFocusPanel.ORIGINAL
    LogViewSectionId.LOG -> LogViewFocusPanel.LOG
    LogViewSectionId.ORIGINAL_A -> LogViewFocusPanel.ORIGINAL_A
    LogViewSectionId.LOG_A -> LogViewFocusPanel.LOG_A
    LogViewSectionId.ORIGINAL_B -> LogViewFocusPanel.ORIGINAL_B
    LogViewSectionId.LOG_B -> LogViewFocusPanel.LOG_B
    LogViewSectionId.NOTES -> LogViewFocusPanel.NOTES
    LogViewSectionId.DIAGRAMS -> LogViewFocusPanel.DIAGRAMS
    LogViewSectionId.AI -> LogViewFocusPanel.AI
    LogViewSectionId.VIDEO -> LogViewFocusPanel.VIDEO
    else -> LogViewFocusPanel.FILTERS
}

/** The panels of the visible tiles in reading order (depth first), the filter tiles collapsed into one FILTERS stop. */
internal fun logViewFocusOrder(projectionRoot: TileNode<LogViewSectionId>): List<LogViewFocusPanel> =
    tileLeafSections(projectionRoot).filter { it != LogViewSectionId.TOOLBAR }.map { it.focusPanel() }.distinct()
