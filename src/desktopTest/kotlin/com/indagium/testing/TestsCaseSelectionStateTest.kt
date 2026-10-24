package com.indagium.testing

import com.indagium.edition.EditionLimits
import com.indagium.testing.model.CaseOutcome
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseRunCell
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunCaseOutcomes
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.deriveCaseRunMatrix
import com.indagium.ui.caseLastRun
import com.indagium.ui.caseNumberRanges
import com.indagium.ui.caseSelectionMovedBy
import com.indagium.ui.lastRunCellDescription
import com.indagium.ui.latestStepStatuses
import com.indagium.ui.lockedCasesNoticeText
import com.indagium.ui.testsLimitsUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pure helpers behind the suite screen's row selection, locked-cases notice and case detail panel. */
@Suppress("MagicNumber") // Synthetic positions, ids and timestamps.
class TestsCaseSelectionStateTest {
    private val free = EditionLimits.FREE
    private val ids = listOf("a", "b", "c")

    @Test
    fun selectionMovesByOneAndStaysInsideTheList() {
        assertEquals("b", caseSelectionMovedBy(ids, "a", 1))
        assertEquals("a", caseSelectionMovedBy(ids, "b", -1))
        assertEquals("c", caseSelectionMovedBy(ids, "c", 1), "clamped at the end")
        assertEquals("a", caseSelectionMovedBy(ids, "a", -1), "clamped at the start")
    }

    @Test
    fun withNoCurrentCaseTheFirstOrLastIsSelectedAndAnEmptyListSelectsNothing() {
        assertEquals("a", caseSelectionMovedBy(ids, null, 1))
        assertEquals("c", caseSelectionMovedBy(ids, null, -1))
        assertEquals("a", caseSelectionMovedBy(ids, "gone", 1), "an unknown current counts as none")
        assertNull(caseSelectionMovedBy(emptyList(), null, 1))
        assertNull(caseSelectionMovedBy(emptyList(), "a", -1))
    }

    @Test
    fun caseNumbersCollapseIntoRanges() {
        assertEquals("7", caseNumberRanges(listOf(7)))
        assertEquals("6–8", caseNumberRanges(listOf(6, 7, 8)))
        assertEquals("2, 4–5", caseNumberRanges(listOf(2, 4, 5)))
        assertEquals("4–5", caseNumberRanges(listOf(5, 4, 5)), "order and duplicates are ignored")
        assertEquals("1, 3, 5", caseNumberRanges(listOf(1, 3, 5)))
    }

    @Test
    fun theNoticeNamesTheLockedCasesAndSaysThemInThePluralOrSingular() {
        val plural = suiteWithCases("S", 8)
        val pluralLimits = testsLimitsUiState(TestLibrary(suites = listOf(plural)), free)
        assertEquals(
            "Cases 6–8 are read-only on Free. You can still view, export, reorder and delete them.",
            lockedCasesNoticeText(plural, pluralLimits, "Free"),
        )

        val single = suiteWithCases("S", 6)
        val singleLimits = testsLimitsUiState(TestLibrary(suites = listOf(single)), free)
        assertEquals(
            "Case 6 is read-only on Free. You can still view, export, reorder and delete it.",
            lockedCasesNoticeText(single, singleLimits, "Free"),
        )
    }

    @Test
    fun noNoticeWhenNothingOrTheWholeSuiteIsLocked() {
        val within = suiteWithCases("S", 5)
        assertNull(lockedCasesNoticeText(within, testsLimitsUiState(TestLibrary(suites = listOf(within)), free), "Free"))

        val first = suiteWithCases("First", 2)
        val second = suiteWithCases("Second", 3)
        val library = TestLibrary(suites = listOf(first, second))
        assertNull(lockedCasesNoticeText(second, testsLimitsUiState(library, free), "Free"), "the suite's own notice covers a locked suite")
        assertNull(lockedCasesNoticeText(first, testsLimitsUiState(library, EditionLimits.UNLIMITED), "Unlimited"))
    }

    private fun lane() = LaneConfig("lane-a", LaneKind.EXTERNAL, deviceSerial = "d1")

    private fun step(id: String, status: StepStatus, setup: Boolean = false) = StepResult(id, 1, "Act", status = status, setup = setup)

    private fun run(vararg lanes: List<CaseResult>) = TestRun(
        id = "run-1", suite = TestSuite("s", "S"), scripts = emptyList(), sharedSteps = emptyList(),
        config = RunConfig("s", null, listOf(lane())),
        lanes = lanes.mapIndexed { index, cases -> LaneResult("lane-$index", lane(), RunStatus.FAILED, cases) },
        status = RunStatus.FAILED, createdAt = 1_000L, finishedAt = 2_000L,
    )

    @Test
    fun stepStatusesAreTheWorstOverLanesAndIterationsAndLeaveOutHooksAndOtherCases() {
        val run = run(
            listOf(
                CaseResult(
                    "c1", "C1", 1, CaseStatus.PASS,
                    listOf(step("s1", StepStatus.PASS), step("s2", StepStatus.PASS), step("hook", StepStatus.FAIL, setup = true)),
                ),
                CaseResult("c1", "C1", 2, CaseStatus.FAIL, listOf(step("s1", StepStatus.PASS), step("s2", StepStatus.FAIL))),
                CaseResult("c2", "C2", 1, CaseStatus.FAIL, listOf(step("other", StepStatus.FAIL))),
            ),
            listOf(CaseResult("c1", "C1", 1, CaseStatus.BLOCKED, listOf(step("s1", StepStatus.BLOCKED)))),
        )

        val statuses = latestStepStatuses(run, "c1")

        assertEquals(mapOf<String, StepStatus?>("s1" to StepStatus.BLOCKED, "s2" to StepStatus.FAIL), statuses)
        assertNull(statuses["s3"], "a step with no result is not run")
        assertEquals(emptyMap(), latestStepStatuses(null, "c1"))
        assertEquals(emptyMap(), latestStepStatuses(run, "missing"))
    }

    @Test
    fun theLastRunOfACaseIsTheNewestColumnThatIncludedIt() {
        val suite = suiteWithCases("S", 2)
        val (one, two) = suite.cases

        fun outcomes(id: String, created: Long, cases: Map<String, CaseOutcome>) =
            RunCaseOutcomes(id, suite.id, RunStatus.PASSED, created, created, created + 5_000L, cases)
        val matrix = deriveCaseRunMatrix(
            suite,
            listOf(
                outcomes("r1", 1_000L, mapOf(one.id to CaseOutcome(CaseStatus.PASS, 1_200L, false), two.id to CaseOutcome(CaseStatus.FAIL, 900L, false))),
                outcomes("r2", 2_000L, mapOf(one.id to CaseOutcome(CaseStatus.FAIL, 3_400L, false))),
            ),
        )

        val last = caseLastRun(matrix, one.id)
        assertEquals("r2", last?.runId)
        assertEquals(3_400L, last?.durationMs)
        assertEquals(CaseStatus.FAIL, last?.status)
        assertEquals(7_000L, last?.momentMs)
        assertEquals("r1", caseLastRun(matrix, two.id)?.runId, "the newer run did not include it")
        assertNull(caseLastRun(matrix, "unknown"))
        assertNull(caseLastRun(deriveCaseRunMatrix(suite, emptyList()), one.id))
    }

    @Test
    fun lastRunSquaresAreDescribedWithTheRunNumber() {
        assertEquals("#21 passed", lastRunCellDescription(21, CaseRunCell.PASSED))
        assertEquals("#3 failed", lastRunCellDescription(3, CaseRunCell.FAILED))
        assertEquals("#3 not in run", lastRunCellDescription(3, CaseRunCell.NOT_IN_RUN))
        assertEquals("blocked", lastRunCellDescription(null, CaseRunCell.BLOCKED))
    }
}
