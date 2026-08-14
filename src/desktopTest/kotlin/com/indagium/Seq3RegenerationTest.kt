package com.indagium

import com.indagium.diagram3.Seq3Authoring
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3RegenChangeKind
import com.indagium.diagram3.Seq3RegenDecision
import com.indagium.diagram3.acceptAllSeq3Regen
import com.indagium.diagram3.applySeq3Regeneration
import com.indagium.diagram3.rejectAllSeq3Regen
import com.indagium.diagram3.reviewSeq3Regeneration
import com.indagium.diagram3.unlockSeq3RegenRow
import com.indagium.diagram3.withSeq3RegenDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Seq3RegenerationTest {
    private fun occ(id: Int) = Seq3Occurrence(id, 100L + id, "10:00:00.000", 1, 1, 'I', "text $id")

    private fun message(id: String, from: String, to: String?, entryId: Int, template: String, authoring: Seq3Authoring = Seq3Authoring.AUTO) =
        Seq3Message(id, Seq3Match(from, template), from, to, template, authoring = authoring, occurrences = listOf(occ(entryId)))

    private fun lifelines() = listOf(
        Seq3Lifeline("A", "A", setOf("A"), 0),
        Seq3Lifeline("B", "B", setOf("B"), 1),
        Seq3Lifeline("C", "C", setOf("C"), 2),
    )

    private fun currentDocument() = Seq3Document(
        lifelines = lifelines().take(2),
        messages = listOf(
            message("c-kept", "A", "B", 1, "kept"),
            message("c-changed", "A", "B", 2, "changed-src"),
            message("c-removed", "A", "B", 3, "removed-src"),
            message("c-edited", "A", "B", 4, "edited-src", authoring = Seq3Authoring.EDITED),
        ),
    )

    private fun freshDocument() = Seq3Document(
        lifelines = lifelines(),
        messages = listOf(
            // identical content, matches c-kept by evidence
            message("f-kept", "A", "B", 1, "kept"),
            // same evidence, different target -> CHANGED
            message("f-changed", "A", "C", 2, "changed-src"),
            // matches c-edited's evidence, but c-edited is locked
            message("f-edited", "A", "C", 4, "edited-src"),
            // no current message shares entryId 5
            message("f-new", "A", "B", 5, "new-src"),
        ),
    )

    @Test
    fun identicalMatchedMessagesAreUnchangedAndNeverCountedInTheSummary() {
        val review = reviewSeq3Regeneration(currentDocument(), freshDocument())
        val row = review.rows.single { it.id == "c-kept" }
        assertEquals(Seq3RegenChangeKind.UNCHANGED, row.kind)
        // The summary chips ("12 new · 3 changed · ...") only count actual differences — an
        // UNCHANGED row must not inflate any of them.
        assertEquals(1, review.summary.newCount)
        assertEquals(1, review.summary.changedCount)
        assertEquals(1, review.summary.removedCount)
        assertEquals(1, review.summary.editsKeptCount)
    }

    @Test
    fun classifiesNewChangedAndRemovedCorrectly() {
        val review = reviewSeq3Regeneration(currentDocument(), freshDocument())

        val changed = review.rows.single { it.kind == Seq3RegenChangeKind.CHANGED }
        assertEquals("c-changed", changed.current?.id)
        assertEquals("f-changed", changed.fresh?.id)

        val removed = review.rows.single { it.kind == Seq3RegenChangeKind.REMOVED }
        assertEquals("c-removed", removed.current?.id)
        assertEquals(null, removed.fresh)

        val added = review.rows.single { it.kind == Seq3RegenChangeKind.NEW }
        assertEquals("f-new", added.fresh?.id)
        assertEquals(null, added.current)

        assertEquals(review.summary.changedCount, 1)
        assertEquals(review.summary.removedCount, 1)
        assertEquals(review.summary.newCount, 1)
    }

    @Test
    fun editedMessagesAreLockedAndReportedNotReplaced() {
        val review = reviewSeq3Regeneration(currentDocument(), freshDocument())
        val editedRow = review.rows.single { it.kind == Seq3RegenChangeKind.EDITED_KEPT }
        assertEquals("c-edited", editedRow.current?.id)
        assertEquals("f-edited", editedRow.fresh?.id, "the row still reports what regeneration WOULD have proposed")
        assertEquals(1, review.summary.editsKeptCount)

        // Accepting everything must NOT touch a locked row.
        val accepted = acceptAllSeq3Regen(review)
        val applied = applySeq3Regeneration(currentDocument(), accepted)
        val survivor = applied.messages.single { it.id == "c-edited" }
        assertEquals("B", survivor.toLifelineId, "an edited message must never be silently replaced by regeneration")
        assertEquals(Seq3Authoring.EDITED, survivor.authoring)
    }

    @Test
    fun unlockingALockedRowMakesItOrdinaryReviewable() {
        val review = reviewSeq3Regeneration(currentDocument(), freshDocument())
        val unlocked = unlockSeq3RegenRow(review, "c-edited")
        val accepted = withSeq3RegenDecision(unlocked, "c-edited", Seq3RegenDecision.ACCEPT)
        val applied = applySeq3Regeneration(currentDocument(), accepted)

        val survivor = applied.messages.single { it.id == "f-edited" }
        assertEquals("C", survivor.toLifelineId, "once unlocked and accepted, the fresh proposal wins")
    }

    @Test
    fun rejectAllNeverTouchesLockedRows() {
        val review = reviewSeq3Regeneration(currentDocument(), freshDocument())
        val rejected = rejectAllSeq3Regen(review)
        val lockedRow = rejected.rows.single { it.kind == Seq3RegenChangeKind.EDITED_KEPT }
        assertEquals(Seq3RegenDecision.PENDING, lockedRow.decision, "acceptAll/rejectAll must skip locked rows entirely")
    }

    @Test
    fun applyIsOneNewDocumentReflectingEveryDecision() {
        val current = currentDocument()
        var review = reviewSeq3Regeneration(current, freshDocument())
        review = withSeq3RegenDecision(review, "c-changed", Seq3RegenDecision.ACCEPT)
        review = withSeq3RegenDecision(review, "c-removed", Seq3RegenDecision.ACCEPT) // accept the removal
        review = withSeq3RegenDecision(review, "f-new", Seq3RegenDecision.ACCEPT)

        val applied = applySeq3Regeneration(current, review)
        val ids = applied.messages.map { it.id }.toSet()
        assertTrue("c-kept" in ids) // untouched matched content always survives
        assertTrue("f-changed" in ids) // accepted the fresh proposal
        assertTrue("c-removed" !in ids) // accepted the removal
        assertTrue("f-new" in ids) // accepted the addition
        assertTrue("c-edited" in ids) // locked, always kept
        assertEquals(freshDocument().lifelines, applied.lifelines, "lifelines always track the fresh scan")
    }

    @Test
    fun pendingChangedAndRemovedRowsDefaultToKeepingTheCurrentMessage() {
        val current = currentDocument()
        val review = reviewSeq3Regeneration(current, freshDocument()) // nothing decided yet
        val applied = applySeq3Regeneration(current, review)
        val ids = applied.messages.map { it.id }.toSet()

        assertTrue("c-changed" in ids, "an unreviewed CHANGED row must keep the user's current message")
        assertTrue("c-removed" in ids, "an unreviewed REMOVED row must not silently delete anything")
        assertTrue("f-new" !in ids, "an unreviewed NEW row must not silently get added")
    }
}
