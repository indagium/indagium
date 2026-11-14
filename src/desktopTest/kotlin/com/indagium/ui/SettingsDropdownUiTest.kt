package com.indagium.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The [SettingsDropdown] widget: opens on click, a pick calls onSelect and closes, options carry their tags, onInteraction fires. */
@OptIn(ExperimentalTestApi::class)
class SettingsDropdownUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val choices = listOf("alpha", "beta", "gamma")

    @Test
    fun clickOpensTheListAndPickingAnOptionSelectsAndCloses() {
        val picked = mutableListOf<String>()
        var interactions = 0
        rule.setContent {
            var selected by remember { mutableStateOf("alpha") }
            SettingsDropdown(
                options = choices,
                selected = selected,
                label = { it.uppercase() },
                onSelect = { selected = it; picked += it },
                testTag = "dd",
                onInteraction = { interactions++ },
            )
        }
        rule.onNodeWithTag("dd-option-1").assertDoesNotExist()

        rule.onNodeWithTag("dd").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("dd-option-1").assertExists()

        rule.onNodeWithTag("dd-option-1").performClick()
        rule.waitForIdle()

        assertEquals(listOf("beta"), picked)
        rule.onNodeWithTag("dd-option-1").assertDoesNotExist()
        rule.onNodeWithText("BETA").assertExists()
        // One call for opening, one for the pick.
        assertEquals(2, interactions)
    }

    @Test
    fun customOptionTagsReplaceTheIndexedDefault() {
        rule.setContent {
            SettingsDropdown(
                options = choices,
                selected = "gamma",
                label = { it },
                onSelect = {},
                testTag = "dd",
                optionTag = { "pick-$it" },
            )
        }
        rule.onNodeWithTag("dd").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("pick-alpha").assertExists()
        rule.onNodeWithTag("pick-gamma").assertExists()
        rule.onNodeWithTag("dd-option-0").assertDoesNotExist()
    }

    @Test
    fun clickingTheFieldAgainClosesTheList() {
        rule.setContent { SettingsDropdown(options = choices, selected = "alpha", label = { it }, onSelect = {}, testTag = "dd") }
        rule.onNodeWithTag("dd").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("dd-option-0").assertExists()

        rule.onNodeWithTag("dd").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("dd-option-0").assertDoesNotExist()
    }
}
