package com.indagium.testing.run

import com.indagium.ai.AiInvestigationContext
import com.indagium.ai.AiRun
import com.indagium.ai.AiRunEvent
import com.indagium.ai.AiSession
import com.indagium.ai.aiRunTiming
import com.indagium.ai.aiUsageFromHistory
import com.indagium.model.AiRunTiming
import com.indagium.model.AiUsageStats
import com.indagium.testing.model.JudgeClassification
import com.indagium.testing.model.JudgeComparison
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.StepJudgement
import com.indagium.testing.store.TranscriptWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

// Runs the judge: one separate AiRun per judgement (a step) or comparison (lanes that disagreed), through the same agent
// launchers as the lanes (an in-app model, Claude Code or Codex), behind a judge-only gateway (JudgeTools). The run ends
// as soon as the answer is submitted. A judge that times out, ends without answering or fails answers INCONCLUSIVE with the
// reason in [StepJudgement.error]; it never throws, so a flaky judge cannot break a run. Everything the judge says and does
// goes to the run's judge.jsonl, each line tagged with what it judged and stamped with the time it really happened (the run
// stamps its events as it emits them; the file follows while the judge runs). Every judge run ends with one judge_finished line,
// the timing breakdown of AiRunTiming, written even when the judge was cancelled, timed out or never started (partial = true), and
// hostReused: whether the judge took over a Codex process that an earlier judge of its lane had started (see CodexJudgeHost).
// A lane's judges share one agent from [LaneAgent.reusableForLaneJudge] when the judge agent has one; it is released when the lane
// ends. The comparison judge always starts its own short-lived run.

private const val ENDED_WITHOUT_ANSWER = "The judge ended without submitting a verdict."
private const val SESSION_PREFIX = "testjudge"

/** One finished judge run: its answer, or why it has none. */
private class JudgeOutcome(
    val submission: JudgeSubmission?,
    val failure: String?,
    val durationMs: Long,
    val usage: AiUsageStats?,
    val timing: AiRunTiming,
)

/**
 * What differs between the judge of a step and the judge of a comparison. [budget] is the evidence tool calls when the first message
 * carries all the evidence, [fetchBudget] when images have to be fetched with tools; [toolsOnlyPrompt] is the first message when the
 * evidence could not be put together at all.
 */
private class JudgeRunSpec(val systemPrompt: String, val budget: Int, val fetchBudget: Int, val toolsOnlyPrompt: String)

internal class JudgeService(
    private val runId: String,
    private val suiteId: String,
    private val agent: LaneAgent,
    private val tuning: EngineTuning,
    private val transcript: TranscriptWriter?,
    private val goldenImage: (suiteId: String, assetPath: String) -> ByteArray?,
    private val wallClock: () -> Long,
) : AutoCloseable {
    /** The agent each lane's judges use: a kept one (a Codex process) where the judge agent has one, otherwise the shared [agent]. */
    private val laneAgents = ConcurrentHashMap<String, LaneAgent>()

    /** The judge of one lane's steps. */
    fun forLane(laneId: String): StepJudge = object : UsageReportingStepJudge {
        override suspend fun judge(site: JudgeSite, evidence: JudgeEvidence): StepJudgement = judgeStep(laneId, site, evidence)

        override suspend fun judge(
            site: JudgeSite,
            evidence: JudgeEvidence,
            onUsage: (AiUsageStats) -> Unit,
        ): StepJudgement = judgeStep(laneId, site, evidence, onUsage)
    }

    suspend fun judgeStep(
        laneId: String,
        site: JudgeSite,
        evidence: JudgeEvidence,
        onUsage: (AiUsageStats) -> Unit = {},
    ): StepJudgement {
        val label = "$laneId:${site.evidencePrefix}-i${site.iteration}-ca${site.caseAttempt}-s${site.stepNumber}-a${site.attempt}"
        val spec = JudgeRunSpec(JUDGE_SYSTEM_PROMPT, JUDGE_TOOL_CALL_BUDGET, JUDGE_FETCH_TOOL_CALL_BUDGET, judgePrompt(evidence))
        val outcome = runJudge(evidence, label, spec, onUsage, agentForLane(laneId))
        val submission = outcome.submission
        return StepJudgement(
            verdict = submission?.verdict ?: JudgeVerdict.INCONCLUSIVE,
            reasoning = submission?.reasoning.orEmpty(),
            classification = submission?.classification ?: JudgeClassification.UNKNOWN,
            suggestedFix = submission?.fix,
            judgedAt = wallClock(),
            durationMs = outcome.durationMs,
            error = outcome.failure,
            usage = outcome.usage,
            timing = outcome.timing,
        )
    }

    /** The judgement of a step the lanes disagreed on. [evidence] has one entry per lane; [site] names the step. */
    suspend fun compare(
        caseId: String,
        iteration: Int,
        stepId: String,
        evidence: JudgeEvidence,
        comparisonId: String = com.indagium.testing.model.newComparisonId(),
        onUsage: (AiUsageStats) -> Unit = {},
    ): JudgeComparison {
        val label = "compare:$caseId-i$iteration-s${evidence.stepNumber}"
        val spec = JudgeRunSpec(
            COMPARISON_SYSTEM_PROMPT, COMPARISON_TOOL_CALL_BUDGET, COMPARISON_FETCH_TOOL_CALL_BUDGET, comparisonPrompt(evidence),
        )
        val outcome = runJudge(evidence, label, spec, onUsage)
        val submission = outcome.submission
        val verdicts = submission?.laneVerdicts ?: evidence.lanes.associate { it.laneId to JudgeVerdict.INCONCLUSIVE }
        return JudgeComparison(
            id = comparisonId,
            caseId = caseId,
            iteration = iteration,
            stepId = stepId,
            stepNumber = evidence.stepNumber,
            action = evidence.action,
            verdicts = verdicts,
            classification = submission?.classification ?: JudgeClassification.UNKNOWN,
            explanation = submission?.reasoning.orEmpty(),
            suggestedFix = submission?.fix,
            judgedAt = wallClock(),
            error = outcome.failure,
            usage = outcome.usage,
        )
    }

    /** The lane's judges are over: its kept agent (a Codex process, its managed MCP server, its workspace) is closed, within a bound. */
    suspend fun releaseLane(laneId: String) {
        val kept = laneAgents.remove(laneId) ?: return
        if (kept !== agent) closeBounded(kept)
    }

    /** Closes what the lanes still hold (bounded, so a stuck process cannot hold the run) and the judge agent. */
    suspend fun shutdown() {
        laneAgents.keys.toList().forEach { releaseLane(it) }
        agent.close()
    }

    override fun close() {
        laneAgents.values.toList().forEach { if (it !== agent) it.close() }
        laneAgents.clear()
        agent.close()
    }

    private fun agentForLane(laneId: String): LaneAgent = laneAgents.computeIfAbsent(laneId) { agent.reusableForLaneJudge() ?: agent }

    private suspend fun closeBounded(kept: AutoCloseable) = withContext(NonCancellable + Dispatchers.IO) {
        withTimeoutOrNull(tuning.agentCancelWaitMs) { runInterruptible { runCatching { kept.close() } } }
    }

    // ── One judge run ────────────────────────────────────────────────

    @Suppress("TooGenericExceptionCaught") // Whatever starting the judge throws means "no verdict".
    private suspend fun runJudge(
        evidence: JudgeEvidence,
        label: String,
        spec: JudgeRunSpec,
        onUsage: (AiUsageStats) -> Unit = {},
        /** The agent that runs this judge: the lane's kept one, or the shared [agent] for a judge with no lane (the comparison judge). */
        runner: LaneAgent = agent,
    ): JudgeOutcome {
        val started = wallClock()
        val tools = JudgeTools(evidence, ::loadExample)
        val tabId = "$SESSION_PREFIX:$runId:$label"
        val session = AiSession(tabId)
        transcript?.append("judge_started", mapOf("judge" to label, "step" to evidence.stepNumber, "lanes" to evidence.lanes.size))
        val brief = briefOf(evidence, label, started)
        val briefMs = wallClock() - started
        val budget = if (brief == null || brief.needsTools) spec.fetchBudget else spec.budget
        val request = AgentSegmentRequest(
            session = session,
            prompt = brief?.prompt ?: spec.toolsOnlyPrompt,
            systemPrompt = spec.systemPrompt,
            context = AiInvestigationContext(tabId),
            gateway = tools.gateway,
            toolCallLimit = budget,
            maxTurns = budget + tuning.judgeTurnHeadroom,
            freeTools = JUDGE_FREE_TOOL_NAMES,
            confirmationTimeoutMs = tuning.judgeTimeoutMs,
            budgetGuidance = ::judgeBudgetGuidance,
            promptPreamble = ::judgePromptPreamble,
            images = brief?.images.orEmpty(),
            promptWithoutImages = brief?.promptWithoutImages,
        )
        transcript?.append(
            "judge_brief",
            mapOf(
                "judge" to label,
                "evidenceUpFront" to (brief != null),
                "images" to request.images.size,
                "promptChars" to request.prompt.length,
                "briefMs" to briefMs,
                "toolBudget" to budget,
            ),
        )
        val run = try {
            runner.start(request)
        } catch (failure: Exception) {
            session.deleteClaudeCodeWorkspace()
            val elapsed = wallClock() - started
            val reason = "The judge could not start: ${failure.message ?: failure::class.simpleName}"
            val timing = AiRunTiming(totalMs = elapsed, partial = true)
            transcript?.append("judge_finished", finishedFields(label, timing, null, reason, briefMs, hostReused = false))
            return JudgeOutcome(null, reason, elapsed, null, timing)
        }
        val tail = transcript?.let { TranscriptTail(run, it, mapOf("judge" to label)) }
        // The file follows the run while it works, so a judge that hangs or is killed still leaves what it did up to then.
        val tailJob = tail?.let { CoroutineScope(Dispatchers.IO + SupervisorJob()).launch { it.follow(tuning.transcriptPollMs) } }
        var submission: JudgeSubmission? = null
        var failure: String? = null
        var cleanupComplete = false
        var usage: AiUsageStats? = null
        var timing = AiRunTiming(partial = true)
        try {
            submission = awaitSubmission(run, tools)
            failure = if (submission != null) null else failureOf(run)
        } finally {
            withContext(NonCancellable) {
                tailJob?.cancel()
                run.cancel()
                cleanupComplete = withTimeoutOrNull(tuning.agentCancelWaitMs) {
                    run.job?.join()
                    run.job?.isCompleted == true
                } ?: false
                tail?.close()
                session.deleteClaudeCodeWorkspace()
                val history = run.history
                usage = aiUsageFromHistory(
                    history,
                    partial = !cleanupComplete || submission == null || history.any { it == AiRunEvent.Cancelled || it is AiRunEvent.Error },
                )
                runCatching { usage?.let(onUsage) }
                timing = aiRunTiming(run, answerToolNames = JUDGE_FREE_TOOL_NAMES, partial = !cleanupComplete || submission == null)
                transcript?.append("judge_finished", finishedFields(label, timing, submission, failure, briefMs, run.agentReused))
            }
        }
        return JudgeOutcome(submission, failure, wallClock() - started, usage, timing)
    }

    /**
     * The `judge_finished` line: [AiRunTiming] flat, plus how long winding the agent down took after the verdict and what was decided,
     * and whether the judge [hostReused] a Codex process an earlier judge of its lane had started (its startupMs then leaves the launch out).
     */
    private fun finishedFields(
        label: String,
        timing: AiRunTiming,
        submission: JudgeSubmission?,
        error: String?,
        briefMs: Long,
        hostReused: Boolean,
    ): Map<String, Any?> = linkedMapOf(
        "judge" to label,
        "startupMs" to timing.startupMs,
        "firstResponseMs" to timing.firstResponseMs,
        "modelTurns" to timing.modelTurns,
        "toolCalls" to timing.toolCalls,
        "verdictMs" to timing.verdictMs,
        "totalMs" to timing.totalMs,
        "inputTokens" to timing.inputTokens,
        "outputTokens" to timing.outputTokens,
        "partial" to timing.partial,
        "cleanupMs" to timing.verdictMs?.let { (timing.totalMs - it).coerceAtLeast(0L) },
        "verdict" to (submission?.verdict?.name ?: submission?.laneVerdicts?.entries?.joinToString(",") { "${it.key}=${it.value.name}" }),
        "error" to error,
        "briefMs" to briefMs,
        "hostReused" to hostReused,
    )

    /**
     * The first message of the judge: all the evidence and, for an agent that takes them, the screenshots. Null when it cannot be
     * put together (a log or image that fails to load); the judge then starts with the pointer to its tools, as it always could.
     * A judge cancelled while the brief is built still leaves its judge_finished line.
     */
    @Suppress("TooGenericExceptionCaught") // Evidence is best effort: a brief that cannot be built must not stop the judge.
    private suspend fun briefOf(evidence: JudgeEvidence, label: String, started: Long): JudgeBrief? = try {
        buildJudgeBrief(evidence, ::loadExample, agent.acceptsImagesUpFront)
    } catch (cancelled: CancellationException) {
        val elapsed = wallClock() - started
        transcript?.append(
            "judge_finished",
            finishedFields(label, AiRunTiming(totalMs = elapsed, partial = true), null, "The judge was cancelled.", elapsed, hostReused = false),
        )
        throw cancelled
    } catch (failure: Exception) {
        transcript?.append("judge_brief_failed", mapOf("judge" to label, "error" to (failure.message ?: failure::class.simpleName)))
        null
    }

    /** The submitted answer, or null when the judge's run ended without one or [EngineTuning.judgeTimeoutMs] passed. */
    private suspend fun awaitSubmission(run: AiRun, tools: JudgeTools): JudgeSubmission? {
        val job = checkNotNull(run.job) { "The judge run has no job." }
        val waited = withTimeoutOrNull(tuning.judgeTimeoutMs) {
            select<JudgeSubmission?> {
                tools.submitted.onAwait { it }
                job.onJoin { tools.answer }
            }
        }
        return waited ?: tools.answer
    }

    private fun failureOf(run: AiRun): String {
        val error = run.history.lastOrNull { it is AiRunEvent.Error } as? AiRunEvent.Error
        val stillRunning = run.job?.isActive == true
        return when {
            stillRunning -> "The judge did not answer within ${tuning.judgeTimeoutMs / MILLIS_PER_SECOND} s."
            error != null -> "The judge failed: ${error.message}"
            else -> ENDED_WITHOUT_ANSWER
        }
    }

    /** The golden image, scaled down like a screenshot; a format the JDK cannot decode (webp) goes to the model as it is. */
    private suspend fun loadExample(example: StepExample.GoldenScreenshot): JudgeImage? = withContext(Dispatchers.IO) {
        runCatching { goldenImage(suiteId, example.assetPath) }.getOrNull()?.let(::boundedJudgeImage)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
