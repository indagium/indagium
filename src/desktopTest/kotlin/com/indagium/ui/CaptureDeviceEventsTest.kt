package com.indagium.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.indagium.capture.CaptureAvailabilityInterval
import com.indagium.capture.CaptureMappingRow
import com.indagium.model.Filter
import com.indagium.model.IssueCategorySelection
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CaptureDeviceEventsTest {
    @Test
    fun eventPopupClampsAtWindowEdgesAndFlipsAboveItsTriggerWhenNeeded() {
        val position = CaptureDeviceEventsPopupPositionProvider(marginPx = 8, gapPx = 4).calculatePosition(
            anchorBounds = IntRect(700, 540, 740, 564),
            windowSize = IntSize(800, 600),
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = IntSize(372, 360),
        )

        assertEquals(IntOffset(420, 176), position)
    }

    @Test
    fun deviceEventsCategoryRoundTripsInExistingFilterPanelToken() {
        val selected = FilterPanelUiState().apply {
            crashCategory = IssueCategorySelection.DeviceEvents
        }
        val restored = FilterPanelUiState().apply {
            restoreFilterPanelToken(selected.filterPanelToken())
        }

        assertEquals(IssueCategorySelection.DeviceEvents, restored.crashCategory)
        val custom = FilterPanelUiState().apply {
            crashCategory = IssueCategorySelection.Custom("rule-id")
        }
        val restoredCustom = FilterPanelUiState().apply {
            restoreFilterPanelToken(custom.filterPanelToken())
        }
        assertEquals(IssueCategorySelection.Custom("rule-id"), restoredCustom.crashCategory)
        assertTrue(
            com.indagium.utils.issueSitesForCategory(
                crashSites = emptyList(),
                customSites = emptyList(),
                category = IssueCategorySelection.DeviceEvents,
            ).isEmpty(),
        )
    }

    @Test
    fun repeatedOutagesSummarizeOnlyStructuredRebootEvidence() {
        val intervals = listOf(
            CaptureAvailabilityInterval(1_000, 4_000, rebootConfirmed = true),
            CaptureAvailabilityInterval(7_000, 9_000, rebootConfirmed = false),
            CaptureAvailabilityInterval(12_000, 13_000, rebootConfirmed = null),
            CaptureAvailabilityInterval(15_000, null, rebootConfirmed = true),
        )

        assertEquals(
            CaptureDeviceEventSummary(disconnectCount = 4, confirmedRebootCount = 1, waitingCount = 1),
            captureDeviceEventSummary(intervals),
        )
        assertEquals(CaptureDeviceEventStatus.REBOOT_CONFIRMED, captureDeviceEventStatus(intervals[0]))
        assertEquals(CaptureDeviceEventStatus.SAME_BOOT, captureDeviceEventStatus(intervals[1]))
        assertEquals(CaptureDeviceEventStatus.REBOOT_UNKNOWN, captureDeviceEventStatus(intervals[2]))
        assertEquals(CaptureDeviceEventStatus.WAITING, captureDeviceEventStatus(intervals[3]))
    }

    @Test
    fun importedChildTimelineUsesRebasedRowsAndAbsoluteMarkerClock() {
        val intervals = listOf(CaptureAvailabilityInterval(500, 3_500, rebootConfirmed = true))
        val rows = listOf(CaptureMappingRow(4, 500, null), CaptureMappingRow(9, 3_500, null))
        val markers = listOf(
            // LogEntry IDs are intentionally unrelated to timeline ordinals. The raw elapsed value
            // is from the original capture and differs from this child's base+local elapsed.
            CaptureDeviceEventMarker(104, 4, CaptureDeviceEventMarkerKind.UNAVAILABLE, captureElapsedMs = 99_999),
            CaptureDeviceEventMarker(
                entryId = 908,
                rowOrdinal = 9,
                kind = CaptureDeviceEventMarkerKind.RECONNECTED,
                unavailableMs = 3_000,
                rebootEvidence = true,
                rebootEvidenceKnown = true,
            ),
        )

        assertEquals(
            listOf(CaptureDeviceEventLogTargets(unavailableEntryId = 104, reconnectedEntryId = 908)),
            captureDeviceEventLogTargets(
                intervals = intervals,
                timelineBaseElapsedMs = 10_000,
                timelineRows = rows,
                markers = markers,
            ),
        )
        assertEquals(10_500, captureDeviceEventSourceElapsedMs(500, 10_000))
        assertNull(captureDeviceEventSourceElapsedMs(Long.MAX_VALUE, 1))
    }

    @Test
    fun liveReconnectTargetRequiresCompleteOrderedMarkerSetAndMatchingEvidence() {
        val intervals = listOf(
            CaptureAvailabilityInterval(1_000, 3_000, rebootConfirmed = false),
            CaptureAvailabilityInterval(5_000, 9_000, rebootConfirmed = true),
        )
        val markers = listOf(
            CaptureDeviceEventMarker(20, 1, CaptureDeviceEventMarkerKind.UNAVAILABLE, captureElapsedMs = 1_000),
            CaptureDeviceEventMarker(
                30,
                2,
                CaptureDeviceEventMarkerKind.RECONNECTED,
                unavailableMs = 2_000,
                rebootEvidence = false,
                rebootEvidenceKnown = true,
            ),
            CaptureDeviceEventMarker(50, 3, CaptureDeviceEventMarkerKind.UNAVAILABLE, captureElapsedMs = 5_000),
            CaptureDeviceEventMarker(60, 4, CaptureDeviceEventMarkerKind.RECONNECTED, unavailableMs = 4_000, rebootEvidence = true, rebootEvidenceKnown = true),
        )

        assertEquals(
            listOf(CaptureDeviceEventLogTargets(20, 30), CaptureDeviceEventLogTargets(50, 60)),
            captureDeviceEventLogTargets(intervals, 0, emptyList(), markers),
        )
        assertEquals(
            listOf(CaptureDeviceEventLogTargets(20, null), CaptureDeviceEventLogTargets(50, null)),
            captureDeviceEventLogTargets(intervals, 0, emptyList(), markers.dropLast(1)),
        )
        val forged = markers.map { marker ->
            if (marker.entryId == 30) marker.copy(rebootEvidence = true) else marker
        }
        assertEquals(
            listOf(CaptureDeviceEventLogTargets(20, null), CaptureDeviceEventLogTargets(50, 60)),
            captureDeviceEventLogTargets(intervals, 0, emptyList(), forged),
        )
    }

    @Test
    fun parserAcceptsOnlyRecorderAvailabilityLinesAndKeepsRowPositionSeparateFromId() {
        assertEquals(
            CaptureDeviceEventMarker(
                entryId = 20,
                rowOrdinal = 4,
                kind = CaptureDeviceEventMarkerKind.UNAVAILABLE,
                captureElapsedMs = 9_876,
            ),
            parseCaptureDeviceEventMarker(marker(20, "Device unavailable; elapsedMs=9876"), rowOrdinal = 4),
        )
        assertEquals(
            CaptureDeviceEventMarker(
                entryId = 30,
                rowOrdinal = 9,
                kind = CaptureDeviceEventMarkerKind.RECONNECTED,
                unavailableMs = 1_234,
            ),
            parseCaptureDeviceEventMarker(marker(30, "Device reconnected; unavailableMs=1234; reboot=unknown"), rowOrdinal = 9),
        )
        assertNull(parseCaptureDeviceEventMarker(marker(40, "Device rebooted; reboot=true"), rowOrdinal = 10))
        assertNull(parseCaptureDeviceEventMarker(marker(50, "Device unavailable; elapsedMs=bad"), rowOrdinal = 11))
        assertNull(parseCaptureDeviceEventMarker(marker(60, "Device unavailable; elapsedMs=1", tag = "App"), rowOrdinal = 12))
        assertNull(parseCaptureDeviceEventMarker(marker(70, "Device unavailable; elapsedMs=1").copy(pid = 123), rowOrdinal = 13))
        assertNull(parseCaptureDeviceEventMarker(marker(80, "Device unavailable; elapsedMs=1").copy(tid = 123), rowOrdinal = 14))
        assertNull(parseCaptureDeviceEventMarker(marker(90, "Device unavailable; elapsedMs=1").copy(level = LogLevel.I), rowOrdinal = 15))
    }

    @Test
    fun timelineLookupAndPrefixChecksAvoidAssumingLogEntryIdsAreOrdinals() {
        val rows = (1..10_000).map { ordinal -> CaptureMappingRow(ordinal, ordinal * 10L, null) }
        assertEquals(CaptureMappingRow(9_999, 99_990, null), timelineRowAtOrdinal(rows, 9_999))
        assertNull(timelineRowAtOrdinal(rows, 10_001))

        val source = (1..10_000).map { marker(it * 10, "ordinary row $it") }
        val samples = captureDeviceEventPrefixSamples(source, source.size)
        assertEquals(true, captureDeviceEventPrefixUnchanged(source + marker(999_999, "appended"), source.size, samples))
        val replacedAtSample = source.toMutableList().also { it[7_500] = marker(-50, "reloaded row") }
        assertEquals(false, captureDeviceEventPrefixUnchanged(replacedAtSample + marker(999_999, "appended"), source.size, samples))
    }

    @Test
    fun unavailableJumpExplainsWhenMarkersWereDisabled() {
        assertEquals(
            "Availability log markers were disabled for this capture; event timing is still recorded here.",
            captureDeviceEventMarkerHint(markerEnabled = false, unresolvedTransitions = 1),
        )
        assertEquals(
            "No matching transition marker is loaded, so an exact log jump is unavailable for this event.",
            captureDeviceEventMarkerHint(markerEnabled = null, unresolvedTransitions = 1),
        )
        assertNull(captureDeviceEventMarkerHint(markerEnabled = true, unresolvedTransitions = 0))
    }

    @Test
    fun eventJumpPreservesFilterAndDoesNotRevealOriginalPane() {
        val filter = Filter(activeTags = setOf("KeepThisFilter"))
        val entry = marker(20, "Device unavailable; elapsedMs=1000")
        val tab = LogTab(
            id = "capture",
            filename = "capture.log",
            logData = listOf(entry),
            rmap = mapOf(entry.id to entry),
            filter = filter,
        )

        assertFalse(captureDeviceEventTargetPassesCurrentFilter(tab, entry.id))
        assertTrue(captureDeviceEventTargetPassesCurrentFilter(tab.copy(filter = Filter()), entry.id))
        assertFalse(tab.showUnfiltered)
        assertSame(filter, tab.filter)

        val directory = createTempDirectory("capture-device-events").toFile()
        val state = AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archive"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        ).also { it.tabs = listOf(tab) }
        try {
            val allowedTab = tab.copy(filter = Filter())
            state.tabs = listOf(allowedTab)
            assertTrue(navigateToCaptureDeviceEventEntry(state, tab.id, entry.id))

            val updated = requireNotNull(state.tab(tab.id))
            assertFalse(updated.showUnfiltered)
            assertSame(allowedTab.filter, updated.filter)
            state.tabs = listOf(tab)
            assertFalse(navigateToCaptureDeviceEventEntry(state, tab.id, entry.id))
            val filtered = requireNotNull(state.tab(tab.id))
            assertFalse(filtered.showUnfiltered)
            assertSame(filter, filtered.filter)
            assertEquals(emptySet(), filtered.selected)
        } finally {
            state.close()
            directory.deleteRecursively()
        }
    }

    private fun marker(id: Int, message: String, tag: String = "IndagiumCapture") =
        LogEntry(id, "10-15 09:00:00.000", LogLevel.W, tag, message)
}
