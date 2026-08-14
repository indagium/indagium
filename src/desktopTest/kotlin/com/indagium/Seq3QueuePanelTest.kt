package com.indagium

import com.indagium.diagram3.Seq3CaptureSource
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3PinDirection
import com.indagium.diagram3.Seq3Repeat
import com.indagium.ui.Seq3TemplateSegment
import com.indagium.ui.seq3CollapsedOccurrenceCount
import com.indagium.ui.seq3ParseTemplateCaptures
import com.indagium.ui.seq3PinnableDirections
import com.indagium.ui.seq3TemplateSegments
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
