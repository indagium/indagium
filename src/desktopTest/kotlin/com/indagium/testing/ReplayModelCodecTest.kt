package com.indagium.testing

import com.indagium.testing.device.replayProblem
import com.indagium.testing.model.DEFAULT_STEP_RETRIES
import com.indagium.testing.model.DEFAULT_STEP_TIMEOUT_MS
import com.indagium.testing.model.RecordedInput
import com.indagium.testing.model.RecordedInputEvent
import com.indagium.testing.model.ScreenshotRole
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.StepIntent
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.TimedGesturePoint
import com.indagium.testing.model.estimatedReplayMs
import com.indagium.testing.model.newSharedStepId
import com.indagium.testing.model.newStepId
import com.indagium.testing.model.usesReplay
import com.indagium.testing.model.usesStepKinds
import com.indagium.testing.model.withKind
import com.indagium.testing.store.TEST_LIBRARY_GESTURE_HINT_FILE_VERSION
import com.indagium.testing.store.TEST_SUITE_BASE_FILE_VERSION
import com.indagium.testing.store.TEST_SUITE_FILE_VERSION
import com.indagium.testing.store.TEST_SUITE_GESTURE_HINT_FILE_VERSION
import com.indagium.testing.store.TEST_SUITE_REPLAY_FILE_VERSION
import com.indagium.testing.store.decodeLibraryFile
import com.indagium.testing.store.decodeRecordedInput
import com.indagium.testing.store.decodeRunFile
import com.indagium.testing.store.decodeSuiteFile
import com.indagium.testing.store.encodeLibraryFile
import com.indagium.testing.store.encodeRunFile
import com.indagium.testing.store.encodeSuiteFile
import com.indagium.testing.store.validateStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Replay kind and the recorded input in the library files: the optional field, the version a replay step needs, and old files. */
class ReplayModelCodecTest {
    private fun versionOf(text: String): Int = (Json.parseToJsonElement(text) as JsonObject)["version"]!!.jsonPrimitive.content.toInt()

    private fun suiteWith(vararg steps: TestStep) = suiteOf(caseOf("Hub", *steps))

    private fun stepOf(text: String): JsonObject =
        Json.parseToJsonElement(text).jsonObject["suite"]!!.jsonObject["cases"]!!.jsonArray[0].jsonObject["steps"]!!.jsonArray[0].jsonObject

    // ── The optional field ──────────────────────────────────────────

    @Test
    fun recordedInputRoundTripsWithTheTimeOfEverySampleTheGapsAndTheScreen() {
        val suite = suiteWith(replayStep())

        val decoded = decodeSuiteFile(encodeSuiteFile(suite)).getOrThrow().suite

        assertEquals(suite.cases, decoded.cases)
        val input = assertNotNull(decoded.cases.single().steps.single().recordedInput)
        assertEquals(3, input.events.size)
        assertEquals(700L, input.settleMs)
        val swipe = input.events[1] as RecordedInputEvent.Touch
        assertEquals(listOf(0L, 100L, 200L, 300L, 420L, 752L), swipe.points.map { it.tMs })
        assertEquals(300L, swipe.gapBeforeMs)
        assertEquals(1080, swipe.sourceScreenWidth)
        assertEquals(0, swipe.orientationQuarterTurns)
        assertEquals(RecordedInputEvent.Key("BACK", 200L), input.events[2])
    }

    @Test
    fun anAiStepKeepsItsRecordedInputWithoutRaisingTheFileVersion() {
        val suite = suiteWith(replayStep(kind = StepKind.AGENT))

        val text = encodeSuiteFile(suite)

        assertEquals(TEST_SUITE_BASE_FILE_VERSION, versionOf(text), "the field is an enrichment an older build may ignore")
        assertTrue("recordedInput" in stepOf(text))
        assertNull(stepOf(text)["kind"], "an AI step is written as before")
        assertEquals(suite.cases, decodeSuiteFile(text).getOrThrow().suite.cases)
    }

    @Test
    fun aStepWithoutTheFieldIsWrittenWithoutIt() {
        val text = encodeSuiteFile(suiteWith(TestStep(newStepId(), "Tap")))
        assertFalse("recordedInput" in stepOf(text))
    }

    @Test
    fun aFileWrittenBeforeTheFieldExistedLoadsWithNullRecordedInput() {
        val old = """{"format":"indagium-test-suite","version":4,"suite":{"id":"suite-a","cases":[{"id":"case-a","steps":[
            {"id":"step-a","action":"Tap it","gestureHints":[{"kind":"TAP","path":[{"x":0.5,"y":0.5}]}]}]}]}}"""

        val step = decodeSuiteFile(old).getOrThrow().suite.cases.single().steps.single()

        assertNull(step.recordedInput)
        assertEquals(StepKind.AGENT, step.kind)
        assertEquals(1, step.gestureHints.size)
    }

    // ── The kind and the version ────────────────────────────────────

    @Test
    fun aReplayStepIsWrittenAtVersionFiveSoAnOlderBuildOpensTheSuiteReadOnly() {
        val suite = suiteWith(replayStep())

        val text = encodeSuiteFile(suite)

        assertTrue(suite.usesReplay() && suite.usesStepKinds())
        assertEquals(TEST_SUITE_REPLAY_FILE_VERSION, versionOf(text))
        assertTrue(TEST_SUITE_REPLAY_FILE_VERSION > TEST_SUITE_GESTURE_HINT_FILE_VERSION, "a build that reads up to 4 flags it read-only and never rewrites it")
        assertEquals("REPLAY", stepOf(text)["kind"]!!.jsonPrimitive.content)
        assertEquals(TEST_SUITE_FILE_VERSION, TEST_SUITE_REPLAY_FILE_VERSION)
        val decoded = decodeSuiteFile(text).getOrThrow()
        assertFalse(decoded.readOnly, "this build knows version 5")
        assertEquals(StepKind.REPLAY, decoded.suite.cases.single().steps.single().kind)
    }

    @Test
    fun anOlderBuildThatReadsAnUnknownKindFallsBackToAnAiStepAndAReplayKindIsOneSuchKind() {
        // What a build without Replay does with `"kind":"REPLAY"`: the tolerant reader takes an unknown kind name as AGENT, so the
        // step stays a working AI step (its text describes the gesture); the version bump above keeps that build from saving it.
        val text = encodeSuiteFile(suiteWith(replayStep())).replace("\"REPLAY\"", "\"SOME_FUTURE_KIND\"")
        val step = decodeSuiteFile(text).getOrThrow().suite.cases.single().steps.single()
        assertEquals(StepKind.AGENT, step.kind)
        assertNotNull(step.recordedInput, "the structured input is untouched")
    }

    @Test
    fun aSharedStepWithAReplayStepRaisesTheLibraryVersionToo() {
        val shared = SharedStep(newSharedStepId(), "Open recents", steps = listOf(replayStep()))

        val text = encodeLibraryFile(emptyList(), emptyList(), listOf(shared))

        assertEquals(TEST_SUITE_REPLAY_FILE_VERSION, versionOf(text))
        assertTrue(TEST_LIBRARY_GESTURE_HINT_FILE_VERSION < versionOf(text))
        val decoded = decodeLibraryFile(text).getOrThrow()
        assertEquals(StepKind.REPLAY, decoded.sharedSteps.single().steps.single().kind)
        assertEquals(shared.steps.single().recordedInput, decoded.sharedSteps.single().steps.single().recordedInput)
    }

    @Test
    fun aReplayStepSurvivesTheRunFileBecauseTheRunReplaysFromItsFrozenSnapshot() {
        val step = replayStep()
        val run = com.indagium.testing.model.TestRun(
            "run-1", suiteWith(step), emptyList(), emptyList(),
            com.indagium.testing.model.RunConfig("suite-1", null, listOf(agentLane())),
            emptyList(),
        )

        val decoded = decodeRunFile(encodeRunFile(run)).getOrThrow()

        val frozen = decoded.suite.cases.single().steps.single()
        assertEquals(StepKind.REPLAY, frozen.kind)
        assertEquals(step.recordedInput, frozen.recordedInput)
    }

    // ── Unreadable input ────────────────────────────────────────────

    @Test
    fun aRecordedInputWithAnInputOfAnUnknownTypeIsDroppedAsAWholeNotReplayedInPart() {
        val text = encodeSuiteFile(suiteWith(replayStep(kind = StepKind.AGENT)))
            .replace("\"type\": \"key\"", "\"type\": \"gamepad\"")

        val step = decodeSuiteFile(text).getOrThrow().suite.cases.single().steps.single()

        assertNull(step.recordedInput, "a partial replay would do something other than what was recorded")
    }

    @Test
    fun garbageInTheFieldDecodesAsNoRecordedInput() {
        val base = encodeSuiteFile(suiteWith(replayStep(kind = StepKind.AGENT)))
        val noEvents = base.replace("\"events\": [", "\"events_\": [")
        assertNull(decodeSuiteFile(noEvents).getOrThrow().suite.cases.single().steps.single().recordedInput, "no inputs at all")
        val outOfRange = base.replace("\"x\": 0.18", "\"x\": 7.5")
        assertNull(decodeSuiteFile(outOfRange).getOrThrow().suite.cases.single().steps.single().recordedInput, "a position off the screen")
    }

    @Test
    fun malformedOrOversizedRecordedInputsAreDroppedWhole() {
        fun decode(json: String) = decodeRecordedInput(Json.parseToJsonElement(json).jsonObject)
        assertNull(decode("""{"events":[{"type":"key","key":"BACK"},42]}"""), "a nonobject event cannot disappear")
        val points = (0..64).joinToString(",") { """{"x":0.5,"y":0.5,"t":$it}""" }
        assertNull(decode("""{"events":[{"type":"touch","kind":"SWIPE","durationMs":1000,"points":[$points]}]}"""))
        assertNull(decode("""{"events":[{"type":"touch","kind":"SWIPE","durationMs":500,"points":[{"x":0.2,"y":0.3,"t":-1}]}]}"""))
    }

    @Test
    fun recordedScreenRolesRaiseTheVersionAndLegacyScreensStayExpected() {
        val before = StepExample.GoldenScreenshot("ex-before", "before.jpg", role = ScreenshotRole.RECORDED_BEFORE)
        val suite = suiteWith(TestStep(newStepId(), "Tap the Wi-Fi icon", examples = listOf(before)))
        val encoded = encodeSuiteFile(suite)
        assertEquals(TEST_SUITE_REPLAY_FILE_VERSION, versionOf(encoded), "old builds must not mistake a baseline for an oracle")
        assertEquals(ScreenshotRole.RECORDED_BEFORE, (decodeSuiteFile(encoded).getOrThrow().suite.cases.single().steps.single()
            .examples.single() as StepExample.GoldenScreenshot).role)
        assertTrue(validateStep(suite.cases.single().steps.single().copy(checks = listOf(
            StepCheck.ScreenJudge("chk-1", "The screen changed", before.id),
        )))!!.contains("expected screenshot"), "a baseline cannot be bound as the expected image")
        val legacy = encodeSuiteFile(suiteWith(TestStep(newStepId(), "Tap", examples = listOf(
            StepExample.GoldenScreenshot("ex-expected", "expected.jpg"),
        ))))
        assertEquals(TEST_SUITE_BASE_FILE_VERSION, versionOf(legacy))
        assertTrue("\"role\"" !in legacy)
        assertEquals(ScreenshotRole.EXPECTED, (decodeSuiteFile(legacy).getOrThrow().suite.cases.single().steps.single()
            .examples.single() as StepExample.GoldenScreenshot).role)
        assertTrue(decodeSuiteFile(encoded.replace("recorded_before", "unknown_role")).isFailure, "an unknown explicit role fails closed")
    }

    // ── Validation and kind switching ───────────────────────────────

    @Test
    fun aReplayStepNeedsReplayableInputAndNoScriptOrOptionalCondition() {
        assertNull(validateStep(replayStep()))
        assertTrue(validateStep(replayStep(input = null))!!.contains("recorded"), "no input")
        assertTrue(validateStep(replayStep(input = RecordedInput(emptyList())))!!.contains("no inputs"))
        assertTrue(validateStep(replayStep().copy(optional = true, condition = "an ad"))!!.contains("optional"))
        assertTrue(validateStep(replayStep().copy(intent = StepIntent.ENSURE_STATE))!!.contains("perform an action"))
        assertTrue(validateStep(replayStep().copy(precondition = "The tile is off"))!!.contains("precondition"))
        assertTrue(validateStep(replayStep().copy(script = com.indagium.testing.model.ScriptCall("script-x")))!!.contains("script"))
        val badKey = RecordedInput(listOf(RecordedInputEvent.Key("SLEEP")))
        assertTrue(validateStep(replayStep(input = badKey))!!.contains("SLEEP"))
        val backwards = RecordedInput(
            listOf(
                RecordedInputEvent.Touch(
                    com.indagium.testing.model.RecordedTouchKind.SWIPE,
                    listOf(TimedGesturePoint(0.1, 0.1, 0), TimedGesturePoint(0.2, 0.2, 500), TimedGesturePoint(0.3, 0.3, 100)),
                    600, 100, 200,
                ),
            ),
        )
        assertTrue(validateStep(replayStep(input = backwards))!!.contains("time order"))
        assertNotNull(replayStep(input = null).replayProblem())
        assertNull(replayStep().replayProblem())
    }

    @Test
    fun switchingToReplayDropsTheScriptDoesNotRetryAndMakesRoomForTheRecordedTime() {
        val ai = replayStep(kind = StepKind.AGENT, timeoutMs = DEFAULT_STEP_TIMEOUT_MS, retries = DEFAULT_STEP_RETRIES)
            .copy(script = com.indagium.testing.model.ScriptCall("script-x"))

        val replay = ai.withKind(StepKind.REPLAY)

        assertEquals(StepKind.REPLAY, replay.kind)
        assertNull(replay.script)
        assertEquals(0, replay.retries, "re-injecting could undo a toggle, so a replay is not retried unless its author asks")
        assertEquals(DEFAULT_STEP_TIMEOUT_MS, replay.timeoutMs.coerceAtMost(DEFAULT_STEP_TIMEOUT_MS), "the default is kept when it is enough")
        assertNotNull(replay.recordedInput, "the recorded input stays on the step whatever its kind")

        val back = replay.withKind(StepKind.AGENT)
        assertEquals(DEFAULT_STEP_RETRIES, back.retries)
        assertNotNull(back.recordedInput)

        val long = RecordedInput(List(10) { RecordedInputEvent.Key("BACK", gapBeforeMs = 5_000) })
        val roomy = replayStep(kind = StepKind.AGENT, input = long, timeoutMs = DEFAULT_STEP_TIMEOUT_MS).withKind(StepKind.REPLAY)
        assertTrue(roomy.timeoutMs > DEFAULT_STEP_TIMEOUT_MS && roomy.timeoutMs >= long.estimatedReplayMs() + 30_000, "${roomy.timeoutMs}")
    }

    @Test
    fun theEstimateCountsGapsDurationsAndTheSettle() {
        // 0 + 90 (tap), 300 + 752 (swipe), 200 + 0 (back), plus the 700 ms settle.
        assertEquals(90 + 300 + 752 + 200 + 700L, sampleRecordedInput().estimatedReplayMs())
    }
}
