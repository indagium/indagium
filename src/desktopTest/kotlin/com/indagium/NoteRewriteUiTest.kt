@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.indagium

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.IntSize
import com.indagium.ai.LlmRole
import com.indagium.ai.LlmStreamEvent
import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.model.VoiceInputSettings
import com.indagium.model.VoiceRecognitionEngine
import com.indagium.testing.TurnProvider
import com.indagium.testing.run.LaneAgentFactory
import com.indagium.testing.run.ProviderLaneAgent
import com.indagium.ui.AnnotationMarkdownEditorDialog
import com.indagium.ui.AppState
import com.indagium.ui.LocalVoiceInputUi
import com.indagium.ui.NoteRewriteDialog
import com.indagium.ui.NoteRewriteSnapshot
import com.indagium.ui.TestRunOverrides
import com.indagium.ui.VoiceInputUi
import com.indagium.ui.isMacOs
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteRewriteUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val directory = createTempDirectory("note-rewrite-ui").toFile()
    private val state = AppState(
        autosaveFile = File(directory, "state.cache"),
        autoExportNotes = false,
        notesDir = File(directory, "notes"),
        archiveCacheDir = File(directory, "archives"),
        customCommandsDir = File(directory, "commands"),
        controlTokenFile = File(directory, "token"),
        sourceIndexFile = File(directory, "source-index"),
        testingDir = File(directory, "testing"),
    )
    private var voiceCoordinator: VoiceInputSessionCoordinator? = null
    private var voiceScope: CoroutineScope? = null

    @After
    fun tearDown() {
        voiceCoordinator?.close()
        voiceScope?.cancel()
        state.close()
        directory.deleteRecursively()
    }

    private fun setContentWithVoice(
        transcriber: VoiceTranscriber = VoiceTranscriber { _, _ ->
            VoiceTranscriptionResult.Success(VoiceTranscript("new words"))
        },
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        voiceScope = scope
        val controller = VoiceInputController(
            hasInstalledModel = { true },
            capture = object : VoiceCapture {
                override fun start() = VoiceCaptureStartResult.Started(object : VoiceCaptureSession {
                    override fun stop() = VoiceCaptureResult.Captured(VoiceAudio(byteArrayOf(0, 64)))

                    override fun cancel() = Unit
                })
            },
            transcriber = transcriber,
            scope = scope,
        )
        val coordinator = VoiceInputSessionCoordinator(controller, scope)
        voiceCoordinator = coordinator
        val runtime = VoiceInputRuntime(controller, VoiceModelInstaller(File(directory, "voice-model")), VoiceRecognitionEngine.APPLE_SPEECH)
        val settings = mutableStateOf(VoiceInputSettings())
        rule.setContent {
            CompositionLocalProvider(
                LocalVoiceInputUi provides VoiceInputUi(runtime, coordinator, settings.value) { settings.value = it },
            ) { content() }
        }
    }

    private fun configureProvider(provider: TurnProvider) {
        val profile = AiProviderProfile("rewrite-profile", "Rewrite profile", "http://127.0.0.1:1/v1", "rewrite-model", kind = AiProviderKind.OPENAI_COMPATIBLE)
        state.updateSettings { it.copy(aiProviderProfiles = listOf(profile)) }
        state.testRunOverrides = TestRunOverrides(
            agentFactory = LaneAgentFactory { chosen, _ -> ProviderLaneAgent(provider, chosen) },
        )
    }

    @Test
    fun selectedTextIsReviewedAppliedOnceAndUndoRestoresTheCapturedDraft() {
        val provider = TurnProvider(
            listOf(
                {
                    emit(LlmStreamEvent.TextDelta("polished"))
                    emit(LlmStreamEvent.Completed)
                },
            ),
        )
        configureProvider(provider)
        rule.setContent {
            AnnotationMarkdownEditorDialog(
                title = "Edit note",
                initialText = "rough original",
                confirmLabel = "Save note",
                windowSize = IntSize(900, 800),
                sessionKey = "rewrite-session",
                rewriteState = state,
                onConfirm = {},
                onDismiss = {},
            )
        }

        val editor = rule.onNodeWithTag("annotation-markdown-editor")
        editor.performClick()
        editor.performTextInputSelection(TextRange(0, 5))
        rule.onNodeWithText("Rewrite with AI…").assertIsEnabled().performClick()
        rule.onNodeWithTag("note-rewrite-instructions").assertIsDisplayed()
        rule.onNodeWithTag("note-rewrite-cancel").performClick()
        editor.assertTextEquals("rough original")
        editor.performTextInputSelection(TextRange(0, 5))
        rule.onNodeWithText("Rewrite with AI…").performClick()
        rule.onNodeWithTag("note-rewrite-submit").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Review rewrite"), 15_000)

        val sent = provider.requests.single()
        assertTrue(sent.tools.isEmpty())
        assertTrue(sent.messages.any { it.content.orEmpty().contains("rough") })
        assertTrue(sent.messages.filter { it.role == LlmRole.USER }.none { it.content.orEmpty().contains("original") })
        rule.onNodeWithText("polished").assertIsDisplayed()
        rule.onNodeWithTag("note-rewrite-apply").performClick()
        editor.assertTextEquals("polished original")

        editor.performKeyInput {
            withKeyDown(if (isMacOs) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.Z) }
        }
        editor.assertTextEquals("rough original")
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun lockedAndBlankDraftsDoNotOfferAnEnabledRewriteAction() {
        rule.setContent {
            AnnotationMarkdownEditorDialog(
                title = "Edit note",
                initialText = "   ",
                confirmLabel = "Save note",
                windowSize = IntSize(900, 800),
                sessionKey = "locked-rewrite-session",
                locked = true,
                rewriteState = state,
                onConfirm = {},
                onDismiss = {},
            )
        }
        rule.onNodeWithText("Rewrite with AI…").assertIsNotEnabled()
    }

    @Test
    fun providerFailureNeverShowsPartialTextAndLeavesEditorUntouched() {
        val provider = TurnProvider(
            listOf(
                {
                    emit(LlmStreamEvent.TextDelta("partial suggestion"))
                    emit(LlmStreamEvent.Error("provider failed"))
                },
            ),
        )
        configureProvider(provider)
        rule.setContent {
            AnnotationMarkdownEditorDialog(
                title = "Edit note",
                initialText = "original draft",
                confirmLabel = "Save note",
                windowSize = IntSize(900, 800),
                sessionKey = "rewrite-failure-session",
                rewriteState = state,
                onConfirm = {},
                onDismiss = {},
            )
        }
        val editor = rule.onNodeWithTag("annotation-markdown-editor")
        rule.onNodeWithText("Rewrite with AI…").performClick()
        rule.onNodeWithTag("note-rewrite-submit").performClick()
        rule.waitUntilAtLeastOneExists(hasText("provider failed", substring = true), 15_000)
        rule.onNodeWithText("partial suggestion").assertDoesNotExist()
        rule.onNodeWithTag("note-rewrite-cancel").performClick()
        editor.assertTextEquals("original draft")
    }

    @Test
    fun cancellingAnActiveRewriteStopsTheProviderAndPreservesTheDraft() {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val provider = TurnProvider(
            listOf(
                {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                },
            ),
        )
        configureProvider(provider)
        rule.setContent {
            AnnotationMarkdownEditorDialog(
                title = "Edit note",
                initialText = "cancel me safely",
                confirmLabel = "Save note",
                windowSize = IntSize(900, 800),
                sessionKey = "rewrite-cancel-session",
                rewriteState = state,
                onConfirm = {},
                onDismiss = {},
            )
        }
        val editor = rule.onNodeWithTag("annotation-markdown-editor")
        rule.onNodeWithText("Rewrite with AI…").performClick()
        rule.onNodeWithTag("note-rewrite-submit").performClick()
        rule.waitUntil(15_000) { entered.isCompleted }
        rule.onNodeWithTag("note-rewrite-cancel").performClick()
        rule.waitUntil(15_000) { cancelled.isCompleted }
        editor.assertTextEquals("cancel me safely")
        assertTrue(provider.requests.size == 1)
    }

    @Test
    fun conflictDuringGenerationDiscardsTheLateProposal() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val provider = TurnProvider(
            listOf(
                {
                    entered.complete(Unit)
                    release.await()
                    emit(LlmStreamEvent.TextDelta("late proposal"))
                    emit(LlmStreamEvent.Completed)
                },
            ),
        )
        configureProvider(provider)
        var current by androidx.compose.runtime.mutableStateOf(true)
        var applied: String? = null
        val snapshot = NoteRewriteSnapshot("conflict-session", "captured draft", 0, 14, "captured draft")
        rule.setContent {
            NoteRewriteDialog(
                state = state,
                snapshot = snapshot,
                isCurrent = { current },
                onApply = { applied = it },
                onDismiss = {},
            )
        }
        rule.onNodeWithTag("note-rewrite-submit").performClick()
        rule.waitUntil(15_000) { entered.isCompleted }
        rule.runOnIdle { current = false }
        release.complete(Unit)
        rule.waitUntilAtLeastOneExists(hasText("note changed while rewriting", substring = true), 15_000)
        rule.onNodeWithTag("note-rewrite-apply").assertDoesNotExist()
        assertEquals(null, applied)
    }

    @Test
    fun rewriteActionRemainsVisibleAtNarrowEditorWidth() {
        setContentWithVoice {
            AnnotationMarkdownEditorDialog(
                title = "Edit note",
                initialText = "A narrow-window note",
                confirmLabel = "Save note",
                windowSize = IntSize(420, 700),
                sessionKey = "narrow-rewrite-session",
                rewriteState = state,
                onConfirm = {},
                onDismiss = {},
            )
        }
        val action = rule.onNodeWithText("Rewrite with AI…")
        action.assertIsDisplayed().assertIsEnabled()
        val bounds = action.fetchSemanticsNode().boundsInRoot
        val group = rule.onNodeWithTag("annotation-ai-action-group").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val microphone = rule.onNodeWithContentDescription("Voice input").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val dialog = rule.onNodeWithTag("annotation-editor-dialog").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width > 0f && bounds.height > 0f)
        assertTrue(bounds.right < microphone.left, "the rewrite action sits beside the microphone: action=$bounds mic=$microphone")
        assertTrue(group.left <= bounds.left && group.right >= microphone.right, "both actions share the separated group: group=$group")
        assertTrue(dialog.right - group.right < 40f, "the action group stays trailing at narrow widths: group=$group dialog=$dialog")
        rule.onNodeWithText("Heading").assertIsDisplayed()
        rule.onNodeWithText("Link").assertIsDisplayed()
    }

    @Test
    fun localDictationReplacesTheRetainedInstructionSelectionAndBlocksGenerationUntilComplete() {
        val transcriberEntered = CountDownLatch(1)
        val releaseTranscriber = CountDownLatch(1)
        setContentWithVoice(
            transcriber = VoiceTranscriber { _, _ ->
                transcriberEntered.countDown()
                check(releaseTranscriber.await(15, TimeUnit.SECONDS))
                VoiceTranscriptionResult.Success(VoiceTranscript("new words"))
            },
        ) {
            NoteRewriteDialog(
                state = state,
                snapshot = NoteRewriteSnapshot("voice-rewrite-session", "draft", 0, 5, "draft"),
                isCurrent = { true },
                onApply = {},
                onDismiss = {},
            )
        }

        val instructions = rule.onNodeWithTag("note-rewrite-instructions")
        instructions.performTextInput("First sentence. End")
        instructions.performTextInputSelection(TextRange(16, 19))
        rule.onNodeWithContentDescription("Voice input").performMouseInput { click() }
        rule.onNodeWithTag("note-rewrite-submit").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Stop voice input").performMouseInput { click() }
        rule.onNodeWithTag("note-rewrite-submit").assertIsNotEnabled()
        rule.waitUntil(15_000) { transcriberEntered.count == 0L }

        releaseTranscriber.countDown()
        rule.waitUntilAtLeastOneExists(hasText("First sentence. New words"), 15_000)
        instructions.performTextInput(" more")
        instructions.assertTextEquals("First sentence. New words more")
        rule.onNodeWithTag("note-rewrite-submit").assertIsEnabled()
        assertEquals(VoiceInputState.Idle, voiceCoordinator?.snapshot?.value?.state)
    }

    @Test
    fun escapeCancelsLocalDictationBeforeDialogDismissalAndClosingCleansUpCoordinator() {
        var dialogVisible by mutableStateOf(true)
        setContentWithVoice {
            if (dialogVisible) {
                NoteRewriteDialog(
                    state = state,
                    snapshot = NoteRewriteSnapshot("voice-escape-session", "draft", 0, 5, "draft"),
                    isCurrent = { true },
                    onApply = {},
                    onDismiss = { dialogVisible = false },
                )
            }
        }

        val instructions = rule.onNodeWithTag("note-rewrite-instructions")
        rule.onNodeWithContentDescription("Voice input").performMouseInput { click() }
        assertTrue(voiceCoordinator?.snapshot?.value?.state is VoiceInputState.Recording)
        instructions.performKeyInput { pressKey(Key.Escape) }
        assertEquals(VoiceInputState.Idle, voiceCoordinator?.snapshot?.value?.state)
        instructions.assertIsDisplayed()

        rule.onNodeWithContentDescription("Voice input").performMouseInput { click() }
        rule.onNodeWithTag("note-rewrite-cancel").performClick()
        rule.waitUntil(5_000) { !dialogVisible }
        assertEquals(VoiceInputState.Idle, voiceCoordinator?.snapshot?.value?.state)
    }
}
