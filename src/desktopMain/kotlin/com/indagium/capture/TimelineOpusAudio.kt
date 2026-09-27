@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package com.indagium.capture

import com.indagium.capture.mirror.LIVE_AUDIO_CHANNELS
import com.indagium.capture.mirror.LIVE_AUDIO_SAMPLE_RATE_HZ
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_OPUS
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.javacpp.BytePointer
import java.io.Closeable
import java.io.IOException
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlin.concurrent.thread

/** Timestamped 48 kHz stereo PCM mixer. Inputs may arrive out of order by a few packets; the
 * bounded queue retains them until the capture clock reaches their presentation time. */
internal class TimelinePcmMixer(
    private val maxQueuedChunks: Int = MAX_QUEUED_PCM_CHUNKS,
) {
    private val lock = Any()
    private val chunks = ArrayDeque<PcmChunk>()
    private var emittedThroughFrame = 0L

    fun offer(ptsUs: Long, pcm: ShortArray): Long {
        if (pcm.isEmpty()) return 0L
        val channels = LIVE_AUDIO_CHANNELS
        val sampleFrames = pcm.size / channels
        if (sampleFrames == 0) return 0L
        var droppedFrames = 0L
        synchronized(lock) {
            var startFrame = ptsUs * LIVE_AUDIO_SAMPLE_RATE_HZ / MICROS_PER_SECOND
            var samples = pcm
            if (startFrame < 0) {
                val trimFrames = (-startFrame).coerceAtMost(sampleFrames.toLong()).toInt()
                droppedFrames += trimFrames
                if (trimFrames == sampleFrames) return droppedFrames
                samples = samples.copyOfRange(trimFrames * channels, samples.size)
                startFrame += trimFrames
            }
            if (startFrame < emittedThroughFrame) {
                val trimFrames = (emittedThroughFrame - startFrame).coerceAtMost((samples.size / channels).toLong()).toInt()
                droppedFrames += trimFrames
                if (trimFrames == samples.size / channels) return droppedFrames
                samples = samples.copyOfRange(trimFrames * channels, samples.size)
                startFrame += trimFrames
            }
            // Keep samples timestamp ordered. The queue is small (roughly 2.5s at the default),
            // so a sorted insert avoids a more complex lock-free multi-source timeline.
            val insertion = chunks.indexOfFirst { it.startFrame > startFrame }
            if (insertion < 0) {
                chunks.addLast(PcmChunk(startFrame, samples))
            } else {
                val copy = chunks.toMutableList()
                copy.add(insertion, PcmChunk(startFrame, samples))
                chunks.clear()
                copy.forEach(chunks::addLast)
            }
            while (chunks.size > maxQueuedChunks) {
                droppedFrames += chunks.removeFirst().samples.size / channels
            }
        }
        return droppedFrames
    }

    fun mix(startFrame: Long, frames: Int): ShortArray {
        require(frames > 0)
        val start = startFrame.coerceAtLeast(0)
        val end = start + frames
        val output = IntArray(frames * LIVE_AUDIO_CHANNELS)
        synchronized(lock) {
            val iterator = chunks.iterator()
            while (iterator.hasNext()) {
                val chunk = iterator.next()
                val chunkFrames = chunk.samples.size / LIVE_AUDIO_CHANNELS
                val chunkEnd = chunk.startFrame + chunkFrames
                if (chunkEnd <= start) {
                    iterator.remove()
                    continue
                }
                if (chunk.startFrame >= end) break
                val overlapStart = maxOf(start, chunk.startFrame)
                val overlapEnd = minOf(end, chunkEnd)
                for (frame in overlapStart until overlapEnd) {
                    val targetBase = ((frame - start) * LIVE_AUDIO_CHANNELS).toInt()
                    val sourceBase = ((frame - chunk.startFrame) * LIVE_AUDIO_CHANNELS).toInt()
                    for (channel in 0 until LIVE_AUDIO_CHANNELS) {
                        output[targetBase + channel] += chunk.samples[sourceBase + channel].toInt()
                    }
                }
                if (chunkEnd <= end) iterator.remove()
            }
            emittedThroughFrame = maxOf(emittedThroughFrame, end)
        }
        return ShortArray(output.size) { softLimitMix(output[it]).toShort() }
    }

    private data class PcmChunk(val startFrame: Long, val samples: ShortArray)

    /** Keep single-source PCM unchanged, then compress only peaks created by summing the host mic
     * and Android audio. Hard saturation clipped speech whenever both sources were loud at once. */
    private fun softLimitMix(sample: Int): Int {
        val magnitude = kotlin.math.abs(sample.toLong())
        if (magnitude <= LIMITER_THRESHOLD) return sample
        val limitedMagnitude = LIMITER_THRESHOLD + (magnitude - LIMITER_THRESHOLD) / LIMITER_RATIO_DENOMINATOR
        return (if (sample < 0) -limitedMagnitude else limitedMagnitude).toInt()
    }

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000L
        const val MAX_QUEUED_PCM_CHUNKS = 256
        const val LIMITER_THRESHOLD = 24_575L
        const val LIMITER_RATIO_DENOMINATOR = 7L
    }
}

/**
 * Smooths a device-audio stream's own jittery source timestamps into sample-count-continuous chunk
 * starts before they reach [TimelinePcmMixer] — the device-audio analogue of
 * [com.indagium.capture.MonotonicMicrophoneSampleClock], but deliberately a separate, simpler class
 * rather than a reuse of it: scrcpy's AudioRecord timestamps jitter by a few milliseconds between
 * consecutive 20ms Opus packets, and [TimelinePcmMixer] either SUMS that jitter's overlap or leaves
 * a tiny silent gap for it — either way, a broadband click on every jittered boundary (the regular
 * thin vertical lines a spectrogram of an affected capture shows).
 * [com.indagium.capture.MonotonicMicrophoneSampleClock]'s resync step (bounded to a couple of
 * milliseconds per buffer, to gently correct a real-time capture clock's slow wall-clock drift) is
 * the wrong shape for THIS source: a device can legitimately stop sending audio entirely for
 * seconds at a time (the screen is static, so scrcpy sends no video either — see
 * [com.indagium.video.shouldReportPlaybackStopped]'s KDoc for the player-side half of this), and the
 * very next chunk's pts then jumps forward by that whole real gap; stepping toward it a couple of
 * milliseconds per buffer would keep gluing new audio onto the end of the silence for seconds,
 * audibly wrong. So the rule here is binary, not gradual: within [toleranceUs] of the expected next
 * sample, snap to the expected sample count (contiguous — no overlap, no gap); beyond it — a real
 * discontinuity, either a silence gap or a stream/decoder reset — trust the observed pts immediately
 * and re-anchor there instead.
 */
internal class DeviceAudioContinuityClock(
    private val sampleRateHz: Int,
    private val toleranceUs: Long = DEFAULT_TOLERANCE_MS * MICROS_PER_MILLI,
) {
    private var nextSampleFrame: Long? = null

    /**
     * [observedStartUs] is this chunk's own device-reported (jittery) start, already rebased to the
     * recording's own t=0 — [sampleFrames] is how many frames it actually decoded to. Returns the
     * sample-count-continuous start to offer to the mixer instead, and advances the running
     * expectation by [sampleFrames] so the NEXT call's jitter is judged against THIS chunk's true
     * (possibly snapped) end, not its own noisy observed start.
     */
    fun continuousStartUs(observedStartUs: Long, sampleFrames: Int): Long {
        require(sampleFrames > 0)
        val observedStartFrame = observedStartUs * sampleRateHz / MICROS_PER_SECOND
        val expectedStartFrame = nextSampleFrame
        val toleranceFrames = toleranceUs * sampleRateHz / MICROS_PER_SECOND
        val startFrame = if (expectedStartFrame == null || kotlin.math.abs(observedStartFrame - expectedStartFrame) > toleranceFrames) {
            observedStartFrame
        } else {
            expectedStartFrame
        }
        nextSampleFrame = startFrame + sampleFrames
        return startFrame * MICROS_PER_SECOND / sampleRateHz
    }

    /** Forgets the running expectation. Callers reset this whenever the audio config is re-received
     *  or the device stream restarts — a fresh decoder/stream has no sample-count relationship to
     *  whatever this clock was tracking before, so judging its first chunk's jitter against the old
     *  expectation would misfire as either a false snap or a false discontinuity. */
    fun reset() {
        nextSampleFrame = null
    }

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000L
        const val MICROS_PER_MILLI = 1_000L
        const val DEFAULT_TOLERANCE_MS = 50L
    }
}

/** Opus encoder and paced mux writer for microphone plus optional Android audio. */
internal class TimelineOpusAudio(
    private val muxer: StreamingMkvWriter,
    private val elapsedMillis: () -> Long,
    private val videoAnchorElapsedMs: () -> Long?,
    private val onDiagnostic: (String) -> Unit,
) : Closeable {
    private val mixer = TimelinePcmMixer()
    private val encoder = OpusPcmEncoder()

    @Volatile private var closed = false

    @Volatile private var encoderClosed = false

    @Volatile private var trackInstalled = false

    @Volatile private var lateDropReported = false
    private var outputThread: Thread? = null
    private var nextFrame = 0L

    // Strictly monotonic packet index used to derive every written packet's pts — see
    // writeEncodedPacket's KDoc for why this replaces OpusPcmEncoder.EncodedPacket.ptsUs.
    private var nextPacketIndex = 0L

    fun extradata(): ByteArray = encoder.extradata

    @Synchronized
    fun installTrack() {
        if (trackInstalled) return
        muxer.addAudio(LIVE_AUDIO_SAMPLE_RATE_HZ, LIVE_AUDIO_CHANNELS, encoder.extradata)
        trackInstalled = true
        outputThread = thread(name = "capture-audio-mixer", isDaemon = true) { writeLoop() }
    }

    /** Device packet timestamps are already rebased to the first video packet by
     * EmbeddedDeviceSession. */
    fun offerDevice(ptsUs: Long, pcm: ShortArray) = mixer.offer(ptsUs, pcm).also(::reportDropped)

    /** Microphone timestamps use the capture clock. Rebase them to first video presentation. */
    fun offerMicrophone(captureElapsedUs: Long, pcm: ShortArray) {
        val anchorUs = videoAnchorElapsedMs()?.times(MICROS_PER_MILLI) ?: return
        mixer.offer(captureElapsedUs - anchorUs, pcm).also(::reportDropped)
    }

    private fun writeLoop() {
        try {
            while (!closed) {
                val anchor = videoAnchorElapsedMs()
                if (anchor == null) {
                    Thread.sleep(AUDIO_POLL_MS)
                    continue
                }
                val elapsedFrames = audioFramesReady(
                    elapsedMs = elapsedMillis(),
                    anchorMs = anchor,
                    playoutDelayMs = PLAYOUT_DELAY_MS,
                )
                while (nextFrame + encoder.frameSize <= elapsedFrames) {
                    writeFrame()
                }
                Thread.sleep(AUDIO_POLL_MS)
            }
        } catch (failure: InterruptedException) {
            if (!closed) Thread.currentThread().interrupt()
        } catch (failure: Throwable) {
            if (!closed) onDiagnostic("Microphone/device audio encoding stopped: ${failure.message ?: failure::class.simpleName}; video capture continues.")
        } finally {
            closed = true
            finishAudioOnWriterThread()
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        val writer = outputThread
        if (writer == null) {
            closeEncoder()
            return
        }
        writer.interrupt()
        if (writer === Thread.currentThread()) return
        var interrupted = false
        while (writer.isAlive) {
            try {
                writer.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    /** The writer thread is the sole owner of encode, mux, flush, and native encoder teardown. */
    private fun finishAudioOnWriterThread() {
        try {
            val anchor = videoAnchorElapsedMs()
            if (trackInstalled && anchor != null) {
                val finalFrames = ((elapsedMillis() - anchor).coerceAtLeast(0) * LIVE_AUDIO_SAMPLE_RATE_HZ / MILLIS_PER_SECOND)
                while (nextFrame + encoder.frameSize <= finalFrames) writeFrame()
            }
            encoder.flush().forEach(::writeEncodedPacket)
        } catch (failure: Throwable) {
            onDiagnostic("Final audio packets could not be written (${failure.message}); video capture remains intact.")
        } finally {
            closeEncoder()
        }
    }

    private fun closeEncoder() {
        if (encoderClosed) return
        runCatching { encoder.close() }
        encoderClosed = true
    }

    private fun reportDropped(frames: Long) {
        if (frames <= 0L || lateDropReported) return
        lateDropReported = true
        onDiagnostic("Audio mixer dropped $frames late or overrun sample frame(s); its bounded playout buffer resynchronized. Audio capture continues.")
    }

    private fun writeFrame() {
        val samples = mixer.mix(nextFrame, encoder.frameSize)
        encoder.encode(samples, nextFrame).forEach(::writeEncodedPacket)
        nextFrame += encoder.frameSize
    }

    /**
     * Writes one encoded packet at a strictly monotonic, non-overlapping pts derived from
     * [nextPacketIndex] * the fixed Opus frame size — never from
     * [OpusPcmEncoder.EncodedPacket.ptsUs]. libopus reports a NEGATIVE pts for its first packet (the
     * codec's own initial padding/pre-skip, ~312 samples at 48 kHz); the previous
     * `packet.ptsUs?.takeIf { it >= 0 } ?: ptsBaseUs` fallback treated that missing case as "no pts"
     * and placed packet 0 at t=0, while packet 1's (still padding-shifted, but now non-negative)
     * reported pts placed IT at ~14ms instead of the true 20ms frame boundary — two 20ms packets
     * overlapping by ~6ms, audible as a click at the very start of every recording. Every packet
     * [encoder] emits (whether from [writeFrame]'s per-call encode or a delayed one released only by
     * [flush]) corresponds 1:1, in order, to one [encoder].frameSize-sized input frame, so counting
     * emitted packets is exactly equivalent to counting input frames — this holds across the flush
     * call at the end too, which is why [finishAudioOnWriterThread] routes its own flush through this
     * same function instead of computing a separate final pts.
     */
    private fun writeEncodedPacket(packet: OpusPcmEncoder.EncodedPacket) {
        val ptsUs = nextPacketIndex * encoder.frameSize * MICROS_PER_SECOND / LIVE_AUDIO_SAMPLE_RATE_HZ
        nextPacketIndex++
        muxer.writeAudioPacket(ptsUs, packet.data)
    }

    private companion object {
        const val MICROS_PER_MILLI = 1_000L
        const val MILLIS_PER_SECOND = 1_000L
        const val MICROS_PER_SECOND = 1_000_000L
        const val AUDIO_POLL_MS = 4L

        // USB/Bluetooth audio backends can deliver already-timestamped chunks well after their
        // capture PTS. Keep enough bounded playout delay that those chunks enter the mixer before
        // the writer commits their timeline interval.
        const val PLAYOUT_DELAY_MS = 250L
    }
}

internal fun audioFramesReady(elapsedMs: Long, anchorMs: Long, playoutDelayMs: Long): Long =
    (elapsedMs - anchorMs - playoutDelayMs).coerceAtLeast(0) * LIVE_AUDIO_SAMPLE_RATE_HZ / 1_000L

internal class OpusPcmEncoder(
    private val targetBitRateBps: Long = MIXED_AUDIO_OPUS_BITRATE_BPS,
) : Closeable {
    private val codecContext: AVCodecContext
    private val frame: AVFrame
    private val packet = av_packet_alloc() ?: throw IOException("FFmpeg could not allocate an Opus packet")
    val frameSize: Int
    val extradata: ByteArray

    init {
        val codec = listOf("libopus", "opus").firstNotNullOfOrNull { name ->
            avcodec_find_encoder_by_name(name)?.takeUnless { it.isNull }
        } ?: avcodec_find_encoder(AV_CODEC_ID_OPUS)?.takeUnless { it.isNull }
            ?: throw IOException("Bundled FFmpeg has no Opus encoder")
        val context = avcodec_alloc_context3(codec)
        if (context == null || context.isNull) throw IOException("FFmpeg could not allocate an Opus encoder")
        codecContext = context
        context.sample_rate(LIVE_AUDIO_SAMPLE_RATE_HZ)
        av_channel_layout_default(context.ch_layout(), LIVE_AUDIO_CHANNELS)
        context.sample_fmt(AV_SAMPLE_FMT_S16)
        context.bit_rate(targetBitRateBps)
        context.time_base(AVRational().num(1).den(LIVE_AUDIO_SAMPLE_RATE_HZ))
        if (avcodec_open2(context, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) < 0) {
            avcodec_free_context(context)
            throw IOException("FFmpeg could not open the Opus encoder")
        }
        frameSize = context.frame_size().takeIf { it > 0 } ?: DEFAULT_OPUS_FRAME_SIZE
        extradata = ByteArray(context.extradata_size()).also { bytes ->
            if (bytes.isNotEmpty()) context.extradata().position(0L).get(bytes, 0, bytes.size)
        }
        if (extradata.isEmpty()) {
            avcodec_free_context(context)
            throw IOException("FFmpeg Opus encoder did not provide stream metadata")
        }
        frame = av_frame_alloc() ?: throw IOException("FFmpeg could not allocate an Opus frame")
        frame.format(AV_SAMPLE_FMT_S16)
        frame.sample_rate(LIVE_AUDIO_SAMPLE_RATE_HZ)
        av_channel_layout_default(frame.ch_layout(), LIVE_AUDIO_CHANNELS)
        frame.nb_samples(frameSize)
        if (av_frame_get_buffer(frame, 0) < 0) throw IOException("FFmpeg could not allocate Opus frame samples")
    }

    fun encode(samples: ShortArray, ptsSamples: Long): List<EncodedPacket> {
        require(samples.size == frameSize * LIVE_AUDIO_CHANNELS)
        frame.pts(ptsSamples)
        // AVFrame.data() is an unbounded native pointer from JavaCPP's point of view. Give the
        // wrapper the byte count allocated by av_frame_get_buffer before creating the NIO view.
        val sampleBytes = frameSize.toLong() * LIVE_AUDIO_CHANNELS * Short.SIZE_BYTES
        val sampleBuffer = BytePointer(frame.data(0))
            .capacity(sampleBytes)
            .position(0L)
            .asByteBuffer()
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        sampleBuffer.put(samples)
        if (avcodec_send_frame(codecContext, frame) < 0) throw IOException("FFmpeg rejected an Opus audio frame")
        return receivePackets()
    }

    fun flush(): List<EncodedPacket> {
        if (avcodec_send_frame(codecContext, null as AVFrame?) < 0) {
            throw IOException("FFmpeg could not flush the Opus encoder")
        }
        return receivePackets()
    }

    private fun receivePackets(): List<EncodedPacket> {
        val result = mutableListOf<EncodedPacket>()
        while (true) {
            val received = avcodec_receive_packet(codecContext, packet)
            if (received < 0) break
            try {
                val bytes = ByteArray(packet.size())
                if (bytes.isNotEmpty()) packet.data().position(0L).get(bytes, 0, bytes.size)
                val packetPts = packet.pts().takeIf { it != AV_NOPTS_VALUE }
                val ptsUs = packetPts?.let { it * MICROS_PER_SECOND / LIVE_AUDIO_SAMPLE_RATE_HZ }
                result += EncodedPacket(ptsUs, bytes)
            } finally {
                av_packet_unref(packet)
            }
        }
        return result
    }

    override fun close() {
        av_packet_free(packet)
        av_frame_free(frame)
        avcodec_free_context(codecContext)
    }

    internal data class EncodedPacket(val ptsUs: Long?, val data: ByteArray)

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000L
        const val DEFAULT_OPUS_FRAME_SIZE = 960
    }
}

internal const val MIXED_AUDIO_OPUS_BITRATE_BPS = 160_000L
