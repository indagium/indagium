package com.indagium.capture.mirror

import com.indagium.capture.CaptureCommandResult
import com.indagium.capture.CaptureExecutable
import com.indagium.capture.CaptureProcessRunner
import com.indagium.capture.CaptureProcessSpec
import com.indagium.capture.CaptureTools
import com.indagium.capture.RunningCaptureProcess
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ConnectException
import java.net.Socket
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddedMirrorTest {
    @Test
    fun coordinateMapperAccountsForFitLetterboxAndRotation() {
        val mapper = MirrorCoordinateMapper(1_000, 1_000, 2_000, 1_000)
        assertEquals(DevicePoint(1_000, 500), mapper.map(500f, 500f))
        assertEquals(null, mapper.map(10f, 10f), "top/bottom letterbox must not inject touches")

        val rotated = MirrorCoordinateMapper(1_000, 1_000, 2_000, 1_000, rotationDegrees = 90)
        assertEquals(DevicePoint(999, 500), rotated.map(500f, 500f))
    }

    @Test
    fun controlEncoderUsesBigEndianScrcpyWireFields() {
        val key = ScrcpyControlEncoder.encode(MirrorControlCommand.Key(MirrorKeyAction.DOWN, 29, 2, 3))
        assertContentEquals(
            byteArrayOf(0, 0, 0, 0, 0, 29, 0, 0, 0, 2, 0, 0, 0, 3),
            key,
        )

        val text = ScrcpyControlEncoder.encode(MirrorControlCommand.Text("é"))
        assertContentEquals(byteArrayOf(1, 0, 0, 0, 2, 0xc3.toByte(), 0xa9.toByte()), text)

        val touch = ScrcpyControlEncoder.encode(
            MirrorControlCommand.Touch(
                MirrorTouchAction.DOWN,
                7,
                10,
                20,
                pressure = 1f,
                screenWidth = 1_080,
                screenHeight = 1_920,
            ),
        )
        assertEquals(32, touch.size)
        assertEquals(2, touch[0].toInt())
        assertEquals(0xff.toByte(), touch[22])
        assertEquals(0xff.toByte(), touch[23])
        assertContentEquals(byteArrayOf(4, 1), ScrcpyControlEncoder.encode(MirrorControlCommand.Back()))
        assertContentEquals(
            byteArrayOf(8, 0),
            ScrcpyControlEncoder.encode(MirrorControlCommand.GetClipboard(MirrorClipboardCopyKey.COPY)),
        )
        assertEquals(9, ScrcpyControlEncoder.encode(MirrorControlCommand.Clipboard("x")).first().toInt())
    }

    @Test
    fun annexBFramerEmitsCompleteUnitsAndRetainsTrailingPartialUnit() {
        val framer = H264AnnexBFramer()
        val first = framer.append(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 0, 0, 1, 0x41))
        assertEquals(1, first.size)
        assertContentEquals(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2), first.single())
        val finished = framer.finish()
        assertEquals(1, finished.size)
        assertContentEquals(byteArrayOf(0, 0, 1, 0x41), finished.single())
    }

    @Test
    fun latestFrameBufferDropsOldestAndReturnsNewest() {
        val buffer = LatestFrameBuffer(capacity = 2)
        val one = frame(1)
        val two = frame(2)
        val three = frame(3)
        assertTrue(buffer.offer(one))
        assertTrue(buffer.offer(two))
        assertTrue(buffer.offer(three))
        assertEquals(1L, buffer.droppedFrames())
        assertEquals(three, buffer.pollLatest())
        assertEquals(null, buffer.pollLatest())
    }

    @Test
    fun javaCvDecoderStartsLiveRawH264BeforeInputReachesEof() {
        val bytes = requireNotNull(javaClass.classLoader.getResourceAsStream("video/raw-h264-no-timestamps.h264")) {
            "raw H.264 decoder fixture is missing"
        }.use { it.readBytes() }
        val releaseEof = CountDownLatch(1)
        val firstFrame = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val input = BlockingAfterPayloadInputStream(bytes, releaseEof)
        val worker = thread(start = true, isDaemon = true, name = "mirror-decoder-test") {
            try {
                JavaCvH264Decoder().decode(input) { firstFrame.countDown() }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        try {
            assertTrue(firstFrame.await(2, TimeUnit.SECONDS), "decoder waited for live input EOF before its first frame")
        } finally {
            releaseEof.countDown()
            worker.join(2_000)
        }
        assertFalse(worker.isAlive, "decoder did not stop after the live stream closed")
        assertNull(failure.get())
    }

    @Test
    fun runtimePublishesConnectingLiveThenFailedAndStopsItsTransport() {
        val states = CopyOnWriteArrayList<EmbeddedMirrorState>()
        var closed = false
        val transport = EmbeddedMirrorTransport { _, _ ->
            object : EmbeddedMirrorConnection {
                override val videoInput = ByteArrayInputStream(ByteArray(0))
                override val audioInput: InputStream? = null

                override fun sendControl(bytes: ByteArray) = Unit

                override fun close() { closed = true }
            }
        }
        val decoder = object : H264Decoder {
            override fun decode(input: java.io.InputStream, onFrame: (MirrorFrame) -> Unit) = Unit
        }
        val runtime = EmbeddedMirrorRuntime(
            transport,
            decoder,
            maxReconnectAttempts = 0,
            listener = { states += it.state },
        )
        try {
            runtime.start("emulator-5554")
            await { runtime.snapshot().state == EmbeddedMirrorState.FAILED }
            assertTrue(states.contains(EmbeddedMirrorState.CONNECTING))
            assertTrue(states.contains(EmbeddedMirrorState.LIVE))
            assertEquals(EmbeddedMirrorState.FAILED, runtime.snapshot().state)
            assertNotNull(runtime.snapshot().error)
            runtime.stop()
            assertTrue(closed)
            assertEquals(EmbeddedMirrorState.DISCONNECTED, runtime.snapshot().state)
        } finally {
            runtime.close()
        }
    }

    // Regression test for the "stop() blocks callers under its lock" bug: AdbScrcpyConnection.close()
    // runs synchronous adb subprocess cleanup and can take real wall-clock time. stop() used to call
    // connection.close() from inside its synchronized block, so a concurrent snapshot()/send() call
    // (both also synchronized on the same lock) — and, in the app, a Compose click handler calling
    // stop() straight from the UI thread — sat blocked behind that close for the whole duration.
    @Test
    fun stopDetachesUnderItsLockButClosesTheConnectionAfterReleasingIt() {
        val closeStarted = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val transport = EmbeddedMirrorTransport { _, _ ->
            object : EmbeddedMirrorConnection {
                override val videoInput = ByteArrayInputStream(ByteArray(0))
                override val audioInput: InputStream? = null

                override fun sendControl(bytes: ByteArray) = Unit

                override fun close() {
                    closeStarted.countDown()
                    releaseClose.await(2, TimeUnit.SECONDS)
                }
            }
        }
        val decoder = object : H264Decoder {
            override fun decode(input: InputStream, onFrame: (MirrorFrame) -> Unit) {
                // Block "forever" (bounded by the test's own timeouts) so the connection stays open
                // until stop() detaches and closes it — the scenario under test.
                Thread.sleep(5_000)
            }
        }
        val runtime = EmbeddedMirrorRuntime(transport, decoder, maxReconnectAttempts = 0)
        try {
            runtime.start("serial")
            await { runtime.snapshot().state == EmbeddedMirrorState.LIVE }

            val stopper = thread(name = "mirror-stop-test") { runtime.stop() }
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS), "stop() must reach connection.close()")
            // The lock must already be free at this point — a concurrent snapshot() must return
            // immediately instead of blocking behind the still-running close() above.
            val snapshotReturned = CountDownLatch(1)
            thread(name = "mirror-snapshot-test") {
                runtime.snapshot()
                snapshotReturned.countDown()
            }
            assertTrue(snapshotReturned.await(500, TimeUnit.MILLISECONDS), "snapshot() must not block behind a slow close()")

            releaseClose.countDown()
            stopper.join(2_000)
            assertFalse(stopper.isAlive)
        } finally {
            releaseClose.countDown()
            runtime.close()
        }
    }

    @Test
    fun runtimeReopensTransportAfterStreamFailureWithBoundedAttempts() {
        val states = CopyOnWriteArrayList<EmbeddedMirrorState>()
        var opens = 0
        val transport = EmbeddedMirrorTransport { _, _ ->
            opens++
            object : EmbeddedMirrorConnection {
                override val videoInput = ByteArrayInputStream(ByteArray(0))
                override val audioInput: InputStream? = null

                override fun sendControl(bytes: ByteArray) = Unit

                override fun close() = Unit
            }
        }
        val decoder = object : H264Decoder {
            override fun decode(input: java.io.InputStream, onFrame: (MirrorFrame) -> Unit) = Unit
        }
        val runtime = EmbeddedMirrorRuntime(
            transport,
            decoder,
            maxReconnectAttempts = 1,
            reconnectDelay = java.time.Duration.ZERO,
            listener = { states += it.state },
        )
        try {
            runtime.start("serial")
            await { runtime.snapshot().state == EmbeddedMirrorState.FAILED }
            assertEquals(2, opens)
            assertTrue(states.contains(EmbeddedMirrorState.RECONNECTING))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun stoppingBlockedNativePacketWaitDoesNotTriggerComposeFallback() {
        val packetWaitStarted = CountDownLatch(1)
        val fallbackCalls = AtomicInteger()
        val nativeFailureCalls = AtomicInteger()
        val transport = EmbeddedMirrorTransport { _, _ ->
            val input = PipedInputStream(4 * 1024)
            val output = PipedOutputStream(input)
            object : EmbeddedMirrorConnection {
                override val videoInput: InputStream = input
                override val audioInput: InputStream? = null
                override fun sendControl(bytes: ByteArray) = Unit
                override fun close() {
                    runCatching { input.close() }
                    runCatching { output.close() }
                }
            }
        }
        val directDecoder = DirectH264Decoder { feed, _ ->
            packetWaitStarted.countDown()
            feed.nextPacket()
        }
        val fallbackDecoder = object : H264Decoder {
            override fun decode(input: InputStream, onFrame: (MirrorFrame) -> Unit) {
                fallbackCalls.incrementAndGet()
                input.readBytes()
            }
        }
        val runtime = EmbeddedMirrorRuntime(
            transport = transport,
            directDecoder = directDecoder,
            onDirectFrame = {},
            onDirectDecoderFailure = { nativeFailureCalls.incrementAndGet() },
            directFallbackDecoder = fallbackDecoder,
            maxReconnectAttempts = 0,
        )
        try {
            runtime.start("serial")
            assertTrue(packetWaitStarted.await(2, TimeUnit.SECONDS), "direct decoder did not wait for a video packet")
            runtime.stop()
            assertEquals(EmbeddedMirrorState.DISCONNECTED, runtime.snapshot().state)
            assertEquals(0, fallbackCalls.get(), "stop cancellation must not start Compose fallback")
            assertEquals(0, nativeFailureCalls.get(), "stop cancellation must not be reported as a native failure")
        } finally {
            runtime.close()
        }
    }

    @Test
    fun nativeFailureKeepsComposeFallbackAcrossTransportReconnects() {
        val opens = AtomicInteger()
        val nativeCalls = AtomicInteger()
        val composeCalls = AtomicInteger()
        val nativeFailures = AtomicInteger()
        val decodedFrames = AtomicInteger()
        val nativeFailed = CountDownLatch(1)
        val composeStartedTwice = CountDownLatch(2)
        val releaseKeyFrames = List(2) { CountDownLatch(1) }
        val transport = EmbeddedMirrorTransport { _, _ ->
            val index = opens.getAndIncrement()
            check(index in releaseKeyFrames.indices)
            val input = PipedInputStream(4 * 1024)
            val output = PipedOutputStream(input)
            thread(isDaemon = true, name = "mirror-reconnect-test-writer-$index") {
                runCatching {
                    DataOutputStream(output).use { writer ->
                        writer.writeInt(ScrcpyCodecIds.H264)
                        writer.writeLong(1L shl 62)
                        writer.writeInt(6)
                        writer.write(byteArrayOf(0, 0, 0, 1, 0x67, 1))
                        writer.flush()
                        releaseKeyFrames[index].await(2, TimeUnit.SECONDS)
                        writer.writeLong(1L shl 61)
                        writer.writeInt(6)
                        writer.write(byteArrayOf(0, 0, 0, 1, 0x65, 2))
                    }
                }
            }
            object : EmbeddedMirrorConnection {
                override val videoInput: InputStream = input
                override val audioInput: InputStream? = null
                override fun sendControl(bytes: ByteArray) = Unit
                override fun close() {
                    runCatching { input.close() }
                    runCatching { output.close() }
                }
            }
        }
        val directDecoder = DirectH264Decoder { feed, _ ->
            nativeCalls.incrementAndGet()
            assertTrue(feed.nextPacket()?.config == true, "native path starts with SPS/PPS")
            throw IllegalStateException("simulated native renderer failure")
        }
        val composeDecoder = object : H264Decoder {
            override fun decode(input: InputStream, onFrame: (MirrorFrame) -> Unit) {
                composeCalls.incrementAndGet()
                composeStartedTwice.countDown()
                val bytes = input.readBytes()
                if (bytes.any { it == 0x65.toByte() }) {
                    decodedFrames.incrementAndGet()
                    onFrame(frame(9))
                }
            }
        }
        val runtime = EmbeddedMirrorRuntime(
            transport = transport,
            directDecoder = directDecoder,
            onDirectFrame = {},
            onDirectDecoderFailure = {
                nativeFailures.incrementAndGet()
                nativeFailed.countDown()
            },
            directFallbackDecoder = composeDecoder,
            maxReconnectAttempts = 1,
            reconnectDelay = Duration.ZERO,
        )
        try {
            runtime.start("serial")
            assertTrue(nativeFailed.await(2, TimeUnit.SECONDS), "native failure triggers same-connection fallback")
            releaseKeyFrames[0].countDown()
            assertTrue(composeStartedTwice.await(2, TimeUnit.SECONDS), "reconnect also enters Compose directly")
            releaseKeyFrames[1].countDown()
            await { runtime.snapshot().state == EmbeddedMirrorState.FAILED }

            assertEquals(2, opens.get(), "the stream reconnects once")
            assertEquals(1, nativeCalls.get(), "the hidden native surface is not retried after fallback")
            assertEquals(2, composeCalls.get(), "both the original socket and reconnect use Compose")
            assertEquals(1, nativeFailures.get(), "native fallback is reported once")
            assertEquals(2, decodedFrames.get(), "both same-socket GOPs reach the fallback decoder")
        } finally {
            releaseKeyFrames.forEach(CountDownLatch::countDown)
            runtime.close()
        }
    }

    @Test
    fun missingBundledServerFailsActionablyWithoutNetworkFallback() {
        val resolver = ScrcpyServerAssetResolver(resourceLoader = { null })
        val failure = resolver.availability().exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure.message.orEmpty().contains("not present"))
        assertTrue(failure.message.orEmpty().contains("do not download"))
    }

    @Test
    fun malformedBundledServerIsRejectedByPinnedChecksum() {
        val descriptor = ScrcpyServerDescriptor("test", "server", "0".repeat(64))
        val result = runCatching { ScrcpyServerAsset(descriptor, byteArrayOf(1, 2, 3)) }
        assertTrue(result.isFailure)
    }

    @Test
    fun bundledServerResourceResolvesToPinnedDigest() {
        val asset = ScrcpyServerAssetResolver().resolve()
        assertEquals("4.1", asset.descriptor.version)
        assertEquals(asset.descriptor.sha256, sha256(asset.bytes))
        assertTrue(asset.bytes.isNotEmpty())
    }

    @Test
    fun adbTransportConnectsVideoThenControlAndWritesControlToSecondSocket() {
        val root = java.nio.file.Files.createTempDirectory("mirror-transport-test").toFile()
        val asset = ScrcpyServerAssetResolver().resolve()
        val runner = TransportRunner()
        val sockets = mutableListOf<TestSocket>()
        val resolver = ScrcpyServerAssetResolver(
            resourceLoader = { ByteArrayInputStream(asset.bytes) },
        )
        val transport = AdbScrcpyTransport(
            tools = CaptureTools(CaptureExecutable("adb"), null, runner),
            runner = runner,
            assetResolver = resolver,
            localRoot = root,
            socketConnector = { _, port -> TestSocket(port).also(sockets::add) },
        )
        try {
            val connection = transport.open("serial", MirrorStreamOptions())
            assertEquals(2, sockets.size)
            assertEquals(27_183, sockets[0].requestedPort)
            assertEquals(27_183, sockets[1].requestedPort)
            assertEquals(0x01, connection.videoInput.read())
            connection.sendControl(byteArrayOf(4, 5))
            assertContentEquals(byteArrayOf(4, 5), sockets[1].output.toByteArray())
            assertTrue(runner.serverSpec.command.contains("tunnel_forward=true"))
            assertTrue(runner.serverSpec.command.contains("send_dummy_byte=false"))
            assertTrue(runner.serverSpec.command.contains("video_bit_rate=8000000"))
            assertFalse(runner.serverSpec.command.contains("video_bit_rate=8M"))
            connection.close()
            assertTrue(sockets.all { it.closed })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun adbTransportRetriesTransientRefusalUntilBothSocketsAreReady() {
        val root = java.nio.file.Files.createTempDirectory("mirror-transport-retry-test").toFile()
        val asset = ScrcpyServerAssetResolver().resolve()
        val runner = TransportRunner()
        val sockets = mutableListOf<TestSocket>()
        var attempts = 0
        val transport = AdbScrcpyTransport(
            tools = CaptureTools(CaptureExecutable("adb"), null, runner),
            runner = runner,
            assetResolver = ScrcpyServerAssetResolver(resourceLoader = { ByteArrayInputStream(asset.bytes) }),
            localRoot = root,
            socketConnector = { _, port ->
                attempts++
                if (attempts <= 2) throw ConnectException("server is still starting")
                TestSocket(port).also(sockets::add)
            },
            connectTimeout = Duration.ofSeconds(1),
            retryDelay = Duration.ofMillis(5),
        )
        try {
            val connection = transport.open("serial", MirrorStreamOptions())
            assertEquals(4, attempts, "video and control each need a successful connection")
            assertEquals(2, sockets.size)
            assertEquals(0x01, connection.videoInput.read())
            connection.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun adbTransportRetriesHostSocketThatImmediatelyReachesEof() {
        val root = java.nio.file.Files.createTempDirectory("mirror-transport-eof-test").toFile()
        val asset = ScrcpyServerAssetResolver().resolve()
        val runner = TransportRunner()
        val sockets = mutableListOf<TestSocket>()
        var attempts = 0
        val transport = AdbScrcpyTransport(
            tools = CaptureTools(CaptureExecutable("adb"), null, runner),
            runner = runner,
            assetResolver = ScrcpyServerAssetResolver(resourceLoader = { ByteArrayInputStream(asset.bytes) }),
            localRoot = root,
            socketConnector = { _, port ->
                attempts++
                TestSocket(port, eof = attempts <= 2).also(sockets::add)
            },
            connectTimeout = Duration.ofSeconds(1),
            retryDelay = Duration.ofMillis(5),
            serverStartupGrace = Duration.ZERO,
        )
        try {
            val connection = transport.open("serial", MirrorStreamOptions())
            assertEquals(4, attempts)
            assertTrue(sockets[0].closed)
            assertTrue(sockets[1].closed)
            connection.close()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun adbTransportTimeoutCleansForwardAndServerAfterReadinessFailure() {
        val root = java.nio.file.Files.createTempDirectory("mirror-transport-timeout-test").toFile()
        val asset = ScrcpyServerAssetResolver().resolve()
        val runner = TransportRunner()
        val transport = AdbScrcpyTransport(
            tools = CaptureTools(CaptureExecutable("adb"), null, runner),
            runner = runner,
            assetResolver = ScrcpyServerAssetResolver(resourceLoader = { ByteArrayInputStream(asset.bytes) }),
            localRoot = root,
            socketConnector = { _, _ -> throw ConnectException("server is still starting") },
            connectTimeout = Duration.ofMillis(60),
            retryDelay = Duration.ofMillis(5),
        )
        try {
            val failure = runCatching { transport.open("serial", MirrorStreamOptions()) }.exceptionOrNull()
            assertTrue(failure is MirrorTransportConnectionException)
            assertTrue(runner.runSpecs.any { it.command.contains("--remove") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun adbTransportUsesIsolatedAssetPathsForOverlappingConnections() {
        val root = java.nio.file.Files.createTempDirectory("mirror-transport-isolation-test").toFile()
        val runner = TransportRunner()
        val ids = ArrayDeque(listOf("first", "second"))
        val asset = ScrcpyServerAssetResolver().resolve()
        val transport = AdbScrcpyTransport(
            tools = CaptureTools(CaptureExecutable("adb"), null, runner),
            runner = runner,
            assetResolver = ScrcpyServerAssetResolver(resourceLoader = { ByteArrayInputStream(asset.bytes) }),
            localRoot = root,
            assetIdGenerator = { ids.removeFirst() },
            socketConnector = { _, port -> TestSocket(port) },
        )
        try {
            val first = transport.open("serial", MirrorStreamOptions())
            val firstClasspath = runner.serverSpec.command.first { it.startsWith("CLASSPATH=") }
            first.close()
            val second = transport.open("serial", MirrorStreamOptions())
            val secondClasspath = runner.serverSpec.command.first { it.startsWith("CLASSPATH=") }
            second.close()
            assertTrue(firstClasspath != secondClasspath)
            assertTrue(firstClasspath.contains("first.jar"))
            assertTrue(secondClasspath.contains("second.jar"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun frame(value: Int) = MirrorFrame(1, 1, intArrayOf(value))

    private fun await(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition(), "condition was not met before timeout")
    }

    private class TestSocket(val requestedPort: Int, eof: Boolean = false) : Socket() {
        val input = ByteArrayInputStream(if (eof) ByteArray(0) else byteArrayOf(0x01))
        val output = ByteArrayOutputStream()
        var closed = false

        override fun getInputStream(): InputStream = input

        override fun getOutputStream(): java.io.OutputStream = output

        override fun close() { closed = true }
    }

    private class BlockingAfterPayloadInputStream(
        private val payload: ByteArray,
        private val releaseEof: CountDownLatch,
    ) : InputStream() {
        private var offset = 0

        override fun read(): Int {
            if (offset < payload.size) return payload[offset++].toInt() and 0xff
            awaitEof()
            return -1
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            require(off >= 0 && len >= 0 && off <= buffer.size - len)
            if (offset >= payload.size) {
                awaitEof()
                return -1
            }
            val count = minOf(len, payload.size - offset)
            payload.copyInto(buffer, off, offset, offset + count)
            offset += count
            return count
        }

        private fun awaitEof() {
            try {
                releaseEof.await()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private class TransportRunner : CaptureProcessRunner {
        lateinit var serverSpec: CaptureProcessSpec
        val runSpecs = mutableListOf<CaptureProcessSpec>()

        override fun start(spec: CaptureProcessSpec): RunningCaptureProcess {
            serverSpec = spec
            return object : RunningCaptureProcess {
                override val inputStream = ByteArrayInputStream(ByteArray(0))
                override val errorStream = ByteArrayInputStream(ByteArray(0))
                override val isAlive = true

                override fun waitFor(timeout: Duration): Boolean = true

                override fun exitCode(): Int = 0

                override fun terminate(grace: Duration) = Unit

                override fun close() = Unit
            }
        }

        override fun run(
            spec: CaptureProcessSpec,
            timeout: Duration,
            outputLimitBytes: Int,
        ): CaptureCommandResult {
            runSpecs += spec
            return when {
                spec.command.contains("sha256sum") -> CaptureCommandResult(
                    0,
                    (PinnedScrcpyServer.descriptor.sha256 + "  /data/local/tmp/server.jar\n").toByteArray(),
                    ByteArray(0),
                )
                spec.command.contains("forward") && !spec.command.contains("--remove") ->
                    CaptureCommandResult(0, "27183\n".toByteArray(), ByteArray(0))
                else -> CaptureCommandResult(0, ByteArray(0), ByteArray(0))
            }
        }
    }
}
