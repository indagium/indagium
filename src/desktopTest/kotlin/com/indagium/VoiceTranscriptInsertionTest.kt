package com.indagium

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.indagium.ui.insertVoiceTranscript
import com.indagium.voice.VoiceTranscript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun transcriptAtTheStartOfTheTextIsCapitalised() {
        val result = insertVoiceTranscript(TextFieldValue("", TextRange(0)), VoiceTranscript("and Android."))

        assertEquals("And Android.", result.text)
    }

    @Test
    fun transcriptAfterSentenceEndingPunctuationOrNewlineIsCapitalised() {
        listOf("First point. ", "Really? ", "Done!", "Line one\n", "Line one\n  ").forEach { before ->
            val result = insertVoiceTranscript(TextFieldValue(before, TextRange(before.length)), VoiceTranscript("then it crashed"))

            assertTrue(result.text.endsWith("Then it crashed"), "unexpected result ${result.text} after $before")
        }
    }

    @Test
    fun transcriptInsideASentenceKeepsItsCase() {
        val result = insertVoiceTranscript(TextFieldValue("The app", TextRange(7)), VoiceTranscript("and Android"))
        assertEquals("The app and Android", result.text)

        val afterComma = insertVoiceTranscript(TextFieldValue("Open it,", TextRange(8)), VoiceTranscript("then wait"))
        assertEquals("Open it, then wait", afterComma.text)
    }

    @Test
    fun capitalisationLeavesNonLetterStartsAloneAndHandlesOtherAlphabets() {
        assertEquals("42 errors", insertVoiceTranscript(TextFieldValue(""), VoiceTranscript("42 errors")).text)
        assertEquals("Вона впала", insertVoiceTranscript(TextFieldValue(""), VoiceTranscript("вона впала")).text)
    }
}
