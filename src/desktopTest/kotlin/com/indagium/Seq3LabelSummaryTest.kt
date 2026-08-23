package com.indagium

import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.seq3ChronologicalFallbacks
import com.indagium.diagram3.seq3ChronologicalOrder
import com.indagium.diagram3.seq3DisplayTimestamp
import com.indagium.diagram3.seq3PrefixedLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Direct unit tests for WP10's shared `[#n] [ts] label` prefix helper — the single place
 *  Seq3Layout, Seq3Raster (via Seq3Layout's own row geometry) and Seq3Emitters all compose this
 *  string, so canvas/PNG/text can never quietly disagree about its exact format. */
class Seq3LabelSummaryTest {
    // ── seq3PrefixedLabel ────────────────────────────────────────────────────────────────────

    @Test
    fun neitherToggleOnLeavesTheLabelByteIdentical() {
        val result = seq3PrefixedLabel(
            "hello",
            sequenceNumber = 3,
            rawTimestamp = "10:00:00.000",
            timestampMillis = 1_000L,
            showSequenceNumbers = false,
            showTimestamps = false,
        )
        assertEquals("hello", result)
    }

    @Test
    fun numberOnlyPrefixesJustTheCallNumber() {
        val result = seq3PrefixedLabel(
            "hello",
            sequenceNumber = 3,
            rawTimestamp = "10:00:00.000",
            timestampMillis = 1_000L,
            showSequenceNumbers = true,
            showTimestamps = false,
        )
        assertEquals("[#3] hello", result)
    }

    @Test
    fun timestampOnlyPrefixesJustTheClockTime() {
        val result = seq3PrefixedLabel(
            "hello",
            sequenceNumber = 3,
            rawTimestamp = "10:00:00.000",
            timestampMillis = 1_000L,
            showSequenceNumbers = false,
            showTimestamps = true,
        )
        assertEquals("[10:00:00.000] hello", result)
    }

    @Test
    fun bothOnPrefixesTheNumberBeforeTheTimestamp() {
        val result = seq3PrefixedLabel(
            "hello",
            sequenceNumber = 3,
            rawTimestamp = "10:00:00.000",
            timestampMillis = 1_000L,
            showSequenceNumbers = true,
            showTimestamps = true,
        )
        assertEquals("[#3] [10:00:00.000] hello", result)
    }

    @Test
    fun numberOnIsANoOpWhenTheRowHasNoAssignedNumber() {
        // A Note/Elision row is never assigned a sequence number by either caller — sequenceNumber
        // is null in that case even with the toggle on, and this must not print a bare "[#null]".
        val result = seq3PrefixedLabel(
            "hello",
            sequenceNumber = null,
            rawTimestamp = "",
            timestampMillis = null,
            showSequenceNumbers = true,
            showTimestamps = false,
        )
        assertEquals("hello", result)
    }

    @Test
    fun timestampOnFallsBackSilentlyWhenNeitherRawNorMillisIsAvailable() {
        val result = seq3PrefixedLabel(
            "hello",
            sequenceNumber = null,
            rawTimestamp = "",
            timestampMillis = null,
            showSequenceNumbers = false,
            showTimestamps = true,
        )
        assertEquals("hello", result, "a brief/RAW row with no parseable timestamp must not print an empty '[]' tag")
    }

    // ── seq3DisplayTimestamp ─────────────────────────────────────────────────────────────────

    @Test
    fun rawTimestampWinsOverMillisWhenBothArePresent() {
        assertEquals("09:15:22.500", seq3DisplayTimestamp("09:15:22.500", 999_999L))
    }

    @Test
    fun blankRawTimestampFallsBackToFormattingMillis() {
        // 12:34:56.789 in millis-of-day.
        val millis = (12 * 3_600_000L) + (34 * 60_000L) + (56 * 1_000L) + 789L
        assertEquals("12:34:56.789", seq3DisplayTimestamp("", millis))
        assertEquals("12:34:56.789", seq3DisplayTimestamp("   ", millis))
    }

    @Test
    fun nullWhenNeitherRawNorMillisIsAvailable() {
        assertNull(seq3DisplayTimestamp("", null))
    }

    // ── seq3ChronologicalOrder / seq3ChronologicalFallbacks (Task 0, round-2 corrections plan) ──
    //
    // Direct, non-Emission/non-Seq3Emission tests of the shared comparator itself — Seq3LayoutTest
    // and Seq3EmitterTest cover the two real call sites end to end; these pin down the generic
    // function's contract in isolation, independent of either file's own row/emission shape.

    private fun msg(id: String, ts: Long? = null) = Seq3Message(
        id = id,
        match = Seq3Match(tag = "A", template = id),
        fromLifelineId = "A",
        toLifelineId = "B",
        labelTemplate = id,
        kind = Seq3Kind.CALL,
        manualTimestampMillis = ts,
    )

    private fun docOf(vararg messages: Seq3Message) = Seq3Document(
        lifelines = listOf(Seq3Lifeline("A", "A", setOf("A"), 0), Seq3Lifeline("B", "B", setOf("B"), 1)),
        messages = messages.toList(),
    )

    @Test
    fun chronologicalOrderSortsByTimestampNotByListPosition() {
        // Declared m2-then-m1, timestamps say the opposite.
        val document = docOf(msg("m2", ts = 2_000L), msg("m1", ts = 1_000L))
        val ordered = seq3ChronologicalOrder(document, document.messages, { it.id }, { it.manualTimestampMillis }, { null })
        assertEquals(listOf("m1", "m2"), ordered.map { it.id })
    }

    @Test
    fun chronologicalOrderTiebreaksSameInstantByListPositionThenByEntryId() {
        val document = docOf(msg("first", ts = 1_000L), msg("second", ts = 1_000L))
        val ordered = seq3ChronologicalOrder(document, document.messages, { it.id }, { it.manualTimestampMillis }, { null })
        assertEquals(listOf("first", "second"), ordered.map { it.id }, "a genuine tie keeps declaration order, the stable tiebreak")

        // Several ITEMS of the SAME message id (mirrors several emissions of one EVERY-repeat
        // message) fall back to the entryId tiebreak instead.
        data class Item(val messageId: String, val ts: Long?, val entryId: Int?)
        val items = listOf(Item("m", 1_000L, 3), Item("m", 1_000L, 1), Item("m", 1_000L, 2))
        val orderedItems = seq3ChronologicalOrder(docOf(msg("m", ts = 1_000L)), items, { it.messageId }, { it.ts }, { it.entryId })
        assertEquals(listOf(1, 2, 3), orderedItems.map { it.entryId })
    }

    @Test
    fun chronologicalFallbacksInterpolatesBetweenTimestampedNeighboursInListOrder() {
        val document = docOf(msg("before", ts = 1_000L), msg("untimestamped"), msg("after", ts = 3_000L))
        val fallbacks = seq3ChronologicalFallbacks(document)
        assertEquals(2_000L, fallbacks["untimestamped"], "halfway between its two timestamped neighbours")
        assertEquals(null, fallbacks["before"], "a message with a real timestamp never gets a fallback entry")
    }

    @Test
    fun chronologicalFallbacksHandlesAnUntimestampedMessageAtEitherEdge() {
        val document = docOf(msg("leading"), msg("anchor", ts = 5_000L), msg("trailing"))
        val fallbacks = seq3ChronologicalFallbacks(document)
        assertEquals(4_999L, fallbacks["leading"], "one millisecond before its only timestamped neighbour")
        assertEquals(5_001L, fallbacks["trailing"], "one millisecond after its only timestamped neighbour")
    }

    @Test
    fun chronologicalOrderFallsBackToListOrderWhenEveryMessageIsUntimestamped() {
        val document = docOf(msg("a"), msg("b"), msg("c"))
        val ordered = seq3ChronologicalOrder(document, document.messages, { it.id }, { it.manualTimestampMillis }, { null })
        assertEquals(listOf("a", "b", "c"), ordered.map { it.id }, "no timestamp and no fallback: Long.MAX_VALUE ties, so list order (a stable sort) wins")
    }
}
