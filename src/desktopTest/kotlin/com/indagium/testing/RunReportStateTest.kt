@file:Suppress("MagicNumber", "ktlint:standard:max-line-length", "MaxLineLength")

package com.indagium.testing

import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.JudgeMode
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestStep
import com.indagium.testing.run.ComparedCasePresence
import com.indagium.testing.run.ComparedRunStep
import com.indagium.testing.run.ComparedStepPresence
import com.indagium.testing.run.compareTestRuns
import com.indagium.ui.MatrixRow
import com.indagium.ui.RunReportTab
import com.indagium.ui.TestRunReportFilter
import com.indagium.ui.activityContext
import com.indagium.ui.buildMatrix
import com.indagium.ui.compareLines
import com.indagium.ui.firstNonPassingSelection
import com.indagium.ui.friendlyStatuses
import com.indagium.ui.reportFilterCounts
import com.indagium.ui.reportSelectionMovedBy
import com.indagium.ui.runMetaLine
import com.indagium.ui.runReportTabLabel
import com.indagium.ui.runVerdict
import com.indagium.ui.verdictGlyph
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure state behind the run report: verdict, meta line, filter pill counts, selection, and the Compare / Activity rows. */
class RunReportStateTest {
    private val agent = LaneConfig("lane-a", LaneKind.AGENT_PROFILE, "p1", "emulator-5554")
    private val external = LaneConfig("lane-b", LaneKind.EXTERNAL, deviceSerial = "pixel-8")

    private fun result(definition: TestStep, number: Int, status: StepStatus) =
        StepResult(definition.id, number, definition.action, status = status)

    private fun caseStatusOf(statuses: List<StepStatus>): CaseStatus = when {
        StepStatus.FAIL in statuses || StepStatus.TIMEOUT in statuses -> CaseStatus.FAIL
        StepStatus.BLOCKED in statuses -> CaseStatus.BLOCKED
        StepStatus.ERROR in statuses -> CaseStatus.ERROR
        else -> CaseStatus.PASS
    }

    /** A case result of [case] with one status per step (steps beyond the list were never reached). */
    private fun caseResult(case: TestCase, vararg statuses: StepStatus, status: CaseStatus? = caseStatusOf(statuses.toList())) =
        CaseResult(case.id, case.name, 1, status, case.steps.zip(statuses.toList()).mapIndexed { i, (def, st) -> result(def, i + 1, st) })

    private fun lane(config: LaneConfig, status: RunStatus, vararg cases: CaseResult, error: String? = null) =
        LaneResult(config.id, config, status, cases.toList(), error = error)

    @Suppress("SpreadOperator")
    private fun run(
        status: RunStatus,
        cases: List<TestCase>,
        vararg lanes: LaneResult,
        error: String? = null,
        config: (RunConfig) -> RunConfig = { it },
    ): TestRun {
        val suite = suiteOf(*cases.toTypedArray())
        return TestRun(
            id = "run-1", suite = suite, scripts = emptyList(), sharedSteps = emptyList(),
            config = config(RunConfig(suite.id, null, lanes.map { it.config })),
            lanes = lanes.toList(), status = status, error = error,
        )
    }

    private val open = step("Open the app")
    private val tap = step("Tap Sign in")
    private val swipe = step("Swipe up on the watch page")
    private val login = caseOf("Login", open, tap)
    private val watch = caseOf("New case 3", open, tap, swipe)

    // ── Verdict ─────────────────────────────────────────────────────

    @Test
    fun aPassedRunSaysAllItsStepsPassed() {
        val run = run(RunStatus.PASSED, listOf(login), lane(agent, RunStatus.PASSED, caseResult(login, StepStatus.PASS, StepStatus.PASS)))
        val verdict = runVerdict(run)
        assertEquals(RunStatus.PASSED, verdict.status)
        assertEquals("Passed — all 2 steps passed", verdict.headline)
    }

    @Test
    fun aPassedRunNamesTheLaneCountAndSkippedSteps() {
        val twoLanes = run(
            RunStatus.PASSED, listOf(login),
            lane(agent, RunStatus.PASSED, caseResult(login, StepStatus.PASS, StepStatus.PASS)),
            lane(external, RunStatus.PASSED, caseResult(login, StepStatus.PASS, StepStatus.PASS)),
        )
        assertEquals("Passed — all 2 steps passed on 2 lanes", runVerdict(twoLanes).headline)
        val skipped = run(RunStatus.PASSED, listOf(login), lane(agent, RunStatus.PASSED, caseResult(login, StepStatus.PASS, StepStatus.SKIPPED)))
        assertEquals("Passed — all 1 step passed (1 skipped)", runVerdict(skipped).headline)
        val none = run(RunStatus.PASSED, listOf(login), lane(agent, RunStatus.PASSED))
        assertEquals("Passed — no steps were run", runVerdict(none).headline)
    }

    @Test
    fun aFailedRunNamesTheCaseTheStepNumberAndItsActionText() {
        val blocked = run(
            RunStatus.FAILED, listOf(login, watch),
            lane(agent, RunStatus.FAILED, caseResult(login, StepStatus.PASS, StepStatus.PASS), caseResult(watch, StepStatus.PASS, StepStatus.PASS, StepStatus.BLOCKED)),
        )
        assertEquals(RunStatus.FAILED, runVerdict(blocked).status)
        assertEquals("Failed — New case 3 blocked at step 3 “Swipe up on the watch page”", runVerdict(blocked).headline)
        val failed = run(RunStatus.FAILED, listOf(login), lane(agent, RunStatus.FAILED, caseResult(login, StepStatus.PASS, StepStatus.FAIL)))
        assertEquals("Failed — Login failed at step 2 “Tap Sign in”", runVerdict(failed).headline)
        val timeout = run(RunStatus.FAILED, listOf(login), lane(agent, RunStatus.FAILED, caseResult(login, StepStatus.TIMEOUT)))
        assertEquals("Failed — Login timed out at step 1 “Open the app”", runVerdict(timeout).headline)
    }

    @Test
    fun theFirstFailureIsTheFirstCaseAndStepInMatrixOrderAndTheLowestLaneWithinARow() {
        // Case "Login" passes; "New case 3" fails step 3 on lane A and step 2 on lane B: step 2 comes first in the matrix.
        val run = run(
            RunStatus.FAILED, listOf(login, watch),
            lane(
                agent, RunStatus.FAILED,
                caseResult(login, StepStatus.PASS, StepStatus.PASS),
                caseResult(watch, StepStatus.PASS, StepStatus.PASS, StepStatus.FAIL),
            ),
            lane(
                external, RunStatus.FAILED,
                caseResult(login, StepStatus.PASS, StepStatus.PASS),
                caseResult(watch, StepStatus.PASS, StepStatus.BLOCKED),
            ),
        )
        assertEquals("Failed — New case 3 blocked at step 2 “Tap Sign in”", runVerdict(run).headline)
        val selection = assertNotNull(firstNonPassingSelection(run))
        val row = buildMatrix(run).filterIsInstance<MatrixRow.Step>().first { it.key == selection.rowKey }
        assertEquals(tap.id, row.stepId)
        assertEquals("lane-b", selection.laneId)

        // Both lanes fail the same step: the lower lane wins.
        val both = run.copy(
            lanes = run.lanes.map { l ->
                l.copy(cases = l.cases.map { c -> if (c.caseId == watch.id) caseResult(watch, StepStatus.PASS, StepStatus.FAIL) else c })
            },
        )
        assertEquals("lane-a", firstNonPassingSelection(both)?.laneId)
        assertEquals("Failed — New case 3 failed at step 2 “Tap Sign in”", runVerdict(both).headline)
    }

    @Test
    fun aCaseThatDidNotPassWithoutAFailingStepIsNamedOnItsOwn() {
        val run = run(
            RunStatus.FAILED, listOf(login),
            lane(agent, RunStatus.FAILED, CaseResult(login.id, login.name, 1, CaseStatus.BLOCKED, emptyList())),
        )
        assertEquals("Failed — Login blocked", runVerdict(run).headline)
        assertNull(firstNonPassingSelection(run), "there is no step to select")
        val clean = run(RunStatus.FAILED, listOf(login), lane(agent, RunStatus.FAILED, caseResult(login, StepStatus.PASS, StepStatus.PASS)), error = "Device offline")
        assertEquals("Failed — Device offline", runVerdict(clean).headline)
        val nothing = run(RunStatus.FAILED, listOf(login), lane(agent, RunStatus.FAILED))
        assertEquals("Failed — see the results below", runVerdict(nothing).headline)
    }

    @Test
    fun aLongStepActionIsCutInTheHeadline() {
        val long = step("Scroll ".repeat(30))
        val longCase = caseOf("Long", long)
        val run = run(RunStatus.FAILED, listOf(longCase), lane(agent, RunStatus.FAILED, caseResult(longCase, StepStatus.FAIL)))
        val headline = runVerdict(run).headline
        assertTrue(headline.endsWith("…”"), headline)
        assertTrue(headline.length < 120, headline)
    }

    @Test
    fun aCancelledRunSaysHowFarItGot() {
        val partial = run(
            RunStatus.CANCELLED, listOf(login, watch),
            lane(agent, RunStatus.CANCELLED, caseResult(login, StepStatus.PASS, StepStatus.PASS), caseResult(watch, StepStatus.PASS, status = null)),
        )
        // login: 2 steps, watch: 3 steps; 3 of the 5 have a result.
        assertEquals("Cancelled — stopped after 3 of 5 steps", runVerdict(partial).headline)
        val before = run(RunStatus.CANCELLED, listOf(login), lane(agent, RunStatus.CANCELLED))
        assertEquals("Cancelled — stopped before any step ran", runVerdict(before).headline)
    }

    @Test
    fun anErrorRunShowsTheRunErrorElseTheLaneError() {
        val withRunError = run(RunStatus.ERROR, listOf(login), lane(agent, RunStatus.ERROR), error = "adb: device offline\nsecond line")
        assertEquals("Error — adb: device offline", runVerdict(withRunError).headline)
        val oneLane = run(RunStatus.ERROR, listOf(login), lane(agent, RunStatus.ERROR, error = "Profile not found"))
        assertEquals("Error — Profile not found", runVerdict(oneLane).headline)
        val twoLanes = run(RunStatus.ERROR, listOf(login), lane(agent, RunStatus.PASSED), lane(external, RunStatus.ERROR, error = "No device"))
        assertEquals("Error — Lane 2: No device", runVerdict(twoLanes).headline)
        val bare = run(RunStatus.ERROR, listOf(login), lane(agent, RunStatus.ERROR))
        assertEquals("Error — the run ended with an error", runVerdict(bare).headline)
    }

    @Test
    fun aLiveRunSaysHowManyStepsAreDoneAndAQueuedOneThatItWaits() {
        val running = run(RunStatus.RUNNING, listOf(login, watch), lane(agent, RunStatus.RUNNING, caseResult(login, StepStatus.PASS, StepStatus.PASS)))
        assertEquals(RunStatus.RUNNING, runVerdict(running).status)
        assertEquals("Running — 2 of 5 steps done", runVerdict(running).headline)
        val queued = run(RunStatus.QUEUED, listOf(login), lane(agent, RunStatus.QUEUED))
        assertEquals("Queued — waiting to start", runVerdict(queued).headline)
    }

    @Test
    fun everyStatusHasAGlyph() {
        assertEquals(setOf("✓", "✕", "!", "■", "●"), RunStatus.entries.map(::verdictGlyph).toSet())
    }

    // ── Meta line ───────────────────────────────────────────────────

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun at(day: Int, hour: Int, minute: Int): Long = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun theMetaLineHasTimesCasesLanesAndJudge() {
        val base = run(
            RunStatus.PASSED, listOf(login, watch),
            lane(agent, RunStatus.PASSED, caseResult(login, StepStatus.PASS, StepStatus.PASS)),
            config = { it.copy(judgeMode = JudgeMode.FAILURES_ONLY.wire) },
        ).let { it.copy(startedAt = at(11, 13, 25), finishedAt = at(11, 13, 27) + 3_000L) }
        val line = runMetaLine(base, now(11), profileName = { if (it == "p1") "Codex account" else null }, zone = zone)
        assertEquals("today 13:25 → 13:27 (2m 03s) · 2 cases · 1 lane: emulator-5554 · Codex account · judge: failed steps only", line)
    }

    private fun now(day: Int) = at(day, 18, 0)

    @Test
    fun theMetaLineShowsRepeatsExternalLanesAndNoJudge() {
        val base = run(
            RunStatus.PASSED, listOf(login),
            lane(agent, RunStatus.PASSED),
            lane(external, RunStatus.PASSED),
            config = { it.copy(repeat = 3, caseIds = listOf(login.id)) },
        ).copy(startedAt = at(10, 23, 50), finishedAt = at(11, 0, 10))
        val line = runMetaLine(base, now(11), profileName = { null }, zone = zone)
        assertEquals(
            "yesterday 23:50 → today 00:10 (20m 00s) · 1 case × 3 repeats · 2 lanes: emulator-5554 · p1; pixel-8 · External (MCP) · no judge",
            line,
        )
        val running = base.copy(finishedAt = null, status = RunStatus.RUNNING)
        assertTrue(runMetaLine(running, now(11), profileName = { null }, zone = zone).startsWith("started yesterday 23:50 · "))
    }

    // ── Filter pill counts ──────────────────────────────────────────

    @Test
    fun filterCountsCountMatchingStepRowsAndKeepZeroes() {
        val run = run(
            RunStatus.FAILED, listOf(login, watch),
            lane(
                agent, RunStatus.FAILED,
                caseResult(login, StepStatus.PASS, StepStatus.FAIL),
                caseResult(watch, StepStatus.PASS, StepStatus.PASS, StepStatus.BLOCKED),
            ),
        )
        val counts = reportFilterCounts(run)
        assertEquals(TestRunReportFilter.entries.toSet(), counts.keys)
        assertEquals(1, counts[TestRunReportFilter.FAILURES])
        assertEquals(1, counts[TestRunReportFilter.BLOCKED_OR_ERROR])
        assertEquals(0, counts[TestRunReportFilter.DISAGREEMENTS])
        assertEquals(0, counts[TestRunReportFilter.UNRESOLVED_JUDGING])
    }

    @Test
    fun aRowThatTwoLanesFailCountsOnceAndLaneDisagreementIsCounted() {
        val run = run(
            RunStatus.FAILED, listOf(login),
            lane(agent, RunStatus.FAILED, caseResult(login, StepStatus.PASS, StepStatus.FAIL)),
            lane(external, RunStatus.FAILED, caseResult(login, StepStatus.PASS, StepStatus.PASS)),
        )
        val counts = reportFilterCounts(run)
        assertEquals(1, counts[TestRunReportFilter.FAILURES], "one row, even though only one lane failed it")
        assertEquals(1, counts[TestRunReportFilter.DISAGREEMENTS])
    }

    // ── Selection ───────────────────────────────────────────────────

    @Test
    fun arrowKeysMoveThroughTheRowsThatHaveAResultAndStopAtTheEnds() {
        val run = run(
            RunStatus.RUNNING, listOf(login),
            lane(agent, RunStatus.RUNNING, caseResult(login, StepStatus.PASS)),
            lane(external, RunStatus.RUNNING, caseResult(login, StepStatus.PASS, StepStatus.PASS)),
        )
        val rows = buildMatrix(run)
        val first = assertNotNull(reportSelectionMovedBy(rows, null, +1))
        assertEquals(open.id, rows.filterIsInstance<MatrixRow.Step>().first { it.key == first.rowKey }.stepId)
        val last = assertNotNull(reportSelectionMovedBy(rows, null, -1))
        assertEquals(tap.id, rows.filterIsInstance<MatrixRow.Step>().first { it.key == last.rowKey }.stepId)
        assertEquals("lane-b", last.laneId, "only lane B has a result for the second step")
        assertEquals(last, reportSelectionMovedBy(rows, last, +1), "the end stays selected")
        val back = assertNotNull(reportSelectionMovedBy(rows, last, -1))
        assertEquals(first.rowKey, back.rowKey)
        assertNull(reportSelectionMovedBy(emptyList(), null, 1))
    }

    @Test
    fun theTabsAreLabelledAndTheActivityCountAppearsWhenThereIsActivity() {
        assertEquals(listOf("Results", "Compare", "Activity", "Usage"), RunReportTab.entries.map { runReportTabLabel(it, 0) })
        assertEquals("Activity 37", runReportTabLabel(RunReportTab.Activity, 37))
        assertEquals("Usage", runReportTabLabel(RunReportTab.Usage, 37))
    }

    // ── Compare ─────────────────────────────────────────────────────

    @Test
    fun friendlyStatusesDropTheLaneKeys() {
        assertEquals("Pass", friendlyStatuses("EXTERNAL//pixel-8/i1:PASS"))
        assertEquals("Pass, Fail", friendlyStatuses("AGENT_PROFILE/p1/emu/i1:PASS, EXTERNAL//pixel-8/i1:FAIL"))
        assertEquals("Fail", friendlyStatuses("A/p/e/i1:FAIL, B/p/f/i1:FAIL"))
        assertEquals("Not run", friendlyStatuses("Not run"))
    }

    private fun compared(
        caseName: String = "Login",
        stepId: String = "step-abc",
        presence: ComparedStepPresence = ComparedStepPresence.BOTH,
        definitionChanged: Boolean = false,
        previousAction: String? = "Tap Sign in",
        currentAction: String? = "Tap Sign in",
        previousStatuses: String = "X//e/i1:PASS",
        currentStatuses: String = "X//e/i1:FAIL",
        casePresence: ComparedCasePresence = ComparedCasePresence.BOTH,
    ) = ComparedRunStep(
        caseId = "case-1", caseName = caseName, stepId = stepId, presence = presence, definitionChanged = definitionChanged,
        previousAction = previousAction, currentAction = currentAction, previousExpected = "Done", currentExpected = "Done",
        previousStatuses = previousStatuses, currentStatuses = currentStatuses, casePresence = casePresence,
    )

    @Test
    fun compareLinesShowTheStepActionNeverItsId() {
        val lines = compareLines(
            listOf(
                compared(),
                compared(presence = ComparedStepPresence.ADDED, previousAction = null, previousStatuses = "Not run"),
                compared(presence = ComparedStepPresence.REMOVED, currentAction = null, currentStatuses = "Not run"),
                compared(definitionChanged = true, previousAction = "Tap Log in", currentAction = "Tap Sign in"),
            ),
        )
        assertEquals("Result changed", lines[0].change)
        assertEquals("Login · “Tap Sign in”", lines[0].subject)
        assertEquals("Pass", lines[0].before)
        assertEquals("Fail", lines[0].now)
        assertEquals("Step added", lines[1].change)
        assertEquals("Not in that run", lines[1].before)
        assertEquals("Step removed", lines[2].change)
        assertEquals("Login · “Tap Sign in”", lines[2].subject)
        assertEquals("Not in this run", lines[2].now)
        assertEquals("Step changed", lines[3].change)
        assertEquals("Action: “Tap Log in” → “Tap Sign in”", lines[3].detail)
        assertTrue(lines.none { line -> "step-abc" in line.subject + line.change + line.before + line.now + line.detail.orEmpty() })
    }

    @Test
    fun compareLinesNameCaseAndSuiteRows() {
        val case = compared(stepId = "", casePresence = ComparedCasePresence.ADDED, previousAction = null, previousStatuses = "Not run")
        assertEquals("Case added", compareLines(listOf(case)).single().change)
        assertEquals("Login", compareLines(listOf(case)).single().subject)
        assertEquals("Case removed", compareLines(listOf(compared(stepId = "", casePresence = ComparedCasePresence.REMOVED))).single().change)
    }

    @Test
    fun comparingTwoRealRunsYieldsReadableLines() {
        val before = run(RunStatus.PASSED, listOf(login), lane(agent, RunStatus.PASSED, caseResult(login, StepStatus.PASS, StepStatus.PASS)))
        val afterCase = login.copy(steps = login.steps + swipe)
        val after = before.copy(
            id = "run-2", suite = before.suite.copy(cases = listOf(afterCase)),
            lanes = listOf(lane(agent, RunStatus.FAILED, caseResult(afterCase, StepStatus.PASS, StepStatus.PASS, StepStatus.FAIL))),
        )
        val lines = compareLines(compareTestRuns(before, after))
        assertTrue(lines.isNotEmpty())
        val added = lines.first { it.change == "Step added" }
        assertEquals("Login · “Swipe up on the watch page”", added.subject)
        assertFalse(lines.any { swipe.id in it.subject })
    }

    // ── Activity ────────────────────────────────────────────────────

    @Test
    fun activityContextNamesTheCaseAndStepNumber() {
        val run = run(RunStatus.PASSED, listOf(login), lane(agent, RunStatus.PASSED))
        assertEquals("Login · step 2", activityContext(run, login.id, tap.id, 1, 1))
        assertEquals("Login · step 1 · run 2 · attempt 3", activityContext(run, login.id, open.id, 2, 3))
        assertEquals("Suite setup · a hook step", activityContext(run, com.indagium.testing.model.SUITE_SETUP_CASE_ID, "step-hook", 1, 1))
        assertEquals("", activityContext(run, "", "", 1, 1))
    }
}
