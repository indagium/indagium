package com.indagium.testing.model

import com.indagium.capture.CaptureSettings
import com.indagium.model.AiUsageStats
import com.indagium.model.sumAiUsage

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

/**
 * [profileId] is the AI profile of an AGENT_PROFILE lane and null for an EXTERNAL one. [model] and [reasoningEffort] override the
 * profile's own model and reasoning effort for this lane only; null means "the profile's value" and an empty [reasoningEffort]
 * means "the model's default effort" (it overrides a profile that sets one). Both are appended last: older run files have none.
 */
data class LaneConfig(
    val id: String = newLaneId(),
    val kind: LaneKind,
    val profileId: String? = null,
    val deviceSerial: String,
    val model: String? = null,
    val reasoningEffort: String? = null,
)

/**
 * "profile · model · effort" of a lane, for the live view, the report and Markdown. [profileName] is how the profile is called
 * (null: the lane's profile id); a model or effort the lane does not set is left out, an empty effort reads "default effort".
 */
fun LaneConfig.driverLabel(profileName: String? = null): String =
    listOfNotNull(profileName?.takeIf { it.isNotBlank() } ?: profileId, modelAndEffortLabel().ifEmpty { null }).joinToString(" · ")

/** "model · effort" of a lane, empty when it sets neither. */
fun LaneConfig.modelAndEffortLabel(): String =
    listOfNotNull(model?.takeIf { it.isNotBlank() }, reasoningEffort?.takeIf { it.isNotBlank() }?.let { "$it effort" }).joinToString(" · ")

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
    /** Overrides of the judge profile's model and reasoning effort (same rules as [LaneConfig.model]). Appended last. */
    val judgeModel: String? = null,
    val judgeReasoningEffort: String? = null,
    /**
     * What every lane records while it works: the capture settings the run was started with (the user's saved ones, edited for this
     * run only). Null (an old run file, or a caller that did not choose) means the lane records logcat and, with [EvidenceFlags.video],
     * the screen with the defaults.
     */
    val capture: CaptureSettings? = null,
    /** True: each lane opens a real live capture tab for the run. False: it records the same way without a tab. */
    val openLaneTabs: Boolean = true,
    /** What the run does with MANUAL steps. Appended last: an older run file read it as the default. */
    val manualSteps: ManualStepsOption = ManualStepsOption.USE_SCRIPT_THEN_WAIT,
    /** How a new run treats a missing judge verdict when judging was requested. */
    val judgeInconclusivePolicy: JudgeInconclusivePolicy = JudgeInconclusivePolicy.BLOCK,
    /** When ordinary step judgments run. Explicit judge checks and user pauses remain inline. */
    val judgeTiming: JudgeTiming = JudgeTiming.INLINE,
    /** Additional whole-case attempts after an eligible failure. */
    val caseRetries: Int = 0,
    /** null inherits each step's configured retry count; zero disables retries; positive values override it. */
    val stepRetriesOverride: Int? = null,
)

enum class JudgeTiming(val wire: String, val label: String) {
    INLINE("inline", "Inline"),
    AFTER_CASE("after_case", "After case"),
    BACKGROUND("background", "Background"),
    ;

    companion object {
        fun parse(text: String?): JudgeTiming? = entries.firstOrNull { it.wire.equals(text?.trim(), ignoreCase = true) }
    }
}

enum class JudgeInconclusivePolicy(val wire: String) {
    BLOCK("block"),
    KEEP_AGENT_PASS("keep_agent_pass");

    companion object {
        fun parse(text: String?): JudgeInconclusivePolicy? = entries.firstOrNull { it.wire.equals(text?.trim(), ignoreCase = true) }
    }
}

/**
 * How a run treats MANUAL steps. USE_SCRIPT_THEN_WAIT (the default): a step with an automation script runs it, and every other manual
 * step waits for a tester. WAIT_FOR_TESTER: every manual step waits for a tester, scripts are not used. SKIP: manual steps are not
 * done at all; they are reported as skipped and the case goes on.
 */
enum class ManualStepsOption(val wire: String, val label: String) {
    USE_SCRIPT_THEN_WAIT("use_script_then_wait", "Use the script when there is one, otherwise wait for the tester"),
    WAIT_FOR_TESTER("wait_for_tester", "Always wait for the tester"),
    SKIP("skip", "Skip manual steps"),
    ;

    companion object {
        /** The option named by [text] (its wire name or its enum name, any case), or null. */
        fun parse(text: String?): ManualStepsOption? {
            val wanted = text?.trim().orEmpty()
            return entries.firstOrNull { it.wire.equals(wanted, ignoreCase = true) || it.name.equals(wanted, ignoreCase = true) }
        }
    }
}

/** The judge mode of this config; an unknown text counts as OFF. */
val RunConfig.judge: JudgeMode get() = JudgeMode.parse(judgeMode) ?: JudgeMode.OFF

/** A judge is configured: a profile and a mode other than OFF. */
val RunConfig.judgeActive: Boolean get() = judge != JudgeMode.OFF && !judgeProfileId.isNullOrBlank()

enum class StepStatus { PASS, FAIL, BLOCKED, TIMEOUT, SKIPPED, ERROR }

/** Who or what finished a step, for the report: the AI agent, the tester, the step's checks turning true, a script, or nobody (skipped). */
enum class StepCompletion { AGENT, TESTER, CHECKS, SCRIPT, SKIPPED }

enum class CaseStatus { PASS, FAIL, BLOCKED, SKIPPED, CANCELLED, ERROR }

/** Independent verification state, kept separate from what the agent or deterministic checks established. */
enum class VerificationState { NOT_REQUESTED, PENDING, VERIFIED, FAILED, INCONCLUSIVE }

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
    /** Who performed the step. Appended last: an older run file decodes as an AGENT step. */
    val kind: StepKind = StepKind.AGENT,
    /** How the step was finished; null on a result written before step kinds existed (an agent step) or that never finished. */
    val completedBy: StepCompletion? = null,
    /** For a MANUAL step, the completion mode the step asked for. */
    val manualCompletion: ManualCompletion? = null,
    /**
     * Whom a failure is blamed on when no judge decided it: a manual step nobody confirmed is a problem of the step or the run
     * ([JudgeClassification.AGENT_OR_STEP_PROBLEM]), never an app defect.
     */
    val classification: JudgeClassification? = null,
    /** Actual screen captured before this attempt's first device mutation; relative to the run folder. */
    val startScreenshotPath: String? = null,
    /** Timestamped playback-related evidence frames in capture order; relative to the run folder. */
    val sampleScreenshotPaths: List<String> = emptyList(),
    /** Every concrete attempt, including the final one. Kept even when transcript retention is disabled. */
    val attemptHistory: List<StepAttemptRecord> = emptyList(),
    /** The final attempt passed after an earlier attempt failed. */
    val flaky: Boolean = false,
    /** A user explicitly rejected a paused step; automatic case retries must not overrule that decision. */
    val userRejected: Boolean = false,
    /** Outcome before any blind judge verdict is applied. */
    val executionStatus: StepStatus? = null,
    /** Whether the requested independent judgment has finished. Older run files decode as NOT_REQUESTED. */
    val verificationState: VerificationState = VerificationState.NOT_REQUESTED,
    /** Complete attempt identity; blank/one defaults decode legacy result rows. */
    val caseId: String = "",
    val iteration: Int = 1,
    val caseAttempt: Int = 1,
    /** A driver step failed before an action could run for a permanent setup/permission/configuration reason. */
    val automaticRetryExcluded: Boolean = false,
    /** Time spent queued for the blind judge; execution duration stays in [durationMs]. */
    val judgeQueueWaitMs: Long? = null,
    /** The judge's independent allowance, excluding queue wait. */
    val judgmentDurationMs: Long? = null,
    /**
     * The run was cancelled while this step was being worked on. [status] is still ERROR (an older app reads it so), but the step is
     * neither a failure of the app nor of the test: the report shows it as cancelled and a re-run treats it as interrupted.
     * Appended last and written only when true: older run files decode as false and older apps ignore the key.
     */
    val interrupted: Boolean = false,
)

data class StepAttemptRecord(
    val caseId: String,
    val iteration: Int,
    val caseAttempt: Int,
    val stepId: String,
    val attempt: Int,
    val retryReason: String = "final",
    val status: StepStatus,
    val agentClaim: String? = null,
    val observation: String = "",
    val checks: List<CheckResult> = emptyList(),
    val judge: StepJudgement? = null,
    val startedAt: Long = 0L,
    val durationMs: Long = 0L,
    val screenshotPath: String? = null,
    val startScreenshotPath: String? = null,
    val sampleScreenshotPaths: List<String> = emptyList(),
    val logStartOffset: Long? = null,
    val logEndOffset: Long? = null,
    val transcriptStartOffset: Long? = null,
    val transcriptEndOffset: Long? = null,
    val note: String? = null,
    val agentUsage: AiUsageStats? = null,
    val judgeUsage: AiUsageStats? = null,
    val userRejected: Boolean = false,
    val executionStatus: StepStatus? = null,
    val verificationState: VerificationState = VerificationState.NOT_REQUESTED,
    /** Preserves a driver's permanent setup/permission/configuration exclusion in attempt history. */
    val automaticRetryExcluded: Boolean = false,
    /** Time spent queued for the blind judge; execution duration stays in [durationMs]. */
    val judgeQueueWaitMs: Long? = null,
    /** The judge's independent allowance, excluding queue wait. */
    val judgmentDurationMs: Long? = null,
)

data class CaseAttemptRecord(
    val caseId: String,
    val iteration: Int,
    val attempt: Int,
    val status: CaseStatus,
    val steps: List<StepResult> = emptyList(),
    val startedAt: Long = 0L,
    val finishedAt: Long? = null,
    val note: String? = null,
    val agentUsage: AiUsageStats? = null,
    val judgeUsage: AiUsageStats? = null,
    val externalToolCalls: Long? = null,
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
    /** Agent tool/model usage for this case occurrence, including its setup and teardown hooks. */
    val agentUsage: AiUsageStats? = null,
    /** Sum of each inline blind-judge attempt for this case occurrence. */
    val judgeUsage: AiUsageStats? = null,
    /** Uncapped external-lane tool starts. The caller's model tokens are not visible to Indagium. */
    val externalToolCalls: Long? = null,
    /** 1-based attempt number of the visible/final result. */
    val caseAttempt: Int = 1,
    /** Completed attempts before [caseAttempt], retained when the case was automatically retried. */
    val priorAttempts: List<CaseAttemptRecord> = emptyList(),
    /** The final attempt passed after an earlier attempt failed. */
    val flaky: Boolean = false,
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
    /** Paid tool calls persisted for the live view and reports, including calls that finish after a step timeout. */
    val toolCalls: List<LaneToolCall> = emptyList(),
    /** Relative path to the complete bounded JSONL activity stream; the in-memory cache may hold only its newest calls. */
    val toolActivityPath: String? = null,
)

enum class LaneToolCallStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED }

/** A bounded, redacted record keyed to the step identity captured when the paid dispatch was admitted. */
data class LaneToolCall(
    val id: String,
    val caseId: String,
    val stepId: String,
    val iteration: Int,
    val attempt: Int,
    val toolName: String,
    val argumentsPreview: String,
    val resultPreview: String = "",
    val status: LaneToolCallStatus = LaneToolCallStatus.RUNNING,
    val startedAt: Long,
    val durationMs: Long? = null,
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

/** Sum of the non-overlapping agent and inline-judge scopes of this case occurrence. */
fun CaseResult.aiUsage(): AiUsageStats? = sumAiUsage(
    priorAttempts.flatMap(::caseAttemptUsageParts) +
        listOf(agentUsage, judgeUsage, externalToolCalls?.takeIf { it > 0L }?.let { AiUsageStats(toolCalls = it, partial = true) }),
)

private fun caseAttemptUsageParts(attempt: CaseAttemptRecord): List<AiUsageStats?> = listOf(
    attempt.agentUsage,
    attempt.judgeUsage,
    attempt.externalToolCalls?.takeIf { it > 0L }?.let { AiUsageStats(toolCalls = it, partial = true) },
)

/** Sum of case occurrences and suite hooks in this lane; no cached-history-derived counts are involved. */
fun LaneResult.aiUsage(): AiUsageStats? = sumAiUsage(cases.map(CaseResult::aiUsage))

/** Run total from leaf case scopes plus comparison judges. Lane/case rollups are intentionally not re-added. */
fun TestRun.aiUsage(): AiUsageStats? = sumAiUsage(lanes.flatMap { lane -> lane.cases.map { it.aiUsage() } } + comparisons.map { it.usage })

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
    val suiteId: String = "",
    val repeat: Int = 1,
    val usage: AiUsageStats? = null,
    val pendingVerificationSteps: Int = 0,
    val flaky: Boolean = false,
)

fun TestRun.summary(): RunSummary {
    val steps = lanes.flatMap { lane -> lane.cases.flatMap { it.steps } }.filterNot { it.setup }
    val pendingAttempts = lanes.flatMap { lane ->
        val currentSteps = lane.cases.flatMap { it.steps }.filterNot { it.setup }
        val priorSteps = lane.cases.flatMap { case -> case.priorAttempts.flatMap { it.steps } }.filterNot { it.setup }
        (currentSteps + priorSteps).flatMap { step ->
            val identity = listOf(lane.laneId, step.caseId, step.iteration.toString(), step.caseAttempt.toString(), step.stepId)
            buildList {
                step.attemptHistory.filter { it.verificationState == VerificationState.PENDING }.forEach { attempt ->
                    add(identity + attempt.attempt.toString())
                }
                if (step.verificationState == VerificationState.PENDING) add(identity + step.attempts.toString())
            }
        }
    }.distinct()
    return RunSummary(
        id = id,
        suiteName = suite.name,
        status = status,
        createdAt = createdAt,
        finishedAt = finishedAt,
        laneCount = lanes.size,
        caseCount = lanes.firstOrNull()?.cases?.count { it.caseId != SUITE_SETUP_CASE_ID && it.caseId != SUITE_TEARDOWN_CASE_ID } ?: 0,
        passedSteps = steps.count { it.status == StepStatus.PASS && it.verificationState != VerificationState.PENDING },
        totalSteps = steps.size,
        suiteId = config.suiteId,
        repeat = config.repeat,
        usage = aiUsage(),
        pendingVerificationSteps = pendingAttempts.size,
        flaky = lanes.any { lane -> lane.cases.any { case -> case.flaky || case.steps.any { it.flaky } } },
    )
}

// ── Who performed a step, in words ───────────────────────────────────

/** "Manual step" / "Script step" for a step the run performed, null for one the AI agent did. */
fun StepResult.kindLabel(): String? = when (kind) {
    StepKind.AGENT -> null
    StepKind.MANUAL -> "Manual step"
    StepKind.SCRIPT -> "Script step"
}

/** How a manual or script step was finished, in a few words ("confirmed by the tester"), or null when that is not known or not applicable. */
fun StepResult.completionLabel(): String? = when {
    kind == StepKind.AGENT -> null
    completedBy == StepCompletion.TESTER -> "confirmed by the tester"
    completedBy == StepCompletion.CHECKS -> "detected by its checks"
    completedBy == StepCompletion.SCRIPT -> "done by a script"
    completedBy == StepCompletion.SKIPPED -> "skipped"
    status == StepStatus.TIMEOUT && kind == StepKind.MANUAL -> "not confirmed"
    else -> null
}

/** The completion mode a manual step asked for, in words. */
fun ManualCompletion.label(): String = when (this) {
    ManualCompletion.TESTER_ONLY -> "tester only"
    ManualCompletion.CHECKS_ONLY -> "checks only"
    ManualCompletion.TESTER_OR_CHECKS -> "tester or checks"
}

/** One line for a report: the kind, how it was completed and (manual) the completion mode; null for an AI agent step. */
fun StepResult.performedByLine(): String? {
    val kindText = kindLabel() ?: return null
    val details = listOfNotNull(completionLabel(), manualCompletion?.let { "completion: ${it.label()}" })
    return if (details.isEmpty()) kindText else "$kindText · ${details.joinToString(" · ")}"
}
