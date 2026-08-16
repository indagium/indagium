@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.indagium.diagram3.Seq3ArrowRow
import com.indagium.diagram3.Seq3Box
import com.indagium.diagram3.Seq3BulkAction
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3ElisionRow
import com.indagium.diagram3.Seq3Filter
import com.indagium.diagram3.Seq3FragmentBox
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3Layout
import com.indagium.diagram3.Seq3LifelineColumn
import com.indagium.diagram3.Seq3MessageNoteRow
import com.indagium.diagram3.Seq3NoteBox
import com.indagium.diagram3.Seq3RowGeometry
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.Seq3Selection
import com.indagium.diagram3.Seq3SelfLoopRow
import com.indagium.diagram3.Seq3UnresolvedStubRow
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.seq3Select
import com.indagium.diagram3.seq3MessageIdsAreContiguous
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.awt.Cursor as AwtCursor

// ── The canvas — design spec §04 + §07 ──────────────────────────────────────────────────────────
//
// NATIVE Compose `Canvas` + overlay composables, drawing ONLY from `Seq3RenderCache.layout(
// document)` — the same layout `Seq3Raster.kt` rasterizes for PNG export (this phase's second
// absolute rule). Phase 3's skeleton drew a rasterized AWT bitmap here instead; that bitmap path
// now survives ONLY for headless PNG export (Seq3Raster.kt/Seq3RenderCache.render/display/
// pngBytes) — this file never calls those, and never calls `layoutSeq3` a second time itself.
//
// Pointer input is installed on the scaled-size wrapper, outside the graphicsLayer. Pointer
// positions are therefore physical pixels in the zoomed canvas and must be converted back to the
// layout's own unit-less coordinates before hit-testing.

private const val ZOOM_STEP = 0.1f
private const val MIN_ZOOM = 0.1f
private const val MAX_ZOOM = 4f
private const val MIN_FIT_ZOOM = 0.15f
private const val MAX_FIT_ZOOM = 2.5f
private const val DRAG_CLICK_THRESHOLD_PX = 6f
private const val DOUBLE_CLICK_WINDOW_MS = 350L
private const val PERCENT = 100

@Composable
internal fun Seq3Canvas(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState, modifier: Modifier) {
    val tc = tc()
    val document = session.document
    // Selecting an expanded queue occurrence must be observable even when that message is
    // currently collapsed on the canvas. This is a view-only expansion: the saved document keeps
    // its repeat policy, while the selected occurrence gets a real row to emphasize temporarily.
    val layoutDocument = remember(
        document,
        view.selectedOccurrenceMessageId,
        view.selectedOccurrenceEntryId,
        view.selectedCanvasRows,
    ) {
        seq3CanvasDocumentForSelection(
            document,
            view.selectedOccurrenceMessageId,
            view.selectedOccurrenceEntryId,
            view.selectedCanvasRows,
        )
    }
    val layout = remember(layoutDocument) { Seq3RenderCache.layout(layoutDocument) }
    Column(modifier.fillMaxSize().background(tc.bg)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (layout.lifelines.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AppText(if (session.generating) "Generating…" else "No lifelines to diagram", color = tc.ts, fontSize = 12.sp)
                }
            } else {
                Seq3CanvasContent(state, session, view, document, layout)
            }
        }
        Seq3CanvasStatusBar(state, session, document)
    }
}

private fun seq3CanvasDocumentForSelection(
    document: Seq3Document,
    selectedMessageId: String?,
    selectedEntryId: Int?,
    selectedCanvasRows: Set<Seq3CanvasRowRef>,
): Seq3Document {
    val selectedOccurrenceIds = buildSet {
        if (selectedMessageId != null && selectedEntryId != null) add(selectedMessageId to selectedEntryId)
        selectedCanvasRows.forEach { row ->
            row.occurrenceEntryId?.let { add(row.messageId to it) }
        }
    }
    if (selectedOccurrenceIds.isEmpty()) return document
    val expandableIds = selectedOccurrenceIds.map { it.first }.toSet()
    return document.copy(
        messages = document.messages.map { current ->
            if (current.id in expandableIds &&
                current.occurrences.size > 1 &&
                current.kind != Seq3Kind.NOTE &&
                current.toLifelineId != null &&
                current.repeat != Seq3Repeat.EVERY
            ) {
                current.copy(repeat = Seq3Repeat.EVERY)
            } else {
                current
            }
        },
    )
}

@Composable
internal fun Seq3CanvasZoomToolbarControls(view: Seq3ViewState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Seq3ZoomControls(view)
        SegmentedControl(
            options = listOf("Fit height", "Fit width", "Reset"),
            selectedIndices = when (view.zoomMode) {
                Seq3ZoomMode.FIT -> setOf(0)
                Seq3ZoomMode.FIT_WIDTH -> setOf(1)
                Seq3ZoomMode.MANUAL -> emptySet()
            },
            onToggle = { index ->
                when (index) {
                    0 -> view.zoomMode = Seq3ZoomMode.FIT
                    1 -> view.zoomMode = Seq3ZoomMode.FIT_WIDTH
                    else -> {
                        view.zoom = 1f
                        view.zoomMode = Seq3ZoomMode.MANUAL
                    }
                }
            },
        )
    }
}

@Composable
private fun Seq3ZoomControls(view: Seq3ViewState) {
    val tc = tc()
    Row(
        modifier = Modifier
            .height(28.dp)
            .border(0.5.dp, tc.br, CORNER_MD)
            .clip(CORNER_MD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Seq3ZoomStepButton("−") {
            view.zoom = (view.zoom - ZOOM_STEP).coerceAtLeast(MIN_ZOOM)
            view.zoomMode = Seq3ZoomMode.MANUAL
        }
        Box(
            Modifier.width(42.dp).height(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            AppText(seq3ZoomPercentLabel(view.zoom), color = tc.ts, fontSize = 11.sp)
        }
        Seq3ZoomStepButton("+") {
            view.zoom = (view.zoom + ZOOM_STEP).coerceAtMost(MAX_ZOOM)
            view.zoomMode = Seq3ZoomMode.MANUAL
        }
    }
}

@Composable
private fun Seq3ZoomStepButton(label: String, onClick: () -> Unit) {
    val tc = tc()
    ToolbarBtn(
        label = label,
        modifier = Modifier.size(28.dp)
            .pointerHoverIcon(PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)), overrideDescendants = true),
        shape = CORNER_SM,
        contentPadding = PaddingValues(0.dp),
        baseBg = tc.p2,
        onClick = onClick,
    )
}

@Composable
private fun Seq3CanvasStatusBar(state: AppState, session: Seq3WorkspaceSession, document: Seq3Document) {
    val tc = tc()
    val scanned = state.seq3Sessions.scannedEntryCount(session.id)
    val shown = document.messages.count { it.visibility == Seq3Visibility.VISIBLE }
    val hidden = document.messages.size - shown
    Row(
        Modifier.fillMaxWidth().background(tc.p).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(
            "$shown shown · $scanned scanned · ${document.lifelines.size} lifelines · $hidden hidden",
            color = tc.ts, fontSize = 10.sp,
        )
        AppText(draftStatusLabel(session), color = tc.ts, fontSize = 10.sp)
    }
}

// ── Scrollable, zoomable, pointer-interactive content ──────────────────────────────────────────

@Composable
private fun Seq3CanvasContent(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState, document: Seq3Document, layout: Seq3Layout) {
    val tc = tc()
    val density = LocalDensity.current.density
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    // Live cursor position + candidate lifeline while an arrow-endpoint drag is in progress (item
    // 13, phase-5 post-ship plan) — hoisted here rather than kept inside
    // [seq3CanvasGestureModifier] because the DRAW code below (drawSeq3Diagram) needs to read it
    // every frame, and that modifier function has no other output channel besides the Modifier it
    // returns. `remember(session.id)` so switching to a different open v3 workspace never carries a
    // stale preview over.
    var dragPreview by remember(session.id) { mutableStateOf<Seq3EndpointDragPreview?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportWidth = maxWidth.value.toDouble()
        val viewportHeight = maxHeight.value.toDouble()
        LaunchedEffect(layout, viewportWidth, viewportHeight, view.zoomMode) {
            if (viewportWidth <= 0.0 || viewportHeight <= 0.0) return@LaunchedEffect
            val target = when (view.zoomMode) {
                Seq3ZoomMode.FIT -> seq3FitHeightZoom(layout.height, viewportHeight)
                Seq3ZoomMode.FIT_WIDTH -> seq3FitWidthZoom(layout.width, viewportWidth)
                Seq3ZoomMode.MANUAL -> null
            }
            if (target != null) view.zoom = target
        }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().horizontalScroll(hScroll).verticalScroll(vScroll)) {
                val zoom = view.zoom
                Box(
                    Modifier
                        .size((layout.width * zoom).dp, (layout.height * zoom).dp)
                        .then(
                            seq3CanvasGestureModifier(state, session, view, layout, density, hScroll, vScroll) {
                                dragPreview = it
                            },
                        ),
                ) {
                    Box(
                        Modifier
                            .size(layout.width.dp, layout.height.dp)
                            .graphicsLayer(scaleX = zoom, scaleY = zoom, transformOrigin = TransformOrigin(0f, 0f)),
                    ) {
                        Canvas(Modifier.size(layout.width.dp, layout.height.dp)) {
                            drawSeq3Diagram(
                                layout,
                                tc,
                                view.hoveredMessageId,
                                view.selection.selectedIds,
                                view.inspectorMessageId,
                                view.selectedOccurrenceMessageId,
                                view.selectedOccurrenceEntryId,
                                view.selectedCanvasRows,
                                view.canvasSelectionRect,
                                dragPreview,
                            )
                        }
                        layout.fragments.forEach { fragment -> Seq3FragmentLabelOverlay(fragment) }
                        layout.rows.forEach { row -> Seq3RowOverlay(state, session, view, row) }
                        layout.notes.forEach { note -> Seq3NoteTextOverlay(state, session, view, note) }
                        layout.lifelines.forEach { column -> Seq3LifelineChip(state, session, view, document, layout, column, density) }
                    }
                }
            }
            // Same ScrollStates the scroll modifiers and the pan-drag gesture (item 12) both drive,
            // so wheel scroll, scrollbar drag, and pan-drag can never disagree about position — same
            // shared-style helper ui/LogViewer.kt already uses for its own scrollbars.
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(vScroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style = appScrollbarStyle(tc),
            )
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(hScroll),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                style = appScrollbarStyle(tc),
            )
            if (view.canvasContextMenuMessageId != null) {
                Seq3CanvasContextMenu(state, session, view, document)
            }
        }
    }
}

// Session/tab id is folded into every remembered/pointerInput key above (session.id via the
// composables' own `session` param) — see CLAUDE.md's "ID collisions across tabs" gotcha: without
// it, two open v3 workspaces would share pointerInput coroutines keyed only by `document`/`layout`
// identity, which can collide once two documents happen to structurally `equals()` each other.
@Composable
private fun seq3CanvasGestureModifier(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    layout: Seq3Layout,
    density: Float,
    hScroll: ScrollState,
    vScroll: ScrollState,
    onDragPreview: (Seq3EndpointDragPreview?) -> Unit,
): Modifier {
    // Keep the pointer coroutine alive while the document/layout recomposes. A drop command changes
    // both values synchronously; keying the coroutine by them can cancel the release before it
    // clears the transient drag preview and makes the next gesture start in a stale state.
    val currentState = rememberUpdatedState(state)
    val currentSession = rememberUpdatedState(session)
    val currentView = rememberUpdatedState(view)
    val currentLayout = rememberUpdatedState(layout)
    val currentDensity = rememberUpdatedState(density)
    val currentHScroll = rememberUpdatedState(hScroll)
    val currentVScroll = rememberUpdatedState(vScroll)
    val currentDragPreviewCallback = rememberUpdatedState(onDragPreview)

    return Modifier.pointerInput(session.id) {
        awaitPointerEventScope {
            var dragEndpoint: Seq3DragEndpoint? = null
            var downPosition: Offset? = null
            var moved = false
            var lastClickTimeMs = 0L
            var lastClickMessageId: String? = null
            // Drag-to-pan (item 12, phase-5 post-ship plan). Middle-button drag always pans. A
            // primary drag on empty background creates the requested marquee selection. Overlay
            // bounds are excluded before this gesture is armed, so dragging a note or lifeline
            // cannot also paint a selection rectangle. The pan anchor is kept in viewport pixels,
            // not the diagram node's local pixels: scrolling moves that node under a stationary
            // cursor, so raw local deltas would alternate direction and make the whole diagram
            // flicker.
            var panning = false
            var pressWasMiddle = false
            var lastPanPosition: Offset? = null
            var selectingArea = false
            var selectionStart: Offset? = null
            var selectionAdditive = false
            var selectionRange = false
            var childOwnsGesture = false
            fun panViewportPosition(position: Offset): Offset = Offset(
                position.x - currentHScroll.value.value,
                position.y - currentVScroll.value.value,
            )
            while (true) {
                // Let interactive overlays (lifeline chips, the inline label editor, and its
                // buttons) see the press before the canvas decides to own it. The old Initial-pass
                // handler consumed every primary press before those children could recognize a
                // tap or focus a text field, which made click selection and double-click editing
                // look broken while drag-to-reorder still appeared to work.
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull() ?: continue
                val activeLayout = currentLayout.value
                val activeDensity = currentDensity.value
                // Pointer positions are physical pixels on the zoomed wrapper (CLAUDE.md's own
                // gotcha) — remove both density and zoom to get layout coordinates (1 unit == 1 dp).
                val activeZoom = currentView.value.zoom
                val xUnits = seq3PointerPxToLayoutUnits(change.position.x, activeDensity, activeZoom)
                val yUnits = seq3PointerPxToLayoutUnits(change.position.y, activeDensity, activeZoom)
                when (event.type) {
                    PointerEventType.Press -> {
                        val isMiddle = event.buttons.isTertiaryPressed
                        if (event.buttons.isSecondaryPressed) {
                            if (!change.isConsumed) {
                                val hitRow = seq3RowAt(activeLayout, xUnits, yUnits)
                                if (hitRow != null) {
                                    val modifiers = event.keyboardModifiers
                                    val additive = modifiers.isCtrlPressed || modifiers.isMetaPressed
                                    val document = currentSession.value.document
                                    if (hitRow.messageId !in view.selection.selectedIds || additive || modifiers.isShiftPressed) {
                                        view.selection = seq3Select(
                                            document.messages.map { it.id },
                                            view.selection,
                                            hitRow.messageId,
                                            additive = additive,
                                            range = modifiers.isShiftPressed,
                                        )
                                    }
                                    view.selectionFromMarquee = false
                                    view.selectedCanvasRows = emptySet()
                                    view.inspectorMessageId = hitRow.messageId
                                    view.canvasContextMenuMessageId = hitRow.messageId
                                    view.canvasContextMenuCanvasPoint = Seq3Box(xUnits, yUnits, 0.0, 0.0)
                                    view.canvasContextMenuOffset = IntOffset(
                                        change.position.x.roundToInt().coerceAtLeast(0),
                                        change.position.y.roundToInt().coerceAtLeast(0),
                                    )
                                    change.consume()
                                }
                            }
                            childOwnsGesture = true
                            continue
                        }
                        if (event.buttons.isPrimaryPressed || isMiddle) {
                            if (change.isConsumed) {
                                // A child owns this gesture (for example a lifeline chip or an
                                // already-open text editor). Keep the canvas completely out of its
                                // move/release sequence.
                                childOwnsGesture = true
                                dragEndpoint = null
                                downPosition = null
                                moved = false
                                panning = false
                                pressWasMiddle = false
                                selectingArea = false
                                selectionStart = null
                                continue
                            }
                            childOwnsGesture = false
                            view.canvasContextMenuMessageId = null
                            downPosition = change.position
                            moved = false
                            pressWasMiddle = isMiddle
                            val modifiers = event.keyboardModifiers
                            selectionAdditive = modifiers.isCtrlPressed || modifiers.isMetaPressed
                            selectionRange = modifiers.isShiftPressed
                            dragEndpoint = if (isMiddle) null else seq3ResolveDragEndpoint(activeLayout, xUnits, yUnits)
                            val emptyBackground = seq3IsEmptyCanvasBackground(activeLayout, xUnits, yUnits)
                            if (!isMiddle && dragEndpoint == null && emptyBackground) {
                                selectingArea = true
                                selectionStart = Offset(xUnits.toFloat(), yUnits.toFloat())
                                view.canvasSelectionRect = Seq3Box(xUnits, yUnits, 0.0, 0.0)
                            }
                            // The surrounding scroll modifiers must not take ownership of a
                            // primary drag before this canvas has decided whether it is an
                            // endpoint drag, a background pan, or a click. If an endpoint hit is
                            // missed at a low zoom, competing scroll gestures move the coordinate
                            // basis underneath the pointer and make the diagram visibly jump.
                            if (!isMiddle) change.consume()
                            when {
                                dragEndpoint != null -> {
                                    currentDragPreviewCallback.value(
                                        seq3DragPreview(activeLayout, dragEndpoint, xUnits),
                                    )
                                    change.consume()
                                }
                                isMiddle -> {
                                    panning = true
                                    lastPanPosition = panViewportPosition(change.position)
                                    change.consume()
                                }
                            }
                        }
                    }
                    PointerEventType.Move -> {
                        if (childOwnsGesture) continue
                        val down = downPosition
                        if (down != null && (change.position - down).getDistance() > DRAG_CLICK_THRESHOLD_PX) moved = true
                        val drag = dragEndpoint
                        when {
                            drag != null -> {
                                change.consume()
                                currentDragPreviewCallback.value(seq3DragPreview(activeLayout, drag, xUnits))
                            }
                            panning -> {
                                val last = lastPanPosition
                                if (last != null) {
                                    val current = panViewportPosition(change.position)
                                    currentHScroll.value.dispatchRawDelta(-(current.x - last.x))
                                    currentVScroll.value.dispatchRawDelta(-(current.y - last.y))
                                    lastPanPosition = current
                                }
                                change.consume()
                            }
                            selectingArea -> {
                                val start = selectionStart ?: Offset(xUnits.toFloat(), yUnits.toFloat())
                                view.canvasSelectionRect = seq3SelectionRect(start.x.toDouble(), start.y.toDouble(), xUnits, yUnits)
                                change.consume()
                            }
                            downPosition == null -> view.hoveredMessageId = seq3RowAt(activeLayout, xUnits, yUnits)?.messageId
                        }
                    }
                    PointerEventType.Release -> {
                        if (childOwnsGesture) {
                            childOwnsGesture = false
                            continue
                        }
                        val drag = dragEndpoint
                        when {
                            drag != null -> {
                                // Clear transient UI state before the command can trigger a
                                // document/layout recomposition and invalidate this pointer pass.
                                currentDragPreviewCallback.value(null)
                                dragEndpoint = null
                                seq3NearestLifelineId(activeLayout, xUnits)?.let { targetLifelineId ->
                                    val action = if (drag.side == Seq3EndpointSide.FROM) {
                                        Seq3BulkAction.SetFrom(targetLifelineId)
                                    } else {
                                        Seq3BulkAction.SetTo(targetLifelineId)
                                    }
                                    currentState.value.seq3Sessions.applyCommand(
                                        currentSession.value.id,
                                        Seq3Command.Bulk(setOf(drag.messageId), action),
                                    )
                                }
                            }
                            panning -> {
                                val wasMoved = moved
                                panning = false
                                lastPanPosition = null
                                if (!wasMoved) seq3ClearSelection(view)
                            }
                            selectingArea -> {
                                val start = selectionStart
                                val rect = start?.let { seq3SelectionRect(it.x.toDouble(), it.y.toDouble(), xUnits, yUnits) }
                                val selectedRows = rect?.let { seq3RowRefsInSelection(activeLayout, it) }.orEmpty()
                                val ids = selectedRows.mapTo(linkedSetOf()) { it.messageId }
                                view.canvasSelectionRect = null
                                selectingArea = false
                                selectionStart = null
                                if (ids.isNotEmpty()) {
                                    view.selection = if (selectionAdditive || selectionRange) {
                                        Seq3Selection(view.selection.selectedIds + ids, ids.first())
                                    } else {
                                        Seq3Selection(ids, ids.first())
                                    }
                                    view.selectedCanvasRows = if (selectionAdditive || selectionRange) {
                                        view.selectedCanvasRows + selectedRows
                                    } else {
                                        selectedRows.toSet()
                                    }
                                    view.selectionFromMarquee = true
                                    view.selectedOccurrenceIds = emptySet()
                                    view.selectedOccurrenceMessageId = null
                                    view.selectedOccurrenceEntryId = null
                                } else if (!moved) {
                                    seq3ClearSelection(view)
                                }
                            }
                            !moved && !pressWasMiddle -> {
                                val hitRow = seq3RowAt(activeLayout, xUnits, yUnits)
                                if (hitRow != null) seq3HandleCanvasRowClick(
                                    view,
                                    currentSession.value.document,
                                    hitRow,
                                    selectionAdditive,
                                    selectionRange,
                                ) { now, id ->
                                    val doubleClick = now - lastClickTimeMs <= DOUBLE_CLICK_WINDOW_MS && lastClickMessageId == id
                                    lastClickTimeMs = now
                                    lastClickMessageId = id
                                    doubleClick
                                }
                            }
                        }
                        downPosition = null
                        pressWasMiddle = false
                        selectionAdditive = false
                        selectionRange = false
                    }
                    PointerEventType.Exit -> view.hoveredMessageId = null
                    else -> Unit
                }
            }
        }
    }
}

private fun seq3HandleCanvasRowClick(
    view: Seq3ViewState,
    document: Seq3Document,
    hitRow: Seq3RowGeometry,
    additive: Boolean,
    range: Boolean,
    registerClick: (nowMs: Long, id: String) -> Boolean,
) {
    // A click always resolves the row into view even when the current queue filter/text hides it
    // (spec §04) — reset the view here, BEFORE requesting the scroll, so Seq3QueuePanel's effect
    // finds the row on the very next recomposition.
    view.filter = Seq3Filter.ALL
    view.textFilter = ""
    view.selection = seq3Select(
        document.messages.map { it.id },
        view.selection,
        hitRow.messageId,
        additive = additive,
        range = range,
    )
    view.selectionFromMarquee = false
    view.selectedCanvasRows = emptySet()
    val message = document.messages.firstOrNull { it.id == hitRow.messageId }
    val occurrenceEntryId = hitRow.occurrenceEntryId
    if (message != null && occurrenceEntryId != null && message.occurrences.size > 1) {
        val occurrenceKey = "${message.id}::${occurrenceEntryId}"
        view.selectedOccurrenceIds = if (additive) {
            view.selectedOccurrenceIds + occurrenceKey
        } else {
            setOf(occurrenceKey)
        }
        view.selectedOccurrenceMessageId = message.id
        view.selectedOccurrenceEntryId = occurrenceEntryId
        if (message.occurrences.size > 1) {
            view.expandedOccurrenceMessageIds = view.expandedOccurrenceMessageIds + message.id
        }
    } else {
        view.selectedOccurrenceIds = emptySet()
        view.selectedOccurrenceMessageId = null
        view.selectedOccurrenceEntryId = null
    }
    view.inspectorMessageId = hitRow.messageId
    view.scrollRequestId = hitRow.messageId
    val doubleClick = registerClick(System.currentTimeMillis(), hitRow.messageId)
    view.editingLabelMessageId = null
    view.editingLabelOccurrenceEntryId = null
    if (doubleClick && hitRow is Seq3ArrowRow) {
        view.editingLabelMessageId = hitRow.messageId
        view.editingLabelOccurrenceEntryId = hitRow.occurrenceEntryId
    }
}

internal fun seq3SelectionRect(startX: Double, startY: Double, endX: Double, endY: Double): Seq3Box = Seq3Box(
    x = min(startX, endX),
    y = min(startY, endY),
    width = kotlin.math.abs(endX - startX),
    height = kotlin.math.abs(endY - startY),
)

internal fun seq3RowsInSelection(layout: Seq3Layout, selection: Seq3Box): Set<String> = layout.rows
    .filter { row -> seq3RowBounds(row).let { bounds -> boxesIntersect(bounds, selection) } }
    .mapTo(linkedSetOf()) { it.messageId }

internal fun seq3RowRefsInSelection(layout: Seq3Layout, selection: Seq3Box): List<Seq3CanvasRowRef> = layout.rows
    .filter { row -> row !is Seq3ElisionRow && seq3RowBounds(row).let { bounds -> boxesIntersect(bounds, selection) } }
    .map { row -> Seq3CanvasRowRef(row.messageId, row.occurrenceEntryId) }

private fun seq3RowBounds(row: Seq3RowGeometry): Seq3Box = when (row) {
    is Seq3ArrowRow -> Seq3Box(
        min(row.fromX, row.toX),
        row.y - SEQ3_ROW_HIT_Y_TOLERANCE,
        max(row.toX, row.fromX) - min(row.fromX, row.toX),
        SEQ3_ROW_HIT_Y_TOLERANCE * 2,
    )
    is Seq3SelfLoopRow -> Seq3Box(row.x, row.y - SEQ3_ROW_HIT_Y_TOLERANCE, row.loopWidth, SEQ3_ROW_HIT_Y_TOLERANCE * 2)
    is Seq3UnresolvedStubRow -> Seq3Box(
        min(row.fromX, row.stubEndX),
        row.y - SEQ3_ROW_HIT_Y_TOLERANCE,
        max(row.stubEndX, row.fromX) - min(row.fromX, row.stubEndX),
        SEQ3_ROW_HIT_Y_TOLERANCE * 2,
    )
    is Seq3MessageNoteRow -> row.box
    is Seq3ElisionRow -> row.box
}

private fun boxesIntersect(a: Seq3Box, b: Seq3Box): Boolean =
    a.x <= b.x + b.width && a.x + a.width >= b.x &&
        a.y <= b.y + b.height && a.y + a.height >= b.y

// ── Line/shape drawing — everything from `Seq3Layout`'s own unit-less coordinates ─────────────

private fun DrawScope.drawSeq3Diagram(
    layout: Seq3Layout,
    tc: ThemeColors,
    hoveredMessageId: String?,
    selectedIds: Set<String>,
    focusedMessageId: String?,
    selectedOccurrenceMessageId: String?,
    selectedOccurrenceEntryId: Int?,
    selectedCanvasRows: Set<Seq3CanvasRowRef>,
    selectionRect: Seq3Box? = null,
    dragPreview: Seq3EndpointDragPreview? = null,
) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
    layout.fragments.forEach { fragment ->
        val topLeft = Offset(fragment.box.x.dp.toPx(), fragment.box.y.dp.toPx())
        val size = Size(fragment.box.width.dp.toPx(), fragment.box.height.dp.toPx())
        drawRect(color = tc.seq1.copy(alpha = FRAGMENT_WASH_ALPHA), topLeft = topLeft, size = size)
        drawRect(color = tc.seq1, topLeft = topLeft, size = size, style = Stroke(width = 1.dp.toPx()))
    }
    layout.notes.forEach { note ->
        val topLeft = Offset(note.box.x.dp.toPx(), note.box.y.dp.toPx())
        val size = Size(note.box.width.dp.toPx(), note.box.height.dp.toPx())
        drawRect(color = tc.seq2.copy(alpha = NOTE_FILL_ALPHA), topLeft = topLeft, size = size)
        drawRect(color = tc.seq2, topLeft = topLeft, size = size, style = Stroke(width = 1.dp.toPx()))
    }
    layout.lifelines.forEach { column ->
        drawLine(
            color = tc.td,
            start = Offset(column.centerX.dp.toPx(), column.lifelineTop.dp.toPx()),
            end = Offset(column.centerX.dp.toPx(), column.lifelineBottom.dp.toPx()),
            strokeWidth = 1.dp.toPx(), pathEffect = dash,
        )
    }
    layout.rows.forEach { row ->
        val draggingMessage = dragPreview?.messageId == row.messageId
        val draggingOccurrenceEntryId = dragPreview?.occurrenceEntryId
        val draggingRow = draggingMessage && (draggingOccurrenceEntryId == null || draggingOccurrenceEntryId == row.occurrenceEntryId)
        val emphasized = seq3RowIsEmphasized(
            row,
            hoveredMessageId,
            selectedIds,
            focusedMessageId,
            selectedOccurrenceMessageId,
            selectedOccurrenceEntryId,
            selectedCanvasRows,
        )
        val arrowColor = when {
            draggingRow -> tc.ac
            draggingMessage -> tc.ts.copy(alpha = 0.45f)
            emphasized -> tc.ac
            else -> tc.ts
        }
        when (row) {
            is Seq3ArrowRow -> if (!draggingRow) {
                drawSeq3Arrow(
                    row,
                    arrowColor,
                    if (emphasized) 2.dp.toPx() else 1.5.dp.toPx(),
                )
            }
            is Seq3SelfLoopRow -> if (!draggingRow) drawSeq3SelfLoop(row, arrowColor)
            is Seq3UnresolvedStubRow -> if (!draggingRow) {
                drawLine(
                    color = tc.warn,
                    start = Offset(row.fromX.dp.toPx(), row.y.dp.toPx()),
                    end = Offset(row.stubEndX.dp.toPx(), row.y.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                )
            }
            is Seq3MessageNoteRow -> {
                val topLeft = Offset(row.box.x.dp.toPx(), row.box.y.dp.toPx())
                val size = Size(row.box.width.dp.toPx(), row.box.height.dp.toPx())
                drawRect(color = tc.seq2.copy(alpha = NOTE_FILL_ALPHA), topLeft = topLeft, size = size)
                drawRect(color = tc.seq2, topLeft = topLeft, size = size, style = Stroke(width = 1.dp.toPx()))
            }
            is Seq3ElisionRow -> Unit // text-only marker, drawn by the overlay composable
        }
    }
    // Live feedback while an arrow-endpoint drag is in progress (item 13, phase-5 post-ship plan) —
    // drawn LAST so it sits on top of every row it might cross. The dragged row is omitted above,
    // so this is the one visible line for that message and it follows the cursor in either
    // direction instead of leaving a stationary dotted copy at the old endpoint.
    if (dragPreview != null) {
        dragPreview.candidateLifelineId
            ?.let { id -> layout.lifelines.firstOrNull { it.lifelineId == id } }
            ?.let { column ->
                val half = column.header.width / 2
                drawRect(
                    color = tc.ac.copy(alpha = LIFELINE_CANDIDATE_WASH_ALPHA),
                    topLeft = Offset((column.centerX - half).dp.toPx(), column.lifelineTop.dp.toPx()),
                    size = Size(column.header.width.dp.toPx(), (column.lifelineBottom - column.lifelineTop).dp.toPx()),
                )
            }
        drawSeq3DragPreview(dragPreview, tc.ac, strokeWidth = 2.dp.toPx())
    }
    selectionRect?.let { rect ->
        drawRect(
            color = tc.ac.copy(alpha = 0.12f),
            topLeft = Offset(rect.x.dp.toPx(), rect.y.dp.toPx()),
            size = Size(rect.width.dp.toPx(), rect.height.dp.toPx()),
        )
        drawRect(
            color = tc.ac,
            topLeft = Offset(rect.x.dp.toPx(), rect.y.dp.toPx()),
            size = Size(rect.width.dp.toPx(), rect.height.dp.toPx()),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

private const val LIFELINE_CANDIDATE_WASH_ALPHA = 0.12f

private const val FRAGMENT_WASH_ALPHA = 0.07f
private const val NOTE_FILL_ALPHA = 0.20f
private const val ARROWHEAD_LEN_DP = 8f
private const val ARROWHEAD_HALF_DP = 4f

private fun DrawScope.drawSeq3Arrow(row: Seq3ArrowRow, color: Color, strokeWidth: Float) {
    val start = Offset(row.fromX.dp.toPx(), row.y.dp.toPx())
    val end = Offset(row.toX.dp.toPx(), row.y.dp.toPx())
    drawLine(color, start, end, strokeWidth = strokeWidth)
    val dir = if (row.toX >= row.fromX) 1f else -1f
    val headLen = ARROWHEAD_LEN_DP.dp.toPx()
    val headHalf = ARROWHEAD_HALF_DP.dp.toPx()
    val backX = end.x - dir * headLen
    val head = Path().apply {
        moveTo(end.x, end.y)
        lineTo(backX, end.y - headHalf)
        lineTo(backX, end.y + headHalf)
        close()
    }
    drawPath(head, color)
}

private fun DrawScope.drawSeq3DragPreview(
    preview: Seq3EndpointDragPreview,
    color: Color,
    strokeWidth: Float,
) {
    val fixedX = preview.anchorX.dp.toPx()
    val movingX = preview.cursorX.dp.toPx()
    val y = preview.y.dp.toPx()
    val startX = if (preview.side == Seq3EndpointSide.FROM) movingX else fixedX
    val endX = if (preview.side == Seq3EndpointSide.FROM) fixedX else movingX
    drawLine(color, Offset(startX, y), Offset(endX, y), strokeWidth = strokeWidth)

    // The arrowhead belongs to the target end. When FROM moves, the fixed end is the target;
    // when TO moves, the cursor is the target. This keeps the preview a normal arrow even when
    // the cursor crosses the fixed endpoint and reverses its horizontal direction.
    val direction = if (endX >= startX) 1f else -1f
    val headLen = ARROWHEAD_LEN_DP.dp.toPx()
    val headHalf = ARROWHEAD_HALF_DP.dp.toPx()
    val backX = endX - direction * headLen
    val head = Path().apply {
        moveTo(endX, y)
        lineTo(backX, y - headHalf)
        lineTo(backX, y + headHalf)
        close()
    }
    drawPath(head, color)
}

private fun DrawScope.drawSeq3SelfLoop(row: Seq3SelfLoopRow, color: Color) {
    val x = row.x.dp.toPx()
    val yTop = row.y.dp.toPx()
    val yBottom = row.loopBottomY.dp.toPx()
    val loopW = row.loopWidth.dp.toPx()
    val loop = Path().apply {
        moveTo(x, yTop)
        lineTo(x + loopW, yTop)
        lineTo(x + loopW, yBottom)
        lineTo(x, yBottom)
    }
    drawPath(loop, color, style = Stroke(width = 1.5.dp.toPx()))
    val headLen = ARROWHEAD_LEN_DP.dp.toPx()
    val headHalf = ARROWHEAD_HALF_DP.dp.toPx()
    val head = Path().apply {
        moveTo(x, yBottom)
        lineTo(x + headLen, yBottom - headHalf)
        lineTo(x + headLen, yBottom + headHalf)
        close()
    }
    drawPath(head, color)
}

// ── Text/badge/pill overlay composables — positioned from Seq3Layout's own boxes ──────────────

@Composable
private fun Seq3RowOverlay(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState, row: Seq3RowGeometry) {
    val tc = tc()
    val emphasized = seq3RowIsEmphasized(
        row,
        view.hoveredMessageId,
        view.selection.selectedIds,
        view.inspectorMessageId,
        view.selectedOccurrenceMessageId,
        view.selectedOccurrenceEntryId,
        view.selectedCanvasRows,
    )
    val labelColor = if (emphasized) tc.ac else tc.tx
    when (row) {
        is Seq3ArrowRow -> {
            Seq3LabelText(row.labelBox, row.label, labelColor)
            row.badgeBox?.let { Seq3BadgeChip(it, row.repeatCount) }
            Seq3InlineLabelEditorIfNeeded(state, session, view, row, row.label, row.labelBox)
        }
        is Seq3SelfLoopRow -> {
            Seq3LabelText(row.labelBox, row.label, labelColor)
            row.badgeBox?.let { Seq3BadgeChip(it, row.repeatCount) }
            Seq3InlineLabelEditorIfNeeded(state, session, view, row, row.label, row.labelBox)
        }
        is Seq3UnresolvedStubRow -> {
            Seq3LabelText(row.labelBox, row.label, tc.warn)
            Box(
                Modifier.offset(row.dropPill.x.dp, row.dropPill.y.dp).size(row.dropPill.width.dp, row.dropPill.height.dp)
                    .background(tc.warnBg, RoundedCornerShape(50)).border(1.dp, tc.warn, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) { AppText("drop on a lifeline", color = tc.warn, fontSize = 9.sp, maxLines = 1) }
            Seq3InlineLabelEditorIfNeeded(state, session, view, row, row.label, row.labelBox)
        }
        is Seq3MessageNoteRow -> {
            Box(Modifier.offset(row.box.x.dp, row.box.y.dp).size(row.box.width.dp, row.box.height.dp).padding(3.dp)) {
                Column { row.lines.forEach { line -> AppText(line, color = tc.tx, fontSize = 10.sp, maxLines = 1) } }
            }
            Seq3InlineLabelEditorIfNeeded(state, session, view, row, row.lines.joinToString(" "), row.box)
        }
        is Seq3ElisionRow -> Box(
            Modifier.offset(row.box.x.dp, row.box.y.dp).size(row.box.width.dp, row.box.height.dp),
            contentAlignment = Alignment.Center,
        ) { AppText("⋮ ×${row.elidedCount}", color = tc.td, fontSize = 9.sp) }
    }
}

@Composable
private fun Seq3InlineLabelEditorIfNeeded(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    row: Seq3RowGeometry,
    renderedLabel: String,
    box: Seq3Box,
) {
    if (view.editingLabelMessageId != row.messageId ||
        (view.editingLabelOccurrenceEntryId != null && view.editingLabelOccurrenceEntryId != row.occurrenceEntryId)
    ) return
    // A row label can be a rendered occurrence (for example, `vendorId=04E8`), while SetLabel
    // edits the shared message template. Seed the editor from that template so saving a rename
    // cannot replace capture slots with one occurrence's concrete values for every sibling row.
    val labelTemplate = session.document.messages
        .firstOrNull { it.id == row.messageId }
        ?.labelTemplate
        ?: renderedLabel
    Seq3InlineLabelEditor(
        state,
        session,
        view,
        row.messageId,
        row.occurrenceEntryId,
        labelTemplate,
        box,
    )
}

internal fun seq3RowIsEmphasized(
    row: Seq3RowGeometry,
    hoveredMessageId: String?,
    selectedIds: Set<String>,
    focusedMessageId: String?,
    selectedOccurrenceMessageId: String?,
    selectedOccurrenceEntryId: Int?,
    selectedCanvasRows: Set<Seq3CanvasRowRef> = emptySet(),
): Boolean {
    if (row.messageId == hoveredMessageId) return true
    if (selectedCanvasRows.isNotEmpty()) {
        return Seq3CanvasRowRef(row.messageId, row.occurrenceEntryId) in selectedCanvasRows
    }
    if (selectedOccurrenceMessageId != null) {
        return row.messageId == selectedOccurrenceMessageId && row.occurrenceEntryId == selectedOccurrenceEntryId
    }
    return row.messageId in selectedIds || row.messageId == focusedMessageId
}

@Composable
private fun Seq3LabelText(box: Seq3Box, text: String, color: Color) {
    if (text.isEmpty()) return
    Box(Modifier.offset(box.x.dp, box.y.dp).size(box.width.dp, box.height.dp), contentAlignment = Alignment.CenterStart) {
        AppText(text, color = color, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun Seq3BadgeChip(box: Seq3Box, count: Int) {
    val tc = tc()
    Box(
        Modifier.offset(box.x.dp, box.y.dp).size(box.width.dp, box.height.dp)
            .background(tc.p2, RoundedCornerShape(3.dp)).border(1.dp, tc.br, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) { AppText("×$count", color = tc.ts, fontSize = 9.sp) }
}

@Composable
private fun Seq3FragmentLabelOverlay(fragment: Seq3FragmentBox) {
    val tc = tc()
    Box(Modifier.offset(fragment.box.x.dp, fragment.box.y.dp).padding(3.dp)) {
        AppText(fragment.label, color = tc.seq1, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Seq3CanvasContextMenu(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    document: Seq3Document,
) {
    val messageId = view.canvasContextMenuMessageId ?: return
    val selectedIds = seq3SelectedMessageIds(document, view)
    val selectedRowCount = if (view.selectionFromMarquee && view.selectedCanvasRows.isNotEmpty()) {
        view.selectedCanvasRows.size
    } else {
        selectedIds.size
    }
    Popup(
        alignment = Alignment.TopStart,
        offset = view.canvasContextMenuOffset,
        onDismissRequest = { view.canvasContextMenuMessageId = null },
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(170.dp)
                .background(tc().p, RoundedCornerShape(7.dp))
                .border(1.dp, tc().br, RoundedCornerShape(7.dp))
                .padding(vertical = 4.dp),
        ) {
            Seq3DropdownMenuItem("Rename label") {
                seq3BeginLabelRename(view, document, messageId)
            }
            Seq3DropdownMenuItem("Add note") {
                seq3AddNote(
                    state,
                    session,
                    view,
                    document,
                    selectedIds,
                    placement = view.canvasContextMenuCanvasPoint,
                )
            }
            if ((view.selectionFromMarquee && selectedRowCount > 1) || seq3MessageIdsAreContiguous(document, selectedIds)) {
                Seq3DropdownMenuItem("Group as loop") {
                    seq3GroupMessages(state, session, view, selectedIds, Seq3FragmentKind.LOOP)
                }
                Seq3DropdownMenuItem("Group as alt") {
                    seq3GroupMessages(state, session, view, selectedIds, Seq3FragmentKind.ALT)
                }
                Seq3DropdownMenuItem("Group as opt") {
                    seq3GroupMessages(state, session, view, selectedIds, Seq3FragmentKind.OPT)
                }
                Seq3DropdownMenuItem("Group as par") {
                    seq3GroupMessages(state, session, view, selectedIds, Seq3FragmentKind.PAR)
                }
            }
        }
    }
}

@Composable
private fun Seq3NoteTextOverlay(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    note: Seq3NoteBox,
) {
    val tc = tc()
    val density = LocalDensity.current.density
    var editing by remember(note.noteId) { mutableStateOf(false) }
    var text by remember(note.noteId, note.text) { mutableStateOf(note.text) }
    var moveDelta by remember(note.noteId) { mutableStateOf(Offset.Zero) }
    var resizeDelta by remember(note.noteId) { mutableStateOf(Offset.Zero) }
    val latestMoveDelta = rememberUpdatedState(moveDelta)
    val latestResizeDelta = rememberUpdatedState(resizeDelta)
    val movedBox = note.box.copy(
        x = note.box.x + moveDelta.x / density,
        y = note.box.y + moveDelta.y / density,
        width = (note.box.width + resizeDelta.x / density).coerceAtLeast(120.0),
        height = (note.box.height + resizeDelta.y / density).coerceAtLeast(32.0),
    )

    fun commitBox(box: Seq3Box) {
        state.seq3Sessions.applyCommand(
            session.id,
            Seq3Command.SetNoteGeometry(note.noteId, box.x, box.y, box.width, box.height),
        )
        moveDelta = Offset.Zero
        resizeDelta = Offset.Zero
    }

    fun commitText() {
        if (text.isNotBlank()) {
            state.seq3Sessions.applyCommand(
                session.id,
                Seq3Command.Bulk(emptySet(), Seq3BulkAction.SetNoteText(note.noteId, text)),
            )
        }
        editing = false
    }

    Box(
        Modifier.offset(movedBox.x.dp, movedBox.y.dp)
            .size(movedBox.width.dp, movedBox.height.dp)
            .background(tc.seq2.copy(alpha = NOTE_FILL_ALPHA), CORNER_SM)
            .border(1.dp, tc.seq2, CORNER_SM)
            .pointerInput(session.id, note.noteId, note.box) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        moveDelta += dragAmount
                    },
                    onDragEnd = {
                        val delta = latestMoveDelta.value
                        commitBox(note.box.copy(x = note.box.x + delta.x / density, y = note.box.y + delta.y / density))
                    },
                    onDragCancel = { moveDelta = Offset.Zero },
                )
            }
            .pointerInput(session.id, note.noteId) {
                detectTapGestures(onDoubleTap = { editing = true })
            },
    ) {
        if (editing) {
            Column(Modifier.fillMaxSize().padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                InlineField(
                    value = text,
                    onValue = { text = it },
                    fontSize = 10.sp,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onSubmit = ::commitText,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End)) {
                    SquareIconButton("✓", fontSize = 10.sp, onClick = ::commitText, size = 16.dp)
                    SquareIconButton("×", fontSize = 10.sp, onClick = { editing = false }, size = 16.dp)
                }
            }
        } else {
            AppText(note.text, color = tc.tx, fontSize = 10.sp, maxLines = 4, modifier = Modifier.padding(4.dp))
            SquareIconButton(
                "✎",
                fontSize = 10.sp,
                onClick = { editing = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                size = 16.dp,
            )
        }
        Box(
            Modifier.align(Alignment.BottomEnd)
                .size(12.dp)
                .background(tc.seq2, RoundedCornerShape(topStart = 4.dp))
                .pointerInput(session.id, note.noteId, note.box) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            resizeDelta += dragAmount
                        },
                        onDragEnd = {
                            val delta = latestResizeDelta.value
                            commitBox(
                                note.box.copy(
                                    width = (note.box.width + delta.x / density).coerceAtLeast(120.0),
                                    height = (note.box.height + delta.y / density).coerceAtLeast(32.0),
                                ),
                            )
                        },
                        onDragCancel = { resizeDelta = Offset.Zero },
                    )
                },
        )
    }
}

@Composable
private fun Seq3InlineLabelEditor(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    messageId: String,
    occurrenceEntryId: Int?,
    currentLabelTemplate: String,
    box: Seq3Box,
) {
    val tc = tc()
    // This is intentionally the template, not the rendered label for the clicked occurrence.
    // Template slots are the durable representation used by Seq3BulkAction.SetLabel and keep
    // each occurrence's captured parameter values distinct after a rename.
    var text by remember(messageId, occurrenceEntryId) { mutableStateOf(currentLabelTemplate) }

    fun commit() {
        state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(setOf(messageId), Seq3BulkAction.SetLabel(text)))
        view.editingLabelMessageId = null
        view.editingLabelOccurrenceEntryId = null
    }
    Row(
        Modifier.offset(box.x.dp, (box.y - LABEL_EDITOR_Y_OFFSET_DP).dp)
            .background(tc.p, CORNER_SM).border(1.dp, tc.ac, CORNER_SM).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineField(value = text, onValue = { text = it }, fontSize = 10.sp, modifier = Modifier.width(140.dp), onSubmit = ::commit)
        SquareIconButton("✓", fontSize = 10.sp, onClick = ::commit, size = 16.dp)
        SquareIconButton("×", fontSize = 10.sp, onClick = {
            view.editingLabelMessageId = null
            view.editingLabelOccurrenceEntryId = null
        }, size = 16.dp)
    }
}

private const val LABEL_EDITOR_Y_OFFSET_DP = 20.0

// ── Lifeline header chips — draggable to reorder and double-click to rename (spec §07) ─────────

@Composable
private fun Seq3LifelineChip(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    document: Seq3Document,
    layout: Seq3Layout,
    column: Seq3LifelineColumn,
    density: Float,
) {
    val tc = tc()
    val currentDocument = rememberUpdatedState(document)
    val currentLayout = rememberUpdatedState(layout)
    val currentColumn = rememberUpdatedState(column)
    var dragPx by remember(column.lifelineId) { mutableStateOf(0f) }
    var renaming by remember(column.lifelineId) { mutableStateOf(false) }
    var renameText by remember(column.lifelineId) { mutableStateOf(column.label) }
    val selected = column.lifelineId == view.selectedLifelineId

    Column {
        Box(
            Modifier
                .offset { IntOffset((column.header.x * density + dragPx).roundToInt(), (column.header.y * density).roundToInt()) }
                .size(column.header.width.dp, column.header.height.dp)
                .background(if (selected) tc.abg else tc.p2, RoundedCornerShape(4.dp))
                .border(1.dp, if (selected) tc.ac else tc.br, RoundedCornerShape(4.dp))
                .pointerInput(session.id, column.lifelineId) {
                    detectDragGestures(
                        onDragStart = { view.selectedLifelineId = column.lifelineId },
                        onDrag = { change, dragAmount -> change.consume(); dragPx += dragAmount.x },
                        onDragEnd = {
                            val activeDocument = currentDocument.value
                            val activeLayout = currentLayout.value
                            val activeColumn = currentColumn.value
                            val order = activeDocument.lifelines.sortedBy { it.ordinal }.map { it.id }
                            val orderedColumns = activeLayout.lifelines.sortedWith(compareBy { order.indexOf(it.lifelineId) })
                            val dropXUnits = activeColumn.header.x + dragPx / density + activeColumn.header.width / 2
                            // The insertion point is measured among the columns that will remain
                            // after the dragged column is removed. Including the dragged column
                            // shifts every right-side target by one slot.
                            val targetIndex = seq3LifelineDropIndex(
                                orderedColumns.filterNot { it.lifelineId == activeColumn.lifelineId }.map { it.centerX },
                                dropXUnits,
                            )
                            val reordered = seq3ReorderLifelineIds(order, activeColumn.lifelineId, targetIndex)
                            dragPx = 0f
                            if (reordered != order) state.seq3Sessions.applyCommand(session.id, Seq3Command.ReorderLifelines(reordered))
                        },
                        onDragCancel = { dragPx = 0f },
                    )
                }
                .pointerInput(session.id, column.lifelineId) {
                    detectTapGestures(
                        // Select on press so a plain click gives immediate feedback even though
                        // this same surface also owns drag-to-reorder and double-click-to-rename.
                        onPress = {
                            view.selectedLifelineId = column.lifelineId
                            tryAwaitRelease()
                        },
                        onTap = { view.selectedLifelineId = column.lifelineId },
                        onDoubleTap = { renaming = true },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (renaming) {
                InlineField(
                    value = renameText, onValue = { renameText = it }, fontSize = 10.sp,
                    modifier = Modifier.padding(2.dp),
                    onSubmit = {
                        state.seq3Sessions.applyCommand(session.id, Seq3Command.RenameLifeline(column.lifelineId, renameText))
                        renaming = false
                    },
                )
            } else {
                AppText(
                    column.label, color = if (selected) tc.ac else tc.tx, fontSize = 11.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1,
                )
            }
        }
    }
}

// ── Pure helpers — testable without a composition (Seq3CanvasTest) ─────────────────────────────

internal const val SEQ3_ROW_HIT_Y_TOLERANCE = 18.0
internal const val SEQ3_ROW_HIT_X_TOLERANCE = 24.0
internal const val SEQ3_ENDPOINT_HIT_TOLERANCE_X = 36.0

internal enum class Seq3EndpointSide { FROM, TO }

internal data class Seq3DragEndpoint(
    val messageId: String,
    val side: Seq3EndpointSide,
    /** Stable row identity for repeated occurrences; null keeps compatibility for one-row/custom messages. */
    val occurrenceEntryId: Int? = null,
)

internal fun seq3PointInBox(box: Seq3Box, x: Double, y: Double): Boolean =
    x in box.x..(box.x + box.width) && y in box.y..(box.y + box.height)

/** Nearest row on [layout] whose own y sits within [yTolerance] of ([x], [y]) — the hit test behind
 *  a canvas click/hover, mirroring `SeqDiagramWorkspace.kt`'s pixel-space `resolveCanvasClickHit`
 *  but over the UNIT-LESS layout directly (this file never re-derives pixel geometry from a
 *  rasterized image, per this phase's second absolute rule). */
internal fun seq3RowAt(layout: Seq3Layout, x: Double, y: Double, yTolerance: Double = SEQ3_ROW_HIT_Y_TOLERANCE): Seq3RowGeometry? =
    layout.rows
        .filter { kotlin.math.abs(it.y - y) <= yTolerance && seq3RowXDistance(it, x) <= SEQ3_ROW_HIT_X_TOLERANCE }
        .minByOrNull { seq3RowXDistance(it, x) }

private fun seq3RowXDistance(row: Seq3RowGeometry, x: Double): Double = when (row) {
    is Seq3ArrowRow -> min(
        if (x in min(row.fromX, row.toX)..max(row.fromX, row.toX)) 0.0 else min(kotlin.math.abs(x - row.fromX), kotlin.math.abs(x - row.toX)),
        distanceToBox(row.labelBox, x),
    )
    is Seq3SelfLoopRow -> min(
        if (x in row.x..(row.x + row.loopWidth)) 0.0 else kotlin.math.abs(x - (row.x + row.loopWidth / 2)),
        distanceToBox(row.labelBox, x),
    )
    is Seq3UnresolvedStubRow -> {
        val rightEdge = row.dropPill.x + row.dropPill.width
        if (x in row.fromX..rightEdge) 0.0 else min(kotlin.math.abs(x - row.fromX), kotlin.math.abs(x - rightEdge))
    }
    is Seq3MessageNoteRow -> {
        val rightEdge = row.box.x + row.box.width
        if (x in row.box.x..rightEdge) 0.0 else min(kotlin.math.abs(x - row.box.x), kotlin.math.abs(x - rightEdge))
    }
    is Seq3ElisionRow -> kotlin.math.abs(x - (row.box.x + row.box.width / 2))
}

private fun distanceToBox(box: Seq3Box, x: Double): Double = when {
    x < box.x -> box.x - x
    x > box.x + box.width -> x - (box.x + box.width)
    else -> 0.0
}

/** Which end of [row] a press near ([x], [y]) grabs, or null outside [xTolerance]/[yTolerance] of
 *  either endpoint — the canvas-drag half of the design spec's three `To` affordances (§03). */
internal fun seq3ArrowEndpointAt(
    row: Seq3ArrowRow,
    x: Double,
    y: Double,
    yTolerance: Double = SEQ3_ROW_HIT_Y_TOLERANCE,
    xTolerance: Double = SEQ3_ENDPOINT_HIT_TOLERANCE_X,
): Seq3EndpointSide? {
    if (kotlin.math.abs(y - row.y) > yTolerance) return null
    val fromDistance = kotlin.math.abs(x - row.fromX)
    val toDistance = kotlin.math.abs(x - row.toX)
    return when {
        fromDistance <= xTolerance && fromDistance <= toDistance -> Seq3EndpointSide.FROM
        toDistance <= xTolerance -> Seq3EndpointSide.TO
        else -> null
    }
}

internal const val SEQ3_SELF_ENDPOINT_HIT_TOLERANCE = 28.0

/** A self-loop has no horizontal arrow endpoint at the row's y position. Its target arrowhead is at the
 * bottom-left corner of the loop, while the source remains at the top-left corner. */
internal fun seq3SelfLoopEndpointAt(
    row: Seq3SelfLoopRow,
    x: Double,
    y: Double,
    tolerance: Double = SEQ3_SELF_ENDPOINT_HIT_TOLERANCE,
): Seq3EndpointSide? {
    val fromDistance = kotlin.math.hypot(x - row.x, y - row.y)
    val toDistance = kotlin.math.hypot(x - row.x, y - row.loopBottomY)
    return when {
        fromDistance <= tolerance && fromDistance <= toDistance -> Seq3EndpointSide.FROM
        toDistance <= tolerance -> Seq3EndpointSide.TO
        else -> null
    }
}

/** Resolves a press at ([x], [y]) to an endpoint-drag start: either an existing arrow's FROM/TO
 *  handle, or an unresolved stub's whole line/pill (always the TO side — a stub has no target
 *  yet). Null anywhere else on the canvas, which lets the caller fall through to a plain click. */
internal fun seq3ResolveDragEndpoint(layout: Seq3Layout, x: Double, y: Double): Seq3DragEndpoint? {
    data class Candidate(val endpoint: Seq3DragEndpoint, val distance: Double)
    val candidates = layout.rows.mapNotNull { row ->
        when (row) {
            is Seq3ArrowRow -> seq3ArrowEndpointAt(row, x, y)?.let {
                Candidate(
                    Seq3DragEndpoint(row.messageId, it, row.occurrenceEntryId),
                    min(kotlin.math.abs(x - row.fromX), kotlin.math.abs(x - row.toX)) + kotlin.math.abs(y - row.y),
                )
            }
            is Seq3SelfLoopRow -> seq3SelfLoopEndpointAt(row, x, y)?.let {
                Candidate(
                    Seq3DragEndpoint(row.messageId, it, row.occurrenceEntryId),
                    min(kotlin.math.hypot(x - row.x, y - row.y), kotlin.math.hypot(x - row.x, y - row.loopBottomY)),
                )
            }
            is Seq3UnresolvedStubRow -> {
                val onStub = kotlin.math.abs(y - row.y) <= SEQ3_ROW_HIT_Y_TOLERANCE &&
                    (x in row.fromX..(row.dropPill.x + row.dropPill.width) || seq3PointInBox(row.dropPill, x, y))
                if (onStub) Candidate(
                    Seq3DragEndpoint(row.messageId, Seq3EndpointSide.TO, row.occurrenceEntryId),
                    kotlin.math.abs(y - row.y),
                ) else null
            }
            else -> null
        }
    }.sortedBy { it.distance }
    return candidates.firstOrNull()?.endpoint
}

/** "Dragging its head onto a lifeline resolves it" (spec §04) — always resolves to the nearest
 *  column; there is no minimum-distance gate because the drop pill/endpoint handle is already the
 *  explicit target, unlike a a stray click elsewhere on the canvas. */
internal fun seq3NearestLifelineId(layout: Seq3Layout, x: Double): String? =
    layout.lifelines.minByOrNull { kotlin.math.abs(it.centerX - x) }?.lifelineId

/** True when a press at ([x], [y]) lands on nothing at all — no message row, no arrow/stub endpoint
 *  — the condition that arms drag-to-pan on a plain left-button drag (item 12, phase-5 post-ship
 *  plan). A drag that starts ON content keeps meaning whatever it already means there (select,
 *  resolve an endpoint); only genuinely empty background pans. */
internal fun seq3IsEmptyCanvasBackground(layout: Seq3Layout, x: Double, y: Double): Boolean =
    seq3RowAt(layout, x, y) == null &&
        seq3ResolveDragEndpoint(layout, x, y) == null &&
        layout.lifelines.none { seq3PointInBox(it.header, x, y) } &&
        layout.notes.none { seq3PointInBox(it.box, x, y) } &&
        layout.fragments.none { seq3PointInBox(it.box, x, y) }

/** Live feedback while dragging an arrow endpoint (item 13, phase-5 post-ship plan): the FIXED end
 *  of [endpoint]'s row (the one NOT being dragged), its shared y, the live cursor x, and whichever
 *  lifeline the cursor is nearest right now — reusing [seq3NearestLifelineId], the exact function
 *  release path already uses to resolve the drop, so the highlighted column during the drag and the
 *  column that actually gets applied on release can never disagree. Null when [endpoint]'s row can
 *  no longer be found (the document changed under an in-flight drag) or isn't a row this kind of
 *  preview applies to. */
internal fun seq3DragPreview(layout: Seq3Layout, endpoint: Seq3DragEndpoint, cursorX: Double): Seq3EndpointDragPreview? {
    val row = layout.rows.firstOrNull {
        it.messageId == endpoint.messageId &&
            (endpoint.occurrenceEntryId == null || it.occurrenceEntryId == endpoint.occurrenceEntryId)
    } ?: return null
    val (anchorX, y) = when (row) {
        is Seq3ArrowRow -> (if (endpoint.side == Seq3EndpointSide.FROM) row.toX else row.fromX) to row.y
        is Seq3SelfLoopRow -> row.x to row.y
        is Seq3UnresolvedStubRow -> row.fromX to row.y
        else -> return null
    }
    return Seq3EndpointDragPreview(
        anchorX = anchorX,
        y = y,
        cursorX = cursorX,
        candidateLifelineId = seq3NearestLifelineId(layout, cursorX),
        messageId = endpoint.messageId,
        occurrenceEntryId = row.occurrenceEntryId,
        side = endpoint.side,
    )
}

internal data class Seq3EndpointDragPreview(
    val anchorX: Double,
    val y: Double,
    val cursorX: Double,
    val candidateLifelineId: String?,
    val messageId: String = "",
    val occurrenceEntryId: Int? = null,
    val side: Seq3EndpointSide = Seq3EndpointSide.TO,
)

/** Moves [draggedId] to [targetIndex] within [order], clamped to a valid index. A caller passes
 *  [seq3LifelineDropIndex]'s own result — kept as two small pure functions so
 *  Seq3CanvasTest can assert the insertion-point math and the list-move math independently. */
internal fun seq3ReorderLifelineIds(order: List<String>, draggedId: String, targetIndex: Int): List<String> {
    if (draggedId !in order) return order
    val without = order.filterNot { it == draggedId }
    val clampedIndex = targetIndex.coerceIn(0, without.size)
    return without.toMutableList().apply { add(clampedIndex, draggedId) }
}

/** The insertion index for a drop at [dropX] among [centers] (one lifeline column's `centerX`
 *  each, in the SAME order the caller will pass to [seq3ReorderLifelineIds]) — the first column
 *  whose center is at or to the right of the drop point, or the end of the list if none is.
 *  Callers pass centers with the dragged column removed, so the returned index is directly
 *  usable after removal. */
internal fun seq3LifelineDropIndex(centers: List<Double>, dropX: Double): Int =
    centers.indexOfFirst { dropX <= it }.let { if (it < 0) centers.size else it }

/** The genuine complement of [seq3FitWidthZoom] (item 5, phase-5 post-ship plan) — scales so the
 *  diagram's full HEIGHT fits the viewport; width may then need horizontal scrolling. Renamed from
 *  the old `seq3FitZoom`, which fit both axes at once (a redundant "fit everything" mode under a
 *  "Fit" label that no longer matched what it did once "Fit width" existed alongside it). */
internal fun seq3FitHeightZoom(contentHeight: Double, viewportHeight: Double): Float {
    if (contentHeight <= 0.0 || viewportHeight <= 0.0) return 1f
    return (viewportHeight / contentHeight).toFloat().coerceIn(MIN_FIT_ZOOM, MAX_FIT_ZOOM)
}

internal fun seq3FitWidthZoom(contentWidth: Double, viewportWidth: Double): Float {
    if (contentWidth <= 0.0 || viewportWidth <= 0.0) return 1f
    return (viewportWidth / contentWidth).toFloat().coerceIn(MIN_FIT_ZOOM, MAX_FIT_ZOOM)
}

/** Converts a pointer coordinate from physical pixels on the zoomed canvas to layout units. */
internal fun seq3PointerPxToLayoutUnits(pointerPx: Float, density: Float, zoom: Float): Double {
    val safeDensity = density.coerceAtLeast(0.001f)
    val safeZoom = zoom.coerceAtLeast(0.001f)
    return pointerPx.toDouble() / safeDensity / safeZoom
}

internal fun seq3ZoomPercentLabel(zoom: Float): String = "${(zoom * PERCENT).roundToInt()}%"

internal fun seq3CrossingLabel(count: Int): String = when (count) {
    0 -> "No arrow crossings"
    1 -> "1 arrow crossing"
    else -> "$count arrow crossings"
}
