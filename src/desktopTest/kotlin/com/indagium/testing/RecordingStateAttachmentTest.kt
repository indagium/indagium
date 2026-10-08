@file:Suppress("MagicNumber") // Fixture coordinates, timestamps and sizes, not tunable constants.

package com.indagium.testing

import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorKeyAction
import com.indagium.capture.mirror.MirrorTouchAction
import com.indagium.testing.authoring.RecordingScreenState
import com.indagium.testing.authoring.TestStepRecordingSession
import com.indagium.testing.authoring.contextHint
import com.indagium.testing.device.UiNode
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Fake clocks: every probe state and input time below is a literal millisecond value, so the before/after rule is checked
// without a device and without depending on how fast the probe worker happens to run.
class RecordingStateAttachmentTest {
    // ── The pure rule ──

    private fun at(ms: Long) = com.indagium.testing.authoring.InputWindow(ms, ms)

    private fun probe(seq: Int, start: Long, finish: Long) = com.indagium.testing.authoring.ProbeWindow(seq, start, finish)

    private fun attach(inputs: List<com.indagium.testing.authoring.InputWindow>, vararg probes: com.indagium.testing.authoring.ProbeWindow) =
        com.indagium.testing.authoring.attachScreenStates(inputs, probes.toList()).map { it.beforeSeq to it.afterSeq }

    @Test
    fun theStartProbeIsOnlyTheFirstInputsBeforeState() {
        assertEquals(listOf(1 to null, null to null), attach(listOf(at(100), at(200)), probe(1, 0, 50)))
    }

    @Test
    fun aProbeAfterAnInputIsItsAfterStateAndTheNextInputsBeforeState() {
        val inputs = listOf(at(100), at(200), at(300))
        assertEquals(
            listOf(null to 2, 2 to null, null to null),
            attach(inputs, probe(2, 150, 180)),
        )
    }

    @Test
    fun anInputThatBeginsWhileAProbeIsReadingHasNoKnownBeforeState() {
        assertEquals(listOf(null to 2, null to null), attach(listOf(at(100), at(200)), probe(2, 150, 250)))
        assertEquals(listOf(null to 2, null to null), attach(listOf(at(100), at(200)), probe(2, 150, 200)), "touching is not after")
    }

    @Test
    fun aProbeThatStartedMidGestureAttachesToNothing() {
        val inputs = listOf(com.indagium.testing.authoring.InputWindow(100, 900), at(1_000))
        assertEquals(listOf(null to null, null to null), attach(inputs, probe(2, 500, 520)))
    }

    @Test
    fun theLatestStartedProbeWinsWhenSeveralFollowTheSameInput() {
        assertEquals(listOf(null to 3), attach(listOf(at(100)), probe(2, 200, 220), probe(3, 400, 420)))
    }

    @Test
    fun aGestureUsesItsStartForBeforeAndItsEndForAfter() {
        val swipe = com.indagium.testing.authoring.InputWindow(1_000, 2_200)
        // Finished before the swipe began: its before state, even though the swipe ends much later.
        assertEquals(listOf(1 to null), attach(listOf(swipe), probe(1, 0, 999)))
        // Still reading when the swipe began: unknown.
        assertEquals(listOf(null to null), attach(listOf(swipe), probe(1, 0, 1_000)))
        // Read after the swipe ended: its after state, and the next input's before state.
        assertEquals(listOf(null to 2, 2 to null), attach(listOf(swipe, at(5_000)), probe(2, 2_500, 4_000)))
    }

    // ── Through the session ──

    private fun touch(action: MirrorTouchAction, x: Int, y: Int) = MirrorControlCommand.Touch(
        action = action, pointerId = 1, x = x, y = y, screenWidth = 100, screenHeight = 200,
    )

    private fun node(l: Int, t: Int, r: Int, b: Int, text: String = "", cls: String = "TextView", password: Boolean = false, focused: Boolean = false) =
        UiNode(l, t, r, b, text, "", "", cls, clickable = true, enabled = true, scrollable = false, "com.example.shop", password, focused)

    private fun shopState(start: Long, finish: Long, vararg extra: UiNode) = RecordingScreenState(
        0, start, finish, "com.example.shop", ".Main", listOf(node(200, 150, 400, 250, "Search")) + extra, 1000, 2000,
    )

    /** Returns [states] in call order (the last one repeats) and records how the worker used it. */
    private class ScriptedProbe(private val states: List<RecordingScreenState?>, private val gate: CountDownLatch? = null) {
        val calls = AtomicInteger()
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()
        val thread = AtomicReference<Thread>()

        fun read(): RecordingScreenState? {
            thread.set(Thread.currentThread())
            val index = calls.getAndIncrement()
            maxRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            try {
                if (index == 0) gate?.await(10, TimeUnit.SECONDS)
                return states[minOf(index, states.lastIndex)]
            } finally {
                running.decrementAndGet()
            }
        }
    }

    private fun awaitState(session: TestStepRecordingSession, seq: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (session.screenState(seq) == null && System.nanoTime() < deadline) Thread.sleep(5)
        assertNotNull(session.screenState(seq), "probe state $seq never arrived")
    }

    private fun session(probe: ScriptedProbe, settleMs: Long = 60_000L, drainMs: Long = 5_000L) =
        TestStepRecordingSession("fixture-device", screenProbe = probe::read, settleDelayMs = settleMs, probeDrainTimeoutMs = drainMs)

    @Test
    fun startAndFinalProbesAttachToTheRowsAroundThem() = runBlocking {
        val probe = ScriptedProbe(listOf(shopState(0, 50), shopState(10_000, 10_100)))
        val session = session(probe)
        awaitState(session, 1)
        session.accept(touch(MirrorTouchAction.DOWN, 30, 20), nowMs = 1_000)
        session.accept(touch(MirrorTouchAction.UP, 30, 20), nowMs = 1_050)
        session.accept(MirrorControlCommand.Key(MirrorKeyAction.DOWN, 66), nowMs = 3_000)
        val snapshot = session.stopAndDrain()

        assertEquals(2, probe.calls.get(), "the start probe and one probe after the last input")
        val (tap, enter) = snapshot.steps
        assertEquals(1, tap.before?.seq)
        assertEquals("com.example.shop", tap.before?.packageName)
        assertNull(tap.after, "no probe ran between the two inputs")
        assertEquals("Search", tap.tappedElement?.text)
        assertEquals("on 'Search' in com.example.shop", tap.contextHint())
        assertNull(enter.before, "never guessed")
        assertEquals(2, enter.after?.seq)
        assertEquals(".Main", enter.after?.activity)
        assertNull(enter.tappedElement)
        assertEquals("in com.example.shop", enter.contextHint())
        assertEquals(0, snapshot.pendingSnapshots)
        session.close()
    }

    @Test
    fun anInputDuringTheStartProbeLeavesItsBeforeStateUnknown() = runBlocking {
        val probe = ScriptedProbe(listOf(shopState(0, 2_000)))
        val session = session(probe)
        awaitState(session, 1)
        session.accept(touch(MirrorTouchAction.DOWN, 30, 20), nowMs = 1_500)
        session.accept(touch(MirrorTouchAction.UP, 30, 20), nowMs = 1_560)
        val row = session.stopAndDrain().steps.single()
        assertNull(row.before)
        assertNull(row.tappedElement)
        session.close()
    }

    @Test
    fun aNewInputReplacesAQueuedProbeSoAtMostOneRunsAndOneWaits() = runBlocking {
        val gate = CountDownLatch(1)
        val probe = ScriptedProbe(listOf(shopState(0, 50), shopState(10_000, 10_100)), gate)
        val session = session(probe, settleMs = 10L)
        val accepted = Thread { repeat(6) { session.accept(MirrorControlCommand.Text("input $it"), nowMs = 1_000L + it) } }
        accepted.start()
        accepted.join(5_000)
        assertFalse(accepted.isAlive, "input delivery must never wait on a running probe")
        assertEquals(6, session.snapshot.value.steps.size)
        assertEquals(1, session.snapshot.value.pendingSnapshots, "the running probe and its single queued successor count once")
        gate.countDown()
        session.stopAndDrain()
        assertEquals(2, probe.calls.get(), "six inputs while one probe ran collapse into one follow-up probe")
        assertEquals(1, probe.maxRunning.get())
        session.close()
    }

    @Test
    fun textTypedIntoAPasswordFieldIsNeverStoredOrShown() = runBlocking {
        val probe = ScriptedProbe(listOf(shopState(0, 50, node(100, 400, 900, 500, cls = "EditText", password = true, focused = true))))
        val session = session(probe)
        awaitState(session, 1)
        session.accept(MirrorControlCommand.Text("hunter2"), nowMs = 1_000)
        session.accept(MirrorControlCommand.Clipboard("hunter3", paste = true), nowMs = 2_000)
        val live = session.snapshot.value
        assertEquals(listOf("Enter text: ••••", "Paste text: ••••"), live.steps.map { it.action }, "masked before the row is first published")
        val snapshot = session.stopAndDrain()
        assertTrue(snapshot.warnings.any { "Step 1" in it && "password" in it })
        assertTrue(snapshot.warnings.any { "Step 2" in it && "password" in it })
        assertTrue(snapshot.steps.none { "hunter" in it.action + it.screenContext.orEmpty() })
        assertTrue(snapshot.warnings.none { "hunter" in it })
        session.close()
    }

    @Test
    fun aPasswordFieldSeenOnlyAfterTheTextIsStillHiddenRetroactively() = runBlocking {
        val probe = ScriptedProbe(
            listOf(shopState(0, 50), shopState(10_000, 10_100, node(100, 400, 900, 500, cls = "EditText", password = true, focused = true))),
        )
        val session = session(probe)
        awaitState(session, 1)
        session.accept(MirrorControlCommand.Text("hunter2"), nowMs = 1_000)
        assertEquals("Enter text: hunter2", session.snapshot.value.steps.single().action, "nothing known yet")
        val snapshot = session.stopAndDrain()
        assertEquals("Enter text: ••••", snapshot.steps.single().action)
        assertTrue(snapshot.warnings.any { "password" in it })
        session.close()
    }

    @Test
    fun aWholeTypingRunIsHiddenButTextBeforeAFocusChangeIsNot() = runBlocking {
        val probe = ScriptedProbe(
            listOf(shopState(0, 50), shopState(10_000, 10_100, node(100, 400, 900, 500, cls = "EditText", password = true, focused = true))),
        )
        val session = session(probe)
        awaitState(session, 1)
        session.accept(MirrorControlCommand.Text("user"), nowMs = 1_000)
        session.accept(touch(MirrorTouchAction.DOWN, 30, 20), nowMs = 2_000)
        session.accept(touch(MirrorTouchAction.UP, 30, 20), nowMs = 2_050)
        "pw!".forEachIndexed { i, c -> session.accept(MirrorControlCommand.Text(c.toString()), nowMs = 3_000L + i) }
        session.accept(MirrorControlCommand.Key(MirrorKeyAction.DOWN, 67), nowMs = 3_100)
        session.accept(MirrorControlCommand.Text("x"), nowMs = 3_200)
        val snapshot = session.stopAndDrain()
        assertEquals(
            listOf("Enter text: user", "Tap") + List(3) { "Enter text: ••••" } + listOf("Press Delete", "Enter text: ••••"),
            snapshot.steps.map { if (it.action.startsWith("Tap")) "Tap" else it.action },
        )
        assertTrue(snapshot.warnings.any { "Steps 3-5, 7" in it })
        session.close()
    }

    @Test
    fun ordinaryTextIsKeptWhenTheFocusedFieldIsNotAPassword() = runBlocking {
        val probe = ScriptedProbe(
            listOf(shopState(0, 50, node(100, 400, 900, 500, cls = "EditText", focused = true), node(100, 600, 900, 700, cls = "EditText", password = true))),
        )
        val session = session(probe)
        awaitState(session, 1)
        session.accept(MirrorControlCommand.Text("lofi beats"), nowMs = 1_000)
        val snapshot = session.stopAndDrain()
        assertEquals("Enter text: lofi beats", snapshot.steps.single().action)
        assertTrue(snapshot.warnings.none { "password" in it })
        session.close()
    }

    @Test
    fun aFailingProbeWarnsOnceAndKeepsRecording() = runBlocking {
        val probe = ScriptedProbe(listOf(null))
        val session = session(probe)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (probe.calls.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
        session.accept(MirrorControlCommand.Text("still recorded"), nowMs = 1_000)
        val snapshot = session.stopAndDrain()
        assertEquals(1, snapshot.steps.size)
        assertEquals(1, snapshot.warnings.count { "could not be read through adb" in it })
        assertNull(snapshot.steps.single().before)
        session.close()
    }

    @Test
    fun stoppingAndClosingShutDownTheProbeWorkerWithoutLeakingAThread() = runBlocking {
        val probe = ScriptedProbe(listOf(shopState(0, 50)))
        val session = session(probe)
        awaitState(session, 1)
        val worker = assertNotNull(probe.thread.get())
        session.accept(MirrorControlCommand.Text("one"), nowMs = 1_000)
        session.stopAndDrain()
        worker.join(5_000)
        assertFalse(worker.isAlive, "the probe thread must end once the recording is stopped and drained")
        assertTrue(worker.isDaemon)
        session.close()
    }

    @Test
    fun aHungProbeIsAbandonedAfterTheDrainTimeoutAndItsLateResultIsIgnored() = runBlocking {
        val gate = CountDownLatch(1)
        val probe = ScriptedProbe(listOf(shopState(0, 50)), gate)
        val session = session(probe, settleMs = 0L, drainMs = 50L)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (probe.calls.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
        val snapshot = session.stopAndDrain()
        assertEquals(0, snapshot.pendingSnapshots)
        assertTrue(snapshot.warnings.any { "could not be read before recording stopped" in it })
        gate.countDown()
        probe.thread.get()?.join(5_000)
        assertNull(session.screenState(1))
        session.close()
    }
}
