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
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.av_dict_copy
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_strerror
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.FrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.Java2DFrameConverter
import com.indagium.video.configureCaptureMkvOpusDecoder
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.ShortBuffer
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

/**
 * Decoder AND encoder thread count for [reencodeFromRequestedStart]. JavaCV's `FFmpegFrameGrabber`
 * and `FFmpegFrameRecorder` both hard-code `thread_count(0)` (javacv 1.5.13), i.e. FFmpeg's auto =
 * one thread per core, and FFmpeg's frame threading keeps a decoded frame, a packet and per-thread
 * scratch buffers alive per thread. On a 10-core Mac that turned a Save into a transient native
 * peak the allocator then held on to for the rest of the session. This is a background export, not
 * playback, so 2 threads still overlap decode with encode while bounding that peak. JavaCV applies
 * the `"threads"` AVOption dictionary entry inside `avcodec_open2`, after its own `thread_count(0)`,
 * so the option wins.
 */
private const val EXPORT_FFMPEG_THREADS = "2"
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
) : CaptureVideoExporter, CaptureVideoCoverageProbe, CaptureAvailabilityVideoExporter {
    override fun export(
        source: File,
        destination: File,
        requestedStartMs: Long,
        requestedEndMs: Long,
    ): CaptureVideoClip = exportFrom(source, destination, requestedStartMs, requestedEndMs, snapshot = true)

    /**
     * [source] is final (a stopped or interrupted session never grows again), so it is scanned and
     * remuxed in place — no [withPrefixSnapshot] copy of what can be a multi-GB file. An
     * interrupted recording may lack its Matroska trailer, but [readPackets] already tolerates a
     * truncated tail (INVALIDDATA exactly at the physical EOF, which for a file that no longer
     * grows is the real length), so the result matches what a snapshot of it would have given.
     */
    override fun exportFinal(
        source: File,
        destination: File,
        requestedStartMs: Long,
        requestedEndMs: Long,
    ): CaptureVideoClip = exportFrom(source, destination, requestedStartMs, requestedEndMs, snapshot = false)

    /**
     * Re-encodes only when an export contains device-unavailable intervals. Real video/audio frames
     * retain their capture timestamps; bounded, generated card frames fill the offline windows.
     * The ordinary no-gap path above stays a packet-copy remux.
     */
    override fun exportWithAvailability(
        source: File?,
        destination: File,
        timelineStartElapsedMs: Long,
        timelineEndElapsedMs: Long,
        sourceElapsedOriginMs: Long,
        availabilityIntervals: List<CaptureAvailabilityInterval>,
        sourceIsFinal: Boolean,
    ): CaptureVideoClip {
        require(timelineStartElapsedMs >= 0L) { "Availability export start must be non-negative" }
        require(timelineEndElapsedMs > timelineStartElapsedMs) { "Availability export end must be after its start" }
        require(source?.canonicalFile != destination.canonicalFile || source == null) {
            "Video destination must differ from its capture source"
        }
        val gaps = availabilityIntervals.mapNotNull { interval ->
            val start = maxOf(interval.startElapsedMs, timelineStartElapsedMs)
            val end = minOf(interval.endElapsedMs ?: timelineEndElapsedMs, timelineEndElapsedMs)
            if (end <= start) null else RenderedUnavailableInterval(interval, start, end)
        }.sortedBy { it.startElapsedMs }
        require(gaps.isNotEmpty()) { "Availability export requires an offline interval in the selected range" }
        require(gaps.zipWithNext().all { (left, right) -> left.endElapsedMs <= right.startElapsedMs }) {
            "Capture availability intervals overlap"
        }

        val parent = destination.absoluteFile.parentFile
            ?: throw IOException("Video destination has no parent: ${destination.absolutePath}")
        parent.mkdirs()
        val mp4 = isMp4Destination(destination)
        val staging = Files.createTempFile(parent.toPath(), ".capture-gap-export-", if (mp4) ".mp4" else ".mkv").toFile()
        try {
            val usableSource = source?.takeIf { it.isFile && it.length() > 0L }
            val clip = if (usableSource != null) {
                val render: (File) -> CaptureVideoClip = { input ->
                    renderAvailabilityTimeline(
                        input,
                        staging,
                        timelineStartElapsedMs,
                        timelineEndElapsedMs,
                        sourceElapsedOriginMs,
                        gaps,
                        mp4,
                        usableSource.absolutePath,
                    )
                }
                if (sourceIsFinal) render(usableSource) else withPrefixSnapshot(usableSource, render)
            } else {
                if (!availabilityCoversRange(gaps, timelineStartElapsedMs, timelineEndElapsedMs)) {
                    throw IOException("Capture video is missing outside the recorded device-unavailable intervals")
                }
                renderAvailabilityTimeline(
                    null,
                    staging,
                    timelineStartElapsedMs,
                    timelineEndElapsedMs,
                    sourceElapsedOriginMs,
                    gaps,
                    mp4,
                    null,
                )
            }
            replaceDestination(staging, destination)
            return clip
        } finally {
            staging.delete()
        }
    }

    private fun exportFrom(
        source: File,
        destination: File,
        requestedStartMs: Long,
        requestedEndMs: Long,
        snapshot: Boolean,
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
            // One shared input for both the scan and the remux (unlike coverageEndMs(), which
            // only ever needs the scan): scanWindow()'s window bounds must be valid against exactly
            // the bytes remux() reads. For a growing source that means one frozen copy (also half
            // the I/O of taking two); for a final source the file itself is that frozen input.
            val clip = if (snapshot) {
                withPrefixSnapshot(source) { copy -> exportWindow(copy, staging, requestedStartMs, requestedEndMs, mp4) }
            } else {
                // Same guard copyCurrentPrefix applies to the snapshot path.
                if (source.length() <= 0L) throw IOException("Capture video has no readable bytes: ${source.absolutePath}")
                exportWindow(source, staging, requestedStartMs, requestedEndMs, mp4)
            }
            replaceDestination(staging, destination)
            return clip
        } finally {
            staging.delete()
        }
    }

    private fun exportWindow(
        input: File,
        staging: File,
        requestedStartMs: Long,
        requestedEndMs: Long,
        mp4: Boolean,
    ): CaptureVideoClip {
        val window = scanWindow(input, requestedStartMs, requestedEndMs)
        val keyframeGapMs = requestedStartMs - window.actualStartUs / MILLIS_PER_SECOND
        // The exact-start path decodes/re-encodes video only. Keep audio-bearing captures
        // on the stream-copy path so their Opus track remains in snapshot exports.
        val exact = if (!window.hasAudio && keyframeGapMs > EXACT_START_TOLERANCE_MS) {
            reencodeFromRequestedStart(
                input,
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
        if (exact != null) return exact
        remux(input, staging, window, mp4)
        return CaptureVideoClip(
            actualStartMs = window.actualStartUs / MILLIS_PER_SECOND,
            coveredEndMs = window.coveredEndUs / MILLIS_PER_SECOND,
            durationMs = (window.coveredEndUs - window.actualStartUs) / MILLIS_PER_SECOND,
        )
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

private data class RenderedUnavailableInterval(
    val evidence: CaptureAvailabilityInterval,
    val startElapsedMs: Long,
    val endElapsedMs: Long,
)

private const val GAP_CARD_FPS = 30.0
private const val GAP_CARD_STEP_US = 1_000_000L / GAP_CARD_FPS.toLong()
private const val GAP_AUDIO_WRITE_CHUNK_FRAMES = 4_096
private const val GAP_CARD_FALLBACK_WIDTH = 1280
private const val GAP_CARD_FALLBACK_HEIGHT = 720

/**
 * Stream-decodes the selected source prefix and writes one output frame at a time. Audio frames are
 * passed through the same timestamp rebasing, including those received during an offline interval;
 * the raw recorder's mixed microphone/device-audio stream already contains microphone samples and
 * device silence where that source was unavailable.
 */
@Suppress("LongParameterList", "TooGenericExceptionCaught")
private fun renderAvailabilityTimeline(
    source: File?,
    destination: File,
    timelineStartElapsedMs: Long,
    timelineEndElapsedMs: Long,
    sourceElapsedOriginMs: Long,
    gaps: List<RenderedUnavailableInterval>,
    mp4: Boolean,
    sourceDecoderPath: String?,
): CaptureVideoClip {
    checkInterrupted()
    val videoEncoder = findEncoder(GAP_VIDEO_ENCODER_CANDIDATES)
        ?: throw IOException("No H.264 encoder is available to render device-unavailable cards")
    var input = source?.let(::boundedSnapshotGrabber)
    var recorder: FFmpegFrameRecorder? = null
    var recorderStarted = false
    val converter = Java2DFrameConverter()
    var outputFrames = 0L
    var grabberStarted = false
    try {
        if (input != null) {
            sourceDecoderPath?.let { configureCaptureMkvOpusDecoder(input, it) }
            input.sampleMode = FrameGrabber.SampleMode.SHORT
            input.sampleFormat = AV_SAMPLE_FMT_S16
            input.start()
            grabberStarted = true
        }

        val videoStreamPresent = input != null && input.videoStream >= 0 && input.imageWidth > 0 && input.imageHeight > 0
        if (input != null && !videoStreamPresent && !availabilityCoversRange(gaps, timelineStartElapsedMs, timelineEndElapsedMs)) {
            throw IOException("Capture video has no readable image stream outside the device-unavailable intervals")
        }
        val width = (if (videoStreamPresent) input!!.imageWidth else GAP_CARD_FALLBACK_WIDTH).coerceAtLeast(2)
        val height = (if (videoStreamPresent) input!!.imageHeight else GAP_CARD_FALLBACK_HEIGHT).coerceAtLeast(2)
        val audioStreamPresent = input != null && input.audioStream >= 0 && input.audioChannels > 0 && input.sampleRate > 0
        val audioChannels = if (audioStreamPresent) input!!.audioChannels else 0
        val sourceSampleRate = if (audioStreamPresent) input!!.sampleRate else 0
        val startUs = elapsedMillisToMicros(timelineStartElapsedMs)
        val endUs = elapsedMillisToMicros(timelineEndElapsedMs)
        val originUs = elapsedMillisToMicros(sourceElapsedOriginMs)
        val sourceStartUs = checkedSubtract(startUs, originUs).coerceAtLeast(0L)

        // Position near the selected window instead of decoding a potentially multi-hour source
        // from its beginning. The packet scan is cheap and bounded-memory; decoder work begins at
        // the preceding keyframe so it can recover the still image that was active at the cut.
        if (input != null) {
            input = positionForAvailabilityExport(input, requireNotNull(source), sourceStartUs, sourceDecoderPath)
        }

        val recorderInstance = FFmpegFrameRecorder(destination, width, height, audioChannels).apply {
            format = muxFormatFor(mp4)
            videoCodec = videoEncoder.second
            videoCodecName = videoEncoder.first
            frameRate = GAP_CARD_FPS
            videoBitrate = REENCODE_FALLBACK_BITRATE
            pixelFormat = AV_PIX_FMT_YUV420P
            setVideoOption("threads", EXPORT_FFMPEG_THREADS)
            if (videoStreamPresent) setDisplayRotation(input!!.displayRotation)
            if (audioChannels > 0) {
                sampleRate = sourceSampleRate
                audioBitrate = GAP_AUDIO_BITRATE
                val audioEncoder = findEncoder(if (mp4) GAP_MP4_AUDIO_ENCODERS else GAP_MKV_AUDIO_ENCODERS)
                    ?: throw IOException("No compatible audio encoder is available to preserve capture audio")
                audioCodec = audioEncoder.second
                audioCodecName = audioEncoder.first
                setAudioOption("threads", EXPORT_FFMPEG_THREADS)
            }
            if (mp4) setOption("movflags", "faststart")
        }
        recorder = recorderInstance
        recorderInstance.start()
        recorderStarted = true

        var nextGapIndex = 0
        var nextGapFrameUs = gaps.firstOrNull()?.let { elapsedMillisToMicros(it.startElapsedMs) }
        var activeCardGapIndex = -1
        var activeCard: BufferedImage? = null
        var lastRenderedVideoUs = Long.MIN_VALUE
        var lastEncodedVideoTick = Long.MIN_VALUE
        var lastConnectedImageUs = Long.MIN_VALUE
        var heldImage: BufferedImage? = null
        var timelineStartFrameWritten = false
        val latestOfflineEndUs = gaps.lastOrNull()?.let { elapsedMillisToMicros(it.endElapsedMs) }
        val audioWriter = if (audioStreamPresent) {
            AvailabilityAudioWriter(recorderInstance, sourceSampleRate, audioChannels, startUs, endUs)
        } else {
            null
        }

        fun writeImageFrame(image: BufferedImage, captureUs: Long): Boolean {
            checkInterrupted()
            if (captureUs < startUs || captureUs >= endUs) return false
            val deltaUs = captureUs - startUs
            val frameTick = kotlin.math.round(deltaUs.toDouble() * GAP_CARD_FPS / MICROS_PER_SECOND).toLong()
            // FFmpegFrameRecorder's video timebase is 1/frameRate. A source frame can quantize to
            // the same tick as a generated card (or another source frame, especially if the
            // device briefly emits above 30fps). Keep one packet per output tick; coalescing keeps
            // source timing intact, while forcing duplicate timestamps would make the muxer reject
            // DTS or stretch a dense source past the selected capture duration.
            if (frameTick <= lastEncodedVideoTick) return false
            if (frameTick > Int.MAX_VALUE) throw IOException("Capture video duration exceeds the encoder's frame range")
            val frame = converter.convert(image)
                ?: throw IOException("FFmpeg could not convert a capture video frame")
            recorderInstance.frameNumber = frameTick.toInt()
            recorderInstance.record(frame)
            outputFrames++
            lastEncodedVideoTick = frameTick
            lastRenderedVideoUs = captureUs
            return true
        }

        fun writeCardFrame(gapIndex: Int, captureUs: Long) {
            val gap = gaps[gapIndex]
            if (activeCardGapIndex != gapIndex) {
                activeCard = unavailableCardImage(width, height, gap)
                activeCardGapIndex = gapIndex
            }
            writeImageFrame(requireNotNull(activeCard), captureUs)
        }

        fun writeConnectedImageAt(captureUs: Long) {
            if (captureUs < startUs || captureUs >= endUs) return
            val gapIndex = unavailableGapIndex(captureUs / MICROS_PER_MILLI, gaps)
            if (gapIndex >= 0) return
            writeImageFrame(heldImage ?: BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR), captureUs)
            timelineStartFrameWritten = true
        }

        fun advanceVideoTimelineBefore(captureUs: Long) {
            if (captureUs > startUs && !timelineStartFrameWritten && unavailableGapIndex(startUs / MICROS_PER_MILLI, gaps) < 0) {
                writeConnectedImageAt(startUs)
            }
            while (nextGapIndex < gaps.size) {
                val gap = gaps[nextGapIndex]
                val gapStartUs = elapsedMillisToMicros(gap.startElapsedMs)
                val gapEndUs = elapsedMillisToMicros(gap.endElapsedMs)
                if (nextGapFrameUs == null) nextGapFrameUs = gapStartUs
                val frameLimitUs = minOf(captureUs, gapEndUs)
                var cursor = requireNotNull(nextGapFrameUs)
                while (cursor < frameLimitUs) {
                    writeCardFrame(nextGapIndex, cursor)
                    cursor = saturatingAddTimestamp(cursor, GAP_CARD_STEP_US)
                }
                nextGapFrameUs = cursor
                if (captureUs < gapEndUs) return
                // Keep the card on screen after the interval closes until an actual post-reconnect
                // video frame arrives. A connected device may not emit a frame immediately, and
                // restoring the pre-gap image here would falsely imply we observed the screen.
                nextGapIndex++
                nextGapFrameUs = gaps.getOrNull(nextGapIndex)?.let { elapsedMillisToMicros(it.startElapsedMs) }
            }
        }

        if (input != null) {
            while (true) {
                checkInterrupted()
                val frame = try {
                    input.grab()
                } catch (failure: Exception) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Availability export cancelled")
                    throw IOException("Could not decode the capture while rendering unavailable intervals", failure)
                } ?: break
                val sourcePtsUs = frame.timestamp.coerceAtLeast(0L)
                val captureUs = saturatingAddTimestamp(sourcePtsUs, originUs)
                if (captureUs >= endUs) break
                advanceVideoTimelineBefore(captureUs)
                if (!frame.image.isNullOrEmpty()) {
                    val image = converter.convert(frame)
                        ?: throw IOException("FFmpeg could not convert a decoded capture frame")
                    val inOfflineInterval = unavailableGapIndex(captureUs / MICROS_PER_MILLI, gaps) >= 0
                    if (!inOfflineInterval) {
                        heldImage = copyImage(image)
                        lastConnectedImageUs = captureUs
                    }
                    if (captureUs >= startUs && !inOfflineInterval) {
                        if (writeImageFrame(image, captureUs)) timelineStartFrameWritten = true
                    }
                }
                if (!frame.samples.isNullOrEmpty()) {
                    // Capture audio is already a continuous mixed stream: microphone PCM continues
                    // during an offline device interval and missing device audio is silence. Rebase
                    // and fill any timestamp holes by sample count so audio never collapses gaps.
                    audioWriter?.write(frame, captureUs)
                }
            }
            // If the final decoded image predates an online tail, hold it through that tail. This
            // covers scrcpy's static-screen behavior, where no new packet is emitted until pixels
            // change, without inventing unavailable intervals.
            val finalGap = unavailableGapIndex((endUs - 1L) / MICROS_PER_MILLI, gaps)
            val sourceHasResumedAfterLatestGap = latestOfflineEndUs == null || lastConnectedImageUs >= latestOfflineEndUs
            if (finalGap < 0 && heldImage != null && sourceHasResumedAfterLatestGap && lastConnectedImageUs < endUs - GAP_CARD_STEP_US) {
                if (!timelineStartFrameWritten && unavailableGapIndex(startUs / MICROS_PER_MILLI, gaps) < 0) {
                    writeConnectedImageAt(startUs)
                }
                writeConnectedImageAt((endUs - GAP_CARD_STEP_US).coerceAtLeast(startUs))
            }
        }

        advanceVideoTimelineBefore(endUs)
        // A gap can close before the selected clip ends even though capture never produced a
        // resumed image (for example Stop while the phone stayed offline). In that case keep the
        // last unavailable card on screen through the unobserved tail. Returning to the held
        // pre-reboot image would imply that we observed the device after it came back online.
        val lastGapIndex = gaps.lastIndex
        if (lastGapIndex >= 0 && latestOfflineEndUs != null &&
            lastConnectedImageUs < latestOfflineEndUs && endUs > latestOfflineEndUs &&
            unavailableGapIndex((endUs - 1L).coerceAtLeast(0L) / MICROS_PER_MILLI, gaps) < 0
        ) {
            var cursor = maxOf(startUs, latestOfflineEndUs)
            while (cursor < endUs) {
                writeCardFrame(lastGapIndex, cursor)
                cursor = saturatingAddTimestamp(cursor, GAP_CARD_STEP_US)
            }
        }
        // A true trailing offline interval has cards through the end. Otherwise make the final
        // held picture last until the selected boundary so the output's physical duration agrees
        // with its declared timeline even when the device screen remained static.
        val sourceHasResumedAfterLatestGap = latestOfflineEndUs == null || lastConnectedImageUs >= latestOfflineEndUs
        if (unavailableGapIndex((endUs - 1L).coerceAtLeast(0L) / MICROS_PER_MILLI, gaps) < 0 && sourceHasResumedAfterLatestGap) {
            if (!timelineStartFrameWritten && unavailableGapIndex(startUs / MICROS_PER_MILLI, gaps) < 0) {
                writeConnectedImageAt(startUs)
            }
            val finalImageUs = (endUs - GAP_CARD_STEP_US).coerceAtLeast(startUs)
            if (finalImageUs > lastRenderedVideoUs) writeConnectedImageAt(finalImageUs)
        }
        audioWriter?.finish()
        if (outputFrames == 0L) throw IOException("Availability export produced no video frames")
        recorderInstance.stop()
        recorderStarted = false
        checkInterrupted()
        val durationMs = timelineEndElapsedMs - timelineStartElapsedMs
        return CaptureVideoClip(
            actualStartMs = checkedSubtract(timelineStartElapsedMs, sourceElapsedOriginMs),
            coveredEndMs = checkedSubtract(timelineEndElapsedMs, sourceElapsedOriginMs),
            durationMs = durationMs,
        )
    } finally {
        if (recorderStarted) runCatching { recorder?.stop() }
        recorder?.let { runCatching { it.release() } }
        runCatching { converter.close() }
        if (grabberStarted) runCatching { input?.stop() }
        runCatching { input?.release() }
    }
}

private const val MICROS_PER_MILLI = 1_000L
private const val GAP_AUDIO_BITRATE = 128_000
private val GAP_VIDEO_ENCODER_CANDIDATES = listOf("libopenh264", "libx264", "h264_videotoolbox")
private val GAP_MKV_AUDIO_ENCODERS = listOf("libopus", "opus")
private val GAP_MP4_AUDIO_ENCODERS = listOf("aac")

private fun findEncoder(candidates: List<String>): Pair<String, Int>? = candidates.firstNotNullOfOrNull { name ->
    val encoder = avcodec_find_encoder_by_name(name)
    if (encoder == null || encoder.isNull) null else name to encoder.id()
}

private fun elapsedMillisToMicros(value: Long): Long = try {
    Math.multiplyExact(value, MICROS_PER_MILLI)
} catch (_: ArithmeticException) {
    throw IOException("Capture duration exceeds FFmpeg's timestamp range")
}

private fun availabilityCoversRange(
    gaps: List<RenderedUnavailableInterval>,
    rangeStartMs: Long,
    rangeEndMs: Long,
): Boolean {
    var coveredUntil = rangeStartMs
    for (gap in gaps) {
        if (gap.startElapsedMs > coveredUntil) return false
        coveredUntil = maxOf(coveredUntil, gap.endElapsedMs)
        if (coveredUntil >= rangeEndMs) return true
    }
    return coveredUntil >= rangeEndMs
}

private fun saturatingAddTimestamp(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

private fun checkedSubtract(left: Long, right: Long): Long = try {
    Math.subtractExact(left, right)
} catch (_: ArithmeticException) {
    throw IOException("Capture timeline timestamp is outside FFmpeg's range")
}

private fun copyImage(source: BufferedImage): BufferedImage {
    val copy = BufferedImage(source.width, source.height, BufferedImage.TYPE_3BYTE_BGR)
    val graphics = copy.createGraphics()
    try {
        graphics.drawImage(source, 0, 0, null)
    } finally {
        graphics.dispose()
    }
    return copy
}

private fun positionForAvailabilityExport(
    grabber: FFmpegFrameGrabber,
    source: File,
    sourceStartUs: Long,
    sourceDecoderPath: String?,
): FFmpegFrameGrabber {
    if (grabber.videoStream < 0) {
        runCatching { grabber.setTimestamp(sourceStartUs) }
            .onFailure { throw IOException("Could not position capture audio near the selected interval", it) }
        return grabber
    }
    val firstKeyframeUs = findAvailabilitySeekKeyframe(source, sourceStartUs) ?: run {
        runCatching { grabber.setTimestamp(sourceStartUs) }
            .onFailure { throw IOException("Could not position capture decoder near the selected interval", it) }
        return grabber
    }
    var keyframeUs = firstKeyframeUs
    val configureFreshGrabber: (FFmpegFrameGrabber) -> Unit = { candidate ->
        sourceDecoderPath?.let { configureCaptureMkvOpusDecoder(candidate, it) }
        candidate.sampleMode = FrameGrabber.SampleMode.SHORT
        candidate.sampleFormat = AV_SAMPLE_FMT_S16
    }
    var positioned = positionAtOrBeforeKeyframe(grabber, source, keyframeUs, configureFreshGrabber)
    while (positioned.usedSeek && (positioned.landedFrame?.timestamp ?: Long.MIN_VALUE) > sourceStartUs) {
        // Heuristic seeking in a growing/truncated Matroska file can land on a later keyframe even
        // when its timestamp is within the generic seek tolerance. Step back one real keyframe and
        // verify again so a late landed frame cannot discard the still image/audio overlap needed
        // at the requested cut. If this was the first keyframe in the source, there is no earlier
        // frame to recover and the caller will use it as the first observed image.
        val earlierKeyframeUs = if (keyframeUs > 0L) findAvailabilitySeekKeyframe(source, keyframeUs - 1L) else null
        if (earlierKeyframeUs == null || earlierKeyframeUs >= keyframeUs) break
        keyframeUs = earlierKeyframeUs
        positioned = positionAtOrBeforeKeyframe(positioned.grabber, source, keyframeUs, configureFreshGrabber)
    }
    val positionedGrabber = positioned.grabber
    // positionAtOrBeforeKeyframe consumes one image to verify a seek. Re-seek to that verified
    // image's timestamp so the renderer decodes it again as the first usable still frame.
    if (positioned.usedSeek) {
        runCatching { positionedGrabber.setTimestamp(positioned.landedFrame?.timestamp ?: keyframeUs) }
            .onFailure { throw IOException("Could not rewind to the selected capture keyframe", it) }
    }
    return positionedGrabber
}

private fun findAvailabilitySeekKeyframe(source: File, targetUs: Long): Long? = withInput(source) { input ->
    val videoStream = firstVideoStream(input)
    var firstKeyframe: Long? = null
    var precedingKeyframe: Long? = null
    readPackets(input, source.length()) { packet ->
        if (packet.stream_index() != videoStream || (packet.flags() and AV_PKT_FLAG_KEY) == 0) return@readPackets
        val timestampUs = packetTimestampUs(packet, input.streams(videoStream)) ?: return@readPackets
        if (firstKeyframe == null) firstKeyframe = timestampUs
        if (timestampUs <= targetUs) precedingKeyframe = timestampUs
    }
    precedingKeyframe ?: firstKeyframe
}

/** Rebuilds a timestamped decoded audio stream as sequential PCM, bounded to one small block. */
private class AvailabilityAudioWriter(
    private val recorder: FFmpegFrameRecorder,
    private val sampleRate: Int,
    private val channels: Int,
    private val startUs: Long,
    private val endUs: Long,
) {
    private var writtenSampleFrames = 0L

    fun write(frame: Frame, captureUs: Long) {
        val source = frame.samples?.firstOrNull() as? ShortBuffer ?: return
        val samples = source.duplicate()
        val totalSourceFrames = samples.remaining() / channels
        if (totalSourceFrames <= 0) return
        val rawStart = sampleIndex(captureUs - startUs)
        val rawEnd = rawStart + totalSourceFrames
        val selectedStart = rawStart.coerceAtLeast(0L)
        val selectedEnd = rawEnd.coerceAtMost(sampleIndex(endUs - startUs))
        if (selectedEnd <= selectedStart) return
        if (selectedStart > writtenSampleFrames) writeSilence(selectedStart - writtenSampleFrames)
        val skipFrames = (writtenSampleFrames - rawStart).coerceAtLeast(0L)
        val remainingFrames = selectedEnd - maxOf(selectedStart, writtenSampleFrames)
        if (remainingFrames <= 0L) return
        samples.position(samples.position() + (skipFrames * channels).toInt())
        samples.limit(samples.position() + (remainingFrames * channels).toInt())
        recordInChunks(samples, remainingFrames)
        writtenSampleFrames += remainingFrames
    }

    fun finish() {
        writeSilence((sampleIndex(endUs - startUs) - writtenSampleFrames).coerceAtLeast(0L))
    }

    private fun writeSilence(frameCount: Long) {
        var left = frameCount
        while (left > 0L) {
            checkInterrupted()
            val chunkFrames = minOf(left, GAP_AUDIO_WRITE_CHUNK_FRAMES.toLong()).toInt()
            recordInChunks(ShortBuffer.wrap(ShortArray(chunkFrames * channels)), chunkFrames.toLong())
            writtenSampleFrames += chunkFrames
            left -= chunkFrames
        }
    }

    private fun recordInChunks(samples: ShortBuffer, frameCount: Long) {
        var left = frameCount
        while (left > 0L) {
            checkInterrupted()
            val chunkFrames = minOf(left, GAP_AUDIO_WRITE_CHUNK_FRAMES.toLong()).toInt()
            val sampleCount = chunkFrames * channels
            val chunk = samples.slice().apply { limit(sampleCount) }
            recorder.recordSamples(sampleRate, channels, chunk)
            samples.position(samples.position() + sampleCount)
            left -= chunkFrames
        }
    }

    private fun sampleIndex(deltaUs: Long): Long = try {
        Math.multiplyExact(deltaUs, sampleRate.toLong()) / MICROS_PER_SECOND
    } catch (_: ArithmeticException) {
        throw IOException("Capture audio timestamp exceeds FFmpeg's sample range")
    }
}

private fun unavailableGapIndex(elapsedMs: Long, gaps: List<RenderedUnavailableInterval>): Int {
    var low = 0
    var high = gaps.lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        val gap = gaps[mid]
        when {
            elapsedMs < gap.startElapsedMs -> high = mid - 1
            elapsedMs >= gap.endElapsedMs -> low = mid + 1
            else -> return mid
        }
    }
    return -1
}

private fun unavailableCardImage(width: Int, height: Int, gap: RenderedUnavailableInterval): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
    val graphics = image.createGraphics()
    try {
        graphics.color = Color(12, 17, 24)
        graphics.fillRect(0, 0, width, height)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val centerX = width / 2
        val centerY = height / 2
        graphics.color = Color(246, 190, 78)
        graphics.stroke = java.awt.BasicStroke((minOf(width, height) * 0.004).toFloat().coerceAtLeast(2f))
        graphics.drawRoundRect(
            (width * 0.08).toInt(),
            (height * 0.12).toInt(),
            (width * 0.84).toInt(),
            (height * 0.76).toInt(),
            (minOf(width, height) * 0.025).toInt(),
            (minOf(width, height) * 0.025).toInt(),
        )
        val titleSize = (minOf(width, height) * 0.075).toInt().coerceIn(22, 76)
        val detailSize = (titleSize * 0.42).toInt().coerceAtLeast(13)
        graphics.color = Color(246, 190, 78)
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, titleSize)
        drawCenteredText(graphics, "DEVICE UNAVAILABLE", centerX, centerY - titleSize)
        graphics.color = Color(231, 235, 242)
        graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, detailSize)
        val originalDuration = gap.evidence.endElapsedMs?.let { (it - gap.evidence.startElapsedMs).coerceAtLeast(0L) }
            ?: (gap.endElapsedMs - gap.evidence.startElapsedMs).coerceAtLeast(0L)
        drawCenteredText(graphics, "No device frames for ${formatAvailabilityDuration(originalDuration)}", centerX, centerY + detailSize)
        val reboot = when (gap.evidence.rebootConfirmed) {
            true -> "Device reboot confirmed after reconnection"
            false -> "Device reconnected without a reboot"
            null -> "Reboot status unknown"
        }
        graphics.color = Color(174, 185, 198)
        graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, (detailSize * 0.84).toInt().coerceAtLeast(11))
        drawCenteredText(graphics, reboot, centerX, (centerY + detailSize * 3.2).toInt())
    } finally {
        graphics.dispose()
    }
    return image
}

private fun drawCenteredText(graphics: java.awt.Graphics2D, text: String, centerX: Int, baselineY: Int) {
    val originalFont = graphics.font
    val maxWidth = ((graphics.clipBounds?.width ?: Int.MAX_VALUE) * 0.84).toInt()
    var font = originalFont
    while (graphics.getFontMetrics(font).stringWidth(text) > maxWidth && font.size > 10) {
        font = font.deriveFont(font.size2D - 1f)
    }
    graphics.font = font
    val textWidth = graphics.fontMetrics.stringWidth(text)
    graphics.drawString(text, centerX - textWidth / 2, baselineY)
    graphics.font = originalFont
}

private fun formatAvailabilityDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) + 500L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0L -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
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
    var grabber = boundedSnapshotGrabber(snapshot)
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

private fun boundedSnapshotGrabber(snapshot: File): FFmpegFrameGrabber =
    FFmpegFrameGrabber(snapshot).apply { setVideoOption("threads", EXPORT_FFMPEG_THREADS) }

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
private fun positionAtOrBeforeKeyframe(
    grabber: FFmpegFrameGrabber,
    snapshot: File,
    keyframeStartUs: Long,
    configureFreshGrabber: (FFmpegFrameGrabber) -> Unit = {},
): PositionedGrabber {
    val landed = runCatching {
        grabber.setTimestamp(keyframeStartUs)
        grabber.grabImage()
    }.getOrNull()
    val verified = landed != null && landed.keyFrame && landed.timestamp <= keyframeStartUs + SEEK_VERIFY_TOLERANCE_US
    if (verified) return PositionedGrabber(grabber, usedSeek = true, landedFrame = landed)
    runCatching { grabber.stop() }
    runCatching { grabber.release() }
    val fresh = boundedSnapshotGrabber(snapshot)
    var started = false
    try {
        configureFreshGrabber(fresh)
        fresh.start()
        started = true
    } finally {
        // [fresh] is not yet owned by the caller (its finally only releases the grabber it holds),
        // so a failed start would otherwise leak the half-opened demuxer and decoder context.
        if (!started) runCatching { fresh.release() }
    }
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
    recorder.setVideoOption("threads", EXPORT_FFMPEG_THREADS)
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

private data class ExportWindow(
    val actualStartUs: Long,
    val coveredEndUs: Long,
    val videoStreamIndex: Int,
    val hasAudio: Boolean,
)

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
    val hasAudio = (0 until input.nb_streams()).any { input.streams(it).codecpar().codec_type() == AVMEDIA_TYPE_AUDIO }
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
    return ExportWindow(actualStart, coveredEndUs, videoStreamIndex, hasAudio)
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
