package com.indagium.ui

import com.indagium.diagram3.Seq3Dialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An opener records the indent it actually got, and its closer, its dividers and the lines in its
 * body are placed from that, not from the number of levels open. The cases that matter are the
 * crossings: an activation opened before a block and closed inside it, or the other way round.
 */
class Seq3SourceCrossingIndentTest {
    private val puml = Seq3Dialect.PLANTUML
    private val mmd = Seq3Dialect.MERMAID

    private fun lines(vararg text: String) = text.joinToString("\n")

    private fun lines(text: List<String>) = text.joinToString("\n")

    /** `level` levels of four spaces, then [text]. */
    private fun at(level: Int, text: String) = " ".repeat(level * 4) + text

    /** The user's Mermaid source as the editor indented it before the fix, with the lines in it unchanged. */
    private fun oldMermaid() = lines(
        "sequenceDiagram",
        "    title NewDiagram",
        "    actor Alias as Name",
        "    participant Alias_2 as Name",
        "    participant Alias_3 as Name3",
        "    Alias->>Alias_2: [#35;1] message label",
        "    activate Alias",
        "        activate Alias_2",
        "            Alias-->>Alias_2: [#35;2] aadsd",
        "    deactivate Alias",
        "        opt condition",
        "            Alias_3->>Alias_2: [#35;3] message label",
        "    deactivate Alias_2",
        "        activate Alias_2",
        "        deactivate Alias_2",
        "        Note over Alias_2,Alias_3: note text",
        "    end",
    )

    /** What the rule makes of it: every closer at its opener, `opt` one level deeper than `activate Alias_2`. */
    private fun newMermaid() = lines(
        "sequenceDiagram",
        at(1, "title NewDiagram"),
        at(1, "actor Alias as Name"),
        at(1, "participant Alias_2 as Name"),
        at(1, "participant Alias_3 as Name3"),
        at(1, "Alias->>Alias_2: [#35;1] message label"),
        at(1, "activate Alias"),
        at(2, "activate Alias_2"),
        at(3, "Alias-->>Alias_2: [#35;2] aadsd"),
        at(1, "deactivate Alias"),
        at(3, "opt condition"),
        at(4, "Alias_3->>Alias_2: [#35;3] message label"),
        at(2, "deactivate Alias_2"),
        at(4, "activate Alias_2"),
        at(4, "deactivate Alias_2"),
        at(4, "Note over Alias_2,Alias_3: note text"),
        at(3, "end"),
    )

    private fun newPlantUml() = lines(
        "@startuml",
        "title NewDiagram",
        "actor \"Name\" as Alias",
        "participant \"Name\" as Alias_2",
        "participant \"Name3\" as Alias_3",
        "Alias -> Alias_2: [#1] message label",
        "activate Alias",
        at(1, "activate Alias_2"),
        at(2, "Alias --> Alias_2: [#2] aadsd"),
        "deactivate Alias",
        at(2, "opt condition"),
        at(3, "Alias_3 -> Alias_2: [#3] message label"),
        at(1, "deactivate Alias_2"),
        at(3, "activate Alias_2"),
        at(3, "deactivate Alias_2"),
        at(3, "note over Alias_2, Alias_3: note text"),
        at(2, "end"),
        "@enduml",
    )

    private fun flat(source: String) = source.split('\n').joinToString("\n") { it.trim() }

    @Test
    fun theUserMermaidExampleLinesUpEveryCloserWithItsOpener() {
        val indented = seq3IndentSource(oldMermaid(), mmd)
        assertEquals(newMermaid(), indented)
        val rows = indented.split('\n')

        fun indentOf(text: String, occurrence: Int = 0) =
            rows.filter { it.trim() == text }[occurrence].let { it.length - it.trimStart().length }
        assertEquals(indentOf("activate Alias_2"), indentOf("deactivate Alias_2"), "the first activation and its deactivate")
        assertEquals(indentOf("activate Alias_2", 1), indentOf("deactivate Alias_2", 1), "the second one")
        assertEquals(indentOf("opt condition"), indentOf("end"))
        assertEquals(indentOf("activate Alias"), indentOf("deactivate Alias"))
        assertEquals(newMermaid(), seq3IndentSource(flat(oldMermaid()), mmd), "the same from flat text")
        assertEquals(newMermaid(), seq3IndentSource(newMermaid(), mmd), "idempotent")
    }

    @Test
    fun theSameSourceInPlantUmlStartsAtColumnZero() {
        assertEquals(newPlantUml(), seq3IndentSource(flat(newPlantUml()), puml))
        assertEquals(newPlantUml(), seq3IndentSource(newPlantUml(), puml), "idempotent")
    }

    @Test
    fun aBlockOpenedInsideAnActivationThatClosesAfterTheBlockKeepsItsCloserAtItsOpener() {
        val flat = lines("@startuml", "activate A", "opt x", "A -> B: m", "deactivate A", "B -> A: n", "end", "A -> B: after", "@enduml")
        assertEquals(
            lines("@startuml", "activate A", at(1, "opt x"), at(2, "A -> B: m"), "deactivate A", at(2, "B -> A: n"), at(1, "end"), "A -> B: after", "@enduml"),
            seq3IndentSource(flat, puml),
            "`deactivate A` is at A's indent, the lines after it still inside the opt, `end` at the opt's own indent",
        )
        val levels = seq3SourceLineLevels(flat, puml)
        assertEquals(1, levels[2].level, "opt")
        assertEquals(0, levels[4].level, "deactivate A")
        assertEquals(1, levels[6].level, "end")
        assertEquals(0, levels[7].level, "nothing is open after the end")
    }

    @Test
    fun nestedBlocksWithDividersKeepTheirOwnIndentsAcrossAnActivation() {
        val flat = lines(
            "sequenceDiagram", "activate A", "alt a", "loop b", "A->>B: x", "deactivate A", "A->>B: y", "end", "else c", "par d",
            "A->>B: z", "and e", "A->>B: w", "end", "end", "A->>B: after",
        )
        assertEquals(
            lines(
                "sequenceDiagram",
                at(1, "activate A"),
                at(2, "alt a"),
                at(3, "loop b"),
                at(4, "A->>B: x"),
                at(1, "deactivate A"),
                at(4, "A->>B: y"),
                at(3, "end"),
                at(2, "else c"),
                at(3, "par d"),
                at(4, "A->>B: z"),
                at(3, "and e"),
                at(4, "A->>B: w"),
                at(3, "end"),
                at(2, "end"),
                at(1, "A->>B: after"),
            ),
            seq3IndentSource(flat, mmd),
        )
    }

    @Test
    fun anActivationOpenedInsideABlockAndClosedAfterItKeepsItsCloserAtItsOpener() {
        val flat = lines("@startuml", "alt ok", "activate B", "A -> B: x", "end", "A -> B: y", "deactivate B", "A -> B: z", "@enduml")
        val result = seq3IndentSource(flat, puml)
        assertEquals(
            lines("@startuml", "alt ok", at(1, "activate B"), at(2, "A -> B: x"), "end", at(2, "A -> B: y"), at(1, "deactivate B"), "A -> B: z", "@enduml"),
            result,
        )
        assertEquals(result, seq3IndentSource(result, puml))
    }

    @Test
    fun typingAnEndOrADeactivateMovesItToItsOpenersRecordedIndent() {
        // `deactivate Alias_2` of the user's example, typed one letter at a time on a wrongly indented line.
        val before = lines(
            "@startuml", "activate Alias", at(1, "activate Alias_2"), "deactivate Alias", at(2, "opt condition"),
            at(3, "Alias_3 -> Alias_2: m"), at(3, "deactivate Alias_"),
        )
        val typed = before + "2"
        val edit = requireNotNull(seq3SourceTypedOutdent(before, typed, typed.length, true, puml))
        assertEquals(lines(before.split('\n').dropLast(1) + at(1, "deactivate Alias_2")), edit.text)
        assertEquals(edit.text.length, edit.selectionStart)

        // The `end` of that opt goes to the opt's own indent (2), not to the count of levels still open.
        val beforeEnd = lines(
            "@startuml", "activate Alias", at(1, "activate Alias_2"), "deactivate Alias", at(2, "opt condition"),
            at(3, "Alias_3 -> Alias_2: m"), at(1, "deactivate Alias_2"), at(3, "en"),
        )
        val typedEnd = beforeEnd + "d"
        val endEdit = requireNotNull(seq3SourceTypedOutdent(beforeEnd, typedEnd, typedEnd.length, true, puml))
        assertEquals(lines(beforeEnd.split('\n').dropLast(1) + at(2, "end")), endEdit.text)

        // A closer already at its opener's indent is left alone.
        val settled = lines("@startuml", "activate A", at(1, "opt x"), at(2, "m"), "deactivate A", at(1, "end"))
        assertNull(seq3SourceAutoOutdent(settled, settled.length, puml))
    }

    @Test
    fun enterAfterEachOpenerGoesOneLevelBelowThatOpenersIndent() {
        // Context: A is open at 0 and B at 1; the opener under test is typed after `deactivate A`, so it sits at 2.
        val prefix = lines("sequenceDiagram", at(1, "activate A"), at(2, "activate B"), at(1, "deactivate A"))
        listOf("opt x", "loop x", "alt x", "par x", "critical x", "break x", "rect rgb(1, 2, 3)", "activate C").forEach { opener ->
            val source = lines(prefix, at(3, opener))
            val edit = requireNotNull(seq3SourceEnter(source, source.length, source.length, mmd)) { opener }
            assertEquals(source + "\n" + at(4, ""), edit.text, opener)
        }
        val plain = lines("@startuml", "activate A", at(1, "activate B"), "deactivate A", at(2, "group g"))
        assertEquals(plain + "\n" + at(3, ""), requireNotNull(seq3SourceEnter(plain, plain.length, plain.length, puml)).text)

        // After a closer the next line sits one level below the innermost opener still open.
        val afterClose = lines("@startuml", "activate A", at(1, "activate B"), at(2, "m"), "deactivate A")
        assertEquals(afterClose + "\n" + at(2, ""), requireNotNull(seq3SourceEnter(afterClose, afterClose.length, afterClose.length, puml)).text)
        // A divider: the body after `else` is one level below its block, even across an activation.
        val divider = lines("@startuml", "activate A", at(1, "alt ok"), at(2, "m"), at(1, "else"))
        assertEquals(divider + "\n" + at(2, ""), requireNotNull(seq3SourceEnter(divider, divider.length, divider.length, puml)).text)
    }

    @Test
    fun withAutoIndentOffNothingIsIndentedOrMovedAcrossACrossing() {
        val source = lines("@startuml", "activate A", "opt x", "deactivate A")
        assertEquals(
            source + "\n",
            requireNotNull(seq3SourceEnter(source, source.length, source.length, puml, autoIndent = false)).text,
            "the new line only repeats the leading blanks of the line it came from",
        )
        val before = lines("@startuml", "activate A", "opt x", "        deactivate ")
        assertNull(seq3SourceTypedOutdent(before, before + "A", (before + "A").length, true, puml, autoIndent = false))
    }

    @Test
    fun aSnippetInsertedAfterACrossingIsIndentedFromTheInnermostOpenOpener() {
        val source = lines("@startuml", "activate A", at(1, "opt x"), at(2, "m"), "deactivate A")
        val context = seq3SourceIndentStateAfter(source, puml)
        assertEquals(at(2, "B -> A: y"), seq3SourceReindentSnippet("B -> A: y", context).text)
        // A block snippet: its `end` sits at the block's own indent, its body one deeper.
        assertEquals(
            lines(at(2, "alt condition"), at(3, "From -> To: message label"), at(2, "end")),
            seq3SourceReindentSnippet("alt condition\nFrom -> To: message label\nend", context).text,
        )
        // The context is not changed by the insertion.
        assertEquals(2, context.currentLevel)
    }

    @Test
    fun indentationIsIdempotentAndUnchangedForSourcesWithoutCrossings() {
        val nested = lines(
            "@startuml", "loop x", "activate B", "alt y", "A -> B: m", "else z", "A -> B: n", "end", "deactivate B", "end", "A -> B: after",
            "@enduml",
        )
        val once = seq3IndentSource(nested, puml)
        assertEquals(
            lines(
                "@startuml", "loop x", at(1, "activate B"), at(2, "alt y"), at(3, "A -> B: m"), at(2, "else z"), at(3, "A -> B: n"),
                at(2, "end"), at(1, "deactivate B"), "end", "A -> B: after", "@enduml",
            ),
            once,
        )
        assertEquals(once, seq3IndentSource(once, puml))
    }
}
