package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.indagium.model.LogTab
import com.indagium.model.LogViewSectionId
import com.indagium.model.LogViewTileGroup
import com.indagium.model.MAX_CAPTURE_MIRROR_SPLIT
import com.indagium.model.MIN_CAPTURE_MIRROR_SPLIT
import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import kotlin.math.abs

// The single log view as tiles: FileView's Row of (filter panel | log | Notes / AI / Video sidebar) is one tiling surface here. The
// content lambda emits the tiles in a FIXED order (Toolbar, filters, Original + Log, Notes, Diagram library, AI, Video) and branches only
// on visibility, never on the layout, so moving a tile only changes its rect and never rebuilds its composition (the log's scroll,
// the notes editor, a playing video all survive a drag). The pure rules live in LogViewLayout.kt / LogViewLayoutState.kt.

private const val LOG_VIEW_TITLE_SP = 12f
private const val LOG_VIEW_CORNER_DP = 8
internal const val LOG_VIEW_HOST_PAD_DP = 6
private const val LOG_VIEW_STACKED_SHARE = 0.85f
private const val LOG_VIEW_STACKED_MIN_DP = 280f
private const val LOG_VIEW_STACKED_MAX_DP = 520f
private const val DEFAULT_FILTER_COLUMN_DP = 220f
private const val DEFAULT_RIGHT_COLUMN_DP = 360f
private const val DEFAULT_LOG_LEVEL_DP = 80f
private const val PIN_EPSILON_DP = 0.5f

/**
 * The log view's tile chrome: 32dp headers, 8dp / 6dp gaps, `tile-*` tags, and scrolling side columns ([logViewScrollsInColumn]) with a splitter
 * under their last tile. A column's scrollbar is drawn in the 8 dp gap beside it (1 dp, the 6 dp bar, 1 dp), or, for a column at the right edge, in the
 * 6 dp host padding (1 dp, a 4 dp bar, 1 dp), which is therefore given to the surface (the edge room of [LOG_VIEW_CHROME_SPEC]) rather than left outside it.
 */
internal val LOG_VIEW_CHROME_SPEC = TileChromeSpec<LogViewSectionId>(
    headerDp = LOG_VIEW_TILE_HEADER_DP,
    titleSp = LOG_VIEW_TITLE_SP,
    gapRowDp = LOG_VIEW_GAP_ROW_DP,
    gapColumnDp = LOG_VIEW_GAP_COLUMN_DP,
    cornerDp = LOG_VIEW_CORNER_DP,
    minTileDp = LOG_VIEW_MIN_TILE_DP,
    minPairShare = LOG_VIEW_MIN_PAIR_SHARE,
    tagPrefix = "tile",
    keyOf = { it.key },
    scrollMinDp = LOG_VIEW_PIN_MIN_DP,
    scrollMaxDp = LOG_VIEW_PIN_MAX_DP,
    scrolls = ::logViewScrollsInColumn,
    scrollEdgeRoomDp = LOG_VIEW_HOST_PAD_DP.toFloat(),
    scrollEndHandle = true,
)

/**
 * What FileView hands the host to keep keyboard focus working: the four panels' requesters (the Filters one lands on the first visible
 * filter tile), [onPanelFocused] (which requester just took focus, for F6), [returnToLog] (gives focus back to the log after a click).
 */
internal class LogViewPanelFocus(
    val filters: FocusRequester,
    val log: FocusRequester,
    val notes: FocusRequester,
    val ai: FocusRequester,
    val onPanelFocused: (FocusRequester) -> Unit,
    val returnToLog: () -> Unit,
)

/** What FileView passes for the filter panel and the capture bits; grouped so the host's signature stays readable. */
internal class LogViewHostInputs(
    val filterSearchRequest: FilterSearchRequest?,
    val onFilterSearchRequestConsumed: (FilterSearchRequest) -> Unit,
    val captureDeviceEvents: CaptureDeviceEventsModel?,
    val onExportCaptureRange: (() -> Unit)?,
)

/** The default size a double-click on a splitter resets a pinned node to: the legacy column widths, Log level's natural height. */
internal fun logViewDefaultDp(node: TileNode<LogViewSectionId>): Float? {
    if (node.sizeDp == null) return null
    val leaves = tileLeafSections(node)
    return when {
        leaves == listOf(LogViewSectionId.LOG_LEVEL) -> DEFAULT_LOG_LEVEL_DP
        leaves == listOf(LogViewSectionId.TOOLBAR) -> TOOLBAR_TILE_COLUMN_DP
        leaves.all { it.group == LogViewTileGroup.FILTERS } -> DEFAULT_FILTER_COLUMN_DP
        leaves.all { it.group != LogViewTileGroup.NONE } -> DEFAULT_RIGHT_COLUMN_DP
        else -> null
    }
}

/** The tiles that sit in a COLUMN split of [root]: only those shrink to their header when collapsed. */
internal fun <K> columnTiles(root: TileNode<K>): Set<K> = when (root) {
    is TileLeaf -> emptySet()
    is TileSplit -> root.children.flatMapTo(HashSet()) { child ->
        if (root.axis == TileAxis.COLUMN && child is TileLeaf) setOf(child.section) else columnTiles(child)
    }
}

/** The Video tile's weight and pinned size in [root], or null when Video is not in the tree. */
internal fun TileNode<LogViewSectionId>.videoSlot(): Pair<Float, Float?>? = when (this) {
    is TileLeaf -> if (section == LogViewSectionId.VIDEO) weight to sizeDp else null
    is TileSplit -> children.firstNotNullOfOrNull { it.videoSlot() }
}

/** [this] with the Video tile's weight and pinned size replaced. */
internal fun TileNode<LogViewSectionId>.withVideoSlot(weight: Float, sizeDp: Float?): TileNode<LogViewSectionId> = when (this) {
    is TileLeaf -> if (section == LogViewSectionId.VIDEO) copy(weight = weight, sizeDp = sizeDp) else this
    is TileSplit -> copy(children = children.map { it.withVideoSlot(weight, sizeDp) })
}

/**
 * The log view's body. Measures itself with onSizeChanged (NOT BoxWithConstraints: that is a SubcomposeLayout, and the Popup-based
 * dropdowns in the AI and filter panels have thrown "layouts are not part of the same hierarchy" inside one), lays the tiles out
 * with a [TilingSurface] (or stacked below [LOG_VIEW_STACK_BELOW_DP]) and writes layout edits through [commitLogViewLayout] on release.
 */
@Composable
internal fun LogViewTilingHost(
    state: AppState,
    tab: LogTab,
    focus: LogViewPanelFocus,
    inputs: LogViewHostInputs,
    modifier: Modifier = Modifier,
) {
    LogViewTilingFrame(state, modifier) { widthDp, heightDp -> LogViewTileSurface(state, tab, focus, inputs, widthDp, heightDp) }
}

/**
 * The padded host both the single log view and the compare view tile into: it measures itself (see [LogViewTilingHost] for why with
 * onSizeChanged), restores a maximized tile on Esc, and gives [content] its size in dp once there is one.
 */
@Composable
internal fun LogViewTilingFrame(state: AppState, modifier: Modifier, content: @Composable (widthDp: Float, heightDp: Float) -> Unit) {
    val tc = tc()
    val density = LocalDensity.current.density
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    // Esc restores a maximized tile. It is taken on the preview pass: the log viewer swallows every Esc (it clears the selection), so
    // a bubbling handler would never see it from the log; a find bar or an editor open inside the tile gets its Esc on the next press.
    Box(
        modifier.background(tc.bg).onPreviewKeyEvent { event ->
            val restoring = event.type == KeyEventType.KeyDown && event.key == Key.Escape && state.logViewMaximized != null
            if (restoring) state.logViewMaximized = null
            restoring
        },
    ) {
        // No padding at the right: that strip belongs to the surface (LOG_VIEW_CHROME_SPEC's edge room), where a scrolling column's scrollbar goes.
        Box(
            Modifier.fillMaxSize()
                .padding(start = LOG_VIEW_HOST_PAD_DP.dp, top = LOG_VIEW_HOST_PAD_DP.dp, bottom = LOG_VIEW_HOST_PAD_DP.dp)
                .onSizeChanged { sizePx = it },
        ) {
            val widthDp = sizePx.width / density
            val heightDp = sizePx.height / density
            // Until the first measurement there is nothing to lay out; mounting a surface at 0 x 0 would only be torn down again.
            if (sizePx.width > 0 && sizePx.height > 0) content(widthDp, heightDp)
        }
    }
}

/**
 * [TilingSurface] on a wide host, [TileStackedSurface] below [LOG_VIEW_STACK_BELOW_DP]: the one place the log view and the compare view
 * pick between the two, so both stack at the same width with the same card height.
 */
@Composable
internal fun LogViewSurface(
    layout: TileNode<LogViewSectionId>,
    isVisible: (LogViewSectionId) -> Boolean,
    collapsed: Set<LogViewSectionId>,
    parked: Set<LogViewSectionId>,
    locked: Boolean,
    maximized: LogViewSectionId?,
    widthDp: Float,
    heightDp: Float,
    callbacks: TilingCallbacks<LogViewSectionId>,
    external: TileExternalDrag<LogViewSectionId>? = null,
    content: @Composable TileScope<LogViewSectionId>.() -> Unit,
) {
    if (widthDp < LOG_VIEW_STACK_BELOW_DP) {
        TileStackedSurface(
            fullLayout = layout,
            isVisible = isVisible,
            parked = parked,
            cardHeightDp = (heightDp * LOG_VIEW_STACKED_SHARE).coerceIn(LOG_VIEW_STACKED_MIN_DP, LOG_VIEW_STACKED_MAX_DP),
            spec = LOG_VIEW_CHROME_SPEC,
            callbacks = callbacks,
            // The toolbar's card is a slim one, not a screenful.
            heightOf = { id -> TOOLBAR_STACKED_CARD_DP.takeIf { id == LogViewSectionId.TOOLBAR } },
            modifier = Modifier.padding(end = LOG_VIEW_HOST_PAD_DP.dp),
            content = content,
        )
    } else {
        TilingSurface(
            fullLayout = layout,
            isVisible = isVisible,
            collapsed = collapsed,
            parked = parked,
            maximized = maximized,
            locked = locked,
            widthDp = widthDp,
            heightDp = heightDp,
            spec = LOG_VIEW_CHROME_SPEC,
            callbacks = callbacks,
            external = external,
            content = content,
        )
    }
}

@Composable
private fun LogViewTileSurface(
    state: AppState,
    tab: LogTab,
    focus: LogViewPanelFocus,
    inputs: LogViewHostInputs,
    widthDp: Float,
    heightDp: Float,
) {
    val settings = state.settings
    val ctx = state.logViewTileContext(tab)
    val isVisible: (LogViewSectionId) -> Boolean = { logViewTileVisible(it, ctx, settings.logViewHidden) }
    val baseLayout = state.currentLogViewLayout()

    // The chrome's toolbar bar can be dropped into these tiles: it asks the host to put the toolbar tile at the drop target.
    val bridge = LocalToolbarDock.current
    val currentBase by rememberUpdatedState(baseLayout)
    DisposableEffect(bridge, state) {
        bridge?.dropInLayout = { target -> state.updateSettings { it.withToolbarDroppedInLayout(dropTile(currentBase, LogViewSectionId.TOOLBAR, target)) } }
        onDispose { bridge?.dropInLayout = null }
    }

    // A capture tab keeps the video height remembered for its device: Video is pinned to that share of the host height while the tab
    // is active, and a resize of Video writes back to the per-device layout instead of the global one.
    val captureTab = tab.captureSessionId != null || tab.captureSourceSessionId != null
    val baseVideo = baseLayout.videoSlot()
    val videoInColumn = LogViewSectionId.VIDEO in columnTiles(baseLayout)
    val videoPin = if (captureTab && videoInColumn && baseVideo != null && heightDp > 0f) {
        state.captureMirrorLayoutFor(tab.id).videoSplit.coerceIn(MIN_CAPTURE_MIRROR_SPLIT, MAX_CAPTURE_MIRROR_SPLIT) * heightDp
    } else {
        null
    }
    val layout = if (videoPin != null && baseVideo != null) baseLayout.withVideoSlot(baseVideo.first, videoPin) else baseLayout

    val projection = projectTileLayout(layout, isVisible)
    val collapsibleTiles = projection?.let { columnTiles(it.root) }.orEmpty()
    val collapsedTiles = collapsibleTiles.filterTo(HashSet()) { state.isLogViewTileCollapsed(it) }
    val toggles = remember { HashMap<LogViewSectionId, () -> Unit>() }

    // A detached video player must stay composed (it owns its window), so Video is parked at 0 x 0 instead of dropped.
    var videoExpanded by remember(tab.id) { mutableStateOf(true) }
    val parked = if (!videoExpanded && isVisible(LogViewSectionId.VIDEO)) setOf(LogViewSectionId.VIDEO) else emptySet()

    val commit: (TileNode<LogViewSectionId>) -> Unit = { tree ->
        if (videoPin != null && baseVideo != null) {
            val resized = tree.videoSlot()?.second
            if (resized != null && abs(resized - videoPin) > PIN_EPSILON_DP) {
                state.rememberCaptureMirrorLayout(tab.id) { it.copy(videoSplit = resized / heightDp) }
            }
            val restored = tree.withVideoSlot(baseVideo.first, baseVideo.second)
            if (restored != baseLayout) state.commitLogViewLayout(restored)
        } else {
            state.commitLogViewLayout(tree)
        }
    }
    val callbacks = TilingCallbacks(
        onCommitLayout = commit,
        onHide = state::hideLogViewTile,
        onToggleCollapse = { id -> toggles[id]?.invoke() ?: state.toggleLogViewTileCollapsed(id) },
        onMaximize = { state.logViewMaximized = it },
        onReclaimFocus = focus.returnToLog,
        dropLabel = ::logViewDropLabel,
        defaultDp = ::logViewDefaultDp,
        // The toolbar tile can be dragged out of the tiles onto the window chrome's edge strips; see ToolbarTile.kt.
        onDragPointer = { id, pointer -> if (id == LogViewSectionId.TOOLBAR) bridge?.tileDragPointerRoot = pointer },
        takeDrop = { id, pointer -> id == LogViewSectionId.TOOLBAR && bridge?.tileRelease?.invoke(pointer) == true },
        suppressDrop = { id -> id == LogViewSectionId.TOOLBAR && bridge?.let { b -> b.tileDragPointerRoot?.let(b::overChromeZone) } == true },
    )
    val content: @Composable TileScope<LogViewSectionId>.() -> Unit = {
        LogViewTiles(state, tab, focus, inputs, isVisible, collapsibleTiles, toggles) { videoExpanded = it }
    }
    LogViewSurface(
        layout, isVisible, collapsedTiles, parked, settings.logViewTilesLocked, state.logViewMaximized, widthDp, heightDp, callbacks, bridge?.external, content,
    )
}

// The tiles, in the fixed emission order. Every composable here emits only through Tile (the filter panel through its
// TiledSectionHost, the log viewer through LogViewerTiles), because a stray layout node would be measured 0 x 0.
@Composable
private fun TileScope<LogViewSectionId>.LogViewTiles(
    state: AppState,
    tab: LogTab,
    focus: LogViewPanelFocus,
    inputs: LogViewHostInputs,
    isVisible: (LogViewSectionId) -> Boolean,
    collapsibleTiles: Set<LogViewSectionId>,
    toggles: MutableMap<LogViewSectionId, () -> Unit>,
    onVideoExpandedChange: (Boolean) -> Unit,
) {
    val visibleFilters = LOG_VIEW_FILTER_TILES.filterTo(HashSet()) { isVisible(it) }
    ToolbarTile(state, focus.returnToLog)
    BoundFilterPanel(
        state = state,
        tab = tab,
        width = 0f,
        liveMaxWidth = { 0f },
        focusRequester = focus.filters,
        filterBarVisible = state.filterBarVisible,
        filterSearchRequest = inputs.filterSearchRequest,
        onFilterSearchRequestConsumed = inputs.onFilterSearchRequestConsumed,
        onPanelFocusChanged = { focused -> if (focused) focus.onPanelFocused(focus.filters) },
        captureDeviceEvents = inputs.captureDeviceEvents,
        onCaptureDeviceEventReturnFocus = focus.returnToLog,
        host = TiledSectionHost(this, visibleFilters, collapsibleTiles, toggles),
    )
    BoundLogViewer(
        state = state,
        tab = tab,
        focusRequester = focus.log,
        onPanelFocusChanged = { focused -> if (focused) focus.onPanelFocused(focus.log) },
        onExportCaptureRange = inputs.onExportCaptureRange,
        tiles = LogViewerTiles(this, LogViewSectionId.LOG, LogViewSectionId.ORIGINAL),
    )
    val notesLocked = rememberNotesEditLocked(state, tab)
    CompositionLocalProvider(LocalNotesEditLocked provides notesLocked) {
        NotesTile(state, tab, focus, collapsible = LogViewSectionId.NOTES in collapsibleTiles, diagramsShown = isVisible(LogViewSectionId.DIAGRAMS))
        DiagramsTile(state, tab, collapsible = LogViewSectionId.DIAGRAMS in collapsibleTiles)
    }
    AiTile(state, tab, focus, collapsible = LogViewSectionId.AI in collapsibleTiles)
    VideoTile(state, tab, collapsible = LogViewSectionId.VIDEO in collapsibleTiles, onExpandedChange = onVideoExpandedChange)
}

@Composable
internal fun TileScope<LogViewSectionId>.NotesTile(
    state: AppState,
    tab: LogTab,
    focus: LogViewPanelFocus,
    collapsible: Boolean,
    diagramsShown: Boolean,
) {
    // The Notes actions are part of the tile's header row (NotesHeaderActions.kt), so the tile, not the panel, owns the Copy feedback they and the
    // panel's preview dialog and shortcut share.
    val copyFeedback = rememberCopyFeedback(tab.id) { format, done -> state.copyAnnotationFormatAsync(tab.id, format, done) }
    val actions = NotesActionModel(
        tab = tab,
        defaultFormat = state.settings.annotationCopyFormat,
        feedback = copyFeedback,
        locked = LocalNotesEditLocked.current,
        recentNotes = state.recentNotesForTab(tab),
        recentMenuOpen = state.recentNotesMenuOpen,
        activeNotePath = state.activeNoteFilePath(tab),
        onToggleMd = { state.toggleMd(tab.id) },
        onSave = { state.saveAnalysis(tab.id) },
        onNewAnalysis = { state.newAnalysis(tab.id) },
        onToggleRecentNotes = { state.toggleRecentNotesMenu() },
        onOpenNote = { state.openNoteFileAsync(tab.id, it) },
        onLocateLog = { state.locateLogForTab(tab.id, it) },
        onReclaimFocus = { runCatching { focus.notes.requestFocus() } },
    )
    // Collapse (when it is in a column), maximize and hide are the header icons the buttons must leave room for.
    val headerIcons = (if (collapsible) 1 else 0) + 2
    Tile(
        LogViewSectionId.NOTES,
        TileSlot(
            icon = Icons.AutoMirrored.Outlined.StickyNote2,
            title = logViewTileTitle(LogViewSectionId.NOTES),
            summary = notesSummary(tab.annotations),
            headerAction = {
                val layout = notesHeaderLayout(LocalTileHeaderWidthDp.current, actions.hasLocate, actions.hasRecent, headerIcons)
                NotesActionButtons(actions, layout)
            },
            headerParts = { width -> notesHeaderParts(width, notesHeaderLayout(width, actions.hasLocate, actions.hasRecent, headerIcons)) },
            actionsFollowTitle = true,
            collapsible = collapsible,
            maximizable = true,
            // The note list below the header is the flex part (AnnotationPanel).
            sizing = TileBodySizing.FLEX,
            body = { info ->
                BoundAnnotationPanel(
                    state = state,
                    tab = tab,
                    width = info.widthDp,
                    focusRequester = focus.notes,
                    onPanelFocusChanged = { focused -> if (focused) focus.onPanelFocused(focus.notes) },
                    // With the Diagram library as its own tile the Notes panel leaves its built-in section out.
                    showDiagramLibrary = !diagramsShown,
                    actionsInHeader = true,
                    copyFeedbackState = copyFeedback,
                )
            },
        ),
    )
}

@Composable
internal fun TileScope<LogViewSectionId>.DiagramsTile(state: AppState, tab: LogTab, collapsible: Boolean) {
    val diagramCount = state.seq3Sessions.libraryForTab(tab).size
    Tile(
        LogViewSectionId.DIAGRAMS,
        TileSlot(
            icon = Icons.Outlined.AccountTree,
            title = logViewTileTitle(LogViewSectionId.DIAGRAMS),
            summary = diagramCount.takeIf { it > 0 }?.let { "$it saved" },
            headerAction = { DiagramLibraryHeaderAction(state, tab) },
            collapsible = collapsible,
            maximizable = true,
            // The list is already bounded (DiagramLibrarySection), so the tile is exactly as tall as that.
            sizing = TileBodySizing.CONTENT,
            body = { info -> TileBodyColumn(info) { DiagramLibraryTileBody(state, tab) } },
        ),
    )
}

@Composable
internal fun TileScope<LogViewSectionId>.AiTile(state: AppState, tab: LogTab, focus: LogViewPanelFocus, collapsible: Boolean) {
    Tile(
        LogViewSectionId.AI,
        TileSlot(
            icon = Icons.Outlined.AutoAwesome,
            title = logViewTileTitle(LogViewSectionId.AI),
            collapsible = collapsible,
            maximizable = true,
            // The conversation is the flex part; the prompt composer under it stays (AiSidebarPanel).
            sizing = TileBodySizing.FLEX,
            body = { info ->
                AiSidebarPanel(
                    state = state,
                    tab = tab,
                    focusRequester = focus.ai,
                    onPanelFocusChanged = { focused -> if (focused) focus.onPanelFocused(focus.ai) },
                    bodyHeightDp = info.heightDp,
                )
            },
        ),
    )
}

@Composable
internal fun TileScope<LogViewSectionId>.VideoTile(state: AppState, tab: LogTab, collapsible: Boolean, onExpandedChange: (Boolean) -> Unit) {
    Tile(
        LogViewSectionId.VIDEO,
        TileSlot(
            icon = Icons.Outlined.Movie,
            title = logViewTileTitle(LogViewSectionId.VIDEO),
            collapsible = collapsible,
            maximizable = true,
            // The player reports "detached" through this local; the host then parks the tile at 0 x 0 instead of removing it.
            body = { CompositionLocalProvider(LocalVideoSidebarExpandedChange provides onExpandedChange) { VideoTileBody(state, tab) } },
        ),
    )
}
