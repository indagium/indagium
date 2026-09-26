package com.indagium

import com.indagium.video.ffmpegDisplayRotationDegrees
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VideoOrientationTest {
    @Test
    fun displayMatrixClockwiseRotationIsNormalizedAndRotateTagIsFallback() {
        // av_display_rotation_get reports counterclockwise degrees; the app applies clockwise.
        assertEquals(90, ffmpegDisplayRotationDegrees(-90.0))
        assertEquals(270, ffmpegDisplayRotationDegrees(90.0))
        assertEquals(180, ffmpegDisplayRotationDegrees(-180.0))
        assertEquals(90, ffmpegDisplayRotationDegrees(0.0, "90"))
        assertEquals(270, ffmpegDisplayRotationDegrees(null, "-90"))
        assertEquals(0, ffmpegDisplayRotationDegrees(Double.NaN, "unsupported"))
    }

    @Test
    fun syntheticDisplayMatrixMatchesExternalClockwiseRotationAndDecodedFrameStaysRaw() {
        val file = syntheticRotatedVideo()
        FFmpegFrameGrabber(file).use { grabber ->
            grabber.start()
            val frame = grabber.grabImage()
            assertNotNull(frame)
            // JavaCV's converter returns the untransformed coded pixels. This guards against
            // applying the metadata rotation twice: playback and note capture add the display
            // matrix once at presentation time.
            assertEquals(64, frame.imageWidth)
            assertEquals(48, frame.imageHeight)
            // FFmpeg reports this Display Matrix as +90 (counterclockwise); Compose's positive
            // angle is clockwise, so the equivalent presentation transform is 270 degrees.
            val clockwiseDegrees = ffmpegDisplayRotationDegrees(grabber.displayRotation, grabber.getVideoMetadata("rotate"))
            assertEquals(270, clockwiseDegrees)

            val source = java.awt.image.BufferedImage(64, 48, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val png = ByteArrayOutputStream().use { out ->
                ImageIO.write(source, "png", out)
                out.toByteArray()
            }
            val oriented = requireNotNull(com.indagium.ui.rotatedFramePng(png, clockwiseDegrees))
            val image = assertNotNull(ImageIO.read(oriented.inputStream()))
            assertEquals(48, image.width)
            assertEquals(64, image.height)
        }
    }

    private fun syntheticRotatedVideo(): java.io.File {
        val file = Files.createTempFile("indagium-display-rotation-", ".mp4").toFile().apply { deleteOnExit() }
        val width = 64
        val height = 48
        val pixels = ByteBuffer.allocate(width * height * 3)
        val recorder = FFmpegFrameRecorder(file, width, height, 0).apply {
            format = "mp4"
            frameRate = 1.0
            videoCodec = AV_CODEC_ID_MPEG4
            videoBitrate = 300_000
            setDisplayRotation(90.0)
        }
        try {
            recorder.start()
            repeat(width * height) { pixel ->
                // An asymmetric image gives a deterministic coded frame even if the codec changes
                // the exact colors slightly.
                pixels.put(if (pixel / width < height / 3) 0xff.toByte() else 0x20.toByte())
                pixels.put(if (pixel % width < width / 2) 0x80.toByte() else 0x10.toByte())
                pixels.put(0x40.toByte())
            }
            pixels.flip()
            recorder.recordImage(width, height, 8, 3, width * 3, AV_PIX_FMT_BGR24, pixels)
            recorder.stop()
        } finally {
            runCatching { recorder.release() }
        }
        assertTrue(file.length() > 0)
        return file
    }
}
