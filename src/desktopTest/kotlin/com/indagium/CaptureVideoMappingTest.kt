package com.indagium

import com.indagium.capture.CaptureMappingRow
import com.indagium.capture.CaptureTimeline
import com.indagium.capture.CaptureTimelineIndex
import com.indagium.model.Filter
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.model.VideoAttachment
import com.indagium.ui.AppState
import com.indagium.ui.FollowMappingStatus
import com.indagium.ui.VideoMappingKind
import com.indagium.ui.summarizeItems
import com.indagium.utils.computeItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptureVideoMappingTest {
    private fun entry(id: Int, ts: String): LogEntry = LogEntry(id, ts, LogLevel.I, "Tag", "row $id")

    private fun tab(
        rows: List<CaptureMappingRow>,
        entries: List<LogEntry> = rows.map { entry(it.ordinal * 10, "23:59:${it.ordinal.toString().padStart(2, '0')}.000") },
        offsetMs: Long = 0L,
    ): LogTab = LogTab(
        id = "capture",
        filename = "capture.log",
        logData = entries,
        rmap = entries.associateBy { it.id },
        attachedVideo = VideoAttachment(
            path = "/tmp/capture.mkv",
            sourceLabel = "capture.mkv",
            captureSourcePath = "/tmp/capture.zip",
            captureOffsetMs = offsetMs,
            doubleClickSeekEnabled = true,
        ),
        captureTimeline = CaptureTimeline(rows, quality = "estimated", uncertaintyMs = 120L),
    )

    @Test
    fun forwardAndInverseUseCaptureOrdinalsInsteadOfWallClockTimestamps() {
        val rows = listOf(
            CaptureMappingRow(1, elapsedMs = 0, videoMs = 100),
            CaptureMappingRow(2, elapsedMs = 1_000, videoMs = 1_100),
            CaptureMappingRow(3, elapsedMs = 2_000, videoMs = 2_100),
        )
        val entries = listOf(
            entry(41, "23:59:59.500"),
            entry(99, "00:00:00.500"),
            entry(7, "23:59:58.000"),
        )
        val tab = tab(rows, entries)
        val state = AppState()

        assertEquals(1_100L, state.logIdToVideoMs(tab, 99))
        assertEquals(7, state.videoMsToNearestLogId(tab, 1_900))
    }

    @Test
    fun unmappedRowsAndVideoGapsAreNeverInterpolated() {
        val rows = listOf(
            CaptureMappingRow(1, 0, 0),
            CaptureMappingRow(2, 1_000, 1_000),
            CaptureMappingRow(3, 2_000, null),
            CaptureMappingRow(4, 3_000, 4_000),
            CaptureMappingRow(5, 4_000, 5_000),
        )
        val tab = tab(rows)
        val state = AppState().also {
            it.tabs = listOf(tab)
            it.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))
        }

        assertNull(state.logIdToVideoMs(tab, 30))
        assertNull(state.videoMsToNearestLogId(tab, 2_500))
        assertNull(state.followTargetVisibleLogId(tab.id, 2_500))
        assertEquals(FollowMappingStatus.UNMAPPED_GAP, state.videoFollowMapping(tab.id, 2_500).status)
        assertEquals(40, state.followTargetVisibleLogId(tab.id, 4_000))
    }

    @Test
    fun duplicateVideoPositionsResolveToTheLastCapturedRowForFollow() {
        val rows = listOf(
            CaptureMappingRow(1, 0, 0),
            CaptureMappingRow(2, 1_000, 1_000),
            CaptureMappingRow(3, 1_000, 1_000),
            CaptureMappingRow(4, 2_000, 2_000),
        )
        val tab = tab(rows)
        val state = AppState().also {
            it.tabs = listOf(tab)
            it.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))
        }

        assertEquals(30, state.videoMsToNearestLogId(tab, 1_000))
        assertEquals(30, state.followTargetVisibleLogId(tab.id, 1_000))
    }

    @Test
    fun sharedVideoBoundaryUsesEarlierSegmentWhileTheGapStaysUnmapped() {
        val index = CaptureTimelineIndex(
            CaptureTimeline(
                listOf(
                    CaptureMappingRow(1, 0, 0),
                    CaptureMappingRow(2, 1_000, 1_000),
                    CaptureMappingRow(3, 2_000, null),
                    CaptureMappingRow(4, 3_000, 1_000),
                    CaptureMappingRow(5, 4_000, 2_000),
                ),
            ),
            logRowCount = 5,
        )

        assertEquals(2, index.nearest(1_000).point?.ordinal)
        assertEquals(2, index.floor(1_000).point?.ordinal)
        assertEquals(CaptureTimelineIndex.CapturePositionKind.GAP, index.floor(1_500).kind)
    }

    @Test
    fun filteredSecondSegmentDoesNotClampFollowAcrossAnUnmappedGap() {
        val rows = listOf(
            CaptureMappingRow(1, 0, 0),
            CaptureMappingRow(2, 1_000, 1_000),
            CaptureMappingRow(3, 2_000, null),
            CaptureMappingRow(4, 3_000, 4_000),
        )
        val entries = listOf(
            LogEntry(10, "10:00:00.000", LogLevel.I, "early", "one"),
            LogEntry(20, "10:00:01.000", LogLevel.I, "early", "two"),
            LogEntry(30, "10:00:02.000", LogLevel.I, "gap", "three"),
            LogEntry(40, "10:00:03.000", LogLevel.I, "later", "four"),
        )
        val tab = tab(rows, entries).copy(filter = Filter(excludeTags = setOf("later")))
        val state = AppState().also {
            it.tabs = listOf(tab)
            it.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))
        }

        assertNull(state.followTargetVisibleLogId(tab.id, 4_000))
        assertEquals(FollowMappingStatus.NO_VISIBLE_ROW, state.videoFollowMapping(tab.id, 4_000).status)
    }

    @Test
    fun persistedOffsetShiftsBothDirectionsAndLinkActionRecalibratesIt() {
        val rows = listOf(
            CaptureMappingRow(1, 0, 0),
            CaptureMappingRow(2, 1_000, 1_000),
        )
        val initial = tab(rows, offsetMs = 500)
        val state = AppState().also { it.tabs = listOf(initial) }

        assertEquals(1_500L, state.logIdToVideoMs(initial, 20))
        assertEquals(20, state.videoMsToNearestLogId(initial, 1_500))

        state.setVideoAnchor(initial.id, videoMs = 2_500, logId = 20)
        val calibrated = state.tab(initial.id)!!
        assertNull(calibrated.attachedVideo?.anchor)
        assertEquals(1_500L, calibrated.attachedVideo?.captureOffsetMs)
        assertEquals(2_500L, state.logIdToVideoMs(calibrated, 20))
    }

    @Test
    fun captureFollowReportsEstimatedMappingWithoutLegacyAnchor() {
        val tab = tab(listOf(CaptureMappingRow(1, 42, 100)))
        val state = AppState().also {
            it.tabs = listOf(tab)
            it.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))
        }

        val mapping = state.videoFollowMapping(tab.id, 100)
        assertEquals(VideoMappingKind.CAPTURE, mapping.mappingKind)
        assertEquals("estimated", mapping.captureQuality)
        assertEquals(120L, mapping.captureUncertaintyMs)
        assertNull(mapping.anchorVideoMs)
        assertEquals(10, mapping.mappedNearestLogId)
    }
}
