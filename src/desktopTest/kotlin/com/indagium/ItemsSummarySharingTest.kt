package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.LogEntry
import com.indagium.model.LogItem
import com.indagium.model.LogLevel
import com.indagium.model.ManualCollapseDirection
import com.indagium.ui.spliceSummarize
import com.indagium.ui.summarizeItems
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

// When every item is a plain row, rowIds is allIds element for element, so the summary shares one
// array. The arrays are read-only after construction; a mixed list keeps two separate arrays.
class ItemsSummarySharingTest {
    private fun entry(id: Int) = LogEntry(id, "10:00:00.000", LogLevel.I, "App", "line $id")

    private fun row(id: Int) = LogItem.Row(entry(id), 0)

    private fun header(id: Int) =
        LogItem.ManualHeader(entry(id), "m$id", ManualCollapseDirection.TO_END, expanded = false, count = 1, color = Color.Red)

    @Test
    fun allRowsShareTheSameArrayAndStillHoldTheRightIds() {
        val summary = summarizeItems(listOf(row(1), row(2), row(5)))
        assertSame(summary.allIds, summary.rowIds)
        assertTrue(intArrayOf(1, 2, 5).contentEquals(summary.rowIds))
        assertTrue(summary.rowCount == 3)
    }

    @Test
    fun emptyAndMixedListsKeepSeparateArrays() {
        val empty = summarizeItems(emptyList())
        assertTrue(empty.rowIds.isEmpty() && empty.allIds.isEmpty())
        val mixed = summarizeItems(listOf(row(1), header(2), row(3)))
        assertNotSame(mixed.allIds, mixed.rowIds)
        assertTrue(intArrayOf(1, 3).contentEquals(mixed.rowIds))
        assertTrue(intArrayOf(1, 2, 3).contentEquals(mixed.allIds))
    }

    @Test
    fun spliceSharesWhenTheResultIsAllRowsAndSplitsWhenAHeaderAppears() {
        val shared = row(1)
        val oldItems = listOf(shared, header(2), row(3))
        val oldSummary = summarizeItems(oldItems)
        assertNotSame(oldSummary.allIds, oldSummary.rowIds)

        // The header is replaced by plain rows: every item is a row afterwards.
        val allRows = listOf(shared, row(2), row(3))
        val spliced = spliceSummarize(oldItems, oldSummary, allRows)
        assertNotNull(spliced)
        assertSame(spliced.allIds, spliced.rowIds)
        assertTrue(intArrayOf(1, 2, 3).contentEquals(spliced.rowIds))

        // And back: a shared-array summary spliced into a list with a header gets its own arrays.
        val back = spliceSummarize(allRows, spliced, listOf(shared, header(2), allRows[2]))
        assertNotNull(back)
        assertNotSame(back.allIds, back.rowIds)
        assertTrue(intArrayOf(1, 3).contentEquals(back.rowIds))
        assertTrue(intArrayOf(1, 2, 3).contentEquals(back.allIds))
        // The shared summary it was spliced from is untouched.
        assertTrue(intArrayOf(1, 2, 3).contentEquals(spliced.allIds))
    }
}
