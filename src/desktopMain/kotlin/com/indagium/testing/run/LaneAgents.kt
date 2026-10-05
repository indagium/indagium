package com.indagium.testing.run

import com.indagium.ai.AccountAgentRunner
import com.indagium.ai.AiAgentRunner
import com.indagium.ai.AiInvestigationContext
import com.indagium.ai.AiProviderFactory
import com.indagium.ai.AiRun
import com.indagium.ai.AiSession
import com.indagium.ai.LlmProvider
import com.indagium.ai.ManagedMcpServerLease
import com.indagium.debug.IndagiumToolGateway
import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// How a lane's (or a judge's) agent is started. A lane has one [LaneAgent] for the whole run; each [start] is one agent RUN (a
// "segment") that works through the lane's gateway until the case is over, the step times out or the agent fails —
// then the engine starts a fresh segment. In-app HTTP profiles go through AiAgentRunner with the lane gateway; Claude
// Code and Codex go through AccountAgentRunner, whose managed MCP server is bound to that same gateway.

/** Everything one agent run needs (a lane's segment or a judge's single run). [toolCallLimit] is what is left of the case's budget; protocol tools are free. */
internal class AgentSegmentRequest(
    val session: AiSession,
    val prompt: String,
    val systemPrompt: String,
    val context: AiInvestigationContext,
    val gateway: IndagiumToolGateway,
    val toolCallLimit: Int,
    val maxTurns: Int,
    val freeTools: Set<String>,
    val confirmationTimeoutMs: Long,
    /** The budget wording an in-app model gets; a judge run words its own. */
    val budgetGuidance: (AiRun) -> String = ::laneBudgetGuidance,
    /** What an account agent is told before the request; a judge run words its own. */
    val promptPreamble: (AiRun) -> String = ::lanePromptPreamble,
)

internal interface LaneAgent : AutoCloseable {
    /** Starts one agent run; it ends by itself (Done / Error / Cancelled) and can be cancelled through the returned run. */
    fun start(request: AgentSegmentRequest): AiRun
}

/** Builds the agent of an AGENT_PROFILE lane. */
internal fun interface LaneAgentFactory {
    fun create(profile: AiProviderProfile, apiKey: String): LaneAgent
}

/** An in-app model over HTTP (OpenAI-compatible or Anthropic). The provider is shared by the lane's runs and closed with the lane. */
internal class ProviderLaneAgent(private val provider: LlmProvider, private val profile: AiProviderProfile) : LaneAgent {
    override fun start(request: AgentSegmentRequest): AiRun {
        val runner = AiAgentRunner(
            provider = provider,
            toolGateway = request.gateway,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            maxToolRounds = request.toolCallLimit,
        )
        val run = runner.start(
            session = request.session,
            model = profile.model,
            prompt = request.prompt,
            systemPrompt = request.systemPrompt,
            context = request.context,
            reasoningEffort = profile.reasoningEffort,
            freeTools = request.freeTools,
            confirmationTimeoutMs = request.confirmationTimeoutMs,
            budgetGuidance = request.budgetGuidance,
        )
        run.job?.invokeOnCompletion { runner.close() }
        return run
    }

    override fun close() {
        (provider as? AutoCloseable)?.close()
    }
}

/** Builds the account runner of one segment; the seam tests use to give it fake processes. */
internal fun interface AccountRunnerFactory {
    fun create(request: AgentSegmentRequest): AccountAgentRunner
}

/** The production account runner: its managed MCP server is bound to the segment's lane gateway. */
internal fun laneAccountRunnerFactory(leaseFactory: (AiRun, IndagiumToolGateway) -> ManagedMcpServerLease): AccountRunnerFactory =
    AccountRunnerFactory { request ->
        AccountAgentRunner(
            managedMcpServerFactory = { run -> leaseFactory(run, request.gateway) },
            maxToolRounds = request.toolCallLimit,
            maxTurns = request.maxTurns,
            promptPreamble = request.promptPreamble,
        )
    }

/** Claude Code or Codex running as the signed-in account. */
internal class AccountLaneAgent(private val profile: AiProviderProfile, private val runners: AccountRunnerFactory) : LaneAgent {
    override fun start(request: AgentSegmentRequest): AiRun {
        val runner = runners.create(request)
        val run = runner.start(
            session = request.session,
            profile = profile,
            prompt = request.prompt,
            systemPrompt = request.systemPrompt,
            context = request.context,
            freeTools = request.freeTools,
            confirmationTimeoutMs = request.confirmationTimeoutMs,
        )
        run.job?.invokeOnCompletion { runner.close() }
        return run
    }

    override fun close() = Unit
}

/** The production factory: HTTP profiles use [providers], Codex and Claude Code use [accountRunners]. */
internal fun defaultLaneAgentFactory(providers: AiProviderFactory, accountRunners: AccountRunnerFactory): LaneAgentFactory =
    LaneAgentFactory { profile, apiKey ->
        when (profile.kind) {
            AiProviderKind.CODEX_ACCOUNT, AiProviderKind.CLAUDE_CODE_ACCOUNT -> AccountLaneAgent(profile, accountRunners)
            else -> ProviderLaneAgent(providers.create(profile, apiKey), profile)
        }
    }
