package com.indagium.testing

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import com.indagium.edition.Edition
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.newStepId
import com.indagium.testing.store.StoreResult
import com.indagium.ui.AppState
import com.indagium.ui.TestsNav
import com.indagium.ui.TestsWorkspace
import com.indagium.ui.stepAdvancedSummary
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The case screen's compact step rows and the step inspector, with their selection and keyboard rules, against a real AppState. */
@OptIn(ExperimentalTestApi::class)
class StepListInspectorUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val dir: File = createTempDirectory("step-list-ui").toFile()
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

    private fun threeSteps() = listOf(
        TestStep(newStepId(), "Open the app", expected = "Home is shown"),
        TestStep(newStepId(), "Tap search"),
        TestStep(newStepId(), "Type shoes"),
    )

    /** A suite "Shop" with one case holding [steps]; returns the suite and case ids. */
    private fun seed(steps: List<TestStep> = threeSteps()): Pair<String, String> {
        val suiteId = (state.createTestSuite("Shop") as StoreResult.Ok).value.id
        val caseId = (state.createTestCase(suiteId, TestCase("", "Search", steps = steps)) as StoreResult.Ok).value.id
        return suiteId to caseId
    }

    private fun stepIds(caseId: String) = state.testLibrary.findCase(caseId)!!.case.steps.map { it.id }

    private fun openCase(suiteId: String, caseId: String) {
        rule.setContent { TestsWorkspace(state) }
        state.testsView.selectedSuiteId = suiteId
        state.testsView.selectedCaseId = caseId
        rule.waitForIdle()
    }

    /** Clicks the entry [label] of the open step menu; the case screen has buttons with the same labels, so it is found by its column. */
    private fun clickMenuItem(label: String) {
        val menuLeft = rule.onNodeWithText("Move down").fetchSemanticsNode().boundsInRoot.left
        val index = rule.onAllNodesWithText(label).fetchSemanticsNodes().indexOfFirst { abs(it.boundsInRoot.left - menuLeft) < 1f }
        assertTrue(index >= 0, "$label should be in the open menu")
        rule.onAllNodesWithText(label)[index].performClick()
        rule.waitForIdle()
    }

    private fun pressAlt(key: Key) {
        rule.onRoot().performKeyInput {
            keyDown(Key.AltLeft)
            pressKey(key)
            keyUp(Key.AltLeft)
        }
        rule.waitForIdle()
    }

    @Test
    fun openingACaseSelectsItsFirstStepAndShowsItsEditorInTheInspector() {
        val (suiteId, caseId) = seed()
        openCase(suiteId, caseId)

        assertEquals(stepIds(caseId)[0], state.testsView.selectedStepId)
        rule.onNodeWithText("Step 1 of 3").assertExists()
        rule.onNodeWithText("Performed by").assertExists()
        rule.onNodeWithText("Expected result").assertExists()
        assertTrue(rule.onAllNodes(hasSetTextAction() and hasText("Home is shown")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aSelectionFromAnotherCaseFallsBackToTheFirstStep() {
        val (suiteId, caseId) = seed()
        state.testsView.selectedStepId = "a-step-of-some-other-case"
        openCase(suiteId, caseId)

        assertEquals(stepIds(caseId)[0], state.testsView.selectedStepId)
        rule.onNodeWithText("Step 1 of 3").assertExists()
    }

    @Test
    fun clickingARowSelectsItAndTheInspectorFollows() {
        val (suiteId, caseId) = seed()
        openCase(suiteId, caseId)

        rule.onNode(hasText("Type shoes", substring = true) and hasClickAction() and !hasSetTextAction()).performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals(stepIds(caseId)[2], state.testsView.selectedStepId)
        rule.onNodeWithText("Step 3 of 3").assertExists()
    }

    @Test
    fun arrowKeysChangeTheSelectedStepAndStayInsideTheList() {
        val (suiteId, caseId) = seed()
        val ids = stepIds(caseId)
        openCase(suiteId, caseId)

        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        assertEquals(ids[1], state.testsView.selectedStepId)
        rule.onNodeWithText("Step 2 of 3").assertExists()

        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()
        assertEquals(ids[0], state.testsView.selectedStepId)

        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()
        assertEquals(ids[0], state.testsView.selectedStepId, "clamped at the first step")

        repeat(4) { rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) } }
        rule.waitForIdle()
        assertEquals(ids[2], state.testsView.selectedStepId, "clamped at the last step")
    }

    @Test
    fun altDownMovesTheSelectedStepAndTheSelectionFollowsIt() {
        val (suiteId, caseId) = seed()
        val ids = stepIds(caseId)
        openCase(suiteId, caseId)

        pressAlt(Key.DirectionDown)

        assertEquals(listOf(ids[1], ids[0], ids[2]), stepIds(caseId))
        assertEquals(ids[0], state.testsView.selectedStepId)
        rule.onNodeWithText("Step 2 of 3").assertExists()

        pressAlt(Key.DirectionUp)
        assertEquals(ids, stepIds(caseId))
    }

    @Test
    fun aLockedCaseStillSelectsWithTheArrowsButAltDoesNotMove() {
        state.createTestSuite("First")
        val lockedSuiteId = (state.createTestSuite("Second") as StoreResult.Ok).value.id
        val caseId = (state.createTestCase(lockedSuiteId, TestCase("", "Locked", steps = threeSteps())) as StoreResult.Ok).value.id
        assertTrue(state.editionService.setForDev(Edition.FREE))
        val ids = stepIds(caseId)
        openCase(lockedSuiteId, caseId)

        pressAlt(Key.DirectionDown)
        assertEquals(ids, stepIds(caseId), "a read-only list is not reordered")

        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        assertEquals(ids[1], state.testsView.selectedStepId)
    }

    @Test
    fun theRowMenuOffersMoveDuplicateAndDeleteAndActsOnItsStep() {
        val (suiteId, caseId) = seed()
        val ids = stepIds(caseId)
        openCase(suiteId, caseId)

        rule.onNodeWithContentDescription("Step 2 actions").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Move up").assertExists()
        rule.onNodeWithText("Move down").assertExists()
        assertTrue(rule.onAllNodesWithText("Duplicate").fetchSemanticsNodes().size >= 2, "the menu's Duplicate beside the case's own")
        assertTrue(rule.onAllNodesWithText("Delete").fetchSemanticsNodes().size >= 2, "the menu's Delete beside the case's own")

        clickMenuItem("Move up")

        assertEquals(listOf(ids[1], ids[0], ids[2]), stepIds(caseId))
        assertEquals(ids[0], state.testsView.selectedStepId, "moving another row does not change the selection")
    }

    @Test
    fun duplicatingFromTheInspectorMenuAddsACopyAfterTheStep() {
        val (suiteId, caseId) = seed()
        openCase(suiteId, caseId)

        rule.onNodeWithContentDescription("More actions").performScrollTo().performClick()
        rule.waitForIdle()
        clickMenuItem("Duplicate")

        val steps = state.testLibrary.findCase(caseId)!!.case.steps
        assertEquals(listOf("Open the app", "Open the app", "Tap search", "Type shoes"), steps.map { it.action })
    }

    @Test
    fun deletingTheSelectedStepSelectsItsNeighbour() {
        val (suiteId, caseId) = seed()
        val ids = stepIds(caseId)
        openCase(suiteId, caseId)
        state.testsView.selectedStepId = ids[1]
        rule.waitForIdle()

        rule.onNodeWithContentDescription("More actions").performScrollTo().performClick()
        rule.waitForIdle()
        clickMenuItem("Delete")

        assertEquals(listOf(ids[0], ids[2]), stepIds(caseId))
        assertEquals(ids[2], state.testsView.selectedStepId, "the step after the deleted one")
        rule.onNodeWithText("Step 2 of 2").assertExists()
    }

    @Test
    fun addingAStepSelectsItAndPutsTheCursorInItsActionField() {
        val (suiteId, caseId) = seed()
        openCase(suiteId, caseId)

        rule.onNodeWithText("+ Add step").performClick()
        rule.waitForIdle()

        val ids = stepIds(caseId)
        assertEquals(4, ids.size)
        assertEquals(ids.last(), state.testsView.selectedStepId)
        rule.onNodeWithText("Step 4 of 4").assertExists()
        assertTrue(rule.onAllNodes(hasSetTextAction() and hasText("New step")).fetchSemanticsNodes().isNotEmpty())
        assertEquals(null, state.testsView.focusStepActionId, "the request is consumed")
    }

    @Test
    fun advancedIsCollapsedWithASummaryUntilItIsOpened() {
        val (suiteId, caseId) = seed()
        openCase(suiteId, caseId)
        val step = state.testLibrary.findCase(caseId)!!.case.steps.first()

        rule.onNodeWithText("Advanced").assertExists()
        rule.onNodeWithText(stepAdvancedSummary(step)).assertExists()
        rule.onNodeWithText("Retries").assertDoesNotExist()
        rule.onNodeWithText("Settings").assertDoesNotExist()

        rule.onNodeWithText("Advanced").performScrollTo().performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Retries").assertExists()
        rule.onNodeWithText("Max tool calls").assertExists()
        rule.onNodeWithText(stepAdvancedSummary(step)).assertDoesNotExist()
    }

    @Test
    fun anEditInTheInspectorChangesTheStepAndItsRow() {
        val (suiteId, caseId) = seed()
        openCase(suiteId, caseId)

        rule.onAllNodes(hasSetTextAction() and hasText("Open the app"))[0].performTextReplacement("Launch the app")
        rule.waitUntil(timeoutMillis = 5_000) { state.testLibrary.findCase(caseId)!!.case.steps[0].action == "Launch the app" }
        rule.waitForIdle()

        rule.onNode(hasText("Launch the app", substring = true) and hasClickAction() and !hasSetTextAction()).assertExists()
    }

    @Test
    fun sharedStepsShowTheSameRowsWithTheSelectedStepsEditorUnderneath() {
        val shared = SharedStep(id = "", name = "Log in", steps = listOf(TestStep(newStepId(), "Enter the PIN"), TestStep(newStepId(), "Confirm")))
        val created = (state.createSharedStep(shared) as StoreResult.Ok).value
        rule.setContent { TestsWorkspace(state) }
        state.testsView.nav = TestsNav.SharedSteps
        state.testsView.selectedSharedStepId = created.id
        rule.waitForIdle()

        rule.onNodeWithText("Steps (2)").assertExists()
        rule.onNodeWithText("Step 1 of 2").assertExists()
        assertEquals(created.steps[0].id, state.testsView.selectedStepId)

        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithText("Step 2 of 2").assertExists()

        rule.onAllNodes(hasSetTextAction() and hasText("Confirm"))[0].performTextReplacement("Confirm with OK")
        rule.waitUntil(timeoutMillis = 5_000) { state.testLibrary.sharedSteps.single().steps[1].action == "Confirm with OK" }

        // Typing left the cursor in the field, which owns the arrow keys; clicking the row hands them back.
        rule.onNode(hasText("Confirm with OK", substring = true) and hasClickAction() and !hasSetTextAction()).performScrollTo().performClick()
        pressAlt(Key.DirectionUp)
        assertEquals(listOf("Confirm with OK", "Enter the PIN"), state.testLibrary.sharedSteps.single().steps.map { it.action })
    }
}
