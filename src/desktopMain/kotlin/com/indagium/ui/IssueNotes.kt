package com.indagium.ui

import com.indagium.testing.model.IssueAttachmentKind
import com.indagium.testing.model.IssueDestination
import com.indagium.testing.model.IssueRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

// The notes destination of an issue: an issue-style note (the issue as Markdown) is appended to a log tab's notes, followed
// by the step's screenshot as an image block. The tab is the one that shows the lane's own recorded log; when there is none the
// caller may ask for that log (`capture/.../logcat.log` of the lane) to be opened as a tab first. The annotation mutators run
// here, after the issue store released its lock and on IO, never under stateLock held by store code.

private const val LOAD_WAIT_MS = 60_000L
private const val LOAD_POLL_MS = 50L
private const val NO_LANE_LOG_MESSAGE =
    "The lane's log is not available (the run was deleted or the log was not kept). Open a log tab and pass its tabId."
private const val NOTE_NOT_ADDED_MESSAGE = "The note could not be added (the tab's notes are waiting on an overwrite decision)."

private fun sameFile(a: File, b: File): Boolean = runCatching { a.canonicalFile == b.canonicalFile }.getOrDefault(a.absoluteFile == b.absoluteFile)

/** The Markdown written into the note: the issue, then where its screenshot is shown. */
private fun AppState.noteText(record: IssueRecord, hasImage: Boolean): String {
    val markdown = issueMarkdown(record)
    return if (hasImage) "$markdown\n_The screenshot of the step follows as an image._\n" else markdown
}

/** The tab to write into: the explicit [tabId], else the open tab that shows [logFile]. Null when there is none. */
private fun AppState.targetTab(tabId: String?, logFile: File?): String? {
    if (tabId != null) return tab(tabId)?.id
    if (logFile == null) return null
    return tabs.firstOrNull { tab -> tab.sourcePath?.let { sameFile(File(it), logFile) } == true }?.id
}

/** Opens [logFile] as a tab and waits (bounded) until it is loaded. Null when it did not open. */
private suspend fun AppState.openLogTab(logFile: File): String? {
    val id = openFileAsIs(logFile) ?: return null
    var waited = 0L
    while (isLoadInFlight(id) && waited < LOAD_WAIT_MS) {
        delay(LOAD_POLL_MS)
        waited += LOAD_POLL_MS
    }
    return id.takeIf { tab(it) != null }
}

internal suspend fun AppState.deliverToNotes(record: IssueRecord, tabId: String?, openLaneLog: Boolean): IssueActionResult {
    val logFile = laneLogOfIssue(record)
    // Comparing paths and checking the file touch the disk: not on the caller's thread, which may be the UI thread.
    var target = withContext(Dispatchers.IO) { targetTab(tabId, logFile) }
    if (target == null && tabId != null) return IssueActionResult.Failed("No tab with id '$tabId' is open.")
    if (target == null) {
        if (logFile == null || !withContext(Dispatchers.IO) { logFile.isFile }) return IssueActionResult.Failed(NO_LANE_LOG_MESSAGE)
        if (!openLaneLog) return IssueActionResult.NeedsLogTab(record, logFile)
        target = openLogTab(logFile) ?: return IssueActionResult.Failed("The lane's log could not be opened as a tab.")
    }
    val screenshot = withContext(Dispatchers.IO) {
        record.draft.attachments.firstOrNull { it.kind == IssueAttachmentKind.SCREENSHOT }?.let { issueStore.attachmentFile(record, it) }?.readBytes()
    }
    val noteId = addNoteBlock(target, noteText(record, screenshot != null)) ?: return IssueActionResult.Failed(NOTE_NOT_ADDED_MESSAGE)
    if (screenshot != null) {
        addImageBlock(
            target, screenshot, provenance = "from issue ${record.id}", afterId = noteId,
            caption = "Step ${record.source.stepNumber}: ${record.draft.title}".take(MAX_IMAGE_CAPTION_CHARS),
        )
    }
    return recordDelivery(record, IssueDestination.NOTES, "Added to the notes of tab $target.", reference = target)
}

/** The lane's recorded logcat of the run behind [record], or null when the run or its log is gone. */
internal suspend fun AppState.laneLogOfIssue(record: IssueRecord): File? {
    val run = runOfIssue(record) ?: return null
    val relative = run.lane(record.source.laneId)?.logPath ?: return null
    return File(testRunCoordinator.runDir(run.id), relative)
}

private const val MAX_IMAGE_CAPTION_CHARS = 200
