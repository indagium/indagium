package com.indagium.ui

import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.sourceEmissionAnchors
import com.indagium.diagram3.toSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 6 pure rules: the line to message anchors, the comment toggle and find / replace. */
class Seq3SourceLinkAndFindTest {
    private val puml = Seq3Dialect.PLANTUML
    private val mermaid = Seq3Dialect.MERMAID
    private val marker = "' indagium-seq3:v1 abc123"
    private val mermaidMarker = "%% indagium-seq3:v1 abc123"

    // ── The anchors on indented, marked source ───────────────────────────────────────────────────

    private fun occurrence(id: Int) =
        Seq3Occurrence(id, id.toLong(), "00:00:0$id", 1, 1, 'I', "row $id", emptyMap(), elapsedMillis = id.toLong())

    private fun document() = Seq3Document(
        title = "Link",
        lifelines = listOf(Seq3Lifeline("A", "actor.A", setOf("A"), 0), Seq3Lifeline("B", "service.B", setOf("B"), 1)),
        messages = listOf(
            Seq3Message("first", Seq3Match("A", "one"), "A", "B", "one", repeat = Seq3Repeat.EVERY, occurrences = listOf(occurrence(1))),
            Seq3Message("second", Seq3Match("B", "two"), "B", "A", "two", repeat = Seq3Repeat.EVERY, occurrences = listOf(occurrence(2), occurrence(3))),
        ),
        fragments = listOf(Seq3Fragment("loop", Seq3FragmentKind.LOOP, "again", listOf("second"))),
    )

    @Test
    fun theAnchorsOfIndentedMarkedSourceAreTheSameAsThoseOfTheFlatSource() {
        Seq3Dialect.entries.forEach { dialect ->
            val plain = document().toSource(dialect)
            val indented = seq3IndentSource(plain, dialect)
            assertTrue(indented != plain, dialect.name)
            val anchors = sourceEmissionAnchors(indented, dialect)
            assertEquals(sourceEmissionAnchors(plain, dialect), anchors, dialect.name)
            assertEquals(setOf("first", "second"), anchors.map { it.messageId }.toSet(), dialect.name)
            val lines = indented.lines()
            anchors.forEach { anchor ->
                val text = lines[anchor.line - 1]
                assertTrue(text.isNotBlank() && !isSeq3SourceMarkerLine(text, dialect), "${dialect.name}: $text")
                // The statement is the line right after its (hidden) marker.
                assertTrue(isSeq3SourceMarkerLine(lines[anchor.line - 2], dialect), "${dialect.name}: ${lines[anchor.line - 2]}")
                if (anchor.messageId == "second") assertTrue(text.startsWith("    "), "${dialect.name}: the loop body is indented: '$text'")
                // The statement stays on a visible line while the markers are hidden; its marker does not.
                assertEquals(anchor.line, seq3SourceVisibleRawLines(indented, listOf(anchor.line), dialect, showMarkers = false).single())
                assertTrue(seq3SourceVisibleRawLines(indented, listOf(anchor.line - 1), dialect, showMarkers = false).single() != anchor.line - 1)
                assertNull(seq3SourceAnchorAtLine(anchors, anchor.line - 1), "a marker line is no statement")
                assertEquals(anchor, seq3SourceAnchorAtLine(anchors, anchor.line))
            }
        }
    }

    @Test
    fun aMessageFindsItsLineAndARepeatedOneFindsTheLineOfItsOccurrence() {
        val dialect = puml
        val anchors = sourceEmissionAnchors(seq3IndentSource(document().toSource(dialect), dialect), dialect)
        val second = anchors.filter { it.messageId == "second" }
        assertEquals(2, second.size, anchors.toString())
        assertEquals(second.first().line, seq3SourceLineOfMessage(anchors, "second"))
        assertEquals(second.last().line, seq3SourceLineOfMessage(anchors, "second", second.last().occurrenceEntryId))
        assertEquals(second.first().line, seq3SourceLineOfMessage(anchors, "second", 999), "an unknown occurrence falls back to the first")
        assertNull(seq3SourceLineOfMessage(anchors, "missing"))
        assertNull(seq3SourceLineOfMessage(emptyList(), "first"))
    }

    @Test
    fun aStatementWithoutAMarkerHasNoAnchor() {
        val source = "@startuml\nparticipant A\nparticipant B\nA -> B: typed by hand\n@enduml"
        assertTrue(sourceEmissionAnchors(source, puml).isEmpty())
    }

    // ── Comment toggle ───────────────────────────────────────────────────────────────────────────

    @Test
    fun commentingIndentsTheMarkAndUncommentingRestoresTheText() {
        val source = "@startuml\nloop x\n    A -> B: hi\n    B -> A: ok\nend\n@enduml"
        val from = source.indexOf("    A")
        val to = source.indexOf("ok") + 2
        val commented = seq3SourceToggleComment(source, from, to, puml)
        assertEquals("@startuml\nloop x\n    ' A -> B: hi\n    ' B -> A: ok\nend\n@enduml", commented.text)
        // The selection still covers the same two lines.
        assertEquals("    ' A -> B: hi\n    ' B -> A: ok", commented.text.substring(commented.selectionStart, commented.selectionEnd))
        val restored = seq3SourceToggleComment(commented.text, commented.selectionStart, commented.selectionEnd, puml)
        assertEquals(source, restored.text)
        assertEquals("    A -> B: hi\n    B -> A: ok", restored.text.substring(restored.selectionStart, restored.selectionEnd))
    }

    @Test
    fun mermaidCommentsWithTwoPercentSigns() {
        val source = "sequenceDiagram\n    A->>B: hi"
        val caret = source.indexOf("hi")
        val commented = seq3SourceToggleComment(source, caret, caret, mermaid)
        assertEquals("sequenceDiagram\n    %% A->>B: hi", commented.text)
        assertEquals(caret + 3, commented.selectionStart, "a caret moves along with its text")
        assertEquals(source, seq3SourceToggleComment(commented.text, commented.selectionStart, commented.selectionEnd, mermaid).text)
    }

    @Test
    fun theToggleNeverTouchesAMarkerLineOrABlankLine() {
        listOf(puml to marker, mermaid to mermaidMarker).forEach { (dialect, mark) ->
            val prefix = seq3SourceCommentPrefix(dialect)
            val source = "head\n$mark\nA -> B: hi\n\n$mark\nB -> A: ok"
            val edit = seq3SourceToggleComment(source, 0, source.length, dialect)
            assertEquals("$prefix head\n$mark\n$prefix A -> B: hi\n\n$mark\n$prefix B -> A: ok", edit.text, dialect.name)
            // A selection of only markers and blanks changes nothing.
            val onlyMarkers = source.indexOf(mark)
            val none = seq3SourceToggleComment(source, onlyMarkers, onlyMarkers + mark.length, dialect)
            assertEquals(source, none.text, dialect.name)
        }
    }

    @Test
    fun uncommentingNeedsEveryTouchedLineCommentedElseTheToggleComments() {
        val mixed = "' A -> B: hi\nB -> A: ok"
        assertEquals("' ' A -> B: hi\n' B -> A: ok", seq3SourceToggleComment(mixed, 0, mixed.length, puml).text)
        val all = "' A -> B: hi\n'B -> A: ok"
        assertEquals("A -> B: hi\nB -> A: ok", seq3SourceToggleComment(all, 0, all.length, puml).text, "the space after the mark is optional")
    }

    @Test
    fun aSelectionEndingAtTheStartOfALineLeavesThatLineAlone() {
        val source = "A -> B: one\nB -> A: two\nA -> B: three"
        val end = source.indexOf("A -> B: three")
        val edit = seq3SourceToggleComment(source, 0, end, puml)
        assertEquals("' A -> B: one\n' B -> A: two\nA -> B: three", edit.text)
        assertEquals(edit.text.indexOf("A -> B: three"), edit.selectionEnd, "the end still stands at the start of the third line")
    }

    // ── Find and replace ─────────────────────────────────────────────────────────────────────────

    @Test
    fun findMatchesIgnoreCaseAndNeverOverlap() {
        val source = "Alpha alpha ALPHA\naaa"
        assertEquals(listOf(0, 6, 12), seq3SourceFindMatches(source, "alpha", puml, showMarkers = true).map { it.start })
        assertEquals(1, seq3SourceFindMatches(source, "aa", puml, showMarkers = true).size, "'aaa' holds one non-overlapping 'aa'")
        assertTrue(seq3SourceFindMatches(source, "", puml, showMarkers = true).isEmpty())
    }

    @Test
    fun aMatchInsideAHiddenMarkerIsSkippedAndCountedOnceShown() {
        val source = "A -> B: abc\n$marker\nB -> A: abc"
        val hidden = seq3SourceFindMatches(source, "abc", puml, showMarkers = false)
        assertEquals(2, hidden.size)
        hidden.forEach { assertFalse(source.substring(it.start, it.endExclusive).isEmpty()) }
        assertTrue(hidden.none { it.start > source.indexOf(marker) && it.endExclusive <= source.indexOf(marker) + marker.length })
        assertEquals(3, seq3SourceFindMatches(source, "abc", puml, showMarkers = true).size)
        // A run at the very start or end of the text is hidden as well.
        val marked = "$marker\nabc\n$marker"
        assertEquals(1, seq3SourceFindMatches(marked, "abc", puml, showMarkers = false).size)
        assertEquals(3, seq3SourceFindMatches(marked, "abc", puml, showMarkers = true).size)
    }

    @Test
    fun stepWrapsAndTheStartIndexFollowsTheCaret() {
        val matches = seq3SourceFindMatches("x a x a x a", "a", puml, showMarkers = true)
        assertEquals(3, matches.size)
        assertEquals(1, seq3SourceFindStep(matches, 0, forward = true))
        assertEquals(0, seq3SourceFindStep(matches, 2, forward = true))
        assertEquals(2, seq3SourceFindStep(matches, 0, forward = false))
        assertEquals(0, seq3SourceFindStep(matches, -1, forward = true))
        assertEquals(2, seq3SourceFindStep(matches, -1, forward = false))
        assertEquals(-1, seq3SourceFindStep(emptyList(), 0, forward = true))
        assertEquals(1, seq3SourceFindIndexFrom(matches, 3))
        assertEquals(0, seq3SourceFindIndexFrom(matches, 100), "past the last match wraps to the first")
        assertEquals(-1, seq3SourceFindIndexFrom(emptyList(), 0))
    }

    @Test
    fun replacingOneMatchPutsTheCaretAfterTheReplacement() {
        val source = "one two one"
        val second = seq3SourceFindMatches(source, "one", puml, showMarkers = true)[1]
        val edit = seq3SourceReplaceMatch(source, second, "three")
        assertEquals("one two three", edit.text)
        assertEquals(edit.text.length, edit.selectionStart)
        assertEquals(edit.selectionStart, edit.selectionEnd)
    }

    @Test
    fun replaceAllReplacesEveryMatchAndKeepsTheSelectionInPlace() {
        val source = "one two one three one"
        val matches = seq3SourceFindMatches(source, "one", puml, showMarkers = true)
        val edit = seq3SourceReplaceAll(source, matches, "1", source.indexOf("three"), source.indexOf("three") + 5)
        assertEquals("1 two 1 three 1", edit.text)
        assertEquals("three", edit.text.substring(edit.selectionStart, edit.selectionEnd))
        assertNotNull(seq3SourceReplaceAll(source, emptyList(), "x", 2, 3))
        assertEquals(source, seq3SourceReplaceAll(source, emptyList(), "x", 2, 3).text)
        // The replacement may contain the query: the matches were taken before, so it does not loop.
        assertEquals("oneone two oneone three oneone", seq3SourceReplaceAll(source, matches, "oneone", 0, 0).text)
    }
}
