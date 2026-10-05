package com.indagium.testing

import com.indagium.ai.LlmProvider
import com.indagium.ai.LlmRequest
import com.indagium.ai.LlmStreamEvent
import com.indagium.ai.LlmToolCall
import com.indagium.ai.ModelDiscoveryResult
import com.indagium.ai.ProviderCapabilities
import com.indagium.edition.EditionLimits
import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.HookItem
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.OnFailure
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.newCaseId
import com.indagium.testing.model.newCheckId
import com.indagium.testing.model.newHookId
import com.indagium.testing.model.newScriptId
import com.indagium.testing.model.newStepId
import com.indagium.testing.model.newSuiteId
import com.indagium.testing.run.CoordinatorDeps
import com.indagium.testing.run.EngineTuning
import com.indagium.testing.run.LaneAgentFactory
import com.indagium.testing.run.LaneDeviceOpener
import com.indagium.testing.run.ProviderLaneAgent
import com.indagium.testing.run.TestRunCoordinator
import com.indagium.testing.script.TestScriptRunner
import com.indagium.testing.store.IssueStore
import com.indagium.testing.store.TestRunStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory

// Shared pieces of the run-engine tests: a model that follows a script of turns, a one-lane harness around a real
// TestRunCoordinator with a fake adb, and small builders for suites. Synthetic data only.

internal const val TEST_PROFILE_ID = "profile-test"
private const val AWAIT_STEP_MS = 10_000L

internal val FAST_TUNING = EngineTuning(
    watchdogPollMs = 20L,
    agentEndGraceMs = 1_000L,
    agentCancelWaitMs = 2_000L,
    maxAgentRestarts = 1,
    transcriptPollMs = 30L,
)

internal typealias Turn = suspend FlowCollector<LlmStreamEvent>.(LlmRequest) -> Unit

/** A model that answers request number n with turn n (and ends with a plain "Done." once the script runs out). */
internal class TurnProvider(private val turns: List<Turn>) : LlmProvider {
    val requests = CopyOnWriteArrayList<LlmRequest>()
    private val next = AtomicInteger()

    override val capabilities = ProviderCapabilities(streaming = true, toolCalls = true, modelDiscovery = false)

    override suspend fun listModels(): ModelDiscoveryResult = ModelDiscoveryResult.Unavailable("not used")

    override fun streamChat(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        // The runner reuses one growing message list; keep what THIS request carried.
        requests += request.copy(messages = request.messages.toList())
        val turn = turns.getOrNull(next.getAndIncrement())
        if (turn == null) {
            emit(LlmStreamEvent.TextDelta("Done."))
            emit(LlmStreamEvent.Completed)
        } else {
            turn(request)
        }
    }
}

private val callIds = AtomicInteger()

/** A turn that calls one tool. */
internal fun toolTurn(name: String, argumentsJson: String = "{}", before: () -> Unit = {}): Turn = {
    before()
    emit(LlmStreamEvent.ToolCall(LlmToolCall("call-${callIds.incrementAndGet()}", name, argumentsJson)))
    emit(LlmStreamEvent.Completed)
}

internal fun finishTurn(status: String, observation: String = "ok", before: () -> Unit = {}): Turn =
    toolTurn("finish_step", """{"status":"$status","observation":"$observation"}""", before)

internal val textTurn: Turn = {
    emit(LlmStreamEvent.TextDelta("The case is over."))
    emit(LlmStreamEvent.Completed)
}

/** A turn that never answers: the run is stopped from outside (a timeout or a cancel). */
internal val hangingTurn: Turn = { kotlinx.coroutines.delay(AWAIT_STEP_MS * 6) }

internal fun step(
    action: String,
    expected: String = "It works",
    retries: Int = 0,
    onFailure: OnFailure = OnFailure.STOP_CASE,
    timeoutMs: Long = 30_000L,
    maxToolCalls: Int = 15,
    checks: List<StepCheck> = emptyList(),
) = TestStep(newStepId(), action, expected, checks, emptyList(), timeoutMs, retries, maxToolCalls, onFailure)

internal fun logAppears(regex: String, withinMs: Long = 2_000L) = StepCheck.LogAppears(newCheckId(), null, regex, withinMs)

internal fun caseOf(name: String, vararg steps: TestStep, setup: List<HookItem> = emptyList(), teardown: List<HookItem> = emptyList()) =
    TestCase(newCaseId(), name, setup = setup, teardown = teardown, steps = steps.toList())

internal fun suiteOf(vararg cases: TestCase, setup: List<HookItem> = emptyList(), teardown: List<HookItem> = emptyList()) =
    TestSuite(newSuiteId(), "Synthetic suite", targetPackage = "com.example.app", setup = setup, teardown = teardown, cases = cases.toList())

internal fun hostScript(toolName: String, template: String, permission: ScriptPermission = ScriptPermission.SETUP_TEARDOWN_ONLY) =
    TestScript(newScriptId(), toolName, commandTemplate = template, permission = permission)

internal fun scriptHook(script: TestScript) = HookItem.Script(newHookId(), script.id)

internal val testProfile = AiProviderProfile(
    id = TEST_PROFILE_ID, displayName = "Test model", baseUrl = "http://127.0.0.1:1234", model = "test-model", kind = AiProviderKind.OPENAI_COMPATIBLE,
)

internal fun agentLane(profileId: String = TEST_PROFILE_ID, serial: String = FIXTURE_SERIAL) =
    LaneConfig(kind = LaneKind.AGENT_PROFILE, profileId = profileId, deviceSerial = serial)

internal fun externalLane(serial: String = FIXTURE_SERIAL) = LaneConfig(kind = LaneKind.EXTERNAL, deviceSerial = serial)

/** A coordinator around a fake adb. [provider] answers the lane's agent; scripts run through /bin/sh. */
internal class RunHarness(
    val library: TestLibrary,
    val provider: TurnProvider = TurnProvider(emptyList()),
    val limits: EditionLimits = EditionLimits.UNLIMITED,
    val tuning: EngineTuning = FAST_TUNING,
    val deviceProblem: (String) -> String? = { null },
    val profile: AiProviderProfile = testProfile,
    agentFactory: LaneAgentFactory? = null,
    /** More AI profiles known to the run (a judge's). */
    extraProfiles: List<AiProviderProfile> = emptyList(),
    /** How lanes open their device; the default is one fixture adb for [FIXTURE_SERIAL]. */
    openDevice: LaneDeviceOpener? = null,
    /** Where runs of this harness put their issues; null: none are made. */
    val issues: IssueStore? = null,
) {
    val dir: File = createTempDirectory("indagium-run-test").toFile()
    val adb = ScriptedAdbRunner()
    val store = TestRunStore { File(dir, "test-runs") }
    val coordinator = TestRunCoordinator(
        CoordinatorDeps(
            library = { library },
            limits = { limits },
            store = store,
            profiles = { listOf(profile) + extraProfiles },
            apiKey = { "" },
            deviceProblem = { serial -> deviceProblem(serial) },
            openDevice = openDevice ?: LaneDeviceOpener { _, laneDir, _ -> openFixtureSession(adb, laneDir) },
            agentFactory = agentFactory ?: LaneAgentFactory { chosen, _ -> ProviderLaneAgent(provider, chosen) },
            scriptRunner = TestScriptRunner(hostShell = listOf("/bin/sh", "-c")),
            tuning = tuning.copy(persistDebounceMs = 50L),
            issues = issues,
        ),
    )

    fun config(
        suite: TestSuite,
        vararg lanes: LaneConfig = arrayOf(agentLane(profile.id)),
        caseIds: List<String>? = null,
        repeat: Int = 1,
        toolLimit: Int = 40,
    ) =
        RunConfig(suite.id, caseIds, lanes.toList(), repeat, toolLimit)

    fun close() {
        coordinator.close()
        dir.deleteRecursively()
    }

    fun emitLog(message: String, seconds: Int = 5) = adb.logcat.emit(logRow(message, seconds = seconds))
}

internal fun TestRun.allSteps(): List<StepResult> = lanes.flatMap { lane -> lane.cases.flatMap { it.steps } }

internal fun TestRun.caseResult(name: String): CaseResult = lanes.first().cases.first { it.caseName == name }

internal fun libraryOf(suite: TestSuite, vararg scripts: TestScript) = TestLibrary(suites = listOf(suite), scripts = scripts.toList())
