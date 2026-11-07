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
}
