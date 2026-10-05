package com.indagium.testing.run

import com.indagium.debug.IndagiumToolGateway
import com.indagium.testing.device.TestDeviceSession
import com.indagium.testing.model.CheckResult
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.EvidenceFlags
import com.indagium.testing.model.OnFailure
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestStep
import com.indagium.testing.script.ScriptRunContext
import com.indagium.testing.script.TestScriptRunner
import com.indagium.testing.store.TranscriptWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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

internal sealed interface SequenceEvent {
    /** The sequence is over: every step ran, a step stopped the case, or the sequence was aborted. */
    data object Ended : SequenceEvent

    /** The current step outlived its timeout. A running agent is stale from now on (the sequence may still wait for the user). */
    data object TimedOut : SequenceEvent

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
)

/** What every sequence of a lane shares. [nanoTime] and [wallClock] are test seams. */
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
)

private class Evaluation(val step: TestStep, val status: StepStatus, val result: StepResult, val failedChecks: List<String>)

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

    /** Ended / Moved notifications for whoever drives this sequence. Unbounded: a sender never waits. */
    val events = Channel<SequenceEvent>(Channel.UNLIMITED)

    @Volatile private var index = -1

    @Volatile private var attempt = 1

    @Volatile private var attemptStartNanos = 0L

    @Volatile private var attemptLogOffset = 0L

    @Volatile private var attemptTranscriptOffset = 0L

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
        return LaneStepBrief(step.id, spec.caseName, index + 1, spec.steps.size, step.action, step.expected, attempt)
    }

    // ── Gateway guard ────────────────────────────────────────────────

    /** A new agent run is about to take over: tools of any earlier run are refused from now on. */
    fun newEpoch(): Long = epoch.incrementAndGet()

    /** The lane tools behind the sequence's guard: the per-step call cap and, for an agent run, its epoch. */
    fun gateway(runEpoch: Long?): IndagiumToolGateway = cappedGateway(tools.gateway) { name -> toolRejection(name, runEpoch) }

    private fun toolRejection(tool: String, runEpoch: Long?): String? {
        if (runEpoch != null && runEpoch != epoch.get()) return REPLACED_RUN_MESSAGE
        if (ended) return overMessage()
        if (tool == FINISH_STEP_TOOL) return null
        val step = spec.steps.getOrNull(index) ?: return null
        if (tool in LANE_PROTOCOL_TOOL_NAMES) return if (toolCalls.get() >= step.maxToolCalls) STEP_BUDGET_EXHAUSTED_MESSAGE else null
        return if (toolCalls.incrementAndGet() > step.maxToolCalls) STEP_BUDGET_EXHAUSTED_MESSAGE else null
    }

    // ── Starting steps ───────────────────────────────────────────────

    /** Starts step [stepIndex] (attempt 1); an index past the last step ends the sequence. */
    suspend fun begin(stepIndex: Int) = mutex.withLock { beginLocked(stepIndex) }

    /** Gives the current step a fresh timeout; used when the agent behind it was restarted. */
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
        attemptTranscriptOffset = env.transcript?.sizeBytes ?: 0L
        attemptStartNanos = env.nanoTime()
        toolCalls.set(0)
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

    override suspend fun finishStep(status: LaneStepStatus, observation: String): Map<String, Any?> {
        if (awaitingUser) return mapOf("error" to STEP_PAUSED_MESSAGE)
        if (!finishing.compareAndSet(false, true)) return mapOf("error" to "finish_step is already running; wait for its answer.")
        try {
            val evaluation = mutex.withLock { evaluateLocked(status, observation) } ?: return mapOf("error" to "No step is active.")
            return decide(evaluation)
        } finally {
            finishing.set(false)
        }
    }

    private suspend fun decide(evaluation: Evaluation): Map<String, Any?> {
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
            "attempt" to brief.attempt,
        )
    }

    // ── Evaluating a step ────────────────────────────────────────────

    private suspend fun evaluateLocked(claim: LaneStepStatus, observation: String): Evaluation? {
        if (!active || ended) return null
        val step = spec.steps[index]
        val screenshotPath = captureScreenshot()
        val results = checks.evaluateAll(step.checks, attemptLogOffset)
        val failed = results.filter { it.status == CheckStatus.FAIL || it.status == CheckStatus.ERROR }
        val status = when {
            claim == LaneStepStatus.PASS && failed.isEmpty() -> StepStatus.PASS
            claim == LaneStepStatus.BLOCKED -> StepStatus.BLOCKED
            else -> StepStatus.FAIL
        }
        val reported = (observations + observation).filter(String::isNotBlank).joinToString("\n")
        val result = buildResult(step, status, claim.name.lowercase(), reported, results, screenshotPath)
        return Evaluation(step, status, result, failed.map { it.checkId })
    }

    private suspend fun buildResult(
        step: TestStep,
        status: StepStatus,
        claim: String?,
        observation: String,
        results: List<CheckResult>,
        screenshotPath: String?,
    ): StepResult {
        val logEnd = runCatching { env.session.logMarker() }.getOrNull()
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
        )
    }

    /** Saves the screen as evidence (when asked for) and returns its path relative to the run folder; never fails the step. */
    @Suppress("TooGenericExceptionCaught") // Evidence is best effort: whatever adb throws must not decide a step's outcome.
    private suspend fun captureScreenshot(): String? {
        if (!env.evidence.screenshots) return null
        return try {
            val shot = env.session.screenshot()
            val file = File(File(env.laneDir, SCREENS_DIR), "${spec.evidencePrefix}-s${index + 1}-a$attempt.$SCREENSHOT_EXTENSION")
            file.parentFile?.mkdirs()
            file.writeBytes(shot.png)
            file.relativeTo(env.runDir).invariantSeparatorsPath
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            attemptNotes += "Screenshot failed: ${failure.message ?: failure::class.simpleName}."
            null
        }
    }

    // ── Deciding what comes next ─────────────────────────────────────

    private suspend fun retryLocked(evaluation: Evaluation): Flow {
        attemptNotes += "Attempt $attempt ${evaluation.status.name.lowercase()}" +
            (if (evaluation.failedChecks.isNotEmpty()) " (a check failed)" else "") + "."
        attempt++
        startAttemptLocked()
        return Flow.Redo
    }

    /** Records [evaluation] as the step's final result, then moves according to [flow]. Returns where the sequence went. */
    private suspend fun settleLocked(evaluation: Evaluation, flow: Flow, issue: Boolean): Flow {
        val result = evaluation.result.copy(issueRequested = issue)
        recorded += result
        env.listener.stepRecorded(result)
        return when (flow) {
            Flow.End -> flow.also { endLocked() }
            is Flow.Next -> {
                beginLocked(flow.index)
                if (ended) Flow.End else flow
            }
            Flow.Redo -> flow
        }
    }

    private suspend fun pauseFlow(evaluation: Evaluation): Flow {
        awaitingUser = true
        val decision = try {
            env.listener.awaitUser(
                PausedStep(spec.caseName, index + 1, evaluation.step.action, evaluation.status, evaluation.result.observation, evaluation.failedChecks),
            )
        } finally {
            awaitingUser = false
        }
        return mutex.withLock {
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
    suspend fun checkTimeout(): Boolean {
        if (!active || ended || awaitingUser) return false
        val step = spec.steps.getOrNull(index) ?: return false
        if (elapsedMs() < step.timeoutMs) return false
        if (!finishing.compareAndSet(false, true)) return false
        try {
            val evaluation = mutex.withLock { timeoutEvaluationLocked(step) } ?: return false
            events.trySend(SequenceEvent.TimedOut)
            val flow = when (step.onFailure) {
                OnFailure.PAUSE_FOR_USER -> pauseFlow(evaluation)
                OnFailure.STOP_CASE -> mutex.withLock { settleLocked(evaluation, Flow.End, issue = false) }
                OnFailure.CONTINUE -> mutex.withLock { settleLocked(evaluation, Flow.Next(index + 1), issue = false) }
                OnFailure.CREATE_ISSUE_AND_CONTINUE -> mutex.withLock { settleLocked(evaluation, Flow.Next(index + 1), issue = true) }
            }
            when (flow) {
                is Flow.Next -> events.trySend(SequenceEvent.Moved(flow.index))
                Flow.Redo -> events.trySend(SequenceEvent.Moved(index))
                Flow.End -> Unit // endLocked already announced it
            }
            return true
        } finally {
            finishing.set(false)
        }
    }

    private suspend fun timeoutEvaluationLocked(step: TestStep): Evaluation? {
        if (!active || ended || elapsedMs() < step.timeoutMs) return null
        val screenshotPath = captureScreenshot()
        val reported = observations.joinToString("\n").ifBlank { TIMEOUT_OBSERVATION }
        val result = buildResult(step, StepStatus.TIMEOUT, null, reported, emptyList(), screenshotPath)
            .copy(note = (attemptNotes + "The step timed out after ${step.timeoutMs} ms.").joinToString(" "))
        return Evaluation(step, StepStatus.TIMEOUT, result, emptyList())
    }

    // ── Aborting ─────────────────────────────────────────────────────

    /** Closes the current step with [status] (the agent could not be kept alive) and ends the sequence. */
    suspend fun abort(status: StepStatus, note: String) = mutex.withLock {
        val step = spec.steps.getOrNull(index)
        if (active && step != null) {
            val result = buildResult(step, status, null, observations.joinToString("\n"), emptyList(), null).copy(note = note)
            recorded += result
            env.listener.stepRecorded(result)
        }
        endLocked()
    }

    private companion object {
        const val FINISH_STEP_TOOL = "finish_step"
        const val SCREENS_DIR = "screens"
        const val MAX_OBSERVATION_CHARS = 2_000
    }
}
