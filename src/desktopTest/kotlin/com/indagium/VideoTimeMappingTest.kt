package com.indagium

import com.indagium.model.Filter
import com.indagium.model.LogAnalysis
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.model.ManualCollapseBlock
import com.indagium.model.ManualCollapseDirection
import com.indagium.model.StackTraceGroup
import com.indagium.model.VideoAnchor
import com.indagium.model.VideoAttachment
import com.indagium.ui.AppState
import com.indagium.ui.FollowMappingStatus
import com.indagium.ui.NavigationScrollMode
import com.indagium.ui.summarizeItems
import com.indagium.utils.computeItems
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AppState.logIdToVideoMs / videoMsToNearestLogId (plan doc's Task A "pure mapping helpers") —
 * deterministic anchor + row `ts` -> videoMs and back, including midnight rollover. Pure functions
 * taking a bare LogTab, so no VideoPlayerController/FFmpeg natives are involved.
 */
class VideoTimeMappingTest {
    private fun entry(id: Int, ts: String, tag: String = "Tag"): LogEntry = LogEntry(id, ts, LogLevel.I, tag, "msg $id")

    private fun tabWith(entries: List<LogEntry>, anchor: VideoAnchor?): LogTab {
        val video = anchor?.let {
            // Most legacy timestamp tests exercise the temporary opening-state fallback, before a
            // player has reported its real duration. Duration-aware row interpolation is covered
            // explicitly below.
            VideoAttachment(path = "/tmp/repro.mp4", sourceLabel = "/tmp/repro.mp4", durationMs = 0, anchor = it)
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

    /**
     * The nearest row in the FULL (unfiltered) log for a video position — the raw input the follow
     * navigation path is expected to resolve down to a visible row. Replaces the former
     * `AppState.mappedVideoLogId` convenience wrapper, deleted because a tabId-keyed "map the
     * playhead to a log row" helper that ignores the filter is exactly the call Follow must never
     * make; `followTargetVisibleLogId` is the production entry point.
     *
     * Resolves against the tab as it CURRENTLY is in state, not a captured `LogTab` val — tests
     * that call `setVideoAnchor` mid-way leave their local val holding the superseded anchor, which
     * would silently change the expected row.
     */
    private fun nearestFullLogId(tabId: String, videoMs: Long): Int =
        assertNotNull(state.videoMsToNearestLogId(assertNotNull(state.tab(tabId)), videoMs))

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
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 0, logId = 1)).copy(
            attachedVideo = VideoAttachment(
                path = "/tmp/repro.mp4",
                sourceLabel = "/tmp/repro.mp4",
                durationMs = 60_000,
                anchor = VideoAnchor(videoMs = 0, logId = 1),
            ),
        )

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

    // Regression for a real reported failure: Follow held on a row from ~54 seconds before the true
    // playhead position, with the full arithmetic (anchor + delta) checked and correct. Root cause —
    // demonstrated with this exact shape via a throwaway probe before this fix landed — was a SINGLE
    // anomalous row (here id 3, "01:00:00.000") more than half a day behind its predecessor. The old
    // buildLogElapsedIndex treated any such backward jump as a genuine midnight rollover and
    // permanently added 24h to every later row's elapsed value, so Follow's floor search could never
    // advance past the row before the anomaly again — id 4/5 (which resume the ORIGINAL time base)
    // are exactly where the report's own arithmetic said the true target should land.
    @Test
    fun singleOutlierRowDoesNotPermanentlyShiftTheElapsedTimeline() {
        val entries = listOf(
            // anchor, videoMs 241 per the report
            entry(1, "14:10:31.062"),
            // last visible before the gap
            entry(2, "14:10:31.340"),
            // lone outlier: >13h behind #2, resumes normally right after
            entry(3, "01:00:00.000"),
            entry(4, "14:11:04.663"),
            // exact target per the report's own delta arithmetic
            entry(5, "14:11:25.808"),
        )
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 241, logId = 1))
        state.tabs = listOf(tab)
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        // video 00:54.987 -> mappedElapsed = anchorElapsed(51_031_062) + (54_987 - 241) = 51_085_808,
        // i.e. exactly row 5's own timestamp.
        assertEquals(5, state.followTargetVisibleLogId(tab.id, 54_987))
        val mapping = state.videoFollowMapping(tab.id, 54_987)
        assertEquals(5, mapping.mappedNearestLogId)
        assertEquals("14:11:25.808", mapping.mappedNearestLogTs)
        assertEquals(FollowMappingStatus.ON_VISIBLE_ROW, mapping.status)

        // Rows after the outlier keep their ORIGINAL (non-shifted) elapsed values.
        assertEquals(33_842L, state.logIdToVideoMs(tab, 4))
        assertEquals(54_987L, state.logIdToVideoMs(tab, 5))
    }

    // A genuine midnight rollover — a SUSTAINED drop confirmed by the row after it also continuing
    // from the low post-jump time, not reverting to the pre-jump baseline — must still roll over.
    // This is deliberately close in shape to the suppressed case above; the two together pin the
    // exact line the lone-outlier guard draws.
    @Test
    fun sustainedRolloverAcrossMultipleRowsStillCommits() {
        val entries = listOf(
            entry(1, "23:59:59.000"),
            entry(2, "00:00:01.000"),
            entry(3, "00:00:03.000"),
            entry(4, "00:00:05.000"),
        )
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 0, logId = 1))
        assertEquals(4_000L, state.logIdToVideoMs(tab, 3))
        assertEquals(6_000L, state.logIdToVideoMs(tab, 4))
    }

    // Distinguishes a genuine midnight crossing from a concatenated-buffer log with the minimal
    // fixture that can tell them apart: a SUSTAINED backward jump (so it commits, like the test
    // above — 2 rows continuing forward from the low post-jump time defeats the lone-outlier
    // lookahead) whose LATER row then climbs back ABOVE the pre-jump baseline. A real midnight
    // crossing can never do that within one file (it would take another ~24h of capture); a second
    // buffer resuming at its own unrelated, higher time-of-day does it trivially. Per
    // buildLogElapsedIndex's doc comment, this must invalidate the whole dayOffset model and fall
    // back to raw time-of-day, rather than committing +24h and stranding every later row.
    @Test
    fun sustainedBackwardJumpThatLaterExceedsPreJumpBaselineFallsBackToRawTimeOfDay() {
        val entries = listOf(
            // anchor, videoMs 241 - matches the real report's own numbers
            entry(1, "14:10:31.062"),
            // pre-jump baseline
            entry(2, "14:10:31.340"),
            // sustained backward jump: >13h behind #2, NOT a lone outlier
            entry(3, "01:00:00.000"),
            // confirms sustained (continues forward from #3, not from #2)
            entry(4, "01:00:02.000"),
            // resumes ORIGINAL buffer - raw time-of-day exceeds #2's baseline
            entry(5, "14:11:04.663"),
        )
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 241, logId = 1))
        state.tabs = listOf(tab)
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        val diagnostics = state.followDiagnostics(tab.id, 33_842)
        assertFalse(diagnostics.dayOffsetModelValid)
        // The per-row classifier still reports what it decided in isolation (a real commit, no
        // suppression) - the flag above is what tells a reader that decision was NOT actually
        // applied to the elapsed values below.
        assertEquals(1, diagnostics.rolloverAppliedCount)
        assertEquals(3, diagnostics.rolloverAppliedSamples.single().id)
        assertEquals(0, diagnostics.rolloverSuppressedCount)

        // Raw time-of-day, not day-unrolled: row 5 maps to its own bare millis-of-day. Had the old
        // unconditional-rollover behavior applied here, row 5 would carry a spurious +24h and this
        // would read ~86_433_842 instead - permanently unreachable from any plausible video position.
        assertEquals(33_842L, state.logIdToVideoMs(tab, 5))
        assertEquals(5, state.followTargetVisibleLogId(tab.id, 33_842))
    }

    // Permanent regression for the exact real-world failure: an Android bug report concatenates
    // several log buffers (main/system/radio/...) one after another, each restarting at its own
    // earlier time-of-day, with nothing surviving LogParser's separator-stripping to mark the
    // boundary. This shape - anchor, a sustained multi-thousand-row EARLIER block standing in for
    // a second buffer (filtered out of the visible view, exactly as it was in the real report), then
    // rows resuming at the original buffer's time - reproduced Follow holding on a row ~46s (in the
    // real report; scaled here) behind the correct target before this fix, with the target time
    // itself already verified correct by arithmetic. See buildLogElapsedIndex's doc comment.
    @Test
    fun multiBufferBugReportShapeRemainsReachableAfterTheHiddenEarlierBuffer() {
        val entries = mutableListOf<LogEntry>()
        var id = 1
        entries += entry(id++, "14:10:31.062") // anchor, videoMs 241
        entries += entry(id++, "14:10:31.340") // last visible row before the buffer boundary

        // Second buffer: thousands of rows at 01:xx (>12h behind, so it's not mistaken for a small
        // real backwards jump), monotonically increasing so the lone-outlier lookahead sees a
        // sustained run and commits the rollover rather than suppressing it - then hidden from the
        // view by a tag filter, exactly like the real bug report's non-`main` buffers.
        repeat(3_000) { i ->
            val sec = 3_600 + i
            entries += entry(id++, String.format(Locale.ROOT, "%02d:%02d:%02d.000", sec / 3_600, (sec % 3_600) / 60, sec % 60), tag = "hidden")
        }

        entries += entry(id++, "14:11:04.663")
        entries += entry(id++, "14:11:05.479")
        val expectedFloorId = id
        entries += entry(id++, "14:11:16.705") // must become the Follow floor for the target below
        entries += entry(id, "14:11:53.804") // after the target - must NOT be selected

        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 241, logId = 1)).copy(
            filter = Filter(excludeTags = setOf("hidden")),
        )
        state.tabs = listOf(tab)
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        // Report's own arithmetic: anchor elapsed 51_031_062 + (46_211 - 241) = 51_077_032 ms =
        // 14:11:17.032. The floor must be the last visible row at or before that - 14:11:16.705.
        val diagnostics = state.followDiagnostics(tab.id, 46_211)
        assertEquals(51_077_032L, diagnostics.mappedElapsedMs)
        assertFalse(diagnostics.dayOffsetModelValid)
        assertEquals(expectedFloorId, state.followTargetVisibleLogId(tab.id, 46_211))
        assertEquals(FollowMappingStatus.ON_VISIBLE_ROW, state.videoFollowMapping(tab.id, 46_211).status)
    }

    @Test
    fun duplicateVisibleTimestampsSelectTheLastAvailableLine() {
        val entries = (1..5).map { id -> entry(id, "14:10:31.063") }
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 0, logId = 1)).copy(
            attachedVideo = VideoAttachment(
                path = "/tmp/repro.mp4",
                sourceLabel = "/tmp/repro.mp4",
                durationMs = 100_000,
                anchor = VideoAnchor(videoMs = 0, logId = 1),
            ),
        )
        state.tabs = listOf(tab)
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        assertEquals(5, state.followTargetVisibleLogId(tab.id, 0))
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
    fun followNavigationUsesTheLastVisibleRowAtOrBeforeTheMappedTime() {
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

        assertEquals(setOf(2), state.tab(tab.id)?.selected)
        assertEquals(listOf(2), assertNotNull(state.pendingAnnotationNavigation).logIds)
        assertEquals(NavigationScrollMode.FOLLOW, state.pendingAnnotationNavigation?.scrollMode)
        assertTrue(state.isVideoFollowLogEnabled(tab.id))
        // Filter-hidden rows can never be revealed by expanding a group (there is no group to
        // expand), so this must keep clamping to the visible floor byte-for-byte, not ask the
        // viewer to expand anything.
        assertFalse(state.pendingAnnotationNavigation?.expandCollapsedGroups ?: true)
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

        // The mapped row is hidden at +1s. Follow must stay at the anchor rather than jump ahead
        // to the visible +1min line.
        state.navigateToVideoLog(tab.id, nearestFullLogId(tab.id, 1_000))
        assertEquals(setOf(1), state.tab(tab.id)?.selected)

        // Once playback actually reaches the visible +1min row, follow advances normally.
        state.navigateToVideoLog(tab.id, nearestFullLogId(tab.id, 60_000))
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
        state.navigateToVideoLog(tab.id, nearestFullLogId(tab.id, 60_000))

        assertEquals(setOf(2_001), state.tab(tab.id)?.selected)
        assertTrue(state.isVideoFollowLogEnabled(tab.id))
    }

    @Test
    fun setVideoAnchorAtVideoStartStoresZeroMs() {
        // AppState.setVideoAnchor itself must still accept an explicit videoMs = 0 regardless of
        // wherever the playhead happens to be — e.g. a user who seeks the video to 0:00 first and
        // then uses the context menu's "Link to 0:00" (there's no dedicated "link to start" action;
        // seeking to 0:00 and linking IS that action), or the MCP set_video_anchor tool.
        val tab = tabWith(entries = listOf(entry(1, "10:00:00.000")), anchor = null).copy(
            attachedVideo = VideoAttachment(path = "/tmp/repro.mp4", sourceLabel = "/tmp/repro.mp4", durationMs = 60_000),
        )
        state.tabs = listOf(tab)
        state.activeTabId = tab.id

        state.setVideoAnchor(tab.id, videoMs = 0L, logId = 1)

        assertEquals(VideoAnchor(videoMs = 0L, logId = 1), state.tab(tab.id)?.attachedVideo?.anchor)
    }

    @Test
    fun followNavigationDoesNotRepublishWhenHiddenRowsKeepTheSameVisibleFloor() {
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

    // B2: forceRecenter is what makes the explicit "Logs" button work when its target is already
    // selected but has scrolled off-screen — the automatic follow path (forceRecenter = false, the
    // default) must keep the existing already-selected dedupe so rapid playhead ticks resolving to
    // the same visible row don't spam navigation requests.
    @Test
    fun forceRecenterReissuesNavigationEvenWhenAlreadySelectedWhileDefaultDoesNot() {
        val tab = tabWith(
            entries = listOf(entry(1, "10:00:00.000"), entry(2, "10:00:01.000")),
            anchor = VideoAnchor(videoMs = 0, logId = 1),
        )
        state.tabs = listOf(tab)
        state.activeTabId = tab.id
        state.setVideoFollowLog(tab.id, enabled = true)

        state.navigateToVideoLog(tab.id, logId = 2)
        val firstRequest = assertNotNull(state.pendingAnnotationNavigation)
        assertEquals(setOf(2), state.tab(tab.id)?.selected)

        // Same already-selected row, automatic path (forceRecenter defaults to false): no republish.
        state.navigateToVideoLog(tab.id, logId = 2)
        assertEquals(firstRequest, state.pendingAnnotationNavigation)

        // Same already-selected row, explicit "Logs" button (forceRecenter = true): always re-issues,
        // e.g. to re-center a view that scrolled the selected row off-screen.
        state.navigateToVideoLog(tab.id, logId = 2, forceRecenter = true)
        val secondRequest = assertNotNull(state.pendingAnnotationNavigation)
        assertNotEquals(firstRequest.id, secondRequest.id)
        assertEquals(setOf(2), state.tab(tab.id)?.selected)
    }

    // Shared by the followRevealTarget tests below: entry 2 heads a collapsed manual block that
    // folds entries 3 and 4 away, exactly the fixture followMappingIsHiddenByCollapse... above uses
    // to prove the mapping distinguishes HIDDEN_BY_COLLAPSE from HIDDEN_BY_FILTER.
    private fun tabWithFoldedRow(largeFileMode: Boolean = false): LogTab {
        val entries = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "any", "anchor"),
            LogEntry(2, "10:00:01.000", LogLevel.I, "any", "collapse block anchor"),
            LogEntry(3, "10:00:02.000", LogLevel.I, "any", "folded away"),
            LogEntry(4, "10:00:03.000", LogLevel.I, "any", "folded away"),
            LogEntry(5, "10:00:04.000", LogLevel.I, "any", "after the block"),
        )
        val block = ManualCollapseBlock(id = "m1", anchorId = 2, direction = ManualCollapseDirection.TO_END)
        return tabWith(entries, anchor = VideoAnchor(videoMs = 0, logId = 1)).copy(
            manualBlocks = listOf(block),
            largeFileMode = largeFileMode,
            // expanded stays empty (default) -> the block renders collapsed.
        )
    }

    // followRevealTarget's whole reason to exist: a row folded by a collapsed group (not filtered
    // out) must navigate to the REAL row rather than clamping to the group's header, and must tell
    // the viewer to expand the group to reach it.
    @Test
    fun navigateToVideoLogRevealsAFoldedRowAndSetsExpandCollapsedGroups() {
        val tab = tabWithFoldedRow()
        state.tabs = listOf(tab)
        state.activeTabId = tab.id
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        state.navigateToVideoLog(tab.id, logId = 3)

        assertEquals(setOf(3), state.tab(tab.id)?.selected)
        val request = assertNotNull(state.pendingAnnotationNavigation)
        assertEquals(listOf(3), request.logIds)
        assertEquals(NavigationScrollMode.FOLLOW, request.scrollMode)
        assertTrue(request.expandCollapsedGroups)
    }

    // followExpandAttemptByTab bounds the reveal path to one attempt per folded run: while playback
    // keeps landing on the same folded row (the visible floor never advances), repeated automatic
    // Follow ticks must not keep republishing the same expand request.
    @Test
    fun navigateToVideoLogAllowsOnlyOneExpandAttemptPerFoldedRun() {
        val tab = tabWithFoldedRow()
        state.tabs = listOf(tab)
        state.activeTabId = tab.id
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        state.navigateToVideoLog(tab.id, logId = 3)
        val firstRequest = assertNotNull(state.pendingAnnotationNavigation)

        // Same folded run (the visible floor is still row 2, since nothing expanded tab.expanded
        // in this pure test): no republish.
        state.navigateToVideoLog(tab.id, logId = 3)
        assertEquals(firstRequest, state.pendingAnnotationNavigation)
        assertEquals(setOf(3), state.tab(tab.id)?.selected)
    }

    // forceRecenter (the explicit "Logs" button) must bypass the one-attempt-per-run memo too,
    // exactly like it bypasses the plain clamp-path dedupe above.
    @Test
    fun forceRecenterBypassesTheFoldedRunExpandMemo() {
        val tab = tabWithFoldedRow()
        state.tabs = listOf(tab)
        state.activeTabId = tab.id
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        state.navigateToVideoLog(tab.id, logId = 3)
        val firstRequest = assertNotNull(state.pendingAnnotationNavigation)

        state.navigateToVideoLog(tab.id, logId = 3, forceRecenter = true)
        val secondRequest = assertNotNull(state.pendingAnnotationNavigation)
        assertNotEquals(firstRequest.id, secondRequest.id)
        assertEquals(setOf(3), state.tab(tab.id)?.selected)
        assertTrue(secondRequest.expandCollapsedGroups)
    }

    // largeFileMode disables the viewer's collapsed-group search outright (LogViewer.kt), so a
    // reveal request there could only ever be dropped — followRevealTarget must not publish one.
    @Test
    fun navigateToVideoLogClampsWhenLargeFileModeDisablesGroupSearch() {
        val tab = tabWithFoldedRow(largeFileMode = true)
        state.tabs = listOf(tab)
        state.activeTabId = tab.id
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        state.navigateToVideoLog(tab.id, logId = 3)

        assertEquals(setOf(2), state.tab(tab.id)?.selected)
        val request = assertNotNull(state.pendingAnnotationNavigation)
        assertEquals(listOf(2), request.logIds)
        assertFalse(request.expandCollapsedGroups)
    }

    // B3: followTargetVisibleLogId is the shared visible-floor resolver for the transport bar and
    // automatic follow effect.
    @Test
    fun followTargetVisibleLogIdReturnsTheLastVisibleRowBeforeAFiliteredMatch() {
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
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        assertEquals(2, state.followTargetVisibleLogId(tab.id, 2_000))
    }

    @Test
    fun followHoldsAtLastVisibleTimestampUntilPlaybackReachesTheNextOne() {
        val entries = listOf(
            LogEntry(1, "14:10:31.062", LogLevel.I, "shown", "anchor"),
            LogEntry(2, "14:10:31.340", LogLevel.I, "shown", "last early visible"),
            LogEntry(3, "14:10:40.268", LogLevel.I, "hidden", "mapped during the gap"),
            LogEntry(4, "14:11:04.663", LogLevel.I, "shown", "next visible"),
        )
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 0, logId = 1)).copy(
            filter = Filter(excludeTags = setOf("hidden")),
        )
        state.tabs = listOf(tab)
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        // At 10 seconds, 14:10:40.268 is the exact (hidden) match. Do not jump 24 seconds
        // forward to 14:11:04.663; hold at the last visible 14:10:31.340 line.
        assertEquals(2, state.followTargetVisibleLogId(tab.id, 10_015))
        // The full playhead time, not the nearest hidden row's timestamp, decides the floor.
        assertEquals(2, state.followTargetVisibleLogId(tab.id, 33_500))
        // The next visible line becomes selectable only once the video reaches its timestamp.
        assertEquals(4, state.followTargetVisibleLogId(tab.id, 33_601))
        // After the final visible timestamp, remain on it rather than falling back to a hidden
        // earlier row just because that row is the closest full-log timestamp.
        assertEquals(4, state.followTargetVisibleLogId(tab.id, 42_552))
    }

    // The contract VideoPanel's follow effect relies on to recover from a manual click. Keyed on the
    // resolved target alone, that effect went silent for as long as the target held — which, with a
    // filter active, is the whole gap to the next visible row (often minutes). It now also keys on
    // tab.selected while playing, so re-issuing the SAME unchanged target has to work.
    @Test
    fun renavigatingToAnUnchangedFollowTargetRestoresSelectionAfterAManualClick() {
        val entries = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "shown", "visible A"),
            LogEntry(2, "10:00:01.000", LogLevel.I, "hidden", "hidden"),
            LogEntry(3, "10:01:00.000", LogLevel.I, "shown", "visible B"),
        )
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 0, logId = 1)).copy(
            filter = Filter(excludeTags = setOf("hidden")),
        )
        state.tabs = listOf(tab)
        state.activeTabId = tab.id
        state.setVideoFollowLog(tab.id, enabled = true)
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        val target = assertNotNull(state.followTargetVisibleLogId(tab.id, 1_000))
        state.navigateToVideoLog(tab.id, target)
        assertEquals(setOf(1), state.tab(tab.id)?.selected)

        // Manual click to look ahead; Follow stays on (selRow no longer disables it).
        state.selRow(tab.id, 3, multi = false, range = false)
        assertEquals(setOf(3), state.tab(tab.id)?.selected)

        // Playback advanced, but the visible floor is unchanged — Follow must still take back over.
        assertEquals(target, state.followTargetVisibleLogId(tab.id, 5_000))
        state.navigateToVideoLog(tab.id, target)
        assertEquals(setOf(1), state.tab(tab.id)?.selected)
        assertTrue(state.isVideoFollowLogEnabled(tab.id))
    }

    // A Seq/Manual/StackTrace header IS one real log entry, just rendered in header style — and
    // stack-trace folding is always on, so ordinary logs produce them without the user asking.
    // Follow's candidate set therefore has to be `allIds`, not `rowIds` (which holds only
    // LogItem.Row): restricted to rows, a line heading a group was unreachable while plainly visible
    // on screen, and Follow sat on the last plain row before it for the rest of playback.
    @Test
    fun followReachesAnOnScreenGroupHeaderInsteadOfStallingOnTheRowBeforeIt() {
        val entries = listOf(
            LogEntry(1, "14:11:04.663", LogLevel.I, "App", "anchor line"),
            LogEntry(2, "14:11:05.479", LogLevel.I, "App", "last plain row"),
            LogEntry(3, "14:11:16.705", LogLevel.E, "App", "java.lang.IllegalStateException: boom"),
            LogEntry(4, "14:11:16.705", LogLevel.E, "App", "\tat com.example.A.a(A.kt:1)"),
            LogEntry(5, "14:11:53.804", LogLevel.I, "App", "much later plain row"),
        )
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 0, logId = 1)).copy(
            attachedVideo = VideoAttachment(
                path = "/tmp/repro.mp4",
                sourceLabel = "/tmp/repro.mp4",
                durationMs = 120_000,
                anchor = VideoAnchor(videoMs = 0, logId = 1),
            ),
            // Entry 3 heads a collapsed stack-trace group; entry 4 is swallowed into it.
            analysis = LogAnalysis(
                pending = false,
                stackTraceGroups = listOf(StackTraceGroup(gid = "st_3", rid = 3, memberIds = listOf(4))),
            ),
        )
        state.tabs = listOf(tab)
        state.activeTabId = tab.id
        val summary = summarizeItems(computeItems(tab, applyFilter = true))
        state.noteVisibleItems(tab.id, summary)

        // Precondition: the header entry really is excluded from rowIds but present on screen.
        assertFalse(summary.rowIds.contains(3), "entry 3 should be a header, not a plain row")
        assertTrue(summary.allIds.contains(3), "entry 3 should still be displayed")

        // Before the header's own timestamp, the last plain row is right.
        assertEquals(2, state.followTargetVisibleLogId(tab.id, 1_000))
        // Once playback crosses it, Follow must move onto the header rather than stalling on row 2.
        assertEquals(3, state.followTargetVisibleLogId(tab.id, 12_100))
        assertEquals(3, state.followTargetVisibleLogId(tab.id, 21_576))
        // And it keeps advancing past the group to later plain rows.
        assertEquals(5, state.followTargetVisibleLogId(tab.id, 49_200))
    }

    @Test
    fun followTargetVisibleLogIdNeedsAnAnchorAndAVisibleRow() {
        val entries = listOf(entry(1, "10:00:00.000"))
        assertNull(state.followTargetVisibleLogId("no-such-tab", 1_000))

        val tabNoAnchor = tabWith(entries, anchor = null)
        state.tabs = listOf(tabNoAnchor)
        assertNull(state.followTargetVisibleLogId(tabNoAnchor.id, 1_000))

        // Anchor present but the current filter hides every row: there is no selectable row.
        val tabAllHidden = tabWith(entries, anchor = VideoAnchor(videoMs = 0, logId = 1)).copy(
            filter = Filter(excludeTags = setOf("Tag")),
        )
        state.tabs = listOf(tabAllHidden)
        state.noteVisibleItems(tabAllHidden.id, summarizeItems(computeItems(tabAllHidden, applyFilter = true)))
        assertNull(state.followTargetVisibleLogId(tabAllHidden.id, 1_000))
    }

    @Test
    fun twoVideoPositionsUseTheSameVisibleFloorUntilTheNextVisibleTimestamp() {
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

        val firstTarget = assertNotNull(state.followTargetVisibleLogId(tab.id, 2_000))
        val secondTarget = assertNotNull(state.followTargetVisibleLogId(tab.id, 3_000))
        assertEquals(firstTarget, secondTarget)

        state.navigateToVideoLog(tab.id, firstTarget)
        val firstRequest = assertNotNull(state.pendingAnnotationNavigation)
        state.navigateToVideoLog(tab.id, secondTarget)

        assertEquals(firstRequest, state.pendingAnnotationNavigation)
    }

    // videoFollowMapping — the Follow readout's substrate. Filtered-view Follow silently clamps to
    // whichever boundary row is nearest-visible (see followTargetVisibleLogId's doc); this mapping
    // exists so the UI can tell the user *why* it looks stuck instead of leaving it ambiguous.
    @Test
    fun followMappingIsNoAnchorWithoutAVideoOrAnchor() {
        val entries = listOf(entry(1, "10:00:00.000"))
        val tabNoVideo = tabWith(entries, anchor = null)
        state.tabs = listOf(tabNoVideo)
        val mapping = state.videoFollowMapping(tabNoVideo.id, 1_000)
        assertEquals(FollowMappingStatus.NO_ANCHOR, mapping.status)
        assertNull(mapping.anchorLogTs)
        assertNull(mapping.anchorVideoMs)
        assertNull(mapping.mappedNearestLogId)
        assertNull(mapping.mappedNearestLogTs)

        // Unknown tab id.
        assertEquals(FollowMappingStatus.NO_ANCHOR, state.videoFollowMapping("no-such-tab", 1_000).status)
    }

    @Test
    fun followMappingIsBeforeFirstWhenPlayheadMapsEarlierThanTheFirstTimestampedRow() {
        val entries = listOf(
            entry(1, "10:00:00.000"),
            entry(2, "10:00:10.000"),
        )
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 10_000, logId = 1))
        state.tabs = listOf(tab)

        // 5s of video is 5s before the anchor's mapped time, i.e. before row 1's own elapsed time.
        val mapping = state.videoFollowMapping(tab.id, 5_000)
        assertEquals(FollowMappingStatus.BEFORE_FIRST, mapping.status)
        assertEquals("10:00:00.000", mapping.anchorLogTs)
        assertEquals(10_000L, mapping.anchorVideoMs)
    }

    @Test
    fun followMappingIsAfterLastWhenPlayheadMapsLaterThanTheLastTimestampedRow() {
        val entries = listOf(
            entry(1, "10:00:00.000"),
            entry(2, "10:00:10.000"),
        )
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 0, logId = 1))
        state.tabs = listOf(tab)

        // 20s of video is 10s past row 2's own elapsed time (the last timestamped row).
        val mapping = state.videoFollowMapping(tab.id, 20_000)
        assertEquals(FollowMappingStatus.AFTER_LAST, mapping.status)
    }

    // The single most confusing filtered-view state: the log line matching this video moment exists
    // but the filter hides it, so Follow is deliberately holding on an older visible line. Reporting
    // this as ON_VISIBLE_ROW made a legitimately-behind selection indistinguishable from a frozen one
    // — the readout has to be able to say why. mappedNearestLogId stays the row Follow would actually
    // select, so the readout and the selection can never disagree.
    @Test
    fun followMappingIsHiddenByFilterWhenTheMatchingRowIsFilteredOutButAVisibleFloorExists() {
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
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        val mapping = state.videoFollowMapping(tab.id, 2_000)
        assertEquals(FollowMappingStatus.HIDDEN_BY_FILTER, mapping.status)
        assertEquals(2, mapping.mappedNearestLogId)
        assertEquals("10:00:01.000", mapping.mappedNearestLogTs)
        // Whatever the status, the reported row is exactly what Follow selects.
        assertEquals(state.followTargetVisibleLogId(tab.id, 2_000), mapping.mappedNearestLogId)

        // Playback reaching a visible row's own timestamp is not "hidden".
        assertEquals(FollowMappingStatus.ON_VISIBLE_ROW, state.videoFollowMapping(tab.id, 3_000).status)
    }

    // Regression for a mislabeling bug found alongside the reported "stuck" case: with NO filter
    // active at all (Filter() default — every row passes), a COLLAPSED manual block still folds its
    // member rows out of the displayed item list, which is indistinguishable from HIDDEN_BY_FILTER
    // by floor-mismatch alone (both just mean "the true row isn't independently visible"). The old
    // code reported every such mismatch as HIDDEN_BY_FILTER, which would have told this exact user
    // to go check a filter that was never the cause. fullFloorHiddenReason distinguishes the two by
    // actually checking passesFilter() on the full-log floor row.
    @Test
    fun followMappingIsHiddenByCollapseWhenARowIsFoldedRatherThanFiltered() {
        val entries = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "any", "anchor"),
            LogEntry(2, "10:00:01.000", LogLevel.I, "any", "collapse block anchor"),
            LogEntry(3, "10:00:02.000", LogLevel.I, "any", "folded away"),
            LogEntry(4, "10:00:03.000", LogLevel.I, "any", "folded away"),
            LogEntry(5, "10:00:04.000", LogLevel.I, "any", "after the block"),
        )
        val block = ManualCollapseBlock(id = "m1", anchorId = 2, direction = ManualCollapseDirection.TO_END)
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 0, logId = 1)).copy(
            manualBlocks = listOf(block),
            // expanded stays empty (default) -> the block renders collapsed.
        )
        assertEquals(Filter(), tab.filter) // sanity: no filter criterion is configured at all
        state.tabs = listOf(tab)
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        val mapping = state.videoFollowMapping(tab.id, 3_000)
        assertEquals(FollowMappingStatus.HIDDEN_BY_COLLAPSE, mapping.status)
        assertEquals(2, mapping.mappedNearestLogId)
        assertEquals(state.followTargetVisibleLogId(tab.id, 3_000), mapping.mappedNearestLogId)
        // The un-clamped full-log floor is exposed too — this is what followRevealTarget plumbs
        // through navigateToVideoLog to reveal the real row instead of stalling on the header.
        // videoMs 3_000 maps exactly onto entry 4's own timestamp (10:00:03.000, 3s after the
        // anchor), not entry 3 — the mapped moment lands ON the later folded row, and the visible
        // floor still clamps to entry 2 because both 3 and 4 sit inside the same collapsed block.
        assertEquals(4, mapping.mappedFullFloorLogId)
    }

    // The genuinely-filtered case (proven above) must stay HIDDEN_BY_FILTER, not get relabeled by
    // the new fullFloorHiddenReason check — passesFilter() on the hidden row must actually return
    // false for that status, confirmed here by a filter that excludes tag "hidden" exactly as before.
    @Test
    fun followMappingStaysHiddenByFilterWhenTheRowGenuinelyFailsTheFilter() {
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
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        val mapping = state.videoFollowMapping(tab.id, 2_000)
        assertEquals(FollowMappingStatus.HIDDEN_BY_FILTER, mapping.status)
        // The mapping still exposes the un-clamped floor — it's followRevealTarget, not the
        // mapping itself, that decides a filtered-out row can never be revealed by expanding.
        assertEquals(3, mapping.mappedFullFloorLogId)
    }

    // followDiagnostics is the observability counterpart to videoFollowMapping: it must report the
    // exact same resolved row/status PLUS the extra fields a user hands back to identify why —
    // including the rollover counters that are otherwise invisible (see
    // singleOutlierRowDoesNotPermanentlyShiftTheElapsedTimeline above for the mechanism itself).
    @Test
    fun followDiagnosticsReportsRolloverCountersAndCandidatesAfterTheChosenFloor() {
        val entries = listOf(
            entry(1, "14:10:31.062"),
            entry(2, "14:10:31.340"),
            // suppressed lone outlier
            entry(3, "01:00:00.000"),
            entry(4, "14:11:04.663"),
            entry(5, "14:11:25.808"),
            entry(6, "14:12:00.000"),
        )
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 241, logId = 1))
        state.tabs = listOf(tab)
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        val diagnostics = state.followDiagnostics(tab.id, 54_987)
        assertEquals(FollowMappingStatus.ON_VISIBLE_ROW, diagnostics.status)
        assertEquals(5, diagnostics.chosenVisibleFloor?.id)
        assertEquals("14:11:25.808", diagnostics.chosenVisibleFloor?.ts)
        assertEquals(listOf(6), diagnostics.nextVisibleCandidatesAfterFloor.map { it.id })
        assertEquals(0, diagnostics.rolloverAppliedCount)
        assertEquals(1, diagnostics.rolloverSuppressedCount)
        assertEquals(3, diagnostics.rolloverSuppressedSamples.single().id)
        assertFalse(diagnostics.candidatesFromSummaryFallback)
        assertEquals(6, diagnostics.totalLogDataSize)

        // The formatted report is plain text with no message/tag content leaked into it.
        val report = com.indagium.ui.formatFollowDiagnostics(diagnostics)
        entries.forEach { assertFalse(report.contains(it.msg)) }
    }

    // Previously unreachable: mappedNearestLogId was only null when nothing was visible, and the
    // readout formatted that into the literal text "log null · #null".
    @Test
    fun followMappingIsNoVisibleRowWhenTheFilterHidesEveryTimestampedRow() {
        val entries = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "hidden", "a"),
            LogEntry(2, "10:00:01.000", LogLevel.I, "hidden", "b"),
        )
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 0, logId = 1)).copy(
            filter = Filter(excludeTags = setOf("hidden")),
        )
        state.tabs = listOf(tab)
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        val mapping = state.videoFollowMapping(tab.id, 500)
        assertEquals(FollowMappingStatus.NO_VISIBLE_ROW, mapping.status)
        assertNull(mapping.mappedNearestLogId)
    }

    // A landed ItemsSummary must invalidate anything Compose derived from the previous one:
    // visibleItemsByTab is a plain ConcurrentHashMap, so this counter is the observable half of
    // that read. Without it, changing a filter with playback paused left the Follow target and the
    // readout resolving against the pre-filter row set until some unrelated recomposition.
    @Test
    fun noteVisibleItemsBumpsTheVersionSoFollowReDerivesAgainstTheNewFilter() {
        val entries = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "alpha", "a"),
            LogEntry(2, "10:00:01.000", LogLevel.I, "beta", "b"),
            LogEntry(3, "10:00:02.000", LogLevel.I, "beta", "c"),
        )
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 0, logId = 1))
        state.tabs = listOf(tab)
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))
        val versionBefore = state.visibleItemsVersion
        assertEquals(3, state.followTargetVisibleLogId(tab.id, 2_000))

        state.upFlt(tab.id) { it.copy(excludeTags = setOf("beta")) }
        val filtered = assertNotNull(state.tab(tab.id))
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(filtered, applyFilter = true)))

        assertNotEquals(versionBefore, state.visibleItemsVersion)
        assertEquals(1, state.followTargetVisibleLogId(tab.id, 2_000))
    }

    @Test
    fun followMappingIsOnVisibleRowWhenTheNearestFullLogRowIsShown() {
        val entries = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "shown", "first"),
            LogEntry(2, "10:00:01.000", LogLevel.I, "shown", "second"),
        )
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 0, logId = 1)).copy(
            filter = Filter(excludeTags = setOf("nonexistent")),
        )
        state.tabs = listOf(tab)
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        val mapping = state.videoFollowMapping(tab.id, 1_000)
        assertEquals(FollowMappingStatus.ON_VISIBLE_ROW, mapping.status)
        assertEquals(2, mapping.mappedNearestLogId)
        assertEquals("10:00:01.000", mapping.mappedNearestLogTs)
        assertEquals("10:00:00.000", mapping.anchorLogTs)
        assertEquals(0L, mapping.anchorVideoMs)
    }

    @Test
    fun followMappingTreatsAnUnfilteredMissingVisibleSummaryAsOnVisibleRow() {
        // No noteVisibleItems call yet — mirrors a freshly opened tab before the viewer has
        // reported its first item list. Absence of visibility data must not read as "hidden".
        val entries = listOf(entry(1, "10:00:00.000"), entry(2, "10:00:01.000"))
        val tab = tabWith(entries, anchor = VideoAnchor(videoMs = 0, logId = 1))
        state.tabs = listOf(tab)

        val mapping = state.videoFollowMapping(tab.id, 1_000)
        assertEquals(FollowMappingStatus.ON_VISIBLE_ROW, mapping.status)
        assertEquals(2, mapping.mappedNearestLogId)
    }
}
