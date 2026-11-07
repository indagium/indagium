package com.indagium.ui

import androidx.compose.ui.text.TextRange
import com.indagium.diagram3.Seq3Dialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Seq3SourceSuggestionsTest {
    @Test
    fun slashTriggerTracksAWordAtTheCaretAndIgnoresEmbeddedSlashes() {
        val source = "title /part diagram"
        val caret = source.indexOf("part") + 2
        val trigger = seq3SourceSuggestionTrigger(source, TextRange(caret))

        assertEquals("part", trigger?.query)
        assertEquals(source.indexOf('/'), trigger?.start)
        assertEquals(source.indexOf("part") + 4, trigger?.end)
        assertNull(seq3SourceSuggestionTrigger("path/to/file", TextRange("path/to".length)))
        assertNull(seq3SourceSuggestionTrigger("/par", TextRange(1, 3)))
    }

    @Test
    fun keywordTriggerSuggestsAtLineStartWithoutASlash() {
        val source = "@startuml\npart"
        val trigger = requireNotNull(seq3SourceSuggestionTrigger(source, TextRange(source.length)))
        assertEquals("part", trigger.query)
        assertEquals("", trigger.prefix)
        assertEquals(source.length - 4, trigger.start)
        assertEquals(source.length, trigger.end)
    }

    @Test
    fun suggestionsFilterByKeywordAndUseDialectSpecificSyntax() {
        val plant = seq3SourceSuggestions(Seq3Dialect.PLANTUML, "pa")
        val mermaid = seq3SourceSuggestions(Seq3Dialect.MERMAID, "pa")

        assertEquals(listOf("participant", "par"), plant.map { it.keyword })
        assertTrue(plant.first().syntax.contains("participant \"Name\" as Alias"))
        assertTrue(mermaid.first().syntax.contains("participant Alias as Name"))
        assertEquals("Note over A: note text", seq3SourceSuggestions(Seq3Dialect.MERMAID, "note").single().syntax)
        assertEquals("note over A: note text", seq3SourceSuggestions(Seq3Dialect.PLANTUML, "note").single().syntax)
    }

    @Test
    fun acceptingSuggestionReplacesSlashWordAndPreservesTextAroundIt() {
        val source = "@startuml\n/par\n@enduml"
        val caret = source.indexOf("/par") + 4
        val trigger = requireNotNull(seq3SourceSuggestionTrigger(source, TextRange(caret)))
        val suggestion = requireNotNull(seq3SourceSuggestions(Seq3Dialect.PLANTUML, trigger.query).firstOrNull { it.keyword == "par" })
        val result = insertSeq3SourceSuggestion(source, trigger, suggestion)

        assertEquals("@startuml\npar condition\n    From -> To: message label\nend\n@enduml", result.text)
        assertEquals("condition", result.text.substring(result.selection.start, result.selection.end))
        assertEquals(source.indexOf("/par") + "par ".length, result.selection.start)
    }

    @Test
    fun aBareWordNeedsTwoLettersButASlashDoesNot() {
        assertNull(seq3SourceSuggestionTrigger("@startuml\na", TextRange("@startuml\na".length)))
        assertEquals("pa", seq3SourceSuggestionTrigger("pa", TextRange(2))?.query)
        assertEquals("", seq3SourceSuggestionTrigger("/", TextRange(1))?.query)
        assertEquals("a", seq3SourceSuggestionTrigger("/a", TextRange(2))?.query)
    }

    @Test
    fun aKeywordTypedInFullWithNothingToExpandShowsNoList() {
        val source = "@startuml\nalt ok\n    a\nend"
        val trigger = requireNotNull(seq3SourceSuggestionTrigger(source, TextRange(source.length)))
        assertEquals("end", trigger.query)
        assertEquals(emptyList(), seq3SourceActiveSuggestions(source, trigger, Seq3Dialect.PLANTUML, emptyList()))

        val partial = "@startuml\nen"
        val partialTrigger = requireNotNull(seq3SourceSuggestionTrigger(partial, TextRange(partial.length)))
        assertEquals(listOf("end"), seq3SourceActiveSuggestions(partial, partialTrigger, Seq3Dialect.PLANTUML, emptyList()).map { it.keyword })

        // A keyword that expands (`loop` becomes a whole block) keeps its list; so does a slash trigger.
        val loop = "@startuml\nloop"
        val loopTrigger = requireNotNull(seq3SourceSuggestionTrigger(loop, TextRange(loop.length)))
        assertEquals(listOf("loop"), seq3SourceActiveSuggestions(loop, loopTrigger, Seq3Dialect.PLANTUML, emptyList()).map { it.keyword })
        val slashEnd = "@startuml\n/end"
        val slashTrigger = requireNotNull(seq3SourceSuggestionTrigger(slashEnd, TextRange(slashEnd.length)))
        assertEquals(listOf("end"), seq3SourceActiveSuggestions(slashEnd, slashTrigger, Seq3Dialect.PLANTUML, emptyList()).map { it.keyword })
    }

    @Test
    fun aDividerIsOfferedOnlyInsideTheBlockThatOwnsIt() {
        fun offered(source: String, dialect: Seq3Dialect, query: String): List<String> {
            val trigger = requireNotNull(seq3SourceSuggestionTrigger("$source$query", TextRange(source.length + query.length)))
            return seq3SourceActiveSuggestions("$source$query", trigger, dialect, emptyList()).map { it.keyword }
        }
        val plant = Seq3Dialect.PLANTUML
        val mermaid = Seq3Dialect.MERMAID
        assertEquals(listOf("else"), offered("@startuml\nalt ok\n    A -> B: x\n    ", plant, "el"))
        assertEquals(listOf("else"), offered("@startuml\npar x\n    ", plant, "el"))
        assertEquals(listOf("else"), offered("@startuml\ncritical x\n    ", plant, "el"))
        assertEquals(emptyList(), offered("@startuml\nloop x\n    ", plant, "el"))
        assertEquals(emptyList(), offered("@startuml\n", plant, "el"))
        assertEquals(emptyList(), offered("@startuml\nalt ok\nend\n", plant, "el"))
        // `else` belongs to the innermost block: a loop inside an alt cannot be divided.
        assertEquals(emptyList(), offered("@startuml\nalt ok\n    loop y\n        ", plant, "el"))

        assertEquals(listOf("else"), offered("sequenceDiagram\n    alt ok\n        ", mermaid, "el"))
        assertEquals(emptyList(), offered("sequenceDiagram\n    alt ok\n        ", mermaid, "an"))
        assertEquals(listOf("and"), offered("sequenceDiagram\n    par x\n        ", mermaid, "an"))
        assertEquals(emptyList(), offered("sequenceDiagram\n    par x\n        ", mermaid, "el"))
        assertEquals(listOf("option"), offered("sequenceDiagram\n    critical x\n        ", mermaid, "opt").filter { it == "option" })
        assertEquals(listOf("opt"), offered("sequenceDiagram\n    loop x\n        ", mermaid, "opt"))
    }

    @Test
    fun dividerWordFollowsTheImportersOwnershipRules() {
        assertEquals("else", seq3SourceDividerWord("alt", Seq3Dialect.MERMAID))
        assertEquals("and", seq3SourceDividerWord("par", Seq3Dialect.MERMAID))
        assertEquals("option", seq3SourceDividerWord("critical", Seq3Dialect.MERMAID))
        listOf("alt", "par", "critical").forEach { assertEquals("else", seq3SourceDividerWord(it, Seq3Dialect.PLANTUML)) }
        listOf("loop", "opt", "break", "group", "rect", null).forEach {
            assertNull(seq3SourceDividerWord(it, Seq3Dialect.PLANTUML))
            assertNull(seq3SourceDividerWord(it, Seq3Dialect.MERMAID))
        }
    }

    @Test
    fun arrowEntriesWriteTheDialectsOwnArrowsAndFillInTheFirstTwoParticipants() {
        val plant = seq3SourceSuggestions(Seq3Dialect.PLANTUML, "", listOf("Web", "Api")).associateBy { it.keyword }
        assertEquals("Web -> Api: message label", plant.getValue("call").insertion)
        assertEquals("Web --> Api: message label", plant.getValue("return").insertion)
        assertEquals("Web ->> Api: message label", plant.getValue("async").insertion)
        val mermaid = seq3SourceSuggestions(Seq3Dialect.MERMAID, "", listOf("Web", "Api")).associateBy { it.keyword }
        assertEquals("Web->>Api: message label", mermaid.getValue("call").insertion)
        assertEquals("Web-->>Api: message label", mermaid.getValue("return").insertion)
        assertEquals("Web-)Api: message label", mermaid.getValue("async").insertion)

        val call = plant.getValue("call")
        assertEquals(listOf("Web", "Api", "message label"), call.fields.map { call.insertion.substring(it.start, it.end) })
        assertEquals(listOf(true, true, false), call.fields.map { it.endpoint })
        val noParticipants = seq3SourceSuggestions(Seq3Dialect.PLANTUML, "call").single()
        assertEquals("From -> To: message label", noParticipants.insertion)
    }

    @Test
    fun everySuggestionIsSomethingTheImporterAcceptsAndHasAPictogram() {
        assertTrue(seq3SourceSuggestions(Seq3Dialect.PLANTUML, "").none { it.keyword == "left" }, "note left of is not importable yet")
        val pictograms = listOf(Seq3Dialect.PLANTUML, Seq3Dialect.MERMAID).flatMap { dialect ->
            listOf("alt", "par", "critical").flatMap { block ->
                seq3SourceSuggestions(dialect, "", listOf("A", "B"), block).map { it.glyph }
            }
        }.toSet()
        assertEquals(Seq3SourceGlyph.entries.toSet(), pictograms, "each pictogram is used by some suggestion")
        // The authorable lifecycle statements are `create` (Mermaid declares the lifeline with it) and `destroy`.
        assertEquals("create participant ", seq3SourceSuggestions(Seq3Dialect.MERMAID, "create").single().insertion)
        assertEquals("create ", seq3SourceSuggestions(Seq3Dialect.PLANTUML, "create").single().insertion)
        assertEquals("destroy ", seq3SourceSuggestions(Seq3Dialect.PLANTUML, "destroy").single().insertion)
    }

    @Test
    fun hiddenMarkerLinesAreTakenOutOfTheOffsetThatPlacesTheList() {
        val hidden = listOf(Seq3SourceRange(10, 30), Seq3SourceRange(40, 50))
        assertEquals(5, seq3SourceVisibleOffset(5, hidden))
        assertEquals(10, seq3SourceVisibleOffset(10, hidden))
        assertEquals(10, seq3SourceVisibleOffset(20, hidden), "inside a hidden range maps to its start")
        assertEquals(10, seq3SourceVisibleOffset(30, hidden))
        assertEquals(15, seq3SourceVisibleOffset(35, hidden))
        assertEquals(30, seq3SourceVisibleOffset(60, hidden))
        assertEquals(7, seq3SourceVisibleOffset(7, emptyList()))
    }

    @Test
    fun listOpensBelowTheCaretLineOrAboveItWhenThereIsNoRoom() {
        fun placed(caretTop: Float, caretBottom: Float, listHeight: Float, areaHeight: Float): Pair<Boolean, Float> {
            val slot = seq3SourceSuggestionSlot(caretTop, caretBottom, listHeight, areaHeight, 2f)
            val height = minOf(listHeight, slot.room)
            return slot.below to seq3SourceSuggestionTop(slot, caretTop, caretBottom, height, 2f)
        }
        // Area 300 tall, list 100 tall, caret line 40..58: right under the line.
        assertEquals(true to 60f, placed(40f, 58f, 100f, 300f))
        // Caret line near the bottom: the list ends right above it.
        assertEquals(false to 98f, placed(200f, 218f, 100f, 230f))
        // Fits neither way: the roomier side, cut to the room there (140 below, 118 above).
        assertEquals(true to 60f, placed(40f, 58f, 150f, 200f))
        assertEquals(140f, seq3SourceSuggestionSlot(40f, 58f, 150f, 200f, 2f).room)
        assertEquals(false to 0f, placed(120f, 138f, 150f, 200f))
        assertEquals(118f, seq3SourceSuggestionSlot(120f, 138f, 150f, 200f, 2f).room)
    }
}
