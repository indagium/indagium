package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.buildLogLineRender
import com.indagium.utils.RegexEvaluationContext
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
