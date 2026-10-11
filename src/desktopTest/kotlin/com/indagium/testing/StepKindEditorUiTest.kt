package com.indagium.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.indagium.testing.model.DEFAULT_MANUAL_STEP_TIMEOUT_MS
import com.indagium.testing.model.DEFAULT_STEP_TIMEOUT_MS
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestScript
import com.indagium.testing.store.StoreResult
import com.indagium.ui.AppState
import com.indagium.ui.TestsNav
import com.indagium.ui.TestsWorkspace
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The step editor's kind selector composed against a real AppState: manual and script steps, and the trip to the script editor and back. */
@OptIn(ExperimentalTestApi::class)
class StepKindEditorUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val dir: File = createTempDirectory("step-kind-ui").toFile()
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

    private fun openNewStep(): String {
        val suite = (state.createTestSuite("Hub") as StoreResult.Ok).value
        val case = (state.createTestCase(suite.id, TestCase("", "Plug")) as StoreResult.Ok).value
        rule.setContent { TestsWorkspace(state) }
        state.testsView.selectedSuiteId = suite.id
        state.testsView.selectedCaseId = case.id
        rule.waitForIdle()
        rule.onNodeWithText("+ Add step").performClick()
        rule.waitForIdle()
        return case.id
    }

    /** The inspector's Advanced section starts collapsed. */
    private fun openAdvanced() {
        rule.onNodeWithText("Advanced").performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun stepOf(caseId: String) = state.testLibrary.findCase(caseId)!!.case.steps.single()

    @Test
    fun choosingManualMakesATesterStepWithTheLongWaitAndItsCompletionControls() {
        val caseId = openNewStep()
        rule.onNodeWithText("Performed by").assertExists()

        rule.onNodeWithText("Manual").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()

        val step = stepOf(caseId)
        assertEquals(StepKind.MANUAL, step.kind)
        assertEquals(DEFAULT_MANUAL_STEP_TIMEOUT_MS, step.timeoutMs)
        rule.onNodeWithText("Instructions for the tester").assertExists()
        rule.onNodeWithText("Completed by").assertExists()
        rule.onNodeWithText("Automation script (optional)").assertExists()
        openAdvanced()
        rule.onNodeWithText("Wait up to (ms)").assertExists()
    }

    @Test
    fun choosingScriptPicksTheFirstUsableScriptAndShowsItsExpectations() {
        state.createTestScript(TestScript("", "only_setup", commandTemplate = "true", permission = ScriptPermission.SETUP_TEARDOWN_ONLY))
        val usable = (state.createTestScript(TestScript("", "hub_on", commandTemplate = "true", permission = ScriptPermission.AUTO)) as StoreResult.Ok).value
        val caseId = openNewStep()

        rule.onNodeWithText("Script").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()

        val step = stepOf(caseId)
        assertEquals(StepKind.SCRIPT, step.kind)
        assertEquals(usable.id, step.script?.scriptId, "a script limited to setup and teardown is not offered to a step")
        rule.onNodeWithText("hub_on").assertExists()
        rule.onNodeWithText("Expected exit code").assertExists()
        rule.onNodeWithText("Output must contain").assertExists()
    }

    @Test
    fun aScriptStepWithoutAnyScriptCreatesOneOpensTheScriptEditorAndLeadsBack() {
        val caseId = openNewStep()
        val stepId = stepOf(caseId).id

        rule.onNodeWithText("Script").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()

        val created = state.testLibrary.scripts.single()
        assertEquals(created.id, stepOf(caseId).script?.scriptId, "the new script is already the step's")
        assertEquals(TestsNav.Scripts, state.testsView.nav)
        assertEquals(created.id, state.testsView.selectedScriptId)
        rule.onNodeWithText("Back to the step").assertExists()

        rule.onNodeWithText("Back to the step").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()

        assertEquals(TestsNav.Suites, state.testsView.nav)
        assertEquals(stepId, state.testsView.selectedStepId)
        assertNull(state.testsView.scriptReturn)
        rule.onNodeWithText("Script to run").assertExists()
        assertNotNull(state.testLibrary.script(created.id))
    }

    @Test
    fun backToTheAiStepRestoresTheEditorAndTheDefaultTimeout() {
        val caseId = openNewStep()
        rule.onNodeWithText("Manual").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()

        rule.onNodeWithText("AI step").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()

        val step = stepOf(caseId)
        assertEquals(StepKind.AGENT, step.kind)
        assertEquals(DEFAULT_STEP_TIMEOUT_MS, step.timeoutMs)
        openAdvanced()
        rule.onNodeWithText("Max tool calls").assertExists()
    }
}
