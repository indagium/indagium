package com.indagium.ui

import com.indagium.capture.CaptureDevice
import com.indagium.capture.CaptureRange
import com.indagium.model.LogTab
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceAiCaptureSupportTest {
    @Test
    fun deviceSelectionNeverSilentlyPicksBetweenSeveralReadyDevices() {
        val first = CaptureDevice("emulator-5554", "device", "Pixel_8_API_35")
        val second = CaptureDevice("R58N", "device", "Galaxy")

        assertEquals(AiCaptureDeviceChoice.NeedsSelection(listOf(first, second)), resolveAiCaptureDevice(listOf(first, second), null, null, false))
        assertEquals(AiCaptureDeviceChoice.Selected(first), resolveAiCaptureDevice(listOf(first, second), null, first.serial, false))
        assertEquals(AiCaptureDeviceChoice.Selected(first), resolveAiCaptureDevice(listOf(first), null, null, false))
        assertEquals(AiCaptureDeviceChoice.Selected(first), resolveAiCaptureDevice(listOf(first, second), first.serial, null, false))
        assertEquals(
            AiCaptureDeviceChoice.LiveCaptureDeviceConflict(first.serial),
            resolveAiCaptureDevice(listOf(first, second), first.serial, second.serial, false),
        )
        assertEquals(AiCaptureDeviceChoice.Selected(second), resolveAiCaptureDevice(listOf(first, second), first.serial, second.serial, true))
        assertEquals(AiCaptureDeviceChoice.Unavailable("gone"), resolveAiCaptureDevice(listOf(first), null, "gone", false))
    }

    @Test
    fun aFreshCaptureRequiresSelectionWhenSeveralDevicesAreReady() {
        val first = CaptureDevice("emulator-5554", "device", "Pixel")
        val second = CaptureDevice("R58N", "device", "Galaxy")

        assertEquals(
            AiCaptureDeviceChoice.Selected(first),
            resolveAiCaptureDevice(listOf(first, second), first.serial, null, newCapture = false),
        )
        assertEquals(
            AiCaptureDeviceChoice.NeedsSelection(listOf(first, second)),
            resolveAiCaptureDevice(listOf(first, second), first.serial, null, newCapture = true),
        )
        assertEquals(
            AiCaptureDeviceChoice.Selected(first),
            resolveAiCaptureDevice(listOf(first), first.serial, null, newCapture = true),
        )
        assertEquals(
            AiCaptureDeviceChoice.NeedsSelection(listOf(first, second)),
            resolveAiCaptureDevice(listOf(first, second), null, null, newCapture = false),
        )
    }

    @Test
    fun snapshotFilenamesDoNotOverwriteExistingArchives() {
        val directory = createTempDirectory("indagium-device-ai-snapshot").toFile()
        val original = File(directory, "capture.zip").apply { writeText("existing evidence") }

        val destination = uniqueSnapshotDestination(directory, "capture.zip")

        assertEquals("capture-2.zip", destination.name)
        assertFalse(destination.exists())
        assertEquals("existing evidence", original.readText())
        assertEquals("another.tar.gz", uniqueSnapshotDestination(directory, "another.tar.gz").name)
    }

    @Test
    fun exportWaitsForMarkerAndRefusesToHideMarkerFailure() = runBlocking {
        val barrier = DeviceAiMarkerBarrier()
        barrier.register("capture-tab", "marker-1")
        val export = async { barrier.awaitAndConsume("capture-tab") }
        delay(30)
        assertFalse(export.isCompleted, "export must wait for the marker's screenshot/evidence write")

        barrier.complete("capture-tab", "marker-1", succeeded = true)
        assertTrue(export.await())
        assertTrue(barrier.awaitAndConsume("capture-tab"), "a consumed completed barrier leaves later exports ready")

        barrier.register("capture-tab", "marker-2")
        barrier.complete("capture-tab", "marker-2", succeeded = false)
        assertFalse(barrier.awaitAndConsume("capture-tab"), "failed marker evidence must prevent the snapshot export")
    }

    @Test
    fun rangeResolutionMapsThePopoverPresetsAndRejectsUnknownRanges() {
        assertEquals(CaptureRange.ALL, resolveCaptureSnapshotRangeForAi(null, null))
        assertEquals(CaptureRange.ALL, resolveCaptureSnapshotRangeForAi("", null))
        assertEquals(CaptureRange.ALL, resolveCaptureSnapshotRangeForAi("all", null))
        assertEquals(CaptureRange.LAST_FIVE, resolveCaptureSnapshotRangeForAi("last_minutes", null))
        assertEquals(CaptureRange.LAST_FIVE, resolveCaptureSnapshotRangeForAi("last_minutes", 5))
        assertEquals(CaptureRange.LAST_TEN, resolveCaptureSnapshotRangeForAi("last_minutes", 10))
        assertEquals(CaptureRange.CUSTOM, resolveCaptureSnapshotRangeForAi("last_minutes", 7))
        assertEquals(CaptureRange.SINCE_SAVE, resolveCaptureSnapshotRangeForAi("since_last_save", null))
        assertEquals(CaptureRange.SELECTION, resolveCaptureSnapshotRangeForAi("SELECTION", null))
        assertFailsWith<IllegalStateException> { resolveCaptureSnapshotRangeForAi("bogus", null) }
    }

    @Test
    fun buildCaptureSnapshotExportRequestCarriesTheTabsCurrentSelection() {
        val rows = (10..14).map { id -> com.indagium.model.LogEntry(id, "ts", com.indagium.model.LogLevel.I, "tag", "row $id") }
        val selectedTab = LogTab("capture", "capture", rows, rows.associateBy { it.id }, selected = setOf(11, 13))
        val destination = File("capture.zip")

        val withSelection = buildCaptureSnapshotExportRequest(
            tab = selectedTab,
            destination = destination,
            range = CaptureRange.SELECTION,
            customMinutes = 5,
            includeVideo = true,
            cutoffElapsedMs = 12_000,
            overwriteExisting = false,
        )
        assertEquals(2, withSelection.selectedFirstRowOrdinal)
        assertEquals(4, withSelection.selectedLastRowOrdinal)
        assertEquals(12_000, withSelection.cutoffElapsedMs)
        assertEquals(destination, withSelection.destination)

        val withoutSelection = buildCaptureSnapshotExportRequest(
            tab = selectedTab.copy(selected = emptySet()),
            destination = destination,
            range = CaptureRange.ALL,
            customMinutes = 5,
            includeVideo = false,
            cutoffElapsedMs = 0,
            overwriteExisting = false,
        )
        assertNull(withoutSelection.selectedFirstRowOrdinal)
        assertNull(withoutSelection.selectedLastRowOrdinal)
    }

    @Test
    fun markerBarrierWaitsForEveryConcurrentMarkerBeforeReturningFailure() = runBlocking {
        val barrier = DeviceAiMarkerBarrier()
        barrier.register("tab", "failed")
        barrier.register("tab", "pending")
        barrier.complete("tab", "failed", succeeded = false)
        val export = async { barrier.awaitAndConsume("tab") }
        delay(30)
        assertFalse(export.isCompleted, "export waits for all in-flight marker screenshots, even after one fails")
        barrier.complete("tab", "pending", succeeded = true)
        assertFalse(withTimeout(1_000) { export.await() })
    }
}
