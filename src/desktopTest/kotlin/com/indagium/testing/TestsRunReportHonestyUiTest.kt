@file:Suppress("MagicNumber", "ktlint:standard:max-line-length", "MaxLineLength")

package com.indagium.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import com.indagium.testing.model.CaseAttemptRecord
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
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
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.newRunId
import com.indagium.testing.store.TestRunStore
import com.indagium.ui.AiWorkProgress
import com.indagium.ui.AppState
import com.indagium.ui.TestsNav
import com.indagium.ui.TestsWorkspace
import com.indagium.ui.aiProgressTitle
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The run report for a run that was retried, cancelled, timed out or disputed (the attempt switcher, the cancelled chip, the re-run
 * label, the disagreement marker), and the progress block of the AI authoring actions.
 */
@OptIn(ExperimentalTestApi::class)
class TestsRunReportHonestyUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val dir: File = createTempDirectory("tests-run-honesty-ui").toFile()
    private val state = AppState(
        autosaveFile = File(dir, "state.cache"),
        autoExportNotes = false,
        notesDir = File(dir, "notes"),
        archiveCacheDir = File(dir, "archive"),
        customCommandsDir = File(dir, "commands"),
        controlTokenFile = File(dir, "token"),
        sourceIndexFile = File(dir, "source-index"),
        testingDir = File(dir, "testing"),
    )

    @After
    fun tearDown() {
        state.close()
        dir.deleteRecursively()
    }

    private val open = step("Open the app")
    private val tap = step("Tap the video")
    private val feed = step("Check the feed")
    private val case = caseOf("Watch", open, tap, feed)
    private val suite = suiteOf(case)
    private val lane = LaneConfig("lane-a", LaneKind.EXTERNAL, deviceSerial = "pixel-8")

    private fun result(
        definition: TestStep,
        number: Int,
        status: StepStatus,
        claim: String? = null,
        judge: JudgeVerdict? = null,
        interrupted: Boolean = false,
        caseAttempt: Int = 1,
        executionStatus: StepStatus = status,
    ) = StepResult(
        definition.id, number, definition.action, status = status, agentClaim = claim, interrupted = interrupted, caseId = case.id,
        caseAttempt = caseAttempt, executionStatus = executionStatus, observation = "observation of attempt $caseAttempt step $number",
        judge = judge?.let { StepJudgement(verdict = it, reasoning = "r") },
    )

    private fun save(runStatus: RunStatus, caseResult: CaseResult): TestRun {
        val now = System.currentTimeMillis() - 60_000L
        val run = TestRun(
            id = newRunId(), suite = suite, scripts = emptyList(), sharedSteps = emptyList(),
            config = RunConfig(suite.id, null, listOf(lane)),
            lanes = listOf(LaneResult(lane.id, lane, runStatus, listOf(caseResult), startedAt = now)),
            status = runStatus, createdAt = now, startedAt = now, finishedAt = now + 50_000L,
        )
        TestRunStore { state.activeTestFolders().second }.save(run).getOrThrow()
        return run
    }

    private fun openReport(run: TestRun) {
        state.testsView.nav = TestsNav.Runs
        state.testsView.selectedRunId = run.id
        rule.setContent { Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(1400.dp, 800.dp)) { TestsWorkspace(state) } }
        rule.waitUntilAtLeastOneExists(hasContentDescription("Back to runs"), timeoutMillis = 10_000)
        rule.waitUntilAtLeastOneExists(hasContentDescription("More report actions"), timeoutMillis = 10_000)
        rule.waitForIdle()
    }

    /** Attempt 1 failed (steps 2 and 3 by the judge); attempt 2 passed step 1 and was cancelled in step 2. */
    private fun cancelledDuringRetry(): TestRun {
        val first = CaseAttemptRecord(
            case.id, 1, 1, CaseStatus.FAIL,
            listOf(
                result(open, 1, StepStatus.PASS, "pass", JudgeVerdict.PASS),
                result(tap, 2, StepStatus.FAIL, "pass", JudgeVerdict.FAIL, executionStatus = StepStatus.PASS),
                result(feed, 3, StepStatus.FAIL, "pass", JudgeVerdict.FAIL, executionStatus = StepStatus.PASS),
            ),
        )
        return save(
            RunStatus.CANCELLED,
            CaseResult(
                case.id, case.name, 1, CaseStatus.CANCELLED,
                listOf(result(open, 1, StepStatus.PASS, "pass", caseAttempt = 2), result(tap, 2, StepStatus.ERROR, interrupted = true, caseAttempt = 2)),
                caseAttempt = 2, priorAttempts = listOf(first),
            ),
        )
    }

    @Test
    fun aCancelledRunShowsACancelledStepAnInterruptedRerunAndTheStoryInTheVerdict() {
        openReport(cancelledDuringRetry())

        rule.onNodeWithText("Cancelled — stopped during attempt 2 at step 2; attempt 1 failed at steps 2 and 3 (judge)").assertExists()
        rule.onNodeWithText("Re-run interrupted (1)").assertExists()
        assertTrue(rule.onAllNodesWithText("Re-run failed", substring = true).fetchSemanticsNodes().isEmpty())
        // The interrupted step is the one selected, and it is neither red "Error" nor "Fail".
        rule.onNodeWithText("Step 2 · Watch").assertExists()
        assertTrue(rule.onAllNodesWithText("Error").fetchSemanticsNodes().isEmpty(), "no step is shown as an error")
        assertTrue(rule.onAllNodesWithText("Cancelled", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun theAttemptSwitcherShowsAnEarlierAttemptsStepsAndTheirDetail() {
        openReport(cancelledDuringRetry())

        rule.onNodeWithText("Attempt 2 of 2").assertExists()
        // The latest attempt: step 3 never ran.
        assertTrue(rule.onAllNodesWithText("Fail").fetchSemanticsNodes().isEmpty())

        rule.onNodeWithText("Attempt 2 of 2").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Attempt 1 · Fail").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Attempt 1 of 2").assertExists()
        assertTrue(rule.onAllNodesWithText("Fail").fetchSemanticsNodes().size >= 2, "attempt 1's two judged failures are in the matrix")
        rule.onNodeWithText("2. Tap the video").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("observation of attempt 1 step 2", substring = true).assertExists()
        // The attempt shown has no stopped step; the one the cancel interrupted is listed under the other attempts.
        rule.onNodeWithText("Other case attempts").assertExists()

        Thread.sleep(300) // a dropdown ignores a click right after it closed
        rule.onNodeWithText("Attempt 1 of 2").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Attempt 2 · Cancelled").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Attempt 2 of 2").assertExists()
    }

    @Test
    fun aStepWhereTheAgentAndTheJudgeDisagreeIsMarkedAndCounted() {
        val run = save(
            RunStatus.FAILED,
            CaseResult(
                case.id, case.name, 1, CaseStatus.FAIL,
                listOf(
                    result(open, 1, StepStatus.PASS, "pass", JudgeVerdict.PASS),
                    result(tap, 2, StepStatus.FAIL, "fail", JudgeVerdict.PASS),
                ),
            ),
        )
        openReport(run)

        rule.onNodeWithText("≠").assertExists()
        rule.onNodeWithContentDescription("Disagreements, 1").assertExists()
        rule.onNodeWithText("agent and judge disagree").assertExists()
        rule.onNodeWithContentDescription("Disagreements, 1").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("2. Tap the video").assertExists()
        rule.onNodeWithText("1. Open the app").assertDoesNotExist()
    }

    @Test
    fun aTimedOutStepShowsWhatTheJudgeThoughtInTheMatrixAndTheInspector() {
        val run = save(
            RunStatus.FAILED,
            CaseResult(case.id, case.name, 1, CaseStatus.FAIL, listOf(result(open, 1, StepStatus.PASS), result(tap, 2, StepStatus.TIMEOUT, judge = JudgeVerdict.PASS))),
        )
        openReport(run)

        rule.onNodeWithText("Timeout · met").assertExists()
        rule.onNodeWithText("Timeout · judge: expected result met").assertExists()
    }

    // ── The progress block of the AI authoring actions ──────────────

    @Test
    fun theProgressBlockNamesWhatWorksAndCountsTheTimeAndShowsTheStage() {
        var now = 1_000_000L
        rule.mainClock.autoAdvance = false
        rule.setContent { AiWorkProgress("Rewriting", "Claude Code", "haiku", "Waiting for the model…", clock = { now }) }
        rule.onNodeWithText("Rewriting with Claude Code · haiku · 0s").assertExists()
        rule.onNodeWithText("Waiting for the model…").assertExists()

        now += 72_000L
        rule.mainClock.advanceTimeBy(1_100L)
        rule.onNodeWithText("Rewriting with Claude Code · haiku · 1m 12s").assertExists()
    }

    @Test
    fun theProgressTitleLeavesOutWhatIsUnknown() {
        assertEquals("Drafting steps with Local · gpt · 5s", aiProgressTitle("Drafting steps", "Local", "gpt", 5_000L))
        assertEquals("Improving steps · 0s", aiProgressTitle("Improving steps", null, " ", 0L))
        assertEquals("Rewriting with Claude · 1m 12s", aiProgressTitle("Rewriting", "Claude", null, 72_000L))
    }
}
