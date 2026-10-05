package com.indagium.testing

import com.indagium.ai.LlmProvider
import com.indagium.ai.LlmRequest
import com.indagium.ai.LlmRole
import com.indagium.ai.LlmStreamEvent
import com.indagium.ai.LlmToolCall
import com.indagium.ai.ModelDiscoveryResult
import com.indagium.ai.ProviderCapabilities
import com.indagium.capture.CaptureRecorder
import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.testing.device.TestDeviceSession
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.run.LaneAgentFactory
import com.indagium.testing.run.LaneDeviceOpener
import com.indagium.testing.run.ProviderLaneAgent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// Shared pieces of the parallel-lane and judge tests: one fake adb per device serial, models that answer from the REQUEST
// (so several lanes and judges can use one provider at the same time without a shared script), and small helpers.
// Synthetic data only.

internal const val JUDGE_PROFILE_ID = "profile-judge"
internal const val LANE_A_PROFILE_ID = "profile-lane-a"
internal const val LANE_B_PROFILE_ID = "profile-lane-b"
private const val FAST_WATCHDOG_MS = 20L

internal fun profileOf(id: String, name: String = id) =
    AiProviderProfile(id = id, displayName = name, baseUrl = "http://127.0.0.1:1234", model = "test-model", kind = AiProviderKind.OPENAI_COMPATIBLE)

internal val judgeProfile = profileOf(JUDGE_PROFILE_ID, "Judge model")

internal suspend fun openSerialSession(
    runner: ScriptedAdbRunner,
    serial: String,
    laneDir: File,
    isLiveCaptureSerial: (String) -> Boolean = { false },
): TestDeviceSession = TestDeviceSession.open(
    serial = serial,
    laneDir = laneDir,
    tools = fixtureTools(runner),
    runner = runner,
    isLiveCaptureSerial = isLiveCaptureSerial,
    recorderFactory = { root, processRunner -> CaptureRecorder(root, processRunner, watchdogIntervalMs = FAST_WATCHDOG_MS) },
)

/** One fake adb per device serial; [opener] gives a lane the fake of its serial. [beforeOpen] runs first (a test's latch). */
internal class DeviceFarm(
    private val isLiveCaptureSerial: (String) -> Boolean = { false },
    private val beforeOpen: suspend (serial: String) -> Unit = {},
) {
    private val adbs = ConcurrentHashMap<String, ScriptedAdbRunner>()

    fun adb(serial: String): ScriptedAdbRunner = adbs.getOrPut(serial) { ScriptedAdbRunner() }

    val opener = LaneDeviceOpener { serial, laneDir, _ ->
        beforeOpen(serial)
        openSerialSession(adb(serial), serial, laneDir, isLiveCaptureSerial)
    }
}

private val callCounter = AtomicInteger()

private fun call(name: String, arguments: JsonObject = buildJsonObject {}) =
    LlmStreamEvent.ToolCall(LlmToolCall("call-${callCounter.incrementAndGet()}", name, arguments.toString()))

/**
 * A lane agent that finishes step i with `finishes[i]` (status, observation) and then says it is done. It decides from the
 * number of tool results already in the request, so any number of lanes may share one instance.
 */
internal class AutoAgentProvider(
    private val finishes: List<Pair<String, String>>,
    /** Called with the number of tool results the request carries, before the model answers (a test's hook). */
    private val beforeTurn: (answered: Int) -> Unit = {},
) : LlmProvider {
    val requests = CopyOnWriteArrayList<LlmRequest>()

    override val capabilities = ProviderCapabilities(streaming = true, toolCalls = true, modelDiscovery = false)

    override suspend fun listModels(): ModelDiscoveryResult = ModelDiscoveryResult.Unavailable("not used")

    override fun streamChat(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        requests += request.copy(messages = request.messages.toList())
        val answered = request.messages.count { it.role == LlmRole.TOOL }
        beforeTurn(answered)
        val finish = finishes.getOrNull(answered)
        if (finish == null) {
            emit(LlmStreamEvent.TextDelta("The case is over."))
        } else {
            emit(call("finish_step", buildJsonObject { put("status", finish.first); put("observation", finish.second) }))
        }
        emit(LlmStreamEvent.Completed)
    }
}

/** What a judge was asked, read from the judge prompt. [lanes] are the lane labels of a comparison. */
internal data class JudgeAsk(val comparison: Boolean, val stepNumber: Int, val lanes: List<String>)

/** What the scripted judge submits. [laneVerdicts] (label to verdict) is used by a comparison. */
internal data class JudgeAnswer(
    val verdict: String = "pass",
    val classification: String = "unknown",
    val reasoning: String = "The screen matches what is expected.",
    val fix: JsonObject? = null,
    val laneVerdicts: Map<String, String> = emptyMap(),
)

/**
 * A judge model: looks at the brief, then at a screenshot, then submits what [answer] says. Stateless (it counts the tool
 * results in the request) so it can serve several judge runs at once. Keeps every request for the blindness checks.
 */
internal class ScriptedJudgeProvider(private val answer: (JudgeAsk) -> JudgeAnswer = { JudgeAnswer() }) : LlmProvider {
    val requests = CopyOnWriteArrayList<LlmRequest>()

    override val capabilities = ProviderCapabilities(streaming = true, toolCalls = true, modelDiscovery = false)

    override suspend fun listModels(): ModelDiscoveryResult = ModelDiscoveryResult.Unavailable("not used")

    private fun askOf(request: LlmRequest): JudgeAsk {
        val prompt = request.messages.first { it.role == LlmRole.USER }.content.orEmpty()
        val number = Regex("step (\\d+) of").find(prompt)?.groupValues?.get(1)?.toInt() ?: 0
        val lanes = Regex("\\((Lane [^)]*)\\)").find(prompt)?.groupValues?.get(1)?.split(", ").orEmpty()
        return JudgeAsk(prompt.startsWith("Compare"), number, lanes)
    }

    override fun streamChat(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        requests += request.copy(messages = request.messages.toList())
        val ask = askOf(request)
        when (request.messages.count { it.role == LlmRole.TOOL }) {
            0 -> emit(call("get_step_brief"))
            1 -> emit(call("get_step_screenshot", buildJsonObject { if (ask.comparison) put("lane", ask.lanes.first()) }))
            2 -> emit(submission(ask, answer(ask)))
            else -> emit(LlmStreamEvent.TextDelta("Submitted."))
        }
        emit(LlmStreamEvent.Completed)
    }

    private fun submission(ask: JudgeAsk, answer: JudgeAnswer): LlmStreamEvent.ToolCall {
        val arguments = buildJsonObject {
            if (ask.comparison) {
                put("verdicts", buildJsonObject { ask.lanes.forEach { lane -> put(lane, answer.laneVerdicts[lane] ?: answer.verdict) } })
                put("explanation", answer.reasoning)
            } else {
                put("verdict", answer.verdict)
                put("reasoning", answer.reasoning)
            }
            put("classification", answer.classification)
            answer.fix?.let { put("suggestedStepFix", it) }
        }
        return call(if (ask.comparison) "submit_comparison" else "submit_verdict", arguments)
    }

    /** Everything the judge was ever shown: every message of every request, system prompts and tool results included. */
    fun everythingSeen(): String =
        requests.joinToString("\n") { request ->
            request.messages.joinToString("\n") { it.content.orEmpty() + it.toolCalls.joinToString { call -> call.argumentsJson } }
        }
}

/** A lane agent factory: the provider of each profile id; the judge's profile uses [judge]. */
internal fun agentsByProfile(providers: Map<String, LlmProvider>): LaneAgentFactory = LaneAgentFactory { chosen, _ ->
    ProviderLaneAgent(providers[chosen.id] ?: error("No test provider for profile ${chosen.id}"), chosen)
}

internal fun TestRun.stepStatuses(laneIndex: Int, caseName: String): List<StepStatus> =
    lanes[laneIndex].cases.first { it.caseName == caseName }.steps.filterNot { it.setup }.map { it.status }
