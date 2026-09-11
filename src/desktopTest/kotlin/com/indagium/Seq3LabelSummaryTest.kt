package com.indagium

import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.collapsedStateInvariantValue
import com.indagium.diagram3.seq3ChronologicalFallbacks
import com.indagium.diagram3.seq3ChronologicalOrder
import com.indagium.diagram3.seq3DisplayTimestamp
import com.indagium.diagram3.seq3PrefixedLabel
import com.indagium.utils.elapsedMillisOfDay
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

    // ── WP15 Part 1: elapsed tag ─────────────────────────────────────────────────────────────

    @Test
    fun elapsedOnPrefixesTheMeasuredGapAfterTheNumberAndTimestampTags() {
        val result = seq3PrefixedLabel(
            "hello",
            sequenceNumber = 3,
            rawTimestamp = "10:00:00.140",
            timestampMillis = 1_140L,
            showSequenceNumbers = true,
            showTimestamps = true,
            elapsedMillis = 140L,
            showElapsed = true,
        )
        assertEquals("[#3] [10:00:00.140] [+0.140] hello", result)
    }

    @Test
    fun elapsedOffLeavesTheLabelUnprefixedEvenWhenAMeasuredGapIsAvailable() {
        // The default-off guarantee (WP15 brief): showElapsed=false must never print a tag, even
        // when the caller happens to have a real, non-null elapsedMillis on hand.
        val result = seq3PrefixedLabel(
            "hello",
            sequenceNumber = null,
            rawTimestamp = "",
            timestampMillis = null,
            showSequenceNumbers = false,
            showTimestamps = false,
            elapsedMillis = 140L,
            showElapsed = false,
        )
        assertEquals("hello", result)
    }

    @Test
    fun elapsedOnIsANoOpWhenNoMeasuredGapIsAvailable() {
        // Rule 1's rendering half: seq3PrefixedLabel itself must never fabricate a tag when the
        // fold handed it null (either endpoint unknown) — see prefixEmissionLabels/
        // prefixSeq3EmissionLabels for where that null actually comes from.
        val result = seq3PrefixedLabel(
            "hello",
            sequenceNumber = null,
            rawTimestamp = "",
            timestampMillis = null,
            showSequenceNumbers = false,
            showTimestamps = false,
            elapsedMillis = null,
            showElapsed = true,
        )
        assertEquals("hello", result, "elapsedMillis == null must not print an empty/fabricated '[+...]' tag")
    }

    @Test
    fun elapsedTagUsesTheRolloverCorrectedDeltaNotPlainSubtraction() {
        // Rule 3 (WP15 brief): the fold computes elapsedMillis via utils.elapsedMillisOfDay, never
        // plain subtraction — this pins the exact contract at the point seq3PrefixedLabel actually
        // renders it. 23:59:59.900 -> 00:00:00.100 the next day is a real ~200ms gap; PLAIN
        // subtraction (100 - 86_399_900 = -86_399_800) would print as a huge, nonsensical backwards
        // jump instead. Both prefixEmissionLabels' and prefixSeq3EmissionLabels' own `elapsedFor`
        // helpers route through this exact function (elapsedMillisOfDay's own doc: "the ONE place
        // the ROLLOVER_THRESHOLD_MS correction is written"), so pinning its output here pins theirs
        // too. (In today's pipeline the global chronological sort — seq3ChronologicalOrder — always
        // hands the fold two ASCENDING real timestamps, so this negative-delta branch can't actually
        // fire end-to-end yet, the same acknowledged-unreachable situation Seq3DelaySuggest.kt's own
        // header documents for its gap suggester; using the shared, rollover-aware function anyway
        // is what keeps this arithmetic from drifting out of sync with the one place the correction
        // lives, the moment anything upstream changes.)
        val corrected = elapsedMillisOfDay(86_399_900L, 100L)
        assertEquals(200L, corrected, "sanity check on the fixture itself")

        val result = seq3PrefixedLabel(
            "hello",
            sequenceNumber = null,
            rawTimestamp = "",
            timestampMillis = null,
            showSequenceNumbers = false,
            showTimestamps = false,
            elapsedMillis = corrected,
            showElapsed = true,
        )
        assertEquals("[+0.200] hello", result)
    }

    // ── WP16: the collapsed row's own internal span ─────────────────────────────────────────────
    //
    // See this file's own class doc and Seq3Layout.kt's `Emission.Arrow.spanEndTimestampMillis` for
    // the full "why" — a COLLAPSE_ABOVE row above threshold has no way to say how long its own n
    // occurrences spanned, only how far it sits from the previous row. These pin seq3PrefixedLabel's
    // half of that directly: given [timestampMillis] (the row's own/span-start) and
    // [spanEndTimestampMillis], it renders `over <duration>` via `formatDuration`
    // (magnitude-only — a span has no before/after side to sign), and — the gap-vs-span call this
    // work package makes deliberately, not by accident — the span always wins over a simultaneously
    // available gap: two duration tags on one label would be noise, and the span says something
    // about the row's own content while the gap only says where it sits.

    @Test
    fun spanPresentRendersTheSpanInsteadOfTheGapEvenWhenBothAreAvailable() {
        val result = seq3PrefixedLabel(
            "hello",
            sequenceNumber = null,
            rawTimestamp = "",
            timestampMillis = 1_000L,
            showSequenceNumbers = false,
            showTimestamps = false,
            elapsedMillis = 140L,
            showElapsed = true,
            spanEndTimestampMillis = 5_200L,
        )
        assertEquals("[over 4.2s] hello", result, "the span (1_000L -> 5_200L) wins over the 140ms gap")
    }

    @Test
    fun spanZeroRendersAZeroDurationNotACrashOrABareBracket() {
        val result = seq3PrefixedLabel(
            "hello",
            sequenceNumber = null,
            rawTimestamp = "",
            timestampMillis = 3_000L,
            showSequenceNumbers = false,
            showTimestamps = false,
            showElapsed = true,
            spanEndTimestampMillis = 3_000L,
        )
        assertEquals("[over 0ms] hello", result)
    }

    @Test
    fun spanAbsentFallsBackToTheOrdinaryGapTag() {
        val result = seq3PrefixedLabel(
            "hello",
            sequenceNumber = null,
            rawTimestamp = "",
            timestampMillis = 1_000L,
            showSequenceNumbers = false,
            showTimestamps = false,
            elapsedMillis = 140L,
            showElapsed = true,
            spanEndTimestampMillis = null,
        )
        assertEquals("[+0.140] hello", result, "no span available: the ordinary gap tag is unaffected")
    }

    @Test
    fun showElapsedFalseSuppressesTheSpanTagToo() {
        // The default-off guarantee, reconfirmed for the span branch (mirrors
        // elapsedOffLeavesTheLabelUnprefixedEvenWhenAMeasuredGapIsAvailable above).
        val result = seq3PrefixedLabel(
            "hello",
            sequenceNumber = null,
            rawTimestamp = "",
            timestampMillis = 1_000L,
            showSequenceNumbers = false,
            showTimestamps = false,
            showElapsed = false,
            spanEndTimestampMillis = 5_200L,
        )
        assertEquals("hello", result)
    }

    @Test
    fun spanRequiresANonNullRowTimestampTooNotJustANonNullSpanEnd() {
        // A null [timestampMillis] means this row has no real position of its own (e.g. every
        // occurrence in the group had a null timestamp) — nothing to measure FROM, so no tag at all,
        // even though [spanEndTimestampMillis] alone is non-null.
        val result = seq3PrefixedLabel(
            "hello",
            sequenceNumber = null,
            rawTimestamp = "",
            timestampMillis = null,
            showSequenceNumbers = false,
            showTimestamps = false,
            showElapsed = true,
            spanEndTimestampMillis = 5_200L,
        )
        assertEquals("hello", result, "no fabricated span when the row's own timestamp is unknown")
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

    // ── Midnight-rollover fix: elapsed-aware ordering ───────────────────────────────────────────
    //
    // Direct tests of primaryElapsedMillis/seq3ChronologicalFallbacks/seq3ChronologicalOrder
    // together, independent of generateSeq3/Seq3Layout/Seq3Emitters plumbing — those three own the
    // real end-to-end coverage (Seq3GeneratorTest's headline test, Seq3DelaySuggestTest). These pin
    // the shared comparator's CONTRACT for the elapsed axis, the same split
    // chronologicalOrderSortsByTimestampNotByListPosition already draws for the raw-timestamp axis.

    private fun occWithElapsed(entryId: Int, timestampMillis: Long?, elapsedMillis: Long?) =
        Seq3Occurrence(entryId, timestampMillis, "raw", pid = 0, tid = 0, level = 'I', text = "line$entryId", elapsedMillis = elapsedMillis)

    private fun msgWithOccurrence(id: String, entryId: Int, timestampMillis: Long?, elapsedMillis: Long?) = Seq3Message(
        id = id,
        match = Seq3Match(tag = "A", template = id),
        fromLifelineId = "A",
        toLifelineId = "B",
        labelTemplate = id,
        kind = Seq3Kind.CALL,
        occurrences = listOf(occWithElapsed(entryId, timestampMillis, elapsedMillis)),
    )

    // What every real caller (Seq3DelaySuggest.kt, Seq3Layout.kt, Seq3Emitters.kt) actually passes
    // as `timestampMillisOf` — written once here so a test reads exactly like the production wiring.
    private fun elapsedOrRaw(m: Seq3Message): Long? = m.primaryElapsedMillis ?: m.primaryTimestampMillis

    @Test
    fun primaryElapsedMillisPrefersTheUnrolledOccurrenceValueButLeavesPrimaryTimestampMillisAlone() {
        // "What must NOT change" — displayed wall-clock time stays millis-of-day. primaryElapsedMillis
        // is a NEW, separate value; the pre-existing primaryTimestampMillis must read back untouched.
        val message = msgWithOccurrence("m1", entryId = 1, timestampMillis = 100L, elapsedMillis = 86_400_100L)
        assertEquals(86_400_100L, message.primaryElapsedMillis)
        assertEquals(100L, message.primaryTimestampMillis, "the raw millis-of-day value must be untouched by adding elapsedMillis")
    }

    @Test
    fun chronologicalOrderPrefersElapsedMillisAcrossASimulatedMidnightRollover() {
        // Same shape as the swapped-blocks bug: raw millis-of-day would sort "post" BEFORE "pre"
        // (100 < 86_399_900); the day-unrolled elapsed value fixes the axis.
        val pre = msgWithOccurrence("pre", entryId = 1, timestampMillis = 86_399_900L, elapsedMillis = 86_399_900L)
        val post = msgWithOccurrence("post", entryId = 2, timestampMillis = 100L, elapsedMillis = 86_400_100L)
        val document = docOf(pre, post)

        val elapsedAware = seq3ChronologicalOrder(document, document.messages, { it.id }, ::elapsedOrRaw, { null })
        assertEquals(listOf("pre", "post"), elapsedAware.map { it.id })

        // Sanity check on the fixture itself: sorting by raw timestamp ALONE (the pre-fix axis)
        // really does produce the swapped order this fix exists to prevent.
        val rawOnly = seq3ChronologicalOrder(document, document.messages, { it.id }, { it.primaryTimestampMillis }, { null })
        assertEquals(listOf("post", "pre"), rawOnly.map { it.id }, "test setup sanity: the raw axis alone must reproduce the swapped-blocks bug")
    }

    @Test
    fun aDocumentWithNoElapsedMillisAnywhereOrdersExactlyAsBefore() {
        // An old note (or an entry whose ts never parsed): every occurrence's elapsedMillis is null,
        // so primaryElapsedMillis is null everywhere too and `elapsedOrRaw` reduces to the exact
        // pre-fix expression, `primaryTimestampMillis` — ordinary chronological order, unaffected.
        val later = msgWithOccurrence("later", entryId = 1, timestampMillis = 5_000L, elapsedMillis = null)
        val earlier = msgWithOccurrence("earlier", entryId = 2, timestampMillis = 1_000L, elapsedMillis = null)
        val document = docOf(later, earlier) // declared out of order on purpose

        val ordered = seq3ChronologicalOrder(document, document.messages, { it.id }, ::elapsedOrRaw, { null })
        assertEquals(listOf("earlier", "later"), ordered.map { it.id })
    }

    @Test
    fun chronologicalFallbacksInterpolatesInElapsedSpaceNotRawMillisOfDay() {
        // "before" and "after" straddle a simulated midnight: their RAW millis-of-day values go
        // DOWN (86_399_000 -> 1_000), but their day-unrolled elapsed values keep climbing
        // (86_399_000 -> 86_401_000). seq3ChronologicalFallbacks must interpolate on the elapsed
        // axis — halfway is 86_400_000 — not on the raw axis, where "previous < next" would fail
        // and silently fall back to the wrong-scale "previous + 1" branch instead.
        val before = msgWithOccurrence("before", entryId = 1, timestampMillis = 86_399_000L, elapsedMillis = 86_399_000L)
        val untimestamped = Seq3Message(
            id = "untimestamped",
            match = Seq3Match(tag = "A", template = "untimestamped"),
            fromLifelineId = "A",
            toLifelineId = "B",
            labelTemplate = "untimestamped",
        )
        val after = msgWithOccurrence("after", entryId = 2, timestampMillis = 1_000L, elapsedMillis = 86_401_000L)
        val document = docOf(before, untimestamped, after)

        val fallbacks = seq3ChronologicalFallbacks(document)

        assertEquals(86_400_000L, fallbacks["untimestamped"], "must interpolate in elapsed space, not raw millis-of-day space")
    }

    // ── Authored messages mixed with evidence-backed ones ───────────────────────────────────────
    //
    // manualTimestampMillis is a plain millis-of-day value a user types into a picker, with no
    // calendar date attached — Seq3Message.primaryElapsedMillis's own doc explains why it is reused
    // AS-IS for ordering (never dropped to null, never guessed at) rather than unrolled.

    @Test
    fun anAuthoredMessageOrdersCorrectlyAlongsideElapsedAwareEvidenceOnTheSameDay() {
        // The common, fully-supported case: an authored message's manual timestamp sits, in RAW
        // millis-of-day terms, on the same day as the evidence around it — ordering is sensible.
        val evidenceBefore = msgWithOccurrence("evidenceBefore", entryId = 1, timestampMillis = 10_000L, elapsedMillis = 10_000L)
        val authored = Seq3Message(
            id = "authored",
            match = Seq3Match(tag = "A", template = "authored"),
            fromLifelineId = "A",
            toLifelineId = "B",
            labelTemplate = "authored",
            manualTimestampMillis = 15_000L,
        )
        val evidenceAfter = msgWithOccurrence("evidenceAfter", entryId = 2, timestampMillis = 20_000L, elapsedMillis = 20_000L)
        // Declared out of order on purpose, same as the other ordering tests above.
        val document = docOf(evidenceAfter, authored, evidenceBefore)

        val ordered = seq3ChronologicalOrder(document, document.messages, { it.id }, ::elapsedOrRaw, { null })

        assertEquals(listOf("evidenceBefore", "authored", "evidenceAfter"), ordered.map { it.id })
    }

    @Test
    fun anAuthoredMessagesManualTimestampCanStillMisorderAgainstEvidenceAcrossARealRollover() {
        // The documented, deliberately-accepted residual gap (Seq3Message.primaryElapsedMillis's own
        // doc, Seq3DelaySuggest.kt's rewritten comment): manualTimestampMillis has no monotonic
        // equivalent to compute, so it is compared AS-IS against real day-unrolled evidence. An
        // authored message meant to represent a moment AFTER a real midnight rollover, but typed as
        // a small millis-of-day value, still sorts as if it were EARLY on the pre-rollover day —
        // exactly the swapped-blocks shape every message used to have before this fix. This test
        // pins that this is the CHOSEN, understood behaviour, not an oversight: it must keep failing
        // this exact way unless a future change deliberately revisits the decision.
        val preMidnightEvidence = msgWithOccurrence("preMidnightEvidence", entryId = 1, timestampMillis = 86_399_900L, elapsedMillis = 86_399_900L)
        // Author intends "shortly after the rollover" but types a small clock value — indistinguishable
        // from "shortly after midnight the SAME calendar day" once the date is gone.
        val authoredMeaningAfterRollover = Seq3Message(
            id = "authoredMeaningAfterRollover",
            match = Seq3Match(tag = "A", template = "authoredMeaningAfterRollover"),
            fromLifelineId = "A",
            toLifelineId = "B",
            labelTemplate = "authoredMeaningAfterRollover",
            manualTimestampMillis = 200L,
        )
        val document = docOf(preMidnightEvidence, authoredMeaningAfterRollover)

        val ordered = seq3ChronologicalOrder(document, document.messages, { it.id }, ::elapsedOrRaw, { null })

        assertEquals(
            listOf("authoredMeaningAfterRollover", "preMidnightEvidence"),
            ordered.map { it.id },
            "documents the accepted residual gap — see this test's own header",
        )
    }

    // ── collapsedStateInvariantValue (WP18) ──────────────────────────────────────────────────

    private fun occ(id: Int, value: String?) =
        Seq3Occurrence(id, null, "", pid = 0, tid = 0, level = 'I', text = "", captureValues = value?.let { mapOf("state" to it) }.orEmpty())

    @Test
    fun oneDistinctValueSubstitutesItDirectly() {
        val result = collapsedStateInvariantValue("state", listOf(occ(1, "OPEN"), occ(2, "OPEN")))
        assertEquals("OPEN", result)
    }

    @Test
    fun twoOrThreeDistinctValuesJoinAsAPipeSeparatedSummary() {
        val result = collapsedStateInvariantValue("state", listOf(occ(1, "OPEN"), occ(2, "CLOSED"), occ(3, "OPEN")))
        assertEquals("OPEN|CLOSED", result)
    }

    @Test
    fun aboveThreeDistinctValuesFallsBackToTheHonestCaptureNamePlaceholder() {
        val result = collapsedStateInvariantValue("state", (1..4).map { occ(it, "S$it") })
        assertEquals("{state}", result)
    }

    @Test
    fun noOccurrenceCarryingTheCaptureAtAllReturnsNull() {
        val result = collapsedStateInvariantValue("state", listOf(occ(1, null), occ(2, null)))
        assertNull(result, "a dangling/renamed capture must resolve to null — nothing to draw — never throw")
    }
}
