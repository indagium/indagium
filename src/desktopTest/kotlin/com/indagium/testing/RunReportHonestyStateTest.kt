@file:Suppress("MagicNumber", "ktlint:standard:max-line-length", "MaxLineLength")

package com.indagium.testing

import com.indagium.testing.model.CaseAttemptRecord
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.JudgeTiming
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepJudgement
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.agentJudgeDisagrees
import com.indagium.testing.run.failedCaseIds
import com.indagium.testing.run.interruptedCaseIds
import com.indagium.testing.run.rerunFailedCasesConfig
import com.indagium.testing.store.decodeRunFile
import com.indagium.testing.store.encodeRunFile
import com.indagium.ui.MatrixRow
import com.indagium.ui.TestRunReportFilter
import com.indagium.ui.buildMatrix
import com.indagium.ui.caseAttemptKey
import com.indagium.ui.detailLabel
import com.indagium.ui.filterReportRows
import com.indagium.ui.firstNonPassingSelection
import com.indagium.ui.matrixLabel
import com.indagium.ui.reportFilterCounts
import com.indagium.ui.rerunButtonLabel
import com.indagium.ui.runMetaLine
import com.indagium.ui.runVerdict
import com.indagium.ui.statusLabel
import com.indagium.ui.viewingAttempts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The report-level rules behind a cancelled, retried or disputed run: which cases a re-run offers, what the verdict says, how the
 * matrix shows an earlier attempt and what counts as an agent-versus-judge disagreement. Synthetic runs only.
 */
class RunReportHonestyStateTest {
    private val open = step("Open the app")
    private val tap = step("Tap the video")
    private val feed = step("Check the feed")
    private val case = caseOf("Watch", open, tap, feed)
    private val other = caseOf("Search", step("Open search"))
    private val suite = suiteOf(case, other)
    private val lane = LaneConfig("lane-a", LaneKind.EXTERNAL, deviceSerial = "pixel-8")

    private fun result(
        definition: com.indagium.testing.model.TestStep,
        number: Int,
        status: StepStatus,
        claim: String? = null,
        judge: JudgeVerdict? = null,
        executionStatus: StepStatus? = null,
        interrupted: Boolean = false,
        caseAttempt: Int = 1,
    ) = StepResult(
        definition.id, number, definition.action, status = status, agentClaim = claim, interrupted = interrupted, caseId = case.id,
        executionStatus = executionStatus ?: status, caseAttempt = caseAttempt,
        judge = judge?.let { StepJudgement(verdict = it, reasoning = "r") },
    )

    private fun run(
        runStatus: RunStatus,
        vararg cases: CaseResult,
        timing: JudgeTiming = JudgeTiming.INLINE,
    ) = TestRun(
        id = "run-1", suite = suite, scripts = emptyList(), sharedSteps = emptyList(),
        config = RunConfig(suite.id, null, listOf(lane), judgeProfileId = "judge", judgeMode = "every_step", judgeTiming = timing),
        lanes = listOf(LaneResult(lane.id, lane, runStatus, cases.toList())), status = runStatus,
    )

    /** The user's situation: attempt 1 failed (steps 2 and 3 by the judge), attempt 2 passed step 1 and was cancelled in step 2. */
    private fun cancelledDuringRetry(): TestRun {
        val first = CaseAttemptRecord(
            case.id, 1, 1, CaseStatus.FAIL,
            listOf(
                result(open, 1, StepStatus.PASS, "pass", JudgeVerdict.PASS),
                result(tap, 2, StepStatus.FAIL, "pass", JudgeVerdict.FAIL, StepStatus.PASS),
                result(feed, 3, StepStatus.FAIL, "pass", JudgeVerdict.FAIL, StepStatus.PASS),
            ),
        )
        return run(
            RunStatus.CANCELLED,
            CaseResult(
                case.id, case.name, 1, CaseStatus.CANCELLED,
                listOf(result(open, 1, StepStatus.PASS, "pass", caseAttempt = 2), result(tap, 2, StepStatus.ERROR, interrupted = true, caseAttempt = 2)),
                caseAttempt = 2, priorAttempts = listOf(first),
            ),
        )
    }

    // ── Which cases a re-run offers ─────────────────────────────────

    @Test
    fun anInterruptedStepIsNotAFailureAndItsCaseIsOfferedAsInterrupted() {
        val run = cancelledDuringRetry()

        assertTrue(failedCaseIds(run).isEmpty())
        assertEquals(setOf(case.id), interruptedCaseIds(run))
        assertEquals(listOf(case.id), rerunFailedCasesConfig(run).getOrThrow().caseIds)
    }

    @Test
    fun aCancelledCaseWithAGenuineFailureIsFailedNotInterruptedAndTheRerunKeepsBoth() {
        val run = run(
            RunStatus.CANCELLED,
            CaseResult(case.id, case.name, 1, CaseStatus.CANCELLED, listOf(result(open, 1, StepStatus.FAIL, "fail"), result(tap, 2, StepStatus.ERROR, interrupted = true))),
            CaseResult(other.id, other.name, 1, CaseStatus.CANCELLED),
        )

        assertEquals(setOf(case.id), failedCaseIds(run))
        assertEquals(setOf(other.id), interruptedCaseIds(run))
        assertEquals(listOf(case.id, other.id), rerunFailedCasesConfig(run).getOrThrow().caseIds)
    }

    @Test
    fun theRerunButtonNamesWhatItReruns() {
        assertEquals("Re-run failed (2)", rerunButtonLabel(2, 0))
        assertEquals("Re-run interrupted (1)", rerunButtonLabel(0, 1))
        assertEquals("Re-run failed and interrupted (3)", rerunButtonLabel(2, 1))
        assertEquals(null, rerunButtonLabel(0, 0))
    }

    // ── Verdict and labels ──────────────────────────────────────────

    @Test
    fun theVerdictOfACancelDuringARetryNamesWhereItStoppedAndWhatTheEarlierAttemptGotWrong() {
        assertEquals(
            "Cancelled — stopped during attempt 2 at step 2; attempt 1 failed at steps 2 and 3 (judge)",
            runVerdict(cancelledDuringRetry()).headline,
        )
    }

    @Test
    fun theVerdictMarksOnlyTheStepsTheJudgeFailedWhenTheAgentFailedSomeItself() {
        val first = CaseAttemptRecord(
            case.id, 1, 1, CaseStatus.FAIL,
            listOf(result(open, 1, StepStatus.FAIL, "fail", JudgeVerdict.PASS), result(feed, 3, StepStatus.FAIL, "pass", JudgeVerdict.FAIL, StepStatus.PASS)),
        )
        val run = run(RunStatus.CANCELLED, CaseResult(case.id, case.name, 1, CaseStatus.CANCELLED, emptyList(), caseAttempt = 2, priorAttempts = listOf(first)))

        assertEquals(
            "Cancelled — stopped during attempt 2 before its first step; attempt 1 failed at steps 1 and 3 (judge)",
            runVerdict(run).headline,
        )
    }

    @Test
    fun aCancelWithoutARetryKeepsTheShortVerdict() {
        val run = run(RunStatus.CANCELLED, CaseResult(case.id, case.name, 1, CaseStatus.CANCELLED, listOf(result(open, 1, StepStatus.PASS))))

        assertEquals("Cancelled — stopped after 1 of 4 steps", runVerdict(run).headline)
    }

    @Test
    fun anInterruptedStepShowsAsCancelledAndIsTheStepAReportOpensOn() {
        val run = cancelledDuringRetry()
        val interrupted = run.lanes.single().cases.single().steps.last()

        assertEquals("Cancelled", interrupted.statusLabel())
        assertEquals("Cancelled", interrupted.matrixLabel())
        assertEquals("Error", interrupted.copy(interrupted = false).statusLabel())
        val selection = assertNotNull(firstNonPassingSelection(run))
        assertTrue(selection.rowKey.endsWith(tap.id), "the report opens on the step the cancel interrupted")
    }

    @Test
    fun aTimedOutStepShowsTheJudgesAdviceBesideTheTimeout() {
        val met = result(tap, 2, StepStatus.TIMEOUT, judge = JudgeVerdict.PASS)

        assertEquals("Timeout · judge: expected result met", met.detailLabel())
        assertEquals("Timeout · met", met.matrixLabel())
        val recovered = met.copy(status = StepStatus.PASS, executionStatus = StepStatus.TIMEOUT)
        assertEquals("Pass · recovered", recovered.matrixLabel())
        assertEquals("Pass · recovered after timeout", recovered.detailLabel())
        assertEquals("Timeout · judge: expected result not met", met.copy(judge = met.judge?.copy(verdict = JudgeVerdict.FAIL)).detailLabel())
        assertEquals("Timeout", met.copy(judge = null).detailLabel())
        assertEquals("Fail", result(tap, 2, StepStatus.FAIL, judge = JudgeVerdict.PASS).detailLabel(), "advice belongs to a timeout only")
    }

    @Test
    fun theMetaLineSaysWhenTheJudgeRuns() {
        fun line(timing: JudgeTiming) = runMetaLine(run(RunStatus.PASSED, timing = timing), 0L, profileName = { null })

        assertTrue(line(JudgeTiming.INLINE).endsWith("judge: every step"), line(JudgeTiming.INLINE))
        assertTrue(line(JudgeTiming.AFTER_CASE).endsWith("judge: every step, after the case"), line(JudgeTiming.AFTER_CASE))
        assertTrue(line(JudgeTiming.BACKGROUND).endsWith("judge: every step, in the background"), line(JudgeTiming.BACKGROUND))
    }

    // ── Earlier attempts in the matrix ──────────────────────────────

    @Test
    fun theMatrixShowsTheLatestAttemptByDefaultAndAnEarlierOneOnRequest() {
        val run = cancelledDuringRetry()
        val header = buildMatrix(run).filterIsInstance<MatrixRow.Case>().first { it.caseId == case.id }

        assertEquals(2, header.attemptCount)
        assertEquals(2, header.viewingAttempt)
        assertEquals(mapOf(1 to CaseStatus.FAIL, 2 to CaseStatus.CANCELLED), header.attemptStatuses)

        val first = run.viewingAttempts(mapOf(caseAttemptKey(case.id, 1) to 1))
        val rows = buildMatrix(first)
        val firstHeader = rows.filterIsInstance<MatrixRow.Case>().first { it.caseId == case.id }
        assertEquals(1, firstHeader.viewingAttempt)
        assertEquals(2, firstHeader.attemptCount, "both attempts stay reachable")
        assertEquals(listOf(StepStatus.PASS, StepStatus.FAIL, StepStatus.FAIL), rows.filterIsInstance<MatrixRow.Step>().filter { it.caseId == case.id }.map { it.cells.single().result?.status })
        val firstCase = first.lanes.single().cases.single()
        assertEquals(CaseStatus.FAIL, firstCase.status)
        assertEquals(JudgeVerdict.FAIL, firstCase.steps[1].judge?.verdict, "the earlier attempt's judge verdict is in its rows")
        assertEquals(listOf(2), firstCase.priorAttempts.map { it.attempt }, "the latest attempt moved into the other attempts")

        val again = first.viewingAttempts(mapOf(caseAttemptKey(case.id, 1) to 2))
        assertEquals(run.lanes.single().cases.single().steps, again.lanes.single().cases.single().steps)
        assertEquals(CaseStatus.CANCELLED, again.lanes.single().cases.single().status)
    }

    @Test
    fun anAttemptTheCaseNeverHadLeavesTheRunAsItIs() {
        val run = cancelledDuringRetry()

        assertEquals(run, run.viewingAttempts(mapOf(caseAttemptKey(case.id, 1) to 5)))
        assertEquals(run, run.viewingAttempts(emptyMap()))
        assertEquals(run, run.viewingAttempts(mapOf(caseAttemptKey(other.id, 1) to 1)))
    }

    // ── Agent and judge disagree ────────────────────────────────────

    @Test
    fun anAgentAndAJudgeWhoPointOppositeWaysCountAsADisagreementAndOnlyThose() {
        assertTrue(result(tap, 2, StepStatus.FAIL, "fail", JudgeVerdict.PASS).agentJudgeDisagrees())
        assertTrue(result(tap, 2, StepStatus.BLOCKED, "blocked", JudgeVerdict.PASS).agentJudgeDisagrees())
        assertTrue(result(tap, 2, StepStatus.FAIL, "pass", JudgeVerdict.FAIL, StepStatus.PASS).agentJudgeDisagrees())
        assertFalse(result(tap, 2, StepStatus.PASS, "pass", JudgeVerdict.PASS).agentJudgeDisagrees())
        assertFalse(result(tap, 2, StepStatus.FAIL, "fail", JudgeVerdict.FAIL).agentJudgeDisagrees())
        assertFalse(result(tap, 2, StepStatus.FAIL, "fail", JudgeVerdict.INCONCLUSIVE).agentJudgeDisagrees())
        assertFalse(result(tap, 2, StepStatus.FAIL, "fail", judge = null).agentJudgeDisagrees())
        assertFalse(result(tap, 2, StepStatus.PASS, claim = null, judge = JudgeVerdict.FAIL).agentJudgeDisagrees(), "a step nobody claimed anything about")
    }

    @Test
    fun theDisagreementsFilterAndCountIncludeAgentVersusJudgeOnASingleLane() {
        val run = run(
            RunStatus.FAILED,
            CaseResult(
                case.id, case.name, 1, CaseStatus.FAIL,
                listOf(
                    result(open, 1, StepStatus.PASS, "pass", JudgeVerdict.PASS),
                    result(tap, 2, StepStatus.FAIL, "fail", JudgeVerdict.PASS),
                    result(feed, 3, StepStatus.FAIL, "pass", JudgeVerdict.FAIL, StepStatus.PASS),
                ),
            ),
        )

        assertEquals(2, reportFilterCounts(run)[TestRunReportFilter.DISAGREEMENTS])
        val rows = filterReportRows(run, setOf(TestRunReportFilter.DISAGREEMENTS)).filterIsInstance<MatrixRow.Step>()
        assertEquals(listOf(tap.id, feed.id), rows.map { it.stepId })
    }

    // ── The stored run ──────────────────────────────────────────────

    @Test
    fun theInterruptedMarkerIsWrittenOnlyWhenSetAndAnOlderRunDecodesWithoutIt() {
        val interrupted = cancelledDuringRetry()
        val text = encodeRunFile(interrupted)

        assertTrue(decodeRunFile(text).getOrThrow().lanes.single().cases.single().steps.last().interrupted)
        val plain = encodeRunFile(run(RunStatus.PASSED, CaseResult(case.id, case.name, 1, CaseStatus.PASS, listOf(result(open, 1, StepStatus.PASS)))))
        assertFalse("interrupted" in plain, "a run without an interrupted step is byte-compatible with an older app's output")
        assertFalse(decodeRunFile(plain).getOrThrow().lanes.single().cases.single().steps.single().interrupted)
    }
}
