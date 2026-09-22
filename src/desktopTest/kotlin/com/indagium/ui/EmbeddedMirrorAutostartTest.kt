@file:Suppress("MagicNumber")

package com.indagium.ui

import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExecutable
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureTools
import com.indagium.capture.FakeCaptureRunner
import com.indagium.capture.StreamingFakeProcess
import com.indagium.capture.mirror.EmbeddedMirrorConnection
import com.indagium.capture.mirror.EmbeddedMirrorRuntime
import com.indagium.capture.mirror.EmbeddedMirrorState
import com.indagium.capture.mirror.EmbeddedMirrorTransport
import com.indagium.capture.mirror.H264Decoder
import com.indagium.capture.mirror.MirrorFrame
import com.indagium.capture.mirror.ScrcpyCodecIds
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression coverage for the embedded-mirror autostart race documented on
 * `AppState.ensureEmbeddedMirror`: `CaptureCard`'s own `LaunchedEffect` and [AppState.startCaptureTab]
 * both call `ensureEmbeddedMirror` for the same tab around capture start, and the second call used to
 * be silently dropped — including its `autoStart = true` intent — whenever it landed while the
 * first call's create job (tool resolution + [EmbeddedMirrorHandle.create]) was still in flight.
 */
class EmbeddedMirrorAutostartTest {
    @org.junit.Test(timeout = 20_000)
    fun aLateAutoStartRequestDuringAnInFlightCreateJobIsHonouredOnceItFinishes() {
        val root = createTempDirectory("embedded-mirror-autostart").toFile()
        val runner = FakeCaptureRunner()
        runner.enqueue(StreamingFakeProcess())
        val controller = TabCaptureController(root, runner = runner)
        val app = AppState(autosaveFile = Files.createTempFile("embedded-mirror-autostart-autosave", "").toFile(), autoExportNotes = false)
        try {
            val tabId = "t1"
            app.registerCaptureControllerForTest(tabId, controller)
            // Publish a session synchronously, the same way startCaptureTab's beforeLogcatLaunch
            // callback does — controller.selectedSession is non-null before start() returns.
            controller.start(
                CaptureDevice("SERIAL", "device", "Pixel"),
                CaptureSettings(freeSpaceReserveBytes = 0),
                CaptureTools(CaptureExecutable("adb"), null, runner),
            ) {}

            val createStarted = CountDownLatch(1)
            val releaseCreate = CountDownLatch(1)
            var createCalls = 0
            val fakeTransport = EmbeddedMirrorTransport { _, _ ->
                object : EmbeddedMirrorConnection {
                    override val videoInput: InputStream = ByteArrayInputStream(ByteArray(0))
                    override val audioInput: InputStream? = null

                    override fun sendControl(bytes: ByteArray) = Unit

                    override fun close() = Unit
                }
            }
            val fakeDecoder = object : H264Decoder {
                override fun decode(input: InputStream, onFrame: (MirrorFrame) -> Unit) {
                    // Block until the runtime is stopped/closed, like a real live video stream would.
                    while (!Thread.currentThread().isInterrupted) Thread.sleep(20)
                }
            }
            app.embeddedMirrorHandleFactory = { _, _, _ ->
                createStarted.countDown()
                assertTrue(releaseCreate.await(5, TimeUnit.SECONDS), "test setup: releaseCreate was never signalled")
                createCalls++
                EmbeddedMirrorHandle.createAroundRuntime { listener ->
                    EmbeddedMirrorRuntime(fakeTransport, fakeDecoder, listener = listener)
                }
            }

            // First call: autoStart = false (mirrors CaptureCard's LaunchedEffect on a composition
            // where the session's mirror setting hasn't resolved to true yet). Its create job starts
            // and blocks inside the factory.
            app.ensureEmbeddedMirror(tabId, autoStart = false)
            assertTrue(createStarted.await(2, TimeUnit.SECONDS), "the first call's create job must start")

            // Second call: autoStart = true, landing while the first job is still in flight (mirrors
            // startCaptureTab's own call). Before the fix this just returned because
            // embeddedMirrorStartJobsByTab already contained the tab, silently dropping autoStart.
            app.ensureEmbeddedMirror(tabId, autoStart = true)

            releaseCreate.countDown()

            awaitCondition(timeoutMs = 3_000) { app.embeddedMirrorFor(tabId) != null }
            val handle = app.embeddedMirrorFor(tabId)
            assertTrue(handle != null, "the handle must be published once the create job finishes")
            awaitCondition(timeoutMs = 3_000) { handle.snapshot.value.state == EmbeddedMirrorState.LIVE }

            assertEquals(1, createCalls, "only one create job should ever run for the same tab")
            assertEquals(
                EmbeddedMirrorState.LIVE, handle.snapshot.value.state,
                "the pending autoStart=true request must still start the mirror once the handle exists",
            )
        } finally {
            app.close()
            controller.close()
            root.deleteRecursively()
        }
    }

    // Session sharing: when the capture is recording video, ensureEmbeddedMirror must attach a
    // decoder to the recording's own EmbeddedDeviceSession instead of opening a second embedded
    // scrcpy server — see AppState.ensureEmbeddedMirror's call to controller.activeEmbeddedSession().
    @org.junit.Test(timeout = 20_000)
    fun ensureEmbeddedMirrorAttachesToTheRecordingsOwnSessionInsteadOfOpeningASecondServer() {
        val root = createTempDirectory("embedded-mirror-shared").toFile()
        val runner = FakeCaptureRunner()
        runner.enqueue(StreamingFakeProcess())
        val fakeVideoTransport = EmbeddedMirrorTransport { _, _ -> fakeRecordingConnection() }
        var transportOpenCalls = 0
        val countingTransport = EmbeddedMirrorTransport { serial, options ->
            transportOpenCalls++
            fakeVideoTransport.open(serial, options)
        }
        val controller = TabCaptureController(root, runner = runner, embeddedTransportFactory = { countingTransport })
        val app = AppState(autosaveFile = Files.createTempFile("embedded-mirror-shared-autosave", "").toFile(), autoExportNotes = false)
        try {
            val tabId = "t1"
            app.registerCaptureControllerForTest(tabId, controller)
            controller.start(
                CaptureDevice("SERIAL", "device", "Pixel"),
                CaptureSettings(recordVideo = true, freeSpaceReserveBytes = 0),
                CaptureTools(CaptureExecutable("adb"), null, runner),
            ) {}
            awaitCondition(3_000) { controller.activeEmbeddedSession()?.hasStartedVideo() == true }
            assertEquals(1, transportOpenCalls, "recording must have opened exactly one embedded session")

            var passedSharedSession: Any? = "not called"
            app.embeddedMirrorHandleFactory = { tools, mirrorRoot, sharedSession ->
                passedSharedSession = sharedSession
                EmbeddedMirrorHandle.create(tools, mirrorRoot, sharedSession)
            }
            app.ensureEmbeddedMirror(tabId, autoStart = true)

            awaitCondition(3_000) { app.embeddedMirrorFor(tabId) != null }
            val handle = requireNotNull(app.embeddedMirrorFor(tabId))
            awaitCondition(5_000) { handle.snapshot.value.frame != null }

            assertEquals(
                controller.activeEmbeddedSession(), passedSharedSession,
                "the mirror handle must be built around the recording's own EmbeddedDeviceSession",
            )
            assertEquals(
                1, transportOpenCalls,
                "attaching the mirror must not open a second embedded scrcpy server/session",
            )
            assertEquals(EmbeddedMirrorState.LIVE, handle.snapshot.value.state)
        } finally {
            app.close()
            controller.close()
            root.deleteRecursively()
        }
    }

    // The other half of the review's requirement: Disconnect must only detach the mirror decoder —
    // recording keeps writing video packets to disk regardless.
    @org.junit.Test(timeout = 20_000)
    fun disconnectingTheSharedMirrorLeavesTheRecordingWritingVideoPackets() {
        val root = createTempDirectory("embedded-mirror-disconnect").toFile()
        val runner = FakeCaptureRunner()
        runner.enqueue(StreamingFakeProcess())
        val transport = EmbeddedMirrorTransport { _, _ -> fakeRecordingConnection() }
        val controller = TabCaptureController(root, runner = runner, embeddedTransportFactory = { transport })
        val app = AppState(autosaveFile = Files.createTempFile("embedded-mirror-disconnect-autosave", "").toFile(), autoExportNotes = false)
        try {
            val tabId = "t1"
            app.registerCaptureControllerForTest(tabId, controller)
            val session = controller.start(
                CaptureDevice("SERIAL", "device", "Pixel"),
                CaptureSettings(recordVideo = true, freeSpaceReserveBytes = 0),
                CaptureTools(CaptureExecutable("adb"), null, runner),
            ) {}
            awaitCondition(3_000) { controller.activeEmbeddedSession()?.hasStartedVideo() == true }

            app.ensureEmbeddedMirror(tabId, autoStart = true)
            awaitCondition(3_000) { app.embeddedMirrorFor(tabId)?.snapshot?.value?.frame != null }

            awaitCondition(3_000) { session.videoFile.length() > 0L }
            val sizeWhileMirrorLive = session.videoFile.length()

            app.stopEmbeddedMirror(tabId) // Disconnect

            awaitCondition(3_000) { session.videoFile.length() > sizeWhileMirrorLive }
            assertTrue(
                controller.snapshot.value.videoRecording,
                "recording must still be active after the mirror disconnects",
            )
        } finally {
            app.close()
            controller.close()
            root.deleteRecursively()
        }
    }

    // Regression coverage for the second race the reviewer found: ensureEmbeddedMirror's job used
    // to take one synchronous `controller.activeEmbeddedSession()` read right after `selectedSession`
    // resolved. `CaptureRecorder.startCapture` publishes `selectedSession` (this test's
    // beforeLogcatLaunch callback) well before it ever constructs its embedded device session — adb
    // logcat still has to be spawned in between. A mirror request landing in exactly that window used
    // to see null and silently open a second, independent embedded scrcpy server for a capture that
    // IS recording video. beforeLogcatLaunch is the one seam that fires deterministically inside that
    // window (see CaptureRecorder.start's own doc), so it's used here to request the mirror at the
    // worst possible moment instead of relying on real thread-scheduling luck.
    @org.junit.Test(timeout = 20_000)
    fun mirrorRequestedBetweenSessionPublicationAndEmbeddedSessionCreationSharesTheRecordingsSessionInsteadOfOpeningAStandaloneOne() {
        val root = createTempDirectory("embedded-mirror-publish-race").toFile()
        val runner = FakeCaptureRunner()
        runner.enqueue(StreamingFakeProcess())
        val recordingTransport = EmbeddedMirrorTransport { _, _ -> fakeRecordingConnection() }
        var transportOpenCalls = 0
        val countingTransport = EmbeddedMirrorTransport { serial, options ->
            transportOpenCalls++
            recordingTransport.open(serial, options)
        }
        val controller = TabCaptureController(root, runner = runner, embeddedTransportFactory = { countingTransport })
        val app = AppState(
            autosaveFile = Files.createTempFile("embedded-mirror-publish-race-autosave", "").toFile(),
            autoExportNotes = false,
        )
        try {
            val tabId = "t1"
            app.registerCaptureControllerForTest(tabId, controller)

            var standaloneFactoryCalls = 0
            var capturedSharedSession: Any? = "not called yet"
            app.embeddedMirrorHandleFactory = { tools, mirrorRoot, sharedSession ->
                capturedSharedSession = sharedSession
                if (sharedSession == null) standaloneFactoryCalls++
                EmbeddedMirrorHandle.create(tools, mirrorRoot, sharedSession)
            }

            val ensureRequested = CountDownLatch(1)
            controller.start(
                CaptureDevice("SERIAL", "device", "Pixel"),
                CaptureSettings(recordVideo = true, freeSpaceReserveBytes = 0),
                CaptureTools(CaptureExecutable("adb"), null, runner),
            ) {
                // Fires right after selectedSession is published but before adb logcat is even
                // spawned — i.e. well before startCapture reaches its recordVideo block further
                // down and constructs the embedded device session. This is the exact window
                // awaitEmbeddedRecordingSession's bounded suspend closes.
                assertNull(controller.activeEmbeddedSession(), "test setup: embedded session must not exist yet")
                app.ensureEmbeddedMirror(tabId, autoStart = true)
                ensureRequested.countDown()
            }
            assertTrue(ensureRequested.await(5, TimeUnit.SECONDS), "test setup: beforeLogcatLaunch never ran")

            awaitCondition(5_000) { app.embeddedMirrorFor(tabId) != null }
            val handle = requireNotNull(app.embeddedMirrorFor(tabId))
            awaitCondition(5_000) { handle.snapshot.value.frame != null }

            assertEquals(
                controller.activeEmbeddedSession(), capturedSharedSession,
                "the mirror must end up sharing the recording's own embedded session even though it " +
                    "was requested before that session existed",
            )
            assertEquals(
                0, standaloneFactoryCalls,
                "no standalone embedded mirror runtime may be created for a recordVideo capture, even " +
                    "when the mirror is requested before the embedded session exists",
            )
            assertEquals(
                1, transportOpenCalls,
                "the race must not cause a second embedded scrcpy server/transport to open",
            )
        } finally {
            app.close()
            controller.close()
            root.deleteRecursively()
        }
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLISECOND
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for condition" }
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    /** A fake embedded connection whose video socket keeps emitting real, tiny H.264 packets
     * (config once, then key/delta forever, lightly paced) until closed — enough for
     * StreamingMkvWriter to actually grow session.videoFile and for JavaCvH264Decoder to actually
     * decode frames, the way a live device stream would. */
    private fun fakeRecordingConnection(): EmbeddedMirrorConnection = object : EmbeddedMirrorConnection {
        override val videoInput: InputStream = ContinuousH264Stream()
        override val audioInput: InputStream? = null

        override fun sendControl(bytes: ByteArray) = Unit

        override fun close() = Unit
    }

    /**
     * Streams a real, continuous encode's frames — never one frame's bytes repeated (each frame
     * carries reference-picture/frame_num state relative to the *real* encode sequence it came
     * from; replaying a single captured delta or key frame verbatim desyncs a real H.264 decoder —
     * "missing reference picture during reorder" / a javacv `AssertionError` decoding a corrupted
     * picture, both hit during this test's own development). Once [H264_FIXTURE]'s frames are
     * exhausted the stream ends cleanly (EOF) — EmbeddedDeviceSession's own bounded reconnect then
     * opens a fresh connection/fresh frame sequence, which is fine for what this test checks.
     */
    private class ContinuousH264Stream : InputStream() {
        private val fixture = H264_FIXTURE
        private var buffered = ByteArrayInputStream(header())
        private var index = 0

        @Volatile private var closedFlag = false

        override fun close() {
            closedFlag = true
        }

        override fun read(): Int {
            val single = ByteArray(1)
            val count = read(single, 0, 1)
            return if (count <= 0) -1 else single[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (closedFlag) return -1
            var count = buffered.read(buffer, off, len)
            while (count < 0) {
                if (closedFlag) return -1
                Thread.sleep(15)
                buffered = ByteArrayInputStream(nextPacket())
                count = buffered.read(buffer, off, len)
            }
            return count
        }

        private fun header(): ByteArray = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                writeInt(ScrcpyCodecIds.H264)
                writeInt(0x80000000.toInt())
                writeInt(H264_FIXTURE.width)
                writeInt(H264_FIXTURE.height)
                writeLong(1L shl 62)
                writeInt(fixture.config.size)
                write(fixture.config)
            }
        }.toByteArray()

        // Loops back to frame 0 (always the IDR) once the captured frames are exhausted, rather
        // than ending the stream — this test wants a connection that just keeps producing decodable
        // video for as long as it's watched, not a real reconnect: a reconnect's own fresh
        // config+IDR landing right after this stream's last (possibly frame_num/reference-state-
        // dependent) delta frame corrupted the decoder in this test's own early development,
        // exactly the kind of real-stream state this fixture is built to avoid depending on.
        private fun nextPacket(): ByteArray {
            val frameIndex = index % fixture.frames.size
            val (data, isKey) = fixture.frames[frameIndex]
            val bytes = ByteArrayOutputStream().also { out ->
                DataOutputStream(out).apply {
                    var flags = index * 33_000L
                    if (isKey) flags = flags or (1L shl 61)
                    writeLong(flags)
                    writeInt(data.size)
                    write(data)
                }
            }.toByteArray()
            index++
            return bytes
        }
    }

    private data class H264Fixture(val width: Int, val height: Int, val config: ByteArray, val frames: List<Pair<ByteArray, Boolean>>)

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val POLL_INTERVAL_MS = 10L
        val H264_FIXTURE: H264Fixture by lazy { encodeH264Fixture() }

        @Suppress("MagicNumber", "CyclomaticComplexMethod")
        private fun encodeH264Fixture(): H264Fixture {
            // 64x48 (not the smaller fixtures elsewhere in this suite, which only ever feed the
            // muxer and never actually decode): this is the one test that runs the real
            // JavaCvH264Decoder end to end, and a 32x32 openh264-encoded/decoded frame reliably hit
            // a javacv AssertionError in Java2DFrameConverter here during development — matches the
            // dimensions FfmpegCaptureVideoExporterTest/StreamingMkvWriterTest already use.
            val width = 64
            val height = 48
            val frameCount = 40
            val raw = Files.createTempFile("embedded-mirror-autostart-h264-fixture-", ".h264").toFile().apply { deleteOnExit() }
            val pixels = ByteBuffer.allocate(width * height * 3)
            val recorder = FFmpegFrameRecorder(raw, width, height, 0).apply {
                format = "h264"
                frameRate = 30.0
                videoCodec = AV_CODEC_ID_H264
                videoCodecName = "libopenh264"
                videoBitrate = 300_000
                gopSize = frameCount // one keyframe at the very start
            }
            recorder.start()
            try {
                repeat(frameCount) { frameIndex ->
                    pixels.clear()
                    repeat(width * height) {
                        val shade = ((frameIndex * 5) % 256).toByte()
                        pixels.put(shade).put(shade).put(shade)
                    }
                    pixels.flip()
                    recorder.timestamp = frameIndex * 33_000L
                    recorder.recordImage(width, height, 8, 3, width * 3, AV_PIX_FMT_BGR24, pixels)
                }
            } finally {
                recorder.stop()
                recorder.release()
            }
            val bytes = raw.readBytes()
            raw.delete()
            val starts = mutableListOf<Int>()
            var i = 0
            while (i + 3 < bytes.size) {
                val isFourByte = bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() &&
                    bytes[i + 2] == 0.toByte() && i + 3 < bytes.size && bytes[i + 3] == 1.toByte()
                val isThreeByte = !isFourByte && bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 1.toByte()
                if (isFourByte || isThreeByte) {
                    starts += i
                    i += if (isFourByte) 4 else 3
                } else {
                    i++
                }
            }
            val nalUnits = starts.indices.map { idx ->
                val from = starts[idx]
                val to = if (idx + 1 < starts.size) starts[idx + 1] else bytes.size
                bytes.copyOfRange(from, to)
            }
            val configUnits = mutableListOf<ByteArray>()
            val frames = mutableListOf<Pair<ByteArray, Boolean>>()
            for (nal in nalUnits) {
                val start = if (nal.size >= 4 && nal[2] == 0.toByte() && nal[3] == 1.toByte()) 4 else 3
                when (nal[start].toInt() and 0x1f) {
                    7, 8 -> configUnits += nal
                    5 -> frames += nal to true
                    1 -> frames += nal to false
                }
            }
            require(frames.isNotEmpty() && frames.first().second) { "fixture encode must start with a key frame" }
            return H264Fixture(
                width = width,
                height = height,
                config = configUnits.reduce { acc, more -> acc + more },
                frames = frames,
            )
        }
    }
}
