package com.openlog.ai

import com.openlog.model.AiProviderKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** The small process surface needed by the JSONL transport, suitable for deterministic fakes. */
interface CodexAppServerProcess : Closeable {
    fun writeLine(line: String)

    fun readLine(): String?

    fun isAlive(): Boolean

    fun exitCode(): Int?

    fun destroy()

    override fun close() = destroy()
}

fun interface CodexAppServerProcessFactory {
    fun start(command: List<String>): CodexAppServerProcess
}

data class CodexClientInfo(
    val name: String,
    val title: String,
    val version: String,
)

data class CodexThreadOptions(
    val cwd: String? = null,
    val model: String? = null,
    val approvalPolicy: String? = null,
    val sandbox: String? = null,
    val ephemeral: Boolean? = null,
)

data class CodexThread(
    val id: String,
    val status: String? = null,
    val cwd: String? = null,
    val model: String? = null,
)

data class CodexTurn(
    val id: String,
    val threadId: String? = null,
    val status: String? = null,
)

data class CodexInitializeResult(
    val serverInfo: JsonObject?,
    val raw: JsonObject,
)

/** Mirrors app-server's `TokenUsageBreakdown` (see `thread/tokenUsage/updated`'s schema). */
data class CodexTokenUsage(
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val outputTokens: Long,
    val reasoningOutputTokens: Long,
    val totalTokens: Long,
)

sealed interface CodexAppServerEvent {
    data class Initialized(val result: CodexInitializeResult) : CodexAppServerEvent

    data class ThreadStarted(val thread: CodexThread) : CodexAppServerEvent

    data class ThreadStatusChanged(
        val threadId: String,
        val status: String?,
    ) : CodexAppServerEvent

    data class TurnStarted(val turn: CodexTurn) : CodexAppServerEvent

    data class AgentTextDelta(
        val threadId: String?,
        val turnId: String?,
        val itemId: String?,
        val text: String,
    ) : CodexAppServerEvent

    data class McpToolProgress(
        val threadId: String?,
        val message: String,
    ) : CodexAppServerEvent

    data class McpServerStartupStatus(
        val serverName: String?,
        val status: String?,
        val message: String?,
    ) : CodexAppServerEvent

    /** A request made by app-server to its host, such as an MCP authentication elicitation. */
    data class ServerRequest(
        val id: Long,
        val method: String,
        val params: JsonObject,
    ) : CodexAppServerEvent

    data class TurnCompleted(
        val threadId: String?,
        val turnId: String,
        val status: String?,
    ) : CodexAppServerEvent

    /** Cumulative token usage for the thread, pushed after each turn (`thread/tokenUsage/updated`). */
    data class TokenUsageUpdated(
        val threadId: String?,
        val turnId: String?,
        val total: CodexTokenUsage,
    ) : CodexAppServerEvent

    data class TurnFailed(
        val threadId: String?,
        val turnId: String,
        val message: String,
    ) : CodexAppServerEvent

    data class ProtocolError(
        val message: String,
        val line: String? = null,
    ) : CodexAppServerEvent

    data class ProcessExited(val exitCode: Int?) : CodexAppServerEvent
}

class CodexAppServerException(
    message: String,
    val requestId: Long? = null,
    val errorCode: Int? = null,
    val errorData: JsonElement? = null,
) : IllegalStateException(message)

class CodexAppServerClient(
    private val process: CodexAppServerProcess,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : Closeable {
    private val clientJob = SupervisorJob(scope.coroutineContext[Job])
    private val clientScope = CoroutineScope(scope.coroutineContext + clientJob)
    private val nextRequestId = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val writeLock = Any()
    private val eventBus = MutableSharedFlow<CodexAppServerEvent>(replay = 128)
    private val readerJob: Job = clientScope.launch { readMessages() }
    private var closed = false

    val events: SharedFlow<CodexAppServerEvent> = eventBus.asSharedFlow()

    suspend fun initialize(
        clientInfo: CodexClientInfo = DEFAULT_CLIENT_INFO,
        capabilities: JsonObject? = null,
    ): CodexInitializeResult {
        val params = buildJsonObject {
            put("clientInfo", buildJsonObject {
                put("name", clientInfo.name)
                put("title", clientInfo.title)
                put("version", clientInfo.version)
            })
            capabilities?.let { put("capabilities", it) }
        }
        val result = request("initialize", params).asObject("initialize")
        sendNotification("initialized")
        val initialized = CodexInitializeResult(
            serverInfo = result["serverInfo"]?.jsonObject,
            raw = result,
        )
        eventBus.emit(CodexAppServerEvent.Initialized(initialized))
        return initialized
    }

    suspend fun startThread(options: CodexThreadOptions = CodexThreadOptions()): CodexThread =
        parseThread(request("thread/start", options.toJson()), "thread/start")

    suspend fun resumeThread(
        threadId: String,
        options: CodexThreadOptions = CodexThreadOptions(),
    ): CodexThread {
        require(threadId.isNotBlank()) { "threadId must not be blank" }
        val params = buildJsonObject {
            put("threadId", threadId)
            options.putInto(this)
        }
        return parseThread(request("thread/resume", params), "thread/resume")
    }

    suspend fun startOrResumeThread(
        threadId: String?,
        options: CodexThreadOptions = CodexThreadOptions(),
    ): CodexThread = if (threadId.isNullOrBlank()) {
        startThread(options)
    } else {
        resumeThread(threadId, options)
    }

    suspend fun startTurn(
        threadId: String,
        text: String,
        model: String? = null,
        cwd: String? = null,
        effort: String? = null,
    ): CodexTurn {
        require(threadId.isNotBlank()) { "threadId must not be blank" }
        val params = buildJsonObject {
            put("threadId", threadId)
            put("input", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                    put("text_elements", buildJsonArray { })
                })
            })
            model?.let { put("model", it) }
            cwd?.let { put("cwd", it) }
            effort?.let { put("effort", it) }
        }
        return parseTurn(request("turn/start", params), "turn/start")
    }

    suspend fun interruptTurn(threadId: String, turnId: String) {
        require(threadId.isNotBlank()) { "threadId must not be blank" }
        require(turnId.isNotBlank()) { "turnId must not be blank" }
        request(
            "turn/interrupt",
            buildJsonObject {
                put("threadId", threadId)
                put("turnId", turnId)
            },
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        val failure = CodexAppServerException("Codex app-server client was closed")
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
        readerJob.cancel()
        process.destroy()
        clientJob.cancel()
    }

    private suspend fun request(method: String, params: JsonObject): JsonElement {
        check(!closed) { "Codex app-server client is closed" }
        val id = nextRequestId.getAndIncrement()
        val response = CompletableDeferred<JsonElement>()
        pending[id] = response
        try {
            send(buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
            })
            return response.await()
        } finally {
            pending.remove(id)
        }
    }

    private fun sendNotification(method: String) {
        send(buildJsonObject { put("method", method) })
    }

    private fun send(message: JsonObject) {
        val line = json.encodeToString(JsonObject.serializer(), message)
        synchronized(writeLock) {
            check(!closed) { "Codex app-server client is closed" }
            process.writeLine(line)
        }
    }

    suspend fun resolveServerRequest(id: Long, result: JsonObject) {
        send(buildJsonObject {
            put("id", id)
            put("result", result)
        })
    }

    suspend fun rejectServerRequest(id: Long, message: String) {
        send(buildJsonObject {
            put("id", id)
            put("error", buildJsonObject {
                put("code", -32000)
                put("message", message)
            })
        })
    }

    private suspend fun readMessages() {
        try {
            while (!closed) {
                val line = process.readLine() ?: break
                if (line.isBlank()) continue
                handleMessage(line)
            }
            if (!closed) {
                val failure = CodexAppServerException("Codex app-server process exited")
                pending.values.forEach { it.completeExceptionally(failure) }
                pending.clear()
                eventBus.emit(CodexAppServerEvent.ProcessExited(process.exitCode()))
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!closed) {
                val failure = CodexAppServerException(
                    error.message ?: "Codex app-server process failed",
                )
                pending.values.forEach { it.completeExceptionally(failure) }
                pending.clear()
                eventBus.emit(CodexAppServerEvent.ProtocolError(failure.message ?: "Process failed"))
            }
        }
    }

    private suspend fun handleMessage(line: String) {
        val message = try {
            json.parseToJsonElement(line).jsonObject
        } catch (error: Throwable) {
            eventBus.emit(
                CodexAppServerEvent.ProtocolError(
                    message = "Invalid JSONL message: ${error.message ?: "parse error"}",
                    line = line,
                ),
            )
            return
        }

        val id = message["id"]?.jsonPrimitive?.longOrNull
        val method = message["method"]?.jsonPrimitive?.contentOrNull
        val params = message["params"]?.jsonObject ?: buildJsonObject { }
        if (id != null && method != null) {
            eventBus.emit(CodexAppServerEvent.ServerRequest(id, method, params))
            return
        }
        if (id != null) {
            val response = pending[id]
            if (response == null) {
                eventBus.emit(CodexAppServerEvent.ProtocolError("Response for unknown request id $id", line))
            } else {
                val error = message["error"]?.jsonObject
                if (error != null) {
                    response.completeExceptionally(
                        CodexAppServerException(
                            message = error.string("message") ?: "Codex app-server request failed",
                            requestId = id,
                            errorCode = error["code"]?.jsonPrimitive?.intOrNull,
                            errorData = error["data"],
                        ),
                    )
                } else {
                    response.complete(message["result"] ?: JsonNull)
                }
            }
            return
        }

        method ?: return
        notification(method, params)?.let { eventBus.emit(it) }
    }

    private fun notification(method: String, params: JsonObject): CodexAppServerEvent? = when (method) {
        "thread/started" -> params["thread"]?.jsonObject?.let {
            CodexAppServerEvent.ThreadStarted(parseThreadObject(it))
        }
        "thread/status/changed" -> {
            val threadId = params.string("threadId") ?: return null
            CodexAppServerEvent.ThreadStatusChanged(threadId, params.statusType())
        }
        "turn/started" -> params["turn"]?.jsonObject?.let {
            CodexAppServerEvent.TurnStarted(parseTurnObject(it))
        }
        "item/agentMessage/delta", "item/agent_message/delta" -> {
            CodexAppServerEvent.AgentTextDelta(
                threadId = params.string("threadId"),
                turnId = params.string("turnId"),
                itemId = params.string("itemId"),
                text = params.string("delta") ?: "",
            )
        }
        "item/mcpToolCall/progress", "item/mcp_tool_call/progress" -> {
            CodexAppServerEvent.McpToolProgress(
                threadId = params.string("threadId"),
                message = params.string("message")
                    ?: params.string("progress")
                    ?: params.statusMessage()
                    ?: "MCP tool is running…",
            )
        }
        "mcpServer/startupStatus/updated", "mcp_server/startup_status/updated" -> {
            CodexAppServerEvent.McpServerStartupStatus(
                serverName = params.string("name"),
                status = params.string("status") ?: params.statusType(),
                message = params.string("error") ?: params.statusMessage(),
            )
        }
        "turn/completed" -> {
            val turn = params["turn"]?.jsonObject
            val turnId = turn?.string("id") ?: params.string("turnId") ?: return null
            val status = turn?.statusType() ?: params.statusType()
            val errorMessage = turn?.statusMessage() ?: params.statusMessage()
            if (status == "failed" || errorMessage != null) {
                CodexAppServerEvent.TurnFailed(
                    threadId = turn?.string("threadId") ?: params.string("threadId"),
                    turnId = turnId,
                    message = errorMessage ?: "Codex turn failed",
                )
            } else {
                CodexAppServerEvent.TurnCompleted(
                    threadId = turn?.string("threadId") ?: params.string("threadId"),
                    turnId = turnId,
                    status = status,
                )
            }
        }
        "thread/tokenUsage/updated" -> {
            val total = params["tokenUsage"]?.jsonObject?.get("total")?.jsonObject ?: return null
            CodexAppServerEvent.TokenUsageUpdated(
                threadId = params.string("threadId"),
                turnId = params.string("turnId"),
                total = CodexTokenUsage(
                    inputTokens = total.long("inputTokens"),
                    cachedInputTokens = total.long("cachedInputTokens"),
                    outputTokens = total.long("outputTokens"),
                    reasoningOutputTokens = total.long("reasoningOutputTokens"),
                    totalTokens = total.long("totalTokens"),
                ),
            )
        }
        "error" -> CodexAppServerEvent.ProtocolError(
            message = params.string("message") ?: "Codex app-server error",
        )
        else -> null
    }

    private fun parseThread(result: JsonElement, method: String): CodexThread {
        val objectResult = result.asObject(method)
        return parseThreadObject(objectResult["thread"]?.jsonObject ?: objectResult)
    }

    private fun parseThreadObject(value: JsonObject): CodexThread = CodexThread(
        id = value.string("id") ?: error("Codex thread response did not include an id"),
        status = value.statusType(),
        cwd = value.string("cwd"),
        model = value.string("model"),
    )

    private fun parseTurn(result: JsonElement, method: String): CodexTurn {
        val objectResult = result.asObject(method)
        return parseTurnObject(objectResult["turn"]?.jsonObject ?: objectResult)
    }

    private fun parseTurnObject(value: JsonObject): CodexTurn = CodexTurn(
        id = value.string("id") ?: error("Codex turn response did not include an id"),
        threadId = value.string("threadId"),
        status = value.statusType(),
    )

    private fun CodexThreadOptions.toJson(): JsonObject = buildJsonObject { putInto(this) }

    private fun CodexThreadOptions.putInto(builder: JsonObjectBuilder) {
        cwd?.let { builder.put("cwd", it) }
        model?.let { builder.put("model", it) }
        approvalPolicy?.let { builder.put("approvalPolicy", it) }
        sandbox?.let { builder.put("sandbox", it) }
        ephemeral?.let { builder.put("ephemeral", it) }
    }

    private fun JsonElement.asObject(method: String): JsonObject = jsonObjectOrNull()
        ?: throw CodexAppServerException("$method response did not contain a JSON object")

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(name: String): Long = this[name]?.jsonPrimitive?.longOrNull ?: 0L

    private fun JsonObject.statusType(): String? {
        val status = this["status"]
        return when {
            status is JsonObject -> status.string("type")
            status != null -> status.jsonPrimitive.contentOrNull
            else -> null
        }
    }

    private fun JsonObject.statusMessage(): String? {
        val status = this["status"] as? JsonObject ?: return string("message")
        val error = status["error"]?.jsonObject
        return error?.string("message") ?: status.string("message")
    }

    companion object {
        val DEFAULT_COMMAND: List<String>
            get() = LocalAccountCli.codexCommand("app-server", "--stdio")

        fun command(executablePath: String): List<String> = listOf(
            LocalAccountCli.executable(AiProviderKind.CODEX_ACCOUNT, executablePath),
            "app-server",
            "--stdio",
        )

        val DEFAULT_CLIENT_INFO = CodexClientInfo(
            name = "openlog2",
            title = "openLog2",
            version = "0.0.0",
        )

        fun launch(
            command: List<String> = DEFAULT_COMMAND,
            environment: Map<String, String> = emptyMap(),
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ): CodexAppServerClient {
            val builder = ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
            builder.environment().putAll(environment)
            val process = builder.start()
            return CodexAppServerClient(JavaCodexAppServerProcess(process), scope)
        }
    }
}

private class JavaCodexAppServerProcess(private val process: Process) : CodexAppServerProcess {
    private val stdout: BufferedReader = InputStreamReader(process.inputStream).buffered()
    private val stdin: BufferedWriter = OutputStreamWriter(process.outputStream).buffered()

    override fun writeLine(line: String) {
        synchronized(stdin) {
            stdin.write(line)
            stdin.newLine()
            stdin.flush()
        }
    }

    override fun readLine(): String? = stdout.readLine()

    override fun isAlive(): Boolean = process.isAlive

    override fun exitCode(): Int? = try {
        process.exitValue()
    } catch (_: IllegalThreadStateException) {
        null
    }

    override fun destroy() {
        stdin.close()
        stdout.close()
        if (process.isAlive) process.destroy()
    }
}
