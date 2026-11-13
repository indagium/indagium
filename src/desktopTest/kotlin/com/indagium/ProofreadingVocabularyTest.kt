package com.indagium

import com.indagium.proofread.EnglishProofreader
import com.indagium.proofread.EnglishProofreadingService
import com.indagium.proofread.ProofreadingCategory
import com.indagium.proofread.ProofreadingIssue
import com.indagium.proofread.isProofreadingVocabularyIssue
import com.indagium.proofread.normalizeProofreadingVocabulary
import com.indagium.proofread.normalizeProofreadingVocabularyWord
import com.indagium.proofread.proofreadingVocabularyWordForIssue
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProofreadingVocabularyTest {
    @Test
    fun vocabularyTrimsSingleWordsAndDeduplicatesWithoutCaseSensitivity() {
        assertEquals(
            listOf("Indagium", "Café", "DON'T", "well-known"),
            normalizeProofreadingVocabulary(listOf(" Indagium ", "indagium", "", "two words", "Café", "DON'T", "well-known")),
        )
        assertNull(normalizeProofreadingVocabularyWord("two words"))
        assertNull(normalizeProofreadingVocabularyWord(""))
    }

    @Test
    fun onlyExactWholeSpellingTokensAreAccepted() {
        val vocabulary = setOf("indagium")
        val exact = ProofreadingIssue(1, 9, "spelling", emptyList(), "SPELL", ProofreadingCategory.SPELLING)
        val ordinary = exact.copy(start = 0, end = 8)
        val grammar = ordinary.copy(category = ProofreadingCategory.GRAMMAR)
        assertTrue(isProofreadingVocabularyIssue("'Indagium' works", exact, vocabulary))
        assertFalse(isProofreadingVocabularyIssue("xIndagium works", exact.copy(start = 1, end = 9), vocabulary))
        assertFalse(isProofreadingVocabularyIssue("Indagium works", grammar, vocabulary))
        assertFalse(isProofreadingVocabularyIssue("Indagium works", ordinary, setOf("dia")))
    }

    @Test
    fun aCompleteBaseWordBeforePossessiveSuffixIsEligibleButOtherPartialsAreNot() {
        val text = "The flarble's effect."
        val start = text.indexOf("flarble")
        val baseIssue = ProofreadingIssue(start, start + 7, "spelling", emptyList(), "SPELL")
        assertEquals("flarble", proofreadingVocabularyWordForIssue(text, baseIssue))
        assertTrue(isProofreadingVocabularyIssue(text, baseIssue, setOf("flarble")))

        val curlyText = "The flarble’s effect."
        val curlyStart = curlyText.indexOf("flarble")
        val curlyIssue = baseIssue.copy(start = curlyStart, end = curlyStart + 7)
        assertEquals("flarble", proofreadingVocabularyWordForIssue(curlyText, curlyIssue))
        assertTrue(isProofreadingVocabularyIssue(curlyText, curlyIssue, setOf("flarble")))

        assertNull(proofreadingVocabularyWordForIssue(text, baseIssue.copy(end = start + 3)))
        val contraction = "They don't know."
        val donStart = contraction.indexOf("don")
        assertNull(proofreadingVocabularyWordForIssue(contraction, baseIssue.copy(start = donStart, end = donStart + 3)))
        val compound = "A flarble's-made thing."
        val compoundStart = compound.indexOf("flarble")
        assertNull(proofreadingVocabularyWordForIssue(compound, baseIssue.copy(start = compoundStart, end = compoundStart + 7)))
        assertNull(proofreadingVocabularyWordForIssue(text, baseIssue.copy(end = baseIssue.end + 1)))
        assertNull(proofreadingVocabularyWordForIssue(text, baseIssue.copy(category = ProofreadingCategory.GRAMMAR)))
        assertNull(proofreadingVocabularyWordForIssue(text, baseIssue.copy(end = text.length + 1)))
    }

    @Test
    fun serviceAppliesVocabularyForBothEnglishVariantsAndKeepsOtherIssueKinds() = runBlocking {
        val languages = mutableListOf<String>()
        val service = EnglishProofreadingService {
            EnglishProofreader { text, language ->
                languages += language
                listOf(
                    ProofreadingIssue(0, text.length, "spelling", emptyList(), "SPELL", ProofreadingCategory.SPELLING),
                    ProofreadingIssue(0, text.length, "grammar", emptyList(), "GRAMMAR", ProofreadingCategory.GRAMMAR),
                )
            }
        }
        try {
            val us = service.check("Indagium", "en-US", setOf("indagium"))
            val gb = service.check("Indagium", "en-GB", setOf("indagium"))
            assertEquals(listOf(ProofreadingCategory.GRAMMAR), us.map { it.category })
            assertEquals(listOf(ProofreadingCategory.GRAMMAR), gb.map { it.category })
            assertEquals(listOf("en-US", "en-GB"), languages)
        } finally {
            service.close()
        }
    }
}
