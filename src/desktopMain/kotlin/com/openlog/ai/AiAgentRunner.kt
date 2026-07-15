package com.openlog.ai

import com.openlog.debug.OpenLogToolGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-only transcript for one log tab. It intentionally is not part of [com.openlog.model.LogTab]
 * or [com.openlog.ui.AppState], so neither autosave nor note export can retain an AI conversation.
 */
internal class AiSession internal constructor(val tabId: String) {
    internal val messages = mutableListOf<LlmMessage>()
    internal var activeRun: AiRun? = null
    internal var lastPrompt: String? = null
    internal var lastContext: AiInvestigationContext = AiInvestigationContext(tabId)
    private val _runs = ArrayDeque<AiRun>()

    /** Completed, failed, cancelled, and active requests for this tab during the current launch. */
    val runs: List<AiRun>
        get() = _runs.toList()

    internal fun retain(run: AiRun) {
        _runs += run
        while (_runs.size > MAX_RETAINED_RUNS) _runs.removeFirst()
    }

    /**
     * Removes the last attempted exchange so Retry sends the same prompt once, rather than
     * appending a duplicate user message to an incomplete or failed conversation.
     */
    internal fun prepareRetry(): String? {
        val userIndex = messages.indexOfLast { it.role == LlmRole.USER }
        val prompt = messages.getOrNull(userIndex)?.content ?: lastPrompt
        if (userIndex >= 0) {
            while (messages.size > userIndex) messages.removeLast()
        }
        return prompt?.takeIf { it.isNotBlank() }
    }

    private companion object {
        // This bounds the session-only transcript by requests while preserving every event for each
        // retained run. A user can still clear the registry explicitly when closing a tab.
        const val MAX_RETAINED_RUNS = 50
    }
}

/** Holds the current-launch, tab-scoped sessions. This registry owns no persisted state. */
internal class AiSessionRegistry {
    private val sessions = ConcurrentHashMap<String, AiSession>()

    fun sessionFor(tabId: String): AiSession = sessions.computeIfAbsent(tabId, ::AiSession)

    fun remove(tabId: String) {
        sessions.remove(tabId)?.activeRun?.cancel()
    }

    fun clear() {
        sessions.values.forEach { it.activeRun?.cancel() }
        sessions.clear()
    }
}

/** One ephemeral user request running inside an [AiSession]. */
internal class AiRun internal constructor(
    val id: String = UUID.randomUUID().toString(),
    val tabId: String,
    val userPrompt: String = "",
    val context: AiInvestigationContext = AiInvestigationContext(tabId),
    val sentAt: Long = System.currentTimeMillis(),
) {
    private val _events = MutableSharedFlow<AiRunEvent>(replay = EVENT_REPLAY, extraBufferCapacity = EVENT_BUFFER)
    private val _history = mutableListOf<AiRunEvent>()
    internal val confirmations = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    internal var job: Job? = null

    /** Wall-clock time of the first model-originated event (a reply or a tool call), if any yet. */
    var firstResponseAt: Long? = null
        private set

    /** Wall-clock time this run reached a terminal state (done, error, or cancelled), if any yet. */
    var completedAt: Long? = null
        private set

    val events: SharedFlow<AiRunEvent> = _events.asSharedFlow()

    /** Full event history for this retained, current-launch run; not limited by SharedFlow replay. */
    val history: List<AiRunEvent>
        get() = synchronized(_history) { _history.toList() }

    /** The number of user decisions still blocking this run. */
    val pendingConfirmationCount: Int get() = confirmations.size

    fun isConfirmationPending(confirmationId: String): Boolean = confirmations.containsKey(confirmationId)

    internal suspend fun emit(event: AiRunEvent) {
        synchronized(_history) { _history += event }
        if (firstResponseAt == null && (
                event is AiRunEvent.AssistantDelta || event is AiRunEvent.AgentProgress || event is AiRunEvent.ToolRequested
            )
        ) {
            firstResponseAt = System.currentTimeMillis()
        }
        if (completedAt == null && (event is AiRunEvent.Done || event is AiRunEvent.Error || event == AiRunEvent.Cancelled)) {
            completedAt = System.currentTimeMillis()
        }
        _events.emit(event)
    }

    fun cancel() {
        confirmations.values.forEach { confirmation -> confirmation.cancel() }
        confirmations.clear()
        job?.cancel()
    }

    private companion object {
        const val EVENT_REPLAY = 32
        const val EVENT_BUFFER = 64
    }
}

/** UI-neutral trace of an agent request. Task 05 renders these events in the sidebar. */
internal sealed interface AiRunEvent {
    data class Status(val text: String) : AiRunEvent

    /** A user-visible agent update emitted before the final response; rendered in Investigation. */
    data class AgentProgress(val text: String) : AiRunEvent

    data class AssistantDelta(val text: String) : AiRunEvent

    data class ToolRequested(val call: LlmToolCall) : AiRunEvent

    data class ToolCompleted(
        val call: LlmToolCall,
        val resultPreview: String,
        val resultTruncated: Boolean,
        val evidence: List<AiEvidence> = emptyList(),
    ) : AiRunEvent

    data class ConfirmationRequired(val confirmation: AiToolConfirmation) : AiRunEvent

    /** Forwarded verbatim from [LlmStreamEvent.Usage] when a provider reports it. */
    data class Usage(val promptTokens: Int, val completionTokens: Int, val totalTokens: Int) : AiRunEvent

    data class Error(val message: String) : AiRunEvent

    data object Cancelled : AiRunEvent

    data object Done : AiRunEvent
}

internal data class AiToolConfirmation(
    val id: String,
    val call: LlmToolCall,
    val description: String,
)

/**
 * A bounded, provider-neutral model -> tool -> model loop.
 *
 * It never routes through openLog's HTTP MCP server: [toolGateway] directly invokes the same
 * transport-neutral operations used by the MCP adapter. Tools classified as destructive pause
 * before the gateway is touched, and a UI must explicitly resolve that pause.
 */
internal class AiAgentRunner(
    private val provider: LlmProvider,
    private val toolGateway: OpenLogToolGateway,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val maxToolRounds: Int = MAX_TOOL_ROUNDS,
    private val maxToolResultChars: Int = MAX_TOOL_RESULT_CHARS,
) : AutoCloseable {
    init {
        require(maxToolRounds > 0) { "maxToolRounds must be positive" }
        require(maxToolResultChars > 0) { "maxToolResultChars must be positive" }
    }

    private val toolExecutor = AiToolExecutionCoordinator(toolGateway, maxToolResultChars)

    fun start(
        session: AiSession,
        model: String,
        prompt: String,
        systemPrompt: String? = null,
        context: AiInvestigationContext = AiInvestigationContext(session.tabId),
        reasoningEffort: String? = null,
    ): AiRun {
        require(prompt.isNotBlank()) { "AI prompt must not be blank" }
        require(context.tabId == session.tabId) { "AI context must belong to the session tab." }
        session.activeRun?.cancel()
        val run = AiRun(tabId = session.tabId, userPrompt = prompt, context = context)
        session.lastPrompt = prompt
        session.lastContext = context
        session.activeRun = run
        session.retain(run)
        run.job = scope.launch {
            try {
                runLoop(session, run, model, prompt, systemPrompt, reasoningEffort)
            } catch (_: CancellationException) {
                run.emit(AiRunEvent.Cancelled)
            } finally {
                run.confirmations.values.forEach { it.cancel() }
                run.confirmations.clear()
                if (session.activeRun === run) session.activeRun = null
            }
        }
        return run
    }

    /** Returns false if the run ended/cancelled, or if this confirmation is no longer pending. */
    fun resolveConfirmation(run: AiRun, confirmationId: String, accepted: Boolean): Boolean {
        val deferred = run.confirmations.remove(confirmationId) ?: return false
        return deferred.complete(accepted)
    }

    fun cancel(run: AiRun) = run.cancel()

    override fun close() {
        scope.cancel()
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun runLoop(
        session: AiSession,
        run: AiRun,
        model: String,
        prompt: String,
        systemPrompt: String?,
        reasoningEffort: String?,
    ) {
        val conversation = session.messages.toMutableList()
        val initialMessageCount = session.messages.size
        if (conversation.isEmpty() && !systemPrompt.isNullOrBlank()) {
            conversation += LlmMessage(LlmRole.SYSTEM, systemPrompt)
        }
        conversation += LlmMessage(LlmRole.USER, prompt)
        var toolRounds = 0
        // Scales with the configured budget rather than a fixed count: a small model given a large
        // budget (Settings -> AI providers) can otherwise spend all of it re-checking evidence and
        // never reach the point of writing a note, which is itself several tool calls (see the
        // ISSUE_INVESTIGATION quick action). Bounded so a small configured budget still nudges early
        // rather than only in its very last round.
        val nudgeLeadRounds = (maxToolRounds / NUDGE_LEAD_FRACTION).coerceIn(1, NUDGE_LEAD_ROUNDS_CAP)
        var nudgeSent = false

        try {
            while (true) {
                run.emit(AiRunEvent.Status(if (toolRounds == 0) "Generating response…" else "Continuing investigation…"))
                val assistantText = StringBuilder()
                val toolCalls = mutableListOf<LlmToolCall>()
                val reasoning = mutableListOf<LlmReasoning>()
                var completed = false
                var failed = false
                var assistantRecorded = false

                fun recordAssistantMessage() {
                    if (!assistantRecorded && (assistantText.isNotEmpty() || toolCalls.isNotEmpty() || reasoning.isNotEmpty())) {
                        conversation += LlmMessage(
                            role = LlmRole.ASSISTANT,
                            content = assistantText.toString().ifBlank { null },
                            toolCalls = toolCalls,
                            reasoning = reasoning.toList(),
                        )
                        assistantRecorded = true
                    }
                }

                try {
                    provider.streamChat(
                        LlmRequest(
                            model = model,
                            messages = conversation,
                            tools = toolGateway.openAiFunctions(),
                            reasoningEffort = reasoningEffort?.takeIf(String::isNotBlank),
                        ),
                    ).collect { event ->
                        when (event) {
                            is LlmStreamEvent.TextDelta -> {
                                assistantText.append(event.text)
                                run.emit(AiRunEvent.AssistantDelta(event.text))
                            }

                            is LlmStreamEvent.ToolCall -> toolCalls += event.call
                            is LlmStreamEvent.ReasoningComplete -> reasoning += event.block
                            is LlmStreamEvent.Error -> {
                                failed = true
                                run.emit(AiRunEvent.Error(event.message))
                            }

                            is LlmStreamEvent.Warning -> run.emit(AiRunEvent.Status(event.message))
                            LlmStreamEvent.Completed -> completed = true
                            is LlmStreamEvent.ToolCallDelta -> Unit // Provider-level detail is represented by ToolRequested below.
                            is LlmStreamEvent.Usage -> run.emit(
                                AiRunEvent.Usage(event.promptTokens, event.completionTokens, event.totalTokens),
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    recordAssistantMessage()
                    throw cancelled
                }

                recordAssistantMessage()
                if (failed) return
                if (!completed) {
                    run.emit(AiRunEvent.Error("Model stream ended before completion."))
                    return
                }

                if (toolCalls.isEmpty()) {
                    run.emit(AiRunEvent.Done)
                    return
                }

                if (toolRounds >= maxToolRounds) {
                    run.emit(AiRunEvent.Error("Stopped after $maxToolRounds tool rounds to keep this investigation bounded."))
                    return
                }
                toolRounds++

                toolCalls.forEach { call ->
                    val result = toolExecutor.execute(run, call)
                    conversation += LlmMessage(LlmRole.TOOL, content = result.content, toolCallId = call.id)
                }

                val roundsLeft = maxToolRounds - toolRounds
                if (!nudgeSent && roundsLeft <= nudgeLeadRounds) {
                    nudgeSent = true
                    conversation += LlmMessage(LlmRole.SYSTEM, wrapUpNudge(roundsLeft))
                    run.emit(AiRunEvent.Status("Nearing the tool-call budget; asking the model to wrap up…"))
                }
            }
        } finally {
            session.messages += conversation.drop(initialMessageCount)
        }
    }

    private fun wrapUpNudge(roundsLeft: Int): String =
        "You have $roundsLeft tool round(s) left before this investigation is stopped automatically. " +
            "Stop requesting more evidence now. Conclude using only what you already have: if the task " +
            "asked you to save a note, call add_text_note before your final reply; otherwise give your " +
            "final answer directly without requesting further tools."

    private companion object {
        const val MAX_TOOL_ROUNDS = com.openlog.model.DEFAULT_AI_MAX_TOOL_ROUNDS
        const val MAX_TOOL_RESULT_CHARS = 12_000

        // Cap on how many rounds before the limit the wrap-up nudge fires, and the fraction of the
        // total budget it scales from - see the nudgeLeadRounds computation in runLoop.
        const val NUDGE_LEAD_ROUNDS_CAP = 15
        const val NUDGE_LEAD_FRACTION = 4
    }
}
