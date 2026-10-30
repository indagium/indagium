package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.diagram3.Seq3ArrowRow
import com.indagium.diagram3.Seq3Box
import com.indagium.diagram3.Seq3BulkAction
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Layout
import com.indagium.diagram3.Seq3MessageNoteRow
import com.indagium.diagram3.Seq3SelfLoopRow
import com.indagium.diagram3.Seq3UnresolvedStubRow
import kotlin.math.max
import kotlin.math.roundToInt

// ── The canvas text-edit popover ────────────────────────────────────────────────────────────────
//
// Every canvas text edit (message label, fragment label, delay label, note text, lifeline name)
// opens THIS one popover. It is laid out at UI scale on top of the canvas viewport — NOT inside the
// zoomed layer — so its field, buttons and width are the same at 50% and 150%; only its anchor
// (the edited element's box, mapped through the current zoom and scroll) follows the diagram.

/** Minimum popover width; a wider element widens it to match. */
internal val SEQ3_EDIT_POPOVER_MIN_WIDTH = 280.dp
private val SEQ3_EDIT_POPOVER_GAP = 4.dp
private val SEQ3_EDIT_POPOVER_MARGIN = 6.dp
private const val FRAGMENT_HEADER_MAX_HEIGHT = 28.0
private const val FRAGMENT_HEADER_MIN_HEIGHT = 20.0

/** What the popover edits: the target, the element's box in diagram units, the field's seed text,
 *  and, for a message label, how many arrows share the edited template. */
internal data class Seq3EditSpec(
    val target: Seq3CanvasEditorTarget,
    val box: Seq3Box,
    val seed: String,
    /** MESSAGE_LABEL: arrows drawn for the edited message (the label is shared by all of them). */
    val arrowCount: Int = 1,
    /** MESSAGE_LABEL: the exact arrow can be split out and renamed alone. */
    val canRenameOnlyThisArrow: Boolean = false,
)

/** Resolves [target] against the live [layout]/[document]; null when the element is not drawn
 *  (hidden, deleted) — then there is nothing to anchor a popover to. */
internal fun seq3EditSpec(document: Seq3Document, layout: Seq3Layout, target: Seq3CanvasEditorTarget): Seq3EditSpec? =
    when (target.kind) {
        Seq3CanvasEditorKind.MESSAGE_LABEL -> messageEditSpec(document, layout, target)
        Seq3CanvasEditorKind.FRAGMENT_LABEL -> layout.fragments.firstOrNull { it.fragmentId == target.id }?.let { fragment ->
            val headerHeight = fragment.box.height.coerceAtMost(FRAGMENT_HEADER_MAX_HEIGHT).coerceAtLeast(FRAGMENT_HEADER_MIN_HEIGHT)
            Seq3EditSpec(target, fragment.box.copy(height = headerHeight), fragment.label)
        }
        Seq3CanvasEditorKind.DELAY_LABEL ->
            layout.delays.firstOrNull { it.delayId == target.id }?.let { Seq3EditSpec(target, it.hitBox, it.label) }
        Seq3CanvasEditorKind.NOTE_TEXT ->
            layout.notes.firstOrNull { it.noteId == target.id }?.let { Seq3EditSpec(target, it.box, it.text) }
        Seq3CanvasEditorKind.LIFELINE_NAME ->
            layout.lifelines.firstOrNull { it.lifelineId == target.id }?.let { Seq3EditSpec(target, it.header, it.label) }
    }

private fun messageEditSpec(document: Seq3Document, layout: Seq3Layout, target: Seq3CanvasEditorTarget): Seq3EditSpec? {
    val rows = layout.rows.filter { it.messageId == target.id }
    val row = rows.firstOrNull { target.occurrenceEntryId == null || it.occurrenceEntryId == target.occurrenceEntryId } ?: return null
    val box = when (row) {
        is Seq3ArrowRow -> row.labelBox
        is Seq3SelfLoopRow -> row.labelBox
        is Seq3UnresolvedStubRow -> row.labelBox
        is Seq3MessageNoteRow -> row.box
        else -> return null
    }
    val message = document.messages.firstOrNull { it.id == target.id }
    val arrowCount = rows.count { it is Seq3ArrowRow || it is Seq3SelfLoopRow || it is Seq3UnresolvedStubRow || it is Seq3MessageNoteRow }
    return Seq3EditSpec(
        target = target,
        box = box,
        // The template, not the rendered label of this occurrence: SetLabel edits the shared template,
        // and seeding the rendered text would replace its capture slots with one occurrence's values.
        seed = message?.labelTemplate ?: "",
        arrowCount = arrowCount,
        canRenameOnlyThisArrow = arrowCount > 1 && target.occurrenceEntryId != null && (message?.occurrences?.size ?: 0) > 1,
    )
}

/** The command that saves [text] for [target]; null for blank text (the old editors ignored it). */
internal fun seq3EditCommand(target: Seq3CanvasEditorTarget, text: String): Seq3Command? {
    if (text.isBlank()) return null
    return when (target.kind) {
        Seq3CanvasEditorKind.MESSAGE_LABEL -> Seq3Command.Bulk(setOf(target.id), Seq3BulkAction.SetLabel(text))
        Seq3CanvasEditorKind.FRAGMENT_LABEL -> Seq3Command.Bulk(emptySet(), Seq3BulkAction.SetFragmentLabel(target.id, text))
        Seq3CanvasEditorKind.DELAY_LABEL -> Seq3Command.Bulk(emptySet(), Seq3BulkAction.SetDelayLabel(target.id, text))
        Seq3CanvasEditorKind.NOTE_TEXT -> Seq3Command.Bulk(emptySet(), Seq3BulkAction.SetNoteText(target.id, text))
        Seq3CanvasEditorKind.LIFELINE_NAME -> Seq3Command.RenameLifeline(target.id, text)
    }
}

/** "Rename only this arrow": split the exact occurrence out and name it, as one undo step. */
internal fun seq3RenameOnlyThisArrowCommand(target: Seq3CanvasEditorTarget, text: String): Seq3Command? {
    val entryId = target.occurrenceEntryId
    if (target.kind != Seq3CanvasEditorKind.MESSAGE_LABEL || entryId == null || text.isBlank()) return null
    return Seq3Command.MoveOccurrenceOutAndRename(target.id, entryId, text)
}

/** The note shown under a message-label field when the template is shared by several arrows. */
internal fun seq3SharedLabelNote(arrowCount: Int): String? =
    if (arrowCount > 1) "Renames all $arrowCount arrows of this message" else null

/** The element's box on screen, in physical pixels relative to the canvas viewport: diagram units
 *  scaled by density and zoom, minus how far the canvas is scrolled. */
internal fun seq3ElementRectPx(box: Seq3Box, density: Float, zoom: Float, scrollXPx: Int, scrollYPx: Int): Rect {
    val scale = density * zoom
    return Rect(
        left = (box.x * scale - scrollXPx).toFloat(),
        top = (box.y * scale - scrollYPx).toFloat(),
        right = ((box.x + box.width) * scale - scrollXPx).toFloat(),
        bottom = ((box.y + box.height) * scale - scrollYPx).toFloat(),
    )
}

/** Popover width in px: [minWidthPx] or the element's own on-screen width, whichever is larger,
 *  never wider than the viewport allows. Independent of zoom unless the element itself is wider. */
internal fun seq3EditPopoverWidthPx(anchorWidthPx: Float, minWidthPx: Int, maxWidthPx: Int): Int =
    max(minWidthPx, anchorWidthPx.roundToInt()).coerceAtMost(max(maxWidthPx, 0))

/** Top-left of the popover: just above [anchor], left edges aligned, nudged back into the viewport.
 *  With no room above it drops below the element instead. */
internal fun seq3EditPopoverOffset(anchor: Rect, popup: IntSize, viewport: IntSize, gapPx: Int, marginPx: Int): IntOffset {
    val maxX = (viewport.width - popup.width - marginPx).coerceAtLeast(marginPx)
    val x = anchor.left.roundToInt().coerceIn(marginPx, maxX)
    val above = anchor.top.roundToInt() - gapPx - popup.height
    val y = if (above >= marginPx) above else anchor.bottom.roundToInt() + gapPx
    val maxY = (viewport.height - popup.height - marginPx).coerceAtLeast(marginPx)
    return IntOffset(x, y.coerceIn(marginPx, maxY))
}

/**
 * Hosts the edit popover for whichever canvas element is currently being edited, if any. Meant to
 * sit in the canvas viewport box, beside (never inside) the zoomed layer.
 */
@Composable
internal fun Seq3CanvasEditHost(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    layout: Seq3Layout,
    density: Float,
    scrollX: () -> Int,
    scrollY: () -> Int,
) {
    val target = seq3ActiveCanvasEditorTarget(view) ?: return
    val spec = seq3EditSpec(session.document, layout, target) ?: return
    val focusRequester = LocalSeq3FocusRequester.current

    fun close() {
        seq3CloseCanvasEditor(view, target)
        view.textFieldFocused = false
        focusRequester?.let { runCatching { it.requestFocus() } }
    }

    fun save(command: Seq3Command?) {
        if (command != null) state.seq3Sessions.applyCommand(session.id, command)
        close()
    }

    Seq3EditPopover(
        spec = spec,
        anchor = { seq3ElementRectPx(spec.box, density, view.zoom, scrollX(), scrollY()) },
        onTextFocus = { view.textFieldFocused = it },
        onCommit = { text -> save(seq3EditCommand(target, text)) },
        onRenameOnlyThisArrow = { text -> save(seq3RenameOnlyThisArrowCommand(target, text)) },
        onCancel = ::close,
    )
}

/** The popover itself. [anchor] is read while laying out, so scrolling/zooming only re-places it. */
@Composable
internal fun Seq3EditPopover(
    spec: Seq3EditSpec,
    anchor: () -> Rect,
    onTextFocus: (Boolean) -> Unit,
    onCommit: (String) -> Unit,
    onRenameOnlyThisArrow: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    val density = LocalDensity.current
    val minWidthPx = with(density) { SEQ3_EDIT_POPOVER_MIN_WIDTH.roundToPx() }
    val gapPx = with(density) { SEQ3_EDIT_POPOVER_GAP.roundToPx() }
    val marginPx = with(density) { SEQ3_EDIT_POPOVER_MARGIN.roundToPx() }
    val fieldFocus = remember(spec.target) { FocusRequester() }
    var text by remember(spec.target) { mutableStateOf(spec.seed) }
    LaunchedEffect(spec.target) { runCatching { fieldFocus.requestFocus() } }
    val shape = CORNER_MD
    val sharedNote = if (spec.target.kind == Seq3CanvasEditorKind.MESSAGE_LABEL) seq3SharedLabelNote(spec.arrowCount) else null

    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            Column(
                Modifier
                    .shadow(6.dp, shape)
                    .background(tc.p, shape)
                    .border(1.dp, tc.ac, shape)
                    // Swallow pointer input so a click on the popover never reaches the canvas beneath it.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .testTag("seq3-edit-popover")
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    InlineField(
                        value = text,
                        onValue = { text = it },
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f).focusRequester(fieldFocus)
                            .onFocusChanged { onTextFocus(it.hasFocus) }
                            .testTag("seq3-edit-popover-field"),
                        onSubmit = { onCommit(text) },
                        onCancel = onCancel,
                    )
                    SquareIconButton(
                        "✓",
                        fontSize = 13.sp,
                        onClick = { onCommit(text) },
                        size = 24.dp,
                        modifier = Modifier.testTag("seq3-edit-popover-commit"),
                        tooltip = "Save",
                    )
                    SquareIconButton(
                        "×",
                        fontSize = 15.sp,
                        onClick = onCancel,
                        size = 24.dp,
                        modifier = Modifier.testTag("seq3-edit-popover-cancel"),
                        tooltip = "Cancel",
                    )
                }
                if (sharedNote != null) {
                    AppText(sharedNote, color = tc.ts, fontSize = 11.sp, modifier = Modifier.testTag("seq3-edit-popover-note"))
                    if (spec.canRenameOnlyThisArrow) {
                        Seq3TextLink(
                            "Rename only this arrow",
                            onClick = { onRenameOnlyThisArrow(text) },
                            enabled = text.isNotBlank(),
                            modifier = Modifier.testTag("seq3-edit-popover-rename-only"),
                        )
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val rect = anchor()
        val width = seq3EditPopoverWidthPx(rect.width, minWidthPx, constraints.maxWidth - 2 * marginPx)
        val placeable = measurables.single().measure(Constraints(width, width, 0, constraints.maxHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val position = seq3EditPopoverOffset(
                rect,
                IntSize(placeable.width, placeable.height),
                IntSize(constraints.maxWidth, constraints.maxHeight),
                gapPx,
                marginPx,
            )
            placeable.place(position)
        }
    }
}
