package com.openlog.voice

import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Opt-in real-native verification. It intentionally skips in normal CI because the Whisper model
 * is user-downloaded. Run with INDAGIUM_VOICE_MODEL and INDAGIUM_VOICE_FIXTURE environment
 * variables (or the legacy OPENLOG_VOICE_MODEL / OPENLOG_VOICE_FIXTURE spelling) to prove that the
 * installed model and JNI binding recognize a known-good audio file.
 */
class WhisperJniManualIntegrationTest {
    @Test
    fun recognizesKnownGoodFixtureWhenConfigured() {
        val model = (System.getenv("INDAGIUM_VOICE_MODEL") ?: System.getenv("OPENLOG_VOICE_MODEL"))?.let(::File) ?: return
        val fixture = (System.getenv("INDAGIUM_VOICE_FIXTURE") ?: System.getenv("OPENLOG_VOICE_FIXTURE"))?.let(::File) ?: return
        require(model.isFile) { "INDAGIUM_VOICE_MODEL must point to an installed Whisper model" }
        require(fixture.isFile) { "INDAGIUM_VOICE_FIXTURE must point to an audio fixture" }

        val format = AudioFormat(VoiceAudio.SAMPLE_RATE_HZ.toFloat(), 16, VoiceAudio.CHANNELS, true, false)
        val pcm = AudioSystem.getAudioInputStream(fixture).use { source ->
            AudioSystem.getAudioInputStream(format, source).use { it.readBytes() }
        }
        val result = WhisperJniVoiceTranscriber { model }.transcribe(
            VoiceAudio(pcm),
            VoiceTranscriptionOptions(translateToEnglish = false),
        )

        val success = assertIs<VoiceTranscriptionResult.Success>(result)
        assertTrue(success.transcript.text.contains("hello", ignoreCase = true), success.transcript.text)
    }
}
