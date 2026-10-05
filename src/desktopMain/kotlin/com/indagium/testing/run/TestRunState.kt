package com.indagium.testing.run

import com.indagium.ai.AiRun
import com.indagium.ai.AiRunEvent
import com.indagium.testing.device.TestDeviceSession
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.TestRun
import kotlinx.coroutines.CompletableDeferred
import java.io.File

// The live, mutable side of a running test run. [TestRunState] holds the current immutable [TestRun] and swaps it
// functionally, so a snapshot handed to the UI or written to run.json is never touched afterwards. Its lock is a LEAF:
// nothing is called while holding it, and [changed] runs outside it and takes no argument — a listener reads
// [TestRunState.current], so whichever notification runs last sees the freshest run.

internal class TestRunState(initial: TestRun, private val changed: () -> Unit) {
    private val lock = Any()

    @Volatile
    var current: TestRun = initial
        private set

    fun update(transform: (TestRun) -> TestRun) {
        synchronized(lock) { current = transform(current) }
        changed()
    }

    /** Tells the listener that something not stored in the run changed (a pause started or ended). */
    fun touch() = changed()

    fun updateLane(laneId: String, transform: (LaneResult) -> LaneResult) = update { it.withLane(laneId, transform) }

    /** Replaces the case result with the same [CaseResult.caseId] and iteration, or appends it. */
    fun upsertCase(laneId: String, case: CaseResult) = updateLane(laneId) { lane ->
        val position = lane.cases.indexOfFirst { it.caseId == case.caseId && it.iteration == case.iteration }
        lane.copy(cases = if (position < 0) lane.cases + case else lane.cases.mapIndexed { i, old -> if (i == position) case else old })
    }
}

/** A confirmation card an in-app agent is waiting on (an ASK script). */
internal data class PendingTestConfirmation(
    val runId: String,
    val laneId: String,
    val confirmationId: String,
    val toolName: String,
    val description: String,
)

/** A lane waiting for the user to decide what to do with a step. */
internal data class PausedStepInfo(val runId: String, val laneId: String, val step: PausedStep)

/**
 * The engine's side of one lane that the coordinator reaches into: the agent run to read confirmations from, the pause
 * to resolve and the sequence an external client drives. Everything is volatile; the engine writes, the coordinator reads.
 */
internal class LaneHandle(val runId: String, val laneId: String) {
    @Volatile var sequence: StepSequence? = null

    @Volatile var agentRun: AiRun? = null

    /** The directory of the lane in the run folder, once known. */
    @Volatile var laneDir: File? = null

    @Volatile var session: TestDeviceSession? = null

    private class Pause(val step: PausedStep, val decision: CompletableDeferred<PauseDecision>)

    @Volatile private var pause: Pause? = null

    fun pausedStep(): PausedStep? = pause?.step

    /** Suspends until the user decides; the pause is visible through [pausedStep] meanwhile. */
    suspend fun awaitDecision(step: PausedStep): PauseDecision {
        val waiting = Pause(step, CompletableDeferred())
        pause = waiting
        try {
            return waiting.decision.await()
        } finally {
            pause = null
        }
    }

    /** False when the lane is not paused. */
    fun resolvePause(decision: PauseDecision): Boolean = pause?.decision?.complete(decision) ?: false

    /** Confirmation cards the lane's current agent run still waits on. */
    fun pendingConfirmations(): List<PendingTestConfirmation> {
        val run = agentRun ?: return emptyList()
        return run.history.filterIsInstance<AiRunEvent.ConfirmationRequired>()
            .filter { run.isConfirmationPending(it.confirmation.id) }
            .map { PendingTestConfirmation(runId, laneId, it.confirmation.id, it.confirmation.call.name, it.confirmation.description) }
    }

    /** Answers a pending confirmation card. False when it is no longer pending. */
    fun resolveConfirmation(confirmationId: String, allow: Boolean): Boolean {
        val run = agentRun ?: return false
        val deferred = run.confirmations.remove(confirmationId) ?: return false
        return deferred.complete(allow)
    }
}
