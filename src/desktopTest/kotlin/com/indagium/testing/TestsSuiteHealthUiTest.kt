@file:Suppress("MagicNumber")

package com.indagium.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import com.indagium.edition.Edition
import com.indagium.testing.limits.FREE_EDITION_LIMIT_HINT
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
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
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.newRunId
import com.indagium.testing.model.newStepId
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.TestRunStore
import com.indagium.ui.AppState
import com.indagium.ui.IssueDialogTarget
import com.indagium.ui.RunDialogTarget
import com.indagium.ui.SuiteTab
import com.indagium.ui.TestsWorkspace
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The suite screen's Health tab: summary cards, the cases x runs grid, the flaky badge, the latest failure and the empty/locked states. */
@OptIn(ExperimentalTestApi::class)
class TestsSuiteHealthUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val dir: File = createTempDirectory("tests-suite-health-ui").toFile()
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
    private val lane = LaneConfig("lane-a", LaneKind.EXTERNAL, deviceSerial = "pixel-8")

    @After
    fun tearDown() {
        state.close()
        dir.deleteRecursively()
    }

    private fun suiteWithCases(vararg names: String): TestSuite {
        val suite = (state.createTestSuite("Smoke") as StoreResult.Ok).value
        names.forEach { state.createTestCase(suite.id, TestCase("", it, steps = listOf(TestStep(newStepId(), "Do it")))) }
        return state.testLibrary.suite(suite.id)!!
    }

    /** Saves a finished run of [suite]; [failing] names the case ids that failed (the observation says why). */
    private fun saveRun(suite: TestSuite, failing: Set<String>, createdAt: Long): TestRun {
        val failed = failing.isNotEmpty()
        val runStatus = if (failed) RunStatus.FAILED else RunStatus.PASSED
        val run = TestRun(
            id = newRunId(), suite = suite, scripts = emptyList(), sharedSteps = emptyList(),
            config = RunConfig(suite.id, null, listOf(lane)),
            lanes = listOf(
                LaneResult(
                    lane.id, lane, runStatus,
                    suite.cases.map { case ->
                        val bad = case.id in failing
                        val stepStatus = if (bad) StepStatus.FAIL else StepStatus.PASS
                        CaseResult(
                            case.id, case.name, 1, if (bad) CaseStatus.FAIL else CaseStatus.PASS,
                            case.steps.mapIndexed { i, step ->
                                StepResult(step.id, i + 1, step.action, status = stepStatus, observation = if (bad) "The button did nothing." else "")
                            },
                            startedAt = 1_000L, finishedAt = 49_000L,
                        )
                    },
                    startedAt = 1_000L,
                ),
            ),
            status = runStatus, createdAt = createdAt, finishedAt = createdAt + 50_000L,
        )
        TestRunStore { state.activeTestFolders().second }.save(run).getOrThrow()
        return run
    }

    private fun openHealth(suite: TestSuite) {
        state.testsView.selectedSuiteId = suite.id
        state.testsView.selectedSuiteTab = SuiteTab.Health
        rule.setTestsViewport(1500, 900)
        rule.setContent { Box(Modifier.size(1500.dp, 900.dp)) { TestsWorkspace(state) } }
        rule.waitForIdle()
    }

    private fun seedThreeRuns(): Pair<TestSuite, TestRun> {
        val suite = suiteWithCases("Login", "Checkout")
        saveRun(suite, emptySet(), createdAt = 1_000_000L)
        val failing = saveRun(suite, setOf(suite.cases[0].id), createdAt = 2_000_000L)
        saveRun(suite, emptySet(), createdAt = 3_000_000L)
        return suite to failing
    }

    @Test
    fun theHealthTabShowsPassRateRunCountsFlakyBadgeAndDescribedCells() {
        val (suite, _) = seedThreeRuns()
        openHealth(suite)

        rule.waitUntilAtLeastOneExists(hasContentDescription("#2 failed"), timeoutMillis = 10_000)
        rule.onNodeWithText("Pass rate, last 3 runs").assertExists()
        // The card and Login's own pass column (2 of its 3 runs) both read 67%; Checkout passed every run.
        rule.onAllNodesWithText("67%").assertCountEquals(2)
        rule.onNodeWithText("100%").assertExists()
        rule.onNodeWithText("2 passed · 1 failed").assertExists()
        rule.onNodeWithText("Flaky cases").assertExists()
        rule.onAllNodesWithText("Flaky").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("#1 passed").assertCountEquals(2)
        rule.onAllNodesWithContentDescription("#2 failed").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("#2 passed").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("#3 passed").assertCountEquals(2)
        rule.onNodeWithText("#1").assertExists()
        rule.onNodeWithText("#2").assertExists()
        rule.onNodeWithText("#3").assertExists()
        rule.onNodeWithText("Median duration").assertExists()
        rule.onNodeWithText("Open issues").assertExists()
    }

    @Test
    fun theLatestFailureCardDraftsAnIssueForTheFailingStep() {
        val (suite, failing) = seedThreeRuns()
        openHealth(suite)

        rule.waitUntilAtLeastOneExists(hasText("Draft issue…"), timeoutMillis = 10_000)
        rule.onNodeWithText("Latest failure").assertExists()
        rule.onNodeWithText("#2 · Login · step 1 “Do it”").assertExists()
        rule.onNodeWithText("The button did nothing.").assertExists()
        rule.onNodeWithText("Open run log").assertExists()

        rule.onNodeWithText("Draft issue…").performScrollTo().performClick()
        rule.waitForIdle()

        val target = state.testsView.issueDialog
        assertEquals(
            IssueDialogTarget.FromStep(failing.id, "lane-a", suite.cases[0].id, 1, suite.cases[0].steps.single().id),
            target,
        )
    }

    @Test
    fun openRunLogOpensTheFailedRunsReport() {
        val (suite, failing) = seedThreeRuns()
        openHealth(suite)

        rule.waitUntilAtLeastOneExists(hasText("Open run log"), timeoutMillis = 10_000)
        rule.onNodeWithText("Open run log").performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals(failing.id, state.testsView.selectedRunId)
    }

    @Test
    fun runCaseInTheGridOpensTheRunDialogForThatCase() {
        val (suite, _) = seedThreeRuns()
        openHealth(suite)
        rule.waitUntilAtLeastOneExists(hasContentDescription("Run case Checkout"), timeoutMillis = 10_000)

        rule.onNodeWithContentDescription("Run case Checkout").performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals(RunDialogTarget(suite.id, suite.cases[1].id), state.testsView.runDialog)
    }

    @Test
    fun withNoFinishedRunTheTabSaysSoAndOffersToRunTheSuite() {
        val suite = suiteWithCases("Login")
        openHealth(suite)

        rule.waitUntilAtLeastOneExists(hasText("No finished runs yet. Run the suite to see its health."), timeoutMillis = 10_000)
        rule.onNodeWithText("Pass rate", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Run suite").performClick()
        rule.waitForIdle()

        assertEquals(RunDialogTarget(suite.id), state.testsView.runDialog)
    }

    @Test
    fun underFreeALockedCaseShowsTheLockAndNoRunCase() {
        val suite = suiteWithCases("A", "B", "C", "D", "E", "F", "G")
        saveRun(suite, emptySet(), createdAt = 1_000_000L)
        assertTrue(state.editionService.setForDev(Edition.FREE))
        openHealth(suite)

        rule.waitUntilAtLeastOneExists(hasContentDescription("Run case A"), timeoutMillis = 10_000)
        assertTrue(rule.onAllNodes(hasContentDescription(FREE_EDITION_LIMIT_HINT)).fetchSemanticsNodes().size >= 2, "cases F and G show a lock")
        rule.onNodeWithContentDescription("Run case E").assertExists()
        rule.onNodeWithContentDescription("Run case F").assertDoesNotExist()
        rule.onNodeWithContentDescription("Run case G").assertDoesNotExist()
    }

    @Test
    fun theHeaderOffersARerunOfTheFailedCasesOfTheLatestFinishedRun() {
        val suite = suiteWithCases("Login", "Checkout")
        saveRun(suite, setOf(suite.cases[0].id), createdAt = 1_000_000L)
        openHealth(suite)

        rule.waitUntilAtLeastOneExists(hasText("Re-run failed (1)"), timeoutMillis = 10_000)
        rule.onNodeWithText("Re-run failed (1)").performClick()
        rule.waitForIdle()

        val target = state.testsView.runDialog
        assertEquals(suite.id, target?.suiteId)
        assertEquals(setOf(suite.cases[0].id), target?.initialCaseIds)
    }
}
