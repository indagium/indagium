package com.indagium

import com.indagium.video.audioFrameTooFarAhead
import com.indagium.video.defaultVideoPlayerController
import com.indagium.video.playbackClockUs
import com.indagium.video.shouldAnchorClockOnFrame
import com.indagium.video.videoPlayerControllerWithGrabHook
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_PCM_S16LE
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regression coverage for held images and the transport clock across sparse video PTS intervals. */
@Suppress("MagicNumber")
class VideoSparseSeekTest {
    @Test
    fun seekQueuedBeforeStartOwnsTheFirstPreview() {
        val controller = defaultVideoPlayerController(sparseTimestampVideo().absolutePath)
        try {
            controller.seek(800L)
            controller.start()
            await("first held seek preview") { isGreen(controller.grabCurrentFrame()) }
            assertEquals(800L, controller.positionMs)
        } finally {
            controller.close()
        }
    }

    @Test
    fun seekPreviewAndPlaybackHoldThePreviousImageAcrossConsecutiveSparseIntervals() {
        val source = sparseTimestampVideo()
        val controller = defaultVideoPlayerController(source.absolutePath)
        try {
            controller.start()
            await("initial video frame") { controller.grabCurrentFrame() != null }

            // The next packet is at 1.5s, but a seek to 0.8s is inside the long held interval
            // following the 0.1s frame. The preview must keep the held image and requested time.
            controller.seek(800L)
            await("held seek preview") { isGreen(controller.grabCurrentFrame()) }
            assertEquals(800L, controller.positionMs, "a future preview frame must not move the playhead")

            // Headless notes/MCP grabs should resolve the same requested moment, not snap to 1.5s.
            assertTrue(isGreen(controller.grabFrameAt(800L)), "headless capture should use the held image")

            controller.play()
            Thread.sleep(250L)
            assertTrue(controller.positionMs in 800L..1_300L, "the clock should advance through the first sparse interval")
            assertTrue(isGreen(controller.grabCurrentFrame()), "the 1.5s frame must not display before its PTS")
            await("first resumed frame") { isBlue(controller.grabCurrentFrame()) }
            assertTrue(controller.positionMs >= 1_500L)

            controller.pause()
            controller.seek(2_000L)
            await("second held seek preview") { isCyan(controller.grabCurrentFrame()) }
            assertEquals(2_000L, controller.positionMs, "a second sparse interval must keep its requested time")

            controller.play()
            Thread.sleep(250L)
            assertTrue(controller.positionMs in 2_000L..2_500L)
            assertTrue(isCyan(controller.grabCurrentFrame()), "the 3.0s frame must not display early")
            await("second resumed frame") { isMagenta(controller.grabCurrentFrame()) }
            assertTrue(controller.positionMs >= 3_000L)
        } finally {
            controller.close()
        }
    }

    @Test
    fun futureAudioIsDeferredOnlyBeyondTheExistingReadAheadHorizon() {
        assertTrue(audioFrameTooFarAhead(frameTimestampUs = 2_000_001L, playbackClockUs = 1_500_000L))
        assertFalse(audioFrameTooFarAhead(frameTimestampUs = 2_000_000L, playbackClockUs = 1_500_000L))
        assertFalse(audioFrameTooFarAhead(frameTimestampUs = 1_400_000L, playbackClockUs = 1_500_000L))
    }

    // The recording below has one keyframe at 0 s, so after any seek JavaCV's setTimestamp (which
    // gives up after 1000 grabs) lands far short of the target and playback must decode forward for
    // seconds. The grab hook makes each of those grabs take ~4 ms so the catch-up is long and the
    // test deterministic. The playback clock must not run during it: with the old behaviour the
    // first frames at the target arrived >1 s late, were dropped, and the picture then stayed on
    // colour A while the position advanced past the change.
    @Test
    fun playAfterPausedSeekOnLongGopShowsTheNewPictureWhenThePositionReachesIt() {
        val controller = videoPlayerControllerWithGrabHook(longGopVideo().absolutePath, ::slowGrab)
        try {
            controller.start()
            await("initial frame") { controller.grabCurrentFrame() != null }
            controller.seek(LONG_GOP_SEEK_MS)
            await("paused seek preview") { isRed(controller.grabCurrentFrame()) }
            assertEquals(LONG_GOP_SEEK_MS, controller.positionMs)

            controller.play()
            assertNewPictureShownOncePositionPassesTheChange(controller)
        } finally {
            controller.close()
        }
    }

    @Test
    fun seekWhilePlayingOnLongGopShowsTheNewPictureWhenThePositionReachesIt() {
        val controller = videoPlayerControllerWithGrabHook(longGopVideo().absolutePath, ::slowGrab)
        try {
            controller.start()
            await("initial frame") { controller.grabCurrentFrame() != null }
            controller.play()
            await("playback running") { controller.positionMs >= 200L }

            controller.seek(LONG_GOP_SEEK_MS)
            assertNewPictureShownOncePositionPassesTheChange(controller)
        } finally {
            controller.close()
        }
    }

    private fun assertNewPictureShownOncePositionPassesTheChange(controller: com.indagium.video.VideoPlayerController) {
        await("position past the colour change", timeoutMs = 30_000L) {
            controller.positionMs >= LONG_GOP_CHANGE_MS + LONG_GOP_TOLERANCE_MS
        }
        // The slider has passed the change by the tolerance, so the picture must have followed.
        await("new picture", timeoutMs = 500L) { isBlue(controller.grabCurrentFrame()) }
    }

    private fun slowGrab() {
        // Spin rather than park/sleep: play()/seek() interrupt the decode thread, and a pending
        // interrupt makes parkNanos return immediately.
        val end = System.nanoTime() + GRAB_DELAY_NS
        while (System.nanoTime() < end) Thread.onSpinWait()
    }

    @Test
    fun clockStaysFrozenUntilTheDecoderReachesTheSeekTarget() {
        assertEquals(
            5_000_000L,
            playbackClockUs(true, positionUs = 5_000_000L, startNanos = 0L, startTimestampUs = 1L, nowNanos = 9_000_000_000L, rate = 1f),
        )
        assertEquals(
            3_000_000L,
            playbackClockUs(false, positionUs = 5_000_000L, startNanos = 1_000_000_000L, startTimestampUs = 1_000_000L, nowNanos = 3_000_000_000L, rate = 1f),
        )
        assertEquals(
            5_000_000L,
            playbackClockUs(false, positionUs = 0L, startNanos = 0L, startTimestampUs = 1_000_000L, nowNanos = 2_000_000_000L, rate = 2f),
        )
    }

    @Test
    fun clockAnchorsOnTheFirstFrameAtOrAfterTheTargetOnly() {
        assertFalse(shouldAnchorClockOnFrame(clockAnchorPending = true, frameTimestampUs = 999_999L, minimumPlaybackTimestampUs = 1_000_000L))
        assertTrue(shouldAnchorClockOnFrame(clockAnchorPending = true, frameTimestampUs = 1_000_000L, minimumPlaybackTimestampUs = 1_000_000L))
        assertTrue(shouldAnchorClockOnFrame(clockAnchorPending = true, frameTimestampUs = 2_000_000L, minimumPlaybackTimestampUs = 1_000_000L))
        assertFalse(shouldAnchorClockOnFrame(clockAnchorPending = false, frameTimestampUs = 2_000_000L, minimumPlaybackTimestampUs = 1_000_000L))
    }

    /** 30 fps red until [LONG_GOP_CHANGE_MS], blue after, with a silent audio track; one keyframe. */
    private fun longGopVideo(): java.io.File {
        val output = Files.createTempFile("indagium-long-gop", ".mkv").toFile().apply { deleteOnExit() }
        val width = 64
        val height = 48
        val fps = 30
        val sampleRate = 44_100
        val frameCount = (LONG_GOP_DURATION_MS * fps / 1_000L).toInt()
        val pixels = ByteBuffer.allocate(width * height * 3)
        val recorder = FFmpegFrameRecorder(output, width, height, 1).apply {
            format = "matroska"
            frameRate = fps.toDouble()
            videoCodec = AV_CODEC_ID_MPEG4
            videoBitrate = 500_000
            gopSize = 1_000_000
            audioCodec = AV_CODEC_ID_PCM_S16LE
            this.sampleRate = sampleRate
        }
        try {
            recorder.start()
            repeat(frameCount) { index ->
                val blue = index * 1_000L / fps >= LONG_GOP_CHANGE_MS
                pixels.clear()
                repeat(width * height) {
                    pixels.put((if (blue) 255 else 32).toByte())
                    pixels.put(32.toByte())
                    pixels.put((if (blue) 32 else 255).toByte())
                }
                pixels.flip()
                recorder.recordImage(width, height, 8, 3, width * 3, AV_PIX_FMT_BGR24, pixels)
                recorder.recordSamples(sampleRate, 1, ShortBuffer.wrap(ShortArray(sampleRate / fps)))
            }
            recorder.stop()
        } finally {
            runCatching { recorder.release() }
        }
        return output
    }

    private fun sparseTimestampVideo(): java.io.File {
        val output = Files.createTempFile("indagium-sparse-seek", ".mkv").toFile().apply { deleteOnExit() }
        val width = 64
        val height = 48
        val pixels = ByteBuffer.allocate(width * height * 3)
        val frames = listOf(
            TimedColor(0L, 255, 32, 32),
            TimedColor(100_000L, 32, 255, 32),
            TimedColor(1_500_000L, 32, 32, 255),
            TimedColor(1_600_000L, 32, 255, 255),
            TimedColor(3_000_000L, 255, 32, 255),
        )
        val recorder = FFmpegFrameRecorder(output, width, height, 0).apply {
            format = "matroska"
            frameRate = 10.0
            videoCodec = AV_CODEC_ID_MPEG4
            videoBitrate = 1_000_000
        }
        try {
            recorder.start()
            for (frame in frames) {
                pixels.clear()
                repeat(width * height) {
                    pixels.put(frame.blue.toByte())
                    pixels.put(frame.green.toByte())
                    pixels.put(frame.red.toByte())
                }
                pixels.flip()
                recorder.timestamp = frame.timestampUs
                recorder.recordImage(width, height, 8, 3, width * 3, AV_PIX_FMT_BGR24, pixels)
            }
            recorder.stop()
        } finally {
            runCatching { recorder.release() }
        }
        return output
    }

    private fun isGreen(png: ByteArray?): Boolean = png?.let { dominantChannel(it, 1) } == true

    private fun isRed(png: ByteArray?): Boolean = png?.let { dominantChannel(it, 0) } == true

    private fun isBlue(png: ByteArray?): Boolean = png?.let { dominantChannel(it, 2) } == true

    private fun isCyan(png: ByteArray?): Boolean = png?.let { pixel(it)?.let { rgb -> green(rgb) > 180 && blue(rgb) > 180 && red(rgb) < 100 } } == true

    private fun isMagenta(png: ByteArray?): Boolean = png?.let { pixel(it)?.let { rgb -> red(rgb) > 180 && blue(rgb) > 180 && green(rgb) < 100 } } == true

    private fun dominantChannel(png: ByteArray, channel: Int): Boolean {
        val rgb = pixel(png) ?: return false
        val selected = when (channel) {
            0 -> red(rgb)
            1 -> green(rgb)
            else -> blue(rgb)
        }
        val other = when (channel) {
            0 -> maxOf(green(rgb), blue(rgb))
            1 -> maxOf(red(rgb), blue(rgb))
            else -> maxOf(red(rgb), green(rgb))
        }
        return selected > 180 && selected > other + 60
    }

    private fun pixel(png: ByteArray): Int? = ImageIO.read(ByteArrayInputStream(png))?.let { image ->
        image.getRGB(image.width / 2, image.height / 2)
    }

    private fun red(rgb: Int) = (rgb ushr 16) and 0xFF

    private fun green(rgb: Int) = (rgb ushr 8) and 0xFF

    private fun blue(rgb: Int) = rgb and 0xFF

    private fun await(label: String, timeoutMs: Long = 15_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20L)
        assertTrue(condition(), "timed out waiting for $label")
    }

    private companion object {
        const val LONG_GOP_DURATION_MS = 45_000L
        const val LONG_GOP_CHANGE_MS = 40_000L
        const val LONG_GOP_SEEK_MS = 38_000L
        const val LONG_GOP_TOLERANCE_MS = 700L
        const val GRAB_DELAY_NS = 4_000_000L
    }

    private data class TimedColor(
        val timestampUs: Long,
        val red: Int,
        val green: Int,
        val blue: Int,
    )
}
