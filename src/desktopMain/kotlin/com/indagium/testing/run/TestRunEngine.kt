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
import com.indagium.testing.store.RunPersister
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// Executes one test run: its lanes one after another (the model has N lanes; running them in parallel is a later
// feature), each through a LaneRunner. The run's status is derived from its lanes: ERROR when any lane had an
// infrastructure problem or a case could not be judged, FAILED when any case failed or was blocked, CANCELLED after a
// cancel, PASSED otherwise. However the run ends (even by cancellation) the final status is written and run.json flushed.

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
        try {
            for (lane in snapshot.lanes) {
                val handle = checkNotNull(handles[lane.laneId]) { "No lane handle for ${lane.laneId}" }
                runLane(snapshot, lane.config, handle)
            }
        } catch (stop: CancellationException) {
            cancelled = true
            throw stop
        } finally {
            withContext(NonCancellable) { finish(cancelled) }
        }
    }

    @Suppress("TooGenericExceptionCaught") // A lane that crashes must not take the run, or the other lanes, down with it.
    private suspend fun runLane(snapshot: TestRun, config: LaneConfig, handle: LaneHandle) {
        try {
            LaneRunner(snapshot.id, snapshot, config, plan.cases, plan.locked, state, deps, handle).run()
        } catch (stop: CancellationException) {
            throw stop
        } catch (crash: Exception) {
            val message = "The lane stopped unexpectedly: ${crash.message ?: crash::class.simpleName}"
            state.updateLane(config.id) { it.copy(status = RunStatus.ERROR, error = message, finishedAt = deps.wallClock()) }
        }
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
