package com.indagium.testing.run

import com.indagium.debug.IndagiumToolGateway
import com.indagium.model.AiUsageStats
import com.indagium.testing.device.DeviceInputAction
import com.indagium.testing.device.TestDeviceSession
import com.indagium.testing.model.CheckResult
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.EvidenceFlags
import com.indagium.testing.model.JudgeClassification
import com.indagium.testing.model.JudgeInconclusivePolicy
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.LaneToolCall
import com.indagium.testing.model.LaneToolCallStatus
import com.indagium.testing.model.OnFailure
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepCompletion
import com.indagium.testing.model.StepJudgement
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.isDriverStep
import com.indagium.testing.script.ScriptRunContext
import com.indagium.testing.script.TestScriptRunner
import com.indagium.testing.script.untrustedData
import com.indagium.testing.store.TranscriptWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal const val CASE_BUDGET_EXHAUSTED_NOTE = "The case tool-call budget was exhausted. Increase the case budget and run again."

// The step protocol of one sequence of steps (a case, or the steps of a shared step used as a hook). The lane's agent
// (or an external client) sees ONLY the current step; `finish_step` is what moves the sequence on:
//   1. it takes a screenshot and the log range of the step, then runs the deterministic checks;
//   2. PASS (the agent claimed pass and every check passed) advances; anything else is retried while attempts remain
//      ("redo"), and then the step's onFailure decides: STOP_CASE ends the sequence, CONTINUE and
//      CREATE_ISSUE_AND_CONTINUE advance (the latter flags issueRequested), PAUSE_FOR_USER waits for the user's
//      decision (retry / continue / stop) through the listener;
//   3. the answer names the next step, a redo, or that the case is over.
// A step that outlives its timeout is closed by the watchdog (checkTimeout) as TIMEOUT and the same onFailure applies;
// the sequence then reports a SequenceEvent so a running agent can be restarted at the next step.
//
// With a judge (see StepJudging.kt for the rules) finish_step also asks it, inline, after the deterministic checks; the
// judge sees the evidence only, never the agent's claim. While the run is paused (RunPauseGate) finish_step holds its
// answer, after the step is recorded, until the run is resumed; the step timer is frozen and restarted meanwhile.
//
// A MANUAL or SCRIPT step is performed by the RUN, not by the agent (see DriverStepRunner.kt): the lane driver sees [driverStep] and runs
// it, then reports the outcome through finishDriverStep. While such a step is current the agent's tools and finish_step are refused,
// the watchdog leaves it alone (the driver owns its deadline) and the agent is told, when its finish_step moves the sequence onto
// one, that the next step is not its own: the driver then restarts an agent segment at the next AGENT step.
//
// All state changes happen under [mutex]; `finishing` makes finish_step and the watchdog mutually exclusive, so the
// timer never fires while a step is being finished and a second finish_step cannot start in parallel.

private const val NANOS_PER_MILLI = 1_000_000L
private const val PLAYBACK_SAMPLE_COUNT = 3
private const val PLAYBACK_SAMPLE_INTERVAL_MS = 500L
private val PLAYBACK_RELEVANCE = Regex("\\b(play(?:back|ing|ed)?|pause(?:d)?|video|stream(?:ing)?|media)\\b", RegexOption.IGNORE_CASE)
private const val SCREENSHOT_EXTENSION = "png"
private const val DRIVER_STEP_MESSAGE =
    "The app is performing this step itself (a manual or script step); wait for the next step before using device or script tools."
private const val HANDOFF_MESSAGE =
    "Recorded. The next step is performed by the app, not by you (it is a manual or script step). Stop now and do not call any more " +
        "tools; you will be started again for the step after it."
private const val STEP_PAUSED_MESSAGE = "The run is paused until the user decides what to do with this step; wait."
private const val CASE_OVER_MESSAGE = "The case is finished. Stop now and do not call any more tools."
private const val SETUP_OVER_MESSAGE = "This setup is finished. Stop now and do not call any more tools."
internal const val STEP_BUDGET_EXHAUSTED_MESSAGE = "step budget exhausted — call finish_step"
private const val REPLACED_RUN_MESSAGE = "This agent run was replaced by a new one; stop now."
private const val TIMEOUT_OBSERVATION = "No result was reported before the step timed out."
private const val MAX_AGENT_EXAMPLE_SOURCE_BYTES = 16 * 1024 * 1024
private const val MAX_AGENT_EXAMPLE_CAPTION_CHARS = 300

/** Read an asset with a hard cap that remains effective if the file changes after library validation. */
internal fun readBoundedExampleAsset(file: File?): ByteArray? {
    if (file == null || !file.isFile || file.length() !in 1..MAX_AGENT_EXAMPLE_SOURCE_BYTES.toLong()) return null
    return runCatching {
        file.inputStream().use { input ->
            val bytes = input.readNBytes(MAX_AGENT_EXAMPLE_SOURCE_BYTES + 1)
            bytes.takeIf { it.isNotEmpty() && it.size <= MAX_AGENT_EXAMPLE_SOURCE_BYTES }
        }
    }.getOrNull()
}

internal sealed interface SequenceEvent {
    /** The sequence is over: every step ran, a step stopped the case, or the sequence was aborted. */
    data object Ended : SequenceEvent

    /** The current step timed out; the driver may pause for user choice before it restarts the agent. */
    data class TimedOut(val deferAgentCancel: Boolean = false) : SequenceEvent

    /** The sequence moved to step [index] without the agent finishing the current one (a timeout or the user's retry). */
    data class Moved(val index: Int) : SequenceEvent
}

internal enum class PauseDecision { RETRY, CONTINUE, STOP }

/** What a paused lane shows the user: the step that did not pass and how it failed. */
internal data class PausedStep(
    val caseName: String,
    val stepNumber: Int,
    val action: String,
    val status: StepStatus,
    val observation: String,
    val failedChecks: List<String>,
)

/** What a sequence reports to its owner: progress for the status views, finished steps, and the pause. */
internal interface SequenceListener {
    fun stepStarted(stepNumber: Int, action: String, attempt: Int)

    fun stepRecorded(result: StepResult)

    fun toolCallStarted(call: LaneToolCall) = Unit

    fun toolCallFinished(call: LaneToolCall) = Unit

    fun judgeUsage(usage: AiUsageStats) = Unit

    suspend fun awaitUser(paused: PausedStep): PauseDecision

    /** The lane started or stopped waiting for a tester or a script confirmation (something the status views show, not stored in the run). */
    fun waitStateChanged() = Unit
}

/** [evidencePrefix] makes screenshot file names unique across cases and iterations. */
internal data class SequenceSpec(
    val caseId: String,
    val caseName: String,
    val steps: List<TestStep>,
    val setup: Boolean,
    val allowedTools: Set<String>?,
    val evidencePrefix: String,
    val caseBudget: CaseBudget? = null,
    val iteration: Int = 1,
)

/** What every sequence of a lane shares. [nanoTime] and [wallClock] are test seams. */
@Suppress("LongParameterList") // Everything a lane's sequences share, built once per lane.
internal class SequenceEnv(
    val session: TestDeviceSession,
    val scriptRunner: TestScriptRunner,
    val scripts: List<TestScript>,
    val runDir: File,
    val laneDir: File,
    val packageName: String,
    val evidence: EvidenceFlags,
    val transcript: TranscriptWriter?,
    val listener: SequenceListener,
    val nanoTime: () -> Long = System::nanoTime,
    val wallClock: () -> Long = System::currentTimeMillis,
    /** The lane's judge, or null. */
    val judge: StepJudge? = null,
    val judgeMode: JudgeMode = JudgeMode.OFF,
    val judgeInconclusivePolicy: JudgeInconclusivePolicy = JudgeInconclusivePolicy.BLOCK,
    /** True when the run requested a judge, even if startup failed before a [judge] instance became available. */
    val judgeRequested: Boolean = false,
    val pauseGate: RunPauseGate? = null,
    val suiteId: String = "",
    val goldenImage: (suiteId: String, assetPath: String) -> ByteArray? = { _, _ -> null },
    val goldenFile: (suiteId: String, assetPath: String) -> File? = { _, _ -> null },
    val externalDispatchAdmitted: (Job?, String?, Int) -> Unit = { _, _, _ -> },
)

/** What a step's screenshot became: the file kept as evidence (relative to the run folder) and the image the judge looks at. */
private class Shot(val path: String?, val jpeg: ByteArray?, val capturedAtMs: Long)

private class Evaluation(
    val step: TestStep,
    val status: StepStatus,
    val result: StepResult,
    val failedChecks: List<String>,
    val stepIndex: Int,
    val attemptNumber: Int,
)

private class ActionEvidence(
    val shot: Shot,
    val samples: List<Shot>,
    val checks: List<CheckResult>,
    val automaticFailed: Boolean,
    val logEnd: Long?,
    val evidenceNanos: Long,
    val checksNanos: Long,
)

private data class JudgementOutcome(val judgement: StepJudgement?, val durationNanos: Long?)

/**
 * What the run learned performing a MANUAL or SCRIPT step. [claim] is the verdict before the checks (PASS: it was done, FAIL: it
 * was not or it failed, BLOCKED: it could not be done); [checks] is which of the step's checks to evaluate (null: all of them).
 */
internal class DriverReport(
    val claim: LaneStepStatus,
    val observation: String,
    /** Who finished the step; null when nobody did (it was not confirmed in time). */
    val completion: StepCompletion?,
    val checks: List<StepCheck>? = null,
    val note: String? = null,
)

private data class ToolAdmission(
    val refusal: String?,
    val paid: Boolean,
    val activity: LaneToolCall?,
    val identity: LaneStepIdentity?,
    val reservedCost: Int = 0,
)

private data class AdmittedToolResult(val value: Any?)

private sealed interface Flow {
    data object End : Flow

    data class Next(val index: Int) : Flow

    data object Redo : Flow
}

internal class StepSequence(private val env: SequenceEnv, val spec: SequenceSpec) : LaneCallbacks {
    private val mutex = Mutex()
    private val deviceMutationLock = Mutex()
    private val finishing = AtomicBoolean(false)
    private val epoch = AtomicLong(0)
    private val toolCalls = AtomicInteger(0)
    private val recorded = CopyOnWriteArrayList<StepResult>()
    private val observations = CopyOnWriteArrayList<String>()
    private val attemptNotes = ArrayList<String>()
    private val checks = DeterministicChecks(env.session, env.scriptRunner, { id -> env.scripts.firstOrNull { it.id == id } }, ::scriptContext)

    /** Paid gateway handlers admitted for the current step and still executing. Guarded by [mutex]. */
    private var activePaidDispatches = 0
    private var uiRecoveryAttempts = 0
    private var startCaptureAttempted = false
    private var previousAttemptFailure: Map<String, Any?>? = null

    @Volatile private var submittedReportStepId: String? = null

    @Volatile private var submittedReportAttempt = 0

    @Volatile private var submittedReportClaim: String? = null

    @Volatile private var submittedReportObservation: String? = null

    @Volatile private var lastAttemptShot: Shot? = null

    /**
     * The screen the current attempt started from: the end-of-step screenshot of the step (or failed attempt) before it, which the
     * lane already holds for its own judge. Nothing else is more reliable: it is the very state the device was left in, taken by the
     * run itself, and costs no extra capture. Null for the first step of a case and after a step that kept no screenshot.
     */
    @Volatile private var attemptStartShot: Shot? = null

    @Volatile private var attemptPlaybackSamples: List<Shot> = emptyList()

    @Volatile private var lastAttemptChecks: List<CheckResult> = emptyList()

    /** Ended / Moved notifications for whoever drives this sequence. Unbounded: a sender never waits. */
    val events = Channel<SequenceEvent>(Channel.UNLIMITED)

    @Volatile private var index = -1

    @Volatile private var attempt = 1

    @Volatile private var attemptStartNanos = 0L

    @Volatile private var attemptLogOffset = 0L

    @Volatile private var attemptTranscriptOffset = 0L

    @Volatile private var lastKnownLogEnd: Long? = null

    @Volatile private var firstAttemptWallMs = 0L

    @Volatile private var active = false

    @Volatile private var awaitingUser = false

    @Volatile var ended = false
        private set

    /** The log marker taken when the current attempt began: the base of wait_for_log and read_log_since_step. */
    val stepLogOffset: Long get() = attemptLogOffset

    /** The lane tools of this sequence; the case's allowed-tool list narrows them. */
    val tools: LaneTools by lazy {
        buildLaneTools(
            LaneToolContext(
                session = env.session,
                currentStep = ::brief,
                stepLogOffset = { attemptLogOffset },
                scripts = env.scripts,
                scriptContext = ::scriptContext,
                callbacks = this,
                scriptRunner = env.scriptRunner,
                allowedTools = spec.allowedTools,
                currentExamples = { spec.steps.getOrNull(index)?.examples.orEmpty() },
                loadGoldenImage = { example ->
                    withContext(Dispatchers.IO) { readBoundedExampleAsset(env.goldenFile(env.suiteId, example.assetPath)) }
                },
                inputSequence = InputSequenceCallbacks(
                    releaseBudget = { identity, reserved, consumed ->
                        val unused = (reserved - consumed).coerceAtLeast(0)
                        if (unused > 0) {
                            spec.caseBudget?.releaseDispatch(unused)
                            mutex.withLock {
                                if (identity != null && isCurrentLocked(identity)) {
                                    toolCalls.updateAndGet { current -> (current - unused).coerceAtLeast(0) }
                                }
                            }
                        }
                    },
                    onAction = { identity, actionIndex, action ->
                        env.transcript?.append(
                            "input_sequence_action",
                            mapOf("step" to identity?.stepId, "attempt" to identity?.attempt, "epoch" to identity?.epoch,
                                "actionIndex" to actionIndex,
                                "tool" to action.action, "succeeded" to action.succeeded, "detail" to action.detail),
                        )
                    },
                    onCapture = { identity ->
                        env.transcript?.append("input_sequence_capture_started", mapOf(
                            "step" to identity?.stepId, "attempt" to identity?.attempt, "epoch" to identity?.epoch,
                        ))
                    },
                    onInterrupted = { identity, nextActionIndex, reason ->
                        env.transcript?.append("input_sequence_interrupted", mapOf(
                            "step" to identity?.stepId, "attempt" to identity?.attempt, "epoch" to identity?.epoch,
                            "nextActionIndex" to nextActionIndex, "reason" to reason,
                        ))
                    },
                    onFinished = { identity, durationMs, consumedCost ->
                        env.transcript?.append("input_sequence_finished", mapOf(
                            "step" to identity?.stepId, "attempt" to identity?.attempt, "epoch" to identity?.epoch,
                            "durationMs" to durationMs, "consumedCost" to consumedCost,
                        ))
                    },
                    remainingExecutionMs = ::remainingExecutionMs,
                ),
            ),
        )
    }

    private val externalGateway: IndagiumToolGateway by lazy { gateway(null) }

    /** What an external client's `test_lane_tool_call` runs against. */
    fun externalGateway(): IndagiumToolGateway = externalGateway

    /** Results of the steps that ended, in step order. */
    fun results(): List<StepResult> = recorded.toList()

    /** The attempt number of the current step (1 for the first try). */
    val currentAttempt: Int get() = attempt

    /** Index of the step the sequence is on; the number of steps once it is over. */
    val currentIndex: Int get() = if (ended) spec.steps.size else index

    fun scriptContext() = ScriptRunContext(
        deviceSerial = env.session.serial,
        packageName = env.packageName,
        runDir = env.laneDir,
        caseId = spec.caseId,
        stepId = spec.steps.getOrNull(index)?.id.orEmpty(),
    )

    fun brief(): LaneStepBrief? {
        if (!active) return null
        val step = spec.steps.getOrNull(index)?.takeUnless { it.isDriverStep } ?: return null
        val allowedExampleTools = tools.gateway.tools.map { it.name }.filter { it == "list_step_examples" || it == "get_step_example" }
        return LaneStepBrief(
            step.id,
            spec.caseName,
            index + 1,
            spec.steps.size,
            step.action,
            step.expected,
            attempt,
            step.examples.map { example ->
                LaneStepExampleBrief(
                    exampleId = example.id,
                    kind = if (example is com.indagium.testing.model.StepExample.GoldenScreenshot) "goldenScreenshot" else "referenceLog",
                    caption = example.caption.take(MAX_AGENT_EXAMPLE_CAPTION_CHARS),
                )
            },
            allowedExampleTools,
            optional = step.optional,
            condition = step.condition,
            intent = step.intent.wire,
            precondition = step.precondition,
            dispatchEpoch = epoch.get(),
        )
    }

    // ── Gateway guard ────────────────────────────────────────────────

    /** A new agent run is about to take over: tools of any earlier run are refused from now on. */
    fun newEpoch(): Long = epoch.incrementAndGet()

    suspend fun currentAttemptIdentity(): Pair<String, Int>? = mutex.withLock {
        if (!active || ended) null else spec.steps.getOrNull(index)?.id?.let { it to attempt }
    }

    private fun isCurrentLocked(identity: LaneStepIdentity): Boolean =
        active && !ended && epoch.get() == identity.epoch && attempt == identity.attempt &&
            spec.steps.getOrNull(index)?.id == identity.stepId

    private suspend fun isCurrent(identity: LaneStepIdentity?): Boolean =
        identity != null && mutex.withLock { isCurrentLocked(identity) }

    private suspend fun remainingExecutionMs(identity: LaneStepIdentity?): Long = mutex.withLock {
        if (identity == null || !isCurrentLocked(identity)) return@withLock 0L
        (spec.steps[index].timeoutMs - elapsedMs()).coerceAtLeast(0L)
    }

    /** The lane tools behind the sequence's guard: the per-step call cap and, for an agent run, its epoch. */
    fun gateway(runEpoch: Long?): IndagiumToolGateway = cappedGateway(tools.gateway) { name, arguments, execute ->
        dispatchTool(name, arguments, runEpoch, execute)
    }

    /** Serializes paid dispatch admission with finish/abort, and keeps finish from overtaking an admitted action. */
    private suspend fun dispatchTool(tool: String, arguments: Map<String, Any?>, runEpoch: Long?, execute: suspend () -> Any?): Any? {
        val admission = mutex.withLock { admitToolLocked(tool, arguments, runEpoch) }
        admission.refusal?.let { return mapOf("error" to it) }
        val initial = admission.activity
        if (admission.paid) env.externalDispatchAdmitted(currentCoroutineContext()[Job], initial?.stepId, initial?.attempt ?: 0)
        if (initial != null) runCatching { env.listener.toolCallStarted(initial) }
        return executeAdmittedTool(admission, initial, execute)
    }

    @Suppress("CyclomaticComplexMethod") // Budget, identity and timeout checks must be admitted atomically under the sequence lock.
    private suspend fun admitToolLocked(tool: String, arguments: Map<String, Any?>, runEpoch: Long?): ToolAdmission {
        var paidDispatch = false
        var reservedCost = 0
        var activity: LaneToolCall? = null
        val argumentsPreview = laneToolPreview(arguments)
        var plannedDurationMs = 0L
        val refusal = when {
            runEpoch != null && runEpoch != epoch.get() -> REPLACED_RUN_MESSAGE
            ended -> overMessage()
            tool == FINISH_STEP_TOOL -> null
            else -> {
                val step = spec.steps.getOrNull(index)
                when {
                    step == null -> null
                    step.isDriverStep -> DRIVER_STEP_MESSAGE
                    finishing.get() || awaitingUser -> "The step is finishing or paused; wait for the next step before using device or script tools."
                    tool in LANE_PROTOCOL_TOOL_NAMES && tool != FINISH_STEP_TOOL && toolCalls.get() >= step.maxToolCalls ->
                        STEP_BUDGET_EXHAUSTED_MESSAGE
                    tool in LANE_PROTOCOL_TOOL_NAMES -> null
                    elapsedMs() >= step.timeoutMs -> "The step execution allowance has expired; do not start another tool call."
                    toolCalls.get() >= step.maxToolCalls -> STEP_BUDGET_EXHAUSTED_MESSAGE
                    tool == "wait_for_ui" && uiRecoveryAttempts >= MAX_UI_RECOVERY_ATTEMPTS ->
                        "This step already used its $MAX_UI_RECOVERY_ATTEMPTS bounded UI-recovery attempts."
                    else -> {
                        val cost = if (tool == "perform_input_sequence") {
                            try {
                                val actions = parseInputSequence(arguments, spec.allowedTools)
                                plannedDurationMs = actions.sumOf { action ->
                                    action.delayMs + ((action as? DeviceInputAction.Swipe)?.durationMs ?: 0)
                                }
                                if (plannedDurationMs > (step.timeoutMs - elapsedMs()).coerceAtLeast(0L)) {
                                    return ToolAdmission(
                                        "The requested input sequence durations exceed this step's remaining execution allowance.",
                                        false, null, null,
                                    )
                                }
                                actions.size + 1
                            } catch (invalid: IllegalArgumentException) {
                                return ToolAdmission(invalid.message ?: "Invalid input sequence.", false, null, null)
                            }
                        } else {
                            1
                        }
                        reservedCost = cost
                        if (toolCalls.addAndGet(cost) > step.maxToolCalls) {
                            toolCalls.addAndGet(-cost)
                            STEP_BUDGET_EXHAUSTED_MESSAGE
                        } else if (spec.caseBudget?.tryDispatch(cost) == false) {
                            toolCalls.addAndGet(-cost)
                            withContext(NonCancellable) { abortLocked(StepStatus.ERROR, CASE_BUDGET_EXHAUSTED_NOTE) }
                            CASE_BUDGET_EXHAUSTED_MESSAGE
                        } else {
                            if (tool == "wait_for_ui") uiRecoveryAttempts++
                            activePaidDispatches++
                            paidDispatch = true
                            null
                        }
                    }
                }
            }
        }
        if (refusal == null) {
            spec.steps.getOrNull(index)?.let { step ->
                activity = LaneToolCall(
                    id = UUID.randomUUID().toString(),
                    caseId = spec.caseId,
                    stepId = step.id,
                    iteration = spec.iteration,
                    attempt = attempt,
                    toolName = tool,
                    argumentsPreview = argumentsPreview,
                    startedAt = env.wallClock(),
                )
            }
        }
        val identity = activity?.let { LaneStepIdentity(it.stepId, it.attempt, epoch.get()) }
        return ToolAdmission(refusal, paidDispatch, activity, identity, if (paidDispatch) reservedCost else 0)
    }

    @Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod") // Preserve identity, refunds and failure status across suspending dispatch.
    private suspend fun executeAdmittedTool(
        admission: ToolAdmission,
        initial: LaneToolCall?,
        execute: suspend () -> Any?,
    ): Any? {
        val startNanos = env.nanoTime()
        var completed = initial
        var executionStarted = false
        var reservationRefunded = false
        return try {
            val tool = initial?.toolName.orEmpty()
            val identity = admission.identity
            if (!admission.paid) {
                val result = execute()
                completed = initial?.completed(
                    if (laneToolResultError(result) == null) LaneToolCallStatus.SUCCEEDED else LaneToolCallStatus.FAILED,
                    laneToolPreview(result), elapsedMs(startNanos),
                )
                return result
            }

            suspend fun refundUnstarted() {
                if (!executionStarted && !reservationRefunded) {
                    reservationRefunded = true
                    refundStaleAdmission(admission)
                }
            }
            val remainingMs = remainingExecutionMs(identity)
            val enforceAttemptDeadline = tool == "perform_input_sequence" || tool == "wait_for_ui"

            suspend fun executeWithinAdmission(): AdmittedToolResult {
                val value = if (mutatesDevice(tool)) {
                    deviceMutationLock.withLock {
                        if (!isCurrent(identity)) {
                            refundUnstarted()
                            mapOf("error" to "This step attempt ended before the device action could start; inspect the current step.")
                        } else {
                            ensureAttemptStartShot(identity)
                            if (!isCurrent(identity)) {
                                refundUnstarted()
                                mapOf("error" to "This step attempt ended before the device action could start; inspect the current step.")
                            } else {
                                executionStarted = true
                                if (identity == null) execute() else withContext(LaneDispatchIdentityElement(identity)) { execute() }
                            }
                        }
                    }
                } else {
                    executionStarted = true
                    if (identity == null) execute() else withContext(LaneDispatchIdentityElement(identity)) { execute() }
                }
                return AdmittedToolResult(value)
            }
            val outcome = if (remainingMs <= 0L) {
                null
            } else if (enforceAttemptDeadline) {
                withTimeoutOrNull(remainingMs) { executeWithinAdmission() }
            } else {
                executeWithinAdmission()
            }
            val result = if (outcome == null) {
                refundUnstarted()
                mapOf("error" to "The step execution allowance expired before this tool completed.")
            } else {
                outcome.value
            }
            result.also { result ->
                val status = if (laneToolResultError(result) == null) LaneToolCallStatus.SUCCEEDED else LaneToolCallStatus.FAILED
                completed = initial?.completed(status, laneToolPreview(result), elapsedMs(startNanos))
            }
        } catch (cancelled: CancellationException) {
            if (admission.paid && !executionStarted && !reservationRefunded) {
                reservationRefunded = true
                withContext(NonCancellable) { refundStaleAdmission(admission) }
            }
            completed = initial?.completed(LaneToolCallStatus.CANCELLED, "Cancelled while the tool was running.", elapsedMs(startNanos))
            throw cancelled
        } catch (failure: Exception) {
            completed = initial?.completed(
                LaneToolCallStatus.FAILED,
                laneToolPreview(mapOf("error" to (failure.message ?: failure::class.simpleName.orEmpty()))),
                elapsedMs(startNanos),
            )
            throw failure
        } finally {
            withContext(NonCancellable) {
                if (admission.paid) mutex.withLock { activePaidDispatches = (activePaidDispatches - 1).coerceAtLeast(0) }
                completed?.let { call -> runCatching { env.listener.toolCallFinished(call) } }
            }
        }
    }

    /** Refund an admission that became stale while waiting for the device lock, without touching a later step's cap. */
    private suspend fun refundStaleAdmission(admission: ToolAdmission) {
        val unused = admission.reservedCost
        if (unused <= 0) return
        spec.caseBudget?.releaseDispatch(unused)
        val identity = admission.identity ?: return
        mutex.withLock {
            if (isCurrentLocked(identity)) toolCalls.updateAndGet { current -> (current - unused).coerceAtLeast(0) }
        }
    }

    private fun LaneToolCall.completed(status: LaneToolCallStatus, preview: String, durationMs: Long): LaneToolCall = copy(
        resultPreview = preview,
        status = status,
        durationMs = durationMs,
    )

    private fun elapsedMs(startNanos: Long): Long = ((env.nanoTime() - startNanos) / NANOS_PER_MILLI).coerceAtLeast(0L)

    private fun laneToolResultError(result: Any?): Any? = (result as? Map<*, *>)?.get("error")?.takeIf { it.toString().isNotBlank() }

    // ── Starting steps ───────────────────────────────────────────────

    /** Starts step [stepIndex] (attempt 1); an index past the last step ends the sequence. */
    suspend fun begin(stepIndex: Int) = mutex.withLock { beginLocked(stepIndex) }

    /** Gives the current step a fresh timeout after a user pause. */
    fun resetStepTimer() {
        attemptStartNanos = env.nanoTime()
    }

    private suspend fun beginLocked(stepIndex: Int) {
        if (stepIndex >= spec.steps.size) {
            endLocked()
            return
        }
        index = stepIndex
        attempt = 1
        attemptNotes.clear()
        firstAttemptWallMs = env.wallClock()
        previousAttemptFailure = null
        submittedReportStepId = null
        submittedReportAttempt = 0
        submittedReportClaim = null
        submittedReportObservation = null
        startAttemptLocked()
    }

    private suspend fun startAttemptLocked() {
        attemptLogOffset = runCatching { env.session.logMarker() }.getOrDefault(attemptLogOffset)
        lastKnownLogEnd = attemptLogOffset
        attemptTranscriptOffset = env.transcript?.sizeBytes ?: 0L
        attemptStartNanos = env.nanoTime()
        toolCalls.set(0)
        attemptStartShot = null
        attemptPlaybackSamples = emptyList()
        startCaptureAttempted = false
        uiRecoveryAttempts = 0
        lastAttemptShot = null
        lastAttemptChecks = emptyList()
        observations.clear()
        submittedReportStepId = null
        submittedReportAttempt = 0
        submittedReportClaim = null
        submittedReportObservation = null
        active = true
        env.listener.stepStarted(index + 1, spec.steps[index].action, attempt)
    }

    private fun mutatesDevice(tool: String): Boolean = tool in setOf(
        "tap", "swipe", "press_key", "open_system_panel", "rotate_device", "input_text", "launch_app", "open_url", "perform_input_sequence",
    ) || env.scripts.any { it.toolName == tool }

    /** Capture the actual current screen immediately before the attempt's first device mutation. */
    suspend fun beforeDeviceMutation() {
        deviceMutationLock.withLock { ensureAttemptStartShot() }
    }

    @Suppress("CyclomaticComplexMethod") // Start capture validates attempt identity across a suspended device read.
    private suspend fun ensureAttemptStartShot(identity: LaneStepIdentity? = null, stateLocked: Boolean = false) {
        suspend fun markCaptureStarted(): Pair<Int, Int>? {
            if (identity != null && !isCurrentLocked(identity)) return null
            if (startCaptureAttempted) return null
            startCaptureAttempted = true
            return index to attempt
        }
        val capturedFor = (if (stateLocked) markCaptureStarted() else mutex.withLock { markCaptureStarted() }) ?: return
        if (!env.evidence.screenshots && env.judge == null) return

        suspend fun resetCaptureFlag() {
            if (index == capturedFor.first && attempt == capturedFor.second && (identity == null || isCurrentLocked(identity))) {
                startCaptureAttempted = false
            }
        }
        val captured = try {
            val current = env.session.screenshot()
            val capturedAtMs = env.wallClock()
            val path = if (env.evidence.screenshots) saveScreenshot(current.png, "start", capturedFor.first, capturedFor.second) else null
            Shot(path, current.image.bytes.takeIf { env.judge != null }, capturedAtMs)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (stateLocked) resetCaptureFlag() else mutex.withLock { resetCaptureFlag() }
            }
            throw cancelled
        } catch (_: Exception) {
            null
        }

        fun saveCaptured() {
            val stillSameAttempt = index == capturedFor.first && attempt == capturedFor.second &&
                (identity == null || isCurrentLocked(identity))
            if (stillSameAttempt) attemptStartShot = captured
        }
        if (stateLocked) saveCaptured() else mutex.withLock { saveCaptured() }
    }

    private fun endLocked() {
        active = false
        ended = true
        events.trySend(SequenceEvent.Ended)
    }

    private fun elapsedMs(): Long = (env.nanoTime() - attemptStartNanos) / NANOS_PER_MILLI

    private fun playbackRelated(step: TestStep): Boolean =
        PLAYBACK_RELEVANCE.containsMatchIn(listOfNotNull(step.action, step.expected, step.precondition).joinToString(" "))

    // ── Steps the run performs itself ────────────────────────────────

    /** The current step when the RUN performs it (a manual or script step), else null. Such a step is closed through the methods below. */
    val driverStep: TestStep? get() = if (ended || !active) null else spec.steps.getOrNull(index)?.takeIf { it.isDriverStep }

    /** What this sequence works with (the device, the script runner, the clock), for whoever performs its driver steps. */
    val environment: SequenceEnv get() = env

    /** Closes the current driver step as done or failed: runs [report]'s checks, judges when it has judge checks, records the result and moves on. */
    suspend fun finishDriverStep(report: DriverReport) {
        val evaluation = mutex.withLock {
            val step = driverStep ?: return@withLock null
            // A script's action and deterministic checks share the step allowance. User confirmation
            // and manual waiting have their own boundary, so their post-confirmation checks start fresh.
            if (report.completion != StepCompletion.SCRIPT) resetStepTimer()
            val completed = evaluateLocked(report.claim, report.observation, report)
            completed ?: timeoutEvaluationLocked(step.id, index, attempt, verifyDeadline = false, submittedObservation = report.observation)
        } ?: return
        decideFlow(evaluation)
    }

    /** Closes the current driver step as skipped (the tester skipped it, or the run skips manual steps) and continues with the next one. */
    suspend fun skipDriverStep(report: DriverReport) {
        mutex.withLock {
            val step = driverStep ?: return@withLock
            val evaluation = skippedEvaluationLocked(step, report.observation, report) ?: return@withLock
            settleLocked(evaluation, Flow.Next(index + 1), issue = false)
        }
    }

    /**
     * Closes the current driver step as not confirmed (a manual step nobody completed in time, or whose script could not even start):
     * recorded as a timeout that is the step's or the run's problem, never an app defect, then the step's onFailure applies.
     */
    suspend fun failDriverStep(report: DriverReport, status: StepStatus) {
        val evaluation = mutex.withLock {
            val step = driverStep ?: return@withLock null
            val shot = captureScreenshot()
            val logEnd = runCatching { env.session.logMarker() }.getOrNull().also { if (it != null) lastKnownLogEnd = it }
            val result = buildResult(step, status, null, report.observation, emptyList(), shot.path, logEnd, driver = report)
                .copy(classification = JudgeClassification.AGENT_OR_STEP_PROBLEM)
            Evaluation(step, status, result, emptyList(), index, attempt)
        } ?: return
        decideFlow(evaluation)
    }

    // ── LaneCallbacks ────────────────────────────────────────────────

    override fun reportObservation(text: String) {
        if (active) {
            val bounded = text.take(MAX_OBSERVATION_CHARS)
            observations += bounded
            env.transcript?.append("step_observation", mapOf("step" to spec.steps.getOrNull(index)?.id, "attempt" to attempt, "text" to bounded))
        }
    }

    @Suppress("ReturnCount") // Distinct protocol guards refuse without settling a stale step.
    override suspend fun finishStep(status: LaneStepStatus, observation: String): Map<String, Any?> {
        finishRefusal()?.let { return mapOf("error" to it) }
        if (!finishing.compareAndSet(false, true)) return mapOf("error" to "finish_step is already running; wait for its answer.")
        try {
            if (mutex.withLock { activePaidDispatches > 0 }) {
                return mapOf("error" to "A device or script tool is still running; wait for its result before finishing the step.")
            }
            var timedOut = false
            var skipRefusal: String? = null
            val evaluation = mutex.withLock {
                val step = spec.steps.getOrNull(index) ?: return@withLock null
                if (status == LaneStepStatus.SKIPPED) {
                    if (!step.optional) {
                        skipRefusal = "A required step cannot be skipped."
                        return@withLock null
                    }
                    if (observation.isBlank()) {
                        skipRefusal = "Explain what you checked before skipping this optional step."
                        return@withLock null
                    }
                }
                val stepIndex = index
                val attemptNumber = attempt
                val completed = if (status == LaneStepStatus.SKIPPED) skippedEvaluationLocked(step, observation) else evaluateLocked(status, observation)
                if (completed == null) timedOut = true
                completed ?: timeoutEvaluationLocked(
                    step.id, stepIndex, attemptNumber, verifyDeadline = false,
                    submittedClaim = status.name.lowercase(), submittedObservation = observation,
                )
            }
            skipRefusal?.let { return mapOf("error" to it) }
            val currentEvaluation = evaluation ?: return mapOf("error" to "No step is active.")
            val answer = when {
                timedOut -> respond(settleTimeout(currentEvaluation, cancelAgentBeforePause = false))
                status == LaneStepStatus.SKIPPED -> {
                    val flow = mutex.withLock { settleLocked(currentEvaluation, Flow.Next(index + 1), issue = false) }
                    announceHandoff(flow)
                    respond(flow)
                }
                else -> decide(currentEvaluation)
            }
            if (!ended) holdWhilePaused()
            return answer
        } finally {
            finishing.set(false)
        }
    }

    /** Why finish_step cannot start now: the case is over, the app performs the current step itself, or the user is deciding. */
    private fun finishRefusal(): String? = when {
        ended -> overMessage()
        driverStep != null -> DRIVER_STEP_MESSAGE
        awaitingUser -> STEP_PAUSED_MESSAGE
        else -> null
    }

    private suspend fun decide(evaluation: Evaluation): Map<String, Any?> {
        val flow = decideFlow(evaluation) ?: return mapOf("error" to overMessage())
        announceHandoff(flow)
        return respond(flow)
    }

    /** Where the sequence goes after [evaluation]: the next step on a pass, a retry, or what the step's onFailure says. Null when the step is stale. */
    private suspend fun decideFlow(evaluation: Evaluation): Flow? {
        if (!mutex.withLock { isCurrentLocked(evaluation) }) return null
        val step = evaluation.step
        if (evaluation.status == StepStatus.PASS) return mutex.withLock { settleLocked(evaluation, Flow.Next(index + 1), issue = false) }
        // Nobody can redo a manual step by itself: a retry would only make the tester wait again.
        val retries = if (step.kind == StepKind.MANUAL) 0 else step.retries
        if (evaluation.status != StepStatus.TIMEOUT && attempt < retries + 1) return mutex.withLock { retryLocked(evaluation) }
        return when (step.onFailure) {
            OnFailure.PAUSE_FOR_USER -> pauseFlow(evaluation)
            OnFailure.STOP_CASE -> mutex.withLock { settleLocked(evaluation, Flow.End, issue = false) }
            OnFailure.CONTINUE -> mutex.withLock { settleLocked(evaluation, Flow.Next(index + 1), issue = false) }
            OnFailure.CREATE_ISSUE_AND_CONTINUE -> mutex.withLock { settleLocked(evaluation, Flow.Next(index + 1), issue = true) }
        }
    }

    /**
     * The agent's finish_step moved the sequence onto a step the run performs itself: make the agent's gateway stale and tell the lane
     * driver, which then closes that agent run, performs the step and starts a new agent run at the next agent step.
     */
    private fun announceHandoff(flow: Flow) {
        if (flow is Flow.Next && driverStep != null) {
            epoch.incrementAndGet()
            events.trySend(SequenceEvent.Moved(flow.index))
        }
    }

    /** Pause all: the step is recorded, the next one started; the agent hears about it only once the run is resumed. */
    private suspend fun holdWhilePaused() {
        val gate = env.pauseGate ?: return
        if (!gate.isPaused) return
        awaitingUser = true
        try {
            gate.awaitResumed()
        } finally {
            awaitingUser = false
        }
        resetStepTimer()
    }

    private fun overMessage(): String = if (spec.setup) SETUP_OVER_MESSAGE else CASE_OVER_MESSAGE

    /** Turns the outcome of a settled step into the tool answer. The next step has already been started. */
    private fun respond(flow: Flow): Map<String, Any?> = when (flow) {
        Flow.End -> mapOf("result" to "case_finished", "message" to overMessage())
        is Flow.Next ->
            if (driverStep != null) {
                mapOf("result" to "handoff", "message" to HANDOFF_MESSAGE)
            } else {
                mapOf("result" to "next_step", "message" to "Recorded. Continue with the next step.", "step" to stepMap())
            }
        Flow.Redo -> mapOf(
            "result" to "redo",
            "message" to "Attempt ${attempt - 1} did not pass. Inspect the current screen before acting because the previous action may " +
                "have succeeded even if verification failed. Do not blindly repeat a toggle. This is attempt $attempt.",
            "step" to stepMap(),
        ) + previousAttemptFailure?.let { untrustedData("previous_attempt_result", it) }.orEmpty()
    }

    private fun stepMap(): Map<String, Any?> {
        val brief = brief() ?: return emptyMap()
        return mapOf(
            "stepNumber" to brief.stepNumber,
            "stepCount" to brief.stepCount,
            "action" to brief.action,
            "expected" to brief.expected,
            "intent" to brief.intent,
            "precondition" to brief.precondition,
            "optional" to brief.optional,
            "condition" to brief.condition,
            "attempt" to brief.attempt,
        ) + brief.exampleMetadata()
    }

    // ── Evaluating a step ────────────────────────────────────────────

    /** A condition-unmet optional action advances without checks, judging, retries or a screenshot capture. */
    private suspend fun skippedEvaluationLocked(step: TestStep, observation: String, driver: DriverReport? = null): Evaluation? {
        if (!active || ended) return null
        val logEnd = runCatching { env.session.logMarker() }.getOrNull().also { if (it != null) lastKnownLogEnd = it }
        val reported = (observations + observation).filter(String::isNotBlank).joinToString("\n")
        val result = buildResult(step, StepStatus.SKIPPED, "skipped".takeIf { driver == null }, reported, emptyList(), null, logEnd, driver = driver)
        return Evaluation(step, StepStatus.SKIPPED, result, emptyList(), index, attempt)
    }

    private suspend fun evaluateLocked(claim: LaneStepStatus, observation: String, driver: DriverReport? = null): Evaluation? {
        if (!active || ended) return null
        val step = spec.steps[index]
        val evaluationStart = env.nanoTime()
        val submittedObservation = (observations + observation).filter(String::isNotBlank).joinToString("\n").take(MAX_OBSERVATION_CHARS)
        submittedReportStepId = step.id
        submittedReportAttempt = attempt
        submittedReportClaim = claimText(claim, driver)
        submittedReportObservation = submittedObservation
        env.transcript?.append(
            "step_report_submitted",
            mapOf(
                "step" to step.id,
                "stepNumber" to index + 1,
                "attempt" to attempt,
                "source" to if (driver == null) "agent" else "driver",
                "claim" to claim.name.lowercase(),
                "observation" to submittedObservation,
                "finalObservation" to observation.take(MAX_OBSERVATION_CHARS),
                "reportedObservations" to observations.toList(),
            ),
        )
        val remaining = (step.timeoutMs - elapsedMs()).coerceAtLeast(0L)
        val actionEvidence = collectActionEvidence(step, driver, evaluationStart, remaining) ?: return null
        val judgeOutcome = evaluateJudgement(step, claim, driver, actionEvidence)
        appendStepTiming(
            step,
            actionEvidence.evidenceNanos,
            actionEvidence.checksNanos,
            judgeOutcome.durationNanos,
        )
        return settleEvaluation(step, claim, driver, submittedObservation, actionEvidence, judgeOutcome.judgement)
    }

    private suspend fun collectActionEvidence(
        step: TestStep,
        driver: DriverReport?,
        evaluationStart: Long,
        remainingMs: Long,
    ): ActionEvidence? = withTimeoutOrNull(remainingMs) {
        if (!startCaptureAttempted) ensureAttemptStartShot(stateLocked = true)
        lastAttemptChecks = emptyList()
        attemptPlaybackSamples = emptyList()
        val samples = mutableListOf<Shot>()
        if (playbackRelated(step) && (env.judge != null || env.evidence.screenshots)) {
            repeat(PLAYBACK_SAMPLE_COUNT) { sampleIndex ->
                if (sampleIndex > 0) delay(PLAYBACK_SAMPLE_INTERVAL_MS)
                samples += captureScreenshot("sample-${sampleIndex + 1}")
                // Keep every successful sample immediately. If the next delay/capture reaches the
                // execution deadline, timeout and retry evidence still retain the frames already taken.
                attemptPlaybackSamples = samples.toList()
            }
        }
        val shot = samples.lastOrNull() ?: captureScreenshot()
        val evidenceDone = env.nanoTime()
        val checksStarted = env.nanoTime()
        val automatic = checks.evaluateAll(driver?.checks ?: step.checks, attemptLogOffset) { result ->
            lastAttemptChecks = lastAttemptChecks + result
        }
        val automaticFailed = automatic.any { it.status == CheckStatus.FAIL || it.status == CheckStatus.ERROR }
        val checksDone = env.nanoTime()
        val logEnd = runCatching { env.session.logMarker() }.getOrNull().also { if (it != null) lastKnownLogEnd = it }
        ActionEvidence(
            shot,
            samples.toList(),
            automatic,
            automaticFailed,
            logEnd,
            evidenceDone - evaluationStart,
            checksDone - checksStarted,
        )
    }

    private suspend fun evaluateJudgement(
        step: TestStep,
        claim: LaneStepStatus,
        driver: DriverReport?,
        evidence: ActionEvidence,
    ): JudgementOutcome {
        val requested = judgeable(step, driver) && shouldJudge(env.judgeMode, step, claim, evidence.automaticFailed)
        if (!requested || (!env.judgeRequested && env.judge == null)) return JudgementOutcome(null, null)
        val started = env.nanoTime()
        val judgement = if (env.judgeRequested && env.judge == null) {
            StepJudgement(
                verdict = JudgeVerdict.INCONCLUSIVE,
                judgedAt = env.wallClock(),
                error = "The configured judge could not be prepared; independent verification is unavailable.",
            )
        } else {
            withTimeoutOrNull(JUDGE_TIMEOUT_MS) {
                judgeAttempt(step, claim, evidence.checks, evidence.automaticFailed, evidence.shot, evidence.logEnd, evidence.samples)
            } ?: StepJudgement(
                verdict = JudgeVerdict.INCONCLUSIVE,
                judgedAt = env.wallClock(),
                error = "The judge did not answer within ${JUDGE_TIMEOUT_MS / 1_000} s.",
            )
        }
        judgement.error?.let { attemptNotes += "The judge could not finish: $it" }
        return JudgementOutcome(judgement, env.nanoTime() - started)
    }

    private suspend fun settleEvaluation(
        step: TestStep,
        claim: LaneStepStatus,
        driver: DriverReport?,
        submittedObservation: String,
        actionEvidence: ActionEvidence,
        judgement: StepJudgement?,
    ): Evaluation {
        val results = applyJudgementToChecks(actionEvidence.checks, judgement)
        val failed = results.filter { it.status == CheckStatus.FAIL || it.status == CheckStatus.ERROR }
        val base = deterministicBaseStatus(claim, failed.isNotEmpty())
        val settled = settleWithJudge(
            base,
            judgement,
            requiresVerdict = hasJudgeChecks(step),
            inconclusivePolicy = env.judgeInconclusivePolicy,
        )
        if (judgement != null && base == StepStatus.PASS && settled.status == StepStatus.FAIL) {
            attemptNotes += "The judge failed a step the agent reported as passed."
        }
        val unresolved = hasJudgeChecks(step) && (judgement == null || judgement.verdict == JudgeVerdict.INCONCLUSIVE)
        val verificationNote = when {
            judgement?.verdict == JudgeVerdict.PASS -> "Independent judge confirmed the expected result."
            judgement?.verdict == JudgeVerdict.INCONCLUSIVE && settled.status == StepStatus.PASS ->
                "Independent judgment was inconclusive; the agent's pass was retained by policy. Verification remains unresolved."
            judgement?.verdict == JudgeVerdict.INCONCLUSIVE && settled.status == StepStatus.BLOCKED ->
                "Independent judgment was inconclusive; the run policy blocks this pass. Verification remains unresolved."
            judgement == null && settled.status == StepStatus.PASS ->
                "Passed by the agent and deterministic checks; no independent judge was requested."
            else -> null
        }
        val result = buildResult(
            step, settled.status, claimText(claim, driver), submittedObservation, results, actionEvidence.shot.path, actionEvidence.logEnd, judgement,
            settled.inconclusive, driver, startScreenshotPath = attemptStartShot?.path,
            sampleScreenshotPaths = actionEvidence.samples.mapNotNull { it.path },
        )
            .let { result -> result.copy(note = listOfNotNull(result.note, verificationNote).joinToString(" ").ifBlank { null }) }
            .let {
                if (!unresolved) it else it.copy(
                    note = listOfNotNull(
                        it.note,
                        if (settled.status == StepStatus.BLOCKED) "Explicit judge checks remain unresolved; the step is blocked."
                        else "Explicit judge checks remain unresolved; deterministic failures still determine the step result.",
                    ).joinToString(" "),
                )
            }
        return Evaluation(step, settled.status, result, failed.map { it.checkId }, index, attempt)
    }

    /** Execution evidence/check work shares the step allowance; the independent judge duration is recorded separately. */
    private fun appendStepTiming(step: TestStep, evidenceNanos: Long, checksNanos: Long, judgeNanos: Long?) {
        val transcript = env.transcript ?: return
        val evidenceMs = evidenceNanos / NANOS_PER_MILLI
        val checksMs = checksNanos / NANOS_PER_MILLI
        val judgeMs = judgeNanos?.let { it / NANOS_PER_MILLI }
        transcript.append(
            "finish_step_timing",
            mapOf(
                "step" to step.id,
                "stepNumber" to index + 1,
                "attempt" to attempt,
                "intent" to step.intent.wire,
                "evidenceCaptureMs" to evidenceMs,
                "deterministicChecksMs" to checksMs,
                "judgeMs" to judgeMs,
                "totalMs" to evidenceMs + checksMs + (judgeMs ?: 0L),
                "startCapturedAtMs" to attemptStartShot?.capturedAtMs,
                "endCapturedAtMs" to lastAttemptShot?.capturedAtMs,
                "playbackEvidenceRequired" to playbackRelated(step),
                "playbackSampleCount" to attemptPlaybackSamples.size,
                "playbackSamples" to attemptPlaybackSamples.mapIndexed { sampleIndex, shot ->
                    mapOf("moment" to "sample_${sampleIndex + 1}", "capturedAtMs" to shot.capturedAtMs, "screenshotPath" to shot.path)
                },
            ),
        )
    }

    /**
     * A manual or script step is only judged for the judge checks it is evaluated with: a tester's "failed" or a script's
     * verdict needs no second opinion.
     */
    private fun judgeable(step: TestStep, driver: DriverReport?): Boolean =
        driver == null || (driver.checks ?: step.checks).any { it is StepCheck.ScreenJudge || it is StepCheck.AskJudge }

    /** What the agent said about the step; nobody said anything about a step the run performed. */
    private fun claimText(claim: LaneStepStatus, driver: DriverReport?): String? = claim.name.lowercase().takeIf { driver == null }

    private fun deterministicBaseStatus(claim: LaneStepStatus, hasFailures: Boolean): StepStatus = when {
        claim == LaneStepStatus.PASS && !hasFailures -> StepStatus.PASS
        hasFailures -> StepStatus.FAIL
        claim == LaneStepStatus.BLOCKED -> StepStatus.BLOCKED
        else -> StepStatus.FAIL
    }

    /** Asks the judge about this attempt when the mode says so. The judge is blind: it gets [JudgeEvidence], which has no agent words. */
    @Suppress("TooGenericExceptionCaught", "LongParameterList") // A judge must never decide the step by throwing; whatever it throws is an inconclusive answer.
    private suspend fun judgeAttempt(
        step: TestStep,
        claim: LaneStepStatus,
        automatic: List<CheckResult>,
        automaticFailed: Boolean,
        shot: Shot,
        logEnd: Long?,
        samples: List<Shot>,
    ): StepJudgement? {
        val judge = env.judge ?: return null
        if (!shouldJudge(env.judgeMode, step, claim, automaticFailed)) return null
        val site = JudgeSite(spec.caseId, spec.evidencePrefix, step.id, index + 1, attempt)
        val judgement = try {
            val evidence = judgeEvidence(step, automatic, shot, logEnd, samples)
            if (judge is UsageReportingStepJudge) {
                judge.judge(site, evidence, env.listener::judgeUsage)
            } else {
                judge.judge(site, evidence).also { judgement -> judgement.usage?.let(env.listener::judgeUsage) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            StepJudgement(
                verdict = JudgeVerdict.INCONCLUSIVE, judgedAt = env.wallClock(),
                error = "The judge failed: ${failure.message ?: failure::class.simpleName}",
            )
        }
        return judgement
    }

    private fun judgeEvidence(step: TestStep, automatic: List<CheckResult>, shot: Shot, logEnd: Long?, samples: List<Shot>): JudgeEvidence {
        val start = attemptLogOffset
        val logFile = env.session.readLogFile
        val startShot = attemptStartShot
        val lane = JudgeLaneEvidence(
            label = JUDGE_LANE_LABEL,
            laneId = "",
            deterministic = deterministicOnly(automatic),
            screenshot = { shot.jpeg?.let { JudgeImage(it, SCREEN_MIME_TYPE) } },
            log = { offset, limit -> withContext(Dispatchers.IO) { readStepLogSlice(logFile, start, logEnd, offset, limit) } },
            logBytes = logEnd?.minus(start),
            startScreenshot = { startShot?.jpeg?.let { JudgeImage(it, SCREEN_MIME_TYPE) } },
            startScreenshotCapturedAtMs = startShot?.capturedAtMs,
            screenshotCapturedAtMs = shot.capturedAtMs,
            samples = samples.mapIndexed { index, sample ->
                JudgeScreenshotSample(index + 1, sample.capturedAtMs) { sample.jpeg?.let { JudgeImage(it, SCREEN_MIME_TYPE) } }
            },
        )
        return JudgeEvidence(
            spec.caseName, index + 1, spec.steps.size, step.action, step.expected, judgeChecksOf(step), step.examples, listOf(lane),
            step.intent, step.precondition,
        )
    }

    @Suppress("LongParameterList") // Preserve each independent source needed to build a saved step result without a mutable builder.
    private suspend fun buildResult(
        step: TestStep,
        status: StepStatus,
        claim: String?,
        observation: String,
        results: List<CheckResult>,
        screenshotPath: String?,
        knownLogEnd: Long? = null,
        judgement: StepJudgement? = null,
        judgeInconclusive: Boolean = false,
        driver: DriverReport? = null,
        startScreenshotPath: String? = attemptStartShot?.path,
        sampleScreenshotPaths: List<String> = emptyList(),
    ): StepResult {
        val logEnd = knownLogEnd ?: runCatching { env.session.logMarker() }.getOrNull()
        return StepResult(
            stepId = step.id,
            stepNumber = index + 1,
            action = step.action,
            expected = step.expected,
            setup = spec.setup,
            status = status,
            attempts = attempt,
            agentClaim = claim,
            observation = observation,
            checks = results,
            screenshotPath = screenshotPath,
            logStartOffset = attemptLogOffset,
            logEndOffset = logEnd,
            transcriptStartOffset = attemptTranscriptOffset,
            transcriptEndOffset = env.transcript?.sizeBytes,
            startedAt = firstAttemptWallMs,
            durationMs = env.wallClock() - firstAttemptWallMs,
            note = (attemptNotes + listOfNotNull(driver?.note)).takeIf { it.isNotEmpty() }?.joinToString(" "),
            judge = judgement,
            judgeInconclusive = judgeInconclusive,
            kind = step.kind,
            completedBy = driver?.completion,
            manualCompletion = step.manualCompletion.takeIf { step.kind == StepKind.MANUAL },
            startScreenshotPath = startScreenshotPath,
            sampleScreenshotPaths = sampleScreenshotPaths,
        )
    }

    /**
     * Takes the screen: saved as evidence when asked for (path relative to the run folder) and kept in memory for the judge
     * (which needs it even when screenshots are not kept as evidence). Never fails the step.
     */
    @Suppress("TooGenericExceptionCaught") // Evidence is best effort: whatever adb throws must not decide a step's outcome.
    private suspend fun captureScreenshot(suffix: String? = null): Shot {
        val keep = env.evidence.screenshots
        if (!keep && env.judge == null) return Shot(null, null, env.wallClock())
        return try {
            val shot = env.session.screenshot()
            val path = if (keep) saveScreenshot(shot.png, suffix) else null
            Shot(path, shot.image.bytes.takeIf { env.judge != null }, env.wallClock()).also { lastAttemptShot = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            attemptNotes += "Screenshot failed: ${failure.message ?: failure::class.simpleName}."
            Shot(null, null, env.wallClock())
        }
    }

    private fun saveScreenshot(png: ByteArray, suffix: String? = null, stepIndex: Int = index, attemptNumber: Int = attempt): String {
        val tagged = suffix?.let { "-$it" }.orEmpty()
        val file = File(File(env.laneDir, SCREENS_DIR), "${spec.evidencePrefix}-s${stepIndex + 1}-a$attemptNumber$tagged.$SCREENSHOT_EXTENSION")
        file.parentFile?.mkdirs()
        file.writeBytes(png)
        return file.relativeTo(env.runDir).invariantSeparatorsPath
    }

    // ── Deciding what comes next ─────────────────────────────────────

    private suspend fun retryLocked(evaluation: Evaluation): Flow {
        if (!isCurrentLocked(evaluation)) return Flow.End
        previousAttemptFailure = recordRetryEvidence(evaluation, "automatic")
        attemptNotes += "Attempt $attempt ${evaluation.status.name.lowercase()}" +
            (if (evaluation.failedChecks.isNotEmpty()) " (a check failed)" else "") + "."
        attempt++
        startAttemptLocked()
        return Flow.Redo
    }

    /** Records [evaluation] as the step's final result, then moves according to [flow]. Returns where the sequence went. */
    private suspend fun settleLocked(evaluation: Evaluation, flow: Flow, issue: Boolean, afterRecord: (() -> Unit)? = null): Flow {
        if (!isCurrentLocked(evaluation)) return Flow.End
        val result = evaluation.result.copy(issueRequested = issue)
        recorded += result
        // A skipped step did nothing to the device: the next step starts from the screen this one started from.
        if (result.status == StepStatus.SKIPPED) lastAttemptShot = lastAttemptShot ?: attemptStartShot
        env.listener.stepRecorded(result)
        afterRecord?.invoke()
        return when (flow) {
            Flow.End -> flow.also { endLocked() }
            is Flow.Next -> {
                beginLocked(flow.index)
                if (ended) Flow.End else flow
            }
            Flow.Redo -> flow
        }
    }

    private suspend fun pauseFlow(evaluation: Evaluation, onPauseEntered: (() -> Unit)? = null): Flow {
        val current = mutex.withLock {
            if (!isCurrentLocked(evaluation)) {
                false
            } else {
                awaitingUser = true
                onPauseEntered?.invoke()
                true
            }
        }
        if (!current) return Flow.End
        val decision = try {
            env.listener.awaitUser(
                PausedStep(spec.caseName, index + 1, evaluation.step.action, evaluation.status, evaluation.result.observation, evaluation.failedChecks),
            )
        } finally {
            awaitingUser = false
        }
        return mutex.withLock {
            if (!isCurrentLocked(evaluation)) return@withLock Flow.End
            when (decision) {
                PauseDecision.RETRY -> {
                    previousAttemptFailure = recordRetryEvidence(evaluation, "user_requested")
                    attemptNotes += "Attempt $attempt ${evaluation.status.name.lowercase()}; the user asked for another attempt."
                    attempt++
                    startAttemptLocked()
                    Flow.Redo
                }
                PauseDecision.CONTINUE -> settleLocked(evaluation, Flow.Next(index + 1), issue = false)
                PauseDecision.STOP -> settleLocked(evaluation, Flow.End, issue = false)
            }
        }
    }

    /** Keeps each failed attempt in the saved lane transcript, with bounded references instead of nested StepResult copies. */
    private fun recordRetryEvidence(evaluation: Evaluation, retryReason: String): Map<String, Any?> {
        val result = evaluation.result
        val failures = result.checks.filter { it.status == CheckStatus.FAIL || it.status == CheckStatus.ERROR }
        val entry = mapOf(
            "step" to evaluation.step.id,
            "stepNumber" to evaluation.stepIndex + 1,
            "attempt" to evaluation.attemptNumber,
            "retryReason" to retryReason,
            "status" to result.status.name.lowercase(),
            "agentClaim" to result.agentClaim,
            "observation" to result.observation.take(MAX_OBSERVATION_CHARS),
            "failedChecks" to failures.map { mapOf("checkId" to it.checkId, "kind" to it.kind, "detail" to it.detail.take(MAX_OBSERVATION_CHARS)) },
            "judge" to result.judge?.let {
                mapOf(
                    "verdict" to it.verdict.name.lowercase(),
                    "reasoning" to it.reasoning.take(MAX_OBSERVATION_CHARS),
                    "error" to it.error,
                )
            },
            "screenshotPath" to result.screenshotPath,
            "startScreenshotPath" to result.startScreenshotPath,
            "sampleScreenshotPaths" to result.sampleScreenshotPaths,
            "logStartOffset" to result.logStartOffset,
            "logEndOffset" to result.logEndOffset,
            "transcriptStartOffset" to result.transcriptStartOffset,
            "transcriptEndOffset" to result.transcriptEndOffset,
            "durationMs" to elapsedMs(),
            "capturedAt" to env.wallClock(),
        )
        env.transcript?.append("step_attempt", entry)
        return entry
    }

    // ── Watchdog ─────────────────────────────────────────────────────

    /** Polls the step timer until cancelled; closes a step that outlives its timeout. */
    suspend fun watchdog(pollMs: Long) {
        while (!ended) {
            delay(pollMs)
            checkTimeout()
        }
    }

    /** Closes the current step as TIMEOUT when it is due and nothing else is working on it. True when it did. */
    @Suppress("ReturnCount") // Timeout admission has distinct stale, paused, and in-budget exits under the sequence lock.
    suspend fun checkTimeout(): Boolean {
        if (!active || ended || awaitingUser) return false
        if (spec.caseBudget?.exhausted() == true && finishing.compareAndSet(false, true)) {
            try {
                abort(StepStatus.ERROR, CASE_BUDGET_EXHAUSTED_NOTE)
                return true
            } finally {
                finishing.set(false)
            }
        }
        val expectedIndex = index
        val expectedAttempt = attempt
        val step = spec.steps.getOrNull(expectedIndex) ?: return false
        if (step.isDriverStep || elapsedMs() < step.timeoutMs) return false
        if (!finishing.compareAndSet(false, true)) return false
        try {
            val evaluation = mutex.withLock {
                timeoutEvaluationLocked(step.id, expectedIndex, expectedAttempt, verifyDeadline = true)
            } ?: return false
            settleTimeout(evaluation, cancelAgentBeforePause = true)
            return true
        } finally {
            finishing.set(false)
        }
    }

    private suspend fun timeoutEvaluationLocked(
        expectedStepId: String,
        expectedIndex: Int,
        expectedAttempt: Int,
        verifyDeadline: Boolean,
        submittedClaim: String? = null,
        submittedObservation: String? = null,
    ): Evaluation? {
        if (!active || ended || index != expectedIndex || attempt != expectedAttempt) return null
        val step = spec.steps.getOrNull(index) ?: return null
        if (step.id != expectedStepId || (verifyDeadline && elapsedMs() < step.timeoutMs)) return null
        val hasCurrentSubmittedReport = submittedReportStepId == expectedStepId && submittedReportAttempt == expectedAttempt
        val screenshotPath = lastAttemptShot?.path
        val explicitObservation = submittedObservation?.let { (observations + it).filter(String::isNotBlank).joinToString("\n") }
        val reportObservation = explicitObservation ?: submittedReportObservation.takeIf { hasCurrentSubmittedReport }
        val reported = (reportObservation ?: observations.joinToString("\n")).ifBlank { TIMEOUT_OBSERVATION }
        val reportClaim = submittedClaim ?: submittedReportClaim.takeIf { hasCurrentSubmittedReport }
        val completedIds = lastAttemptChecks.mapTo(HashSet()) { it.checkId }
        val pending = step.checks.filterNot { it.id in completedIds }.map { check ->
            CheckResult(check.id, check.kindName(), CheckStatus.NOT_EVALUATED, "Evaluation stopped when the step deadline expired.", 0)
        }
        val result = buildResult(
            step, StepStatus.TIMEOUT, reportClaim, reported, lastAttemptChecks + pending, screenshotPath,
            knownLogEnd = lastKnownLogEnd, startScreenshotPath = attemptStartShot?.path,
            sampleScreenshotPaths = attemptPlaybackSamples.mapNotNull { it.path },
        )
            .copy(note = (attemptNotes + "The step timed out after ${step.timeoutMs} ms.").joinToString(" "))
        return Evaluation(step, StepStatus.TIMEOUT, result, emptyList(), expectedIndex, expectedAttempt)
    }

    /** Finishes the timeout outcome before notifying the lane driver that its current agent is stale. */
    private suspend fun settleTimeout(evaluation: Evaluation, cancelAgentBeforePause: Boolean): Flow {
        val step = evaluation.step
        if (step.onFailure == OnFailure.PAUSE_FOR_USER) {
            val flow = pauseFlow(evaluation) {
                // Invalidate the current lane agent in the same critical section that accepts
                // the timeout. It may otherwise dispatch against the next step before the
                // driver receives TimedOut and starts a replacement segment.
                epoch.incrementAndGet()
                events.trySend(SequenceEvent.TimedOut(deferAgentCancel = !cancelAgentBeforePause))
            }
            when (flow) {
                is Flow.Next -> events.trySend(SequenceEvent.Moved(flow.index))
                Flow.Redo -> events.trySend(SequenceEvent.Moved(index))
                Flow.End -> Unit
            }
            return flow
        }
        return withContext(NonCancellable) {
            mutex.withLock {
                if (!isCurrentLocked(evaluation)) return@withLock Flow.End
                val requested = when (step.onFailure) {
                    OnFailure.PAUSE_FOR_USER -> error("Pause policy is handled above")
                    OnFailure.STOP_CASE -> Flow.End
                    OnFailure.CONTINUE, OnFailure.CREATE_ISSUE_AND_CONTINUE -> Flow.Next(index + 1)
                }
                // This transition happens before LaneDriver can close the old segment. Make its
                // gateway stale before beginLocked exposes the following step.
                epoch.incrementAndGet()
                val settled = settleLocked(
                    evaluation,
                    requested,
                    issue = step.onFailure == OnFailure.CREATE_ISSUE_AND_CONTINUE,
                    afterRecord = { events.trySend(SequenceEvent.TimedOut()) },
                )
                when (settled) {
                    is Flow.Next -> events.trySend(SequenceEvent.Moved(settled.index))
                    Flow.Redo -> events.trySend(SequenceEvent.Moved(index))
                    Flow.End -> Unit
                }
                settled
            }
        }
    }

    // ── Aborting ─────────────────────────────────────────────────────

    /** Closes the current step with [status] (the agent could not be kept alive) and ends the sequence. */
    suspend fun abort(status: StepStatus, note: String) = mutex.withLock { abortLocked(status, note) }

    private suspend fun abortLocked(status: StepStatus, note: String) {
        if (ended) return
        val step = spec.steps.getOrNull(index)
        if (active && step != null) {
            val hasCurrentSubmittedReport = submittedReportStepId == step.id && submittedReportAttempt == attempt
            val reportObservation = submittedReportObservation.takeIf { hasCurrentSubmittedReport }
                ?: observations.joinToString("\n")
            val result = buildResult(
                step, status, submittedReportClaim.takeIf { hasCurrentSubmittedReport }, reportObservation, lastAttemptChecks,
                lastAttemptShot?.path, knownLogEnd = lastKnownLogEnd, startScreenshotPath = attemptStartShot?.path,
                sampleScreenshotPaths = attemptPlaybackSamples.mapNotNull { it.path },
            ).copy(note = note)
            recorded += result
            env.listener.stepRecorded(result)
        }
        endLocked()
    }

    private fun isCurrentLocked(evaluation: Evaluation): Boolean =
        !ended && active && index == evaluation.stepIndex && attempt == evaluation.attemptNumber &&
            spec.steps.getOrNull(index)?.id == evaluation.step.id

    private companion object {
        const val FINISH_STEP_TOOL = "finish_step"
        const val SCREENS_DIR = "screens"
        const val JUDGE_LANE_LABEL = "Lane"
        const val SCREEN_MIME_TYPE = "image/jpeg"
        const val MAX_OBSERVATION_CHARS = 2_000
        const val CASE_BUDGET_EXHAUSTED_MESSAGE = "The case's paid tool-call limit is exhausted; no further device or script tools may run."
    }
}
