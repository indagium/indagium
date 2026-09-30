package com.indagium

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.indagium.ui.AppState
import com.indagium.ui.SetupAssistantDialog
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Drives the real assistant. It stops before the Capture step, which starts a real adb lookup. */
@OptIn(ExperimentalTestApi::class)
class SetupAssistantUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun install(state: AppState) {
        rule.setContent { SetupAssistantDialog(state) }
    }

    @Test
    fun opensOnTheWorkspaceStepAndContinueAppliesTheSelectedProfile() {
        val state = AppState()
        state.maybeShowSetupAssistantOnStartup()
        install(state)

        rule.onNodeWithText("How do you like to read logs?").assertExists()
        rule.onNodeWithText("Keep my current setup").assertDoesNotExist()
        rule.onNodeWithText("Step 1 of 4").assertExists()

        rule.onNodeWithText("Continue").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Pick a look").assertExists()
        rule.onNodeWithText("Step 2 of 4").assertExists()
        // A new user's preselected Classic profile was applied when leaving step 1.
        assertEquals("classic", state.settings.workspaceProfileId)
    }

    @Test
    fun existingUsersGetKeepCurrentSetupAndContinuingChangesNothing() {
        val state = AppState()
        state.updateSettings { it.copy(setupAssistantDone = true) } // "has a setup already"
        val before = state.settings
        install(state)

        rule.onNodeWithText("Keep my current setup").assertExists()
        rule.onNodeWithText("Continue").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Pick a look").assertExists()
        assertEquals(before, state.settings)
    }

    @Test
    fun skipForNowClosesAndMarksItDone() {
        val state = AppState()
        state.maybeShowSetupAssistantOnStartup()
        install(state)

        rule.onNodeWithText("Skip for now").performClick()

        assertFalse(state.setupAssistantOpen)
        assertTrue(state.settings.setupAssistantDone)
        // Nothing was applied.
        assertEquals(null, state.settings.workspaceProfileId)
    }

    @Test
    fun escapeSkipsAndBackReturnsToThePreviousStep() {
        val state = AppState()
        state.maybeShowSetupAssistantOnStartup()
        install(state)

        rule.onNodeWithText("Continue").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Back").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("How do you like to read logs?").assertExists()

        rule.onRoot().performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()

        assertFalse(state.setupAssistantOpen)
        assertTrue(state.settings.setupAssistantDone)
    }
}
