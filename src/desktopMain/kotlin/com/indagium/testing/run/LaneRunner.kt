package com.indagium.testing.run

import com.indagium.testing.device.TestDeviceSession
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
import com.indagium.testing.store.TEST_RUN_TRANSCRIPT_FILE_NAME
import com.indagium.testing.store.TranscriptWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

// One lane of a run, start to finish: open the device, run the suite's setup hooks, then every case (per iteration):
// case setup -> the case's steps -> case teardown (always) -> ..., and the suite's teardown (always), then release the
// device. A failing setup hook BLOCKS what depends on it (the cases are not run; their steps are SKIPPED). Cancelling the
// lane coroutine still runs the teardown hooks (scripts only) and stops the recorder, on IO and without being cancelled.
// The lane never touches AppState: it only reads the frozen [snapshot] and writes results through [state].

private const val SETUP_FAILED_NOTE = "Setup failed, so the case did not run."
private const val STOPPED_NOTE = "Skipped because an earlier step stopped the case."

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
) : SequenceListener {
    private val suite = snapshot.suite
    private val evidence = snapshot.config.evidence

    @Volatile private var recorder: CaseRecorder? = null

    @Volatile private var cancelled = false

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
            execute(session, agent, laneDir)
        } catch (stop: CancellationException) {
            cancelled = true
            throw stop
        } finally {
            withContext(NonCancellable) { release(session, agent) }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Opening a device can fail in many ways (adb, recorder, guard); all are reported as a lane error.
    private suspend fun openSession(laneDir: File): TestDeviceSession? = try {
        deps.openDevice.open(lane.deviceSerial, laneDir, evidence.video)
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

    private suspend fun release(session: TestDeviceSession, agent: LaneAgent?) {
        session.close()
        handle.session = null
        if (!evidence.logcat) runCatching { session.logFile.delete() }
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
        val transcript = if (evidence.transcript) TranscriptWriter(File(laneDir, TEST_RUN_TRANSCRIPT_FILE_NAME), deps.wallClock) else null
        val env = SequenceEnv(
            session, deps.scriptRunner, snapshot.scripts, runDir, laneDir, suite.targetPackage, evidence, transcript, this,
            wallClock = deps.wallClock,
            judge = judge,
            judgeMode = snapshot.config.judge,
            pauseGate = deps.pauseGate,
        )
        val driver = LaneDriver(runId, lane.id, suite, deps.tuning, agent, handle, transcript, snapshot.config.confirmationTimeoutMs)
        val hooks = HookRunner(snapshot, env, driver) { step -> recorder?.add(step) }
        updateLane {
            it.copy(
                logPath = session.logFile.relativeTo(runDir).invariantSeparatorsPath,
                transcriptPath = transcript?.file?.relativeTo(runDir)?.invariantSeparatorsPath,
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
        val ok = hooks.run(list, id, 1, id, CaseBudget(snapshot.config.caseToolCallLimit), stopOnFailure, scriptsOnly)
        owner.finish(if (ok) CaseStatus.PASS else CaseStatus.FAIL)
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
                val steps = case.steps.mapIndexed { i, step -> skipped(i, step, note) }
                state.upsertCase(lane.id, CaseResult(case.id, case.name, iteration, CaseStatus.BLOCKED, steps, now(), now(), note))
            }
        }
    }

    private fun skipped(index: Int, step: TestStep, note: String) =
        StepResult(step.id, index + 1, step.action, step.expected, status = StepStatus.SKIPPED, attempts = 0, startedAt = now(), note = note)

    @Suppress("LongParameterList")
    private suspend fun runCase(env: SequenceEnv, driver: LaneDriver, hooks: HookRunner, case: TestCase, iteration: Int, prefix: String) {
        deps.pauseGate.awaitResumed()
        val owner = CaseRecorder(CaseResult(case.id, case.name, iteration, startedAt = now()))
        recorder = owner
        updateLane { it.copy(currentCase = case.name, currentStepNumber = null, currentStepAction = null) }
        env.transcript?.append("case_started", mapOf("case" to case.name, "iteration" to iteration))
        val budget = CaseBudget(snapshot.config.caseToolCallLimit)
        try {
            val setupOk = hooks.run(case.setup, case.id, iteration, "$prefix-setup", budget, stopOnFailure = true)
            if (!setupOk) {
                owner.addAll(case.steps.mapIndexed { i, step -> skipped(i, step, SETUP_FAILED_NOTE) })
                owner.finish(CaseStatus.BLOCKED, SETUP_FAILED_NOTE)
            } else {
                runSteps(env, driver, owner, case, iteration, prefix, budget)
            }
        } catch (stop: CancellationException) {
            cancelled = true
            owner.finish(CaseStatus.CANCELLED)
            throw stop
        } finally {
            withContext(NonCancellable) {
                val tornDown = hooks.run(case.teardown, case.id, iteration, "$prefix-teardown", CaseBudget(snapshot.config.caseToolCallLimit), false, cancelled)
                if (!tornDown) owner.note("Teardown failed.")
                recorder = null
            }
        }
    }

    @Suppress("LongParameterList")
    private suspend fun runSteps(
        env: SequenceEnv,
        driver: LaneDriver,
        owner: CaseRecorder,
        case: TestCase,
        iteration: Int,
        prefix: String,
        budget: CaseBudget,
    ) {
        if (case.steps.isEmpty()) {
            owner.finish(CaseStatus.PASS, "The case has no steps.")
            return
        }
        val sequence = StepSequence(env, SequenceSpec(case.id, case.name, case.steps, setup = false, allowedTools = case.allowedTools, evidencePrefix = prefix))
        driver.drive(sequence, case, iteration, budget)
        val results = sequence.results()
        owner.addAll(case.steps.drop(results.size).mapIndexed { i, step -> skipped(results.size + i, step, STOPPED_NOTE) })
        owner.finish(caseStatus(results, case.steps.size))
    }

    private fun caseStatus(results: List<StepResult>, stepCount: Int): CaseStatus = when {
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
        recorder?.add(result)
    }

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
        private var result = initial

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

        @Synchronized
        fun note(text: String) {
            result = result.copy(note = listOfNotNull(result.note, text).joinToString(" "))
            publish()
        }

        @Synchronized
        fun finish(status: CaseStatus, note: String? = null) {
            result = result.copy(status = status, finishedAt = now(), note = note ?: result.note)
            publish()
        }
    }
}
