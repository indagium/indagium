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
    AnnotationPanel(
        tab = tab,
        settings = state.settings,
        rewriteState = state,
        recentNotes = state.recentNotesForTab(tab),
        recentNotesMenuOpen = state.recentNotesMenuOpen,
        activeNotePath = state.activeNoteFilePath(tab),
        onToggleMd = { state.toggleMd(tab.id) },
        onCopyFormat = { format, done -> state.copyAnnotationFormatAsync(tab.id, format, done) },
        onCopyImage = { block -> state.copyImageToClipboard(block.bytes, block.provenance) },
        onCopyDiagramImage = { png, fallback -> state.copyImageToClipboard(png, fallback) },
        onExportFrames = { state.exportAnnotationFrames(tab.id) },
        onSave = { state.saveAnalysis(tab.id) },
        onNewAnalysis = { state.newAnalysis(tab.id) },
        onToggleRecentNotes = { state.toggleRecentNotesMenu() },
        onOpenNote = { state.openNoteFileAsync(tab.id, it) },
        onLocateLog = { state.locateLogForTab(tab.id, it) },
        showUnverifiedRelinkNotice = state.logRelinkUnverifiedTabId == tab.id,
        onDismissUnverifiedRelinkNotice = { state.dismissLogRelinkUnverifiedNotice() },
        onUpdatePrefix = { state.setPrefix(tab.id, it) },
        onUpdateSuffix = { state.setSuffix(tab.id, it) },
        onUpdateIssueDescription = { state.setIssueDescription(tab.id, it) },
        onUpdateBlock = { blockId, text -> state.updateBlock(tab.id, blockId, text) },
        onRemoveBlock = { state.removeBlock(tab.id, it) },
        onMoveBlock = { blockId, d -> state.moveBlock(tab.id, blockId, d) },
        onReorderBlock = { blockId, idx -> state.reorderBlock(tab.id, blockId, idx) },
        onAddNoteAfter = { state.addNoteBlock(tab.id, it) },
        onAddImage = { bytes, provenance, after -> state.addImageBlock(tab.id, bytes, provenance, after) },
        onUnhandledFileDrop = { files -> state.openDroppedFiles(files) },
        onNavigateLogRef = { state.requestAnnotationNavigation(tab.id, it) },
        onNavigateVideoFrame = { state.navigateToVideoFrame(tab.id, it) },
        onUpdateLogRefPresentation = { blockId, caption, presentation ->
            state.updateLogRefPresentation(tab.id, blockId, caption, presentation)
        },
        onEditDiagram = { blockId -> state.seq3Sessions.beginEdit(tab.id, blockId) },
        onNavigateDiagramLine = { entryId -> state.navigateToLogLine(tab.id, entryId) },
        onImportLinkedDiagram = { blockId, source, dialect, confirm ->
            val diagramId = tab.annotations.blocks
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
        highlightedBlockId = state.aiEvidenceNoteTarget?.takeIf { it.tabId == tab.id }?.blockId,
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
