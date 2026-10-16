package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.capture.CaptureAvailabilityInterval
import com.indagium.capture.CaptureMappingRow
import com.indagium.capture.CaptureTimeline
import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.CrashKind
import com.indagium.model.CrashSite
import com.indagium.model.CrashCategory
import com.indagium.model.IssueCategorySelection
import com.indagium.model.LogAnalysis
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalTestApi::class)
class CaptureDeviceEventsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun saveEvidenceScreenshot(name: String, image: ImageBitmap) {
        val directory = File("build/verification/user-followup-20261016").apply { mkdirs() }
        val pixels = image.toPixelMap()
        val rendered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                rendered.setRGB(x, y, pixels[x, y].toArgb())
            }
        }
        check(ImageIO.write(rendered, "png", File(directory, name))) {
            "No PNG writer is available for UI evidence screenshots"
        }
    }

    @Test
    fun deviceEventsCategoryShowsCompactRowsAndFilteredJumpKeepsViewState() {
        val directory = createTempDirectory("capture-device-events-ui").toFile()
        val filter = Filter(mode = FilterMode.TAGS, activeTags = setOf("Application"))
        val issueEntry = LogEntry(10, "10-15 09:00:00.500", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main")
        val rows = listOf(
            issueEntry,
            LogEntry(11, "10-15 09:00:00.000", LogLevel.I, "Application", "before outage"),
            LogEntry(104, "10-15 09:00:01.000", LogLevel.W, "IndagiumCapture", "Device unavailable; elapsedMs=11000"),
            LogEntry(27, "10-15 09:00:02.000", LogLevel.I, "Application", "middle"),
            LogEntry(35, "10-15 09:00:03.000", LogLevel.I, "Application", "middle"),
            LogEntry(48, "10-15 09:00:04.000", LogLevel.I, "Application", "middle"),
            LogEntry(59, "10-15 09:00:05.000", LogLevel.I, "Application", "middle"),
            LogEntry(199, "10-15 09:00:06.000", LogLevel.W, "IndagiumCapture", "Device reconnected; unavailableMs=3000; reboot=true"),
        )
        val timeline = CaptureTimeline(
            rows = listOf(
                CaptureMappingRow(3, 1_000, null),
                CaptureMappingRow(8, 4_000, null),
            ),
            availabilityIntervals = listOf(CaptureAvailabilityInterval(1_000, 4_000, rebootConfirmed = true)),
            timelineBaseElapsedMs = 10_000,
            timelineDurationMs = 5_000,
        )
        val tab = LogTab(
            id = "imported-capture",
            filename = "capture.log",
            logData = rows,
            rmap = rows.associateBy(LogEntry::id),
            filter = filter,
            analysis = LogAnalysis(
                crashSites = listOf(
                    CrashSite("crash-10", issueEntry, CrashKind.EXCEPTION, groupGid = null, isFatal = true),
                ),
                pending = false,
            ),
            captureArchivePath = "capture.zip",
            captureTimeline = timeline,
        )
        val state = AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archive"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        ).also { it.tabs = listOf(tab) }
        state.fpState.apply {
            highlightersExpanded = false
            lvlExpanded = false
            seqExpanded = false
            sfExpanded = false
            crashExpanded = true
        }
        assertFalse(captureDeviceEventTargetPassesCurrentFilter(tab, 199))

        try {
            rule.setContent {
                CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                    val events = rememberCaptureDeviceEvents(state, tab)
                    Column(Modifier.size(360.dp, 900.dp)) {
                        BoundFilterPanel(
                            state = state,
                            tab = tab,
                            width = 320f,
                            liveMaxWidth = { 320f },
                            captureDeviceEvents = events,
                        )
                    }
                }
            }

            rule.onNodeWithText("Issues").assertExists()
            assertFalse(rule.onAllNodesWithText("Device 1").fetchSemanticsNodes().isNotEmpty())
            rule.onNodeWithTag("issue-category-dropdown-trigger").performClick()
            val deviceEventsChoice = rule.onNodeWithTag("issue-category-device-events")
            deviceEventsChoice.assertIsDisplayed()
            rule.onNodeWithTag("issue-category-device-events-count", useUnmergedTree = true).assertTextEquals("1")
            deviceEventsChoice.performClick()
            rule.onNodeWithText("Events detected by Indagium during capture").assertExists()
            rule.waitUntil(5_000) {
                rule.onAllNodesWithText("Jump to reconnect line").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(IssueCategorySelection.DeviceEvents, state.fpState.crashCategory)
            rule.onNodeWithText("Reboot confirmed").assertExists()
            rule.onNodeWithText("00:01").assertExists()
            saveEvidenceScreenshot("device-events-category.png", rule.onRoot().captureToImage())
            val reconnectAction = rule.onNodeWithText("Jump to reconnect line")
            reconnectAction.performScrollTo()
            rule.waitForIdle()
            reconnectAction.assertIsDisplayed().performClick()
            val filteredHint = rule.onNodeWithText(CAPTURE_DEVICE_EVENT_FILTERED_HINT)
            filteredHint.performScrollTo()
            rule.waitForIdle()
            filteredHint.assertIsDisplayed()

            val unchanged = requireNotNull(state.tab(tab.id))
            assertFalse(unchanged.showUnfiltered)
            assertSame(filter, unchanged.filter)
            assertEquals(emptySet(), unchanged.selected)

            // The event model is now demonstrably loaded (the selected category rendered the
            // reconnect jump). Switch back to All only after that point so this guards against
            // events leaking into a still-initializing All-category view.
            rule.onNodeWithTag("issue-category-dropdown-trigger").performClick()
            rule.onNodeWithText("All").performClick()
            rule.waitUntil(5_000) {
                state.fpState.crashCategory == IssueCategorySelection.BuiltIn(CrashCategory.ALL)
            }
            rule.waitForIdle()
            rule.onNodeWithText("FATAL EXCEPTION: main").assertExists()
            rule.onNodeWithTag("issues-header-count", useUnmergedTree = true).assertTextEquals("1")
            assertFalse(rule.onAllNodesWithText("Events detected by Indagium during capture").fetchSemanticsNodes().isNotEmpty())
            assertTrue(rule.onAllNodesWithTag("capture-device-event-row-0").fetchSemanticsNodes().isEmpty())
            saveEvidenceScreenshot("issues-all-no-device-events.png", rule.onRoot().captureToImage())
        } finally {
            state.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun captureEventsPopupStaysOnItsPillAndFilteredClickDismissesWithoutBeingIntercepted() {
        val directory = createTempDirectory("capture-device-events-popup-ui").toFile()
        val interval = CaptureAvailabilityInterval(1_000, 4_000, rebootConfirmed = true)
        val events = CaptureDeviceEventsModel(
            intervals = listOf(interval),
            timelineBaseElapsedMs = 0,
            timelineDurationMs = 5_000,
            timelineRows = emptyList(),
            targets = listOf(CaptureDeviceEventLogTargets()),
            summary = CaptureDeviceEventSummary(disconnectCount = 1, confirmedRebootCount = 1, waitingCount = 0),
            markerEnabled = true,
        )
        val tab = LogTab(
            id = "popup-capture",
            filename = "capture.log",
            logData = emptyList(),
            rmap = emptyMap(),
        )
        val state = AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archive"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        ).also { it.tabs = listOf(tab) }
        val filteredActions = AtomicInteger()
        try {
            rule.setContent {
                CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                    Column(Modifier.size(640.dp, 500.dp).testTag("capture-events-test-content")) {
                        Spacer(Modifier.height(60.dp))
                        Row(Modifier.padding(start = 120.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            CaptureDeviceEventsDropdown(state, tab, events, onReturnFocus = {})
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier.size(width = 82.dp, height = 24.dp)
                                    .clickable { filteredActions.incrementAndGet() },
                                contentAlignment = androidx.compose.ui.Alignment.Center,
                            ) {
                                AppText("Filtered", fontSize = 10.sp)
                            }
                        }
                    }
                }
            }

            rule.onNodeWithTag("capture-device-events-trigger").performClick()
            val popup = rule.onNodeWithTag("capture-device-events-popup")
            popup.assertIsDisplayed()
            val triggerBounds = rule.onNodeWithTag("capture-device-events-trigger").fetchSemanticsNode().boundsInRoot
            val popupBounds = popup.fetchSemanticsNode().boundsInRoot
            assertTrue(kotlin.math.abs(popupBounds.left - triggerBounds.left) < 2f)
            assertTrue(popupBounds.top >= triggerBounds.bottom)
            saveEvidenceScreenshot("capture-events-popup.png", popup.captureToImage())
            // Keep the trigger and neighboring control in a separate screenshot even on
            // Compose Desktop versions where Popup content is captured in its own window.
            saveEvidenceScreenshot(
                "capture-events-popup-trigger-context.png",
                rule.onNodeWithTag("capture-events-test-content").captureToImage(),
            )

            val filteredButton = rule.onNodeWithText("Filtered")
            filteredButton.performMouseInput { moveTo(center) }
            popup.assertIsDisplayed()
            filteredButton.performClick()
            rule.waitUntil(5_000) {
                rule.onAllNodesWithTag("capture-device-events-popup").fetchSemanticsNodes().isEmpty()
            }
            rule.runOnIdle {
                // The filter action remains a single click target even while the event popup is open.
                assertEquals(1, filteredActions.get())
            }

            // Let the trigger's same-click suppression window elapse before reopening it.
            val reopenAfterMs = System.currentTimeMillis() + 250
            rule.waitUntil(2_000) { System.currentTimeMillis() >= reopenAfterMs }
            val trigger = rule.onNodeWithTag("capture-device-events-trigger")
            trigger.performClick()
            rule.onAllNodesWithTag("capture-device-events-popup").also {
                rule.waitUntil(5_000) { it.fetchSemanticsNodes().isNotEmpty() }
            }
            trigger.performKeyInput { pressKey(Key.Escape) }
            rule.waitUntil(5_000) {
                rule.onAllNodesWithTag("capture-device-events-popup").fetchSemanticsNodes().isEmpty()
            }
        } finally {
            state.close()
            directory.deleteRecursively()
        }
    }
}
