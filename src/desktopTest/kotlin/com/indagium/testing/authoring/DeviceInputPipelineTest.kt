@file:Suppress("MagicNumber") // Fixture coordinates and timestamps, not tunable constants.

package com.indagium.testing.authoring

import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorKeyAction
import com.indagium.capture.mirror.MirrorTouchAction
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SCREEN_WIDTH = 1080
private const val SCREEN_HEIGHT = 2400

internal class FakeInputEnvironment : DeviceInputEnvironment {
    var shown: Boolean? = false
    var frame: ScreenRect? = null
    var field: FocusedField? = null
    var imeReads = 0
    var fieldReads = 0

    override fun rotation(): Int? = null

    override fun imeShown(): Boolean? {
        imeReads++
        return shown
    }

    override fun imeFrame(): ScreenRect? = frame

    override fun focusedField(): FocusedField? {
        fieldReads++
        return field
    }
}

internal class CollectingSink : DeviceInputSink {
    sealed interface Delivered {
        data class Input(val command: MirrorControlCommand, val atMs: Long) : Delivered

        data class Typed(val reading: KeyboardTextReading, val startMs: Long, val endMs: Long) : Delivered
    }

    val delivered = CopyOnWriteArrayList<Delivered>()
    val warnings = CopyOnWriteArrayList<String>()

    @Volatile var finished = 0

    override fun deviceInput(command: MirrorControlCommand, atMs: Long) {
        delivered += Delivered.Input(command, atMs)
    }

    override fun keyboardText(reading: KeyboardTextReading, startMs: Long, endMs: Long) {
        delivered += Delivered.Typed(reading, startMs, endMs)
    }

    override fun deviceInputWarning(message: String) {
        warnings += message
    }

    override fun deviceInputFinished() {
        finished++
    }

    val touches: List<MirrorControlCommand.Touch>
        get() = delivered.filterIsInstance<Delivered.Input>().map { it.command }.filterIsInstance<MirrorControlCommand.Touch>()
}

internal fun touchCommand(action: MirrorTouchAction, x: Int, y: Int, pointer: Long = 0) = MirrorControlCommand.Touch(
    action = action, pointerId = pointer, x = x, y = y, screenWidth = SCREEN_WIDTH, screenHeight = SCREEN_HEIGHT,
)

class DeviceInputPipelineTest {
    private val environment = FakeInputEnvironment()
    private val sink = CollectingSink()
    private var now = 0L
    private val pipeline = DeviceInputPipeline(sink, environment, { 0 }, { now })
    private val keyboardFrame = ScreenRect(0, 1500, SCREEN_WIDTH, SCREEN_HEIGHT)

    private fun tap(x: Int, y: Int, at: Long) {
        now = at
        pipeline.onTouch(touchCommand(MirrorTouchAction.DOWN, x, y), at)
        now = at + 50
        pipeline.onTouch(touchCommand(MirrorTouchAction.UP, x, y), at + 50)
    }

    private fun keyboardUp() {
        environment.shown = true
        environment.frame = keyboardFrame
        environment.field = FocusedField("lofi", "search_box", password = false)
    }

    // ── on-screen keyboard ───────────────────────────────────────────

    @Test
    fun keyboardTapsFoldIntoOneTextReadingAndNeverReachTheSession() {
        keyboardUp()
        tap(100, 1800, 1_000)
        tap(500, 1900, 1_200)
        tap(900, 2000, 1_400)
        assertTrue(sink.delivered.isEmpty(), "the burst is still open")

        now = 2_100 // 700 ms after the last key: the field is read once
        pipeline.tick(now)
        assertEquals(1, environment.fieldReads)
        assertTrue(sink.delivered.isEmpty())

        now = 3_500 // two seconds of silence ends the burst; the earlier reading is exact, no second read
        pipeline.tick(now)
        val typed = sink.delivered.single() as CollectingSink.Delivered.Typed
        assertEquals(KeyboardTextReading("lofi", "search_box", password = false), typed.reading)
        assertEquals(1_000L, typed.startMs)
        assertEquals(1_450L, typed.endMs)
        assertEquals(1, environment.fieldReads)
        assertTrue(sink.touches.isEmpty(), "no key position was passed on")
    }

    @Test
    fun aTapOutsideTheKeyboardEndsTheBurstFirstAndIsThenForwardedWithItsOwnTimes() {
        keyboardUp()
        tap(100, 1800, 1_000)
        tap(540, 500, 1_300)

        val kinds = sink.delivered.map { it::class.simpleName }
        assertEquals(listOf("Typed", "Input", "Input"), kinds)
        val forwarded = sink.touches
        assertEquals(listOf(MirrorTouchAction.DOWN, MirrorTouchAction.UP), forwarded.map { it.action })
        assertEquals(500, forwarded.first().y)
        assertEquals(1_300L, (sink.delivered[1] as CollectingSink.Delivered.Input).atMs)
        assertEquals(1_350L, (sink.delivered[2] as CollectingSink.Delivered.Input).atMs)
    }

    @Test
    fun aPasswordFieldIsReportedAsPasswordAndItsTextIsNeverPassedOn() {
        environment.shown = true
        environment.frame = keyboardFrame
        environment.field = FocusedField("", "pw", password = true)
        tap(100, 1800, 1_000)
        now = 1_700
        pipeline.tick(now)
        now = 3_200
        pipeline.tick(now)

        val typed = sink.delivered.single() as CollectingSink.Delivered.Typed
        assertTrue(typed.reading.password)
        assertNull(typed.reading.text)
        assertTrue(sink.touches.isEmpty())
    }

    @Test
    fun aHiddenKeyboardMeansEveryTapIsAnOrdinaryTap() {
        environment.shown = false
        tap(100, 2000, 1_000)
        assertEquals(2, sink.touches.size)
        assertEquals(2000, sink.touches.first().y)
    }

    @Test
    fun withoutAWindowFrameTheBottomFortyPercentStandInForTheKeyboard() {
        environment.shown = true
        environment.frame = null
        tap(100, 1500, 1_000) // 62 % down
        tap(100, 1000, 1_300) // 41 % down
        val forwarded = sink.touches
        assertEquals(listOf(1000, 1000), forwarded.map { it.y }, "only the tap above the fallback area is forwarded")
        assertIs<CollectingSink.Delivered.Typed>(sink.delivered.first())
    }

    @Test
    fun theKeyboardHidingEndsTheBurstBeforeTheIdleTime() {
        keyboardUp()
        tap(100, 1800, 1_000)
        environment.shown = false
        now = 2_700 // the cached state is stale now, the read says hidden; the burst has been quiet 1650 ms
        pipeline.tick(now)
        assertIs<CollectingSink.Delivered.Typed>(sink.delivered.single())
    }

    @Test
    fun aKeyboardStateThatCannotBeReadWarnsOnceAndFallsBackToOrdinaryTaps() {
        environment.shown = null
        tap(100, 1800, 1_000)
        tap(100, 1800, 3_000)
        assertEquals(4, sink.touches.size)
        assertEquals(1, sink.warnings.count { "keyboard state" in it })
    }

    @Test
    fun aSwipeStartingOnTheKeyboardIsPartOfTheBurstToo() {
        keyboardUp()
        now = 1_000
        pipeline.onTouch(touchCommand(MirrorTouchAction.DOWN, 100, 1800), 1_000)
        pipeline.onTouch(touchCommand(MirrorTouchAction.MOVE, 600, 1800), 1_100)
        pipeline.onTouch(touchCommand(MirrorTouchAction.UP, 900, 1800), 1_200)
        assertTrue(sink.touches.isEmpty())
        now = 4_000
        pipeline.tick(now)
        assertIs<CollectingSink.Delivered.Typed>(sink.delivered.single())
    }

    // ── hardware keys ────────────────────────────────────────────────

    private fun press(label: String, at: Long, down: Boolean = true) {
        now = at
        pipeline.onKey(requireNotNull(deviceKeyFor(label)), down, repeat = false, atMs = at)
    }

    @Test
    fun hardwareKeyboardTextIsOneRowAndAControlKeyEndsIt() {
        press("KEY_LEFTSHIFT", 1_000)
        press("KEY_L", 1_010)
        press("KEY_L", 1_020, down = false)
        press("KEY_LEFTSHIFT", 1_030, down = false)
        press("KEY_O", 1_100)
        press("KEY_F", 1_200)
        press("KEY_I", 1_300)
        press("KEY_ENTER", 1_400)

        val inputs = sink.delivered.filterIsInstance<CollectingSink.Delivered.Input>()
        assertEquals(MirrorControlCommand.Text("Lofi"), inputs[0].command)
        assertEquals(1_300L, inputs[0].atMs)
        assertEquals(MirrorControlCommand.Key(MirrorKeyAction.DOWN, KEYCODE_ENTER), inputs[1].command)
    }

    @Test
    fun idleTimeEndsTypedTextAndPowerAndVolumeKeysBecomeKeyCommands() {
        press("KEY_A", 1_000)
        now = 2_400
        pipeline.tick(now)
        assertTrue(sink.delivered.isEmpty(), "1.4 s of silence is not yet the end of a run")
        now = 2_600
        pipeline.tick(now)
        assertEquals(MirrorControlCommand.Text("a"), (sink.delivered.single() as CollectingSink.Delivered.Input).command)

        press("KEY_POWER", 5_000)
        press("KEY_VOLUMEUP", 5_100)
        val keys = sink.delivered.drop(1).map { (it as CollectingSink.Delivered.Input).command }
        assertEquals(
            listOf(
                MirrorControlCommand.Key(MirrorKeyAction.DOWN, KEYCODE_POWER),
                MirrorControlCommand.Key(MirrorKeyAction.DOWN, KEYCODE_VOLUME_UP),
            ),
            keys,
        )
    }

    @Test
    fun finishingDeliversTheTypedTextAndTheOpenBurst() {
        keyboardUp()
        press("KEY_A", 500)
        tap(100, 1800, 1_000)
        now = 1_200
        pipeline.finish()

        val kinds = sink.delivered.map { it::class.simpleName }
        assertEquals(listOf("Input", "Typed"), kinds)
        assertEquals(1, environment.fieldReads, "the field is read once for the burst")
    }

    @Test
    fun anIncompleteTouchIsDroppedAtFinish() {
        now = 1_000
        pipeline.onTouch(touchCommand(MirrorTouchAction.DOWN, 100, 500), 1_000)
        pipeline.finish()
        assertTrue(sink.delivered.isEmpty())
    }

    // ── key text accumulation ────────────────────────────────────────

    private fun typed(vararg steps: Triple<String, Boolean, Long>): List<KeyOutput> {
        val accumulator = KeyTextAccumulator()
        val out = ArrayList<KeyOutput>()
        steps.forEach { (label, down, at) -> out += accumulator.feed(requireNotNull(deviceKeyFor(label)), down, repeat = false, atMs = at) }
        accumulator.flush()?.let { out += KeyOutput.Text(it) }
        return out
    }

    private fun text(outputs: List<KeyOutput>) = outputs.filterIsInstance<KeyOutput.Text>().map { it.run.text }

    @Test
    fun shiftCapsLockAndSymbolsFollowTheKeyboardLayout() {
        val result = typed(
            Triple("KEY_LEFTSHIFT", true, 1L), Triple("KEY_H", true, 2L), Triple("KEY_H", false, 3L), Triple("KEY_LEFTSHIFT", false, 4L),
            Triple("KEY_I", true, 5L), Triple("KEY_COMMA", true, 6L), Triple("KEY_SPACE", true, 7L),
            Triple("KEY_LEFTSHIFT", true, 8L), Triple("KEY_1", true, 9L), Triple("KEY_LEFTSHIFT", false, 10L),
            Triple("KEY_CAPSLOCK", true, 11L), Triple("KEY_A", true, 12L), Triple("KEY_1", true, 13L),
        )
        assertEquals(listOf("Hi, !A1"), text(result))
    }

    @Test
    fun backspaceEditsTheRunAndBecomesDeleteWhenThereIsNothingToEdit() {
        val result = typed(
            Triple("KEY_BACKSPACE", true, 1L),
            Triple("KEY_A", true, 2L), Triple("KEY_B", true, 3L), Triple("KEY_BACKSPACE", true, 4L), Triple("KEY_C", true, 5L),
        )
        assertEquals(KeyOutput.Control(KEYCODE_DELETE, 1L), result.first())
        assertEquals(listOf("ac"), text(result))
    }

    @Test
    fun controlCombinationsAreNotTypedAndASilenceSplitsRuns() {
        val accumulator = KeyTextAccumulator(idleMs = 1_000)
        accumulator.feed(requireNotNull(deviceKeyFor("KEY_LEFTCTRL")), down = true, repeat = false, atMs = 1)
        accumulator.feed(requireNotNull(deviceKeyFor("KEY_C")), down = true, repeat = false, atMs = 2)
        accumulator.feed(requireNotNull(deviceKeyFor("KEY_LEFTCTRL")), down = false, repeat = false, atMs = 3)
        assertTrue(!accumulator.hasText)
        accumulator.feed(requireNotNull(deviceKeyFor("KEY_X")), down = true, repeat = false, atMs = 100)
        val split = accumulator.feed(requireNotNull(deviceKeyFor("KEY_Y")), down = true, repeat = false, atMs = 2_000)
        assertEquals(listOf("x"), split.filterIsInstance<KeyOutput.Text>().map { it.run.text })
        assertEquals("y", accumulator.flush()?.text)
    }

    @Test
    fun theLabelsOfSystemKeysMapToAndroidKeycodes() {
        assertEquals(DeviceKey.Control(KEYCODE_BACK), deviceKeyFor("KEY_BACK"))
        assertEquals(DeviceKey.Control(KEYCODE_HOME), deviceKeyFor("KEY_HOMEPAGE"))
        assertEquals(DeviceKey.Control(KEYCODE_APP_SWITCH), deviceKeyFor("KEY_APPSELECT"))
        assertEquals(DeviceKey.Control(KEYCODE_VOLUME_MUTE), deviceKeyFor("KEY_MUTE"))
        assertEquals(DeviceKey.Control(KEYCODE_MEDIA_PLAY_PAUSE), deviceKeyFor("KEY_PLAYPAUSE"))
        assertNull(deviceKeyFor("KEY_F13"))
        assertNull(deviceKeyFor("BTN_TOUCH"))
    }
}
