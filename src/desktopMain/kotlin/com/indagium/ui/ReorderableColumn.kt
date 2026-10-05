package com.indagium.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.indagium.testing.model.altArrowTarget
import kotlin.math.roundToInt
import java.awt.Cursor as AwtCursor

// A reusable reorderable list for the Tests workspace (suites, cases, steps, checks, examples, hooks, params,
// scripts, shared steps, variables). It adds NO new drag math: the order while dragging comes from the existing
// pure helpers, sequenceOrderDuringDrag (FilterPanel.kt) for rows of one fixed height and
// cumulativeBlockOffsets / blockOrderDuringDrag (AnnotationPanel.kt) for rows of measured, varying heights.
//
// Three ways to move a row, all ending in `onMove(id, toIndex)` (the store's `move*`):
//  - drag the grip, committed on release;
//  - Alt+Up / Alt+Down while the row (or anything inside it) has focus;
//  - the small up/down buttons.
// The existing filter / notes / tab reorder callers are deliberately NOT refactored onto this.

private const val ESTIMATED_ROW_HEIGHT_DP = 40f
private val GRIP_SIZE = 18.dp
private const val DISABLED_ALPHA = 0.35f
private const val FOCUS_BORDER_ALPHA = 0.5f
private const val DRAG_SCALE = 1.01f

// ── Pure logic (unit-tested in ReorderableColumnLogicTest) ───────────

/**
 * The order of [ids] while [draggedId] is dragged [dragOffsetY] pixels from where it started at [dragStartIndex].
 * With [fixedRowHeight] (px) every row is that tall and the FilterPanel helper decides; otherwise the Notes
 * panel helper decides from each row's [heightOf] (px).
 */
internal fun reorderOrderDuringDrag(
    ids: List<String>,
    draggedId: String?,
    dragStartIndex: Int,
    dragOffsetY: Float,
    fixedRowHeight: Float?,
    heightOf: (String) -> Float,
): List<String> =
    if (fixedRowHeight != null) {
        sequenceOrderDuringDrag(ids, draggedId, dragStartIndex, dragOffsetY, fixedRowHeight)
    } else {
        blockOrderDuringDrag(ids, draggedId, dragOffsetY, heightOf)
    }

/** The index the Alt+Up / Alt+Down press moves row [id] to, or null when it is unknown or already at that edge. */
internal fun reorderAltTarget(ids: List<String>, id: String, up: Boolean): Int? =
    altArrowTarget(ids.indexOf(id), ids.size, up)

/** The up (-1) / down (+1) target of the move buttons; null at the edge (the button is disabled there). */
internal fun reorderButtonTarget(ids: List<String>, id: String, delta: Int): Int? =
    altArrowTarget(ids.indexOf(id), ids.size, up = delta < 0)

/** The pixel height of every row of [ids]: [fixedRowHeight] when set, else the measured one, else [estimate]. */
internal fun reorderRowHeights(
    ids: List<String>,
    fixedRowHeight: Float?,
    measured: Map<String, Float>,
    estimate: Float,
): Map<String, Float> = ids.associateWith { fixedRowHeight ?: measured[it] ?: estimate }

/**
 * How far the dragged row must be shifted from its slot in the live layout so it follows the pointer:
 * where it would be ([startTop] + [dragOffsetY]) minus where the column currently lays it out ([liveTop]).
 */
internal fun reorderDragTranslation(startTop: Float, dragOffsetY: Float, liveTop: Float): Float = startTop + dragOffsetY - liveTop

/** The drop target of a finished drag: the dragged row's index in the live order, or null when it did not move. */
internal fun reorderCommitIndex(ids: List<String>, liveOrder: List<String>, draggedId: String): Int? {
    val to = liveOrder.indexOf(draggedId)
    return to.takeIf { it >= 0 && it != ids.indexOf(draggedId) }
}

/** The direction of an Alt+Up (true) / Alt+Down (false) key-down; null for any other key event. */
internal fun altArrowDirection(key: Key, type: KeyEventType, altPressed: Boolean): Boolean? = when {
    type != KeyEventType.KeyDown || !altPressed -> null
    key == Key.DirectionUp -> true
    key == Key.DirectionDown -> false
    else -> null
}

internal fun altArrowDirection(event: KeyEvent): Boolean? = altArrowDirection(event.key, event.type, event.isAltPressed)

// ── Row scope ────────────────────────────────────────────────────────

/** What a row of [ReorderableColumn] gets: its live position and the controls to place inside itself. */
@Stable
internal class ReorderRowScope(
    val id: String,
    /** Position in the order currently on screen (live while dragging). */
    val index: Int,
    val count: Int,
    val dragging: Boolean,
    val enabled: Boolean,
    val dragHandleModifier: Modifier,
    val moveUp: () -> Unit,
    val moveDown: () -> Unit,
) {
    val canMoveUp: Boolean get() = enabled && index > 0
    val canMoveDown: Boolean get() = enabled && index < count - 1
}

/** The ⠿ grip: drag it to move the row. Greyed out and inert when reordering is disabled. */
@Composable
internal fun ReorderGrip(row: ReorderRowScope, modifier: Modifier = Modifier) {
    val tc = tc()
    Box(
        modifier.size(GRIP_SIZE)
            .then(if (row.enabled) row.dragHandleModifier.pointerHoverIcon(PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.MOVE_CURSOR))) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        AppText("⠿", color = if (row.enabled) tc.td else tc.td.copy(alpha = DISABLED_ALPHA), fontSize = 12.sp)
    }
}

/** The ↑ / ↓ buttons. A button at its edge stays in place but is dimmed and inert, so rows do not shift. */
@Composable
internal fun ReorderMoveButtons(row: ReorderRowScope, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        SquareIconButton(
            "↑", fontSize = 12.sp, onClick = row.moveUp, enabled = row.canMoveUp,
            modifier = Modifier.graphicsLayer(alpha = if (row.canMoveUp) 1f else DISABLED_ALPHA),
        )
        SquareIconButton(
            "↓", fontSize = 12.sp, onClick = row.moveDown, enabled = row.canMoveDown,
            modifier = Modifier.graphicsLayer(alpha = if (row.canMoveDown) 1f else DISABLED_ALPHA),
        )
    }
}

// ── The column ───────────────────────────────────────────────────────

/** Mutable drag bookkeeping of one [ReorderableColumn]; plain fields read inside pointer callbacks. */
private class ReorderDragState {
    var id by mutableStateOf<String?>(null)
    var startIndex = -1
    var startTop by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
    var liveOrder by mutableStateOf(emptyList<String>())

    fun reset() {
        id = null
        startIndex = -1
        startTop = 0f
        offsetY = 0f
    }
}

/**
 * A column whose rows can be reordered. [items] are in their stored order; [onMove] is called with a row's id and
 * the index it should end up at (the store's `move*` contract). Every row is wrapped so that a press on it gives it
 * focus (Alt+Up/Down then moves it) and the dragged row follows the pointer while its neighbours shuffle.
 *
 * [fixedRowHeight] declares that every row is exactly that tall (a table); leave it null for rows that vary in
 * height (a step with an editor expanded). [reorderEnabled] false keeps the layout but removes every way to move a row
 * (a search filter is active, or the list is read-only). [rowGap] is added below each row.
 */
@Composable
internal fun <T> ReorderableColumn(
    items: List<T>,
    idOf: (T) -> String,
    onMove: (id: String, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    fixedRowHeight: Dp? = null,
    reorderEnabled: Boolean = true,
    rowGap: Dp = 0.dp,
    rowContent: @Composable (item: T, row: ReorderRowScope) -> Unit,
) {
    val density = LocalDensity.current.density
    val ids = items.map(idOf)
    val currentIds by rememberUpdatedState(ids)
    val currentOnMove by rememberUpdatedState(onMove)
    val measured = remember { mutableStateMapOf<String, Float>() }
    val drag = remember { ReorderDragState() }
    val fixedPx = fixedRowHeight?.let { (it.value + rowGap.value) * density }
    val estimatePx = (ESTIMATED_ROW_HEIGHT_DP + rowGap.value) * density

    fun heights(order: List<String>) = reorderRowHeights(order, fixedPx, measured, estimatePx)

    val dragId = drag.id
    val order = drag.liveOrder.takeIf { dragId != null && it.size == ids.size && it.toSet() == ids.toSet() } ?: ids
    val orderHeights = heights(order)
    val tops = cumulativeBlockOffsets(order) { orderHeights.getValue(it) }
    val byId = items.associateBy(idOf)

    fun handleFor(id: String): Modifier = Modifier.pointerInput(id, reorderEnabled) {
        if (!reorderEnabled) return@pointerInput
        detectDragGestures(
            onDragStart = {
                val stable = currentIds
                val stableHeights = heights(stable)
                drag.startIndex = stable.indexOf(id)
                drag.startTop = cumulativeBlockOffsets(stable) { stableHeights.getValue(it) }[id] ?: 0f
                drag.offsetY = 0f
                drag.liveOrder = stable
                drag.id = id
            },
            onDrag = { change, delta ->
                change.consume()
                drag.offsetY += delta.y
                val stable = currentIds
                val stableHeights = heights(stable)
                drag.liveOrder = reorderOrderDuringDrag(stable, id, drag.startIndex, drag.offsetY, fixedPx) { stableHeights.getValue(it) }
            },
            onDragEnd = {
                val target = reorderCommitIndex(currentIds, drag.liveOrder, id)
                drag.reset()
                if (target != null) currentOnMove(id, target)
            },
            onDragCancel = { drag.reset() },
        )
    }

    Column(modifier) {
        order.forEachIndexed { position, id ->
            val item = byId[id] ?: return@forEachIndexed
            key(id) {
                val row = ReorderRowScope(
                    id = id,
                    index = position,
                    count = order.size,
                    dragging = id == dragId,
                    enabled = reorderEnabled,
                    dragHandleModifier = handleFor(id),
                    moveUp = { reorderButtonTarget(currentIds, id, -1)?.let { currentOnMove(id, it) } },
                    moveDown = { reorderButtonTarget(currentIds, id, +1)?.let { currentOnMove(id, it) } },
                )
                ReorderRowFrame(
                    row = row,
                    fixedRowHeight = fixedRowHeight,
                    rowGap = rowGap,
                    translationY = { if (row.dragging) reorderDragTranslation(drag.startTop, drag.offsetY, tops[id] ?: 0f) else 0f },
                    onMeasured = { px -> measured[id] = px },
                    onAltArrow = { up ->
                        if (reorderEnabled) reorderAltTarget(currentIds, id, up)?.let { currentOnMove(id, it) }
                        reorderEnabled
                    },
                ) { rowContent(item, row) }
            }
        }
    }
}

/** Focus, key handling, size measuring and drag visuals of one row. */
@Composable
private fun ReorderRowFrame(
    row: ReorderRowScope,
    fixedRowHeight: Dp?,
    rowGap: Dp,
    translationY: () -> Float,
    onMeasured: (Float) -> Unit,
    onAltArrow: (up: Boolean) -> Boolean,
    content: @Composable () -> Unit,
) {
    val tc = tc()
    val focus = remember { FocusRequester() }
    var rowFocused by remember { mutableStateOf(false) }
    var hasFocus by remember { mutableStateOf(false) }
    val currentHasFocus by rememberUpdatedState(hasFocus)
    val sizing = if (fixedRowHeight != null) Modifier.height(fixedRowHeight + rowGap) else Modifier
    Box(
        Modifier
            .fillMaxWidth()
            .then(sizing)
            .onSizeChanged { onMeasured(it.height.toFloat()) }
            .offset { IntOffset(0, translationY().roundToInt()) }
            .zIndex(if (row.dragging) 1f else 0f)
            .graphicsLayer {
                if (row.dragging) {
                    scaleX = DRAG_SCALE
                    scaleY = DRAG_SCALE
                }
            }
            // Bubble phase: a text field or button inside the row sees the key first, then the row.
            // Always consumed while reordering is enabled, so a row at the edge never lets Alt+Up/Down
            // fall through and move the OUTER row it is nested in.
            .onKeyEvent { event -> altArrowDirection(event)?.let(onAltArrow) ?: false }
            .onFocusChanged {
                rowFocused = it.isFocused
                hasFocus = it.hasFocus
            }
            .focusRequester(focus)
            .focusable()
            // A press on the row body focuses the row, unless something inside it already has focus
            // (stealing that would blur a text field the user is clicking into).
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press && !currentHasFocus) runCatching { focus.requestFocus() }
                    }
                }
            },
    ) {
        Box(
            Modifier.fillMaxWidth()
                .then(if (fixedRowHeight != null) Modifier.height(fixedRowHeight) else Modifier.padding(bottom = rowGap))
                .then(
                    when {
                        row.dragging -> Modifier.border(1.dp, tc.ac, CORNER_MD)
                        rowFocused -> Modifier.border(1.dp, tc.ac.copy(alpha = FOCUS_BORDER_ALPHA), CORNER_MD)
                        else -> Modifier
                    },
                ),
        ) { content() }
    }
}
