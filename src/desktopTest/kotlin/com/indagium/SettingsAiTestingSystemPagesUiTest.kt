package com.indagium

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.indagium.ai.CustomAiCommand
import com.indagium.model.AppSettings
import com.indagium.testing.model.JudgeMode
import com.indagium.ui.AiPageTags
import com.indagium.ui.AppState
import com.indagium.ui.SettingsDialog
import com.indagium.ui.SettingsPage
import com.indagium.ui.SystemPageTags
import com.indagium.ui.TestingPageTags
import com.indagium.ui.UpdateStatus
import com.indagium.ui.settingsNavTag
import com.indagium.update.SponsorChecker
import com.indagium.update.UpdateChecker
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The AI, Testing and System pages of the Settings dialog: Providers (and its unsaved-changes guard), Commands, Voice input, Test runs,
 * Issue tracker, Storage & files, Integrations and About.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsAiTestingSystemPagesUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val updateChecks = AtomicInteger()

    /** A state whose files live in a temporary folder and whose network clients answer from memory. */
    private fun newState(): AppState {
        val dir = createTempDirectory("settings-ai-testing-system").toFile()
        val client = HttpClient(
            MockEngine {
                updateChecks.incrementAndGet()
                respond("Not Found", HttpStatusCode.NotFound)
            },
        ) { expectSuccess = false }
        return AppState(
            autosaveFile = File(dir, "state.cache"),
            controlTokenFile = File(dir, "control-token"),
            customCommandsDir = File(dir, "commands"),
            platformDefaultSaveRootDir = File(dir, "Indagium"),
            updateChecker = UpdateChecker(client),
            sponsorChecker = SponsorChecker(client),
        )
    }

    private fun openPage(state: AppState, page: SettingsPage, onDismiss: () -> Unit = {}) {
        rule.setContent { SettingsDialog(state, onDismiss = onDismiss) }
        pickPage(page)
    }

    private fun pickPage(page: SettingsPage) {
        rule.onNodeWithTag(settingsNavTag(page)).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun click(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    /** Each of [titles] is on the page (a group title can share its text with a row or a nav item, so at least one node counts). */
    private fun assertGroups(vararg titles: String) =
        titles.forEach { assertTrue(rule.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty(), "no \"$it\" on the page") }

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

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(condition(), "condition was not met within ${timeoutMs}ms")
    }

    // ---- AI ----

    @Test
    fun theAiPagesShowTheirGroups() {
        val state = newState()
        openPage(state, SettingsPage.AiProviders)
        assertGroups("Provider profile", "AI requests")
        rule.onNodeWithText("Provider type").assertExists()
        rule.onNodeWithText("Profile name").assertExists()
        rule.onNodeWithText("Max MCP tool calls per request").assertExists()
        rule.onNodeWithTag(AiPageTags.SAVE_PROFILE).assertExists()

        pickPage(SettingsPage.AiCommands)
        assertGroups("Commands")
        rule.onNodeWithText("(none yet — add one to invoke it as a button in Actions or by typing /name in the chat box)").assertExists()

        pickPage(SettingsPage.VoiceInput)
        assertGroups("Dictation", "Language")
        rule.onNodeWithText("Recognition engine").assertExists()
        rule.onNodeWithText("Recognition language").assertExists()
    }

    @Test
    fun theCommandsPageListsCommandsAndOpensTheEditor() {
        val state = newState()
        assertNull(state.saveCustomAiCommand("timeline", "Build a timeline.\nSecond line."))
        openPage(state, SettingsPage.AiCommands)

        rule.onNodeWithText("/timeline").assertExists()
        rule.onNodeWithText("Build a timeline.").assertExists()
        click(AiPageTags.commandEdit("timeline"))
        assertEquals(CustomAiCommand("timeline", "Build a timeline.\nSecond line."), state.customCommandEditorTarget)

        state.customCommandEditorTarget = null
        click(AiPageTags.ADD_COMMAND)
        assertEquals(CustomAiCommand("", ""), state.customCommandEditorTarget)

        click(AiPageTags.commandDelete("timeline"))
        rule.onNodeWithText("Delete AI command?").assertExists()
        rule.onNode(hasText("Delete") and !hasTestTag(AiPageTags.commandDelete("timeline"))).performClick()
        rule.waitForIdle()
        assertTrue(state.customAiCommands.isEmpty())
    }

    @Test
    fun theVoiceTranslateSwitchFlipsItsSetting() {
        val state = newState()
        openPage(state, SettingsPage.VoiceInput)
        assertSwitchFlips(state, AiPageTags.VOICE_TRANSLATE) { it.voiceInput.translateToEnglish }
    }

    @Test
    fun aDirtyProviderDraftStopsAPageSwitchAndCancelKeepsYouOnProviders() {
        val state = newState()
        openPage(state, SettingsPage.AiProviders)
        rule.onNodeWithTag(AiPageTags.PROFILE_NAME).performScrollTo().performTextInput("-edited")
        rule.waitForIdle()

        rule.onNodeWithTag(settingsNavTag(SettingsPage.General)).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNode(hasText("Save changes to", substring = true)).assertExists()
        rule.onNodeWithText("You changed this provider's settings without saving. Save them before switching pages?").assertExists()

        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        rule.onNode(hasText("Save changes to", substring = true)).assertDoesNotExist()
        rule.onNodeWithTag(AiPageTags.PROFILE_NAME).assertExists()
        rule.onNodeWithText("Workspace profile").assertDoesNotExist()
    }

    @Test
    fun savingTheDraftFromThePageSwitchPromptStoresItAndLeaves() {
        val state = newState()
        openPage(state, SettingsPage.AiProviders)
        rule.onNodeWithTag(AiPageTags.PROFILE_NAME).performScrollTo().performTextInput("-edited")
        rule.waitForIdle()

        rule.onNodeWithTag(settingsNavTag(SettingsPage.General)).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()

        assertTrue(state.settings.aiProviderProfiles.any { "-edited" in it.displayName })
        rule.onNodeWithText("Workspace profile").assertExists()
    }

    @Test
    fun aDirtyProviderDraftAlsoStopsDoneAndDiscardClosesWithoutSaving() {
        val state = newState()
        val namesBefore = state.settings.aiProviderProfiles.map { it.displayName }
        var dismissed = 0
        openPage(state, SettingsPage.AiProviders, onDismiss = { dismissed++ })
        rule.onNodeWithTag(AiPageTags.PROFILE_NAME).performScrollTo().performTextInput("-edited")
        rule.waitForIdle()

        rule.onNodeWithTag("settings-done").performClick()
        rule.waitForIdle()
        assertEquals(0, dismissed, "Done waits for the answer")
        rule.onNodeWithText("You changed this provider's settings without saving. Save them before closing Settings?").assertExists()

        rule.onNodeWithText("Discard").performClick()
        rule.waitForIdle()
        assertEquals(1, dismissed)
        assertEquals(namesBefore, state.settings.aiProviderProfiles.map { it.displayName })
    }

    @Test
    fun aCleanProviderPageSwitchesAndClosesWithoutAPrompt() {
        val state = newState()
        var dismissed = 0
        openPage(state, SettingsPage.AiProviders, onDismiss = { dismissed++ })

        pickPage(SettingsPage.General)
        rule.onNodeWithText("Workspace profile").assertExists()
        pickPage(SettingsPage.AiProviders)
        rule.onNodeWithTag("settings-done").performClick()
        rule.waitForIdle()
        assertEquals(1, dismissed)
        rule.onNode(hasText("Save changes to", substring = true)).assertDoesNotExist()
    }

    @Test
    fun theAddProviderButtonPromptsWhileTheDraftIsDirty() {
        val state = newState()
        openPage(state, SettingsPage.AiProviders)
        val profilesBefore = state.settings.aiProviderProfiles.size
        rule.onNodeWithTag(AiPageTags.PROFILE_NAME).performScrollTo().performTextInput("-edited")
        rule.waitForIdle()

        click(AiPageTags.ADD_PROVIDER)
        rule.onNodeWithText("You changed this provider's settings without saving. Save them before continuing?").assertExists()
        assertEquals(profilesBefore, state.settings.aiProviderProfiles.size, "nothing was added behind the prompt")
    }

    // ---- Test runs ----

    @Test
    fun theTestRunsPageShowsItsGroupsAndTheTestFolders() {
        val state = newState()
        openPage(state, SettingsPage.TestRuns)
        assertGroups("New runs", "Recorded cases", "Folders")
        rule.onNodeWithText("Judge").assertExists()
        rule.onNodeWithText("Evidence to keep").assertExists()
        rule.onNodeWithText("Confirmation timeout").assertExists()
        rule.onNodeWithText("Stored evidence").assertExists()
        listOf("Test suites folder", "Test runs folder", "Issues folder").forEach { rule.onNodeWithText(it).assertExists() }
        listOf("Default save folder", "Capture sessions folder").forEach { rule.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test
    fun theTestRunsSwitchesAndEvidenceCheckboxesFlipTheirSettings() {
        val state = newState()
        openPage(state, SettingsPage.TestRuns)

        assertSwitchFlips(state, TestingPageTags.RECORD_SCREEN) { it.testing.evidence.video }
        assertSwitchFlips(state, TestingPageTags.KEEP_RECORDING_EVIDENCE) { it.testing.keepRecordingEvidence }

        val screenshots = state.settings.testing.evidence.screenshots
        click(TestingPageTags.EVIDENCE_SCREENSHOTS)
        assertEquals(!screenshots, state.settings.testing.evidence.screenshots)
        val transcript = state.settings.testing.evidence.transcript
        click(TestingPageTags.EVIDENCE_TRANSCRIPT)
        assertEquals(!transcript, state.settings.testing.evidence.transcript)
    }

    @Test
    fun theJudgeDropdownPicksTheModeAndRevealsTheProfileRow() {
        val state = newState()
        state.updateSettings { it.copy(testing = it.testing.copy(defaultJudgeMode = JudgeMode.OFF.wire)) }
        openPage(state, SettingsPage.TestRuns)
        rule.onAllNodesWithTag(TestingPageTags.JUDGE_PROFILE).assertCountEquals(0)

        click(TestingPageTags.JUDGE)
        rule.onNodeWithTag("${TestingPageTags.JUDGE}-option-${JudgeMode.EVERY_STEP.ordinal}").performClick()
        rule.waitForIdle()

        assertEquals(JudgeMode.EVERY_STEP, state.settings.testing.judgeMode)
        rule.onNodeWithTag(TestingPageTags.JUDGE_PROFILE).assertExists()
    }

    // ---- Issue tracker ----

    @Test
    fun theIssueTrackerPageShowsItsGroupsAndTheUseSwitchFlips() {
        val state = newState()
        openPage(state, SettingsPage.IssueTracker)
        assertGroups("Tracker", "Authentication", "Filing issues", "Connection")
        rule.onNodeWithText("Use an issue tracker").assertExists()

        assertSwitchFlips(state, TestingPageTags.TRACKER_ENABLE) { it.tracker.enabled }
        assertSwitchFlips(state, TestingPageTags.TRACKER_BEARER) { it.tracker.bearerPrefix }
    }

    @Test
    fun theTrackerFieldsWriteTheirSettingsAndTestConnectionWaitsForAValidUrl() {
        val state = newState()
        state.updateSettings { it.copy(tracker = it.tracker.copy(mcpUrl = "", name = "")) }
        openPage(state, SettingsPage.IssueTracker)

        rule.onNodeWithTag(TestingPageTags.TRACKER_NAME).performScrollTo().performTextInput("Jira")
        rule.waitForIdle()
        assertEquals("Jira", state.settings.tracker.name)

        rule.onNodeWithTag(TestingPageTags.TRACKER_TEST).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(TestingPageTags.TRACKER_SAVE_TOKEN).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(TestingPageTags.TRACKER_URL).performScrollTo().performTextInput("https://tracker.example.com/mcp")
        rule.waitForIdle()
        assertEquals("https://tracker.example.com/mcp", state.settings.tracker.mcpUrl)
        rule.onNodeWithTag(TestingPageTags.TRACKER_TEST).performScrollTo().assertIsEnabled()
        rule.onNodeWithTag(TestingPageTags.TRACKER_TOKEN).performScrollTo().performTextInput("secret")
        rule.waitForIdle()
        rule.onNodeWithTag(TestingPageTags.TRACKER_SAVE_TOKEN).performScrollTo().assertIsEnabled()
    }

    // ---- Storage & files ----

    @Test
    fun theStoragePageShowsItsGroupsFoldersAndTheSwitchesFlip() {
        val state = newState()
        openPage(state, SettingsPage.Storage)
        assertGroups("Folders", "Saving", "Disk use")
        listOf(
            "Default save folder",
            "Analysis artifacts folder",
            "Capture sessions folder",
            "Snapshots folder",
            "Saved captures folder (Save ZIP)",
        ).forEach { rule.onNodeWithText(it).assertExists() }
        listOf("Test suites folder", "Test runs folder", "Issues folder").forEach { rule.onNodeWithText(it).assertDoesNotExist() }

        assertSwitchFlips(state, SystemPageTags.AUTO_SAVE) { it.autoExportNotes }
        assertSwitchFlips(state, SystemPageTags.FILTER_BACKUPS) { it.autoSaveFilters }
    }

    @Test
    fun theDiskUseActionsOpenTheirConfirmations() {
        val state = newState()
        openPage(state, SettingsPage.Storage)
        rule.onNodeWithText("Temporary data").assertExists()
        rule.onNodeWithText("App data").assertExists()
        rule.onNodeWithText("Reset app data").assertExists()

        assertFalse(state.cacheClearConfirmOpen)
        click(SystemPageTags.CLEAR_TEMP)
        assertTrue(state.cacheClearConfirmOpen)

        assertFalse(state.resetAppDataConfirmOpen)
        click(SystemPageTags.RESET_APP_DATA)
        assertTrue(state.resetAppDataConfirmOpen)
    }

    // ---- Integrations ----

    @Test
    fun theIntegrationsPageShowsItsGroupsAndTheCorsAndDebugSwitchesFlip() {
        val state = newState()
        state.updateSettings { it.copy(debugLogFilePath = File(createTempDirectory("settings-debug-log").toFile(), "debug.log").absolutePath) }
        openPage(state, SettingsPage.Integrations)
        assertGroups("MCP control server", "Diagnostics")
        rule.onNodeWithText("Port").assertExists()
        rule.onNodeWithText("Connect a client").assertExists()
        rule.onNodeWithText("Debug log file").assertExists()
        rule.onNodeWithTag(SystemPageTags.MCP_RUNNING).assertDoesNotExist()

        assertSwitchFlips(state, SystemPageTags.MCP_CORS) { it.mcpAllowBrowserClients }
        assertSwitchFlips(state, SystemPageTags.DEBUG_SWITCH) { it.debugLoggingEnabled }
    }

    @Test
    fun theMcpSwitchStartsAndStopsTheServerAndTheRunningBadgeFollowsIt() {
        val state = newState()
        val port = ServerSocket(0).use { it.localPort }
        state.updateSettings { it.copy(mcpControlPort = port) }
        openPage(state, SettingsPage.Integrations)
        try {
            rule.onNodeWithTag(SystemPageTags.MCP_SWITCH).performScrollTo().assertIsOff()
            click(SystemPageTags.MCP_SWITCH)
            waitUntil { state.settings.mcpControlEnabled && state.controlServerToken() != null }
            rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodesWithTag(SystemPageTags.MCP_RUNNING).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(SystemPageTags.MCP_SWITCH).assertIsOn()

            click(SystemPageTags.MCP_SWITCH)
            waitUntil { !state.settings.mcpControlEnabled && state.controlServerToken() == null }
            rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodesWithTag(SystemPageTags.MCP_RUNNING).fetchSemanticsNodes().isEmpty() }
        } finally {
            state.stopControlServerForShutdown()
        }
    }

    @Test
    fun theConnectionInfoButtonOpensTheDialogAndTheTokenStaysMaskedUntilShown() {
        val state = newState()
        openPage(state, SettingsPage.Integrations)
        rule.onNodeWithText("http://127.0.0.1:${state.settings.mcpControlPort}/mcp").assertExists()
        val token = state.connectionInfoToken()
        rule.onNodeWithText(token).assertDoesNotExist()

        click(SystemPageTags.MCP_TOKEN_TOGGLE)
        rule.onNodeWithText(token).assertExists()
        click(SystemPageTags.MCP_TOKEN_TOGGLE)
        rule.onNodeWithText(token).assertDoesNotExist()

        assertFalse(state.mcpInfoOpen)
        click(SystemPageTags.MCP_INFO)
        assertTrue(state.mcpInfoOpen)
    }

    // ---- About ----

    @Test
    fun theAboutPageShowsTheVersionEditionAndProjectRows() {
        val state = newState()
        openPage(state, SettingsPage.About)
        assertGroups("Edition", "Project")
        rule.onNodeWithText("Indagium").assertExists()
        rule.onNodeWithText("GitHub repository").assertExists()
        rule.onNodeWithText("Support the project").assertExists()
        rule.onNodeWithText("License agreement").assertExists()
    }

    @Test
    fun theAboutRowsOpenTheSupportAndLicenseDialogs() {
        val state = newState()
        openPage(state, SettingsPage.About)

        assertFalse(state.supportDialogOpen)
        click(SystemPageTags.ABOUT_SUPPORT)
        assertTrue(state.supportDialogOpen)

        assertFalse(state.licenseAgreementOpen)
        click(SystemPageTags.ABOUT_LICENSE)
        assertTrue(state.licenseAgreementOpen)
    }

    @Test
    fun checkForUpdatesOnAboutRunsTheSameCheckAsGeneralsCheckNow() {
        val state = newState()
        openPage(state, SettingsPage.About)
        assertEquals(0, updateChecks.get())

        click(SystemPageTags.ABOUT_CHECK_UPDATES)
        waitUntil { updateChecks.get() == 1 && state.updateCheckStatus is UpdateStatus.Failed }
        rule.onNodeWithTag(SystemPageTags.ABOUT_UPDATE_STATUS).assertExists()
        rule.onNodeWithText("Couldn't reach GitHub").assertExists()

        pickPage(SettingsPage.General)
        click("update-check-now")
        waitUntil { updateChecks.get() == 2 }
    }
}
