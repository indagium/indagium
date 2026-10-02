package com.indagium.ui

import com.indagium.capture.FakeCaptureRunner
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A heap trim is a full GC pause, so [AppState.requestHeapTrim] must skip it while a capture records,
 * and closing small tabs only requests one once their rows add up past the threshold (50_000).
 */
class HeapTrimGatingTest {
    private fun rows(count: Int) = (1..count).map { LogEntry(it, "10:00:00.000", LogLevel.I, "Tag", "msg $it") }

    private fun newState() = AppState(autosaveFile = Files.createTempFile("heap-trim-gating-autosave", "").toFile(), autoExportNotes = false)

    /** Opens and closes a plain (non-capture) tab of [count] rows. */
    private fun AppState.openAndClose(id: String, count: Int) {
        tabs = tabs + mkTab(id, "$id.log", rows(count))
        closeTab(id)
    }

    @Test
    fun smallClosesAccumulateAndTheCrossingCloseTrimsOnceThenResets() {
        val app = newState()
        val reasons = mutableListOf<String>()
        app.heapTrimRequester = { reasons += it }

        app.openAndClose("a", 20_000)
        app.openAndClose("b", 20_000)
        assertEquals(emptyList(), reasons, "40_000 closed rows are below the threshold")

        app.openAndClose("c", 20_000)
        assertEquals(listOf("closed large or capture tab"), reasons, "the close crossing 50_000 trims exactly once")

        app.openAndClose("d", 20_000)
        assertEquals(1, reasons.size, "the trim reset the tally, so the next small close does not trim")
    }

    @Test
    fun aTrimForAnotherReasonResetsTheTally() {
        val app = newState()
        val reasons = mutableListOf<String>()
        app.heapTrimRequester = { reasons += it }

        app.openAndClose("a", 40_000)
        app.requestHeapTrim("capture finalized")
        assertEquals(listOf("capture finalized"), reasons)

        // 40_000 + 40_000 would have crossed 50_000 had the tally not been reset.
        app.openAndClose("b", 40_000)
        assertEquals(listOf("capture finalized"), reasons)
    }

    @Test
    fun aTrimSkippedForALiveCaptureDoesNotResetTheTally() {
        val root = createTempDirectory("heap-trim-gating-tally").toFile()
        val controller = TabCaptureController(root, runner = FakeCaptureRunner())
        val app = newState()
        try {
            val reasons = mutableListOf<String>()
            app.heapTrimRequester = { reasons += it }

            app.openAndClose("a", 40_000)
            app.registerCaptureControllerForTest("t1", controller)
            app.requestHeapTrim("recording")
            assertEquals(emptyList(), reasons, "skipped while a capture is live")
            app.unregisterCaptureControllerForTest("t1")

            app.openAndClose("b", 20_000)
            assertEquals(listOf("closed large or capture tab"), reasons, "the skipped trim kept the 40_000 tally")
        } finally {
            controller.close()
            root.deleteRecursively()
        }
    }

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
