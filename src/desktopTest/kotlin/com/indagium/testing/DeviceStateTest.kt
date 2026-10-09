package com.indagium.testing

import com.indagium.testing.device.AdbDeviceStateReader
import com.indagium.testing.device.DeviceShellOutput
import com.indagium.testing.device.DeviceStateReader
import com.indagium.testing.device.DeviceStateReading
import com.indagium.testing.device.KNOWN_DEVICE_STATE_KEYS
import com.indagium.testing.device.TestDeviceSession
import com.indagium.testing.device.parseDeviceState
import com.indagium.testing.device.parseUsbDevices
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.newCheckId
import com.indagium.testing.run.DeterministicChecks
import com.indagium.testing.script.ScriptRunContext
import com.indagium.testing.script.TestScriptRunner
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Reading a device property for a device-state check: the key families, their parsers, unknown keys, and the check on a lane session. */
class DeviceStateTest {
    private val laneDir: File = createTempDirectory("indagium-device-state").toFile()
    private val sessions = ArrayList<TestDeviceSession>()

    @AfterTest
    fun tearDown() {
        sessions.forEach { it.closeBlocking() }
        laneDir.deleteRecursively()
    }

    private val usbDump = """
        USB Manager State:
          Host:
            connected devices:
              name=/dev/bus/usb/001/002 vendor_id=1921 product_id=21889 class=0 manufacturer_name=SanDisk product_name=Ultra serial_number=ABC123
              name=/dev/bus/usb/001/003 vendor_id=0x046d product_id=0xc52b class=0 product_name=Receiver
              name=/dev/bus/usb/001/002 vendor_id=1921 product_id=21889 class=0 manufacturer_name=SanDisk product_name=Ultra serial_number=ABC123
          Port status: none
    """.trimIndent()

    @Test
    fun usbDevicesBecomeOneVendorProductLinePerAttachedDevice() {
        val value = parseUsbDevices(usbDump)

        assertEquals(listOf("0781:5581 Ultra", "046d:c52b Receiver"), value.lines())
        assertEquals("", parseUsbDevices("USB Manager State:\n  no devices\n"), "no device is an empty answer, not a failure")
    }

    @Test
    fun theSettingsTogglesAreTrueFalseAndAnythingElseIsUnavailable() {
        assertEquals(DeviceStateReading.Value("true"), parseDeviceState("bt.enabled", "1\n"))
        assertEquals(DeviceStateReading.Value("false"), parseDeviceState("wifi.enabled", "0"))
        assertEquals(DeviceStateReading.Value("true"), parseDeviceState("airplane", " 1 "))
        val odd = assertIs<DeviceStateReading.Unavailable>(parseDeviceState("bt.enabled", "null"))
        assertTrue(odd.reason.contains("bt.enabled") && !odd.permanent)
    }

    @Test
    fun anUnknownKeyIsPermanentlyUnavailableAndNamesTheSupportedOnes() {
        val reading = assertIs<DeviceStateReading.Unavailable>(runBlocking { AdbDeviceStateReader { error("must not run") }.read("battery.level") })

        assertTrue(reading.permanent)
        assertTrue(reading.reason.contains("battery.level"))
        KNOWN_DEVICE_STATE_KEYS.forEach { assertTrue(reading.reason.contains(it), reading.reason) }
    }

    @Test
    fun theAdbReaderRunsOneBoundedShellCommandPerKeyAndReportsFailuresAsReadings() {
        val commands = ArrayList<List<String>>()
        var next = DeviceShellOutput(0, "1\n", "", timedOut = false)
        val reader = AdbDeviceStateReader { command -> commands += command; next }

        assertEquals(DeviceStateReading.Value("true"), runBlocking { reader.read("bt.enabled") })
        assertEquals(listOf(listOf("settings", "get", "global", "bluetooth_on")), commands)

        next = DeviceShellOutput(1, "", "permission denied", timedOut = false)
        assertTrue(assertIs<DeviceStateReading.Unavailable>(runBlocking { reader.read("wifi.enabled") }).reason.contains("permission denied"))
        next = DeviceShellOutput(0, "", "", timedOut = true)
        assertTrue(assertIs<DeviceStateReading.Unavailable>(runBlocking { reader.read("airplane") }).reason.contains("timed out"))
        assertEquals(3, commands.size, "one command per read")
        val broken = runBlocking { AdbDeviceStateReader { error("adb is gone") }.read("usb.devices") }
        assertTrue(assertIs<DeviceStateReading.Unavailable>(broken).reason.contains("adb is gone"))
    }

    @Test
    fun theLaneSessionReadsThroughItsAdbByDefaultAndTheReaderCanBeReplaced() {
        val adb = ScriptedAdbRunner()
        val lane = runBlocking { openFixtureSession(adb, File(laneDir, "a")) }.also { sessions += it }
        adb.shellStdout = "1\n"

        assertEquals(DeviceStateReading.Value("true"), runBlocking { lane.readDeviceState("wifi.enabled") })
        assertTrue(adb.shellCommands.any { it.takeLast(4) == listOf("settings", "get", "global", "wifi_on") }, adb.shellCommands.toString())

        lane.deviceStateReader = DeviceStateReader { DeviceStateReading.Value("0781:5581 Ultra") }
        assertEquals(DeviceStateReading.Value("0781:5581 Ultra"), runBlocking { lane.readDeviceState("usb.devices") })
    }

    private fun checks(reader: DeviceStateReader): DeterministicChecks {
        val lane = runBlocking { openFixtureSession(ScriptedAdbRunner(), File(laneDir, "c${sessions.size}")) }.also { sessions += it }
        lane.deviceStateReader = reader
        return DeterministicChecks(
            lane,
            TestScriptRunner(hostShell = listOf("/bin/sh", "-c")),
            { null },
            { ScriptRunContext(packageName = "com.example", runDir = laneDir, caseId = "case-1", stepId = "step-1") },
        )
    }

    private fun DeterministicChecks.run(check: StepCheck.DeviceState) = runBlocking { evaluate(check, 0L) }

    @Test
    fun aDeviceStateCheckPassesAsSoonAsTheValueMatchesAndFailsWhenItNeverDoes() {
        val reads = AtomicInteger()
        val subject = checks { if (reads.incrementAndGet() >= 3) DeviceStateReading.Value("0781:5581 Ultra") else DeviceStateReading.Value("") }

        val passed = subject.run(StepCheck.DeviceState(newCheckId(), "usb.devices", "0781:5581", withinMs = 5_000L))
        assertEquals(CheckStatus.PASS, passed.status, passed.detail)
        assertEquals("deviceState", passed.kind)
        assertEquals(3, reads.get(), "the state is read again until it matches")

        val other = checks { DeviceStateReading.Value("046d:c52b Receiver") }
        val failed = other.run(StepCheck.DeviceState(newCheckId(), "usb.devices", "0781:5581", withinMs = 0L))
        assertEquals(CheckStatus.FAIL, failed.status)
        assertTrue(failed.detail.contains("046d:c52b") && failed.detail.contains("0781:5581"), failed.detail)
    }

    @Test
    fun anUnknownKeyFailsTheCheckAtOnceWithAClearMessageAndAFailingReadIsAnError() {
        val unknown = checks(AdbDeviceStateReader { error("not used") }).run(StepCheck.DeviceState(newCheckId(), "gps.fix", "true", withinMs = 30_000L))
        assertEquals(CheckStatus.ERROR, unknown.status)
        assertTrue(unknown.detail.contains("Unknown device-state key 'gps.fix'") && unknown.detail.contains("usb.devices"), unknown.detail)
        assertTrue(unknown.durationMs < 5_000L, "no point waiting for a key that cannot appear")

        val broken = checks { DeviceStateReading.Unavailable("adb offline") }.run(StepCheck.DeviceState(newCheckId(), "bt.enabled", "true", withinMs = 0L))
        assertEquals(CheckStatus.ERROR, broken.status)
        assertEquals("adb offline", broken.detail)
    }
}
