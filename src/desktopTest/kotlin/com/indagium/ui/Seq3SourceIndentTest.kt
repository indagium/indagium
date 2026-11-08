package com.indagium.ui

import androidx.compose.ui.text.TextRange
import com.indagium.diagram3.Seq3Dialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Seq3SourceIndentTest {
    private val puml = Seq3Dialect.PLANTUML
    private val mmd = Seq3Dialect.MERMAID

    private fun lines(vararg text: String) = text.joinToString("\n")

    @Test
    fun plantUmlNestsBlocksAndPlacesDividersAtTheirOpenersLevel() {
        val flat = lines(
            "@startuml", "title T", "participant \"A\" as A", "loop again", "A -> B: hi", "alt ok", "A -> B: yes",
            "else bad", "A -> B: no", "end", "end", "A -> B: after", "@enduml",
        )
        val expected = lines(
            "@startuml", "title T", "participant \"A\" as A", "loop again", "    A -> B: hi", "    alt ok", "        A -> B: yes",
            "    else bad", "        A -> B: no", "    end", "end", "A -> B: after", "@enduml",
        )
        assertEquals(expected, seq3IndentSource(flat, puml))
    }

    @Test
    fun mermaidBodyStartsOneLevelInAndUsesItsOwnDividers() {
        val flat = lines(
            "sequenceDiagram", "participant A as Alpha", "par one", "A->>B: x", "and two", "A->>B: y", "end",
            "critical lock", "A->>B: z", "option retry", "A->>B: w", "end", "rect rgb(1, 2, 3)", "Note over A: n", "end",
        )
        val expected = lines(
            "sequenceDiagram", "    participant A as Alpha", "    par one", "        A->>B: x", "    and two", "        A->>B: y", "    end",
            "    critical lock", "        A->>B: z", "    option retry", "        A->>B: w", "    end", "    rect rgb(1, 2, 3)",
            "        Note over A: n", "    end",
        )
        assertEquals(expected, seq3IndentSource(flat, mmd))
    }

    @Test
    fun plantUmlParAndCriticalUseElseAsTheirDivider() {
        val flat = lines("@startuml", "par a", "A -> B: x", "else b", "A -> B: y", "end", "@enduml")
        assertEquals(
            lines("@startuml", "par a", "    A -> B: x", "else b", "    A -> B: y", "end", "@enduml"),
            seq3IndentSource(flat, puml),
        )
    }

    @Test
    fun middleWordsOnlyCountInsideTheBlockThatOwnsThem() {
        val flat = lines("@startuml", "loop x", "else", "and", "option", "end", "alt y", "and z", "option q", "end", "@enduml")
        // Inside a loop nothing is a divider, and `and` / `option` mean nothing in an alt.
        assertEquals(
            lines("@startuml", "loop x", "    else", "    and", "    option", "end", "alt y", "    and z", "    option q", "end", "@enduml"),
            seq3IndentSource(flat, puml),
        )
        val kinds = seq3SourceLineLevels(lines("sequenceDiagram", "loop x", "else"), mmd).map { it.kind }
        assertEquals(listOf(Seq3SourceLineKind.HEADER, Seq3SourceLineKind.OPENER, Seq3SourceLineKind.TEXT), kinds)
    }

    @Test
    fun aStrayEndGetsTheBaseLevelAndOpensNothing() {
        assertEquals(lines("@startuml", "end", "A -> B: x", "@enduml"), seq3IndentSource(lines("@startuml", "  end", "    A -> B: x", "@enduml"), puml))
        assertEquals(lines("sequenceDiagram", "    end", "    A->>B: x"), seq3IndentSource(lines("sequenceDiagram", "end", "A->>B: x"), mmd))
    }

    @Test
    fun commentsAndMarkersNeverOpenOrCloseAndTakeTheFollowingLinesIndent() {
        val flat = lines(
            "@startuml", "' loop not a block", "' indagium-seq3:v1 ZnN0", "loop x", "' indagium-seq3:v1 bTE",
            "A -> B: hi", "' indagium-seq3:v1 ZmVu", "end", "@enduml",
        )
        val expected = lines(
            "@startuml", "' loop not a block", "' indagium-seq3:v1 ZnN0", "loop x", "    ' indagium-seq3:v1 bTE",
            "    A -> B: hi", "' indagium-seq3:v1 ZmVu", "end", "@enduml",
        )
        assertEquals(expected, seq3IndentSource(flat, puml))
        val mermaid = lines("sequenceDiagram", "%% end", "loop x", "%% indagium-seq3:v1 bTE", "A->>B: hi", "end")
        assertEquals(
            lines("sequenceDiagram", "    %% end", "    loop x", "        %% indagium-seq3:v1 bTE", "        A->>B: hi", "    end"),
            seq3IndentSource(mermaid, mmd),
        )
    }

    @Test
    fun indentingIsIdempotentAndStableOnIndentedInput() {
        val flat = lines("sequenceDiagram", "loop a", "alt b", "A->>B: x", "else c", "A->>B: y", "end", "end", "")
        val once = seq3IndentSource(flat, mmd)
        assertEquals(once, seq3IndentSource(once, mmd))
        assertTrue(once.endsWith("\n"), "the trailing newline is kept")
        val messy = lines("@startuml", "loop a", "\t\tA -> B: x", "   ", "end", "@enduml")
        val tidy = seq3IndentSource(messy, puml)
        assertEquals(lines("@startuml", "loop a", "    A -> B: x", "", "end", "@enduml"), tidy)
        assertEquals(tidy, seq3IndentSource(tidy, puml))
    }

    @Test
    fun enterIndentsAfterAnOpenerAndAMiddleWordAndKeepsTheLineIndentElsewhere() {
        val afterLoop = lines("@startuml", "loop x")
        val edit = requireNotNull(seq3SourceEnter(afterLoop, afterLoop.length, afterLoop.length, puml))
        assertEquals(afterLoop + "\n    ", edit.text)
        assertEquals(edit.text.length, edit.selectionStart)

        val afterElse = lines("@startuml", "alt a", "    A -> B: x", "    else b")
        assertEquals(
            lines("@startuml", "alt a", "    A -> B: x", "else b", "    "),
            requireNotNull(seq3SourceEnter(afterElse, afterElse.length, afterElse.length, puml)).text,
        )

        val plain = lines("@startuml", "loop x", "    A -> B: x")
        assertEquals("$plain\n    ", requireNotNull(seq3SourceEnter(plain, plain.length, plain.length, puml)).text)

        val header = "sequenceDiagram"
        assertEquals("$header\n    ", requireNotNull(seq3SourceEnter(header, header.length, header.length, mmd)).text)
    }

    @Test
    fun enterMovesTextAfterTheCaretDownWithoutItsOldLeadingSpaces() {
        val source = lines("@startuml", "loop x    A -> B: hi")
        val caret = source.indexOf("    A")
        val edit = requireNotNull(seq3SourceEnter(source, caret, caret, puml))
        assertEquals(lines("@startuml", "loop x", "    A -> B: hi"), edit.text)
        assertEquals(edit.text.indexOf("A -> B"), edit.selectionStart)
    }

    @Test
    fun enterOnAnEndOrDividerAtTheWrongLevelReindentsItFirst() {
        val wrong = lines("@startuml", "loop x", "    A -> B: x", "        end")
        val edit = requireNotNull(seq3SourceEnter(wrong, wrong.length, wrong.length, puml))
        assertEquals(lines("@startuml", "loop x", "    A -> B: x", "end", ""), edit.text)
        val divider = lines("@startuml", "alt a", "    A -> B: x", "else b")
        assertEquals("$divider\n    ", requireNotNull(seq3SourceEnter(divider, divider.length, divider.length, puml)).text)
    }

    @Test
    fun enterAtTheStartOfALineKeepsTheLineWhereItIs() {
        val source = lines("@startuml", "loop x", "    A -> B: hi")
        val caret = source.indexOf("    A")
        val edit = requireNotNull(seq3SourceEnter(source, caret, caret, puml))
        assertEquals(lines("@startuml", "loop x", "", "    A -> B: hi"), edit.text)
        assertEquals(edit.text.indexOf("    A -> B"), edit.selectionStart)
    }

    @Test
    fun enterDoesNotSplitAHiddenMarkerLine() {
        val source = lines("@startuml", "' indagium-seq3:v1 bTE", "A -> B: hi")
        val caret = source.indexOf("seq3") + 2
        assertNull(seq3SourceEnter(source, caret, caret, puml))
        // At the end of the visible line before a marker the marker stays whole.
        val before = lines("@startuml", "A -> B: hi", "' indagium-seq3:v1 bTE", "A -> B: ho")
        val end = before.indexOf("hi") + 2
        val edit = requireNotNull(seq3SourceEnter(before, end, end, puml))
        assertTrue(edit.text.contains("\n' indagium-seq3:v1 bTE\n"), edit.text)
    }

    @Test
    fun typingEndOrADividerMovesItsLineToTheOpenersLevel() {
        val source = lines("@startuml", "alt a", "    A -> B: x", "    end")
        val edit = requireNotNull(seq3SourceAutoOutdent(source, source.length, puml))
        assertEquals(lines("@startuml", "alt a", "    A -> B: x", "end"), edit.text)
        assertEquals(edit.text.length, edit.selectionStart, "the caret stays after the d")

        val divider = lines("@startuml", "alt a", "    A -> B: x", "    else")
        assertEquals(lines("@startuml", "alt a", "    A -> B: x", "else"), requireNotNull(seq3SourceAutoOutdent(divider, divider.length, puml)).text)
        val mermaid = lines("sequenceDiagram", "    loop a", "        A->>B: x", "        end")
        assertEquals(
            lines("sequenceDiagram", "    loop a", "        A->>B: x", "    end"),
            requireNotNull(seq3SourceAutoOutdent(mermaid, mermaid.length, mmd)).text,
        )
        assertNull(seq3SourceAutoOutdent(lines("@startuml", "alt a", "end"), "@startuml\nalt a\nend".length, puml))
        assertNull(seq3SourceAutoOutdent(lines("@startuml", "loop a", "    else"), "@startuml\nloop a\n    else".length, puml), "else means nothing in a loop")
        assertNull(seq3SourceAutoOutdent(lines("@startuml", "loop a", "    endpoint"), "@startuml\nloop a\n    endpoint".length, puml))
    }

    @Test
    fun onlyASmallTypedChangeTriggersTheOutdent() {
        val before = lines("@startuml", "alt a", "    A -> B: x", "    en")
        val typed = before + "d"
        assertEquals(lines("@startuml", "alt a", "    A -> B: x", "end"), seq3SourceTypedOutdent(before, typed, typed.length, true, puml)?.text)
        val replaced = lines("@startuml", "alt a", "    A -> B: x", "    end", "    A -> B: a long pasted line")
        assertNull(seq3SourceTypedOutdent(before, replaced, replaced.length, true, puml))
        assertNull(seq3SourceTypedOutdent(before, typed, typed.length, false, puml))
    }

    @Test
    fun tabWithoutASelectionInsertsFourSpaces() {
        val edit = seq3SourceTab("ab", 1, 1, shift = false)
        assertEquals("a    b", edit.text)
        assertEquals(5, edit.selectionStart)
        assertEquals(5, edit.selectionEnd)
    }

    @Test
    fun tabAndShiftTabMoveEveryTouchedLineAndKeepTheSelectionOnTheSameText() {
        val source = "one\ntwo\nthree"
        val start = source.indexOf("ne")
        val end = source.indexOf("wo") + 1
        val indented = seq3SourceTab(source, start, end, shift = false)
        assertEquals("    one\n    two\nthree", indented.text)
        assertEquals("ne", indented.text.substring(indented.selectionStart, indented.selectionStart + 2))
        assertEquals("    one\n    tw", indented.text.substring(0, indented.selectionEnd))
        val back = seq3SourceTab(indented.text, indented.selectionStart, indented.selectionEnd, shift = true)
        assertEquals(source, back.text)
        assertEquals(start, back.selectionStart)
        assertEquals(end, back.selectionEnd)
    }

    @Test
    fun aSelectionEndingAtALineStartDoesNotTouchThatLineAndShiftTabStopsAtOneLevel() {
        val source = "  a\n\t b\n        c\nd"
        val edit = seq3SourceTab(source, 0, source.indexOf("        c"), shift = true)
        assertEquals("a\n b\n        c\nd", edit.text)
        val deep = seq3SourceTab("        c", 4, 4, shift = true)
        assertEquals("    c", deep.text)
        assertEquals(0, deep.selectionStart)
        val blank = seq3SourceTab("a\n\nb", 0, 4, shift = false)
        assertEquals("    a\n\n    b", blank.text)
    }

    @Test
    fun aReindentedSnippetMapsOffsetsToTheSameText() {
        val context = seq3SourceIndentStateAfter("@startuml\nloop outer", puml)
        val snippet = "alt condition\n    \nend"
        val result = seq3SourceReindentSnippet(snippet, context)
        assertEquals("    alt condition\n        \n    end", result.text)
        val condition = snippet.indexOf("condition")
        assertEquals("condition", result.text.substring(result.map(condition), result.map(condition) + "condition".length))
        val body = snippet.indexOf("\n    \n") + "\n    ".length
        assertEquals(result.text.indexOf("\n    end"), result.map(body), "the placeholder caret lands at the end of the body indent")
    }

    @Test
    fun insertingASnippetIndentsItToTheCaretLine() {
        val source = lines("@startuml", "loop outer", "    A -> B: x", "    al", "end", "@enduml")
        val trigger = requireNotNull(seq3SourceSuggestionTrigger(source, TextRange(source.indexOf("al") + 2)))
        val inserted = insertSeq3SourceSuggestion(source, trigger, seq3SourceSuggestions(puml, "alt").first(), puml)
        assertEquals(
            lines(
                "@startuml", "loop outer", "    A -> B: x", "    alt condition", "        From -> To: message label",
                "    end", "end", "@enduml",
            ),
            inserted.text,
        )
        assertEquals("condition", inserted.text.substring(inserted.selection.start, inserted.selection.end))
    }

    @Test
    fun aHeaderSnippetUsesTheHeaderLevelInBothDialects() {
        val plant = "@startuml\nloop x\n    A -> B: hi\nend\npa\n@enduml"
        val plantAt = plant.indexOf("pa")
        val header = insertSeq3SourceSuggestion(
            plant, Seq3SourceSuggestionTrigger(plantAt, plantAt + 2, "pa", ""),
            seq3SourceSuggestions(puml, "participant").first(), puml,
        )
        assertTrue(header.text.startsWith("@startuml\nparticipant \"\" as \nloop x\n    A -> B: hi\nend\n\n@enduml"), header.text)
        val mermaid = "sequenceDiagram\n    loop x\n        A->>B: hi\n    end\n    pa"
        val mermaidAt = mermaid.lastIndexOf("pa")
        val mermaidHeader = insertSeq3SourceSuggestion(
            mermaid, Seq3SourceSuggestionTrigger(mermaidAt, mermaidAt + 2, "pa", ""),
            seq3SourceSuggestions(mmd, "participant").first(), mmd,
        )
        assertEquals("sequenceDiagram\n    participant  as \n    loop x\n        A->>B: hi\n    end\n", mermaidHeader.text)
    }
}
