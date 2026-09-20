package com.indagium.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CaptureRecorderTest {
    @Test
    fun recordsRawBytesAndOneBasedDurableIndexBeforePublishingPreview() {
        val root = Files.createTempDirectory("capture-recorder-test").toFile()
        val runner = FakeCaptureRunner()
        val logcat = StreamingFakeProcess()
        runner.enqueue(logcat)
        val recorder = CaptureRecorder(root, runner)
        try {
            val session = recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            val raw = "--------- beginning of main\n\n01-02 03:04:05.006  100  101 I Tag: first\nsecond\n"
            logcat.emit(raw)
            awaitCapture { recorder.snapshot.value.indexedRows == 2 }

            val boundary = recorder.snapshotForExport(session.id)
            assertEquals(raw.toByteArray().size.toLong(), boundary.logLength)
            assertEquals(raw, session.logFile.readText())

            val records = session.indexFile.readLines().map { Json.parseToJsonElement(it).jsonObject }
            assertEquals(4, records.size)
            assertFalse(records[0].containsKey("rowOrdinal"))
            assertFalse(records[1].containsKey("rowOrdinal"))
            assertEquals(1, records[2].getValue("rowOrdinal").jsonPrimitive.int)
            assertEquals(2, records[3].getValue("rowOrdinal").jsonPrimitive.int)
            assertEquals(0L, records[0].getValue("byteOffset").jsonPrimitive.long)
            assertEquals(
                records[0].getValue("byteLength").jsonPrimitive.long,
                records[1].getValue("byteOffset").jsonPrimitive.long,
            )
            assertEquals(listOf(1, 2), recorder.snapshot.value.preview.mapNotNull { it.rowOrdinal })
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun defaultLogcatStartsAtCurrentTailAndAlwaysSelectsTheDevice() {
        val root = Files.createTempDirectory("capture-command-test").toFile()
        val runner = FakeCaptureRunner()
        runner.enqueue(StreamingFakeProcess())
        val recorder = CaptureRecorder(root, runner)
        try {
            recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            val command = runner.specs.single().command
            assertEquals(listOf("adb", "-s", DEVICE.serial), command.take(3))
            assertTrue(command.containsAll(listOf("logcat", "-v", "threadtime", "-T", "1")))
            assertEquals(3, command.count { it == "-b" })
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun disconnectInterruptsSessionAndPersistsRecoveryMetadata() {
        val root = Files.createTempDirectory("capture-disconnect-test").toFile()
        val runner = FakeCaptureRunner()
        val logcat = StreamingFakeProcess()
        runner.enqueue(logcat)
        val recorder = CaptureRecorder(root, runner)
        try {
            val started = recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            logcat.finish()
            awaitCapture { recorder.snapshot.value.state == RecorderState.INTERRUPTED }

            val recovered = recorder.listSessions().single()
            assertEquals(started.id, recovered.id)
            assertEquals(CaptureStatus.INTERRUPTED, recovered.status)
            assertTrue(recovered.interruptions.single().contains("disconnected"))
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun videoFailureLeavesLogCaptureRunning() {
        val root = Files.createTempDirectory("capture-video-failure-test").toFile()
        val runner = FakeCaptureRunner()
        val logcat = StreamingFakeProcess()
        runner.enqueue(logcat)
        runner.enqueue(CompletedFakeProcess("scrcpy 3.3.1\n"))
        runner.enqueue(
            CompletedFakeProcess(
                "--serial --record --record-format --max-size --max-fps " +
                    "--video-bit-rate --video-codec --no-audio\n",
            ),
        )
        runner.enqueue(CompletedFakeProcess(stdout = byteArrayOf(), stderr = "encoder failed".toByteArray(), code = 1))
        val tools = CaptureTools(ADB, CaptureExecutable("scrcpy"), runner)
        val recorder = CaptureRecorder(root, runner)
        try {
            recorder.start(DEVICE, testSettings(recordVideo = true), tools)
            awaitCapture { recorder.snapshot.value.diagnostics.any { it.contains("Video recording ended") } }
            logcat.emit("still logging\n")
            awaitCapture { recorder.snapshot.value.indexedRows == 1 }

            assertEquals(RecorderState.RECORDING, recorder.snapshot.value.state)
            assertFalse(recorder.snapshot.value.videoRecording)
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun screenshotUsesExecOutAndWritesPngWithoutTextConversion() {
        val root = Files.createTempDirectory("capture-screenshot-test").toFile()
        val runner = FakeCaptureRunner()
        runner.enqueue(StreamingFakeProcess())
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        runner.enqueue(CompletedFakeProcess(stdout = png))
        val recorder = CaptureRecorder(root, runner)
        try {
            recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            val screenshot = recorder.screenshot()

            assertTrue(png.contentEquals(screenshot.readBytes()))
            assertEquals(
                listOf("adb", "-s", DEVICE.serial, "exec-out", "screencap", "-p"),
                runner.specs.last().command,
            )
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun checkpointsUpdateTheRequestedRetainedSessionOnly() {
        val root = Files.createTempDirectory("capture-checkpoint-test").toFile()
        val runner = FakeCaptureRunner()
        runner.enqueue(StreamingFakeProcess())
        runner.enqueue(StreamingFakeProcess())
        val recorder = CaptureRecorder(root, runner)
        try {
            val first = recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            recorder.stop()
            val second = recorder.start(DEVICE.copy(serial = "SECOND"), testSettings(), CaptureTools(ADB, null, runner))
            recorder.stop()

            val updated = recorder.updateSuccessfulExportCheckpoints(first.id, 1_200, 900)
            val sessions = recorder.listSessions().associateBy { it.id }
            assertEquals(1_200, updated.logCheckpointMs)
            assertEquals(900, updated.videoCheckpointMs)
            assertEquals(1, updated.exportCounter)
            assertEquals(-1, sessions.getValue(second.id).logCheckpointMs)
            assertEquals(second.id, recorder.selectedSession.value?.id)
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun recoversRecordingSessionAsInterruptedAndAllowsDeletion() {
        val root = Files.createTempDirectory("capture-recovery-test").toFile()
        val runner = FakeCaptureRunner()
        runner.enqueue(StreamingFakeProcess())
        val recorder = CaptureRecorder(root, runner)
        try {
            val session = recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            recorder.stop()
            val metadata = session.directory.resolve("session.json")
            metadata.writeText(metadata.readText().replace("\"status\":\"STOPPED\"", "\"status\":\"RECORDING\""))

            val recovered = CaptureRecorder(root).recoverSessions().single()
            assertEquals(CaptureStatus.INTERRUPTED, recovered.status)
            assertTrue(recovered.interruptions.isNotEmpty())
            assertTrue(recorder.deleteSession(recovered.id))
            assertFalse(recovered.directory.exists())
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun sizeLimitStopsBeforeWritingAnOversizedLine() {
        val root = Files.createTempDirectory("capture-limit-test").toFile()
        val runner = FakeCaptureRunner()
        val logcat = StreamingFakeProcess()
        runner.enqueue(logcat)
        val recorder = CaptureRecorder(root, runner)
        try {
            val session = recorder.start(
                DEVICE,
                testSettings(sessionLimitBytes = 4),
                CaptureTools(ADB, null, runner),
            )
            logcat.emit("too large\n")
            awaitCapture { recorder.snapshot.value.state == RecorderState.INTERRUPTED }

            assertEquals(0L, session.logFile.length())
            assertTrue(recorder.snapshot.value.diagnostics.any { it.contains("size limit") })
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun manualOffsetPersistsAcrossRecorderInstances() {
        val root = Files.createTempDirectory("capture-offset-test").toFile()
        val runner = FakeCaptureRunner()
        runner.enqueue(StreamingFakeProcess())
        val recorder = CaptureRecorder(root, runner)
        try {
            val session = recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            recorder.stop()
            val updated = recorder.setManualOffset(session.id, -275)

            assertNotEquals(session.manualOffsetMs, updated.manualOffsetMs)
            assertEquals(-275, CaptureRecorder(root).listSessions().single().manualOffsetMs)
            assertNotNull(recorder.selectSession(session.id))
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun watchdogPersistsElapsedMetadataWithoutLogTraffic() {
        val root = Files.createTempDirectory("capture-watchdog-metadata-test").toFile()
        val runner = FakeCaptureRunner()
        runner.enqueue(StreamingFakeProcess())
        val recorder = CaptureRecorder(
            sessionsRoot = root,
            runner = runner,
            watchdogIntervalMs = 5,
            metadataPersistIntervalMs = 10,
        )
        try {
            val session = recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            awaitCapture { Json.parseToJsonElement(session.directory.resolve("session.json").readText())
                .jsonObject.getValue("elapsedMs").jsonPrimitive.long > 0 }
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun watchdogStopsCaptureWhenDiskReserveIsLostWithoutLogTraffic() {
        val root = Files.createTempDirectory("capture-watchdog-space-test").toFile()
        val runner = FakeCaptureRunner()
        runner.enqueue(StreamingFakeProcess())
        val checks = AtomicInteger()
        val recorder = CaptureRecorder(
            sessionsRoot = root,
            runner = runner,
            diskSpace = CaptureDiskSpace { if (checks.incrementAndGet() == 1) Long.MAX_VALUE else 0L },
            watchdogIntervalMs = 5,
            spaceCheckIntervalMs = 0,
        )
        try {
            recorder.start(
                DEVICE,
                testSettings().copy(freeSpaceReserveBytes = 1),
                CaptureTools(ADB, null, runner),
            )
            awaitCapture { recorder.snapshot.value.state == RecorderState.INTERRUPTED }
            assertTrue(recorder.snapshot.value.diagnostics.any { it.contains("free-space reserve") })
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun recoveryUsesLastDurableIndexElapsedWhenMetadataWasStale() {
        val root = Files.createTempDirectory("capture-recovery-duration-test").toFile()
        val runner = FakeCaptureRunner()
        val logcat = StreamingFakeProcess()
        runner.enqueue(logcat)
        var monotonicMs = 10L
        val recorder = CaptureRecorder(
            sessionsRoot = root,
            runner = runner,
            clock = CaptureClock { monotonicMs },
        )
        try {
            val session = recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            monotonicMs = 1_510L
            logcat.emit("line\n")
            awaitCapture { recorder.snapshot.value.indexedRows == 1 }
            recorder.stop()

            val metadata = session.directory.resolve("session.json")
            metadata.writeText(
                metadata.readText()
                    .replace(Regex("\"elapsedMs\":[0-9]+"), "\"elapsedMs\":0")
                    .replace("\"status\":\"STOPPED\"", "\"status\":\"RECORDING\""),
            )
            val recovered = CaptureRecorder(root).recoverSessions().single()
            assertEquals(1_500L, recovered.elapsedMs)
            assertEquals(CaptureStatus.INTERRUPTED, recovered.status)
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    private fun testSettings(
        recordVideo: Boolean = false,
        sessionLimitBytes: Long = ONE_MIB,
    ) = CaptureSettings(
        recordVideo = recordVideo,
        sessionLimitBytes = sessionLimitBytes,
        freeSpaceReserveBytes = 0,
    )

    private companion object {
        val DEVICE = CaptureDevice("SERIAL", "device", "Pixel")
        val ADB = CaptureExecutable("adb")
        const val ONE_MIB = 1024L * 1024L
    }
}
