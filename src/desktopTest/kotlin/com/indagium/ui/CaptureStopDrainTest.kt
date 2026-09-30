@file:Suppress("MagicNumber")

package com.indagium.ui

import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExecutable
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureTools
import com.indagium.capture.FakeCaptureRunner
import com.indagium.capture.StreamingFakeProcess
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * [AppState.stopCaptureTab] must stop the recorder BEFORE the final tailer drain: the recorder keeps
 * writing and indexing whatever adb emits while it terminates (including a last line with no
 * trailing newline), and a drain that ran first never put those rows into the tab.
 */
class CaptureStopDrainTest {
    @org.junit.Test(timeout = 30_000)
    fun rowsWrittenWhileTheRecorderStopsLandInTheTabIncludingAnUnterminatedFinalLine() {
        val root = createTempDirectory("capture-stop-drain").toFile()
        val runner = FakeCaptureRunner()
        runner.enqueue(
            StreamingFakeProcess(
                emitOnTerminate = "01-02 03:04:05.006  100  101 I Tag: written during stop\n" +
                    "01-02 03:04:05.007  100  101 I Tag: no trailing newline",
            ),
        )
        val controller = TabCaptureController(root, runner = runner)
        val app = AppState(autosaveFile = Files.createTempFile("capture-stop-drain-autosave", "").toFile(), autoExportNotes = false)
        try {
            val session = controller.start(
                CaptureDevice("SERIAL", "device", "Pixel"),
                CaptureSettings(recordVideo = false, freeSpaceReserveBytes = 0),
                CaptureTools(CaptureExecutable("adb"), null, runner),
            ) {}
            // The recorder's log file is empty here, so the tab and its tailer both start at offset 0.
            val tabId = checkNotNull(app.openFile(session.logFile))
            awaitCondition { !app.isLoading }
            app.registerCaptureControllerForTest(tabId, controller)
            app.startTailing(tabId)

            app.stopCaptureTab(tabId)
            awaitCondition { app.captureFinalizationStatus(tabId) != CAPTURE_FINALIZING_STATUS }

            val messages = app.tab(tabId)!!.logData.map { it.msg }
            assertEquals(2, messages.size, "both rows written during stop must be in the tab: $messages")
            assertEquals("no trailing newline", messages.last())
            assertFalse(app.tab(tabId)!!.tailing)
        } finally {
            controller.close()
            root.deleteRecursively()
        }
    }

    private fun awaitCondition(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(10)
        }
    }
}
