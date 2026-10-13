package com.indagium.testing

import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.capture.mirror.MirrorGestureControl
import com.indagium.testing.device.GestureMode
import com.indagium.testing.device.GesturePoint
import com.indagium.testing.device.LaneCapture
import com.indagium.testing.device.TestDeviceSession
import com.indagium.testing.device.playMirrorGesture
import com.indagium.testing.device.mirrorGesturePoints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MirrorGestureTest {
    private val points = listOf(GesturePoint(100, 900, 0), GesturePoint(110, 800, 36), GesturePoint(200, 350, 201), GesturePoint(200, 350, 577))
    private fun action(bytes: ByteArray) = bytes[1].toInt()

    @Test
    fun samplesUseAbsoluteDeadlinesOnOneContinuousTouchStream() = runBlocking {
        var timeMs = 0L
        val events = mutableListOf<Pair<Long, ByteArray>>()
        playMirrorGesture(points, 1000, 1000, send = {
            events += timeMs to it
            timeMs += 4 // Sending takes time, which must not accumulate on top of each pause.
            true
        }, nowNanos = { timeMs * 1_000_000 }, pause = { timeMs += it })
        assertEquals(listOf(0L, 40L, 205L, 581L), events.map { it.first })
        assertEquals(listOf(0, 2, 2, 1), events.map { action(it.second) })
        assertTrue(events.all { ByteBuffer.wrap(it.second, 2, 8).long == 1L }, "same pointer as the manual mirror")
    }

    @Test
    fun interruptionCancelsTheHeldTouchWithoutRepeatingTheAction() = runBlocking {
        val events = mutableListOf<ByteArray>()
        assertFailsWith<CancellationException> {
            playMirrorGesture(points, 1000, 1000, { events += it; true }, pause = { throw CancellationException("stop") })
        }
        assertEquals(listOf(MirrorTouchAction.DOWN.wireValue, MirrorTouchAction.CANCEL.wireValue), events.map(::action))
    }

    @Test
    fun aFailedStreamDoesNotStartAnotherGesture() = runBlocking {
        val events = mutableListOf<ByteArray>()
        assertFailsWith<IllegalStateException> {
            playMirrorGesture(points, 1000, 1000, { events += it; events.size == 1 }, nowNanos = { 0L }, pause = {})
        }
        assertEquals(listOf(0, 2, 3), events.map(::action))
    }

    @Test
    fun sessionUsesTheCaptureStreamAndMapsImageCoordinatesWithoutAdbMotionCommands() {
        val runner = ScriptedAdbRunner()
        val dir = kotlin.io.path.createTempDirectory("mirror-gesture").toFile()
        val original = runBlocking { openFixtureSession(runner, dir) }
        try {
            val events = mutableListOf<ByteArray>()
            val capture = object : LaneCapture by original.capture {
                override fun gestureControl() = MirrorGestureControl(540, 1200) { events += it; true }
            }
            val session = TestDeviceSession.forCapture(original.serial, dir, fixtureTools(runner), capture)
            val space = runBlocking { session.screenshot() }.space
            val result = runBlocking { session.gesture(listOf(GesturePoint(0, 0, 0), GesturePoint(space.imageWidth - 1, space.imageHeight - 1, 50))) }
            assertEquals(GestureMode.MIRROR_CONTROL, result.mode)
            assertEquals(listOf(0, 1), events.map(::action))
            assertEquals(539, ByteBuffer.wrap(events.last(), 10, 4).int)
            assertEquals(1199, ByteBuffer.wrap(events.last(), 14, 4).int)
            assertEquals(540, ByteBuffer.wrap(events.last(), 18, 2).short.toInt())
            assertEquals(1200, ByteBuffer.wrap(events.last(), 20, 2).short.toInt())
            assertTrue(runner.shellCommands.isEmpty(), "no separate Android input process per event")
        } finally {
            original.closeBlocking()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aRotatedOrStaleStreamIsRefusedBeforeTouchDown() {
        assertFailsWith<IllegalArgumentException> { mirrorGesturePoints(points, 1000, 2000, 1280, 640) }
    }
}
