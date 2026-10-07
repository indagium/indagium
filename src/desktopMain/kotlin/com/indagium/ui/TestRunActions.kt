package com.indagium.ui

import com.indagium.testing.limits.LimitDecision
import com.indagium.testing.model.StepFix
import com.indagium.testing.run.StartRunResult
import com.indagium.testing.run.TestRunReportExportProgress
import com.indagium.testing.run.TestRunReportExportResult
import com.indagium.testing.run.TestRunReportFormat
import com.indagium.testing.run.exportTestRunReport
import com.indagium.testing.run.findFix
import com.indagium.testing.run.rerunConfig
import com.indagium.testing.run.rerunFailedCasesConfig
import com.indagium.testing.run.withAgentError
import com.indagium.testing.run.withFix
import com.indagium.testing.run.withFixApplied
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The actions of a run report, shared by the report screen and the MCP tools so both do exactly the same thing:
// apply a judge's suggested fix to the library step, mark a step as the agent's mistake, re-run a step. Expected problems
// come back as data ([ReportActionResult.Failed]); nothing here throws for them. All of it runs off the UI thread.

internal sealed interface ReportActionResult {
    data class Done(val message: String) : ReportActionResult

    /** [limit] is set when the edition limits refused (the step is locked). */
    data class Failed(val message: String, val limit: LimitDecision.Refused? = null) : ReportActionResult
}

/**
 * Writes the fix [fixRef] (a judgement's or comparison's id) of run [runId] into library step [stepId]: its action and/or
 * expected text. The library's locks and limits apply, so a locked step is refused. The fix is then marked applied in the run.
 */
internal suspend fun AppState.applyStepFix(runId: String, stepId: String, fixRef: String): ReportActionResult {
    val run = testRunCoordinator.loadRun(runId) ?: return ReportActionResult.Failed("Run '$runId' was not found.")
    val found = findFix(run, fixRef) ?: return ReportActionResult.Failed("Run '$runId' has no suggested fix '$fixRef'.")
    return when {
        found.stepId != stepId -> ReportActionResult.Failed("The fix '$fixRef' is about step '${found.stepId}', not '$stepId'.")
        found.applied -> ReportActionResult.Failed("The fix '$fixRef' was already applied.")
        !found.fix.isApplicable -> {
            val advice = found.fix.note?.let { ": $it" }.orEmpty()
            ReportActionResult.Failed("The fix '$fixRef' is advice only (it has no replacement text)$advice.")
        }
        else -> withContext(Dispatchers.IO) { writeFix(runId, stepId, fixRef, found.fix) }
    }
}

private suspend fun AppState.writeFix(runId: String, stepId: String, fixRef: String, fix: StepFix): ReportActionResult =
    when (val result = updateTestStep(stepId) { it.withFix(fix) }) {
        is StoreResult.Ok -> {
            testRunCoordinator.updateRun(runId) { it.withFixApplied(fixRef) }
            ReportActionResult.Done("Updated step '$stepId' in the library.")
        }
        is StoreResult.LimitReached -> ReportActionResult.Failed(result.decision.message, result.decision)
        is StoreResult.NotFound -> ReportActionResult.Failed("${result.message} The step may have been deleted since the run.")
        is StoreResult.Invalid -> ReportActionResult.Failed(result.reason)
    }

/** Notes on the result of the step that the agent, not the app, got it wrong. [iteration] is 1-based. */
internal suspend fun AppState.markAgentError(runId: String, laneId: String, caseId: String, iteration: Int, stepId: String, note: String): ReportActionResult {
    if (note.isBlank()) return ReportActionResult.Failed("note is required.")
    val run = testRunCoordinator.loadRun(runId) ?: return ReportActionResult.Failed("Run '$runId' was not found.")
    if (run.withAgentError(laneId, caseId, iteration, stepId, note) == null) {
        return ReportActionResult.Failed("Run '$runId' has no result for step '$stepId' of case '$caseId' (run $iteration) on lane '$laneId'.")
    }
    testRunCoordinator.updateRun(runId) { it.withAgentError(laneId, caseId, iteration, stepId, note) ?: it }
    return ReportActionResult.Done("Marked as an agent error.")
}

/** Starts a new run of the case of [stepId] up to and including that step, on the same lane setup as [laneId] of [runId]. */
internal suspend fun AppState.rerunStep(runId: String, laneId: String, caseId: String, stepId: String): StartRunResult {
    val run = testRunCoordinator.loadRun(runId) ?: return StartRunResult.Rejected(listOf("Run '$runId' was not found."))
    val config = rerunConfig(run, laneId, caseId, stepId).getOrElse { return StartRunResult.Rejected(listOf(it.message ?: "The step cannot be re-run.")) }
    return testRunCoordinator.start(config)
}

/** Starts a current-library run of the union of cases that failed, blocked, or errored in [runId]. */
internal suspend fun AppState.rerunFailedCases(runId: String): StartRunResult {
    val run = testRunCoordinator.loadRun(runId) ?: return StartRunResult.Rejected(listOf("Run '$runId' was not found."))
    val config = rerunFailedCasesConfig(run).getOrElse { return StartRunResult.Rejected(listOf(it.message ?: "Failed cases cannot be re-run.")) }
    val selected = config.caseIds.orEmpty()
    val currentSuite = testLibrary.suite(config.suiteId)
        ?: return StartRunResult.Rejected(listOf("The suite from run '$runId' was deleted; failed cases cannot be re-run."))
    val currentIds = currentSuite.cases.map { it.id }.toSet()
    val missing = selected.filterNot { it in currentIds }
    if (selected.isEmpty()) return StartRunResult.Rejected(listOf("No failed case remains selected; no run was started."))
    if (missing.isNotEmpty()) {
        return StartRunResult.Rejected(
            listOf("Some failed cases were deleted from the current suite; refresh the run report before retrying."),
        )
    }
    return testRunCoordinator.start(config)
}

/** Shared local report export used by the Runs UI and MCP catalog. */
internal suspend fun AppState.exportTestRunReport(
    runId: String,
    destination: String,
    format: TestRunReportFormat,
    evidencePaths: List<String> = emptyList(),
    overwrite: Boolean = false,
    progress: (TestRunReportExportProgress) -> Unit = {},
): Result<TestRunReportExportResult> {
    val run = testRunCoordinator.loadRun(runId) ?: return Result.failure(IllegalArgumentException("Run '$runId' was not found."))
    if (run.status == com.indagium.testing.model.RunStatus.RUNNING || run.status == com.indagium.testing.model.RunStatus.QUEUED) {
        return Result.failure(IllegalArgumentException("Wait until run '$runId' finishes before exporting its report."))
    }
    return exportTestRunReport(
        run, testRunCoordinator.runDir(runId), java.io.File(destination), format, evidencePaths,
        overwrite = overwrite, progress = progress,
    )
}
