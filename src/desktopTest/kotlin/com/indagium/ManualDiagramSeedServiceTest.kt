package com.indagium

import com.indagium.diagram.DiagramMessage
import com.indagium.diagram.DiagramParticipant
import com.indagium.diagram.MessageEvidence
import com.indagium.diagram.MessageKind
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.SeqDiagram
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.extractManualParameters
import com.indagium.diagram.manualDocumentFromDiagram
import com.indagium.diagram.manualInteractionGroupKey
import com.indagium.diagram.normalizeManualMessage
import com.indagium.diagram.stripDiagramPresentationPrefixes
import com.indagium.ui.manualKindAfterEndpointChange
import com.indagium.model.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManualDiagramSeedServiceTest {
    @Test
    fun normalizesVolatileLogValuesButRetainsNamedParameters() {
        assertEquals("request id=? peer=?", normalizeManualMessage("request id=42 peer=api-7"))
        assertEquals(
            listOf("id" to "42", "peer" to "api-7"),
            extractManualParameters("request id=42 peer=api-7").map { it.name to it.value },
        )
        assertEquals(
            listOf("arg1" to "42", "arg2" to "ready"),
            extractManualParameters("request(42, ready)").map { it.name to it.value },
        )
    }

    @Test
    fun groupingIncludesEndpointsAndKindWithoutIncludingOccurrenceValues() {
        val call = manualInteractionGroupKey(null, null, "client", "service", MessageKind.CALL, "request id=1")
        val sameShape = manualInteractionGroupKey(null, null, "client", "service", MessageKind.CALL, "request id=2")
        val reverse = manualInteractionGroupKey(null, null, "service", "client", MessageKind.CALL, "request id=2")
        val returned = manualInteractionGroupKey(null, null, "client", "service", MessageKind.RETURN, "request id=2")

        assertEquals(call, sameShape)
        assertNotEquals(call, reverse)
        assertNotEquals(call, returned)
    }

    @Test
    fun sourceSeedUsesStructuralOperationAndOccurrenceParameters() {
        val participants = listOf(
            DiagramParticipant("client", "Client", ParticipantKind.TAG, tag = "Client"),
            DiagramParticipant("service", "Service", ParticipantKind.TAG, tag = "Service"),
        )
        val message = DiagramMessage(
            fromIdx = 0,
            toIdx = 1,
            label = "fetch id=42",
            entryId = 7,
            ts = "10:00:00.000",
            level = LogLevel.I,
            kind = MessageKind.CALL,
            sourceOperationId = "operation:fetch",
            sourceLogSiteId = "site:7",
        )

        val interaction = manualDocumentFromDiagram(SeqDiagram(SeqDiagramSpec(), participants, listOf(message)))
            .interactions.single()

        assertEquals("fetch", interaction.operation)
        assertEquals(listOf("id" to "42"), interaction.parameters.map { it.name to it.value })
        assertNull(interaction.label)
        assertEquals("client", interaction.fromParticipantId)
        assertEquals("service", interaction.toParticipantId)
        assertEquals(MessageKind.CALL, interaction.kind)
        assertEquals("source:operation:fetch|site:7|client|service|CALL|fetch id=?", interaction.groupKey)
    }

    @Test
    fun logSeedKeepsTheWholeMessageAsLiteralTextAndDropsPresentationPrefixes() {
        val participants = listOf(
            DiagramParticipant("usb", "USB", ParticipantKind.TAG, tag = "USB"),
        )
        val message = DiagramMessage(
            fromIdx = 0,
            toIdx = 0,
            label = "+0.000  USB device(detached=vendorId=0BDA, productId=8153)",
            entryId = 7,
            ts = "10:00:00.000",
            level = LogLevel.I,
            kind = MessageKind.SELF,
            evidence = MessageEvidence.LOG,
        )

        val interaction = manualDocumentFromDiagram(SeqDiagram(SeqDiagramSpec(), participants, listOf(message)))
            .interactions.single()

        assertEquals("USB device(detached=vendorId=0BDA, productId=8153)", interaction.operation)
        assertEquals(emptyList(), interaction.parameters)
        assertEquals(interaction.operation, interaction.label)
    }

    @Test
    fun seedIgnoresSupplementalStructuralMessagesWhenPrimaryEvidenceExists() {
        val participant = DiagramParticipant("usb", "USB", ParticipantKind.TAG, tag = "USB")
        val primary = DiagramMessage(
            fromIdx = 0, toIdx = 0, label = "USB poll", entryId = 7,
            ts = "10:00:00.000", level = LogLevel.I, kind = MessageKind.SELF,
            evidence = MessageEvidence.LOG, primary = true,
        )
        val supplemental = primary.copy(label = "handoff", evidence = MessageEvidence.THREAD_HANDOFF, primary = false)

        val interactions = manualDocumentFromDiagram(
            SeqDiagram(SeqDiagramSpec(), listOf(participant), listOf(primary, supplemental)),
        ).interactions

        assertEquals(1, interactions.size)
        assertTrue(interactions.single().operation.contains("USB poll"))
    }

    @Test
    fun stripsTimestampAndElapsedPrefixesTogether() {
        assertEquals(
            "USB poll(tick=portCount=4)",
            stripDiagramPresentationPrefixes("10:00:00.000  +0.040  USB poll(tick=portCount=4)"),
        )
    }

    @Test
    fun changingManualEndpointsKeepsSelfAndCallKindsInSync() {
        assertEquals(MessageKind.CALL, manualKindAfterEndpointChange(MessageKind.SELF, "client", "service"))
        assertEquals(MessageKind.SELF, manualKindAfterEndpointChange(MessageKind.CALL, "client", "client"))
        assertEquals(MessageKind.RETURN, manualKindAfterEndpointChange(MessageKind.RETURN, "client", "client"))
    }
}
