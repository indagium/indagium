package com.indagium.ui

import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExecutable
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureTools
import com.indagium.capture.CompletedFakeProcess
import com.indagium.capture.NativeMediaSupport
import com.indagium.capture.mirror.EmbeddedMirrorSnapshot
import com.indagium.capture.mirror.EmbeddedMirrorState
import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorStreamOptions
import com.indagium.testing.ScriptedAdbRunner
import com.indagium.testing.authoring.EMULATOR_DUMP
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

    private fun newApp(root: File) = AppState(
        autosaveFile = File(root, "state.cache"),
        autoExportNotes = false,
        notesDir = File(root, "notes"),
        archiveCacheDir = File(root, "archive"),
        customCommandsDir = File(root, "commands"),
        controlTokenFile = File(root, "token"),
        sourceIndexFile = File(root, "source-index"),
        testingDir = File(root, "testing"),
    )

    /** An app whose mirror cannot connect, on a device that answers [getevent] for `getevent -lp`. */
    private fun appWithBrokenMirror(root: File, runner: ScriptedAdbRunner): Pair<AppState, TestCase> {
        val app = newApp(root)
        app.captureToolsProvider = { CaptureTools(CaptureExecutable("adb"), null, runner) }
        app.captureMediaSupportProvider = { NativeMediaSupport(available = true) }
        app.testStepRecordingDeviceDiscovery = { listOf(CaptureDevice(AUTHORING_SERIAL, "device", "Pixel")) }
        app.embeddedMirrorHandleFactory = { _, _, _, _ -> EmbeddedMirrorHandle.forBackend(FailingMirrorBackend()) }
        app.updateSettings { it.copy(captureSettings = CaptureSettings(recordVideo = false, mirror = false, freeSpaceReserveBytes = 0)) }
        val suite = (app.createTestSuite("Authoring fixture") as StoreResult.Ok).value
        val testCase = (app.createTestCase(suite.id, TestCase("", "Playback")) as StoreResult.Ok).value
        app.openTestsTab()
        return app to testCase
    }

    private fun answerDeviceInput(runner: ScriptedAdbRunner, withGetevent: Boolean) {
        runner.shellResponder = { arguments ->
            when {
                arguments.take(2) == listOf("getevent", "-lp") ->
                    if (withGetevent) CompletedFakeProcess(EMULATOR_DUMP) else CompletedFakeProcess("", code = 1)
                arguments.take(2) == listOf("wm", "size") -> CompletedFakeProcess("Physical size: 1080x2400\n")
                arguments.take(2) == listOf("dumpsys", "input_method") -> CompletedFakeProcess("  mInputShown=false\n")
                else -> null
            }
        }
    }

    @Test
    fun aMirrorThatCannotConnectDoesNotStopRecordingTheDeviceItself() = runBlocking<Unit> {
        val root = createTempDirectory("authoring-no-mirror").toFile()
        val runner = ScriptedAdbRunner()
        answerDeviceInput(runner, withGetevent = true)
        val (app, testCase) = appWithBrokenMirror(root, runner)
        try {
            val suiteId = app.testLibrary.suites.single().id
            val result = withTimeout(20_000) { app.prepareTestStepRecording(AUTHORING_SERIAL, suiteId, testCase.id) }

            val session = assertIs<StoreResult.Ok<com.indagium.testing.authoring.TestStepRecordingSession>>(result, result.userMessage().orEmpty()).value
            assertNull(session.videoTimeline, "no mirror, no video timeline")
            assertTrue(session.snapshot.value.warnings.any { "Recording continues without the mirror" in it })
            assertTrue(runner.specs.any { "getevent" in it.command && "-lt" in it.command }, "the device's own input is streamed")

            fun line(micros: Long, type: String, code: String, value: String) =
                "[%5d.%06d] /dev/input/event3: %-12s %-20s %s\n".format(micros / 1_000_000, micros % 1_000_000, type, code, value)
            runner.getevent.emit(
                line(5_000_000, "EV_ABS", "ABS_MT_TRACKING_ID", "00000003") +
                    line(5_000_000, "EV_ABS", "ABS_MT_POSITION_X", "00004000") +
                    line(5_000_000, "EV_ABS", "ABS_MT_POSITION_Y", "00004000") +
                    line(5_000_000, "EV_SYN", "SYN_REPORT", "00000000") +
                    line(5_080_000, "EV_ABS", "ABS_MT_TRACKING_ID", "ffffffff") +
                    line(5_080_000, "EV_SYN", "SYN_REPORT", "00000000"),
            )
            awaitUntil { session.snapshot.value.steps.isNotEmpty() }
            assertTrue(session.snapshot.value.steps.single().action.startsWith("Tap at (50%, 50%)"))

            session.stopAndDrain()
            app.clearTestStepRecording(force = true)
        } finally {
            app.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun recordingIsRefusedWithAClearMessageWhenNeitherTheMirrorNorGeteventWorks() = runBlocking<Unit> {
        val root = createTempDirectory("authoring-nothing").toFile()
        val runner = ScriptedAdbRunner()
        answerDeviceInput(runner, withGetevent = false)
        val (app, testCase) = appWithBrokenMirror(root, runner)
        try {
            val suiteId = app.testLibrary.suites.single().id
            val result = withTimeout(20_000) { app.prepareTestStepRecording(AUTHORING_SERIAL, suiteId, testCase.id) }

            val invalid = assertIs<StoreResult.Invalid>(result)
            assertTrue("Nothing can be recorded" in invalid.reason, invalid.reason)
            assertTrue("getevent" in invalid.reason, invalid.reason)
            assertNull(app.testStepRecordingSession)
        } finally {
            app.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aLiveMirrorWithoutGeteventStillRecordsAndSaysWhatIsMissing() = runBlocking<Unit> {
        val root = createTempDirectory("authoring-mirror-only").toFile()
        val runner = ScriptedAdbRunner()
        answerDeviceInput(runner, withGetevent = false)
        val app = newApp(root)
        try {
            app.captureToolsProvider = { CaptureTools(CaptureExecutable("adb"), null, runner) }
            app.captureMediaSupportProvider = { NativeMediaSupport(available = true) }
            app.testStepRecordingDeviceDiscovery = { listOf(CaptureDevice(AUTHORING_SERIAL, "device", "Pixel")) }
            app.embeddedMirrorHandleFactory = { _, _, _, _ -> EmbeddedMirrorHandle.forBackend(ImmediateMirrorBackend()) }
            app.updateSettings { it.copy(captureSettings = CaptureSettings(recordVideo = false, mirror = false, freeSpaceReserveBytes = 0)) }
            val suite = (app.createTestSuite("Authoring fixture") as StoreResult.Ok).value
            val testCase = (app.createTestCase(suite.id, TestCase("", "Playback")) as StoreResult.Ok).value
            app.openTestsTab()

            val result = withTimeout(20_000) { app.prepareTestStepRecording(AUTHORING_SERIAL, suite.id, testCase.id) }

            val session = assertIs<StoreResult.Ok<com.indagium.testing.authoring.TestStepRecordingSession>>(result, result.userMessage().orEmpty()).value
            assertNotNull(session.videoTimeline, "a live mirror keeps its video timeline")
            assertTrue(session.snapshot.value.warnings.any { "Only input sent through the Indagium mirror" in it })
            assertTrue(runner.specs.none { "getevent" in it.command && "-lt" in it.command }, "no stream is started for a device that cannot report input")
            session.stopAndDrain()
            app.clearTestStepRecording(force = true)
        } finally {
            app.close()
            root.deleteRecursively()
        }
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) kotlinx.coroutines.delay(20)
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

private class FailingMirrorBackend : MirrorBackend {
    override fun snapshot() = EmbeddedMirrorSnapshot(EmbeddedMirrorState.DISCONNECTED)

    override fun start(serial: String, options: MirrorStreamOptions) {
        error("the mirror is not available in this test")
    }

    override fun stop() = Unit

    override fun send(command: MirrorControlCommand) = false

    override fun isAlreadyStarted(serial: String) = false

    override fun close() = Unit
}
