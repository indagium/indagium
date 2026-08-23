package com.indagium

import com.indagium.diagram3.Seq3Authoring
import com.indagium.diagram3.Seq3BulkAction
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3CommandResult
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3LifelineKind
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3OccurrenceRef
import com.indagium.diagram3.Seq3RegenDecision
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.applySeq3Command
import com.indagium.diagram3.reviewSeq3Regeneration
import com.indagium.diagram3.undoSeq3Command
import com.indagium.diagram3.withSeq3RegenDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun selectedLifelinesCanBeMergedInOneUndoableCommand() {
        val doc = Seq3Document(
            lifelines = listOf(
                Seq3Lifeline("A", "A", setOf("A"), 0),
                Seq3Lifeline("B", "B", setOf("B"), 1),
                Seq3Lifeline("C", "C", setOf("C"), 2),
            ),
            messages = listOf(message("m1", "C", "B", 1)),
        )

        val result = applySeq3Command(doc, Seq3Command.MergeLifelineSelection("A", setOf("B", "C")))

        assertTrue(result.applied)
        assertEquals(listOf("A"), result.document.lifelines.map { it.id })
        assertEquals(setOf("A", "B", "C"), result.document.lifelines.single().tagIds)
        assertEquals("A", result.document.messages.single().fromLifelineId)
        assertEquals("A", result.document.messages.single().toLifelineId)
        assertEquals(doc, result.undo?.let(::undoSeq3Command))
    }

    @Test
    fun hidingAndRemovingLifelinesPreservesTheQueue() {
        val hidden = applySeq3Command(
            baseDocument(),
            Seq3Command.SetLifelineVisibility("B", Seq3Visibility.HIDDEN),
        )
        assertTrue(hidden.applied)
        assertEquals(Seq3Visibility.HIDDEN, hidden.document.lifelines.single { it.id == "B" }.visibility)

        val removed = applySeq3Command(hidden.document, Seq3Command.RemoveLifeline("B"))
        assertTrue(removed.applied)
        assertTrue(removed.document.lifelines.none { it.id == "B" })
        assertNull(removed.document.messages.single { it.id == "m1" }.toLifelineId)
        assertEquals(2, removed.document.messages.size)
    }

    @Test
    fun movingOneTagOutOfAMergedLifelineCreatesASeparateSourceLifeline() {
        val merged = Seq3Lifeline("Common", "Common", setOf("Legacy", "Modern"), 0)
        val legacy = message("legacy", "Common", "Common", 1).copy(match = Seq3Match("Legacy", "legacy"))
        val modern = message("modern", "Common", "Common", 2).copy(match = Seq3Match("Modern", "modern"))
        val doc = Seq3Document(lifelines = listOf(merged), messages = listOf(legacy, modern))

        val result = applySeq3Command(
            doc,
            Seq3Command.SplitLifeline(
                lifelineId = "Common",
                tagId = "Legacy",
                newLifeline = Seq3Lifeline("Legacy-line", "Legacy", emptySet(), 1),
            ),
        )

        assertTrue(result.applied)
        assertEquals(setOf("Modern"), result.document.lifelines.single { it.id == "Common" }.tagIds)
        assertEquals(setOf("Legacy"), result.document.lifelines.single { it.id == "Legacy-line" }.tagIds)
        assertEquals("Legacy-line", result.document.messages.single { it.id == "legacy" }.fromLifelineId)
        assertEquals("Common", result.document.messages.single { it.id == "modern" }.fromLifelineId)
        assertEquals(doc, result.undo?.let(::undoSeq3Command))
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
    fun replaceMessageCanRestoreAGroupAndRemoveItsSplitSiblingAtomically() {
        val original = message("m1", "A", "B", 1).copy(
            match = Seq3Match("A", "USB poll devices={devices}"),
            labelTemplate = "USB poll devices={devices}",
            occurrences = listOf(occ(1).copy(text = "USB poll devices=3"), occ(2).copy(text = "USB poll devices=2")),
        )
        val split = original.copy(id = "m2", authoring = Seq3Authoring.EDITED, occurrences = listOf(original.occurrences.last()))
        val doc = baseDocument().copy(messages = listOf(original.copy(occurrences = listOf(original.occurrences.first())), split))
        val restored = applySeq3Command(
            doc,
            Seq3Command.ReplaceMessage(
                messageId = "m1",
                replacement = original.copy(authoring = Seq3Authoring.AUTO),
                removeMessageIds = setOf("m2"),
            ),
        )

        assertTrue(restored.applied)
        assertEquals(listOf("m1"), restored.document.messages.map { it.id })
        assertEquals(listOf(1, 2), restored.document.messages.single().occurrences.map { it.entryId })
    }

    @Test
    fun mergeRequiresTwoMessagesAndPreservesTheFirstSelectedQueuePosition() {
        val first = message("m1", "A", "B", 1).copy(
            match = Seq3Match("A", "USB poll devices={devices}"),
            labelTemplate = "USB poll devices={devices}",
            occurrences = listOf(occ(1).copy(text = "USB poll devices=3")),
        )
        val second = first.copy(id = "m2", occurrences = listOf(occ(2).copy(text = "USB poll devices=2")))
        val doc = baseDocument().copy(messages = listOf(first, second, message("m3", "A", "B", 3)))

        val one = applySeq3Command(doc, Seq3Command.Bulk(setOf("m1"), Seq3BulkAction.Merge("m1")))
        assertFalseApplied(one)
        assertEquals(doc, one.document)

        val merged = applySeq3Command(doc, Seq3Command.Bulk(setOf("m1", "m2"), Seq3BulkAction.Merge("m1")))
        assertTrue(merged.applied)
        assertEquals(listOf("m1", "m3"), merged.document.messages.map { it.id })
        assertEquals(listOf(1, 2), merged.document.messages.first().occurrences.map { it.entryId })
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
    fun movedOutOccurrenceCanBeMovedBackToItsOriginalGroup() {
        val grouped = message("m1", "A", "B", 1).copy(occurrences = listOf(occ(1), occ(2)))
        val doc = baseDocument().copy(messages = listOf(grouped))

        val moved = applySeq3Command(doc, Seq3Command.MoveOccurrenceOut("m1", 2))
        assertTrue(moved.applied)
        val standalone = moved.document.messages.single { it.id != "m1" }
        assertEquals("m1", standalone.movedOutFromMessageId)

        val restored = applySeq3Command(moved.document, Seq3Command.MoveOccurrenceBack(standalone.id))
        assertTrue(restored.applied)
        assertEquals(listOf("m1"), restored.document.messages.map { it.id })
        assertEquals(listOf(1, 2), restored.document.messages.single().occurrences.map { it.entryId })
        assertNull(restored.document.messages.single().movedOutFromMessageId)
    }

    @Test
    fun checkedOccurrencesMoveOutAsOneCommandAndKeepOneGroupOccurrence() {
        val grouped = message("m1", "A", "B", 1).copy(occurrences = listOf(occ(1), occ(2), occ(3)))
        val doc = baseDocument().copy(messages = listOf(grouped))
        val moved = applySeq3Command(
            doc,
            Seq3Command.MoveOccurrencesOut(
                listOf(Seq3OccurrenceRef("m1", 2), Seq3OccurrenceRef("m1", 3)),
            ),
        )

        assertTrue(moved.applied)
        assertEquals(3, moved.document.messages.size)
        assertEquals(listOf(1), moved.document.messages.single { it.id == "m1" }.occurrences.map { it.entryId })
        assertEquals(2, moved.document.messages.count { it.movedOutFromMessageId == "m1" })
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

    // ── WP1: item 8 merge fix (manual lifeline tagIds + ordinal compaction) ────────────────────

    @Test
    fun mergeLifelinesCompactsOrdinalsAfterRemoval() {
        val doc = Seq3Document(
            lifelines = listOf(
                Seq3Lifeline("A", "A", setOf("A"), 0),
                Seq3Lifeline("B", "B", setOf("B"), 1),
                Seq3Lifeline("C", "C", setOf("C"), 2),
                Seq3Lifeline("D", "D", setOf("D"), 3),
            ),
            messages = listOf(message("m1", "A", "D", 1)),
        )
        val result = applySeq3Command(doc, Seq3Command.MergeLifelines(keepLifelineId = "D", mergedLifelineId = "B"))
        assertTrue(result.applied)
        // B (ordinal 1) is gone; C must slide down to fill the gap rather than keep its old
        // ordinal 2 — matching dispatchReorder/dispatchSplitLifeline's own gap-free contract.
        assertEquals(
            mapOf("A" to 0, "C" to 1, "D" to 2),
            result.document.lifelines.associate { it.id to it.ordinal },
        )
    }

    @Test
    fun aManuallyAddedLifelineMergesWithMultipleRepresentedTags() {
        val doc = baseDocument() // lifelines A, B
        val added = applySeq3Command(
            doc,
            Seq3Command.AddLifeline(Seq3Lifeline(id = "manual-1", name = "Manual", tagIds = emptySet(), ordinal = 2)),
        )
        assertTrue(added.applied)
        // The whole item-8 fix: an empty tagIds at creation time must NOT survive as empty.
        assertEquals(setOf("Manual"), added.document.lifelines.single { it.id == "manual-1" }.tagIds)

        val merged = applySeq3Command(added.document, Seq3Command.MergeLifelines(keepLifelineId = "A", mergedLifelineId = "manual-1"))
        assertTrue(merged.applied)
        val keep = merged.document.lifelines.single { it.id == "A" }
        assertTrue(keep.tagIds.size > 1, "expected a folded multi-tag lifeline, got ${keep.tagIds}")
        assertEquals(setOf("A", "Manual"), keep.tagIds)
        assertEquals((0 until merged.document.lifelines.size).toList(), merged.document.lifelines.sortedBy { it.ordinal }.map { it.ordinal })
    }

    // ── WP1: new lifeline-identity / diagram-wide display commands ─────────────────────────────

    @Test
    fun setLifelineKindTogglesActorAndIsUndoable() {
        val doc = baseDocument()
        val result = applySeq3Command(doc, Seq3Command.SetLifelineKind("A", Seq3LifelineKind.ACTOR))
        assertTrue(result.applied)
        assertEquals(Seq3LifelineKind.ACTOR, result.document.lifelines.single { it.id == "A" }.kind)
        assertEquals(doc, result.undo?.let(::undoSeq3Command))

        assertFalseApplied(applySeq3Command(result.document, Seq3Command.SetLifelineKind("A", Seq3LifelineKind.ACTOR)))
    }

    @Test
    fun setLifelineKindIsUnappliedForAnUnknownLifeline() {
        val doc = baseDocument()
        val result = applySeq3Command(doc, Seq3Command.SetLifelineKind("NoSuchLifeline", Seq3LifelineKind.ACTOR))
        assertFalseApplied(result)
        assertEquals(doc, result.document)
    }

    @Test
    fun setLifelineDisplaySegmentsOverridesThenClearsBackToInheriting() {
        val doc = baseDocument()
        val set = applySeq3Command(doc, Seq3Command.SetLifelineDisplaySegments("A", 2))
        assertTrue(set.applied)
        assertEquals(2, set.document.lifelines.single { it.id == "A" }.displaySegments)

        val cleared = applySeq3Command(set.document, Seq3Command.SetLifelineDisplaySegments("A", null))
        assertTrue(cleared.applied)
        assertNull(cleared.document.lifelines.single { it.id == "A" }.displaySegments)
        // Undo restores the state right before THIS command (displaySegments=2), not all the way
        // back to the original doc — that would take undoing both commands.
        assertEquals(set.document, cleared.undo?.let(::undoSeq3Command))
    }

    @Test
    fun setDocumentDisplaySegmentsChangesTheDiagramDefaultAndIsUndoable() {
        val doc = baseDocument()
        val result = applySeq3Command(doc, Seq3Command.SetDocumentDisplaySegments(2))
        assertTrue(result.applied)
        assertEquals(2, result.document.lifelineDisplaySegments)
        assertEquals(doc, result.undo?.let(::undoSeq3Command))
    }

    @Test
    fun setDocumentThemeSetsAndClearsThePerDiagramOverride() {
        val doc = baseDocument()
        val set = applySeq3Command(doc, Seq3Command.SetDocumentTheme("DRACULA"))
        assertTrue(set.applied)
        assertEquals("DRACULA", set.document.themePresetName)

        val cleared = applySeq3Command(set.document, Seq3Command.SetDocumentTheme(null))
        assertTrue(cleared.applied)
        assertNull(cleared.document.themePresetName)
    }

    // ── WP10 (item 7): inline call numbering / timestamps toggles ──────────────────────────────

    @Test
    fun setShowSequenceNumbersTogglesAndIsUndoable() {
        val doc = baseDocument()
        assertFalse(doc.showSequenceNumbers)

        val on = applySeq3Command(doc, Seq3Command.SetShowSequenceNumbers(true))
        assertTrue(on.applied)
        assertTrue(on.document.showSequenceNumbers)
        assertEquals(doc, on.undo?.let(::undoSeq3Command))

        val off = applySeq3Command(on.document, Seq3Command.SetShowSequenceNumbers(false))
        assertTrue(off.applied)
        assertFalse(off.document.showSequenceNumbers)
    }

    @Test
    fun setShowSequenceNumbersIsANoOpWhenAlreadyAtTheRequestedValue() {
        val doc = baseDocument()
        val result = applySeq3Command(doc, Seq3Command.SetShowSequenceNumbers(false))
        assertFalse(result.applied)
        assertNull(result.undo)
    }

    @Test
    fun setShowTimestampsTogglesAndIsUndoable() {
        val doc = baseDocument()
        assertFalse(doc.showTimestamps)

        val on = applySeq3Command(doc, Seq3Command.SetShowTimestamps(true))
        assertTrue(on.applied)
        assertTrue(on.document.showTimestamps)
        assertEquals(doc, on.undo?.let(::undoSeq3Command))

        val off = applySeq3Command(on.document, Seq3Command.SetShowTimestamps(false))
        assertTrue(off.applied)
        assertFalse(off.document.showTimestamps)
    }

    // ── WP1: SwapEndpoints (needed by WP5's ⇄ control) ──────────────────────────────────────────

    @Test
    fun swapEndpointsFlipsFromAndToAndStampsEdited() {
        val doc = baseDocument() // m1: A -> B
        val result = applySeq3Command(doc, Seq3Command.Bulk(setOf("m1"), Seq3BulkAction.SwapEndpoints))
        assertTrue(result.applied)
        val swapped = result.document.messages.single { it.id == "m1" }
        assertEquals("B", swapped.fromLifelineId)
        assertEquals("A", swapped.toLifelineId)
        assertEquals(Seq3Authoring.EDITED, swapped.authoring)
        assertEquals(doc, result.undo?.let(::undoSeq3Command))
    }

    @Test
    fun swapEndpointsIsANoOpForNoteKindAndForANullTarget() {
        val doc = baseDocument().let { d -> d.copy(messages = d.messages + message("note1", "A", null, 5).copy(kind = Seq3Kind.NOTE)) }
        // m2 already carries a null target in baseDocument(); note1 is NOTE kind. Neither is
        // eligible, so the whole bulk command must be unapplied.
        val result = applySeq3Command(doc, Seq3Command.Bulk(setOf("m2", "note1"), Seq3BulkAction.SwapEndpoints))
        assertFalseApplied(result)
        assertEquals(doc, result.document)
    }

    @Test
    fun swapEndpointsInAMixedSelectionLeavesIneligibleMessagesCompletelyUntouched() {
        val doc = baseDocument() // m1: A -> B (eligible), m2: A -> null (ineligible)
        val result = applySeq3Command(doc, Seq3Command.Bulk(setOf("m1", "m2"), Seq3BulkAction.SwapEndpoints))
        assertTrue(result.applied)
        val m1 = result.document.messages.single { it.id == "m1" }
        assertEquals("B", m1.fromLifelineId)
        assertEquals("A", m1.toLifelineId)
        assertEquals(Seq3Authoring.EDITED, m1.authoring)
        // m2 must be byte-identical to before, not just endpoint-unchanged: it must not even pick
        // up the EDITED stamp from being caught in the same selection.
        assertEquals(doc.messages.single { it.id == "m2" }, result.document.messages.single { it.id == "m2" })
    }

    // ── WP7 item 2: SetCaller (the stub-drop verb) ──────────────────────────────────────────────

    @Test
    fun setCallerAssignsTheDroppedLifelineAsFromAndTheMessagesPriorFromAsToInOneCommand() {
        val doc = baseDocument() // m2: A -> null (unresolved stub, tag lifeline is A)
        val result = applySeq3Command(doc, Seq3Command.Bulk(setOf("m2"), Seq3BulkAction.SetCaller("B")))
        assertTrue(result.applied)
        val m2 = result.document.messages.single { it.id == "m2" }
        // "B" was dropped on -> B is now the CALLER; the tag's own prior from ("A") becomes the
        // CALLEE — both endpoints set from a single command, so this is one undo step.
        assertEquals("B", m2.fromLifelineId)
        assertEquals("A", m2.toLifelineId)
        assertEquals(Seq3Authoring.EDITED, m2.authoring)
        assertEquals(doc, result.undo?.let(::undoSeq3Command), "one Bulk command must be one undo step")
    }

    @Test
    fun setCallerDroppedBackOntoTheTagsOwnLifelineResolvesAsASelfCall() {
        // Degenerate case (WP7 item 2's own decision): dropping the stub back onto the tag's OWN
        // lifeline collapses from/to onto the same id — read as a genuine self-call rather than
        // rejected, the same auto-flip SetFrom/SetTo already perform elsewhere.
        val doc = baseDocument() // m2: A -> null
        val result = applySeq3Command(doc, Seq3Command.Bulk(setOf("m2"), Seq3BulkAction.SetCaller("A")))
        assertTrue(result.applied)
        val m2 = result.document.messages.single { it.id == "m2" }
        assertEquals("A", m2.fromLifelineId)
        assertEquals("A", m2.toLifelineId)
        assertEquals(Seq3Kind.SELF, m2.kind)
        assertEquals(Seq3Authoring.EDITED, m2.authoring)
    }

    @Test
    fun setCallerIsANoOpForAnUnknownLifeline() {
        val doc = baseDocument()
        val result = applySeq3Command(doc, Seq3Command.Bulk(setOf("m2"), Seq3BulkAction.SetCaller("NoSuchLifeline")))
        assertFalseApplied(result)
        assertEquals(doc, result.document)
        assertNull(result.undo)
    }
}
