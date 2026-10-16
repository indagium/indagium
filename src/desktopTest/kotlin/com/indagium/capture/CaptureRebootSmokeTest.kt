package com.indagium.capture

import com.indagium.testing.device.StandaloneLaneCapture
import com.indagium.testing.device.TestDeviceSession
import kotlinx.coroutines.runBlocking
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.junit.Assume.assumeTrue
import java.io.File
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Opt-in integration check. Never reboots a physical device or runs as part of ordinary tests. */
@Suppress("MagicNumber") // Fixture timings, counts and sizes, not tunable constants.
class CaptureRebootSmokeTest {
    @Test
    fun emulatorRebootKeepsOneCaptureAndExportsItsGap() {
        val serial = System.getenv("INDAGIUM_CAPTURE_SMOKE_SERIAL").orEmpty()
        assumeTrue("Set INDAGIUM_CAPTURE_SMOKE_SERIAL to an emulator serial to run", serial.startsWith("emulator-"))
        val adbPath = System.getenv("INDAGIUM_CAPTURE_SMOKE_ADB") ?: "/opt/homebrew/bin/adb"
        assumeTrue("Set INDAGIUM_CAPTURE_SMOKE_ADB to the adb executable", File(adbPath).canExecute())
        val evidence = File("build/verification/capture-reboot/${UUID.randomUUID()}").absoluteFile
        evidence.mkdirs()
        val runner = ProcessBuilderCaptureRunner()
        val tools = CaptureTools(CaptureExecutable(adbPath), null, runner)

        fun adb(vararg arguments: String): CaptureCommandResult =
            tools.runAdb(serial, arguments.toList(), Duration.ofSeconds(5))
        assertEquals("1", adb("shell", "getprop", "sys.boot_completed").stdoutText().trim(), "Emulator must be booted before this test")
        val bootBefore = adb("shell", "cat", "/proc/sys/kernel/random/boot_id").stdoutText().trim()
        assertTrue(bootBefore.isNotBlank())
        val recorder = CaptureRecorder(File(evidence, "sessions"))
        try {
            val session = recorder.start(
                CaptureDevice(serial, "device"),
                CaptureSettings(
                    mirror = false,
                    recordVideo = true,
                    maxSize = 640,
                    maxFps = 10,
                    bitrateMbps = 1,
                    freeSpaceReserveBytes = 0,
                ),
                tools,
            )
            val laneDevice = TestDeviceSession.forCapture(serial, evidence, tools, StandaloneLaneCapture(recorder, session))
            val before = "before-${UUID.randomUUID()}"
            assertEquals(0, adb("shell", "log", "-t", "IndagiumRebootSmoke", before).exitCode)
            awaitSmoke("Initial log/video did not start", recorder) {
                recorder.snapshotForExport(session.id)
                session.logFile.readText().contains(before) && recorder.snapshot.value.videoRecording
            }
            val embedded = assertNotNull(recorder.activeEmbeddedSession())
            val packetsBeforeReboot = embedded.pathStats.muxedVideoPackets.get()
            assertEquals(0, adb("reboot").exitCode)
            awaitSmoke("Reboot did not produce an availability interval", recorder) {
                recorder.snapshot.value.session?.availabilityIntervals?.isNotEmpty() == true
            }
            assertEquals(RecorderState.RECORDING, recorder.snapshot.value.state)
            val ready = runBlocking { laneDevice.waitForDeviceReady(120_000) }
            assertEquals(true, ready["ready"], "Emulator did not become ready: $ready")
            assertEquals(true, ready["rebootConfirmed"], "AI readiness wait lost its boot identity evidence")
            assertEquals(RecorderState.RECORDING, recorder.snapshot.value.state)
            val after = "after-${UUID.randomUUID()}"
            assertEquals(0, adb("shell", "log", "-t", "IndagiumRebootSmoke", after).exitCode)
            awaitSmoke("Logcat did not resume after reboot", recorder) {
                recorder.snapshotForExport(session.id)
                session.logFile.readText().contains(after) &&
                    recorder.snapshot.value.session?.availabilityIntervals?.lastOrNull()?.endElapsedMs != null
            }
            awaitSmoke("Video did not resume after reboot", recorder) {
                embedded.pathStats.muxedVideoPackets.get() > packetsBeforeReboot &&
                    recorder.snapshot.value.diagnostics.any { "video gap" in it }
            }
            val bootAfter = adb("shell", "cat", "/proc/sys/kernel/random/boot_id").stdoutText().trim()
            assertTrue(bootAfter.isNotBlank() && bootAfter != bootBefore)
            val stopped = assertNotNull(recorder.stop())
            assertEquals(session.id, stopped.id)
            assertEquals(CaptureStatus.STOPPED, stopped.status)
            val log = stopped.logFile.readText()
            assertTrue(log.contains(before) && log.contains(after))
            assertTrue(log.contains("Device unavailable") && log.contains("Device reconnected"))
            assertTrue(stopped.availabilityIntervals.any { it.rebootConfirmed == true })
            FFmpegFrameGrabber(stopped.videoFile).use { rawVideo ->
                rawVideo.start()
                val lastRecordedElapsedMs = assertNotNull(stopped.videoStartElapsedMs) + rawVideo.lengthInTime / 1_000
                val outageEnd = assertNotNull(stopped.availabilityIntervals.last().endElapsedMs)
                assertTrue(lastRecordedElapsedMs >= outageEnd, "The resumed video must retain the entire reboot gap")
            }
            val exporter = CaptureArchiveExporter()
            exporter.finalizeSessionInPlace(stopped)
            val archive = File(evidence, "reboot.zip")
            exporter.export(stopped, CaptureExportRequest(archive, cutoffElapsedMs = stopped.elapsedMs))
            val imported = CaptureArchiveReader.open(archive, File(evidence, "opened"))
            assertEquals(4, imported.descriptor.formatVersion)
            assertTrue(imported.descriptor.availabilityIntervals.isNotEmpty())
            assertNotNull(imported.timeline)
            assertTrue(assertNotNull(imported.videoFile).length() > 0)
            val gap = stopped.availabilityIntervals.first { it.endElapsedMs != null }
            val gapEnd = assertNotNull(gap.endElapsedMs)
            val inset = (gapEnd - gap.startElapsedMs) / 4
            if (inset > 0) {
                val gapArchive = File(evidence, "offline-only.zip")
                exporter.export(
                    stopped,
                    CaptureExportRequest(
                        gapArchive,
                        range = CaptureRange.EXPLICIT,
                        cutoffElapsedMs = stopped.elapsedMs,
                        explicitStartElapsedMs = gap.startElapsedMs + inset,
                        explicitEndElapsedMs = gapEnd - inset,
                    ),
                )
                val gapImported = CaptureArchiveReader.open(gapArchive, File(evidence, "offline-only-opened"))
                assertTrue(assertNotNull(gapImported.videoFile).length() > 0, "Entirely offline range must have a playable card")
                assertTrue(gapImported.descriptor.timelineDurationMs > 0)
            }
            println("Emulator reboot smoke evidence: ${evidence.absolutePath}")
        } finally {
            recorder.close()
        }
    }

    private fun awaitSmoke(message: String, recorder: CaptureRecorder, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            assertEquals(RecorderState.RECORDING, recorder.snapshot.value.state, recorder.snapshot.value.diagnostics.joinToString("\n"))
            Thread.sleep(250)
        }
        error("$message\n${recorder.snapshot.value.diagnostics.joinToString("\n")}")
    }
}
