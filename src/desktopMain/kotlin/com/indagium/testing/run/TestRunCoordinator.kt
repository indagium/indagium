package com.indagium.testing.run

import com.indagium.edition.EditionLimits
import com.indagium.model.AiProviderProfile
import com.indagium.testing.limits.LimitDecision
import com.indagium.testing.model.HookItem
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.RunSummary
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.judgeActive
import com.indagium.testing.model.newRunId
import com.indagium.testing.model.summary
import com.indagium.testing.script.TestScriptRunner
import com.indagium.testing.store.IssueStore
import com.indagium.testing.store.RunPersister
import com.indagium.testing.store.TestRunStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

// Owns every AI test run of this launch (the lanes of a run work in parallel across devices; see TestRunEngine). start()
// validates, freezes the suite into a TestRun and launches the engine on the coordinator's OWN scope (SupervisorJob + IO),
// so nothing here ever runs on the UI thread or holds a UI lock. The run's state is exposed as a StateFlow of immutable
// TestRun snapshots (AppState mirrors it into Compose state); what
// is live and not part of the stored run (pending confirmation cards, a paused lane) is read from the lane handles.
//
// Locks: [registryLock] only guards the check-and-register of devices in use, and the publish lock the snapshot
// assignment; both are leaves. Nothing here touches AppState.stateLock: the owner passes plain lambdas.

const val COORDINATOR_CLOSE_WAIT_MS = 4_000L
private const val MAX_FINISHED_RUNS_IN_MEMORY = 20

sealed interface StartRunResult {
    /** The run is under way. [laneIds] are the ids `test_lane_tool_call` and `resume_paused_step` take. */
    data class Started(val runId: String, val laneIds: List<String>, val warnings: List<String>) : StartRunResult

    /** Nothing started. [limit] is set when the edition limits refused the run. */
    data class Rejected(val errors: List<String>, val limit: LimitDecision.Refused? = null) : StartRunResult
}

/** The owner's view of the world, as lambdas so the coordinator knows nothing of AppState. */
@Suppress("LongParameterList") // The owner's whole view of the world, as lambdas; each has a default or a single use.
internal class CoordinatorDeps(
    val library: () -> TestLibrary,
    val limits: () -> EditionLimits,
    val store: TestRunStore,
    val profiles: () -> List<AiProviderProfile>,
    val apiKey: (profileId: String) -> String,
    /** Why a device cannot be used (not connected, held by the live capture), or null when it can. Runs on IO. */
    val deviceProblem: suspend (serial: String) -> String?,
    val openDevice: LaneDeviceOpener,
    val agentFactory: LaneAgentFactory,
    val scriptRunner: TestScriptRunner = TestScriptRunner(),
    val tuning: EngineTuning = EngineTuning(),
    val wallClock: () -> Long = System::currentTimeMillis,
    /** The image of a golden-screenshot example, for the judge. Blocking; called on IO. */
    val goldenImage: (suiteId: String, assetPath: String) -> ByteArray? = { _, _ -> null },
    /** Where the issues of this app live; null (tests) means runs create no draft issues and re-check none. */
    val issues: IssueStore? = null,
    /** The file of a golden-screenshot example, for the evidence of an issue. */
    val goldenFile: (suiteId: String, assetPath: String) -> java.io.File? = { _, _ -> null },
)

private class RunEntry(
    val state: TestRunState,
    val handles: Map<String, LaneHandle>,
    val persister: RunPersister,
    val gate: RunPauseGate,
) {
    @Volatile var job: Job? = null
}

internal class TestRunCoordinator(
    private val deps: CoordinatorDeps,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Called (on any thread) after the snapshot flow changed. */
    private val onChanged: () -> Unit = {},
) : AutoCloseable {
    private val runs = ConcurrentHashMap<String, RunEntry>()
    private val registryLock = Any()
    private val publishLock = Any()
    private val devicesInUse = HashMap<String, String>()
    private val liveRuns = MutableStateFlow<List<TestRun>>(emptyList())

    /** Every run of this launch, newest first. */
    val runsFlow: StateFlow<List<TestRun>> = liveRuns.asStateFlow()

    private fun publish() {
        synchronized(publishLock) {
            liveRuns.value = runs.values.map { it.state.current }.sortedByDescending { it.createdAt }
        }
        onChanged()
    }

    // ── Starting ─────────────────────────────────────────────────────

    suspend fun start(config: RunConfig): StartRunResult {
        val library = deps.library()
        val profiles = deps.profiles()
        val validation = validateRun(config, library, deps.limits(), profiles, deps.apiKey)
        val problems = ArrayList(validation.errors)
        val suite = validation.suite
        if (problems.isEmpty() && suite != null) {
            for (serial in config.lanes.map { it.deviceSerial }.distinct()) deps.deviceProblem(serial)?.let { problems += it }
        }
        if (problems.isNotEmpty() || suite == null) return StartRunResult.Rejected(problems, validation.limit)
        val runId = newRunId()
        val busy = synchronized(registryLock) {
            val taken = config.lanes.map { it.deviceSerial }.distinct().filter { it in devicesInUse }
            if (taken.isEmpty()) config.lanes.forEach { devicesInUse[it.deviceSerial] = runId }
            taken.map { "Device $it is already used by run ${devicesInUse[it]}." }
        }
        if (busy.isNotEmpty()) return StartRunResult.Rejected(busy)
        launchRun(runId, suite, library, config, validation, profiles)
        return StartRunResult.Started(runId, config.lanes.map { it.id }, validation.warnings)
    }

    @Suppress("LongParameterList")
    private fun launchRun(
        runId: String,
        suite: TestSuite,
        library: TestLibrary,
        config: RunConfig,
        validation: RunValidation,
        profiles: List<AiProviderProfile>,
    ) {
        val frozenSuite = suite.truncatedAfter(config.stopAfterStepId)
        val plan = validation.plan.truncatedAfter(config.stopAfterStepId)
        val initial = TestRun(
            id = runId,
            suite = frozenSuite,
            scripts = library.scripts,
            sharedSteps = referencedSharedSteps(frozenSuite, library),
            config = config,
            lanes = config.lanes.map { LaneResult(it.id, it) },
            createdAt = deps.wallClock(),
            warnings = validation.warnings,
        )
        val handles = config.lanes.associate { it.id to LaneHandle(runId, it.id) }
        lateinit var persister: RunPersister
        val state = TestRunState(initial) {
            persister.request()
            publish()
        }
        persister = RunPersister(deps.store, scope, { state.current }, deps.tuning.persistDebounceMs)
        val gate = RunPauseGate()
        val entry = RunEntry(state, handles, persister, gate)
        runs[runId] = entry
        publish() // The run is listed the moment start() returns, before its job has run a single instruction.
        val agents = config.lanes.filter { it.kind == LaneKind.AGENT_PROFILE }
            .associate { it.id to profiles.profileOrNull(it.profileId)?.let { profile -> profile to deps.apiKey(profile.id) } }
        val engineDeps = EngineDeps(
            openDevice = deps.openDevice,
            agentFor = { lane ->
                val (profile, key) = checkNotNull(agents[lane.id]) { "The AI profile of lane ${lane.id} is gone." }
                deps.agentFactory.create(profile, key)
            },
            scriptRunner = deps.scriptRunner,
            store = deps.store,
            tuning = deps.tuning,
            wallClock = deps.wallClock,
            judgeAgent = judgeAgentFactory(config, profiles),
            goldenImage = deps.goldenImage,
            pauseGate = gate,
            issues = deps.issues,
            goldenFile = deps.goldenFile,
        )
        // ATOMIC: a run cancelled before its first instruction must still reach the finally that frees its devices.
        entry.job = scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                persister.flush()
                TestRunEngine(state, persister, engineDeps, plan, handles).execute()
            } finally {
                release(runId, config)
            }
        }
    }

    /** The judge's agent builder, or null when the run has no judge. The judge uses the same launchers as a lane, whatever its profile kind. */
    private fun judgeAgentFactory(config: RunConfig, profiles: List<AiProviderProfile>): (() -> LaneAgent)? {
        if (!config.judgeActive) return null
        val profile = profiles.profileOrNull(config.judgeProfileId) ?: return null
        val key = deps.apiKey(profile.id)
        return { deps.agentFactory.create(profile, key) }
    }

    private fun release(runId: String, config: RunConfig) {
        synchronized(registryLock) { config.lanes.forEach { if (devicesInUse[it.deviceSerial] == runId) devicesInUse.remove(it.deviceSerial) } }
        trimFinished()
        publish()
    }

    /** Keeps the last few finished runs in memory; older ones are still on disk and load from there. */
    private fun trimFinished() {
        val finished = runs.entries.filter { it.value.state.current.isFinished }.sortedByDescending { it.value.state.current.createdAt }
        finished.drop(MAX_FINISHED_RUNS_IN_MEMORY).forEach { runs.remove(it.key) }
    }

    private fun referencedSharedSteps(suite: TestSuite, library: TestLibrary): List<SharedStep> {
        val hooks = suite.setup + suite.teardown + suite.cases.flatMap { it.setup + it.teardown }
        val ids = hooks.filterIsInstance<HookItem.Shared>().map { it.sharedStepId }.distinct()
        return ids.mapNotNull { library.sharedStep(it) }
    }

    // ── Reading ──────────────────────────────────────────────────────

    fun run(runId: String): TestRun? = runs[runId]?.state?.current

    /** The run from memory, or from disk for a run of an earlier launch. */
    suspend fun loadRun(runId: String): TestRun? = run(runId) ?: withContext(Dispatchers.IO) { deps.store.load(runId) }

    /** The runs of this launch and the stored ones, newest first. Reads the disk on IO. */
    suspend fun listRuns(): List<RunSummary> {
        val live = runs.values.map { it.state.current.summary() }
        val stored = withContext(Dispatchers.IO) { deps.store.list() }
        return (live + stored.filter { s -> live.none { it.id == s.id } }).sortedByDescending { it.createdAt }
    }

    /** The folder of [runId] (its screenshots, logs and transcript live under it). */
    fun runDir(runId: String): java.io.File = deps.store.runDir(runId)

    fun laneConfig(runId: String, laneId: String): LaneConfig? = run(runId)?.lane(laneId)?.config

    // ── Controlling ──────────────────────────────────────────────────

    /** Cancels the run; false when it is unknown or already over. The final state arrives asynchronously. */
    fun cancel(runId: String): Boolean {
        val entry = runs[runId] ?: return false
        if (entry.state.current.isFinished) return false
        entry.job?.cancel()
        return true
    }

    /** Pause all: lanes of the run stop at their next step boundary until [paused] is false again. False for an unknown or finished run. */
    fun setPaused(runId: String, paused: Boolean): Boolean {
        val entry = runs[runId] ?: return false
        if (entry.state.current.isFinished) return false
        entry.gate.setPaused(paused)
        entry.state.touch()
        return true
    }

    fun isPaused(runId: String): Boolean = runs[runId]?.gate?.isPaused ?: false

    /** The most recent tool calls of the lane's agent (newest last), as short lines for the live view. Empty when no agent is running. */
    fun recentToolCalls(runId: String, laneId: String, max: Int): List<String> =
        runs[runId]?.handles?.get(laneId)?.agentRun?.history?.let { toolCallLines(it, max) }.orEmpty()

    /**
     * Applies [transform] to a run, in memory when the run is held here (its persister then saves it) or, for a run of an
     * earlier launch, on disk. Null for an unknown run; otherwise the updated run. Runs on IO.
     */
    suspend fun updateRun(runId: String, transform: (TestRun) -> TestRun): TestRun? {
        val entry = runs[runId]
        if (entry != null) {
            entry.state.update(transform)
            entry.persister.flush()
            return entry.state.current
        }
        return withContext(Dispatchers.IO) {
            val stored = deps.store.load(runId) ?: return@withContext null
            transform(stored).also { deps.store.save(it) }
        }
    }

    /** Waits for the run to end (tests, and callers that want the final report). Null for an unknown run. */
    suspend fun awaitFinished(runId: String): TestRun? {
        val entry = runs[runId] ?: return loadRun(runId)
        entry.job?.join()
        return entry.state.current
    }

    fun pendingConfirmations(): List<PendingTestConfirmation> = runs.values.flatMap { entry -> entry.handles.values.flatMap { it.pendingConfirmations() } }

    /** Answers a confirmation card of an in-app agent run. False when it is not pending any more. */
    fun resolveConfirmation(runId: String, confirmationId: String, allow: Boolean): Boolean {
        val entry = runs[runId] ?: return false
        return entry.handles.values.any { it.resolveConfirmation(confirmationId, allow) }.also { if (it) publish() }
    }

    fun pausedSteps(): List<PausedStepInfo> = runs.values.flatMap { entry ->
        entry.handles.values.mapNotNull { handle -> handle.pausedStep()?.let { PausedStepInfo(handle.runId, handle.laneId, it) } }
    }

    /** Resumes a lane paused by a PAUSE_FOR_USER step. False when the lane is not paused. */
    fun resumePausedStep(runId: String, laneId: String, decision: PauseDecision): Boolean =
        runs[runId]?.handles?.get(laneId)?.resolvePause(decision) ?: false

    /**
     * Runs a lane tool of an EXTERNAL lane (what a client driving the lane over MCP does). The tool runs against the
     * lane's current case, behind the same per-step guard an agent has. Errors come back as `{ "error": ... }`.
     */
    suspend fun laneToolCall(runId: String, laneId: String, tool: String, arguments: Map<String, Any?>): Any? {
        val entry = runs[runId] ?: return mapOf("error" to "Run '$runId' is not running in this session.")
        val handle = entry.handles[laneId] ?: return mapOf("error" to "Run '$runId' has no lane '$laneId'.")
        val lane = entry.state.current.lane(laneId)
        if (lane?.config?.kind != LaneKind.EXTERNAL) return mapOf("error" to "Lane '$laneId' is driven by an agent; only external lanes accept tool calls.")
        if (lane.status != RunStatus.RUNNING) return mapOf("error" to "Lane '$laneId' is ${lane.status.name.lowercase()}; it cannot take tool calls.")
        val sequence = handle.sequence ?: return mapOf("error" to NO_ACTIVE_CASE_MESSAGE)
        return sequence.externalGateway().executeSuspending(tool, arguments)
    }

    override fun close() {
        val jobs = runs.values.mapNotNull { it.job }
        jobs.forEach { it.cancel() }
        runBlocking { withTimeoutOrNull(COORDINATOR_CLOSE_WAIT_MS) { jobs.joinAll() } }
        scope.cancel()
    }

    private companion object {
        const val NO_ACTIVE_CASE_MESSAGE =
            "No case is active on this lane right now (it may be starting, running setup or teardown, or between cases). " +
                "Call get_test_run_status and retry."
    }
}
