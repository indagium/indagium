package com.indagium.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import com.indagium.edition.Edition
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestScript
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.encodeSuiteFile
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
import kotlin.test.assertTrue

/** The whole Tests workspace composed against a real AppState: navigation, creation and the Free-edition locked state. */
@OptIn(ExperimentalTestApi::class)
class TestsWorkspaceUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val dir: File = createTempDirectory("tests-workspace-ui").toFile()
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

    /** A disabled AppButton is a non-clickable node with the label as a child, so it is found through its descendant. */
    private fun assertButtonDisabled(label: String) {
        assertTrue(rule.onAllNodes(isNotEnabled() and hasAnyDescendant(hasText(label))).fetchSemanticsNodes().isNotEmpty(), "$label should be disabled")
    }

    @After
    fun tearDown() {
        state.close()
        dir.deleteRecursively()
    }

    @Test
    fun anEmptyLibraryOffersANewSuiteWhichThenOpensItsScreen() {
        rule.setContent { TestsWorkspace(state) }

        rule.onNodeWithText("No test suites yet. A suite groups the cases an AI agent runs against your app.").assertExists()
        rule.onNodeWithContentDescription("New suite").performClick()
        rule.waitForIdle()

        assertEquals(listOf("New suite"), state.testLibrary.suites.map { it.name })
        rule.onNodeWithText("Suite name").assertExists()
        rule.onNodeWithText("Cases (0)").assertExists()
    }

    @Test
    fun aSuiteScreenCreatesACaseOpensItAndAddsAStep() {
        val suite = (state.createTestSuite("Smoke") as StoreResult.Ok).value
        rule.setContent { TestsWorkspace(state) }
        state.testsView.selectedSuiteId = suite.id

        rule.onNodeWithText("New case").performClick()
        rule.waitForIdle()
        val case = state.testLibrary.suite(suite.id)!!.cases.single()
        assertEquals(case.id, state.testsView.selectedCaseId)
        rule.onNodeWithContentDescription("Case name").assertExists()
        rule.onNodeWithText("Steps (0)").assertExists()

        rule.onNodeWithText("+ Add step").performClick()
        rule.waitForIdle()

        assertEquals(1, state.testLibrary.findCase(case.id)!!.case.steps.size)
        rule.onNodeWithText("Steps (1)").assertExists()
        rule.onNodeWithText("Expected result").assertExists()
        // Adding a step puts the cursor in its Action field, which may scroll the page (the inspector stacks under the list here).
        rule.onNodeWithContentDescription("Back to suite Smoke").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Cases (1)").assertExists()
    }

    @Test
    fun theLibrarySectionsAndPlaceholdersAreReachableFromTheNavigation() {
        state.createTestScript(TestScript("", "reset_app", commandTemplate = "echo hi"))
        rule.setContent { TestsWorkspace(state) }

        rule.onNodeWithText("Scripts").performClick()
        rule.waitForIdle()
        assertEquals(TestsNav.Scripts, state.testsView.nav)
        rule.onNodeWithText("Tool name").assertExists()
        rule.onNodeWithText("Try it").assertExists()

        rule.onNodeWithText("Shared steps").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("New shared step").assertExists()

        // Runs and Issues are real screens now (the run list and the issue list).
        rule.onNodeWithText("Runs").performClick()
        rule.waitForIdle()
        assertEquals(TestsNav.Runs, state.testsView.nav)
        rule.onNodeWithText("No runs yet.").assertExists()
        rule.onNodeWithText("Issues").performClick()
        rule.waitForIdle()
        assertEquals(TestsNav.Issues, state.testsView.nav)
        rule.onNodeWithText("No issues yet.").assertExists()

        rule.onNodeWithText("Examples for agents").performClick()
        rule.waitForIdle()
        assertEquals(TestsNav.AgentExamples, state.testsView.nav)
        rule.onNodeWithText("No step examples yet. Add a golden screenshot or reference log in a step editor.").assertExists()

        rule.onNodeWithContentDescription("More suite actions").performClick()
        rule.onNodeWithText("Agent profiles…").performClick()
        rule.waitForIdle()
        assertTrue(state.settingsOpen)
        assertEquals(com.indagium.ui.SettingsSection.AiProviders, state.requestedSettingsSection)
    }

    @Test
    fun aFullSuiteComposesTheSuiteCaseStepAndLibraryEditorsWithoutErrors() {
        val script = sampleScript()
        val shared = sampleSharedStep()
        state.createTestScript(script)
        state.createSharedStep(shared)
        val suite = (state.importTestSuite(encodeSuiteFile(fullSuite(script, shared))) as StoreResult.Ok).value
        val case = suite.cases.first()
        rule.setContent { TestsWorkspace(state) }
        state.testsView.selectedSuiteId = suite.id

        rule.waitForIdle()
        rule.onNodeWithText("Cases (2)").assertExists()
        rule.onNodeWithText("Setup & teardown").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Setup hooks").assertExists()
        rule.onNodeWithText("Variables").performClick()
        rule.waitForIdle()
        // The tab label and the section title it opened.
        assertTrue(rule.onAllNodesWithText("Variables").fetchSemanticsNodes().size >= 2, "Variables section should be shown")
        state.testsView.selectedSuiteTab = SuiteTab.Cases
        rule.waitForIdle()

        state.testsView.selectedCaseId = case.id
        state.testsView.selectedStepId = case.steps.first().id
        rule.waitForIdle()
        rule.onNodeWithText("Steps (2)").assertExists()
        rule.onNodeWithText("Checks (6)").assertExists()
        rule.onNodeWithText("Examples (2)").assertExists()
        // Allowed tools live behind the Tools chip: closed until it is clicked.
        rule.onNodeWithText("Allowed tools").assertDoesNotExist()
        rule.onNodeWithText("Tools").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Allowed tools").assertExists()
        state.testsView.caseInspectorField = null
        rule.waitForIdle()
        // The inspector's Examples start collapsed; opening them shows the editors.
        rule.onNodeWithText("Golden screenshot").assertDoesNotExist()
        rule.onNodeWithText("Examples (2)").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Golden screenshot").assertExists()
        assertTrue(rule.onAllNodesWithText("Reference log").fetchSemanticsNodes().isNotEmpty())

        state.testsView.nav = TestsNav.Scripts
        state.testsView.selectedScriptId = script.id
        rule.waitForIdle()
        rule.onNodeWithText("Parameters").assertExists()
        rule.onNodeWithText("Command").assertExists()

        state.testsView.nav = TestsNav.SharedSteps
        state.testsView.selectedSharedStepId = shared.id
        rule.waitForIdle()
        rule.onNodeWithText("Steps (2)").assertExists()
    }

    @Test
    fun editingAFieldCommitsAfterTheDebounceAndAStoreRefusalShowsUnderTheField() {
        val suite = (state.createTestSuite("Smoke") as StoreResult.Ok).value
        rule.setContent { TestsWorkspace(state) }
        state.testsView.selectedSuiteId = suite.id
        rule.waitForIdle()

        rule.onAllNodes(hasSetTextAction() and hasText("Smoke"))[0].performTextReplacement("Renamed")
        rule.waitUntil(timeoutMillis = 5_000) { state.testLibrary.suite(suite.id)!!.name == "Renamed" }

        rule.onAllNodes(hasSetTextAction() and hasText("Renamed"))[0].performTextReplacement("")
        rule.waitUntilAtLeastOneExists(hasText("Suite name must not be blank."), timeoutMillis = 5_000)
        assertEquals("Renamed", state.testLibrary.suite(suite.id)!!.name, "a refused edit leaves the stored value alone")
    }

    @Test
    fun underTheFreeEditionALockedSuiteIsReadOnlyButStillDeletableAndNewCaseIsDisabled() {
        state.createTestSuite("First")
        val second = (state.createTestSuite("Second") as StoreResult.Ok).value
        assertTrue(state.editionService.setForDev(Edition.FREE))
        rule.setContent { TestsWorkspace(state) }

        rule.onNodeWithContentDescription("New suite").assertIsNotEnabled()
        rule.onAllNodesWithText("Second")[0].performClick()
        rule.waitForIdle()

        assertEquals(second.id, state.testsView.selectedSuiteId)
        assertTrue(rule.onAllNodesWithText("This suite is locked.", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertButtonDisabled("New case")
        assertButtonDisabled("Duplicate")
        assertButtonDisabled("Run suite…")
        rule.onAllNodesWithText("Delete")[0].assertIsEnabled()
        rule.onNodeWithText("Export…").assertIsEnabled()
        rule.onAllNodesWithText("Delete")[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete suite?").assertExists()
        rule.onAllNodesWithText("Delete")[1].performClick()
        rule.waitUntil(timeoutMillis = 5_000) { state.testLibrary.suite(second.id) == null }
    }

    /** The nodes with [text] inside the nav column (the centre screen can show the same names, e.g. a suite's case table). */
    private fun navNodes(text: String) =
        rule.onAllNodesWithText(text).fetchSemanticsNodes().filter { it.boundsInRoot.right <= state.testsView.navWidthDp }

    private fun clickNav(text: String) {
        val index = rule.onAllNodesWithText(text).fetchSemanticsNodes().indexOfFirst { it.boundsInRoot.right <= state.testsView.navWidthDp }
        assertTrue(index >= 0, "$text should be in the nav")
        rule.onAllNodesWithText(text)[index].performClick()
    }

    private fun libraryWithTwoSuites(): Triple<String, String, String> {
        val alpha = (state.createTestSuite("Alpha") as StoreResult.Ok).value
        val beta = (state.createTestSuite("Beta") as StoreResult.Ok).value
        state.createTestCase(alpha.id, TestCase("", "Login flow"))
        state.createTestCase(alpha.id, TestCase("", "Checkout"))
        val tour = (state.createTestCase(beta.id, TestCase("", "Settings tour")) as StoreResult.Ok).value
        return Triple(alpha.id, beta.id, tour.id)
    }

    @Test
    fun expandingASuiteInTheTreeShowsItsCasesAndOpeningOneShowsTheCaseScreen() {
        val (alphaId, betaId, tourId) = libraryWithTwoSuites()
        rule.setContent { TestsWorkspace(state) }
        rule.waitForIdle()

        // The suite the workspace is on opens itself; the other stays collapsed.
        assertTrue(navNodes("Login flow").isNotEmpty())
        assertTrue(navNodes("Checkout").isNotEmpty())
        assertTrue(navNodes("Settings tour").isEmpty())
        assertTrue(alphaId in state.testsView.expandedSuiteIds)

        rule.onNodeWithContentDescription("Expand Beta").performClick()
        rule.waitForIdle()
        assertTrue(navNodes("Settings tour").isNotEmpty())
        assertTrue(betaId in state.testsView.expandedSuiteIds)
        assertEquals(null, state.testsView.selectedCaseId, "the caret only expands, it does not open the suite")

        clickNav("Settings tour")
        rule.waitForIdle()
        assertEquals(betaId, state.testsView.selectedSuiteId)
        assertEquals(tourId, state.testsView.selectedCaseId)
        rule.onNodeWithContentDescription("Case name").assertExists()

        rule.onNodeWithContentDescription("Collapse Beta").performClick()
        rule.waitForIdle()
        assertTrue(navNodes("Settings tour").isEmpty())
    }

    @Test
    fun theSearchFindsACaseUnderItsSuiteAndOpensIt() {
        val (_, betaId, tourId) = libraryWithTwoSuites()
        rule.setContent { TestsWorkspace(state) }
        rule.waitForIdle()
        assertTrue(navNodes("Settings tour").isEmpty())

        state.testsView.search = "tour"
        rule.waitForIdle()
        // Beta is collapsed, yet the matching case shows (its suite opens for the search); other cases are filtered out.
        assertTrue(navNodes("Settings tour").isNotEmpty())
        assertTrue(navNodes("Checkout").isEmpty())
        clickNav("Settings tour")
        rule.waitForIdle()
        assertEquals(betaId, state.testsView.selectedSuiteId)
        assertEquals(tourId, state.testsView.selectedCaseId)

        state.testsView.search = "nothing like this"
        rule.waitForIdle()
        rule.onNodeWithText("No suite or case matches the search.").assertExists()
    }

    @Test
    fun thePlusButtonCreatesAndOpensASuiteInAnExistingLibrary() {
        libraryWithTwoSuites()
        rule.setContent { TestsWorkspace(state) }
        rule.waitForIdle()

        rule.onNodeWithContentDescription("New suite").assertIsEnabled().performClick()
        rule.waitForIdle()

        val created = state.testLibrary.suites.last()
        assertEquals("New suite", created.name)
        assertEquals(created.id, state.testsView.selectedSuiteId)
        assertEquals(TestsNav.Suites, state.testsView.nav)
    }

    @Test
    fun theSuitesMenuOffersImportAndAgentProfiles() {
        rule.setContent { TestsWorkspace(state) }
        rule.waitForIdle()

        rule.onNodeWithText("Import suite…").assertDoesNotExist()
        rule.onNodeWithContentDescription("More suite actions").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Import suite…").assertExists()
        rule.onNodeWithText("Agent profiles…").assertExists()
    }

    @Test
    fun theEditionBadgeAndCaseCountLiveInTheNavFooter() {
        libraryWithTwoSuites()
        rule.setContent { TestsWorkspace(state) }
        rule.waitForIdle()

        rule.onNodeWithText(state.editionService.current.value.label).assertExists()
        rule.onNodeWithText("3 cases").assertExists()
        rule.onNodeWithText("AI test suites").assertDoesNotExist()
    }

    @Test
    fun aNarrowWindowHidesTheTreeBehindAToggleAndClosesItAfterAChoice() {
        rule.setContent { Box(Modifier.size(500.dp, 700.dp)) { TestsWorkspace(state) } }
        rule.waitForIdle()
        rule.onNodeWithText("Scripts").assertDoesNotExist()

        rule.onNodeWithContentDescription("Show navigation").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Scripts").assertExists()
        rule.onNodeWithText("Scripts").performClick()
        rule.waitForIdle()

        assertEquals(TestsNav.Scripts, state.testsView.nav)
        rule.onNodeWithText("Shared steps").assertDoesNotExist()
    }
}
