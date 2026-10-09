package com.indagium.testing

import com.indagium.testing.device.DeviceStateReading
import com.indagium.testing.device.DeviceStateSource
import com.indagium.testing.device.KNOWN_DEVICE_STATE_KEYS
import com.indagium.testing.device.parseAudioDevices
import com.indagium.testing.device.parseBatteryStatus
import com.indagium.testing.device.parseBluetoothConnected
import com.indagium.testing.device.parseDeviceState
import com.indagium.testing.device.parseDisplayRotation
import com.indagium.testing.device.parsePowerSource
import com.indagium.testing.device.parseScreenAwake
import com.indagium.testing.device.parseUsbDevices
import com.indagium.testing.device.parseWifiEnabled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A dump under src/desktopTest/resources/device-state: real captures from an Android 17 emulator, or hand-built variants of them. */
internal fun deviceStateFixture(name: String): String =
    requireNotNull(DeviceStateParsersTest::class.java.getResourceAsStream("/device-state/$name")) { "missing fixture $name" }
        .readBytes().toString(Charsets.UTF_8)

private fun fixture(name: String) = deviceStateFixture(name)

/**
 * The parsers behind the device-state keys, against dumps captured from an Android 17 emulator (usb without a host device, battery on
 * AC, power awake, Bluetooth on, audio on the speaker, display upright) and against variants built from them by hand where the emulator
 * cannot produce the situation (an attached USB device, a charger unplugged, a connected Bluetooth device, a wired headset).
 */
class DeviceStateParsersTest {
    // ── USB ──────────────────────────────────────────────────────────

    @Test
    fun theRealCaptureWithoutAHostDeviceIsAnEmptyValueAndItsFiltersAreNotDevices() {
        // settings_manager prints vendor_id=-1 / product_id=-1 filters on separate lines; they are not attached devices.
        val dump = fixture("dumpsys_usb_no_host.txt")

        assertEquals("", parseUsbDevices(dump))
        assertEquals(DeviceStateReading.Value(""), parseDeviceState("usb.devices", dump))
    }

    @Test
    fun anAttachedHostDeviceIsReadFromItsMultiLineRecordWithOnlyItsOwnFields() {
        val dump = fixture("dumpsys_usb_host_attached.txt")

        // The second record has decimal-less 0x ids and nested interface fields (vendor_id=999) that must not leak into it.
        assertEquals(listOf("0781:5581 Ultra", "046d:c52b USB Receiver"), parseUsbDevices(dump).lines())
    }

    @Test
    fun aListOfOneIsPrintedWithoutBracketsAndStillCounts() {
        val dump = fixture("dumpsys_usb_host_single.txt")

        assertEquals("05ac:1200", parseUsbDevices(dump), "an unnamed device is its ids alone")
    }

    @Test
    fun onlyTheDevicesOfTheHostManagerCountNeverOtherRecordsWithIds() {
        val trap = """
            {
              device_manager={
                devices=[
                  {
                    vendor_id=1111
                    product_id=2222
                    product_name=Not host
                  }
                ]
              }
              settings_manager={
                filters={
                  vendor_id=3333
                  product_id=4444
                }
              }
              host_manager={
                connections=[
                  {
                    vendor_id=5555
                    product_id=6666
                  }
                ]
              }
            }
        """.trimIndent()

        assertEquals("", parseUsbDevices(trap))
    }

    @Test
    fun theOlderOneLineFormatStillWorks() {
        val dump = fixture("dumpsys_usb_legacy_host.txt")

        assertEquals(listOf("0781:5581 Ultra", "046d:c52b USB Receiver"), parseUsbDevices(dump).lines())
    }

    @Test
    fun aDumpCutShortInsideARecordDropsThatRecordInsteadOfGuessing() {
        val lines = fixture("dumpsys_usb_host_attached.txt").lines()
        val cut = lines.take(lines.indexOfFirst { it.contains("product_name=Ultra") } + 1).joinToString("\n")

        assertEquals("", parseUsbDevices(cut))
    }

    // ── Battery and power ────────────────────────────────────────────

    @Test
    fun theBatteryDumpGivesThePowerSourceAndStatus() {
        val ac = fixture("dumpsys_battery_ac.txt")
        assertEquals("ac", parsePowerSource(ac))
        assertEquals("charging", parseBatteryStatus(ac))

        val usb = ac.replace("AC powered: true", "AC powered: false").replace("USB powered: false", "USB powered: true")
        assertEquals("usb", parsePowerSource(usb))

        val wireless = ac.replace("AC powered: true", "AC powered: false").replace("Wireless powered: false", "Wireless powered: true")
        assertEquals("wireless", parsePowerSource(wireless))

        val unplugged = ac.replace("AC powered: true", "AC powered: false").replace("status: 2", "status: 3")
        assertEquals("none", parsePowerSource(unplugged))
        assertEquals("discharging", parseBatteryStatus(unplugged))
        assertEquals("full", parseBatteryStatus(ac.replace("status: 2", "status: 5")))
        assertEquals("not_charging", parseBatteryStatus(ac.replace("status: 2", "status: 4")))
        assertNull(parsePowerSource("Current Battery Service state:\n"), "a dump that names no source says nothing")
        assertNull(parseBatteryStatus("nothing"))
    }

    @Test
    fun theScreenIsAwakeOrNotFromTheWakefulness() {
        val awake = fixture("dumpsys_power_awake.txt")

        assertEquals("true", parseScreenAwake(awake))
        assertEquals("false", parseScreenAwake(awake.replace("mWakefulness=Awake", "mWakefulness=Asleep")))
        assertEquals("false", parseScreenAwake(awake.replace("mWakefulness=Awake", "mWakefulness=Dozing")))
        assertEquals("true", parseScreenAwake(awake.replace("mWakefulness=Awake", "mWakefulness=Dreaming")))
        assertNull(parseScreenAwake("POWER MANAGER (dumpsys power)\n"))
    }

    @Test
    fun theRotationIsReadFromTheRealDisplayDump() {
        assertEquals(0, parseDisplayRotation(fixture("dumpsys_window_displays_portrait.txt")))
        assertEquals("0", (parseDeviceState("rotation", fixture("dumpsys_window_displays_portrait.txt")) as DeviceStateReading.Value).text)
    }

    // ── Bluetooth ────────────────────────────────────────────────────

    @Test
    fun bluetoothOnWithNothingConnectedIsAnEmptyValue() {
        assertEquals("", parseBluetoothConnected(fixture("dumpsys_bluetooth_manager_on.txt")))
    }

    @Test
    fun aConnectedBluetoothDeviceIsListedByName() {
        val dump = fixture("dumpsys_bluetooth_manager_on.txt").replace(
            "  Connected devices: 0",
            "  Connected devices: 2\n    XX:XX:XX:XX:AB:CD [BR/EDR] JBL Flip 5\n    XX:XX:XX:XX:12:34\n",
        )

        assertEquals(listOf("JBL Flip 5", "XX:XX:XX:XX:12:34"), parseBluetoothConnected(dump)?.lines())
    }

    @Test
    fun bluetoothTurnedOffOrAnUnknownDumpIsHandledBestEffort() {
        assertEquals("", parseBluetoothConnected("Bluetooth Status:\n  State:         OFF\n"))
        assertEquals("connected device", parseBluetoothConnected("  State: ON\n  ConnectionState: STATE_CONNECTED\n"))
        assertEquals("", parseBluetoothConnected("  State: ON\n  ConnectionState: STATE_DISCONNECTED\n"))
        assertNull(parseBluetoothConnected("something else entirely"))
    }

    // ── Audio ────────────────────────────────────────────────────────

    @Test
    fun theSpeakerAloneIsAnEmptyValue() {
        assertEquals("", parseAudioDevices(fixture("dumpsys_audio_speaker.txt")))
    }

    @Test
    fun aWiredOrUsbHeadsetIsReadFromTheStreamDevices() {
        val speaker = fixture("dumpsys_audio_speaker.txt")

        assertEquals("headset", parseAudioDevices(speaker.replace("Devices: speaker(2)", "Devices: headset(4)")))
        assertEquals("headphone", parseAudioDevices(speaker.replace("Devices: speaker(2)", "Devices: headphone(8)")))
        assertEquals(
            "headset\nusb_headset",
            parseAudioDevices(speaker.replace("Devices: speaker(2)", "Devices: usb_headset(4000000), headset(4)")),
        )
        assertNull(parseAudioDevices("AudioService Dumpsys\n"), "a dump without stream devices says nothing")
    }

    // ── Settings toggles and the source table ────────────────────────

    @Test
    fun wifiStaysOnInTheAirplaneOverrideState() {
        assertEquals("true", parseWifiEnabled("2\n"))
        assertEquals("false", parseWifiEnabled("0"))
        assertNull(parseWifiEnabled("null"))
    }

    @Test
    fun everyKeyBelongsToExactlyOneSourceAndAFailedCommandIsUnavailable() {
        assertEquals(KNOWN_DEVICE_STATE_KEYS.sorted(), DeviceStateSource.entries.flatMap { it.keys }.sorted())
        KNOWN_DEVICE_STATE_KEYS.forEach { key -> assertEquals(key, DeviceStateSource.forKey(key)?.keys?.firstOrNull { it == key }) }
        listOf("", "Can't find service: usb", "Permission Denial: can't dump").forEach { answer ->
            val reading = assertIs<DeviceStateReading.Unavailable>(parseDeviceState("power.source", answer))
            assertTrue(reading.reason.contains("power.source"), reading.reason)
        }
    }
}
