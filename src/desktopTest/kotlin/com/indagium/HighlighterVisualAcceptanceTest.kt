package com.indagium

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.indagium.ui.AppText
import com.indagium.ui.DARK_GITHUB
import com.indagium.ui.HighlighterColorPicker
import com.indagium.ui.LIGHT_THEME
import com.indagium.ui.LocalLogFontFamily
import com.indagium.ui.LocalTheme
import com.indagium.ui.LocalUiFontFamily
import com.indagium.ui.ThemeColors
import com.indagium.ui.UI
import com.indagium.ui.WARM_PAPER
import com.indagium.ui.monoFont
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val TRANSPARENT_TEST_COLOR = Color(0x8034ABCD)
private val OPAQUE_TEST_COLOR = Color(0xFF112233)

/** Exercises the actual popup and records light/dark images for manual layout verification. */
@OptIn(ExperimentalTestApi::class)
class HighlighterVisualAcceptanceTest {
    @get:Rule
    val rule = createComposeRule()

    private fun install(theme: ThemeColors, columns: Int) {
        var selected by mutableStateOf(TRANSPARENT_TEST_COLOR)
        var paletteColumns by mutableIntStateOf(columns)
        var open by mutableStateOf(true)
        var customEditorExpanded by mutableStateOf(true)
        var custom by mutableStateOf(listOf(TRANSPARENT_TEST_COLOR, OPAQUE_TEST_COLOR))
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides theme) {
                Box(Modifier.size(460.dp, 650.dp).background(theme.bg)) {
                    HighlighterColorPicker(
                        color = selected,
                        onColorChange = { selected = it },
                        customColors = custom,
                        onSaveCustomColor = { custom = (custom + it).distinct() },
                        onDeleteCustomColor = { color -> custom = custom.filterNot { it == color } },
                        paletteColumns = paletteColumns,
                        onPaletteColumnsChange = { paletteColumns = it },
                        customColorEditorExpanded = customEditorExpanded,
                        onCustomColorEditorExpandedChange = { customEditorExpanded = it },
                        pickerOpen = open,
                        onPickerOpenChange = { open = it },
                        testTagPrefix = "visual",
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun record(name: String) {
        val image = rule.onNodeWithTag("visual-picker").captureToImage()
        val pixels = image.toPixelMap()
        val rendered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) rendered.setRGB(x, y, pixels[x, y].toArgb())
        }
        val directory = File("build/highlighter-qa").apply { mkdirs() }
        assertTrue(ImageIO.write(rendered, "png", File(directory, "$name.png")))
    }

    @Test
    fun lightPopupSwitchesPagesAndClosesWithEscape() {
        install(LIGHT_THEME, 5)
        rule.onNodeWithText("Page 1 of 5").assertExists()
        rule.onNodeWithTag("visual-hex").assertExists()
        record("light-custom")
        rule.onNodeWithTag("visual-custom-toggle").performClick()
        rule.onNodeWithTag("visual-hex").assertDoesNotExist()
        record("light-pages")
        rule.onNodeWithTag("visual-next-page").performClick()
        rule.onNodeWithText("Page 2 of 5").assertExists()
        rule.onNodeWithTag("visual-prev-page").performClick()
        rule.onNodeWithText("Page 1 of 5").assertExists()
        rule.onNodeWithTag("visual-custom-toggle").performClick()
        rule.onNodeWithTag("visual-hex").performClick().performKeyInput { pressKey(Key.Escape) }
        rule.onNodeWithTag("visual-picker").assertDoesNotExist()
    }

    @Test
    fun darkPopupSwitchesToExpandedRectangle() {
        install(DARK_GITHUB, 5)
        record("dark-custom")
        rule.onNodeWithTag("visual-custom-toggle").performClick()
        record("dark-pages")
        rule.onNodeWithTag("visual-mode-rectangle").performClick()
        rule.onNodeWithText("Page 1 of 5").assertDoesNotExist()
        rule.onNodeWithTag("visual-palette").assertExists()
        record("dark-rectangle")
        rule.onNodeWithTag("visual-mode-pages").performClick()
        rule.onNodeWithText("Page 1 of 5").assertExists()
        rule.onNodeWithTag("visual-custom-toggle").performClick()
        record("dark-custom")
    }

    @Test
    fun warmPaperCustomEditorStartsOpenAndRemembersClosingAcrossPopupReopen() {
        install(WARM_PAPER, 5)
        rule.onNodeWithTag("visual-hex").assertExists()
        record("warm-paper-custom")
        rule.onNodeWithTag("visual-custom-toggle").performClick()
        rule.onNodeWithTag("visual-hex").assertDoesNotExist()
        record("warm-paper-pages")
        rule.onNodeWithTag("visual-custom-toggle").performKeyInput { pressKey(Key.Escape) }
        rule.onNodeWithTag("visual-picker").assertDoesNotExist()
        rule.onNodeWithTag("visual-trigger").performClick()
        rule.onNodeWithTag("visual-picker").assertExists()
        rule.onNodeWithTag("visual-hex").assertDoesNotExist()
        rule.onNodeWithTag("visual-custom-toggle").performClick()
        rule.onNodeWithTag("visual-hex").assertExists()
    }

    @Test
    fun changingInterfaceFontDoesNotChangeDefaultProportionalLogPreview() {
        var interfaceFamily by mutableStateOf<FontFamily>(FontFamily.Serif)
        rule.setContent {
            CompositionLocalProvider(
                LocalUiFontFamily provides interfaceFamily,
                LocalLogFontFamily provides FontFamily.Default,
            ) {
                Column {
                    AppText("Interface", fontFamily = UI, modifier = Modifier.testTag("interface-sample"))
                    AppText("Log preview", fontFamily = monoFont(), modifier = Modifier.testTag("log-sample"))
                }
            }
        }

        fun familyAt(tag: String): FontFamily? {
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style.fontFamily
        }

        assertEquals(FontFamily.Serif, familyAt("interface-sample"))
        assertEquals(FontFamily.Default, familyAt("log-sample"))
        rule.runOnIdle { interfaceFamily = FontFamily.SansSerif }
        assertEquals(FontFamily.SansSerif, familyAt("interface-sample"))
        assertEquals(FontFamily.Default, familyAt("log-sample"))
    }
}
