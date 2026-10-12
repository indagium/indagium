@file:Suppress("MagicNumber") // Fixture coordinates, timestamps and sizes, not tunable constants.

package com.indagium.testing

import com.indagium.testing.model.RecordedInput
import com.indagium.testing.model.RecordedInputEvent
import com.indagium.testing.model.RecordedTouchKind
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.TimedGesturePoint
import com.indagium.testing.model.newStepId

/** The swipe of the user's run: up from the bottom edge, arriving at (57%, 56%) after 420 ms and resting there until the lift at 752 ms. */
internal fun swipeUpAndHoldEvent(gapBeforeMs: Long = 0L, width: Int = 1080, height: Int = 2400) = RecordedInputEvent.Touch(
    kind = RecordedTouchKind.SWIPE,
    points = listOf(
        TimedGesturePoint(0.59, 0.98, 0),
        TimedGesturePoint(0.59, 0.95, 100),
        TimedGesturePoint(0.58, 0.85, 200),
        TimedGesturePoint(0.57, 0.70, 300),
        TimedGesturePoint(0.57, 0.56, 420),
        TimedGesturePoint(0.57, 0.56, 752),
    ),
    durationMs = 752,
    sourceScreenWidth = width,
    sourceScreenHeight = height,
    orientationQuarterTurns = 0,
    gapBeforeMs = gapBeforeMs,
)

internal fun tapEvent(x: Double = 0.18, y: Double = 0.16, gapBeforeMs: Long = 0L, width: Int = 1080, height: Int = 2400) = RecordedInputEvent.Touch(
    kind = RecordedTouchKind.TAP,
    points = listOf(TimedGesturePoint(x, y, 0)),
    durationMs = 90,
    sourceScreenWidth = width,
    sourceScreenHeight = height,
    orientationQuarterTurns = 0,
    gapBeforeMs = gapBeforeMs,
)

/** Tap the Wi-Fi tile's toggle, swipe up and hold, press Back: with the 700 ms the person waited before doing anything else. */
internal fun sampleRecordedInput(settleMs: Long = 700L, width: Int = 1080, height: Int = 2400) = RecordedInput(
    listOf(
        tapEvent(width = width, height = height),
        swipeUpAndHoldEvent(gapBeforeMs = 300, width = width, height = height),
        RecordedInputEvent.Key("BACK", gapBeforeMs = 200),
    ),
    settleMs,
)

internal fun replayStep(
    action: String = "Swipe up from the bottom and hold",
    input: RecordedInput? = sampleRecordedInput(),
    expected: String = "Recents opens",
    kind: StepKind = StepKind.REPLAY,
    timeoutMs: Long = 30_000L,
    retries: Int = 0,
) = TestStep(newStepId(), action, expected, timeoutMs = timeoutMs, retries = retries, kind = kind, recordedInput = input)
