package com.indagium.ui

import com.indagium.ai.AiInvestigationContext
import com.indagium.ai.AiRun
import com.indagium.ai.AiRunEvent
import com.indagium.ai.AiSession
import com.indagium.ai.ManagedMcpServerLease
import com.indagium.ai.defaultAiProviderFactory
import com.indagium.ai.normalizeAiProviderProfiles
import com.indagium.debug.IndagiumToolGateway
import com.indagium.testing.authoring.RewriteGeneration
import com.indagium.testing.authoring.rewriteBudgetGuidance
import com.indagium.testing.authoring.rewritePromptPreamble
import com.indagium.testing.run.AgentSegmentRequest
import com.indagium.testing.run.CoordinatorDeps
import com.indagium.testing.run.EngineTuning
import com.indagium.testing.run.LaneAgentFactory
import com.indagium.testing.run.LaneDeviceOpener
import com.indagium.testing.run.TestRunCoordinator
import com.indagium.testing.run.defaultLaneAgentFactory
import com.indagium.testing.run.laneAccountRunnerFactory
import com.indagium.testing.run.laneBudgetGuidance
import com.indagium.testing.run.lanePromptPreamble
import com.indagium.testing.run.overrideProblems
import com.indagium.testing.run.withRunOverrides
import com.indagium.testing.script.TestScriptRunner
import com.indagium.testing.store.TestRunStore
import com.indagium.testing.store.readBoundedTestAsset
import com.indagium.testing.tracker.TrackerMcpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

// Connects the AI test-run coordinator (testing/run, which knows nothing of AppState) to the app: where runs are stored,
// which AI profiles and keys exist, how a lane opens its device and how it starts an agent. Production uses the
// defaults; tests replace pieces through AppState.testRunOverrides BEFORE the first use of the coordinator, so no
// real adb, CLI or model is needed.

/** Test seams for the run coordinator. A null piece means "the production behaviour". */
internal class TestRunOverrides(
    val openDevice: LaneDeviceOpener? = null,
    val agentFactory: LaneAgentFactory? = null,
    val deviceProblem: (suspend (serial: String) -> String?)? = null,
    val scriptRunner: TestScriptRunner? = null,
    val tuning: EngineTuning = EngineTuning(),
    /** Replaces the MCP client of the issue tracker (Settings > Issue tracker): tests need no network. */
    val trackerClients: TrackerMcpClientFactory? = null,
)

/** How an agent (a lane, the judge, the issue-filing job) is started: [TestRunOverrides.agentFactory] or the real launchers. */
internal fun AppState.productionAgentFactory(overrides: TestRunOverrides): LaneAgentFactory = overrides.agentFactory ?: defaultLaneAgentFactory(
    defaultAiProviderFactory,
    laneAccountRunnerFactory { run, gateway -> ManagedMcpServerLease.start(this, run, gateway) },
)

/**
 * The words around an agent run that only answers in text: what its session is called, what is said when it fails, ends early or says
 * nothing, and how an in-app model and an account agent are told about their tool budget.
 */
internal class AgentTextWording(
    val sessionPrefix: String,
    val failure: String,
    val unfinished: String,
    val empty: String,
    val budgetGuidance: (AiRun) -> String = ::laneBudgetGuidance,
    val promptPreamble: (AiRun) -> String = ::lanePromptPreamble,
)

/** One agent run whose answer is text: the profile ([model] / [reasoningEffort] replace its own), the words, its tools and its limits. */
internal class AgentTextRequest(
    val profileId: String,
    val model: String?,
    val reasoningEffort: String?,
    val prompt: String,
    val systemPrompt: String,
    val gateway: IndagiumToolGateway,
    val toolCallLimit: Int,
    val maxTurns: Int,
    val timeoutMs: Long,
    val wording: AgentTextWording,
)

private val STEP_DRAFT_WORDING = AgentTextWording(
    sessionPrefix = "test-step-draft",
    failure = "The provider could not draft steps",
    unfinished = "The provider did not finish the draft request.",
    empty = "The provider returned no step draft.",
)

/** Drafts steps through an existing profile with an empty MCP gateway and no host tools or device session. */
internal suspend fun AppState.generateTestStepDraft(profileId: String, prompt: String, model: String? = null, reasoningEffort: String? = null): String =
    runAgentForText(
        AgentTextRequest(
            profileId = profileId,
            model = model,
            reasoningEffort = reasoningEffort,
            prompt = prompt,
            systemPrompt =
                "You draft Android QA test steps. Return only the requested JSON. You have no device or tools; " +
                    "do not claim to have run or observed anything.",
            gateway = IndagiumToolGateway(emptyList(), emptyMap()),
            toolCallLimit = 1,
            maxTurns = 2,
            timeoutMs = TEST_STEP_DRAFT_TIMEOUT_MS,
            wording = STEP_DRAFT_WORDING,
        ),
    )

private val RECORDING_REWRITE_WORDING = AgentTextWording(
    sessionPrefix = "test-recording-rewrite",
    failure = "The provider could not rewrite the recording",
    unfinished = "The provider did not finish the rewrite request.",
    empty = "The provider returned no rewrite.",
    budgetGuidance = ::rewriteBudgetGuidance,
    promptPreamble = ::rewritePromptPreamble,
)

/** Rewrites a recording through an existing profile; its only tools read the recording ([RewriteGeneration.gateway]). */
internal suspend fun AppState.generateRecordingRewrite(request: RewriteGeneration): String = runAgentForText(
    AgentTextRequest(
        profileId = request.profileId,
        model = request.model,
        reasoningEffort = request.effort,
        prompt = request.prompt,
        systemPrompt = request.systemPrompt,
        gateway = request.gateway,
        toolCallLimit = request.toolCallLimit,
        maxTurns = request.maxTurns,
        timeoutMs = RECORDING_REWRITE_TIMEOUT_MS,
        wording = RECORDING_REWRITE_WORDING,
    ),
)

/**
 * Runs one agent through the lane launchers (an in-app model over HTTP, or Claude Code / Codex as the signed-in account, which
 * reach [AgentTextRequest.gateway] through the managed MCP lease) and returns what it answered after its last tool call. Cancelling
 * the caller cancels the run; the run and its workspace are cleaned up either way.
 */
internal suspend fun AppState.runAgentForText(request: AgentTextRequest): String {
    val base = normalizeAiProviderProfiles(settings.aiProviderProfiles).firstOrNull { it.id == request.profileId }
        ?: error("Choose an existing provider profile.")
    val problems = overrideProblems("Model", base, request.model, request.reasoningEffort)
    check(problems.isEmpty()) { problems.joinToString(" ") }
    val profile = base.withRunOverrides(request.model, request.reasoningEffort)
    val agent = productionAgentFactory(testRunOverrides).create(profile, aiProviderApiKey(request.profileId))
    val tabId = "${request.wording.sessionPrefix}:${java.util.UUID.randomUUID()}"
    val session = com.indagium.ai.AiSession(tabId)
    var run: AiRun? = null
    try {
        val activeRun = agent.start(
            AgentSegmentRequest(
                session = session,
                prompt = request.prompt,
                systemPrompt = request.systemPrompt,
                context = AiInvestigationContext(tabId),
                gateway = request.gateway,
                toolCallLimit = request.toolCallLimit,
                maxTurns = request.maxTurns,
                freeTools = emptySet(),
                confirmationTimeoutMs = 1_000,
                budgetGuidance = request.wording.budgetGuidance,
                promptPreamble = request.wording.promptPreamble,
            ),
        )
        run = activeRun
        withTimeout(request.timeoutMs) { activeRun.job?.join() }
        val error = activeRun.history.filterIsInstance<AiRunEvent.Error>().lastOrNull()
        check(error == null) { "${request.wording.failure}: ${error?.message}" }
        check(activeRun.job?.isActive != true) { request.wording.unfinished }
        return finalAnswerText(activeRun.history).also { check(it.isNotBlank()) { request.wording.empty } }
    } finally {
        run?.cancel()
        withContext(NonCancellable) { withTimeoutOrNull(TEST_STEP_DRAFT_CLEANUP_MS) { run?.job?.join() } }
        session.deleteClaudeCodeWorkspace()
        agent.close()
    }
}

/** The assistant text after the run's last tool call: what an agent said between tool calls is thinking aloud, not the answer. */
internal fun finalAnswerText(history: List<AiRunEvent>): String {
    val lastTool = history.indexOfLast { it is AiRunEvent.ToolRequested || it is AiRunEvent.ToolCompleted }
    return history.drop(lastTool + 1).filterIsInstance<AiRunEvent.AssistantDelta>().joinToString("") { it.text }
}

private const val TEST_STEP_DRAFT_TIMEOUT_MS = 120_000L
private const val TEST_STEP_DRAFT_CLEANUP_MS = 2_000L

/** A little longer than the service's own timeout (180 s), so the service answers first with its message. */
private const val RECORDING_REWRITE_TIMEOUT_MS = 190_000L

private const val LIVE_CAPTURE_PROBLEM = "is held by the live capture in the main window; stop that capture or choose another device."

/** Builds the app's coordinator. [baseDir] is where run folders go; [onChanged] is called after every change of a run. */
internal fun AppState.createTestRunCoordinator(overrides: TestRunOverrides, baseDir: () -> File, onChanged: () -> Unit): TestRunCoordinator {
    val openDevice = overrides.openDevice ?: ProductionLaneOpener(this)
    val agentFactory = productionAgentFactory(overrides)
    val deviceProblem = overrides.deviceProblem ?: { serial -> productionDeviceProblem(serial) }
    return TestRunCoordinator(
        CoordinatorDeps(
            library = { testLibrary },
            limits = { editionService.limits() },
            store = TestRunStore(baseDir),
            profiles = { normalizeAiProviderProfiles(settings.aiProviderProfiles) },
            apiKey = ::aiProviderApiKey,
            deviceProblem = deviceProblem,
            openDevice = openDevice,
            agentFactory = agentFactory,
            scriptRunner = overrides.scriptRunner ?: TestScriptRunner(),
            tuning = overrides.tuning,
            goldenImage = { suiteId, assetPath -> testGoldenImageFile(suiteId, assetPath)?.let(::readBoundedTestAsset) },
            issues = issueStore,
            goldenFile = { suiteId, assetPath -> testGoldenImageFile(suiteId, assetPath) },
        ),
        onChanged = onChanged,
    )
}

@Suppress("TooGenericExceptionCaught") // Listing devices runs adb; any failure becomes the message the user sees.
private suspend fun AppState.productionDeviceProblem(serial: String): String? = withContext(Dispatchers.IO) {
    try {
        val devices = aiCaptureDevices()
        val device = devices.firstOrNull { it.serial == serial }
        when {
            device == null -> "Device $serial is not connected."
            !device.available -> "Device $serial is not ready (${device.state})."
            serial == liveCaptureSerial() -> "Device $serial $LIVE_CAPTURE_PROBLEM"
            laneCaptureOnDevice(serial) != null ->
                "Device $serial is recording for an AI test run (run ${laneCaptureOnDevice(serial)?.runId}); wait for it to finish."
            else -> null
        }
    } catch (failure: Exception) {
        "Could not list the connected devices: ${failure.message ?: failure::class.simpleName}"
    }
}
