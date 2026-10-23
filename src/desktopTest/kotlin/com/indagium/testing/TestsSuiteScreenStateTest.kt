@file:Suppress("MagicNumber")

package com.indagium.testing

import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.IssueSource
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.RunSummary
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.SuiteCaseHistory
import com.indagium.testing.model.SuiteCaseLaneHistory
import com.indagium.testing.model.TestRun
import com.indagium.ui.SUITE_COMPACT_BELOW_DP
import com.indagium.ui.SuiteLastRun
import com.indagium.ui.SuiteTab
import com.indagium.ui.caseIssueIds
import com.indagium.ui.detail
import com.indagium.ui.laneResultLine
import com.indagium.ui.ranLanes
import com.indagium.ui.runOrdinals
import com.indagium.ui.suiteCasesCompact
import com.indagium.ui.suiteLastRun
import com.indagium.ui.suiteTabLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The pure state behind the suite screen: tab labels, the last-run line, the compact breakpoint, lane results and issue ids. */
class TestsSuiteScreenStateTest {
    private val lane = LaneConfig("lane-1", LaneKind.EXTERNAL, deviceSerial = "pixel-8")

    private fun run(status: RunStatus, vararg stepStatuses: StepStatus): TestRun {
        val suite = suiteOf(caseOf("Login"))
        val steps = stepStatuses.mapIndexed { i, s -> StepResult("s$i", i + 1, "Step $i", status = s) }
        return TestRun(
            id = "run-1", suite = suite, scripts = emptyList(), sharedSteps = emptyList(),
            config = RunConfig(suite.id, null, listOf(lane)),
            lanes = listOf(LaneResult("lane-1", lane, status, listOf(CaseResult("c1", "Login", 1, CaseStatus.PASS, steps)))),
            status = status, createdAt = 1_000L, finishedAt = 2_000L,
        )
    }

    private fun laneHistory(status: CaseStatus?) = SuiteCaseLaneHistory(lane, status, 48_000L, 2, emptyList(), 0)

    @Test
    fun theSuiteHasExactlyThreeTabsInOrder() {
        assertEquals(listOf("Cases", "Runs", "Settings"), SuiteTab.entries.map { it.label })
    }

    @Test
    fun tabLabelsCarryCountsAndHideTheRunCountWhileLoading() {
        assertEquals("Cases (3)", suiteTabLabel(SuiteTab.Cases, 3, 0))
        assertEquals("Runs (2)", suiteTabLabel(SuiteTab.Runs, 3, 2))
        assertEquals("Runs (0)", suiteTabLabel(SuiteTab.Runs, 3, 0))
        assertEquals("Runs", suiteTabLabel(SuiteTab.Runs, 3, null), "no count until the history loaded")
        assertEquals("Settings", suiteTabLabel(SuiteTab.Settings, 3, 2))
    }

    @Test
    fun theCaseTableTurnsCompactBelowSevenHundredTwentyDp() {
        assertEquals(720f, SUITE_COMPACT_BELOW_DP)
        assertTrue(suiteCasesCompact(719f))
        assertFalse(suiteCasesCompact(720f))
        assertFalse(suiteCasesCompact(900f))
    }

    private fun summary(id: String, createdAt: Long) =
        RunSummary(id, "Suite", RunStatus.PASSED, createdAt, createdAt + 1, 1, 1, 1, 1)

    @Test
    fun runOrdinalsNumberTheOldestRunOneAndBreakTiesById() {
        val ordinals = runOrdinals(
            listOf(summary("run-c", 3_000L), summary("run-b", 2_000L), summary("run-a2", 1_000L), summary("run-a1", 1_000L), summary("run-b", 2_000L)),
        )

        assertEquals(mapOf("run-a1" to 1, "run-a2" to 2, "run-b" to 3, "run-c" to 4), ordinals)
        assertEquals(emptyMap(), runOrdinals(emptyList()))
    }

    @Test
    fun theLastRunLineIsLoadingUntilTheHistoryLoadedAndOnlyThenNeverRun() {
        assertEquals(SuiteLastRun.Loading, suiteLastRun(loaded = false, latestFinished = null))
        assertEquals(SuiteLastRun.NeverRun, suiteLastRun(loaded = true, latestFinished = null))
    }

    @Test
    fun aFinishedRunSummarisesItsCasesLanesAndPassedSteps() {
        val line = suiteLastRun(loaded = false, latestFinished = run(RunStatus.FAILED, StepStatus.PASS, StepStatus.PASS, StepStatus.FAIL))

        assertIs<SuiteLastRun.Ran>(line)
        assertEquals(RunStatus.FAILED, line.status)
        assertEquals(2_000L, line.momentMs)
        assertEquals("1 case · 1 lane · 2 of 3 steps passed", line.detail())
    }

    @Test
    fun laneResultLinesNameTheLaneStatusDurationAndRepeats() {
        assertEquals("Pixel · Pass · 48s ×2", laneResultLine("Pixel", CaseStatus.PASS, 48_000L, 2))
        assertEquals("Pixel · Fail · 6m 52s", laneResultLine("Pixel", CaseStatus.FAIL, 412_000L, 1))
        assertEquals("Pixel · Not run", laneResultLine("Pixel", null, 0L, 1))
    }

    @Test
    fun onlyLanesThatRanTheCaseCount() {
        val history = SuiteCaseHistory("c1", listOf(laneHistory(CaseStatus.PASS), laneHistory(null)), disagreement = false)

        assertEquals(1, history.ranLanes().size)
        assertTrue((null as SuiteCaseHistory?).ranLanes().isEmpty())
    }

    @Test
    fun caseIssuesJoinTheLatestRunsIssuesAndTheStoredOnesWithoutDuplicates() {
        fun issue(id: String, caseId: String, suiteId: String = "suite-1") = IssueRecord(
            id, IssueDraft("t$id"), IssueSource("run-1", "lane-1", suiteId, caseId, "step-1"),
        )
        val history = SuiteCaseHistory(
            "c1",
            listOf(SuiteCaseLaneHistory(lane, CaseStatus.FAIL, 1L, 1, listOf("i1"), 0)),
            disagreement = false,
        )
        val stored = listOf(issue("i1", "c1"), issue("i2", "c1"), issue("i3", "c2"), issue("i4", "c1", suiteId = "other"))

        assertEquals(listOf("i1", "i2"), caseIssueIds(history, "c1", "suite-1", stored))
        assertEquals(listOf("i3"), caseIssueIds(null, "c2", "suite-1", stored))
        assertTrue(caseIssueIds(null, "c9", "suite-1", stored).isEmpty())
    }
}
