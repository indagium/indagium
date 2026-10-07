package com.indagium.testing.run

import com.indagium.ai.AiRunEvent
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.StepFix
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.newLaneId

// What a person (or an MCP client) can do with a finished step in a run report, as pure functions over the run:
//   apply_step_fix   - take the fix a judge or comparison suggested and write it into the step in the LIBRARY (the run's own
//                      frozen suite is never changed); the fix is then marked applied so it cannot be applied twice;
//   mark_agent_error - note on the result that the agent, not the app, got the step wrong;
//   rerun_test_step  - a new, small run: the same case, up to and including that step, on the same lane setup. The case is
//                      rerun from its first step because a step only makes sense in the state the steps before it left.

private const val TOOL_FEED_ARGUMENT_CHARS = 80
private const val MAX_AGENT_ERROR_NOTE_CHARS = 1_000

/** A suggested fix found in a run: [ref] is the judgement's or comparison's id, [stepId] the library step it is about. */
internal class FoundFix(val ref: String, val stepId: String, val caseId: String, val fix: StepFix, val applied: Boolean)

/** The suggested fix with id [fixRef] (a [com.indagium.testing.model.StepJudgement.id] or [com.indagium.testing.model.JudgeComparison.id]), or null. */
internal fun findFix(run: TestRun, fixRef: String): FoundFix? {
    run.comparisons.firstOrNull { it.id == fixRef }?.let { comparison ->
        return comparison.suggestedFix?.let { FoundFix(fixRef, comparison.stepId, comparison.caseId, it, comparison.fixApplied) }
    }
    for (lane in run.lanes) {
        for (case in lane.cases) {
            for (step in case.steps) {
                val judgement = step.judge?.takeIf { it.id == fixRef } ?: continue
                return judgement.suggestedFix?.let { FoundFix(fixRef, step.stepId, case.caseId, it, judgement.fixApplied) }
            }
        }
    }
    return null
}

/** [run] with the fix [fixRef] marked as applied. */
internal fun TestRun.withFixApplied(fixRef: String): TestRun = copy(
    comparisons = comparisons.map { if (it.id == fixRef) it.copy(fixApplied = true) else it },
    lanes = lanes.map { lane ->
        lane.copy(
            cases = lane.cases.map { case ->
                case.copy(
                    steps = case.steps.map { step ->
                        step.judge?.takeIf { it.id == fixRef }?.let { step.copy(judge = it.copy(fixApplied = true)) } ?: step
                    },
                )
            },
        )
    },
)

/** [step] with the fix's action and expected text in place of its own; a null part of the fix leaves the step's text alone. */
internal fun TestStep.withFix(fix: StepFix): TestStep = copy(
    action = fix.action?.takeIf { it.isNotBlank() } ?: action,
    expected = fix.expected?.takeIf { it.isNotBlank() } ?: expected,
)

/** [run] with [note] on the result of the step, or null when that step has no result in the run. The note is trimmed and bounded. */
internal fun TestRun.withAgentError(laneId: String, caseId: String, iteration: Int, stepId: String, note: String): TestRun? {
    if (stepResult(laneId, caseId, iteration, stepId) == null) return null
    val text = note.trim().take(MAX_AGENT_ERROR_NOTE_CHARS)

    fun CaseResult.mark(): CaseResult = copy(steps = steps.map { if (it.stepId == stepId && !it.setup) it.copy(agentError = text) else it })

    fun LaneResult.mark(): LaneResult = copy(cases = cases.map { if (it.caseId == caseId && it.iteration == iteration) it.mark() else it })
    return withLane(laneId) { it.mark() }
}

/** The config of a re-run of [stepId] of [caseId] from [run] on lane [laneId], or why that is not possible. */
internal fun rerunConfig(run: TestRun, laneId: String, caseId: String, stepId: String): Result<RunConfig> {
    val lane = run.lane(laneId) ?: return Result.failure(IllegalArgumentException("Run '${run.id}' has no lane '$laneId'."))
    val case = run.suite.cases.firstOrNull { it.id == caseId }
        ?: return Result.failure(IllegalArgumentException("Run '${run.id}' has no case '$caseId'."))
    if (case.steps.none { it.id == stepId }) return Result.failure(IllegalArgumentException("Case '${case.name}' has no step '$stepId'."))
    return Result.success(
        run.config.copy(
            suiteId = run.suite.id,
            caseIds = listOf(caseId),
            lanes = listOf(lane.config.copy(id = newLaneId())),
            repeat = 1,
            stopAfterStepId = stepId,
            rerunOf = run.id,
        ),
    )
}

/** The original run configuration narrowed to the union of cases that failed, blocked, or errored on any lane/repeat. */
internal fun rerunFailedCasesConfig(run: TestRun): Result<RunConfig> {
    val failed = failedCaseIds(run).toList()
    if (failed.isEmpty()) return Result.failure(IllegalArgumentException("This run has no failed, blocked, or errored cases to re-run."))
    val currentCaseIds = run.suite.cases.map { it.id }.toSet()
    val selected = failed.filter { it in currentCaseIds }
    if (selected.isEmpty()) return Result.failure(IllegalArgumentException("None of the failed cases still exist in the run's suite."))
    return Result.success(
        run.config.copy(
            suiteId = run.suite.id,
            caseIds = selected,
            lanes = run.config.lanes.map { it.copy(id = newLaneId()) },
            stopAfterStepId = null,
            rerunOf = run.id,
        ),
    )
}

/** Case outcomes after their final step attempt, unioned across lanes and repeats. */
internal fun failedCaseIds(run: TestRun): Set<String> = run.lanes.flatMap { it.cases }
    .filter { it.caseId !in setOf("suite-setup", "suite-teardown") }
    .filter { result ->
        result.status in setOf(CaseStatus.FAIL, CaseStatus.BLOCKED, CaseStatus.ERROR) ||
            result.steps.any { it.status in setOf(StepStatus.FAIL, StepStatus.BLOCKED, StepStatus.ERROR, StepStatus.TIMEOUT) }
    }.mapTo(LinkedHashSet()) { it.caseId }

/** [this] suite without the steps after [stepId] in the case that has it; unchanged for a null id. */
internal fun TestSuite.truncatedAfter(stepId: String?): TestSuite {
    if (stepId == null) return this
    return copy(cases = cases.map { case -> case.truncatedAfter(stepId) })
}

private fun TestCase.truncatedAfter(stepId: String): TestCase {
    val index = steps.indexOfFirst { it.id == stepId }
    return if (index < 0) this else copy(steps = steps.take(index + 1))
}

internal fun CasePlan.truncatedAfter(stepId: String?): CasePlan {
    if (stepId == null) return this
    return CasePlan(cases.map { it.truncatedAfter(stepId) }, locked)
}

/** The last [max] tool calls an agent made, oldest first, as `name {arguments}` lines. */
internal fun toolCallLines(events: List<AiRunEvent>, max: Int): List<String> =
    events.filterIsInstance<AiRunEvent.ToolRequested>().takeLast(max).map { event ->
        val arguments = event.call.argumentsJson.trim().replace(Regex("\\s+"), " ")
        if (arguments.isEmpty() || arguments == "{}") event.call.name else "${event.call.name} ${arguments.take(TOOL_FEED_ARGUMENT_CHARS)}"
    }
