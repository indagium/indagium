@file:Suppress("MagicNumber")

package com.indagium.ui

import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExecutable
import com.indagium.capture.CaptureMappingRow
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureTimeline
import com.indagium.capture.CaptureTools
import com.indagium.capture.FakeCaptureRunner
import com.indagium.capture.StreamingFakeProcess
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.model.VideoAttachment
import com.indagium.utils.HeapPressure
import com.indagium.utils.HeapSnapshot
import com.indagium.utils.MemoryShortfall
import com.indagium.utils.computeItems
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The live capture log view pauses at CRITICAL heap pressure (recording continues on disk), can be
 * resumed once pressure allows, and Stop while paused never loads the backlog into memory.
 */
class CaptureLogPauseTest {
    private val gb = 1024L * 1024L * 1024L
    private val critical = HeapSnapshot(9 * gb, 10 * gb)
    private val warning = HeapSnapshot(8 * gb, 10 * gb)
    private val apps = mutableListOf<AppState>()
    private val dirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        apps.forEach { it.close() }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun newApp(): AppState = AppState(
        autosaveFile = Files.createTempFile("capture-log-pause-autosave", "").toFile(),
        autoExportNotes = false,
    ).also { apps += it }

    private fun tempDir(): File = createTempDirectory("capture-log-pause").toFile().also { dirs += it }

    private fun logLine(n: Int) = "01-02 03:04:05.%03d  100  101 I Tag: msg$n".format(n)

    private fun File.appendLines(range: IntRange) = appendText(range.joinToString("") { logLine(it) + "\n" })

    private fun waitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(10)
        }
    }

    /** A capture-style tab: empty file at offset 0, marked live by a captureSessionId, tailing. */
    private fun AppState.openLiveCaptureTab(file: File): String {
        val tabId = checkNotNull(openFile(file))
        waitUntil { !isLoading }
        upTab(tabId) { it.copy(captureSessionId = "session-$tabId") }
        startCaptureTailing(tabId)
        assertTrue(tab(tabId)!!.tailing)
        return tabId
    }

    private fun AppState.messages(tabId: String) = tab(tabId)!!.logData.map { it.msg }

    @Test
    fun pauseKeepsTheOffsetAndResumeAppendsExactlyTheNewerLines() {
        val file = File(tempDir(), "capture.log").apply { writeText("") }
        val app = newApp()
        val tabId = app.openLiveCaptureTab(file)

        file.appendLines(1..3)
        waitUntil { app.tab(tabId)!!.logData.size == 3 }

        assertTrue(app.pauseTailing(tabId))
        val paused = app.tab(tabId)!!
        assertFalse(paused.tailing)
        assertEquals(3, paused.tailPausedAtRow)
        assertTrue(app.isCaptureLogViewPaused(tabId))
        assertFalse(app.pauseTailing(tabId), "pausing a paused tab is a no-op")

        file.appendLines(4..5)
        // Longer than the capture poll interval: a tailer that kept running would have appended by now.
        Thread.sleep(2_500)
        assertEquals(listOf("msg1", "msg2", "msg3"), app.messages(tabId), "nothing is appended while paused")

        assertTrue(app.resumeCaptureLogView(tabId))
        waitUntil { app.tab(tabId)!!.logData.size == 5 }
        Thread.sleep(1_500) // a replaying tailer would show duplicates by now
        assertEquals(listOf("msg1", "msg2", "msg3", "msg4", "msg5"), app.messages(tabId), "no duplicates, no gaps")
        val resumed = app.tab(tabId)!!
        assertTrue(resumed.tailing)
        assertNull(resumed.tailPausedAtRow)
        assertFalse(app.isCaptureLogViewPaused(tabId))
        app.stopTailing(tabId)
    }

    @Test
    fun drainAndStopOnAPausedTabDoesNotLoadTheBacklog() {
        val file = File(tempDir(), "capture.log").apply { writeText("") }
        val app = newApp()
        val tabId = app.openLiveCaptureTab(file)
        file.appendLines(1..2)
        waitUntil { app.tab(tabId)!!.logData.size == 2 }
        assertTrue(app.pauseTailing(tabId))
        file.appendLines(3..50)

        app.drainAndStopTailing(tabId, includeTrailingPartialLine = true)

        val stopped = app.tab(tabId)!!
        assertEquals(2, stopped.logData.size, "the paused backlog must stay on disk")
        assertFalse(stopped.tailing)
        assertEquals(2, stopped.tailPausedAtRow, "the tab stays marked as a truncated prefix")
        assertFalse(app.isCaptureLogViewPaused(tabId), "the paused tailer is dropped")
        assertFalse(app.resumeCaptureLogView(tabId), "nothing left to resume")
    }

    @Test
    fun criticalPressurePausesLiveCaptureTabsOnlyNotOrdinaryTailedFiles() {
        val dir = tempDir()
        val captureFile = File(dir, "capture.log").apply { writeText("") }
        val plainFile = File(dir, "plain.log").apply { writeText("") }
        val app = newApp()
        val captureTab = app.openLiveCaptureTab(captureFile)
        val plainTab = checkNotNull(app.openFile(plainFile))
        waitUntil { !app.isLoading }
        app.startTailing(plainTab)
        assertTrue(app.tab(plainTab)!!.tailing)

        app.onHeapPressureChanged(HeapPressure.CRITICAL, critical)

        waitUntil { app.isCaptureLogViewPaused(captureTab) }
        assertFalse(app.tab(captureTab)!!.tailing)
        assertEquals(0, app.tab(captureTab)!!.tailPausedAtRow)
        assertTrue(app.tab(plainTab)!!.tailing, "a user-controlled tail -f tab is left alone")
        assertFalse(app.isCaptureLogViewPaused(plainTab))
        app.stopTailing(plainTab)
    }

    @Test
    fun resumeIsRefusedWhileCriticalAndAllowedAtWarning() {
        val file = File(tempDir(), "capture.log").apply { writeText("") }
        val app = newApp()
        val tabId = app.openLiveCaptureTab(file)
        app.onHeapPressureChanged(HeapPressure.CRITICAL, critical)
        waitUntil { app.isCaptureLogViewPaused(tabId) }

        assertFalse(app.resumeCaptureLogView(tabId))
        assertTrue(app.isCaptureLogViewPaused(tabId))
        assertFalse(app.tab(tabId)!!.tailing)

        app.onHeapPressureChanged(HeapPressure.WARNING, warning)
        assertTrue(app.isCaptureLogViewPaused(tabId), "a lower level never resumes automatically")
        assertTrue(app.resumeCaptureLogView(tabId))
        assertTrue(app.tab(tabId)!!.tailing)
        assertNull(app.tab(tabId)!!.tailPausedAtRow)
        app.stopTailing(tabId)
    }

    @Test
    fun aTabThatIsNotALiveCaptureIsNeverResumedByTheCaptureAction() {
        val file = File(tempDir(), "capture.log").apply { writeText("") }
        val app = newApp()
        val tabId = app.openLiveCaptureTab(file)
        assertTrue(app.pauseTailing(tabId))
        // Stop already cleared the live marker (attachFinalizedCapture / a failed finalize).
        app.upTab(tabId) { it.copy(captureSessionId = null) }

        assertFalse(app.resumeCaptureLogView(tabId))
        assertFalse(app.tab(tabId)!!.tailing)
    }

    @Test
    fun stopCaptureWhilePausedFinalizesWithoutLoadingTheBacklogIntoTheTab() {
        val root = tempDir()
        val runner = FakeCaptureRunner()
        runner.enqueue(
            StreamingFakeProcess(
                emitOnTerminate = "01-02 03:04:05.006  100  101 I Tag: written during stop\n" +
                    "01-02 03:04:05.007  100  101 I Tag: no trailing newline",
            ),
        )
        val controller = TabCaptureController(root, runner = runner)
        val app = newApp()
        try {
            val session = controller.start(
                CaptureDevice("SERIAL", "device", "Pixel"),
                CaptureSettings(recordVideo = false, freeSpaceReserveBytes = 0),
                CaptureTools(CaptureExecutable("adb"), null, runner),
            ) {}
            val tabId = checkNotNull(app.openFile(session.logFile))
            waitUntil { !app.isLoading }
            app.registerCaptureControllerForTest(tabId, controller)
            app.upTab(tabId) { it.copy(captureSessionId = session.id) }
            app.startCaptureTailing(tabId)
            assertTrue(app.pauseTailing(tabId))

            app.stopCaptureTab(tabId)
            waitUntil { app.captureFinalizationStatus(tabId) != CAPTURE_FINALIZING_STATUS }

            // The same scenario without the pause puts both rows into the tab (CaptureStopDrainTest).
            val tab = app.tab(tabId)!!
            assertEquals(0, tab.logData.size, "the rows recorded while paused are not drained into memory")
            assertEquals(0, tab.tailPausedAtRow, "the truncation marker survives Stop for the strip")
            assertFalse(tab.tailing)
            assertNull(tab.captureSessionId, "the capture is finalized and the tab no longer live")
            assertNull(app.captureFinalizationStatus(tabId), "finalization must not report a failure: ${app.captureFinalizationStatus(tabId)}")
            assertTrue(session.logFile.readText().contains("no trailing newline"), "the recording itself is complete on disk")
        } finally {
            controller.close()
        }
    }

    // A paused-then-stopped tab is a strict prefix of the capture's own row index. Every ordinal
    // lookup must tolerate timeline rows beyond logData.size (CaptureTimelineIndex ignores them).
    @Test
    fun videoMappingOfATruncatedCaptureTabClampsToTheRowsItHolds() {
        val rows = (1..6).map { CaptureMappingRow(it, elapsedMs = it * 1_000L, videoMs = it * 1_000L) }
        val entries = (1..3).map { LogEntry(it, "10:00:0$it.000", LogLevel.I, "Tag", "row $it") }
        val tab = LogTab(
            id = "capture",
            filename = "capture.log",
            logData = entries,
            rmap = entries.associateBy { it.id },
            attachedVideo = VideoAttachment(
                path = "/tmp/capture.mkv",
                sourceLabel = "capture.mkv",
                captureSourcePath = "/tmp/capture.zip",
                doubleClickSeekEnabled = true,
            ),
            captureTimeline = CaptureTimeline(rows),
            tailPausedAtRow = 3,
        )
        val state = AppState().also {
            it.tabs = listOf(tab)
            it.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))
        }

        assertEquals(2_000L, state.logIdToVideoMs(tab, 2), "rows the tab holds still map")
        assertEquals(3, state.videoMsToNearestLogId(tab, 3_000))
        // A playhead on a row the tab never loaded (ordinal 5) resolves to "after the last row the tab
        // holds" (the index ignores timeline rows beyond logData.size) instead of indexing past it.
        assertNull(state.videoMsToNearestLogId(tab, 5_000))
        assertNull(state.followTargetVisibleLogId(tab.id, 5_000))
        assertEquals(FollowMappingStatus.AFTER_LAST, state.videoFollowMapping(tab.id, 5_000).status)
        assertEquals(3, state.followTargetVisibleLogId(tab.id, 3_000))
        // Diagnostics walk the same indexes; it must simply not throw.
        state.followDiagnostics(tab.id, 5_000)
        state.followDiagnostics(tab.id, 2_000)
    }

    @Test
    fun attachFinalizedCaptureKeepsTheTruncationMarkerAndToleratesAnAnchorBeyondTheTab() {
        val root = tempDir()
        val session = com.indagium.capture.CaptureSession(
            id = "s",
            directory = File(root, "session"),
            device = CaptureDevice("emulator-5554", "device"),
            settings = CaptureSettings(recordVideo = false, freeSpaceReserveBytes = 0),
            startedEpochMs = 1_700_000_000_000,
            elapsedMs = 3_000,
            status = com.indagium.capture.CaptureStatus.STOPPED,
        )
        session.logFile.parentFile.mkdirs()
        session.indexFile.parentFile.mkdirs()
        session.logFile.writeText(logLine(1) + "\n")
        session.indexFile.writeText("{\"byteOffset\":0,\"byteLength\":${session.logFile.length()},\"elapsedMs\":1000,\"rowOrdinal\":1}\n")
        val imported = com.indagium.capture.CaptureArchiveExporter().finalizeSessionInPlace(session)
        val truncated = LogTab(
            id = "t1",
            filename = "Capture — device",
            logData = emptyList(),
            rmap = emptyMap(),
            tailPausedAtRow = 0,
            captureSessionId = "s",
        )

        val attached = attachFinalizedCapture(truncated, imported, enableDoubleClickSeek = true)

        assertEquals(0, attached.tailPausedAtRow)
        assertNull(attached.captureSessionId)
        assertEquals("s", attached.captureSourceSessionId)
    }

    @Test
    fun tailPausedAtRowIsSessionOnlyAndNeverPersisted() {
        val logFile = tempDir().resolve("app.log").apply { writeText("10:00:00.000 I/Tag: hello\n") }
        val base = LogTab(id = "t1", filename = logFile.name, logData = emptyList(), rmap = emptyMap(), sourcePath = logFile.absolutePath)
        val paused = base.copy(tailPausedAtRow = 42)

        assertEquals(base.tabToken(), paused.tabToken(), "the marker must not change the tab token")
        assertEquals(base.persistedSnapshot(), paused.persistedSnapshot())
        assertNull(paused.tabToken().tabShellFromToken()?.tab?.tailPausedAtRow, "a restored tab is never truncated")
    }

    @Test
    fun pausedCaptureStripTextsAndResumeGate() {
        assertEquals(
            "Log view paused at row 1200 to save memory — recording continues on disk",
            captureLogPausedMessage(1200),
        )
        assertEquals(
            "Showing the first 1200 rows — the full log is in the saved capture / ZIP",
            captureLogTruncatedNote(1200),
        )
        assertFalse(captureLogResumeEnabled(HeapPressure.CRITICAL))
        assertTrue(captureLogResumeEnabled(HeapPressure.WARNING))
        assertTrue(captureLogResumeEnabled(HeapPressure.NORMAL))
        // The one predicate also refuses a backlog that would not fit in the free heap.
        val tooBig = MemoryShortfall(neededBytes = 3 * gb, freeBytes = gb)
        assertFalse(captureLogResumeEnabled(HeapPressure.NORMAL, tooBig))
        assertFalse(captureLogResumeEnabled(HeapPressure.WARNING, tooBig))
        assertEquals(CAPTURE_LOG_RESUME_BLOCKED_HINT, captureLogResumeBlockedReason(HeapPressure.CRITICAL, null))
        assertEquals(
            "Loading the rows recorded while paused needs about 3.0 GB of memory; about 1.0 GB is free. " +
                "Close other tabs first",
            captureLogResumeBlockedReason(HeapPressure.NORMAL, tooBig),
        )
    }

    // Resume used to look only at the heap level: a long pause at WARNING could then load a backlog far
    // bigger than the free heap and push the app straight back to critical.
    @Test
    fun resumeIsRefusedWhenThePausedBacklogWouldNotFitAndAllowedOnceItDoes() {
        val file = File(tempDir(), "capture.log").apply { writeText("") }
        val app = newApp()
        val tabId = app.openLiveCaptureTab(file)
        file.appendLines(1..3)
        waitUntil { app.tab(tabId)!!.logData.size == 3 }
        assertTrue(app.pauseTailing(tabId))
        file.appendLines(4..2_000) // ~90 KB recorded while paused

        app.heapFreeBytesProvider = { 10_000L } // 3.5x of the backlog does not fit
        val shortfall = assertNotNull(app.captureLogResumeBacklogShortfall(tabId))
        assertTrue(shortfall.neededBytes > shortfall.freeBytes)
        assertFalse(captureLogResumeEnabled(app.heapPressure, shortfall), "the strip's Resume button is disabled")
        assertFalse(app.resumeCaptureLogView(tabId))
        assertTrue(app.isCaptureLogViewPaused(tabId), "a refused resume leaves the tab paused")
        assertEquals(3, app.tab(tabId)!!.logData.size)

        app.heapFreeBytesProvider = { Long.MAX_VALUE }
        assertNull(app.captureLogResumeBacklogShortfall(tabId))
        assertTrue(app.resumeCaptureLogView(tabId))
        waitUntil { app.tab(tabId)!!.logData.size == 2_000 }
        app.stopTailing(tabId)
    }

    // --- Pause vs Stop: the tailer is claimed atomically, never peeked ---

    // Holds stateLock so the tailer's in-flight append (offset already advanced) blocks inside
    // appendTailedLines: the state a Pause's join and a Stop's drain have to resolve between them.
    private inner class StateLockHolder(private val app: AppState) {
        private val acquired = java.util.concurrent.CountDownLatch(1)
        private val release = java.util.concurrent.CountDownLatch(1)
        private val thread = Thread {
            synchronized(app.stateLock) {
                acquired.countDown()
                release.await(15, java.util.concurrent.TimeUnit.SECONDS)
            }
        }.apply { isDaemon = true }

        fun hold() {
            thread.start()
            assertTrue(acquired.await(15, java.util.concurrent.TimeUnit.SECONDS))
        }

        fun release() {
            release.countDown()
            thread.join(15_000)
        }
    }

    @Test
    fun stopArrivingWhilePauseIsJoiningTakesTheTailerOverAndDrainsEverything() {
        val file = File(tempDir(), "capture.log").apply { writeText("") }
        val app = newApp()
        val tabId = app.openLiveCaptureTab(file)
        val holder = StateLockHolder(app).also { it.hold() }
        file.appendLines(1..2)
        Thread.sleep(2_000) // the tailer has read rows 1..2 and is blocked appending them
        var pauseResult: Boolean? = null
        val pause = Thread { pauseResult = app.pauseTailing(tabId) }.also { it.start() }
        Thread.sleep(300) // the pause has claimed the tailer and is blocked in its join
        file.appendLines(3..4)
        val stop = Thread { app.drainAndStopTailing(tabId, includeTrailingPartialLine = true) }.also { it.start() }
        Thread.sleep(300)

        holder.release()
        pause.join(15_000)
        stop.join(15_000)

        assertEquals(false, pauseResult, "the pause gives up once Stop owns the tailer")
        assertFalse(app.isCaptureLogViewPaused(tabId), "no stale pausedTails entry")
        val tab = app.tab(tabId)!!
        assertNull(tab.tailPausedAtRow, "a fully drained tab carries no truncation marker")
        assertFalse(tab.tailing)
        assertEquals(listOf("msg1", "msg2", "msg3", "msg4"), app.messages(tabId), "Stop drained every row, in order")
        // startTailing is not blocked by anything left over.
        app.startTailing(tabId)
        assertTrue(app.tab(tabId)!!.tailing)
        app.stopTailing(tabId)
    }

    @Test
    fun pauseArrivingWhileStopIsDrainingIsRefused() {
        val file = File(tempDir(), "capture.log").apply { writeText("") }
        val app = newApp()
        val tabId = app.openLiveCaptureTab(file)
        val holder = StateLockHolder(app).also { it.hold() }
        file.appendLines(1..2)
        Thread.sleep(2_000)
        val stop = Thread { app.drainAndStopTailing(tabId, includeTrailingPartialLine = true) }.also { it.start() }
        Thread.sleep(300) // Stop has claimed the tailer and is blocked in its join

        assertFalse(app.pauseTailing(tabId), "Stop owns the tailer; a late pause must not park it")
        assertFalse(app.isCaptureLogViewPaused(tabId))

        holder.release()
        stop.join(15_000)

        val tab = app.tab(tabId)!!
        assertNull(tab.tailPausedAtRow)
        assertFalse(tab.tailing)
        assertFalse(app.isCaptureLogViewPaused(tabId))
        assertEquals(listOf("msg1", "msg2"), app.messages(tabId))
    }

    @Test
    fun pauseVetoedByTheCallerLeavesTheTabTailing() {
        val file = File(tempDir(), "capture.log").apply { writeText("") }
        val app = newApp()
        val tabId = app.openLiveCaptureTab(file)

        assertFalse(app.pauseTailing(tabId, mayPause = { false }), "e.g. the capture started finalizing")

        assertTrue(app.tab(tabId)!!.tailing)
        assertNull(app.tab(tabId)!!.tailPausedAtRow)
        assertFalse(app.isCaptureLogViewPaused(tabId))
        app.stopTailing(tabId)
    }

    @Test
    fun aPausedTabCanBeResumedAgainAfterAPauseThatLostItsClaim() {
        // Pause -> Stop(drain) race lost by the pause, then the user starts watching the tab again.
        val file = File(tempDir(), "capture.log").apply { writeText("") }
        val app = newApp()
        val tabId = app.openLiveCaptureTab(file)
        file.appendLines(1..2)
        waitUntil { app.tab(tabId)!!.logData.size == 2 }
        app.drainAndStopTailing(tabId)

        assertFalse(app.pauseTailing(tabId), "nothing to pause once the tab is stopped")
        assertFalse(app.isCaptureLogViewPaused(tabId))
        assertNull(app.tab(tabId)!!.tailPausedAtRow)
        app.startCaptureTailing(tabId)
        assertTrue(app.tab(tabId)!!.tailing)
        app.stopTailing(tabId)
    }
}
