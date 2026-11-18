package com.indagium.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.indagium.diagram3.Seq3SourceImportResult
import com.indagium.diagram3.parseSeq3Note
import com.indagium.model.AnnBlock
import com.indagium.model.LogTab

// The bodies of the log view's side tiles, built once so FileView, CompareView and the tiled log view bind the same panels the
// same way.

/**
 * The Notes panel wired to [state] for [tab]. The saved Diagram library is a separate tile; source-linked diagram controls remain in Notes.
 * [onPanelFocusChanged] is the panel's focus-changed callback (the caller records which panel has focus).
 */
@Composable
internal fun BoundAnnotationPanel(
    state: AppState,
    tab: LogTab,
    width: Float,
    focusRequester: FocusRequester?,
    onPanelFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    actionsInHeader: Boolean = false,
    copyFeedbackState: CopyFeedbackState? = null,
) {
    // The note the panel shows and edits: the tab's own, or the shared note it views (every edit below goes to `noteId`). What
    // belongs to the log the user is looking at (the relink notice, diagram lines, video frames) stays on `tab`.
    val note = state.shownNoteOf(tab)
    val noteId = note.id
    val ownNote = noteId == tab.id
    AnnotationPanel(
        tab = note,
        settings = state.settings,
        rewriteState = state,
        recentNotes = state.recentNotesForTab(note),
        recentNotesMenuOpen = state.recentNotesMenuOpen,
        activeNotePath = state.activeNoteFilePath(note),
        onToggleMd = { state.toggleMd(noteId) },
        onCopyFormat = { format, done -> state.copyAnnotationFormatAsync(noteId, format, done) },
        onCopyImage = { block -> state.copyImageToClipboard(block.bytes, block.provenance) },
        onCopyDiagramImage = { png, fallback -> state.copyImageToClipboard(png, fallback) },
        onExportFrames = { state.exportAnnotationFrames(noteId) },
        onSave = { state.saveAnalysis(noteId) },
        onNewAnalysis = { state.newAnalysis(noteId) },
        onToggleRecentNotes = { state.toggleRecentNotesMenu() },
        onOpenNote = { state.openNoteFileAsync(noteId, it) },
        onLocateLog = { state.locateLogForTab(tab.id, it) },
        showUnverifiedRelinkNotice = ownNote && state.logRelinkUnverifiedTabId == tab.id,
        onDismissUnverifiedRelinkNotice = { state.dismissLogRelinkUnverifiedNotice() },
        onUpdatePrefix = { state.setPrefix(noteId, it) },
        onUpdateSuffix = { state.setSuffix(noteId, it) },
        onUpdateIssueDescription = { state.setIssueDescription(noteId, it) },
        onUpdateBlock = { blockId, text -> state.updateBlock(noteId, blockId, text) },
        onRemoveBlock = { state.removeBlock(noteId, it) },
        onMoveBlock = { blockId, d -> state.moveBlock(noteId, blockId, d) },
        onReorderBlock = { blockId, idx -> state.reorderBlock(noteId, blockId, idx) },
        onAddNoteAfter = { state.addNoteBlock(noteId, it) },
        onAddImage = { bytes, provenance, after -> state.addImageBlock(noteId, bytes, provenance, after) },
        onUnhandledFileDrop = { files -> state.openDroppedFiles(files) },
        // A shared note's references name their own source tab and section; a tab's own note keeps its navigation as it was.
        onNavigateLogRef = { if (ownNote) state.requestAnnotationNavigation(tab.id, it) else state.launchGoToLogRefRows(it, tab.id) },
        onNavigateVideoFrame = { state.navigateToVideoFrame(tab.id, it) },
        onUpdateLogRefPresentation = { blockId, caption, presentation ->
            state.updateLogRefPresentation(noteId, blockId, caption, presentation)
        },
        onEditDiagram = { blockId -> state.seq3Sessions.beginEdit(noteId, blockId) },
        onNavigateDiagramLine = { entryId -> state.navigateToLogLine(tab.id, entryId) },
        onImportLinkedDiagram = { blockId, source, dialect, confirm ->
            val diagramId = note.annotations.blocks
                .filterIsInstance<AnnBlock.Note>()
                .firstOrNull { it.id == blockId }
                ?.text
                ?.let { text -> parseSeq3Note(text) }
                ?.attachment
                ?.diagramId
            val session = diagramId?.let { id -> state.seq3Sessions.sessions.singleOrNull { it.libraryItemId == id } }
                ?: state.seq3Sessions.sessions.singleOrNull { it.confirmedBlockId == blockId }
            if (session == null) {
                state.pendingDiagramNotice = DiagramNotice(
                    "Couldn't import diagram edits",
                    "This linked diagram is not open in a workspace.",
                )
                Seq3SourceImportResult.Failure(emptyList())
            } else {
                state.seq3Sessions.importSource(session.id, source, dialect, confirm)
            }
        },
        width = width,
        focusRequester = focusRequester,
        onPanelFocusChanged = onPanelFocusChanged,
        keyboardFocusVisible = state.keyboardFocusVisible,
        scrollStateStore = state.logViewerScrollStateStore,
        highlightedBlockId = state.aiEvidenceNoteTarget?.takeIf { it.tabId == noteId }?.blockId,
        modifier = modifier,
        actionsInHeader = actionsInHeader,
        copyFeedbackState = copyFeedbackState,
    )
}

/**
 * True when [tab] has a capture timeline with unavailable-frame intervals to show but no video attached: the Video panel is then
 * worth showing for the timeline alone.
 */
internal fun hasTimelineOnlyVideoPanel(state: AppState, tab: LogTab): Boolean {
    val retainedCaptureTimeline = state.captureTimelineFor(tab.id) ?: tab.captureTimeline
    return retainedCaptureTimeline != null &&
        retainedCaptureTimeline.availabilityIntervals.isNotEmpty() &&
        retainedCaptureTimeline.availabilityFramesRendered != true &&
        (retainedCaptureTimeline.showUnavailableFrames ?: state.settings.captureSettings.showUnavailableFrames) &&
        state.captureSnapshotSessionFor(tab.id)?.settings?.recordVideo != false
}

/** The Video panel's body: the live capture card for a capture tab, otherwise the attached video (or capture timeline). */
@Composable
internal fun VideoTileBody(state: AppState, tab: LogTab) {
    if (tab.captureSessionId != null) {
        CaptureCard(state = state, tab = tab)
    } else {
        BoundVideoPanel(state = state, tab = tab, modifier = Modifier.fillMaxSize())
    }
}
