@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.indagium.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.TooltipPlacement
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.indagium.diagram3.Seq3ArrowRow
import com.indagium.diagram3.Seq3Box
import com.indagium.diagram3.Seq3BulkAction
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Delay
import com.indagium.diagram3.Seq3DelayBox
import com.indagium.diagram3.seq3LifelineSegments
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3ElisionRow
import com.indagium.diagram3.Seq3Filter
import com.indagium.diagram3.Seq3FontRole
import com.indagium.diagram3.Seq3FragmentBox
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3Layout
import com.indagium.diagram3.Seq3LifelineColumn
import com.indagium.diagram3.Seq3LifelineKind
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3MessageNoteRow
import com.indagium.diagram3.Seq3NoteBox
import com.indagium.diagram3.Seq3RowGeometry
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.Seq3Selection
import com.indagium.diagram3.Seq3SelfLoopRow
import com.indagium.diagram3.Seq3UnresolvedStubRow
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.seq3ArrowStyle
import com.indagium.diagram3.seq3Select
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
    // WP4: the diagram's own content — canvas background, arrows, lifelines, fragments, notes,
    // labels — resolves THIS document's theme (falling back to the ambient app theme when the
    // document follows it), so it always agrees with the exported PNG and the note preview. Only
    // genuinely chrome-level UI below (the zoom toolbar, status bar, context menu, inline editor
    // popups, scrollbars) keeps reading the ambient [tc] instead — see Seq3CanvasContent's own
    // call sites for exactly where that line is drawn.
    val docTheme = resolveSeq3ThemeColors(document, state.settings)
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
    Column(modifier.fillMaxSize().background(docTheme.bg)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (layout.lifelines.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AppText(if (session.generating) "Generating…" else "No lifelines to diagram", color = docTheme.ts, fontSize = 12.sp)
                }
            } else {
                Seq3CanvasContent(state, session, view, document, layout, docTheme)
            }
        }
        Seq3CanvasStatusBar(state, session, document, view)
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Seq3CanvasControlTooltip("Pan diagram") {
            ToolbarBtn(
                label = "Pan diagram",
                icon = Icons.Outlined.OpenWith,
                showLabel = false,
                active = view.canvasPanMode,
                modifier = Modifier.size(24.dp),
                shape = CORNER_SM,
                contentPadding = PaddingValues(0.dp),
                onClick = { view.canvasPanMode = !view.canvasPanMode },
            )
        }
        Seq3ZoomControls(view)
        Seq3CanvasControlTooltip("Reset zoom to 100%") {
            ToolbarBtn(
                label = "↺",
                showLabel = false,
                modifier = Modifier.size(24.dp),
                shape = CORNER_SM,
                contentPadding = PaddingValues(0.dp),
                onClick = {
                    view.zoom = 1f
                    view.zoomMode = Seq3ZoomMode.MANUAL
                },
            )
        }
        SegmentedControl(
            options = listOf("Fit height", "Fit width"),
            selectedIndices = when (view.zoomMode) {
                Seq3ZoomMode.FIT -> setOf(0)
                Seq3ZoomMode.FIT_WIDTH -> setOf(1)
                Seq3ZoomMode.MANUAL -> emptySet()
            },
            onToggle = { index ->
                when (index) {
                    0 -> view.zoomMode = Seq3ZoomMode.FIT
                    1 -> view.zoomMode = Seq3ZoomMode.FIT_WIDTH
                }
            },
            segmentHeight = 24.dp,
            segmentFontSize = 11.sp,
            segmentHorizontalPadding = 7.dp,
        )
    }
}

@Composable
private fun Seq3CanvasControlTooltip(text: String, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = { ToolbarTooltip(text) },
        delayMillis = 650,
        tooltipPlacement = TooltipPlacement.ComponentRect(
            anchor = Alignment.TopCenter,
            alignment = Alignment.BottomCenter,
            offset = DpOffset(0.dp, -24.dp),
        ),
        content = content,
    )
}

@Composable
private fun Seq3ZoomControls(view: Seq3ViewState) {
    val tc = tc()
    Row(
        modifier = Modifier
            .height(24.dp)
            .border(0.5.dp, tc.br, CORNER_MD)
            .clip(CORNER_MD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Seq3ZoomStepButton("−") {
            view.zoom = (view.zoom - ZOOM_STEP).coerceAtLeast(MIN_ZOOM)
            view.zoomMode = Seq3ZoomMode.MANUAL
        }
        Box(
            Modifier.width(36.dp).height(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            AppText(seq3ZoomPercentLabel(view.zoom), color = tc.ts, fontSize = 10.sp)
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
        modifier = Modifier.size(24.dp)
            .pointerHoverIcon(PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)), overrideDescendants = true),
        shape = CORNER_SM,
        contentPadding = PaddingValues(0.dp),
        baseBg = tc.p2,
        onClick = onClick,
    )
}

@Composable
private fun Seq3CanvasStatusBar(state: AppState, session: Seq3WorkspaceSession, document: Seq3Document, view: Seq3ViewState) {
    val tc = tc()
    val scanned = state.seq3Sessions.scannedEntryCount(session.id)
    val shown = document.messages.count { it.visibility == Seq3Visibility.VISIBLE }
    val hidden = document.messages.size - shown
    Row(
        Modifier.fillMaxWidth().background(tc.p).padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(
            "$shown shown · $scanned scanned · ${document.lifelines.size} lifelines · $hidden hidden",
            color = tc.ts, fontSize = 10.sp,
        )
        Seq3CanvasZoomToolbarControls(view)
    }
}

// ── Scrollable, zoomable, pointer-interactive content ──────────────────────────────────────────

@Composable
private fun Seq3CanvasContent(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    document: Seq3Document,
    layout: Seq3Layout,
    docTheme: ThemeColors,
) {
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
        Box(
            Modifier.fillMaxSize()
                .then(seq3ZoomWheelModifier(view, session.id)),
        ) {
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
                                docTheme,
                                view.hoveredMessageId,
                                view.selection.selectedIds,
                                view.focusedMessageId,
                                view.selectedOccurrenceMessageId,
                                view.selectedOccurrenceEntryId,
                                view.selectedCanvasRows,
                                view.canvasSelectionRect,
                                dragPreview,
                                view.selectedFragmentId,
                                view.hoveredFragmentId,
                                view.selectedNoteId,
                                view.hoveredNoteId,
                            )
                        }
                        layout.fragments.forEach { fragment -> Seq3FragmentLabelOverlay(state, session, view, fragment, docTheme) }
                        layout.rows.forEach { row -> Seq3RowOverlay(state, session, view, row, docTheme) }
                        layout.notes.forEach { note -> Seq3NoteTextOverlay(state, session, view, note, docTheme) }
                        layout.delays.forEach { delay -> Seq3DelayLabelOverlay(state, session, view, delay, docTheme) }
                        layout.lifelines.forEach { column -> Seq3LifelineChip(state, session, view, document, layout, column, density, docTheme) }
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
            if (view.canvasEmptyContextMenuOpen) {
                Seq3CanvasEmptyContextMenu(state, session, view, document)
            }
        }
    }
}

/** Ctrl/Cmd-wheel zooms the diagram while an unmodified wheel keeps scrolling the canvas. This
 * modifier sits outside the scroll containers and listens in the Initial pass, so the scroll
 * modifier cannot consume a modified wheel event before the zoom gesture sees it. */
@Composable
private fun seq3ZoomWheelModifier(view: Seq3ViewState, sessionId: String): Modifier {
    val currentView = rememberUpdatedState(view)
    return Modifier.pointerInput(sessionId) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                val modifiers = event.keyboardModifiers
                if (!modifiers.isCtrlPressed && !modifiers.isMetaPressed) continue
                val nextZoom = seq3ZoomByWheel(currentView.value.zoom, change.scrollDelta.y)
                if (nextZoom != currentView.value.zoom) {
                    currentView.value.zoom = nextZoom
                    currentView.value.zoomMode = Seq3ZoomMode.MANUAL
                }
                change.consume()
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
            // primary drag on empty background pans when the toolbar pan toggle is enabled;
            // otherwise it creates the marquee selection. Overlay bounds are excluded before
            // this gesture is armed, so dragging a note or lifeline cannot also paint a selection
            // rectangle. The pan anchor is kept in viewport pixels, not the diagram node's local
            // pixels: scrolling moves that node under a stationary cursor, so raw local deltas
            // would alternate direction and make the whole diagram flicker.
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
            fun registerMessageClick(now: Long, id: String): Boolean {
                val doubleClick = now - lastClickTimeMs <= DOUBLE_CLICK_WINDOW_MS && lastClickMessageId == id
                lastClickTimeMs = now
                lastClickMessageId = id
                return doubleClick
            }
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
                        view.hoveredMessageId = null
                        if (event.buttons.isSecondaryPressed) {
                            if (!change.isConsumed) {
                                val hitRow = seq3RowAt(activeLayout, xUnits, yUnits)
                                if (hitRow != null) {
                                    val modifiers = event.keyboardModifiers
                                    val additive = modifiers.isCtrlPressed || modifiers.isMetaPressed
                                    val document = currentSession.value.document
                                    // Right-clicking INSIDE an existing selection has to leave it
                                    // alone — the menu acts on what is selected. This used to
                                    // clear selectedCanvasRows unconditionally, so a marquee
                                    // selection silently collapsed to bare message ids the instant
                                    // the menu opened: "Group as" then had only ×N containers to
                                    // work with and disappeared, and grouping from the header
                                    // afterwards framed whole messages instead of the picked rows.
                                    val rowRef = Seq3CanvasRowRef(hitRow.messageId, hitRow.occurrenceEntryId)
                                    val insideSelection = if (view.selectedCanvasRows.isNotEmpty()) {
                                        rowRef in view.selectedCanvasRows
                                    } else {
                                        hitRow.messageId in view.selection.selectedIds
                                    }
                                    if (!insideSelection || additive || modifiers.isShiftPressed) {
                                        view.selection = seq3Select(
                                            document.messages.map { it.id },
                                            view.selection,
                                            hitRow.messageId,
                                            additive = additive,
                                            range = modifiers.isShiftPressed,
                                        )
                                        // Keep the exact-row representation a left click already
                                        // uses, so right-clicking one arrow and grouping it frames
                                        // that arrow rather than every occurrence of its message.
                                        view.selectedCanvasRows = if (additive) {
                                            view.selectedCanvasRows + rowRef
                                        } else {
                                            setOf(rowRef)
                                        }
                                        view.selectionFromMarquee = false
                                    }
                                    view.focusedMessageId = hitRow.messageId
                                    view.canvasContextMenuMessageId = hitRow.messageId
                                    view.canvasContextMenuOccurrenceEntryId = hitRow.occurrenceEntryId
                                    view.canvasEmptyContextMenuOpen = false
                                    view.canvasContextMenuCanvasPoint = Seq3Box(xUnits, yUnits, 0.0, 0.0)
                                    view.canvasContextMenuOffset = seq3ContextMenuOffset(
                                        change.position,
                                        currentHScroll.value.value,
                                        currentVScroll.value.value,
                                    )
                                    change.consume()
                                } else {
                                    // WP7 item 5 (canvas half): right-clicking empty background —
                                    // no message row under the cursor — still opens a menu, just
                                    // the "Add note here" one instead of the row menu.
                                    view.canvasContextMenuMessageId = null
                                    view.canvasContextMenuOccurrenceEntryId = null
                                    view.canvasEmptyContextMenuOpen = true
                                    view.canvasContextMenuCanvasPoint = Seq3Box(xUnits, yUnits, 0.0, 0.0)
                                    view.canvasContextMenuOffset = seq3ContextMenuOffset(
                                        change.position,
                                        currentHScroll.value.value,
                                        currentVScroll.value.value,
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
                            view.canvasContextMenuOccurrenceEntryId = null
                            view.canvasEmptyContextMenuOpen = false
                            downPosition = change.position
                            moved = false
                            pressWasMiddle = isMiddle
                            val modifiers = event.keyboardModifiers
                            selectionAdditive = modifiers.isCtrlPressed || modifiers.isMetaPressed
                            selectionRange = modifiers.isShiftPressed
                            dragEndpoint = if (isMiddle) null else seq3ResolveDragEndpoint(activeLayout, xUnits, yUnits)
                            val emptyBackground = seq3IsEmptyCanvasBackground(activeLayout, xUnits, yUnits)
                            val panBackground = !isMiddle && view.canvasPanMode && dragEndpoint == null && emptyBackground
                            // Pan mode is unchanged: there a background press pans, so the band
                            // never arms and the wider slot would only get in the way.
                            val bandBackground = !view.canvasPanMode &&
                                seq3IsEmptyCanvasBackground(activeLayout, xUnits, yUnits, SEQ3_BAND_START_Y_TOLERANCE)
                            if (!isMiddle && !panBackground && dragEndpoint == null && bandBackground) {
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
                                panBackground -> {
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
                            drag != null && moved -> {
                                // Clear transient UI state before the command can trigger a
                                // document/layout recomposition and invalidate this pointer pass.
                                currentDragPreviewCallback.value(null)
                                dragEndpoint = null
                                seq3NearestLifelineId(activeLayout, xUnits)?.let { targetLifelineId ->
                                    val action = when {
                                        // WP7 item 2: a stub drop sets BOTH endpoints (from =
                                        // dropped lifeline, to = the tag's own prior from) in one
                                        // command — see Seq3BulkAction.SetCaller's own doc.
                                        drag.isStub -> Seq3BulkAction.SetCaller(targetLifelineId)
                                        drag.side == Seq3EndpointSide.FROM -> Seq3BulkAction.SetFrom(targetLifelineId)
                                        else -> Seq3BulkAction.SetTo(targetLifelineId)
                                    }
                                    currentState.value.seq3Sessions.applyCommand(
                                        currentSession.value.id,
                                        Seq3Command.Bulk(setOf(drag.messageId), action),
                                    )
                                }
                            }
                            drag != null -> {
                                // An endpoint hit is also a valid message click. Only a gesture
                                // that crossed the drag threshold should retarget the endpoint;
                                // a stationary Cmd/Ctrl-click must participate in multi-selection.
                                currentDragPreviewCallback.value(null)
                                dragEndpoint = null
                                seq3HandleCanvasClick(
                                    view,
                                    currentSession.value.document,
                                    activeLayout,
                                    xUnits,
                                    yUnits,
                                    selectionAdditive,
                                    selectionRange,
                                ) { now, id ->
                                    registerMessageClick(now, id)
                                }
                            }
                            panning -> {
                                val wasMoved = moved
                                panning = false
                                lastPanPosition = null
                                if (!wasMoved) seq3ClearSelection(view, clearFocus = true)
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
                                    view.focusedMessageId = view.selection.selectedIds.firstOrNull()
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
                                    // Pressed and released without dragging: that is a click, not
                                    // an empty band. Send it through the ordinary click path so the
                                    // forgiving [SEQ3_ROW_HIT_Y_TOLERANCE] still picks up a nearby
                                    // arrow — arming the band on a tighter tolerance must not cost
                                    // the user the easy click they had before. That path clears the
                                    // selection itself when nothing is under the cursor.
                                    seq3HandleCanvasClick(
                                        view,
                                        currentSession.value.document,
                                        activeLayout,
                                        xUnits,
                                        yUnits,
                                        selectionAdditive,
                                        selectionRange,
                                    ) { now, id -> registerMessageClick(now, id) }
                                }
                            }
                            !moved && !pressWasMiddle -> {
                                seq3HandleCanvasClick(
                                    view,
                                    currentSession.value.document,
                                    activeLayout,
                                    xUnits,
                                    yUnits,
                                    selectionAdditive,
                                    selectionRange,
                                ) { now, id -> registerMessageClick(now, id) }
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

private fun seq3HandleCanvasClick(
    view: Seq3ViewState,
    document: Seq3Document,
    layout: Seq3Layout,
    x: Double,
    y: Double,
    additive: Boolean,
    range: Boolean,
    registerClick: (nowMs: Long, id: String) -> Boolean,
) {
    val hitRow = seq3RowAt(layout, x, y)
    if (hitRow != null) {
        seq3HandleCanvasRowClick(view, document, hitRow, additive, range, registerClick)
    } else if (!additive && !range) {
        // Notes, fragments, lifeline headers, and empty canvas space are not message-selection
        // targets. A plain click on any of them must dismiss the previous message selection.
        seq3ClearSelection(view, clearFocus = true)
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
    if (!range) {
        // Cmd/Ctrl-click uses the same exact-row representation as a marquee. This keeps
        // highlighting, repeated-occurrence expansion, and fragment grouping identical between
        // the two canvas selection gestures.
        val rowRef = Seq3CanvasRowRef(hitRow.messageId, hitRow.occurrenceEntryId)
        val nextRows = if (additive) {
            if (rowRef in view.selectedCanvasRows) view.selectedCanvasRows - rowRef
            else view.selectedCanvasRows + rowRef
        } else {
            setOf(rowRef)
        }
        view.selectedCanvasRows = nextRows
        view.selection = Seq3Selection(
            selectedIds = nextRows.mapTo(linkedSetOf()) { it.messageId },
            anchorId = hitRow.messageId,
        )
        view.selectedOccurrenceIds = emptySet()
        // Keep the exact occurrence focus for the queue as well as the canvas row ref. The canvas
        // uses selectedCanvasRows for exact arrow emphasis; the queue uses these two fields for
        // the visible selected-submessage background.
        view.selectedOccurrenceMessageId = hitRow.occurrenceEntryId?.let { hitRow.messageId }
        view.selectedOccurrenceEntryId = hitRow.occurrenceEntryId
        if (hitRow.occurrenceEntryId != null) {
            // A canvas click must make the exact queue row visible even when the parent group was
            // collapsed. Otherwise the diagram knows the occurrence, but the user cannot see or
            // act on the matching submessage in the queue. Tracked as an automatic expansion so it
            // collapses again when the canvas moves on — see seq3AutoExpandOccurrences.
            seq3AutoExpandOccurrences(view, hitRow.messageId)
        }
    } else {
        view.selection = seq3Select(
            document.messages.map { it.id },
            view.selection,
            hitRow.messageId,
            additive = additive,
            range = range,
        )
        view.selectedCanvasRows = emptySet()
        view.selectedOccurrenceIds = emptySet()
        view.selectedOccurrenceMessageId = null
        view.selectedOccurrenceEntryId = null
    }
    view.focusedMessageId = hitRow.messageId.takeIf { view.selection.selectedIds.isNotEmpty() }
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
    .filter { row -> boxesIntersect(seq3RowSelectionBounds(row), selection) }
    .mapTo(linkedSetOf()) { it.messageId }

internal fun seq3RowRefsInSelection(layout: Seq3Layout, selection: Seq3Box): List<Seq3CanvasRowRef> = layout.rows
    .filter { row -> row !is Seq3ElisionRow && boxesIntersect(seq3RowSelectionBounds(row), selection) }
    .map { row -> Seq3CanvasRowRef(row.messageId, row.occurrenceEntryId) }

/** What a rubber band encloses.
 *
 *  This used to pad an arrow by [SEQ3_ROW_HIT_Y_TOLERANCE] above and below its line, borrowing
 *  the generous target a CLICK wants ([seq3RowAt] still applies that tolerance, which is right for
 *  pointing at a one-pixel line). A band is not a click: rows sit only ROW_H (42) apart, so 18
 *  units of padding reached most of the way to the neighbouring arrow, and a band whose edge
 *  stopped in the gap between two rows still swept in the row beyond it — the user dragged over
 *  four arrows and got a fragment around six. A band selects what it visually contains, so an
 *  arrow counts only when its own line falls inside. Rows that genuinely occupy a box (notes,
 *  elisions) keep that box. */
private fun seq3RowSelectionBounds(row: Seq3RowGeometry): Seq3Box = when (row) {
    is Seq3ArrowRow -> Seq3Box(min(row.fromX, row.toX), row.y, max(row.toX, row.fromX) - min(row.fromX, row.toX), 0.0)
    is Seq3SelfLoopRow -> Seq3Box(row.x, row.y, row.loopWidth, 0.0)
    is Seq3UnresolvedStubRow -> Seq3Box(
        min(row.fromX, row.stubEndX),
        row.y,
        max(row.stubEndX, row.fromX) - min(row.fromX, row.stubEndX),
        0.0,
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
    // WP7 item 7 (deferred from an earlier package, TODO(WP7) on Seq3ViewState): a Fragments &
    // notes panel row's click/hover already writes these two id pairs — the canvas was the one
    // missing consumer. Mirrors how a message row's hover/selection already emphasizes its own
    // arrow (seq3RowIsEmphasized below) via the same "thicker accent stroke" visual language.
    selectedFragmentId: String? = null,
    hoveredFragmentId: String? = null,
    selectedNoteId: String? = null,
    hoveredNoteId: String? = null,
) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
    layout.fragments.forEach { fragment ->
        val emphasized = seq3FragmentIsEmphasized(fragment.fragmentId, selectedFragmentId, hoveredFragmentId)
        val topLeft = Offset(fragment.box.x.dp.toPx(), fragment.box.y.dp.toPx())
        val size = Size(fragment.box.width.dp.toPx(), fragment.box.height.dp.toPx())
        drawRect(color = tc.seq1.copy(alpha = FRAGMENT_WASH_ALPHA), topLeft = topLeft, size = size)
        drawRect(
            color = if (emphasized) tc.ac else tc.seq1,
            topLeft = topLeft, size = size,
            style = Stroke(width = (if (emphasized) 2 else 1).dp.toPx()),
        )
    }
    layout.notes.forEach { note ->
        val emphasized = seq3NoteIsEmphasized(note.noteId, selectedNoteId, hoveredNoteId)
        val topLeft = Offset(note.box.x.dp.toPx(), note.box.y.dp.toPx())
        val size = Size(note.box.width.dp.toPx(), note.box.height.dp.toPx())
        drawRect(color = tc.seq2.copy(alpha = NOTE_FILL_ALPHA), topLeft = topLeft, size = size)
        drawRect(
            color = if (emphasized) tc.ac else tc.seq2,
            topLeft = topLeft, size = size,
            style = Stroke(width = (if (emphasized) 2 else 1).dp.toPx()),
        )
    }
    // User-observed correction: a lifeline used to draw with the same dash the whole way down.
    // PlantUML itself switches to a denser, round-dotted pattern for the height of a `...` delay
    // marker, then reverts — dottedDash pairs a near-zero dash length with a round cap so it
    // draws as dots, not tiny dashes; seq3LifelineSegments (Seq3Layout.kt) is the one place that
    // decides where each pattern applies, shared with Seq3Raster.kt's own paintLifelines.
    val dottedDash = PathEffect.dashPathEffect(floatArrayOf(0.1.dp.toPx(), 4.dp.toPx()))
    layout.lifelines.forEach { column ->
        seq3LifelineSegments(column.lifelineTop, column.lifelineBottom, layout.delays).forEach { segment ->
            drawLine(
                color = tc.td,
                start = Offset(column.centerX.dp.toPx(), segment.fromY.dp.toPx()),
                end = Offset(column.centerX.dp.toPx(), segment.toY.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
                pathEffect = if (segment.isDotted) dottedDash else dash,
                cap = if (segment.isDotted) StrokeCap.Round else StrokeCap.Butt,
            )
        }
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
    // User-observed correction: this used to draw a dashed rule spanning the full diagram width
    // for every delay. PlantUML's own `...label...` (plantuml.com's own "Delay" example, compared
    // against directly) draws no divider line at all — only the participants' own lifelines
    // continue straight through the gap (already painted, unbroken, in the lifeline loop above),
    // with the label centered in the extra vertical space. Nothing to paint here any more; the
    // label itself is a Compose Text overlay (Seq3DelayLabelOverlay, drawn in the parent Box
    // alongside Seq3FragmentLabelOverlay/Seq3NoteTextOverlay), including its own selected/hovered
    // emphasis now that there's no rule left to color instead.
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

// Item 10 (WP2): the "reference" weight the caller's `strokeWidth` argument was always expressed
// relative to BEFORE this rewrite — the call site (drawSeq3Diagram) still passes exactly
// `1.5.dp.toPx()` normal / `2.dp.toPx()` emphasized, unchanged, so this is what makes THIS
// function's own re-derivation of that emphasis ratio agree with what the caller means by it.
private const val ARROW_REFERENCE_WIDTH_DP = 1.5f

// Seq3ArrowStyle.thin's own weight, one notch thinner than the reference above — mirrors
// Seq3Raster's STROKE_THIN vs STROKE_THICK split (1f vs 1.6f) closely enough that RETURN/ASYNC
// read visibly thinner than a CALL arrow on screen, matching the exported PNG.
private const val ARROW_THIN_WIDTH_DP = 1f

/**
 * Item 10 (phase-5/WP2 fix): this used to always draw a solid line with a filled triangular head,
 * never reading [Seq3ArrowRow.kind] at all — RETURN/ASYNC looked identical to CALL on screen while
 * already differing in the exported PNG (Seq3Raster's `strokeFor`/`drawArrowhead` DID branch on
 * kind). Now both renderers consume the exact same [seq3ArrowStyle] descriptor, so they can never
 * drift apart again.
 *
 * [strokeWidth] is the caller's hover/selection EMPHASIS signal (`drawSeq3Diagram` still passes the
 * same `1.5.dp.toPx()` / `2.dp.toPx()` pair it always did) — see [ARROW_REFERENCE_WIDTH_DP]'s own
 * doc for how this function re-expresses it as a ratio and re-applies that ratio on top of THIS
 * kind's own base weight, rather than discarding the caller's emphasis behaviour outright.
 */
/**
 * Pure ratio math extracted out of [drawSeq3Arrow] so [Seq3CanvasTest] can pin it down without a
 * live Compose `DrawScope`/`Density` — [emphasisWidth]/[referenceWidth]/[thinWidth] are meant to
 * be called with values in the SAME unit (this file always passes already-`.dp.toPx()`-resolved
 * pixels; the ratio itself is unit/density invariant, so a caller in a pure test can just as
 * validly pass plain dp floats). Returns (line stroke width, open-head stroke width) in that same
 * unit — see [drawSeq3Arrow]'s own doc for why the head width is always the UN-thinned reference
 * width scaled by the emphasis ratio, even when the line itself draws thinner.
 */
internal fun seq3ArrowStrokeWidths(
    kind: Seq3Kind,
    emphasisWidth: Float,
    referenceWidth: Float = ARROW_REFERENCE_WIDTH_DP,
    thinWidth: Float = ARROW_THIN_WIDTH_DP,
): Pair<Float, Float> {
    val style = seq3ArrowStyle(kind)
    val emphasisRatio = if (referenceWidth > 0f) emphasisWidth / referenceWidth else 1f
    val baseWidth = if (style.thin) thinWidth else referenceWidth
    val lineWidth = baseWidth * emphasisRatio
    val headWidth = referenceWidth * emphasisRatio
    return lineWidth to headWidth
}

private fun DrawScope.drawSeq3Arrow(row: Seq3ArrowRow, color: Color, strokeWidth: Float) {
    val style = seq3ArrowStyle(row.kind)
    val referenceWidthPx = ARROW_REFERENCE_WIDTH_DP.dp.toPx()
    val thinWidthPx = ARROW_THIN_WIDTH_DP.dp.toPx()
    val (lineWidth, headStrokeWidth) = seq3ArrowStrokeWidths(row.kind, strokeWidth, referenceWidthPx, thinWidthPx)

    val start = Offset(row.fromX.dp.toPx(), row.y.dp.toPx())
    val end = Offset(row.toX.dp.toPx(), row.y.dp.toPx())
    val pathEffect = style.dash?.let { dash -> PathEffect.dashPathEffect(dash.map { it.dp.toPx() }.toFloatArray()) }
    drawLine(color, start, end, strokeWidth = lineWidth, pathEffect = pathEffect)

    val dir = if (row.toX >= row.fromX) 1f else -1f
    val headLen = ARROWHEAD_LEN_DP.dp.toPx()
    val headHalf = ARROWHEAD_HALF_DP.dp.toPx()
    val backX = end.x - dir * headLen
    if (style.filledHead) {
        val head = Path().apply {
            moveTo(end.x, end.y)
            lineTo(backX, end.y - headHalf)
            lineTo(backX, end.y + headHalf)
            close()
        }
        drawPath(head, color)
    } else {
        // Open two-line head — never drawPath(head) for RETURN/ASYNC, matching Seq3Raster's own
        // `drawArrowhead(filled = false)` two-stroke shape instead of a filled triangle.
        drawLine(color, Offset(end.x, end.y), Offset(backX, end.y - headHalf), strokeWidth = headStrokeWidth)
        drawLine(color, Offset(end.x, end.y), Offset(backX, end.y + headHalf), strokeWidth = headStrokeWidth)
    }
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
private fun Seq3RowOverlay(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    row: Seq3RowGeometry,
    docTheme: ThemeColors,
) {
    val emphasized = seq3RowIsEmphasized(
        row,
        view.hoveredMessageId,
        view.selection.selectedIds,
        view.focusedMessageId,
        view.selectedOccurrenceMessageId,
        view.selectedOccurrenceEntryId,
        view.selectedCanvasRows,
    )
    val labelColor = if (emphasized) docTheme.ac else docTheme.tx
    when (row) {
        is Seq3ArrowRow -> {
            Seq3LabelText(row.labelBox, row.label, labelColor)
            row.badgeBox?.let { Seq3BadgeChip(it, row.repeatCount, docTheme) }
            Seq3InlineLabelEditorIfNeeded(state, session, view, row, row.label, row.labelBox)
        }
        is Seq3SelfLoopRow -> {
            Seq3LabelText(row.labelBox, row.label, labelColor)
            row.badgeBox?.let { Seq3BadgeChip(it, row.repeatCount, docTheme) }
            Seq3InlineLabelEditorIfNeeded(state, session, view, row, row.label, row.labelBox)
        }
        is Seq3UnresolvedStubRow -> {
            Seq3LabelText(row.labelBox, row.label, docTheme.warn)
            Box(
                Modifier.offset(row.dropPill.x.dp, row.dropPill.y.dp).size(row.dropPill.width.dp, row.dropPill.height.dp)
                    .background(docTheme.warnBg, RoundedCornerShape(50)).border(1.dp, docTheme.warn, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) { AppText("drop on a lifeline", color = docTheme.warn, fontSize = 9.sp, maxLines = 1) }
            Seq3InlineLabelEditorIfNeeded(state, session, view, row, row.label, row.labelBox)
        }
        is Seq3MessageNoteRow -> {
            Box(Modifier.offset(row.box.x.dp, row.box.y.dp).size(row.box.width.dp, row.box.height.dp).padding(3.dp)) {
                Column { row.lines.forEach { line -> AppText(line, color = docTheme.tx, fontSize = 10.sp, maxLines = 1) } }
            }
            Seq3InlineLabelEditorIfNeeded(state, session, view, row, row.lines.joinToString(" "), row.box)
        }
        is Seq3ElisionRow -> Box(
            Modifier.offset(row.box.x.dp, row.box.y.dp).size(row.box.width.dp, row.box.height.dp),
            contentAlignment = Alignment.Center,
        ) { AppText("⋮ ×${row.elidedCount}", color = docTheme.td, fontSize = 9.sp) }
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
        // A null occurrence id represents a whole-message selection (queue Cmd/Ctrl-click).
        // Concrete occurrence ids remain exact, which keeps repeated submessages independent.
        return Seq3CanvasRowRef(row.messageId, occurrenceEntryId = null) in selectedCanvasRows ||
            Seq3CanvasRowRef(row.messageId, row.occurrenceEntryId) in selectedCanvasRows
    }
    if (selectedOccurrenceMessageId != null) {
        return row.messageId == selectedOccurrenceMessageId && row.occurrenceEntryId == selectedOccurrenceEntryId
    }
    return row.messageId in selectedIds || row.messageId == focusedMessageId
}

/** WP7 item 7: the fragment counterpart of [seq3RowIsEmphasized] — a Fragments & notes panel row's
 *  selection OR hover highlights that fragment's bracket on the canvas. Pure/trivial on purpose
 *  (both id comparisons, no state reads) so it stays testable without a composition, matching every
 *  other hit-test/emphasis helper in this file. */
internal fun seq3FragmentIsEmphasized(fragmentId: String, selectedFragmentId: String?, hoveredFragmentId: String?): Boolean =
    fragmentId == selectedFragmentId || fragmentId == hoveredFragmentId

/** The note counterpart of [seq3FragmentIsEmphasized] — same contract. */
internal fun seq3NoteIsEmphasized(noteId: String, selectedNoteId: String?, hoveredNoteId: String?): Boolean =
    noteId == selectedNoteId || noteId == hoveredNoteId

/** The delay counterpart of [seq3FragmentIsEmphasized] — same contract. */
internal fun seq3DelayIsEmphasized(delayId: String, selectedDelayId: String?, hoveredDelayId: String?): Boolean =
    delayId == selectedDelayId || delayId == hoveredDelayId

// Item 2 (WP2 font-mismatch fix): Seq3Layout measures a message label at Seq3FontRole.LABEL's
// basePointSize (12pt, Seq3AwtTextMetrics) — this used to draw at 11.sp, so the AWT-computed label
// box (and any wrapping/ellipsizing sized against it) was measured at a size Compose never
// actually painted at. Aligning the draw size to what layout measured is the fix (rather than
// re-measuring in Compose), per this file's own header: layout is the single geometry source both
// renderers must agree with, never re-derived downstream.
private val SEQ3_LABEL_FONT_SIZE = Seq3FontRole.LABEL.basePointSize.sp

// Item 9 (WP9 regression fix): Seq3AwtTextMetrics measures with `java.awt.Font(Font.SANS_SERIF, ...)`
// (Seq3Raster.kt's fontFor). AppText's default fontFamily is FontFamily.Default, which Skia/Compose
// resolves to whatever the platform's default UI font is — NOT necessarily the same face or the
// same advance widths as AWT's SansSerif logical font. Aligning WP2's font *size* without also
// aligning the font *family* still leaves the two renderers free to disagree on a string's painted
// width, and a label box sized to the AWT measurement with zero slack (see Seq3Layout's
// withMeasurementSlack) then clips the tail of the drawn text — this is exactly what made a real
// message's parameter (e.g. `onScreenChanged: MEDIA`) vanish from the canvas while still showing up
// correctly in the raster/PNG export (which paints with the very FontMetrics that measured it) and
// in the Info panel (which reads the model directly, never the painted pixels).
private val SEQ3_MEASURED_FONT_FAMILY = FontFamily.SansSerif

@Composable
private fun Seq3LabelText(box: Seq3Box, text: String, color: Color) {
    if (text.isEmpty()) return
    Box(Modifier.offset(box.x.dp, box.y.dp).size(box.width.dp, box.height.dp), contentAlignment = Alignment.CenterStart) {
        AppText(text, color = color, fontSize = SEQ3_LABEL_FONT_SIZE, fontFamily = SEQ3_MEASURED_FONT_FAMILY, maxLines = 1)
    }
}

@Composable
private fun Seq3BadgeChip(box: Seq3Box, count: Int, docTheme: ThemeColors) {
    Box(
        Modifier.offset(box.x.dp, box.y.dp).size(box.width.dp, box.height.dp)
            .background(docTheme.p2, RoundedCornerShape(3.dp)).border(1.dp, docTheme.br, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) { AppText("×$count", color = docTheme.ts, fontSize = 9.sp) }
}

@Composable
private fun Seq3FragmentLabelOverlay(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    fragment: Seq3FragmentBox,
    docTheme: ThemeColors,
) {
    var editing by remember(session.id, fragment.fragmentId) { mutableStateOf(false) }
    var text by remember(session.id, fragment.fragmentId, fragment.label) { mutableStateOf(fragment.label) }
    val headerHeight = fragment.box.height.coerceAtMost(28.0).coerceAtLeast(20.0).dp
    val kindLabel = fragment.kind.name.lowercase()
    // WP12: `hideKindLabel` lets a fragment show just its own label, with no "$kind: " prefix — a
    // per-fragment presentation flag (Seq3Fragment.hideKindLabel's own doc explains why per-
    // fragment rather than document-wide). This is canvas-only: the raster/Mermaid/PlantUML
    // outputs already draw/emit the bare label, never this prefix, so nothing else reads this flag.
    val displayedLabel = if (fragment.hideKindLabel || fragment.label.equals(kindLabel, ignoreCase = true)) {
        fragment.label
    } else {
        "$kindLabel: ${fragment.label}"
    }

    fun commit() {
        if (text.isNotBlank()) {
            state.seq3Sessions.applyCommand(
                session.id,
                Seq3Command.Bulk(emptySet(), Seq3BulkAction.SetFragmentLabel(fragment.fragmentId, text)),
            )
        }
        editing = false
    }

    fun cancel() {
        text = fragment.label
        editing = false
    }

    Box(
        Modifier.offset(fragment.box.x.dp, fragment.box.y.dp)
            .width(fragment.box.width.dp)
            .height(headerHeight)
            .pointerInput(session.id, fragment.fragmentId) {
                detectTapGestures(
                    onTap = { seq3ClearSelection(view, clearFocus = true) },
                    onDoubleTap = { editing = true },
                )
            },
    ) {
        if (editing) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 3.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                InlineField(
                    value = text,
                    onValue = { text = it },
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f).onFocusChanged { view.textFieldFocused = it.hasFocus },
                    onSubmit = ::commit,
                    onCancel = ::cancel,
                )
                SquareIconButton("✓", fontSize = 10.sp, onClick = ::commit, size = 16.dp)
                SquareIconButton("×", fontSize = 10.sp, onClick = ::cancel, size = 16.dp)
            }
        } else {
            Row(
                Modifier.fillMaxSize().padding(start = 3.dp, end = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                AppText(
                    displayedLabel,
                    color = docTheme.seq1,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                SquareIconButton("✎", fontSize = 9.sp, onClick = { editing = true }, size = 16.dp)
                SquareIconButton(
                    "×",
                    fontSize = 10.sp,
                    onClick = {
                        state.seq3Sessions.applyCommand(
                            session.id,
                            Seq3Command.Bulk(emptySet(), Seq3BulkAction.DeleteFragment(fragment.fragmentId)),
                        )
                    },
                    size = 16.dp,
                )
            }
        }
    }
}

/** WP11 — the label overlay for one [Seq3DelayBox]. Mirrors [Seq3FragmentLabelOverlay]'s
 *  edit/delete affordances (double-click to rename, always-visible × to remove) rather than
 *  [Seq3NoteTextOverlay]'s drag/resize handling — a delay has no position of its own to drag, it
 *  is always exactly where [Seq3DelayBox.box] (computed by `buildRows`, Seq3Layout.kt) says. */
@Composable
private fun Seq3DelayLabelOverlay(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    delay: Seq3DelayBox,
    docTheme: ThemeColors,
) {
    var editing by remember(session.id, delay.delayId) { mutableStateOf(false) }
    var text by remember(session.id, delay.delayId, delay.label) { mutableStateOf(delay.label) }
    // Now that there's no rule left to color (the horizontal divider was removed — see the
    // DrawScope call site's own comment on why), the Artifacts panel row's select/hover instead
    // outlines this chip, same "accent border" language Seq3ArtifactRow uses for a selected row.
    val emphasized = seq3DelayIsEmphasized(delay.delayId, view.selectedDelayId, view.hoveredDelayId)

    fun commit() {
        if (text.isNotBlank()) {
            state.seq3Sessions.applyCommand(
                session.id,
                Seq3Command.Bulk(emptySet(), Seq3BulkAction.SetDelayLabel(delay.delayId, text)),
            )
        }
        editing = false
    }

    Box(
        Modifier.offset(delay.box.x.dp, delay.box.y.dp)
            .width(delay.box.width.dp)
            .height(delay.box.height.dp)
            .pointerInput(session.id, delay.delayId) {
                detectTapGestures(
                    onTap = { seq3ClearSelection(view, clearFocus = true) },
                    onDoubleTap = { editing = true },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (editing) {
            Row(
                Modifier.background(docTheme.p, RoundedCornerShape(4.dp)).padding(horizontal = 3.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                InlineField(
                    value = text,
                    onValue = { text = it },
                    fontSize = 10.sp,
                    modifier = Modifier.width(90.dp).onFocusChanged { view.textFieldFocused = it.hasFocus },
                    onSubmit = ::commit,
                )
                SquareIconButton("✓", fontSize = 10.sp, onClick = ::commit, size = 16.dp)
                SquareIconButton("×", fontSize = 10.sp, onClick = { editing = false }, size = 16.dp)
            }
        } else {
            Row(
                Modifier.background(docTheme.p, RoundedCornerShape(4.dp))
                    .then(if (emphasized) Modifier.border(1.dp, docTheme.ac, RoundedCornerShape(4.dp)) else Modifier)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // User-observed correction: this used to share the 10sp every other overlay chip's
                // label uses, which read as too easy to miss floating alone with nothing else
                // nearby to draw the eye — bumped to match Seq3FontRole.DELAY's 13pt raster size
                // and given Medium weight, since a delay has no line of its own left to anchor it.
                AppText(
                    delay.label,
                    color = if (emphasized) docTheme.ac else docTheme.ts,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                SquareIconButton(
                    "×",
                    fontSize = 9.sp,
                    onClick = {
                        state.seq3Sessions.applyCommand(
                            session.id,
                            Seq3Command.Bulk(emptySet(), Seq3BulkAction.DeleteDelay(delay.delayId)),
                        )
                    },
                    size = 14.dp,
                )
            }
        }
    }
}

// Both canvas context menus' card width — widened from the old borderless 170dp
// Seq3DropdownMenuItem list (see this section's own header comment) to make room for CtxItem's
// 24dp icon gutter and to match the log view's row context menu card (App.kt ~line 740) the two
// are meant to feel identical to.
private val SEQ3_CTX_MENU_WIDTH = 264.dp

/** The non-interactive title row shared by [Seq3CanvasContextMenu]'s card — unlike the log view's
 *  own `ActionHeader` (App.kt ~line 786, clickable because it doubles as "Add annotation") there
 *  is no single primary verb for a right-clicked message, so this is a plain label plus the same
 *  `p2` strip and hairline rule, not a [HoverBox]. */
@Composable
private fun Seq3CtxMenuHeader(label: String) {
    val tc = tc()
    Box(Modifier.fillMaxWidth().background(tc.p2).padding(horizontal = 14.dp, vertical = 10.dp)) {
        AppText(label, color = tc.ac, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(tc.br))
}

/** Confirms which message the canvas menu is about to act on — a repeated message's occurrence is
 *  what decides where "Insert delay after this" lands (see that item's own comment below), and
 *  without this preview the menu gave no confirmation of its target at all. Mirrors the log view's
 *  own `CtxMenuEntry.Preview` card (App.kt ~line 860) styling exactly. Falls back to the raw
 *  lifeline id if a lookup misses (should not happen for a well-formed document), and omits the
 *  arrow line entirely for a [Seq3Kind.NOTE]-style message with no `toLifelineId`. */
@Composable
private fun Seq3CtxMenuPreview(document: Seq3Document, message: Seq3Message) {
    val tc = tc()
    val sourceName = document.lifelines.firstOrNull { it.id == message.fromLifelineId }?.name
        ?: message.fromLifelineId
    val targetName = message.toLifelineId?.let { toId ->
        document.lifelines.firstOrNull { it.id == toId }?.name ?: toId
    }
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(tc.p2, CORNER_MD)
            .border(BorderStroke(0.5.dp, tc.br.copy(alpha = 0.5f)), CORNER_MD)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (targetName != null) {
            AppText(
                "$sourceName → $targetName",
                color = tc.td,
                fontSize = 10.sp,
                fontFamily = MONO,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
        }
        AppText(
            message.labelTemplate,
            color = tc.ts,
            fontSize = 10.sp,
            fontFamily = MONO,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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
    val occurrenceEntryId = view.canvasContextMenuOccurrenceEntryId
    val selectedIds = seq3SelectedMessageIds(document, view)
    val message = document.messages.firstOrNull { it.id == messageId }
    val tc = tc()
    val density = LocalDensity.current
    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    // Same idea as App.kt's own `submenuOpensLeft` (that file's row context menu, ~line 481): a
    // submenu opening to the right of a 264dp-wide card can fall off-screen near the window's
    // right edge, so flip it left when there isn't room. This menu's own x comes from
    // `view.canvasContextMenuOffset` (pixels, unlike App.kt's dp-based `x`) since it positions a
    // Popup outside the scrolled canvas content rather than inside a BoxWithConstraints.
    val submenuOpensLeft = with(density) {
        view.canvasContextMenuOffset.x + SEQ3_CTX_MENU_WIDTH.roundToPx() + CTX_SUBMENU_WIDTH.roundToPx() > windowWidthPx
    }
    Popup(
        alignment = Alignment.TopStart,
        offset = view.canvasContextMenuOffset,
        onDismissRequest = {
            view.canvasContextMenuMessageId = null
            view.canvasContextMenuOccurrenceEntryId = null
        },
        properties = PopupProperties(focusable = true),
    ) {
        // The exact occurrence's own entryId when the right-click hit one specific row of a
        // repeated message, else that message's first — same "prefer the exact occurrence, fall
        // back to the message's own default" shape "Insert delay after this" below already uses.
        // Null only when the message itself somehow has no occurrences left (every visible one
        // hidden) — CtxItem's own `enabled` then makes the item a no-op, not a crash.
        val jumpEntryId = message?.let {
            occurrenceEntryId?.let { entryId -> it.occurrences.firstOrNull { occ -> occ.entryId == entryId } }?.entryId
                ?: it.occurrences.firstOrNull()?.entryId
        }
        Column(
            Modifier.width(SEQ3_CTX_MENU_WIDTH)
                .shadow(8.dp, RoundedCornerShape(7.dp))
                .background(tc.p, RoundedCornerShape(7.dp))
                .border(1.dp, tc.br, RoundedCornerShape(7.dp)),
            // No vertical padding on the card itself: the header's `p2` strip has to run flush to
            // the card's top edge (the log view's own menu card has none either, for the same
            // reason) or a 4dp band of `p` shows above it. The bottom's breathing room is the
            // trailing Spacer below instead.
        ) {
            Seq3CtxMenuHeader("Message")
            if (message != null) {
                Seq3CtxMenuPreview(document, message)
            }
            CtxDivider()
            // Mirrors the `l` keyboard shortcut (Seq3KeyAction.JumpToLog / applySeq3JumpToLog)
            // but occurrence-precise rather than always the message's first occurrence, and
            // reachable without first focusing the row — the same discoverability gap "Insert
            // delay after this" filled for delays now filled here for the log-navigation path.
            CtxItem(
                Icons.AutoMirrored.Outlined.Login,
                "Go to log line",
                enabled = jumpEntryId != null && session.sourceTabId != null,
            ) {
                val tabId = session.sourceTabId
                if (jumpEntryId != null && tabId != null) {
                    state.navigateToLogLine(tabId, jumpEntryId)
                }
                view.canvasContextMenuMessageId = null
                view.canvasContextMenuOccurrenceEntryId = null
            }
            CtxItem(Icons.Outlined.Edit, "Rename label") {
                seq3BeginLabelRename(view, document, messageId)
            }
            CtxItem(Icons.AutoMirrored.Outlined.StickyNote2, "Add note") {
                seq3AddNote(
                    state,
                    session,
                    view,
                    document,
                    selectedIds,
                    placement = view.canvasContextMenuCanvasPoint,
                )
            }
            CtxItem(Icons.Outlined.Schedule, "Insert delay after this") {
                // User-observed correction: pass the exact occurrence the right-click hit
                // (`occurrenceEntryId`, resolved above) rather than only `messageId` — a message
                // that repeats used to always land the delay after its LAST occurrence, no matter
                // which specific row's context menu this was opened from.
                seq3InsertDelayAfter(state, session, messageId, afterOccurrenceEntryId = occurrenceEntryId)
                view.canvasContextMenuMessageId = null
                view.canvasContextMenuOccurrenceEntryId = null
            }
            if (seq3CanGroupSelection(document, view, selectedIds)) {
                CtxDivider()
                // Replaces the old four "Group as loop/alt/opt/par" rows with every
                // Seq3FragmentKind — the header's own `Group ▾` (Seq3Workspace.kt's
                // Seq3ContextualSelectionActions) already offered all seven, so the canvas menu's
                // shorter list was a real gap, not a deliberate trim. The row's own click (the log
                // view's convention: clicking the row runs the default action) groups as LOOP,
                // the most common case; the other six live only in the submenu.
                CtxItemWithSubmenu(
                    icon = Icons.Outlined.Layers,
                    label = "Group as",
                    submenu = Seq3FragmentKind.entries.map { kind ->
                        CtxSubmenuOption(kind.name.lowercase()) { seq3GroupMessages(state, session, view, selectedIds, kind) }
                    },
                    preferLeft = submenuOpensLeft,
                    onClick = { seq3GroupMessages(state, session, view, selectedIds, Seq3FragmentKind.LOOP) },
                )
            }
            message?.let {
                val occurrence = occurrenceEntryId?.let { entryId ->
                    it.occurrences.firstOrNull { occ -> occ.entryId == entryId }
                }
                val hidden = occurrence?.visibility == Seq3Visibility.HIDDEN ||
                    (occurrence == null && it.visibility == Seq3Visibility.HIDDEN)
                CtxDivider()
                CtxItem(
                    if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    if (hidden) "Show message" else "Hide message",
                ) {
                    if (occurrence != null) {
                        state.seq3Sessions.applyCommand(
                            session.id,
                            Seq3Command.SetOccurrenceVisibility(
                                messageId,
                                occurrence.entryId,
                                if (hidden) Seq3Visibility.VISIBLE else Seq3Visibility.HIDDEN,
                            ),
                        )
                    } else {
                        state.seq3Sessions.applyCommand(
                            session.id,
                            Seq3Command.Bulk(
                                setOf(messageId),
                                if (hidden) Seq3BulkAction.Show else Seq3BulkAction.Hide,
                            ),
                        )
                    }
                    view.canvasContextMenuMessageId = null
                    view.canvasContextMenuOccurrenceEntryId = null
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** WP7 item 5 (canvas half): the empty-canvas counterpart of [Seq3CanvasContextMenu] — opened by a
 *  right-click that hit no message row (`seq3RowAt` returned null). Only "Add note here" today; a
 *  future verb with no message-row precondition would belong here too. Same card chrome as
 *  [Seq3CanvasContextMenu] (264dp width, shadow, border) so the two match, but no header or
 *  preview block — nothing was right-clicked, so there is nothing to preview. */
@Composable
private fun Seq3CanvasEmptyContextMenu(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    document: Seq3Document,
) {
    if (!view.canvasEmptyContextMenuOpen) return
    val placement = view.canvasContextMenuCanvasPoint
    Popup(
        alignment = Alignment.TopStart,
        offset = view.canvasContextMenuOffset,
        onDismissRequest = { view.canvasEmptyContextMenuOpen = false },
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(SEQ3_CTX_MENU_WIDTH)
                .shadow(8.dp, RoundedCornerShape(7.dp))
                .background(tc().p, RoundedCornerShape(7.dp))
                .border(1.dp, tc().br, RoundedCornerShape(7.dp))
                .padding(vertical = 4.dp),
        ) {
            CtxItem(Icons.AutoMirrored.Outlined.StickyNote2, "Add note here") {
                // Explicit emptySet() rather than the canvas' current message selection — a
                // free-floating note dropped on empty background is never meant to span whatever
                // happened to be selected before the right-click.
                seq3AddNote(state, session, view, document, emptySet(), placement = placement)
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
    docTheme: ThemeColors,
) {
    val density = LocalDensity.current.density
    var editing by remember(session.id, note.noteId) { mutableStateOf(false) }
    var text by remember(session.id, note.noteId, note.text) { mutableStateOf(note.text) }
    var moveDelta by remember(session.id, note.noteId) { mutableStateOf(Offset.Zero) }
    var resizeDelta by remember(session.id, note.noteId) { mutableStateOf(Offset.Zero) }
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

    fun deleteNote() {
        state.seq3Sessions.applyCommand(
            session.id,
            Seq3Command.Bulk(emptySet(), Seq3BulkAction.DeleteNote(note.noteId)),
        )
    }

    fun cancelText() {
        text = note.text
        editing = false
    }

    Box(
        Modifier.offset(movedBox.x.dp, movedBox.y.dp)
            .size(movedBox.width.dp, movedBox.height.dp)
            .background(docTheme.seq2.copy(alpha = NOTE_FILL_ALPHA), CORNER_SM)
            .border(1.dp, docTheme.seq2, CORNER_SM)
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
                detectTapGestures(
                    onTap = { seq3ClearSelection(view, clearFocus = true) },
                    onDoubleTap = { editing = true },
                )
            },
    ) {
        if (editing) {
            Column(Modifier.fillMaxSize().padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                InlineField(
                    value = text,
                    onValue = { text = it },
                    fontSize = 10.sp,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .onFocusChanged { view.textFieldFocused = it.hasFocus },
                    onSubmit = ::commitText,
                    onCancel = ::cancelText,
                )
                // Reserve the resize handle's corner so the reject button remains a separate,
                // clickable target while the note editor is open.
                Row(
                    Modifier.fillMaxWidth().padding(end = NOTE_RESIZE_HANDLE_RESERVED_DP),
                    horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End),
                ) {
                    SquareIconButton("✓", fontSize = 10.sp, onClick = ::commitText, size = 16.dp)
                    SquareIconButton("×", fontSize = 10.sp, onClick = ::cancelText, size = 16.dp)
                }
            }
        } else {
            AppText(note.text, color = docTheme.tx, fontSize = 10.sp, maxLines = 4, modifier = Modifier.padding(4.dp))
            Row(
                Modifier.align(Alignment.TopEnd).padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SquareIconButton("✎", fontSize = 10.sp, onClick = { editing = true }, size = 16.dp)
                SquareIconButton("×", fontSize = 11.sp, onClick = ::deleteNote, size = 16.dp)
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd)
                .size(12.dp)
                .background(docTheme.seq2, RoundedCornerShape(topStart = 4.dp))
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

private val NOTE_RESIZE_HANDLE_RESERVED_DP = 16.dp

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

    fun close() {
        view.editingLabelMessageId = null
        view.editingLabelOccurrenceEntryId = null
    }

    fun commit() {
        state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(setOf(messageId), Seq3BulkAction.SetLabel(text)))
        close()
    }

    fun cancel() {
        text = currentLabelTemplate
        close()
    }
    Row(
        Modifier.offset(box.x.dp, (box.y - LABEL_EDITOR_Y_OFFSET_DP).dp)
            .background(tc.p, CORNER_SM).border(1.dp, tc.ac, CORNER_SM).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineField(
            value = text,
            onValue = { text = it },
            fontSize = 10.sp,
            modifier = Modifier.width(140.dp).onFocusChanged { view.textFieldFocused = it.hasFocus },
            onSubmit = ::commit,
            onCancel = ::cancel,
        )
        SquareIconButton("✓", fontSize = 10.sp, onClick = ::commit, size = 16.dp)
        SquareIconButton("×", fontSize = 10.sp, onClick = ::cancel, size = 16.dp)
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
    docTheme: ThemeColors,
) {
    val currentDocument = rememberUpdatedState(document)
    val currentLayout = rememberUpdatedState(layout)
    val currentColumn = rememberUpdatedState(column)
    var dragPx by remember(column.lifelineId) { mutableStateOf(0f) }
    var renaming by remember(column.lifelineId) { mutableStateOf(false) }
    var renameText by remember(column.lifelineId) { mutableStateOf(column.label) }
    val selected = column.lifelineId == view.selectedLifelineId
    val isActor = column.kind == Seq3LifelineKind.ACTOR

    // WP7 item 6: this used to be the only inline editor in the workspace with no accept/reject
    // buttons, no blank-name guard, and — most importantly — no view.textFieldFocused wiring, so
    // the single-letter key map (h/m/g/…) fired while typing a rename. Brought in line with the
    // other three canvas editors (Seq3FragmentLabelOverlay is the model).
    fun commitRename() {
        if (renameText.isNotBlank()) {
            state.seq3Sessions.applyCommand(session.id, Seq3Command.RenameLifeline(column.lifelineId, renameText))
        }
        renaming = false
    }

    fun cancelRename() {
        renameText = column.label
        renaming = false
    }

    // A plain Box, NOT a Column: both children below use their OWN absolute `offset` (derived
    // straight from column.header's unit-less coordinates), matching every other absolutely
    // positioned overlay in this file. A Column would first stack them in flow order and then
    // apply each child's offset ON TOP of that flow position, silently double-offsetting the
    // second child — a Box's default per-child placement is (0,0), so two independently offset
    // children never interfere with each other, the same way the sole pre-existing child never did.
    Box {
        if (isActor) {
            Canvas(
                Modifier
                    .offset {
                        IntOffset(
                            (column.header.x * density + dragPx).roundToInt(),
                            ((column.header.y - SEQ3_ACTOR_GLYPH_RESERVE_DP) * density).roundToInt(),
                        )
                    }
                    .size(column.header.width.dp, SEQ3_ACTOR_GLYPH_RESERVE_DP.dp),
            ) {
                drawSeq3ActorGlyph(if (selected) docTheme.ac else docTheme.br)
            }
        }
        if (renaming) {
            // Task 1 fix (rename editing surface): the static chip's `header.width`/`header.height`
            // box is sized for a short, already-committed label (HEADER_MIN_W/HEADER_MAX_W in
            // Seq3Layout.kt run ~90–200dp) and the old code squeezed the 100dp field + two 16dp
            // buttons into that same box — on a narrow header the row's own content (~140dp)
            // overflowed the box with no backdrop behind the overflow. This is a fully separate,
            // wider, self-painted surface instead of reusing the static chip's geometry: it stays
            // anchored to the SAME lifeline (centered on `column.centerX`, same `header.y`) but is
            // free to be wider than any header, and it owns no drag-to-reorder pointerInput — that
            // gesture belongs to the static chip only, which is absent from composition while
            // renaming, so there is no drag/tap competition with the text field.
            Box(
                Modifier.offset {
                    IntOffset(
                        ((column.centerX - SEQ3_RENAME_SURFACE_WIDTH_DP / 2) * density).roundToInt(),
                        (column.header.y * density).roundToInt(),
                    )
                },
            ) {
                Row(
                    Modifier
                        .width(SEQ3_RENAME_SURFACE_WIDTH_DP.dp)
                        .height(column.header.height.dp)
                        .background(docTheme.p2, RoundedCornerShape(4.dp))
                        .border(1.dp, docTheme.ac, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InlineField(
                        value = renameText,
                        onValue = { renameText = it },
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f).onFocusChanged { view.textFieldFocused = it.hasFocus },
                        onSubmit = ::commitRename,
                        onCancel = ::cancelRename,
                    )
                }
                // The ✓/× buttons sit OUTSIDE the editing surface's trailing edge (an .offset past
                // where the surface's own border ends) rather than sharing the surface's cramped
                // width with the text field — CenterStart vertically centers them against the
                // surface's own height since the surface is always at least as tall as these 16dp
                // buttons (column.header.height is sized for a full text line).
                Row(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = (SEQ3_RENAME_SURFACE_WIDTH_DP + 4f).dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    SquareIconButton("✓", fontSize = 10.sp, onClick = ::commitRename, size = 16.dp)
                    SquareIconButton("×", fontSize = 10.sp, onClick = ::cancelRename, size = 16.dp)
                }
            }
        } else {
            Box(
                Modifier
                    .offset { IntOffset((column.header.x * density + dragPx).roundToInt(), (column.header.y * density).roundToInt()) }
                    .size(column.header.width.dp, column.header.height.dp)
                    .let {
                        // Item 2 (actor glyph): "a stick figure ABOVE THE NAME INSTEAD OF the rounded
                        // chip" — an actor column skips the fill/border entirely rather than drawing
                        // an (unwanted) box behind its name, mirroring Seq3Raster's paintActorGlyph.
                        if (isActor) it else it.background(if (selected) docTheme.abg else docTheme.p2, RoundedCornerShape(4.dp))
                            .border(1.dp, if (selected) docTheme.ac else docTheme.br, RoundedCornerShape(4.dp))
                    }
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
                                seq3ClearSelection(view, clearFocus = true)
                                view.selectedLifelineId = column.lifelineId
                                tryAwaitRelease()
                            },
                            onTap = { view.selectedLifelineId = column.lifelineId },
                            onDoubleTap = { renaming = true },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Item 2: labelLines is already wrapped/ellipsized by Seq3Layout against this exact
                // header width — draw every line it produced, not just column.label's single-line
                // raw name (see Seq3LifelineColumn's own doc on why the two are kept distinct).
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    column.labelLines.ifEmpty { listOf(column.label) }.forEach { line ->
                        AppText(
                            line, color = if (selected) docTheme.ac else docTheme.tx, fontSize = SEQ3_LIFELINE_FONT_SIZE,
                            fontFamily = SEQ3_MEASURED_FONT_FAMILY, fontWeight = FontWeight.Medium, maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

// Task 1 fix: a fixed, generous width for the rename editing surface — independent of
// `column.header.width` (90–200dp per HEADER_MIN_W/HEADER_MAX_W) so there is always comfortable
// room to type a longer name than the committed label, with the ✓/× buttons offset past its
// trailing edge rather than sharing this width with the text field.
private const val SEQ3_RENAME_SURFACE_WIDTH_DP = 172f

// Item 2 (WP2 font-mismatch fix): Seq3Layout measures a lifeline header at Seq3FontRole.LIFELINE's
// basePointSize (13pt) — this chip used to draw at 11.sp, so header wrapping computed by layout
// (against the 13pt-measured width) would not match what Compose actually painted. See
// SEQ3_LABEL_FONT_SIZE's own doc for why aligning the draw size to the measured size, not the
// other way around, is the fix — and SEQ3_MEASURED_FONT_FAMILY's own doc (item 9) for why the
// font *family* has to be aligned the same way, not just the size.
private val SEQ3_LIFELINE_FONT_SIZE = Seq3FontRole.LIFELINE.basePointSize.sp

// Item 2 (actor glyph): how tall a band this file draws the stick figure within, above the name
// box — mirrors Seq3Raster's own ACTOR_GLYPH_H + ACTOR_GLYPH_GAP (26 + 4), independently chosen
// here (not read off Seq3Layout) for the same reason the raster's own constants aren't threaded
// through Seq3Layout either: layout only reserves the SPACE (its ACTOR_HEADER_RESERVE, 34 units),
// painting proportions inside that space are each renderer's own call — see Seq3LifelineColumn's
// own doc. Kept comfortably under 34 so the glyph never crowds the name box above it.
private const val SEQ3_ACTOR_GLYPH_RESERVE_DP = 30f

// Task 2 fix: the figure's OWN proportions, fixed — mirrors Seq3Raster's fixed ACTOR_GLYPH_W/H
// constants exactly (16dp / 26dp). These must never be derived from `size.width`/`size.height`
// (the caller's DrawScope size, which is the column's own header width/[SEQ3_ACTOR_GLYPH_RESERVE_DP]
// reserve band) — that was the bug: a wide header inflated `headR` far past what the fixed-height
// reserve band can hold, driving `legSplit` above `bodyTop` and drawing the body/legs backward
// through the head. See [seq3ActorGlyphGeometry]'s own doc and Seq3CanvasTest's regression test.
private const val ACTOR_GLYPH_W_DP = 16f
private const val ACTOR_GLYPH_H_DP = 26f

/** Pure geometry for the actor stick-figure glyph, extracted out of [drawSeq3ActorGlyph] so
 *  [Seq3CanvasTest] can pin it down without a live Compose `DrawScope`/`Density`. Mirrors
 *  Seq3Raster's own `paintActorGlyph` math exactly, substituting a local origin of `glyphTop = 0`
 *  for the raster's `col.header.y - ACTOR_GLYPH_GAP - ACTOR_GLYPH_H` (this file draws inside a
 *  Canvas already offset/sized to that band, so the band's own top IS this function's origin).
 *
 *  [boxWidthPx] is the caller's own DrawScope width — i.e. the column's header width in px, which
 *  can be 90–200dp+ depending on the lifeline's name. It is accepted here ONLY to make explicit,
 *  at the one call site and in this function's test, that it is NOT used to size the figure: every
 *  dimension below comes exclusively from [glyphWidthPx]/[glyphHeightPx], fixed px values the
 *  caller derives from [ACTOR_GLYPH_W_DP]/[ACTOR_GLYPH_H_DP] via `.dp.toPx()`. That is the fix for
 *  the bug where `headR` used to be `size.width / 8`. */
internal fun seq3ActorGlyphGeometry(
    @Suppress("UNUSED_PARAMETER") boxWidthPx: Float,
    glyphWidthPx: Float,
    glyphHeightPx: Float,
): Seq3ActorGlyphGeometry {
    val headR = glyphWidthPx / 4f
    val headCenterY = headR
    val bodyTop = headCenterY + headR
    val glyphBottom = glyphHeightPx
    val legSplit = glyphBottom - glyphHeightPx * 0.28f
    val armY = bodyTop + (legSplit - bodyTop) * 0.35f
    return Seq3ActorGlyphGeometry(
        headR = headR,
        headCenterY = headCenterY,
        bodyTop = bodyTop,
        legSplit = legSplit,
        armY = armY,
        glyphBottom = glyphBottom,
    )
}

internal data class Seq3ActorGlyphGeometry(
    val headR: Float,
    val headCenterY: Float,
    val bodyTop: Float,
    val legSplit: Float,
    val armY: Float,
    val glyphBottom: Float,
)

/** Item 2 (actor glyph): a plain stick figure — circle head, line body/arms/legs — drawn at a
 *  FIXED size (see [seq3ActorGlyphGeometry]) within this DrawScope's own size (set by the caller
 *  to [SEQ3_ACTOR_GLYPH_RESERVE_DP] tall, the column's header width wide — only `cx` below uses
 *  that width, to center the figure horizontally within it). Mirrors Seq3Raster's `paintActorGlyph`
 *  shape so the on-screen glyph and the exported PNG's glyph read as the same figure at the same
 *  proportions, regardless of how wide the lifeline's header is. */
private fun DrawScope.drawSeq3ActorGlyph(color: Color) {
    val cx = size.width / 2
    val glyphWidthPx = ACTOR_GLYPH_W_DP.dp.toPx()
    val glyphHeightPx = ACTOR_GLYPH_H_DP.dp.toPx()
    val geometry = seq3ActorGlyphGeometry(size.width, glyphWidthPx, glyphHeightPx)
    val halfWidth = glyphWidthPx / 2
    val stroke = Stroke(width = 1.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
    drawCircle(color, radius = geometry.headR, center = Offset(cx, geometry.headCenterY), style = stroke)
    drawLine(color, Offset(cx, geometry.bodyTop), Offset(cx, geometry.legSplit), strokeWidth = stroke.width, cap = stroke.cap)
    drawLine(color, Offset(cx - halfWidth, geometry.armY), Offset(cx + halfWidth, geometry.armY), strokeWidth = stroke.width, cap = stroke.cap)
    drawLine(color, Offset(cx, geometry.legSplit), Offset(cx - halfWidth, geometry.glyphBottom), strokeWidth = stroke.width, cap = stroke.cap)
    drawLine(color, Offset(cx, geometry.legSplit), Offset(cx + halfWidth, geometry.glyphBottom), strokeWidth = stroke.width, cap = stroke.cap)
}

// ── Pure helpers — testable without a composition (Seq3CanvasTest) ─────────────────────────────

// How far from an arrow the canvas still reacts to the pointer — hover emphasis, click selection,
// and (via seq3IsEmptyCanvasBackground) whether a press counts as "on a row" instead of background.
//
// These were 18 and 24. Rows sit only ROW_H (42) apart, so ±18 meant 36 of every 42 units belonged
// to some row: the canvas lit up and selected a line while the pointer was visibly in the gap
// beside it, and there was almost nowhere left to press that counted as background. Halved and
// then some, so the reactive band is 16 of 42 and the gap between two arrows is genuinely inert.
internal const val SEQ3_ROW_HIT_Y_TOLERANCE = 8.0
internal const val SEQ3_ROW_HIT_X_TOLERANCE = 10.0

/** How close to an arrow a press may land and still START A RUBBER BAND, as opposed to counting as
 *  a press on that arrow ([SEQ3_ROW_HIT_Y_TOLERANCE]).
 *
 *  Kept a little tighter than the click tolerance so the band still yields to a deliberate press on
 *  a line, while a press that never moves resolves through the ordinary click path on release
 *  anyway — see the `selectingArea` release branch — so nothing is lost by arming eagerly here. */
internal const val SEQ3_BAND_START_Y_TOLERANCE = 6.0

// An arrow's endpoint handles sit ON the lifelines, and the left margin beside the first lifeline is
// exactly where a rubber band gets started. At 36 this claimed a 72-wide strip around every
// lifeline in which a press was an endpoint grab, so no band could arm and the row still reacted —
// the "big reaction zone" that survived shrinking the row tolerances alone. 18 still gives the
// handle a wide target relative to the 1px line it decorates.
internal const val SEQ3_ENDPOINT_HIT_TOLERANCE_X = 18.0

internal enum class Seq3EndpointSide { FROM, TO }

internal data class Seq3DragEndpoint(
    val messageId: String,
    val side: Seq3EndpointSide,
    /** Stable row identity for repeated occurrences; null keeps compatibility for one-row/custom messages. */
    val occurrenceEntryId: Int? = null,
    /** WP7 item 2: true when this endpoint was resolved from an unresolved stub rather than an
     *  existing arrow/self-loop's own FROM/TO handle. [side] is FROM either way (a stub drag
     *  resolves the CALLER), but release must still tell the two apart: a stub drop dispatches
     *  [com.indagium.diagram3.Seq3BulkAction.SetCaller] (sets BOTH endpoints in one command),
     *  never the plain [com.indagium.diagram3.Seq3BulkAction.SetFrom] used for an ordinary arrow's
     *  FROM handle — that would leave `to` at null and the message would still read as
     *  needs-target. */
    val isStub: Boolean = false,
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
        // WP7 item 2: the stub (and its pill) now draw to the LEFT of its lifeline, so the pill's
        // own LEFT edge (row.dropPill.x) — not its right edge — is the far/outer boundary of the
        // touchable span; row.fromX (the lifeline) is the near one. Bracket with min/max rather
        // than assume either order, so this stays correct regardless of which is numerically larger.
        val pillFar = row.dropPill.x
        val lo = min(row.fromX, pillFar)
        val hi = max(row.fromX, pillFar)
        if (x in lo..hi) 0.0 else min(kotlin.math.abs(x - row.fromX), kotlin.math.abs(x - pillFar))
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
 *  handle, or an unresolved stub's whole line/pill (WP7 item 2: always the FROM side, with
 *  [Seq3DragEndpoint.isStub] set — dragging a stub resolves its CALLER, not a target). Null
 *  anywhere else on the canvas, which lets the caller fall through to a plain click. */
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
                // WP7 item 2: dragging a stub resolves the CALLER, so this is always a FROM
                // endpoint (see Seq3DragEndpoint.isStub's own doc for why release must still tell
                // it apart from an ordinary arrow's FROM handle). The pill sits to the LEFT of
                // fromX now, so its LEFT edge (not right) is the far/outer boundary — see
                // seq3RowXDistance's own doc on the same fix.
                val pillFar = row.dropPill.x
                val lo = min(row.fromX, pillFar)
                val hi = max(row.fromX, pillFar)
                val onStub = kotlin.math.abs(y - row.y) <= SEQ3_ROW_HIT_Y_TOLERANCE &&
                    (x in lo..hi || seq3PointInBox(row.dropPill, x, y))
                if (onStub) Candidate(
                    Seq3DragEndpoint(row.messageId, Seq3EndpointSide.FROM, row.occurrenceEntryId, isStub = true),
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
internal fun seq3IsEmptyCanvasBackground(
    layout: Seq3Layout,
    x: Double,
    y: Double,
    // Defaulted so pan-arming and every existing caller keep the forgiving click tolerance; only
    // rubber-band arming passes the tighter [SEQ3_BAND_START_Y_TOLERANCE].
    yTolerance: Double = SEQ3_ROW_HIT_Y_TOLERANCE,
): Boolean =
    seq3RowAt(layout, x, y, yTolerance) == null &&
        seq3ResolveDragEndpoint(layout, x, y) == null &&
        layout.lifelines.none { seq3PointInBox(it.header, x, y) } &&
        layout.notes.none { seq3PointInBox(it.box, x, y) } &&
        layout.fragments.none { seq3PointInBox(it.box, x, y) } &&
        layout.delays.none { seq3PointInBox(it.box, x, y) }

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

/** WP7 item 4: `view.canvasContextMenuOffset` positions a `Popup` composed OUTSIDE the scrolled
 *  diagram content (a sibling of the scroll containers, in `Seq3CanvasContent`'s own
 *  `BoxWithConstraints`), while [change].position (the raw pointer event this file reads) is
 *  measured INSIDE that scrolled content — i.e. relative to the full diagram, not the visible
 *  viewport. On an unscrolled canvas the two coincide, but scroll away from the origin and the
 *  menu drifts by exactly the scroll offset. This is the same "subtract scroll before using a raw
 *  pointer pixel" correction every hit-test already gets via [seq3PointerPxToLayoutUnits] (which
 *  needs no scroll subtraction of its own — layout coordinates are absolute diagram-space, not
 *  viewport-anchored) and the pan-drag gesture's own `panViewportPosition` closure already applies
 *  for exactly this reason. Clamped to non-negative so a menu opened right at the viewport edge
 *  never requests a negative `Popup` offset. */
internal fun seq3ContextMenuOffset(pointerPx: Offset, hScrollPx: Int, vScrollPx: Int): IntOffset = IntOffset(
    (pointerPx.x - hScrollPx).roundToInt().coerceAtLeast(0),
    (pointerPx.y - vScrollPx).roundToInt().coerceAtLeast(0),
)

internal fun seq3ZoomPercentLabel(zoom: Float): String = "${(zoom * PERCENT).roundToInt()}%"

internal fun seq3ZoomByWheel(currentZoom: Float, scrollDeltaY: Float): Float {
    val direction = when {
        scrollDeltaY < 0f -> 1f
        scrollDeltaY > 0f -> -1f
        else -> 0f
    }
    return (currentZoom + direction * ZOOM_STEP).coerceIn(MIN_ZOOM, MAX_ZOOM)
}

internal fun seq3CrossingLabel(count: Int): String = when (count) {
    0 -> "No arrow crossings"
    1 -> "1 arrow crossing"
    else -> "$count arrow crossings"
}
