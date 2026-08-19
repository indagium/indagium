package com.indagium

import com.indagium.diagram3.SEQ3_AUTO_SUGGEST_DELAY_GAP_MILLIS
import com.indagium.diagram3.Seq3Delay
import com.indagium.diagram3.Seq3Document
import com.indagium.diagram3.Seq3Kind
import com.indagium.diagram3.Seq3Lifeline
import com.indagium.diagram3.Seq3Match
import com.indagium.diagram3.Seq3Message
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

    @Test
    fun anUntimestampedMessageContributesNoBoundary() {
        val document = docOf(msg("m1", ts = 0L), msg("untimestamped", ts = null), msg("m3", ts = SEQ3_AUTO_SUGGEST_DELAY_GAP_MILLIS))
        // "untimestamped" has no real timestamp, so neither the m1->untimestamped nor the
        // untimestamped->m3 boundary can be measured — only a genuinely timestamped pair counts.
        assertTrue(seq3SuggestedDelays(document).isEmpty())
    }
}
