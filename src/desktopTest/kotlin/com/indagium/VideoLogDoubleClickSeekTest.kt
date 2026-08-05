package com.indagium

import androidx.compose.ui.graphics.ImageBitmap
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.model.VideoAnchor
import com.indagium.model.VideoAttachment
import com.indagium.model.VideoSource
import com.indagium.ui.AppState
import com.indagium.video.VideoPlayerController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoLogDoubleClickSeekTest {
    private class FakeVideoController : VideoPlayerController {
        override val currentFrame: ImageBitmap? = null
        override val positionMs: Long = 0L
        override val durationMs: Long = 6_000L
        override val isPlaying: Boolean = false
        override val volume: Float = 1f
        override val isMuted: Boolean = false
        override val error: String? = null
        var seekedTo: Long? = null

        override fun play() = Unit

        override fun pause() = Unit

        override fun seek(ms: Long) { seekedTo = ms }

        override fun setRate(rate: Float) = Unit

        override fun setVolume(volume: Float) = Unit

        override fun setMuted(muted: Boolean) = Unit

        override fun grabCurrentFrame(): ByteArray? = null

        override fun grabFrameAt(ms: Long): ByteArray? = null

        override fun close() = Unit
    }

    private fun entry(id: Int, ts: String) = LogEntry(id, ts, LogLevel.I, "Tag", "message $id")

    @Test
    fun seekVideoToLogRowRejectsMissingAnchorUnknownTimeAndOutOfRangeWithoutChangingSelectionOrFollow() {
        val controller = FakeVideoController()
        val entries = listOf(
            entry(1, "10:00:00.000"),
            entry(2, ""),
            entry(3, "10:00:07.000"),
        )
        val state = AppState(videoControllerFactory = { controller })
        state.tabs = listOf(
            LogTab(
                id = "tab",
                filename = "app.log",
                logData = entries,
                rmap = entries.associateBy { it.id },
                selected = setOf(1),
                videoFollowLog = true,
                attachedVideo = VideoAttachment(
                    VideoSource.LocalFile("/videos/repro.mp4"),
                    "repro.mp4",
                    durationMs = 6_000L,
                ),
            ),
        )

        assertFalse(state.seekVideoToLogRow("tab", 1)) // attachment has no anchor
        state.setVideoAnchor("tab", videoMs = 1_000L, logId = 1)
        assertFalse(state.seekVideoToLogRow("tab", 2)) // no parseable log time
        assertFalse(state.seekVideoToLogRow("tab", 3)) // maps past the known duration
        assertEquals(null, controller.seekedTo)
        assertEquals(setOf(1), state.tab("tab")!!.selected)
        assertTrue(state.isVideoFollowLogEnabled("tab"))
    }

    @Test
    fun seekVideoToLogRowSeeksThroughTheCurrentAnchorWithoutChangingSelectionOrFollow() {
        val controller = FakeVideoController()
        val entries = listOf(entry(1, "10:00:00.000"), entry(2, "10:00:04.000"))
        val state = AppState(videoControllerFactory = { controller })
        state.tabs = listOf(
            LogTab(
                id = "tab",
                filename = "app.log",
                logData = entries,
                rmap = entries.associateBy { it.id },
                selected = setOf(1),
                videoFollowLog = true,
                attachedVideo = VideoAttachment(
                    VideoSource.LocalFile("/videos/repro.mp4"),
                    "repro.mp4",
                    durationMs = 6_000L,
                    anchor = VideoAnchor(videoMs = 1_000L, logId = 1),
                ),
            ),
        )

        assertTrue(state.seekVideoToLogRow("tab", 2))
        assertEquals(5_000L, controller.seekedTo)
        assertEquals(setOf(1), state.tab("tab")!!.selected)
        assertTrue(state.isVideoFollowLogEnabled("tab"))
    }

    @Test
    fun doubleClickFollowGuardCoversTheFirstPressThenHoldsTheManualSeekRowUntilPlaybackAdvances() {
        val controller = FakeVideoController()
        val entries = listOf(entry(1, "10:00:00.000"), entry(2, "10:00:04.000"), entry(3, "10:00:08.000"))
        val state = AppState(videoControllerFactory = { controller })
        state.tabs = listOf(
            LogTab(
                id = "tab",
                filename = "app.log",
                logData = entries,
                rmap = entries.associateBy { it.id },
                videoFollowLog = true,
                attachedVideo = VideoAttachment(
                    VideoSource.LocalFile("/videos/repro.mp4"),
                    "repro.mp4",
                    durationMs = 12_000L,
                    anchor = VideoAnchor(videoMs = 1_000L, logId = 1),
                ),
            ),
        )

        // The first primary press must hold Follow while it is still unknown whether this will be
        // an ordinary selection or the first half of a word-selecting double-click.
        state.beginVideoLogDoubleClickGesture("tab")
        assertTrue(state.isVideoLogDoubleClickGestureFollowSuppressed("tab"))
        state.endVideoLogDoubleClickGesture("tab")
        assertFalse(state.isVideoLogDoubleClickGestureFollowSuppressed("tab"))

        state.beginVideoLogDoubleClickGesture("tab")
        assertTrue(state.seekVideoToLogRow("tab", 2))
        assertEquals(5_000L, controller.seekedTo)
        assertFalse(state.isVideoLogDoubleClickGestureFollowSuppressed("tab"))
        assertEquals(2, state.manualVideoSeekFollowTarget("tab"))

        // VideoPanel keeps the target row's text selection until playback maps to another row.
        assertEquals(2, state.manualVideoSeekFollowTarget("tab"))
        state.clearManualVideoSeekFollowSuppression("tab")
        assertEquals(null, state.manualVideoSeekFollowTarget("tab"))
    }

    @Test
    fun linkLocalDoubleClickChoiceUsesTheCreationDefaultAndStaysIndependentFromFollow() {
        val controller = FakeVideoController()
        val entries = listOf(entry(1, "10:00:00.000"), entry(2, "10:00:04.000"))
        val state = AppState(videoControllerFactory = { controller })
        state.tabs = listOf(
            LogTab(
                id = "tab",
                filename = "app.log",
                logData = entries,
                rmap = entries.associateBy { it.id },
                attachedVideo = VideoAttachment(
                    VideoSource.LocalFile("/videos/repro.mp4"),
                    "repro.mp4",
                    durationMs = 6_000L,
                ),
            ),
        )

        state.updateSettings { it.copy(enableDoubleClickVideoSeekOnLink = false) }
        state.setVideoAnchor("tab", videoMs = 1_000L, logId = 1)
        assertFalse(state.isVideoDoubleClickSeekEnabled("tab"))
        assertFalse(state.seekVideoToLogRow("tab", 2))
        assertFalse(state.isVideoFollowLogEnabled("tab"))

        state.setVideoDoubleClickSeekEnabled("tab", true)
        assertTrue(state.isVideoDoubleClickSeekEnabled("tab"))
        assertTrue(state.seekVideoToLogRow("tab", 2))
        assertEquals(5_000L, controller.seekedTo)
        assertFalse(state.isVideoFollowLogEnabled("tab"))

        // Replacing a link deliberately takes the setting once again, without changing Follow.
        state.setVideoAnchor("tab", videoMs = 2_000L, logId = 1)
        assertFalse(state.isVideoDoubleClickSeekEnabled("tab"))
        assertFalse(state.isVideoFollowLogEnabled("tab"))
    }

    @Test
    fun noAnchorIsInactiveEvenIfAttachmentContainsAStaleEnabledFlag() {
        val controller = FakeVideoController()
        val entries = listOf(entry(1, "10:00:00.000"))
        val state = AppState(videoControllerFactory = { controller })
        state.tabs = listOf(
            LogTab(
                id = "tab",
                filename = "app.log",
                logData = entries,
                rmap = entries.associateBy { it.id },
                attachedVideo = VideoAttachment(
                    VideoSource.LocalFile("/videos/repro.mp4"),
                    "repro.mp4",
                    doubleClickSeekEnabled = true,
                ),
            ),
        )

        assertFalse(state.isVideoDoubleClickSeekEnabled("tab"))
        assertFalse(state.seekVideoToLogRow("tab", 1))
        state.setVideoDoubleClickSeekEnabled("tab", false)
        assertFalse(state.tab("tab")!!.attachedVideo!!.doubleClickSeekEnabled)
        assertEquals(null, controller.seekedTo)
    }
}
