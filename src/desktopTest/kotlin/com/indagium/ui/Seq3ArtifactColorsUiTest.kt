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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
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
import kotlin.test.assertFalse
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

    private fun setPopoverContent(
        initial: Seq3ArtifactStyle? = null,
        showKindWord: Boolean? = null,
        onStyle: (Seq3ArtifactStyle?) -> Unit = {},
        onKindWord: (Boolean) -> Unit = {},
    ) {
        style = initial
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(320.dp, 360.dp)) {
                    Seq3ArtifactColorAction(
                        state = state,
                        style = style,
                        onStyleChange = { changed ->
                            onStyle(changed)
                            style = changed
                        },
                        fillDefault = FILL_DEFAULT,
                        borderDefault = BORDER_DEFAULT,
                        textDefault = TEXT_DEFAULT,
                        showKindWord = showKindWord,
                        onShowKindWordChange = onKindWord,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun openPopover() {
        rule.onNodeWithTag("seq3-artifact-color-action-button").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-artifact-color-popover").assertIsDisplayed()
    }

    private var style by mutableStateOf<Seq3ArtifactStyle?>(null)

    private companion object {
        val FILL_DEFAULT = Color(0xFFFFF0C2)
        val BORDER_DEFAULT = Color(0xFFB36B00)
        val TEXT_DEFAULT = Color(0xFF593600)
    }

    @Test
    fun triggerIsAStyleSwatchAndPopoverListsLabelledRowsWithHexAndNoGlyphButtons() {
        setPopoverContent()
        val action = rule.onNodeWithTag("seq3-artifact-color-action")
        action.assertIsDisplayed()
        val actionHeightDp = action.fetchSemanticsNode().boundsInRoot.height / rule.density.density
        assertTrue(actionHeightDp <= 24f, "the style trigger stays swatch-sized, height=${actionHeightDp}dp")
        openPopover()

        val widthDp = rule.onNodeWithTag("seq3-artifact-color-popover").fetchSemanticsNode()
            .boundsInRoot.width / rule.density.density
        assertEquals(260f, widthDp, 1f, "the popover is 260dp wide")
        mapOf("fill" to "Fill", "border" to "Border", "text" to "Text").forEach { (field, label) ->
            rule.onNodeWithTag("seq3-artifact-$field-label").assertIsDisplayed().assertTextEquals(label)
            rule.onNodeWithTag("seq3-artifact-$field-trigger").assertIsDisplayed()
            rule.onNodeWithTag("seq3-artifact-$field-hex").assertIsDisplayed()
        }
        rule.onNodeWithTag("seq3-artifact-fill-hex").assertTextEquals("#FFF0C2")
        rule.onNodeWithTag("seq3-artifact-border-hex").assertTextEquals("#B36B00")
        rule.onNodeWithTag("seq3-artifact-text-hex").assertTextEquals("#593600")
        rule.onAllNodes(hasText("↺"), useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(hasText("Default"), useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(hasTestTag("seq3-artifact-kind-word"), useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithTag("seq3-artifact-colors-reset").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithText("Reset all to diagram colours").assertIsDisplayed()
        saveCompactControlsScreenshot("seq3-artifact-color-popover")
    }

    @Test
    fun defaultLinkAppearsOnlyForOverriddenColoursAndResetsThatColour() {
        val changes = mutableStateListOf<Seq3ArtifactStyle?>()
        setPopoverContent(
            initial = Seq3ArtifactStyle(fillArgb = Color.Green.toArgb(), textArgb = Color.Red.toArgb()),
            onStyle = { changes += it },
        )
        openPopover()
        rule.onNodeWithTag("seq3-artifact-fill-reset").assertIsDisplayed().assertTextEquals("Default")
        rule.onNodeWithTag("seq3-artifact-text-reset").assertIsDisplayed()
        rule.onAllNodes(hasTestTag("seq3-artifact-border-reset"), useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithTag("seq3-artifact-fill-hex").assertTextEquals("#00FF00")
        rule.onNodeWithTag("seq3-artifact-colors-reset").assertIsEnabled()

        rule.onNodeWithTag("seq3-artifact-fill-reset").performClick()
        rule.waitForIdle()
        assertEquals(Seq3ArtifactStyle(textArgb = Color.Red.toArgb()), style, "only the fill override is cleared")
        rule.onAllNodes(hasTestTag("seq3-artifact-fill-reset"), useUnmergedTree = true).assertCountEquals(0)

        rule.onNodeWithTag("seq3-artifact-text-reset").performClick()
        rule.waitForIdle()
        assertNull(style, "clearing the last override restores the diagram defaults entirely")
        assertEquals(2, changes.size)
        assertNull(changes.last())
    }

    @Test
    fun resetAllClearsEveryOverrideInOneUpdate() {
        val changes = mutableStateListOf<Seq3ArtifactStyle?>()
        setPopoverContent(
            initial = Seq3ArtifactStyle(Color.Green.toArgb(), Color.Blue.toArgb(), Color.Red.toArgb()),
            onStyle = { changes += it },
        )
        openPopover()
        rule.onNodeWithTag("seq3-artifact-colors-reset").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertNull(style)
        assertEquals(1, changes.size)
        assertNull(changes.single())
        rule.onNodeWithTag("seq3-artifact-colors-reset").assertIsNotEnabled()
    }

    @Test
    fun pickingAColourEmitsOneUpdateWhenItsPickerCloses() {
        val changes = mutableStateListOf<Seq3ArtifactStyle?>()
        setPopoverContent(onStyle = { changes += it })
        openPopover()
        rule.onNodeWithTag("seq3-artifact-fill-trigger").performClick()
        rule.onNodeWithTag("seq3-artifact-fill-swatch-0").assertIsDisplayed().performClick()
        rule.onNodeWithTag("seq3-artifact-fill-swatch-1").performClick()
        assertEquals(0, changes.size, "picker edits stay staged while the popup is open")
        rule.onNodeWithTag("seq3-artifact-fill-picker").performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        rule.onNodeWithTag("seq3-artifact-color-popover").assertIsDisplayed()
        assertEquals(HL_COLORS[1].toArgb(), style?.fillArgb)
        assertEquals(1, changes.size, "closing after edits emits one style update")
        rule.onNodeWithTag("seq3-artifact-fill-reset").assertIsDisplayed()

        rule.onNodeWithTag("seq3-artifact-color-popover").performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        rule.onAllNodes(hasTestTag("seq3-artifact-color-popover"), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun fragmentKindWordCheckboxReportsTheInverseOfHideKindLabel() {
        val requested = mutableStateListOf<Boolean>()
        setPopoverContent(showKindWord = true, onKindWord = { requested += it })
        openPopover()
        rule.onNodeWithTag("seq3-artifact-kind-word").assertIsDisplayed()
        rule.onNodeWithText("Show kind word on canvas").assertIsDisplayed()
        rule.onNodeWithTag("seq3-artifact-kind-word").performClick()
        rule.waitForIdle()
        assertEquals(listOf(false), requested, "unchecking asks to hide the kind word")
        rule.onAllNodes(hasText("Op"), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun hexLabelDropsTheAlphaDigitsOnlyForOpaqueColours() {
        assertEquals("#B36B00", seq3ArtifactHexLabel(Color(0xFFB36B00)))
        assertEquals("#12112233", seq3ArtifactHexLabel(Color(0x12112233)))
    }

    @Test
    fun popoverOpensBelowTheTriggerAndStaysInsideTheWindow() {
        val window = IntSize(800, 600)
        val popup = IntSize(260, 200)
        assertEquals(IntOffset(100, 134), seq3PopoverPosition(IntRect(100, 100, 120, 130), popup, window, gapPx = 4))
        assertEquals(
            IntOffset(536, 134),
            seq3PopoverPosition(IntRect(700, 100, 720, 130), popup, window, gapPx = 4),
            "a trigger near the right edge shifts the popover left to fit",
        )
        assertEquals(
            IntOffset(100, 276),
            seq3PopoverPosition(IntRect(100, 480, 120, 500), popup, window, gapPx = 4),
            "with no room below the popover opens above the trigger",
        )
    }

    @Test
    fun swatchesNeedAnOutlineOnlyWhenTheyVanishIntoTheirBackdrop() {
        val panel = Color.White
        assertTrue(colorSwatchNeedsOutline(Color(0xFFFAFAFA), panel))
        assertTrue(colorSwatchNeedsOutline(Color(0x12B36B00).compositeOver(panel), panel), "a 7% wash is nearly invisible")
        assertFalse(colorSwatchNeedsOutline(Color(0xFFB36B00), panel))
        assertTrue(colorSwatchNeedsOutline(Color(0xFF101010), Color(0xFF181818)), "dark on dark vanishes too")
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
