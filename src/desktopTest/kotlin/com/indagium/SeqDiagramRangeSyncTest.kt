package com.indagium

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.mkTab
import com.indagium.ui.rowsForTimeRange
import com.indagium.ui.selectionRangeForRows
import com.indagium.ui.timeRangeForRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SeqDiagramRangeSyncTest {
    private val tab = mkTab(
        "t1",
        "app.log",
        listOf(
            LogEntry(10, "10:00:00.000", LogLevel.I, "A", "a"),
            LogEntry(11, "", LogLevel.I, "A", "brief"),
            LogEntry(12, "10:00:00.200", LogLevel.I, "B", "b"),
            LogEntry(13, "10:00:00.300", LogLevel.I, "A", "c"),
        ),
    )

    @Test
    fun selectionAndTimeRangesUseTheSameSelectedRows() {
        assertEquals(10..13, selectionRangeForRows(tab, setOf(13, 10))?.let { it.from..it.to })
        assertEquals(
            "10:00:00.000" to "10:00:00.300",
            timeRangeForRows(tab, setOf(10, 13))?.let { it.fromTs to it.toTs },
        )
    }

    @Test
    fun editedTimeRangeSelectsRowsAndCarriesForwardBriefTimestamps() {
        val result = rowsForTimeRange(tab, "10:00:00.000", "10:00:00.250")
        assertNull(result.error)
        assertEquals(listOf(10, 11, 12), result.selectedIds)
        assertNotNull(result.range)
    }

    @Test
    fun invalidTimeRangeIsRejectedWithoutChangingSelection() {
        val result = rowsForTimeRange(tab, "not-a-time", "10:00:00.250")
        assertNull(result.range)
        assertEquals(emptyList(), result.selectedIds)
        assertNotNull(result.error)
    }
}
