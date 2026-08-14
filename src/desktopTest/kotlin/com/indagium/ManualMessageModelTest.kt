package com.indagium

import com.indagium.diagram.ManualDiagramDocument
import com.indagium.diagram.ManualDiagramEvidence
import com.indagium.diagram.ManualDiagramInteraction
import com.indagium.diagram.ManualMessageMatchInput
import com.indagium.diagram.DiagramParticipant
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.canonicalizeManualMessages
import com.indagium.diagram.compileManualMessageMatch
import com.indagium.diagram.encodeDiagramNote
import com.indagium.diagram.normalizeManualDocument
import com.indagium.diagram.parseDiagramNote
import com.indagium.diagram.selectManualQueueMessageIds
import com.indagium.diagram.setManualMessageOrderOverride
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.model.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ManualMessageModelTest {
    @Test
    fun compilerPersistsNamedCapturesAndProvesUnicodePunctuationOccurrences() {
        val result = compileManualMessageMatch(
            listOf(
                ManualMessageMatchInput("o1", "USB poll: portCount=4 devices=3/2 label=✓"),
                ManualMessageMatchInput("o2", "USB poll: portCount=5 devices=3/2 label=✓"),
            ),
        )

        assertTrue(result.compiled, result.toString())
        assertEquals("USB poll: portCount={portCount} devices=3/2 label=✓", result.preview)
        assertEquals(mapOf("portCount" to "4"), result.captureValuesByOccurrence["o1"])
        assertEquals(mapOf("portCount" to "5"), result.captureValuesByOccurrence["o2"])
    }

    @Test
    fun legacyGroupsBecomeOneMessageWithEvidenceAndDisagreementSplitsConservatively() {
        val first = interaction("m1", "USB poll: portCount=4", 1, to = "Device")
        val second = interaction("m2", "USB poll: portCount=5", 2, to = "Device")
        val disagreement = interaction("m3", "USB poll: portCount=6", 3, to = "Other")
        val canonical = canonicalizeManualMessages(ManualDiagramDocument(interactions = listOf(first, second, disagreement)))

        assertEquals(2, canonical.messages.size)
        val repeated = canonical.messages.first { it.definition.occurrenceIds.size == 2 }
        assertEquals(setOf(1, 2), repeated.occurrences.flatMap { it.evidence }.map { it.entryId }.toSet())
        assertEquals("{portCount}", repeated.occurrences.first().captureValues.keys.single().let { "{$it}" })
        assertTrue(canonical.diagnostics.isEmpty())
        assertTrue(normalizeManualDocument(ManualDiagramDocument(interactions = listOf(first, second))).messages.isNotEmpty())
    }

    @Test
    fun versionFiveRoundTripRetainsMessageDefinitionCapturesAndEvidence() {
        val document = normalizeManualDocument(
            ManualDiagramDocument(
                interactions = listOf(
                    interaction("m1", "event id=α", 10, to = "B"),
                    interaction("m2", "event id=β", 11, to = "B"),
                ),
            ),
        )
        val parsed = parseDiagramNote(
            encodeDiagramNote(
                SeqDiagramSpec(
                    participants = listOf(
                        DiagramParticipant("A", "A", ParticipantKind.TAG),
                        DiagramParticipant("B", "B", ParticipantKind.TAG),
                    ),
                    manualDocument = document,
                ),
                "sequenceDiagram\n  A->>B: event\n",
            ),
        )

        assertNotNull(parsed, "encoded note could not be parsed:\n${encodeDiagramNote(SeqDiagramSpec(manualDocument = document), "sequenceDiagram\\n")}")
        assertTrue(parsed.source.isNotBlank())
        assertTrue(encodeDiagramNote(SeqDiagramSpec(manualDocument = document), "sequenceDiagram\n")
            .contains("indagium:diagram v5"))
        assertEquals(document.messages, parsed.spec.manualDocument.messages)
        assertEquals(document.interactions.map { it.evidence }, parsed.spec.manualDocument.interactions.map { it.evidence })
    }

    @Test
    fun queueSelectionUsesVisibleMessageOrderForRangeAndAdditiveSelection() {
        val (range, anchor) = selectManualQueueMessageIds(
            visibleMessageIds = listOf("m1", "m2", "m3", "m4"),
            selectedMessageIds = setOf("m1"),
            anchorMessageId = "m1",
            clickedMessageId = "m3",
            range = true,
        )
        assertEquals(setOf("m1", "m2", "m3"), range)
        assertEquals("m1", anchor)
        val (additive, additiveAnchor) = selectManualQueueMessageIds(
            visibleMessageIds = listOf("m1", "m2", "m3", "m4"),
            selectedMessageIds = range,
            anchorMessageId = anchor,
            clickedMessageId = "m4",
            additive = true,
        )
        assertEquals(setOf("m1", "m2", "m3", "m4"), additive)
        assertEquals("m4", additiveAnchor)
    }

    @Test
    fun orderOverrideAcceptsOnlySameTimestampNeighbors() {
        val document = normalizeManualDocument(
            ManualDiagramDocument(
                interactions = listOf(
                    interaction("a", "a", 1, "B").copy(groupKey = "a", evidence = listOf(ManualDiagramEvidence(1, "10:00:00.100", LogLevel.I))),
                    interaction("b", "b", 2, "B").copy(groupKey = "b", evidence = listOf(ManualDiagramEvidence(2, "10:00:00.100", LogLevel.I))),
                    interaction("c", "c", 3, "B").copy(groupKey = "c", evidence = listOf(ManualDiagramEvidence(3, "10:00:00.200", LogLevel.I))),
                ),
            ),
        )
        val target = document.messages.first { it.occurrenceIds == listOf("a") }
        val applied = setManualMessageOrderOverride(document, target.id, 36_000_100L, 0)
        assertTrue(applied.applied, applied.reason ?: "pin was rejected")
        assertEquals(0, applied.document.messages.first { it.id == target.id }.orderOverride?.tieRank)
        val rejected = setManualMessageOrderOverride(document, target.id, 36_000_200L, 0)
        assertTrue(!rejected.applied)
    }

    private fun interaction(id: String, label: String, order: Long, to: String): ManualDiagramInteraction =
        ManualDiagramInteraction(
            id = id,
            sourceEntryIds = setOf(order.toInt()),
            fromParticipantId = "A",
            toParticipantId = to,
            label = label,
            order = order,
            groupKey = "usb-poll",
            evidence = listOf(ManualDiagramEvidence(order.toInt(), "10:00:00.00$order", LogLevel.I)),
        )
}
