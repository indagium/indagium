package com.indagium

import com.indagium.capture.CaptureArchiveExporter
import com.indagium.capture.CaptureBlockWriter
import com.indagium.capture.CaptureClockReference
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExportRequest
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureStatus
import com.indagium.capture.CaptureStorageVersion
import com.indagium.capture.CaptureSyncMetadata
import com.indagium.capture.CaptureSyncReliability
import com.indagium.capture.CaptureSyncSegment
import com.indagium.capture.CaptureTimeline
import com.indagium.capture.CaptureVideoClip
import com.indagium.capture.CaptureVideoExporter
import com.indagium.capture.parseCaptureClockReference
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.model.VideoAttachment
import com.indagium.ui.AppState
import com.indagium.ui.FollowMappingStatus
import com.indagium.ui.summarizeItems
import com.indagium.utils.computeItems
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The video panel's Follow button is enabled exactly when [AppState.videoFollowMapping] resolves a
 * visible target for the playhead position (`VideoPanel.videoFollowState`). These pin that for a
 * v5 (sparse, clock-segment) capture at the positions a paused player really sits at: the first
 * frame right after opening and wherever the user seeks to, not only while it plays.
 *
 * SparseCaptureVideoMappingTest and CaptureSparseArchiveIntegrationTest use perfectly ordered logs
 * with every row timestamped and never ask for the playhead at the first frame, so a v5 mapping
 * that is unusable for most seeks still passed.
 */
@Suppress("MagicNumber", "LargeClass")
class SparseCaptureFollowTest {
    @Test
    fun importedV5ArchiveFollowsAtTheFirstFrameRightAfterOpening() = withImportedArchive { state, tab ->
        // The first frame sits before the first row that has a video position; the row logged just
        // before the video began is the last one at or before it.
        val atStart = state.videoFollowMapping(tab.id, 0)
        assertEquals(FollowMappingStatus.ON_VISIBLE_ROW, atStart.status)
        assertEquals(tab.logData[VIDEO_START_ROW - 1].id, atStart.mappedNearestLogId)
    }

    @Test
    fun importedV5ArchiveFollowsAtEverySeekPositionEvenWithOutOfOrderRows() = withImportedArchive { state, tab ->
        // Every position a paused player can be left at, including the ones inside the stretches
        // where the log's clock steps backwards.
        var positionMs = FIRST_SEEK_MS
        while (positionMs <= VIDEO_DURATION_MS - STEP_MS) {
            val mapping = state.videoFollowMapping(tab.id, positionMs)
            assertTrue(state.isVideoPositionValid(tab, positionMs), "position $positionMs must be valid")
            assertEquals(FollowMappingStatus.ON_VISIBLE_ROW, mapping.status, "status at $positionMs ms")
            assertNotNull(mapping.mappedNearestLogId, "Follow target at $positionMs ms")
            positionMs += STEP_MS
        }
    }

    @Test
    fun rowsWithoutATimestampDoNotLeaveUnmappedHolesInsideOneClockSegment() = withState { state ->
        val tab = syntheticTab(timestampless = setOf(3, 7))
        state.tabs = listOf(tab)
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        assertFollowsEverywhere(state, tab, fromMs = 0, toMs = 900, stepMs = 7)
        // Row 3's own slot (100 ms) has no clock, so the playhead there stays on row 2.
        assertEquals(tab.logData[1].id, state.videoFollowMapping(tab.id, 150).mappedNearestLogId)
        assertEquals(tab.logData[5].id, state.videoFollowMapping(tab.id, 505).mappedNearestLogId)
    }

    @Test
    fun aRowStampedBeforeItsPredecessorDoesNotMakeTheRestOfTheSegmentUnreachable() = withState { state ->
        val tab = syntheticTab(outOfOrder = setOf(4))
        state.tabs = listOf(tab)
        state.noteVisibleItems(tab.id, summarizeItems(computeItems(tab, applyFilter = true)))

        assertFollowsEverywhere(state, tab, fromMs = 0, toMs = 900, stepMs = 7)
        // Log order wins over the stamp: once the clock has passed row 3, the late-stamped row 4 is
        // the last row at or before the playhead.
        assertEquals(tab.logData[3].id, state.videoFollowMapping(tab.id, 150).mappedNearestLogId)
    }

    private fun assertFollowsEverywhere(state: AppState, tab: LogTab, fromMs: Long, toMs: Long, stepMs: Long) {
        var positionMs = fromMs
        while (positionMs <= toMs) {
            val mapping = state.videoFollowMapping(tab.id, positionMs)
            assertEquals(FollowMappingStatus.ON_VISIBLE_ROW, mapping.status, "status at $positionMs ms")
            assertNotNull(mapping.mappedNearestLogId, "Follow target at $positionMs ms")
            positionMs += stepMs
        }
    }

    /**
     * 12 rows 100 ms apart; the capture starts at the first row and the video 100 ms later, so the
     * first row precedes the video and row N (1-based) sits at video position (N - 2) * 100 ms.
     */
    private fun syntheticTab(timestampless: Set<Int> = emptySet(), outOfOrder: Set<Int> = emptySet()): LogTab {
        val entries = (1..12).map { ordinal ->
            val clockMs = ordinal * 100L - if (ordinal in outOfOrder) 250L else 0L
            LogEntry(
                ordinal * 10,
                if (ordinal in timestampless) "" else clockText(clockMs),
                LogLevel.I,
                "Tag",
                "row $ordinal",
            )
        }
        val segment = CaptureSyncSegment(
            id = 0,
            firstRow = 1,
            lastRowExclusive = entries.size + 1,
            anchorLogTimeMs = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli() + 100L,
            anchorCaptureElapsedMs = 100L,
            reliability = CaptureSyncReliability.RELIABLE,
            clockReference = CaptureClockReference(100L, null, 2026),
        )
        return LogTab(
            id = "sparse-follow",
            filename = "capture.log",
            logData = entries,
            rmap = entries.associateBy { it.id },
            attachedVideo = VideoAttachment(
                path = "/tmp/sparse-follow.mkv",
                sourceLabel = "capture.mkv",
                durationMs = 1_000,
                captureSourcePath = "/tmp/sparse-follow.zip",
                doubleClickSeekEnabled = true,
            ),
            captureTimeline = CaptureTimeline(
                rows = emptyList(),
                timelineDurationMs = 2_000,
                videoOriginElapsedMs = 200L,
                syncSegments = listOf(segment),
                videoDurationMs = 1_000,
                sparseTiming = true,
            ),
        )
    }

    /** A real v5 `.zip`: ordered arrival times, a clock that steps backwards on every tenth row. */
    private fun exportV5Archive(root: File): File {
        val directory = File(root, "session").apply { mkdirs() }
        val session = CaptureSession(
            id = "sparse-follow-session",
            directory = directory,
            device = CaptureDevice("serial", "device", "fixture"),
            settings = CaptureSettings(recordVideo = true, freeSpaceReserveBytes = 0),
            startedEpochMs = SESSION_STARTED_EPOCH_MS,
            elapsedMs = ROW_COUNT * ROW_INTERVAL_MS,
            status = CaptureStatus.STOPPED,
            videoStartElapsedMs = VIDEO_START_ELAPSED_MS,
            storageVersion = CaptureStorageVersion.BLOCKS_V2,
        )
        val elapsed = (0 until ROW_COUNT).map { it * ROW_INTERVAL_MS + ROW_PHASE_MS }
        val lines = elapsed.mapIndexed { index, arrivalMs ->
            val stampedMs = if (index % OUT_OF_ORDER_EVERY == OUT_OF_ORDER_EVERY / 2) arrivalMs - OUT_OF_ORDER_BY_MS else arrivalMs
            "01-01 10:00:${clockSeconds(stampedMs)}  1  1 I Tag: row ${index + 1}\n"
        }
        val bytes = lines.joinToString("").toByteArray(Charsets.UTF_8)
        session.logFile.parentFile.mkdirs()
        session.logFile.writeBytes(bytes)
        session.blockIndexFile.parentFile.mkdirs()
        var byteOffset = 0L
        CaptureBlockWriter(session.blockIndexFile).use { writer ->
            lines.forEachIndexed { index, line ->
                val raw = line.toByteArray(Charsets.UTF_8)
                writer.append(byteOffset, raw, index + 1, elapsed[index], 0, elapsed[index])
                byteOffset += raw.size
            }
            writer.freeze()
        }
        val firstReference = requireNotNull(parseCaptureClockReference(lines.first(), SESSION_STARTED_EPOCH_MS + elapsed.first()))
        val metadata = CaptureSyncMetadata(
            logBytes = bytes.size.toLong(),
            rowCount = lines.size,
            timelineBaseElapsedMs = 0,
            timelineDurationMs = elapsed.last() + TIMELINE_SLACK_MS,
            segments = listOf(
                CaptureSyncSegment(
                    id = 0,
                    firstRow = 1,
                    lastRowExclusive = lines.size + 1,
                    anchorLogTimeMs = SESSION_STARTED_EPOCH_MS + elapsed.first(),
                    anchorCaptureElapsedMs = elapsed.first(),
                    reliability = CaptureSyncReliability.RELIABLE,
                    clockReference = firstReference,
                ),
            ),
        )
        session.syncFile.parentFile.mkdirs()
        session.syncFile.writeText(metadata.toJson())
        session.videoFile.parentFile.mkdirs()
        session.videoFile.writeBytes(byteArrayOf(1, 2, 3))
        val source = session.copy(
            syncMetadataOverride = metadata,
            frozenLogBytes = bytes.size.toLong(),
            frozenBlockIndexBytes = session.blockIndexFile.length(),
        )
        val video = CaptureVideoExporter { _, destination, start, end ->
            destination.writeText("synthetic video")
            CaptureVideoClip(start, end, end - start)
        }
        val destination = File(root, "capture.zip")
        CaptureArchiveExporter(video).export(source, CaptureExportRequest(destination, cutoffElapsedMs = elapsed.last() + 1))
        return destination
    }

    private fun clockSeconds(ms: Long): String = "%02d.%03d".format(ms / 1000, ms % 1000)

    private fun clockText(ms: Long): String = "00:00:" + clockSeconds(ms)

    /** Opens a freshly exported v5 archive through the real Open/drag-and-drop path. */
    private fun withImportedArchive(block: (AppState, LogTab) -> Unit) = runBlocking {
        val root = createTempDirectory("sparse-follow-import").toFile()
        try {
            val archive = exportV5Archive(root)
            val state = AppState(
                autosaveFile = File(root, "autosave"),
                autoExportNotes = false,
                archiveCacheDir = File(root, "cache"),
            )
            try {
                val tabId = state.openCaptureFile(archive)
                withTimeout(LOAD_TIMEOUT_MS) { while (state.isLoadInFlight(tabId)) delay(POLL_MS) }
                val tab = assertNotNull(state.tab(tabId), state.openError?.message)
                assertTrue(assertNotNull(tab.captureTimeline).sparseTiming, "the archive must exercise the v5 mapping")
                assertEquals(ROW_COUNT, tab.logData.size)
                // The LogViewer reports what it shows; the Follow floor index reads this.
                state.noteVisibleItems(tabId, summarizeItems(computeItems(tab, applyFilter = true)))
                block(state, tab)
            } finally {
                state.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun withState(block: (AppState) -> Unit) {
        val root = createTempDirectory("sparse-follow").toFile()
        val state = AppState(autosaveFile = File(root, "autosave.cache"))
        try {
            block(state)
        } finally {
            state.close()
            root.deleteRecursively()
        }
    }

    private companion object {
        const val SESSION_STARTED_EPOCH_MS = 1_704_103_200_000L // 2024-01-01 10:00 UTC
        const val ROW_COUNT = 400
        const val ROW_INTERVAL_MS = 50L
        const val ROW_PHASE_MS = 7L
        const val OUT_OF_ORDER_EVERY = 10
        const val OUT_OF_ORDER_BY_MS = 120L
        const val VIDEO_START_ELAPSED_MS = 1_000L
        const val TIMELINE_SLACK_MS = 10_000L
        const val STEP_MS = 125L
        const val FIRST_SEEK_MS = 100L

        /** Rows arrive every 50 ms from 7 ms; the 21st (ordinal 20) is the last one before video time 0. */
        const val VIDEO_START_ROW = 20
        const val VIDEO_DURATION_MS = ROW_COUNT * ROW_INTERVAL_MS - VIDEO_START_ELAPSED_MS
        const val LOAD_TIMEOUT_MS = 20_000L
        const val POLL_MS = 20L
    }
}
