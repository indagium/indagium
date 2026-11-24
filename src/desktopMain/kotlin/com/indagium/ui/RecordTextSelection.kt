package com.indagium.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.sign

// Which fields of a record's tree a text selection (or a Shift-click range) covers. The tree is one SelectionContainer whose rows are
// plain texts, so Compose can say what text is selected but not where; the tree records where its field rows are and which rows the last
// drag ran from and to, and the covered fields are the rows between those two. UI side only; the menus that use it are RecordFieldActions.kt.

/** [path] sits inside [outer] (a strictly deeper path under it). */
internal fun isFieldInside(path: List<String>, outer: List<String>): Boolean =
    path.size > outer.size && path.subList(0, outer.size) == outer

/**
 * The fields to add for [covered] rows of a tree whose visible fields are [order] (document order: a container before what it holds).
 * A covered container whose visible subtree is covered as a whole stands for it (only the outermost such field is kept); one whose subtree
 * is covered only in part is left out, its covered fields counting one by one instead (adding it would add the rest too). A covered
 * container with none of its fields covered is kept as it is. Result in document order.
 */
internal fun normalizeFieldPaths(order: List<List<String>>, covered: Set<List<String>>): List<List<String>> {
    val kept = ArrayList<List<String>>()
    var index = 0
    while (index < order.size) {
        val path = order[index]
        var end = index + 1
        while (end < order.size && isFieldInside(order[end], path)) end++
        val inside = end - index - 1
        val coveredInside = (index + 1 until end).count { order[it] in covered }
        if (path in covered && (coveredInside == 0 || coveredInside == inside)) {
            kept += path
            index = end
        } else {
            index++
        }
    }
    return kept
}

/**
 * The rows of [range] (the rows between an anchor and the new end, in the rows laid out now) that only stand in for [base] fields that are
 * no visible rows: a container collapsed since the pick was made is not itself picked, and adding it to a range would add its whole subtree
 * and so widen the pick. Those fields stay picked ([extendedFieldPick] and the additive range and drag keep them); the row is left out,
 * except [end], the row the range was explicitly taken to.
 */
internal fun standInRows(
    order: List<List<String>>,
    base: Set<List<String>>,
    range: Set<List<String>>,
    end: List<String>? = null,
): Set<List<String>> {
    val visible = order.toHashSet()
    val hidden = base.filter { it !in visible }
    if (hidden.isEmpty()) return emptySet()
    val coverage = FieldPickCoverage(base)
    return range.filterTo(LinkedHashSet()) { row -> row != end && !coverage.covers(row) && hidden.any { isFieldInside(it, row) } }
}

/**
 * The pick a keyboard extension to [end] makes from [range] on top of [base], the canonical pick it extends: the range without its
 * [standInRows], and the [base] fields they stood for.
 */
internal fun extendedFieldPick(
    order: List<List<String>>,
    base: Set<List<String>>,
    range: Set<List<String>>,
    end: List<String>,
): List<List<String>> {
    val holders = standInRows(order, base, range, end)
    if (holders.isEmpty()) return range.toList()
    val visible = order.toHashSet()
    return range.filter { it !in holders } + base.filter { it !in visible && holders.any { holder -> isFieldInside(it, holder) } }
}

/**
 * Which rows a pick of record fields marks. The pick holds the rows the user covered (a Shift range or a drag keeps every row between
 * its ends, like a text selection); a picked field with none of its own fields picked (a Ctrl/Cmd-clicked container, or a range that
 * ends on one) stands for its whole subtree, exactly as [normalizeFieldPaths] adds it, so the rows inside it are marked too. A picked
 * container with some of its fields picked is marked on its own row only: just those fields are in.
 */
internal class FieldPickCoverage(val paths: Set<List<String>>) {
    private val owners: Set<List<String>> = run {
        val holdingPicked = HashSet<List<String>>()
        for (path in paths) for (length in 1 until path.size) holdingPicked += path.subList(0, length)
        paths.filterTo(HashSet()) { it !in holdingPicked }
    }

    /** The picked field that stands for [path]'s whole subtree and holds [path], or null. */
    fun ownerAbove(path: List<String>): List<String>? {
        if (owners.isEmpty()) return null
        for (length in 1 until path.size) path.subList(0, length).let { if (it in owners) return it }
        return null
    }

    /** Whether the row of [path] is marked: picked itself, or inside a picked field that stands for its subtree. */
    fun covers(path: List<String>): Boolean = path in paths || ownerAbove(path) != null
}

/** The rows of [order] a pick marks, in document order, then the picked fields that are not visible rows (as they are). */
internal fun markedFieldRows(order: List<List<String>>, pick: Set<List<String>>): List<List<String>> {
    val coverage = FieldPickCoverage(pick)
    val visible = order.toHashSet()
    return order.filter { coverage.covers(it) } + pick.filter { it !in visible }
}

/**
 * The fields a pick adds, copies or offers to the menus: the pick as [normalizeFieldPaths] reads it for a tree whose visible fields are
 * [order] (document order), so what is used is what a range was normalised to before the pick kept every covered row. Picked fields that
 * are not visible rows stay as they are unless a kept field holds them. With no [order] (a tree that is not laid out) the pick itself.
 */
internal fun normalizedPick(order: List<List<String>>, pick: Set<List<String>>): List<List<String>> {
    val coverage = FieldPickCoverage(pick)
    val visible = order.toHashSet()
    val kept = normalizeFieldPaths(order, order.filterTo(LinkedHashSet()) { coverage.covers(it) })
    val hidden = pick.filter { it !in visible && kept.none { holder -> isFieldInside(it, holder) } }
    return kept + hidden
}

/**
 * The pick after a Ctrl/Cmd-click on [path]. A field that is not marked joins the pick (with its visible fields when some of them
 * are picked already, so it stands for all of them). A picked field leaves it alone when nothing else depends on it; one that is marked
 * only because a picked container holds it, or that holds picked fields, leaves with its subtree, and the container it was marked
 * through gives way to the visible fields it held that are not on [path]'s line (so the click is never a silent no-op).
 */
internal fun toggledFieldPick(order: List<List<String>>, pick: Set<List<String>>, path: List<String>): Set<List<String>> {
    val coverage = FieldPickCoverage(pick)
    val viaContainer = coverage.ownerAbove(path) != null
    val holdsPicked = pick.any { isFieldInside(it, path) }
    return when {
        path in pick && !viaContainer && !holdsPicked -> pick - setOf(path)
        !coverage.covers(path) && !holdsPicked -> pick + setOf(path)
        !coverage.covers(path) -> LinkedHashSet(markedFieldRows(order, pick)).apply {
            add(path)
            addAll(order.filter { row -> isFieldInside(row, path) })
        }
        else -> markedFieldRows(order, pick).filterTo(LinkedHashSet()) { it != path && !isFieldInside(it, path) && !isFieldInside(path, it) }
    }
}

/** The fields a text selection in a tree covers: every [covered] row the selection touches, and [fields], what adding them adds. */
internal class TextSelectionFields(val covered: Set<List<String>>, val fields: List<List<String>>)

/** What a field row lets the keyboard do to it: whether it opens and closes, and the open state itself (the row's own). */
internal class RowControl(
    val expandable: Boolean,
    val expanded: MutableState<Boolean>,
    /** How far into the row a press is on the field itself: left of it are the indent and the disclosure chevron, which are no row drag's start. */
    val clickStart: Dp = 0.dp,
)

/** The row under a position of the tree: its field, and whether the position is in its expanded value area rather than its own row. */
internal class RowHit(val path: List<String>, val inArea: Boolean)

/** The row the keyboard just moved to, to scroll into view; [nonce] tells a repeated move to the same row from the last one. */
internal class RowReveal(val path: List<String>, val nonce: Int)

/**
 * Where one record tree's field rows are (the row of a field and, for a hex dump or multi-line string, its expanded value area) and
 * which two rows the last text selection ran between. Rows report in as they are laid out and out as they leave the tree; everything
 * is read on the UI thread.
 */
internal class RecordTreeGeometry {
    private class Slot(var header: LayoutCoordinates? = null, var area: LayoutCoordinates? = null)

    private class Span(val path: List<String>, val top: Float, val bottom: Float, val areaTop: Float?)

    private val slots = LinkedHashMap<List<String>, Slot>()
    private val controls = HashMap<List<String>, RowControl>()
    private var container: LayoutCoordinates? = null
    private var selectionStart: List<String>? = null
    private var selectionEnd: List<String>? = null

    /** The field the keyboard (or the last click) is on: where Up/Down go from and Shift-Up/Down extend to. Null until one is set. */
    var cursor: List<String>? = null

    /** The row to bring into view after a keyboard move; the rows watch it. */
    var reveal: RowReveal? by mutableStateOf(null)
        private set

    fun revealRow(path: List<String>) {
        reveal = RowReveal(path, (reveal?.nonce ?: 0) + 1)
    }

    fun setControl(path: List<String>, control: RowControl) {
        controls[path] = control
    }

    fun clearControl(path: List<String>, control: RowControl) {
        if (controls[path] === control) controls.remove(path)
    }

    fun control(path: List<String>): RowControl? = controls[path]

    fun attach(coordinates: LayoutCoordinates) {
        container = coordinates
    }

    fun place(path: List<String>, area: Boolean, coordinates: LayoutCoordinates) {
        val slot = slots.getOrPut(path) { Slot() }
        if (area) slot.area = coordinates else slot.header = coordinates
    }

    fun remove(path: List<String>, area: Boolean) {
        val slot = slots[path] ?: return
        if (area) slot.area = null else slot.header = null
        if (slot.header == null && slot.area == null) slots.remove(path)
    }

    /** The field rows laid out right now, top to bottom, each with the vertical extent of its row and value area. */
    private fun spans(): List<Span> {
        val root = container?.takeIf { it.isAttached } ?: return emptyList()
        return slots.mapNotNull { (path, slot) ->
            val header = slot.header?.takeIf { it.isAttached } ?: return@mapNotNull null
            val top = root.localPositionOf(header, Offset.Zero).y
            var bottom = top + header.size.height
            var areaTop: Float? = null
            slot.area?.takeIf { it.isAttached }?.let { area ->
                areaTop = root.localPositionOf(area, Offset.Zero).y
                bottom = maxOf(bottom, areaTop!! + area.size.height)
            }
            Span(path, top, bottom, areaTop)
        }.sortedBy { it.top }
    }

    /** The visible fields in document order. */
    fun order(): List<List<String>> = spans().map { it.path }

    /** The field whose rows are nearest to [y] (a position in the tree), or null for a tree with no rows. */
    fun pathAt(y: Float): List<String>? =
        spans().minByOrNull { if (y < it.top) it.top - y else if (y > it.bottom) y - it.bottom else 0f }?.path

    /** The row (or value area) that contains the position [y] of the tree itself, or null when it is between rows or outside them. */
    fun hitAt(y: Float): RowHit? =
        spans().firstOrNull { y >= it.top && y <= it.bottom }?.let { RowHit(it.path, it.areaTop != null && y >= it.areaTop) }

    /** A position [localY] of the tree as a position in the window (null while the tree is not placed). */
    fun rootY(localY: Float): Float? = container?.takeIf { it.isAttached }?.let { it.positionInRoot().y + localY }

    /** A position [rootY] in the window as a position of the tree (null while the tree is not placed). */
    fun localY(rootY: Float): Float? = container?.takeIf { it.isAttached }?.let { rootY - it.positionInRoot().y }

    /**
     * The press now held (or just released) was dragged across rows. The release of such a press lands within touch slop of the press when
     * the rows are close, and the row it ends on must not take it for a click.
     */
    var rowDragged = false

    /** Forgets which rows the last text selection ran between (it was cleared, so it covers nothing). */
    fun forgetSelection() {
        selectionStart = null
        selectionEnd = null
    }

    /** The visible fields from [from] to [to], either way round, or null when one of them is not visible. */
    fun between(from: List<String>, to: List<String>): Set<List<String>>? {
        val order = order()
        val a = order.indexOf(from)
        val b = order.indexOf(to)
        if (a < 0 || b < 0) return null
        return order.subList(minOf(a, b), maxOf(a, b) + 1).toSet()
    }

    /** A new text selection begins at the row at [y]. */
    fun startSelection(y: Float) {
        val path = pathAt(y)
        selectionStart = path
        selectionEnd = path
    }

    /** The selection's moving end is at the row at [y] now (a drag, or a Shift-click extending it). */
    fun extendSelection(y: Float) {
        if (selectionStart == null) return
        selectionEnd = pathAt(y) ?: selectionEnd
    }

    /**
     * The fields the tree's text selection covers, or null when [selectedText] is empty (no selection) or its two ends are not visible
     * rows any more (the tree changed shape since).
     */
    fun textSelectionFields(selectedText: String): TextSelectionFields? {
        if (selectedText.isEmpty()) return null
        val start = selectionStart ?: return null
        val end = selectionEnd ?: return null
        val order = order()
        val covered = between(start, end) ?: return null
        val fields = normalizeFieldPaths(order, covered)
        return if (fields.isEmpty()) null else TextSelectionFields(covered, fields)
    }
}

/** Reports a field row (or, with [area], a field's expanded value area) to [geometry] while it is in the tree. */
@Composable
internal fun Modifier.trackFieldRow(geometry: RecordTreeGeometry?, path: List<String>, area: Boolean = false): Modifier {
    if (geometry == null) return this
    DisposableEffect(geometry, path, area) { onDispose { geometry.remove(path, area) } }
    return this.onGloballyPositioned { geometry.place(path, area, it) }
}

/** A primary press on a field row that may become a row drag: where it began and the pick a Ctrl/Cmd press adds the dragged range to. */
private class RowDrag(val start: List<String>, val additive: Boolean, val baseline: Set<List<String>>) {
    /** The pointer has entered another row: this is a row drag now, and no text selection. */
    var active = false

    /** The row the range was last picked up to, so a move inside a row does not pick again. */
    var last: List<String>? = null
}

/**
 * Follows the pointer over a tree to know which rows its text selection runs between, and takes the presses that are not text selection.
 * It sees a press before the tree's selection container does, and the container ignores a consumed press, so a Shift-press that picks
 * a range of fields ([RecordFieldContext.shiftClickPicksRange]; with Ctrl/Cmd also, the range is added to the pick) is consumed here and
 * does not extend the text selection. Any other plain press starts a text selection at its row, a drag moves its end, and a Shift-press
 * with text selected extends it from the old start. A Ctrl/Cmd-press is the row's (it toggles its field); right presses are left alone.
 *
 * A press on a field row (past its chevron) that is dragged into another row stops being a text selection: the text selection is cleared and
 * the pick becomes the rows from the pressed one to the one under the pointer, live, as in the log list (Ctrl/Cmd keeps the old pick and adds
 * the range to it). While the pointer stays inside the pressed row the drag is text selection as before. A pointer above or below the
 * [scroller]'s viewport scrolls it, a modest speed that grows with the distance, and the range follows what scrolls under the pointer.
 */
internal fun Modifier.trackTextSelection(
    geometry: RecordTreeGeometry,
    scroller: () -> RecordTreeScroller? = { null },
    context: () -> RecordFieldContext?,
): Modifier =
    onGloballyPositioned { geometry.attach(it) }.pointerInput(geometry) {
        coroutineScope {
            val tracker = TreePointerTracker(geometry, scroller, context, this)
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    when (event.type) {
                        PointerEventType.Press -> tracker.press(event, change, viewConfiguration.touchSlop) { it.toPx() }
                        // A pointer that leaves the tree while pressed arrives as Exit (and comes back as Enter), not as Move.
                        PointerEventType.Move, PointerEventType.Exit, PointerEventType.Enter ->
                            tracker.move(event, change, density)
                        PointerEventType.Release -> tracker.release(change)
                    }
                }
            }
        }
    }

/** The pointer state of one tree between its presses and releases (see [trackTextSelection]); everything runs on the UI thread. */
private class TreePointerTracker(
    private val geometry: RecordTreeGeometry,
    private val scroller: () -> RecordTreeScroller?,
    private val context: () -> RecordFieldContext?,
    private val scope: CoroutineScope,
) {
    private var dragging = false
    private var pressAt = Offset.Zero
    private var touchSlop = 0f
    private var pickDropped = false
    private var rowDrag: RowDrag? = null
    private var pointerRootY: Float? = null
    private var autoScroll: Job? = null

    fun press(event: PointerEvent, change: PointerInputChange, slop: Float, toPx: (Dp) -> Float) {
        finishRowDrag()
        geometry.rowDragged = false
        dragging = false
        if (!event.buttons.isPrimaryPressed) return
        touchSlop = slop
        pressAt = change.position
        pickDropped = false
        context()?.actions?.noteTreePress()
        dragging = onPrimaryPress(geometry, context(), change, event.keyboardModifiers)
        rowDrag = rowDragStart(geometry, context(), change.position, event.keyboardModifiers, toPx)
    }

    fun move(event: PointerEvent, change: PointerInputChange, density: Float) {
        val y = change.position.y
        val drag = rowDrag
        if (drag != null && !event.buttons.isPrimaryPressed) {
            finishRowDrag()
        } else if (drag != null) {
            moveRowDrag(drag, change, density)
        }
        if (dragging && rowDrag?.active != true && event.type == PointerEventType.Move) {
            geometry.extendSelection(y)
            // A press that moves is a text drag, not a click on a row: the rows picked so far make way for the text.
            if (!pickDropped && (change.position - pressAt).getDistance() > touchSlop) {
                pickDropped = true
                context()?.dropPick()
            }
        }
    }

    fun release(change: PointerInputChange) {
        val y = change.position.y
        val drag = rowDrag
        if (drag?.active == true) pickTo(drag, y)
        if (dragging && drag?.active != true) geometry.extendSelection(y)
        dragging = false
        finishRowDrag()
    }

    private fun moveRowDrag(drag: RowDrag, change: PointerInputChange, density: Float) {
        val y = change.position.y
        pointerRootY = geometry.rootY(y)
        if (!drag.active && geometry.pathAt(y).let { it != null && it != drag.start }) {
            drag.active = true
            context()?.beginRowDrag(drag.start)
        }
        if (!drag.active) return
        change.consume()
        pickTo(drag, y)
        val rootY = pointerRootY
        val viewport = scroller()?.viewport()
        if (rootY != null && viewport != null && (rootY < viewport.first || rootY > viewport.second)) startAutoScroll(drag, density)
    }

    private fun pickTo(drag: RowDrag, localY: Float) {
        val current = geometry.pathAt(localY) ?: return
        if (current == drag.last) return
        drag.last = current
        context()?.pickDragRange(drag.start, current, drag.baseline.takeIf { drag.additive })
    }

    private fun finishRowDrag() {
        autoScroll?.cancel()
        autoScroll = null
        if (rowDrag?.active == true) context()?.endRowDrag()
        rowDrag = null
    }

    /** While the pointer is held above or below the viewport, scrolls it a frame at a time and keeps the range up to what is under the pointer. */
    private fun startAutoScroll(drag: RowDrag, density: Float) {
        val source = scroller() ?: return
        if (autoScroll?.isActive == true) return
        autoScroll = scope.launch {
            var last = withFrameNanos { it }
            while (rowDrag === drag) {
                val now = withFrameNanos { it }
                val seconds = ((now - last) / NANOS_PER_SECOND).coerceAtMost(MAX_AUTO_SCROLL_STEP_SECONDS)
                last = now
                val rootY = pointerRootY ?: return@launch
                val viewport = source.viewport() ?: return@launch
                val over = when {
                    rootY < viewport.first -> rootY - viewport.first
                    rootY > viewport.second -> rootY - viewport.second
                    else -> return@launch
                }
                val dp = (AUTO_SCROLL_BASE_DP + abs(over) / density * AUTO_SCROLL_DP_PER_DP).coerceAtMost(AUTO_SCROLL_MAX_DP)
                source.scrollBy(sign(over) * dp * density * seconds)
                geometry.localY(rootY)?.let { pickTo(drag, it) }
            }
        }
    }
}

/**
 * The press that may turn into a row drag: a plain or Ctrl/Cmd primary press (not Shift's, which is a range) on a field's own row past its
 * chevron, or in its value area. [toPx] converts a dp to pixels. Null for any other press.
 */
private fun rowDragStart(
    geometry: RecordTreeGeometry,
    context: RecordFieldContext?,
    position: Offset,
    keys: PointerKeyboardModifiers,
    toPx: (Dp) -> Float,
): RowDrag? {
    if (context == null || keys.isShiftPressed) return null
    val hit = geometry.hitAt(position.y) ?: return null
    if (!hit.inArea && position.x < toPx(geometry.control(hit.path)?.clickStart ?: 0.dp)) return null
    val additive = keys.isCtrlPressed || keys.isMetaPressed
    return RowDrag(hit.path, additive, if (additive) context.canonicalPick() else emptySet())
}

private const val NANOS_PER_SECOND = 1_000_000_000f
private const val MAX_AUTO_SCROLL_STEP_SECONDS = 0.1f

/** Scrolling a viewport while a row drag is held outside it: this much (dp per second) at its edge, more per dp farther out, up to a cap. */
private const val AUTO_SCROLL_BASE_DP = 60f
private const val AUTO_SCROLL_DP_PER_DP = 6f
private const val AUTO_SCROLL_MAX_DP = 700f

/** One primary press as [trackTextSelection] sees it; true when it starts or extends a text selection (so a drag follows). */
private fun onPrimaryPress(
    geometry: RecordTreeGeometry,
    context: RecordFieldContext?,
    change: PointerInputChange,
    keys: PointerKeyboardModifiers,
): Boolean {
    val y = change.position.y
    val toggles = keys.isCtrlPressed || keys.isMetaPressed
    return when {
        toggles -> {
            // Ctrl/Cmd alone is the row's (it toggles its field); with Shift it adds a range, so the selection must not extend.
            if (keys.isShiftPressed) {
                change.consume()
                geometry.pathAt(y)?.let { context?.pickRangeTo(it, additive = true) }
            }
            false
        }
        !keys.isShiftPressed -> {
            geometry.startSelection(y)
            true
        }
        context?.shiftClickPicksRange() == true -> {
            change.consume()
            geometry.pathAt(y)?.let { context.pickRangeTo(it, additive = false) }
            false
        }
        else -> {
            geometry.extendSelection(y)
            true
        }
    }
}

/** One mounted record tree, seen from outside it: which record it shows and what its text selection covers right now. */
internal class RecordTreeHandle(
    val tabId: String,
    val entryId: Int,
    private val geometry: RecordTreeGeometry,
    private val probe: TreeSelectionProbe,
    /** Whether keyboard focus is in this tree right now. */
    private val hasFocus: () -> Boolean = { false },
    /** Takes a key press for the tree's selected fields; true when it did something. */
    private val onKey: (KeyEvent) -> Boolean = { false },
) {
    /** The tree handles [event] when it holds keyboard focus and the key means something to its selected fields. */
    fun handleKey(event: KeyEvent): Boolean = hasFocus() && onKey(event)

    /** The visible rows of the tree in document order, or null while none is laid out. */
    fun visibleOrder(): List<List<String>>? = geometry.order().takeIf { it.isNotEmpty() }

    /** The fields the tree's text selection would add, in document order; empty when no text is selected. */
    fun selectedFields(): List<List<String>> = geometry.textSelectionFields(probe.selectedText())?.fields.orEmpty()
}

/**
 * The mounted record trees of one log view, and the text selections they held when the last right press began. A right-click on a log
 * row moves keyboard focus to the view, which clears a tree's text selection before the row's menu is built; the menu reads this instead.
 */
internal class RecordFieldTrees {
    private val handles = CopyOnWriteArrayList<RecordTreeHandle>()
    private var atRightPress: Map<Pair<String, Int>, List<List<String>>> = emptyMap()

    /** Whether the primary press being dispatched landed in a tree (so the view's plain-press clearing leaves the pick to the tree). */
    var pressInTree = false

    /** Whether a drag across a tree's field rows is under way (the log list's own drag must leave it alone). */
    @Volatile
    var rowDragActive = false

    /** Gives [event] to the tree that holds keyboard focus; true when it handled it. */
    fun handleKey(event: KeyEvent): Boolean = handles.any { it.handleKey(event) }

    /** Adds [handle]; the returned function takes it out again. */
    fun register(handle: RecordTreeHandle): () -> Unit {
        handles += handle
        return { handles -= handle }
    }

    /** The visible rows of a mounted, laid-out tree of record [entryId] in [tabId] (document order), or null when there is none. */
    fun visibleOrder(tabId: String, entryId: Int): List<List<String>>? =
        handles.firstNotNullOfOrNull { if (it.tabId == tabId && it.entryId == entryId) it.visibleOrder() else null }

    /** Remembers what each tree's text selection covers now; called as a right press begins, before anything reacts to it. */
    fun captureAtRightPress() {
        atRightPress = handles.mapNotNull { handle ->
            handle.selectedFields().takeIf { it.isNotEmpty() }?.let { (handle.tabId to handle.entryId) to it }
        }.toMap()
    }

    /** Forgets the captured selections (a press that is not a right one has moved on from them). */
    fun clearCaptured() {
        atRightPress = emptyMap()
    }

    /** What the text selection of record [entryId] in [tabId] covered when the last right press began; empty if none. */
    fun selectedAtRightPress(tabId: String, entryId: Int): List<List<String>> = atRightPress[tabId to entryId].orEmpty()
}
