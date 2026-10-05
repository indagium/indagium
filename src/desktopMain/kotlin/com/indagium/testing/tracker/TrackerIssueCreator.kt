package com.indagium.testing.tracker

import com.indagium.ai.AiInvestigationContext
import com.indagium.ai.AiRun
import com.indagium.ai.AiRunEvent
import com.indagium.ai.AiSession
import com.indagium.ai.redactDiagnosticSecrets
import com.indagium.testing.model.IssueAttachment
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.run.AgentSegmentRequest
import com.indagium.testing.run.EngineTuning
import com.indagium.testing.run.LaneAgent
import com.indagium.testing.run.toMarkdown
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

// Files ONE issue in the user's tracker: a one-shot agent job, modelled on the judge. The AI profile the user chose (an in-app
// model, Claude Code or Codex) is started with a gateway that holds only the proxied tracker tools plus get_issue_draft,
// read_issue_attachment and report_issue_created (TrackerTools.kt) and the user's own instructions as its prompt. The job ends
// as soon as the agent reports the created issue, or when it stops without reporting, fails or runs out of time. Nothing here
// throws for an expected problem: the answer is a [TrackerSendResult]. The access token stays inside [connection]; the agent
// only ever sees tracker answers, fenced as untrusted data.

const val TRACKER_JOB_TIMEOUT_MS = 180_000L
private const val TRACKER_TURN_HEADROOM = 6
private const val ENDED_WITHOUT_REPORT =
    "The agent finished without reporting a created issue. It may still have created one in the tracker: look there before sending again."
private const val MILLIS_PER_SECOND = 1_000L

/** What the creator needs to know about the issue to send: the stored record and where its evidence files are. */
internal class TrackerSendRequest(
    val record: IssueRecord,
    val trackerName: String,
    val instructions: String,
    val attachmentFile: (IssueAttachment) -> File?,
)

internal sealed interface TrackerSendResult {
    data class Created(val url: String, val key: String) : TrackerSendResult

    data class Failed(val message: String) : TrackerSendResult
}

/** Single use: [create] closes the connection and the [agent]. */
internal class TrackerIssueCreator(
    private val connection: TrackerConnection,
    private val clients: TrackerMcpClientFactory,
    private val agent: LaneAgent,
    private val tuning: EngineTuning = EngineTuning(),
    private val jobTimeoutMs: Long = TRACKER_JOB_TIMEOUT_MS,
) {
    @Suppress("TooGenericExceptionCaught") // Whatever setting the job up throws means "the issue was not filed".
    suspend fun create(request: TrackerSendRequest): TrackerSendResult {
        val client = clients.create(connection)
        try {
            val tools = try {
                client.connect()
                client.listTools()
            } catch (failure: TrackerMcpException) {
                return TrackerSendResult.Failed("Could not reach the issue tracker: ${failure.message}")
            }
            if (tools.isEmpty()) return TrackerSendResult.Failed("The issue tracker offers no tools, so no issue can be created.")
            return run(request, TrackerTools(request.record, markdownOf(request), tools, client, request.attachmentFile))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return TrackerSendResult.Failed("The issue could not be sent: ${connection.scrub(failure.message ?: failure::class.simpleName.orEmpty())}")
        } finally {
            withContext(NonCancellable) {
                client.close()
                agent.close()
            }
        }
    }

    /** The draft as Markdown for the prompt: evidence by file NAME (never a path on this computer). */
    private fun markdownOf(request: TrackerSendRequest): String =
        request.record.draft.toMarkdown(request.record.source) { attachment -> attachment.fileName.takeIf { attachment.storedPath != null } }

    @Suppress("TooGenericExceptionCaught") // Whatever starting the agent throws means "the issue was not filed".
    private suspend fun run(request: TrackerSendRequest, tools: TrackerTools): TrackerSendResult {
        val tabId = "$TRACKER_SESSION_PREFIX:${request.record.id}"
        val session = AiSession(tabId)
        val segment = AgentSegmentRequest(
            session = session,
            prompt = trackerPrompt(request.trackerName, request.instructions, request.record, markdownOf(request)),
            systemPrompt = TRACKER_SYSTEM_PROMPT,
            context = AiInvestigationContext(tabId),
            gateway = tools.gateway,
            toolCallLimit = TRACKER_TOOL_CALL_BUDGET,
            maxTurns = TRACKER_TOOL_CALL_BUDGET + TRACKER_TURN_HEADROOM,
            freeTools = TRACKER_FREE_TOOL_NAMES,
            confirmationTimeoutMs = jobTimeoutMs,
            budgetGuidance = ::trackerBudgetGuidance,
            promptPreamble = ::trackerPromptPreamble,
        )
        val run = try {
            agent.start(segment)
        } catch (failure: Exception) {
            session.deleteClaudeCodeWorkspace()
            return TrackerSendResult.Failed("The AI profile could not start: ${connection.scrub(failure.message ?: failure::class.simpleName.orEmpty())}")
        }
        try {
            val report = awaitReport(run, tools)
            return if (report != null) TrackerSendResult.Created(report.url, report.key) else TrackerSendResult.Failed(failureOf(run))
        } finally {
            withContext(NonCancellable) {
                run.cancel()
                withTimeoutOrNull(tuning.agentCancelWaitMs) { run.job?.join() }
                session.deleteClaudeCodeWorkspace()
            }
        }
    }

    private suspend fun awaitReport(run: AiRun, tools: TrackerTools): TrackerReport? {
        val job = checkNotNull(run.job) { "The issue agent run has no job." }
        val waited = withTimeoutOrNull(jobTimeoutMs) {
            select<TrackerReport?> {
                tools.reported.onAwait { it }
                job.onJoin { tools.report }
            }
        }
        return waited ?: tools.report
    }

    private fun failureOf(run: AiRun): String {
        val error = run.history.lastOrNull { it is AiRunEvent.Error } as? AiRunEvent.Error
        return when {
            run.job?.isActive == true ->
                "The agent did not report a created issue within ${jobTimeoutMs / MILLIS_PER_SECOND} s. It may still have created one: look in the tracker."
            error != null -> "The agent failed: ${connection.scrub(redactDiagnosticSecrets(error.message))}"
            else -> ENDED_WITHOUT_REPORT
        }
    }
}
