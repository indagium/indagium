@file:Suppress("TooGenericExceptionCaught")

package com.indagium.capture.mirror

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avutil.AVBufferRef
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avcodec.AV_PKT_FLAG_KEY
import org.bytedeco.ffmpeg.global.avcodec.av_new_packet
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NONE
import org.bytedeco.ffmpeg.global.avutil.av_buffer_ref
import org.bytedeco.ffmpeg.global.avutil.av_buffer_unref
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_unref
import org.bytedeco.ffmpeg.global.avutil.av_hwdevice_ctx_create
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import java.io.Closeable
import java.io.IOException

/** Synchronous sink for a decoded hardware frame. Implementations must submit/present before the
 * decoder reuses the frame's backing surface. */
internal fun interface HardwareMirrorFramePresenter : Closeable {
    fun present(frame: AVFrame)

    override fun close() = Unit
}

/**
 * Hardware-only H.264 decode over the recorder's bounded packet feed. It leaves decoded frames in
 * D3D11VA/VAAPI surfaces and synchronously hands those surfaces to the native presenter; it never
 * maps frames into Java pixel buffers. If device setup or decoding fails, the runtime switches the
 * same connection to its existing JavaCV/Compose fallback at the next key frame.
 */
internal class HardwareH264MirrorDecoder(
    private val hardwareDeviceType: Int,
    private val hardwarePixelFormat: Int,
    private val presenter: HardwareMirrorFramePresenter,
) : DirectH264Decoder {
    @Volatile private var closed = false

    @Suppress("CyclomaticComplexMethod") // The single decode/cleanup scope keeps native resources paired.
    override fun decode(input: BoundedScrcpyPacketFeed, onFrame: (MirrorFrameInfo) -> Unit) {
        val codec = avcodec_find_decoder(AV_CODEC_ID_H264)
            ?: throw IOException("Bundled FFmpeg has no H.264 decoder")
        val codecContext = avcodec_alloc_context3(codec)
            ?: throw IOException("FFmpeg could not allocate an H.264 decoder")
        val devicePointer = PointerPointer<Pointer>(1).put(null as Pointer?)
        val packet = av_packet_alloc() ?: throw IOException("FFmpeg could not allocate a video packet")
        val frame = av_frame_alloc() ?: throw IOException("FFmpeg could not allocate a video frame")
        var previousWidth = 0
        var previousHeight = 0
        val formatSelector = object : AVCodecContext.Get_format_AVCodecContext_IntPointer() {
            override fun call(context: AVCodecContext, formats: IntPointer): Int {
                var index = 0L
                while (true) {
                    val format = formats.get(index++)
                    if (format == AV_PIX_FMT_NONE) return AV_PIX_FMT_NONE
                    if (format == hardwarePixelFormat) return format
                }
            }
        }
        try {
            val created = av_hwdevice_ctx_create(devicePointer, hardwareDeviceType, null as BytePointer?, null, 0)
            if (created < 0) throw IOException("FFmpeg could not create hardware device context (error $created)")
            // The codec takes its own reference; devicePointer keeps ownership of the original.
            codecContext.hw_device_ctx(av_buffer_ref(AVBufferRef(devicePointer.get())))
            codecContext.get_format(formatSelector)
            val timeBase = AVRational().num(MIRROR_TIME_BASE_NUMERATOR).den(MIRROR_TIMESTAMP_TICKS_PER_SECOND)
            codecContext.time_base(timeBase)
            codecContext.pkt_timebase(timeBase)
            val openResult = avcodec_open2(codecContext, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?)
            if (openResult < 0) throw IOException("FFmpeg could not open the hardware H.264 decoder (error $openResult)")

            while (!closed) {
                val accessUnit = input.nextPacket() ?: break
                if (accessUnit.data.isEmpty()) continue
                val allocateResult = av_new_packet(packet, accessUnit.data.size)
                if (allocateResult < 0) throw IOException("FFmpeg could not allocate H.264 packet data")
                packet.data().capacity(accessUnit.data.size.toLong()).position(0L).put(accessUnit.data, 0, accessUnit.data.size)
                packet.pts(accessUnit.ptsUs)
                packet.dts(accessUnit.ptsUs)
                packet.flags(if (accessUnit.keyFrame) AV_PKT_FLAG_KEY else 0)
                val sendResult = avcodec_send_packet(codecContext, packet)
                av_packet_unref(packet)
                if (sendResult < 0) throw IOException("FFmpeg rejected an H.264 access unit (error $sendResult)")

                while (avcodec_receive_frame(codecContext, frame) == 0) {
                    try {
                        if (frame.format() != hardwarePixelFormat) {
                            throw IOException("FFmpeg returned software pixels instead of the requested GPU surface")
                        }
                        presenter.present(frame)
                        if (frame.width() != previousWidth || frame.height() != previousHeight) {
                            previousWidth = frame.width()
                            previousHeight = frame.height()
                            onFrame(MirrorFrameInfo(previousWidth, previousHeight, accessUnit.ptsUs))
                        }
                    } finally {
                        av_frame_unref(frame)
                    }
                }
            }
        } finally {
            runCatching { av_packet_unref(packet) }
            runCatching { av_frame_unref(frame) }
            av_packet_free(packet)
            av_frame_free(frame)
            val contextPointer = PointerPointer<AVCodecContext>(1).put(codecContext)
            avcodec_free_context(contextPointer)
            // Unref the device exactly once, through the slot av_hwdevice_ctx_create filled (this
            // also nulls it). A second Java wrapper around the same address used to be unreffed
            // too, freeing one AVBufferRef twice on every decoder teardown — native heap
            // corruption on disconnect/stop/fallback.
            av_buffer_unref(devicePointer)
            devicePointer.close()
            formatSelector.close()
        }
    }

    override fun close() {
        closed = true
    }
}

private const val MIRROR_TIME_BASE_NUMERATOR = 1
private const val MIRROR_TIMESTAMP_TICKS_PER_SECOND = 1_000_000
