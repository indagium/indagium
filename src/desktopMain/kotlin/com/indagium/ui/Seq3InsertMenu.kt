package com.indagium.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.runtime.Composable
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3RowRef
import com.indagium.diagram3.applySeq3Command
import com.indagium.model.LogEntry

/** Which dialog a [Seq3PendingInsertRequest] opens first. There is no chooser step: each menu entry
 *  names the way it adds a message, and cancelling a dialog closes the request. */
internal enum class Seq3InsertStart { CUSTOM, ROWS }

/** How many rows are selected in the diagram's source log tab (0 without a source tab). */
internal fun seq3SelectedLogRowCount(state: AppState, session: Seq3WorkspaceSession): Int =
    session.sourceTabId?.let(state::tab)?.selected?.size ?: 0

/** The menu label of the one-click insertion of the source tab's selection. */
internal fun seq3AddSelectedRowsLabel(count: Int): String =
    "Add $count selected log ${if (count == 1) "row" else "rows"} after this"

/** Applies [command], an insertion after some row: a command the model refuses leaves a hint for the
 *  header instead of a silent no-op. Returns whether the document changed. */
internal fun seq3ApplyInsertion(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState, command: Seq3Command): Boolean {
    val preview = applySeq3Command(session.document, command)
    if (!preview.applied) {
        view.messageAddHint = preview.reason ?: "The message could not be added after this row"
        return false
    }
    val applied = state.seq3Sessions.applyCommand(session.id, command)
    view.messageAddHint = if (applied) null else "The message could not be added after this row"
    if (applied) runCatching { view.focusRequester.requestFocus() }
    return applied
}

/** Inserts the source tab's selected log rows as one message after [after]. */
internal fun seq3AddSelectedRowsAfter(state: AppState, session: Seq3WorkspaceSession, view: Seq3ViewState, after: Seq3RowRef): Boolean {
    val tab = session.sourceTabId?.let(state::tab) ?: return false
    val entries: List<LogEntry> = seq3ResolveSelectedEntries(tab.logData, tab.selected)
    return seq3ApplyInsertion(state, session, view, Seq3Command.AddSelectionMessage(entries, seq3InsertionPositionAfter(after)))
}

/**
 * The "add a message after this row" entries shared by the canvas arrow menu and the Messages list
 * menu: the custom-message dialog straight away, the source tab's selected rows in one click (only
 * while some are selected), and a "From log" submenu for the other log-backed ways. [dismiss] closes
 * the hosting menu. The header's "+ message" menu is a different menu and does not use this.
 */
@Composable
internal fun Seq3AddAfterMenuItems(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    after: Seq3RowRef,
    canvasPointX: Double? = null,
    canvasPointY: Double? = null,
    preferLeft: Boolean = false,
    dismiss: () -> Unit,
    customItemTag: String? = null,
) {
    val selectedCount = seq3SelectedLogRowCount(state, session)

    fun request(start: Seq3InsertStart) {
        view.pendingInsertRequest = Seq3PendingInsertRequest(after, canvasPointX, canvasPointY, start)
        dismiss()
    }
    CtxItem(Icons.Outlined.Add, "Add custom message after this…", tag = customItemTag) { request(Seq3InsertStart.CUSTOM) }
    if (selectedCount > 0) {
        CtxItem(Icons.AutoMirrored.Outlined.PlaylistAdd, seq3AddSelectedRowsLabel(selectedCount)) {
            dismiss()
            seq3AddSelectedRowsAfter(state, session, view, after)
        }
    }
    CtxItemWithSubmenu(
        icon = Icons.Outlined.ListAlt,
        label = "From log",
        submenu = listOf(
            CtxSubmenuOption("Enter row numbers…") { request(Seq3InsertStart.ROWS) },
            CtxSubmenuOption("Open log view to select rows", enabled = session.sourceTabId != null) {
                session.sourceTabId?.let(state::activateTab)
                dismiss()
            },
        ),
        preferLeft = preferLeft,
        triggerTag = "seq3-add-after-from-log",
        onClick = {},
    )
}
