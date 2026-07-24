package com.openlog

import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.model.LogTab
import com.openlog.model.VideoAnchor
import com.openlog.model.VideoAttachment
import com.openlog.ui.AppState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * AppState.logIdToVideoMs / videoMsToNearestLogId (plan doc's Task A "pure mapping helpers") —
 * deterministic anchor + row `ts` -> videoMs and back, including midnight rollover. Pure functions
 * taking a bare LogTab, so no VideoPlayerController/FFmpeg natives are involved.
 */
class VideoTimeMappingTest {
    private fun entry(id: Int, ts: String): LogEntry = LogEntry(id, ts, LogLevel.I, "Tag", "msg $id")

    private fun tabWith(entries: List<LogEntry>, anchor: VideoAnchor?): LogTab {
        val video = anchor?.let {
            VideoAttachment(path = "/tmp/repro.mp4", sourceLabel = "/tmp/repro.mp4", durationMs = 60_000, anchor = it)
        }
        return LogTab(
            id = "t1",
            filename = "app.log",
            logData = entries,
            rmap = entries.associateBy { it.id },
            attachedVideo = video,
        )
    }

    private val state = AppState()

    @Test
    fun logIdToVideoMsIsNullWithNoVideoAttached() {
        val tab = tabWith(listOf(entry(1, "10:00:00.000")), anchor = null)
        assertNull(state.logIdToVideoMs(tab, 1))
    }

    @Test
    fun logIdToVideoMsIsNullWithNoAnchorSet() {
        val entries = listOf(entry(1, "10:00:00.000"))
        val tab = tabWith(entries, anchor = null).copy(
            attachedVideo = VideoAttachment(path = "/tmp/repro.mp4", sourceLabel = "/tmp/repro.mp4"),
        )
        assertNull(state.logIdToVideoMs(tab, 1))
    }

    @Test
    fun logIdToVideoMsAddsTheDeltaFromTheAnchorRow() {
        // Anchor: log row 1 (10:00:00.000) <-> video 5_000ms. Row 3 is +2.5s later in the log, so
        // its video-time should be anchor.videoMs + 2500.
        val entries = listOf(
            entry(1, "10:00:00.000"),
            entry(2, "10:00:01.250"),
            entry(3, "10:00:02.500"),
        )
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 5_000, logId = 1))
        assertEquals(5_000L, state.logIdToVideoMs(tab, 1))
        assertEquals(6_250L, state.logIdToVideoMs(tab, 2))
        assertEquals(7_500L, state.logIdToVideoMs(tab, 3))
    }

    @Test
    fun logIdToVideoMsHandlesARowBeforeTheAnchor() {
        val entries = listOf(entry(1, "10:00:00.000"), entry(2, "10:00:05.000"))
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 10_000, logId = 2))
        assertEquals(5_000L, state.logIdToVideoMs(tab, 1))
    }

    @Test
    fun logIdToVideoMsCorrectsForMidnightRollover() {
        // Anchor just before midnight, target row just after — deltaMillis' rollover correction
        // (LogTime.kt) must treat this as +2s forward, not a ~24h jump backward.
        val entries = listOf(entry(1, "23:59:59.000"), entry(2, "00:00:01.000"))
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 1_000, logId = 1))
        assertEquals(3_000L, state.logIdToVideoMs(tab, 2))
    }

    @Test
    fun logIdToVideoMsIsNullWhenEitherRowIsMissingOrUnparseable() {
        val entries = listOf(entry(1, "10:00:00.000"), entry(2, "")) // blank ts = brief/RAW row
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 0, logId = 1))
        assertNull(state.logIdToVideoMs(tab, 2))
        assertNull(state.logIdToVideoMs(tab, 999)) // no such log id
    }

    @Test
    fun videoMsToNearestLogIdIsTheInverseOfLogIdToVideoMs() {
        val entries = listOf(
            entry(1, "10:00:00.000"),
            entry(2, "10:00:01.250"),
            entry(3, "10:00:02.500"),
        )
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 5_000, logId = 1))
        assertEquals(1, state.videoMsToNearestLogId(tab, 5_000))
        assertEquals(2, state.videoMsToNearestLogId(tab, 6_250))
        assertEquals(3, state.videoMsToNearestLogId(tab, 7_500))
    }

    @Test
    fun videoMsToNearestLogIdPicksTheClosestRowForAnInBetweenTime() {
        val entries = listOf(
            entry(1, "10:00:00.000"),
            entry(2, "10:00:01.000"),
            entry(3, "10:00:02.000"),
        )
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 0, logId = 1))
        // 1.6s is closer to row 3 (2.0s, diff 0.4s) than row 2 (1.0s, diff 0.6s).
        assertEquals(3, state.videoMsToNearestLogId(tab, 1_600))
        // Exactly halfway (diff 0.5s either way) rounds to whichever the loop sees first with a
        // strictly-smaller diff — pins current, deterministic behavior rather than leaving it
        // unspecified.
        assertEquals(2, state.videoMsToNearestLogId(tab, 1_500))
    }

    @Test
    fun videoMsToNearestLogIdIsNullWithNoAnchorOrUnparseableAnchorRow() {
        val entries = listOf(entry(1, "10:00:00.000"))
        assertNull(state.videoMsToNearestLogId(tabWith(entries, anchor = null), 1_000))

        val unparseableAnchor = listOf(entry(1, "")).let { it }
        val tabBadAnchor = tabWith(unparseableAnchor, anchor = VideoAnchor(videoMs = 0, logId = 1))
        assertNull(state.videoMsToNearestLogId(tabBadAnchor, 1_000))
    }
}
