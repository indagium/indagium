package com.indagium.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The [SettingsSwitch] widget: click toggles, it is announced as a Switch, a disabled one ignores clicks, and the tag lands on it. */
@OptIn(ExperimentalTestApi::class)
class SettingsSwitchUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun clickReportsTheOppositeValueAndTheSwitchFollowsTheState() {
        val reported = mutableListOf<Boolean>()
        rule.setContent {
            var checked by remember { mutableStateOf(false) }
            SettingsSwitch(checked = checked, onCheckedChange = { checked = it; reported += it }, label = "Minimap", testTag = "sw")
        }
        rule.onNodeWithTag("sw").assertIsOff()

        rule.onNodeWithTag("sw").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("sw").assertIsOn()
        rule.onNodeWithTag("sw").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("sw").assertIsOff()

        assertEquals(listOf(true, false), reported)
    }

    @Test
    fun semanticsRoleIsSwitchAndTheLabelIsTheContentDescription() {
        rule.setContent { SettingsSwitch(checked = true, onCheckedChange = {}, label = "Row numbers", testTag = "sw") }

        rule.onNodeWithTag("sw").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        rule.onNodeWithContentDescription("Row numbers").assertIsOn()
    }

    @Test
    fun disabledSwitchIgnoresClicks() {
        var calls = 0
        rule.setContent { SettingsSwitch(checked = false, onCheckedChange = { calls++ }, label = "Minimap", enabled = false, testTag = "sw") }

        rule.onNodeWithTag("sw").assertIsNotEnabled()
        rule.onNodeWithTag("sw").performClick()
        rule.waitForIdle()

        assertEquals(0, calls)
        rule.onNodeWithTag("sw").assertIsOff()
    }

    @Test
    fun testTagIsAppliedToTheSwitchNode() {
        rule.setContent { SettingsSwitch(checked = false, onCheckedChange = {}, label = "Minimap", testTag = "my-switch") }

        rule.onNodeWithTag("my-switch").assertExists()
    }
}
