@file:Suppress("MagicNumber")

package com.indagium.capture

import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_OPUS
import org.bytedeco.ffmpeg.global.avcodec.AV_INPUT_BUFFER_PADDING_SIZE
import org.bytedeco.ffmpeg.global.avcodec.AV_PKT_FLAG_KEY
import org.bytedeco.ffmpeg.global.avcodec.av_new_packet
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avformat.AVFMT_NOFILE
import org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE
import org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame
import org.bytedeco.ffmpeg.global.avformat.av_write_trailer
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2
import org.bytedeco.ffmpeg.global.avformat.avformat_free_context
import org.bytedeco.ffmpeg.global.avformat.avformat_new_stream
import org.bytedeco.ffmpeg.global.avformat.avformat_write_header
import org.bytedeco.ffmpeg.global.avformat.avio_closep
import org.bytedeco.ffmpeg.global.avformat.avio_open
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_malloc
import org.bytedeco.ffmpeg.global.avutil.av_strerror
import org.bytedeco.javacpp.BytePointer
import java.io.Closeable
import java.io.File
import java.io.IOException

private const val MICROS_TIME_BASE_DEN = 1_000_000
private const val FFMPEG_ERROR_BUFFER_BYTES = 256L

/**
 * Writes H.264 (+ optional Opus) packets straight from the embedded scrcpy device stream into a
 * Matroska file that stays readable by ffprobe/JavaCV while still open — no re-encode, no source
 * file to remux from. Replaces relying on host `scrcpy --record` for the durable MKV (see
 * [EmbeddedDeviceSession]); the file this writes is exactly [com.indagium.capture.CaptureSession.videoFile]
 * (`video/screen.mkv`), so [CaptureArchiveExporter]/[FfmpegCaptureVideoExporter] and the importer
 * keep working unmodified against it.
 *
 * Callers own PTS rebasing: every `ptsUs` passed to [writeVideoPacket]/[writeAudioPacket] must
 * already be relative to this recording's own t=0 (see [EmbeddedDeviceSession] for how the anchor
 * is chosen and how a mid-recording reconnect's PTS is offset to stay continuous). This class only
 * clamps against going backwards, so a reconnect that computes its offset even slightly wrong still
 * produces a monotonic, muxable file instead of an FFmpeg write error.
 *
 * Readability while growing rests on two matroska muxer options, not on `avio_flush` alone: FFmpeg
 * accumulates each Cluster's blocks in an in-memory buffer and only appends it to the file's AVIO
 * stream when the cluster closes (see libavformat/matroskaenc.c's `cluster_bc` dynamic buffer) —
 * `avio_flush` only pushes already-written bytes past the OS buffer, it cannot force a cluster to
 * close early. [clusterTimeLimitMs]/[clusterSizeLimitBytes] (mapped to the muxer's own
 * `cluster_time_limit`/`cluster_size_limit` private options) are therefore what actually bounds how
 * far the readable end of the file can lag real time; `flush_packets=1` (set unconditionally below)
 * makes sure a just-closed cluster's bytes reach disk immediately rather than sitting in a
 * process-level stdio buffer.
 */
internal class StreamingMkvWriter(
    private val destination: File,
    private val clusterTimeLimitMs: Long = 750L,
    private val clusterSizeLimitBytes: Int = 96 * 1024,
) : Closeable {
    private var output: AVFormatContext? = null
    private var videoStreamIndex = -1
    private var audioStreamIndex = -1
    private var headerWritten = false
    private var trailerWritten = false
    private var closed = false
    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L

    /** Allocates the output context and its video stream. Must be called before anything else;
     * [addAudio] (if used) must follow immediately, before the first packet write. */
    @Synchronized
    fun start(width: Int, height: Int, extradata: ByteArray) {
        check(output == null) { "StreamingMkvWriter.start() already called" }
        require(width > 0 && height > 0) { "video dimensions must be positive" }
        destination.absoluteFile.parentFile?.mkdirs()
        val context = AVFormatContext(null)
        ffmpegCheck(
            avformat_alloc_output_context2(context, null, "matroska", destination.absolutePath),
            "allocate streaming Matroska output",
        )
        if (context.isNull) throw IOException("FFmpeg did not allocate a streaming output context")
        output = context
        context.flush_packets(1)
        val stream = newStream(context, AVMEDIA_TYPE_VIDEO, AV_CODEC_ID_H264)
        stream.codecpar().width(width).height(height)
        setExtradata(stream, extradata)
        videoStreamIndex = stream.index()
    }

    /** Adds an Opus audio stream. Must be called after [start] and before the first packet write —
     * matroska (like most muxers) cannot add a stream once its header has been written. */
    @Synchronized
    fun addAudio(sampleRate: Int, channels: Int, extradata: ByteArray) {
        val context = checkNotNull(output) { "call start() before addAudio()" }
        check(!headerWritten) { "cannot add an audio stream after streaming has started" }
        require(sampleRate > 0 && channels > 0) { "audio format must be positive" }
        val stream = newStream(context, AVMEDIA_TYPE_AUDIO, AV_CODEC_ID_OPUS)
        stream.codecpar().sample_rate(sampleRate)
        av_channel_layout_default(stream.codecpar().ch_layout(), channels)
        setExtradata(stream, extradata)
        audioStreamIndex = stream.index()
    }

    @Synchronized
    fun writeVideoPacket(ptsUs: Long, keyFrame: Boolean, data: ByteArray) {
        check(!closed) { "StreamingMkvWriter is closed" }
        ensureHeaderWritten()
        val safePts = maxOf(ptsUs, lastVideoPtsUs + 1).coerceAtLeast(0)
        writePacket(videoStreamIndex, safePts, keyFrame, data)
        lastVideoPtsUs = safePts
    }

    @Synchronized
    fun writeAudioPacket(ptsUs: Long, data: ByteArray) {
        check(!closed) { "StreamingMkvWriter is closed" }
        check(audioStreamIndex >= 0) { "no audio stream — call addAudio() first" }
        ensureHeaderWritten()
        val safePts = maxOf(ptsUs, lastAudioPtsUs + 1).coerceAtLeast(0)
        writePacket(audioStreamIndex, safePts, keyFrame = true, data)
        lastAudioPtsUs = safePts
    }

    /** True once at least one video packet has actually been written to disk — i.e. the video
     * track's PTS-0 anchor is now meaningful (see [EmbeddedDeviceSession]'s `videoStartElapsedMs`). */
    @Synchronized
    fun hasWrittenVideo(): Boolean = lastVideoPtsUs >= 0

    /** Writes the trailer (cues, durations) so the file is a normal, fully seekable Matroska
     * recording once capture stops. Safe to call even if no packet was ever written. */
    @Synchronized
    fun finish() {
        if (closed) return
        val context = output
        if (context != null && headerWritten && !trailerWritten) {
            ffmpegCheck(av_write_trailer(context), "finalize streaming Matroska output")
            trailerWritten = true
        }
        closeInternal()
    }

    /** Best-effort finalize on abrupt shutdown (e.g. the app closing) — prefer [finish] on the
     * normal stop path so failures surface to the caller. */
    @Synchronized
    override fun close() {
        if (closed) return
        val context = output
        if (context != null && headerWritten && !trailerWritten) {
            runCatching { av_write_trailer(context) }
        }
        closeInternal()
    }

    private fun closeInternal() {
        closed = true
        val context = output
        if (context != null) {
            if (!context.isNull && (context.oformat().flags() and AVFMT_NOFILE) == 0) {
                val io = context.pb()
                if (io != null && !io.isNull) {
                    avio_closep(io)
                    context.pb(null)
                }
            }
            if (!context.isNull) avformat_free_context(context)
        }
        output = null
    }

    private fun ensureHeaderWritten() {
        if (headerWritten) return
        val context = checkNotNull(output) { "call start() first" }
        if ((context.oformat().flags() and AVFMT_NOFILE) == 0) {
            val io = AVIOContext(null)
            ffmpegCheck(avio_open(io, destination.absolutePath, AVIO_FLAG_WRITE), "open streaming Matroska output")
            context.pb(io)
        }
        val options = AVDictionary(null)
        try {
            av_dict_set(options, "cluster_time_limit", clusterTimeLimitMs.toString(), 0)
            av_dict_set(options, "cluster_size_limit", clusterSizeLimitBytes.toString(), 0)
            ffmpegCheck(avformat_write_header(context, options), "write streaming Matroska header")
        } finally {
            av_dict_free(options)
        }
        headerWritten = true
    }

    private fun writePacket(streamIndex: Int, ptsUs: Long, keyFrame: Boolean, data: ByteArray) {
        val context = checkNotNull(output)
        val stream = context.streams(streamIndex)
        val packet = av_packet_alloc() ?: throw IOException("FFmpeg could not allocate a streaming mkv packet")
        try {
            ffmpegCheck(av_new_packet(packet, data.size), "allocate streaming mkv packet payload")
            if (data.isNotEmpty()) packet.data().position(0L).put(data, 0, data.size)
            packet.size(data.size)
            packet.stream_index(streamIndex)
            packet.pts(ptsUs)
            packet.dts(ptsUs)
            if (keyFrame) packet.flags(packet.flags() or AV_PKT_FLAG_KEY)
            av_packet_rescale_ts(packet, microsecondTimeBase(), stream.time_base())
            ffmpegCheck(av_interleaved_write_frame(context, packet), "write streaming mkv packet")
        } finally {
            av_packet_unref(packet)
            av_packet_free(packet)
        }
    }

    private fun newStream(context: AVFormatContext, mediaType: Int, codecId: Int) = requireNotNull(avformat_new_stream(context, null)) {
        "FFmpeg could not allocate a streaming mkv track"
    }.also { stream ->
        stream.codecpar().codec_type(mediaType).codec_id(codecId)
        stream.time_base(microsecondTimeBase())
    }

    private fun setExtradata(stream: org.bytedeco.ffmpeg.avformat.AVStream, extradata: ByteArray) {
        if (extradata.isEmpty()) return
        val padded = ByteArray(extradata.size + AV_INPUT_BUFFER_PADDING_SIZE)
        extradata.copyInto(padded)
        val raw = av_malloc(padded.size.toLong()) ?: throw IOException("FFmpeg could not allocate extradata")
        val pointer = BytePointer(raw).capacity(padded.size.toLong())
        pointer.position(0L).put(padded, 0, padded.size)
        stream.codecpar().extradata(pointer).extradata_size(extradata.size)
    }
}

private fun microsecondTimeBase(): AVRational = AVRational().num(1).den(MICROS_TIME_BASE_DEN)

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
