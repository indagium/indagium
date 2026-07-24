package com.openlog

import com.openlog.model.LogEntry
import com.openlog.model.Filter
import com.openlog.model.LogLevel
import com.openlog.model.LogTab
import com.openlog.model.VideoAnchor
import com.openlog.model.VideoAttachment
import com.openlog.ui.AppState
import com.openlog.ui.NavigationScrollMode
import com.openlog.ui.summarizeItems
import com.openlog.utils.computeItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    @Test
    fun mappingUsesOneMonotonicTimelineAcrossMidnightInBothDirections() {
        val entries = listOf(
            entry(1, "23:59:59.000"),
            entry(2, "00:00:01.000"),
            entry(3, "00:00:03.000"),
        )
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 10_000, logId = 2))

        assertEquals(8_000L, state.logIdToVideoMs(tab, 1))
        assertEquals(12_000L, state.logIdToVideoMs(tab, 3))
        assertEquals(1, state.videoMsToNearestLogId(tab, 8_000))
        assertEquals(3, state.videoMsToNearestLogId(tab, 12_000))
    }

    @Test
    fun rangeValidationRejectsNegativeAndPastKnownDuration() {
        val entries = listOf(entry(1, "10:00:00.000"))
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 0, logId = 1))

        assertEquals(1_000L..2_000L, state.validatedVideoRange(tab, 1_000, 2_000))
        assertNull(state.validatedVideoRange(tab, -1, 2_000))
        assertNull(state.validatedVideoRange(tab, 2_000, 1_000))
        assertNull(state.validatedVideoRange(tab, 1_000, 60_001))
    }

    @Test
    fun smallBackwardsTimestampIsNotTreatedAsAnotherMidnight() {
        val entries = listOf(
            entry(1, "10:00:01.000"),
            entry(2, "10:00:00.900"),
        )
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 5_000, logId = 1))

        assertEquals(4_900L, state.logIdToVideoMs(tab, 2))
        assertEquals(2, state.videoMsToNearestLogId(tab, 4_900))
    }

    @Test
    fun followNavigationSelectsMappedRowWithFollowViewportModeWithoutDisablingFollowMode() {
        val tab = tabWith(
            entries = listOf(entry(1, "10:00:00.000"), entry(2, "10:00:01.000")),
            anchor = VideoAnchor(videoMs = 0, logId = 1),
        )
        state.tabs = listOf(tab)
        state.activeTabId = tab.id
        state.setVideoFollowLog(tab.id, enabled = true)

        state.navigateToVideoLog(tab.id, logId = 2)

        assertEquals(setOf(2), state.tab(tab.id)?.selected)
        assertTrue(state.isVideoFollowLogEnabled(tab.id))
        assertEquals(listOf(2), assertNotNull(state.pendingAnnotationNavigation).logIds)
        assertEquals(tab.id, state.pendingAnnotationNavigation?.tabId)
        assertEquals(NavigationScrollMode.FOLLOW, state.pendingAnnotationNavigation?.scrollMode)
    }

    @Test
    fun followNavigationUsesClosestDisplayedRowWhenMappedRowIsFilteredOut() {
        val entries = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "shown", "first"),
            LogEntry(2, "10:00:01.000", LogLevel.I, "shown", "before"),
            LogEntry(3, "10:00:02.000", LogLevel.I, "hidden", "mapped"),
            LogEntry(4, "10:00:03.000", LogLevel.I, "shown", "after"),
        )
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 0, logId = 1)).copy(
            filter = Filter(excludeTags = setOf("hidden")),
        )
        state.tabs = listOf(tab)
        state.activeTabId = tab.id
        state.setVideoFollowLog(tab.id, enabled = true)
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        state.navigateToVideoLog(tab.id, logId = 3)

        // 2 and 4 are equally close in source order; the preceding displayed row wins the tie.
        assertEquals(setOf(2), state.tab(tab.id)?.selected)
        assertEquals(listOf(2), assertNotNull(state.pendingAnnotationNavigation).logIds)
        assertEquals(NavigationScrollMode.FOLLOW, state.pendingAnnotationNavigation?.scrollMode)
        assertTrue(state.isVideoFollowLogEnabled(tab.id))
    }

    @Test
    fun followNavigationUsesMappedTimeInsteadOfRowIdDistanceForFilteredRepeatedLines() {
        // The hidden rows are intentionally assigned source-order ids far from the anchor. This
        // mirrors a dense filtered span: at one second, id-distance says that the visible +1min
        // line is closer, while the actual timestamp says to stay on the visible anchor.
        val entries = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "shown", "anchor"),
            LogEntry(999, "10:00:00.000", LogLevel.I, "hidden", "same timestamp"),
            LogEntry(1_000, "10:00:01.000", LogLevel.I, "hidden", "one second later"),
            LogEntry(1_001, "10:01:00.000", LogLevel.I, "shown", "one minute later"),
        )
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 0, logId = 1)).copy(
            filter = Filter(excludeTags = setOf("hidden")),
        )
        state.tabs = listOf(tab)
        state.activeTabId = tab.id
        state.setVideoFollowLog(tab.id, enabled = true)
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        // The real mapped row is hidden at +1s. The nearest visible timestamp is the anchor,
        // not the next visible record one minute later.
        state.navigateToVideoLog(tab.id, assertNotNull(state.mappedVideoLogId(tab.id, 1_000)))
        assertEquals(setOf(1), state.tab(tab.id)?.selected)

        // Once playback actually reaches the visible +1min row, follow advances normally.
        state.navigateToVideoLog(tab.id, assertNotNull(state.mappedVideoLogId(tab.id, 60_000)))
        assertEquals(setOf(1_001), state.tab(tab.id)?.selected)
        assertTrue(state.isVideoFollowLogEnabled(tab.id))
    }

    @Test
    fun relinkingAnchorStillFollowsTheFullSourceTimelineToTheNextVisibleRow() {
        val entries = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "shown", "first"),
            LogEntry(999, "10:00:00.000", LogLevel.I, "hidden", "repeated"),
            LogEntry(1_000, "10:00:01.000", LogLevel.I, "hidden", "hidden second"),
            LogEntry(1_001, "10:01:00.000", LogLevel.I, "shown", "new anchor"),
            LogEntry(2_001, "10:02:00.000", LogLevel.I, "shown", "next visible"),
        )
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 0, logId = 1)).copy(
            filter = Filter(excludeTags = setOf("hidden")),
        )
        state.tabs = listOf(tab)
        state.activeTabId = tab.id
        state.setVideoFollowLog(tab.id, enabled = true)
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        // Set a fresh link at the one-minute row. A minute of video later maps to the two-minute
        // row in the full timeline and must move the filtered selection there.
        state.setVideoAnchor(tab.id, videoMs = 0, logId = 1_001)
        state.navigateToVideoLog(tab.id, assertNotNull(state.mappedVideoLogId(tab.id, 60_000)))

        assertEquals(setOf(2_001), state.tab(tab.id)?.selected)
        assertTrue(state.isVideoFollowLogEnabled(tab.id))
    }

    @Test
    fun followNavigationDoesNotRepublishWhenMappedRowsResolveToTheSameDisplayedRow() {
        val entries = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "hidden", "first"),
            LogEntry(2, "10:00:01.000", LogLevel.I, "shown", "before"),
            LogEntry(3, "10:00:02.000", LogLevel.I, "hidden", "mapped one"),
            LogEntry(4, "10:00:03.000", LogLevel.I, "hidden", "mapped two"),
            LogEntry(5, "10:00:04.000", LogLevel.I, "hidden", "after"),
        )
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 0, logId = 1)).copy(
            filter = Filter(excludeTags = setOf("hidden")),
        )
        state.tabs = listOf(tab)
        state.activeTabId = tab.id
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        state.navigateToVideoLog(tab.id, logId = 3)
        val firstRequest = assertNotNull(state.pendingAnnotationNavigation)
        state.navigateToVideoLog(tab.id, logId = 4)

        assertEquals(setOf(2), state.tab(tab.id)?.selected)
        assertEquals(firstRequest, state.pendingAnnotationNavigation)
    }

    @Test
    fun showInLogsUsesTheSameFollowSafeNavigationMode() {
        val tab = tabWith(
            entries = listOf(entry(1, "10:00:00.000"), entry(2, "10:00:01.000")),
            anchor = VideoAnchor(videoMs = 0, logId = 1),
        )
        state.tabs = listOf(tab)
        state.activeTabId = tab.id
        state.setVideoFollowLog(tab.id, enabled = true)

        state.requestVideoLogNavigation(tab.id, logId = 2)

        assertEquals(setOf(2), state.tab(tab.id)?.selected)
        assertTrue(state.isVideoFollowLogEnabled(tab.id))
        assertEquals(listOf(2), assertNotNull(state.pendingAnnotationNavigation).logIds)
        assertEquals(tab.id, state.pendingAnnotationNavigation?.tabId)
        assertEquals(NavigationScrollMode.FOLLOW, state.pendingAnnotationNavigation?.scrollMode)
    }
}
