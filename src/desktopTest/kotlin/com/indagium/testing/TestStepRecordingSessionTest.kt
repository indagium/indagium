package com.indagium.testing

import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorFrame
import com.indagium.capture.mirror.MirrorKeyAction
import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.testing.authoring.TestStepRecordingSession
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TestStepRecordingSessionTest {
    private fun touch(action: MirrorTouchAction, pointer: Long, x: Int, y: Int) = MirrorControlCommand.Touch(
        action = action, pointerId = pointer, x = x, y = y, screenWidth = 100, screenHeight = 200,
    )

    private fun frame() = MirrorFrame(16, 12, IntArray(16 * 12) { 0xff44aa77.toInt() })

    @Test
    fun tapSwipeLongPressKeyTextAndImageContextAreRecordedInOrder() = runBlocking {
        val detached = AtomicBoolean(false)
        val session = TestStepRecordingSession("fixture-device", Closeable { detached.set(true) })
        session.accept(touch(MirrorTouchAction.DOWN, 1, 10, 20), frame(), nowMs = 1_000)
        session.accept(touch(MirrorTouchAction.UP, 1, 10, 20), frame(), nowMs = 1_100)
        session.accept(touch(MirrorTouchAction.DOWN, 2, 10, 20), frame(), nowMs = 2_000)
        session.accept(touch(MirrorTouchAction.MOVE, 2, 80, 160), frame(), nowMs = 2_050)
        session.accept(touch(MirrorTouchAction.UP, 2, 80, 160), frame(), nowMs = 2_100)
        session.accept(touch(MirrorTouchAction.DOWN, 3, 30, 30), frame(), nowMs = 3_000)
        session.accept(touch(MirrorTouchAction.UP, 3, 30, 30), frame(), nowMs = 3_700)
        session.accept(MirrorControlCommand.Key(MirrorKeyAction.DOWN, 66), frame())
        session.accept(MirrorControlCommand.Text("hello fixture"), frame())
        val snapshot = session.stopAndDrain()

        assertFalse(snapshot.active)
        assertEquals(0, snapshot.pendingSnapshots)
        assertTrue(detached.get())
        assertEquals(5, snapshot.steps.size)
        assertTrue(snapshot.steps[0].action.startsWith("Tap at (10%, 10%)"))
        assertTrue(snapshot.steps[1].action.startsWith("Swipe from (10%, 10%) to (80%, 80%)"))
        assertTrue(snapshot.steps[2].action.startsWith("Long press at (30%, 15%)"))
        assertEquals("Press Enter", snapshot.steps[3].action)
        assertEquals("Enter text: hello fixture", snapshot.steps[4].action)
        assertTrue(snapshot.steps.all { "UI hierarchy isn't exposed" in it.screenContext.orEmpty() })
        val screenshot = snapshot.steps.first().screenshotJpeg
        assertNotNull(screenshot)
        val decoded = ImageIO.read(ByteArrayInputStream(screenshot!!))
        assertNotNull(decoded)
        assertTrue(decoded.width <= 1280 && decoded.height <= 1280)
        session.close()
    }

    @Test
    fun unsupportedMultiTouchAndLongDragWarnInsteadOfInventingTaps() {
        val session = TestStepRecordingSession("fixture-device")
        session.accept(touch(MirrorTouchAction.DOWN, 1, 10, 10))
        session.accept(touch(MirrorTouchAction.DOWN, 2, 20, 20))
        session.accept(touch(MirrorTouchAction.UP, 1, 10, 10))
        session.accept(touch(MirrorTouchAction.UP, 2, 20, 20))
        session.accept(touch(MirrorTouchAction.DOWN, 3, 10, 10), nowMs = 10)
        session.accept(touch(MirrorTouchAction.MOVE, 3, 80, 80), nowMs = 20)
        session.accept(touch(MirrorTouchAction.UP, 3, 80, 80), nowMs = 900)
        val snapshot = session.snapshot.value
        assertEquals(1, snapshot.steps.size)
        assertTrue(snapshot.steps.single().action.startsWith("Swipe"))
        assertTrue(snapshot.warnings.any { "Multi-touch" in it })
        assertTrue(snapshot.warnings.any { "long swipe" in it })
        session.close()
    }

    @Test
    fun longTextWarnsAboutTruncationAndImageFreeInputKeepsContext() {
        val session = TestStepRecordingSession("fixture-device")
        session.accept(MirrorControlCommand.Text("x".repeat(240)))
        val snapshot = session.snapshot.value
        assertTrue(snapshot.steps.single().action.contains("first 200 of 240 characters"))
        assertTrue(snapshot.warnings.any { "truncated" in it })
        assertTrue(snapshot.steps.single().screenContext.orEmpty().contains("frame unavailable"))
        session.stop()
        assertTrue(session.updateReviewedSteps(listOf("Reviewed action" to "Visible result")))
        assertEquals("Visible result", session.snapshot.value.steps.single().expected)
        session.stop()
        assertFalse(session.updateReviewedSteps(emptyList()))
        session.close()
    }

    @Test
    fun capturedInputTimeFrameIsContextByDefaultAndOnlyBecomesOracleAfterReview() = runBlocking {
        val session = TestStepRecordingSession("fixture-device")
        session.accept(MirrorControlCommand.Text("open settings"), frame())
        val snapshot = session.stopAndDrain()
        val row = snapshot.steps.single()
        assertNotNull(row.screenshotJpeg)
        assertFalse(session.updateReviewedSteps(listOf("out-of-order row" to "wrong"), expectedRowIds = listOf("recording-row-from-another-session")))
        val defaultStep = session.toTestSteps { "assets/context.jpg" }.single()
        assertTrue(defaultStep.examples.none { it is com.indagium.testing.model.StepExample.GoldenScreenshot })
        val inputContext = defaultStep.examples.filterIsInstance<com.indagium.testing.model.StepExample.ReferenceLog>().single()
        assertTrue(inputContext.caption.contains("before this action"))
        assertTrue(inputContext.text.contains("UI hierarchy isn't exposed"))

        assertTrue(session.updateReviewedSteps(listOf("open settings" to "settings screen is open"), setOf(row.id)))
        val reviewedStep = session.toTestSteps { "assets/context.jpg" }.single()
        assertEquals(1, reviewedStep.examples.filterIsInstance<com.indagium.testing.model.StepExample.GoldenScreenshot>().size)
        assertTrue(
            reviewedStep.examples
                .filterIsInstance<com.indagium.testing.model.StepExample.GoldenScreenshot>()
                .single().caption.contains("captured at input time"),
        )
        session.close()
    }

    @Test
    fun reviewedRowsCannotChangeAfterApplyReservation() {
        val session = TestStepRecordingSession("fixture-device")
        session.accept(MirrorControlCommand.Text("review this"))
        session.stop()
        val before = session.snapshot.value
        assertNotNull(session.freezeReviewedSnapshotForApply())

        assertFalse(session.replaceSteps(before.steps.map { it.copy(action = "changed after approval") }))
        assertFalse(session.updateReviewedSteps(listOf("changed after approval" to "result")))
        assertEquals(before, session.snapshot.value)
        session.releaseApplyReservation()
        assertTrue(session.replaceSteps(before.steps.map { it.copy(action = "edited before approval") }))
        assertEquals("edited before approval", session.snapshot.value.steps.single().action)
        session.close()
    }

    @Test
    fun timedOutDrainDropsQueuedImagesAndLateEncoderCannotPublishOrRevivePendingCount() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = TestStepRecordingSession(
            "fixture-device",
            snapshotEncoder = { started.countDown(); release.await(2, TimeUnit.SECONDS); byteArrayOf(1, 2, 3) },
            drainTimeoutMs = 20,
        )
        session.accept(MirrorControlCommand.Text("first"), frame())
        session.accept(MirrorControlCommand.Text("second"), frame())
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val stopped = session.stopAndDrain()
        assertEquals(0, stopped.pendingSnapshots)
        assertTrue(stopped.warnings.any { "without those images" in it })
        release.countDown()
        kotlinx.coroutines.delay(50)
        assertEquals(0, session.snapshot.value.pendingSnapshots)
        assertTrue(session.snapshot.value.steps.all { it.screenshotJpeg == null })
        session.close()
    }
}
