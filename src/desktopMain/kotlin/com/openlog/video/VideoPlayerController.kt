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
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

// Presentation tolerance for the timestamped wall-clock wait below: a video frame within this
// many microseconds of the current clock is shown immediately rather than sleeping for a
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
 * The transport timeline is authoritative for both the slider and video-to-log mapping. A normal
 * decode must never move it backwards: that would make a late frame look like a seek. A deliberate
 * seek is the one exception and starts a new timeline epoch.
 *
 * This small type is deliberately independent of FFmpeg so its ordering contract can be tested
 * without native video libraries.
 */
internal class PlaybackTimeline(initialUs: Long = 0L) {
    var positionUs: Long = initialUs.coerceAtLeast(0L)
        private set

    fun seek(targetUs: Long): Long = targetUs.coerceAtLeast(0L).also { positionUs = it }

    fun advance(candidateUs: Long): Long = maxOf(positionUs, candidateUs.coerceAtLeast(0L)).also { positionUs = it }
}

/**
 * Behind-an-interface video playback engine (plan doc's Task A "player core"). The concrete
 * implementation ([FfmpegVideoPlayerController]) wraps `FFmpegFrameGrabber` (bytedeco/javacv) on a
 * background thread: video [Frame]s become Compose [ImageBitmap]s and audio `Frame`s feed a
 * JavaSound `SourceDataLine`. Presentation uses a timestamped wall clock. This is deliberately
 * correctness-first: a single FFmpeg grabber cannot wait for an audio frame that is still behind a
 * video frame in that same decode stream, which was an audio-clock deadlock in the old design.
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

    /** Playback speed multiplier. MVP limitation (documented, not silently wrong): it re-paces
     *  video presentation but JavaSound audio remains at 1x. Real variable-speed audio needs
     *  resampling, out of scope for this substrate. */
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

/**
 * Stands in for a real controller when AppState.videoController can't even get as far as opening
 * one — currently the one gap in that function's own "non-null whenever a video is attached"
 * contract: a [com.openlog.model.VideoSource.ArchiveEntry] whose archive has since been moved or
 * deleted (a very normal workflow for a disposable bug-report zip download, unlike an explicitly
 * attached local recording a user wouldn't casually delete) fails before [FfmpegVideoPlayerController]
 * ever gets a path to open. Without this, [error] had nowhere to live and the video panel/context-menu
 * actions silently acted as if no video were attached at all, instead of showing the same
 * "Couldn't play this video" failure state a broken local file already gets via its own
 * FFmpeg-reported [error]. Every mutator is a no-op; there is nothing to play, seek, or grab.
 */
internal class FailedVideoPlayerController(override val error: String) : VideoPlayerController {
    override val currentFrame: ImageBitmap? = null
    override val positionMs: Long = 0L
    override val durationMs: Long = 0L
    override val isPlaying: Boolean = false

    override fun play() = Unit

    override fun pause() = Unit

    override fun seek(ms: Long) = Unit

    override fun setRate(rate: Float) = Unit

    override fun grabCurrentFrame(): ByteArray? = null

    override fun grabFrameAt(ms: Long): ByteArray? = null

    override fun close() = Unit
}

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

    private data class SeekCommand(val epoch: Long, val targetUs: Long)

    // A request's epoch makes it impossible for an already-decoded frame to overwrite a newer
    // seek. The lock also keeps the UI thread's immediate position update and the decoder's frame
    // publication in one ordering domain.
    private val presentationLock = Any()
    private val seekEpoch = AtomicLong(0L)
    private var pendingSeek: SeekCommand? = null
    private val timeline = PlaybackTimeline()

    @Volatile private var resetPlaybackClockRequested = false

    @Volatile private var pendingRate: Float? = null

    // A newly-opened grabber has no decoded image yet. Keep this flag on the decode thread so it
    // can acquire and publish the first video frame even while playback is paused. Seeking is
    // handled directly in applyPendingSeek() for the same reason.
    private var needsInitialFrame = true

    @Volatile private var rate = 1f

    @Volatile private var closed = false

    private var playbackStartNanos = 0L
    private var playbackStartTimestampUs = 0L
    private var hasAudioStream = false

    private val decodeThread = Thread({ runLoop() }, "openlog-video-decode").apply {
        isDaemon = true
    }

    init {
        decodeThread.start()
    }

    override fun play() {
        if (playRequested || closed) return
        playRequested = true
        // The decoder owns the clock fields. Rebase on its next step so a Play immediately after
        // a seek cannot use a stale frame timestamp as its origin.
        resetPlaybackClockRequested = true
        _isPlaying = true
        runCatching { audioLine?.start() }
        wakeDecoder()
    }

    override fun pause() {
        playRequested = false
        _isPlaying = false
        runCatching { audioLine?.stop() }
        runCatching { audioLine?.flush() }
        wakeDecoder()
    }

    override fun seek(ms: Long) {
        val targetUs = ms.coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / MICROS_PER_MS) * MICROS_PER_MS
        val command = SeekCommand(seekEpoch.incrementAndGet(), targetUs)
        synchronized(presentationLock) {
            // Calls can arrive from more than one caller. An older caller which was delayed until
            // after a newer one must not replace that newer command.
            if (command.epoch >= seekEpoch.get()) {
                pendingSeek = command
                publishSeekPositionLocked(command)
            }
        }
        // The decoder normally idles while paused. Interrupting that short idle sleep makes a
        // paused scrub publish its frame promptly instead of waiting for a later Play action.
        wakeDecoder()
    }

    override fun setRate(rate: Float) {
        pendingRate = rate.coerceIn(MIN_PLAYBACK_RATE, MAX_PLAYBACK_RATE)
        wakeDecoder()
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
        _isPlaying = false
        // Closing the audio line wakes a blocking SourceDataLine.write; interrupt wakes an idle
        // poll/sleep. The decode thread owns grabber.release() so FFmpeg is never released while
        // another thread is grabbing from it.
        runCatching { audioLine?.close() }
        decodeThread.interrupt()
    }

    // ── Decode loop (background thread) ─────────────────────────────
    private fun runLoop() {
        try {
            if (!openGrabber()) return
            while (!closed) decodeStep()
        } finally {
            releaseAll()
        }
    }

    // One step of the decode loop, pulled out of runLoop's while so every early-exit is a plain
    // `return` from this function rather than a `continue` in the loop itself (detekt's
    // LoopWithTooManyJumpStatements — a loop body that's just one function call has none).
    private fun decodeStep() {
        if (applyPendingSeek()) return
        if (needsInitialFrame) {
            needsInitialFrame = false
            presentImageImmediately(isInitialFrame = true)
            return
        }
        if (!playRequested) {
            applyPendingRateWhilePaused()
            sleepQuietly(IDLE_POLL_MS)
            return
        }
        applyPendingPlaybackClockChanges()
        // Capture before the blocking grab. If a seek is requested while FFmpeg is decoding this
        // packet, the returned frame belongs to the old epoch and must be discarded rather than
        // being presented under the newly requested timeline position.
        val epoch = seekEpoch.get()
        val frame = grabNextFrame() ?: return
        presentFrame(frame, epoch)
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
        val command = synchronized(presentationLock) {
            pendingSeek?.also { pendingSeek = null }
        } ?: return false
        if (!isCurrentEpoch(command.epoch)) return true
        runCatching { grabber.setTimestamp(command.targetUs) }
            .onFailure { AppLogger.warn("video", "seek failed for $path", it) }
        runCatching { audioLine?.flush() }
        if (!isCurrentEpoch(command.epoch)) return true
        playbackStartNanos = System.nanoTime()
        playbackStartTimestampUs = command.targetUs
        // Use grabImage rather than the normal interleaved grab loop: when paused we need a
        // video frame now, and waiting to encounter/drain audio packets would leave the preview
        // stale (or block on an audio line). The grabber remains exclusively owned by this thread.
        presentImageImmediately(isInitialFrame = false, epoch = command.epoch, minimumTimestampUs = command.targetUs)
        return true
    }

    /**
     * Decodes and publishes the next image without playback-clock pacing. Used for readiness and
     * paused seeks, so opening a video and moving the slider both show a picture before Play.
     */
    private fun presentImageImmediately(
        isInitialFrame: Boolean,
        epoch: Long = seekEpoch.get(),
        minimumTimestampUs: Long? = null,
    ) {
        val frame = runCatching { grabImageAtOrAfter(minimumTimestampUs, epoch) }
            .onFailure {
                AppLogger.error("video", "image grab failed for $path", it)
                _error = it.message ?: "Playback error"
            }
            .getOrNull()

        if (frame == null) {
            if (isInitialFrame && !closed) {
                _error = "Video contains no decodable image frames"
            }
            return
        }
        if (closed || !isCurrentEpoch(epoch)) return

        val image = runCatching { converter.convert(frame) }.getOrNull() ?: return
        publishFrame(image, frame.timestamp, epoch)
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

    private fun presentFrame(frame: Frame, epoch: Long) {
        when {
            !frame.image.isNullOrEmpty() -> presentVideoFrame(frame, epoch)
            !frame.samples.isNullOrEmpty() -> presentAudioFrame(frame)
            else -> Unit // data/subtitle frames — nothing to do with them here
        }
    }

    private fun presentVideoFrame(frame: Frame, epoch: Long) {
        waitUntilPresentationTime(frame.timestamp, epoch)
        if (closed || !playRequested || !isCurrentEpoch(epoch)) return // superseded while waiting
        val image = runCatching { converter.convert(frame) }.getOrNull() ?: return
        publishFrame(image, frame.timestamp, epoch)
    }

    private fun presentAudioFrame(frame: Frame) {
        writeAudio(frame)
    }

    // Waits in short bounded sleeps (not a tight spin) until [targetUs] reaches the playback
    // clock, or until closed/paused/re-seeked. Crucially this clock never depends on a future
    // decoded audio frame, so interleaved video/audio streams continue making forward progress.
    private fun waitUntilPresentationTime(targetUs: Long, epoch: Long) {
        while (!closed && playRequested && isCurrentEpoch(epoch)) {
            // The frame is already decoded, so the wall clock may advance smoothly toward its
            // timestamp without the slider getting ahead of the picture it is waiting to show.
            publishClockPosition(targetUs, epoch)
            val remaining = targetUs - currentClockUs()
            if (remaining <= PRESENT_TOLERANCE_US) return
            sleepQuietly((remaining / MICROS_PER_MS).coerceIn(1, MAX_WAIT_SLEEP_MS))
        }
    }

    private fun currentClockUs(): Long {
        val elapsedUs = (System.nanoTime() - playbackStartNanos) / NANOS_PER_MICRO
        return playbackStartTimestampUs + (elapsedUs * rate).toLong()
    }

    private fun applyPendingRateWhilePaused() {
        pendingRate?.let {
            rate = it
            pendingRate = null
        }
    }

    private fun applyPendingPlaybackClockChanges() {
        pendingRate?.let { newRate ->
            val position = currentClockUs()
            rate = newRate
            pendingRate = null
            playbackStartNanos = System.nanoTime()
            playbackStartTimestampUs = position
        }
        if (resetPlaybackClockRequested) {
            resetPlaybackClockRequested = false
            synchronized(presentationLock) {
                playbackStartNanos = System.nanoTime()
                playbackStartTimestampUs = timeline.positionUs
            }
        }
    }

    /** Grabs past a keyframe before the requested timestamp, so a seek cannot publish backwards. */
    private fun grabImageAtOrAfter(minimumTimestampUs: Long?, epoch: Long): Frame? {
        var frame = grabber.grabImage()
        while (frame != null && minimumTimestampUs != null && frame.timestamp < minimumTimestampUs && isCurrentEpoch(epoch)) {
            frame = grabber.grabImage()
        }
        return frame
    }

    private fun isCurrentEpoch(epoch: Long): Boolean = seekEpoch.get() == epoch

    private fun publishSeekPositionLocked(command: SeekCommand) {
        if (!isCurrentEpoch(command.epoch)) return
        _positionMs = timeline.seek(command.targetUs) / MICROS_PER_MS
    }

    private fun publishClockPosition(frameTimestampUs: Long, epoch: Long) {
        publishPosition(frameTimestampUs.coerceAtMost(currentClockUs()), epoch)
    }

    private fun publishFrame(image: BufferedImage, timestampUs: Long, epoch: Long) {
        synchronized(presentationLock) {
            if (closed || !isCurrentEpoch(epoch)) return
            // Keep one timestamp for the bitmap and controller position. Do not merely retain the
            // newer position for an old bitmap: that would leave logs following one moment while
            // the video visibly shows another. A backward frame is stale and is discarded.
            if (timestampUs.coerceAtLeast(0L) < timeline.positionUs) return
            val positionUs = timeline.advance(timestampUs)
            lastImage = image
            runCatching { _currentFrame = image.toComposeImageBitmap() }
            _positionMs = positionUs / MICROS_PER_MS
            playbackStartNanos = System.nanoTime()
            playbackStartTimestampUs = positionUs
        }
    }

    private fun publishPosition(candidateUs: Long, epoch: Long) {
        synchronized(presentationLock) {
            if (closed || !isCurrentEpoch(epoch)) return
            _positionMs = timeline.advance(candidateUs) / MICROS_PER_MS
        }
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
        // Do not let JavaSound hold the sole grabber-owning thread in a blocking write. That would
        // make pause/seek unable to reach applyPendingSeek(), especially when pausing prevents the
        // audio device from draining. Dropping the portion that will not fit is preferable to a
        // stale video preview; video has its own timestamp clock and software decode continues.
        val writableBytes = bytes.size.coerceAtMost(line.available().coerceAtLeast(0))
        if (writableBytes > 0) runCatching { line.write(bytes, 0, writableBytes) }
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

    private fun wakeDecoder() {
        decodeThread.interrupt()
    }
}
