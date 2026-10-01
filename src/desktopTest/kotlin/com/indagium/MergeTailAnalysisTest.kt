@file:Suppress("MagicNumber")

package com.indagium

import com.indagium.model.LogAnalysis
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.StackTraceGroup
import com.indagium.ui.extendsSnapshot
import com.indagium.ui.mergeTailAnalysis
import com.indagium.ui.mkTab
import com.indagium.utils.appendLogEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The decision `TailCoordinator.refreshAnalysis` makes when a slow full analysis finishes. */
class MergeTailAnalysisTest {
    private fun entries(range: IntRange) = range.map { LogEntry(it, "10:00:00.000", LogLevel.I, "Tag", "msg $it") }

    private val group = StackTraceGroup(gid = "g1", rid = 3, memberIds = listOf(3, 4))
    private val full = LogAnalysis(
        tagCounts = mapOf("Tag" to 5),
        stackTraceGroups = listOf(group),
        processNames = mapOf(1 to "snapshot.proc"),
        pending = false,
    )
    private val incremental = LogAnalysis(
        tagCounts = mapOf("Tag" to 9),
        processNames = mapOf(1 to "live.proc", 2 to "other"),
        pending = true,
    )

    @Test
    fun identicalLogDataAppliesTheFullResultCompleteAndKeepsTheLiveCounts() {
        val snapshot = appendLogEntries(entries(1..3), entries(4..5))
        val tab = mkTab("t", "f", snapshot, analysis = incremental)
        val merged = mergeTailAnalysis(tab, snapshot, full)!!
        assertEquals(listOf(group), merged.stackTraceGroups)
        assertEquals(incremental.tagCounts, merged.tagCounts, "the refresh does not even compute the counts")
        assertEquals(incremental.processNames, merged.processNames)
        assertFalse(merged.pending)
        assertNull(merged.analyzedThroughId, "complete: it covers every row the tab holds")
        assertFalse(merged.isStaleFor(snapshot.last().id))
    }

    @Test
    fun appendedRowsKeepTheLiveCountsAndRecordHowFarTheResultsReach() {
        val snapshot = appendLogEntries(entries(1..3), entries(4..5))
        val current = appendLogEntries(snapshot, entries(6..9))
        val merged = mergeTailAnalysis(mkTab("t", "f", current, analysis = incremental), snapshot, full)!!
        assertEquals(listOf(group), merged.stackTraceGroups)
        assertEquals(incremental.tagCounts, merged.tagCounts)
        assertEquals(incremental.processNames, merged.processNames)
        // The analysed prefix's results are visible; only the appended rows (ids 6..9) are unanalysed.
        assertFalse(merged.pending, "the prefix's results must not be hidden while rows keep arriving")
        assertEquals(5, merged.analyzedThroughId)
        assertFalse(merged.isStaleFor(5))
        assertTrue(merged.isStaleFor(9))
    }

    @Test
    fun aPendingAnalysisIsStaleUntilItIsReplaced() {
        assertTrue(LogAnalysis().isStaleFor(null), "nothing analysed yet: the initial load still shows analyzing")
        assertTrue(LogAnalysis().isStaleFor(3))
    }

    @Test
    fun appendedRowsAcrossBackingStoreRegrowthStillCount() {
        val snapshot = appendLogEntries(emptyList(), entries(1..5))
        // Far more than the store's spare capacity: the current list lives in a different store.
        val current = appendLogEntries(snapshot, entries(6..5000))
        assertTrue(extendsSnapshot(current, snapshot))
        val merged = mergeTailAnalysis(mkTab("t", "f", current, analysis = incremental), snapshot, full)!!
        assertEquals(listOf(group), merged.stackTraceGroups)
        assertFalse(merged.pending)
        assertEquals(5, merged.analyzedThroughId)
    }

    @Test
    fun replacedOrUnrelatedLogDataIsDiscarded() {
        val snapshot = appendLogEntries(entries(1..3), entries(4..5))
        val unrelated = entries(1..9) // equal ids, but different row objects
        assertNull(mergeTailAnalysis(mkTab("t", "f", unrelated, analysis = incremental), snapshot, full))
        val shorter = appendLogEntries(emptyList(), snapshot.take(2))
        assertNull(mergeTailAnalysis(mkTab("t", "f", shorter, analysis = incremental), snapshot, full))
    }

    @Test
    fun emptySnapshotWithLaterRowsIsDiscarded() {
        val current = entries(1..4)
        assertNull(mergeTailAnalysis(mkTab("t", "f", current, analysis = incremental), emptyList(), full))
        assertFalse(extendsSnapshot(current, emptyList()))
    }
}
