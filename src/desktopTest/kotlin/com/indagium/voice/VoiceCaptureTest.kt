package com.indagium.voice

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VoiceCaptureTest {
    @Test
    fun reportsMicrophonePermissionDenialWithoutStartingCapture() {
        val capture = JavaSoundVoiceCapture(lineProvider = { throw SecurityException("denied") })

        val result = assertIs<VoiceCaptureStartResult.Failure>(capture.start())

        assertTrue(result.message.contains("denied", ignoreCase = true))
    }

    @Test
    fun reportsUnavailableMicrophoneWithoutStartingCapture() {
        val capture = JavaSoundVoiceCapture(lineProvider = { error("no input device") })

        val result = assertIs<VoiceCaptureStartResult.Failure>(capture.start())

        assertTrue(result.message.contains("microphone", ignoreCase = true))
    }
}
