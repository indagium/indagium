package com.indagium.testing

import com.indagium.capture.CompletedFakeProcess
import com.indagium.debug.mapDisplayedScreenCoordinatesToDevice
import com.indagium.testing.device.DeviceInputAction
import com.indagium.testing.device.GESTURE_MAX_DURATION_MS
import com.indagium.testing.device.GESTURE_MAX_POINTS
import com.indagium.testing.device.GestureMode
import com.indagium.testing.device.GesturePoint
import com.indagium.testing.device.MOTION_DOWN_MARKER
import com.indagium.testing.device.TestDeviceSession
import com.indagium.testing.device.gestureCancelCommand
import com.indagium.testing.device.gestureHoldMs
import com.indagium.testing.device.gestureProblem
import com.indagium.testing.device.gestureShellScript
import com.indagium.testing.device.gestureSwipeFallback
import com.indagium.testing.device.gestureTimeoutSeconds
import com.indagium.testing.device.looksLikeMotionEventUnsupported
import com.indagium.testing.device.mayFallbackAfterMotionScript
import com.indagium.testing.device.parseSdkLevel
import com.indagium.testing.run.InputSequenceCallbacks
import com.indagium.testing.run.LaneStepBrief
import com.indagium.testing.run.LaneToolContext
import com.indagium.testing.run.LaneTools
import com.indagium.testing.run.RecordingLaneCallbacks
import com.indagium.testing.run.buildLaneTools
import com.indagium.testing.run.parseGesturePoints
import com.indagium.testing.script.ScriptRunContext
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SDK_PROPERTY = listOf("getprop", "ro.build.version.sdk")

/** The faithful gesture: the script that follows a path with its timing, the session that sends it, its fallback, and the lane tool. */
class GestureTest {
    private val runner = ScriptedAdbRunner()
    private val laneDir: File = createTempDirectory("indagium-gesture-test").toFile()
    private var session: TestDeviceSession? = null

    @AfterTest
    fun tearDown() {
        session?.closeBlocking()
        laneDir.deleteRecursively()
    }

    private fun open(): TestDeviceSession = runBlocking { openFixtureSession(runner, laneDir) }.also { session = it }

    private fun reportsApi(level: Int) {
        runner.shellResponder = { arguments -> if (arguments == SDK_PROPERTY) CompletedFakeProcess("$level\n") else null }
    }

    /** The swipe of the user's run: up from the bottom edge, then resting before the finger lifts (752 ms in all). */
    private val swipeAndHold = listOf(
        GesturePoint(640, 2350, 0),
        GesturePoint(640, 2300, 100),
        GesturePoint(630, 1900, 200),
        GesturePoint(620, 1350, 420),
        GesturePoint(620, 1350, 752),
    )

    // ── The script ──────────────────────────────────────────────────

    @Test
    fun theScriptPutsTheFingerDownMovesItThroughEveryPointAndLiftsItAtTheLastOne() {
        val script = gestureShellScript(swipeAndHold)
        val calls = script.split("; ").filter { it.startsWith("input ") }
        assertEquals(
            listOf(
                "input touchscreen motionevent DOWN 640 2350 && echo $MOTION_DOWN_MARKER || f=1",
                "input touchscreen motionevent MOVE 640 2300 || f=1",
                "input touchscreen motionevent MOVE 630 1900 || f=1",
                "input touchscreen motionevent MOVE 620 1350 || f=1",
                "input touchscreen motionevent UP 620 1350 || f=1",
            ),
            calls,
        )
        assertTrue(script.endsWith("exit \$f"), "a failing call is reported but never skips the lift: $script")
        assertFalse(script.contains('\n'), "one line, so adb passes it as one remote command")
    }

    @Test
    fun thePausesAreTheGapsBetweenPointsAndTheLastOneIsTheHoldBeforeTheLift() {
        val script = gestureShellScript(swipeAndHold)
        val pauses = script.split("; ").filter { it.startsWith("p ") }.map { it.removePrefix("p ").toLong() }
        assertEquals(listOf(100L, 100L, 220L, 332L), pauses)
        assertEquals(752L, pauses.sum(), "the pauses add up to the recorded duration")
        assertEquals(332L, pauses.last(), "the hold before release is the last pause")
        // The order in the script: DOWN, then pause/event pairs, UP last.
        val order = script.split("; ").filter { it.startsWith("input ") || it.startsWith("p ") }.map { it.substringBefore(" ").trim() }
        assertEquals(listOf("input", "p", "input", "p", "input", "p", "input", "p", "input"), order)
    }

    @Test
    fun theScriptTimesOneInputCallAndShortensEveryPauseByIt() {
        val script = gestureShellScript(swipeAndHold)
        // The cost of one call (centiseconds) is measured around DOWN and taken off every pause, so the rhythm survives slow `input`.
        assertTrue(script.indexOf("a=\$(c)") in 0 until script.indexOf("DOWN"))
        assertTrue(script.indexOf("DOWN") < script.indexOf("b=\$(c)"))
        assertTrue(script.contains("g=\$((\$1-d*10))"))
        assertTrue(script.contains("/proc/uptime"))
    }

    @Test
    fun aPressIsTwoPointsAtTheSamePlaceAndHoldsForItsWholeDuration() {
        val press = listOf(GesturePoint(100, 200, 0), GesturePoint(100, 200, 800))
        assertNull(gestureProblem(press))
        assertEquals(800L, gestureHoldMs(press))
        val script = gestureShellScript(press)
        assertTrue(script.contains("p 800; input touchscreen motionevent UP 100 200"), script)
    }

    @Test
    fun theHoldIsTheTimeTheFingerRestedWithinAFewPixelsOfItsLastPosition() {
        assertEquals(332L, gestureHoldMs(swipeAndHold))
        assertEquals(0L, gestureHoldMs(listOf(GesturePoint(0, 0, 0), GesturePoint(0, 100, 300))), "moving until the lift is no hold")
        assertEquals(
            500L,
            gestureHoldMs(listOf(GesturePoint(0, 0, 0), GesturePoint(50, 0, 100), GesturePoint(51, 1, 200), GesturePoint(52, 1, 600))),
            "within 3 px counts as resting",
        )
    }

    @Test
    fun aGestureMustBeInTimeOrderLongEnoughAndNotTooLongOrTooBig() {
        assertNull(gestureProblem(swipeAndHold))
        assertNotNull(gestureProblem(listOf(GesturePoint(1, 1, 0))), "one point is not a path")
        assertNotNull(gestureProblem(listOf(GesturePoint(1, 1, 5), GesturePoint(2, 2, 400))), "starts at 0")
        assertNotNull(gestureProblem(listOf(GesturePoint(1, 1, 0), GesturePoint(2, 2, 300), GesturePoint(3, 3, 200))), "time must not go back")
        assertNotNull(gestureProblem(listOf(GesturePoint(1, 1, 0), GesturePoint(2, 2, 10))), "shorter than 50 ms")
        assertNotNull(gestureProblem(listOf(GesturePoint(1, 1, 0), GesturePoint(2, 2, GESTURE_MAX_DURATION_MS + 1))))
        assertNotNull(gestureProblem((0..GESTURE_MAX_POINTS).map { GesturePoint(it, it, it * 20L) }), "more than the point limit")
    }

    @Test
    fun theAdbTimeLimitCoversTheGestureAndEveryInputCall() {
        // 752 ms of gesture + 1.5 s for each of the 5 input calls + 3 s of slack, rounded up to whole seconds.
        assertEquals(12L, gestureTimeoutSeconds(swipeAndHold))
    }

    @Test
    fun theFallbackIsAStraightSwipeFromTheFirstToTheLastPointOverTheWholeTime() {
        assertEquals(listOf("input", "swipe", "640", "2350", "620", "1350", "752"), gestureSwipeFallback(swipeAndHold, 50, 2_000))
        assertEquals("2000", gestureSwipeFallback(listOf(GesturePoint(0, 0, 0), GesturePoint(5, 5, 9_000)), 50, 2_000).last(), "clamped to the swipe range")
    }

    @Test
    fun theSdkLevelAndTheUnsupportedMarkersAreRecognised() {
        assertEquals(34, parseSdkLevel("34\n"))
        assertEquals(28, parseSdkLevel(" 28 \r\n"))
        assertNull(parseSdkLevel("not a number"))
        assertNull(parseSdkLevel(""))
        assertTrue(looksLikeMotionEventUnsupported("Error: Unknown command: motionevent\nUsage: input [<source>] <command> [<arg>...]"))
        assertFalse(looksLikeMotionEventUnsupported(""))
        assertFalse(
            looksLikeMotionEventUnsupported("Usage: input [<source>] <command>\n  motionevent <DOWN|UP|MOVE|CANCEL> <x> <y>"),
            "a usage text that lists motionevent is not a refusal of it",
        )
        assertEquals(listOf("input", "touchscreen", "motionevent", "UP", "620", "1350"), gestureCancelCommand(swipeAndHold.last()))
        assertTrue(mayFallbackAfterMotionScript("Error: Unknown command: motionevent"))
        assertFalse(mayFallbackAfterMotionScript("$MOTION_DOWN_MARKER\nError: Unknown command: motionevent"), "never repeat a partial gesture")
    }

    // ── The session ─────────────────────────────────────────────────

    @Test
    fun aGestureMapsScreenshotPixelsToDevicePixelsLikeTapAndSwipeDo() {
        val lane = open()
        reportsApi(34)
        val space = runBlocking { lane.screenshot() }.space
        val points = listOf(
            GesturePoint(0, 0, 0),
            GesturePoint(space.imageWidth / 2, space.imageHeight / 2, 200),
            GesturePoint(space.imageWidth - 1, space.imageHeight - 1, 500),
        )

        val result = runBlocking { lane.gesture(points) }

        assertEquals(GestureMode.MOTION_EVENTS, result.mode)
        assertNull(result.note)
        val (midX, midY) = mapDisplayedScreenCoordinatesToDevice(space.imageWidth / 2, space.imageHeight / 2, space)
        assertEquals(
            listOf(GesturePoint(0, 0, 0), GesturePoint(midX, midY, 200), GesturePoint(FIXTURE_DEVICE_WIDTH - 1, FIXTURE_DEVICE_HEIGHT - 1, 500)),
            result.devicePoints,
        )
        assertEquals(points, result.imagePoints)
        val sent = runner.shellCommands.last().single()
        assertTrue(sent.contains("input touchscreen motionevent DOWN 0 0"), sent)
        assertTrue(sent.contains("motionevent MOVE $midX $midY"), sent)
        assertTrue(sent.contains("motionevent UP ${FIXTURE_DEVICE_WIDTH - 1} ${FIXTURE_DEVICE_HEIGHT - 1}"), sent)
    }

    @Test
    fun aGestureOutsideTheScreenshotOrOutOfTimeOrderIsRefusedBeforeAnyAdbCall() {
        val lane = open()
        val space = runBlocking { lane.screenshot() }.space
        assertFailsWith<IllegalArgumentException> {
            runBlocking { lane.gesture(listOf(GesturePoint(0, 0, 0), GesturePoint(space.imageWidth, 5, 300))) }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking { lane.gesture(listOf(GesturePoint(0, 0, 0), GesturePoint(5, 5, 300), GesturePoint(6, 6, 100))) }
        }
        assertTrue(runner.shellCommands.isEmpty())
    }

    @Test
    fun aDeviceBeforeAndroid10GetsAStraightSwipeAndTheResultSaysSo() {
        val lane = open()
        reportsApi(28)
        val space = runBlocking { lane.screenshot() }.space
        val points = listOf(GesturePoint(10, 10, 0), GesturePoint(10, 50, 300), GesturePoint(10, 90, 800), GesturePoint(10, 90, 1_100))

        val result = runBlocking { lane.gesture(points) }

        assertEquals(GestureMode.SWIPE_FALLBACK, result.mode)
        val note = assertNotNull(result.note)
        assertTrue(note.contains("API 28") && note.contains("input motionevent") && note.contains("hold before release"), note)
        val (x1, y1) = mapDisplayedScreenCoordinatesToDevice(10, 10, space)
        val (x2, y2) = mapDisplayedScreenCoordinatesToDevice(10, 90, space)
        assertEquals(listOf("input", "swipe", "$x1", "$y1", "$x2", "$y2", "1100"), runner.shellCommands.last())
        assertEquals(28, lane.androidApiLevel)
    }

    @Test
    fun aDeviceThatAnswersWithTheUsageTextInsteadOfRunningMotionEventFallsBackAndRemembersIt() {
        val lane = open()
        runner.shellResponder = { arguments ->
            when {
                arguments == SDK_PROPERTY -> CompletedFakeProcess("34\n")
                arguments.singleOrNull()?.contains("motionevent") == true ->
                    CompletedFakeProcess("", "Error: Unknown command: motionevent\nUsage: input [<source>] <command> [<arg>...]\n")
                else -> null
            }
        }
        runBlocking { lane.screenshot() }
        val points = listOf(GesturePoint(10, 10, 0), GesturePoint(10, 90, 400))

        val first = runBlocking { lane.gesture(points) }
        val second = runBlocking { lane.gesture(points) }

        assertEquals(GestureMode.SWIPE_FALLBACK, first.mode)
        assertEquals(GestureMode.SWIPE_FALLBACK, second.mode)
        assertEquals(1, runner.shellCommands.count { it == SDK_PROPERTY }, "the API level is read once")
        assertEquals(1, runner.shellCommands.count { it.singleOrNull()?.contains("motionevent") == true }, "an unsupported device is not asked twice")
    }

    @Test
    fun theApiLevelIsReadOnceAndAnUnreadableAnswerStillTriesTheFaithfulPath() {
        val lane = open()
        runBlocking { lane.screenshot() }
        val points = listOf(GesturePoint(10, 10, 0), GesturePoint(10, 90, 400))
        assertEquals(GestureMode.MOTION_EVENTS, runBlocking { lane.gesture(points) }.mode)
        assertEquals(GestureMode.MOTION_EVENTS, runBlocking { lane.gesture(points) }.mode)
        assertEquals(1, runner.shellCommands.count { it == SDK_PROPERTY })
        assertNull(lane.androidApiLevel)
    }

    @Test
    fun aFailedGestureCommandIsReported() {
        val lane = open()
        reportsApi(34)
        runBlocking { lane.screenshot() }
        runner.shellResponder = { arguments ->
            if (arguments == SDK_PROPERTY) CompletedFakeProcess("34\n") else CompletedFakeProcess("", "boom", 1)
        }
        val failure = assertFailsWith<IllegalStateException> {
            runBlocking { lane.gesture(listOf(GesturePoint(10, 10, 0), GesturePoint(10, 90, 400))) }
        }
        assertTrue(failure.message.orEmpty().contains("boom"), failure.message)
    }

    // ── Replaying a list of inputs ──────────────────────────────────

    @Test
    fun aReplayRunsEveryActionInOrderKeepingTheRecordedPausesAndTakesNoScreenshotAfterwards() {
        val lane = open()
        reportsApi(34)
        runBlocking { lane.screenshot() }
        val shots = runner.specs.count { "screencap" in it.command }
        val seen = mutableListOf<Int>()

        val started = System.nanoTime()
        val results = runBlocking {
            lane.performReplay(
                listOf(
                    DeviceInputAction.Tap(10, 20, delayMs = 250),
                    DeviceInputAction.Gesture(listOf(GesturePoint(10, 20, 0), GesturePoint(10, 80, 300)), delayMs = 250),
                    DeviceInputAction.Key("BACK"),
                ),
            ) { index, _ -> seen += index }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(listOf("tap", "gesture", "press_key"), results.map { it.action })
        assertTrue(results.all { it.succeeded })
        assertEquals(listOf(1, 2, 3), seen)
        assertTrue(elapsedMs >= 500, "the two recorded pauses were kept ($elapsedMs ms)")
        assertEquals(shots, runner.specs.count { "screencap" in it.command }, "a replay does not capture the screen")
        val commands = runner.shellCommands.filterNot { it == SDK_PROPERTY }
        assertEquals("tap", commands[0][1])
        assertTrue(commands[1].single().contains("motionevent DOWN"))
        assertEquals(listOf("input", "keyevent", "KEYCODE_BACK"), commands[2])
    }

    @Test
    fun aReplayStopsAtTheFirstFailingActionAndValidatesEverythingFirst() {
        val lane = open()
        val space = runBlocking { lane.screenshot() }.space
        assertFailsWith<IllegalArgumentException> {
            runBlocking { lane.performReplay(listOf(DeviceInputAction.Tap(1, 1), DeviceInputAction.Tap(space.imageWidth, 1))) }
        }
        assertTrue(runner.shellCommands.isEmpty(), "nothing is injected when one action is off the screen")

        runner.shellExitCode = 1
        val results = runBlocking { lane.performReplay(listOf(DeviceInputAction.Key("HOME"), DeviceInputAction.Key("BACK"))) }
        assertEquals(1, results.size)
        assertFalse(results.single().succeeded)
    }

    @Test
    fun aPanelActionOpensTheShadeWithTheStatusBarCommand() {
        val lane = open()
        runBlocking { lane.screenshot() }
        val results = runBlocking {
            lane.performReplay(listOf(DeviceInputAction.Panel(com.indagium.testing.device.SystemPanel.QUICK_SETTINGS)))
        }
        assertTrue(results.single().succeeded)
        assertEquals(listOf("cmd", "statusbar", "expand-settings"), runner.shellCommands.last())
    }

    // ── The lane tool ───────────────────────────────────────────────

    private fun tools(allowed: Set<String>? = null): LaneTools {
        val lane = open()
        return buildLaneTools(
            LaneToolContext(
                session = lane,
                currentStep = { LaneStepBrief("step-1", "Case", 1, 1, "Swipe up and hold", "Recents opens") },
                stepLogOffset = { 0L },
                scripts = emptyList(),
                scriptContext = { ScriptRunContext(packageName = "com.example", runDir = laneDir, caseId = "case-1", stepId = "step-1") },
                callbacks = RecordingLaneCallbacks(),
                allowedTools = allowed,
                inputSequence = InputSequenceCallbacks(),
            ),
        )
    }

    private fun LaneTools.call(name: String, vararg args: Pair<String, Any?>): Map<*, *> =
        runBlocking { gateway.executeSuspending(name, mapOf(*args)) } as Map<*, *>

    private fun point(x: Int, y: Int, t: Int? = null): Map<String, Any?> = if (t == null) mapOf("x" to x, "y" to y) else mapOf("x" to x, "y" to y, "tMs" to t)

    @Test
    fun theGestureToolIsOfferedNextToSwipeAndSwipeIsUnchanged() {
        val lane = tools()
        val names = lane.gateway.tools.map { it.name }
        assertTrue("gesture" in names && "swipe" in names)
        lane.call("swipe", "x1" to 10, "y1" to 100, "x2" to 10, "y2" to 400, "durationMs" to 300)
        assertEquals("swipe", runner.shellCommands.last()[1], "swipe still runs input swipe")
        val description = lane.gateway.tools.single { it.name == "gesture" }.description
        assertTrue(description.contains("holdEndMs") && description.contains("tMs") && description.contains("hold"), description)
    }

    @Test
    fun aCaseThatDoesNotAllowGestureDoesNotGetTheTool() {
        val lane = tools(allowed = setOf("swipe", "tap", "take_screenshot"))
        assertFalse("gesture" in lane.gateway.tools.map { it.name })
        assertTrue("swipe" in lane.gateway.tools.map { it.name })
    }

    @Test
    fun theGestureToolFollowsTheGivenTimedPointsAndReportsHowItWentIn() {
        val lane = tools()
        reportsApi(34)
        val result = lane.call(
            "gesture",
            "points" to listOf(point(300, 800, 0), point(300, 600, 180), point(290, 400, 420)),
            "holdEndMs" to 330,
        )
        assertEquals(true, result["ok"])
        assertEquals("gesture", result["action"])
        assertEquals("motionevent", result["mode"])
        assertEquals(750L, result["durationMs"])
        assertEquals(330L, result["holdBeforeReleaseMs"])
        assertNull(result["fallback"])
        val sent = runner.shellCommands.last().single()
        assertEquals(4, sent.split("; ").count { it.startsWith("input ") }, "DOWN, two MOVEs and UP: $sent")
        assertTrue(sent.contains("p 330; input touchscreen motionevent UP"), sent)
    }

    @Test
    fun theGestureToolReportsAFallbackOnAnOldDevice() {
        val lane = tools()
        reportsApi(27)
        val result = lane.call("gesture", "points" to listOf(point(10, 10, 0), point(10, 90, 600)))
        assertEquals(true, result["ok"])
        assertEquals("swipe_fallback", result["mode"])
        assertEquals(true, result["fallback"])
        assertTrue(result["note"].toString().contains("approximated"), result["note"].toString())
    }

    @Test
    fun theGestureToolSpreadsPointsEvenlyWhenNoneHasATime() {
        val points = parseGesturePoints(mapOf("points" to listOf(point(0, 0), point(10, 10), point(20, 20)), "durationMs" to 600))
        assertEquals(listOf(0L, 300L, 600L), points.map { it.tMs })
        val defaulted = parseGesturePoints(mapOf("points" to listOf(point(0, 0), point(10, 10))))
        assertEquals(listOf(0L, 350L), defaulted.map { it.tMs })
    }

    @Test
    fun theGestureToolShiftsTimesToStartAtZeroAppendsTheHoldAndMakesASinglePointAPress() {
        val shifted = parseGesturePoints(mapOf("points" to listOf(point(0, 0, 1_000), point(10, 10, 1_400)), "holdEndMs" to 200))
        assertEquals(listOf(0L, 400L, 600L), shifted.map { it.tMs })
        assertEquals(listOf(10, 10), shifted.last().let { listOf(it.x, it.y) })
        assertEquals(shifted[1].x, shifted[2].x, "the hold stays on the last point")

        val press = parseGesturePoints(mapOf("points" to listOf(point(5, 6)), "durationMs" to 900))
        assertEquals(listOf(GesturePoint(5, 6, 0), GesturePoint(5, 6, 900)), press)
        val timedPress = parseGesturePoints(mapOf("points" to listOf(point(5, 6, 0)), "holdEndMs" to 700))
        assertEquals(listOf(GesturePoint(5, 6, 0), GesturePoint(5, 6, 700)), timedPress)
    }

    @Test
    fun theGestureToolRefusesMixedTimesBackwardsTimeAndOutOfRangeValues() {
        val lane = tools()

        fun error(vararg args: Pair<String, Any?>) = lane.call("gesture", *args)["error"].toString()
        assertTrue(error("points" to listOf(point(0, 0, 0), point(1, 1))).contains("every point or on none"))
        assertTrue(error("points" to listOf(point(0, 0, 0), point(1, 1, 500), point(2, 2, 100))).contains("time order"))
        assertTrue(error("points" to listOf(point(0, 0, 0), point(1, 1, 400)), "holdEndMs" to 99_999).contains("holdEndMs"))
        assertTrue(error("points" to emptyList<Any>()).contains("1 to"))
        assertTrue(error("points" to listOf(point(0, 0, 0), point(10_000, 1, 400))).contains("inside"), "off the screenshot")
        assertTrue(error("points" to listOf(point(0, 0, 0), point(1, 1, 10))).contains("between"))
        assertTrue(runner.shellCommands.isEmpty(), "nothing was injected")
    }
}
