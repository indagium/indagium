package com.indagium.testing.run

import com.indagium.capture.CaptureMirrorMode
import com.indagium.capture.CaptureSettings
import com.indagium.capture.withMirrorMode
import com.indagium.model.AiUsageStats
import com.indagium.model.sumAiUsage
import com.indagium.testing.device.LaneMarkerOutcome
import com.indagium.testing.device.LaneMarkerRequest
import com.indagium.testing.device.TestDeviceSession
import com.indagium.testing.model.CaseAttemptRecord
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.HookItem
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.SUITE_SETUP_CASE_ID
import com.indagium.testing.model.SUITE_TEARDOWN_CASE_ID
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.judge
import com.indagium.testing.model.judgeActive
import com.indagium.testing.store.LaneToolActivityWriter
import com.indagium.testing.store.TEST_RUN_TRANSCRIPT_FILE_NAME
import com.indagium.testing.store.TranscriptWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

// One lane of a run, start to finish: open the device, run the suite's setup hooks, then every case (per iteration):
// case setup -> the case's steps -> case teardown (always) -> ..., and the suite's teardown (always), then release the
// device. A failing setup hook BLOCKS what depends on it (the cases are not run; their steps are SKIPPED). Cancelling the
// lane coroutine still runs the teardown hooks (scripts only) and stops the recorder, on IO and without being cancelled.
// The lane never touches AppState: it only reads the frozen [snapshot] and writes results through [state].

private const val SETUP_FAILED_NOTE = "Setup failed, so the case did not run."
private const val STOPPED_NOTE = "Skipped because an earlier step stopped the case."
private const val CAPTURE_LOST_ERROR =
    "The lane's capture ended before the lane did (its tab was closed or stopped, or the device was disconnected), so the lane stopped."

/** How long a lane waits for the markers it just wrote (their note and screenshot; not the trailing log window) before it stops recording. */
private const val MARKER_WRITE_WAIT_MS = 15_000L

/** How long the trailing log windows of those markers may delay stopping the recording; the capture clips a window it cannot wait for. */
private const val MARKER_WINDOW_WAIT_MS = 15_000L

/** The most a lane waits for its recording to stop and finalize; a longer one is left to finish by itself. */
internal const val LANE_STOP_WAIT_MS = 90_000L

/** A step screenshot is attached to a marker only up to this size. */
private const val MAX_MARKER_SCREENSHOT_BYTES = 16 * 1024 * 1024

/** The recording a run without capture settings does: logcat, and the screen when video evidence is on, without any display. */
internal fun headlessCaptureSettings(video: Boolean): CaptureSettings =
    CaptureSettings(recordVideo = video, includeBufferedLogs = false).withMirrorMode(CaptureMirrorMode.DISABLED)

/** The lane's recording ended on its own. Not a [CancellationException], so it fails the lane's scope instead of just ending a child. */
private class LaneCaptureLostException : IllegalStateException(CAPTURE_LOST_ERROR)

internal class LaneRunner(
    private val runId: String,
    private val snapshot: TestRun,
    private val lane: LaneConfig,
    private val cases: List<TestCase>,
    private val lockedCases: List<Pair<TestCase, String>>,
    private val state: TestRunState,
    private val deps: EngineDeps,
    private val handle: LaneHandle,
    /** The judge of this lane, or null when the run has none. */
    private val judge: StepJudge? = null,
    private val judgeLimiter: JudgeConcurrencyLimiter = JudgeConcurrencyLimiter(),
) : SequenceListener {
    private val suite = snapshot.suite
    private val evidence = snapshot.config.evidence

    @Volatile private var recorder: CaseRecorder? = null

    @Volatile private var activityWriter: LaneToolActivityWriter? = null

    @Volatile private var transcriptWriter: TranscriptWriter? = null

    @Volatile private var cancelled = false

    @Volatile private var captureLost = false

    private val markerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val markerJobs = CopyOnWriteArrayList<Job>()

    private fun now() = deps.wallClock()

    private fun updateLane(transform: (LaneResult) -> LaneResult) = state.updateLane(lane.id, transform)

    // ── Lane lifecycle ───────────────────────────────────────────────

    suspend fun run() {
        val laneDir = deps.store.laneDir(runId, lane.id)
        handle.laneDir = laneDir
        updateLane { it.copy(status = RunStatus.RUNNING, startedAt = now()) }
        val agent = try {
            if (lane.kind == LaneKind.AGENT_PROFILE) deps.agentFor(lane) else null
        } catch (refused: IllegalStateException) {
            return failLane("The agent could not be prepared: ${refused.message}")
        } catch (refused: IllegalArgumentException) {
            return failLane("The agent could not be prepared: ${refused.message}")
        }
        val session = openSession(laneDir)
        if (session == null) {
            agent?.close()
            return
        }
        handle.session = session
        try {
            watchingCapture(session) { execute(session, agent, laneDir) }
        } catch (lost: LaneCaptureLostException) {
            captureLost = true
            cancelled = false
            updateLane { it.copy(error = lost.message) }
        } catch (stop: CancellationException) {
            cancelled = true
            throw stop
        } finally {
            withContext(NonCancellable) { release(session, agent) }
        }
    }

    /** Runs [body] and fails the whole lane (not the run) when the lane's recording ends on its own while it works. */
    private suspend fun <T> watchingCapture(session: TestDeviceSession, body: suspend () -> T): T = coroutineScope {
        val watcher = launch {
            session.capture.lost.await()
            throw LaneCaptureLostException()
        }
        try {
            body()
        } finally {
            watcher.cancel()
        }
    }

    private fun openRequest(laneDir: File): LaneOpenRequest {
        val configured = snapshot.config.capture
        return LaneOpenRequest(
            runId = runId,
            laneId = lane.id,
            serial = lane.deviceSerial,
            laneDir = laneDir,
            agentLabel = if (lane.kind == LaneKind.EXTERNAL) "external" else deps.laneLabel(lane),
            capture = configured ?: headlessCaptureSettings(evidence.video),
            // A run without capture settings (an older run file, a test) never had lane tabs; one with them opens a tab unless told not to.
            openTab = configured != null && snapshot.config.openLaneTabs,
        )
    }

    @Suppress("TooGenericExceptionCaught") // Opening a device can fail in many ways (adb, recorder, guard); all are reported as a lane error.
    private suspend fun openSession(laneDir: File): TestDeviceSession? = try {
        deps.openDevice.open(openRequest(laneDir))
    } catch (stop: CancellationException) {
        cancelled = true
        updateLane { it.copy(status = RunStatus.CANCELLED, finishedAt = now()) }
        throw stop
    } catch (failure: Exception) {
        failLane("Could not open device ${lane.deviceSerial}: ${failure.message ?: failure::class.simpleName}")
        null
    }

    private fun failLane(message: String) =
        updateLane { it.copy(status = RunStatus.ERROR, error = message, finishedAt = now(), currentCase = null, currentStepNumber = null) }

    @Suppress("TooGenericExceptionCaught") // Stopping a recording must never keep a lane from ending: whatever it throws is a diagnostic.
    private suspend fun release(session: TestDeviceSession, agent: LaneAgent?) {
        // Markers first: their notes are written and their trailing log windows are given a moment, then the recording stops (a tab
        // stays open as a stopped capture) and the lane is over. The stop is bounded: a recording that will not finish is left to it.
        withTimeoutOrNull(MARKER_WRITE_WAIT_MS) { markerJobs.joinAll() }
        markerScope.cancel()
        runCatching { session.capture.awaitMarkers(MARKER_WINDOW_WAIT_MS) }
        val stopped = try {
            withTimeoutOrNull(LANE_STOP_WAIT_MS) { session.close() }
        } catch (failure: Exception) {
            transcriptWriter?.append("lane_capture_stop_failed", mapOf("message" to (failure.message ?: failure::class.simpleName)))
            Unit
        }
        if (stopped == null) transcriptWriter?.append("lane_capture_stop_timeout", mapOf("waitedMs" to LANE_STOP_WAIT_MS))
        handle.session = null
        // Without capture settings the lane recorded headlessly for evidence only; with them the log is the capture itself and stays.
        if (!evidence.logcat && snapshot.config.capture == null) runCatching { session.logFile.delete() }
        agent?.close()
        updateLane { lane ->
            val ended = when {
                cancelled -> RunStatus.CANCELLED
                lane.error != null -> RunStatus.ERROR
                lane.cases.any { it.status == CaseStatus.ERROR } -> RunStatus.ERROR
                lane.cases.any { it.status == CaseStatus.FAIL || it.status == CaseStatus.BLOCKED } -> RunStatus.FAILED
                else -> RunStatus.PASSED
            }
            lane.copy(status = ended, finishedAt = now(), currentCase = null, currentStepNumber = null, currentStepAction = null)
        }
    }

    private suspend fun execute(session: TestDeviceSession, agent: LaneAgent?, laneDir: File) {
        val runDir = deps.store.runDir(runId)
        val laneActivityFile = File(laneDir, "tool-activity.jsonl")
        activityWriter = LaneToolActivityWriter(laneActivityFile)
        val transcript = if (evidence.transcript) TranscriptWriter(File(laneDir, TEST_RUN_TRANSCRIPT_FILE_NAME), deps.wallClock) else null
        transcriptWriter = transcript
        val env = SequenceEnv(
            session, deps.scriptRunner, snapshot.scripts, runDir, laneDir, suite.targetPackage, evidence, transcript, this,
            wallClock = deps.wallClock,
            judge = judge,
            judgeMode = snapshot.config.judge,
            judgeInconclusivePolicy = snapshot.config.judgeInconclusivePolicy,
            judgeTiming = snapshot.config.judgeTiming,
            judgeLimiter = judgeLimiter,
            laneId = lane.id,
            stepRetriesOverride = snapshot.config.stepRetriesOverride,
            judgeRequested = snapshot.config.judgeActive,
            pauseGate = deps.pauseGate,
            suiteId = suite.id,
            goldenImage = deps.goldenImage,
            goldenFile = deps.goldenFile,
            externalDispatchAdmitted = deps.externalDispatchAdmitted,
        )
        val driver = LaneDriver(
            runId, lane.id, suite, deps.tuning, agent, handle, transcript, snapshot.config.confirmationTimeoutMs,
            onAgentUsage = { segmentId, usage -> recorder?.agentUsage(segmentId, usage) },
            manualSteps = snapshot.config.manualSteps,
        )
        val hooks = HookRunner(snapshot, env, driver) { step -> recorder?.add(step) }
        updateLane {
            it.copy(
                logPath = session.logFile.relativeTo(runDir).invariantSeparatorsPath,
                transcriptPath = transcript?.file?.relativeTo(runDir)?.invariantSeparatorsPath,
                toolActivityPath = laneActivityFile.relativeTo(runDir).invariantSeparatorsPath,
            )
        }
        recordLockedCases()
        try {
            val setupOk = runSuiteHooks(hooks, suite.setup, SUITE_SETUP_CASE_ID, "Suite setup", stopOnFailure = true, scriptsOnly = false)
            if (setupOk) runCases(env, driver, hooks) else blockCases("Suite setup failed.")
        } catch (stop: CancellationException) {
            cancelled = true
            throw stop
        } finally {
            withContext(NonCancellable) {
                runSuiteHooks(hooks, suite.teardown, SUITE_TEARDOWN_CASE_ID, "Suite teardown", stopOnFailure = false, scriptsOnly = cancelled)
            }
        }
    }

    private fun recordLockedCases() = lockedCases.forEach { (case, reason) ->
        state.upsertCase(lane.id, CaseResult(case.id, case.name, 1, CaseStatus.SKIPPED, startedAt = now(), finishedAt = now(), note = reason))
    }

    /** Hooks of the suite itself leave their results in a pseudo case; no hooks, no pseudo case. */
    @Suppress("LongParameterList")
    private suspend fun runSuiteHooks(
        hooks: HookRunner,
        list: List<HookItem>,
        id: String,
        title: String,
        stopOnFailure: Boolean,
        scriptsOnly: Boolean,
    ): Boolean {
        if (list.isEmpty()) return true
        val owner = CaseRecorder(CaseResult(id, title, 1, startedAt = now()))
        recorder = owner
        updateLane { it.copy(currentCase = title) }
        val budget = CaseBudget(snapshot.config.caseToolCallLimit)
        val ok = hooks.run(list, id, 1, id, budget, stopOnFailure, scriptsOnly)
        owner.finish(
            when {
                budget.exhausted() -> CaseStatus.ERROR
                ok -> CaseStatus.PASS
                else -> CaseStatus.FAIL
            },
            CASE_BUDGET_EXHAUSTED_NOTE.takeIf { budget.exhausted() },
        )
        recorder = null
        return ok
    }

    // ── Cases ────────────────────────────────────────────────────────

    private suspend fun runCases(env: SequenceEnv, driver: LaneDriver, hooks: HookRunner) {
        for (iteration in 1..snapshot.config.repeat) {
            for ((position, case) in cases.withIndex()) runCase(env, driver, hooks, case, iteration, "c${position + 1}-i$iteration")
        }
    }

    private fun blockCases(note: String) {
        for (iteration in 1..snapshot.config.repeat) {
            for (case in cases) {
                val steps = case.steps.mapIndexed { i, step -> skipped(i, step, note, case.id, iteration, caseAttempt = 1) }
                state.upsertCase(lane.id, CaseResult(case.id, case.name, iteration, CaseStatus.BLOCKED, steps, now(), now(), note))
            }
        }
    }

    private fun skipped(index: Int, step: TestStep, note: String, caseId: String, iteration: Int, caseAttempt: Int) =
        StepResult(
            step.id,
            index + 1,
            step.action,
            step.expected,
            status = StepStatus.SKIPPED,
            attempts = 0,
            startedAt = now(),
            note = note,
            kind = step.kind,
            caseId = caseId,
            iteration = iteration,
            caseAttempt = caseAttempt,
        )

    @Suppress("LongParameterList")
    private suspend fun runCase(env: SequenceEnv, driver: LaneDriver, hooks: HookRunner, case: TestCase, iteration: Int, prefix: String) {
        val maximumAttempts = 1 + snapshot.config.caseRetries.coerceIn(0, 2)
        val priorAttempts = ArrayList<CaseAttemptRecord>()
        val manualReason = manualRetryExclusion(case)
        for (caseAttempt in 1..maximumAttempts) {
            deps.pauseGate.awaitResumed()
            val attemptPrefix = "$prefix-a$caseAttempt"
            val attemptResult = runCaseAttempt(env, driver, hooks, case, iteration, caseAttempt, attemptPrefix, priorAttempts)
            var result = attemptResult.copy(
                caseAttempt = caseAttempt,
                priorAttempts = priorAttempts.toList(),
                flaky = attemptResult.status == CaseStatus.PASS &&
                    (priorAttempts.isNotEmpty() || attemptResult.steps.any { it.flaky }),
            )
            val canRetry = caseAttempt < maximumAttempts && isEligibleForCaseRetry(result)
            val exclusion = manualReason ?: caseRetryExclusion(result)
            if (canRetry && exclusion != null) {
                result = result.copy(note = listOfNotNull(result.note, "Automatic case retry skipped: $exclusion").joinToString(" "))
            }
            if (canRetry && exclusion == null) {
                priorAttempts += result.toAttemptRecord(caseAttempt)
                result = result.copy(
                    priorAttempts = priorAttempts.dropLast(1),
                    note = listOfNotNull(result.note, "Automatic retry scheduled after attempt $caseAttempt.").joinToString(" "),
                )
                state.upsertCase(lane.id, result)
                env.transcript?.append(
                    "case_retry_scheduled",
                    mapOf(
                        "caseId" to case.id,
                        "iteration" to iteration,
                        "caseAttempt" to caseAttempt,
                        "nextCaseAttempt" to caseAttempt + 1,
                        "status" to result.status?.name,
                    ),
                )
                continue
            }
            val needsFinalIssueDrafts = result.status != CaseStatus.PASS &&
                (snapshot.config.caseRetries > 0 || snapshot.config.judgeTiming != com.indagium.testing.model.JudgeTiming.INLINE)
            if (needsFinalIssueDrafts) {
                result = finalizeIssueDrafts(result)
            }
            state.upsertCase(lane.id, result)
            if (result.flaky) {
                env.transcript?.append(
                    "case_flaky_recovered",
                    mapOf("caseId" to case.id, "iteration" to iteration, "caseAttempt" to caseAttempt),
                )
            }
            return
        }
    }

    @Suppress("LongParameterList")
    private suspend fun runCaseAttempt(
        env: SequenceEnv,
        driver: LaneDriver,
        hooks: HookRunner,
        case: TestCase,
        iteration: Int,
        caseAttempt: Int,
        prefix: String,
        priorAttempts: List<CaseAttemptRecord>,
    ): CaseResult {
        val owner = CaseRecorder(
            CaseResult(case.id, case.name, iteration, startedAt = now(), caseAttempt = caseAttempt, priorAttempts = priorAttempts.toList()),
        )
        recorder = owner
        updateLane {
            it.copy(
                currentCase = if (caseAttempt > 1) "${case.name} (attempt $caseAttempt)" else case.name,
                currentStepNumber = null,
                currentStepAction = null,
            )
        }
        env.transcript?.append("case_started", mapOf("case" to case.name, "iteration" to iteration, "caseAttempt" to caseAttempt))
        val budget = CaseBudget(snapshot.config.caseToolCallLimit)
        var sequence: StepSequence? = null
        var setupBlocked: CaseStatus? = null
        var caseBodyCompleted = false
        try {
            val setupOk = hooks.run(
                case.setup,
                case.id,
                iteration,
                "$prefix-setup",
                budget,
                stopOnFailure = true,
                caseAttempt = caseAttempt,
                priorCaseAttempts = priorAttempts,
            )
            if (!setupOk) {
                val note = if (budget.exhausted()) CASE_BUDGET_EXHAUSTED_NOTE else SETUP_FAILED_NOTE
                owner.addAll(case.steps.mapIndexed { i, step -> skipped(i, step, note, case.id, iteration, caseAttempt) })
                setupBlocked = if (budget.exhausted()) CaseStatus.ERROR else CaseStatus.BLOCKED
                owner.note(note)
            } else {
                sequence = runSteps(env, driver, owner, case, iteration, caseAttempt, prefix, budget, priorAttempts)
            }
            caseBodyCompleted = true
        } catch (stop: CancellationException) {
            cancelled = true
            withContext(NonCancellable) { sequence?.cancelDeferredJudgements() }
            owner.finish(CaseStatus.CANCELLED)
            throw stop
        } finally {
            withContext(NonCancellable) {
                val tornDown = hooks.run(
                    case.teardown,
                    case.id,
                    iteration,
                    "$prefix-teardown",
                    CaseBudget(snapshot.config.caseToolCallLimit),
                    false,
                    cancelled,
                    caseAttempt,
                    priorAttempts,
                )
                if (!tornDown) owner.note("Teardown failed.")
                if (!caseBodyCompleted) recorder = null
            }
        }
        try {
            if (setupBlocked != null) {
                owner.finish(checkNotNull(setupBlocked))
            } else {
                sequence?.finishDeferredJudgements()
                owner.finish(
                    caseStatus(owner.snapshot().steps.filterNot { it.setup }, case.steps.size, budget),
                    CASE_BUDGET_EXHAUSTED_NOTE.takeIf { budget.exhausted() },
                )
            }
        } catch (stop: CancellationException) {
            cancelled = true
            withContext(NonCancellable) {
                sequence?.cancelDeferredJudgements()
                owner.finish(CaseStatus.CANCELLED)
            }
            throw stop
        } finally {
            recorder = null
        }
        return owner.snapshot()
    }

    private fun manualRetryExclusion(case: TestCase): String? {
        val caseHookSteps = (case.setup + case.teardown).flatMap { hook ->
            when (hook) {
                is HookItem.Script -> emptyList()
                is HookItem.Shared -> snapshot.sharedSteps.firstOrNull { it.id == hook.sharedStepId }?.steps.orEmpty()
            }
        }
        val manual = (case.steps + caseHookSteps).filter { it.kind == com.indagium.testing.model.StepKind.MANUAL }
        if (manual.isEmpty()) return null
        return when (snapshot.config.manualSteps) {
            com.indagium.testing.model.ManualStepsOption.WAIT_FOR_TESTER -> "the case requires a tester for manual steps."
            com.indagium.testing.model.ManualStepsOption.USE_SCRIPT_THEN_WAIT ->
                "the case has manual steps without an automation script."
                    .takeIf { manual.any { step -> step.script == null } }
            com.indagium.testing.model.ManualStepsOption.SKIP -> null
        }
    }

    private fun isEligibleForCaseRetry(result: CaseResult): Boolean {
        if (result.status !in setOf(CaseStatus.FAIL, CaseStatus.BLOCKED)) return false
        if (result.steps.any { it.userRejected || it.automaticRetryExcluded }) return false
        val failed = result.steps.filter { step ->
            step.status == StepStatus.TIMEOUT || step.status == StepStatus.FAIL && (
                !step.setup || step.checks.isNotEmpty() || step.agentClaim != null || step.judge?.verdict == com.indagium.testing.model.JudgeVerdict.FAIL ||
                    step.executionStatus == StepStatus.FAIL
            )
        }
        if (failed.isNotEmpty()) return true
        if (result.steps.any { it.setup && it.status == StepStatus.BLOCKED && !it.judgeInconclusive }) return true
        val blockers = result.steps.filter { !it.setup && it.status == StepStatus.BLOCKED }
        return blockers.isNotEmpty() && blockers.any { !it.judgeInconclusive }
    }

    private fun caseRetryExclusion(result: CaseResult): String? = when {
        result.steps.any { it.automaticRetryExcluded } -> "a driver step needs a configuration or permission change."
        result.steps.any { it.userRejected } -> "a user rejected the step."
        result.steps.any { it.kind == com.indagium.testing.model.StepKind.MANUAL && it.completedBy == com.indagium.testing.model.StepCompletion.TESTER } ->
            "a tester completed a manual step."
        else -> null
    }

    private fun CaseResult.toAttemptRecord(attempt: Int) = CaseAttemptRecord(
        caseId = caseId,
        iteration = iteration,
        attempt = attempt,
        status = status ?: CaseStatus.ERROR,
        steps = steps,
        startedAt = startedAt,
        finishedAt = finishedAt,
        note = note,
        agentUsage = agentUsage,
        judgeUsage = judgeUsage,
        externalToolCalls = externalToolCalls,
    )

    private fun finalizeIssueDrafts(result: CaseResult): CaseResult {
        val issues = deps.issues ?: return result
        val drafter = IssueAutoDrafter(issues, deps.store.runDir(runId), deps.goldenFile)
        val steps = result.steps.map { step ->
            if (!step.issueRequested || step.issueId != null) return@map step
            val draft = runCatching { drafter.draft(state.current, lane.id, result.caseId, result.iteration, step).getOrNull() }.getOrNull()
            if (draft != null) step.copy(issueId = draft) else step
        }
        return result.copy(steps = steps)
    }

    @Suppress("LongParameterList")
    private suspend fun runSteps(
        env: SequenceEnv,
        driver: LaneDriver,
        owner: CaseRecorder,
        case: TestCase,
        iteration: Int,
        caseAttempt: Int,
        prefix: String,
        budget: CaseBudget,
        priorAttempts: List<CaseAttemptRecord>,
    ): StepSequence? {
        if (case.steps.isEmpty()) {
            return null
        }
        val sequence = StepSequence(
            env,
            SequenceSpec(
                case.id,
                case.name,
                case.steps,
                setup = false,
                allowedTools = case.allowedTools,
                evidencePrefix = prefix,
                caseBudget = budget,
                iteration = iteration,
                caseAttempt = caseAttempt,
                priorCaseAttempts = priorAttempts,
            ),
        )
        try {
            driver.drive(sequence, case, iteration, budget)
        } catch (cancelled: CancellationException) {
            // A cancellation during independent judgment must not discard the finish_step report already
            // committed to the sequence. Preserve the current result before the case is finalized.
            withContext(NonCancellable) {
                sequence.cancelDeferredJudgements()
                sequence.abort(StepStatus.ERROR, "The run was cancelled while this step was in progress.", interrupted = true)
            }
            throw cancelled
        }
        val results = sequence.results()
        owner.addAll(
            case.steps.drop(results.size).mapIndexed { i, step -> skipped(results.size + i, step, STOPPED_NOTE, case.id, iteration, caseAttempt) },
        )
        return sequence
    }

    private fun caseStatus(results: List<StepResult>, stepCount: Int, budget: CaseBudget): CaseStatus = when {
        budget.exhausted() -> CaseStatus.ERROR
        results.any { it.status == StepStatus.FAIL || it.status == StepStatus.TIMEOUT } -> CaseStatus.FAIL
        results.any { it.status == StepStatus.BLOCKED } -> CaseStatus.BLOCKED
        results.any { it.status == StepStatus.ERROR } -> CaseStatus.ERROR
        results.size < stepCount -> CaseStatus.FAIL
        else -> CaseStatus.PASS
    }

    // ── SequenceListener ─────────────────────────────────────────────

    override fun stepStarted(stepNumber: Int, action: String, attempt: Int) {
        updateLane { it.copy(currentStepNumber = stepNumber, currentStepAction = if (attempt > 1) "$action (attempt $attempt)" else action) }
    }

    override fun stepRecorded(result: StepResult) {
        val owner = recorder ?: return
        owner.add(
            if (result.issueRequested && !result.setup && snapshot.config.caseRetries == 0 &&
                snapshot.config.judgeTiming == com.indagium.testing.model.JudgeTiming.INLINE
            ) {
                withIssueDraft(owner, result)
            } else {
                result
            },
        )
        if (!result.setup && result.status in MARKED_STEP_STATUSES) markStepInCapture(owner.caseName, result)
    }

    override fun stepUpdated(result: StepResult) {
        val owner = recorder ?: return
        if (owner.updateDeferredStep(result) && !result.setup && result.status in MARKED_STEP_STATUSES) {
            markStepInCapture(owner.caseName, result)
        }
    }

    override fun stepUnjudged(result: StepResult) {
        recorder?.replaceStep(result)
    }

    /**
     * Writes a marker for a step that ended badly into the lane's capture, the way the Mark issue button would at this moment: a
     * note (action, expected, failed checks, judge verdict), the step's screenshot and, once the window has elapsed, the log around it.
     * It runs beside the lane and never decides a step's outcome.
     */
    @Suppress("TooGenericExceptionCaught") // A marker is evidence only: whatever writing it throws is logged in the transcript, nothing more.
    private fun markStepInCapture(caseName: String, step: StepResult) {
        val session = handle.session ?: return
        val runDir = deps.store.runDir(runId)
        markerJobs += markerScope.launch {
            try {
                val screenshot = step.screenshotPath?.let { readBoundedRunArtifact(runDir, it, MAX_MARKER_SCREENSHOT_BYTES) }
                val request = LaneMarkerRequest(laneMarkerLabel(caseName, step), laneMarkerNote(caseName, step), screenshot)
                val fields = when (val outcome = session.capture.addMarker(request)) {
                    is LaneMarkerOutcome.Added -> mapOf("step" to step.stepNumber, "marker" to outcome.markerId, "screenshot" to outcome.screenshotAttached)
                    is LaneMarkerOutcome.Skipped -> mapOf("step" to step.stepNumber, "skipped" to outcome.reason)
                }
                transcriptWriter?.append("lane_marker", fields)
            } catch (stop: CancellationException) {
                throw stop
            } catch (failure: Exception) {
                transcriptWriter?.append("lane_marker_failed", mapOf("step" to step.stepNumber, "message" to (failure.message ?: failure::class.simpleName)))
            }
        }
    }

    override fun toolCallStarted(call: com.indagium.testing.model.LaneToolCall) {
        activityWriter?.append("started", call)
        transcriptWriter?.append("lane_tool_call_started", laneToolCallFields(call))
        updateLane { lane -> lane.copy(toolCalls = (lane.toolCalls + call).takeLast(MAX_PERSISTED_TOOL_CALLS)) }
        if (lane.kind == LaneKind.EXTERNAL) recorder?.externalToolCall(call.id)
    }

    override fun toolCallFinished(call: com.indagium.testing.model.LaneToolCall) {
        activityWriter?.append("finished", call)
        transcriptWriter?.append("lane_tool_call_finished", laneToolCallFields(call))
        updateLane { lane -> lane.copy(toolCalls = lane.toolCalls.map { if (it.id == call.id) call else it }) }
    }

    override fun judgeUsage(usage: AiUsageStats) {
        recorder?.judgeUsage(usage)
    }

    private fun laneToolCallFields(call: com.indagium.testing.model.LaneToolCall) = mapOf(
        "id" to call.id,
        "caseId" to call.caseId,
        "stepId" to call.stepId,
        "iteration" to call.iteration,
        "attempt" to call.attempt,
        "toolName" to call.toolName,
        "arguments" to call.argumentsPreview,
        "result" to call.resultPreview,
        "status" to call.status.name,
        "durationMs" to call.durationMs,
    )

    /**
     * A step that asked for an issue (CREATE_ISSUE_AND_CONTINUE) gets a LOCAL draft now, while its evidence is on disk. The
     * issue's id goes on the result; when no draft could be made, the reason goes into the result's note. Never fails the step.
     */
    @Suppress("TooGenericExceptionCaught") // Evidence is read from disk and copied; whatever that throws must not decide a step's outcome.
    private fun withIssueDraft(owner: CaseRecorder, result: StepResult): StepResult {
        val issues = deps.issues ?: return result
        val drafter = IssueAutoDrafter(issues, deps.store.runDir(runId), deps.goldenFile)
        val failure = try {
            drafter.draft(state.current, lane.id, owner.caseId, owner.iteration, result).fold(
                onSuccess = { return result.copy(issueId = it) },
                onFailure = { it.message },
            )
        } catch (problem: Exception) {
            problem.message ?: problem::class.simpleName
        }
        return result.copy(note = listOfNotNull(result.note, "No issue draft could be created: $failure").joinToString(" "))
    }

    override fun waitStateChanged() = state.touch()

    override suspend fun awaitUser(paused: PausedStep): PauseDecision {
        state.touch() // lets the status views notice the pause
        return try {
            handle.awaitDecision(paused)
        } finally {
            state.touch()
        }
    }

    /** The result of the case being run, published to the run state on every change. */
    private inner class CaseRecorder(initial: CaseResult) {
        private var result = if (lane.kind == LaneKind.EXTERNAL) initial.copy(externalToolCalls = 0L) else initial
        private val agentSegments = LinkedHashMap<String, AiUsageStats>()
        private val externalToolCallIds = HashSet<String>()
        val caseId: String = initial.caseId
        val caseName: String = initial.caseName
        val iteration: Int = initial.iteration

        init {
            publish()
        }

        private fun publish() = state.upsertCase(lane.id, result)

        @Synchronized
        fun add(step: StepResult) {
            result = result.copy(steps = result.steps + step)
            publish()
        }

        @Synchronized
        fun addAll(steps: List<StepResult>) {
            result = result.copy(steps = result.steps + steps)
            publish()
        }

        @Suppress("CyclomaticComplexMethod") // Atomically applies deferred verdicts to exact pending top-level/history attempts.
        @Synchronized
        fun updateDeferredStep(step: StepResult): Boolean {
            if (step.caseId != caseId || step.iteration != iteration || step.caseAttempt != result.caseAttempt) return false
            val position = result.steps.indexOfFirst { current ->
                current.caseId == step.caseId && current.iteration == step.iteration && current.caseAttempt == step.caseAttempt &&
                    current.stepId == step.stepId && (
                        current.attempts == step.attempts && current.verificationState == com.indagium.testing.model.VerificationState.PENDING ||
                            current.attemptHistory.any { earlier ->
                                earlier.verificationState == com.indagium.testing.model.VerificationState.PENDING &&
                                    step.attemptHistory.any { resolved ->
                                        resolved.caseId == earlier.caseId && resolved.iteration == earlier.iteration &&
                                            resolved.caseAttempt == earlier.caseAttempt && resolved.stepId == earlier.stepId &&
                                            resolved.attempt == earlier.attempt &&
                                            resolved.verificationState != com.indagium.testing.model.VerificationState.PENDING
                                    }
                            }
                    )
            }
            if (position < 0) return false
            val previous = result.steps[position]
            val completedJudges = step.attemptHistory.filter { resolved ->
                previous.attemptHistory.any { before ->
                    before.caseId == resolved.caseId && before.iteration == resolved.iteration && before.caseAttempt == resolved.caseAttempt &&
                        before.stepId == resolved.stepId && before.attempt == resolved.attempt &&
                        before.verificationState == com.indagium.testing.model.VerificationState.PENDING &&
                        resolved.verificationState != com.indagium.testing.model.VerificationState.PENDING
                }
            }.mapNotNull { it.judgeUsage }
            val topAttemptResolvedInHistory = step.attemptHistory.any { resolved ->
                resolved.caseId == previous.caseId && resolved.iteration == previous.iteration && resolved.caseAttempt == previous.caseAttempt &&
                    resolved.stepId == previous.stepId && resolved.attempt == previous.attempts &&
                    resolved.verificationState != com.indagium.testing.model.VerificationState.PENDING
            }
            val topJudgeUsage = if (previous.verificationState == com.indagium.testing.model.VerificationState.PENDING && !topAttemptResolvedInHistory) {
                step.judge?.usage
            } else {
                null
            }
            result = result.copy(
                steps = result.steps.mapIndexed { index, current -> if (index == position) step else current },
                judgeUsage = sumAiUsage(listOfNotNull(result.judgeUsage, topJudgeUsage) + completedJudges),
            )
            publish()
            return true
        }

        /** Swaps in [step] (same case attempt and step id) without touching usage; used when only its note changed. */
        @Synchronized
        fun replaceStep(step: StepResult) {
            if (step.caseId != caseId || step.iteration != iteration || step.caseAttempt != result.caseAttempt) return
            val position = result.steps.indexOfFirst { it.stepId == step.stepId && it.setup == step.setup }
            if (position < 0) return
            result = result.copy(steps = result.steps.mapIndexed { index, current -> if (index == position) step else current })
            publish()
        }

        @Synchronized
        fun note(text: String) {
            result = result.copy(note = listOfNotNull(result.note, text).joinToString(" "))
            publish()
        }

        @Synchronized
        fun agentUsage(segmentId: String, usage: AiUsageStats) {
            agentSegments[segmentId] = usage
            result = result.copy(agentUsage = sumAiUsage(agentSegments.values.toList()))
            publish()
        }

        @Synchronized
        fun judgeUsage(usage: AiUsageStats) {
            result = result.copy(judgeUsage = sumAiUsage(listOf(result.judgeUsage, usage)))
            publish()
        }

        @Synchronized
        fun externalToolCall(callId: String) {
            if (!externalToolCallIds.add(callId)) return
            result = result.copy(externalToolCalls = externalToolCallIds.size.toLong())
            publish()
        }

        @Synchronized
        fun finish(status: CaseStatus, note: String? = null) {
            result = result.copy(status = status, finishedAt = now(), note = note ?: result.note)
            publish()
        }

        @Synchronized
        fun snapshot(): CaseResult = result
    }
}

private const val MAX_PERSISTED_TOOL_CALLS = 300
