package com.indagium

import androidx.compose.ui.graphics.ImageBitmap
import com.indagium.model.LogTab
import com.indagium.model.VideoAttachment
import com.indagium.model.VideoFrameReference
import com.indagium.model.VideoSource
import com.indagium.ui.AppState
import com.indagium.video.VideoDisplayRotationAware
import com.indagium.video.VideoPlayerController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoFrameNoteTest {
    private class FakeVideoController : VideoPlayerController, VideoDisplayRotationAware {
        override val currentFrame: ImageBitmap? = null
        override val positionMs: Long = 0L
        override val durationMs: Long = 60_000L
        override val isPlaying: Boolean = false
        override val volume: Float = 1f
        override val isMuted: Boolean = false
        override val error: String? = null
        override var displayRotationDegrees: Int = 0
        var seekedTo: Long? = null

        override fun play() = Unit

        override fun pause() = Unit

        override fun seek(ms: Long) {
            seekedTo = ms
        }

        override fun setRate(rate: Float) = Unit

        override fun setVolume(volume: Float) = Unit

        override fun setMuted(muted: Boolean) = Unit

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

    @Test
    fun frameProvenanceShortensLocalPathButKeepsDurableSourceIdentity() {
        val source = VideoSource.LocalFile("/Users/example/Downloads/repro.mp4")
        val frame = VideoFrameReference(source, source.path, 12_345L)

        assertTrue(frame.provenanceLabel.startsWith("From repro.mp4 @ "))
        assertEquals("/Users/example/Downloads/repro.mp4", (frame.source as VideoSource.LocalFile).path)
    }

    @Test
    fun archiveFrameProvenanceShowsArchiveNameAndFullEntryPath() {
        val source = VideoSource.ArchiveEntry(
            archivePath = "/Users/example/Downloads/bugreport.zip",
            entryPath = "FS/data/media/recordings/screen.mp4",
            displayName = "screen.mp4",
        )
        val frame = VideoFrameReference(source, "bugreport.zip/screen.mp4", 12_345L)

        assertTrue(frame.provenanceLabel.startsWith("From bugreport.zip/FS/data/media/recordings/screen.mp4 @ "))
        assertEquals("FS/data/media/recordings/screen.mp4", (frame.source as VideoSource.ArchiveEntry).entryPath)
    }

    @Test
    fun automaticDisplayRotationCombinesWithPersistedManualClockwiseTurns() {
        val source = VideoSource.LocalFile("/videos/repro.mp4")
        val controller = FakeVideoController().apply { displayRotationDegrees = 90 }
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
        state.videoController("tab")

        assertEquals(90, state.videoRotationDegrees("tab"))
        state.rotateVideoClockwise("tab")
        assertEquals(180, state.videoRotationDegrees("tab"))
        assertEquals(90, state.tab("tab")?.attachedVideo?.rotationDegrees)
    }
}
