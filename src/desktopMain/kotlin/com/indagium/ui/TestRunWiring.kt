package com.indagium.ui

import com.indagium.ai.AiInvestigationContext
import com.indagium.ai.AiRun
import com.indagium.ai.AiRunEvent
import com.indagium.ai.AiSession
import com.indagium.ai.ManagedMcpServerLease
import com.indagium.ai.defaultAiProviderFactory
import com.indagium.ai.normalizeAiProviderProfiles
import com.indagium.debug.IndagiumToolGateway
import com.indagium.testing.run.AgentSegmentRequest
import com.indagium.testing.run.CoordinatorDeps
import com.indagium.testing.run.EngineTuning
import com.indagium.testing.run.LaneAgentFactory
import com.indagium.testing.run.LaneDeviceOpener
import com.indagium.testing.run.TestRunCoordinator
import com.indagium.testing.run.defaultLaneAgentFactory
import com.indagium.testing.run.laneAccountRunnerFactory
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

/** Drafts steps through an existing profile with an empty MCP gateway and no host tools or device session. */
internal suspend fun AppState.generateTestStepDraft(profileId: String, prompt: String): String {
    val profile = normalizeAiProviderProfiles(settings.aiProviderProfiles).firstOrNull { it.id == profileId }
        ?: error("Choose an existing provider profile.")
    val agent = productionAgentFactory(testRunOverrides).create(profile, aiProviderApiKey(profileId))
    val tabId = "test-step-draft:${java.util.UUID.randomUUID()}"
    val session = com.indagium.ai.AiSession(tabId)
    var run: AiRun? = null
    try {
        val gateway = IndagiumToolGateway(emptyList(), emptyMap())
        val activeRun = agent.start(
            AgentSegmentRequest(
                session = session,
                prompt = prompt,
                systemPrompt =
                    "You draft Android QA test steps. Return only the requested JSON. You have no device or tools; " +
                        "do not claim to have run or observed anything.",
                context = AiInvestigationContext(tabId),
                gateway = gateway,
                toolCallLimit = 1,
                maxTurns = 2,
                freeTools = emptySet(),
                confirmationTimeoutMs = 1_000,
            ),
        )
        run = activeRun
        withTimeout(TEST_STEP_DRAFT_TIMEOUT_MS) { activeRun.job?.join() }
        val error = activeRun.history.filterIsInstance<AiRunEvent.Error>().lastOrNull()
        check(error == null) { "The provider could not draft steps: ${error?.message}" }
        check(activeRun.job?.isActive != true) { "The provider did not finish the draft request." }
        return activeRun.history.filterIsInstance<AiRunEvent.AssistantDelta>().joinToString("") { it.text }
            .also { check(it.isNotBlank()) { "The provider returned no step draft." } }
    } finally {
        run?.cancel()
        withContext(NonCancellable) { withTimeoutOrNull(TEST_STEP_DRAFT_CLEANUP_MS) { run?.job?.join() } }
        session.deleteClaudeCodeWorkspace()
        agent.close()
    }
}

private const val TEST_STEP_DRAFT_TIMEOUT_MS = 120_000L
private const val TEST_STEP_DRAFT_CLEANUP_MS = 2_000L

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
