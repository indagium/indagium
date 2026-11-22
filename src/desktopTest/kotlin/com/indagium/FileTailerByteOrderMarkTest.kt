package com.indagium

import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.LogLevel
import com.indagium.model.ProfileTimestampKind
import com.indagium.model.TabContentState
import com.indagium.ui.AppState
import com.indagium.utils.FileTailer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val BOM_WAIT_SECONDS = 15L
private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

/**
 * A file that starts with a UTF-8 byte order mark: the reader that opens it drops the mark, and a tail that reads it again from
 * byte 0 (the first read, a rotation, a restart) must too. Offsets stay byte-exact either way.
 */
class FileTailerByteOrderMarkTest {
    private var dir: File? = null
    private var state: AppState? = null

    @AfterTest
    fun tearDown() {
        state?.close()
        dir?.deleteRecursively()
    }

    private fun tempDir(): File = createTempDirectory("tail-bom").toFile().also { dir = it }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOM_WAIT_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within ${BOM_WAIT_SECONDS}s" }
            Thread.sleep(10)
        }
    }

    private fun File.writeBom(text: String) = writeBytes(UTF8_BOM + text.toByteArray(Charsets.UTF_8))

    @Test
    fun aRotatedBomFileEmitsItsFirstLineWithoutTheMark() {
        val file = File(tempDir(), "out.log").apply { writeBom("first session\n") }
        val received = CopyOnWriteArrayList<String>()
        val tailer = FileTailer(file, onNewLines = { received.addAll(it) }, pollIntervalMs = 50)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = tailer.start(scope)
        try {
            file.appendText("more\n")
            waitUntil { received.isNotEmpty() }
            received.clear()

            // Rotated to a new file that starts with the mark again.
            file.writeBom("second\nmore\n")
            waitUntil { received.size >= 2 }

            assertEquals(listOf("second", "more"), received.toList())
            assertEquals(file.length(), tailer.currentOffset, "offsets stay byte exact: the mark's three bytes are counted")
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun aTailReplayedFromByteZeroOfABomFileDropsTheMarkOfItsFirstLine() {
        val file = File(tempDir(), "out.log").apply { writeBom("alpha\nbeta\n") }
        val received = CopyOnWriteArrayList<String>()
        val tailer = FileTailer(file, onNewLines = { received.addAll(it) }, pollIntervalMs = 50, startOffset = 0L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = tailer.start(scope)
        try {
            waitUntil { received.size >= 2 }
            assertEquals(listOf("alpha", "beta"), received.toList())
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun aMarkInTheMiddleOfAFileIsNotTouched() {
        val file = File(tempDir(), "out.log").apply { writeText("one\n") }
        val received = CopyOnWriteArrayList<String>()
        val tailer = FileTailer(file, onNewLines = { received.addAll(it) }, pollIntervalMs = 50)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = tailer.start(scope)
        try {
            file.appendText("﻿two\n")
            waitUntil { received.isNotEmpty() }
            assertEquals(listOf("﻿two"), received.toList(), "only a read that starts at byte 0 meets a byte order mark")
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    private fun newState(): AppState {
        val directory = tempDir()
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

    @Test
    fun aCsvRotatedToANewBomFileRecognisesItsHeaderAndReadsItsFirstDataRow() {
        val app = newState()
        val profile = LogFormatProfile(
            id = "tail-csv-bom",
            name = "Tail CSV BOM",
            engine = LogFormatEngine.CSV,
            fieldMappings = mapOf(
                LogFieldRole.TIMESTAMP to "time",
                LogFieldRole.SEVERITY to "level",
                LogFieldRole.MESSAGE to "message",
            ),
            timestampKind = ProfileTimestampKind.ISO_8601,
        )
        val file = File(requireNotNull(dir), "bom.csv").apply {
            writeBom("time,level,message\n2024-01-01T00:00:00Z,INFO,one\n2024-01-01T00:00:01Z,INFO,two\n")
        }
        assertTrue(app.saveLogFormatProfile(profile).isSuccess)
        val tabId = requireNotNull(app.openFileWithLogFormatProfile(file, profile.id))
        waitUntil { app.tab(tabId)?.contentState == TabContentState.LOADED }
        assertEquals(listOf("one", "two"), app.tab(tabId)!!.logData.map { it.msg }, "fixture: the open reads past the mark")

        app.startTailing(tabId)
        assertTrue(app.tab(tabId)!!.tailing)
        try {
            file.writeBom("time,level,message\n2024-02-02T00:00:00Z,WARN,fresh\n")
            waitUntil { app.tab(tabId)!!.logData.size == 3 }
            // A header taken as data would be a fourth row; give it a moment to show up.
            Thread.sleep(300)

            val rows = app.tab(tabId)!!.logData
            assertEquals(listOf("one", "two", "fresh"), rows.map { it.msg })
            assertEquals(LogLevel.W, rows.last().level, "the first data row is a data row: its columns are mapped by the header")
            assertTrue(rows.last().ts.isNotBlank(), "the header's first column is 'time', not a mark and 'time': the timestamp is read")
            assertEquals(2L, rows.last().sourceStartLine, "the new file's lines are numbered from 1")
        } finally {
            app.stopTailing(tabId)
        }
    }

    @Test
    fun aLogcatFileRotatedToANewBomFileParsesItsFirstLineInsteadOfShowingItRaw() {
        val app = newState()
        val file = File(requireNotNull(dir), "bom.log").apply { writeBom("06-26 10:00:00.000  123  456 I App: first session of the log\n") }
        val tabId = requireNotNull(app.openFile(file))
        waitUntil { app.tab(tabId)?.contentState == TabContentState.LOADED }
        app.startTailing(tabId)
        assertTrue(app.tab(tabId)!!.tailing)
        try {
            file.writeBom("06-26 11:00:00.000  123  456 W App: rotated\n")
            waitUntil { app.tab(tabId)!!.logData.size == 2 }

            val rotated = app.tab(tabId)!!.logData[1]
            assertEquals("rotated", rotated.msg)
            assertEquals(LogLevel.W, rotated.level, "the first line of the new file is a Logcat row, not RAW")
            assertEquals("App", rotated.tag)
        } finally {
            app.stopTailing(tabId)
        }
    }
}
