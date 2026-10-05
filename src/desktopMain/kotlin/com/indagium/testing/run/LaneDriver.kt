package com.indagium.testing.run

import com.indagium.ai.AiInvestigationContext
import com.indagium.ai.AiRun
import com.indagium.ai.AiRunEvent
import com.indagium.ai.AiSession
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestSuite
import com.indagium.testing.store.TranscriptWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// Drives one StepSequence to its end.
//   EXTERNAL lane: nothing runs; a client drives the sequence through `test_lane_tool_call` and the watchdog closes
//   steps that outlive their timeout. The driver just waits for the sequence to end.
//   Agent lane: one agent RUN (a segment) works through the sequence. A new segment starts when the step timed out
//   (resuming at the next step), or when the agent failed or stopped without finishing its step (resuming at that same
//   step, at most maxAgentRestarts times). Every new segment gets a summary of the steps already done, fenced as
//   untrusted data. The case's tool-call limit is spent across the segments.

private sealed interface Signal {
    data class Sequence(val event: SequenceEvent) : Signal

    data object AgentStopped : Signal
}

private class Segment(val run: AiRun, val session: AiSession, val tail: TranscriptTail?, val tailJob: Job?) {
    val job: Job get() = checkNotNull(run.job) { "The agent run has no job." }
}

internal class LaneDriver(
    private val runId: String,
    private val laneId: String,
    private val suite: TestSuite,
    private val tuning: EngineTuning,
    private val agent: LaneAgent?,
    private val handle: LaneHandle,
    private val transcript: TranscriptWriter?,
    private val confirmationTimeoutMs: Long,
) {
    /** Runs [seq] from its first step until it ends. [case] is null for the steps of a shared-step hook. */
    suspend fun drive(seq: StepSequence, case: TestCase?, iteration: Int, budget: CaseBudget) {
        handle.sequence = seq
        try {
            seq.begin(0)
            if (seq.ended) return
            coroutineScope {
                val watchdog = launch { seq.watchdog(tuning.watchdogPollMs) }
                try {
                    if (agent == null) awaitEnd(seq) else agentLoop(this, seq, agent, case, iteration, budget)
                } finally {
                    watchdog.cancel()
                }
            }
        } finally {
            handle.sequence = null
        }
    }

    private suspend fun awaitEnd(seq: StepSequence) {
        for (event in seq.events) if (event == SequenceEvent.Ended) return
    }

    // ── Agent lanes ──────────────────────────────────────────────────

    private suspend fun agentLoop(scope: CoroutineScope, seq: StepSequence, agent: LaneAgent, case: TestCase?, iteration: Int, budget: CaseBudget) {
        var restarts = 0
        while (true) {
            if (budget.remaining() <= 0) {
                seq.abort(StepStatus.ERROR, "The case's tool-call limit was used up.")
                return
            }
            val segment = try {
                startSegment(scope, seq, agent, case, iteration, budget)
            } catch (invalid: IllegalArgumentException) {
                seq.abort(StepStatus.ERROR, "The agent could not be started: ${invalid.message}")
                return
            } catch (invalid: IllegalStateException) {
                seq.abort(StepStatus.ERROR, "The agent could not be started: ${invalid.message}")
                return
            }
            try {
                when (awaitOutcome(seq, segment)) {
                    SegmentOutcome.ENDED -> {
                        awaitAgentEnd(segment)
                        return
                    }
                    SegmentOutcome.MOVED -> Unit
                    SegmentOutcome.STOPPED -> {
                        restarts++
                        if (restarts > tuning.maxAgentRestarts) {
                            seq.abort(StepStatus.ERROR, "The agent stopped ${restarts - 1} time(s) without finishing its step: ${stopReason(segment.run)}")
                            return
                        }
                        seq.resetStepTimer()
                    }
                }
            } finally {
                closeSegment(segment)
                budget.spend(segment.run)
            }
        }
    }

    private enum class SegmentOutcome { ENDED, MOVED, STOPPED }

    private suspend fun awaitOutcome(seq: StepSequence, segment: Segment): SegmentOutcome {
        var cancelledForTimeout = false
        while (true) {
            val signal: Signal = if (cancelledForTimeout) {
                Signal.Sequence(seq.events.receive())
            } else {
                select {
                    seq.events.onReceive { Signal.Sequence(it) }
                    segment.job.onJoin { Signal.AgentStopped }
                }
            }
            when (signal) {
                Signal.AgentStopped -> return SegmentOutcome.STOPPED
                is Signal.Sequence -> when (signal.event) {
                    SequenceEvent.Ended -> return SegmentOutcome.ENDED
                    is SequenceEvent.Moved -> return SegmentOutcome.MOVED
                    SequenceEvent.TimedOut -> {
                        // The step is over for this agent. Stop it right away; the sequence may still wait for the user.
                        segment.run.cancel()
                        cancelledForTimeout = true
                    }
                }
            }
        }
    }

    /** After the case is over the agent only has to say goodbye; it is cancelled when it takes too long. */
    private suspend fun awaitAgentEnd(segment: Segment) {
        withTimeoutOrNull(tuning.agentEndGraceMs) { segment.job.join() } ?: segment.run.cancel()
    }

    private suspend fun startSegment(
        scope: CoroutineScope,
        seq: StepSequence,
        agent: LaneAgent,
        case: TestCase?,
        iteration: Int,
        budget: CaseBudget,
    ): Segment {
        val epoch = seq.newEpoch()
        val tabId = "testrun:$runId:$laneId:${seq.spec.caseId}:$iteration"
        val session = AiSession(tabId)
        val remaining = budget.remaining()
        val request = AgentSegmentRequest(
            session = session,
            prompt = lanePrompt(
                suite, case, seq.spec.caseName, seq.spec.steps, seq.currentIndex, seq.currentAttempt, seq.results(), seq.spec.setup,
            ),
            systemPrompt = LANE_SYSTEM_PROMPT,
            context = AiInvestigationContext(tabId),
            gateway = seq.gateway(epoch),
            toolCallLimit = remaining,
            maxTurns = remaining + seq.spec.steps.size * tuning.turnHeadroomPerStep,
            freeTools = LANE_FREE_TOOL_NAMES,
            confirmationTimeoutMs = confirmationTimeoutMs,
        )
        val run = agent.start(request)
        handle.agentRun = run
        val tail = transcript?.let { TranscriptTail(run, it) }
        val tailJob = tail?.let { scope.launch { it.follow(tuning.transcriptPollMs) } }
        return Segment(run, session, tail, tailJob)
    }

    /** Stops the run (if it still runs), waits for it to wind down and releases what it held. Safe when cancelled. */
    private suspend fun closeSegment(segment: Segment) {
        withContext(NonCancellable) {
            segment.run.cancel()
            withTimeoutOrNull(tuning.agentCancelWaitMs) { segment.job.join() }
            segment.tailJob?.cancel()
            segment.tail?.close()
            segment.session.deleteClaudeCodeWorkspace()
            if (handle.agentRun === segment.run) handle.agentRun = null
        }
    }

    private fun stopReason(run: AiRun): String =
        run.history.lastOrNull { it is AiRunEvent.Error }?.let { (it as AiRunEvent.Error).message } ?: "it ended without a final answer."
}
