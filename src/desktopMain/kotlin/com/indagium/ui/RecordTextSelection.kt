package com.indagium.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import java.util.concurrent.CopyOnWriteArrayList

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

/** The fields a text selection in a tree covers: every [covered] row the selection touches, and [fields], what adding them adds. */
internal class TextSelectionFields(val covered: Set<List<String>>, val fields: List<List<String>>)

/**
 * Where one record tree's field rows are (the row of a field and, for a hex dump or multi-line string, its expanded value area) and
 * which two rows the last text selection ran between. Rows report in as they are laid out and out as they leave the tree; everything
 * is read on the UI thread.
 */
internal class RecordTreeGeometry {
    private class Slot(var header: LayoutCoordinates? = null, var area: LayoutCoordinates? = null)

    private class Span(val path: List<String>, val top: Float, val bottom: Float)

    private val slots = LinkedHashMap<List<String>, Slot>()
    private var container: LayoutCoordinates? = null
    private var selectionStart: List<String>? = null
    private var selectionEnd: List<String>? = null

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
            slot.area?.takeIf { it.isAttached }?.let { area ->
                bottom = maxOf(bottom, root.localPositionOf(area, Offset.Zero).y + area.size.height)
            }
            Span(path, top, bottom)
        }.sortedBy { it.top }
    }

    /** The visible fields in document order. */
    fun order(): List<List<String>> = spans().map { it.path }

    /** The field whose rows are nearest to [y] (a position in the tree), or null for a tree with no rows. */
    fun pathAt(y: Float): List<String>? =
        spans().minByOrNull { if (y < it.top) it.top - y else if (y > it.bottom) y - it.bottom else 0f }?.path

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

/**
 * Follows the pointer over a tree to know which rows its text selection runs between, and takes the presses that are not text selection.
 * It sees a press before the tree's selection container does, and the container ignores a consumed press, so a Shift-press that picks
 * a range of fields ([RecordFieldContext.shiftClickPicksRange]; with Ctrl/Cmd also, the range is added to the pick) is consumed here and
 * does not extend the text selection. Any other plain press starts a text selection at its row, a drag moves its end, and a Shift-press
 * with text selected extends it from the old start. A Ctrl/Cmd-press is the row's (it toggles its field); right presses are left alone.
 */
internal fun Modifier.trackTextSelection(geometry: RecordTreeGeometry, context: () -> RecordFieldContext?): Modifier =
    onGloballyPositioned { geometry.attach(it) }.pointerInput(geometry) {
        awaitPointerEventScope {
            var dragging = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                val y = change.position.y
                when (event.type) {
                    PointerEventType.Press -> {
                        dragging = false
                        if (event.buttons.isPrimaryPressed) dragging = onPrimaryPress(geometry, context(), change, event.keyboardModifiers)
                    }
                    PointerEventType.Move -> if (dragging) geometry.extendSelection(y)
                    PointerEventType.Release -> {
                        if (dragging) geometry.extendSelection(y)
                        dragging = false
                    }
                }
            }
        }
    }

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
) {
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

    /** Adds [handle]; the returned function takes it out again. */
    fun register(handle: RecordTreeHandle): () -> Unit {
        handles += handle
        return { handles -= handle }
    }

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
