package com.indagium

import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.mkTab
import com.indagium.ui.spliceSummarize
import com.indagium.ui.summarizeItems
import com.indagium.utils.FlatRows
import com.indagium.utils.MatchingEntries
import com.indagium.utils.appendLogEntries
import com.indagium.utils.cachedVisibleEntriesFor
import com.indagium.utils.computeItems
import com.indagium.utils.invalidateComputeCache
import com.indagium.utils.isAppendOnlyExtensionOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FlatRowsProjectionTest {
    private fun entries(first: Int, count: Int) = (first until first + count).map {
        LogEntry(it, "10:00:00.000", LogLevel.I, "synthetic", if (it % 2 == 0) "keep" else "drop")
    }

    @Test
    fun allMatchesReuseInputAndRowsAreCreatedOnAccess() {
        val tab = mkTab("flat-all", "synthetic.log", entries(1, 10))
        invalidateComputeCache(tab.id)
        val items = computeItems(tab, true)
        assertTrue(items is FlatRows)
        assertSame(tab.logData, cachedVisibleEntriesFor(tab, true))
        assertNotSame(items[0], items[0])
        assertEquals(items[0], items[0])
    }

    @Test
    fun sparseMatchesAndAppendedSnapshotsKeepTheirOwnContents() {
        val tab = mkTab("flat-sparse", "synthetic.log", entries(1, 10))
            .copy(filter = Filter(mode = FilterMode.KEYWORD, kwText = "keep"))
        invalidateComputeCache(tab.id)
        val before = computeItems(tab, true)
        assertTrue(cachedVisibleEntriesFor(tab, true) is MatchingEntries)
        val summary = summarizeItems(before)
        val grown = tab.copy(logData = appendLogEntries(tab.logData, entries(11, 4)))
        val after = computeItems(grown, true)
        assertEquals(5, before.size)
        assertContentEquals(intArrayOf(2, 4, 6, 8, 10), summarizeItems(before).rowIds)
        assertContentEquals(intArrayOf(2, 4, 6, 8, 10, 12, 14), summarizeItems(after).rowIds)
        assertContentEquals(summarizeItems(after).rowIds, spliceSummarize(before, summary, after)!!.rowIds)
    }

    @Test
    fun emptyAndInitiallyAllPassingPrefixesSurviveFilteredAppendTransitions() {
        for (first in listOf(1, 2)) {
            val tab = mkTab("flat-transition-$first", "synthetic.log", entries(first, 1))
                .copy(filter = Filter(mode = FilterMode.KEYWORD, kwText = "keep"))
            invalidateComputeCache(tab.id)
            val initial = computeItems(tab, true)
            assertContentEquals(if (first == 2) intArrayOf(2) else intArrayOf(), summarizeItems(initial).rowIds)
            val noMatch = tab.copy(logData = appendLogEntries(tab.logData, entries(3, 1)))
            assertSame(initial, computeItems(noMatch, true))
            val match = noMatch.copy(logData = appendLogEntries(noMatch.logData, entries(4, 1)))
            val grown = computeItems(match, true)
            assertContentEquals(if (first == 2) intArrayOf(2, 4) else intArrayOf(4), summarizeItems(grown).rowIds)
            assertEquals(computeItems(match.copy(id = "flat-transition-fresh-$first"), true), grown)
        }
    }

    @Test
    fun divergentBranchesSharingTheirLastEntryAreNotExtensionsOrEqual() {
        val prefix = entries(1, 1)
        val commonLast = entries(4, 1).single()
        val base = FlatRows(prefix)
        val left = base.extend(prefix + entries(2, 1)).extend(prefix + entries(2, 1) + commonLast)
        val right = base.extend(prefix + entries(3, 1)).extend(prefix + entries(3, 1) + commonLast)
        assertFalse(right.isAppendOnlyExtensionOf(left))
        assertFalse(right == left)
        val updated = spliceSummarize(left, summarizeItems(left), right) ?: summarizeItems(right)
        assertContentEquals(intArrayOf(1, 3, 4), updated.rowIds)
    }

    @Test
    fun filteringMatchesExpectedRowsAcrossSparseAndDensePatterns() {
        val cases = listOf(
            1 to { id: Int -> id != 1 },
            2 to { id: Int -> id != 5000 },
            3 to { id: Int -> id % 5 == 0 },
            4 to { _: Int -> false },
            5 to { id: Int -> id > 1 && id <= 1025 },
            6 to { id: Int -> id > 1 && id <= 1538 },
        )
        for ((caseId, matches) in cases) {
            val rows = (1..5000).map { id ->
                LogEntry(id, "10:00:00.000", LogLevel.I, "synthetic", if (matches(id)) "keep" else "drop")
            }
            val tab = mkTab("flat-pattern-$caseId", "synthetic.log", rows)
                .copy(filter = Filter(mode = FilterMode.KEYWORD, kwText = "keep"))
            invalidateComputeCache(tab.id)
            val items = computeItems(tab, true)
            val expected = rows.filter { matches(it.id) }.map { it.id }.toIntArray()
            assertContentEquals(expected, summarizeItems(items).rowIds, "case $caseId")
            assertEquals(expected.toList(), cachedVisibleEntriesFor(tab, true)?.map { it.id }, "case $caseId")
        }
    }
}
