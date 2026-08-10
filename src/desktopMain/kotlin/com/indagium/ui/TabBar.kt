@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Movie
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
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.model.*
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
        if (state.seqDiagrams.workspaces.isNotEmpty()) {
            DiagramWorkspaceTabs(state)
            Spacer(Modifier.width(toolbarGap))
        }
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
        // Only offered when the active tab actually has a video attached — toggling
        // AppState.videoPanelVisible would otherwise have no visible effect (BoundVideoPanel
        // renders nothing without an attachment), matching Compare's own `enabled = canCompare`
        // convention of gating on real applicability rather than hiding the whole button.
        if (state.tab(state.activeTabId)?.attachedVideo != null) {
            ToolbarBtn(
                "Video",
                icon = Icons.Outlined.Movie,
                showLabel = showToolbarText,
                tooltip = "Toggle video in sidebar",
                active = state.videoPanelVisible,
                modifier = Modifier.fillMaxHeight(),
                shape = middleShape,
            ) { state.updateVideoPanelVisible(!state.videoPanelVisible) }
        }
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
            "Cases",
            icon = Icons.AutoMirrored.Outlined.ManageSearch,
            showLabel = showToolbarText,
            tooltip = "Search past analyses",
            enabled = state.tabs.isNotEmpty(),
            modifier = Modifier.fillMaxHeight(),
            shape = middleShape,
        ) { state.activeTab()?.id?.let(state::openCaseLibrary) }
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

// At 12sp MONO, 140dp minus 12dp start padding minus a 24dp CloseButton leaves ~90dp of label —
// enough for a short diagram title without the 100dp pill's cramped ellipsis.
private val DIAGRAM_TAB_WIDTH = 140.dp

// The gap DiagramWorkspaceTabs' tab strip renders between adjacent tabs. Unlike TabOverflowRow's
// log tabs (which pack edge-to-edge and derive their pitch from the container's own width),
// diagram tabs are fixed-width, so their drag "pitch" for browserTabOrderDuringDrag/tabRenderX is
// simply this width plus this gap — each tab's Box is laid out narrower than its pitch slot,
// which reproduces the gap visually without needing Arrangement.spacedBy (that in turn is what
// let this reuse the log-tab drag helpers unmodified — see the Part A task note on not forking
// them).
private val DIAGRAM_TAB_GAP = 2.dp

/** Diagram sessions are rendered beside, never inside, the log-tab collection.  This preserves
 * every LogTab-only invariant (compare, autosave and tab ordering) while still giving diagrams
 * the same tab chrome (TabShell) as log tabs — see the design-correspondence note at the top of
 * this file's diagram-tab section.
 *
 * Drag-to-reorder mirrors [TabOverflowRow]'s own container-level `awaitPointerEventScope` loop
 * (8px slop, suppress-click-while-dragging, live visual reorder via [browserTabOrderDuringDrag]/
 * [tabRenderX]) rather than a second implementation — the only genuine difference is the fixed
 * (container-independent) tab pitch and [diagramWorkspaceOrderAfterVisibleReorder]'s commit step,
 * needed because [diagramWorkspaceIdsForWidth]'s visible window can sit in the middle of the full
 * id list instead of always being a trailing suffix.
 */
@Composable
private fun DiagramWorkspaceTabs(state: AppState) {
    val tc = tc()
    val density = LocalDensity.current.density
    var overflowOpen by remember { mutableStateOf(false) }
    // Right-click menu for a single diagram tab. Local to this composable per Step 2 of the
    // tab-shell plan — deliberately not an AppState field; TabCtxMenuState (log tabs) is a
    // full-window overlay anchored by absolute position, but a diagram tab only needs two actions
    // so a Popup anchored to that tab's own Box (like the overflow Popup below) is enough.
    var ctxMenuWorkspaceId by remember { mutableStateOf<String?>(null) }

    var dragWorkspaceId by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableStateOf(-1) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    var justReleasedWorkspaceId by remember { mutableStateOf<String?>(null) }
    var liveVisualIds by remember { mutableStateOf(emptyList<String>()) }

    val tabPitchPx = (DIAGRAM_TAB_WIDTH.value + DIAGRAM_TAB_GAP.value) * density

    LaunchedEffect(justReleasedWorkspaceId) {
        if (justReleasedWorkspaceId != null) {
            kotlinx.coroutines.delay(120)
            justReleasedWorkspaceId = null
        }
    }

    BoxWithConstraints(Modifier.widthIn(max = 460.dp).fillMaxHeight()) {
        val rawCapacity = (maxWidth.value / 145f).toInt().coerceIn(1, 3)
        val capacity = if (state.seqDiagrams.workspaces.size > rawCapacity) {
            (rawCapacity - 1).coerceAtLeast(1)
        } else {
            rawCapacity
        }
        val activeId = (state.activeSurface as? ActiveSurface.Diagram)?.workspaceId
        val allIds = state.seqDiagrams.workspaces.map { it.id }
        val (visibleIds, overflowIds) = diagramWorkspaceIdsForWidth(allIds, activeId, capacity)
        val byId = state.seqDiagrams.workspaces.associateBy { it.id }

        LaunchedEffect(visibleIds, dragWorkspaceId) {
            if (dragWorkspaceId == null) liveVisualIds = visibleIds
        }
        val visualIds = liveVisualIds
            .takeIf { it.toSet() == visibleIds.toSet() && it.size == visibleIds.size }
            ?: visibleIds
        val currentVisualIds by rememberUpdatedState(visualIds)
        val currentAllIds by rememberUpdatedState(allIds)

        Row(Modifier.fillMaxHeight()) {
            Box(
                Modifier
                    .width((tabPitchPx * visibleIds.size / density).dp)
                    .fillMaxHeight()
                    .pointerInput(visibleIds, tabPitchPx) {
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
                                        val idx = (ch.position.x / tabPitchPx).toInt()
                                            .coerceIn(0, visibleIds.lastIndex.coerceAtLeast(0))
                                        downId = visibleIds.getOrNull(idx)
                                    }

                                    PointerEventType.Move -> {
                                        if (downId != null && !dragging && (ch.position - downPos).getDistance() > 8f) {
                                            dragging = true
                                            dragWorkspaceId = downId
                                            justReleasedWorkspaceId = null
                                            dragStartIndex = visibleIds.indexOf(downId)
                                            dragOffsetX = 0f
                                        }
                                        if (dragging && dragWorkspaceId != null) {
                                            ch.consume()
                                            dragOffsetX = ch.position.x - downPos.x
                                            liveVisualIds = browserTabOrderDuringDrag(
                                                visibleIds = visibleIds,
                                                draggedId = dragWorkspaceId,
                                                dragStartIndex = dragStartIndex,
                                                dragOffsetX = dragOffsetX,
                                                tabWidth = tabPitchPx,
                                            )
                                        }
                                    }

                                    PointerEventType.Release -> {
                                        if (dragging && dragWorkspaceId != null) {
                                            justReleasedWorkspaceId = dragWorkspaceId
                                            state.seqDiagrams.reorderWorkspaces(
                                                diagramWorkspaceOrderAfterVisibleReorder(
                                                    allIds = currentAllIds,
                                                    newVisibleOrder = currentVisualIds,
                                                ),
                                            )
                                        }
                                        dragWorkspaceId = null
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
                visibleIds.mapNotNull(byId::get).forEach { workspace ->
                    key(workspace.id) {
                        val isDragging = workspace.id == dragWorkspaceId
                        val targetIndex = visualIds.indexOf(workspace.id).takeIf { it >= 0 }
                            ?: visibleIds.indexOf(workspace.id)
                        val targetX = targetIndex * tabPitchPx
                        val animatedX by animateFloatAsState(
                            targetValue = targetX,
                            animationSpec = spring(stiffness = 650f, dampingRatio = 0.86f),
                            label = "diagram-tab-x-${workspace.id}",
                        )
                        val startX = (dragStartIndex.takeIf { it >= 0 } ?: targetIndex) * tabPitchPx
                        val tabX = tabRenderX(
                            isDragging = isDragging,
                            isJustReleased = workspace.id == justReleasedWorkspaceId,
                            pointerX = startX + dragOffsetX,
                            targetX = targetX,
                            animatedX = animatedX,
                        )
                        val active = state.activeSurface == ActiveSurface.Diagram(workspace.id)
                        Box(
                            Modifier
                                .offset { IntOffset(tabX.roundToInt(), 0) }
                                .width(DIAGRAM_TAB_WIDTH)
                                .fillMaxHeight()
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer {
                                    if (isDragging) {
                                        scaleX = 1.02f
                                        scaleY = 1.02f
                                    }
                                },
                        ) {
                            TabShell(
                                pointerKey = workspace.id,
                                label = workspace.spec.title.ifBlank { "Diagram" },
                                tooltip = diagramTabTooltip(workspace.spec),
                                isActive = active,
                                showClose = true,
                                dragging = isDragging,
                                onClick = { if (dragWorkspaceId == null) state.seqDiagrams.activateWorkspace(workspace.id) },
                                onClose = { state.seqDiagrams.requestCloseWorkspace(workspace.id) },
                                onCtxMenu = { _, _ -> ctxMenuWorkspaceId = workspace.id },
                            )
                            if (ctxMenuWorkspaceId == workspace.id) {
                                Popup(
                                    alignment = Alignment.TopStart,
                                    offset = IntOffset(0, (34 * density).roundToInt()),
                                    onDismissRequest = { ctxMenuWorkspaceId = null },
                                    properties = PopupProperties(focusable = true),
                                ) {
                                    Column(
                                        Modifier.width(190.dp).background(tc.p, RoundedCornerShape(7.dp))
                                            .border(1.dp, tc.br, RoundedCornerShape(7.dp)).padding(vertical = 4.dp),
                                    ) {
                                        CtxItem(icon = Icons.Outlined.Close, label = "Close") {
                                            state.seqDiagrams.requestCloseWorkspace(workspace.id)
                                            ctxMenuWorkspaceId = null
                                        }
                                        CtxItem(icon = Icons.Outlined.Block, label = "Close all diagrams") {
                                            closeAllDiagramWorkspaces(state)
                                            ctxMenuWorkspaceId = null
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (overflowIds.isNotEmpty()) {
                ToolbarBtn("▾ ${overflowIds.size}", active = overflowOpen, modifier = Modifier.fillMaxHeight()) {
                    overflowOpen = !overflowOpen
                }
            }
        }
        if (overflowOpen && overflowIds.isNotEmpty()) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, (34 * density).roundToInt()),
                onDismissRequest = { overflowOpen = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.width(240.dp).background(tc.p, RoundedCornerShape(7.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(7.dp)).padding(vertical = 4.dp),
                ) {
                    overflowIds.mapNotNull(byId::get).forEach { workspace ->
                        HoverBox(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                state.seqDiagrams.activateWorkspace(workspace.id)
                                overflowOpen = false
                            },
                        ) {
                            AppText(
                                workspace.spec.title.ifBlank { "Diagram" },
                                fontSize = 11.sp,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Closes every diagram workspace, saving nothing silently.
 *
 * pendingCloseWorkspaceId is a single slot (SeqDiagramCoordinator), so a naive
 * `workspaces.forEach(::requestCloseWorkspace)` has every dirty workspace overwrite the previous
 * one's prompt: exactly one of them asks to save and the rest stay open with no explanation.
 * Instead close the clean ones outright and hand the first dirty one to the normal Save/Discard
 * gate — re-invoking the action walks the remaining drafts one prompt at a time, which is the only
 * shape that never discards an unsaved draft without asking.
 */
private fun closeAllDiagramWorkspaces(state: AppState) {
    val ids = state.seqDiagrams.workspaces.map { it.id }
    ids.filterNot(state.seqDiagrams::workspaceNeedsSave).forEach { state.seqDiagrams.closeWorkspace(it) }
    ids.firstOrNull(state.seqDiagrams::workspaceNeedsSave)?.let(state.seqDiagrams::requestCloseWorkspace)
}

/** Title + scope + source, matching what the inspector's own Scope pill shows (rangeSummary,
 * ui/SeqDiagramDialog.kt) so the tab tooltip and the inspector never disagree about the range. */
private fun diagramTabTooltip(spec: SeqDiagramSpec): String = buildString {
    append(spec.title.ifBlank { "Diagram" })
    append('\n')
    append(rangeSummary(spec.range))
    spec.sourceFile?.let {
        append('\n')
        append(it)
    }
}

/**
 * Picks the visible window of diagram tabs, preserving [ids]' own relative order rather than
 * force-swapping the active id into slot 0 the way an earlier revision did (that clobbered any
 * order the user had just dragged into place). The default window is the trailing (most
 * recently opened) [capacity] ids, matching a browser's own "keep the newest tabs visible"
 * convention; if [activeId] would fall outside that window, the window slides left just far
 * enough to include it, never further — so activating an older tab reveals it in place instead
 * of relocating it to the front.
 */
internal fun diagramWorkspaceIdsForWidth(
    ids: List<String>,
    activeId: String?,
    capacity: Int,
): Pair<List<String>, List<String>> {
    if (ids.size <= capacity) return ids to emptyList()
    val activeIndex = activeId?.let(ids::indexOf) ?: -1
    val defaultStart = ids.size - capacity
    val start = if (activeIndex in 0 until defaultStart) activeIndex else defaultStart
    val visible = ids.subList(start, start + capacity)
    return visible to ids.filterNot { it in visible }
}

/**
 * Commits a drag-reordered VISIBLE window back over the FULL workspace id list — the diagram-tab
 * counterpart of [tabOrderAfterVisibleReorder]. That log-tab helper can get away with a plain
 * `overflowIds + visibleIds` concatenation because its overflow is always a single prefix (the
 * oldest tabs); [diagramWorkspaceIdsForWidth]'s window can instead sit in the MIDDLE of [allIds]
 * (sliding left to keep the active id visible — see its own doc), so overflow can exist on both
 * sides at once. Walking [allIds] and substituting the next id off [newVisibleOrder] wherever a
 * slot belonged to the visible set reorders exactly that (possibly non-edge) window in place
 * while leaving every overflow id exactly where it already was.
 */
internal fun diagramWorkspaceOrderAfterVisibleReorder(
    allIds: List<String>,
    newVisibleOrder: List<String>,
): List<String> {
    val visibleSet = newVisibleOrder.toSet()
    val cursor = newVisibleOrder.iterator()
    return allIds.map { id -> if (id in visibleSet) cursor.next() else id }
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
                            isActive = tab.id == state.activeTabId && !state.diagramSurfaceActive,
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
/** The log-tab visual recipe, generalised so [DiagramWorkspaceTabs] can render diagram tabs
 *  through the exact same shell instead of an ad-hoc pill.  [pointerKey] replaces `tab.id` as the
 *  `pointerInput` restart key (cross-tab/cross-workspace id collisions are the same hazard either
 *  way — see the ID-collision note in CLAUDE.md); [tooltip] is the plain-text tooltip body
 *  (log tabs show the source path, diagram tabs show title/range/source); [leading] is an
 *  optional composable slot before the label (log tabs use it for the live-tailing `●`).
 */
@Composable
internal fun TabShell(
    pointerKey: Any,
    label: String,
    tooltip: String,
    isActive: Boolean,
    showClose: Boolean,
    dragging: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
    onClose: () -> Unit,
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
            .pointerInput(pointerKey) {
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
        leading?.invoke()
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
                        tooltip,
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

@Composable
internal fun TabItem(
    tab: LogTab, isActive: Boolean, showClose: Boolean,
    label: String = tab.filename,
    dragging: Boolean = false, onClick: () -> Unit, onClose: () -> Unit,
    onCtxMenu: (Float, Float) -> Unit = { _, _ -> },
) {
    val tc = tc()
    TabShell(
        pointerKey = tab.id,
        label = label,
        tooltip = tab.sourcePath ?: tab.filename,
        isActive = isActive,
        showClose = showClose,
        dragging = dragging,
        // Live tailing (utils/FileTailer.kt) is toggled from the tab's right-click context menu,
        // not a clickable button here — this is purely an indicator, plain and non-interactive.
        leading = if (tab.tailing) {
            {
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
        } else null,
        onClick = onClick,
        onClose = onClose,
        onCtxMenu = onCtxMenu,
    )
}
