package com.indagium

import com.indagium.model.AnnBlock
import com.indagium.model.AnnotationLogBlockStyle
import com.indagium.model.Annotations
import com.indagium.model.AppSettings
import com.indagium.model.LogAnalysis
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.ProcessNameMode
import com.indagium.ui.AppState
import com.indagium.ui.mkTab
import com.indagium.utils.buildAnnotationsHtml
import com.indagium.utils.buildFilteredCsv
import com.indagium.utils.buildFilteredTxt
import com.indagium.utils.buildMd
import com.indagium.utils.exportFilteredToFile
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CopyMetadataPresentationTest {
    private companion object {
        const val PROCESS_ID = 42
    }

    private val rows = listOf(
        LogEntry(1, "10:00:00.000", LogLevel.I, "App", "start"),
        LogEntry(2, "10:00:00.250", LogLevel.E, "App", "boom", pid = PROCESS_ID, tid = 7),
    )

    private val settings = AppSettings(
        copyPidTid = true,
        copyPidAsName = true,
        copyRowNumber = true,
        copyTimeDelta = true,
        showRowNumbers = true,
        annotationLogBlockStyle = AnnotationLogBlockStyle.INDENTED,
    )

    private fun tab() = mkTab(
        "log",
        "test.log",
        rows,
        analysis = LogAnalysis(processNames = mapOf(PROCESS_ID to "com.example.app"), pending = false),
        // Copy-as-name is an export preference, deliberately independent of the per-tab display
        // mode used by the PID cell in the viewer.
        processNameMode = ProcessNameMode.OFF,
    ).copy(
        showTimeDelta = true,
        annotations = Annotations(blocks = listOf(AnnBlock.LogRef("evidence", listOf(2), "Evidence"))),
    )

    @Test
    fun selectedCopyUsesExplicitAnchorAndTheVisibleColumnOrder() {
        val state = AppState()
        state.settings = settings
        state.tabs = listOf(tab().copy(selected = setOf(1)))

        assertEquals(
            "2  0.000  10:00:00.250  com.example.app 7  E/App  boom",
            state.selectedLinesText("log", explicitIds = setOf(2)),
        )
        assertEquals(
            "**[2  0.000  10:00:00.250  com.example.app 7] `E/App`:** boom",
            state.selectedLinesMarkdownText("log", explicitIds = setOf(2)),
        )
    }

    @Test
    fun selectedOriginalRowsUseTheirLowestExplicitIdAsDeltaAnchor() {
        val originalRows = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "filtered"),
            LogEntry(2, "10:00:00.250", LogLevel.W, "Binder", "original one", pid = PROCESS_ID, tid = 7),
            LogEntry(3, "10:00:01.000", LogLevel.E, "Binder", "original two", pid = PROCESS_ID, tid = 7),
        )
        val state = AppState()
        state.settings = settings
        state.tabs = listOf(
            mkTab(
                "log",
                "test.log",
                originalRows,
                analysis = LogAnalysis(processNames = mapOf(PROCESS_ID to "com.example.app"), pending = false),
            ).copy(showTimeDelta = true, selected = setOf(1)),
        )

        assertEquals(
            "2  0.000  10:00:00.250  com.example.app 7  W/Binder  original one\n" +
                "3  +0.750  10:00:01.000  com.example.app 7  E/Binder  original two",
            state.selectedLinesText("log", explicitIds = setOf(2, 3)),
        )
        assertEquals(
            "**[2  0.000  10:00:00.250  com.example.app 7] `W/Binder`:** original one\n" +
                "**[3  +0.750  10:00:01.000  com.example.app 7] `E/Binder`:** original two",
            state.selectedLinesMarkdownText("log", explicitIds = setOf(2, 3)),
        )
    }

    @Test
    fun annotationsAndRichPreviewUseTheSameMetadataButNoCrossSourceGuessing() {
        val current = tab()

        val markdown = buildMd(current, settings)
        val html = buildAnnotationsHtml(current, settings)

        assertTrue(markdown.contains("2  +0.250  10:00:00.250  com.example.app 7  E/App  boom"))
        assertTrue(html.contains("2  +0.250  10:00:00.250  com.example.app 7  E/App  boom"))

        val persisted = current.copy(
            annotations = Annotations(
                blocks = listOf(
                    AnnBlock.LogRef("saved", listOf(2), "Saved", sourceTabId = "other", sourceEntries = listOf(rows[1])),
                ),
            ),
        )
        val recovered = buildMd(persisted, settings)
        assertTrue(recovered.contains("42 7"))
        assertFalse(recovered.contains("com.example.app"))
        assertFalse(recovered.contains("+0.250"))
    }

    @Test
    fun filteredTextCsvAndStreamingShareEffectiveMetadataSchema() {
        val current = tab()
        val txt = buildFilteredTxt(current, settings)
        val csv = buildFilteredCsv(current, settings)
        val destination = File(createTempDirectory("openlog-copy-metadata").toFile(), "filtered.csv")

        exportFilteredToFile(current, destination, csv = true, settings = settings)

        assertEquals("row_number,time_delta,ts,pid,tid,pid_name,level,tag,msg", csv.lineSequence().first())
        assertTrue(txt.contains("2  +0.250  10:00:00.250  com.example.app 7  E/App  boom"))
        assertTrue(csv.contains("2,+0.250,10:00:00.250,42,7,com.example.app,E,App,boom"))
        assertEquals(csv, destination.readText())
    }

    @Test
    fun unavailableColumnsAndMissingPidAreOmittedOrBlankAsAppropriate() {
        val withoutColumns = tab().copy(showTimeDelta = false)
        val noColumnsSettings = settings.copy(showRowNumbers = false)
        val txt = buildFilteredTxt(withoutColumns, noColumnsSettings)
        val csv = buildFilteredCsv(withoutColumns, noColumnsSettings)

        assertFalse(txt.contains("+0.250"))
        assertFalse(txt.lineSequence().first().startsWith("1  "))
        assertTrue(csv.lineSequence().first().startsWith("ts,pid,tid,pid_name"))
        // The first log line has no PID/TID; CSV keeps its optional schema aligned using blanks.
        assertTrue(csv.contains("10:00:00.000,,,,I,App,start"))
    }
}
