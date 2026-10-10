package com.indagium.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.indagium.testing.authoring.ImproveEvidenceSummary
import com.indagium.testing.authoring.ImproveStepEdit
import com.indagium.testing.authoring.ImproveStepProposal
import com.indagium.testing.authoring.TestCaseImproveProposal
import com.indagium.testing.authoring.improvedStepOf
import com.indagium.testing.authoring.incidentalWarningOf
import com.indagium.testing.authoring.isProblemStep
import com.indagium.testing.model.SUITE_SETUP_CASE_ID
import com.indagium.testing.model.SUITE_TEARDOWN_CASE_ID
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestStep

// The state of the "Improve steps with AI" dialog, kept free of composables so it is testable: which case and run it is opened for, the
// line that says what will be sent, why generating is blocked, and the review of a proposal (which steps are accepted, the edits).

/** The case the improve dialog is for; [runId] is a run that is the evidence (from the run report), else the case's latest run. */
internal data class ImproveStepsTarget(val suiteId: String, val caseId: String, val runId: String? = null)

/**
 * What will be sent, in one line: "Latest run 2026-10-10 18:20 · 4 screenshots · 2 judge suggestions". Screenshots are the run's and
 * the golden ones that fit the limits. Without a run the line says so and counts the golden screenshots. [explicitRun] is a run the
 * person (or the run report) chose.
 */
internal fun improveEvidenceLabel(summary: ImproveEvidenceSummary, explicitRun: Boolean = false): String {
    val parts = ArrayList<String>()
    if (summary.runId == null) {
        parts += "No run of this case yet"
        parts += plural(summary.goldenScreenshots, "golden screenshot").ifEmpty { "steps only" }
    } else {
        parts += (if (explicitRun) "Run " else "Latest run ") + formatRunTime(summary.runCreatedAt)
        parts += plural(summary.screenshots, "screenshot")
        parts += plural(summary.judgeSuggestions, "judge suggestion")
    }
    if (summary.leftOut > 0) parts += "${summary.leftOut} left out by the image limit"
    return parts.filter(String::isNotEmpty).joinToString(" · ")
}

private fun plural(count: Int, noun: String): String = if (count <= 0) "" else if (count == 1) "1 $noun" else "$count ${noun}s"

/** Why generating is not possible for [case], or null: the case is gone, has no steps, or cannot be edited. */
internal fun improveBlockedReason(case: TestCase?, editable: Boolean): String? = when {
    case == null -> "This case no longer exists."
    !editable -> "This case is read-only."
    case.steps.isEmpty() -> "This case has no steps to improve yet."
    else -> null
}

/** The cases of [run] that have a step that failed, was blocked, timed out, errored or could not be judged: where the report offers the dialog. */
internal fun improvableCaseIds(run: TestRun): Set<String> = run.lanes.flatMap { lane -> lane.cases }
    .filter { it.caseId != SUITE_SETUP_CASE_ID && it.caseId != SUITE_TEARDOWN_CASE_ID && it.steps.any { step -> step.isProblemStep() } }
    .mapTo(LinkedHashSet()) { it.caseId }

/** One step of the review: the model's proposal and what the person did with it. A changed step starts accepted. */
internal class ImproveReviewRow(val step: ImproveStepProposal, private val scripts: List<TestScript>) {
    var action by mutableStateOf(step.action)
    var expected by mutableStateOf(step.expected)
    var optional by mutableStateOf(step.optional)
    var condition by mutableStateOf(step.condition.orEmpty())
    var accepted by mutableStateOf(step.changed)

    /** Only an AI step can be optional or conditional. */
    val canBeOptional: Boolean get() = step.previous.kind == StepKind.AGENT

    private val result: Result<TestStep>
        get() = runCatching { improvedStepOf(step.previous, action, expected, optional, condition.trim().ifEmpty { null }, step.checks, scripts) }

    /** What is wrong with the fields as they stand, or null. */
    val problem: String? get() = result.exceptionOrNull()?.message

    /** The fields (and the proposed checks) differ from the saved step. */
    val changed: Boolean get() = result.getOrNull()?.let { it != step.previous } ?: true

    /** The review warning when the expected result looks like it depends on content that changes between runs. */
    val warning: String? get() = incidentalWarningOf(step.previous.kind, expected, optional)

    fun edit() = ImproveStepEdit(step.stepId, action, expected, optional, condition.trim().ifEmpty { null })
}

/** The review of a proposal: the rows, which are selected, and what Apply would do. */
internal class ImproveReview(val proposal: TestCaseImproveProposal, scripts: List<TestScript> = emptyList()) {
    val rows: List<ImproveReviewRow> = proposal.steps.map { ImproveReviewRow(it, scripts) }

    /** The steps the proposal changes (before any edit). */
    val proposedChanges: Int get() = proposal.steps.count { it.changed }

    /** The rows Apply would write: accepted, and still different from the saved step. */
    val selected: List<ImproveReviewRow> get() = rows.filter { it.accepted && it.problem == null && it.changed }

    /** Why Apply is not possible yet, or null. */
    val blockedReason: String?
        get() = rows.firstOrNull { it.accepted && it.problem != null }?.let { "Step ${it.step.number}: ${it.problem}" }
            ?: "Select at least one changed step to apply.".takeIf { selected.isEmpty() }

    val applyLabel: String get() = "Apply ${selected.size} selected step${if (selected.size == 1) "" else "s"}"

    fun edits(): List<ImproveStepEdit> = rows.map(ImproveReviewRow::edit)

    fun acceptedIds(): Set<String> = selected.mapTo(LinkedHashSet()) { it.step.stepId }
}
