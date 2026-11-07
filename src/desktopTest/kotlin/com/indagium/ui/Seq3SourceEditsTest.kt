package com.indagium.ui

import androidx.compose.ui.text.TextRange
import com.indagium.diagram3.SEQ3_SOURCE_EDIT_LABEL
import com.indagium.diagram3.Seq3Dialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Seq3SourceEditsTest {
    private val puml = Seq3Dialect.PLANTUML
    private val marker = "' indagium-seq3:v1 abc123"

    private fun deleteBack(source: String, caret: Int) = seq3SourceDeleteNextToMarkers(source, caret, caret, puml, forward = false)

    private fun deleteForward(source: String, caret: Int) = seq3SourceDeleteNextToMarkers(source, caret, caret, puml, forward = true)

    // ── Backspace and Delete next to markers ───────────────────────────────────────────────────

    @Test
    fun backspaceOnABlankLineBelowMarkersRemovesTheBlankLineOnly() {
        val before = "title T\n$marker\n\nA -> B: hi"
        val caret = before.indexOf("\n\nA") + 1
        val edit = requireNotNull(deleteBack(before, caret))
        assertEquals("title T\n$marker\nA -> B: hi", edit.text)
        assertEquals(caret, edit.selectionStart)
        assertEquals(caret, edit.selectionEnd)
        assertTrue(edit.text.contains(marker), "the marker run stays")
    }

    @Test
    fun backspaceOnTheLastBlankLineRemovesItsWhitespaceOnly() {
        val before = "title T\n$marker\n    "
        val edit = requireNotNull(deleteBack(before, before.lastIndexOf('\n') + 1))
        assertEquals("title T\n$marker\n", edit.text)
    }

    @Test
    fun backspaceAtTheStartOfAStatementBelowMarkersNeverJoinsIntoTheMarker() {
        val before = "title T\n$marker\nA -> B: hi"
        val caret = before.indexOf("A -> B")
        val edit = requireNotNull(deleteBack(before, caret))
        assertEquals(before, edit.text, "there is no blank line to remove, so nothing changes")
        assertEquals(caret, edit.selectionStart)
    }

    @Test
    fun backspaceAtAStatementRemovesABlankLineAboveTheMarkersInstead() {
        val before = "title T\n\n$marker\nA -> B: hi"
        val caret = before.indexOf("A -> B")
        val edit = requireNotNull(deleteBack(before, caret))
        assertEquals("title T\n$marker\nA -> B: hi", edit.text)
        assertEquals(edit.text.indexOf("A -> B"), edit.selectionStart, "the caret stays on its statement")
    }

    @Test
    fun deleteAtTheEndOfTheLineAboveMarkersRemovesTheBlankLineBelowThem() {
        val before = "title T\n$marker\n\nA -> B: hi"
        val caret = "title T".length
        val edit = requireNotNull(deleteForward(before, caret))
        assertEquals("title T\n$marker\nA -> B: hi", edit.text)
        assertEquals(caret, edit.selectionStart)
    }

    @Test
    fun deleteAboveAStatementWithMarkersDoesNothing() {
        val before = "title T\n$marker\nA -> B: hi"
        val edit = requireNotNull(deleteForward(before, "title T".length))
        assertEquals(before, edit.text)
    }

    @Test
    fun otherDeletesKeepTheDefaultEdit() {
        val plain = "title T\n\nA -> B: hi"
        assertNull(deleteBack(plain, plain.indexOf("\nA") + 1), "no marker beside the line break")
        assertNull(deleteForward(plain, "title T".length))
        val marked = "title T\n$marker\n\nA -> B: hi"
        assertNull(deleteBack(marked, marked.indexOf("\n\nA") + 2), "a blank line, not the marker, is above the statement")
        assertNull(deleteBack(marked, marked.indexOf("\n\nA") + 2 + 3), "caret inside a statement")
        assertNull(seq3SourceDeleteNextToMarkers(marked, 2, 9, puml, forward = false), "a selection deletes as selected")
        assertNull(
            seq3SourceDeleteNextToMarkers(marked, marked.indexOf("\n\nA") + 1, marked.indexOf("\n\nA") + 1, puml, false, showMarkers = true),
            "visible markers are the user's to delete",
        )
        assertNull(deleteBack("no markers\n\nhere", 11))
    }

    // ── Typing runs in the editor history ──────────────────────────────────────────────────────

    private fun type(text: String, char: Char, run: Seq3SourceTypingRun?, now: Long, at: Int = text.length): Pair<String, Seq3SourceHistoryDecision> {
        val next = text.substring(0, at) + char + text.substring(at)
        return next to seq3SourceHistoryDecision(text, next, TextRange(at + 1), run, now)
    }

    @Test
    fun aTypedWordIsOneUndoStepAndWhitespaceStartsTheNext() {
        var text = ""
        var run: Seq3SourceTypingRun? = null
        val pushes = mutableListOf<Boolean>()
        "foo bar".forEachIndexed { index, char ->
            val (next, decision) = type(text, char, run, now = 100L + index)
            pushes += decision.pushStep
            text = next
            run = decision.run
        }
        // f o o ' ' b a r: the first letter and the space open steps, the rest join their step.
        assertEquals(listOf(true, false, false, true, false, false, false), pushes)
    }

    @Test
    fun aCaretJumpOrAPauseStartsANewStep() {
        val (_, first) = type("fo", 'o', Seq3SourceTypingRun(2, 100L), now = 200L)
        assertFalse(first.pushStep)
        val (_, jumped) = type("foo", 'x', Seq3SourceTypingRun(3, 100L), now = 200L, at = 1)
        assertTrue(jumped.pushStep, "typing somewhere else is a new step")
        assertEquals(2, jumped.run?.caret)
        val (_, paused) = type("fo", 'o', Seq3SourceTypingRun(2, 100L), now = 100L + SEQ3_SOURCE_TYPING_PAUSE_MS + 1)
        assertTrue(paused.pushStep, "a pause over a second starts a new step")
        val (_, justInTime) = type("fo", 'o', Seq3SourceTypingRun(2, 100L), now = 100L + SEQ3_SOURCE_TYPING_PAUSE_MS)
        assertFalse(justInTime.pushStep)
    }

    @Test
    fun deletionsPastesAndNewlinesAreTheirOwnStepsAndCloseTheRun() {
        val run = Seq3SourceTypingRun(3, 100L)
        val deleted = seq3SourceHistoryDecision("foo", "fo", TextRange(2), run, 110L)
        assertTrue(deleted.pushStep)
        assertNull(deleted.run)
        val pasted = seq3SourceHistoryDecision("foo", "foobar", TextRange(6), run, 110L)
        assertTrue(pasted.pushStep)
        assertNull(pasted.run)
        val newline = seq3SourceHistoryDecision("foo", "foo\n", TextRange(4), run, 110L)
        assertTrue(newline.pushStep)
        assertNull(newline.run)
        val selected = seq3SourceHistoryDecision("foo", "foox", TextRange(3, 4), run, 110L)
        assertTrue(selected.pushStep)
    }

    // ── Diagram undo burst ─────────────────────────────────────────────────────────────────────

    @Test
    fun liveSourceEditsInOneBurstShareAnUndoEntry() {
        val label = SEQ3_SOURCE_EDIT_LABEL
        assertTrue(seq3SourceUndoMerges(label, 1_000L, 1_600L))
        assertTrue(seq3SourceUndoMerges(label, 1_000L, 1_000L + SEQ3_SOURCE_UNDO_BURST_MS))
        assertFalse(seq3SourceUndoMerges(label, 1_000L, 1_000L + SEQ3_SOURCE_UNDO_BURST_MS + 1), "a pause ends the burst")
        assertFalse(seq3SourceUndoMerges(label, null, 1_100L), "no burst is open")
        assertFalse(seq3SourceUndoMerges("Add message", 1_000L, 1_100L), "another command is on top")
        assertFalse(seq3SourceUndoMerges(null, 1_000L, 1_100L), "nothing to merge into")
        assertFalse(seq3SourceUndoMerges(label, 2_000L, 1_000L), "a clock that went back never merges")
    }

    // ── Caret after an external replacement ────────────────────────────────────────────────────

    @Test
    fun theCaretKeepsItsPlaceWhenTextIsReplacedFromOutside() {
        assertEquals(2, seq3SourceMapCaret("abXcd", "abYYcd", 2), "before the change")
        assertEquals(6, seq3SourceMapCaret("abXcd", "abYYcd", 5), "after the change moves with it")
        assertEquals(3, seq3SourceMapCaret("abXXXcd", "abYcd", 4), "inside the change stays within the new text")
        assertEquals(0, seq3SourceMapCaret("abc", "", 3))
        assertEquals(0, seq3SourceMapCaret("", "abc", 0))
        assertEquals(2, seq3SourceMapCaret("ab", "abcd", 99), "an out-of-range caret is clamped first")
    }
}
