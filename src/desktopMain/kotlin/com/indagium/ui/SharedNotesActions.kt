package com.indagium.ui

import com.indagium.model.AnnBlock
import com.indagium.model.LogFormat
import com.indagium.model.LogTab
import com.indagium.model.SharedNoteInfo
import com.indagium.model.VideoFrameReference

// What the Notes header's New ▾ and Open ▾ menus need to offer shared notes (see SharedNotes.kt for the operations), kept apart from
// NotesActionModel so a panel used without a tab (and every test that builds the model) simply passes none and keeps its plain buttons.

/** The New ▾ menu's items and the Open ▾ menu's note sections for the tab whose Notes tile is shown. */
internal class NotesSharedActions(
    /** The tab's own note's label in Open ▾ ("<file> notes"). */
    val ownNoteLabel: String,
    /** The shared notes the tab is a member of, in creation order. */
    val sharedNotes: List<SharedNoteInfo>,
    /** Every other shared note: choosing one adds the tab as a member. */
    val otherSharedNotes: List<SharedNoteInfo>,
    /** The id of the shared note the tile shows, or null while it shows the tab's own note. */
    val shownHostId: String?,
    val onNewSharedNote: () -> Unit,
    val onShareThisNote: () -> Unit,
    val onShowOwnNote: () -> Unit,
    val onShowSharedNote: (String) -> Unit,
) {
    /** "Share this note…" moves the tab's own note, so it is offered only while the tab shows that note. */
    val canShareThisNote: Boolean get() = shownHostId == null

    /** Open ▾ has note sections only when some shared note exists at all; otherwise it stays the plain recent-notes list. */
    val hasNoteSections: Boolean get() = sharedNotes.isNotEmpty() || otherSharedNotes.isNotEmpty()
}

/** "1 tab" / "N tabs". */
internal fun tabCountLabel(count: Int): String = if (count == 1) "1 tab" else "$count tabs"

/** The shared-note menus' inputs for [tab] (the tab whose Notes tile is shown), wired to [AppState]'s operations. */
internal fun AppState.notesSharedActionsFor(tab: LogTab, onReclaimFocus: () -> Unit): NotesSharedActions =
    NotesSharedActions(
        ownNoteLabel = "${tab.filename} notes",
        sharedNotes = sharedNotesFor(tab.id),
        otherSharedNotes = otherSharedNotes(tab.id),
        shownHostId = notesTargetFor(tab.id).takeIf { it != tab.id },
        onNewSharedNote = { sharedNoteDialog = SharedNoteDialogRequest.Share(tab.id, shareExistingNote = false, reclaimFocus = onReclaimFocus) },
        onShareThisNote = { sharedNoteDialog = SharedNoteDialogRequest.Share(tab.id, shareExistingNote = true, reclaimFocus = onReclaimFocus) },
        onShowOwnNote = { setNotesView(tab.id, null) },
        onShowSharedNote = { hostId -> setNotesView(tab.id, hostId) },
    )

/** The format a tab is read with, for the share dialog's rows: its pinned profile's name (else the automatic one), plus its section count. */
internal fun AppState.tabFormatLabel(tab: LogTab): String {
    val profile = pinnedLogFormatProfile(tab.id)
        ?: logFormatProfile(if (tab.logFormat == LogFormat.DLT) "automotive-dlt" else "android-logcat")
    val name = profile?.name ?: if (tab.logFormat == LogFormat.DLT) "Automotive DLT" else "Android logcat"
    return if (tab.logSections.size > 1) "$name · ${tab.logSections.size} sections" else name
}

// ── Row references ───────────────────────────────────────────────────────────────────────────

/** What a row reference block in the Notes tile can do besides being clicked: name its source and go to its rows (SharedNotes.kt's goToLogRefRows). */
internal class LogRefActions(
    /** Where the rows come from ("file" or "file · section"), or null for rows of the note's own tab. */
    val sourceLabel: (AnnBlock.LogRef) -> String?,
    /** Goes to the block's rows; [onNeedsReopen] hears that the source tab is closed (the block then offers to reopen it). */
    val goToRows: (AnnBlock.LogRef, onNeedsReopen: (GoToRowsResult.NeedsReopen) -> Unit) -> Unit,
    /** Reopens the closed source tab the user agreed to reopen, then goes to the rows. */
    val reopenAndGoToRows: (GoToRowsResult.NeedsReopen, AnnBlock.LogRef) -> Unit,
)

/** The notice a Go to rows that found nothing shows. */
private fun AppState.rowsGoneNotice(block: AnnBlock.LogRef, viewingTabId: String) {
    val file = block.sourceFilename ?: tab(viewingTabId)?.filename ?: "this file"
    pendingDiagramNotice = DiagramNotice("Rows not found", "These rows are no longer in $file.")
}

/** The notice a note's video frame shows when its video is not attached to any open tab (a negative position counts as that too). */
internal fun videoNotOpenNotice() = DiagramNotice(
    "Video not open",
    "The video this frame came from is not attached to an open tab. Attach it to the log to jump to this frame.",
)

/**
 * A click on a note's video frame: jumps to it ([AppState.navigateToVideoFrame]), or tells the user the frame's video is not open.
 * The UI entry point; tool callers use navigateToVideoFrame alone, with no notice.
 */
internal fun AppState.openNoteVideoFrame(tabId: String, frame: VideoFrameReference, fromSharedNote: Boolean): Boolean {
    val opened = navigateToVideoFrame(tabId, frame, fromSharedNote)
    if (!opened) pendingDiagramNotice = videoNotOpenNotice()
    return opened
}

/** [LogRefActions] for the Notes tile shown in [viewingTabId], wired to [AppState]. */
internal fun AppState.logRefActionsFor(viewingTabId: String): LogRefActions = LogRefActions(
    sourceLabel = { block -> logRefSourceLabel(block) },
    goToRows = { block, onNeedsReopen ->
        launchGoToLogRefRows(block, viewingTabId) { result ->
            when (result) {
                is GoToRowsResult.NeedsReopen -> onNeedsReopen(result)
                GoToRowsResult.RowsNotFound -> rowsGoneNotice(block, viewingTabId)
                is GoToRowsResult.Navigated, GoToRowsResult.NoSource -> Unit
            }
        }
    },
    reopenAndGoToRows = { offer, block ->
        launchInBackground {
            if (goToLogRefRowsAfterReopen(offer.ownerId, offer.closedTabId, block, viewingTabId) == GoToRowsResult.RowsNotFound) {
                rowsGoneNotice(block, viewingTabId)
            }
        }
    },
)

/**
 * "New note" in the Notes header: a fresh private note for this tab. A tab that shows a shared note first goes back to its own, so the shared
 * note is never the one cleared.
 */
internal fun AppState.newPrivateNote(tabId: String) {
    if (notesTargetFor(tabId) != tabId) setNotesView(tabId, null)
    newAnalysis(tabId)
}
