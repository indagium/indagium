package com.indagium.testing

import com.indagium.testing.model.OnFailure
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestStep
import com.indagium.ui.effectiveSelectedStepId
import com.indagium.ui.stepAdvancedSummary
import com.indagium.ui.stepChecksSummary
import com.indagium.ui.stepSelectionAfterDelete
import com.indagium.ui.stepSelectionMovedBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pure rules behind the step list: which step is selected, where the selection goes, and the row / inspector summaries. */
class StepSelectionRulesTest {
    private val a = TestStep("a", "A")
    private val b = TestStep("b", "B")
    private val c = TestStep("c", "C")
    private val steps = listOf(a, b, c)

    @Test
    fun theSelectionIsTheChosenStepElseTheFirstOne() {
        assertEquals("b", effectiveSelectedStepId(steps, "b"))
        assertEquals("a", effectiveSelectedStepId(steps, "elsewhere"))
        assertEquals("a", effectiveSelectedStepId(steps, null))
        assertNull(effectiveSelectedStepId(emptyList(), "a"))
    }

    @Test
    fun deletingSelectsTheNextStepElseThePreviousElseNothing() {
        assertEquals("b", stepSelectionAfterDelete(steps, "a"))
        assertEquals("c", stepSelectionAfterDelete(steps, "b"))
        assertEquals("b", stepSelectionAfterDelete(steps, "c"))
        assertNull(stepSelectionAfterDelete(listOf(a), "a"))
        assertNull(stepSelectionAfterDelete(steps, "missing"))
    }

    @Test
    fun movingTheSelectionIsClampedToTheListEnds() {
        assertEquals("b", stepSelectionMovedBy(steps, "a", 1))
        assertEquals("a", stepSelectionMovedBy(steps, "a", -1))
        assertEquals("c", stepSelectionMovedBy(steps, "c", 1))
        assertEquals("a", stepSelectionMovedBy(steps, "missing", 1), "an unknown selection starts at the first step")
        assertNull(stepSelectionMovedBy(emptyList(), "a", 1))
    }

    @Test
    fun theChecksSummaryNamesTheKindOfOneCheckAndCountsSeveral() {
        assertNull(stepChecksSummary(a))
        assertEquals("Screen", stepChecksSummary(a.copy(checks = listOf(StepCheck.ScreenJudge("k1", "ok")))))
        assertEquals("2 checks", stepChecksSummary(a.copy(checks = listOf(StepCheck.ScreenJudge("k1", "ok"), StepCheck.AskJudge("k2", "ok?")))))
    }

    @Test
    fun theAdvancedSummaryIsBuiltFromTheFieldsTheKindHas() {
        val agent = a.copy(retries = 1, maxToolCalls = 40, onFailure = OnFailure.STOP_CASE)
        assertEquals("retries 1 · up to 40 tool calls · on failure: stop the case", stepAdvancedSummary(agent))
        assertEquals(
            "retries 2 · up to 40 tool calls · on failure: continue · timeout 2m 00s",
            stepAdvancedSummary(agent.copy(retries = 2, onFailure = OnFailure.CONTINUE, timeoutMs = 120_000)),
        )
        val script = a.copy(kind = StepKind.SCRIPT, retries = 0)
        assertEquals("retries 0 · on failure: stop the case", stepAdvancedSummary(script))
        val manual = a.copy(kind = StepKind.MANUAL, timeoutMs = com.indagium.testing.model.DEFAULT_MANUAL_STEP_TIMEOUT_MS)
        assertEquals("on failure: stop the case", stepAdvancedSummary(manual))
    }
}
