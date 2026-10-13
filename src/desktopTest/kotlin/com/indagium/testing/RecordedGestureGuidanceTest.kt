package com.indagium.testing

import com.indagium.testing.model.RecordedInput
import com.indagium.testing.model.RecordedInputEvent
import com.indagium.testing.model.RecordedTouchKind
import com.indagium.testing.model.TimedGesturePoint
import com.indagium.testing.run.LaneStepBrief
import com.indagium.testing.run.lanePrompt
import com.indagium.testing.run.recordedGestureContext
import com.indagium.testing.run.recordedGestureGuidance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordedGestureGuidanceTest {
    private val times = listOf(0L, 36L, 59L, 201L, 351L, 480L, 564L, 577L)
    private val input = RecordedInput(listOf(RecordedInputEvent.Touch(
        RecordedTouchKind.SWIPE,
        times.mapIndexed { index, time -> TimedGesturePoint(0.58 + index * 0.01, 0.98 - index * 0.08, time) },
        577L, 1234, 1280,
    )))

    @Test
    fun everyRecordedSampleTimeSurvivesTheInitialPromptAndProtocolBrief() {
        val step = step("Swipe up from the bottom").copy(recordedInput = input)
        val suite = suiteOf(caseOf("YouTube", step))
        val case = suite.cases.single()
        val prompt = lanePrompt(suite, case, case.name, case.steps, 0, 1, emptyList(), setup = false)
        val brief = LaneStepBrief(step.id, case.name, 1, 1, step.action, step.expected, recordedInput = input)
        val data = brief.recordedGestureContext()["untrusted_data"] as Map<*, *>
        val gestures = data["recordedGestures"] as List<*>
        val points = (gestures.single() as Map<*, *>)["points"] as List<*>
        assertEquals(times, points.map { (it as Map<*, *>)["tMs"] })
        times.forEach { assertTrue("\"tMs\":$it" in prompt, prompt) }
        assertTrue("source=\"recorded_gesture_samples\"" in prompt)
    }

    @Test
    fun releaseIsIncludedOnceWithoutAnExtraHold() {
        val held = input.copy(events = listOf((input.events.single() as RecordedInputEvent.Touch).copy(durationMs = 900)))
        val gesture = (recordedGestureGuidance(held)["recordedGestures"] as List<*>).single() as Map<*, *>
        val points = gesture["points"] as List<*>
        assertEquals(900L, (points.last() as Map<*, *>)["tMs"])
        assertEquals(0L, gesture["restBeforeLiftMs"])
    }
}
