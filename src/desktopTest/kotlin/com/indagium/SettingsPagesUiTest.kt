package com.indagium

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.indagium.capture.CaptureBufferMode
import com.indagium.model.ToolbarPlacement
import com.indagium.ui.AppState
import com.indagium.ui.CapturePageTags
import com.indagium.ui.SETTINGS_SEARCH_INDEX
import com.indagium.ui.SettingsAnchorKey
import com.indagium.ui.SettingsDialog
import com.indagium.ui.SettingsPage
import com.indagium.ui.holiday.SEASONAL_ANCHORS
import com.indagium.ui.settingsNavTag
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives the real Settings dialog's shell: the page nav, the deep links, the footer, and that every page, drill-in sub-pages included,
 * shows what the search index says it does.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsPagesUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun anchorsOnScreen(): Set<String> = rule
        .onAllNodes(SemanticsMatcher.keyIsDefined(SettingsAnchorKey), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .map { it.config[SettingsAnchorKey] }
        .toSet()

    /**
     * Rows that only exist in some states of their page, so a page in its default state cannot show them: the Seasonal tab of the theme
     * block (a search result opens it, see SettingsSearchUiTest), and the two provider rows that depend on the provider kind and on
     * the models the provider reported (CLI executable replaces Endpoint for account providers).
     */
    private val stateDependentAnchors = SEASONAL_ANCHORS + setOf("CLI executable (optional)", "Reasoning effort")

    /** Opens [page] the way a user does: a nav click, then for a drill-in sub-page the row of its parent that leads to it. */
    private fun openPage(page: SettingsPage) {
        val parent = page.parent
        if (parent == null) {
            rule.onNodeWithTag(settingsNavTag(page)).performScrollTo().performClick()
        } else {
            openPage(parent)
            rule.onNodeWithTag(CapturePageTags.drillRow(page)).performScrollTo().performClick()
        }
        rule.waitForIdle()
    }

    @Test
    fun everyPageRendersAnAnchoredRowForEachOfItsSearchEntries() {
        val state = AppState()
        // The default state hides rows that depend on a choice: put those choices in the state the rows need.
        state.updateSettings {
            it.copy(
                toolbarPlacement = ToolbarPlacement.OWN_EDGE,
                captureSettings = it.captureSettings.copy(bufferMode = CaptureBufferMode.CUSTOM),
            )
        }
        rule.setContent { SettingsDialog(state, onDismiss = {}) }

        val missing = mutableListOf<String>()
        for (page in SettingsPage.entries) {
            openPage(page)
            val shown = anchorsOnScreen()
            SETTINGS_SEARCH_INDEX.filter { it.page == page && it.anchor !in shown && it.anchor !in stateDependentAnchors }
                .forEach { missing += "${page.title}: ${it.label} (anchor \"${it.anchor.replace('\n', ' ')}\")" }
        }
        assertTrue(missing.isEmpty(), "index entries without a rendered anchor on their page:\n${missing.joinToString("\n")}")
    }

    @Test
    fun theRequestedPageOpensAndIsConsumedOnce() {
        val state = AppState()
        state.requestedSettingsPage = SettingsPage.IssueTracker
        rule.setContent { SettingsDialog(state, onDismiss = {}) }
        rule.waitForIdle()

        val shown = anchorsOnScreen()
        assertTrue("Use an issue tracker" in shown, shown.toString())
        assertFalse("Workspace profile" in shown, "General is not the open page")
        assertEquals(null, state.requestedSettingsPage)
    }

    @Test
    fun theFooterVersionLinkOpensAboutAndDoneDismisses() {
        val state = AppState()
        var dismissed = 0
        rule.setContent { SettingsDialog(state, onDismiss = { dismissed++ }) }
        rule.onNodeWithText("Workspace profile").assertExists()

        rule.onNodeWithTag("settings-footer-version").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Workspace profile").assertDoesNotExist()
        rule.onNodeWithText("GitHub repository").assertExists()
        rule.onNodeWithText("License agreement").assertExists()
        assertEquals(0, dismissed)

        rule.onNodeWithTag("settings-done").performClick()
        rule.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun theFooterOpensTheShortcutsOnTopAndTheSetupAssistantInPlaceOfSettings() {
        val state = AppState()
        state.settingsOpen = true
        rule.setContent { SettingsDialog(state, onDismiss = {}) }

        rule.onNodeWithTag("settings-footer-shortcuts").performClick()
        rule.waitForIdle()
        assertTrue(state.shortcutsOpen)
        assertTrue(state.settingsOpen, "the shortcuts stack over Settings")

        rule.onNodeWithTag("settings-footer-setup-assistant").performClick()
        rule.waitForIdle()
        assertTrue(state.setupAssistantOpen)
        assertFalse(state.settingsOpen, "the setup assistant replaces Settings")
    }
}
