package com.openlog.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.openlog.debug.AppLogger
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ShortBuffer
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

// Presentation tolerance for the audio-master-clock wait below: a video frame within this many
// microseconds of the current clock is shown immediately rather than sleeping for a
// sub-millisecond gap that would just burn CPU on wakeups.
private const val PRESENT_TOLERANCE_US = 1_000L

// Idle poll interval while paused/not-yet-playing, and the cap on any single sleep while waiting
// for a video frame's presentation time — bounds how long play()/pause()/seek()/close() can take
// to actually be observed by the decode loop.
private const val IDLE_POLL_MS = 15L
private const val MAX_WAIT_SLEEP_MS = 20L

// javacv/FFmpeg timestamps (Frame.timestamp, getLengthInTime, setTimestamp) are always
// microseconds; every positionMs/durationMs field this controller exposes is milliseconds.
private const val MICROS_PER_MS = 1_000L

// System.nanoTime() is nanoseconds; currentClockUs()'s wall-clock fallback converts to
// microseconds. Numerically identical to MICROS_PER_MS but a distinct unit conversion, kept as
// its own constant so a future change to one doesn't silently also change the other.
private const val NANOS_PER_MICRO = 1_000L

private const val MIN_PLAYBACK_RATE = 0.1f
private const val MAX_PLAYBACK_RATE = 8f

// 16-bit PCM: grabber.setSampleFormat(AV_SAMPLE_FMT_S16) below guarantees Frame.samples is a
// ShortBuffer, so each sample is exactly 2 bytes for both the AudioFormat and the little-endian
// byte-packing loop in writeAudio.
private const val AUDIO_SAMPLE_SIZE_BITS = 16
private const val BYTES_PER_SAMPLE = 2
private const val BITS_PER_BYTE = 8
private const val BYTE_MASK = 0xFF

/**
 * Behind-an-interface video playback engine (plan doc's Task A "player core"). The concrete
 * implementation ([FfmpegVideoPlayerController]) wraps `FFmpegFrameGrabber` (bytedeco/javacv) on a
 * background thread: video [Frame]s become Compose [ImageBitmap]s, audio `Frame`s feed a JavaSound
 * `SourceDataLine`, and the audio line's own playback pace is the clock video frames wait on
 * before being presented — the standard "audio-master-clock" A/V sync pattern (see the plan doc's
 * "Decisions taken").
 *
 * Kept as an interface (not exposed as a concrete class from AppState) so ui/AppState.kt's pure
 * mapping helpers and the autosave round-trip tests never need real FFmpeg natives on the test
 * classpath — see AppState's `videoControllerFactory` constructor parameter.
 */
interface VideoPlayerController {
    /** Latest decoded video frame, or null before the first frame decodes / after [close]. */
    val currentFrame: ImageBitmap?
    val positionMs: Long
    val durationMs: Long
    val isPlaying: Boolean

    /** Human-readable failure reason (unsupported/corrupt file, no audio device, ...) — the file
     *  failing to open is surfaced here, never as a crash. Null while healthy. */
    val error: String?

    fun play()

    fun pause()

    fun seek(ms: Long)

    /** Playback speed multiplier. MVP limitation (documented, not silently wrong): this only
     *  re-paces the WALL-CLOCK fallback used while no audio frame has been decoded yet (e.g.
     *  video-only content); once a real audio Frame lands, the audio hardware — which always
     *  plays at 1x — becomes the clock again. Real variable-speed audio needs resampling, out of
     *  scope for this substrate. */
    fun setRate(rate: Float)

    /** PNG bytes of whatever [currentFrame] currently shows. Null before the first frame decodes. */
    fun grabCurrentFrame(): ByteArray?

    /** Headless single-frame grab via a SEPARATE, lightweight grabber that opens, seeks, grabs one
     *  video frame, and releases — never touches this controller's playing pipeline, so it's safe
     *  to call mid-playback (used by MCP's get_video_frame). Null on any failure. */
    fun grabFrameAt(ms: Long): ByteArray?

    /** Stops the decode thread and releases the grabber/audio line. Safe to call more than once. */
    fun close()
}

/** Default factory used by AppState.videoController — a thin function reference so tests can
 *  substitute a fake [VideoPlayerController] instead (see AppState's videoControllerFactory). */
fun defaultVideoPlayerController(path: String): VideoPlayerController = FfmpegVideoPlayerController(path)

private class FfmpegVideoPlayerController(private val path: String) : VideoPlayerController {
    private val grabber = FFmpegFrameGrabber(path)
    private val converter = Java2DFrameConverter()

    private var _currentFrame by mutableStateOf<ImageBitmap?>(null)
    private var _positionMs by mutableStateOf(0L)
    private var _durationMs by mutableStateOf(0L)
    private var _isPlaying by mutableStateOf(false)
    private var _error by mutableStateOf<String?>(null)

    override val currentFrame: ImageBitmap? get() = _currentFrame
    override val positionMs: Long get() = _positionMs
    override val durationMs: Long get() = _durationMs
    override val isPlaying: Boolean get() = _isPlaying
    override val error: String? get() = _error

    // Written only from the decode thread; read from any thread (Compose recomposition, MCP,
    // grabCurrentFrame callers) — a plain @Volatile is enough since it's always assigned wholesale
    // (a fresh BufferedImage), never mutated in place.
    @Volatile private var lastImage: BufferedImage? = null
    private var audioLine: SourceDataLine? = null

    // Cross-thread commands: play()/pause()/seek()/setRate()/close() are called from the UI
    // thread; the decode loop (below) polls these volatiles at the top of every iteration rather
    // than touching `grabber` directly from the caller's thread — FFmpegFrameGrabber is not
    // safe for concurrent grab+seek.
    @Volatile private var playRequested = false

    @Volatile private var pendingSeekUs: Long? = null

    @Volatile private var rate = 1f

    @Volatile private var closed = false

    // Audio-master clock: -1 means "no audio frame decoded since the last seek/resume", which
    // routes currentClockUs() through the wall-clock fallback until a real audio Frame lands.
    @Volatile private var audioClockUs = -1L
    private var playbackStartNanos = 0L
    private var playbackStartTimestampUs = 0L
    private var hasAudioStream = false

    init {
        // Not kept as a property (detekt's UnusedPrivateProperty would flag it — nothing ever
        // needs to join/interrupt it, it's a daemon thread that self-stops via `closed`): starting
        // it here, as the last statement in the constructor, is enough.
        Thread({ runLoop() }, "openlog-video-decode").apply { isDaemon = true }.start()
    }

    override fun play() {
        if (playRequested || closed) return
        playbackStartNanos = System.nanoTime()
        playbackStartTimestampUs = _positionMs * MICROS_PER_MS
        audioClockUs = -1L
        playRequested = true
        _isPlaying = true
        runCatching { audioLine?.start() }
    }

    override fun pause() {
        playRequested = false
        _isPlaying = false
        runCatching { audioLine?.stop() }
    }

    override fun seek(ms: Long) {
        pendingSeekUs = (ms * MICROS_PER_MS).coerceAtLeast(0)
    }

    override fun setRate(rate: Float) {
        this.rate = rate.coerceIn(MIN_PLAYBACK_RATE, MAX_PLAYBACK_RATE)
    }

    override fun grabCurrentFrame(): ByteArray? = lastImage?.let(::encodePng)

    override fun grabFrameAt(ms: Long): ByteArray? = runCatching {
        FFmpegFrameGrabber(path).use { g ->
            g.start()
            g.setTimestamp((ms * MICROS_PER_MS).coerceAtLeast(0))
            val frame = g.grabImage() ?: return@use null
            Java2DFrameConverter().use { c -> c.convert(frame)?.let(::encodePng) }
        }
    }.onFailure { AppLogger.error("video", "grabFrameAt failed for $path", it) }.getOrNull()

    override fun close() {
        closed = true
        playRequested = false
    }

    // ── Decode loop (background thread) ─────────────────────────────
    private fun runLoop() {
        if (!openGrabber()) return
        while (!closed) decodeStep()
        releaseAll()
    }

    // One step of the decode loop, pulled out of runLoop's while so every early-exit is a plain
    // `return` from this function rather than a `continue` in the loop itself (detekt's
    // LoopWithTooManyJumpStatements — a loop body that's just one function call has none).
    private fun decodeStep() {
        if (applyPendingSeek()) return
        if (!playRequested) {
            sleepQuietly(IDLE_POLL_MS)
            return
        }
        val frame = grabNextFrame() ?: return
        presentFrame(frame)
    }

    private fun openGrabber(): Boolean = runCatching {
        grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_S16)
        grabber.start()
        _durationMs = grabber.lengthInTime / MICROS_PER_MS
        hasAudioStream = grabber.hasAudio()
        if (hasAudioStream) openAudioLine()
        true
    }.getOrElse { t ->
        AppLogger.error("video", "Failed to open $path", t)
        _error = t.message ?: t::class.simpleName ?: "Failed to open video"
        false
    }

    /** Returns true (and restarts the loop) if a seek was applied this iteration. */
    private fun applyPendingSeek(): Boolean {
        val target = pendingSeekUs ?: return false
        pendingSeekUs = null
        runCatching { grabber.setTimestamp(target) }
            .onFailure { AppLogger.warn("video", "seek failed for $path", it) }
        runCatching { audioLine?.flush() }
        audioClockUs = -1L
        playbackStartNanos = System.nanoTime()
        playbackStartTimestampUs = target
        _positionMs = target / MICROS_PER_MS
        return true
    }

    private fun grabNextFrame(): Frame? = runCatching { grabber.grab() }
        .onFailure {
            AppLogger.error("video", "grab failed for $path", it)
            _error = it.message ?: "Playback error"
            playRequested = false
            _isPlaying = false
        }
        .getOrNull()
        ?: run {
            // Null with no exception means end of stream, not a failure.
            if (playRequested) { playRequested = false; _isPlaying = false }
            null
        }

    private fun presentFrame(frame: Frame) {
        when {
            !frame.image.isNullOrEmpty() -> presentVideoFrame(frame)
            !frame.samples.isNullOrEmpty() -> presentAudioFrame(frame)
            else -> Unit // data/subtitle frames — nothing to do with them here
        }
    }

    private fun presentVideoFrame(frame: Frame) {
        waitUntilPresentationTime(frame.timestamp)
        if (closed || pendingSeekUs != null) return // superseded by a seek while we were waiting
        val image = runCatching { converter.convert(frame) }.getOrNull() ?: return
        lastImage = image
        runCatching { _currentFrame = image.toComposeImageBitmap() }
        _positionMs = frame.timestamp / MICROS_PER_MS
    }

    private fun presentAudioFrame(frame: Frame) {
        writeAudio(frame)
        audioClockUs = frame.timestamp
    }

    // Busy-waits (in short bounded sleeps, not a tight spin) until the audio-master clock reaches
    // [targetUs], or until closed/paused/re-seeked interrupts the wait — those three cases all
    // need to abandon a stale wait rather than block the whole controller.
    private fun waitUntilPresentationTime(targetUs: Long) {
        while (!closed && playRequested && pendingSeekUs == null) {
            val remaining = targetUs - currentClockUs()
            if (remaining <= PRESENT_TOLERANCE_US) return
            sleepQuietly((remaining / MICROS_PER_MS).coerceIn(1, MAX_WAIT_SLEEP_MS))
        }
    }

    private fun currentClockUs(): Long {
        val audio = audioClockUs
        if (hasAudioStream && audio >= 0) return audio
        val elapsedUs = (System.nanoTime() - playbackStartNanos) / NANOS_PER_MICRO
        return playbackStartTimestampUs + (elapsedUs * rate).toLong()
    }

    private fun openAudioLine() {
        runCatching {
            val format = AudioFormat(grabber.sampleRate.toFloat(), AUDIO_SAMPLE_SIZE_BITS, grabber.audioChannels, true, false)
            val info = DataLine.Info(SourceDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) {
                hasAudioStream = false
                return
            }
            audioLine = (AudioSystem.getLine(info) as SourceDataLine).apply { open(format) }
        }.onFailure {
            AppLogger.warn("video", "No audio output line for $path — playing video-only", it)
            hasAudioStream = false
        }
    }

    private fun writeAudio(frame: Frame) {
        val line = audioLine ?: return
        val samples = frame.samples?.getOrNull(0) as? ShortBuffer ?: return
        samples.rewind()
        val bytes = ByteArray(samples.remaining() * BYTES_PER_SAMPLE)
        var i = 0
        while (samples.hasRemaining()) {
            val s = samples.get().toInt()
            bytes[i] = (s and BYTE_MASK).toByte()
            bytes[i + 1] = ((s shr BITS_PER_BYTE) and BYTE_MASK).toByte()
            i += BYTES_PER_SAMPLE
        }
        // Blocking write — SourceDataLine.write() only returns once there's buffer room, which is
        // exactly what makes the audio hardware the natural pace-setter for the whole loop.
        runCatching { line.write(bytes, 0, bytes.size) }
    }

    private fun encodePng(image: BufferedImage): ByteArray? = runCatching {
        ByteArrayOutputStream().use { out -> ImageIO.write(image, "png", out); out.toByteArray() }
    }.getOrNull()

    private fun releaseAll() {
        runCatching { audioLine?.stop() }
        runCatching { audioLine?.close() }
        runCatching { grabber.release() }
    }

    private fun sleepQuietly(ms: Long) {
        runCatching { Thread.sleep(ms.coerceAtLeast(1)) }
    }
}
