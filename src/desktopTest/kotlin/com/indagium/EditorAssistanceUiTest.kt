package com.indagium

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.indagium.model.VoiceInputSettings
import com.indagium.model.VoiceRecognitionEngine
import com.indagium.model.ProofreadingSettings
import com.indagium.proofread.EnglishProofreader
import com.indagium.proofread.EnglishProofreadingService
import com.indagium.proofread.ProofreadingIssue
import com.indagium.ui.AnnotationMarkdownEditorDialog
import com.indagium.ui.DARK_GITHUB
import com.indagium.ui.LIGHT_THEME
import com.indagium.ui.LocalTheme
import com.indagium.ui.LocalVoiceInputUi
import com.indagium.ui.LocalProofreadingUi
import com.indagium.ui.ProofreadingUi
import com.indagium.ui.ThemeColors
import com.indagium.ui.VoiceInputUi
import com.indagium.ui.isMacOs
import com.indagium.ui.MarkdownInlineOrTextField
import com.indagium.ui.CommitTextField
import com.indagium.ui.tc
import com.indagium.testing.store.StoreResult
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
import com.indagium.voice.VoiceTranscriber
import com.indagium.voice.VoiceTranscript
import com.indagium.voice.VoiceTranscriptionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real note-dialog integration with a fake recognizer: no audio devices, model downloads, or provider calls. */
class EditorAssistanceUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val directory = createTempDirectory("editor-assistance-ui").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var coordinator: VoiceInputSessionCoordinator? = null
    private var releaseTranscriber: CountDownLatch? = null
    private var proofreadingService: EnglishProofreadingService? = null

    @After
    fun tearDown() {
        releaseTranscriber?.countDown()
        coordinator?.close()
        proofreadingService?.close()
        scope.cancel()
        directory.deleteRecursively()
    }

    @Test
    fun selectedTextIsReplacedOnceAndUndoAndCancelKeepTheirNormalMeaning() {
        var confirms = 0
        var dismissals = 0
        setContent {
            AnnotationMarkdownEditorDialog(
                title = "Edit note",
                initialText = "before old after",
                confirmLabel = "Save note",
                windowSize = IntSize(900, 850),
                sessionKey = "selection-undo",
                onConfirm = { confirms++ },
                onDismiss = { dismissals++ },
            )
        }
        val editor = rule.onNodeWithTag("annotation-markdown-editor")
        editor.performClick()
        editor.performTextInputSelection(TextRange(7, 10))
        rule.onNodeWithContentDescription("Voice input").performMouseInput { click() }
        rule.onNodeWithContentDescription("Stop voice input").performMouseInput { click() }
        rule.waitUntil(5_000) {
            coordinator?.snapshot?.value?.state == VoiceInputState.Idle
        }
        editor.assertTextEquals("before new words after")
        assertEquals(0, confirms, "dictation must not save the dialog")

        editor.performKeyInput {
            withKeyDown(if (isMacOs) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.Z) }
        }
        editor.assertTextEquals("before old after")
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, dismissals)
        assertEquals(0, confirms)
    }

    @Test
    fun lockingTheNoteDuringTranscriptionDiscardsThePendingResult() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1).also { releaseTranscriber = it }
        val locked = mutableStateOf(false)
        setContent(
            transcriber = VoiceTranscriber { _, _ ->
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                VoiceTranscriptionResult.Success(VoiceTranscript("late words"))
            },
        ) {
            AnnotationMarkdownEditorDialog(
                title = "Edit note",
                initialText = "original text",
                confirmLabel = "Save note",
                windowSize = IntSize(900, 850),
                sessionKey = "lock-pending",
                locked = locked.value,
                onConfirm = { error("must not save") },
                onDismiss = {},
            )
        }
        rule.onNodeWithContentDescription("Voice input").performClick()
        rule.onNodeWithContentDescription("Stop voice input").performClick()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        rule.runOnIdle { locked.value = true }
        rule.waitForIdle()
        release.countDown()
        rule.waitUntil(5_000) { coordinator?.snapshot?.value?.state !is VoiceInputState.Transcribing }
        rule.onNodeWithTag("annotation-markdown-editor").assertTextEquals("original text")
    }

    @Test
    fun escapeCancelsRecordingWithoutDismissingTheNote() {
        var dismissals = 0
        setContent {
            AnnotationMarkdownEditorDialog(
                title = "Edit note",
                initialText = "keep this draft",
                confirmLabel = "Save note",
                windowSize = IntSize(900, 850),
                sessionKey = "escape-recording",
                onConfirm = {},
                onDismiss = { dismissals++ },
            )
        }
        rule.onNodeWithContentDescription("Voice input").performClick()
        rule.onNodeWithTag("annotation-markdown-editor").performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        rule.onNodeWithTag("annotation-markdown-editor").assertTextEquals("keep this draft")
        assertEquals(0, dismissals)
        assertEquals(VoiceInputState.Idle, coordinator?.snapshot?.value?.state)
    }

    @Test
    fun lightNarrowDialogKeepsVoiceControlsWithinItsBounds() {
        captureDialog(LIGHT_THEME, "light-narrow", IntSize(620, 760))
    }

    @Test
    fun darkDialogKeepsVoiceControlsWithinItsBounds() {
        captureDialog(DARK_GITHUB, "dark", IntSize(900, 850))
    }

    @Test
    fun englishSuggestionAppliesAsOneUndoableEditAndDoesNotSaveTheNote() {
        var confirms = 0
        val proofreading = proofreadingUi()
        setContent(proofreading = proofreading) {
            AnnotationMarkdownEditorDialog(
                title = "Edit note",
                initialText = "A speling mistake.",
                confirmLabel = "Save note",
                windowSize = IntSize(900, 850),
                sessionKey = "proofreading-undo",
                onConfirm = { confirms++ },
                onDismiss = {},
            )
        }
        val editor = rule.onNodeWithTag("annotation-markdown-editor")
        editor.performClick()
        editor.performTextInputSelection(TextRange(4))
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("spelling").fetchSemanticsNode() }.isSuccess }
        rule.onNodeWithText("spelling").performClick()
        editor.assertTextEquals("A spelling mistake.")
        assertEquals(0, confirms)
        editor.performKeyInput {
            withKeyDown(if (isMacOs) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.Z) }
        }
        editor.assertTextEquals("A speling mistake.")
        assertEquals(0, confirms)
    }

    @Test
    fun proofreadingResultFromAnOlderDraftIsDiscarded() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1).also { releaseTranscriber = it }
        val proofreading = proofreadingUi { text, _ ->
            if (text.contains("speling")) {
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                listOf(ProofreadingIssue(2, 9, "Spelling", listOf("spelling"), "test"))
            } else emptyList()
        }
        setContent(proofreading = proofreading) {
            AnnotationMarkdownEditorDialog(
                title = "Edit note",
                initialText = "A speling mistake.",
                confirmLabel = "Save note",
                windowSize = IntSize(900, 850),
                sessionKey = "proofreading-stale",
                onConfirm = {},
                onDismiss = {},
            )
        }
        val editor = rule.onNodeWithTag("annotation-markdown-editor")
        editor.performClick()
        editor.performTextInputSelection(TextRange(4))
        rule.waitUntil(5_000) { started.count == 0L }
        editor.performTextReplacement("The revised sentence.")
        release.countDown()
        rule.waitForIdle()
        editor.assertTextEquals("The revised sentence.")
        rule.onNodeWithText("spelling").assertDoesNotExist()
    }

    @Test
    fun inlineDictationPreservesSelectionAndSupportsUndo() {
        val text = mutableStateOf("before old after")
        setContent {
            MarkdownInlineOrTextField(
                value = text.value,
                placeholder = "Note",
                tc = tc(),
                renderInlineMarkdown = true,
                fieldFocusRequester = remember { FocusRequester() },
                onFieldFocusChanged = {},
                onValueChange = { text.value = it },
                proseTargetId = "note:inline-selection",
                modifier = Modifier.testTag("inline-note"),
            )
        }
        val editor = rule.onNodeWithTag("inline-note")
        editor.performClick()
        editor.performTextInputSelection(TextRange(7, 10))
        rule.onNodeWithContentDescription("Voice input").performMouseInput { click() }
        rule.onNodeWithContentDescription("Stop voice input").performMouseInput { click() }
        rule.waitUntil(5_000) { coordinator?.snapshot?.value?.state == VoiceInputState.Idle }
        assertEquals("before new words after", text.value)
        editor.performKeyInput {
            withKeyDown(if (isMacOs) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.Z) }
        }
        rule.waitForIdle()
        assertEquals("before old after", text.value)
    }

    @Test
    fun inlineEditorSurvivesTheMissingModelDialog() {
        val text = mutableStateOf("Existing note")
        setContent(hasModel = false) {
            MarkdownInlineOrTextField(
                value = text.value,
                placeholder = "Note",
                tc = tc(),
                renderInlineMarkdown = true,
                fieldFocusRequester = remember { FocusRequester() },
                onFieldFocusChanged = {},
                onValueChange = { text.value = it },
                proseTargetId = "note:model-consent",
                modifier = Modifier.testTag("inline-note"),
            )
        }
        rule.onNodeWithTag("inline-note").performClick()
        rule.onNodeWithContentDescription("Voice input").performMouseInput { click() }
        rule.waitForIdle()
        rule.onNodeWithText("Download local voice model").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithTag("inline-note").assert(hasSetTextAction())
        assertEquals("Existing note", text.value)
    }

    @Test
    fun testProseDictationUsesNormalCommitAndTechnicalFieldsHaveNoMic() {
        val stored = mutableStateOf("before old after")
        setContent {
            Column {
                CommitTextField(
                    value = stored.value,
                    onCommit = { stored.value = it; StoreResult.Ok(Unit) },
                    proseTargetId = "test:case:step:action",
                )
                CommitTextField(value = "com.example.app", onCommit = { StoreResult.Ok(Unit) }, mono = true)
            }
        }
        val editor = rule.onNode(hasSetTextAction().and(hasText("before old after")))
        editor.performClick()
        editor.performTextInputSelection(TextRange(7, 10))
        rule.onNodeWithContentDescription("Voice input").performMouseInput { click() }
        rule.onNodeWithContentDescription("Stop voice input").performMouseInput { click() }
        rule.waitUntil(5_000) { stored.value == "before new words after" }
        rule.onNode(hasSetTextAction().and(hasText("com.example.app"))).performClick()
        rule.onNodeWithContentDescription("Voice input").assertDoesNotExist()
    }

    @Test
    fun multilineTestDictationUsesTheLatestTextAndCaretAfterTyping() {
        val stored = mutableStateOf("before old after")
        setContent {
            CommitTextField(
                value = stored.value,
                onCommit = { stored.value = it; StoreResult.Ok(Unit) },
                multiline = true,
                proseTargetId = "test:case:step:multiline-action",
            )
        }
        val editor = rule.onNode(hasSetTextAction())
        editor.performClick()
        editor.performTextInputSelection(TextRange(7, 10))
        rule.onNodeWithContentDescription("Voice input").performMouseInput { click() }
        editor.performTextInputSelection(TextRange(0))
        editor.performTextInput("Start ")
        rule.onNodeWithContentDescription("Stop voice input").performMouseInput { click() }
        rule.waitUntil(5_000) { coordinator?.snapshot?.value?.state == VoiceInputState.Idle }
        editor.assertTextEquals("Start new words before old after")
        rule.waitUntil(5_000) { stored.value == "Start new words before old after" }
    }

    @Test
    fun microphoneFailureKeepsNarrowEditorUsableAndExposesTheReason() {
        val failure = "Microphone permission was denied. Allow access in system settings before recording again."
        setContent(captureFailure = failure) {
            Column(Modifier.width(320.dp)) {
                CommitTextField("Observation", { StoreResult.Ok(Unit) }, proseTargetId = "test:failure")
            }
        }
        val editor = rule.onNode(hasSetTextAction())
        editor.performClick()
        editor.performTextInputSelection(TextRange("Observation".length))
        rule.onNodeWithContentDescription("Voice input").performMouseInput { click() }
        rule.onNodeWithText("Retry").assertIsDisplayed()
        val retry = rule.onNodeWithContentDescription("Voice input").fetchSemanticsNode()
        assertEquals(failure, retry.config[SemanticsProperties.StateDescription])
        assertTrue(editor.fetchSemanticsNode().boundsInRoot.width > 180f, "failure text must not squeeze the editor out of a narrow row")
        editor.performTextInput(" after")
        editor.assertTextEquals("Observation after")
    }

    private fun captureDialog(theme: ThemeColors, name: String, windowSize: IntSize) {
        setContent(theme = theme) {
            AnnotationMarkdownEditorDialog(
                title = "Edit note",
                initialText = "## Investigation\n\nThe app opens the expected screen. Add the next observation here.",
                confirmLabel = "Save note",
                windowSize = windowSize,
                sessionKey = name,
                onConfirm = {},
                onDismiss = {},
            )
        }
        val microphone = rule.onNodeWithContentDescription("Voice input").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val dialog = rule.onNodeWithTag("annotation-editor-dialog")
        val bounds = dialog.fetchSemanticsNode().boundsInRoot
        assertTrue(microphone.left >= bounds.left && microphone.right <= bounds.right)
        assertTrue(microphone.top >= bounds.top && microphone.bottom <= bounds.bottom)
        listOf("EN", "Auto").forEach { label ->
            val control = rule.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(control.left >= bounds.left && control.right <= bounds.right, "$label must fit inside a narrow dialog")
        }
        val image = dialog.captureToImage()
        val pixels = image.toPixelMap()
        val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) bitmap.setRGB(x, y, pixels[x, y].toArgb())
        val output = File("build/qa/editor-assistance").apply { mkdirs() }
        assertTrue(ImageIO.write(bitmap, "png", File(output, "$name.png")))
    }

    private fun setContent(
        theme: ThemeColors = LIGHT_THEME,
        proofreading: ProofreadingUi? = null,
        hasModel: Boolean = true,
        captureFailure: String? = null,
        transcriber: VoiceTranscriber = VoiceTranscriber { _, _ ->
            VoiceTranscriptionResult.Success(VoiceTranscript("new words"))
        },
        content: @Composable () -> Unit,
    ) {
        val controller = VoiceInputController(
            hasInstalledModel = { hasModel },
            capture = object : VoiceCapture {
                override fun start(): VoiceCaptureStartResult {
                    if (captureFailure != null) return VoiceCaptureStartResult.Failure(captureFailure)
                    return VoiceCaptureStartResult.Started(object : VoiceCaptureSession {
                        override fun stop() = VoiceCaptureResult.Captured(VoiceAudio(byteArrayOf(1, 0)))

                        override fun cancel() = Unit
                    })
                }
            },
            transcriber = transcriber,
            scope = scope,
        )
        val shared = VoiceInputSessionCoordinator(controller, scope).also { coordinator = it }
        val runtime = VoiceInputRuntime(controller, VoiceModelInstaller(directory), VoiceRecognitionEngine.WHISPER)
        val voice = VoiceInputUi(runtime, shared, VoiceInputSettings(), {})
        rule.setContent {
            CompositionLocalProvider(
                LocalTheme provides theme,
                LocalVoiceInputUi provides voice,
                LocalProofreadingUi provides proofreading,
            ) { content() }
        }
    }

    private fun proofreadingUi(
        checker: EnglishProofreader = EnglishProofreader { text, _ ->
            val offset = text.indexOf("speling")
            if (offset < 0) emptyList()
            else listOf(ProofreadingIssue(offset, offset + "speling".length, "Spelling", listOf("spelling"), "test"))
        },
    ): ProofreadingUi {
        val service = EnglishProofreadingService { checker }.also { proofreadingService = it }
        return ProofreadingUi(service, ProofreadingSettings(), mutableSetOf())
    }
}
