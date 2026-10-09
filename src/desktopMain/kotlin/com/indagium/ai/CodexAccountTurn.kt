package com.indagium.ai

import kotlinx.coroutines.CompletableDeferred
import java.nio.file.Path

// The two halves of one Codex turn that an account run and a kept judge process (CodexJudgeHost) share: what the turn is asked, and
// how what it reports becomes the events of its AiRun.

/** What an account run is asked: [prompt] with [images], or [promptWithoutImages] when the images cannot be attached. */
internal class AccountRequest(val prompt: String, val images: List<LlmImage>, private val promptWithoutImages: String?) {
    /** [prompt], unless there were images and none could be [attached]. */
    fun promptFor(attached: Boolean): String = if (images.isEmpty() || attached) prompt else promptWithoutImages ?: prompt
}

/** The text of the Codex turn and the image files that go with it. */
internal class CodexTurnInput(val text: String, val imagePaths: List<String>)

/** The text an account agent is given: the system prompt, the budget and tool rules, and the request. */
internal fun accountPromptText(systemPrompt: String, preamble: String, prompt: String): String =
    "$systemPrompt\n\n$preamble\n\nUser request:\n$prompt"

/**
 * The text of the Codex turn and the image files that go with it. The files are written into [imageDirectory], which the caller
 * deletes when the run ends (Codex reads them as the turn starts); when they cannot be written the text is the prompt without images
 * and a status says so.
 */
@Suppress("TooGenericExceptionCaught") // Whatever stops the files from being written means "no images", never a failed run.
internal suspend fun codexTurnInput(
    run: AiRun,
    request: AccountRequest,
    systemPrompt: String,
    preamble: String,
    imageDirectory: Path,
): CodexTurnInput {
    val paths = try {
        writeAttachedImages(imageDirectory, request.images).map(Path::toString)
    } catch (failure: Exception) {
        run.emit(AiRunEvent.Status("Could not attach the evidence images for Codex (${failure.message ?: failure::class.simpleName}); it will fetch them."))
        emptyList()
    }
    return CodexTurnInput(accountPromptText(systemPrompt, preamble, request.promptFor(attached = paths.isNotEmpty())), paths)
}

/** What Codex's startup status for the managed MCP server says once the server is connected. */
private const val CODEX_MCP_READY_STATUS = "ready"

/**
 * A failure of the Codex process itself (it exited, could not start the managed MCP server, asked for something this host cannot
 * give), as opposed to a turn that failed on its own. A process that has one is not used again.
 */
internal class CodexProcessFault(message: String) : IllegalStateException(message)

/**
 * Turns what one Codex turn reports into the events of [run]: progress, usage, the timing marks, the answers to the app-server's
 * approval requests, and the end of the turn ([terminal]). Events of other threads are ignored once [bind] named this turn's
 * thread; events that name no thread are taken as this turn's.
 */
internal class CodexTurnEvents(private val client: CodexAppServerClient, private val run: AiRun) {
    /** Completes when the turn is over; exceptionally when it failed, or ([CodexProcessFault]) when the process did. */
    val terminal = CompletableDeferred<Unit>()

    private val agentMessages = AgentTurnMessageBuffer()
    private var receivedAgentMessage = false
    private var lastReportedTotalTokens: Long? = null

    @Volatile
    private var threadId: String? = null

    /** The turn runs on the thread [id]; thread-scoped events of any other thread are no longer ours. */
    fun bind(id: String) {
        threadId = id
    }

    /** Ends the turn with [failure] (unless it already ended). */
    fun fail(failure: Throwable) {
        terminal.completeExceptionally(failure)
    }

    private fun ours(eventThreadId: String?): Boolean = eventThreadId == null || eventThreadId == threadId

    suspend fun handle(event: CodexAppServerEvent) {
        when (event) {
            is CodexAppServerEvent.TurnStarted -> if (ours(event.turn.threadId)) onTurnStarted()
            is CodexAppServerEvent.AgentTextDelta -> if (ours(event.threadId)) onAgentText(event)
            is CodexAppServerEvent.McpToolProgress -> if (ours(event.threadId)) run.emit(AiRunEvent.Status("Codex MCP: ${event.message}"))
            is CodexAppServerEvent.McpServerStartupStatus -> onMcpStartup(event)
            is CodexAppServerEvent.ServerRequest -> onServerRequest(event)
            is CodexAppServerEvent.TokenUsageUpdated -> if (ours(event.threadId)) onTokenUsage(event)
            is CodexAppServerEvent.TurnCompleted -> if (ours(event.threadId)) onTurnCompleted()
            is CodexAppServerEvent.TurnFailed -> if (ours(event.threadId)) terminal.completeExceptionally(IllegalStateException(event.message))
            is CodexAppServerEvent.ProcessExited -> terminal.completeExceptionally(
                CodexProcessFault("Codex app-server exited before the response completed."),
            )
            is CodexAppServerEvent.ProtocolError -> run.emit(AiRunEvent.Status("Codex protocol: ${event.message}"))
            else -> Unit
        }
    }

    private suspend fun onTurnStarted() {
        // The process is up, initialised and has accepted the prompt: ready, even if no MCP status said so.
        run.markAgentReady()
        run.emit(AiRunEvent.Status("Codex is investigating…"))
    }

    private suspend fun onAgentText(event: CodexAppServerEvent.AgentTextDelta) {
        if (!receivedAgentMessage) {
            receivedAgentMessage = true
            run.emit(AiRunEvent.Status("Codex is gathering evidence…"))
        }
        agentMessages.append(event.itemId, event.text)?.let { text -> run.emit(AiRunEvent.AgentProgress(text)) }
    }

    private suspend fun onMcpStartup(event: CodexAppServerEvent.McpServerStartupStatus) {
        if (event.serverName != MANAGED_MCP_SERVER_NAME) return
        val detail = event.message ?: event.status ?: "updated"
        if (event.status.equals("failed", ignoreCase = true) || event.status.equals("error", ignoreCase = true)) {
            terminal.completeExceptionally(CodexProcessFault("Indagium MCP could not start: $detail"))
        } else {
            if (detail.equals(CODEX_MCP_READY_STATUS, ignoreCase = true)) run.markAgentReady()
            run.emit(AiRunEvent.Status("Indagium MCP: $detail"))
        }
    }

    private suspend fun onServerRequest(event: CodexAppServerEvent.ServerRequest) {
        if (event.method != ELICITATION_REQUEST) {
            client.rejectServerRequest(event.id, "Indagium does not support this app-server request.")
            terminal.completeExceptionally(CodexProcessFault("Codex requested unsupported host action: ${event.method}"))
            return
        }
        val decision = decideCodexElicitation(event.params)
        client.resolveServerRequest(event.id, decision.response)
        if (!decision.isManagedServerApproval) {
            // Some other MCP server configured in the user's Codex profile (e.g. an OAuth-backed integration) is asking for
            // approval. Decline it gracefully instead of aborting; that server just stays unavailable for this run.
            run.emit(AiRunEvent.Status("Codex declined a request from another MCP server; it stays unavailable for this run."))
        }
    }

    private suspend fun onTokenUsage(event: CodexAppServerEvent.TokenUsageUpdated) {
        // Codex reports the run's token usage once per model response; a repeat of the same total is not a new one.
        if (event.total.totalTokens != lastReportedTotalTokens) run.markModelResponse()
        lastReportedTotalTokens = event.total.totalTokens
        run.emit(
            AiRunEvent.Usage(
                inputTokens = event.total.inputTokens,
                outputTokens = event.total.outputTokens,
                totalTokens = event.total.totalTokens,
                cachedInputTokens = event.total.cachedInputTokens,
                reasoningOutputTokens = event.total.reasoningOutputTokens,
                requestId = threadId,
                aggregation = AiUsageAggregation.RUN_CUMULATIVE,
            ),
        )
    }

    private suspend fun onTurnCompleted() {
        agentMessages.finalText()?.let { run.emit(AiRunEvent.AssistantDelta(it)) }
        terminal.complete(Unit)
    }

    private companion object {
        const val ELICITATION_REQUEST = "mcpServer/elicitation/request"
    }
}
