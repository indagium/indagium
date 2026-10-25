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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.indagium.model.*
import kotlin.math.roundToInt

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
    val density = LocalDensity.current.density
    var containerPx by remember { mutableStateOf(0) }
    var tabAreaPx by remember { mutableStateOf(0) }
    var dragTabId by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableStateOf(-1) }
    // Along the strip's main axis (x for a horizontal strip, y for a vertical one).
    var dragOffsetX by remember { mutableStateOf(0f) }
    var justReleasedTabId by remember { mutableStateOf<String?>(null) }
    var liveVisualTabIds by remember { mutableStateOf(emptyList<String>()) }
    var overflowOpen by remember { mutableStateOf(false) }
    // Right-click popup for a single diagram tab — local like the overflow popup below, not an
    // AppState field: a log tab's context menu is the window-level state.tabCtx overlay (App.kt),
    // but a diagram tab's menu only ever needs a Popup anchored to that tab's own Box.
    var ctxMenuSessionId by remember { mutableStateOf<String?>(null) }

    // A dismissed Popup (and a clicked item) hands keyboard focus back to the root, which a clickable otherwise keeps (CLAUDE.md).
    fun dismissCtxMenu() {
        ctxMenuSessionId = null
        onReclaimFocus()
    }

    fun dismissOverflow() {
        overflowOpen = false
        onReclaimFocus()
    }

    // The strip's own interleaving of log tabs and diagram workspaces — the union order that lets
    // a diagram tab sit anywhere among log tabs. Deliberately session-only Compose state, not a
    // new AppState field to DRIVE rendering from: TabBar is composed exactly once for the life of
    // the window (App.kt:308, never behind a key()/conditional that would tear this down), so
    // `remember` here already gives it the same lifetime an AppState field would, without widening
    // AppState's surface for something that's purely a rendering order, fully re-derivable from
    // the two backing stores on every read via reconcileTabOrder.
    //
    // User-observed correction: seeded from state.tabOrder (rather than always emptyList()) so a
    // restart can put a diagram tab BACK where it actually sat among the log tabs, not always
    // appended after every one of them — AppState.restoreTabOrder resolves the persisted order's
    // ids to whatever's actually live before this composable's first composition ever reads them,
    // the same "restore runs before Compose starts" ordering AppState.restoreDiagramTabs already
    // relies on. Every one of the three sites below that reassigns unifiedOrder also mirrors the
    // new value into state.tabOrder — the one-way trip back out, purely so serializeAutosave() has
    // something to persist; state.tabOrder is otherwise never read to drive anything.
    var unifiedOrder by remember { mutableStateOf(state.tabOrder) }

    val stripHeightDp = tabStripHeightDp(state.settings.tabStripStyle)
    val minTabPx = (80 * density).toInt()
    // A vertical strip's overflow button is as tall as a tab; a horizontal one is 40 dp wide.
    val ovBtnPx = if (vertical) (stripHeightDp * density).toInt() else (40 * density).toInt()

    val logTabIds = state.tabs.map { it.id }
    val diagramSessionIds = state.seq3Sessions.sessions.map { it.id }
    val testsOpen = state.testsTabOpen
    // Reconciled only while nothing is mid-drag — mirrors liveVisualTabIds' own dragTabId-gated
    // LaunchedEffect below, so a membership change landing mid-gesture can never clobber the
    // in-flight optimistic reorder.
    LaunchedEffect(logTabIds, diagramSessionIds, testsOpen) {
        if (dragTabId == null) {
            unifiedOrder = reconcileTabOrder(unifiedOrder, logTabIds, diagramSessionIds, testsOpen)
            state.tabOrder = unifiedOrder
        }
    }

    // The order the strip actually draws. The LaunchedEffect above only lands a frame AFTER the store
    // changed, so rendering straight from unifiedOrder would draw one frame without a just-opened tab
    // (an empty slot where a replaced New tab used to be). Reconciling synchronously here, with the
    // same pure function, makes the first frame already right; the effect then writes the identical
    // result back. Mid-drag the in-flight optimistic order is rendered untouched (same gating as the
    // effect), so a membership change landing mid-gesture still can't clobber it.
    val renderOrder = if (dragTabId == null) {
        remember(unifiedOrder, logTabIds, diagramSessionIds, testsOpen) {
            reconcileTabOrder(unifiedOrder, logTabIds, diagramSessionIds, testsOpen)
        }
    } else {
        unifiedOrder
    }

    val visibleTabLimit = state.settings.visibleTabLimit
    val settings = state.settings
    val position = settings.tabStripPosition
    // The widths-differ maths (FIT) also serves a vertical strip, where every tab's length along the strip is one style height.
    val fit = vertical || settings.tabsFitLabel()
    val logById = state.tabs.associateBy { it.id }
    val diagramById = state.seq3Sessions.sessions.associateBy { it.id }

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
    val fitWidthsPx = if (vertical) {
        renderOrder.map { stripHeightDp * density }
    } else if (fit) {
        fitTabWidthsPx(renderOrder.map { ref -> descriptors[ref.rawId()]?.let { tabFitContentDp(it.label, it.appearance) } ?: FIT_TAB_MIN_DP }, density)
    } else {
        emptyList()
    }
    val (visibleRefs, overflowRefs) = remember(renderOrder, containerPx, visibleTabLimit, fit, fitWidthsPx) {
        if (fit) {
            splitTabsForVisibilityFit(
                tabs = renderOrder,
                widthsPx = fitWidthsPx,
                containerPx = containerPx,
                overflowButtonPx = ovBtnPx,
                visibleTabLimit = visibleTabLimit,
            )
        } else {
            splitTabsForVisibility(
                tabs = renderOrder,
                containerPx = containerPx,
                minTabPx = minTabPx,
                overflowButtonPx = ovBtnPx,
                visibleTabLimit = visibleTabLimit,
            )
        }
    }

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
    // Each visible tab's width: an equal share of the strip (FIXED, unchanged), or its label's width, scaled down together when they
    // would not fit (FIT).
    val fitWidthById = if (fit) renderOrder.map { it.rawId() }.zip(fitWidthsPx).toMap() else emptyMap()
    val visibleWidthsPx =
        if (fit) shrinkTabWidthsToFit(visibleTabIds.map { fitWidthById[it] ?: minTabPx.toFloat() }, tabAreaPx.toFloat()) else emptyList()
    val widthById = if (fit) visibleTabIds.zip(visibleWidthsPx).toMap() else emptyMap()
    val visualOrderIds =
        liveVisualTabIds.takeIf { it.toSet() == visibleTabIds.toSet() && it.size == visibleTabIds.size }
            ?: visibleTabIds
    val visualOffsetsPx = if (fit) tabOffsets(visualOrderIds.map { widthById[it] ?: tabWidthPx }) else emptyList()
    val currentVisualOrderIds by rememberUpdatedState(visualOrderIds)
    val currentOverflowTabIds by rememberUpdatedState(overflowTabIds)
    val currentUnifiedOrder by rememberUpdatedState(renderOrder)

    // Commits a drag release: resolves the reordered raw ids back to TabRefs, updates the strip's
    // own unifiedOrder, then partitions and writes BOTH backing stores — state.tabs via the same
    // direct assignment the pre-merge log-tab strip always used, seq3Sessions via its own
    // reorderSessions. The two stores are never otherwise touched by this file.
    fun commitReorder(newVisibleOrder: List<String>, overflowOrder: List<String>) {
        val newRawOrder = tabOrderAfterVisibleReorder(visibleIds = newVisibleOrder, overflowIds = overflowOrder)
        val byRawId = currentUnifiedOrder.associateBy { it.rawId() }
        val newOrder = newRawOrder.mapNotNull(byRawId::get)
        unifiedOrder = newOrder
        state.tabOrder = newOrder
        val (logOrder, diagramOrder) = partitionTabOrder(newOrder)
        state.tabs = logOrder.mapNotNull { id -> state.tabs.find { it.id == id } }
        state.seq3Sessions.reorderSessions(diagramOrder)
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
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull() ?: continue
                            when (ev.type) {
                                PointerEventType.Press -> {
                                    downPos = ch.position; dragging = false
                                    val pointerMain = if (vertical) ch.position.y else ch.position.x
                                    downMain = pointerMain
                                    val idx = if (fit) {
                                        tabIndexAtX(pointerMain, visibleWidthsPx)
                                    } else {
                                        (pointerMain / tabWidthPx).toInt().coerceIn(0, visibleTabIds.lastIndex.coerceAtLeast(0))
                                    }
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
                                        dragOffsetX = (if (vertical) ch.position.y else ch.position.x) - downMain
                                        liveVisualTabIds = if (fit) {
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
                    val targetX = if (fit) visualOffsetsPx.getOrElse(targetIndex) { 0f } else targetIndex * tabWidthPx
                    val animatedX by animateFloatAsState(
                        targetValue = targetX,
                        animationSpec = spring(stiffness = 650f, dampingRatio = 0.86f),
                        label = "tab-x-${ref.rawId()}",
                    )
                    val startIndex = dragStartIndex.takeIf { it >= 0 } ?: targetIndex
                    val startX = if (fit) tabOffsets(visibleWidthsPx).getOrElse(startIndex) { 0f } else startIndex * tabWidthPx
                    val tabX = tabRenderX(
                        isDragging = isDragging,
                        isJustReleased = ref.rawId() == justReleasedTabId,
                        pointerX = startX + dragOffsetX,
                        targetX = targetX,
                        animatedX = animatedX,
                    )
                    val tabLengthDp = ((widthById[ref.rawId()] ?: tabWidthPx) / density).dp
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
                    ) {
                        when (ref) {
                            is TabRef.Log -> logById[ref.tabId]?.let { tab ->
                                val described = descriptors[ref.rawId()]
                                TabItem(
                                    tab = tab,
                                    label = described?.label ?: tabDisplayLabel(tab, state.tabs),
                                    appearance = described?.appearance ?: TabAppearance.Default,
                                    isActive = tab.id == state.activeTabId && !state.diagramSurfaceActive && !state.testsSurfaceActive,
                                    showClose = true,
                                    dragging = isDragging,
                                    onClick = { if (dragTabId == null) state.activateTab(tab.id).also { onReclaimFocus() } },
                                    onClose = { state.closeTab(tab.id).also { onReclaimFocus() } },
                                    onCtxMenu = { x, y -> state.tabCtx = TabCtxMenuState(tab.id, x, y) },
                                )
                            }

                            is TabRef.Diagram -> diagramById[ref.sessionId]?.let { workspace ->
                                val active = state.activeSurface == ActiveSurface.Diagram3(workspace.id)
                                TabShell(
                                    pointerKey = workspace.id,
                                    label = workspace.document.title.ifBlank { "Diagram" },
                                    appearance = descriptors[ref.rawId()]?.appearance ?: TabAppearance.Default,
                                    tooltip = diagramTabTooltip(workspace),
                                    isActive = active,
                                    showClose = true,
                                    dragging = isDragging,
                                    onClick = { if (dragTabId == null) state.seq3Sessions.activate(workspace.id).also { onReclaimFocus() } },
                                    onClose = { state.seq3Sessions.close(workspace.id).also { onReclaimFocus() } },
                                    onCtxMenu = { _, _ -> ctxMenuSessionId = workspace.id },
                                )
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
        if (overflowRefs.isNotEmpty()) {
            val overflowFill = if (vertical) Modifier.fillMaxWidth().height(stripHeightDp.dp) else Modifier.fillMaxHeight()
            Box(overflowFill) {
                ToolbarBtn(
                    "▾ ${overflowRefs.size}",
                    active = overflowOpen,
                    modifier = overflowFill,
                ) { overflowOpen = !overflowOpen }
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
                            Column(Modifier.padding(vertical = 4.dp)) {
                                overflowRefs.forEach { ref ->
                                    val label = descriptors[ref.rawId()]?.label ?: return@forEach
                                    HoverBox(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            // Bring the picked tab into the visible window, mirroring
                                            // AppState.activateOverflowTab's own "select from overflow
                                            // moves it into view" contract for log tabs — diagrams
                                            // have no independent "into view" concept of their own
                                            // (unlike the deleted diagramWorkspaceIdsForWidth sliding
                                            // window), so this strip's own unifiedOrder is what moves.
                                            unifiedOrder = renderOrder.filterNot { it == ref } + ref
                                            state.tabOrder = unifiedOrder
                                            when (ref) {
                                                is TabRef.Log -> state.activateOverflowTab(ref.tabId)
                                                is TabRef.Diagram -> state.seq3Sessions.activate(ref.sessionId)
                                                TabRef.Tests -> state.openTestsTab()
                                            }
                                            dismissOverflow()
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
