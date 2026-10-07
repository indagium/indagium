@file:Suppress("MagicNumber")

package com.indagium.ui

import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExecutable
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureTools
import com.indagium.capture.FakeCaptureRunner
import com.indagium.capture.StreamingFakeProcess
import com.indagium.capture.StreamingMkvWriter
import com.indagium.capture.mirror.BoundedScrcpyPacketFeed
import com.indagium.capture.mirror.DirectH264Decoder
import com.indagium.capture.mirror.EmbeddedDeviceSession
import com.indagium.capture.mirror.EmbeddedMirrorConnection
import com.indagium.capture.mirror.EmbeddedMirrorSnapshot
import com.indagium.capture.mirror.EmbeddedMirrorState
import com.indagium.capture.mirror.EmbeddedMirrorTransport
import com.indagium.capture.mirror.H264Decoder
import com.indagium.capture.mirror.MirrorControlCommand
import com.indagium.capture.mirror.MirrorFrame
import com.indagium.capture.mirror.MirrorFrameInfo
import com.indagium.capture.mirror.MirrorStreamOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.EventQueue
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
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
        private val closeDelayMs: Long = 0,
        /** Models a native surface: its close needs the real EDT ([runOnEdtBounded]), like
         * [EmbeddedMirrorMacSurface.close], and is idempotent with the same already-closed fast path. */
        private val hasEdtSurface: Boolean = false,
    ) : MirrorBackend {
        private val lifecycleLock = Any()
        private val decoderCount = AtomicInteger()

        @Volatile var attached = false

        @Volatile var snapshotSerial: String? = null

        @Volatile var acceptsInput = false
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val closes = AtomicInteger()
        val maxAttachedDecoders = AtomicInteger()
        val stopEntered = CountDownLatch(1)
        val startsAfterClose = AtomicInteger()
        val liveAudioEnables = AtomicInteger()
        val surfaceCloseTimeouts = AtomicInteger()

        @Volatile var surfaceClosed = false

        @Volatile var closeFinished = false

        @Volatile var onCloseFinished: () -> Unit = {}

        override fun snapshot() = EmbeddedMirrorSnapshot(
            if (attached) EmbeddedMirrorState.LIVE else EmbeddedMirrorState.DISCONNECTED,
            deviceSerial = snapshotSerial,
        )

        override fun isAlreadyStarted(serial: String) = attached

        override fun start(serial: String, options: MirrorStreamOptions) = synchronized(lifecycleLock) {
            if (closeFinished) startsAfterClose.incrementAndGet()
            if (attached) return@synchronized
            attached = true
            snapshotSerial = serial
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

        override fun send(command: MirrorControlCommand) = acceptsInput

        override fun setLiveAudioEnabled(enabled: Boolean, volume: () -> Float, onDiagnostic: (String) -> Unit) {
            if (enabled) liveAudioEnables.incrementAndGet()
        }

        override fun closeNativeSurfaces() {
            if (!hasEdtSurface || surfaceClosed) return
            if (!runOnEdtBounded(EDT_CLOSE_WAIT_MS) { surfaceClosed = true }) surfaceCloseTimeouts.incrementAndGet()
        }

        override fun close() {
            synchronized(lifecycleLock) {
                attached = false
                closes.incrementAndGet()
                closeNativeSurfaces()
                if (closeDelayMs > 0) Thread.sleep(closeDelayMs) // adb/scrcpy cleanup, decoder join
                closeFinished = true
                onCloseFinished()
            }
        }
    }

    private class Harness(detachDelayMs: Long, closeDelayMs: Long = 0, hasEdtSurface: Boolean = false) : AutoCloseable {
        val root = createTempDirectory("embedded-mirror-lifecycle").toFile()
        val fakeEdt: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "fake-edt").apply { isDaemon = true }
        }
        val backend = LockingFakeBackend(fakeEdt, detachDelayMs, closeDelayMs, hasEdtSurface)
        val recorderProcess = StreamingFakeProcess()
        private val runner = FakeCaptureRunner().also { it.enqueue(recorderProcess) }
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
            // The mirror was already LIVE before the clicks, so "LIVE" alone proves nothing: the last
            // Connect supersedes the Disconnects and must really tear down and re-attach (starts == 2).
            // 15 s, not 5 s: this failed once only when the whole capture/mirror suite ran in
            // parallel; under that load the 300 ms detach + lane hops can exceed a tight bound.
            awaitCondition(15_000) { h.backend.starts.get() == 2 }
            awaitCondition(15_000) { handle.snapshot.value.state == EmbeddedMirrorState.LIVE }
            Thread.sleep(300)
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

    @org.junit.Test(timeout = 15_000)
    fun acceptedInputUsesCurrentSnapshotIdentityWhenSharedBackendStartIsAlreadyLive() {
        val edt = Executors.newSingleThreadExecutor { Thread(it, "fake-edt").apply { isDaemon = true } }
        val backend = LockingFakeBackend(edt, detachDelayMs = 0).apply {
            attached = true
            snapshotSerial = "current-device"
            acceptsInput = true
        }
        val handle = EmbeddedMirrorHandle.forBackend(backend)
        val currentInput = CountDownLatch(1)
        val staleInput = AtomicInteger()
        val current = MirrorInputObservers.observe("current-device") { currentInput.countDown() }
        val stale = MirrorInputObservers.observe("requested-stale") { staleInput.incrementAndGet() }
        try {
            // The shared backend reports an already-live stream, so start does not replace its snapshot.
            handle.start("requested-stale", MirrorStreamOptions())
            assertTrue(handle.send(MirrorControlCommand.Text("synthetic accepted input")))
            assertTrue(currentInput.await(2, TimeUnit.SECONDS), "the current device receives the observer event")
            assertEquals(0, staleInput.get(), "the stale requested serial receives nothing")
        } finally {
            current.close()
            stale.close()
            handle.close()
            edt.shutdownNow()
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

    // ---- Close from the real EDT (quit / tab close) ------------------------------------------

    /** Runs [block] on the REAL EDT (the one runOnEdtBounded targets) and returns its elapsed ms. */
    private fun onRealEdt(block: () -> Unit): Long {
        var elapsedMs = 0L
        EventQueue.invokeAndWait {
            val started = System.nanoTime()
            block()
            elapsedMs = (System.nanoTime() - started) / 1_000_000
        }
        return elapsedMs
    }

    @org.junit.Test(timeout = 30_000)
    fun closeCalledOnTheEdtTearsTheSurfaceDownInlineAndDoesNotWaitOnTheLane() {
        val fakeEdt = Executors.newSingleThreadExecutor { Thread(it, "fake-edt").apply { isDaemon = true } }
        try {
            // The surface close needs the EDT, and the lane's remaining close work takes 1 s.
            val backend = LockingFakeBackend(fakeEdt, detachDelayMs = 0, closeDelayMs = 1_000, hasEdtSurface = true)
            val handle = EmbeddedMirrorHandle.forBackend(backend)
            handle.start("s", MirrorStreamOptions())

            val ms = onRealEdt { handle.close() }
            assertTrue(ms < 500, "close() on the EDT took $ms ms; it must not wait for the lane")
            assertTrue(backend.surfaceClosed, "the surface must already be closed when close() returns on the EDT")
            awaitCondition(10_000) { backend.closeFinished }
            assertEquals(0, backend.surfaceCloseTimeouts.get(), "no surface close may time out waiting for the EDT")
            assertEquals(1, backend.closes.get())
        } finally {
            fakeEdt.shutdownNow()
        }
    }

    @org.junit.Test(timeout = 30_000)
    fun theEdtCanWaitForARequestedCloseWithoutTheLaneNeedingTheEdt() {
        val fakeEdt = Executors.newSingleThreadExecutor { Thread(it, "fake-edt").apply { isDaemon = true } }
        try {
            val backend = LockingFakeBackend(fakeEdt, detachDelayMs = 0, closeDelayMs = 200, hasEdtSurface = true)
            val handle = EmbeddedMirrorHandle.forBackend(backend)
            handle.start("s", MirrorStreamOptions())

            // What AppState.stopAllLiveCaptures does at quit: requestClose, then a bounded wait, all on
            // the EDT. Before the fix the lane's surface close queued behind this very wait and timed
            // out after EDT_CLOSE_WAIT_MS (1.5 s) per mirror.
            val ms = onRealEdt { handle.requestClose().get(5, TimeUnit.SECONDS) }
            assertTrue(ms < 1_000, "waiting for a close on the EDT took $ms ms")
            assertTrue(backend.surfaceClosed)
            assertEquals(0, backend.surfaceCloseTimeouts.get())
            assertTrue(backend.closeFinished)
        } finally {
            fakeEdt.shutdownNow()
        }
    }

    @org.junit.Test(timeout = 30_000)
    fun quittingTheAppOnTheEdtDoesNotFreezeForTheMirrorCloseTimeout() {
        Harness(detachDelayMs = 0, closeDelayMs = 100, hasEdtSurface = true).use { h ->
            h.connectAndWaitUntilAttached()

            val ms = onRealEdt { h.app.close() }
            assertTrue(ms < 1_000, "AppState.close() on the EDT froze the window for $ms ms")
            assertTrue(h.backend.surfaceClosed)
            assertEquals(0, h.backend.surfaceCloseTimeouts.get())
            assertEquals(1, h.backend.closes.get())
            assertFalse(h.recorderProcess.isAlive, "the recorder is stopped after the mirror close")
        }
    }

    // ---- start racing close -------------------------------------------------------------------

    @org.junit.Test(timeout = 30_000)
    fun aStartRacingABlockingCloseIsNeverRunAfterTheClose() {
        val edt = Executors.newSingleThreadExecutor { Thread(it, "fake-edt").apply { isDaemon = true } }
        try {
            val backend = LockingFakeBackend(edt, detachDelayMs = 0, closeDelayMs = 50)
            val handle = EmbeddedMirrorHandle.forBackend(backend)
            handle.start("s", MirrorStreamOptions())
            handle.stop()

            val parked = CountDownLatch(1)
            val gate = CountDownLatch(1)
            val first = handle.requestStart("s", MirrorStreamOptions()) {
                parked.countDown()
                gate.await(10, TimeUnit.SECONDS)
            }
            assertTrue(parked.await(5, TimeUnit.SECONDS))
            // A start that is already queued (it passed no check yet) when the blocking close arrives
            // from another thread, plus more starts racing in afterwards.
            val queuedStart = handle.requestStart("s", MirrorStreamOptions())
            val closer = Thread { handle.close() }.apply { isDaemon = true; start() }
            Thread.sleep(100) // the close is now requested and waiting behind the parked job
            val lateStart = handle.requestStart("s", MirrorStreamOptions())
            gate.countDown()
            closer.join(10_000)
            assertFalse(closer.isAlive, "the blocking close must return")
            first.get(5, TimeUnit.SECONDS)
            queuedStart.get(5, TimeUnit.SECONDS)
            lateStart.get(5, TimeUnit.SECONDS)

            assertEquals(1, backend.closes.get())
            handle.start("s", MirrorStreamOptions()) // a blocking start after close is a no-op, not an error
            handle.requestStart("s", MirrorStreamOptions()).get(5, TimeUnit.SECONDS)
            assertEquals(0, backend.startsAfterClose.get(), "no start may reach the backend after the close")
            assertFalse(backend.attached)
        } finally {
            edt.shutdownNow()
        }
    }

    @org.junit.Test(timeout = 30_000)
    fun aStandaloneRuntimeNeverOpensANewConnectionAfterClose() {
        val opens = AtomicInteger()
        val transport = EmbeddedMirrorTransport { _, _ ->
            opens.incrementAndGet()
            object : EmbeddedMirrorConnection {
                override val videoInput: InputStream = InputStream.nullInputStream()
                override val audioInput: InputStream? = null

                override fun sendControl(bytes: ByteArray) = Unit

                override fun close() = Unit
            }
        }
        val decoder = object : H264Decoder {
            override fun decode(input: InputStream, onFrame: (MirrorFrame) -> Unit) {
                while (!Thread.currentThread().isInterrupted) if (input.read() < 0) return
            }
        }
        val handle = EmbeddedMirrorHandle.createAroundRuntime { listener ->
            com.indagium.capture.mirror.EmbeddedMirrorRuntime(transport, decoder, listener = listener)
        }
        handle.close()
        handle.start("serial", MirrorStreamOptions())
        handle.requestStart("serial", MirrorStreamOptions()).get(5, TimeUnit.SECONDS)
        Thread.sleep(200)
        assertEquals(0, opens.get(), "a start after close must not open an adb forward / scrcpy server")
        assertEquals(EmbeddedMirrorState.DISCONNECTED, handle.snapshot.value.state)
    }

    // ---- Disconnect -> Connect on a busy lane ---------------------------------------------------

    @org.junit.Test(timeout = 30_000)
    fun disconnectThenConnectQueuedBehindASlowJobIsARealReconnect() {
        val edt = Executors.newSingleThreadExecutor { Thread(it, "fake-edt").apply { isDaemon = true } }
        try {
            val backend = LockingFakeBackend(edt, detachDelayMs = 50)
            val handle = EmbeddedMirrorHandle.forBackend(backend)
            handle.start("s", MirrorStreamOptions())
            assertEquals(1, backend.starts.get())

            val parked = CountDownLatch(1)
            val gate = CountDownLatch(1)
            val slow = handle.requestStart("s", MirrorStreamOptions()) {
                parked.countDown()
                gate.await(10, TimeUnit.SECONDS)
            }
            assertTrue(parked.await(5, TimeUnit.SECONDS))
            val afterReconnect = AtomicInteger()
            val stop = handle.requestStop()
            val start = handle.requestStart("s", MirrorStreamOptions()) { afterReconnect.incrementAndGet() }
            gate.countDown()
            slow.get(5, TimeUnit.SECONDS)
            stop.get(5, TimeUnit.SECONDS)
            start.get(5, TimeUnit.SECONDS)

            assertEquals(1, backend.stops.get(), "the Disconnect must really tear the stream down")
            assertEquals(2, backend.starts.get(), "the Connect must really re-attach the decoder (once)")
            assertTrue(backend.attached)
            assertEquals(1, backend.maxAttachedDecoders.get(), "never two decoders at once")
            assertEquals(1, afterReconnect.get())

            // And a plain Connect on an already attached mirror stays a no-op (no spurious restart).
            handle.requestStart("s", MirrorStreamOptions()).get(5, TimeUnit.SECONDS)
            assertEquals(1, backend.stops.get())
            assertEquals(2, backend.starts.get())
        } finally {
            edt.shutdownNow()
        }
    }

    @org.junit.Test(timeout = 30_000)
    fun disconnectConnectDisconnectOnABusyLaneEndsDisconnectedWithoutAFlashOfConnect() {
        val edt = Executors.newSingleThreadExecutor { Thread(it, "fake-edt").apply { isDaemon = true } }
        try {
            val backend = LockingFakeBackend(edt, detachDelayMs = 50)
            val handle = EmbeddedMirrorHandle.forBackend(backend)
            handle.start("s", MirrorStreamOptions())

            val parked = CountDownLatch(1)
            val gate = CountDownLatch(1)
            val slow = handle.requestStart("s", MirrorStreamOptions()) {
                parked.countDown()
                gate.await(10, TimeUnit.SECONDS)
            }
            assertTrue(parked.await(5, TimeUnit.SECONDS))
            val all = listOf(
                handle.requestStop(),
                handle.requestStart("s", MirrorStreamOptions()),
                handle.requestStop(),
            )
            gate.countDown()
            slow.get(5, TimeUnit.SECONDS)
            all.forEach { it.get(5, TimeUnit.SECONDS) }

            assertFalse(backend.attached, "the LAST click (Disconnect) wins")
            assertEquals(1, backend.stops.get(), "only the final Disconnect runs")
            assertEquals(1, backend.starts.get(), "the superseded Connect never ran")
        } finally {
            edt.shutdownNow()
        }
    }

    // ---- live audio after close -----------------------------------------------------------------

    @org.junit.Test(timeout = 30_000)
    fun liveAudioToggledOnAfterCloseOrQueuedBehindCloseAttachesNothing() {
        val edt = Executors.newSingleThreadExecutor { Thread(it, "fake-edt").apply { isDaemon = true } }
        try {
            val backend = LockingFakeBackend(edt, detachDelayMs = 0)
            val handle = EmbeddedMirrorHandle.forBackend(backend)
            handle.start("s", MirrorStreamOptions())

            handle.requestSetLiveAudioEnabled(true).get(5, TimeUnit.SECONDS)
            assertEquals(1, backend.liveAudioEnables.get(), "while open the toggle reaches the backend")

            val parked = CountDownLatch(1)
            val gate = CountDownLatch(1)
            val slow = handle.requestStart("s", MirrorStreamOptions()) {
                parked.countDown()
                gate.await(10, TimeUnit.SECONDS)
            }
            assertTrue(parked.await(5, TimeUnit.SECONDS))
            val queuedBeforeClose = handle.requestSetLiveAudioEnabled(true)
            val close = handle.requestClose()
            val afterClose = handle.requestSetLiveAudioEnabled(true)
            gate.countDown()
            listOf(slow, queuedBeforeClose, close, afterClose).forEach { it.get(5, TimeUnit.SECONDS) }
            handle.requestSetLiveAudioEnabled(true).get(5, TimeUnit.SECONDS)

            assertEquals(1, backend.liveAudioEnables.get(), "no toggle may reach the backend once a close was requested")
        } finally {
            edt.shutdownNow()
        }
    }

    @org.junit.Test(timeout = 30_000)
    fun sharedRecordingSessionAttachesNoLiveAudioPlayerAfterClose() {
        val transport = EmbeddedMirrorTransport { _, _ -> error("the session is never started in this test") }
        val mkvFile = Files.createTempFile("mirror-lifecycle-audio", ".mkv").toFile().apply { deleteOnExit() }
        val session = EmbeddedDeviceSession(transport, StreamingMkvWriter(mkvFile), elapsedMillis = { 0L })
        val sinkCreations = AtomicInteger()
        val sinksClosed = AtomicInteger()
        try {
            val backend = MirrorBackend.SharedRecordingSession(
                session = session,
                decoder = object : H264Decoder {
                    override fun decode(input: InputStream, onFrame: (MirrorFrame) -> Unit) = Unit
                },
                liveAudioSinkFactory = { _, _ ->
                    sinkCreations.incrementAndGet()
                    object : com.indagium.capture.mirror.LiveAudioSink {
                        override fun onAudioConfig(extradata: ByteArray) = Unit

                        override fun onAudioPacket(ptsUs: Long, data: ByteArray) = Unit

                        override fun close() {
                            sinksClosed.incrementAndGet()
                        }
                    }
                },
                onSnapshotChanged = {},
            )
            backend.setLiveAudioEnabled(true, volume = { 1f }, onDiagnostic = {})
            assertEquals(1, sinkCreations.get())
            backend.close()
            assertEquals(1, sinksClosed.get(), "close detaches the player it had attached")

            backend.setLiveAudioEnabled(true, volume = { 1f }, onDiagnostic = {})
            assertEquals(1, sinkCreations.get(), "enabling live audio after close must be a no-op")
        } finally {
            session.close()
            mkvFile.delete()
        }
    }

    // ---- tab close ordering ---------------------------------------------------------------------

    @org.junit.Test(timeout = 30_000)
    fun closingATabStopsTheRecorderOnlyAfterTheMirrorCloseFinishedAndNeverBlocksTheCaller() {
        Harness(detachDelayMs = 0, closeDelayMs = 800).use { h ->
            h.connectAndWaitUntilAttached()
            assertTrue(h.recorderProcess.isAlive)
            val recorderAliveWhenMirrorCloseFinished = AtomicBoolean()
            h.backend.onCloseFinished = { recorderAliveWhenMirrorCloseFinished.set(h.recorderProcess.isAlive) }

            val ms = h.onUiThread { h.app.closeTab(h.tabId) }
            assertTrue(ms < 400, "closing the tab blocked the UI thread for $ms ms")
            assertTrue(h.recorderProcess.isAlive, "the recorder must keep running while the mirror close is in flight")

            awaitCondition(10_000) { !h.recorderProcess.isAlive }
            assertTrue(h.backend.closeFinished)
            assertTrue(
                recorderAliveWhenMirrorCloseFinished.get(),
                "the recorder was stopped before the mirror finished closing",
            )
        }
    }

    @org.junit.Test(timeout = 30_000)
    fun closingATabOnTheEdtClosesTheSurfaceBeforeReturning() {
        Harness(detachDelayMs = 0, closeDelayMs = 300, hasEdtSurface = true).use { h ->
            h.connectAndWaitUntilAttached()

            val ms = onRealEdt { h.app.closeTab(h.tabId) }
            assertTrue(ms < 500, "closing the tab on the EDT took $ms ms")
            assertTrue(h.backend.surfaceClosed, "the surface must be torn down before the tab's SwingPanel is disposed")
            awaitCondition(10_000) { !h.recorderProcess.isAlive }
            assertEquals(0, h.backend.surfaceCloseTimeouts.get())
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

    // ---- surface publication: the decoder's surface must be the one Compose hosts -------------------

    /** Stands in for the Metal canvas: closing is final, "attached" is set by [FakeCompose] when the
     * panel hosts it, and no Canvas/native library is needed. */
    private class FakeSurface(val id: Int) : MirrorNativeSurface {
        @Volatile var closed = false

        @Volatile var attached = false
        val closeCalls = AtomicInteger()
        override val mode = "fake-metal"
        override val isClosed get() = closed
        override val isPresentationAttached get() = !closed && attached

        override fun describeAttachment() = "fake#$id closed=$closed attached=$attached"

        override fun close() {
            closeCalls.incrementAndGet()
            closed = true
        }
    }

    /** Models VideoToolbox decoder: close() closes the surface it was bound to. */
    private class BoundDecoder(val surface: FakeSurface) : DirectH264Decoder {
        override fun decode(input: BoundedScrcpyPacketFeed, onFrame: (MirrorFrameInfo) -> Unit) {
            while (input.nextPacket() != null) {
                // swallow packets until the feed is closed
            }
        }

        override fun close() = surface.close()
    }

    /** Models the panel: observes the handle's surface flow (conflated, like collectAsState) and
     * hosts exactly the latest published surface, remounting only when its identity changes — what
     * `key(surface) { SwingPanel(...) }` does. Hosting marks the surface as attached to a window. */
    private class FakeCompose(handle: EmbeddedMirrorHandle) : AutoCloseable {
        private val scope = CoroutineScope(Dispatchers.Default)

        @Volatile var mounted: FakeSurface? = null
        val mounts = AtomicInteger()

        init {
            scope.launch {
                handle.nativeSurface.collect { published ->
                    val next = published as? FakeSurface
                    if (next !== mounted) {
                        mounted?.attached = false
                        mounted = next
                        next?.attached = true
                        mounts.incrementAndGet()
                    }
                }
            }
        }

        override fun close() = scope.cancel()
    }

    private class SharedFixture(
        val factoryGate: CountDownLatch? = null,
        val factoryEntered: CountDownLatch? = null,
        attachCheckDelayMs: Long = 0L,
        initialSurfaceClosed: Boolean = false,
    ) : AutoCloseable {
        val created = CopyOnWriteArrayList<FakeSurface>()
        val bound = CopyOnWriteArrayList<FakeSurface>()
        val diagnostics = CopyOnWriteArrayList<String>()
        private val mkvFile = Files.createTempFile("mirror-surface-publication", ".mkv").toFile().apply { deleteOnExit() }
        val session = EmbeddedDeviceSession(
            EmbeddedMirrorTransport { _, _ -> error("the session is never started in this test") },
            StreamingMkvWriter(mkvFile),
            elapsedMillis = { 0L },
        )
        private var surfaceIds = 0

        private fun newSurface() = FakeSurface(++surfaceIds).also { created += it }

        val backend = MirrorBackend.SharedRecordingSession(
            session = session,
            decoder = object : H264Decoder {
                override fun decode(input: InputStream, onFrame: (MirrorFrame) -> Unit) = Unit
            },
            macSurface = newSurface().also { if (initialSurfaceClosed) it.close() },
            directDecoderFactory = { surface -> BoundDecoder(surface as FakeSurface).also { bound += surface } },
            macSurfaceFactory = {
                factoryEntered?.countDown()
                factoryGate?.await(10, TimeUnit.SECONDS)
                newSurface()
            },
            bindDiagnostics = SurfaceBindDiagnostics(attachCheckDelayMs) { diagnostics += it },
            onSnapshotChanged = {},
        )
        val handle = EmbeddedMirrorHandle.forBackend(backend)
        val compose = FakeCompose(handle)

        val published: FakeSurface get() = requireNotNull(handle.nativeSurface.value as FakeSurface?)

        /** The invariant: the decoder is bound to the published, open surface, and that is what the
         * panel hosts. [settle] gives the (asynchronous) collector a moment to catch up. */
        fun assertDecoderBoundToHostedSurface(context: String) {
            val surface = published
            assertFalse(surface.isClosed, "$context: the published surface is closed")
            assertSame(surface, bound.last(), "$context: the decoder is bound to a different surface than the published one")
            awaitCondition(5_000) { compose.mounted === surface }
            assertTrue(surface.isPresentationAttached, "$context: the published surface is not hosted")
        }

        override fun close() {
            compose.close()
            handle.close()
            session.close()
            mkvFile.delete()
        }
    }

    @org.junit.Test(timeout = 60_000)
    fun everyDisconnectConnectPublishesTheNewSurfaceAndBindsTheDecoderToIt() {
        SharedFixture().use { f ->
            f.handle.requestStart("s", MirrorStreamOptions()).get(5, TimeUnit.SECONDS)
            f.assertDecoderBoundToHostedSurface("initial Connect")
            assertEquals(1, f.created.size)

            repeat(60) { round ->
                val before = f.published
                // Back-to-back like a fast double click: the Connect usually supersedes the queued
                // Disconnect, so the lane runs it as one restart (stop then start).
                val stop = f.handle.requestStop()
                val start = f.handle.requestStart("s", MirrorStreamOptions())
                stop.get(10, TimeUnit.SECONDS)
                start.get(10, TimeUnit.SECONDS)

                assertNotSame(before, f.published, "round $round: a new surface was created but never published")
                assertTrue(before.isClosed, "round $round: the retired surface must be closed")
                f.assertDecoderBoundToHostedSurface("round $round")
                assertEquals(f.created.last(), f.published, "round $round: the newest surface is the published one")
            }
            // Never more than one live (unclosed) surface at a time.
            assertEquals(1, f.created.count { !it.isClosed })
        }
    }

    @org.junit.Test(timeout = 60_000)
    fun spacedOutDisconnectsAndConnectsAlsoRepublishTheSurface() {
        SharedFixture().use { f ->
            f.handle.requestStart("s", MirrorStreamOptions()).get(5, TimeUnit.SECONDS)
            repeat(20) { round ->
                val before = f.published
                f.handle.requestStop().get(10, TimeUnit.SECONDS)
                // Disconnected: the swap already completed, the replacement is what is published.
                assertNotSame(before, f.published, "round $round: the replacement was not published by Disconnect")
                assertFalse(f.published.isClosed)
                f.handle.requestStart("s", MirrorStreamOptions()).get(10, TimeUnit.SECONDS)
                f.assertDecoderBoundToHostedSurface("round $round")
            }
        }
    }

    @org.junit.Test(timeout = 60_000)
    fun aBusyLaneCollapsesDisconnectConnectBurstsAndStillEndsBoundToThePublishedSurface() {
        SharedFixture().use { f ->
            f.handle.requestStart("s", MirrorStreamOptions()).get(5, TimeUnit.SECONDS)
            repeat(15) { round ->
                val parked = CountDownLatch(1)
                val gate = CountDownLatch(1)
                val slow = f.handle.requestStart("s", MirrorStreamOptions()) {
                    parked.countDown()
                    gate.await(10, TimeUnit.SECONDS)
                }
                assertTrue(parked.await(5, TimeUnit.SECONDS))
                val burst = if (round % 2 == 0) {
                    listOf(f.handle.requestStop(), f.handle.requestStart("s", MirrorStreamOptions()))
                } else {
                    listOf(
                        f.handle.requestStop(),
                        f.handle.requestStart("s", MirrorStreamOptions()),
                        f.handle.requestStop(),
                        f.handle.requestStart("s", MirrorStreamOptions()),
                    )
                }
                gate.countDown()
                slow.get(10, TimeUnit.SECONDS)
                burst.forEach { it.get(10, TimeUnit.SECONDS) }
                f.assertDecoderBoundToHostedSurface("busy-lane round $round")
                assertEquals(1, f.created.count { !it.isClosed }, "round $round: exactly one open surface")
            }
        }
    }

    @org.junit.Test(timeout = 60_000)
    fun aConnectArrivingWhileTheSurfaceSwapIsInFlightBindsToTheReplacement() {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        SharedFixture(factoryGate = gate, factoryEntered = entered).use { f ->
            f.backend.start("s", MirrorStreamOptions())
            val first = f.published
            val stopper = Executors.newSingleThreadExecutor { Thread(it, "swap-stopper").apply { isDaemon = true } }
            val starter = Executors.newSingleThreadExecutor { Thread(it, "swap-starter").apply { isDaemon = true } }
            try {
                val stopDone = stopper.submit { f.backend.stop() }
                assertTrue(entered.await(5, TimeUnit.SECONDS), "stop() must be inside the surface recreation")
                // The old surface is retired and not yet replaced: nothing is published.
                assertNull(f.handle.nativeSurface.value, "the retired surface must no longer be published")
                val startDone = starter.submit { f.backend.start("s", MirrorStreamOptions()) }
                Thread.sleep(300)
                assertFalse(startDone.isDone, "Connect must wait for the swap instead of binding to a retired surface")

                gate.countDown()
                stopDone.get(10, TimeUnit.SECONDS)
                startDone.get(10, TimeUnit.SECONDS)

                assertNotSame(first, f.published)
                f.assertDecoderBoundToHostedSurface("connect during swap")
                assertEquals(2, f.bound.size)
            } finally {
                gate.countDown()
                stopper.shutdownNow()
                starter.shutdownNow()
            }
        }
    }

    @org.junit.Test(timeout = 30_000)
    fun aSurfaceThatIsAlreadyClosedAtConnectIsReplacedBeforeTheDecoderBindsToIt() {
        SharedFixture(initialSurfaceClosed = true).use { f ->
            f.handle.requestStart("s", MirrorStreamOptions()).get(5, TimeUnit.SECONDS)

            assertEquals(2, f.created.size, "a replacement surface must have been created")
            assertTrue(f.created[0].isClosed)
            f.assertDecoderBoundToHostedSurface("closed surface at Connect")
            assertTrue(f.bound.none { it.isClosed }, "a decoder was bound to a closed surface")
            assertTrue(f.diagnostics.any { "already closed" in it }, "the closed surface must be logged: ${f.diagnostics}")
        }
    }

    @org.junit.Test(timeout = 30_000)
    fun aDecoderBoundToASurfaceThatNeverAttachesIsReportedAndAHostedOneIsNot() {
        SharedFixture(attachCheckDelayMs = 150).use { f ->
            // Hosted: FakeCompose marks the published surface attached, so the check stays quiet.
            f.handle.requestStart("s", MirrorStreamOptions()).get(5, TimeUnit.SECONDS)
            Thread.sleep(500)
            assertTrue(f.diagnostics.none { "not attached" in it }, "unexpected diagnostic: ${f.diagnostics}")

            // Not hosted: take the panel away, reconnect, and the check must say so.
            f.compose.close()
            f.handle.requestStop().get(5, TimeUnit.SECONDS)
            f.handle.requestStart("s", MirrorStreamOptions()).get(5, TimeUnit.SECONDS)
            awaitCondition(5_000) { f.diagnostics.any { "not attached" in it } }
        }
    }

    // A replacement surface that finishes after a close ran must be closed by whoever created it and
    // never published: nothing else owns it, so it would be a leaked native Metal/D3D surface.
    @org.junit.Test(timeout = 30_000)
    fun aReplacementCreatedWhileTheSurfacesAreBeingClosedIsClosedAndNeverPublished() {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        SharedFixture(factoryGate = gate, factoryEntered = entered).use { f ->
            f.backend.start("s", MirrorStreamOptions())
            val stopper = Executors.newSingleThreadExecutor { Thread(it, "swap-stopper").apply { isDaemon = true } }
            try {
                val stopDone = stopper.submit { f.backend.stop() }
                assertTrue(entered.await(5, TimeUnit.SECONDS), "stop() must be inside the surface recreation")

                f.backend.closeNativeSurfaces() // the EDT-safe half of a close: runs while the swap is in flight
                gate.countDown()
                stopDone.get(10, TimeUnit.SECONDS)

                assertEquals(2, f.created.size)
                assertNull(f.backend.nativeSurface, "a replacement created after the close must not be published")
                assertTrue(f.created.all { it.isClosed }, "every surface ever created must be closed: ${f.created.map { it.closed }}")
                assertEquals(1, f.created[1].closeCalls.get(), "the replacement is closed by its creator, exactly once")
            } finally {
                gate.countDown()
                stopper.shutdownNow()
            }
        }
    }

    // close() stops waiting for an in-flight swap after SURFACE_SWAP_WAIT_NANOS and clears the fields
    // anyway; the swap that is still running must then close its own replacement. Takes about 5 s.
    @org.junit.Test(timeout = 60_000)
    fun aCloseThatGaveUpWaitingForTheSwapStillEndsWithEverySurfaceClosed() {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        SharedFixture(factoryGate = gate, factoryEntered = entered).use { f ->
            f.backend.start("s", MirrorStreamOptions())
            val stopper = Executors.newSingleThreadExecutor { Thread(it, "swap-stopper").apply { isDaemon = true } }
            val closer = Executors.newSingleThreadExecutor { Thread(it, "swap-closer").apply { isDaemon = true } }
            try {
                val stopDone = stopper.submit { f.backend.stop() }
                assertTrue(entered.await(5, TimeUnit.SECONDS), "stop() must be inside the surface recreation")

                // The swap is stuck in the factory: close() waits its bounded time, then carries on.
                val closeDone = closer.submit { f.backend.close() }
                closeDone.get(30, TimeUnit.SECONDS)
                assertNull(f.backend.nativeSurface)

                gate.countDown() // the slow factory finally returns, long after close() gave up
                stopDone.get(10, TimeUnit.SECONDS)

                assertNull(f.backend.nativeSurface, "the late replacement must not be published into a closed mirror")
                assertTrue(f.created.all { it.isClosed }, "every surface ever created must be closed: ${f.created.map { it.closed }}")
            } finally {
                gate.countDown()
                stopper.shutdownNow()
                closer.shutdownNow()
            }
        }
    }

    @org.junit.Test(timeout = 30_000)
    fun closingTheHandlePublishesNoSurfaceAndClosesTheLastOne() {
        SharedFixture().use { f ->
            f.handle.requestStart("s", MirrorStreamOptions()).get(5, TimeUnit.SECONDS)
            val surface = f.published
            f.handle.close()
            assertNull(f.handle.nativeSurface.value)
            assertTrue(surface.isClosed)
            awaitCondition(5_000) { f.compose.mounted == null }
        }
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
