package com.openlog.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceInputControllerTest {
    @Test
    fun missingModelRequestsInstallationWithoutOpeningMicrophone() {
        val capture = FakeCapture()
        val controller = controller(hasModel = false, capture = capture) { _, _ -> error("not called") }

        assertEquals(VoiceInputState.ModelRequired, controller.startRecording())
        assertEquals(0, capture.starts)
    }

    @Test
    fun transcriptUsesConfiguredTranslationAndIsConsumedOnlyOnce() = runBlocking {
        val capture = FakeCapture(audio = audio())
        var options: VoiceTranscriptionOptions? = null
        val controller = controller(capture = capture, optionsProvider = { VoiceTranscriptionOptions(translateToEnglish = false, initialPrompt = "terms") }) { _, transcriptionOptions ->
            options = transcriptionOptions
            VoiceTranscriptionResult.Success(VoiceTranscript("  Explain the Gradle error  ", "uk", translatedToEnglish = false))
        }

        assertIs<VoiceInputState.Recording>(controller.startRecording())
        assertNotNull(controller.stopAndTranscribe()?.join())
        assertEquals(false, options?.translateToEnglish)
        assertEquals("terms", options?.initialPrompt)
        assertIs<VoiceInputState.TranscriptReady>(controller.state.value)
        assertEquals("Existing prompt Explain the Gradle error", appendVoiceTranscript("Existing prompt", controller.consumeTranscript()!!))
        assertEquals(VoiceInputState.Idle, controller.state.value)
        assertNull(controller.consumeTranscript())
    }

    @Test
    fun explicitTranslationOverrideWinsOverSavedSetting() = runBlocking {
        var translate: Boolean? = null
        val controller = controller(optionsProvider = { VoiceTranscriptionOptions(translateToEnglish = false) }) { _, transcriptionOptions ->
            translate = transcriptionOptions.translateToEnglish
            VoiceTranscriptionResult.Success(VoiceTranscript("hello"))
        }

        controller.startRecording()
        controller.stopAndTranscribe(translateToEnglish = true)!!.join()

        assertEquals(true, translate)
    }

    @Test
    fun captureFailuresAndEmptyAudioKeepThePromptUntouchedForUi() = runBlocking {
        val unavailable = controller(capture = FakeCapture(startResult = VoiceCaptureStartResult.Failure("No microphone"))) { _, _ -> error("not called") }
        assertEquals(VoiceInputState.Failed("No microphone"), unavailable.startRecording())

        val empty = controller(capture = FakeCapture(audio = VoiceAudio(ByteArray(0)))) { _, _ -> error("not called") }
        empty.startRecording()
        empty.stopAndTranscribe()!!.join()
        assertIs<VoiceInputState.Failed>(empty.state.value)
        assertEquals("Existing prompt", appendVoiceTranscript("Existing prompt", VoiceTranscript("   ")))
    }

    @Test
    fun timeoutReportsRecoverableError() = runBlocking {
        val capture = FakeCapture(session = object : VoiceCaptureSession {
            override fun stop(): VoiceCaptureResult = VoiceCaptureResult.TimedOut

            override fun cancel() = Unit
        })
        val controller = controller(capture = capture) { _, _ -> error("not called") }

        controller.startRecording()
        controller.stopAndTranscribe()!!.join()

        assertEquals(VoiceInputState.Failed("Recording stopped after 90 seconds. Try a shorter request."), controller.state.value)
    }

    @Test
    fun cancellationDropsLateRecognizerResult() = runBlocking {
        val stopped = CountDownLatch(1)
        val release = CountDownLatch(1)
        val capture = FakeCapture(session = object : VoiceCaptureSession {
            override fun stop(): VoiceCaptureResult {
                stopped.countDown()
                release.await(2, TimeUnit.SECONDS)
                return VoiceCaptureResult.Captured(audio())
            }

            override fun cancel() = Unit
        })
        val controller = controller(capture = capture, dispatcher = Dispatchers.Default) { _, _ ->
            VoiceTranscriptionResult.Success(VoiceTranscript("late text"))
        }

        controller.startRecording()
        val job = controller.stopAndTranscribe()!!
        assertTrue(stopped.await(2, TimeUnit.SECONDS))
        controller.cancel()
        release.countDown()
        job.join()

        assertEquals(VoiceInputState.Idle, controller.state.value)
        assertNull(controller.consumeTranscript())
    }

    @Test
    fun recognizerFailureIsRecoverableAndRecordingCanBeRetried() = runBlocking {
        val capture = FakeCapture(audio = audio())
        val controller = controller(capture = capture) { _, _ -> VoiceTranscriptionResult.Failure("Recognition failed") }
        controller.startRecording()
        controller.stopAndTranscribe()!!.join()
        assertEquals(VoiceInputState.Failed("Recognition failed"), controller.state.value)

        assertIs<VoiceInputState.Recording>(controller.startRecording())
        assertEquals(2, capture.starts)
    }

    private fun controller(
        hasModel: Boolean = true,
        capture: FakeCapture = FakeCapture(audio = audio()),
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
        optionsProvider: () -> VoiceTranscriptionOptions = { VoiceTranscriptionOptions() },
        transcriber: VoiceTranscriber,
    ) = VoiceInputController(
        hasInstalledModel = { hasModel },
        capture = capture,
        transcriber = transcriber,
        scope = CoroutineScope(SupervisorJob() + dispatcher),
        dispatcher = dispatcher,
        optionsProvider = optionsProvider,
    )

    private class FakeCapture(
        private val startResult: VoiceCaptureStartResult? = null,
        private val audio: VoiceAudio = VoiceAudio(ByteArray(0)),
        private val session: VoiceCaptureSession? = null,
    ) : VoiceCapture {
        var starts = 0

        override fun start(): VoiceCaptureStartResult {
            starts++
            return startResult ?: VoiceCaptureStartResult.Started(session ?: object : VoiceCaptureSession {
                override fun stop(): VoiceCaptureResult = VoiceCaptureResult.Captured(audio)

                override fun cancel() = Unit
            })
        }
    }

    private companion object {
        fun audio() = VoiceAudio(byteArrayOf(1, 0, 2, 0))
    }
}
