package com.indagium.ui

import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureSession
import com.indagium.capture.CaptureSettings
import com.indagium.capture.RecorderSnapshot
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun selectedCaptureOrdinalsUseActualRowPositionsAndIgnoreStaleIds() {
        val rows = (10..14).map { id ->
            LogEntry(id, "ts", LogLevel.I, "tag", "row $id")
        }
        val tab = LogTab("capture", "capture", rows, rows.associateBy { it.id }, selected = setOf(11, 14, 999))

        assertEquals(2..5, selectedCaptureOrdinals(tab))
        assertNull(selectedCaptureOrdinals(tab.copy(selected = setOf(999))))
    }
}
