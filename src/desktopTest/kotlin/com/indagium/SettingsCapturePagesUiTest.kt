package com.indagium

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.indagium.capture.CaptureBufferMode
import com.indagium.capture.CaptureMirrorMode
import com.indagium.capture.CaptureSettings
import com.indagium.capture.CaptureVideoContainer
import com.indagium.model.AppSettings
import com.indagium.ui.AppState
import com.indagium.ui.CapturePageTags
import com.indagium.ui.SettingsAnchorKey
import com.indagium.ui.SettingsDialog
import com.indagium.ui.SettingsPage
import com.indagium.ui.captureFieldTag
import com.indagium.ui.invalidCaptureSettings
import com.indagium.ui.isMacOs
import com.indagium.ui.settingsNavTag
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Capture & video page of the Settings dialog and its four drill-in pages: Tools, Limits and snapshots, Mark issue, Video playback. */
@OptIn(ExperimentalTestApi::class)
class SettingsCapturePagesUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val subPages = listOf(
        SettingsPage.CaptureTools,
        SettingsPage.CaptureLimits,
        SettingsPage.CaptureMarkIssue,
        SettingsPage.CaptureVideoPlayback,
    )

    private fun anchorsOnScreen(): Set<String> = rule
        .onAllNodes(SemanticsMatcher.keyIsDefined(SettingsAnchorKey), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .map { it.config[SettingsAnchorKey] }
        .toSet()

    private fun openCapturePage(state: AppState) {
        rule.setContent { SettingsDialog(state, onDismiss = {}) }
        rule.onNodeWithTag(settingsNavTag(SettingsPage.CaptureVideo)).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun drillInto(page: SettingsPage) {
        rule.onNodeWithTag(CapturePageTags.drillRow(page)).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun click(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun capture(state: AppState): CaptureSettings = state.settings.captureSettings

    /** Clicks the switch [tag] and checks that [read] flipped, then clicks it back and checks that it returned. */
    private fun assertSwitchFlips(state: AppState, tag: String, read: (AppSettings) -> Boolean) {
        val before = read(state.settings)
        if (before) rule.onNodeWithTag(tag).performScrollTo().assertIsOn() else rule.onNodeWithTag(tag).performScrollTo().assertIsOff()
        click(tag)
        assertEquals(!before, read(state.settings), "$tag flips the setting")
        if (before) rule.onNodeWithTag(tag).assertIsOff() else rule.onNodeWithTag(tag).assertIsOn()
        click(tag)
        assertEquals(before, read(state.settings), "$tag flips it back")
    }

    @Test
    fun theMainPageHoldsTheRecordingAndLogcatDefaultsAndFourDrillRows() {
        val state = AppState()
        openCapturePage(state)

        listOf("Recording", "Logcat", "Device availability", "More").forEach { rule.onNodeWithText(it).assertExists() }
        listOf("Record the screen", "Video quality", "Recording codec", "Sound", "Microphone", "Show the device", "Buffers", "Include earlier device logs")
            .forEach { rule.onNodeWithText(it).assertExists() }
        subPages.forEach { page ->
            rule.onNodeWithTag(CapturePageTags.drillRow(page)).performScrollTo().assertExists()
            rule.onNodeWithText(page.title).assertExists()
        }
        // What moved to a drill-in page is no longer on the main page.
        val shown = anchorsOnScreen()
        listOf("ADB path", "Max FPS", "Before, seconds", "Video follow readout", "Live audio volume %").forEach {
            assertFalse(it in shown, "$it is on a drill-in page")
        }
        assertEquals(listOf("Tools", "Limits and snapshots", "Mark issue", "Video playback"), subPages.map { it.title })
        assertTrue(subPages.all { it.parent == SettingsPage.CaptureVideo })
    }

    @Test
    fun eachDrillRowOpensItsPageAndTheBackLinkReturnsToCaptureAndVideo() {
        val state = AppState()
        openCapturePage(state)
        val firstAnchor = mapOf(
            SettingsPage.CaptureTools to "ADB path",
            SettingsPage.CaptureLimits to "Max FPS",
            SettingsPage.CaptureMarkIssue to "Before, seconds",
            SettingsPage.CaptureVideoPlayback to "Video follow readout",
        )
        for (page in subPages) {
            drillInto(page)
            assertTrue(firstAnchor.getValue(page) in anchorsOnScreen(), "${page.title} shows ${firstAnchor.getValue(page)}")
            rule.onNodeWithText("‹ Capture & video").assertExists()
            rule.onNodeWithText("Record the screen").assertDoesNotExist()

            rule.onNodeWithText("‹ Capture & video").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Record the screen").assertExists()
            rule.onNodeWithText("‹ Capture & video").assertDoesNotExist()
        }
    }

    @Test
    fun theNavKeepsCaptureAndVideoHighlightedOnASubPageAndHasNoItemOfItsOwn() {
        val state = AppState()
        openCapturePage(state)
        rule.onNodeWithTag(settingsNavTag(SettingsPage.CaptureVideo)).assertIsSelected()

        drillInto(SettingsPage.CaptureLimits)
        rule.onNodeWithTag(settingsNavTag(SettingsPage.CaptureVideo)).assertIsSelected()
        rule.onNodeWithTag(settingsNavTag(SettingsPage.General)).assertIsNotSelected()
        subPages.forEach { rule.onNodeWithTag(settingsNavTag(it)).assertDoesNotExist() }

        // The parent's own nav item leaves the sub-page the way a back link does.
        rule.onNodeWithTag(settingsNavTag(SettingsPage.CaptureVideo)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Record the screen").assertExists()
    }

    @Test
    fun aSearchResultOpensTheSubPageDirectlyAndItsRowExists() {
        val state = AppState()
        rule.setContent { SettingsDialog(state, onDismiss = {}) }
        val cases = listOf(
            Triple("Bitrate", "Bitrate Mbps", "Bitrate Mbps"),
            Triple("screenshot on mark", "Take a screenshot on Mark issue", "Take a screenshot on Mark issue"),
            Triple("double-click", "New video links: double-click seeks", "New video links:\ndouble-click seeks"),
        )
        for ((query, resultLabel, anchor) in cases) {
            rule.onNodeWithTag("settings-search-field").performTextInput(query)
            rule.waitForIdle()
            // The Capture & video nav item stands for its sub-pages: it is not dimmed and its count includes their results.
            rule.onNodeWithTag(settingsNavTag(SettingsPage.CaptureVideo)).assertExists()
            rule.onNodeWithText(resultLabel).performClick()
            rule.waitForIdle()
            assertTrue(anchor in anchorsOnScreen(), "$anchor is on the page the result opened")
            rule.onNodeWithText("‹ Capture & video").assertExists()
            rule.onNodeWithTag(settingsNavTag(SettingsPage.CaptureVideo)).assertIsSelected()
            rule.onNodeWithText("‹ Capture & video").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Record the screen").assertExists()
        }
    }

    @Test
    fun theMainPagesSwitchesFlipTheirSettings() {
        val state = AppState()
        openCapturePage(state)
        assertSwitchFlips(state, CapturePageTags.RECORD_SCREEN) { it.captureSettings.recordVideo }
        assertSwitchFlips(state, CapturePageTags.EARLIER_LOGS) { it.captureSettings.includeBufferedLogs }
        assertSwitchFlips(state, CapturePageTags.AVAILABILITY_MARKERS) { it.captureSettings.writeDeviceAvailabilityMarkers }
        assertSwitchFlips(state, CapturePageTags.UNAVAILABLE_FRAMES) { it.captureSettings.showUnavailableFrames }
    }

    @Test
    fun soundIsTwoCheckboxesAndKeepSoundWaitsForCaptureAudio() {
        val state = AppState()
        openCapturePage(state)
        assertFalse(capture(state).audio)
        rule.onNodeWithTag(CapturePageTags.CAPTURE_AUDIO).performScrollTo().assertIsOff()
        rule.onNodeWithTag(CapturePageTags.KEEP_DEVICE_AUDIO).performScrollTo().assertIsNotEnabled()

        click(CapturePageTags.CAPTURE_AUDIO)
        assertTrue(capture(state).audio)
        rule.onNodeWithTag(CapturePageTags.CAPTURE_AUDIO).assertIsOn()
        rule.onNodeWithTag(CapturePageTags.KEEP_DEVICE_AUDIO).assertIsEnabled()

        click(CapturePageTags.KEEP_DEVICE_AUDIO)
        assertTrue(capture(state).keepDeviceAudio)
        rule.onNodeWithTag(CapturePageTags.KEEP_DEVICE_AUDIO).assertIsOn()
    }

    @Test
    fun theQualityRowsFollowRecordTheScreenAndTheCustomBufferListFollowsTheBufferMode() {
        val state = AppState()
        state.updateSettings { it.copy(captureSettings = it.captureSettings.copy(buffers = emptyList())) }
        openCapturePage(state)
        rule.onNodeWithText("Video quality").assertExists()
        rule.onNodeWithText("Recording codec").assertExists()
        rule.onNodeWithText("Buffers to capture").assertDoesNotExist()

        click(CapturePageTags.RECORD_SCREEN)
        assertFalse(capture(state).recordVideo)
        rule.onNodeWithText("Video quality").assertDoesNotExist()
        rule.onNodeWithText("Recording codec").assertDoesNotExist()
        click(CapturePageTags.RECORD_SCREEN)
        rule.onNodeWithText("Video quality").assertExists()

        rule.onNodeWithText("Custom").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(CaptureBufferMode.CUSTOM, capture(state).bufferMode)
        rule.onNodeWithText("Buffers to capture").assertExists()
        rule.onNodeWithText("Choose at least one buffer for Custom mode.").assertExists()
        rule.onNodeWithText("radio").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("radio"), capture(state).buffers)
        rule.onNodeWithText("Choose at least one buffer for Custom mode.").assertDoesNotExist()
    }

    @Test
    fun theDeviceDisplayDropdownStoresTheChosenMode() {
        val state = AppState()
        openCapturePage(state)
        assertEquals(CaptureMirrorMode.EMBEDDED, capture(state).mirrorMode)
        click(CapturePageTags.DEVICE_DISPLAY)
        rule.onNodeWithTag("${CapturePageTags.DEVICE_DISPLAY}-option-1").performClick()
        rule.waitForIdle()
        assertEquals(CaptureMirrorMode.EXTERNAL, capture(state).mirrorMode)
        rule.onNodeWithTag(CapturePageTags.DEVICE_DISPLAY).assertExists()
    }

    @Test
    fun aMainPageDrillRowFlagsAPageWhoseValueIsInvalid() {
        val state = AppState()
        state.updateSettings { it.copy(captureSettings = it.captureSettings.copy(adbPath = "/no/such/adb")) }
        openCapturePage(state)
        rule.onNodeWithText("Needs attention").assertExists()
        rule.onNodeWithText("adb and scrcpy · auto-detected").assertDoesNotExist()

        drillInto(SettingsPage.CaptureTools)
        rule.onNodeWithText("path does not point to a file").assertExists()
    }

    @Test
    fun theToolsPageShowsThePathsAndOnlyOffMacTheHardwareMirrorSwitch() {
        val state = AppState()
        openCapturePage(state)
        drillInto(SettingsPage.CaptureTools)
        val shown = anchorsOnScreen()
        listOf("Tools", "ADB path", "scrcpy path", "Recheck tools").forEach {
            assertTrue(it in shown, "$it on the Tools page")
        }
        rule.onNodeWithText("Recheck tools").assertExists()
        rule.onNodeWithText("Install guidance").assertExists()
        if (isMacOs) {
            assertFalse("Hardware-accelerated mirror (experimental)" in shown)
        } else {
            assertSwitchFlips(state, CapturePageTags.HARDWARE_MIRROR) { it.captureSettings.hardwareMirror }
        }
    }

    @Test
    fun theLimitsPageEditsTheNumbersAndFlagsOutOfRangeValues() {
        val state = AppState()
        openCapturePage(state)
        drillInto(SettingsPage.CaptureLimits)
        val shown = anchorsOnScreen()
        listOf("Screen recording", "Storage", "Snapshot files", "Max size", "Bitrate Mbps", "Session limit GiB", "Reserve GiB", "Filename template", "Label")
            .forEach { assertTrue(it in shown, "$it on the Limits page") }

        val fps = captureFieldTag("Max FPS")
        rule.onNodeWithTag(fps).performScrollTo().performTextClearance()
        rule.onNodeWithTag(fps).performTextInput("60")
        rule.waitForIdle()
        assertEquals(60, capture(state).maxFps)
        rule.onNodeWithText("out of range").assertDoesNotExist()

        rule.onNodeWithTag(fps).performTextClearance()
        rule.onNodeWithTag(fps).performTextInput("0")
        rule.waitForIdle()
        assertEquals(0, capture(state).maxFps)
        assertTrue("maxFps" in invalidCaptureSettings(capture(state)))
        rule.onNodeWithText("out of range").assertExists()

        rule.onNodeWithTag(captureFieldTag("Session limit GiB")).performScrollTo().performTextClearance()
        rule.onNodeWithTag(captureFieldTag("Session limit GiB")).performTextInput("4")
        rule.waitForIdle()
        assertEquals(4L * 1024 * 1024 * 1024, capture(state).sessionLimitBytes)

        rule.onNodeWithTag(captureFieldTag("Filename template")).performScrollTo().performTextClearance()
        rule.onNodeWithTag(captureFieldTag("Filename template")).performTextInput("{unknown}")
        rule.waitForIdle()
        assertTrue("filenameTemplate" in invalidCaptureSettings(capture(state)))
    }

    @Test
    fun theSavedVideoFormatPicksTheContainer() {
        val state = AppState()
        openCapturePage(state)
        drillInto(SettingsPage.CaptureLimits)
        rule.onNodeWithText("MKV").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(CaptureVideoContainer.MKV, capture(state).videoContainer)
        rule.onNodeWithText("MP4").performClick()
        rule.waitForIdle()
        assertEquals(CaptureVideoContainer.MP4, capture(state).videoContainer)
    }

    @Test
    fun theMarkIssuePageHasTheWindowFieldsAndTwoSwitches() {
        val state = AppState()
        openCapturePage(state)
        drillInto(SettingsPage.CaptureMarkIssue)
        val shown = anchorsOnScreen()
        listOf("Mark issue", "Before, seconds", "After, seconds", "Take a screenshot on Mark issue", "Include markers in saved snapshots")
            .forEach { assertTrue(it in shown, "$it on the Mark issue page") }

        assertSwitchFlips(state, CapturePageTags.MARKER_SCREENSHOT) { it.captureSettings.markerScreenshot }
        assertSwitchFlips(state, CapturePageTags.MARKER_IN_SNAPSHOT) { it.captureSettings.markerNotesInSnapshot }

        val before = captureFieldTag("Before, seconds")
        rule.onNodeWithTag(before).performScrollTo().performTextClearance()
        rule.onNodeWithTag(before).performTextInput("45")
        rule.waitForIdle()
        assertEquals(45_000L, capture(state).markerPreMs)
    }

    @Test
    fun theVideoPlaybackPageHoldsTheTwoAppSettingsAndTheLiveAudioVolume() {
        val state = AppState()
        openCapturePage(state)
        drillInto(SettingsPage.CaptureVideoPlayback)
        val shown = anchorsOnScreen()
        listOf("Video follow readout", "New video links:\ndouble-click seeks", "Live audio volume %").forEach {
            assertTrue(it in shown, "$it on the Video playback page")
        }
        assertSwitchFlips(state, CapturePageTags.FOLLOW_READOUT) { it.showVideoFollowReadout }
        assertSwitchFlips(state, CapturePageTags.DOUBLE_CLICK_SEEKS) { it.enableDoubleClickVideoSeekOnLink }

        val volume = captureFieldTag("Live audio volume %")
        rule.onNodeWithTag(volume).performScrollTo().performTextClearance()
        rule.onNodeWithTag(volume).performTextInput("35")
        rule.waitForIdle()
        assertEquals(35, capture(state).liveAudioVolume)
    }
}
