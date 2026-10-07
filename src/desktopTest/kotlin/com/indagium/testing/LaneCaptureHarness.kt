package com.indagium.testing

import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureMirrorMode
import com.indagium.capture.CaptureProcessRunner
import com.indagium.capture.CaptureProcessSpec
import com.indagium.capture.CaptureSettings
import com.indagium.capture.NativeMediaSupport
import com.indagium.capture.RunningCaptureProcess
import com.indagium.capture.withMirrorMode
import com.indagium.model.TestLaneTabRef
import com.indagium.ui.AppState
import com.indagium.ui.LaneCaptureHandle
import com.indagium.ui.LaneCaptureStart
import com.indagium.ui.TabCaptureController
import java.io.File
import kotlin.io.path.createTempDirectory

// An AppState whose lane captures run against synthetic devices: one scripted adb per serial (DeviceFarm), reached through one
// runner that routes by the `-s <serial>` of each command. Nothing here needs a device, adb or a real scrcpy.

/** Sends each adb command to the scripted adb of the serial it names (the first device for a command that names none). */
private class SerialRoutingRunner(private val farm: DeviceFarm, private val defaultSerial: String) : CaptureProcessRunner {
    override fun start(spec: CaptureProcessSpec): RunningCaptureProcess {
        val command = spec.command
        val serial = command.getOrNull(command.indexOf("-s") + 1)?.takeIf { "-s" in command } ?: defaultSerial
        return farm.adb(serial).start(spec)
    }
}

internal const val HARNESS_RUN_ID = "run-harness"
private const val HARNESS_MARKER_WINDOW_MS = 300L
private const val NANOS_PER_MILLISECOND = 1_000_000L

internal class LaneCaptureHarness(vararg serials: String) {
    val dir: File = createTempDirectory("indagium-lane-capture").toFile()
    val farm = DeviceFarm()
    val serials: List<String> = serials.toList().ifEmpty { listOf(FIXTURE_SERIAL) }

    val state = AppState(
        autosaveFile = File(dir, "state.cache"),
        autoExportNotes = false,
        notesDir = File(dir, "notes"),
        archiveCacheDir = File(dir, "archive"),
        customCommandsDir = File(dir, "commands"),
        controlTokenFile = File(dir, "token"),
        sourceIndexFile = File(dir, "source-index"),
        testingDir = File(dir, "testing"),
    )

    /** What lanes record by default here: logcat only, no display, short marker windows so a test does not wait seconds. */
    val captureSettings: CaptureSettings = CaptureSettings(
        recordVideo = false,
        includeBufferedLogs = false,
        freeSpaceReserveBytes = 0,
        markerPreMs = HARNESS_MARKER_WINDOW_MS,
        markerPostMs = HARNESS_MARKER_WINDOW_MS,
        markerScreenshot = false,
    ).withMirrorMode(CaptureMirrorMode.DISABLED)

    init {
        val runner = SerialRoutingRunner(farm, this.serials.first())
        this.serials.forEach { farm.adb(it) }
        state.captureToolsProvider = { fixtureTools(runner) }
        state.captureMediaSupportProvider = { NativeMediaSupport(available = true) }
        state.laneControllerFactory = { root -> TabCaptureController(root, runner = runner) }
    }

    fun laneDir(laneId: String): File = File(dir, "lanes/$laneId").apply { mkdirs() }

    fun ref(laneId: String) = TestLaneTabRef(HARNESS_RUN_ID, laneId)

    /** Starts the recording of lane [laneId] on [serial], with a tab when [openTab]. */
    fun startLane(laneId: String, serial: String = serials.first(), openTab: Boolean = true, settings: CaptureSettings = captureSettings): LaneCaptureHandle =
        state.beginLaneCapture(
            LaneCaptureStart(
                lane = ref(laneId),
                device = CaptureDevice(serial, "device", "Pixel-$serial"),
                captureRoot = File(laneDir(laneId), "capture"),
                settings = settings,
                openTab = openTab,
                title = "Test lane — agent · Pixel-$serial",
            ),
        )

    fun emit(serial: String, message: String, seconds: Int = 5) = farm.adb(serial).logcat.emit(logRow(message, seconds = seconds))

    fun close() {
        state.close()
        dir.deleteRecursively()
    }
}

/** Polls [condition] until it holds; fails the test with [what] when it does not within [timeoutMs]. */
internal fun awaitTrue(what: String, timeoutMs: Long = 20_000L, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLISECOND
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Timed out waiting for: $what" }
        Thread.sleep(20)
    }
}
