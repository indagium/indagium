package com.openlog.ai

import com.openlog.model.AiProviderProfile
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
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
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
    private val httpClient: HttpClient = HttpClient(CIO) { expectSuccess = false },
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
                LlmModel(id = id, displayName = item["name"]?.jsonPrimitive?.contentOrNull ?: id)
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
        val toolCalls = sortedMapOf<Int, ToolCallAccumulator>()
        var receivedTerminator = false
        var terminalHttpFailure = false
        try {
            httpClient.preparePost(endpoint("chat/completions")) {
                applyAuthorization()
                contentType(ContentType.Application.Json)
                setBody(request.toOpenAiJson().toString())
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
                    val line = channel.readUTF8Line() ?: break
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
        } catch (cancelled: CancellationException) {
            // Keep structured-concurrency cancellation intact: callers must never see a fake
            // completed/error event for a request they stopped.
            throw cancelled
        } catch (_: Exception) {
            emit(LlmStreamEvent.Error("Unable to connect to the configured model provider."))
        }
    }

    override fun close() {
        httpClient.close()
    }

    private fun endpoint(path: String): String = profile.baseUrl.trim().trimEnd('/') + "/$path"

    private fun io.ktor.client.request.HttpRequestBuilder.applyAuthorization() {
        apiKey.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private fun parseChunk(
        data: String,
        accumulators: MutableMap<Int, ToolCallAccumulator>,
    ): List<LlmStreamEvent> = try {
        val root = json.parseToJsonElement(data).jsonObject
        val choice = root["choices"]?.jsonArray.orEmpty()
            .firstOrNull { (it.jsonObject["index"]?.jsonPrimitive?.intOrNull ?: 0) == 0 }
            ?.jsonObject ?: return emptyList()
        val delta = choice["delta"] as? JsonObject ?: return emptyList()
        buildList {
            delta["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                add(LlmStreamEvent.TextDelta(it))
            }
            delta["tool_calls"]?.jsonArray.orEmpty().forEachIndexed { fallbackIndex, element ->
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
        temperature?.let { put("temperature", it) }
        maxTokens?.let { put("max_tokens", it) }
        put("messages", buildJsonArray { messages.forEach { add(it.toOpenAiJson()) } })
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

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
