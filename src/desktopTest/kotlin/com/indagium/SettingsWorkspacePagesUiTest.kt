package com.indagium

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.indagium.model.DEFAULT_INACTIVE_TAB_UNLOAD_MINUTES
import com.indagium.ui.AppState
import com.indagium.ui.AppearanceSystemFontSelectors
import com.indagium.ui.FontCatalog
import com.indagium.ui.PROFILE_GROUP_MENU_TAG
import com.indagium.ui.SettingsDialog
import com.indagium.ui.SettingsPage
import com.indagium.ui.TabsAppearanceSettings
import com.indagium.ui.searchSettings
import com.indagium.ui.settingsNavTag
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Workspace pages of the Settings dialog: General, Appearance and Tabs & toolbar, as rows of switches, dropdowns and pickers. */
@OptIn(ExperimentalTestApi::class)
class SettingsWorkspacePagesUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun openPage(state: AppState, page: SettingsPage) {
        rule.setContent { SettingsDialog(state, onDismiss = {}) }
        rule.onNodeWithTag(settingsNavTag(page)).performScrollTo().performClick()
        rule.waitForIdle()
    }

    // ---- General ----

    @Test
    fun theProfileGroupsMenuHoldsSaveImportAndExportAndTheLinksAreGone() {
        val state = AppState()
        openPage(state, SettingsPage.General)

        rule.onNodeWithText("Save current setup as profile…").assertDoesNotExist()
        rule.onNodeWithText("Import profile…").assertDoesNotExist()
        rule.onNodeWithText("Export current setup…").assertDoesNotExist()
        rule.onNodeWithTag(PROFILE_GROUP_MENU_TAG).performClick()
        rule.waitForIdle()
        listOf("save", "import", "export").forEach { rule.onNodeWithTag("$PROFILE_GROUP_MENU_TAG-$it").assertExists() }
        rule.onNodeWithText("Save current setup as profile…").assertExists()
        rule.onNodeWithText("Import profile…").assertExists()
        rule.onNodeWithText("Export current setup…").assertExists()

        rule.onNodeWithTag("$PROFILE_GROUP_MENU_TAG-save").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("profile-name-field").assertExists()
        rule.onNodeWithText("Import profile…").assertDoesNotExist() // choosing an entry closes the menu
    }

    @Test
    fun inactiveTabMemoryIsASwitchAndAMinutesFieldThatDimsWhileOff() {
        val state = AppState()
        openPage(state, SettingsPage.General)
        assertEquals(DEFAULT_INACTIVE_TAB_UNLOAD_MINUTES, state.settings.inactiveTabUnloadMinutes)
        rule.onNodeWithTag("inactive-tab-memory-switch").assertIsOn()
        rule.onNodeWithTag("inactive-tab-minutes").assertTextContains(DEFAULT_INACTIVE_TAB_UNLOAD_MINUTES.toString())

        rule.onNodeWithTag("inactive-tab-minutes").performTextClearance()
        rule.onNodeWithTag("inactive-tab-minutes").performTextInput("45")
        rule.waitForIdle()
        assertEquals(45, state.settings.inactiveTabUnloadMinutes)

        rule.onNodeWithTag("inactive-tab-memory-switch").performClick()
        rule.waitForIdle()
        assertEquals(0, state.settings.inactiveTabUnloadMinutes, "off stores 0")
        rule.onNodeWithTag("inactive-tab-memory-switch").assertIsOff()
        rule.onNodeWithTag("inactive-tab-minutes").assertIsNotEnabled()
        rule.onNodeWithTag("inactive-tab-minutes").assertTextContains("45")

        rule.onNodeWithTag("inactive-tab-memory-switch").performClick()
        rule.waitForIdle()
        assertEquals(45, state.settings.inactiveTabUnloadMinutes, "on restores the last value this session saw")
        rule.onNodeWithTag("inactive-tab-memory-switch").assertIsOn()
    }

    @Test
    fun turningInactiveTabMemoryOnFromAStoredZeroUsesTheDefault() {
        val state = AppState()
        state.updateSettings { it.copy(inactiveTabUnloadMinutes = 0) }
        openPage(state, SettingsPage.General)
        rule.onNodeWithTag("inactive-tab-memory-switch").assertIsOff()
        rule.onNodeWithTag("inactive-tab-memory-switch").performClick()
        rule.waitForIdle()
        assertEquals(DEFAULT_INACTIVE_TAB_UNLOAD_MINUTES, state.settings.inactiveTabUnloadMinutes)
    }

    @Test
    fun theUpdateCheckIsASwitchWithCheckNowBesideIt() {
        val state = AppState()
        state.updateSettings { it.copy(autoCheckUpdates = true) }
        openPage(state, SettingsPage.General)
        rule.onNodeWithTag("update-check-switch").assertIsOn()
        rule.onNodeWithTag("update-check-now").assertExists()
        rule.onNodeWithTag("update-check-switch").performClick()
        rule.waitForIdle()
        assertFalse(state.settings.autoCheckUpdates)
        rule.onNodeWithTag("update-check-switch").assertIsOff()
    }

    // ---- Appearance ----

    @Test
    fun theLogFontListStartsWithTheTwoDefaultsAndPickingOneSetsTheFallbackKindAndClearsTheFamily() {
        val family = FontCatalog.families.first { !it.startsWith(".") }
        val state = AppState()
        rule.setContent { Column(Modifier.width(700.dp)) { AppearanceSystemFontSelectors(state) } }
        rule.onNodeWithTag("settings-log-font-trigger").assertTextContains("Monospace · default")

        rule.onNodeWithTag("settings-log-font-trigger").performClick()
        rule.onNodeWithTag("settings-log-font-option-default-monospace").assertExists()
        rule.onNodeWithTag("settings-log-font-option-default-proportional").assertExists()
        rule.onNodeWithTag("settings-log-font-option-default-proportional").performClick()
        rule.waitForIdle()
        assertFalse(state.settings.fontMono)
        assertNull(state.settings.logFontFamily)
        rule.onNodeWithTag("settings-log-font-trigger").assertTextContains("Proportional · default")

        // A named family leaves the default kind alone: it is the fallback if the family goes missing.
        rule.onNodeWithTag("settings-log-font-trigger").performClick()
        rule.onNodeWithTag("settings-log-font-search").performTextInput(family)
        rule.onNodeWithTag("settings-log-font-option-0").performClick()
        rule.waitForIdle()
        assertEquals(family, state.settings.logFontFamily)
        assertFalse(state.settings.fontMono)
        rule.onNodeWithTag("settings-log-font-trigger").assertTextContains(family)

        rule.onNodeWithTag("settings-log-font-trigger").performClick()
        rule.onNodeWithTag("settings-log-font-option-default-monospace").performClick()
        rule.waitForIdle()
        assertTrue(state.settings.fontMono)
        assertNull(state.settings.logFontFamily)
        // The interface font keeps its single default row.
        rule.onNodeWithTag("settings-interface-font-trigger").performClick()
        rule.onNodeWithTag("settings-interface-font-option-default").assertExists()
        rule.onNodeWithTag("settings-interface-font-option-default-monospace").assertDoesNotExist()
    }

    @Test
    fun theAppearancePageHasNoFallbackLogFamilyAndTheOldSearchTermsFindTheLogFontRow() {
        val state = AppState()
        openPage(state, SettingsPage.Appearance)
        rule.onNodeWithText("Fallback log family").assertDoesNotExist()
        rule.onNodeWithText("Log font").assertExists()
        rule.onNodeWithText("Tiles").assertExists()
        rule.onNodeWithText("Toolbar").assertExists()
        val hits = searchSettings("fallback log family", SettingsPage.entries.toSet())
        assertTrue(hits.any { it.page == SettingsPage.Appearance && it.anchor == "Log font" }, hits.map { it.label }.toString())
    }

    // ---- Tabs & toolbar ----

    @Test
    fun theTabsRowsCoverPositionWidthLabelCloseButtonAndVisibleTabs() {
        val state = AppState()
        rule.setContent { Column(Modifier.width(700.dp).verticalScroll(rememberScrollState())) { TabsAppearanceSettings(state) } }
        listOf(
            "Position", "Width", "Label", "Close button position", "Close button on every tab", "Visible tabs", "Placement", "Button labels",
            "Lock tabs and toolbar", "File-type badge", "Source colour stripe",
        ).forEach { rule.onNodeWithText(it).assertExists() }
        // Visible tabs is a stepper: + moves to the next limit.
        val before = state.settings.visibleTabLimit
        rule.onNodeWithText("+").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(state.settings.visibleTabLimit > before)
        // Button labels: Icon and text | Icons only.
        rule.onNodeWithText("Icons only").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(state.settings.toolbarIconOnlyButtons)
        rule.onNodeWithTag("chrome-lock").assertIsOff()
    }
}
