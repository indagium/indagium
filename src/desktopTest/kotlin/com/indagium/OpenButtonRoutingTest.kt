package com.indagium

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.ui.AppState
import com.indagium.ui.mkTab
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// AppState.openPathOrShowError's routing, exercised end to end — the function the toolbar's Open
// button (ui/TabBar.kt) calls after java.awt.FileDialog.LOAD returns a picked file. Order matters
// inside openPathOrShowError (directory, then video, then Unsupported archive, then the generic
// isOpenableAsLog error, then openPath) — each test here pins one branch so a later reorder can't
// silently regress a wrong-error-message bug the same way pre-Part-4 code had for videos.
class OpenButtonRoutingTest {
    private fun stateWithActiveTab(dir: File): AppState = AppState(File(dir, "state.cache")).also { state ->
        state.tabs = listOf(
            mkTab("existing", "existing.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "Old", "open"))),
        )
        state.activeTabId = "existing"
    }

    @Test
    fun videoWithAnActiveTabAttachesAndShowsThePanel() {
        val dir = createTempDirectory("openlog-openbutton-video").toFile()
        val state = stateWithActiveTab(dir)
        state.videoPanelVisible = false
        val video = File(dir, "recording.mp4").apply { writeText("not decoded by this test") }

        state.openPathOrShowError(video)

        assertEquals(video.absolutePath, state.tab("existing")?.attachedVideo?.path)
        assertTrue(state.videoPanelVisible)
        assertNull(state.openError)
    }

    @Test
    fun videoWithNoTabsSetsCouldNotAttachVideoError() {
        val dir = createTempDirectory("openlog-openbutton-video-no-tabs").toFile()
        val state = AppState(File(dir, "state.cache"))
        val video = File(dir, "recording.mp4").apply { writeText("not decoded by this test") }

        state.openPathOrShowError(video)

        assertEquals("Could not attach video", state.openError?.title)
        assertTrue(state.tabs.isEmpty())
    }

    @Test
    fun rarArchiveReportsUnsupportedArchiveFormatNamingRar() {
        val dir = createTempDirectory("openlog-openbutton-rar").toFile()
        val state = AppState(File(dir, "state.cache"))
        // "Rar!" magic — RAR's actual signature, matched by content (not extension) the same way
        // every other format in this app is — see ArchiveFormat.kt's UNSUPPORTED_MAGIC_TABLE.
        val rar = File(dir, "archive.rar").apply {
            writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00))
        }

        state.openPathOrShowError(rar)

        assertEquals("Unsupported archive format", state.openError?.title)
        assertTrue(state.openError?.message?.contains("RAR") == true, "expected the error to name RAR: ${state.openError?.message}")
        assertTrue(state.tabs.isEmpty())
    }

    @Test
    fun binaryJunkReportsAGenericErrorThatMentionsTarAndLogGz() {
        val dir = createTempDirectory("openlog-openbutton-binary").toFile()
        val state = AppState(File(dir, "state.cache"))
        val junk = File(dir, "payload.bin").apply {
            writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5, 0, 6, 7, 0))
        }

        state.openPathOrShowError(junk)

        assertEquals("Could not open file", state.openError?.title)
        val message = state.openError?.message.orEmpty()
        assertTrue(".tar" in message, "expected the generic error to mention .tar: $message")
        assertTrue(".log.gz" in message, "expected the generic error to mention .log.gz: $message")
        assertTrue(state.tabs.isEmpty())
    }

    @Test
    fun aDirectoryRoutesToTheFolderPickerRatherThanTheGenericError() {
        val dir = createTempDirectory("openlog-openbutton-folder").toFile()
        val folder = File(dir, "unpacked").apply { mkdir() }
        File(folder, "first.log").writeText("06-26 10:00:00.000  1  1 I First: loaded\n")
        File(folder, "second.log").writeText("06-26 10:00:01.000  1  1 I Second: loaded\n")
        val state = AppState(File(dir, "state.cache"))

        state.openPathOrShowError(folder)

        waitUntil { state.pendingFolderPicker != null }
        assertNull(state.openError)
        assertEquals(listOf("first.log", "second.log"), state.pendingFolderPicker?.candidates?.map { it.entryPath })
    }

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition(), "condition not met within ${timeoutMs}ms")
    }
}
