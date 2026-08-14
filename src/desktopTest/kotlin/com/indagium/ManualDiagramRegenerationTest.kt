package com.indagium

import com.indagium.diagram.ManualDiagramDocument
import com.indagium.diagram.ManualDiagramGroup
import com.indagium.diagram.ManualDiagramInteraction
import com.indagium.diagram.ManualDiagramRepeatPresentation
import com.indagium.diagram.ManualInteractionAuthoring
import com.indagium.diagram.ManualRegenerationChangeKind
import com.indagium.diagram.ManualRegenerationReview
import com.indagium.diagram.ManualRegenerationRowDecision
import com.indagium.diagram.acceptAllRegenerationRows
import com.indagium.diagram.applyReviewedManualRegeneration
import com.indagium.diagram.rejectAllRegenerationRows
import com.indagium.diagram.reviewManualRegeneration
import com.indagium.diagram.withRowDecision
import com.indagium.model.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManualDiagramRegenerationTest {
    private fun interaction(
        id: String,
        label: String,
        order: Long,
        authoring: ManualInteractionAuthoring = ManualInteractionAuthoring.AUTO,
        entryId: Int = order.toInt(),
    ) = ManualDiagramInteraction(
        id = id,
        sourceEntryIds = setOf(entryId),
        fromParticipantId = "client",
        toParticipantId = "service",
        label = label,
        order = order,
        authoring = authoring,
        renderAnchorLevel = LogLevel.I,
    )

    @Test
    fun reviewKeepsEditedTargetAndLabelWhileUpdatingAutoAndAddingNewRows() {
        val edited = interaction("edited", "user label", 0, ManualInteractionAuthoring.EDITED)
        val auto = interaction("auto", "old", 1)
        val existing = ManualDiagramDocument(interactions = listOf(edited, auto))
        val candidate = ManualDiagramDocument(
            interactions = listOf(
                interaction("candidate-edited", "new candidate label", 0),
                interaction("candidate-auto", "new auto label", 1),
                interaction("new", "new message", 2, entryId = 3),
            ),
        )
        val review = reviewManualRegeneration(existing, candidate)

        assertTrue(review.rows.any { it.kind == ManualRegenerationChangeKind.EDITED_KEPT })
        assertEquals(1, review.changedAutoCount)
        assertEquals(1, review.newCount)
        val applied = applyReviewedManualRegeneration(existing, review)
        assertEquals("user label", applied.interactions.first { it.id == "edited" }.label)
        assertEquals("new auto label", applied.interactions.first { it.id == "auto" }.label)
        assertEquals(3, applied.interactions.size)
    }

    @Test
    fun removedAutoRowsDoNotLeaveBrokenStructuralReferences() {
        val old = interaction("old", "old", 0)
        val existing = ManualDiagramDocument(
            interactions = listOf(old),
            groups = listOf(ManualDiagramGroup("frame", "Frame", listOf(old.id))),
        )
        val review = reviewManualRegeneration(existing, ManualDiagramDocument())

        assertEquals(1, review.noLongerInSourceCount)
        assertTrue(applyReviewedManualRegeneration(existing, review).groups.isEmpty())
    }

    /** Builds one review carrying all four ManualRegenerationChangeKind rows at once. */
    private fun mixedKindReview(): Pair<ManualDiagramDocument, ManualRegenerationReview> {
        val edited = interaction("edited", "user label", 0, ManualInteractionAuthoring.EDITED)
        val auto = interaction("auto", "old", 1)
        val gone = interaction("gone", "gone", 2)
        val existing = ManualDiagramDocument(interactions = listOf(edited, auto, gone))
        val candidate = ManualDiagramDocument(
            interactions = listOf(
                interaction("candidate-edited", "new candidate label", 0),
                interaction("candidate-auto", "new auto label", 1),
                interaction("new", "new message", 3, entryId = 9),
            ),
        )
        return existing to reviewManualRegeneration(existing, candidate)
    }

    @Test
    fun defaultDecisionsReproduceTheOldFixedPerKindPolicyExactly() {
        val (existing, review) = mixedKindReview()
        assertEquals(4, review.rows.size)

        val applied = applyReviewedManualRegeneration(existing, review)

        assertEquals("user label", applied.interactions.first { it.id == "edited" }.label, "edited kept byte-for-byte")
        assertEquals("new auto label", applied.interactions.first { it.id == "auto" }.label, "changed-auto defaults to accepting the candidate")
        assertEquals(null, applied.interactions.firstOrNull { it.id == "gone" }, "no-longer-in-source defaults to removal")
        assertTrue(applied.interactions.any { it.id == "new" }, "new defaults to being added")
        assertEquals(3, applied.interactions.size)
    }

    @Test
    fun rejectingAChangedAutoRowKeepsTheOldDataInsteadOfTheCandidates() {
        val (existing, review) = mixedKindReview()
        val changedAutoIndex = review.rows.indexOfFirst { it.kind == ManualRegenerationChangeKind.CHANGED_AUTO }
        val rejected = withRowDecision(review, changedAutoIndex, ManualRegenerationRowDecision.REJECT)

        val applied = applyReviewedManualRegeneration(existing, rejected)

        assertEquals("old", applied.interactions.first { it.id == "auto" }.label)
    }

    @Test
    fun acceptingANoLongerInSourceRowKeepsItInsteadOfRemovingIt() {
        val (existing, review) = mixedKindReview()
        val goneIndex = review.rows.indexOfFirst { it.kind == ManualRegenerationChangeKind.NO_LONGER_IN_SOURCE }
        val kept = withRowDecision(review, goneIndex, ManualRegenerationRowDecision.ACCEPT)

        val applied = applyReviewedManualRegeneration(existing, kept)

        assertTrue(applied.interactions.any { it.id == "gone" })
    }

    @Test
    fun rejectingANewRowSkipsAddingIt() {
        val (existing, review) = mixedKindReview()
        val newIndex = review.rows.indexOfFirst { it.kind == ManualRegenerationChangeKind.NEW }
        val skipped = withRowDecision(review, newIndex, ManualRegenerationRowDecision.REJECT)

        val applied = applyReviewedManualRegeneration(existing, skipped)

        assertTrue(applied.interactions.none { it.id == "new" })
    }

    @Test
    fun withRowDecisionIsANoOpForAnOutOfRangeIndex() {
        val (_, review) = mixedKindReview()
        assertEquals(review, withRowDecision(review, -1, ManualRegenerationRowDecision.REJECT))
        assertEquals(review, withRowDecision(review, review.rows.size, ManualRegenerationRowDecision.REJECT))
    }

    @Test
    fun acceptAllAndRejectAllLeaveEditedKeptRowsUntouched() {
        val (existing, review) = mixedKindReview()

        val accepted = acceptAllRegenerationRows(review)
        val rejected = rejectAllRegenerationRows(review)

        accepted.rows.forEach { row ->
            if (row.kind == ManualRegenerationChangeKind.EDITED_KEPT) {
                assertEquals(ManualRegenerationRowDecision.PENDING, row.decision)
            } else {
                assertEquals(ManualRegenerationRowDecision.ACCEPT, row.decision)
            }
        }
        rejected.rows.forEach { row ->
            if (row.kind == ManualRegenerationChangeKind.EDITED_KEPT) {
                assertEquals(ManualRegenerationRowDecision.PENDING, row.decision)
            } else {
                assertEquals(ManualRegenerationRowDecision.REJECT, row.decision)
            }
        }
        // Reject-all still keeps the edited row, keeps the changed-auto row's OLD data (candidate
        // rejected), removes the no-longer-in-source row, and skips adding the new row.
        val applied = applyReviewedManualRegeneration(existing, rejected)
        assertEquals("user label", applied.interactions.first { it.id == "edited" }.label)
        assertEquals("old", applied.interactions.first { it.id == "auto" }.label)
        assertTrue(applied.interactions.none { it.id == "gone" })
        assertTrue(applied.interactions.none { it.id == "new" })
    }

    @Test
    fun acceptAllAppliesEveryReviewableRowWhileKeepingTheEditedSnapshot() {
        val (existing, review) = mixedKindReview()

        val applied = applyReviewedManualRegeneration(existing, acceptAllRegenerationRows(review))

        assertEquals("user label", applied.interactions.first { it.id == "edited" }.label)
        assertEquals("new auto label", applied.interactions.first { it.id == "auto" }.label)
        assertTrue(applied.interactions.any { it.id == "gone" })
        assertTrue(applied.interactions.any { it.id == "new" })
    }

    @Test
    fun unchangedRegenerationCreatesNoReviewRowsAndLeavesTheDocumentUntouched() {
        val existing = ManualDiagramDocument(
            interactions = listOf(interaction("existing", "same", 1)),
            repeatPresentation = ManualDiagramRepeatPresentation.FIRST_AND_LAST,
        )
        // Candidate ids come from a fresh seed and are not durable user identities.
        val candidate = existing.copy(interactions = listOf(interaction("fresh-seed-id", "same", 1)))

        val review = reviewManualRegeneration(existing, candidate)

        assertTrue(review.rows.isEmpty())
        assertEquals(existing, applyReviewedManualRegeneration(existing, review))
    }

    @Test
    fun regenerationDoesNotSemanticallyMatchRowsWhoseDurableProvenanceDiffers() {
        val existing = ManualDiagramDocument(interactions = listOf(
            interaction("old", "same label", 1).copy(sourceMethodId = "method:old", sourceLogSiteId = "site:1"),
        ))
        val candidate = ManualDiagramDocument(interactions = listOf(
            interaction("new", "same label", 1).copy(sourceMethodId = "method:new", sourceLogSiteId = "site:1"),
        ))

        val review = reviewManualRegeneration(existing, candidate)

        assertEquals(1, review.noLongerInSourceCount)
        assertEquals(1, review.newCount)
        assertEquals(0, review.changedAutoCount)
    }

    @Test
    fun regenerationSurfacesAmbiguousEvidenceFreeSemanticMatchesAsSeparateRows() {
        fun evidenceFree(id: String) = ManualDiagramInteraction(
            id = id, sourceEntryIds = emptySet(), fromParticipantId = "client", toParticipantId = "service",
            label = "same", order = 0, renderAnchorTs = "10:00:00.000", renderAnchorLevel = LogLevel.I,
        )
        val existing = ManualDiagramDocument(interactions = listOf(evidenceFree("old-1"), evidenceFree("old-2")))
        val candidate = ManualDiagramDocument(interactions = listOf(evidenceFree("new-1"), evidenceFree("new-2")))

        val review = reviewManualRegeneration(existing, candidate)

        assertEquals(2, review.noLongerInSourceCount)
        assertEquals(2, review.newCount)
        assertTrue(review.rows.all { it.matchAmbiguous })
    }

    @Test
    fun regenerationRetainsTheExistingRepeatPresentation() {
        val existing = ManualDiagramDocument(
            interactions = listOf(interaction("old", "old", 0)),
            repeatPresentation = ManualDiagramRepeatPresentation.FIRST_AND_LAST,
        )
        val candidate = ManualDiagramDocument(interactions = listOf(interaction("new", "new", 1)))

        val applied = applyReviewedManualRegeneration(existing, reviewManualRegeneration(existing, candidate))

        assertEquals(ManualDiagramRepeatPresentation.FIRST_AND_LAST, applied.repeatPresentation)
    }
}
