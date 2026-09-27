@file:Suppress("MagicNumber")

package com.indagium.capture

import com.indagium.capture.mirror.LIVE_AUDIO_CHANNELS
import com.indagium.capture.mirror.LIVE_AUDIO_SAMPLE_RATE_HZ
import com.indagium.capture.mirror.OpusPcmDecoder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpusPcmEncoderTest {
    @Test
    fun bundledEncoderProvidesTrackMetadataAndEncodesTheMixFormat() {
        OpusPcmEncoder().use { encoder ->
            assertFalse(encoder.extradata.isEmpty())
            val encoded = buildList {
                repeat(3) { index ->
                    addAll(encoder.encode(ShortArray(encoder.frameSize * 2), index * encoder.frameSize.toLong()))
                }
                addAll(encoder.flush())
            }
            assertFalse(encoded.isEmpty())
            assertFalse(encoded.any { it.data.isEmpty() })
        }
    }

    @Test
    fun mixedMicrophoneAndDeviceAudioSurviveOpusEncodeDecodeWithoutHardClipping() {
        OpusPcmEncoder().use { encoder ->
            val mixer = TimelinePcmMixer()
            val sourceFrames = encoder.frameSize * 12
            mixer.offer(0, sine(440.0, sourceFrames, 22_000))
            mixer.offer(0, sine(660.0, sourceFrames, 22_000))
            val mixed = mixer.mix(0, sourceFrames)
            assertTrue(mixed.any { it != 0.toShort() }, "mixed microphone/device PCM should carry audio")
            assertTrue(mixed.maxOf { kotlin.math.abs(it.toInt()) } < Short.MAX_VALUE, "mix limiter should prevent hard clipping")

            val encoded = buildList {
                for (frameStart in 0 until sourceFrames step encoder.frameSize) {
                    val frame = mixed.copyOfRange(
                        frameStart * LIVE_AUDIO_CHANNELS,
                        (frameStart + encoder.frameSize) * LIVE_AUDIO_CHANNELS,
                    )
                    addAll(encoder.encode(frame, frameStart.toLong()))
                }
                addAll(encoder.flush())
            }
            assertTrue(encoded.isNotEmpty(), "mixed audio should encode to Opus packets")

            val decoder = OpusPcmDecoder(onDiagnostic = {})
            try {
                decoder.configure(encoder.extradata)
                val decoded = encoded.mapNotNull { decoder.decode(it.data) }
                assertTrue(decoded.isNotEmpty(), "encoded mic/device mix should decode")
                assertTrue(decoded.sumOf { samples -> samples.sumOf { it.toLong() * it.toLong() } } > 0L)
                val decodedFrames = decoded.sumOf { it.size / LIVE_AUDIO_CHANNELS }
                assertTrue(decodedFrames > 5_000, "round trip should retain a steady segment after Opus pre-skip")
                assertTrue(
                    toneProjection(decoded, 440.0, decodedFrames) > MIN_TONE_PROJECTION,
                    "the microphone-frequency component should survive the encoded track",
                )
                assertTrue(
                    toneProjection(decoded, 660.0, decodedFrames) > MIN_TONE_PROJECTION,
                    "the device-audio-frequency component should survive the encoded track",
                )
            } finally {
                decoder.close()
            }
        }
    }

    @Test
    fun mixedAudioBitrateImprovesSourceMatchedOpusRoundTripFidelity() {
        val source = musicLikeSignal(SOURCE_FRAMES)
        val baseline = encodeDecode(source, BASELINE_BITRATE_BPS)
        val mixed = encodeDecode(source, MIXED_AUDIO_OPUS_BITRATE_BPS)

        val baselineError = alignedRelativeError(source, baseline)
        val mixedError = alignedRelativeError(source, mixed)
        assertTrue(
            mixedError < baselineError * 0.8,
            "the production mixed-audio bitrate should reduce re-encode error: 96 kbps=$baselineError, " +
                "${MIXED_AUDIO_OPUS_BITRATE_BPS / 1_000} kbps=$mixedError",
        )
    }

    private fun encodeDecode(source: ShortArray, bitrateBps: Long): ShortArray {
        OpusPcmEncoder(bitrateBps).use { encoder ->
            val packets = buildList {
                val sourceFrames = source.size / LIVE_AUDIO_CHANNELS
                for (frameStart in 0 until sourceFrames step encoder.frameSize) {
                    val from = frameStart * LIVE_AUDIO_CHANNELS
                    val to = (frameStart + encoder.frameSize) * LIVE_AUDIO_CHANNELS
                    addAll(encoder.encode(source.copyOfRange(from, to), frameStart.toLong()))
                }
                addAll(encoder.flush())
            }
            val decoder = OpusPcmDecoder(onDiagnostic = {})
            try {
                decoder.configure(encoder.extradata)
                val chunks = packets.mapNotNull { decoder.decode(it.data) }
                val result = ShortArray(chunks.sumOf { it.size })
                var offset = 0
                chunks.forEach { chunk ->
                    chunk.copyInto(result, offset)
                    offset += chunk.size
                }
                return result
            } finally {
                decoder.close()
            }
        }
    }

    private fun musicLikeSignal(frames: Int): ShortArray {
        var noiseState = 0x13579bdf

        fun nextNoise(): Int {
            noiseState = noiseState * 1_103_515_245 + 12_345
            return ((noiseState ushr 16) and 0x7fff) - 16_384
        }
        return ShortArray(frames * LIVE_AUDIO_CHANNELS) { index ->
            val frame = index / LIVE_AUDIO_CHANNELS
            val channel = index % LIVE_AUDIO_CHANNELS
            val t = frame.toDouble() / LIVE_AUDIO_SAMPLE_RATE_HZ
            val tone = if (channel == 0) {
                5_000.0 * sin(2.0 * PI * 440.0 * t) +
                    3_000.0 * sin(2.0 * PI * 1_700.0 * t) +
                    1_500.0 * sin(2.0 * PI * 6_100.0 * t)
            } else {
                4_200.0 * sin(2.0 * PI * 330.0 * t) +
                    2_800.0 * sin(2.0 * PI * 2_300.0 * t) +
                    1_300.0 * sin(2.0 * PI * 7_100.0 * t)
            }
            (tone + nextNoise() * 0.16).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun alignedRelativeError(reference: ShortArray, decoded: ShortArray): Double {
        val referenceFrames = reference.size / LIVE_AUDIO_CHANNELS
        val decodedFrames = decoded.size / LIVE_AUDIO_CHANNELS
        val commonFrames = minOf(referenceFrames, decodedFrames)
        val coarseBest = (-MAX_ALIGNMENT_FRAMES..MAX_ALIGNMENT_FRAMES step COARSE_ALIGNMENT_STEP)
            .maxByOrNull { shift -> correlation(reference, decoded, commonFrames, shift, CORRELATION_SAMPLE_STEP) }
            ?: 0
        val bestShift = (coarseBest - COARSE_ALIGNMENT_STEP..coarseBest + COARSE_ALIGNMENT_STEP)
            .maxByOrNull { shift -> correlation(reference, decoded, commonFrames, shift, 1) }
            ?: coarseBest
        val from = maxOf(ANALYSIS_START_FRAME, -bestShift)
        val until = minOf(commonFrames - ANALYSIS_END_MARGIN_FRAMES, commonFrames - bestShift - ANALYSIS_END_MARGIN_FRAMES)
        var referenceEnergy = 0.0
        var errorEnergy = 0.0
        var sampleCount = 0
        for (frame in from until until) {
            val decodedFrame = frame + bestShift
            for (channel in 0 until LIVE_AUDIO_CHANNELS) {
                val expected = reference[frame * LIVE_AUDIO_CHANNELS + channel].toDouble()
                val actual = decoded[decodedFrame * LIVE_AUDIO_CHANNELS + channel].toDouble()
                referenceEnergy += expected * expected
                val error = expected - actual
                errorEnergy += error * error
                sampleCount++
            }
        }
        return kotlin.math.sqrt(errorEnergy / sampleCount / (referenceEnergy / sampleCount))
    }

    private fun correlation(
        reference: ShortArray,
        decoded: ShortArray,
        commonFrames: Int,
        shift: Int,
        sampleStep: Int,
    ): Double {
        val from = maxOf(ANALYSIS_START_FRAME, -shift)
        val until = minOf(commonFrames - ANALYSIS_END_MARGIN_FRAMES, commonFrames - shift - ANALYSIS_END_MARGIN_FRAMES)
        var cross = 0.0
        var referenceEnergy = 0.0
        var decodedEnergy = 0.0
        for (frame in from until until step sampleStep) {
            val referenceMono = reference[frame * 2].toDouble() + reference[frame * 2 + 1]
            val decodedFrame = frame + shift
            val decodedMono = decoded[decodedFrame * 2].toDouble() + decoded[decodedFrame * 2 + 1]
            cross += referenceMono * decodedMono
            referenceEnergy += referenceMono * referenceMono
            decodedEnergy += decodedMono * decodedMono
        }
        return cross / kotlin.math.sqrt(referenceEnergy * decodedEnergy)
    }

    private fun toneProjection(chunks: List<ShortArray>, frequencyHz: Double, totalFrames: Int): Double {
        // Skip the Opus encoder's leading look-ahead, then analyze exactly 100ms. Both test tones
        // complete an integer number of cycles in that window, so a surviving component has a
        // strong projection while leakage from the other frequency stays small.
        val startFrame = TONE_ANALYSIS_START_FRAME
        val endFrame = minOf(totalFrames, startFrame + ANALYSIS_WINDOW_FRAMES)
        var real = 0.0
        var imaginary = 0.0
        var globalFrame = 0
        val angularStep = 2.0 * PI * frequencyHz / LIVE_AUDIO_SAMPLE_RATE_HZ
        for (chunk in chunks) {
            for (frame in 0 until chunk.size / LIVE_AUDIO_CHANNELS) {
                if (globalFrame in startFrame until endFrame) {
                    val mono = (chunk[frame * 2].toInt() + chunk[frame * 2 + 1].toInt()) / 2.0
                    val phase = angularStep * globalFrame
                    real += mono * cos(phase)
                    imaginary += mono * sin(phase)
                }
                globalFrame++
            }
        }
        return hypot(real, imaginary)
    }

    private fun sine(frequencyHz: Double, frames: Int, amplitude: Int): ShortArray =
        ShortArray(frames * LIVE_AUDIO_CHANNELS) { index ->
            val frame = index / LIVE_AUDIO_CHANNELS
            (sin(2.0 * PI * frequencyHz * frame / LIVE_AUDIO_SAMPLE_RATE_HZ) * amplitude).toInt().toShort()
        }

    private companion object {
        const val BASELINE_BITRATE_BPS = 96_000L
        const val SOURCE_FRAMES = 48_000
        const val MAX_ALIGNMENT_FRAMES = 1_200
        const val COARSE_ALIGNMENT_STEP = 24
        const val CORRELATION_SAMPLE_STEP = 12
        const val ANALYSIS_START_FRAME = 2_000
        const val ANALYSIS_END_MARGIN_FRAMES = 2_000
        const val TONE_ANALYSIS_START_FRAME = 1_000
        const val ANALYSIS_WINDOW_FRAMES = 4_800
        const val MIN_TONE_PROJECTION = 5_000_000.0
    }
}
