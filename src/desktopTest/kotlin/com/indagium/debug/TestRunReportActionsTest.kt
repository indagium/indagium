package com.indagium.debug

import com.indagium.edition.Edition
import com.indagium.testing.FIXTURE_SERIAL
import com.indagium.testing.ScriptedAdbRunner
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.JudgeClassification
import com.indagium.testing.model.JudgeComparison
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepFix
import com.indagium.testing.model.StepJudgement
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.newRunId
import com.indagium.testing.openFixtureSession
import com.indagium.testing.run.EngineTuning
import com.indagium.testing.run.LaneDeviceOpener
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.decodeRunFile
import com.indagium.testing.store.encodeRunFile
import com.indagium.ui.AppState
import com.indagium.ui.TestRunOverrides
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val AWAIT_MS = 15_000L
private const val POLL_MS = 15L

/** apply_step_fix, mark_agent_error and rerun_test_step over the gateway: library changes, locks, persistence, new runs. */
class TestRunReportActionsTest {
    private lateinit var dir: File
    private lateinit var state: AppState
    private lateinit var operations: IndagiumToolOperations
    private val adb = ScriptedAdbRunner()

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("test-run-actions").toFile()
        state = AppState(
            autosaveFile = File(dir, "state.cache"),
            autoExportNotes = false,
            notesDir = File(dir, "notes"),
            archiveCacheDir = File(dir, "archive"),
            customCommandsDir = File(dir, "commands"),
            controlTokenFile = File(dir, "token"),
            sourceIndexFile = File(dir, "source-index"),
            testingDir = File(dir, "testing"),
        )
        state.testRunOverrides = TestRunOverrides(
            openDevice = LaneDeviceOpener { _, laneDir, _ -> openFixtureSession(adb, laneDir) },
            deviceProblem = { serial -> if (serial == FIXTURE_SERIAL) null else "Device $serial is not connected." },
            tuning = EngineTuning(persistDebounceMs = 50L),
        )
        operations = IndagiumToolOperations(state)
    }

    @AfterTest
    fun tearDown() {
        state.close()
        dir.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun call(tool: String, vararg args: Pair<String, Any?>): Map<String, Any?> = runBlocking {
        val wire = Json.decode(Json.encode(mapOf(*args))) as Map<String, Any?>
        Json.decode(Json.encode(operations.toolGateway.executeSuspending(tool, wire))) as Map<String, Any?>
    }

    private fun applyFix(runId: String, stepId: String, fixRef: String) =
        call("apply_step_fix", "runId" to runId, "stepId" to stepId, "fixRef" to fixRef)

    private fun markError(runId: String, laneId: String, caseId: String, stepId: String, note: String) =
        call("mark_agent_error", "runId" to runId, "laneId" to laneId, "caseId" to caseId, "stepId" to stepId, "note" to note)

    private fun rerun(runId: String, laneId: String, caseId: String, stepId: String) =
        call("rerun_test_step", "runId" to runId, "laneId" to laneId, "caseId" to caseId, "stepId" to stepId)

    private class Library(val suiteId: String, val caseId: String, val steps: List<TestStep>)

    private fun createSuite(name: String): Library {
        val suite = (state.createTestSuite(name) as StoreResult.Ok).value
        val case = (state.createTestCase(suite.id, TestCase("", "Sign in")) as StoreResult.Ok).value
        val steps = listOf("Open the app", "Sign in", "Look at the home screen").map { action ->
            (state.createTestStep(case.id, TestStep("", action, "It works", retries = 0)) as StoreResult.Ok).value
        }
        return Library(suite.id, case.id, steps)
    }

    private fun judgement(fix: StepFix?, verdict: JudgeVerdict = JudgeVerdict.FAIL) =
        StepJudgement(verdict = verdict, reasoning = "The screen differs.", classification = JudgeClassification.AGENT_OR_STEP_PROBLEM, suggestedFix = fix)

    /** A finished run of an earlier launch, written to disk: the library's suite with a judged result for [stepIndex] and one comparison. */
    private fun storeRun(library: Library, stepIndex: Int, fix: StepFix?, comparisonFix: StepFix? = null): Triple<String, String, String> {
        val suite = checkNotNull(state.testLibrary.suite(library.suiteId))
        val step = library.steps[stepIndex]
        val lane = LaneConfig(kind = LaneKind.EXTERNAL, deviceSerial = FIXTURE_SERIAL)
        val judged = judgement(fix)
        val result = StepResult(
            step.id, stepIndex + 1, step.action, step.expected, status = StepStatus.FAIL, agentClaim = "pass", observation = "ok", judge = judged,
        )
        val comparison = JudgeComparison(
            caseId = library.caseId, iteration = 1, stepId = step.id, stepNumber = stepIndex + 1, action = step.action,
            verdicts = mapOf(lane.id to JudgeVerdict.FAIL), explanation = "They differ.", suggestedFix = comparisonFix,
        )
        val run = TestRun(
            id = newRunId(), suite = suite, scripts = emptyList(), sharedSteps = emptyList(),
            config = RunConfig(suite.id, null, listOf(lane)),
            lanes = listOf(LaneResult(lane.id, lane, RunStatus.FAILED, listOf(CaseResult(library.caseId, "Sign in", 1, CaseStatus.FAIL, listOf(result))))),
            status = RunStatus.FAILED, createdAt = 1L, finishedAt = 2L, comparisons = listOf(comparison),
        )
        val file = File(dir, "testing/runs/${run.id}/run.json").also { it.parentFile.mkdirs() }
        file.writeText(encodeRunFile(run))
        return Triple(run.id, judged.id, comparison.id)
    }

    private fun stored(runId: String): TestRun = decodeRunFile(File(dir, "testing/runs/$runId/run.json").readText()).getOrThrow()

    private fun libraryStep(stepId: String): TestStep = checkNotNull(state.testLibrary.findStep(stepId)).step

    // ── apply_step_fix ──────────────────────────────────────────────

    @Test
    fun applyStepFixWritesTheSuggestedTextIntoTheLibraryStepAndMarksItAppliedOnce() {
        val library = createSuite("Fixable")
        val step = library.steps[1]
        val (runId, verdictId, _) = storeRun(library, 1, StepFix(action = "Sign in with the test account", expected = null, note = "Say which account."))

        val applied = applyFix(runId, step.id, verdictId)
        assertEquals(null, applied["error"], applied.toString())
        assertEquals(true, applied["applied"])
        assertEquals("Sign in with the test account", libraryStep(step.id).action)
        assertEquals("It works", libraryStep(step.id).expected, "a part the fix does not give stays as it was")
        val judge = assertNotNull(stored(runId).lanes.single().cases.single().steps.single().judge)
        assertTrue(judge.fixApplied, "the run remembers the fix was applied")
        assertEquals("Sign in", stored(runId).suite.cases.single().steps[1].action, "the run's own frozen suite is not changed")

        val again = applyFix(runId, step.id, verdictId)
        assertTrue(again["error"].toString().contains("already applied"), again.toString())
    }

    @Test
    fun aComparisonsFixIsAppliedByItsIdAndBadReferencesAreRefusedAsData() {
        val library = createSuite("Comparable")
        val step = library.steps[0]
        val (runId, verdictId, comparisonId) = storeRun(library, 0, fix = null, comparisonFix = StepFix(expected = "The splash screen is shown first"))

        assertTrue(applyFix(runId, step.id, "jdg-nope")["error"].toString().contains("no suggested fix"))
        assertTrue(applyFix(runId, step.id, verdictId)["error"].toString().contains("no suggested fix"), "a verdict without a fix")
        assertTrue(applyFix("run-nope", step.id, comparisonId)["error"].toString().contains("not found"))
        assertTrue(applyFix(runId, library.steps[1].id, comparisonId)["error"].toString().contains("about step"))
        assertEquals("It works", libraryStep(step.id).expected, "nothing changed so far")

        val applied = applyFix(runId, step.id, comparisonId)
        assertEquals(null, applied["error"], applied.toString())
        assertEquals("The splash screen is shown first", libraryStep(step.id).expected)
        assertTrue(stored(runId).comparisons.single().fixApplied)
    }

    @Test
    fun anAdviceOnlyFixHasNoReplacementTextAndIsRefused() {
        val library = createSuite("Advice")
        val step = library.steps[0]
        val (runId, verdictId, _) = storeRun(library, 0, StepFix(note = "Consider asking for a screenshot of the title."))

        val refused = applyFix(runId, step.id, verdictId)
        assertTrue(refused["error"].toString().contains("advice only"), refused.toString())
        assertEquals("Open the app", libraryStep(step.id).action)
    }

    @Test
    fun applyStepFixRespectsTheEditionLocksAndKeepsTheLibraryUnchanged() {
        val first = createSuite("Active suite")
        val second = createSuite("Locked suite")
        state.editionService.setForDev(Edition.FREE)
        val step = second.steps[0]
        val (runId, verdictId, _) = storeRun(second, 0, StepFix(action = "Open the app twice"))

        val refused = applyFix(runId, step.id, verdictId)
        assertTrue(refused["error"].toString().contains("locked"), refused.toString())
        @Suppress("UNCHECKED_CAST")
        val limit = refused["limit"] as Map<String, Any?>
        assertEquals("LOCKED", limit["kind"])
        assertEquals("FREE", limit["edition"])
        assertEquals("Open the app", libraryStep(step.id).action)
        assertFalse(stored(runId).lanes.single().cases.single().steps.single().judge!!.fixApplied)

        val (activeRun, activeVerdict, _) = storeRun(first, 0, StepFix(action = "Open the app twice"))
        assertEquals(null, applyFix(activeRun, first.steps[0].id, activeVerdict)["error"])
    }

    // ── mark_agent_error ────────────────────────────────────────────

    @Test
    fun markAgentErrorIsPersistedOnTheStepResultAndShownInTheReport() {
        val library = createSuite("Marked")
        val step = library.steps[2]
        val (runId, _, _) = storeRun(library, 2, null)
        val laneId = stored(runId).lanes.single().laneId

        val marked = markError(runId, laneId, library.caseId, step.id, "  It tapped the wrong tab.  ")
        assertEquals(null, marked["error"], marked.toString())
        assertEquals("It tapped the wrong tab.", stored(runId).lanes.single().cases.single().steps.single().agentError)

        val markdown = call("get_test_run_report", "runId" to runId, "format" to "markdown")["markdown"] as String
        assertTrue(markdown.contains("Marked as an agent error: It tapped the wrong tab."), markdown)
        @Suppress("UNCHECKED_CAST")
        val report = call("get_test_run_report", "runId" to runId)["report"] as Map<String, Any?>
        assertTrue(report.toString().contains("agentError"), report.toString())

        assertTrue(markError(runId, laneId, library.caseId, "step-nope", "x")["error"].toString().contains("no result"))
        assertTrue(markError(runId, laneId, library.caseId, step.id, "  ")["error"].toString().contains("note is required"))
        assertTrue(markError("run-nope", laneId, library.caseId, step.id, "x")["error"].toString().contains("not found"))
    }

    // ── rerun_test_step ─────────────────────────────────────────────

    @Test
    fun rerunTestStepStartsANewRunOfTheCaseUpToAndIncludingThatStepOnTheSameLaneSetup() {
        val library = createSuite("Rerunnable")
        val (originalId, _, _) = storeRun(library, 1, null)
        val original = stored(originalId)

        val started = rerun(originalId, original.lanes.single().laneId, library.caseId, library.steps[1].id)
        assertEquals(null, started["error"], started.toString())
        val newId = started["runId"] as String
        val run = assertNotNull(state.testRunCoordinator.run(newId))
        assertTrue(newId != originalId)
        assertEquals(originalId, run.config.rerunOf)
        assertEquals(library.steps[1].id, run.config.stopAfterStepId)
        assertEquals(listOf(library.caseId), run.config.caseIds)
        assertEquals(listOf("Open the app", "Sign in"), run.suite.cases.single().steps.map { it.action }, "the steps after the chosen one are not run")
        assertEquals(FIXTURE_SERIAL, run.lanes.single().config.deviceSerial)
        assertTrue(run.lanes.single().laneId != original.lanes.single().laneId, "a new lane id")
        assertEquals(3, state.testLibrary.findCase(library.caseId)!!.case.steps.size, "the library still has all steps")

        runBlocking {
            withTimeout(AWAIT_MS) { while (state.testRunCoordinator.run(newId)?.lanes?.single()?.currentStepNumber != 1) delay(POLL_MS) }
        }
        val busy = rerun(originalId, original.lanes.single().laneId, library.caseId, library.steps[0].id)
        assertTrue(busy["error"].toString().contains("already used"), "the device is busy: $busy")
        call("cancel_test_run", "runId" to newId)
        runBlocking { withTimeout(AWAIT_MS) { assertNotNull(state.testRunCoordinator.awaitFinished(newId)) } }
    }

    @Test
    fun rerunTestStepRefusesAnUnknownRunLaneCaseOrStepAndAStepThatIsGone() {
        val library = createSuite("Refusals")
        val (runId, _, _) = storeRun(library, 0, null)
        val laneId = stored(runId).lanes.single().laneId
        assertTrue(rerun("run-nope", laneId, library.caseId, library.steps[0].id)["error"].toString().contains("not found"))
        assertTrue(rerun(runId, "lane-x", library.caseId, library.steps[0].id)["error"].toString().contains("no lane"))
        assertTrue(rerun(runId, laneId, "case-x", library.steps[0].id)["error"].toString().contains("no case"))
        assertTrue(rerun(runId, laneId, library.caseId, "step-x")["error"].toString().contains("no step"))

        state.deleteTestStep(library.steps[2].id)
        val gone = rerun(runId, laneId, library.caseId, library.steps[2].id)
        assertTrue(gone["error"].toString().isNotBlank(), "a step deleted since the run cannot be re-run: $gone")
        assertTrue(state.testRunCoordinator.runsFlow.value.isEmpty())
    }

    // ── Policy ──────────────────────────────────────────────────────

    @Test
    fun theActionsAreConfirmationGatedAndRerunNeedsPerCallApprovalFromExternalClients() {
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, operations.toolGateway.actionPolicy("apply_step_fix"))
        assertEquals(IndagiumToolActionPolicy.CONFIRMATION_REQUIRED, operations.toolGateway.actionPolicy("rerun_test_step"))
        assertEquals(IndagiumToolActionPolicy.AUTOMATIC, operations.toolGateway.actionPolicy("mark_agent_error"))
        assertTrue("rerun_test_step" in PER_CALL_APPROVAL_MCP_TOOLS)
        assertFalse("apply_step_fix" in PER_CALL_APPROVAL_MCP_TOOLS, "it only edits the library, like update_test_step")
    }
}
