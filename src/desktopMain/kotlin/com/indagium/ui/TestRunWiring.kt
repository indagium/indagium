package com.indagium.ui

import com.indagium.ai.AiInvestigationContext
import com.indagium.ai.AiRun
import com.indagium.ai.AiRunEvent
import com.indagium.ai.AiSession
import com.indagium.ai.ManagedMcpServerLease
import com.indagium.ai.aiRunTiming
import com.indagium.ai.aiUsageFromHistory
import com.indagium.ai.defaultAiProviderFactory
import com.indagium.ai.logSummary
import com.indagium.ai.normalizeAiProviderProfiles
import com.indagium.debug.AppLogger
import com.indagium.debug.IndagiumToolGateway
import com.indagium.testing.authoring.IMPROVE_BUDGET_GUIDANCE
import com.indagium.testing.authoring.IMPROVE_PROMPT_PREAMBLE
import com.indagium.testing.authoring.ImproveGeneration
import com.indagium.testing.authoring.ImproveRunSource
import com.indagium.testing.authoring.RewriteAttachment
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
import com.indagium.testing.run.profileProblems
import com.indagium.testing.run.withRunOverrides
import com.indagium.testing.script.TestScriptRunner
import com.indagium.testing.store.TestRunStore
import com.indagium.testing.store.readBoundedTestAsset
import com.indagium.testing.tracker.TrackerMcpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    laneAccountRunnerFactory(
        leaseFactory = { run, gateway -> ManagedMcpServerLease.start(this, run, gateway) },
        routeLeaseFactory = { route -> ManagedMcpServerLease.startSwitchable(this, route) },
    ),
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
    /** What the log says when the agent takes no images and runs the attachment's fallback prompt. */
    val noImagesNote: String = "rewrite_brief: this agent takes no images; the tools fetch the evidence",
)

/** One agent run whose answer is text: the profile ([model] / [reasoningEffort] replace its own), the words, its tools and its limits. */
@Suppress("LongParameterList") // One run request: the profile, the words, its tools, its limits and the images that go with it.
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
    val onUsage: (com.indagium.model.AiUsageStats) -> Unit = {},
    /** Images that go with [prompt] when the agent takes them; otherwise the run is the attachment's fallback (see [runAgentForText]). */
    val attachment: RewriteAttachment? = null,
    val onVisualEvidenceDelivered: (Map<Int, Set<String>>) -> Unit = {},
)

private val STEP_DRAFT_WORDING = AgentTextWording(
    sessionPrefix = "test-step-draft",
    failure = "The provider could not draft steps",
    unfinished = "The provider did not finish the draft request.",
    empty = "The provider returned no step draft.",
)

/** Drafts steps through an existing profile with an empty MCP gateway and no host tools or device session. */
internal suspend fun AppState.generateTestStepDraft(
    profileId: String,
    prompt: String,
    model: String? = null,
    reasoningEffort: String? = null,
    onUsage: (com.indagium.model.AiUsageStats) -> Unit = {},
): String =
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
            onUsage = onUsage,
        ),
    )

private val SCRIPT_DRAFT_WORDING = AgentTextWording(
    sessionPrefix = "test-script-draft",
    failure = "The provider could not draft the script",
    unfinished = "The provider did not finish the script request.",
    empty = "The provider returned no script draft.",
)

/** Drafts one library script through an existing profile with an empty MCP gateway and no host tools or device session. */
internal suspend fun AppState.generateTestScriptDraft(
    profileId: String,
    prompt: String,
    model: String? = null,
    reasoningEffort: String? = null,
    onUsage: (com.indagium.model.AiUsageStats) -> Unit = {},
): String =
    runAgentForText(
        AgentTextRequest(
            profileId = profileId,
            model = model,
            reasoningEffort = reasoningEffort,
            prompt = prompt,
            systemPrompt =
                "You write shell scripts for an Android QA test library. Return only the requested JSON object. You have no device " +
                    "or tools and cannot run anything; do not claim to have run or tested the command.",
            gateway = IndagiumToolGateway(emptyList(), emptyMap()),
            toolCallLimit = 1,
            maxTurns = 2,
            timeoutMs = TEST_STEP_DRAFT_TIMEOUT_MS,
            wording = SCRIPT_DRAFT_WORDING,
            onUsage = onUsage,
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
        onUsage = request.onUsage,
        attachment = request.attachment,
        onVisualEvidenceDelivered = request.onVisualEvidenceDelivered,
    ),
)

private val STEP_IMPROVE_WORDING = AgentTextWording(
    sessionPrefix = "test-step-improve",
    failure = "The provider could not improve the steps",
    unfinished = "The provider did not finish the improvement request.",
    empty = "The provider returned no improvement.",
    budgetGuidance = { IMPROVE_BUDGET_GUIDANCE },
    promptPreamble = { IMPROVE_PROMPT_PREAMBLE },
    noImagesNote = "improve_brief: this agent takes no images; the improvement works from the text evidence",
)

/**
 * Improves the saved steps of a case through an existing profile with an empty MCP gateway and no host tools or device session: the
 * evidence (and its screenshots, when the agent takes images) is in the request, so a single model turn answers.
 */
internal suspend fun AppState.generateTestCaseImprove(request: ImproveGeneration): String = runAgentForText(
    AgentTextRequest(
        profileId = request.profileId,
        model = request.model,
        reasoningEffort = request.effort,
        prompt = request.prompt,
        systemPrompt = request.systemPrompt,
        gateway = IndagiumToolGateway(emptyList(), emptyMap()),
        toolCallLimit = 1,
        maxTurns = 2,
        timeoutMs = TEST_CASE_IMPROVE_TIMEOUT_MS,
        wording = STEP_IMPROVE_WORDING,
        onUsage = request.onUsage,
        attachment = request.attachment,
    ),
)

private val NOTE_REWRITE_WORDING = AgentTextWording(
    sessionPrefix = "note-rewrite",
    failure = "The provider could not rewrite the note",
    unfinished = "The provider did not finish the note rewrite request.",
    empty = "The provider returned no rewritten note.",
    budgetGuidance = { "No tools are available; return only the rewritten note text." },
    promptPreamble = { "" },
)

/** Rewrites only the editor's captured text through an existing profile with no tools or app evidence. */
internal suspend fun AppState.generateNoteRewrite(
    profileId: String,
    model: String?,
    reasoningEffort: String?,
    targetText: String,
    instructions: String,
    onUsage: (com.indagium.model.AiUsageStats) -> Unit = {},
): String = runAgentForText(
    AgentTextRequest(
        profileId = profileId,
        model = model,
        reasoningEffort = reasoningEffort,
        prompt = noteRewritePrompt(targetText, instructions),
        systemPrompt = NOTE_REWRITE_SYSTEM_PROMPT,
        gateway = IndagiumToolGateway(emptyList(), emptyMap()),
        toolCallLimit = 1,
        maxTurns = 2,
        timeoutMs = NOTE_REWRITE_TIMEOUT_MS,
        wording = NOTE_REWRITE_WORDING,
        onUsage = onUsage,
    ),
)

/**
 * Runs one agent through the lane launchers (an in-app model over HTTP, or Claude Code / Codex as the signed-in account, which
 * reach [AgentTextRequest.gateway] through the managed MCP lease) and returns what it answered after its last tool call. Cancelling
 * the caller cancels the run; the run and its workspace are cleaned up either way. An [AgentTextRequest.attachment] goes with the first
 * message when the agent takes images; an agent that does not runs the attachment's fallback prompt and tool budget instead.
 */
internal suspend fun AppState.runAgentForText(request: AgentTextRequest): String {
    val base = normalizeAiProviderProfiles(settings.aiProviderProfiles).firstOrNull { it.id == request.profileId }
        ?: error("Choose an existing provider profile.")
    val problems = overrideProblems("Model", base, request.model, request.reasoningEffort)
    check(problems.isEmpty()) { problems.joinToString(" ") }
    val profile = base.withRunOverrides(request.model, request.reasoningEffort)
    val profileProblems = profileProblems("Model", profile, ::aiProviderApiKey)
    check(profileProblems.isEmpty()) { profileProblems.joinToString(" ") }
    val agent = productionAgentFactory(testRunOverrides).create(profile, aiProviderApiKey(request.profileId))
    val tabId = "${request.wording.sessionPrefix}:${java.util.UUID.randomUUID()}"
    val session = com.indagium.ai.AiSession(tabId)
    var run: AiRun? = null
    val attachment = request.attachment
    val attached = attachment?.takeIf { agent.acceptsImagesUpFront && it.images.isNotEmpty() }
    val fallback = attachment?.takeIf { attached == null }
    if (attached == null) request.onVisualEvidenceDelivered(emptyMap())
    if (fallback != null) {
        AppLogger.info(AGENT_TIMING_LOG_COMPONENT, request.wording.noImagesNote)
    }
    try {
        val activeRun = agent.start(
            AgentSegmentRequest(
                session = session,
                prompt = fallback?.fallbackPrompt ?: request.prompt,
                systemPrompt = request.systemPrompt,
                context = AiInvestigationContext(tabId),
                gateway = request.gateway,
                toolCallLimit = fallback?.fallbackToolCallLimit ?: request.toolCallLimit,
                maxTurns = fallback?.fallbackMaxTurns ?: request.maxTurns,
                freeTools = emptySet(),
                confirmationTimeoutMs = 1_000,
                budgetGuidance = request.wording.budgetGuidance,
                promptPreamble = request.wording.promptPreamble,
                images = attached?.images.orEmpty(),
                promptWithoutImages = attachment?.fallbackPrompt,
                onImagesDelivered = { delivered ->
                    request.onVisualEvidenceDelivered(if (delivered) attached?.visualMoments.orEmpty() else emptyMap())
                },
            ),
        )
        run = activeRun
        withTimeout(request.timeoutMs) {
            val job = activeRun.job
            while (job?.isActive == true) {
                request.onUsage(aiUsageFromHistory(activeRun.history, partial = true))
                delay(AGENT_USAGE_POLL_INTERVAL_MS)
            }
            job?.join()
        }
        currentCoroutineContext().ensureActive()
        return completedAgentAnswer(activeRun.history, request.wording)
    } finally {
        run?.cancel()
        withContext(NonCancellable) {
            withTimeoutOrNull(TEST_STEP_DRAFT_CLEANUP_MS) { run?.job?.join() }
            run?.let { activeRun ->
                val successful = activeRun.history.any { it == AiRunEvent.Done }
                request.onUsage(aiUsageFromHistory(activeRun.history, partial = !successful))
                // Where the time of a rewrite or a draft went, in the same terms as a judge's judge_finished line.
                val timing = aiRunTiming(activeRun, partial = !successful).logSummary()
                AppLogger.info(AGENT_TIMING_LOG_COMPONENT, "${request.wording.sessionPrefix} (${profile.displayName}, ${profile.model}): $timing")
            }
        }
        session.deleteClaudeCodeWorkspace()
        agent.close()
    }
}

/** The assistant text after the run's last tool call: what an agent said between tool calls is thinking aloud, not the answer. */
internal fun finalAnswerText(history: List<AiRunEvent>): String {
    val lastTool = history.indexOfLast { it is AiRunEvent.ToolRequested || it is AiRunEvent.ToolCompleted }
    return history.drop(lastTool + 1).filterIsInstance<AiRunEvent.AssistantDelta>().joinToString("") { it.text }
}

/** Refuse partial provider output. A successful preview needs the explicit terminal event as well as answer text. */
internal fun completedAgentAnswer(history: List<AiRunEvent>, wording: AgentTextWording): String {
    val error = history.filterIsInstance<AiRunEvent.Error>().lastOrNull()
    check(error == null) { "${wording.failure}: ${error?.message}" }
    check(AiRunEvent.Cancelled !in history) { wording.unfinished }
    check(AiRunEvent.Done in history) { wording.unfinished }
    return finalAnswerText(history).also { check(it.isNotBlank()) { wording.empty } }
}

private const val AGENT_TIMING_LOG_COMPONENT = "test-agent-timing"
private const val TEST_STEP_DRAFT_TIMEOUT_MS = 120_000L
private const val TEST_STEP_DRAFT_CLEANUP_MS = 2_000L
private const val AGENT_USAGE_POLL_INTERVAL_MS = 120L

/** A little longer than the service's own timeout (180 s), so the service answers first with its message. */
private const val RECORDING_REWRITE_TIMEOUT_MS = 190_000L

/** Same margin over the improvement service's own 180 s. */
private const val TEST_CASE_IMPROVE_TIMEOUT_MS = 190_000L
private const val NOTE_REWRITE_TIMEOUT_MS = 120_000L

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
            testStepRecordingSession?.let { it.deviceSerial == serial && it.snapshot.value.active } == true ->
                "Device $serial has an active test-step recording. Stop recording steps before starting the AI test run."
            laneCaptureOnDevice(serial) != null ->
                "Device $serial is recording for an AI test run (run ${laneCaptureOnDevice(serial)?.runId}); wait for it to finish."
            else -> null
        }
    } catch (failure: Exception) {
        "Could not list the connected devices: ${failure.message ?: failure::class.simpleName}"
    }
}

/** Where an improvement of a saved case finds the runs of its suite: this launch's runs and the stored ones, through the coordinator. */
internal fun AppState.improveRunSource(): ImproveRunSource = object : ImproveRunSource {
    override suspend fun summaries(suiteId: String) = testRunCoordinator.listRunSummariesForSuite(suiteId)

    override suspend fun load(runId: String) = testRunCoordinator.loadRun(runId)

    override fun runDir(runId: String): File = testRunCoordinator.runDir(runId)
}
