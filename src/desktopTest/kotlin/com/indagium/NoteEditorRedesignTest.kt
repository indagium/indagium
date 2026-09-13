package com.indagium

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.evidenceSummary
import com.indagium.ui.logExcerptVisibleRowCount
import com.indagium.ui.markdownWordCount
import com.indagium.ui.rangeLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteEditorRedesignTest {
    private fun entry(id: Int, ts: String, level: LogLevel, tag: String = "Tag", msg: String = "msg") =
        LogEntry(id = id, ts = ts, level = level, tag = tag, msg = msg)

    @Test
    fun evidenceSummaryCountsFirstAndLastTimestamp() {
        val rows = listOf(
            entry(1, "09:15:00.010", LogLevel.I),
            entry(2, "09:15:00.200", LogLevel.W),
            entry(3, "09:15:00.552", LogLevel.E),
        )

        val summary = evidenceSummary(rows)

        assertEquals(3, summary.count)
        assertEquals("09:15:00.010", summary.firstTs)
        assertEquals("09:15:00.552", summary.lastTs)
    }

    @Test
    fun evidenceSummaryOrdersLevelChipsWorstFirstAndSkipsZeroCounts() {
        val rows = listOf(
            entry(1, "t1", LogLevel.V),
            entry(2, "t2", LogLevel.V),
            entry(3, "t3", LogLevel.I),
            entry(4, "t4", LogLevel.I),
            entry(5, "t5", LogLevel.I),
            entry(6, "t6", LogLevel.I),
            entry(7, "t7", LogLevel.W),
            entry(8, "t8", LogLevel.W),
        )

        val summary = evidenceSummary(rows)

        // Error and Debug are absent (zero rows) — they must not appear at all, and the rest read
        // worst-first: Warn, Info, Verbose.
        assertEquals(
            listOf(LogLevel.W to 2, LogLevel.I to 4, LogLevel.V to 2),
            summary.levelCounts,
        )
    }

    @Test
    fun evidenceSummaryOfNoRowsIsEmptyNotCrashing() {
        val summary = evidenceSummary(emptyList())

        assertEquals(0, summary.count)
        assertEquals("", summary.firstTs)
        assertEquals("", summary.lastTs)
        assertTrue(summary.levelCounts.isEmpty())
    }

    @Test
    fun markdownWordCountSplitsOnWhitespaceRuns() {
        assertEquals(0, markdownWordCount(""))
        assertEquals(0, markdownWordCount("   "))
        assertEquals(1, markdownWordCount("crash"))
        assertEquals(3, markdownWordCount("USB poll budget"))
        assertEquals(3, markdownWordCount("  USB   poll\nbudget  "))
    }

    @Test
    fun markdownWordCountDoesNotTreatMarkdownPunctuationAsExtraWords() {
        assertEquals(2, markdownWordCount("**bold** text"))
        assertEquals(2, markdownWordCount("`durationMs=761` right"))
    }

    @Test
    fun logExcerptVisibleRowCountShowsAllRowsWhenAtOrUnderTheCap() {
        assertEquals(0, logExcerptVisibleRowCount(total = 0, expanded = false))
        assertEquals(1, logExcerptVisibleRowCount(total = 1, expanded = false))
        assertEquals(3, logExcerptVisibleRowCount(total = 3, expanded = false))
    }

    @Test
    fun logExcerptVisibleRowCountCollapsesToCapWhenOverItAndNotExpanded() {
        assertEquals(3, logExcerptVisibleRowCount(total = 4, expanded = false))
        assertEquals(3, logExcerptVisibleRowCount(total = 10, expanded = false))
    }

    @Test
    fun logExcerptVisibleRowCountShowsEveryRowWhenExpandedRegardlessOfTotal() {
        assertEquals(4, logExcerptVisibleRowCount(total = 4, expanded = true))
        assertEquals(10, logExcerptVisibleRowCount(total = 10, expanded = true))
        assertEquals(0, logExcerptVisibleRowCount(total = 0, expanded = true))
    }

    @Test
    fun logExcerptVisibleRowCountHonorsACustomCap() {
        assertEquals(5, logExcerptVisibleRowCount(total = 8, expanded = false, cap = 5))
        assertEquals(8, logExcerptVisibleRowCount(total = 8, expanded = false, cap = 10))
    }

    @Test
    fun rangeLabelSingularisesOneLine() {
        val one = listOf(LogEntry(1, "09:15:00.010", LogLevel.I, "Tag", "msg"))
        assertEquals("1 line · 09:15:00.010 → 09:15:00.010", evidenceSummary(one).rangeLabel())
        val two = one + LogEntry(2, "09:15:00.552", LogLevel.W, "Tag", "msg")
        assertEquals("2 lines · 09:15:00.010 → 09:15:00.552", evidenceSummary(two).rangeLabel())
    }
}
