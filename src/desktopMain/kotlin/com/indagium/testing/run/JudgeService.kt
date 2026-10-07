package com.indagium.testing.run

import com.indagium.ai.AiInvestigationContext
import com.indagium.ai.AiRun
import com.indagium.ai.AiRunEvent
import com.indagium.ai.AiSession
import com.indagium.testing.model.JudgeClassification
import com.indagium.testing.model.JudgeComparison
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.StepJudgement
import com.indagium.testing.store.TranscriptWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// Runs the judge: one separate AiRun per judgement (a step) or comparison (lanes that disagreed), through the same agent
// launchers as the lanes (an in-app model, Claude Code or Codex), behind a judge-only gateway (JudgeTools). The run ends
// as soon as the answer is submitted. A judge that times out, ends without answering or fails answers INCONCLUSIVE with the
// reason in [StepJudgement.error]; it never throws, so a flaky judge cannot break a run. Everything the judge says and does
// goes to the run's judge.jsonl, each line tagged with what it judged.

private const val ENDED_WITHOUT_ANSWER = "The judge ended without submitting a verdict."
private const val SESSION_PREFIX = "testjudge"

/** One finished judge run: its answer, or why it has none. */
private class JudgeOutcome(val submission: JudgeSubmission?, val failure: String?, val durationMs: Long)

internal class JudgeService(
    private val runId: String,
    private val suiteId: String,
    private val agent: LaneAgent,
    private val tuning: EngineTuning,
    private val transcript: TranscriptWriter?,
    private val goldenImage: (suiteId: String, assetPath: String) -> ByteArray?,
    private val wallClock: () -> Long,
) : AutoCloseable {
    /** The judge of one lane's steps. */
    fun forLane(laneId: String): StepJudge = StepJudge { site, evidence -> judgeStep(laneId, site, evidence) }

    suspend fun judgeStep(laneId: String, site: JudgeSite, evidence: JudgeEvidence): StepJudgement {
        val label = "$laneId:${site.evidencePrefix}-s${site.stepNumber}-a${site.attempt}"
        val outcome = runJudge(evidence, label, JUDGE_TOOL_CALL_BUDGET, JUDGE_SYSTEM_PROMPT, judgePrompt(evidence))
        val submission = outcome.submission
        return StepJudgement(
            verdict = submission?.verdict ?: JudgeVerdict.INCONCLUSIVE,
            reasoning = submission?.reasoning.orEmpty(),
            classification = submission?.classification ?: JudgeClassification.UNKNOWN,
            suggestedFix = submission?.fix,
            judgedAt = wallClock(),
            durationMs = outcome.durationMs,
            error = outcome.failure,
        )
    }

    /** The judgement of a step the lanes disagreed on. [evidence] has one entry per lane; [site] names the step. */
    suspend fun compare(caseId: String, iteration: Int, stepId: String, evidence: JudgeEvidence): JudgeComparison {
        val label = "compare:$caseId-i$iteration-s${evidence.stepNumber}"
        val outcome = runJudge(evidence, label, COMPARISON_TOOL_CALL_BUDGET, COMPARISON_SYSTEM_PROMPT, comparisonPrompt(evidence))
        val submission = outcome.submission
        val verdicts = submission?.laneVerdicts ?: evidence.lanes.associate { it.laneId to JudgeVerdict.INCONCLUSIVE }
        return JudgeComparison(
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
        )
    }

    override fun close() = agent.close()

    // ── One judge run ────────────────────────────────────────────────

    @Suppress("TooGenericExceptionCaught", "LongParameterList") // Whatever starting the judge throws means "no verdict".
    private suspend fun runJudge(evidence: JudgeEvidence, label: String, budget: Int, systemPrompt: String, prompt: String): JudgeOutcome {
        val started = wallClock()
        val tools = JudgeTools(evidence, ::loadExample)
        val tabId = "$SESSION_PREFIX:$runId:$label"
        val session = AiSession(tabId)
        val request = AgentSegmentRequest(
            session = session,
            prompt = prompt,
            systemPrompt = systemPrompt,
            context = AiInvestigationContext(tabId),
            gateway = tools.gateway,
            toolCallLimit = budget,
            maxTurns = budget + tuning.judgeTurnHeadroom,
            freeTools = JUDGE_FREE_TOOL_NAMES,
            confirmationTimeoutMs = tuning.judgeTimeoutMs,
            budgetGuidance = ::judgeBudgetGuidance,
            promptPreamble = ::judgePromptPreamble,
        )
        transcript?.append("judge_started", mapOf("judge" to label, "step" to evidence.stepNumber, "lanes" to evidence.lanes.size))
        val run = try {
            agent.start(request)
        } catch (failure: Exception) {
            session.deleteClaudeCodeWorkspace()
            return JudgeOutcome(null, "The judge could not start: ${failure.message ?: failure::class.simpleName}", wallClock() - started)
        }
        val tail = transcript?.let { TranscriptTail(run, it, mapOf("judge" to label)) }
        try {
            val submission = awaitSubmission(run, tools)
            val failure = if (submission != null) null else failureOf(run)
            return JudgeOutcome(submission, failure, wallClock() - started)
        } finally {
            withContext(NonCancellable) {
                run.cancel()
                withTimeoutOrNull(tuning.agentCancelWaitMs) { run.job?.join() }
                tail?.close()
                session.deleteClaudeCodeWorkspace()
            }
        }
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
