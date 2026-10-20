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

    @Test
    fun translationCapabilityDependsOnTheSelectedWhisperModel() {
        assertTrue(VoiceRecognitionEngines.supportsTranslation(VoiceRecognitionEngine.WHISPER, VoiceModelCatalog.base))
        assertTrue(VoiceRecognitionEngines.supportsTranslation(VoiceRecognitionEngine.WHISPER, VoiceModelCatalog.largeV3))
        assertFalse(VoiceRecognitionEngines.supportsTranslation(VoiceRecognitionEngine.WHISPER, VoiceModelCatalog.largeV3Turbo))
    }
}
