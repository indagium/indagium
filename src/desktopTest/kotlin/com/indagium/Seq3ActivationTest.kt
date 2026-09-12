package com.indagium

import com.indagium.diagram3.Seq3ActivationEvent
import com.indagium.diagram3.Seq3ActivationSpan
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.seq3ActivationSpans
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
