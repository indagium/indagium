package com.indagium

import com.indagium.diagram3.Seq3Authoring
import com.indagium.diagram3.Seq3Command
import com.indagium.diagram3.Seq3CustomMessageResult
import com.indagium.diagram3.Seq3CustomMessageSpec
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Fragment
import com.indagium.diagram3.Seq3FragmentKind
import com.indagium.diagram3.Seq3InsertionPosition
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3MessageEditResult
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3State
import com.indagium.diagram3.addSeq3CustomMessage
import com.indagium.diagram3.applySeq3Command
import com.indagium.diagram3.encodeSeq3Note
import com.indagium.diagram3.moveSeq3Message
import com.indagium.diagram3.moveSeq3OccurrenceOut
import com.indagium.diagram3.parseSeq3Note
import com.indagium.diagram3.toMermaid
import com.indagium.diagram3.undoSeq3Command
import com.indagium.diagram3.updateSeq3MessageTimestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val FIRST_LIFELINE_ORDINAL = 0
private const val SECOND_LIFELINE_ORDINAL = 1
private const val FIRST_MESSAGE_TIMESTAMP = 100L
private const val SECOND_MESSAGE_TIMESTAMP = 200L

// Midnight-rollover fixture for moveOutPlacesAPostMidnightOccurrenceAfterItsPreMidnightSourceUsingElapsedMillis:
// day-unrolled elapsed millis keep increasing across the rollover even though the raw wall clock
// (millis-of-day) resets to near-zero.
private const val MILLIS_PER_DAY = 86_400_000L
private const val PRE_MIDNIGHT_TS = 86_390_000L // 23:59:50.000 -- still day 0, so elapsed == wall clock
private const val POST_MIDNIGHT_WALL_TS = 5_000L // 00:00:05.000 wall clock, after the rollover
private const val POST_MIDNIGHT_ELAPSED_TS = MILLIS_PER_DAY + POST_MIDNIGHT_WALL_TS
private const val FOLLOWING_ANCHOR_WALL_TS = 10_000L // 00:00:10.000 wall clock, also after the rollover
private const val FOLLOWING_ANCHOR_ELAPSED_TS = MILLIS_PER_DAY + FOLLOWING_ANCHOR_WALL_TS

class Seq3CustomMessageTest {
    @Test
    fun customMessageUsesExplicitEndpointsPositionTimestampAndExistingFragmentWithoutFakeEvidence() {
        val document = baseDocument()
        val result = addSeq3CustomMessage(
            document,
            Seq3CustomMessageSpec(
                fromLifelineId = "A",
                toLifelineId = "B",
                text = "no issue path",
                timestampMillis = 150L,
                rawTimestamp = "10:00:00.150",
                position = Seq3InsertionPosition.BeforeMessage("m2"),
                fragmentId = "opt-1",
            ),
        ) as? Seq3CustomMessageResult.Added ?: error("expected custom message to be added")

        assertEquals(1, result.insertionIndex)
        assertEquals(listOf("m1", result.newMessageId, "m2"), result.document.messages.map { it.id })
        val message = result.document.messages[1]
        assertEquals("A", message.fromLifelineId)
        assertEquals("B", message.toLifelineId)
        assertEquals("no issue path", message.labelTemplate)
        assertEquals(150L, message.primaryTimestampMillis)
        assertEquals("10:00:00.150", message.primaryRawTimestamp)
        assertTrue(message.occurrences.isEmpty(), "custom rows must not invent a navigable log entry")
        assertEquals(Seq3Authoring.EDITED, message.authoring)
        assertEquals(Seq3State.EDITED, message.state)
        assertEquals(listOf("m1", result.newMessageId, "m2"), result.document.fragments.single().messageIds)
    }

    @Test
    fun customMessageRendersEvenWithoutEvidenceAndRoundTripsManualFields() {
        val result = addSeq3CustomMessage(
            baseDocument(),
            Seq3CustomMessageSpec("A", "B", "manual arrow", timestampMillis = 125L),
        ) as? Seq3CustomMessageResult.Added ?: error("expected custom message to be added")

        val mermaid = result.document.toMermaid()
        assertTrue(mermaid.contains("manual arrow"))
        val parsed = parseSeq3Note(encodeSeq3Note(result.document))
        assertNotNull(parsed)
        val restored = parsed.document.messages.single { it.id == result.newMessageId }
        assertEquals(125L, restored.manualTimestampMillis)
        assertEquals("manual arrow", restored.labelTemplate)
        assertTrue(restored.occurrences.isEmpty())
    }

    @Test
    fun customMessageRejectsMissingEndpointsAndInvalidPositions() {
        val missingSource = addSeq3CustomMessage(baseDocument(), Seq3CustomMessageSpec("missing", "B", "x"))
        assertEquals("Unknown source lifeline", (missingSource as Seq3CustomMessageResult.Rejected).reason)

        val missingTarget = addSeq3CustomMessage(baseDocument(), Seq3CustomMessageSpec("A", null, "x"))
        assertEquals("Target lifeline is required", (missingTarget as Seq3CustomMessageResult.Rejected).reason)

        val invalidPosition = addSeq3CustomMessage(
            baseDocument(),
            Seq3CustomMessageSpec("A", "B", "x", position = Seq3InsertionPosition.AtIndex(99)),
        )
        assertEquals("Invalid message insertion position", (invalidPosition as Seq3CustomMessageResult.Rejected).reason)
    }

    @Test
    fun timestampAndQueuePositionCanBeEditedIndependently() {
        val added = addSeq3CustomMessage(
            baseDocument(),
            Seq3CustomMessageSpec("A", "B", "x", timestampMillis = 100L, position = Seq3InsertionPosition.End),
        ) as? Seq3CustomMessageResult.Added ?: error("expected custom message to be added")

        val timestampResult = updateSeq3MessageTimestamp(added.document, added.newMessageId, 75L, "10:00:00.075")
        val timestampDocument = (timestampResult as Seq3MessageEditResult.Updated).document
        assertEquals(75L, timestampDocument.messages.last().primaryTimestampMillis)
        assertEquals("10:00:00.075", timestampDocument.messages.last().primaryRawTimestamp)

        val moved = moveSeq3Message(timestampDocument, added.newMessageId, Seq3InsertionPosition.AtIndex(1))
        val movedDocument = (moved as Seq3MessageEditResult.Updated).document
        assertEquals(listOf("m1", added.newMessageId, "m2"), movedDocument.messages.map { it.id })
    }

    @Test
    fun generatedMessagesCannotBeMoved() {
        val document = baseDocument()
        val result = moveSeq3Message(document, "m1", Seq3InsertionPosition.End)
        assertFalse(result is Seq3MessageEditResult.Updated)
        val rejected = result as? Seq3MessageEditResult.Rejected ?: error("expected move to be rejected")
        assertEquals("Only custom messages can be moved", rejected.reason)
    }

    @Test
    fun oneRepeatedOccurrenceCanBeMovedOutAtItsOwnTimestamp() {
        val grouped = baseDocument().copy(
            messages = baseDocument().messages.map { message ->
                if (message.id == "m1") message.copy(
                    occurrences = listOf(
                        message.occurrences.single(),
                        Seq3Occurrence(150, 150L, "10:00:00.150", 1, 1, 'I', "m1 later evidence"),
                    ),
                ) else message
            },
        )

        val result = moveSeq3OccurrenceOut(grouped, "m1", 150)
        val updated = (result as? Seq3MessageEditResult.Updated)?.document ?: error("expected split")

        assertEquals(3, updated.messages.size)
        assertEquals(listOf(100L, 150L, 200L), updated.messages.map { it.primaryTimestampMillis })
        assertEquals(listOf(100), updated.messages[0].occurrences.map { it.entryId })
        assertEquals(listOf(150), updated.messages[1].occurrences.map { it.entryId })
        assertEquals(Seq3Authoring.EDITED, updated.messages[0].authoring)
        assertEquals(Seq3Authoring.EDITED, updated.messages[1].authoring)
        assertEquals(updated.messages.map { it.id }.toSet(), updated.fragments.single().messageIds.toSet())
    }

    @Test
    fun moveOutPlacesAPostMidnightOccurrenceAfterItsPreMidnightSourceUsingElapsedMillis() {
        // m1's second occurrence actually happened just after midnight (day-unrolled elapsed =
        // POST_MIDNIGHT_ELAPSED_TS), but its raw millis-of-day (POST_MIDNIGHT_WALL_TS) reads
        // *earlier* than m1's remaining pre-midnight occurrence and m2's anchor. Comparing raw
        // timestamps would reinsert the extracted message before both; comparing elapsed millis
        // places it correctly between them.
        val document = Seq3Document(
            lifelines = listOf(
                Seq3Lifeline("A", "Alpha", setOf("A"), FIRST_LIFELINE_ORDINAL),
                Seq3Lifeline("B", "Beta", setOf("B"), SECOND_LIFELINE_ORDINAL),
            ),
            messages = listOf(
                Seq3Message(
                    id = "m1",
                    match = Seq3Match("A", "m1 label"),
                    fromLifelineId = "A",
                    toLifelineId = "B",
                    labelTemplate = "m1 label",
                    occurrences = listOf(
                        Seq3Occurrence(
                            1, PRE_MIDNIGHT_TS, "23:59:50.000", 1, 1, 'I', "before midnight",
                            elapsedMillis = PRE_MIDNIGHT_TS,
                        ),
                        Seq3Occurrence(
                            2, POST_MIDNIGHT_WALL_TS, "00:00:05.000", 1, 1, 'I', "after midnight",
                            elapsedMillis = POST_MIDNIGHT_ELAPSED_TS,
                        ),
                    ),
                ),
                Seq3Message(
                    id = "m2",
                    match = Seq3Match("B", "m2 label"),
                    fromLifelineId = "B",
                    toLifelineId = "A",
                    labelTemplate = "m2 label",
                    occurrences = listOf(
                        Seq3Occurrence(
                            3, FOLLOWING_ANCHOR_WALL_TS, "00:00:10.000", 1, 1, 'I', "anchor after midnight",
                            elapsedMillis = FOLLOWING_ANCHOR_ELAPSED_TS,
                        ),
                    ),
                ),
            ),
        )

        val result = moveSeq3OccurrenceOut(document, "m1", 2)
        val updated = (result as? Seq3MessageEditResult.Updated)?.document ?: error("expected split")

        val extractedId = updated.messages.map { it.id }.single { it !in setOf("m1", "m2") }
        assertEquals(
            listOf("m1", extractedId, "m2"),
            updated.messages.map { it.id },
            "elapsed order is pre-midnight m1 (23:59:50) < extracted occurrence (day+5s) < m2's " +
                "anchor (day+10s); comparing raw millis-of-day would instead read the extracted " +
                "occurrence (00:00:05) and m2 (00:00:10) as both earlier than m1 (23:59:50)",
        )
    }

    @Test
    fun commandPathAddsCustomMessageAndKeepsOneStepUndo() {
        val document = baseDocument()
        val result = applySeq3Command(
            document,
            Seq3Command.AddCustomMessage(Seq3CustomMessageSpec("A", "B", "command message", position = Seq3InsertionPosition.Start)),
        )
        assertTrue(result.applied)
        assertEquals(document, result.undo?.let(::undoSeq3Command))
        assertEquals("command message", result.document.messages.first().labelTemplate)
    }

    private fun baseDocument() = Seq3Document(
        lifelines = listOf(
            Seq3Lifeline("A", "Alpha", setOf("A"), FIRST_LIFELINE_ORDINAL),
            Seq3Lifeline("B", "Beta", setOf("B"), SECOND_LIFELINE_ORDINAL),
        ),
        messages = listOf(
            message("m1", "A", "B", FIRST_MESSAGE_TIMESTAMP),
            message("m2", "B", "A", SECOND_MESSAGE_TIMESTAMP),
        ),
        fragments = listOf(Seq3Fragment("opt-1", Seq3FragmentKind.OPT, "no issue", listOf("m1", "m2"))),
    )

    private fun message(id: String, from: String, to: String, timestamp: Long) = Seq3Message(
        id = id,
        match = Seq3Match(from, "$id label"),
        fromLifelineId = from,
        toLifelineId = to,
        labelTemplate = "$id label",
        occurrences = listOf(Seq3Occurrence(timestamp.toInt(), timestamp, "10:00:00.000", 1, 1, 'I', "$id evidence")),
    )
}
