package com.indagium.testing.model

import com.indagium.model.AiUsageStats

// Domain types of the blind judge: what a run asks of it (JudgeMode), what it answers for one step (StepJudgement) and
// what it concludes where several lanes disagree (JudgeComparison). Pure data. The judge never sees the agent's claim or
// observation, so nothing here refers to them.

const val JUDGEMENT_ID_PREFIX = "jdg-"
const val COMPARISON_ID_PREFIX = "cmp-"

fun newJudgementId(): String = newPrefixedId(JUDGEMENT_ID_PREFIX)

fun newComparisonId(): String = newPrefixedId(COMPARISON_ID_PREFIX)

/**
 * When a judge runs. OFF: never. FAILURES_ONLY: for a step a deterministic check failed or the agent reported as failed
 * or blocked, and for any step that has a ScreenJudge or AskJudge check (those checks need the judge to be evaluated at all).
 * EVERY_STEP: for every step, after its deterministic checks.
 */
enum class JudgeMode(val wire: String, val label: String) {
    OFF("off", "No judge"),
    FAILURES_ONLY("failures_only", "Failed steps only"),
    EVERY_STEP("every_step", "Every step"),
    ;

    companion object {
        /** The mode named by [text] (its wire name or its enum name, any case), or null. */
        fun parse(text: String?): JudgeMode? {
            val wanted = text?.trim().orEmpty()
            return entries.firstOrNull { it.wire.equals(wanted, ignoreCase = true) || it.name.equals(wanted, ignoreCase = true) }
        }
    }
}

enum class JudgeVerdict { PASS, FAIL, INCONCLUSIVE }

/** Who the judge blames for a failure: the app, the agent or the step as written, or it cannot say. */
enum class JudgeClassification { APP_DEFECT, AGENT_OR_STEP_PROBLEM, UNKNOWN }

/**
 * A change the judge suggests to the step as written in the library. A null part is left as it is. [note] explains the
 * change and is never applied. A fix with neither [action] nor [expected] is advice only.
 */
data class StepFix(val action: String? = null, val expected: String? = null, val note: String? = null) {
    val isApplicable: Boolean get() = !action.isNullOrBlank() || !expected.isNullOrBlank()
}

/**
 * The judge's answer for one step attempt. [error] is set when the judge itself could not finish (it timed out, ended
 * without a verdict or its model failed): the verdict is then INCONCLUSIVE. [fixApplied] flips once the suggested fix was
 * applied to the library, so it is not applied twice.
 */
data class StepJudgement(
    val id: String = newJudgementId(),
    val verdict: JudgeVerdict,
    val reasoning: String = "",
    val classification: JudgeClassification = JudgeClassification.UNKNOWN,
    val suggestedFix: StepFix? = null,
    val judgedAt: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
    val fixApplied: Boolean = false,
    /** Usage of this single judge attempt; CaseResult.judgeUsage holds the aggregate across retries. */
    val usage: AiUsageStats? = null,
)

/**
 * What the comparison judge made of a step the lanes disagreed on. [verdicts] maps a lane id to the judge's verdict on
 * that lane's evidence. The step is identified like a result: [caseId], [iteration], [stepId].
 */
data class JudgeComparison(
    val id: String = newComparisonId(),
    val caseId: String,
    val iteration: Int,
    val stepId: String,
    val stepNumber: Int,
    val action: String,
    val verdicts: Map<String, JudgeVerdict>,
    val classification: JudgeClassification = JudgeClassification.UNKNOWN,
    val explanation: String = "",
    val suggestedFix: StepFix? = null,
    val judgedAt: Long = 0L,
    val error: String? = null,
    val fixApplied: Boolean = false,
    /** Usage of the single comparison-judge run. */
    val usage: AiUsageStats? = null,
)
