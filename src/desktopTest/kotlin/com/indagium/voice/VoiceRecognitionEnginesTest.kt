package com.indagium.voice

import com.indagium.model.VoiceRecognitionEngine
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceRecognitionEnginesTest {
    @Test
    fun whisperIsAlwaysTheCrossPlatformDefaultEngine() {
        assertTrue(VoiceRecognitionEngine.WHISPER in VoiceRecognitionEngines.availableChoices())
        assertTrue(VoiceRecognitionEngines.supportsTranslation(VoiceRecognitionEngine.WHISPER))
        assertFalse(VoiceRecognitionEngines.supportsTranslation(VoiceRecognitionEngine.APPLE_SPEECH))
        assertFalse(VoiceRecognitionEngines.supportsTranslation(VoiceRecognitionEngine.WINDOWS_SPEECH))
    }
}
