package com.indagium

import com.indagium.capture.CaptureClockReference
import com.indagium.capture.CaptureMappingRow
import com.indagium.capture.CaptureSyncReliability
import com.indagium.capture.CaptureSyncSegment
import com.indagium.capture.CaptureTimeline
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.model.VideoAttachment
import com.indagium.ui.AppState
import com.indagium.ui.summarizeItems
import com.indagium.utils.computeItems
import com.indagium.utils.logDaySlot
import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Suppress("MagicNumber")
class SparseCaptureVideoMappingTest {
    @Test
    fun legacyAdapterDoesNotApplyBakedDescriptorOffsetTwice() = withState { state ->
        val initial = tab(uncertainSecondSegment = false).copy(
            captureTimeline = CaptureTimeline(
                rows = listOf(CaptureMappingRow(1, 0, 100), CaptureMappingRow(2, 1_000, 1_100)),
                manualOffsetMs = 300,
            ),
        )
        state.tabs = listOf(initial)
        assertEquals(100L, state.logIdToVideoMs(initial, 10))
        assertEquals(1_100L, state.logIdToVideoMs(initial, 20))
        assertEquals(20, state.videoMsToNearestLogId(initial, 1_100))
    }

    @Test
    fun manualLinkCorrectsOnlyTheSelectedClockSegment() = withState { state ->
        val initial = tab(uncertainSecondSegment = false)
        state.tabs = listOf(initial)
        assertEquals(10_000L, state.logIdToVideoMs(initial, 30))

        state.setVideoAnchor(initial.id, videoMs = 15_000, logId = 30)
        val corrected = requireNotNull(state.tab(initial.id))
        assertEquals(0L, state.logIdToVideoMs(corrected, 10))
        assertEquals(1_000L, state.logIdToVideoMs(corrected, 20))
        assertEquals(15_000L, state.logIdToVideoMs(corrected, 30))
        assertEquals(16_000L, state.logIdToVideoMs(corrected, 40))
        assertNull(state.logIdToVideoMs(corrected, 50))
        assertEquals(15_000L, corrected.attachedVideo?.captureSegmentAnchors?.single()?.captureElapsedMs)
    }

    @Test
    fun uncertainSegmentCanBeLinkedAndRelinkedWithoutInventingMissingTimestamp() = withState { state ->
        val initial = tab(uncertainSecondSegment = true)
        state.tabs = listOf(initial)
        assertNull(state.logIdToVideoMs(initial, 30))
        state.setVideoAnchor(initial.id, videoMs = 15_000, logId = 30)
        val corrected = requireNotNull(state.tab(initial.id))
        assertEquals(15_000L, state.logIdToVideoMs(corrected, 30))
        assertEquals(16_000L, state.logIdToVideoMs(corrected, 40))
        assertNull(state.logIdToVideoMs(corrected, 50))

        state.setVideoAnchor(initial.id, videoMs = 18_000, logId = 30)
        val relinked = requireNotNull(state.tab(initial.id))
        assertEquals(19_000L, state.logIdToVideoMs(relinked, 40))
        assertEquals(18_000L, relinked.attachedVideo?.captureSegmentAnchors?.single()?.captureElapsedMs)
        state.setVideoAnchor(initial.id, videoMs = 20_000, logId = 50)
        assertEquals(relinked.attachedVideo?.captureSegmentAnchors, state.tab(initial.id)?.attachedVideo?.captureSegmentAnchors)
    }

    @Test
    fun uncertainLeapDayManualAnchorUsesCaptureYearInsteadOfCurrentHostYear() = withState { state ->
        val base = tab(uncertainSecondSegment = true)
        val entries = base.logData.map { entry ->
            if (entry.id in setOf(30, 40)) entry.copy(dayOfYear = logDaySlot(2, 29)) else entry
        }
        val timeline = requireNotNull(base.captureTimeline)
        val initial = base.copy(
            logData = entries,
            rmap = entries.associateBy { it.id },
            captureTimeline = timeline.copy(
                sourceStartedEpochMs = Instant.parse("2024-02-29T10:00:00Z").toEpochMilli(),
                syncSegments = timeline.syncSegments.map { segment ->
                    if (segment.id == 1) CaptureSyncSegment(1, 3, 6, null, null, CaptureSyncReliability.UNCERTAIN) else segment
                },
            ),
        )
        state.tabs = listOf(initial)
        state.setVideoAnchor(initial.id, videoMs = 15_000, logId = 30)
        val corrected = requireNotNull(state.tab(initial.id))
        assertEquals(2024, corrected.attachedVideo?.captureSegmentAnchors?.single()?.clockReference?.anchorYear)
        assertEquals(16_000L, state.logIdToVideoMs(corrected, 40))
    }

    @Test
    fun reliableManualAnchorResolvesReferenceYearAcrossDecemberToJanuary() = withState { state ->
        val base = tab(uncertainSecondSegment = false)
        val entries = base.logData.map { entry ->
            when (entry.id) {
                30 -> entry.copy(ts = "00:00:00.000", dayOfYear = logDaySlot(1, 1))
                40 -> entry.copy(ts = "00:00:01.000", dayOfYear = logDaySlot(1, 1))
                else -> entry
            }
        }
        val timeline = requireNotNull(base.captureTimeline)
        val initial = base.copy(
            logData = entries,
            rmap = entries.associateBy { it.id },
            captureTimeline = timeline.copy(
                sourceStartedEpochMs = Instant.parse("2023-12-31T23:59:50Z").toEpochMilli(),
                syncSegments = timeline.syncSegments.map { segment ->
                    if (segment.id == 1) segment.copy(
                        anchorLogTimeMs = Instant.parse("2023-12-31T23:59:59Z").toEpochMilli(),
                        anchorCaptureElapsedMs = 9_000,
                        clockReference = CaptureClockReference(86_399_000, logDaySlot(12, 31).toInt(), 2023),
                    ) else segment
                },
            ),
        )
        state.tabs = listOf(initial)
        state.setVideoAnchor(initial.id, videoMs = 15_000, logId = 30)
        val corrected = requireNotNull(state.tab(initial.id))
        assertEquals(2024, corrected.attachedVideo?.captureSegmentAnchors?.single()?.clockReference?.anchorYear)
        assertEquals(16_000L, state.logIdToVideoMs(corrected, 40))
    }

    @Test
    fun inverseNavigationDoesNotBridgeClockSegmentsWhenEveryBoundaryRowHasATimestamp() = withState { state ->
        val initial = tab(uncertainSecondSegment = false)
        state.tabs = listOf(initial)
        assertEquals(20, state.videoMsToNearestLogId(initial, 1_000))
        assertNull(state.videoMsToNearestLogId(initial, 5_000))
        assertEquals(30, state.videoMsToNearestLogId(initial, 10_000))
    }

    @Test
    fun followCacheRefreshesWhenManualLinkMakesUncertainRowsMappable() = withState { state ->
        val initial = tab(uncertainSecondSegment = true)
        state.tabs = listOf(initial)
        state.noteVisibleItems(initial.id, summarizeItems(computeItems(initial, applyFilter = true)))
        assertEquals(20, state.followTargetVisibleLogId(initial.id, 1_000))
        assertNull(state.followTargetVisibleLogId(initial.id, 15_000))
        state.setVideoAnchor(initial.id, videoMs = 15_000, logId = 30)
        assertEquals(30, state.followTargetVisibleLogId(initial.id, 15_000))
    }

    private fun tab(uncertainSecondSegment: Boolean): LogTab {
        val entries = listOf(
            LogEntry(10, "10:00:00.000", LogLevel.I, "Tag", "before"),
            LogEntry(20, "10:00:01.000", LogLevel.I, "Tag", "before"),
            LogEntry(30, "10:10:00.000", LogLevel.I, "Tag", "after"),
            LogEntry(40, "10:10:01.000", LogLevel.I, "Tag", "after"),
            LogEntry(50, "", LogLevel.I, "Tag", "without a timestamp"),
        )
        val segments = listOf(
            segment(id = 0, firstRow = 1, lastRowExclusive = 3, logClockMs = 36_000_000L, captureElapsedMs = 0L),
            segment(id = 1, firstRow = 3, lastRowExclusive = 6, logClockMs = 36_600_000L, captureElapsedMs = 10_000L)
                .let { if (uncertainSecondSegment) it.copy(reliability = CaptureSyncReliability.UNCERTAIN) else it },
        )
        return LogTab(
            id = "sparse-capture",
            filename = "capture.log",
            logData = entries,
            rmap = entries.associateBy { it.id },
            attachedVideo = VideoAttachment(
                path = "/tmp/sparse-capture.mkv",
                sourceLabel = "capture.mkv",
                durationMs = 30_000,
                captureSourcePath = "/tmp/sparse-capture.zip",
                doubleClickSeekEnabled = true,
            ),
            captureTimeline = CaptureTimeline(
                rows = emptyList(),
                timelineDurationMs = 30_000,
                videoOriginElapsedMs = 0L,
                syncSegments = segments,
                videoDurationMs = 30_000,
                sparseTiming = true,
            ),
        )
    }

    private fun segment(id: Int, firstRow: Int, lastRowExclusive: Int, logClockMs: Long, captureElapsedMs: Long) = CaptureSyncSegment(
        id = id,
        firstRow = firstRow,
        lastRowExclusive = lastRowExclusive,
        anchorLogTimeMs = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli() + logClockMs,
        anchorCaptureElapsedMs = captureElapsedMs,
        reliability = CaptureSyncReliability.RELIABLE,
        clockReference = CaptureClockReference(logClockMs, null, 2026),
    )

    private fun withState(block: (AppState) -> Unit) {
        val root = createTempDirectory("sparse-video-mapping").toFile()
        val state = AppState(autosaveFile = File(root, "autosave.cache"))
        try {
            block(state)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }
}
