package com.indagium

import com.indagium.diagram.DiagramParticipant
import com.indagium.diagram.ManualDiagramDocument
import com.indagium.diagram.ManualDiagramGroup
import com.indagium.diagram.ManualDiagramInteraction
import com.indagium.diagram.ManualDiagramNote
import com.indagium.diagram.ManualInteractionAuthoring
import com.indagium.diagram.ManualMessageBulkAction
import com.indagium.diagram.ManualMessageFilter
import com.indagium.diagram.ManualMessageSort
import com.indagium.diagram.ManualMessageState
import com.indagium.diagram.MessageKind
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.applyManualMessageBulkAction
import com.indagium.diagram.buildManualMessageQueue
import com.indagium.diagram.suggestManualTarget
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ManualDiagramMessageQueueTest {
    private fun interaction(
        id: String,
        from: String = "client",
        to: String? = "service",
        label: String = id,
        order: Long = id.removePrefix("m").toLong(),
        groupKey: String? = null,
        enabled: Boolean = true,
        authoring: ManualInteractionAuthoring = ManualInteractionAuthoring.AUTO,
    ) = ManualDiagramInteraction(
        id = id,
        sourceEntryIds = setOf(order.toInt()),
        fromParticipantId = from,
        toParticipantId = to,
        label = label,
        kind = if (to == null) MessageKind.CALL else MessageKind.CALL,
        order = order,
        groupKey = groupKey,
        enabled = enabled,
        authoring = authoring,
    )

    @Test
    fun queueGroupsOnlyDurableKeysAndKeepsEvidenceCounts() {
        val document = ManualDiagramDocument(
            interactions = listOf(
                interaction("m2", label = "request id=?", groupKey = "request"),
                interaction("m1", label = "request id=1", groupKey = "request"),
                interaction("m3", to = null, label = "unresolved"),
            ),
        )

        val queue = buildManualMessageQueue(document)

        assertEquals(2, queue.rows.size)
        assertEquals(2, queue.rows.first().occurrenceCount)
        assertEquals(setOf(1, 2), queue.rows.first().sourceEntryIds)
        assertEquals(ManualMessageState.NEEDS_TARGET, queue.rows.last().state)
        assertEquals(1, queue.needsTargetCount)
    }

    @Test
    fun filtersAndSortsAreDeterministicAndDoNotHideEditedOrHiddenState() {
        val document = ManualDiagramDocument(
            interactions = listOf(
                interaction("m1", label = "hidden", enabled = false),
                interaction("m2", label = "edited", authoring = ManualInteractionAuthoring.EDITED),
                interaction("m3", label = "needs target", to = null),
            ),
        )

        assertEquals(listOf("individual:m3"), buildManualMessageQueue(document, ManualMessageFilter.NEEDS_TARGET).rows.map { it.id })
        assertEquals(listOf("individual:m2"), buildManualMessageQueue(document, ManualMessageFilter.EDITED).rows.map { it.id })
        assertEquals(listOf("individual:m1"), buildManualMessageQueue(document, ManualMessageFilter.HIDDEN).rows.map { it.id })
        assertEquals(
            listOf("individual:m3", "individual:m2", "individual:m1"),
            buildManualMessageQueue(document, sort = ManualMessageSort.STATE).rows.map { it.id },
        )
    }

    @Test
    fun targetSuggestionRequiresDeclaredSameThreadEvidence() {
        val participants = listOf(
            DiagramParticipant("client", "Client", ParticipantKind.TAG, tag = "Client"),
            DiagramParticipant("service", "Service", ParticipantKind.TAG, tag = "Service"),
        )
        val entries = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Client", "start", pid = 7, tid = 3),
            LogEntry(2, "10:00:00.001", LogLevel.I, "Service", "handled", pid = 7, tid = 3),
            LogEntry(3, "10:00:00.002", LogLevel.I, "Other", "unrelated", pid = 9, tid = 3),
        )

        val suggestion = suggestManualTarget(interaction("m1", to = null, order = 1), entries, participants)

        assertEquals("service", suggestion?.participantId)
        assertEquals(2, suggestion?.sourceEntryId)
        assertTrue(suggestion?.reason?.contains("same PID/TID") == true)
        assertEquals(null, suggestManualTarget(interaction("m1", to = null, order = 1), entries, emptyList()))
    }

    @Test
    fun bulkActionsMarkEditsAndInvalidSelectionsAreNoOps() {
        val original = ManualDiagramDocument(interactions = listOf(interaction("m1", to = null)))
        val target = applyManualMessageBulkAction(
            original,
            setOf("m1"),
            ManualMessageBulkAction.SetTarget("service"),
        )

        assertTrue(target.applied)
        assertEquals("service", target.document.interactions.single().toParticipantId)
        assertEquals(ManualInteractionAuthoring.EDITED, target.document.interactions.single().authoring)
        assertEquals(MessageKind.CALL, target.document.interactions.single().kind)

        val invalid = applyManualMessageBulkAction(
            original,
            setOf("missing"),
            ManualMessageBulkAction.Hide,
        )
        assertFalse(invalid.applied)
        assertSame(original, invalid.document)
    }

    @Test
    fun mergeFragmentAndNoteActionsRemainExplicitAndReversible() {
        val original = ManualDiagramDocument(
            interactions = listOf(interaction("m1"), interaction("m2", order = 2)),
        )
        val merged = applyManualMessageBulkAction(
            original,
            setOf("m1", "m2"),
            ManualMessageBulkAction.Merge("request"),
        ).document
        assertEquals(listOf("request", "request"), merged.interactions.map { it.groupKey })

        val ungrouped = applyManualMessageBulkAction(
            merged,
            setOf("m1", "m2"),
            ManualMessageBulkAction.Ungroup,
        ).document
        assertEquals(listOf(null, null), ungrouped.interactions.map { it.groupKey })

        val withFragment = applyManualMessageBulkAction(
            ungrouped,
            setOf("m1", "m2"),
            ManualMessageBulkAction.GroupAsFragment(ManualDiagramGroup("fragment", "Request", listOf("m1", "m2"))),
        ).document
        assertEquals(listOf("m1", "m2"), withFragment.groups.single().interactionIds)

        val withNote = applyManualMessageBulkAction(
            withFragment,
            setOf("m1"),
            ManualMessageBulkAction.AddNote(ManualDiagramNote("note", "client", "m1", "Observed boundary")),
        ).document
        assertEquals("Observed boundary", withNote.notes.single().text)
        assertEquals(ManualInteractionAuthoring.EDITED, withNote.interactions.first().authoring)
    }
}
