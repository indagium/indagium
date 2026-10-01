package com.indagium

import com.indagium.capture.CAPTURE_DESCRIPTOR_NAME
import com.indagium.capture.CaptureArchiveExporter
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureStatus
import com.indagium.capture.sessionJson
import com.indagium.ui.AppState
import com.indagium.ui.unfinalizedStoppedCaptureForLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TIMEOUT_MS = 15_000L
private const val POLL_MS = 20L

/**
 * A capture stopped by quitting the app never went through the stop-time finalization, so its tab
 * came back as a plain log with no video. Restoring such a tab must finalize the session in place and
 * attach its video, without ever redoing the work for a session that was finalized before.
 */
class CaptureRestoreAfterQuitTest {
    private fun sessionFixture(root: File, status: CaptureStatus, recordVideo: Boolean = true): CaptureSession {
        val session = CaptureSession(
            id = "20261001-181009-quit",
            directory = File(root, "captures/20261001-181009-quit"),
            device = CaptureDevice("emulator-5554", "device", "sdk gphone"),
            settings = CaptureSettings(recordVideo = recordVideo, freeSpaceReserveBytes = 0),
            startedEpochMs = 1_700_000_000_000,
            elapsedMs = 3_000,
            status = status,
            videoStartElapsedMs = if (recordVideo) 0L else null,
        )
        session.logFile.parentFile.mkdirs()
        session.indexFile.parentFile.mkdirs()
        val first = "09-19 12:00:00.000  1  1 I Tag: first row\n"
        val second = "09-19 12:00:01.000  1  1 I Tag: second row\n"
        session.logFile.writeText(first + second)
        session.indexFile.writeText(
            "{\"byteOffset\":0,\"byteLength\":${first.length},\"elapsedMs\":1000,\"rowOrdinal\":1}\n" +
                "{\"byteOffset\":${first.length},\"byteLength\":${second.length},\"elapsedMs\":2000,\"rowOrdinal\":2}\n",
        )
        if (recordVideo) {
            session.videoFile.parentFile.mkdirs()
            session.videoFile.writeBytes(byteArrayOf(1, 2, 3))
        }
        File(session.directory, "session.json").writeText(sessionJson(session))
        return session
    }

    /** Opens [log] as a plain log, saves the session, and returns the autosave file — what the app
     * leaves behind when it is quit while the (already stopped) capture's log is the open tab. */
    private fun quitWithPlainLogTab(root: File, log: File): File {
        val autosave = File(root, "autosave")
        val app = AppState(autosaveFile = autosave, autoExportNotes = false, archiveCacheDir = File(root, "cache-1"))
        try {
            val id = assertNotNull(app.openFile(log))
            runBlocking { withTimeout(TIMEOUT_MS) { while (app.isLoadInFlight(id)) delay(POLL_MS) } }
            assertNull(app.tab(id)?.attachedVideo)
            app.autosaveNow()
        } finally {
            app.close()
        }
        return autosave
    }

    private fun restore(root: File, autosave: File): AppState {
        val restored = AppState(
            autosaveFile = autosave,
            restoreOnCreate = true,
            autoExportNotes = false,
            archiveCacheDir = File(root, "cache-2"),
        )
        restored.startPendingRestoredTabLoads()
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                while (restored.tabs.isEmpty() || restored.tabs.any { restored.isLoadInFlight(it.id) }) delay(POLL_MS)
            }
        }
        return restored
    }

    @Test
    fun aStoppedButUnfinalizedCaptureComesBackWithItsVideo() {
        val root = createTempDirectory("capture-restore-after-quit").toFile()
        try {
            val session = sessionFixture(root, CaptureStatus.STOPPED)
            val autosave = quitWithPlainLogTab(root, session.logFile)
            assertFalse(File(session.directory, CAPTURE_DESCRIPTOR_NAME).exists(), "the quit left the session unfinalized")

            val restored = restore(root, autosave)
            try {
                runBlocking {
                    withTimeout(TIMEOUT_MS) { while (restored.tabs.firstOrNull()?.attachedVideo == null) delay(POLL_MS) }
                }
                val tab = restored.tabs.single()
                val video = assertNotNull(tab.attachedVideo)
                assertEquals(session.videoFile.absolutePath, (video.source as com.indagium.model.VideoSource.LocalFile).path)
                assertEquals(session.id, tab.captureSourceSessionId)
                assertNull(tab.captureSessionId)
                assertFalse(tab.tailing)
                assertEquals(2, tab.logData.size, "the restored rows are untouched")
                assertTrue(File(session.directory, CAPTURE_DESCRIPTOR_NAME).isFile, "the descriptor was published in place")
                assertTrue(restored.videoPanelVisible)
            } finally {
                restored.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun anInterruptedUnfinalizedCaptureIsAdoptedToo() {
        val root = createTempDirectory("capture-restore-interrupted").toFile()
        try {
            val session = sessionFixture(root, CaptureStatus.INTERRUPTED)
            val autosave = quitWithPlainLogTab(root, session.logFile)
            val restored = restore(root, autosave)
            try {
                runBlocking {
                    withTimeout(TIMEOUT_MS) { while (restored.tabs.firstOrNull()?.attachedVideo == null) delay(POLL_MS) }
                }
            } finally {
                restored.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aVideoOffCaptureStillLinksItsSessionForExport() {
        val root = createTempDirectory("capture-restore-no-video").toFile()
        try {
            val session = sessionFixture(root, CaptureStatus.STOPPED, recordVideo = false)
            val autosave = quitWithPlainLogTab(root, session.logFile)
            val restored = restore(root, autosave)
            try {
                runBlocking {
                    withTimeout(TIMEOUT_MS) { while (restored.tabs.firstOrNull()?.captureSourceSessionId == null) delay(POLL_MS) }
                }
                assertNull(restored.tabs.single().attachedVideo)
            } finally {
                restored.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aSessionFinalizedBeforeIsNeverReAttachedBehindTheUsersBack() {
        val root = createTempDirectory("capture-restore-already-final").toFile()
        try {
            val session = sessionFixture(root, CaptureStatus.STOPPED)
            CaptureArchiveExporter().finalizeSessionInPlace(session)
            val autosave = quitWithPlainLogTab(root, session.logFile)
            val restored = restore(root, autosave)
            try {
                Thread.sleep(1_000)
                assertNull(restored.tabs.single().attachedVideo, "a finalized capture whose video the user detached stays detached")
            } finally {
                restored.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aSessionStillMarkedRecordingIsLeftAlone() {
        val root = createTempDirectory("capture-restore-recording").toFile()
        try {
            val session = sessionFixture(root, CaptureStatus.RECORDING)
            assertNull(unfinalizedStoppedCaptureForLog(session.logFile))
            val autosave = quitWithPlainLogTab(root, session.logFile)
            val restored = restore(root, autosave)
            try {
                Thread.sleep(1_000)
                assertNull(restored.tabs.single().attachedVideo)
                assertFalse(File(session.directory, CAPTURE_DESCRIPTOR_NAME).exists())
            } finally {
                restored.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun onlyASessionsOwnLogcatLogIsRecognised() {
        val root = createTempDirectory("capture-restore-paths").toFile()
        try {
            val session = sessionFixture(root, CaptureStatus.STOPPED)
            assertEquals(session.id, unfinalizedStoppedCaptureForLog(session.logFile)?.id)
            val elsewhere = File(root, "other/logs/logcat.log").also {
                it.parentFile.mkdirs()
                it.writeText("x\n")
            }
            assertNull(unfinalizedStoppedCaptureForLog(elsewhere), "no session.json beside it")
            val renamed = File(session.directory, "logs/other.log").also { it.writeText("x\n") }
            assertNull(unfinalizedStoppedCaptureForLog(renamed))
        } finally {
            root.deleteRecursively()
        }
    }
}
