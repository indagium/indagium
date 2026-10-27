@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.debug.AppLogger
import com.indagium.model.TileAxis
import com.indagium.model.TileNode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The generic tiling host behind the New tab and (in later phases) the log and compare views: one composable surface that
// lays a TileNode<K> tree out as cards in a window-manager-style arrangement. The pure tree/geometry engine is TileLayout.kt;
// this file adds the chrome (header, grip, buttons, splitters, drop overlay) and the interaction state.
//
// A caller hands TilingSurface its layout tree and a content lambda that emits tiles with TileScope.Tile(id, slot). One
// composable may emit several tiles (multi-emission): every Tile call is placed by its own id, wherever it sits in the
// composition. Moving a tile only changes its rect, never its place in the composition, so its remembered state survives.
// Moving a tile is a manual pointer drag on its grip, NOT Compose/AWT drag-and-drop (App.kt's file-drop target must never see it,
// and a test can drive it). A splitter drag only edits a local draft; the layout is written through the callbacks once, on release.

private const val TILE_SPLITTER_KEY_STEP_DP = 24f
private const val TILE_DRAGGED_CARD_ALPHA = 0.45f
private const val TILE_PREVIEW_FILL_ALPHA = 0.18f
private const val TILE_STRIP_FILL_ALPHA = 0.12f
private const val TILE_STRIP_BORDER_ALPHA = 0.55f
private const val TILE_STRIP_DASH_DP = 5f
private const val TILE_VERTICAL_TEXT_DEGREES = -90f

/** Host width (dp) a tile header needs to keep its summary text; narrower headers drop it first. */
internal const val TILE_HEADER_SUMMARY_MIN_DP = 300f

/** Host width (dp) a tile header needs to keep its icon; narrower headers drop it after the summary. The grip and buttons are never dropped. */
internal const val TILE_HEADER_ICON_MIN_DP = 180f

/** The gap (dp) between two neighbouring buttons or parts of a tile header. */
internal const val TILE_HEADER_GAP_DP = 6f

/**
 * Everything that makes one tiling surface look and size the way it does. [headerDp] is the tile header's height, which is also
 * the height of a collapsed tile; [cornerDp] the card radius (splitter lines stop that far short of both ends of their gap);
 * [minTileDp] and [minPairShare] bound a resize (see [TileGeometrySpec]); [tagPrefix] starts every test tag
 * (`<prefix>-card-<key>`, `-grip-`, `-hide-`, `-collapse-`, `-maximize-`, `-header-`, `<prefix>-splitter-<i>`,
 * `-splitter-line-<i>`, `-scrollbar-<key>`, `-drop-preview`, `-page-strip-<edge>`); [keyOf] gives the stable, unique string a tile's tags use.
 * [scrolls] says which tiles may sit in a scrolling column (a COLUMN split of only such tiles keeps its tiles' heights and scrolls when
 * they add up to more than the window; see TileLayout.kt), with [scrollMinDp] / [scrollMaxDp] bounding a tile's height there; the
 * default is none, a surface that always fits. A scrolling column's scrollbar is drawn in the gap beside the column ([tileScrollbarRect]), never
 * over a card; a column that ends at the host's right edge has [scrollEdgeRoomDp] there (the caller gives the surface that much more width than
 * the cards use, the log view its host padding). [scrollEndHandle] adds the splitter below the column's last tile;
 * [scrollEndHandleOuterRoomDp] can place it in an outside bottom gutter while the viewport and scrollbar stay aligned with the main host.
 * [loneColumn] says which tiles
 * are kept in a one-tile column of their own when they end up alone where a column would be, so they size and scroll like a column's tiles
 * (see normalizeTileLayout); the default is none.
 */
internal data class TileChromeSpec<K>(
    val headerDp: Float,
    val titleSp: Float,
    val gapRowDp: Float,
    val gapColumnDp: Float,
    val cornerDp: Int,
    val minTileDp: Float,
    val minPairShare: Float,
    val tagPrefix: String,
    val keyOf: (K) -> String,
    val scrollMinDp: Float = minTileDp,
    val scrollMaxDp: Float = TILE_MAX_SIZE_DP,
    val scrolls: (K) -> Boolean = { false },
    val loneColumn: (K) -> Boolean = { false },
    val scrollEdgeRoomDp: Float = 0f,
    val scrollEndHandle: Boolean = false,
    val scrollEndHandleOuterRoomDp: Float = 0f,
) {
    /** [loneColumn] together with [scrolls], as the layout edits (drop, remove, normalize) take them. */
    val loneColumns: TileLoneColumns<K> get() = TileLoneColumns(loneColumn, scrolls)

    internal fun geometrySpec(): TileGeometrySpec = TileGeometrySpec(
        gapRowDp = gapRowDp,
        gapColumnDp = gapColumnDp,
        collapsedExtentDp = headerDp,
        minTileDp = minTileDp,
        minPairShare = minPairShare,
        scrollMinDp = scrollMinDp,
        scrollMaxDp = scrollMaxDp,
        scrollEdgeRoomDp = scrollEdgeRoomDp,
        scrollEndHandle = scrollEndHandle,
        scrollEndHandleOuterRoomDp = scrollEndHandleOuterRoomDp,
    )
}

/**
 * The size (dp) of a tile's body area, passed to [TileSlot.body]; zero while the tile is parked, collapsed or covered by a maximized tile.
 * [contentSized] is true while the tile sits in a scrolling column and its slot is [TileBodySizing.CONTENT] or [TileBodySizing.FLEX]: the
 * body is then measured at its natural height (so it must not scroll as a whole or fill the height) and the tile is as tall as it asks.
 */
internal data class TileBodyInfo(val widthDp: Float, val heightDp: Float, val contentSized: Boolean = false)

/**
 * One tile's content: the [icon] and [title] (plus an optional [summary] such as "2 active") in its header, an optional
 * [headerAction] before the header buttons, and the [body] that fills the rest of the card (the body does its own scrolling).
 * A [headerless] tile draws no header at all: its body brings its own bar and may place [TileScope.Grip] and
 * [TileScope.MaximizeButton] in it. [collapsible] / [hideable] / [maximizable] choose which header buttons show; [canHide] false
 * disables the hide button (the last visible tile). [iconTint] colours the header icon (null = the theme's secondary text colour).
 * [sizing] says how the body takes part in the height of a scrolling column (see [TileBodySizing] and [FlexRegion]); the default leaves it
 * to the stored or weighted height. [headerParts] replaces the default rule for which header parts fit a card of a given width (summary, then
 * icon, see [tileHeaderParts]); [actionsCentered] puts the [headerAction] in the middle of the room between the title part and the header icons
 * instead of at the right edge (the title shortens first when the room is short).
 */
@Suppress("LongParameterList") // a slot is a flat description of one tile; every field has a default
internal class TileSlot(
    val icon: ImageVector,
    val title: String,
    val summary: String? = null,
    val headerAction: (@Composable RowScope.() -> Unit)? = null,
    val iconTint: Color? = null,
    val headerless: Boolean = false,
    val collapsible: Boolean = false,
    val hideable: Boolean = true,
    val canHide: Boolean = true,
    val maximizable: Boolean = false,
    val sizing: TileBodySizing = TileBodySizing.FILL,
    val headerParts: ((widthDp: Float) -> TileHeaderParts)? = null,
    val actionsCentered: Boolean = false,
    val body: @Composable (TileBodyInfo) -> Unit,
)

/**
 * What a surface asks of its owner. Layout changes ([onCommitLayout]) arrive once per gesture, on release (an arrow-key resize
 * or a splitter double-click is one gesture). [onMaximize] gets the tile to maximize, or null to restore. [onReclaimFocus] must give
 * root keyboard focus back (CLAUDE.md's clickable-steals-focus gotcha); it is called after every click and drag here.
 * [dropLabel] captions the drop overlay. [defaultDp] is the size a double-click on a splitter resets a node to (null = equalize).
 *
 * Three optional hooks let something outside the surface take part in a tile's move (the window chrome, for the toolbar tile):
 * [onDragPointer] hears the dragged tile and the pointer (window root px; null when the drag is over), [takeDrop] is asked first on
 * release and returns true when it handled the drop itself (the surface then drops nothing), and [suppressDrop] says that the pointer
 * is over a target of the outsider, so the surface shows no tile drop target for it.
 */
internal class TilingCallbacks<K>(
    val onCommitLayout: (TileNode<K>) -> Unit,
    val onHide: (K) -> Unit,
    val onToggleCollapse: (K) -> Unit,
    val onMaximize: (K?) -> Unit,
    val onReclaimFocus: () -> Unit,
    val dropLabel: (TileDropTarget<K>) -> String,
    val defaultDp: (TileNode<K>) -> Float? = { null },
    val onDragPointer: (K, Offset?) -> Unit = { _, _ -> },
    val takeDrop: (K, Offset) -> Boolean = { _, _ -> false },
    val suppressDrop: (K) -> Boolean = { false },
)

/**
 * A tile [id] being dragged from OUTSIDE the surface (the window chrome's toolbar bar) over it: the owner sets [pointerRoot] (window root
 * px, null when no such drag is over the surface) and the surface shows the same drop target and preview as for a tile move; the
 * surface keeps [resolver] up to date, so the owner can ask what a release at a root px point would drop [id] on.
 */
internal class TileExternalDrag<K>(val id: K) {
    var pointerRoot by mutableStateOf<Offset?>(null)
    var resolver: ((Offset) -> TileDropTarget<K>?)? = null
}

/** The receiver of a surface's content lambda: emits the tiles. */
internal interface TileScope<K> {
    /** Emits tile [id]. Does nothing unless [id] is visible in the layout (or parked); call it in a fixed order and branch only on visibility. */
    @Composable
    fun Tile(id: K, slot: TileSlot, modifier: Modifier = Modifier)

    /** The move handle of tile [id], for a [TileSlot.headerless] tile's own bar. Draws nothing while tiles are locked, maximized or stacked. */
    @Composable
    fun Grip(id: K)

    /** The maximize / restore button of tile [id], for a headerless tile's own bar. Draws nothing in a stacked surface. */
    @Composable
    fun MaximizeButton(id: K)
}

/** Which header parts fit [widthDp]; see [tileHeaderParts]. The title is only ever dropped by a slot's own [TileSlot.headerParts]. */
internal data class TileHeaderParts(val showIcon: Boolean, val showSummary: Boolean, val showTitle: Boolean = true)

/** The width (dp) of the card whose header is being composed, for a [TileSlot.headerAction] that adapts to it; 0 outside a header. */
internal val LocalTileHeaderWidthDp = compositionLocalOf { 0f }

/** The header parts that fit a card [widthDp] wide: the summary goes first, then the icon; the grip and the buttons always stay. */
internal fun tileHeaderParts(widthDp: Float): TileHeaderParts =
    TileHeaderParts(showIcon = widthDp >= TILE_HEADER_ICON_MIN_DP, showSummary = widthDp >= TILE_HEADER_SUMMARY_MIN_DP)

/** Arrow-key resize of a focused splitter: left/right on a ROW gap, up/down on a COLUMN gap; null for any other key. */
internal fun tileSplitterKeyDelta(axis: TileAxis, key: Key): Float? = when (axis) {
    TileAxis.ROW -> when (key) {
        Key.DirectionLeft -> -TILE_SPLITTER_KEY_STEP_DP
        Key.DirectionRight -> TILE_SPLITTER_KEY_STEP_DP
        else -> null
    }
    TileAxis.COLUMN -> when (key) {
        Key.DirectionUp -> -TILE_SPLITTER_KEY_STEP_DP
        Key.DirectionDown -> TILE_SPLITTER_KEY_STEP_DP
        else -> null
    }
}

// The layout ids the surface's custom Layout places children by. Cards are keyed by TileChromeSpec.keyOf, so the layout needs no
// knowledge of K; a child with neither id (a Popup's anchor, a Dialog's) is a stray and is measured at 0 x 0.
private data class TileCardKey(val key: String)

private data class TileGapKey(val index: Int)

private data class TileScrollbarKey(val index: Int)

private val LocalTileTitle = compositionLocalOf { "tile" }

// ---------------------------------------------------------------------------------------------------------------
// Shared state
// ---------------------------------------------------------------------------------------------------------------

/** The interaction state of one surface; lives as long as the surface does. Pointers are in host dp. */
private class TileDragState<K> {
    var dragged by mutableStateOf<K?>(null)
    var pointer by mutableStateOf<Offset?>(null)
    var pointerRoot: Offset? = null
    var hostRoot by mutableStateOf(Offset.Zero)
    var draft by mutableStateOf<TileNode<K>?>(null)
    val scroll = TileScrollState<K>()
    val measured = TileMeasureStore<K>()
}

// What one composition of a surface knows; it is the [TileScope] the content lambda receives. [composed] says which tiles are kept
// in the composition (visible ones and parked ones); a composed tile without a rect in [geometry] is measured 0 x 0.
private class TileScopeImpl<K>(
    val spec: TileChromeSpec<K>,
    val callbacks: TilingCallbacks<K>,
    val geometry: TileGeometry<K>,
    val host: TileRect,
    val fullLayout: TileNode<K>,
    val composed: (K) -> Boolean,
    val collapsed: Set<K>,
    val maximized: K?,
    val gripsEnabled: Boolean,
    val stacked: Boolean,
    val drag: TileDragState<K>,
) : TileScope<K> {
    var density: Float = 1f

    /** The geometry as it is on screen: scrolling columns shifted by their offsets, cards cut to their viewports. What a drop hit-tests. */
    fun visibleGeometry(): TileGeometry<K> = geometry.visibleCards(drag.scroll::offsetOf)

    /** The scrolling column the card of [id] sits in, if any. */
    fun regionOf(id: K): TileScrollRegion<K>? =
        geometry.cards.firstOrNull { it.section == id }?.scrollRegion?.let { geometry.scrollRegions.getOrNull(it) }

    @Composable
    override fun Tile(id: K, slot: TileSlot, modifier: Modifier) {
        if (composed(id)) key(id) { TileCard(this, id, slot, modifier.layoutId(TileCardKey(spec.keyOf(id)))) }
    }

    @Composable
    override fun Grip(id: K) {
        if (gripsEnabled) TileGrip(this, id, LocalTileTitle.current)
    }

    @Composable
    override fun MaximizeButton(id: K) {
        if (!stacked) TileMaximizeButton(this, id)
    }

    fun gripStart(id: K) {
        drag.dragged = id
        drag.pointer = null
        drag.pointerRoot = null
    }

    fun gripMove(rootPx: Offset) {
        drag.pointerRoot = rootPx
        drag.pointer = Offset((rootPx.x - drag.hostRoot.x) / density, (rootPx.y - drag.hostRoot.y) / density)
        drag.dragged?.let { callbacks.onDragPointer(it, rootPx) }
    }

    fun gripEnd(id: K) {
        // The outsider (the chrome's dock strips) is asked first; otherwise the drop is hit-tested again from the latest pointer
        // rather than the last composed target: the release can land before the next frame.
        val handled = drag.pointerRoot?.let { callbacks.takeDrop(id, it) } == true
        val drop = if (handled) null else drag.pointer?.let { tileDropTargetAt(visibleGeometry(), host.w, host.h, it.x, it.y, id) }
        gripCancel()
        if (drop != null) callbacks.onCommitLayout(dropTile(fullLayout, id, drop, spec.loneColumns))
    }

    fun gripCancel() {
        drag.dragged?.let { callbacks.onDragPointer(it, null) }
        drag.dragged = null
        drag.pointer = null
        drag.pointerRoot = null
        callbacks.onReclaimFocus()
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Wide surface
// ---------------------------------------------------------------------------------------------------------------

/**
 * Lays [fullLayout] out in a [widthDp] x [heightDp] dp host and runs [content] to emit the tiles. The tiles use [widthDp] less the spec's
 * `scrollEdgeRoomDp`: that strip at the right edge belongs to the surface (so a scrolling column that ends there can draw its scrollbar in it)
 * but holds no tile.
 *
 * [isVisible] and [parked] decide which leaves take part in the geometry (the layout is projected onto the visible,
 * non-parked ones); [collapsed] tiles shrink to their header; a [parked] tile stays composed but measured 0 x 0, so its body keeps
 * its state (a detached video player); [maximized] gives that one tile the whole host while every other tile stays composed at 0 x 0;
 * [locked] hides the grips (splitters still work). Layout edits go through [callbacks] on release only.
 */
@Composable
internal fun <K> TilingSurface(
    fullLayout: TileNode<K>,
    isVisible: (K) -> Boolean,
    collapsed: Set<K>,
    parked: Set<K>,
    maximized: K?,
    locked: Boolean,
    widthDp: Float,
    heightDp: Float,
    spec: TileChromeSpec<K>,
    callbacks: TilingCallbacks<K>,
    external: TileExternalDrag<K>? = null,
    content: @Composable TileScope<K>.() -> Unit,
) {
    val drag = remember { TileDragState<K>() }
    val layout = drag.draft ?: fullLayout
    val shown: (K) -> Boolean = { isVisible(it) && it !in parked }
    val projection = projectTileLayout(layout, shown)
    val sizingOf: (K) -> TileSizing? = { drag.measured.sizingOf(it, spec.scrollMaxDp) }
    val maximizedId: K? = maximized?.takeIf { id -> projection != null && id in tileLeafSections(projection.root) }
    val tilesWidthDp = max(0f, widthDp - spec.scrollEdgeRoomDp)
    val geometry: TileGeometry<K> = when {
        maximizedId != null -> maximizedTileGeometry(maximizedId, tilesWidthDp, heightDp)
        projection != null -> tileGeometry(projection.root, tilesWidthDp, heightDp, spec.geometrySpec(), collapsed, spec.scrolls, sizingOf)
        else -> TileGeometry(emptyList(), emptyList())
    }
    val composedIds = tileLeafSections(layout).filterTo(HashSet()) { isVisible(it) } + parked
    val scope = TileScopeImpl<K>(
        spec = spec,
        callbacks = callbacks,
        geometry = geometry,
        host = TileRect(0f, 0f, tilesWidthDp, heightDp),
        fullLayout = fullLayout,
        composed = { it in composedIds },
        collapsed = collapsed,
        maximized = maximizedId,
        gripsEnabled = !locked && maximizedId == null,
        stacked = false,
        drag = drag,
    )
    val density = LocalDensity.current.density
    scope.density = density
    drag.scroll.density = density
    drag.scroll.regions = geometry.scrollRegions
    // Drops are hit-tested against what is on screen, which differs from the geometry inside a scrolled column.
    val suppressed = drag.dragged?.let(callbacks.suppressDrop) == true
    val target = drag.dragged?.takeIf { !suppressed }?.let { dragged ->
        drag.pointer?.let { tileDropTargetAt(scope.visibleGeometry(), tilesWidthDp, heightDp, it.x, it.y, dragged) }
    }
    // A drag from outside answers to the same maths: the resolver turns a window pixel into the target a release there would drop on.
    val resolveExternal: (Offset) -> TileDropTarget<K>? = { root ->
        external?.let {
            tileDropTargetAt(scope.visibleGeometry(), tilesWidthDp, heightDp, (root.x - drag.hostRoot.x) / density, (root.y - drag.hostRoot.y) / density, it.id)
        }
    }
    SideEffect { external?.resolver = resolveExternal }
    DisposableEffect(external) { onDispose { external?.resolver = null } }
    val externalPointer = external?.pointerRoot
    val externalTarget = externalPointer?.let(resolveExternal)
    Box(Modifier.fillMaxSize().onGloballyPositioned { drag.hostRoot = it.positionInRoot() }) {
        TilingLayout(Modifier.fillMaxSize(), geometry, spec, drag.scroll) {
            scope.content()
            geometry.gaps.forEachIndexed { index, gap ->
                if (gap.resizable) {
                    key(index) {
                        fun resized(from: TileNode<K>, proj: TileProjection<K>, delta: Float) = resizeProjected(
                            from, proj, gap, delta, spec.minTileDp, spec.minPairShare, collapsed, spec.headerDp, spec.scrollMinDp, spec.scrollMaxDp, sizingOf,
                        )
                        TileSplitter(
                            index = index,
                            gap = gap,
                            spec = spec,
                            modifier = gap.scrollRegion?.let { geometry.scrollRegions.getOrNull(it) }
                                ?.let { region ->
                                    if (gap.scrollEndHandleOutsideViewport) {
                                        Modifier.tileScrollEndHandleRegion(drag.scroll, region)
                                    } else {
                                        Modifier.tileScrollRegion(drag.scroll, region, gap.rect)
                                    }
                                } ?: Modifier,
                            onDelta = { delta ->
                                val current = drag.draft ?: fullLayout
                                projectTileLayout(current, shown)?.let { proj -> drag.draft = resized(current, proj, delta) }
                            },
                            onDragEnd = {
                                val committed = drag.draft
                                drag.draft = null
                                if (committed != null && committed != fullLayout) callbacks.onCommitLayout(committed)
                                callbacks.onReclaimFocus()
                            },
                            onKeyResize = { delta ->
                                projectTileLayout(fullLayout, shown)?.let { proj ->
                                    val next = resized(fullLayout, proj, delta)
                                    if (next != fullLayout) callbacks.onCommitLayout(next)
                                }
                            },
                            onReset = {
                                projectTileLayout(fullLayout, shown)?.let { proj ->
                                    // A tile of a scrolling column goes back to the height its content asks for (no stored size).
                                    val reset = resetProjected(fullLayout, proj, gap) { node -> if (gap.scrolling) null else callbacks.defaultDp(node) }
                                    if (reset != fullLayout) callbacks.onCommitLayout(reset)
                                }
                                callbacks.onReclaimFocus()
                            },
                        )
                    }
                }
            }
            geometry.scrollRegions.forEachIndexed { index, region ->
                if (region.scrolls) {
                    key(index) {
                        Box(Modifier.layoutId(TileScrollbarKey(index))) {
                            TileRegionScrollbar(drag.scroll, region, "${spec.tagPrefix}-scrollbar-${spec.keyOf(region.key)}")
                        }
                    }
                }
            }
        }
        if (drag.dragged != null && !suppressed) TileDropOverlay(scope, target)
        if (externalPointer != null) TileDropOverlay(scope, externalTarget)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Narrow surface
// ---------------------------------------------------------------------------------------------------------------

/**
 * The same tiles stacked top to bottom in tree order, each [cardHeightDp] tall, inside one vertical scroll (narrow hosts): no
 * grips, splitters, collapsing or maximizing, but the hide button stays. [parked] tiles stay composed at 0 x 0.
 */
@Composable
internal fun <K> TileStackedSurface(
    fullLayout: TileNode<K>,
    isVisible: (K) -> Boolean,
    parked: Set<K>,
    cardHeightDp: Float,
    spec: TileChromeSpec<K>,
    callbacks: TilingCallbacks<K>,
    modifier: Modifier = Modifier,
    heightOf: (K) -> Float? = { null },
    content: @Composable TileScope<K>.() -> Unit,
) {
    val drag = remember { TileDragState<K>() }
    val order = projectTileLayout(fullLayout) { isVisible(it) && it !in parked }?.let { tileLeafSections(it.root) }.orEmpty()
    val composedIds = tileLeafSections(fullLayout).filterTo(HashSet()) { isVisible(it) } + parked
    val heights = order.map { heightOf(it) ?: cardHeightDp }
    val totalDp = max(0f, heights.sumOf { (it + spec.gapColumnDp).toDouble() }.toFloat() - spec.gapColumnDp)
    // The width comes from onSizeChanged, not BoxWithConstraints: that is a SubcomposeLayout, and a Popup-based dropdown several
    // levels inside one (the AI and filter panels' pickers) has thrown "layouts are not part of the same hierarchy". The first frame is
    // laid out at width 0 and settles on the next one.
    var widthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current.density
    val widthDp = widthPx / density
    ScrollColumnWithBar(modifier.fillMaxSize(), endGutter = 8.dp) {
        Box(Modifier.fillMaxWidth().height(totalDp.dp).onSizeChanged { widthPx = it.width }) {
            val geometry = stackedTileGeometry(order, widthDp, heights, spec.gapColumnDp)
            val scope = TileScopeImpl<K>(
                spec = spec,
                callbacks = callbacks,
                geometry = geometry,
                host = TileRect(0f, 0f, widthDp, totalDp),
                fullLayout = fullLayout,
                composed = { it in composedIds },
                collapsed = emptySet(),
                maximized = null,
                gripsEnabled = false,
                stacked = true,
                drag = drag,
            )
            TilingLayout(Modifier.fillMaxSize(), geometry, spec, drag.scroll) { scope.content() }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Layout
// ---------------------------------------------------------------------------------------------------------------

/**
 * Lays every child out at its rect of [geometry]: cards by [TileCardKey], splitters by [TileGapKey], scrollbars by [TileScrollbarKey]. A
 * card or splitter inside a scrolling column is placed shifted by that column's offset ([scroll], read while placing, so a scroll
 * re-places but does not recompose). A card with no rect (parked, or behind a maximized tile) and a child with no recognised layout id
 * (a Popup or Dialog anchor emitted by a content lambda) are measured 0 x 0 at the origin; the latter is logged once, because it
 * means a caller emitted something outside a Tile.
 */
@Composable
private fun <K> TilingLayout(
    modifier: Modifier,
    geometry: TileGeometry<K>,
    spec: TileChromeSpec<K>,
    scroll: TileScrollState<K>,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current.density
    val warned = remember { BooleanArray(1) }
    val cards = geometry.cards.associateBy { spec.keyOf(it.section) }
    val gaps = geometry.gaps
    val regions = geometry.scrollRegions
    val policy = remember(cards, gaps, regions, density) {
        MeasurePolicy { measurables, constraints ->
            val placed = measurables.map { measurable ->
                when (val id = measurable.layoutId) {
                    is TileCardKey -> placeAt(measurable, cards[id.key]?.rect, density, cards[id.key]?.scrollRegion)
                    is TileGapKey -> placeAt(measurable, gaps.getOrNull(id.index)?.rect, density, gaps.getOrNull(id.index)?.scrollRegion)
                    is TileScrollbarKey -> placeAt(measurable, regions.getOrNull(id.index)?.let { tileScrollbarRect(it) }, density, null)
                    else -> {
                        if (!warned[0]) {
                            warned[0] = true
                            AppLogger.warn("tiling", "A tiling surface child has no tile layout id and is measured 0x0 (emitted outside a Tile?)")
                        }
                        placeAt(measurable, null, density, null)
                    }
                }
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placed.forEach { tile ->
                    val shift = tile.region?.let { regions.getOrNull(it) }?.let { (scroll.offsetOf(it) * density).roundToInt() } ?: 0
                    tile.placeable.place(tile.x, tile.y - shift)
                }
            }
        }
    }
    Layout(content = content, modifier = modifier, measurePolicy = policy)
}

private class PlacedTile(val placeable: Placeable, val x: Int, val y: Int, val region: Int?)

private fun placeAt(measurable: Measurable, rect: TileRect?, density: Float, region: Int?): PlacedTile {
    if (rect == null) return PlacedTile(measurable.measure(Constraints.fixed(0, 0)), 0, 0, null)
    // Rounded per edge, so neighbouring cards and splitters never open a pixel seam.
    val left = (rect.x * density).roundToInt()
    val top = (rect.y * density).roundToInt()
    val width = ((rect.x + rect.w) * density).roundToInt() - left
    val height = ((rect.y + rect.h) * density).roundToInt() - top
    return PlacedTile(measurable.measure(Constraints.fixed(width.coerceAtLeast(0), height.coerceAtLeast(0))), left, top, region)
}

// ---------------------------------------------------------------------------------------------------------------
// Card
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun <K> TileCard(s: TileScopeImpl<K>, id: K, slot: TileSlot, modifier: Modifier) {
    val tc = tc()
    val rect = s.geometry.cardOf(id)
    val collapsed = rect != null && id in s.collapsed
    val shape = RoundedCornerShape(s.spec.cornerDp.dp)
    val headerHeight = if (slot.headerless) 0f else s.spec.headerDp + if (collapsed) 0f else 1f
    val region = s.regionOf(id)
    // A tile in a scrolling column whose slot says so is sized by what its body asks for: the body is measured at its natural height.
    val contentSized = region != null && rect != null && rect.w > 0f && slot.sizing != TileBodySizing.FILL
    val bodyInfo = TileBodyInfo(rect?.w ?: 0f, if (collapsed) 0f else max(0f, (rect?.h ?: 0f) - headerHeight), contentSized)
    val chromeDp = if (slot.headerless) 0f else s.spec.headerDp + 1f
    val flex = remember(id) { TileFlexHost() }
    val store = s.drag.measured
    flex.onFlex = { natural, cap ->
        store.edit(id, TileBodyMeasure(slot.sizing, chromeDp)) { it.copy(flexNaturalDp = natural, flexCapDp = cap) }
    }
    // The height the surface wants the body's flex region to show: the card minus its header and the body's fixed part.
    val fixedDp = store[id]?.fixedDp
    val allotted = if (contentSized && !collapsed && rect != null && fixedDp != null) max(0f, rect.h - chromeDp - fixedDp) else null
    SideEffect { flex.allottedDp = allotted }
    Column(
        modifier
            .then(if (region != null && rect != null) Modifier.tileScrollRegion(s.drag.scroll, region, rect) else Modifier)
            .alpha(if (s.drag.dragged == id) TILE_DRAGGED_CARD_ALPHA else 1f)
            .clip(shape)
            .background(tc.p)
            .border(1.dp, tc.br, shape)
            .testTag("${s.spec.tagPrefix}-card-${s.spec.keyOf(id)}"),
    ) {
        if (!slot.headerless) {
            TileHeader(s, id, slot, rect, collapsed)
            if (!collapsed) Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
        }
        // A collapsed tile keeps its body composed (so it keeps its state) but gives it no height.
        TileBodyHost(
            // One dp short on the right: the card's 1 dp border is drawn over its content, so a scrollbar at the body's edge would lie under it.
            modifier = if (collapsed) Modifier.fillMaxWidth().height(0.dp) else Modifier.weight(1f).fillMaxWidth().padding(end = 1.dp),
            contentSized = contentSized,
            onMeasured = { totalPx, density ->
                val fixed = max(0f, (totalPx - flex.shownPx) / density)
                store.edit(id, TileBodyMeasure(slot.sizing, chromeDp)) { it.copy(mode = slot.sizing, chromeDp = chromeDp, fixedDp = fixed) }
            },
        ) {
            val provided = flex.takeIf { contentSized && slot.sizing == TileBodySizing.FLEX }
            CompositionLocalProvider(LocalTileTitle provides slot.title, LocalTileFlex provides provided, LocalInTile provides true) { slot.body(bodyInfo) }
        }
    }
}

// The card's body area. Outside a scrolling column it is a Box (children at most as big as the area). In one, the body is measured at its
// natural height instead (unbounded, at the card's width) and [onMeasured] hears how tall it came out; the area itself keeps the card's size.
@Composable
private fun TileBodyHost(modifier: Modifier, contentSized: Boolean, onMeasured: (totalPx: Int, density: Float) -> Unit, content: @Composable () -> Unit) {
    val latest = rememberUpdatedState(onMeasured)
    val policy = remember(contentSized) {
        MeasurePolicy { measurables, constraints ->
            val width = constraints.maxWidth
            if (contentSized) {
                val natural = Constraints(minWidth = width, maxWidth = width, minHeight = 0, maxHeight = Constraints.Infinity)
                val placeables = measurables.map { it.measure(natural) }
                latest.value(placeables.maxOfOrNull { it.height } ?: 0, density)
                layout(width, constraints.maxHeight) { placeables.forEach { it.place(0, 0) } }
            } else {
                val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
                layout(width, constraints.maxHeight) { placeables.forEach { it.place(0, 0) } }
            }
        }
    }
    Layout(content = content, modifier = modifier, measurePolicy = policy)
}

@Composable
private fun <K> TileHeader(s: TileScopeImpl<K>, id: K, slot: TileSlot, rect: TileRect?, collapsed: Boolean) {
    val tc = tc()
    val prefix = s.spec.tagPrefix
    val key = s.spec.keyOf(id)
    val parts = slot.headerParts?.invoke(rect?.w ?: 0f) ?: tileHeaderParts(rect?.w ?: 0f)
    val callbacks = s.callbacks
    Row(
        Modifier.fillMaxWidth().height(s.spec.headerDp.dp).padding(horizontal = 8.dp).testTag("$prefix-header-$key"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TILE_HEADER_GAP_DP.dp),
    ) {
        if (s.gripsEnabled) TileGrip(s, id, slot.title)
        if (parts.showIcon) Icon(slot.icon, contentDescription = null, tint = slot.iconTint ?: tc.ts, modifier = Modifier.size(15.dp))
        val title: @Composable (Modifier) -> Unit = { titleModifier ->
            AppText(
                slot.title,
                color = tc.tx,
                fontSize = s.spec.titleSp.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = titleModifier,
            )
        }
        // The title (and summary) take the free room, which puts the header action at the right edge. With [TileSlot.actionsCentered] the title
        // part and the action share one group instead: the title takes what it needs (it shortens first when the room is short) and the action
        // is centred in the room that is left, before the header icons.
        val titleGroup: @Composable (Modifier) -> Unit = { groupModifier ->
            if (parts.showSummary && slot.summary != null) {
                Row(groupModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TILE_HEADER_GAP_DP.dp)) {
                    title(Modifier.weight(1f, fill = false))
                    AppText(
                        slot.summary,
                        color = tc.td,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            } else {
                title(groupModifier)
            }
        }
        // The header actions may place TileScope.MaximizeButton, which names its tile after this local (as in a headerless body).
        val actions: @Composable RowScope.() -> Unit = {
            slot.headerAction?.let { action ->
                CompositionLocalProvider(LocalTileTitle provides slot.title, LocalTileHeaderWidthDp provides (rect?.w ?: 0f)) { action(this) }
            }
        }
        if (slot.actionsCentered) {
            TitleWithCenteredActions(
                Modifier.weight(1f),
                title = { if (parts.showTitle) titleGroup(Modifier) },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TILE_HEADER_GAP_DP.dp)) { actions() }
                },
            )
        } else {
            if (parts.showTitle) titleGroup(Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
            actions()
        }
        if (slot.collapsible && !s.stacked) {
            val label = if (collapsed) "Expand" else "Collapse"
            TileIconButton(
                if (collapsed) Icons.Outlined.ChevronRight else Icons.Outlined.ExpandMore,
                "$label ${slot.title}",
                label,
                "$prefix-collapse-$key",
            ) {
                callbacks.onToggleCollapse(id)
                callbacks.onReclaimFocus()
            }
        }
        if (slot.maximizable && !s.stacked) TileMaximizeButton(s, id)
        if (slot.hideable) {
            TileIconButton(Icons.Outlined.VisibilityOff, "Hide ${slot.title}", "Hide section", "$prefix-hide-$key", enabled = slot.canHide) {
                callbacks.onHide(id)
                callbacks.onReclaimFocus()
            }
        }
    }
}

// The header's title part at the start and the actions centred in what is left of the width, at their natural width. When the two do not fit
// the title shortens first; the actions never sit closer than a header gap after it (or touching it when even that does not fit).
@Composable
private fun TitleWithCenteredActions(modifier: Modifier, title: @Composable () -> Unit, actions: @Composable () -> Unit) {
    Layout(content = { Box { title() }; Box { actions() } }, modifier = modifier) { measurables, constraints ->
        val gap = TILE_HEADER_GAP_DP.dp.roundToPx()
        val total = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val actionsPlaceable = measurables[1].measure(loose)
        val titleRoom = if (actionsPlaceable.width > 0) max(0, total - actionsPlaceable.width - gap) else total
        val titlePlaceable = measurables[0].measure(loose.copy(maxWidth = titleRoom))
        val height = max(titlePlaceable.height, actionsPlaceable.height)
        val free = max(0, total - titlePlaceable.width - actionsPlaceable.width)
        layout(total, height) {
            titlePlaceable.place(0, (height - titlePlaceable.height) / 2)
            val x = titlePlaceable.width + max(free / 2, min(gap, free))
            actionsPlaceable.place(x, (height - actionsPlaceable.height) / 2)
        }
    }
}

@Composable
private fun <K> TileMaximizeButton(s: TileScopeImpl<K>, id: K) {
    val isMaximized = s.maximized == id
    val label = if (isMaximized) "Restore" else "Maximize"
    TileIconButton(
        if (isMaximized) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
        "$label ${LocalTileTitle.current}",
        label,
        "${s.spec.tagPrefix}-maximize-${s.spec.keyOf(id)}",
    ) {
        s.callbacks.onMaximize(if (isMaximized) null else id)
        s.callbacks.onReclaimFocus()
    }
}

/** A 22dp icon button with a tooltip: the header buttons and the New tab's top bar buttons. */
@Composable
internal fun TileIconButton(
    icon: ImageVector,
    contentDescription: String,
    tooltip: String,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tc = tc()
    TooltipArea(tooltip = { ToolbarTooltip(tooltip, maxLines = 4) }) {
        HoverBox(
            modifier = Modifier
                .size(22.dp)
                .clip(CORNER_MD)
                .testTag(tag)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            hoverEnabled = enabled,
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (enabled) tc.ts else tc.td.copy(alpha = 0.4f),
                modifier = Modifier.align(Alignment.Center).size(15.dp),
            )
        }
    }
}

@Composable
private fun <K> TileGrip(s: TileScopeImpl<K>, id: K, title: String) {
    val tc = tc()
    val current by rememberUpdatedState(s)
    var gripRoot by remember { mutableStateOf(Offset.Zero) }
    // A drag whose grip leaves the composition (the tile got hidden mid-drag) never reports an end: drop its state here.
    DisposableEffect(id) {
        onDispose { if (current.drag.dragged == id) current.gripCancel() }
    }
    Box(
        Modifier
            .size(22.dp)
            .moveCursor()
            .onGloballyPositioned { gripRoot = it.positionInRoot() }
            .testTag("${s.spec.tagPrefix}-grip-${s.spec.keyOf(id)}")
            .semantics { contentDescription = "Move $title" }
            .pointerInput(id) {
                detectMoveDragGestures(
                    onDragStart = { down ->
                        current.gripStart(id)
                        current.gripMove(gripRoot + down)
                    },
                    onDragEnd = { current.gripEnd(id) },
                    onDragCancel = { current.gripCancel() },
                    onDrag = { change, _ ->
                        change.consume()
                        current.gripMove(gripRoot + change.position)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.DragIndicator, contentDescription = null, tint = tc.td, modifier = Modifier.size(16.dp))
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Splitter
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun <K> TileSplitter(
    index: Int,
    gap: TileGap,
    spec: TileChromeSpec<K>,
    modifier: Modifier,
    onDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onKeyResize: (Float) -> Unit,
    onReset: () -> Unit,
) {
    val tc = tc()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val currentKeyResize by rememberUpdatedState(onKeyResize)
    val currentReset by rememberUpdatedState(onReset)
    Box(
        modifier
            .layoutId(TileGapKey(index))
            .testTag("${spec.tagPrefix}-splitter-$index")
            .semantics { contentDescription = "Resize sections" }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { currentReset() }) }
            .focusable(interactionSource = interaction)
            .onKeyEvent { event ->
                val delta = if (event.type == KeyEventType.KeyDown) tileSplitterKeyDelta(gap.axis, event.key) else null
                delta?.let { currentKeyResize(it) }
                delta != null
            },
        contentAlignment = Alignment.Center,
    ) {
        // The splitter is never drawn, at rest, hovered or dragged: the gap, the resize cursor and the hit area are all there is. Its hit
        // area (and the keyboard focus ring) stops where the neighbouring cards' rounded corners start: a vertical splitter (ROW gap)
        // is inset top and bottom, a horizontal one (COLUMN gap) left and right.
        val corner = spec.cornerDp.dp
        val inset = if (gap.axis == TileAxis.ROW) PaddingValues(vertical = corner) else PaddingValues(horizontal = corner)
        Box(Modifier.fillMaxSize().padding(inset).testTag("${spec.tagPrefix}-splitter-line-$index"), contentAlignment = Alignment.Center) {
            if (gap.axis == TileAxis.ROW) {
                HDivider(onDragEnd = onDragEnd, showLine = false, onDelta = onDelta)
            } else {
                VDivider(onDragEnd = onDragEnd, showLine = false, onDelta = onDelta)
            }
            if (focused) Box(Modifier.fillMaxSize().border(1.dp, tc.ac, RoundedCornerShape(2.dp)))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Drag overlay
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun <K> TileDropOverlay(s: TileScopeImpl<K>, target: TileDropTarget<K>?) {
    val tc = tc()
    val prefix = s.spec.tagPrefix
    val shape = RoundedCornerShape(s.spec.cornerDp.dp)
    val preview = target?.let { tileDropPreview(s.visibleGeometry(), s.host.w, s.host.h, it) }
    Box(Modifier.fillMaxSize()) {
        TileEdge.entries.forEach { edge ->
            TilePageStrip(s, edge, active = target == TileDropTarget.OnPage(edge))
        }
        if (target != null && preview != null) {
            Box(
                Modifier
                    .offset(preview.x.dp, preview.y.dp)
                    .size(preview.w.dp, preview.h.dp)
                    .testTag("$prefix-drop-preview")
                    .background(tc.ac.copy(alpha = TILE_PREVIEW_FILL_ALPHA), shape)
                    .border(2.dp, tc.ac, shape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.background(tc.ac, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    AppText(s.callbacks.dropLabel(target), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun <K> TilePageStrip(s: TileScopeImpl<K>, edge: TileEdge, active: Boolean) {
    val tc = tc()
    val band = TILE_PAGE_BAND_DP
    val vertical = edge.axis == TileAxis.ROW
    val x = if (edge == TileEdge.RIGHT) s.host.w - band else 0f
    val y = if (edge == TileEdge.BOTTOM) s.host.h - band else 0f
    val label = s.callbacks.dropLabel(TileDropTarget.OnPage(edge))
    Box(
        Modifier
            .offset(x.dp, y.dp)
            .size(if (vertical) band.dp else s.host.w.dp, if (vertical) s.host.h.dp else band.dp)
            .testTag("${s.spec.tagPrefix}-page-strip-${edge.name.lowercase()}")
            .drawBehind {
                val dash = TILE_STRIP_DASH_DP.dp.toPx()
                if (active) drawRect(tc.ac.copy(alpha = TILE_STRIP_FILL_ALPHA))
                drawRoundRect(
                    color = tc.ac.copy(alpha = if (active) 1f else TILE_STRIP_BORDER_ALPHA),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            label,
            color = tc.ac,
            fontSize = 10.sp,
            maxLines = 1,
            modifier = Modifier.wrapContentSize(unbounded = true).then(if (vertical) Modifier.rotate(TILE_VERTICAL_TEXT_DEGREES) else Modifier),
        )
    }
}
