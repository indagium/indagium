package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.Filter
import com.indagium.model.LogEntry
import com.indagium.model.LogItem
import com.indagium.model.LogLevel
import com.indagium.model.SeqGroup
import com.indagium.model.SequenceDef
import com.indagium.ui.mkTab
import com.indagium.utils.buildSeqOwnerByStackRoot
import com.indagium.utils.computeItems
import com.indagium.utils.invalidateComputeCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeqOwnerByStackRootTest {
    private fun logs(): List<LogEntry> = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "App", "burst start", pid = 1),
        LogEntry(2, "10:00:00.100", LogLevel.I, "App", "plain content", pid = 1),
        LogEntry(3, "10:00:00.200", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 1),
        LogEntry(4, "10:00:00.300", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 1),
        LogEntry(5, "10:00:00.400", LogLevel.I, "App", "more plain content", pid = 1),
        LogEntry(6, "10:00:00.500", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: worker", pid = 1),
        LogEntry(7, "10:00:00.600", LogLevel.E, "AndroidRuntime", "    at com.app.Worker.run(Worker.java:5)", pid = 1),
        LogEntry(8, "10:00:00.700", LogLevel.I, "App", "tail", pid = 1),
    )

    private val burst = SequenceDef("burst", "burst start", priority = 1, color = Color.Blue, tag = "App")

    @Test
    fun mapContainsOnlyStackRootsNotEverySwallowedRow() {
        val tab = mkTab("so-1", "a.log", logs()).copy(filter = Filter(sequences = listOf(burst)))
        val stackRoots = tab.analysis.stackTraceGroups.map { it.rid }.toSet()
        assertEquals(setOf(3, 6), stackRoots)
        val seqGroup = SeqGroup("sg_burst_1", rid = 1, plain = listOf(2, 3, 4, 5, 6, 7, 8), nested = emptyList(), defId = "burst", endExclusive = 8)

        val owner = buildSeqOwnerByStackRoot(listOf(seqGroup), tab.analysis.stackTraceGroups)

        assertEquals(mapOf(3 to "sg_burst_1", 6 to "sg_burst_1"), owner)
    }

    @Test
    fun lastSequenceWinsWhenTwoSwallowTheSameRootAndNothingIsBuiltWithoutStacksOrSequences() {
        val tab = mkTab("so-2", "a.log", logs())
        val a = SeqGroup("a", 1, listOf(3), emptyList(), "x", 4)
        val b = SeqGroup("b", 2, listOf(3, 6), emptyList(), "x", 8)
        assertEquals(mapOf(3 to "b", 6 to "b"), buildSeqOwnerByStackRoot(listOf(a, b), tab.analysis.stackTraceGroups))
        assertTrue(buildSeqOwnerByStackRoot(emptyList(), tab.analysis.stackTraceGroups).isEmpty())
        assertTrue(buildSeqOwnerByStackRoot(listOf(a, b), emptyList()).isEmpty())
    }

    // Collapsed -> expanded -> collapsed reuses the cached owner map across expanded-set changes.
    // A crash swallowed by a sequence nests inside it only while that sequence is expanded.
    @Test
    fun crashNestsInsideAnExpandedSequenceAndStaysIndependentInACollapsedOneWithAWarmCache() {
        val base = mkTab("so-3", "a.log", logs()).copy(filter = Filter(sequences = listOf(burst)))
        invalidateComputeCache(base.id)

        val collapsed = computeItems(base, applyFilter = true)
        val collapsedCrashes = collapsed.filterIsInstance<LogItem.StackTraceHeader>()
        assertEquals(listOf(3, 6), collapsedCrashes.map { it.entry.id })
        assertTrue(collapsedCrashes.all { it.indent == 0 })

        val sequenceGid = collapsed.filterIsInstance<LogItem.SeqHeader>().single().gid
        val expandedTab = base.copy(expanded = setOf(sequenceGid))
        val expanded = computeItems(expandedTab, applyFilter = true)
        assertTrue(expanded.filterIsInstance<LogItem.StackTraceHeader>().all { it.indent == 1 })
        invalidateComputeCache(base.id)
        assertEquals(computeItems(expandedTab, applyFilter = true), expanded)

        val collapsedAgain = computeItems(base, applyFilter = true)
        assertTrue(collapsedAgain.filterIsInstance<LogItem.StackTraceHeader>().all { it.indent == 0 })
        invalidateComputeCache(base.id)
        assertEquals(computeItems(base, applyFilter = true), collapsedAgain)
    }
}
