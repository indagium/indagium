package com.indagium.capture

import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec.AV_PKT_FLAG_KEY
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_copy
import org.bytedeco.ffmpeg.global.avformat.AVFMT_NOFILE
import org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE
import org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_write_trailer
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_free_context
import org.bytedeco.ffmpeg.global.avformat.avformat_new_stream
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avformat.avformat_write_header
import org.bytedeco.ffmpeg.global.avformat.avio_closep
import org.bytedeco.ffmpeg.global.avformat.avio_open
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AVERROR_INVALIDDATA
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.av_dict_copy
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_strerror
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

private const val MICROS_PER_SECOND = 1_000_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val COPY_BUFFER_BYTES = 1024 * 1024
private const val FFMPEG_ERROR_BUFFER_BYTES = 256L
private val NO_FORMAT_OPTIONS: PointerPointer<*>? = null

/**
 * How far a keyframe-aligned start may precede the requested start before it's worth paying for a
 * decode/re-encode instead. Below this, [remux]'s ordinary keyframe-aligned clip is both cheaper
 * and lossless (no re-encode generation loss), and the extra few hundred ms of leading video is a
 * reasonable trade.
 */
private const val EXACT_START_TOLERANCE_MS = 500L

/** How far after [reencodeFromRequestedStart]'s target keyframe a seek's landed frame may be and
 * still be trusted — see [positionAtOrBeforeKeyframe]. Generous (well above ordinary decoder/
 * timestamp rounding) since landing early is always safe; this only guards against landing late. */
private const val SEEK_VERIFY_TOLERANCE_US = 200_000L

/**
 * H.264 encoders to try, in order, for [reencodeFromRequestedStart]. `libopenh264` is bytedeco's
 * bundled non-GPL software encoder and is expected on every platform this app ships JavaCPP FFmpeg
 * for; `libx264` covers a differently-built FFmpeg (e.g. a GPL build) that omits libopenh264;
 * `h264_videotoolbox` is macOS's hardware encoder, tried last since the other two are portable.
 * [avcodec_find_encoder_by_name] returns a null pointer (never a Kotlin `null`) for a name this
 * FFmpeg build wasn't compiled with, so probing in order and taking the first real hit is enough —
 * no platform-sniffing needed. Verified against the bundled ffmpeg 8.0.1-1.5.13 build this project
 * pins (build.gradle.kts): libopenh264 and h264_videotoolbox are both present, libx264 is not.
 */
private val EXACT_START_ENCODER_CANDIDATES = listOf("libopenh264", "libx264", "h264_videotoolbox")
private const val REENCODE_FALLBACK_FRAME_RATE = 30.0
private const val REENCODE_FALLBACK_BITRATE = 8_000_000

/**
 * Exports a playable interval from the immutable prefix of a scrcpy Matroska recording that exists
 * when [export] starts. The encoded packets are remuxed; the source is never sought, truncated, or
 * otherwise modified and no codec is required on the machine beyond the bundled JavaCPP FFmpeg.
 */
class FfmpegCaptureVideoExporter(
    // Test-only observability seam: [reencodeFromRequestedStart] reports whether it trusted its
    // keyframe seek or fell back to decoding from the start of the snapshot, and how many frames it
    // had to read before reaching the requested start. Production never sets this; tests use it to
    // prove seeking (not a full linear decode of a long recording) is what positions the decoder —
    // see FfmpegCaptureVideoExporterTest.
    private val reencodeDiagnosticsHook: ((ReencodeDiagnostics) -> Unit)? = null,
) : CaptureVideoExporter, CaptureVideoCoverageProbe {
    override fun export(
        source: File,
        destination: File,
        requestedStartMs: Long,
        requestedEndMs: Long,
    ): CaptureVideoClip {
        require(requestedStartMs >= 0L) { "Video export start must be non-negative" }
        require(requestedEndMs > requestedStartMs) { "Video export end must be after its start" }
        require(source.isFile) { "Capture video does not exist: ${source.absolutePath}" }
        require(source.canonicalFile != destination.canonicalFile) { "Video destination must differ from its capture source" }

        // MP4 export (archive v3): the container is chosen by the DESTINATION's own extension —
        // CaptureArchive.kt's export() picks "screen.mp4" or "screen.mkv" per the session's
        // CaptureSettings.videoContainer — rather than a new exporter parameter, so every existing
        // `CaptureVideoExporter { source, destination, start, end -> ... }` test fake (this codebase
        // has dozens) keeps compiling unchanged. See [muxFormatFor]'s own doc for what each format
        // means for the two write paths below.
        val mp4 = isMp4Destination(destination)
        val destinationParent = destination.absoluteFile.parentFile
            ?: throw IOException("Video destination has no parent: ${destination.absolutePath}")
        destinationParent.mkdirs()
        val staging = Files.createTempFile(destinationParent.toPath(), ".capture-export-", if (mp4) ".mp4" else ".mkv").toFile()
        try {
            // One shared snapshot for both the scan and the remux (unlike coverageEndMs(), which
            // only ever needs the scan): scanWindow()'s window bounds must be valid against exactly
            // the bytes remux() reads, and a single copy is also half the I/O of taking two.
            val clip = withPrefixSnapshot(source) { snapshot ->
                val window = scanWindow(snapshot, requestedStartMs, requestedEndMs)
                val keyframeGapMs = requestedStartMs - window.actualStartUs / MILLIS_PER_SECOND
                val exact = if (keyframeGapMs > EXACT_START_TOLERANCE_MS) {
                    reencodeFromRequestedStart(
                        snapshot,
                        staging,
                        requestedStartMs,
                        window.actualStartUs,
                        window.coveredEndUs,
                        mp4,
                        reencodeDiagnosticsHook,
                    )
                } else {
                    null
                }
                if (exact != null) {
                    exact
                } else {
                    remux(snapshot, staging, window, mp4)
                    CaptureVideoClip(
                        actualStartMs = window.actualStartUs / MILLIS_PER_SECOND,
                        coveredEndMs = window.coveredEndUs / MILLIS_PER_SECOND,
                        durationMs = (window.coveredEndUs - window.actualStartUs) / MILLIS_PER_SECOND,
                    )
                }
            }
            replaceDestination(staging, destination)
            return clip
        } finally {
            staging.delete()
        }
    }

    /**
     * See [CaptureVideoCoverageProbe]: a read-only scan of the *live* [source] — deliberately not
     * [withPrefixSnapshot]'s copy-then-scan. [export]/[reencodeFromRequestedStart] need a frozen,
     * byte-identical copy because [remux] does a second pass that must see exactly what [scanWindow]
     * saw; this probe only ever reads, once, so there is nothing a second pass could disagree with.
     * Copying was previously done here too, out of caution, but for a multi-GB growing recording
     * that meant copying the whole file on every ~200ms poll while a snapshot popover is open — for
     * an hour-long capture, gigabytes of I/O per Save, repeated on every preview tick. [inputLength]
     * is sampled exactly once and passed straight to [readPackets], whose existing
     * INVALIDDATA-at-physical-EOF tolerance already handles a cluster still being flushed past that
     * point — the same tolerance [withPrefixSnapshot]'s copy exists to make *simpler* to reason
     * about, not the only thing that makes a growing-file read safe.
     */
    override fun coverageEndMs(source: File, requestedStartMs: Long, requestedEndMs: Long): Long {
        require(requestedStartMs >= 0L) { "Video coverage probe start must be non-negative" }
        require(requestedEndMs > requestedStartMs) { "Video coverage probe end must be after its start" }
        require(source.isFile) { "Capture video does not exist: ${source.absolutePath}" }
        val inputLength = source.length()
        require(inputLength > 0L) { "Capture video has no readable bytes: ${source.absolutePath}" }
        val window = withInput(source) { input -> scanWindow(input, inputLength, requestedStartMs, requestedEndMs) }
        return window.coveredEndUs / MILLIS_PER_SECOND
    }
}

/**
 * Snapshots the current readable prefix of a growing [source] into a scratch file in the system
 * temp directory and runs [block] against it, deleting the scratch file afterwards.
 */
private fun <T> withPrefixSnapshot(source: File, block: (File) -> T): T {
    val snapshot = Files.createTempFile(".capture-snapshot-", ".mkv").toFile()
    try {
        copyCurrentPrefix(source, snapshot)
        return block(snapshot)
    } finally {
        snapshot.delete()
    }
}

/**
 * Attempts a frame-accurate clip start: decodes [snapshot] from its preceding keyframe and
 * re-encodes only the video frames from [requestedStartMs] up to [coveredEndUs] (a bound already
 * verified complete by [scanWindow] — reused as-is rather than re-derived, see the call site).
 * Returns null — never throws for an ordinary "can't do this" reason — when no bundled H.264
 * encoder is available, the source has no usable video, or nothing decodes in range; the caller
 * falls back to [remux]'s always-correct keyframe-aligned clip in every such case. Every ordinary
 * decode/encode failure (unsupported pixel format, an encoder that reports available but rejects
 * this stream, a grabber/recorder exception, etc.) is deliberately swallowed for exactly that same
 * fallback — [CaptureArchiveExporter.export]'s caller only ever sees the outcome (an exact-start
 * clip or a keyframe-aligned one), never this internal decision. Only a genuine cancellation
 * ([InterruptedIOException]) is not "ordinary" and propagates.
 *
 * Audio is intentionally dropped here (see [EXACT_START_ENCODER_CANDIDATES]'s neighbourhood): a
 * snapshot's audio track doesn't need frame-exact alignment the way video does for
 * [CaptureArchiveExporter]'s log-to-video row mapping, and re-encoding it in lockstep with video
 * here would roughly double this function's failure surface for no correctness benefit.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException", "LongParameterList")
private fun reencodeFromRequestedStart(
    snapshot: File,
    staging: File,
    requestedStartMs: Long,
    keyframeStartUs: Long,
    coveredEndUs: Long,
    mp4: Boolean,
    onDiagnostics: ((ReencodeDiagnostics) -> Unit)?,
): CaptureVideoClip? {
    val encoder = EXACT_START_ENCODER_CANDIDATES.firstNotNullOfOrNull { name ->
        val codec = avcodec_find_encoder_by_name(name)
        if (codec != null && !codec.isNull) name to codec.id() else null
    } ?: return null
    return try {
        reencodeWithEncoder(
            snapshot,
            staging,
            requestedStartMs * MILLIS_PER_SECOND,
            keyframeStartUs,
            coveredEndUs,
            encoder.first,
            encoder.second,
            mp4,
            onDiagnostics,
        )
    } catch (failure: InterruptedIOException) {
        throw failure
    } catch (failure: Exception) {
        null
    }
}

/** Test-only observability for [reencodeFromRequestedStart] — see [FfmpegCaptureVideoExporter]'s
 * `reencodeDiagnosticsHook` constructor parameter. Public only because that constructor parameter
 * (though itself `private`) is part of a public class's primary constructor signature. */
data class ReencodeDiagnostics(val usedSeek: Boolean, val framesReadBeforeStart: Int)

@Suppress("LongParameterList")
private fun reencodeWithEncoder(
    snapshot: File,
    staging: File,
    requestedStartUs: Long,
    keyframeStartUs: Long,
    coveredEndUs: Long,
    encoderName: String,
    encoderId: Int,
    mp4: Boolean,
    onDiagnostics: ((ReencodeDiagnostics) -> Unit)?,
): CaptureVideoClip? {
    var grabber = FFmpegFrameGrabber(snapshot)
    try {
        grabber.start()
        if (grabber.videoStream < 0 || grabber.imageWidth <= 0 || grabber.imageHeight <= 0) return null
        val positioned = positionAtOrBeforeKeyframe(grabber, snapshot, keyframeStartUs)
        grabber = positioned.grabber
        var framesReadBeforeStart = 0
        val start = FFmpegFrameRecorder(staging, grabber.imageWidth, grabber.imageHeight, 0).use { recorder ->
            configureReencodeRecorder(recorder, grabber, encoderName, encoderId, mp4)
            encodeFramesInRange(grabber, recorder, requestedStartUs, coveredEndUs, positioned.landedFrame) {
                framesReadBeforeStart++
            }
        } ?: return null
        onDiagnostics?.invoke(ReencodeDiagnostics(positioned.usedSeek, framesReadBeforeStart))
        return CaptureVideoClip(
            actualStartMs = start / MILLIS_PER_SECOND,
            coveredEndMs = coveredEndUs / MILLIS_PER_SECOND,
            durationMs = (coveredEndUs - start) / MILLIS_PER_SECOND,
        )
    } finally {
        runCatching { grabber.stop() }
        runCatching { grabber.release() }
    }
}

private class PositionedGrabber(val grabber: FFmpegFrameGrabber, val usedSeek: Boolean, val landedFrame: Frame?)

/**
 * Positions [grabber] at or immediately before [keyframeStartUs] — already known, from
 * [scanWindow]'s own packet-level scan, to be a real keyframe at or before the requested start — so
 * the caller doesn't have to linearly software-decode every frame from the beginning of a
 * potentially very long recording just to reach a point already found. Tries
 * `FFmpegFrameGrabber.setTimestamp` (the cheap, single-seek path — not `setVideoTimestamp`, whose
 * own frame-accurate refinement loop can itself decode many frames when the source's reported frame
 * rate is unreliable, exactly the situation [encodeFramesInRange]'s doc describes) and verifies the
 * result by grabbing one frame and checking it is actually a keyframe at or close to
 * [keyframeStartUs], before trusting it.
 *
 * Verification matters because a live, still-growing scrcpy MKV snapshot has no Matroska Cues
 * element (that's written into the trailer, which a growing recording doesn't have yet), so FFmpeg
 * has to fall back to its own heuristic index-building when seeking such a file — behaviour this
 * project doesn't control and hadn't previously exercised. On any failure (a thrown exception, or a
 * frame that doesn't check out — wrong type, or landed after [keyframeStartUs] beyond a small
 * tolerance), the original [grabber] is discarded and [snapshot] is reopened fresh, falling back to
 * decoding from the very start — the exact, already-verified-correct behaviour this optimization
 * replaces, just slower.
 */
private fun positionAtOrBeforeKeyframe(grabber: FFmpegFrameGrabber, snapshot: File, keyframeStartUs: Long): PositionedGrabber {
    val landed = runCatching {
        grabber.setTimestamp(keyframeStartUs)
        grabber.grabImage()
    }.getOrNull()
    val verified = landed != null && landed.keyFrame && landed.timestamp <= keyframeStartUs + SEEK_VERIFY_TOLERANCE_US
    if (verified) return PositionedGrabber(grabber, usedSeek = true, landedFrame = landed)
    runCatching { grabber.stop() }
    runCatching { grabber.release() }
    val fresh = FFmpegFrameGrabber(snapshot)
    fresh.start()
    return PositionedGrabber(fresh, usedSeek = false, landedFrame = null)
}

/**
 * Both `videoCodec` and `videoCodecName` must be set together: `FFmpegFrameRecorder` resolves the
 * encoder from `videoCodec` (an AVCodecID) first when it isn't `AV_CODEC_ID_NONE`, and only
 * consults `videoCodecName` to disambiguate which encoder implements that ID — `videoCodecName`
 * alone (its default `videoCodec` left at `AV_CODEC_ID_MPEG4`, javacv's own class default) silently
 * produced an MPEG-4 clip instead of the intended H.264 encoder during manual verification of this
 * function.
 */
private fun configureReencodeRecorder(
    recorder: FFmpegFrameRecorder,
    grabber: FFmpegFrameGrabber,
    encoderName: String,
    encoderId: Int,
    mp4: Boolean,
) {
    recorder.format = muxFormatFor(mp4)
    recorder.videoCodec = encoderId
    recorder.videoCodecName = encoderName
    recorder.frameRate = grabber.videoFrameRate.takeIf { it > 0.0 } ?: REENCODE_FALLBACK_FRAME_RATE
    recorder.videoBitrate = REENCODE_FALLBACK_BITRATE
    recorder.setDisplayRotation(grabber.displayRotation)
    // MP4 export (archive v3): without +faststart the moov atom (the file's index) lands at the
    // very END of the file, so nothing can start playback until the whole download/copy finishes —
    // exactly the "unreadable until the trailer is written" property that keeps live recording on
    // Matroska in the first place (see this file's own module doc). A finished export is a
    // completed, static file either way, so paying to relocate moov to the front here costs nothing
    // the live recorder couldn't already afford, and makes the exported file genuinely streamable.
    if (mp4) recorder.setOption("movflags", "faststart")
}

/** The container FORMAT NAME FFmpeg's muxer registry expects — distinct from [CaptureVideoContainer]
 *  (a settings-facing enum in CaptureModels.kt) and from the archive/destination FILE EXTENSION
 *  ("mp4"/"mkv"): here it's "matroska", not "mkv". [isMp4Destination] is what actually decides `mp4`
 *  for both this and [remux] — inferred from the destination FILE's own extension, not a new
 *  exporter parameter, so [CaptureVideoExporter]'s single-abstract-method contract (and every
 *  existing SAM-lambda test fake built against it) is untouched. */
private fun muxFormatFor(mp4: Boolean): String = if (mp4) "mp4" else "matroska"

private fun isMp4Destination(destination: File): Boolean = destination.extension.equals("mp4", ignoreCase = true)

/**
 * Encodes every frame with a timestamp in `[requestedStartUs, coveredEndUs)` and returns the first
 * encoded frame's timestamp (microseconds), or null when nothing fell in range.
 *
 * `recorder.record(Frame)` does **not** read [org.bytedeco.javacv.Frame.timestamp] — verified
 * against javacv 1.5.13's source: it forwards straight to `recordImage`, which stamps each frame by
 * auto-incrementing the recorder's own internal frame counter at a fixed `1 / recorder.frameRate`
 * spacing, entirely ignoring how far apart the *source* frames actually were. A real scrcpy
 * recording's grabbed frame rate is unreliable in exactly the way that makes this bite hardest —
 * verified manually against the connected emulator, where the source reported an ~1000 nominal
 * rate (Matroska's container timebase, not a real fps), and the encoded clip ended up ~1ms of
 * output per source frame: several real seconds of capture compressed into well under 100ms of
 * playable video, while [CaptureVideoClip]'s own reported bounds (built from decode-side timestamps
 * below, not the recorder's output) still claimed the correct multi-second span. `setTimestamp`
 * (called once per frame, rebased so the clip's own first frame lands at 0) is what makes the
 * recorder's output timeline match real elapsed time regardless of that nominal rate.
 */
private fun encodeFramesInRange(
    grabber: FFmpegFrameGrabber,
    recorder: FFmpegFrameRecorder,
    requestedStartUs: Long,
    coveredEndUs: Long,
    // A frame [positionAtOrBeforeKeyframe] already grabbed while verifying its seek landed — fed
    // into this loop first so that successful verification doesn't also discard the very frame it
    // proved was usable.
    pregrabbedFrame: Frame?,
    onFrameReadBeforeStart: () -> Unit,
): Long? {
    var firstUs: Long? = null
    var started = false
    var pending = pregrabbedFrame
    while (true) {
        checkInterrupted()
        val frame = pending ?: grabber.grabImage()
        pending = null
        if (frame == null || frame.timestamp >= coveredEndUs) break
        if (frame.timestamp >= requestedStartUs) {
            if (!started) {
                recorder.start()
                started = true
            }
            if (firstUs == null) firstUs = frame.timestamp
            recorder.timestamp = frame.timestamp - firstUs
            recorder.record(frame)
        } else {
            onFrameReadBeforeStart()
        }
    }
    if (started) recorder.stop()
    return firstUs
}

private data class ExportWindow(val actualStartUs: Long, val coveredEndUs: Long, val videoStreamIndex: Int)

/** File.length() is sampled once: bytes appended after this point are deliberately invisible. */
private fun copyCurrentPrefix(source: File, snapshot: File) {
    val prefixLength = source.length()
    if (prefixLength <= 0L) throw IOException("Capture video has no readable bytes: ${source.absolutePath}")
    FileChannel.open(source.toPath(), StandardOpenOption.READ).use { input ->
        FileChannel.open(snapshot.toPath(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { output ->
            val buffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES)
            var remaining = prefixLength
            while (remaining > 0L) {
                checkInterrupted()
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                buffer.flip()
                while (buffer.hasRemaining()) output.write(buffer)
                remaining -= read
            }
            if (remaining != 0L) {
                throw IOException("Capture video became shorter while its prefix was being snapshotted")
            }
            output.force(false)
        }
    }
}

/** Scans a static, already-copied snapshot file — used by [export]/[reencodeFromRequestedStart],
 * where the bytes [remux] reads must be exactly the bytes this scan saw. */
private fun scanWindow(snapshot: File, requestedStartMs: Long, requestedEndMs: Long): ExportWindow =
    withInput(snapshot) { input -> scanWindow(input, snapshot.length(), requestedStartMs, requestedEndMs) }

/**
 * Core scan, parameterized by an explicit [inputLength] rather than re-reading `File.length()`
 * internally: [coverageEndMs] calls this directly against the *live*, still-growing [source] file
 * (see that function's doc for why), where the length must be sampled exactly once up front and
 * reused for the whole scan — a second `File.length()` call partway through would race the writer.
 */
@Suppress("ThrowsCount")
private fun scanWindow(input: AVFormatContext, inputLength: Long, requestedStartMs: Long, requestedEndMs: Long): ExportWindow {
    val videoStreamIndex = firstVideoStream(input)
    val requestedStartUs = requestedStartMs * MILLIS_PER_SECOND
    val requestedEndUs = requestedEndMs * MILLIS_PER_SECOND
    var actualStartUs: Long? = null
    var coveredEndUs = Long.MIN_VALUE
    var readableVideoPackets = 0L
    readPackets(input, inputLength) { packet ->
        if (packet.stream_index() != videoStreamIndex) return@readPackets
        val stream = input.streams(videoStreamIndex)
        val startUs = packetTimestampUs(packet, stream) ?: return@readPackets
        val endUs = packetEndUs(packet, stream, startUs)
        readableVideoPackets++
        if ((packet.flags() and AV_PKT_FLAG_KEY) != 0) {
            when {
                startUs <= requestedStartUs -> {
                    // A later preceding keyframe supersedes any earlier candidate and its
                    // coverage. Starting at an earlier keyframe would include unnecessary
                    // video and can make the reported interval inconsistent with remuxing.
                    actualStartUs = startUs
                    coveredEndUs = Long.MIN_VALUE
                }
                actualStartUs == null && startUs < requestedEndUs -> {
                    // Video may start after the session's monotonic zero (for example when
                    // another stream starts first). Report the first usable video keyframe.
                    actualStartUs = startUs
                    coveredEndUs = Long.MIN_VALUE
                }
            }
        }
        val selectedStartUs = actualStartUs
        if (selectedStartUs != null && startUs >= selectedStartUs && startUs < requestedEndUs && endUs <= requestedEndUs) {
            coveredEndUs = maxOf(coveredEndUs, endUs)
        }
    }
    if (readableVideoPackets == 0L) throw IOException("Capture snapshot contains no readable video packets")
    val actualStart = actualStartUs
        ?: throw IOException("Capture snapshot has no readable keyframe in the requested interval")
    if (coveredEndUs <= actualStart) {
        throw IOException("Capture snapshot contains no complete video in the requested interval")
    }
    return ExportWindow(actualStart, coveredEndUs, videoStreamIndex)
}

private fun remux(snapshot: File, staging: File, window: ExportWindow, mp4: Boolean) {
    withInput(snapshot) { input ->
        val outputPointer = PointerPointer<Pointer>(1)
        outputPointer.put(null as Pointer?)
        var output: AVFormatContext? = null
        var outputOpened = false
        var headerWritten = false
        var headerOptions: AVDictionary? = null
        try {
            BytePointer(muxFormatFor(mp4)).use { formatName ->
                BytePointer(staging.absolutePath).use { destinationName ->
                    ffmpegCheck(
                        avformat_alloc_output_context2(outputPointer, null, formatName, destinationName),
                        "allocate ${muxFormatFor(mp4)} output",
                    )
                }
            }
            output = AVFormatContext(outputPointer.get())
            if (output.isNull) throw IOException("FFmpeg did not allocate an output context")
            val context = output
            val streamMap = copyStreams(input, context)
            if ((context.oformat().flags() and AVFMT_NOFILE) == 0) {
                openOutputIo(context, staging)
                outputOpened = true
            }
            copyMetadata(input.metadata(), "copy container metadata") { context.metadata(it) }
            // MP4 export (archive v3): +faststart moves the moov atom to the front of the file — see
            // configureReencodeRecorder's matching comment for why that's worth doing on every
            // export (this is the exact-start reencode path's raw-AVFormatContext sibling; both
            // write paths need it, not just one).
            if (mp4) {
                headerOptions = AVDictionary(null).also { av_dict_set(it, "movflags", "faststart", 0) }
            }
            ffmpegCheck(avformat_write_header(context, headerOptions), "write export header")
            headerWritten = true

            readPackets(input, snapshot.length()) { packet ->
                val inputIndex = packet.stream_index()
                val outputIndex = streamMap[inputIndex]
                if (outputIndex < 0) return@readPackets
                val inputStream = input.streams(inputIndex)
                if (!packetIsInWindow(packet, inputStream, inputIndex == window.videoStreamIndex, window)) {
                    return@readPackets
                }
                val outputStream = context.streams(outputIndex)
                rebasePacket(packet, inputStream, outputStream, window.actualStartUs)
                packet.stream_index(outputIndex).pos(-1L)
                ffmpegCheck(av_interleaved_write_frame(context, packet), "write export packet")
            }
            checkInterrupted()
            val trailerResult = av_write_trailer(context)
            headerWritten = false
            ffmpegCheck(trailerResult, "finalize export")
        } finally {
            val context = output
            if (headerWritten && context != null) runCatching { av_write_trailer(context) }
            if (outputOpened && context != null) closeOutputIo(context)
            if (context != null && !context.isNull) avformat_free_context(context)
            headerOptions?.let { if (!it.isNull) av_dict_free(it) }
            outputPointer.close()
        }
    }
}

private fun openOutputIo(output: AVFormatContext, destination: File) {
    val ioPointer = PointerPointer<Pointer>(1)
    try {
        ioPointer.put(null as Pointer?)
        BytePointer(destination.absolutePath).use { destinationName ->
            ffmpegCheck(avio_open(ioPointer, destinationName, AVIO_FLAG_WRITE), "open export output")
        }
        val io = AVIOContext(ioPointer.get())
        if (io.isNull) throw IOException("FFmpeg did not allocate export output I/O")
        output.pb(io)
    } finally {
        ioPointer.close()
    }
}

private fun closeOutputIo(output: AVFormatContext) {
    val io = output.pb() ?: return
    if (io.isNull) return
    val ioPointer = PointerPointer<Pointer>(1)
    try {
        ioPointer.put(io)
        avio_closep(ioPointer)
        output.pb(null)
    } finally {
        ioPointer.close()
    }
}

/** Copies into a real native pointer slot, then transfers ownership to the format context. */
private fun copyMetadata(source: AVDictionary?, operation: String, install: (AVDictionary) -> Unit) {
    if (source == null || source.isNull) return
    val destination = PointerPointer<Pointer>(1)
    try {
        destination.put(null as Pointer?)
        ffmpegCheck(av_dict_copy(destination, source, 0), operation)
        val copy = AVDictionary(destination.get())
        if (!copy.isNull) install(copy)
    } finally {
        destination.close()
    }
}

private fun copyStreams(input: AVFormatContext, output: AVFormatContext): IntArray {
    val mapping = IntArray(input.nb_streams()) { -1 }
    repeat(input.nb_streams()) { index ->
        checkInterrupted()
        val inputStream = input.streams(index)
        val outputStream = avformat_new_stream(output, null)
        if (outputStream == null || outputStream.isNull) throw IOException("FFmpeg could not allocate output stream $index")
        ffmpegCheck(avcodec_parameters_copy(outputStream.codecpar(), inputStream.codecpar()), "copy stream $index codec")
        outputStream.id(inputStream.id())
        outputStream.disposition(inputStream.disposition())
        outputStream.time_base().num(inputStream.time_base().num()).den(inputStream.time_base().den())
        outputStream.avg_frame_rate().num(inputStream.avg_frame_rate().num()).den(inputStream.avg_frame_rate().den())
        outputStream.r_frame_rate().num(inputStream.r_frame_rate().num()).den(inputStream.r_frame_rate().den())
        outputStream.sample_aspect_ratio()
            .num(inputStream.sample_aspect_ratio().num())
            .den(inputStream.sample_aspect_ratio().den())
        copyMetadata(inputStream.metadata(), "copy stream $index metadata") { outputStream.metadata(it) }
        mapping[index] = outputStream.index()
    }
    return mapping
}

private fun packetIsInWindow(
    packet: AVPacket,
    stream: AVStream,
    video: Boolean,
    window: ExportWindow,
): Boolean {
    val startUs = packetTimestampUs(packet, stream) ?: return false
    val endUs = packetEndUs(packet, stream, startUs)
    return if (video) {
        startUs >= window.actualStartUs && startUs < window.coveredEndUs && endUs <= window.coveredEndUs
    } else {
        endUs > window.actualStartUs && startUs < window.coveredEndUs
    }
}

private fun rebasePacket(packet: AVPacket, input: AVStream, output: AVStream, startUs: Long) {
    val offset = microsToTicks(startUs, input)
    if (packet.pts() != AV_NOPTS_VALUE) packet.pts(packet.pts() - offset)
    if (packet.dts() != AV_NOPTS_VALUE) packet.dts(packet.dts() - offset)
    av_packet_rescale_ts(packet, input.time_base(), output.time_base())
}

private fun firstVideoStream(input: AVFormatContext): Int {
    repeat(input.nb_streams()) { index ->
        if (input.streams(index).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) return index
    }
    throw IOException("Capture snapshot contains no video stream")
}

private inline fun <T> withInput(file: File, block: (AVFormatContext) -> T): T {
    checkInterrupted()
    val inputPointer = PointerPointer<Pointer>(1)
    inputPointer.put(null as Pointer?)
    var opened = false
    try {
        BytePointer(file.absolutePath).use { filename ->
            ffmpegCheck(avformat_open_input(inputPointer, filename, null, NO_FORMAT_OPTIONS), "open capture snapshot")
        }
        opened = true
        val input = AVFormatContext(inputPointer.get())
        if (input.isNull) throw IOException("FFmpeg did not allocate an input context")
        val infoResult = avformat_find_stream_info(input, NO_FORMAT_OPTIONS)
        if (infoResult < 0 && input.nb_streams() == 0) ffmpegCheck(infoResult, "read capture stream information")
        return block(input)
    } finally {
        if (opened) avformat_close_input(inputPointer)
        inputPointer.close()
    }
}

/** Truncated live tails may end as INVALIDDATA only after the demuxer consumed the physical EOF. */
private inline fun readPackets(input: AVFormatContext, inputLength: Long, consume: (AVPacket) -> Unit) {
    val packet = org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc()
        ?: throw IOException("FFmpeg could not allocate a packet")
    var packetsRead = 0L
    try {
        while (true) {
            checkInterrupted()
            val result = av_read_frame(input, packet)
            if (result < 0) {
                val inputEndedMidElement = result == AVERROR_INVALIDDATA &&
                    packetsRead > 0L && atPhysicalEof(input, inputLength)
                if (result == AVERROR_EOF || inputEndedMidElement) break
                ffmpegCheck(result, "read capture packet")
            }
            consume(packet)
            packetsRead++
            av_packet_unref(packet)
        }
    } finally {
        av_packet_unref(packet)
        av_packet_free(packet)
    }
}

/**
 * `AVIOContext.pos` points after the buffered data. Subtract unread bytes so corrupt data that
 * happens to share a read-ahead buffer with EOF remains an error instead of looking like a live
 * recording's incomplete final element.
 */
private fun atPhysicalEof(input: AVFormatContext, inputLength: Long): Boolean {
    val io = input.pb()
    if (io.isNull || io.eof_reached() == 0) return false
    val unreadBytes = (io.buf_end().address() - io.buf_ptr().address()).coerceAtLeast(0L)
    return io.pos() - unreadBytes >= inputLength
}

private fun packetTimestampUs(packet: AVPacket, stream: AVStream): Long? {
    val timestamp = if (packet.pts() != AV_NOPTS_VALUE) packet.pts() else packet.dts()
    if (timestamp == AV_NOPTS_VALUE || stream.time_base().den() == 0) return null
    return ticksToMicros(timestamp, stream)
}

private fun packetEndUs(packet: AVPacket, stream: AVStream, startUs: Long): Long {
    if (packet.duration() <= 0L) return startUs
    return startUs + ticksToMicros(packet.duration(), stream).coerceAtLeast(0L)
}

private fun ticksToMicros(ticks: Long, stream: AVStream): Long =
    (ticks.toDouble() * stream.time_base().num() * MICROS_PER_SECOND / stream.time_base().den()).toLong()

private fun microsToTicks(micros: Long, stream: AVStream): Long =
    (micros.toDouble() * stream.time_base().den() / (stream.time_base().num() * MICROS_PER_SECOND)).toLong()

private fun checkInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Video export interrupted")
}

private fun ffmpegCheck(result: Int, operation: String) {
    if (result >= 0) return
    val buffer = BytePointer(FFMPEG_ERROR_BUFFER_BYTES)
    try {
        av_strerror(result, buffer, FFMPEG_ERROR_BUFFER_BYTES)
        throw IOException("Could not $operation: ${buffer.string}")
    } finally {
        buffer.close()
    }
}

private fun replaceDestination(staging: File, destination: File) {
    try {
        Files.move(
            staging.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
