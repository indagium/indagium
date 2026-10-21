package com.indagium.capture

import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avcodec.AVPacketSideData
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVDictionaryEntry
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_GLOBAL_HEADER
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AV1
import org.bytedeco.ffmpeg.global.avcodec.AV_PKT_DATA_DISPLAYMATRIX
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts
import org.bytedeco.ffmpeg.global.avcodec.av_packet_side_data_new
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_copy
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_from_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame
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
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_DICT_IGNORE_SUFFIX
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.AV_PICTURE_TYPE_I
import org.bytedeco.ffmpeg.global.avutil.AV_PICTURE_TYPE_NONE
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.ffmpeg.global.avutil.av_dict_copy
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_get
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_display_rotation_set
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.avutil.av_frame_make_writable
import org.bytedeco.ffmpeg.global.avutil.av_rescale_q
import org.bytedeco.ffmpeg.global.avutil.av_strerror
import org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val COMPACT_AV1_ENCODER = "libsvtav1"
private const val COMPACT_AV1_CRF = "28"

// SVT-AV1 disables its screen-content tools at preset 9 and above and warns that M10+ is meant for
// automation tooling ("visual artifacts may occur"). Preset 8 keeps them. On a 180 s emulator clip,
// preset 10 with 2 s keyframes produced 52.8% of the source size; preset 8 with 10 s keyframes
// produced 15.0% with a higher SSIM, at roughly twice the encode time (about 35x realtime).
internal const val COMPACT_AV1_PRESET = 8
private const val COMPACT_AV1_MIN_PRESET = 0
private const val COMPACT_AV1_MAX_PRESET = 13
private const val COMPACT_AV1_MIN_THREADS = 2
private const val COMPACT_AV1_MIN_LOGICAL_PROCESSORS = 1
private const val COMPACT_AV1_MAX_THREADS = 6
private const val COMPACT_AV1_DECODE_THREADS = 2
private const val COPY_BUFFER_BYTES = 64 * 1024
private const val COMPACT_AV1_TIME_BASE_MS = 1_000
internal const val COMPACT_AV1_KEYFRAME_INTERVAL_MS = 10_000L

// JavaCV 1.5.13's FFmpegFrameGrabber.setTimestamp(long) gives up after 1000 grabs, counting audio and
// video frames together (the same hazard is described in FfmpegCaptureVideoExporter). Opus adds about
// 50 packets/s, so at most 300 video frames plus about 500 audio packets per 10 s keyframe gap is
// roughly 800 grabs and keeps seeks under the cap. Sparse or static screens therefore get a keyframe
// every 10 s, while 60 fps motion gets one about every 5 s.
internal const val COMPACT_AV1_MAX_FRAMES_PER_KEYFRAME = 300
private const val COMPACT_AV1_FALLBACK_FPS = 30.0
private const val COMPACT_AV1_MAX_NOMINAL_FPS = 120.0
private const val FRAME_RATE_RATIONAL_MAX_DENOMINATOR = 100_000
private const val MICROS_PER_SECOND = 1_000_000
private const val MICROS_PER_MILLISECOND = 1_000.0
private const val ROTATION_EPSILON_DEGREES = 0.01
private const val ASPECT_RATIO_PRECISION = 1_000
private const val FFMPEG_ERROR_BUFFER_BYTES = 256L
private const val AVERROR_EAGAIN_LINUX = -11
private const val AVERROR_EAGAIN_MACOS = -35

/** AV1 video encoder for the optional Indagium Compact export. It only reads the already-staged
 *  interval, keeps its original dimensions and frame timestamps, and copies every source audio
 *  packet into the final Matroska container without decoding or re-encoding it. */
class FfmpegAv1CompactVideoTranscoder internal constructor(
    private val preset: Int = COMPACT_AV1_PRESET,
    private val logicalProcessors: Int = compactEncoderProcessorCount(Runtime.getRuntime().availableProcessors()),
    private val keyframeIntervalMs: Long = COMPACT_AV1_KEYFRAME_INTERVAL_MS,
    private val maxFramesPerKeyframe: Int = COMPACT_AV1_MAX_FRAMES_PER_KEYFRAME,
) : CaptureCompactVideoTranscoder {
    init {
        require(keyframeIntervalMs > 0L) { "Compact keyframe interval must be positive" }
        require(maxFramesPerKeyframe > 0) { "Compact frames per keyframe must be positive" }
        require(preset in COMPACT_AV1_MIN_PRESET..COMPACT_AV1_MAX_PRESET) { "Unsupported SVT-AV1 preset: $preset" }
        require(logicalProcessors in COMPACT_AV1_MIN_LOGICAL_PROCESSORS..COMPACT_AV1_MAX_THREADS) {
            "Compact encoder concurrency must be between one and six"
        }
    }

    override fun ensureAvailable() {
        try {
            val encoder = avcodec_find_encoder_by_name(COMPACT_AV1_ENCODER)
            if (encoder == null || encoder.isNull) {
                throw IOException(
                    "Indagium Compact is unavailable: this build does not include the bundled AV1 encoder libsvtav1. " +
                        "The standard ZIP export remains available.",
                )
            }
        } catch (failure: LinkageError) {
            throw IOException(
                "Indagium Compact is unavailable: the bundled FFmpeg AV1 encoder could not be loaded. " +
                    "The standard ZIP export remains available.",
                failure,
            )
        }
    }

    override fun isAlreadyAv1(source: File): Boolean {
        if (!source.isFile || source.length() <= 0L) return false
        val grabber = FFmpegFrameGrabber(source)
        return try {
            grabber.start(false)
            grabber.videoCodec == AV_CODEC_ID_AV1
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        } finally {
            runCatching { grabber.stop() }
        }
    }

    override fun copyAlreadyAv1(source: File, destination: File) {
        require(source.isFile && source.length() > 0L) { "Compact export has no readable staged video" }
        if (source.extension.equals("mkv", ignoreCase = true)) {
            copyWithCancellation(source, destination)
        } else {
            muxOriginalAudio(source, source, destination)
        }
    }

    // This boundary translates JavaCV/JNI failures into the export UI's actionable IOException.
    @Suppress("TooGenericExceptionCaught")
    override fun transcode(source: File, destination: File) {
        ensureAvailable()
        require(source.isFile && source.length() > 0L) { "Compact export has no readable staged video" }
        require(source.canonicalFile != destination.canonicalFile) { "Compact video destination must differ from its source" }
        val parent = destination.absoluteFile.parentFile ?: throw IOException("Compact video has no parent folder")
        require(parent.mkdirs() || parent.isDirectory) { "Cannot create Compact video folder: $parent" }
        val encodedVideo = Files.createTempFile(parent.toPath(), ".ind-av1-video-", ".mkv").toFile()
        val muxedVideo = Files.createTempFile(parent.toPath(), ".ind-av1-mux-", ".mkv").toFile()
        try {
            val needsPacketCopyMux = encodeVideo(source, encodedVideo)
            val completedVideo = if (needsPacketCopyMux) {
                muxOriginalAudio(source, encodedVideo, muxedVideo)
                muxedVideo
            } else {
                encodedVideo
            }
            checkCompactInterrupted()
            Files.move(
                completedVideo.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (interrupted: InterruptedIOException) {
            throw interrupted
        } catch (failure: LinkageError) {
            throw IOException("Indagium Compact could not load the bundled FFmpeg encoder: ${failure.message}", failure)
        } catch (failure: Exception) {
            throw IOException("Indagium Compact AV1 encoding failed: ${failure.message ?: failure::class.simpleName}", failure)
        } finally {
            encodedVideo.delete()
            muxedVideo.delete()
        }
    }

    // Keep native allocation, use and conditional teardown together so every partial-init failure
    // releases its resources. The explicit ownership checks account for most of this complexity.
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ThrowsCount")
    private fun encodeVideo(source: File, destination: File): Boolean {
        val grabber = FFmpegFrameGrabber(source).apply {
            setVideoOption("threads", COMPACT_AV1_DECODE_THREADS.toString())
            imageMode = FrameGrabber.ImageMode.RAW
        }
        var started = false
        var codecContext: AVCodecContext? = null
        var output: AVFormatContext? = null
        var outputOpened = false
        var headerWritten = false
        var headerOptions: AVDictionary? = null
        var scaler: SwsContext? = null
        var convertedFrame: AVFrame? = null
        var encodedPacket: AVPacket? = null
        val outputPointer = PointerPointer<Pointer>(1)
        outputPointer.put(null as Pointer?)
        try {
            grabber.start()
            started = true
            require(grabber.videoStream >= 0 && grabber.imageWidth > 0 && grabber.imageHeight > 0) {
                "Compact export requires readable video frames"
            }
            require(grabber.imageWidth % 2 == 0 && grabber.imageHeight % 2 == 0) {
                "Compact AV1 export requires even video dimensions; source dimensions were not changed"
            }
            val sourceFormat: AVFormatContext = grabber.getFormatContext()
            if (sourceFormat.isNull) throw IOException("Compact export has no input format context")
            val sourceVideoStream = sourceFormat.streams(grabber.videoStream)
            val needsPacketCopyMux = (0 until sourceFormat.nb_streams()).any { it != grabber.videoStream }
            val encoder = avcodec_find_encoder_by_name(COMPACT_AV1_ENCODER)
                ?: throw IOException("Bundled AV1 encoder libsvtav1 is unavailable")
            if (encoder.isNull) throw IOException("Bundled AV1 encoder libsvtav1 is unavailable")
            val nominalFps = saneFrameRate(grabber.videoFrameRate)
            codecContext = avcodec_alloc_context3(encoder)
                ?: throw IOException("FFmpeg could not allocate the AV1 encoder context")
            val firstFrame = grabber.grabImage() ?: throw IOException("Compact export decoded no video frames")
            val firstInputFrame = firstFrame.opaque as? AVFrame
                ?: throw IOException("FFmpeg returned a video frame without native pixels")
            configureEncoder(
                codecContext, encoder, grabber.imageWidth, grabber.imageHeight, nominalFps,
                firstInputFrame, sourceVideoStream.sample_aspect_ratio(),
            )

            BytePointer("matroska").use { formatName ->
                BytePointer(destination.absolutePath).use { destinationName ->
                    compactFfmpegCheck(
                        avformat_alloc_output_context2(outputPointer, null, formatName, destinationName),
                        "allocate Compact video output",
                    )
                }
            }
            output = AVFormatContext(outputPointer.get())
            if (output.isNull) throw IOException("FFmpeg could not allocate Compact video output")
            val outputContext = output
            val stream = avformat_new_stream(outputContext, null)
                ?: throw IOException("FFmpeg could not allocate Compact video stream")
            compactFfmpegCheck(
                avcodec_parameters_from_context(stream.codecpar(), codecContext),
                "copy AV1 codec parameters",
            )
            stream.codecpar().codec_tag(0)
            stream.time_base().num(codecContext.time_base().num()).den(codecContext.time_base().den())
            copyRational(sourceVideoStream.avg_frame_rate(), stream.avg_frame_rate())
            if (stream.avg_frame_rate().num() <= 0 || stream.avg_frame_rate().den() <= 0) {
                stream.avg_frame_rate().num(nominalFps.first).den(nominalFps.second)
            }
            copyRational(sourceVideoStream.r_frame_rate(), stream.r_frame_rate())
            if (stream.r_frame_rate().num() <= 0 || stream.r_frame_rate().den() <= 0) {
                stream.r_frame_rate().num(nominalFps.first).den(nominalFps.second)
            }
            copyRational(sourceVideoStream.sample_aspect_ratio(), stream.sample_aspect_ratio())
            if (stream.sample_aspect_ratio().num() <= 0 || stream.sample_aspect_ratio().den() <= 0) {
                val aspectRatio = grabber.aspectRatio.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
                stream.sample_aspect_ratio().num((aspectRatio * ASPECT_RATIO_PRECISION).roundToInt()).den(ASPECT_RATIO_PRECISION)
            }
            copyRational(stream.sample_aspect_ratio(), codecContext.sample_aspect_ratio())
            stream.id(sourceVideoStream.id()).disposition(sourceVideoStream.disposition())
            copyCompactMetadata(sourceFormat.metadata(), "copy Compact container metadata") { outputContext.metadata(it) }
            copyCompactMetadata(sourceVideoStream.metadata(), "copy Compact video metadata") { stream.metadata(it) }
            if (grabber.displayRotation.isFinite() && kotlin.math.abs(grabber.displayRotation) > ROTATION_EPSILON_DEGREES) {
                addDisplayMatrix(stream, grabber.displayRotation)
            }

            if ((outputContext.oformat().flags() and AVFMT_NOFILE) == 0) {
                openCompactOutputIo(outputContext, destination)
                outputOpened = true
            }
            headerOptions = AVDictionary(null)
            compactFfmpegCheck(avformat_write_header(outputContext, headerOptions), "write Compact video header")
            headerWritten = true

            convertedFrame = av_frame_alloc()
                ?: throw IOException("FFmpeg could not allocate a converted video frame")
            convertedFrame.format(codecContext.pix_fmt()).width(codecContext.width()).height(codecContext.height())
            convertedFrame.color_range(codecContext.color_range())
                .colorspace(codecContext.colorspace())
                .color_primaries(codecContext.color_primaries())
                .color_trc(codecContext.color_trc())
                .chroma_location(codecContext.chroma_sample_location())
            compactFfmpegCheck(av_frame_get_buffer(convertedFrame, 32), "allocate converted video frame buffers")
            encodedPacket = av_packet_alloc()
                ?: throw IOException("FFmpeg could not allocate an AV1 packet")
            var priorPtsMs: Long? = null
            var lastKeyframePtsMs: Long? = null
            var frameCount = 0L
            var framesSinceKeyframe = 0
            var pendingFrame: Frame? = firstFrame

            while (true) {
                checkCompactInterrupted()
                val frame = pendingFrame ?: grabber.grabImage() ?: break
                pendingFrame = null
                val inputFrame = frame.opaque as? AVFrame
                    ?: throw IOException("FFmpeg returned a video frame without native pixels")
                if (inputFrame.width() != codecContext.width() || inputFrame.height() != codecContext.height()) {
                    throw IOException("Compact AV1 export does not resize changing source dimensions")
                }
                val ptsMs = frame.timestamp.roundToNearestMillisecond()
                if (priorPtsMs != null && ptsMs < priorPtsMs) {
                    throw IOException("Compact AV1 source video timestamps are out of order")
                }
                priorPtsMs = ptsMs
                val shouldForceKeyframe = compactKeyframeDue(
                    ptsMs, lastKeyframePtsMs, framesSinceKeyframe, keyframeIntervalMs, maxFramesPerKeyframe,
                )
                if (shouldForceKeyframe) {
                    lastKeyframePtsMs = ptsMs
                    framesSinceKeyframe = 0
                }

                scaler = scaler ?: sws_getContext(
                    inputFrame.width(), inputFrame.height(), inputFrame.format(),
                    codecContext.width(), codecContext.height(), codecContext.pix_fmt(),
                    SWS_BILINEAR, null, null, null as DoubleArray?,
                ) ?: throw IOException("FFmpeg could not convert source pixels to AV1 format")
                compactFfmpegCheck(av_frame_make_writable(convertedFrame), "prepare AV1 frame")
                val scaledRows = sws_scale(
                    scaler,
                    inputFrame.data(), inputFrame.linesize(),
                    0, inputFrame.height(),
                    convertedFrame.data(), convertedFrame.linesize(),
                )
                if (scaledRows != codecContext.height()) throw IOException("FFmpeg converted only $scaledRows video rows")
                val duration = inputFrame.duration()
                val inputTimeBase = inputFrame.time_base()
                val frameDurationMs = if (duration > 0L && inputTimeBase.num() > 0 && inputTimeBase.den() > 0) {
                    av_rescale_q(duration, inputTimeBase, codecContext.time_base()).coerceAtLeast(1L)
                } else {
                    (COMPACT_AV1_TIME_BASE_MS.toLong() * nominalFps.second / nominalFps.first).coerceAtLeast(1L)
                }
                convertedFrame.pts(ptsMs).time_base(codecContext.time_base())
                    .duration(frameDurationMs)
                    .pict_type(if (shouldForceKeyframe) AV_PICTURE_TYPE_I else AV_PICTURE_TYPE_NONE)
                compactFfmpegCheck(avcodec_send_frame(codecContext, convertedFrame), "encode AV1 frame")
                drainEncodedPackets(codecContext, outputContext, stream, encodedPacket)
                frameCount++
                framesSinceKeyframe++
            }
            if (frameCount == 0L) throw IOException("Compact export decoded no video frames")
            compactFfmpegCheck(avcodec_send_frame(codecContext, null), "flush AV1 encoder")
            drainEncodedPackets(codecContext, outputContext, stream, encodedPacket, flushing = true)
            checkCompactInterrupted()
            compactFfmpegCheck(av_write_trailer(outputContext), "finalize Compact video")
            headerWritten = false
            return needsPacketCopyMux
        } finally {
            if (headerWritten && output != null && !output.isNull) runCatching { av_write_trailer(output) }
            encodedPacket?.let { if (!it.isNull) av_packet_free(it) }
            convertedFrame?.let { if (!it.isNull) av_frame_free(it) }
            scaler?.let { if (!it.isNull) sws_freeContext(it) }
            codecContext?.let { if (!it.isNull) org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context(it) }
            if (outputOpened && output != null && !output.isNull) closeCompactOutputIo(output)
            if (output != null && !output.isNull) avformat_free_context(output)
            headerOptions?.let { if (!it.isNull) av_dict_free(it) }
            outputPointer.close()
            if (started) runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }

    private fun configureEncoder(
        context: AVCodecContext,
        encoder: AVCodec,
        width: Int,
        height: Int,
        fps: Pair<Int, Int>,
        firstFrame: AVFrame,
        sourceSampleAspectRatio: AVRational,
    ) {
        context.codec_id(encoder.id())
        context.codec_type(AVMEDIA_TYPE_VIDEO)
        context.width(width).height(height).pix_fmt(AV_PIX_FMT_YUV420P)
        context.time_base().num(1).den(COMPACT_AV1_TIME_BASE_MS)
        context.flags(context.flags() or AV_CODEC_FLAG_GLOBAL_HEADER or org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_CLOSED_GOP)
        context.color_range(firstFrame.color_range())
            .colorspace(firstFrame.colorspace())
            .color_primaries(firstFrame.color_primaries())
            .color_trc(firstFrame.color_trc())
            .chroma_sample_location(firstFrame.chroma_location())
        val sourceSarIsValid = !sourceSampleAspectRatio.isNull &&
            sourceSampleAspectRatio.num() > 0 && sourceSampleAspectRatio.den() > 0
        if (sourceSarIsValid) {
            copyRational(sourceSampleAspectRatio, context.sample_aspect_ratio())
        } else {
            context.sample_aspect_ratio().num(1).den(1)
        }
        context.framerate().num(fps.first).den(fps.second)
        context.gop_size(compactGopSize(fps, keyframeIntervalMs, maxFramesPerKeyframe))
        context.thread_count(logicalProcessors)
        context.max_b_frames(0)
        context.bit_rate(0L)
        val options = AVDictionary(null)
        try {
            av_dict_set(options, "crf", COMPACT_AV1_CRF, 0)
            av_dict_set(options, "preset", preset.toString(), 0)
            // This SVT option enables explicit AV_PICTURE_TYPE_I requests, which the loop schedules
            // from source PTS once the keyframe interval has elapsed, or sooner when the frame cap is
            // reached (see compactKeyframeDue). gop_size is only the encoder's own backstop.
            av_dict_set(options, "svtav1-params", "lp=$logicalProcessors:enable-force-key-frames=true", 0)
            compactFfmpegCheck(avcodec_open2(context, encoder, options), "open bundled AV1 encoder")
            if (!options.isNull) {
                val rejected = av_dict_get(options, "", null as AVDictionaryEntry?, AV_DICT_IGNORE_SUFFIX)
                if (rejected != null && !rejected.isNull) {
                    throw IOException("Bundled AV1 encoder rejected the requested Compact option: ${rejected.key().getString()}")
                }
            }
        } finally {
            if (!options.isNull) av_dict_free(options)
        }
    }

    private fun drainEncodedPackets(
        codec: AVCodecContext,
        output: AVFormatContext,
        stream: AVStream,
        packet: AVPacket,
        flushing: Boolean = false,
    ) {
        while (true) {
            checkCompactInterrupted()
            val result = org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet(codec, packet)
            if (result == AVERROR_EOF || isAgain(result)) {
                if (flushing && result != AVERROR_EOF && !isAgain(result)) compactFfmpegCheck(result, "drain AV1 encoder")
                break
            }
            compactFfmpegCheck(result, "receive AV1 packet")
            av_packet_rescale_ts(packet, codec.time_base(), stream.time_base())
            packet.stream_index(stream.index()).pos(-1L)
            compactFfmpegCheck(av_interleaved_write_frame(output, packet), "write AV1 packet")
            av_packet_unref(packet)
        }
    }

    // Stream cursors and muxer resources must be released in the same scope as native allocation.
    @Suppress("CyclomaticComplexMethod")
    private fun muxOriginalAudio(source: File, encodedVideo: File, destination: File) {
        withCompactInput(encodedVideo) { videoInput ->
            withCompactInput(source) { sourceInput ->
                val encodedVideoIndex = firstCompactVideoStream(videoInput)
                val sourceVideoIndex = firstCompactVideoStream(sourceInput)
                val outputPointer = PointerPointer<Pointer>(1)
                outputPointer.put(null as Pointer?)
                var output: AVFormatContext? = null
                var outputOpened = false
                var headerWritten = false
                var headerOptions: AVDictionary? = null
                try {
                    BytePointer("matroska").use { formatName ->
                        BytePointer(destination.absolutePath).use { destinationName ->
                            compactFfmpegCheck(
                                avformat_alloc_output_context2(outputPointer, null, formatName, destinationName),
                                "allocate final Compact archive video",
                            )
                        }
                    }
                    output = AVFormatContext(outputPointer.get())
                    if (output.isNull) throw IOException("FFmpeg could not allocate final Compact output")
                    val outputContext = output
                    val videoOutputIndex = copyCompactStream(videoInput, encodedVideoIndex, outputContext)
                    val videoStreamMap = IntArray(videoInput.nb_streams()) { -1 }
                    videoStreamMap[encodedVideoIndex] = videoOutputIndex
                    val sourceStreamMap = IntArray(sourceInput.nb_streams()) { -1 }
                    repeat(sourceInput.nb_streams()) { inputIndex ->
                        if (inputIndex == sourceVideoIndex) return@repeat
                        sourceStreamMap[inputIndex] = copyCompactStream(sourceInput, inputIndex, outputContext)
                    }
                    val sourceVideo = sourceInput.streams(sourceVideoIndex)
                    val compactVideoStream = outputContext.streams(videoOutputIndex)
                    copyRational(sourceVideo.avg_frame_rate(), compactVideoStream.avg_frame_rate())
                    copyRational(sourceVideo.r_frame_rate(), compactVideoStream.r_frame_rate())
                    copyRational(sourceVideo.sample_aspect_ratio(), compactVideoStream.sample_aspect_ratio())
                    if ((outputContext.oformat().flags() and AVFMT_NOFILE) == 0) {
                        openCompactOutputIo(outputContext, destination)
                        outputOpened = true
                    }
                    headerOptions = AVDictionary(null)
                    copyCompactMetadata(sourceInput.metadata(), "copy Compact container metadata") { outputContext.metadata(it) }
                    compactFfmpegCheck(avformat_write_header(outputContext, headerOptions), "write final Compact header")
                    headerWritten = true

                    CompactPacketCursor(videoInput, videoStreamMap).use { videoCursor ->
                        CompactPacketCursor(sourceInput, sourceStreamMap).use { audioCursor ->
                            var hasVideo = videoCursor.advance()
                            var hasAudio = audioCursor.advance()
                            while (hasVideo || hasAudio) {
                                checkCompactInterrupted()
                                val chooseVideo = when {
                                    !hasAudio -> true
                                    !hasVideo -> false
                                    else -> videoCursor.dtsUs() <= audioCursor.dtsUs()
                                }
                                val cursor = if (chooseVideo) videoCursor else audioCursor
                                val input = if (chooseVideo) videoInput else sourceInput
                                val mapping = if (chooseVideo) videoStreamMap else sourceStreamMap
                                val inputIndex = cursor.packet.stream_index()
                                val inputStream = input.streams(inputIndex)
                                val outputStream = outputContext.streams(mapping[inputIndex])
                                av_packet_rescale_ts(cursor.packet, inputStream.time_base(), outputStream.time_base())
                                cursor.packet.stream_index(outputStream.index()).pos(-1L)
                                compactFfmpegCheck(av_interleaved_write_frame(outputContext, cursor.packet), "write Compact media packet")
                                cursor.consumed()
                                if (chooseVideo) hasVideo = videoCursor.advance() else hasAudio = audioCursor.advance()
                            }
                        }
                    }
                    checkCompactInterrupted()
                    compactFfmpegCheck(av_write_trailer(outputContext), "finalize Compact archive video")
                    headerWritten = false
                } finally {
                    if (headerWritten && output != null && !output.isNull) runCatching { av_write_trailer(output) }
                    if (outputOpened && output != null && !output.isNull) closeCompactOutputIo(output)
                    if (output != null && !output.isNull) avformat_free_context(output)
                    headerOptions?.let { if (!it.isNull) av_dict_free(it) }
                    outputPointer.close()
                }
            }
        }
    }

    private fun copyWithCancellation(source: File, destination: File) {
        destination.outputStream().use { output ->
            source.inputStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    checkCompactInterrupted()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    private fun copyCompactStream(input: AVFormatContext, inputIndex: Int, output: AVFormatContext): Int {
        val inputStream = input.streams(inputIndex)
        val outputStream = avformat_new_stream(output, null)
            ?: throw IOException("FFmpeg could not allocate output stream $inputIndex")
        compactFfmpegCheck(
            avcodec_parameters_copy(outputStream.codecpar(), inputStream.codecpar()),
            "copy media stream $inputIndex codec",
        )
        outputStream.codecpar().codec_tag(0)
        outputStream.id(inputStream.id())
        outputStream.disposition(inputStream.disposition())
        copyRational(inputStream.time_base(), outputStream.time_base())
        copyRational(inputStream.avg_frame_rate(), outputStream.avg_frame_rate())
        copyRational(inputStream.r_frame_rate(), outputStream.r_frame_rate())
        copyRational(inputStream.sample_aspect_ratio(), outputStream.sample_aspect_ratio())
        outputStream.duration(inputStream.duration())
        copyCompactMetadata(inputStream.metadata(), "copy media stream $inputIndex metadata") { outputStream.metadata(it) }
        return outputStream.index()
    }

    private fun copyCompactMetadata(source: AVDictionary?, operation: String, install: (AVDictionary) -> Unit) {
        if (source == null || source.isNull) return
        val destination = PointerPointer<Pointer>(1)
        try {
            destination.put(null as Pointer?)
            compactFfmpegCheck(av_dict_copy(destination, source, 0), operation)
            val copy = AVDictionary(destination.get())
            if (!copy.isNull) install(copy)
        } finally {
            destination.close()
        }
    }

    private fun addDisplayMatrix(stream: AVStream, angle: Double) {
        // Explicit Long literals select JavaCPP's capacity constructor. Passing Int selects
        // the vararg-value constructor and allocates a single element containing the number.
        // Long literals select allocation/index overloads, rather than IntPointer's varargs APIs.
        val matrix = IntPointer(9L)
        val codedSideData = PointerPointer<Pointer>(1)
        val count = IntPointer(1L)
        try {
            av_display_rotation_set(matrix, -angle)
            codedSideData.put(0L, stream.codecpar().coded_side_data())
            count.put(0L, stream.codecpar().nb_coded_side_data())
            val bytes = matrix.capacity() * Int.SIZE_BYTES.toLong()
            val sideData: AVPacketSideData = av_packet_side_data_new(
                codedSideData,
                count,
                AV_PKT_DATA_DISPLAYMATRIX,
                bytes,
                0,
            ) ?: throw IOException("FFmpeg could not preserve video rotation metadata")
            if (sideData.isNull || sideData.data().isNull) throw IOException("FFmpeg could not preserve video rotation metadata")
            stream.codecpar().coded_side_data(AVPacketSideData(codedSideData.get(0L)))
            stream.codecpar().nb_coded_side_data(count.get(0L))
            sideData.data().capacity(bytes).asByteBuffer().put(matrix.asByteBuffer())
        } finally {
            count.close()
            codedSideData.close()
            matrix.close()
        }
    }

    private fun openCompactOutputIo(output: AVFormatContext, destination: File) {
        val ioPointer = PointerPointer<Pointer>(1)
        try {
            ioPointer.put(null as Pointer?)
            BytePointer(destination.absolutePath).use { name ->
                compactFfmpegCheck(avio_open(ioPointer, name, AVIO_FLAG_WRITE), "open Compact output")
            }
            val io = AVIOContext(ioPointer.get())
            if (io.isNull) throw IOException("FFmpeg could not allocate Compact output I/O")
            output.pb(io)
        } finally {
            ioPointer.close()
        }
    }

    private fun closeCompactOutputIo(output: AVFormatContext) {
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

    private inline fun <T> withCompactInput(file: File, block: (AVFormatContext) -> T): T {
        checkCompactInterrupted()
        val inputPointer = PointerPointer<Pointer>(1)
        inputPointer.put(null as Pointer?)
        var opened = false
        try {
            BytePointer(file.absolutePath).use { filename ->
                compactFfmpegCheck(
                    avformat_open_input(inputPointer, filename, null, null),
                    "open staged capture video",
                )
            }
            opened = true
            val input = AVFormatContext(inputPointer.get())
            if (input.isNull) throw IOException("FFmpeg could not open staged capture video")
            val info = avformat_find_stream_info(input, null as AVDictionary?)
            if (info < 0 && input.nb_streams() == 0) compactFfmpegCheck(info, "read staged capture stream information")
            return block(input)
        } finally {
            if (opened) avformat_close_input(inputPointer)
            inputPointer.close()
        }
    }

    private fun firstCompactVideoStream(input: AVFormatContext): Int {
        repeat(input.nb_streams()) { index ->
            if (input.streams(index).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) return index
        }
        throw IOException("Compact video has no readable image stream")
    }
}

private class CompactPacketCursor(
    private val input: AVFormatContext,
    private val streamMap: IntArray,
) : AutoCloseable {
    val packet: AVPacket = av_packet_alloc() ?: throw IOException("FFmpeg could not allocate a media packet")

    fun advance(): Boolean {
        av_packet_unref(packet)
        while (true) {
            checkCompactInterrupted()
            val result = av_read_frame(input, packet)
            if (result == AVERROR_EOF) return false
            compactFfmpegCheck(result, "read compact media packet")
            if (streamMap.getOrElse(packet.stream_index()) { -1 } >= 0) return true
            av_packet_unref(packet)
        }
    }

    fun dtsUs(): Long {
        val stream = input.streams(packet.stream_index())
        val pts = if (packet.dts() != AV_NOPTS_VALUE) packet.dts() else packet.pts()
        if (pts == AV_NOPTS_VALUE || stream.time_base().den() == 0) return Long.MAX_VALUE
        return av_rescale_q(pts, stream.time_base(), AVRational().num(1).den(MICROS_PER_SECOND))
    }

    fun consumed() = av_packet_unref(packet)

    override fun close() {
        if (!packet.isNull) av_packet_free(packet)
    }
}

private fun saneFrameRate(raw: Double): Pair<Int, Int> {
    if (!raw.isFinite() || raw !in 1.0..COMPACT_AV1_MAX_NOMINAL_FPS) return COMPACT_AV1_FALLBACK_FPS.toInt() to 1
    val rational = org.bytedeco.ffmpeg.global.avutil.av_d2q(raw, FRAME_RATE_RATIONAL_MAX_DENOMINATOR)
    if (rational.isNull || rational.num() <= 0 || rational.den() <= 0) return COMPACT_AV1_FALLBACK_FPS.toInt() to 1
    return rational.num() to rational.den()
}

/** True when the frame at [ptsMs] must be a forced keyframe: the first frame, once [intervalMs] of source
 *  time has passed since the last keyframe, or once [framesSinceKeyframe] reaches [maxFrames]. The count
 *  includes the keyframe itself (it is 1 after the keyframe is encoded), so each GOP holds exactly
 *  [maxFrames] frames and keyframes are at most [maxFrames] frames apart. */
internal fun compactKeyframeDue(
    ptsMs: Long,
    lastKeyframePtsMs: Long?,
    framesSinceKeyframe: Int,
    intervalMs: Long,
    maxFrames: Int,
): Boolean = lastKeyframePtsMs == null ||
    ptsMs - lastKeyframePtsMs >= intervalMs ||
    framesSinceKeyframe >= maxFrames

/** SVT-AV1 backstop GOP length in frames: the keyframe interval at the nominal frame rate, capped at [maxFrames]. */
internal fun compactGopSize(fps: Pair<Int, Int>, intervalMs: Long, maxFrames: Int): Int {
    val framesPerInterval = fps.first.toDouble() / fps.second * intervalMs / COMPACT_AV1_TIME_BASE_MS
    return minOf(framesPerInterval.roundToInt(), maxFrames).coerceAtLeast(1)
}

internal fun compactEncoderProcessorCount(availableProcessors: Int): Int {
    if (availableProcessors <= COMPACT_AV1_MIN_LOGICAL_PROCESSORS) return COMPACT_AV1_MIN_LOGICAL_PROCESSORS
    return (availableProcessors - COMPACT_AV1_MIN_THREADS).coerceAtLeast(COMPACT_AV1_MIN_THREADS)
        .coerceAtMost(COMPACT_AV1_MAX_THREADS)
}

private fun copyRational(source: AVRational, destination: AVRational) {
    if (source.isNull || destination.isNull) return
    destination.num(source.num()).den(source.den())
}

private fun Long.roundToNearestMillisecond(): Long = (this.toDouble() / MICROS_PER_MILLISECOND).roundToLong()

private fun isAgain(result: Int): Boolean = result == AVERROR_EAGAIN_LINUX || result == AVERROR_EAGAIN_MACOS

private fun checkCompactInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Compact AV1 export interrupted")
}

private fun compactFfmpegCheck(result: Int, operation: String) {
    if (result >= 0) return
    val buffer = BytePointer(FFMPEG_ERROR_BUFFER_BYTES)
    try {
        av_strerror(result, buffer, FFMPEG_ERROR_BUFFER_BYTES)
        throw IOException("Could not $operation: ${buffer.string}")
    } finally {
        buffer.close()
    }
}
