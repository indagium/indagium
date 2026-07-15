package com.openlog.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** The small process surface kept injectable so Claude Code can be tested without a CLI binary. */
fun interface ClaudeCodeProcessFactory {
    fun start(command: List<String>, workingDirectory: Path?): ClaudeCodeProcess
}

interface ClaudeCodeProcess {
    val stdout: InputStream
    val stderr: InputStream
    val isAlive: Boolean

    fun waitFor(): Int

    fun destroy()

    fun destroyForcibly()
}

object SystemClaudeCodeProcessFactory : ClaudeCodeProcessFactory {
    override fun start(command: List<String>, workingDirectory: Path?): ClaudeCodeProcess {
        val builder = ProcessBuilder(command)
        workingDirectory?.toFile()?.let(builder::directory)
        return JvmClaudeCodeProcess(builder.start())
    }
}

class ClaudeCodeBinaryNotFoundException(executable: String) : IOException("Claude Code binary not found: $executable")

data class ClaudeCodeMcpServer(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val type: String = "http",
)

data class ClaudeCodeRequest(
    val prompt: String,
    val mcpServers: Map<String, ClaudeCodeMcpServer> = emptyMap(),
    /** A non-null value resumes an existing Claude Code session with --resume. */
    val sessionId: String? = null,
    val model: String? = null,
    val maxTurns: Int? = null,
    val workingDirectory: Path? = null,
    val effort: String? = null,
)

enum class ClaudeCodeErrorKind {
    BinaryNotFound,
    ProcessStart,
    Process,
    MalformedEvent,
    ClaudeCode,
}

sealed interface ClaudeCodeEvent {
    data class SessionId(val value: String) : ClaudeCodeEvent

    data class TextDelta(val text: String) : ClaudeCodeEvent

    /**
     * Marks the end of one complete assistant turn (Claude Code's top-level `"type":"assistant"`
     * stream-json line). This is the only per-turn boundary the print-mode protocol exposes -
     * unlike Codex's app-server events, there is no per-item id. Callers that want to distinguish
     * interim narration (emitted between tool calls) from the true final answer use this to key an
     * [AgentTurnMessageBuffer] by turn index, mirroring how Codex keys the same buffer by itemId.
     */
    data object TurnBoundary : ClaudeCodeEvent

    data class Final(val text: String, val sessionId: String?) : ClaudeCodeEvent

    data class Error(
        val kind: ClaudeCodeErrorKind,
        val message: String,
        val sessionId: String? = null,
        val exitCode: Int? = null,
    ) : ClaudeCodeEvent
}

/** Parses one Claude Code stream-json line and remembers the session for later terminal events. */
class ClaudeCodeStreamParser {
    var sessionId: String? = null
        private set

    private var sawPartialText = false

    fun parse(line: String): List<ClaudeCodeEvent> {
        if (line.isBlank()) return emptyList()

        val root = try {
            Json.parseToJsonElement(line).jsonObject
        } catch (_: Exception) {
            return listOf(
                ClaudeCodeEvent.Error(
                    kind = ClaudeCodeErrorKind.MalformedEvent,
                    message = "Ignored malformed Claude Code stream event.",
                    sessionId = sessionId,
                ),
            )
        }

        return buildList {
            root.string("session_id")?.let { addSessionId(it) }

            when (root.string("type")) {
                "stream_event" -> {
                    val nested = root["event"] as? JsonObject
                    val delta = nested?.get("delta") as? JsonObject
                    if (nested != null && delta != null && nested.string("type") == "content_block_delta" &&
                        delta.string("type") == "text_delta"
                    ) {
                        delta.string("text")?.takeIf { it.isNotEmpty() }?.let {
                            sawPartialText = true
                            add(ClaudeCodeEvent.TextDelta(it))
                        }
                    }
                }

                "assistant" -> {
                    // Without --include-partial-messages, Claude Code still emits a complete
                    // assistant message. Use it as a fallback, but never duplicate partial text.
                    if (!sawPartialText) {
                        textBlocks(root["message"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let {
                            add(ClaudeCodeEvent.TextDelta(it))
                        }
                    }
                    // This line always marks one assistant turn as fully complete, whether its text
                    // arrived here or was already streamed via prior TextDelta events.
                    add(ClaudeCodeEvent.TurnBoundary)
                }

                "result" -> {
                    val result = root.string("result").orEmpty()
                    val subtype = root.string("subtype").orEmpty()
                    val isError = root.boolean("is_error") == true || subtype.contains("error", ignoreCase = true)
                    if (isError) {
                        add(
                            ClaudeCodeEvent.Error(
                                kind = ClaudeCodeErrorKind.ClaudeCode,
                                message = root.errorMessage() ?: result.ifBlank { "Claude Code returned an error." },
                                sessionId = sessionId,
                            ),
                        )
                    } else {
                        add(ClaudeCodeEvent.Final(result, sessionId))
                    }
                }

                "error" -> {
                    add(
                        ClaudeCodeEvent.Error(
                            kind = ClaudeCodeErrorKind.ClaudeCode,
                            message = root.errorMessage() ?: "Claude Code returned an error.",
                            sessionId = sessionId,
                        ),
                    )
                }
            }
        }
    }

    private fun MutableList<ClaudeCodeEvent>.addSessionId(value: String) {
        if (value != sessionId) {
            sessionId = value
            add(ClaudeCodeEvent.SessionId(value))
        }
    }

    private fun textBlocks(message: JsonObject?): String? = message?.get("content")
        ?.let { it as? JsonArray }
        ?.mapNotNull { block ->
            val objectBlock = block as? JsonObject ?: return@mapNotNull null
            if (objectBlock.string("type") == "text") objectBlock.string("text") else null
        }
        ?.joinToString("")

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.errorMessage(): String? = string("error") ?: string("message")
        ?: (this["error"] as? JsonObject)?.string("message")
}

/** Claude Code print-mode adapter used by the account agent's managed run (see [buildCommand]). */
class ClaudeCodeClient(
    private val processFactory: ClaudeCodeProcessFactory = SystemClaudeCodeProcessFactory,
    private val executable: String = "claude",
) {
    fun stream(request: ClaudeCodeRequest): Flow<ClaudeCodeEvent> = channelFlow {
        val process = try {
            processFactory.start(buildCommand(request, executable), request.workingDirectory)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            trySend(
                ClaudeCodeEvent.Error(
                    kind = if (error.isBinaryNotFound()) ClaudeCodeErrorKind.BinaryNotFound else ClaudeCodeErrorKind.ProcessStart,
                    message = error.message ?: "Unable to start Claude Code.",
                ),
            )
            return@channelFlow
        }

        val stderr = async(Dispatchers.IO) {
            runCatching { process.stderr.bufferedReader().use { it.readText() } }.getOrDefault("")
        }
        val parser = ClaudeCodeStreamParser()
        val reader = launch(Dispatchers.IO) {
            var finalSeen = false
            var errorSeen = false
            var exitCode: Int? = null
            try {
                process.stdout.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        parser.parse(line).forEach { event ->
                            when (event) {
                                is ClaudeCodeEvent.Final -> finalSeen = true
                                is ClaudeCodeEvent.Error -> errorSeen = true
                                else -> Unit
                            }
                            trySend(event)
                        }
                    }
                }
                exitCode = process.waitFor()

                val stderrText = stderr.await().trim()
                if (!finalSeen && !errorSeen) {
                    val message = stderrText.ifBlank { "Claude Code ended without a final result." }
                    trySend(
                        ClaudeCodeEvent.Error(
                            kind = ClaudeCodeErrorKind.Process,
                            message = message,
                            sessionId = parser.sessionId,
                            exitCode = exitCode,
                        ),
                    )
                } else if (exitCode != 0 && !errorSeen) {
                    trySend(
                        ClaudeCodeEvent.Error(
                            kind = ClaudeCodeErrorKind.Process,
                            message = stderrText.ifBlank { "Claude Code exited with code $exitCode." },
                            sessionId = parser.sessionId,
                            exitCode = exitCode,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                trySend(
                    ClaudeCodeEvent.Error(
                        kind = ClaudeCodeErrorKind.Process,
                        message = error.message ?: "Claude Code stream failed.",
                        sessionId = parser.sessionId,
                        exitCode = exitCode,
                    ),
                )
            } finally {
                if (process.isAlive) process.destroy()
                stderr.cancel()
                close()
            }
        }
        awaitClose {
            if (process.isAlive) process.destroyForcibly()
            stderr.cancel()
            reader.cancel()
        }
    }

    companion object {
        fun buildCommand(request: ClaudeCodeRequest, executable: String = "claude"): List<String> = buildList {
            add(executable)
            add("--print")
            add("--output-format")
            add("stream-json")
            add("--verbose")
            add("--include-partial-messages")
            // Without these three, Claude Code defaults to interactive per-tool approval. In
            // --print mode it cannot actually prompt, so it gives up and describes the request in
            // plain text instead of a tool call - that text then surfaces as a normal assistant
            // reply asking the user to "grant permissions", never reaching real investigation.
            // --strict-mcp-config: load only the managed `openlog` server passed below, ignoring
            // any other MCP servers configured on this machine (parallels the Codex account path's
            // codexDisableUserServersConfig).
            // --tools "": disable every built-in tool (Bash, Read, Write, Edit, ...) - this class's
            // whole design is that the agent only ever touches evidence through its managed MCP
            // endpoint, never the filesystem directly.
            // --permission-mode bypassPermissions: with the two restrictions above, the only tools
            // left to call are the managed openlog ones, and openLog already gates sensitive
            // actions itself via AiToolExecutionCoordinator - so auto-approving Claude Code's side
            // of that handshake is correct, exactly like decideCodexElicitation does for Codex.
            add("--strict-mcp-config")
            add("--tools")
            add("")
            add("--permission-mode")
            add("bypassPermissions")
            add("--mcp-config")
            add(generateMcpConfig(request.mcpServers))
            request.model?.takeIf { it.isNotBlank() }?.let { addAll(listOf("--model", it)) }
            request.effort?.takeIf { it.isNotBlank() }?.let { addAll(listOf("--effort", it)) }
            request.maxTurns?.let { addAll(listOf("--max-turns", it.toString())) }
            request.sessionId?.takeIf { it.isNotBlank() }?.let { addAll(listOf("--resume", it)) }
            add(request.prompt)
        }

        fun generateMcpConfig(servers: Map<String, ClaudeCodeMcpServer>): String = buildJsonObject {
            put("mcpServers", buildJsonObject {
                servers.forEach { (name, server) ->
                    put(name, buildJsonObject {
                        put("type", server.type)
                        put("url", server.url)
                        if (server.headers.isNotEmpty()) {
                            put("headers", buildJsonObject {
                                server.headers.forEach { (key, value) -> put(key, value) }
                            })
                        }
                    })
                }
            })
        }.toString()

        private fun Exception.isBinaryNotFound(): Boolean = this is FileNotFoundException || this is NoSuchFileException ||
            message.orEmpty().lowercase().let { text ->
                "no such file" in text || "command not found" in text || "error=2" in text || "not found" in text
            }
    }
}

private class JvmClaudeCodeProcess(private val process: Process) : ClaudeCodeProcess {
    override val stdout: InputStream = process.inputStream
    override val stderr: InputStream = process.errorStream
    override val isAlive: Boolean get() = process.isAlive

    override fun waitFor(): Int = process.waitFor()

    override fun destroy() = process.destroy()

    override fun destroyForcibly() {
        process.destroyForcibly()
    }
}
