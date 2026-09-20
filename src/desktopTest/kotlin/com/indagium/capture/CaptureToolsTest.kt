package com.indagium.capture

import java.io.File
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
    fun scrcpyUsesSelectedAdbAndSafeRecordingDefaults() {
        val tools = CaptureTools(
            adb = CaptureExecutable("/sdk/platform-tools/adb"),
            scrcpy = CaptureExecutable("/usr/bin/scrcpy"),
            runner = FakeCaptureRunner(),
        )
        val spec = tools.scrcpySpec(
            serial = "ABC",
            settings = CaptureSettings(recordVideo = true, audio = true),
            destination = File("/tmp/session/screen.mkv"),
        )

        assertEquals("/sdk/platform-tools/adb", spec.environment["ADB"])
        assertTrue(spec.command.containsAll(listOf("--serial", "ABC", "--record-format=mkv", "--video-codec=h264")))
        assertTrue(spec.command.any { it.startsWith("--record=") })
        assertFalse(spec.command.contains("--audio"))
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
    fun validatesToolIdentityAndRequiredCapabilities() {
        val runner = FakeCaptureRunner().apply {
            enqueue(CompletedFakeProcess("Android Debug Bridge version 1.0.41\n"))
            enqueue(CompletedFakeProcess("devices logcat exec-out\n"))
        }
        val validation = CaptureTools(CaptureExecutable("adb"), null, runner).validateAdb()

        assertTrue(validation.available)
        assertEquals("Android Debug Bridge version 1.0.41", validation.version)
    }
}
