package com.openlog

import com.openlog.model.LogEntry
import com.openlog.model.LogItem
import com.openlog.model.LogLevel
import com.openlog.ui.ItemsSummary
import com.openlog.ui.cursorRowIndex
import com.openlog.ui.indexOfId
import com.openlog.ui.visibleRowRangeIds
import kotlin.test.Test
import kotlin.test.assertEquals

// P-05: keyboard navigation and drag-selection used to re-scan rows/items with .indexOf/
// .indexOfFirst on every keypress/pointer-move instead of using an O(log n) id->position lookup.
// These tests cover the new lookup primitive (indexOfId) and the shared cursor-position logic
// (cursorRowIndex) directly — handleNavKey/handleSelKey themselves stay private and take a real
// Compose KeyEvent/LazyListState/CoroutineScope, which isn't practical to construct in a plain
// unit test, so the actual id-lookup logic they both delegate to is what's characterized here.
class LogViewerIndexingTest {
    @Test
    fun indexOfIdFindsEntriesAtStartMiddleAndEnd() {
        val ids = intArrayOf(10, 11, 12, 13, 14)
        assertEquals(0, ids.indexOfId(10))
        assertEquals(2, ids.indexOfId(12))
        assertEquals(4, ids.indexOfId(14))
    }

    @Test
    fun indexOfIdReturnsMinusOneWhenAbsent() {
        val ids = intArrayOf(10, 11, 12, 13, 14)
        assertEquals(-1, ids.indexOfId(9))
        assertEquals(-1, ids.indexOfId(15))
        assertEquals(-1, ids.indexOfId(-1))
    }

    @Test
    fun indexOfIdWorksOnAnEmptyArray() {
        assertEquals(-1, IntArray(0).indexOfId(1))
    }

    @Test
    fun indexOfIdFallsBackToBinarySearchWhenIdsAreNotDense() {
        // Filtering/folding removes ids, so the array is strictly ascending but not consecutive —
        // the dense-guess fast path (id - first) will usually miss and must fall through to a
        // correct binary search rather than a wrong/missed result.
        val ids = intArrayOf(5, 8, 12, 20, 21, 35, 100)
        assertEquals(0, ids.indexOfId(5))
        assertEquals(2, ids.indexOfId(12))
        assertEquals(4, ids.indexOfId(21))
        assertEquals(6, ids.indexOfId(100))
        assertEquals(-1, ids.indexOfId(13))
        assertEquals(-1, ids.indexOfId(9))
    }

    @Test
    fun indexOfIdHandlesASingleElementArray() {
        val ids = intArrayOf(42)
        assertEquals(0, ids.indexOfId(42))
        assertEquals(-1, ids.indexOfId(41))
    }

    private fun row(id: Int) = LogItem.Row(LogEntry(id, "10:00:00.000", LogLevel.I, "App", "line $id"), 0)

    private fun manualHeader(id: Int, gid: String) =
        LogItem.ManualHeader(
            LogEntry(id, "10:00:00.000", LogLevel.I, "App", "line $id"),
            gid,
            com.openlog.model.ManualCollapseDirection.TO_END,
            expanded = false,
            count = 1,
            color = androidx.compose.ui.graphics.Color.Red,
        )

    private fun summaryOf(items: List<LogItem>): ItemsSummary {
        val allIds = items.map { item -> (item as? LogItem.Row)?.entry?.id ?: (item as LogItem.ManualHeader).entry.id }.toIntArray()
        val rowIds = items.filterIsInstance<LogItem.Row>().map { it.entry.id }.toIntArray()
        return ItemsSummary(allIds, rowIds, java.util.BitSet(), collapsedGroupCount = 0, expandedGroupCount = 0)
    }

    @Test
    fun cursorRowIndexReturnsTheRowsPositionWhenACursorIdIsPresent() {
        val items = listOf(row(1), row(2), row(3), row(4))
        val summary = summaryOf(items)

        assertEquals(0, cursorRowIndex(cursorEntryId = 1, firstVisibleItemIndex = 0, items = items, summary = summary))
        assertEquals(2, cursorRowIndex(cursorEntryId = 3, firstVisibleItemIndex = 0, items = items, summary = summary))
    }

    @Test
    fun cursorRowIndexFallsBackToTheFirstVisibleRowWhenNoCursorIdIsSet() {
        val items = listOf(row(1), row(2), row(3), row(4))
        val summary = summaryOf(items)

        // firstVisibleItemIndex points directly at a row (items-index 2 == entry id 3, row-index 2).
        assertEquals(2, cursorRowIndex(cursorEntryId = null, firstVisibleItemIndex = 2, items = items, summary = summary))
    }

    @Test
    fun cursorRowIndexSkipsPastANonRowHeaderWhenFallingBackFromViewportPosition() {
        // items-index 0 is a header (not a Row); the first actual row at/after it is at items-index
        // 1 (entry id 2), which is row-index 0 within the row-only list.
        val items = listOf(manualHeader(1, "m1"), row(2), row(3))
        val summary = summaryOf(items)

        assertEquals(0, cursorRowIndex(cursorEntryId = null, firstVisibleItemIndex = 0, items = items, summary = summary))
    }

    @Test
    fun cursorRowIndexReturnsZeroWhenTheCursorIdIsNotAVisibleRow() {
        val items = listOf(row(1), row(2), row(3))
        val summary = summaryOf(items)

        // Cursor id refers to an entry that isn't in the current row list at all (e.g. folded into
        // a collapsed group) — must not throw, must degrade to 0 like the pre-existing .indexOfFirst
        // + coerceAtLeast(0) behavior did.
        assertEquals(0, cursorRowIndex(cursorEntryId = 999, firstVisibleItemIndex = 0, items = items, summary = summary))
    }

    @Test
    fun visibleRowRangeIdsIntArrayOverloadMatchesEitherDirection() {
        val visibleIds = intArrayOf(10, 20, 30, 40)

        assertEquals(listOf(20, 30, 40), visibleRowRangeIds(20, 40, visibleIds))
        assertEquals(listOf(20, 30, 40), visibleRowRangeIds(40, 20, visibleIds))
        assertEquals(listOf(10, 20, 30, 40), visibleRowRangeIds(10, 40, visibleIds))
        assertEquals(listOf(30), visibleRowRangeIds(30, 30, visibleIds))
    }

    @Test
    fun visibleRowRangeIdsIntArrayOverloadReturnsEmptyWhenEitherEndIsMissing() {
        val visibleIds = intArrayOf(10, 20, 30, 40)

        assertEquals(emptyList(), visibleRowRangeIds(10, 999, visibleIds))
        assertEquals(emptyList(), visibleRowRangeIds(999, 10, visibleIds))
    }
}
