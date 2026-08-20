package com.indagium

import com.indagium.diagram3.Seq3ArrowRow
import com.indagium.diagram3.Seq3Box
import com.indagium.diagram3.Seq3Capture
import com.indagium.diagram3.Seq3CaptureSource
import com.indagium.diagram3.Seq3Delay
import com.indagium.diagram3.Seq3DelayBox
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3ElisionRow
import com.indagium.diagram3.Seq3FontRole
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3LayoutOptions
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3LifelineKind
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
import com.indagium.diagram3.Seq3LifelineSegment
import com.indagium.diagram3.layoutSeq3
import com.indagium.diagram3.renderSeq3
import com.indagium.diagram3.seq3LifelineSegments
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

    private fun occurrence(entryId: Int, ts: Long? = 1_000L, text: String = "line $entryId", captureValues: Map<String, String> = emptyMap()) =
        Seq3Occurrence(entryId, ts, "10:00:00.000", pid = 1, tid = 1, level = 'I', text = text, captureValues = captureValues)

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
        match: Seq3Match = Seq3Match(from, template),
    ) = Seq3Message(
        id = id,
        match = match,
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
        // WP7 item 2: a class that logs a line is usually the CALLEE, so the stub now draws to the
        // LEFT of its own lifeline (arrowhead pointing INTO it once resolved), not to the right.
        assertTrue(stub.stubEndX < stub.fromX, "the stub must extend to the LEFT of its lifeline so the eventual arrow points into it")
        assertTrue(stub.dropPill.width > 0 && stub.dropPill.height > 0, "the drop-on-a-lifeline pill must have real geometry")
        assertTrue(stub.dropPill.x >= 0.0, "the pill must never render at a negative x, even on the leftmost (only) lifeline")
        assertTrue(stub.labelBox.x >= 0.0, "the label must never render at a negative x either")
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
    fun aFreeFloatingNoteWithNoMessageIdsSurvivesLayoutInsteadOfBeingDropped() {
        // WP7 item 3: the empty-canvas "Add note here" menu creates a note with an EMPTY
        // messageIds and its own explicit geometry — the old code dropped any note whose
        // messageIds resolved to nothing (no anchor row to find), which silently ate this one too.
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(message("m1", "A", "B", occurrences = listOf(occurrence(1)))),
            notes = listOf(Seq3Note("floating1", "just a thought", messageIds = emptyList(), x = 300.0, y = 500.0, width = 220.0, height = 72.0)),
        )
        val layout = layoutSeq3(doc, opts())

        val note = layout.notes.single()
        assertEquals("floating1", note.noteId)
        assertEquals(300.0, note.box.x)
        assertEquals(500.0, note.box.y)
        assertEquals(220.0, note.box.width)
        assertEquals(72.0, note.box.height)
    }

    @Test
    fun aNoteWithNoMessageIdsAndNoGeometryIsStillDroppedRatherThanCrashing() {
        // The degenerate case a free-floating note's own "carries its own geometry" contract
        // depends on: empty messageIds AND no x/y/width/height at all has genuinely nothing to
        // place it by — layoutNotes must still skip it safely, not throw or invent a position.
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0)),
            notes = listOf(Seq3Note("n1", "orphaned", messageIds = emptyList())),
        )
        val layout = layoutSeq3(doc, opts())

        assertTrue(layout.notes.isEmpty())
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
    fun stubOnTheLeftmostOfSeveralLifelinesShiftsTheWholeDiagramRatherThanClipping() {
        // WP7 item 2: buildStubRow draws to the LEFT of its lifeline; when that lifeline is the
        // FIRST column there is no gap before it to widen (unlike an interior column, where
        // solveGaps' widenLeftSingle widens gaps[c-1] instead) — layoutSeq3 must instead shift
        // every column's start (placeColumns' leftExtra) so the pill/label still land at a
        // non-negative x, never clipped against the canvas edge.
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1), lifeline("C", 2)),
            messages = listOf(
                message("m1", "A", null, template = "a genuinely long unresolved label text"),
                message("m2", "B", "C", occurrences = listOf(occurrence(2))),
            ),
        )
        val layout = layoutSeq3(doc, opts())
        val stub = layout.rows.single { it.messageId == "m1" } as Seq3UnresolvedStubRow

        assertTrue(stub.stubEndX < stub.fromX, "the stub still points into its own (leftmost) lifeline")
        assertTrue(stub.dropPill.x >= 0.0, "the pill must never render at a negative x on the leftmost lifeline")
        assertTrue(stub.labelBox.x >= 0.0, "the label must never render at a negative x on the leftmost lifeline")
        // Every OTHER lifeline must have shifted right by the same amount, not just A — otherwise
        // the columns would overlap or the inter-column gaps would silently change.
        val bCenter = layout.lifelines.single { it.lifelineId == "B" }.centerX
        val cCenter = layout.lifelines.single { it.lifelineId == "C" }.centerX
        assertTrue(bCenter < cCenter, "B must still sit left of C after the shift")
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
    fun hiddenLifelinesAndTheirArrowsLeaveTheCanvasUntilShownAgain() {
        val hiddenB = lifeline("B", 1).copy(visibility = Seq3Visibility.HIDDEN)
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), hiddenB),
            messages = listOf(message("m1", "A", "B", occurrences = listOf(occurrence(1)))),
        )

        val layout = layoutSeq3(doc, opts())

        assertEquals(listOf("A"), layout.lifelines.map { it.lifelineId })
        assertTrue(layout.rows.isEmpty(), "an arrow to a hidden lifeline must not become a dangling canvas row")
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

    // ── WP2 item 5: fragment box must never intrude into the header band ───────────────────────

    @Test
    fun fragmentSpanningTheFirstMessageNeverIntrudesIntoTheHeaderBand() {
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(
                message("m1", "A", "B", occurrences = listOf(occurrence(1))),
                message("m2", "A", "B", occurrences = listOf(occurrence(2))),
            ),
            fragments = listOf(Seq3Fragment("f1", Seq3FragmentKind.LOOP, "retry", listOf("m1"))),
        )
        val layout = layoutSeq3(doc, opts())
        val headerBandBottom = layout.lifelines.first().lifelineTop
        val fragment = layout.fragments.single()

        assertTrue(
            fragment.box.y >= headerBandBottom,
            "fragment top (${fragment.box.y}) must not rise above the header band bottom ($headerBandBottom)",
        )
    }

    @Test
    fun nestedFragmentSpanningTheFirstMessageAlsoNeverIntrudesIntoTheHeaderBand() {
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(
                message("m1", "A", "B", occurrences = listOf(occurrence(1))),
                message("m2", "A", "B", occurrences = listOf(occurrence(2))),
            ),
            fragments = listOf(
                Seq3Fragment("outer", Seq3FragmentKind.LOOP, "retry", listOf("m1", "m2")),
                Seq3Fragment("inner", Seq3FragmentKind.ALT, "maybe", listOf("m1")),
            ),
        )
        val layout = layoutSeq3(doc, opts())
        val headerBandBottom = layout.lifelines.first().lifelineTop

        layout.fragments.forEach { fragment ->
            assertTrue(
                fragment.box.y >= headerBandBottom,
                "fragment '${fragment.fragmentId}' top (${fragment.box.y}) must not rise above the header band bottom ($headerBandBottom)",
            )
        }
    }

    @Test
    fun hiddenFragmentIsOmittedFromTheLayout() {
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(message("m1", "A", "B", occurrences = listOf(occurrence(1)))),
            fragments = listOf(Seq3Fragment("f1", Seq3FragmentKind.LOOP, "retry", listOf("m1"), visibility = Seq3Visibility.HIDDEN)),
        )
        val layout = layoutSeq3(doc, opts())

        assertTrue(layout.fragments.isEmpty(), "a hidden fragment must not produce a drawn box")
    }

    @Test
    fun hiddenNoteIsOmittedFromTheLayout() {
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(message("m1", "A", "B", occurrences = listOf(occurrence(1)))),
            notes = listOf(Seq3Note("n1", "hidden note", listOf("m1"), visibility = Seq3Visibility.HIDDEN)),
        )
        val layout = layoutSeq3(doc, opts())

        assertTrue(layout.notes.isEmpty(), "a hidden note must not produce a drawn box")
    }

    // ── WP2 item 2: multi-line headers + actor glyph ────────────────────────────────────────────

    @Test
    fun longDottedLifelineNameWrapsAndGrowsTheSharedHeaderHeight() {
        val shortDoc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(message("m1", "A", "B")),
        )
        val shortLayout = layoutSeq3(shortDoc, opts())

        val long = Seq3Lifeline("A", "com.mycompany.myapp.Example1", setOf("A"), 0)
        val short = Seq3Lifeline("B", "B", setOf("B"), 1)
        val longDoc = Seq3Document(lifelines = listOf(long, short), messages = listOf(message("m1", "A", "B")))
        val longLayout = layoutSeq3(longDoc, opts())

        val longCol = longLayout.lifelines.single { it.lifelineId == "A" }
        val shortCol = longLayout.lifelines.single { it.lifelineId == "B" }
        assertTrue(longCol.labelLines.size > 1, "a long dotted name must wrap into multiple lines; got ${longCol.labelLines}")
        // headerHeight is ONE shared value: the short-named column's box must be exactly as tall
        // as the wrapped long-named column's, even though its own label is one line.
        assertEquals(shortCol.header.height, longCol.header.height, "every column's header box must share ONE height")
        assertTrue(
            longLayout.lifelines.first().lifelineTop > shortLayout.lifelines.first().lifelineTop,
            "wrapping to multiple lines must grow the shared header band",
        )
    }

    @Test
    fun actorLifelineReservesExtraHeaderHeightForEveryColumn() {
        val plainDoc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(message("m1", "A", "B")),
        )
        val plainLayout = layoutSeq3(plainDoc, opts())

        val actor = lifeline("A", 0).copy(kind = Seq3LifelineKind.ACTOR)
        val participant = lifeline("B", 1)
        val actorDoc = Seq3Document(lifelines = listOf(actor, participant), messages = listOf(message("m1", "A", "B")))
        val actorLayout = layoutSeq3(actorDoc, opts())

        val actorCol = actorLayout.lifelines.single { it.lifelineId == "A" }
        val participantCol = actorLayout.lifelines.single { it.lifelineId == "B" }
        assertEquals(Seq3LifelineKind.ACTOR, actorCol.kind)
        assertEquals(Seq3LifelineKind.PARTICIPANT, participantCol.kind)
        // The reserve is document-wide (shared geometry), so the PARTICIPANT column also grows.
        assertEquals(actorCol.header.height, participantCol.header.height)
        assertTrue(
            actorLayout.lifelines.first().lifelineTop > plainLayout.lifelines.first().lifelineTop,
            "an ACTOR lifeline anywhere in the document must grow the shared header band for every column",
        )
    }

    // ── Item 9 (WP9 regression fix) ─────────────────────────────────────────────────────────────

    @Test
    fun labelBoxIsStrictlyWiderThanItsOwnMeasuredString() {
        // The exact string from the user's bug report: visible in the Info panel but clipped off
        // the drawn arrow because the box used to be sized to EXACTLY this string's measured width.
        val text = "onScreenChanged: MEDIA"
        val metrics = StubMetrics()
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(message("m1", "A", "B", template = text)),
        )
        val layout = layoutSeq3(doc, Seq3LayoutOptions(metrics))

        val row = layout.rows.single() as Seq3ArrowRow
        val measured = metrics.width(Seq3FontRole.LABEL, text)
        assertTrue(
            row.labelBox.width > measured,
            "label box (${row.labelBox.width}) must carry slack over the raw measured width ($measured), or a residual rasterizer delta clips the drawn text",
        )

        // WP10: turning on numbering/timestamps widens the DRAWN string itself ("[#1] [10:00:00.000]
        // onScreenChanged: MEDIA"). If measurement ran on the bare label and prefixing happened
        // afterward, the box would be sized to the SHORTER, unprefixed string while the longer,
        // prefixed one is what's actually drawn — WP9's bug again, just triggered by a toggle
        // instead of a font mismatch. Assert against the box's own drawn label, not the original
        // bare text, so this only passes when prefixing happened BEFORE measureRequirement ran.
        val prefixedDoc = doc.copy(showSequenceNumbers = true, showTimestamps = true)
        val prefixedLayout = layoutSeq3(prefixedDoc, Seq3LayoutOptions(metrics))
        val prefixedRow = prefixedLayout.rows.single() as Seq3ArrowRow
        assertTrue(prefixedRow.label.startsWith("[#1] ["), "expected the drawn label to carry the [#n] [ts] prefix; got '${prefixedRow.label}'")
        val prefixedMeasured = metrics.width(Seq3FontRole.LABEL, prefixedRow.label)
        assertTrue(
            prefixedRow.labelBox.width > prefixedMeasured,
            "prefixed label box (${prefixedRow.labelBox.width}) must carry slack over its OWN measured width ($prefixedMeasured) — " +
                "measurement must run AFTER prefixing, not before",
        )
    }

    @Test
    fun selfLoopLabelBoxAlsoCarriesSlack() {
        val text = "reallyQuiteALongSelfCallLabel"
        val metrics = StubMetrics()
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0)),
            messages = listOf(message("m1", "A", "A", kind = Seq3Kind.SELF, template = text)),
        )
        val layout = layoutSeq3(doc, Seq3LayoutOptions(metrics))

        val row = layout.rows.single() as Seq3SelfLoopRow
        val measured = metrics.width(Seq3FontRole.LABEL, text)
        assertTrue(row.labelBox.width > measured, "self-loop label box (${row.labelBox.width}) must carry slack over the raw measured width ($measured)")
    }

    @Test
    fun collapsedRowWithThreeOrFewerDistinctValuesShowsACompactSummary() {
        val occs = listOf(
            occurrence(1, text = "onScreenChanged: MEDIA", captureValues = mapOf("screen" to "MEDIA")),
            occurrence(2, text = "onScreenChanged: HOME", captureValues = mapOf("screen" to "HOME")),
            occurrence(3, text = "onScreenChanged: MEDIA", captureValues = mapOf("screen" to "MEDIA")),
            occurrence(4, text = "onScreenChanged: HOME", captureValues = mapOf("screen" to "HOME")),
        )
        val match = Seq3Match(tag = "A", template = "onScreenChanged: {screen}", captures = listOf(Seq3Capture("screen", Seq3CaptureSource.NAMED_VALUE)))
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(
                message(
                    "m1", "A", "B",
                    repeat = Seq3Repeat.COLLAPSE_ABOVE, threshold = 3, occurrences = occs,
                    template = "onScreenChanged: {screen}", match = match,
                ),
            ),
        )
        val layout = layoutSeq3(doc, opts())

        val row = layout.rows.single() as Seq3ArrowRow
        assertEquals(4, row.repeatCount)
        assertEquals("onScreenChanged: MEDIA|onScreenChanged: HOME", row.label, "a collapsed row with <=3 distinct values must show a compact A|B|C summary, not a raw {token}")
    }

    @Test
    fun collapsedRowWithMoreThanThreeDistinctValuesKeepsTheRawTemplate() {
        val occs = (1..5).map { i -> occurrence(i, text = "onScreenChanged: V$i", captureValues = mapOf("screen" to "V$i")) }
        val match = Seq3Match(tag = "A", template = "onScreenChanged: {screen}", captures = listOf(Seq3Capture("screen", Seq3CaptureSource.NAMED_VALUE)))
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(
                message(
                    "m1", "A", "B",
                    repeat = Seq3Repeat.COLLAPSE_ABOVE, threshold = 3, occurrences = occs,
                    template = "onScreenChanged: {screen}", match = match,
                ),
            ),
        )
        val layout = layoutSeq3(doc, opts())

        val row = layout.rows.single() as Seq3ArrowRow
        assertEquals(5, row.repeatCount)
        assertEquals("onScreenChanged: {screen}", row.label, "above 3 distinct values, the raw {token} template is the honest 'many different values' signal")
    }

    // ── WP10 (item 7): inline call numbering ────────────────────────────────────────────────────

    @Test
    fun sequenceNumbersSkipHiddenMessagesAndCountOnlyDrawnRows() {
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(
                message("m1", "A", "B", template = "first"),
                message("m2", "A", "B", template = "hidden", visibility = Seq3Visibility.HIDDEN),
                message("m3", "A", "B", template = "second"),
            ),
            showSequenceNumbers = true,
        )
        val layout = layoutSeq3(doc, opts())

        val rows = layout.rows.filterIsInstance<Seq3ArrowRow>()
        assertEquals(2, rows.size, "the hidden message must never draw a row at all")
        assertTrue(rows[0].label.startsWith("[#1] "), "the first drawn call must be numbered #1; got '${rows[0].label}'")
        assertTrue(rows[1].label.startsWith("[#2] "), "a hidden row must not consume a number — the next drawn call is #2, not #3; got '${rows[1].label}'")
    }

    @Test
    fun collapsedRepeatRowTakesExactlyOneSequenceNumber() {
        val occs = (1..5).map { i -> occurrence(i) }
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(
                message("m1", "A", "B", repeat = Seq3Repeat.COLLAPSE_ABOVE, threshold = 3, occurrences = occs, template = "repeated"),
                message("m2", "A", "B", template = "next"),
            ),
            showSequenceNumbers = true,
        )
        val layout = layoutSeq3(doc, opts())

        val rows = layout.rows.filterIsInstance<Seq3ArrowRow>()
        assertEquals(2, rows.size, "the collapsed ×5 group must still draw as ONE row")
        assertEquals(5, rows[0].repeatCount)
        assertTrue(rows[0].label.startsWith("[#1] "), "a collapsed ×n row takes exactly one number; got '${rows[0].label}'")
        assertTrue(rows[1].label.startsWith("[#2] "), "the next message must be #2, not #6; got '${rows[1].label}'")
    }

    @Test
    fun bothTogglesOffLeaveTheLabelUnprefixed() {
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(message("m1", "A", "B", template = "plain")),
        )
        val layout = layoutSeq3(doc, opts())

        val row = layout.rows.single() as Seq3ArrowRow
        assertEquals("plain", row.label)
    }

    @Test
    fun everyDrawnRowGeometryCarriesItsOwnTimestampFields() {
        val occ = occurrence(1, ts = 5_000L, text = "line")
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(message("m1", "A", "B", occurrences = listOf(occ))),
        )
        val layout = layoutSeq3(doc, opts())

        val row = layout.rows.single() as Seq3ArrowRow
        assertEquals(5_000L, row.timestampMillis)
        assertEquals("10:00:00.000", row.rawTimestamp)
    }

    // ── Time-gap markers (WP11) ─────────────────────────────────────────────────────────────

    @Test
    fun aDelayRendersAsALabelledBandAfterItsAnchorMessageAndPushesLaterRowsDown() {
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(
                message("m1", "A", "B", occurrences = listOf(occurrence(1, ts = 1_000L))),
                message("m2", "A", "B", occurrences = listOf(occurrence(2, ts = 2_000L))),
            ),
            delays = listOf(Seq3Delay("d1", afterMessageId = "m1", label = "a while later")),
        )
        val layout = layoutSeq3(doc, opts())

        assertEquals(2, layout.rows.size, "a delay is not a drawn row of its own")
        val delayBox = layout.delays.single()
        assertEquals("d1", delayBox.delayId)
        assertEquals("a while later", delayBox.label)
        assertTrue(delayBox.box.width > 0.0, "the band must span real width, not a zero-width sliver")

        val m1Row = layout.rows.single { it.messageId == "m1" }
        val m2Row = layout.rows.single { it.messageId == "m2" }
        assertTrue(delayBox.box.y >= m1Row.y, "the band must sit at or after its anchor row")
        assertTrue(m2Row.y >= delayBox.box.y + delayBox.box.height, "a row after the delay must be pushed below the whole band, not overlap it")
    }

    @Test
    fun aDelayWithNoVisibleAnchorRowRendersNothing() {
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(message("m1", "A", "B", visibility = Seq3Visibility.HIDDEN)),
            delays = listOf(Seq3Delay("d1", afterMessageId = "m1", label = "gap")),
        )
        val layout = layoutSeq3(doc, opts())

        assertTrue(layout.delays.isEmpty(), "an anchor with no drawn row leaves nothing to attach the band to")
    }

    @Test
    fun aDelayAnchoredToOneSpecificOccurrenceLandsRightAfterThatRowEvenWhenTheMessageRepeatsLater() {
        // User-observed correction: right-clicking the FIRST of two occurrences of the same
        // repeated message and choosing "Insert delay after this" used to always place the delay
        // after the LAST occurrence instead — afterMessageId alone can't tell the two rows apart.
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(
                message("m1", "A", "B", occurrences = listOf(occurrence(1, ts = 1_000L), occurrence(2, ts = 2_000L))),
                message("m2", "A", "B", occurrences = listOf(occurrence(3, ts = 3_000L))),
            ),
            delays = listOf(Seq3Delay("d1", afterMessageId = "m1", label = "gap", afterOccurrenceEntryId = 1)),
        )
        val layout = layoutSeq3(doc, opts())

        assertEquals(3, layout.rows.size, "a delay is not a drawn row of its own")
        val firstOccurrenceRow = layout.rows.single { it.messageId == "m1" && it.occurrenceEntryId == 1 }
        val secondOccurrenceRow = layout.rows.single { it.messageId == "m1" && it.occurrenceEntryId == 2 }
        val delayBox = layout.delays.single()
        assertTrue(delayBox.box.y >= firstOccurrenceRow.y, "the band must sit at or after the FIRST occurrence it was anchored to")
        assertTrue(
            secondOccurrenceRow.y >= delayBox.box.y + delayBox.box.height,
            "the SECOND occurrence must be pushed below the whole band, not sit above/inside it — the delay belongs between the two occurrences, not after both",
        )
    }

    @Test
    fun aDelayAnchoredToAStaleOccurrenceFallsBackToAfterTheLastOccurrenceOfItsMessage() {
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(
                message("m1", "A", "B", occurrences = listOf(occurrence(1, ts = 1_000L), occurrence(2, ts = 2_000L))),
            ),
            // entryId 99 was never emitted (hidden, or the row no longer repeats that many times).
            delays = listOf(Seq3Delay("d1", afterMessageId = "m1", label = "gap", afterOccurrenceEntryId = 99)),
        )
        val layout = layoutSeq3(doc, opts())

        val lastOccurrenceRow = layout.rows.single { it.messageId == "m1" && it.occurrenceEntryId == 2 }
        val delayBox = layout.delays.single()
        assertTrue(delayBox.box.y >= lastOccurrenceRow.y, "a dangling occurrence ref must fall back to the message's last occurrence, not draw nothing")
    }

    @Test
    fun aHiddenDelayIsDroppedFromLayoutButItsMessageIsUnaffected() {
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(message("m1", "A", "B", occurrences = listOf(occurrence(1)))),
            delays = listOf(Seq3Delay("d1", afterMessageId = "m1", label = "gap", visibility = Seq3Visibility.HIDDEN)),
        )
        val layout = layoutSeq3(doc, opts())

        assertTrue(layout.delays.isEmpty())
        assertEquals(1, layout.rows.size)
    }

    // ── seq3LifelineSegments (WP11 follow-up): dash/dot split around a delay band ─────────────

    private fun delayBox(y: Double, height: Double = 34.0) = Seq3DelayBox("d", "gap", Seq3Box(0.0, y, 100.0, height))

    @Test
    fun noDelaysProducesOneUndottedSegmentSpanningTheWholeLifeline() {
        val segments = seq3LifelineSegments(top = 0.0, bottom = 500.0, delays = emptyList())

        assertEquals(listOf(Seq3LifelineSegment(0.0, 500.0, isDotted = false)), segments)
    }

    @Test
    fun oneDelayInTheMiddleProducesDashDotDash() {
        val segments = seq3LifelineSegments(top = 0.0, bottom = 500.0, delays = listOf(delayBox(y = 200.0, height = 34.0)))

        assertEquals(
            listOf(
                Seq3LifelineSegment(0.0, 200.0, isDotted = false),
                Seq3LifelineSegment(200.0, 234.0, isDotted = true),
                Seq3LifelineSegment(234.0, 500.0, isDotted = false),
            ),
            segments,
        )
    }

    @Test
    fun aDelayFlushAgainstTheTopEdgeProducesJustDotThenDash() {
        val segments = seq3LifelineSegments(top = 0.0, bottom = 100.0, delays = listOf(delayBox(y = 0.0, height = 34.0)))

        assertEquals(listOf(Seq3LifelineSegment(0.0, 34.0, isDotted = true), Seq3LifelineSegment(34.0, 100.0, isDotted = false)), segments)
    }

    @Test
    fun twoDelaysProduceDashDotDashDotDash() {
        val segments = seq3LifelineSegments(top = 0.0, bottom = 500.0, delays = listOf(delayBox(y = 100.0), delayBox(y = 300.0)))

        assertEquals(5, segments.size)
        assertEquals(listOf(false, true, false, true, false), segments.map { it.isDotted })
    }

    @Test
    fun delaysOutOfOrderInTheInputStillProduceAMonotonicNonOverlappingSegmentList() {
        // document.delays has no guaranteed order — the function itself must sort by y.
        val segments = seq3LifelineSegments(top = 0.0, bottom = 500.0, delays = listOf(delayBox(y = 300.0), delayBox(y = 100.0)))

        assertEquals(listOf(false, true, false, true, false), segments.map { it.isDotted })
        for (i in 1 until segments.size) {
            assertTrue(segments[i - 1].toY <= segments[i].fromY, "segments must be produced in increasing, non-overlapping y order")
        }
    }
}
