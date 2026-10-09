package com.indagium.testing.run

import com.indagium.ai.AccountAgentRunner
import com.indagium.ai.AiAgentRunner
import com.indagium.ai.AiInvestigationContext
import com.indagium.ai.AiProviderFactory
import com.indagium.ai.AiRun
import com.indagium.ai.AiSession
import com.indagium.ai.CodexJudgeHost
import com.indagium.ai.CodexJudgeTurn
import com.indagium.ai.LlmImage
import com.indagium.ai.LlmProvider
import com.indagium.ai.ManagedMcpServerLease
import com.indagium.ai.SwitchableMcpRoute
import com.indagium.ai.acceptsImagesUpFront
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
@Suppress("LongParameterList") // One run request; each field is something an agent kind reads when it starts the run.
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
    /**
     * Images that go with [prompt] in the first message (a judge's screenshots). Only for an agent that [LaneAgent.acceptsImagesUpFront];
     * the caller words [prompt] for the case that they are there.
     */
    val images: List<LlmImage> = emptyList(),
    /** The request to send instead when an agent cannot attach [images] after all (Codex could not write its image files); null keeps [prompt]. */
    val promptWithoutImages: String? = null,
)

internal interface LaneAgent : AutoCloseable {
    /** Whether [AgentSegmentRequest.images] reach the model with the first message (see [acceptsImagesUpFront]); a judge asks before it builds its brief. */
    val acceptsImagesUpFront: Boolean get() = true

    /** Starts one agent run; it ends by itself (Done / Error / Cancelled) and can be cancelled through the returned run. */
    fun start(request: AgentSegmentRequest): AiRun

    /**
     * An agent for the judges of ONE lane that keeps what it started between them (a Codex process, see [CodexJudgeHost]), closed by the
     * caller when the lane ends; null when every run of this agent already starts only what it needs (an HTTP provider shared by the
     * lane) or cannot share it (Claude Code's CLI is one process per request). The judges that use it must run one after another.
     */
    fun reusableForLaneJudge(): LaneAgent? = null
}

/** Builds the agent of an AGENT_PROFILE lane. */
internal fun interface LaneAgentFactory {
    fun create(profile: AiProviderProfile, apiKey: String): LaneAgent
}

/** An in-app model over HTTP (OpenAI-compatible or Anthropic). The provider is shared by the lane's runs and closed with the lane. */
internal class ProviderLaneAgent(
    private val provider: LlmProvider,
    private val profile: AiProviderProfile,
    /** The clock of the runs this agent starts (see [AiRun]); a test injects a ticking one. */
    private val clock: () -> Long = System::currentTimeMillis,
) : LaneAgent {
    override val acceptsImagesUpFront: Boolean get() = profile.kind.acceptsImagesUpFront()

    override fun start(request: AgentSegmentRequest): AiRun {
        val runner = AiAgentRunner(
            provider = provider,
            toolGateway = request.gateway,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            maxToolRounds = request.toolCallLimit,
            clock = clock,
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
            images = request.images,
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

    /** The Codex process a lane keeps for its judges, or null when this factory has none (then each judge starts its own). */
    fun judgeHost(profile: AiProviderProfile): CodexJudgeHost? = null
}

/**
 * The production account runner: its managed MCP server is bound to the segment's lane gateway. [routeLeaseFactory], when given, also
 * lets a lane's judges share one Codex process behind one switchable managed MCP server (see [CodexJudgeHost]).
 */
internal fun laneAccountRunnerFactory(
    leaseFactory: (AiRun, IndagiumToolGateway) -> ManagedMcpServerLease,
    routeLeaseFactory: ((SwitchableMcpRoute) -> ManagedMcpServerLease)? = null,
): AccountRunnerFactory = object : AccountRunnerFactory {
    override fun create(request: AgentSegmentRequest): AccountAgentRunner = AccountAgentRunner(
        managedMcpServerFactory = { run -> leaseFactory(run, request.gateway) },
        maxToolRounds = request.toolCallLimit,
        maxTurns = request.maxTurns,
        promptPreamble = request.promptPreamble,
    )

    override fun judgeHost(profile: AiProviderProfile): CodexJudgeHost? = routeLeaseFactory?.let { CodexJudgeHost(profile, it) }
}

/** Claude Code or Codex running as the signed-in account. */
internal class AccountLaneAgent(private val profile: AiProviderProfile, private val runners: AccountRunnerFactory) : LaneAgent {
    override val acceptsImagesUpFront: Boolean get() = profile.kind.acceptsImagesUpFront()

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
            images = request.images,
            promptWithoutImages = request.promptWithoutImages,
        )
        run.job?.invokeOnCompletion { runner.close() }
        return run
    }

    override fun reusableForLaneJudge(): LaneAgent? =
        if (profile.kind == AiProviderKind.CODEX_ACCOUNT) runners.judgeHost(profile)?.let(::CodexJudgeLaneAgent) else null

    override fun close() = Unit
}

/** The judges of one lane on one kept Codex process; closing it closes the process, its managed MCP server and its workspace. */
internal class CodexJudgeLaneAgent(private val host: CodexJudgeHost) : LaneAgent {
    override fun start(request: AgentSegmentRequest): AiRun = host.start(
        CodexJudgeTurn(
            session = request.session,
            prompt = request.prompt,
            systemPrompt = request.systemPrompt,
            context = request.context,
            gateway = request.gateway,
            toolCallLimit = request.toolCallLimit,
            freeTools = request.freeTools,
            confirmationTimeoutMs = request.confirmationTimeoutMs,
            promptPreamble = request.promptPreamble,
            images = request.images,
            promptWithoutImages = request.promptWithoutImages,
        ),
    )

    override fun close() = host.close()
}

/** The production factory: HTTP profiles use [providers], Codex and Claude Code use [accountRunners]. */
internal fun defaultLaneAgentFactory(providers: AiProviderFactory, accountRunners: AccountRunnerFactory): LaneAgentFactory =
    LaneAgentFactory { profile, apiKey ->
        when (profile.kind) {
            AiProviderKind.CODEX_ACCOUNT, AiProviderKind.CLAUDE_CODE_ACCOUNT -> AccountLaneAgent(profile, accountRunners)
            else -> ProviderLaneAgent(providers.create(profile, apiKey), profile)
        }
    }
