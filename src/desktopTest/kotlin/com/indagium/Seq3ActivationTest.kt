package com.indagium

import com.indagium.diagram3.Seq3ActivationEvent
import com.indagium.diagram3.Seq3ActivationSpan
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3ResolvedManualActivation
import com.indagium.diagram3.seq3ActivationSpans
import com.indagium.diagram3.seq3IsValidManualActivationEnd
import com.indagium.diagram3.seq3ManualActivationEndLimit
import com.indagium.diagram3.seq3MergedActivationSpans
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure logic, so unlike most of this package's other tests there is no `Seq3TextMetrics` stub
 *  and no `Seq3Document` fixture here — just [Seq3ActivationEvent] lists built inline per test,
 *  matching this suite's own "no shared builder" convention (each file owns its own fixtures). */
class Seq3ActivationTest {
    // A `CALL` with no target lifeline is what "no push" means in the table in
    // seq3ActivationSpans's own doc — folded into every fixture below via a small helper so each
    // test only spells out what actually matters for it.
    private fun call(index: Int, from: String, to: String?, messageId: String = "m$index") =
        Seq3ActivationEvent(index, messageId, Seq3Kind.CALL, from, to)

    private fun ret(index: Int, from: String, to: String? = null, messageId: String = "m$index") =
        Seq3ActivationEvent(index, messageId, Seq3Kind.RETURN, from, toLifelineId = to)

    private fun neutral(index: Int, kind: Seq3Kind, from: String, to: String? = null, messageId: String = "m$index") =
        Seq3ActivationEvent(index, messageId, kind, from, to)

    @Test
    fun balancedCallAndReturnProduceOneClosedSpan() {
        val events = listOf(
            call(0, from = "A", to = "B"),
            ret(1, from = "B"),
        )

        val spans = seq3ActivationSpans(events, lastIndex = 1)

        assertEquals(listOf(Seq3ActivationSpan("B", startIndex = 0, endIndex = 1, depth = 0, unmatched = false)), spans)
    }

    @Test
    fun twoLevelNestingOnTheSameLifelineProducesDepthZeroAndDepthOne() {
        // B calls back into itself (or is re-entered) before its first call returns: the outer
        // span opens at depth 0, the inner reentrant one at depth 1, and they close inner-first.
        val events = listOf(
            // Outer open, depth 0.
            call(0, from = "A", to = "B"),
            // Inner open, depth 1.
            call(1, from = "B", to = "B"),
            // Closes the inner, depth-1 frame.
            ret(2, from = "B"),
            // Closes the outer, depth-0 frame.
            ret(3, from = "B"),
        )

        val spans = seq3ActivationSpans(events, lastIndex = 3)

        assertEquals(
            listOf(
                Seq3ActivationSpan("B", startIndex = 0, endIndex = 3, depth = 0, unmatched = false),
                Seq3ActivationSpan("B", startIndex = 1, endIndex = 2, depth = 1, unmatched = false),
            ),
            spans,
            "the outer call (pushed first, popped last) must report depth 0; the reentrant inner call, depth 1",
        )
    }

    @Test
    fun targetedReturnsCloseTheMostRecentFrameForTheirExactCallerAndCallee() {
        // Two callers invoke B before either return. A return explicitly addressed to A must
        // close A's older frame, rather than blindly popping C's newer frame from B's stack.
        val events = listOf(
            call(0, from = "A", to = "B"),
            call(1, from = "C", to = "B"),
            ret(2, from = "B", to = "A"),
            ret(3, from = "B", to = "C"),
        )

        val spans = seq3ActivationSpans(events, lastIndex = 3)

        assertEquals(
            listOf(
                Seq3ActivationSpan("B", startIndex = 0, endIndex = 2, depth = 0, unmatched = false),
                Seq3ActivationSpan("B", startIndex = 1, endIndex = 3, depth = 1, unmatched = false),
            ),
            spans,
        )
    }

    @Test
    fun targetedReturnWithoutAMatchingCallerIsANoOpAndDoesNotCloseAnotherFrame() {
        val events = listOf(
            call(0, from = "A", to = "B"),
            call(1, from = "C", to = "B"),
            // No B frame was opened by D.
            ret(2, from = "B", to = "D"),
            // Compatibility path: closes C's most recent frame.
            ret(3, from = "B"),
        )

        val spans = seq3ActivationSpans(events, lastIndex = 3)

        assertEquals(
            listOf(
                Seq3ActivationSpan("B", startIndex = 0, endIndex = 3, depth = 0, unmatched = true),
                Seq3ActivationSpan("B", startIndex = 1, endIndex = 3, depth = 1, unmatched = false),
            ),
            spans,
            "the unmatched targeted return must not steal C's frame; a null target remains legacy LIFO",
        )
    }

    @Test
    fun anUnmatchedCallIsFlaggedAndClosedAtItsLifelinesLastTouchingIndex() {
        // B never returns. The lifeline's last touching row is the call itself (index 0) unless
        // something else later touches B — here, an unrelated call FROM B extends that to index 2.
        val events = listOf(
            call(0, from = "A", to = "B"),
            // Does not touch B at all.
            call(1, from = "X", to = "Y"),
            // Touches B as sender, so this is now B's last touch.
            call(2, from = "B", to = "C"),
        )

        val spans = seq3ActivationSpans(events, lastIndex = 10)

        val bSpan = spans.single { it.lifelineId == "B" }
        assertEquals(Seq3ActivationSpan("B", startIndex = 0, endIndex = 2, depth = 0, unmatched = true), bSpan)
    }

    @Test
    fun anUnmatchedCallWithNoOtherTouchOnItsLifelineClosesAtItsOwnOpeningRow() {
        // The call that opens a span always itself touches its lifeline (as `toLifelineId`), so
        // when nothing later touches "Z" again, its own opening index IS the last touching index
        // — this is the ordinary (reachable) case; `lastIndex` is a defensive fallback for a
        // lifeline `seq3ActivationSpans`'s own doc notes is not expected to be reachable from real
        // input (see that doc for why: a lifeline can only ever be in `openStacks` because a CALL
        // event touching it pushed onto it, which means it is also always in `lastTouchIndex`).
        val events = listOf(call(0, from = "A", to = "Z"))

        val spans = seq3ActivationSpans(events, lastIndex = 99)

        assertEquals(listOf(Seq3ActivationSpan("Z", startIndex = 0, endIndex = 0, depth = 0, unmatched = true)), spans)
    }

    @Test
    fun anUnmatchedReturnIsSilentlyDroppedRatherThanThrowingOrOpeningAnything() {
        val events = listOf(ret(0, from = "B"))

        val spans = seq3ActivationSpans(events, lastIndex = 5)

        assertTrue(spans.isEmpty(), "a return with nothing open on its lifeline must open and close nothing")
    }

    @Test
    fun pushesPastTheDepthCapAreDroppedInsteadOfNestingFurther() {
        // 9 unmatched calls into "B": the cap (8) admits depths 0..7 and silently drops the 9th
        // push, so only 8 spans should exist for B, and no span reports depth 8.
        val events = (0 until 9).map { i -> call(i, from = "A", to = "B") }

        val spans = seq3ActivationSpans(events, lastIndex = 8)

        assertEquals(8, spans.size, "the 9th call must be dropped rather than opening a 9th nested span")
        assertEquals((0..7).toSet(), spans.map { it.depth }.toSet(), "depths must run 0..7 with nothing beyond the cap")
    }

    @Test
    fun aFirstLastStyleDoublePushIsAbsorbedAsTwoUnmatchedSpansRatherThanReconciled() {
        // Simulates Seq3Repeat.FIRST_LAST: one message contributes two CALL rows (first + last
        // occurrence), each its own event per this function's "per row, not per message" contract
        // — with only one RETURN row in the expansion, one push must stay open and unmatched.
        val events = listOf(
            call(0, from = "A", to = "B", messageId = "m1"),
            call(1, from = "A", to = "B", messageId = "m1"),
            ret(2, from = "B", messageId = "m1"),
        )

        val spans = seq3ActivationSpans(events, lastIndex = 2)

        assertEquals(2, spans.size, "both pushes must produce a span even though only one RETURN row exists")
        val closed = spans.single { !it.unmatched }
        val open = spans.single { it.unmatched }
        assertEquals(
            Seq3ActivationSpan("B", startIndex = 1, endIndex = 2, depth = 1, unmatched = false),
            closed,
            "the RETURN pops the most recently pushed (top) call",
        )
        assertEquals(
            Seq3ActivationSpan("B", startIndex = 0, endIndex = 2, depth = 0, unmatched = true),
            open,
            "the older call is left open and closes at the lifeline's last touching row",
        )
    }

    @Test
    fun asyncSelfAndNoteAreNeutralAndNeverOpenOrCloseASpan() {
        val events = listOf(
            neutral(0, Seq3Kind.ASYNC, from = "A", to = "B"),
            neutral(1, Seq3Kind.SELF, from = "A", to = "A"),
            neutral(2, Seq3Kind.NOTE, from = "A", to = null),
        )

        val spans = seq3ActivationSpans(events, lastIndex = 2)

        assertTrue(spans.isEmpty(), "ASYNC/SELF/NOTE must never push or pop")
    }

    @Test
    fun aCallWithNoResolvedTargetIsANoOp() {
        // A message that still needs a target (toLifelineId == null) cannot open a bar on a
        // lifeline that doesn't exist for it yet.
        val events = listOf(call(0, from = "A", to = null), ret(1, from = "A"))

        val spans = seq3ActivationSpans(events, lastIndex = 1)

        assertTrue(spans.isEmpty(), "a null-target CALL must not push, so the later RETURN also finds nothing and is dropped")
    }

    // ── Manual activation merge/nesting (phase 1) ───────────────────────────────────────────

    private fun manual(id: String, lifelineId: String, start: Int, end: Int) = Seq3ResolvedManualActivation(id, lifelineId, start, end)

    @Test
    fun mergingWithNoManualActivationsReturnsTheAutoSpansUnchangedWithNullManualId() {
        val auto = listOf(Seq3ActivationSpan("B", 0, 1, depth = 0, unmatched = false))

        val merged = seq3MergedActivationSpans(auto, emptyList(), lastIndex = 1)

        assertEquals(auto, merged)
        assertNull(merged.single().manualId)
    }

    @Test
    fun aManualSpanWithNothingElseOnItsLifelineNestsAtDepthZeroAndCarriesItsId() {
        val merged = seq3MergedActivationSpans(emptyList(), listOf(manual("bar1", "B", 0, 3)), lastIndex = 3)

        assertEquals(listOf(Seq3ActivationSpan("B", 0, 3, depth = 0, unmatched = false, manualId = "bar1")), merged)
    }

    @Test
    fun aManualSpanStartingInsideAnAutoSpanNestsOneLevelDeeper() {
        val auto = listOf(Seq3ActivationSpan("B", 0, 5, depth = 0, unmatched = false))

        val merged = seq3MergedActivationSpans(auto, listOf(manual("bar1", "B", 1, 4)), lastIndex = 5)

        val inner = merged.single { it.manualId == "bar1" }
        assertEquals(1, inner.depth, "a manual bar starting inside the auto span must nest one level deeper")
        assertEquals(4, inner.endIndex, "the manual bar fits entirely inside its enclosing span, so no clamp is needed")
    }

    @Test
    fun aManualSpanThatCrossesAnEnclosingSpanIsClampedToTheEnclosingSpansEnd() {
        val auto = listOf(Seq3ActivationSpan("B", 0, 3, depth = 0, unmatched = false))

        val merged = seq3MergedActivationSpans(auto, listOf(manual("bar1", "B", 1, 10)), lastIndex = 10)

        val inner = merged.single { it.manualId == "bar1" }
        assertEquals(3, inner.endIndex, "a manual span crossing its enclosing span must clamp to the enclosing span's own end")
        assertEquals(1, inner.depth)
    }

    @Test
    fun aManualSpanWithANullEndLosesToDiagramEndOnceCrossingClampsItToAnEarlierRow() {
        // Phase 2 fix: `toDiagramEnd` means "draw down to the lifeline's own bottom", which is only
        // true once this span's endIndex genuinely reflects the diagram's own last emission. Once a
        // crossing clamps that endIndex down to an enclosing span's own end (3, here — well short of
        // the diagram's real lastIndex of 10), the clamped span's bottom IS that row, not the
        // lifeline's bottom — so the clamp must also turn `toDiagramEnd` off, or Seq3Layout.kt would
        // draw the (now-shorter) bar all the way to the lifeline's bottom anyway.
        val auto = listOf(Seq3ActivationSpan("B", 0, 3, depth = 0, unmatched = false))
        val nullEnded = Seq3ResolvedManualActivation("bar1", "B", 1, 10, toDiagramEnd = true)

        val merged = seq3MergedActivationSpans(auto, listOf(nullEnded), lastIndex = 10)

        val inner = merged.single { it.manualId == "bar1" }
        assertEquals(3, inner.endIndex, "clamped to the enclosing span's own end")
        assertFalse(inner.toDiagramEnd, "a clamped span's bottom is the row it was clamped to, not the diagram's own end")
    }

    @Test
    fun anAutoSpanIsNeverClampedEvenWhenItWouldCrossAnEnclosingManualSpan() {
        // A manual bar opens first (0..3) on B; an auto CALL/RETURN pair nests inside it (starts at
        // 1) but its own RETURN lands at 8, past the manual bar's own end — per this function's own
        // doc, only a MANUAL span is ever clamped; the auto span's own call/return pairing is
        // authoritative and must survive untouched, even though this technically makes the drawn
        // bars overlap rather than nest.
        val auto = listOf(Seq3ActivationSpan("B", 1, 8, depth = 0, unmatched = false))

        val merged = seq3MergedActivationSpans(auto, listOf(manual("bar1", "B", 0, 3)), lastIndex = 8)

        val autoResult = merged.single { it.manualId == null }
        assertEquals(8, autoResult.endIndex, "an auto span must never be clamped, even when it crosses an enclosing manual span")
    }

    @Test
    fun autoSortsBeforeManualOnAnExactStartAndEndTie() {
        val auto = listOf(Seq3ActivationSpan("B", 0, 5, depth = 0, unmatched = false))

        val merged = seq3MergedActivationSpans(auto, listOf(manual("bar1", "B", 0, 5)), lastIndex = 5)

        assertEquals(0, merged.single { it.manualId == null }.depth, "auto sorts before manual on an exact start+end tie")
        assertEquals(1, merged.single { it.manualId == "bar1" }.depth)
    }

    @Test
    fun anAutoSpanStartingInsideAManualSpanAndEndingAfterItClampsTheManualInsteadOfTheAuto() {
        // manual[2,5] opens first; auto[4,8] starts inside it but its own end (8) escapes past the
        // manual's end (5) — the auto span must stay completely untouched (still 8, depth 0, no
        // longer enclosed by anything) and the MANUAL span must shrink instead, to end right before
        // the auto span begins (3). This is the reverse of the "manual crosses an enclosing auto"
        // case above: here the manual is the one that OPENED first / would-be enclosing.
        val manualEntry = manual("bar1", "B", 2, 5)
        val auto = listOf(Seq3ActivationSpan("B", 4, 8, depth = 0, unmatched = false))

        val merged = seq3MergedActivationSpans(auto, listOf(manualEntry), lastIndex = 8)

        val manualResult = merged.single { it.manualId == "bar1" }
        val autoResult = merged.single { it.manualId == null }
        assertEquals(3, manualResult.endIndex, "the manual span must shrink to end right before the auto span it can no longer enclose")
        assertEquals(8, autoResult.endIndex, "the auto span's own end must never be touched")
        assertEquals(0, autoResult.depth, "once the manual no longer encloses it, the auto span is no longer nested under anything")
        assertTrue(manualResult.endIndex < autoResult.startIndex, "the two spans must end up disjoint, never partially overlapping")
    }

    @Test
    fun aManualSpanTiedWithAnAutoSpanAtTheSameStartIndexNestsCleanlyWithoutClampingEither() {
        // Same start index: the tie-break already treats the LONGER span (here, the auto one) as
        // the enclosing one, so this must resolve as clean containment, never a crossing that
        // clamps anything — the crossing pre-pass must not mistake a tied start for a partial
        // overlap just because it shares an index with the auto span.
        val manualEntry = manual("bar1", "B", 3, 6)
        val auto = listOf(Seq3ActivationSpan("B", 3, 10, depth = 0, unmatched = false))

        val merged = seq3MergedActivationSpans(auto, listOf(manualEntry), lastIndex = 10)

        val manualResult = merged.single { it.manualId == "bar1" }
        val autoResult = merged.single { it.manualId == null }
        assertEquals(10, autoResult.endIndex, "the auto span must be untouched")
        assertEquals(0, autoResult.depth, "the longer (auto) span at the tied start is the outer one")
        assertEquals(6, manualResult.endIndex, "the manual span fits entirely inside the auto one, so no clamp is needed")
        assertEquals(1, manualResult.depth)
    }

    @Test
    fun chainedCrossingsStabilizeAfterMultiplePasses() {
        // A single small auto span forces manual bar m1 to shrink (m1 crosses the auto span
        // directly); that shrink then reveals a SECOND crossing against m2, a manual bar that
        // originally looked perfectly nested inside m1's much longer ORIGINAL span. The fixed
        // point must keep re-scanning until it settles on m1's TRUE final end, not whatever a
        // single pass happens to compute depending on iteration order.
        val auto = listOf(Seq3ActivationSpan("B", 10, 12, depth = 0, unmatched = false))
        val m1 = manual("m1", "B", 0, 11)
        val m2 = manual("m2", "B", 5, 20)

        val merged = seq3MergedActivationSpans(auto, listOf(m1, m2), lastIndex = 20)

        val autoResult = merged.single { it.manualId == null }
        val m1Result = merged.single { it.manualId == "m1" }
        val m2Result = merged.single { it.manualId == "m2" }
        assertEquals(12, autoResult.endIndex, "the auto span must never be touched")
        assertEquals(0, autoResult.depth, "once m1 shrinks out of its way, the auto span is no longer enclosed by anything")
        assertEquals(9, m1Result.endIndex, "m1 crosses the auto span directly and must clamp to just before it")
        assertEquals(0, m1Result.depth)
        assertEquals(9, m2Result.endIndex, "m2's own crossing is with m1, not the auto span directly — it must settle at m1's FINAL (already-shrunk) end, not m1's original one")
        assertEquals(1, m2Result.depth, "m2 still nests inside m1's shrunk span")
    }

    @Test
    fun spansPastTheDepthCapAreDroppedFromTheMergedResultToo() {
        // 9 manual bars, all opening/closing at the exact same indices on the same lifeline: only
        // the first SEQ3_MAX_ACTIVATION_DEPTH (8) may nest, mirroring seq3ActivationSpans' own
        // depth-cap rule (rule 3) for the auto pairing.
        val manualEntries = (0 until 9).map { i -> manual("bar$i", "B", 0, 8) }

        val merged = seq3MergedActivationSpans(emptyList(), manualEntries, lastIndex = 8)

        assertEquals(8, merged.size, "the 9th manual bar must be dropped rather than nesting an 8th level deep")
        assertEquals((0..7).toSet(), merged.map { it.depth }.toSet(), "depths must run 0..7 with nothing beyond the cap")
    }

    // ── seq3ManualActivationEndLimit ─────────────────────────────────────────────────────────

    @Test
    fun endLimitReachesTheLastIndexWhenNothingEncloses() {
        val limit = seq3ManualActivationEndLimit(emptyList(), emptyList(), "B", startIndex = 2, lastIndex = 10)

        assertEquals(10, limit)
    }

    @Test
    fun endLimitStopsAtTheEnclosingSpansOwnEnd() {
        val auto = listOf(Seq3ActivationSpan("B", 0, 6, depth = 0, unmatched = false))

        val limit = seq3ManualActivationEndLimit(auto, emptyList(), "B", startIndex = 2, lastIndex = 20)

        assertEquals(6, limit, "a bar starting inside an enclosing span may never drag past that span's own end")
    }

    @Test
    fun endLimitWithExclusionMeasuresAgainstTheEnclosingSpanNotTheBarsOwnStaleSpan() {
        // Resizing bar1 (currently 2..4) against an outer bar (0..20): excluding bar1's own id from
        // the probe means only the OUTER bar's own end constrains how far it may now be dragged,
        // not its own not-yet-updated (and, mid-drag, stale) current span.
        val outer = manual("outer", "B", 0, 20)
        val existing = manual("bar1", "B", 2, 4)

        val limit = seq3ManualActivationEndLimit(emptyList(), listOf(outer, existing), "B", startIndex = 2, lastIndex = 20, excludeManualId = "bar1")

        assertEquals(20, limit, "bar1 may drag all the way to its enclosing outer bar's own end once its own stale span is excluded")
    }

    @Test
    fun endLimitOnADifferentLifelineIgnoresSpansElsewhere() {
        val auto = listOf(Seq3ActivationSpan("B", 0, 3, depth = 0, unmatched = false))

        val limit = seq3ManualActivationEndLimit(auto, emptyList(), "C", startIndex = 0, lastIndex = 10)

        assertEquals(10, limit, "a span on a different lifeline must never constrain this one")
    }

    // ── seq3IsValidManualActivationEnd ───────────────────────────────────────────────────────

    @Test
    fun isValidManualActivationEndIsTrueWhenNothingConstrainsIt() {
        assertTrue(seq3IsValidManualActivationEnd(emptyList(), emptyList(), "B", startIndex = 0, candidateEndIndex = 5, lastIndex = 10))
    }

    @Test
    fun isValidManualActivationEndIsFalseWhenAnEnclosingAutoSpanWouldClampIt() {
        val auto = listOf(Seq3ActivationSpan("B", 0, 4, depth = 0, unmatched = false))

        assertFalse(
            seq3IsValidManualActivationEnd(auto, emptyList(), "B", startIndex = 1, candidateEndIndex = 8, lastIndex = 10),
            "8 would be clamped down to 4 by the enclosing auto span, so it is not a valid end",
        )
        assertTrue(seq3IsValidManualActivationEnd(auto, emptyList(), "B", startIndex = 1, candidateEndIndex = 4, lastIndex = 10))
    }

    @Test
    fun isValidManualActivationEndIsFalseWhenTheProbeWouldBeDroppedPastTheDepthCap() {
        // 8 manual bars, each starting one row later than the last but sharing one common (very
        // long) end, already occupy depths 0..7. A 9th bar starting even later, inside all of
        // them, would open at depth 8 — past the cap — so it is dropped entirely, and therefore
        // not a valid end either, even though nothing would otherwise clamp its declared end.
        val manyManual = (0 until 8).map { i -> manual("bar$i", "B", i, 100) }

        assertFalse(seq3IsValidManualActivationEnd(emptyList(), manyManual, "B", startIndex = 8, candidateEndIndex = 100, lastIndex = 100))
    }

    @Test
    fun isValidManualActivationEndAgreesWithEndLimitAtTheBoundary() {
        val auto = listOf(Seq3ActivationSpan("B", 0, 6, depth = 0, unmatched = false))
        val limit = seq3ManualActivationEndLimit(auto, emptyList(), "B", startIndex = 2, lastIndex = 20)

        assertTrue(
            seq3IsValidManualActivationEnd(auto, emptyList(), "B", startIndex = 2, candidateEndIndex = limit, lastIndex = 20),
            "exactly the limit end-limit reports must itself be a valid, unclamped end",
        )
        assertFalse(
            seq3IsValidManualActivationEnd(auto, emptyList(), "B", startIndex = 2, candidateEndIndex = limit + 1, lastIndex = 20),
            "one past the limit must be invalid",
        )
    }

    @Test
    fun isValidManualActivationEndExcludesTheBarsOwnCurrentSpanWhenResizingItself() {
        // Mirrors endLimitWithExclusionMeasuresAgainstTheEnclosingSpanNotTheBarsOwnStaleSpan: the
        // outer bar's own end is what decides validity for bar1's new end, once bar1's own
        // not-yet-updated span is excluded from the check.
        val outer = manual("outer", "B", 0, 20)
        val existing = manual("bar1", "B", 2, 4)

        assertTrue(
            seq3IsValidManualActivationEnd(emptyList(), listOf(outer, existing), "B", startIndex = 2, candidateEndIndex = 20, lastIndex = 20, excludeManualId = "bar1"),
        )
    }
}
