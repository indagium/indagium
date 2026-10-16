package com.indagium.ui

import com.indagium.capture.CaptureAvailabilityInterval
import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureExportPreview
import com.indagium.capture.CaptureExportRequest
import com.indagium.capture.CaptureRange
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureSnapshotRangeTest {
    private val stopped = CaptureSession(
        id = "range-test",
        directory = File("/tmp/range-test"),
        device = CaptureDevice("serial", "device"),
        settings = CaptureSettings(recordVideo = true),
        startedEpochMs = 0L,
        elapsedMs = 20L * 60_000L,
        status = CaptureStatus.STOPPED,
    )

    @Test
    fun stoppedPlayheadOnlyCutsLastMinutesAndSinceSave() {
        val playhead = 10L * 60_000L
        assertEquals(stopped.elapsedMs, captureSnapshotCutoffForChoice(stopped, SnapshotRangeChoice.ALL, playhead))
        assertEquals(stopped.elapsedMs, captureSnapshotCutoffForChoice(stopped, SnapshotRangeChoice.SELECTION, playhead))
        assertEquals(playhead, captureSnapshotCutoffForChoice(stopped, SnapshotRangeChoice.LAST_MINUTES, playhead))
        assertEquals(playhead, captureSnapshotCutoffForChoice(stopped, SnapshotRangeChoice.SINCE_SAVE, playhead))
    }

    @Test
    fun aroundPlayheadIncludesTheAfterWindowAndUsesFullSessionEnd() {
        val bounds = captureSnapshotExplicitBounds(
            SnapshotRangeChoice.AROUND_PLAYHEAD,
            durationMs = stopped.elapsedMs,
            playheadElapsedMs = 10L * 60_000L,
        )
        assertEquals(5L * 60_000L to 15L * 60_000L, bounds)
    }

    @Test
    fun startOffsetAndExplicitRangeAreNotTruncatedAtTheStoppedPlayhead() {
        val playhead = 10L * 60_000L
        assertEquals(
            0L to 12L * 60_000L,
            captureSnapshotExplicitBounds(
                SnapshotRangeChoice.START_TO_PLAYHEAD,
                stopped.elapsedMs,
                playhead,
                offsetMs = 2L * 60_000L,
            ),
        )
        assertEquals(
            15L * 60_000L to 18L * 60_000L,
            captureSnapshotExplicitBounds(
                SnapshotRangeChoice.EXPLICIT,
                stopped.elapsedMs,
                playhead,
                explicitStartMs = 15L * 60_000L,
                explicitEndMs = 18L * 60_000L,
            ),
        )
    }

    @Test
    fun explicitWindowsClampToCaptureBoundsAndRejectEmptyRangesAtCaller() {
        assertEquals(
            19L * 60_000L to stopped.elapsedMs,
            captureSnapshotExplicitBounds(
                SnapshotRangeChoice.EXPLICIT,
                stopped.elapsedMs,
                playheadElapsedMs = 10L * 60_000L,
                explicitStartMs = 19L * 60_000L,
                explicitEndMs = 25L * 60_000L,
            ),
        )
        assertEquals(
            0L to 0L,
            captureSnapshotExplicitBounds(
                SnapshotRangeChoice.AROUND_PLAYHEAD,
                stopped.elapsedMs,
                playheadElapsedMs = 0L,
                beforeMs = 5L * 60_000L,
                afterMs = 0L,
            ),
        )
        assertNull(captureSnapshotExplicitBounds(SnapshotRangeChoice.ALL, stopped.elapsedMs, 0L))
    }

    @Test
    fun sinceSaveTracksTheCurrentStoppedOrLivePlayheadAndRefreshesAfterSave() {
        val checkpoint = 10L * 60_000L
        val saved = stopped.copy(snapshotCheckpointMs = checkpoint, videoCheckpointMs = checkpoint)
        // Imported and retained stopped sources share the same playhead cutoff as the editor.
        assertEquals(9L * 60_000L, captureSnapshotCutoffForChoice(saved, SnapshotRangeChoice.SINCE_SAVE, 9L * 60_000L))
        assertFalse(captureSinceSaveEnabled(saved, 9L * 60_000L))
        assertTrue(captureSinceSaveHint(saved, 9L * 60_000L).contains("before the saved endpoint"))
        assertFalse(captureSinceSaveEnabled(saved, checkpoint))
        assertTrue(captureSinceSaveHint(saved, checkpoint).contains("no later range"))
        assertTrue(captureSinceSaveEnabled(saved, 11L * 60_000L))

        // A live capture uses now as its cutoff; after a successful save, the new endpoint is
        // immediately reflected and does not leave the old preview eligible.
        val live = saved.copy(status = CaptureStatus.RECORDING)
        assertEquals(live.elapsedMs, captureSnapshotCutoffForChoice(live, SnapshotRangeChoice.SINCE_SAVE, 9L * 60_000L))
        val afterSave = saved.copy(snapshotCheckpointMs = 11L * 60_000L, videoCheckpointMs = 11L * 60_000L)
        assertFalse(captureSinceSaveEnabled(afterSave, 11L * 60_000L))
        assertTrue(captureSinceSaveEnabled(afterSave, 12L * 60_000L))
    }

    @Test
    fun equalLogEndpointAllowsOnlyARealIncludedVideoTail() {
        val end = 10L * 60_000L
        val videoTail = stopped.copy(snapshotCheckpointMs = end, videoCheckpointMs = 8L * 60_000L)
        assertTrue(captureSinceSaveEnabled(videoTail, end, includeVideo = true))
        assertFalse(captureSinceSaveEnabled(videoTail, end, includeVideo = false))
        assertTrue(captureSinceSaveHint(videoTail, end, includeVideo = true).contains("video remains unsaved"))

        val request = CaptureExportRequest(
            destination = File("preview.zip"),
            range = CaptureRange.SINCE_SAVE,
            includeVideo = true,
            cutoffElapsedMs = end,
        )
        val previewWithoutCoverage = CaptureExportPreview(null, null, null, 2_000, null, null, false, true)
        assertFalse(captureSinceSavePreviewHasContent(videoTail, request, previewWithoutCoverage))
        assertTrue(
            captureSinceSavePreviewHasContent(
                videoTail,
                request,
                previewWithoutCoverage.copy(videoCoveredEndMs = 9L * 60_000L, videoShortfallMs = 1_000L),
            ),
        )
    }

    @Test
    fun sinceSavePreviewAllowsAvailabilityOnlyEvidenceAndRejectsAnEmptyRange() {
        val session = stopped.copy(
            elapsedMs = 10_000,
            snapshotCheckpointMs = 2_000,
            videoCheckpointMs = 2_000,
            settings = CaptureSettings(recordVideo = false),
            availabilityIntervals = listOf(CaptureAvailabilityInterval(2_000, 5_000)),
        )
        val request = CaptureExportRequest(
            destination = File("metadata.zip"),
            range = CaptureRange.SINCE_SAVE,
            includeVideo = false,
            cutoffElapsedMs = 5_000,
        )
        val emptyPreview = CaptureExportPreview(null, null, null, null, null, null, false, false)
        assertTrue(captureSinceSavePreviewHasContent(session, request, emptyPreview))
        assertFalse(
            captureSinceSavePreviewHasContent(
                session.copy(availabilityIntervals = emptyList()),
                request,
                emptyPreview,
            ),
        )
        val oldVideoGap = session.copy(
            snapshotCheckpointMs = 5_000,
            videoCheckpointMs = 2_000,
            settings = CaptureSettings(recordVideo = true),
            availabilityIntervals = listOf(CaptureAvailabilityInterval(2_500, 4_000)),
        )
        assertFalse(
            captureSinceSavePreviewHasContent(
                oldVideoGap,
                request.copy(cutoffElapsedMs = 6_000),
                emptyPreview,
            ),
            "a log-only cut must not re-export availability metadata before the log cursor",
        )
        assertTrue(
            captureSinceSavePreviewHasContent(
                oldVideoGap,
                request.copy(includeVideo = true, cutoffElapsedMs = 6_000),
                emptyPreview,
            ),
            "including the lagging video cursor also includes its availability tail",
        )
    }

    @Test
    fun movingLiveSinceSavePreviewIsReusableButStoppedPlayheadAndCheckpointChangesInvalidateIt() {
        val live = stopped.copy(
            status = CaptureStatus.RECORDING,
            snapshotCheckpointMs = 5_000,
            videoCheckpointMs = 4_000,
        )
        val firstRequest = CaptureExportRequest(File("snapshot.zip"), CaptureRange.SINCE_SAVE, cutoffElapsedMs = 10_000)
        val laterLiveRequest = firstRequest.copy(cutoffElapsedMs = 11_000)
        assertEquals(captureExportPreviewIdentity(live, firstRequest), captureExportPreviewIdentity(live, laterLiveRequest))

        val stoppedAtFirstPlayhead = live.copy(status = CaptureStatus.STOPPED)
        assertNotEquals(
            captureExportPreviewIdentity(stoppedAtFirstPlayhead, firstRequest),
            captureExportPreviewIdentity(stoppedAtFirstPlayhead, laterLiveRequest),
        )
        assertNotEquals(
            captureExportPreviewIdentity(live, laterLiveRequest),
            captureExportPreviewIdentity(live.copy(snapshotCheckpointMs = 11_000), laterLiveRequest),
        )

        val aroundFirst = CaptureExportRequest(
            File("snapshot.zip"), CaptureRange.EXPLICIT, cutoffElapsedMs = 10_000,
            explicitStartElapsedMs = 5_000, explicitEndElapsedMs = 10_000,
        )
        val aroundLater = aroundFirst.copy(cutoffElapsedMs = 11_000, explicitEndElapsedMs = 11_000)
        assertEquals(
            captureExportPreviewIdentity(live, aroundFirst, SnapshotRangeChoice.AROUND_PLAYHEAD, "5/5"),
            captureExportPreviewIdentity(live, aroundLater, SnapshotRangeChoice.AROUND_PLAYHEAD, "5/5"),
        )
        assertNotEquals(
            captureExportPreviewIdentity(live, aroundLater, SnapshotRangeChoice.AROUND_PLAYHEAD, "5/5"),
            captureExportPreviewIdentity(live, aroundLater, SnapshotRangeChoice.AROUND_PLAYHEAD, "2/2"),
        )
        assertNotEquals(
            captureExportPreviewIdentity(live, aroundFirst, SnapshotRangeChoice.EXPLICIT, "5/5"),
            captureExportPreviewIdentity(live, aroundLater, SnapshotRangeChoice.EXPLICIT, "5/5"),
        )
    }
}
