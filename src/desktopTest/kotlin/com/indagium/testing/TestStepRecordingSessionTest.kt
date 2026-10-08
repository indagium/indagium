@file:Suppress("MagicNumber") // Fixture coordinates, timestamps and sizes, not tunable constants.

package com.indagium.testing

import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorFrame
import com.indagium.capture.mirror.MirrorKeyAction
import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.testing.authoring.RecordedInputKind
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.authoring.recordingApplyBlockedReason
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun unsupportedMultiTouchWarnsAndASlowDragKeepsItsDuration() {
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
        assertTrue(snapshot.warnings.none { "not preserved" in it || "long swipe" in it }, "the old blanket warning is gone")
        assertEquals(890L, snapshot.steps.single().durationMs)
        assertTrue(snapshot.steps.single().action.endsWith("over 890 ms"))
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

    // ── GPU mirror paths: no CPU pixels with the input, so the image comes from adb screencap ──

    private fun screencapPng(): ByteArray = ByteArrayOutputStream().also { out ->
        ImageIO.write(BufferedImage(20, 30, BufferedImage.TYPE_INT_RGB), "png", out)
    }.toByteArray()

    private val noFrameWarning = "No screen frame was available for a recorded input; its step keeps device/screen context only."

    @Test
    fun aFramelessInputReadsItsImageThroughAdbOffTheCallerThread() = runBlocking {
        val calls = AtomicInteger()
        val callerThread = Thread.currentThread()
        val adbThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val session = TestStepRecordingSession(
            "fixture-device",
            screencap = { calls.incrementAndGet(); adbThread.set(Thread.currentThread()); screencapPng() },
        )
        session.accept(MirrorControlCommand.Text("open settings"), frame = null)
        val snapshot = session.stopAndDrain()

        assertEquals(1, calls.get())
        assertTrue(adbThread.get() !== callerThread, "adb must never be read on the thread that delivered the input")
        val row = snapshot.steps.single()
        val image = ImageIO.read(ByteArrayInputStream(assertNotNull(row.screenshotJpeg)))
        assertEquals(20, image.width)
        assertEquals(30, image.height)
        assertTrue(row.screenshotFromAdb)
        assertTrue("adb" in row.screenContext.orEmpty())
        assertTrue(noFrameWarning !in snapshot.warnings)
        assertEquals(0, snapshot.pendingSnapshots)
        assertTrue(session.updateReviewedSteps(listOf("open settings" to "settings"), setOf(row.id)))
        val golden = session.toTestSteps { "assets/adb.jpg" }.single().examples
            .filterIsInstance<com.indagium.testing.model.StepExample.GoldenScreenshot>().single()
        assertTrue("adb" in golden.caption)
        session.close()
    }

    @Test
    fun aFrameFromTheMirrorNeverCallsAdb() = runBlocking {
        val calls = AtomicInteger()
        val session = TestStepRecordingSession("fixture-device", screencap = { calls.incrementAndGet(); screencapPng() })
        session.accept(MirrorControlCommand.Text("has a frame"), frame())
        val snapshot = session.stopAndDrain()

        assertEquals(0, calls.get())
        assertNotNull(snapshot.steps.single().screenshotJpeg)
        assertFalse(snapshot.steps.single().screenshotFromAdb)
        session.close()
    }

    @Test
    fun anAdbFailureKeepsTheStepWithTheExistingWarning() = runBlocking {
        val session = TestStepRecordingSession("fixture-device", screencap = { error("adb offline") })
        session.accept(MirrorControlCommand.Text("first"), frame = null)
        session.accept(MirrorControlCommand.Text("second"), frame = null)
        val snapshot = session.stopAndDrain()

        assertEquals(2, snapshot.steps.size)
        assertTrue(snapshot.steps.all { it.screenshotJpeg == null })
        assertTrue(noFrameWarning in snapshot.warnings)
        assertEquals(0, snapshot.pendingSnapshots)
        session.close()
    }

    @Test
    fun anUnreadableScreencapIsAnImageFreeStepNotACrash() = runBlocking {
        val session = TestStepRecordingSession("fixture-device", screencap = { byteArrayOf(1, 2, 3) })
        session.accept(MirrorControlCommand.Text("garbage"), frame = null)
        val snapshot = session.stopAndDrain()

        assertNull(snapshot.steps.single().screenshotJpeg)
        assertTrue(noFrameWarning in snapshot.warnings)
        session.close()
    }

    @Test
    fun adbReadsShareTheScreenshotQueueLimit() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val session = TestStepRecordingSession(
            "fixture-device",
            screencap = { calls.incrementAndGet(); started.countDown(); release.await(5, TimeUnit.SECONDS); screencapPng() },
        )
        repeat(6) { session.accept(MirrorControlCommand.Text("input $it"), frame = null) }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val queued = session.snapshot.value
        assertEquals(6, queued.steps.size)
        assertEquals(3, queued.pendingSnapshots)
        assertTrue(queued.warnings.any { "queue is full" in it })
        release.countDown()
        val drained = session.stopAndDrain()
        assertEquals(3, calls.get(), "inputs beyond the queue limit are recorded without any adb read")
        assertEquals(3, drained.steps.count { it.screenshotJpeg != null })
        session.close()
    }

    // ── Swipe duration and path ──

    private fun swipe(session: TestStepRecordingSession, downAt: Long, upAt: Long, moves: Int = 1) {
        session.accept(touch(MirrorTouchAction.DOWN, 1, 10, 20), nowMs = downAt)
        repeat(moves) { session.accept(touch(MirrorTouchAction.MOVE, 1, 30 + it % 60, 60 + it % 100), nowMs = downAt + 1) }
        session.accept(touch(MirrorTouchAction.UP, 1, 90, 180), frame(), nowMs = upAt)
    }

    @Test
    fun aSwipeKeepsItsDurationInTheActionAndTheRow() {
        val session = TestStepRecordingSession("fixture-device")
        swipe(session, downAt = 1_000, upAt = 2_200)
        val row = session.snapshot.value.steps.single()
        assertEquals("Swipe from (10%, 10%) to (90%, 90%) over 1200 ms", row.action)
        assertEquals(1_200L, row.durationMs)
        assertEquals(RecordedInputKind.SWIPE, row.kind)
        assertTrue(session.snapshot.value.warnings.isEmpty())
        session.close()
    }

    @Test
    fun aDragAboveTheLaneLimitIsClampedWithAPreciseWarning() {
        val session = TestStepRecordingSession("fixture-device")
        swipe(session, downAt = 1_000, upAt = 4_400)
        val snapshot = session.snapshot.value
        val row = snapshot.steps.single()
        assertEquals(2_000L, row.durationMs)
        assertTrue(row.action.endsWith("over 2000 ms"))
        assertEquals(listOf("Step 1: recorded drag took 3400 ms; replay uses 2000 ms."), snapshot.warnings)
        session.close()
    }

    @Test
    fun aVeryShortSwipeClampsToTheLaneMinimumWithoutAWarning() {
        val session = TestStepRecordingSession("fixture-device")
        swipe(session, downAt = 1_000, upAt = 1_020)
        val snapshot = session.snapshot.value
        assertEquals(50L, snapshot.steps.single().durationMs)
        assertTrue(snapshot.steps.single().action.endsWith("over 50 ms"))
        assertTrue(snapshot.warnings.isEmpty())
        session.close()
    }

    @Test
    fun gesturePathSamplesStayBoundedAndKeepStartAndEnd() {
        val session = TestStepRecordingSession("fixture-device")
        swipe(session, downAt = 1_000, upAt = 1_500, moves = 5_000)
        val path = session.snapshot.value.steps.single().gesturePath
        assertTrue(path.size in 3..10, "path had ${path.size} points")
        assertEquals(10 to 20, path.first().x to path.first().y)
        assertEquals(90 to 180, path.last().x to path.last().y)
        assertTrue(path.all { it.screenWidth == 100 && it.screenHeight == 200 })
        session.close()
    }

    @Test
    fun tapsAndKeysCarryTheirKindDurationAndPoint() {
        val session = TestStepRecordingSession("fixture-device")
        session.accept(touch(MirrorTouchAction.DOWN, 1, 50, 100), nowMs = 1_000)
        session.accept(touch(MirrorTouchAction.UP, 1, 50, 100), nowMs = 1_090)
        session.accept(MirrorControlCommand.Key(MirrorKeyAction.DOWN, 4), nowMs = 2_000)
        session.accept(MirrorControlCommand.Back(MirrorKeyAction.UP), nowMs = 3_000)
        val steps = session.snapshot.value.steps
        assertEquals(RecordedInputKind.TAP, steps[0].kind)
        assertEquals(90L, steps[0].durationMs)
        assertEquals(listOf(50 to 100), steps[0].gesturePath.map { it.x to it.y })
        assertEquals(RecordedInputKind.BACK, steps[1].kind)
        assertEquals(RecordedInputKind.BACK, steps[2].kind)
        assertEquals(listOf(1_090L, 2_000L, 3_000L), steps.map { it.inputAtMs })
        assertEquals(listOf(1_000L, 2_000L, 3_000L), steps.map { it.inputStartMs })
        session.close()
    }

    // ── Review hints ──

    @Test
    fun applyIsExplainedByTheStepsStillMissingExpected() {
        val session = TestStepRecordingSession("fixture-device")
        repeat(8) { session.accept(MirrorControlCommand.Text("input $it"), nowMs = 1_000L + it) }
        session.stop()
        assertEquals("Fill in Expected for steps 1, 2, 3, 4, 5, 6 and 2 more.", recordingApplyBlockedReason(session.snapshot.value))
        assertTrue(session.updateReviewedSteps(List(8) { "input $it" to if (it == 1 || it == 4) "" else "done" }))
        assertEquals("Fill in Expected for steps 2, 5.", recordingApplyBlockedReason(session.snapshot.value))
        assertTrue(session.updateReviewedSteps(List(8) { "input $it" to "done" }))
        assertNull(recordingApplyBlockedReason(session.snapshot.value))
        session.close()
    }
}
