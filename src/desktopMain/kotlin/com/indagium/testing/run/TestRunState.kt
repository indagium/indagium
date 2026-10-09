package com.indagium.testing.run

import com.indagium.ai.AiRun
import com.indagium.ai.AiRunEvent
import com.indagium.testing.device.TestDeviceSession
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.ManualCompletion
import com.indagium.testing.model.TestRun
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

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

/** What a tester can answer to a manual step the lane waits on. */
internal enum class ManualDecision { DONE, FAILED, SKIP }

internal data class ManualResolution(val decision: ManualDecision, val note: String)

/**
 * What a lane that waits for a tester shows: the step's [instructions], the device it is on and the time left ([deadlineAtMs], wall
 * clock). [testerCanComplete] is false for a step that is completed by its checks only: the tester can then only fail or skip it.
 */
internal data class ManualWait(
    val caseName: String,
    val stepNumber: Int,
    val stepCount: Int,
    val instructions: String,
    val expected: String,
    val deviceSerial: String,
    val startedAtMs: Long,
    val deadlineAtMs: Long,
    val completion: ManualCompletion,
    val testerCanComplete: Boolean,
    /** A short sentence about what the lane does while it waits (a failed automation script, the checks it watches). */
    val hint: String = "",
)

/** A lane waiting for a tester (the card of the live view, and what get_test_run_status lists). */
internal data class WaitingLaneInfo(val runId: String, val laneId: String, val wait: ManualWait)

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

    private class ManualWaiting(val info: ManualWait, val resolution: CompletableDeferred<ManualResolution>)

    @Volatile private var manualWaiting: ManualWaiting? = null

    /** The manual step this lane waits for a tester on, or null. */
    fun waitingStep(): ManualWait? = manualWaiting?.info

    /** Starts waiting for a tester: the wait is visible through [waitingStep] until [endManualWait]; the answer completes the returned deferred. */
    fun beginManualWait(info: ManualWait): CompletableDeferred<ManualResolution> {
        val waiting = ManualWaiting(info, CompletableDeferred())
        manualWaiting = waiting
        return waiting.resolution
    }

    fun endManualWait() {
        manualWaiting = null
    }

    /** Answers the manual step: false when the lane is not waiting, or the tester cannot complete it (a checks-only step may only be failed or skipped). */
    fun resolveManual(decision: ManualDecision, note: String): Boolean {
        val waiting = manualWaiting ?: return false
        if (decision == ManualDecision.DONE && !waiting.info.testerCanComplete) return false
        return waiting.resolution.complete(ManualResolution(decision, note))
    }

    private class ScriptConfirmation(val card: PendingTestConfirmation, val answer: CompletableDeferred<Boolean>)

    @Volatile private var scriptConfirmation: ScriptConfirmation? = null

    /**
     * Asks the user whether the lane may run an ASK script as a step (the lane has no agent run to ask for it). The card appears with
     * the others in [pendingConfirmations]; no answer within [timeoutMs] counts as a refusal.
     */
    suspend fun awaitScriptConfirmation(toolName: String, description: String, timeoutMs: Long): Boolean {
        val pending = ScriptConfirmation(
            PendingTestConfirmation(runId, laneId, UUID.randomUUID().toString(), toolName, description),
            CompletableDeferred(),
        )
        scriptConfirmation = pending
        try {
            return withTimeoutOrNull(timeoutMs) { pending.answer.await() } ?: false
        } finally {
            scriptConfirmation = null
        }
    }

    /** Confirmation cards the lane's current agent run, or its script step, still waits on. */
    fun pendingConfirmations(): List<PendingTestConfirmation> {
        val stepCard = scriptConfirmation?.card?.let { listOf(it) }.orEmpty()
        val run = agentRun ?: return stepCard
        return stepCard + run.history.filterIsInstance<AiRunEvent.ConfirmationRequired>()
            .filter { run.isConfirmationPending(it.confirmation.id) }
            .map { PendingTestConfirmation(runId, laneId, it.confirmation.id, it.confirmation.call.name, it.confirmation.description) }
    }

    /** Answers a pending confirmation card. False when it is no longer pending. */
    fun resolveConfirmation(confirmationId: String, allow: Boolean): Boolean {
        scriptConfirmation?.takeIf { it.card.confirmationId == confirmationId }?.let { return it.answer.complete(allow) }
        val run = agentRun ?: return false
        val deferred = run.confirmations.remove(confirmationId) ?: return false
        return deferred.complete(allow)
    }
}
