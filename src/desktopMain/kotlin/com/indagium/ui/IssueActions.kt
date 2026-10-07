package com.indagium.ui

import com.indagium.testing.model.IssueDestination
import com.indagium.testing.model.IssueDestinationResult
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.IssueSeverity
import com.indagium.testing.model.IssueSource
import com.indagium.testing.model.IssueStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.isPendingCaptureArchive
import com.indagium.testing.model.normalizeTags
import com.indagium.testing.run.AndroidBugreportCollector
import com.indagium.testing.run.IssueDraftContext
import com.indagium.testing.run.IssueStepClipExport
import com.indagium.testing.run.IssueStepClipRequest
import com.indagium.testing.run.buildIssueDraft
import com.indagium.testing.run.collectAndroidBugreport
import com.indagium.testing.run.defaultIssueStepClipWindow
import com.indagium.testing.run.exportIssueCaptureArchive
import com.indagium.testing.run.toMarkdown
import com.indagium.testing.run.withIssueId
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import com.indagium.testing.run.exportIssueStepClip as exportIssueStepClipService

// The actions on issues, shared by the issue dialog, the Issues screen and the MCP tools so all of them do exactly the same
// thing: build a draft from a step, store it, send it to a destination (the local store, a note in a log tab, Markdown),
// edit and delete it. Expected problems come back as data ([IssueActionResult.Failed]); nothing here throws for them.
// Everything runs on IO: nothing holds a store lock while it calls into AppState (the notes destination calls the annotation
// mutators only after the issue store has released its lock), and nothing here holds stateLock. The tracker destination
// (IssueTrackerActions.kt) starts an AI agent that files the issue through the tracker's MCP tools.

private const val MAX_DESTINATION_RESULTS = 20
private const val MAX_BUGREPORT_ERROR_DETAIL_CHARS = 500
private const val SAVED_MESSAGE = "Saved locally."
private const val MARKDOWN_COPIED_MESSAGE = "The issue as Markdown was copied to the clipboard."
private const val MARKDOWN_RENDERED_MESSAGE = "The issue was rendered as Markdown."

internal sealed interface IssueActionResult {
    /** [markdown] is set for the Markdown destination; [tabId] for the notes destination; [trackerUrl] and [trackerKey] for the tracker. */
    data class Done(
        val record: IssueRecord,
        val message: String,
        val markdown: String? = null,
        val tabId: String? = null,
        val warnings: List<String> = emptyList(),
        val trackerUrl: String? = null,
        val trackerKey: String? = null,
    ) : IssueActionResult

    /** The notes destination found no tab of the lane's log; [logFile] is the lane's recorded log that could be opened as one. */
    data class NeedsLogTab(val record: IssueRecord, val logFile: File) : IssueActionResult

    data class Failed(val message: String) : IssueActionResult
}

/** The parts of an issue a caller may change; a null part is left alone. */
internal data class IssueOverrides(
    val title: String? = null,
    val severity: IssueSeverity? = null,
    val labels: List<String>? = null,
    val stepsToReproduce: List<String>? = null,
    val expected: String? = null,
    val actual: String? = null,
    val judgeNotes: String? = null,
    val linkToCase: Boolean? = null,
)

internal fun IssueDraft.withOverrides(overrides: IssueOverrides): IssueDraft = copy(
    title = overrides.title?.trim()?.takeIf { it.isNotEmpty() } ?: title,
    severity = overrides.severity ?: severity,
    labels = overrides.labels?.let(::normalizeTags) ?: labels,
    stepsToReproduce = overrides.stepsToReproduce?.map { it.trim() }?.filter { it.isNotEmpty() } ?: stepsToReproduce,
    expected = overrides.expected ?: expected,
    actual = overrides.actual ?: actual,
    judgeNotes = overrides.judgeNotes ?: judgeNotes,
)

private fun <T> StoreResult<T>.failure(): IssueActionResult.Failed = IssueActionResult.Failed(
    if (this is StoreResult.NotFound) "Issue '$id' was not found." else userMessage() ?: "The issue could not be saved.",
)

/** The issue's Markdown with the absolute paths of its stored evidence. */
internal fun AppState.issueMarkdown(record: IssueRecord): String =
    record.draft.toMarkdown(record.source) { attachment -> issueStore.attachmentFile(record, attachment)?.absolutePath }

// ── Building and saving ──────────────────────────────────────────────

/** What a "Create issue…" dialog starts from: a draft built from a step of [runId], and where it came from. */
internal class IssueDraftSeed(val draft: IssueDraft, val source: IssueSource)

/** The draft for a step of a run, built off the UI thread, or why there is none. */
internal suspend fun AppState.buildIssueSeed(runId: String, laneId: String, caseId: String, iteration: Int, stepId: String): Result<IssueDraftSeed> {
    val run = testRunCoordinator.loadRun(runId) ?: return Result.failure(IllegalArgumentException("Run '$runId' was not found."))
    val step = run.stepResult(laneId, caseId, iteration, stepId)
        ?: return Result.failure(
            IllegalArgumentException("Run '$runId' has no result for step '$stepId' of case '$caseId' (run $iteration) on lane '$laneId'."),
        )
    return withContext(Dispatchers.IO) {
        val context = IssueDraftContext(run, testRunCoordinator.runDir(runId)) { suiteId, assetPath -> testGoldenImageFile(suiteId, assetPath) }
        buildIssueDraft(context, laneId, caseId, iteration, step).map { IssueDraftSeed(it.draft, it.source) }
    }
}

/** Explicit, shared UI/MCP bugreport action. The host adb process is interruptible and the service owns staging cleanup. */
internal suspend fun AppState.collectIssueBugreport(issueId: String, progress: (String) -> Unit = {}): Result<IssueRecord> =
    collectAndroidBugreport(issueStore, issueId, AndroidBugreportCollector { serial, destination, report ->
        val tools = withContext(Dispatchers.IO) { captureService.toolsForStart(settings.captureSettings) }
        report("Running adb bugreport on $serial (five-minute limit)…")
        val result = withContext(Dispatchers.IO) {
            runInterruptible {
                tools.runAdb(serial, listOf("bugreport", destination.absolutePath), Duration.ofMinutes(5), outputLimitBytes = 1024 * 1024)
            }
        }
        check(!result.timedOut) { "adb bugreport timed out after five minutes." }
        if (result.exitCode != 0) {
            val detail = (result.stderrText().ifBlank { result.stdoutText() }).trim().take(MAX_BUGREPORT_ERROR_DETAIL_CHARS)
            error("adb bugreport failed with exit code ${result.exitCode}${detail.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}")
        }
    }, progress)

/**
 * Exports the capture archive an issue wants (the pending, ticked one) from the whole recording of its lane and attaches it, with
 * [progress] messages; a no-op that succeeds when the issue wants none or already holds it. Shared by the issue editor and every
 * delivery. A lane that is still recording is exported like a live Save ZIP, a finished one from its run folder.
 */
internal suspend fun AppState.attachIssueCaptureArchive(issueId: String, progress: (String) -> Unit = {}): Result<IssueRecord> {
    val issue = withContext(Dispatchers.IO) { issueStore.load(issueId) }
        ?: return Result.failure(IllegalArgumentException("Issue '$issueId' was not found."))
    val run = testRunCoordinator.loadRun(issue.source.runId)
        ?: return Result.failure(IllegalArgumentException("Run '${issue.source.runId}' was not found."))
    val runDir = testRunCoordinator.runDir(run.id)
    val live = testRunCoordinator.liveLaneArchiveExporter(run.id, issue.source.laneId)
    val laneDir = File(runDir, "lanes/${issue.source.laneId}")
    return exportIssueCaptureArchive(
        store = issueStore, issueId = issueId, run = run, runDir = runDir, liveExport = live,
        notes = { laneNotesOf(run.id, issue.source.laneId, laneDir) }, progress = progress,
    ).map { it.issue }
}

/** Explicit padded video-clip export shared by the issue editor and MCP. Blank bounds use the failed-step default. */
internal suspend fun AppState.exportIssueStepClip(
    issueId: String,
    startMs: Long? = null,
    endMs: Long? = null,
    progress: (String) -> Unit = {},
): Result<IssueStepClipExport> {
    val issue = withContext(Dispatchers.IO) { issueStore.load(issueId) }
        ?: return Result.failure(IllegalArgumentException("Issue '$issueId' was not found."))
    val run = testRunCoordinator.loadRun(issue.source.runId)
        ?: return Result.failure(IllegalArgumentException("Run '${issue.source.runId}' was not found."))
    return withContext(Dispatchers.IO) {
        exportIssueStepClipService(issueStore, issueId, run, testRunCoordinator.runDir(run.id), startMs, endMs, progress = progress)
    }
}

internal suspend fun AppState.defaultIssueStepClipWindow(source: IssueSource): Result<IssueStepClipRequest> {
    val run = testRunCoordinator.loadRun(source.runId)
        ?: return Result.failure(IllegalArgumentException("Run '${source.runId}' was not found."))
    return withContext(Dispatchers.IO) { defaultIssueStepClipWindow(run, testRunCoordinator.runDir(run.id), source) }
}

/**
 * Stores [draft]: it replaces the issue [existingId] (keeping its status), or creates a new DRAFT issue from [source] and notes
 * the issue on the step's result so the report finds it again.
 */
internal suspend fun AppState.saveIssue(existingId: String?, source: IssueSource?, draft: IssueDraft, linkToCase: Boolean): IssueActionResult =
    withContext(Dispatchers.IO) {
        if (existingId != null) {
            return@withContext when (val result = issueStore.update(existingId) { it.copy(draft = draft, linkToCase = linkToCase) }) {
                is StoreResult.Ok -> IssueActionResult.Done(result.value, "Draft saved.", warnings = result.warnings)
                else -> result.failure()
            }
        }
        val where = source ?: return@withContext IssueActionResult.Failed("An issue needs a step to be about.")
        when (val result = issueStore.create(draft, where, IssueStatus.DRAFT, linkToCase)) {
            is StoreResult.Ok -> {
                noteIssueOnStep(where, result.value.id)
                IssueActionResult.Done(result.value, "Draft saved.", warnings = result.warnings)
            }
            else -> result.failure()
        }
    }

private suspend fun AppState.noteIssueOnStep(source: IssueSource, issueId: String) {
    testRunCoordinator.updateRun(source.runId) { it.withIssueId(source.laneId, source.caseId, source.iteration, source.stepId, issueId) }
}

/**
 * The MCP and report entry point: builds the draft of a step (or reuses the draft the engine already made for it), applies
 * [overrides] and sends the issue to [destination].
 */
@Suppress("LongParameterList") // One parameter per thing a create_issue_from_step call names.
internal suspend fun AppState.createIssueFromStep(
    runId: String,
    laneId: String,
    caseId: String,
    iteration: Int,
    stepId: String,
    destination: IssueDestination,
    overrides: IssueOverrides,
    tabId: String? = null,
    openLaneLog: Boolean = false,
    copyMarkdown: Boolean = false,
    resendToTracker: Boolean = false,
): IssueActionResult {
    // Nothing is created for a tracker that is not set up (the token itself is checked when the issue is sent).
    if (destination == IssueDestination.TRACKER) trackerPreflightProblem()?.let { return IssueActionResult.Failed(it) }
    val run = testRunCoordinator.loadRun(runId) ?: return IssueActionResult.Failed("Run '$runId' was not found.")
    val existing = run.stepResult(laneId, caseId, iteration, stepId)?.issueId?.let { withContext(Dispatchers.IO) { issueStore.load(it) } }
    val saved = if (existing != null) {
        saveIssue(existing.id, null, existing.draft.withOverrides(overrides), overrides.linkToCase ?: existing.linkToCase)
    } else {
        val seed = buildIssueSeed(runId, laneId, caseId, iteration, stepId).getOrElse {
            return IssueActionResult.Failed(it.message ?: "No issue can be made from this step.")
        }
        saveIssue(null, seed.source, seed.draft.withOverrides(overrides), overrides.linkToCase ?: false)
    }
    val record = (saved as? IssueActionResult.Done)?.record ?: return saved
    return deliverIssue(record.id, destination, copyMarkdown, tabId, openLaneLog, resendToTracker)
}

// ── Delivering ───────────────────────────────────────────────────────

/**
 * Sends the stored issue [issueId] to [destination]; [copyMarkdown] puts the Markdown on the clipboard (the UI's choice, never an
 * MCP call's). [resendToTracker]: create another tracker issue although this one was already sent (ui/IssueTrackerActions.kt).
 */
internal suspend fun AppState.deliverIssue(
    issueId: String,
    destination: IssueDestination,
    copyMarkdown: Boolean,
    tabId: String? = null,
    openLaneLog: Boolean = false,
    resendToTracker: Boolean = false,
    progress: (String) -> Unit = {},
): IssueActionResult {
    var record = withContext(Dispatchers.IO) { issueStore.load(issueId) } ?: return IssueActionResult.Failed("Issue '$issueId' was not found.")
    // The ticked capture archive is part of the evidence every destination hands on, so it is exported before anything is delivered.
    if (record.draft.attachments.any { it.isPendingCaptureArchive && it.include }) {
        record = attachIssueCaptureArchive(issueId, progress).getOrElse { failure ->
            return IssueActionResult.Failed(
                "The capture archive could not be created: ${failure.message ?: failure::class.simpleName}. " +
                    "Untick it in the evidence list to go on without it.",
            )
        }
    }
    return when (destination) {
        IssueDestination.LOCAL -> recordDelivery(record, destination, SAVED_MESSAGE)
        IssueDestination.MARKDOWN -> {
            val markdown = withContext(Dispatchers.IO) { issueMarkdown(record) }
            if (copyMarkdown) copyToClipboard(markdown)
            recordDelivery(record, destination, if (copyMarkdown) MARKDOWN_COPIED_MESSAGE else MARKDOWN_RENDERED_MESSAGE, markdown = markdown)
        }
        IssueDestination.NOTES -> deliverToNotes(record, tabId, openLaneLog)
        IssueDestination.TRACKER -> deliverToTracker(record, resendToTracker)
    }
}

/** Notes the delivery on the issue: LOCAL makes a draft SAVED, anything else SENT. The status never goes back. */
internal suspend fun AppState.recordDelivery(
    record: IssueRecord,
    destination: IssueDestination,
    message: String,
    reference: String? = null,
    markdown: String? = null,
    trackerKey: String? = null,
): IssueActionResult = withContext(Dispatchers.IO) {
    val entry = IssueDestinationResult(destination, ok = true, message = message, at = System.currentTimeMillis(), reference = reference)
    val reached = if (destination == IssueDestination.LOCAL) IssueStatus.SAVED else IssueStatus.SENT
    val result = issueStore.update(record.id) {
        it.copy(status = maxOf(it.status, reached), destinationResults = (it.destinationResults + entry).takeLast(MAX_DESTINATION_RESULTS))
    }
    when (result) {
        is StoreResult.Ok -> {
            val tabId = reference.takeIf { destination == IssueDestination.NOTES }
            val trackerUrl = reference.takeIf { destination == IssueDestination.TRACKER }
            IssueActionResult.Done(result.value, message, markdown = markdown, tabId = tabId, trackerUrl = trackerUrl, trackerKey = trackerKey)
        }
        else -> result.failure()
    }
}

// ── Editing and deleting ─────────────────────────────────────────────

internal suspend fun AppState.updateIssueFields(issueId: String, overrides: IssueOverrides): IssueActionResult = withContext(Dispatchers.IO) {
    when (val result = issueStore.update(issueId) { it.copy(draft = it.draft.withOverrides(overrides), linkToCase = overrides.linkToCase ?: it.linkToCase) }) {
        is StoreResult.Ok -> IssueActionResult.Done(result.value, "Issue updated.")
        else -> result.failure()
    }
}

internal suspend fun AppState.deleteIssue(issueId: String): IssueActionResult = withContext(Dispatchers.IO) {
    val record = issueStore.load(issueId) ?: return@withContext IssueActionResult.Failed("Issue '$issueId' was not found.")
    when (val result = issueStore.delete(issueId)) {
        is StoreResult.Ok -> IssueActionResult.Done(record, "Issue deleted.")
        else -> result.failure()
    }
}

/** The run behind an issue, or null when it is gone. */
internal suspend fun AppState.runOfIssue(record: IssueRecord): TestRun? = testRunCoordinator.loadRun(record.source.runId)
