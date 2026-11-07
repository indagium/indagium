package com.indagium.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.indagium.diagram3.Seq3Dialect
import com.indagium.model.ThemePreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Seq3SourceTokensTest {
    private val plant = Seq3Dialect.PLANTUML
    private val mermaid = Seq3Dialect.MERMAID

    /** The text of each token paired with its role, in order. */
    private fun roles(source: String, dialect: Seq3Dialect): List<Pair<String, Seq3SourceRole>> =
        tokenizeSeq3Source(source, dialect).map { source.substring(it.start, it.end) to it.role }

    @Test
    fun plantUmlMessageSplitsNamesArrowAndLabel() {
        assertEquals(
            listOf(
                "Client" to Seq3SourceRole.NAME,
                "->" to Seq3SourceRole.ARROW,
                "Server" to Seq3SourceRole.NAME,
                "GET /items" to Seq3SourceRole.LABEL,
            ),
            roles("Client -> Server: GET /items", plant),
        )
        assertEquals(
            listOf("-->" to Seq3SourceRole.ARROW, "->>" to Seq3SourceRole.ARROW),
            roles("A --> B: x\nA ->> B: y", plant).filter { it.second == Seq3SourceRole.ARROW },
        )
    }

    @Test
    fun mermaidArrowsIncludeTheActivationSign() {
        assertEquals(
            listOf(
                "A" to Seq3SourceRole.NAME,
                "->>" to Seq3SourceRole.ARROW,
                "+" to Seq3SourceRole.ARROW,
                "B" to Seq3SourceRole.NAME,
                "hi" to Seq3SourceRole.LABEL,
            ),
            roles("A->>+B: hi", mermaid),
        )
        assertEquals(listOf("A", "-)", "B", "async"), roles("A-)B: async", mermaid).map { it.first })
        // Only Mermaid has the sign: a PlantUML name may start with a dash.
        assertEquals(
            listOf("A" to Seq3SourceRole.NAME, "->" to Seq3SourceRole.ARROW, "-B" to Seq3SourceRole.NAME, "x" to Seq3SourceRole.LABEL),
            roles("A -> -B: x", plant),
        )
    }

    @Test
    fun declarationsColourKeywordNamesQuotesAndAs() {
        assertEquals(
            listOf(
                "participant" to Seq3SourceRole.KEYWORD,
                "\"Order Service\"" to Seq3SourceRole.QUOTED,
                "as" to Seq3SourceRole.KEYWORD,
                "Orders" to Seq3SourceRole.NAME,
            ),
            roles("participant \"Order Service\" as Orders", plant),
        )
        assertEquals(
            listOf(
                "actor" to Seq3SourceRole.KEYWORD,
                "U" to Seq3SourceRole.NAME,
                "as" to Seq3SourceRole.KEYWORD,
                "User" to Seq3SourceRole.NAME,
            ),
            roles("actor U as User", mermaid),
        )
        assertEquals(
            listOf("create" to Seq3SourceRole.KEYWORD, "participant" to Seq3SourceRole.KEYWORD, "C" to Seq3SourceRole.NAME),
            roles("create participant C", mermaid),
        )
    }

    @Test
    fun blockWordsShareOneRoleAndNeverFallBelowEnd() {
        val source = "loop forever\nalt ok\nelse bad\nend\npar x\nand y\nend\ncritical z\noption w\nend"
        val blockWords = roles(source, mermaid).filter { it.second == Seq3SourceRole.BLOCK }.map { it.first }
        assertEquals(listOf("loop", "alt", "else", "end", "par", "and", "end", "critical", "option", "end"), blockWords)
        assertEquals(
            listOf("forever", "ok", "bad", "y", "z", "w", "x").sorted(),
            roles(source, mermaid).filter { it.second == Seq3SourceRole.LABEL }.map { it.first }.sorted(),
            "the condition after a block word is a label",
        )
    }

    @Test
    fun aParticipantCalledLikeABlockWordStillReadsAsAMessage() {
        assertEquals(
            listOf("Loop" to Seq3SourceRole.NAME, "->" to Seq3SourceRole.ARROW, "B" to Seq3SourceRole.NAME, "x" to Seq3SourceRole.LABEL),
            roles("Loop -> B: x", plant),
        )
    }

    @Test
    fun keywordsNotesTitlesAndHeadersGetTheirRoles() {
        assertEquals(listOf("@startuml" to Seq3SourceRole.KEYWORD, "@enduml" to Seq3SourceRole.KEYWORD), roles("@startuml\n@enduml", plant))
        assertEquals(listOf("sequenceDiagram" to Seq3SourceRole.KEYWORD), roles("sequenceDiagram", mermaid))
        assertEquals(
            listOf("title" to Seq3SourceRole.KEYWORD, "Checkout flow" to Seq3SourceRole.LABEL),
            roles("title Checkout flow", plant),
        )
        assertEquals(
            listOf(
                "note" to Seq3SourceRole.KEYWORD, "over" to Seq3SourceRole.KEYWORD,
                "A" to Seq3SourceRole.NAME, "B" to Seq3SourceRole.NAME, "careful" to Seq3SourceRole.LABEL,
            ),
            roles("note over A,B: careful", plant),
        )
        assertEquals(
            listOf("Note" to Seq3SourceRole.KEYWORD, "right of" to Seq3SourceRole.KEYWORD, "A" to Seq3SourceRole.NAME, "hi" to Seq3SourceRole.LABEL),
            roles("Note right of A: hi", mermaid),
        )
        assertEquals(
            listOf("activate" to Seq3SourceRole.KEYWORD, "Server" to Seq3SourceRole.NAME),
            roles("    activate Server", plant),
        )
    }

    @Test
    fun commentsAndMarkerLinesAreCommentsPerDialect() {
        assertEquals(listOf("' a note to self" to Seq3SourceRole.COMMENT), roles("  ' a note to self", plant))
        assertEquals(listOf("%% a note to self" to Seq3SourceRole.COMMENT), roles("%% a note to self", mermaid))
        // The other dialect's comment mark is not a comment here.
        assertTrue(roles("%% text", plant).none { it.second == Seq3SourceRole.COMMENT })
        assertEquals(
            listOf("' indagium-seq3:v1 m-1" to Seq3SourceRole.COMMENT),
            roles("' indagium-seq3:v1 m-1", plant),
        )
    }

    @Test
    fun tokensStayInsideTheirLineAndNeverOverlap() {
        val source = "@startuml\nparticipant \"X\" as X\n  X -> Y: hi: there\nloop 3\n  Y --> X: ok\nend\n@enduml"
        val tokens = tokenizeSeq3Source(source, plant)
        tokens.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start, "overlap: $a $b") }
        tokens.forEach { assertTrue(it.start < it.end && it.end <= source.length, "bad range: $it") }
        tokens.forEach { assertTrue('\n' !in source.substring(it.start, it.end), "token spans lines: $it") }
        assertEquals("hi: there", tokens.first { it.role == Seq3SourceRole.LABEL }.let { source.substring(it.start, it.end) })
    }

    @Test
    fun highlightAppliesOneColourSpanPerToken() {
        val palette = seq3SourcePalette(themeColors(ThemePreset.LIGHT))
        val source = "A -> B: hi"
        val styled = highlightSeq3Source(source, plant, palette)
        assertEquals(source, styled.text)
        val byRole = styled.spanStyles.associate { source.substring(it.start, it.end) to it.item.color }
        assertEquals(palette.arrow, byRole["->"])
        assertEquals(palette.label, byRole["hi"])
        assertEquals(palette.name, byRole["A"])
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun everyRoleReadsOnTheEditorPanelOfEveryPreset() {
        ThemePreset.entries.forEach { preset ->
            val colors = themeColors(preset)
            val palette = seq3SourcePalette(colors)
            Seq3SourceRole.entries.forEach { role ->
                val ratio = contrast(palette.colorOf(role), colors.p2)
                assertTrue(ratio >= 3.0, "$preset $role on the editor panel: $ratio")
            }
            assertTrue(contrast(palette.text, colors.p2) >= 4.5, "$preset plain text on the editor panel")
        }
    }

    @Test
    fun rolesAreToldApartInBothThemeFamilies() {
        listOf(ThemePreset.LIGHT, ThemePreset.DARK_GITHUB).forEach { preset ->
            val palette = seq3SourcePalette(themeColors(preset))
            val distinct = Seq3SourceRole.entries.map { palette.colorOf(it) }.distinct()
            assertTrue(distinct.size >= 6, "$preset should keep most roles visibly different, got ${distinct.size}")
            assertTrue(palette.colorOf(Seq3SourceRole.KEYWORD) != palette.colorOf(Seq3SourceRole.BLOCK), "$preset keyword vs block")
            assertTrue(palette.colorOf(Seq3SourceRole.LABEL) != palette.colorOf(Seq3SourceRole.COMMENT), "$preset label vs comment")
        }
    }
}
