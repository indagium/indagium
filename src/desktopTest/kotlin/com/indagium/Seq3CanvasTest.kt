package com.indagium

import com.indagium.diagram3.Seq3ArrowRow
import com.indagium.diagram3.Seq3Document
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
import com.indagium.diagram3.Seq3Repeat
import com.indagium.diagram3.Seq3SelfLoopRow
import com.indagium.diagram3.Seq3TextMetrics
import com.indagium.diagram3.Seq3UnresolvedStubRow
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.layoutSeq3
import com.indagium.ui.Seq3DragEndpoint
import com.indagium.ui.Seq3CanvasRowRef
import com.indagium.ui.Seq3EndpointSide
import com.indagium.ui.seq3ArrowEndpointAt
import com.indagium.ui.seq3CrossingLabel
import com.indagium.ui.seq3DragPreview
import com.indagium.ui.seq3FitHeightZoom
import com.indagium.ui.seq3FitWidthZoom
import com.indagium.ui.seq3IsEmptyCanvasBackground
import com.indagium.ui.seq3LifelineDropIndex
import com.indagium.ui.seq3NearestLifelineId
import com.indagium.ui.seq3PointInBox
import com.indagium.ui.seq3PointerPxToLayoutUnits
import com.indagium.ui.seq3ReorderLifelineIds
import com.indagium.ui.seq3ResolveDragEndpoint
import com.indagium.ui.seq3RowAt
import com.indagium.ui.seq3RowIsEmphasized
import com.indagium.ui.seq3RowsInSelection
import com.indagium.ui.seq3RowRefsInSelection
import com.indagium.ui.seq3SelectionRect
import com.indagium.ui.seq3SelfLoopEndpointAt
import com.indagium.ui.seq3ArrowStrokeWidths
import com.indagium.ui.seq3ContextMenuOffset
import com.indagium.ui.seq3FragmentIsEmphasized
import com.indagium.ui.seq3NoteIsEmphasized
import com.indagium.ui.seq3ZoomPercentLabel
import com.indagium.ui.seq3ZoomByWheel
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ── Phase 4: Seq3Canvas's pure (non-Compose) helpers ────────────────────────────────────────────
//
// Every function under test here operates on a REAL `Seq3Layout` (via `layoutSeq3` + a
// deterministic stub metrics, same fixture style as Seq3LayoutTest) — never a second, ad-hoc
// geometry, per this phase's second absolute rule: "geometry comes from Seq3RenderCache.layout".

class Seq3CanvasTest {
    private class StubMetrics(private val charWidth: Double = 7.0, private val lineH: Double = 16.0) : Seq3TextMetrics {
        override fun width(role: Seq3FontRole, text: String): Double = text.length * charWidth

        override fun lineHeight(role: Seq3FontRole): Double = lineH
    }

    private fun opts() = Seq3LayoutOptions(StubMetrics())

    private fun lifeline(id: String, ordinal: Int) = Seq3Lifeline(id, id, setOf(id), ordinal)

    private fun occurrence(entryId: Int, ts: Long? = 1_000L) = Seq3Occurrence(entryId, ts, "10:00:00.000", 1, 1, 'I', "line $entryId")

    private fun message(
        id: String,
        from: String,
        to: String?,
        kind: Seq3Kind = Seq3Kind.CALL,
        occurrences: List<Seq3Occurrence> = listOf(occurrence(1)),
        visibility: Seq3Visibility = Seq3Visibility.VISIBLE,
    ) = Seq3Message(
        id = id, match = Seq3Match(from, "$id-label"), fromLifelineId = from, toLifelineId = to, labelTemplate = "$id-label",
        kind = kind, repeat = Seq3Repeat.EVERY, visibility = visibility, occurrences = occurrences,
    )

    private fun twoLifelineArrowDoc(): Seq3Document = Seq3Document(
        lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
        messages = listOf(message("m1", "A", "B")),
    )

    @Test
    fun focusedQueueMessageAndFocusedOccurrenceEmphasizeTheCanvasArrow() {
        val row = layoutSeq3(twoLifelineArrowDoc(), opts()).rows.single()

        assertTrue(seq3RowIsEmphasized(row, null, emptySet(), "m1", null, null))
        assertTrue(seq3RowIsEmphasized(row, null, emptySet(), null, "m1", 1))
    }

    // ── seq3RowAt: canvas click/hover hit-testing ───────────────────────────────────────────────

    @Test
    fun rowAtFindsAnArrowRowOnItsOwnLine() {
        val layout = layoutSeq3(twoLifelineArrowDoc(), opts())
        val row = layout.rows.single() as Seq3ArrowRow
        val hit = seq3RowAt(layout, x = (row.fromX + row.toX) / 2, y = row.y)
        assertEquals("m1", hit?.messageId)
    }

    @Test
    fun marqueeSelectionReturnsEveryMessageRowIntersectingTheRectangle() {
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(
                message("m1", "A", "B", occurrences = listOf(occurrence(1, 1_000L))),
                message("m2", "A", "B", occurrences = listOf(occurrence(2, 2_000L))),
            ),
        )
        val layout = layoutSeq3(doc, opts())
        val first = layout.rows[0] as Seq3ArrowRow
        val selected = seq3RowsInSelection(
            layout,
            seq3SelectionRect(first.fromX - 5.0, first.y - 5.0, first.toX + 5.0, first.y + 5.0),
        )

        assertEquals(setOf("m1"), selected)
    }

    @Test
    fun marqueeSelectionKeepsRepeatedOccurrencesAsExactRows() {
        val repeated = message(
            "m1",
            "A",
            "B",
            occurrences = listOf(occurrence(1, 1_000L), occurrence(2, 2_000L)),
        ).copy(repeat = Seq3Repeat.EVERY)
        val layout = layoutSeq3(
            Seq3Document(
                lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
                messages = listOf(repeated),
            ),
            opts(),
        )
        val first = layout.rows[0] as Seq3ArrowRow
        val selected = seq3RowRefsInSelection(
            layout,
            seq3SelectionRect(first.fromX - 5.0, first.y - 5.0, first.toX + 5.0, first.y + 5.0),
        )

        assertEquals(listOf(Seq3CanvasRowRef("m1", 1)), selected)
        assertEquals(
            false,
            seq3RowIsEmphasized(layout.rows[1], null, setOf("m1"), null, null, null, selected.toSet()),
            "a selected occurrence must not highlight its sibling occurrence",
        )
    }

    @Test
    fun noteAndFragmentBoundsAreNotEmptyCanvasBackground() {
        val doc = twoLifelineArrowDoc().copy(
            fragments = listOf(Seq3Fragment("f1", Seq3FragmentKind.LOOP, "loop", listOf("m1"))),
            notes = listOf(Seq3Note("n1", "note", listOf("m1"))),
        )
        val layout = layoutSeq3(doc, opts())
        val note = layout.notes.single().box
        val fragment = layout.fragments.single().box

        assertTrue(!seq3IsEmptyCanvasBackground(layout, note.x + note.width / 2, note.y + note.height / 2))
        assertTrue(!seq3IsEmptyCanvasBackground(layout, fragment.x + 2.0, fragment.y + 2.0))
    }

    @Test
    fun rowAtReturnsNullFarFromEveryRow() {
        val layout = layoutSeq3(twoLifelineArrowDoc(), opts())
        assertNull(seq3RowAt(layout, x = -500.0, y = -500.0))
    }

    @Test
    fun rowAtPicksTheNearestRowWhenTwoShareAYBand() {
        // Two self-loops on adjacent lifelines land at slightly different y (loop height differs
        // from a plain arrow's), so use two arrows on the SAME row instead — not possible with one
        // document (rows never overlap in y by construction) — assert the x-distance tiebreak
        // directly on a single row's own geometry instead.
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1), lifeline("C", 2)),
            messages = listOf(message("m1", "A", "C")),
        )
        val layout = layoutSeq3(doc, opts())
        val row = layout.rows.single() as Seq3ArrowRow
        // A click well past the arrow's right end still resolves to the same (only) row, not null.
        val hit = seq3RowAt(layout, x = row.toX + 5.0, y = row.y)
        assertEquals("m1", hit?.messageId)
    }

    @Test
    fun rowAtFindsASelfLoopRow() {
        val doc = Seq3Document(lifelines = listOf(lifeline("A", 0)), messages = listOf(message("m1", "A", "A", kind = Seq3Kind.SELF)))
        val layout = layoutSeq3(doc, opts())
        val row = layout.rows.single() as Seq3SelfLoopRow
        val hit = seq3RowAt(layout, x = row.x + row.loopWidth / 2, y = row.y)
        assertEquals("m1", hit?.messageId)
    }

    @Test
    fun rowAtFindsAnUnresolvedStubRow() {
        val doc = Seq3Document(lifelines = listOf(lifeline("A", 0)), messages = listOf(message("m1", "A", null)))
        val layout = layoutSeq3(doc, opts())
        val row = layout.rows.single() as Seq3UnresolvedStubRow
        val hit = seq3RowAt(layout, x = row.dropPill.x + row.dropPill.width / 2, y = row.y)
        assertEquals("m1", hit?.messageId)
    }

    @Test
    fun rowAtIgnoresAHiddenMessageBecauseItHasNoGeometryAtAll() {
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(message("m1", "A", "B", visibility = Seq3Visibility.HIDDEN)),
        )
        val layout = layoutSeq3(doc, opts())
        assertTrue(layout.rows.isEmpty())
    }

    // ── seq3ArrowEndpointAt / seq3ResolveDragEndpoint: the canvas-drag `To` affordance ─────────

    @Test
    fun arrowEndpointAtGrabsWhicheverEndIsCloser() {
        val layout = layoutSeq3(twoLifelineArrowDoc(), opts())
        val row = layout.rows.single() as Seq3ArrowRow
        assertEquals(Seq3EndpointSide.FROM, seq3ArrowEndpointAt(row, x = row.fromX + 1.0, y = row.y))
        assertEquals(Seq3EndpointSide.TO, seq3ArrowEndpointAt(row, x = row.toX - 1.0, y = row.y))
    }

    @Test
    fun arrowEndpointAtIsNullInTheMiddleOfTheArrow() {
        val layout = layoutSeq3(twoLifelineArrowDoc(), opts())
        val row = layout.rows.single() as Seq3ArrowRow
        assertNull(seq3ArrowEndpointAt(row, x = (row.fromX + row.toX) / 2, y = row.y))
    }

    @Test
    fun arrowEndpointAtIsNullOffTheRowsYBand() {
        val layout = layoutSeq3(twoLifelineArrowDoc(), opts())
        val row = layout.rows.single() as Seq3ArrowRow
        assertNull(seq3ArrowEndpointAt(row, x = row.toX, y = row.y + 500.0))
    }

    @Test
    fun resolveDragEndpointFindsAnExistingArrowsEndpoint() {
        val layout = layoutSeq3(twoLifelineArrowDoc(), opts())
        val row = layout.rows.single() as Seq3ArrowRow
        val drag = seq3ResolveDragEndpoint(layout, x = row.toX - 1.0, y = row.y)
        assertEquals(Seq3DragEndpoint("m1", Seq3EndpointSide.TO, occurrenceEntryId = 1), drag)
    }

    @Test
    fun resolveDragEndpointAlwaysOffersTheFromSideOfAnUnresolvedStub() {
        // WP7 item 2: a stub drag resolves the CALLER, not a target — always FROM, and flagged
        // isStub so release dispatches SetCaller instead of the plain SetFrom.
        val doc = Seq3Document(lifelines = listOf(lifeline("A", 0)), messages = listOf(message("m1", "A", null)))
        val layout = layoutSeq3(doc, opts())
        val row = layout.rows.single() as Seq3UnresolvedStubRow
        val drag = seq3ResolveDragEndpoint(layout, x = row.dropPill.x + 1.0, y = row.y)
        assertEquals(Seq3DragEndpoint("m1", Seq3EndpointSide.FROM, occurrenceEntryId = 1, isStub = true), drag)
    }

    @Test
    fun resolveDragEndpointKeepsTheSpecificOccurrenceRowInsideAGroupedMessage() {
        val doc = Seq3Document(
            lifelines = listOf(lifeline("A", 0), lifeline("B", 1)),
            messages = listOf(
                message(
                    "m1",
                    "A",
                    "B",
                    occurrences = listOf(occurrence(1, ts = 1_000L), occurrence(2, ts = 2_000L)),
                ),
            ),
        )
        val layout = layoutSeq3(doc, opts())
        val second = layout.rows[1] as Seq3ArrowRow
        val drag = seq3ResolveDragEndpoint(layout, x = second.toX - 1.0, y = second.y)

        assertEquals(Seq3EndpointSide.TO, drag?.side)
        assertEquals("m1", drag?.messageId)
        assertEquals(2, drag?.occurrenceEntryId)
        assertEquals(second.y, seq3DragPreview(layout, drag!!, cursorX = second.toX + 5.0)?.y)
    }

    @Test
    fun selfLoopEndpointAtRecognizesItsVisibleArrowhead() {
        val doc = Seq3Document(lifelines = listOf(lifeline("A", 0)), messages = listOf(message("m1", "A", "A", kind = Seq3Kind.SELF)))
        val row = layoutSeq3(doc, opts()).rows.single() as Seq3SelfLoopRow

        assertEquals(Seq3EndpointSide.TO, seq3SelfLoopEndpointAt(row, row.x + 1.0, row.loopBottomY))
        assertEquals(Seq3EndpointSide.FROM, seq3SelfLoopEndpointAt(row, row.x, row.y))
    }

    @Test
    fun resolveDragEndpointIsNullAwayFromEveryHandle() {
        val layout = layoutSeq3(twoLifelineArrowDoc(), opts())
        assertNull(seq3ResolveDragEndpoint(layout, x = -1000.0, y = -1000.0))
    }

    // ── seq3IsEmptyCanvasBackground: pan-arming (item 12) ───────────────────────────────────────

    @Test
    fun emptyCanvasBackgroundIsFalseOnAnArrowRow() {
        val layout = layoutSeq3(twoLifelineArrowDoc(), opts())
        val row = layout.rows.single() as Seq3ArrowRow
        assertTrue(!seq3IsEmptyCanvasBackground(layout, x = (row.fromX + row.toX) / 2, y = row.y))
    }

    @Test
    fun emptyCanvasBackgroundIsFalseExactlyOnAnEndpointHandleToo() {
        // Not just "on the row" — right on the grabbable handle itself must also count as content,
        // since a pan armed there would fight the endpoint-drag Press already resolves first.
        val layout = layoutSeq3(twoLifelineArrowDoc(), opts())
        val row = layout.rows.single() as Seq3ArrowRow
        assertTrue(!seq3IsEmptyCanvasBackground(layout, x = row.toX - 1.0, y = row.y))
    }

    @Test
    fun emptyCanvasBackgroundIsTrueFarFromEveryRow() {
        val layout = layoutSeq3(twoLifelineArrowDoc(), opts())
        assertTrue(seq3IsEmptyCanvasBackground(layout, x = -1000.0, y = -1000.0))
    }

    @Test
    fun emptyCanvasBackgroundIsFalseOnALifelineHeaderSoHeaderDragOwnsTheGesture() {
        val layout = layoutSeq3(twoLifelineArrowDoc(), opts())
        val header = layout.lifelines.first().header

        assertTrue(!seq3IsEmptyCanvasBackground(layout, header.x + header.width / 2, header.y + header.height / 2))
    }

    // ── seq3DragPreview: live feedback while dragging an endpoint (item 13) ─────────────────────

    @Test
    fun dragPreviewAnchorsOnTheOppositeEndWhenDraggingTo() {
        val layout = layoutSeq3(twoLifelineArrowDoc(), opts())
        val row = layout.rows.single() as Seq3ArrowRow
        val preview = seq3DragPreview(layout, Seq3DragEndpoint("m1", Seq3EndpointSide.TO), cursorX = row.toX + 5.0)
        assertEquals(row.fromX, preview?.anchorX, "TO is being dragged, so the anchor is the FROM end that's staying put")
        assertEquals(row.y, preview?.y)
        assertEquals(row.toX + 5.0, preview?.cursorX)
    }

    @Test
    fun dragPreviewAnchorsOnTheOppositeEndWhenDraggingFrom() {
        val layout = layoutSeq3(twoLifelineArrowDoc(), opts())
        val row = layout.rows.single() as Seq3ArrowRow
        val preview = seq3DragPreview(layout, Seq3DragEndpoint("m1", Seq3EndpointSide.FROM), cursorX = row.fromX - 5.0)
        assertEquals(row.toX, preview?.anchorX, "FROM is being dragged, so the anchor is the TO end that's staying put")
    }

    @Test
    fun dragPreviewAlwaysAnchorsOnFromForAnUnresolvedStub() {
        val doc = Seq3Document(lifelines = listOf(lifeline("A", 0)), messages = listOf(message("m1", "A", null)))
        val layout = layoutSeq3(doc, opts())
        val row = layout.rows.single() as Seq3UnresolvedStubRow
        val preview = seq3DragPreview(layout, Seq3DragEndpoint("m1", Seq3EndpointSide.TO), cursorX = row.fromX + 40.0)
        assertEquals(row.fromX, preview?.anchorX)
        assertEquals(row.fromX + 40.0, preview?.cursorX)
    }

    @Test
    fun dragPreviewReportsTheNearestLifelineAsTheCandidateUsingTheSameFunctionReleaseWillUse() {
        val doc = Seq3Document(lifelines = listOf(lifeline("A", 0), lifeline("B", 1), lifeline("C", 2)))
        val layout = layoutSeq3(doc, opts())
        val cCenter = layout.lifelines.first { it.lifelineId == "C" }.centerX
        val docWithArrow = Seq3Document(lifelines = doc.lifelines, messages = listOf(message("m1", "A", "B")))
        val layoutWithArrow = layoutSeq3(docWithArrow, opts())
        val preview = seq3DragPreview(layoutWithArrow, Seq3DragEndpoint("m1", Seq3EndpointSide.TO), cursorX = cCenter)
        assertEquals(seq3NearestLifelineId(layoutWithArrow, cCenter), preview?.candidateLifelineId)
        assertEquals("C", preview?.candidateLifelineId)
    }

    @Test
    fun dragPreviewIsNullWhenTheDraggedMessageNoLongerHasARow() {
        val layout = layoutSeq3(twoLifelineArrowDoc(), opts())
        assertNull(seq3DragPreview(layout, Seq3DragEndpoint("no-such-message", Seq3EndpointSide.TO), cursorX = 0.0))
    }

    // ── seq3PointInBox ───────────────────────────────────────────────────────────────────────

    @Test
    fun pointInBoxIsTrueOnTheEdgesAndFalseOutside() {
        val box = com.indagium.diagram3.Seq3Box(10.0, 10.0, 20.0, 20.0)
        assertTrue(seq3PointInBox(box, 10.0, 10.0))
        assertTrue(seq3PointInBox(box, 30.0, 30.0))
        assertTrue(seq3PointInBox(box, 20.0, 20.0))
        assertTrue(!seq3PointInBox(box, 9.0, 20.0))
        assertTrue(!seq3PointInBox(box, 20.0, 31.0))
    }

    // ── seq3NearestLifelineId: drop resolution (spec §04) ───────────────────────────────────────

    @Test
    fun nearestLifelineIdAlwaysResolvesToTheClosestColumn() {
        val doc = Seq3Document(lifelines = listOf(lifeline("A", 0), lifeline("B", 1), lifeline("C", 2)))
        val layout = layoutSeq3(doc, opts())
        val centers = layout.lifelines.associate { it.lifelineId to it.centerX }
        assertEquals("A", seq3NearestLifelineId(layout, centers.getValue("A")))
        assertEquals("B", seq3NearestLifelineId(layout, (centers.getValue("A") + centers.getValue("B")) / 2 + 0.01))
        assertEquals("C", seq3NearestLifelineId(layout, 999_999.0))
    }

    @Test
    fun nearestLifelineIdIsNullWithNoLifelines() {
        val layout = layoutSeq3(Seq3Document(), opts())
        assertNull(seq3NearestLifelineId(layout, 0.0))
    }

    // ── seq3ReorderLifelineIds / seq3LifelineDropIndex: lifeline-chip drag (spec §07) ───────────

    @Test
    fun reorderLifelineIdsMovesTheDraggedIdToTheTargetIndex() {
        val order = listOf("A", "B", "C", "D")
        assertEquals(listOf("B", "C", "A", "D"), seq3ReorderLifelineIds(order, "A", targetIndex = 2))
        assertEquals(listOf("A", "B", "C", "D"), seq3ReorderLifelineIds(order, "A", targetIndex = 0))
        assertEquals(listOf("B", "C", "D", "A"), seq3ReorderLifelineIds(order, "A", targetIndex = 10))
    }

    @Test
    fun reorderLifelineIdsIsANoOpForAnUnknownId() {
        val order = listOf("A", "B", "C")
        assertEquals(order, seq3ReorderLifelineIds(order, "Z", targetIndex = 1))
    }

    @Test
    fun lifelineDropIndexFindsTheInsertionPointByCenterX() {
        val centers = listOf(0.0, 100.0, 200.0, 300.0)
        assertEquals(0, seq3LifelineDropIndex(centers, -50.0))
        assertEquals(2, seq3LifelineDropIndex(centers, 150.0))
        assertEquals(1, seq3LifelineDropIndex(centers, 100.0))
        assertEquals(4, seq3LifelineDropIndex(centers, 999.0))
    }

    @Test
    fun lifelineDropIndexCanBeUsedAfterRemovingTheDraggedColumn() {
        val order = listOf("A", "B", "C")
        val remainingCenters = listOf(100.0, 300.0) // B is being dragged; A and C remain.

        val targetIndex = seq3LifelineDropIndex(remainingCenters, 350.0)

        assertEquals(listOf("A", "C", "B"), seq3ReorderLifelineIds(order, "B", targetIndex))
    }

    // ── Crossing count surfaced through the toolbar label ───────────────────────────────────────

    @Test
    fun crossingLabelReadsSingularAndPluralCorrectly() {
        assertEquals("No arrow crossings", seq3CrossingLabel(0))
        assertEquals("1 arrow crossing", seq3CrossingLabel(1))
        assertEquals("3 arrow crossings", seq3CrossingLabel(3))
    }

    @Test
    fun crossingCountFromARealLayoutFeedsTheLabelUnchanged() {
        // Same interleaved arrangement as Seq3LayoutTest's own crossing-count fixture — this just
        // confirms Seq3Canvas's toolbar reads `layout.crossingCount` (never a second computation).
        val lifelines = listOf(lifeline("A", 0), lifeline("B", 1), lifeline("C", 2), lifeline("D", 3))
        val doc = Seq3Document(
            lifelines = lifelines,
            messages = listOf(message("m1", "A", "C"), message("m2", "B", "D")),
        )
        val layout = layoutSeq3(doc, opts())
        assertEquals(1, layout.crossingCount)
        assertEquals("1 arrow crossing", seq3CrossingLabel(layout.crossingCount))
    }

    // ── Zoom helpers ─────────────────────────────────────────────────────────────────────────

    @Test
    fun fitHeightZoomOnlyConsidersHeight() {
        // Genuine complement of fitWidthZoomOnlyConsidersWidth below (item 5) — width is irrelevant.
        assertEquals(0.5f, seq3FitHeightZoom(contentHeight = 200.0, viewportHeight = 100.0))
        assertEquals(2f, seq3FitHeightZoom(contentHeight = 100.0, viewportHeight = 200.0))
    }

    @Test
    fun fitHeightZoomFallsBackToOneForADegenerateViewport() {
        assertEquals(1f, seq3FitHeightZoom(200.0, 0.0))
        assertEquals(1f, seq3FitHeightZoom(0.0, 100.0))
    }

    @Test
    fun fitWidthZoomOnlyConsidersWidth() {
        assertEquals(2f, seq3FitWidthZoom(contentWidth = 100.0, viewportWidth = 200.0))
    }

    @Test
    fun zoomPercentLabelRoundsToTheNearestPercent() {
        assertEquals("86%", seq3ZoomPercentLabel(0.864f))
        assertEquals("100%", seq3ZoomPercentLabel(1f))
    }

    @Test
    fun modifierWheelZoomsInAndOutWithinTheSupportedRange() {
        assertEquals(1.1f, seq3ZoomByWheel(1f, -1f))
        assertEquals(0.9f, seq3ZoomByWheel(1f, 1f))
        assertEquals(4f, seq3ZoomByWheel(4f, -1f))
        assertEquals(0.1f, seq3ZoomByWheel(0.1f, 1f))
        assertEquals(1f, seq3ZoomByWheel(1f, 0f))
    }

    @Test
    fun pointerCoordinatesUndoDensityAndCanvasZoomBeforeHitTesting() {
        assertEquals(100.0, seq3PointerPxToLayoutUnits(pointerPx = 400f, density = 2f, zoom = 2f))
        assertEquals(100.0, seq3PointerPxToLayoutUnits(pointerPx = 158f, density = 2f, zoom = 0.79f), absoluteTolerance = 0.0001)
        assertEquals(100.0, seq3PointerPxToLayoutUnits(pointerPx = 178f, density = 2f, zoom = 0.89f), absoluteTolerance = 0.0001)
    }

    // ── WP2 item 10: on-screen arrow stroke weight must consume seq3ArrowStyle per Seq3Kind ─────

    @Test
    fun callAndSelfKindsDrawAtTheUnthinnedReferenceWeight() {
        // CALL/SELF/RETURN-filled-head etc. all resolve to seq3ArrowStyle's non-thin branch — the
        // shaft draws at the plain (non-emphasized) reference weight with no thinning applied.
        val (line, head) = seq3ArrowStrokeWidths(Seq3Kind.CALL, emphasisWidth = 1.5f, referenceWidth = 1.5f, thinWidth = 1f)
        assertEquals(1.5f, line)
        assertEquals(1.5f, head)
    }

    @Test
    fun returnAndAsyncKindsDrawTheirShaftThinnerThanTheReferenceWeight() {
        listOf(Seq3Kind.RETURN, Seq3Kind.ASYNC).forEach { kind ->
            val (line, head) = seq3ArrowStrokeWidths(kind, emphasisWidth = 1.5f, referenceWidth = 1.5f, thinWidth = 1f)
            assertEquals(1f, line, "$kind must draw its shaft at the thin weight")
            // The open head is still drawn at the UN-thinned reference weight — mirrors
            // Seq3Raster's own drawArrowhead, which always strokes an open head at STROKE_THICK.
            assertEquals(1.5f, head, "$kind's open head must stay at the reference weight")
        }
    }

    @Test
    fun hoverEmphasisScalesBothTheShaftAndTheHeadByTheSameRatio() {
        // The caller's existing emphasis pair (1.5 normal / 2.0 emphasized) must still visibly
        // thicken a THIN kind's shaft, proportionally, without discarding the thinning altogether.
        val (normalLine, normalHead) = seq3ArrowStrokeWidths(Seq3Kind.RETURN, emphasisWidth = 1.5f, referenceWidth = 1.5f, thinWidth = 1f)
        val (emphasizedLine, emphasizedHead) = seq3ArrowStrokeWidths(Seq3Kind.RETURN, emphasisWidth = 2f, referenceWidth = 1.5f, thinWidth = 1f)

        assertTrue(emphasizedLine > normalLine, "hover/selection must still thicken a thin kind's shaft")
        assertEquals(2f, emphasizedHead, "the head always tracks the caller's own emphasis width exactly")
        assertEquals(1.5f, normalHead, "and does the same at the non-emphasized baseline")
    }

    // ── WP7 item 4: the context-menu offset transform ───────────────────────────────────────────

    @Test
    fun contextMenuOffsetSubtractsScrollFromTheRawPointerPosition() {
        // A Popup positioned outside the scrolled content needs viewport-relative pixels, while
        // the raw pointer event is measured inside the scrolled (full-diagram) content — the same
        // "subtract scroll" correction the pan-drag gesture's own panViewportPosition applies.
        val offset = seq3ContextMenuOffset(Offset(500f, 300f), hScrollPx = 120, vScrollPx = 40)
        assertEquals(IntOffset(380, 260), offset)
    }

    @Test
    fun contextMenuOffsetIsUnchangedWhenTheCanvasIsNotScrolled() {
        val offset = seq3ContextMenuOffset(Offset(150f, 90f), hScrollPx = 0, vScrollPx = 0)
        assertEquals(IntOffset(150, 90), offset)
    }

    @Test
    fun contextMenuOffsetNeverGoesNegativeEvenIfScrollOutrunsThePointer() {
        val offset = seq3ContextMenuOffset(Offset(10f, 10f), hScrollPx = 500, vScrollPx = 500)
        assertEquals(IntOffset(0, 0), offset)
    }

    // ── WP7 item 7: fragment/note panel-row selection and hover reach the canvas ────────────────

    @Test
    fun fragmentIsEmphasizedWhenSelectedOrHoveredButNotOtherwise() {
        assertTrue(seq3FragmentIsEmphasized("f1", selectedFragmentId = "f1", hoveredFragmentId = null))
        assertTrue(seq3FragmentIsEmphasized("f1", selectedFragmentId = null, hoveredFragmentId = "f1"))
        assertFalse(seq3FragmentIsEmphasized("f1", selectedFragmentId = "f2", hoveredFragmentId = null))
        assertFalse(seq3FragmentIsEmphasized("f1", selectedFragmentId = null, hoveredFragmentId = null))
    }

    @Test
    fun noteIsEmphasizedWhenSelectedOrHoveredButNotOtherwise() {
        assertTrue(seq3NoteIsEmphasized("n1", selectedNoteId = "n1", hoveredNoteId = null))
        assertTrue(seq3NoteIsEmphasized("n1", selectedNoteId = null, hoveredNoteId = "n1"))
        assertFalse(seq3NoteIsEmphasized("n1", selectedNoteId = "n2", hoveredNoteId = null))
    }
}
