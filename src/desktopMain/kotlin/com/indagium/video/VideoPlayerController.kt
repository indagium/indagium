package com.indagium.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.indagium.debug.AppLogger
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean
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

// Floor on how often publishClockPosition actually publishes — see that function's KDoc. ~16ms is
// roughly one display frame at 60Hz: smooth enough for a slider, far cheaper than the per-iteration
// cost of the wait loop that calls it.
private const val CLOCK_PUBLISH_MIN_INTERVAL_NANOS = 16_000_000L

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

// Bounds the video read-ahead queue decode-ahead builds during playback (see the class KDoc's
// "decode-ahead" section and refillReadAheadQueue/presentQueuedVideoFrame) by BOTH frame count and
// buffered duration — whichever limit is reached first stops refilling. Both are needed because
// either alone fails at an extreme frame rate: duration alone would let a very high fps source queue
// dozens of full decoded pictures (a memory blow-up), and count alone would let a very low fps
// source's queue span several real seconds of video (defeating "stay a few hundred ms ahead", and
// delaying how quickly a seek/pause/rate-change becomes visible once that backlog has to drain
// first).
//
// MAX_QUEUED_VIDEO_FRAMES: each queued entry is a Frame.clone() (see refillReadAheadQueue for why
// cloning is mandatory) holding a full native decoded picture at up to MAX_DECODE_LONG_EDGE_PX on
// its long edge — roughly 1.4MB for a 1280x720 YUV420P frame. 16 frames is at most ~22MB of extra
// native memory, comfortably inside the "tens of MB for a single full-resolution frame's own
// conversion" territory MAX_DECODE_LONG_EDGE_PX's own comment above already accepts.
//
// MAX_QUEUED_VIDEO_DURATION_US: 400ms is comfortably more than the pacing gaps
// waitUntilPresentationTime historically saw between audio writes (~33ms at 30fps, ~41ms at 24fps) —
// enough backlog to survive an expensive keyframe decode or a GC pause without the audio line
// underrunning, without holding several seconds of (potentially soon-to-be-superseded) video in
// memory for no presentation benefit.
private const val MAX_QUEUED_VIDEO_FRAMES = 16
private const val MAX_QUEUED_VIDEO_DURATION_US = 400_000L

// HARD_MAX_QUEUED_VIDEO_FRAMES is the unconditional backstop for the audio-priority override in
// shouldReadAhead below: when audio is under-buffered, refill is allowed to keep grabbing PAST the
// soft MAX_QUEUED_VIDEO_FRAMES/MAX_QUEUED_VIDEO_DURATION_US bound above so the audio sitting just
// past a run of video packets in the container's interleave still gets pulled off the grabber (see
// shouldReadAhead's own KDoc for why the soft bound alone periodically starves audio). This is the
// ceiling on how far that override can push the video queue, checked first and unconditionally —
// 64 frames, 4x the soft cap, is ~90MB of extra native memory at ~1.4MB/frame (a 1280x720 YUV420P
// frame at MAX_DECODE_LONG_EDGE_PX's cap, per that constant's own comment). That is comfortably more
// than ordinary audio catch-up ever needs — a container's interleave chunk is normally well under a
// second of content, so the audio-priority path is expected to release its hold on the queue long
// before this — while still bounding a pathological interleave, or an audio device that has stopped
// draining entirely (so AUDIO_READ_AHEAD_TARGET_US below is never reached), to a fixed and tolerable
// memory cost instead of an unbounded one.
private const val HARD_MAX_QUEUED_VIDEO_FRAMES = 64

// How far ahead audio must already be buffered (SourceDataLine contents + pendingAudioTail, via
// audioBufferedAheadUs) before shouldReadAhead lets the video queue's soft bound govern again. 250ms
// is comfortably more than one interleave chunk of an ordinary MP4/MKV — the same real-world pacing
// MAX_QUEUED_VIDEO_DURATION_US's own comment cites (~33ms at 30fps, ~41ms at 24fps, between audio
// writes under steady playback) — so a queue that stops refilling here has enough audio already
// buffered to survive the next run of video-only packets without the line underrunning.
private const val AUDIO_READ_AHEAD_TARGET_US = 250_000L

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

// How much audio the SourceDataLine's own internal buffer should hold, and how long writeAudio's
// carry-over is allowed to grow before it is dropped instead of retried. Both are sized in seconds
// of audio rather than a fixed byte count because they must scale with the stream's actual sample
// rate/channel count (openAudioLine reads those from the grabber once it's open).
//
// 300ms of line buffer is comfortably bigger than the write gaps waitUntilPresentationTime's pacing
// sleeps create between audio writes (~33ms at 30fps, ~41ms at 24fps, MAX_WAIT_SLEEP_MS=20 between
// checks) — see openAudioLine. 1s of carry-over is a last-resort safety valve for a device that has
// stopped draining entirely (disconnected, or paused for a long stretch) — see writeAudio — sized
// generously relative to the line buffer since normal playback is never expected to approach it.
private const val AUDIO_BUFFER_TARGET_SECONDS = 0.3
private const val AUDIO_BUFFER_MIN_BYTES = 8_192
private const val AUDIO_BUFFER_MAX_BYTES = 1_048_576
private const val AUDIO_CARRYOVER_MAX_SECONDS = 1.0

// SourceDataLine.open(format, bufferSize) documents bufferSize as a HINT — the platform mixer is
// free to round it to a device-native chunk/period size, which is ordinarily off by a few bytes and
// not worth logging. Below this fraction of what was actually requested, treat it as a meaningful
// clamp worth a warning: it would silently reintroduce the underrun risk
// computeAudioLineBufferSizeBytes exists to avoid, and nothing before this checked for it at all —
// see openAudioLine's read-back of line.bufferSize.
private const val AUDIO_BUFFER_CLAMP_WARNING_RATIO = 0.9

/**
 * How large to open the audio SourceDataLine's buffer, in bytes, for a stream at [sampleRate]/
 * [channels]/[bytesPerSample]. See AUDIO_BUFFER_TARGET_SECONDS above for why 300ms, and the min/max
 * clamp for why it can't be sized arbitrarily small (an underrun-prone sliver) or large (needless
 * output latency/memory on a pathological sample rate). Aligned to a whole multiple of the frame
 * size (channels * bytesPerSample) so JavaSound never sees a request that isn't a whole number of
 * sample frames.
 */
internal fun computeAudioLineBufferSizeBytes(sampleRate: Int, channels: Int, bytesPerSample: Int = BYTES_PER_SAMPLE): Int {
    val frameBytes = channels * bytesPerSample
    if (frameBytes <= 0 || sampleRate <= 0) return AUDIO_BUFFER_MIN_BYTES
    val target = (sampleRate * frameBytes * AUDIO_BUFFER_TARGET_SECONDS).toInt()
    val aligned = (target / frameBytes) * frameBytes
    return aligned.coerceIn(AUDIO_BUFFER_MIN_BYTES, AUDIO_BUFFER_MAX_BYTES)
}

/**
 * Rounds [byteCount] DOWN to the nearest whole multiple of [frameBytes] (channels * bytes-per-
 * sample). SourceDataLine.write requires a whole-frame multiple; without this rounding, a stereo
 * frame cut mid-sample leaves every subsequent sample channel-swapped/byte-misaligned until the next
 * flush. `frameBytes <= 0` (no audio channels known yet) returns [byteCount] unchanged rather than
 * dividing by zero — callers only reach that state before a grabber has actually opened audio.
 */
internal fun alignToFrameBoundary(byteCount: Int, frameBytes: Int): Int {
    if (frameBytes <= 0) return byteCount.coerceAtLeast(0)
    val n = byteCount.coerceAtLeast(0)
    return n - (n % frameBytes)
}

/**
 * How much unwritten PCM writeAudio's carry-over may hold for [sampleRate]/[channels]/
 * [bytesPerSample], in bytes — see AUDIO_CARRYOVER_MAX_SECONDS above. Aligned to a whole multiple of
 * the frame size, same reasoning as [computeAudioLineBufferSizeBytes], so [boundAudioCarryover]'s
 * trim point is always a frame boundary too.
 */
internal fun computeAudioCarryoverCapBytes(sampleRate: Int, channels: Int, bytesPerSample: Int = BYTES_PER_SAMPLE): Int {
    val frameBytes = channels * bytesPerSample
    if (frameBytes <= 0 || sampleRate <= 0) return 0
    val target = (sampleRate * frameBytes * AUDIO_CARRYOVER_MAX_SECONDS).toInt()
    return (target / frameBytes) * frameBytes
}

/**
 * Keeps [combined] (previous carry-over plus whatever new PCM didn't fit this pass) under
 * [capBytes], dropping the OLDEST bytes when it doesn't fit rather than growing without bound — the
 * anti-deadlock guarantee writeAudio documents (never block the sole grabber-owning thread on a full
 * line) only holds if this stays bounded even when a device stops draining entirely. [combined] and
 * [capBytes] are expected to already be frame-aligned (see [alignToFrameBoundary] /
 * [computeAudioCarryoverCapBytes]), which trimming to a suffix preserves: capBytes is itself a whole
 * multiple of the frame size, so `combined.size - capBytes` lands on a frame boundary too.
 */
internal fun boundAudioCarryover(combined: ByteArray, capBytes: Int): ByteArray {
    if (capBytes <= 0) return ByteArray(0)
    return if (combined.size <= capBytes) combined else combined.copyOfRange(combined.size - capBytes, combined.size)
}

/**
 * Null when [actualBytes] (what `line.bufferSize` reports after `open()`) is within
 * [AUDIO_BUFFER_CLAMP_WARNING_RATIO] of [requestedBytes] — ordinary rounding to a device-native
 * chunk size, not worth logging. Otherwise a ready-to-log message describing the shortfall, so
 * [FfmpegVideoPlayerController.openAudioLine] can surface a clamp that would otherwise be invisible
 * — see AUDIO_BUFFER_CLAMP_WARNING_RATIO's own comment for why nothing checked this before. A pure
 * function, like this file's other decision points, so the threshold can be unit tested without a
 * real audio device. `requestedBytes <= 0` (nothing meaningful was ever requested) returns null
 * rather than warning about a division that couldn't have meant anything.
 */
internal fun audioLineBufferClampWarning(requestedBytes: Int, actualBytes: Int): String? {
    if (requestedBytes <= 0) return null
    if (actualBytes >= requestedBytes * AUDIO_BUFFER_CLAMP_WARNING_RATIO) return null
    return "audio line buffer clamped by the platform: requested $requestedBytes bytes, got $actualBytes bytes " +
        "— underrun risk is higher than computeAudioLineBufferSizeBytes was sized for"
}

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
 * How far into the future decode-ahead has already buffered video, in microseconds: the gap
 * between the playback clock and the newest (most recently decoded, i.e. last-queued) frame
 * currently sitting in the read-ahead queue. Never negative — a queue whose newest frame the clock
 * has already caught up to (or passed) has buffered nothing usable ahead of now, regardless of how
 * many stale entries are still sitting in it waiting to be dropped by [shouldDropLateFrame] as they
 * reach the front.
 *
 * Deliberately independent of the queue's own data structure — like [shouldDropLateFrame] above —
 * so [shouldReadAhead]'s bound can be unit tested against hand-picked clock/timestamp pairs without
 * a real decode thread or FFmpeg natives.
 */
internal fun queuedVideoDurationAheadUs(currentClockUs: Long, newestQueuedTimestampUs: Long): Long =
    (newestQueuedTimestampUs - currentClockUs).coerceAtLeast(0L)

/**
 * How far ahead audio is already buffered, in microseconds, given [bufferedBytes] of not-yet-played
 * PCM (the SourceDataLine's own already-written contents plus any
 * [FfmpegVideoPlayerController.pendingAudioTail] carry-over — see that call site for how the two are
 * combined) at [sampleRate] with [frameBytes] bytes per sample frame (channels * bytes-per-sample,
 * i.e. [FfmpegVideoPlayerController.audioFrameBytes]).
 *
 * A pure bytes-to-microseconds conversion, kept separate from its [FfmpegVideoPlayerController] call
 * site — like [queuedVideoDurationAheadUs] above — so [shouldReadAhead]'s audio term is unit testable
 * against hand-picked byte counts without a real audio device. `sampleRate <= 0` or `frameBytes <= 0`
 * (no audio stream opened yet, or a malformed format) returns 0 rather than dividing by zero — the
 * caller is expected to treat "no audio opened" as a fully-satisfied term itself (see
 * [shouldReadAhead]'s KDoc), not via this function returning some sentinel.
 */
internal fun audioBufferedAheadUs(bufferedBytes: Long, sampleRate: Int, frameBytes: Int): Long {
    if (sampleRate <= 0 || frameBytes <= 0 || bufferedBytes <= 0L) return 0L
    val bufferedFrames = bufferedBytes / frameBytes
    return bufferedFrames * MICROS_PER_SECOND / sampleRate
}

/**
 * Whether the decode loop should keep grabbing — and, for a video frame, cloning and queueing it
 * (see [FfmpegVideoPlayerController.refillReadAheadQueue]) — rather than move on to presenting/
 * pacing the read-ahead queue's next frame.
 *
 * This bound is audio-aware, not video-only: [FfmpegVideoPlayerController.refillReadAheadQueue] is
 * the SOLE place `grabber.grab()` is called, and audio is decoded exclusively as a side effect of
 * that same loop (see the class KDoc's "decode-ahead" section) — there is no separate audio decode
 * path to fall back on. A container interleaves video and audio packets in CHUNKS, not perfectly
 * alternating, so stopping refill purely because the video queue hit its soft bound
 * (MAX_QUEUED_VIDEO_FRAMES/MAX_QUEUED_VIDEO_DURATION_US) can leave an entire run of audio packets
 * sitting just past the video packets that filled that bound — ungrabbed — and the audio line then
 * runs dry for as long as that run's own duration (this is what produced the periodic ~100ms
 * dropouts users heard: the stall length tracked the container's interleave period) until a queued
 * video frame is finally presented, freeing a slot so refill can grab again. So: audio need takes
 * priority over the video queue's SOFT bound. Only [HARD_MAX_QUEUED_VIDEO_FRAMES] — checked first,
 * unconditionally — can still stop refilling while audio remains under-buffered; see that constant's
 * own comment for its memory-safety sizing.
 *
 * [audioBufferedAheadUs] (see that function above for the bytes-to-microseconds conversion) is
 * expected to already read as "fully satisfied" — a value at or above [AUDIO_READ_AHEAD_TARGET_US],
 * in practice [Long.MAX_VALUE] — from the caller whenever audio isn't actually being consumed: no
 * audio stream, a line that failed to open, or `rate != 1x` muting `presentAudioFrame` (see that
 * function's KDoc). Without that, the audio term would read as permanently empty and this function
 * would never stop reading ahead for a video-only file or during a rate change — see
 * [FfmpegVideoPlayerController.currentAudioBufferedAheadUs] for where that substitution happens. This
 * function itself only ever compares against the target, so the video-only/muted cases fall straight
 * through to exactly the same soft video bound as before this change.
 *
 * See MAX_QUEUED_VIDEO_FRAMES/MAX_QUEUED_VIDEO_DURATION_US above for why the soft bound itself needs
 * both a count and a duration term. An empty queue with audio already fully buffered
 * (`queuedFrameCount == 0`, `queuedDurationAheadUs == 0`, `audioBufferedAheadUs >=
 * AUDIO_READ_AHEAD_TARGET_US`) always reads ahead at least once, which is what lets the very first
 * frame after play()/seek() ever get queued at all.
 *
 * Pure and FFmpeg-free, like [shouldDropLateFrame], so the bound itself — not just its constants —
 * is unit testable without native video libraries or a real decode thread.
 */
internal fun shouldReadAhead(queuedFrameCount: Int, queuedDurationAheadUs: Long, audioBufferedAheadUs: Long): Boolean {
    if (queuedFrameCount >= HARD_MAX_QUEUED_VIDEO_FRAMES) return false
    if (audioBufferedAheadUs < AUDIO_READ_AHEAD_TARGET_US) return true
    return queuedFrameCount < MAX_QUEUED_VIDEO_FRAMES && queuedDurationAheadUs < MAX_QUEUED_VIDEO_DURATION_US
}

/** A lifecycle event the decode loop reacts to that might or might not make already-decoded state
 *  (the video read-ahead queue, or audio's [FfmpegVideoPlayerController.pendingAudioTail] carry-
 *  over) stale — see [invalidatesDecodedState]. */
internal enum class PlaybackTransition { PAUSE, SEEK, RATE_CHANGE, CLOSE }

/** Which already-decoded state a transition might invalidate — the video read-ahead queue and the
 *  audio carry-over buffer don't always answer the same way to the same transition (see
 *  [invalidatesDecodedState]'s own KDoc for RATE_CHANGE). */
internal enum class DecodedStateKind { VIDEO_QUEUE, AUDIO_CARRYOVER }

/**
 * Whether [transition] makes already-decoded [kind] state stale and therefore due for discarding,
 * versus merely pausing/repacing the same underlying stream. This is the single source of truth
 * [FfmpegVideoPlayerController.pause]/`applyPendingSeek`/`releaseAll`/the rate-change paths defer
 * to, so the invariant lives in one tested place instead of four separately-reasoned-about call
 * sites that could drift out of sync with each other.
 *
 *  - **PAUSE invalidates neither.** A pause is not a discontinuity in the stream — the clock simply
 *    stops advancing. Whatever decode-ahead already produced for [C, C+something] (C being the last
 *    position actually shown) is still exactly the next audio/video to play once resumed; see
 *    [FfmpegVideoPlayerController.pause]'s own comment for what discarding it used to break.
 *  - **SEEK and CLOSE invalidate both.** A seek is about to make "the current position" mean
 *    something entirely different, and a close tears the whole pipeline down — anything already
 *    decoded for the OLD position (or for a grabber about to be released) is simply wrong to play.
 *  - **RATE_CHANGE invalidates AUDIO_CARRYOVER but not VIDEO_QUEUE.** These genuinely differ, unlike
 *    every other row above. Audio has no in-between state: `presentAudioFrame` writes it verbatim at
 *    rate == 1 or mutes it completely otherwise (see `setRate`'s KDoc — no pitch-correct resampling
 *    here), so a carried-over fragment written under the OLD rate assumption is wrong the instant the
 *    rate changes, in either direction. A queued VIDEO frame has no such rate-dependent identity: it
 *    is decoded picture data timestamped for one specific moment regardless of playback speed: at a
 *    faster rate more of the queue's frames get skipped by [shouldDropLateFrame] as the clock outruns
 *    them, and at a slower rate fewer do, but every frame already in the queue remains exactly the
 *    right picture for its own timestamp either way — nothing about it needs discarding.
 *
 * A pure, FFmpeg-free lookup table — like [shouldDropLateFrame]/[shouldReadAhead] above — so this
 * invariant is unit testable without a real decode thread, audio device, or video file.
 */
internal fun invalidatesDecodedState(transition: PlaybackTransition, kind: DecodedStateKind): Boolean = when (transition) {
    PlaybackTransition.PAUSE -> false
    PlaybackTransition.SEEK, PlaybackTransition.CLOSE -> true
    PlaybackTransition.RATE_CHANGE -> kind == DecodedStateKind.AUDIO_CARRYOVER
}

/**
 * Step 5 of the duration-recovery chain (see [scanDurationMs]'s KDoc for earlier metadata steps): raises
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
 * Whether the transport has a trustworthy upper bound to use for seeking. This describes
 * capability rather than a platform or container type: FFmpeg's reported duration can differ
 * across native builds, so the same recovery chain drives macOS, Linux, and Windows.
 */
internal enum class VideoSeekReadiness {
    /** A headerless source is still being inspected in the background. */
    DISCOVERING,

    /** A positive duration is available, so timeline seeks are meaningful. */
    READY,

    /** Every non-playback recovery path was exhausted without finding a duration. */
    UNAVAILABLE,
}

/**
 * One immutable duration/readiness update. Keeping these values together prevents a scan thread
 * from publishing stale "unknown" state after the decoder has observed a usable duration. A later
 * playback position may turn UNAVAILABLE into READY, but a ready duration never regresses.
 */
internal data class VideoSeekState(
    val durationMs: Long = 0L,
    val readiness: VideoSeekReadiness = VideoSeekReadiness.DISCOVERING,
)

internal fun advanceVideoSeekState(
    current: VideoSeekState,
    observedDurationMs: Long = 0L,
    discoveryFinished: Boolean = false,
): VideoSeekState {
    val durationMs = growDurationIfNeeded(current.durationMs, observedDurationMs.coerceAtLeast(0L))
    val readiness = when {
        durationMs > 0L -> VideoSeekReadiness.READY
        current.readiness == VideoSeekReadiness.UNAVAILABLE || discoveryFinished -> VideoSeekReadiness.UNAVAILABLE
        else -> VideoSeekReadiness.DISCOVERING
    }
    return VideoSeekState(durationMs, readiness)
}

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

/** Which non-header recovery signal yielded a duration, if any. */
internal enum class DurationRecoverySource {
    PACKET_METADATA,
    DECODE_TIMESTAMPS,
    UNAVAILABLE,
    CANCELLED,
}

internal data class DurationRecoveryResult(
    val durationMs: Long,
    val source: DurationRecoverySource,
)

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
    if (isCancelled()) return PacketScanResult(0L, 0L, 0.0)
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
        return if (isCancelled()) PacketScanResult(0L, 0L, 0.0) else PacketScanResult(lastEndUs, videoPacketCount, grabber.videoFrameRate)
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
 * both handled here as two fallback steps sharing the single packet pass [scanPackets] performs.
 * The caller may subsequently use [scanDecodedTimestampDurationMs] as step 4, only when both
 * metadata signals below have failed:
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
 *    direction, which is exactly why [growDurationIfNeeded] (step 5, applied where playback
 *    position is published, not here) exists: an approximate estimate from this step is safe only
 *    because playback can never run past it without immediately raising it to match.
 *
 * Guards `videoFrameRate <= 0` (unset) and non-finite (NaN/Infinity, e.g. a 0/0 avg_frame_rate)
 * rather than dividing by it blindly — either would otherwise produce a garbage or infinite
 * "duration". Returns 0 (== "still unknown") if neither metadata step above found anything
 * usable; [recoverDurationMs] then decides whether to run the decoded timestamp fallback. This
 * function returns 0 for cancellation too, so callers must not treat a cancelled result as a
 * completed discovery.
 *
 * See [scanPackets] for why the packet walk itself is a separate function (both to share one pass
 * for these two steps and so a test can assert its precondition), and its own KDoc for what
 * [isCancelled] is for.
 */
internal fun scanDurationMs(path: String, isCancelled: () -> Boolean = { false }): Long =
    if (isCancelled()) 0L else resolveScannedDurationMs(scanPackets(path, isCancelled))

/**
 * Last-resort duration recovery for files where the container exposes neither a length nor usable
 * packet timestamps/frame rate. This decodes image frames solely to read their FFmpeg presentation
 * timestamps; it is intentionally more expensive than [scanDurationMs] and callers must invoke it
 * only after that metadata-only pass has yielded no duration.
 */
internal fun scanDecodedTimestampDurationMs(path: String, isCancelled: () -> Boolean = { false }): Long {
    if (isCancelled()) return 0L
    FFmpegFrameGrabber(path).use { grabber ->
        grabber.start()
        var latestTimestampUs = 0L
        while (!isCancelled()) {
            val frame = grabber.grabImage() ?: break
            // JavaCV can expose the decoded-image timestamp on either the Frame or its grabber,
            // depending on the native demuxer/decoder combination, so accept whichever is newer.
            val decodedTimestampUs = maxOf(frame.timestamp, grabber.timestamp).coerceAtLeast(0L)
            latestTimestampUs = maxOf(latestTimestampUs, decodedTimestampUs)
        }
        return if (isCancelled()) 0L else latestTimestampUs / MICROS_PER_MS
    }
}

/**
 * Applies the recovery order without coupling it to FFmpeg so cancellation and fallback order can
 * be tested without native video files. Decoding is never attempted while packet metadata has
 * produced a usable duration, nor after cancellation.
 */
internal fun recoverDurationMs(
    scanPacketMetadata: () -> Long,
    scanDecodedTimestamps: () -> Long,
    isCancelled: () -> Boolean = { false },
): DurationRecoveryResult {
    if (isCancelled()) return DurationRecoveryResult(0L, DurationRecoverySource.CANCELLED)
    val metadataDurationMs = scanPacketMetadata().coerceAtLeast(0L)
    if (isCancelled()) return DurationRecoveryResult(0L, DurationRecoverySource.CANCELLED)
    if (metadataDurationMs > 0L) return DurationRecoveryResult(metadataDurationMs, DurationRecoverySource.PACKET_METADATA)
    val decodedDurationMs = scanDecodedTimestamps().coerceAtLeast(0L)
    if (isCancelled()) return DurationRecoveryResult(0L, DurationRecoverySource.CANCELLED)
    return if (decodedDurationMs > 0L) {
        DurationRecoveryResult(decodedDurationMs, DurationRecoverySource.DECODE_TIMESTAMPS)
    } else {
        DurationRecoveryResult(0L, DurationRecoverySource.UNAVAILABLE)
    }
}

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
 * video frame in that same decode stream, which was an audio-clock deadlock in the old design — so
 * there is still exactly one thread that ever calls `grabber.grab()`, and audio is never queued or
 * paced against a clock of its own; it is written to the line the instant it is decoded.
 *
 * Within that one-thread constraint, the decode thread decodes AHEAD of what it is currently
 * showing rather than idling between frames: `grabber.grab()` returns whatever the container
 * interleaves next, and pacing to a video frame's presentation timestamp used to mean sleeping with
 * nothing else happening on this thread for that whole gap — no new audio got decoded either, so
 * the audio line only ever had whatever backlog the container's own interleave depth happened to
 * hand it, easily wiped out by one expensive keyframe decode or GC pause. Now the loop keeps
 * grabbing during what would have been that idle gap: any audio it encounters is written to the
 * line immediately, and any video frame is [Frame.clone]d (the grabber reuses its own `Frame`
 * buffer on the very next `grab()`, so a reference into it cannot be held past that call) into a
 * small bounded read-ahead queue (`videoQueue`; see MAX_QUEUED_VIDEO_FRAMES/
 * MAX_QUEUED_VIDEO_DURATION_US and [shouldReadAhead]) instead of being paced immediately. Only THEN
 * does the loop pace/present the queue's oldest video frame, exactly as before ([shouldDropLateFrame]
 * included) — see `refillReadAheadQueue`/`presentQueuedVideoFrame`. Net effect: audio decoding is
 * pulled ahead of video presentation, so the line stays fed through a hiccup that would otherwise
 * have starved it.
 *
 * The video queue's fill bound is itself audio-aware, not video-only: because `grabber.grab()` is
 * the one shared source of both streams, refill must not stop just because the video queue reached
 * its normal soft bound while audio is still short of its own target — a container's interleave
 * comes in chunks, so stopping there would leave a whole run of about-to-be-needed audio ungrabbed
 * right behind the video packets that filled the queue, and the line would starve for exactly that
 * chunk's duration (this is what produced the periodic ~100ms dropouts users heard even after the
 * decode-ahead rework above). [shouldReadAhead] therefore lets refill keep grabbing past the soft
 * bound whenever audio is under-buffered, up to a separate and larger hard cap kept purely for
 * memory safety — see that function and HARD_MAX_QUEUED_VIDEO_FRAMES/AUDIO_READ_AHEAD_TARGET_US for
 * the full reasoning.
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

    /**
     * Starts decoding after the controller has been installed in a composed player surface. The
     * default preserves compatibility for lightweight test doubles, which have no decoder to
     * start. Production defers this rather than starting from its constructor so its first preview
     * frame cannot be published before Compose has subscribed to [currentFrame].
     */
    fun start() = Unit

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

/** Internal capability implemented by production controllers without widening the established
 * public player interface. Older test doubles continue to work and are treated as discovering
 * until they report a positive duration. */
internal interface SeekReadinessAwareVideoPlayerController {
    val seekReadiness: VideoSeekReadiness
}

internal val VideoPlayerController.seekReadiness: VideoSeekReadiness
    get() = (this as? SeekReadinessAwareVideoPlayerController)?.seekReadiness
        ?: if (durationMs > 0L) VideoSeekReadiness.READY else VideoSeekReadiness.DISCOVERING

/**
 * Stands in for a real controller when AppState.videoController can't even get as far as opening
 * one — currently the one gap in that function's own "non-null whenever a video is attached"
 * contract: a [com.indagium.model.VideoSource.ArchiveEntry] whose archive has since been moved or
 * deleted (a very normal workflow for a disposable bug-report zip download, unlike an explicitly
 * attached local recording a user wouldn't casually delete) fails before [FfmpegVideoPlayerController]
 * ever gets a path to open. Without this, [error] had nowhere to live and the video panel/context-menu
 * actions silently acted as if no video were attached at all, instead of showing the same
 * "Couldn't play this video" failure state a broken local file already gets via its own
 * FFmpeg-reported [error]. Every mutator is a no-op; there is nothing to play, seek, or grab.
 */
internal class FailedVideoPlayerController(override val error: String) : VideoPlayerController, SeekReadinessAwareVideoPlayerController {
    override val currentFrame: ImageBitmap? = null
    override val positionMs: Long = 0L
    override val durationMs: Long = 0L
    override val seekReadiness: VideoSeekReadiness = VideoSeekReadiness.UNAVAILABLE
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

private class FfmpegVideoPlayerController(private val path: String) : VideoPlayerController, SeekReadinessAwareVideoPlayerController {
    private val grabber = FFmpegFrameGrabber(path)
    private val converter = Java2DFrameConverter()

    private var currentFrameState by mutableStateOf<ImageBitmap?>(null)
    private var positionMsState by mutableStateOf(0L)
    private var seekState by mutableStateOf(VideoSeekState())
    private var isPlayingState by mutableStateOf(false)
    private var errorState by mutableStateOf<String?>(null)
    private var volumeState by mutableStateOf(1f)
    private var isMutedState by mutableStateOf(false)

    // FFmpeg opens and decodes on [decodeThread], while Compose reads these fields during a UI
    // snapshot. A direct `mutableStateOf` write from the decoder can otherwise remain in that
    // thread's snapshot on Linux/Windows: the initial declared duration is then still read as 0
    // and playback grows that stale 0 into a fake duration. Applying every controller-state
    // mutation through one mutable snapshot makes the write globally visible and schedules the
    // observing composition. The lock also prevents competing UI and decoder mutations from
    // applying overlapping snapshots.
    private val uiStateLock = Any()

    private fun publishUiState(update: () -> Unit) = synchronized(uiStateLock) {
        Snapshot.withMutableSnapshot(update)
    }

    override val currentFrame: ImageBitmap? get() = currentFrameState
    override val positionMs: Long get() = positionMsState
    override val durationMs: Long get() = seekState.durationMs
    override val seekReadiness: VideoSeekReadiness get() = seekState.readiness
    override val isPlaying: Boolean get() = isPlayingState
    override val volume: Float get() = volumeState
    override val isMuted: Boolean get() = isMutedState
    override val error: String? get() = errorState

    // Written only from the decode thread; read from any thread (Compose recomposition, MCP,
    // grabCurrentFrame callers) — a plain @Volatile is enough since it's always assigned wholesale
    // (a fresh BufferedImage), never mutated in place.
    @Volatile private var lastImage: BufferedImage? = null

    // Opened on the decode thread (openGrabber -> openAudioLine, after grabber.start()) but
    // read/called from the UI thread too (play/pause/close/setVolume all touch the SourceDataLine
    // directly) — @Volatile makes the reference itself visible across threads. The line's own
    // start/stop/flush/write methods are documented thread-safe by javax.sound.sampled.
    @Volatile private var audioLine: SourceDataLine? = null

    // audioFrameBytes/audioCarryoverCapBytes are set once, in openAudioLine, before the decode loop
    // starts consuming audio frames — read-only after that from the decode thread that owns
    // writeAudio, so no synchronization is needed for them specifically.
    private var audioFrameBytes: Int = BYTES_PER_SAMPLE
    private var audioCarryoverCapBytes: Int = 0

    // PCM that writeAudio couldn't fit into the line this pass, retried at the top of the next
    // writeAudio call and on every decode-loop pass (including during waitUntilPresentationTime's
    // pacing sleeps) rather than being discarded outright — see writeAudio/flushPendingAudio.
    // Written wholesale (a fresh ByteArray), never mutated in place, same as lastImage above; the
    // occasional cross-thread clear from pause() races benignly with the decode thread at worst
    // dropping or keeping one extra chunk of audio a moment longer, never corrupting it.
    @Volatile private var pendingAudioTail: ByteArray = ByteArray(0)

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

    // Decode-thread-owned throttle for publishClockPosition — see that function for why.
    private var lastClockPublishNanos = 0L

    private data class QueuedVideoFrame(val frame: Frame, val epoch: Long)

    // The decode-ahead read-ahead queue — see the class KDoc's "decode-ahead" section and
    // refillReadAheadQueue/presentQueuedVideoFrame. Holds only VIDEO frames (audio is written to
    // the line immediately as it's encountered, never queued); each entry is already a
    // Frame.clone(), independent of the grabber's own reused Frame buffer.
    //
    // Mutated from the decode thread (push during refill, pop during present) AND from the UI
    // thread (pause() clears it) plus close()'s releaseAll() at loop exit — ArrayDeque itself is
    // not thread-safe, so every read or mutation goes through videoQueueLock. Kept as a short-held
    // lock: only the deque bookkeeping happens under it, never the (comparatively expensive)
    // Frame.close()/converter.convert() work on an already-removed entry.
    private val videoQueueLock = Any()
    private val videoQueue = ArrayDeque<QueuedVideoFrame>()

    // Decode-thread-owned. Set once grabber.grab() returns null with no exception (clean end of
    // stream) — see grabNextFrame/refillReadAheadQueue. Refilling stops there, but the queue may
    // still hold frames decoded before EOF that haven't been presented yet, so
    // presentQueuedVideoFrame only reports playback as stopped once BOTH this is true AND the queue
    // has fully drained. Reset by applyPendingSeek: a seek (even backward from EOF) repositions the
    // grabber to produce frames again.
    private var grabberExhausted = false

    private val decodeStarted = AtomicBoolean(false)

    private val decodeThread = Thread({ runLoop() }, "indagium-video-decode").apply {
        isDaemon = true
    }

    override fun start() {
        if (!closed && decodeStarted.compareAndSet(false, true)) decodeThread.start()
    }

    override fun play() {
        if (playRequested || closed) return
        playRequested = true
        // The decoder owns the clock fields. Rebase on its next step so a Play immediately after
        // a seek cannot use a stale frame timestamp as its origin.
        resetPlaybackClockRequested = true
        publishUiState { isPlayingState = true }
        runCatching { audioLine?.start() }
        wakeDecoder()
    }

    override fun pause() {
        playRequested = false
        publishUiState { isPlayingState = false }
        // A pause is NOT a discontinuity in the stream — only a seek (or a close) is; see
        // invalidatesDecodedState's KDoc and PlaybackTransition.PAUSE. In steady state, decode-ahead
        // has already pushed the grabber up to MAX_QUEUED_VIDEO_DURATION_US ahead of the last frame
        // actually shown (call that position C): the audio line holds roughly [C, C+bufferMs] of
        // already-decoded PCM, pendingAudioTail may hold a little more, and videoQueue holds already-
        // decoded video frames spanning [C, C+400ms]. None of that becomes wrong merely because the
        // clock stopped — it is still exactly the next audio/video to play once resumed. Discarding
        // it here (as an earlier version of this method did, via flush()/clearing both) meant play()
        // rebasing the clock to C left the grabber sitting at C+400ms with nothing to show until it
        // caught back up: a ~400ms frozen picture, and audio jumping ahead of the video it was
        // supposed to accompany. So: audioLine.stop() (not close/flush) — per the
        // javax.sound.sampled contract, stop() halts playback while RETAINING whatever is already
        // buffered in the line and resumes exactly where it left off on the next start(); flush()
        // (used by seek/close, which genuinely are discontinuities) is what would discard it instead.
        // pendingAudioTail and videoQueue are likewise left untouched below.
        runCatching { audioLine?.stop() }
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
        publishUiState { volumeState = volume.coerceIn(0f, 1f) }
        applyGain()
    }

    override fun setMuted(muted: Boolean) {
        publishUiState { isMutedState = muted }
        applyGain()
    }

    private fun applyGain() {
        val control = gainControl ?: return
        val effective = if (isMutedState) 0f else volumeState
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
    }.onFailure { AppLogger.error("video", "single-frame grab failed", it) }.getOrNull()

    override fun close() {
        closed = true
        playRequested = false
        publishUiState { isPlayingState = false }
        // Closing the audio line wakes a blocking SourceDataLine.write; interrupt wakes an idle
        // poll/sleep. The decode thread owns grabber.release() so FFmpeg is never released while
        // another thread is grabbing from it.
        runCatching { audioLine?.close() }
        if (decodeStarted.get()) decodeThread.interrupt()
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
            // Neither refillReadAheadQueue nor presentQueuedVideoFrame runs while paused — both are
            // reached only past this early return — so a full queue at the moment of a pause cannot
            // spin or wedge this idle branch: there is simply nothing left in decodeStep for a paused
            // pass to do besides poll pendingRate and sleep, exactly as before decode-ahead existed.
            applyPendingRateWhilePaused()
            sleepQuietly(IDLE_POLL_MS)
            return
        }
        applyPendingPlaybackClockChanges()
        // One more chance to drain a carried-over tail per pass, independent of what refill below
        // does — writeAudio already retries at its own top, and waitUntilPresentationTime retries
        // every pacing-sleep iteration, but this covers a pass that reaches neither of those (e.g.
        // refill finds the queue already full and grabs nothing new this pass).
        audioLine?.let { flushPendingAudio(it) }
        // Top the read-ahead queue back up to its bound — this is what pulls audio decoding ahead of
        // video presentation (see the class KDoc's "decode-ahead" section) — then pace/present
        // whatever is now at the front of it. Two separate steps rather than one combined loop so
        // each keeps its own single, simple exit condition (detekt's LoopWithTooManyJumpStatements).
        refillReadAheadQueue()
        presentQueuedVideoFrame()
    }

    /**
     * Grabs frames past whatever is already queued, up to [shouldReadAhead]'s bound, writing any
     * audio it encounters straight to the line (never queued — see the class KDoc) and cloning any
     * video frame into [videoQueue]. This is the decode-ahead step: it runs BEFORE
     * [presentQueuedVideoFrame]'s pacing wait, in the window that used to be pure idle sleep with no
     * decoding happening at all.
     *
     * `epoch` is captured once for the whole call rather than re-read from [seekEpoch] per
     * iteration: a seek arriving mid-refill must stop this call outright (its `while` condition
     * checks [isCurrentEpoch] every iteration) rather than keep grabbing-and-discarding under a
     * epoch nothing downstream will use — [applyPendingSeek] runs first thing on the very next
     * [decodeStep] and clears the queue wholesale regardless.
     */
    private fun refillReadAheadQueue() {
        val epoch = seekEpoch.get()
        while (!closed && playRequested && !grabberExhausted && isCurrentEpoch(epoch)) {
            if (!shouldReadAhead(videoQueueCount(), videoQueueAheadUs(), currentAudioBufferedAheadUs())) return
            val frame = grabNextFrame()
            if (frame == null) {
                // Null with no exception means end of stream — see grabNextFrame. Stop grabbing;
                // presentQueuedVideoFrame drains whatever is already queued before reporting stopped.
                grabberExhausted = true
                return
            }
            if (!isCurrentEpoch(epoch)) return // a seek landed while this grab() was in flight
            when {
                // JavaCV reuses this Frame's own buffers on the very next grab() call — a reference
                // held past that point would see corrupted/overwritten pixel data. clone() deep-copies
                // into freshly allocated native buffers before this frame goes anywhere near a queue
                // that outlives the current loop iteration; presentQueuedVideoFrame closes the clone
                // (releasing that native memory) once it has been shown or dropped.
                !frame.image.isNullOrEmpty() -> enqueueVideoFrame(frame.clone(), epoch)
                // Audio is written immediately, not queued: writeAudio fully copies the sample data
                // into either the line or pendingAudioTail before returning, so it never needs to
                // outlive this grab() the way a queued video Frame would.
                !frame.samples.isNullOrEmpty() -> presentAudioFrame(frame)
                else -> Unit // data/subtitle frames — nothing to do with them here
            }
        }
    }

    /**
     * Paces and shows (or drops) the video frame at the front of [videoQueue], mirroring what
     * `presentVideoFrame` used to do directly against a freshly grabbed frame — the only difference
     * is the frame now comes from the read-ahead queue instead of straight off the grabber.
     */
    private fun presentQueuedVideoFrame() {
        val head = peekVideoQueueHead()
        if (head == null) {
            // Nothing queued. If the grabber has also hit end of stream, there is nothing left to
            // ever produce another frame — only now is it correct to report playback as stopped.
            if (grabberExhausted) reportPlaybackStopped()
            return
        }
        if (!isCurrentEpoch(head.epoch)) {
            // Defensive backstop: applyPendingSeek() clears the queue wholesale as soon as it runs
            // (checked first thing every decodeStep), so this should be unreachable in practice, but
            // drop this one stale entry rather than presenting content under a superseded epoch.
            removeVideoQueueHead()?.frame?.let { runCatching { it.close() } }
            return
        }
        waitUntilPresentationTime(head.frame.timestamp, head.epoch)
        // Superseded while waiting (paused/closed/re-seeked) — leave it queued. pause()/
        // applyPendingSeek()/releaseAll() own clearing it in that case, not this function.
        if (closed || !playRequested || !isCurrentEpoch(head.epoch)) return

        val entry = removeVideoQueueHead() ?: return
        val frame = entry.frame
        try {
            // waitUntilPresentationTime returns as soon as the clock has reached (or already passed)
            // this frame's timestamp, without sleeping further — so a frame that was already late on
            // entry (refill fell behind, or rate > 1 made the clock outrun it) reaches here with a
            // positive latenessUs instead of ever causing a wait. Drop it before paying for the
            // conversion, which is the expensive part (see MAX_DECODE_LONG_EDGE_PX's comment).
            val latenessUs = currentClockUs() - frame.timestamp
            if (shouldDropLateFrame(latenessUs, consecutiveDrops)) {
                consecutiveDrops++
                // Still advance the published position to this frame's timestamp even though its
                // picture is being skipped — video-to-log sync (and the slider) must keep moving
                // forward with the clock rather than stalling on the last frame that was actually shown.
                publishClockPosition(frame.timestamp, entry.epoch)
                return
            }
            consecutiveDrops = 0
            val image = runCatching { converter.convert(frame) }.getOrNull() ?: return
            publishFrame(image, frame.timestamp, entry.epoch)
        } finally {
            // Release this clone's native buffers now that it has been shown or dropped — the
            // original (unclonded) grabber Frame this was copied from was already reused/overwritten
            // long ago by subsequent refill iterations.
            runCatching { frame.close() }
        }
    }

    private fun reportPlaybackStopped() {
        if (playRequested) {
            playRequested = false
            publishUiState { isPlayingState = false }
        }
    }

    // ── Read-ahead queue bookkeeping ─────────────────────────────────
    // ArrayDeque is not thread-safe; every access below goes through videoQueueLock because both
    // the decode thread (push/pop) and the UI thread (pause()'s clear) touch this queue. See
    // videoQueueLock's own field comment for the "why a lock, not a volatile" reasoning.

    private fun enqueueVideoFrame(frame: Frame, epoch: Long) {
        synchronized(videoQueueLock) { videoQueue.addLast(QueuedVideoFrame(frame, epoch)) }
    }

    private fun peekVideoQueueHead(): QueuedVideoFrame? = synchronized(videoQueueLock) { videoQueue.firstOrNull() }

    private fun removeVideoQueueHead(): QueuedVideoFrame? = synchronized(videoQueueLock) { videoQueue.removeFirstOrNull() }

    private fun videoQueueCount(): Int = synchronized(videoQueueLock) { videoQueue.size }

    private fun videoQueueAheadUs(): Long {
        val newestTimestampUs = synchronized(videoQueueLock) { videoQueue.lastOrNull()?.frame?.timestamp } ?: return 0L
        return queuedVideoDurationAheadUs(currentClockUs(), newestTimestampUs)
    }

    /**
     * The audio term [shouldReadAhead] compares against [AUDIO_READ_AHEAD_TARGET_US]: how far ahead
     * audio is already buffered, combining whatever the [SourceDataLine] itself already holds
     * (`line.bufferSize - line.available()`, i.e. bytes already written but not yet drained by the
     * device) with [pendingAudioTail] (bytes decoded but not yet even offered to the line — see
     * [writeAudio]) — see [audioBufferedAheadUs] for the bytes-to-microseconds conversion.
     *
     * Returns [Long.MAX_VALUE] — "fully satisfied, no override needed" — whenever audio isn't
     * actually being fed at all, so [shouldReadAhead] falls straight through to the ordinary
     * video-only soft bound instead of reading the term as permanently starved and refilling without
     * limit (bounded only by [HARD_MAX_QUEUED_VIDEO_FRAMES], which would turn every video-only file
     * into one that always buffers the hard cap's worth of frames):
     *  - No audio stream, or [openAudioLine] failed to open one (`audioLine == null`): there is
     *    nothing to wait on — a video-only file must read ahead exactly as it did before this change.
     *  - `rate != 1f`: [presentAudioFrame] silently drops every audio frame at any rate other than
     *    1x (see its own KDoc — no pitch-correct resampling here), so nothing would ever fill the
     *    buffer for as long as a non-1x rate is in effect; treating that as "starved" would make
     *    refill ignore the video queue's soft bound the entire time the rate stayed changed.
     *
     * Called only from the decode thread (via [refillReadAheadQueue]), same as [videoQueueAheadUs]
     * above — [grabber]/[audioFrameBytes] are safe to read from here for the same reasons documented
     * at their own declarations.
     */
    private fun currentAudioBufferedAheadUs(): Long {
        if (!hasAudioStream || rate != 1f) return Long.MAX_VALUE
        val line = audioLine ?: return Long.MAX_VALUE
        val bufferedBytes = (line.bufferSize - line.available()).toLong().coerceAtLeast(0L) + pendingAudioTail.size
        return audioBufferedAheadUs(bufferedBytes, grabber.sampleRate, audioFrameBytes)
    }

    /** Drops every queued read-ahead frame and releases its cloned native buffers — called
     *  everywhere [pendingAudioTail] is also cleared (pause, seek, close): a queued-but-unshown
     *  video frame is exactly as stale as carried-over PCM from before that same event. */
    private fun clearVideoQueue() {
        val drained = synchronized(videoQueueLock) {
            val copy = videoQueue.toList()
            videoQueue.clear()
            copy
        }
        // Closed outside the lock — Frame.close() only releases this clone's own native pointers, it
        // never touches the grabber or the queue, so there is nothing left to protect by that point.
        drained.forEach { runCatching { it.frame.close() } }
    }

    // Temporary diagnostic breadcrumbs (not gated behind debugLoggingEnabled's normal
    // info/warn/error selectivity — deliberately loud) for a reported Linux symptom where a video
    // opened as part of attaching a bug-report archive gets stuck at "Preparing timeline..."
    // forever: no error, no recovered duration, and critically NO other video-tagged log line
    // either, which an ordinary successful open also never produces (there is no "opened OK" log
    // today) — so the previous silence couldn't distinguish "worked fine" from "hung before
    // logging anything". These pin down exactly which step never returns.
    private fun openGrabber(): Boolean = runCatching {
        AppLogger.info(
            "video",
            "openGrabber: starting grabber.start() for $path (controller=${System.identityHashCode(this)})",
        )
        grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_S16)
        grabber.start()
        AppLogger.info("video", "openGrabber: grabber.start() returned")
        applyDecodeDownscale()
        val declaredDurationMs = grabber.lengthInTime / MICROS_PER_MS
        AppLogger.info("video", "openGrabber: declaredDurationMs=$declaredDurationMs")
        publishDurationState(observedDurationMs = declaredDurationMs)
        maybeStartDurationRecoveryScan(declaredDurationMs)
        hasAudioStream = grabber.hasAudio()
        AppLogger.info("video", "openGrabber: hasAudioStream=$hasAudioStream, opening audio line")
        if (hasAudioStream) openAudioLine()
        AppLogger.info("video", "openGrabber: complete")
        true
    }.getOrElse { t ->
        AppLogger.error("video", "failed to open video", t)
        publishUiState { errorState = t.message ?: t::class.simpleName ?: "Failed to open video" }
        publishDurationState(discoveryFinished = true)
        false
    }

    /**
     * `grabber.lengthInTime` reporting 0 (or negative) means the container's own header carries no
     * duration — see [scanDurationMs]'s KDoc for metadata steps 2/3 and
     * [scanDecodedTimestampDurationMs] for the decoded step 4. Declared-duration files return
     * immediately here and never pay for a scan.
     *
     * Runs on its own short-lived daemon thread, separate from both [decodeThread] and the UI
     * thread's Compose recomposition, exactly like [grabFrameAt] opens its own separate grabber
     * rather than touching this controller's playing one — spawning it here (from [openGrabber],
     * itself already running on [decodeThread]) is non-blocking, so opening a durationless file
     * never delays showing its first frame. [publishUiState] applies the immutable seek state from
     * this background thread into a mutable Compose snapshot, so the transport bar observes the
     * completed write rather than retaining the state from its initial composition. The normal
     * metadata pass is I/O-bound; decoding is deliberately deferred until it fails, so an unusual
     * source can take longer without delaying its first preview frame.
     *
     * Cancellation: both scans check [closed] for each packet/frame. A cancelled result preserves
     * DISCOVERING rather than publishing UNAVAILABLE, and the final publish is gated on `!closed`.
     * Each short-lived grabber is released inside its own `use` block.
     *
     * The publish itself goes through [growDurationIfNeeded] rather than a plain assignment: this
     * scan can take a few milliseconds, during which the decode thread may already have published
     * real playback positions (see [setPositionMs]) that pushed the duration up via step 5's
     * self-correcting growth. A recovered value that turned out lower than a position already
     * reached must not yank the duration back down — [growDurationIfNeeded] keeps this monotonic
     * either way, so the transport bar never regresses once a real length is showing.
     */
    private fun maybeStartDurationRecoveryScan(declaredMs: Long) {
        if (declaredMs > 0L) return
        AppLogger.info("video", "maybeStartDurationRecoveryScan: spawning scan thread")
        Thread({
            AppLogger.info("video", "duration recovery scan thread: started")
            val result = recoverDurationMs(
                scanPacketMetadata = {
                    runCatching { scanDurationMs(path) { closed } }
                        .onFailure { AppLogger.warn("video", "packet duration recovery failed", it) }
                        .getOrDefault(0L)
                },
                scanDecodedTimestamps = {
                    runCatching { scanDecodedTimestampDurationMs(path) { closed } }
                        .onFailure { AppLogger.warn("video", "decoded timestamp duration recovery failed", it) }
                        .getOrDefault(0L)
                },
                isCancelled = { closed },
            )
            // No path is included: diagnostics retain the recovery source and result without
            // retaining user-owned recording locations.
            AppLogger.info("video", "duration recovery source=${result.source} durationMs=${result.durationMs}")
            if (!closed && result.source != DurationRecoverySource.CANCELLED) {
                publishDurationState(
                    observedDurationMs = result.durationMs,
                    discoveryFinished = true,
                )
            }
        }, "indagium-video-duration-scan").apply { isDaemon = true }.start()
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
            .onFailure { AppLogger.warn("video", "seek failed", it) }
        runCatching { audioLine?.flush() }
        // Unlike pause() — see invalidatesDecodedState(SEEK, *), both true — a seek genuinely is a
        // discontinuity: it is about to make "the current position" mean something else entirely, so
        // anything already decoded for the OLD position (carried-over PCM, and read-ahead video
        // frames queued before this seek) is simply wrong to play. Gated on the shared predicate
        // (rather than an unconditional call) so this stays in lockstep with pause()/rate-change/
        // close, which consult the exact same table for their own transitions.
        if (invalidatesDecodedState(PlaybackTransition.SEEK, DecodedStateKind.AUDIO_CARRYOVER)) pendingAudioTail = ByteArray(0)
        if (invalidatesDecodedState(PlaybackTransition.SEEK, DecodedStateKind.VIDEO_QUEUE)) clearVideoQueue()
        // A seek — even backward from end of stream — repositions the grabber to produce frames
        // again, so a prior EOF no longer applies.
        grabberExhausted = false
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
                AppLogger.error("video", "image grab failed", it)
                publishUiState { errorState = it.message ?: "Playback error" }
            }
            .getOrNull()

        if (frame == null) {
            if (isInitialFrame && !closed) {
                publishUiState { errorState = "Video contains no decodable image frames" }
            }
            return
        }
        if (closed || !isCurrentEpoch(epoch)) return

        val image = runCatching { converter.convert(frame) }.getOrNull() ?: return
        publishFrame(image, frame.timestamp, epoch)
    }

    // Returns null both on a genuine grab failure (logged/surfaced below) and on a clean end of
    // stream — refillReadAheadQueue is the sole caller and treats both the same way (stop
    // refilling), so the two don't need to be distinguished here. On failure this also flips
    // playRequested off directly, which is what makes reportPlaybackStopped's own flip a no-op for
    // that case — an error already published isPlayingState = false.
    private fun grabNextFrame(): Frame? = runCatching { grabber.grab() }
        .onFailure {
            AppLogger.error("video", "video frame grab failed", it)
            publishUiState {
                errorState = it.message ?: "Playback error"
                isPlayingState = false
            }
            playRequested = false
        }
        .getOrNull()

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
    //
    // Still no grabber.grab() happens while this sleeps below (see the class KDoc for why one
    // decode thread can't wait on a future audio frame) — but that no longer means nothing useful
    // happened ahead of this wait: refillReadAheadQueue (called once per decodeStep, before this
    // function) already decoded and wrote out any audio up to the read-ahead bound BEFORE pacing
    // ever got here, which is what keeps the line fed through this gap instead of underrunning.
    // Draining any carry-over writeAudio left behind is still worth doing on every iteration too —
    // cheap, and covers the case where the line had no room left even for that ahead-of-time write.
    private fun waitUntilPresentationTime(targetUs: Long, epoch: Long) {
        while (!closed && playRequested && isCurrentEpoch(epoch)) {
            // The frame is already decoded, so the wall clock may advance smoothly toward its
            // timestamp without the slider getting ahead of the picture it is waiting to show.
            publishClockPosition(targetUs, epoch)
            audioLine?.let { flushPendingAudio(it) }
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
            // AUDIO_CARRYOVER: a rate change discards audio either way (presentAudioFrame mutes
            // whenever rate != 1x) — clear any carry-over so a later return to 1x doesn't play stale
            // pre-change PCM. See invalidatesDecodedState(RATE_CHANGE, *)'s KDoc for why VIDEO_QUEUE
            // is deliberately NOT cleared here: a queued video frame's picture stays correct for its
            // own timestamp at any rate, only whether it gets shown or skipped by shouldDropLateFrame
            // changes — nothing in it needs discarding just because the pace changed while paused.
            if (invalidatesDecodedState(PlaybackTransition.RATE_CHANGE, DecodedStateKind.AUDIO_CARRYOVER)) {
                pendingAudioTail = ByteArray(0)
            }
        }
    }

    private fun applyPendingPlaybackClockChanges() {
        pendingRate?.let { newRate ->
            val position = currentClockUs()
            rate = newRate
            pendingRate = null
            playbackStartNanos = System.nanoTime()
            playbackStartTimestampUs = position
            // Same AUDIO_CARRYOVER-only reasoning as applyPendingRateWhilePaused above — videoQueue
            // is deliberately left untouched; see invalidatesDecodedState(RATE_CHANGE, *)'s KDoc.
            if (invalidatesDecodedState(PlaybackTransition.RATE_CHANGE, DecodedStateKind.AUDIO_CARRYOVER)) {
                pendingAudioTail = ByteArray(0)
            }
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

    // Called once per waitUntilPresentationTime loop iteration — as often as every ~1ms in the
    // final approach to a frame's presentation time — purely to keep the slider tracking smoothly
    // while a frame waits to be shown, plus once per dropped frame in presentVideoFrame (which can
    // fire many times in a row at rate > 1). Each publish takes uiStateLock, applies a full Compose
    // snapshot, and (via setPositionMs -> publishDurationState) also takes presentationLock — worth
    // paying for at roughly one-display-frame granularity, not for every sub-millisecond wakeup.
    // Throttling inside this function (rather than only in the wait loop) covers both call sites at
    // once. The eventual accurate position always lands anyway, via publishFrame/publishPosition
    // once a frame is actually shown or dropped-and-skipped — this only limits how often the
    // in-between estimate is republished.
    private fun publishClockPosition(frameTimestampUs: Long, epoch: Long) {
        val now = System.nanoTime()
        if (now - lastClockPublishNanos < CLOCK_PUBLISH_MIN_INTERVAL_NANOS) return
        lastClockPublishNanos = now
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
            runCatching { publishUiState { currentFrameState = image.toComposeImageBitmap() } }
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

    // Step 5 of the duration-recovery chain (see growDurationIfNeeded's KDoc): every publish of a
    // real position is also a chance to notice the currently-known duration has been undershot, and
    // fix that on the spot. Routing every positionMsState write through this one function (rather than
    // duplicating the growth check at each call site) is what guarantees no future publish site can
    // forget it — the three current call sites are a seek, a shown frame, and a dropped-frame clock
    // advance, and any new one inherits the same guarantee for free.
    private fun setPositionMs(ms: Long) {
        publishUiState { positionMsState = ms }
        publishDurationState(observedDurationMs = ms)
    }

    /** Must run under [presentationLock] when called from playback; it also serializes the scan
     * thread's completion with those playback updates so duration/readiness stay monotonic. */
    private fun publishDurationState(observedDurationMs: Long = 0L, discoveryFinished: Boolean = false) {
        synchronized(presentationLock) {
            publishUiState {
                seekState = advanceVideoSeekState(seekState, observedDurationMs, discoveryFinished)
            }
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
            audioFrameBytes = (grabber.audioChannels * BYTES_PER_SAMPLE).coerceAtLeast(BYTES_PER_SAMPLE)
            audioCarryoverCapBytes = computeAudioCarryoverCapBytes(grabber.sampleRate, grabber.audioChannels)
            val bufferSizeBytes = computeAudioLineBufferSizeBytes(grabber.sampleRate, grabber.audioChannels)
            val line = (AudioSystem.getLine(info) as SourceDataLine).apply { open(format, bufferSizeBytes) }
            audioLine = line
            // open(format, bufferSize) only takes bufferSize as a HINT — the platform mixer may
            // clamp it to a device-native chunk/period size, and nothing before this ever checked
            // whether it actually got what was requested. A silently-clamped small buffer would
            // reintroduce exactly the underrun risk computeAudioLineBufferSizeBytes exists to avoid,
            // invisibly. Read the real size back and log if it's meaningfully smaller than asked.
            audioLineBufferClampWarning(bufferSizeBytes, line.bufferSize)?.let { AppLogger.warn("video", it) }
            gainControl = runCatching {
                audioLine?.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl
            }.getOrNull()
            applyGain()
            // play() may already have been called while this grabber was still opening (a large
            // HEVC file can take a while to reach here) — its own audioLine?.start() was a no-op
            // against a still-null line at that point. Start the line now if that intent is still
            // current, instead of leaving audio accumulating in a never-started line, silent until
            // the next pause/play toggle.
            if (playRequested) runCatching { audioLine?.start() }
        }.onFailure {
            AppLogger.warn("video", "no audio output line; playing video-only", it)
            hasAudioStream = false
        }
    }

    /** Writes as much of [pendingAudioTail] as currently fits into [line], frame-aligned, keeping
     *  whatever doesn't fit rather than discarding it. Called at the top of [writeAudio] and, so the
     *  line keeps draining even on a decode-loop pass that writes no new audio at all, once per
     *  pacing-sleep iteration in [waitUntilPresentationTime] and once per [decodeStep]. */
    private fun flushPendingAudio(line: SourceDataLine) {
        val tail = pendingAudioTail
        if (tail.isEmpty()) return
        val writable = alignToFrameBoundary(tail.size.coerceAtMost(line.available().coerceAtLeast(0)), audioFrameBytes)
        if (writable <= 0) return
        if (runCatching { line.write(tail, 0, writable) }.isFailure) return
        pendingAudioTail = if (writable >= tail.size) ByteArray(0) else tail.copyOfRange(writable, tail.size)
    }

    private fun writeAudio(frame: Frame) {
        val line = audioLine ?: return
        flushPendingAudio(line)
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
        // audio device from draining. Writing only what currently fits (aligned to a whole sample
        // frame via alignToFrameBoundary, so a stereo frame is never cut mid-sample) and carrying
        // the rest over in pendingAudioTail — instead of dropping it outright — is what
        // flushPendingAudio above retries on the next pass; video keeps its own timestamp clock and
        // software decode continues regardless. boundAudioCarryover bounds how far that carry-over
        // can grow, so a device that stops draining entirely still can't leak memory unboundedly.
        if (pendingAudioTail.isNotEmpty()) {
            // flushPendingAudio just ran and still couldn't drain everything — the line has no room
            // at all right now. Queue this frame's PCM behind the existing tail rather than writing
            // out of order.
            pendingAudioTail = boundAudioCarryover(pendingAudioTail + bytes, audioCarryoverCapBytes)
            return
        }
        val writableBytes = alignToFrameBoundary(bytes.size.coerceAtMost(line.available().coerceAtLeast(0)), audioFrameBytes)
        if (writableBytes > 0) runCatching { line.write(bytes, 0, writableBytes) }
        if (writableBytes < bytes.size) {
            pendingAudioTail = boundAudioCarryover(bytes.copyOfRange(writableBytes, bytes.size), audioCarryoverCapBytes)
        }
    }

    private fun encodePng(image: BufferedImage): ByteArray? = runCatching {
        ByteArrayOutputStream().use { out -> ImageIO.write(image, "png", out); out.toByteArray() }
    }.getOrNull()

    private fun releaseAll() {
        runCatching { audioLine?.stop() }
        runCatching { audioLine?.close() }
        // CLOSE invalidates everything — see invalidatesDecodedState(CLOSE, *), both true: the whole
        // pipeline is being torn down, so nothing already decoded has anywhere left to play. The
        // decode thread is also the sole owner of the queue by this point (runLoop's while(!closed)
        // has already exited, so refillReadAheadQueue/presentQueuedVideoFrame won't touch it again)
        // — release every still-queued clone's native buffers rather than leaking them.
        if (invalidatesDecodedState(PlaybackTransition.CLOSE, DecodedStateKind.VIDEO_QUEUE)) clearVideoQueue()
        runCatching { grabber.release() }
    }

    private fun sleepQuietly(ms: Long) {
        runCatching { Thread.sleep(ms.coerceAtLeast(1)) }
    }

    private fun wakeDecoder() {
        decodeThread.interrupt()
    }
}
