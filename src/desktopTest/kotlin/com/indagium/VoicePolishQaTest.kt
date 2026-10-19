@file:Suppress("MagicNumber")

package com.indagium

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.indagium.model.ProofreadingSettings
import com.indagium.model.VoiceInputSettings
import com.indagium.model.VoiceRecognitionEngine
import com.indagium.proofread.EnglishProofreader
import com.indagium.proofread.EnglishProofreadingService
import com.indagium.proofread.ProofreadingCategory
import com.indagium.proofread.ProofreadingIssue
import com.indagium.ui.AnnotationMarkdownEditorDialog
import com.indagium.ui.DARK_GITHUB
import com.indagium.ui.LIGHT_THEME
import com.indagium.ui.LocalProofreadingUi
import com.indagium.ui.LocalTheme
import com.indagium.ui.LocalVoiceInputUi
import com.indagium.ui.MarkdownInlineOrTextField
import com.indagium.ui.ProofreadingUi
import com.indagium.ui.ThemeColors
import com.indagium.ui.VoiceInputUi
import com.indagium.ui.tc
import com.indagium.voice.VoiceAudio
import com.indagium.voice.VoiceCapture
import com.indagium.voice.VoiceCaptureResult
import com.indagium.voice.VoiceCaptureSession
import com.indagium.voice.VoiceCaptureStartResult
import com.indagium.voice.VoiceInputController
import com.indagium.voice.VoiceInputRuntime
import com.indagium.voice.VoiceInputSessionCoordinator
import com.indagium.voice.VoiceInputState
import com.indagium.voice.VoiceModelInstaller
import com.indagium.voice.VoiceTranscript
import com.indagium.voice.VoiceTranscriptionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.assertTrue

/**
 * Writes the review screenshots for the dictation and proofreading polish to build/qa/voice-polish. Rendered at 1.25x
 * so 11sp text is legible; each scene is captured in the light and the dark theme.
 */
class VoicePolishQaTest {
    @get:Rule
    val rule = createComposeRule()

    private val directory = createTempDirectory("voice-polish-qa").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var coordinator: VoiceInputSessionCoordinator? = null
    private var proofreadingService: EnglishProofreadingService? = null
    private val output = File("build/qa/voice-polish").apply { mkdirs() }

    @After
    fun tearDown() {
        coordinator?.close()
        proofreadingService?.close()
        scope.cancel()
        directory.deleteRecursively()
    }

    @Test
    fun lightDialogToolbar() = dialogToolbar(LIGHT_THEME, "light")

    @Test
    fun darkDialogToolbar() = dialogToolbar(DARK_GITHUB, "dark")

    @Test
    fun lightInlineField() = inlineField(LIGHT_THEME, "light")

    @Test
    fun darkInlineField() = inlineField(DARK_GITHUB, "dark")

    @Test
    fun lightProofreadingPopup() = proofreadingPopup(LIGHT_THEME, "light")

    @Test
    fun darkProofreadingPopup() = proofreadingPopup(DARK_GITHUB, "dark")

    private fun dialogToolbar(theme: ThemeColors, name: String) {
        setContent(theme) { dialog("toolbar-$name") }
        val dialog = rule.onNodeWithTag("annotation-editor-dialog")
        save("toolbar-idle-$name", dialog)

        rule.onNodeWithContentDescription("Voice input").performMouseInput { click() }
        rule.waitForIdle()
        save("toolbar-recording-$name", dialog)
        rule.onNodeWithContentDescription("Stop voice input").performMouseInput { click() }
        rule.waitUntil(5_000) { coordinator?.snapshot?.value?.state == VoiceInputState.Idle }

        rule.onNodeWithContentDescription("Dictation language", substring = true).performClick()
        rule.waitForIdle()
        save("toolbar-menu-$name", dialog, rule.onNodeWithTag("dictation-menu"))
    }

    private fun inlineField(theme: ThemeColors, name: String) {
        val text = mutableStateOf("")
        val filled = mutableStateOf("Login opens the home screen, then the list flickers on rotate.")
        setContent(theme) {
            Column(Modifier.testTag("inline-scene").width(360.dp).background(tc().p).padding(12.dp)) {
                MarkdownInlineOrTextField(
                    value = text.value,
                    placeholder = "Write your note…",
                    tc = tc(),
                    renderInlineMarkdown = true,
                    fieldFocusRequester = remember { FocusRequester() },
                    onFieldFocusChanged = {},
                    onValueChange = { text.value = it },
                    proseTargetId = "qa:inline-empty",
                    modifier = Modifier.testTag("inline-empty"),
                )
                Box(Modifier.padding(top = 8.dp))
                MarkdownInlineOrTextField(
                    value = filled.value,
                    placeholder = "Write your note…",
                    tc = tc(),
                    renderInlineMarkdown = false,
                    fieldFocusRequester = remember { FocusRequester() },
                    onFieldFocusChanged = {},
                    onValueChange = { filled.value = it },
                    maxHeight = 120.dp,
                    proseTargetId = "qa:inline-filled",
                    modifier = Modifier.testTag("inline-filled"),
                )
            }
        }
        val scene = rule.onNodeWithTag("inline-scene")
        save("inline-idle-$name", scene)
        rule.onNodeWithTag("inline-empty").performClick()
        rule.waitForIdle()
        save("inline-focused-$name", scene)
        rule.onAllNodesWithContentDescription("Voice input")[0].performMouseInput { click() }
        rule.waitForIdle()
        save("inline-recording-$name", scene)
        rule.onNodeWithContentDescription("Stop voice input").performMouseInput { click() }
        rule.waitUntil(5_000) { coordinator?.snapshot?.value?.state == VoiceInputState.Idle }
        rule.onAllNodesWithContentDescription("Voice input")[0].performMouseInput { rightClick() }
        rule.waitForIdle()
        save("inline-menu-$name", scene, rule.onNodeWithTag("dictation-menu"))
    }

    private fun proofreadingPopup(theme: ThemeColors, name: String) {
        val service = EnglishProofreadingService {
            EnglishProofreader { text, _ ->
                listOfNotNull(
                    text.indexOf("speling").takeIf { it >= 0 }?.let {
                        ProofreadingIssue(it, it + 7, "Possible spelling mistake found.", listOf("spelling", "spewing", "spieling"), "qa-spelling")
                    },
                    text.indexOf("alot").takeIf { it >= 0 }?.let {
                        ProofreadingIssue(
                            it,
                            it + 4,
                            "This is a common mistake: write 'a lot' as two words.",
                            listOf("a lot", "allot"),
                            "qa-grammar",
                            ProofreadingCategory.GRAMMAR,
                        )
                    },
                )
            }
        }.also { proofreadingService = it }
        val proofreading = ProofreadingUi(service, ProofreadingSettings(), mutableSetOf())
        setContent(theme, proofreading) {
            AnnotationMarkdownEditorDialog(
                title = "Edit note",
                initialText = "The speling of the title is wrong and the list flickers alot after rotating.",
                confirmLabel = "Save note",
                windowSize = IntSize(1024, 768),
                sessionKey = "qa-proofreading-$name",
                onConfirm = {},
                onDismiss = {},
            )
        }
        val editor = rule.onNodeWithTag("annotation-markdown-editor")
        val dialog = rule.onNodeWithTag("annotation-editor-dialog")
        editor.performMouseInput { click(Offset(500f, 300f)) }
        rule.letProofreadingSettle()
        save("proofreading-marks-$name", dialog)

        rule.hoverUntilSuggestionOpens(editor, y = 13f, xRange = 20..300 step 8)
        save("proofreading-spelling-popup-$name", dialog, rule.onNodeWithTag("proofreading-suggestion-popup"))
        editor.performMouseInput { moveTo(Offset(600f, 300f)) }
        rule.mainClock.advanceTimeBy(700)
        rule.waitForIdle()
        rule.hoverUntilSuggestionOpens(editor, y = 13f, xRange = 400..1000 step 8)
        save("proofreading-grammar-popup-$name", dialog, rule.onNodeWithTag("proofreading-suggestion-popup"))
    }

    @Composable
    private fun dialog(sessionKey: String) {
        AnnotationMarkdownEditorDialog(
            title = "Edit note",
            initialText = "## Investigation\n\nThe app opens the expected screen. Add the next observation here.",
            confirmLabel = "Save note",
            windowSize = IntSize(1024, 768),
            sessionKey = sessionKey,
            onConfirm = {},
            onDismiss = {},
        )
    }

    private fun setContent(
        theme: ThemeColors,
        proofreading: ProofreadingUi? = null,
        content: @Composable () -> Unit,
    ) {
        val controller = VoiceInputController(
            hasInstalledModel = { true },
            capture = object : VoiceCapture {
                override fun start(): VoiceCaptureStartResult = VoiceCaptureStartResult.Started(object : VoiceCaptureSession {
                    override fun stop() = VoiceCaptureResult.Captured(VoiceAudio(byteArrayOf(1, 0)))

                    override fun cancel() = Unit
                })
            },
            transcriber = { _, _ -> VoiceTranscriptionResult.Success(VoiceTranscript("new words")) },
            scope = scope,
        )
        val shared = VoiceInputSessionCoordinator(controller, scope).also { coordinator = it }
        val runtime = VoiceInputRuntime(controller, VoiceModelInstaller(directory), VoiceRecognitionEngine.WHISPER)
        rule.setContent {
            val settings = remember { mutableStateOf(VoiceInputSettings()) }
            CompositionLocalProvider(
                LocalDensity provides Density(1.25f),
                LocalTheme provides theme,
                LocalVoiceInputUi provides VoiceInputUi(runtime, shared, settings.value) { settings.value = it },
                LocalProofreadingUi provides proofreading,
            ) { content() }
        }
    }

    /** Saves [base], with each popup-layer node in [overlays] painted where it sits relative to it. */
    private fun save(fileName: String, base: SemanticsNodeInteraction, vararg overlays: SemanticsNodeInteraction) {
        val baseBounds = base.fetchSemanticsNode().boundsInRoot
        val parts = overlays.map { it.fetchSemanticsNode().boundsInRoot to it.captureToImage() }
        val left = minOf(baseBounds.left, parts.minOfOrNull { it.first.left } ?: baseBounds.left)
        val top = minOf(baseBounds.top, parts.minOfOrNull { it.first.top } ?: baseBounds.top)
        val right = maxOf(baseBounds.right, parts.maxOfOrNull { it.first.right } ?: baseBounds.right)
        val bottom = maxOf(baseBounds.bottom, parts.maxOfOrNull { it.first.bottom } ?: baseBounds.bottom)
        val canvas = BufferedImage((right - left).toInt(), (bottom - top).toInt(), BufferedImage.TYPE_INT_ARGB)
        canvas.createGraphics().apply {
            color = Color(128, 128, 128)
            fillRect(0, 0, canvas.width, canvas.height)
            dispose()
        }

        fun paint(image: androidx.compose.ui.graphics.ImageBitmap, x: Int, y: Int) {
            val pixels = image.toPixelMap()
            for (py in 0 until image.height) for (px in 0 until image.width) {
                val tx = x + px
                val ty = y + py
                if (tx in 0 until canvas.width && ty in 0 until canvas.height) canvas.setRGB(tx, ty, pixels[px, py].toArgb())
            }
        }
        paint(base.captureToImage(), (baseBounds.left - left).toInt(), (baseBounds.top - top).toInt())
        parts.forEach { (bounds, image) -> paint(image, (bounds.left - left).toInt(), (bounds.top - top).toInt()) }
        assertTrue(ImageIO.write(canvas, "png", File(output, "$fileName.png")))
    }
}
