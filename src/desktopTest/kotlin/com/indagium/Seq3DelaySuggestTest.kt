package com.indagium

import com.indagium.diagram3.SEQ3_AUTO_SUGGEST_DELAY_GAP_MILLIS
import com.indagium.diagram3.Seq3Delay
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
import com.indagium.diagram3.Seq3Occurrence
import com.indagium.diagram3.Seq3Visibility
import com.indagium.diagram3.seq3SuggestedDelays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WP11 auto-suggest — see Seq3DelaySuggest.kt's own header for the "offer, never insert
 *  silently" contract these tests pin down. */
class Seq3DelaySuggestTest {
    private fun msg(id: String, ts: Long?, visibility: Seq3Visibility = Seq3Visibility.VISIBLE) = Seq3Message(
        id = id,
        match = Seq3Match(tag = "A", template = id),
        fromLifelineId = "A",
        toLifelineId = "B",
        labelTemplate = id,
        kind = Seq3Kind.CALL,
        manualTimestampMillis = ts,
        visibility = visibility,
    )

    private fun docOf(vararg messages: Seq3Message) = Seq3Document(
        lifelines = listOf(Seq3Lifeline("A", "A", setOf("A"), 0), Seq3Lifeline("B", "B", setOf("B"), 1)),
        messages = messages.toList(),
    )

    // Midnight-rollover fix: an evidence-backed message (occurrence timestampMillis/elapsedMillis),
    // as opposed to [msg]'s authored manualTimestampMillis shape above — needed to exercise
    // Seq3Message.primaryElapsedMillis's occurrence-reading branch rather than its manual-override
    // one.
    private fun msgWithOccurrence(id: String, entryId: Int, timestampMillis: Long, elapsedMillis: Long) = Seq3Message(
        id = id,
        match = Seq3Match(tag = "A", template = id),
        fromLifelineId = "A",
        toLifelineId = "B",
        labelTemplate = id,
        kind = Seq3Kind.CALL,
        occurrences = listOf(
            Seq3Occurrence(entryId, timestampMillis, "raw", pid = 0, tid = 0, level = 'I', text = id, elapsedMillis = elapsedMillis),
        ),
    )

    @Test
    fun aGapAtOrAboveTheThresholdIsSuggested() {
        val document = docOf(msg("m1", ts = 0L), msg("m2", ts = SEQ3_AUTO_SUGGEST_DELAY_GAP_MILLIS))
        val suggestions = seq3SuggestedDelays(document)
        assertEquals(1, suggestions.size)
        val suggestion = suggestions.single()
        assertEquals("m1", suggestion.afterMessageId)
        assertEquals("m2", suggestion.beforeMessageId)
        assertEquals(SEQ3_AUTO_SUGGEST_DELAY_GAP_MILLIS, suggestion.gapMillis)
    }

    @Test
    fun aGapJustBelowTheThresholdIsNotSuggested() {
        val document = docOf(msg("m1", ts = 0L), msg("m2", ts = SEQ3_AUTO_SUGGEST_DELAY_GAP_MILLIS - 1))
        assertTrue(seq3SuggestedDelays(document).isEmpty())
    }

    @Test
    fun aSmallOrdinaryGapIsNeverSuggested() {
        val document = docOf(msg("m1", ts = 0L), msg("m2", ts = 500L), msg("m3", ts = 1_200L))
        assertTrue(seq3SuggestedDelays(document).isEmpty())
    }

    @Test
    fun aGapAlreadyMarkedByAVisibleDelayIsNotOfferedAgain() {
        val document = docOf(msg("m1", ts = 0L), msg("m2", ts = SEQ3_AUTO_SUGGEST_DELAY_GAP_MILLIS))
            .copy(delays = listOf(Seq3Delay("d1", afterMessageId = "m1", label = "already marked")))
        assertTrue(seq3SuggestedDelays(document).isEmpty(), "a gap the user already placed a marker on must not be re-offered")
    }

    @Test
    fun aGapWithOnlyAHiddenDelayIsStillOffered() {
        // A HIDDEN delay is a dismissed/removed-from-view marker, not "already handled" — the
        // gap should still surface as a candidate, same as [Seq3Fragment.visibility]'s own
        // "hidden means dropped, not gone" contract elsewhere in this package.
        val document = docOf(msg("m1", ts = 0L), msg("m2", ts = SEQ3_AUTO_SUGGEST_DELAY_GAP_MILLIS))
            .copy(delays = listOf(Seq3Delay("d1", afterMessageId = "m1", label = "hidden", visibility = Seq3Visibility.HIDDEN)))
        assertEquals(1, seq3SuggestedDelays(document).size)
    }

    @Test
    fun aHiddenMessageContributesNoBoundaryOnEitherSide() {
        val document = docOf(
            msg("m1", ts = 0L),
            msg("hidden", ts = 5_000L, visibility = Seq3Visibility.HIDDEN),
            msg("m3", ts = SEQ3_AUTO_SUGGEST_DELAY_GAP_MILLIS + 5_000L),
        )
        val suggestions = seq3SuggestedDelays(document)
        assertEquals(1, suggestions.size)
        assertEquals("m1", suggestions.single().afterMessageId, "the hidden message must be skipped entirely, not treated as a boundary")
        assertEquals("m3", suggestions.single().beforeMessageId)
    }

    @Test
    fun aCustomThresholdOverridesTheDefault() {
        val document = docOf(msg("m1", ts = 0L), msg("m2", ts = 5_000L))
        assertTrue(seq3SuggestedDelays(document, thresholdMillis = 10_000L).isEmpty())
        assertEquals(1, seq3SuggestedDelays(document, thresholdMillis = 5_000L).size)
    }

    // ── Midnight-rollover fix ────────────────────────────────────────────────────────────────────

    @Test
    fun theSpuriousDayLongGapAcrossAMidnightRolloverIsGoneAndTheTrueShortGapIsReportedInstead() {
        // "pre" and "post" are ~200ms apart in real time, straddling a rollover: pre-midnight raw ts
        // 86_399_900 (23:59:59.900), post-midnight raw ts 100 (00:00:00.100), day-unrolled elapsed
        // 86_400_100. Before this fix, ordering (and this gap) ran on raw millis-of-day alone: the
        // pair sorted swapped and the "gap" read back as ~86_399_800ms (~24h), not the true ~200ms.
        val document = docOf(
            msgWithOccurrence("pre", entryId = 1, timestampMillis = 86_399_900L, elapsedMillis = 86_399_900L),
            msgWithOccurrence("post", entryId = 2, timestampMillis = 100L, elapsedMillis = 86_400_100L),
        )

        // The TRUE ~200ms gap must not trip the default 30s-or-more suggestion threshold — a
        // spurious ~24h gap comfortably would have.
        assertTrue(
            seq3SuggestedDelays(document).isEmpty(),
            "the true ~200ms gap across the rollover must not trigger the default suggestion threshold",
        )

        // A threshold tight enough to catch the real gap reports its TRUE size.
        val suggestions = seq3SuggestedDelays(document, thresholdMillis = 100L)
        assertEquals(1, suggestions.size)
        val suggestion = suggestions.single()
        assertEquals("pre", suggestion.afterMessageId)
        assertEquals("post", suggestion.beforeMessageId)
        assertEquals(200L, suggestion.gapMillis, "the reported gap must be the TRUE ~200ms one, never the spurious ~24h millis-of-day delta")
    }

    @Test
    fun anUntimestampedMessageContributesNoBoundary() {
        val document = docOf(msg("m1", ts = 0L), msg("untimestamped", ts = null), msg("m3", ts = SEQ3_AUTO_SUGGEST_DELAY_GAP_MILLIS))
        // "untimestamped" has no real timestamp, so neither the m1->untimestamped nor the
        // untimestamped->m3 boundary can be measured — only a genuinely timestamped pair counts.
        assertTrue(seq3SuggestedDelays(document).isEmpty())
    }
}
