@file:Suppress("MagicNumber", "TooGenericExceptionCaught", "UseCheckOrError")

package com.indagium.capture.mirror

import com.indagium.capture.CaptureProcessRunner
import com.indagium.capture.CaptureTools
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

internal enum class EmbeddedMirrorState { DISCONNECTED, CONNECTING, LIVE, RECONNECTING, FAILED }

internal data class MirrorFrame(
    val width: Int,
    val height: Int,
    val pixelsArgb: IntArray,
    val presentationTimeUs: Long = 0,
) {
    init {
        require(width > 0 && height > 0) { "frame dimensions must be positive" }
        require(pixelsArgb.size == width * height) { "frame pixels do not match dimensions" }
    }
}

internal data class EmbeddedMirrorSnapshot(
    val state: EmbeddedMirrorState = EmbeddedMirrorState.DISCONNECTED,
    val deviceSerial: String? = null,
    val frame: MirrorFrame? = null,
    val droppedFrames: Long = 0,
    val reconnectAttempt: Int = 0,
    val error: String? = null,
)

internal data class MirrorStreamOptions(
    val maxSize: Int = 1080,
    val maxFps: Int = 30,
    val bitrateMbps: Int = 8,
    val audio: Boolean = false,
    /** Server-side `video_codec_options=i-frame-interval:float=<n>` — a short keyframe interval
     * keeps exact-start snapshot re-encodes cheap (see [com.indagium.capture.FfmpegCaptureVideoExporter])
     * and bounds how much of a live recording a fresh mirror connection must wait to resync on. */
    val keyFrameIntervalSeconds: Float = 2f,
) {
    init {
        require(maxSize >= 0)
        require(maxFps > 0)
        require(bitrateMbps in 1..MAX_SERVER_BITRATE_MBPS) {
            "bitrateMbps must fit scrcpy server's signed 32-bit bits-per-second option"
        }
    }

    val serverVideoBitRateBitsPerSecond: Int get() = bitrateMbps * BITS_PER_MEGABIT

    private companion object {
        const val BITS_PER_MEGABIT = 1_000_000
        const val MAX_SERVER_BITRATE_MBPS = Int.MAX_VALUE / BITS_PER_MEGABIT
    }
}

internal interface EmbeddedMirrorConnection : Closeable {
    /** Raw scrcpy v4.1 frame-meta video socket bytes — parse with [ScrcpyPacketReader], or wrap
     * with [ScrcpyToAnnexBInputStream] for a plain decodable Annex-B stream (what [runSession] does
     * for the mirror decoder). */
    val videoInput: InputStream

    /** Raw scrcpy audio socket bytes, present only when [MirrorStreamOptions.audio] was requested.
     * Its first 4 bytes are a [ScrcpyPacketReader.readHeader] result — a real codec id, or one of
     * the two "disabled" sentinels signalling audio was unavailable on this device/Android version
     * (never a transport failure — see [ScrcpyCodecIds]). */
    val audioInput: InputStream?

    fun sendControl(bytes: ByteArray)
}

internal fun interface EmbeddedMirrorTransport {
    fun open(deviceSerial: String, options: MirrorStreamOptions): EmbeddedMirrorConnection
}

internal fun interface H264Decoder : Closeable {
    /** Blocks until input EOF or a decoder error, delivering complete frames to [onFrame]. */
    fun decode(input: InputStream, onFrame: (MirrorFrame) -> Unit)

    override fun close() = Unit
}

/** Bounded latest-frame queue. Slow Compose consumers never back up the device socket. */
internal class LatestFrameBuffer(private val capacity: Int = 2) : Closeable {
    private val lock = Any()
    private val frames = ArrayDeque<MirrorFrame>()
    private var dropped = 0L
    private var closed = false

    init { require(capacity > 0) }

    fun offer(frame: MirrorFrame): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        if (frames.size == capacity) {
            frames.removeFirst()
            dropped++
        }
        frames.addLast(frame)
        true
    }

    /** Returns the newest frame and discards stale frames in one atomic operation. */
    fun pollLatest(): MirrorFrame? = synchronized(lock) {
        if (frames.isEmpty()) return@synchronized null
        val latest = frames.removeLast()
        frames.clear()
        latest
    }

    fun droppedFrames(): Long = synchronized(lock) { dropped }

    override fun close() = synchronized(lock) {
        closed = true
        frames.clear()
    }
}

/**
 * Owns only the embedded mirror transport and decoder. Recording remains owned by
 * [com.indagium.capture.CaptureRecorder], so stopping one cannot mutate the other.
 */
internal class EmbeddedMirrorRuntime(
    private val transport: EmbeddedMirrorTransport,
    private val decoder: H264Decoder,
    private val maxReconnectAttempts: Int = 3,
    private val reconnectDelay: Duration = Duration.ofMillis(250),
    private val frameBuffer: LatestFrameBuffer = LatestFrameBuffer(),
    private val listener: (EmbeddedMirrorSnapshot) -> Unit = {},
) : Closeable {
    private val lock = Any()
    private val generation = AtomicLong(0)
    private var snapshotValue = EmbeddedMirrorSnapshot()
    private var connection: EmbeddedMirrorConnection? = null
    private var worker: Thread? = null
    private var stopping = false

    init { require(maxReconnectAttempts >= 0) }

    fun snapshot(): EmbeddedMirrorSnapshot = synchronized(lock) { snapshotValue }

    /** Starts asynchronously; [snapshot] is CONNECTING immediately and never blocks on ADB. */
    fun start(deviceSerial: String, options: MirrorStreamOptions = MirrorStreamOptions()) {
        require(deviceSerial.isNotBlank()) { "device serial cannot be blank" }
        val previousConnection = synchronized(lock) {
            val previous = detachLocked()
            stopping = false
            val runId = generation.incrementAndGet()
            publishLocked(EmbeddedMirrorSnapshot(EmbeddedMirrorState.CONNECTING, deviceSerial = deviceSerial))
            worker = thread(name = "embedded-mirror-$deviceSerial", isDaemon = true) {
                runSession(runId, deviceSerial, options)
            }
            previous
        }
        // Closing a real AdbScrcpyConnection runs `adb forward --remove`/`adb shell rm`
        // synchronously and can take real wall-clock time; doing that while still holding [lock]
        // would block every other caller of this runtime (snapshot(), send(), a concurrent
        // stop()/start()) behind it for no reason — none of them need the old connection, only the
        // fact that it's been detached. See stop() below for the same pattern.
        runCatching { previousConnection?.close() }
    }

    fun stop() {
        val threadToJoin: Thread?
        val previousConnection: EmbeddedMirrorConnection?
        synchronized(lock) {
            stopping = true
            generation.incrementAndGet()
            threadToJoin = worker
            previousConnection = detachLocked()
            publishLocked(EmbeddedMirrorSnapshot())
        }
        runCatching { previousConnection?.close() }
        if (threadToJoin !== Thread.currentThread()) threadToJoin?.join(MIRROR_STOP_JOIN_MS)
    }

    fun pollFrame(): MirrorFrame? = frameBuffer.pollLatest()

    fun send(command: MirrorControlCommand): Boolean {
        val active = synchronized(lock) {
            if (snapshotValue.state != EmbeddedMirrorState.LIVE) return false
            connection
        } ?: return false
        return runCatching { active.sendControl(ScrcpyControlEncoder.encode(command)); true }
            .getOrElse { failure ->
                // Closing the connection wakes a blocked decoder so the session loop can perform
                // its bounded reconnect sequence instead of leaving the UI stuck in RECONNECTING.
                runCatching { active.close() }
                synchronized(lock) {
                    if (connection === active && snapshotValue.state == EmbeddedMirrorState.LIVE) {
                        publishLocked(snapshotValue.copy(state = EmbeddedMirrorState.RECONNECTING, error = diagnostic(failure)))
                    }
                }
                false
            }
    }

    fun sendTouch(
        mapper: MirrorCoordinateMapper,
        action: MirrorTouchAction,
        pointerId: Long,
        viewportX: Float,
        viewportY: Float,
        pressure: Float = 1f,
        actionButton: Long = 0,
        buttons: Long = 0,
    ): Boolean {
        val point = mapper.map(viewportX, viewportY) ?: return false
        return send(
            MirrorControlCommand.Touch(
                action = action,
                pointerId = pointerId,
                x = point.x,
                y = point.y,
                pressure = pressure,
                actionButton = actionButton,
                buttons = buttons,
                screenWidth = mapper.screenWidth,
                screenHeight = mapper.screenHeight,
            ),
        )
    }

    override fun close() {
        stop()
        frameBuffer.close()
        decoder.close()
    }

    private fun runSession(runId: Long, serial: String, options: MirrorStreamOptions) {
        var attempt = 0
        while (isCurrent(runId)) {
            if (attempt > 0) {
                synchronized(lock) {
                    if (!isCurrentLocked(runId)) return
                    publishLocked(
                        snapshotValue.copy(
                            state = EmbeddedMirrorState.RECONNECTING,
                            reconnectAttempt = attempt,
                            error = null,
                        ),
                    )
                }
                if (!sleepBeforeReconnect()) return
            }
            var opened: EmbeddedMirrorConnection? = null
            try {
                opened = transport.open(serial, options)
                synchronized(lock) {
                    if (!isCurrentLocked(runId)) {
                        opened.close()
                        return
                    }
                    connection = opened
                    publishLocked(
                        snapshotValue.copy(
                            state = EmbeddedMirrorState.LIVE,
                            reconnectAttempt = attempt,
                            error = null,
                        ),
                    )
                }
                decoder.decode(ScrcpyToAnnexBInputStream(requireNotNull(opened).videoInput)) { frame ->
                    if (isCurrent(runId)) {
                        frameBuffer.offer(frame)
                        synchronized(lock) {
                            if (isCurrentLocked(runId)) {
                                publishLocked(snapshotValue.copy(frame = frame, droppedFrames = frameBuffer.droppedFrames()))
                            }
                        }
                    }
                }
                if (!isCurrent(runId)) return
                throw IllegalStateException("mirror video stream ended")
            } catch (failure: Throwable) {
                runCatching { opened?.close() }
                synchronized(lock) {
                    if (connection === opened) connection = null
                    if (!isCurrentLocked(runId)) return
                }
                if (attempt++ >= maxReconnectAttempts) {
                    synchronized(lock) {
                        if (isCurrentLocked(runId)) {
                            publishLocked(
                                snapshotValue.copy(
                                    state = EmbeddedMirrorState.FAILED,
                                    reconnectAttempt = attempt,
                                    error = diagnostic(failure),
                                ),
                            )
                            worker = null
                        }
                    }
                    return
                }
            }
        }
    }

    private fun sleepBeforeReconnect(): Boolean = try {
        Thread.sleep(reconnectDelay.toMillis().coerceAtLeast(0))
        isCurrent(generation.get())
    } catch (_: InterruptedException) {
        false
    }

    /**
     * Detaches the current connection/worker under [lock] without closing or joining either — the
     * actual close (which can block on adb subprocess cleanup) happens after the lock is released;
     * see the call sites in [start]/[stop].
     */
    private fun detachLocked(): EmbeddedMirrorConnection? {
        val previous = connection
        connection = null
        worker?.interrupt()
        worker = null
        return previous
    }

    private fun isCurrent(runId: Long): Boolean = synchronized(lock) { isCurrentLocked(runId) }

    private fun isCurrentLocked(runId: Long): Boolean = !stopping && generation.get() == runId

    private fun publishLocked(next: EmbeddedMirrorSnapshot) {
        snapshotValue = next
        runCatching { listener(next) }
    }

    private fun diagnostic(failure: Throwable): String = failure.message?.take(300)
        ?.ifBlank { null }
        ?: failure::class.simpleName
        ?: "Mirror transport failed"

    companion object {
        private const val MIRROR_STOP_JOIN_MS = 1_000L
    }
}

/** JavaCV/FFmpeg decoder for the raw H.264 stream exposed by the scrcpy server. */
internal class JavaCvH264Decoder : H264Decoder {
    override fun decode(input: InputStream, onFrame: (MirrorFrame) -> Unit) {
        // A live socket is never seekable or EOF-terminated. The default InputStream constructor
        // allocates a near-Integer.MAX_VALUE seek buffer; FFmpeg then tries to fill/seek it before
        // probing, so start() waits forever and the UI reports transport LIVE with no frame. A
        // zero maximum size disables that buffering, and start(false) avoids an EOF-dependent full
        // stream-info scan while still probing the H.264 codec.
        FFmpegFrameGrabber(input, 0).use { grabber ->
            grabber.format = "h264"
            grabber.setOption("fflags", "nobuffer")
            grabber.start(false)
            Java2DFrameConverter().use { converter ->
                while (true) {
                    val frame = grabber.grabImage() ?: break
                    val image = converter.convert(frame) ?: continue
                    onFrame(image.toMirrorFrame(frame))
                }
            }
            grabber.stop()
        }
    }

    private fun BufferedImage.toMirrorFrame(frame: Frame): MirrorFrame {
        val pixels = IntArray(width * height)
        getRGB(0, 0, width, height, pixels, 0, width)
        return MirrorFrame(width, height, pixels, frame.timestamp)
    }
}

/** A connection around the ADB-forwarded scrcpy socket. All subprocesses are stopped together. */
internal class AdbScrcpyTransport(
    private val tools: CaptureTools,
    private val runner: CaptureProcessRunner,
    private val assetResolver: ScrcpyServerAssetResolver = ScrcpyServerAssetResolver(),
    private val localRoot: File,
    private val socketConnector: (String, Int) -> Socket = { host, port ->
        Socket().also { it.connect(InetSocketAddress(host, port), SOCKET_CONNECT_TIMEOUT_MS) }
    },
    private val connectTimeout: Duration = Duration.ofSeconds(SOCKET_CONNECT_TIMEOUT_SECONDS),
    private val retryDelay: Duration = Duration.ofMillis(SOCKET_RETRY_DELAY_MS),
    private val serverStartupGrace: Duration = Duration.ofMillis(SERVER_STARTUP_GRACE_MS),
    private val assetIdGenerator: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) : EmbeddedMirrorTransport {
    override fun open(deviceSerial: String, options: MirrorStreamOptions): EmbeddedMirrorConnection {
        val asset = assetResolver.resolve()
        // Isolate every deploy. A reconnect or a second runtime can still be tearing down its old
        // server while this one is being deployed; sharing a fixed path lets old cleanup remove or
        // truncate the new jar. The device log showed the correct command with a zero-byte jar.
        val assetId = assetIdGenerator().filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        require(assetId.isNotBlank()) { "Embedded mirror asset id cannot be blank" }
        val localAsset = File(localRoot, "scrcpy-server-${asset.descriptor.version}-$assetId.jar")
        asset.materialize(localAsset)
        check(localAsset.length() == asset.bytes.size.toLong()) {
            "Unable to materialize embedded scrcpy server: expected ${asset.bytes.size} bytes, " +
                "wrote ${localAsset.length()}"
        }
        val remoteAsset = "/data/local/tmp/indagium-scrcpy-server-${asset.descriptor.version}-$assetId.jar"
        var localPort: Int? = null
        var server: com.indagium.capture.RunningCaptureProcess? = null
        return try {
            val pushed = runner.run(tools.adbSpec(deviceSerial, "push", localAsset.absolutePath, remoteAsset))
            check(!pushed.timedOut && pushed.exitCode == 0) {
                "Unable to deploy embedded scrcpy server: ${pushed.stderrText().trim().take(300)}"
            }
            verifyRemoteAsset(deviceSerial, remoteAsset, asset.descriptor.sha256)
            val forwarded = runner.run(tools.adbSpec(deviceSerial, "forward", "tcp:0", "localabstract:scrcpy"))
            check(!forwarded.timedOut && forwarded.exitCode == 0) {
                "Unable to create embedded scrcpy tunnel: ${forwarded.stderrText().trim().take(300)}"
            }
            localPort = forwarded.stdoutText().trim().lineSequence().lastOrNull()?.toIntOrNull()
            checkNotNull(localPort) { "adb did not return a local tunnel port" }
            val serverArguments = buildList {
                add(asset.descriptor.version)
                // We establish adb forward before launching the server. In forward mode the
                // server accepts sockets in a fixed order: video, then audio (if requested), then
                // control.
                add("tunnel_forward=true")
                add("control=true")
                add("audio=${options.audio}")
                add("video=true")
                // Frame-meta protocol (verified against the pinned v4.1 server's
                // device/Streamer.java / device/DesktopConnection.java): a 4-byte codec-id header
                // per stream, then a 12-byte PTS+flags header before every packet (and periodic
                // 12-byte session-meta records carrying width/height — see ScrcpyPacketReader's
                // doc). This replaces raw_stream=true, which stripped that metadata and is unusable
                // for muxing a durable recording (no PTS, no config/key-frame boundaries).
                // ScrcpyPacketReader parses this directly for recording; ScrcpyToAnnexBInputStream
                // re-flattens it back to a plain Annex-B stream for the mirror decoder, which never
                // sees this change.
                add("send_device_meta=false")
                add("send_frame_meta=true")
                add("send_stream_meta=true")
                add("send_dummy_byte=false")
                add("max_size=${options.maxSize}")
                add("max_fps=${options.maxFps}")
                // Server options parse this as an integer bit count; the host scrcpy CLI's
                // human-friendly `8M` suffix is not accepted by com.genymobile.scrcpy.Server.
                add("video_bit_rate=${options.serverVideoBitRateBitsPerSecond}")
                // A short keyframe interval keeps exact-start snapshot re-encodes cheap and bounds
                // how much of a live recording a fresh connection must wait to resync on — see
                // MirrorStreamOptions.keyFrameIntervalSeconds's doc. "float" matches the type
                // Android's KEY_I_FRAME_INTERVAL expects for this key.
                add("video_codec_options=i-frame-interval:float=${options.keyFrameIntervalSeconds}")
                if (options.audio) add("audio_codec=opus")
            }
            server = runner.start(
                tools.adbSpec(
                    deviceSerial,
                    listOf("shell", "CLASSPATH=$remoteAsset", "app_process", "/", "com.genymobile.scrcpy.Server") + serverArguments,
                ),
            )
            awaitServerStartup(server)
            // adb forward can accept the host-side TCP connection before the device-side
            // localabstract socket exists. Open video first, then audio (if requested), then
            // control, retrying transient refusal/EOF until the bounded deadline. Sockets already
            // opened are torn down on any later failure so the runtime can perform a clean
            // reconnect instead of leaking a partial connection.
            val opened = mutableListOf<Socket>()
            try {
                val video = connectSocketUntilReady("video", localPort, ::prepareVideoSocket)
                opened += video.socket
                val audio = if (options.audio) {
                    connectSocketUntilReady("audio", localPort) { socket -> PreparedSocket(socket, socket.getInputStream()) }
                        .also { opened += it.socket }
                } else {
                    null
                }
                val control = connectSocketUntilReady("control", localPort) { socket -> PreparedSocket(socket, socket.getInputStream()) }
                opened += control.socket
                localAsset.delete()
                AdbScrcpyConnection(
                    video.socket,
                    video.input,
                    audio?.socket,
                    audio?.input,
                    control.socket,
                    requireNotNull(server),
                    runner,
                    tools,
                    deviceSerial,
                    localPort,
                    remoteAsset,
                )
            } catch (failure: Throwable) {
                opened.forEach { socket -> runCatching { socket.close() } }
                throw failure
            }
        } catch (failure: Throwable) {
            runCatching { server?.close() }
            localPort?.let { port ->
                runCatching { runner.run(tools.adbSpec(deviceSerial, "forward", "--remove", "tcp:$port")) }
            }
            runCatching { runner.run(tools.adbSpec(deviceSerial, "shell", "rm", "-f", remoteAsset)) }
            localAsset.delete()
            throw failure
        }
    }

    private fun verifyRemoteAsset(deviceSerial: String, remoteAsset: String, expectedSha256: String) {
        val result = runner.run(tools.adbSpec(deviceSerial, "shell", "sha256sum", remoteAsset))
        val actualSha256 = result.stdoutText()
            .lineSequence()
            .map(String::trim)
            .mapNotNull { line -> line.split(Regex("\\s+"), limit = 2).firstOrNull() }
            .firstOrNull { it.matches(Regex("[0-9a-fA-F]{64}")) }
        check(!result.timedOut && result.exitCode == 0 && actualSha256.equals(expectedSha256, ignoreCase = true)) {
            val detail = (result.stderrText().ifBlank { result.stdoutText() }).trim().take(300)
            "Embedded scrcpy server deployment checksum mismatch: expected $expectedSha256, " +
                "device returned ${actualSha256 ?: "no checksum"}${if (detail.isBlank()) "" else " ($detail)"}"
        }
    }

    private fun awaitServerStartup(server: com.indagium.capture.RunningCaptureProcess) {
        val deadline = System.nanoTime() + serverStartupGrace.toNanos().coerceAtLeast(0)
        while (true) {
            if (Thread.currentThread().isInterrupted) {
                throw MirrorTransportCancelled("Embedded mirror server startup was cancelled")
            }
            check(server.isAlive) { "Embedded scrcpy server exited before its socket became ready" }
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) return
            val delayMs = minOf(
                SERVER_STARTUP_POLL_MS,
                (remainingNanos / NANOS_PER_MILLISECOND).coerceAtLeast(1),
            )
            try {
                Thread.sleep(delayMs)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw MirrorTransportCancelled("Embedded mirror server startup was cancelled", interrupted)
            }
        }
    }

    private fun connectSocketUntilReady(
        role: String,
        localPort: Int,
        prepare: (Socket) -> PreparedSocket,
    ): PreparedSocket {
        val deadline = System.nanoTime() + connectTimeout.toNanos().coerceAtLeast(0)
        while (true) {
            if (Thread.currentThread().isInterrupted) {
                throw MirrorTransportCancelled("Embedded mirror $role socket connection was cancelled")
            }
            try {
                val socket = socketConnector("127.0.0.1", localPort)
                return try {
                    if (socket.isClosed) throw EOFException("socket closed before $role became ready")
                    prepare(socket)
                } catch (failure: IOException) {
                    runCatching { socket.close() }
                    throw failure
                }
            } catch (failure: IOException) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) {
                    throw MirrorTransportConnectionException(
                        "Timed out waiting for embedded mirror $role socket after " +
                            "${connectTimeout.toMillis()} ms: ${failure.message ?: failure::class.simpleName}",
                        failure,
                    )
                }
                val delayMs = minOf(
                    retryDelay.toMillis().coerceAtLeast(1),
                    (remainingNanos / NANOS_PER_MILLISECOND).coerceAtLeast(1),
                )
                try {
                    Thread.sleep(delayMs)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw MirrorTransportCancelled(
                        "Embedded mirror $role socket connection was cancelled",
                        interrupted,
                    )
                }
            }
        }
    }

    companion object {
        private const val SOCKET_CONNECT_TIMEOUT_MS = 3_000
        private const val SOCKET_RETRY_DELAY_MS = 40L
        private const val SOCKET_CONNECT_TIMEOUT_SECONDS = 5L
        private const val SERVER_STARTUP_GRACE_MS = 350L
        private const val SERVER_STARTUP_POLL_MS = 25L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }

    private data class PreparedSocket(val socket: Socket, val input: InputStream)

    private fun prepareVideoSocket(socket: Socket): PreparedSocket {
        if (socket.isClosed) throw EOFException("video socket closed before first frame")
        val pushback = PushbackInputStream(socket.getInputStream(), 1)
        val hasTimeout = socket.isConnected
        if (hasTimeout) socket.soTimeout = VIDEO_READINESS_PROBE_TIMEOUT_MS
        try {
            when (val firstByte = try {
                pushback.read()
            } catch (_: SocketTimeoutException) {
                VIDEO_READINESS_TIMEOUT
            }) {
                -1 -> throw EOFException("video socket reached EOF before scrcpy became ready")
                VIDEO_READINESS_TIMEOUT -> Unit
                else -> pushback.unread(firstByte)
            }
            return PreparedSocket(socket, pushback)
        } finally {
            if (hasTimeout) runCatching { socket.soTimeout = 0 }
        }
    }
}

internal class MirrorTransportConnectionException(message: String, cause: Throwable) : IOException(message, cause)

internal class MirrorTransportCancelled(message: String, cause: Throwable? = null) : IOException(message, cause)

private class AdbScrcpyConnection(
    private val videoSocket: Socket,
    videoStream: InputStream,
    private val audioSocket: Socket?,
    audioStream: InputStream?,
    private val controlSocket: Socket,
    private val server: com.indagium.capture.RunningCaptureProcess,
    private val runner: CaptureProcessRunner,
    private val tools: CaptureTools,
    private val serial: String,
    private val localPort: Int,
    private val remoteAsset: String,
) : EmbeddedMirrorConnection {
    private val control: OutputStream = controlSocket.getOutputStream()
    override val videoInput: InputStream = videoStream
    override val audioInput: InputStream? = audioStream

    override fun sendControl(bytes: ByteArray) = synchronized(control) {
        control.write(bytes)
        control.flush()
    }

    override fun close() {
        runCatching { videoSocket.close() }
        runCatching { audioSocket?.close() }
        runCatching { controlSocket.close() }
        runCatching { server.terminate() }
        runCatching { server.close() }
        runCatching { runner.run(tools.adbSpec(serial, "forward", "--remove", "tcp:$localPort")) }
        runCatching { runner.run(tools.adbSpec(serial, "shell", "rm", "-f", remoteAsset)) }
    }
}

private const val VIDEO_READINESS_PROBE_TIMEOUT_MS = 250
private const val VIDEO_READINESS_TIMEOUT = -2
