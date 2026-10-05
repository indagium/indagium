package com.indagium.testing.run

import com.indagium.edition.EditionLimits
import com.indagium.testing.limits.LimitDecision
import com.indagium.testing.limits.LimitOperation
import com.indagium.testing.limits.decide
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.judgeActive
import com.indagium.testing.store.RunPersister
import com.indagium.testing.store.TEST_RUN_JUDGE_FILE_NAME
import com.indagium.testing.store.TranscriptWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

// Executes one test run: its lanes through LaneRunners, in parallel across devices and one after another on a shared
// device (LaneScheduler.kt), then, when a judge is configured, a comparison of the steps the lanes disagreed on. The run's
// status is derived from its lanes: ERROR when any lane had an infrastructure problem or a case could not be judged,
// FAILED when any case failed or was blocked, CANCELLED after a cancel, PASSED otherwise. However the run ends (even by
// cancellation) the judge is closed, the final status written and run.json flushed.

private const val LOCKED_CASE_NOTE = "Locked by the edition limit; it was not run."

/** The cases a run will execute and the ones the edition limits keep out, each with the reason. */
internal data class CasePlan(val cases: List<TestCase>, val locked: List<Pair<TestCase, String>>)

/**
 * The cases of [suiteId] chosen by [caseIds] (null = all), split into runnable and locked. A locked case is never run:
 * the caller reports it as skipped. [limits] is the edition's current limits.
 */
internal fun planCases(library: TestLibrary, suiteId: String, caseIds: List<String>?, limits: EditionLimits): CasePlan {
    val suite = library.suite(suiteId) ?: return CasePlan(emptyList(), emptyList())
    val chosen = if (caseIds == null) suite.cases else suite.cases.filter { it.id in caseIds }
    val runnable = ArrayList<TestCase>()
    val locked = ArrayList<Pair<TestCase, String>>()
    for (case in chosen) {
        if (decide(library, LimitOperation.RunCase(suiteId, case.id), limits) is LimitDecision.Refused) {
            locked += case to LOCKED_CASE_NOTE
        } else {
            runnable += case
        }
    }
    return CasePlan(runnable, locked)
}

internal class TestRunEngine(
    private val state: TestRunState,
    private val persister: RunPersister,
    private val deps: EngineDeps,
    private val plan: CasePlan,
    private val handles: Map<String, LaneHandle>,
) {
    suspend fun execute() {
        val snapshot = state.current
        state.update { it.copy(status = RunStatus.RUNNING, startedAt = deps.wallClock()) }
        var cancelled = false
        var judge: JudgeService? = null
        try {
            judge = prepareJudge(snapshot)
            runLaneGroups(snapshot.lanes.map { it.config }, deps.tuning.maxParallelDevices) { config ->
                runLane(snapshot, config, checkNotNull(handles[config.id]) { "No lane handle for ${config.id}" }, judge)
            }
            if (judge != null) compareLanes(judge)
        } catch (stop: CancellationException) {
            cancelled = true
            throw stop
        } finally {
            withContext(NonCancellable) {
                judge?.close()
                finish(cancelled)
            }
        }
    }

    /** The run's judge, or null when none is configured or it could not be prepared (then a warning says so and the run goes on without). */
    private fun prepareJudge(snapshot: TestRun): JudgeService? {
        val config = snapshot.config
        val make = deps.judgeAgent
        if (!config.judgeActive || make == null) return null
        val agent = try {
            make()
        } catch (refused: IllegalStateException) {
            return withoutJudge(refused.message)
        } catch (refused: IllegalArgumentException) {
            return withoutJudge(refused.message)
        }
        val transcript = if (config.evidence.transcript) {
            TranscriptWriter(File(deps.store.runDir(snapshot.id), TEST_RUN_JUDGE_FILE_NAME), deps.wallClock)
        } else {
            null
        }
        return JudgeService(snapshot.id, snapshot.suite.id, agent, deps.tuning, transcript, deps.goldenImage, deps.wallClock)
    }

    private fun withoutJudge(reason: String?): JudgeService? {
        val warning = "The judge could not be prepared (${reason ?: "unknown reason"}); the run goes on without it."
        state.update { it.copy(warnings = it.warnings + warning) }
        return null
    }

    @Suppress("TooGenericExceptionCaught") // A lane that crashes must not take the run, or the other lanes, down with it.
    private suspend fun runLane(snapshot: TestRun, config: LaneConfig, handle: LaneHandle, judge: JudgeService?) {
        try {
            LaneRunner(snapshot.id, snapshot, config, plan.cases, plan.locked, state, deps, handle, judge?.forLane(config.id)).run()
        } catch (stop: CancellationException) {
            throw stop
        } catch (crash: Exception) {
            val message = "The lane stopped unexpectedly: ${crash.message ?: crash::class.simpleName}"
            state.updateLane(config.id) { it.copy(status = RunStatus.ERROR, error = message, finishedAt = deps.wallClock()) }
        }
    }

    /** Every lane is done: the steps they disagreed on go to the comparison judge, one at a time. */
    private suspend fun compareLanes(judge: JudgeService) {
        val run = state.current
        compareDisagreements(run, deps.store.runDir(run.id), judge) { comparison -> state.update { it.copy(comparisons = it.comparisons + comparison) } }
    }

    private suspend fun finish(cancelled: Boolean) {
        state.update { run ->
            val lanes = run.lanes.map { lane ->
                if (lane.status == RunStatus.QUEUED || lane.status == RunStatus.RUNNING) {
                    lane.copy(status = if (cancelled) RunStatus.CANCELLED else RunStatus.ERROR, finishedAt = lane.finishedAt ?: deps.wallClock())
                } else {
                    lane
                }
            }
            val status = when {
                cancelled -> RunStatus.CANCELLED
                lanes.any { it.status == RunStatus.ERROR } -> RunStatus.ERROR
                lanes.any { it.status == RunStatus.FAILED || it.status == RunStatus.CANCELLED } -> RunStatus.FAILED
                else -> RunStatus.PASSED
            }
            run.copy(lanes = lanes, status = status, finishedAt = deps.wallClock())
        }
        persister.flush()
    }
}
