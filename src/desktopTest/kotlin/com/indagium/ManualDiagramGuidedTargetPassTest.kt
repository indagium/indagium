package com.indagium

import com.indagium.diagram.GuidedTargetPassState
import com.indagium.diagram.ManualDiagramDocument
import com.indagium.diagram.ManualDiagramInteraction
import com.indagium.diagram.advanceGuidedTargetPass
import com.indagium.diagram.beginGuidedTargetPass
import com.indagium.diagram.guidedTargetContext
import com.indagium.diagram.guidedTargetRow
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ManualDiagramGuidedTargetPassTest {
    private fun interaction(id: String, target: String?, order: Long) = ManualDiagramInteraction(
        id = id,
        sourceEntryIds = setOf(order.toInt()),
        fromParticipantId = "client",
        toParticipantId = target,
        label = id,
        order = order,
    )

    @Test
    fun passUsesStableGroupIdsAndAdvancesOnlyAfterRefreshingUnresolvedRows() {
        val document = ManualDiagramDocument(
            interactions = listOf(
                interaction("m1", null, 1),
                interaction("m2", null, 2),
            ),
        )
        val initial = beginGuidedTargetPass(document)
        requireNotNull(initial)
        assertEquals("individual:m1", initial.currentGroupId)
        assertEquals("individual:m2", advanceGuidedTargetPass(document, initial)?.currentGroupId)

        val resolved = document.copy(interactions = document.interactions.first().copy(toParticipantId = "service").let {
            listOf(it, document.interactions[1])
        })
        val afterResolve = advanceGuidedTargetPass(resolved, initial)
        assertEquals("individual:m2", afterResolve?.currentGroupId)
    }

    @Test
    fun contextIsLimitedToTheRepresentativeEvidenceNeighborhood() {
        val rowDocument = ManualDiagramDocument(interactions = listOf(interaction("m2", null, 2)))
        val row = requireNotNull(guidedTargetRow(rowDocument, GuidedTargetPassState(listOf("individual:m2"))))
        val entries = (1..5).map { id ->
            LogEntry(id, "10:00:00.00$id", LogLevel.I, "Tag$id", "line$id")
        }
        assertEquals(listOf(1, 2, 3), guidedTargetContext(row, entries).map { it.id })
        assertNull(beginGuidedTargetPass(ManualDiagramDocument(interactions = listOf(interaction("m1", "service", 1)))))
    }
}
