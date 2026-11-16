package com.indagium

import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.ProfileTimestampKind
import com.indagium.model.TabContentState
import com.indagium.ui.AppState
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WAIT_SECONDS = 15L

// End to end: a real growing file, AppState.startTailing, and profiles whose records span lines.
class IncrementalTailTest {
    private var dir: File? = null
    private var state: AppState? = null

    @AfterTest
    fun tearDown() {
        state?.close()
        dir?.deleteRecursively()
    }

    private fun newState(): AppState {
        val directory = createTempDirectory("incremental-tail").toFile().also { dir = it }
        return AppState(
            autosaveFile = File(directory, "state.cache"),
            restoreOnCreate = false,
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archive-cache"),
            customCommandsDir = File(directory, "commands"),
            filterBackupsDir = File(directory, "filter-backups"),
            autoExportNotes = false,
            controlTokenFile = File(directory, "control-token"),
            sourceIndexFile = File(directory, "source-index"),
            testingDir = File(directory, "testing"),
        ).also { state = it }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within ${WAIT_SECONDS}s" }
            Thread.sleep(10)
        }
    }

    private fun openWith(app: AppState, profile: LogFormatProfile, file: File): String {
        assertTrue(app.saveLogFormatProfile(profile).isSuccess)
        val tabId = requireNotNull(app.openFileWithLogFormatProfile(file, profile.id))
        waitUntil { app.tab(tabId)?.contentState == TabContentState.LOADED }
        return tabId
    }

    @Test
    fun aGrowingCsvAppendsItsRowsAndNeverTheHeaderRow() {
        val app = newState()
        val profile = LogFormatProfile(
            id = "tail-csv",
            name = "Tail CSV",
            engine = LogFormatEngine.CSV,
            fieldMappings = mapOf(
                LogFieldRole.TIMESTAMP to "time",
                LogFieldRole.SEVERITY to "level",
                LogFieldRole.MESSAGE to "message",
            ),
            timestampKind = ProfileTimestampKind.ISO_8601,
        )
        val file = File(requireNotNull(dir), "data.csv").apply {
            writeText("time,level,message\n2024-01-01T00:00:00Z,INFO,one\n2024-01-01T00:00:01Z,INFO,two\n")
        }
        val tabId = openWith(app, profile, file)
        assertEquals(listOf("one", "two"), app.tab(tabId)!!.logData.map { it.msg })

        app.startTailing(tabId)
        assertTrue(app.tab(tabId)!!.tailing, "a CSV profile can be tailed")
        try {
            // The first batch ends inside a quoted cell: no row yet, but the lines count as consumed.
            file.appendText("2024-01-01T00:00:02Z,WARN,\"split\n")
            waitUntil { app.tab(tabId)!!.profileSourceLineCount == 4L }
            assertEquals(2, app.tab(tabId)!!.logData.size, "an open quote holds its record")

            file.appendText("cell\",\n2024-01-01T00:00:03Z,ERROR,three\n")
            waitUntil { app.tab(tabId)!!.logData.size == 4 }

            val rows = app.tab(tabId)!!.logData
            assertEquals(listOf("one", "two", "split\ncell", "three"), rows.map { it.msg })
            assertEquals(listOf(1, 2, 3, 4), rows.map { it.id })
            assertEquals(listOf(2L, 3L, 4L, 6L), rows.map { it.sourceStartLine })
            assertEquals(listOf(2L, 3L, 5L, 6L), rows.map { it.sourceEndLine })
            assertTrue(rows.none { it.rawRecord?.startsWith("time,level") == true }, "the header row is never a row")
            assertEquals(6L, app.tab(tabId)!!.profileSourceLineCount)
        } finally {
            app.stopTailing(tabId)
        }
    }

    @Test
    fun stoppingTheTailFlushesAHeldMultilineRecord() {
        val app = newState()
        val profile = LogFormatProfile(
            id = "tail-regex",
            name = "Tail regex",
            engine = LogFormatEngine.REGEX,
            regexPattern = "(?s)^\\[(\\S+)] ([A-Z]+) (.*)$",
            multilineStartPattern = "^\\[",
            fieldMappings = mapOf(
                LogFieldRole.TIMESTAMP to "1",
                LogFieldRole.SEVERITY to "2",
                LogFieldRole.MESSAGE to "3",
            ),
            timestampKind = ProfileTimestampKind.ISO_8601,
        )
        val file = File(requireNotNull(dir), "app.log").apply {
            writeText("[2024-03-01T10:00:00Z] ERROR first\n\tat a.B(B.java:1)\n[2024-03-01T10:00:01Z] INFO second\n")
        }
        val tabId = openWith(app, profile, file)
        assertEquals(listOf("first\n\tat a.B(B.java:1)", "second"), app.tab(tabId)!!.logData.map { it.msg })

        app.startTailing(tabId)
        assertTrue(app.tab(tabId)!!.tailing, "a multiline regex profile can be tailed")
        file.appendText("[2024-03-01T10:00:02Z] WARN third\n\tat c.D(D.java:2)\n")
        waitUntil { app.tab(tabId)!!.profileSourceLineCount == 5L }
        assertEquals(2, app.tab(tabId)!!.logData.size, "the last record may still grow, so it is held")

        file.appendText("[2024-03-01T10:00:03Z] INFO fourth\n")
        waitUntil { app.tab(tabId)!!.logData.size == 3 }
        assertEquals("third\n\tat c.D(D.java:2)", app.tab(tabId)!!.logData.last().msg)

        app.stopTailing(tabId)
        waitUntil { app.tab(tabId)!!.logData.size == 4 }
        val last = app.tab(tabId)!!.logData.last()
        assertEquals("fourth", last.msg)
        assertEquals(4, last.id)
        assertEquals(6L, last.sourceStartLine)
        assertEquals(6L, app.tab(tabId)!!.profileSourceLineCount)
        assertEquals(false, app.tab(tabId)!!.tailing)
    }

    @Test
    fun pausingAndResumingKeepsTheHeldRecord() {
        val app = newState()
        val profile = LogFormatProfile(
            id = "tail-regex-pause",
            name = "Tail regex pause",
            engine = LogFormatEngine.REGEX,
            regexPattern = "(?s)^\\[(\\S+)] ([A-Z]+) (.*)$",
            multilineStartPattern = "^\\[",
            fieldMappings = mapOf(LogFieldRole.SEVERITY to "2", LogFieldRole.MESSAGE to "3"),
        )
        val file = File(requireNotNull(dir), "pause.log").apply { writeText("[t0] INFO zero\n") }
        val tabId = openWith(app, profile, file)

        app.startTailing(tabId)
        file.appendText("[t1] INFO one\n\tat held\n")
        waitUntil { app.tab(tabId)!!.profileSourceLineCount == 3L }
        assertTrue(app.pauseTailing(tabId))

        file.appendText("\tat still held\n[t2] INFO two\n")
        assertTrue(app.resumeTailing(tabId))
        waitUntil { app.tab(tabId)!!.logData.size == 2 }
        assertEquals("one\n\tat held\n\tat still held", app.tab(tabId)!!.logData.last().msg)
        app.stopTailing(tabId)
        waitUntil { app.tab(tabId)!!.logData.size == 3 }
        assertEquals("two", app.tab(tabId)!!.logData.last().msg)
    }
}
