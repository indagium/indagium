package com.indagium

import com.indagium.diagram3.Seq3CaptureSource
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3OccurrenceRef
import com.indagium.diagram3.Seq3PinDirection
import com.indagium.diagram3.Seq3Repeat
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.Seq3TemplateSegment
import com.indagium.ui.blockOrderDuringDrag
import com.indagium.ui.cumulativeBlockOffsets
import com.indagium.ui.seq3CanSwapEndpoints
import com.indagium.ui.seq3CollapsedOccurrenceCount
import com.indagium.ui.seq3DocumentDisplaySegmentsLabel
import com.indagium.ui.seq3FragmentMessageCount
import com.indagium.ui.seq3LifelineDisplaySegmentsLabel
import com.indagium.ui.seq3ParseRowNumbers
import com.indagium.ui.seq3ParseTemplateCaptures
import com.indagium.ui.seq3PinnableDirections
import com.indagium.ui.seq3ResolveSelectedEntries
import com.indagium.ui.seq3TemplateSegments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ── Phase 4: Seq3QueuePanel's pure (non-Compose) helpers ───────────────────────────────────────
//
// Compose UI itself is untested in this repo (this phase's brief) — these cover exactly the parts
// of Seq3QueuePanel.kt that don't need a composition: the tie-only pin rule, the collapsed-repeat
// detector, and template-string parsing for the row's accent-highlighted pattern line and the
// Inspector's pattern field.

class Seq3QueuePanelTest {
    private fun occ(id: Int, ts: Long, text: String = "line $id") = Seq3Occurrence(id, ts, "10:00:00.000", 1, 1, 'I', text)

    private fun msg(
        id: String,
        occurrences: List<Seq3Occurrence>,
        repeat: Seq3Repeat = Seq3Repeat.COLLAPSE_ABOVE,
        threshold: Int = 3,
        template: String = "$id-label",
    ) = Seq3Message(id, Seq3Match("A", template), "A", "B", template, repeat = repeat, repeatThreshold = threshold, occurrences = occurrences)

    // ── seq3PinnableDirections: tie-only pin rule (spec §07) ────────────────────────────────────

    @Test
    fun pinnableDirectionsAreEmptyWhenNoNeighbourTies() {
        val doc = Seq3Document(
            lifelines = listOf(Seq3Lifeline("A", "A", setOf("A"), 0), Seq3Lifeline("B", "B", setOf("B"), 1)),
            messages = listOf(msg("m1", listOf(occ(1, 100))), msg("m2", listOf(occ(2, 200)))),
        )
        assertTrue(seq3PinnableDirections(doc, "m1").isEmpty())
        assertTrue(seq3PinnableDirections(doc, "m2").isEmpty())
    }

    @Test
    fun pinnableDirectionsIncludeBothSidesForTheMiddleOfAThreeWayTie() {
        val doc = Seq3Document(
            lifelines = listOf(Seq3Lifeline("A", "A", setOf("A"), 0), Seq3Lifeline("B", "B", setOf("B"), 1)),
            messages = listOf(msg("m1", listOf(occ(1, 100))), msg("m2", listOf(occ(2, 100))), msg("m3", listOf(occ(3, 100)))),
        )
        assertEquals(setOf(Seq3PinDirection.DOWN), seq3PinnableDirections(doc, "m1"))
        assertEquals(setOf(Seq3PinDirection.UP, Seq3PinDirection.DOWN), seq3PinnableDirections(doc, "m2"))
        assertEquals(setOf(Seq3PinDirection.UP), seq3PinnableDirections(doc, "m3"))
    }

    @Test
    fun pinnableDirectionsAreEmptyForAnUnknownMessage() {
        val doc = Seq3Document(messages = listOf(msg("m1", listOf(occ(1, 100)))))
        assertTrue(seq3PinnableDirections(doc, "nope").isEmpty())
    }

    // ── seq3CanSwapEndpoints: WP5's ⇄ control disable rule ──────────────────────────────────────

    @Test
    fun swapEndpointsIsEnabledForAnOrdinaryMessageWithATarget() {
        assertTrue(seq3CanSwapEndpoints(msg("m1", listOf(occ(1, 100)))))
    }

    @Test
    fun swapEndpointsIsDisabledForANoteMessage() {
        val note = Seq3Message("m1", Seq3Match("A", "note"), "A", null, "note", kind = Seq3Kind.NOTE, occurrences = listOf(occ(1, 100)))
        assertFalse(seq3CanSwapEndpoints(note))
    }

    @Test
    fun swapEndpointsIsDisabledForAMessageWithNoTarget() {
        val needsTarget = Seq3Message("m1", Seq3Match("A", "label"), "A", null, "label", occurrences = listOf(occ(1, 100)))
        assertFalse(seq3CanSwapEndpoints(needsTarget))
    }

    // ── seq3CollapsedOccurrenceCount: third inset row line (spec §04) ──────────────────────────

    @Test
    fun collapsedOccurrenceCountIsNullBelowOrAtTheThreshold() {
        val message = msg("m1", (1..3).map { occ(it, 100L) }, repeat = Seq3Repeat.COLLAPSE_ABOVE, threshold = 3)
        assertEquals(null, seq3CollapsedOccurrenceCount(message))
    }

    @Test
    fun collapsedOccurrenceCountIsTheOccurrenceCountAboveTheThreshold() {
        val message = msg("m1", (1..5).map { occ(it, 100L) }, repeat = Seq3Repeat.COLLAPSE_ABOVE, threshold = 3)
        assertEquals(5, seq3CollapsedOccurrenceCount(message))
    }

    @Test
    fun collapsedOccurrenceCountIsNullForEveryModeRegardlessOfCount() {
        val message = msg("m1", (1..10).map { occ(it, 100L) }, repeat = Seq3Repeat.EVERY)
        assertEquals(null, seq3CollapsedOccurrenceCount(message))
    }

    // ── seq3TemplateSegments: accent-highlighted `{token}` slots (spec §04) ────────────────────

    @Test
    fun templateSegmentsSplitsLiteralAndTokenRuns() {
        val segments = seq3TemplateSegments("connect to {deviceKey} on port {port}")
        assertEquals(
            listOf(
                Seq3TemplateSegment.Literal("connect to "),
                Seq3TemplateSegment.Token("deviceKey"),
                Seq3TemplateSegment.Literal(" on port "),
                Seq3TemplateSegment.Token("port"),
            ),
            segments,
        )
    }

    @Test
    fun templateSegmentsOfAPurelyLiteralTemplateIsOneSegment() {
        assertEquals(listOf(Seq3TemplateSegment.Literal("no tokens here")), seq3TemplateSegments("no tokens here"))
    }

    @Test
    fun templateSegmentsOfAPurelyTokenTemplateHasNoLiteralSegments() {
        assertEquals(listOf(Seq3TemplateSegment.Token("n")), seq3TemplateSegments("{n}"))
    }

    // ── seq3ParseTemplateCaptures: Inspector pattern field (spec §03) ──────────────────────────

    @Test
    fun parseTemplateCapturesFindsEveryDistinctTokenAsAuthorSourced() {
        val captures = seq3ParseTemplateCaptures("{a} and {b} and {a} again")
        assertEquals(2, captures.size)
        assertEquals(listOf("a", "b"), captures.map { it.name })
        assertTrue(captures.all { it.source == Seq3CaptureSource.AUTHOR })
    }

    @Test
    fun parseTemplateCapturesOfALiteralTemplateIsEmpty() {
        assertTrue(seq3ParseTemplateCaptures("no tokens").isEmpty())
    }

    // ── seq3ResolveSelectedEntries: "Add ＋" wiring (item 2) ────────────────────────────────────

    @Test
    fun resolveSelectedEntriesReturnsOnlyTheEntriesWhoseIdIsSelected() {
        val logData = (1..5).map { LogEntry(it, "10:00:00.00$it", LogLevel.I, "A", "line $it") }
        val resolved = seq3ResolveSelectedEntries(logData, setOf(2, 4))
        assertEquals(listOf(2, 4), resolved.map { it.id })
    }

    @Test
    fun resolveSelectedEntriesIsEmptyForAnEmptySelection() {
        val logData = listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "A", "line"))
        assertTrue(seq3ResolveSelectedEntries(logData, emptySet()).isEmpty())
    }

    @Test
    fun parseRowNumbersAcceptsSinglesRangesAndDeduplicates() {
        assertEquals(listOf(2, 4, 5, 6, 8), seq3ParseRowNumbers("2, 4-6; 8, 6"))
    }

    // ── WP3 item 3: the "name ▾" / "names ▾" display-segment option mapping ────────────────────
    // (Diagram default ↔ null, Full name ↔ 0, Last N ↔ N) ───────────────────────────────────────

    @Test
    fun lifelineDisplaySegmentsLabelMapsNullToDiagramDefault() {
        assertEquals("Diagram default", seq3LifelineDisplaySegmentsLabel(null))
    }

    @Test
    fun lifelineDisplaySegmentsLabelMapsZeroToFullName() {
        assertEquals("Full name", seq3LifelineDisplaySegmentsLabel(0))
    }

    @Test
    fun lifelineDisplaySegmentsLabelMapsOneTwoThreeToOrdinaryWords() {
        assertEquals("Last segment", seq3LifelineDisplaySegmentsLabel(1))
        assertEquals("Last 2", seq3LifelineDisplaySegmentsLabel(2))
        assertEquals("Last 3", seq3LifelineDisplaySegmentsLabel(3))
    }

    @Test
    fun lifelineDisplaySegmentsLabelFallsBackForAnUncoveredValue() {
        // A decoded note could hold a value no menu entry covers (schema evolution, hand-edited
        // JSON, …) — this must never throw, only degrade to a generic "Last N" label.
        assertEquals("Last 7", seq3LifelineDisplaySegmentsLabel(7))
    }

    @Test
    fun documentDisplaySegmentsLabelHasNoDiagramDefaultEntry() {
        // The document-level control IS the diagram default, so it never offers "inherit" as one
        // of its own choices — only the four concrete segment counts.
        assertEquals("Full name", seq3DocumentDisplaySegmentsLabel(0))
        assertEquals("Last segment", seq3DocumentDisplaySegmentsLabel(1))
        assertEquals("Last 2", seq3DocumentDisplaySegmentsLabel(2))
        assertEquals("Last 3", seq3DocumentDisplaySegmentsLabel(3))
        assertEquals("Last 9", seq3DocumentDisplaySegmentsLabel(9))
    }

    // ── WP3 item 9: fragment ×N message count (occurrenceRefs ∪ messageIds, deduplicated) ──────

    @Test
    fun fragmentMessageCountUnionsMessageIdsAndOccurrenceRefMessageIds() {
        val fragment = Seq3Fragment(
            id = "f1",
            kind = Seq3FragmentKind.LOOP,
            label = "loop",
            messageIds = listOf("m1", "m2"),
            occurrenceRefs = listOf(Seq3OccurrenceRef("m2", 10), Seq3OccurrenceRef("m3", 11)),
        )
        assertEquals(3, seq3FragmentMessageCount(fragment))
    }

    @Test
    fun fragmentMessageCountIsPlainSizeWhenThereAreNoOccurrenceRefs() {
        val fragment = Seq3Fragment(id = "f1", kind = Seq3FragmentKind.ALT, label = "alt", messageIds = listOf("m1", "m2", "m3"))
        assertEquals(3, seq3FragmentMessageCount(fragment))
    }

    // ── WP3 item 4: lifeline drag-to-reorder — variable-height index math ──────────────────────
    //
    // The Lifelines panel section reuses `cumulativeBlockOffsets`/`blockOrderDuringDrag`
    // (AnnotationPanel.kt's own note-block drag math, already covered by
    // `AppStateBehaviorTest.kt`'s block-drag tests) rather than a parallel duplicate — both are
    // fully generic over a `List<String>` + `heightOf: (String) -> Float`, with nothing
    // block-specific in their signatures. These tests exercise that same math from the lifeline
    // panel's own angle: rows of genuinely different heights (a plain row vs. one with an
    // expanded merged-tag block), which is exactly the shape `Seq3LifelinesSection`'s own
    // `rowHeightOf` produces.

    private val lifelineRowHeights = mapOf("A" to 64f, "B" to 132f, "C" to 64f, "D" to 64f)
    private fun lifelineRowHeight(id: String) = lifelineRowHeights.getValue(id)

    @Test
    fun cumulativeLifelineOffsetsAccountForOneTallMergedRow() {
        val offsets = cumulativeBlockOffsets(listOf("A", "B", "C", "D"), ::lifelineRowHeight)
        assertEquals(mapOf("A" to 0f, "B" to 64f, "C" to 196f, "D" to 260f), offsets)
    }

    @Test
    fun draggingAPlainRowPastATallMergedRowReordersByCenterCrossing() {
        // "B" (a tall, merged-tag row) sits after "A". Dragging "A" down far enough to cross past
        // B's center — not merely its top edge — should move A after B.
        val order = blockOrderDuringDrag(
            visibleIds = listOf("A", "B", "C", "D"),
            draggedId = "A",
            dragOffsetY = 150f,
            heightOf = ::lifelineRowHeight,
        )
        assertEquals(listOf("B", "A", "C", "D"), order)
    }

    @Test
    fun aSmallDragThatDoesNotCrossATallRowsCenterLeavesOrderUnchanged() {
        val order = blockOrderDuringDrag(
            visibleIds = listOf("A", "B", "C", "D"),
            draggedId = "A",
            dragOffsetY = 40f,
            heightOf = ::lifelineRowHeight,
        )
        assertEquals(listOf("A", "B", "C", "D"), order)
    }

    @Test
    fun dragOrderIgnoresAnUnknownDraggedLifelineId() {
        val order = blockOrderDuringDrag(
            visibleIds = listOf("A", "B", "C", "D"),
            draggedId = "not-a-lifeline",
            dragOffsetY = 200f,
            heightOf = ::lifelineRowHeight,
        )
        assertEquals(listOf("A", "B", "C", "D"), order)
    }
}
