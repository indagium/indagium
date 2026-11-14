package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** [SettingsGroup], [SettingsRow] and [SettingsDrillRow]: the card stacks its rows with a 1dp divider between neighbours. */
@OptIn(ExperimentalTestApi::class)
class SettingsGroupUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun groupPutsADividerBetweenRowsAndFollowsConditionalRows() {
        var showSecond by mutableStateOf(true)
        rule.setContent {
            SettingsGroup("Rows", modifier = Modifier.width(400.dp).testTag("group")) {
                Box(Modifier.testTag("r1").height(20.dp).width(50.dp))
                if (showSecond) Box(Modifier.testTag("r2").height(30.dp).width(50.dp))
                Box(Modifier.testTag("r3").height(10.dp).width(50.dp))
            }
        }
        rule.onNodeWithText("Rows").assertExists()
        val r1 = rule.onNodeWithTag("r1").getBoundsInRoot()
        val r2 = rule.onNodeWithTag("r2").getBoundsInRoot()
        val r3 = rule.onNodeWithTag("r3").getBoundsInRoot()
        assertEquals(r1.bottom.value + 1f, r2.top.value, 0.01f)
        assertEquals(r2.bottom.value + 1f, r3.top.value, 0.01f)

        rule.runOnIdle { showSecond = false }
        rule.waitForIdle()
        rule.onNodeWithTag("r2").assertDoesNotExist()
        val r1b = rule.onNodeWithTag("r1").getBoundsInRoot()
        val r3b = rule.onNodeWithTag("r3").getBoundsInRoot()
        assertEquals(r1b.bottom.value + 1f, r3b.top.value, 0.01f)
    }

    @Test
    fun rowShowsLabelAndDescriptionAndIndentMovesItRight() {
        rule.setContent {
            SettingsGroup("Group") {
                SettingsRow("Plain", "Explains plain") { Box(Modifier.width(10.dp).height(10.dp)) }
                SettingsRow("Nested", "Explains nested", indent = true, modifier = Modifier.testTag("nested")) { Box(Modifier.width(10.dp).height(10.dp)) }
            }
        }
        rule.onNodeWithText("Explains plain").assertExists()
        val plain = rule.onNodeWithText("Plain").getBoundsInRoot()
        val nested = rule.onNodeWithText("Nested").getBoundsInRoot()
        assertEquals(28f, nested.left.value - plain.left.value, 0.01f)
    }

    @Test
    fun drillRowClickCallsBack() {
        var clicks = 0
        rule.setContent {
            SettingsGroup("More") {
                SettingsDrillRow(Icons.Outlined.Build, "Tools", "adb and scrcpy", onClick = { clicks++ })
            }
        }
        rule.onNodeWithText("adb and scrcpy").assertExists()
        rule.onNodeWithText("Tools").performClick()
        rule.waitForIdle()
        assertEquals(1, clicks)
    }
}
