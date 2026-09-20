package com.indagium.capture

import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_PCM_S16LE
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val TEST_FRAME_RATE = 10.0
private const val TEST_SAMPLE_RATE = 8_000
private const val TEST_FRAME_COUNT = 40
private const val TEST_SAMPLES_PER_FRAME = TEST_SAMPLE_RATE / TEST_FRAME_RATE.toInt()

@Suppress("MagicNumber")
class FfmpegCaptureVideoExporterTest {
    @Test
    fun remuxesFromPrecedingKeyframeAndPreservesAudioAndRotation() {
        val source = syntheticCapture()
        val before = sha256(source)
        val sourceCodecs = codecs(source)
        val destination = tempFile("clip", ".mkv")

        val clip = FfmpegCaptureVideoExporter().export(source, destination, 1_500, 3_250)

        assertTrue(clip.actualStartMs in 0..1_500, "clip must begin on a keyframe preceding the request: $clip")
        assertTrue(clip.coveredEndMs in 3_100..3_250, "expected a complete frame before 3250ms, got $clip")
        assertEquals(clip.coveredEndMs - clip.actualStartMs, clip.durationMs)
        assertContentEquals(before, sha256(source), "export must never modify or restart the capture source")

        FFmpegFrameGrabber(destination).use { grabber ->
            grabber.start()
            assertTrue(grabber.videoStream >= 0, "export should retain video")
            assertTrue(grabber.audioStream >= 0, "export should retain audio")
            assertEquals(sourceCodecs.first, grabber.videoCodec, "video codec and extradata should be stream-copied")
            assertEquals(sourceCodecs.second, grabber.audioCodec, "audio codec should be stream-copied")
            assertTrue(grabber.grabImage() != null, "export should decode a real video frame")
            assertTrue(abs(abs(grabber.displayRotation) - 90.0) < 0.1, "display matrix should survive remux")
        }
    }

    @Test
    fun exportsAPlayableClipFromAMissingTrailerAndTruncatedPacketTail() {
        val complete = syntheticCapture()
        val bytes = complete.readBytes()
        val source = tempFile("truncated-capture", ".mkv").apply {
            writeBytes(bytes.copyOf(bytes.size - minOf(2_048, bytes.size / 10)))
        }
        val destination = tempFile("truncated-clip", ".mkv")

        val clip = FfmpegCaptureVideoExporter().export(source, destination, 0, 10_000)

        assertEquals(0L, clip.actualStartMs)
        assertTrue(clip.coveredEndMs > 2_500, "the committed prefix should retain most video: $clip")
        assertTrue(clip.coveredEndMs < 4_100)
        FFmpegFrameGrabber(destination).use { grabber ->
            grabber.start()
            assertTrue(grabber.grabImage() != null, "finalized export from truncated input must be playable")
        }
    }

    @Test
    fun fromStartUsesAndReportsTheFirstKeyframeWhenVideoBeginsAfterZero() {
        val source = syntheticCapture(videoStartOffsetUs = 500_000L)
        val destination = tempFile("offset-clip", ".mkv")

        val clip = FfmpegCaptureVideoExporter().export(source, destination, 0, 1_500)

        assertTrue(clip.actualStartMs in 400..600, "actual start should expose the source video offset: $clip")
        assertTrue(clip.coveredEndMs > clip.actualStartMs)
        FFmpegFrameGrabber(destination).use { grabber ->
            grabber.start()
            assertTrue(grabber.grabImage() != null)
        }
    }

    @Test
    fun snapshotsOnePrefixWhileTheCaptureContinuesAppending() {
        val completeBytes = syntheticCapture().readBytes()
        val initialLength = completeBytes.size * 4 / 5
        val source = tempFile("growing-capture", ".mkv").apply {
            writeBytes(completeBytes.copyOf(initialLength))
        }
        val destination = tempFile("growing-clip", ".mkv")
        val appender = thread(name = "capture-test-appender") {
            RandomAccessFile(source, "rw").use { file ->
                file.seek(initialLength.toLong())
                var offset = initialLength
                while (offset < completeBytes.size) {
                    val count = minOf(97, completeBytes.size - offset)
                    file.write(completeBytes, offset, count)
                    offset += count
                    Thread.sleep(1)
                }
            }
        }

        val clip = FfmpegCaptureVideoExporter().export(source, destination, 0, 10_000)
        appender.join(10_000)

        assertTrue(!appender.isAlive, "fixture append should finish")
        assertContentEquals(completeBytes, source.readBytes(), "export must not interfere with the writer")
        assertTrue(clip.coveredEndMs > 2_000)
        assertTrue(clip.coveredEndMs <= 4_000)
        FFmpegFrameGrabber(destination).use { grabber ->
            grabber.start()
            assertTrue(grabber.grabImage() != null)
        }
    }

    @Test
    fun rejectsARealAudioOnlyContainerAsHavingNoReadableVideo() {
        val source = syntheticAudioOnlyCapture()
        val failure = assertFailsWith<IOException> {
            FfmpegCaptureVideoExporter().export(source, tempFile("no-video", ".mkv"), 0, 1_000)
        }
        assertTrue(failure.message.orEmpty().contains("no video stream", ignoreCase = true))
    }

    @Test
    fun interruptedExportCleansItsStagingFiles() {
        val directory = Files.createTempDirectory("indagium-interrupted-export").toFile()
        val source = File(directory, "source.mkv").apply { writeBytes(syntheticCapture().readBytes()) }
        val destination = File(directory, "clip.mkv")

        Thread.currentThread().interrupt()
        try {
            assertFailsWith<IOException> {
                FfmpegCaptureVideoExporter().export(source, destination, 0, 1_000)
            }
        } finally {
            Thread.interrupted()
        }

        assertTrue(!destination.exists())
        assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith(".capture-") })
    }

    private fun syntheticCapture(videoStartOffsetUs: Long = 0L): File {
        val output = tempFile("synthetic-capture", ".mkv")
        val width = 64
        val height = 48
        val pixels = ByteBuffer.allocate(width * height * 3)
        val recorder = FFmpegFrameRecorder(output, width, height, 1).apply {
            format = "matroska"
            frameRate = TEST_FRAME_RATE
            gopSize = TEST_FRAME_RATE.toInt()
            videoCodec = AV_CODEC_ID_MPEG4
            videoBitrate = 300_000
            sampleRate = TEST_SAMPLE_RATE
            audioCodec = AV_CODEC_ID_PCM_S16LE
            setDisplayRotation(90.0)
            setVideoMetadata("capture-test", "orientation-and-audio")
        }
        try {
            recorder.start()
            if (videoStartOffsetUs > 0L) recorder.timestamp = videoStartOffsetUs
            repeat(TEST_FRAME_COUNT) { frameIndex ->
                pixels.clear()
                repeat(width * height) {
                    pixels.put((frameIndex * 5 % 255).toByte())
                    pixels.put((frameIndex * 11 % 255).toByte())
                    pixels.put((frameIndex * 17 % 255).toByte())
                }
                pixels.flip()
                recorder.recordImage(width, height, 8, 3, width * 3, AV_PIX_FMT_BGR24, pixels)
                val samples = ShortArray(TEST_SAMPLES_PER_FRAME) { sampleIndex ->
                    val position = frameIndex * TEST_SAMPLES_PER_FRAME + sampleIndex
                    (sin(position * 2.0 * Math.PI * 440.0 / TEST_SAMPLE_RATE) * Short.MAX_VALUE / 8).toInt().toShort()
                }
                recorder.recordSamples(TEST_SAMPLE_RATE, 1, ShortBuffer.wrap(samples))
            }
            recorder.stop()
        } finally {
            runCatching { recorder.release() }
        }
        return output
    }

    private fun syntheticAudioOnlyCapture(): File {
        val output = tempFile("synthetic-audio", ".mkv")
        val recorder = FFmpegFrameRecorder(output, 1).apply {
            format = "matroska"
            sampleRate = TEST_SAMPLE_RATE
            audioCodec = AV_CODEC_ID_PCM_S16LE
        }
        try {
            recorder.start()
            recorder.recordSamples(TEST_SAMPLE_RATE, 1, ShortBuffer.wrap(ShortArray(TEST_SAMPLE_RATE)))
            recorder.stop()
        } finally {
            runCatching { recorder.release() }
        }
        return output
    }

    private fun tempFile(prefix: String, suffix: String): File =
        Files.createTempFile("indagium-$prefix-", suffix).toFile().apply { deleteOnExit() }

    private fun sha256(file: File): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    private fun codecs(file: File): Pair<Int, Int> = FFmpegFrameGrabber(file).use { grabber ->
        grabber.start()
        grabber.videoCodec to grabber.audioCodec
    }
}
