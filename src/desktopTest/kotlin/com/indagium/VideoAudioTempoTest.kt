package com.indagium

import com.indagium.video.atempoChain
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.Frame
import java.nio.ShortBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val STAGE_RANGE = 0.5..2.0
private const val PRODUCT_TOLERANCE = 1e-5

/**
 * [atempoChain] builds the FFmpeg filter description that keeps video audio audible, pitch-preserved,
 * at any playback rate. Each `atempo` stage must stay inside FFmpeg's supported [0.5, 2.0] range and
 * the stage tempi must multiply out to the requested rate. The last test drives the real
 * FFmpegFrameFilter with the same configuration the controller uses.
 */
class VideoAudioTempoTest {
    private fun stages(rate: Float): List<Double> {
        val chain = atempoChain(rate)
        return chain.split(",").map { stage ->
            assertTrue(stage.startsWith("atempo="), "unexpected stage '$stage' in '$chain'")
            stage.removePrefix("atempo=").toDouble()
        }
    }

    private fun assertValidChain(rate: Float) {
        val s = stages(rate)
        s.forEach { assertTrue(it in STAGE_RANGE, "stage $it out of range for rate $rate: ${atempoChain(rate)}") }
        val product = s.fold(1.0) { acc, v -> acc * v }
        assertTrue(abs(product - rate) <= rate * PRODUCT_TOLERANCE, "product $product != $rate for ${atempoChain(rate)}")
    }

    @Test
    fun exactChainsForCommonRates() {
        assertEquals("atempo=1.0", atempoChain(1f))
        assertEquals("atempo=0.5", atempoChain(0.5f))
        assertEquals("atempo=1.5", atempoChain(1.5f))
        assertEquals("atempo=2.0", atempoChain(2f))
        assertEquals("atempo=0.5,atempo=0.5", atempoChain(0.25f))
        assertEquals("atempo=2.0,atempo=2.0", atempoChain(4f))
    }

    @Test
    fun everyStageIsInRangeAndStagesMultiplyToTheRate() {
        listOf(0.1f, 0.25f, 0.3f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f, 5.5f, 8f).forEach(::assertValidChain)
    }

    @Test
    fun extremeRatesChainMultipleStages() {
        assertEquals(4, stages(0.1f).size)
        assertEquals(3, stages(8f).size)
    }

    @Test
    fun invalidRatesFallBackToIdentity() {
        assertEquals("atempo=1.0", atempoChain(0f))
        assertEquals("atempo=1.0", atempoChain(-2f))
        assertEquals("atempo=1.0", atempoChain(Float.NaN))
        assertEquals("atempo=1.0", atempoChain(Float.POSITIVE_INFINITY))
    }

    @Test
    fun formattingIsLocaleIndependent() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("atempo=1.5", atempoChain(1.5f))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun realFilterScalesDurationAndKeepsS16Interleaved() {
        val sampleRate = 48_000
        val channels = 2
        for ((rate, expectedRatio) in listOf(2f to 0.5, 0.5f to 2.0, 1.5f to 1.0 / 1.5)) {
            val filter = FFmpegFrameFilter("${atempoChain(rate)},aformat=sample_fmts=s16", channels).apply {
                sampleFormat = avutil.AV_SAMPLE_FMT_S16
                this.sampleRate = sampleRate
                start()
            }
            try {
                val framesPerChunk = 1024
                val chunks = 200
                var inputFrames = 0L
                var outputFrames = 0L
                val buffer = ShortBuffer.allocate(framesPerChunk * channels)
                repeat(chunks) {
                    buffer.clear()
                    repeat(framesPerChunk * channels) { i -> buffer.put((i % 100).toShort()) }
                    buffer.flip()
                    val frame = Frame().apply {
                        this.sampleRate = sampleRate
                        audioChannels = channels
                        samples = arrayOf(buffer)
                    }
                    filter.push(frame)
                    inputFrames += framesPerChunk
                    var out = filter.pull()
                    while (out != null) {
                        assertEquals(sampleRate, out.sampleRate)
                        assertEquals(channels, out.audioChannels)
                        val samples = out.samples?.getOrNull(0)
                        assertTrue(samples is ShortBuffer, "output must be S16 interleaved")
                        outputFrames += samples.limit() / channels
                        out = filter.pull()
                    }
                }
                val ratio = outputFrames.toDouble() / inputFrames
                // atempo holds back a small latency window, so allow a few percent of slack.
                assertTrue(abs(ratio - expectedRatio) < expectedRatio * 0.06, "rate $rate: ratio $ratio vs $expectedRatio")
            } finally {
                runCatching { filter.stop() }
                runCatching { filter.release() }
            }
        }
    }
}
