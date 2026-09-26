package com.indagium.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
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
    fun publishesStorageBeforeLaunchingAdb() {
        val root = Files.createTempDirectory("capture-order-test").toFile()
        val runner = FakeCaptureRunner()
        runner.enqueue(StreamingFakeProcess())
        val recorder = CaptureRecorder(root, runner)
        var callbackSession: CaptureSession? = null
        try {
            val started = recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner)) { session ->
                callbackSession = session
                assertTrue(session.logFile.isFile)
                assertEquals(0L, session.logFile.length())
                // The callback is the ordering seam: adb has not been spawned yet.
                assertTrue(runner.specs.isEmpty())
            }
            assertEquals(started.id, callbackSession?.id)
            assertEquals(1, runner.specs.size)
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aSecondRecorderCannotLeaveTwoSessionsRecording() {
        val root = Files.createTempDirectory("capture-single-live-test").toFile()
        val runner = FakeCaptureRunner()
        runner.enqueue(StreamingFakeProcess())
        runner.enqueue(StreamingFakeProcess())
        val first = CaptureRecorder(root, runner)
        val second = CaptureRecorder(root, runner)
        try {
            first.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            second.start(DEVICE.copy(serial = "SECOND"), testSettings(), CaptureTools(ADB, null, runner))
            val statuses = second.listSessions().associateBy { it.device.serial }
            assertEquals(CaptureStatus.INTERRUPTED, statuses.getValue(DEVICE.serial).status)
            assertEquals(CaptureStatus.RECORDING, statuses.getValue("SECOND").status)
        } finally {
            second.close()
            first.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun recordsRawBytesAndOneBasedDurableIndexWithoutRetainingPreviewRows() {
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
            assertEquals(
                listOf(1, 2),
                records.mapNotNull { it["rowOrdinal"]?.jsonPrimitive?.intOrNull },
            )
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun defaultLogcatStartsAtCurrentTailAndAlwaysSelectsTheDevice() {
        // DEFAULT buffer mode emits no -b at all (CaptureBufferMode.DEFAULT / logcatBufferArgs()):
        // this used to assert 3 hardcoded "-b" flags (main/system/crash), but that silently
        // dropped adb's real default buffer `kernel` and could error out on a device missing one
        // of the three named buffers. Passing no -b tracks whatever plain `adb logcat` does.
        val root = Files.createTempDirectory("capture-command-test").toFile()
        val runner = FakeCaptureRunner()
        runner.enqueue(StreamingFakeProcess())
        val recorder = CaptureRecorder(root, runner)
        try {
            // includeBufferedLogs defaults to true now, which drops "-T", "1" (see CaptureSettings'
            // own doc); this test is about the DEFAULT buffer-mode -b behavior, so it explicitly
            // asks for the old "start at current tail" flag instead of relying on the new default.
            recorder.start(DEVICE, testSettings().copy(includeBufferedLogs = false), CaptureTools(ADB, null, runner))
            val command = runner.specs.single().command
            assertEquals(listOf("adb", "-s", DEVICE.serial), command.take(3))
            assertTrue(command.containsAll(listOf("logcat", "-v", "threadtime", "-T", "1")))
            assertEquals(0, command.count { it == "-b" })
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun allBufferModeEmitsSingleAllFlag() {
        val root = Files.createTempDirectory("capture-command-all-test").toFile()
        val runner = FakeCaptureRunner()
        runner.enqueue(StreamingFakeProcess())
        val recorder = CaptureRecorder(root, runner)
        try {
            recorder.start(
                DEVICE,
                testSettings().copy(bufferMode = CaptureBufferMode.ALL),
                CaptureTools(ADB, null, runner),
            )
            val command = runner.specs.single().command
            assertEquals(listOf("all"), command.zipWithNext().filter { it.first == "-b" }.map { it.second })
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun customBufferModeEmitsOneFlagPairPerDistinctBuffer() {
        val root = Files.createTempDirectory("capture-command-custom-test").toFile()
        val runner = FakeCaptureRunner()
        runner.enqueue(StreamingFakeProcess())
        val recorder = CaptureRecorder(root, runner)
        try {
            recorder.start(
                DEVICE,
                testSettings().copy(bufferMode = CaptureBufferMode.CUSTOM, buffers = listOf("main", "crash", "main")),
                CaptureTools(ADB, null, runner),
            )
            val command = runner.specs.single().command
            assertEquals(
                listOf("main", "crash"),
                command.zipWithNext().filter { it.first == "-b" }.map { it.second },
            )
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

    @org.junit.Test(timeout = 20_000)
    fun videoFailureLeavesLogCaptureRunning() {
        // Recording no longer spawns host scrcpy (CaptureRecorder now owns the device stream via
        // EmbeddedDeviceSession/AdbScrcpyTransport — see that class's own unit tests for the
        // packet-level protocol/muxer behaviour). This exercises the recorder-level contract the
        // old host-scrcpy-launch-failure test used to cover: an embedded transport that can never
        // connect must not stop or interrupt log capture, and must surface a diagnostic instead.
        val root = Files.createTempDirectory("capture-video-failure-test").toFile()
        val runner = FakeCaptureRunner()
        val logcat = StreamingFakeProcess()
        runner.enqueue(logcat)
        val tools = CaptureTools(ADB, null, runner)
        val recorder = CaptureRecorder(
            root,
            runner,
            embeddedTransportFactory = { com.indagium.capture.mirror.EmbeddedMirrorTransport { _, _ -> error("embedded scrcpy server unavailable") } },
        )
        try {
            recorder.start(DEVICE, testSettings(recordVideo = true), tools)
            awaitCapture { recorder.snapshot.value.diagnostics.any { it.contains("Embedded recording", ignoreCase = true) } }
            logcat.emit("still logging\n")
            awaitCapture { recorder.snapshot.value.indexedRows == 1 }

            assertEquals(RecorderState.RECORDING, recorder.snapshot.value.state)
            assertFalse(recorder.snapshot.value.videoRecording)
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @org.junit.Test(timeout = 20_000)
    fun embeddedRecordingWritesVideoAndPersistsVideoStartElapsedMs() {
        val root = Files.createTempDirectory("capture-embedded-video-test").toFile()
        val runner = FakeCaptureRunner()
        val logcat = StreamingFakeProcess()
        runner.enqueue(logcat)
        val fixture = CaptureRecorderTestH264Fixture.encode()
        val videoBytes = java.io.ByteArrayOutputStream().also { out ->
            java.io.DataOutputStream(out).apply {
                writeInt(0x68_32_36_34) // ScrcpyCodecIds.H264
                writeInt(0x80000000.toInt())
                writeInt(64)
                writeInt(64)
                writeLong(1L shl 62)
                writeInt(fixture.config.size)
                write(fixture.config)
                writeLong(0L or (1L shl 61))
                writeInt(fixture.key.size)
                write(fixture.key)
            }
        }.toByteArray()
        val transport = com.indagium.capture.mirror.EmbeddedMirrorTransport { _, _ ->
            object : com.indagium.capture.mirror.EmbeddedMirrorConnection {
                override val videoInput: java.io.InputStream = java.io.ByteArrayInputStream(videoBytes)
                override val audioInput: java.io.InputStream? = null

                override fun sendControl(bytes: ByteArray) = Unit

                override fun close() = Unit
            }
        }
        val recorder = CaptureRecorder(root, runner, embeddedTransportFactory = { transport })
        try {
            val started = recorder.start(DEVICE, testSettings(recordVideo = true), CaptureTools(ADB, null, runner))
            awaitCapture { recorder.snapshot.value.videoRecording }
            awaitCapture { (recorder.selectedSession.value?.videoStartElapsedMs ?: -1L) >= 0L }

            assertTrue(started.videoFile.isFile)
            assertTrue(started.videoFile.length() > 0L, "the embedded muxer must have written real bytes to session.videoFile")
            assertNotNull(recorder.selectedSession.value?.videoStartElapsedMs)
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    /**
     * Regression test for a real lock-order deadlock caught during review: the embedded recording
     * session's video-writing thread called `onVideoStartElapsedMs`/`elapsedMillis`
     * (`CaptureRecorder.elapsedNow`, which took `CaptureRecorder`'s own lock) from *inside* the
     * session's own lock, while `CaptureRecorder`'s watchdog thread separately called
     * `EmbeddedDeviceSession.hasStartedVideo()` (which took the session's lock) from *inside*
     * `CaptureRecorder`'s lock — two objects each waiting on the other's lock while holding their
     * own. It reproduced as `EmbeddedMirrorAutostartTest` hanging for 68 minutes at 0% CPU with the
     * watchdog thread and a video-writing thread deadlocked, and `detachDecoder()`/`AppState.close`
     * blocked forever behind it.
     *
     * Uses a real (very short) watchdog interval — the watchdog's own publish cycle is one half of
     * the deadlock — a continuously-flowing fake video stream to keep `writeVideoFrame` firing
     * repeatedly, and `org.junit.Test`'s own `timeout` (not `kotlin.test.Test`, which has no such
     * parameter) so a regression fails this test outright instead of hanging the whole suite the
     * way the real bug did.
     */
    @org.junit.Test(timeout = 20_000)
    fun recordingPacketsWatchdogPublishesAndCloseNeverDeadlockConcurrently() {
        val root = Files.createTempDirectory("capture-deadlock-regression-test").toFile()
        val runner = FakeCaptureRunner()
        val logcat = StreamingFakeProcess()
        runner.enqueue(logcat)
        val fixture = CaptureRecorderTestH264Fixture.encode()
        val videoBytes = java.io.ByteArrayOutputStream().also { out ->
            java.io.DataOutputStream(out).apply {
                writeInt(0x68_32_36_34) // ScrcpyCodecIds.H264
                writeInt(0x80000000.toInt())
                writeInt(64)
                writeInt(64)
                writeLong(1L shl 62)
                writeInt(fixture.config.size)
                write(fixture.config)
            }
        }.toByteArray()
        // A video socket that keeps handing back fresh key-frame packets (same bytes repeated —
        // this test only stresses the lock interaction, it never decodes anything) roughly every
        // 2ms, so writeVideoFrame() keeps racing the watchdog's own publish cycle for the whole
        // test instead of firing once and going quiet.
        val continuousVideo = object : java.io.InputStream() {
            private var buffered = java.io.ByteArrayInputStream(videoBytes)
            private var index = 0

            override fun read(): Int {
                val single = ByteArray(1)
                val count = read(single, 0, 1)
                return if (count <= 0) -1 else single[0].toInt() and 0xff
            }

            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                var count = buffered.read(buffer, off, len)
                while (count < 0) {
                    Thread.sleep(2)
                    val packet = java.io.ByteArrayOutputStream().also { out ->
                        java.io.DataOutputStream(out).apply {
                            writeLong((index * 33_000L) or (1L shl 61))
                            writeInt(fixture.key.size)
                            write(fixture.key)
                        }
                    }.toByteArray()
                    index++
                    buffered = java.io.ByteArrayInputStream(packet)
                    count = buffered.read(buffer, off, len)
                }
                return count
            }
        }
        val transport = com.indagium.capture.mirror.EmbeddedMirrorTransport { _, _ ->
            object : com.indagium.capture.mirror.EmbeddedMirrorConnection {
                override val videoInput: java.io.InputStream = continuousVideo
                override val audioInput: java.io.InputStream? = null

                override fun sendControl(bytes: ByteArray) = Unit

                override fun close() = Unit
            }
        }
        val recorder = CaptureRecorder(
            root,
            runner,
            watchdogIntervalMs = 5L,
            embeddedTransportFactory = { transport },
        )
        try {
            val started = recorder.start(DEVICE, testSettings(recordVideo = true), CaptureTools(ADB, null, runner))
            awaitCapture { recorder.snapshot.value.videoRecording }
            // Let the watchdog and the video-writing thread race against each other for a while —
            // long enough that the original bug reliably deadlocked within this window.
            Thread.sleep(500)
            assertTrue(started.videoFile.length() > 0L)
        } finally {
            recorder.close() // must return — this is what hung for 68 minutes before the fix
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

    // Regression test for the "screenshot saved (could not add it to Notes)" bug: `screencap`
    // itself — not adb — writes a "[Warning] Multiple displays were found..." banner to stdout
    // ahead of the PNG bytes on any device/emulator with more than one display. Confirmed against
    // a real connected emulator: `adb exec-out screencap -p` returned that banner text followed by
    // a valid PNG. Left in place, the banner corrupts the PNG signature, ImageIO can't decode it,
    // and downscaleAndEncodeJpeg (built on ImageIO.read) returns null — the file on disk is
    // "saved" (its bytes are whatever adb returned), but it is not a valid image and can never be
    // attached to Notes.
    @Test
    fun screenshotStripsALeadingDeviceWarningBannerBeforeThePngSignature() {
        val root = Files.createTempDirectory("capture-screenshot-banner-test").toFile()
        val runner = FakeCaptureRunner()
        runner.enqueue(StreamingFakeProcess())
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
        val banner = "[Warning] Multiple displays were found, but no display id was specified!\n".toByteArray()
        runner.enqueue(CompletedFakeProcess(stdout = banner + png))
        val recorder = CaptureRecorder(root, runner)
        try {
            recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            val screenshot = recorder.screenshotCapture()

            assertTrue(png.contentEquals(screenshot.bytes), "the banner must be stripped from the in-memory bytes")
            assertTrue(png.contentEquals(screenshot.file.readBytes()), "the banner must also be stripped from the file written to disk")
            assertTrue(
                recorder.snapshot.value.diagnostics.any { it.contains("stripped") },
                "stripping a banner is unusual enough to surface as a diagnostic",
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
            assertEquals(1_200, updated.snapshotCheckpointMs)
            assertEquals(1_200, sessions.getValue(first.id).snapshotCheckpointMs)
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

    // Regression test for the video checkpoint persistence bug: sessionJson() never wrote
    // videoCheckpointMs (only the log-cursor-derived snapshotCheckpointMs), so
    // updateSuccessfulExportCheckpoints' in-memory video cursor was silently forgotten on the next
    // app restart even though CaptureArchiveExporter now depends on it surviving one (see
    // CaptureModels.effectiveVideoCheckpointMs and the Since-last-save video range in
    // CaptureArchive.kt). This proves both cursors round-trip through a fresh CaptureRecorder
    // reading the same session directory back from disk, independently of each other.
    @Test
    fun videoCheckpointSurvivesAReloadIndependentlyOfTheLogCheckpoint() {
        val root = Files.createTempDirectory("capture-video-checkpoint-persist-test").toFile()
        val runner = FakeCaptureRunner()
        runner.enqueue(StreamingFakeProcess())
        val recorder = CaptureRecorder(root, runner)
        try {
            val session = recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            recorder.stop()
            recorder.updateSuccessfulExportCheckpoints(session.id, logCheckpointMs = 5_000, videoCheckpointMs = 3_000)
            // A video-less export afterwards must not move the video cursor, log-only ones do.
            recorder.updateSuccessfulExportCheckpoints(session.id, logCheckpointMs = 7_000, videoCheckpointMs = null)

            val reloaded = CaptureRecorder(root, runner)
            try {
                val restored = reloaded.listSessions().single { it.id == session.id }
                assertEquals(7_000, restored.snapshotCheckpointMs)
                assertEquals(3_000, restored.videoCheckpointMs)
                assertEquals(3_000, restored.effectiveVideoCheckpointMs)
            } finally {
                reloaded.close()
            }
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

/** One real, tiny SPS/PPS + IDR NAL set (bundled libopenh264, same encoder
 * [FfmpegCaptureVideoExporter] uses), so [CaptureRecorderTest]'s fake embedded transport feeds
 * [StreamingMkvWriter] structurally valid extradata/H.264 the way [EmbeddedDeviceSessionTest]'s own
 * fixture does — StreamingMkvWriter's `avformat_write_header` rejects hand-rolled placeholder bytes. */
private object CaptureRecorderTestH264Fixture {
    data class Fixture(val config: ByteArray, val key: ByteArray)

    private val fixture: Fixture by lazy { encodeInternal() }

    fun encode(): Fixture = fixture

    @Suppress("MagicNumber", "CyclomaticComplexMethod")
    private fun encodeInternal(): Fixture {
        val width = 32
        val height = 32
        val raw = java.nio.file.Files.createTempFile("capture-recorder-h264-fixture-", ".h264").toFile().apply { deleteOnExit() }
        val pixels = java.nio.ByteBuffer.allocate(width * height * 3)
        val recorder = org.bytedeco.javacv.FFmpegFrameRecorder(raw, width, height, 0).apply {
            format = "h264"
            frameRate = 30.0
            videoCodec = org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
            videoCodecName = "libopenh264"
            videoBitrate = 300_000
            gopSize = 30
        }
        recorder.start()
        try {
            pixels.clear()
            repeat(width * height) { pixels.put(60).put(60).put(60) }
            pixels.flip()
            recorder.timestamp = 0
            recorder.recordImage(width, height, 8, 3, width * 3, org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24, pixels)
        } finally {
            recorder.stop()
            recorder.release()
        }
        val bytes = raw.readBytes()
        raw.delete()
        val starts = mutableListOf<Int>()
        var index = 0
        while (index + 3 < bytes.size) {
            val isFourByte = bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() &&
                bytes[index + 2] == 0.toByte() && index + 3 < bytes.size && bytes[index + 3] == 1.toByte()
            val isThreeByte = !isFourByte && bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() && bytes[index + 2] == 1.toByte()
            if (isFourByte || isThreeByte) {
                starts += index
                index += if (isFourByte) 4 else 3
            } else {
                index++
            }
        }
        val nalUnits = starts.indices.map { i ->
            val from = starts[i]
            val to = if (i + 1 < starts.size) starts[i + 1] else bytes.size
            bytes.copyOfRange(from, to)
        }
        val configUnits = mutableListOf<ByteArray>()
        var key: ByteArray? = null
        for (nal in nalUnits) {
            val start = if (nal.size >= 4 && nal[2] == 0.toByte() && nal[3] == 1.toByte()) 4 else 3
            when (nal[start].toInt() and 0x1f) {
                7, 8 -> configUnits += nal
                5 -> if (key == null) key = nal
            }
        }
        return Fixture(
            config = configUnits.reduce { acc, more -> acc + more },
            key = requireNotNull(key) { "fixture encode did not produce a key frame" },
        )
    }
}
