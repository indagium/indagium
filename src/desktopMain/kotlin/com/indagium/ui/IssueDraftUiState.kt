package com.indagium.ui

import com.indagium.testing.model.IssueAttachment
import com.indagium.testing.model.IssueDestination
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.IssueSeverity
import com.indagium.testing.model.IssueStatus
import com.indagium.testing.model.RecheckOutcome
import com.indagium.testing.model.normalizeTags
import com.indagium.testing.run.formatByteSize

// UI-free state of the issue dialog and the Issues screen: what the form holds while the user edits it, how it becomes a
// draft again, which destinations are on offer and how an issue reads in a list. Pure functions over plain data, unit-tested
// without Compose (IssueDraftUiStateTest).

internal const val TRACKER_DISABLED_HINT = "Configure an issue tracker in Settings"

/** What the issue dialog was opened for: an issue that is stored, or a step of a run (its draft is built when the dialog opens). */
internal sealed interface IssueDialogTarget {
    data class Existing(val issueId: String) : IssueDialogTarget

    data class FromStep(val runId: String, val laneId: String, val caseId: String, val iteration: Int, val stepId: String) : IssueDialogTarget
}

/**
 * The dialog's inputs as the user left them. Labels and steps are kept as the text typed (comma separated, one step per
 * line) so typing is never rewritten under the cursor; [toDraft] turns them back into lists.
 */
internal data class IssueFormModel(
    val title: String,
    val severity: IssueSeverity,
    val labelsText: String,
    val stepsText: String,
    val expected: String,
    val actual: String,
    val judgeNotes: String,
    val attachments: List<IssueAttachment>,
    val destination: IssueDestination = IssueDestination.LOCAL,
    val linkToCase: Boolean = false,
) {
    /** [base] with the form's fields (its environment is not editable here and is kept). */
    fun toDraft(base: IssueDraft): IssueDraft = base.copy(
        title = title.trim(),
        severity = severity,
        labels = normalizeTags(labelsText.split(',')),
        stepsToReproduce = stepsText.lines().map { it.trim() }.filter { it.isNotEmpty() },
        expected = expected,
        actual = actual,
        judgeNotes = judgeNotes,
        attachments = attachments,
    )

    fun toggleAttachment(index: Int): IssueFormModel =
        copy(attachments = attachments.mapIndexed { i, attachment -> if (i == index) attachment.copy(include = !attachment.include) else attachment })

    /** Why the issue cannot be created yet, or null. */
    val problem: String? get() = if (title.isBlank()) "Give the issue a title." else null

    companion object {
        fun from(draft: IssueDraft, linkToCase: Boolean): IssueFormModel = IssueFormModel(
            title = draft.title,
            severity = draft.severity,
            labelsText = draft.labels.joinToString(", "),
            stepsText = draft.stepsToReproduce.joinToString("\n"),
            expected = draft.expected,
            actual = draft.actual,
            judgeNotes = draft.judgeNotes,
            attachments = draft.attachments,
            linkToCase = linkToCase,
        )
    }
}

/** Whether [destination] can be chosen now, and the hint to show when it cannot. */
internal data class DestinationChoice(val destination: IssueDestination, val label: String, val enabled: Boolean, val hint: String? = null)

/**
 * The destinations on offer. [trackerProblem] is what still has to be set up for the tracker (AppState.trackerSendProblem), null
 * when it can be chosen; the default is "not configured".
 */
internal fun destinationChoices(trackerProblem: String? = TRACKER_DISABLED_HINT): List<DestinationChoice> = listOf(
    DestinationChoice(IssueDestination.LOCAL, "Local", enabled = true),
    DestinationChoice(IssueDestination.NOTES, "Notes", enabled = true),
    DestinationChoice(IssueDestination.MARKDOWN, "Markdown", enabled = true),
    DestinationChoice(IssueDestination.TRACKER, "Tracker", enabled = trackerProblem == null, hint = trackerProblem),
)

/** The label of the dialog's create button for [destination]; [trackerName] names the configured tracker. */
internal fun createLabel(destination: IssueDestination, trackerName: String = "tracker"): String = when (destination) {
    IssueDestination.LOCAL -> "Create"
    IssueDestination.NOTES -> "Create and add to notes"
    IssueDestination.MARKDOWN -> "Create and copy Markdown"
    IssueDestination.TRACKER -> "Create in $trackerName"
}

/** One line per evidence attachment for the checklist: label and size. */
internal fun IssueAttachment.checklistLabel(): String = "$label · ${formatByteSize(sizeBytes)}"

internal fun IssueStatus.label(): String = when (this) {
    IssueStatus.DRAFT -> "Draft"
    IssueStatus.SAVED -> "Saved"
    IssueStatus.SENT -> "Sent"
}

internal fun IssueSeverity.chipLabel(): String = name.lowercase().replaceFirstChar { it.titlecase() }

internal fun RecheckOutcome.label(): String = when (this) {
    RecheckOutcome.STILL_FAILING -> "still failing"
    RecheckOutcome.PASSING_NOW -> "passing now"
}

/** "Case · step 3 · run-…", the second line of an issue in the list. */
internal fun IssueRecord.originLine(): String =
    listOf(source.caseName.ifBlank { "case" }, "step ${source.stepNumber}", source.runId).joinToString(" · ")

/** Issues as the list shows them: newest first (the store already sorts them), filtered by a case-insensitive search of title, case and labels. */
internal fun filterIssues(issues: List<IssueRecord>, query: String): List<IssueRecord> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return issues
    return issues.filter { issue ->
        issue.draft.title.lowercase().contains(q) || issue.source.caseName.lowercase().contains(q) ||
            issue.draft.labels.any { it.lowercase().contains(q) }
    }
}
