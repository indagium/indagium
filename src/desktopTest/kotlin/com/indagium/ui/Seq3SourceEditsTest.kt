package com.indagium.ui

import androidx.compose.ui.text.TextRange
import com.indagium.diagram3.SEQ3_SOURCE_EDIT_LABEL
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3SourceDiagnostic
import com.indagium.diagram3.Seq3SourceFix
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

    // The field maps the visible start of a statement below hidden markers to the raw start of the
    // marker run, so these carets are where the app actually puts them.

    private val indentedMarker = "    $marker"

    @Test
    fun backspaceAtTheRawStartOrEndOfAMarkerLineActsAtTheStatementBelow() {
        val before = "alt ok\n$indentedMarker\n    B -> A: hi"
        val markerStart = before.indexOf(indentedMarker)
        val markerLineEnd = markerStart + indentedMarker.length
        val statement = markerLineEnd + 1
        listOf(markerStart, markerStart + 2, markerLineEnd).forEach { caret ->
            val edit = requireNotNull(deleteBack(before, caret)) { "caret $caret" }
            assertEquals(before, edit.text, "nothing to remove, markers and the join stay (caret $caret)")
            assertEquals(statement, edit.selectionStart, "the caret goes to the statement it is shown on")
        }
    }

    @Test
    fun backspaceAtTheRawStartOfAMarkerLineRemovesABlankLineBelowTheRun() {
        val before = "alt ok\n$indentedMarker\n\n    B -> A: hi"
        val markerStart = before.indexOf(indentedMarker)
        val edit = requireNotNull(deleteBack(before, markerStart))
        assertEquals("alt ok\n$indentedMarker\n    B -> A: hi", edit.text)
        assertEquals(markerStart + indentedMarker.length + 1, edit.selectionStart)
    }

    @Test
    fun deleteAtTheRawStartOrEndOfAMarkerLineDeletesFromTheStatementBelowNeverTheMarker() {
        val before = "alt ok\n$indentedMarker\n    B -> A: hi"
        val markerStart = before.indexOf(indentedMarker)
        val markerLineEnd = markerStart + indentedMarker.length
        val statement = markerLineEnd + 1
        listOf(markerStart, markerStart + 2, markerLineEnd).forEach { caret ->
            val edit = requireNotNull(deleteForward(before, caret)) { "caret $caret" }
            assertEquals(before.removeRange(statement, statement + 1), edit.text, "one character of the statement (caret $caret)")
            assertEquals(statement, edit.selectionStart)
            assertTrue(edit.text.contains(indentedMarker), "the marker line is intact")
        }
    }

    @Test
    fun deleteAtTheEndOfTheLineAboveAnIndentedMarkerKeepsTheMarkerRun() {
        val before = "alt ok\n$indentedMarker\n    B -> A: hi"
        val edit = requireNotNull(deleteForward(before, "alt ok".length))
        assertEquals(before, edit.text)
        val withBlank = "alt ok\n$indentedMarker\n\n    B -> A: hi"
        assertEquals(before, requireNotNull(deleteForward(withBlank, "alt ok".length)).text)
    }

    @Test
    fun aSelectionCoveringTheMarkerRunIsTheCaretShownAtTheStatementBelow() {
        // Home on the statement line reports the raw selection (marker start, statement start).
        val before = "alt ok\n$indentedMarker\n    B -> A: hi"
        val markerStart = before.indexOf(indentedMarker)
        val statement = markerStart + indentedMarker.length + 1
        val back = requireNotNull(seq3SourceDeleteNextToMarkers(before, markerStart, statement, puml, forward = false))
        assertEquals(before, back.text)
        assertEquals(statement, back.selectionStart)
        assertEquals(statement, back.selectionEnd)
        val forward = requireNotNull(seq3SourceDeleteNextToMarkers(before, markerStart, statement, puml, forward = true))
        assertEquals(before.removeRange(statement, statement + 1), forward.text)

        val withBlank = "alt ok\n$indentedMarker\n\n    B -> A: hi"
        val blankStart = markerStart + indentedMarker.length + 1
        val removed = requireNotNull(seq3SourceDeleteNextToMarkers(withBlank, markerStart, blankStart, puml, forward = false))
        assertEquals(before, removed.text, "the blank line below the run goes")

        val trailing = "alt ok\n$marker\n"
        val edit = requireNotNull(seq3SourceDeleteNextToMarkers(trailing, "alt ok".length, trailing.length, puml, forward = false))
        assertEquals("alt o\n$marker\n", edit.text, "a trailing run is shown at the end of the line above")
    }

    @Test
    fun aRealSelectionOverMarkersAndTextDeletesAsSelected() {
        val before = "alt ok\n$indentedMarker\n    B -> A: hi"
        val markerStart = before.indexOf(indentedMarker)
        assertNull(seq3SourceDeleteNextToMarkers(before, 2, markerStart + 5, puml, forward = false))
        assertNull(seq3SourceDeleteNextToMarkers(before, markerStart, before.length, puml, forward = true))
    }

    @Test
    fun aSelectionInsideAHiddenRunCollapsesToWhereItIsShown() {
        val before = "alt ok\n$indentedMarker\n    B -> A: hi"
        val runStart = before.indexOf(indentedMarker)
        val statement = runStart + indentedMarker.length + 1

        fun shown(text: String, start: Int, end: Int) = seq3SourceShownCaretAtHiddenRun(text, start, end, puml)
        assertEquals(statement, shown(before, runStart, statement), "the whole run: shown at the statement")
        assertEquals(statement, shown(before, statement, runStart), "a reversed selection is the same")
        assertEquals(statement, shown(before, runStart + 2, runStart + 9), "a part of the run")
        val two = "alt ok\n$indentedMarker\n$indentedMarker\n    B -> A: hi"
        assertEquals(two.indexOf("    B"), shown(two, runStart, two.indexOf("    B")), "a run of two marker lines")
        val trailing = "alt ok\n$marker\n"
        assertEquals("alt ok".length, shown(trailing, "alt ok".length, trailing.length), "a trailing run: its start")
        assertNull(shown(before, runStart, runStart), "a collapsed caret is left alone")
        assertNull(shown(before, 2, statement + 3), "a real selection over text and markers keeps its meaning")
        assertNull(shown(before, runStart - 3, statement), "reaching into the line above")
        assertNull(shown("no markers\nhere", 0, 5))
    }

    @Test
    fun aTrailingMarkerRunIsShownAsTheEndOfTheLineAboveIt() {
        val before = "alt ok\n$marker\n"
        val markerStart = before.indexOf(marker)
        val back = requireNotNull(deleteBack(before, markerStart))
        assertEquals("alt o\n$marker\n", back.text, "Backspace deletes the visible character before the run")
        assertEquals("alt o".length, back.selectionStart)
        val forward = requireNotNull(deleteForward(before, markerStart))
        assertEquals(before, forward.text, "nothing is shown after the run")
        val atEnd = requireNotNull(deleteBack(before, before.length))
        assertEquals("alt o\n$marker\n", atEnd.text, "the caret after a trailing marker is shown at the end of the line above")
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

    // ── Issues list: raw diagnostic lines, hidden markers, quick fixes ──────────────────────────

    private val withMarkers = "@startuml\n$marker\nA -> B: one\n$marker\nA -> Pay: two\n@enduml"

    @Test
    fun aRawLineIsTheLineHoldingTheOffset() {
        assertEquals(1, seq3SourceRawLineAt("a\nb\nc", 0))
        assertEquals(1, seq3SourceRawLineAt("a\nb\nc", 1), "the line break belongs to the line it ends")
        assertEquals(2, seq3SourceRawLineAt("a\nb\nc", 2))
        assertEquals(3, seq3SourceRawLineAt("a\nb\nc", 99), "an offset past the end is clamped")
    }

    @Test
    fun aDiagnosticOnAHiddenMarkerLineMapsToTheNextVisibleLine() {
        // Raw lines: 1 @startuml, 2 marker, 3 one, 4 marker, 5 two, 6 @enduml.
        assertEquals(listOf(1, 3, 3, 5, 5, 6), seq3SourceVisibleRawLines(withMarkers, listOf(1, 2, 3, 4, 5, 6), puml, showMarkers = false))
        assertEquals(listOf(2, 4), seq3SourceVisibleRawLines(withMarkers, listOf(2, 4), puml, showMarkers = true), "shown markers hide nothing")
        assertEquals(listOf(6, 1), seq3SourceVisibleRawLines(withMarkers, listOf(99, 0), puml, showMarkers = false), "out of range is clamped")
    }

    @Test
    fun aTrailingMarkerRunMapsBackToTheLastVisibleLine() {
        val source = "@startuml\nA -> B: one\n$marker\n"
        assertEquals(listOf(2, 2, 2), seq3SourceVisibleRawLines(source, listOf(3, 4, 2), puml, showMarkers = false))
    }

    @Test
    fun theCaretLandsAfterTheIndentationOfTheVisibleLine() {
        val source = "@startuml\n$marker\n    A -> Pay: two\n\n@enduml"
        assertEquals(source.indexOf("A -> Pay"), seq3SourceCaretOffsetForLine(source, 3, puml, showMarkers = false))
        assertEquals(source.indexOf("A -> Pay"), seq3SourceCaretOffsetForLine(source, 2, puml, showMarkers = false), "a marker line shows the line after it")
        assertEquals(source.indexOf("\n\n@enduml") + 1, seq3SourceCaretOffsetForLine(source, 4, puml, showMarkers = false), "a blank line ends where it starts")
        assertEquals(source.indexOf(marker), seq3SourceCaretOffsetForLine(source, 2, puml, showMarkers = true))
    }

    @Test
    fun aLineIndexInTheVisibleNumberingIsFoundByItsRawNumber() {
        val map = seq3SourceLineNumberMap(withMarkers, puml, showMarkers = false)
        assertEquals(4, map.lineCount)
        assertEquals(0, map.indexOfLine(1))
        assertEquals(1, map.indexOfLine(3))
        assertEquals(2, map.indexOfLine(5))
        assertEquals(3, map.indexOfLine(6))
        assertEquals(1, map.indexOfLine(2), "a hidden line is the row that follows it")
        assertEquals(3, map.indexOfLine(99), "past the end is the last row")
        assertEquals(4, seq3SourceLineNumberMap("a\nb\nc\nd\ne", puml, showMarkers = false).indexOfLine(5))
    }

    @Test
    fun theDiagnosticAtTheCaretIsFoundOnItsVisibleLine() {
        val onTwo = Seq3SourceDiagnostic(5, "Participant 'Pay' isn't declared.")
        val onMarker = Seq3SourceDiagnostic(4, "on the marker above two")
        val caretOnTwo = withMarkers.indexOf("A -> Pay") + 3
        assertEquals(onTwo, seq3SourceDiagnosticAtCaret(withMarkers, caretOnTwo, listOf(onTwo), puml, showMarkers = false))
        assertEquals(
            onMarker,
            seq3SourceDiagnosticAtCaret(withMarkers, caretOnTwo, listOf(onMarker), puml, showMarkers = false),
            "a hidden line is shown as the line after it",
        )
        assertNull(seq3SourceDiagnosticAtCaret(withMarkers, withMarkers.indexOf("A -> B") + 1, listOf(onTwo), puml, showMarkers = false))
        assertNull(seq3SourceDiagnosticAtCaret(withMarkers, 0, emptyList(), puml, showMarkers = false))
    }

    @Test
    fun aQuickFixInsertsOneLineAndTheCaretStaysOnItsText() {
        val source = "@startuml\nparticipant \"A\" as A\nA -> Pay: hi\n@enduml"
        val fix = Seq3SourceFix("Add participant Pay", 3, "participant \"Pay\" as Pay")
        val caret = source.indexOf("-> Pay")
        val edit = seq3SourceApplyFix(source, fix, caret, caret)
        assertEquals("@startuml\nparticipant \"A\" as A\nparticipant \"Pay\" as Pay\nA -> Pay: hi\n@enduml", edit.text)
        assertEquals(edit.text.indexOf("-> Pay"), edit.selectionStart, "the caret moves down with its text")
        val above = seq3SourceApplyFix(source, fix, 2, 2)
        assertEquals(2, above.selectionStart, "a caret above the insertion does not move")
        val atLineStart = source.indexOf("A -> Pay")
        assertEquals(
            edit.text.indexOf("A -> Pay"),
            seq3SourceApplyFix(source, fix, atLineStart, atLineStart).selectionStart,
            "a caret at the line start goes with that line",
        )
    }

    @Test
    fun aQuickFixPastTheLastLineAppends() {
        val fix = Seq3SourceFix("Add", 99, "x")
        assertEquals("a\nx", seq3SourceApplyFix("a", fix, 0, 0).text)
        assertEquals("a\nx", seq3SourceApplyFix("a\n", fix, 0, 0).text)
        assertEquals("x", seq3SourceApplyFix("", fix, 0, 0).text)
    }
}
