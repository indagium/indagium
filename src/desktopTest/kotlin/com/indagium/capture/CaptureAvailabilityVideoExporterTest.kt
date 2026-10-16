package com.indagium.capture

import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_PCM_S16LE
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.FrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.Color
import java.io.File
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Suppress("MagicNumber")
class CaptureAvailabilityVideoExporterTest {
    @Test
    fun middleGapRendersCardKeepsElapsedDurationAndPreservesMicrophoneAudio() {
        val source = syntheticCapture()
        val destination = tempFile("middle-gap", ".mkv")
        val gap = CaptureAvailabilityInterval(
            startElapsedMs = 1_000,
            endElapsedMs = 2_000,
            bootIdBefore = "boot-before",
            bootIdAfter = "boot-after",
            rebootConfirmed = true,
        )

        val clip = FfmpegCaptureVideoExporter().exportWithAvailability(
            source = source,
            destination = destination,
            timelineStartElapsedMs = 0,
            timelineEndElapsedMs = 4_000,
            sourceElapsedOriginMs = 0,
            availabilityIntervals = listOf(gap),
            sourceIsFinal = true,
        )

        assertEquals(CaptureVideoClip(0, 4_000, 4_000), clip)
        assertTrue(destination.length() > 0L)
        assertCardAt(destination, 1_500)
        assertSourceFrameAt(destination, 2_500)
        assertVideoDurationWithinOneFrame(destination, expectedDurationMs = 4_000)
        assertAudioPresentThroughGap(destination, 1_400..1_700)
        FFmpegFrameGrabber(destination).use { grabber ->
            grabber.start()
            assertTrue(abs(abs(grabber.displayRotation) - 90.0) < 0.1, "rendered export should retain source rotation")
        }
    }

    @Test
    fun leadingOfflineRangeUsesSessionTimeEvenWhenSourcePtsZeroStartsLater() {
        val source = syntheticCapture()
        val destination = tempFile("leading-gap", ".mkv")

        val clip = FfmpegCaptureVideoExporter().exportWithAvailability(
            source = source,
            destination = destination,
            timelineStartElapsedMs = 0,
            timelineEndElapsedMs = 3_000,
            sourceElapsedOriginMs = 1_000,
            availabilityIntervals = listOf(CaptureAvailabilityInterval(0, 1_000, rebootConfirmed = null)),
            sourceIsFinal = true,
        )

        // `actualStartMs` is source-equivalent for archive sync math and can be negative because
        // output PTS zero represents the leading generated card, before source frame PTS zero.
        assertEquals(-1_000L, clip.actualStartMs)
        assertEquals(2_000L, clip.coveredEndMs)
        assertEquals(3_000L, clip.durationMs)
        assertCardAt(destination, 500)
        assertVideoDurationWithinOneFrame(destination, expectedDurationMs = 3_000)
    }

    @Test
    fun entirelyOfflineRangeExportsWithoutAnySourceVideoPackets() {
        val destination = tempFile("all-offline", ".mkv")

        val clip = FfmpegCaptureVideoExporter().exportWithAvailability(
            source = null,
            destination = destination,
            timelineStartElapsedMs = 250,
            timelineEndElapsedMs = 2_750,
            sourceElapsedOriginMs = 0,
            availabilityIntervals = listOf(
                CaptureAvailabilityInterval(0, null, rebootConfirmed = null),
            ),
        )

        assertEquals(CaptureVideoClip(250, 2_750, 2_500), clip)
        assertTrue(destination.length() > 0L)
        assertCardAt(destination, 1_250)
        assertVideoDurationWithinOneFrame(destination, expectedDurationMs = 2_500)
    }

    @Test
    fun denseSourceFramesAreCoalescedWithoutStretchingTheSelectedTimeline() {
        val source = syntheticCapture(frameRate = 60.0, withAudio = false)
        val destination = tempFile("dense-middle-gap", ".mkv")

        FfmpegCaptureVideoExporter().exportWithAvailability(
            source = source,
            destination = destination,
            timelineStartElapsedMs = 0,
            timelineEndElapsedMs = 4_000,
            sourceElapsedOriginMs = 0,
            availabilityIntervals = listOf(CaptureAvailabilityInterval(1_000, 2_000)),
            sourceIsFinal = true,
        )

        assertVideoDurationWithinOneFrame(destination, expectedDurationMs = 4_000)
    }

    @Test
    fun wholeOfflineRangeKeepsMixedAudioWhileEveryVideoFrameIsReplacedByTheCard() {
        val source = syntheticCapture()
        val destination = tempFile("all-offline-with-audio", ".mkv")

        FfmpegCaptureVideoExporter().exportWithAvailability(
            source = source,
            destination = destination,
            timelineStartElapsedMs = 0,
            timelineEndElapsedMs = 4_000,
            sourceElapsedOriginMs = 0,
            availabilityIntervals = listOf(CaptureAvailabilityInterval(0, 4_000)),
            sourceIsFinal = true,
        )

        assertCardAt(destination, 2_000)
        assertVideoDurationWithinOneFrame(destination, expectedDurationMs = 4_000)
        assertAudioPresentThroughGap(destination, 1_400..1_700)
    }

    @Test
    fun cardRemainsVisibleThroughOnlineTailUntilARealPostReconnectFrameArrives() {
        val source = syntheticCapture(frameRate = 10.0, withAudio = false, durationSeconds = 1.0)
        val destination = tempFile("unresumed-tail", ".mkv")

        FfmpegCaptureVideoExporter().exportWithAvailability(
            source = source,
            destination = destination,
            timelineStartElapsedMs = 0,
            timelineEndElapsedMs = 4_000,
            sourceElapsedOriginMs = 0,
            availabilityIntervals = listOf(CaptureAvailabilityInterval(500, 1_000)),
            sourceIsFinal = true,
        )

        assertCardAt(destination, 3_900)
        assertVideoDurationWithinOneFrame(destination, expectedDurationMs = 4_000)
    }

    private fun assertCardAt(file: File, targetMs: Long) {
        val converter = Java2DFrameConverter()
        try {
            FFmpegFrameGrabber(file).use { grabber ->
                grabber.start()
                var image = grabber.grabImage()
                while (image != null && image.timestamp < targetMs * 1_000L) image = grabber.grabImage()
                assertNotNull(image, "expected a frame around ${targetMs}ms")
                val bitmap = converter.convert(image)
                assertNotNull(bitmap)
                val corner = Color(bitmap.getRGB(2, 2))
                assertTrue(
                    corner.red < 60 && corner.green < 70 && corner.blue < 80,
                    "offline frame should have the generated dark card background, got $corner",
                )
            }
        } finally {
            converter.close()
        }
    }

    private fun assertVideoDurationWithinOneFrame(file: File, expectedDurationMs: Long) {
        FFmpegFrameGrabber(file).use { grabber ->
            grabber.start()
            var previousTimestampUs = -1L
            var lastTimestampUs = -1L
            while (true) {
                val frame = grabber.grabImage() ?: break
                assertTrue(
                    frame.timestamp > previousTimestampUs,
                    "video timestamps must be strictly increasing: $previousTimestampUs then ${frame.timestamp}",
                )
                previousTimestampUs = frame.timestamp
                lastTimestampUs = frame.timestamp
            }
            assertTrue(lastTimestampUs >= 0L, "expected at least one rendered image")
            // The final frame occupies one 30fps tick. Its PTS is therefore expected to fall
            // within one tick of the selected duration even when 60fps source frames collided
            // with generated card ticks and were coalesced.
            val physicalDurationMs = lastTimestampUs / 1_000L + 33L
            assertTrue(
                kotlin.math.abs(physicalDurationMs - expectedDurationMs) <= 34L,
                "expected physical video duration within one frame of ${expectedDurationMs}ms, got ${physicalDurationMs}ms",
            )
        }
    }

    private fun assertSourceFrameAt(file: File, targetMs: Long) {
        val converter = Java2DFrameConverter()
        try {
            FFmpegFrameGrabber(file).use { grabber ->
                grabber.start()
                var image = grabber.grabImage()
                while (image != null && image.timestamp < targetMs * 1_000L) image = grabber.grabImage()
                assertNotNull(image, "expected a resumed source frame around ${targetMs}ms")
                val bitmap = converter.convert(image)
                assertNotNull(bitmap)
                val corner = Color(bitmap.getRGB(2, 2))
                assertTrue(corner.red > 100, "the first real post-reconnect frame should replace the card, got $corner")
            }
        } finally {
            converter.close()
        }
    }

    private fun assertAudioPresentThroughGap(file: File, expectedMs: IntRange) {
        FFmpegFrameGrabber(file).use { grabber ->
            grabber.sampleMode = FrameGrabber.SampleMode.SHORT
            grabber.start()
            assertTrue(grabber.audioStream >= 0, "gap export should keep the microphone track")
            var foundSignal = false
            var lastTimestampMs = 0L
            while (true) {
                val frame = grabber.grabSamples() ?: break
                lastTimestampMs = frame.timestamp / 1_000L
                if (lastTimestampMs in expectedMs) {
                    val samples = frame.samples?.firstOrNull() as? ShortBuffer ?: continue
                    val duplicate = samples.duplicate()
                    var absoluteTotal = 0L
                    var count = 0
                    while (duplicate.hasRemaining()) {
                        absoluteTotal += abs(duplicate.get().toInt())
                        count++
                    }
                    if (count > 0 && absoluteTotal / count > 500L) foundSignal = true
                }
            }
            assertTrue(foundSignal, "the host microphone signal should remain audible during the offline card")
            assertTrue(lastTimestampMs >= 3_900L, "audio should continue through the end of the selected range")
        }
    }

    private fun syntheticCapture(
        frameRate: Double = 10.0,
        withAudio: Boolean = true,
        durationSeconds: Double = 4.0,
    ): File {
        val output = tempFile("availability-source", ".mkv")
        val width = 64
        val height = 48
        val sampleRate = 48_000
        val audioChannels = 2
        val videoFrames = (durationSeconds * frameRate).toInt()
        val samplesPerFrame = (sampleRate / frameRate).toInt().coerceAtLeast(1)
        val pixels = ByteBuffer.allocate(width * height * 3)
        val recorder = FFmpegFrameRecorder(output, width, height, if (withAudio) audioChannels else 0).apply {
            format = "matroska"
            this.frameRate = frameRate
            videoCodec = AV_CODEC_ID_MPEG4
            videoBitrate = 300_000
            setDisplayRotation(90.0)
            if (withAudio) {
                audioCodec = AV_CODEC_ID_PCM_S16LE
                this.sampleRate = sampleRate
            }
        }
        try {
            recorder.start()
            repeat(videoFrames) { frameIndex ->
                pixels.clear()
                repeat(width * height) {
                    pixels.put(0x20.toByte())
                    pixels.put(0x70.toByte())
                    pixels.put(0xD0.toByte())
                }
                pixels.flip()
                recorder.recordImage(width, height, 8, 3, width * 3, AV_PIX_FMT_BGR24, pixels)
                // A strong, constant microphone signal makes a lost/collapsed audio gap visible in
                // the decoded PCM without relying on a particular lossy encoder's exact samples.
                if (withAudio) {
                    recorder.recordSamples(
                        sampleRate,
                        audioChannels,
                        ShortBuffer.wrap(ShortArray(samplesPerFrame * audioChannels) { 8_000 }),
                    )
                }
            }
            recorder.stop()
        } finally {
            runCatching { recorder.release() }
        }
        return output
    }

    private fun tempFile(prefix: String, suffix: String): File =
        Files.createTempFile("indagium-$prefix-", suffix).toFile().apply { deleteOnExit() }
}
