@file:Suppress("MagicNumber")

package com.indagium.capture.mirror

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Round-trips a short synthetic Opus stream through [OpusPcmDecoder]: this test's own [encodeTone]
 * helper drives the bundled JavaCPP FFmpeg's Opus ENCODER (same library, opposite direction of
 * [OpusPcmDecoder]/[findOpusDecoder]) to produce a few real Opus packets plus real extradata from a
 * synthesized sine wave, then feeds them back through the decoder under test and checks that audio —
 * not silence, not nothing — comes out the other end. Skips gracefully (never fails the build) if
 * this FFmpeg build has no usable Opus encoder at all, per this feature's task doc.
 */
class OpusPcmDecoderTest {
    @Test
    fun decodesScrcpySilencePacketWithDeviceOpusHead() {
        // Android's Opus encoder emits this compact DTX/silence access unit while its input is
        // silent. It appeared repeatedly in the user's mic-off capture; keep it as a packet-level
        // regression so the live decoder handles it the same way as ordinary encoded speech.
        val opusHead = byteArrayOf(
            0x4f, 0x70, 0x75, 0x73, 0x48, 0x65, 0x61, 0x64, 0x01, 0x02,
            0x38, 0x01, 0x80.toByte(), 0xbb.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val silencePacket = byteArrayOf(0xfc.toByte(), 0xff.toByte(), 0xfe.toByte())
        val decoder = OpusPcmDecoder(onDiagnostic = {})
        try {
            decoder.configure(opusHead)
            val decoded = decoder.decode(silencePacket)
            assertNotNull("Android Opus DTX packet should decode instead of being treated as corrupt", decoded)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun decodesASyntheticOpusStreamBackIntoNonSilentPcm() {
        val encoded = encodeTone()
        assumeTrue("no Opus encoder available in this FFmpeg build", encoded != null)
        val (extradata, packets) = encoded!!
        assertTrue("encoder produced no packets to test with", packets.isNotEmpty())

        val decoder = OpusPcmDecoder(onDiagnostic = {})
        try {
            decoder.configure(extradata)
            val decodedChunks = packets.mapNotNull { packet -> decoder.decode(packet) }
            assertTrue("decoder produced no PCM at all", decodedChunks.isNotEmpty())

            val totalSamples = decodedChunks.sumOf { it.size }
            assertTrue("expected a meaningful amount of decoded audio, got $totalSamples samples", totalSamples > 100)

            val sumOfSquares = decodedChunks.sumOf { chunk -> chunk.sumOf { sample -> sample.toLong() * sample.toLong() } }
            assertTrue("decoded audio was silent — expected a real sine tone", sumOfSquares > 0)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun decodeBeforeConfigureReturnsNullInsteadOfThrowing() {
        val decoder = OpusPcmDecoder(onDiagnostic = {})
        try {
            assertNull(decoder.decode(ByteArray(16)))
        } finally {
            decoder.close()
        }
    }

    @Test
    fun malformedExtradataNeverThrowsFromConfigureOrDecode() {
        // Simulate "no Opus decoder in this build" without depending on one actually being absent:
        // configure() with an extradata that can't open (e.g. malformed) still must not throw, and
        // decode() afterwards must return null rather than use a half-initialized context.
        var diagnostic: String? = null
        val decoder = OpusPcmDecoder(onDiagnostic = { diagnostic = it })
        try {
            // A real Opus decoder tolerates most extradata sizes, so this alone won't fail to open in
            // every build; the assertion that matters is only that nothing throws either way.
            decoder.configure(ByteArray(0))
            decoder.decode(ByteArray(4))
        } finally {
            decoder.close()
        }
        // Not asserted on `diagnostic` — whether this particular input fails to open is FFmpeg-build
        // dependent; the point of this test is the absence of an exception.
        @Suppress("UNUSED_EXPRESSION")
        diagnostic
    }

    private data class EncodedOpus(val extradata: ByteArray, val packets: List<ByteArray>)

    /** Encodes ~200ms of a 440Hz sine wave to Opus using the bundled FFmpeg's own encoder, returning
     *  its extradata (OpusHead) and the individual Opus packets, or null if this build has no Opus
     *  encoder (native "opus" or bundled "libopus") at all. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun encodeTone(): EncodedOpus? {
        val codec = listOf("libopus", "opus").firstNotNullOfOrNull { name ->
            avcodec_find_encoder_by_name(name)?.takeUnless { it.isNull }
        } ?: return null

        val ctx = avcodec_alloc_context3(codec)
        if (ctx == null || ctx.isNull) return null
        try {
            ctx.sample_rate(LIVE_AUDIO_SAMPLE_RATE_HZ)
            av_channel_layout_default(ctx.ch_layout(), LIVE_AUDIO_CHANNELS)
            ctx.sample_fmt(AV_SAMPLE_FMT_S16)
            ctx.bit_rate(64_000L)
            if (avcodec_open2(ctx, codec, null as AVDictionary?) < 0) return null

            val extradataSize = ctx.extradata_size()
            val extradata = if (extradataSize > 0) {
                ByteArray(extradataSize).also { out ->
                    ctx.extradata().position(0L).get(out, 0, extradataSize)
                }
            } else {
                ByteArray(0)
            }

            val frameSize = ctx.frame_size().takeIf { it > 0 } ?: 960
            val packets = mutableListOf<ByteArray>()
            val packet = av_packet_alloc()
            try {
                // A handful of frames is enough to exercise the decoder without a slow test.
                repeat(FRAME_COUNT) { frameIndex ->
                    val frame = av_frame_alloc()
                    try {
                        frame.format(AV_SAMPLE_FMT_S16)
                        frame.sample_rate(LIVE_AUDIO_SAMPLE_RATE_HZ)
                        av_channel_layout_default(frame.ch_layout(), LIVE_AUDIO_CHANNELS)
                        frame.nb_samples(frameSize)
                        if (av_frame_get_buffer(frame, 0) < 0) return@repeat
                        writeSineWave(frame, frameIndex * frameSize, frameSize)
                        drainEncoder(ctx, packet, frame, packets)
                    } finally {
                        av_frame_free(frame)
                    }
                }
                // Flush.
                drainEncoder(ctx, packet, null, packets)
            } finally {
                av_packet_free(packet)
            }
            return EncodedOpus(extradata, packets)
        } catch (failure: Exception) {
            return null
        } finally {
            avcodec_free_context(ctx)
        }
    }

    private fun drainEncoder(
        ctx: AVCodecContext,
        packet: org.bytedeco.ffmpeg.avcodec.AVPacket,
        frame: org.bytedeco.ffmpeg.avutil.AVFrame?,
        out: MutableList<ByteArray>,
    ) {
        if (avcodec_send_frame(ctx, frame) < 0) return
        while (avcodec_receive_packet(ctx, packet) == 0) {
            val size = packet.size()
            if (size > 0) {
                val bytes = ByteArray(size)
                packet.data().position(0L).get(bytes, 0, size)
                out.add(bytes)
            }
            av_packet_unref(packet)
        }
    }

    private fun writeSineWave(frame: org.bytedeco.ffmpeg.avutil.AVFrame, sampleOffset: Int, sampleCount: Int) {
        val shorts = ShortArray(sampleCount * LIVE_AUDIO_CHANNELS)
        for (i in 0 until sampleCount) {
            val t = (sampleOffset + i).toDouble() / LIVE_AUDIO_SAMPLE_RATE_HZ
            val value = (sin(2.0 * PI * 440.0 * t) * Short.MAX_VALUE * 0.5).toInt().toShort()
            shorts[i * 2] = value
            shorts[i * 2 + 1] = value
        }
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            val v = shorts[i].toInt()
            bytes[i * 2] = (v and 0xff).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        frame.data(0).position(0L).put(bytes, 0, bytes.size)
    }

    private companion object {
        const val FRAME_COUNT = 10
    }
}
