package com.indagium

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.indagium.model.LogViewPresetId
import com.indagium.model.ProfileLayoutRef
import com.indagium.model.TabStripStyle
import com.indagium.model.ThemePreset
import com.indagium.ui.AppState
import com.indagium.ui.PROFILE_GROUP_MENU_TAG
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

    // The group's "⋯" menu holds the save, import and export actions.
    private fun saveCurrentSetupAsProfile() {
        rule.onNodeWithTag(PROFILE_GROUP_MENU_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Save current setup as profile…").performClick()
        rule.waitForIdle()
    }

    @Test
    fun savingThroughTheNameDialogWithEnterAddsAndSelectsAProfile() {
        val state = AppState()
        install(state)

        saveCurrentSetupAsProfile()
        rule.onNodeWithTag("profile-name-field").performTextClearance()
        rule.onNodeWithTag("profile-name-field").performTextInput("Evening")
        rule.onNodeWithTag("profile-name-field").performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        val saved = state.settings.customWorkspaceProfiles.single()
        assertEquals("Evening", saved.name)
        assertEquals(saved.id, state.settings.workspaceProfileId)
        rule.onNodeWithTag("profile-layout-select").assertExists() // the new custom profile is the selected one: its layout row is a dropdown
    }

    @Test
    fun aTakenNameDisablesSave() {
        val state = AppState()
        install(state)

        saveCurrentSetupAsProfile()
        rule.onNodeWithTag("profile-name-field").performTextClearance()
        rule.onNodeWithTag("profile-name-field").performTextInput("focused")
        rule.waitForIdle()

        rule.onNodeWithText("A profile with this name already exists.").assertExists()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertTrue(state.settings.customWorkspaceProfiles.isEmpty())
    }

    private fun pickFromCardMenu(label: String) {
        rule.onNodeWithTag("profile-card-menu").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(label).performClick()
        rule.waitForIdle()
    }

    @Test
    fun deletingTheSelectedProfileThroughTheCardMenuAsksFirstAndClearsTheSelection() {
        val state = AppState()
        val doomed = state.saveCurrentAsWorkspaceProfile("Doomed")
        install(state)

        // The card's "⋯" is the one home of the profile's actions: no strip of links repeats them under the cards.
        rule.onAllNodesWithText("Delete…").assertCountEquals(0)
        pickFromCardMenu("Delete…")
        rule.onNodeWithText("Delete profile?").assertExists()
        assertEquals(1, state.settings.customWorkspaceProfiles.size)

        // Cancel keeps it.
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals(doomed.id, state.settings.customWorkspaceProfiles.single().id)

        pickFromCardMenu("Delete…")
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()

        assertTrue(state.settings.customWorkspaceProfiles.isEmpty())
        assertNull(state.settings.workspaceProfileId)
        rule.onAllNodesWithTag("profile-card-menu").assertCountEquals(0)
    }

    @Test
    fun thereIsNoLinkStripUnderTheCardsAndTheCardMenuOffersRenameUpdateExportAndDeleteOnlyForACustomProfile() {
        val state = AppState()
        install(state)
        rule.onAllNodesWithTag("profile-card-menu").assertCountEquals(0)

        rule.runOnIdle { state.applyWorkspaceProfile(WorkspaceProfile.FOCUSED) }
        rule.waitForIdle()
        rule.onAllNodesWithTag("profile-card-menu").assertCountEquals(0)

        val saved = rule.runOnIdle { state.saveCurrentAsWorkspaceProfile("Mine") }
        rule.waitForIdle()
        rule.onAllNodesWithTag("profile-card-menu").assertCountEquals(1)
        rule.onNodeWithTag("profile-card-menu").performClick()
        rule.waitForIdle()
        listOf("Rename…", "Update from current setup", "Export…", "Delete…").forEach { rule.onNodeWithText(it).assertExists() }
        // Nothing else on the page repeats them as links.
        rule.onAllNodesWithText("Rename…").assertCountEquals(1)
        rule.onNodeWithText("Update from current setup").performClick()
        rule.waitForIdle()
        assertEquals(emptyList(), state.workspaceProfileDifferences())

        rule.runOnIdle { state.updateSettings { it.copy(tabStripStyle = TabStripStyle.PILL) } }
        rule.waitForIdle()
        pickFromCardMenu("Update from current setup")
        assertEquals(TabStripStyle.PILL, state.settings.customWorkspaceProfiles.single { it.id == saved.id }.spec.appearance?.tabStripStyle)
        assertEquals(emptyList(), state.workspaceProfileDifferences())

        pickFromCardMenu("Rename…")
        rule.onNodeWithTag("profile-name-field").assertExists()
    }

    @Test
    fun theCardMenuButtonIsDrawnWithoutHoverOnTheCaptionLineAndIsAn18dpTarget() {
        val state = AppState()
        state.saveCurrentAsWorkspaceProfile("Mine")
        install(state)

        // No pointer has entered anything: the "⋯" is on screen anyway, and only on the custom profile's card.
        rule.onAllNodesWithTag("profile-card-menu").assertCountEquals(1)
        val node = rule.onNodeWithTag("profile-card-menu")
        node.assertIsDisplayed()
        val bounds = node.getBoundsInRoot()
        assertEquals(18f, bounds.width.value, 0.5f)
        assertEquals(18f, bounds.height.value, 0.5f)
    }

    @Test
    fun theCustomizedStripNamesATabStyleChangeForACustomProfileAndForABuiltIn() {
        val state = AppState()
        state.saveCurrentAsWorkspaceProfile("Mine")
        state.updateSettings { it.copy(tabStripStyle = TabStripStyle.PILL) }
        install(state)

        rule.onNodeWithText("Customized — 1 setting differs from Mine: Tab style").assertExists()

        rule.runOnIdle { state.applyWorkspaceProfile(WorkspaceProfile.CLASSIC) }
        rule.waitForIdle()
        rule.onAllNodesWithText("Customized", substring = true).assertCountEquals(0)
        // A built-in carries the whole look: a tab style that is not its (default) one is a difference, like for any profile.
        rule.runOnIdle { state.updateSettings { it.copy(tabStripStyle = TabStripStyle.QUIET) } }
        rule.waitForIdle()
        rule.onNodeWithText("Customized — 1 setting differs from Indagium Classic: Tab style").assertExists()
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
