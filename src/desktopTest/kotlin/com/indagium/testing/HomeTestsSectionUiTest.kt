package com.indagium.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import com.indagium.edition.Edition
import com.indagium.testing.model.CaseResult
import com.indagium.testing.model.CaseStatus
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.IssueSource
import com.indagium.testing.model.IssueStatus
import com.indagium.testing.model.LaneConfig
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.RunConfig
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.RunSummary
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestSuite
import com.indagium.testing.model.newRunId
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.StoredLatestRuns
import com.indagium.testing.store.TestRunStore
import com.indagium.ui.AppState
import com.indagium.ui.HomeLastRun
import com.indagium.ui.HomeTestsSection
import com.indagium.ui.RunDialogTarget
import com.indagium.ui.TestsNav
import com.indagium.ui.homeIssuesLine
import com.indagium.ui.homeLatestRunLine
import com.indagium.ui.homeSuiteSubLine
import com.indagium.ui.mergeLatestRuns
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The New tab's Tests section composed against a real AppState (seeded suites and stored runs), plus its pure text helpers. */
@OptIn(ExperimentalTestApi::class)
@Suppress("MagicNumber") // Synthetic timestamps and counts.
class HomeTestsSectionUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val dir: File = createTempDirectory("home-tests-section-ui").toFile()
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

    private fun suiteWithCases(name: String, caseCount: Int = 1): TestSuite {
        val suite = (state.createTestSuite(name) as StoreResult.Ok).value
        repeat(caseCount) { state.createTestCase(suite.id, TestCase("", "Case ${it + 1}")) }
        return state.testLibrary.suite(suite.id)!!
    }

    private fun saveFinishedRun(suite: TestSuite, status: RunStatus = RunStatus.PASSED): TestRun {
        val lane = LaneConfig("lane-a", LaneKind.EXTERNAL, deviceSerial = "pixel-8")
        val createdAt = System.currentTimeMillis() - 60_000L
        val stepStatus = if (status == RunStatus.PASSED) StepStatus.PASS else StepStatus.FAIL
        val run = TestRun(
            id = newRunId(), suite = suite, scripts = emptyList(), sharedSteps = emptyList(),
            config = RunConfig(suite.id, null, listOf(lane)),
            lanes = listOf(
                LaneResult(
                    lane.id, lane, status,
                    suite.cases.map { case ->
                        CaseResult(
                            case.id, case.name, 1, CaseStatus.PASS, listOf(StepResult("step-${case.id}", 1, "Act", status = stepStatus)),
                            startedAt = 1_000L, finishedAt = 49_000L,
                        )
                    },
                    startedAt = 1_000L,
                ),
            ),
            status = status, createdAt = createdAt, finishedAt = createdAt + 50_000L,
        )
        TestRunStore { state.activeTestFolders().second }.save(run).getOrThrow()
        return run
    }

    private fun setSection() {
        rule.setContent { Box(Modifier.size(900.dp, 600.dp)) { HomeTestsSection(state, onReclaimFocus = {}, modifier = Modifier.size(900.dp, 600.dp)) } }
        rule.waitForIdle()
    }

    private fun assertButtonDisabled(tag: String) {
        assertTrue(
            rule.onAllNodes(isNotEnabled() and (hasTestTag(tag) or hasAnyDescendant(hasTestTag(tag)))).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasTestTag(tag) and isNotEnabled()).fetchSemanticsNodes().isNotEmpty(),
            "$tag should be disabled",
        )
    }

    // ── Composed section ─────────────────────────────────────────────

    @Test
    fun runOpensTheTestsTabOnTheSuiteWithItsRunDialog() {
        val suite = suiteWithCases("Smoke", 2)
        setSection()

        rule.waitUntilAtLeastOneExists(hasText("2 cases · Not run yet"), timeoutMillis = 10_000)
        rule.onNodeWithTag("home-tests-run-${suite.id}").performClick()
        rule.waitForIdle()

        assertTrue(state.testsSurfaceActive)
        assertEquals(TestsNav.Suites, state.testsView.nav)
        assertEquals(suite.id, state.testsView.selectedSuiteId)
        assertNull(state.testsView.selectedCaseId)
        assertEquals(RunDialogTarget(suite.id), state.testsView.runDialog)
    }

    @Test
    fun theSuiteRowShowsItsLastResultAndTheLatestActivityOpensThatRunsReport() {
        val suite = suiteWithCases("Smoke")
        val run = saveFinishedRun(suite)
        setSection()

        rule.waitUntilAtLeastOneExists(hasText("Passed", substring = true) and hasText("1 case", substring = true), timeoutMillis = 10_000)
        rule.onNodeWithText("Run passed · 1 of 1 step · 50s").assertExists()
        rule.onNodeWithTag("home-tests-latest").performClick()
        rule.waitForIdle()

        assertTrue(state.testsSurfaceActive)
        assertEquals(TestsNav.Runs, state.testsView.nav)
        assertEquals(run.id, state.testsView.selectedRunId)
    }

    @Test
    fun aLockedSuiteOffersViewInsteadOfRunAndNamesTheEdition() {
        val first = suiteWithCases("First")
        val second = suiteWithCases("Second", 3)
        assertTrue(state.editionService.setForDev(Edition.FREE))
        setSection()

        rule.waitUntilAtLeastOneExists(hasText("3 cases · read-only on Free"), timeoutMillis = 10_000)
        rule.onNodeWithTag("home-tests-run-${first.id}").assertExists()
        rule.onNodeWithTag("home-tests-run-${second.id}").assertDoesNotExist()
        rule.onNodeWithTag("home-tests-view-${second.id}").performClick()
        rule.waitForIdle()

        assertTrue(state.testsSurfaceActive)
        assertEquals(second.id, state.testsView.selectedSuiteId)
        assertNull(state.testsView.runDialog)
    }

    @Test
    fun theUsageLineAppearsOnlyOnALimitedEdition() {
        suiteWithCases("Smoke", 5)
        assertTrue(state.editionService.setForDev(Edition.UNLIMITED))
        setSection()
        rule.waitUntilAtLeastOneExists(hasText("5 cases", substring = true), timeoutMillis = 10_000)
        rule.onNodeWithTag("home-tests-usage").assertDoesNotExist()

        assertTrue(state.editionService.setForDev(Edition.FREE))
        rule.waitForIdle()

        rule.onNodeWithTag("home-tests-usage").assertExists()
        rule.onNodeWithText("Free · 1 of 1 suite · 5 of 5 cases").assertExists()
        assertButtonDisabled("home-tests-new-suite")
    }

    @Test
    fun anEmptyLibraryOffersANewSuiteWhichOpensTheTestsTabOnIt() {
        setSection()

        rule.onNodeWithText("No test suites yet.").assertExists()
        rule.onNodeWithTag("home-tests-new-suite").performClick()
        rule.waitForIdle()

        val created = state.testLibrary.suites.single()
        assertEquals("New suite", created.name)
        assertTrue(state.testsSurfaceActive)
        assertEquals(created.id, state.testsView.selectedSuiteId)
        assertNull(state.testsView.runDialog)
    }

    // ── Pure text helpers ────────────────────────────────────────────

    @Test
    fun suiteSubLinesCoverLoadingNeverRunRanAndLocked() {
        val now = 1_700_000_000_000L
        assertEquals("1 case · …", homeSuiteSubLine(1, false, "Free", HomeLastRun.Loading, now))
        assertEquals("3 cases · Not run yet", homeSuiteSubLine(3, false, "Free", HomeLastRun.NeverRun, now))
        assertTrue(homeSuiteSubLine(3, false, "Free", HomeLastRun.Ran(RunStatus.FAILED, now), now).startsWith("3 cases · Failed today "))
        assertEquals("5 cases · read-only on Premium", homeSuiteSubLine(5, true, "Premium", HomeLastRun.Ran(RunStatus.PASSED, now), now))
    }

    @Test
    fun latestRunLineReadsLikeTheReportHeader() {
        val base = RunSummary(
            "r", "Smoke", RunStatus.PASSED, createdAt = 1_000L, finishedAt = 155_000L,
            laneCount = 1, caseCount = 2, passedSteps = 8, totalSteps = 8,
        )
        assertEquals("Run passed · 8 of 8 steps · 2m 34s", homeLatestRunLine(base))
        assertEquals("Run failed · 7 of 8 steps · 2m 34s", homeLatestRunLine(base.copy(status = RunStatus.FAILED, passedSteps = 7)))
        val cancelled = base.copy(status = RunStatus.CANCELLED, passedSteps = 0, totalSteps = 1, finishedAt = null)
        assertEquals("Run was cancelled · 0 of 1 step", homeLatestRunLine(cancelled))
    }

    @Test
    fun issuesLineCountsOpenDraftsAndSavedAndHidesWhenNothingIsOpen() {
        val source = IssueSource("r", "l", "s", "c", "st", caseName = "C", stepNumber = 1)

        fun issue(status: IssueStatus) = IssueRecord("i-$status", IssueDraft("t"), source, status)
        assertNull(homeIssuesLine(emptyList()))
        assertNull(homeIssuesLine(listOf(issue(IssueStatus.SENT))))
        assertEquals("1 open issue · 1 draft", homeIssuesLine(listOf(issue(IssueStatus.DRAFT), issue(IssueStatus.SENT))))
        assertEquals("2 open issues · 1 draft · 1 saved", homeIssuesLine(listOf(issue(IssueStatus.DRAFT), issue(IssueStatus.SAVED))))
    }

    @Test
    fun liveFinishedRunsWinOverStoredOnesAndOlderLiveRunsDoNot() {
        fun summary(id: String, suite: String, createdAt: Long) =
            RunSummary(id, "S", RunStatus.PASSED, createdAt, createdAt + 1, 1, 1, 1, 1, suiteId = suite)
        val stored = StoredLatestRuns(
            mapOf("a" to summary("old-a", "a", 1_000L), "b" to summary("b1", "b", 5_000L)),
            summary("b1", "b", 5_000L),
        )
        val live = listOf(summary("new-a", "a", 2_000L), summary("older-b", "b", 4_000L), summary("other", "zzz", 9_000L))

        val merged = mergeLatestRuns(stored, live, setOf("a", "b"))

        assertEquals("new-a", merged.bySuite.getValue("a").id)
        assertEquals("b1", merged.bySuite.getValue("b").id)
        assertEquals("other", merged.latestOverall?.id)
        assertNull(merged.bySuite["zzz"])
    }
}
