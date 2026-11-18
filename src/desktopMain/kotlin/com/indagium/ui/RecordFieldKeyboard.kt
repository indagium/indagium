package com.indagium.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.indagium.utils.joinRecordPath
import com.indagium.utils.jsonSubtreeAt
import com.indagium.utils.parseRecordJson
import com.indagium.utils.recordValueText

// The keyboard for the picked rows of a record's field tree, which behave like log rows: Up/Down move the pick to the previous/next
// visible row (with Shift they extend it from the anchor), Left/Right close/open the row or step to its parent/first child, and
// Cmd/Ctrl+C copies the picked rows as "path: value" lines. A tree only gets keys while it holds keyboard focus (a click on a row
// gives it) and has a pick, so the log view's own keys are untouched otherwise. Esc is AppState.handleRecordFieldEscape.

/** Gives [event] to the record tree that holds keyboard focus and has picked rows; true when it was used. */
internal fun AppState.handleRecordFieldKey(event: KeyEvent): Boolean = recordFieldTrees.handleKey(event)

private val ARROW_KEYS = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)

/**
 * One key press for the tree of [context]; true when it was used (so nothing else, such as the log view's row navigation, sees it).
 * Only keys that mean something to picked rows are taken, and only while some row is picked.
 */
internal fun handleRecordTreeKey(context: RecordFieldContext, event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val actionKey = if (isMacOs) event.isMetaPressed else event.isCtrlPressed
    val otherModifier = event.isAltPressed || (if (isMacOs) event.isCtrlPressed else event.isMetaPressed)
    val picked = context.actions.selectedPaths(context.tabId, context.entryId)
    return when {
        picked.isEmpty() || otherModifier -> false
        actionKey && event.key == Key.C -> copyPicked(context, picked)
        actionKey || event.key !in ARROW_KEYS -> false
        else -> moveWithArrow(context, picked, event.key, event.isShiftPressed)
    }
}

/** Cmd/Ctrl+C: the text selected in the tree when there is some, else the picked rows as "path: value" lines (document order). */
private fun copyPicked(context: RecordFieldContext, picked: Set<List<String>>): Boolean {
    val text = context.probe.selectedText()
    if (text.isNotEmpty()) {
        context.actions.copyText(text)
        return true
    }
    val order = context.geometry.order()
    val rows = order.filter { it in picked } + picked.filter { it !in order }
    val root = parseRecordJson(context.source)
    val lines = rows.map { path ->
        val value = root?.let { jsonSubtreeAt(it, path) }?.let(::recordValueText).orEmpty()
        "${joinRecordPath(path)}: $value"
    }
    context.actions.copyText(lines.joinToString("\n"))
    return true
}

/** The row the keyboard works from: the cursor, else the anchor, else the first picked row on screen; null when none is on screen. */
private fun cursorRow(context: RecordFieldContext, picked: Set<List<String>>, order: List<List<String>>): List<String>? {
    val cursor = context.geometry.cursor?.takeIf { it in order && it in picked }
    val anchor = context.actions.rangeAnchor(context.tabId, context.entryId)?.takeIf { it in order && it in picked }
    return cursor ?: anchor ?: order.firstOrNull { it in picked }
}

private fun moveWithArrow(context: RecordFieldContext, picked: Set<List<String>>, key: Key, extend: Boolean): Boolean {
    val order = context.geometry.order()
    val from = cursorRow(context, picked, order) ?: return false
    val index = order.indexOf(from)
    when (key) {
        Key.DirectionUp -> order.getOrNull(index - 1)?.let { moveTo(context, it, order, extend, from) }
        Key.DirectionDown -> order.getOrNull(index + 1)?.let { moveTo(context, it, order, extend, from) }
        Key.DirectionRight -> if (!extend) openOrStepIn(context, from, order, index)
        else -> if (!extend) closeOrStepOut(context, from, order)
    }
    return true
}

/** Plain: the row is the whole pick. With Shift: the pick runs from the anchor (where this move began, when there is none) to the row. */
private fun moveTo(context: RecordFieldContext, row: List<String>, order: List<List<String>>, extend: Boolean, from: List<String>) {
    if (!extend) {
        context.moveSelectionTo(row)
    } else {
        val anchor = context.actions.rangeAnchor(context.tabId, context.entryId)?.takeIf { it in order } ?: from
        val covered = context.geometry.between(anchor, row) ?: setOf(row)
        val range = normalizeFieldPaths(order, covered).ifEmpty { listOf(row) }
        context.actions.setRangeAnchor(context.tabId, context.entryId, anchor)
        context.actions.pickFields(context.tabId, context.entryId, range, additive = false)
        context.geometry.cursor = row
    }
    context.geometry.revealRow(row)
}

/** Right: opens a closed row, steps into the first field of an open one. */
private fun openOrStepIn(context: RecordFieldContext, row: List<String>, order: List<List<String>>, index: Int) {
    val control = context.geometry.control(row)?.takeIf { it.expandable } ?: return
    if (!control.expanded.value) {
        control.expanded.value = true
        context.geometry.revealRow(row)
        return
    }
    order.getOrNull(index + 1)?.takeIf { isFieldInside(it, row) }?.let { moveTo(context, it, order, extend = false, from = row) }
}

/** Left: closes an open row, else steps to the field that holds it. */
private fun closeOrStepOut(context: RecordFieldContext, row: List<String>, order: List<List<String>>) {
    val control = context.geometry.control(row)
    if (control != null && control.expandable && control.expanded.value) {
        control.expanded.value = false
        context.geometry.revealRow(row)
        return
    }
    row.dropLast(1).takeIf { it.isNotEmpty() && it in order }?.let { moveTo(context, it, order, extend = false, from = row) }
}
