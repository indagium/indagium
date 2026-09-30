@file:Suppress("MagicNumber")

package com.indagium.ui

import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExecutable
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureTools
import com.indagium.capture.FakeCaptureRunner
import com.indagium.capture.StreamingFakeProcess
import com.indagium.capture.StreamingMkvWriter
import com.indagium.capture.mirror.EmbeddedDeviceSession
import com.indagium.capture.mirror.EmbeddedMirrorConnection
import com.indagium.capture.mirror.EmbeddedMirrorSnapshot
import com.indagium.capture.mirror.EmbeddedMirrorState
import com.indagium.capture.mirror.EmbeddedMirrorTransport
import com.indagium.capture.mirror.H264Decoder
import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorFrame
import com.indagium.capture.mirror.MirrorStreamOptions
import java.awt.EventQueue
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for the Disconnect -> Connect freeze: a Disconnect on an IO thread held the
 * mirror's lifecycle lock while tearing a native surface down (which needs the EDT), and a Connect
 * clicked on the EDT then blocked on that same lock. See [EmbeddedMirrorHandle]'s threading
 * invariant. A single-thread executor ("fake-edt") stands in for the EDT, so the exact lock cycle is
 * reproduced without any native code or display.
 */
class EmbeddedMirrorLifecycleTest {
    /** Has the real backend's lock shape: stop() holds [lifecycleLock] across a slow detach AND a
     * wait on the (fake) UI thread — exactly what used to deadlock against a UI-thread start(). */
    private class LockingFakeBackend(
        private val fakeEdt: ExecutorService,
        private val detachDelayMs: Long,
    ) : MirrorBackend {
        private val lifecycleLock = Any()
        private val decoderCount = AtomicInteger()

        @Volatile var attached = false
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val closes = AtomicInteger()
        val maxAttachedDecoders = AtomicInteger()
        val stopEntered = CountDownLatch(1)

        override fun snapshot() = EmbeddedMirrorSnapshot(
            if (attached) EmbeddedMirrorState.LIVE else EmbeddedMirrorState.DISCONNECTED,
        )

        override fun isAlreadyStarted(serial: String) = attached

        override fun start(serial: String, options: MirrorStreamOptions) = synchronized(lifecycleLock) {
            if (attached) return@synchronized
            attached = true
            starts.incrementAndGet()
            maxAttachedDecoders.accumulateAndGet(decoderCount.incrementAndGet(), ::maxOf)
            Unit
        }

        override fun stop() = synchronized(lifecycleLock) {
            attached = false
            stops.incrementAndGet()
            stopEntered.countDown()
            Thread.sleep(detachDelayMs) // decoder join with no video
            fakeEdt.submit { }.get() // invokeAndWait for native surface teardown
            decoderCount.set(0)
        }

        override fun send(command: MirrorControlCommand) = false

        override fun close() {
            synchronized(lifecycleLock) {
                attached = false
                closes.incrementAndGet()
            }
        }
    }

    private class Harness(detachDelayMs: Long) : AutoCloseable {
        val root = createTempDirectory("embedded-mirror-lifecycle").toFile()
        val fakeEdt: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "fake-edt").apply { isDaemon = true }
        }
        val backend = LockingFakeBackend(fakeEdt, detachDelayMs)
        private val runner = FakeCaptureRunner().also { it.enqueue(StreamingFakeProcess()) }
        val controller = TabCaptureController(root, runner = runner)
        val app = AppState(
            autosaveFile = Files.createTempFile("embedded-mirror-lifecycle-autosave", "").toFile(),
            autoExportNotes = false,
        )
        val tabId = "t1"

        init {
            app.registerCaptureControllerForTest(tabId, controller)
            controller.start(
                CaptureDevice("SERIAL", "device", "Pixel"),
                CaptureSettings(freeSpaceReserveBytes = 0),
                CaptureTools(CaptureExecutable("adb"), null, runner),
            ) {}
            app.embeddedMirrorHandleFactory = { _, _, _, _ -> EmbeddedMirrorHandle.forBackend(backend) }
        }

        /** Runs [block] the way a Compose click handler does: on the (fake) UI thread, which must
         * come back quickly no matter what a mirror Disconnect is doing. Returns the elapsed ms. */
        fun onUiThread(block: () -> Unit): Long {
            val started = System.nanoTime()
            fakeEdt.submit(block).get(5, TimeUnit.SECONDS)
            return (System.nanoTime() - started) / 1_000_000
        }

        fun connectAndWaitUntilAttached() {
            app.ensureEmbeddedMirror(tabId, autoStart = true)
            awaitCondition(5_000) { backend.attached }
        }

        override fun close() {
            app.close()
            controller.close()
            fakeEdt.shutdownNow()
            root.deleteRecursively()
        }
    }

    @org.junit.Test(timeout = 30_000)
    fun connectClickedOnTheUiThreadWhileDisconnectIsTearingDownDoesNotDeadlock() {
        Harness(detachDelayMs = 1_000).use { h ->
            h.connectAndWaitUntilAttached()

            h.onUiThread { h.app.stopEmbeddedMirror(h.tabId) }
            // The Disconnect is now inside stop(): holding its lifecycle lock, sleeping in the
            // "decoder join", then waiting on the UI thread for the surface teardown.
            assertTrue(h.backend.stopEntered.await(5, TimeUnit.SECONDS))

            // The Connect click lands on the UI thread in exactly that window. Before the fix this
            // blocked on the lock forever (the stop was waiting for this very thread).
            val connectMs = h.onUiThread { h.app.ensureEmbeddedMirror(h.tabId, autoStart = true) }
            assertTrue(connectMs < 500, "Connect on the UI thread must not wait for the Disconnect (took $connectMs ms)")

            // The UI thread stays responsive for the rest of the teardown.
            val pingMs = h.onUiThread { }
            assertTrue(pingMs < 500, "UI thread blocked for $pingMs ms during a Disconnect")

            awaitCondition(10_000) { h.backend.attached }
            assertEquals(1, h.backend.stops.get(), "exactly the one Disconnect ran")
            assertEquals(2, h.backend.starts.get(), "initial Connect plus the re-Connect")
            assertEquals(1, h.backend.maxAttachedDecoders.get(), "two decoders must never be attached")
        }
    }

    @org.junit.Test(timeout = 30_000)
    fun rapidDisconnectConnectDisconnectEndsDisconnected() {
        Harness(detachDelayMs = 300).use { h ->
            h.connectAndWaitUntilAttached()

            val ms = h.onUiThread {
                h.app.stopEmbeddedMirror(h.tabId)
                h.app.ensureEmbeddedMirror(h.tabId, autoStart = true)
                h.app.stopEmbeddedMirror(h.tabId)
            }
            assertTrue(ms < 500, "three clicks on the UI thread took $ms ms")

            val handle = requireNotNull(h.app.embeddedMirrorFor(h.tabId))
            awaitCondition(5_000) { handle.snapshot.value.state == EmbeddedMirrorState.DISCONNECTED }
            Thread.sleep(500) // a wrongly-run Connect would show up as LIVE again
            assertFalse(h.backend.attached, "the LAST click (Disconnect) must win")
            assertEquals(1, h.backend.maxAttachedDecoders.get())
        }
    }

    @org.junit.Test(timeout = 30_000)
    fun rapidDisconnectConnectDisconnectConnectEndsConnected() {
        Harness(detachDelayMs = 300).use { h ->
            h.connectAndWaitUntilAttached()

            h.onUiThread {
                h.app.stopEmbeddedMirror(h.tabId)
                h.app.ensureEmbeddedMirror(h.tabId, autoStart = true)
                h.app.stopEmbeddedMirror(h.tabId)
                h.app.ensureEmbeddedMirror(h.tabId, autoStart = true)
            }

            val handle = requireNotNull(h.app.embeddedMirrorFor(h.tabId))
            awaitCondition(5_000) { handle.snapshot.value.state == EmbeddedMirrorState.LIVE }
            Thread.sleep(500)
            assertTrue(h.backend.attached, "the LAST click (Connect) must win")
            assertEquals(1, h.backend.maxAttachedDecoders.get())
        }
    }

    @org.junit.Test(timeout = 30_000)
    fun closingTheTabWhileAMirrorStopIsInFlightDoesNotBlockTheCaller() {
        Harness(detachDelayMs = 1_500).use { h ->
            h.connectAndWaitUntilAttached()

            h.onUiThread { h.app.stopEmbeddedMirror(h.tabId) }
            assertTrue(h.backend.stopEntered.await(5, TimeUnit.SECONDS))

            // closeTab() is what the tab's close button calls on the UI thread. It used to call
            // handle.close() right there, which waits on the lifecycle lock held by the stop above.
            val closeMs = h.onUiThread { h.app.closeTab(h.tabId) }
            assertTrue(closeMs < 1_000, "closing a tab blocked the UI thread for $closeMs ms behind a mirror stop")
            assertEquals(null, h.app.embeddedMirrorFor(h.tabId), "the handle is unregistered immediately")

            // The close itself still happens, ordered after the in-flight stop.
            awaitCondition(10_000) { h.backend.closes.get() == 1 }
            assertEquals(1, h.backend.stops.get())
        }
    }

    @org.junit.Test(timeout = 30_000)
    fun aSupersededConnectIsSkippedAndACloseIsNeverSkipped() {
        val edt = Executors.newSingleThreadExecutor { Thread(it, "fake-edt").apply { isDaemon = true } }
        try {
            val backend = LockingFakeBackend(edt, detachDelayMs = 200)
            val handle = EmbeddedMirrorHandle.forBackend(backend)
            handle.start("s", MirrorStreamOptions())
            val afterStarts = AtomicInteger()

            // Park the lane inside a running job so the three requests below are all queued behind
            // it (otherwise the first could legitimately run before the later ones are requested).
            val parked = CountDownLatch(1)
            val gate = CountDownLatch(1)
            val first = handle.requestStart("s", MirrorStreamOptions()) {
                parked.countDown()
                gate.await(10, TimeUnit.SECONDS)
            }
            assertTrue(parked.await(5, TimeUnit.SECONDS))
            val stop = handle.requestStop()
            val start = handle.requestStart("s", MirrorStreamOptions()) { afterStarts.incrementAndGet() }
            val close = handle.requestClose()
            gate.countDown()
            first.get(5, TimeUnit.SECONDS)
            stop.get(5, TimeUnit.SECONDS)
            start.get(5, TimeUnit.SECONDS)
            close.get(5, TimeUnit.SECONDS)

            assertEquals(0, backend.stops.get(), "the Disconnect was superseded by the later requests")
            assertEquals(1, backend.starts.get(), "only the original Connect ran")
            assertEquals(0, afterStarts.get(), "a skipped Connect must not run its follow-up")
            assertEquals(1, backend.closes.get(), "close always runs")
            // Anything after a close is a harmless no-op, not an exception.
            handle.requestStart("s", MirrorStreamOptions()).get(5, TimeUnit.SECONDS)
            assertEquals(1, backend.starts.get())
        } finally {
            edt.shutdownNow()
        }
    }

    /** The real [MirrorBackend.SharedRecordingSession] over a real [EmbeddedDeviceSession] whose
     * decoder is slow to close: Disconnect, then Connect during the slow detach. */
    @org.junit.Test(timeout = 30_000)
    fun sharedRecordingSessionSerializesASlowDisconnectWithTheNextConnect() {
        val videoInput = PipedInputStream(4 * 1024)
        val videoOutput = PipedOutputStream(videoInput)
        val transport = EmbeddedMirrorTransport { _, _ ->
            object : EmbeddedMirrorConnection {
                override val videoInput: InputStream = videoInput
                override val audioInput: InputStream? = null

                override fun sendControl(bytes: ByteArray) = Unit

                override fun close() {
                    runCatching { videoInput.close() }
                    runCatching { videoOutput.close() }
                }
            }
        }
        val mkvFile = Files.createTempFile("mirror-lifecycle-shared", ".mkv").toFile().apply { deleteOnExit() }
        val session = EmbeddedDeviceSession(transport, StreamingMkvWriter(mkvFile), elapsedMillis = { 0L })
        val activeDecodes = AtomicInteger()
        val maxActiveDecodes = AtomicInteger()
        val decodeStarts = AtomicInteger()
        val closeEntered = CountDownLatch(1)
        val decoder = object : H264Decoder {
            override fun decode(input: InputStream, onFrame: (MirrorFrame) -> Unit) {
                decodeStarts.incrementAndGet()
                maxActiveDecodes.accumulateAndGet(activeDecodes.incrementAndGet(), ::maxOf)
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        if (input.read() < 0) return
                    }
                } finally {
                    activeDecodes.decrementAndGet()
                }
            }

            override fun close() {
                closeEntered.countDown()
                Thread.sleep(800) // the slow native teardown
            }
        }
        try {
            session.start("serial", MirrorStreamOptions())
            awaitCondition(5_000) { session.connectionSnapshot().state == EmbeddedMirrorState.LIVE }
            val backend = MirrorBackend.SharedRecordingSession(session = session, decoder = decoder, onSnapshotChanged = {})
            val handle = EmbeddedMirrorHandle.forBackend(backend)
            try {
                handle.start("ignored", MirrorStreamOptions())
                awaitCondition(5_000) { backend.snapshot().state == EmbeddedMirrorState.LIVE }

                val disconnect = handle.requestStop()
                assertTrue(closeEntered.await(5, TimeUnit.SECONDS), "the Disconnect must be inside the slow detach")
                val started = System.nanoTime()
                val connect = handle.requestStart("ignored", MirrorStreamOptions())
                assertTrue((System.nanoTime() - started) / 1_000_000 < 200, "requesting a Connect must not block")

                disconnect.get(10, TimeUnit.SECONDS)
                connect.get(10, TimeUnit.SECONDS)
                awaitCondition(5_000) { backend.snapshot().state == EmbeddedMirrorState.LIVE }
                assertEquals(2, decodeStarts.get())
                assertEquals(1, maxActiveDecodes.get(), "two decoders must never be attached at once")

                handle.requestStop().get(10, TimeUnit.SECONDS)
                assertEquals(EmbeddedMirrorState.DISCONNECTED, backend.snapshot().state)
            } finally {
                handle.close()
            }
        } finally {
            session.close()
            mkvFile.delete()
        }
    }

    @org.junit.Test(timeout = 20_000)
    fun runOnEdtBoundedNeverWaitsForeverOnABusyUiThread() {
        assertFalse(EventQueue.isDispatchThread())
        val ran = AtomicBoolean(false)
        assertTrue(runOnEdtBounded(2_000) { ran.set(true) }, "an idle EDT runs the task and the caller sees it finish")
        assertTrue(ran.get())

        val edtBlocked = CountDownLatch(1)
        val releaseEdt = CountDownLatch(1)
        EventQueue.invokeLater {
            edtBlocked.countDown()
            releaseEdt.await(10, TimeUnit.SECONDS)
        }
        assertTrue(edtBlocked.await(5, TimeUnit.SECONDS))

        val lateRan = AtomicBoolean(false)
        val started = System.nanoTime()
        val completed = runOnEdtBounded(200) { lateRan.set(true) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertFalse(completed, "a busy EDT must time the wait out")
        assertTrue(elapsedMs < 2_000, "the bounded wait took $elapsedMs ms")
        assertFalse(lateRan.get())

        releaseEdt.countDown()
        awaitCondition(5_000) { lateRan.get() } // the work was still queued, not dropped
    }

    companion object {
        private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean) {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (!condition()) {
                check(System.nanoTime() < deadline) { "Timed out waiting for condition" }
                Thread.sleep(10)
            }
        }
    }
}
