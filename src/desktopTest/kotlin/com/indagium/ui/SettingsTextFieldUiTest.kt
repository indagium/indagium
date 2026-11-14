package com.indagium.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The Settings pages' one text field, its numeric variant and the compact empty state. */
@OptIn(ExperimentalTestApi::class)
class SettingsTextFieldUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun aTextFieldIs26dpTallDrawsItsBorderAndKeepsWhatIsTyped() {
        var typed = ""
        rule.setContent {
            Column(Modifier.width(300.dp)) {
                var text by remember { mutableStateOf("") }
                SettingsTextField(text, { text = it; typed = it }, Modifier.width(200.dp), placeholder = "Profile name", testTag = "field")
            }
        }
        rule.onNodeWithText("Profile name").assertExists()
        val bounds = rule.onNodeWithTag("field").getBoundsInRoot()
        assertEquals(26.dp, bounds.height)
        assertEquals(200.dp, bounds.width)

        // A 1dp border: the outermost pixel ring differs from the fill just inside it.
        val pixels = rule.onNodeWithTag("field").captureToImage().toPixelMap()
        assertNotEquals(pixels[0, pixels.height / 2], pixels[3, pixels.height / 2], "the field draws a border around its fill")

        rule.onNodeWithTag("field").performTextInput("LM Studio")
        rule.waitForIdle()
        assertEquals("LM Studio", typed)
        rule.onNodeWithTag("field").assertTextEquals("LM Studio")
        rule.onNodeWithText("Profile name").assertDoesNotExist()
    }

    @Test
    fun aDisabledTextFieldTakesNoInput() {
        var typed = ""
        rule.setContent { SettingsTextField("kept", { typed = it }, enabled = false, testTag = "field") }
        rule.onNodeWithTag("field").assertIsNotEnabled()
        runCatching { rule.onNodeWithTag("field").performTextInput("x") }
        rule.waitForIdle()
        assertEquals("", typed)
    }

    @Test
    fun aNumberFieldKeepsDigitsOnlyInAFixedWidthAndShowsItsUnitAfterIt() {
        var typed = ""
        rule.setContent {
            Column(Modifier.width(300.dp)) {
                var text by remember { mutableStateOf("5") }
                SettingsNumberField(text, { text = it; typed = it }, unit = "min", maxDigits = 3, testTag = "number")
            }
        }
        val field = rule.onNodeWithTag("number").getBoundsInRoot()
        assertEquals(26.dp, field.height)
        assertEquals(SETTINGS_NUMBER_FIELD_WIDTH, field.width)
        val unit = rule.onNodeWithText("min").getBoundsInRoot()
        assertTrue(unit.left >= field.right, "the unit follows the field")

        rule.onNodeWithTag("number").performTextInput("a1b2c3d4")
        rule.waitForIdle()
        // Typed in front of the "5" that was there: the letters are dropped and the digits stop at the third.
        assertEquals("123", typed)
    }

    @Test
    fun anEmptyStateIsOneBorderedLineOfText() {
        rule.setContent { SettingsEmptyState("No commands yet.") }
        rule.onNodeWithText("No commands yet.").assertExists()
        rule.onNodeWithTag(SETTINGS_EMPTY_STATE_TAG).assertExists()
        val height = rule.onNodeWithTag(SETTINGS_EMPTY_STATE_TAG).getBoundsInRoot().height
        assertTrue(height < 48.dp, "a compact line, not a list box: $height")
    }
}
