package com.indagium.testing.run

import com.indagium.testing.model.IssueRecheck
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.IssueStatus
import com.indagium.testing.model.RecheckOutcome
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.store.IssueStore
import com.indagium.testing.store.StoreResult
import java.io.File

// What the run engine does with issues, kept apart from the lane code:
//   - [IssueAutoDrafter]: a step that ended with onFailure CREATE_ISSUE_AND_CONTINUE gets a LOCAL draft issue (status DRAFT)
//     the moment its result is recorded (its evidence is already on disk), so a person only has to review it;
//   - [markLinkedIssues]: when a run ends, every issue linked to a case of that run is marked "still failing" or
//     "passing now" according to what the run did with the issue's step.
// Both only touch the IssueStore (a leaf lock) and never AppState.

/** Creates the draft issue of a recorded step. Reads and copies evidence, so it runs on an IO thread (the lane's). */
internal class IssueAutoDrafter(
    private val store: IssueStore,
    private val runDir: File,
    private val goldenFile: (suiteId: String, assetPath: String) -> File?,
) {
    /** The new issue's id, or why there is none (the step is not a case step, the disk refused). */
    fun draft(run: TestRun, laneId: String, caseId: String, iteration: Int, step: StepResult): Result<String> {
        val built = buildIssueDraft(IssueDraftContext(run, runDir, goldenFile), laneId, caseId, iteration, step).getOrElse { return Result.failure(it) }
        return when (val stored = store.create(built.draft, built.source, IssueStatus.DRAFT)) {
            is StoreResult.Ok -> Result.success(stored.value.id)
            is StoreResult.Invalid -> Result.failure(IllegalStateException(stored.reason))
            is StoreResult.NotFound -> Result.failure(IllegalStateException(stored.message))
            is StoreResult.LimitReached -> Result.failure(IllegalStateException(stored.decision.message))
        }
    }
}

/**
 * What [run] says about the step of [issue]: STILL_FAILING when any lane's result for it is not a pass, PASSING_NOW when
 * every result is a pass, null when the run never ran that step (skipped, not reached, other suite, or the run that
 * created the issue).
 */
internal fun recheckOutcomeFor(run: TestRun, issue: IssueRecord): RecheckOutcome? {
    val source = issue.source
    if (!issue.linkToCase || run.id == source.runId || run.suite.id != source.suiteId) return null
    val results = run.lanes.flatMap { lane ->
        lane.cases.filter { it.caseId == source.caseId }.flatMap { case -> case.steps.filter { it.stepId == source.stepId && !it.setup } }
    }.filter { it.status != StepStatus.SKIPPED }
    return when {
        results.isEmpty() -> null
        results.all { it.status == StepStatus.PASS } -> RecheckOutcome.PASSING_NOW
        else -> RecheckOutcome.STILL_FAILING
    }
}

/** Records the re-check of every linked issue of [run]. Returns how many issues were marked. Reads and writes the disk (IO). */
internal fun markLinkedIssues(store: IssueStore, run: TestRun, now: Long): Int {
    var marked = 0
    for (issue in store.list()) {
        val outcome = recheckOutcomeFor(run, issue) ?: continue
        val updated = store.update(issue.id) { it.copy(recheck = IssueRecheck(run.id, outcome, now)) }
        if (updated is StoreResult.Ok) marked++
    }
    return marked
}

/** [this] run with [issueId] on the result of the step, so the report finds the issue again. Unchanged when the step has no result. */
internal fun TestRun.withIssueId(laneId: String, caseId: String, iteration: Int, stepId: String, issueId: String): TestRun = withLane(laneId) { lane ->
    lane.copy(
        cases = lane.cases.map { case ->
            if (case.caseId != caseId || case.iteration != iteration) {
                case
            } else {
                case.copy(steps = case.steps.map { if (it.stepId == stepId && !it.setup) it.copy(issueId = issueId) else it })
            }
        },
    )
}
