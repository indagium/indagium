package com.indagium.testing

import com.indagium.model.AiUsageStats
import com.indagium.ui.formatClock
import com.indagium.ui.formatDuration
import com.indagium.ui.formatRunMoment
import com.indagium.ui.formatTokens
import com.indagium.ui.formatUsage
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class TestsFormatTest {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun durationsReadAsSecondsMinutesAndHours() {
        assertEquals("0s", formatDuration(0))
        assertEquals("48s", formatDuration(48_000))
        assertEquals("1m 00s", formatDuration(60_000))
        assertEquals("6m 52s", formatDuration((6 * 60 + 52) * 1000L))
        assertEquals("6m 05s", formatDuration((6 * 60 + 5) * 1000L))
        assertEquals("1h 03m", formatDuration((3600 + 3 * 60 + 20) * 1000L))
        assertEquals("12h 00m", formatDuration(12 * 3600 * 1000L))
    }

    @Test
    fun durationsRoundToTheNearestSecondAndClampNegatives() {
        assertEquals("1s", formatDuration(600))
        assertEquals("0s", formatDuration(400))
        assertEquals("2s", formatDuration(1_500))
        assertEquals("1m 00s", formatDuration(59_600))
        assertEquals("0s", formatDuration(-5_000))
    }

    @Test
    fun tokenCountsAbbreviateThousandsAndMillions() {
        assertEquals("0", formatTokens(0))
        assertEquals("980", formatTokens(980))
        assertEquals("1K", formatTokens(1_000))
        assertEquals("1.5K", formatTokens(1_500))
        assertEquals("150K", formatTokens(150_000))
        assertEquals("1.36M", formatTokens(1_360_000))
        assertEquals("2M", formatTokens(2_000_000))
        assertEquals("12.35M", formatTokens(12_345_678))
    }

    @Test
    fun tokenCountsThatRoundUpToAThousandThousandBecomeOneMillion() {
        assertEquals("999K", formatTokens(999_400))
        assertEquals("1M", formatTokens(999_600))
        assertEquals("10K", formatTokens(9_999))
    }

    @Test
    fun runMomentsAreRelativeToTheCurrentDay() {
        val now = at(2026, 10, 11, 9, 30)
        assertEquals("today 13:25", formatRunMoment(at(2026, 10, 11, 13, 25), now, zone))
        assertEquals("today 00:05", formatRunMoment(at(2026, 10, 11, 0, 5), now, zone))
        assertEquals("yesterday 19:27", formatRunMoment(at(2026, 10, 10, 19, 27), now, zone))
        assertEquals("Oct 7, 23:25", formatRunMoment(at(2026, 10, 7, 23, 25), now, zone))
        assertEquals("Jan 2, 08:00", formatRunMoment(at(2026, 1, 2, 8, 0), now, zone))
    }

    @Test
    fun runMomentsFromAnotherYearCarryTheYear() {
        val now = at(2026, 1, 3, 9, 30)
        assertEquals("Dec 30, 2025, 22:10", formatRunMoment(at(2025, 12, 30, 22, 10), now, zone))
        assertEquals("yesterday 22:10", formatRunMoment(at(2026, 1, 2, 22, 10), now, zone))
    }

    @Test
    fun yesterdayFollowsTheZoneNotUtc() {
        // 23:30 UTC on Oct 10 is already Oct 11 (01:30) in Berlin, so it is "today" for a Berlin reader at 09:30.
        val now = at(2026, 10, 11, 9, 30)
        val lateUtc = ZonedDateTime.of(2026, 10, 10, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("today 01:30", formatRunMoment(lateUtc, now, zone))
    }

    @Test
    fun clockTimesUseTheZone() {
        assertEquals("13:27", formatClock(at(2026, 10, 11, 13, 27), zone))
        assertEquals("00:05", formatClock(at(2026, 10, 11, 0, 5), zone))
    }

    @Test
    fun usageReadsInShortFormsAndNeverShowsAMissingMetricAsZero() {
        assertEquals("Unavailable", formatUsage(null))
        assertEquals(
            "43 tool calls · 1.36M tokens · partial count",
            formatUsage(AiUsageStats(toolCalls = 43, totalTokens = 1_360_000, partial = true)),
        )
        assertEquals("1 tool call · 980 tokens", formatUsage(AiUsageStats(toolCalls = 1, totalTokens = 980)))
        assertEquals("0 tool calls · tokens unavailable", formatUsage(AiUsageStats(toolCalls = 0)))
        assertEquals("tool calls unavailable · 150K in · 12K out", formatUsage(AiUsageStats(inputTokens = 150_000, outputTokens = 12_000)))
        assertEquals("tool calls unavailable · 150K in · out unavailable", formatUsage(AiUsageStats(inputTokens = 150_000)))
        assertEquals("tool calls unavailable · in unavailable · 2K out", formatUsage(AiUsageStats(outputTokens = 2_000)))
    }
}
