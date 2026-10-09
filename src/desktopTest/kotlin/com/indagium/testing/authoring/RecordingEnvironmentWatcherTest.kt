@file:Suppress("MagicNumber") // Fixture timestamps and counts, not tunable constants.

package com.indagium.testing.authoring

import com.indagium.capture.CaptureCommandResult
import com.indagium.testing.device.DeviceStateSource
import com.indagium.testing.device.KNOWN_DEVICE_STATE_KEYS
import com.indagium.testing.deviceStateFixture
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun fixture(name: String) = deviceStateFixture(name)

/** The probe's output for a quiet emulator: the real captures, with a section replaced or left out where [change] says so. */
internal fun probeOutput(change: Map<DeviceStateSource, String?> = emptyMap()): String {
    val base = mapOf(
        DeviceStateSource.USB to fixture("dumpsys_usb_no_host.txt"),
        DeviceStateSource.BATTERY to fixture("dumpsys_battery_ac.txt"),
        DeviceStateSource.BLUETOOTH_ENABLED to "1\n",
        DeviceStateSource.BLUETOOTH_CONNECTED to fixture("dumpsys_bluetooth_manager_on.txt"),
        DeviceStateSource.WIFI to "1\n",
        DeviceStateSource.AIRPLANE to "0\n",
        DeviceStateSource.MOBILE_DATA to "1\n",
        DeviceStateSource.AUDIO to fixture("dumpsys_audio_speaker.txt"),
        DeviceStateSource.POWER to fixture("dumpsys_power_awake.txt"),
        DeviceStateSource.ROTATION to fixture("dumpsys_window_displays_portrait.txt"),
    )
    return DeviceStateSource.entries.joinToString("") { source ->
        val text = if (source in change) change[source] else base[source]
        if (text == null) "" else "$ENVIRONMENT_MARKER${source.section}\n${text.trimEnd()}\n"
    }
}

private class FakeEnvironmentAdb(private val answers: List<CaptureCommandResult>) : RecordingAdb {
    val calls = CopyOnWriteArrayList<List<String>>()
    private val next = AtomicInteger()

    override fun run(arguments: List<String>, timeout: Duration, outputLimitBytes: Int): CaptureCommandResult {
        calls += arguments
        return answers[minOf(next.getAndIncrement(), answers.lastIndex)]
    }
}

private fun ok(text: String) = CaptureCommandResult(0, text.toByteArray(), ByteArray(0))

private class CollectingEnvironmentSink : EnvironmentSink {
    val events = CopyOnWriteArrayList<EnvironmentEvent>()
    val warnings = CopyOnWriteArrayList<String>()

    override fun environmentEvents(events: List<EnvironmentEvent>) {
        this.events += events
    }

    override fun environmentWarning(message: String) {
        warnings += message
    }
}

private val usbAttached = fixture("dumpsys_usb_host_attached.txt")

class RecordingEnvironmentWatcherTest {
    // ── The probe ────────────────────────────────────────────────────

    @Test
    fun theProbeIsOneConstantRemoteCommandWithNoQuoteInIt() {
        val script = ENVIRONMENT_PROBE_SCRIPT

        assertEquals(listOf("shell", "sh -c '$script'"), ENVIRONMENT_PROBE_COMMAND)
        assertFalse('\'' in script, "a single quote would end the remote shell's quoting")
        assertFalse('"' in script, "a double quote would need escaping when the host is Windows")
        DeviceStateSource.entries.forEach { source ->
            assertTrue("echo $ENVIRONMENT_MARKER${source.section}; ${source.command.joinToString(" ")} 2>&1 | head -c" in script, source.name)
        }
        assertTrue(script.all { it.code in 32..126 }, "plain ASCII, nothing to interpret")
    }

    @Test
    fun aQuietDeviceGivesEveryKnownKeyItsValue() {
        val snapshot = parseEnvironmentOutput(probeOutput())

        assertEquals(emptyList(), snapshot.failedKeys)
        assertEquals(KNOWN_DEVICE_STATE_KEYS.toSet(), snapshot.values.keys)
        assertEquals(
            mapOf(
                "usb.devices" to "", "bt.enabled" to "true", "bt.connected" to "", "wifi.enabled" to "true", "airplane" to "false",
                "mobile_data" to "true", "power.source" to "ac", "battery.status" to "charging", "audio.devices" to "", "screen.awake" to "true",
                "rotation" to "0",
            ),
            snapshot.values,
        )
    }

    @Test
    fun aFailingSectionIsDroppedAndTheOthersStillRead() {
        val snapshot = parseEnvironmentOutput(
            probeOutput(
                mapOf(
                    DeviceStateSource.AUDIO to "Can't find service: audio",
                    DeviceStateSource.POWER to null,
                    DeviceStateSource.BLUETOOTH_ENABLED to "null",
                ),
            ),
        )

        assertEquals(setOf("audio.devices", "screen.awake", "bt.enabled"), snapshot.failedKeys.toSet())
        assertTrue("usb.devices" in snapshot.values && "wifi.enabled" in snapshot.values)
        assertTrue(snapshot.values.keys.none { it in snapshot.failedKeys })
    }

    @Test
    fun anAttachedUsbDeviceAndAWiredHeadsetShowUpInTheirKeys() {
        val speaker = fixture("dumpsys_audio_speaker.txt")
        val snapshot = parseEnvironmentOutput(
            probeOutput(
                mapOf(
                    DeviceStateSource.USB to usbAttached,
                    DeviceStateSource.AUDIO to speaker.replace("Devices: speaker(2)", "Devices: headset(4)"),
                ),
            ),
        )

        assertEquals("046d:c52b USB Receiver\n0781:5581 Ultra", snapshot.values["usb.devices"], "list values are sorted so the dump's order is no change")
        assertEquals("headset", snapshot.values["audio.devices"])
    }

    // ── The differ ───────────────────────────────────────────────────

    @Test
    fun theFirstSnapshotIsTheBaselineAndNeverAnEvent() {
        val differ = EnvironmentDiffer()

        assertEquals(emptyList(), differ.accept(mapOf("wifi.enabled" to "true", "airplane" to "false"), 1_000))
        assertEquals(emptyList(), differ.accept(mapOf("wifi.enabled" to "true", "airplane" to "false"), 3_000))
        // A key that only appears later (its section failed at first) gets its baseline then, silently.
        assertEquals(emptyList(), differ.accept(mapOf("wifi.enabled" to "true", "bt.enabled" to "false"), 5_000))
    }

    @Test
    fun aChangeIsReportedOnlyWhenTheNextPollSeesItToo() {
        val differ = EnvironmentDiffer()
        differ.accept(mapOf("wifi.enabled" to "true"), 1_000)

        assertEquals(emptyList(), differ.accept(mapOf("wifi.enabled" to "false"), 3_000), "one poll could be a glitch")
        val events = differ.accept(mapOf("wifi.enabled" to "false"), 5_000)

        assertEquals(listOf(EnvironmentEvent("wifi.enabled", "true", "false", atMs = 3_000, sinceMs = 1_000)), events)
        assertEquals(emptyList(), differ.accept(mapOf("wifi.enabled" to "false"), 7_000), "reported once")
        assertEquals(emptyList(), differ.accept(mapOf("wifi.enabled" to "true"), 9_000))
        assertEquals(1, differ.accept(mapOf("wifi.enabled" to "true"), 11_000).size, "and the change back is its own event")
    }

    @Test
    fun aValueThatFlapsBackIsNeverReported() {
        val differ = EnvironmentDiffer()
        differ.accept(mapOf("bt.enabled" to "true"), 1_000)

        assertEquals(emptyList(), differ.accept(mapOf("bt.enabled" to "false"), 3_000))
        assertEquals(emptyList(), differ.accept(mapOf("bt.enabled" to "true"), 5_000))
        assertEquals(emptyList(), differ.accept(mapOf("bt.enabled" to "false"), 7_000))
        assertEquals(emptyList(), differ.accept(mapOf("bt.enabled" to "true"), 9_000))
    }

    @Test
    fun aKeyMissingFromAPollChangesNothing() {
        val differ = EnvironmentDiffer()
        differ.accept(mapOf("wifi.enabled" to "true", "airplane" to "false"), 1_000)
        differ.accept(mapOf("wifi.enabled" to "false", "airplane" to "false"), 3_000)

        assertEquals(emptyList(), differ.accept(mapOf("airplane" to "false"), 5_000), "wifi.enabled could not be read this time")
        assertEquals(1, differ.accept(mapOf("wifi.enabled" to "false", "airplane" to "false"), 7_000).size)
    }

    // ── Words ────────────────────────────────────────────────────────

    private fun event(key: String, from: String, to: String) = EnvironmentEvent(key, from, to, 10_000)

    @Test
    fun eventsAreToldInWordsAMachineAndAPersonCanUse() {
        val usbIn = event("usb.devices", "", "0781:5581 Ultra")
        assertEquals("USB device 'Ultra' (0781:5581) connected", usbIn.summary())
        assertEquals("Connect the USB device 'Ultra' (0781:5581) to the phone", usbIn.instruction())
        assertEquals("0781:5581", usbIn.expectedRegex())
        assertEquals("USB device 0781:5581 disconnected", event("usb.devices", "0781:5581", "").summary())
        assertEquals("Bluetooth turned off", event("bt.enabled", "true", "false").summary())
        assertEquals("Turn Bluetooth off", event("bt.enabled", "true", "false").instruction())
        assertEquals("Charger disconnected", event("power.source", "ac", "none").summary())
        assertEquals("Charger connected (USB)", event("power.source", "none", "usb").summary())
        assertEquals("Power source changed from AC to USB", event("power.source", "ac", "usb").summary())
        assertEquals("Bluetooth device 'JBL Flip' connected", event("bt.connected", "", "JBL Flip").summary())
        assertEquals("Wired headset connected", event("audio.devices", "", "headset").summary())
        assertEquals("Screen turned off", event("screen.awake", "true", "false").summary())
        assertEquals("Screen rotated to landscape (90°)", event("rotation", "0", "1").summary())
        assertEquals("Wi-Fi turned on", event("wifi.enabled", "false", "true").summary())
        assertEquals("true", event("wifi.enabled", "false", "true").expectedRegex())
        assertNull(event("battery.status", "charging", "full").expectedRegex(), "a battery status proposes no check")
    }

    @Test
    fun aDisconnectedDeviceIsCheckedByItsAbsenceAndDeviceTextIsMadeSafe() {
        val gone = event("usb.devices", "0781:5581 Ultra", "")
        val absent = Regex(requireNotNull(gone.expectedRegex()))
        assertTrue(absent.containsMatchIn(""))
        assertTrue(absent.containsMatchIn("046d:c52b USB Receiver"))
        assertFalse(absent.containsMatchIn("046d:c52b USB Receiver\n0781:5581 Ultra"))

        val tricky = event("bt.connected", "", "Evil\tname\u0000 'x' (.*)")
        assertEquals("Bluetooth device 'Evil name 'x' (.*)' connected", tricky.summary(), "control characters never reach a sentence")
        val pattern = Regex(requireNotNull(tricky.expectedRegex()))
        assertTrue(pattern.containsMatchIn("Evil\tname\u0000 'x' (.*)"), "the device's own text is matched literally, not as a pattern")
        assertFalse(pattern.containsMatchIn("Evil\tname\u0000 'x' (anything)"))
    }

    // ── The watcher ──────────────────────────────────────────────────

    private fun watcher(adb: FakeEnvironmentAdb, sink: CollectingEnvironmentSink, clock: () -> Long) =
        RecordingEnvironmentWatcher(adb, sink, clock, pollMs = 10)

    @Test
    fun aUsbDeviceAppearingIsReportedOnceAndTheBaselineIsNot() {
        val quiet = ok(probeOutput())
        val plugged = ok(probeOutput(mapOf(DeviceStateSource.USB to usbAttached)))
        val adb = FakeEnvironmentAdb(listOf(quiet, plugged, plugged, plugged))
        val sink = CollectingEnvironmentSink()
        var now = 0L
        val watcher = watcher(adb, sink) { now += 2_000; now }

        repeat(4) { watcher.pollOnce() }

        assertEquals(1, sink.events.size)
        val reported = sink.events.single()
        assertEquals("usb.devices", reported.key)
        assertEquals("", reported.from)
        assertEquals("046d:c52b USB Receiver\n0781:5581 Ultra", reported.to)
        assertEquals(4_000L, reported.atMs, "timed at the poll that first saw it")
        assertEquals(2_000L, reported.sinceMs)
        assertEquals(emptyList(), sink.warnings)
        assertTrue(adb.calls.all { it == ENVIRONMENT_PROBE_COMMAND }, "every poll is the one constant command")
    }

    @Test
    fun anUnreadableSectionWarnsOnceNoMatterHowOftenItFails() {
        val broken = ok(probeOutput(mapOf(DeviceStateSource.AUDIO to "Can't find service: audio", DeviceStateSource.BLUETOOTH_CONNECTED to null)))
        val adb = FakeEnvironmentAdb(listOf(broken))
        val sink = CollectingEnvironmentSink()
        val watcher = watcher(adb, sink) { 0L }

        repeat(5) { watcher.pollOnce() }

        assertEquals(1, sink.warnings.size, sink.warnings.toString())
        assertTrue("audio.devices" in sink.warnings.single() && "bt.connected" in sink.warnings.single())
        assertEquals(emptyList(), sink.events)
    }

    @Test
    fun aFailingAdbWarnsOnceAndPollingGoesOn() {
        val failing = CaptureCommandResult(1, ByteArray(0), "device offline".toByteArray())
        val timedOut = CaptureCommandResult(-1, ByteArray(0), ByteArray(0), timedOut = true)
        val adb = FakeEnvironmentAdb(listOf(failing, timedOut, ok(probeOutput())))
        val sink = CollectingEnvironmentSink()
        val watcher = watcher(adb, sink) { 0L }

        repeat(4) { watcher.pollOnce() }

        assertEquals(1, sink.warnings.size, sink.warnings.toString())
        assertEquals(4, adb.calls.size)
    }

    @Test
    fun nothingIsReportedAfterTheWatcherIsClosed() {
        val adb = FakeEnvironmentAdb(listOf(ok(probeOutput()), ok(probeOutput(mapOf(DeviceStateSource.WIFI to "0")))))
        val sink = CollectingEnvironmentSink()
        val watcher = watcher(adb, sink) { 0L }
        watcher.pollOnce()
        watcher.close()

        repeat(3) { watcher.pollOnce() }

        assertEquals(1, adb.calls.size, "a closed watcher does not even ask the device")
        assertEquals(emptyList(), sink.events)
    }

    @Test
    fun theWatcherPollsOnItsOwnThreadUntilItIsClosed() {
        val adb = FakeEnvironmentAdb(listOf(ok(probeOutput())))
        val sink = CollectingEnvironmentSink()
        val watcher = RecordingEnvironmentWatcher(adb, sink, pollMs = 5)

        watcher.start()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (adb.calls.size < 3 && System.nanoTime() < deadline) Thread.sleep(5)
        watcher.close()
        Thread.sleep(50)
        val stoppedAt = adb.calls.size

        assertTrue(stoppedAt >= 3, "polled repeatedly: $stoppedAt")
        Thread.sleep(50)
        assertEquals(stoppedAt, adb.calls.size, "and stopped when closed")
    }
}
