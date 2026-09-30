package com.indagium

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import com.indagium.ui.AppState
import com.indagium.ui.SettingsDialog
import com.indagium.ui.WorkspaceProfile
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drives Settings › General's profile actions; file dialogs (import/export) are not exercised. */
@OptIn(ExperimentalTestApi::class)
class WorkspaceProfileUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun install(state: AppState) {
        rule.setContent { SettingsDialog(state, onDismiss = {}) }
    }

    @Test
    fun savingThroughTheNameDialogWithEnterAddsAndSelectsAProfile() {
        val state = AppState()
        install(state)

        rule.onNodeWithText("Save current setup as profile…").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("profile-name-field").performTextClearance()
        rule.onNodeWithTag("profile-name-field").performTextInput("Evening")
        rule.onNodeWithTag("profile-name-field").performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        val saved = state.settings.customWorkspaceProfiles.single()
        assertEquals("Evening", saved.name)
        assertEquals(saved.id, state.settings.workspaceProfileId)
        rule.onNodeWithText("Evening").assertExists()
    }

    @Test
    fun aTakenNameDisablesSave() {
        val state = AppState()
        install(state)

        rule.onNodeWithText("Save current setup as profile…").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("profile-name-field").performTextClearance()
        rule.onNodeWithTag("profile-name-field").performTextInput("focused")
        rule.waitForIdle()

        rule.onNodeWithText("A profile with this name already exists.").assertExists()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertTrue(state.settings.customWorkspaceProfiles.isEmpty())
    }

    @Test
    fun deletingTheSelectedProfileThroughTheMenuAsksFirstAndClearsTheSelection() {
        val state = AppState()
        state.saveCurrentAsWorkspaceProfile("Doomed")
        install(state)

        rule.onNodeWithTag("profile-card-menu").performClick()
        rule.onNodeWithText("Delete…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete profile?").assertExists()
        assertEquals(1, state.settings.customWorkspaceProfiles.size)

        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()

        assertTrue(state.settings.customWorkspaceProfiles.isEmpty())
        assertNull(state.settings.workspaceProfileId)
    }

    @Test
    fun theCustomizedStripOffersSaveAndUpdateForACustomProfile() {
        val state = AppState()
        state.applyWorkspaceProfile(WorkspaceProfile.FOCUSED)
        val saved = state.saveCurrentAsWorkspaceProfile("Mine")
        state.updateSettings { it.copy(fontSize = 15) }
        install(state)

        rule.onNodeWithText("Update “Mine”").performClick()
        rule.waitForIdle()

        assertEquals(15, state.settings.customWorkspaceProfiles.single { it.id == saved.id }.spec.fontSize)
        assertEquals(emptyList(), state.workspaceProfileDifferences())
    }
}
