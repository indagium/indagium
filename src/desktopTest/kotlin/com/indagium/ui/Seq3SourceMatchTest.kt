package com.indagium.ui

import com.indagium.diagram3.Seq3Dialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The opener / closer pairs the source editor highlights for the keyword under the caret. */
class Seq3SourceMatchTest {
    private val puml = Seq3Dialect.PLANTUML
    private val mmd = Seq3Dialect.MERMAID

    private fun lines(vararg text: String) = text.joinToString("\n")

    /** One mark as (line index, marked text, unbalanced). */
    private data class Shown(val line: Int, val text: String, val unbalanced: Boolean = false)

    /** [marked] holds one `|` where the caret is; returns the marks for it. */
    private fun marks(marked: String, dialect: Seq3Dialect = puml): List<Shown> {
        val caret = marked.indexOf('|')
        require(caret >= 0) { "no caret in the test source" }
        val source = marked.removeRange(caret, caret + 1)
        return seq3SourceConstructMarks(source, caret, caret, dialect).map {
            Shown(source.substring(0, it.range.start).count { c -> c == '\n' }, source.substring(it.range.start, it.range.endExclusive), it.unbalanced)
        }
    }

    @Test
    fun everyBlockOpenerPairsWithItsEndFromEitherEnd() {
        listOf("loop", "alt", "opt", "par", "critical", "break", "group").forEach { word ->
            val expected = listOf(Shown(1, word), Shown(3, "end"))
            assertEquals(expected, marks(lines("@startuml", "$word| x", "A -> B: m", "end", "@enduml")), "$word, caret after the keyword")
            assertEquals(expected, marks(lines("@startuml", "|$word x", "A -> B: m", "end", "@enduml")), "$word, caret before the keyword")
            assertEquals(expected, marks(lines("@startuml", "$word x", "A -> B: m", "en|d", "@enduml")), "end of $word, caret inside")
            assertEquals(expected, marks(lines("@startuml", "$word x", "A -> B: m", "|end", "@enduml")), "end of $word, caret before")
            assertEquals(expected, marks(lines("@startuml", "$word x", "A -> B: m", "end|", "@enduml")), "end of $word, caret after")
        }
        assertEquals(
            listOf(Shown(1, "rect"), Shown(3, "end")),
            marks(lines("sequenceDiagram", "re|ct rgb(1, 2, 3)", "A->>B: m", "end"), mmd),
        )
    }

    @Test
    fun theCaretMustBeOnTheKeywordToken() {
        // One character past the keyword (on the space), in the label and on an indented line's blanks: nothing.
        assertEquals(emptyList(), marks(lines("@startuml", "loop| x", "end").replace("loop|", "loop |")))
        assertEquals(emptyList(), marks(lines("@startuml", "loop x|", "end")))
        assertEquals(emptyList(), marks(lines("@startuml", "|    loop x", "end")))
        assertEquals(emptyList(), marks(lines("@startuml", "A -> B: lo|op", "end")))
        // An indented keyword is found from inside it and from both edges.
        assertEquals(listOf(Shown(1, "loop"), Shown(2, "end")), marks(lines("@startuml", "    |loop x", "    end")))
        assertEquals(listOf(Shown(1, "loop"), Shown(2, "end")), marks(lines("@startuml", "    loop| x", "    end")))
        // A longer word that merely starts with a keyword is not one.
        assertEquals(emptyList(), marks(lines("@startuml", "loop|back -> B: x")))
        assertEquals(emptyList(), marks(lines("@startuml", "loop x", "endpo|int -> B: x", "end")))
        // Case does not matter for blocks, as in the indentation.
        assertEquals(listOf(Shown(1, "LOOP"), Shown(2, "END")), marks(lines("@startuml", "LO|OP x", "END")))
    }

    @Test
    fun aSelectionShowsNoMarks() {
        val source = lines("@startuml", "loop x", "A -> B: m", "end")
        assertEquals(emptyList(), seq3SourceConstructMarks(source, 10, 14, puml))
        assertEquals(2, seq3SourceConstructMarks(source, 10, 10, puml).size)
    }

    @Test
    fun theDividersOfABlockAreMarkedWithItsOpenerAndEndWhicheverIsUnderTheCaret() {
        val alt = lines("@startuml", "alt a", "A -> B: x", "else b", "A -> B: y", "else c", "A -> B: z", "end")
        val all = listOf(Shown(1, "alt"), Shown(3, "else"), Shown(5, "else"), Shown(7, "end"))
        assertEquals(all, marks(alt.replaceFirst("alt a", "al|t a")))
        assertEquals(all, marks(alt.replaceFirst("else b", "el|se b")))
        assertEquals(all, marks(alt.replaceFirst("else c", "else| c")))
        assertEquals(all, marks(alt.replaceFirst("end", "e|nd")))

        val par = lines("sequenceDiagram", "par one", "A->>B: x", "and two", "A->>B: y", "end")
        assertEquals(
            listOf(Shown(1, "par"), Shown(3, "and"), Shown(5, "end")),
            marks(par.replaceFirst("and two", "|and two"), mmd),
        )
        val critical = lines("sequenceDiagram", "critical c", "A->>B: x", "option o", "A->>B: y", "end")
        assertEquals(
            listOf(Shown(1, "critical"), Shown(3, "option"), Shown(5, "end")),
            marks(critical.replaceFirst("option o", "opt|ion o"), mmd),
        )
        // PlantUML writes every divider `else`.
        val plantPar = lines("@startuml", "par a", "A -> B: x", "else b", "A -> B: y", "end")
        assertEquals(
            listOf(Shown(1, "par"), Shown(3, "else"), Shown(5, "end")),
            marks(plantPar.replaceFirst("par a", "pa|r a")),
        )
    }

    @Test
    fun aDividerThatMeansNothingHereIsNotMarked() {
        assertEquals(emptyList(), marks(lines("@startuml", "loop x", "el|se", "end")), "else in a loop")
        assertEquals(emptyList(), marks(lines("@startuml", "alt x", "A -> B: m", "an|d", "end")), "and in an alt")
        assertEquals(emptyList(), marks(lines("@startuml", "el|se")), "else outside every block")
    }

    @Test
    fun nestedBlocksMarkOnlyTheirOwnPair() {
        val source = lines("@startuml", "loop outer", "opt inner", "A -> B: m", "end", "alt a", "else b", "end", "end")
        assertEquals(listOf(Shown(2, "opt"), Shown(4, "end")), marks(source.replaceFirst("opt inner", "o|pt inner")))
        assertEquals(listOf(Shown(2, "opt"), Shown(4, "end")), marks(source.replaceFirst("\nend\nalt", "\ne|nd\nalt")))
        assertEquals(listOf(Shown(1, "loop"), Shown(8, "end")), marks(source.replaceFirst("loop outer", "|loop outer")))
        assertEquals(listOf(Shown(1, "loop"), Shown(8, "end")), marks(source.substring(0, source.lastIndexOf("end")) + "end|"))
        assertEquals(listOf(Shown(5, "alt"), Shown(6, "else"), Shown(7, "end")), marks(source.replaceFirst("else b", "else| b")))
    }

    @Test
    fun anActivationPairsWithItsDeactivateFromAnywhereOnEitherLine() {
        val source = lines("@startuml", "activate Alias_2", "Alias -> Alias_2: m", "deactivate Alias_2", "@enduml")
        val pair = listOf(Shown(1, "activate Alias_2"), Shown(3, "deactivate Alias_2"))
        assertEquals(pair, marks(source.replaceFirst("activate Alias_2", "|activate Alias_2")))
        assertEquals(pair, marks(source.replaceFirst("activate Alias_2", "activate Alias_2|")))
        assertEquals(pair, marks(source.replaceFirst("activate Alias_2", "activate Ali|as_2")))
        assertEquals(pair, marks(source.replaceFirst("\ndeactivate Alias_2", "\ndeac|tivate Alias_2")))
        assertEquals(pair, marks(source.replaceFirst("\ndeactivate Alias_2", "\ndeactivate Alias_2|")))
    }

    @Test
    fun anActivationAcrossABlockPairsWithItsDeactivateInsideIt() {
        // The user's example: Alias_2 is activated before the opt and deactivated inside it.
        val source = lines(
            "sequenceDiagram", "actor Alias as Name", "activate Alias", "activate Alias_2", "Alias-->>Alias_2: m", "deactivate Alias",
            "opt condition", "Alias_3->>Alias_2: m", "deactivate Alias_2", "activate Alias_2", "deactivate Alias_2", "Note over Alias_2: n", "end",
        )
        assertEquals(
            listOf(Shown(3, "activate Alias_2"), Shown(8, "deactivate Alias_2")),
            marks(source.replaceFirst("\nactivate Alias_2\nAlias-->>", "\nactivate Ali|as_2\nAlias-->>"), mmd),
        )
        assertEquals(
            listOf(Shown(3, "activate Alias_2"), Shown(8, "deactivate Alias_2")),
            marks(source.replaceFirst("deactivate Alias_2\nactivate", "|deactivate Alias_2\nactivate"), mmd),
        )
        // The second activate / deactivate pair inside the opt.
        assertEquals(
            listOf(Shown(9, "activate Alias_2"), Shown(10, "deactivate Alias_2")),
            marks(source.replaceFirst("\nactivate Alias_2\ndeactivate", "\nact|ivate Alias_2\ndeactivate"), mmd),
        )
        assertEquals(listOf(Shown(6, "opt"), Shown(12, "end")), marks(source.replaceFirst("opt condition", "opt| condition"), mmd))
        assertEquals(listOf(Shown(2, "activate Alias"), Shown(5, "deactivate Alias")), marks(source.replaceFirst("deactivate Alias\n", "deactivate Alias|\n"), mmd))
    }

    @Test
    fun interleavedActivationsPairByNameNotByNesting() {
        val source = lines("@startuml", "activate A", "activate B", "A -> B: x", "deactivate A", "B -> A: y", "deactivate B")
        assertEquals(
            listOf(Shown(1, "activate A"), Shown(4, "deactivate A")),
            marks(source.replaceFirst("activate A\nactivate B", "activate A|\nactivate B")),
        )
        assertEquals(
            listOf(Shown(2, "activate B"), Shown(6, "deactivate B")),
            marks(source.replaceFirst("deactivate B", "deactivate B|")),
        )
        // The same name activated twice: the inner one closes first.
        val twice = lines("@startuml", "activate B", "activate B", "deactivate B", "deactivate B")
        assertEquals(listOf(Shown(2, "activate B"), Shown(3, "deactivate B")), marks(twice.replaceFirst("deactivate B\ndeactivate B", "|deactivate B\ndeactivate B")))
        assertEquals(listOf(Shown(1, "activate B"), Shown(4, "deactivate B")), marks(twice.substring(0, twice.length - 1) + "|" + "B"))
    }

    @Test
    fun aBareOrMisspelledDeactivateClosesTheInnermostActivationLikeTheIndentation() {
        val source = lines("@startuml", "activate B", "A -> B: x", "deactivate Bo", "@enduml")
        assertEquals(
            listOf(Shown(1, "activate B"), Shown(3, "deactivate Bo")),
            marks(source.replaceFirst("deactivate Bo", "dea|ctivate Bo")),
        )
        assertEquals(
            listOf(Shown(1, "activate B"), Shown(3, "deactivate")),
            marks(lines("@startuml", "activate B", "A -> B: x", "deac|tivate", "@enduml")),
        )
    }

    @Test
    fun unbalancedConstructsAreMarkedAsSuch() {
        assertEquals(listOf(Shown(1, "loop", true)), marks(lines("@startuml", "lo|op x", "A -> B: m", "@enduml")), "a loop without an end")
        assertEquals(listOf(Shown(2, "end", true)), marks(lines("@startuml", "A -> B: m", "e|nd", "@enduml")), "an end without a block")
        assertEquals(
            listOf(Shown(1, "deactivate B", true)),
            marks(lines("@startuml", "dea|ctivate B", "@enduml")),
            "a deactivate without an activate",
        )
        assertEquals(listOf(Shown(1, "deactivate", true)), marks(lines("@startuml", "deactivate|", "@enduml")), "a bare deactivate")
        assertEquals(
            listOf(Shown(1, "activate B", true)),
            marks(lines("@startuml", "ac|tivate B", "A -> B: m", "@enduml")),
            "an activate never deactivated",
        )
        // An alt that lost its end: the opener and its dividers all say so.
        assertEquals(
            listOf(Shown(1, "alt", true), Shown(3, "else", true)),
            marks(lines("@startuml", "alt a", "A -> B: x", "el|se b", "A -> B: y", "@enduml")),
        )
        // The inner block is balanced, the outer one is not: only the pair under the caret is looked at.
        val source = lines("@startuml", "loop outer", "opt inner", "end")
        assertEquals(listOf(Shown(2, "opt"), Shown(3, "end")), marks(source.replaceFirst("opt inner", "o|pt inner")))
        assertEquals(listOf(Shown(1, "loop", true)), marks(source.replaceFirst("loop outer", "lo|op outer")))
        // A second `end` has no block left.
        assertEquals(listOf(Shown(3, "end", true)), marks(lines("@startuml", "loop x", "end", "en|d")))
        // A bare `activate` opens nothing, so there is nothing to mark.
        assertEquals(emptyList(), marks(lines("@startuml", "activ|ate", "A -> B: m")))
    }

    @Test
    fun theMarksAgreeWithTheLevelsTheIndentationGivesTheSameSource() {
        val source = lines(
            "sequenceDiagram", "activate Alias", "activate Alias_2", "Alias-->>Alias_2: m", "deactivate Alias", "opt condition",
            "Alias_3->>Alias_2: m", "deactivate Alias_2", "alt a", "else b", "end", "end",
        )
        val indented = seq3IndentSource(source, mmd)
        val rowIndent = indented.split('\n').map { it.length - it.trimStart().length }
        val rawLines = indented.split('\n')
        rawLines.forEachIndexed { index, row ->
            val trimmed = row.trim()
            val first = trimmed.takeWhile { !it.isWhitespace() }
            if (first !in setOf("activate", "opt", "alt", "end", "else", "deactivate")) return@forEachIndexed
            val caret = rawLines.take(index).sumOf { it.length + 1 } + rowIndent[index]
            val marked = seq3SourceConstructMarks(indented, caret, caret, mmd)
            assertTrue(marked.isNotEmpty(), "line $index: $trimmed")
            val markedLines = marked.map { indented.substring(0, it.range.start).count { c -> c == '\n' } }
            assertTrue(index in markedLines, "line $index is in its own group")
            // Every marked line of a group sits at the same indent (dividers, closers and the opener line up).
            assertEquals(1, markedLines.map { rowIndent[it] }.toSet().size, "line $index $trimmed: $markedLines")
        }
    }

    @Test
    fun marksAreRawOffsetsThatMapToTheShownTextWhenIdentityMarkerLinesAreHidden() {
        val marker = "' indagium-seq3:v1 abc123"
        val source = lines(
            "@startuml", marker, "loop x", marker.replace("abc123", "def456"), "A -> B: m", marker.replace("abc123", "0a1b2c"), "end", "@enduml",
        )
        val hidden = seq3SourceMarkerDeletionRanges(source, puml)
        val shown = StringBuilder(source).also { builder -> hidden.asReversed().forEach { builder.delete(it.start, it.endExclusive) } }.toString()
        assertTrue(!shown.contains("indagium-seq3"), shown)
        val caret = source.indexOf("loop") + 2
        val marked = seq3SourceConstructMarks(source, caret, caret, puml)
        assertEquals(listOf("loop", "end"), marked.map { source.substring(it.range.start, it.range.endExclusive) })
        marked.forEach {
            val start = seq3SourceVisibleOffset(it.range.start, hidden)
            val end = seq3SourceVisibleOffset(it.range.endExclusive, hidden)
            assertEquals(source.substring(it.range.start, it.range.endExclusive), shown.substring(start, end))
        }
        // The caret on the closer finds the opener through the hidden lines too.
        val onEnd = source.indexOf("\nend") + 2
        assertEquals(listOf("loop", "end"), seq3SourceConstructMarks(source, onEnd, onEnd, puml).map { source.substring(it.range.start, it.range.endExclusive) })
        // A marker comment is never a keyword.
        assertEquals(emptyList(), seq3SourceConstructMarks(source, source.indexOf("indagium") + 3, source.indexOf("indagium") + 3, puml))
    }
}
