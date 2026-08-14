@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.indagium.diagram3.Seq3ArrowRow
import com.indagium.diagram3.Seq3Box
import com.indagium.diagram3.Seq3BulkAction
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3ElisionRow
import com.indagium.diagram3.Seq3Filter
import com.indagium.diagram3.Seq3FragmentBox
import com.indagium.diagram3.Seq3Layout
import com.indagium.diagram3.Seq3LifelineColumn
import com.indagium.diagram3.Seq3MessageNoteRow
import com.indagium.diagram3.Seq3NoteBox
import com.indagium.diagram3.Seq3RowGeometry
import com.indagium.diagram3.Seq3Selection
import com.indagium.diagram3.Seq3SelfLoopRow
import com.indagium.diagram3.Seq3UnresolvedStubRow
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.seq3Select
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ── The canvas — design spec §04 + §07 ──────────────────────────────────────────────────────────
//
// NATIVE Compose `Canvas` + overlay composables, drawing ONLY from `Seq3RenderCache.layout(
// document)` — the same layout `Seq3Raster.kt` rasterizes for PNG export (this phase's second
// absolute rule). Phase 3's skeleton drew a rasterized AWT bitmap here instead; that bitmap path
// now survives ONLY for headless PNG export (Seq3Raster.kt/Seq3RenderCache.render/display/
// pngBytes) — this file never calls those, and never calls `layoutSeq3` a second time itself.
//
// Zoom is a single `Modifier.graphicsLayer(scaleX/scaleY)` around the whole unscaled content —
// Compose inverse-transforms pointer positions through a graphicsLayer automatically, so every
// hit-test below still operates in the layout's own unit-less coordinates (1 unit == 1 dp) without
// this file ever multiplying a click position by the current zoom by hand.

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
    val layout = remember(document) { Seq3RenderCache.layout(document) }
    Column(modifier.fillMaxSize().background(tc.bg)) {
        Seq3CanvasToolbar(view, layout)
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

@Composable
private fun Seq3CanvasToolbar(view: Seq3ViewState, layout: Seq3Layout) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().background(tc.p).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppText("Canvas", color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            // Design spec §07: "a crossing count that flags a bad arrangement" — straight off
            // Seq3Layout's own `crossingCount`, recomputed by layoutSeq3 whenever a lifeline drag
            // changes `Seq3Lifeline.ordinal` (see Seq3Layout.kt's own doc on that field).
            AppText(
                seq3CrossingLabel(layout.crossingCount),
                color = if (layout.crossingCount > 0) tc.warn else tc.ts, fontSize = 10.sp,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Seq3ZoomStepButton("−") { view.zoom = (view.zoom - ZOOM_STEP).coerceAtLeast(MIN_ZOOM); view.zoomMode = Seq3ZoomMode.MANUAL }
                AppText(seq3ZoomPercentLabel(view.zoom), color = tc.ts, fontSize = 11.sp, modifier = Modifier.width(36.dp))
                Seq3ZoomStepButton("+") { view.zoom = (view.zoom + ZOOM_STEP).coerceAtMost(MAX_ZOOM); view.zoomMode = Seq3ZoomMode.MANUAL }
            }
            SegmentedControl(
                options = listOf("Fit", "Fit width", "Reset"),
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
}

@Composable
private fun Seq3ZoomStepButton(label: String, onClick: () -> Unit) {
    val tc = tc()
    Box(
        Modifier.size(20.dp).background(tc.p2, CORNER_SM).border(1.dp, tc.br, CORNER_SM).clip(CORNER_SM).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { AppText(label, color = tc.ts, fontSize = 12.sp) }
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportWidth = maxWidth.value.toDouble()
        val viewportHeight = maxHeight.value.toDouble()
        LaunchedEffect(layout, viewportWidth, viewportHeight, view.zoomMode) {
            if (viewportWidth <= 0.0 || viewportHeight <= 0.0) return@LaunchedEffect
            val target = when (view.zoomMode) {
                Seq3ZoomMode.FIT -> seq3FitZoom(layout.width, layout.height, viewportWidth, viewportHeight)
                Seq3ZoomMode.FIT_WIDTH -> seq3FitWidthZoom(layout.width, viewportWidth)
                Seq3ZoomMode.MANUAL -> null
            }
            if (target != null) view.zoom = target
        }
        Box(Modifier.fillMaxSize().horizontalScroll(hScroll).verticalScroll(vScroll)) {
            val zoom = view.zoom
            Box(Modifier.size((layout.width * zoom).dp, (layout.height * zoom).dp)) {
                Box(
                    Modifier.size(layout.width.dp, layout.height.dp)
                        .graphicsLayer(scaleX = zoom, scaleY = zoom, transformOrigin = TransformOrigin(0f, 0f))
                        .then(seq3CanvasGestureModifier(state, session, view, document, layout, density)),
                ) {
                    Canvas(Modifier.size(layout.width.dp, layout.height.dp)) {
                        drawSeq3Diagram(layout, tc, view.hoveredMessageId, view.selection.selectedIds)
                    }
                    layout.fragments.forEach { fragment -> Seq3FragmentLabelOverlay(fragment) }
                    layout.rows.forEach { row -> Seq3RowOverlay(state, session, view, row) }
                    layout.notes.forEach { note -> Seq3NoteTextOverlay(note) }
                    layout.lifelines.forEach { column -> Seq3LifelineChip(state, session, view, document, layout, column, density) }
                }
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
    document: Seq3Document,
    layout: Seq3Layout,
    density: Float,
): Modifier = Modifier
    .pointerInput(session.id, document, layout, density) {
        awaitPointerEventScope {
            var dragEndpoint: Seq3DragEndpoint? = null
            var downPosition: Offset? = null
            var moved = false
            var lastClickTimeMs = 0L
            var lastClickMessageId: String? = null
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                // Pointer deltas are in PIXELS (CLAUDE.md's own gotcha) — divide by density to get
                // back to the layout's own unit-less coordinates (1 unit == 1 dp) before hit-testing.
                val xUnits = (change.position.x / density).toDouble()
                val yUnits = (change.position.y / density).toDouble()
                when (event.type) {
                    PointerEventType.Press -> if (event.buttons.isPrimaryPressed) {
                        downPosition = change.position
                        moved = false
                        dragEndpoint = seq3ResolveDragEndpoint(layout, xUnits, yUnits)
                        if (dragEndpoint != null) change.consume()
                    }
                    PointerEventType.Move -> {
                        val down = downPosition
                        if (down != null && (change.position - down).getDistance() > DRAG_CLICK_THRESHOLD_PX) moved = true
                        if (dragEndpoint != null) {
                            change.consume()
                        } else if (downPosition == null) {
                            view.hoveredMessageId = seq3RowAt(layout, xUnits, yUnits)?.messageId
                        }
                    }
                    PointerEventType.Release -> {
                        val drag = dragEndpoint
                        if (drag != null) {
                            seq3NearestLifelineId(layout, xUnits)?.let { targetLifelineId ->
                                val action = if (drag.side == Seq3EndpointSide.FROM) {
                                    Seq3BulkAction.SetFrom(targetLifelineId)
                                } else {
                                    Seq3BulkAction.SetTo(targetLifelineId)
                                }
                                state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(setOf(drag.messageId), action))
                            }
                            dragEndpoint = null
                        } else if (!moved) {
                            val hitRow = seq3RowAt(layout, xUnits, yUnits)
                            if (hitRow != null) seq3HandleCanvasRowClick(view, document, hitRow, event) { now, id ->
                                val doubleClick = now - lastClickTimeMs <= DOUBLE_CLICK_WINDOW_MS && lastClickMessageId == id
                                lastClickTimeMs = now
                                lastClickMessageId = id
                                doubleClick
                            }
                        }
                        downPosition = null
                    }
                    PointerEventType.Exit -> view.hoveredMessageId = null
                    else -> Unit
                }
            }
        }
    }

private fun seq3HandleCanvasRowClick(
    view: Seq3ViewState,
    document: Seq3Document,
    hitRow: Seq3RowGeometry,
    event: PointerEvent,
    registerClick: (nowMs: Long, id: String) -> Boolean,
) {
    val mods = event.keyboardModifiers
    val allIds = document.messages.map { it.id }
    if (mods.isShiftPressed || mods.isMetaPressed || mods.isCtrlPressed) {
        view.selection = seq3Select(allIds, view.selection, hitRow.messageId, mods.isMetaPressed || mods.isCtrlPressed, mods.isShiftPressed)
    } else {
        // A plain click always resolves the row into view even when the current queue
        // filter/text hides it (spec §04) — reset the view here, BEFORE requesting the scroll,
        // so Seq3QueuePanel's own effect finds the row on the very next recomposition.
        view.filter = Seq3Filter.ALL
        view.textFilter = ""
        view.selection = Seq3Selection(setOf(hitRow.messageId), hitRow.messageId)
    }
    view.inspectorMessageId = hitRow.messageId
    view.scrollRequestId = hitRow.messageId
    val doubleClick = registerClick(System.currentTimeMillis(), hitRow.messageId)
    if (doubleClick && hitRow is Seq3ArrowRow) view.editingLabelMessageId = hitRow.messageId
}

// ── Line/shape drawing — everything from `Seq3Layout`'s own unit-less coordinates ─────────────

private fun DrawScope.drawSeq3Diagram(layout: Seq3Layout, tc: ThemeColors, hoveredMessageId: String?, selectedIds: Set<String>) {
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
        val emphasized = row.messageId == hoveredMessageId || row.messageId in selectedIds
        val arrowColor = if (emphasized) tc.ac else tc.ts
        when (row) {
            is Seq3ArrowRow -> drawSeq3Arrow(row.fromX, row.toX, row.y, arrowColor, if (emphasized) 2.dp.toPx() else 1.5.dp.toPx())
            is Seq3SelfLoopRow -> drawSeq3SelfLoop(row, arrowColor)
            is Seq3UnresolvedStubRow -> drawLine(
                color = tc.warn,
                start = Offset(row.fromX.dp.toPx(), row.y.dp.toPx()),
                end = Offset(row.stubEndX.dp.toPx(), row.y.dp.toPx()),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
            )
            is Seq3MessageNoteRow -> {
                val topLeft = Offset(row.box.x.dp.toPx(), row.box.y.dp.toPx())
                val size = Size(row.box.width.dp.toPx(), row.box.height.dp.toPx())
                drawRect(color = tc.seq2.copy(alpha = NOTE_FILL_ALPHA), topLeft = topLeft, size = size)
                drawRect(color = tc.seq2, topLeft = topLeft, size = size, style = Stroke(width = 1.dp.toPx()))
            }
            is Seq3ElisionRow -> Unit // text-only marker, drawn by the overlay composable
        }
    }
}

private const val FRAGMENT_WASH_ALPHA = 0.07f
private const val NOTE_FILL_ALPHA = 0.20f
private const val ARROWHEAD_LEN_DP = 8f
private const val ARROWHEAD_HALF_DP = 4f

private fun DrawScope.drawSeq3Arrow(fromX: Double, toX: Double, y: Double, color: Color, strokeWidth: Float) {
    val start = Offset(fromX.dp.toPx(), y.dp.toPx())
    val end = Offset(toX.dp.toPx(), y.dp.toPx())
    drawLine(color, start, end, strokeWidth = strokeWidth)
    val dir = if (toX >= fromX) 1f else -1f
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
    val emphasized = row.messageId == view.hoveredMessageId || row.messageId in view.selection.selectedIds
    val labelColor = if (emphasized) tc.ac else tc.tx
    when (row) {
        is Seq3ArrowRow -> {
            Seq3LabelText(row.labelBox, row.label, labelColor)
            row.badgeBox?.let { Seq3BadgeChip(it, row.repeatCount) }
            if (view.editingLabelMessageId == row.messageId) {
                Seq3InlineLabelEditor(state, session, view, row.messageId, row.label, row.labelBox)
            }
        }
        is Seq3SelfLoopRow -> {
            Seq3LabelText(row.labelBox, row.label, labelColor)
            row.badgeBox?.let { Seq3BadgeChip(it, row.repeatCount) }
        }
        is Seq3UnresolvedStubRow -> {
            Seq3LabelText(row.labelBox, row.label, tc.warn)
            Box(
                Modifier.offset(row.dropPill.x.dp, row.dropPill.y.dp).size(row.dropPill.width.dp, row.dropPill.height.dp)
                    .background(tc.warnBg, RoundedCornerShape(50)).border(1.dp, tc.warn, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) { AppText("drop on a lifeline", color = tc.warn, fontSize = 9.sp, maxLines = 1) }
        }
        is Seq3MessageNoteRow -> {
            Box(Modifier.offset(row.box.x.dp, row.box.y.dp).size(row.box.width.dp, row.box.height.dp).padding(3.dp)) {
                Column { row.lines.forEach { line -> AppText(line, color = tc.tx, fontSize = 10.sp, maxLines = 1) } }
            }
        }
        is Seq3ElisionRow -> Box(
            Modifier.offset(row.box.x.dp, row.box.y.dp).size(row.box.width.dp, row.box.height.dp),
            contentAlignment = Alignment.Center,
        ) { AppText("⋮ ×${row.elidedCount}", color = tc.td, fontSize = 9.sp) }
    }
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
private fun Seq3NoteTextOverlay(note: Seq3NoteBox) {
    val tc = tc()
    Box(Modifier.offset(note.box.x.dp, note.box.y.dp).size(note.box.width.dp, note.box.height.dp).padding(4.dp)) {
        AppText(note.text, color = tc.tx, fontSize = 10.sp, maxLines = 2)
    }
}

@Composable
private fun Seq3InlineLabelEditor(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    messageId: String,
    currentLabel: String,
    box: Seq3Box,
) {
    val tc = tc()
    var text by remember(messageId) { mutableStateOf(currentLabel) }

    fun commit() {
        state.seq3Sessions.applyCommand(session.id, Seq3Command.Bulk(setOf(messageId), Seq3BulkAction.SetLabel(text)))
        view.editingLabelMessageId = null
    }
    Row(
        Modifier.offset(box.x.dp, (box.y - LABEL_EDITOR_Y_OFFSET_DP).dp)
            .background(tc.p, CORNER_SM).border(1.dp, tc.ac, CORNER_SM).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineField(value = text, onValue = { text = it }, fontSize = 10.sp, modifier = Modifier.width(140.dp), onSubmit = ::commit)
        SquareIconButton("✓", fontSize = 10.sp, onClick = ::commit, size = 16.dp)
        SquareIconButton("×", fontSize = 10.sp, onClick = { view.editingLabelMessageId = null }, size = 16.dp)
    }
}

private const val LABEL_EDITOR_Y_OFFSET_DP = 20.0

// ── Lifeline header chips — draggable to reorder, double-click to rename, merge (spec §07) ────

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
                .pointerInput(session.id, column.lifelineId, layout, density) {
                    detectDragGestures(
                        onDragStart = { view.selectedLifelineId = column.lifelineId },
                        onDrag = { change, dragAmount -> change.consume(); dragPx += dragAmount.x },
                        onDragEnd = {
                            val order = document.lifelines.sortedBy { it.ordinal }.map { it.id }
                            val orderedColumns = layout.lifelines.sortedWith(compareBy { order.indexOf(it.lifelineId) })
                            val dropXUnits = column.header.x + dragPx / density + column.header.width / 2
                            val targetIndex = seq3LifelineDropIndex(orderedColumns.map { it.centerX }, dropXUnits)
                            val reordered = seq3ReorderLifelineIds(order, column.lifelineId, targetIndex)
                            if (reordered != order) state.seq3Sessions.applyCommand(session.id, Seq3Command.ReorderLifelines(reordered))
                            dragPx = 0f
                        },
                        onDragCancel = { dragPx = 0f },
                    )
                }
                .pointerInput(session.id, column.lifelineId) {
                    detectTapGestures(
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
        // "merge two lifelines that are the same actor under two tags" (spec §07) — click-based
        // (not right-click, matching every other verb in this file), reusing Seq3DropdownButton.
        if (document.lifelines.size > 1) {
            Box(
                Modifier.offset {
                    IntOffset((column.header.x * density + dragPx).roundToInt(), ((column.header.y + column.header.height) * density).roundToInt())
                },
            ) {
                Seq3DropdownButton(label = "merge", labelColor = tc.td, fillColor = Color.Transparent, menuWidth = 150.dp) { close ->
                    document.lifelines.filter { it.id != column.lifelineId }.sortedBy { it.ordinal }.forEach { other ->
                        Seq3DropdownMenuItem("Into ${other.name}") {
                            state.seq3Sessions.applyCommand(session.id, Seq3Command.MergeLifelines(other.id, column.lifelineId))
                            close()
                        }
                    }
                }
            }
        }
    }
}

// ── Pure helpers — testable without a composition (Seq3CanvasTest) ─────────────────────────────

internal const val SEQ3_ROW_HIT_Y_TOLERANCE = 16.0
internal const val SEQ3_ENDPOINT_HIT_TOLERANCE_X = 24.0

internal enum class Seq3EndpointSide { FROM, TO }

internal data class Seq3DragEndpoint(val messageId: String, val side: Seq3EndpointSide)

internal fun seq3PointInBox(box: Seq3Box, x: Double, y: Double): Boolean =
    x in box.x..(box.x + box.width) && y in box.y..(box.y + box.height)

/** Nearest row on [layout] whose own y sits within [yTolerance] of ([x], [y]) — the hit test behind
 *  a canvas click/hover, mirroring `SeqDiagramWorkspace.kt`'s pixel-space `resolveCanvasClickHit`
 *  but over the UNIT-LESS layout directly (this file never re-derives pixel geometry from a
 *  rasterized image, per this phase's second absolute rule). */
internal fun seq3RowAt(layout: Seq3Layout, x: Double, y: Double, yTolerance: Double = SEQ3_ROW_HIT_Y_TOLERANCE): Seq3RowGeometry? =
    layout.rows.filter { kotlin.math.abs(it.y - y) <= yTolerance }.minByOrNull { seq3RowXDistance(it, x) }

private fun seq3RowXDistance(row: Seq3RowGeometry, x: Double): Double = when (row) {
    is Seq3ArrowRow -> if (x in min(row.fromX, row.toX)..max(row.fromX, row.toX)) 0.0 else min(kotlin.math.abs(x - row.fromX), kotlin.math.abs(x - row.toX))
    is Seq3SelfLoopRow -> kotlin.math.abs(x - (row.x + row.loopWidth / 2))
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

/** Resolves a press at ([x], [y]) to an endpoint-drag start: either an existing arrow's FROM/TO
 *  handle, or an unresolved stub's whole line/pill (always the TO side — a stub has no target
 *  yet). Null anywhere else on the canvas, which lets the caller fall through to a plain click. */
internal fun seq3ResolveDragEndpoint(layout: Seq3Layout, x: Double, y: Double): Seq3DragEndpoint? {
    layout.rows.forEach { row ->
        when (row) {
            is Seq3ArrowRow -> seq3ArrowEndpointAt(row, x, y)?.let { return Seq3DragEndpoint(row.messageId, it) }
            is Seq3UnresolvedStubRow -> {
                val onStub = kotlin.math.abs(y - row.y) <= SEQ3_ROW_HIT_Y_TOLERANCE &&
                    (x in row.fromX..(row.dropPill.x + row.dropPill.width) || seq3PointInBox(row.dropPill, x, y))
                if (onStub) return Seq3DragEndpoint(row.messageId, Seq3EndpointSide.TO)
            }
            else -> Unit
        }
    }
    return null
}

/** "Dragging its head onto a lifeline resolves it" (spec §04) — always resolves to the nearest
 *  column; there is no minimum-distance gate because the drop pill/endpoint handle is already the
 *  explicit target, unlike a a stray click elsewhere on the canvas. */
internal fun seq3NearestLifelineId(layout: Seq3Layout, x: Double): String? =
    layout.lifelines.minByOrNull { kotlin.math.abs(it.centerX - x) }?.lifelineId

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
 *  whose center is to the right of the drop point, or the end of the list if none is. */
internal fun seq3LifelineDropIndex(centers: List<Double>, dropX: Double): Int =
    centers.indexOfFirst { dropX < it }.let { if (it < 0) centers.size else it }

internal fun seq3FitZoom(contentWidth: Double, contentHeight: Double, viewportWidth: Double, viewportHeight: Double): Float {
    if (contentWidth <= 0.0 || contentHeight <= 0.0 || viewportWidth <= 0.0 || viewportHeight <= 0.0) return 1f
    return min(viewportWidth / contentWidth, viewportHeight / contentHeight).toFloat().coerceIn(MIN_FIT_ZOOM, MAX_FIT_ZOOM)
}

internal fun seq3FitWidthZoom(contentWidth: Double, viewportWidth: Double): Float {
    if (contentWidth <= 0.0 || viewportWidth <= 0.0) return 1f
    return (viewportWidth / contentWidth).toFloat().coerceIn(MIN_FIT_ZOOM, MAX_FIT_ZOOM)
}

internal fun seq3ZoomPercentLabel(zoom: Float): String = "${(zoom * PERCENT).roundToInt()}%"

internal fun seq3CrossingLabel(count: Int): String = when (count) {
    0 -> "No crossings"
    1 -> "1 crossing"
    else -> "$count crossings"
}
