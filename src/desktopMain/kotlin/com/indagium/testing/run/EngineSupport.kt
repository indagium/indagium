package com.indagium.testing.run

import com.indagium.ai.AiRun
import com.indagium.ai.AiRunEvent
import com.indagium.model.AiProviderProfile
import com.indagium.testing.device.TestDeviceSession
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.script.TestScriptRunner
import com.indagium.testing.store.RUN_SAVE_DEBOUNCE_MS
import com.indagium.testing.store.TestRunStore
import com.indagium.testing.store.TranscriptWriter
import kotlinx.coroutines.delay
import java.io.File

// Seams and small helpers of the run engine: how a lane's device is opened, the engine's timings (shortened by tests),
// and the tail that copies an agent run's events into the lane's transcript.

/** Opens the headless device lane of one run lane. Production wires adb and the live-capture guard; tests give a fake adb. */
internal fun interface LaneDeviceOpener {
    suspend fun open(serial: String, laneDir: File, recordVideo: Boolean): TestDeviceSession
}

internal data class EngineTuning(
    /** How often the step timer is checked. */
    val watchdogPollMs: Long = 250L,
    /** How long an agent may take to end after the case is over before its run is cancelled. */
    val agentEndGraceMs: Long = 3_000L,
    /** How long a cancelled agent run may take to wind down before the engine moves on. */
    val agentCancelWaitMs: Long = 5_000L,
    /** How often a lane agent is started again after it failed or stopped without finishing its step. */
    val maxAgentRestarts: Int = 2,
    /** How often the transcript is brought up to date from the running agent. */
    val transcriptPollMs: Long = 250L,
    /** How long run.json waits after a change before it is written (a finishing run writes at once). */
    val persistDebounceMs: Long = RUN_SAVE_DEBOUNCE_MS,
    /** Claude Code's turn allowance on top of the tool budget, per step: protocol calls are free but are still turns. */
    val turnHeadroomPerStep: Int = 12,
)

/** Everything the engine needs from its owner. */
internal class EngineDeps(
    val openDevice: LaneDeviceOpener,
    /** The agent of an AGENT_PROFILE lane. Not called for an EXTERNAL lane. May throw [IllegalStateException] to refuse. */
    val agentFor: (LaneConfig) -> LaneAgent,
    val scriptRunner: TestScriptRunner,
    val store: TestRunStore,
    val tuning: EngineTuning = EngineTuning(),
    val wallClock: () -> Long = System::currentTimeMillis,
)

/** The first profile with [profileId], or null. */
internal fun List<AiProviderProfile>.profileOrNull(profileId: String?): AiProviderProfile? = firstOrNull { it.id == profileId }

private const val MAX_LOGGED_ARGUMENT_CHARS = 2_000
private const val MAX_LOGGED_RESULT_CHARS = 2_000

/**
 * Copies the events of [run] into [writer] as JSON lines, in order. The run's history is read, not its event flow, so
 * nothing is lost to a late start. Assistant text arrives in many small deltas and is written as one line.
 */
internal class TranscriptTail(private val run: AiRun, private val writer: TranscriptWriter) {
    private var written = 0
    private val assistant = StringBuilder()

    /** Writes everything the run has recorded since the last call. The last assistant text stays buffered until [close]. */
    @Synchronized
    fun flush() {
        val events = run.history
        events.drop(written).forEach(::write)
        written = events.size
    }

    /** Writes the rest, including a trailing assistant text. */
    @Synchronized
    fun close() {
        flush()
        flushAssistant()
    }

    suspend fun follow(pollMs: Long) {
        while (true) {
            flush()
            delay(pollMs)
        }
    }

    private fun flushAssistant() {
        if (assistant.isNotEmpty()) writer.append("assistant", mapOf("text" to assistant.toString()))
        assistant.clear()
    }

    private fun write(event: AiRunEvent) {
        if (event is AiRunEvent.AssistantDelta) {
            assistant.append(event.text)
            return
        }
        flushAssistant()
        when (event) {
            is AiRunEvent.ToolRequested -> writer.append(
                "tool_call",
                mapOf("tool" to event.call.name, "arguments" to event.call.argumentsJson.take(MAX_LOGGED_ARGUMENT_CHARS)),
            )
            is AiRunEvent.ToolCompleted -> writer.append(
                "tool_result",
                mapOf("tool" to event.call.name, "result" to event.resultPreview.take(MAX_LOGGED_RESULT_CHARS), "truncated" to event.resultTruncated),
            )
            is AiRunEvent.AgentProgress -> writer.append("assistant", mapOf("text" to event.text))
            is AiRunEvent.ConfirmationRequired ->
                writer.append("confirmation", mapOf("tool" to event.confirmation.call.name, "description" to event.confirmation.description))
            is AiRunEvent.Error -> writer.append("error", mapOf("message" to event.message))
            is AiRunEvent.Status -> writer.append("status", mapOf("text" to event.text))
            is AiRunEvent.Usage ->
                writer.append("usage", mapOf("input" to event.inputTokens, "output" to event.outputTokens, "total" to event.totalTokens))
            AiRunEvent.Cancelled -> writer.append("cancelled")
            AiRunEvent.Done -> writer.append("done")
            is AiRunEvent.AssistantDelta -> Unit
        }
    }
}

/** A case's share of the tool-call limit, spent across the agent runs (restarts) that serve it. */
internal class CaseBudget(private val limit: Int) {
    private var used = 0

    @Synchronized
    fun remaining(): Int = limit - used

    @Synchronized
    fun spend(run: AiRun) {
        used += run.toolCallBudget.snapshot().totalUsed
    }
}

internal fun TestRunStore.relativeToRun(runId: String, file: File): String = file.relativeTo(runDir(runId)).invariantSeparatorsPath
