package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogLevel
import com.indagium.ui.buildLogLineRender
import com.indagium.ui.dltColumnHeader
import com.indagium.ui.mkTab
import com.indagium.utils.RegexEvaluationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A DLT row's session sits in the pid field; it has no thread ID, so that field stays blank instead of reading 0. */
class DltRowPresentationTest {
    private fun rendered(entry: LogEntry) = buildLogLineRender(
        entry = entry,
        highlighters = emptyList(),
        tsColor = Color.Gray,
        pidColor = Color.Gray,
        tagColor = Color.DarkGray,
        msgColor = Color.Black,
        keywordRegexFilter = null,
        regexContext = RegexEvaluationContext(),
    ).text.text

    @Test
    fun aDltRowLeavesTheThreadFieldBlank() {
        val dlt = LogEntry(1, "12:00:00.500", LogLevel.I, "ECU1/APP1/WRK1", "Request accepted", pid = 4100, tid = 0, dltEcuId = "ECU1")
        assertEquals("12:00:00.500   4100" + " ".repeat(8) + "I  ECU1/APP1/WRK1: Request accepted", rendered(dlt))
    }

    @Test
    fun aLogcatRowStillShowsItsThread() {
        val logcat = LogEntry(1, "12:00:00.500", LogLevel.I, "App", "hi", pid = 4100, tid = 4200)
        assertEquals("12:00:00.500   4100  4200  I  App: hi", rendered(logcat))
    }

    @Test
    fun theDltHeaderPutsEachLabelOverItsField() {
        val rows = listOf(
            LogEntry(1, "12:00:00.500", LogLevel.I, "ECU1/APP1/WRK1", "Request accepted", pid = 4100, tid = 0, dltEcuId = "ECU1"),
            LogEntry(2, "12:00:00.750", LogLevel.W, "ECU1/APP1/CHK1", "Retrying", pid = 4101, tid = 0, dltEcuId = "ECU1"),
        )
        val tab = mkTab("dlt", "a.dlt", rows, logFormat = LogFormat.DLT)
        val header = assertNotNull(dltColumnHeader(tab, showPidTid = true, pidFieldChars = 5))
        val row = rendered(rows[0])

        assertEquals(row.indexOf("4100") - 1, header.indexOf("SID"), "SID starts the pid field")
        assertEquals(row.indexOf(" I ") + 1, header.indexOf("L  ECU"), "L over the level letter")
        assertEquals(row.indexOf("ECU1/APP1/WRK1"), header.indexOf("ECU/APP/CTX"))
        assertEquals(row.indexOf("Request accepted"), header.indexOf("MESSAGE"))
        // A narrow tile drops the session from rows and header alike.
        val narrow = assertNotNull(dltColumnHeader(tab, showPidTid = false, pidFieldChars = 5))
        assertEquals(-1, narrow.indexOf("SID"))
        // A Logcat tab has no DLT header.
        assertNull(dltColumnHeader(mkTab("l", "l.log", rows), showPidTid = true, pidFieldChars = 5))
    }
}
