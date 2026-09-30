package com.indagium

import com.indagium.capture.CaptureSyncSample
import com.indagium.capture.estimateCaptureSyncAnchor
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.model.NO_DATE
import com.indagium.model.VideoAnchor
import com.indagium.model.VideoAttachment
import com.indagium.model.hasSameRow
import com.indagium.ui.AppState
import com.indagium.ui.mkTab
import com.indagium.utils.LogTimelinePoint
import com.indagium.utils.TS_UNKNOWN
import com.indagium.utils.deltaMillis
import com.indagium.utils.logDaySlot
import com.indagium.utils.parseLogcatLines
import com.indagium.utils.parseMillisOfDay
import com.indagium.utils.unrollLogTimeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Date-aware timeline and Δt (LogEntry.dayOfYear): the parsed `MM-DD` prefix is kept as a calendar
 * slot so day boundaries are derived, not guessed. Synthetic data only. The central contract is that
 * clean single-day and midnight-crossing logs produce exactly what the undated 12 h heuristic does.
 */
class LogDateAwareTimeTest {
    private val hourMs = 3_600_000L
    private val minuteMs = 60_000L
    private val dayMs = 24 * hourMs

    private fun slot(monthDay: String): Short =
        logDaySlot(monthDay.substring(0, 2).toInt(), monthDay.substring(3, 5).toInt())

    private fun entry(id: Int, monthDay: String?, ts: String): LogEntry =
        LogEntry(id, ts, LogLevel.I, "Tag", "msg $id", dayOfYear = monthDay?.let(::slot) ?: NO_DATE)

    private fun millisOf(ts: String): Long = parseMillisOfDay(ts)

    private fun points(entries: List<LogEntry>, withDates: Boolean): List<LogTimelinePoint> = entries.map {
        LogTimelinePoint(
            it.id,
            parseMillisOfDay(it.ts).takeUnless { millis -> millis == TS_UNKNOWN },
            it.ts,
            if (withDates) it.dayOfYear else NO_DATE,
        )
    }

    private fun assertIdenticalToLegacy(entries: List<LogEntry>) {
        val dated = unrollLogTimeline(points(entries, withDates = true))
        val legacy = unrollLogTimeline(points(entries, withDates = false))
        assertEquals(legacy, dated)
        assertEquals(legacy.byId.entries.toList(), dated.byId.entries.toList(), "log order of byId must match too")
    }

    // ── LogParser ──────────────────────────────────────────────────────────

    @Test
    fun calendarSlotsUseA366DayCalendarWithFeb29AsItsOwnSlot() {
        assertEquals(0.toShort(), logDaySlot(1, 1))
        assertEquals(58.toShort(), logDaySlot(2, 28))
        assertEquals(59.toShort(), logDaySlot(2, 29))
        assertEquals(60.toShort(), logDaySlot(3, 1))
        assertEquals(365.toShort(), logDaySlot(12, 31))
        assertEquals(NO_DATE, logDaySlot(0, 10))
        assertEquals(NO_DATE, logDaySlot(13, 1))
        assertEquals(NO_DATE, logDaySlot(4, 31))
        assertEquals(NO_DATE, logDaySlot(2, 30))
        assertEquals(NO_DATE, logDaySlot(5, 0))
    }

    @Test
    fun parserDecodesTheMonthDayOfThreadtimeAndTimeRowsWithoutChangingTs() {
        val entries = parseLogcatLines(
            sequenceOf(
                "09-29 11:06:01.123  1000  1001 I Tag: threadtime fast path",
                "09-30 12:15:28.500 I/Tag( 1000): time format",
                "09-30 12:15:29.500 +0200 1000 1001 I Tag: threadtime with offset",
                "2026-01-02 03:04:05.678+0100 1000 1001 I Tag: year qualified",
            ),
        )

        assertEquals(4, entries.size)
        assertEquals("11:06:01.123", entries[0].ts)
        assertEquals(slot("09-29"), entries[0].dayOfYear)
        assertEquals("12:15:28.500", entries[1].ts)
        assertEquals(slot("09-30"), entries[1].dayOfYear)
        assertEquals("12:15:29.500", entries[2].ts)
        assertEquals(slot("09-30"), entries[2].dayOfYear)
        assertEquals("03:04:05.678", entries[3].ts)
        assertEquals(slot("01-02"), entries[3].dayOfYear)
    }

    @Test
    fun parserLeavesUndatedFormatsAndInvalidDatesAsNoDate() {
        val entries = parseLogcatLines(
            sequenceOf(
                "I/Tag( 1000): brief",
                "12:15:28.500 I/Tag: bare time",
                "just some raw text",
                "13-45 10:00:00.000  1000  1001 I Tag: impossible month and day",
                "04-31 10:00:01.000  1000  1001 I Tag: no 31st of April",
            ),
        )

        assertEquals(5, entries.size)
        assertEquals(NO_DATE, entries[0].dayOfYear)
        assertEquals(NO_DATE, entries[1].dayOfYear)
        assertEquals("12:15:28.500", entries[1].ts)
        assertEquals(NO_DATE, entries[2].dayOfYear)
        assertEquals(NO_DATE, entries[3].dayOfYear)
        assertEquals("10:00:00.000", entries[3].ts)
        assertEquals(NO_DATE, entries[4].dayOfYear)
    }

    // ── unrollLogTimeline: identical to the legacy heuristic on clean logs ──

    @Test
    fun singleDayDatedLogIsByteIdenticalToTheUndatedResult() {
        val entries = (1..60).map { i ->
            entry(i, "09-30", "%02d:%02d:%02d.%03d".format(9 + i / 30, i % 60, (i * 7) % 60, (i * 13) % 1000))
        } + entry(61, "09-30", "10:59:59.999") + entry(62, "09-30", "10:59:59.999")

        assertIdenticalToLegacy(entries)
        val timeline = unrollLogTimeline(entries)
        assertEquals(0, timeline.rolloverAppliedCount)
        assertTrue(timeline.dayOffsetModelValid)
    }

    @Test
    fun midnightCrossingDatedLogIsByteIdenticalToTheUndatedResult() {
        val entries = listOf(
            entry(1, "09-29", "23:59:58.000"),
            entry(2, "09-29", "23:59:59.500"),
            entry(3, "09-30", "00:00:00.250"),
            entry(4, "09-30", "00:00:01.000"),
            entry(5, "09-30", "00:05:00.000"),
        )

        assertIdenticalToLegacy(entries)
        val timeline = unrollLogTimeline(entries)
        assertEquals(1, timeline.rolloverAppliedCount)
        assertEquals(3, timeline.rolloverAppliedSamples.single().id)
        assertEquals(dayMs + millisOf("00:00:00.250"), timeline.byId[3])
    }

    @Test
    fun yearBoundaryCrossingIsByteIdenticalToTheUndatedResult() {
        val entries = listOf(
            entry(1, "12-31", "23:59:59.000"),
            entry(2, "01-01", "00:00:01.000"),
        )

        assertIdenticalToLegacy(entries)
        assertEquals(2_000L, unrollLogTimeline(entries).byId[2]!! - unrollLogTimeline(entries).byId[1]!!)
    }

    @Test
    fun nonLeapEndOfFebruaryCrossingCountsNoPhantomDay() {
        val entries = listOf(
            entry(1, "02-28", "23:59:00.000"),
            entry(2, "03-01", "00:01:00.000"),
        )

        assertIdenticalToLegacy(entries)
        val byId = unrollLogTimeline(entries).byId
        assertEquals(2 * minuteMs, byId[2]!! - byId[1]!!)
    }

    @Test
    fun leapFileWithAFeb29RowCountsFeb29AsARealDay() {
        val entries = listOf(
            entry(1, "02-28", "23:00:00.000"),
            entry(2, "02-29", "01:00:00.000"),
            entry(3, "02-29", "23:00:00.000"),
            entry(4, "03-01", "01:00:00.000"),
        )

        val byId = unrollLogTimeline(entries).byId
        assertEquals(2 * hourMs, byId[2]!! - byId[1]!!)
        assertEquals(2 * hourMs, byId[4]!! - byId[3]!!)
        assertEquals(2, unrollLogTimeline(entries).rolloverAppliedCount)
    }

    @Test
    fun leapFileCrossingOnlyFeb29ToMarchIsByteIdenticalToTheUndatedResult() {
        val entries = listOf(
            entry(1, "02-29", "23:58:00.000"),
            entry(2, "02-29", "23:59:00.000"),
            entry(3, "03-01", "00:01:00.000"),
            entry(4, "03-01", "00:02:00.000"),
        )

        assertIdenticalToLegacy(entries)
    }

    // ── unrollLogTimeline: where the legacy guess was wrong ─────────────────

    @Test
    fun bufferedEarlierDayRowsSitBeforeTheNextDaysSessionRows() {
        // Buffered 09-29 11:06 -> 18:41, then a live session on 09-30 at 12:15. The legacy heuristic
        // reads 18:41 -> 12:15 as a 6.4 h backwards step and puts today's rows BEFORE yesterday's.
        val entries = listOf(
            entry(1, "09-29", "11:06:00.000"),
            entry(2, "09-29", "18:41:00.000"),
            entry(3, "09-30", "12:15:28.000"),
            entry(4, "09-30", "12:15:29.000"),
        )

        val byId = unrollLogTimeline(entries).byId
        assertEquals(millisOf("11:06:00.000"), byId[1])
        assertEquals(millisOf("18:41:00.000"), byId[2])
        assertEquals(dayMs + millisOf("12:15:28.000"), byId[3])
        assertEquals(1_000L, byId[4]!! - byId[3]!!)
        assertTrue(byId[3]!! > byId[2]!!)
        // The undated result is the wrong one here, which is what the date path fixes.
        val legacy = unrollLogTimeline(points(entries, withDates = false)).byId
        assertTrue(legacy[3]!! < legacy[2]!!)
        assertEquals(1, unrollLogTimeline(entries).rolloverAppliedCount)
        assertEquals(3, unrollLogTimeline(entries).rolloverAppliedSamples.single().id)
    }

    @Test
    fun gapOfSeveralDaysIsCountedInWholeDays() {
        val entries = listOf(
            entry(1, "09-25", "10:00:00.000"),
            entry(2, "09-25", "10:00:01.000"),
            entry(3, "09-30", "10:00:00.000"),
        )

        val byId = unrollLogTimeline(entries).byId
        assertEquals(5 * dayMs, byId[3]!! - byId[1]!!)
        assertEquals(1, unrollLogTimeline(entries).rolloverAppliedCount)
    }

    @Test
    fun threeConsecutiveDaysStayOnTheDayModelWhereLegacyGivesUp() {
        val entries = listOf(
            entry(1, "09-28", "23:00:00.000"),
            entry(2, "09-29", "01:00:00.000"),
            entry(3, "09-29", "23:00:00.000"),
            entry(4, "09-30", "01:00:00.000"),
        )

        val timeline = unrollLogTimeline(entries)
        assertEquals(26 * hourMs, timeline.byId[4]!! - timeline.byId[1]!!)
        assertTrue(timeline.dayOffsetModelValid)
        assertEquals(2, timeline.rolloverAppliedCount)
        assertEquals(0, timeline.rolloverSuppressedCount)
    }

    @Test
    fun outOfOrderRowFromAnEarlierDayGetsItsTrueEarlierTime() {
        val entries = listOf(
            entry(1, "09-30", "10:00:00.000"),
            entry(2, "09-30", "10:01:00.000"),
            entry(3, "09-29", "23:59:00.000"),
            entry(4, "09-30", "10:02:00.000"),
        )

        val byId = unrollLogTimeline(entries).byId
        // 23:59 the previous day is one minute before the first row (10:00 is value 36_000_000).
        assertEquals(-minuteMs, byId[3])
        assertEquals(millisOf("10:02:00.000"), byId[4])
    }

    // ── unrollLogTimeline: legacy path is untouched when any timed row is undated ──

    @Test
    fun aSingleUndatedTimedRowKeepsTheWholeLogOnTheLegacyPath() {
        val entries = listOf(
            entry(1, "09-29", "11:00:00.000"),
            entry(2, "09-29", "18:41:00.000"),
            entry(3, null, "12:15:00.000"),
        )

        val mixed = unrollLogTimeline(entries)
        assertEquals(unrollLogTimeline(points(entries, withDates = false)), mixed)
        assertEquals(millisOf("12:15:00.000"), mixed.byId[3])
    }

    @Test
    fun untimedRowsDoNotPreventTheDatePath() {
        val entries = listOf(
            entry(1, "09-29", "18:41:00.000"),
            // brief/RAW row: no ts, no date, not a timed point
            entry(2, null, ""),
            entry(3, "09-30", "12:15:00.000"),
        )

        val byId = unrollLogTimeline(entries).byId
        assertNull(byId[2])
        assertEquals(dayMs + millisOf("12:15:00.000"), byId[3])
    }

    @Test
    fun allUndatedLogsUseTheLegacyAlgorithm() {
        val entries = listOf(
            entry(1, null, "23:59:58.000"),
            entry(2, null, "00:00:01.000"),
        )
        val timeline = unrollLogTimeline(entries)
        assertEquals(dayMs + millisOf("00:00:01.000"), timeline.byId[2])
        assertEquals(1, timeline.rolloverAppliedCount)
    }

    // ── Δt ──────────────────────────────────────────────────────────────────

    @Test
    fun deltaAcrossDaysUsesTheDateDifference() {
        val yesterday = entry(1, "09-29", "18:41:00.000")
        val today = entry(2, "09-30", "12:15:00.000")

        // 24 h + (12:15 - 18:41), where the string form would say -6h26m.
        assertEquals(dayMs + millisOf("12:15:00.000") - millisOf("18:41:00.000"), deltaMillis(yesterday, today))
        assertEquals(-6 * hourMs - 26 * minuteMs, deltaMillis(yesterday.ts, today.ts))
        assertEquals(-(dayMs + millisOf("12:15:00.000") - millisOf("18:41:00.000")), deltaMillis(today, yesterday))
    }

    @Test
    fun deltaWithinADayAndAcrossMidnightMatchesTheStringForm() {
        val a = entry(1, "09-30", "10:00:00.000")
        val b = entry(2, "09-30", "10:00:02.651")
        assertEquals(2_651L, deltaMillis(a, b))
        assertEquals(deltaMillis(a.ts, b.ts), deltaMillis(a, b))

        val late = entry(3, "09-29", "23:59:59.000")
        val early = entry(4, "09-30", "00:00:01.000")
        assertEquals(2_000L, deltaMillis(late, early))
        assertEquals(deltaMillis(late.ts, early.ts), deltaMillis(late, early))

        // A small backwards step on the same day is returned as-is, like the string form.
        assertEquals(-100L, deltaMillis(entry(5, "09-30", "10:00:01.000"), entry(6, "09-30", "10:00:00.900")))
    }

    @Test
    fun deltaFallsBackToTheStringFormWhenEitherSideIsUndated() {
        val dated = entry(1, "09-29", "18:41:00.000")
        val undated = entry(2, null, "12:15:00.000")
        assertEquals(deltaMillis(dated.ts, undated.ts), deltaMillis(dated, undated))
        assertEquals(deltaMillis(undated.ts, dated.ts), deltaMillis(undated, dated))
        assertNull(deltaMillis(dated, entry(3, "09-30", ""))) // unparseable ts
    }

    @Test
    fun deltaAcrossYearEndAndFebruaryBoundaries() {
        assertEquals(2_000L, deltaMillis(entry(1, "12-31", "23:59:59.000"), entry(2, "01-01", "00:00:01.000")))
        // Non-leap: Feb 28 -> Mar 1 is one day (no phantom Feb 29).
        assertEquals(2 * minuteMs, deltaMillis(entry(3, "02-28", "23:59:00.000"), entry(4, "03-01", "00:01:00.000")))
        // A row that actually sits on Feb 29 makes it a real day.
        assertEquals(2 * minuteMs, deltaMillis(entry(5, "02-28", "23:59:00.000"), entry(6, "02-29", "00:01:00.000")))
        assertEquals(2 * minuteMs, deltaMillis(entry(7, "02-29", "23:59:00.000"), entry(8, "03-01", "00:01:00.000")))
    }

    // ── Capture / video mapping ─────────────────────────────────────────────

    @Test
    fun captureAnchorAndMappingTreatBufferedOtherDayRowsAsOutsideTheVideo() {
        // The shape of a capture with earlier device logs: buffered rows on 09-29, then the recorded
        // session on 09-30. Session row i is at 12:15:28 + i*100 ms; the video starts 984 ms in.
        val bufferedCount = 200
        val lines = (0 until bufferedCount).map { i ->
            val total = millisOf("11:06:00.000") + i * 2 * minuteMs
            "09-29 %02d:%02d:%02d.000  1000  1001 I Buffered: row $i".format(
                total / hourMs, total % hourMs / minuteMs, total % minuteMs / 1000,
            )
        } + (0 until 300).map { j ->
            val ms = millisOf("12:15:28.000") + j * 100L
            "09-30 12:15:%02d.%03d  1000  1001 I Session: row $j".format(ms % minuteMs / 1000, ms % 1000)
        }
        val entries = parseLogcatLines(lines.asSequence())
        assertEquals(bufferedCount + 300, entries.size)

        val elapsedById = unrollLogTimeline(entries).byId
        val sessionStartElapsed = elapsedById.getValue(bufferedCount + 1)
        val samples = entries.map { e ->
            val isSession = e.id > bufferedCount
            val videoMs = (elapsedById.getValue(e.id) - sessionStartElapsed - 984L + 40L).takeIf { isSession && it >= 0 }
            CaptureSyncSample(e.id, elapsedById[e.id], videoMs)
        }
        val anchor = assertNotNull(estimateCaptureSyncAnchor(samples, videoDurationMs = 30_000L))
        assertTrue(anchor.row > bufferedCount, "anchor must be a recorded session row, got ${anchor.row}")

        // Every buffered row predicts a position far before the video start (more than 18 h), so
        // none of them can land inside 0..duration; session rows are all in range.
        val anchorElapsed = elapsedById.getValue(anchor.row)
        for (e in entries) {
            val predicted = anchor.videoMs + (elapsedById.getValue(e.id) - anchorElapsed)
            if (e.id <= bufferedCount) {
                assertTrue(predicted < -hourMs, "buffered row ${e.id} predicted $predicted ms")
            } else {
                assertTrue(predicted in -1_000L..30_000L, "session row ${e.id} predicted $predicted ms")
            }
        }
    }

    @Test
    fun manualAnchorOnASessionRowMapsYesterdaysRowsOutsideTheKnownDuration() {
        val entries = listOf(
            // yesterday, same time of day as the session start
            entry(1, "09-29", "12:15:28.000"),
            entry(2, "09-29", "12:15:29.000"),
            entry(3, "09-30", "12:15:28.000"),
            entry(4, "09-30", "12:15:30.000"),
        )
        val anchor = VideoAnchor(videoMs = 1_000, logId = 3)
        val tab = LogTab(
            id = "t1",
            filename = "app.log",
            logData = entries,
            rmap = entries.associateBy { it.id },
            attachedVideo = VideoAttachment(
                path = "/tmp/repro.mp4",
                sourceLabel = "/tmp/repro.mp4",
                durationMs = 60_000,
                anchor = anchor,
            ),
        )
        val state = AppState()

        assertEquals(3_000L, state.logIdToVideoMs(tab, 4))
        val yesterdayMs = assertNotNull(state.logIdToVideoMs(tab, 2))
        assertTrue(yesterdayMs < -20 * hourMs, "yesterday's row mapped to $yesterdayMs")
        assertNull(state.validatedVideoRange(tab, yesterdayMs, yesterdayMs + 1))
        assertEquals(3, state.videoMsToNearestLogId(tab, 1_000))
        assertEquals(4, state.videoMsToNearestLogId(tab, 3_000))
    }

    @Test
    fun aNoteRowRestoredWithoutADateStillMatchesItsDatedTabRow() {
        val dated = parseLogcatLines(sequenceOf("09-30 12:15:28.046  1000  1100 I Tag: hello")).single()
        val tab = mkTab("t", "a.log", listOf(dated))
        // A .ann token carries no date, so the restored note row is the same row minus dayOfYear.
        assertTrue(tab.hasSameRow(dated.copy(dayOfYear = NO_DATE)))
        assertTrue(tab.hasSameRow(dated))
        assertFalse(tab.hasSameRow(dated.copy(msg = "changed", dayOfYear = NO_DATE)))
        assertFalse(tab.hasSameRow(dated.copy(id = dated.id + 1)))
    }
}
