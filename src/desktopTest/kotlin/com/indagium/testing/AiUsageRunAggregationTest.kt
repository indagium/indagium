package com.indagium.testing

import com.indagium.model.AiUsageStats
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.JudgeClassification
import com.indagium.testing.model.JudgeComparison
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepJudgement
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.aiUsage
import com.indagium.testing.model.summary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiUsageRunAggregationTest {
    @Test
    fun runTotalsSumCaseRetriesLanesAndComparisonWithoutAddingRollupsOrStepJudgeTwice() {
        val suite = suiteOf(caseOf("Sign in", step("Open sign-in")))
        val stepDefinition = suite.cases.single().steps.single()
        val firstLane = agentLane("profile-a", "SER-A")
        val secondLane = agentLane("profile-b", "SER-B")
        val external = externalLane("SER-EXT")
        val inline = AiUsageStats(toolCalls = 1, inputTokens = 5, outputTokens = 2, totalTokens = 7)
        val originalJudgeAttempt = AiUsageStats(toolCalls = 99, inputTokens = 999, outputTokens = 999, totalTokens = 1_998)
        val firstOccurrence = CaseResult(
            caseId = suite.cases.single().id,
            caseName = "Sign in",
            iteration = 1,
            status = CaseStatus.PASS,
            steps = listOf(
                StepResult(
                    stepId = stepDefinition.id,
                    stepNumber = 1,
                    action = stepDefinition.action,
                    status = StepStatus.PASS,
                    judge = StepJudgement(verdict = JudgeVerdict.PASS, usage = originalJudgeAttempt),
                ),
            ),
            agentUsage = AiUsageStats(toolCalls = 2, inputTokens = 100, outputTokens = 20, totalTokens = 120),
            judgeUsage = inline,
        )
        val retry = CaseResult(
            caseId = suite.cases.single().id,
            caseName = "Sign in",
            iteration = 2,
            status = CaseStatus.PASS,
            agentUsage = AiUsageStats(toolCalls = 3, inputTokens = 50, outputTokens = 10, totalTokens = 60),
        )
        val secondLaneCase = CaseResult(
            caseId = suite.cases.single().id,
            caseName = "Sign in",
            status = CaseStatus.PASS,
            agentUsage = AiUsageStats(toolCalls = 1, inputTokens = 25, outputTokens = 5, totalTokens = 30),
        )
        val externalCase = CaseResult(
            caseId = suite.cases.single().id,
            caseName = "Sign in",
            status = CaseStatus.PASS,
            externalToolCalls = 4,
        )
        val comparison = JudgeComparison(
            caseId = suite.cases.single().id,
            iteration = 1,
            stepId = stepDefinition.id,
            stepNumber = 1,
            action = stepDefinition.action,
            verdicts = emptyMap(),
            classification = JudgeClassification.UNKNOWN,
            usage = AiUsageStats(toolCalls = 2, inputTokens = 10, outputTokens = 2, totalTokens = 12),
        )
        val run = TestRun(
            id = "run-usage",
            suite = suite,
            scripts = emptyList(),
            sharedSteps = emptyList(),
            config = RunConfig(suite.id, lanes = listOf(firstLane, secondLane, external)),
            lanes = listOf(
                LaneResult(firstLane.id, firstLane, RunStatus.PASSED, listOf(firstOccurrence, retry)),
                LaneResult(secondLane.id, secondLane, RunStatus.PASSED, listOf(secondLaneCase)),
                LaneResult(external.id, external, RunStatus.PASSED, listOf(externalCase)),
            ),
            status = RunStatus.PASSED,
            comparisons = listOf(comparison),
        )

        val laneTotal = run.lanes.first().aiUsage()
        assertEquals(6L, laneTotal?.toolCalls)
        assertEquals(187L, laneTotal?.totalTokens)
        val total = run.aiUsage()
        assertEquals(13L, total?.toolCalls)
        assertEquals(190L, total?.inputTokens)
        assertEquals(39L, total?.outputTokens)
        assertEquals(229L, total?.totalTokens)
        assertTrue(total?.partial == true, "the external MCP actor has a known call count but no token report")
        assertEquals(total, run.summary().usage)
    }

    @Test
    fun absentJudgeHooksAndScriptScopesDoNotMakeACompleteAgentReportPartial() {
        val suite = suiteOf(caseOf("One step", step("Open app")))
        val lane = agentLane("profile-a", "SER-A")
        val external = externalLane("SER-EXT")
        val case = CaseResult(
            caseId = suite.cases.single().id,
            caseName = suite.cases.single().name,
            status = CaseStatus.PASS,
            agentUsage = AiUsageStats(toolCalls = 1, inputTokens = 10, outputTokens = 2, totalTokens = 12),
        )
        val run = TestRun(
            id = "run-no-judge",
            suite = suite,
            scripts = emptyList(),
            sharedSteps = emptyList(),
            config = RunConfig(suite.id, lanes = listOf(lane)),
            lanes = listOf(LaneResult(lane.id, lane, RunStatus.PASSED, listOf(case))),
            status = RunStatus.PASSED,
        )
        val externalOnlyRun = run.copy(
            config = run.config.copy(lanes = listOf(lane, external)),
            lanes = run.lanes + LaneResult(
                external.id,
                external,
                RunStatus.PASSED,
                listOf(case.copy(caseId = "external-case", externalToolCalls = 0L)),
            ),
        )

        assertFalse(run.aiUsage()!!.partial)
        assertEquals(12L, run.summary().usage?.totalTokens)
        assertFalse(externalOnlyRun.aiUsage()!!.partial, "an external actor that was never invoked contributes no missing-usage scope")
    }
}
