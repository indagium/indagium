@file:Suppress("MagicNumber") // Fixture coordinates, timestamps and sizes, not tunable constants.

package com.indagium.testing

import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorKeyAction
import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.debug.DeviceScreenCoordinateSpace
import com.indagium.testing.authoring.RecordedInputKind
import com.indagium.testing.authoring.RecordedPoint
import com.indagium.testing.authoring.RecordedTestStep
import com.indagium.testing.authoring.ReferenceTiming
import com.indagium.testing.authoring.RewriteDescription
import com.indagium.testing.authoring.RewriteSensitivity
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.authoring.TimedPercentPoint
import com.indagium.testing.authoring.buildRecordedInput
import com.indagium.testing.authoring.describe
import com.indagium.testing.authoring.formatHold
import com.indagium.testing.authoring.formatTimedPath
import com.indagium.testing.authoring.parseReferenceGestures
import com.indagium.testing.authoring.restBeforeLiftMs
import com.indagium.testing.authoring.rewriteInputsOf
import com.indagium.testing.device.DeviceInputAction
import com.indagium.testing.model.RecordedInputEvent
import com.indagium.testing.model.RecordedInput
import com.indagium.testing.model.TimedGesturePoint
import com.indagium.testing.model.RecordedTouchKind
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.holdBeforeReleaseMs
import com.indagium.testing.run.ReplayPlan
import com.indagium.testing.run.planReplay
import com.indagium.testing.run.recordedGesturesOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a recording keeps so a gesture can be reproduced: the time of every sample, the rest before the finger lifts, the structured
 * input of a step, and the reference-log text with the timing (and the old text without it).
 */
class ReplayRecordingTest {
    @Test
    fun replayDoesNotReduceTheUsersShortRecordedSwipeToAGuessedCoarsePath() {
        val times = listOf(0L, 6L, 13L, 59L, 97L, 143L, 188L, 208L)
        val points = times.mapIndexed { index, time -> TimedGesturePoint(0.61 + index * 0.005, 0.35 - index * 0.02, time) }
        val input = RecordedInput(listOf(RecordedInputEvent.Touch(RecordedTouchKind.SWIPE, points, 208, 1234, 1280)))
        val plan = planReplay(input, DeviceScreenCoordinateSpace(1038, 1076, 2076, 2152)) as ReplayPlan.Ready
        val gesture = plan.actions.single() as DeviceInputAction.Gesture
        assertEquals(times, gesture.points.map { it.tMs })
        assertEquals(points.size, gesture.points.size)
    }

    private fun touch(action: MirrorTouchAction, x: Int, y: Int, pointer: Long = 1) = MirrorControlCommand.Touch(
        action = action, pointerId = pointer, x = x, y = y, screenWidth = 100, screenHeight = 200,
    )

    /** The user's swipe: up from the bottom edge, arriving at (57%, 56%) after 420 ms and resting there until the lift at 752 ms. */
    private fun swipeUpAndHold(session: TestStepRecordingSession, base: Long = 1_000L) {
        session.accept(touch(MirrorTouchAction.DOWN, 59, 196), nowMs = base)
        session.accept(touch(MirrorTouchAction.MOVE, 59, 190), nowMs = base + 100)
        session.accept(touch(MirrorTouchAction.MOVE, 58, 170), nowMs = base + 200)
        session.accept(touch(MirrorTouchAction.MOVE, 57, 140), nowMs = base + 300)
        session.accept(touch(MirrorTouchAction.MOVE, 57, 112), nowMs = base + 420)
        session.accept(touch(MirrorTouchAction.UP, 57, 112), nowMs = base + 752)
    }

    private fun stopped(block: (TestStepRecordingSession) -> Unit): TestStepRecordingSession = runBlocking {
        TestStepRecordingSession("fixture-device").also { session ->
            block(session)
            session.stopAndDrain()
        }
    }

    // ── Timing on the recorded samples ──────────────────────────────

    @Test
    fun everySampleOfASwipeKeepsTheTimeItWasReachedAndTheLastMoveIsAlwaysKept() {
        val session = stopped { swipeUpAndHold(it) }
        val row = session.snapshot.value.steps.single()

        assertEquals(RecordedInputKind.SWIPE, row.kind)
        assertEquals(752L, row.durationMs)
        assertEquals(listOf(0L, 100L, 200L, 300L, 420L, 752L), row.gesturePath.map { it.tMs })
        assertEquals(RecordedPoint(57, 112, 100, 200, 752), row.gesturePath.last())
        session.close()
    }

    @Test
    fun aLongSwipeThinnedToItsSampleLimitStillEndsWithTheMoveWhereTheFingerCameToRest() {
        val session = stopped {
            it.accept(touch(MirrorTouchAction.DOWN, 50, 190), nowMs = 0)
            repeat(40) { index -> it.accept(touch(MirrorTouchAction.MOVE, 50, 190 - index * 2), nowMs = (index + 1) * 10L) }
            it.accept(touch(MirrorTouchAction.UP, 50, 112), nowMs = 900)
        }
        val path = session.snapshot.value.steps.single().gesturePath

        assertTrue(path.size <= 10, "still within the sample limit: ${path.size}")
        assertEquals(400L, path[path.size - 2].tMs, "the last MOVE (at 400 ms) is kept even though thinning skipped it")
        assertEquals(900L, path.last().tMs)
        assertEquals(0L, path.first().tMs)
        assertTrue(path.zipWithNext().all { (a, b) -> a.tMs <= b.tMs }, "times never go back")
        session.close()
    }

    @Test
    fun aTapIsOneSamplePressedAtZero() {
        val session = stopped {
            it.accept(touch(MirrorTouchAction.DOWN, 18, 32), nowMs = 5_000)
            it.accept(touch(MirrorTouchAction.UP, 18, 32), nowMs = 5_090)
        }
        val row = session.snapshot.value.steps.single()
        assertEquals(listOf(RecordedPoint(18, 32, 100, 200, 0)), row.gesturePath)
        assertEquals(90L, row.durationMs)
        session.close()
    }

    // ── The structured input of a step ──────────────────────────────

    @Test
    fun aStepKeepsTheStructuredInputWithTheHoldTheGapsAndTheRecordedScreen() {
        val session = stopped {
            it.accept(touch(MirrorTouchAction.DOWN, 18, 32), nowMs = 1_000)
            it.accept(touch(MirrorTouchAction.UP, 18, 32), nowMs = 1_080)
            swipeUpAndHold(it, base = 2_000)
            it.accept(MirrorControlCommand.Key(MirrorKeyAction.DOWN, 4), nowMs = 4_500)
            it.accept(touch(MirrorTouchAction.DOWN, 10, 10), nowMs = 9_000)
            it.accept(touch(MirrorTouchAction.UP, 10, 10), nowMs = 9_050)
        }

        val steps = session.toTestSteps()

        assertEquals(4, steps.size)
        val tap = assertNotNull(steps[0].recordedInput).events.single() as RecordedInputEvent.Touch
        assertEquals(RecordedTouchKind.TAP, tap.kind)
        assertEquals(18.0 / 99.0, tap.points.single().x, 1e-9)
        assertEquals(32.0 / 199.0, tap.points.single().y, 1e-9)
        assertEquals(100, tap.sourceScreenWidth)
        assertEquals(200, tap.sourceScreenHeight)
        assertEquals(920L, steps[0].recordedInput?.settleMs, "from the lift of the tap (1080) to the next press (2000)")

        val swipeInput = assertNotNull(steps[1].recordedInput)
        val swipe = swipeInput.events.single() as RecordedInputEvent.Touch
        assertEquals(RecordedTouchKind.SWIPE, swipe.kind)
        assertEquals(752L, swipe.durationMs)
        assertEquals(listOf(0L, 100L, 200L, 300L, 420L, 752L), swipe.points.map { it.tMs })
        assertEquals(332L, swipe.holdBeforeReleaseMs(), "it rested 332 ms before the finger lifted")
        assertEquals(196.0 / 199.0, swipe.points.first().y, 1e-9)
        assertEquals(112.0 / 199.0, swipe.points.last().y, 1e-9)
        assertEquals(1_748L, swipeInput.settleMs, "from the lift at 2752 to the Back key at 4500")
        assertEquals(RecordedInputEvent.Key("BACK"), steps[2].recordedInput?.events?.single())
        assertEquals(StepKind.AGENT, steps[1].kind, "an AI step by default; Replay is a choice")
        session.close()
    }

    @Test
    fun aRewrittenStepThatMergesSeveralInputsKeepsAllOfThemInRecordedOrderWithTheirGaps() {
        val tap = RecordedTestStep(
            "Tap", kind = RecordedInputKind.TAP, durationMs = 80, inputStartMs = 1_000, inputAtMs = 1_080,
            gesturePath = listOf(RecordedPoint(50, 100, 100, 200, 0)), id = "a",
        )
        val back = RecordedTestStep("Back", kind = RecordedInputKind.BACK, keyName = "BACK", inputStartMs = 1_600, inputAtMs = 1_600, id = "b")
        val shade = RecordedTestStep("Shade", kind = RecordedInputKind.NOTIFICATIONS, inputStartMs = 2_400, inputAtMs = 2_400, id = "c")
        val later = RecordedTestStep(
            "Later", kind = RecordedInputKind.TAP, durationMs = 50, inputStartMs = 6_000, inputAtMs = 6_050,
            gesturePath = listOf(RecordedPoint(10, 10, 100, 200, 0)), id = "d",
        )
        val inputs = rewriteInputsOf(listOf(tap, back, shade, later)) { null }

        val recorded = assertNotNull(buildRecordedInput(inputs, setOf("a", "b", "c"), RewriteSensitivity(inputs)))

        assertEquals(3, recorded.events.size)
        assertEquals(listOf(0L, 520L, 800L), recorded.events.map { it.gapBeforeMs })
        assertEquals(RecordedInputEvent.Panel("notifications", 800L), recorded.events[2])
        assertEquals(3_600L, recorded.settleMs, "the time until the next recorded input")
    }

    private fun tapRow(id: String, start: Long) = RecordedTestStep(
        "Tap", kind = RecordedInputKind.TAP, durationMs = 80, inputStartMs = start, inputAtMs = start + 80,
        gesturePath = listOf(RecordedPoint(50, 100, 100, 200, 0)), id = id,
    )

    @Test
    fun aStepWithTypedTextRotationAnUnnamedKeyOrAuthorTextHasNoReplayableInput() {
        val text = RecordedTestStep("Enter text: hello", kind = RecordedInputKind.TEXT, inputStartMs = 2_000, inputAtMs = 2_000, id = "t")
        val rotate = RecordedTestStep("Rotate the device", kind = RecordedInputKind.ROTATE, inputStartMs = 3_000, inputAtMs = 3_000, id = "r")
        val author = RecordedTestStep("Do it", authorAdded = true, id = "x")
        val unnamedKey = RecordedTestStep("Press Android key 999", kind = RecordedInputKind.KEY, inputStartMs = 5_000, inputAtMs = 5_000, id = "k")
        val inputs = rewriteInputsOf(listOf(tapRow("a", 1_000), text, rotate, author, unnamedKey)) { null }
        val sensitivity = RewriteSensitivity(inputs)

        assertNotNull(buildRecordedInput(inputs, setOf("a"), sensitivity))
        assertNull(buildRecordedInput(inputs, setOf("t"), sensitivity), "typed text")
        assertNull(buildRecordedInput(inputs, setOf("r"), sensitivity), "rotation")
        assertNull(buildRecordedInput(inputs, setOf("a", "t"), sensitivity), "one input that cannot be replayed spoils the step")
        assertNull(buildRecordedInput(inputs, setOf("x"), sensitivity), "a row the person wrote")
        assertNull(buildRecordedInput(inputs, setOf("k"), sensitivity), "a key a lane cannot press")
        assertNull(buildRecordedInput(inputs, setOf("missing"), sensitivity))
    }

    @Test
    fun anInputThatTouchedAPasswordOrFollowsItInTheSensitiveWindowIsWithheld() {
        val password = RecordedTestStep("Enter text: ••••", kind = RecordedInputKind.TEXT, inputStartMs = 10_000, inputAtMs = 10_000, id = "p")
        val inputs = rewriteInputsOf(listOf(tapRow("early", 100), password, tapRow("late", 11_000))) { null }
        val sensitivity = RewriteSensitivity(inputs)

        assertNotNull(buildRecordedInput(inputs, setOf("early"), sensitivity), "well before the password: kept")
        assertNull(buildRecordedInput(inputs, setOf("p"), sensitivity))
        assertNull(buildRecordedInput(inputs, setOf("late"), sensitivity), "in the sensitive window after it")
    }

    @Test
    fun aGestureFromARecordingStoredBeforeTimesWereKeptIsSpreadEvenlyOverItsDuration() {
        val swipe = RecordedTestStep(
            "Swipe", kind = RecordedInputKind.SWIPE, durationMs = 600, inputStartMs = 1_000, inputAtMs = 1_600,
            gesturePath = listOf(RecordedPoint(10, 10, 100, 200), RecordedPoint(10, 50, 100, 200), RecordedPoint(10, 90, 100, 200)), id = "s",
        )
        val inputs = rewriteInputsOf(listOf(swipe)) { null }

        val touch = assertNotNull(buildRecordedInput(inputs, setOf("s"), RewriteSensitivity(inputs))).events.single() as RecordedInputEvent.Touch

        assertEquals(listOf(0L, 300L, 600L), touch.points.map { it.tMs })
    }

    @Test
    fun sameSizeReplayKeepsTheOriginalTouchedPixelIncludingTheBottomRightEdge() {
        val row = RecordedTestStep(
            "Tap the icon", kind = RecordedInputKind.TAP, durationMs = 80, inputStartMs = 100, inputAtMs = 180,
            gesturePath = listOf(RecordedPoint(99, 199, 100, 200, 0)), id = "edge",
        )
        val inputs = rewriteInputsOf(listOf(row)) { null }
        val recorded = assertNotNull(buildRecordedInput(inputs, setOf("edge"), RewriteSensitivity(inputs)))
        val plan = planReplay(recorded, DeviceScreenCoordinateSpace(100, 200, 100, 200)) as ReplayPlan.Ready
        assertEquals(DeviceInputAction.Tap(99, 199), plan.actions.single())
    }

    @Test
    fun denseReferencePathKeepsTheMovementEndpointAndOneHold() {
        val path = listOf(RecordedPoint(50, 190, 100, 200, 0)) +
            (1..40).map { RecordedPoint(50, 190 - it * 2, 100, 200, it * 10L) } +
            RecordedPoint(50, 110, 100, 200, 900)
        val row = RecordedTestStep(
            "Swipe from bottom to top over 900 ms", kind = RecordedInputKind.SWIPE, durationMs = 900,
            inputStartMs = 0, inputAtMs = 900, gesturePath = path, id = "dense",
        )
        val text = rewriteInputsOf(listOf(row)) { null }.single().describe(RewriteDescription.HINT)
        val gesture = parseReferenceGestures(text).single()
        assertTrue(gesture.points.size <= 12)
        assertEquals(400L, gesture.points.last().tMs, "last movement, not a prefix of the path")
        assertEquals(500L, gesture.holdMs, "the release is represented once, as holdEndMs")
        assertEquals(900L, gesture.durationMs)
    }

    // ── The reference-log text ──────────────────────────────────────

    @Test
    fun theReferenceLogCarriesTheTimedPathAndTheRestBeforeTheLift() {
        val session = stopped { swipeUpAndHold(it) }
        val rows = session.snapshot.value.steps
        val text = rewriteInputsOf(rows) { null }.single().describe(RewriteDescription.HINT)

        assertTrue(text.contains("normalized path: (59%,98%)->"), "the existing path is still there: $text")
        assertTrue(text.contains(" | timed path (ms from touch-down): (59.6%,98.5%)@0 -> (59.6%,95.5%)@100 -> "), text)
        assertTrue(text.contains("(57.6%,56.3%)@420"), text)
        assertTrue("@752" !in text, "release is represented by hold separately: $text")
        assertTrue(text.contains(" | rested 332 ms on the last position before the finger lifted"), text)
        session.close()
    }

    @Test
    fun aGestureWithoutRecordedTimesKeepsTheOldLogLine() {
        val old = RecordedTestStep(
            "Swipe from (10%, 10%) to (10%, 90%) over 600 ms", kind = RecordedInputKind.SWIPE, durationMs = 600, inputStartMs = 1_000,
            inputAtMs = 1_600, gesturePath = listOf(RecordedPoint(10, 20, 100, 200), RecordedPoint(10, 180, 100, 200)), id = "s",
        )
        val text = rewriteInputsOf(listOf(old)) { null }.single().describe(RewriteDescription.HINT)
        assertTrue(text.contains("normalized path: (10%,10%)->(10%,90%)"), text)
        assertTrue("timed path" !in text && "rested" !in text, text)
    }

    @Test
    fun theTimedPathFormatsAndParsesBack() {
        val points = listOf(TimedPercentPoint(59.4, 98.2, 0), TimedPercentPoint(59.1, 95.0, 180), TimedPercentPoint(56.9, 56.1, 752))
        assertEquals("(59.4%,98.2%)@0 -> (59.1%,95.0%)@180 -> (56.9%,56.1%)@752", formatTimedPath(points))
        assertNull(formatHold(40), "a rest of a few milliseconds is no hold")
        assertEquals("rested 332 ms on the last position before the finger lifted", formatHold(332))
        assertEquals(0L, restBeforeLiftMs(points, 752), "moving until the end")

        val line = "2. Swipe up | normalized path: (59%,98%)->(57%,56%) | timed path (ms from touch-down): " +
            "(59.4%,98.2%)@0 -> (59.1%,95.0%)@180 -> (56.9%,56.1%)@420 -> (56.9%,56.1%)@752 | " +
            "rested 332 ms on the last position before the finger lifted | gap before next input: 5 ms"
        val gesture = parseReferenceGestures(line).single()
        assertEquals(2, gesture.inputNumber)
        assertEquals(ReferenceTiming.RECORDED, gesture.timing)
        assertEquals(listOf(0L, 180L, 420L), gesture.points.map { it.tMs })
        assertEquals(59.4, gesture.points.first().xPercent)
        assertEquals(752L, gesture.durationMs)
        assertEquals(332L, gesture.holdMs)
    }

    @Test
    fun anOldLogWithoutTimingStillParsesWithEvenSpacingAssumed() {
        val old = "3. Swipe from (59%, 98%) to (57%, 56%) over 752 ms | normalized path: (59%,98%)->(59%,98%)->(59%,98%)->(59%,95%)->" +
            "(58%,85%)->(57%,76%)->(57%,65%)->(57%,58%)->(57%,56%)->(57%,56%) | source orientation quarter turns: 0 | gap before next input: 800 ms"

        val gesture = parseReferenceGestures(old).single()

        assertEquals(3, gesture.inputNumber)
        assertEquals(ReferenceTiming.EVEN_SPACING_ASSUMED, gesture.timing)
        assertEquals(10, gesture.points.size)
        assertEquals(0L, gesture.points.first().tMs)
        assertEquals(752L, gesture.points.last().tMs)
        assertEquals(listOf(0L, 84L, 167L, 251L), gesture.points.take(4).map { it.tMs })
        assertEquals(0L, gesture.holdMs, "an old log says nothing about a rest")
        assertEquals(57.0, gesture.points.last().xPercent)
    }

    @Test
    fun textThatIsNotARecordedInputLogGivesNoGestures() {
        assertTrue(parseReferenceGestures("").isEmpty())
        assertTrue(parseReferenceGestures("Observed on the device after this input while recording\nWi-Fi turned off").isEmpty())
        assertTrue(parseReferenceGestures("1. Press Back | before app: com.example").isEmpty())
        assertTrue(parseReferenceGestures("timed path (ms from touch-down): garbage").isEmpty())
    }

    @Test
    fun theAgentGetsTheParsedGesturesNextToTheFencedLog() {
        val text = "1. Swipe up | normalized path: (59%,98%)->(57%,56%) | timed path (ms from touch-down): (59.0%,98.0%)@0 -> (57.0%,56.0%)@420 -> " +
            "(57.0%,56.0%)@752 | rested 332 ms on the last position before the finger lifted"

        val extra = recordedGesturesOf(text)

        val gestures = extra.getValue("recordedGestures") as List<*>
        val first = gestures.single() as Map<*, *>
        assertEquals("recorded", first["timing"])
        assertEquals(332L, first["restBeforeLiftMs"])
        assertEquals(2, (first["points"] as List<*>).size)
        assertTrue(extra.getValue("recordedGesturesHowTo").toString().contains("holdEndMs"))
        assertTrue(recordedGesturesOf("just some words").isEmpty())
    }
}
