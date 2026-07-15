package com.openlog.ai

import com.openlog.model.AiProviderKind
import com.openlog.model.AiProviderProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs a locally authenticated coding agent in an empty temporary workspace. The agent receives
 * log evidence only through its per-run managed MCP endpoint; it never receives app paths or a
 * source-folder mount.
 */
internal class AccountAgentRunner(
    private val managedMcpServerFactory: (AiRun) -> ManagedMcpServerLease,
    private val maxToolRounds: Int,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {
    fun start(
        session: AiSession,
        profile: AiProviderProfile,
        prompt: String,
        systemPrompt: String,
        context: AiInvestigationContext,
    ): AiRun {
        require(profile.kind == AiProviderKind.CODEX_ACCOUNT || profile.kind == AiProviderKind.CLAUDE_CODE_ACCOUNT)
        require(prompt.isNotBlank()) { "AI prompt must not be blank" }
        session.activeRun?.cancel()
        val run = AiRun(tabId = session.tabId, userPrompt = prompt, context = context)
        session.lastPrompt = prompt
        session.lastContext = context
        session.activeRun = run
        session.retain(run)
        run.job = scope.launch {
            val workspace = resolveAccountAgentWorkspace(session, profile.kind)
            var lease: ManagedMcpServerLease? = null
            try {
                run.emit(AiRunEvent.Status("Checking ${profile.kind.label}…"))
                checkAccountCli(profile).takeIf { !it.isReady }?.let { check ->
                    throw IllegalStateException(check.message)
                }
                lease = managedMcpServerFactory(run)
                run.emit(AiRunEvent.Status("Starting ${profile.kind.label}…"))
                when (profile.kind) {
                    AiProviderKind.CODEX_ACCOUNT -> runCodex(run, profile, prompt, systemPrompt, workspace, lease)
                    AiProviderKind.CLAUDE_CODE_ACCOUNT -> runClaude(session, run, profile, prompt, systemPrompt, workspace, lease)
                }
            } catch (_: CancellationException) {
                run.emit(AiRunEvent.Cancelled)
            } catch (error: Exception) {
                run.emit(AiRunEvent.Error(error.message ?: "Unable to start the account agent."))
            } finally {
                lease?.close()
                if (profile.kind != AiProviderKind.CLAUDE_CODE_ACCOUNT) deleteWorkspace(workspace)
                run.confirmations.values.forEach { it.cancel() }
                run.confirmations.clear()
                if (session.activeRun === run) session.activeRun = null
            }
        }
        return run
    }

    override fun close() {
        scope.cancel()
    }

    private suspend fun runCodex(
        run: AiRun,
        profile: AiProviderProfile,
        prompt: String,
        systemPrompt: String,
        workspace: Path,
        lease: ManagedMcpServerLease,
    ) {
        val command = CodexAppServerClient.command(profile.executablePath) +
            codexDisableUserServersConfig() +
            codexManagedMcpConfig(lease.url, lease.token)
        CodexAppServerClient.launch(
            command = command,
            scope = scope,
        ).use { client ->
            client.initialize()
            val thread = client.startThread(
                CodexThreadOptions(
                    cwd = workspace.toString(),
                    model = profile.model.takeIf(String::isNotBlank),
                    approvalPolicy = "never",
                    sandbox = "read-only",
                    ephemeral = true,
                ),
            )
            val terminal = CompletableDeferred<Unit>()
            val agentMessages = AgentTurnMessageBuffer()
            var receivedAgentMessage = false
            val events: Job = scope.launch {
                client.events.collect { event ->
                    when (event) {
                        is CodexAppServerEvent.TurnStarted -> if (event.turn.threadId == null || event.turn.threadId == thread.id) {
                            run.emit(AiRunEvent.Status("Codex is investigating…"))
                        }
                        is CodexAppServerEvent.AgentTextDelta -> {
                            if (event.threadId == null || event.threadId == thread.id) {
                                if (!receivedAgentMessage) {
                                    receivedAgentMessage = true
                                    run.emit(AiRunEvent.Status("Codex is gathering evidence…"))
                                }
                                agentMessages.append(event.itemId, event.text)?.let { text ->
                                    run.emit(AiRunEvent.AgentProgress(text))
                                }
                            }
                        }
                        is CodexAppServerEvent.McpToolProgress -> {
                            if (event.threadId == null || event.threadId == thread.id) {
                                run.emit(AiRunEvent.Status("Codex MCP: ${event.message}"))
                            }
                        }
                        is CodexAppServerEvent.McpServerStartupStatus -> {
                            if (event.serverName == "openlog") {
                                val detail = event.message ?: event.status ?: "updated"
                                if (event.status.equals("failed", ignoreCase = true) || event.status.equals("error", ignoreCase = true)) {
                                    terminal.completeExceptionally(IllegalStateException("openLog MCP could not start: $detail"))
                                } else {
                                    run.emit(AiRunEvent.Status("openLog MCP: $detail"))
                                }
                            }
                        }
                        is CodexAppServerEvent.ServerRequest -> when (event.method) {
                            "mcpServer/elicitation/request" -> {
                                val decision = decideCodexElicitation(event.params)
                                client.resolveServerRequest(event.id, decision.response)
                                if (!decision.isOpenLogToolApproval) {
                                    // Some other MCP server configured in the user's Codex profile (e.g. an
                                    // OAuth-backed integration) is asking for approval. Decline it gracefully
                                    // instead of aborting; that server just stays unavailable for this run.
                                    run.emit(
                                        AiRunEvent.Status(
                                            "Codex declined a request from another MCP server; it stays unavailable for this run.",
                                        ),
                                    )
                                }
                            }
                            else -> {
                                client.rejectServerRequest(event.id, "openLog does not support this app-server request.")
                                terminal.completeExceptionally(
                                    IllegalStateException("Codex requested unsupported host action: ${event.method}"),
                                )
                            }
                        }
                        is CodexAppServerEvent.TokenUsageUpdated -> if (event.threadId == null || event.threadId == thread.id) {
                            run.emit(
                                AiRunEvent.Usage(
                                    promptTokens = event.total.inputTokens.toInt(),
                                    completionTokens = event.total.outputTokens.toInt(),
                                    totalTokens = event.total.totalTokens.toInt(),
                                ),
                            )
                        }
                        is CodexAppServerEvent.TurnCompleted -> if (event.threadId == null || event.threadId == thread.id) {
                            agentMessages.finalText()
                                ?.let { run.emit(AiRunEvent.AssistantDelta(it)) }
                            terminal.complete(Unit)
                        }
                        is CodexAppServerEvent.TurnFailed -> if (event.threadId == null || event.threadId == thread.id) {
                            terminal.completeExceptionally(IllegalStateException(event.message))
                        }
                        is CodexAppServerEvent.ProcessExited -> terminal.completeExceptionally(
                            IllegalStateException("Codex app-server exited before the response completed."),
                        )
                        is CodexAppServerEvent.ProtocolError -> run.emit(AiRunEvent.Status("Codex protocol: ${event.message}"))
                        else -> Unit
                    }
                }
            }
            try {
                client.startTurn(
                    thread.id,
                    accountPrompt(systemPrompt, prompt),
                    profile.model.takeIf(String::isNotBlank),
                    workspace.toString(),
                    profile.reasoningEffort.takeIf(String::isNotBlank),
                )
                terminal.await()
                run.emit(AiRunEvent.Done)
            } finally {
                events.cancel()
            }
        }
    }

    private suspend fun runClaude(
        session: AiSession,
        run: AiRun,
        profile: AiProviderProfile,
        prompt: String,
        systemPrompt: String,
        workspace: Path,
        lease: ManagedMcpServerLease,
    ) {
        var sawDelta = false
        var completed = false
        // Claude Code's print-mode protocol has no per-item id like Codex's app-server events, so
        // interim narration between tool calls (the "Let me start by...", "Now let me..." bursts)
        // would otherwise land in the same AssistantDelta stream as the true final answer, glued
        // together with no separator. Key the shared buffer by a turn counter bumped on each
        // TurnBoundary instead: every turn but the last becomes AgentProgress (Investigation panel),
        // and only the last turn's text becomes the Assistant answer - mirroring runCodex below.
        var turnIndex = 0
        val turnMessages = AgentTurnMessageBuffer()
        ClaudeCodeClient(executable = LocalAccountCli.executable(profile.kind, profile.executablePath)).stream(
            ClaudeCodeRequest(
                prompt = accountPrompt(systemPrompt, prompt),
                mcpServers = mapOf("openlog" to ClaudeCodeMcpServer(lease.url, mapOf("Authorization" to "Bearer ${lease.token}"))),
                // Resumes the same tab's prior Claude Code session (if any) so a follow-up like "are
                // you sure?" is answered from real conversation memory instead of a blank session
                // that has to either re-investigate from scratch or admit it has no context.
                sessionId = session.claudeCodeSessionId,
                model = profile.model.takeIf(String::isNotBlank),
                maxTurns = maxToolRounds,
                workingDirectory = workspace,
                effort = profile.reasoningEffort.takeIf(String::isNotBlank),
            ),
        ).collect { event ->
            when (event) {
                is ClaudeCodeEvent.TextDelta -> {
                    sawDelta = true
                    turnMessages.append(turnIndex.toString(), event.text)?.let { text ->
                        run.emit(AiRunEvent.AgentProgress(text))
                    }
                }
                is ClaudeCodeEvent.TurnBoundary -> turnIndex++
                is ClaudeCodeEvent.Final -> {
                    val finalText = turnMessages.finalText()
                    if (finalText != null) {
                        run.emit(AiRunEvent.AssistantDelta(finalText))
                    } else if (!sawDelta && event.text.isNotBlank()) {
                        run.emit(AiRunEvent.AssistantDelta(event.text))
                    }
                    event.usage?.let { usage ->
                        run.emit(
                            AiRunEvent.Usage(
                                promptTokens = usage.inputTokens,
                                completionTokens = usage.outputTokens,
                                totalTokens = usage.inputTokens + usage.outputTokens,
                            ),
                        )
                    }
                    completed = true
                }
                is ClaudeCodeEvent.Error -> throw IllegalStateException(event.message)
                is ClaudeCodeEvent.SessionId -> session.claudeCodeSessionId = event.value
            }
        }
        if (!completed) throw IllegalStateException("Claude Code ended before the response completed.")
        run.emit(AiRunEvent.Done)
    }

    private fun accountPrompt(systemPrompt: String, prompt: String): String =
        "$systemPrompt\n\nYou have one MCP server named openlog. Use only its tools for log, source, filter, " +
            "tab, or note evidence and actions. Do not inspect the local workspace; it is intentionally empty." +
            "\n\nUser request:\n$prompt"

    private fun deleteWorkspace(workspace: Path) {
        runCatching {
            Files.walk(workspace).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}

/**
 * The workspace directory an account-agent run should use as its `cwd`. Codex gets a fresh one
 * per call - it never resumes a prior thread (see [AccountAgentRunner]'s class doc) - which the
 * caller deletes once that call ends. Claude Code instead reuses and keeps the same tab-scoped
 * workspace across follow-ups, storing it on [session]: see [AiSession.claudeCodeWorkspace] for
 * why that's required for `--resume` to work at all.
 */
internal fun resolveAccountAgentWorkspace(session: AiSession, kind: AiProviderKind): Path =
    if (kind == AiProviderKind.CLAUDE_CODE_ACCOUNT) {
        session.claudeCodeWorkspace ?: Files.createTempDirectory("openlog-agent-").also { session.claudeCodeWorkspace = it }
    } else {
        Files.createTempDirectory("openlog-agent-")
    }

/**
 * The bundled Codex app-server currently ignores `bearer_token_env_var` and `env_http_headers`
 * during its Streamable HTTP MCP handshake. Use its supported static-header configuration instead.
 * The token is a fresh, localhost-only credential that is discarded when this run ends.
 */
internal fun codexManagedMcpConfig(url: String, token: String): List<String> = listOf(
    "--config", "mcp_servers.openlog.url=\"$url\"",
    "--config", "mcp_servers.openlog.http_headers={ Authorization = \"Bearer $token\" }",
    // `approval_mode` is not a key Codex's app-server recognizes; in app-server mode Codex always
    // delegates tool-call approval to the host via `mcpServer/elicitation/request`; see
    // [decideCodexElicitation]. openLog itself routes sensitive actions through
    // AiToolExecutionCoordinator, so auto-approving Codex's side of that handshake is correct.
    "--config", "mcp_servers.openlog.approval_mode=\"never\"",
)

/**
 * `--config mcp_servers.<name>.enabled=false` for every MCP server in the user's Codex config
 * except the per-run `openlog` endpoint. Codex loads every server from `~/.codex/config.toml` for
 * any run it starts, and an OAuth-backed one (Figma, Xcode, ...) that cannot authenticate in this
 * non-interactive launch quits with an `Auth(AuthorizationRequired)` transport error printed to
 * stderr — harmless to the analysis, but alarming in the console. The panel agent only needs
 * `openlog`, so the rest are disabled for the launch while the real config home (and its model /
 * account settings) is left untouched. Returns empty when the config is missing or unreadable.
 */
internal fun codexDisableUserServersConfig(
    configFile: Path? = codexConfigFile(),
): List<String> {
    val configText = runCatching { configFile?.takeIf(Files::isReadable)?.let(Files::readString) }.getOrNull()
        ?: return emptyList()
    return codexUserMcpServerNames(configText).flatMap { listOf("--config", "mcp_servers.$it.enabled=false") }
}

/** Distinct MCP server names declared in a Codex `config.toml`, excluding the managed `openlog`. */
internal fun codexUserMcpServerNames(configText: String): Set<String> =
    Regex("""(?m)^\s*\[?mcp_servers\.([A-Za-z0-9_-]+)""")
        .findAll(configText)
        .map { it.groupValues[1] }
        .filter { it != "openlog" }
        .toSet()

private fun codexConfigFile(): Path? {
    val home = System.getenv("CODEX_HOME")?.trim()?.takeIf(String::isNotBlank)?.let { Path.of(it) }
        ?: System.getProperty("user.home")?.takeIf(String::isNotBlank)?.let { Path.of(it, ".codex") }
    return home?.resolve("config.toml")
}

/** The reply to send back for a Codex `mcpServer/elicitation/request`, and what it means. */
internal data class CodexElicitationDecision(
    val isOpenLogToolApproval: Boolean,
    val response: JsonObject,
)

/**
 * Decides how to answer a Codex app-server `mcpServer/elicitation/request`. Captured params from a
 * real run look like:
 * ```
 * {"threadId":"...","turnId":"...","serverName":"openlog","mode":"form",
 *   "_meta":{"codex_approval_kind":"mcp_tool_call", ...},
 *   "message":"Allow the openlog MCP server to run tool \"list_tabs\"?", ...}
 * ```
 * This is a tool-call approval, not an OAuth prompt. When it is for the managed `openlog` server
 * (identified by `serverName` or `_meta.codex_approval_kind`), accept it and let the tool run —
 * openLog already gates destructive tools itself via [AiToolExecutionCoordinator]. Any other server
 * (e.g. an OAuth-backed integration from the user's own `~/.codex/config.toml`) is declined; that
 * server simply stays unavailable for this run instead of aborting it.
 */
internal fun decideCodexElicitation(params: JsonObject): CodexElicitationDecision {
    val serverName = params.stringOrNull("serverName")
    val approvalKind = (params["_meta"] as? JsonObject)?.stringOrNull("codex_approval_kind")
    val isOpenLogToolApproval = serverName == "openlog" || approvalKind == "mcp_tool_call"
    val response = buildJsonObject {
        put("action", if (isOpenLogToolApproval) "accept" else "decline")
        put("content", buildJsonObject { })
    }
    return CodexElicitationDecision(isOpenLogToolApproval, response)
}

private fun JsonObject.stringOrNull(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

/**
 * Keeps an agent's interim per-turn messages out of the final assistant answer: text appended
 * under a given turn key accumulates together, and switching to a new key flushes the previous
 * key's buffered text as "prior progress" (returned from [append]). Used by both account-agent
 * providers - Codex keys by its `itemId`; Claude Code keys by a synthetic turn counter bumped on
 * each [ClaudeCodeEvent.TurnBoundary] (Claude's stream-json protocol has no per-item id).
 */
internal class AgentTurnMessageBuffer {
    private val messages = linkedMapOf<String, StringBuilder>()
    private var latestMessageId: String? = null

    fun append(itemId: String?, text: String): String? {
        val resolvedItemId = itemId ?: "agent-message-${messages.size}"
        val priorProgress = if (resolvedItemId != latestMessageId) {
            latestMessageId
                ?.let(messages::get)
                ?.toString()
                ?.trim()
                ?.takeIf(String::isNotBlank)
        } else {
            null
        }
        latestMessageId = resolvedItemId
        messages.getOrPut(resolvedItemId, ::StringBuilder).append(text)
        return priorProgress
    }

    fun finalText(): String? = latestMessageId
        ?.let(messages::get)
        ?.toString()
        ?.trim()
        ?.takeIf(String::isNotBlank)
}
