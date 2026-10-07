package com.indagium.testing

import com.indagium.capture.CaptureDevice
import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.CheckResult
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.EvidenceFlags
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.SUITE_SETUP_CASE_ID
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.ui.LaneChoice
import com.indagium.ui.MatrixRow
import com.indagium.ui.RunDialogModel
import com.indagium.ui.TestRunReportFilter
import com.indagium.ui.buildMatrix
import com.indagium.ui.deviceChoices
import com.indagium.ui.filterReportRows
import com.indagium.ui.laneChoices
import com.indagium.ui.progressLine
import com.indagium.ui.readLogExcerpt
import com.indagium.ui.reportMetrics
import com.indagium.ui.toConfig
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestRunUiStateTest {
    private val profiles = listOf(
        AiProviderProfile("p1", "My Claude", "", "", kind = AiProviderKind.CLAUDE_CODE_ACCOUNT),
        AiProviderProfile("p2", "", "http://localhost:1234", "m", kind = AiProviderKind.OPENAI_COMPATIBLE),
    )

    // ── Dialog ──────────────────────────────────────────────────────

    @Test
    fun theLaneChoicesAreTheProfilesThenExternal() {
        val choices = laneChoices(profiles)
        assertEquals(listOf("p1", "p2", null), choices.map { it.profileId })
        assertTrue(choices.first().label.contains("My Claude") && choices.first().label.contains("Claude Code"))
        assertTrue(choices[1].label.startsWith("OpenAI-compatible"), "a blank name falls back to the kind")
        assertTrue(choices.last().isExternal)
    }

    @Test
    fun theLiveCaptureDeviceAndUnreadyDevicesAreNotOffered() {
        val devices = listOf(
            CaptureDevice("SER-A", "device", "Pixel 8"),
            CaptureDevice("SER-LIVE", "device", "Pixel 7"),
            CaptureDevice("SER-OFF", "offline"),
            CaptureDevice("SER-B", "device"),
        )
        val offered = deviceChoices(devices, liveCaptureSerial = "SER-LIVE")
        assertEquals(listOf("SER-A", "SER-B"), offered.map { it.serial })
        assertEquals("Pixel 8 (SER-A)", offered.first().label)
        assertEquals("SER-B", offered.last().label)
        assertEquals(3, deviceChoices(devices, null).size)
    }

    @Test
    fun theDialogModelBecomesARunConfigOrTheFirstProblem() {
        val all = listOf("case-1", "case-2", "case-3")
        val agent = LaneChoice("p1", "My Claude")
        val everything = RunDialogModel("suite-1", all.toSet(), agent, "SER-A", 3, " 25 ", EvidenceFlags(video = true))
        val ok = everything.toConfig(all).getOrThrow()
        assertNull(ok.caseIds, "every case selected means no filter")
        assertEquals(LaneKind.AGENT_PROFILE, ok.lanes.single().kind)
        assertEquals("p1", ok.lanes.single().profileId)
        assertEquals("SER-A", ok.lanes.single().deviceSerial)
        assertEquals(3, ok.repeat)
        assertEquals(25, ok.caseToolCallLimit)
        assertTrue(ok.evidence.video)

        val some = RunDialogModel("suite-1", setOf("case-3", "case-1"), LaneChoice(null, "External (MCP)"), "SER-A").toConfig(all).getOrThrow()
        assertEquals(listOf("case-1", "case-3"), some.caseIds, "suite order, not selection order")
        assertEquals(LaneKind.EXTERNAL, some.lanes.single().kind)
        assertNull(some.lanes.single().profileId)
        val staleSelection = RunDialogModel("suite-1", setOf("case-1", "deleted-case"), agent, "SER-A").toConfig(all)
        assertTrue(staleSelection.exceptionOrNull()?.message.orEmpty().contains("no longer exist"))

        fun problem(model: RunDialogModel) = model.toConfig(all).exceptionOrNull()?.message.orEmpty()
        val base = RunDialogModel("suite-1", setOf("case-1"), agent, "SER-A")
        assertTrue(problem(base.copy(selectedCaseIds = emptySet())).contains("at least one case"))
        assertTrue(problem(base.copy(choice = null)).contains("drives the lane"))
        assertTrue(problem(base.copy(deviceSerial = null)).contains("device"))
        assertTrue(problem(base.copy(repeat = 2)).contains("Repeat"))
        assertTrue(problem(base.copy(toolLimitText = "many")).contains("tool-call limit"))
        assertTrue(problem(base.copy(toolLimitText = "0")).contains("tool-call limit"))
    }

    // ── Matrix ──────────────────────────────────────────────────────

    private fun result(stepId: String, number: Int, status: StepStatus, setup: Boolean = false) =
        StepResult(stepId, number, "action $number", status = status, setup = setup)

    private fun run(): TestRun {
        val first = step("Open")
        val second = step("Close")
        val case = caseOf("Case A", first, second)
        val suite = suiteOf(case)
        val laneA = LaneConfig(kind = LaneKind.AGENT_PROFILE, profileId = "p1", deviceSerial = "SER-A")
        val laneB = LaneConfig(kind = LaneKind.EXTERNAL, deviceSerial = "SER-B")
        return TestRun(
            id = "run-1", suite = suite, scripts = emptyList(), sharedSteps = emptyList(),
            config = RunConfig(suite.id, null, listOf(laneA, laneB), repeat = 1),
            lanes = listOf(
                LaneResult(
                    laneA.id, laneA, RunStatus.FAILED,
                    listOf(
                        CaseResult(SUITE_SETUP_CASE_ID, "Suite setup", 1, CaseStatus.PASS, listOf(result("hook-1", 1, StepStatus.PASS, setup = true))),
                        CaseResult(case.id, case.name, 1, CaseStatus.FAIL, listOf(result(first.id, 1, StepStatus.PASS), result(second.id, 2, StepStatus.FAIL))),
                    ),
                ),
                LaneResult(laneB.id, laneB, RunStatus.RUNNING, listOf(CaseResult(case.id, case.name, 1, null, listOf(result(first.id, 1, StepStatus.PASS)))),
                    currentCase = "Case A", currentStepNumber = 2, currentStepAction = "Close"),
            ),
        )
    }

    @Test
    fun theMatrixHasOneColumnPerLaneAndShowsPendingCellsForALiveRun() {
        val run = run()
        val rows = buildMatrix(run)

        assertEquals(listOf("Suite setup", "Case A"), rows.filterIsInstance<MatrixRow.Case>().map { it.name })
        val steps = rows.filterIsInstance<MatrixRow.Step>()
        assertEquals(listOf(true, false, false), steps.map { it.setup })
        val open = steps[1]
        assertEquals(listOf(StepStatus.PASS, StepStatus.PASS), open.cells.map { it.result?.status })
        val close = steps[2]
        assertEquals(listOf(StepStatus.FAIL, null), close.cells.map { it.result?.status }, "lane B has not reached the step yet")
        val caseRow = rows.filterIsInstance<MatrixRow.Case>().last()
        assertEquals(listOf(CaseStatus.FAIL, null), caseRow.cells.map { it.status })
        assertEquals(listOf(true, true), caseRow.cells.map { it.present })
    }

    @Test
    fun repeatedCasesGetARowPerIterationThatAnyLaneReached() {
        val base = run()
        val case = base.suite.cases.single()
        val twice = base.copy(
            config = base.config.copy(repeat = 3),
            lanes = base.lanes.map { lane ->
                if (lane.laneId == base.lanes.first().laneId) lane.copy(cases = lane.cases + CaseResult(case.id, case.name, 2, CaseStatus.PASS)) else lane
            },
        )
        val iterations = buildMatrix(twice).filterIsInstance<MatrixRow.Case>().filter { it.caseId == case.id }.map { it.iteration }
        assertEquals(listOf(1, 2), iterations, "iteration 3 was not reached by any lane")
    }

    @Test
    fun reportFiltersKeepCaseContextAndCountCrossLaneDisagreementAndUnresolvedChecks() {
        val base = run()
        val testCase = base.suite.cases.single()
        val failingDefinition = testCase.steps.last()
        val laneA = base.lanes.first().copy(cases = base.lanes.first().cases.map { case ->
            if (case.caseId != testCase.id) case else case.copy(steps = case.steps.map { step ->
                if (step.stepId == failingDefinition.id) step.copy(
                    checks = listOf(CheckResult("ask-judge", "askJudge", CheckStatus.NOT_EVALUATED, "Judge could not decide.")),
                ) else step
            })
        })
        val laneB = base.lanes.last().copy(cases = base.lanes.last().cases.map { case ->
            if (case.caseId != testCase.id) case else CaseResult(
                testCase.id,
                testCase.name,
                iteration = 1,
                status = CaseStatus.PASS,
                steps = listOf(result(testCase.steps.first().id, 1, StepStatus.PASS), result(failingDefinition.id, 2, StepStatus.PASS)),
            )
        })
        val run = base.copy(lanes = listOf(laneA, laneB))
        val failures = filterReportRows(run, setOf(TestRunReportFilter.FAILURES))
        val failedSteps = failures.filterIsInstance<MatrixRow.Step>()
        assertEquals(listOf(failingDefinition.id), failedSteps.map { it.stepId })
        assertTrue(failures.any { it is MatrixRow.Case && it.caseId == testCase.id }, "filtered steps retain their case header")
        val disagreements = filterReportRows(run, setOf(TestRunReportFilter.DISAGREEMENTS)).filterIsInstance<MatrixRow.Step>()
        assertEquals(listOf(failingDefinition.id), disagreements.map { it.stepId })
        val unresolved = filterReportRows(run, setOf(TestRunReportFilter.UNRESOLVED_JUDGING)).filterIsInstance<MatrixRow.Step>()
        assertEquals(listOf(failingDefinition.id), unresolved.map { it.stepId }, "NOT_EVALUATED remains visible alongside deterministic FAIL")
        val metrics = reportMetrics(run)
        assertEquals(1, metrics.disagreements)
        assertEquals(1, metrics.unresolvedJudging)
        val deterministicOnlyLane = laneA.copy(cases = laneA.cases.map { case ->
            if (case.caseId != testCase.id) case else case.copy(steps = case.steps.map { step ->
                if (step.stepId == failingDefinition.id) step.copy(
                    checks = listOf(CheckResult("log-check", "logAppears", CheckStatus.NOT_EVALUATED, "Deadline expired.")),
                ) else step
            })
        })
        val deterministicOnly = run.copy(lanes = listOf(deterministicOnlyLane, laneB))
        assertEquals(0, reportMetrics(deterministicOnly).unresolvedJudging)
        assertTrue(filterReportRows(deterministicOnly, setOf(TestRunReportFilter.UNRESOLVED_JUDGING)).isEmpty())
    }

    @Test
    fun theProgressLineNamesTheDeviceStatusAndPlaceWhileRunning() {
        val run = run()
        val live = run.lanes.last()
        assertEquals("SER-B · Running · Case A, step 2, “Close”", run.progressLine(live.laneId))
        assertEquals("SER-A · Failed", run.progressLine(run.lanes.first().laneId))
    }

    // ── Log excerpt ─────────────────────────────────────────────────

    @Test
    fun theLogExcerptIsTheStepsBytesCutAtTheLimitAndNullWithoutALog() {
        val dir = createTempDirectory("excerpt").toFile()
        try {
            val log = File(dir, "logcat.log").apply { writeText("0123456789".repeat(10)) }
            assertEquals("2345", readLogExcerpt(log, 2L, 6L))
            assertEquals("", readLogExcerpt(log, 6L, 6L))
            val cut = readLogExcerpt(log, 0L, 100L, maxBytes = 10)
            assertTrue(cut!!.startsWith("0123456789") && cut.contains("90 more bytes"), cut)
            assertNull(readLogExcerpt(log, null, 5L))
            assertNull(readLogExcerpt(log, 9L, 3L))
            assertNull(readLogExcerpt(File(dir, "missing.log"), 0L, 5L))
            assertEquals(100 - 95, readLogExcerpt(log, 95L, 500L)!!.length, "an end past the file is clamped")
        } finally {
            dir.deleteRecursively()
        }
    }
}
