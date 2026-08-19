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
import androidx.compose.material.icons.outlined.ContentCopy
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

/**
 * Union member of the single merged tab strip ([TabOverflowRow]): a log tab backed by
 * [AppState.tabs] or a diagram workspace backed by [AppState.seq3Sessions]. **The two backing
 * stores stay separate** — nothing in this file ever moves an id between them; a [TabRef] only
 * orders how the two render together in one strip. This preserves every LogTab-only invariant
 * (compare mode, autosave, [AppState.activateTab]) that depends on [AppState.tabs] holding
 * nothing but log tabs, same as the earlier two-strip design this replaces.
 */
internal sealed interface TabRef {
    data class Log(val tabId: String) : TabRef

    data class Diagram(val sessionId: String) : TabRef
}

/** The id a [TabRef] drags/renders under. Log tab ids are bare `UUID.randomUUID()` strings and
 *  diagram session ids are always `"seq3-<uuid>"` ([Seq3Session.begin]), so the two id spaces can
 *  never collide — safe to feed straight into [browserTabOrderDuringDrag]'s/[tabOrderAfterVisibleReorder]'s
 *  existing string-keyed machinery without a namespacing prefix. */
internal fun TabRef.rawId(): String = when (this) {
    is TabRef.Log -> tabId
    is TabRef.Diagram -> sessionId
}

/**
 * Reconciles the strip's last known interleaved order against the CURRENT contents of both
 * backing stores. An entry present in [previousOrder] that's still live keeps its old relative
 * position — this is what lets a user's manual drag survive an unrelated tab/diagram opening or
 * closing elsewhere. An id in [logTabIds]/[diagramSessionIds] that isn't in [previousOrder] yet (a
 * newly opened tab or diagram) is appended at the end, in its own store's order. An id that WAS in
 * [previousOrder] but is no longer live in its store (closed) is dropped.
 *
 * Broader than the plain `takeIf { it.toSet() == ids.toSet() } ?: ids` staleness guard this file
 * already uses for `liveVisualTabIds` below — that guard only ever falls all the way back to a
 * fresh order on any mismatch; this instead merges, since the interleaving must survive individual
 * opens/closes one at a time rather than reset wholesale on every membership change.
 */
internal fun reconcileTabOrder(
    previousOrder: List<TabRef>,
    logTabIds: List<String>,
    diagramSessionIds: List<String>,
): List<TabRef> {
    val liveLogIds = logTabIds.toSet()
    val liveDiagramIds = diagramSessionIds.toSet()
    val kept = previousOrder.filter { ref ->
        when (ref) {
            is TabRef.Log -> ref.tabId in liveLogIds
            is TabRef.Diagram -> ref.sessionId in liveDiagramIds
        }
    }
    val keptLogIds = kept.filterIsInstance<TabRef.Log>().mapTo(mutableSetOf()) { it.tabId }
    val keptDiagramIds = kept.filterIsInstance<TabRef.Diagram>().mapTo(mutableSetOf()) { it.sessionId }
    val newRefs = logTabIds.filterNot { it in keptLogIds }.map(TabRef::Log) +
        diagramSessionIds.filterNot { it in keptDiagramIds }.map(TabRef::Diagram)
    return kept + newRefs
}

/**
 * Splits a reordered union strip back into its two backing stores' own id orders — the commit
 * step after a drag release. Each kind keeps its relative order from [order]; the caller assigns
 * the log ids straight into [AppState.tabs] and the diagram ids into [Seq3Session.reorderSessions]
 * — see [TabOverflowRow]'s release handler.
 */
internal fun partitionTabOrder(order: List<TabRef>): Pair<List<String>, List<String>> =
    order.filterIsInstance<TabRef.Log>().map { it.tabId } to
        order.filterIsInstance<TabRef.Diagram>().map { it.sessionId }

/** Closes every open diagram workspace. Unlike v1/v2's `SeqDiagramCoordinator`, a v3
 *  [Seq3WorkspaceSession] has no dirty-close confirmation of its own (Seq3Workspace.kt's header
 *  `CloseButton` already closes outright) — a confirmed diagram is durable the moment it's written
 *  as a note, so there is no "unsaved draft" state here to protect with a one-at-a-time prompt. */
private fun closeAllDiagramWorkspaces(state: AppState) {
    state.seq3Sessions.sessions.map { it.id }.forEach(state.seq3Sessions::close)
}

/** Diagram-tab counterpart of [AppState.closeOtherTabs] — scoped to diagram sessions only (a
 *  diagram's context menu must never reach into the log-tab store), and ordered by
 *  [Seq3Session.sessions]' own order, exactly like the log-tab action uses [AppState.tabs]' order
 *  rather than the strip's merged visual order. */
private fun closeOtherDiagramWorkspaces(state: AppState, keepId: String) {
    state.seq3Sessions.sessions.map { it.id }.filter { it != keepId }.forEach(state.seq3Sessions::close)
}

/** Diagram-tab counterpart of [AppState.closeTabsToRight]. */
private fun closeDiagramWorkspacesToRight(state: AppState, id: String) {
    val ids = state.seq3Sessions.sessions.map { it.id }
    val idx = ids.indexOf(id)
    if (idx < 0) return
    ids.drop(idx + 1).forEach(state.seq3Sessions::close)
}

/** Diagram-tab counterpart of [AppState.closeTabsToLeft]. */
private fun closeDiagramWorkspacesToLeft(state: AppState, id: String) {
    val ids = state.seq3Sessions.sessions.map { it.id }
    val idx = ids.indexOf(id)
    if (idx < 0) return
    ids.take(idx).forEach(state.seq3Sessions::close)
}

/** Title + scope + source, matching what the queue panel's own scope line shows so the tab tooltip
 * and the workspace never disagree about the range. */
private fun diagramTabTooltip(session: Seq3WorkspaceSession): String = buildString {
    append(session.document.title.ifBlank { "Diagram" })
    append('\n')
    append(rangeSummary(session.range))
    session.document.sourceFile?.let {
        append('\n')
        append(it)
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

/** Generic over [T] so the same helper serves the unified [TabRef] strip below, `CompareView.kt`'s
 *  own `List<LogTab>` picker strip, and any test fixture — the logic never actually inspects a
 *  tab's fields, only `tabs.size`, so genericizing is a no-op for every existing caller. */
internal fun <T> splitTabsForVisibility(
    tabs: List<T>,
    containerPx: Int,
    minTabPx: Int,
    overflowButtonPx: Int,
    visibleTabLimit: Int,
): Pair<List<T>, List<T>> {
    if (containerPx == 0) return tabs to emptyList()
    if (tabs.isEmpty()) return emptyList<T>() to emptyList()
    var n = minOf(tabs.size, visibleTabLimit.coerceIn(1, tabs.size))
    while (n > 1) {
        val avail = if (n < tabs.size) containerPx - overflowButtonPx else containerPx
        if (avail / n >= minTabPx) break
        n--
    }
    return tabs.takeLast(n) to tabs.dropLast(n)
}

/** Generic over [T] for the same reason as [splitTabsForVisibility] — a plain concatenation that
 *  never inspects an id's shape, so it serves both the unified [TabRef] strip's `List<String>`
 *  drag machinery and `CompareView.kt`'s own `List<String>` picker unchanged. */
internal fun <T> tabOrderAfterVisibleReorder(
    visibleIds: List<T>,
    overflowIds: List<T>,
): List<T> = overflowIds + visibleIds

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
    // Right-click popup for a single diagram tab — local like the overflow popup below, not an
    // AppState field: a log tab's context menu is the window-level state.tabCtx overlay (App.kt),
    // but a diagram tab's menu only ever needs a Popup anchored to that tab's own Box.
    var ctxMenuSessionId by remember { mutableStateOf<String?>(null) }

    // The strip's own interleaving of log tabs and diagram workspaces — the union order that lets
    // a diagram tab sit anywhere among log tabs. Deliberately session-only Compose state, not a
    // new AppState field: TabBar is composed exactly once for the life of the window (App.kt:308,
    // never behind a key()/conditional that would tear this down), so `remember` here already
    // gives it the same lifetime an AppState field would, without widening AppState's surface for
    // something that's purely a rendering order, fully re-derivable from the two backing stores on
    // every read via reconcileTabOrder. NOT persisted to autosave / across app restarts — a fresh
    // launch starts from the two stores' own natural order (log tabs first, diagrams appended),
    // same as this file's original two-strip layout always effectively showed anyway.
    var unifiedOrder by remember { mutableStateOf(emptyList<TabRef>()) }

    val minTabPx = (80 * density).toInt()
    val ovBtnPx = (40 * density).toInt()

    val logTabIds = state.tabs.map { it.id }
    val diagramSessionIds = state.seq3Sessions.sessions.map { it.id }
    // Reconciled only while nothing is mid-drag — mirrors liveVisualTabIds' own dragTabId-gated
    // LaunchedEffect below, so a membership change landing mid-gesture can never clobber the
    // in-flight optimistic reorder.
    LaunchedEffect(logTabIds, diagramSessionIds) {
        if (dragTabId == null) unifiedOrder = reconcileTabOrder(unifiedOrder, logTabIds, diagramSessionIds)
    }

    val visibleTabLimit = state.settings.visibleTabLimit
    val (visibleRefs, overflowRefs) = remember(unifiedOrder, containerPx, visibleTabLimit) {
        splitTabsForVisibility(
            tabs = unifiedOrder,
            containerPx = containerPx,
            minTabPx = minTabPx,
            overflowButtonPx = ovBtnPx,
            visibleTabLimit = visibleTabLimit,
        )
    }

    val logById = state.tabs.associateBy { it.id }
    val diagramById = state.seq3Sessions.sessions.associateBy { it.id }

    val visibleTabIds = visibleRefs.map { it.rawId() }
    val overflowTabIds = overflowRefs.map { it.rawId() }
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
        if (visibleRefs.isNotEmpty() && tabAreaPx > 0) tabAreaPx.toFloat() / visibleRefs.size else minTabPx.toFloat()
    val visualOrderIds =
        liveVisualTabIds.takeIf { it.toSet() == visibleTabIds.toSet() && it.size == visibleTabIds.size }
            ?: visibleTabIds
    val currentVisualOrderIds by rememberUpdatedState(visualOrderIds)
    val currentOverflowTabIds by rememberUpdatedState(overflowTabIds)
    val currentUnifiedOrder by rememberUpdatedState(unifiedOrder)

    // Commits a drag release: resolves the reordered raw ids back to TabRefs, updates the strip's
    // own unifiedOrder, then partitions and writes BOTH backing stores — state.tabs via the same
    // direct assignment the pre-merge log-tab strip always used, seq3Sessions via its own
    // reorderSessions. The two stores are never otherwise touched by this file.
    fun commitReorder(newVisibleOrder: List<String>, overflowOrder: List<String>) {
        val newRawOrder = tabOrderAfterVisibleReorder(visibleIds = newVisibleOrder, overflowIds = overflowOrder)
        val byRawId = currentUnifiedOrder.associateBy { it.rawId() }
        val newOrder = newRawOrder.mapNotNull(byRawId::get)
        unifiedOrder = newOrder
        val (logOrder, diagramOrder) = partitionTabOrder(newOrder)
        state.tabs = logOrder.mapNotNull { id -> state.tabs.find { it.id == id } }
        state.seq3Sessions.reorderSessions(diagramOrder)
    }

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
                                        commitReorder(currentVisualOrderIds, currentOverflowTabIds)
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
            visibleRefs.forEach { ref ->
                key(ref.rawId()) {
                    val isDragging = ref.rawId() == dragTabId
                    val targetIndex = visualOrderIds.indexOf(ref.rawId()).takeIf { it >= 0 } ?: visibleRefs.indexOf(ref)
                    val targetX = targetIndex * tabWidthPx
                    val animatedX by animateFloatAsState(
                        targetValue = targetX,
                        animationSpec = spring(stiffness = 650f, dampingRatio = 0.86f),
                        label = "tab-x-${ref.rawId()}",
                    )
                    val startX = (dragStartIndex.takeIf { it >= 0 } ?: targetIndex) * tabWidthPx
                    val tabX = tabRenderX(
                        isDragging = isDragging,
                        isJustReleased = ref.rawId() == justReleasedTabId,
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
                        when (ref) {
                            is TabRef.Log -> logById[ref.tabId]?.let { tab ->
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

                            is TabRef.Diagram -> diagramById[ref.sessionId]?.let { workspace ->
                                val active = state.activeSurface == ActiveSurface.Diagram3(workspace.id)
                                TabShell(
                                    pointerKey = workspace.id,
                                    label = workspace.document.title.ifBlank { "Diagram" },
                                    tooltip = diagramTabTooltip(workspace),
                                    isActive = active,
                                    showClose = true,
                                    dragging = isDragging,
                                    onClick = { if (dragTabId == null) state.seq3Sessions.activate(workspace.id) },
                                    onClose = { state.seq3Sessions.close(workspace.id) },
                                    onCtxMenu = { _, _ -> ctxMenuSessionId = workspace.id },
                                )
                                if (ctxMenuSessionId == workspace.id) {
                                    Popup(
                                        alignment = Alignment.TopStart,
                                        offset = IntOffset(0, (34 * density).roundToInt()),
                                        onDismissRequest = { ctxMenuSessionId = null },
                                        properties = PopupProperties(focusable = true),
                                    ) {
                                        Column(
                                            Modifier.width(200.dp).background(tc.p, RoundedCornerShape(7.dp))
                                                .border(1.dp, tc.br, RoundedCornerShape(7.dp)).padding(vertical = 4.dp),
                                        ) {
                                            // Extended to match the log-tab context menu's own action
                                            // family (copy / close-other / close-to-side / close-all) —
                                            // see this file's WP6 report for why a diagram's "to the
                                            // right/left" scope is the diagram-only order rather than
                                            // the strip's merged visual order (closeTabsToRight/Left's
                                            // own log-tab precedent).
                                            CtxItem(icon = Icons.Outlined.ContentCopy, label = "Copy diagram title") {
                                                state.copyToClipboard(workspace.document.title.ifBlank { "Diagram" })
                                                ctxMenuSessionId = null
                                            }
                                            CtxDivider()
                                            CtxItem(icon = Icons.Outlined.Close, label = "Close") {
                                                state.seq3Sessions.close(workspace.id)
                                                ctxMenuSessionId = null
                                            }
                                            CtxItem(icon = Icons.Outlined.Block, label = "Close other diagrams") {
                                                closeOtherDiagramWorkspaces(state, keepId = workspace.id)
                                                ctxMenuSessionId = null
                                            }
                                            CtxItem(icon = Icons.Outlined.Block, label = "Close diagrams to the right") {
                                                closeDiagramWorkspacesToRight(state, workspace.id)
                                                ctxMenuSessionId = null
                                            }
                                            CtxItem(icon = Icons.Outlined.Block, label = "Close diagrams to the left") {
                                                closeDiagramWorkspacesToLeft(state, workspace.id)
                                                ctxMenuSessionId = null
                                            }
                                            CtxItem(icon = Icons.Outlined.Block, label = "Close all diagrams") {
                                                closeAllDiagramWorkspaces(state)
                                                ctxMenuSessionId = null
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
        if (overflowRefs.isNotEmpty()) {
            Box(Modifier.fillMaxHeight()) {
                ToolbarBtn(
                    "▾ ${overflowRefs.size}",
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
                                overflowRefs.forEach { ref ->
                                    val label = when (ref) {
                                        is TabRef.Log -> logById[ref.tabId]?.let { tabDisplayLabel(it, state.tabs) }
                                        is TabRef.Diagram -> diagramById[ref.sessionId]?.document?.title?.ifBlank { "Diagram" }
                                    } ?: return@forEach
                                    HoverBox(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            // Bring the picked tab into the visible window, mirroring
                                            // AppState.activateOverflowTab's own "select from overflow
                                            // moves it into view" contract for log tabs — diagrams
                                            // have no independent "into view" concept of their own
                                            // (unlike the deleted diagramWorkspaceIdsForWidth sliding
                                            // window), so this strip's own unifiedOrder is what moves.
                                            unifiedOrder = unifiedOrder.filterNot { it == ref } + ref
                                            when (ref) {
                                                is TabRef.Log -> state.activateOverflowTab(ref.tabId)
                                                is TabRef.Diagram -> state.seq3Sessions.activate(ref.sessionId)
                                            }
                                            overflowOpen = false
                                        },
                                    ) {
                                        AppText(
                                            label, color = tc.tx, fontSize = 12.sp,
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

/** The log-tab visual recipe, generalised so [TabOverflowRow] can render a diagram tab through the
 *  exact same shell instead of an ad-hoc pill.  [pointerKey] replaces `tab.id` as the
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
        } else {
            null
        },
        onClick = onClick,
        onClose = onClose,
        onCtxMenu = onCtxMenu,
    )
}
