package com.openlog

import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.utils.indexOfEntryId
import kotlin.test.Test
import kotlin.test.assertEquals

// PERF-1/PERF-7: manualCollapseRange (right-click collapse-to-start/end/selection) and the MCP
// get_line_context tool both used to re-scan tab.logData with .indexOf/.indexOfFirst on every
// call. These tests characterize the shared O(log n) replacement directly, mirroring
// LogViewerIndexingTest's coverage of the equivalent IntArray.indexOfId lookup.
class EntryIdMapTest {
    private fun entry(id: Int) = LogEntry(id, "10:00:00.000", LogLevel.I, "App", "line $id")

    @Test
    fun indexOfEntryIdFindsEntriesAtStartMiddleAndEnd() {
        val data = (10..14).map { entry(it) }
        assertEquals(0, data.indexOfEntryId(10))
        assertEquals(2, data.indexOfEntryId(12))
        assertEquals(4, data.indexOfEntryId(14))
    }

    @Test
    fun indexOfEntryIdReturnsMinusOneWhenAbsent() {
        val data = (10..14).map { entry(it) }
        assertEquals(-1, data.indexOfEntryId(9))
        assertEquals(-1, data.indexOfEntryId(15))
        assertEquals(-1, data.indexOfEntryId(-1))
    }

    @Test
    fun indexOfEntryIdWorksOnAnEmptyList() {
        assertEquals(-1, emptyList<LogEntry>().indexOfEntryId(1))
    }

    @Test
    fun indexOfEntryIdFallsBackToBinarySearchWhenIdsAreSparse() {
        // Merged/tailed logs can have gaps between ids — strictly ascending but not consecutive —
        // so the dense-guess fast path (id - first) will usually miss and must fall through to a
        // correct binary search rather than a wrong/missed result.
        val data = listOf(5, 8, 12, 20, 21, 35, 100).map { entry(it) }
        assertEquals(0, data.indexOfEntryId(5))
        assertEquals(2, data.indexOfEntryId(12))
        assertEquals(4, data.indexOfEntryId(21))
        assertEquals(6, data.indexOfEntryId(100))
        assertEquals(-1, data.indexOfEntryId(13))
        assertEquals(-1, data.indexOfEntryId(9))
    }

    @Test
    fun indexOfEntryIdHandlesASingleElementList() {
        val data = listOf(entry(42))
        assertEquals(0, data.indexOfEntryId(42))
        assertEquals(-1, data.indexOfEntryId(41))
    }
}
