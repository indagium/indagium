package com.indagium.testing

import com.indagium.testing.model.CheckResult
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepJudgement
import com.indagium.testing.model.StepKind
import com.indagium.testing.run.canRecoverTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimeoutRecoveryTest {
    private val state = StepCheck.DeviceState("wifi", "wifi.enabled", "false")
    private val step = plainStep().copy(checks = listOf(state, StepCheck.AskJudge("judge", "Is Wi-Fi off?")))
    private val pass = StepJudgement(verdict = JudgeVerdict.PASS)
    private val results = listOf(CheckResult(state.id, "deviceState", CheckStatus.PASS, "Wi-Fi is off", 0))

    @Test
    fun aJudgeCanRecoverOnlyAfterEveryDeterministicCheckPasses() {
        assertTrue(canRecoverTimeout(step, results, pass, inputsIdle = true))
        assertFalse(canRecoverTimeout(step, emptyList(), pass, inputsIdle = true))
        for (status in listOf(CheckStatus.FAIL, CheckStatus.ERROR, CheckStatus.NOT_EVALUATED)) {
            assertFalse(canRecoverTimeout(step, results.map { it.copy(status = status) }, pass, inputsIdle = true))
        }
    }

    @Test
    fun uncertaintyRunningInputsAndDriverStepsCannotBeRecovered() {
        assertFalse(canRecoverTimeout(step, results, pass, inputsIdle = false))
        assertFalse(canRecoverTimeout(step, results, null, inputsIdle = true))
        for (verdict in listOf(JudgeVerdict.FAIL, JudgeVerdict.INCONCLUSIVE)) {
            assertFalse(canRecoverTimeout(step, results, pass.copy(verdict = verdict), inputsIdle = true))
        }
        assertFalse(canRecoverTimeout(step, results, pass.copy(error = "failed request"), inputsIdle = true))
        for (kind in listOf(StepKind.SCRIPT, StepKind.MANUAL, StepKind.REPLAY)) {
            assertFalse(canRecoverTimeout(step.copy(kind = kind), results, pass, inputsIdle = true))
        }
    }
}
