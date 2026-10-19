package com.indagium

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import com.indagium.model.AiProviderKind
import com.indagium.model.AiProviderProfile
import com.indagium.model.VoiceInputSettings
import com.indagium.model.VoiceRecognitionEngine
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestStep
import com.indagium.testing.store.StoreResult
import com.indagium.ui.AppState
import com.indagium.ui.LIGHT_THEME
import com.indagium.ui.LocalTestsUi
import com.indagium.ui.LocalTheme
import com.indagium.ui.LocalVoiceInputUi
import com.indagium.ui.TestsUi
import com.indagium.ui.ThemeColors
import com.indagium.ui.VoiceInputUi
import com.indagium.voice.VoiceAudio
import com.indagium.voice.VoiceCapture
import com.indagium.voice.VoiceCaptureResult
import com.indagium.voice.VoiceCaptureSession
import com.indagium.voice.VoiceCaptureStartResult
import com.indagium.voice.VoiceInputController
import com.indagium.voice.VoiceInputRuntime
import com.indagium.voice.VoiceInputSessionCoordinator
import com.indagium.voice.VoiceModelInstaller
import com.indagium.voice.VoiceTranscript
import com.indagium.voice.VoiceTranscriptionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * A real [AppState] (one suite, one case, one AI profile) plus a fake voice recognizer, for rendering the Tests workspace's
 * AI prompt dialogs on their own: no audio device, no model download, no provider call.
 */
internal class AiPromptDialogFixture : AutoCloseable {
    private val dir: File = createTempDirectory("ai-prompt-dialogs").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val state = AppState(
        autosaveFile = File(dir, "state.cache"),
        autoExportNotes = false,
        notesDir = File(dir, "notes"),
        archiveCacheDir = File(dir, "archive"),
        customCommandsDir = File(dir, "commands"),
        controlTokenFile = File(dir, "token"),
        sourceIndexFile = File(dir, "source-index"),
        testingDir = File(dir, "testing"),
    )
    val coordinator: VoiceInputSessionCoordinator
    private val runtime: VoiceInputRuntime
    val suiteId: String
    val case: TestCase

    init {
        val controller = VoiceInputController(
            hasInstalledModel = { true },
            capture = object : VoiceCapture {
                override fun start(): VoiceCaptureStartResult = VoiceCaptureStartResult.Started(object : VoiceCaptureSession {
                    override fun stop() = VoiceCaptureResult.Captured(VoiceAudio(byteArrayOf(0, 64)))

                    override fun cancel() = Unit
                })
            },
            transcriber = { _, _ -> VoiceTranscriptionResult.Success(VoiceTranscript("new words")) },
            scope = scope,
        )
        coordinator = VoiceInputSessionCoordinator(controller, scope)
        runtime = VoiceInputRuntime(controller, VoiceModelInstaller(File(dir, "voice-model")), VoiceRecognitionEngine.WHISPER)
        val profile = AiProviderProfile("fixture-profile", "Fixture model", "http://127.0.0.1:1/v1", "base-model", kind = AiProviderKind.OPENAI_COMPATIBLE)
        state.updateSettings { it.copy(aiProviderProfiles = listOf(profile)) }
        val suite = (state.createTestSuite("Shop suite") as StoreResult.Ok).value
        suiteId = suite.id
        val created = (state.createTestCase(suite.id, TestCase("", "Login")) as StoreResult.Ok).value
        state.createTestStep(created.id, TestStep("", "Open the app", "The home screen shows"))
        case = created
    }

    /** Provides the theme, voice input and the Tests workspace's shared state around [content]. */
    @Composable
    fun Provide(theme: ThemeColors = LIGHT_THEME, content: @Composable () -> Unit) {
        val settings = remember { mutableStateOf(VoiceInputSettings()) }
        val composeScope = rememberCoroutineScope()
        val ui = remember { TestsUi(state, state.testsView, FocusRequester(), composeScope) }
        CompositionLocalProvider(
            LocalTheme provides theme,
            LocalVoiceInputUi provides VoiceInputUi(runtime, coordinator, settings.value) { settings.value = it },
            LocalTestsUi provides ui,
        ) { content() }
    }

    override fun close() {
        coordinator.close()
        state.close()
        scope.cancel()
        dir.deleteRecursively()
    }
}
