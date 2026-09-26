package com.indagium.ui

import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.RecorderSnapshot
import com.indagium.model.AnnBlock
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureStripTest {
    @Test
    fun elapsedFormattingUsesCompactClockAndClampsNegativeValues() {
        assertEquals("00:00", formatCaptureElapsed(0))
        assertEquals("01:05", formatCaptureElapsed(65_400))
        assertEquals("1:01:05", formatCaptureElapsed(3_665_000))
        assertEquals("00:00", formatCaptureElapsed(-1))
    }

    @Test
    fun storageFormattingUsesReadableBinaryUnits() {
        assertEquals("512 B", formatCaptureBytes(512))
        assertEquals("1.0 KiB", formatCaptureBytes(1024))
        assertEquals("1.0 MiB", formatCaptureBytes(1024L * 1024L))
    }

    @Test
    fun videoStatusDistinguishesRecordingConfiguredAndDisabled() {
        assertEquals("Video off", captureVideoStatus(RecorderSnapshot()))
        val session = CaptureSession(
            id = "session",
            directory = File("capture"),
            device = CaptureDevice("serial", "device"),
            settings = CaptureSettings(recordVideo = true),
            startedEpochMs = 0,
        )
        assertEquals("Video idle", captureVideoStatus(RecorderSnapshot(session = session)))
        assertEquals("Video REC", captureVideoStatus(RecorderSnapshot(session = session, videoRecording = true)))
    }

    @Test
    fun sinceSaveIsDisabledUntilARealCheckpointAndThenShowsItsAge() {
        val session = CaptureSession(
            id = "session",
            directory = File("capture"),
            device = CaptureDevice("serial", "device"),
            settings = CaptureSettings(),
            startedEpochMs = 0,
            elapsedMs = 5_000,
        )
        assertTrue(!captureSinceSaveEnabled(session))
        assertTrue(captureSinceSaveHint(session).contains("first successful snapshot"))

        val saved = session.copy(snapshotCheckpointMs = 2_000)
        assertTrue(captureSinceSaveEnabled(saved))
        assertEquals("Last save 00:03 ago.", captureSinceSaveHint(saved))
    }

    @Test
    fun storageMeterFractionClampsAndTreatsMissingLimitAsEmpty() {
        assertEquals(0f, captureStorageMeterFraction(0, 1_000))
        assertEquals(0.5f, captureStorageMeterFraction(500, 1_000))
        assertEquals(1f, captureStorageMeterFraction(1_500, 1_000))
        assertEquals(0f, captureStorageMeterFraction(500, null))
        assertEquals(0f, captureStorageMeterFraction(500, 0))
    }

    @Test
    fun storageMeterWarnsAtEightyFivePercentOfTheSessionLimit() {
        assertTrue(!captureStorageMeterIsWarn(840, 1_000))
        assertTrue(captureStorageMeterIsWarn(850, 1_000))
        assertTrue(captureStorageMeterIsWarn(1_000, 1_000))
        assertTrue(!captureStorageMeterIsWarn(850, null))
    }

    @Test
    fun selectedCaptureOrdinalsUseActualRowPositionsAndIgnoreStaleIds() {
        val rows = (10..14).map { id ->
            LogEntry(id, "ts", LogLevel.I, "tag", "row $id")
        }
        val tab = LogTab("capture", "capture", rows, rows.associateBy { it.id }, selected = setOf(11, 14, 999))

        assertEquals(2..5, selectedCaptureOrdinals(tab))
        assertNull(selectedCaptureOrdinals(tab.copy(selected = setOf(999))))
    }

    @Test
    fun deletingAMarkerTakesItsOwnScreenshotAndExcerptOnly() {
        val blocks = listOf(
            AnnBlock.Note("n1", "marker 1"),
            AnnBlock.Image("i1", caption = "", provenance = "", format = "png", bytes = ByteArray(0)),
            AnnBlock.Image("i1b", caption = "", provenance = "", format = "png", bytes = ByteArray(0)),
            AnnBlock.LogRef("r1", logIds = listOf(1), caption = ""),
            AnnBlock.Note("n2", "marker 2, still collecting"),
            AnnBlock.Note("n3", "plain note"),
            AnnBlock.LogRef("r3", logIds = listOf(2), caption = ""),
        )
        // Only the first Image/LogRef before the next Note — exactly what the row displays.
        assertEquals(listOf("n1", "i1", "r1"), markerOwnedBlockIds(blocks, "n1"))
        // Its excerpt hasn't arrived yet: the next note's blocks are never swept in.
        assertEquals(listOf("n2"), markerOwnedBlockIds(blocks, "n2"))
        assertTrue(markerOwnedBlockIds(blocks, "missing").isEmpty())
    }

    @Test
    fun followBadgeTooltipReflectsTheGlobalSettingBeforeTheRawPerPanelState() {
        assertEquals(
            "Auto-scroll while tailing is off in Settings; this only pre-arms the filtered pane.",
            followBadgeTooltip(autoScrollEnabled = false, following = true, paneName = "filtered"),
        )
        assertEquals(
            "Following the newest unfiltered line. Click to stop.",
            followBadgeTooltip(autoScrollEnabled = true, following = true, paneName = "unfiltered"),
        )
        assertEquals(
            "Not following. Click to jump to the newest filtered line.",
            followBadgeTooltip(autoScrollEnabled = true, following = false, paneName = "filtered"),
        )
    }
}
