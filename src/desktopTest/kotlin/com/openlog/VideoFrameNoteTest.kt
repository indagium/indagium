package com.openlog

import androidx.compose.ui.graphics.ImageBitmap
import com.openlog.model.LogTab
import com.openlog.model.VideoAttachment
import com.openlog.model.VideoFrameReference
import com.openlog.model.VideoSource
import com.openlog.ui.AppState
import com.openlog.video.VideoPlayerController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoFrameNoteTest {
    private class FakeVideoController : VideoPlayerController {
        override val currentFrame: ImageBitmap? = null
        override val positionMs: Long = 0L
        override val durationMs: Long = 60_000L
        override val isPlaying: Boolean = false
        override val error: String? = null
        var seekedTo: Long? = null

        override fun play() = Unit

        override fun pause() = Unit

        override fun seek(ms: Long) {
            seekedTo = ms
        }

        override fun setRate(rate: Float) = Unit

        override fun grabCurrentFrame(): ByteArray? = null

        override fun grabFrameAt(ms: Long): ByteArray? = null

        override fun close() = Unit
    }

    @Test
    fun navigationSeeksTheMatchingVideoToTheSavedExactFramePosition() {
        val controller = FakeVideoController()
        val source = VideoSource.LocalFile("/videos/repro.mp4")
        val state = AppState(videoControllerFactory = { controller })
        state.tabs = listOf(
            LogTab(
                id = "tab",
                filename = "app.log",
                logData = emptyList(),
                rmap = emptyMap(),
                attachedVideo = VideoAttachment(source, "repro.mp4"),
            ),
        )
        val frame = VideoFrameReference(source, "repro.mp4", 12_345L)

        assertTrue(state.navigateToVideoFrame("tab", frame))
        assertEquals(12_345L, controller.seekedTo)
        assertTrue(state.videoPanelVisible)
    }

    @Test
    fun navigationRefusesAFrameFromADifferentVideo() {
        val controller = FakeVideoController()
        val state = AppState(videoControllerFactory = { controller })
        state.tabs = listOf(
            LogTab(
                id = "tab",
                filename = "app.log",
                logData = emptyList(),
                rmap = emptyMap(),
                attachedVideo = VideoAttachment(VideoSource.LocalFile("/videos/current.mp4"), "current.mp4"),
            ),
        )
        val oldFrame = VideoFrameReference(VideoSource.LocalFile("/videos/old.mp4"), "old.mp4", 12_345L)

        assertFalse(state.navigateToVideoFrame("tab", oldFrame))
        assertEquals(null, controller.seekedTo)
    }
}
