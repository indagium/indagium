package com.indagium

import com.indagium.capture.CaptureArchiveExporter
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureStatus
import com.indagium.ui.attachFinalizedCapture
import com.indagium.ui.mkTab
import com.indagium.ui.persistedSnapshot
import com.indagium.ui.tabShellFromToken
import com.indagium.ui.tabToken
import com.indagium.ui.tokenFields
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Regression coverage for LogTab.captureSourceSessionId — added so a STOPPED capture tab's strip
 * (CaptureStoppedStrip in ui/CaptureStrip.kt) can still find its recorder session and offer
 * Save ZIP/Open folder after captureSessionId is cleared. Two things must both hold:
 *  1. attachFinalizedCapture() sets it even when the session recorded no video — this is the whole
 *     reason the field exists instead of reusing attachedVideo.captureSourcePath, which stays null
 *     for a video-off capture (see the field's doc in Model.kt).
 *  2. It follows captureSessionId's own session-only convention: absent from tabToken()/
 *     persistedSnapshot(), never restored — same proof shape as CaptureSessionIdTokenTest.
 */
class CaptureSourceSessionIdTest {
    private val tempDir = createTempDirectory("openlog-capture-source-session-test").toFile()

    private fun finalizedCapture(root: File, recordVideo: Boolean): com.indagium.capture.ImportedCapture {
        val session = CaptureSession(
            id = "session-under-test",
            directory = File(root, "session"),
            device = CaptureDevice("emulator-5554", "device"),
            settings = CaptureSettings(recordVideo = recordVideo, freeSpaceReserveBytes = 0),
            startedEpochMs = 1_700_000_000_000,
            elapsedMs = 3_000,
            status = CaptureStatus.STOPPED,
            videoStartElapsedMs = if (recordVideo) 0 else null,
        )
        session.logFile.parentFile.mkdirs()
        session.indexFile.parentFile.mkdirs()
        session.logFile.writeText("09-19 12:00:00.000  1  1 I Tag: row\n")
        session.indexFile.writeText("{\"byteOffset\":0,\"byteLength\":${session.logFile.length()},\"elapsedMs\":1000,\"rowOrdinal\":1}\n")
        if (recordVideo) {
            session.videoFile.parentFile.mkdirs()
            session.videoFile.writeBytes(byteArrayOf(1, 2, 3))
        }
        return CaptureArchiveExporter().finalizeSessionInPlace(session)
    }

    @Test
    fun attachFinalizedCaptureSetsSourceSessionIdEvenWithoutVideo() {
        val root = createTempDirectory("capture-attach-no-video").toFile()
        val imported = finalizedCapture(root, recordVideo = false)
        val tab = mkTab(id = "t1", filename = "Capture — device", logData = emptyList())
            .copy(captureSessionId = imported.descriptor.sessionId, tailing = true)

        val attached = attachFinalizedCapture(tab, imported, enableDoubleClickSeek = true)

        assertNull(attached.attachedVideo, "no video was recorded, so there is nothing to attach")
        assertNull(attached.captureSessionId, "recording is over; the tab is no longer live")
        assertEquals(
            imported.descriptor.sessionId,
            attached.captureSourceSessionId,
            "captureSourceSessionId must survive even when attachedVideo (the field this replaced) is null",
        )
    }

    @Test
    fun attachFinalizedCaptureSetsSourceSessionIdWithVideo() {
        val root = createTempDirectory("capture-attach-video").toFile()
        val imported = finalizedCapture(root, recordVideo = true)
        val tab = mkTab(id = "t1", filename = "Capture — device", logData = emptyList())
            .copy(captureSessionId = imported.descriptor.sessionId, tailing = true)

        val attached = attachFinalizedCapture(tab, imported, enableDoubleClickSeek = true)

        assertNotNull(attached.attachedVideo)
        assertEquals(imported.descriptor.sessionId, attached.captureSourceSessionId)
    }

    private fun tabFixture(captureSourceSessionId: String?): com.indagium.model.LogTab {
        val logFile = tempDir.resolve("app-${System.nanoTime()}.log").apply { writeText("10:00:00.000 I/Tag: hello\n") }
        return com.indagium.model.LogTab(
            id = "t1",
            filename = logFile.name,
            logData = emptyList(),
            rmap = emptyMap(),
            sourcePath = logFile.absolutePath,
            captureSourceSessionId = captureSourceSessionId,
        )
    }

    @Test
    fun captureSourceSessionIdIsNotPersisted() {
        val withSession = tabFixture("session-123")
        val withoutSession = withSession.copy(captureSourceSessionId = null)

        assertEquals(
            withoutSession.tabToken().tokenFields().size,
            withSession.tabToken().tokenFields().size,
            "captureSourceSessionId must not add a field to the tab token",
        )

        val restored = withSession.tabToken().tabShellFromToken()
        assertNull(
            restored?.tab?.captureSourceSessionId,
            "captureSourceSessionId is session-only and must never survive a restore",
        )
    }

    @Test
    fun persistedSnapshotIgnoresCaptureSourceSessionId() {
        val a = tabFixture("session-a")
        val b = a.copy(captureSourceSessionId = "session-b")

        assertEquals(
            a.persistedSnapshot(),
            b.persistedSnapshot(),
            "two tabs differing only in captureSourceSessionId must produce an equal persistedSnapshot",
        )
    }
}
