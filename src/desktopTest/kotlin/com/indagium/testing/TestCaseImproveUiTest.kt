package com.indagium.testing

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import com.indagium.ai.LlmStreamEvent
import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.testing.model.TestCase
import com.indagium.testing.run.LaneAgentFactory
import com.indagium.testing.run.ProviderLaneAgent
import com.indagium.testing.store.StoreResult
import com.indagium.ui.AppState
import com.indagium.ui.ImproveStepsTarget
import com.indagium.ui.TestRunOverrides
import com.indagium.ui.TestsWorkspace
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WAIT_MS = 15_000L

/** The improve dialog composed against a real AppState and a scripted model: ask, review, apply. */
@OptIn(ExperimentalTestApi::class)
class TestCaseImproveUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val dir: File = createTempDirectory("improve-ui").toFile()
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

    private fun seed(): String {
        val profile = AiProviderProfile(IMPROVE_PROFILE_ID, "Improve model", "http://127.0.0.1:1/v1", "base-model", kind = AiProviderKind.OPENAI_COMPATIBLE)
        state.updateSettings { it.copy(aiProviderProfiles = listOf(profile)) }
        check(state.createTestScript(improveScript()) is StoreResult.Ok)
        val suiteId = (state.createTestSuite("Shop suite") as StoreResult.Ok).value.id
        check(state.createTestCase(suiteId, improveSuite().cases.single()) is StoreResult.Ok)
        return suiteId
    }

    private fun answerWith(vararg lines: String) {
        val provider = TurnProvider(
            listOf<Turn>(
                {
                    emit(LlmStreamEvent.TextDelta("""{"steps":[${lines.joinToString(",")}]}"""))
                    emit(LlmStreamEvent.Completed)
                },
            ),
        )
        state.testRunOverrides = TestRunOverrides(agentFactory = LaneAgentFactory { chosen, _ -> ProviderLaneAgent(provider, chosen) })
    }

    private fun entry(stepId: String, action: String, expected: String, reason: String) =
        """{"stepId":"$stepId","action":"$action","expected":"$expected","reason":"$reason"}"""

    @Test
    fun theCaseScreenOpensTheDialogWhichGeneratesAPreviewAndAppliesTheSelectedSteps() {
        val suiteId = seed()
        val steps = state.testLibrary.findCase(IMPROVE_CASE_ID)!!.case.steps
        answerWith(
            entry(steps[0].id, "Launch the shop app", steps[0].expected, "More precise"),
            entry(steps[1].id, steps[1].action, "A list of search results is shown", "An ad is not stable"),
            entry(steps[2].id, steps[2].action, steps[2].expected, "unchanged"),
            entry(steps[3].id, steps[3].action, steps[3].expected, "unchanged"),
        )
        rule.setContent { TestsWorkspace(state) }
        state.testsView.selectedSuiteId = suiteId
        state.testsView.selectedCaseId = IMPROVE_CASE_ID
        rule.waitForIdle()

        rule.onNodeWithContentDescription("More step actions").performClick()
        rule.onNodeWithText("Improve steps with AI…").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Generate preview"), WAIT_MS)
        assertEquals(ImproveStepsTarget(suiteId, IMPROVE_CASE_ID, null), state.testsView.improveDialog)
        rule.waitUntilAtLeastOneExists(hasText("No run of this case yet · steps only"), WAIT_MS)

        rule.onNodeWithText("Generate preview").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Apply 2 selected steps"), WAIT_MS)
        rule.onNodeWithText("2 of 4 step(s) would change. Untick a step to keep it as it is; edit the new text if needed.").assertExists()
        rule.onNodeWithText("Why: More precise").assertExists()
        assertEquals("Open the app", state.testLibrary.findCase(IMPROVE_CASE_ID)!!.case.steps[0].action, "the preview changes nothing")

        rule.onNodeWithText("Apply 2 selected steps").performClick()
        rule.waitUntil(timeoutMillis = WAIT_MS) { state.testLibrary.findCase(IMPROVE_CASE_ID)!!.case.steps[0].action == "Launch the shop app" }
        rule.waitUntil(timeoutMillis = WAIT_MS) { state.testsView.improveDialog == null }
        assertEquals("A list of search results is shown", state.testLibrary.findCase(IMPROVE_CASE_ID)!!.case.steps[1].expected)
    }

    @Test
    fun theButtonIsDisabledForACaseWithoutSteps() {
        val suiteId = (state.createTestSuite("Empty suite") as StoreResult.Ok).value.id
        val case = (state.createTestCase(suiteId, TestCase("", "No steps yet")) as StoreResult.Ok).value
        rule.setContent { TestsWorkspace(state) }
        state.testsView.selectedSuiteId = suiteId
        state.testsView.selectedCaseId = case.id
        rule.waitForIdle()

        rule.onNodeWithContentDescription("More step actions").performClick()
        rule.onNodeWithText("Improve steps with AI…").performClick()
        rule.waitForIdle()

        assertNull(state.testsView.improveDialog, "a disabled button opens nothing")
        assertTrue(state.testLibrary.findCase(case.id)!!.case.steps.isEmpty())
    }
}
