package com.indagium

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.indagium.model.VoiceInputSettings
import com.indagium.model.VoiceRecognitionEngine
import com.indagium.ui.DictationMic
import com.indagium.ui.LIGHT_THEME
import com.indagium.ui.LocalTheme
import com.indagium.ui.LocalVoiceInputUi
import com.indagium.ui.VoiceInputUi
import com.indagium.ui.rememberDictationControl
import com.indagium.voice.VoiceCapture
import com.indagium.voice.VoiceCaptureResult
import com.indagium.voice.VoiceCaptureSession
import com.indagium.voice.VoiceCaptureStartResult
import com.indagium.voice.VoiceInputController
import com.indagium.voice.VoiceInputRuntime
import com.indagium.voice.VoiceInputSessionCoordinator
import com.indagium.voice.VoiceInputState
import com.indagium.voice.VoiceModelAvailability
import com.indagium.voice.VoiceModelCheckState
import com.indagium.voice.VoiceModelDescriptor
import com.indagium.voice.VoiceModelInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DictationModelVerificationUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val directory = createTempDirectory("dictation-model-verification").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var coordinator: VoiceInputSessionCoordinator? = null

    @After
    fun tearDown() {
        coordinator?.close()
        scope.cancel()
        directory.deleteRecursively()
    }

    @Test
    fun successfulVerificationReenablesTheMicrophoneAfterAClickEncounteredChecking() = verifyAfterClick(valid = true)

    @Test
    fun failedVerificationLetsTheSameMicrophoneOfferModelInstallation() = verifyAfterClick(valid = false)

    private fun verifyAfterClick(valid: Boolean) {
        val expected = "test voice model".encodeToByteArray()
        val descriptor = VoiceModelDescriptor(
            id = "test", version = "1", fileName = "test-model.bin",
            downloadUrl = "https://example.com/model", licenseUrl = "https://example.com/license",
            sha256 = MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") { "%02x".format(it) },
            sizeBytes = expected.size.toLong(),
        )
        File(directory, descriptor.fileName).writeBytes(expected)
        val installer = VoiceModelInstaller(directory, descriptor)
        assertTrue(installer.isInstalled())
        var changeOnStart = true
        var captureStarts = 0
        val controller = VoiceInputController(
            hasInstalledModel = { installer.cachedCheckState() == VoiceModelCheckState.INSTALLED },
            modelAvailability = {
                // Change the file after the UI rendered an enabled mic, before start checks it.
                if (changeOnStart) {
                    changeOnStart = false
                    val modified = installer.modelFile.lastModified()
                    if (!valid) installer.modelFile.writeText("corrupt")
                    Files.setLastModifiedTime(installer.modelFile.toPath(), FileTime.fromMillis(modified + TimeUnit.SECONDS.toMillis(1)))
                }
                when (installer.cachedCheckState()) {
                    VoiceModelCheckState.CHECKING -> VoiceModelAvailability.CHECKING
                    VoiceModelCheckState.INSTALLED -> VoiceModelAvailability.AVAILABLE
                    VoiceModelCheckState.MISSING_OR_INVALID -> VoiceModelAvailability.MISSING
                }
            },
            capture = object : VoiceCapture {
                override fun start(): VoiceCaptureStartResult {
                    captureStarts++
                    return VoiceCaptureStartResult.Started(object : VoiceCaptureSession {
                        override fun stop() = VoiceCaptureResult.Cancelled

                        override fun cancel() = Unit
                    })
                }
            },
            transcriber = { _, _ -> error("not transcribing") },
            scope = scope,
        )
        val shared = VoiceInputSessionCoordinator(controller, scope).also { coordinator = it }
        val runtime = VoiceInputRuntime(controller, installer, VoiceRecognitionEngine.WHISPER)
        rule.setContent {
            CompositionLocalProvider(
                LocalTheme provides LIGHT_THEME,
                LocalVoiceInputUi provides VoiceInputUi(runtime, shared, VoiceInputSettings()) {},
            ) {
                DictationMic(assertNotNull(rememberDictationControl("test:note", enabled = true, onTranscript = {})))
            }
        }

        rule.onNodeWithContentDescription("Voice input").assertIsEnabled().performClick()
        rule.onNodeWithContentDescription("Checking local voice model").assertIsDisplayed()
        assertEquals(VoiceInputState.CheckingModel, shared.snapshot.value.state)
        assertEquals(0, captureStarts)

        runBlocking { assertNotNull(installer.requestVerification(scope)).join() }

        rule.onNodeWithContentDescription("Voice input").assertIsEnabled().performClick()
        if (valid) {
            rule.onNodeWithContentDescription("Stop voice input").assertIsDisplayed()
            assertEquals(1, captureStarts)
        } else {
            rule.onNodeWithText("Download local voice model").assertIsDisplayed()
            assertEquals(0, captureStarts)
        }
    }
}
