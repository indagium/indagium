package com.indagium.testing.run

import com.indagium.testing.model.CheckResult
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepJudgement
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestStep

// How a judge's verdict is woven into a step's result, and when a judge is asked at all. Pure functions, so the rules
// are testable without a run.
//
// FINAL STEP STATUS (a judge is configured):
//   1. The base status is the one without a judge: PASS when the agent claimed pass and no check failed, BLOCKED when it
//      claimed blocked, FAIL otherwise. A deterministic failure (a log, script or judge check that failed) is therefore FAIL,
//      and a judge can never turn a failure, a block or a timeout into a pass.
//   2. Only a base PASS is revisited: a judge verdict of FAIL makes it FAIL; INCONCLUSIVE keeps PASS only when judging
//      was optional. Explicit judge checks become BLOCKED until a judge supplies a verdict.
//   3. The step's ScreenJudge and AskJudge checks, NOT_EVALUATED without a judge, take the judge's verdict (PASS or FAIL);
//      an inconclusive judge leaves them NOT_EVALUATED with the reason in their detail.
//   No judge: exactly the base status, judge checks NOT_EVALUATED.

private const val DETAIL_REASONING_CHARS = 300

/** Where in a lane a judgement happens; names the judge's session and its transcript lines. */
internal data class JudgeSite(val caseId: String, val evidencePrefix: String, val stepId: String, val stepNumber: Int, val attempt: Int)

/** Judges one step of one lane. Never throws for a judge that fails: that is an INCONCLUSIVE judgement with an error. */
internal fun interface StepJudge {
    suspend fun judge(site: JudgeSite, evidence: JudgeEvidence): StepJudgement
}

/** The status of a step after its judgement, and whether the judge could not decide. */
internal data class JudgedStatus(val status: StepStatus, val inconclusive: Boolean)

internal fun hasJudgeChecks(step: TestStep): Boolean = step.checks.any { it is StepCheck.ScreenJudge || it is StepCheck.AskJudge }

/** Whether the judge is asked about an attempt. A step with judge checks always needs it: nothing else can answer them. */
internal fun shouldJudge(mode: JudgeMode, step: TestStep, claim: LaneStepStatus, deterministicFailed: Boolean): Boolean = when (mode) {
    JudgeMode.OFF -> false
    JudgeMode.EVERY_STEP -> true
    JudgeMode.FAILURES_ONLY -> deterministicFailed || claim != LaneStepStatus.PASS || hasJudgeChecks(step)
}

internal fun settleWithJudge(base: StepStatus, judgement: StepJudgement?, requiresVerdict: Boolean = false): JudgedStatus {
    if (base != StepStatus.PASS) return JudgedStatus(base, inconclusive = false)
    if (judgement == null) return JudgedStatus(if (requiresVerdict) StepStatus.BLOCKED else base, inconclusive = false)
    return when (judgement.verdict) {
        JudgeVerdict.FAIL -> JudgedStatus(StepStatus.FAIL, inconclusive = false)
        JudgeVerdict.INCONCLUSIVE -> JudgedStatus(if (requiresVerdict) StepStatus.BLOCKED else base, inconclusive = true)
        JudgeVerdict.PASS -> JudgedStatus(base, inconclusive = false)
    }
}

/** The judge checks (the NOT_EVALUATED ones) of [results] answered with [judgement]; every other result is unchanged. */
internal fun applyJudgementToChecks(results: List<CheckResult>, judgement: StepJudgement?): List<CheckResult> {
    if (judgement == null) return results
    val why = judgement.reasoning.trim().replace(Regex("\\s+"), " ").take(DETAIL_REASONING_CHARS)
    return results.map { result ->
        if (result.status != CheckStatus.NOT_EVALUATED) {
            result
        } else {
            when (judgement.verdict) {
                JudgeVerdict.PASS -> result.copy(status = CheckStatus.PASS, detail = "The judge passed the step: $why")
                JudgeVerdict.FAIL -> result.copy(status = CheckStatus.FAIL, detail = "The judge failed the step: $why")
                JudgeVerdict.INCONCLUSIVE ->
                    result.copy(detail = "The judge could not decide${judgement.error?.let { ": $it" } ?: ". $why"}".trimEnd())
            }
        }
    }
}
