package com.indagium.testing.run

import com.indagium.ai.AiRun
import com.indagium.ai.AiRunEvent
import com.indagium.capture.CaptureSettings
import com.indagium.model.AiProviderProfile
import com.indagium.testing.device.TestDeviceSession
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.driverLabel
import com.indagium.testing.script.TestScriptRunner
import com.indagium.testing.store.IssueStore
import com.indagium.testing.store.RUN_SAVE_DEBOUNCE_MS
import com.indagium.testing.store.TestRunStore
import com.indagium.testing.store.TranscriptWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.io.File

// Seams and small helpers of the run engine: how a lane's device is opened, the engine's timings (shortened by tests),
// and the tail that copies an agent run's events into the lane's transcript.

/**
 * What a lane's device is opened for: the lane's identity (so its capture tab can name it), where its files go, what to record and
 * whether a real live capture tab is opened for it.
 */
internal class LaneOpenRequest(
    val runId: String,
    val laneId: String,
    val serial: String,
    val laneDir: File,
    /** Who drives the lane ("Claude Code · opus · high effort", "external"), for the title of its capture tab. */
    val agentLabel: String,
    val capture: CaptureSettings,
    val openTab: Boolean,
)

/**
 * Opens the device lane of one run lane. Production starts a real capture (ui/LaneCaptures.kt); tests give a fake adb. A test that
 * only cares about the device overrides the three-argument [open]; the engine calls [open] with the whole [LaneOpenRequest].
 */
internal fun interface LaneDeviceOpener {
    suspend fun open(serial: String, laneDir: File, recordVideo: Boolean): TestDeviceSession

    suspend fun open(request: LaneOpenRequest): TestDeviceSession = open(request.serial, request.laneDir, request.capture.recordVideo)
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
    /** How long a judge may take for one step or comparison; a judge that is slower is inconclusive. */
    val judgeTimeoutMs: Long = JUDGE_TIMEOUT_MS,
    /** Turns a judge run gets on top of its tool budget (submitting the verdict is free but is still a turn). */
    val judgeTurnHeadroom: Int = JUDGE_TURN_HEADROOM,
    /** How many different devices run at once; lanes that share a device always run one after another. */
    val maxParallelDevices: Int = MAX_PARALLEL_DEVICES,
)

/** An inline judge blocks the agent's finish_step, so it is bounded; account agents' MCP clients give up on long tool calls. */
const val JUDGE_TIMEOUT_MS = 60_000L
const val JUDGE_TURN_HEADROOM = 4

/** Everything the engine needs from its owner. */
@Suppress("LongParameterList") // This is the lane engine's explicit dependency bundle.
internal class EngineDeps(
    val openDevice: LaneDeviceOpener,
    /** The agent of an AGENT_PROFILE lane. Not called for an EXTERNAL lane. May throw [IllegalStateException] to refuse. */
    val agentFor: (LaneConfig) -> LaneAgent,
    val scriptRunner: TestScriptRunner,
    val store: TestRunStore,
    val tuning: EngineTuning = EngineTuning(),
    val wallClock: () -> Long = System::currentTimeMillis,
    /** The judge's agent, or null when no judge runs. Called once per run; may throw [IllegalStateException] to refuse. */
    val judgeAgent: (() -> LaneAgent)? = null,
    /** The image of a golden-screenshot example (suite id, asset path), or null when it is not available. Blocking: call on IO. */
    val goldenImage: (suiteId: String, assetPath: String) -> ByteArray? = { _, _ -> null },
    /** Pause all: lanes stop at their next step boundary until resumed. */
    val pauseGate: RunPauseGate = RunPauseGate(),
    /** Where issues go. Null: no draft issue is made for a CREATE_ISSUE_AND_CONTINUE step and no linked issue is re-checked. */
    val issues: IssueStore? = null,
    /** The file of a golden-screenshot example (suite id, asset path), or null; its image becomes evidence of an issue. */
    val goldenFile: (suiteId: String, assetPath: String) -> File? = { _, _ -> null },
    /** Marks an external protocol job after its paid dispatch has passed the sequence guard. */
    val externalDispatchAdmitted: (Job?, String?, Int) -> Unit = { _, _, _ -> },
    /** How a lane is named where a person reads it (its capture tab): the profile's name with the model and effort it runs with. */
    val laneLabel: (LaneConfig) -> String = { lane -> lane.driverLabel().ifBlank { "agent" } },
)

/** The first profile with [profileId], or null. */
internal fun List<AiProviderProfile>.profileOrNull(profileId: String?): AiProviderProfile? = firstOrNull { it.id == profileId }

/**
 * The profile as one lane (or the judge) of a run uses it: [model] and [reasoningEffort] replace the profile's own when given. A
 * blank [model] counts as "not given"; an empty [reasoningEffort] is a real choice (the model's default effort).
 */
internal fun AiProviderProfile.withRunOverrides(model: String?, reasoningEffort: String?): AiProviderProfile = copy(
    model = model?.trim()?.takeIf { it.isNotEmpty() } ?: this.model,
    reasoningEffort = reasoningEffort?.trim() ?: this.reasoningEffort,
)

private const val MAX_LOGGED_ARGUMENT_CHARS = 2_000
private const val MAX_LOGGED_RESULT_CHARS = 2_000

/**
 * Copies the events of [run] into [writer] as JSON lines, in order. The run's history is read, not its event flow, so
 * nothing is lost to a late start. Assistant text arrives in many small deltas and is written as one line.
 */
internal class TranscriptTail(
    private val run: AiRun,
    private val writer: TranscriptWriter,
    /** Fields added to every line (a judge's transcript says which step it judged). */
    private val extra: Map<String, Any?> = emptyMap(),
) {
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
        if (assistant.isNotEmpty()) append("assistant", mapOf("text" to assistant.toString()))
        assistant.clear()
    }

    private fun append(kind: String, fields: Map<String, Any?> = emptyMap()) = writer.append(kind, extra + fields)

    private fun write(event: AiRunEvent) {
        if (event is AiRunEvent.AssistantDelta) {
            assistant.append(event.text)
            return
        }
        flushAssistant()
        when (event) {
            is AiRunEvent.ToolRequested -> append(
                "tool_call",
                mapOf("tool" to event.call.name, "arguments" to event.call.argumentsJson.take(MAX_LOGGED_ARGUMENT_CHARS)),
            )
            is AiRunEvent.ToolCompleted -> append(
                "tool_result",
                mapOf("tool" to event.call.name, "result" to event.resultPreview.take(MAX_LOGGED_RESULT_CHARS), "truncated" to event.resultTruncated),
            )
            is AiRunEvent.AgentProgress -> append("assistant", mapOf("text" to event.text))
            is AiRunEvent.ConfirmationRequired ->
                append("confirmation", mapOf("tool" to event.confirmation.call.name, "description" to event.confirmation.description))
            is AiRunEvent.Error -> append("error", mapOf("message" to event.message))
            is AiRunEvent.Status -> append("status", mapOf("text" to event.text))
            is AiRunEvent.Usage -> append(
                "usage",
                mapOf(
                    "input" to event.inputTokens,
                    "output" to event.outputTokens,
                    "total" to event.totalTokens,
                    "cachedInput" to event.cachedInputTokens,
                    "cachedInputIncludedInInput" to event.cachedInputIncludedInInput,
                    "cacheCreationInput" to event.cacheCreationInputTokens,
                    "reasoningOutput" to event.reasoningOutputTokens,
                    "aggregation" to event.aggregation.name,
                ),
            )
            AiRunEvent.Cancelled -> append("cancelled")
            AiRunEvent.Done -> append("done")
            is AiRunEvent.AssistantDelta, is AiRunEvent.ToolExecutionStarted, is AiRunEvent.UsageRequestStarted -> Unit
        }
    }
}

/** A case's share of the tool-call limit, spent across the agent runs (restarts) that serve it. */
internal class CaseBudget(private val limit: Int) {
    private var used = 0
    private var wasExceeded = false

    @Synchronized
    fun remaining(): Int = limit - used

    fun limit(): Int = limit

    @Synchronized
    fun tryDispatch(): Boolean {
        if (used >= limit) {
            wasExceeded = true
            return false
        }
        used++
        return true
    }

    @Synchronized
    fun exhausted(): Boolean = wasExceeded
}

internal fun TestRunStore.relativeToRun(runId: String, file: File): String = file.relativeTo(runDir(runId)).invariantSeparatorsPath
