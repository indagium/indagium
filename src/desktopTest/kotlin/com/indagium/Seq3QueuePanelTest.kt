package com.indagium

import com.indagium.diagram3.Seq3ArtifactStyle
import com.indagium.diagram3.Seq3CaptureSource
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Filter
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3InsertionPosition
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3OccurrenceRef
import com.indagium.diagram3.Seq3PinDirection
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.Seq3RowRef
import com.indagium.diagram3.Seq3Selection
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.seq3DrawnRowRefs
import com.indagium.diagram3.seq3FilterCounts
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.DARK_GITHUB
import com.indagium.ui.LIGHT_THEME
import com.indagium.ui.Seq3ArtifactHeading
import com.indagium.ui.Seq3CanvasRowRef
import com.indagium.ui.Seq3MessagesMode
import com.indagium.ui.Seq3TemplateSegment
import com.indagium.ui.Seq3ViewState
import com.indagium.ui.TileHeaderParts
import com.indagium.ui.blockOrderDuringDrag
import com.indagium.ui.cumulativeBlockOffsets
import com.indagium.ui.seq3ArtifactHeading
import com.indagium.ui.seq3CanSwapEndpoints
import com.indagium.ui.seq3CollapsedOccurrenceCount
import com.indagium.ui.seq3DocumentDisplaySegmentsLabel
import com.indagium.ui.seq3DropInsideFragments
import com.indagium.ui.seq3FragmentMessageCount
import com.indagium.ui.seq3FragmentRailLabel
import com.indagium.ui.seq3FragmentRails
import com.indagium.ui.seq3LifelineDisplaySegmentsLabel
import com.indagium.ui.seq3MessagesHeaderParts
import com.indagium.ui.seq3MessagesModeLabels
import com.indagium.ui.seq3MessagesSummaryCount
import com.indagium.ui.seq3ParseRowNumbers
import com.indagium.ui.seq3ParseSequenceRowKey
import com.indagium.ui.seq3ParseTemplateCaptures
import com.indagium.ui.seq3PinnableDirections
import com.indagium.ui.seq3QueueFooterText
import com.indagium.ui.seq3RailGutterDp
import com.indagium.ui.seq3ResolveSelectedEntries
import com.indagium.ui.seq3SelectSequenceRow
import com.indagium.ui.seq3SequenceDropCommand
import com.indagium.ui.seq3SequenceFilterActive
import com.indagium.ui.seq3SequenceFilterCounts
import com.indagium.ui.seq3SequenceGhostBackground
import com.indagium.ui.seq3SequenceGripHit
import com.indagium.ui.seq3SequenceRowCaption
import com.indagium.ui.seq3SequenceRowCount
import com.indagium.ui.seq3SequenceRowKey
import com.indagium.ui.seq3SequenceRowSelected
import com.indagium.ui.seq3SequenceRowText
import com.indagium.ui.seq3SequenceRows
import com.indagium.ui.seq3SequenceScrollIndex
import com.indagium.ui.seq3SequenceVisibleRows
import com.indagium.ui.seq3TemplateSegments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ── Phase 4: Seq3QueuePanel's pure (non-Compose) helpers ───────────────────────────────────────
//
// Compose UI itself is untested in this repo (this phase's brief) — these cover exactly the parts
// of Seq3QueuePanel.kt that don't need a composition: artifact heading display, the tie-only pin
// rule, the collapsed-repeat detector, and template-string parsing for the row's accent-highlighted
// pattern line and the Inspector's pattern field.

class Seq3QueuePanelTest {
    private fun occ(id: Int, ts: Long, text: String = "line $id") = Seq3Occurrence(id, ts, "10:00:00.000", 1, 1, 'I', text)

    private fun msg(
        id: String,
        occurrences: List<Seq3Occurrence>,
        repeat: Seq3Repeat = Seq3Repeat.COLLAPSE_ABOVE,
        threshold: Int = 3,
        template: String = "$id-label",
    ) = Seq3Message(id, Seq3Match("A", template), "A", "B", template, repeat = repeat, repeatThreshold = threshold, occurrences = occurrences)

    @Test
    fun artifactHeadingKeepsTheEditableLabelAndDropsOnlyAnExactKindDuplicate() {
        assertEquals(Seq3ArtifactHeading(kind = null, label = "group"), seq3ArtifactHeading("group", "group"))
        assertEquals(Seq3ArtifactHeading(kind = "loop", label = "retry"), seq3ArtifactHeading("loop", "retry"))
        assertEquals(Seq3ArtifactHeading(kind = "group", label = "Group"), seq3ArtifactHeading("group", "Group"))
    }

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

    // ── Sequence mode: one row per drawn arrow ──────────────────────────────────────────────────

    private val abLifelines = listOf(Seq3Lifeline("A", "com.acme.A", setOf("A"), 0), Seq3Lifeline("B", "B", setOf("B"), 1))

    /** `rep` draws as three exact rows, `big` as one collapsed summary, `solo` as one custom row. */
    @Suppress("MagicNumber") // Fixture entry ids, timestamps and repeat threshold.
    private fun sequenceDocument(fragments: List<Seq3Fragment> = emptyList()) = Seq3Document(
        lifelines = abLifelines,
        messages = listOf(
            msg("rep", (1..3).map { occ(it, it * 100L, "send $it") }, repeat = Seq3Repeat.EVERY),
            msg("big", (11..15).map { occ(it, 1_000L + it, "tick $it") }, repeat = Seq3Repeat.COLLAPSE_ABOVE, threshold = 3),
            Seq3Message("solo", Seq3Match("B", "reply"), "B", "A", "reply"),
        ),
        fragments = fragments,
    )

    @Test
    fun sequenceRowKeyRoundTripsExactAndSummaryRefs() {
        listOf(Seq3RowRef("m1", 7), Seq3RowRef("m1"), Seq3RowRef("a::b", 3)).forEach { ref ->
            assertEquals(ref, seq3ParseSequenceRowKey(seq3SequenceRowKey(ref)))
        }
        assertEquals("m1::*", seq3SequenceRowKey(Seq3RowRef("m1")))
        assertEquals("m1::7", seq3SequenceRowKey(Seq3RowRef("m1", 7)))
    }

    @Test
    fun parseSequenceRowKeyRejectsMalformedKeys() {
        assertEquals(null, seq3ParseSequenceRowKey("m1"))
        assertEquals(null, seq3ParseSequenceRowKey("::3"))
        assertEquals(null, seq3ParseSequenceRowKey("m1::x"))
    }

    @Test
    fun sequenceRowCountEqualsTheDrawnRowsAndSummaryRowsCarryNoOccurrence() {
        val document = sequenceDocument()
        val rows = seq3SequenceRows(document)
        assertEquals(seq3DrawnRowRefs(document), rows.map { it.ref })
        assertEquals((1..rows.size).toList(), rows.map { it.number })
        assertEquals(5, rows.size, "three exact rows, one collapsed summary, one custom row")
        val summary = rows.single { it.message.id == "big" }
        assertEquals(null, summary.occurrence)
        assertEquals(Seq3RowRef("big"), summary.ref)
        assertEquals(5, seq3SequenceRowCount(summary))
        assertEquals(null, seq3SequenceRowCount(rows.first { it.message.id == "rep" }))
        assertEquals("send 2", seq3SequenceRowText(rows.first { it.ref == Seq3RowRef("rep", 2) }))
        assertEquals("big-label", seq3SequenceRowText(summary))
    }

    @Test
    fun sequenceRowCaptionUsesTimestampAndDisplayNames() {
        val document = sequenceDocument().copy(lifelineDisplaySegments = 1)
        val rows = seq3SequenceRows(document)
        assertEquals("10:00:00.000 · A → B", seq3SequenceRowCaption(document, rows.first()))
        // A custom row has no evidence timestamp: just the endpoints.
        assertEquals("B → A", seq3SequenceRowCaption(document, rows.last()))
    }

    @Test
    fun sequenceRowCaptionMarksAnUnresolvedTarget() {
        val message = Seq3Message("m", Seq3Match("A", "x"), "A", null, "x")
        val document = Seq3Document(lifelines = abLifelines, messages = listOf(message))
        assertEquals("com.acme.A → ?", seq3SequenceRowCaption(document, seq3SequenceRows(document).single()))
    }

    // Drop → MoveRow: the final order is the list order with the dragged row in its new slot.

    private val dropKeys = listOf("a::*", "b::*", "c::*", "d::*")

    @Test
    fun droppingARowDownMovesItAfterTheRowNowAboveIt() {
        val command = seq3SequenceDropCommand("a::*", dropKeys, listOf("b::*", "c::*", "a::*", "d::*"))
        assertEquals(Seq3Command.MoveRow(Seq3RowRef("a"), Seq3InsertionPosition.AfterRow(Seq3RowRef("c"))), command)
    }

    @Test
    fun droppingARowUpMovesItBeforeTheRowItDisplaced() {
        val command = seq3SequenceDropCommand("d::*", dropKeys, listOf("a::*", "d::*", "b::*", "c::*"))
        assertEquals(Seq3Command.MoveRow(Seq3RowRef("d"), Seq3InsertionPosition.BeforeRow(Seq3RowRef("b"))), command)
    }

    @Test
    fun droppingAtTheTopAndBottomUsesTheOnlyNeighbour() {
        assertEquals(
            Seq3Command.MoveRow(Seq3RowRef("c"), Seq3InsertionPosition.BeforeRow(Seq3RowRef("a"))),
            seq3SequenceDropCommand("c::*", dropKeys, listOf("c::*", "a::*", "b::*", "d::*")),
        )
        assertEquals(
            Seq3Command.MoveRow(Seq3RowRef("a"), Seq3InsertionPosition.AfterRow(Seq3RowRef("d"))),
            seq3SequenceDropCommand("a::*", dropKeys, listOf("b::*", "c::*", "d::*", "a::*")),
        )
    }

    @Test
    fun droppingAnExactOccurrenceKeepsItsEntryId() {
        val keys = listOf("m::1", "m::2", "n::*")
        assertEquals(
            Seq3Command.MoveRow(Seq3RowRef("m", 1), Seq3InsertionPosition.AfterRow(Seq3RowRef("n"))),
            seq3SequenceDropCommand("m::1", keys, listOf("m::2", "n::*", "m::1")),
        )
    }

    @Test
    fun aDropThatChangedNothingIsNoCommand() {
        assertEquals(null, seq3SequenceDropCommand("b::*", dropKeys, dropKeys))
        assertEquals(null, seq3SequenceDropCommand(null, dropKeys, dropKeys))
        assertEquals(null, seq3SequenceDropCommand("zzz::*", dropKeys, dropKeys))
    }

    @Test
    fun theDroppedCommandMovesTheRowInTheDocument() {
        val document = sequenceDocument()
        val keys = seq3SequenceRows(document).map { it.key }
        val moved = keys.toMutableList().apply { add(0, removeAt(2)) }
        val command = requireNotNull(seq3SequenceDropCommand(keys[2], keys, moved))
        val result = com.indagium.diagram3.applySeq3Command(document, command)
        assertTrue(result.applied)
        assertEquals(moved, seq3SequenceRows(result.document).map { it.key })
    }

    @Test
    fun gripHitFollowsTheRailGutter() {
        assertTrue(seq3SequenceGripHit(10f, 1f, 0f))
        assertFalse(seq3SequenceGripHit(60f, 1f, 0f))
        assertFalse(seq3SequenceGripHit(10f, 1f, 14f), "a rail gutter pushes the grip right")
        assertTrue(seq3SequenceGripHit(30f, 1f, 14f))
        assertTrue(seq3SequenceGripHit(26f, 2f, 0f), "pixels scale with the density")
    }

    // Filters

    @Test
    fun noFilterKeepsEveryRowAndAnyFilterIsActive() {
        val document = sequenceDocument()
        val rows = seq3SequenceRows(document)
        assertFalse(seq3SequenceFilterActive(Seq3Filter.ALL, "  "))
        assertTrue(seq3SequenceFilterActive(Seq3Filter.EDITED, ""))
        assertTrue(seq3SequenceFilterActive(Seq3Filter.ALL, "x"))
        assertEquals(rows, seq3SequenceVisibleRows(document, rows, Seq3Filter.ALL, ""))
    }

    @Test
    fun pillFiltersFilterRowsByTheirParentMessage() {
        val needs = Seq3Message("needs", Seq3Match("A", "n"), "A", null, "n", occurrences = listOf(occ(30, 3_000L)))
        val document = sequenceDocument().let { it.copy(messages = it.messages + needs) }
        val rows = seq3SequenceRows(document)
        val visible = seq3SequenceVisibleRows(document, rows, Seq3Filter.NEEDS_TARGET, "")
        assertTrue(visible.isNotEmpty())
        assertEquals(setOf("needs"), visible.map { it.message.id }.toSet())
    }

    @Test
    fun textFilterMatchesTheParentTemplateOrTheOccurrenceText() {
        val document = sequenceDocument()
        val rows = seq3SequenceRows(document)
        // "reply" is solo's label.
        assertEquals(listOf("solo"), seq3SequenceVisibleRows(document, rows, Seq3Filter.ALL, "reply").map { it.message.id })
        // "tick 12" is only in one occurrence's log text: the collapsed summary row of its parent is kept.
        assertEquals(listOf("big"), seq3SequenceVisibleRows(document, rows, Seq3Filter.ALL, "tick 12").map { it.message.id }.distinct())
        // The parent's template matches, so all three of its rows stay.
        assertEquals(3, seq3SequenceVisibleRows(document, rows, Seq3Filter.ALL, "rep-label").size)
    }

    // Fragment rail

    private fun loop(vararg ids: String, refs: List<Seq3OccurrenceRef> = emptyList(), style: Seq3ArtifactStyle? = null) =
        Seq3Fragment("loop1", Seq3FragmentKind.LOOP, "retry", ids.toList(), occurrenceRefs = refs, style = style)

    @Test
    fun railsMarkMembershipRunStartsAndContinuation() {
        val fragment = loop("rep")
        val document = sequenceDocument(listOf(fragment))
        val rows = seq3SequenceRows(document)
        val rails = seq3FragmentRails(document, rows.map { it.ref })
        assertEquals(listOf(1, 1, 1, 0, 0), rails.map { it.containing.size })
        assertEquals(listOf(true, false, false, false, false), rails.map { it.starting.isNotEmpty() })
        assertEquals(listOf(true, true, false, false, false), rails.map { "loop1" in it.continuing })
        assertEquals(6f, seq3RailGutterDp(rails))
    }

    @Test
    fun railsAreAbsentWithoutVisibleFragmentsAndAHiddenFragmentDrawsNoRail() {
        val document = sequenceDocument(listOf(loop("rep").copy(visibility = Seq3Visibility.HIDDEN)))
        val rails = seq3FragmentRails(document, seq3SequenceRows(document).map { it.ref })
        assertTrue(rails.all { it.containing.isEmpty() })
        assertEquals(0f, seq3RailGutterDp(rails))
    }

    @Test
    fun nestedFragmentsWidenTheGutterUpToThreeBars() {
        val outer = loop("rep")
        val inner = Seq3Fragment("opt1", Seq3FragmentKind.OPT, "x", listOf("rep"))
        val document = sequenceDocument(listOf(outer, inner))
        val rails = seq3FragmentRails(document, seq3SequenceRows(document).map { it.ref })
        assertEquals(10f, seq3RailGutterDp(rails))
    }

    @Test
    fun aDropBetweenTwoMembersIsInsideTheFragmentAndAnEdgeDropIsNot() {
        val document = sequenceDocument(listOf(loop("rep")))
        val first = Seq3RowRef("rep", 1)
        val second = Seq3RowRef("rep", 2)
        val third = Seq3RowRef("rep", 3)
        assertEquals(listOf("loop1"), seq3DropInsideFragments(document, first, second).map { it.id })
        assertEquals(listOf("loop1"), seq3DropInsideFragments(document, second, third).map { it.id })
        assertTrue(seq3DropInsideFragments(document, third, Seq3RowRef("big")).isEmpty())
        assertTrue(seq3DropInsideFragments(document, null, first).isEmpty())
    }

    @Test
    fun railLabelFollowsTheCanvasKindPrefixRule() {
        assertEquals("loop: retry", seq3FragmentRailLabel(loop("rep")))
        assertEquals("retry", seq3FragmentRailLabel(loop("rep").copy(hideKindLabel = true)))
        assertEquals("Loop", seq3FragmentRailLabel(loop("rep").copy(label = "Loop")))
    }

    // Selection sync

    @Test
    fun anExactRowIsSelectedByItsOwnArrowOrByAWholeMessageSelection() {
        val ref = Seq3RowRef("rep", 2)
        val none = Seq3Selection()
        assertTrue(seq3SequenceRowSelected(ref, none, setOf(Seq3CanvasRowRef("rep", 2)), null, null))
        assertTrue(seq3SequenceRowSelected(ref, none, emptySet(), "rep", 2))
        assertFalse(seq3SequenceRowSelected(ref, none, setOf(Seq3CanvasRowRef("rep", 3)), "rep", 3))
        assertFalse(seq3SequenceRowSelected(ref, none, emptySet(), null, null))
        // The Templates list selected the whole message and no arrow in particular: every arrow shows.
        assertTrue(seq3SequenceRowSelected(ref, Seq3Selection(setOf("rep"), "rep"), emptySet(), null, null))
        // ...but a focused arrow of that message narrows it to that arrow.
        assertFalse(seq3SequenceRowSelected(ref, Seq3Selection(setOf("rep"), "rep"), emptySet(), "rep", 3))
    }

    @Test
    fun aSummaryRowIsSelectedByItsMessage() {
        val summary = Seq3RowRef("big")
        assertTrue(seq3SequenceRowSelected(summary, Seq3Selection(setOf("big"), "big"), emptySet(), null, null))
        assertFalse(seq3SequenceRowSelected(summary, Seq3Selection(setOf("rep"), "rep"), emptySet(), null, null))
    }

    @Test
    fun selectingARowSetsWhatAnArrowClickSets() {
        val view = Seq3ViewState()
        view.selectedOccurrenceIds = setOf("rep::1")
        seq3SelectSequenceRow(view, Seq3RowRef("rep", 2))
        assertEquals(setOf(Seq3CanvasRowRef("rep", 2)), view.selectedCanvasRows)
        assertEquals(Seq3Selection(setOf("rep"), "rep"), view.selection)
        assertEquals("rep", view.selectedOccurrenceMessageId)
        assertEquals(2, view.selectedOccurrenceEntryId)
        assertEquals("rep", view.focusedMessageId)
        assertEquals(emptySet(), view.selectedOccurrenceIds)
        assertTrue("rep" in view.expandedOccurrenceMessageIds, "the template card is open when the user switches back")
    }

    @Test
    fun selectingASummaryRowSelectsTheMessageWithoutAnExactOccurrence() {
        val view = Seq3ViewState()
        seq3SelectSequenceRow(view, Seq3RowRef("big"))
        assertEquals(Seq3Selection(setOf("big"), "big"), view.selection)
        assertEquals(null, view.selectedOccurrenceMessageId)
        assertEquals(null, view.selectedOccurrenceEntryId)
        assertEquals("big", view.focusedMessageId)
    }

    @Test
    fun scrollIndexPrefersTheExactArrowThenTheSummaryThenTheFirstRow() {
        val rows = seq3SequenceRows(sequenceDocument())
        assertEquals(1, seq3SequenceScrollIndex(rows, "rep", "rep", 2))
        // A summary click names an occurrence the list does not draw: fall back to the summary row.
        assertEquals(3, seq3SequenceScrollIndex(rows, "big", "big", 11))
        assertEquals(0, seq3SequenceScrollIndex(rows, "rep", null, null))
        assertEquals(-1, seq3SequenceScrollIndex(rows, "nope", null, null))
    }

    // Header and footer copy

    @Test
    fun modeLabelsCarryTheTemplateAndRowCounts() {
        assertEquals(listOf("Templates 11", "Sequence 45"), seq3MessagesModeLabels(11, 45, compact = false))
        assertEquals(listOf("Templates", "Sequence"), seq3MessagesModeLabels(11, 45, compact = true))
    }

    @Test
    fun footerTextCountsTemplatesMessagesAndMissingTargets() {
        assertEquals("11 templates · 45 messages · 0 need a target", seq3QueueFooterText(11, 45, 0))
        assertEquals("1 template · 1 message · 1 need a target", seq3QueueFooterText(1, 1, 1))
    }

    @Test
    fun theGhostFillIsOpaqueSoTheRowsUnderItNeverShowThrough() {
        listOf(LIGHT_THEME, DARK_GITHUB).forEach { theme ->
            val fill = seq3SequenceGhostBackground(theme)
            assertEquals(1f, fill.alpha, "the lifted row paints an opaque fill")
            assertTrue(fill != theme.abg, "it is the accent tint over the panel colour, not the bare translucent tint")
        }
    }

    @Test
    fun sequencePillCountsCountTheRowsOfTheMatchingMessages() {
        val base = sequenceDocument()
        val withHidden = base.copy(
            messages = base.messages.map { message ->
                if (message.id == "rep") {
                    message.copy(occurrences = message.occurrences.map { if (it.entryId == 2) it.copy(visibility = Seq3Visibility.HIDDEN) else it })
                } else {
                    message
                }
            },
        )
        val counts = seq3SequenceFilterCounts(withHidden, seq3SequenceRows(withHidden))
        assertEquals(4, counts.all, "all counts drawn rows (the hidden occurrence draws none), not the three templates")
        assertEquals(2, counts.hidden, "the hidden pill counts the rows of the message that has hidden content")
        assertEquals(3, seq3FilterCounts(withHidden).all, "the Templates pills keep counting messages")
    }

    @Test
    fun theMessagesHeaderCountFollowsTheMode() {
        val document = sequenceDocument()
        assertEquals(3, seq3MessagesSummaryCount(document, Seq3MessagesMode.TEMPLATES))
        assertEquals(5, seq3MessagesSummaryCount(document, Seq3MessagesMode.SEQUENCE))
    }

    @Test
    fun theMessagesHeaderDropsItsCountAndIconBeforeTheTitleCanShrink() {
        assertEquals(TileHeaderParts(showIcon = true, showSummary = true), seq3MessagesHeaderParts(460f))
        // The standard ~370dp tile: the title keeps its room, so no count there.
        assertEquals(TileHeaderParts(showIcon = true, showSummary = false), seq3MessagesHeaderParts(370f))
        assertEquals(TileHeaderParts(showIcon = false, showSummary = false), seq3MessagesHeaderParts(340f))
    }
}
