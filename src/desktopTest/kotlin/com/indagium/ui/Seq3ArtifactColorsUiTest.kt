package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.indagium.diagram3.Seq3ArtifactStyle
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Seq3ArtifactColorsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val root = createTempDirectory("seq3-artifact-colors-ui").toFile()
    private val state = AppState(
        autosaveFile = File(root, "state.cache"),
        notesDir = File(root, "notes"),
    )

    @After
    fun tearDown() {
        state.close()
        root.deleteRecursively()
    }

    @Test
    fun compactControlsPickOneColorPerCloseAndExposeClearDefaultResets() {
        var style by mutableStateOf<Seq3ArtifactStyle?>(null)
        val changes = mutableStateListOf<Seq3ArtifactStyle?>()
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(250.dp, 48.dp)) {
                    Seq3ArtifactColorAction(
                        state = state,
                        style = style,
                        onStyleChange = { changed ->
                            changes += changed
                            style = changed
                        },
                        fillDefault = Color(0xFFFFF0C2),
                        borderDefault = Color(0xFFB36B00),
                        textDefault = Color(0xFF593600),
                    )
                }
            }
        }
        rule.waitForIdle()

        val action = rule.onNodeWithTag("seq3-artifact-color-action")
        action.assertIsDisplayed()
        val actionHeightDp = action.fetchSemanticsNode().boundsInRoot.height / rule.density.density
        assertTrue(actionHeightDp <= 22f, "the artifact action stays icon-sized, height=${actionHeightDp}dp")
        rule.onNodeWithTag("seq3-artifact-color-action-button").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-artifact-color-popover").assertIsDisplayed()

        val controls = rule.onNodeWithTag("seq3-artifact-colors")
        controls.assertIsDisplayed()
        val heightDp = controls.fetchSemanticsNode().boundsInRoot.height / rule.density.density
        assertTrue(heightDp <= 28f, "the artifact colors take one compact row, height=${heightDp}dp")

        listOf("fill", "border", "text").forEach { field ->
            rule.onNodeWithTag("seq3-artifact-$field-label").assertIsDisplayed()
            rule.onNodeWithTag("seq3-artifact-$field-trigger").assertIsDisplayed()
            rule.onNodeWithTag("seq3-artifact-$field-reset").assertIsDisplayed()
            rule.onAllNodes(hasTestTag("seq3-artifact-$field-value"), useUnmergedTree = true).assertCountEquals(0)
        }
        rule.onNodeWithTag("seq3-artifact-colors-reset").assertIsDisplayed()
        rule.onAllNodes(hasText("Theme"), useUnmergedTree = true).assertCountEquals(0)
        val resetDescription = rule.onNodeWithTag("seq3-artifact-fill-reset")
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        assertEquals("Use diagram default color", resetDescription.single())
        saveCompactControlsScreenshot("seq3-artifact-color-popover")

        rule.onNodeWithTag("seq3-artifact-fill-trigger").performClick()
        rule.onNodeWithTag("seq3-artifact-fill-swatch-0").assertIsDisplayed().performClick()
        rule.onNodeWithTag("seq3-artifact-fill-swatch-1").performClick()
        assertEquals(0, changes.size, "picker edits stay staged while the popup is open")
        rule.onNodeWithTag("seq3-artifact-fill-picker").performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-artifact-color-popover").assertIsDisplayed()
        val pickedArgb = HL_COLORS[1].toArgb()
        assertEquals(pickedArgb, style?.fillArgb)
        assertEquals(1, changes.size, "closing after edits emits one style update")

        rule.onNodeWithTag("seq3-artifact-color-popover")
            .performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        rule.onAllNodes(hasTestTag("seq3-artifact-color-popover"), useUnmergedTree = true)
            .assertCountEquals(0)
        rule.onNodeWithTag("seq3-artifact-color-action-button").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-artifact-color-popover").assertIsDisplayed()

        rule.onNodeWithTag("seq3-artifact-fill-reset").performClick()
        rule.waitForIdle()
        assertNull(style, "a field-level reset restores diagram defaults when it was the only override")
        assertEquals(2, changes.size)
        assertNull(changes.last())

        rule.runOnIdle {
            style = Seq3ArtifactStyle(
                fillArgb = Color.Green.toArgb(),
                borderArgb = Color.Blue.toArgb(),
                textArgb = Color.Red.toArgb(),
            )
        }
        rule.onNodeWithTag("seq3-artifact-colors-reset").performClick()
        rule.waitForIdle()
        assertNull(style, "the compact reset-all icon restores every field's diagram default")
        assertEquals(3, changes.size)
        assertNull(changes.last())
    }

    private fun saveCompactControlsScreenshot(tag: String) {
        val image = rule.onNodeWithTag(tag).captureToImage()
        val pixels = image.toPixelMap()
        val rendered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) rendered.setRGB(x, y, pixels[x, y].toArgb())
        }
        val output = File("temp_visuals_not_for_git/seq3-compact-colors.png")
        output.parentFile?.mkdirs()
        assertTrue(ImageIO.write(rendered, "png", output))
    }
}
