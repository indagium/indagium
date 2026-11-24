@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.indagium.ui

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Link
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.indagium.utils.RECORD_FIELDS_MAX_JSON_CHARS
import com.indagium.utils.RecordFieldsResult
import com.indagium.utils.joinRecordPath
import com.indagium.utils.jsonSubtreeAt
import com.indagium.utils.parseRecordJson
import com.indagium.utils.recordFieldRefs
import com.indagium.utils.recordFieldsInDocumentOrder
import com.indagium.utils.recordInspectionSource
import com.indagium.utils.recordValueText

// Choosing fields of a JSON record in its field tree and adding them to the notes: the pick (a multi-selection of one record),
// the right-click menu and what the menu's actions do. The tree itself is StructuredLogRecord.kt; the note's rendering of the
// result is AnnotationPanel.kt; the data is AnnBlock.LogRef.recordFields.

/** The fields picked in the tree of one record: row [entryId] of [tabId] (in [sectionId] of a log with sections). */
internal data class RecordFieldSelection(
    val tabId: String,
    val sectionId: String?,
    val entryId: Int,
    val paths: Set<List<String>>,
    /**
     * The visible fields (document order) of the tree the pick was made against, or null for a pick that needs none (a plain click on
     * one field) or was made with no tree laid out. What a pick adds and copies is read against THIS order ([normalizedPick]), not the
     * rows laid out later: expanding or collapsing a container afterwards must not change what the pick stands for. A new pick replaces it.
     */
    val order: List<List<String>>? = null,
)

/**
 * "Go to rows" on a fields reference asks the record's tree to open [paths] (and the way down to them). [nonce] tells a repeated
 * request from the last one, so a tree that already composed applies it again.
 */
internal data class RecordFieldOpenRequest(
    val nonce: Int,
    val tabId: String,
    val sectionId: String?,
    val entryId: Int,
    val paths: Set<List<String>>,
)

/** What a field tree can do with the record it shows, wired to [AppState] by [AppState.recordFieldActions]; null outside the log view. */
internal interface RecordFieldActions {
    /** The picked fields of record [entryId] in [tabId]; empty when the pick is in another record. Reads snapshot state. */
    fun selectedPaths(tabId: String, entryId: Int): Set<List<String>>

    /** Adds [path] to the pick, or removes it; a pick in another record is replaced by it. */
    fun toggleSelected(tabId: String, entryId: Int, path: List<String>)

    /** Starts adding [paths] of the record to the notes (the usual caption dialog). */
    fun addToNotes(tabId: String, entryId: Int, paths: List<List<String>>)

    fun copyText(text: String)

    /** The paths "Go to rows" asked this record to open, or null. Reads snapshot state. */
    fun openRequest(tabId: String, entryId: Int): RecordFieldOpenRequest?

    /** Gives keyboard focus back to the log view after a menu closed (a closed popup leaves it nowhere). */
    fun reclaimFocus()

    /** The field a Shift-click range starts from in record [entryId]: the last one plain-clicked or Ctrl/Cmd-clicked, or null. Reads snapshot state. */
    fun rangeAnchor(tabId: String, entryId: Int): List<String>? = null

    fun setRangeAnchor(tabId: String, entryId: Int, path: List<String>) = Unit

    /** The visible order the pick of record [entryId] was made against ([RecordFieldSelection.order]), or null. Reads snapshot state. */
    fun pickOrder(tabId: String, entryId: Int): List<List<String>>? = null

    /** Records [order] as what the pick just made in record [entryId] was made against; a later pick replaces it. */
    fun notePickOrder(tabId: String, entryId: Int, order: List<List<String>>) = Unit

    /** Makes [paths] the pick of the record (or adds them to it when [additive]); the range anchor stays where it is. */
    fun pickFields(tabId: String, entryId: Int, paths: List<List<String>>, additive: Boolean) = Unit

    /** Lets the log view look at a mounted tree (for the log row's menu); the returned function takes it out again. */
    fun registerTree(handle: RecordTreeHandle): () -> Unit = {}

    /** A primary press landed in a tree, so the view's plain-press clearing of the pick must leave it to the tree. */
    fun noteTreePress() = Unit

    /** A press in a tree is dragging across its field rows (picking them): the log list must not read the same drag as a row selection. */
    fun setRowDragActive(active: Boolean) = Unit

    fun rowDragActive(): Boolean = false
}

/**
 * A scrolling viewport that holds record trees (the log list, or Message details), which a row drag scrolls while the pointer is held
 * above or below it.
 */
internal interface RecordTreeScroller {
    /** The top and bottom of what is visible, in window coordinates; null while it is not placed. */
    fun viewport(): Pair<Float, Float>?

    /** Scrolls the content by [deltaPx] pixels (positive towards its end). */
    fun scrollBy(deltaPx: Float)
}

/** A [RecordTreeScroller] over a [ScrollableState] whose visible part is the layout that [viewport] was last given. */
internal class ScrollableRecordTreeScroller(private val state: ScrollableState) : RecordTreeScroller {
    var viewportCoordinates: LayoutCoordinates? = null

    override fun viewport(): Pair<Float, Float>? {
        val coordinates = viewportCoordinates?.takeIf { it.isAttached } ?: return null
        val top = coordinates.positionInRoot().y
        return top to top + coordinates.size.height
    }

    override fun scrollBy(deltaPx: Float) {
        state.dispatchRawDelta(deltaPx)
    }
}

internal val LocalRecordTreeScroller = staticCompositionLocalOf<RecordTreeScroller?> { null }

internal val LocalRecordFieldActions = staticCompositionLocalOf<RecordFieldActions?> { null }

/**
 * Reads the text a tree's selection container has selected right now. The container's own menu knows it (its text manager), so
 * [ProvideSelectionProbe] lends that manager to the field menu, which then offers "Copy" for the selected text next to the field's items.
 */
internal class TreeSelectionProbe {
    var manager: TextContextMenu.TextManager? = null

    /** The selected text, or "" when nothing is selected. */
    fun selectedText(): String {
        val current = manager ?: return ""
        return if (current.copy?.enabled == true) current.selectedText.text else ""
    }
}

/** Wraps a tree's selection container so [probe] sees its text manager; the container's own menu is the surrounding one. */
@Composable
internal fun ProvideSelectionProbe(probe: TreeSelectionProbe, content: @Composable () -> Unit) {
    val outer = LocalTextContextMenu.current
    val menu = remember(outer, probe) {
        object : TextContextMenu {
            @Composable
            override fun Area(textManager: TextContextMenu.TextManager, state: ContextMenuState, content: @Composable () -> Unit) {
                SideEffect { probe.manager = textManager }
                outer.Area(textManager, state, content)
            }
        }
    }
    CompositionLocalProvider(LocalTextContextMenu provides menu, content = content)
}

/** The wiring of one record's tree: which record it is, what is picked in it and its JSON (for Copy value). */
internal class RecordFieldContext(
    val actions: RecordFieldActions,
    val tabId: String,
    val entryId: Int,
    val selected: Set<List<String>>,
    val source: String,
    val probe: TreeSelectionProbe = TreeSelectionProbe(),
    val geometry: RecordTreeGeometry = RecordTreeGeometry(),
    /** Gives keyboard focus to the tree (a click on a row, a closed menu): the arrow keys and Copy then act on its picked rows. */
    val focusTree: () -> Unit = {},
    /** Drops the text selected in the tree (a row drag takes over from it). */
    val clearTextSelection: () -> Unit = {},
    /** The visible order [selected] was made against ([RecordFieldActions.pickOrder]), or null: the rows laid out now are read then. */
    val selectedOrder: List<List<String>>? = null,
) {
    private val coverage by lazy(LazyThreadSafetyMode.NONE) { FieldPickCoverage(selected) }

    /** Whether the row of [path] is marked by the pick: picked itself, or inside a picked field that stands for its subtree. */
    fun isMarked(path: List<String>): Boolean = coverage.covers(path)

    /** How the row of [path] shows the pick: picked itself, included because a picked container holds it, or not at all. */
    fun markOf(path: List<String>): FieldRowMark = when {
        path in selected -> FieldRowMark.PICKED
        coverage.ownerAbove(path) != null -> FieldRowMark.INCLUDED
        else -> FieldRowMark.NONE
    }

    /**
     * The visible fields the pick is read against: the ones it was made against, else (a plain click, or a pick made with no tree laid
     * out) the rows laid out now.
     */
    fun pickOrder(): List<List<String>> = selectedOrder ?: geometry.order()

    /** What the pick adds and copies, as [normalizedPick] reads it for the rows the pick was made against. */
    fun pickedFields(): List<List<String>> = normalizedPick(pickOrder(), selected)

    /** The pick just made (a range, a drag, a toggle, a keyboard extension) stands for the rows laid out now, whatever opens or closes later. */
    fun notePickOrder() = actions.notePickOrder(tabId, entryId, geometry.order())

    /**
     * The pick as what it stands for, read live: [normalizedPick] against the rows it was made against, which is what it adds and copies.
     * Every action that builds on the pick (a Ctrl/Cmd toggle, Ctrl/Cmd+Shift range or drag, a keyboard extension) starts from this set
     * and not from the raw one, whose helper rows (the container a range was dragged from) would otherwise stand for their whole subtree
     * once a collapse hides what they were helping to pick. The new pick is then made against, and stored with, the rows laid out now.
     */
    fun canonicalPick(): Set<List<String>> {
        val picked = actions.selectedPaths(tabId, entryId)
        if (picked.isEmpty()) return picked
        return normalizedPick(actions.pickOrder(tabId, entryId) ?: geometry.order(), picked).toCollection(LinkedHashSet())
    }

    /**
     * Whether a Shift-press would pick a range of fields: there is an anchor and no text is selected (with text selected it extends
     * that selection instead). Read as the press arrives, before the text selection reacts to it.
     */
    fun shiftClickPicksRange(): Boolean = actions.rangeAnchor(tabId, entryId) != null && probe.selectedText().isEmpty()

    /** A plain press on [path] is where a later Shift-click range starts. */
    fun anchorAt(path: List<String>) = actions.setRangeAnchor(tabId, entryId, path)

    /**
     * A click on the row of [path], as on a log row: the pick becomes just that field, which is also where a Shift-click range and the
     * arrow keys start from. [takeFocus] gives the tree keyboard focus (which also drops a text selection a click should not leave
     * behind); the second click of a double-click keeps focus where it is, so the word it selects stays selected.
     */
    fun clickSelect(path: List<String>, takeFocus: Boolean) {
        if (geometry.rowDragged) return
        moveSelectionTo(path)
        if (takeFocus) focusTree()
    }

    /** Makes just [path] the pick, the anchor and the keyboard's cursor. */
    fun moveSelectionTo(path: List<String>) {
        actions.pickFields(tabId, entryId, listOf(path), additive = false)
        actions.setRangeAnchor(tabId, entryId, path)
        geometry.cursor = path
    }

    /** A right-click on the row of [path]: a row that is not part of the pick (or of a text selection) is picked first, as on a log row. */
    fun selectForMenu(path: List<String>) {
        if (FieldPickCoverage(actions.selectedPaths(tabId, entryId)).covers(path)) return
        val covered = geometry.textSelectionFields(probe.selectedText())?.covered
        if (covered != null && path in covered) return
        moveSelectionTo(path)
    }

    /** A text drag begins: the picked rows (of this record or another) make way for the text. */
    fun dropPick() = actions.pickFields(tabId, entryId, emptyList(), additive = false)

    /**
     * Picks the fields from the anchor to [path]: every row that lies between them, as a text selection covers them (just [path] when
     * the anchor is not in the tree any more); what is added or copied is [pickedFields], the normalised pick. [additive] (Shift with
     * Ctrl/Cmd) keeps the rest of the pick, with the rows a picked container stands for. False when there is no anchor.
     */
    fun pickRangeTo(path: List<String>, additive: Boolean): Boolean {
        val anchor = actions.rangeAnchor(tabId, entryId) ?: return false
        val between = geometry.between(anchor, path) ?: setOf(path)
        val base = if (additive) canonicalPick() else emptySet()
        val covered = between - standInRows(geometry.order(), base, between, path)
        val kept = if (additive) markedFieldRows(geometry.order(), base) else emptyList()
        val keptSet = kept.toHashSet()
        actions.pickFields(tabId, entryId, kept + covered.filter { it !in keptSet }, additive = false)
        notePickOrder()
        geometry.cursor = path
        return true
    }

    /** A press on [start] was dragged into another row: the text selection makes way and the rows between become the pick. */
    fun beginRowDrag(start: List<String>) {
        geometry.rowDragged = true
        geometry.forgetSelection()
        clearTextSelection()
        actions.setRowDragActive(true)
        actions.setRangeAnchor(tabId, entryId, start)
    }

    /**
     * The row drag from [start] is at [current]: the pick is the rows between them (all of them, as a Shift-click range keeps them),
     * together with [kept] (the pick a Ctrl/Cmd drag adds to, with the rows a picked container stands for). The anchor stays at [start]
     * and the keyboard continues from [current].
     */
    fun pickDragRange(start: List<String>, current: List<String>, kept: Set<List<String>>?) {
        val order = geometry.order()
        val between = geometry.between(start, current) ?: setOf(current)
        val range = if (kept == null) between else between - standInRows(order, kept, between, current)
        val all = if (kept == null) range else markedFieldRows(order, kept).toSet() + range
        actions.pickFields(tabId, entryId, order.filter { it in all } + all.filter { it !in order }, additive = false)
        notePickOrder()
        geometry.cursor = current
    }

    /** The mouse was released after a row drag: the tree takes keyboard focus, as after a click. */
    fun endRowDrag() {
        actions.setRowDragActive(false)
        focusTree()
    }

    /**
     * A Ctrl/Cmd-click on [path]: adds it to the pick or takes it out ([toggledFieldPick]: a field inside a picked container takes the
     * container's other visible fields as the pick instead), and the keyboard continues from it.
     */
    fun toggleRow(path: List<String>) {
        val picked = actions.selectedPaths(tabId, entryId)
        val base = canonicalPick()
        val next = toggledFieldPick(geometry.order(), base, path)
        if (base == picked && next == (if (path in picked) picked - setOf(path) else picked + setOf(path))) {
            actions.toggleSelected(tabId, entryId, path)
        } else {
            actions.pickFields(tabId, entryId, next.toList(), additive = false)
            actions.setRangeAnchor(tabId, entryId, path)
        }
        notePickOrder()
        geometry.cursor = path
    }
}

/** How a field row shows the pick: [PICKED] the full selection fill, [INCLUDED] (inside a picked container) a lighter one. */
internal enum class FieldRowMark { NONE, PICKED, INCLUDED }

/** What a field's menu acts on, and so which items it offers. */
internal sealed interface FieldMenuTargets {
    val paths: List<List<String>>

    /** Just the field under the pointer: add it, copy its value, copy its path. */
    data class One(val path: List<String>) : FieldMenuTargets {
        override val paths get() = listOf(path)
    }

    /** The Ctrl/Cmd-click pick the pointer is on. */
    data class Pick(override val paths: List<List<String>>) : FieldMenuTargets

    /** The fields a text selection covers, which the pointer is inside. */
    data class Selection(override val paths: List<List<String>>) : FieldMenuTargets
}

/**
 * What the menu of [clicked] acts on: the pick ([selected], the normalised pick) when the pointer is on a marked row ([clickedPicked]:
 * more than one field is picked, or the row is only marked through a container), else the fields of the text selection when the pointer
 * is inside it and it covers something other than just that field, else the field itself.
 */
internal fun fieldMenuTargets(
    selected: Set<List<String>>,
    clicked: List<String>,
    selection: TextSelectionFields?,
    clickedPicked: Boolean = clicked in selected,
): FieldMenuTargets = when {
    clickedPicked && (selected.size > 1 || clicked !in selected) -> FieldMenuTargets.Pick(selected.toList())
    selection != null && clicked in selection.covered && selection.fields != listOf(clicked) -> FieldMenuTargets.Selection(selection.fields)
    else -> FieldMenuTargets.One(clicked)
}

/** The menu's "Add" label: "Add field to notes", or "Add 3 fields to notes". */
internal fun addFieldsLabel(count: Int): String = if (count == 1) "Add field to notes" else "Add $count fields to notes"

/** The label for fields chosen by a text selection or a pick, as the log row's menu has it: "Add selected field to notes", "Add 3 selected fields to notes". */
internal fun addSelectedFieldsLabel(count: Int): String =
    if (count == 1) "Add selected field to notes" else "Add $count selected fields to notes"

internal fun recordFieldTooLargeNotice(chars: Int): DiagramNotice = DiagramNotice(
    "Too much to add",
    "The chosen fields hold $chars characters of JSON; a note keeps at most $RECORD_FIELDS_MAX_JSON_CHARS. Choose fewer or smaller fields.",
)

private class AppRecordFieldActions(private val state: AppState, private val reclaim: () -> Unit) : RecordFieldActions {
    private fun sectionOf(tabId: String): String? = state.tab(tabId)?.selectedLogSectionId

    override fun selectedPaths(tabId: String, entryId: Int): Set<List<String>> {
        val selection = state.recordFieldSelection ?: return emptySet()
        return if (selection.tabId == tabId && selection.entryId == entryId && selection.sectionId == sectionOf(tabId)) selection.paths else emptySet()
    }

    override fun toggleSelected(tabId: String, entryId: Int, path: List<String>) {
        val current = selectedPaths(tabId, entryId)
        val next = if (path in current) current.minusElement(path) else current.plusElement(path)
        state.recordFieldSelection = next.takeIf { it.isNotEmpty() }?.let { RecordFieldSelection(tabId, sectionOf(tabId), entryId, it) }
        setRangeAnchor(tabId, entryId, path)
    }

    override fun rangeAnchor(tabId: String, entryId: Int): List<String>? {
        val anchor = state.recordFieldAnchor ?: return null
        val here = anchor.tabId == tabId && anchor.entryId == entryId && anchor.sectionId == sectionOf(tabId)
        return if (here) anchor.paths.firstOrNull() else null
    }

    override fun setRangeAnchor(tabId: String, entryId: Int, path: List<String>) {
        state.recordFieldAnchor = RecordFieldSelection(tabId, sectionOf(tabId), entryId, setOf(path))
    }

    private fun pickHere(tabId: String, entryId: Int): RecordFieldSelection? =
        state.recordFieldSelection?.takeIf { it.tabId == tabId && it.entryId == entryId && it.sectionId == sectionOf(tabId) }

    override fun pickOrder(tabId: String, entryId: Int): List<List<String>>? = pickHere(tabId, entryId)?.order

    override fun notePickOrder(tabId: String, entryId: Int, order: List<List<String>>) {
        pickHere(tabId, entryId)?.let { state.recordFieldSelection = it.copy(order = order) }
    }

    override fun pickFields(tabId: String, entryId: Int, paths: List<List<String>>, additive: Boolean) {
        val next = if (additive) selectedPaths(tabId, entryId) + paths else paths.toSet()
        state.recordFieldSelection = next.takeIf { it.isNotEmpty() }?.let { RecordFieldSelection(tabId, sectionOf(tabId), entryId, it) }
    }

    override fun registerTree(handle: RecordTreeHandle): () -> Unit = state.recordFieldTrees.register(handle)

    override fun addToNotes(tabId: String, entryId: Int, paths: List<List<String>>) {
        state.requestAddRecordFields(tabId, entryId, paths)
    }

    override fun copyText(text: String) = state.copyToClipboard(text)

    override fun openRequest(tabId: String, entryId: Int): RecordFieldOpenRequest? =
        state.recordFieldOpenRequest?.takeIf { it.tabId == tabId && it.entryId == entryId && it.sectionId == sectionOf(tabId) }

    override fun reclaimFocus() = reclaim()

    override fun noteTreePress() {
        state.recordFieldTrees.pressInTree = true
    }

    override fun setRowDragActive(active: Boolean) {
        state.recordFieldTrees.rowDragActive = active
    }

    override fun rowDragActive(): Boolean = state.recordFieldTrees.rowDragActive
}

/** The actions for a log view; [reclaimFocus] returns keyboard focus to the view after a field menu closes. */
internal fun AppState.recordFieldActions(reclaimFocus: () -> Unit): RecordFieldActions = AppRecordFieldActions(this, reclaimFocus)

/** Clears the pick; true when there was one (so Esc is only taken when it did something). */
internal fun AppState.clearRecordFieldSelection(): Boolean {
    if (recordFieldSelection == null) return false
    recordFieldSelection = null
    return true
}

/**
 * What "Add selected fields to notes" on the log row of record [entryId] in [tabId] adds: the fields picked with Ctrl/Cmd-click, else
 * the fields the record's tree had text-selected when the menu's right press began. Empty when there are none.
 */
internal fun AppState.recordFieldsForRowMenu(tabId: String, entryId: Int): List<List<String>> {
    val pick = recordFieldSelection
    if (pick != null && pick.tabId == tabId && pick.entryId == entryId && pick.sectionId == tab(tabId)?.selectedLogSectionId) {
        return normalizedPickOfRecord(tabId, entryId, pick.paths, pick.order)
    }
    return recordFieldTrees.selectedAtRightPress(tabId, entryId)
}

/**
 * [pick] of record [entryId] as what it adds: normalised against the rows it was made against ([madeAgainst], see
 * [RecordFieldSelection.order]), else the rows its mounted tree shows, else (no tree is laid out, so there is no geometry) the
 * record's own fields in document order, else the pick as it is.
 */
internal fun AppState.normalizedPickOfRecord(
    tabId: String,
    entryId: Int,
    pick: Set<List<String>>,
    madeAgainst: List<List<String>>? = null,
): List<List<String>> {
    val order = madeAgainst ?: recordFieldTrees.visibleOrder(tabId, entryId)
        ?: tab(tabId)?.rmap?.get(entryId)?.recordInspectionSource()?.let(::recordFieldsInDocumentOrder)
        ?: return pick.toList()
    return normalizedPick(order, pick)
}

/** The log row menu's "Add N selected fields to notes" for record [entryId] of [tabId], or null when no fields are picked or text-selected. */
internal fun AppState.recordFieldsMenuEntry(tabId: String, entryId: Int): CtxMenuEntry.Action? {
    val fields = recordFieldsForRowMenu(tabId, entryId).takeIf { it.isNotEmpty() } ?: return null
    return CtxMenuEntry.Action(Icons.AutoMirrored.Outlined.PlaylistAdd, addSelectedFieldsLabel(fields.size)) {
        ctx = null
        requestAddRecordFields(tabId, entryId, fields)
    }
}

/**
 * Freezes [paths] of row [entryId] in [tabId] and opens the add-annotation dialog for them, aimed at the note the tab shows (its own,
 * or a shared one) exactly like a row reference. A selection with too much JSON, or a record that is gone, shows a notice instead.
 */
internal fun AppState.requestAddRecordFields(tabId: String, entryId: Int, paths: List<List<String>>) {
    val entry = tab(tabId)?.rmap?.get(entryId) ?: return
    when (val result = recordFieldRefs(entry.recordInspectionSource(), paths)) {
        is RecordFieldsResult.Ok -> {
            recordFieldSelection = null
            recordFieldAnchor = null
            requestAddAnn(tabId, listOf(entryId), recordFields = result.fields)
        }
        is RecordFieldsResult.TooLarge -> pendingDiagramNotice = recordFieldTooLargeNotice(result.chars)
        RecordFieldsResult.Unavailable ->
            pendingDiagramNotice = DiagramNotice("Fields not found", "This record no longer has the chosen fields.")
    }
}

/**
 * Opens the record of row [entryId] in [tabId] and asks its tree to open [paths] and to scroll the first one into view; "Go to rows" on
 * a fields reference. The fields are also picked, so the tree marks them with the pick's tint until Esc or a plain click clears it.
 */
internal fun AppState.openRecordFields(tabId: String, entryId: Int, paths: Set<List<String>>) {
    if (!recordExpansion(tabId).isExpanded(entryId)) toggleRecordExpansion(tabId, entryId)
    val sectionId = tab(tabId)?.selectedLogSectionId
    recordFieldSelection = paths.takeIf { it.isNotEmpty() }?.let { RecordFieldSelection(tabId, sectionId, entryId, it) }
    recordFieldOpenRequest = RecordFieldOpenRequest(
        nonce = (recordFieldOpenRequest?.nonce ?: 0) + 1,
        tabId = tabId,
        sectionId = sectionId,
        entryId = entryId,
        paths = paths,
    )
}

/**
 * Watches a field row's presses without taking part in the ones it does not use: a right press asks for the menu at the pointer (consumed,
 * so the text selection around the tree does not open its own menu), a Ctrl/Cmd-press toggles the field in the pick, a plain press tells
 * [onPlainPress] (the anchor of a later Shift-click range) and goes on to the text selection. A plain press that is released without
 * moving beyond touch slop is a click on the row and calls [onClick] (true for the second click of a double-click); one that moves is a
 * text drag and is not. A press left of [clickStart] (the row's indent and disclosure chevron, which has its own click) is no row click.
 * A press with Shift is the tree's ([trackTextSelection] picks the range or extends the selection), so it is left alone here.
 */
internal fun Modifier.recordFieldGestures(
    key: Any,
    onToggle: () -> Unit,
    onMenu: (Offset) -> Unit,
    onPlainPress: () -> Unit = {},
    onClick: (repeated: Boolean) -> Unit = {},
    clickStart: Dp = 0.dp,
): Modifier =
    pointerInput(key) {
        awaitPointerEventScope {
            val click = RowClick(viewConfiguration.touchSlop, viewConfiguration.doubleTapTimeoutMillis)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                when (event.type) {
                    PointerEventType.Press -> {
                        click.cancel()
                        val keys = event.keyboardModifiers
                        when {
                            event.buttons.isSecondaryPressed -> {
                                change.consume()
                                onMenu(change.position)
                            }
                            !event.buttons.isPrimaryPressed || keys.isShiftPressed -> Unit
                            keys.isCtrlPressed || keys.isMetaPressed -> {
                                change.consume()
                                onToggle()
                            }
                            else -> {
                                onPlainPress()
                                if (!change.isConsumed && change.position.x >= clickStart.toPx()) click.press(change.position, change.uptimeMillis)
                            }
                        }
                    }
                    // A pointer that leaves the row while pressed is a drag too (it arrives as Exit, not Move).
                    PointerEventType.Move, PointerEventType.Exit -> click.moved(change.position)
                    PointerEventType.Release -> if (click.release(change.position)) onClick(click.repeated)
                }
            }
        }
    }

/** One row's plain click in progress: a press that is released within touch slop of where it began, and whether it is a double-click's second. */
private class RowClick(private val slop: Float, private val doubleTapMs: Long) {
    private var at: Offset? = null
    private var lastMs = NO_CLICK

    /** Whether the click just released was the second of a double-click. */
    var repeated = false
        private set

    fun press(position: Offset, uptimeMs: Long) {
        at = position
        repeated = uptimeMs - lastMs <= doubleTapMs
        lastMs = if (repeated) NO_CLICK else uptimeMs
    }

    fun cancel() {
        at = null
    }

    fun moved(position: Offset) {
        val start = at ?: return
        if ((position - start).getDistance() > slop) at = null
    }

    /** True when the release at [position] ends a click (the press stayed within slop), false when it ends a drag or no click. */
    fun release(position: Offset): Boolean {
        val start = at ?: return false
        at = null
        return (position - start).getDistance() <= slop
    }
}

/** "No earlier click": far enough in the past that the next click never counts as the second of a double-click. */
private const val NO_CLICK = Long.MIN_VALUE / 2

/**
 * A plain left press anywhere in the view clears the pick, except one that lands in a record tree (which picks, or starts a text
 * drag that clears it, itself) and a Ctrl/Cmd press (which builds it); a right press opens a menu on it. The clearing runs after
 * the tree has seen the press, so a click on a picked row never flickers through "nothing picked".
 */
internal fun Modifier.clearRecordFieldSelectionOnPlainPress(state: AppState): Modifier =
    onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
        if (event.buttons.isPrimaryPressed) {
            state.recordFieldTrees.clearCaptured()
            state.recordFieldTrees.pressInTree = false
        }
    }.onPointerEvent(PointerEventType.Press, PointerEventPass.Final) { event ->
        val keys = event.keyboardModifiers
        val plain = event.buttons.isPrimaryPressed && !keys.isCtrlPressed && !keys.isMetaPressed
        if (plain && !state.recordFieldTrees.pressInTree) state.clearRecordFieldSelection()
    }

/**
 * A right press anywhere in the view first notes what each record tree's text selection covers, before anything reacts to the press
 * (a right-click on a log row takes keyboard focus, and the tree then drops its selection), for the log row's menu.
 */
internal fun Modifier.captureRecordFieldSelectionOnRightPress(state: AppState): Modifier =
    onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
        if (event.buttons.isSecondaryPressed) state.recordFieldTrees.captureAtRightPress()
    }

/** Esc clears the pick when there is one. */
internal fun AppState.handleRecordFieldEscape(event: KeyEvent): Boolean =
    event.type == KeyEventType.KeyDown && event.key == Key.Escape && clearRecordFieldSelection()

/**
 * What the menu of the field at [path] offers: "Copy" for the text selected in the tree when there is some ([selectedText]), then add
 * what the menu acts on ([targets]: the field, the pick or the text selection's fields) to the notes and, for a lone field, copy its
 * value and its path.
 */
private fun recordFieldMenuItems(context: RecordFieldContext, path: List<String>, selectedText: String, targets: FieldMenuTargets): List<ContextMenuItem> {
    val items = mutableListOf<ContextMenuItem>()
    if (selectedText.isNotEmpty()) {
        items += IndagiumContextMenuItem("Copy", true, { context.actions.copyText(selectedText) }, Icons.Outlined.ContentCopy)
    }
    val label = when (targets) {
        is FieldMenuTargets.Selection -> addSelectedFieldsLabel(targets.paths.size)
        else -> addFieldsLabel(targets.paths.size)
    }
    items += IndagiumContextMenuItem(
        label,
        true,
        { context.actions.addToNotes(context.tabId, context.entryId, targets.paths) },
        Icons.AutoMirrored.Outlined.PlaylistAdd,
    )
    if (targets is FieldMenuTargets.One) {
        items += IndagiumContextMenuItem(
            "Copy value",
            true,
            {
                val value = parseRecordJson(context.source)?.let { jsonSubtreeAt(it, path) }
                if (value != null) context.actions.copyText(recordValueText(value))
            },
            Icons.Outlined.ContentCopy,
        )
        items += IndagiumContextMenuItem("Copy path", true, { context.actions.copyText(joinRecordPath(path)) }, Icons.Outlined.Link)
    }
    return items
}

/**
 * The menu of one field row, opened at [at] (a position in the row), drawn with the app's context-menu look. Gives keyboard focus back
 * to the log view when it closes, however it closed.
 */
@Composable
internal fun RecordFieldMenu(context: RecordFieldContext, path: List<String>, at: Offset, onClosed: () -> Unit) {
    val state = remember(at) { ContextMenuState().also { it.status = ContextMenuState.Status.Open(Rect(at, at)) } }
    // Read once, as the menu opens: the text selected in the tree is what "Copy" copies, and the fields it covers what it can add.
    val selectedText = remember(at) { context.probe.selectedText() }
    val targets = remember(at) {
        fieldMenuTargets(
            LinkedHashSet(context.pickedFields()),
            path,
            context.geometry.textSelectionFields(selectedText),
            clickedPicked = context.isMarked(path),
        )
    }
    val closed by rememberUpdatedState(onClosed)
    LaunchedEffect(state.status) {
        if (state.status is ContextMenuState.Status.Closed) {
            closed()
            context.actions.reclaimFocus()
            // Picked rows keep the keyboard (arrow keys, Copy) once the menu is gone.
            if (context.actions.selectedPaths(context.tabId, context.entryId).isNotEmpty()) context.focusTree()
        }
    }
    // The menu opens inside the tree's SelectionContainer; its texts must not register as selectable text there.
    DisableSelection { LocalContextMenuRepresentation.current.Representation(state) { recordFieldMenuItems(context, path, selectedText, targets) } }
}
