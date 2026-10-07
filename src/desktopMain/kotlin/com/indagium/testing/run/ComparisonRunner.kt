package com.indagium.testing.run

import com.indagium.testing.model.JudgeComparison
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Where several lanes ran the same step and ended up differently, one comparison judge looks at every lane's evidence
// and says why. "Differently" is a different step status or a different single-step judge verdict. Lanes whose step was
// skipped or ended in an infrastructure error have no evidence and do not take part. The evidence is the same blind
// kind a single judge gets (check results, screenshot, log range); no lane's claim, observation, status or earlier
// verdict is passed on. Comparisons run when every lane of the run is done, so each lane's results are final.

/** One lane's result of the step. [laneNumber] is the 1-based position of the lane in the run. */
internal class LaneStepResult(val lane: LaneResult, val laneNumber: Int, val result: StepResult)

internal class ComparisonTarget(val case: TestCase, val iteration: Int, val step: TestStep, val stepNumber: Int, val results: List<LaneStepResult>)

private fun disagree(results: List<LaneStepResult>): Boolean =
    results.map { it.result.status }.distinct().size > 1 || results.mapNotNull { it.result.judge?.verdict }.distinct().size > 1

/** The steps of [run] at which at least two lanes disagree, in suite order. Empty for a run with fewer than two lanes. */
internal fun comparisonTargets(run: TestRun): List<ComparisonTarget> {
    if (run.lanes.size < 2) return emptyList()
    val chosen = run.config.caseIds
    val targets = ArrayList<ComparisonTarget>()
    for (iteration in 1..run.config.repeat) {
        for (case in run.suite.cases.filter { chosen == null || it.id in chosen }) {
            case.steps.forEachIndexed { index, step ->
                val results = run.lanes.mapIndexedNotNull { laneIndex, lane ->
                    run.stepResult(lane.laneId, case.id, iteration, step.id)
                        ?.takeIf { it.status != StepStatus.SKIPPED && it.status != StepStatus.ERROR }
                        ?.let { LaneStepResult(lane, laneIndex + 1, it) }
                }
                if (results.size >= 2 && disagree(results)) targets += ComparisonTarget(case, iteration, step, index + 1, results)
            }
        }
    }
    return targets
}

/** The comparison judge's evidence for [target]: per lane its automatic check results and the files kept in [runDir]. */
internal fun comparisonEvidence(runDir: File, target: ComparisonTarget): JudgeEvidence {
    val lanes = target.results.map { laneStep ->
        val result = laneStep.result
        val logFile = laneStep.lane.logPath?.let { File(runDir, it) }
        val start = result.logStartOffset
        val end = result.logEndOffset
        JudgeLaneEvidence(
            label = "Lane ${laneStep.laneNumber}",
            laneId = laneStep.lane.laneId,
            deterministic = deterministicOnly(result.checks),
            screenshot = { loadScreenshot(runDir, result.screenshotPath) },
            log = { offset, limit -> logFile?.let { withContext(Dispatchers.IO) { readStepLogSlice(it, start, end, offset, limit) } } },
            logBytes = if (start != null && end != null) end - start else null,
        )
    }
    return JudgeEvidence(
        caseName = target.case.name,
        stepNumber = target.stepNumber,
        stepCount = target.case.steps.size,
        action = target.step.action,
        expected = target.step.expected,
        judgeChecks = judgeChecksOf(target.step),
        examples = target.step.examples,
        lanes = lanes,
    )
}

private suspend fun loadScreenshot(runDir: File, relativePath: String?): JudgeImage? {
    val file = relativePath?.takeIf { it.isNotBlank() }?.let { File(runDir, it) }?.takeIf { it.isFile } ?: return null
    return withContext(Dispatchers.IO) {
        val bytes = com.indagium.testing.store.readBoundedTestAsset(file) ?: return@withContext null
        boundedJudgeImage(bytes)
    }
}

/** Judges every disagreement of [run]; [record] gets each comparison as it is made. A cancelled run stops here. */
internal suspend fun compareDisagreements(run: TestRun, runDir: File, judge: JudgeService, record: (JudgeComparison) -> Unit) {
    for (target in comparisonTargets(run)) {
        record(judge.compare(target.case.id, target.iteration, target.step.id, comparisonEvidence(runDir, target)))
    }
}
