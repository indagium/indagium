package com.indagium.ui

import com.indagium.model.AiProviderProfile
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.JudgeClassification
import com.indagium.testing.model.JudgeComparison
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.SUITE_SETUP_CASE_ID
import com.indagium.testing.model.SUITE_TEARDOWN_CASE_ID
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.StepFix
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestStep
import com.indagium.testing.run.PausedStepInfo
import com.indagium.testing.run.PendingTestConfirmation

// UI-free state of the live view and of the report's judge columns: how a running run is laid out as one column per
// lane, the judge feed, the consensus of a step across lanes, and what applying a suggested fix would change. Pure
// functions over plain data, unit-tested without Compose (TestRunLiveStateTest).

// ── Live view ────────────────────────────────────────────────────────

internal const val LIVE_MIN_LANE_WIDTH_DP = 280f
internal const val LIVE_TOOL_FEED_LINES = 6
internal const val JUDGE_FEED_LIMIT = 30
private const val REASONING_EXCERPT_CHARS = 160

/** One line of a lane's step checklist: [status] is null while the step has no result yet; [current] marks the step in progress. */
internal data class LiveStepLine(val number: Int, val action: String, val status: StepStatus?, val current: Boolean)

/** A draft issue the engine made for a step of this lane: the card in the live view opens it. */
internal data class LiveIssueLine(val issueId: String, val caseName: String, val stepNumber: Int, val action: String)

/** Everything one lane column shows. */
internal data class LiveLaneColumn(
    val laneId: String,
    /** The agent's name (its profile), or "External (MCP)". */
    val title: String,
    val deviceSerial: String,
    val status: RunStatus,
    val currentCase: String?,
    val currentStep: String?,
    val steps: List<LiveStepLine>,
    val toolCalls: List<String>,
    /** The newest screenshot of the lane, relative to the run folder. */
    val screenshotPath: String?,
    val confirmations: List<PendingTestConfirmation>,
    val pause: PausedStepInfo?,
    val error: String?,
    /** The draft issues the engine created on this lane so far (steps with onFailure CREATE_ISSUE_AND_CONTINUE), oldest first. */
    val issues: List<LiveIssueLine> = emptyList(),
)

private fun laneTitle(lane: LaneResult, profiles: List<AiProviderProfile>): String {
    if (lane.config.kind == LaneKind.EXTERNAL) return EXTERNAL_LANE_LABEL
    val profile = profiles.firstOrNull { it.id == lane.config.profileId } ?: return lane.config.profileId ?: "Agent"
    return profile.displayName.ifBlank { profile.kind.label }
}

/** The case a lane is working on right now: the last case result that has no final status yet. */
private fun runningCase(lane: LaneResult): CaseResult? = lane.cases.lastOrNull { it.status == null }

private fun stepChecklist(run: TestRun, lane: LaneResult): List<LiveStepLine> {
    val case = runningCase(lane) ?: return emptyList()
    val currentNumber = lane.currentStepNumber
    if (case.caseId == SUITE_SETUP_CASE_ID || case.caseId == SUITE_TEARDOWN_CASE_ID) {
        return case.steps.mapIndexed { i, step -> LiveStepLine(i + 1, step.action, step.status, current = false) }
    }
    val steps = run.suite.cases.firstOrNull { it.id == case.caseId }?.steps ?: return emptyList()
    return steps.mapIndexed { index, step ->
        val result = case.steps.firstOrNull { it.stepId == step.id && !it.setup }
        LiveStepLine(index + 1, step.action, result?.status, current = result == null && currentNumber == index + 1)
    }
}

private fun latestScreenshot(lane: LaneResult): String? =
    lane.cases.asReversed().firstNotNullOfOrNull { case -> case.steps.asReversed().firstNotNullOfOrNull { it.screenshotPath } }

private fun issueLines(lane: LaneResult): List<LiveIssueLine> = lane.cases.flatMap { case ->
    case.steps.filter { !it.setup }.mapNotNull { step -> step.issueId?.let { LiveIssueLine(it, case.caseName, step.stepNumber, step.action) } }
}

/**
 * One column per lane, in lane order. [toolCalls] gives a lane's recent tool calls (newest last); [confirmations] and
 * [pauses] are the live cards of the whole coordinator and are filtered to this run and lane here.
 */
internal fun liveColumns(
    run: TestRun,
    profiles: List<AiProviderProfile>,
    toolCalls: (laneId: String) -> List<String>,
    confirmations: List<PendingTestConfirmation>,
    pauses: List<PausedStepInfo>,
): List<LiveLaneColumn> = run.lanes.map { lane ->
    LiveLaneColumn(
        laneId = lane.laneId,
        title = laneTitle(lane, profiles),
        deviceSerial = lane.config.deviceSerial,
        status = lane.status,
        currentCase = lane.currentCase,
        currentStep = lane.currentStepNumber?.let { number -> "Step $number" + (lane.currentStepAction?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") },
        steps = stepChecklist(run, lane),
        toolCalls = toolCalls(lane.laneId).takeLast(LIVE_TOOL_FEED_LINES),
        screenshotPath = latestScreenshot(lane),
        confirmations = confirmations.filter { it.runId == run.id && it.laneId == lane.laneId },
        pause = pauses.firstOrNull { it.runId == run.id && it.laneId == lane.laneId },
        error = lane.error,
        issues = issueLines(lane),
    )
}

/** How many lane columns fit in [widthDp]: at least one, at most [laneCount], each at least [minLaneDp] wide. Lanes wrap below that. */
internal fun liveGridColumns(widthDp: Float, laneCount: Int, minLaneDp: Float = LIVE_MIN_LANE_WIDTH_DP): Int =
    (widthDp / minLaneDp).toInt().coerceIn(1, laneCount.coerceAtLeast(1))

// ── Judge feed ───────────────────────────────────────────────────────

/** One line of the judge feed. [lane] is "Lane 2" style, or "Comparison" for a comparison of lanes. */
internal data class JudgeFeedItem(
    val at: Long,
    val verdict: JudgeVerdict,
    val lane: String,
    val caseName: String,
    val stepNumber: Int,
    val reasoning: String,
    val classification: JudgeClassification,
    val comparison: Boolean,
)

private fun excerpt(text: String): String {
    val oneLine = text.trim().replace(Regex("\\s+"), " ")
    return if (oneLine.length <= REASONING_EXCERPT_CHARS) oneLine else oneLine.take(REASONING_EXCERPT_CHARS) + "…"
}

/** What a comparison amounts to as one verdict: FAIL when any lane failed, else INCONCLUSIVE when any was, else PASS. */
internal fun JudgeComparison.overallVerdict(): JudgeVerdict = when {
    verdicts.values.any { it == JudgeVerdict.FAIL } -> JudgeVerdict.FAIL
    verdicts.values.any { it == JudgeVerdict.INCONCLUSIVE } || verdicts.isEmpty() -> JudgeVerdict.INCONCLUSIVE
    else -> JudgeVerdict.PASS
}

/** The judge's verdicts of [run] and its comparisons, newest first (ties keep the lane, then run order), at most [limit]. */
internal fun judgeFeed(run: TestRun, limit: Int = JUDGE_FEED_LIMIT): List<JudgeFeedItem> {
    val items = ArrayList<JudgeFeedItem>()
    run.lanes.forEachIndexed { laneIndex, lane ->
        lane.cases.forEach { case ->
            case.steps.forEach { step ->
                val judge = step.judge ?: return@forEach
                items += JudgeFeedItem(
                    judge.judgedAt, judge.verdict, "Lane ${laneIndex + 1}", case.caseName, step.stepNumber,
                    excerpt(judge.reasoning.ifBlank { judge.error.orEmpty() }), judge.classification, comparison = false,
                )
            }
        }
    }
    run.comparisons.forEach { comparison ->
        val caseName = run.suite.cases.firstOrNull { it.id == comparison.caseId }?.name.orEmpty()
        items += JudgeFeedItem(
            comparison.judgedAt, comparison.overallVerdict(), "Comparison", caseName, comparison.stepNumber,
            excerpt(comparison.explanation.ifBlank { comparison.error.orEmpty() }), comparison.classification, comparison = true,
        )
    }
    val newestFirst = compareByDescending<IndexedValue<JudgeFeedItem>> { it.value.at }.thenByDescending { it.index }
    return items.withIndex().sortedWith(newestFirst).map { it.value }.take(limit)
}

// ── Consensus ────────────────────────────────────────────────────────

internal enum class ConsensusTone { NONE, PASS, FAIL, DISAGREE, UNSURE }

/** The Judge / Consensus cell of a step row: [label] and a colour [tone]. */
internal data class ConsensusCell(val label: String, val tone: ConsensusTone)

internal fun JudgeClassification.label(): String = when (this) {
    JudgeClassification.APP_DEFECT -> "app defect"
    JudgeClassification.AGENT_OR_STEP_PROBLEM -> "agent or step problem"
    JudgeClassification.UNKNOWN -> "cause unknown"
}

internal fun JudgeVerdict.label(): String = when (this) {
    JudgeVerdict.PASS -> "Pass"
    JudgeVerdict.FAIL -> "Fail"
    JudgeVerdict.INCONCLUSIVE -> "Inconclusive"
}

private fun toneOf(verdict: JudgeVerdict): ConsensusTone = when (verdict) {
    JudgeVerdict.PASS -> ConsensusTone.PASS
    JudgeVerdict.FAIL -> ConsensusTone.FAIL
    JudgeVerdict.INCONCLUSIVE -> ConsensusTone.UNSURE
}

/**
 * What the lanes of a step add up to. A comparison (lanes disagreed) wins and names its classification. Otherwise the judge's
 * verdicts decide when there are any (all alike: that verdict; different: "Judges differ"); with no judge the step statuses do
 * ("Lanes agree: Pass" / "Lanes differ"). A step no lane has reached has no consensus yet.
 */
internal fun consensusFor(run: TestRun, row: MatrixRow.Step): ConsensusCell {
    val results = row.cells.mapNotNull { it.result }
    if (results.isEmpty()) return ConsensusCell("—", ConsensusTone.NONE)
    run.comparisons.firstOrNull { it.stepId == row.stepId && it.iteration == row.iteration && it.caseId == row.caseId }?.let { comparison ->
        return ConsensusCell("Lanes differ: ${comparison.classification.label()}", ConsensusTone.DISAGREE)
    }
    val verdicts = results.mapNotNull { it.judge?.verdict }
    if (verdicts.isNotEmpty()) {
        val distinct = verdicts.distinct()
        if (distinct.size > 1) return ConsensusCell("Judges differ", ConsensusTone.DISAGREE)
        return ConsensusCell("Judge: ${distinct.single().label().lowercase()}", toneOf(distinct.single()))
    }
    val statuses = results.map { it.status }.distinct()
    return when {
        results.size < 2 -> ConsensusCell("—", ConsensusTone.NONE)
        statuses.size > 1 -> ConsensusCell("Lanes differ", ConsensusTone.DISAGREE)
        statuses.single() == StepStatus.PASS -> ConsensusCell("Lanes agree: ${statuses.single().label()}", ConsensusTone.PASS)
        else -> ConsensusCell("Lanes agree: ${statuses.single().label()}", ConsensusTone.FAIL)
    }
}

// ── Suggested fix ────────────────────────────────────────────────────

/** One field a fix would change: what it says now and what it would say. */
internal data class FixChange(val field: String, val before: String, val after: String)

/** What applying [fix] to the library [step] would change; an unchanged or blank part is left out. Empty when nothing would change. */
internal fun fixChanges(step: TestStep, fix: StepFix): List<FixChange> = buildList {
    fix.action?.takeIf { it.isNotBlank() && it != step.action }?.let { add(FixChange("Action", step.action, it)) }
    fix.expected?.takeIf { it.isNotBlank() && it != step.expected }?.let { add(FixChange("Expected result", step.expected, it)) }
}

/** The golden screenshot a step's judge compared with: the one a ScreenJudge check names, else the first one of the step. */
internal fun goldenExampleFor(step: TestStep?): StepExample.GoldenScreenshot? {
    if (step == null) return null
    val goldens = step.examples.filterIsInstance<StepExample.GoldenScreenshot>()
    val named = step.checks.filterIsInstance<StepCheck.ScreenJudge>().firstNotNullOfOrNull { check ->
        goldens.firstOrNull { it.id == check.exampleRef }
    }
    return named ?: goldens.firstOrNull()
}
