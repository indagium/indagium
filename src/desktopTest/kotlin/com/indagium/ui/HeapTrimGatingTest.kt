package com.indagium.ui

import com.indagium.capture.FakeCaptureRunner
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A heap trim is a full GC pause, so [AppState.requestHeapTrim] must skip it while a capture records,
 * and closing small tabs only requests one once their rows add up past the threshold (50_000).
 */
class HeapTrimGatingTest {
    private fun rows(count: Int) = (1..count).map { LogEntry(it, "10:00:00.000", LogLevel.I, "Tag", "msg $it") }

    private fun newState() = AppState(
        autosaveFile = Files.createTempFile("heap-trim-gating-autosave", "").toFile(),
        autoExportNotes = false,
    ).also { it.closedTabRowsReleaseDelayMs = 100 }

    /** Opens and closes a plain (non-capture) tab of [count] rows. */
    private fun AppState.openAndClose(id: String, count: Int): LogTab {
        val tab = mkTab(id, "$id.log", rows(count))
        tabs = tabs + tab
        closeTab(id)
        return tab
    }

    private fun waitUntilReleased(tab: LogTab) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while ((tab.logData.isNotEmpty() || tab.rmap.isNotEmpty()) && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(tab.logData.isEmpty() && tab.rmap.isEmpty(), "closed rows and index must be released")
    }

    @Test
    fun smallClosesAccumulateAndTheCrossingCloseTrimsOnceThenResets() {
        val app = newState()
        try {
            val reasons = Collections.synchronizedList(mutableListOf<String>())
            val trimmed = CountDownLatch(1)
            val crossing = mkTab("c", "c.log", rows(20_000))
            app.heapTrimRequester = {
                reasons += it
                trimmed.countDown()
            }

            app.openAndClose("a", 20_000)
            app.openAndClose("b", 20_000)
            assertEquals(emptyList(), reasons, "40_000 closed rows are below the threshold")

            app.tabs = app.tabs + crossing
            app.closeTab(crossing.id)
            assertTrue(trimmed.await(10, TimeUnit.SECONDS), "the threshold crossing must eventually trim")
            assertEquals(listOf("released closed capture rows"), reasons)

            val afterReset = app.openAndClose("d", 20_000)
            waitUntilReleased(afterReset)
            assertEquals(1, reasons.size, "the trim reset the tally, so the next small close does not trim")
        } finally {
            app.close()
        }
    }

    @Test
    fun rapidClosesCountReleasedRowsOnceAndPinnedRowsOnlyAfterUnpin() {
        val app = newState()
        try {
            val reasons = Collections.synchronizedList(mutableListOf<String>())
            app.heapTrimRequester = { reasons += it }
            val pinned = mkTab("pinned", "pinned.log", rows(40_000))
            app.tabs = listOf(pinned)
            val pin = app.pinRows(pinned)
            app.closeTab(pinned.id)
            val released = (1..3).map { app.openAndClose("rapid-$it", 20_000) }
            released.forEach(::waitUntilReleased)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (reasons.size < 1 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(1, reasons.size, "60,000 released rows should trigger one trim")
            assertEquals(40_000, pinned.logData.size, "the pinned store remains readable")
            pin.close()
            waitUntilReleased(pinned)
            assertEquals(1, reasons.size, "pinned rows start a new tally only when released")
            val crossing = app.openAndClose("crossing", 20_000)
            waitUntilReleased(crossing)
            val nextDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (reasons.size < 2 && System.nanoTime() < nextDeadline) Thread.sleep(10)
            assertEquals(2, reasons.size, "later released rows must not be erased by an earlier trim")
        } finally {
            app.close()
        }
    }

    @Test
    fun aTrimForAnotherReasonResetsTheTally() {
        val app = newState()
        val reasons = mutableListOf<String>()
        app.heapTrimRequester = { reasons += it }

        val first = app.openAndClose("a", 40_000)
        waitUntilReleased(first)
        app.requestHeapTrim("capture finalized")
        assertEquals(listOf("capture finalized"), reasons)

        // 40_000 + 40_000 would have crossed 50_000 had the tally not been reset.
        val second = app.openAndClose("b", 40_000)
        waitUntilReleased(second)
        assertEquals(listOf("capture finalized"), reasons)
        app.close()
    }

    @Test
    fun aTrimSkippedForALiveCaptureDoesNotResetTheTally() {
        val root = createTempDirectory("heap-trim-gating-tally").toFile()
        val controller = TabCaptureController(root, runner = FakeCaptureRunner())
        val app = newState()
        try {
            val reasons = Collections.synchronizedList(mutableListOf<String>())
            val trimmed = CountDownLatch(1)
            val crossing = mkTab("b", "b.log", rows(20_000))
            app.heapTrimRequester = {
                reasons += it
                trimmed.countDown()
            }

            app.registerCaptureControllerForTest("t1", controller)
            val first = app.openAndClose("a", 40_000)
            waitUntilReleased(first)
            app.requestHeapTrim("recording")
            assertEquals(emptyList(), reasons, "skipped while a capture is live")
            app.unregisterCaptureControllerForTest("t1")

            app.tabs = app.tabs + crossing
            app.closeTab(crossing.id)
            assertTrue(trimmed.await(10, TimeUnit.SECONDS), "the retained tally must eventually trigger a trim")
            assertEquals(listOf("released closed capture rows"), reasons, "the skipped trim kept the 40_000 tally")
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
