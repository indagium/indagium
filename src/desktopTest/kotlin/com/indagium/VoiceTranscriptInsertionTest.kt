package com.indagium

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.indagium.ui.insertVoiceTranscript
import com.indagium.voice.VoiceTranscript
import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceTranscriptInsertionTest {
    @Test
    fun replacementKeepsAdjacentSentencePunctuationTight() {
        val result = insertVoiceTranscript(TextFieldValue("(old).", TextRange(1, 4)), VoiceTranscript("new words"))

        assertEquals("(new words).", result.text)
        assertEquals(10, result.selection.start)
    }

    @Test
    fun insertionInsideWordsAddsOnlyNecessaryWordBoundaries() {
        val result = insertVoiceTranscript(TextFieldValue("We shouldgo now", TextRange(9)), VoiceTranscript("new words"))

        assertEquals("We should new words go now", result.text)
    }
}
