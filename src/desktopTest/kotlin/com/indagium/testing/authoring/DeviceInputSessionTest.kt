@file:Suppress("MagicNumber") // Fixture coordinates and timestamps, not tunable constants.

package com.indagium.testing.authoring

import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorKeyAction
import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.testing.device.UiNode
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeHandle(private val whenFinished: () -> Unit = {}) : DeviceInputHandle {
    @Volatile var finishCalls = 0

    @Volatile var closed = false

    override fun finish() {
        finishCalls++
        whenFinished()
    }

    override fun close() {
        closed = true
    }
}

private fun editField(text: String, password: Boolean = false) = UiNode(
    left = 0, top = 100, right = 1080, bottom = 200, text = if (password) "" else text, contentDesc = "",
    resourceId = "com.example:id/search_box", className = "EditText", clickable = true, enabled = true, scrollable = false,
    packageName = "com.example", password = password, focused = true,
)

private fun awaitTrue(timeoutMs: Long = 4_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Timed out waiting for the session" }
        Thread.sleep(10)
    }
}

class DeviceInputSessionTest {
    private fun touch(action: MirrorTouchAction, x: Int, y: Int) = touchCommand(action, x, y)

    private fun TestStepRecordingSession.device(command: MirrorControlCommand, at: Long) = accept(command, null, at, InputSource.DEVICE)

    @Test
    fun deviceTouchesAndSystemKeysBecomeLabelledRows() {
        val session = TestStepRecordingSession("device-1")
        session.device(touch(MirrorTouchAction.DOWN, 540, 1200), 1_000)
        session.device(touch(MirrorTouchAction.UP, 540, 1200), 1_080)
        listOf(
            KEYCODE_POWER, KEYCODE_VOLUME_UP, KEYCODE_VOLUME_DOWN, KEYCODE_VOLUME_MUTE, KEYCODE_APP_SWITCH, KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_HOME,
        ).forEachIndexed { index, code ->
            session.device(MirrorControlCommand.Key(MirrorKeyAction.DOWN, code), 2_000L + index * 100)
        }
        session.device(MirrorControlCommand.Key(MirrorKeyAction.DOWN, KEYCODE_BACK), 3_000)

        val rows = session.snapshot.value.steps
        assertTrue(rows[0].action.startsWith("Tap at (50%, 50%)"))
        assertEquals(RecordedInputKind.TAP, rows[0].kind)
        assertEquals(
            listOf(
                "Press Power", "Press Volume up", "Press Volume down", "Press Mute", "Press Recents", "Press Play/Pause", "Press Home",
                "Press Back",
            ),
            rows.drop(1).map { it.action },
        )
        assertEquals(RecordedInputKind.BACK, rows.last().kind)
        assertTrue(rows.drop(1).dropLast(1).all { it.kind == RecordedInputKind.KEY })
        assertTrue(rows.all { "mirror" !in it.screenContext.orEmpty() }, "a row from the device does not claim to come through the mirror")
        assertTrue(rows.drop(1).all { "observed on the device" in it.screenContext.orEmpty() })
        assertTrue(session.snapshot.value.warnings.none { "no friendly label" in it }, "every system key has a label")
        session.close()
    }

    @Test
    fun mirrorSystemCommandsAreRowsNotWarnings() {
        val session = TestStepRecordingSession("device-1")
        session.accept(MirrorControlCommand.ExpandNotifications)
        session.accept(MirrorControlCommand.ExpandSettings)
        session.accept(MirrorControlCommand.CollapsePanels)
        session.accept(MirrorControlCommand.RotateDevice)
        session.accept(MirrorControlCommand.SetDisplayPowerOn)
        session.accept(MirrorControlCommand.CollapseNotifications)

        val rows = session.snapshot.value.steps
        assertEquals(
            listOf(
                "Open the notification shade", "Open quick settings", "Close the notification panels", "Rotate the device",
                "Turn the screen on", "Close the notification panels",
            ),
            rows.map { it.action },
        )
        assertEquals(
            listOf(
                RecordedInputKind.NOTIFICATIONS, RecordedInputKind.QUICK_SETTINGS, RecordedInputKind.CLOSE_PANELS,
                RecordedInputKind.ROTATE, RecordedInputKind.SCREEN_ON, RecordedInputKind.CLOSE_PANELS,
            ),
            rows.map { it.kind },
        )
        assertTrue(session.snapshot.value.warnings.none { "not converted" in it })
        assertTrue(rows.all { "sent through the device mirror" in it.screenContext.orEmpty() })
        session.close()
    }

    @Test
    fun theSameInputFromTheOtherSourceWithinFiftyMillisecondsIsOneRow() {
        val session = TestStepRecordingSession("device-1")
        val volume = MirrorControlCommand.Key(MirrorKeyAction.DOWN, KEYCODE_VOLUME_UP)
        session.accept(volume, null, 1_000, InputSource.MIRROR)
        session.accept(volume, null, 1_030, InputSource.DEVICE)
        assertEquals(1, session.snapshot.value.steps.size)

        session.accept(volume, null, 1_200, InputSource.DEVICE) // 170 ms later: a second press
        assertEquals(2, session.snapshot.value.steps.size)

        session.accept(volume, null, 1_220, InputSource.DEVICE) // the same source twice is never a duplicate
        assertEquals(3, session.snapshot.value.steps.size)

        session.accept(touch(MirrorTouchAction.DOWN, 100, 100), null, 5_000, InputSource.MIRROR)
        session.accept(touch(MirrorTouchAction.DOWN, 102, 99).copy(pointerId = 7), null, 5_020, InputSource.DEVICE)
        session.accept(touch(MirrorTouchAction.UP, 100, 100), null, 5_060, InputSource.MIRROR)
        session.accept(touch(MirrorTouchAction.UP, 102, 99).copy(pointerId = 7), null, 5_070, InputSource.DEVICE)
        assertEquals(4, session.snapshot.value.steps.size, "a tap seen by both sources is one tap")
        session.close()
    }

    // ── keyboard rows ────────────────────────────────────────────────

    @Test
    fun aKeyboardBurstIsOneTextRowWithoutAnyPosition() {
        val session = TestStepRecordingSession("device-1")
        session.deviceInputSink.keyboardText(KeyboardTextReading("lofi", "search_box", password = false), 1_000, 1_400)

        val row = session.snapshot.value.steps.single()
        assertEquals("Enter text: lofi", row.action)
        assertEquals(RecordedInputKind.TEXT, row.kind)
        assertEquals(1_000L, row.inputStartMs)
        assertEquals(1_400L, row.inputAtMs)
        assertTrue(row.gesturePath.isEmpty())
        assertNull(row.tappedElement)
        assertTrue("search_box" in row.screenContext.orEmpty())
        session.close()
    }

    @Test
    fun aPasswordBurstIsMaskedAndNeverCarriesTheField() {
        val session = TestStepRecordingSession("device-1")
        session.deviceInputSink.keyboardText(KeyboardTextReading(null, "pw", password = true), 1_000, 1_400)

        val row = session.snapshot.value.steps.single()
        assertEquals("Enter text: ••••", row.action)
        assertTrue(row.gesturePath.isEmpty())
        assertFalse("hunter2" in row.screenContext.orEmpty())
        session.close()
    }

    @Test
    fun anUnreadableFieldGivesAGenericRowWithNoTextAndNoLength() {
        val session = TestStepRecordingSession("device-1")
        session.deviceInputSink.keyboardText(KeyboardTextReading(null, null, password = false), 1_000, 1_400)
        assertEquals("Type text on the on-screen keyboard", session.snapshot.value.steps.single().action)
        session.close()
    }

    private fun sessionWithFocusedField(node: UiNode): TestStepRecordingSession {
        val session = TestStepRecordingSession(
            "device-1",
            screenProbe = {
                RecordingScreenState(
                    seq = 0, startedAt = 100, finishedAt = 500, packageName = "com.example", activity = "Main", nodes = listOf(node),
                    screenWidth = 1080, screenHeight = 2400,
                )
            },
            settleDelayMs = 60_000L,
        )
        awaitTrue { session.screenState(1) != null }
        return session
    }

    @Test
    fun theTextAlreadyInTheFieldBeforeTheBurstIsNotRecordedAsTyped() {
        val session = sessionWithFocusedField(editField("lo"))
        session.deviceInputSink.keyboardText(KeyboardTextReading("lofi", "search_box", password = false), 1_000, 1_400)
        assertEquals("Enter text: fi", session.snapshot.value.steps.single().action)

        session.deviceInputSink.keyboardText(KeyboardTextReading("lo", "search_box", password = false), 2_000, 2_400)
        assertEquals(1, session.snapshot.value.steps.size, "a burst that changed nothing leaves no row")

        session.deviceInputSink.keyboardText(KeyboardTextReading("", "search_box", password = false), 3_000, 3_400)
        assertEquals("Clear the text field", session.snapshot.value.steps.last().action)
        session.close()
    }

    @Test
    fun aPasswordFieldSeenOnScreenBeforeTheBurstMasksItEvenIfTheLaterReadMissedIt() {
        val session = sessionWithFocusedField(editField("", password = true))
        session.deviceInputSink.keyboardText(KeyboardTextReading("hunter2", null, password = false), 1_000, 1_400)
        assertEquals("Enter text: ••••", session.snapshot.value.steps.single().action)
        session.close()
    }

    @Test
    fun noKeyboardPositionReachesAnyRowWhenTheWholePipelineRuns() {
        val session = TestStepRecordingSession("device-1")
        var now = 0L
        val environment = FakeInputEnvironment().apply {
            shown = true
            frame = ScreenRect(0, 1500, 1080, 2400)
            field = FocusedField("", "pw", password = true)
        }
        val pipeline = DeviceInputPipeline(session.deviceInputSink, environment, { 0 }, { now })
        val taps = listOf(137 to 1711, 593 to 1893, 861 to 2044)
        taps.forEachIndexed { index, (x, y) ->
            val at = 1_000L + index * 150
            now = at
            pipeline.onTouch(touchCommand(MirrorTouchAction.DOWN, x, y), at)
            pipeline.onTouch(touchCommand(MirrorTouchAction.UP, x, y), at + 40)
        }
        now = 1_000L + 3 * 150 + 700
        pipeline.tick(now)
        now += 2_100
        pipeline.tick(now)

        val rows = session.snapshot.value.steps
        assertEquals(1, rows.size)
        assertEquals("Enter text: ••••", rows.single().action)
        assertTrue(rows.all { it.gesturePath.isEmpty() && it.tappedElement == null })
        val everything = rows.joinToString(" ") { it.action + " " + it.screenContext.orEmpty() + " " + it.sourceHint.orEmpty() }
        taps.forEach { (x, y) ->
            assertFalse(x.toString() in everything || y.toString() in everything, "no coordinate of a keyboard tap in $everything")
        }
        assertFalse("%" in everything, "no percent position either")
        session.close()
    }

    // ── tail after stop ──────────────────────────────────────────────

    @Test
    fun stopWaitsForTheRecordersLastTextAndTheRowArrivesAfterStop() = runBlocking {
        lateinit var session: TestStepRecordingSession
        val handle = FakeHandle {
            thread(isDaemon = true) {
                Thread.sleep(150)
                session.deviceInputSink.keyboardText(KeyboardTextReading("lofi", "search_box", password = false), 1_000, 1_400)
                session.deviceInputSink.deviceInputFinished()
            }
        }
        session = TestStepRecordingSession("device-1")
        session.attachDeviceInput(handle)
        session.accept(MirrorControlCommand.Key(MirrorKeyAction.DOWN, KEYCODE_POWER))

        val stopped = session.stop()
        assertFalse(stopped.active)
        assertTrue(stopped.pendingSnapshots >= 1, "the recording is pending until the recorder has delivered its tail")
        assertEquals(1, handle.finishCalls)
        assertEquals(1, stopped.steps.size)

        val drained = session.stopAndDrain()
        assertEquals(0, drained.pendingSnapshots)
        assertEquals(listOf("Press Power", "Enter text: lofi"), drained.steps.map { it.action })
        session.accept(MirrorControlCommand.Key(MirrorKeyAction.DOWN, KEYCODE_BACK))
        assertEquals(2, session.snapshot.value.steps.size, "the mirror's input after stop is still refused")
        session.close()
        assertTrue(handle.closed)
    }

    @Test
    fun aRecorderThatNeverFinishesIsClosedAfterTheTailTimeout() = runBlocking {
        val handle = FakeHandle()
        val session = TestStepRecordingSession("device-1").also { it.tailDrainTimeoutMs = 100 }
        session.attachDeviceInput(handle)
        val drained = session.stopAndDrain()
        assertEquals(0, drained.pendingSnapshots)
        assertTrue(handle.closed)
        assertTrue(drained.warnings.any { "last input on the device" in it })
        session.close()
    }

    @Test
    fun rowsFromTheDeviceAreAcceptedWhileRecordingButNotAfterTheTailEnded() {
        val handle = FakeHandle { }
        val session = TestStepRecordingSession("device-1")
        session.attachDeviceInput(handle)
        session.deviceInputSink.deviceInput(MirrorControlCommand.Key(MirrorKeyAction.DOWN, KEYCODE_VOLUME_DOWN), 1_000)
        session.stop()
        session.deviceInputSink.deviceInput(MirrorControlCommand.Key(MirrorKeyAction.DOWN, KEYCODE_VOLUME_UP), 1_100)
        session.deviceInputSink.deviceInputFinished()
        session.deviceInputSink.deviceInput(MirrorControlCommand.Key(MirrorKeyAction.DOWN, KEYCODE_POWER), 1_200)

        assertEquals(listOf("Press Volume down", "Press Volume up"), session.snapshot.value.steps.map { it.action })
        assertEquals(0, session.snapshot.value.pendingSnapshots)
        session.close()
    }

    @Test
    fun aRecorderAttachedAfterStopIsClosedAtOnce() {
        val session = TestStepRecordingSession("device-1")
        session.stop()
        val late = FakeHandle()
        session.attachDeviceInput(late)
        assertTrue(late.closed)
        assertNotNull(session.snapshot.value)
        session.close()
    }

    @Test
    fun aSessionWithoutADeviceRecorderIsNotPendingAfterStop() {
        val session = TestStepRecordingSession("device-1")
        assertEquals(0, session.stop().pendingSnapshots)
        session.close()
    }
}
