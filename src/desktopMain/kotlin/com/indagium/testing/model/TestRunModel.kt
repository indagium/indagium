package com.indagium.testing.model

// Domain types of an AI test RUN: what was asked for (RunConfig), the frozen copy of what was run (TestRun.suite and
// friends) and what happened (lane, case and step results). Pure data and immutable: the engine replaces a result by
// copying it, so a snapshot handed to the UI or written to run.json is never mutated underneath its reader.
// A run has N lanes: lanes on different devices run in parallel, lanes that share a device one after another.

const val RUN_ID_PREFIX = "run-"
const val LANE_ID_PREFIX = "lane-"

fun newRunId(): String = newPrefixedId(RUN_ID_PREFIX)

fun newLaneId(): String = newPrefixedId(LANE_ID_PREFIX)

const val DEFAULT_CASE_TOOL_CALL_LIMIT = 60
const val MIN_CASE_TOOL_CALL_LIMIT = 1
const val MAX_CASE_TOOL_CALL_LIMIT = 500
const val DEFAULT_CONFIRMATION_TIMEOUT_MS = 5 * 60 * 1000L
val ALLOWED_RUN_REPEATS: List<Int> = listOf(1, 3, 5)

/** The pseudo cases that hold the results of the suite's own setup and teardown hooks. */
const val SUITE_SETUP_CASE_ID = "suite-setup"
const val SUITE_TEARDOWN_CASE_ID = "suite-teardown"

/** The external-lane marker the MCP `lanes[].profileId` uses. */
const val EXTERNAL_LANE_PROFILE_ID = "external"

enum class RunStatus { QUEUED, RUNNING, PASSED, FAILED, CANCELLED, ERROR }

/** AGENT_PROFILE: an AI profile drives the lane. EXTERNAL: nobody does; a client drives it through `test_lane_tool_call`. */
enum class LaneKind { AGENT_PROFILE, EXTERNAL }

/** [profileId] is the AI profile of an AGENT_PROFILE lane and null for an EXTERNAL one. */
data class LaneConfig(
    val id: String = newLaneId(),
    val kind: LaneKind,
    val profileId: String? = null,
    val deviceSerial: String,
)

/** What a run keeps as evidence. Logcat is always recorded while the run is live; [logcat] says whether it is kept afterwards. */
data class EvidenceFlags(
    val video: Boolean = false,
    val screenshots: Boolean = true,
    val logcat: Boolean = true,
    val transcript: Boolean = true,
)

/**
 * [judgeProfileId] is the AI profile that judges steps (any profile kind) and [judgeMode] when it does (a [JudgeMode] wire
 * name; kept as text so a run file written by a newer build with another mode still loads, and read as OFF here).
 * [stopAfterStepId] ends the (single) chosen case after that step: a re-run of "this step and everything before it".
 * [rerunOf] names the run such a re-run came from.
 */
data class RunConfig(
    val suiteId: String,
    /** null runs every (unlocked) case of the suite. */
    val caseIds: List<String>? = null,
    val lanes: List<LaneConfig>,
    val repeat: Int = 1,
    val caseToolCallLimit: Int = DEFAULT_CASE_TOOL_CALL_LIMIT,
    val evidence: EvidenceFlags = EvidenceFlags(),
    val confirmationTimeoutMs: Long = DEFAULT_CONFIRMATION_TIMEOUT_MS,
    val judgeProfileId: String? = null,
    val judgeMode: String = JudgeMode.OFF.wire,
    val stopAfterStepId: String? = null,
    val rerunOf: String? = null,
)

/** The judge mode of this config; an unknown text counts as OFF. */
val RunConfig.judge: JudgeMode get() = JudgeMode.parse(judgeMode) ?: JudgeMode.OFF

/** A judge is configured: a profile and a mode other than OFF. */
val RunConfig.judgeActive: Boolean get() = judge != JudgeMode.OFF && !judgeProfileId.isNullOrBlank()

enum class StepStatus { PASS, FAIL, BLOCKED, TIMEOUT, SKIPPED, ERROR }

enum class CaseStatus { PASS, FAIL, BLOCKED, SKIPPED, CANCELLED, ERROR }

/** NOT_EVALUATED: the check needs the judge and none judged this step (no judge configured, or it was inconclusive). */
enum class CheckStatus { PASS, FAIL, NOT_EVALUATED, ERROR }

data class CheckResult(
    val checkId: String,
    /** The check's kind name, e.g. `logAppears`. */
    val kind: String,
    val status: CheckStatus,
    val detail: String = "",
    val durationMs: Long = 0L,
)

/**
 * One step as it ended. [attempts] counts how often the step was tried. [agentClaim] is what the agent said (pass, fail,
 * blocked) and [observation] what it reported seeing. Evidence paths are relative to the run folder; the log and
 * transcript ranges are byte offsets into the lane's `logcat.log` and `transcript.jsonl`.
 */
data class StepResult(
    val stepId: String,
    val stepNumber: Int,
    val action: String,
    val expected: String = "",
    /** A step of a setup hook (a shared step run before the suite or a case), not of the case itself. */
    val setup: Boolean = false,
    val status: StepStatus,
    val attempts: Int = 1,
    val agentClaim: String? = null,
    val observation: String = "",
    val checks: List<CheckResult> = emptyList(),
    val screenshotPath: String? = null,
    val logStartOffset: Long? = null,
    val logEndOffset: Long? = null,
    val transcriptStartOffset: Long? = null,
    val transcriptEndOffset: Long? = null,
    val startedAt: Long = 0L,
    val durationMs: Long = 0L,
    /** The step asked for an issue to be created (CREATE_ISSUE_AND_CONTINUE); [issueId] is the draft the engine made for it. */
    val issueRequested: Boolean = false,
    val note: String? = null,
    /** The blind judge's verdict on this step, when one judged it. */
    val judge: StepJudgement? = null,
    /** The judge could not decide, so [status] is the agent's claim and the deterministic checks only. */
    val judgeInconclusive: Boolean = false,
    /** A person's note that the agent (not the app) got this step wrong; set by mark_agent_error. */
    val agentError: String? = null,
    /** The draft issue the engine created for this step (CREATE_ISSUE_AND_CONTINUE), or null. Appended last: older run files decode as null. */
    val issueId: String? = null,
)

/** [iteration] is 1-based (a run can repeat every case). [caseId] is [SUITE_SETUP_CASE_ID] or [SUITE_TEARDOWN_CASE_ID] for the suite-level hooks. */
data class CaseResult(
    val caseId: String,
    val caseName: String,
    val iteration: Int = 1,
    val status: CaseStatus? = null,
    val steps: List<StepResult> = emptyList(),
    val startedAt: Long = 0L,
    val finishedAt: Long? = null,
    val note: String? = null,
)

data class LaneResult(
    val laneId: String,
    val config: LaneConfig,
    val status: RunStatus = RunStatus.QUEUED,
    val cases: List<CaseResult> = emptyList(),
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val error: String? = null,
    /** Where the lane is right now, for the status views. */
    val currentCase: String? = null,
    val currentStepNumber: Int? = null,
    val currentStepAction: String? = null,
    /** The lane's recorded logcat, relative to the run folder. */
    val logPath: String? = null,
    val transcriptPath: String? = null,
)

/** The frozen inputs: the suite, every library script and the shared steps the suite refers to, as they were at start. */
data class TestRun(
    val id: String,
    val suite: TestSuite,
    val scripts: List<TestScript>,
    val sharedSteps: List<SharedStep>,
    val config: RunConfig,
    val lanes: List<LaneResult>,
    val status: RunStatus = RunStatus.QUEUED,
    val createdAt: Long = 0L,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
    /** Where lanes disagreed on a step, what the comparison judge made of it. */
    val comparisons: List<JudgeComparison> = emptyList(),
) {
    val isFinished: Boolean get() = status == RunStatus.PASSED || status == RunStatus.FAILED ||
        status == RunStatus.CANCELLED || status == RunStatus.ERROR

    fun lane(laneId: String): LaneResult? = lanes.firstOrNull { it.laneId == laneId }

    fun withLane(laneId: String, transform: (LaneResult) -> LaneResult): TestRun =
        copy(lanes = lanes.map { if (it.laneId == laneId) transform(it) else it })

    /** The result of step [stepId] of [caseId] (iteration [iteration]) on [laneId], or null. Hook steps are not matched. */
    fun stepResult(laneId: String, caseId: String, iteration: Int, stepId: String): StepResult? =
        lane(laneId)?.cases?.firstOrNull { it.caseId == caseId && it.iteration == iteration }?.steps
            ?.firstOrNull { it.stepId == stepId && !it.setup }
}

/** A one-line view of a run for lists. */
data class RunSummary(
    val id: String,
    val suiteName: String,
    val status: RunStatus,
    val createdAt: Long,
    val finishedAt: Long?,
    val laneCount: Int,
    val caseCount: Int,
    val passedSteps: Int,
    val totalSteps: Int,
)

fun TestRun.summary(): RunSummary {
    val steps = lanes.flatMap { lane -> lane.cases.flatMap { it.steps } }.filterNot { it.setup }
    return RunSummary(
        id = id,
        suiteName = suite.name,
        status = status,
        createdAt = createdAt,
        finishedAt = finishedAt,
        laneCount = lanes.size,
        caseCount = lanes.firstOrNull()?.cases?.count { it.caseId != SUITE_SETUP_CASE_ID && it.caseId != SUITE_TEARDOWN_CASE_ID } ?: 0,
        passedSteps = steps.count { it.status == StepStatus.PASS },
        totalSteps = steps.size,
    )
}
