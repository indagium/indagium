@file:Suppress("MagicNumber")

package com.indagium.testing

import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.IssueSource
import com.indagium.testing.model.IssueStatus
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunCaseOutcomes
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepJudgement
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.SuiteHealthSummary
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestStep
import com.indagium.ui.LATEST_FAILURE_DETAIL_CHARS
import com.indagium.ui.healthColumnTooltip
import com.indagium.ui.healthPassRateDetail
import com.indagium.ui.healthPassRateValue
import com.indagium.ui.latestFailureHeadline
import com.indagium.ui.latestFailureOf
import com.indagium.ui.openSuiteIssueCount
import com.indagium.ui.trimmedFailureDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure helpers behind the suite screen's Health tab. */
class TestsSuiteHealthStateTest {
    private fun health(passed: Int, failed: Int, rate: Int?) =
        SuiteHealthSummary(passed + failed, passed, failed, rate, null, emptyList(), null)

    @Test
    fun passRateReadsAsPercentWithRunCounts() {
        val summary = health(10, 2, 83)
        assertEquals("83%", healthPassRateValue(summary))
        assertEquals("10 passed · 2 failed", healthPassRateDetail(summary))
    }

    @Test
    fun passRateIsADashWhenNoRunWasJudged() {
        val summary = health(0, 0, null)
        assertEquals("—", healthPassRateValue(summary))
        assertEquals("No passed or failed runs", healthPassRateDetail(summary))
    }

    @Test
    fun openIssuesCountDraftsAndSavedOnesOfThisSuiteOnly() {
        fun issue(suiteId: String, status: IssueStatus) = IssueRecord(
            id = "i-$suiteId-$status", draft = IssueDraft("t"),
            source = IssueSource("run", "lane", suiteId, "case", "step"), status = status,
        )
        val issues = listOf(
            issue("s1", IssueStatus.DRAFT), issue("s1", IssueStatus.SAVED), issue("s1", IssueStatus.SENT), issue("s2", IssueStatus.DRAFT),
        )
        assertEquals(2, openSuiteIssueCount(issues, "s1"))
        assertEquals(1, openSuiteIssueCount(issues, "s2"))
        assertEquals(0, openSuiteIssueCount(issues, "s3"))
    }

    @Test
    fun failureDetailIsFlattenedAndCutToTheLimitWithAnEllipsis() {
        assertEquals("a b c", trimmedFailureDetail("  a\n b\t c  "))
        val long = "x".repeat(LATEST_FAILURE_DETAIL_CHARS + 50)
        val cut = trimmedFailureDetail(long)
        assertEquals(LATEST_FAILURE_DETAIL_CHARS, cut.length)
        assertTrue(cut.endsWith("…"))
        assertEquals("x".repeat(LATEST_FAILURE_DETAIL_CHARS), trimmedFailureDetail("x".repeat(LATEST_FAILURE_DETAIL_CHARS)))
    }

    @Test
    fun columnTooltipNamesTheMomentAndTheStatus() {
        val column = RunCaseOutcomes("r1", "s1", RunStatus.FAILED, 1_000L, 1_000L, 2_000L, emptyMap())
        val text = healthColumnTooltip(column, nowMs = 5_000L)
        assertTrue(text.endsWith(" · Failed"), text)
    }

    private val lane = LaneConfig("lane-1", LaneKind.EXTERNAL, deviceSerial = "pixel-8")

    private fun failedRun(judgeReasoning: String?, observation: String): TestRun {
        val suite = suiteOf(caseOf("Login", TestStep("st1", "Tap Sign in"), TestStep("st2", "See home")))
        val case = suite.cases.single()
        val judge = judgeReasoning?.let { StepJudgement(verdict = JudgeVerdict.FAIL, reasoning = it) }
        val results = listOf(
            StepResult("st1", 1, "Tap Sign in", status = StepStatus.PASS),
            StepResult("st2", 2, "See home", status = StepStatus.FAIL, observation = observation, judge = judge),
        )
        return TestRun(
            id = "run-1", suite = suite, scripts = emptyList(), sharedSteps = emptyList(),
            config = RunConfig(suite.id, null, listOf(lane)),
            lanes = listOf(LaneResult("lane-1", lane, RunStatus.FAILED, listOf(CaseResult(case.id, case.name, 1, CaseStatus.FAIL, results)))),
            status = RunStatus.FAILED, createdAt = 1_000L, finishedAt = 2_000L,
        )
    }

    @Test
    fun theLatestFailureIsTheFirstFailingStepWithTheJudgesReasoningFirst() {
        val failure = assertNotNull(latestFailureOf(failedRun("The home screen never appeared.", "observed a spinner")))
        assertEquals("Login", failure.caseName)
        assertEquals("st2", failure.stepId)
        assertEquals(2, failure.stepNumber)
        assertEquals("lane-1", failure.laneId)
        assertEquals("The home screen never appeared.", failure.detail)
        assertEquals("#7 · Login · step 2 “See home”", latestFailureHeadline(7, failure))
        assertEquals("Login · step 2 “See home”", latestFailureHeadline(null, failure))
    }

    @Test
    fun theLatestFailureFallsBackToTheObservationWhenNoJudgeReasoned() {
        val failure = assertNotNull(latestFailureOf(failedRun(null, "observed a spinner")))
        assertEquals("observed a spinner", failure.detail)
    }

    @Test
    fun aRunWithoutAFailureHasNoLatestFailure() {
        val passed = failedRun(null, "").let { run ->
            run.copy(
                status = RunStatus.PASSED,
                lanes = run.lanes.map { l ->
                    l.copy(cases = l.cases.map { c -> c.copy(status = CaseStatus.PASS, steps = c.steps.map { it.copy(status = StepStatus.PASS) }) })
                },
            )
        }
        assertNull(latestFailureOf(passed))
    }
}
