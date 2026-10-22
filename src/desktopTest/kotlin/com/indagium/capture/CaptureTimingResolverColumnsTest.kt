package com.indagium.capture

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The resolver keeps primitive columns and builds a [CaptureTimingPoint] on demand, and the index
 * reads through it instead of copying a row object per log line. These pin that every answer matches
 * what the per-row functions say, on a log with every awkward shape in it.
 */
@Suppress("MagicNumber")
class CaptureTimingResolverColumnsTest {
    /**
     * 12 rows, row N stamped N * 100 ms. Segment 0 (rows 1-6, reliable): the capture clock is 100 ms
     * at the first row, the video starts at capture 200 ms, so row 1 precedes the video, row 3 has no
     * timestamp. Segment 1 (rows 7-9) is uncertain: no clock. Segment 2 (rows 10-12, reliable) jumps
     * the capture clock forward; row 12 falls past the end of the 1000 ms video.
     */
    private val entries = (1..12).map { ordinal ->
        LogEntry(ordinal * 10, if (ordinal == 3) "" else clockText(ordinal * 100L), LogLevel.I, "Tag", "row $ordinal")
    }
    private val timeline = CaptureTimeline(
        rows = emptyList(),
        timelineDurationMs = 2_000,
        videoOriginElapsedMs = 200L,
        syncSegments = listOf(
            segment(0, 1, 7, anchorClockMs = 100L, anchorElapsedMs = 100L),
            CaptureSyncSegment(1, 7, 10, null, null, CaptureSyncReliability.UNCERTAIN),
            segment(2, 10, 13, anchorClockMs = 1_000L, anchorElapsedMs = 1_100L),
        ),
        videoDurationMs = 1_000,
        sparseTiming = true,
    )
    private val metadata = CaptureSyncMetadata(0L, entries.size, 0L, 2_000L, timeline.syncSegments)

    @Test
    fun everyRowMatchesTheStandalonePerRowResolution() {
        val resolver = CaptureTimingResolver.forTimeline(entries, timeline)

        for (ordinal in 1..entries.size) {
            val expected = sparseCaptureTimingPoint(entries[ordinal - 1], ordinal, metadata, timeline)
            assertEquals(expected, resolver.pointForOrdinal(ordinal), "point $ordinal")
            assertEquals(expected?.videoMs, resolver.videoMsForOrdinal(ordinal), "video $ordinal")
            assertEquals(expected?.captureElapsedMs, resolver.elapsedMsForOrdinal(ordinal), "elapsed $ordinal")
            assertEquals(metadata.segments.firstOrNull { it.contains(ordinal) }?.id, resolver.segmentIdForOrdinal(ordinal))
        }
        assertEquals(entries.mapNotNull { resolver.pointForOrdinal(entries.indexOf(it) + 1) }, resolver.allPoints())
    }

    @Test
    fun playheadKeysKeepNegativePositionsButNotRowsPastTheVideoOrWithoutAClock() {
        val resolver = CaptureTimingResolver.forTimeline(entries, timeline)

        // Row 1 is logged 100 ms before the video starts: no seekable position, but a search key.
        assertNull(resolver.videoMsForOrdinal(1))
        assertEquals(-100L, resolver.playheadKeyForOrdinal(1))
        assertEquals(0L, resolver.playheadKeyForOrdinal(2))
        assertNull(resolver.playheadKeyForOrdinal(3), "no timestamp")
        assertEquals(400L, resolver.playheadKeyForOrdinal(6))
        for (ordinal in 7..9) assertNull(resolver.playheadKeyForOrdinal(ordinal), "uncertain segment row $ordinal")
        assertEquals(900L, resolver.playheadKeyForOrdinal(10))
        assertEquals(1_000L, resolver.playheadKeyForOrdinal(11), "the video's last frame is still inside")
        // Row 12 is past the video's end yet still has a capture elapsed time.
        assertNull(resolver.playheadKeyForOrdinal(12))
        assertNull(resolver.videoMsForOrdinal(12))
        assertEquals(1_300L, resolver.elapsedMsForOrdinal(12))
    }

    @Test
    fun ordinalsOutsideTheLogHaveNoPointKeyOrElapsed() {
        val resolver = CaptureTimingResolver.forTimeline(entries, timeline)

        for (ordinal in listOf(-1, 0, entries.size + 1)) {
            assertNull(resolver.pointForOrdinal(ordinal))
            assertNull(resolver.playheadKeyForOrdinal(ordinal))
            assertNull(resolver.videoMsForOrdinal(ordinal))
            assertNull(resolver.elapsedMsForOrdinal(ordinal))
        }
        assertEquals(0, resolver.segmentIdForOrdinal(1))
        assertNull(resolver.segmentIdForOrdinal(0))
        assertNull(resolver.segmentIdForOrdinal(entries.size + 1))
    }

    @Test
    fun manySegmentsAreFoundByOrdinalWithoutScanningThem() {
        val segments = (0 until 500).map { CaptureSyncSegment(it, it * 2 + 1, it * 2 + 3, null, null, CaptureSyncReliability.UNCERTAIN) }
        val rowCount = 1_000
        val metadata = CaptureSyncMetadata(0L, rowCount, 0L, 0L, segments)

        for (ordinal in 1..rowCount) assertEquals((ordinal - 1) / 2, metadata.segmentContaining(ordinal)?.id)
        assertNull(metadata.segmentContaining(0))
        assertNull(metadata.segmentContaining(rowCount + 1))
    }

    @Test
    fun legacyRowsResolveFromTheTimelineIncludingDuplicatesAndClippedPositions() {
        val legacy = CaptureTimeline(
            rows = listOf(
                CaptureMappingRow(1, 0, 0),
                CaptureMappingRow(2, 100, null),
                CaptureMappingRow(3, 200, -5),
                CaptureMappingRow(4, 300, 900),
                CaptureMappingRow(4, 310, 950),
                CaptureMappingRow(5, 400, 2_000),
                CaptureMappingRow(9, 500, 10),
            ),
            videoDurationMs = 1_000,
        )
        val resolver = CaptureTimingResolver.forTimeline(entries.take(5), legacy)

        assertEquals(CaptureTimingPoint(1, 0, 0, null, reliable = true), resolver.pointForOrdinal(1))
        assertEquals(CaptureTimingPoint(2, 100, null, null, reliable = true), resolver.pointForOrdinal(2))
        assertEquals(CaptureTimingPoint(3, 200, null, null, reliable = true), resolver.pointForOrdinal(3))
        assertEquals(CaptureTimingPoint(4, 310, 950, null, reliable = true), resolver.pointForOrdinal(4))
        assertEquals(CaptureTimingPoint(5, 400, null, null, reliable = true), resolver.pointForOrdinal(5))
        assertNull(resolver.pointForOrdinal(9))
        assertEquals(950L, resolver.playheadKeyForOrdinal(4))
        assertNull(resolver.playheadKeyForOrdinal(5))
        assertNull(resolver.segmentIdForOrdinal(1))
    }

    @Test
    fun singleAnchorMapsRowsByClockDelta() {
        val resolver = CaptureTimingResolver.singleAnchor(entries.take(4), CaptureSyncAnchor(row = 2, videoMs = 150))

        // Row 1 is 100 ms before the anchor row: 50 ms. Row 3 has no timestamp. Row 4 is 200 ms after.
        assertEquals(CaptureTimingPoint(1, -100, 50, null, reliable = true), resolver.pointForOrdinal(1))
        assertEquals(CaptureTimingPoint(2, 0, 150, null, reliable = true), resolver.pointForOrdinal(2))
        assertNull(resolver.pointForOrdinal(3))
        assertEquals(CaptureTimingPoint(4, 200, 350, null, reliable = true), resolver.pointForOrdinal(4))
        assertEquals(350L, resolver.playheadKeyForOrdinal(4))
        assertNull(resolver.playheadKeyForOrdinal(3))
    }

    @Test
    fun indexDropsHolesInsideAClockSegmentButKeepsEveryOrdinalAddressable() {
        val resolver = CaptureTimingResolver.forTimeline(entries, timeline)
        val index = CaptureTimelineIndex(timeline, entries.size, resolver)

        // Rows without a key inside a clock segment (3, 7-9, 12) are not rows of the index.
        for (ordinal in listOf(3, 7, 8, 9, 12)) {
            assertEquals(false, index.isPlayheadSearchable(ordinal), "row $ordinal")
            assertNull(index.pointForOrdinal(ordinal), "row $ordinal")
        }
        // Dropping row 3 shifts the later rows' positions but not their ordinals.
        assertEquals(CaptureTimelineIndex.Point(4, 400L, 200L, 1), index.pointForOrdinal(4))
        assertEquals(CaptureTimelineIndex.Point(2, 200L, 0L, 1), index.pointForOrdinal(2))
        assertNull(index.pointForOrdinal(1), "before the video: searchable but not seekable")
        assertEquals(true, index.isPlayheadSearchable(1))
        assertEquals(0, index.segmentIdForOrdinal(3))
        assertEquals(1, index.segmentIdForOrdinal(8))

        assertEquals(2, index.floor(150).point?.ordinal, "row 3 has no clock, the playhead stays on row 2")
        assertEquals(4, index.floor(250).point?.ordinal)
        assertEquals(1, index.floor(-100).point?.ordinal, "the first frame still has a row at or before it")
        assertEquals(CaptureTimelineIndex.CapturePositionKind.BEFORE_FIRST, index.floor(-101).kind)
        assertEquals(CaptureTimelineIndex.CapturePositionKind.GAP, index.floor(600).kind)
        assertEquals(10, index.floor(950).point?.ordinal)
        assertEquals(CaptureTimelineIndex.Point(11, 1_200L, 1_000L, 10), index.floor(1_000).point)
        assertEquals(CaptureTimelineIndex.CapturePositionKind.AFTER_LAST, index.floor(1_001).kind)
    }

    private fun segment(id: Int, first: Int, end: Int, anchorClockMs: Long, anchorElapsedMs: Long) = CaptureSyncSegment(
        id = id,
        firstRow = first,
        lastRowExclusive = end,
        anchorLogTimeMs = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli() + anchorClockMs,
        anchorCaptureElapsedMs = anchorElapsedMs,
        reliability = CaptureSyncReliability.RELIABLE,
        clockReference = CaptureClockReference(anchorClockMs, null, 2026),
    )

    private fun clockText(ms: Long): String = "%02d:%02d:%02d.%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60, ms % 1_000)
}
