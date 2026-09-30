package com.indagium.ai

import com.indagium.model.AiProviderProfile
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * OpenAI Chat Completions transport used first for LM Studio and later for any compatible
 * endpoint. It intentionally uses only the portable `/models` and `/chat/completions` contract.
 */
class OpenAiCompatibleProvider(
    private val profile: AiProviderProfile,
    private val apiKey: String = "",
    private val httpClient: HttpClient = HttpClient(CIO) {
        expectSuccess = false
        engine {
            // CIO's whole-call requestTimeout (15s default) applies even to this hand-rolled SSE
            // read, since only the official ktor SSE plugin is exempted from it. Local model
            // generation routinely exceeds 15s, so disable it here; the agent runner's own Stop
            // control is what cancels an in-flight request.
            requestTimeout = 0
        }
    },
) : LlmProvider, AutoCloseable {
    override val capabilities = ProviderCapabilities(
        streaming = true,
        toolCalls = true,
        modelDiscovery = true,
    )

    override suspend fun listModels(): ModelDiscoveryResult = try {
        val response = httpClient.get(endpoint("models")) { applyAuthorization() }
        if (!response.status.isSuccess()) {
            return ModelDiscoveryResult.Unavailable("Model discovery is unavailable (HTTP ${response.status.value}).")
        }
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val models = payload["data"]?.jsonArray.orEmpty().mapNotNull { model ->
            val item = model as? JsonObject ?: return@mapNotNull null
            item["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { id ->
                LlmModel(
                    id = id,
                    displayName = item["name"]?.jsonPrimitive?.contentOrNull ?: id,
                    reasoningEfforts = if (supportsReasoningEffort(id)) REASONING_EFFORTS else emptyList(),
                )
            }
        }.distinctBy { it.id }
        ModelDiscoveryResult.Available(models)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ModelDiscoveryResult.Unavailable("Model discovery is unavailable. Enter a model id manually.")
    }

    override fun streamChat(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        if (request.model.isBlank()) {
            emit(LlmStreamEvent.Error("Select or enter a model before starting an AI request."))
            return@flow
        }
        try {
            streamChatCompletion(request)
        } catch (cancelled: CancellationException) {
            // Keep structured-concurrency cancellation intact: callers must never see a fake
            // completed/error event for a request they stopped.
            throw cancelled
        } catch (_: Exception) {
            emit(LlmStreamEvent.Error("Unable to connect to the configured model provider."))
        }
    }

    /** The body of [streamChat] once a model is confirmed: post the request, read the SSE frames,
     *  and emit their events until the stream's own `[DONE]` terminator (or EOF/HTTP failure). Split
     *  out of [streamChat] itself so the outer flow keeps only the blank-model guard and the
     *  cancellation-preserving try/catch, which is all that needs to run before a model is chosen. */
    private suspend fun FlowCollector<LlmStreamEvent>.streamChatCompletion(request: LlmRequest) {
        val toolCalls = sortedMapOf<Int, ToolCallAccumulator>()
        var receivedTerminator = false
        var terminalHttpFailure = false
        httpClient.preparePost(endpoint("chat/completions")) {
            applyAuthorization()
            contentType(ContentType.Application.Json)
            setBody(request.toOpenAiJson().toString())
        }.execute { response ->
            if (!response.status.isSuccess()) {
                emit(httpFailureEvent(request, response))
                terminalHttpFailure = true
                return@execute
            }
            val dataLines = mutableListOf<String>()

            suspend fun dispatchFrame() {
                if (dataLines.isEmpty()) return
                val data = dataLines.joinToString("\n")
                dataLines.clear()
                if (data == "[DONE]") {
                    toolCalls.values.forEach { accumulator ->
                        accumulator.completeOrNull()?.let { emit(LlmStreamEvent.ToolCall(it)) }
                            ?: emit(LlmStreamEvent.Warning("Ignored incomplete tool call from provider."))
                    }
                    receivedTerminator = true
                    emit(LlmStreamEvent.Completed)
                    return
                }
                parseChunk(data, toolCalls).forEach { emit(it) }
            }

            val channel = response.bodyAsChannel()
            while (!receivedTerminator) {
                val line = channel.readLine() ?: break
                if (line.isEmpty()) {
                    dispatchFrame()
                } else if (line.startsWith("data:")) {
                    dataLines += line.removePrefix("data:").removePrefix(" ")
                }
            }
            dispatchFrame()
        }
        if (!receivedTerminator && !terminalHttpFailure) {
            emit(LlmStreamEvent.Warning("Provider stream ended before its completion marker."))
        }
    }

    /** Builds the one error event a non-2xx chat-completion response reports, with a specific
     *  vision-support hint when the request itself included an image. */
    private suspend fun httpFailureEvent(request: LlmRequest, response: HttpResponse): LlmStreamEvent.Error {
        val details = runCatching { response.bodyAsText().take(MAX_ERROR_BODY_CHARS) }.getOrNull().orEmpty()
        val message = if (request.messages.any { it.images.isNotEmpty() }) {
            "The selected model/provider rejected the screen image input (HTTP ${response.status.value}). " +
                details.ifBlank { "Choose a vision-capable model or provider." }
        } else {
            "Provider request failed (HTTP ${response.status.value})."
        }
        return LlmStreamEvent.Error(message)
    }

    override fun close() {
        httpClient.close()
    }

    private fun endpoint(path: String): String = aiProviderRequestBaseUrl(profile.baseUrl).trimEnd('/') + "/$path"

    private fun io.ktor.client.request.HttpRequestBuilder.applyAuthorization() {
        apiKey.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private fun parseChunk(
        data: String,
        accumulators: MutableMap<Int, ToolCallAccumulator>,
    ): List<LlmStreamEvent> = try {
        val root = json.parseToJsonElement(data).jsonObject
        buildList {
            // Sent as its own final chunk with an empty `choices` array, so this must be checked
            // independently rather than after bailing out on a missing/empty choice.
            (root["usage"] as? JsonObject)?.let { usage ->
                add(
                    LlmStreamEvent.Usage(
                        promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                        completionTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                        totalTokens = usage["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                    ),
                )
            }
            val delta = root["choices"]?.jsonArray.orEmpty()
                .firstOrNull { (it.jsonObject["index"]?.jsonPrimitive?.intOrNull ?: 0) == 0 }
                ?.jsonObject?.get("delta") as? JsonObject
            delta?.get("content")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                add(LlmStreamEvent.TextDelta(it))
            }
            delta?.get("tool_calls")?.jsonArray.orEmpty().forEachIndexed { fallbackIndex, element ->
                val toolDelta = element as? JsonObject ?: return@forEachIndexed
                val index = toolDelta["index"]?.jsonPrimitive?.intOrNull ?: fallbackIndex
                val id = toolDelta["id"]?.jsonPrimitive?.contentOrNull
                val function = toolDelta["function"] as? JsonObject
                val name = function?.get("name")?.jsonPrimitive?.contentOrNull
                val arguments = function?.get("arguments")?.jsonPrimitive?.contentOrNull
                accumulators.getOrPut(index) { ToolCallAccumulator(index) }.append(id, name, arguments)
                add(LlmStreamEvent.ToolCallDelta(index, id, name, arguments))
            }
        }
    } catch (_: Exception) {
        listOf(LlmStreamEvent.Warning("Ignored malformed provider stream event."))
    }

    private fun LlmRequest.toOpenAiJson(): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", true)
        // Opt into the final usage-only chunk (see parseChunk) so the sidebar can show a per-request
        // token count. Providers that don't recognise this option (or don't report usage at all)
        // simply never send that chunk - there is nothing else to opt out of here.
        put("stream_options", buildJsonObject { put("include_usage", true) })
        temperature?.let { put("temperature", it) }
        maxTokens?.let { put("max_tokens", it) }
        // OpenAI's harmony reasoning_effort field (low/medium/high). Servers that don't recognize
        // it - including most non-reasoning local models served via LM Studio - simply ignore it.
        reasoningEffort?.takeIf { it.isNotBlank() }?.let { put("reasoning_effort", it) }
        put("messages", buildJsonArray { messages.toOpenAiJsonMessages().forEach { add(it) } })
        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters)
                        })
                    })
                }
            })
        }
    }

    private fun LlmMessage.toOpenAiJson(): JsonObject = buildJsonObject {
        put("role", role.name.lowercase())
        if (content == null) put("content", JsonNull) else put("content", content)
        toolCallId?.let { put("tool_call_id", it) }
        if (toolCalls.isNotEmpty()) {
            put("tool_calls", buildJsonArray {
                toolCalls.forEach { call ->
                    add(buildJsonObject {
                        put("id", call.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", call.name)
                            put("arguments", call.argumentsJson)
                        })
                    })
                }
            })
        }
    }

    /** OpenAI Chat Completions requires tool output to remain text; attach an image returned by a
     * tool in a separate following user message, matching the documented tool-message shape. */
    private fun LlmMessage.toOpenAiJsonMessages(): List<JsonObject> {
        if (images.isEmpty()) return listOf(toOpenAiJson())
        val output = mutableListOf<JsonObject>()
        if (role == LlmRole.TOOL) output += toOpenAiJson()
        output += buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                content?.takeIf(String::isNotBlank)?.let { text ->
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    })
                }
                images.forEach { image ->
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject {
                            put("url", "data:${image.mimeType};base64,${image.base64}")
                        })
                    })
                }
            })
        }
        return output
    }

    /** Keep every tool-role reply from one assistant tool-call batch adjacent. Image payloads
     * returned by tools are emitted as following user messages, after the batch's tool replies,
     * because OpenAI-compatible APIs generally require all requested tool_call_id responses
     * before another message begins. */
    private fun List<LlmMessage>.toOpenAiJsonMessages(): List<JsonObject> {
        val output = mutableListOf<JsonObject>()
        val deferredToolImages = mutableListOf<JsonObject>()

        fun flushDeferredImages() {
            output += deferredToolImages
            deferredToolImages.clear()
        }
        for (message in this) {
            if (message.role != LlmRole.TOOL) flushDeferredImages()
            val expanded = message.toOpenAiJsonMessages()
            if (message.role == LlmRole.TOOL && message.images.isNotEmpty()) {
                output += expanded.first()
                deferredToolImages += expanded.drop(1)
            } else {
                output += expanded
            }
        }
        flushDeferredImages()
        return output
    }

    private data class ToolCallAccumulator(
        val index: Int,
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    ) {
        fun append(id: String?, name: String?, argumentsDelta: String?) {
            if (id != null) this.id = id
            if (name != null) this.name = name
            if (argumentsDelta != null) arguments.append(argumentsDelta)
        }

        fun completeOrNull(): LlmToolCall? = id?.takeIf { it.isNotBlank() }?.let { callId ->
            name?.takeIf { it.isNotBlank() }?.let { callName ->
                LlmToolCall(callId, callName, arguments.toString())
            }
        }
    }

    /**
     * There is no standard way to ask an OpenAI-compatible `/models` endpoint whether a model
     * supports reasoning, so this matches known local reasoning-model families (gpt-oss, the
     * DeepSeek-R1/QwQ/Qwen3 lineage, Phi-4-reasoning, Magistral) by id, the same way
     * [AnthropicMessagesProvider] recognizes thinking-capable Claude models by id.
     */
    private fun supportsReasoningEffort(model: String): Boolean = REASONING_MODEL_REGEX.containsMatchIn(model)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        // Bounds how much of a failed chat-completion response body is echoed into the error event.
        const val MAX_ERROR_BODY_CHARS = 1_000

        // Reasoning levels offered for reasoning-capable models; LM Studio and other
        // llama.cpp-based servers accept this fixed low/medium/high set for gpt-oss's harmony
        // format. There is no per-model discovery for this, unlike Anthropic's model list.
        val REASONING_EFFORTS = listOf("low", "medium", "high")
        val REASONING_MODEL_REGEX = Regex(
            "gpt-oss|deepseek.*r1|qwq|qwen3|phi-4-reasoning|magistral|reasoning|thinking",
            RegexOption.IGNORE_CASE,
        )
    }
}
