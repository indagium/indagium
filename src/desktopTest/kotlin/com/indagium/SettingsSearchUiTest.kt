package com.indagium

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import com.indagium.model.ErrorHighlightStyle
import com.indagium.model.SearchScopeBehavior
import com.indagium.ui.AppState
import com.indagium.ui.SettingsDialog
import com.indagium.ui.holiday.seasonalPackTag
import com.indagium.ui.isMacOs
import com.indagium.ui.mkTab
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Drives the real Settings dialog: typing into the nav search, Esc, and opening a result. */
@OptIn(ExperimentalTestApi::class)
class SettingsSearchUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun install() {
        val state = AppState()
        rule.setContent { SettingsDialog(state, onDismiss = {}) }
    }

    private fun type(text: String) {
        rule.onNodeWithTag("settings-search-field").performTextInput(text)
        rule.waitForIdle()
    }

    @Test
    fun typingShowsGroupedResultsAndTheCountInTheNav() {
        install()
        type("folder")

        rule.onNodeWithText("“folder”", substring = true).assertExists()
        rule.onNodeWithText("All results").assertExists()
        rule.onNodeWithText("Snapshots folder").assertExists()
    }

    @Test
    fun noMatchShowsTheEmptyState() {
        install()
        type("zzzznothing")

        rule.onNodeWithText("No settings match “zzzznothing”").assertExists()
    }

    @Test
    fun escapeClearsTheQueryAndRestoresTheSection() {
        install()
        type("folder")
        rule.onNodeWithText("All results").assertExists()

        rule.onNodeWithTag("settings-search-field").performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()

        rule.onNodeWithText("All results").assertDoesNotExist()
        rule.onNodeWithText("Workspace profile").assertExists()
    }

    @Test
    fun clickingAResultOpensItsSectionAndClearsTheQuery() {
        install()
        type("minimap")
        rule.onNodeWithText("Minimap").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("All results").assertDoesNotExist()
        // Editor behavior is now the open section: one of its other rows is on screen.
        rule.onNodeWithText("Keyboard scroll margin").assertExists()
    }

    @Test
    fun personalVocabularyCanBeAddedAndRemovedFromEditorSettings() {
        val state = AppState()
        rule.setContent { SettingsDialog(state, onDismiss = {}) }
        type("personal vocabulary")
        rule.onNodeWithText("Personal vocabulary").performClick()
        rule.waitForIdle()

        val query = rule.onNodeWithTag("proofreading-vocabulary-query")
        query.performTextInput("  Indagium  ")
        rule.onNodeWithTag("proofreading-vocabulary-add").performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(listOf("Indagium"), state.settings.proofreading.vocabulary) }

        // Adding clears the field, so the helper line counts the saved word.
        rule.onNodeWithText("1 word").assertExists()
        rule.onNodeWithText("Already in your vocabulary.").assertDoesNotExist()

        // Typing a saved word again (any case) warns instead of counting and blocks Add.
        query.performTextInput("indagium")
        rule.onNodeWithText("Already in your vocabulary.").assertExists()
        rule.onNodeWithTag("proofreading-vocabulary-add").assertIsNotEnabled()
        query.performTextClearance()
        rule.onNodeWithText("1 word").assertExists()

        rule.onNodeWithTag("proofreading-vocabulary-remove-${"Indagium".hashCode()}").performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(emptyList(), state.settings.proofreading.vocabulary) }
        rule.onNodeWithText("No saved words").assertExists()
    }

    @Test
    fun vocabularyStaysEditableWhileProofreadingIsOff() {
        val state = AppState()
        state.updateSettings { it.copy(proofreading = it.proofreading.copy(enabled = false)) }
        rule.setContent { SettingsDialog(state, onDismiss = {}) }
        type("personal vocabulary")
        rule.onNodeWithText("Personal vocabulary").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("proofreading-vocabulary-query").performTextInput("Indagium")
        rule.onNodeWithTag("proofreading-vocabulary-add").performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(listOf("Indagium"), state.settings.proofreading.vocabulary) }
    }

    private fun openEditorBehavior(state: AppState) {
        rule.setContent { SettingsDialog(state, onDismiss = {}) }
        type("minimap")
        rule.onNodeWithText("Minimap").performClick()
        rule.waitForIdle()
    }

    private fun top(label: String): Float = rule.onNodeWithText(label).fetchSemanticsNode().positionInRoot.y

    private fun left(label: String): Float = rule.onNodeWithText(label).fetchSemanticsNode().positionInRoot.x

    private fun panelBounds() = rule.onNodeWithTag("error-highlight-global-panel").fetchSemanticsNode().boundsInRoot

    @Test
    fun editorBehaviorGridKeepsTheCtrlFCellsOnTheirOwnRow() {
        openEditorBehavior(AppState())

        fun row(vararg labels: String): Float {
            val tops = labels.map(::top)
            assertTrue(tops.all { it == tops.first() }, "${labels.toList()} must share a row, tops=$tops")
            return tops.first()
        }
        val row2 = row("Row wrapping", "Crash rows", "Error rows", "Row number")
        val row3 = row("Minimap", "Follow live logs", "Regex summary", "Original panel")
        val row4 = row("Ctrl+F opens", "Ctrl+F also shows Original", "Search scope behavior", "Link split selection and Find")
        val row5 = row("Video follow readout", "New video links:\ndouble-click seeks", "Process names in new tabs")
        val row6 = row("English proofreading", "English variant")
        assertTrue(row2 < row3 && row3 < row4 && row4 < row5 && row5 < row6, "rows out of order: $row2 $row3 $row4 $row5 $row6")

        // The proofreading cells sit in the grid's first two columns, left-aligned with the cells above them.
        assertEquals(left("Row wrapping"), left("English proofreading"))
        assertEquals(left("Crash rows"), left("English variant"))
        assertEquals(left("Video follow readout"), left("English proofreading"))
        assertEquals(left("New video links:\ndouble-click seeks"), left("English variant"))
    }

    @Test
    fun errorRowsCellSwitchesAndOpensTheStylePanelInAPopupUnderTheButton() {
        val state = AppState()
        openEditorBehavior(state)

        rule.onNodeWithTag("error-highlight-global-panel").assertDoesNotExist()
        rule.onNodeWithText("Style ▸").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("error-highlight-global-panel").assertExists()
        rule.onNodeWithText("Style ▾").assertExists()
        // The wide editor spells out the colour labels and previews on a sample Error row.
        rule.onNodeWithText("Background").assertExists()
        rule.onNodeWithText("Text").assertExists()
        rule.onNodeWithText("E AndroidRuntime: FATAL EXCEPTION: main").assertExists()
        // The popup opens under the button with its start edge aligned to the button's.
        val button = rule.onNodeWithTag("error-highlight-global-style-toggle").fetchSemanticsNode().boundsInRoot
        val panel = panelBounds()
        assertTrue(button.bottom <= panel.top, "panel $panel must open below the button $button")
        assertTrue(panel.left <= button.left, "panel $panel must not start right of the button $button")

        rule.onNodeWithText("Style ▾").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("error-highlight-global-panel").assertDoesNotExist()
        rule.onNodeWithText("Style ▸").assertExists()

        rule.runOnIdle { assertTrue(state.settings.errorHighlightStyle.enabled) }
        rule.onNode(hasText("Off") and hasAnyAncestor(hasTestTag("error-highlight-global-switch"))).performClick()
        rule.runOnIdle { assertFalse(state.settings.errorHighlightStyle.enabled) }
    }

    @Test
    fun styleEditsInThePopupReachTheGlobalSetting() {
        val state = AppState()
        openEditorBehavior(state)
        rule.onNodeWithText("Style ▸").performClick()
        rule.waitForIdle()

        assertEquals(null, state.settings.errorHighlightStyle.bold)
        rule.onNodeWithTag("error-highlight-global-bold-cycle").performClick()
        rule.runOnIdle { assertEquals(true, state.settings.errorHighlightStyle.bold) }
    }

    @Test
    fun clickingInsideANestedPickerKeepsTheStylePopupAndEscClosesTheInnermostFirst() {
        var dismissed = false
        val state = AppState()
        rule.setContent { SettingsDialog(state, onDismiss = { dismissed = true }) }
        type("minimap")
        rule.onNodeWithText("Minimap").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Style ▸").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("error-highlight-global-background-swatch-zone").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("error-highlight-global-background-picker").assertExists()
        // A click inside the nested picker does not count as an outside click for the style popup.
        rule.onNodeWithTag("error-highlight-global-background-custom-toggle").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("error-highlight-global-background-picker").assertExists()
        rule.onNodeWithTag("error-highlight-global-panel").assertExists()

        rule.onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        rule.onNodeWithTag("error-highlight-global-background-picker").assertDoesNotExist()
        rule.onNodeWithTag("error-highlight-global-panel").assertExists()

        // The font dropdown nests the same way.
        rule.onNodeWithTag("error-highlight-global-font-trigger").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("error-highlight-global-font-popup").assertExists()
        rule.onNodeWithTag("error-highlight-global-font-search").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("error-highlight-global-panel").assertExists()
        rule.onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        rule.onNodeWithTag("error-highlight-global-font-popup").assertDoesNotExist()
        rule.onNodeWithTag("error-highlight-global-panel").assertExists()

        rule.onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        rule.onNodeWithTag("error-highlight-global-panel").assertDoesNotExist()
        rule.onNodeWithText("Style ▸").assertExists()
        assertFalse(dismissed, "Esc that closed the popups must not also close the Settings dialog")

        // Focus is back on the dialog, so its own key handling (Ctrl/Cmd+F focuses the search) works again.
        rule.onAllNodes(isRoot()).onFirst().performKeyInput {
            val modifier = if (isMacOs) Key.MetaLeft else Key.CtrlLeft
            keyDown(modifier)
            pressKey(Key.F)
            keyUp(modifier)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("settings-search-field").assertIsFocused()
    }

    @Test
    fun clickingOutsideTheStylePopupDismissesIt() {
        val state = AppState()
        openEditorBehavior(state)
        rule.onNodeWithText("Style ▸").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("error-highlight-global-panel").assertExists()

        rule.onAllNodes(isRoot()).onFirst().performMouseInput { click(Offset(2f, 2f)) }
        rule.waitForIdle()
        rule.onNodeWithTag("error-highlight-global-panel").assertDoesNotExist()
        rule.onNodeWithText("Style ▸").assertExists()
    }

    @Test
    fun cellShowsTabOverridesAndResetTabsClearsThem() {
        val state = AppState()
        state.tabs = listOf(
            mkTab("a", "a.log", emptyList()).copy(errorHighlightStyle = ErrorHighlightStyle(color = Color.Magenta)),
            mkTab("b", "b.log", emptyList()),
        )
        openEditorBehavior(state)

        rule.onNodeWithTag("error-highlight-override-count").assertTextEquals("1 tab uses its own style")
        state.tabs = state.tabs.map { if (it.id == "b") it.copy(errorHighlightStyle = ErrorHighlightStyle(bold = true)) else it }
        rule.waitForIdle()
        rule.onNodeWithTag("error-highlight-override-count").assertTextEquals("2 tabs use their own style")

        rule.onNodeWithTag("error-highlight-reset-tabs").performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(state.tabs.all { it.errorHighlightStyle == null }) }
        rule.onNodeWithTag("error-highlight-override-count").assertDoesNotExist()
        rule.onNodeWithTag("error-highlight-reset-tabs").assertDoesNotExist()
    }

    @Test
    fun cellShowsNoOverrideLineWhenNoTabHasItsOwnStyle() {
        val state = AppState()
        state.tabs = listOf(mkTab("a", "a.log", emptyList()))
        openEditorBehavior(state)

        rule.onNodeWithTag("error-highlight-override-count").assertDoesNotExist()
        rule.onNodeWithTag("error-highlight-reset-tabs").assertDoesNotExist()
    }

    @Test
    fun scopeBehaviorOffersAFixedTwoSearchesDefault() {
        val state = AppState()
        rule.setContent { SettingsDialog(state, onDismiss = {}) }
        type("scope behavior")
        rule.onNodeWithText("Search scope behavior").performClick()
        rule.onNodeWithTag("search-scope-behavior-selector").performClick()
        rule.onNodeWithText("Two searches").performClick()
        rule.runOnIdle { assertEquals(SearchScopeBehavior.TWO_SEARCHES, state.settings.searchScopeBehavior) }
    }

    @Test
    fun actionKeyFFocusesTheSearchField() {
        install()
        rule.onNodeWithText("Workspace profile").assertExists()

        rule.onAllNodes(isRoot()).onFirst().performKeyInput {
            val modifier = if (isMacOs) Key.MetaLeft else Key.CtrlLeft
            keyDown(modifier)
            pressKey(Key.F)
            keyUp(modifier)
        }
        rule.waitForIdle()

        rule.onNodeWithTag("settings-search-field").assertIsFocused()
    }

    @Test
    fun aSeasonalResultOpensTheSeasonalTabOfTheThemeBlockAndShowsItsRow() {
        install()
        type("halloween")
        rule.onNodeWithText("Special theme").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("All results").assertDoesNotExist()
        // The Theme block is open on its Seasonal themes tab: the pack card and the When row are on screen.
        rule.onNodeWithTag(seasonalPackTag(com.indagium.model.HolidayPack.PUMPKIN_PATCH)).assertExists()
        rule.onNodeWithText("When it applies").assertExists()
    }
}
