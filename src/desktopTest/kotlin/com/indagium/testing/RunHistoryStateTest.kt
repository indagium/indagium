package com.indagium.testing

import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.RunSummary
import com.indagium.ui.RunFilter
import com.indagium.ui.countNoun
import com.indagium.ui.filterRuns
import com.indagium.ui.groupRunsByDay
import com.indagium.ui.passFraction
import com.indagium.ui.runDayLabel
import com.indagium.ui.runFilterCounts
import com.indagium.ui.runMetaText
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** The pure run-history rules behind the Runs screen and the suite's Runs tab: day groups, status filters, bar fraction, meta text. */
class RunHistoryStateTest {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0, z: ZoneId = zone): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, z).toInstant().toEpochMilli()

    private fun run(
        id: String,
        createdAt: Long,
        status: RunStatus = RunStatus.PASSED,
        suiteId: String = "s1",
        passed: Int = 2,
        total: Int = 4,
        cases: Int = 2,
        lanes: Int = 1,
    ) = RunSummary(id, "Suite", status, createdAt, null, lanes, cases, passed, total, suiteId = suiteId)

    private val now = at(2026, 10, 12, 15, 0)

    @Test
    fun runsAreGroupedByDayNewestFirstWithTodayYesterdayAndWeekdayLabels() {
        val runs = listOf(
            run("old", at(2026, 10, 9, 8, 0)),
            run("today-early", at(2026, 10, 12, 8, 30)),
            run("yesterday", at(2026, 10, 11, 22, 10)),
            run("today-late", at(2026, 10, 12, 14, 45)),
        )
        val groups = groupRunsByDay(runs, now, zone)

        assertEquals(listOf("Today", "Yesterday", "Friday, Oct 9"), groups.map { it.label })
        assertEquals(listOf("today-late", "today-early"), groups[0].runs.map { it.id })
        assertEquals(listOf("yesterday"), groups[1].runs.map { it.id })
        assertEquals(listOf("old"), groups[2].runs.map { it.id })
    }

    @Test
    fun anotherCalendarYearAddsTheYearToTheLabel() {
        val groups = groupRunsByDay(listOf(run("a", at(2025, 10, 9)), run("b", at(2026, 1, 2))), now, zone)

        assertEquals(listOf("Friday, Jan 2", "Thursday, Oct 9, 2025"), groups.map { it.label })
        assertEquals("Thursday, Oct 9, 2025", runDayLabel(LocalDate.of(2025, 10, 9), LocalDate.of(2026, 10, 12)))
    }

    @Test
    fun midnightBelongsToTheNewDayAndTheLastMillisecondToTheOldOne() {
        val midnight = at(2026, 10, 12, 0, 0)
        val groups = groupRunsByDay(listOf(run("just-after", midnight), run("just-before", midnight - 1)), now, zone)

        assertEquals(listOf("Today", "Yesterday"), groups.map { it.label })
        assertEquals("just-after", groups[0].runs.single().id)
        assertEquals("just-before", groups[1].runs.single().id)
    }

    @Test
    fun theZoneDecidesWhichDayARunBelongsTo() {
        // 23:30 on Oct 11 in New York is 05:30 on Oct 12 in Berlin.
        val ny = ZoneId.of("America/New_York")
        val created = at(2026, 10, 11, 23, 30, ny)
        val nowInBerlin = at(2026, 10, 12, 15, 0)

        assertEquals("Today", groupRunsByDay(listOf(run("a", created)), nowInBerlin, zone).single().label)
        val nowInNewYork = at(2026, 10, 12, 9, 0, ny)
        assertEquals("Yesterday", groupRunsByDay(listOf(run("a", created)), nowInNewYork, ny).single().label)
    }

    @Test
    fun noRunsMakeNoGroupsAndEqualTimesKeepAStableOrder() {
        assertEquals(emptyList(), groupRunsByDay(emptyList(), now, zone))
        val t = at(2026, 10, 12, 10, 0)
        assertEquals(listOf("b", "a"), groupRunsByDay(listOf(run("a", t), run("b", t)), now, zone).single().runs.map { it.id })
    }

    @Test
    fun filtersCountFailedAsFailedPlusErrorAndKeepLiveRunsUnderAllOnly() {
        val runs = listOf(
            run("1", 1, RunStatus.FAILED), run("2", 2, RunStatus.ERROR), run("3", 3, RunStatus.PASSED),
            run("4", 4, RunStatus.CANCELLED), run("5", 5, RunStatus.RUNNING), run("6", 6, RunStatus.QUEUED, suiteId = "s2"),
        )
        val counts = runFilterCounts(runs)

        assertEquals(6, counts[RunFilter.ALL])
        assertEquals(2, counts[RunFilter.FAILED])
        assertEquals(1, counts[RunFilter.PASSED])
        assertEquals(1, counts[RunFilter.CANCELLED])
        assertEquals(listOf("1", "2"), filterRuns(runs, null, RunFilter.FAILED).map { it.id })
        assertEquals(listOf("6"), filterRuns(runs, "s2", RunFilter.ALL).map { it.id })
        assertEquals(emptyList(), filterRuns(runs, "s2", RunFilter.PASSED))
    }

    @Test
    fun theBarFractionAndTheMetaTextFollowTheSummary() {
        assertEquals(.5f, run("a", 1, passed = 2, total = 4).passFraction())
        assertEquals(0f, run("a", 1, passed = 0, total = 0).passFraction())
        assertEquals(1f, run("a", 1, passed = 5, total = 4).passFraction())
        assertEquals("2 cases · 1 lane", runMetaText(run("a", 1), showSuite = true))
        assertEquals("3 lanes", runMetaText(run("a", 1, lanes = 3), showSuite = false))
        assertEquals("1 case", countNoun(1, "case"))
        val noted = run("a", 1).copy(pendingVerificationSteps = 2, flaky = true)
        assertEquals("2 cases · 1 lane · 2 awaiting judge · flaky recovery", runMetaText(noted, showSuite = true))
    }
}
