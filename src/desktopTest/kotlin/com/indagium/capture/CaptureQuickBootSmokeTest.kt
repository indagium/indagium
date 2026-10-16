package com.indagium.capture

import com.indagium.ui.AppState
import com.indagium.ui.CaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in integration check for a QuickBoot restart of the isolated cloned AVD. It is deliberately
 * tied to emulator-5556 and the clone's exact name so it cannot stop the primary emulator, original
 * AVD, or a physical device.
 */
class CaptureQuickBootSmokeTest {
    @Test
    fun sameBootRestartRestoresFourMegabyteBuffersAndDoesNotReplayStartupRows() =
        runRestartSmoke(forceColdBoot = false)

    @Test
    fun coldBootRestoresFourMegabyteBuffersAndDoesNotReplayStartupRows() =
        runRestartSmoke(forceColdBoot = true)

    private fun runRestartSmoke(forceColdBoot: Boolean) {
        val serial = System.getenv("INDAGIUM_CAPTURE_SMOKE_SERIAL").orEmpty()
        assumeTrue(
            "Set INDAGIUM_CAPTURE_SMOKE_SERIAL=emulator-5556 to opt in; this smoke test skips by default",
            serial == SECONDARY_SERIAL,
        )

        val avdName = System.getenv("INDAGIUM_CAPTURE_SMOKE_AVD").orEmpty()
        assertEquals(
            CLONED_AVD_NAME,
            avdName,
            "Set INDAGIUM_CAPTURE_SMOKE_AVD to the isolated cloned AVD name",
        )
        val emulatorPath = System.getenv("INDAGIUM_CAPTURE_SMOKE_EMULATOR").orEmpty()
        assertTrue(
            emulatorPath.isNotBlank() && File(emulatorPath).canExecute(),
            "Set INDAGIUM_CAPTURE_SMOKE_EMULATOR to an executable emulator binary",
        )
        val adbPath = System.getenv("INDAGIUM_CAPTURE_SMOKE_ADB") ?: DEFAULT_ADB
        assertTrue(File(adbPath).canExecute(), "adb is not executable at $adbPath")

        val evidence = File("build/verification/quickboot-real/${UUID.randomUUID()}").absoluteFile
        val logs = File(evidence, "logs")
        val sessionRoot = File(evidence, "session")
        logs.mkdirs()
        sessionRoot.mkdirs()
        val outcomeFile = File(evidence, "outcomes.txt")
        val outcomes = StringBuilder()
        val deadline = SmokeDeadline(Duration.ofMinutes(3))
        val tools = CaptureTools(CaptureExecutable(adbPath), null, ProcessBuilderCaptureRunner())
        var recorder: CaptureRecorder? = null
        var relaunchedEmulator: Process? = null
        var mayCleanUpSecondary = false
        var helperApp: AppState? = null
        var helperScope: CoroutineScope? = null
        var captureService: CaptureService? = null

        fun note(message: String) {
            outcomes.append(message).append('\n')
            outcomeFile.writeText(outcomes.toString())
        }

        fun adb(vararg arguments: String, timeout: Duration = Duration.ofSeconds(5), outputLimitBytes: Int = DEFAULT_ADB_OUTPUT_LIMIT): CaptureCommandResult =
            tools.runAdb(serial, arguments.toList(), timeout, outputLimitBytes)

        fun adbRequired(vararg arguments: String, timeout: Duration = Duration.ofSeconds(5), outputLimitBytes: Int = DEFAULT_ADB_OUTPUT_LIMIT): CaptureCommandResult {
            val result = adb(*arguments, timeout = timeout, outputLimitBytes = outputLimitBytes)
            assertTrue(!result.timedOut, "adb ${arguments.joinToString(" ")} timed out")
            assertEquals(0, result.exitCode, "adb ${arguments.joinToString(" ")} failed: ${result.stderrText()}")
            return result
        }

        try {
            assertEquals("device", adbRequired("get-state").stdoutText().trim(), "The selected secondary emulator must be online")
            assertEquals(CLONED_AVD_NAME, readAvdName(::adbRequired), "emulator-5556 is not the isolated cloned AVD")
            assertEquals("1", adbRequired("shell", "getprop", "sys.boot_completed").stdoutText().trim(), "The selected AVD must be booted")
            val bootBefore = readBootId(::adbRequired)
            val uptimeBefore = readOptionalDeviceValue(::adb, "shell", "cat", "/proc/uptime")
            val wallClockBefore = readOptionalDeviceValue(::adb, "shell", "date", "+%s")
            note("mode=${if (forceColdBoot) "coldboot" else "same-boot"} serial=$serial avd=$avdName bootIdBefore=$bootBefore uptimeBefore=${uptimeBefore ?: "unavailable"} wallClockEpochBefore=${wallClockBefore ?: "unavailable"}")

            // Each method may run first. Refresh only the isolated clone's default QuickBoot
            // snapshot so the same-boot case always resumes the boot ID observed above.
            val snapshotSave = adbRequired("emu", "avd", "snapshot", "save", "default_boot", timeout = Duration.ofSeconds(20))
            File(logs, "snapshot-save.txt").writeText(snapshotSave.stdoutText() + snapshotSave.stderrText())
            note("ownedCloneSnapshot=default_boot saved=true")

            // Use the same production reader as capture startup, with every app-owned path rooted
            // in this evidence folder so the smoke test never reads or writes user Documents.
            val app = AppState(
                autosaveFile = File(evidence, "app/autosave.cache"),
                notesDir = File(evidence, "app/notes"),
                archiveCacheDir = File(evidence, "app/archive-cache"),
                customCommandsDir = File(evidence, "app/custom-commands"),
            )
            helperApp = app
            val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            helperScope = serviceScope
            val service = CaptureService(app, serviceScope, File(evidence, "app/legacy-captures")) {
                File(evidence, "app/captures")
            }
            captureService = service
            val initialLoggingSnapshot = service.readDeviceLoggingConfiguration(tools, serial)
            assertTrue(
                initialLoggingSnapshot.warnings.isEmpty(),
                "Device logging settings must be fully readable before QuickBoot: ${initialLoggingSnapshot.warnings}",
            )
            val originalLoggingConfiguration = initialLoggingSnapshot.configuration
            val originalBufferSizes = assertNotNull(originalLoggingConfiguration.bufferSizesBytes)
            assertEquals(EXPECTED_LOG_BUFFER_COUNT, originalBufferSizes.size, "The cloned AVD should expose all eight logcat buffers")
            assertTrue(
                originalBufferSizes.keys.containsAll(setOf("main", "system", "crash")),
                "The production reader did not return the expected complete logcat buffer set: ${originalBufferSizes.keys}",
            )
            assertNotNull(originalLoggingConfiguration.globalLogTag)
            assertNotNull(originalLoggingConfiguration.perTagOverrides)
            File(logs, "device-logging-before.txt").writeText("$originalLoggingConfiguration\n")
            note("deviceLoggingSnapshot=complete buffers=${originalBufferSizes.size} perTag=${originalLoggingConfiguration.perTagOverrides?.size}")

            val resized = adbRequired("logcat", "-b", "all", "-G", "4M")
            File(logs, "set-buffer-sizes.txt").writeText(resized.stdoutText() + resized.stderrText())
            val configuredLoggingSnapshot = service.readDeviceLoggingConfiguration(tools, serial)
            assertTrue(
                configuredLoggingSnapshot.warnings.isEmpty(),
                "Device logging settings must remain fully readable after resizing: ${configuredLoggingSnapshot.warnings}",
            )
            val captureLoggingConfiguration = configuredLoggingSnapshot.configuration
            val expectedFourMegabyteBuffers = originalBufferSizes.keys.associateWith { FOUR_MEBIBYTES }
            assertEquals(expectedFourMegabyteBuffers, captureLoggingConfiguration.bufferSizesBytes)
            assertEquals(originalLoggingConfiguration.globalLogTag, captureLoggingConfiguration.globalLogTag)
            assertEquals(originalLoggingConfiguration.perTagOverrides, captureLoggingConfiguration.perTagOverrides)
            File(logs, "device-logging-configured-for-capture.txt").writeText("$captureLoggingConfiguration\n")
            note("deviceLoggingBeforeCapture=all ${EXPECTED_LOG_BUFFER_COUNT} buffers set to 4 MiB")

            val beforeMarker = "quickboot-before-${UUID.randomUUID()}"
            adbRequired("shell", "log", "-t", MARKER_TAG, beforeMarker)
            val startupDump = adbRequired(
                "logcat", "-d", "-v", "threadtime",
                timeout = minOf(Duration.ofSeconds(12), deadline.remaining()),
                outputLimitBytes = STARTUP_BUFFER_LIMIT_BYTES,
            )
            assertTrue(
                startupDump.stdout.size < STARTUP_BUFFER_LIMIT_BYTES,
                "The startup logcat buffer reached its bounded output limit; refusing an incomplete replay comparison",
            )
            val startupBuffer = startupDump.stdoutText()
            File(logs, "startup-buffer.log").writeText(startupBuffer)
            val allStartupRows = parseThreadtimeRows(startupBuffer)
            val markerRowIndex = allStartupRows.indexOfLast { it.isSmokeMarker(beforeMarker) }
            assertTrue(markerRowIndex >= 0, "The startup buffer did not include its unique marker")
            val startupRows = allStartupRows.take(markerRowIndex + 1).takeLast(STARTUP_ROWS_TO_CHECK)
            assertTrue(startupRows.isNotEmpty(), "The emulator logcat startup buffer contained no threadtime rows")
            assertTrue(startupRows.any { it.isSmokeMarker(beforeMarker) })
            File(logs, "startup-rows-selected.log").writeText(startupRows.joinToString("\n", postfix = "\n") { it.raw })

            val capture = CaptureRecorder(sessionRoot)
            recorder = capture
            val session = capture.startWithDeviceLoggingConfiguration(
                CaptureDevice(serial, "device", avdName, emulator = true),
                CaptureSettings(
                    mirror = false,
                    recordVideo = false,
                    includeBufferedLogs = true,
                    freeSpaceReserveBytes = 0,
                ),
                tools,
                captureLoggingConfiguration,
            )
            assertEquals(CaptureStatus.RECORDING, session.status)
            assertEquals(RecorderState.RECORDING, capture.snapshot.value.state)
            awaitSmoke("Initial buffered logcat marker was not captured", capture, session.id, deadline) {
                session.logFile.isFile && hasSmokeMarker(session.logFile.readText(), beforeMarker)
            }
            capture.snapshotForExport(session.id)

            val baselineText = session.logFile.readText()
            File(logs, "capture-before-restart.log").writeText(baselineText)
            val baselineRows = parseThreadtimeRows(baselineText)
            val baselineCounts = baselineRows.groupingBy { it }.eachCount()
            val startupCounts = startupRows.groupingBy { it }.eachCount()
            startupCounts.forEach { (row, count) ->
                assertTrue(
                    (baselineCounts[row] ?: 0) >= count,
                    "The initial buffered capture missed startup row ${row.raw}",
                )
            }
            assertEquals(1, baselineRows.count { it.isSmokeMarker(beforeMarker) }, "The initial marker should be recorded once")
            note("sessionId=${session.id} baselineRows=${baselineRows.size} startupRows=${startupRows.size}")

            // From this point on the test owns only the verified cloned AVD on port 5556.
            mayCleanUpSecondary = true
            val killed = adbRequired("emu", "kill", timeout = Duration.ofSeconds(10))
            File(logs, "emu-kill.txt").writeText(killed.stdoutText() + killed.stderrText())
            awaitSmoke("Capture did not record the emulator availability interval", capture, session.id, deadline) {
                capture.snapshot.value.session?.availabilityIntervals?.isNotEmpty() == true
            }
            assertEquals(RecorderState.RECORDING, capture.snapshot.value.state)
            note("availability interval opened; recorder remained RECORDING")

            val emulatorLog = File(logs, "emulator-relaunch.log")
            val launchArguments = buildList {
                add(emulatorPath)
                add("-avd")
                add(avdName)
                add("-port")
                add("5556")
                add("-no-window")
                add("-no-audio")
                if (forceColdBoot) add("-no-snapshot-load")
                add("-no-snapshot-save")
            }
            val launchedEmulator = ProcessBuilder(launchArguments)
                .redirectErrorStream(true).redirectOutput(emulatorLog).start()
            relaunchedEmulator = launchedEmulator
            waitForEmulatorReady(::adb, avdName, launchedEmulator, deadline, emulatorLog)
            val bootAfter = readBootId(::adbRequired)
            val uptimeAfter = readOptionalDeviceValue(::adb, "shell", "cat", "/proc/uptime")
            val wallClockAfter = readOptionalDeviceValue(::adb, "shell", "date", "+%s")
            File(logs, "boot-ids.txt").writeText("before=$bootBefore\nafter=$bootAfter\n")
            File(logs, "device-clock.txt").writeText(
                "uptimeBefore=${uptimeBefore ?: "unavailable"}\nuptimeAfter=${uptimeAfter ?: "unavailable"}\n" +
                    "wallClockEpochBefore=${wallClockBefore ?: "unavailable"}\nwallClockEpochAfter=${wallClockAfter ?: "unavailable"}\n",
            )
            assertEquals(
                !forceColdBoot,
                bootBefore == bootAfter,
                "Unexpected boot ID transition for ${if (forceColdBoot) "coldboot" else "same-boot"} restart. Emulator output: ${tail(emulatorLog)}",
            )
            note("bootIdAfter=$bootAfter uptimeAfter=${uptimeAfter ?: "unavailable"} wallClockEpochAfter=${wallClockAfter ?: "unavailable"}")

            awaitSmoke("Availability interval did not close after QuickBoot resume", capture, session.id, deadline) {
                capture.snapshot.value.session?.availabilityIntervals?.lastOrNull()?.endElapsedMs != null
            }
            val resumedSnapshot = capture.snapshot.value
            assertEquals(RecorderState.RECORDING, resumedSnapshot.state, resumedSnapshot.diagnostics.joinToString("\n"))
            assertEquals(session.id, assertNotNull(resumedSnapshot.session).id, "QuickBoot must keep the original capture session")
            val interval = assertNotNull(resumedSnapshot.session).availabilityIntervals.single()
            assertEquals(bootBefore, interval.bootIdBefore)
            assertEquals(bootAfter, interval.bootIdAfter)
            assertEquals(forceColdBoot, interval.rebootConfirmed, "Availability markers did not match the requested restart mode")
            assertTrue(
                resumedSnapshot.diagnostics.any { if (forceColdBoot) "reboot confirmed" in it else "same boot" in it },
                "The recorder did not report the expected ${if (forceColdBoot) "coldboot" else "same-boot"} reconnection",
            )

            val restoreEvent = runBlocking {
                withTimeout(deadline.remaining().toMillis().coerceAtLeast(1L)) {
                    capture.deviceLoggingRestoreEvents.first { it.serial == serial }
                }
            }
            assertEquals(serial, restoreEvent.serial)
            val restoredDiagnostic = capture.snapshot.value.diagnostics.firstOrNull { "device logging settings" in it.lowercase() }
            assertEquals(
                "Device logging settings were restored and verified after restart.",
                restoredDiagnostic,
                "The recorder must finish a successful production restore before readback",
            )
            assertTrue(
                deadline.remaining() >= Duration.ofMillis(POST_RESTORE_SETTLE_MS),
                "Not enough time remained for the delayed post-restore buffer readback",
            )
            Thread.sleep(POST_RESTORE_SETTLE_MS)
            val restoredLoggingSnapshot = service.readDeviceLoggingConfiguration(tools, serial)
            assertTrue(
                restoredLoggingSnapshot.warnings.isEmpty(),
                "Restored device logging settings could not be fully read back: ${restoredLoggingSnapshot.warnings}",
            )
            assertEquals(captureLoggingConfiguration, restoredLoggingSnapshot.configuration, "QuickBoot restore must match the settings captured after resizing")
            assertEquals(expectedFourMegabyteBuffers, restoredLoggingSnapshot.configuration.bufferSizesBytes)
            File(logs, "device-logging-after.txt").writeText("${restoredLoggingSnapshot.configuration}\n")
            assertEquals(RecorderState.RECORDING, capture.snapshot.value.state, "Capture must remain active after the delayed settings readback")
            note("deviceLoggingRestore=complete delayedReadbackAfterMs=$POST_RESTORE_SETTLE_MS buffersRemain4MiB=true")

            val afterMarker = "quickboot-after-${UUID.randomUUID()}"
            adbRequired("shell", "log", "-t", MARKER_TAG, afterMarker)
            awaitSmoke("Logcat did not resume after QuickBoot", capture, session.id, deadline) {
                hasSmokeMarker(session.logFile.readText(), afterMarker)
            }
            capture.snapshotForExport(session.id)
            val resumedText = session.logFile.readText()
            File(logs, "capture-after-restart.log").writeText(resumedText)
            val resumedCounts = parseThreadtimeRows(resumedText).groupingBy { it }.eachCount()
            baselineCounts.forEach { (row, count) ->
                assertEquals(
                    count,
                    resumedCounts[row] ?: 0,
                    "A pre-restart logcat row was lost or replayed: ${row.raw}",
                )
            }
            val resumedRows = parseThreadtimeRows(resumedText)
            val beforeMarkerRow = resumedRows.single { it.isSmokeMarker(beforeMarker) }
            val afterMarkerRow = resumedRows.single { it.isSmokeMarker(afterMarker) }
            val logTimestampRewound = afterMarkerRow.timestamp < beforeMarkerRow.timestamp
            File(logs, "marker-timestamps.txt").writeText(
                "before=${beforeMarkerRow.timestamp}\nafter=${afterMarkerRow.timestamp}\nrewound=$logTimestampRewound\n",
            )
            note("deviceClockComparison uptime=${compareOptionalUptimes(uptimeBefore, uptimeAfter)} wallClock=${compareOptionalEpochs(wallClockBefore, wallClockAfter)} logcatTimestampRewound=$logTimestampRewound")
            assertEquals(1, resumedRows.count { it.isSmokeMarker(beforeMarker) })
            assertEquals(1, resumedRows.count { it.isSmokeMarker(afterMarker) }, "The new marker should be captured once")
            assertTrue(resumedText.contains("IndagiumCapture: Device unavailable; elapsedMs="))
            assertTrue(resumedText.contains("IndagiumCapture: Device reconnected; unavailableMs="))
            assertEquals(RecorderState.RECORDING, capture.snapshot.value.state)
            assertEquals(session.id, capture.snapshot.value.session?.id)
            assertEquals(1, sessionRoot.listFiles().orEmpty().count { it.isDirectory }, "QuickBoot must not create a second capture session")
            note("${if (forceColdBoot) "coldboot" else "same boot"} verified; resumedRows=${resumedRows.size}; afterMarker=$afterMarker")

            val stopped = assertNotNull(capture.stop(), "Stop should finalize the QuickBoot capture")
            assertEquals(session.id, stopped.id)
            assertEquals(CaptureStatus.STOPPED, stopped.status)
            assertEquals(RecorderState.STOPPED, capture.snapshot.value.state)
            assertEquals(1, sessionRoot.listFiles().orEmpty().count { it.isDirectory })
            note("stop=STOPPED sessionId=${stopped.id} evidence=${evidence.absolutePath}")
        } catch (failure: Throwable) {
            note("failure=${failure::class.simpleName}: ${failure.message}")
            throw failure
        } finally {
            recorder?.let { activeRecorder ->
                runCatching { activeRecorder.stop() }
                runCatching { activeRecorder.close() }
            }
            runCatching { captureService?.close() }
            helperScope?.cancel()
            runCatching { helperApp?.close(forAppDataReset = true) }
            if (mayCleanUpSecondary) {
                val activeRelaunch = relaunchedEmulator
                val avdStillMatches = runCatching {
                    readAvdName(::adb) == avdName
                }.getOrDefault(false)
                if (avdStillMatches) {
                    runCatching { adb("emu", "kill", timeout = Duration.ofSeconds(8)) }
                } else if (activeRelaunch?.isAlive == true) {
                    // If adb never became ready, terminate only the cloned emulator process this test started.
                    activeRelaunch.toHandle().descendants().forEach { child -> runCatching { child.destroy() } }
                    runCatching { activeRelaunch.destroy() }
                }
                if (activeRelaunch?.isAlive == true) {
                    runCatching { activeRelaunch.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) }
                    if (activeRelaunch.isAlive) {
                        activeRelaunch.toHandle().descendants().forEach { child -> runCatching { child.destroyForcibly() } }
                        runCatching { activeRelaunch.destroyForcibly() }
                    }
                }
            }
            runCatching { outcomeFile.writeText(outcomes.toString()) }
        }
    }

    private fun readOptionalDeviceValue(
        adb: (Array<out String>, Duration, Int) -> CaptureCommandResult,
        vararg arguments: String,
    ): String? = runCatching {
        adb(arguments, Duration.ofSeconds(3), DEFAULT_ADB_OUTPUT_LIMIT)
            .takeIf { !it.timedOut && it.exitCode == 0 }
            ?.stdoutText()?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun compareOptionalUptimes(before: String?, after: String?): String {
        val beforeSeconds = before?.substringBefore(' ')?.toDoubleOrNull() ?: return "unavailable"
        val afterSeconds = after?.substringBefore(' ')?.toDoubleOrNull() ?: return "unavailable"
        return when {
            afterSeconds < beforeSeconds -> "rewound"
            afterSeconds > beforeSeconds -> "advanced"
            else -> "unchanged"
        }
    }

    private fun compareOptionalEpochs(before: String?, after: String?): String {
        val beforeEpoch = before?.toLongOrNull() ?: return "unavailable"
        val afterEpoch = after?.toLongOrNull() ?: return "unavailable"
        return when {
            afterEpoch < beforeEpoch -> "rewound"
            afterEpoch > beforeEpoch -> "advanced"
            else -> "unchanged"
        }
    }

    private fun readBootId(adbRequired: (Array<out String>, Duration, Int) -> CaptureCommandResult): String =
        adbRequired(arrayOf("shell", "cat", "/proc/sys/kernel/random/boot_id"), Duration.ofSeconds(5), DEFAULT_ADB_OUTPUT_LIMIT)
            .stdoutText().trim().also { assertTrue(it.isNotBlank(), "Emulator boot ID was empty") }

    private fun readAvdName(adbRequired: (Array<out String>, Duration, Int) -> CaptureCommandResult): String =
        adbRequired(arrayOf("shell", "getprop", "ro.boot.qemu.avd_name"), Duration.ofSeconds(5), DEFAULT_ADB_OUTPUT_LIMIT)
            .stdoutText().trim()

    private fun waitForEmulatorReady(
        adb: (Array<out String>, Duration, Int) -> CaptureCommandResult,
        expectedAvd: String,
        emulator: Process,
        deadline: SmokeDeadline,
        emulatorLog: File,
    ) {
        while (!deadline.expired()) {
            if (!emulator.isAlive) {
                error("The cloned emulator process exited with ${emulator.exitValue()}: ${tail(emulatorLog)}")
            }
            val state = runCatching {
                adb(arrayOf("get-state"), Duration.ofSeconds(3), DEFAULT_ADB_OUTPUT_LIMIT)
                    .takeIf { !it.timedOut && it.exitCode == 0 }
                    ?.stdoutText()?.trim()
            }.getOrNull()
            if (state == "device") {
                val avd = runCatching { readAvdName(adb) }.getOrNull()
                val bootComplete = runCatching {
                    adb(arrayOf("shell", "getprop", "sys.boot_completed"), Duration.ofSeconds(3), DEFAULT_ADB_OUTPUT_LIMIT)
                        .takeIf { !it.timedOut && it.exitCode == 0 }
                        ?.stdoutText()?.trim()
                }.getOrNull()
                val shellReady = runCatching {
                    adb(arrayOf("shell", "echo", "quickboot-ready"), Duration.ofSeconds(3), DEFAULT_ADB_OUTPUT_LIMIT)
                        .let { !it.timedOut && it.exitCode == 0 && "quickboot-ready" in it.stdoutText() }
                }.getOrDefault(false)
                if (avd == expectedAvd && bootComplete == "1" && shellReady) return
            }
            Thread.sleep(250)
        }
        error("The cloned emulator did not become ready before the deadline. Emulator output: ${tail(emulatorLog)}")
    }

    private fun awaitSmoke(
        message: String,
        recorder: CaptureRecorder,
        sessionId: String,
        deadline: SmokeDeadline,
        condition: () -> Boolean,
    ) {
        while (!deadline.expired()) {
            if (condition()) return
            val snapshot = recorder.snapshot.value
            assertEquals(
                RecorderState.RECORDING,
                snapshot.state,
                "$message\n${snapshot.diagnostics.joinToString("\n")}",
            )
            assertEquals(sessionId, snapshot.session?.id, "$message; capture session changed")
            Thread.sleep(200)
        }
        error("$message\n${recorder.snapshot.value.diagnostics.joinToString("\n")}")
    }

    private fun parseThreadtimeRows(text: String): List<ThreadtimeRow> = text.lineSequence().mapNotNull { raw ->
        val match = THREADTIME_ROW.matchEntire(raw) ?: return@mapNotNull null
        ThreadtimeRow(
            raw = raw,
            timestamp = match.groupValues[1],
            pid = match.groupValues[2],
            tid = match.groupValues[3],
            priority = match.groupValues[4],
            tag = match.groupValues[5],
            body = match.groupValues[6],
        )
    }.toList()

    private fun hasSmokeMarker(text: String, marker: String): Boolean =
        parseThreadtimeRows(text).any { it.isSmokeMarker(marker) }

    private fun ThreadtimeRow.isSmokeMarker(marker: String): Boolean =
        tag == MARKER_TAG && body.trim() == marker

    private fun tail(file: File): String = if (file.isFile) file.readText().takeLast(MAX_EMULATOR_DIAGNOSTIC_CHARS) else "<no emulator output>"

    private data class ThreadtimeRow(
        val raw: String,
        val timestamp: String,
        val pid: String,
        val tid: String,
        val priority: String,
        val tag: String,
        val body: String,
    )

    private class SmokeDeadline(duration: Duration) {
        private val endNanos = System.nanoTime() + duration.toNanos()
        fun expired(): Boolean = System.nanoTime() >= endNanos
        fun remaining(): Duration = Duration.ofNanos((endNanos - System.nanoTime()).coerceAtLeast(1L))
    }

    private companion object {
        const val SECONDARY_SERIAL = "emulator-5556"
        const val CLONED_AVD_NAME = "Indagium_QuickBoot_Test"
        const val DEFAULT_ADB = "/opt/homebrew/bin/adb"
        const val MARKER_TAG = "IndagiumQuickBootSmoke"
        const val DEFAULT_ADB_OUTPUT_LIMIT = 64 * 1024
        const val STARTUP_BUFFER_LIMIT_BYTES = 16 * 1024 * 1024
        const val STARTUP_ROWS_TO_CHECK = 128
        const val EXPECTED_LOG_BUFFER_COUNT = 8
        const val FOUR_MEBIBYTES = 4L * 1024L * 1024L
        const val POST_RESTORE_SETTLE_MS = 5_000L
        const val MAX_EMULATOR_DIAGNOSTIC_CHARS = 8_000
        val THREADTIME_ROW = Regex("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEFAS])\\s+(.+?): ?(.*)$")
    }
}
