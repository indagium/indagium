package com.indagium

import com.indagium.diagram.ManualDiagramDocument
import com.indagium.diagram.ManualDiagramGroup
import com.indagium.diagram.ManualDiagramInteraction
import com.indagium.diagram.ManualInteractionAuthoring
import com.indagium.diagram.ManualRegenerationChangeKind
import com.indagium.diagram.applyReviewedManualRegeneration
import com.indagium.diagram.reviewManualRegeneration
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
}
