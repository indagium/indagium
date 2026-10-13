@file:Suppress("MagicNumber") // Fixture coordinates, timestamps and sizes, not tunable constants.

package com.indagium.testing

import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorKeyAction
import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.testing.authoring.RecordingApplyOptions
import com.indagium.testing.authoring.TestStepRecordingApplyService
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.authoring.asReplayStepOrSame
import com.indagium.testing.authoring.canBeReplay
import com.indagium.testing.model.RecordedInputEvent
import com.indagium.testing.model.RecordedTouchKind
import com.indagium.testing.model.StepIntent
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestStep
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Applying a recording keeps each step's structured input; "Apply as replay steps" turns the replayable ones into REPLAY steps. */
class ReplayApplyTest {
    private fun touch(action: MirrorTouchAction, x: Int, y: Int) =
        MirrorControlCommand.Touch(action = action, pointerId = 1, x = x, y = y, screenWidth = 100, screenHeight = 200)

    /** Tap, swipe up and hold, typed text: the first two can be replayed, the text cannot. */
    private fun reviewedSession(): TestStepRecordingSession = runBlocking {
        val session = TestStepRecordingSession("fixture-device")
        session.accept(touch(MirrorTouchAction.DOWN, 18, 32), nowMs = 1_000)
        session.accept(touch(MirrorTouchAction.UP, 18, 32), nowMs = 1_080)
        session.accept(touch(MirrorTouchAction.DOWN, 59, 196), nowMs = 2_000)
        session.accept(touch(MirrorTouchAction.MOVE, 57, 112), nowMs = 2_420)
        session.accept(touch(MirrorTouchAction.UP, 57, 112), nowMs = 2_752)
        session.accept(MirrorControlCommand.Text("hello"), nowMs = 4_000)
        session.stopAndDrain()
        assertTrue(
            session.updateReviewedSteps(
                listOf("Toggle Wi-Fi" to "Wi-Fi is off", "Swipe up and hold" to "Recents opens", "Type hello" to "The text shows"),
            ),
        )
        session
    }

    private fun service(inserted: MutableList<TestStep>) = TestStepRecordingApplyService(
        preflight = { _, _ -> StoreResult.Ok(Unit) },
        importImage = { _, _ -> error("No screenshot is expected") },
        resolveImage = { _, _ -> null },
        insertSteps = { _, steps, _ -> inserted += steps; StoreResult.Ok(steps) },
    )

    @Test
    fun applyingKeepsTheStructuredInputOfEveryStepAndLeavesThemAiSteps() = runBlocking {
        val session = reviewedSession()
        val inserted = mutableListOf<TestStep>()

        val result = service(inserted).apply(session, "suite-fixture", "case-fixture")

        assertTrue(result is StoreResult.Ok)
        assertEquals(listOf(StepKind.AGENT, StepKind.AGENT, StepKind.AGENT), inserted.map { it.kind })
        val tap = assertNotNull(inserted[0].recordedInput).events.single()
        assertTrue(tap is RecordedInputEvent.Touch && tap.points.single().x == 18.0 / 99.0)
        val swipe = assertNotNull(inserted[1].recordedInput).events.single() as RecordedInputEvent.Touch
        assertEquals(752L, swipe.durationMs)
        assertEquals(listOf(0L, 420L, 752L), swipe.points.map { it.tMs })
        assertNull(inserted[2].recordedInput, "typed text cannot be replayed")
        session.close()
    }

    @Test
    fun applyAsReplayStepsMakesTheReplayableStepsReplayStepsAndTellsHowManyStayedAi() = runBlocking {
        val session = reviewedSession()
        val inserted = mutableListOf<TestStep>()

        val result = service(inserted).apply(session, "suite-fixture", "case-fixture", options = RecordingApplyOptions().withReplay(true))

        val ok = result as StoreResult.Ok
        assertEquals(listOf(StepKind.REPLAY, StepKind.REPLAY, StepKind.AGENT), inserted.map { it.kind })
        assertEquals(0, inserted[0].retries, "a replay is not retried unless its author asks")
        assertNotNull(inserted[0].recordedInput)
        assertTrue(ok.warnings.any { it.contains("2 step(s) were applied as replay steps") }, ok.warnings.toString())
        assertTrue(ok.warnings.any { it.contains("1 step(s) stay AI steps") }, ok.warnings.toString())
        session.close()
    }

    @Test
    fun onlyTheSelectedRecordedSwipeIsReplayedWhileOtherStepsStayAi() = runBlocking {
        val session = reviewedSession()
        val swipeId = session.snapshot.value.steps[1].id
        val inserted = mutableListOf<TestStep>()
        val result = service(inserted).apply(
            session, "suite-fixture", "case-fixture", options = RecordingApplyOptions().withReplayRows(setOf(swipeId)),
        ) as StoreResult.Ok

        assertEquals(listOf(StepKind.AGENT, StepKind.REPLAY, StepKind.AGENT), inserted.map { it.kind })
        assertEquals(RecordedTouchKind.SWIPE, (assertNotNull(inserted[1].recordedInput).events.single() as RecordedInputEvent.Touch).kind)
        assertNotNull(inserted[0].recordedInput, "unselected AI steps retain recording guidance")
        assertTrue(result.warnings.any { "1 step(s) were applied as replay steps" in it })
        assertTrue(result.warnings.any { "unselected rows are not converted" in it })
        session.close()
    }

    @Test
    fun replaySelectionFollowsTheRowIdWhenTheReviewIsReordered() = runBlocking {
        val session = reviewedSession()
        val swipeId = session.snapshot.value.steps[1].id
        assertTrue(session.moveReviewedRow(swipeId, 0))
        val inserted = mutableListOf<TestStep>()
        assertTrue(service(inserted).apply(
            session, "suite-fixture", "case-fixture", options = RecordingApplyOptions().withReplayRows(setOf(swipeId)),
        ) is StoreResult.Ok)

        assertEquals(listOf(StepKind.REPLAY, StepKind.AGENT, StepKind.AGENT), inserted.map { it.kind })
        assertEquals("Swipe up and hold", inserted.first().action)
        session.close()
    }

    @Test
    fun missingAndIneligibleReplaySelectionsAreRejectedWithoutInsertingSteps() = runBlocking {
        val session = reviewedSession()
        val typedTextId = session.snapshot.value.steps[2].id
        val inserted = mutableListOf<TestStep>()
        for (id in listOf("missing-row", typedTextId)) {
            val result = service(inserted).apply(
                session, "suite-fixture", "case-fixture", options = RecordingApplyOptions().withReplayRows(setOf(id)),
            )
            assertTrue(result is StoreResult.Invalid)
            assertTrue(inserted.isEmpty())
        }
        assertTrue(service(inserted).apply(session, "suite-fixture", "case-fixture") is StoreResult.Ok, "a refusal releases the review reservation")
        assertTrue(inserted.all { it.kind == StepKind.AGENT })
        session.close()
    }

    @Test
    fun optionalRecordedRowsCannotBeSelectedForReplayAndRemainOptionalAiSteps() = runBlocking {
        val session = reviewedSession()
        val rows = session.snapshot.value.steps
        val tapId = rows.first().id
        assertTrue(session.updateReviewedSteps(
            rows.map { it.action to it.expected }, optionalOverrides = mapOf(tapId to (true to "Wi-Fi is on")),
        ))
        assertEquals(setOf(rows[1].id), session.replayableRowIds())
        val inserted = mutableListOf<TestStep>()
        assertTrue(service(inserted).apply(
            session, "suite-fixture", "case-fixture", options = RecordingApplyOptions().withReplayRows(setOf(tapId)),
        ) is StoreResult.Invalid)
        assertTrue(inserted.isEmpty())
        assertTrue(service(inserted).apply(
            session, "suite-fixture", "case-fixture", options = RecordingApplyOptions().withReplay(true),
        ) is StoreResult.Ok)
        assertEquals(StepKind.AGENT, inserted.first().kind)
        assertTrue(inserted.first().optional)
        assertEquals("Wi-Fi is on", inserted.first().condition)
        assertEquals(StepKind.REPLAY, inserted[1].kind)
        session.close()
    }

    @Test
    fun onlyAPlainPerformActionStepWithReplayableInputBecomesAReplayStep() {
        val replayable = replayStep(kind = StepKind.AGENT)
        assertTrue(replayable.canBeReplay())
        assertEquals(StepKind.REPLAY, asReplayStepOrSame(replayable).kind)

        val optional = replayable.copy(optional = true, condition = "an ad is playing")
        val ensure = replayable.copy(intent = StepIntent.ENSURE_STATE)
        val precondition = replayable.copy(precondition = "The shade is closed")
        val noInput = replayable.copy(recordedInput = null)
        val already = replayStep(kind = StepKind.MANUAL)
        listOf(optional, ensure, precondition, noInput, already).forEach { step ->
            assertFalse(step.canBeReplay(), step.toString())
            assertEquals(step, asReplayStepOrSame(step), "left as it is")
        }
        assertTrue(asReplayStepOrSame(optional).optional, "an optional step is not silently made required")
    }

    @Test
    fun theReviewPanelCountsTheRowsThatCanBeReplayed() {
        val session = reviewedSession()
        assertEquals(2, session.replayableRowCount())
        session.close()
        val empty = TestStepRecordingSession("fixture-device")
        empty.accept(MirrorControlCommand.Key(MirrorKeyAction.DOWN, 66), nowMs = 1_000)
        empty.accept(MirrorControlCommand.Text("typed"), nowMs = 2_000)
        empty.stop()
        assertEquals(1, empty.replayableRowCount(), "the Enter key can be pressed again, the text cannot")
        empty.close()
    }
}
