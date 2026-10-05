package com.indagium.testing.tracker

import com.indagium.ai.LlmProvider
import com.indagium.ai.LlmRequest
import com.indagium.ai.LlmRole
import com.indagium.ai.LlmStreamEvent
import com.indagium.ai.LlmToolCall
import com.indagium.ai.ModelDiscoveryResult
import com.indagium.ai.ProviderCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList

// Shared pieces of the tracker tests: a fake MCP client that records its calls, and a scripted model that makes one tool call per turn.

/** A tracker that answers from a table, records every call and never contains the token itself. */
internal class FakeTrackerClient(
    private val tools: List<TrackerTool> = listOf(createIssueTool()),
    private val connectFailure: String? = null,
    private val result: (name: String, args: Map<String, Any?>) -> TrackerToolResult = { _, _ -> TrackerToolResult("created ABC-17", isError = false) },
) : TrackerMcpClient {
    val calls = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()

    @Volatile
    var closed = false

    override suspend fun connect() {
        connectFailure?.let { throw TrackerMcpException(it) }
    }

    override suspend fun listTools(): List<TrackerTool> = tools

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): TrackerToolResult {
        calls += name to arguments
        return result(name, arguments)
    }

    override suspend fun close() {
        closed = true
    }
}

internal fun createIssueTool(name: String = "create_issue") = TrackerTool(
    name, "Create an issue in the tracker",
    buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("summary", buildJsonObject { put("type", "string") }) })
    },
)

/** A model that makes the calls of [script] one per turn (it counts the tool results in the request), then says it is done. */
internal class ScriptedTrackerModel(private val script: List<Pair<String, JsonObject>>) : LlmProvider {
    val requests = CopyOnWriteArrayList<LlmRequest>()

    override val capabilities = ProviderCapabilities(streaming = true, toolCalls = true, modelDiscovery = false)

    override suspend fun listModels(): ModelDiscoveryResult = ModelDiscoveryResult.Unavailable("not used")

    override fun streamChat(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        requests += request.copy(messages = request.messages.toList())
        val answered = request.messages.count { it.role == LlmRole.TOOL }
        val next = script.getOrNull(answered)
        if (next == null) {
            emit(LlmStreamEvent.TextDelta("Done."))
        } else {
            emit(LlmStreamEvent.ToolCall(LlmToolCall("call-$answered", next.first, next.second.toString())))
        }
        emit(LlmStreamEvent.Completed)
    }

    /** Everything the model was ever shown. */
    fun everythingSeen(): String = requests.flatMap { it.messages }.joinToString("\n") { it.content.orEmpty() }

    fun toolResults(): List<String> = requests.lastOrNull()?.messages?.filter { it.role == LlmRole.TOOL }?.map { it.content.orEmpty() }.orEmpty()
}

internal fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
