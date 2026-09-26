package com.indagium.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureToolsTest {
    @Test
    fun parsesAdbDevicesAndProvidesActionableStateGuidance() {
        val devices = parseAdbDevices(
            """
            List of devices attached
            emulator-5554 device product:sdk model:Pixel_8 device:emu transport_id:1
            PHONE123 unauthorized usb:1-2 transport_id:2
            PHONE456 no permissions (user in plugdev group; are your udev rules wrong?)
            PHONE789 offline transport_id:3
            """.trimIndent(),
        )

        assertEquals(listOf("emulator-5554", "PHONE123", "PHONE456", "PHONE789"), devices.map { it.device.serial })
        assertEquals("Pixel 8", devices[0].device.model)
        assertTrue(devices[0].device.emulator)
        assertNull(devices[0].guidance)
        assertTrue(devices[1].guidance.orEmpty().contains("accept"))
        assertEquals("no permissions", devices[2].device.state)
        assertTrue(devices[2].guidance.orEmpty().contains("udev"))
        assertTrue(devices[3].guidance.orEmpty().contains("Reconnect"))
    }

    @Test
    fun allDeviceCommandsUseAnExplicitSerial() {
        val tools = CaptureTools(CaptureExecutable("/sdk/adb"), null, FakeCaptureRunner())

        assertEquals(
            listOf("/sdk/adb", "-s", "serial with spaces", "logcat", "-v", "threadtime"),
            tools.adbSpec("serial with spaces", "logcat", "-v", "threadtime").command,
        )
    }

    @Test
    fun mirrorSpecUsesSelectedAdbAndNeverCarriesARecordFlag() {
        // Recording no longer spawns host scrcpy at all — CaptureRecorder now owns the device
        // stream directly via the embedded scrcpy server (see EmbeddedDeviceSession). scrcpyMirrorSpec
        // is the one remaining host-scrcpy launch: an explicitly visible, non-recording auxiliary
        // window, which must never carry a --record flag (it must not be able to race or overwrite
        // the canonical session MKV that EmbeddedDeviceSession/StreamingMkvWriter now own).
        val tools = CaptureTools(
            adb = CaptureExecutable("/sdk/platform-tools/adb"),
            scrcpy = CaptureExecutable("/usr/bin/scrcpy"),
            runner = FakeCaptureRunner(),
        )
        val spec = tools.scrcpyMirrorSpec(serial = "ABC", settings = CaptureSettings(recordVideo = true, audio = true))

        assertEquals("/sdk/platform-tools/adb", spec.environment["ADB"])
        assertTrue(spec.command.containsAll(listOf("--serial", "ABC", "--video-codec=h264")))
        assertFalse(spec.command.any { it.startsWith("--record") })
        assertFalse(spec.command.contains("--no-audio"))
    }

    @Test
    fun flatpakResolutionAndCommandsRunOnHostWithBusWatch() {
        val runner = FakeCaptureRunner().apply {
            enqueue(CompletedFakeProcess("/host/android/adb\n"))
            enqueue(CompletedFakeProcess())
            enqueue(CompletedFakeProcess("/host/bin/scrcpy\n"))
            enqueue(CompletedFakeProcess())
        }
        val tools = CaptureToolResolver(
            runner = runner,
            environment = emptyMap(),
            executableExists = { false },
            flatpak = true,
        ).resolve(CaptureSettings())

        assertEquals("/host/android/adb", tools.adb.path)
        assertEquals("/host/bin/scrcpy", tools.scrcpy?.path)
        assertEquals(
            listOf("flatpak-spawn", "--host", "--watch-bus", "/host/android/adb", "devices", "-l"),
            tools.adb.command(listOf("devices", "-l")),
        )
        assertTrue(runner.specs.all { it.command.take(3) == listOf("flatpak-spawn", "--host", "--watch-bus") })
    }

    @Test
    fun validatesAdbByVersionAloneAgainstARealisticModernHelpPayload() {
        // Fixture is a trimmed excerpt of REAL `adb version` / `adb help` output from
        // platform-tools 35.0.2 (verified on the machine this test was written on). The full
        // `adb help` text is 8,839 bytes and contains the substring "exec-out" ZERO times, even
        // though `adb exec-out` itself works fine -- that gap is exactly the B1 bug: the old
        // validateAdb() grepped `adb help` for "devices", "logcat" and "exec-out" and rejected
        // every modern adb as a result. validateAdb() no longer runs `adb help` at all, so this
        // fixture only needs to be realistic, not exhaustive.
        val runner = FakeCaptureRunner().apply {
            enqueue(
                CompletedFakeProcess(
                    "Android Debug Bridge version 1.0.41\n" +
                        "Version 35.0.2-12147458\n" +
                        "Installed as /opt/homebrew/bin/adb\n" +
                        "Running on Darwin 25.6.0 (arm64)\n",
                ),
            )
        }
        val validation = CaptureTools(CaptureExecutable("adb"), null, runner).validateAdb()

        assertTrue(validation.available)
        assertEquals("Android Debug Bridge version 1.0.41", validation.version)
        // Only one process should have run -- no `adb help` round trip.
        assertEquals(1, runner.specs.size)
    }

    @Test
    fun aGenuinelyBrokenAdbFailsValidationWithTheDiagnosticSurfaced() {
        val nonZeroExit = FakeCaptureRunner().apply {
            enqueue(CompletedFakeProcess(stdout = byteArrayOf(), stderr = "adb: command not found".toByteArray(), code = 127))
        }
        val nonZeroValidation = CaptureTools(CaptureExecutable("adb"), null, nonZeroExit).validateAdb()
        assertFalse(nonZeroValidation.available)
        assertTrue(nonZeroValidation.message.contains("adb: command not found"))

        val wrongProgram = FakeCaptureRunner().apply {
            enqueue(CompletedFakeProcess("BusyBox v1.36.1\n"))
        }
        val wrongProgramValidation = CaptureTools(CaptureExecutable("adb"), null, wrongProgram).validateAdb()
        assertFalse(wrongProgramValidation.available)
        assertEquals("BusyBox v1.36.1", wrongProgramValidation.version)
    }

    @Test
    fun macLoginShellFallbackResolvesAToolMissingFromTheScannedPath() {
        // Simulates a macOS .app launched from Finder: PATH is launchd's minimal set, so the
        // ordinary PATH scan (executableExists always false) and the hardcoded candidate
        // directories both miss a Homebrew-installed adb -- only the login shell knows about it.
        // resolve() also probes scrcpy the same way; that probe reports "not found" here since
        // only adb is stubbed as installed.
        val runner = FakeCaptureRunner().apply {
            enqueue(CompletedFakeProcess("/opt/homebrew/bin/adb\n"))
            enqueue(CompletedFakeProcess(stdout = byteArrayOf(), code = 1))
        }
        val resolver = CaptureToolResolver(
            runner = runner,
            environment = mapOf("PATH" to "/usr/bin:/bin", "SHELL" to "/bin/zsh"),
            executableExists = { it == "/opt/homebrew/bin/adb" },
            flatpak = false,
            isMacOs = true,
        )
        val tools = resolver.resolve(CaptureSettings())

        assertEquals("/opt/homebrew/bin/adb", tools.adb.path)
        assertEquals(null, tools.scrcpy)
        assertEquals(listOf("/bin/zsh", "-lc", "command -v adb"), runner.specs[0].command)
        assertEquals(listOf("/bin/zsh", "-lc", "command -v scrcpy"), runner.specs[1].command)
    }

    @Test
    fun macLoginShellFallbackIsCachedAndNotRepeatedOnAnotherResolution() {
        val runner = FakeCaptureRunner().apply {
            enqueue(CompletedFakeProcess("/opt/homebrew/bin/adb\n"))
            enqueue(CompletedFakeProcess(stdout = byteArrayOf(), code = 1))
        }
        val resolver = CaptureToolResolver(
            runner = runner,
            environment = mapOf("PATH" to "", "SHELL" to "/bin/zsh"),
            executableExists = { it == "/opt/homebrew/bin/adb" },
            flatpak = false,
            isMacOs = true,
        )

        resolver.resolve(CaptureSettings())
        resolver.resolve(CaptureSettings())

        // Both tool probes ran exactly once across two resolve() calls -- the per-name cache in
        // loginShellPath() means a second "Recheck tools" click doesn't re-spawn a shell.
        assertEquals(2, runner.specs.size)
    }

    @Test
    fun loginShellFallbackNeverRunsOnNonMacHostsOrUnderFlatpak() {
        val runner = FakeCaptureRunner()
        val resolver = CaptureToolResolver(
            runner = runner,
            environment = mapOf("PATH" to ""),
            executableExists = { false },
            flatpak = false,
            isMacOs = false,
        )

        val failure = runCatching { resolver.resolve(CaptureSettings()) }

        assertTrue(failure.isFailure)
        assertTrue(runner.specs.isEmpty())
    }

    @Test
    fun runAdbBuildsAnExplicitSerialCommandAndReturnsRawOutputWithoutThrowing() {
        val runner = FakeCaptureRunner()
        runner.enqueue(CompletedFakeProcess("main: ring buffer is 256KB (0KB consumed)\n"))
        val tools = CaptureTools(CaptureExecutable("/sdk/adb"), null, runner)

        val result = tools.runAdb("SERIAL", listOf("logcat", "-g"))

        assertEquals(listOf("/sdk/adb", "-s", "SERIAL", "logcat", "-g"), runner.specs.single().command)
        assertEquals(0, result.exitCode)
        assertTrue(result.stdoutText().contains("ring buffer"))
    }

    @Test
    fun runAdbSurfacesANonZeroExitInsteadOfThrowing() {
        val runner = FakeCaptureRunner()
        runner.enqueue(CompletedFakeProcess("", "Permission denied\n", 1))
        val tools = CaptureTools(CaptureExecutable("/sdk/adb"), null, runner)

        val result = tools.runAdb("SERIAL", listOf("shell", "setprop", "log.tag", "D"))

        assertEquals(1, result.exitCode)
        assertTrue(result.stderrText().contains("Permission denied"))
    }
}
