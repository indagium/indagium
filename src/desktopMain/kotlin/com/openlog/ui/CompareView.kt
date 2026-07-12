@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.openlog.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.openlog.model.*
import kotlin.math.roundToInt

// ── CompareView ───────────────────────────────────────────────────────
@Composable
internal fun CompareView(
    state: AppState,
    requestedPanelFocus: KeyboardPanel? = null,
    filterSearchFocusRequest: Int = 0,
    onPanelFocusConsumed: () -> Unit = {},
) {
    val leftTab = state.tab(state.activeTabId) ?: state.tabs.firstOrNull() ?: return
    val rawRightTab = state.tab(state.compareTabId) ?: state.tabs.getOrNull(1) ?: state.tabs.first()
    // Left and right must never resolve to the same tab: both LogViewers key their scroll
    // state by tab id alone, so two panes on the same tab would share one LazyListState and
    // silently fight over scroll ownership (see LogViewerScrollStateStore).
    val rightTab = if (rawRightTab.id == leftTab.id) {
        state.tabs.firstOrNull { it.id != leftTab.id } ?: rawRightTab
    } else {
        rawRightTab
    }
    val tc = tc()
    val filterFr = remember { FocusRequester() }
    val leftLogFr = remember { FocusRequester() }
    val rightLogFr = remember { FocusRequester() }
    val annotationFr = remember { FocusRequester() }
    val aiFr = remember { FocusRequester() }
    var focusedPanelIdx by remember { mutableStateOf(0) }

    fun visiblePanelFrs(): List<Pair<KeyboardPanel, FocusRequester>> = buildList {
        if (state.filterVisible) add(KeyboardPanel.FILTERS to filterFr)
        add(KeyboardPanel.LOG_VIEW to leftLogFr)
        add(KeyboardPanel.LOG_VIEW to rightLogFr)
        if (state.annotationVisible) add(KeyboardPanel.NOTES to annotationFr)
        if (state.aiPanelVisible) add(KeyboardPanel.AI to aiFr)
    }

    LaunchedEffect(requestedPanelFocus, state.filterVisible, state.annotationVisible, state.aiPanelVisible, leftTab.id, rightTab.id) {
        val panel = requestedPanelFocus ?: return@LaunchedEffect
        val fr = visiblePanelFrs().firstOrNull { it.first == panel }?.second ?: return@LaunchedEffect
        runCatching { fr.requestFocus() }
        onPanelFocusConsumed()
    }

    // Right side shows all entries when filter is off; mirrors left filter when on.
    val effectiveRightTab = if (state.compareFilterRight)
        rightTab.copy(filter = leftTab.filter)
    else
        rightTab.copy(filter = Filter())

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val totalWidthDp = maxWidth.value

        Row(
            Modifier.fillMaxSize().onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.F6) {
                    val frs = visiblePanelFrs()
                    if (frs.isNotEmpty()) {
                        val delta = if (ev.isShiftPressed) -1 else 1
                        val next = (focusedPanelIdx + delta).mod(frs.size)
                        state.keyboardFocusVisible = true
                        runCatching { frs[next].second.requestFocus() }
                    }
                    true
                } else {
                    false
                }
            },
        ) {
            // ── Left panel ──────────────────────────────────────────
            // No local tab picker here — the top TabBar already controls activeTabId (the tab
            // shown on the left), so a second picker for the same state would be redundant.
            Column(
                Modifier
                    .width((totalWidthDp * state.compareSplit).dp)
                    .fillMaxHeight()
                    .border(BorderStroke(1.dp, tc.br.copy(.53f)))
            ) {
                // Empty bar matching the right panel's header height, so both LogViewers start
                // at the same vertical offset — this is a side-by-side diff view.
                Box(Modifier.fillMaxWidth().height(22.dp).background(tc.p2))
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    BoundFilterPanel(
                        state = state,
                        tab = leftTab,
                        focusRequester = filterFr,
                        focusSearchRequest = filterSearchFocusRequest,
                        onPanelFocusChanged = { focused ->
                            if (focused) focusedPanelIdx = visiblePanelFrs().indexOfFirst { it.second == filterFr }
                        },
                    )
                    LogViewer(
                        tab = leftTab, modifier = Modifier.weight(1f),
                        settings = state.settings,
                        onSelRow = { id, m, r -> state.selRow(leftTab.id, id, m, r) },
                        onSelRowRange = { ids -> state.setSelectedRows(leftTab.id, ids) },
                        onCtxMenu = { id, x, y, sel, panelSel ->
                            state.ctx = CtxMenuState(leftTab.id, id, x, y, sel, panelSel)
                        },
                        onToggleGroup = { state.toggleGroup(leftTab.id, it) },
                        onClearFilter = { state.requestClearFilter(leftTab.id) },
                        onExpandAll = { state.expandAll(leftTab.id) },
                        onCollapseAll = { state.collapseAll(leftTab.id) },
                        onToggleUnfiltered = { state.toggleUnfiltered(leftTab.id) },
                        onExportTxt = { state.exportFilteredTxt(leftTab.id) },
                        onExportCsv = { state.exportFilteredCsv(leftTab.id) },
                        scrollStateStore = state.logViewerScrollStateStore,
                        annotationNavigationRequest = state.pendingAnnotationNavigation,
                        onConsumeAnnotationNavigation = { state.consumeAnnotationNavigation(it) },
                        onSelectAll = { state.selectAll(leftTab.id) },
                        onClearSelection = { state.clearSelection(leftTab.id) },
                        onCopySelection = { selectedIds -> state.copySelectedLines(leftTab.id, selectedIds) },
                        onCopyText = { text -> state.copyToClipboard(text) },
                        navScrollMargin = state.settings.navScrollMargin,
                        focusRequester = leftLogFr,
                        onPanelFocusChanged = { focused ->
                            if (focused) focusedPanelIdx = visiblePanelFrs().indexOfFirst { it.second == leftLogFr }
                        },
                        keyboardFocusVisible = state.keyboardFocusVisible,
                        onVisibleItems = { summary -> state.noteVisibleItems(leftTab.id, summary) },
                        onHoverPanelKey = { key -> state.hoveredLogPanelKey = key },
                    )
                }
            }

            // ── Split divider ────────────────────────────────────────
            HDivider { delta ->
                val newFrac = (state.compareSplit * totalWidthDp + delta) / totalWidthDp
                state.updateCompareSplit(newFrac)
            }

            // ── Right panel ──────────────────────────────────────────
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    Modifier.fillMaxWidth().height(22.dp).background(tc.p2).padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompareTabPicker(
                        state = state,
                        leftTab = leftTab,
                        rightTab = rightTab,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    CompareTabPill(
                        label = if (state.compareFilterRight) "⊟ Filter" else "⊞ Filter",
                        active = state.compareFilterRight,
                        tooltip = "Toggle right-side filtering",
                        modifier = Modifier.fillMaxHeight(),
                        onClick = { state.updateCompareFilterRight(!state.compareFilterRight) },
                    )
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    LogViewer(
                        tab = effectiveRightTab, modifier = Modifier.weight(1f),
                        settings = state.settings,
                        onSelRow = { id, m, r -> state.selRow(rightTab.id, id, m, r) },
                        onSelRowRange = { ids -> state.setSelectedRows(rightTab.id, ids) },
                        onCtxMenu = { id, x, y, sel, panelSel ->
                            state.ctx = CtxMenuState(rightTab.id, id, x, y, sel, panelSel)
                        },
                        onToggleGroup = { state.toggleGroup(rightTab.id, it) },
                        onClearFilter = { state.requestClearFilter(rightTab.id) },
                        onExpandAll = { state.expandAll(rightTab.id) },
                        onCollapseAll = { state.collapseAll(rightTab.id) },
                        onToggleUnfiltered = { state.toggleUnfiltered(rightTab.id) },
                        onExportTxt = { state.exportFilteredTxt(rightTab.id) },
                        onExportCsv = { state.exportFilteredCsv(rightTab.id) },
                        scrollStateStore = state.logViewerScrollStateStore,
                        annotationNavigationRequest = state.pendingAnnotationNavigation,
                        onConsumeAnnotationNavigation = { state.consumeAnnotationNavigation(it) },
                        onSelectAll = { state.selectAll(rightTab.id) },
                        onClearSelection = { state.clearSelection(rightTab.id) },
                        onCopySelection = { selectedIds -> state.copySelectedLines(rightTab.id, selectedIds) },
                        onCopyText = { text -> state.copyToClipboard(text) },
                        navScrollMargin = state.settings.navScrollMargin,
                        focusRequester = rightLogFr,
                        onPanelFocusChanged = { focused ->
                            if (focused) focusedPanelIdx = visiblePanelFrs().indexOfFirst { it.second == rightLogFr }
                        },
                        keyboardFocusVisible = state.keyboardFocusVisible,
                        // Keyed by rightTab (not effectiveRightTab): the summary reflects what
                        // this pane displays, which is what selRow/selectAll on rightTab.id need.
                        onVisibleItems = { summary -> state.noteVisibleItems(rightTab.id, summary) },
                        onHoverPanelKey = { key -> state.hoveredLogPanelKey = key },
                    )
                    if (state.annotationVisible || state.aiPanelVisible) {
                        HDivider { d ->
                            state.updateAnnotationPanelWidth(state.annotationPanelWidth - d)
                        }
                        RightSidebarPanel(
                            state = state,
                            tab = leftTab,
                            width = state.annotationPanelWidth,
                            aiFocusRequester = aiFr,
                            onAiPanelFocusChanged = { focused ->
                                if (focused) focusedPanelIdx = visiblePanelFrs().indexOfFirst { it.second == aiFr }
                            },
                        ) {
                            AnnotationPanel(
                                tab = leftTab,
                                settings = state.settings,
                                recentNotes = state.recentNotesForTab(leftTab),
                                recentNotesMenuOpen = state.recentNotesMenuOpen,
                                onToggleMd = { state.toggleMd(leftTab.id) },
                                onCopy = { state.copyAnn(leftTab.id) },
                                onSave = { state.saveAnalysis(leftTab.id) },
                                onToggleRecentNotes = { state.toggleRecentNotesMenu() },
                                onOpenNote = { state.openNoteFileAsync(leftTab.id, it) },
                                onUpdatePrefix = { state.setPrefix(leftTab.id, it) },
                                onUpdateSuffix = { state.setSuffix(leftTab.id, it) },
                                onUpdateIssueDescription = { state.setIssueDescription(leftTab.id, it) },
                                onUpdateBlock = { bid, t -> state.updateBlock(leftTab.id, bid, t) },
                                onRemoveBlock = { state.removeBlock(leftTab.id, it) },
                                onMoveBlock = { bid, d -> state.moveBlock(leftTab.id, bid, d) },
                                onReorderBlock = { bid, idx -> state.reorderBlock(leftTab.id, bid, idx) },
                                onAddNoteAfter = { state.addNoteBlock(leftTab.id, it) },
                                onNavigateLogRef = { state.requestAnnotationNavigation(leftTab.id, it) },
                                width = state.annotationPanelWidth,
                                focusRequester = annotationFr,
                                onPanelFocusChanged = { focused ->
                                    if (focused) focusedPanelIdx = visiblePanelFrs().indexOfFirst { it.second == annotationFr }
                                },
                                keyboardFocusVisible = state.keyboardFocusVisible,
                                scrollStateStore = state.logViewerScrollStateStore,
                                highlightedBlockId = state.aiEvidenceNoteTarget?.takeIf { it.tabId == leftTab.id }?.blockId,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CompareTabPicker(
    state: AppState,
    leftTab: LogTab,
    rightTab: LogTab,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    val density = LocalDensity.current.density
    var containerPx by remember { mutableStateOf(0) }
    var tabAreaPx by remember { mutableStateOf(0) }
    var compareOrderIds by remember { mutableStateOf(state.tabs.map { it.id }) }
    var dragTabId by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableStateOf(-1) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    var justReleasedTabId by remember { mutableStateOf<String?>(null) }
    var liveVisualTabIds by remember { mutableStateOf(emptyList<String>()) }
    var overflowOpen by remember { mutableStateOf(false) }
    val minTabPx = (100 * density).toInt()
    val ovBtnPx = (44 * density).toInt()
    val orderedTabs = remember(state.tabs, compareOrderIds) {
        orderedTabsForComparePicker(state.tabs, compareOrderIds)
    }
    LaunchedEffect(state.tabs.map { it.id }) {
        compareOrderIds = orderedTabsForComparePicker(state.tabs, compareOrderIds).map { it.id }
    }
    val (visibleTabs, overflowTabs) = remember(orderedTabs, containerPx, state.settings.visibleTabLimit) {
        splitTabsForVisibility(
            tabs = orderedTabs,
            containerPx = containerPx,
            minTabPx = minTabPx,
            overflowButtonPx = ovBtnPx,
            visibleTabLimit = state.settings.visibleTabLimit,
        )
    }

    val visibleTabIds = visibleTabs.map { it.id }
    val overflowTabIds = overflowTabs.map { it.id }
    LaunchedEffect(visibleTabIds, dragTabId) {
        if (dragTabId == null) liveVisualTabIds = visibleTabIds
    }
    LaunchedEffect(justReleasedTabId) {
        if (justReleasedTabId != null) {
            kotlinx.coroutines.delay(120)
            justReleasedTabId = null
        }
    }
    val tabWidthPx =
        if (visibleTabs.isNotEmpty() && tabAreaPx > 0) tabAreaPx.toFloat() / visibleTabs.size else minTabPx.toFloat()
    val visualOrderIds =
        liveVisualTabIds.takeIf { it.toSet() == visibleTabIds.toSet() && it.size == visibleTabIds.size }
            ?: visibleTabIds
    val currentVisualOrderIds by rememberUpdatedState(visualOrderIds)
    val currentOverflowTabIds by rememberUpdatedState(overflowTabIds)

    fun selectCompareTab(tabId: String, fromOverflow: Boolean = false) {
        if (fromOverflow) compareOrderIds = comparePickerOrderAfterOverflowSelection(compareOrderIds, tabId)
        if (tabId == leftTab.id) state.activateTab(rightTab.id)
        state.compareTabId = tabId
        overflowOpen = false
    }

    Row(
        modifier.onSizeChanged { containerPx = it.width },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.weight(1f).fillMaxHeight()
                .onSizeChanged { tabAreaPx = it.width }
                .pointerInput(visibleTabIds, tabWidthPx) {
                    var downPos = Offset.Zero
                    var downId: String? = null
                    var dragging = false
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull() ?: continue
                            when (ev.type) {
                                PointerEventType.Press -> {
                                    downPos = ch.position
                                    dragging = false
                                    val idx = (ch.position.x / tabWidthPx).toInt()
                                        .coerceIn(0, visibleTabIds.lastIndex.coerceAtLeast(0))
                                    downId = visibleTabIds.getOrNull(idx)
                                }

                                PointerEventType.Move -> {
                                    if (downId != null && !dragging && (ch.position - downPos).getDistance() > 8f) {
                                        dragging = true
                                        dragTabId = downId
                                        justReleasedTabId = null
                                        dragStartIndex = visibleTabIds.indexOf(downId)
                                        dragOffsetX = 0f
                                    }
                                    if (dragging && dragTabId != null) {
                                        ch.consume()
                                        dragOffsetX = ch.position.x - downPos.x
                                        liveVisualTabIds = browserTabOrderDuringDrag(
                                            visibleIds = visibleTabIds,
                                            draggedId = dragTabId,
                                            dragStartIndex = dragStartIndex,
                                            dragOffsetX = dragOffsetX,
                                            tabWidth = tabWidthPx,
                                        )
                                    }
                                }

                                PointerEventType.Release -> {
                                    if (dragging && dragTabId != null) {
                                        justReleasedTabId = dragTabId
                                        val newOrder = tabOrderAfterVisibleReorder(
                                            visibleIds = currentVisualOrderIds,
                                            overflowIds = currentOverflowTabIds,
                                        )
                                        compareOrderIds = newOrder
                                    }
                                    dragTabId = null
                                    dragStartIndex = -1
                                    dragOffsetX = 0f
                                    downId = null
                                    dragging = false
                                }

                                else -> {}
                            }
                        }
                    }
                },
        ) {
            visibleTabs.forEach { tab ->
                key(tab.id) {
                    val isDragging = tab.id == dragTabId
                    val targetIndex = visualOrderIds.indexOf(tab.id).takeIf { it >= 0 } ?: visibleTabs.indexOf(tab)
                    val targetX = targetIndex * tabWidthPx
                    val animatedX by animateFloatAsState(
                        targetValue = targetX,
                        animationSpec = spring(stiffness = 650f, dampingRatio = 0.86f),
                        label = "compare-tab-x-${tab.id}",
                    )
                    val startX = (dragStartIndex.takeIf { it >= 0 } ?: targetIndex) * tabWidthPx
                    val tabX = tabRenderX(
                        isDragging = isDragging,
                        isJustReleased = tab.id == justReleasedTabId,
                        pointerX = startX + dragOffsetX,
                        targetX = targetX,
                        animatedX = animatedX,
                    )
                    Box(
                        Modifier
                            .offset { IntOffset(tabX.roundToInt(), 0) }
                            .width((tabWidthPx / density).dp)
                            .fillMaxHeight()
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                if (isDragging) {
                                    scaleX = 1.02f
                                    scaleY = 1.02f
                                }
                            },
                    ) {
                        CompareTabPill(
                            label = tab.filename,
                            active = tab.id == rightTab.id,
                            dragging = isDragging,
                            tooltip = "Compare with ${tab.filename}",
                            modifier = Modifier.fillMaxSize(),
                            onClick = { if (dragTabId == null) selectCompareTab(tab.id) },
                        )
                    }
                }
            }
        }
        if (overflowTabs.isNotEmpty()) {
            Box(Modifier.fillMaxHeight()) {
                CompareTabPill(
                    label = "▾ ${overflowTabs.size}",
                    active = overflowOpen,
                    tooltip = "Show hidden compare tabs",
                    modifier = Modifier.fillMaxHeight(),
                ) { overflowOpen = !overflowOpen }
                if (overflowOpen) {
                    Popup(
                        alignment = Alignment.TopEnd,
                        offset = IntOffset(0, (22 * density).toInt()),
                        onDismissRequest = { overflowOpen = false },
                        properties = PopupProperties(focusable = true),
                    ) {
                        Box(
                            Modifier.width(280.dp)
                                .heightIn(max = 320.dp)
                                .background(tc.p, RoundedCornerShape(7.dp))
                                .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Column(Modifier.padding(vertical = 4.dp)) {
                                overflowTabs.forEach { tab ->
                                    HoverBox(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { selectCompareTab(tab.id, fromOverflow = true) },
                                    ) {
                                        TooltipArea(
                                            tooltip = { CompareTooltip("Compare with ${tab.filename}") },
                                        ) {
                                            AppText(
                                                tab.filename,
                                                color = if (tab.id == rightTab.id) tc.ac else tc.tx,
                                                fontSize = 12.sp,
                                                fontFamily = MONO,
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun orderedTabsForComparePicker(tabs: List<LogTab>, orderIds: List<String>): List<LogTab> {
    val byId = tabs.associateBy { it.id }
    val ordered = orderIds.mapNotNull { byId[it] }
    val orderedIds = ordered.mapTo(mutableSetOf()) { it.id }
    return ordered + tabs.filter { it.id !in orderedIds }
}

internal fun comparePickerOrderAfterOverflowSelection(orderIds: List<String>, tabId: String): List<String> {
    if (tabId !in orderIds) return orderIds
    return orderIds.filter { it != tabId } + tabId
}

@Composable
internal fun CompareTabPill(
    label: String,
    active: Boolean,
    dragging: Boolean = false,
    tooltip: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    TooltipArea(
        tooltip = { CompareTooltip(tooltip) },
    ) {
        Box(
            modifier
                .border(1.dp, if (active) tc.ac else tc.br, CORNER_MD)
                .background(if (active) tc.ac.copy(.15f) else if (hovered || dragging) tc.hv else Color.Transparent, CORNER_MD)
                .clip(CORNER_MD)
                .clickable(onClick = onClick)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .padding(horizontal = 6.dp, vertical = 0.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            AppText(
                label,
                color = if (active) tc.ac else tc.ts,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun CompareTooltip(text: String) {
    val tc = tc()
    Box(
        Modifier.background(tc.p2, RoundedCornerShape(4.dp))
            .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        AppText(text, color = tc.tx, fontSize = 11.sp, fontFamily = MONO)
    }
}
