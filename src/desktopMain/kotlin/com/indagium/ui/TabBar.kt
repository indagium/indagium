@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.indagium.model.*
import kotlin.math.roundToInt

internal fun groupedFixedVisibleWidthsPx(
    items: List<TabStripItem>,
    headerWidthsPx: Map<String, Float>,
    availablePx: Float,
    fallbackTabPx: Float,
): List<Float> {
    if (items.isEmpty()) return emptyList()
    val headers = items.filterIsInstance<TabStripItem.GroupHeader>()
    val headerTotal = headers.sumOf { (headerWidthsPx[it.group.id] ?: 0f).toDouble() }.toFloat()
    val tabCount = items.count { it is TabStripItem.Tab }
    val headerBudget = when {
        headerTotal < availablePx -> headerTotal
        tabCount == 0 -> availablePx
        else -> (availablePx - minOf(availablePx * TAB_GROUP_HEADER_TAB_RESERVE_RATIO, fallbackTabPx * tabCount))
            .coerceAtLeast(0f)
    }
    val headerScale = if (headerTotal > headerBudget && headerTotal > 0f) headerBudget / headerTotal else 1f
    val scaledHeaderTotal = headerTotal * headerScale
    val sharedTabWidth = if (tabCount == 0) {
        fallbackTabPx
    } else {
        (availablePx - scaledHeaderTotal).coerceAtLeast(0f) / tabCount
    }
    return items.map { item ->
        if (item is TabStripItem.GroupHeader) (headerWidthsPx[item.group.id] ?: 0f) * headerScale else sharedTabWidth
    }
}

internal fun groupHeaderPopupOrigin(
    position: TabStripPosition,
    headerBounds: IntRect,
    popupSize: IntSize,
    windowSize: IntSize,
    topDropPx: Int,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
): IntOffset {
    val placed = ChromePopupPositionProvider(position, topDropPx)
        .calculatePosition(headerBounds, windowSize, layoutDirection, popupSize)
    return IntOffset(
        placed.x.coerceIn(0, (windowSize.width - popupSize.width).coerceAtLeast(0)),
        placed.y.coerceIn(0, (windowSize.height - popupSize.height).coerceAtLeast(0)),
    )
}

private class GroupHeaderPopupPositionProvider(
    private val position: TabStripPosition,
    private val headerBounds: IntRect,
    private val topDropPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = groupHeaderPopupOrigin(position, headerBounds, popupContentSize, windowSize, topDropPx, layoutDirection)
}

private const val TAB_GROUP_HEADER_TAB_RESERVE_RATIO = 0.5f
private val DROP_BAR_THICKNESS = 2.dp

// Fraction of a tab's own width used as the drag-reorder snap threshold — moved here from
// App.kt's file-level consts since it's only used by TabBar's drag logic.
internal const val TAB_DRAG_SNAP_BIAS = 0.25f

/** Only disambiguate labels when a filename collision is actually visible to the user. */
internal fun tabDisplayLabel(tab: LogTab, allTabs: List<LogTab>): String =
    tabLabelParts(tab, allTabs, TabLabelMode.FILE_SESSION).joined()

// ── TabBar ────────────────────────────────────────────────────────────

/**
 * The tab strip and the main toolbar in one horizontal bar, as the window always had them (TOP, toolbar at the end). The window itself
 * uses [ChromeScaffold], which places the two parts ([TabStrip], [MainToolbar]) on any edge; this is the same bar in one piece, for
 * tests and previews that need the strip without the scaffold.
 */
@Composable
internal fun TabBar(state: AppState, onReclaimFocus: () -> Unit = {}) {
    val tc = tc()
    val tabStyle = state.settings.tabStripStyle
    Row(
        Modifier.fillMaxWidth().height(tabStripHeightDp(tabStyle).dp).testTag("tab-strip").background(tabStripBackground(tabStyle, tc))
            .border(BorderStroke(1.dp, tc.br)).padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabStrip(state, ChromeOrientation.HORIZONTAL, Modifier.weight(1f).fillMaxHeight(), onReclaimFocus)
        // Outside the strip, not inside it: its drag-reorder math divides the strip's pixel width by its own visible-tab count, so a
        // sibling (rather than one more item inside the row) keeps that math untouched.
        MainToolbar(state, ChromeOrientation.HORIZONTAL, onReclaimFocus, Modifier.fillMaxHeight())
    }
}

/**
 * The tabs: log tabs, diagram workspaces and the Tests tab in one strip with overflow, drag reorder and the style from Settings >
 * Appearance > Tabs. Horizontal tabs share the strip's width (or fit their label); vertical tabs stack, one style-height each. It draws
 * no bar of its own: the bar (background, border) belongs to [ChromeScaffold] / [TabBar].
 */
@Composable
internal fun TabStrip(state: AppState, orientation: ChromeOrientation, modifier: Modifier = Modifier, onReclaimFocus: () -> Unit = {}) {
    TabOverflowRow(state = state, modifier = modifier, vertical = orientation == ChromeOrientation.VERTICAL, onReclaimFocus = onReclaimFocus)
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

    /** The singleton AI test-suites workspace tab ([AppState.testsTabOpen]). */
    data object Tests : TabRef
}

/** The raw id the Tests tab drags/renders under. Log tab ids are `t<n>`, UUIDs or `capture-launcher-<uuid>` and diagram
 *  session ids `seq3-<uuid>`, so this constant can never collide with either. */
internal const val TESTS_TAB_RAW_ID = "tests-workspace"

/** The id a [TabRef] drags/renders under. Log tab ids are bare `UUID.randomUUID()` strings and
 *  diagram session ids are always `"seq3-<uuid>"` ([Seq3Session.begin]), so the two id spaces can
 *  never collide — safe to feed straight into [browserTabOrderDuringDrag]'s/[tabOrderAfterVisibleReorder]'s
 *  existing string-keyed machinery without a namespacing prefix. */
internal fun TabRef.rawId(): String = when (this) {
    is TabRef.Log -> tabId
    is TabRef.Diagram -> sessionId
    TabRef.Tests -> TESTS_TAB_RAW_ID
}

/**
 * Reconciles the strip's last known interleaved order against the CURRENT contents of both
 * backing stores. An entry present in [previousOrder] that's still live keeps its old relative
 * position — this is what lets a user's manual drag survive an unrelated tab/diagram opening or
 * closing elsewhere. An id in [diagramSessionIds] that isn't in [previousOrder] yet (a newly opened
 * diagram) is appended at the end, in its store's order. A new log id in [logTabIds] is placed where
 * the store put it: right after the nearest preceding log tab (in [logTabIds] order) already in the
 * order, else before the first following one, else appended — so a tab that replaces another one in
 * the middle (a file opened from the New tab) takes that slot, while a plain append still appends.
 * An id that WAS in [previousOrder] but is no longer live in its store (closed) is dropped. The
 * relative order of the surviving entries never changes.
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
    testsOpen: Boolean = false,
): List<TabRef> {
    val liveLogIds = logTabIds.toSet()
    val liveDiagramIds = diagramSessionIds.toSet()
    val kept = previousOrder.filter { ref ->
        when (ref) {
            is TabRef.Log -> ref.tabId in liveLogIds
            is TabRef.Diagram -> ref.sessionId in liveDiagramIds
            TabRef.Tests -> testsOpen
        }
    }
    val keptLogIds = kept.filterIsInstance<TabRef.Log>().mapTo(mutableSetOf()) { it.tabId }
    val keptDiagramIds = kept.filterIsInstance<TabRef.Diagram>().mapTo(mutableSetOf()) { it.sessionId }
    val result = kept.toMutableList()
    logTabIds.forEachIndexed { i, id ->
        if (id in keptLogIds) return@forEachIndexed
        val ref = TabRef.Log(id)
        val after = (i - 1 downTo 0).firstNotNullOfOrNull { j -> result.indexOf(TabRef.Log(logTabIds[j])).takeIf { it >= 0 } }
        val before = if (after == null) {
            (i + 1 until logTabIds.size).firstNotNullOfOrNull { j -> result.indexOf(TabRef.Log(logTabIds[j])).takeIf { it >= 0 } }
        } else {
            null
        }
        when {
            after != null -> result.add(after + 1, ref)
            before != null -> result.add(before, ref)
            else -> result.add(ref)
        }
    }
    diagramSessionIds.filterNot { it in keptDiagramIds }.mapTo(result, TabRef::Diagram)
    // A newly opened Tests tab is appended like a newly opened diagram; an already-placed one keeps its slot.
    if (testsOpen && TabRef.Tests !in kept) result.add(TabRef.Tests)
    return result
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

// [vertical] stacks the tabs top to bottom, each one style-height tall (the main axis below is then y, not x); the overflow button goes
// under them. A vertical strip lays its tabs out like FIT mode does (one length per tab, here all equal), so it reuses that drag maths.
@Composable
internal fun TabOverflowRow(state: AppState, modifier: Modifier, vertical: Boolean = false, onReclaimFocus: () -> Unit = {}) {
    val tc = tc()
    val openGroupEditor = LocalOpenTabGroupEditor.current
    val densityContext = LocalDensity.current
    val density = densityContext.density
    var containerPx by remember { mutableStateOf(0) }
    var tabAreaPx by remember { mutableStateOf(0) }
    var dragTabId by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableStateOf(-1) }
    // Along the strip's main axis (x for a horizontal strip, y for a vertical one).
    var dragOffsetX by remember { mutableStateOf(0f) }
    var justReleasedTabId by remember { mutableStateOf<String?>(null) }
    var liveVisualTabIds by remember { mutableStateOf(emptyList<String>()) }
    var overflowOpen by remember { mutableStateOf(false) }
    var viewportAnchorRef by remember { mutableStateOf<TabRef?>(null) }
    var dropTargetKey by remember { mutableStateOf<String?>(null) }
    var dropTargetAfter by remember { mutableStateOf(false) }
    var dropAtStripStart by remember { mutableStateOf(false) }
    var dropAtStripEnd by remember { mutableStateOf(false) }
    var ctxMenuGroupId by remember { mutableStateOf<String?>(null) }
    var ctxMenuGroupBounds by remember { mutableStateOf<IntRect?>(null) }
    // Right-click popup for a single diagram tab — local like the overflow popup below, not an
    // AppState field: a log tab's context menu is the window-level state.tabCtx overlay (App.kt),
    // but a diagram tab's menu only ever needs a Popup anchored to that tab's own Box.
    var ctxMenuSessionId by remember { mutableStateOf<String?>(null) }

    // A dismissed Popup (and a clicked item) hands keyboard focus back to the root, which a clickable otherwise keeps (CLAUDE.md).
    fun dismissCtxMenu() {
        ctxMenuSessionId = null
        onReclaimFocus()
    }

    fun dismissGroupMenu() {
        ctxMenuGroupId = null
        ctxMenuGroupBounds = null
        onReclaimFocus()
    }

    fun dismissOverflow() {
        overflowOpen = false
        onReclaimFocus()
    }

    val stripHeightDp = tabStripHeightDp(state.settings.tabStripStyle)
    val stripBackdrop = tabStripBackground(state.settings.tabStripStyle, tc)
    val minTabPx = (80 * density).toInt()
    // A vertical strip's overflow button is as tall as a tab; a horizontal one is 40 dp wide.
    val ovBtnPx = if (vertical) (stripHeightDp * density).toInt() else (40 * density).toInt()

    // AppState is the sole order authority so group menu changes, close cleanup and drag commits
    // cannot be overwritten by a remembered composition-only copy.
    val renderOrder = state.reconciledTabOrder()
    val groups = state.tabGroups
    val groupedMode = groups.isNotEmpty()
    val activeStripRef = when (val active = state.activeSurface) {
        is ActiveSurface.Log -> TabRef.Log(active.tabId)
        is ActiveSurface.Diagram3 -> TabRef.Diagram(active.sessionId)
        ActiveSurface.Tests -> TabRef.Tests
        null -> state.activeTabId?.let(TabRef::Log)
    }
    // A collapsed group still shows the tab being viewed, so the active ref is part of what the projection depends on.
    val stripItems = remember(renderOrder, groups, activeStripRef) { projectTabStripItems(renderOrder, groups, activeStripRef) }

    val visibleTabLimit = state.settings.visibleTabLimit
    val settings = state.settings
    val position = settings.tabStripPosition
    // A group chip is drawn by the same shell as a tab, so it takes the strip's style, edge and close-button side.
    val chipAppearance = TabAppearance(
        style = settings.tabStripStyle,
        position = position,
        closeButtonPosition = settings.tabCloseButtonPosition,
        closeAlways = settings.tabCloseOnEvery,
    )
    // The widths-differ maths (FIT) also serves a vertical strip, where every tab's length along the strip is one style height.
    val fit = vertical || settings.tabsFitLabel()
    val groupedFixedMode = groupedMode && !fit
    val textMeasurer = rememberTextMeasurer()
    val uiFont = LocalUiFontFamily.current
    val groupHeaderWidthsPx = remember(groups, textMeasurer, densityContext, uiFont, settings.tabStripStyle) {
        groups.associate { group ->
            group.id to tabGroupHeaderWidthPx(group, textMeasurer, uiFont, densityContext, settings.tabStripStyle)
        }
    }
    val logById = state.tabs.associateBy { it.id }
    val diagramById = state.seq3Sessions.sessions.associateBy { it.id }
    val activeGroup = activeStripRef?.let(state::tabGroupOf)

    data class ActiveGroupObservation(val ref: TabRef?, val groupId: String?, val collapsed: Boolean?)
    val activeObservation = ActiveGroupObservation(activeStripRef, activeGroup?.id, activeGroup?.collapsed)
    var lastActiveObservation by remember { mutableStateOf(activeObservation) }
    LaunchedEffect(activeObservation) {
        val previous = lastActiveObservation
        lastActiveObservation = activeObservation
        when {
            activeObservation.ref != null && activeObservation.groupId == null -> viewportAnchorRef = null
            activeObservation.ref != null && activeObservation.collapsed == false -> {
                val refChanged = previous.ref != activeObservation.ref
                val groupExpanded = previous.groupId == activeObservation.groupId && previous.collapsed == true
                if (refChanged || groupExpanded) viewportAnchorRef = activeObservation.ref
            }
        }
    }

    // Label and look of every tab in the strip, per the Tabs appearance settings (the default is today's tabDisplayLabel).
    fun describe(ref: TabRef): TabDescriptor? = when (ref) {
        is TabRef.Log -> logById[ref.tabId]?.let { logTabDescriptor(it, state.tabs, settings) }
        is TabRef.Diagram -> diagramById[ref.sessionId]?.let {
            workspaceTabDescriptor(it.document.title.ifBlank { "Diagram" }, TabKind.DIAGRAM, settings)
        }
        TabRef.Tests -> workspaceTabDescriptor("Tests", TabKind.TESTS, settings)
    }
    val descriptors = renderOrder.mapNotNull { ref -> describe(ref)?.let { ref.rawId() to it } }.toMap()

    // FIT only: each tab as wide as its label (the equal-share maths of FIXED needs none of this).
    fun itemFitDp(item: TabStripItem): Float = when (item) {
        is TabStripItem.Tab -> descriptors[item.ref.rawId()]?.let { descriptor ->
            tabFitContentDp(descriptor.label, descriptor.appearance)
        } ?: FIT_TAB_MIN_DP
        is TabStripItem.GroupHeader -> (groupHeaderWidthsPx[item.group.id] ?: 64 * density).div(density)
    }
    val fitWidthsPx = when {
        vertical -> stripItems.map { stripHeightDp * density }
        settings.tabsFitLabel() -> fitTabWidthsPx(stripItems.map(::itemFitDp), density)
        else -> emptyList()
    }
    val selectionWidthsPx = when {
        vertical -> stripItems.map { stripHeightDp * density }
        fit -> fitWidthsPx
        groupedFixedMode -> stripItems.map { item ->
            if (item is TabStripItem.GroupHeader) groupHeaderWidthsPx[item.group.id] ?: minTabPx.toFloat() else minTabPx.toFloat()
        }
        else -> stripItems.map { minTabPx.toFloat() }
    }
    val (visibleItems, overflowItems) = remember(
        stripItems, containerPx, visibleTabLimit, fit, fitWidthsPx, selectionWidthsPx, viewportAnchorRef, groups, groupedFixedMode,
    ) {
        if (!groupedMode) {
            val (visible, overflow) = if (fit) {
                splitTabsForVisibilityFit(renderOrder, fitWidthsPx, containerPx, ovBtnPx, visibleTabLimit)
            } else {
                splitTabsForVisibility(renderOrder, containerPx, minTabPx, ovBtnPx, visibleTabLimit)
            }
            visible.map(TabStripItem::Tab) to overflow.map(TabStripItem::Tab)
        } else {
            splitTabStripItems(
                items = stripItems,
                widthsPx = selectionWidthsPx,
                containerPx = containerPx,
                overflowButtonPx = ovBtnPx,
                visibleItemLimit = visibleTabLimit,
                minimumItemPx = if (vertical) (stripHeightDp * density).toInt() else minTabPx,
                groups = groups,
                anchorRef = viewportAnchorRef,
                forceOverflow = stripItems.filterIsInstance<TabStripItem.Tab>().size < renderOrder.size,
                preserveItemSize = vertical,
            )
        }
    }

    val visibleRefs = visibleItems.filterIsInstance<TabStripItem.Tab>().map { it.ref }
    val visibleRefSet = visibleRefs.toSet()
    val overflowRefs = if (groupedMode) renderOrder.filterNot { it in visibleRefSet } else overflowItems.filterIsInstance<TabStripItem.Tab>().map { it.ref }
    val visibleTabIds = visibleItems.map { it.key }
    val overflowTabIds = overflowItems.map { it.key }
    // The group a dragged tab would join: the one under the pointer, whose members' tint then rises.
    val draggedItem = visibleItems.firstOrNull { it.key == dragTabId }
    val hoveredGroupId = if (draggedItem is TabStripItem.Tab) {
        when (val target = visibleItems.firstOrNull { it.key == dropTargetKey }) {
            is TabStripItem.GroupHeader -> target.group.id
            is TabStripItem.Tab -> groups.firstOrNull { target.ref in it.members }?.id
            null -> null
        }
    } else {
        null
    }

    // A member tab's look: a tint in its group's colour inside the tab's own shape (stronger while a dragged tab hovers the group).
    fun memberAppearance(ref: TabRef, base: TabAppearance): TabAppearance {
        val group = groups.firstOrNull { ref in it.members } ?: return base
        return base.copy(
            groupColor = group.color,
            groupTintAlpha = tabGroupTintAlpha(tc.p, dragHover = group.id == hoveredGroupId),
        )
    }
    LaunchedEffect(visibleTabIds, dragTabId) {
        if (dragTabId == null) liveVisualTabIds = visibleTabIds
    }
    LaunchedEffect(justReleasedTabId) {
        if (justReleasedTabId != null) {
            kotlinx.coroutines.delay(120)
            justReleasedTabId = null
        }
    }
    val tabWidthPx = if (visibleItems.isNotEmpty() && tabAreaPx > 0) {
        tabAreaPx.toFloat() / visibleItems.size
    } else {
        minTabPx.toFloat()
    }
    // Each visible tab's width: an equal share of the strip (FIXED, unchanged), or its label's width, scaled down together when they
    // would not fit (FIT).
    val visibleWidthsPx = when {
        vertical -> visibleItems.map { stripHeightDp * density }
        fit -> shrinkTabWidthsToFit(
            visibleItems.map { item ->
                when (item) {
                    is TabStripItem.GroupHeader -> groupHeaderWidthsPx[item.group.id] ?: minTabPx.toFloat()
                    is TabStripItem.Tab -> selectionWidthsPx.getOrElse(stripItems.indexOfFirst { it.key == item.key }) { minTabPx.toFloat() }
                }
            },
            tabAreaPx.toFloat(),
        )
        groupedFixedMode -> groupedFixedVisibleWidthsPx(visibleItems, groupHeaderWidthsPx, tabAreaPx.toFloat(), minTabPx.toFloat())
        else -> emptyList()
    }
    val variableWidthMode = fit || groupedFixedMode
    val widthById = if (variableWidthMode) visibleTabIds.zip(visibleWidthsPx).toMap() else emptyMap()
    val visualOrderIds =
        liveVisualTabIds.takeIf { it.toSet() == visibleTabIds.toSet() && it.size == visibleTabIds.size }
            ?: visibleTabIds
    val visualOffsetsPx = if (variableWidthMode) tabOffsets(visualOrderIds.map { widthById[it] ?: tabWidthPx }) else emptyList()
    val currentVisualOrderIds by rememberUpdatedState(visualOrderIds)
    val currentOverflowTabIds by rememberUpdatedState(overflowTabIds)
    val currentUnifiedOrder by rememberUpdatedState(renderOrder)
    val currentVisibleItems by rememberUpdatedState(visibleItems)
    val currentAllItems by rememberUpdatedState(stripItems)
    val currentDropTargetKey by rememberUpdatedState(dropTargetKey)
    val currentDropTargetAfter by rememberUpdatedState(dropTargetAfter)
    val currentDropAtStripStart by rememberUpdatedState(dropAtStripStart)
    val currentDropAtStripEnd by rememberUpdatedState(dropAtStripEnd)
    val currentOverflowItems by rememberUpdatedState(overflowItems)

    fun commitReorder(newVisibleOrder: List<String>, overflowOrder: List<String>, draggedKey: String?, targetKey: String?) {
        val itemByKey = (currentAllItems + currentVisibleItems + currentOverflowItems).associateBy { it.key }
        val dragged = itemByKey[draggedKey]
        val target = itemByKey[targetKey]
        val visibleOrderRefs = newVisibleOrder.mapNotNull { itemByKey[it] as? TabStripItem.Tab }.map { it.ref }.distinct()
        val reorderedRefs = if (!groupedMode) {
            val newRawOrder = tabOrderAfterVisibleReorder(newVisibleOrder, overflowOrder)
            newRawOrder.mapNotNull { itemByKey[it] as? TabStripItem.Tab }.map { it.ref }.distinct()
        } else {
            val visibleSet = currentVisibleItems.filterIsInstance<TabStripItem.Tab>().mapTo(mutableSetOf()) { it.ref }
            var replacementIndex = 0
            currentUnifiedOrder.map { ref ->
                if (ref in visibleSet) visibleOrderRefs.getOrNull(replacementIndex++) ?: ref else ref
            }
        }
        when (dragged) {
            is TabStripItem.GroupHeader -> {
                val before = when {
                    currentDropAtStripStart -> currentUnifiedOrder.firstOrNull { it !in dragged.group.members }
                    currentDropAtStripEnd -> null
                    else -> tabGroupDropBefore(target, currentUnifiedOrder, currentDropTargetAfter)
                }
                if (target !is TabStripItem.GroupHeader || target.group.id != dragged.group.id) {
                    state.moveTabGroup(dragged.group.id, before)
                }
            }
            is TabStripItem.Tab -> {
                val ref = dragged.ref
                val targetGroup = when (target) {
                    is TabStripItem.GroupHeader -> target.group
                    is TabStripItem.Tab -> state.tabGroupOf(target.ref)
                    null -> null
                }
                when {
                    targetGroup != null && target is TabStripItem.Tab -> {
                        state.assignTabToGroup(
                            ref,
                            targetGroup.id,
                            before = if (!currentDropTargetAfter) {
                                target.ref
                            } else {
                                targetGroup.members.dropWhile { it != target.ref }.drop(1).firstOrNull { it != ref }
                            },
                        )
                    }
                    targetGroup != null -> state.assignTabToGroup(ref, targetGroup.id)
                    else -> {
                        // A log leaving its group takes its diagrams along and they stay right after it.
                        val followers = if (state.tabGroupOf(ref) != null) state.removeTabFromGroup(ref) else emptyList()
                        state.commitTabOrder(orderWithFollowersAfter(reorderedRefs, ref, followers))
                    }
                }
            }
            null -> state.commitTabOrder(reorderedRefs)
        }
    }

    fun pointerTargetIndex(pointerMain: Float): Int? {
        val total = if (variableWidthMode) visibleWidthsPx.sum() else tabWidthPx * visibleTabIds.size
        if (pointerMain < 0f || pointerMain > total || visibleTabIds.isEmpty()) return null
        return if (variableWidthMode) {
            tabIndexAtX(pointerMain, visibleWidthsPx)
        } else {
            (pointerMain / tabWidthPx).toInt().coerceIn(0, visibleTabIds.lastIndex)
        }
    }

    fun pointerIsAfterTarget(pointerMain: Float, targetIndex: Int): Boolean {
        val start = if (variableWidthMode) tabOffsets(visibleWidthsPx).getOrElse(targetIndex) { 0f } else targetIndex * tabWidthPx
        val width = if (variableWidthMode) visibleWidthsPx.getOrElse(targetIndex) { tabWidthPx } else tabWidthPx
        return pointerMain >= start + width / 2f
    }

    // The strip itself: a row (tabs, then the overflow button) or, vertical, a column of the same.
    TabStripAxis(vertical, modifier.onSizeChanged { containerPx = if (vertical) it.height else it.width }) {
        Box(
            Modifier.fillStrip()
                .onSizeChanged { tabAreaPx = if (vertical) it.height else it.width }
                .pointerInput(visibleTabIds, tabWidthPx, visibleWidthsPx) {
                    var downPos = Offset.Zero
                    var downMain = 0f
                    var downId: String? = null
                    var dragging = false
                    awaitReorderPointerScope {
                        try {
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                val ch = ev.changes.firstOrNull() ?: continue
                                when (ev.type) {
                                    PointerEventType.Press -> {
                                        downPos = ch.position; dragging = false
                                        val pointerMain = if (vertical) ch.position.y else ch.position.x
                                        downMain = pointerMain
                                        val idx = pointerTargetIndex(pointerMain) ?: -1
                                        downId = visibleTabIds.getOrNull(idx)
                                        dropTargetKey = downId
                                        dropTargetAfter = if (idx >= 0) pointerIsAfterTarget(pointerMain, idx) else false
                                        dropAtStripStart = false
                                        dropAtStripEnd = false
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
                                            dragOffsetX = (if (vertical) ch.position.y else ch.position.x) - downMain
                                            val pointerMain = if (vertical) ch.position.y else ch.position.x
                                            val targetIndex = pointerTargetIndex(pointerMain)
                                            dropTargetKey = targetIndex?.let(visibleTabIds::getOrNull)
                                            dropTargetAfter = targetIndex?.let { pointerIsAfterTarget(pointerMain, it) } ?: false
                                            val total = if (variableWidthMode) visibleWidthsPx.sum() else tabWidthPx * visibleTabIds.size
                                            dropAtStripStart = pointerMain < 0f
                                            dropAtStripEnd = pointerMain > total
                                            liveVisualTabIds = if (variableWidthMode) {
                                                browserTabOrderDuringDragVariable(
                                                    visibleIds = visibleTabIds,
                                                    widthsPx = visibleWidthsPx,
                                                    draggedId = dragTabId,
                                                    dragStartIndex = dragStartIndex,
                                                    dragOffsetX = dragOffsetX,
                                                )
                                            } else {
                                                browserTabOrderDuringDrag(
                                                    visibleIds = visibleTabIds,
                                                    draggedId = dragTabId,
                                                    dragStartIndex = dragStartIndex,
                                                    dragOffsetX = dragOffsetX,
                                                    tabWidth = tabWidthPx,
                                                )
                                            }
                                        }
                                    }

                                    PointerEventType.Release -> {
                                        releaseMoveDragCursor()
                                        if (dragging && dragTabId != null) {
                                            justReleasedTabId = dragTabId
                                            commitReorder(currentVisualOrderIds, currentOverflowTabIds, dragTabId, currentDropTargetKey)
                                        }
                                        dragTabId = null
                                        dragStartIndex = -1
                                        dragOffsetX = 0f
                                        downId = null
                                        dropTargetKey = null
                                        dropTargetAfter = false
                                        dropAtStripStart = false
                                        dropAtStripEnd = false
                                        dragging = false
                                    }

                                    else -> {}
                                }
                            }
                        } finally {
                            // A live order or membership change cancels this pointerInput block.
                            // Always clear the drag gate here so clicks cannot remain disabled.
                            dragTabId = null
                            dragStartIndex = -1
                            dragOffsetX = 0f
                            dropTargetKey = null
                            dropTargetAfter = false
                            dropAtStripStart = false
                            dropAtStripEnd = false
                            liveVisualTabIds = emptyList()
                            releaseMoveDragCursor()
                        }
                    }
                }
                .testTag("tab-strip-drag-${if (dragTabId == null) "idle" else "active"}"),
        ) {
            visibleItems.forEach { item ->
                key(item.key) {
                    val isDragging = item.key == dragTabId
                    val targetIndex = visualOrderIds.indexOf(item.key).takeIf { it >= 0 } ?: visibleItems.indexOf(item)
                    val targetX = if (variableWidthMode) visualOffsetsPx.getOrElse(targetIndex) { 0f } else targetIndex * tabWidthPx
                    val animatedX by animateFloatAsState(
                        targetValue = targetX,
                        animationSpec = spring(stiffness = 650f, dampingRatio = 0.86f),
                        label = "tab-x-${item.key}",
                    )
                    val startIndex = dragStartIndex.takeIf { it >= 0 } ?: targetIndex
                    val startX = if (variableWidthMode) tabOffsets(visibleWidthsPx).getOrElse(startIndex) { 0f } else startIndex * tabWidthPx
                    val tabX = tabRenderX(
                        isDragging = isDragging,
                        isJustReleased = item.key == justReleasedTabId,
                        pointerX = startX + dragOffsetX,
                        targetX = targetX,
                        animatedX = animatedX,
                    )
                    val tabLengthDp = ((widthById[item.key] ?: tabWidthPx) / density).dp
                    Box(
                        Modifier
                            .then(
                                if (vertical) {
                                    Modifier.offset { IntOffset(0, tabX.roundToInt()) }.height(tabLengthDp).fillMaxWidth()
                                } else {
                                    Modifier.offset { IntOffset(tabX.roundToInt(), 0) }.width(tabLengthDp).fillMaxHeight()
                                },
                            )
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                if (isDragging) {
                                    scaleX = 1.02f
                                    scaleY = 1.02f
                                }
                            }
                            // The lifted tab sits on an opaque backdrop: the translucent tints of the hover and style chrome would
                            // otherwise let the neighbours it passes over show through it (the Sequence list's ghost does the same).
                            .then(
                                if (isDragging) {
                                    Modifier.background(stripBackdrop).testTag("tab-drag-ghost-${item.key}")
                                } else {
                                    Modifier
                                },
                            )
                    ) {
                        when (item) {
                            is TabStripItem.GroupHeader -> {
                                TabGroupHeader(
                                    group = item.group,
                                    vertical = vertical,
                                    width = tabLengthDp,
                                    height = stripHeightDp.dp,
                                    appearance = chipAppearance,
                                    supporting = item.supporting,
                                    dragging = isDragging,
                                    onClick = { state.toggleTabGroupCollapsed(item.group.id); onReclaimFocus() },
                                    onContextMenu = { bounds ->
                                        ctxMenuGroupId = item.group.id
                                        ctxMenuGroupBounds = bounds
                                    },
                                )
                            }
                            is TabStripItem.Tab -> when (val ref = item.ref) {
                                is TabRef.Log -> logById[ref.tabId]?.let { tab ->
                                    val described = descriptors[ref.rawId()]
                                    Box(Modifier.fillMaxSize()) {
                                        TabItem(
                                            tab = tab,
                                            label = described?.label ?: tabDisplayLabel(tab, state.tabs),
                                            appearance = memberAppearance(ref, described?.appearance ?: TabAppearance.Default),
                                            isActive = tab.id == state.activeTabId && !state.diagramSurfaceActive && !state.testsSurfaceActive,
                                            showClose = true,
                                            dragging = isDragging,
                                            onClick = {
                                                if (dragTabId == null) state.activateStripTab(ref).also {
                                                    viewportAnchorRef = ref.takeIf { groupedMode }
                                                    onReclaimFocus()
                                                }
                                            },
                                            onClose = { state.closeTab(tab.id).also { onReclaimFocus() } },
                                            onCtxMenu = { x, y -> state.tabCtx = TabCtxMenuState(tab.id, x, y) },
                                        )
                                    }
                                }

                                is TabRef.Diagram -> diagramById[ref.sessionId]?.let { workspace ->
                                    val active = state.activeSurface == ActiveSurface.Diagram3(workspace.id)
                                    Box(Modifier.fillMaxSize()) {
                                        TabShell(
                                            pointerKey = workspace.id,
                                            label = descriptors[ref.rawId()]?.label ?: workspace.document.title.ifBlank { "Diagram" },
                                            appearance = memberAppearance(ref, descriptors[ref.rawId()]?.appearance ?: TabAppearance.Default),
                                            tooltip = diagramTabTooltip(workspace),
                                            isActive = active,
                                            showClose = true,
                                            dragging = isDragging,
                                            onClick = {
                                                if (dragTabId == null) state.activateStripTab(ref).also {
                                                    viewportAnchorRef = ref.takeIf { groupedMode }
                                                    onReclaimFocus()
                                                }
                                            },
                                            onClose = { state.seq3Sessions.close(workspace.id).also { onReclaimFocus() } },
                                            onCtxMenu = { _, _ -> ctxMenuSessionId = workspace.id },
                                        )
                                    }
                                    if (ctxMenuSessionId == workspace.id) {
                                        Popup(
                                            popupPositionProvider = remember(position, density, stripHeightDp) {
                                                ChromePopupPositionProvider(position, ((stripHeightDp - 2) * density).roundToInt())
                                            },
                                            onDismissRequest = ::dismissCtxMenu,
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
                                                    dismissCtxMenu()
                                                }
                                                TabGroupContextActions(
                                                    state = state,
                                                    ref = ref,
                                                    onDismiss = ::dismissCtxMenu,
                                                    onEdit = openGroupEditor,
                                                )
                                                CtxDivider()
                                                CtxItem(icon = Icons.Outlined.Close, label = "Close") {
                                                    state.seq3Sessions.close(workspace.id)
                                                    dismissCtxMenu()
                                                }
                                                CtxItem(icon = Icons.Outlined.Block, label = "Close other diagrams") {
                                                    closeOtherDiagramWorkspaces(state, keepId = workspace.id)
                                                    dismissCtxMenu()
                                                }
                                                CtxItem(icon = Icons.Outlined.Block, label = "Close diagrams to the right") {
                                                    closeDiagramWorkspacesToRight(state, workspace.id)
                                                    dismissCtxMenu()
                                                }
                                                CtxItem(icon = Icons.Outlined.Block, label = "Close diagrams to the left") {
                                                    closeDiagramWorkspacesToLeft(state, workspace.id)
                                                    dismissCtxMenu()
                                                }
                                                CtxItem(icon = Icons.Outlined.Block, label = "Close all diagrams") {
                                                    closeAllDiagramWorkspaces(state)
                                                    dismissCtxMenu()
                                                }
                                            }
                                        }
                                    }
                                }

                                TabRef.Tests -> TabShell(
                                    pointerKey = TESTS_TAB_RAW_ID,
                                    label = "Tests",
                                    appearance = descriptors[ref.rawId()]?.appearance ?: TabAppearance.Default,
                                    tooltip = "AI test suites",
                                    isActive = state.testsSurfaceActive,
                                    showClose = true,
                                    dragging = isDragging,
                                    onClick = { if (dragTabId == null) state.openTestsTab().also { onReclaimFocus() } },
                                    onClose = { state.closeTestsTab().also { onReclaimFocus() } },
                                )
                            }
                        }
                    }
                }
            }
            // The 2dp accent bar at the leading edge of the gap the dragged tab will land in (the neighbours have slid apart to
            // make it); the layout order never changes during a drag, only the offsets do.
            val dragSlot = visualOrderIds.indexOf(dragTabId).takeIf { dragTabId != null && it >= 0 }
            if (dragSlot != null) {
                val slotPx = if (variableWidthMode) visualOffsetsPx.getOrElse(dragSlot) { 0f } else dragSlot * tabWidthPx
                Box(
                    (
                        if (vertical) {
                            Modifier.offset { IntOffset(0, slotPx.roundToInt()) }.fillMaxWidth().height(DROP_BAR_THICKNESS)
                        } else {
                            Modifier.offset { IntOffset(slotPx.roundToInt(), 0) }.width(DROP_BAR_THICKNESS).fillMaxHeight()
                        }
                    ).zIndex(2f).background(tc.ac, RoundedCornerShape(1.dp)).testTag("tab-drop-bar"),
                )
            }
        }
        ctxMenuGroupId?.let { groupId ->
            val group = state.tabGroups.firstOrNull { it.id == groupId }
            val headerBounds = ctxMenuGroupBounds
            if (group != null && headerBounds != null) {
                Popup(
                    popupPositionProvider = remember(position, headerBounds, stripHeightDp, density) {
                        GroupHeaderPopupPositionProvider(
                            position,
                            headerBounds,
                            // The chip's bounds are the whole tab cell, so a top strip's menu opens right under it.
                            (stripHeightDp * density).roundToInt(),
                        )
                    },
                    onDismissRequest = ::dismissGroupMenu,
                    properties = PopupProperties(focusable = true),
                ) {
                    TabGroupChipMenu(state, group, onDismiss = ::dismissGroupMenu, onEdit = openGroupEditor)
                }
            }
        }
        if (overflowRefs.isNotEmpty()) {
            val overflowFill = if (vertical) Modifier.fillMaxWidth().height(stripHeightDp.dp) else Modifier.fillMaxHeight()
            Box(overflowFill) {
                TooltipArea(tooltip = { ToolbarTooltip("More tabs — collapsed groups and tabs outside the visible area") }) {
                    ToolbarBtn(
                        "▾ ${overflowRefs.size}",
                        active = overflowOpen,
                        modifier = overflowFill.testTag("tab-overflow-button"),
                    ) { overflowOpen = !overflowOpen }
                }
                if (overflowOpen) {
                    Popup(
                        popupPositionProvider = remember(position, density, stripHeightDp) {
                            ChromePopupPositionProvider(position, (stripHeightDp * density).toInt())
                        },
                        onDismissRequest = ::dismissOverflow,
                        properties = PopupProperties(focusable = true),
                    ) {
                        Box(
                            Modifier.width(240.dp)
                                .background(tc.p, RoundedCornerShape(7.dp))
                                .border(1.dp, tc.br, RoundedCornerShape(7.dp)),
                        ) {
                            Column(
                                Modifier.heightIn(max = 400.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 4.dp)
                                    .testTag("tab-overflow-list"),
                            ) {
                                val collapsedRefs = overflowRefs.filter { state.tabGroupOf(it)?.collapsed == true }
                                val moreRefs = overflowRefs.filterNot { state.tabGroupOf(it)?.collapsed == true }
                                if (collapsedRefs.isNotEmpty()) {
                                    OverflowSectionTitle("Collapsed groups", "tab-overflow-collapsed-groups")
                                    TabOverflowEntries(state, collapsedRefs, descriptors, tc) { ref ->
                                        viewportAnchorRef = ref
                                        state.activateStripTab(ref)
                                        dismissOverflow()
                                    }
                                }
                                if (moreRefs.isNotEmpty()) {
                                    OverflowSectionTitle("More tabs", "tab-overflow-more-tabs")
                                    TabOverflowEntries(state, moreRefs, descriptors, tc) { ref ->
                                        if (groupedMode) {
                                            viewportAnchorRef = ref
                                            state.activateStripTab(ref)
                                        } else {
                                            // Keep the legacy ungrouped behavior: picking from
                                            // overflow slides the tab to the end of the strip.
                                            state.commitTabOrder(renderOrder.filterNot { it == ref } + ref)
                                            when (ref) {
                                                is TabRef.Log -> state.activateOverflowTab(ref.tabId)
                                                is TabRef.Diagram -> state.seq3Sessions.activate(ref.sessionId)
                                                TabRef.Tests -> state.openTestsTab()
                                            }
                                        }
                                        dismissOverflow()
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

@Composable
private fun OverflowSectionTitle(title: String, tag: String) {
    val tc = tc()
    AppText(
        title,
        color = tc.ts,
        fontSize = 10.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 3.dp).testTag(tag),
    )
}

@Composable
private fun TabOverflowEntries(
    state: AppState,
    refs: List<TabRef>,
    descriptors: Map<String, TabDescriptor>,
    tc: ThemeColors,
    onClick: (TabRef) -> Unit,
) {
    val shownGroups = mutableSetOf<String>()
    refs.forEach { ref ->
        val label = descriptors[ref.rawId()]?.label ?: return@forEach
        val group = state.tabGroupOf(ref)
        if (group != null && shownGroups.add(group.id)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 5.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).background(group.color, RoundedCornerShape(50)))
                Spacer(Modifier.width(7.dp))
                AppText(group.name, color = tc.ts, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HoverBox(
            modifier = Modifier.fillMaxWidth().testTag("tab-overflow-ref-${ref.rawId()}"),
            onClick = { onClick(ref) },
        ) {
            AppText(
                label,
                color = tc.tx,
                fontSize = 12.sp,
                fontFamily = MONO,
                modifier = Modifier.padding(start = if (group == null) 14.dp else 28.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
