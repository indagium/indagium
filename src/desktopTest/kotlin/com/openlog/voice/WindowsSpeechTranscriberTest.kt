package com.openlog.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsSpeechTranscriberTest {
    @Test
    fun unavailableRecognizerUsesFriendlyLanguageSpecificMessage() {
        assertEquals(
            "Windows Speech has no installed offline recognizer for Ukrainian. " +
                "Choose Local Whisper, or install Basic or Enhanced speech recognition for a supported Windows language.",
            WindowsSpeechTranscriber.failureMessage("OPENLOG_SPEECH_LANGUAGE_UNAVAILABLE", "uk-UA"),
        )
    }
}
