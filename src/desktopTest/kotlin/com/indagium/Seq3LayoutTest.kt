package com.indagium

import com.indagium.diagram3.Seq3ArrowRow
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3ElisionRow
import com.indagium.diagram3.Seq3FontRole
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3LayoutOptions
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Note
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3OccurrenceRef
import com.indagium.diagram3.Seq3RasterTheme
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.Seq3SelfLoopRow
import com.indagium.diagram3.Seq3TextMetrics
import com.indagium.diagram3.Seq3UnresolvedStubRow
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.layoutSeq3
import com.indagium.diagram3.renderSeq3
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Seq3LayoutTest {
    // A fixed-width-per-character stub — deterministic across JDK/OS font rendering, unlike a real
    // AWT FontMetrics (see Seq3Layout.kt's own header doc on why the interface exists at all).
    private class StubMetrics(private val charWidth: Double = 7.0, private val lineH: Double = 16.0) : Seq3TextMetrics {
        override fun width(role: Seq3FontRole, text: String): Double = text.length * charWidth

        override fun lineHeight(role: Seq3FontRole): Double = lineH
    }

    private fun opts() = Seq3LayoutOptions(StubMetrics())

    private fun lifeline(id: String, ordinal: Int) = Seq3Lifeline(id, id, setOf(id), ordinal)

    private fun occurrence(entryId: Int, ts: Long? = 1_000L, text: String = "line $entryId") =
        Seq3Occurrence(entryId, ts, "10:00:00.000", pid = 1, tid = 1, level = 'I', text = text)

    private fun message(
        id: String,
        from: String,
        to: String?,
        kind: Seq3Kind = Seq3Kind.CALL,
        repeat: Seq3Repeat = Seq3Repeat.EVERY,
        threshold: Int = 3,
        occurrences: List<Seq3Occurrence> = listOf(occurrence(1)),
        visibility: Seq3Visibility = Seq3Visibility.VISIBLE,
        template: String = "$id-label",
    ) = Seq3Message(
        id = id,
        match = Seq3Match(from, template),
        fromLifelineId = from,
        toLifelineId = to,
        labelTemplate = template,
        kind = kind,
        repeat = repeat,
        repeatThreshold = threshold,
        visibility = visibility,
        occurrences = occurrences,
    )

    @Test
    fun selfCallDrawsAsALoopNotAStraightArrow() {
        val doc = Seq3Document(lifelines = listOf(lifeline("A", 0)), messages = listOf(message("m1", "A", "A", kind = Seq3Kind.SELF)))
        val layout = layoutSeq3(doc, opts())

        val row = layout.rows.single()
        assertTrue(row is Seq3SelfLoopRow, "a SELF message must produce a Seq3SelfLoopRow, not a straight arrow: $row")
        assertEquals("A", row.lifelineId)
    }

    @Test
    fun unresolvedMessageDrawsADashedStubNeverNothing() {
        val doc = Seq3Document(lifelines = listOf(lifeline("A", 0)), messages = listOf(message("m1", "A", null)))
        val layout = layoutSeq3(doc, opts())

        assertEquals(1, layout.rows.size, "an unresolved message must still draw something")
        val stub = layout.rows.single() as Seq3UnresolvedStubRow
        assertEquals("A", stub.fromLifelineId)
        assertTrue(stub.stubEndX > stub.fromX, "the stub must extend outward from its lifeline")
        assertTrue(stub.dropPill.width > 0 && stub.dropPill.height > 0, "the drop-on-a-lifeline pill must have real geometry")
    }

    @Test
    fun fragmentsNestCorrectlyWithAnInnerBracketClampedInsideItsParent() {
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(
                message("m1", "A", "B", occurrences = listOf(occurrence(1))),
                message("m2", "A", "B", occurrences = listOf(occurrence(2))),
                message("m3", "A", "B", occurrences = listOf(occurrence(3))),
            ),
            fragments = listOf(
                Seq3Fragment("outer", Seq3FragmentKind.LOOP, "retry", listOf("m1", "m2", "m3")),
                Seq3Fragment("inner", Seq3FragmentKind.ALT, "maybe", listOf("m2")),
            ),
        )
        val layout = layoutSeq3(doc, opts())

        val outer = layout.fragments.single { it.fragmentId == "outer" }
        val inner = layout.fragments.single { it.fragmentId == "inner" }
        assertEquals(0, outer.depth)
        assertEquals(1, inner.depth)
        assertTrue(inner.box.y >= outer.box.y, "inner top must not escape above outer top")
        assertTrue(inner.box.y + inner.box.height <= outer.box.y + outer.box.height, "inner bottom must not escape below outer bottom")
    }

    @Test
    fun occurrenceScopedFragmentDoesNotExpandToSiblingOccurrences() {
        val repeated = message(
            "m1",
            "A",
            "B",
            occurrences = listOf(occurrence(1), occurrence(2)),
        ).copy(repeat = Seq3Repeat.EVERY)
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(repeated),
            fragments = listOf(
                Seq3Fragment(
                    "exact",
                    Seq3FragmentKind.LOOP,
                    "only first",
                    messageIds = emptyList(),
                    occurrenceRefs = listOf(Seq3OccurrenceRef("m1", 1)),
                ),
            ),
        )
        val layout = layoutSeq3(doc, opts())
        val firstRow = layout.rows.single { it.occurrenceEntryId == 1 }
        val fragment = layout.fragments.single()

        assertEquals(firstRow.y + 21.0, fragment.box.y + fragment.box.height)
    }

    @Test
    fun noteSpansItsReferencedMessages() {
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(
                message("m1", "A", "B", occurrences = listOf(occurrence(1))),
                message("m2", "A", "B", occurrences = listOf(occurrence(2))),
            ),
            notes = listOf(Seq3Note("n1", "both calls happened", listOf("m1", "m2"))),
        )
        val layout = layoutSeq3(doc, opts())

        val note = layout.notes.single()
        assertEquals("n1", note.noteId)
        assertTrue(note.box.width > 0 && note.box.height > 0)
    }

    @Test
    fun collapseAboveThresholdProducesOneBadgedArrow() {
        val occs = (1..5).map { occurrence(it) }
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(message("m1", "A", "B", repeat = Seq3Repeat.COLLAPSE_ABOVE, threshold = 3, occurrences = occs)),
        )
        val layout = layoutSeq3(doc, opts())

        val row = layout.rows.single() as Seq3ArrowRow
        assertEquals(5, row.repeatCount)
        assertTrue(row.badgeBox != null, "a collapsed row above threshold must carry a badge box")
    }

    @Test
    fun collapseAtOrBelowThresholdDrawsEveryOccurrenceSeparately() {
        val occs = (1..2).map { occurrence(it) }
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(message("m1", "A", "B", repeat = Seq3Repeat.COLLAPSE_ABOVE, threshold = 3, occurrences = occs)),
        )
        val layout = layoutSeq3(doc, opts())

        assertEquals(2, layout.rows.size)
        layout.rows.forEach { row -> assertEquals(1, (row as Seq3ArrowRow).repeatCount) }
    }

    @Test
    fun everyModeDrawsOneRowPerOccurrence() {
        val occs = (1..4).map { occurrence(it) }
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(message("m1", "A", "B", repeat = Seq3Repeat.EVERY, occurrences = occs)),
        )
        val layout = layoutSeq3(doc, opts())

        assertEquals(4, layout.rows.size)
        assertTrue(layout.rows.all { it.messageId == "m1" })
    }

    // ── Item 5 (phase-5 round-2 post-ship plan): TRUE timeline order, not grouped-by-message ──────
    //
    // This directly reverses the round-1 assumption that "one message's occurrences render as one
    // contiguous block" was correct — that was only ever true of `expandForLayout`'s per-message
    // grouping (kept, unchanged, for the queue PANEL's convenience), never of what the CANVAS should
    // draw. Two messages on two different lifeline pairs, interleaved in real time (A@t0, B@t1,
    // A@t2, B@t3), must produce four canvas rows that alternate A/B/A/B by Y position — not two
    // contiguous blocks of A then B.

    @Test
    fun canvasRowsInterleaveByTrueTimestampAcrossDifferentMessagesNotGroupedByMessage() {
        val doc = Seq3Document(
            lifelines = listOf(lifeline("X", 0), lifeline("Y", 1), lifeline("Z", 2)),
            messages = listOf(
                // Message A (X->Y) fires at t0 and t2; message B (Y->Z) fires at t1 and t3 — genuinely
                // interleaved in real time, but grouped contiguously in doc.messages/expandForLayout
                // order (all of A's occurrences, then all of B's).
                message(
                    "msgA", "X", "Y", repeat = Seq3Repeat.EVERY,
                    occurrences = listOf(occurrence(1, ts = 1_000L), occurrence(3, ts = 3_000L)),
                ),
                message(
                    "msgB", "Y", "Z", repeat = Seq3Repeat.EVERY,
                    occurrences = listOf(occurrence(2, ts = 2_000L), occurrence(4, ts = 4_000L)),
                ),
            ),
        )
        val layout = layoutSeq3(doc, opts())

        assertEquals(4, layout.rows.size)
        val messageIdsByY = layout.rows.sortedBy { it.y }.map { it.messageId }
        assertEquals(
            listOf("msgA", "msgB", "msgA", "msgB"), messageIdsByY,
            "canvas rows must alternate by true timestamp order (A@1000,B@2000,A@3000,B@4000), " +
                "not group all of msgA's occurrences before msgB's: got $messageIdsByY",
        )
        // Also pin down the actual entry ids in order, not just the message-id alternation.
        val entryIdsByY = layout.rows.sortedBy { it.y }.map { it.occurrenceEntryId }
        assertEquals(listOf(1, 2, 3, 4), entryIdsByY)
    }

    @Test
    fun untimestampedAuthoredMessageKeepsItsDocumentInsertionPositionOnCanvas() {
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(
                message("before", "A", "B", occurrences = listOf(occurrence(1, 1_000L))),
                message("custom", "A", "B", occurrences = emptyList()),
                message("after", "A", "B", occurrences = listOf(occurrence(2, 2_000L))),
            ),
        )

        val layout = layoutSeq3(doc, opts())

        assertEquals(listOf("before", "custom", "after"), layout.rows.sortedBy { it.y }.map { it.messageId })
    }

    @Test
    fun manualTimestampOverrideMovesEveryEmissionOfAnEvidenceBackedMessage() {
        val moved = message("moved", "A", "B", occurrences = listOf(occurrence(1, 3_000L)))
            .copy(manualTimestampMillis = 1_000L)
        val later = message("later", "A", "B", occurrences = listOf(occurrence(2, 2_000L)))
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(moved, later),
        )

        val layout = layoutSeq3(doc, opts())

        assertEquals(listOf("moved", "later"), layout.rows.sortedBy { it.y }.map { it.messageId })
    }

    @Test
    fun firstLastModeDrawsFirstElisionMarkerThenLast() {
        // Item 5 (phase-5 round-2): the canvas now sorts every drawn row into true timeline order,
        // so this fixture uses genuinely increasing timestamps (rather than the default helper's
        // shared 1_000L) — a realistic FIRST_LAST run, not a synthetic same-instant tie.
        val occs = (1..5).map { occurrence(it, ts = 1_000L + it) }
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(message("m1", "A", "B", repeat = Seq3Repeat.FIRST_LAST, occurrences = occs)),
        )
        val layout = layoutSeq3(doc, opts())

        assertEquals(3, layout.rows.size)
        assertTrue(layout.rows[0] is Seq3ArrowRow)
        val elision = layout.rows[1] as Seq3ElisionRow
        assertEquals(3, elision.elidedCount) // 5 occurrences - first - last = 3 elided
        assertTrue(layout.rows[2] is Seq3ArrowRow)
        assertEquals(occs.first().entryId, (layout.rows[0] as Seq3ArrowRow).occurrenceEntryId)
        assertEquals(occs.last().entryId, (layout.rows[2] as Seq3ArrowRow).occurrenceEntryId)
    }

    @Test
    fun hiddenMessageContributesNoGeometryAtAll() {
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(
                message("m1", "A", "B", visibility = Seq3Visibility.VISIBLE),
                message("m2", "A", "B", occurrences = listOf(occurrence(2)), visibility = Seq3Visibility.HIDDEN),
            ),
        )
        val layout = layoutSeq3(doc, opts())

        assertTrue(layout.rows.none { it.messageId == "m2" }, "a hidden message must not appear in the layout at all")
        assertTrue(layout.rows.any { it.messageId == "m1" })
    }

    @Test
    fun stubRowLabelAndPillNeverOverlapVertically() {
        // item 10 (phase-5 post-ship plan): labelBox and dropPill used to share the same origin and
        // overlapping y-ranges. Assert their y-ranges are now genuinely disjoint.
        val doc = Seq3Document(lifelines = listOf(lifeline("A", 0)), messages = listOf(message("m1", "A", null)))
        val layout = layoutSeq3(doc, opts())
        val stub = layout.rows.single() as Seq3UnresolvedStubRow

        val labelTop = stub.labelBox.y
        val labelBottom = stub.labelBox.y + stub.labelBox.height
        val pillTop = stub.dropPill.y
        val pillBottom = stub.dropPill.y + stub.dropPill.height
        assertTrue(
            labelBottom <= pillTop || pillBottom <= labelTop,
            "labelBox [$labelTop, $labelBottom] and dropPill [$pillTop, $pillBottom] must not overlap in y",
        )
    }

    @Test
    fun stubRowGrowsItsPitchSoTheNextRowNeverOverlapsThePill() {
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(
                message("m1", "A", null),
                message("m2", "A", "B", occurrences = listOf(occurrence(2))),
            ),
        )
        val layout = layoutSeq3(doc, opts())
        val stub = layout.rows.single { it.messageId == "m1" } as Seq3UnresolvedStubRow
        val next = layout.rows.single { it.messageId == "m2" }
        val pillBottom = stub.dropPill.y + stub.dropPill.height

        assertTrue(next.y > pillBottom, "the next row (y=${next.y}) must sit below the stub's pill (bottom=$pillBottom)")
    }

    @Test
    fun crossingCountDetectsAnInterleavedArrangementButNotANestedOrDisjointOne() {
        val lifelines = listOf(lifeline("A", 0), lifeline("B", 1), lifeline("C", 2), lifeline("D", 3))
        // A->C (span 0..2) and B->D (span 1..3) interleave: 0 < 1 < 2 < 3 -> exactly one crossing.
        // A->B (span 0..1) is disjoint-ish from C->D and must not add another crossing.
        val doc = Seq3Document(
            lifelines = lifelines,
            messages = listOf(
                message("m1", "A", "C", occurrences = listOf(occurrence(1))),
                message("m2", "B", "D", occurrences = listOf(occurrence(2))),
                message("m3", "A", "B", occurrences = listOf(occurrence(3))),
            ),
        )
        val layout = layoutSeq3(doc, opts())

        assertEquals(1, layout.crossingCount)
    }

    @Test
    fun crossingCountIsZeroForACleanArrangement() {
        val lifelines = listOf(lifeline("A", 0), lifeline("B", 1), lifeline("C", 2))
        val doc = Seq3Document(
            lifelines = lifelines,
            messages = listOf(
                message("m1", "A", "B", occurrences = listOf(occurrence(1))),
                message("m2", "B", "C", occurrences = listOf(occurrence(2))),
            ),
        )
        val layout = layoutSeq3(doc, opts())

        assertEquals(0, layout.crossingCount)
    }

    @Test
    fun canvasAndRasterShareTheExactSameLayoutGeometry() {
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(message("m1", "A", "B", occurrences = listOf(occurrence(1)))),
        )
        val layout = layoutSeq3(doc, opts())
        val scale = 2f
        val rendered = renderSeq3(layout, Seq3RasterTheme.DEFAULT_LIGHT, scale)

        // The raster never recomputes a second layout — it scales THIS layout's own dimensions via
        // the Graphics2D transform (see Seq3Raster.kt's header). If it ever started re-measuring at
        // the target scale instead, these would silently drift apart.
        assertEquals((layout.width * scale).roundToInt(), rendered.widthPx)
        assertEquals((layout.height * scale).roundToInt(), rendered.heightPx)
    }
}
