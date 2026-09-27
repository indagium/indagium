@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package com.indagium.capture.mirror

import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_OPUS
import org.bytedeco.ffmpeg.global.avcodec.AV_INPUT_BUFFER_PADDING_SIZE
import org.bytedeco.ffmpeg.global.avcodec.av_new_packet
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder_by_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_malloc
import org.bytedeco.ffmpeg.global.swresample.swr_alloc
import org.bytedeco.ffmpeg.global.swresample.swr_alloc_set_opts2
import org.bytedeco.ffmpeg.global.swresample.swr_convert
import org.bytedeco.ffmpeg.global.swresample.swr_free
import org.bytedeco.ffmpeg.global.swresample.swr_get_out_samples
import org.bytedeco.ffmpeg.global.swresample.swr_init
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import java.io.Closeable
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

internal const val LIVE_AUDIO_SAMPLE_RATE_HZ = 48_000
internal const val LIVE_AUDIO_CHANNELS = 2
private const val BYTES_PER_SAMPLE = 2

/**
 * The other end of [EmbeddedDeviceSession.attachLiveAudio]'s tee: receives the same raw scrcpy Opus
 * config/packets the recording muxer sees, decoupled from that muxer's own PTS anchoring (live
 * playback should start as soon as audio arrives, not wait for video to anchor the timeline the way
 * [EmbeddedDeviceSession.writeAudioFrame] does). Every call must be non-blocking and must never
 * throw into the caller — the audio pump thread that calls it also feeds the recording and must
 * never be slowed or stalled by playback (see [LiveAudioPlayer]'s own doc for how it honours that).
 */
internal interface LiveAudioSink : Closeable {
    fun onAudioConfig(extradata: ByteArray)

    fun onAudioPacket(ptsUs: Long, data: ByteArray)

    override fun close()
}

/** Opus decoder candidates to probe, in order — mirrors FfmpegCaptureVideoExporter's own H.264
 *  encoder-name probing (avcodec_find_decoder_by_name returns a null POINTER, never a Kotlin null,
 *  for a name this FFmpeg build wasn't compiled with). "libopus" is bytedeco's bundled libopus-backed
 *  decoder when present; the native "opus" decoder ships in every FFmpeg build with no external
 *  dependency and is the guaranteed fallback, found by ID rather than name as a last resort. */
private val OPUS_DECODER_CANDIDATES = listOf("libopus", "opus")

internal fun findOpusDecoder(): AVCodec? =
    OPUS_DECODER_CANDIDATES.firstNotNullOfOrNull { name ->
        avcodec_find_decoder_by_name(name)?.takeUnless { it.isNull }
    } ?: avcodec_find_decoder(AV_CODEC_ID_OPUS)?.takeUnless { it.isNull }

/**
 * Bounded ring buffer of interleaved 16-bit stereo PCM "frames" (one L+R sample pair). [write] never
 * blocks and drops the OLDEST audio when the buffer would overflow — the decode thread that calls it
 * must never be slowed by a playback thread falling behind. [read] always fills the caller's buffer
 * completely, padding with silence on underrun so [LiveAudioPlayer]'s line always gets a full,
 * glitch-free chunk. Capacity bounds worst-case latency to [maxLatencyMs] (see that parameter's doc);
 * pure and hardware-free, so this is unit-testable without FFmpeg or an audio device.
 */
internal class AudioJitterBuffer(
    maxLatencyMs: Int = DEFAULT_MAX_LATENCY_MS,
    private val sampleRateHz: Int = LIVE_AUDIO_SAMPLE_RATE_HZ,
    private val channels: Int = LIVE_AUDIO_CHANNELS,
) {
    private val lock = Any()
    private val maxFrames = (sampleRateHz.toLong() * maxLatencyMs / MILLIS_PER_SECOND).toInt().coerceAtLeast(1)
    private val buffer = ShortArray(maxFrames * channels)
    private var readIndex = 0
    private var writeIndex = 0
    private var storedFrames = 0
    private var closed = false

    val capacityFrames: Int get() = maxFrames

    fun availableFrames(): Int = synchronized(lock) { storedFrames }

    /** [pcm] is interleaved [channels]-channel 16-bit samples; [frames] is how many sample-groups
     * (frame = one sample per channel) it holds — NOT `pcm.size`. */
    fun write(pcm: ShortArray, frames: Int) {
        if (frames <= 0) return
        synchronized(lock) {
            if (closed) return
            var toWrite = frames
            var srcFrameOffset = 0
            // A single write larger than the whole buffer: only its tail fits.
            if (toWrite > maxFrames) {
                srcFrameOffset = toWrite - maxFrames
                toWrite = maxFrames
            }
            val overflow = storedFrames + toWrite - maxFrames
            if (overflow > 0) {
                readIndex = (readIndex + overflow) % maxFrames
                storedFrames -= overflow
            }
            var remaining = toWrite
            var srcFrame = srcFrameOffset
            while (remaining > 0) {
                val chunk = minOf(remaining, maxFrames - writeIndex)
                System.arraycopy(pcm, srcFrame * channels, buffer, writeIndex * channels, chunk * channels)
                writeIndex = (writeIndex + chunk) % maxFrames
                srcFrame += chunk
                remaining -= chunk
            }
            storedFrames += toWrite
        }
    }

    /** Fills [out] ([frames] frames, interleaved) from the buffer, padding the tail with silence when
     * fewer than [frames] are stored. Returns the number of REAL (non-silence) frames supplied. */
    fun read(out: ShortArray, frames: Int): Int = synchronized(lock) {
        val available = minOf(storedFrames, frames)
        var pos = 0
        var remaining = available
        while (remaining > 0) {
            val chunk = minOf(remaining, maxFrames - readIndex)
            System.arraycopy(buffer, readIndex * channels, out, pos * channels, chunk * channels)
            readIndex = (readIndex + chunk) % maxFrames
            pos += chunk
            remaining -= chunk
        }
        storedFrames -= available
        if (available < frames) java.util.Arrays.fill(out, available * channels, frames * channels, 0)
        available
    }

    fun close() = synchronized(lock) {
        closed = true
        storedFrames = 0
        readIndex = 0
        writeIndex = 0
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L

        // Within the task's suggested 60-120ms jitter window; combined with the playback line's own
        // small buffer (~50ms, see LiveAudioPlayer.LINE_BUFFER_MS) the total pipeline stays under the
        // ~200ms target.
        const val DEFAULT_MAX_LATENCY_MS = 120
    }
}

/**
 * Decodes a live scrcpy Opus stream — raw MediaCodec-produced Opus access units, one packet per
 * scrcpy audio record, never Ogg-encapsulated — into 16-bit interleaved stereo PCM at
 * [LIVE_AUDIO_SAMPLE_RATE_HZ], via the bundled JavaCPP FFmpeg's own low-level avcodec/swresample API
 * (the same library [com.indagium.capture.FfmpegCaptureVideoExporter] and
 * [com.indagium.capture.StreamingMkvWriter] already use — no new native dependency). Reopened by
 * [configure] whenever a fresh extradata (config) packet arrives, mirroring how
 * [EmbeddedDeviceSession] replays its own `lastConfigBytes`/`lastAudioConfigBytes` to a decoder that
 * attaches mid-stream. The extradata bytes are exactly scrcpy's audio config packet — the same bytes
 * [com.indagium.capture.StreamingMkvWriter.addAudio] already writes as-is into the Matroska track's
 * CodecPrivate, so they are already in the plain Opus extradata form this decoder expects.
 */
internal class OpusPcmDecoder(private val onDiagnostic: (String) -> Unit = {}) : Closeable {
    private var codecContext: AVCodecContext? = null
    private var resampler: SwrContext? = null
    private var frame: AVFrame? = null
    private var packet: org.bytedeco.ffmpeg.avcodec.AVPacket? = null
    private var failed = false

    fun configure(extradata: ByteArray) {
        close()
        val codec = findOpusDecoder()
        if (codec == null) {
            onDiagnostic("Live audio: no Opus decoder available in this FFmpeg build; playback disabled.")
            failed = true
            return
        }
        val ctx = avcodec_alloc_context3(codec)
        if (ctx == null || ctx.isNull) {
            failed = true
            return
        }
        ctx.sample_rate(LIVE_AUDIO_SAMPLE_RATE_HZ)
        av_channel_layout_default(ctx.ch_layout(), LIVE_AUDIO_CHANNELS)
        if (extradata.isNotEmpty()) setExtradata(ctx, extradata)
        if (avcodec_open2(ctx, codec, null as AVDictionary?) < 0) {
            onDiagnostic("Live audio: could not open the Opus decoder.")
            avcodec_free_context(ctx)
            failed = true
            return
        }
        codecContext = ctx
        packet = av_packet_alloc()
        frame = av_frame_alloc()
        failed = false
    }

    /** Decodes one Opus access unit; returns interleaved S16 stereo PCM, or null when nothing
     * decoded (not yet configured, needs more input, or a decode error already diagnosed once). */
    fun decode(data: ByteArray): ShortArray? {
        val ctx = codecContext
        val pkt = packet
        val frm = frame
        if (failed || ctx == null || pkt == null || frm == null) return null
        return try {
            if (av_new_packet(pkt, data.size) < 0) return null
            if (data.isNotEmpty()) pkt.data().position(0L).put(data, 0, data.size)
            pkt.size(data.size)
            if (avcodec_send_packet(ctx, pkt) < 0) return null
            val chunks = ArrayList<ShortArray>(2)
            while (avcodec_receive_frame(ctx, frm) == 0) {
                convertFrame(frm)?.let { chunks.add(it) }
            }
            mergeChunks(chunks)
        } finally {
            av_packet_unref(pkt)
        }
    }

    private fun convertFrame(frm: AVFrame): ShortArray? {
        val swr = resampler ?: allocateResampler(frm)?.also { resampler = it } ?: return null
        val maxOutSamples = swr_get_out_samples(swr, frm.nb_samples())
        if (maxOutSamples <= 0) return null
        val outputBuffer = BytePointer(maxOutSamples.toLong() * LIVE_AUDIO_CHANNELS * BYTES_PER_SAMPLE)
        // A single-slot PointerPointer whose slot 0 holds outputBuffer's own address — NOT the
        // `PointerPointer(Pointer)` "reinterpret this address as an existing pointer array" view
        // constructor, which would misread outputBuffer's sample bytes as a pointer table. `put(long,
        // Pointer)` on a freshly `capacity`-allocated instance is the correct "build a new one-element
        // array" idiom (output is packed S16, never planar, so exactly one slot is ever needed).
        val outPointers = PointerPointer<BytePointer>(1L).put(0L, outputBuffer)
        return try {
            val converted = swr_convert(swr, outPointers, maxOutSamples, frm.data(), frm.nb_samples())
            if (converted <= 0) return null
            val shorts = ShortArray(converted * LIVE_AUDIO_CHANNELS)
            outputBuffer.asBuffer().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            shorts
        } finally {
            outputBuffer.deallocate()
        }
    }

    private fun allocateResampler(frm: AVFrame): SwrContext? {
        val outLayout = AVChannelLayout()
        av_channel_layout_default(outLayout, LIVE_AUDIO_CHANNELS)
        val swr = swr_alloc() ?: return null
        val inRate = if (frm.sample_rate() > 0) frm.sample_rate() else LIVE_AUDIO_SAMPLE_RATE_HZ
        val configured = swr_alloc_set_opts2(
            swr,
            outLayout, AV_SAMPLE_FMT_S16, LIVE_AUDIO_SAMPLE_RATE_HZ,
            frm.ch_layout(), frm.format(), inRate,
            0, null,
        )
        if (configured < 0 || swr_init(swr) < 0) {
            swr_free(swr)
            return null
        }
        return swr
    }

    private fun mergeChunks(chunks: List<ShortArray>): ShortArray? = when (chunks.size) {
        0 -> null
        1 -> chunks[0]
        else -> {
            val merged = ShortArray(chunks.sumOf { it.size })
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(merged, offset)
                offset += chunk.size
            }
            merged
        }
    }

    private fun setExtradata(ctx: AVCodecContext, extradata: ByteArray) {
        val padded = ByteArray(extradata.size + AV_INPUT_BUFFER_PADDING_SIZE)
        extradata.copyInto(padded)
        val raw = av_malloc(padded.size.toLong()) ?: return
        val pointer = BytePointer(raw).capacity(padded.size.toLong())
        pointer.position(0L).put(padded, 0, padded.size)
        ctx.extradata(pointer).extradata_size(extradata.size)
    }

    override fun close() {
        resampler?.let { swr_free(it) }
        resampler = null
        frame?.let { av_frame_free(it) }
        frame = null
        packet?.let { av_packet_free(it) }
        packet = null
        codecContext?.let { if (!it.isNull) avcodec_free_context(it) }
        codecContext = null
        failed = false
    }
}

private data class LiveAudioPacket(val config: Boolean, val data: ByteArray)

internal data class OpenedAudioLine(val line: SourceDataLine, val mixerName: String)

/** Opens a [SourceDataLine] for [format], preferring a mixer that explicitly advertises support (so
 * the diagnostic can name it) and falling back to the JVM's own default resolution. Throws
 * [javax.sound.sampled.LineUnavailableException] (or any other [Exception]) when no output device is
 * available at all — callers must catch this to fail gracefully rather than crash. */
internal fun openDefaultAudioLine(format: AudioFormat, bufferBytes: Int): OpenedAudioLine {
    val info = DataLine.Info(SourceDataLine::class.java, format)
    for (mixerInfo in AudioSystem.getMixerInfo()) {
        val opened = runCatching {
            val mixer = AudioSystem.getMixer(mixerInfo)
            if (!mixer.isLineSupported(info)) return@runCatching null
            val line = mixer.getLine(info) as SourceDataLine
            line.open(format, bufferBytes)
            OpenedAudioLine(line, mixerInfo.name)
        }.getOrNull()
        if (opened != null) return opened
    }
    val line = AudioSystem.getLine(info) as SourceDataLine
    line.open(format, bufferBytes)
    return OpenedAudioLine(line, "default output")
}

/**
 * Plays a live scrcpy Opus audio stream on this computer while a capture with audio is running — the
 * other side of [EmbeddedDeviceSession.attachLiveAudio]'s tee. Owns two dedicated daemon threads:
 *  - a decode thread pulling raw Opus packets from a small bounded queue (dropping the OLDEST packet
 *    on overflow — [onAudioPacket]/[onAudioConfig] never block, so the audio pump thread that feeds
 *    them, which also writes the recording, is never slowed or stalled by playback) and decoding them
 *    into an [AudioJitterBuffer];
 *  - a playback thread reading fixed-size chunks out of that buffer and writing them to a
 *    [SourceDataLine] — the blocking `write()` call itself paces real-time playback, no manual sleep
 *    needed — playing silence on underrun instead of starving the line.
 * "No audio output device" (no line could be opened at all) is diagnosed once and playback simply
 * never starts; it is never a crash.
 */
internal class LiveAudioPlayer(
    private val onDiagnostic: (String) -> Unit = {},
    private val volume: () -> Float = { 1f },
    private val lineFactory: (AudioFormat, Int) -> OpenedAudioLine = ::openDefaultAudioLine,
) : LiveAudioSink {
    private val queue = ArrayBlockingQueue<LiveAudioPacket>(QUEUE_CAPACITY)
    private val jitterBuffer = AudioJitterBuffer()
    private val decoder = OpusPcmDecoder(onDiagnostic)
    private val running = AtomicBoolean(true)
    private val decodeThread = thread(name = "live-audio-decode", isDaemon = true) { runDecodeLoop() }
    private val playbackThread = thread(name = "live-audio-playback", isDaemon = true) { runPlaybackLoop() }
    private var line: SourceDataLine? = null

    override fun onAudioConfig(extradata: ByteArray) = offer(LiveAudioPacket(config = true, data = extradata))

    override fun onAudioPacket(ptsUs: Long, data: ByteArray) = offer(LiveAudioPacket(config = false, data = data))

    /** Never blocks: drops the oldest queued packet and retries once on overflow rather than ever
     * back-pressuring the caller (the recording's own audio pump thread). */
    private fun offer(packet: LiveAudioPacket) {
        if (queue.offer(packet)) return
        queue.poll()
        queue.offer(packet)
    }

    private fun runDecodeLoop() {
        while (running.get()) {
            val item = try {
                queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: continue
            if (item.config) {
                decoder.configure(item.data)
            } else {
                val pcm = runCatching { decoder.decode(item.data) }.getOrNull()
                if (pcm != null && pcm.isNotEmpty()) jitterBuffer.write(pcm, pcm.size / LIVE_AUDIO_CHANNELS)
            }
        }
    }

    private fun runPlaybackLoop() {
        val format = AudioFormat(LIVE_AUDIO_SAMPLE_RATE_HZ.toFloat(), 16, LIVE_AUDIO_CHANNELS, true, false)
        val bufferBytes = LIVE_AUDIO_SAMPLE_RATE_HZ * LINE_BUFFER_MS / MILLIS_PER_SECOND_INT * LIVE_AUDIO_CHANNELS * BYTES_PER_SAMPLE
        val opened = try {
            lineFactory(format, bufferBytes)
        } catch (failure: Exception) {
            onDiagnostic("Live audio: no output device available (${failure.message ?: failure::class.simpleName}); playback disabled.")
            return
        }
        line = opened.line
        onDiagnostic("Live audio: playing on ${opened.mixerName}")
        val chunkFrames = LIVE_AUDIO_SAMPLE_RATE_HZ * CHUNK_MS / MILLIS_PER_SECOND_INT
        val chunk = ShortArray(chunkFrames * LIVE_AUDIO_CHANNELS)
        val bytes = ByteArray(chunk.size * BYTES_PER_SAMPLE)
        try {
            opened.line.start()
            while (running.get()) {
                jitterBuffer.read(chunk, chunkFrames)
                applyVolume(chunk)
                shortsToLittleEndianBytes(chunk, bytes)
                opened.line.write(bytes, 0, bytes.size)
            }
        } finally {
            runCatching { opened.line.drain() }
            runCatching { opened.line.stop() }
            runCatching { opened.line.close() }
        }
    }

    private fun applyVolume(chunk: ShortArray) {
        val level = volume().coerceIn(0f, 1f)
        if (level >= 1f) return
        for (i in chunk.indices) chunk[i] = (chunk[i] * level).toInt().toShort()
    }

    override fun close() {
        running.set(false)
        decodeThread.interrupt()
        playbackThread.interrupt()
        runCatching { decodeThread.join(JOIN_MS) }
        runCatching { playbackThread.join(JOIN_MS) }
        runCatching { line?.close() }
        runCatching { decoder.close() }
        jitterBuffer.close()
    }

    private companion object {
        const val QUEUE_CAPACITY = 64
        const val POLL_TIMEOUT_MS = 200L
        const val CHUNK_MS = 20
        const val LINE_BUFFER_MS = 50
        const val MILLIS_PER_SECOND_INT = 1_000
        const val JOIN_MS = 500L
    }
}

private fun shortsToLittleEndianBytes(shorts: ShortArray, out: ByteArray) {
    for (i in shorts.indices) {
        val value = shorts[i].toInt()
        out[i * 2] = (value and 0xff).toByte()
        out[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
    }
}
