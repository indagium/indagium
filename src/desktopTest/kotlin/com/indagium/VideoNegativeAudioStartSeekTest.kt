package com.indagium

import com.indagium.video.clampSeekTargetToVideoStartUs
import com.indagium.video.defaultVideoPlayerController
import com.indagium.video.seekToTimestampUs
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_OPUS
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.junit.Assume.assumeTrue
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Seeking to 0..6 ms in a file whose first container packet is Opus audio with a negative pts.
 *
 * libopus' pre-skip makes ffmpeg-made MKV/MP4 files carry their first audio packet a few milliseconds
 * before the first video frame (-7 ms vs 0), so the container's `start_time` is negative. JavaCV's
 * `setTimestamp(t)` then aims at `t + start_time`, which for t < 7 ms is a time nothing can satisfy;
 * it burns its 1000-grab fallback and lands near the end of a short file (about 12 s into a longer one).
 * App-recorded captures start their audio at pts 0, so only imported files are exposed.
 */
@Suppress("MagicNumber")
class VideoNegativeAudioStartSeekTest {
    @Test
    fun theFixtureReallyStartsItsAudioBeforeItsVideo() {
        FFmpegFrameGrabber(fixture.absolutePath).use { grabber ->
            grabber.start()
            val format = assertNotNull(grabber.formatContext)
            assertTrue(format.start_time() < 0L, "container start_time ${format.start_time()} must be negative")
            assertEquals(0L, format.streams(grabber.videoStream).start_time(), "the video itself starts at 0")
        }
    }

    @Test
    fun theGrabberSeekHelperLandsOnTheFirstFrameForEveryTargetBeforeTheVideoStarts() {
        FFmpegFrameGrabber(fixture.absolutePath).use { grabber ->
            grabber.start()
            for (targetMs in 0L..10L) {
                grabber.seekToTimestampUs(targetMs * 1_000L)
                val image = grabber.grabImage()
                assertNotNull(image, "no image after seeking to $targetMs ms")
                assertTrue(image.timestamp < 100_000L, "seek to $targetMs ms landed at ${image.timestamp / 1_000L} ms")
            }
        }
    }

    @Test
    fun grabFrameAtTinyTargetsReturnsTheOpeningPicture() {
        val controller = defaultVideoPlayerController(fixture.absolutePath)
        try {
            for (targetMs in listOf(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 10L)) {
                assertTrue(isRed(controller.grabFrameAt(targetMs)), "grabFrameAt($targetMs) must return the opening (red) picture")
            }
        } finally {
            controller.close()
        }
    }

    @Test
    fun seekingBackToTheStartReplacesALaterPictureWithTheFirstFrame() {
        val controller = defaultVideoPlayerController(fixture.absolutePath)
        try {
            controller.start()
            await("initial frame") { controller.grabCurrentFrame() != null }

            controller.seek(3_000L)
            await("mid-file picture") { isBlue(controller.grabCurrentFrame()) }

            controller.seek(0L)
            await("first frame after seek(0)") { isRed(controller.grabCurrentFrame()) }
            assertEquals(0L, controller.positionMs)

            // A target inside the first frame interval previews the next frame, exactly like a target
            // of 10 ms always did (frame-quantised scrubbing); it must not jump to the end of the file.
            for (targetMs in listOf(3_000L, 1L, 3_000L, 5L)) {
                controller.seek(targetMs)
                if (targetMs > 100L) {
                    await("mid-file picture again") { isBlue(controller.grabCurrentFrame()) }
                } else {
                    await("opening picture after seek($targetMs)") {
                        isRed(controller.grabCurrentFrame()) && controller.positionMs == FRAME_INTERVAL_MS
                    }
                }
            }
        } finally {
            controller.close()
        }
    }

    @Test
    fun targetsAwayFromTheStartStayFrameAccurate() {
        val controller = defaultVideoPlayerController(fixture.absolutePath)
        try {
            // The picture turns blue at frame 60 (2000 ms); frame 59 sits at about 1967 ms.
            assertTrue(isRed(controller.grabFrameAt(1_000L)))
            assertTrue(isRed(controller.grabFrameAt(1_960L)), "the last red frame is the first one at/after 1960 ms")
            assertTrue(isBlue(controller.grabFrameAt(1_990L)), "the first frame at/after 1990 ms is the first blue one")
            assertTrue(isBlue(controller.grabFrameAt(3_000L)))

            controller.start()
            await("initial frame") { controller.grabCurrentFrame() != null }
            controller.seek(1_960L)
            await("last red frame") { isRed(controller.grabCurrentFrame()) && controller.positionMs in 1_960L..2_000L }
            controller.seek(1_990L)
            await("first blue frame") { isBlue(controller.grabCurrentFrame()) && controller.positionMs == 2_000L }
        } finally {
            controller.close()
        }
    }

    @Test
    fun clampOnlyRaisesTargetsThatAimBeforeTheVideosFirstFrame() {
        // Container start -7 ms (audio pre-skip), video starts at 0: targets below 7 ms are raised to 7 ms.
        assertEquals(7_000L, clampSeekTargetToVideoStartUs(0L, -7_000L, 0L))
        assertEquals(7_000L, clampSeekTargetToVideoStartUs(6_000L, -7_000L, 0L))
        assertEquals(7_000L, clampSeekTargetToVideoStartUs(7_000L, -7_000L, 0L))
        assertEquals(10_000L, clampSeekTargetToVideoStartUs(10_000L, -7_000L, 0L))
        assertEquals(5_000_000L, clampSeekTargetToVideoStartUs(5_000_000L, -7_000L, 0L))
        // Video that really has negative timestamps keeps JavaCV's own mapping.
        assertEquals(0L, clampSeekTargetToVideoStartUs(0L, -33_000L, -33_000L))
        // Non-negative container starts, missing information and audio-only files are untouched.
        assertEquals(0L, clampSeekTargetToVideoStartUs(0L, 0L, 0L))
        assertEquals(0L, clampSeekTargetToVideoStartUs(0L, 1_000_000L, 1_000_000L))
        assertEquals(0L, clampSeekTargetToVideoStartUs(0L, null, 0L))
        assertEquals(0L, clampSeekTargetToVideoStartUs(0L, -7_000L, null))
    }

    private fun isRed(png: ByteArray?): Boolean = png?.let(::pixel)?.let { red(it) > 180 && red(it) > blue(it) + 60 } == true

    private fun isBlue(png: ByteArray?): Boolean = png?.let(::pixel)?.let { blue(it) > 180 && blue(it) > red(it) + 60 } == true

    private fun pixel(png: ByteArray): Int? = ImageIO.read(ByteArrayInputStream(png))?.let { image ->
        image.getRGB(image.width / 2, image.height / 2)
    }

    private fun red(rgb: Int) = (rgb ushr 16) and 0xFF

    private fun blue(rgb: Int) = rgb and 0xFF

    private fun await(label: String, timeoutMs: Long = 15_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20L)
        assertTrue(condition(), "timed out waiting for $label")
    }

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 48
        const val FPS = 30
        const val SAMPLE_RATE = 48_000
        const val DURATION_MS = 4_000L
        const val CHANGE_MS = 2_000L
        const val FRAME_INTERVAL_MS = 33L

        /**
         * 30 fps MPEG-4 (no B-frames, one keyframe) red until [CHANGE_MS] and blue after, plus a libopus
         * track whose encoder delay makes its first packet land at pts -7 ms, as in an ffmpeg-made file.
         */
        val fixture: File by lazy {
            assumeTrue("bundled FFmpeg has no libopus encoder", avcodec_find_encoder_by_name("libopus")?.isNull == false)
            val output = Files.createTempFile("indagium-negative-audio-start", ".mkv").toFile().apply { deleteOnExit() }
            val pixels = ByteBuffer.allocate(WIDTH * HEIGHT * 3)
            val recorder = FFmpegFrameRecorder(output, WIDTH, HEIGHT, 1).apply {
                format = "matroska"
                frameRate = FPS.toDouble()
                videoCodec = AV_CODEC_ID_MPEG4
                videoBitrate = 500_000
                gopSize = 1_000_000
                audioCodec = AV_CODEC_ID_OPUS
                sampleRate = SAMPLE_RATE
            }
            try {
                recorder.start()
                repeat((DURATION_MS * FPS / 1_000L).toInt()) { index ->
                    val blue = index * 1_000L / FPS >= CHANGE_MS
                    pixels.clear()
                    repeat(WIDTH * HEIGHT) {
                        pixels.put((if (blue) 255 else 32).toByte())
                        pixels.put(32.toByte())
                        pixels.put((if (blue) 32 else 255).toByte())
                    }
                    pixels.flip()
                    recorder.recordImage(WIDTH, HEIGHT, 8, 3, WIDTH * 3, AV_PIX_FMT_BGR24, pixels)
                    recorder.recordSamples(SAMPLE_RATE, 1, ShortBuffer.wrap(ShortArray(SAMPLE_RATE / FPS)))
                }
                recorder.stop()
            } finally {
                runCatching { recorder.release() }
            }
            output
        }
    }
}
