package com.indagium.ui

import androidx.compose.ui.text.TextRange
import com.indagium.diagram3.Seq3Dialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The keyword-template list opens only for a word the user typed, alone on its line. */
class Seq3SourceTypedKeywordTest {
    private fun typed(old: String, new: String, caret: Int = new.length, prior: Seq3SourceTypedWord? = null) =
        seq3SourceTypedWordAfter(prior, old, new, TextRange(caret))

    @Test
    fun wordStartIsTheFirstLetterOrTheSlashInFrontOfIt() {
        assertEquals(10, seq3SourceWordStartAt("@startuml\nopt", 13))
        assertEquals(10, seq3SourceWordStartAt("@startuml\n/par", 14))
        assertEquals(10, seq3SourceWordStartAt("@startuml\n/par", 12))
        assertEquals(2, seq3SourceWordStartAt("a/par", 5), "a slash glued to a word is no slash trigger")
        assertEquals(0, seq3SourceWordStartAt("opt", 2))
    }

    @Test
    fun anInsertionEndingAtTheCaretInAWordIsTyping() {
        assertEquals(Seq3SourceTypedWord(10), typed("@startuml\n", "@startuml\no"))
        assertEquals(Seq3SourceTypedWord(10), typed("@startuml\no", "@startuml\nop"))
        assertEquals(Seq3SourceTypedWord(10), typed("@startuml\n", "@startuml\nopt"), "several characters at once")
        assertEquals(Seq3SourceTypedWord(10), typed("@startuml\n", "@startuml\n/"), "the slash itself opens the slash list")
        assertEquals(Seq3SourceTypedWord(10), typed("@startuml\n/pa", "@startuml\n/par"))
        assertEquals(Seq3SourceTypedWord(14), typed("@startuml\n    ", "@startuml\n    op"), "after indentation")
    }

    @Test
    fun aRepeatedLetterIsStillAnInsertionAtTheCaret() {
        assertEquals(Seq3SourceTypedWord(0), typed("aa", "aaa", caret = 2))
        assertEquals(Seq3SourceTypedWord(0), typed("op", "opp"))
    }

    @Test
    fun typingInTheMiddleOfAWordCounts() {
        assertEquals(Seq3SourceTypedWord(0), typed("ot", "opt", caret = 2))
    }

    @Test
    fun aBlankOrANewlineTypedEndsTheWord() {
        assertNull(typed("opt", "opt ", prior = Seq3SourceTypedWord(0)))
        assertNull(typed("opt", "opt\n", prior = Seq3SourceTypedWord(0)))
    }

    @Test
    fun deletingInsideTheTypedWordKeepsIt() {
        val prior = Seq3SourceTypedWord(10)
        assertEquals(prior, typed("@startuml\nopt", "@startuml\nop", prior = prior))
        assertEquals(prior, typed("@startuml\nop", "@startuml\no", prior = prior))
        assertEquals(prior, typed("@startuml\nopt", "@startuml\nopt".removeRange(11, 12), caret = 11, prior = prior), "Delete in the word")
    }

    @Test
    fun deletingInAWordThatWasNotTypedDoesNotOpenIt() {
        assertNull(typed("@startuml\nopt condition", "@startuml\nop condition", caret = 12))
        assertNull(typed("@startuml\nopt", "@startuml\nop", prior = Seq3SourceTypedWord(0)), "another word was typed")
    }

    @Test
    fun deletingElsewhereOrALongerEditAwayFromTheCaretEndsIt() {
        val prior = Seq3SourceTypedWord(10)
        // The text after the caret changed: this is no insertion at the caret.
        assertNull(typed("@startuml\nopt x", "@startuml\nopt y", caret = 13, prior = prior))
    }

    @Test
    fun aCaretMoveKeepsTheWordWhileTheCaretStaysInItAndEndsItOtherwise() {
        val text = "@startuml\nopt\nnote over A: hi"
        val prior = Seq3SourceTypedWord(10)
        assertEquals(prior, typed(text, text, caret = 11, prior = prior))
        assertEquals(prior, typed(text, text, caret = 13, prior = prior))
        assertEquals(prior, typed(text, text, caret = 10, prior = prior), "the start of the word is still the word")
        assertNull(typed(text, text, caret = 5, prior = prior))
        assertNull(typed(text, text, caret = text.length, prior = prior))
        assertNull(typed(text, text, caret = 11, prior = null), "a caret move never types")
    }

    @Test
    fun aSelectionIsNeverTheTypedWord() {
        val text = "@startuml\nopt"
        assertNull(seq3SourceTypedWordAfter(Seq3SourceTypedWord(10), text, text, TextRange(10, 13)))
    }

    @Test
    fun typingOverASelectionIsAnInsertion() {
        assertEquals(Seq3SourceTypedWord(10), typed("@startuml\nxyz", "@startuml\no"))
    }

    @Test
    fun lineShapeAllowsOnlyIndentationBeforeAndNothingAfter() {
        val bare = "@startuml\nopt"
        assertTrue(seq3SourceKeywordLineShape(bare, 10, bare.length))
        val indented = "@startuml\nalt a\n    opt"
        assertTrue(seq3SourceKeywordLineShape(indented, indented.length - 3, indented.length), "indentation is allowed")
        val tabbed = "@startuml\n\t\topt"
        assertTrue(seq3SourceKeywordLineShape(tabbed, 12, tabbed.length))
        val slash = "@startuml\n  /par"
        assertTrue(seq3SourceKeywordLineShape(slash, 12, slash.length), "the slash is part of the word")
        val mermaid = "sequenceDiagram\n    loop Every\n        op\n    end"
        val mermaidWord = mermaid.lastIndexOf("op")
        assertTrue(seq3SourceKeywordLineShape(mermaid, mermaidWord, mermaidWord + 2), "Mermaid body indentation")
    }

    @Test
    fun lineShapeRejectsTextBeforeTheWord() {
        val source = "@startuml\nAlice -> opt"
        assertFalse(seq3SourceKeywordLineShape(source, source.length - 3, source.length))
        val slash = "@startuml\ntitle /par"
        assertFalse(seq3SourceKeywordLineShape(slash, slash.length - 4, slash.length))
    }

    @Test
    fun lineShapeRejectsTextAfterTheCaret() {
        val source = "@startuml\nopt condition"
        assertFalse(seq3SourceKeywordLineShape(source, 10, 13), "typing in front of existing text")
        val mid = "@startuml\nopt"
        assertFalse(seq3SourceKeywordLineShape(mid, 10, 12), "the rest of the word is after the caret")
        val blanks = "@startuml\nopt   \nend"
        assertTrue(seq3SourceKeywordLineShape(blanks, 10, 13), "blanks after the caret are nothing")
        val crlf = "@startuml\r\nopt\r\nend"
        assertTrue(seq3SourceKeywordLineShape(crlf, 11, 14), "a carriage return is blank")
    }

    @Test
    fun lineShapeOnTheFirstLine() {
        assertTrue(seq3SourceKeywordLineShape("opt", 0, 3))
        assertFalse(seq3SourceKeywordLineShape("x opt", 2, 5))
    }

    private fun gated(source: String, caret: Int, typed: Seq3SourceTypedWord?, dialect: Seq3Dialect = Seq3Dialect.PLANTUML) =
        seq3SourceTypedKeywordTrigger(
            seq3SourceSuggestionTrigger(source, TextRange(caret), dialect), source, TextRange(caret), typed,
        )

    @Test
    fun aTypedWordAloneOnItsLineKeepsItsKeywordTrigger() {
        val source = "@startuml\nopt"
        val trigger = requireNotNull(gated(source, source.length, Seq3SourceTypedWord(10)))
        assertTrue(trigger.keywords)
        assertEquals("opt", trigger.query)
    }

    @Test
    fun aWordTheCaretMerelySitsInOffersNoKeywords() {
        val source = "@startuml\nopt condition\n    From -> To: message label\nend"
        val trigger = gated(source, 12, null)
        assertFalse(requireNotNull(trigger).keywords, "a line-start word keeps only its names")
        assertTrue(trigger.names)
        assertEquals("opt", trigger.query)
    }

    @Test
    fun aSlashWordNotTypedOffersNothing() {
        val source = "@startuml\n/par"
        assertNull(gated(source, source.length, null))
        assertNull(gated(source, source.length, Seq3SourceTypedWord(0)), "another word was typed")
    }

    @Test
    fun aTypedWordInFrontOfOtherTextOffersNoKeywords() {
        val source = "@startuml\nopt condition"
        assertFalse(requireNotNull(gated(source, 13, Seq3SourceTypedWord(10))).keywords)
    }

    @Test
    fun aNameOnlyTriggerIsUnchanged() {
        val source = "@startuml\nAlias -> Ali"
        val trigger = requireNotNull(seq3SourceSuggestionTrigger(source, TextRange(source.length)))
        assertFalse(trigger.keywords)
        assertEquals(trigger, seq3SourceTypedKeywordTrigger(trigger, source, TextRange(source.length), null))
        assertNull(seq3SourceTypedKeywordTrigger(null, source, TextRange(0), null))
    }

    @Test
    fun theKeywordEntriesOfAGatedTriggerAreGoneButItsNamesStay() {
        val source = "@startuml\nparticipant Alice\nAl"
        val participants = sourceParticipants(source, Seq3Dialect.PLANTUML)
        val aliases = participants.map { it.alias }
        val open = requireNotNull(gated(source, source.length, Seq3SourceTypedWord(source.length - 2)))
        val shut = requireNotNull(gated(source, source.length, null))
        val both = seq3SourceActiveSuggestions(source, open, Seq3Dialect.PLANTUML, aliases, participants)
        assertTrue(both.any { it.name } && both.any { !it.name }, "names and keywords: $both")
        val names = seq3SourceActiveSuggestions(source, shut, Seq3Dialect.PLANTUML, aliases, participants)
        assertTrue(names.isNotEmpty() && names.all { it.name }, "names alone: $names")
    }
}
