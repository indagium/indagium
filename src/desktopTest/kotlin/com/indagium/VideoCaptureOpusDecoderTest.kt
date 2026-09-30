package com.indagium

import com.indagium.video.configureCaptureMkvOpusDecoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder_by_name
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoCaptureOpusDecoderTest {
    @Test
    fun captureMkvPrefersLibopusButUnrelatedVideosKeepDefaultSelection() {
        val libopus = avcodec_find_decoder_by_name("libopus")
        assumeTrue("bundled FFmpeg has no libopus decoder", libopus != null && !libopus.isNull)

        val capture = FFmpegFrameGrabber("unused")
        val unrelated = FFmpegFrameGrabber("unused")
        try {
            configureCaptureMkvOpusDecoder(capture, "/tmp/capture/video/screen.mkv")
            configureCaptureMkvOpusDecoder(unrelated, "/tmp/imported/video/movie.mkv")

            assertEquals("libopus", capture.audioCodecName)
            assertEquals("noparse", capture.getOption("fflags"))
            assertNull(unrelated.audioCodecName)
            assertNull(unrelated.getOption("fflags"))
        } finally {
            capture.release()
            unrelated.release()
        }
    }
}
