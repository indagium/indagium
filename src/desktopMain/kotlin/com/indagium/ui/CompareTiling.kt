package com.indagium.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.CompareFilterTarget
import com.indagium.model.LogTab
import com.indagium.model.LogViewSectionId
import com.indagium.utils.TS_UNKNOWN
import com.indagium.utils.parseMillisOfDay
import kotlinx.coroutines.launch

// The compare view on tiles: the strip above them ("Filters apply to A | B | Both" and the scroll link), the tiling host, the tiles in
// their fixed emission order, the A / B badges and the timestamp-based sync scroll. CompareView.kt (focus, effective tabs) calls the host;
// the layout rules are in LogViewLayout.kt / CompareLayoutState.kt, the filter rules in CompareFilters.kt.

private const val STRIP_HEIGHT_DP = 32
private const val BADGE_SIZE_DP = 18
private const val PILL_HEIGHT_DP = 22

/** Test tag of the strip's A | B | Both switch. */
internal const val COMPARE_TARGET_TAG = "compare-target"

/** Test tag of the strip's B tab picker. */
internal const val COMPARE_PICKER_TAG = "compare-tab-picker"

/** Test tag of the strip's scroll link toggle. */
internal const val COMPARE_SYNC_TAG = "compare-sync-scroll"

/** Test tag of the file name in the header of [side] ("a" / "b"). */
internal fun compareNameTag(side: String): String = "compare-name-${side.lowercase()}"

/** Test tag of the A / B badge of [side] ("a" / "b"), see [compareBadgeTag]. */
internal fun compareBadgeTag(side: String): String = "compare-badge-${side.lowercase()}"

/**
 * What CompareView hands the host to keep keyboard focus working: [panels] (its `log` is side A's requester), side B's requester
 * ([rightLog]) and the search request for the filter panel.
 */
internal class ComparePanelFocus(
    val panels: LogViewPanelFocus,
    val rightLog: FocusRequester,
    val filterSearchRequest: FilterSearchRequest?,
    val onFilterSearchRequestConsumed: (FilterSearchRequest) -> Unit,
)

// ---------------------------------------------------------------------------------------------------------------
// The strip above the tiles
// ---------------------------------------------------------------------------------------------------------------

private val TARGET_LABELS = listOf("A", "B", "Both")

/**
 * "Filters apply to  A | B | Both", the picker that chooses which tab B shows (the full [CompareTabPicker]: every tab as a pill, an
 * overflow list and drag reorder, in the width the other two leave) and the scroll link. It sits above the tiles because the tiles can
 * be moved, a toolbar must not.
 */
@Composable
internal fun CompareFilterStrip(state: AppState, left: LogTab, right: LogTab, onReclaimFocus: () -> Unit) {
    val tc = tc()
    val target = state.compareFilterTarget
    Row(
        Modifier.fillMaxWidth().height(STRIP_HEIGHT_DP.dp).background(tc.p2).padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText("Filters apply to", color = tc.ts, fontSize = 11.sp)
        SegmentedControl(
            options = TARGET_LABELS,
            selectedIndices = setOf(indexOfTarget(target)),
            onToggle = { index ->
                state.updateCompareFilterTarget(targetOfIndex(index))
                onReclaimFocus()
            },
            segmentHeight = PILL_HEIGHT_DP.dp,
            segmentFontSize = 11.sp,
            segmentHorizontalPadding = 10.dp,
            modifier = Modifier.testTag(COMPARE_TARGET_TAG),
        )
        AppText("B:", color = tc.ts, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        CompareTabPicker(
            state = state,
            leftTab = left,
            rightTab = right,
            modifier = Modifier.weight(1f).height(PILL_HEIGHT_DP.dp).testTag(COMPARE_PICKER_TAG),
            onReclaimFocus = onReclaimFocus,
        )
        CompareTabPill(
            label = "Link scroll",
            icon = Icons.Outlined.Link,
            active = state.settings.compareSyncScroll,
            tooltip = "Scroll A and B together by timestamp",
            modifier = Modifier.height(PILL_HEIGHT_DP.dp).testTag(COMPARE_SYNC_TAG),
            onClick = {
                state.updateSettings { it.copy(compareSyncScroll = !it.compareSyncScroll) }
                onReclaimFocus()
            },
        )
    }
}

// The switch lists "A", "B", "Both"; spelled out so a reorder of the enum cannot silently scramble the labels.
private fun indexOfTarget(target: CompareFilterTarget): Int = when (target) {
    CompareFilterTarget.A -> 0
    CompareFilterTarget.B -> 1
    CompareFilterTarget.BOTH -> 2
}

private fun targetOfIndex(index: Int): CompareFilterTarget = when (index) {
    0 -> CompareFilterTarget.A
    1 -> CompareFilterTarget.B
    else -> CompareFilterTarget.BOTH
}

// ---------------------------------------------------------------------------------------------------------------
// The host
// ---------------------------------------------------------------------------------------------------------------

/**
 * The compare view's tiles. [shownLeft] / [shownRight] are the tabs as displayed (their filter replaced per [compareDisplayedFilters]);
 * [left] / [right] the stored tabs the panels edit. Measures itself like [LogViewTilingHost] and writes layout edits through
 * [commitCompareLayout] on release.
 */
@Composable
internal fun CompareTilingHost(
    state: AppState,
    left: LogTab,
    right: LogTab,
    shownLeft: LogTab,
    shownRight: LogTab,
    focus: ComparePanelFocus,
    modifier: Modifier = Modifier,
) {
    LogViewTilingFrame(state, modifier) { widthDp, heightDp ->
        CompareTileSurface(state, left, right, shownLeft, shownRight, focus, widthDp, heightDp)
    }
}

@Composable
private fun CompareTileSurface(
    state: AppState,
    left: LogTab,
    right: LogTab,
    shownLeft: LogTab,
    shownRight: LogTab,
    focus: ComparePanelFocus,
    widthDp: Float,
    heightDp: Float,
) {
    val settings = state.settings
    val ctx = state.compareTileContext(left, right)
    val hidden = settings.compareHidden
    val isVisible: (LogViewSectionId) -> Boolean = { logViewTileVisible(it, ctx, hidden) }
    val layout = state.currentCompareLayout()
    val projection = projectTileLayout(layout, isVisible)
    val collapsibleTiles = projection?.let { columnTiles(it.root) }.orEmpty()
    val collapsedTiles = collapsibleTiles.filterTo(HashSet()) { state.isLogViewTileCollapsed(it) }
    val toggles = remember { HashMap<LogViewSectionId, () -> Unit>() }
    // A detached video player must stay composed (it owns its window), so Video is parked at 0 x 0 instead of dropped.
    var videoExpanded by remember(left.id) { mutableStateOf(true) }
    val parked = if (!videoExpanded && isVisible(LogViewSectionId.VIDEO)) setOf(LogViewSectionId.VIDEO) else emptySet()
    val callbacks = TilingCallbacks(
        onCommitLayout = state::commitCompareLayout,
        onHide = state::hideLogViewTile,
        onToggleCollapse = { id -> toggles[id]?.invoke() ?: state.toggleLogViewTileCollapsed(id) },
        onMaximize = { state.logViewMaximized = it },
        onReclaimFocus = focus.panels.returnToLog,
        dropLabel = ::logViewDropLabel,
        defaultDp = ::logViewDefaultDp,
    )
    LogViewSurface(layout, isVisible, collapsedTiles, parked, settings.logViewTilesLocked, state.logViewMaximized, widthDp, heightDp, callbacks) {
        CompareTiles(state, left, right, shownLeft, shownRight, focus, isVisible, collapsibleTiles, toggles) { videoExpanded = it }
    }
}

// The tiles in the fixed emission order: filters, A (Original + Log), B (Original + Log), Notes, Diagram library, AI, Video. Only
// visibility branches, never the layout, so a move changes a tile's rect but never rebuilds its composition. Notes, AI and Video follow
// the left tab, as they always did in compare.
@Composable
private fun TileScope<LogViewSectionId>.CompareTiles(
    state: AppState,
    left: LogTab,
    right: LogTab,
    shownLeft: LogTab,
    shownRight: LogTab,
    focus: ComparePanelFocus,
    isVisible: (LogViewSectionId) -> Boolean,
    collapsibleTiles: Set<LogViewSectionId>,
    toggles: MutableMap<LogViewSectionId, () -> Unit>,
    onVideoExpandedChange: (Boolean) -> Unit,
) {
    val panels = focus.panels
    val target = state.compareFilterTarget
    val editTab = if (compareFilterEditTabId(target, left.id, right.id) == right.id) right else left
    val visibleFilters = LOG_VIEW_FILTER_TILES.filterTo(HashSet()) { isVisible(it) }
    BoundFilterPanel(
        state = state,
        tab = editTab,
        width = 0f,
        liveMaxWidth = { 0f },
        focusRequester = panels.filters,
        filterBarVisible = false,
        filterSearchRequest = focus.filterSearchRequest,
        onFilterSearchRequestConsumed = focus.onFilterSearchRequestConsumed,
        onPanelFocusChanged = { focused -> if (focused) panels.onPanelFocused(panels.filters) },
        host = TiledSectionHost(this, visibleFilters, collapsibleTiles, toggles),
    )
    BoundLogViewer(
        state = state,
        tab = shownLeft,
        focusRequester = panels.log,
        onPanelFocusChanged = { focused -> if (focused) panels.onPanelFocused(panels.log) },
        onExportCaptureRange = null,
        tiles = LogViewerTiles(
            this, LogViewSectionId.LOG_A, LogViewSectionId.ORIGINAL_A,
            logHeaderLeading = { nameMaxDp -> CompareSideLeading("A", left, right, compareSideIsEdited(target, sideIsRight = false), nameMaxDp) },
        ),
        compare = true,
    )
    BoundLogViewer(
        state = state,
        tab = shownRight,
        focusRequester = focus.rightLog,
        onPanelFocusChanged = { focused -> if (focused) panels.onPanelFocused(focus.rightLog) },
        onExportCaptureRange = null,
        tiles = LogViewerTiles(
            this, LogViewSectionId.LOG_B, LogViewSectionId.ORIGINAL_B,
            logHeaderLeading = { nameMaxDp -> CompareSideLeading("B", left, right, compareSideIsEdited(target, sideIsRight = true), nameMaxDp) },
        ),
        compare = true,
    )
    val notesLocked = rememberNotesEditLocked(state, left)
    CompositionLocalProvider(LocalNotesEditLocked provides notesLocked) {
        NotesTile(state, left, panels, collapsible = LogViewSectionId.NOTES in collapsibleTiles)
        DiagramsTile(state, left, collapsible = LogViewSectionId.DIAGRAMS in collapsibleTiles)
    }
    AiTile(state, left, panels, collapsible = LogViewSectionId.AI in collapsibleTiles)
    VideoTile(state, left, collapsible = LogViewSectionId.VIDEO in collapsibleTiles, onExpandedChange = onVideoExpandedChange)
}

// ---------------------------------------------------------------------------------------------------------------
// A / B headers
// ---------------------------------------------------------------------------------------------------------------

/** "Live", "Capture" or "Log": what kind of source [tab] is, for the small pill beside the file name. */
internal fun compareTabKind(tab: LogTab): String = when {
    tab.captureSessionId != null -> "Live"
    tab.captureSourceSessionId != null || tab.captureArchivePath != null -> "Capture"
    else -> "Log"
}

/**
 * The start of a side's log header: the A / B badge (filled with the accent while the filter panel edits this side), the kind pill, and
 * the file name, which takes at most [nameMaxDp] and ellipsizes (the toolbar's own items overflow only after it is down to its minimum).
 * Which tab B shows is chosen in the strip above the tiles ([CompareFilterStrip]).
 */
@Composable
private fun RowScope.CompareSideLeading(side: String, left: LogTab, right: LogTab, edited: Boolean, nameMaxDp: Dp) {
    val tc = tc()
    val tab = if (side == "A") left else right
    CompareSideBadge(side, edited)
    Spacer(Modifier.width(4.dp))
    AppText(
        compareTabKind(tab),
        color = tc.ts,
        fontSize = 9.sp,
        modifier = Modifier.border(1.dp, tc.br, CORNER_SM).padding(horizontal = 4.dp, vertical = 1.dp),
    )
    Spacer(Modifier.width(4.dp))
    AppText(
        tab.filename,
        color = tc.tx,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = nameMaxDp).testTag(compareNameTag(side)),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompareSideBadge(side: String, edited: Boolean) {
    val tc = tc()
    val shape = RoundedCornerShape(4.dp)
    val description = if (edited) "$side, filters apply here" else "$side, filters do not apply here"
    Box(
        Modifier
            .size(BADGE_SIZE_DP.dp)
            .background(if (edited) tc.ac else tc.p2, shape)
            .border(1.dp, if (edited) tc.ac else tc.br, shape)
            .testTag(compareBadgeTag(side))
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        AppText(side, color = if (edited) androidx.compose.ui.graphics.Color.White else tc.ts, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Sync scroll
// ---------------------------------------------------------------------------------------------------------------

/**
 * Keeps A and B level by timestamp while [enabled]: whichever pane is scrolled drives, the other scrolls its filtered list to the row
 * nearest in time to the driving pane's first visible row. Selection is left alone. Draws nothing.
 */
@Composable
internal fun CompareScrollSync(state: AppState, leftId: String, rightId: String, enabled: Boolean) {
    if (!enabled) return
    val store = state.logViewerScrollStateStore
    val leftState = store.lazyState("$leftId:main")
    val rightState = store.lazyState("$rightId:main")
    LaunchedEffect(leftId, rightId, leftState, rightState) {
        // The first-visible index each pane was last moved to by the mirror, so its own change is not mistaken for a drive.
        val mirrored = HashMap<LazyListState, Int>()
        launch { mirrorScroll(state, leftId, leftState, rightId, rightState, mirrored) }
        launch { mirrorScroll(state, rightId, rightState, leftId, leftState, mirrored) }
    }
}

private suspend fun mirrorScroll(
    state: AppState,
    fromId: String,
    from: LazyListState,
    toId: String,
    to: LazyListState,
    mirrored: MutableMap<LazyListState, Int>,
) {
    snapshotFlow { from.firstVisibleItemIndex }.collect { index ->
        if (mirrored.remove(from) == index) return@collect
        val targetIndex = compareSyncTargetIndex(state, fromId, index, toId) ?: return@collect
        val before = to.firstVisibleItemIndex
        if (targetIndex == before) return@collect
        mirrored[to] = targetIndex
        to.scrollToItem(targetIndex)
        // A scroll that could not move (the end of the list) emits nothing, so there is no echo to swallow.
        if (to.firstVisibleItemIndex != targetIndex) mirrored.remove(to)
    }
}

/** The row of [toId]'s displayed list nearest in time to the row at [index] of [fromId]'s displayed list, or null when either has no times yet. */
internal fun compareSyncTargetIndex(state: AppState, fromId: String, index: Int, toId: String): Int? {
    val fromIds = state.visibleItemsSummaryOf(fromId)?.allIds
    val toIds = state.visibleItemsSummaryOf(toId)?.allIds
    val fromTab = state.tab(fromId)
    val toTab = state.tab(toId)
    val anchor = if (fromIds != null && fromTab != null) fromIds.getOrNull(index)?.let { fromTab.rmap[it] } else null
    val time = anchor?.let { parseMillisOfDay(it.ts) } ?: TS_UNKNOWN
    return if (toIds == null || toTab == null || time == TS_UNKNOWN) {
        null
    } else {
        nearestEntryIndexByTime(
            count = toIds.size,
            timeAt = { i -> toTab.rmap[toIds[i]]?.let { parseMillisOfDay(it.ts) } ?: TS_UNKNOWN },
            target = time,
        )
    }
}
