package com.openlog

import androidx.compose.ui.graphics.Color
import com.openlog.model.Filter
import com.openlog.model.FilterMode
import com.openlog.model.LogAnalysis
import com.openlog.model.LogEntry
import com.openlog.model.LogItem
import com.openlog.model.LogLevel
import com.openlog.model.LogTab
import com.openlog.model.SequenceDef
import com.openlog.ui.mkTab
import com.openlog.utils.EntryIdMap
import com.openlog.utils.computeItems
import com.openlog.utils.invalidateComputeCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The stack-group toggle splice (spliceStackToggle in Filter.kt) must be indistinguishable from
// a full recompute. Ground truth for every case is the same computeItems call with the cache
// invalidated first, so any splice bug shows up as a list mismatch.
class ComputeItemsSpliceTest {
    private fun crashEntries(): List<LogEntry> = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello before", pid = 5),
        LogEntry(2, "10:00:00.001", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 5),
        LogEntry(3, "10:00:00.002", LogLevel.E, "AndroidRuntime", "java.lang.IllegalStateException: boom", pid = 5),
        LogEntry(4, "10:00:00.003", LogLevel.E, "AndroidRuntime", "    at com.example.Foo.bar(Foo.kt:1)", pid = 5),
        LogEntry(5, "10:00:00.004", LogLevel.I, "App", "middle", pid = 5),
        LogEntry(6, "10:00:00.005", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: worker", pid = 5),
        LogEntry(7, "10:00:00.006", LogLevel.E, "AndroidRuntime", "    at com.example.Baz.qux(Baz.kt:9)", pid = 5),
        LogEntry(8, "10:00:00.007", LogLevel.I, "App", "after", pid = 5),
    )

    private fun freshVsSpliced(tab: LogTab, warmExpanded: Set<String>, toggledExpanded: Set<String>) {
        invalidateComputeCache(tab.id)
        computeItems(tab.copy(expanded = warmExpanded), applyFilter = true) // warm the cache
        val spliced = computeItems(tab.copy(expanded = toggledExpanded), applyFilter = true)
        invalidateComputeCache(tab.id)
        val fresh = computeItems(tab.copy(expanded = toggledExpanded), applyFilter = true)
        assertEquals(fresh, spliced)
        assertTrue(fresh.isNotEmpty())
    }

    @Test
    fun expandingStackGroupMatchesFullRecompute() {
        val tab = mkTab("sp-exp", "a.log", crashEntries())
        val gid = tab.analysis.stackTraceGroups.first().gid
        freshVsSpliced(tab, warmExpanded = emptySet(), toggledExpanded = setOf(gid))
    }

    @Test
    fun collapsingStackGroupMatchesFullRecompute() {
        val tab = mkTab("sp-col", "a.log", crashEntries())
        val gids = tab.analysis.stackTraceGroups.map { it.gid }
        freshVsSpliced(tab, warmExpanded = gids.toSet(), toggledExpanded = gids.drop(1).toSet())
    }

    @Test
    fun expandingStackGroupUnderKeywordFilterMatchesFullRecompute() {
        val tab = mkTab("sp-kw", "a.log", crashEntries())
            .copy(filter = Filter(mode = FilterMode.KEYWORD, kwText = "e"))
        invalidateComputeCache(tab.id)
        val warm = computeItems(tab, applyFilter = true)
        val gid = warm.filterIsInstance<LogItem.StackTraceHeader>().first().gid
        val spliced = computeItems(tab.copy(expanded = setOf(gid)), applyFilter = true)
        invalidateComputeCache(tab.id)
        val fresh = computeItems(tab.copy(expanded = setOf(gid)), applyFilter = true)
        assertEquals(fresh, spliced)
    }

    @Test
    fun togglingStackGroupNestedInExpandedSequenceMatchesFullRecompute() {
        // A start-only sequence def on the first line swallows everything after it, so the crash
        // groups render nested once the sequence is expanded — the splice must handle that
        // placement (indent 1, members at indent 2) identically to a full recompute.
        val seq = SequenceDef(id = "sq", matchText = "hello before", priority = 1, color = Color.Red)
        val tab = mkTab("sp-nest", "a.log", crashEntries())
        val seqTab = tab.copy(filter = tab.filter.copy(sequences = listOf(seq)))
        invalidateComputeCache(seqTab.id)
        val base = computeItems(seqTab, applyFilter = true)
        val seqGid = base.filterIsInstance<LogItem.SeqHeader>().first().gid
        invalidateComputeCache(seqTab.id)
        val warmExpanded = setOf(seqGid)
        computeItems(seqTab.copy(expanded = warmExpanded), applyFilter = true)
        val nestedStackGid = seqTab.analysis.stackTraceGroups.first().gid
        val toggled = warmExpanded + nestedStackGid
        val spliced = computeItems(seqTab.copy(expanded = toggled), applyFilter = true)
        invalidateComputeCache(seqTab.id)
        val fresh = computeItems(seqTab.copy(expanded = toggled), applyFilter = true)
        assertEquals(fresh, spliced)
    }

    @Test
    fun pendingAnalysisRendersUnfoldedWithoutStackScan() {
        val entries = crashEntries()
        val tab = LogTab(
            id = "sp-pend",
            filename = "x.log",
            logData = entries,
            rmap = EntryIdMap(entries),
            analysis = LogAnalysis(pending = true),
        )
        invalidateComputeCache(tab.id)
        val items = computeItems(tab, applyFilter = true)
        assertTrue(items.all { it is LogItem.Row }, "no folding while analysis is pending")
        assertEquals(entries.size, items.size)
    }
}
