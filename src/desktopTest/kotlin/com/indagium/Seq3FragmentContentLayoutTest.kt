package com.indagium

import com.indagium.diagram3.Seq3ActivationBar
import com.indagium.diagram3.Seq3Box
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3FontRole
import com.indagium.diagram3.Seq3Layout
import com.indagium.diagram3.Seq3LayoutOptions
import com.indagium.diagram3.Seq3RasterTheme
import com.indagium.diagram3.Seq3SourceImportResult
import com.indagium.diagram3.Seq3TextMetrics
import com.indagium.diagram3.importSeq3Source
import com.indagium.diagram3.layoutSeq3
import com.indagium.diagram3.renderSeq3
import com.indagium.diagram3.toSource
import com.indagium.ui.seq3FragmentHeaderAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A fragment's box encloses everything written inside it in the source (messages, notes, nested
 * boxes), and its borders and label strip stay clear of activation bars. Built from the diagram a
 * user hit in the real app, in both dialects.
 */
class Seq3FragmentContentLayoutTest {
    private class StubMetrics : Seq3TextMetrics {
        override fun width(role: Seq3FontRole, text: String): Double = text.length * 7.0

        override fun lineHeight(role: Seq3FontRole): Double = 16.0
    }

    private val mermaid = """
sequenceDiagram
    title NewDiagram
    actor Alias as Name
    participant Alias_2 as Name
    participant Alias_3 as Name3
    Alias->>Alias_2: [#35;1] message label
    activate Alias
        activate Alias_2
            Alias-->>Alias_2: [#35;2] aadsd
    deactivate Alias
        opt condition
            Alias_3->>Alias_2: [#35;3] message label
    deactivate Alias_2
        activate Alias_2
        deactivate Alias_2
        Note over Alias_2,Alias_3: note text
    end
    """.trimIndent()

    private val plantUml = """
@startuml
title NewDiagram
actor "Name" as Alias
participant "Name" as Alias_2
participant "Name3" as Alias_3
Alias -> Alias_2: [#1] message label
activate Alias
activate Alias_2
Alias --> Alias_2: [#2] aadsd
deactivate Alias
opt condition
Alias_3 -> Alias_2: [#3] message label
deactivate Alias_2
activate Alias_2
deactivate Alias_2
note over Alias_2, Alias_3: note text
end
@enduml
    """.trimIndent()

    private fun source(dialect: Seq3Dialect) = if (dialect == Seq3Dialect.MERMAID) mermaid else plantUml

    private fun import(source: String, dialect: Seq3Dialect, existing: Seq3Document = Seq3Document()): Seq3Document {
        val result = importSeq3Source(source, dialect, existing)
        return assertIs<Seq3SourceImportResult.Success>(result, "$dialect: $result").document
    }

    private fun layout(document: Seq3Document) = layoutSeq3(document, Seq3LayoutOptions(StubMetrics()))

    private fun Seq3Box.right() = x + width

    private fun Seq3Box.bottom() = y + height

    private fun Seq3Box.inside(outer: Seq3Box) =
        x >= outer.x && right() <= outer.right() && y >= outer.y && bottom() <= outer.bottom()

    private fun Seq3Box.intersects(other: Seq3Box) =
        x < other.right() && right() > other.x && y < other.bottom() && bottom() > other.y

    private fun barsOf(layout: Seq3Layout, lifelineId: String): List<Seq3ActivationBar> =
        layout.activations.filter { it.lifelineId == lifelineId }

    @Test
    fun theNoteWrittenInsideTheOptIsDrawnInsideItsBox() {
        Seq3Dialect.entries.forEach { dialect ->
            val document = import(source(dialect), dialect)
            val fragment = document.fragments.single()
            val note = document.notes.single()
            assertEquals(fragment.messageIds, note.messageIds, "the note follows the opt's last message: $dialect")
            val laid = layout(document)
            val box = laid.fragments.single().box
            val noteBox = laid.notes.single().box
            assertTrue(noteBox.inside(box), "$dialect: note $noteBox must lie inside the opt $box")
        }
    }

    @Test
    fun theOptBoxLeftEdgeClearsTheActivationBarsOfItsLeftLifeline() {
        Seq3Dialect.entries.forEach { dialect ->
            val laid = layout(import(source(dialect), dialect))
            val box = laid.fragments.single().box
            val bars = barsOf(laid, "Alias_2").filter { it.box.y < box.bottom() && it.box.bottom() > box.y }
            assertTrue(bars.isNotEmpty(), "$dialect: Alias_2 has bars reaching the opt")
            val outerLeft = bars.minOf { it.box.x }
            assertTrue(box.x <= outerLeft - 10.0, "$dialect: opt left ${box.x} must be 10 left of the bars' outer side $outerLeft")
        }
    }

    @Test
    fun theLabelStripIsClearOfEveryActivationBar() {
        Seq3Dialect.entries.forEach { dialect ->
            val laid = layout(import(source(dialect), dialect))
            val fragment = laid.fragments.single()
            val header = fragment.headerBox
            assertFalse(fragment.headerOverBar, dialect.name)
            assertTrue(header.inside(fragment.box), "$dialect: strip $header stays in the box ${fragment.box}")
            laid.activations.forEach { bar ->
                assertFalse(header.intersects(bar.box), "$dialect: label strip $header must not touch bar ${bar.box}")
            }
            // The first bar crosses the top border, so the strip really did have to move off the corner.
            assertTrue(header.x > fragment.box.x, "$dialect: the strip starts right of the crossing bar")
        }
    }

    @Test
    fun theHeaderHitTestFollowsTheMovedStripNotTheWholeTopOfTheBox() {
        val laid = layout(import(mermaid, Seq3Dialect.MERMAID))
        val fragment = laid.fragments.single()
        val header = fragment.headerBox
        assertEquals(fragment.fragmentId, seq3FragmentHeaderAt(laid, header.x + 5, header.y + 5)?.fragmentId)
        assertNull(seq3FragmentHeaderAt(laid, fragment.box.x + 2, fragment.box.y + 5), "the corner left of the strip is not the header")
    }

    @Test
    fun rowsAfterTheOptStayBelowItsBorderEvenThoughTheNoteHangsUnderItsLastRow() {
        val source = mermaid.replace("    end", "    end\n    Alias->>Alias_3: after")
        val laid = layout(import(source, Seq3Dialect.MERMAID))
        val box = laid.fragments.single().box
        val after = laid.rows.last()
        assertTrue(after.y - 21.0 >= box.bottom(), "the row after the opt (y=${after.y}) must start below the box bottom ${box.bottom()}")
    }

    @Test
    fun aNoteInsideTheOptRoundTripsThroughBothEmittersInsideTheOpt() {
        Seq3Dialect.entries.forEach { dialect ->
            val document = import(source(dialect), dialect)
            val text = document.toSource(dialect)
            val lines = text.lines().map { it.trim() }.filterNot { it.startsWith("%%") || it.startsWith("'") }
            val open = lines.indexOfFirst { it.startsWith("opt ") }
            val close = lines.indexOfLast { it == "end" }
            val note = lines.indexOfFirst { it.lowercase().startsWith("note over") }
            assertTrue(note in (open + 1) until close, "$dialect: note line $note must sit between opt $open and end $close:\n$text")
            val again = import(text, dialect, document)
            assertEquals(document, again, "$dialect: re-importing the emitted text changes nothing")
        }
    }

    @Test
    fun aNoteAnchoredMidFragmentStaysInsideWithoutPushingRowsApart() {
        val withNote = """
            sequenceDiagram
                participant A
                participant B
                opt guard
                    A->>B: one
                    Note over A,B: remark
                    B->>A: two
                end
        """.trimIndent()
        val plain = withNote.replace("        Note over A,B: remark\n", "")
        val laid = layout(import(withNote, Seq3Dialect.MERMAID))
        val box = laid.fragments.single().box
        assertTrue(laid.notes.single().box.inside(box), "the note is enclosed: ${laid.notes.single().box} in $box")
        assertEquals(layout(import(plain, Seq3Dialect.MERMAID)).rows.map { it.y }, laid.rows.map { it.y }, "a mid-fragment note adds no pitch")
    }

    @Test
    fun nestedFragmentsAndDividersClearBarsOnBothEdgeLifelines() {
        val nested = """
            sequenceDiagram
                participant A
                participant B
                participant C
                A->>C: one
                activate A
                activate C
                opt outer
                    alt first
                        A->>C: two
                    else second
                        A->>C: three
                    end
                    A->>C: four
                end
                deactivate A
                deactivate C
        """.trimIndent()
        val laid = layout(import(nested, Seq3Dialect.MERMAID))
        assertEquals(2, laid.fragments.size)
        assertEquals(2, laid.activations.size)
        assertClearOfBars(laid)
        val outer = laid.fragments.first { it.depth == 0 }
        val inner = laid.fragments.first { it.depth == 1 }
        assertTrue(outer.box.x <= inner.box.x - 10.0 && outer.box.right() >= inner.box.right() + 10.0, "outer reaches 10 beyond inner")
        assertTrue(inner.box.inside(outer.box), "the alt box is inside the opt box")
        val divider = inner.dividers.single()
        assertNotNull(divider.guardX, "the 'second' guard has to move right of the bar on the left edge lifeline")
    }

    @Test
    fun aBarOnTheRightEdgeLifelineIsClearedAndKeepsTheStripOffTheControls() {
        val right = """
            sequenceDiagram
                participant A
                participant B
                A->>B: one
                activate B
                activate B
                activate B
                opt guard
                    A->>B: two
                end
                deactivate B
                deactivate B
                deactivate B
        """.trimIndent()
        val laid = layout(import(right, Seq3Dialect.MERMAID))
        assertClearOfBars(laid)
        val box = laid.fragments.single().box
        val outerRight = laid.activations.filter { it.lifelineId == "B" }.maxOf { it.box.right() }
        assertTrue(box.right() >= outerRight + 10.0, "right border ${box.right()} is 10 beyond the bars' outer side $outerRight")
        assertTrue(laid.width >= box.right(), "the diagram is wide enough for the pushed border")
    }

    @Test
    fun diagramsWithoutBarsOrNotesKeepTheirOriginalFragmentGeometry() {
        val plain = """
            sequenceDiagram
                participant A
                participant B
                opt guard
                    A->>B: one
                end
        """.trimIndent()
        val laid = layout(import(plain, Seq3Dialect.MERMAID))
        val fragment = laid.fragments.single()
        assertNull(fragment.header)
        assertFalse(fragment.headerOverBar)
        val row = laid.rows.single()
        assertEquals(minOf((row as com.indagium.diagram3.Seq3ArrowRow).fromX, row.toX) - 10.0, fragment.box.x)
        assertEquals(row.y + 21.0, fragment.box.bottom())
    }

    @Test
    fun theRasterUsesTheSameGeometry() {
        val laid = layout(import(mermaid, Seq3Dialect.MERMAID))
        val theme = Seq3RasterTheme.DEFAULT_LIGHT
        val rendered = renderSeq3(laid, theme, scale = 1f)
        val image = rendered.image
        assertEquals(Math.ceil(laid.width).toInt(), image.width, "raster size comes from the layout")
        val box = laid.fragments.single().box
        val noteBox = laid.notes.single().box
        assertTrue(noteBox.bottom() < box.bottom(), "note ends above the border the raster draws")
        // The raster draws the opt's bottom border at the layout's bottom edge, below the note.
        val bottomPixel = image.getRGB((box.x + box.width / 2).toInt(), box.bottom().toInt())
        assertNotEquals(theme.background, bottomPixel, "a border pixel sits on the layout's bottom edge")
        val belowBorder = image.getRGB((box.x + box.width / 2).toInt(), box.bottom().toInt() + 6)
        assertEquals(theme.background, belowBorder, "nothing but the diagram background below the box")
        // The label strip is clear of bars, so the corner of the box carries only the box's own paint.
        val strip = laid.fragments.single().headerBox
        laid.activations.forEach { assertFalse(strip.intersects(it.box)) }
    }

    private fun assertClearOfBars(laid: Seq3Layout) {
        laid.fragments.forEach { fragment ->
            val box = fragment.box
            laid.activations.filter { it.box.y < box.bottom() && it.box.bottom() > box.y }.forEach { bar ->
                listOf(box.x, box.right()).forEach { border ->
                    assertFalse(
                        border > bar.box.x - 10.0 && border < bar.box.right() + 10.0,
                        "${fragment.label}: border $border is within 10 of bar ${bar.box}",
                    )
                }
            }
            val header = fragment.headerBox
            assertFalse(fragment.headerOverBar, fragment.label)
            laid.activations.forEach { assertFalse(header.intersects(it.box), "${fragment.label}: strip $header vs ${it.box}") }
            fragment.dividers.forEach { divider ->
                val x = divider.guardX ?: (box.x + 3.0)
                val width = divider.guard.length * 7.0
                laid.activations.filter { it.box.y < divider.y && it.box.bottom() > divider.y - 13.0 }.forEach { bar ->
                    assertFalse(x < bar.box.right() && x + width > bar.box.x, "${fragment.label}: guard at $x vs ${bar.box}")
                }
            }
        }
    }
}
