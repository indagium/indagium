package com.indagium.debug

import com.indagium.model.AiRunTiming
import com.indagium.testing.agentLane
import com.indagium.testing.caseOf
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepJudgement
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.step
import com.indagium.testing.store.encodeRunFile
import com.indagium.testing.suiteOf
import com.indagium.ui.AppState
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val RUN_ID = "run-judge-timing"

/** get_test_run_status and get_test_run_report show how long the judge took for each judged step. */
class TestRunJudgeTimingToolsTest {
    private lateinit var dir: File
    private lateinit var state: AppState
    private lateinit var operations: IndagiumToolOperations

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("judge-timing-tools").toFile()
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

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.list(key: String): List<Map<String, Any?>> = this[key] as List<Map<String, Any?>>

    private fun stepResult(number: Int, judge: StepJudgement?) =
        StepResult(stepId = "step-$number", stepNumber = number, action = "Step $number", status = StepStatus.PASS, judge = judge)

    /** Three steps: one judged with timing, one judged by a run from before timing existed, one not judged at all. */
    private fun storeRun() {
        val suite = suiteOf(caseOf("Login", step("Step 1"), step("Step 2"), step("Step 3")))
        val timing = AiRunTiming(startupMs = 1_900L, firstResponseMs = 2_800L, modelTurns = 2, toolCalls = 3, verdictMs = 4_000L, totalMs = 4_200L)
        val steps = listOf(
            stepResult(1, StepJudgement(verdict = JudgeVerdict.PASS, durationMs = 4_200L, timing = timing)),
            stepResult(2, StepJudgement(verdict = JudgeVerdict.PASS, durationMs = 9_000L)),
            stepResult(3, null),
        )
        val lane = LaneResult(
            "lane-1", agentLane(), status = RunStatus.PASSED,
            cases = listOf(CaseResult(suite.cases.single().id, "Login", status = CaseStatus.PASS, steps = steps)),
        )
        val run = TestRun(RUN_ID, suite, emptyList(), emptyList(), RunConfig(suite.id, null, listOf(agentLane())), listOf(lane), status = RunStatus.PASSED)
        val file = File(dir, "testing/runs/$RUN_ID/run.json")
        file.parentFile.mkdirs()
        file.writeText(encodeRunFile(run))
    }

    @Test
    fun theStatusListsTheJudgeTimeOfEachJudgedStepThatRecordedIt() {
        storeRun()

        val status = call("get_test_run_status", "runId" to RUN_ID)

        val case = status.list("lanes").single().list("cases").single()
        val judged = case.list("judgedSteps").single()
        assertEquals(1, judged["stepNumber"])
        assertEquals(4_200, judged["judgeMs"])
        assertEquals(2, judged["judgeModelTurns"])
        assertEquals(listOf("PASS", "PASS", "PASS"), case["steps"], "the steps list keeps its shape")
    }

    @Test
    fun theJsonReportCarriesJudgeMsAndTurnsOnTheStepAndTheWholeBreakdownOnItsJudge() {
        storeRun()

        val report = call("get_test_run_report", "runId" to RUN_ID)

        @Suppress("UNCHECKED_CAST")
        val steps = ((report["report"] as Map<String, Any?>).list("lanes").single().list("cases").single())["steps"] as List<Map<String, Any?>>
        assertEquals(4_200, steps[0]["judgeMs"])
        assertEquals(2, steps[0]["judgeModelTurns"])
        @Suppress("UNCHECKED_CAST")
        val breakdown = (steps[0]["judge"] as Map<String, Any?>)["timing"] as Map<String, Any?>
        assertEquals(1_900, breakdown["startupMs"])
        assertEquals(4_000, breakdown["verdictMs"])
        assertFalse(steps[1].containsKey("judgeMs"), "a judge from an older run has no timing to show")
        assertFalse(steps[2].containsKey("judgeMs"))
    }

    @Test
    fun theMarkdownReportShowsTheJudgeTime() {
        storeRun()

        val markdown = call("get_test_run_report", "runId" to RUN_ID, "format" to "markdown")["markdown"] as String

        assertTrue(markdown.contains("Judge time: 4.2 s (2 model turns)"), markdown)
    }
}
