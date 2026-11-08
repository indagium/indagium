package com.indagium.ui

import com.indagium.diagram3.Seq3Dialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The lines between `activate X` and `deactivate X` sit one level deeper, like a block body. */
class Seq3SourceActivationIndentTest {
    private val puml = Seq3Dialect.PLANTUML
    private val mmd = Seq3Dialect.MERMAID

    private fun lines(vararg text: String) = text.joinToString("\n")

    @Test
    fun linesBetweenActivateAndDeactivateAreOneLevelDeeper() {
        val flat = lines(
            "@startuml", "A -> B: call", "activate B", "B -> C: work", "B --> A: done", "deactivate B", "A -> B: after", "@enduml",
        )
        assertEquals(
            lines(
                "@startuml", "A -> B: call", "activate B", "    B -> C: work", "    B --> A: done", "deactivate B",
                "A -> B: after", "@enduml",
            ),
            seq3IndentSource(flat, puml),
        )
        val mermaid = lines("sequenceDiagram", "A->>B: call", "activate B", "B->>C: work", "deactivate B", "A->>B: after")
        assertEquals(
            lines("sequenceDiagram", "    A->>B: call", "    activate B", "        B->>C: work", "    deactivate B", "    A->>B: after"),
            seq3IndentSource(mermaid, mmd),
        )
    }

    @Test
    fun anActivationInsideABlockNestsDeeperAndAZeroLengthPairStaysLevel() {
        val flat = lines("@startuml", "loop x", "A -> B: m", "activate B", "B -> C: n", "deactivate B", "activate C", "deactivate C", "end", "@enduml")
        assertEquals(
            lines(
                "@startuml", "loop x", "    A -> B: m", "    activate B", "        B -> C: n", "    deactivate B",
                "    activate C", "    deactivate C", "end", "@enduml",
            ),
            seq3IndentSource(flat, puml),
        )
    }

    @Test
    fun interleavedActivationsIndentByTheNumberOfOpenOnesAndDeactivateClosesItsOwnLevel() {
        val flat = lines(
            "@startuml", "activate A", "activate B", "A -> B: x", "deactivate A", "B -> A: y", "deactivate B", "A -> B: z", "@enduml",
        )
        assertEquals(
            lines(
                "@startuml", "activate A", "    activate B", "        A -> B: x", "deactivate A", "    B -> A: y", "deactivate B",
                "A -> B: z", "@enduml",
            ),
            seq3IndentSource(flat, puml),
            "A's level is removed by `deactivate A`; B stays open, so the lines after it are still one level in",
        )
        val kinds = seq3SourceLineLevels(flat, puml).map { it.kind to it.level }
        assertEquals(Seq3SourceLineKind.CLOSER to 0, kinds[4])
        assertEquals(Seq3SourceLineKind.OPENER to 1, kinds[2])
    }

    @Test
    fun theSameNameActivatedTwiceClosesTheInnerOneFirst() {
        val flat = lines("@startuml", "activate B", "activate B", "A -> B: x", "deactivate B", "A -> B: y", "deactivate B", "@enduml")
        assertEquals(
            lines("@startuml", "activate B", "    activate B", "        A -> B: x", "    deactivate B", "    A -> B: y", "deactivate B", "@enduml"),
            seq3IndentSource(flat, puml),
        )
    }

    @Test
    fun aBareOrUnmatchedDeactivateClosesTheInnermostAndWithNoneOpenIsAnOrdinaryLine() {
        assertEquals(
            lines("@startuml", "activate B", "    A -> B: x", "deactivate", "A -> B: y", "@enduml"),
            seq3IndentSource(lines("@startuml", "activate B", "A -> B: x", "deactivate", "A -> B: y", "@enduml"), puml),
        )
        assertEquals(
            lines("@startuml", "activate B", "    A -> B: x", "deactivate Bo", "A -> B: y", "@enduml"),
            seq3IndentSource(lines("@startuml", "activate B", "A -> B: x", "deactivate Bo", "A -> B: y", "@enduml"), puml),
            "a name still being typed closes the innermost activation",
        )
        assertEquals(
            lines("@startuml", "deactivate B", "A -> B: y", "@enduml"),
            seq3IndentSource(lines("@startuml", "deactivate B", "A -> B: y", "@enduml"), puml),
        )
        // A bare `activate` (no name yet) opens nothing.
        assertEquals(
            lines("@startuml", "activate", "A -> B: y", "@enduml"),
            seq3IndentSource(lines("@startuml", "activate", "A -> B: y", "@enduml"), puml),
        )
    }

    @Test
    fun anEndLeavesActivationsOpenedInsideItsBlockOpen() {
        val flat = lines("@startuml", "alt ok", "activate B", "A -> B: x", "end", "A -> B: y", "deactivate B", "A -> B: z", "@enduml")
        assertEquals(
            lines("@startuml", "alt ok", "    activate B", "        A -> B: x", "end", "    A -> B: y", "deactivate B", "A -> B: z", "@enduml"),
            seq3IndentSource(flat, puml),
        )
    }

    @Test
    fun aDividerInsideAnActivationInsideItsBlockStaysAtTheBlocksLevel() {
        val flat = lines("@startuml", "alt ok", "activate B", "A -> B: x", "else bad", "A -> B: y", "deactivate B", "end", "@enduml")
        assertEquals(
            lines(
                "@startuml", "alt ok", "    activate B", "        A -> B: x", "else bad", "        A -> B: y", "    deactivate B",
                "end", "@enduml",
            ),
            seq3IndentSource(flat, puml),
        )
    }

    @Test
    fun theMermaidArrowShorthandDoesNotCountAsAnActivation() {
        val flat = lines("sequenceDiagram", "A->>+B: call", "B->>C: work", "B-->>-A: done", "A->>B: after")
        assertEquals(
            lines("sequenceDiagram", "    A->>+B: call", "    B->>C: work", "    B-->>-A: done", "    A->>B: after"),
            seq3IndentSource(flat, mmd),
        )
        // Mixed with the explicit lines, only the explicit ones indent.
        val mixed = lines("sequenceDiagram", "A->>+B: call", "activate C", "C->>B: x", "deactivate C", "B-->>-A: done")
        assertEquals(
            lines("sequenceDiagram", "    A->>+B: call", "    activate C", "        C->>B: x", "    deactivate C", "    B-->>-A: done"),
            seq3IndentSource(mixed, mmd),
        )
    }

    @Test
    fun indentingGeneratedTextWithActivationsIsIdempotent() {
        val flat = lines("sequenceDiagram", "loop l", "activate A", "activate B", "A->>B: x", "deactivate A", "deactivate B", "end")
        val once = seq3IndentSource(flat, mmd)
        assertEquals(once, seq3IndentSource(once, mmd))
        assertEquals(once, seq3IndentSource(once.lines().joinToString("\n") { "  " + it.trimStart() }, mmd))
    }

    @Test
    fun enterAfterActivateIndentsAndEnterAfterDeactivateGoesBackOut() {
        val afterActivate = lines("@startuml", "A -> B: call", "activate B")
        assertEquals(
            "$afterActivate\n    ",
            requireNotNull(seq3SourceEnter(afterActivate, afterActivate.length, afterActivate.length, puml)).text,
        )
        val mermaid = lines("sequenceDiagram", "    A->>B: call", "    activate B")
        assertEquals("$mermaid\n        ", requireNotNull(seq3SourceEnter(mermaid, mermaid.length, mermaid.length, mmd)).text)

        val inside = lines("@startuml", "activate B", "    B -> C: work", "    deactivate B")
        assertEquals(
            lines("@startuml", "activate B", "    B -> C: work", "deactivate B", ""),
            requireNotNull(seq3SourceEnter(inside, inside.length, inside.length, puml)).text,
            "a deactivate typed at the wrong level is moved first; the next line is back at its level",
        )
        val nested = lines("@startuml", "activate A", "    activate B", "        A -> B: x", "deactivate A")
        assertEquals(
            "$nested\n    ",
            requireNotNull(seq3SourceEnter(nested, nested.length, nested.length, puml)).text,
            "B is still open after `deactivate A`, so the next line is one level in",
        )
    }

    @Test
    fun enterAfterAnActivateWithoutANameKeepsTheLineIndent() {
        val bare = lines("@startuml", "loop x", "    activate ")
        assertEquals("$bare\n    ", requireNotNull(seq3SourceEnter(bare, bare.length, bare.length, puml)).text)
    }

    @Test
    fun typingDeactivateMovesItsLineToTheLevelOfItsActivate() {
        val before = lines("@startuml", "activate B", "    B -> C: work", "    deactivate ")
        val typed = before + "B"
        val edit = requireNotNull(seq3SourceTypedOutdent(before, typed, typed.length, true, puml))
        assertEquals(lines("@startuml", "activate B", "    B -> C: work", "deactivate B"), edit.text)
        assertEquals(edit.text.length, edit.selectionStart)

        val mermaid = lines("sequenceDiagram", "    activate B", "        B->>C: work", "        deactivate B")
        assertEquals(
            lines("sequenceDiagram", "    activate B", "        B->>C: work", "    deactivate B"),
            requireNotNull(seq3SourceAutoOutdent(mermaid, mermaid.length, mmd)).text,
        )
        val alreadyThere = lines("@startuml", "activate B", "    B -> C: work", "deactivate B")
        assertNull(seq3SourceAutoOutdent(alreadyThere, alreadyThere.length, puml))
        val noActivation = lines("@startuml", "    deactivate B")
        assertNull(seq3SourceAutoOutdent(noActivation, noActivation.length, puml), "nothing is open, the line is ordinary")
    }

    @Test
    fun withAutoIndentOffEnterOnlyKeepsTheLeadingWhitespaceAndNothingIsOutdented() {
        val afterActivate = lines("@startuml", "  activate B")
        assertEquals(
            "$afterActivate\n  ",
            requireNotNull(seq3SourceEnter(afterActivate, afterActivate.length, afterActivate.length, puml, autoIndent = false)).text,
        )
        val afterLoop = lines("@startuml", "loop x")
        assertEquals("$afterLoop\n", requireNotNull(seq3SourceEnter(afterLoop, afterLoop.length, afterLoop.length, puml, autoIndent = false)).text)
        val wrong = lines("@startuml", "loop x", "        end")
        assertEquals(
            "$wrong\n        ",
            requireNotNull(seq3SourceEnter(wrong, wrong.length, wrong.length, puml, autoIndent = false)).text,
            "an end at the wrong level is left alone",
        )
        val split = lines("@startuml", "  A -> B: hi")
        val caret = split.indexOf("B:")
        val edit = requireNotNull(seq3SourceEnter(split, caret, caret, puml, autoIndent = false))
        assertEquals(lines("@startuml", "  A -> ", "  B: hi"), edit.text)
        assertEquals(edit.text.indexOf("B: hi"), edit.selectionStart)

        val before = lines("@startuml", "activate B", "    deactivate ")
        assertNull(seq3SourceTypedOutdent(before, before + "B", (before + "B").length, true, puml, autoIndent = false))
        val endBefore = lines("@startuml", "alt a", "    en")
        assertNull(seq3SourceTypedOutdent(endBefore, endBefore + "d", (endBefore + "d").length, true, puml, autoIndent = false))
    }

    @Test
    fun tabAndShiftTabStillIndentWithAutoIndentOff() {
        // seq3SourceTab has no auto-indent switch on purpose: it is the manual way to indent.
        assertEquals("    a", seq3SourceTab("a", 0, 0, shift = false).text)
        assertEquals("a", seq3SourceTab("    a", 4, 4, shift = true).text)
    }

    @Test
    fun aSnippetWithAutoIndentOffKeepsItsOwnShapeAtTheCaretLinesBlanks() {
        val source = lines("@startuml", "  loop outer", "    A -> B: x")
        val caret = source.length
        val inserted = insertSeq3SourceSuggestion(
            source + "\n  al",
            Seq3SourceSuggestionTrigger(caret + 3, caret + 5, "al", ""),
            seq3SourceSuggestions(puml, "alt").first(), puml, autoIndent = false,
        )
        assertEquals(
            lines(
                "@startuml", "  loop outer", "    A -> B: x", "  alt condition", "      From -> To: message label", "  end",
            ),
            inserted.text,
        )
        val on = insertSeq3SourceSuggestion(
            source + "\n  al",
            Seq3SourceSuggestionTrigger(caret + 3, caret + 5, "al", ""),
            seq3SourceSuggestions(puml, "alt").first(), puml, autoIndent = true,
        )
        assertTrue(on.text.contains("\n    alt condition\n        From -> To: message label\n    end"), on.text)
    }

    @Test
    fun aFlatSnippetMapsOffsetsToTheSameText() {
        val snippet = "alt condition\n    \nend"
        val result = seq3SourceFlatSnippet(snippet, "  ")
        assertEquals("  alt condition\n      \n  end", result.text)
        val condition = snippet.indexOf("condition")
        assertEquals("condition", result.text.substring(result.map(condition), result.map(condition) + "condition".length))
    }

    @Test
    fun anActivationSnippetInsertedInsideABlockIsIndentedLikeAnyLine() {
        val source = lines("@startuml", "loop outer", "    A -> B: x")
        val snippet = seq3SourceReindentSnippet("activate B", seq3SourceIndentStateAfter(source, puml))
        assertEquals("    activate B", snippet.text)
        val deeper = seq3SourceIndentStateAfter(lines(source, "    activate B"), puml)
        assertEquals("        A -> B: y", seq3SourceReindentSnippet("A -> B: y", deeper).text)
    }
}
