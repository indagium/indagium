package com.indagium

import androidx.compose.ui.graphics.Color
import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogLevel
import com.indagium.ui.buildLogLineRender
import com.indagium.ui.dltGridLayout
import com.indagium.ui.dltGridProjection
import com.indagium.ui.mkTab
import com.indagium.ui.tabShellFromToken
import com.indagium.ui.tabToken
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

    @Test
    fun theDltGridAlignsCompactAndSplitIdentifiers() {
        val entry = LogEntry(
            1, "12:00:00.500", LogLevel.I, "ECU1/APP1/WRK1", "Request accepted", pid = 4100,
            dltEcuId = "ECU1", dltAppId = "APP1", dltContextId = "WRK1",
        )
        val compact = dltGridLayout(split = false, hasSession = true)
        val row = dltGridProjection(entry, split = false, compact.widths).text
        assertEquals(row.indexOf("4100"), compact.header.indexOf("SID"))
        assertEquals(row.indexOf("ECU1/APP1/WRK1"), compact.header.indexOf("ECU/APID/CTID"))
        assertEquals(row.indexOf("Request accepted"), compact.header.indexOf("MESSAGE"))

        val split = dltGridLayout(split = true, hasSession = true)
        val splitRow = dltGridProjection(entry, split = true, split.widths).text
        assertEquals(splitRow.indexOf("ECU1"), split.header.indexOf("ECU"))
        assertEquals(splitRow.indexOf("APP1"), split.header.indexOf("APID"))
        assertEquals(splitRow.indexOf("WRK1"), split.header.indexOf("CTID"))
        assertEquals(splitRow.indexOf("Request accepted"), split.header.indexOf("MESSAGE"))
    }

    @Test
    fun theSplitDltColumnChoiceSurvivesTabRestore() {
        val tab = mkTab("dlt", "capture.dlt", emptyList(), logFormat = LogFormat.DLT)
            .copy(sourcePath = "/tmp/capture.dlt", dltSplitIdentifiers = true)
        val token = tab.tabToken()
        assertEquals(true, token.tabShellFromToken()?.tab?.dltSplitIdentifiers)
        assertEquals(false, token.substringBeforeLast('|').tabShellFromToken()?.tab?.dltSplitIdentifiers)
    }
}
