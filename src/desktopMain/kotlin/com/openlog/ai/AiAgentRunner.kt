package com.openlog.ai

import com.openlog.debug.OpenLogToolActionPolicy
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
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
) {
    private val _events = MutableSharedFlow<AiRunEvent>(replay = EVENT_REPLAY, extraBufferCapacity = EVENT_BUFFER)
    private val _history = mutableListOf<AiRunEvent>()
    internal val confirmations = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    internal var job: Job? = null

    val events: SharedFlow<AiRunEvent> = _events.asSharedFlow()

    /** Full event history for this retained, current-launch run; not limited by SharedFlow replay. */
    val history: List<AiRunEvent>
        get() = synchronized(_history) { _history.toList() }

    internal suspend fun emit(event: AiRunEvent) {
        synchronized(_history) { _history += event }
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

    data class AssistantDelta(val text: String) : AiRunEvent

    data class ToolRequested(val call: LlmToolCall) : AiRunEvent

    data class ToolCompleted(
        val call: LlmToolCall,
        val resultPreview: String,
        val resultTruncated: Boolean,
        val evidence: List<AiEvidence> = emptyList(),
    ) : AiRunEvent

    data class ConfirmationRequired(val confirmation: AiToolConfirmation) : AiRunEvent

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

    fun start(
        session: AiSession,
        model: String,
        prompt: String,
        systemPrompt: String? = null,
        context: AiInvestigationContext = AiInvestigationContext(session.tabId),
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
                runLoop(session, run, model, prompt, systemPrompt)
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
    ) {
        val conversation = session.messages.toMutableList()
        val initialMessageCount = session.messages.size
        if (conversation.isEmpty() && !systemPrompt.isNullOrBlank()) {
            conversation += LlmMessage(LlmRole.SYSTEM, systemPrompt)
        }
        conversation += LlmMessage(LlmRole.USER, prompt)
        var toolRounds = 0

        try {
            while (true) {
                run.emit(AiRunEvent.Status(if (toolRounds == 0) "Generating response…" else "Continuing investigation…"))
                val assistantText = StringBuilder()
                val toolCalls = mutableListOf<LlmToolCall>()
                var completed = false
                var failed = false
                var assistantRecorded = false

                fun recordAssistantMessage() {
                    if (!assistantRecorded && (assistantText.isNotEmpty() || toolCalls.isNotEmpty())) {
                        conversation += LlmMessage(
                            role = LlmRole.ASSISTANT,
                            content = assistantText.toString().ifBlank { null },
                            toolCalls = toolCalls,
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
                        ),
                    ).collect { event ->
                        when (event) {
                            is LlmStreamEvent.TextDelta -> {
                                assistantText.append(event.text)
                                run.emit(AiRunEvent.AssistantDelta(event.text))
                            }

                            is LlmStreamEvent.ToolCall -> toolCalls += event.call
                            is LlmStreamEvent.Error -> {
                                failed = true
                                run.emit(AiRunEvent.Error(event.message))
                            }

                            is LlmStreamEvent.Warning -> run.emit(AiRunEvent.Status(event.message))
                            LlmStreamEvent.Completed -> completed = true
                            is LlmStreamEvent.ToolCallDelta -> Unit // Provider-level detail is represented by ToolRequested below.
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
                    run.emit(AiRunEvent.ToolRequested(call))
                    val result = executeToolWithPolicy(run, call)
                    conversation += LlmMessage(LlmRole.TOOL, content = result.content, toolCallId = call.id)
                    run.emit(AiRunEvent.ToolCompleted(call, result.preview, result.truncated, result.evidence))
                }
            }
        } finally {
            session.messages += conversation.drop(initialMessageCount)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun executeToolWithPolicy(run: AiRun, call: LlmToolCall): ToolResult {
        val arguments = parseArguments(call.argumentsJson)
            ?: return ToolResult.error("Tool arguments must be a JSON object.")
        if (toolGateway.actionPolicy(call.name) == OpenLogToolActionPolicy.CONFIRMATION_REQUIRED) {
            val confirmation = AiToolConfirmation(
                id = UUID.randomUUID().toString(),
                call = call,
                description = confirmationDescription(call.name),
            )
            val decision = CompletableDeferred<Boolean>()
            run.confirmations[confirmation.id] = decision
            run.emit(AiRunEvent.ConfirmationRequired(confirmation))
            val accepted = try {
                decision.await()
            } finally {
                run.confirmations.remove(confirmation.id, decision)
            }
            if (!accepted) return ToolResult.error("The user declined this action; no changes were made.")
        }

        return try {
            val rawResult = toolGateway.execute(call.name, arguments)
            ToolResult.from(rawResult, maxToolResultChars, AiEvidenceExtractor.from(call.name, rawResult))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ToolResult.error("Tool '${call.name}' failed: ${error.message ?: "unexpected error"}")
        }
    }

    private fun parseArguments(rawArguments: String): Map<String, Any?>? = try {
        json.parseToJsonElement(rawArguments).jsonObject.toKotlinMap()
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.toKotlinMap(): Map<String, Any?> = entries.associate { (key, value) -> key to value.toKotlinValue() }

    private fun JsonElement.toKotlinValue(): Any? = when (this) {
        JsonNull -> null
        is JsonObject -> toKotlinMap()
        is JsonArray -> map { it.toKotlinValue() }
        is JsonPrimitive -> booleanOrNull ?: doubleOrNull ?: content
    }

    private data class ToolResult(
        val content: String,
        val preview: String,
        val truncated: Boolean,
        val evidence: List<AiEvidence> = emptyList(),
    ) {
        companion object {
            fun from(value: Any?, maxChars: Int, evidence: List<AiEvidence>): ToolResult {
                val rendered = value?.toString() ?: "null"
                return if (rendered.length <= maxChars) {
                    ToolResult(rendered, rendered, truncated = false, evidence = evidence)
                } else {
                    val notice = "\n\n[Tool result truncated to $maxChars characters by openLog.]"
                    val bounded = rendered.take((maxChars - notice.length).coerceAtLeast(0)) + notice
                    ToolResult(bounded, bounded, truncated = true, evidence = evidence)
                }
            }

            fun error(message: String): ToolResult = ToolResult(message, message, truncated = false)
        }
    }

    private fun confirmationDescription(toolName: String): String = when (toolName) {
        "open_log_file", "split_log_file" -> "Open or split a log file"
        "close_tab", "merge_tabs" -> "Change open log tabs"
        "start_tailing", "stop_tailing" -> "Change live tailing"
        "export_analysis", "export_filtered_log" -> "Write an export file"
        "save_annotations", "load_annotations" -> "Save or load annotation files"
        else -> "Perform a confirmation-required action"
    }

    private companion object {
        const val MAX_TOOL_ROUNDS = 12
        const val MAX_TOOL_RESULT_CHARS = 12_000
        val json = Json { ignoreUnknownKeys = true }
    }
}
