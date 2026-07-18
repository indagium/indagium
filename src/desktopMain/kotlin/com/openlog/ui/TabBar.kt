@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.openlog.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.openlog.model.*
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.math.roundToInt

// Fraction of a tab's own width used as the drag-reorder snap threshold — moved here from
// App.kt's file-level consts since it's only used by TabBar's drag logic.
internal const val TAB_DRAG_SNAP_BIAS = 0.25f

/** Only disambiguate labels when a filename collision is actually visible to the user. */
internal fun tabDisplayLabel(tab: LogTab, allTabs: List<LogTab>): String {
    val sameNameTabs = allTabs.filter { it.filename == tab.filename }
    if (sameNameTabs.size < 2) return tab.filename

    fun sourceSuffix(candidate: LogTab, includeArchiveEntryPath: Boolean): String {
        val sourcePath = candidate.sourcePath.orEmpty()
        val archiveSeparator = sourcePath.indexOf('!')
        if (archiveSeparator >= 0) {
            val archiveName = File(sourcePath.substring(0, archiveSeparator)).name.takeIf { it.isNotBlank() }
            val entryPath = sourcePath.substring(archiveSeparator + 1).trim('/').takeIf { it.isNotBlank() }
            if (archiveName != null && includeArchiveEntryPath && entryPath != null) return "$archiveName/$entryPath"
            if (archiveName != null) return archiveName
        }
        return File(sourcePath).parentFile?.name?.takeIf { it.isNotBlank() } ?: candidate.id.take(8)
    }

    val compactSuffix = sourceSuffix(tab, includeArchiveEntryPath = false)
    if (sameNameTabs.count { sourceSuffix(it, includeArchiveEntryPath = false) == compactSuffix } == 1) {
        return "${tab.filename} — $compactSuffix"
    }

    val expandedSuffix = sourceSuffix(tab, includeArchiveEntryPath = true)
    if (sameNameTabs.count { sourceSuffix(it, includeArchiveEntryPath = true) == expandedSuffix } == 1) {
        return "${tab.filename} — $expandedSuffix"
    }

    return "${tab.filename} — $expandedSuffix — ${tab.id.take(8)}"
}

// ── TabBar ────────────────────────────────────────────────────────────
@Composable
internal fun TabBar(state: AppState) {
    val tc = tc()
    val toolbarGap = 4.dp
    val leftShape = RoundedCornerShape(topStart = 7.dp, bottomStart = 7.dp)
    val middleShape = RoundedCornerShape(0.dp)
    val rightShape = RoundedCornerShape(topEnd = 7.dp, bottomEnd = 7.dp)
    val standaloneShape = RoundedCornerShape(7.dp)
    val hasRecentFiles = state.recentFiles.isNotEmpty()
    val showToolbarText = !state.settings.toolbarIconOnlyButtons
    Row(
        Modifier.fillMaxWidth().height(36.dp).background(tc.p2).border(BorderStroke(1.dp, tc.br)).padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabOverflowRow(state = state, modifier = Modifier.weight(1f).fillMaxHeight())
        Spacer(Modifier.width(toolbarGap))
        ToolbarBtn(
            "Filter",
            icon = Icons.Outlined.FilterList,
            showLabel = showToolbarText,
            tooltip = "Toggle filter panel",
            active = state.filterVisible,
            modifier = Modifier.fillMaxHeight(),
            shape = leftShape,
        ) { state.updateFilterVisible(!state.filterVisible) }
        ToolbarBtn(
            "Notes",
            icon = Icons.AutoMirrored.Outlined.StickyNote2,
            showLabel = showToolbarText,
            tooltip = "Toggle notes panel",
            active = state.annotationVisible,
            modifier = Modifier.fillMaxHeight(),
            shape = middleShape,
        ) { state.updateAnnotationVisible(!state.annotationVisible) }
        ToolbarBtn(
            "AI",
            icon = Icons.Outlined.AutoAwesome,
            showLabel = showToolbarText,
            tooltip = "Toggle AI panel",
            active = state.aiPanelVisible,
            modifier = Modifier.fillMaxHeight(),
            shape = middleShape,
        ) { state.updateAiPanelVisible(!state.aiPanelVisible) }
        ToolbarBtn(
            "Compare",
            icon = Icons.AutoMirrored.Outlined.CompareArrows,
            showLabel = showToolbarText,
            tooltip = "Toggle compare view",
            active = state.compareMode,
            enabled = state.canCompare,
            modifier = Modifier.fillMaxHeight(),
            shape = middleShape,
        ) { state.updateCompareMode(!state.compareMode) }
        ToolbarBtn(
            "Open",
            icon = Icons.Outlined.FolderOpen,
            showLabel = showToolbarText,
            tooltip = "Open log file",
            modifier = Modifier.fillMaxHeight(),
            shape = if (hasRecentFiles) middleShape else rightShape,
        ) {
            // No setFilenameFilter here: it's unreliable on macOS (the native NSOpenPanel doesn't
            // consistently invoke it), which greyed out files that would open fine by drag-and-drop.
            // Show everything and validate after the pick — see AppState.openPathOrShowError.
            val fd = FileDialog(null as Frame?, "Open Log File", FileDialog.LOAD)
            fd.isVisible = true
            fd.file?.let { state.openPathOrShowError(File(fd.directory, it)) }
        }
        if (hasRecentFiles) {
            ToolbarBtn(
                "▾",
                active = state.recentMenuOpen,
                tooltip = "Recent files",
                modifier = Modifier.fillMaxHeight().width(18.dp),
                shape = rightShape,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 5.dp),
            ) { state.toggleRecentFilesMenu() }
        }
        Spacer(Modifier.width(toolbarGap))
        ToolbarBtn(
            "⚙",
            tooltip = "Settings",
            modifier = Modifier.fillMaxHeight().width(36.dp),
            shape = standaloneShape
        ) { state.settingsOpen = true }
        Spacer(Modifier.width(toolbarGap))
    }
}

// Renders visible tabs and an overflow "▾ N" button for any that don't fit.
// Drag-and-drop reorder: press-and-move >8px to start drag; a 3dp accent line marks the drop point.
internal fun browserTabOrderDuringDrag(
    visibleIds: List<String>,
    draggedId: String?,
    dragStartIndex: Int,
    dragOffsetX: Float,
    tabWidth: Float,
): List<String> {
    val dragged = draggedId?.takeIf { it in visibleIds } ?: return visibleIds
    if (tabWidth <= 0f || dragStartIndex !in visibleIds.indices) return visibleIds
    val sensitivityBias = tabWidth * TAB_DRAG_SNAP_BIAS * dragOffsetX.compareTo(0f)
    val draggedCenter = dragStartIndex * tabWidth + tabWidth / 2f + dragOffsetX + sensitivityBias
    val without = visibleIds.filter { it != dragged }
    val insertAt = without.indexOfFirst { id ->
        val center = visibleIds.indexOf(id) * tabWidth + tabWidth / 2f
        draggedCenter < center
    }.takeIf { it >= 0 } ?: without.size
    return without.take(insertAt) + dragged + without.drop(insertAt)
}

internal fun tabRenderX(
    isDragging: Boolean,
    isJustReleased: Boolean,
    pointerX: Float,
    targetX: Float,
    animatedX: Float,
): Float = when {
    isDragging -> pointerX
    isJustReleased -> targetX
    else -> animatedX
}

internal fun splitTabsForVisibility(
    tabs: List<LogTab>,
    containerPx: Int,
    minTabPx: Int,
    overflowButtonPx: Int,
    visibleTabLimit: Int,
): Pair<List<LogTab>, List<LogTab>> {
    if (containerPx == 0) return tabs to emptyList()
    if (tabs.isEmpty()) return emptyList<LogTab>() to emptyList()
    var n = minOf(tabs.size, visibleTabLimit.coerceIn(1, tabs.size))
    while (n > 1) {
        val avail = if (n < tabs.size) containerPx - overflowButtonPx else containerPx
        if (avail / n >= minTabPx) break
        n--
    }
    return tabs.takeLast(n) to tabs.dropLast(n)
}

internal fun tabOrderAfterVisibleReorder(
    visibleIds: List<String>,
    overflowIds: List<String>,
): List<String> = overflowIds + visibleIds

@Composable
internal fun TabOverflowRow(state: AppState, modifier: Modifier) {
    val tc = tc()
    val density = LocalDensity.current.density
    var containerPx by remember { mutableStateOf(0) }
    var tabAreaPx by remember { mutableStateOf(0) }
    var dragTabId by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableStateOf(-1) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    var justReleasedTabId by remember { mutableStateOf<String?>(null) }
    var liveVisualTabIds by remember { mutableStateOf(emptyList<String>()) }
    var overflowOpen by remember { mutableStateOf(false) }

    val minTabPx = (80 * density).toInt()
    val ovBtnPx = (40 * density).toInt()

    val visibleTabLimit = state.settings.visibleTabLimit
    val (visibleTabs, overflowTabs) = remember(state.tabs, state.activeTabId, containerPx, visibleTabLimit) {
        splitTabsForVisibility(
            tabs = state.tabs,
            containerPx = containerPx,
            minTabPx = minTabPx,
            overflowButtonPx = ovBtnPx,
            visibleTabLimit = visibleTabLimit,
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

    Row(
        modifier
            .onSizeChanged { containerPx = it.width },
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
                                    downPos = ch.position; dragging = false
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
                                        state.tabs = newOrder.mapNotNull { id -> state.tabs.find { it.id == id } }
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
                        label = "tab-x-${tab.id}",
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
                            }
                    ) {
                        TabItem(
                            tab = tab,
                            label = tabDisplayLabel(tab, state.tabs),
                            isActive = tab.id == state.activeTabId,
                            showClose = true,
                            dragging = isDragging,
                            onClick = { if (dragTabId == null) state.activateTab(tab.id) },
                            onClose = { state.closeTab(tab.id) },
                            onCtxMenu = { x, y -> state.tabCtx = TabCtxMenuState(tab.id, x, y) },
                        )
                    }
                }
            }
        }
        if (overflowTabs.isNotEmpty()) {
            Box(Modifier.fillMaxHeight()) {
                ToolbarBtn(
                    "▾ ${overflowTabs.size}",
                    active = overflowOpen,
                    modifier = Modifier.fillMaxHeight(),
                ) { overflowOpen = !overflowOpen }
                if (overflowOpen) {
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(0, (36 * density).toInt()),
                        onDismissRequest = { overflowOpen = false },
                        properties = PopupProperties(focusable = true),
                    ) {
                        Box(
                            Modifier.width(240.dp)
                                .background(tc.p, RoundedCornerShape(7.dp))
                                .border(1.dp, tc.br, RoundedCornerShape(7.dp)),
                        ) {
                            Column(Modifier.padding(vertical = 4.dp)) {
                                overflowTabs.forEach { tab ->
                                    HoverBox(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { state.activateOverflowTab(tab.id); overflowOpen = false },
                                    ) {
                                        AppText(
                                            tabDisplayLabel(tab, state.tabs), color = tc.tx, fontSize = 12.sp,
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

// ── Shared ────────────────────────────────────────────────────────────
@Composable
internal fun TabItem(
    tab: LogTab, isActive: Boolean, showClose: Boolean,
    label: String = tab.filename,
    dragging: Boolean = false, onClick: () -> Unit, onClose: () -> Unit,
    onCtxMenu: (Float, Float) -> Unit = { _, _ -> },
) {
    val tc = tc()
    val density = LocalDensity.current.density
    var hov by remember { mutableStateOf(false) }
    var rowRoot by remember { mutableStateOf(Offset.Zero) }
    val accent = tc.ac
    Row(
        Modifier.fillMaxWidth().height(36.dp)
            .background(if (isActive) tc.bg else if (hov || dragging) tc.p else tc.p2)
            .border(BorderStroke(1.dp, tc.br.copy(alpha = 0.95f)))
            .drawBehind {
                if (isActive) {
                    val stroke = 2.dp.toPx()
                    drawRect(
                        color = accent,
                        topLeft = Offset(0f, size.height - stroke),
                        size = androidx.compose.ui.geometry.Size(size.width, stroke),
                    )
                }
            }
            .onGloballyPositioned { rowRoot = it.positionInRoot() }
            .onPointerEvent(PointerEventType.Enter) { hov = true }
            .onPointerEvent(PointerEventType.Exit) { hov = false }
            .pointerInput(tab.id) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.type == PointerEventType.Press && ev.buttons.isSecondaryPressed) {
                            val ch = ev.changes.firstOrNull() ?: continue
                            ch.consume()
                            onCtxMenu((rowRoot.x + ch.position.x) / density, (rowRoot.y + ch.position.y) / density)
                        }
                    }
                }
            }
            .clickable(onClick = onClick).padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        // Live tailing (utils/FileTailer.kt) is toggled from the tab's right-click context menu,
        // not a clickable button here — this is purely an indicator, plain and non-interactive.
        if (tab.tailing) {
            TooltipArea(
                tooltip = {
                    Box(
                        Modifier.background(tc.p2, RoundedCornerShape(4.dp))
                            .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        AppText("Live tailing — watching file for new lines", color = tc.tx, fontSize = 11.sp, fontFamily = MONO)
                    }
                },
            ) {
                AppText("●", color = DANGER_RED, fontSize = 10.sp)
            }
        }
        TooltipArea(
            tooltip = {
                val tooltipScroll = rememberScrollState()
                Box(
                    Modifier
                        .widthIn(max = 760.dp)
                        .heightIn(max = 180.dp)
                        .background(tc.p2, RoundedCornerShape(4.dp))
                        .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                        .verticalScroll(tooltipScroll)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    AppText(
                        tab.sourcePath ?: tab.filename,
                        color = tc.tx,
                        fontSize = 11.sp,
                        fontFamily = MONO,
                        maxLines = Int.MAX_VALUE,
                        overflow = TextOverflow.Clip,
                    )
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            AppText(
                label,
                color = if (isActive || dragging) tc.tx else tc.ts,
                fontSize = 12.sp,
                fontFamily = MONO,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
        }
        if (showClose) {
            CloseButton(onClick = onClose)
        }
    }
}
