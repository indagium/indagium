@file:Suppress("MagicNumber")

package com.indagium.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueSource
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
import com.indagium.ui.SuiteTab
import com.indagium.ui.TestsNav
import com.indagium.ui.TestsWorkspace
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The suite screen (header, last-run line, tabs, case table, Runs, Settings) composed against a real AppState. */
@OptIn(ExperimentalTestApi::class)
class TestsSuiteScreenUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val dir: File = createTempDirectory("tests-suite-screen-ui").toFile()
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

    private companion object {
        const val MENU_REOPEN_WAIT_MS = 300L
    }

    @After
    fun tearDown() {
        state.close()
        dir.deleteRecursively()
    }

    private fun suiteWithCases(vararg names: String, goal: String = ""): TestSuite {
        val suite = (state.createTestSuite("Smoke") as StoreResult.Ok).value
        names.forEach { state.createTestCase(suite.id, TestCase("", it, description = goal)) }
        return state.testLibrary.suite(suite.id)!!
    }

    private fun external(id: String, serial: String) = LaneConfig(id, LaneKind.EXTERNAL, deviceSerial = serial)

    /** A finished run of [suite] saved to the runs folder: every lane reports [caseStatus] for every case. */
    private fun saveRun(
        suite: TestSuite,
        status: RunStatus,
        lanes: List<Pair<LaneConfig, CaseStatus>>,
        createdAt: Long = System.currentTimeMillis() - 60_000L,
    ): TestRun {
        val run = TestRun(
            id = newRunId(), suite = suite, scripts = emptyList(), sharedSteps = emptyList(),
            config = RunConfig(suite.id, null, lanes.map { it.first }),
            lanes = lanes.map { (lane, caseStatus) ->
                LaneResult(
                    lane.id, lane, status,
                    suite.cases.map { case ->
                        val stepStatus = if (caseStatus == CaseStatus.PASS) StepStatus.PASS else StepStatus.FAIL
                        CaseResult(
                            case.id, case.name, 1, caseStatus, listOf(StepResult("step-${case.id}", 1, "Act", status = stepStatus)),
                            startedAt = 1_000L, finishedAt = 49_000L,
                        )
                    },
                    startedAt = 1_000L,
                )
            },
            status = status, createdAt = createdAt, finishedAt = createdAt + 50_000L,
        )
        TestRunStore { state.activeTestFolders().second }.save(run).getOrThrow()
        return run
    }

    private fun open(suite: TestSuite, tab: SuiteTab = SuiteTab.Cases, width: Dp = 1100.dp) {
        state.testsView.selectedSuiteId = suite.id
        state.testsView.selectedSuiteTab = tab
        rule.setContent { Box(Modifier.size(width, 800.dp)) { TestsWorkspace(state) } }
        rule.waitForIdle()
    }

    /** The nodes with [text] right of the nav (the nav tree and the centre screen can carry the same words). */
    private fun centre(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().filter { it.boundsInRoot.left >= state.testsView.navWidthDp }

    /** A popup menu closes with a short reopen suppression (a click on the anchor that dismissed it must not reopen it). */
    private fun openMenu(description: String) {
        Thread.sleep(MENU_REOPEN_WAIT_MS)
        rule.onNodeWithContentDescription(description).performClick()
        rule.waitForIdle()
    }

    /** Clicks the tab with [label]: the topmost match right of the nav (the nav and a case can carry the same word). */
    private fun clickTab(label: String) {
        val nodes = rule.onAllNodesWithText(label).fetchSemanticsNodes()
        val index = nodes.indices.filter { nodes[it].boundsInRoot.left >= state.testsView.navWidthDp }.minByOrNull { nodes[it].boundsInRoot.top }
        assertTrue(index != null, "tab $label should exist")
        rule.onAllNodesWithText(label)[index].performClick()
    }

    private fun SemanticsNodeInteraction.assertTextNotTruncated() {
        val layouts = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertTrue(layouts.isNotEmpty(), "the node should expose its text layout")
        assertFalse(layouts.first().hasVisualOverflow, "the text should not be clipped")
    }

    @Test
    fun theHeaderMenuOffersDuplicateExportAndDeleteAndDeleteAsksForConfirmation() {
        val suite = suiteWithCases("Login")
        open(suite)

        rule.onNodeWithText("▶ Run suite").assertExists()
        rule.onNodeWithContentDescription("Suite actions").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Duplicate").assertExists()
        rule.onNodeWithText("Export…").assertExists()
        rule.onNodeWithText("Delete…").assertExists()

        rule.onNodeWithText("Delete…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete suite?").assertExists()
        assertEquals(1, state.testLibrary.suites.size, "nothing is deleted before the confirmation")
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals(1, state.testLibrary.suites.size)

        openMenu("Suite actions")
        rule.onNodeWithText("Delete…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete").performClick()
        rule.waitUntil(timeoutMillis = 5_000) { state.testLibrary.suite(suite.id) == null }
    }

    @Test
    fun duplicatingFromTheHeaderMenuSelectsTheCopy() {
        val suite = suiteWithCases("Login")
        open(suite)

        rule.onNodeWithContentDescription("Suite actions").performClick()
        rule.onNodeWithText("Duplicate").performClick()
        rule.waitForIdle()

        assertEquals(2, state.testLibrary.suites.size)
        assertTrue(state.testsView.selectedSuiteId != suite.id)
    }

    @Test
    fun theTabsAreCasesRunsSettingsAndSettingsShowsTheFourGroups() {
        val suite = suiteWithCases("Login", "Checkout")
        open(suite)

        rule.onNodeWithText("Cases (2)").assertExists()
        rule.onNodeWithText("Runs (0)").assertExists()
        rule.onNodeWithText("Setup & teardown").assertDoesNotExist()

        clickTab("Settings")
        rule.waitForIdle()

        assertEquals(SuiteTab.Settings, state.testsView.selectedSuiteTab)
        rule.onNodeWithText("Setup & teardown").assertExists()
        rule.onNodeWithText("Setup hooks").assertExists()
        rule.onNodeWithText("Teardown hooks").assertExists()
        rule.onNodeWithText("Agent instructions").assertExists()
        rule.onNodeWithText("Variables").assertExists()
        assertTrue(centre("Login").isEmpty(), "the case table is not part of the Settings tab")
    }

    @Test
    fun theLastRunLineSaysLoadingBeforeTheHistoryLoadsAndSummarisesTheRunAfter() {
        val suite = suiteWithCases("Login", "Checkout")
        saveRun(suite, RunStatus.PASSED, listOf(external("lane-a", "pixel-8") to CaseStatus.PASS))
        state.testsView.selectedSuiteId = suite.id
        rule.mainClock.autoAdvance = false
        rule.setContent { TestsWorkspace(state) }
        // No frame has run, so the history read (launched by an effect on the paused clock) cannot have started or finished:
        // advancing a frame here would race the real disk read on a slow machine and could already show the loaded state.

        rule.onNodeWithText("Loading runs…").assertExists()
        rule.onNodeWithText("Not run yet").assertDoesNotExist()
        assertTrue(centre("Runs").isNotEmpty(), "the Runs tab shows no count while loading")
        assertTrue(centre("Runs (0)").isEmpty())

        rule.mainClock.autoAdvance = true
        rule.waitUntilAtLeastOneExists(hasText("Open report →"), timeoutMillis = 10_000)
        rule.onNodeWithText("Loading runs…").assertDoesNotExist()
        rule.onNodeWithText("2 cases · 1 lane · 2 of 2 steps passed", substring = true).assertExists()
        rule.onNodeWithText("Runs (1)").assertExists()

        rule.onNodeWithText("Open report →").performClick()
        rule.waitForIdle()
        assertEquals(TestsNav.Runs, state.testsView.nav)
        assertTrue(state.testsView.selectedRunId != null)
    }

    @Test
    fun aSuiteWithoutRunsSaysNotRunYetOnlyAfterLoadingAndTheRunsTabHintsHowToStart() {
        val suite = suiteWithCases("Login")
        open(suite)

        rule.waitUntilAtLeastOneExists(hasText("Not run yet"), timeoutMillis = 10_000)
        rule.onNodeWithText("Loading runs…").assertDoesNotExist()
        clickTab("Runs (0)")
        rule.waitForIdle()
        rule.onNodeWithText("No runs yet. Start one with Run suite.").assertExists()
    }

    @Test
    fun aCancelledRunIsNeverTruncatedInTheLastRunLineOrTheRunsTab() {
        val suite = suiteWithCases("Login")
        saveRun(suite, RunStatus.CANCELLED, listOf(external("lane-a", "pixel-8") to CaseStatus.PASS))
        open(suite)

        rule.waitUntilAtLeastOneExists(hasText("Open report →"), timeoutMillis = 10_000)
        rule.onNodeWithText("Cancelled").assertExists().assertTextNotTruncated()

        clickTab("Runs (1)")
        rule.waitForIdle()
        val chips = rule.onAllNodesWithText("Cancelled")
        chips[0].assertExists().assertTextNotTruncated()
        // Without the suite name (the screen is the suite) the row reads: chip · "1 case" · pass bar "1/1" · "1 lane" · clock time.
        rule.onNodeWithText("1/1").assertExists()
        assertTrue(centre("1 case").isNotEmpty(), "the run row names its cases (the nav tree carries the same words)")
        rule.onNodeWithText("1 lane").assertExists()
        rule.onNodeWithText("Today").assertExists()
    }

    @Test
    fun aCaseWithSeveralLanesShowsOneDotPerLaneWithoutGrowingItsRow() {
        val suite = suiteWithCases("Alpha", "Bravo", "Charlie")
        saveRun(
            suite, RunStatus.FAILED,
            listOf(external("lane-a", "pixel-8") to CaseStatus.PASS, external("lane-b", "galaxy-s") to CaseStatus.FAIL),
        )
        open(suite)

        rule.waitUntilAtLeastOneExists(hasContentDescription("External · pixel-8 · Pass · 48s"), timeoutMillis = 10_000)
        assertEquals(3, rule.onAllNodesWithContentDescription("External · pixel-8 · Pass · 48s").fetchSemanticsNodes().size)
        assertEquals(3, rule.onAllNodesWithContentDescription("External · galaxy-s · Fail · 48s").fetchSemanticsNodes().size)
        // A row is 34dp plus its 1dp separator however many lanes ran; the old table grew 18dp per lane.
        val tops = listOf("Alpha", "Bravo", "Charlie").map { name -> centre(name).single().boundsInRoot.top }
        val rowPx = with(rule.density) { 36.dp.toPx() }
        assertTrue(tops[1] - tops[0] <= rowPx && tops[2] - tops[1] <= rowPx, "rows grew: $tops")
    }

    @Test
    fun aSingleLaneCaseShowsStatusDurationAndRepeatsOnOneLine() {
        val suite = suiteWithCases("Login")
        saveRun(suite, RunStatus.PASSED, listOf(external("lane-a", "pixel-8") to CaseStatus.PASS))
        open(suite)

        rule.waitUntilAtLeastOneExists(hasText("Pass"), timeoutMillis = 10_000)
        rule.onNodeWithText("48s").assertExists()
    }

    @Test
    fun theIssuesColumnCountOpensTheIssuesScreenOnThatIssue() {
        val suite = suiteWithCases("Login")
        val case = suite.cases.single()
        val issue = (
            state.issueStore.create(
                IssueDraft("Login crashes"),
                IssueSource("run-1", "lane-1", suite.id, case.id, "step-1", caseName = "Login", stepNumber = 2),
            ) as StoreResult.Ok
        ).value
        open(suite)

        rule.waitUntilAtLeastOneExists(hasContentDescription("Issues: 1"), timeoutMillis = 10_000)
        rule.onNodeWithContentDescription("Issues: 1").performClick()
        rule.waitForIdle()

        assertEquals(TestsNav.Issues, state.testsView.nav)
        assertEquals(issue.id, state.testsView.selectedIssueId)
    }

    @Test
    fun theCaseRowMenuMovesDuplicatesAndDeletesWithAConfirmation() {
        val suite = suiteWithCases("Login", "Checkout")
        open(suite)

        openMenu("Actions for Checkout")
        rule.onNodeWithText("Move up").performClick()
        rule.waitForIdle()
        assertEquals(listOf("Checkout", "Login"), state.testLibrary.suite(suite.id)!!.cases.map { it.name })

        openMenu("Actions for Login")
        rule.onNodeWithText("Duplicate").performClick()
        rule.waitForIdle()
        assertEquals(3, state.testLibrary.suite(suite.id)!!.cases.size)

        openMenu("Actions for Checkout")
        rule.onNodeWithText("Delete…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete case?").assertExists()
        assertEquals(3, state.testLibrary.suite(suite.id)!!.cases.size)
        rule.onNodeWithText("Delete").performClick()
        rule.waitUntil(timeoutMillis = 5_000) { state.testLibrary.suite(suite.id)!!.cases.size == 2 }
    }

    @Test
    fun clickingARowSelectsTheCaseAndEditStepsOrASecondClickOpensIt() {
        val suite = suiteWithCases("Login")
        open(suite, width = 1500.dp)
        val caseId = suite.cases.single().id

        fun clickRow() {
            rule.onAllNodesWithText("Login").fetchSemanticsNodes().let { nodes ->
                val index = nodes.indices.first { nodes[it].boundsInRoot.left >= state.testsView.navWidthDp }
                rule.onAllNodesWithText("Login")[index].performClick()
            }
            rule.waitForIdle()
        }
        clickRow()
        assertEquals(caseId, state.testsView.previewCaseId, "a click selects the row")
        assertNull(state.testsView.selectedCaseId, "and does not open the editor")
        rule.onNodeWithText("Case 1").assertExists()
        assertTrue(centre("Login").size >= 2, "the detail panel names the case next to the table row")

        rule.onNodeWithText("Edit steps").performClick()
        rule.waitForIdle()
        assertEquals(caseId, state.testsView.selectedCaseId)

        state.testsView.selectedCaseId = null
        rule.waitForIdle()
        clickRow()
        assertEquals(caseId, state.testsView.selectedCaseId, "a second click on the selected row opens the case")

        val empty = (state.createTestSuite("Empty") as StoreResult.Ok).value
        state.testsView.selectedCaseId = null
        state.testsView.selectedSuiteId = empty.id
        rule.waitForIdle()
        rule.onNodeWithText("No cases yet. Add a case to define a workflow for the agent.").assertExists()
        // Both the header button and the empty state offer it.
        assertEquals(2, rule.onAllNodesWithText("+ New case").fetchSemanticsNodes().size)
    }

    @Test
    fun theWideLayoutShowsTheColumnHeaderAndTheGoalLine() {
        val suite = suiteWithCases("Login", goal = "Signing in works")
        open(suite, width = 1400.dp)

        assertTrue(centre("Latest").isNotEmpty(), "the table header names the latest-result column")
        assertTrue(centre("Issues").isNotEmpty(), "the table header names the issues column")
        assertTrue(centre("Signing in works").isNotEmpty(), "the goal line shows under the name")
    }

    @Test
    fun belowTheBreakpointTheTableHasNoHeaderAndEachRowShowsStepsAndLatest() {
        val suite = suiteWithCases("Login")
        saveRun(suite, RunStatus.PASSED, listOf(external("lane-a", "pixel-8") to CaseStatus.PASS))
        open(suite, width = 500.dp)

        rule.waitUntilAtLeastOneExists(hasText("Pass"), timeoutMillis = 10_000)
        assertTrue(centre("Latest").isEmpty(), "the narrow table has no header")
        // The narrow window hides the nav, so these can only be the centre screen's row.
        rule.onNodeWithText("0 steps ·").assertExists()
        rule.onNodeWithText("Login").assertExists()
    }

    @Test
    fun newCaseIsASecondaryButtonInTheHeaderNextToRunSuiteAndNotUnderTheTable() {
        val suite = suiteWithCases("Login", "Checkout")
        open(suite)

        val newCase = centre("+ New case")
        assertEquals(1, newCase.size, "one button, in the header: the ghost button under the table is gone")
        val run = centre("▶ Run suite").single().boundsInRoot
        val button = newCase.single().boundsInRoot
        assertTrue(kotlin.math.abs(button.center.y - run.center.y) < 4f, "same row as Run suite: $button vs $run")
        assertTrue(button.right <= run.left + 1f, "left of Run suite: $button vs $run")
        val firstRow = centre("Login").first().boundsInRoot
        assertTrue(button.top < firstRow.top, "above the table, not below it")

        rule.onAllNodesWithText("+ New case").let { nodes ->
            val index = nodes.fetchSemanticsNodes().indices.first { nodes.fetchSemanticsNodes()[it].boundsInRoot.left >= state.testsView.navWidthDp }
            nodes[index].performClick()
        }
        rule.waitForIdle()
        assertEquals(3, state.testLibrary.suite(suite.id)!!.cases.size)
    }

    @Test
    fun theHeaderNewCaseButtonIsDisabledWithItsHintForALockedSuiteAndWhenTheLibraryIsReadOnly() {
        state.createTestSuite("First")
        val locked = (state.createTestSuite("Second") as StoreResult.Ok).value
        assertTrue(state.editionService.setForDev(com.indagium.edition.Edition.FREE))
        open(state.testLibrary.suite(locked.id)!!)

        assertTrue(
            rule.onAllNodes(androidx.compose.ui.test.isNotEnabled() and androidx.compose.ui.test.hasAnyDescendant(hasText("+ New case")))
                .fetchSemanticsNodes().isNotEmpty(),
            "+ New case is disabled for a locked suite",
        )
    }
}
