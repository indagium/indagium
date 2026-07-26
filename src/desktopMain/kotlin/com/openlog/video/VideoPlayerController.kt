package com.openlog.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.openlog.debug.AppLogger
import org.bytedeco.ffmpeg.avcodec.AVPacket
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
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10

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

// scanDurationMs converts an AVStream time_base (rational seconds per tick) to microseconds, not
// milliseconds — kept distinct from MICROS_PER_MS so that conversion's intent reads standalone.
private const val MICROS_PER_SECOND = 1_000_000L

// resolveScannedDurationMs's packet-count/frame-rate fallback (step 3) converts a packets-per-
// second rate straight into milliseconds — no microseconds step involved — so this is deliberately
// its own constant rather than reusing MICROS_PER_SECOND (a different unit) or MICROS_PER_MS (a
// different conversion direction).
private const val MS_PER_SECOND = 1_000.0

// System.nanoTime() is nanoseconds; currentClockUs()'s wall-clock fallback converts to
// microseconds. Numerically identical to MICROS_PER_MS but a distinct unit conversion, kept as
// its own constant so a future change to one doesn't silently also change the other.
private const val NANOS_PER_MICRO = 1_000L

private const val MIN_PLAYBACK_RATE = 0.1f
private const val MAX_PLAYBACK_RATE = 8f

// A decoded video frame whose presentation timestamp already trails the playback clock by more
// than this is skipped instead of paid for with a convert()+publish() — converting it would only
// delay showing a frame that matters more (the next one), digging an already-behind pipeline (or a
// fast-forward rate) further behind. 100ms is roughly 3 frame periods at 30fps: generous enough
// that ordinary decode jitter or a GC pause doesn't cost a visible drop, small enough that a
// genuinely-behind pipeline (or rate > 1, which relies on this path to skip frames at all — see
// setRate) catches back up within a handful of frames rather than converting nothing usefully.
private const val FRAME_DROP_LATENESS_US = 100_000L

// Caps shouldDropLateFrame's run of consecutive drops so the picture always eventually updates,
// even under a persistently overloaded decode pipeline. Must comfortably clear the drop run a
// legitimate MAX_PLAYBACK_RATE (8x) needs — at 8x roughly 7 of every 8 frames are expected to drop
// by design — so the safety valve never fights ordinary fast playback, while still bounding how
// long the picture can go without a single visible update.
private const val MAX_CONSECUTIVE_FRAME_DROPS = 15

// Decoded/converted frame size is capped on its long edge before playback conversion (see
// openGrabber's downscale). Java2DFrameConverter.convert() and toComposeImageBitmap() each pay for
// a full-resolution copy of every frame; at a 4K+ screen-recording source that is tens of MB per
// frame, single-threaded, on the decode thread. Pushing the resize into native swscale (by setting
// grabber.imageWidth/imageHeight) instead shrinks both Java-side copies by the same factor. 1280px
// is comfortably more than this app's video panel ever renders at, so it costs no visible quality.
private const val MAX_DECODE_LONG_EDGE_PX = 1280

// Below this linear gain, treat the line as silent rather than computing a (very large negative)
// decibel value from log10(0..epsilon) — MASTER_GAIN's own minimum already represents "silent" for
// this hardware line, so clamping to it directly avoids depending on log10's behavior near zero.
private const val SILENT_VOLUME_THRESHOLD = 0.0001f
private const val DB_PER_DECADE = 20f

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
 * Decides whether a decoded video frame should be skipped rather than converted and shown, given
 * how far behind the playback clock its presentation timestamp already is. [latenessUs] is
 * `currentClock - frame.timestamp` (positive once the frame's moment has passed);
 * [consecutiveDrops] is how many frames in a row were already dropped since the last one that WAS
 * shown.
 *
 * This is the mechanism that makes [FfmpegVideoPlayerController.setRate] actually change playback
 * speed rather than only being able to slow it down: at rate > 1 the clock outruns the frames'
 * natural spacing by design, so this function is what skips the ones the clock has already passed.
 * At rate == 1 with a decode pipeline that's keeping up, `latenessUs` stays near zero and nothing
 * is dropped.
 *
 * Deliberately independent of FFmpeg/Compose types — like [PlaybackTimeline] — so the drop
 * threshold and the "always make forward visual progress" safety valve can be unit tested without
 * native video libraries.
 */
internal fun shouldDropLateFrame(latenessUs: Long, consecutiveDrops: Int): Boolean =
    latenessUs > FRAME_DROP_LATENESS_US && consecutiveDrops < MAX_CONSECUTIVE_FRAME_DROPS

/**
 * Step 4 of the duration-recovery chain (see [scanDurationMs]'s KDoc for steps 1-3): raises
 * [currentDurationMs] to [positionMs] whenever real playback has moved past what's currently
 * believed to be the file's length. This is what makes an approximate or even entirely absent
 * duration estimate safe to show at all — [resolveScannedDurationMs]'s packet-count/frame-rate
 * fallback (step 3) is a guess by construction (FFmpeg guesses a raw stream's frame rate), and
 * even the exact packet-timestamp scan (step 2) is a background scan racing the decode thread's
 * own first published positions — so no earlier step can be trusted to never undershoot. Called on
 * every published position (one comparison — cheap enough to pay per frame) rather than only while
 * a fallback is active, so it also covers the should-be-rare case where every recovery step above
 * still yields 0: the timeline becomes usable the moment the video actually plays, rather than
 * staying stuck at "--:--" for a file no automated technique could measure up front. Never lets the
 * duration regress (see [FfmpegVideoPlayerController]'s use at both its position-publish sites and
 * its duration-recovery-scan publish, the latter guarding against the scan's own exact/approximate
 * value arriving lower than a position playback had already reached in the meantime).
 *
 * Deliberately independent of FFmpeg/Compose types, like [PlaybackTimeline]/[shouldDropLateFrame]
 * above, so growth's own "never regress, only grow" contract can be unit tested without native
 * video libraries.
 */
internal fun growDurationIfNeeded(currentDurationMs: Long, positionMs: Long): Long = maxOf(currentDurationMs, positionMs)

/**
 * Raw signals from ONE packet-only pass over a container — no image/audio decoding — that
 * [resolveScannedDurationMs] combines into a duration a format header didn't report:
 *  - [maxEndUs]: the maximum resolvable packet end timestamp seen (microseconds), or 0 if no
 *    packet carried a usable pts/dts at all.
 *  - [videoPacketCount]: how many of the packets walked belonged to the video stream.
 *  - [videoFrameRate]: `grabber.videoFrameRate` as read from this same short-lived scan grabber
 *    (an instant header/stream property, not something the pass has to compute).
 *
 * A single type carrying all three instead of [scanDurationMs] returning a Long directly is what
 * lets steps 2 and 3 of that function's chain share one walk of the file rather than reading it
 * twice — the timestamp total, the video-stream packet count, and the frame rate are three numbers
 * pulled from the exact same pass, so there is no reason to open the file a second time just to
 * count packets after already having walked every one of them for timestamps.
 */
internal data class PacketScanResult(val maxEndUs: Long, val videoPacketCount: Long, val videoFrameRate: Double)

/**
 * Walks every packet in [path] exactly once — no image/audio decoding, so this is I/O-bound only
 * (measured ~0-21ms against multi-second fixtures), cheap enough to walk the whole file — collecting
 * the raw signals [resolveScannedDurationMs] turns into a duration. Kept separate from that
 * resolving logic so a test can assert this scan's own precondition directly (e.g. "this fixture
 * truly has no packet timestamps, only packets") without that assertion being masked by the
 * fallback [scanDurationMs] applies on top.
 *
 * Deliberately a plain top-level function taking just a path (unlike [PlaybackTimeline]/
 * [shouldDropLateFrame]/[growDurationIfNeeded] above, this one DOES need real FFmpeg natives, which
 * the test classpath has) rather than a method on [FfmpegVideoPlayerController] — so a test can call
 * it directly against a committed fixture without spinning up that class's decode thread.
 * [isCancelled] is the one seam the controller needs and a plain path-only test doesn't: it lets the
 * controller's duration-recovery thread (see `maybeStartDurationRecoveryScan`) stop walking a file
 * whose controller has since been [FfmpegVideoPlayerController.close]d, without this function taking
 * on any dependency on that class's lifecycle.
 */
internal fun scanPackets(path: String, isCancelled: () -> Boolean = { false }): PacketScanResult {
    FFmpegFrameGrabber(path).use { grabber ->
        grabber.start()
        var lastEndUs = 0L
        var videoPacketCount = 0L
        while (!isCancelled()) {
            val packet = grabber.grabPacket() ?: break
            if (packet.stream_index() == grabber.videoStream) videoPacketCount++
            val endUs = packetEndUs(packet, grabber) ?: continue
            if (endUs > lastEndUs) lastEndUs = endUs
        }
        return PacketScanResult(lastEndUs, videoPacketCount, grabber.videoFrameRate)
    }
}

/**
 * This packet's presentation end time in microseconds, or null when it carries nothing usable —
 * neither `pts` nor `dts` set (kept out of [scanPackets]'s own loop so that function has only the
 * two jump statements detekt's LoopWithTooManyJumpStatements allows: `break` on end-of-stream,
 * `continue` on a packet [scanPackets] can't use for the timestamp total — it is still counted
 * towards [PacketScanResult.videoPacketCount] before this returns null).
 */
private fun packetEndUs(packet: AVPacket, grabber: FFmpegFrameGrabber): Long? {
    val pts = if (packet.pts() != avutil.AV_NOPTS_VALUE) packet.pts() else packet.dts()
    if (pts == avutil.AV_NOPTS_VALUE) return null
    val timeBase = grabber.formatContext.streams(packet.stream_index()).time_base()
    if (timeBase.den() == 0) return null // a malformed/unknown time_base can't be converted
    return ((pts + maxOf(packet.duration(), 0L)).toDouble() * timeBase.num() / timeBase.den() * MICROS_PER_SECOND).toLong()
}

/**
 * Recovers a container's true duration for files whose format header reports none at all.
 * `grabber.lengthInTime` (AVFormatContext.duration) comes back 0 for two different shapes of file,
 * both handled here as two fallback steps sharing the single packet pass [scanPackets] performs:
 *
 *  - **Step 2 (exact)**: a container written by a live-mode/streaming muxer that never got to
 *    record its own trailer — a screen recorder that streams its output, or is killed before
 *    finalizing — still carries real per-packet timestamps. [PacketScanResult.maxEndUs] (the
 *    maximum packet presentation-end time across the whole file) IS the container duration a
 *    well-formed header would have reported: the true end of whichever stream runs longest.
 *    `lengthInVideoFrames` is ALSO 0 for these files (confirmed against real `-f matroska -live 1`
 *    / `-f webm -live 1` output), so a frames-times-frame-rate fallback can't rescue them — packet
 *    timestamps are the only exact signal available, which is why this step is preferred whenever
 *    it has anything to offer.
 *  - **Step 3 (approximate fallback)**: a RAW elementary stream (h264/hevc — Android tooling
 *    sometimes writes these, occasionally even with a `.mp4` extension) carries no container
 *    timestamps at all. FFmpeg content-probes and plays it happily, but every packet comes back
 *    with neither pts nor dts set, so step 2 finds nothing (maxEndUs stays 0) even though the file
 *    plainly has content. The only signal left is how many packets went by on the video stream,
 *    divided by FFmpeg's own frame rate for that stream — [PacketScanResult.videoFrameRate], which
 *    for a raw stream is itself a guess (`avg_frame_rate` on content with no declared frame rate),
 *    not something the format actually declares. This can be off from the real duration in either
 *    direction, which is exactly why [growDurationIfNeeded] (step 4, applied where playback
 *    position is published, not here) exists: an approximate estimate from this step is safe only
 *    because playback can never run past it without immediately raising it to match.
 *
 * Guards `videoFrameRate <= 0` (unset) and non-finite (NaN/Infinity, e.g. a 0/0 avg_frame_rate)
 * rather than dividing by it blindly — either would otherwise produce a garbage or infinite
 * "duration". Returns 0 (== "still unknown") if neither step above found anything usable, e.g. an
 * empty stream, a cancelled scan, or a raw stream FFmpeg couldn't even guess a frame rate for.
 *
 * See [scanPackets] for why the packet walk itself is a separate function (both to share one pass
 * for these two steps and so a test can assert its precondition), and its own KDoc for what
 * [isCancelled] is for.
 */
internal fun scanDurationMs(path: String, isCancelled: () -> Boolean = { false }): Long =
    resolveScannedDurationMs(scanPackets(path, isCancelled))

/** The step 2 / step 3 decision described in [scanDurationMs]'s KDoc, pulled out as its own pure
 *  function so the fallback arithmetic can be unit-tested against a hand-built [PacketScanResult]
 *  without needing a real (or even a cancelled) file scan to produce one. */
internal fun resolveScannedDurationMs(result: PacketScanResult): Long {
    if (result.maxEndUs > 0L) return result.maxEndUs / MICROS_PER_MS
    val frameRate = result.videoFrameRate
    if (result.videoPacketCount <= 0L || frameRate <= 0.0 || !frameRate.isFinite()) return 0L
    return (result.videoPacketCount / frameRate * MS_PER_SECOND).toLong()
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

    /** Linear gain in [0, 1] applied to the audio line, independent of [isMuted]. Defaults to 1
     *  (full volume) on a freshly opened controller. */
    val volume: Float

    /** True when audio output is silenced without discarding the [volume] level to restore. */
    val isMuted: Boolean

    /** Human-readable failure reason (unsupported/corrupt file, no audio device, ...) — the file
     *  failing to open is surfaced here, never as a crash. Null while healthy. */
    val error: String?

    fun play()

    fun pause()

    fun seek(ms: Long)

    /** Playback speed multiplier. MVP limitation (documented, not silently wrong): real
     *  variable-speed audio needs pitch-correct resampling, out of scope for this substrate, so
     *  audio is silenced entirely whenever rate != 1x instead — playing it unmodified would be
     *  wrong-pitched (rate < 1) or reduced to chopped garbage by the truncate-to-available() path
     *  in `writeAudio` (rate > 1). Audio resumes automatically once rate returns to 1x. Video
     *  presentation re-paces correctly at any rate: frames whose timestamps the (rate-scaled)
     *  playback clock has already passed are dropped rather than decoded, which is also the
     *  mechanism that lets rate > 1 actually play faster (see `shouldDropLateFrame`). */
    fun setRate(rate: Float)

    /** Sets the linear gain in [0, 1]. Does not change [isMuted]. */
    fun setVolume(volume: Float)

    /** Toggles silence without losing the [volume] level to restore on unmute. */
    fun setMuted(muted: Boolean)

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
    override val volume: Float = 1f
    override val isMuted: Boolean = false

    override fun play() = Unit

    override fun pause() = Unit

    override fun seek(ms: Long) = Unit

    override fun setRate(rate: Float) = Unit

    override fun setVolume(volume: Float) = Unit

    override fun setMuted(muted: Boolean) = Unit

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
    private var _volume by mutableStateOf(1f)
    private var _isMuted by mutableStateOf(false)

    override val currentFrame: ImageBitmap? get() = _currentFrame
    override val positionMs: Long get() = _positionMs
    override val durationMs: Long get() = _durationMs
    override val isPlaying: Boolean get() = _isPlaying
    override val volume: Float get() = _volume
    override val isMuted: Boolean get() = _isMuted
    override val error: String? get() = _error

    // Written only from the decode thread; read from any thread (Compose recomposition, MCP,
    // grabCurrentFrame callers) — a plain @Volatile is enough since it's always assigned wholesale
    // (a fresh BufferedImage), never mutated in place.
    @Volatile private var lastImage: BufferedImage? = null
    private var audioLine: SourceDataLine? = null

    // MASTER_GAIN is a plain javax.sound control, unlike `grabber` — safe to touch from any
    // thread, so setVolume/setMuted apply it directly rather than routing through the decode
    // thread's cross-thread command volatiles.
    private var gainControl: FloatControl? = null

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

    // Decode-thread-owned run length of frames dropped in a row by shouldDropLateFrame, reset to 0
    // every time a frame is actually shown. See shouldDropLateFrame's KDoc for why this is bounded.
    private var consecutiveDrops = 0

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

    override fun setVolume(volume: Float) {
        _volume = volume.coerceIn(0f, 1f)
        applyGain()
    }

    override fun setMuted(muted: Boolean) {
        _isMuted = muted
        applyGain()
    }

    private fun applyGain() {
        val control = gainControl ?: return
        val effective = if (_isMuted) 0f else _volume
        val db = if (effective < SILENT_VOLUME_THRESHOLD) {
            control.minimum
        } else {
            (DB_PER_DECADE * log10(effective)).coerceIn(control.minimum, control.maximum)
        }
        runCatching { control.value = db }
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
        applyDecodeDownscale()
        _durationMs = grabber.lengthInTime / MICROS_PER_MS
        maybeStartDurationRecoveryScan(_durationMs)
        hasAudioStream = grabber.hasAudio()
        if (hasAudioStream) openAudioLine()
        true
    }.getOrElse { t ->
        AppLogger.error("video", "Failed to open $path", t)
        _error = t.message ?: t::class.simpleName ?: "Failed to open video"
        false
    }

    /**
     * `grabber.lengthInTime` reporting 0 (or negative) means the container's own header carries no
     * duration — see [scanDurationMs]'s KDoc for the two different shapes of durationless file this
     * covers (steps 2 and 3 of the chain) and why a plain frame-count fallback alone can't help.
     * Declared-duration files return immediately here and never pay for a scan.
     *
     * Runs on its own short-lived daemon thread, separate from both [decodeThread] and the UI
     * thread's Compose recomposition, exactly like [grabFrameAt] opens its own separate grabber
     * rather than touching this controller's playing one — spawning it here (from [openGrabber],
     * itself already running on [decodeThread]) is non-blocking, so opening a durationless file
     * never delays showing its first frame. `_durationMs` is a Compose `mutableStateOf`, snapshot-
     * safe to write from any thread (see CLAUDE.md), so the transport bar simply recomposes once
     * this publishes a real value — no signaling back to [decodeThread] or the UI is needed. In
     * practice this thread finishes in single-digit-to-low-double-digit milliseconds (I/O bound, no
     * decoding), so by the time a user could react to the panel, a real duration is already showing
     * — see [scanDurationMs]'s own KDoc for the exact numbers this was measured against.
     *
     * Cancellation: [scanDurationMs]'s `isCancelled` callback reads [closed] on every packet, so a
     * scan racing [close] stops promptly instead of walking the rest of the file; the final publish
     * is ALSO gated on `!closed` in case the scan's last iteration finishes between that check and
     * [close] being called. Either way the scan's own `FFmpegFrameGrabber` is opened and released
     * entirely inside [scanPackets]'s `use` block, so there is nothing here left to leak.
     *
     * The publish itself goes through [growDurationIfNeeded] rather than a plain assignment: this
     * scan can take a few milliseconds, during which the decode thread may already have published
     * real playback positions (see [setPositionMs]) that pushed `_durationMs` up via step 4's
     * self-correcting growth. A recovered value that turned out lower than a position already
     * reached must not yank the duration back down — [growDurationIfNeeded] keeps this monotonic
     * either way, so the transport bar never regresses once a real length is showing.
     */
    private fun maybeStartDurationRecoveryScan(declaredMs: Long) {
        if (declaredMs > 0L) return
        val scanPath = path
        Thread({
            val recoveredMs = runCatching { scanDurationMs(scanPath) { closed } }
                .onFailure { AppLogger.warn("video", "duration recovery scan failed for $scanPath", it) }
                .getOrNull()
            // Logged unconditionally (success, failure, or "found nothing") so a future report
            // shaped like this one is diagnosable from the log alone, not just from the live UI.
            AppLogger.info(
                "video",
                "duration recovery for $scanPath: declaredMs=$declaredMs recoveredMs=${recoveredMs ?: "n/a"}",
            )
            if (!closed && recoveredMs != null && recoveredMs > 0L) {
                _durationMs = growDurationIfNeeded(_durationMs, recoveredMs)
            }
        }, "openlog-video-duration-scan").apply { isDaemon = true }.start()
    }

    /**
     * Caps the decoded frame size on its long edge, preserving aspect ratio, by setting
     * `grabber.imageWidth`/`imageHeight` so FFmpeg's native swscale does the resize instead of every
     * frame paying for two full-resolution Java-side copies (`Java2DFrameConverter.convert()`, then
     * `toComposeImageBitmap()`) — see MAX_DECODE_LONG_EDGE_PX's comment for why that matters.
     *
     * Ordering constraint: `imageWidth`/`imageHeight` default to 0 ("use the source's native size"),
     * and FFmpegFrameGrabber's own getters only resolve that to an actual pixel count once the
     * stream is open (`video_c != null`, per its `getImageWidth()`/`getImageHeight()` overrides) —
     * before `start()` there is nothing to compute an aspect-preserving cap from. So this must run
     * AFTER `grabber.start()`, reading the still-zero fields (whose getters fall back to the native
     * decoder width/height while unset) to learn the source size, then writing back a capped size.
     * Setting them after `start()` is not too late, despite that: FFmpegFrameGrabber re-derives the
     * output size from those same fields on every decoded frame (`processImage()`, via a
     * `sws_getCachedContext` that's cheap to re-fetch when parameters are unchanged and reallocates
     * when they change), so the very next grab already comes out at the capped resolution — no
     * restart of the grabber needed.
     *
     * [grabFrameAt] intentionally does not call this: it opens its own separate, short-lived grabber
     * for MCP/screenshot frame grabs, which stays full resolution on purpose.
     */
    private fun applyDecodeDownscale() {
        val sourceWidth = grabber.imageWidth
        val sourceHeight = grabber.imageHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return // no video stream (e.g. audio-only file)
        val longEdge = maxOf(sourceWidth, sourceHeight)
        if (longEdge <= MAX_DECODE_LONG_EDGE_PX) return // already small enough; never upscale
        val scale = MAX_DECODE_LONG_EDGE_PX.toDouble() / longEdge
        // swscale needs even dimensions for the YUV chroma-subsampled pixel formats most video uses;
        // an odd target can fail to convert or misalign chroma planes. Round down to even, and
        // coerceAtLeast(2) guards the (pathological) case of a source with a >1280x1 aspect ratio.
        grabber.imageWidth = (((sourceWidth * scale).toInt()) / 2 * 2).coerceAtLeast(2)
        grabber.imageHeight = (((sourceHeight * scale).toInt()) / 2 * 2).coerceAtLeast(2)
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

        // waitUntilPresentationTime returns as soon as the clock has reached (or already passed)
        // this frame's timestamp, without sleeping further — so a frame that was already late on
        // entry (this decode step took too long, or rate > 1 made the clock outrun it) reaches here
        // with a positive latenessUs instead of ever causing a wait. Drop it before paying for the
        // conversion, which is the expensive part (see MAX_DECODE_LONG_EDGE_PX's comment).
        val latenessUs = currentClockUs() - frame.timestamp
        if (shouldDropLateFrame(latenessUs, consecutiveDrops)) {
            consecutiveDrops++
            // Still advance the published position to this frame's timestamp even though its
            // picture is being skipped — video-to-log sync (and the slider) must keep moving
            // forward with the clock rather than stalling on the last frame that was actually shown.
            publishClockPosition(frame.timestamp, epoch)
            return
        }

        consecutiveDrops = 0
        val image = runCatching { converter.convert(frame) }.getOrNull() ?: return
        publishFrame(image, frame.timestamp, epoch)
    }

    private fun presentAudioFrame(frame: Frame) {
        // JavaSound plays PCM at a fixed sample rate, i.e. always at 1x pitch — there is no
        // resampling here (see setRate's KDoc). Writing frames unmodified at rate != 1x would be
        // wrong in two different ways depending on direction: at rate < 1 the audio would play at
        // the wrong (higher) pitch and finish before the video does; at rate > 1, writeAudio's
        // truncate-to-line.available() path would silently drop most of it, since audio frames are
        // still decoded and offered at their normal real-time cadence while a faster clock consumes
        // video sooner — the result is not slow/fast audio but chopped garbage. Silence is the
        // correct MVP behavior for either case; audio resumes automatically once rate returns to 1x
        // (this check is re-evaluated per frame, on the same decode thread that owns `rate`).
        if (rate != 1f) return
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
        setPositionMs(timeline.seek(command.targetUs) / MICROS_PER_MS)
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
            setPositionMs(positionUs / MICROS_PER_MS)
            // Deliberately NOT rebasing playbackStartNanos/playbackStartTimestampUs here. This used
            // to reset the wall-clock origin to "now = this frame's timestamp" on every published
            // frame, which made currentClockUs() measure decode throughput instead of real elapsed
            // time — the clock could never observe (or recover from) the pipeline falling behind,
            // which is also why setRate had no way to speed anything up: doing so requires the
            // clock to outrun frames so shouldDropLateFrame can skip them, and a clock that
            // re-anchors to every frame it shows can never get ahead of the frame it just showed.
            // The clock origin is established ONLY at a seek (applyPendingSeek), a play
            // (applyPendingPlaybackClockChanges' resetPlaybackClockRequested branch), and a rate
            // change (applyPendingPlaybackClockChanges' pendingRate branch) — see those for why each
            // one is still correct without this rebase.
        }
    }

    private fun publishPosition(candidateUs: Long, epoch: Long) {
        synchronized(presentationLock) {
            if (closed || !isCurrentEpoch(epoch)) return
            setPositionMs(timeline.advance(candidateUs) / MICROS_PER_MS)
        }
    }

    // Step 4 of the duration-recovery chain (see growDurationIfNeeded's KDoc): every publish of a
    // real position is also a chance to notice the currently-known duration has been undershot, and
    // fix that on the spot. Routing every _positionMs write through this one function (rather than
    // duplicating the growth check at each call site) is what guarantees no future publish site can
    // forget it — the three current call sites are a seek, a shown frame, and a dropped-frame clock
    // advance, and any new one inherits the same guarantee for free.
    private fun setPositionMs(ms: Long) {
        _positionMs = ms
        _durationMs = growDurationIfNeeded(_durationMs, ms)
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
            gainControl = runCatching {
                audioLine?.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl
            }.getOrNull()
            applyGain()
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
