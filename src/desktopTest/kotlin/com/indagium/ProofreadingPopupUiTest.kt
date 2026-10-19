package com.indagium

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.click
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.IntSize
import com.indagium.model.ProofreadingSettings
import com.indagium.proofread.EnglishProofreader
import com.indagium.proofread.EnglishProofreadingService
import com.indagium.proofread.ProofreadingIssue
import com.indagium.ui.AnnotationMarkdownEditorDialog
import com.indagium.ui.LIGHT_THEME
import com.indagium.ui.LocalProofreadingUi
import com.indagium.ui.LocalTheme
import com.indagium.ui.ProofreadingUi
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

class ProofreadingPopupUiTest {
    @get:Rule
    val rule = createComposeRule()

    private var service: EnglishProofreadingService? = null

    @After
    fun tearDown() {
        service?.close()
    }

    @Test
    fun popupStaysBesideCaretInsideNarrowEditorAndSavesVisualEvidence() {
        val proofreading = proofreadingUi { text ->
            val start = text.indexOf("speling")
            if (start < 0) emptyList()
            else listOf(ProofreadingIssue(start, start + 7, "Possible spelling mistake", listOf("spelling"), "test"))
        }
        setContent(proofreading) {
            AnnotationMarkdownEditorDialog(
                title = "Edit note",
                initialText = "A speling mistake in this investigation note.",
                confirmLabel = "Save note",
                windowSize = IntSize(620, 760),
                sessionKey = "proofreading-popup-position",
                onConfirm = {},
                onDismiss = {},
            )
        }

        val editor = rule.onNodeWithTag("annotation-markdown-editor")
        editor.performMouseInput { click() }
        editor.performTextInputSelection(TextRange(4))
        val popup = rule.onNodeWithTag("proofreading-suggestion-popup")
        rule.waitUntil(5_000) {
            runCatching { popup.fetchSemanticsNode() }.isSuccess
        }
        popup.assertIsDisplayed()

        val editorBounds = editor.fetchSemanticsNode().boundsInRoot
        val popupBounds = popup.fetchSemanticsNode().boundsInRoot
        val dialog = rule.onNodeWithTag("annotation-editor-dialog")
        val dialogBounds = dialog.fetchSemanticsNode().boundsInRoot
        assertTrue(popupBounds.left >= dialogBounds.left && popupBounds.right <= dialogBounds.right, "popup must fit horizontally in the note dialog")
        assertTrue(popupBounds.top >= dialogBounds.top && popupBounds.bottom <= dialogBounds.bottom, "popup must fit vertically in the note dialog")
        assertTrue(
            popupBounds.top in (editorBounds.top + 8f)..(editorBounds.top + 100f),
            "popup should sit beside the active first text line, not at the root corner",
        )
        assertTrue(
            popupBounds.left in (editorBounds.left + 8f)..(editorBounds.left + 140f),
            "popup should be horizontally anchored near the selected word",
        )

        val dialogImage = dialog.captureToImage()
        val popupImage = popup.captureToImage()
        val dialogPixels = dialogImage.toPixelMap()
        val popupPixels = popupImage.toPixelMap()
        val rendered = BufferedImage(dialogImage.width, dialogImage.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until dialogImage.height) for (x in 0 until dialogImage.width) {
            rendered.setRGB(x, y, dialogPixels[x, y].toArgb())
        }
        val popupLeft = (popupBounds.left - dialogBounds.left).toInt()
        val popupTop = (popupBounds.top - dialogBounds.top).toInt()
        for (y in 0 until popupImage.height) for (x in 0 until popupImage.width) {
            val targetX = popupLeft + x
            val targetY = popupTop + y
            if (targetX in 0 until rendered.width && targetY in 0 until rendered.height) {
                rendered.setRGB(targetX, targetY, popupPixels[x, y].toArgb())
            }
        }
        val output = File("build/qa/proofreading-popup").apply { mkdirs() }
        assertTrue(ImageIO.write(rendered, "png", File(output, "narrow-caret-anchor.png")))
    }

    @Test
    fun ignoreForSessionRemovesEveryMatchingOccurrenceImmediately() {
        val proofreading = proofreadingUi { text ->
            Regex("speling").findAll(text).map { match ->
                ProofreadingIssue(match.range.first, match.range.last + 1, "Possible spelling mistake", listOf("spelling"), "same-rule")
            }.toList()
        }
        setContent(proofreading) {
            AnnotationMarkdownEditorDialog(
                title = "Edit note",
                initialText = "speling then speling",
                confirmLabel = "Save note",
                windowSize = IntSize(760, 760),
                sessionKey = "proofreading-ignore-duplicates",
                onConfirm = {},
                onDismiss = {},
            )
        }

        val editor = rule.onNodeWithTag("annotation-markdown-editor")
        editor.performMouseInput { click() }
        editor.performTextInputSelection(TextRange(3))
        val popup = rule.onNodeWithTag("proofreading-suggestion-popup")
        rule.waitUntil(5_000) { runCatching { popup.fetchSemanticsNode() }.isSuccess }
        popup.assertIsDisplayed()
        rule.onNodeWithText("Ignore").performMouseInput { click() }
        editor.performTextInputSelection(TextRange(16))
        rule.waitForIdle()
        assertTrue(runCatching { rule.onNodeWithTag("proofreading-suggestion-popup").fetchSemanticsNode() }.isFailure)
    }

    @Test
    fun altEnterFocusesFirstReplacementAndEnterAppliesIt() {
        val proofreading = proofreadingUi { text ->
            val start = text.indexOf("speling")
            if (start < 0) emptyList()
            else listOf(ProofreadingIssue(start, start + 7, "Possible spelling mistake", listOf("spelling"), "keyboard-replace"))
        }
        setContent(proofreading) {
            AnnotationMarkdownEditorDialog(
                title = "Edit note",
                initialText = "A speling mistake",
                confirmLabel = "Save note",
                windowSize = IntSize(760, 760),
                sessionKey = "proofreading-keyboard-replace",
                onConfirm = {},
                onDismiss = {},
            )
        }

        val editor = rule.onNodeWithTag("annotation-markdown-editor")
        val popup = rule.onNodeWithTag("proofreading-suggestion-popup")
        editor.performMouseInput { click() }
        editor.performTextInputSelection(TextRange(2, 9))
        rule.waitUntil(5_000) { runCatching { popup.fetchSemanticsNode() }.isSuccess }
        editor.assertIsFocused()
        rule.onNodeWithTag("proofreading-suggestion-keyboard-hint").assertIsDisplayed()

        editor.performKeyInput {
            withKeyDown(Key.AltLeft) { pressKey(Key.Enter) }
        }
        rule.waitForIdle()
        val replacement = rule.onNodeWithTag("proofreading-suggestion-option-0")
        replacement.assertIsFocused()
        replacement.performKeyInput { pressKey(Key.Enter) }

        editor.assertTextEquals("A spelling mistake")
        assertPopupMissing()
    }

    @Test
    fun escapeReturnsToEditorAndTabNavigationCanIgnoreWithoutEditing() {
        val proofreading = proofreadingUi { text ->
            val start = text.indexOf("speling")
            if (start < 0) emptyList()
            else listOf(ProofreadingIssue(start, start + 7, "Possible spelling mistake", listOf("spelling"), "keyboard-ignore"))
        }
        setContent(proofreading) {
            AnnotationMarkdownEditorDialog(
                title = "Edit note",
                initialText = "A speling mistake",
                confirmLabel = "Save note",
                windowSize = IntSize(760, 760),
                sessionKey = "proofreading-keyboard-ignore",
                onConfirm = {},
                onDismiss = {},
            )
        }

        val editor = rule.onNodeWithTag("annotation-markdown-editor")
        val popup = rule.onNodeWithTag("proofreading-suggestion-popup")
        editor.performMouseInput { click() }
        editor.performTextInputSelection(TextRange(4))
        rule.waitUntil(5_000) { runCatching { popup.fetchSemanticsNode() }.isSuccess }
        editor.performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.Enter) } }
        rule.waitForIdle()
        popup.performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()

        editor.assertIsFocused()
        editor.assertTextEquals("A speling mistake")
        assertPopupMissing()

        editor.performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.Enter) } }
        rule.waitForIdle()
        val replacement = rule.onNodeWithTag("proofreading-suggestion-option-0")
        replacement.assertIsFocused()
        popup.performKeyInput { pressKey(Key.Tab) }
        val ignore = rule.onNodeWithTag("proofreading-suggestion-ignore")
        ignore.assertIsFocused()
        ignore.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        replacement.assertIsFocused()
        popup.performKeyInput { pressKey(Key.Tab) }
        ignore.assertIsFocused()
        ignore.performKeyInput { pressKey(Key.Spacebar) }

        editor.assertTextEquals("A speling mistake")
        assertPopupMissing()
    }

    private fun proofreadingUi(checker: (String) -> List<ProofreadingIssue>): ProofreadingUi {
        val localService = EnglishProofreadingService {
            EnglishProofreader { text, _ -> checker(text) }
        }.also { service = it }
        return ProofreadingUi(localService, ProofreadingSettings(), mutableSetOf())
    }

    private fun assertPopupMissing() {
        assertTrue(runCatching { rule.onNodeWithTag("proofreading-suggestion-popup").fetchSemanticsNode() }.isFailure)
    }

    private fun setContent(proofreading: ProofreadingUi, content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(
                LocalTheme provides LIGHT_THEME,
                LocalProofreadingUi provides proofreading,
            ) { content() }
        }
    }
}
