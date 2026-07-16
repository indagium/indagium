package com.openlog.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Native transport for Anthropic's streaming Messages API. */
class AnthropicMessagesProvider(
    private val baseUrl: String = "https://api.anthropic.com/v1",
    private val apiKey: String = "",
    private val httpClient: HttpClient = HttpClient(CIO) {
        expectSuccess = false
        engine {
            requestTimeout = 0
        }
    },
) : LlmProvider, AutoCloseable {
    // Base URL is normalized to end with the API path segment. Anthropic's own console shows the
    // host as a bare `https://api.anthropic.com`, so a path-less value is treated as shorthand for
    // `/v1` rather than hitting `/messages` at the root (which 404s).
    private val apiBaseUrl: String = aiProviderRequestBaseUrl(baseUrl)

    override val capabilities = ProviderCapabilities(
        streaming = true,
        toolCalls = true,
        modelDiscovery = true,
    )

    override suspend fun listModels(): ModelDiscoveryResult = try {
        val response = httpClient.get(endpoint("models")) {
            apiKey.takeIf { it.isNotBlank() }?.let { header(API_KEY_HEADER, it) }
            header(ANTHROPIC_VERSION_HEADER, ANTHROPIC_VERSION)
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }
        if (!response.status.isSuccess()) {
            ModelDiscoveryResult.Unavailable("Anthropic model list failed (HTTP ${response.status.value}).")
        } else {
            val models = (json.parseToJsonElement(response.bodyAsText()).jsonObject["data"] as? JsonArray)
                ?.mapNotNull { entry ->
                    val model = entry as? JsonObject ?: return@mapNotNull null
                    val id = model["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val displayName = model["display_name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: id
                    LlmModel(id, displayName, if (supportsExtendedThinking(id)) THINKING_EFFORTS else emptyList())
                }
                .orEmpty()
            if (models.isEmpty()) ModelDiscoveryResult.Unavailable("Anthropic returned no models.")
            else ModelDiscoveryResult.Available(models)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ModelDiscoveryResult.Unavailable("Unable to reach Anthropic to list models.")
    }

    override fun streamChat(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        if (request.model.isBlank()) {
            emit(LlmStreamEvent.Error("Select or enter a model before starting an AI request."))
            return@flow
        }

        val state = StreamState()
        var terminalHttpFailure = false
        try {
            httpClient.preparePost(endpoint("messages")) {
                apiKey.takeIf { it.isNotBlank() }?.let { header(API_KEY_HEADER, it) }
                header(ANTHROPIC_VERSION_HEADER, ANTHROPIC_VERSION)
                header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
                contentType(ContentType.Application.Json)
                setBody(request.toAnthropicJson().toString())
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    emit(LlmStreamEvent.Error("Provider request failed (HTTP ${response.status.value})."))
                    terminalHttpFailure = true
                    return@execute
                }

                val dataLines = mutableListOf<String>()

                suspend fun dispatchFrame() {
                    if (dataLines.isEmpty()) return
                    val data = dataLines.joinToString("\n")
                    dataLines.clear()
                    if (data == "[DONE]") {
                        completePendingToolCalls(state).forEach { emit(it) }
                        state.terminal = true
                        emit(LlmStreamEvent.Completed)
                    } else {
                        parseEvent(data, state).forEach { emit(it) }
                    }
                }

                val channel = response.bodyAsChannel()
                while (!state.terminal) {
                    val line = channel.readLine() ?: break
                    if (line.isEmpty()) {
                        dispatchFrame()
                    } else if (line.startsWith("data:")) {
                        dataLines += line.removePrefix("data:").removePrefix(" ")
                    }
                }
                dispatchFrame()
            }
            if (!state.terminal && !terminalHttpFailure) {
                emit(LlmStreamEvent.Warning("Provider stream ended before its completion marker."))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emit(LlmStreamEvent.Error("Unable to connect to the configured model provider."))
        }
    }

    override fun close() {
        httpClient.close()
    }

    private fun endpoint(path: String): String = apiBaseUrl.trimEnd('/') + "/$path"

    private fun parseContentBlockStart(root: JsonObject, state: StreamState): List<LlmStreamEvent> {
        val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
        val block = root["content_block"] as? JsonObject
        return when (block?.get("type")?.jsonPrimitive?.contentOrNull) {
            "tool_use" -> {
                val accumulator = state.toolCalls.getOrPut(index) { ToolCallAccumulator() }
                accumulator.id = block["id"]?.jsonPrimitive?.contentOrNull
                accumulator.name = block["name"]?.jsonPrimitive?.contentOrNull
                listOf(LlmStreamEvent.ToolCallDelta(index, accumulator.id, accumulator.name))
            }

            "thinking" -> {
                state.reasoning[index] = ReasoningAccumulator()
                emptyList()
            }

            "redacted_thinking" -> {
                state.reasoning[index] = ReasoningAccumulator(redactedData = block["data"]?.jsonPrimitive?.contentOrNull)
                emptyList()
            }

            else -> emptyList()
        }
    }

    private fun parseContentBlockDelta(root: JsonObject, state: StreamState): List<LlmStreamEvent> {
        val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
        val delta = root["delta"] as? JsonObject
        return when (delta?.get("type")?.jsonPrimitive?.contentOrNull) {
            "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotEmpty() }
                ?.let { listOf(LlmStreamEvent.TextDelta(it)) }
                ?: emptyList()

            "thinking_delta" -> {
                delta["thinking"]?.jsonPrimitive?.contentOrNull?.let {
                    state.reasoning.getOrPut(index) { ReasoningAccumulator() }.thinking.append(it)
                }
                emptyList()
            }

            "signature_delta" -> {
                delta["signature"]?.jsonPrimitive?.contentOrNull?.let {
                    state.reasoning.getOrPut(index) { ReasoningAccumulator() }.signature.append(it)
                }
                emptyList()
            }

            "input_json_delta" -> {
                val partialJson = delta["partial_json"]?.jsonPrimitive?.contentOrNull
                if (partialJson.isNullOrEmpty()) {
                    emptyList()
                } else {
                    state.toolCalls.getOrPut(index) { ToolCallAccumulator() }.arguments.append(partialJson)
                    listOf(LlmStreamEvent.ToolCallDelta(index, argumentsDelta = partialJson))
                }
            }

            else -> emptyList()
        }
    }

    private fun parseContentBlockStop(root: JsonObject, state: StreamState): List<LlmStreamEvent> {
        val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
        state.reasoning.remove(index)?.let { reasoning ->
            return reasoning.completeOrNull()?.let { listOf(LlmStreamEvent.ReasoningComplete(it)) } ?: emptyList()
        }
        val accumulator = state.toolCalls.remove(index) ?: return emptyList()
        return accumulator.completeOrNull()?.let { listOf(LlmStreamEvent.ToolCall(it)) }
            ?: listOf(LlmStreamEvent.Warning("Ignored incomplete tool call from provider."))
    }

    private fun parseEvent(data: String, state: StreamState): List<LlmStreamEvent> = try {
        val root = json.parseToJsonElement(data).jsonObject
        when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "message_start" -> {
                val usage = (root["message"] as? JsonObject)?.get("usage") as? JsonObject
                state.inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull
                emptyList()
            }

            "content_block_start" -> parseContentBlockStart(root, state)

            "content_block_delta" -> parseContentBlockDelta(root, state)

            "content_block_stop" -> parseContentBlockStop(root, state)

            "message_delta" -> {
                val usage = root["usage"] as? JsonObject
                state.outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull
                if (usage == null) {
                    emptyList()
                } else {
                    listOf(
                        LlmStreamEvent.Usage(
                            promptTokens = state.inputTokens ?: 0,
                            completionTokens = state.outputTokens ?: 0,
                            totalTokens = (state.inputTokens ?: 0) + (state.outputTokens ?: 0),
                        ),
                    )
                }
            }

            "message_stop" -> {
                state.terminal = true
                buildList {
                    addAll(completePendingToolCalls(state))
                    add(LlmStreamEvent.Completed)
                }
            }

            "error" -> {
                state.terminal = true
                val message = ((root["error"] as? JsonObject)?.get("message") as? JsonPrimitive)
                    ?.contentOrNull
                    ?: "Anthropic stream reported an error."
                listOf(LlmStreamEvent.Error(message))
            }

            // Anthropic sends pings and message_start/content block metadata that do not map to
            // provider-neutral events. Unknown event types are ignored for forward compatibility.
            else -> emptyList()
        }
    } catch (_: Exception) {
        listOf(LlmStreamEvent.Warning("Ignored malformed provider stream event."))
    }

    private fun completePendingToolCalls(state: StreamState): List<LlmStreamEvent> = buildList {
        state.toolCalls.toSortedMap().values.forEach { accumulator ->
            accumulator.completeOrNull()?.let { add(LlmStreamEvent.ToolCall(it)) }
                ?: add(LlmStreamEvent.Warning("Ignored incomplete tool call from provider."))
        }
        state.toolCalls.clear()
    }

    private fun LlmRequest.toAnthropicJson(): JsonObject = buildJsonObject {
        put("model", model)
        val thinkingBudget = reasoningEffort
            ?.takeIf { it.isNotBlank() && supportsExtendedThinking(model) }
            ?.let(::thinkingBudgetFor)
        // The thinking budget is drawn from max_tokens, so leave room for a visible answer on top.
        put("max_tokens", (maxTokens ?: DEFAULT_MAX_TOKENS).let { if (thinkingBudget != null) it.coerceAtLeast(thinkingBudget + DEFAULT_MAX_TOKENS) else it })
        put("stream", true)
        if (thinkingBudget != null) {
            put("thinking", buildJsonObject {
                put("type", "enabled")
                put("budget_tokens", thinkingBudget)
            })
            // Extended thinking requires the model's default temperature; a custom value is rejected.
        } else {
            temperature?.let { put("temperature", it) }
        }

        messages.filter { it.role == LlmRole.SYSTEM }
            .mapNotNull { it.content?.takeIf(String::isNotBlank) }
            .joinToString("\n\n")
            .takeIf(String::isNotBlank)
            ?.let { put("system", it) }

        put("messages", buildJsonArray {
            messages.filter { it.role != LlmRole.SYSTEM }.forEach { add(it.toAnthropicMessage()) }
        })
        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                tools.forEach { tool ->
                    add(buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", tool.parameters)
                    })
                }
            })
        }
    }

    private fun LlmMessage.toAnthropicMessage(): JsonObject = buildJsonObject {
        when (role) {
            LlmRole.USER -> {
                put("role", "user")
                put("content", content ?: "")
            }

            LlmRole.ASSISTANT -> {
                put("role", "assistant")
                if (toolCalls.isEmpty() && reasoning.isEmpty()) {
                    put("content", content ?: "")
                } else {
                    put("content", buildJsonArray {
                        // Thinking blocks must precede text/tool_use, replayed verbatim with their
                        // signatures, or Anthropic rejects a thinking-enabled tool-use turn.
                        reasoning.forEach { block -> add(block.toAnthropicBlock()) }
                        content?.takeIf(String::isNotEmpty)?.let { text ->
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", text)
                            })
                        }
                        toolCalls.forEach { call ->
                            add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", call.id)
                                put("name", call.name)
                                put("input", parseToolArguments(call.argumentsJson))
                            })
                        }
                    })
                }
            }

            LlmRole.TOOL -> {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", toolCallId ?: "")
                        put("content", content ?: "")
                    })
                })
            }

            LlmRole.SYSTEM -> error("System messages are lifted to the top-level system field.")
        }
    }

    private fun parseToolArguments(argumentsJson: String) = try {
        json.parseToJsonElement(argumentsJson)
            .takeIf { it is JsonObject }
            ?: buildJsonObject { }
    } catch (_: Exception) {
        buildJsonObject { }
    }

    private fun LlmReasoning.toAnthropicBlock(): JsonObject = buildJsonObject {
        if (redactedData != null) {
            put("type", "redacted_thinking")
            put("data", redactedData)
        } else {
            put("type", "thinking")
            put("thinking", thinking ?: "")
            signature?.let { put("signature", it) }
        }
    }

    private fun supportsExtendedThinking(model: String): Boolean =
        model.contains("claude-3-7") || THINKING_MODEL_REGEX.containsMatchIn(model)

    private fun thinkingBudgetFor(effort: String): Int = when (effort.lowercase()) {
        "low" -> THINKING_BUDGET_LOW
        "high" -> THINKING_BUDGET_HIGH
        else -> THINKING_BUDGET_MEDIUM // medium, and any unrecognized level, use a balanced budget
    }

    private data class StreamState(
        val toolCalls: MutableMap<Int, ToolCallAccumulator> = sortedMapOf(),
        val reasoning: MutableMap<Int, ReasoningAccumulator> = sortedMapOf(),
        var inputTokens: Int? = null,
        var outputTokens: Int? = null,
        var terminal: Boolean = false,
    )

    private data class ReasoningAccumulator(
        val thinking: StringBuilder = StringBuilder(),
        val signature: StringBuilder = StringBuilder(),
        val redactedData: String? = null,
    ) {
        fun completeOrNull(): LlmReasoning? {
            if (redactedData != null) return LlmReasoning(redactedData = redactedData)
            val text = thinking.toString()
            if (text.isBlank()) return null
            return LlmReasoning(thinking = text, signature = signature.toString().takeIf(String::isNotBlank))
        }
    }

    private data class ToolCallAccumulator(
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    ) {
        fun completeOrNull(): LlmToolCall? {
            val callId = id?.takeIf(String::isNotBlank) ?: return null
            val callName = name?.takeIf(String::isNotBlank) ?: return null
            return LlmToolCall(callId, callName, arguments.toString().ifBlank { "{}" })
        }
    }

    private companion object {
        const val API_KEY_HEADER = "x-api-key"
        const val ANTHROPIC_VERSION_HEADER = "anthropic-version"
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val DEFAULT_MAX_TOKENS = 4096
        const val THINKING_BUDGET_LOW = 4_096
        const val THINKING_BUDGET_MEDIUM = 12_288
        const val THINKING_BUDGET_HIGH = 24_576

        // Reasoning levels offered for thinking-capable models, mapped to token budgets in
        // thinkingBudgetFor. Anthropic exposes no per-model effort list, so this is a fixed set.
        val THINKING_EFFORTS = listOf("low", "medium", "high")

        // Extended thinking is available on Claude 3.7 (matched by the explicit contains() check
        // in supportsExtendedThinking below, since its version number sits before the family name:
        // "claude-3-7-sonnet") and on every family/major-version-4-or-later model since ("claude-
        // opus-4", "claude-sonnet-4-5", "claude-opus-4-8", "claude-sonnet-5", "claude-fable-5", ...).
        // (QUAL-3) The old "claude-(opus|sonnet|haiku)-4" pattern only matched major version 4
        // exactly, so it silently stopped matching as soon as a "claude-sonnet-5"-shaped id showed
        // up from listModels. Matching >=4 (single digit 4-9, or two-plus digits for a future
        // double-digit major) plus any family name errs toward "supports thinking" for a newer
        // model, which is safe: the effort param is only ever sent when the user picks a reasoning
        // level, and Anthropic is the one returning these ids from listModels in the first place.
        val THINKING_MODEL_REGEX = Regex("claude-(opus|sonnet|haiku|fable)-([4-9]|\\d{2,})")
        val json = Json { ignoreUnknownKeys = true }
    }
}
