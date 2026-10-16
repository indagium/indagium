package com.indagium.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
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

    // CaptureArchiveExporter must not read a growing MKV directly just because its status was
    // rewritten on disk: a recorder in this process that is still recording says so here.
    @Test
    fun aRecorderRegistersItsSessionAsLiveUntilItsStopFinished() {
        val root = Files.createTempDirectory("capture-live-registry-test").toFile()
        val runner = FakeCaptureRunner()
        runner.enqueue(StreamingFakeProcess())
        val recorder = CaptureRecorder(root, runner)
        try {
            val started = recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            assertTrue(LiveCaptureSessions.isLive(started.directory))

            val stopped = requireNotNull(recorder.stop())
            assertFalse(LiveCaptureSessions.isLive(stopped.directory))
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
    fun healthyLogcatEofRetriesWithTimestampCursorWithoutRecordingDeviceDowntime() {
        val root = Files.createTempDirectory("capture-logcat-reconnect-test").toFile()
        val runner = FakeCaptureRunner()
        val disconnected = StreamingFakeProcess()
        val reconnected = StreamingFakeProcess()
        runner.enqueue(disconnected)
        runner.enqueue(reconnected)
        val recorder = CaptureRecorder(root, runner, reconnectBaseDelayMs = 1)
        try {
            val session = recorder.startWithDeviceLoggingConfiguration(
                DEVICE,
                testSettings(),
                CaptureTools(ADB, null, runner),
                testDeviceLoggingConfiguration(),
            )
            val boundary = "01-02 03:04:05.006  100  101 I Tag: first overlap\n" +
                "01-02 03:04:06.007  100  101 I Tag: second overlap\n"
            disconnected.emit(boundary)
            awaitCapture { recorder.snapshot.value.indexedRows == 2 }
            disconnected.finish()
            awaitCapture { runner.specs.size >= 2 }
            reconnected.emit(boundary + "01-02 03:04:07.008  100  101 I Tag: after reconnect\n")
            awaitCapture { session.logFile.readText().contains("after reconnect") }

            assertEquals(RecorderState.RECORDING, recorder.snapshot.value.state)
            assertEquals(1, session.logFile.readText().windowed("first overlap".length).count { it == "first overlap" })
            assertEquals(1, session.logFile.readText().windowed("second overlap".length).count { it == "second overlap" })
            assertEquals(1, session.logFile.readText().windowed("after reconnect".length).count { it == "after reconnect" })
            assertTrue(recorder.snapshot.value.session!!.availabilityIntervals.isEmpty())
            assertTrue(runner.specs[1].command.containsAll(listOf("-T", "01-02 03:04:06.007")))
            assertFalse(runner.runSpecs.any(::isDeviceLoggingRestoreCommand), "a healthy same-boot EOF must not reapply device settings")
            assertTrue(session.logFile.readText().contains("after reconnect"))
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun yearWrappedReconnectDropsOldDecemberRowsAndKeepsNewJanuaryRows() {
        val root = Files.createTempDirectory("capture-year-wrap-reconnect-test").toFile()
        val runner = FakeCaptureRunner()
        val disconnected = StreamingFakeProcess()
        val reconnected = StreamingFakeProcess()
        runner.enqueue(disconnected)
        runner.enqueue(reconnected)
        val recorder = CaptureRecorder(root, runner, reconnectBaseDelayMs = 1)
        val december = "12-31 23:59:59.900  100  101 I Tag: previous year row\n"
        val januaryBoundary = "01-01 00:00:00.050  100  101 I Tag: boundary row\n"
        try {
            val session = recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            disconnected.emit(december + januaryBoundary)
            awaitCapture { recorder.snapshot.value.indexedRows == 2 }
            disconnected.finish()
            awaitCapture { runner.specs.size >= 2 }
            reconnected.emit(december + januaryBoundary +
                "01-01 00:00:00.051  100  101 I Tag: after year wrap\n")
            awaitCapture { session.logFile.readText().contains("after year wrap") }

            val text = session.logFile.readText()
            assertEquals(1, text.windowed("previous year row".length).count { it == "previous year row" })
            assertEquals(1, text.windowed("boundary row".length).count { it == "boundary row" })
            assertEquals(1, text.windowed("after year wrap".length).count { it == "after year wrap" })
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun rebootReconnectKeepsEarlyBootBufferAndConfirmsIdentityChange() {
        val root = Files.createTempDirectory("capture-reboot-buffer-test").toFile()
        val runner = FakeCaptureRunner()
        val initial = StreamingFakeProcess()
        val returned = StreamingFakeProcess()
        runner.enqueue(initial)
        runner.enqueue(returned)
        val recorder = CaptureRecorder(root, runner, reconnectBaseDelayMs = 1)
        try {
            val session = recorder.startWithDeviceLoggingConfiguration(
                DEVICE,
                testSettings(),
                CaptureTools(ADB, null, runner),
                testDeviceLoggingConfiguration(),
            )
            initial.emit("01-02 03:04:05.006  100  101 I Tag: before reboot\n")
            awaitCapture { recorder.snapshot.value.indexedRows == 1 }
            runner.adbState = "offline"
            initial.finish()
            awaitCapture { recorder.snapshot.value.session?.availabilityIntervals?.isNotEmpty() == true }
            runner.bootId = "replacement-boot-id"
            runner.adbState = "device"
            awaitCapture { runner.specs.size >= 2 }
            returned.emit("01-02 03:04:05.006  100  101 I Tag: before reboot\n" +
                "01-02 03:04:06.007  100  101 I Tag: early boot buffered\n")
            awaitCapture { session.logFile.readText().contains("early boot buffered") }

            val interval = recorder.snapshot.value.session!!.availabilityIntervals.single()
            assertNotNull(interval.endElapsedMs)
            assertEquals(true, interval.rebootConfirmed)
            assertEquals(2, session.logFile.readText().windowed("before reboot".length).count { it == "before reboot" })
            assertTrue(session.logFile.readText().contains("early boot buffered"))
            val resumedCommand = runner.specs[1].command
            assertFalse(resumedCommand.contains("-T"), "a new boot must retain its logcat ring buffer")
            awaitCapture { runner.runSpecs.count(::isDeviceLoggingRestoreCommand) >= 4 }
            assertEquals(2, runner.runSpecs.count { "-G" in it.command }, "each captured buffer size is restored")
            assertTrue(runner.runSpecs.any { it.command.any { argument -> "setprop 'log.tag' ''" in argument } })
            assertTrue(runner.runSpecs.any { it.command.any { argument -> "setprop 'log.tag.FOO' 'D'" in argument } })
            assertEquals(RecorderState.RECORDING, recorder.snapshot.value.state)
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun bootRecoveryWaitsForBootAndRepairsLateEmulatorBufferResetWhileStreamingContinues() {
        val root = Files.createTempDirectory("capture-emulator-buffer-bootstrap-test").toFile()
        val runner = FakeCaptureRunner()
        val initial = StreamingFakeProcess()
        val returned = StreamingFakeProcess()
        runner.enqueue(initial)
        runner.enqueue(returned)
        val savedSizes = linkedMapOf(
            "main" to FOUR_MIB,
            "radio" to FOUR_MIB,
            "events" to FOUR_MIB,
            "system" to FOUR_MIB,
            "crash" to FOUR_MIB,
            "stats" to FOUR_MIB,
            "security" to FOUR_MIB,
            "kernel" to FOUR_MIB,
        )
        runner.setFakeBufferSizes(savedSizes)
        runner.globalLogTagValue = "D\n"
        runner.getpropListing = "[log.tag.FOO]: [D]\n"
        runner.bootCompletedValue = "0"
        var resetAtNanos = 0L
        var resetApplied = false
        val defaultSizedBuffers = mapOf(
            "main" to TWO_MIB,
            "system" to TWO_MIB,
            "crash" to TWO_MIB,
            "kernel" to TWO_MIB,
        )
        runner.runHandler = { spec ->
            val args = spec.command
            if (args.contains("-G") && args.getOrNull(args.indexOf("-b") + 1) == "kernel" && resetAtNanos == 0L) {
                // Model the emulator host callback after boot-complete has already been reported.
                resetAtNanos = System.nanoTime() + 80_000_000L
            }
            if (args.takeLast(4) == listOf("logcat", "-b", "all", "-g")) {
                if (!resetApplied && resetAtNanos != 0L && System.nanoTime() >= resetAtNanos) {
                    runner.setFakeBufferSizes(defaultSizedBuffers)
                    resetApplied = true
                }
                CaptureCommandResult(0, runner.bufferListing.toByteArray(), byteArrayOf())
            } else null
        }
        val recorder = CaptureRecorder(
            root,
            runner,
            reconnectBaseDelayMs = 1,
            loggingRestoreStabilityWindowMs = 300,
            loggingRestoreSampleIntervalMs = 25,
            loggingRestoreBootWaitMs = 2_000,
        )
        val liveRow = "01-02 03:04:06.007  100  101 I Tag: log while boot waits\n"
        try {
            val session = recorder.startWithDeviceLoggingConfiguration(
                DEVICE,
                testSettings(),
                CaptureTools(ADB, null, runner),
                DeviceLoggingConfiguration(savedSizes, "D", mapOf("FOO" to "D")),
            )
            runner.adbState = "offline"
            initial.finish()
            awaitCapture { recorder.snapshot.value.session?.availabilityIntervals?.isNotEmpty() == true }
            runner.bootId = "emulator-bootstrap-boot"
            runner.setFakeBufferSizes(defaultSizedBuffers)
            runner.adbState = "device"
            awaitCapture { runner.specs.size >= 2 }

            returned.emit(liveRow)
            awaitCapture { session.logFile.readText().contains("log while boot waits") }
            Thread.sleep(80)
            assertFalse(runner.runSpecs.any { "-G" in it.command }, "buffer restoration must wait for Android boot completion")

            runner.bootCompletedValue = "1"
            awaitCapture {
                recorder.snapshot.value.diagnostics.any { it == "Device logging settings were restored and verified after restart." }
            }

            val finalSizes = parseLogcatBufferSizes(runner.bufferListing).associate { it.buffer to it.sizeBytes }
            assertTrue(savedSizes.all { (buffer, size) -> finalSizes[buffer] == size }, "saved buffer sizes should survive the emulator's later 2 MiB initialization")
            assertTrue(resetApplied, "the fake emulator should perform its delayed post-boot reset")
            assertTrue(runner.runSpecs.count { "-G" in it.command } >= savedSizes.size + defaultSizedBuffers.size)
            assertTrue(session.logFile.readText().contains("log while boot waits"), "recovery must keep streaming while boot initialization settles")
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun restartRestoreUsesLatestConfigurationAndStopCancelsBootWait() {
        val root = Files.createTempDirectory("capture-restore-latest-config-test").toFile()
        val runner = FakeCaptureRunner()
        val initial = StreamingFakeProcess()
        val returned = StreamingFakeProcess()
        runner.enqueue(initial)
        runner.enqueue(returned)
        runner.bootCompletedValue = "0"
        val recorder = CaptureRecorder(
            root,
            runner,
            reconnectBaseDelayMs = 1,
            loggingRestoreStabilityWindowMs = 100,
            loggingRestoreSampleIntervalMs = 20,
            loggingRestoreBootWaitMs = 2_000,
        )
        val latestSizes = mapOf("main" to TWO_MIB)
        try {
            recorder.startWithDeviceLoggingConfiguration(
                DEVICE,
                testSettings(),
                CaptureTools(ADB, null, runner),
                DeviceLoggingConfiguration(mapOf("main" to ONE_MIB), "", emptyMap()),
            )
            runner.adbState = "offline"
            initial.finish()
            awaitCapture { recorder.snapshot.value.session?.availabilityIntervals?.isNotEmpty() == true }
            runner.bootId = "latest-config-recovery-boot"
            runner.adbState = "device"
            awaitCapture { runner.specs.size >= 2 }
            awaitCapture { runner.runSpecs.any { it.command.takeLast(3) == listOf("shell", "getprop", "sys.boot_completed") } }

            recorder.updateDeviceLoggingConfiguration(
                DEVICE.serial,
                DeviceLoggingConfiguration(latestSizes, "D", mapOf("FOO" to "I")),
            )
            runner.bootCompletedValue = "1"
            awaitCapture { recorder.snapshot.value.diagnostics.any { it == "Device logging settings were restored and verified after restart." } }

            assertTrue(runner.runSpecs.any { it.command.takeLast(2) == listOf("-G", TWO_MIB.toString()) })
            assertFalse(runner.runSpecs.any { it.command.takeLast(2) == listOf("-G", ONE_MIB.toString()) }, "the pre-wait session snapshot must not overwrite a newer saved size")
            assertEquals("D\n", runner.globalLogTagValue)
            assertTrue(runner.getpropListing.contains("[log.tag.FOO]: [I]"))
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun stopCancelsRecoveryWhileWaitingForBootCompletion() {
        val root = Files.createTempDirectory("capture-restore-boot-wait-stop-test").toFile()
        val runner = FakeCaptureRunner()
        val initial = StreamingFakeProcess()
        val returned = StreamingFakeProcess()
        runner.enqueue(initial)
        runner.enqueue(returned)
        runner.bootCompletedValue = "0"
        val recorder = CaptureRecorder(
            root,
            runner,
            reconnectBaseDelayMs = 1,
            loggingRestoreStabilityWindowMs = 100,
            loggingRestoreSampleIntervalMs = 20,
            loggingRestoreBootWaitMs = 2_000,
        )
        try {
            recorder.startWithDeviceLoggingConfiguration(
                DEVICE,
                testSettings(),
                CaptureTools(ADB, null, runner),
                testDeviceLoggingConfiguration(),
            )
            runner.adbState = "offline"
            initial.finish()
            awaitCapture { recorder.snapshot.value.session?.availabilityIntervals?.isNotEmpty() == true }
            runner.bootId = "stop-during-boot-wait"
            runner.adbState = "device"
            awaitCapture { runner.specs.size >= 2 }
            awaitCapture { runner.runSpecs.any { it.command.takeLast(3) == listOf("shell", "getprop", "sys.boot_completed") } }

            val stopped = requireNotNull(recorder.stop())
            assertEquals(CaptureStatus.STOPPED, stopped.status)
            assertFalse(runner.runSpecs.any { "-G" in it.command }, "Stop must cancel recovery before it resizes any buffers")
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun sameIdentityUptimeRewindRestoresSettingsAndKeepsRepeatedRowsBeyondThe256LineOverlap() {
        val root = Files.createTempDirectory("capture-quickboot-overlap-test").toFile()
        val runner = FakeCaptureRunner()
        val initial = StreamingFakeProcess()
        val returned = StreamingFakeProcess()
        runner.enqueue(initial)
        runner.enqueue(returned)
        val recorder = CaptureRecorder(root, runner, reconnectBaseDelayMs = 1)
        val repeated = "01-02 03:04:05.006  100  101 I Tag: same timestamp repeated\n"
        try {
            val session = recorder.startWithDeviceLoggingConfiguration(
                DEVICE,
                testSettings(),
                CaptureTools(ADB, null, runner),
                testDeviceLoggingConfiguration(),
            )
            initial.emit(repeated.repeat(300))
            awaitCapture { recorder.snapshot.value.indexedRows == 300 }
            runner.bufferedLogcatOutput = repeated.repeat(344)
            runner.bootUptimeMs = 1_000L // Same boot id, but corroborated QuickBoot uptime rewind.
            initial.finish()
            awaitCapture {
                runner.specs.size >= 2 &&
                    session.logFile.readText().windowed("same timestamp repeated".length)
                        .count { it == "same timestamp repeated" } == 344
            }
            // The replacement stream replays the inspected ring, then one genuinely new
            // identical event. Ring-sequence filtering must preserve that one.
            Thread.sleep(40)
            returned.emit(repeated.repeat(345))
            awaitCapture {
                session.logFile.readText().windowed("same timestamp repeated".length)
                    .count { it == "same timestamp repeated" } == 345
            }
            awaitCapture { runner.runSpecs.count(::isDeviceLoggingRestoreCommand) >= 4 }

            val interval = recorder.snapshot.value.session!!.availabilityIntervals.single()
            assertEquals(false, interval.rebootConfirmed, "same boot id is not reported as confirmed reboot")
            assertEquals(RecorderState.RECORDING, recorder.snapshot.value.state)
            assertFalse(runner.specs[1].command.contains("-T"), "a same-ID uptime rewind must not reuse its old timestamp cursor")
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun sameIdentityWallClockRollbackDetectsQuickBootWhenUptimeContinues() {
        val root = Files.createTempDirectory("capture-quickboot-wall-clock-test").toFile()
        val runner = FakeCaptureRunner()
        val initial = StreamingFakeProcess()
        val returned = StreamingFakeProcess()
        runner.enqueue(initial)
        runner.enqueue(returned)
        var monotonicMs = 1_000L
        var deviceWallClock = "01-02T03:04:05"
        runner.runHandler = { spec ->
            when {
                spec.command.takeLast(4) == listOf("shell", "date", "-u", "+%m-%dT%H:%M:%S") ->
                    CaptureCommandResult(0, deviceWallClock.toByteArray(), byteArrayOf())
                spec.command.takeLast(3) == listOf("shell", "cat", "/proc/uptime") -> {
                    val uptimeSeconds = 10_000.0 + (monotonicMs - 1_000L) / 1_000.0
                    CaptureCommandResult(0, "$uptimeSeconds 0.00\n".toByteArray(), byteArrayOf())
                }
                else -> null
            }
        }
        // Uptime advances in lockstep with the monotonic clock, proving the restart is found from
        // the device wall clock alone.
        val recorder = CaptureRecorder(
            root,
            runner,
            clock = CaptureClock { monotonicMs },
            reconnectBaseDelayMs = 1,
        )
        val startup = "01-02 03:04:05.006  297  297 I vold: Vold 3.0 (the awakening) firing up\n"
        val early = "01-02 03:04:05.007  100  101 I Tag: early boot buffered\n"
        val live = "01-02 03:04:05.008  100  101 I Tag: after QuickBoot\n"
        try {
            val session = recorder.start(
                DEVICE,
                testSettings().copy(includeBufferedLogs = true),
                CaptureTools(ADB, null, runner),
            )
            initial.emit(startup)
            awaitCapture { recorder.snapshot.value.indexedRows == 1 }
            runner.bufferedLogcatOutput = startup + early
            monotonicMs += 10_000L
            deviceWallClock = "01-02T03:00:00" // Same boot id and advancing uptime, restored clock is older.
            initial.finish()
            awaitCapture { runner.specs.size >= 2 && session.logFile.readText().contains("early boot buffered") }

            assertFalse(runner.specs[1].command.contains("-T"), "a wall-clock rollback must avoid the stale cursor")
            returned.emit(startup + early + live)
            awaitCapture { session.logFile.readText().contains("after QuickBoot") }
            val text = session.logFile.readText()
            assertEquals(1, text.windowed("firing up".length).count { it == "firing up" })
            assertEquals(1, text.windowed("early boot buffered".length).count { it == "early boot buffered" })
            assertEquals(1, text.windowed("after QuickBoot".length).count { it == "after QuickBoot" })
            assertEquals(false, recorder.snapshot.value.session!!.availabilityIntervals.single().rebootConfirmed)
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun quickbootGroupedBufferReplayDeduplicatesRowsBeyondTheRecentSuffixAndKeepsNewOccurrences() {
        val root = Files.createTempDirectory("capture-quickboot-grouped-buffer-test").toFile()
        val runner = FakeCaptureRunner()
        val initial = StreamingFakeProcess()
        val returned = StreamingFakeProcess()
        runner.enqueue(initial)
        runner.enqueue(returned)
        val recorder = CaptureRecorder(root, runner, reconnectBaseDelayMs = 1)
        val repeat = "01-02 03:04:11.000  100  101 I Main: same timestamp repeated\n"
        fun row(millisAfterStart: Int, pid: Int, tag: String): String {
            val second = 10 + millisAfterStart / 1_000
            val millis = (millisAfterStart % 1_000).toString().padStart(3, '0')
            val secondText = second.toString().padStart(2, '0')
            return "01-02 03:04:$secondText.$millis  $pid  101 I $tag: captured row $millisAfterStart\n"
        }
        val systemRows = (0 until 300).map { row(it * 3, 110, "System") }
        val crashRows = (0 until 300).map { row(it * 3 + 1, 120, "Crash") }
        val mainRows = (0 until 300).map { row(it * 3 + 2, 130, "Main") }
        val liveSnapshot = buildString {
            for (index in 0 until 300) {
                append(systemRows[index])
                append(crashRows[index])
                append(mainRows[index])
            }
            append(repeat.repeat(300))
        }
        val bufferedSnapshot = buildString {
            append("--------- beginning of system\n")
            append(systemRows.joinToString(separator = ""))
            append("--------- beginning of crash\n")
            append(crashRows.joinToString(separator = ""))
            append("--------- beginning of main\n")
            append(mainRows.joinToString(separator = ""))
            append(repeat.repeat(344))
            append("01-02 03:04:11.001  130  131 I Main: buffered after QuickBoot\n")
        }
        try {
            val session = recorder.start(
                DEVICE,
                testSettings().copy(includeBufferedLogs = true),
                CaptureTools(ADB, null, runner),
            )
            initial.emit(liveSnapshot)
            assertFalse(runner.specs.single().command.contains("-T"), "Include earlier logs starts with the saved buffer")
            awaitCapture { recorder.snapshot.value.indexedRows == 1_200 }
            runner.bufferedLogcatOutput = bufferedSnapshot
            runner.bootUptimeMs = 1_000L
            initial.finish()
            awaitCapture {
                runner.specs.size >= 2 &&
                    session.logFile.readText().windowed("same timestamp repeated".length)
                        .count { it == "same timestamp repeated" } == 344
            }

            // The replacement stream reports the same saved rows grouped in another order, then
            // emits one truly new event whose raw row happens to match the prior repeated row.
            val interleavedReplay = buildString {
                append("--------- beginning of main\n")
                append(mainRows.joinToString(separator = ""))
                append(repeat.repeat(344))
                append("--------- beginning of system\n")
                append(systemRows.joinToString(separator = ""))
                append("--------- beginning of crash\n")
                append(crashRows.joinToString(separator = ""))
                append("01-02 03:04:11.001  130  131 I Main: buffered after QuickBoot\n")
                append(repeat)
                append("01-02 03:04:11.002  130  132 I Main: live after QuickBoot\n")
            }
            returned.emit(interleavedReplay)
            awaitCapture { session.logFile.readText().contains("live after QuickBoot") }

            val text = session.logFile.readText()
            assertEquals(1, text.windowed("captured row 0".length).count { it == "captured row 0" })
            assertEquals(1, text.windowed("captured row 897".length).count { it == "captured row 897" })
            assertEquals(345, text.windowed("same timestamp repeated".length).count { it == "same timestamp repeated" })
            assertEquals(1, text.windowed("buffered after QuickBoot".length).count { it == "buffered after QuickBoot" })
            assertEquals(1, text.windowed("live after QuickBoot".length).count { it == "live after QuickBoot" })
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun quickbootReplayOverflowScansOnlyDrainCandidatesAndCarriesOldQuotasIntoStreamReplay() {
        val root = Files.createTempDirectory("capture-quickboot-overflow-scan-test").toFile()
        val runner = FakeCaptureRunner()
        val initial = StreamingFakeProcess()
        val returned = StreamingFakeProcess()
        runner.enqueue(initial)
        runner.enqueue(returned)
        // Force FIFO eviction with a tiny history cap while keeping the production defaults
        // unchanged; the file-scan fallback must recover old quiet-buffer rows from disk.
        val recorder = CaptureRecorder(
            root,
            runner,
            reconnectBaseDelayMs = 1,
            replayHistoryByteLimitForTests = 128,
        )
        val quietRows = (0 until 5).map { index ->
            "01-02 03:04:00.${index.toString().padStart(3, '0')}  297  297 I vold: quiet startup row $index\n"
        }
        val busyRows = (0 until 80).map { index ->
            "01-02 03:04:01.${index.toString().padStart(3, '0')}  400  401 I Main: recent busy row $index\n"
        }
        val repeat = "01-02 03:04:02.000  400  401 I Main: repeated exact event\n"
        val buffered = "01-02 03:04:02.001  400  402 I Main: buffered after QuickBoot\n"
        val live = "01-02 03:04:02.002  400  403 I Main: live after QuickBoot\n"
        val priorInterleaved = buildString {
            for (index in busyRows.indices) {
                if (index < quietRows.size) append(quietRows[index])
                append(busyRows[index])
            }
            append(repeat.repeat(2))
        }
        val groupedDrain = buildString {
            append("--------- beginning of system\n")
            append(quietRows.joinToString(separator = ""))
            append("--------- beginning of main\n")
            append(busyRows.joinToString(separator = ""))
            append(repeat.repeat(5))
            append(buffered)
        }
        val groupedStreamReplay = buildString {
            append("--------- beginning of main\n")
            append(busyRows.joinToString(separator = ""))
            append(repeat.repeat(6)) // five buffered occurrences plus one new identical event
            append("--------- beginning of system\n")
            append(quietRows.joinToString(separator = ""))
            append(buffered)
            append(live)
        }
        try {
            val session = recorder.startWithDeviceLoggingConfiguration(
                DEVICE,
                testSettings().copy(includeBufferedLogs = true),
                CaptureTools(ADB, null, runner),
                testDeviceLoggingConfiguration(),
            )
            initial.emit(priorInterleaved)
            awaitCapture { recorder.snapshot.value.indexedRows == quietRows.size + busyRows.size + 2 }
            runner.bufferedLogcatOutput = groupedDrain
            runner.bootUptimeMs = 1_000L
            initial.finish()
            awaitCapture {
                runner.specs.size >= 2 &&
                    session.logFile.readText().contains("buffered after QuickBoot")
            }

            returned.emit(groupedStreamReplay)
            awaitCapture { session.logFile.readText().contains("live after QuickBoot") }
            val text = session.logFile.readText()
            assertEquals(1, text.windowed("quiet startup row 0".length).count { it == "quiet startup row 0" })
            assertEquals(1, text.windowed("quiet startup row 4".length).count { it == "quiet startup row 4" })
            assertEquals(1, text.windowed("recent busy row 0".length).count { it == "recent busy row 0" })
            assertEquals(1, text.windowed("recent busy row 79".length).count { it == "recent busy row 79" })
            assertEquals(6, text.windowed("repeated exact event".length).count { it == "repeated exact event" })
            assertEquals(1, text.windowed("buffered after QuickBoot".length).count { it == "buffered after QuickBoot" })
            assertEquals(1, text.windowed("live after QuickBoot".length).count { it == "live after QuickBoot" })
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun failedDeviceLoggingRestoreWarnsAndLeavesCaptureRunning() {
        val root = Files.createTempDirectory("capture-logging-restore-denied-test").toFile()
        val runner = FakeCaptureRunner()
        val initial = StreamingFakeProcess()
        val returned = StreamingFakeProcess()
        runner.enqueue(initial)
        runner.enqueue(returned)
        val recorder = CaptureRecorder(root, runner, reconnectBaseDelayMs = 1)
        try {
            recorder.startWithDeviceLoggingConfiguration(
                DEVICE,
                testSettings(),
                CaptureTools(ADB, null, runner),
                testDeviceLoggingConfiguration(),
            )
            runner.failRestoreCommands = true
            runner.adbState = "offline"
            initial.finish()
            awaitCapture { recorder.snapshot.value.session?.availabilityIntervals?.isNotEmpty() == true }
            runner.bootId = "replacement-boot-id"
            runner.adbState = "device"
            awaitCapture { runner.specs.size >= 2 }
            awaitCapture {
                recorder.snapshot.value.diagnostics.any { "Android denied permission" in it }
            }

            assertEquals(RecorderState.RECORDING, recorder.snapshot.value.state)
            assertEquals(CaptureStatus.RECORDING, recorder.snapshot.value.session?.status)
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun failedCurrentOverrideReadWarnsEvenWhenSavedOverrideMapIsKnownEmpty() {
        val root = Files.createTempDirectory("capture-empty-override-restore-test").toFile()
        val runner = FakeCaptureRunner()
        val initial = StreamingFakeProcess()
        val returned = StreamingFakeProcess()
        runner.enqueue(initial)
        runner.enqueue(returned)
        val recorder = CaptureRecorder(root, runner, reconnectBaseDelayMs = 1)
        try {
            recorder.startWithDeviceLoggingConfiguration(
                DEVICE,
                testSettings(),
                CaptureTools(ADB, null, runner),
                DeviceLoggingConfiguration(bufferSizesBytes = emptyMap(), perTagOverrides = emptyMap()),
            )
            runner.runHandler = { spec ->
                if (spec.command.takeLast(2) == listOf("shell", "getprop")) {
                    CaptureCommandResult(1, byteArrayOf(), "getprop failed".toByteArray())
                } else null
            }
            runner.adbState = "offline"
            initial.finish()
            awaitCapture { recorder.snapshot.value.session?.availabilityIntervals?.isNotEmpty() == true }
            runner.bootId = "replacement-boot-id"
            runner.adbState = "device"
            awaitCapture { runner.specs.size >= 2 }
            awaitCapture {
                recorder.snapshot.value.diagnostics.any { "Could not read current per-tag overrides" in it }
            }

            assertEquals(RecorderState.RECORDING, recorder.snapshot.value.state)
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun stopInterruptsAnInFlightLoggingRestoreBeforeItCanIssueMoreCommands() {
        val root = Files.createTempDirectory("capture-logging-restore-stop-test").toFile()
        val runner = FakeCaptureRunner()
        val initial = StreamingFakeProcess()
        val returned = StreamingFakeProcess()
        runner.enqueue(initial)
        runner.enqueue(returned)
        val restoreEntered = CountDownLatch(1)
        val commandInterrupted = AtomicBoolean(false)
        val restoreCommandCount = AtomicInteger(0)
        val recorder = CaptureRecorder(root, runner, reconnectBaseDelayMs = 1)
        try {
            recorder.startWithDeviceLoggingConfiguration(
                DEVICE,
                testSettings(),
                CaptureTools(ADB, null, runner),
                testDeviceLoggingConfiguration(),
            )
            runner.runHandler = { spec ->
                if (spec.command.any { "setprop" in it }) {
                    restoreCommandCount.incrementAndGet()
                    restoreEntered.countDown()
                    try {
                        CountDownLatch(1).await()
                    } catch (failure: InterruptedException) {
                        commandInterrupted.set(true)
                        throw failure
                    }
                }
                null
            }
            runner.adbState = "offline"
            initial.finish()
            awaitCapture { recorder.snapshot.value.session?.availabilityIntervals?.isNotEmpty() == true }
            runner.bootId = "replacement-boot-id"
            runner.adbState = "device"
            assertTrue(restoreEntered.await(5, TimeUnit.SECONDS), "the restore worker did not reach its bounded adb call")

            val stopped = requireNotNull(recorder.stop())
            Thread.sleep(40)
            assertEquals(CaptureStatus.STOPPED, stopped.status)
            assertTrue(commandInterrupted.get(), "Stop should interrupt the in-flight adb restore command")
            assertEquals(1, restoreCommandCount.get(), "no restore commands may start after Stop")
            assertFalse(stopped.interruptions.any { "could not be restored" in it })
            assertFalse(recorder.snapshot.value.diagnostics.any { "could not be restored" in it })
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun cappedReconnectDrainIndexesOnlyCompleteRowsAndResumesFromTheNewSafeCursor() {
        val root = Files.createTempDirectory("capture-capped-drain-test").toFile()
        val runner = FakeCaptureRunner()
        val initial = StreamingFakeProcess()
        val returned = StreamingFakeProcess()
        runner.enqueue(initial)
        runner.enqueue(returned)
        val recorder = CaptureRecorder(root, runner, reconnectBaseDelayMs = 1)
        val before = "01-02 03:04:05.006  100  101 I Tag: before rewind\n"
        val early = "01-02 03:04:06.007  100  101 I Tag: complete buffered row\n"
        val partial = "01-02 03:04:07.008  100  101 I Tag: incomplete partial row"
        try {
            val session = recorder.startWithDeviceLoggingConfiguration(
                DEVICE,
                testSettings(),
                CaptureTools(ADB, null, runner),
                DeviceLoggingConfiguration(bufferSizesBytes = mapOf("main" to 262_144L)),
            )
            initial.emit(before)
            awaitCapture { recorder.snapshot.value.indexedRows == 1 }
            val prefix = before + early + partial
            runner.bufferedLogcatOutput = prefix + "x".repeat(32 * 1024 * 1024 - prefix.length)
            runner.bootUptimeMs = 1_000L
            initial.finish()
            awaitCapture {
                runner.specs.size >= 2 && session.logFile.readText().contains("complete buffered row")
            }

            val text = session.logFile.readText()
            assertEquals(1, text.windowed("before rewind".length).count { it == "before rewind" })
            assertEquals(1, text.windowed("complete buffered row".length).count { it == "complete buffered row" })
            assertFalse(text.contains("incomplete partial row"), "an unterminated bounded-output tail must not be indexed")
            assertTrue(runner.specs[1].command.containsAll(listOf("-T", "01-02 03:04:06.007")), "the partial drain establishes a safe cursor from its complete new row")
            assertTrue(runner.runSpecs.none { "-G" in it.command }, "a capped drain must never resize log buffers")
            assertEquals(RecorderState.RECORDING, recorder.snapshot.value.state)
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun sameIdentityRewindRetriesFailedFirstDrainBeforeStartingTimestampFilteredLogcat() {
        val root = Files.createTempDirectory("capture-rewind-drain-retry-test").toFile()
        val runner = FakeCaptureRunner()
        val initial = StreamingFakeProcess()
        val returned = StreamingFakeProcess()
        runner.enqueue(initial)
        runner.enqueue(returned)
        val drainAttempts = AtomicInteger(0)
        val recorder = CaptureRecorder(root, runner, reconnectBaseDelayMs = 1)
        val oldRow = "01-02 03:04:09.009  100  101 I Tag: before reset\n"
        val earlyRow = "01-02 03:04:02.002  100  101 I Tag: early after reset\n"
        try {
            val session = recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            initial.emit(oldRow)
            awaitCapture { recorder.snapshot.value.indexedRows == 1 }
            runner.bootUptimeMs = 1_000L
            runner.bufferedLogcatOutput = earlyRow
            runner.runHandler = { spec ->
                if (spec.command.contains("logcat") && spec.command.contains("-d") && drainAttempts.incrementAndGet() == 1) {
                    CaptureCommandResult(1, byteArrayOf(), "temporary adb transport failure".toByteArray())
                } else null
            }
            initial.finish()

            awaitCapture {
                drainAttempts.get() >= 2 && runner.specs.size >= 2 && session.logFile.readText().contains("early after reset")
            }
            returned.emit(earlyRow + "01-02 03:04:03.003  100  101 I Tag: after reset\n")
            awaitCapture { session.logFile.readText().contains("after reset") }

            assertFalse(runner.specs[1].command.contains("-T"), "the old future cursor must not be used after a same-ID rewind")
            assertEquals(1, session.logFile.readText().windowed("early after reset".length).count { it == "early after reset" })
            assertEquals(RecorderState.RECORDING, recorder.snapshot.value.state)
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun completeEmptyRingClearsTheOldCursorAndAcceptsLowerTimestampRows() {
        val root = Files.createTempDirectory("capture-rewind-empty-ring-test").toFile()
        val runner = FakeCaptureRunner()
        val initial = StreamingFakeProcess()
        val returned = StreamingFakeProcess()
        runner.enqueue(initial)
        runner.enqueue(returned)
        val recorder = CaptureRecorder(root, runner, reconnectBaseDelayMs = 1)
        val oldRow = "01-02 03:04:09.009  100  101 I Tag: before reset\n"
        val newRow = "01-02 03:04:02.002  100  101 I Tag: first row in empty ring\n"
        try {
            val session = recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            initial.emit(oldRow)
            awaitCapture { recorder.snapshot.value.indexedRows == 1 }
            runner.bootUptimeMs = 1_000L
            runner.bufferedLogcatOutput = ""
            initial.finish()
            awaitCapture { runner.specs.size >= 2 }
            returned.emit(newRow)
            awaitCapture { session.logFile.readText().contains("first row in empty ring") }

            assertFalse(runner.specs[1].command.contains("-T"), "an inspected empty ring must clear the stale cursor")
            assertEquals(RecorderState.RECORDING, recorder.snapshot.value.state)
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun completeReplayOnlyRingUsesItsKnownSequenceWithoutOldClockComparisons() {
        val root = Files.createTempDirectory("capture-rewind-replay-only-ring-test").toFile()
        val runner = FakeCaptureRunner()
        val initial = StreamingFakeProcess()
        val returned = StreamingFakeProcess()
        runner.enqueue(initial)
        runner.enqueue(returned)
        val recorder = CaptureRecorder(root, runner, reconnectBaseDelayMs = 1)
        val replay = "01-02 03:04:09.009  100  101 I Tag: captured before reset\n"
        val newRow = "01-02 03:04:02.002  100  101 I Tag: lower timestamp after reset\n"
        try {
            val session = recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            initial.emit(replay)
            awaitCapture { recorder.snapshot.value.indexedRows == 1 }
            runner.bootUptimeMs = 1_000L
            runner.bufferedLogcatOutput = replay
            initial.finish()
            awaitCapture { runner.specs.size >= 2 }
            returned.emit(replay + newRow)
            awaitCapture { session.logFile.readText().contains("lower timestamp after reset") }

            val text = session.logFile.readText()
            assertEquals(1, text.windowed("captured before reset".length).count { it == "captured before reset" })
            assertTrue(text.contains("lower timestamp after reset"))
            assertFalse(runner.specs[1].command.contains("-T"))
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun changedRingPrefixStillCapturesNewLowerTimestampRows() {
        val root = Files.createTempDirectory("capture-rewind-ring-mismatch-test").toFile()
        val runner = FakeCaptureRunner()
        val initial = StreamingFakeProcess()
        val returned = StreamingFakeProcess()
        runner.enqueue(initial)
        runner.enqueue(returned)
        val recorder = CaptureRecorder(root, runner, reconnectBaseDelayMs = 1)
        val before = "01-02 03:04:09.009  100  101 I Tag: before reset\n"
        val inspected = "01-02 03:04:02.001  100  101 I Tag: inspected ring row\n"
        val newFirst = "01-02 03:04:01.001  100  101 I Tag: changed ring first row\n"
        try {
            val session = recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            initial.emit(before)
            awaitCapture { recorder.snapshot.value.indexedRows == 1 }
            runner.bootUptimeMs = 1_000L
            runner.bufferedLogcatOutput = inspected
            initial.finish()
            awaitCapture { runner.specs.size >= 2 && session.logFile.readText().contains("inspected ring row") }
            returned.emit(newFirst + inspected)
            awaitCapture { session.logFile.readText().contains("changed ring first row") }

            assertTrue(session.logFile.readText().contains("changed ring first row"), "a replay mismatch must immediately stop suppression")
            assertFalse(runner.specs[1].command.contains("-T"))
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun evictedRingPrefixAlignsAtTheFirstInspectedSuffixRow() {
        val root = Files.createTempDirectory("capture-rewind-ring-evicted-prefix-test").toFile()
        val runner = FakeCaptureRunner()
        val initial = StreamingFakeProcess()
        val returned = StreamingFakeProcess()
        runner.enqueue(initial)
        runner.enqueue(returned)
        val recorder = CaptureRecorder(root, runner, reconnectBaseDelayMs = 1)
        val before = "01-02 03:04:09.009  100  101 I Tag: before reset\n"
        val first = "01-02 03:04:02.001  100  101 I Tag: inspected first row\n"
        val second = "01-02 03:04:02.002  100  101 I Tag: inspected second row\n"
        val third = "01-02 03:04:02.003  100  101 I Tag: inspected third row\n"
        val after = "01-02 03:04:01.001  100  101 I Tag: after evicted prefix\n"
        try {
            val session = recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            initial.emit(before)
            awaitCapture { recorder.snapshot.value.indexedRows == 1 }
            runner.bootUptimeMs = 1_000L
            runner.bufferedLogcatOutput = first + second + third
            initial.finish()
            awaitCapture { runner.specs.size >= 2 && session.logFile.readText().contains("inspected third row") }
            returned.emit(second + third + after)
            awaitCapture { session.logFile.readText().contains("after evicted prefix") }

            val text = session.logFile.readText()
            assertEquals(1, text.windowed("inspected first row".length).count { it == "inspected first row" })
            assertEquals(1, text.windowed("inspected second row".length).count { it == "inspected second row" })
            assertEquals(1, text.windowed("inspected third row".length).count { it == "inspected third row" })
            assertTrue(text.contains("after evicted prefix"))
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun logcatConfigurationErrorStopsOnlyTheLogCaptureWithoutMarkingDeviceUnavailable() {
        val root = Files.createTempDirectory("capture-logcat-config-error-test").toFile()
        val runner = FakeCaptureRunner()
        runner.enqueue(CompletedFakeProcess(stderr = "logcat: unknown buffer name".toByteArray(), code = 1))
        val recorder = CaptureRecorder(root, runner)
        try {
            val started = recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            awaitCapture { recorder.snapshot.value.state == RecorderState.INTERRUPTED }
            val stopped = recorder.snapshot.value.session
            assertEquals(started.id, stopped?.id)
            assertTrue(stopped?.availabilityIntervals.orEmpty().isEmpty())
            assertTrue(stopped?.interruptions.orEmpty().any { "rejected its configuration" in it })
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
            // includeBufferedLogs is off by default, which is what adds "-T", "1" (see
            // CaptureSettings' own doc); this test is about the DEFAULT buffer-mode -b behavior.
            recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
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
    fun disconnectedDeviceKeepsOneRecordingSessionWaitingUntilStop() {
        val root = Files.createTempDirectory("capture-disconnect-test").toFile()
        val runner = FakeCaptureRunner()
        val logcat = StreamingFakeProcess()
        runner.enqueue(logcat)
        val recorder = CaptureRecorder(root, runner)
        try {
            val started = recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            runner.adbState = "offline"
            logcat.finish()
            awaitCapture { recorder.snapshot.value.session?.availabilityIntervals?.isNotEmpty() == true }
            assertEquals(RecorderState.RECORDING, recorder.snapshot.value.state)
            assertEquals(CaptureStatus.RECORDING, recorder.snapshot.value.session?.status)
            assertEquals(started.id, recorder.snapshot.value.session?.id)

            val stopped = requireNotNull(recorder.stop())
            assertEquals(CaptureStatus.STOPPED, stopped.status)
            assertTrue(stopped.availabilityIntervals.single().endElapsedMs == null)
            assertTrue(stopped.interruptions.isEmpty())
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun stopDrainsTheFinalBufferedLogcatLineWithoutInterruptingTheReader() {
        val root = Files.createTempDirectory("capture-stop-tail-test").toFile()
        val runner = FakeCaptureRunner()
        val logcat = StreamingFakeProcess(emitOnTerminate = "01-02 03:04:05.006  100  101 I Tag: final tail\n")
        runner.enqueue(logcat)
        val recorder = CaptureRecorder(root, runner)
        try {
            val session = recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            val stopped = requireNotNull(recorder.stop())
            assertEquals(CaptureStatus.STOPPED, stopped.status)
            assertEquals(1, session.logFile.readText().windowed("final tail".length).count { it == "final tail" })
            assertEquals(1, session.indexFile.readLines().map(Json::parseToJsonElement).size)
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
    fun availabilityIntervalsRoundTripThroughSessionMetadata() {
        val root = Files.createTempDirectory("capture-availability-persist-test").toFile()
        val runner = FakeCaptureRunner()
        runner.enqueue(StreamingFakeProcess())
        val recorder = CaptureRecorder(root, runner)
        try {
            val session = recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            recorder.stop()
            val interval = CaptureAvailabilityInterval(
                startElapsedMs = 1_200,
                endElapsedMs = 4_800,
                bootIdBefore = "old-boot",
                bootIdAfter = "new-boot",
                rebootConfirmed = true,
            )
            File(session.directory, "session.json").writeText(sessionJson(session.copy(availabilityIntervals = listOf(interval))))
            val restored = requireNotNull(readCaptureSessionDirectory(session.directory))
            assertEquals(listOf(interval), restored.availabilityIntervals)
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

    @Test
    fun storageFailureWhileStoppingStillTerminatesAdbAndMarksTheSessionInterrupted() {
        val root = Files.createTempDirectory("capture-stop-storage-test").toFile()
        val runner = FakeCaptureRunner()
        val logcat = StreamingFakeProcess()
        runner.enqueue(logcat)
        val failing = AtomicBoolean(false)
        // A long watchdog interval keeps the periodic publish (which flushes) from draining the
        // buffer or tripping the failure before the explicit stop below.
        val recorder = CaptureRecorder(
            root,
            runner,
            watchdogIntervalMs = 60_000,
            openStorage = { FailingFileOutputStream(it, failing) },
        )
        try {
            recorder.start(DEVICE, testSettings(), CaptureTools(ADB, null, runner))
            logcat.emit("01-02 03:04:05.006  100  101 I Tag: first\n")

            failing.set(true) // disk full from here on: the buffered bytes can no longer be flushed
            val stopped = assertNotNull(recorder.stop())

            assertEquals(CaptureStatus.INTERRUPTED, stopped.status)
            assertTrue(stopped.interruptions.single().startsWith("Capture storage failed while stopping:"))
            assertTrue(stopped.interruptions.single().contains("No space left on device"))
            assertFalse(logcat.isAlive, "adb must still be terminated when the final flush fails")
            assertEquals(RecorderState.INTERRUPTED, recorder.snapshot.value.state)
            assertEquals(CaptureStatus.INTERRUPTED, recorder.listSessions().single().status)
        } finally {
            failing.set(false)
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun overlappingOpenMirrorCallsStartExactlyOneScrcpyProcess() {
        val root = Files.createTempDirectory("capture-mirror-once-test").toFile()
        val scrcpyStarts = AtomicInteger(0)
        val scrcpyStarted = CountDownLatch(1)
        val releaseScrcpy = CountDownLatch(1)
        val logcat = StreamingFakeProcess()
        val mirror = StreamingFakeProcess()
        val runner = CaptureProcessRunner { spec ->
            if (spec.command.first() == "scrcpy") {
                scrcpyStarts.incrementAndGet()
                scrcpyStarted.countDown()
                check(releaseScrcpy.await(4, TimeUnit.SECONDS)) { "scrcpy launch was never released" }
                mirror
            } else {
                logcat
            }
        }
        val recorder = CaptureRecorder(root, runner)
        try {
            recorder.start(DEVICE, testSettings().copy(audio = false), CaptureTools(ADB, CaptureExecutable("scrcpy"), runner))
            val first = AtomicBoolean(false)
            val opener = thread(isDaemon = true) { first.set(recorder.openMirror()) }
            assertTrue(scrcpyStarted.await(4, TimeUnit.SECONDS))

            // The first call is still inside runner.start: an overlapping call must be a no-op.
            assertTrue(recorder.openMirror())
            assertEquals(1, scrcpyStarts.get())

            releaseScrcpy.countDown()
            opener.join(4_000)
            assertTrue(first.get())
            assertTrue(recorder.openMirror())
            assertEquals(1, scrcpyStarts.get())

            // The mirror's stdout pipe is drained so scrcpy can never block on a full pipe.
            mirror.emit("scrcpy chatter\n")
            awaitCapture { mirror.inputStream.available() == 0 }
        } finally {
            releaseScrcpy.countDown()
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun mirrorRetryRuleCoversAudioErrorsCleanExitsAndEarlyDeaths() {
        val audioError = listOf("INFO: scrcpy 2.4", "ERROR: Could not open audio device")
        val late = MIRROR_EARLY_EXIT_MS + 1
        // An audio error line on a failed exit retries, even long after launch.
        assertTrue(shouldRetryMirrorWithoutAudio(1, audioError, audioRequested = true, alreadyRetried = false, uptimeMs = late))
        assertTrue(shouldRetryMirrorWithoutAudio(1, listOf("WARN: Audio capture failed"), true, false, late))
        // A clean exit (the user closed the window) or a still-running process never retries.
        assertFalse(shouldRetryMirrorWithoutAudio(0, audioError, true, false, 100))
        assertFalse(shouldRetryMirrorWithoutAudio(null, audioError, true, false, 100))
        // Audio was not on, or this already is the retry.
        assertFalse(shouldRetryMirrorWithoutAudio(1, audioError, audioRequested = false, alreadyRetried = false, uptimeMs = 100))
        assertFalse(shouldRetryMirrorWithoutAudio(1, audioError, audioRequested = true, alreadyRetried = true, uptimeMs = 100))
        // No audio line: only an early death retries; a late crash with unrelated output does not.
        assertTrue(shouldRetryMirrorWithoutAudio(1, listOf("ERROR: Server connection failed"), true, false, 1_000))
        assertFalse(shouldRetryMirrorWithoutAudio(1, listOf("ERROR: Server connection failed"), true, false, late))
        // "audio" without an ERROR/WARN level (e.g. an INFO line) is not evidence.
        assertFalse(shouldRetryMirrorWithoutAudio(1, listOf("INFO: Audio enabled"), true, false, late))
    }

    @Test
    fun mirrorAudioCrashReopensTheWindowOnceWithNoAudio() {
        val root = Files.createTempDirectory("capture-mirror-audio-retry-test").toFile()
        val scrcpyCommands = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
        val logcat = StreamingFakeProcess()
        val retried = StreamingFakeProcess()
        val runner = CaptureProcessRunner { spec ->
            if (spec.command.first() == "scrcpy") {
                scrcpyCommands += spec.command
                if (scrcpyCommands.size == 1) CompletedFakeProcess("", "ERROR: Could not open audio device\n", 1) else retried
            } else {
                logcat
            }
        }
        val recorder = CaptureRecorder(root, runner)
        try {
            recorder.start(DEVICE, testSettings().copy(audio = true), CaptureTools(ADB, CaptureExecutable("scrcpy"), runner))
            assertTrue(recorder.openMirror())

            awaitCapture { scrcpyCommands.size == 2 }
            assertFalse(scrcpyCommands[0].contains("--no-audio"), "the first window keeps its audio")
            assertTrue(scrcpyCommands[1].contains("--no-audio"), "the retry must be silent")
            awaitCapture { recorder.snapshot.value.diagnostics.any { it.startsWith(MIRROR_AUDIO_FALLBACK_DIAGNOSTIC) } }
            // The audio line itself stays in the diagnostics, in the same form as before.
            assertTrue(recorder.snapshot.value.diagnostics.any { it == "scrcpy mirror: ERROR: Could not open audio device" })
            // The session's own settings are untouched by the forced no-audio copy.
            assertTrue(recorder.snapshot.value.session?.settings?.audio == true)
            // The retried window is alive, so a further open is a no-op.
            assertTrue(recorder.openMirror())
            assertEquals(2, scrcpyCommands.size)
        } finally {
            retried.finish()
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun mirrorThatKeepsCrashingIsRetriedOnlyOnce() {
        val root = Files.createTempDirectory("capture-mirror-retry-once-test").toFile()
        val scrcpyStarts = AtomicInteger(0)
        val logcat = StreamingFakeProcess()
        val runner = CaptureProcessRunner { spec ->
            if (spec.command.first() == "scrcpy") {
                scrcpyStarts.incrementAndGet()
                CompletedFakeProcess("", "ERROR: Could not open audio device\n", 1)
            } else {
                logcat
            }
        }
        val recorder = CaptureRecorder(root, runner)
        try {
            recorder.start(DEVICE, testSettings().copy(audio = true), CaptureTools(ADB, CaptureExecutable("scrcpy"), runner))
            assertTrue(recorder.openMirror())
            awaitCapture { scrcpyStarts.get() >= 2 }
            // Give a (buggy) third launch time to show up.
            Thread.sleep(500)
            assertEquals(2, scrcpyStarts.get())
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun mirrorWithAudioOffIsNotRetriedAfterACrash() {
        val root = Files.createTempDirectory("capture-mirror-no-retry-test").toFile()
        val scrcpyStarts = AtomicInteger(0)
        val logcat = StreamingFakeProcess()
        val runner = CaptureProcessRunner { spec ->
            if (spec.command.first() == "scrcpy") {
                scrcpyStarts.incrementAndGet()
                CompletedFakeProcess("", "ERROR: Could not open audio device\n", 1)
            } else {
                logcat
            }
        }
        val recorder = CaptureRecorder(root, runner)
        try {
            recorder.start(DEVICE, testSettings().copy(audio = false), CaptureTools(ADB, CaptureExecutable("scrcpy"), runner))
            assertTrue(recorder.openMirror())
            awaitCapture { recorder.snapshot.value.diagnostics.any { it.startsWith("scrcpy mirror exited with status 1") } }
            Thread.sleep(300)
            assertEquals(1, scrcpyStarts.get())
        } finally {
            recorder.close()
            root.deleteRecursively()
        }
    }

    private fun testDeviceLoggingConfiguration() = DeviceLoggingConfiguration(
        bufferSizesBytes = linkedMapOf("main" to 262_144L, "system" to 131_072L),
        globalLogTag = "",
        perTagOverrides = mapOf("FOO" to "D"),
    )

    private fun isDeviceLoggingRestoreCommand(spec: CaptureProcessSpec): Boolean =
        "-G" in spec.command || spec.command.any { "setprop " in it }

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
        const val TWO_MIB = 2L * ONE_MIB
        const val FOUR_MIB = 4L * ONE_MIB
    }
}

/** A storage stream that fails every write once [failing] is set, like a disk that just filled up. */
private class FailingFileOutputStream(file: File, private val failing: AtomicBoolean) : FileOutputStream(file, true) {
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (failing.get()) throw IOException("No space left on device")
        super.write(b, off, len)
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
