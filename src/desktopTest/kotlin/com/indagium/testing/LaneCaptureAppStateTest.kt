package com.indagium.testing

import com.indagium.capture.CaptureArchiveReader
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureSettings
import com.indagium.capture.parseMarkerHeader
import com.indagium.capture.reanchorImportedCaptureNotes
import com.indagium.model.AnnBlock
import com.indagium.testing.device.LaneMarkerOutcome
import com.indagium.testing.device.LaneMarkerRequest
import com.indagium.ui.ActiveSurface
import com.indagium.ui.AppLaneCapture
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val STOP_WAIT_MS = 30_000L

/** The lane capture primitives of AppState: a quiet lane tab, the per-device guard, concurrent lanes, stop, tabless lanes and markers. */
class LaneCaptureAppStateTest {
    private var harness: LaneCaptureHarness? = null

    @AfterTest
    fun tearDown() {
        harness?.close()
    }

    private fun harness(vararg serials: String) = LaneCaptureHarness(*serials).also { harness = it }

    // ── The lane tab ────────────────────────────────────────────────

    @Test
    fun aLaneStartOpensACaptureTabMarkedAsALaneWithoutTakingFocus() {
        val h = harness()
        h.state.openTestsTab()
        val before = h.state.activeSurface
        val handle = h.startLane("lane-a")
        val tabId = assertNotNull(handle.tabId)

        val tab = assertNotNull(h.state.tab(tabId))
        assertEquals(h.ref("lane-a"), tab.testLane)
        assertEquals("Test lane — agent · Pixel-$FIXTURE_SERIAL", tab.filename)
        assertNotNull(tab.captureSessionId, "a real, live capture tab")
        assertTrue(tab.tailing)
        assertEquals(before, h.state.activeSurface, "the Tests workspace keeps the focus")
        assertIs<ActiveSurface.Tests>(h.state.activeSurface)
        assertFalse(h.state.activeTabId == tabId, "the lane tab was not activated")
        assertNotNull(h.state.captureControllerFor(tabId))
    }

    @Test
    fun theManualLiveCaptureRuleIgnoresLaneTabs() {
        val h = harness()
        val handle = h.startLane("lane-a")
        assertNotNull(handle.tabId)
        assertNull(h.state.liveCaptureTabId, "a lane tab is not the user's live capture")
        assertNull(h.state.liveCaptureSerial())
    }

    // ── The per-device guard ────────────────────────────────────────

    @Test
    fun aLaneRefusesTheDeviceOfTheManualLiveCapture() {
        val h = harness()
        val manual = TabCaptureControllerFixture.liveManualCapture(h)
        assertEquals(manual.tabId, h.state.liveCaptureTabId)

        val failure = assertFailsWith<IllegalStateException> { h.startLane("lane-a") }
        assertTrue(failure.message.orEmpty().contains("live capture in the main window"), failure.message)
        assertNull(h.state.laneCaptureOnDevice(FIXTURE_SERIAL), "a refused lane holds nothing")
        manual.close()
    }

    @Test
    fun aManualCaptureRefusesTheDeviceOfARunningLaneWithAClearMessage() {
        val h = harness()
        h.startLane("lane-a")

        val started = h.state.startCaptureTab(CaptureDevice(FIXTURE_SERIAL, "device", "Pixel"))
        assertNull(started)
        assertTrue(h.state.captureService.error.orEmpty().contains("AI test run"), h.state.captureService.error)
        assertNull(h.state.liveCaptureTabId)
    }

    @Test
    fun twoLanesCannotRecordTheSameDeviceAtOnce() {
        val h = harness()
        h.startLane("lane-a")
        val failure = assertFailsWith<IllegalStateException> { h.startLane("lane-b") }
        assertTrue(failure.message.orEmpty().contains("another test lane"), failure.message)
        assertEquals(h.ref("lane-a"), h.state.laneCaptureOnDevice(FIXTURE_SERIAL))
    }

    @Test
    fun lanesOnDifferentDevicesRecordAtTheSameTime() {
        val h = harness("SER-A", "SER-B")
        val a = h.startLane("lane-a", "SER-A")
        val b = h.startLane("lane-b", "SER-B")
        h.emit("SER-A", "only on A")
        h.emit("SER-B", "only on B")

        awaitTrue("both logs") { a.session.logFile.readText().contains("only on A") && b.session.logFile.readText().contains("only on B") }
        assertFalse(a.session.logFile.readText().contains("only on B"))
        assertNotNull(a.tabId)
        assertNotNull(b.tabId)
        assertEquals(setOf(h.ref("lane-a"), h.ref("lane-b")), setOf(h.state.laneCaptureOnDevice("SER-A"), h.state.laneCaptureOnDevice("SER-B")))
        assertNull(h.state.liveCaptureTabId)
    }

    // ── Stopping ────────────────────────────────────────────────────

    @Test
    fun finishingALaneStopsTheRecordingKeepsTheTabAsAStoppedCaptureAndFreesTheDevice() {
        val h = harness()
        val handle = h.startLane("lane-a")
        val tabId = assertNotNull(handle.tabId)
        h.emit(FIXTURE_SERIAL, "before the end")
        awaitTrue("row recorded") { handle.session.logFile.readText().contains("before the end") }

        h.state.finishLaneCapture(handle, STOP_WAIT_MS)

        assertNull(h.state.captureControllerFor(tabId), "the controller is gone: nothing records any more")
        val tab = assertNotNull(h.state.tab(tabId), "the tab stays")
        assertNull(tab.captureSessionId)
        assertEquals(handle.session.id, tab.captureSourceSessionId)
        assertEquals(h.ref("lane-a"), tab.testLane)
        assertNull(h.state.laneCaptureOnDevice(FIXTURE_SERIAL))
        assertTrue(File(handle.session.directory, "capture.indagium.json").isFile, "finalized like a manual capture")
        val registered = assertNotNull(h.state.captureService.retainedSession(handle.session.id), "Save ZIP finds the lane's session")
        assertEquals(handle.session.directory, registered.directory)
        assertFalse(h.state.captureService.listSessions().any { it.id == handle.session.id }, "the lane is not offered as a retained session of the launcher")
        // The device is free again for another lane and for a manual capture.
        h.startLane("lane-b")
    }

    @Test
    fun closingTheLaneTabEndsOnlyThatLanesRecording() {
        val h = harness("SER-A", "SER-B")
        val a = h.startLane("lane-a", "SER-A")
        val b = h.startLane("lane-b", "SER-B")
        val captureA = AppLaneCapture(h.state, a, h.laneDir("lane-a"))
        val captureB = AppLaneCapture(h.state, b, h.laneDir("lane-b"))

        h.state.closeTab(assertNotNull(a.tabId))

        val reason = runBlocking { withTimeout(STOP_WAIT_MS) { captureA.lost.await() } }
        assertTrue(reason.contains("capture ended"), reason)
        assertFalse(captureB.lost.isCompleted, "the other lane is untouched")
        assertTrue(captureB.isRecording)
        captureA.stop()
        captureB.stop()
    }

    // ── Markers and the archive ─────────────────────────────────────

    private fun recordedLane(h: LaneCaptureHarness, openTab: Boolean): Pair<AppLaneCapture, com.indagium.ui.LaneCaptureHandle> {
        val handle = h.startLane("lane-a", openTab = openTab)
        repeat(6) { index -> h.emit(FIXTURE_SERIAL, "row $index") }
        awaitTrue("rows recorded") { handle.session.logFile.readText().contains("row 5") }
        return AppLaneCapture(h.state, handle, h.laneDir("lane-a")) to handle
    }

    @Test
    fun aTablessLaneWritesTheSameMarkerAndExportsAnArchiveThatReopensWithIt() {
        val h = harness()
        val (capture, handle) = recordedLane(h, openTab = false)
        assertNull(handle.tabId)
        assertNull(h.state.tabs.firstOrNull { it.testLane != null }, "no tab was opened")

        val outcome = runBlocking {
            capture.addMarker(LaneMarkerRequest("AI · Sign in · step 1 failed", "**Action:** tap\n- Expected: it opens", fixturePng(40, 80)))
        }
        assertIs<LaneMarkerOutcome.Added>(outcome)
        assertTrue(outcome.screenshotAttached)
        runBlocking { capture.awaitMarkers(10_000L) }
        capture.stop()

        val archive = File(h.dir, "lane.zip")
        val exported = capture.exportArchive(archive)
        assertTrue(exported.file.isFile && archive.length() > 0)
        val imported = CaptureArchiveReader.open(archive, File(h.dir, "cache"))
        val notes = assertNotNull(imported.notes, "the archive carries the lane's notes")
        val marker = notes.blocks.filterIsInstance<AnnBlock.Note>().mapNotNull { parseMarkerHeader(it.text) }.single()
        assertEquals("AI · Sign in · step 1 failed", marker.label)
        assertTrue(notes.blocks.any { it is AnnBlock.Image }, "the step screenshot is in the notes")
        assertTrue(notes.blocks.any { it is AnnBlock.LogRef }, "the log window around the failure is referenced")
        assertTrue(imported.logFile.readText().contains("row 3"), "the whole log is in the archive")
        val parsed = com.indagium.utils.parseLogcat(imported.logFile)
        val reanchored = reanchorImportedCaptureNotes(notes, imported.descriptor.markers, parsed)
        assertTrue(reanchored.blocks.filterIsInstance<AnnBlock.LogRef>().single().logIds.isNotEmpty(), "the marker still points at rows after reopening")
        assertTrue(File(h.laneDir("lane-a"), "lane-notes.ann").isFile, "the lane's notes were kept next to its other files for later issue exports")
    }

    @Test
    fun aTabLanesAiMarkerHasTheShapeOfAMarkIssuePress() {
        val h = harness()
        val (capture, handle) = recordedLane(h, openTab = true)
        val tabId = assertNotNull(handle.tabId)

        h.state.markIssue(tabId)
        awaitTrue("the button marker's log window") {
            h.state.tab(tabId)!!.annotations.blocks.count { it is AnnBlock.LogRef } == 1
        }
        // The rows around the failure: the button's window above has long passed, so the failing step's rows are new.
        repeat(3) { index -> h.emit(FIXTURE_SERIAL, "failing step row $index", seconds = 9) }
        awaitTrue("failing rows recorded") { handle.session.logFile.readText().contains("failing step row 2") }
        runBlocking { capture.addMarker(LaneMarkerRequest("AI · Case · step 2 failed", "- Action: x", fixturePng(40, 80))) }
        runBlocking { capture.awaitMarkers(10_000L) }

        val blocks = h.state.tab(tabId)!!.annotations.blocks
        val markers = blocks.filterIsInstance<AnnBlock.Note>().mapNotNull { parseMarkerHeader(it.text) }
        assertEquals(listOf("Issue detected here", "AI · Case · step 2 failed"), markers.map { it.label })
        assertEquals(listOf("m1", "m2"), markers.map { it.id }, "numbered like any other marker of the tab")
        val aiNote = blocks.filterIsInstance<AnnBlock.Note>().last()
        assertTrue(aiNote.text.contains("## ▲ Marker 2 — AI · Case · step 2 failed") && aiNote.text.contains("- Action: x"), aiNote.text)
        assertTrue(blocks.any { it is AnnBlock.Image }, "the screenshot sits under the note")
        assertEquals(2, blocks.count { it is AnnBlock.LogRef }, "each marker has its log window")
        capture.stop()
    }

    @Test
    fun aMarkerAfterTheRecordingStoppedIsSkippedNotThrown() {
        val h = harness()
        val (capture, _) = recordedLane(h, openTab = false)
        capture.stop()
        val outcome = runBlocking { capture.addMarker(LaneMarkerRequest("late", "late", null)) }
        assertIs<LaneMarkerOutcome.Skipped>(outcome)
    }

    @Suppress("unused")
    private fun settingsWithVideo(): CaptureSettings = CaptureSettings()
}

/** A manual live capture on [FIXTURE_SERIAL] the way the toolbar's flow leaves one: a controller, its tab and its session id. */
private object TabCaptureControllerFixture {
    class Manual(val tabId: String, private val controller: com.indagium.ui.TabCaptureController) {
        fun close() = controller.close()
    }

    fun liveManualCapture(h: LaneCaptureHarness): Manual {
        val controller = com.indagium.ui.TabCaptureController(File(h.dir, "manual-sessions"), runner = h.farm.adb(FIXTURE_SERIAL))
        val session = controller.start(
            CaptureDevice(FIXTURE_SERIAL, "device", "Pixel"),
            CaptureSettings(recordVideo = false, freeSpaceReserveBytes = 0),
            fixtureTools(h.farm.adb(FIXTURE_SERIAL)),
        ) {}
        val tabId = checkNotNull(h.state.openFile(session.logFile))
        awaitTrue("tab loaded") { !h.state.isLoading }
        h.state.registerCaptureControllerForTest(tabId, controller)
        h.state.upTab(tabId) { it.copy(captureSessionId = session.id) }
        return Manual(tabId, controller)
    }
}
