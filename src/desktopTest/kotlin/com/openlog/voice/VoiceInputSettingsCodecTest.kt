package com.openlog.voice

import com.openlog.model.AppSettings
import com.openlog.model.VoiceInputSettings
import com.openlog.ui.settingsFromJson
import com.openlog.ui.settingsJson
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceInputSettingsCodecTest {
    @Test
    fun voiceInputPreferenceRoundTripsThroughKeyedSettingsJson() {
        val restored = settingsFromJson(
            AppSettings(
                voiceInput = VoiceInputSettings(
                    translateToEnglish = false,
                    recognitionLanguageCodes = listOf("auto", "uk", "en", "ru"),
                    selectedRecognitionLanguageCode = "uk",
                    modelId = "whisper-small",
                ),
            ).settingsJson(),
        )!!

        assertFalse(restored.voiceInput.translateToEnglish)
        assertEquals(listOf("auto", "uk", "en", "ru"), restored.voiceInput.recognitionLanguageCodes)
        assertEquals("uk", restored.voiceInput.selectedRecognitionLanguageCode)
        assertEquals("whisper-small", restored.voiceInput.modelId)
    }

    @Test
    fun legacySettingsDefaultToEnglishTranslation() {
        val restored = settingsFromJson("{}")!!.voiceInput
        assertTrue(restored.translateToEnglish)
        assertEquals(listOf("auto", "uk", "en"), restored.recognitionLanguageCodes)
        assertEquals("auto", restored.selectedRecognitionLanguageCode)
        assertEquals("whisper-base", restored.modelId)
    }
}
