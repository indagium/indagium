@file:Suppress("MagicNumber", "SpreadOperator") // Synthetic getevent dumps and fixture coordinates, not tunable constants.

package com.indagium.testing.authoring

import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.testing.device.parseDisplayRotation
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val EMULATOR_TOUCH = "/dev/input/event3"
private const val OLD_TOUCH = "/dev/input/event2"

/** What an emulator (virtio multitouch, power and volume keys, a keyboard) and the mirror's UHID devices print for `getevent -lp`. */
internal val EMULATOR_DUMP = """
add device 1: /dev/input/event3
  name:     "virtio_input_multi_touch_1"
  events:
    KEY (0001): BTN_TOUCH
    ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                ABS_MT_TOUCH_MAJOR    : value 0, min 0, max 255, fuzz 0, flat 0, resolution 0
                ABS_MT_POSITION_X     : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
                ABS_MT_POSITION_Y     : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
                ABS_MT_TRACKING_ID    : value 0, min 0, max 10, fuzz 0, flat 0, resolution 0
  input props:
    INPUT_PROP_DIRECT
add device 2: /dev/input/event2
  name:     "gpio-keys"
  events:
    KEY (0001): KEY_POWER             KEY_VOLUMEUP          KEY_VOLUMEDOWN
  input props:
    <none>
add device 3: /dev/input/event1
  name:     "virtio_input_keyboard"
  events:
    KEY (0001): KEY_ESC               KEY_1                 KEY_2                 KEY_A                 KEY_B
                KEY_ENTER             KEY_BACKSPACE         KEY_LEFTSHIFT         KEY_SPACE
  input props:
    <none>
add device 4: /dev/input/event5
  name:     "scrcpy"
  events:
    KEY (0001): KEY_ESC               KEY_A                 KEY_ENTER
  input props:
    <none>
add device 5: /dev/input/event6
  name:     "scrcpy"
  events:
    KEY (0001): BTN_LEFT              BTN_RIGHT
    REL (0002): REL_X                 REL_Y
  input props:
    <none>
add device 6: /dev/input/event4
  name:     "virtual touchpad"
  events:
    KEY (0001): BTN_TOUCH
    ABS (0003): ABS_X                 : value 0, min 0, max 1000, fuzz 0, flat 0, resolution 0
                ABS_Y                 : value 0, min 0, max 1000, fuzz 0, flat 0, resolution 0
  input props:
    INPUT_PROP_POINTER
""".trimIndent()

/** An older device: protocol A multitouch (no slots), and no `input props` section anywhere. */
private val PROTOCOL_A_DUMP = """
add device 1: /dev/input/event2
  name:     "synaptics_dsx"
  events:
    KEY (0001): BTN_TOUCH
    ABS (0003): ABS_MT_TOUCH_MAJOR    : value 0, min 0, max 15, fuzz 0, flat 0
                ABS_MT_POSITION_X     : value 0, min 0, max 1079, fuzz 0, flat 0
                ABS_MT_POSITION_Y     : value 0, min 0, max 1919, fuzz 0, flat 0
add device 2: /dev/input/event0
  name:     "uhid"
  events:
    KEY (0001): KEY_A
""".trimIndent()

private val SINGLE_TOUCH_DUMP = """
add device 1: /dev/input/event0
  name:     "resistive_ts"
  events:
    KEY (0001): BTN_TOUCH
    ABS (0003): ABS_X                 : value 0, min 0, max 799, fuzz 0, flat 0, resolution 0
                ABS_Y                 : value 0, min 0, max 479, fuzz 0, flat 0, resolution 0
  input props:
    INPUT_PROP_DIRECT
""".trimIndent()

internal fun line(micros: Long, path: String, type: String, code: String, value: String): String =
    "[%5d.%06d] %s: %-12s %-20s %s".format(micros / 1_000_000, micros % 1_000_000, path, type, code, value)

internal fun hex(value: Int) = "%08x".format(value)

private fun event(micros: Long, path: String, type: String, code: String, value: String) =
    requireNotNull(parseGeteventLine(line(micros, path, type, code, value))) { "fixture line must parse" }

class GeteventParserTest {
    @Test
    fun emulatorDumpYieldsTheTouchscreenKeyDevicesAndLeavesTheMirrorsVirtualDevicesOut() {
        val devices = GeteventDeviceParser.parse(EMULATOR_DUMP)

        val touch = devices.touchScreens.single()
        assertEquals("/dev/input/event3", touch.path)
        assertTrue(touch.slotted && touch.multiTouch)
        assertEquals(GeteventAxis(0, 32767), touch.x)
        assertEquals(GeteventAxis(0, 32767), touch.y)
        assertEquals(setOf("/dev/input/event2", "/dev/input/event1"), devices.keyDevices.map { it.path }.toSet())
        assertEquals(listOf("scrcpy", "scrcpy"), devices.excludedNames, "the UHID keyboard and mouse the mirror creates are excluded by name")
        assertTrue(devices.keyDevices.none { it.name == "scrcpy" })
        assertTrue(devices.touchScreens.none { it.path == "/dev/input/event4" }, "a pointer (touchpad) device is not a touchscreen")
    }

    @Test
    fun protocolAWithoutPropertiesIsRecognisedAndUhidIsExcluded() {
        val devices = GeteventDeviceParser.parse(PROTOCOL_A_DUMP)

        val touch = devices.touchScreens.single()
        assertEquals(OLD_TOUCH, touch.path)
        assertTrue(touch.multiTouch && !touch.slotted)
        assertEquals(1080, touch.x.span)
        assertEquals(listOf("uhid"), devices.excludedNames)
        assertTrue(devices.keyDevices.isEmpty())
    }

    @Test
    fun singleTouchScreenAndEmptyOrNoisyOutput() {
        val single = GeteventDeviceParser.parse(SINGLE_TOUCH_DUMP).touchScreens.single()
        assertTrue(!single.multiTouch && !single.slotted)
        assertEquals(GeteventAxis(0, 799), single.x)

        val noise = GeteventDeviceParser.parse("could not get driver version for /dev/input/mouse0, Not a typewriter\n")
        assertTrue(!noise.isUsable)
        assertTrue(!GeteventDeviceParser.parse("").isUsable)
    }

    @Test
    fun linesParseIncludingNegativeAxisValuesAndRejectEverythingElse() {
        val lift = event(12_345_678_901, EMULATOR_TOUCH, "EV_ABS", "ABS_MT_TRACKING_ID", "ffffffff")
        assertEquals(12_345_678_901L, lift.kernelMicros)
        assertEquals(-1, lift.axisValue())
        assertEquals("EV_ABS", lift.type)
        assertEquals(1000, event(1, EMULATOR_TOUCH, "EV_ABS", "ABS_MT_POSITION_X", "000003e8").axisValue())
        assertEquals("DOWN", event(1, EMULATOR_TOUCH, "EV_KEY", "BTN_TOUCH", "DOWN").value)
        assertNull(parseGeteventLine("add device 1: /dev/input/event3"))
        assertNull(parseGeteventLine("  name:     \"virtio_input_multi_touch_1\""))
        assertNull(parseGeteventLine(""))
        assertNull(parseGeteventLine("[ 1.5] garbage"))
    }

    // ── touch conversion ─────────────────────────────────────────────

    private fun emulatorParser(rotation: () -> Int = { 0 }): GeteventTouchParser {
        val devices = GeteventDeviceParser.parse(EMULATOR_DUMP)
        return GeteventTouchParser(devices.touchScreens, DisplayGeometry(1080, 2400), rotation)
    }

    private fun GeteventTouchParser.feed(vararg events: GeteventEvent): List<TimedTouch> = events.flatMap { accept(it) }

    private fun slotFrame(
        micros: Long,
        slot: Int?,
        trackingId: Int?,
        x: Int?,
        y: Int?,
    ): List<GeteventEvent> = buildList {
        slot?.let { add(event(micros, EMULATOR_TOUCH, "EV_ABS", "ABS_MT_SLOT", hex(it))) }
        trackingId?.let { add(event(micros, EMULATOR_TOUCH, "EV_ABS", "ABS_MT_TRACKING_ID", hex(it))) }
        x?.let { add(event(micros, EMULATOR_TOUCH, "EV_ABS", "ABS_MT_POSITION_X", hex(it))) }
        y?.let { add(event(micros, EMULATOR_TOUCH, "EV_ABS", "ABS_MT_POSITION_Y", hex(it))) }
        add(event(micros, EMULATOR_TOUCH, "EV_SYN", "SYN_REPORT", "00000000"))
    }

    @Test
    fun protocolBTapBecomesDownAndUpInLogicalPixels() {
        val parser = emulatorParser()
        val touches = parser.feed(
            *slotFrame(1_000_000, null, 7, 16384, 16384).toTypedArray(),
            *slotFrame(1_090_000, null, -1, null, null).toTypedArray(),
        )

        assertEquals(listOf(MirrorTouchAction.DOWN, MirrorTouchAction.UP), touches.map { it.command.action })
        val down = touches.first().command
        assertEquals(540, down.x)
        assertEquals(1200, down.y)
        assertEquals(1080, down.screenWidth)
        assertEquals(2400, down.screenHeight)
        assertEquals(1_000_000L, touches.first().kernelMicros)
        val up = touches.last().command
        assertEquals(540 to 1200, up.x to up.y, "the lift is reported where the finger was")
        assertEquals(down.pointerId, up.pointerId)
    }

    @Test
    fun protocolBRepeatedTapReusesTheSlotCoordinates() {
        val parser = emulatorParser()
        val touches = parser.feed(
            *slotFrame(1, null, 7, 16384, 16384).toTypedArray(),
            *slotFrame(2, null, -1, null, null).toTypedArray(),
            *slotFrame(3, null, 8, null, null).toTypedArray(),
            *slotFrame(4, null, -1, null, null).toTypedArray(),
        )

        assertEquals(
            listOf(MirrorTouchAction.DOWN, MirrorTouchAction.UP, MirrorTouchAction.DOWN, MirrorTouchAction.UP),
            touches.map { it.command.action },
        )
        assertEquals(
            listOf(540 to 1200, 540 to 1200, 540 to 1200, 540 to 1200),
            touches.map { it.command.x to it.command.y },
        )
        assertEquals(listOf(0L, 0L, 0L, 0L), touches.map { it.command.pointerId })
    }

    @Test
    fun protocolBTapsWithOnlyOneChangedAxisReuseTheOtherAxis() {
        val parser = emulatorParser()
        val touches = parser.feed(
            *slotFrame(1, null, 1, 1000, 2000).toTypedArray(),
            *slotFrame(2, null, -1, null, null).toTypedArray(),
            *slotFrame(3, null, 2, 3000, null).toTypedArray(),
            *slotFrame(4, null, -1, null, null).toTypedArray(),
            *slotFrame(5, null, 3, null, 5000).toTypedArray(),
            *slotFrame(6, null, -1, null, null).toTypedArray(),
        )

        assertEquals(
            listOf(
                MirrorTouchAction.DOWN, MirrorTouchAction.UP,
                MirrorTouchAction.DOWN, MirrorTouchAction.UP,
                MirrorTouchAction.DOWN, MirrorTouchAction.UP,
            ),
            touches.map { it.command.action },
        )
        assertEquals(
            listOf(32 to 146, 32 to 146, 98 to 146, 98 to 146, 98 to 366, 98 to 366),
            touches.map { it.command.x to it.command.y },
        )
    }

    @Test
    fun protocolBSlotCoordinatesStayIndependentWhenContactsAlternate() {
        val parser = emulatorParser()
        val touches = parser.feed(
            *slotFrame(1, 0, 1, 1000, 2000).toTypedArray(),
            *slotFrame(2, 1, 2, 10000, 20000).toTypedArray(),
            *slotFrame(3, 0, -1, null, null).toTypedArray(),
            *slotFrame(4, 1, -1, null, null).toTypedArray(),
            *slotFrame(5, 1, 3, 11000, null).toTypedArray(),
            *slotFrame(6, 1, -1, null, null).toTypedArray(),
            *slotFrame(7, 0, 4, null, 3000).toTypedArray(),
            *slotFrame(8, 0, -1, null, null).toTypedArray(),
        )

        assertEquals(
            listOf(
                MirrorTouchAction.DOWN, MirrorTouchAction.DOWN, MirrorTouchAction.UP, MirrorTouchAction.UP,
                MirrorTouchAction.DOWN, MirrorTouchAction.UP, MirrorTouchAction.DOWN, MirrorTouchAction.UP,
            ),
            touches.map { it.command.action },
        )
        assertEquals(
            listOf(0L, 1L, 0L, 1L, 1L, 1L, 0L, 0L),
            touches.map { it.command.pointerId },
        )
        assertEquals(362 to 1464, touches[4].command.x to touches[4].command.y, "slot 1 keeps its own Y")
        assertEquals(32 to 219, touches[6].command.x to touches[6].command.y, "slot 0 keeps its own X")
    }

    @Test
    fun protocolBFirstContactWaitsUntilBothCoordinatesHaveBeenSeen() {
        val parser = emulatorParser()
        assertTrue(parser.feed(*slotFrame(1, null, 1, 1200, null).toTypedArray()).isEmpty())

        val touches = parser.feed(
            *slotFrame(2, null, null, null, 2000).toTypedArray(),
            *slotFrame(3, null, -1, null, null).toTypedArray(),
        )

        assertEquals(listOf(MirrorTouchAction.DOWN, MirrorTouchAction.UP), touches.map { it.command.action })
        assertEquals(listOf(39 to 146, 39 to 146), touches.map { it.command.x to it.command.y })
    }

    @Test
    fun aRecreatedParserStartsWithUnknownSlotCoordinates() {
        val devices = GeteventDeviceParser.parse(EMULATOR_DUMP).touchScreens
        val geometry = DisplayGeometry(1080, 2400)
        val previousParser = GeteventTouchParser(devices, geometry) { 0 }
        previousParser.feed(
            *slotFrame(1, null, 1, 16384, 16384).toTypedArray(),
            *slotFrame(2, null, -1, null, null).toTypedArray(),
        )

        val parser = GeteventTouchParser(devices, geometry) { 0 }
        assertTrue(parser.feed(*slotFrame(3, null, 2, 1000, null).toTypedArray()).isEmpty())

        val touches = parser.feed(
            *slotFrame(4, null, null, null, 2000).toTypedArray(),
            *slotFrame(5, null, -1, null, null).toTypedArray(),
        )
        assertEquals(listOf(MirrorTouchAction.DOWN, MirrorTouchAction.UP), touches.map { it.command.action })
        assertEquals(listOf(32 to 146, 32 to 146), touches.map { it.command.x to it.command.y })
    }

    @Test
    fun protocolBSwipeMovesAndTwoFingersGetOwnPointers() {
        val parser = emulatorParser()
        val touches = parser.feed(
            *slotFrame(1, 0, 1, 1000, 2000).toTypedArray(),
            *slotFrame(2, null, null, 1000, 3000).toTypedArray(),
            *slotFrame(3, 1, 2, 30000, 30000).toTypedArray(),
            *slotFrame(4, 0, -1, null, null).toTypedArray(),
            *slotFrame(5, 1, -1, null, null).toTypedArray(),
        )

        assertEquals(
            listOf(
                MirrorTouchAction.DOWN, MirrorTouchAction.MOVE, MirrorTouchAction.DOWN, MirrorTouchAction.UP, MirrorTouchAction.UP,
            ),
            touches.map { it.command.action },
        )
        assertEquals(2, touches.map { it.command.pointerId }.toSet().size)
        assertTrue(touches[1].command.y > touches[0].command.y)
    }

    @Test
    fun aRotatedDisplayTurnsTheNaturalPosition() {
        // (540, 1200) in a 1080x2400 natural display.
        val expected = mapOf(
            0 to Triple(540, 1200, 1080 to 2400),
            1 to Triple(1200, 539, 2400 to 1080),
            2 to Triple(539, 1199, 1080 to 2400),
            3 to Triple(1199, 540, 2400 to 1080),
        )
        for ((turns, want) in expected) {
            val parser = emulatorParser { turns }
            val down = parser.feed(*slotFrame(1, null, 3, 16384, 16384).toTypedArray()).single().command
            assertEquals(want.first to want.second, down.x to down.y, "rotation $turns")
            assertEquals(want.third, down.screenWidth to down.screenHeight, "rotation $turns")
        }
    }

    @Test
    fun theRotationIsReadAtTheMomentOfEachTouch() {
        var turns = 0
        val parser = emulatorParser { turns }
        val portrait = parser.feed(*slotFrame(1, null, 3, 16384, 16384).toTypedArray()).single().command
        parser.feed(*slotFrame(2, null, -1, null, null).toTypedArray())
        turns = 1
        val landscape = parser.feed(*slotFrame(3, null, 4, 16384, 16384).toTypedArray()).single().command
        assertEquals(1080, portrait.screenWidth)
        assertEquals(2400, landscape.screenWidth)
    }

    @Test
    fun eventsFromOtherDevicesAreIgnored() {
        val parser = emulatorParser()
        val touches = parser.feed(
            event(1, "/dev/input/event2", "EV_ABS", "ABS_MT_POSITION_X", hex(5)),
            event(1, "/dev/input/event2", "EV_SYN", "SYN_REPORT", "00000000"),
        )
        assertTrue(touches.isEmpty())
    }

    @Test
    fun protocolAFramesGiveDownMoveAndUp() {
        val touch = GeteventDeviceParser.parse(PROTOCOL_A_DUMP).touchScreens
        val parser = GeteventTouchParser(touch, DisplayGeometry(1080, 1920)) { 0 }

        fun contact(micros: Long, x: Int, y: Int) = listOf(
            event(micros, OLD_TOUCH, "EV_ABS", "ABS_MT_POSITION_X", hex(x)),
            event(micros, OLD_TOUCH, "EV_ABS", "ABS_MT_POSITION_Y", hex(y)),
            event(micros, OLD_TOUCH, "EV_SYN", "SYN_MT_REPORT", "00000000"),
        )

        fun report(micros: Long) = event(micros, OLD_TOUCH, "EV_SYN", "SYN_REPORT", "00000000")

        val touches = parser.feed(
            *contact(1, 100, 200).toTypedArray(), report(1),
            *contact(2, 100, 400).toTypedArray(), report(2),
            *contact(3, 100, 400).toTypedArray(), report(3),
            report(4),
        )

        assertEquals(
            listOf(MirrorTouchAction.DOWN, MirrorTouchAction.MOVE, MirrorTouchAction.UP),
            touches.map { it.command.action },
            "an unchanged frame is no move, an empty frame lifts the contact",
        )
        assertEquals(100 to 200, touches.first().command.x to touches.first().command.y)
        assertEquals(400, touches.last().command.y, "the lift happens at the last position")
    }

    @Test
    fun singleTouchUsesBtnTouchAndSynReport() {
        val device = GeteventDeviceParser.parse(SINGLE_TOUCH_DUMP).touchScreens
        val path = "/dev/input/event0"
        val parser = GeteventTouchParser(device, DisplayGeometry(800, 480)) { 0 }
        val touches = parser.feed(
            event(1, path, "EV_ABS", "ABS_X", hex(400)),
            event(1, path, "EV_ABS", "ABS_Y", hex(240)),
            event(1, path, "EV_KEY", "BTN_TOUCH", "DOWN"),
            event(1, path, "EV_SYN", "SYN_REPORT", "00000000"),
            event(2, path, "EV_ABS", "ABS_X", hex(420)),
            event(2, path, "EV_SYN", "SYN_REPORT", "00000000"),
            event(3, path, "EV_KEY", "BTN_TOUCH", "UP"),
            event(3, path, "EV_SYN", "SYN_REPORT", "00000000"),
            event(4, path, "EV_KEY", "BTN_TOUCH", "DOWN"),
            event(4, path, "EV_SYN", "SYN_REPORT", "00000000"),
            event(5, path, "EV_KEY", "BTN_TOUCH", "UP"),
            event(5, path, "EV_SYN", "SYN_REPORT", "00000000"),
        )
        assertEquals(
            listOf(MirrorTouchAction.DOWN, MirrorTouchAction.MOVE, MirrorTouchAction.UP, MirrorTouchAction.DOWN, MirrorTouchAction.UP),
            touches.map { it.command.action },
        )
        assertEquals(400 to 240, touches.first().command.x to touches.first().command.y)
        assertEquals(420 to 240, touches[3].command.x to touches[3].command.y, "single-touch axes also persist for the next tap")
    }

    // ── bounded line reader and clock ────────────────────────────────

    @Test
    fun overlongLinesAreSkippedWithoutBreakingTheLinesAroundThem() {
        val text = "first\n" + "x".repeat(5_000) + "\nthird\nlast-without-newline"
        val lines = ArrayList<String>()
        forEachBoundedLine(ByteArrayInputStream(text.toByteArray()), 64) {
            lines += it
            true
        }
        assertEquals(listOf("first", "third", "last-without-newline"), lines)
    }

    @Test
    fun lineReaderStopsWhenToldTo() {
        val lines = ArrayList<String>()
        forEachBoundedLine(ByteArrayInputStream("a\nb\nc\n".toByteArray()), 64) {
            lines += it
            lines.size < 2
        }
        assertEquals(listOf("a", "b"), lines)
    }

    @Test
    fun kernelClockKeepsTheSmallestOffsetAndNeverRunsBackwards() {
        var now = 10_000L
        val clock = KernelClock { now }
        assertEquals(10_000L, clock.wallMs(1_000_000))
        now = 10_700L // a late-delivered line: 700 ms after its kernel time
        assertEquals(10_100L, clock.wallMs(1_100_000), "the offset of the quickest delivery is kept")
        now = 10_150L
        assertEquals(10_150L, clock.wallMs(1_150_000))
        now = 10_190L // a later, slower delivery cannot move the clock back
        assertTrue(clock.wallMs(1_160_000) >= 10_150L)
    }

    // ── device facts ─────────────────────────────────────────────────

    @Test
    fun displaySizeRotationAndKeyboardFactsParseFromDumps() {
        assertEquals(DisplayGeometry(1080, 2400), parseWmSize("Physical size: 1080x2400\n"))
        assertEquals(DisplayGeometry(720, 1600), parseWmSize("Physical size: 1080x2400\nOverride size: 720x1600\n"))
        assertNull(parseWmSize("Error: no display"))

        assertEquals(1, parseDisplayRotation("  mCurrentRotation=ROTATION_90 mLastOrientation=0"))
        assertEquals(3, parseDisplayRotation("    mRotation=3 mUserRotation=USER_ROTATION_LOCKED"))
        assertEquals(0, parseDisplayRotation("  mCurrentRotation=ROTATION_0"))
        assertNull(parseDisplayRotation("nothing to see"))

        assertEquals(true, parseImeShown("  mInputShown=true\n  mIsInputViewShown=false"))
        assertEquals(false, parseImeShown("  mInputShown=false"))
        assertNull(parseImeShown("no flags"))
    }

    @Test
    fun theKeyboardWindowFrameIsReadFromItsOwnWindowOnly() {
        val dump = """
            Window #3 Window{1a2b u0 StatusBar}:
              mFrame=[0,0][1080,100] last=[0,0][1080,100]
            Window #4 Window{3c4d u0 InputMethod}:
              mDisplayId=0 stackId=1
              Frames: parent=[0,0][1080,2400] display=[0,0][1080,2400] frame=[0,1500][1080,2400] last=[0,1500][1080,2400]
            Window #5 Window{5e6f u0 com.example/com.example.Main}:
              mFrame=[0,0][1080,2400]
        """.trimIndent()
        assertEquals(ScreenRect(0, 1500, 1080, 2400), parseImeWindowFrame(dump))
        val newer = "Window #7 Window{ab12 u0 InputMethod}:\n    mFrame=Rect(0, 1480 - 1080, 2400)\n"
        assertEquals(ScreenRect(0, 1480, 1080, 2400), parseImeWindowFrame(newer))
        assertNull(parseImeWindowFrame("Window #4 Window{3c4d u0 com.example/.Main}:\n  mFrame=[0,0][1080,2400]\n"))
    }

    @Test
    fun typedTextDeltaSubtractsWhatTheFieldAlreadyHeld() {
        assertEquals("lofi", typedTextDelta(null, "lofi"))
        assertEquals("lofi", typedTextDelta("", "lofi"))
        assertEquals("fi", typedTextDelta("lo", "lofi"))
        assertEquals("other", typedTextDelta("Search", "other"), "a baseline that is not a prefix is a hint, the whole text was typed")
        assertNull(typedTextDelta("lofi", "lofi"))
        assertNull(typedTextDelta("lofi", ""))
    }

    @Test
    fun theFocusedFieldIsTheFocusedEditControlAndAPasswordNeverCarriesText() {
        val dump = """
            <hierarchy rotation="1">
              <node text="" resource-id="" class="android.widget.FrameLayout" bounds="[0,0][2400,1080]" />
              <node text="Sign in" class="android.widget.Button" clickable="true" focused="true" bounds="[0,0][100,100]" />
              <node text="lofi" resource-id="com.example:id/search_box" class="android.widget.EditText" clickable="true" focused="true"
                bounds="[0,100][2400,200]" />
            </hierarchy>
        """.trimIndent()
        val tree = assertNotNull(com.indagium.testing.device.parseUiAutomatorDump(dump))
        assertEquals(1, tree.rotation)
        assertEquals(FocusedField("lofi", "search_box", password = false), focusedFieldOf(tree.nodes))

        val secret = """
            <hierarchy rotation="0">
              <node text="hunter2" resource-id="com.example:id/pw" class="android.widget.EditText" password="true" focused="true"
                clickable="true" bounds="[0,100][1080,200]" />
            </hierarchy>
        """.trimIndent()
        val field = assertNotNull(focusedFieldOf(assertNotNull(com.indagium.testing.device.parseUiAutomatorDump(secret)).nodes))
        assertEquals(FocusedField("", "pw", password = true), field)
    }

    @Test
    fun pointerIdsDoNotCollideBetweenDevices() {
        val devices = GeteventDeviceParser.parse(EMULATOR_DUMP).touchScreens + GeteventDeviceParser.parse(SINGLE_TOUCH_DUMP).touchScreens
        val parser = GeteventTouchParser(devices, DisplayGeometry(1080, 2400)) { 0 }
        val a = parser.feed(*slotFrame(1, null, 3, 100, 100).toTypedArray()).single().command
        val path = "/dev/input/event0"
        val b = parser.feed(
            event(2, path, "EV_ABS", "ABS_X", hex(10)),
            event(2, path, "EV_ABS", "ABS_Y", hex(10)),
            event(2, path, "EV_KEY", "BTN_TOUCH", "DOWN"),
            event(2, path, "EV_SYN", "SYN_REPORT", "00000000"),
        ).single().command
        assertTrue(a.pointerId != b.pointerId)
        assertTrue(a is MirrorControlCommand.Touch && b is MirrorControlCommand.Touch)
    }
}
