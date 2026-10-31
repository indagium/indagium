package com.indagium

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import com.indagium.model.LogViewPresetId
import com.indagium.model.ProfileLayoutRef
import com.indagium.model.ThemePreset
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

    @Test
    fun cardsDescribeThemeSizeAndLayout() {
        val state = AppState()
        install(state)

        rule.onNodeWithText("Dark (GitHub) · 12px · Layout: Filter bar").assertExists()
        rule.onNodeWithText("Graphite Dim · 11px · Layout: Log focus").assertExists()
        rule.onNodeWithText("Warm Paper · 12px · Layout: Classic").assertExists()
    }

    @Test
    fun togglingAPanelNeverShowsCustomizedButAThemeChangeStillDoes() {
        val state = AppState()
        state.applyWorkspaceProfile(WorkspaceProfile.FOCUSED)
        state.updateAnnotationVisible(true)
        state.updateFilterVisible(true)
        state.updateVideoPanelVisible(true)
        state.updateAiPanelVisible(true)
        install(state)

        rule.onAllNodesWithText("Customized", substring = true).assertCountEquals(0)

        rule.runOnIdle { state.updateSettings { it.copy(theme = ThemePreset.DARK_GITHUB) } }
        rule.waitForIdle()
        rule.onNodeWithText("Customized — 1 setting differs from Focused: Theme").assertExists()
    }

    @Test
    fun aBuiltInProfilesLayoutIsReadOnlyText() {
        val state = AppState()
        state.applyWorkspaceProfile(WorkspaceProfile.FOCUSED)
        install(state)

        rule.onNodeWithTag("profile-layout-row").assertExists()
        rule.onNodeWithTag("profile-layout-readonly").assertTextEquals("Filter bar")
        rule.onNodeWithTag("profile-layout-select").assertDoesNotExist()
    }

    @Test
    fun noLayoutRowWithoutASelectedProfile() {
        val state = AppState()
        install(state)

        rule.onNodeWithTag("profile-layout-row").assertDoesNotExist()
    }

    @Test
    fun aCustomProfilesLayoutIsPickedFromADropdownOfPresetsSavedLayoutsAndKeepCurrent() {
        val state = AppState()
        val layout = state.saveCurrentAsLogViewLayout("Triage")
        val profile = state.saveCurrentAsWorkspaceProfile("Mine")
        install(state)

        rule.onNodeWithTag("profile-layout-select").assertTextContains("Triage") // the saved layout is selected and matches, so "Save as new profile" stored it
        rule.onNodeWithTag("profile-layout-select").performClick()
        rule.waitForIdle()
        listOf(
            "preset-CLASSIC", "preset-LOG_FOCUS", "preset-FILTER_BAR", "preset-FILTERING", "preset-ANALYSIS", "saved-${layout.id}", "keep",
        ).forEach { rule.onNodeWithTag("profile-layout-option-$it").assertExists() }

        rule.onNodeWithTag("profile-layout-option-saved-${layout.id}").performClick()
        rule.waitForIdle()
        assertEquals(ProfileLayoutRef.Saved(layout.id), state.settings.customWorkspaceProfiles.single { it.id == profile.id }.spec.layout)

        rule.onNodeWithTag("profile-layout-select").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("profile-layout-option-preset-FILTER_BAR").performClick()
        rule.waitForIdle()
        assertEquals(ProfileLayoutRef.Preset(LogViewPresetId.FILTER_BAR), state.settings.customWorkspaceProfiles.single().spec.layout)
        rule.onNodeWithText("Warm Paper · 12px · Layout: Filter bar").assertExists()

        rule.onNodeWithTag("profile-layout-select").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("profile-layout-option-keep").performClick()
        rule.waitForIdle()
        assertNull(state.settings.customWorkspaceProfiles.single().spec.layout)
        rule.onNodeWithTag("profile-layout-select").assertTextContains("Keep current layout")
    }

    @Test
    fun resetToProfileReappliesItsLayoutAndLeavesAnUnrelatedSavedLayoutAlone() {
        val state = AppState()
        val saved = state.saveCurrentAsLogViewLayout("Mine")
        state.applyWorkspaceProfile(WorkspaceProfile.MINIMAL)
        state.updateSettings { it.copy(fontSize = 15) }
        install(state)

        rule.onNodeWithText("Reset to profile").performClick()
        rule.waitForIdle()

        assertEquals(11, state.settings.fontSize)
        assertNull(state.settings.lastCustomLogViewLayoutId, "the saved layout stays unselected, so it is never Modified")
        assertTrue(state.settings.customLogViewLayouts.contains(saved))
        assertEquals(LogViewPresetId.LOG_FOCUS, state.settings.lastLogViewPreset)
    }
}
