@file:Suppress("MagicNumber", "ktlint:standard:max-line-length", "MaxLineLength")

package com.indagium.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import com.indagium.model.AiUsageStats
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.LaneToolCall
import com.indagium.testing.model.LaneToolCallStatus
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.newRunId
import com.indagium.testing.store.TestRunStore
import com.indagium.ui.AppState
import com.indagium.ui.RunReportTab
import com.indagium.ui.TestsNav
import com.indagium.ui.TestsWorkspace
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The run report across Workspace, Details and Evidence, composed against a real AppState and stored runs. */
@OptIn(ExperimentalTestApi::class)
class TestsRunReportUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val dir: File = createTempDirectory("tests-run-report-ui").toFile()
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
    private val tap = step("Tap Sign in")
    private val dashboard = step("Check dashboard")
    private val case: TestCase = caseOf("Login", open, tap, dashboard)
    private val suite: TestSuite = suiteOf(case)
    private val lane = LaneConfig("lane-a", LaneKind.EXTERNAL, deviceSerial = "pixel-8")

    private fun result(definition: TestStep, number: Int, status: StepStatus, observation: String = "") =
        StepResult(definition.id, number, definition.action, status = status, observation = observation)

    /** A saved run of [suite]; [statuses] are the step outcomes in order (the case status follows from them). */
    private fun saveRun(
        status: RunStatus,
        vararg statuses: StepStatus,
        createdAt: Long,
        agentUsage: AiUsageStats? = null,
        judgeUsage: AiUsageStats? = null,
        toolCalls: List<LaneToolCall> = emptyList(),
    ): TestRun {
        val caseStatus = when {
            StepStatus.FAIL in statuses -> CaseStatus.FAIL
            StepStatus.BLOCKED in statuses -> CaseStatus.BLOCKED
            else -> CaseStatus.PASS
        }
        val steps = case.steps.zip(statuses.toList()).mapIndexed { i, (def, st) ->
            result(def, i + 1, st, observation = if (st == StepStatus.FAIL) "The Sign in button was not found" else "")
        }
        val run = TestRun(
            id = newRunId(), suite = suite, scripts = emptyList(), sharedSteps = emptyList(),
            config = RunConfig(suite.id, null, listOf(lane)),
            lanes = listOf(
                LaneResult(
                    lane.id, lane, status,
                    listOf(CaseResult(case.id, case.name, 1, caseStatus, steps, startedAt = 1_000L, finishedAt = 49_000L, agentUsage = agentUsage, judgeUsage = judgeUsage)),
                    startedAt = 1_000L, toolCalls = toolCalls,
                ),
            ),
            status = status, createdAt = createdAt, startedAt = createdAt, finishedAt = createdAt + 50_000L,
        )
        TestRunStore { state.activeTestFolders().second }.save(run).getOrThrow()
        return run
    }

    private fun openReport(run: TestRun, tab: RunReportTab = RunReportTab.Results, width: Int = 1400) {
        state.testsView.nav = TestsNav.Runs
        state.testsView.selectedRunId = run.id
        state.testsView.reportTab = tab
        rule.setTestsViewport(width, 800)
        rule.setContent { Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(width.dp, 800.dp)) { TestsWorkspace(state) } }
        rule.waitUntilAtLeastOneExists(hasContentDescription("Back to runs"), timeoutMillis = 10_000)
        rule.waitUntilAtLeastOneExists(hasContentDescription("More report actions"), timeoutMillis = 10_000)
        rule.waitForIdle()
    }

    /** The nodes with [text] right of the nav (the nav tree carries some of the same words). */
    private fun centre(text: String, substring: Boolean = false) =
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().filter { it.boundsInRoot.left >= state.testsView.navWidthDp }

    private fun inTile(tile: String, text: String) = rule.onAllNodes(
        hasText(text) and hasAnyAncestor(hasTestTag("tests-tile-card-$tile")),
    )

    private fun clickCentre(text: String) {
        val nodes = rule.onAllNodesWithText(text).fetchSemanticsNodes()
        val index = nodes.indices.first { nodes[it].boundsInRoot.left >= state.testsView.navWidthDp }
        rule.onAllNodesWithText(text)[index].performClick()
        rule.waitForIdle()
    }

    private fun failedRun(createdAt: Long = System.currentTimeMillis() - 60_000L) =
        saveRun(RunStatus.FAILED, StepStatus.PASS, StepStatus.FAIL, StepStatus.BLOCKED, createdAt = createdAt)

    @Test
    fun theReportShowsTheVerdictTheMetaLineTheTabsAndPreselectsTheFirstFailingStep() {
        val run = failedRun()
        openReport(run)

        rule.onNodeWithText("Failed — Login failed at step 2 “Tap Sign in”").assertExists()
        rule.onNodeWithText("1 case", substring = true).assertExists()
        listOf("Results", "Compare", "Activity", "Usage").forEach { assertTrue(centre(it).isNotEmpty(), "tab $it") }
        rule.onNodeWithText("Re-run failed (1)").assertExists()
        // The first non-passing step is already selected in Details and Evidence.
        rule.onNodeWithText("Step 2 · Login").assertExists()
        rule.onNodeWithText("The Sign in button was not found").assertExists()
        rule.onNodeWithText("Select a step to see its evidence.").assertDoesNotExist()
        // The old metric tiles and filter row are gone.
        rule.onNodeWithText("Passed · final cases").assertDoesNotExist()
        rule.onNodeWithText("Cases / repeats").assertDoesNotExist()
        rule.onNodeWithText("Filter").assertDoesNotExist()
    }

    @Test
    fun selectingAnotherRowUpdatesDetailsForThatStep() {
        val run = failedRun()
        openReport(run)

        rule.onNodeWithText("1. Open the app").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Step 1 · Login").assertExists()
        rule.onNodeWithText("Step 2 · Login").assertDoesNotExist()
        rule.onNodeWithText("The Sign in button was not found").assertDoesNotExist()

        rule.onNodeWithText("3. Check dashboard").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Step 3 · Login").assertExists()
    }

    @Test
    fun detailsStayInsideTheirTileAndStackUnderTheResultsInASmallWindow() {
        val run = failedRun()
        openReport(run)
        rule.onNodeWithText("Step 2 · Login").assertExists()
        rule.onNodeWithText("The Sign in button was not found").assertExists()
        val inspector = rule.onNodeWithText("Step 2 · Login").fetchSemanticsNode().boundsInRoot
        val details = rule.onNodeWithTag("tests-tile-card-details").fetchSemanticsNode().boundsInRoot
        assertTrue(inspector.left >= details.left && inspector.right <= details.right, "the selected step header belongs inside Details: $inspector vs $details")
    }

    @Test
    fun aSmallWindowStacksTheInspectorUnderTheResults() {
        val run = failedRun()
        openReport(run, width = 700)
        rule.onNodeWithText("Step 2 · Login").assertExists()
        val matrixRow = rule.onNodeWithText("2. Tap Sign in").fetchSemanticsNode().boundsInRoot
        val inspector = rule.onNodeWithText("Step 2 · Login").fetchSemanticsNode().boundsInRoot
        assertTrue(inspector.top > matrixRow.top, "the inspector sits below the matrix")
    }

    @Test
    fun aPassedRunSelectsNothingAndOffersNoRerun() {
        val run = saveRun(RunStatus.PASSED, StepStatus.PASS, StepStatus.PASS, StepStatus.PASS, createdAt = System.currentTimeMillis() - 60_000L)
        openReport(run)

        assertTrue(inTile("details", "Passed — all 3 steps passed").fetchSemanticsNodes().isNotEmpty(), "Details summarizes a run with no selected step")
        assertTrue(inTile("details", "Run scope").fetchSemanticsNodes().isNotEmpty(), "Details keeps the run scope available")
        assertTrue(inTile("evidence", "Select a step to see its evidence.").fetchSemanticsNodes().isNotEmpty(), "Evidence has no stale step content")
        assertTrue(rule.onAllNodesWithText("Re-run failed", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun filterPillsShowCountsToggleRowsMultiSelectAndClear() {
        val run = failedRun()
        openReport(run)

        rule.onNodeWithContentDescription("Failures, 1").assertExists()
        rule.onNodeWithContentDescription("Blocked / error, 1").assertExists()
        rule.onNodeWithContentDescription("Disagreements, 0").assertExists()
        rule.onNodeWithContentDescription("Unresolved judging, 0").assertExists()
        rule.onNodeWithContentDescription("Clear").assertDoesNotExist()

        rule.onNodeWithContentDescription("Failures, 1").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("2. Tap Sign in").assertExists()
        rule.onNodeWithText("1. Open the app").assertDoesNotExist()
        rule.onNodeWithText("3. Check dashboard").assertDoesNotExist()
        rule.onNodeWithContentDescription("Clear").assertExists()

        // A second filter widens the result (the filters are ORed).
        rule.onNodeWithContentDescription("Blocked / error, 1").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("2. Tap Sign in").assertExists()
        rule.onNodeWithText("3. Check dashboard").assertExists()
        rule.onNodeWithText("1. Open the app").assertDoesNotExist()

        // Toggling a filter off narrows it again.
        rule.onNodeWithContentDescription("Failures, 1").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("2. Tap Sign in").assertDoesNotExist()
        rule.onNodeWithText("3. Check dashboard").assertExists()

        rule.onNodeWithContentDescription("Clear").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("1. Open the app").assertExists()
        rule.onNodeWithText("2. Tap Sign in").assertExists()
        rule.onNodeWithContentDescription("Clear").assertDoesNotExist()
    }

    @Test
    fun anEmptyFilterResultSaysSo() {
        val run = failedRun()
        openReport(run)
        rule.onNodeWithContentDescription("Disagreements, 0").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("No steps match these filters.").assertExists()
    }

    @Test
    fun theTabsSwitchBetweenResultsCompareActivityAndUsage() {
        val calls = listOf(
            LaneToolCall("call-1", case.id, tap.id, 1, 1, "tap", "x=0.5,y=0.9", "ok", LaneToolCallStatus.SUCCEEDED, startedAt = 1_000L, durationMs = 50L),
        )
        val run = saveRun(
            RunStatus.FAILED, StepStatus.PASS, StepStatus.FAIL, StepStatus.BLOCKED,
            createdAt = System.currentTimeMillis() - 60_000L,
            agentUsage = AiUsageStats(toolCalls = 37, totalTokens = 1_210_000),
            judgeUsage = AiUsageStats(toolCalls = 6, totalTokens = 150_000),
            toolCalls = calls,
        )
        openReport(run)

        clickCentre("Activity 1")
        assertEquals(RunReportTab.Activity, state.testsView.reportTab)
        assertTrue(centre("tap").isNotEmpty())
        rule.onNodeWithText("Login · step 2").assertExists()
        rule.onNodeWithText("1. Open the app").assertDoesNotExist()

        clickCentre("Usage")
        assertEquals(RunReportTab.Usage, state.testsView.reportTab)
        assertTrue(centre("43 tool calls · 1.36M tokens").isNotEmpty(), "run total")
        rule.onNodeWithText("agent: 37 tool calls · 1.21M tokens", substring = true).assertExists()
        rule.onNodeWithText("judge: 6 tool calls · 150K tokens", substring = true).assertExists()

        clickCentre("Results")
        assertEquals(RunReportTab.Results, state.testsView.reportTab)
        rule.onNodeWithText("1. Open the app").assertExists()
    }

    @Test
    fun theCompareTabNamesStepsByTheirActionAndNeverShowsIds() {
        saveRun(RunStatus.PASSED, StepStatus.PASS, StepStatus.PASS, StepStatus.PASS, createdAt = System.currentTimeMillis() - 3_600_000L)
        val run = failedRun()
        openReport(run)

        // Nothing about the previous run is shown above the results.
        rule.onNodeWithText("Compared with", substring = true).assertDoesNotExist()
        clickCentre("Compare")
        rule.waitUntilAtLeastOneExists(hasText("Compared with the run from", substring = true), timeoutMillis = 10_000)

        rule.onNodeWithText("Login · “Tap Sign in”").assertExists()
        assertTrue(centre("Pass").size >= 2, "the Before column and the matrix both say Pass")
        assertTrue(centre("Fail").size >= 2)
        rule.onNodeWithText("Compare with…").assertExists()
        assertEquals(0, rule.onAllNodesWithText("step-", substring = true).fetchSemanticsNodes().size, "no step id is shown")
    }

    @Test
    fun theCompareTabExplainsWhenThereIsNoEarlierRun() {
        val run = failedRun()
        openReport(run, RunReportTab.Compare)
        rule.waitUntilAtLeastOneExists(hasText("There is no earlier finished run of this suite to compare with."), timeoutMillis = 10_000)
    }

    @Test
    fun theOverflowMenuHoldsExportAndCompare() {
        val run = failedRun()
        openReport(run)
        rule.onNodeWithContentDescription("More report actions").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Export report…").assertExists()
        rule.onNodeWithText("Compare with…").assertExists()
    }

    @Test
    fun theBackLinkReturnsToTheRunList() {
        val run = failedRun()
        openReport(run)
        rule.onNodeWithContentDescription("Back to runs").performClick()
        rule.waitForIdle()
        assertEquals(null, state.testsView.selectedRunId)
        assertEquals(RunReportTab.Results, state.testsView.reportTab)
    }
}
