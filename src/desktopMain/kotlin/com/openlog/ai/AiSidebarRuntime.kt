package com.openlog.ai

import com.openlog.debug.OpenLogToolGateway
import com.openlog.model.AiProviderProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Factory seam for the sidebar and its focused tests. */
internal fun interface AiProviderFactory {
    fun create(profile: AiProviderProfile, apiKey: String): LlmProvider
}

internal sealed interface AiStartResult {
    data class Started(val run: AiRun) : AiStartResult

    data class Rejected(val message: String) : AiStartResult
}

/** Ephemeral, per-provider result for the sidebar. It is never written to settings. */
internal sealed interface AiConnectionState {
    data object NotChecked : AiConnectionState
    data object Checking : AiConnectionState
    data object Ready : AiConnectionState
    data class Failed(val message: String) : AiConnectionState
}

/**
 * Current-launch UI coordinator around [AiAgentRunner]. It deliberately owns neither settings
 * nor a serialized transcript: [AiSessionRegistry] is tab-scoped and AppState owns profiles and
 * in-memory keys. The revision flow batches streaming events so Compose does not reparse Markdown
 * once per token.
 */
internal class AiSidebarRuntime(
    private val sessions: AiSessionRegistry,
    private val toolGatewayFactory: () -> OpenLogToolGateway,
    // Read fresh per run rather than captured once, so a mid-launch Settings change (Settings ->
    // AI providers -> Max tool rounds) applies to the next request without restarting the app.
    private val maxToolRounds: () -> Int = { com.openlog.model.DEFAULT_AI_MAX_TOOL_ROUNDS },
    private val providerFactory: AiProviderFactory = AiProviderFactory { profile, key ->
        OpenAiCompatibleProvider(profile, key)
    },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {
    private data class RunResources(
        val runner: AiAgentRunner,
        val provider: LlmProvider,
    ) : AutoCloseable {
        override fun close() {
            runner.close()
            (provider as? AutoCloseable)?.close()
        }
    }

    private val resources = ConcurrentHashMap<String, RunResources>()
    private val observers = ConcurrentHashMap<String, Job>()
    private val connectionStates = ConcurrentHashMap<String, AiConnectionState>()
    private val _revision = MutableStateFlow(0L)
    private val updatePending = AtomicBoolean(false)

    /** UI observes this to update a streamed answer at most once every 75 ms. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun sessionFor(tabId: String): AiSession = sessions.sessionFor(tabId)

    fun connectionState(profileId: String): AiConnectionState =
        connectionStates[profileId] ?: AiConnectionState.NotChecked

    @Suppress("TooGenericExceptionCaught")
    fun start(
        tabId: String,
        profile: AiProviderProfile,
        apiKey: String,
        prompt: String,
        context: AiInvestigationContext = AiInvestigationContext(tabId),
    ): AiStartResult {
        startProblem(tabId, profile, prompt, context)?.let { return AiStartResult.Rejected(it) }

        val session = sessions.sessionFor(tabId)
        val provider = try {
            providerFactory.create(profile, apiKey)
        } catch (error: Exception) {
            return AiStartResult.Rejected(error.message ?: "Unable to prepare the model provider.")
        }
        val resourcesForRun = RunResources(
            AiAgentRunner(provider, toolGatewayFactory(), maxToolRounds = maxToolRounds()),
            provider,
        )
        val run = try {
            resourcesForRun.runner.start(
                session = session,
                model = profile.model,
                prompt = prompt.trim(),
                systemPrompt = systemPrompt(context),
                context = context,
            )
        } catch (error: Exception) {
            resourcesForRun.close()
            return AiStartResult.Rejected(error.message ?: "Unable to start the AI request.")
        }
        resources[run.id] = resourcesForRun
        connectionStates[profile.id] = AiConnectionState.Checking
        observers[run.id] = observe(run, profile.id)
        run.job?.invokeOnCompletion {
            // The observer is intentionally cancelled with the run resources. Read the retained
            // history here as well so a very fast terminal event cannot race that cancellation.
            run.history.lastOrNull()?.let { updateConnectionState(profile.id, it) }
            observers.remove(run.id)?.cancel()
            resources.remove(run.id)?.close()
            scheduleUiUpdate()
        }
        scheduleUiUpdate()
        return AiStartResult.Started(run)
    }

    fun retry(tabId: String, profile: AiProviderProfile, apiKey: String): AiStartResult {
        val session = sessions.sessionFor(tabId)
        if (session.activeRun != null) return AiStartResult.Rejected("Stop the current request before retrying.")
        val prompt = session.prepareRetry() ?: return AiStartResult.Rejected("There is no request to retry in this tab.")
        return start(tabId, profile, apiKey, prompt, session.lastContext)
    }

    fun cancel(run: AiRun) {
        resources[run.id]?.runner?.cancel(run) ?: run.cancel()
        scheduleUiUpdate()
    }

    /**
     * Drops this tab's session entirely: [AiSessionRegistry.remove] cancels any active run (which
     * still cancels its underlying job and, via that job's invokeOnCompletion, its own resources),
     * and the next [sessionFor] call for this tab starts a brand-new, empty [AiSession].
     */
    fun resetSession(tabId: String) {
        sessions.remove(tabId)
        scheduleUiUpdate()
    }

    fun resolveConfirmation(run: AiRun, confirmation: AiToolConfirmation, accepted: Boolean): Boolean {
        val resolved = resources[run.id]?.runner?.resolveConfirmation(run, confirmation.id, accepted) ?: false
        if (resolved) scheduleUiUpdate()
        return resolved
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun discoverModels(profile: AiProviderProfile, apiKey: String): ModelDiscoveryResult {
        val validation = validateAiProviderProfile(profile)
        if (!validation.isValid) return ModelDiscoveryResult.Unavailable(validation.problem!!.message)
        val provider = try {
            providerFactory.create(profile, apiKey)
        } catch (error: Exception) {
            return ModelDiscoveryResult.Unavailable(error.message ?: "Unable to prepare the model provider.")
        }
        return try {
            provider.listModels()
        } finally {
            (provider as? AutoCloseable)?.close()
        }
    }

    /** Explicit `/models` probe; neither credentials nor the outcome leave the current launch. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun testConnection(profile: AiProviderProfile, apiKey: String): AiConnectionState {
        val validation = validateAiProviderProfile(profile)
        if (!validation.isValid) {
            return AiConnectionState.Failed(validation.problem!!.message).also {
                connectionStates[profile.id] = it
                scheduleUiUpdate()
            }
        }
        connectionStates[profile.id] = AiConnectionState.Checking
        scheduleUiUpdate()
        val outcome = try {
            val provider = providerFactory.create(profile, apiKey)
            try {
                when (val discovered = provider.listModels()) {
                    is ModelDiscoveryResult.Available -> AiConnectionState.Ready
                    is ModelDiscoveryResult.Unavailable -> AiConnectionState.Failed(discovered.message)
                }
            } finally {
                (provider as? AutoCloseable)?.close()
            }
        } catch (error: Exception) {
            AiConnectionState.Failed(error.message ?: "Unable to connect to the configured model provider.")
        }
        connectionStates[profile.id] = outcome
        scheduleUiUpdate()
        return outcome
    }

    private fun observe(run: AiRun, profileId: String): Job =
        scope.launch {
            run.events.collect { event ->
                updateConnectionState(profileId, event)
                scheduleUiUpdate()
            }
        }

    private fun updateConnectionState(profileId: String, event: AiRunEvent) {
        when (event) {
            AiRunEvent.Done -> connectionStates[profileId] = AiConnectionState.Ready
            is AiRunEvent.Error -> connectionStates[profileId] = AiConnectionState.Failed(event.message)
            AiRunEvent.Cancelled -> if (connectionStates[profileId] == AiConnectionState.Checking) {
                connectionStates[profileId] = AiConnectionState.NotChecked
            }
            else -> Unit
        }
    }

    private fun scheduleUiUpdate() {
        if (!updatePending.compareAndSet(false, true)) return
        scope.launch {
            delay(UI_UPDATE_DEBOUNCE_MS)
            _revision.value += 1
            updatePending.set(false)
        }
    }

    override fun close() {
        resources.values.forEach { it.close() }
        resources.clear()
        observers.values.forEach { it.cancel() }
        observers.clear()
        scope.cancel()
    }

    private fun systemPrompt(context: AiInvestigationContext): String =
        """
        You are openLog's in-app log investigation assistant. The pinned log tab for this request is `${context.tabId}`.
        ${when {
            context.lineIds.isNotEmpty() ->
                "The selected log-line context is `${context.lineIds.joinToString(", ")}`. " +
                    "Use those lines as the starting evidence."
            context.lineId != null -> "The pinned log line is `${context.lineId}`."
            else -> "There is no pinned log line for this request."
        }}
        Use the provided tools for evidence; pass this pinned tab id to tools that require tabId unless
        the user explicitly asks about another open tab. Do not invent facts, log lines, source
        mappings, or completed actions. State uncertainty and the next useful check clearly.
        """.trimIndent()

    private fun startProblem(
        tabId: String,
        profile: AiProviderProfile,
        prompt: String,
        context: AiInvestigationContext,
    ): String? {
        val validation = validateAiProviderProfile(profile)
        return when {
            !validation.isValid -> validation.problem!!.message
            profile.model.isBlank() -> "Choose or enter a model before sending a request."
            prompt.isBlank() -> "Enter a question for the AI assistant."
            context.tabId != tabId -> "The AI context belongs to a different log tab."
            else -> null
        }
    }

    private companion object {
        const val UI_UPDATE_DEBOUNCE_MS = 75L
    }
}
