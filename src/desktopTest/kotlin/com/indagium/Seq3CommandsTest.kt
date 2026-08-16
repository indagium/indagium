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
import com.indagium.diagram3.Seq3Visibility
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
    fun replaceMessageSwapsExactlyOneMessageAndIsUndoable() {
        val doc = baseDocument()
        val replacement = message("m2", "A", "B", 99) // same id, different content
        val result = applySeq3Command(doc, Seq3Command.ReplaceMessage("m2", replacement))
        assertTrue(result.applied)
        assertEquals(listOf(message("m1", "A", "B", 1), replacement), result.document.messages)
        assertEquals(doc, result.undo?.let(::undoSeq3Command))
    }

    @Test
    fun replaceMessageRetainsTargetIdWhenFreshReplacementUsesAnotherId() {
        val doc = baseDocument()
        val replacement = message("m2", "A", "B", 99)
        val result = applySeq3Command(doc, Seq3Command.ReplaceMessage("m1", replacement))

        assertTrue(result.applied)
        assertEquals(listOf("m1", "m2"), result.document.messages.map { it.id })
        assertEquals(2, result.document.messages.map { it.id }.toSet().size)
    }

    @Test
    fun occurrenceVisibilityChangesOnlyTheRequestedOccurrence() {
        val doc = baseDocument().copy(
            messages = listOf(
                baseDocument().messages.first().copy(occurrences = listOf(occ(1), occ(2))),
            ),
        )
        val result = applySeq3Command(
            doc,
            Seq3Command.SetOccurrenceVisibility("m1", 1, Seq3Visibility.HIDDEN),
        )

        assertTrue(result.applied)
        val occurrences = result.document.messages.single().occurrences
        assertEquals(Seq3Visibility.HIDDEN, occurrences.first { it.entryId == 1 }.visibility)
        assertEquals(Seq3Visibility.VISIBLE, occurrences.first { it.entryId == 2 }.visibility)
    }

    @Test
    fun replaceMessageIsUnappliedForAnUnknownId() {
        val doc = baseDocument()
        val result = applySeq3Command(doc, Seq3Command.ReplaceMessage("no-such-id", message("x", "A", "B", 1)))
        assertFalseApplied(result)
        assertEquals(doc, result.document)
        assertNull(result.undo)
    }

    @Test
    fun replaceDocumentSwapsTheWholeDocumentAndIsUndoable() {
        val doc = baseDocument()
        val replacement = doc.copy(messages = doc.messages + message("m3", "A", "B", 3))
        val result = applySeq3Command(doc, Seq3Command.ReplaceDocument(replacement))
        assertTrue(result.applied)
        assertEquals(replacement, result.document)
        assertEquals(doc, result.undo?.let(::undoSeq3Command))
    }

    @Test
    fun replaceDocumentIsUnappliedWhenTheDocumentIsUnchanged() {
        val doc = baseDocument()
        val result = applySeq3Command(doc, Seq3Command.ReplaceDocument(doc))
        assertFalseApplied(result)
        assertEquals(doc, result.document)
        assertNull(result.undo)
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
