@file:Suppress("MagicNumber", "ktlint:standard:max-line-length", "MaxLineLength")

package com.indagium.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
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
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.newRunId
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.TestRunStore
import com.indagium.ui.AppState
import com.indagium.ui.TestsNav
import com.indagium.ui.TestsWorkspace
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Runs screen (suite filter, status pills with counts, day groups, row click) composed against a real AppState and stored runs. */
@OptIn(ExperimentalTestApi::class)
class TestsRunsScreenUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val dir: File = createTempDirectory("tests-runs-screen-ui").toFile()
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

    private fun suiteNamed(name: String): TestSuite {
        val suite = (state.createTestSuite(name) as StoreResult.Ok).value
        state.createTestCase(suite.id, TestCase("", "Login"))
        return state.testLibrary.suite(suite.id)!!
    }

    private fun saveRun(suite: TestSuite, status: RunStatus, createdAt: Long): TestRun {
        val stepStatus = if (status == RunStatus.PASSED) StepStatus.PASS else StepStatus.FAIL
        val caseStatus = if (status == RunStatus.PASSED) CaseStatus.PASS else CaseStatus.FAIL
        val run = TestRun(
            id = newRunId(), suite = suite, scripts = emptyList(), sharedSteps = emptyList(),
            config = RunConfig(suite.id, null, listOf(lane)),
            lanes = listOf(
                LaneResult(
                    lane.id, lane, status,
                    suite.cases.map { case -> CaseResult(case.id, case.name, 1, caseStatus, listOf(StepResult("step-${case.id}", 1, "Act", status = stepStatus)), startedAt = 1_000L, finishedAt = 49_000L) },
                    startedAt = 1_000L,
                ),
            ),
            status = status, createdAt = createdAt, finishedAt = createdAt + 50_000L,
        )
        TestRunStore { state.activeTestFolders().second }.save(run).getOrThrow()
        return run
    }

    private fun yesterdayNoon() = LocalDate.now().minusDays(1).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun openRuns() {
        state.testsView.nav = TestsNav.Runs
        rule.setContent { Box(Modifier.size(1200.dp, 800.dp)) { TestsWorkspace(state) } }
        rule.waitForIdle()
    }

    private fun centre(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().filter { it.boundsInRoot.left >= state.testsView.navWidthDp }

    private fun clickCentre(text: String) {
        val nodes = rule.onAllNodesWithText(text).fetchSemanticsNodes()
        val index = nodes.indices.first { nodes[it].boundsInRoot.left >= state.testsView.navWidthDp }
        rule.onAllNodesWithText(text)[index].performClick()
        rule.waitForIdle()
    }

    private fun seed() {
        val smoke = suiteNamed("Smoke")
        val regression = suiteNamed("Regression")
        suiteNamed("Nightly")
        val now = System.currentTimeMillis()
        saveRun(smoke, RunStatus.PASSED, now - 1_000L)
        saveRun(smoke, RunStatus.FAILED, now - 2_000L)
        saveRun(regression, RunStatus.ERROR, now - 3_000L)
        saveRun(smoke, RunStatus.CANCELLED, yesterdayNoon())
    }

    @Test
    fun theListSaysLoadingBeforeTheFirstReadAndNeverNoRunsYetWhileLoading() {
        seed()
        state.testsView.nav = TestsNav.Runs
        rule.mainClock.autoAdvance = false
        rule.setContent { Box(Modifier.size(1200.dp, 800.dp)) { TestsWorkspace(state) } }
        rule.mainClock.advanceTimeByFrame()

        rule.onNodeWithText("Loading runs…").assertExists()
        assertTrue(rule.onAllNodesWithText("No runs yet.", substring = true).fetchSemanticsNodes().isEmpty())

        rule.mainClock.autoAdvance = true
        rule.waitUntilAtLeastOneExists(hasText("Today"), timeoutMillis = 10_000)
        rule.onNodeWithText("Loading runs…").assertDoesNotExist()
    }

    @Test
    fun withoutAnyRunTheEmptyTextAppearsOnlyAfterTheLoad() {
        openRuns()

        rule.waitUntilAtLeastOneExists(hasText("No runs yet. Start one with Run suite."), timeoutMillis = 10_000)
        rule.onNodeWithText("Loading runs…").assertDoesNotExist()
    }

    @Test
    fun runsAreGroupedUnderTodayAndYesterdayWithTheirStatusAndPassBar() {
        seed()
        openRuns()

        rule.waitUntilAtLeastOneExists(hasText("Today"), timeoutMillis = 10_000)
        rule.onNodeWithText("Yesterday").assertExists()
        // Each row is one node (chip, suite, bar, meta and clock merge): three Smoke runs, one Regression run.
        assertEquals(3, centre("Smoke").size, "three Smoke runs, one row each")
        assertEquals(1, centre("Regression").size)
        // A passed run's pass bar reads 1/1, a failed one 0/1; all carry "1 case · 1 lane".
        assertEquals(1, centre("1/1").size)
        assertEquals(3, centre("0/1").size)
        assertEquals(4, centre("1 case · 1 lane").size)
    }

    @Test
    fun theStatusPillsShowCountsAndFilterTheList() {
        seed()
        openRuns()
        rule.waitUntilAtLeastOneExists(hasText("Today"), timeoutMillis = 10_000)

        rule.onNodeWithContentDescription("All, 4").assertExists()
        rule.onNodeWithContentDescription("Failed, 2").assertExists()
        rule.onNodeWithContentDescription("Passed, 1").assertExists()
        rule.onNodeWithContentDescription("Cancelled, 1").assertExists()

        rule.onNodeWithContentDescription("Failed, 2").performClick()
        rule.waitForIdle()
        // The Failed filter keeps the failed and the errored run; the pill carries its own "Failed" text.
        assertEquals(2, centre("Failed").size, "the pill and the failed run")
        assertEquals(1, centre("Error").size)
        assertEquals(1, centre("Passed").size, "only the pill is left: no Passed row")
        rule.onNodeWithText("Yesterday").assertDoesNotExist()

        rule.onNodeWithContentDescription("Cancelled, 1").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Yesterday").assertExists()
        rule.onNodeWithText("Today").assertDoesNotExist()

        rule.onNodeWithContentDescription("All, 4").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Today").assertExists()
    }

    @Test
    fun theSuiteDropdownNarrowsTheListAndTheCountsAndAnEmptyFilterSaysSo() {
        seed()
        openRuns()
        rule.waitUntilAtLeastOneExists(hasText("Today"), timeoutMillis = 10_000)

        clickCentre("All suites")
        clickCentre("Nightly")
        rule.onNodeWithText("No runs match this filter.").assertExists()
        rule.onNodeWithContentDescription("All, 0").assertExists()

        Thread.sleep(MENU_REOPEN_WAIT_MS)
        clickCentre("Nightly")
        clickCentre("Regression")
        rule.onNodeWithContentDescription("All, 1").assertExists()
        rule.onNodeWithContentDescription("Failed, 1").assertExists()
        assertEquals(1, centre("Regression").size - 1, "one run row besides the dropdown label")
        rule.onNodeWithText("Yesterday").assertDoesNotExist()
    }

    @Test
    fun clickingARowOpensItsReport() {
        val regression = suiteNamed("Regression")
        val run = saveRun(regression, RunStatus.FAILED, System.currentTimeMillis() - 1_000L)
        openRuns()
        rule.waitUntilAtLeastOneExists(hasText("Today"), timeoutMillis = 10_000)

        clickCentre("Regression")

        assertEquals(run.id, state.testsView.selectedRunId)
        rule.waitUntilAtLeastOneExists(androidx.compose.ui.test.hasContentDescription("Back to runs"), timeoutMillis = 10_000)
    }

    private companion object {
        const val MENU_REOPEN_WAIT_MS = 300L
    }
}
