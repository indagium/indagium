package com.indagium

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.IntSize
import com.indagium.model.ProofreadingSettings
import com.indagium.model.VoiceInputSettings
import com.indagium.model.VoiceRecognitionEngine
import com.indagium.proofread.EnglishProofreader
import com.indagium.proofread.EnglishProofreadingService
import com.indagium.proofread.ProofreadingIssue
import com.indagium.testing.store.StoreResult
import com.indagium.ui.AnnotationMarkdownEditorDialog
import com.indagium.ui.CommitTextField
import com.indagium.ui.LIGHT_THEME
import com.indagium.ui.LocalProofreadingUi
import com.indagium.ui.LocalTheme
import com.indagium.ui.LocalVoiceInputUi
import com.indagium.ui.MarkdownInlineOrTextField
import com.indagium.ui.ProofreadingUi
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
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals

private const val DICTATION_TIMEOUT_MS = 5_000L

/**
 * A reference to a local function (`::insertVoice`) is a `FunctionReferenceImpl` whose `equals` ignores what it captured. Handed to
 * `rememberUpdatedState`, as the dictation and proofreading callbacks are, a newer reference is "equal" to the first one and the write is
 * dropped, so the callback keeps the values of the first composition. These pin that each editor acts on the values of the latest one.
 */
class DictationLatestCallbackUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val directory = createTempDirectory("dictation-latest-callback-ui").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var coordinator: VoiceInputSessionCoordinator? = null
    private var proofreadingService: EnglishProofreadingService? = null

    @After
    fun tearDown() {
        coordinator?.close()
        proofreadingService?.close()
        scope.cancel()
        directory.deleteRecursively()
    }

    /** A diagram caption's `onValueChange` rebuilds the note from the note text it closes over: an old closure would undo the newer diagram. */
    @Test
    fun dictatedCaptionIsWrittenOverTheLatestDiagramText() {
        val noteText = mutableStateOf("diagram v1")
        val written = mutableListOf<String>()
        setContent {
            val current = noteText.value
            MarkdownInlineOrTextField(
                value = "caption",
                placeholder = "Add a caption…",
                tc = tc(),
                renderInlineMarkdown = false,
                fieldFocusRequester = remember { FocusRequester() },
                onFieldFocusChanged = {},
                onValueChange = { caption -> written += "$current|$caption" },
                proseTargetId = "note:diagram-caption",
                modifier = Modifier.testTag("caption"),
            )
        }
        rule.runOnIdle { noteText.value = "diagram v2" }
        rule.waitForIdle()

        rule.onNodeWithTag("caption").performClick()
        dictate()

        assertEquals(listOf("diagram v2"), written.map { it.substringBefore('|') })
    }

    @Test
    fun inlineFieldThatStartedLockedAcceptsDictationOnceUnlocked() {
        val enabled = mutableStateOf(false)
        val text = mutableStateOf("note")
        setContent {
            MarkdownInlineOrTextField(
                value = text.value,
                placeholder = "Note",
                tc = tc(),
                renderInlineMarkdown = false,
                fieldFocusRequester = remember { FocusRequester() },
                onFieldFocusChanged = {},
                onValueChange = { text.value = it },
                enabled = enabled.value,
                proseTargetId = "note:unlocked-inline",
                modifier = Modifier.testTag("inline-note"),
            )
        }
        rule.runOnIdle { enabled.value = true }
        rule.waitForIdle()

        rule.onNodeWithTag("inline-note").performClick()
        dictate()

        assertEquals("note new words", text.value)
    }

    @Test
    fun noteEditorThatOpenedLockedAcceptsDictationOnceUnlocked() {
        val locked = mutableStateOf(true)
        setContent {
            AnnotationMarkdownEditorDialog(
                title = "Edit note",
                initialText = "note",
                confirmLabel = "Save note",
                windowSize = IntSize(900, 850),
                sessionKey = "unlocked-dictation",
                locked = locked.value,
                onConfirm = {},
                onDismiss = {},
            )
        }
        rule.runOnIdle { locked.value = false }
        rule.waitForIdle()

        rule.onNodeWithTag("annotation-markdown-editor").performClick()
        dictate()

        rule.onNodeWithTag("annotation-markdown-editor").assertTextEquals("note new words")
    }

    @Test
    fun noteEditorThatOpenedLockedAppliesAProofreadingFixOnceUnlocked() {
        val locked = mutableStateOf(true)
        val service = EnglishProofreadingService {
            EnglishProofreader { text, _ ->
                val offset = text.indexOf("speling")
                if (offset < 0) emptyList()
                else listOf(ProofreadingIssue(offset, offset + "speling".length, "Spelling", listOf("spelling"), "test"))
            }
        }.also { proofreadingService = it }
        setContent(proofreading = ProofreadingUi(service, ProofreadingSettings(), mutableSetOf())) {
            AnnotationMarkdownEditorDialog(
                title = "Edit note",
                initialText = "A speling mistake.",
                confirmLabel = "Save note",
                windowSize = IntSize(900, 850),
                sessionKey = "unlocked-proofreading",
                locked = locked.value,
                onConfirm = {},
                onDismiss = {},
            )
        }
        rule.runOnIdle { locked.value = false }
        rule.waitForIdle()

        val editor = rule.onNodeWithTag("annotation-markdown-editor")
        editor.performClick()
        rule.letProofreadingSettle()
        rule.hoverUntilSuggestionOpens(editor)
        rule.onNodeWithTag("proofreading-suggestion-option-0").performClick()

        editor.assertTextEquals("A spelling mistake.")
    }

    /**
     * The recording review's Details tile is not keyed by row, so selecting another row gives the same action field a new dictation target
     * and a new draft. The transcript has to land in that draft, not the one left behind by the old target.
     */
    @Test
    fun commitFieldDictatesIntoTheDraftOfItsCurrentTarget() {
        val targetId = mutableStateOf("recorded-step:first:action")
        val stored = mutableStateOf("")
        setContent {
            CommitTextField(
                value = stored.value,
                onCommit = { stored.value = it; StoreResult.Ok(Unit) },
                proseTargetId = targetId.value,
            )
        }
        rule.runOnIdle { targetId.value = "recorded-step:second:action" }
        rule.waitForIdle()

        rule.onNode(hasSetTextAction()).performClick()
        dictate()

        rule.onNode(hasSetTextAction()).assertTextEquals("New words")
    }

    private fun dictate() {
        rule.onNodeWithContentDescription("Voice input").performMouseInput { click() }
        rule.onNodeWithContentDescription("Stop voice input").performMouseInput { click() }
        rule.waitUntil(DICTATION_TIMEOUT_MS) { coordinator?.snapshot?.value?.state == VoiceInputState.Idle }
        rule.waitForIdle()
    }

    private fun setContent(proofreading: ProofreadingUi? = null, content: @Composable () -> Unit) {
        val controller = VoiceInputController(
            hasInstalledModel = { true },
            capture = object : VoiceCapture {
                override fun start(): VoiceCaptureStartResult = VoiceCaptureStartResult.Started(
                    object : VoiceCaptureSession {
                        override fun stop() = VoiceCaptureResult.Captured(VoiceAudio(byteArrayOf(0, 64)))

                        override fun cancel() = Unit
                    },
                )
            },
            transcriber = VoiceTranscriber { _, _ -> VoiceTranscriptionResult.Success(VoiceTranscript("new words")) },
            scope = scope,
        )
        val shared = VoiceInputSessionCoordinator(controller, scope).also { coordinator = it }
        val runtime = VoiceInputRuntime(controller, VoiceModelInstaller(directory), VoiceRecognitionEngine.WHISPER)
        val settings = mutableStateOf(VoiceInputSettings())
        rule.setContent {
            val voice = VoiceInputUi(runtime, shared, settings.value) { settings.value = it }
            CompositionLocalProvider(
                LocalTheme provides LIGHT_THEME,
                LocalVoiceInputUi provides voice,
                LocalProofreadingUi provides proofreading,
            ) { content() }
        }
    }
}
