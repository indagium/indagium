package com.indagium

import com.indagium.capture.CaptureArchiveExporter
import com.indagium.capture.CaptureArchiveReader
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExportFormat
import com.indagium.capture.CaptureExportRequest
import com.indagium.capture.CaptureRange
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureStatus
import com.indagium.capture.captureSettingsToJson
import com.indagium.ui.AppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WAIT_MS = 10_000L
private const val POLL_MS = 20L
private const val FIXTURE_EPOCH_MS = 1_700_000_000_000L
private const val ROW_STEP_MS = 1_000L

/**
 * Save ZIP / Save Compact on a tab opened from an archive must export THAT archive. They used to go through
 * `saveRetainedCapture(descriptor.sessionId)`, which only searches the local capture roots: on a recipient's machine the
 * sender's session id is not there at all, and on the sender's machine it exports the local original instead.
 */
@Suppress("MagicNumber")
class CaptureSaveOpenedArchiveTest {
    @Test
    fun saveZipOnAnOpenedArchiveWhoseSessionIsNotOnDiskExportsTheArchive() = runBlocking {
        val root = createTempDirectory("save-opened-ghost").toFile()
        try {
            val archive = exportArchive(root, "ghost-session", rows = 3)
            val app = appState(root)
            try {
                val tabId = app.openCaptureFile(archive)
                waitLoaded(app, tabId)
                val sourceId = assertNotNull(app.tab(tabId)?.captureSourceSessionId)
                assertTrue(app.isImportedCaptureSnapshot(tabId))

                app.saveRetainedCapture(sourceId, tabId, CaptureExportFormat.ZIP)
                awaitIdle(app)

                assertNull(app.captureExportError, "an opened archive must not depend on its session being on this machine")
                val saved = assertNotNull(app.captureExportResult).file
                assertTrue(saved.isFile)
                assertEquals(logLines(archive, root, "a"), logLines(saved, root, "b"), "the saved ZIP equals the opened archive")
            } finally {
                app.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun saveZipOnAnOpenedRangeArchiveExportsItsRowsNotTheLocalOriginalSession() = runBlocking {
        val root = createTempDirectory("save-opened-local-original").toFile()
        try {
            // The opened archive holds two rows; a DIFFERENT local session with the same id holds three.
            val archive = exportArchive(root, "shared-session", rows = 2)
            writeLocalOriginal(root, "shared-session", rows = 3)
            val app = appState(root)
            try {
                val tabId = app.openCaptureFile(archive)
                waitLoaded(app, tabId)
                val sourceId = assertNotNull(app.tab(tabId)?.captureSourceSessionId)

                app.saveRetainedCapture(sourceId, tabId, CaptureExportFormat.ZIP)
                awaitIdle(app)

                assertNull(app.captureExportError)
                val saved = assertNotNull(app.captureExportResult).file
                assertEquals(2, logLines(saved, root, "saved").size, "the archive's rows, not the original session's")
                assertEquals(logLines(archive, root, "orig"), logLines(saved, root, "saved2"))
            } finally {
                app.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun appState(root: File) = AppState(
        autosaveFile = File(root, "autosave"),
        notesDir = File(root, "notes"),
        archiveCacheDir = File(root, "cache"),
        autoExportNotes = false,
        platformDefaultSaveRootDir = File(root, "save-root"),
    )

    private fun logLines(archive: File, root: File, tag: String): List<String> =
        CaptureArchiveReader.open(archive, File(root, "read-$tag")).use { it.logFile.readLines() }

    private fun exportArchive(root: File, sessionId: String, rows: Int): File {
        val session = session(File(root, "origin-$sessionId"), sessionId, rows)
        writeSessionFiles(session, rows)
        return File(root, "$sessionId.zip").also { destination ->
            CaptureArchiveExporter().export(
                session,
                CaptureExportRequest(destination, CaptureRange.ALL, includeVideo = false, cutoffElapsedMs = session.elapsedMs),
            )
        }
    }

    /** A stopped session in the app's legacy capture root (`<autosave dir>/captures`), as CaptureService lists it. */
    private fun writeLocalOriginal(root: File, sessionId: String, rows: Int) {
        val session = session(File(root, "captures/$sessionId"), sessionId, rows)
        writeSessionFiles(session, rows)
        File(session.directory, "session.json").writeText(
            buildJsonObject {
                put("formatVersion", 1)
                put("id", session.id)
                put("device", buildJsonObject {
                    put("serial", session.device.serial)
                    put("state", session.device.state)
                    put("model", session.device.model)
                    put("emulator", session.device.emulator)
                })
                put("settings", Json.parseToJsonElement(captureSettingsToJson(session.settings)))
                put("startedEpochMs", session.startedEpochMs)
                put("elapsedMs", session.elapsedMs)
                put("status", session.status.name)
            }.toString(),
        )
    }

    private fun session(directory: File, id: String, rows: Int) = CaptureSession(
        id = id,
        directory = directory,
        device = CaptureDevice("emulator-5554", "device"),
        settings = CaptureSettings(recordVideo = false, freeSpaceReserveBytes = 0),
        startedEpochMs = FIXTURE_EPOCH_MS,
        elapsedMs = rows * ROW_STEP_MS,
        status = CaptureStatus.STOPPED,
    )

    private fun writeSessionFiles(session: CaptureSession, rows: Int) {
        session.logFile.parentFile.mkdirs()
        session.indexFile.parentFile.mkdirs()
        var offset = 0L
        session.logFile.outputStream().use { log ->
            session.indexFile.bufferedWriter().use { index ->
                for (i in 1..rows) {
                    val bytes = "09-19 12:00:0${i - 1}.000  100  101 I Tag: row $i\n".toByteArray()
                    log.write(bytes)
                    index.appendLine(
                        "{\"byteOffset\":$offset,\"byteLength\":${bytes.size},\"elapsedMs\":${i * ROW_STEP_MS},\"rowOrdinal\":$i}",
                    )
                    offset += bytes.size
                }
            }
        }
    }

    private suspend fun waitLoaded(app: AppState, id: String) {
        withTimeout(WAIT_MS) { while (app.isLoadInFlight(id) || app.captureSnapshotSessionFor(id) == null) delay(POLL_MS) }
        assertNotNull(app.tab(id), app.openError?.message)
    }

    private suspend fun awaitIdle(app: AppState) {
        withTimeout(WAIT_MS) {
            while (app.captureExportBusy || (app.captureExportResult == null && app.captureExportError == null)) delay(POLL_MS)
        }
    }
}
