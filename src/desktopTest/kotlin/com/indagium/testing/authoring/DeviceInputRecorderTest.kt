@file:Suppress("MagicNumber", "SpreadOperator") // Synthetic event streams and fixture sizes, not tunable constants.

package com.indagium.testing.authoring

import com.indagium.capture.CaptureCommandResult
import com.indagium.capture.StreamingFakeProcess
import com.indagium.capture.awaitCapture
import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorKeyAction
import com.indagium.capture.mirror.MirrorTouchAction
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val KEYS_PATH = "/dev/input/event2"
private const val UHID_PATH = "/dev/input/event5"

class DeviceInputRecorderTest {
    private val sink = CollectingSink()
    private val environment = FakeInputEnvironment()
    private val process = StreamingFakeProcess()
    private val devices = GeteventDeviceParser.parse(EMULATOR_DUMP)

    private fun plan(rotation: Int = 0, warnings: List<String> = emptyList()) =
        DeviceInputPreflight.Ready(devices, DisplayGeometry(1080, 2400), rotation, warnings)

    private fun recorder(plan: DeviceInputPreflight.Ready = plan(), start: () -> StreamingFakeProcess = { process }) =
        DeviceInputRecorder(plan, startStream = start, environment = environment, sink = sink)

    private fun StreamingFakeProcess.emitLines(vararg lines: String) = emit(lines.joinToString("\n", postfix = "\n"))

    private fun touchStream(startMicros: Long): Array<String> = arrayOf(
        line(startMicros, EMULATOR_TOUCH, "EV_ABS", "ABS_MT_TRACKING_ID", hex(4)),
        line(startMicros, EMULATOR_TOUCH, "EV_ABS", "ABS_MT_POSITION_X", hex(16384)),
        line(startMicros, EMULATOR_TOUCH, "EV_ABS", "ABS_MT_POSITION_Y", hex(16384)),
        line(startMicros, EMULATOR_TOUCH, "EV_SYN", "SYN_REPORT", hex(0)),
        line(startMicros + 90_000, EMULATOR_TOUCH, "EV_ABS", "ABS_MT_TRACKING_ID", hex(-1)),
        line(startMicros + 90_000, EMULATOR_TOUCH, "EV_SYN", "SYN_REPORT", hex(0)),
    )

    @Test
    fun streamedLinesBecomeCommandsInOrderAndTheMirrorsVirtualDevicesAreIgnored() {
        val recorder = recorder()
        recorder.start()
        process.emitLines(
            "add device 1: /dev/input/event3",
            "  name:     \"virtio_input_multi_touch_1\"",
            *touchStream(1_000_000),
            line(1_200_000, UHID_PATH, "EV_KEY", "KEY_A", "DOWN"),
            line(1_300_000, KEYS_PATH, "EV_KEY", "KEY_VOLUMEUP", "DOWN"),
            line(1_350_000, KEYS_PATH, "EV_KEY", "KEY_VOLUMEUP", "UP"),
            "this is not an event line",
        )
        awaitCapture { sink.delivered.size >= 3 }

        val commands = sink.delivered.map { (it as CollectingSink.Delivered.Input).command }
        assertEquals(3, commands.size, "the UHID keyboard and the noise lines produced nothing")
        assertEquals(MirrorTouchAction.DOWN, (commands[0] as MirrorControlCommand.Touch).action)
        assertEquals(MirrorTouchAction.UP, (commands[1] as MirrorControlCommand.Touch).action)
        assertEquals(MirrorControlCommand.Key(MirrorKeyAction.DOWN, KEYCODE_VOLUME_UP), commands[2])
        val times = sink.delivered.map { (it as CollectingSink.Delivered.Input).atMs }
        assertEquals(times.sorted(), times)
        recorder.close()
    }

    @Test
    fun aRotationTheProbeLearnedIsUsedForLaterTouches() {
        val recorder = recorder()
        recorder.start()
        recorder.updateRotation(1)
        process.emitLines(*touchStream(2_000_000))
        awaitCapture { sink.touches.size >= 2 }
        assertEquals(2400, sink.touches.first().screenWidth)
        assertEquals(1080, sink.touches.first().screenHeight)
        recorder.close()
    }

    @Test
    fun finishDeliversPendingTypedTextThenReportsFinishedAndStopsTheStream() {
        val recorder = recorder()
        recorder.start()
        process.emitLines(
            line(1_000_000, "/dev/input/event1", "EV_KEY", "KEY_A", "DOWN"),
            line(1_010_000, "/dev/input/event1", "EV_KEY", "KEY_A", "UP"),
        )
        // /dev/input/event1 is the emulator dump's virtio_input_keyboard; its keys type text. Give the reader time to take them in.
        Thread.sleep(300)
        recorder.finish()
        awaitCapture { sink.finished == 1 }

        val inputs = sink.delivered.map { (it as CollectingSink.Delivered.Input).command }
        assertEquals(listOf(MirrorControlCommand.Text("a")), inputs)
        awaitCapture { !process.isAlive }
        assertEquals(1, sink.finished)
    }

    @Test
    fun closeStopsTheStreamWithoutReportingFinished() {
        val recorder = recorder()
        recorder.start()
        recorder.close()
        awaitCapture { !process.isAlive }
        assertEquals(0, sink.finished)
        recorder.finish() // after close, finishing is not an error and reports nothing pending
        assertEquals(1, sink.finished)
    }

    @Test
    fun aStreamThatCannotStartWarnsAndHasNothingPending() {
        val recorder = DeviceInputRecorder(plan(), startStream = { throw IOException("adb is gone") }, environment = environment, sink = sink)
        recorder.start()
        assertTrue(sink.warnings.any { "adb is gone" in it })
        recorder.finish()
        assertEquals(1, sink.finished)
    }

    @Test
    fun aStreamThatEndsOnItsOwnWarnsOnce() {
        val recorder = recorder()
        recorder.start()
        process.finish()
        awaitCapture { sink.warnings.any { "getevent ended" in it } }
        assertEquals(1, sink.warnings.count { "getevent ended" in it })
        recorder.close()
    }

    @Test
    fun preflightWarningsAreReportedWhenRecordingStarts() {
        val recorder = recorder(plan(warnings = listOf("two touchscreens")))
        recorder.start()
        assertEquals(listOf("two touchscreens"), sink.warnings.toList())
        recorder.close()
    }

    // ── preflight ────────────────────────────────────────────────────

    private fun adb(vararg answers: Pair<List<String>, CaptureCommandResult>) = RecordingAdb { arguments, _, _ ->
        answers.toMap()[arguments] ?: CaptureCommandResult(1, ByteArray(0), "no such command".toByteArray())
    }

    private fun ok(text: String) = CaptureCommandResult(0, text.toByteArray(), ByteArray(0))

    @Test
    fun preflightReadsDevicesDisplaySizeAndRotation() {
        val result = preflightDeviceInput(
            adb(
                GETEVENT_DEVICES_COMMAND to ok(EMULATOR_DUMP),
                WM_SIZE_COMMAND to ok("Physical size: 1080x2400\n"),
                DISPLAY_ROTATION_COMMAND to ok("mCurrentRotation=ROTATION_270"),
            ),
        )
        val ready = assertIs<DeviceInputPreflight.Ready>(result)
        assertEquals(DisplayGeometry(1080, 2400), ready.geometry)
        assertEquals(3, ready.rotation)
        assertEquals(1, ready.devices.touchScreens.size)
    }

    @Test
    fun getEventThatFailsIsUnavailableNotAnException() {
        assertIs<DeviceInputPreflight.Unavailable>(preflightDeviceInput(adb()))
        assertIs<DeviceInputPreflight.Unavailable>(preflightDeviceInput(adb(GETEVENT_DEVICES_COMMAND to ok(""))))
        assertIs<DeviceInputPreflight.Unavailable>(preflightDeviceInput(adb(GETEVENT_DEVICES_COMMAND to ok("Permission denied\n"))))
        val timedOut = CaptureCommandResult(-1, ByteArray(0), ByteArray(0), timedOut = true)
        assertIs<DeviceInputPreflight.Unavailable>(preflightDeviceInput(adb(GETEVENT_DEVICES_COMMAND to timedOut)))
        val throwing = RecordingAdb { _, _, _ -> throw IOException("adb server down") }
        assertIs<DeviceInputPreflight.Unavailable>(preflightDeviceInput(throwing))
    }

    @Test
    fun withoutADisplaySizeTouchIsDroppedWhenKeysRemainAndRefusedOtherwise() {
        val withKeys = preflightDeviceInput(adb(GETEVENT_DEVICES_COMMAND to ok(EMULATOR_DUMP)))
        val ready = assertIs<DeviceInputPreflight.Ready>(withKeys)
        assertTrue(ready.devices.touchScreens.isEmpty())
        assertTrue(ready.devices.keyDevices.isNotEmpty())
        assertTrue(ready.warnings.any { "display size" in it })
        assertEquals(0, ready.rotation)

        val touchOnly = "add device 1: /dev/input/event0\n  name:     \"ts\"\n  events:\n    KEY (0001): BTN_TOUCH\n" +
            "    ABS (0003): ABS_X : value 0, min 0, max 799, fuzz 0, flat 0\n                ABS_Y : value 0, min 0, max 479, fuzz 0, flat 0\n" +
            "  input props:\n    INPUT_PROP_DIRECT\n"
        assertIs<DeviceInputPreflight.Unavailable>(preflightDeviceInput(adb(GETEVENT_DEVICES_COMMAND to ok(touchOnly))))
    }

    @Test
    fun severalTouchscreensAreMentioned() {
        val second = EMULATOR_DUMP.replace("virtio_input_multi_touch_1", "virtio_input_multi_touch_2").replace("event3", "event9")
        val dump = EMULATOR_DUMP + "\n" + second.substringBefore("add device 2:")
        val ready = assertIs<DeviceInputPreflight.Ready>(
            preflightDeviceInput(adb(GETEVENT_DEVICES_COMMAND to ok(dump), WM_SIZE_COMMAND to ok("Physical size: 1080x2400"))),
        )
        assertEquals(2, ready.devices.touchScreens.size)
        assertTrue(ready.warnings.any { "touchscreens" in it })
    }
}
