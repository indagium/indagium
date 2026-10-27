package com.indagium.testing

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.indagium.testing.authoring.testScriptUsageReferences
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.newExampleId
import com.indagium.testing.model.newStepId
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.encodeSuiteFile
import com.indagium.ui.AppState
import com.indagium.ui.TestsNav
import com.indagium.ui.TestsWorkspace
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Scripts, shared steps and examples for agents across the Workspace, Details and Evidence tiles. */
@OptIn(ExperimentalTestApi::class)
class TestsLibraryScreensUiTest {
    private companion object {
        const val MENU_REOPEN_WAIT_MS = 300L
    }

    @get:Rule
    val rule = createComposeRule()

    private val dir: File = createTempDirectory("tests-library-ui").toFile()
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

    private fun open(nav: TestsNav) {
        rule.setContent { TestsWorkspace(state) }
        state.testsView.nav = nav
        rule.waitForIdle()
    }

    private fun inTile(tile: String, text: String) = rule.onAllNodes(
        hasText(text) and hasAnyAncestor(hasTestTag("tests-tile-card-$tile")),
    ).fetchSemanticsNodes()

    /** A closed menu ignores a click on its anchor for a moment (the click that dismissed it also lands there). */
    private fun reopenMenu(description: String) {
        Thread.sleep(MENU_REOPEN_WAIT_MS)
        rule.onNodeWithContentDescription(description).performClick()
        rule.waitForIdle()
    }

    // ── Scripts ──────────────────────────────────────────────────────

    @Test
    fun theScriptsScreenListsScriptsAndEditsTheSelectedOneWithItsPanelsInTheInspector() {
        val script = sampleScript()
        state.createTestScript(script)
        check(state.importTestSuite(encodeSuiteFile(fullSuite(script, sampleSharedStep()))) is StoreResult.Ok)
        val uses = testScriptUsageReferences(state.testLibrary, script.id).size
        assertTrue(uses > 0, "the suite should use the script")
        open(TestsNav.Scripts)

        rule.onNodeWithText("Scripts (1)").assertExists()
        rule.onNodeWithText("+ New script").assertExists()
        rule.onNodeWithContentDescription("Script list actions").assertExists()
        // The editor: the tool name is the title, then the fields.
        rule.onNodeWithContentDescription("Tool name").assertExists()
        rule.onNodeWithText("Description (shown to the agent)").assertExists()
        rule.onNodeWithText("Parameters").assertExists()
        rule.onNodeWithText("Command").assertExists()
        assertTrue(inTile("workspace", "Settings").isNotEmpty(), "the script editor stays in Workspace")
        rule.onNodeWithText("Runs on").assertExists()
        rule.onNodeWithText("Who may start it").assertExists()
        rule.onNodeWithText("Timeout (ms)").assertExists()
        rule.onNodeWithText("Output cap (bytes)").assertExists()
        rule.onNodeWithText("Working directory (optional)").assertExists()
        // The old stand-alone buttons are in the header menu now.
        rule.onNodeWithText("Delete script").assertDoesNotExist()
        // Details keeps Try it, Tool schema (collapsed) and Used in N, each with its title inside its card.
        rule.onNodeWithText("Try it").assertExists()
        rule.onNodeWithText("Tool schema").assertExists()
        rule.onNodeWithText("Used in $uses").assertExists()
        rule.onNodeWithContentDescription("Hide Tool schema").assertDoesNotExist()
        rule.onNodeWithContentDescription("Show Tool schema").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Hide Tool schema").assertExists()
        rule.onNodeWithContentDescription("Show Used in $uses").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Hide Used in $uses").assertExists()
    }

    @Test
    fun theScriptHeaderMenuHoldsDuplicateImportExportAndDeleteWhichAsksFirst() {
        val script = sampleScript()
        state.createTestScript(script)
        open(TestsNav.Scripts)

        rule.onNodeWithContentDescription("More script actions").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Duplicate").assertExists()
        rule.onNodeWithText("Import JSON…").assertExists()
        rule.onNodeWithText("Export JSON…").assertExists()
        rule.onNodeWithText("Delete…").assertExists()

        rule.onNodeWithText("Duplicate").performClick()
        rule.waitForIdle()
        assertEquals(2, state.testLibrary.scripts.size)
        rule.onNodeWithText("Scripts (2)").assertExists()

        val selected = state.testsView.selectedScriptId
        reopenMenu("More script actions")
        rule.onNodeWithText("Delete…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete script?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals(2, state.testLibrary.scripts.size, "cancelling keeps the script")

        reopenMenu("More script actions")
        rule.onNodeWithText("Delete…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        assertEquals(1, state.testLibrary.scripts.size)
        assertTrue(state.testLibrary.scripts.none { it.id == selected })
    }

    @Test
    fun aNewScriptIsAddedFromTheListHeaderAndSelected() {
        open(TestsNav.Scripts)

        rule.onNodeWithText("Scripts (0)").assertExists()
        rule.onNodeWithText("+ New script").performClick()
        rule.waitForIdle()

        val created = state.testLibrary.scripts.single()
        assertEquals(created.id, state.testsView.selectedScriptId)
        rule.onNodeWithText("Scripts (1)").assertExists()
        rule.onNodeWithContentDescription("Tool name").assertExists()
        rule.onNodeWithText("Try it").assertExists()
    }

    @Test
    fun aNewScriptCanBeDeletedDirectlyFromItsListRow() {
        open(TestsNav.Scripts)
        rule.onNodeWithText("+ New script").performClick()
        rule.waitForIdle()
        val created = state.testLibrary.scripts.single()
        val menu = "${created.toolName} actions"

        rule.onNodeWithContentDescription(menu).performClick()
        rule.onNodeWithText("Delete…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete script?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals(created.id, state.testLibrary.scripts.single().id)

        reopenMenu(menu)
        rule.onNodeWithText("Delete…").performClick()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        assertTrue(state.testLibrary.scripts.isEmpty())
        rule.onNodeWithText("Scripts (0)").assertExists()
    }

    @Test
    fun deletingAnotherRowKeepsTheSelectedScriptAndTargetsTheRequestedRow() {
        val selected = sampleScript("keep_script")
        val target = sampleScript("delete_script")
        state.createTestScript(selected)
        state.createTestScript(target)
        state.testsView.selectedScriptId = selected.id
        open(TestsNav.Scripts)

        rule.onNodeWithContentDescription("${target.toolName} actions").performClick()
        rule.onNodeWithText("Delete…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("\"delete_script\" will be deleted.").assertExists()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        assertEquals(selected.id, state.testLibrary.scripts.single().id)
        assertEquals(selected.id, state.testsView.selectedScriptId)
    }

    // ── Shared steps ─────────────────────────────────────────────────

    @Test
    fun anEmptySharedStepsLibraryShowsACentredEmptyStateWithTheNewButton() {
        open(TestsNav.SharedSteps)

        listOf("suites", "library", "activity", "workspace", "execution", "details", "evidence").forEach { key ->
            rule.onNodeWithTag("tests-tile-card-$key").assertExists()
        }
        rule.onNodeWithText("No shared steps yet").assertExists()
        rule.onNodeWithText("A shared step is a reusable sequence of steps. Suite and case hooks can run it before or after a case.").assertExists()
        val workspace = rule.onNodeWithTag("tests-tile-card-workspace").fetchSemanticsNode().boundsInRoot
        val title = rule.onNodeWithText("No shared steps yet").fetchSemanticsNode().boundsInRoot
        val actions = rule.onNodeWithTag("tests-workspace-actions").fetchSemanticsNode().boundsInRoot
        val create = rule.onNodeWithText("+ New shared step").fetchSemanticsNode().boundsInRoot
        assertTrue(
            abs(title.center.x - workspace.center.x) < workspace.width * 0.1f,
            "empty state is centred inside Workspace: ${title.center.x} vs ${workspace.center.x}",
        )
        assertTrue(create.top >= actions.top && create.bottom <= actions.bottom, "creation stays in the fixed Workspace header: $create vs $actions")
        assertEquals(1, rule.onAllNodesWithText("+ New shared step").fetchSemanticsNodes().size, "one create action is shown")

        rule.onNodeWithText("+ New shared step").performClick()
        rule.waitForIdle()
        assertEquals(1, state.testLibrary.sharedSteps.size)
        rule.onNodeWithText("Shared steps (1)").assertExists()
        rule.onNodeWithContentDescription("Shared step name").assertExists()
    }

    @Test
    fun theSelectedSharedStepsStepIsEditedInTheInspectorAndTheHeaderMenuDeletes() {
        val shared = SharedStep("", "Log in", steps = listOf(TestStep(newStepId(), "Enter the PIN"), TestStep(newStepId(), "Confirm")))
        val created = (state.createSharedStep(shared) as StoreResult.Ok).value
        open(TestsNav.SharedSteps)

        rule.onNodeWithText("Shared steps (1)").assertExists()
        rule.onNodeWithText("Steps (2)").assertExists()
        rule.onNodeWithText("Step 1 of 2").assertExists()
        // Selecting another step switches the selected step in Details.
        rule.onNodeWithText("Confirm", useUnmergedTree = false).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(created.steps[1].id, state.testsView.selectedStepId)
        rule.onNodeWithText("Step 2 of 2").assertExists()
        // The old inline "Delete shared step" button is the header menu's Delete… now.
        rule.onNodeWithText("Delete shared step").assertDoesNotExist()

        rule.onNodeWithContentDescription("More shared step actions").performClick()
        rule.onNodeWithText("Delete…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete shared step?").assertExists()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        assertTrue(state.testLibrary.sharedSteps.isEmpty())
        rule.onNodeWithText("No shared steps yet").assertExists()
    }

    // ── Examples for agents ──────────────────────────────────────────

    private val logLines = (1..12).joinToString("\n") { "line $it of the reference log" }

    private fun seedExamples(): Triple<String, String, TestStep> {
        val suiteId = (state.createTestSuite("Shop") as StoreResult.Ok).value.id
        val first = TestStep(
            newStepId(), "Open the cart",
            examples = listOf(StepExample.ReferenceLog(newExampleId(), logLines, "Cart opened")),
        )
        val second = TestStep(
            newStepId(), "Pay",
            examples = listOf(StepExample.ReferenceLog(newExampleId(), "payment accepted", "")),
        )
        val caseId = (state.createTestCase(suiteId, TestCase("", "Checkout", steps = listOf(first, second))) as StoreResult.Ok).value.id
        return Triple(suiteId, caseId, first)
    }

    @Test
    fun examplesAreGroupedUnderTheirCaseWithTwoLinePreviewsAndTheFullTextInTheInspector() {
        seedExamples()
        open(TestsNav.AgentExamples)

        assertTrue(inTile("workspace", "Examples for agents").isNotEmpty())
        rule.onNode(hasText("Shop › Checkout") and hasAnyAncestor(hasTestTag("tests-tile-card-workspace"))).assertExists()
        rule.onNodeWithText("No step examples yet. Add a golden screenshot or reference log in a step editor.").assertDoesNotExist()
        // The row preview remains in Workspace; the full log is in Evidence, and the caption is in Details.
        val preview = rule.onNode(
            hasText("line 1 of the reference log", substring = true) and hasAnyAncestor(hasTestTag("tests-tile-card-workspace")),
        ).fetchSemanticsNode().boundsInRoot
        val evidence = rule.onNode(
            hasText("line 1 of the reference log", substring = true) and hasAnyAncestor(hasTestTag("tests-tile-card-evidence")),
        ).fetchSemanticsNode().boundsInRoot
        assertTrue(preview.height < evidence.height, "the Workspace preview is clamped while Evidence shows the full log: $preview vs $evidence")
        assertTrue(inTile("workspace", "Cart opened").isNotEmpty())
        assertTrue(inTile("details", "Cart opened").isNotEmpty())
        rule.onNode(hasText("Open step") and hasAnyAncestor(hasTestTag("tests-tile-card-details"))).assertExists()

        // Selecting the second example updates Details and Evidence; with no caption, its row uses the step's action.
        rule.onNodeWithText("Pay").assertExists()
        rule.onNode(
            hasText("payment accepted") and hasAnyAncestor(hasTestTag("tests-tile-card-workspace")),
        ).performClick()
        rule.waitForIdle()
        assertTrue(inTile("workspace", "payment accepted").isNotEmpty())
        assertTrue(inTile("evidence", "payment accepted").isNotEmpty())
        assertTrue(inTile("evidence", "line 1 of the reference log").isEmpty(), "selection clears the prior Evidence content")
    }

    @Test
    fun openStepLeadsToTheStepWithItsExamplesExpanded() {
        val (suiteId, caseId, first) = seedExamples()
        open(TestsNav.AgentExamples)

        rule.onNodeWithText("Open step").performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals(TestsNav.Suites, state.testsView.nav)
        assertEquals(suiteId, state.testsView.selectedSuiteId)
        assertEquals(caseId, state.testsView.selectedCaseId)
        assertEquals(first.id, state.testsView.selectedStepId)
        assertTrue(state.testsView.examplesExpanded(first.id, hasExamples = false), "an explicit open wins over the default")
    }

    @Test
    fun withNoExamplesTheScreenKeepsItsEmptyText() {
        open(TestsNav.AgentExamples)

        assertTrue(inTile("workspace", "Examples for agents").isNotEmpty())
        rule.onNodeWithText("No step examples yet. Add a golden screenshot or reference log in a step editor.").assertExists()
        rule.onNodeWithText("Open step").assertDoesNotExist()
    }
}
