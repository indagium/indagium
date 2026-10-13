package com.indagium.testing.run

import com.indagium.testing.model.CheckResult
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepJudgement
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestStep

/** A screenshot verdict cannot waive explicit checks or prove that an unfinished input/script has stopped. */
internal fun canRecoverTimeout(step: TestStep, results: List<CheckResult>, judgement: StepJudgement?, inputsIdle: Boolean): Boolean =
    step.kind == StepKind.AGENT && inputsIdle && judgement?.verdict == JudgeVerdict.PASS && judgement.error == null &&
        step.checks.all { check ->
            check is StepCheck.ScreenJudge || check is StepCheck.AskJudge ||
                results.any { it.checkId == check.id && it.status == CheckStatus.PASS }
        } && results.none { it.status == CheckStatus.FAIL || it.status == CheckStatus.ERROR }
