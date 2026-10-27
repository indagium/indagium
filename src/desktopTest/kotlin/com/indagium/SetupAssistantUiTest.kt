package com.indagium

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.width
import com.indagium.model.CtrlFTarget
import com.indagium.model.SearchScopeBehavior
import com.indagium.model.TabSizeMode
import com.indagium.model.TabStripStyle
import com.indagium.ui.AppState
import com.indagium.ui.LICENSE_VERSION
import com.indagium.ui.SetupAssistantDialog
import com.indagium.ui.TAB_STYLE_GRID_TAG
import com.indagium.update.ReleaseInfo
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val FIRST_SEGMENT_CLICK_FRACTION = 0.25f
private const val SECOND_SEGMENT_CLICK_FRACTION = 0.75f
private const val SEGMENT_VERTICAL_CENTER_FRACTION = 0.5f

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
        rule.onNodeWithText("Step 1 of 5").assertExists()

        rule.onNodeWithText("Continue").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Pick a look").assertExists()
        rule.onNodeWithText("Step 2 of 5").assertExists()
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
    fun lookStepIncludesLiveTabAppearanceControls() {
        val state = AppState()
        state.maybeShowSetupAssistantOnStartup()
        install(state)
        rule.onNodeWithText("Continue").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("tab-style-PILL").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(TabStripStyle.PILL, state.settings.tabStripStyle)

        val grid = rule.onNodeWithTag(TAB_STYLE_GRID_TAG).getBoundsInRoot()
        val firstPreview = rule.onNodeWithTag("tab-style-CURRENT").getBoundsInRoot()
        val fifthPreview = rule.onNodeWithTag("tab-style-COMPACT").getBoundsInRoot()
        assertEquals(grid.left, firstPreview.left)
        assertEquals(grid.right, fifthPreview.right)
        assertEquals(firstPreview.width, rule.onNodeWithTag("tab-style-label-CURRENT").getBoundsInRoot().width)

        rule.onNodeWithText("Fit label").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(TabSizeMode.FIT, state.settings.tabSizeMode)
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
        assertEquals(2, state.settings.setupAssistantRevision)
    }

    @Test
    fun completedRevisionZeroOpensSearchWithCurrentSettingsAndKeepCurrentChoice() {
        val state = AppState()
        state.updateSettings {
            it.copy(
                setupAssistantDone = true,
                setupAssistantRevision = 0,
                acceptedLicenseVersion = LICENSE_VERSION,
                searchScopeBehavior = SearchScopeBehavior.TWO_SEARCHES,
                ctrlFTarget = CtrlFTarget.MESSAGE_RULE,
                openUnfilteredOnCtrlF = true,
                openNewFilesWithUnfiltered = true,
                syncSplitPanelSelectionAndScroll = false,
                workspaceProfileId = "saved-workspace",
            )
        }
        val before = state.settings
        state.maybeShowSetupAssistantOnStartup()
        rule.setContent {
            if (!state.needsLicenseAcceptance && !state.updateDialogVisible) SetupAssistantDialog(state)
        }

        rule.onNodeWithText("Choose how search works").assertExists()
        assertTrue(
            state.shouldSetupAssistantStartAtSearch(),
            "A temporary modal remount must retain the migration route",
        )
        rule.onNodeWithText("Step 4 of 5").assertExists()
        rule.onNodeWithText("Two searches").assertExists()
        // The legacy message-rule target remains intact until a visible destination is selected.
        assertEquals(CtrlFTarget.MESSAGE_RULE, state.settings.ctrlFTarget)
        assertEquals(before, state.settings)

        rule.runOnIdle {
            state.availableUpdate = ReleaseInfo(
                "9.9.9",
                "v9.9.9",
                "https://example.test/release",
                "",
                emptyList(),
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Choose how search works").assertDoesNotExist()
        rule.runOnIdle { state.availableUpdate = null }
        rule.waitForIdle()
        rule.onNodeWithText("Choose how search works").assertExists()
        rule.onNodeWithText("Step 4 of 5").assertExists()
        assertEquals(before, state.settings)

        // The full rail is retained for an upgrade; leaving Workspace with Keep current changes no profile.
        rule.onNodeWithText("Workspace").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Keep my current setup").assertExists()
        rule.onNodeWithText("Continue").performClick()
        rule.waitForIdle()
        assertEquals(before, state.settings)
    }

    @Test
    fun searchStepUpdatesEveryPreferenceIncludingAllScopeChoices() {
        val state = AppState()
        state.maybeShowSetupAssistantOnStartup()
        install(state)
        repeat(3) {
            rule.onNodeWithText("Continue").performClick()
            rule.waitForIdle()
        }
        rule.onNodeWithText("Step 4 of 5").assertExists()

        val scopeChoices = listOf(
            SearchScopeBehavior.FILTERED,
            SearchScopeBehavior.UNFILTERED,
            SearchScopeBehavior.TWO_SEARCHES,
            SearchScopeBehavior.LAST_USED,
            SearchScopeBehavior.FOLLOW_VIEW,
        )
        scopeChoices.forEach { behavior ->
            rule.onNodeWithTag("search-scope-behavior-selector").performClick()
            rule.onNodeWithTag("search-scope-behavior-option-${behavior.name.lowercase()}").performClick()
            rule.waitForIdle()
            assertEquals(behavior, state.settings.searchScopeBehavior)
        }

        rule.onNodeWithTag("ctrl-f-target-selector").performClick()
        rule.onNodeWithTag("ctrl-f-target-option-tags").performClick()
        rule.waitForIdle()
        assertEquals(CtrlFTarget.TAGS, state.settings.ctrlFTarget)

        clickSegment("setup-search-open-unfiltered-on-ctrl-f", 0)
        clickSegment("setup-search-open-new-files-unfiltered", 0)
        clickSegment("setup-search-link-split-selection-and-find", 1)
        rule.waitForIdle()
        assertTrue(state.settings.openUnfilteredOnCtrlF)
        assertTrue(state.settings.openNewFilesWithUnfiltered)
        assertFalse(state.settings.syncSplitPanelSelectionAndScroll)
    }

    @Test
    fun popupDismissalRestoresEscapeFocus() {
        val state = AppState()
        state.updateSettings { it.copy(setupAssistantDone = true, setupAssistantRevision = 0) }
        state.maybeShowSetupAssistantOnStartup()
        install(state)

        rule.onNodeWithTag("search-scope-behavior-selector").performClick()
        rule.onNodeWithText("Choose how search works").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("search-scope-behavior-option-filtered").assertDoesNotExist()
        rule.onRoot().performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()

        assertFalse(state.setupAssistantOpen)
        assertTrue(state.settings.setupAssistantDone)
        assertEquals(2, state.settings.setupAssistantRevision)
    }

    @Test
    fun searchSelectorInteractionKeepsBackAndEnterNavigationWorking() {
        val state = AppState()
        state.updateSettings { it.copy(setupAssistantDone = true, setupAssistantRevision = 0) }
        state.maybeShowSetupAssistantOnStartup()
        install(state)

        rule.onNodeWithTag("search-scope-behavior-selector").performClick()
        rule.onNodeWithTag("search-scope-behavior-option-unfiltered").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Back").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Where should files go?").assertExists()
        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        rule.onNodeWithText("Choose how search works").assertExists()
        assertEquals(SearchScopeBehavior.UNFILTERED, state.settings.searchScopeBehavior)
        clickSegment("setup-search-open-unfiltered-on-ctrl-f", 0)
        rule.onRoot().performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertFalse(state.setupAssistantOpen, "Escape still works after the Search toggle")
    }

    private fun clickSegment(tag: String, index: Int) {
        val node = rule.onNodeWithTag(tag)
        node.performScrollTo()
        val size = node.fetchSemanticsNode().size
        val x = size.width * if (index == 0) FIRST_SEGMENT_CLICK_FRACTION else SECOND_SEGMENT_CLICK_FRACTION
        node.performTouchInput { click(Offset(x, size.height * SEGMENT_VERTICAL_CENTER_FRACTION)) }
    }
}
