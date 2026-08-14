@file:Suppress("MaxLineLength")

package com.indagium

import com.indagium.diagram.DiagramCoverage
import com.indagium.diagram.DiagramParticipant
import com.indagium.diagram.ManualDiagramDocument
import com.indagium.diagram.ManualDiagramEvidence
import com.indagium.diagram.ManualDiagramInteraction
import com.indagium.diagram.ManualDiagramMessageDefinition
import com.indagium.diagram.ManualDocumentIssueCode
import com.indagium.diagram.ManualInteractionAuthoring
import com.indagium.diagram.ManualMessageCapture
import com.indagium.diagram.ManualCaptureSource
import com.indagium.diagram.ManualMessageMatch
import com.indagium.diagram.ManualMessageMatchInput
import com.indagium.diagram.ManualMessageRepeatMode
import com.indagium.diagram.ManualMessageRepeatPolicy
import com.indagium.diagram.ManualMessageStateKind
import com.indagium.diagram.MessageKind
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.applyReviewedManualRegenerationSpec
import com.indagium.diagram.buildManualSequenceDiagram
import com.indagium.diagram.canonicalizeManualMessages
import com.indagium.diagram.compileManualMessageMatch
import com.indagium.diagram.derivedManualMessageOrder
import com.indagium.diagram.encodeDiagramNote
import com.indagium.diagram.matchManualMessage
import com.indagium.diagram.mergeManualMessageDefinitions
import com.indagium.diagram.moveManualMessageOccurrenceOut
import com.indagium.diagram.normalizeManualDocument
import com.indagium.diagram.parseDiagramNote
import com.indagium.diagram.reviewManualMessageRegeneration
import com.indagium.diagram.reviewManualRegenerationSpec
import com.indagium.diagram.restoreManualRegenerationSpec
import com.indagium.diagram.setManualMessageOrderOverride
import com.indagium.diagram.unmergeManualMessageDefinition
import com.indagium.diagram.validateManualDocument
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManualDiagramV2ContractTest {
    @Test
    fun legacyOccurrencesCanonicalizeToOneMessageWithoutDroppingEvidence() {
        val document = ManualDiagramDocument(
            interactions = listOf(
                interaction("o1", "poll id=α", 1, "poll", "10:00:00.100"),
                interaction("o2", "poll id=β", 2, "poll", "10:00:00.200"),
            ),
        )

        val canonical = canonicalizeManualMessages(document)

        assertEquals(1, canonical.messages.size)
        assertEquals(listOf("o1", "o2"), canonical.messages.single().definition.occurrenceIds)
        assertEquals(mapOf("id" to "α"), canonical.messages.single().occurrences.first().captureValues)
        assertEquals(setOf(1, 2), canonical.messages.single().occurrences.flatMap { it.evidence }.map { it.entryId }.toSet())
    }

    @Test
    fun v5ValidationRejectsDuplicateAndDanglingOccurrenceOwnership() {
        val occurrence = interaction("o1", "tick", 1, "one", "10:00:00.100")
        val other = interaction("o2", "tick", 2, "two", "10:00:00.200")
        val definition = definition("m1", listOf("o1"), "tick")
        val malformed = ManualDiagramDocument(
            interactions = listOf(occurrence, other),
            messages = listOf(
                definition,
                definition.copy(id = "m2", occurrenceIds = listOf("o1", "missing")),
            ),
        )

        val codes = validateManualDocument(malformed).issues.map { it.code }.toSet()

        assertTrue(ManualDocumentIssueCode.DUPLICATE_OCCURRENCE_OWNERSHIP in codes)
        assertTrue(ManualDocumentIssueCode.DANGLING_OCCURRENCE in codes)
        assertTrue(ManualDocumentIssueCode.UNOWNED_OCCURRENCE in codes)
        assertFalse(validateManualDocument(malformed).isValid)
    }

    @Test
    fun capturesAreProvenAgainstEveryOccurrenceAndTokenNamesMustAgree() {
        val match = ManualMessageMatch(
            textPattern = "tick id={id}",
            captures = listOf(ManualMessageCapture("id", ManualCaptureSource.NAMED_VALUE)),
        )
        val good = interaction("o1", "tick id=7", 1, "poll", "10:00:00.100").copy(captureValues = mapOf("id" to "7"))
        val valid = ManualDiagramDocument(
            interactions = listOf(good),
            messages = listOf(definition("m", listOf("o1"), match, state = ManualMessageStateKind.AUTO)),
        )
        val bad = valid.copy(interactions = listOf(good.copy(captureValues = mapOf("id" to "8"))))

        assertTrue(validateManualDocument(valid).isValid, validateManualDocument(valid).issues.toString())
        assertTrue(ManualDocumentIssueCode.CAPTURE_VALUE_MISMATCH in validateManualDocument(bad).issues.map { it.code })
        assertNull(matchManualMessage(match.copy(captures = listOf(ManualMessageCapture("other", ManualCaptureSource.AUTHOR))), "tick id=7"))
        assertTrue(compileManualMessageMatch(listOf(
            ManualMessageMatchInput("o1", "tick id=7"),
            ManualMessageMatchInput("o2", "tick id=8"),
        )).compiled)
    }

    @Test
    fun targetlessMessagesNeedNeedsTargetStateAndValidEndpoints() {
        val targetless = ManualDiagramDocument(
            interactions = listOf(interaction("o", "queued", 1, "one", "10:00:00.100")),
            messages = listOf(definition("m", listOf("o"), "queued", to = null, state = ManualMessageStateKind.NEEDS_TARGET)),
        )
        val malformed = targetless.copy(messages = listOf(targetless.messages.single().copy(state = ManualMessageStateKind.AUTO)))

        assertTrue(validateManualDocument(targetless).isValid)
        assertTrue(ManualDocumentIssueCode.INVALID_TARGETLESS_STATE in validateManualDocument(malformed).issues.map { it.code })
        assertTrue(ManualDocumentIssueCode.INVALID_ENDPOINT in validateManualDocument(
            targetless.copy(messages = listOf(targetless.messages.single().copy(fromParticipantId = " "))),
        ).issues.map { it.code })
    }

    @Test
    fun repeatPolicyIsOwnedByEachMessageAndControlsCanonicalRendering() {
        val interactions = (1..3).map { index -> interaction("o$index", "tick", index.toLong(), "poll", "10:00:00.00$index") }
        val definition = definition("m", interactions.map { it.id }, "tick").copy(
            repeatPolicy = ManualMessageRepeatPolicy(ManualMessageRepeatMode.COLLAPSE_CONSECUTIVE, 3),
        )
        val participants = listOf(
            DiagramParticipant("one", "One", ParticipantKind.TAG),
            DiagramParticipant("two", "Two", ParticipantKind.TAG),
        )
        val spec = SeqDiagramSpec(participants = participants, manualDocument = ManualDiagramDocument(interactions, messages = listOf(definition)))
        val collapsed = buildManualSequenceDiagram(spec, (1..3).map { id -> LogEntry(id, "10:00:00.00$id", LogLevel.I, "one", "tick") }, participants, DiagramCoverage(3, 3, 0, 0), mutableListOf())
        val every = buildManualSequenceDiagram(spec.copy(manualDocument = spec.manualDocument.copy(messages = listOf(definition.copy(
            repeatPolicy = definition.repeatPolicy.copy(mode = ManualMessageRepeatMode.EVERY_OCCURRENCE),
        )))), (1..3).map { id -> LogEntry(id, "10:00:00.00$id", LogLevel.I, "one", "tick") }, participants, DiagramCoverage(3, 3, 0, 0), mutableListOf())

        assertEquals(1, collapsed.messages.size)
        assertEquals(3, collapsed.messages.single().repeatCount)
        assertEquals(3, every.messages.size)
    }

    @Test
    fun derivedOrderUsesEvidenceTimeAndOverridesStayInsideTimestampTies() {
        val document = normalizeManualDocument(ManualDiagramDocument(interactions = listOf(
            interaction("a", "a", 20, "a", "10:00:00.100"),
            interaction("b", "b", 10, "b", "10:00:00.100"),
            interaction("c", "c", 1, "c", "10:00:00.200"),
        )))
        val ordered = derivedManualMessageOrder(document)
        val a = document.messages.first { it.occurrenceIds == listOf("a") }
        val applied = setManualMessageOrderOverride(document, a.id, 36_000_100L, 0)
        val rejected = setManualMessageOrderOverride(document, a.id, 36_000_200L, 0)

        assertEquals(listOf("b", "a", "c"), ordered.flatMap { it.definition.occurrenceIds })
        assertTrue(applied.applied, applied.reason ?: "same-timestamp override was rejected")
        assertFalse(rejected.applied)
    }

    @Test
    fun mergeMoveOutAndUnmergePreserveExactOccurrenceOwnership() {
        val first = interaction("o1", "poll id=1", 1, "one", "10:00:00.100")
        val second = interaction("o2", "poll id=2", 2, "two", "10:00:00.200")
        val legacy = ManualDiagramDocument(interactions = listOf(first, second))
        val normalized = normalizeManualDocument(legacy)
        val merged = mergeManualMessageDefinitions(normalized, normalized.messages.map { it.id }.toSet(), "joined")
        val mergedDocument = merged.document
        val mergedDefinition = mergedDocument.messages.single()
        val moved = moveManualMessageOccurrenceOut(mergedDocument, mergedDefinition.id, "o1", "moved")
        val unmerged = unmergeManualMessageDefinition(mergedDocument, mergedDefinition.id)

        assertTrue(merged.applied, merged.reason ?: "merge rejected")
        assertTrue(validateManualDocument(mergedDocument).isValid)
        assertEquals(setOf("o1", "o2"), mergedDefinition.occurrenceIds.toSet())
        assertTrue(moved.applied, moved.reason ?: "move-out rejected")
        assertTrue(validateManualDocument(moved.document).isValid)
        assertTrue(unmerged.applied, unmerged.reason ?: "unmerge rejected")
        assertTrue(validateManualDocument(unmerged.document).isValid)
    }

    @Test
    fun regenerationComparesMessagesAndSnapshotsTheCompleteSpec() {
        val oldInteraction = interaction("o1", "old", 1, "one", "10:00:00.100")
        val oldDocument = normalizeManualDocument(ManualDiagramDocument(interactions = listOf(oldInteraction)))
        val candidateDocument = normalizeManualDocument(ManualDiagramDocument(interactions = listOf(
            oldInteraction.copy(label = "new"),
            interaction("o2", "added", 2, "two", "10:00:00.200"),
        )))
        val oldSpec = SeqDiagramSpec(title = "before", manualDocument = oldDocument)
        val candidateSpec = SeqDiagramSpec(title = "after", manualDocument = candidateDocument)
        val review = reviewManualMessageRegeneration(oldDocument, candidateDocument)
        val specReview = reviewManualRegenerationSpec(oldSpec, candidateSpec)

        assertTrue(review.rows.any { it.kind.name == "CHANGED_AUTO" })
        assertTrue(review.rows.any { it.kind.name == "NEW" })
        assertEquals(oldSpec, restoreManualRegenerationSpec(specReview.snapshot))
        assertEquals("after", applyReviewedManualRegenerationSpec(specReview).title)
    }

    @Test
    fun codecRejectsMalformedV5OwnershipButLegacyNoteStillDecodes() {
        val occurrence = interaction("o", "tick", 1, "one", "10:00:00.100")
        val malformed = ManualDiagramDocument(
            interactions = listOf(occurrence),
            messages = listOf(definition("m", listOf("missing"), "tick")),
        )
        val spec = SeqDiagramSpec(
            participants = listOf(
                DiagramParticipant("one", "One", ParticipantKind.TAG),
                DiagramParticipant("two", "Two", ParticipantKind.TAG),
            ),
            manualDocument = malformed,
        )
        val legacySpec = SeqDiagramSpec(
            participants = listOf(
                DiagramParticipant("one", "One", ParticipantKind.TAG),
                DiagramParticipant("two", "Two", ParticipantKind.TAG),
            ),
            manualDocument = ManualDiagramDocument(interactions = listOf(occurrence)),
        )

        assertNull(parseDiagramNote(encodeDiagramNote(spec, "sequenceDiagram\n")))
        assertNotNull(parseDiagramNote(encodeDiagramNote(legacySpec, "sequenceDiagram\n")))
    }

    @Test
    fun v2EditorDiscriminatorRoundTripsThroughTheV5Codec() {
        val parsed = assertNotNull(
            parseDiagramNote(encodeDiagramNote(SeqDiagramSpec(editorVersion = 2), "sequenceDiagram\n")),
        )

        assertEquals(2, parsed.spec.editorVersion)
    }

    private fun interaction(id: String, label: String, order: Long, group: String, timestamp: String): ManualDiagramInteraction =
        ManualDiagramInteraction(
            id = id,
            sourceEntryIds = setOf(order.toInt()),
            fromParticipantId = "one",
            toParticipantId = "two",
            label = label,
            groupKey = group,
            order = order,
            evidence = listOf(ManualDiagramEvidence(order.toInt(), timestamp, LogLevel.I)),
        )

    private fun definition(
        id: String,
        occurrenceIds: List<String>,
        text: String,
        to: String? = "two",
        state: ManualMessageStateKind = ManualMessageStateKind.AUTO,
    ): ManualDiagramMessageDefinition = definition(
        id,
        occurrenceIds,
        ManualMessageMatch(textPattern = text),
        to,
        state,
    )

    private fun definition(
        id: String,
        occurrenceIds: List<String>,
        match: ManualMessageMatch,
        to: String? = "two",
        state: ManualMessageStateKind = ManualMessageStateKind.AUTO,
    ): ManualDiagramMessageDefinition = ManualDiagramMessageDefinition(
        id = id,
        occurrenceIds = occurrenceIds,
        match = match,
        fromParticipantId = "one",
        toParticipantId = to,
        labelTemplate = match.textPattern,
        kind = if (to == null) MessageKind.CALL else MessageKind.CALL,
        state = state,
        authoring = ManualInteractionAuthoring.AUTO,
    )
}
