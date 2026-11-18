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
)

/** Whether [paths] of [selected] picks more than one field, so a menu on one of them offers all of them. */
internal fun fieldsForMenu(selected: Set<List<String>>, clicked: List<String>): List<List<String>> =
    if (clicked in selected && selected.size > 1) selected.toList() else listOf(clicked)

/** The menu's "Add" label: "Add field to notes", or "Add 3 fields to notes". */
internal fun addFieldsLabel(count: Int): String = if (count == 1) "Add field to notes" else "Add $count fields to notes"

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
    }

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
 * Freezes [paths] of row [entryId] in [tabId] and opens the add-annotation dialog for them, aimed at the note the tab shows (its own,
 * or a shared one) exactly like a row reference. A selection with too much JSON, or a record that is gone, shows a notice instead.
 */
internal fun AppState.requestAddRecordFields(tabId: String, entryId: Int, paths: List<List<String>>) {
    val entry = tab(tabId)?.rmap?.get(entryId) ?: return
    when (val result = recordFieldRefs(entry.rawRecord, paths)) {
        is RecordFieldsResult.Ok -> {
            recordFieldSelection = null
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
 * Watches a field row's presses without taking part in the ones it does not use: a right press asks for the menu at the pointer, a
 * Ctrl/Cmd-press toggles the field in the pick (both consumed, so the text selection around the tree does not start or open its own
 * menu). Double-click toggling and plain presses are left alone.
 */
internal fun Modifier.recordFieldGestures(key: Any, onToggle: () -> Unit, onMenu: (Offset) -> Unit): Modifier =
    pointerInput(key) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                if (event.type != PointerEventType.Press) continue
                when {
                    event.buttons.isSecondaryPressed -> {
                        change.consume()
                        onMenu(change.position)
                    }
                    event.buttons.isPrimaryPressed && (event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed) -> {
                        change.consume()
                        onToggle()
                    }
                }
            }
        }
    }

/** A plain left press anywhere in the view clears the pick (a Ctrl/Cmd press builds it, a right press opens a menu on it). */
internal fun Modifier.clearRecordFieldSelectionOnPlainPress(state: AppState): Modifier =
    onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
        val keys = event.keyboardModifiers
        if (event.buttons.isPrimaryPressed && !keys.isCtrlPressed && !keys.isMetaPressed) state.clearRecordFieldSelection()
    }

/** Esc clears the pick when there is one. */
internal fun AppState.handleRecordFieldEscape(event: KeyEvent): Boolean =
    event.type == KeyEventType.KeyDown && event.key == Key.Escape && clearRecordFieldSelection()

/**
 * What the menu of the field at [path] offers: "Copy" for the text selected in the tree when there is some ([selectedText]), then add
 * the field (or the whole pick) to the notes, copy its value, copy its path.
 */
private fun recordFieldMenuItems(context: RecordFieldContext, path: List<String>, selectedText: String): List<ContextMenuItem> {
    val targets = fieldsForMenu(context.selected, path)
    val items = mutableListOf<ContextMenuItem>()
    if (selectedText.isNotEmpty()) {
        items += IndagiumContextMenuItem("Copy", true, { context.actions.copyText(selectedText) }, Icons.Outlined.ContentCopy)
    }
    items += IndagiumContextMenuItem(
        addFieldsLabel(targets.size),
        true,
        { context.actions.addToNotes(context.tabId, context.entryId, targets) },
        Icons.AutoMirrored.Outlined.PlaylistAdd,
    )
    if (targets.size == 1) {
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
    // Read once, as the menu opens: the text selected in the tree is what "Copy" copies.
    val selectedText = remember(at) { context.probe.selectedText() }
    val closed by rememberUpdatedState(onClosed)
    LaunchedEffect(state.status) {
        if (state.status is ContextMenuState.Status.Closed) {
            closed()
            context.actions.reclaimFocus()
        }
    }
    // The menu opens inside the tree's SelectionContainer; its texts must not register as selectable text there.
    DisableSelection { LocalContextMenuRepresentation.current.Representation(state) { recordFieldMenuItems(context, path, selectedText) } }
}
