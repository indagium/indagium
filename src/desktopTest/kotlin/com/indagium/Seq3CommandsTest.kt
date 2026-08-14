package com.indagium

import com.indagium.diagram3.Seq3BulkAction
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3CommandResult
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3RegenDecision
import com.indagium.diagram3.applySeq3Command
import com.indagium.diagram3.reviewSeq3Regeneration
import com.indagium.diagram3.undoSeq3Command
import com.indagium.diagram3.withSeq3RegenDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Seq3CommandsTest {
    private fun occ(id: Int) = Seq3Occurrence(id, 100L, "10:00:00.000", 1, 1, 'I', "text $id")

    private fun message(id: String, from: String, to: String?, entryId: Int) =
        Seq3Message(id, Seq3Match(from, "$id-label"), from, to, "$id-label", occurrences = listOf(occ(entryId)))

    private fun baseDocument() = Seq3Document(
        lifelines = listOf(Seq3Lifeline("A", "A", setOf("A"), 0), Seq3Lifeline("B", "B", setOf("B"), 1)),
        messages = listOf(message("m1", "A", "B", 1), message("m2", "A", null, 2)),
    )

    @Test
    fun anAppliedCommandCarriesAnUndoEntryThatRestoresTheExactPriorDocument() {
        val doc = baseDocument()
        val result = applySeq3Command(doc, Seq3Command.Bulk(setOf("m1"), Seq3BulkAction.Hide))
        assertTrue(result.applied)
        assertEquals(doc, result.undo?.let(::undoSeq3Command), "undo must restore byte-identical prior state")
    }

    @Test
    fun anUnappliedCommandCarriesNoUndoEntryAndLeavesTheDocumentUntouched() {
        val doc = baseDocument()
        val result = applySeq3Command(doc, Seq3Command.Bulk(setOf("m1"), Seq3BulkAction.SetFrom("NoSuchLifeline")))
        assertFalseApplied(result)
        assertEquals(doc, result.document)
        assertNull(result.undo)
    }

    private fun assertFalseApplied(result: Seq3CommandResult) = assertTrue(!result.applied)

    @Test
    fun guidedTargetCommandMarksEditedAndIsFullyUndoable() {
        val doc = baseDocument()
        val result = applySeq3Command(doc, Seq3Command.GuidedTarget("m2", "B"))
        assertTrue(result.applied)
        assertEquals("B", result.document.messages.single { it.id == "m2" }.toLifelineId)
        assertEquals(doc, result.undo?.let(::undoSeq3Command))
    }

    @Test
    fun reorderLifelinesRejectsAnIncompleteList() {
        val doc = baseDocument()
        val result = applySeq3Command(doc, Seq3Command.ReorderLifelines(listOf("A"))) // missing "B"
        assertFalseApplied(result)
        assertEquals(doc, result.document)
    }

    @Test
    fun reorderLifelinesAppliesNewOrdinalsAndIsUndoable() {
        val doc = baseDocument()
        val result = applySeq3Command(doc, Seq3Command.ReorderLifelines(listOf("B", "A")))
        assertTrue(result.applied)
        assertEquals(0, result.document.lifelines.single { it.id == "B" }.ordinal)
        assertEquals(1, result.document.lifelines.single { it.id == "A" }.ordinal)
        assertEquals(doc, result.undo?.let(::undoSeq3Command))
    }

    @Test
    fun mergeLifelinesFoldsTagsAndRepointsMessages() {
        val doc = Seq3Document(
            lifelines = listOf(
                Seq3Lifeline("Legacy", "Legacy", setOf("Legacy"), 0),
                Seq3Lifeline("Modern", "Modern", setOf("Modern"), 1),
                Seq3Lifeline("Other", "Other", setOf("Other"), 2),
            ),
            messages = listOf(message("m1", "Legacy", "Other", 1)),
        )
        val result = applySeq3Command(doc, Seq3Command.MergeLifelines(keepLifelineId = "Modern", mergedLifelineId = "Legacy"))
        assertTrue(result.applied)
        assertTrue(result.document.lifelines.none { it.id == "Legacy" })
        val merged = result.document.lifelines.single { it.id == "Modern" }
        assertEquals(setOf("Legacy", "Modern"), merged.tagIds)
        assertEquals("Modern", result.document.messages.single().fromLifelineId)
    }

    @Test
    fun regenerationApplyIsExactlyOneUndoStep() {
        val current = baseDocument()
        // m2 gone, m3 is genuinely new
        val fresh = current.copy(
            messages = listOf(message("m1", "A", "B", 1), message("m3", "A", "B", 3)),
        )
        var review = reviewSeq3Regeneration(current, fresh)
        review = withSeq3RegenDecision(review, "m3", Seq3RegenDecision.ACCEPT)

        val result = applySeq3Command(current, Seq3Command.ApplyRegeneration(review))
        assertTrue(result.applied)
        assertTrue(result.document.messages.any { it.id == "m3" })

        // ONE undo step restores everything the whole regeneration review touched, not a partial
        // rollback of just the last row decided.
        val restored = result.undo?.let(::undoSeq3Command)
        assertEquals(current, restored)
    }
}
