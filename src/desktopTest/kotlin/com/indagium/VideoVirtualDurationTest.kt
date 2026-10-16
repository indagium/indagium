package com.indagium

import com.indagium.video.defaultVideoPlayerController
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Covers playback past the physical media EOF for an archive with a longer capture timeline. */
class VideoVirtualDurationTest {
    @Test
    fun seekAndPlaybackCanReachTheVirtualCaptureEndAfterRawVideoEof() {
        val video = oneSecondVideo()
        FFmpegFrameGrabber(video).use { grabber ->
            grabber.start()
            assertTrue(grabber.lengthInTime in 900_000L..1_100_000L, "test source must end at one second")
            assertNotNull(grabber.grabImage(), "test source must contain decodable image frames")
        }
        val controller = defaultVideoPlayerController(video)
        try {
            controller.setVirtualDurationMs(3_000L)
            controller.start()
            await("virtual duration publication") { controller.durationMs >= 3_000L }
            await("initial image decode or error") { controller.grabCurrentFrame() != null || controller.error != null }
            assertNotNull(controller.grabCurrentFrame(), "initial image decode failed: ${controller.error}")
            assertEquals(null, controller.error)

            // Seeking into the virtual offline tail must leave the playhead usable even though the
            // one-second decoder cannot produce frames there.
            controller.seek(2_000L)
            await("seek into virtual tail") { controller.positionMs >= 2_000L }
            assertEquals(null, controller.error)
            controller.play()
            await("playback after seeking beyond raw EOF") { controller.positionMs >= 2_250L }
            assertTrue(controller.isPlaying, "a seek beyond physical EOF must not stop virtual-tail playback")
            controller.pause()

            // Then seek back into real media and verify playback continues across the physical EOF
            // through the unavailable tail instead of reporting stopped at one second.
            controller.seek(800L)
            await("seek back into source media") { controller.positionMs >= 800L }
            controller.play()
            await("playback through the physical media EOF") { controller.positionMs >= 2_500L }
            assertTrue(controller.isPlaying, "playback must still be active before virtual EOF")
            await("playback through virtual EOF") { !controller.isPlaying }

            assertEquals(null, controller.error)
            assertFalse(controller.isPlaying)
            assertTrue(
                controller.positionMs in 2_900L..3_000L,
                "playback should advance to the virtual end, got ${controller.positionMs}ms",
            )
        } finally {
            controller.close()
        }
    }

    private fun oneSecondVideo(): String {
        val output = Files.createTempFile("openlog-virtual-video-duration", ".mkv").toFile()
        val width = 64
        val height = 48
        val pixels = ByteBuffer.allocate(width * height * 3)
        val recorder = FFmpegFrameRecorder(output, width, height).apply {
            format = "matroska"
            frameRate = 10.0
            videoCodec = AV_CODEC_ID_MPEG4
        }
        try {
            recorder.start()
            repeat(10) { frameIndex ->
                pixels.clear()
                repeat(width * height) {
                    pixels.put((20 + frameIndex).toByte())
                    pixels.put(80.toByte())
                    pixels.put(220.toByte())
                }
                pixels.flip()
                recorder.recordImage(width, height, 8, 3, width * 3, AV_PIX_FMT_BGR24, pixels)
            }
            recorder.stop()
        } finally {
            runCatching { recorder.release() }
        }
        return output.absolutePath
    }

    private fun await(label: String, timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20L)
        assertTrue(condition(), "timed out waiting for $label")
    }
}
