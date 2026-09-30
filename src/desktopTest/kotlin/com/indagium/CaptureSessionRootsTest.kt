package com.indagium

import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExecutable
import com.indagium.capture.CaptureRecorder
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureTools
import com.indagium.capture.FakeCaptureRunner
import com.indagium.capture.StreamingFakeProcess
import com.indagium.ui.AppState
import com.indagium.ui.CaptureService
import com.indagium.ui.mergeCaptureSessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Section 3 (save folders): capture sessions now live under two roots — the fixed legacy
// Application Support root, and whatever the (configurable) sessions folder currently resolves
// to. CaptureService merges listings from both so a session recorded before a user ever touched
// the setting stays visible and deletable. CaptureCoordinatorToolStatusTest explains why
// CaptureCoordinator/CaptureService itself isn't unit-tested end to end elsewhere; this file
// covers the pure merge helper plus the two operations (listing, discard) that genuinely need to
// span both roots.
class CaptureSessionRootsTest {
    @Test
    fun mergeCaptureSessionsCombinesRootsDedupesByIdAndSortsNewestFirst() {
        val older = fakeSession("a", startedEpochMs = 1_000L)
        val newer = fakeSession("b", startedEpochMs = 2_000L)
        val staleDuplicate = older.copy(elapsedMs = 999L) // same id as `older` from a different root

        val merged = mergeCaptureSessions(listOf(listOf(older, newer), listOf(staleDuplicate)))

        assertEquals(listOf("b", "a"), merged.map(CaptureSession::id))
    }

    @Test
    fun listSessionsCombinesTheLegacyAndConfiguredCaptureRoots() {
        val dir = createTempDirectory("capture-roots-list").toFile()
        val legacyRoot = File(dir, "legacy")
        val newRoot = File(dir, "new")
        val legacyId = recordAndStopOneSession(legacyRoot, serial = "legacy-device")
        val newId = recordAndStopOneSession(newRoot, serial = "new-device")

        val app = AppState(autosaveFile = File(dir, "state.cache"))
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = CaptureService(app, scope, legacyRoot) { newRoot }

            assertEquals(setOf(legacyId, newId), service.listSessions().map(CaptureSession::id).toSet())
        } finally {
            scope.cancel()
        }
    }

    // Regression guard: CaptureRecorder.listSessions() unconditionally mkdirs() the root it scans,
    // so merely listing/recovering sessions (which happens just from opening the home tab, not
    // from starting a capture) must never be the thing that brings a configured-but-never-used
    // sessions folder into existence — that would violate "folders are created on first write
    // only, never at startup" for a folder that can now live somewhere as visible as
    // ~/Documents/Indagium.
    @Test
    fun listingSessionsNeverCreatesAConfiguredRootThatHasNoSessionsYet() {
        val dir = createTempDirectory("capture-roots-no-eager-create").toFile()
        val legacyRoot = File(dir, "legacy")
        val neverUsedRoot = File(dir, "never-used")
        val legacyId = recordAndStopOneSession(legacyRoot, serial = "legacy-device")

        val app = AppState(autosaveFile = File(dir, "state.cache"))
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = CaptureService(app, scope, legacyRoot) { neverUsedRoot }

            assertEquals(listOf(legacyId), service.listSessions().map(CaptureSession::id))
            assertFalse(neverUsedRoot.exists(), "listing must not create a sessions folder nothing has ever written to")

            service.recoverSessions()
            assertFalse(neverUsedRoot.exists(), "recovery must not create it either")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun discardRetainedSessionRemovesFromWhicheverRootActuallyHoldsIt() {
        val dir = createTempDirectory("capture-roots-discard").toFile()
        val legacyRoot = File(dir, "legacy")
        val newRoot = File(dir, "new")
        val legacyId = recordAndStopOneSession(legacyRoot, serial = "legacy-device")
        val newId = recordAndStopOneSession(newRoot, serial = "new-device")

        val app = AppState(autosaveFile = File(dir, "state.cache"))
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val service = CaptureService(app, scope, legacyRoot) { newRoot }

            assertTrue(service.discardRetainedSession(legacyId))

            assertEquals(listOf(newId), service.listSessions().map(CaptureSession::id))
            assertFalse(File(legacyRoot, legacyId).exists())
            assertTrue(File(newRoot, newId).exists())
        } finally {
            scope.cancel()
        }
    }

    private fun recordAndStopOneSession(root: File, serial: String): String {
        val runner = FakeCaptureRunner()
        runner.enqueue(StreamingFakeProcess())
        val recorder = CaptureRecorder(root, runner)
        return try {
            val device = CaptureDevice(serial, "device")
            val tools = CaptureTools(CaptureExecutable("adb"), null, runner)
            val session = recorder.start(device, CaptureSettings(recordVideo = false, freeSpaceReserveBytes = 0L), tools)
            recorder.stop()
            session.id
        } finally {
            recorder.close()
        }
    }

    private fun fakeSession(id: String, startedEpochMs: Long) = CaptureSession(
        id = id,
        directory = File("unused/$id"),
        device = CaptureDevice("SERIAL", "device"),
        settings = CaptureSettings(),
        startedEpochMs = startedEpochMs,
    )
}
