package com.openlog.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * Provider-neutral contract used by the in-app agent. Provider implementations own transport
 * details; callers only reason about messages, tool calls, and streamed run events.
 */
interface LlmProvider {
    val capabilities: ProviderCapabilities

    /**
     * Returns [ModelDiscoveryResult.Unavailable] rather than throwing when an endpoint does not
     * expose a model-list API. The profile's manually entered model remains usable in that case.
     */
    suspend fun listModels(): ModelDiscoveryResult

    /**
     * Starts one streaming completion. Cancelling collection cancels its underlying request.
     * Transport and parse failures are emitted as [LlmStreamEvent.Error]; cancellation is
     * deliberately propagated and never turned into a synthetic completed event.
     */
    fun streamChat(request: LlmRequest): Flow<LlmStreamEvent>
}

data class ProviderCapabilities(
    val streaming: Boolean,
    val toolCalls: Boolean,
    val modelDiscovery: Boolean,
)

data class LlmModel(
    val id: String,
    val displayName: String = id,
)

sealed interface ModelDiscoveryResult {
    data class Available(val models: List<LlmModel>) : ModelDiscoveryResult

    /** An unavailable list must not prevent manually entering or retaining a model id. */
    data class Unavailable(val message: String) : ModelDiscoveryResult
}

enum class LlmRole { SYSTEM, USER, ASSISTANT, TOOL }

data class LlmMessage(
    val role: LlmRole,
    val content: String? = null,
    val toolCalls: List<LlmToolCall> = emptyList(),
    val toolCallId: String? = null,
)

data class LlmToolCall(
    val id: String,
    val name: String,
    /** Raw JSON arguments are preserved so the gateway can validate against its own schema. */
    val argumentsJson: String,
)

data class LlmToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

data class LlmRequest(
    val model: String,
    val messages: List<LlmMessage>,
    val tools: List<LlmToolDefinition> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)

sealed interface LlmStreamEvent {
    data class TextDelta(val text: String) : LlmStreamEvent

    /** A raw transport delta, including only the fields present in that SSE message. */
    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val argumentsDelta: String? = null,
    ) : LlmStreamEvent

    /** A complete tool call assembled from one or more [ToolCallDelta] events. */
    data class ToolCall(val call: LlmToolCall) : LlmStreamEvent

    /** The provider explicitly sent a valid stream terminator. */
    data object Completed : LlmStreamEvent

    /** One malformed non-terminal SSE message was skipped while the stream continued. */
    data class Warning(val message: String) : LlmStreamEvent

    /**
     * Token accounting for one request, if the provider reports it. OpenAI-compatible servers
     * (including LM Studio) only include this on the request's final chunk, and only when asked
     * via `stream_options.include_usage` - so it may never arrive for a given provider.
     */
    data class Usage(val promptTokens: Int, val completionTokens: Int, val totalTokens: Int) : LlmStreamEvent

    /** A failed request or an unusable terminal response. Cancellation is never represented here. */
    data class Error(val message: String) : LlmStreamEvent
}
