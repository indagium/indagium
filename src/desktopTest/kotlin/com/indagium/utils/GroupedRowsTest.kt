package com.indagium.utils

import androidx.compose.ui.graphics.Color
import com.indagium.model.LogEntry
import com.indagium.model.LogItem
import com.indagium.model.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class GroupedRowsTest {
    @Test
    fun rowsKeepStylesAcrossHeadersAndAreCreatedOnAccess() {
        val entries = (1..4).map { LogEntry(it, "", LogLevel.I, "tag", "message") }
        val header = LogItem.StackTraceHeader(entries[0], "stack", 0, true, 1)
        val builder = GroupedRows.Builder()
        builder.header(header)
        builder.row(entries[0], 0, null, null)
        builder.row(entries[1], 1, Color.Red, Color.Red)
        builder.header(header)
        builder.row(entries[2], 1, Color.Red, Color.Red)
        builder.row(entries[3], 0, null, null)
        val rows = builder.finish()
        assertEquals(
            listOf(
                header, LogItem.Row(entries[0], 0), LogItem.Row(entries[1], 1, Color.Red, Color.Red),
                header, LogItem.Row(entries[2], 1, Color.Red, Color.Red), LogItem.Row(entries[3], 0),
            ),
            rows,
        )
        assertNotSame(rows[1], rows[1])
    }
}
