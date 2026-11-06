package com.indagium

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.model.VideoAnchor
import com.indagium.model.VideoAttachment
import com.indagium.ui.AppState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The video timeline index keeps parallel id/elapsed arrays and no id->elapsed map; point lookups go
// through a dense-offset guess and then a binary search. These ids are NOT dense (gaps in the id
// sequence, plus rows without a timestamp that the index omits), so lookups must take the search path.
class ElapsedIndexSparseLookupTest {
    private fun entry(id: Int, ts: String) = LogEntry(id, ts, LogLevel.I, "Tag", "msg $id")

    private val entries = listOf(
        entry(10, "10:00:00.000"),
        entry(11, "10:00:01.000"),
        entry(15, ""),
        entry(16, "10:00:02.500"),
        entry(40, "10:00:03.000"),
        entry(41, ""),
        entry(90, "10:00:10.000"),
        entry(91, "10:00:11.000"),
    )

    private fun tab(anchorId: Int, anchorVideoMs: Long = 5_000): LogTab = LogTab(
        id = "t1",
        filename = "app.log",
        logData = entries,
        rmap = entries.associateBy { it.id },
        attachedVideo = VideoAttachment(
            path = "/tmp/repro.mp4",
            sourceLabel = "/tmp/repro.mp4",
            durationMs = 0,
            anchor = VideoAnchor(videoMs = anchorVideoMs, logId = anchorId),
        ),
    )

    private val state = AppState()

    @Test
    fun logIdToVideoMsFindsRowsAcrossIdGapsAndIsNullForRowsWithoutATimestamp() {
        val tab = tab(anchorId = 10)
        assertEquals(5_000L, state.logIdToVideoMs(tab, 10))
        assertEquals(6_000L, state.logIdToVideoMs(tab, 11))
        assertEquals(7_500L, state.logIdToVideoMs(tab, 16))
        assertEquals(8_000L, state.logIdToVideoMs(tab, 40))
        assertEquals(15_000L, state.logIdToVideoMs(tab, 90))
        assertEquals(16_000L, state.logIdToVideoMs(tab, 91))
        assertNull(state.logIdToVideoMs(tab, 15))
        assertNull(state.logIdToVideoMs(tab, 41))
        assertNull(state.logIdToVideoMs(tab, 12))
        assertNull(state.logIdToVideoMs(tab, 9))
        assertNull(state.logIdToVideoMs(tab, 1_000))
    }

    @Test
    fun anAnchorOnASparselyNumberedRowResolvesRowsBeforeAndAfterIt() {
        val tab = tab(anchorId = 40, anchorVideoMs = 20_000)
        assertEquals(17_000L, state.logIdToVideoMs(tab, 10))
        assertEquals(19_500L, state.logIdToVideoMs(tab, 16))
        assertEquals(27_000L, state.logIdToVideoMs(tab, 90))
    }

    @Test
    fun anAnchorOnARowWithoutATimestampMapsNothing() {
        val tab = tab(anchorId = 15)
        assertNull(state.logIdToVideoMs(tab, 10))
        assertNull(state.videoMsToNearestLogId(tab, 5_000))
    }

    @Test
    fun videoMsToNearestLogIdReturnsRealIdsAndSkipsRowsWithoutATimestamp() {
        val tab = tab(anchorId = 10)
        assertEquals(10, state.videoMsToNearestLogId(tab, 5_000))
        assertEquals(11, state.videoMsToNearestLogId(tab, 6_000))
        assertEquals(16, state.videoMsToNearestLogId(tab, 7_400))
        assertEquals(40, state.videoMsToNearestLogId(tab, 8_100))
        assertEquals(90, state.videoMsToNearestLogId(tab, 14_000))
        assertEquals(91, state.videoMsToNearestLogId(tab, 99_000))
        assertEquals(10, state.videoMsToNearestLogId(tab, -50_000))
    }
}
