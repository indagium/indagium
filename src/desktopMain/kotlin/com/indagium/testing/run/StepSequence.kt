package com.indagium.testing.run

import com.indagium.debug.IndagiumToolGateway
import com.indagium.testing.device.TestDeviceSession
import com.indagium.testing.model.CheckResult
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.EvidenceFlags
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.LaneToolCall
import com.indagium.testing.model.LaneToolCallStatus
import com.indagium.testing.model.OnFailure
import com.indagium.testing.model.StepJudgement
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestStep
import com.indagium.testing.script.ScriptRunContext
import com.indagium.testing.script.TestScriptRunner
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
// All state changes happen under [mutex]; `finishing` makes finish_step and the watchdog mutually exclusive, so the
// timer never fires while a step is being finished and a second finish_step cannot start in parallel.

private const val NANOS_PER_MILLI = 1_000_000L
private const val SCREENSHOT_EXTENSION = "png"
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

    suspend fun awaitUser(paused: PausedStep): PauseDecision
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
    val pauseGate: RunPauseGate? = null,
    val suiteId: String = "",
    val goldenImage: (suiteId: String, assetPath: String) -> ByteArray? = { _, _ -> null },
    val goldenFile: (suiteId: String, assetPath: String) -> File? = { _, _ -> null },
    val externalDispatchAdmitted: (Job?, String?, Int) -> Unit = { _, _, _ -> },
)

/** What a step's screenshot became: the file kept as evidence (relative to the run folder) and the image the judge looks at. */
private class Shot(val path: String?, val jpeg: ByteArray?)

private class Evaluation(
    val step: TestStep,
    val status: StepStatus,
    val result: StepResult,
    val failedChecks: List<String>,
    val stepIndex: Int,
    val attemptNumber: Int,
)

private data class ToolAdmission(val refusal: String?, val paid: Boolean, val activity: LaneToolCall?)

private sealed interface Flow {
    data object End : Flow

    data class Next(val index: Int) : Flow

    data object Redo : Flow
}

internal class StepSequence(private val env: SequenceEnv, val spec: SequenceSpec) : LaneCallbacks {
    private val mutex = Mutex()
    private val finishing = AtomicBoolean(false)
    private val epoch = AtomicLong(0)
    private val toolCalls = AtomicInteger(0)
    private val recorded = CopyOnWriteArrayList<StepResult>()
    private val observations = CopyOnWriteArrayList<String>()
    private val attemptNotes = ArrayList<String>()
    private val checks = DeterministicChecks(env.session, env.scriptRunner, { id -> env.scripts.firstOrNull { it.id == id } }, ::scriptContext)

    /** Paid gateway handlers admitted for the current step and still executing. Guarded by [mutex]. */
    private var activePaidDispatches = 0

    @Volatile private var lastAttemptShot: Shot? = null

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

    private fun scriptContext() = ScriptRunContext(
        deviceSerial = env.session.serial,
        packageName = env.packageName,
        runDir = env.laneDir,
        caseId = spec.caseId,
        stepId = spec.steps.getOrNull(index)?.id.orEmpty(),
    )

    fun brief(): LaneStepBrief? {
        if (!active) return null
        val step = spec.steps.getOrNull(index) ?: return null
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
        )
    }

    // ── Gateway guard ────────────────────────────────────────────────

    /** A new agent run is about to take over: tools of any earlier run are refused from now on. */
    fun newEpoch(): Long = epoch.incrementAndGet()

    suspend fun currentAttemptIdentity(): Pair<String, Int>? = mutex.withLock {
        if (!active || ended) null else spec.steps.getOrNull(index)?.id?.let { it to attempt }
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

    private suspend fun admitToolLocked(tool: String, arguments: Map<String, Any?>, runEpoch: Long?): ToolAdmission {
        var paidDispatch = false
        var activity: LaneToolCall? = null
        val argumentsPreview = laneToolPreview(arguments)
        val refusal = when {
            runEpoch != null && runEpoch != epoch.get() -> REPLACED_RUN_MESSAGE
            ended -> overMessage()
            tool == FINISH_STEP_TOOL -> null
            else -> {
                val step = spec.steps.getOrNull(index)
                when {
                    step == null -> null
                    tool in LANE_PROTOCOL_TOOL_NAMES ->
                        if (toolCalls.get() >= step.maxToolCalls) STEP_BUDGET_EXHAUSTED_MESSAGE else null
                    finishing.get() || awaitingUser -> "The step is finishing or paused; wait for the next step before using device or script tools."
                    toolCalls.get() >= step.maxToolCalls -> STEP_BUDGET_EXHAUSTED_MESSAGE
                    else -> {
                        toolCalls.incrementAndGet()
                        if (spec.caseBudget?.tryDispatch() == false) {
                            toolCalls.decrementAndGet()
                            withContext(NonCancellable) { abortLocked(StepStatus.ERROR, CASE_BUDGET_EXHAUSTED_NOTE) }
                            CASE_BUDGET_EXHAUSTED_MESSAGE
                        } else {
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
        return ToolAdmission(refusal, paidDispatch, activity)
    }

    @Suppress("TooGenericExceptionCaught") // Admitted gateway tools can throw provider/host-specific failures; record them and rethrow.
    private suspend fun executeAdmittedTool(
        admission: ToolAdmission,
        initial: LaneToolCall?,
        execute: suspend () -> Any?,
    ): Any? {
        val startNanos = env.nanoTime()
        var completed = initial
        return try {
            execute().also { result ->
                val status = if (laneToolResultError(result) == null) LaneToolCallStatus.SUCCEEDED else LaneToolCallStatus.FAILED
                completed = initial?.completed(status, laneToolPreview(result), elapsedMs(startNanos))
            }
        } catch (cancelled: CancellationException) {
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
        startAttemptLocked()
    }

    private suspend fun startAttemptLocked() {
        attemptLogOffset = runCatching { env.session.logMarker() }.getOrDefault(attemptLogOffset)
        lastKnownLogEnd = attemptLogOffset
        attemptTranscriptOffset = env.transcript?.sizeBytes ?: 0L
        attemptStartNanos = env.nanoTime()
        toolCalls.set(0)
        lastAttemptShot = null
        lastAttemptChecks = emptyList()
        observations.clear()
        active = true
        env.listener.stepStarted(index + 1, spec.steps[index].action, attempt)
    }

    private fun endLocked() {
        active = false
        ended = true
        events.trySend(SequenceEvent.Ended)
    }

    private fun elapsedMs(): Long = (env.nanoTime() - attemptStartNanos) / NANOS_PER_MILLI

    // ── LaneCallbacks ────────────────────────────────────────────────

    override fun reportObservation(text: String) {
        if (active) observations += text.take(MAX_OBSERVATION_CHARS)
    }

    @Suppress("ReturnCount") // Distinct protocol guards refuse without settling a stale step.
    override suspend fun finishStep(status: LaneStepStatus, observation: String): Map<String, Any?> {
        if (ended) return mapOf("error" to overMessage())
        if (awaitingUser) return mapOf("error" to STEP_PAUSED_MESSAGE)
        if (!finishing.compareAndSet(false, true)) return mapOf("error" to "finish_step is already running; wait for its answer.")
        try {
            if (mutex.withLock { activePaidDispatches > 0 }) {
                return mapOf("error" to "A device or script tool is still running; wait for its result before finishing the step.")
            }
            if (spec.caseBudget?.exhausted() == true) {
                withContext(NonCancellable) { abort(StepStatus.ERROR, CASE_BUDGET_EXHAUSTED_NOTE) }
                return mapOf("result" to "case_finished", "error" to CASE_BUDGET_EXHAUSTED_NOTE, "message" to overMessage())
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
                val remaining = (step.timeoutMs - elapsedMs()).coerceAtLeast(0L)
                val completed = withTimeoutOrNull(remaining) {
                    if (status == LaneStepStatus.SKIPPED) skippedEvaluationLocked(step, observation) else evaluateLocked(status, observation)
                }
                if (completed == null) timedOut = true
                completed ?: timeoutEvaluationLocked(step.id, stepIndex, attemptNumber, verifyDeadline = false)
            }
            skipRefusal?.let { return mapOf("error" to it) }
            val currentEvaluation = evaluation ?: return mapOf("error" to "No step is active.")
            val answer = when {
                timedOut -> respond(settleTimeout(currentEvaluation, cancelAgentBeforePause = false))
                status == LaneStepStatus.SKIPPED -> respond(mutex.withLock { settleLocked(currentEvaluation, Flow.Next(index + 1), issue = false) })
                else -> decide(currentEvaluation)
            }
            if (!ended) holdWhilePaused()
            return answer
        } finally {
            finishing.set(false)
        }
    }

    private suspend fun decide(evaluation: Evaluation): Map<String, Any?> {
        if (!mutex.withLock { isCurrentLocked(evaluation) }) return mapOf("error" to overMessage())
        val step = evaluation.step
        if (evaluation.status == StepStatus.PASS) return respond(mutex.withLock { settleLocked(evaluation, Flow.Next(index + 1), issue = false) })
        if (attempt < step.retries + 1) return respond(mutex.withLock { retryLocked(evaluation) })
        val flow = when (step.onFailure) {
            OnFailure.PAUSE_FOR_USER -> pauseFlow(evaluation)
            OnFailure.STOP_CASE -> mutex.withLock { settleLocked(evaluation, Flow.End, issue = false) }
            OnFailure.CONTINUE -> mutex.withLock { settleLocked(evaluation, Flow.Next(index + 1), issue = false) }
            OnFailure.CREATE_ISSUE_AND_CONTINUE -> mutex.withLock { settleLocked(evaluation, Flow.Next(index + 1), issue = true) }
        }
        return respond(flow)
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
        is Flow.Next -> mapOf("result" to "next_step", "message" to "Recorded. Continue with the next step.", "step" to stepMap())
        Flow.Redo -> mapOf(
            "result" to "redo",
            "message" to "Attempt ${attempt - 1} did not pass. Redo this step from its current screen state (attempt $attempt).",
            "step" to stepMap(),
        )
    }

    private fun stepMap(): Map<String, Any?> {
        val brief = brief() ?: return emptyMap()
        return mapOf(
            "stepNumber" to brief.stepNumber,
            "stepCount" to brief.stepCount,
            "action" to brief.action,
            "expected" to brief.expected,
            "optional" to brief.optional,
            "condition" to brief.condition,
            "attempt" to brief.attempt,
        ) + brief.exampleMetadata()
    }

    // ── Evaluating a step ────────────────────────────────────────────

    /** A condition-unmet optional action advances without checks, judging, retries or a screenshot capture. */
    private suspend fun skippedEvaluationLocked(step: TestStep, observation: String): Evaluation? {
        if (!active || ended) return null
        val logEnd = runCatching { env.session.logMarker() }.getOrNull().also { if (it != null) lastKnownLogEnd = it }
        val reported = (observations + observation).filter(String::isNotBlank).joinToString("\n")
        val result = buildResult(step, StepStatus.SKIPPED, "skipped", reported, emptyList(), null, logEnd)
        return Evaluation(step, StepStatus.SKIPPED, result, emptyList(), index, attempt)
    }

    private suspend fun evaluateLocked(claim: LaneStepStatus, observation: String): Evaluation? {
        if (!active || ended) return null
        val step = spec.steps[index]
        val shot = captureScreenshot()
        lastAttemptChecks = emptyList()
        val automatic = checks.evaluateAll(step.checks, attemptLogOffset) { result -> lastAttemptChecks = lastAttemptChecks + result }
        val automaticFailed = automatic.any { it.status == CheckStatus.FAIL || it.status == CheckStatus.ERROR }
        val logEnd = runCatching { env.session.logMarker() }.getOrNull().also { if (it != null) lastKnownLogEnd = it }
        val judgement = judgeAttempt(step, claim, automatic, automaticFailed, shot, logEnd)
        val results = applyJudgementToChecks(automatic, judgement)
        val failed = results.filter { it.status == CheckStatus.FAIL || it.status == CheckStatus.ERROR }
        val base = deterministicBaseStatus(claim, failed.isNotEmpty())
        val settled = settleWithJudge(base, judgement, requiresVerdict = hasJudgeChecks(step))
        if (judgement != null && base == StepStatus.PASS && settled.status == StepStatus.FAIL) {
            attemptNotes += "The judge failed a step the agent reported as passed."
        }
        val reported = (observations + observation).filter(String::isNotBlank).joinToString("\n")
        val unresolved = hasJudgeChecks(step) && (judgement == null || judgement.verdict == JudgeVerdict.INCONCLUSIVE)
        val result = buildResult(step, settled.status, claim.name.lowercase(), reported, results, shot.path, logEnd, judgement, settled.inconclusive)
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
    ): StepJudgement? {
        val judge = env.judge ?: return null
        if (!shouldJudge(env.judgeMode, step, claim, automaticFailed)) return null
        val site = JudgeSite(spec.caseId, spec.evidencePrefix, step.id, index + 1, attempt)
        val judgement = try {
            judge.judge(site, judgeEvidence(step, automatic, shot, logEnd))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            StepJudgement(
                verdict = JudgeVerdict.INCONCLUSIVE, judgedAt = env.wallClock(),
                error = "The judge failed: ${failure.message ?: failure::class.simpleName}",
            )
        }
        judgement.error?.let { attemptNotes += "The judge could not finish: $it" }
        return judgement
    }

    private fun judgeEvidence(step: TestStep, automatic: List<CheckResult>, shot: Shot, logEnd: Long?): JudgeEvidence {
        val start = attemptLogOffset
        val logFile = env.session.readLogFile
        val lane = JudgeLaneEvidence(
            label = JUDGE_LANE_LABEL,
            laneId = "",
            deterministic = deterministicOnly(automatic),
            screenshot = { shot.jpeg?.let { JudgeImage(it, SCREEN_MIME_TYPE) } },
            log = { offset, limit -> withContext(Dispatchers.IO) { readStepLogSlice(logFile, start, logEnd, offset, limit) } },
            logBytes = logEnd?.minus(start),
        )
        return JudgeEvidence(spec.caseName, index + 1, spec.steps.size, step.action, step.expected, judgeChecksOf(step), step.examples, listOf(lane))
    }

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
            note = attemptNotes.takeIf { it.isNotEmpty() }?.joinToString(" "),
            judge = judgement,
            judgeInconclusive = judgeInconclusive,
        )
    }

    /**
     * Takes the screen: saved as evidence when asked for (path relative to the run folder) and kept in memory for the judge
     * (which needs it even when screenshots are not kept as evidence). Never fails the step.
     */
    @Suppress("TooGenericExceptionCaught") // Evidence is best effort: whatever adb throws must not decide a step's outcome.
    private suspend fun captureScreenshot(): Shot {
        val keep = env.evidence.screenshots
        if (!keep && env.judge == null) return Shot(null, null)
        return try {
            val shot = env.session.screenshot()
            val path = if (keep) saveScreenshot(shot.png) else null
            Shot(path, shot.image.bytes.takeIf { env.judge != null }).also { lastAttemptShot = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            attemptNotes += "Screenshot failed: ${failure.message ?: failure::class.simpleName}."
            Shot(null, null)
        }
    }

    private fun saveScreenshot(png: ByteArray): String {
        val file = File(File(env.laneDir, SCREENS_DIR), "${spec.evidencePrefix}-s${index + 1}-a$attempt.$SCREENSHOT_EXTENSION")
        file.parentFile?.mkdirs()
        file.writeBytes(png)
        return file.relativeTo(env.runDir).invariantSeparatorsPath
    }

    // ── Deciding what comes next ─────────────────────────────────────

    private suspend fun retryLocked(evaluation: Evaluation): Flow {
        if (!isCurrentLocked(evaluation)) return Flow.End
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
        if (elapsedMs() < step.timeoutMs) return false
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
    ): Evaluation? {
        if (!active || ended || index != expectedIndex || attempt != expectedAttempt) return null
        val step = spec.steps.getOrNull(index) ?: return null
        if (step.id != expectedStepId || (verifyDeadline && elapsedMs() < step.timeoutMs)) return null
        val screenshotPath = lastAttemptShot?.path
        val reported = observations.joinToString("\n").ifBlank { TIMEOUT_OBSERVATION }
        val completedIds = lastAttemptChecks.mapTo(HashSet()) { it.checkId }
        val pending = step.checks.filterNot { it.id in completedIds }.map { check ->
            CheckResult(check.id, check.kindName(), CheckStatus.NOT_EVALUATED, "Evaluation stopped when the step deadline expired.", 0)
        }
        val result = buildResult(step, StepStatus.TIMEOUT, null, reported, lastAttemptChecks + pending, screenshotPath, knownLogEnd = lastKnownLogEnd)
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
            val result = buildResult(
                step, status, null, observations.joinToString("\n"), emptyList(), null, knownLogEnd = lastKnownLogEnd,
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
