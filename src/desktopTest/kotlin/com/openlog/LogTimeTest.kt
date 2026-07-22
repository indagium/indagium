package com.openlog

import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.utils.TS_UNKNOWN
import com.openlog.utils.deltaAnchorId
import com.openlog.utils.deltaMillis
import com.openlog.utils.formatDelta
import com.openlog.utils.formatSignedDelta
import com.openlog.utils.parseMillisOfDay
import com.openlog.utils.widestAdjacentGapMagnitudeMs
import com.openlog.utils.widestAnchorDeltaMagnitudeMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogTimeTest {
    // widestAdjacentGapMagnitudeMs takes entries (not raw ts strings) — only ts matters for these
    // fixtures, everything else is a filler value.
    private fun entriesWithTs(vararg ts: String): List<LogEntry> =
        ts.mapIndexed { i, t -> LogEntry(i + 1, t, LogLevel.I, "Tag", "msg") }

    @Test
    fun parsesAValidThreeDigitFractionTimestamp() {
        // 1h 2m 3s .456 = ((1*3600)+(2*60)+3)*1000 + 456
        assertEquals(3_723_456L, parseMillisOfDay("01:02:03.456"))
    }

    @Test
    fun blankTsFromBriefOrRawRowsIsUnknown() {
        assertEquals(TS_UNKNOWN, parseMillisOfDay(""))
    }

    @Test
    fun malformedTsIsUnknown() {
        assertEquals(TS_UNKNOWN, parseMillisOfDay("not-a-time"))
        assertEquals(TS_UNKNOWN, parseMillisOfDay("25:00:00.000")) // hour out of range
        assertEquals(TS_UNKNOWN, parseMillisOfDay("12:60:00.000")) // minute out of range
        assertEquals(TS_UNKNOWN, parseMillisOfDay("12:00:00.")) // trailing dot, no fraction digits
    }

    @Test
    fun bareTimeWithoutAFractionIsStillValid() {
        // RE_BARE-style inputs always carry a fraction in practice, but the parser tolerates a
        // bare HH:MM:SS defensively rather than rejecting it.
        assertEquals(43_200_000L, parseMillisOfDay("12:00:00"))
    }

    @Test
    fun oneFractionDigitIsPaddedToMilliseconds() {
        assertEquals(100L, parseMillisOfDay("00:00:00.1"))
    }

    @Test
    fun threeFractionDigitsAreExact() {
        assertEquals(123L, parseMillisOfDay("00:00:00.123"))
    }

    @Test
    fun sixFractionDigitsAreTruncatedToMilliseconds() {
        assertEquals(123L, parseMillisOfDay("00:00:00.123456"))
    }

    @Test
    fun deltaMillisReturnsNullWhenEitherSideIsUnparseable() {
        assertNull(deltaMillis("", "10:00:00.000"))
        assertNull(deltaMillis("10:00:00.000", ""))
        assertNull(deltaMillis("garbage", "10:00:00.000"))
    }

    @Test
    fun deltaMillisComputesTheOrdinaryForwardGap() {
        assertEquals(2_651L, deltaMillis("10:00:00.000", "10:00:02.651"))
    }

    @Test
    fun deltaMillisAppliesMidnightRolloverCorrection() {
        // 23:59:59.900 -> 00:00:00.100 is a 200ms gap across the rollover, not a ~24h jump backward.
        assertEquals(200L, deltaMillis("23:59:59.900", "00:00:00.100"))
    }

    @Test
    fun deltaMillisRendersASmallNegativeDeltaAsIsRatherThanClamping() {
        // Out-of-order rows (e.g. a merged tab) can go slightly backward — this must NOT trigger
        // the rollover heuristic (well under the 12h threshold) and must NOT be clamped to zero.
        assertEquals(-500L, deltaMillis("10:00:01.000", "10:00:00.500"))
    }

    @Test
    fun formatDeltaSubSecond() {
        assertEquals("+0.140", formatDelta(140))
        assertEquals("-0.050", formatDelta(-50))
    }

    @Test
    fun formatDeltaSeconds() {
        assertEquals("+2.651", formatDelta(2_651))
    }

    @Test
    fun formatDeltaMinutes() {
        assertEquals("+1m02s", formatDelta(62_000))
    }

    @Test
    fun formatDeltaHours() {
        assertEquals("+1h02m03s", formatDelta(3_723_000))
    }

    @Test
    fun formatSignedDeltaOmitsTheSignOnlyAtExactlyZero() {
        // The selected row itself: bare "0.000", not "+0.000" — it's the anchor ("you are here"),
        // not a tiny forward gap.
        assertEquals("0.000", formatSignedDelta(0))
    }

    @Test
    fun formatSignedDeltaMatchesFormatDeltaAwayFromZero() {
        assertEquals(formatDelta(4_291), formatSignedDelta(4_291))
        assertEquals("-4.291", formatSignedDelta(-4_291))
        assertEquals("+0.001", formatSignedDelta(1))
    }

    @Test
    fun deltaAnchorIdIsNullWhenNothingIsSelected() {
        assertNull(deltaAnchorId(emptySet()))
    }

    @Test
    fun deltaAnchorIdPicksTheLowestSelectedId() {
        // Deterministic anchor rule: lowest id wins regardless of set iteration/insertion order.
        assertEquals(3, deltaAnchorId(setOf(9, 3, 7)))
        assertEquals(1, deltaAnchorId(setOf(1)))
    }

    @Test
    fun widestAnchorDeltaMagnitudeIsTheWiderOfTheTwoExtremes() {
        // Anchor sits much closer to firstTs than to lastTs — the widest string that mode will
        // ever render is the anchor-to-last distance, not the anchor-to-first one or the full span.
        val widest = widestAnchorDeltaMagnitudeMs(firstTs = "10:00:00.000", lastTs = "12:00:00.000", anchorTs = "10:00:01.000")
        assertEquals(7_199_000L, widest) // 12:00:00.000 - 10:00:01.000
    }

    @Test
    fun widestAnchorDeltaMagnitudeIsZeroWhenTimestampsDontParse() {
        assertEquals(0L, widestAnchorDeltaMagnitudeMs(firstTs = "", lastTs = "", anchorTs = "10:00:00.000"))
        assertEquals(0L, widestAnchorDeltaMagnitudeMs(firstTs = "garbage", lastTs = "10:00:00.000", anchorTs = "10:00:00.000"))
    }

    @Test
    fun widestAdjacentGapMagnitudeFindsTheOneLargerGapAmongOtherwiseTinyOnes() {
        // Mostly-5ms consecutive gaps with one deliberately larger 250ms gap in the middle — the
        // result must be exactly that 250ms, not the (much larger) first-to-last span, and not one
        // of the surrounding 5ms gaps either.
        // "10:00:00.255" is the +250ms adjacent gap; the pairs on either side of it are all 5ms.
        val entries = entriesWithTs(
            "10:00:00.000",
            "10:00:00.005",
            "10:00:00.255",
            "10:00:00.260",
            "10:00:00.265",
        )
        assertEquals(250L, widestAdjacentGapMagnitudeMs(entries))
    }

    @Test
    fun widestAdjacentGapMagnitudeStaysNarrowEvenWhenTheTotalSpanIsHours() {
        // The exact bug report scenario: a log spanning over two hours (first to last) where every
        // CONSECUTIVE row is only milliseconds apart. A "total span" bound would size this column
        // for "+120m00s"+; the correct adjacent-gap bound must stay a single-digit millisecond
        // value, since that's what's actually rendered for every row.
        // Total span (first to last) is ~2h13m, but this function only ever looks at consecutive
        // PAIRS — there's exactly one huge pair here (the last two), which is legitimately the
        // widest adjacent gap.
        val entries = entriesWithTs(
            "10:00:00.000",
            "10:00:00.005",
            "10:00:00.010",
            "10:00:00.015",
            "12:13:45.000",
        )
        // 12:13:45.000 - 10:00:00.015
        assertEquals(8_024_985L, widestAdjacentGapMagnitudeMs(entries))

        // Same total span shape, but with NO stall anywhere — every consecutive pair is 5ms. This
        // is the actual regression case: the widest adjacent gap must be tiny even though nothing
        // here bounds it away from the (nonexistent, in this fixture) total-span number.
        val allNarrowGaps = entriesWithTs(
            "10:00:00.000",
            "10:00:00.005",
            "10:00:00.010",
            "10:00:00.015",
            "10:00:00.020",
        )
        assertEquals(5L, widestAdjacentGapMagnitudeMs(allNarrowGaps))
    }

    @Test
    fun widestAdjacentGapMagnitudeIgnoresUnparseablePairs() {
        assertEquals(0L, widestAdjacentGapMagnitudeMs(emptyList()))
        assertEquals(0L, widestAdjacentGapMagnitudeMs(entriesWithTs("10:00:00.000")))
        assertEquals(0L, widestAdjacentGapMagnitudeMs(entriesWithTs("", "")))
    }
}
