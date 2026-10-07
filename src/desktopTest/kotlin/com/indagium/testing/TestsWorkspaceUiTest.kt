package com.indagium.testing

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import com.indagium.edition.Edition
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
        rule.onAllNodesWithText("New suite")[0].performClick()
        rule.waitForIdle()

        assertEquals(listOf("New suite"), state.testLibrary.suites.map { it.name })
        rule.onNodeWithText("Suite name").assertExists()
        rule.onNodeWithText("CASES (0)").assertExists()
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
        rule.onNodeWithText("Case name").assertExists()
        rule.onNodeWithText("STEPS (0)").assertExists()

        rule.onNodeWithText("+ Add step").performClick()
        rule.waitForIdle()

        assertEquals(1, state.testLibrary.findCase(case.id)!!.case.steps.size)
        rule.onNodeWithText("STEPS (1)").assertExists()
        rule.onNodeWithText("Expected result").assertExists()
        rule.onNodeWithText("‹ Smoke").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("CASES (1)").assertExists()
    }

    @Test
    fun theLibrarySectionsAndPlaceholdersAreReachableFromTheNavigation() {
        state.createTestScript(TestScript("", "reset_app", commandTemplate = "echo hi"))
        rule.setContent { TestsWorkspace(state) }

        rule.onNodeWithText("Scripts").performClick()
        rule.waitForIdle()
        assertEquals(TestsNav.Scripts, state.testsView.nav)
        rule.onNodeWithText("Tool name").assertExists()
        rule.onNodeWithText("Try it".uppercase()).assertExists()

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

        rule.onNodeWithText("Agent profiles").performClick()
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
        rule.onNodeWithText("CASES (2)").assertExists()
        rule.onNodeWithText("Setup & teardown").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("SETUP HOOKS").assertExists()
        rule.onNodeWithText("Variables").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("VARIABLES").assertExists()
        state.testsView.selectedSuiteTab = SuiteTab.Cases
        rule.waitForIdle()

        state.testsView.selectedCaseId = case.id
        state.testsView.expandedStepId = case.steps.first().id
        rule.waitForIdle()
        rule.onNodeWithText("STEPS (2)").assertExists()
        rule.onNodeWithText("CHECKS (6)").assertExists()
        rule.onNodeWithText("EXAMPLES (2)").assertExists()
        rule.onNodeWithText("ALLOWED TOOLS").assertExists()
        rule.onNodeWithText("Golden screenshot").assertExists()
        assertTrue(rule.onAllNodesWithText("Reference log").fetchSemanticsNodes().isNotEmpty())

        state.testsView.nav = TestsNav.Scripts
        state.testsView.selectedScriptId = script.id
        rule.waitForIdle()
        rule.onNodeWithText("PARAMETERS").assertExists()
        rule.onNodeWithText("COMMAND").assertExists()

        state.testsView.nav = TestsNav.SharedSteps
        state.testsView.selectedSharedStepId = shared.id
        rule.waitForIdle()
        rule.onNodeWithText("STEPS (2)").assertExists()
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

        assertButtonDisabled("New suite")
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
}
