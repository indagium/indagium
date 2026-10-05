package com.indagium.ui

import com.indagium.testing.model.IssueDestination
import com.indagium.testing.model.IssueDestinationResult
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.IssueSeverity
import com.indagium.testing.model.IssueSource
import com.indagium.testing.model.IssueStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.normalizeTags
import com.indagium.testing.run.IssueDraftContext
import com.indagium.testing.run.buildIssueDraft
import com.indagium.testing.run.toMarkdown
import com.indagium.testing.run.withIssueId
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// The actions on issues, shared by the issue dialog, the Issues screen and the MCP tools so all of them do exactly the same
// thing: build a draft from a step, store it, send it to a destination (the local store, a note in a log tab, Markdown),
// edit and delete it. Expected problems come back as data ([IssueActionResult.Failed]); nothing here throws for them.
// Everything runs on IO: nothing holds a store lock while it calls into AppState (the notes destination calls the annotation
// mutators only after the issue store has released its lock), and nothing here holds stateLock.

internal const val TRACKER_UNAVAILABLE_MESSAGE = "Configure an issue tracker in Settings to send issues to it. That arrives in a later version."
private const val MAX_DESTINATION_RESULTS = 20
private const val SAVED_MESSAGE = "Saved locally."
private const val MARKDOWN_COPIED_MESSAGE = "The issue as Markdown was copied to the clipboard."
private const val MARKDOWN_RENDERED_MESSAGE = "The issue was rendered as Markdown."

internal sealed interface IssueActionResult {
    /** [markdown] is set for the Markdown destination; [tabId] for the notes destination. */
    data class Done(
        val record: IssueRecord,
        val message: String,
        val markdown: String? = null,
        val tabId: String? = null,
        val warnings: List<String> = emptyList(),
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
): IssueActionResult {
    if (destination == IssueDestination.TRACKER) return IssueActionResult.Failed(TRACKER_UNAVAILABLE_MESSAGE)
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
    return deliverIssue(record.id, destination, copyMarkdown, tabId, openLaneLog)
}

// ── Delivering ───────────────────────────────────────────────────────

/** Sends the stored issue [issueId] to [destination]; [copyMarkdown] puts the Markdown on the clipboard (the UI's choice, never an MCP call's). */
internal suspend fun AppState.deliverIssue(
    issueId: String,
    destination: IssueDestination,
    copyMarkdown: Boolean,
    tabId: String? = null,
    openLaneLog: Boolean = false,
): IssueActionResult {
    val record = withContext(Dispatchers.IO) { issueStore.load(issueId) } ?: return IssueActionResult.Failed("Issue '$issueId' was not found.")
    return when (destination) {
        IssueDestination.LOCAL -> recordDelivery(record, destination, SAVED_MESSAGE)
        IssueDestination.MARKDOWN -> {
            val markdown = withContext(Dispatchers.IO) { issueMarkdown(record) }
            if (copyMarkdown) copyToClipboard(markdown)
            recordDelivery(record, destination, if (copyMarkdown) MARKDOWN_COPIED_MESSAGE else MARKDOWN_RENDERED_MESSAGE, markdown = markdown)
        }
        IssueDestination.NOTES -> deliverToNotes(record, tabId, openLaneLog)
        IssueDestination.TRACKER -> IssueActionResult.Failed(TRACKER_UNAVAILABLE_MESSAGE)
    }
}

/** Notes the delivery on the issue: LOCAL makes a draft SAVED, anything else SENT. The status never goes back. */
internal suspend fun AppState.recordDelivery(
    record: IssueRecord,
    destination: IssueDestination,
    message: String,
    reference: String? = null,
    markdown: String? = null,
): IssueActionResult = withContext(Dispatchers.IO) {
    val entry = IssueDestinationResult(destination, ok = true, message = message, at = System.currentTimeMillis(), reference = reference)
    val reached = if (destination == IssueDestination.LOCAL) IssueStatus.SAVED else IssueStatus.SENT
    val result = issueStore.update(record.id) {
        it.copy(status = maxOf(it.status, reached), destinationResults = (it.destinationResults + entry).takeLast(MAX_DESTINATION_RESULTS))
    }
    when (result) {
        is StoreResult.Ok -> {
            val tabId = reference.takeIf { destination == IssueDestination.NOTES }
            IssueActionResult.Done(result.value, message, markdown = markdown, tabId = tabId)
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
