package com.indagium

import com.indagium.diagram3.Seq3ArrowRow
import com.indagium.diagram3.Seq3Box
import com.indagium.diagram3.Seq3Dialect
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3FontRole
import com.indagium.diagram3.Seq3Layout
import com.indagium.diagram3.Seq3LayoutOptions
import com.indagium.diagram3.Seq3SourceImportResult
import com.indagium.diagram3.Seq3TextMetrics
import com.indagium.diagram3.importSeq3Source
import com.indagium.diagram3.layoutSeq3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The vertical room around a fragment box: the row that opens a fragment leaves space above it for
 * the label strip (the actual 28-unit control) plus a clearance below the previous row, fragments
 * opening on one row stack their strips, and the row after a fragment starts below its border. Built
 * from the diagram a user hit in the real app, where the opt's top edge sat at the height of the
 * message before it and that message's arrow was drawn inside the strip.
 */
class Seq3FragmentSpacingTest {
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
    Alias->>Alias_2: one
    activate Alias
        activate Alias_2
            Alias-->>Alias_2: two
    deactivate Alias
            opt condition
                Alias_3->>Alias_2: three
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
Alias -> Alias_2: one
activate Alias
activate Alias_2
Alias --> Alias_2: two
deactivate Alias
opt condition
Alias_3 -> Alias_2: three
deactivate Alias_2
note over Alias_2, Alias_3: note text
end
@enduml
    """.trimIndent()

    private fun source(dialect: Seq3Dialect) = if (dialect == Seq3Dialect.MERMAID) mermaid else plantUml

    private fun layout(source: String, dialect: Seq3Dialect = Seq3Dialect.MERMAID): Seq3Layout {
        val result = importSeq3Source(source, dialect, Seq3Document())
        val document = assertIs<Seq3SourceImportResult.Success>(result, "$dialect: $result").document
        return layoutSeq3(document, Seq3LayoutOptions(StubMetrics()))
    }

    private fun Seq3Box.right() = x + width

    private fun Seq3Box.bottom() = y + height

    private fun Seq3Box.intersects(other: Seq3Box) =
        x < other.right() && right() > other.x && y < other.bottom() && bottom() > other.y

    private val clearance = 6.0

    @Test
    fun theOptBoxTopIsBelowThePreviousMessageAndItsLabelInBothDialects() {
        Seq3Dialect.entries.forEach { dialect ->
            val laid = layout(source(dialect), dialect)
            val two = laid.rows[1] as Seq3ArrowRow
            val fragment = laid.fragments.single()
            val box = fragment.box
            assertTrue(box.y >= two.y + clearance, "$dialect: opt top ${box.y} must clear message two's arrow at ${two.y} by $clearance")
            assertTrue(box.y >= two.labelBox.bottom() + clearance, "$dialect: opt top ${box.y} must clear the label ${two.labelBox}")
            assertFalse(fragment.headerBox.intersects(two.labelBox), "$dialect: label strip ${fragment.headerBox} vs label ${two.labelBox}")
            val arrowLine = Seq3Box(minOf(two.fromX, two.toX), two.y - 4.0, kotlin.math.abs(two.toX - two.fromX), 8.0)
            assertFalse(fragment.headerBox.intersects(arrowLine), "$dialect: label strip ${fragment.headerBox} vs arrow $arrowLine")
            val three = laid.rows[2]
            assertTrue(three.y - 21.0 >= box.y + 28.0, "$dialect: the first row sits below the whole strip")
        }
    }

    @Test
    fun aFragmentRightAfterAMessageWithNoDirectiveInBetweenIsClearToo() {
        val laid = layout(
            """
            sequenceDiagram
                participant A
                participant B
                A->>B: one
                opt guard
                    B->>A: two
                end
            """.trimIndent(),
        )
        val one = laid.rows[0] as Seq3ArrowRow
        val fragment = laid.fragments.single()
        assertEquals(one.y + 21.0 + clearance, fragment.box.y, "the box top is one clearance below message one's row")
        assertFalse(fragment.headerBox.intersects(one.labelBox))
        assertEquals(fragment.box.y + 28.0 + 21.0, laid.rows[1].y, "the strip and half a row sit above the first row")
    }

    @Test
    fun nestedFragmentsOpeningOnTheSameRowStackTheirHeaders() {
        val laid = layout(
            """
            sequenceDiagram
                participant A
                participant B
                A->>B: one
                opt outer
                    alt inner
                        B->>A: two
                    else other
                        A->>B: three
                    end
                end
            """.trimIndent(),
        )
        val one = laid.rows[0] as Seq3ArrowRow
        val outer = laid.fragments.single { it.depth == 0 }
        val inner = laid.fragments.single { it.depth == 1 }
        assertTrue(outer.box.y >= one.y + clearance, "the outer top clears message one: ${outer.box.y} vs ${one.y}")
        assertFalse(outer.headerBox.intersects(inner.headerBox), "strips ${outer.headerBox} and ${inner.headerBox} must not overlap")
        assertEquals(outer.box.y + 28.0, inner.box.y, "the inner box starts right under the outer strip")
        assertEquals(inner.box.y + 28.0 + 21.0, laid.rows[1].y, "the first row sits under the inner strip")
        assertTrue(inner.box.y >= outer.headerBox.bottom(), "the inner box is below the outer strip")
    }

    @Test
    fun nestedFragmentsOpeningOnTheFirstRowStayBelowTheHeaderBand() {
        val laid = layout(
            """
            sequenceDiagram
                participant A
                participant B
                opt outer
                    alt inner
                        A->>B: one
                    else other
                        B->>A: two
                    end
                end
            """.trimIndent(),
        )
        val outer = laid.fragments.single { it.depth == 0 }
        val inner = laid.fragments.single { it.depth == 1 }
        assertTrue(outer.box.y >= laid.headerBandBottom, "outer top ${outer.box.y} vs header band ${laid.headerBandBottom}")
        assertEquals(outer.box.y + 28.0, inner.box.y)
        assertFalse(outer.headerBox.intersects(inner.headerBox))
    }

    @Test
    fun theRowAfterAFragmentStartsBelowItsBorderWithAndWithoutATrailingNote() {
        val body = """
            sequenceDiagram
                participant A
                participant B
                A->>B: one
                opt guard
                    B->>A: two
                %NOTE%
                end
                A->>B: after
        """.trimIndent()
        val plain = layout(body.replace("%NOTE%\n", "").replace("    %NOTE%", ""))
        val plainBox = plain.fragments.single().box
        val plainAfter = plain.rows.last()
        assertEquals(plainBox.bottom(), plainAfter.y - 21.0, "no note: the row after the box already starts at its border")
        val withNote = layout(body.replace("%NOTE%", "Note over A,B: remark"))
        val noteBox = withNote.notes.single().box
        val box = withNote.fragments.single().box
        val after = withNote.rows.last()
        assertTrue(noteBox.bottom() < box.bottom(), "the note ends above the border")
        assertTrue(after.y - 21.0 >= box.bottom(), "with note: the row after (y=${after.y}) starts below the border ${box.bottom()}")
    }

    @Test
    fun aNoteUnderTheRowBeforeAFragmentStaysOutOfItsLabelStrip() {
        val laid = layout(
            """
            sequenceDiagram
                participant A
                participant B
                A->>B: one
                Note over A,B: remark
                opt guard
                    B->>A: two
                end
            """.trimIndent(),
        )
        val note = laid.notes.single().box
        val fragment = laid.fragments.single()
        assertTrue(fragment.box.y >= note.bottom() + clearance, "opt top ${fragment.box.y} vs note bottom ${note.bottom()}")
    }

    @Test
    fun spacingThatWasAlreadyRightKeepsItsGeometry() {
        // No fragment at all: rows keep the plain pitch.
        val plain = layout(
            """
            sequenceDiagram
                participant A
                participant B
                A->>B: one
                B->>A: two
                A->>B: three
            """.trimIndent(),
        )
        assertEquals(listOf(42.0, 42.0), plain.rows.zipWithNext { a, b -> b.y - a.y })
        // A fragment on the first row sits one clearance below the header band, and the rows it
        // spans keep the plain pitch.
        val first = layout(
            """
            sequenceDiagram
                participant A
                participant B
                opt guard
                    A->>B: one
                    B->>A: two
                end
            """.trimIndent(),
        )
        assertEquals(first.headerBandBottom + clearance, first.fragments.single().box.y)
        assertEquals(42.0, first.rows[1].y - first.rows[0].y)
        // Two sibling fragments: the second box starts one clearance under the first box.
        val siblings = layout(
            """
            sequenceDiagram
                participant A
                participant B
                opt first
                    A->>B: one
                end
                opt second
                    B->>A: two
                end
            """.trimIndent(),
        )
        val a = siblings.fragments.single { it.label == "first" }.box
        val b = siblings.fragments.single { it.label == "second" }.box
        assertEquals(a.bottom() + clearance, b.y)
    }
}
