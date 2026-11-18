@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.indagium.ui

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
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
import com.indagium.utils.RECORD_FIELDS_MAX_JSON_CHARS
import com.indagium.utils.RecordFieldsResult
import com.indagium.utils.joinRecordPath
import com.indagium.utils.jsonSubtreeAt
import com.indagium.utils.parseRecordJson
import com.indagium.utils.recordFieldRefs
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

    /** Makes [paths] the pick of the record (or adds them to it when [additive]); the range anchor stays where it is. */
    fun pickFields(tabId: String, entryId: Int, paths: List<List<String>>, additive: Boolean) = Unit

    /** Lets the log view look at a mounted tree (for the log row's menu); the returned function takes it out again. */
    fun registerTree(handle: RecordTreeHandle): () -> Unit = {}
}

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
) {
    /**
     * Whether a Shift-press would pick a range of fields: there is an anchor and no text is selected (with text selected it extends
     * that selection instead). Read as the press arrives, before the text selection reacts to it.
     */
    fun shiftClickPicksRange(): Boolean = actions.rangeAnchor(tabId, entryId) != null && probe.selectedText().isEmpty()

    /** A plain press on [path] is where a later Shift-click range starts. */
    fun anchorAt(path: List<String>) = actions.setRangeAnchor(tabId, entryId, path)

    /**
     * Picks the fields from the anchor to [path]: what lies between them, normalised like a text selection (just [path] when the
     * anchor is not in the tree any more). [additive] (Shift with Ctrl/Cmd) keeps the rest of the pick. False when there is no anchor.
     */
    fun pickRangeTo(path: List<String>, additive: Boolean): Boolean {
        val anchor = actions.rangeAnchor(tabId, entryId) ?: return false
        val covered = geometry.between(anchor, path) ?: setOf(path)
        val picked = normalizeFieldPaths(geometry.order(), covered).ifEmpty { listOf(path) }
        actions.pickFields(tabId, entryId, picked, additive)
        return true
    }
}

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
 * What the menu of [clicked] acts on: the pick when the pointer is on a picked row (more than one is picked), else the fields of the text
 * selection when the pointer is inside it and it covers something other than just that field, else the field itself.
 */
internal fun fieldMenuTargets(selected: Set<List<String>>, clicked: List<String>, selection: TextSelectionFields?): FieldMenuTargets = when {
    clicked in selected && selected.size > 1 -> FieldMenuTargets.Pick(selected.toList())
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
        return pick.paths.toList()
    }
    return recordFieldTrees.selectedAtRightPress(tabId, entryId)
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
    when (val result = recordFieldRefs(entry.rawRecord, paths)) {
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
 * so the text selection around the tree does not open its own menu), a Ctrl/Cmd-press toggles the field in the pick, a plain press only
 * tells [onPlainPress] (the anchor of a later Shift-click range) and goes on to the text selection. A press with Shift is the
 * tree's ([trackTextSelection] picks the range or extends the selection), so it is left alone here.
 */
internal fun Modifier.recordFieldGestures(
    key: Any,
    onToggle: () -> Unit,
    onMenu: (Offset) -> Unit,
    onPlainPress: () -> Unit = {},
): Modifier =
    pointerInput(key) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                if (event.type != PointerEventType.Press) continue
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
                    else -> onPlainPress()
                }
            }
        }
    }

/** A plain left press anywhere in the view clears the pick (a Ctrl/Cmd press builds it, a right press opens a menu on it). */
internal fun Modifier.clearRecordFieldSelectionOnPlainPress(state: AppState): Modifier =
    onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
        val keys = event.keyboardModifiers
        if (event.buttons.isPrimaryPressed) state.recordFieldTrees.clearCaptured()
        if (event.buttons.isPrimaryPressed && !keys.isCtrlPressed && !keys.isMetaPressed) state.clearRecordFieldSelection()
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
        fieldMenuTargets(context.selected, path, context.geometry.textSelectionFields(selectedText))
    }
    val closed by rememberUpdatedState(onClosed)
    LaunchedEffect(state.status) {
        if (state.status is ContextMenuState.Status.Closed) {
            closed()
            context.actions.reclaimFocus()
        }
    }
    // The menu opens inside the tree's SelectionContainer; its texts must not register as selectable text there.
    DisableSelection { LocalContextMenuRepresentation.current.Representation(state) { recordFieldMenuItems(context, path, selectedText, targets) } }
}
