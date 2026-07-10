package com.openlog

import com.openlog.model.Filter
import com.openlog.model.FilterMode
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.model.LogTab
import com.openlog.utils.buildFilteredCsv
import com.openlog.utils.buildFilteredTxt
import com.openlog.utils.exportFilteredToFile
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportFilteredLogTest {
    private fun tab(logs: List<LogEntry>, filter: Filter = Filter()) = LogTab(
        id = "t1", filename = "test.log", logData = logs, rmap = logs.associateBy { it.id }, filter = filter,
    )

    @Test
    fun txtExportOnlyIncludesEntriesPassingTheFilter() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "keep me"),
            LogEntry(2, "10:00:00.100", LogLevel.D, "App", "drop me"),
        )
        val filtered = tab(logs, Filter(levels = setOf(LogLevel.I)))

        val txt = buildFilteredTxt(filtered)

        assertTrue(txt.contains("keep me"))
        assertFalse(txt.contains("drop me"))
    }

    @Test
    fun txtExportUsesTsLevelTagMsgFormat() {
        val logs = listOf(LogEntry(1, "10:00:00.000", LogLevel.E, "MyTag", "boom"))
        val txt = buildFilteredTxt(tab(logs))

        assertEquals("10:00:00.000  E/MyTag  boom", txt.trim())
    }

    @Test
    fun txtExportIgnoresCollapsedGroupUiStateAndIncludesAllFilteredLines() {
        // Stack-trace folding always collapses this pair into one header in computeItems(), but
        // export must still emit every filtered line — collapsed headers are a viewing
        // convenience, not a filter.
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100),
        )
        val txt = buildFilteredTxt(tab(logs))

        assertTrue(txt.contains("FATAL EXCEPTION: main"))
        assertTrue(txt.contains("at com.app.Main.onCreate(Main.java:10)"))
    }

    @Test
    fun csvExportHasHeaderRowAndOneRowPerEntry() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello", pid = 10, tid = 20),
        )
        val csv = buildFilteredCsv(tab(logs))
        val lines = csv.trim().lines()

        assertEquals("ts,level,tag,pid,tid,msg", lines[0])
        assertEquals("10:00:00.000,I,App,10,20,hello", lines[1])
    }

    @Test
    fun csvExportRespectsTheActiveFilter() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "keep"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "Other", "drop"),
        )
        val filtered = tab(logs, Filter(mode = FilterMode.TAGS, activeTags = setOf("App")))
        val csv = buildFilteredCsv(filtered)

        assertTrue(csv.contains("keep"))
        assertFalse(csv.contains("drop"))
    }

    @Test
    fun csvExportQuotesFieldsContainingCommasQuotesOrNewlines() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "has, a comma"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "App", "has \"quotes\""),
            LogEntry(3, "10:00:00.200", LogLevel.I, "App", "has\na newline"),
        )
        val csv = buildFilteredCsv(tab(logs))
        val lines = csv.trim().lines()

        assertTrue(lines.any { it.contains("\"has, a comma\"") })
        assertTrue(lines.any { it.contains("\"has \"\"quotes\"\"\"") })
        // The embedded newline splits the quoted field across two physical lines, so check the
        // joined content rather than a single `lines()` entry.
        assertTrue(csv.contains("\"has\na newline\""))
    }

    // ── Streaming export parity (P-03) ──────────────────────────────────
    // exportFilteredToFile must never diverge from buildFilteredTxt/buildFilteredCsv — those
    // string builders are the oracle every parity case below is checked against.

    private fun exportToTempFile(t: LogTab, csv: Boolean): String {
        val dest = File(createTempDirectory("openlog-export-parity").toFile(), "out")
        exportFilteredToFile(t, dest, csv)
        return dest.readText()
    }

    @Test
    fun streamedTxtExportMatchesBuiltStringForEmptyLog() {
        val t = tab(emptyList())
        assertEquals(buildFilteredTxt(t), exportToTempFile(t, csv = false))
    }

    @Test
    fun streamedTxtExportMatchesBuiltStringForASmallLog() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello"),
            LogEntry(2, "10:00:00.100", LogLevel.E, "App", "boom"),
        )
        val t = tab(logs)
        assertEquals(buildFilteredTxt(t), exportToTempFile(t, csv = false))
    }

    @Test
    fun streamedTxtExportMatchesBuiltStringForUnicodeContent() {
        val logs = listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "héllo wörld 日本語 🎉"))
        val t = tab(logs)
        assertEquals(buildFilteredTxt(t), exportToTempFile(t, csv = false))
    }

    @Test
    fun streamedTxtExportMatchesBuiltStringForMultilineRawContent() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main"),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)"),
        )
        val t = tab(logs)
        assertEquals(buildFilteredTxt(t), exportToTempFile(t, csv = false))
    }

    @Test
    fun streamedTxtExportMatchesBuiltStringWhenFiltered() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "keep me"),
            LogEntry(2, "10:00:00.100", LogLevel.D, "App", "drop me"),
        )
        val t = tab(logs, Filter(levels = setOf(LogLevel.I)))
        assertEquals(buildFilteredTxt(t), exportToTempFile(t, csv = false))
    }

    @Test
    fun streamedCsvExportMatchesBuiltStringForEmptyLog() {
        val t = tab(emptyList())
        assertEquals(buildFilteredCsv(t), exportToTempFile(t, csv = true))
    }

    @Test
    fun streamedCsvExportMatchesBuiltStringForQuotedFields() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "has, a comma"),
            LogEntry(2, "10:00:00.100", LogLevel.I, "App", "has \"quotes\""),
            LogEntry(3, "10:00:00.200", LogLevel.I, "App", "has\na newline"),
        )
        val t = tab(logs)
        assertEquals(buildFilteredCsv(t), exportToTempFile(t, csv = true))
    }

    @Test
    fun streamedExportEndsWithATrailingNewlineJustLikeTheBuiltString() {
        val logs = listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello"))
        val t = tab(logs)
        val built = buildFilteredTxt(t)
        val streamed = exportToTempFile(t, csv = false)
        assertTrue(built.endsWith("\n"))
        assertTrue(streamed.endsWith("\n"))
        assertEquals(built, streamed)
    }
}
