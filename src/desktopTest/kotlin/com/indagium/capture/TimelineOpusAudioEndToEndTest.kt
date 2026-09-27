@file:Suppress("MagicNumber")

package com.indagium.capture

import com.indagium.capture.mirror.LIVE_AUDIO_CHANNELS
import com.indagium.capture.mirror.LIVE_AUDIO_SAMPLE_RATE_HZ
import com.indagium.video.configureCaptureMkvOpusDecoder
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end regression coverage for the audio-mux pts bug (see [TimelineOpusAudio.writeEncodedPacket]'s
 * KDoc): drives a synthetic 1 kHz tone, delivered in irregular chunk sizes with timestamps from the
 * real [MonotonicMicrophoneSampleClock], through the real [TimelinePcmMixer]/[TimelineOpusAudio]/
 * [StreamingMkvWriter] pipeline into a temp Matroska file, then decodes it back with
 * [FFmpegFrameGrabber] — the same decoder path production playback and export use — and checks the
 * result is a clean, monotonically-timestamped tone rather than the overlapping-packet click the bug
 * produced.
 */
class TimelineOpusAudioEndToEndTest {
    @Test
    fun syntheticToneSurvivesMixerEncoderAndMuxerWithMonotonicNonOverlappingPackets() {
        val destination = tempCaptureMkv()
        val writer = StreamingMkvWriter(destination)
        // A real (if trivial) H.264 keyframe, not empty extradata: the Matroska muxer's own avcC
        // validation rejects an H.264 track with no usable extradata at avformat_write_header time,
        // and this test only cares about the audio track anyway — mirroring how production always
        // has a real video stream (see EmbeddedDeviceSession) keeps this test representative
        // instead of exercising an unrelated video-track edge case this pipeline was never meant to
        // cover.
        val (videoExtradata, videoKeyframe) = encodeOneSyntheticH264Keyframe()
        writer.start(width = VIDEO_WIDTH, height = VIDEO_HEIGHT, extradata = videoExtradata)

        val elapsedMs = AtomicLong(0)
        val pipeline = TimelineOpusAudio(
            muxer = writer,
            elapsedMillis = { elapsedMs.get() },
            videoAnchorElapsedMs = { 0L },
            onDiagnostic = {},
        )
        // addAudio() must run before the very first packet write (StreamingMkvWriter's own
        // contract), so install the audio track before writing the one dummy video keyframe.
        pipeline.installTrack()
        writer.writeVideoPacket(0L, keyFrame = true, data = videoKeyframe)

        offerSyntheticTone(pipeline, elapsedMs)

        // The writer drains on its own background thread, polling real wall time
        // (TimelineOpusAudio.AUDIO_POLL_MS) against whatever this fake clock currently reports.
        // Push the fake clock past the end of the offered content, then give that poll loop a
        // moment of real time to observe it and drain, before close()'s own final flush handles
        // any remainder.
        elapsedMs.set(TOTAL_FRAMES * 1_000L / LIVE_AUDIO_SAMPLE_RATE_HZ + END_MARGIN_MS)
        Thread.sleep(DRAIN_WAIT_MS)
        pipeline.close()
        writer.finish()

        val decoded = decodeAudio(destination)
        assertMonotonicNonOverlappingPackets(decoded.timestampsUs)

        val analysis = trimmedMono(decoded.mono)
        assertTrue(analysis.isNotEmpty(), "decoded audio should not be empty")
        assertDominantFrequencyNear(analysis, TONE_FREQUENCY_HZ)
        assertRmsWithinExpectedRange(analysis, AMPLITUDE)
        assertNoLongDiscontinuities(analysis)
    }

    /**
     * The device-audio counterpart of the test above — see [DeviceAudioContinuityClock]'s own KDoc
     * for the bug: scrcpy's own AudioRecord timestamps jitter by a few ms between consecutive 20ms
     * Opus packets, and offering that jitter straight to [TimelinePcmMixer] either sums the overlap
     * or leaves a tiny silent gap for it — a click on every jittered boundary (the regular thin
     * broadband lines a spectrogram of an affected real capture shows). Feeds the same clean 1kHz
     * tone as regular 960-frame (20ms) chunks — matching a real decoded Opus packet's frame count —
     * through [DeviceAudioContinuityClock] before [TimelineOpusAudio.offerDevice], with each chunk's
     * OWN reported pts jittered by a few ms around its true schedule, and asserts the decoded result
     * is still click-free: no sample-to-sample discontinuity spike well above what a clean 1kHz tone
     * produces on its own.
     */
    @Test
    fun deviceAudioToneWithJitteredPtsDecodesClickFree() {
        val destination = tempCaptureMkv()
        val writer = StreamingMkvWriter(destination)
        val (videoExtradata, videoKeyframe) = encodeOneSyntheticH264Keyframe()
        writer.start(width = VIDEO_WIDTH, height = VIDEO_HEIGHT, extradata = videoExtradata)

        val elapsedMs = AtomicLong(0)
        val pipeline = TimelineOpusAudio(
            muxer = writer,
            elapsedMillis = { elapsedMs.get() },
            videoAnchorElapsedMs = { 0L },
            onDiagnostic = {},
        )
        pipeline.installTrack()
        writer.writeVideoPacket(0L, keyFrame = true, data = videoKeyframe)

        offerJitteredDeviceTone(pipeline, elapsedMs)

        elapsedMs.set(TOTAL_FRAMES * 1_000L / LIVE_AUDIO_SAMPLE_RATE_HZ + END_MARGIN_MS)
        Thread.sleep(DRAIN_WAIT_MS)
        pipeline.close()
        writer.finish()

        val decoded = decodeAudio(destination)
        assertMonotonicNonOverlappingPackets(decoded.timestampsUs)

        val analysis = trimmedMono(decoded.mono)
        assertTrue(analysis.isNotEmpty(), "decoded audio should not be empty")
        assertDominantFrequencyNear(analysis, DEVICE_TONE_FREQUENCY_HZ)
        assertNoClickLikeDiscontinuities(analysis, DEVICE_TONE_FREQUENCY_HZ, AMPLITUDE)
    }

    private fun offerJitteredDeviceTone(pipeline: TimelineOpusAudio, elapsedMs: AtomicLong) {
        val chunkFrames = 960 // a real decoded scrcpy Opus packet's frame count at 48kHz/20ms
        val continuity = DeviceAudioContinuityClock(LIVE_AUDIO_SAMPLE_RATE_HZ)
        // A realistic jitter pattern (device timestamp noise), cycling through both directions —
        // never a real gap, so DeviceAudioContinuityClock should absorb every one of these.
        val jitterUs = longArrayOf(0L, 3_000L, -2_000L, 4_000L, -1_000L, -5_000L, 2_000L, -3_000L, 1_000L, -4_000L)
        var producedFrames = 0L
        var chunkIndex = 0
        while (producedFrames < TOTAL_FRAMES) {
            val framesThisChunk = minOf(chunkFrames.toLong(), TOTAL_FRAMES - producedFrames).toInt()
            // Deliberately NOT TONE_FREQUENCY_HZ (1000Hz): at 20ms/960-frame chunks, 1000Hz completes
            // exactly 20 whole cycles per chunk, so every chunk boundary sits at the SAME phase
            // (a zero-crossing) regardless of any mixer-timeline jitter — masking exactly the
            // boundary discontinuity this test exists to catch. A non-aligned frequency makes a real
            // overlap/gap show up as a genuine amplitude jump instead of a coincidental non-event.
            val chunk = sineChunk(producedFrames, framesThisChunk, DEVICE_TONE_FREQUENCY_HZ, AMPLITUDE)
            val trueScheduleUs = producedFrames * 1_000_000L / LIVE_AUDIO_SAMPLE_RATE_HZ
            val jitteredPtsUs = (trueScheduleUs + jitterUs[chunkIndex % jitterUs.size]).coerceAtLeast(0L)
            val continuousPtsUs = continuity.continuousStartUs(jitteredPtsUs, framesThisChunk)
            pipeline.offerDevice(continuousPtsUs, chunk)
            elapsedMs.set(elapsedMs.get() + framesThisChunk * 1_000L / LIVE_AUDIO_SAMPLE_RATE_HZ)
            producedFrames += framesThisChunk
            chunkIndex++
        }
    }

    /**
     * A clean sine's own sample-to-sample delta is bounded by `amplitude * 2*pi*frequency /
     * sampleRate` (its derivative's peak); a click from summing (or leaving a silent gap at) an
     * overlapping/misaligned same-source chunk boundary spikes far above that — comparable to the
     * amplitude itself, not a fraction of it. [CLICK_DELTA_MULTIPLIER] gives ordinary Opus
     * quantization/lossy-compression noise generous headroom while still catching a real click.
     */
    private fun assertNoClickLikeDiscontinuities(mono: ShortArray, frequencyHz: Double, amplitude: Int) {
        val theoreticalMaxDeltaPerSample = amplitude * 2.0 * PI * frequencyHz / LIVE_AUDIO_SAMPLE_RATE_HZ
        val threshold = theoreticalMaxDeltaPerSample * CLICK_DELTA_MULTIPLIER
        var maxObservedDelta = 0
        var maxObservedAtFrame = 0
        for (i in 1 until mono.size) {
            val delta = abs(mono[i].toInt() - mono[i - 1].toInt())
            if (delta > maxObservedDelta) {
                maxObservedDelta = delta
                maxObservedAtFrame = i
            }
        }
        assertTrue(
            maxObservedDelta <= threshold,
            "found a click-like discontinuity around frame $maxObservedAtFrame " +
                "(${maxObservedAtFrame.toDouble() / LIVE_AUDIO_SAMPLE_RATE_HZ}s in): max adjacent-sample delta " +
                "$maxObservedDelta exceeds the smooth-sine bound of ~$threshold (clean theoretical max " +
                "~$theoreticalMaxDeltaPerSample)",
        )
    }

    private fun offerSyntheticTone(pipeline: TimelineOpusAudio, elapsedMs: AtomicLong) {
        val chunkSizesFrames = intArrayOf(441, 960, 1500)
        val sampleClock = MonotonicMicrophoneSampleClock()
        var producedFrames = 0L
        var chunkIndex = 0
        while (producedFrames < TOTAL_FRAMES) {
            val framesThisChunk = minOf(chunkSizesFrames[chunkIndex % chunkSizesFrames.size].toLong(), TOTAL_FRAMES - producedFrames).toInt()
            val chunk = sineChunk(producedFrames, framesThisChunk, TONE_FREQUENCY_HZ, AMPLITUDE)
            val readCompletedMs = elapsedMs.get() + framesThisChunk * 1_000L / LIVE_AUDIO_SAMPLE_RATE_HZ
            val startUs = sampleClock.startElapsedUs(readCompletedMs, framesThisChunk)
            pipeline.offerMicrophone(startUs, chunk)
            elapsedMs.set(readCompletedMs)
            producedFrames += framesThisChunk
            chunkIndex++
        }
    }

    private fun sineChunk(startFrame: Long, frameCount: Int, frequencyHz: Double, amplitude: Int): ShortArray {
        val samples = ShortArray(frameCount * LIVE_AUDIO_CHANNELS)
        for (i in 0 until frameCount) {
            val t = (startFrame + i).toDouble() / LIVE_AUDIO_SAMPLE_RATE_HZ
            val value = (amplitude * sin(2.0 * PI * frequencyHz * t)).toInt().toShort()
            samples[i * LIVE_AUDIO_CHANNELS] = value
            samples[i * LIVE_AUDIO_CHANNELS + 1] = value
        }
        return samples
    }

    private data class DecodedAudio(val timestampsUs: List<Long>, val mono: ShortArray)

    private fun decodeAudio(file: File): DecodedAudio {
        val timestamps = mutableListOf<Long>()
        val monoChunks = mutableListOf<ShortArray>()
        FFmpegFrameGrabber(file).use { grabber ->
            configureCaptureMkvOpusDecoder(grabber, file.absolutePath)
            grabber.sampleMode = FrameGrabber.SampleMode.SHORT
            grabber.start()
            // generateSequence (rather than a while(true) with break/continue) so detekt's
            // LoopWithTooManyJumpStatements has no loop body to flag — decodeMonoChunk's own early
            // returns live in a plain function instead.
            generateSequence { grabber.grabSamples() }.forEach { frame ->
                decodeMonoChunk(frame)?.let { chunk ->
                    timestamps += frame.timestamp
                    monoChunks += chunk
                }
            }
        }
        val mono = ShortArray(monoChunks.sumOf { it.size })
        var offset = 0
        monoChunks.forEach { chunk -> chunk.copyInto(mono, offset); offset += chunk.size }
        return DecodedAudio(timestamps, mono)
    }

    private fun decodeMonoChunk(frame: Frame): ShortArray? {
        val buffer = frame.samples?.firstOrNull() as? ShortBuffer ?: return null
        val duplicate = buffer.duplicate()
        if (!duplicate.hasRemaining()) return null
        val interleaved = ShortArray(duplicate.remaining())
        duplicate.get(interleaved)
        val channels = LIVE_AUDIO_CHANNELS
        val frameCount = interleaved.size / channels
        return ShortArray(frameCount) { i ->
            var sum = 0
            for (c in 0 until channels) sum += interleaved[i * channels + c]
            (sum / channels).toShort()
        }
    }

    private fun assertMonotonicNonOverlappingPackets(timestampsUs: List<Long>) {
        assertTrue(timestampsUs.size > 10, "expected multiple decoded audio packets, got ${timestampsUs.size}")
        // OpusPcmEncoder's frame size at 48 kHz is the standard 960-sample (20ms) Opus frame.
        val expectedSpacingUs = 960L * 1_000_000L / LIVE_AUDIO_SAMPLE_RATE_HZ
        for (i in 1 until timestampsUs.size) {
            val delta = timestampsUs[i] - timestampsUs[i - 1]
            assertTrue(delta > 0, "pts must be strictly increasing at index $i: ${timestampsUs[i - 1]} -> ${timestampsUs[i]}")
            assertTrue(
                delta >= expectedSpacingUs - PTS_TOLERANCE_US,
                "packet $i overlaps the previous one: spacing ${delta}us < the expected ~${expectedSpacingUs}us frame period",
            )
        }
    }

    private fun trimmedMono(mono: ShortArray): ShortArray {
        // Skip the Opus encoder's initial pre-skip/ramp-up at the start. At the end, skip both that
        // same margin AND END_MARGIN_MS: the tail push past the offered tone's own end (so the
        // writer's poll loop has something to drain up to — see offerSyntheticTone's caller) is
        // genuine trailing silence, not the pipeline's fault, and must not be mistaken for a dropout
        // by assertNoLongDiscontinuities.
        val startMarginFrames = (LIVE_AUDIO_SAMPLE_RATE_HZ * TRIM_MARGIN_MS / 1_000L).toInt()
        val endMarginFrames = (LIVE_AUDIO_SAMPLE_RATE_HZ * (TRIM_MARGIN_MS + END_MARGIN_MS) / 1_000L).toInt()
        val from = startMarginFrames.coerceAtMost(mono.size)
        val to = (mono.size - endMarginFrames).coerceAtLeast(from)
        return mono.copyOfRange(from, to)
    }

    private fun assertDominantFrequencyNear(mono: ShortArray, expectedHz: Double) {
        var crossings = 0
        for (i in 1 until mono.size) {
            if ((mono[i - 1] < 0) != (mono[i] < 0)) crossings++
        }
        val durationSeconds = mono.size.toDouble() / LIVE_AUDIO_SAMPLE_RATE_HZ
        val estimatedHz = crossings / 2.0 / durationSeconds
        assertTrue(
            abs(estimatedHz - expectedHz) < expectedHz * 0.1,
            "expected a dominant frequency near ${expectedHz}Hz, estimated ${estimatedHz}Hz from $crossings zero crossings over ${durationSeconds}s",
        )
    }

    private fun assertRmsWithinExpectedRange(mono: ShortArray, inputAmplitude: Int) {
        val rms = rmsOf(mono)
        val expectedRms = inputAmplitude / sqrt(2.0)
        val ratio = rms / expectedRms
        assertTrue(ratio in 0.5..1.5, "decoded RMS $rms should stay within a few dB of the expected $expectedRms (ratio=$ratio)")
    }

    private fun assertNoLongDiscontinuities(mono: ShortArray) {
        val windowFrames = LIVE_AUDIO_SAMPLE_RATE_HZ / 10 // 100ms windows
        var index = 0
        val minAcceptableWindowRms = AMPLITUDE / 8.0 / sqrt(2.0)
        while (index + windowFrames <= mono.size) {
            val window = mono.copyOfRange(index, index + windowFrames)
            val rms = rmsOf(window)
            assertTrue(
                rms > minAcceptableWindowRms,
                "found a dropout/discontinuity around ${index.toDouble() / LIVE_AUDIO_SAMPLE_RATE_HZ}s into the decoded tone (window rms=$rms)",
            )
            index += windowFrames
        }
    }

    private fun rmsOf(samples: ShortArray): Double = sqrt(samples.sumOf { it.toDouble() * it.toDouble() } / samples.size)

    private fun tempCaptureMkv(): File {
        val dir = Files.createTempDirectory("indagium-audio-e2e-").toFile().apply { deleteOnExit() }
        val videoDir = File(dir, "video").apply { mkdirs() }
        return File(videoDir, "screen.mkv").apply { deleteOnExit() }
    }

    /** One tiny libopenh264-encoded keyframe, split into its own SPS+PPS extradata and a single
     * Annex-B slice NAL — just enough for [StreamingMkvWriter.start]'s H.264 track to write a valid
     * header and for the finalized file to open at all; this test never decodes or asserts on the
     * video track itself. Same encoder [StreamingMkvWriterTest] uses, trimmed to one frame. */
    private fun encodeOneSyntheticH264Keyframe(): Pair<ByteArray, ByteArray> {
        val raw = Files.createTempFile("indagium-audio-e2e-h264-", ".h264").toFile().apply { deleteOnExit() }
        val pixels = ByteBuffer.allocate(VIDEO_WIDTH * VIDEO_HEIGHT * 3)
        val recorder = FFmpegFrameRecorder(raw, VIDEO_WIDTH, VIDEO_HEIGHT, 0).apply {
            format = "h264"
            frameRate = 30.0
            videoCodec = AV_CODEC_ID_H264
            videoCodecName = "libopenh264"
            videoBitrate = 100_000
            gopSize = 1
        }
        recorder.start()
        try {
            repeat(VIDEO_WIDTH * VIDEO_HEIGHT) { pixels.put(0).put(0).put(0) }
            pixels.flip()
            recorder.timestamp = 0L
            recorder.recordImage(VIDEO_WIDTH, VIDEO_HEIGHT, 8, 3, VIDEO_WIDTH * 3, AV_PIX_FMT_BGR24, pixels)
        } finally {
            recorder.stop()
            recorder.release()
        }
        val nalUnits = splitAnnexBNalUnits(raw.readBytes())
        raw.delete()
        val configUnits = mutableListOf<ByteArray>()
        var keyframe: ByteArray? = null
        for (nal in nalUnits) {
            when (nal[nal.annexBStartCodeLength()].toInt() and 0x1f) {
                7, 8 -> configUnits += nal // SPS, PPS
                5 -> if (keyframe == null) keyframe = nal // first IDR slice
                else -> Unit
            }
        }
        val extradata = configUnits.reduce { acc, bytes -> acc + bytes }
        return extradata to requireNotNull(keyframe) { "encoder must have produced an IDR slice" }
    }

    @Suppress("ComplexCondition")
    private fun ByteArray.annexBStartCodeLength(): Int =
        if (size >= 4 && this[0] == 0.toByte() && this[1] == 0.toByte() && this[2] == 0.toByte() && this[3] == 1.toByte()) 4 else 3

    private fun splitAnnexBNalUnits(bytes: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var index = 0
        while (index + 3 < bytes.size) {
            val isFourByte = bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() &&
                bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte()
            val isThreeByte = !isFourByte && bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() && bytes[index + 2] == 1.toByte()
            if (isFourByte || isThreeByte) {
                starts += index
                index += if (isFourByte) 4 else 3
            } else {
                index++
            }
        }
        return starts.indices.map { i ->
            val from = starts[i]
            val to = if (i + 1 < starts.size) starts[i + 1] else bytes.size
            bytes.copyOfRange(from, to)
        }
    }

    private companion object {
        const val TONE_FREQUENCY_HZ = 1_000.0
        const val DEVICE_TONE_FREQUENCY_HZ = 1_013.0
        const val AMPLITUDE = 10_000
        const val DURATION_SECONDS = 3L
        const val TOTAL_FRAMES = DURATION_SECONDS * LIVE_AUDIO_SAMPLE_RATE_HZ
        const val END_MARGIN_MS = 300L
        const val DRAIN_WAIT_MS = 300L
        const val TRIM_MARGIN_MS = 100L
        const val PTS_TOLERANCE_US = 500L
        const val VIDEO_WIDTH = 64
        const val VIDEO_HEIGHT = 48
        const val CLICK_DELTA_MULTIPLIER = 3.0
    }
}
