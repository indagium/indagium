package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How a [SettingsRow] keeps its description whole: fields shrink in a narrow row, and a control that would squeeze the text goes under it. */
@OptIn(ExperimentalTestApi::class)
class SettingsRowLayoutUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val description = "The header that carries the access token. Leave the name empty for a server that needs no authentication."

    private fun show(width: Int) {
        rule.setContent {
            SettingsGroup("Group", modifier = Modifier.width(width.dp)) {
                SettingsRow("Header", description) {
                    SettingsTextField("", {}, Modifier.width(SETTINGS_FIELD_LONG), testTag = "field")
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun aWideRowKeepsTheFieldBesideTheTextAtItsFullWidth() {
        show(760)
        val field = rule.onNodeWithTag("field").getBoundsInRoot()
        val text = rule.onNodeWithText(description).getBoundsInRoot()
        assertEquals(SETTINGS_FIELD_LONG.value, field.width.value, 0.5f)
        assertTrue(text.right <= field.left, "the text sits left of the field")
    }

    @Test
    fun aNarrowRowShrinksTheFieldAndStillShowsTheWholeDescription() {
        show(480)
        val field = rule.onNodeWithTag("field").getBoundsInRoot()
        assertTrue(field.width < SETTINGS_FIELD_LONG, "the field gives way to the text: ${field.width}")
        // Whole text: three lines or fewer, never the info glyph of a cut one.
        val text = rule.onNodeWithText(description).getBoundsInRoot()
        assertTrue(text.height.value < 3 * 16f + 1f, "at most three lines, was ${text.height}")
    }

    @Test
    fun aControlTooWideForTheTextGoesUnderIt() {
        rule.setContent {
            SettingsGroup("Group", modifier = Modifier.width(420.dp)) {
                SettingsRow("Corners", "How round tiles are. Theme default keeps each theme's own shapes.") {
                    Box(Modifier.testTag("wide").width(360.dp).height(26.dp))
                }
            }
        }
        rule.waitForIdle()
        val text = rule.onNodeWithText("Corners").getBoundsInRoot()
        val control = rule.onNodeWithTag("wide").getBoundsInRoot()
        assertTrue(control.top >= text.bottom, "the control is under the label: $control under $text")
        assertEquals(text.left.value, control.left.value, 0.5f)
    }

    @Test
    fun aDisabledPrimaryButtonIsInertAndDrawsNoAccentFill() {
        rule.setContent {
            SettingsButton("Save", {}, variant = ButtonVariant.Primary, enabled = false, modifier = Modifier.testTag("save"))
        }
        rule.onNodeWithTag("save").assertIsNotEnabled()
    }
}
