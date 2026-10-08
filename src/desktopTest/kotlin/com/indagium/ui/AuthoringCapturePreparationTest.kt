package com.indagium.ui

import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExecutable
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureTools
import com.indagium.capture.NativeMediaSupport
import com.indagium.capture.mirror.EmbeddedMirrorSnapshot
import com.indagium.capture.mirror.EmbeddedMirrorState
import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorStreamOptions
import com.indagium.testing.ScriptedAdbRunner
import com.indagium.testing.model.TestCase
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AuthoringCapturePreparationTest {
    @Test
    fun cancelBeforeTheEmbeddedMirrorIsReadyNeverRegistersInputObservation() = runBlocking {
        val root = createTempDirectory("authoring-capture-cancel").toFile()
        val runner = ScriptedAdbRunner()
        val app = AppState(
            autosaveFile = File(root, "state.cache"),
            autoExportNotes = false,
            notesDir = File(root, "notes"),
            archiveCacheDir = File(root, "archive"),
            customCommandsDir = File(root, "commands"),
            controlTokenFile = File(root, "token"),
            sourceIndexFile = File(root, "source-index"),
            testingDir = File(root, "testing"),
        )
        val mirrorStarting = CountDownLatch(1)
        val releaseMirrorStart = CountDownLatch(1)
        val backend = object : MirrorBackend {
            @Volatile private var live = false

            @Volatile private var deviceSerial: String? = null

            override fun snapshot() = EmbeddedMirrorSnapshot(
                if (live) EmbeddedMirrorState.LIVE else EmbeddedMirrorState.DISCONNECTED,
                deviceSerial = deviceSerial,
            )

            override fun start(serial: String, options: MirrorStreamOptions) {
                deviceSerial = serial
                mirrorStarting.countDown()
                check(releaseMirrorStart.await(5, TimeUnit.SECONDS)) { "test setup did not release mirror startup" }
                live = true
            }

            override fun stop() {
                live = false
                deviceSerial = null
            }

            override fun send(command: MirrorControlCommand) = false

            override fun isAlreadyStarted(serial: String) = live

            override fun close() {
                releaseMirrorStart.countDown()
                live = false
                deviceSerial = null
            }
        }
        try {
            app.captureToolsProvider = { CaptureTools(CaptureExecutable("adb"), null, runner) }
            app.captureMediaSupportProvider = { NativeMediaSupport(available = true) }
            app.testStepRecordingDeviceDiscovery = { listOf(CaptureDevice(AUTHORING_SERIAL, "device", "Pixel")) }
            app.embeddedMirrorHandleFactory = { _, _, _, _ -> EmbeddedMirrorHandle.forBackend(backend) }
            app.updateSettings {
                it.copy(captureSettings = CaptureSettings(recordVideo = false, mirror = false, freeSpaceReserveBytes = 0))
            }
            val suite = (app.createTestSuite("Authoring fixture") as StoreResult.Ok).value
            val testCase = (app.createTestCase(suite.id, TestCase("", "Playback")) as StoreResult.Ok).value
            app.openTestsTab()

            val preparation = async(Dispatchers.Default) {
                app.prepareTestStepRecording(AUTHORING_SERIAL, suite.id, testCase.id)
            }
            assertTrue(mirrorStarting.await(5, TimeUnit.SECONDS), "capture should start its selected embedded mirror")
            assertEquals(ActiveSurface.Tests, app.activeSurface, "authoring leaves the Tests workspace in front")
            preparation.cancelAndJoin()

            assertNull(app.testStepRecordingSession, "input observers are installed only after mirror readiness")
            assertFalse(preparation.isActive)

            // Let the independent mirror-start job finish before teardown; the already-started capture is intentionally reusable.
            releaseMirrorStart.countDown()
            withTimeout(5_000) {
                while (app.embeddedMirrorFor(assertNotNull(app.liveCaptureTabId))?.snapshot?.value?.state != EmbeddedMirrorState.LIVE) {
                    kotlinx.coroutines.delay(20)
                }
            }
            assertNull(app.testStepRecordingSession)
        } finally {
            releaseMirrorStart.countDown()
            app.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun preparationReusesTheMatchingManualCaptureInsteadOfStartingAnother() = runBlocking<Unit> {
        val root = createTempDirectory("authoring-capture-reuse").toFile()
        val runner = ScriptedAdbRunner()
        val app = AppState(
            autosaveFile = File(root, "state.cache"),
            autoExportNotes = false,
            notesDir = File(root, "notes"),
            archiveCacheDir = File(root, "archive"),
            customCommandsDir = File(root, "commands"),
            controlTokenFile = File(root, "token"),
            sourceIndexFile = File(root, "source-index"),
            testingDir = File(root, "testing"),
        )
        val backend = ImmediateMirrorBackend()
        try {
            app.captureToolsProvider = { CaptureTools(CaptureExecutable("adb"), null, runner) }
            app.captureMediaSupportProvider = { NativeMediaSupport(available = true) }
            app.testStepRecordingDeviceDiscovery = { listOf(CaptureDevice(AUTHORING_SERIAL, "device", "Pixel")) }
            app.embeddedMirrorHandleFactory = { _, _, _, _ -> EmbeddedMirrorHandle.forBackend(backend) }
            app.updateSettings {
                it.copy(captureSettings = CaptureSettings(recordVideo = false, mirror = false, freeSpaceReserveBytes = 0))
            }
            val suite = (app.createTestSuite("Authoring fixture") as StoreResult.Ok).value
            val testCase = (app.createTestCase(suite.id, TestCase("", "Playback")) as StoreResult.Ok).value
            app.openTestsTab()
            val sourceTabId = requireNotNull(app.startCaptureTab(CaptureDevice(AUTHORING_SERIAL, "device", "Pixel"), takeFocus = false))
            val sourceController = requireNotNull(app.captureControllerFor(sourceTabId))
            withTimeout(5_000) {
                while (sourceController.snapshot.value.state != com.indagium.capture.RecorderState.RECORDING) {
                    kotlinx.coroutines.delay(20)
                }
            }

            val result = app.prepareTestStepRecording(AUTHORING_SERIAL, suite.id, testCase.id)

            assertIs<StoreResult.Ok<com.indagium.testing.authoring.TestStepRecordingSession>>(
                result,
                result.userMessage().orEmpty(),
            )
            assertEquals(sourceTabId, app.liveCaptureTabId)
            assertSame(sourceController, app.captureControllerFor(sourceTabId))
            assertEquals(1, app.tabs.count { it.captureSessionId != null && it.testLane == null })
            assertEquals(ActiveSurface.Tests, app.activeSurface)
            assertNotNull(app.testStepRecordingSession)
            app.stopTestStepRecording()
            app.clearTestStepRecording(force = true)
        } finally {
            app.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun missingCaseIsRejectedBeforeDeviceDiscoveryOrCaptureStart() = runBlocking {
        val root = createTempDirectory("authoring-capture-preflight").toFile()
        val app = AppState(
            autosaveFile = File(root, "state.cache"),
            autoExportNotes = false,
            notesDir = File(root, "notes"),
            archiveCacheDir = File(root, "archive"),
            customCommandsDir = File(root, "commands"),
            controlTokenFile = File(root, "token"),
            sourceIndexFile = File(root, "source-index"),
            testingDir = File(root, "testing"),
        )
        var discovered = false
        try {
            app.testStepRecordingDeviceDiscovery = { discovered = true; emptyList() }
            val result = app.prepareTestStepRecording(AUTHORING_SERIAL, "missing-suite", "missing-case")

            assertIs<StoreResult.NotFound>(result)
            assertFalse(discovered)
            assertFalse(app.captureStartInProgress)
            assertNull(app.liveCaptureTabId)
        } finally {
            app.close()
            root.deleteRecursively()
        }
    }

    private companion object {
        const val AUTHORING_SERIAL = "AUTHORING-1"
    }
}

private class ImmediateMirrorBackend : MirrorBackend {
    @Volatile private var live = false

    @Volatile private var deviceSerial: String? = null

    override fun snapshot() = EmbeddedMirrorSnapshot(
        if (live) EmbeddedMirrorState.LIVE else EmbeddedMirrorState.DISCONNECTED,
        deviceSerial = deviceSerial,
    )

    override fun start(serial: String, options: MirrorStreamOptions) {
        deviceSerial = serial
        live = true
    }

    override fun stop() {
        live = false
        deviceSerial = null
    }

    override fun send(command: MirrorControlCommand) = false

    override fun isAlreadyStarted(serial: String) = live

    override fun close() {
        live = false
        deviceSerial = null
    }
}
