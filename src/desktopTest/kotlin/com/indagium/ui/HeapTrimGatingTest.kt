package com.indagium.ui

import com.indagium.capture.FakeCaptureRunner
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/** A heap trim is a full GC pause, so [AppState.requestHeapTrim] must skip it while a capture records. */
class HeapTrimGatingTest {
    @Test
    fun trimIsSkippedWhileACaptureControllerIsRegistered() {
        val root = createTempDirectory("heap-trim-gating").toFile()
        val controller = TabCaptureController(root, runner = FakeCaptureRunner())
        val app = AppState(autosaveFile = Files.createTempFile("heap-trim-gating-autosave", "").toFile(), autoExportNotes = false)
        try {
            val reasons = mutableListOf<String>()
            app.heapTrimRequester = { reasons += it }

            app.requestHeapTrim("idle")
            assertEquals(listOf("idle"), reasons)

            app.registerCaptureControllerForTest("t1", controller)
            app.requestHeapTrim("recording")
            assertEquals(listOf("idle"), reasons, "no trim while a capture is live")
        } finally {
            controller.close()
            root.deleteRecursively()
        }
    }
}
