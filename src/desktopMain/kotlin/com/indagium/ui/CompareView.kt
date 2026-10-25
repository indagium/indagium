@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.indagium.model.*
import kotlin.math.roundToInt

// ── CompareView ───────────────────────────────────────────────────────
// The compare view: a strip ("Filters apply to A | B | Both", scroll link) above one tiling surface (CompareTiling.kt) whose tiles are
// the filter sections, A and B (each Original over Log), Notes, the Diagram library, AI and Video. This composable owns what is not
// tile-specific: which tabs are A and B, keyboard focus (F6, requested panels), the displayed filters and the sync scroll.
@Composable
internal fun CompareView(
    state: AppState,
    requestedPanelFocus: KeyboardPanel? = null,
    filterSearchRequest: FilterSearchRequest? = null,
    onFilterSearchRequestConsumed: (FilterSearchRequest) -> Unit = {},
    onPanelFocusConsumed: () -> Unit = {},
) {
    // Left and right must never resolve to the same tab: both LogViewers key their scroll
    // state by tab id alone, so two panes on the same tab would share one LazyListState and
    // silently fight over scroll ownership (see LogViewerScrollStateStore). compareTabs() guarantees it.
    val (leftTab, rightTab) = state.compareTabs() ?: return
    val filterFr = remember { FocusRequester() }
    val leftLogFr = remember { FocusRequester() }
    val rightLogFr = remember { FocusRequester() }
    val annotationFr = remember { FocusRequester() }
    val aiFr = remember { FocusRequester() }
    var focusedPanelIdx by remember { mutableStateOf(0) }

    // The F6 / panel-focus stops of the tiles that are showing, in the layout's reading order. Each side's Original and Log are one stop
    // (the log viewer has a single requester), as are all the filter tiles.
    fun visiblePanelFrs(): List<Pair<KeyboardPanel, FocusRequester>> = buildList {
        state.compareVisiblePanels(leftTab, rightTab).forEach { panel ->
            when (panel) {
                LogViewFocusPanel.FILTERS -> add(KeyboardPanel.FILTERS to filterFr)
                LogViewFocusPanel.ORIGINAL_A, LogViewFocusPanel.LOG_A -> if (none { it.second == leftLogFr }) add(KeyboardPanel.LOG_VIEW to leftLogFr)
                LogViewFocusPanel.ORIGINAL_B, LogViewFocusPanel.LOG_B -> if (none { it.second == rightLogFr }) add(KeyboardPanel.LOG_VIEW to rightLogFr)
                LogViewFocusPanel.NOTES -> add(KeyboardPanel.NOTES to annotationFr)
                LogViewFocusPanel.AI -> add(KeyboardPanel.AI to aiFr)
                else -> Unit
            }
        }
    }

    LaunchedEffect(
        requestedPanelFocus, state.filterVisible, state.annotationVisible, state.aiPanelVisible, state.settings.compareHidden,
        leftTab.id, rightTab.id,
    ) {
        val panel = requestedPanelFocus ?: return@LaunchedEffect
        val fr = visiblePanelFrs().firstOrNull { it.first == panel }?.second ?: return@LaunchedEffect
        runCatching { fr.requestFocus() }
        onPanelFocusConsumed()
    }

    // What each pane displays: the filter on the pane(s) it applies to, everything (plus the other tab's highlighters) on the rest.
    val (leftShown, rightShown) = compareDisplayedFilters(state.compareFilterTarget, leftTab.filter, rightTab.filter)
    val shownLeft = if (leftShown === leftTab.filter) leftTab else leftTab.copy(filter = leftShown)
    val shownRight = if (rightShown === rightTab.filter) rightTab else rightTab.copy(filter = rightShown)

    // AppState.scheduleSearchRecompute mirrors this same displayed-filter logic (see its effectiveSearchFilter) when computing a pane's
    // Find-bar matches, but nothing on the AppState side observes the target switching or the other tab's filter changing while it is
    // displayed here — neither mutates the pane's own stored `.filter`, so upTab's own staleness hook never fires for them. This is the
    // explicit, reactive trigger for exactly that gap: it re-runs whenever the *effective* filter a pane actually displays changes.
    LaunchedEffect(leftShown, leftTab.id) { state.notifyEffectiveFilterChanged(leftTab.id) }
    LaunchedEffect(rightShown, rightTab.id) { state.notifyEffectiveFilterChanged(rightTab.id) }

    CompareScrollSync(state, leftTab.id, rightTab.id, enabled = state.settings.compareSyncScroll)

    val reclaim = { runCatching { leftLogFr.requestFocus() }; Unit }
    Column(
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
        CompareFilterStrip(state, leftTab, rightTab, onReclaimFocus = reclaim)
        CompareTilingHost(
            state = state,
            left = leftTab,
            right = rightTab,
            shownLeft = shownLeft,
            shownRight = shownRight,
            focus = ComparePanelFocus(
                panels = LogViewPanelFocus(
                    filters = filterFr,
                    log = leftLogFr,
                    notes = annotationFr,
                    ai = aiFr,
                    onPanelFocused = { fr -> focusedPanelIdx = visiblePanelFrs().indexOfFirst { it.second == fr } },
                    returnToLog = reclaim,
                ),
                rightLog = rightLogFr,
                filterSearchRequest = filterSearchRequest,
                onFilterSearchRequestConsumed = onFilterSearchRequestConsumed,
            ),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
internal fun CompareTabPicker(
    state: AppState,
    leftTab: LogTab,
    rightTab: LogTab,
    modifier: Modifier = Modifier,
    // Gives the log list the keyboard focus back once a pill or overflow row was clicked or the overflow list was dismissed: a clicked
    // pill (and a dismissed Popup) keeps keyboard focus otherwise, and the compare view's keys stop working.
    onReclaimFocus: () -> Unit = {},
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
        onReclaimFocus()
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
                    awaitReorderPointerScope {
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
                                        holdMoveDragCursor()
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
                                    releaseMoveDragCursor()
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
                        onDismissRequest = { overflowOpen = false; onReclaimFocus() },
                        properties = PopupProperties(focusable = true),
                    ) {
                        ScrollColumnWithBar(
                            Modifier.width(280.dp)
                                .heightIn(max = 320.dp)
                                .background(tc.p, RoundedCornerShape(7.dp))
                                .border(1.dp, tc.br, RoundedCornerShape(7.dp)),
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
    icon: ImageVector? = null,
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
            val contentColor = if (active) tc.ac else tc.ts
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(12.dp),
                    )
                }
                AppText(
                    label,
                    color = contentColor,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
