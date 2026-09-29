package com.indagium

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import com.indagium.ui.AppState
import com.indagium.ui.SettingsDialog
import com.indagium.ui.isMacOs
import org.junit.Rule
import org.junit.Test

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
    fun actionKeyFFocusesTheSearchField() {
        install()
        rule.onNodeWithText("Workspace profile").assertExists()

        rule.onRoot().performKeyInput {
            val modifier = if (isMacOs) Key.MetaLeft else Key.CtrlLeft
            keyDown(modifier)
            pressKey(Key.F)
            keyUp(modifier)
        }
        rule.waitForIdle()

        rule.onNodeWithTag("settings-search-field").assertIsFocused()
    }
}
