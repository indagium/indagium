package com.indagium

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.indagium.model.AnnBlock
import com.indagium.model.CapabilityAvailability
import com.indagium.model.LogCapability
import com.indagium.model.LogEntry
import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.LogLevel
import com.indagium.model.LogProfileValueType
import com.indagium.model.LogRecordMetadata
import com.indagium.model.ManualCollapseBlock
import com.indagium.model.ManualCollapseDirection
import com.indagium.model.ProfileTimestampKind
import com.indagium.ui.ActiveLogFormatDetailsDialog
import com.indagium.ui.AppState
import com.indagium.ui.FileView
import com.indagium.ui.LogFormatTabContextAction
import com.indagium.ui.SETTINGS_SEARCH_INDEX
import com.indagium.ui.SettingsAnchorKey
import com.indagium.ui.SettingsDialog
import com.indagium.ui.SettingsPage
import com.indagium.ui.capabilityChipText
import com.indagium.ui.capabilityLabel
import com.indagium.ui.previewSeverityColor
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SettingsLogFormatsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private var tempDir: File? = null
    private var appState: AppState? = null

    @After
    fun tearDown() {
        appState?.close()
        tempDir?.deleteRecursively()
    }

    private fun newState(): AppState {
        val directory = Files.createTempDirectory("log-format-ui-").toFile()
        tempDir = directory
        return AppState(
            autosaveFile = File(directory, "state.cache"),
            restoreOnCreate = false,
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archive-cache"),
            customCommandsDir = File(directory, "commands"),
            filterBackupsDir = File(directory, "filter-backups"),
            autoExportNotes = false,
            controlTokenFile = File(directory, "control-token"),
            sourceIndexFile = File(directory, "source-index"),
            testingDir = File(directory, "testing"),
        ).also { appState = it }
    }

    private fun openPage(state: AppState) {
        state.requestedSettingsPage = SettingsPage.LogFormats
        rule.setContent { SettingsDialog(state, onDismiss = {}) }
        rule.waitForIdle()
    }

    @Test
    fun theProfileListShowsAScrollbarWhenItRunsPastThePopup() {
        val state = newState()
        openPage(state)

        rule.onNodeWithTag("settings-log-format-profile").performClick()
        rule.waitForIdle()

        // 14 built-in profiles do not fit the popup: the thin scrollbar says more are below.
        assertTrue(rule.onAllNodesWithTag("settings-log-format-profile-scrollbar").fetchSemanticsNodes().isNotEmpty())
        recordScreenshot("profile-dropdown-open", "settings-log-format-profile-popup")
    }

    @Test
    fun closingAProfileDraftCanCancelDiscardOrSave() {
        val state = newState()
        var dismissed = false
        state.requestedSettingsPage = SettingsPage.LogFormats
        rule.setContent { SettingsDialog(state, onDismiss = { dismissed = true }) }

        fun newDraft(name: String) {
            rule.onNodeWithTag("settings-log-format-new").performScrollTo().performClick()
            rule.onNodeWithTag("settings-log-format-name").performScrollTo().performTextReplacement(name)
            rule.onNodeWithTag("settings-done").performClick()
            rule.onNodeWithText("Save changes to $name?").assertExists()
        }
        newDraft("Keep editing")
        rule.onNodeWithText("Cancel").performClick()
        assertFalse(dismissed)
        assertTrue(state.availableLogFormatProfiles().none { it.name == "Keep editing" })

        rule.onNodeWithTag("settings-done").performClick()
        rule.onNode(hasText("Discard") and !hasTestTag("settings-log-format-revert")).performClick()
        assertTrue(dismissed)
        assertTrue(state.availableLogFormatProfiles().none { it.name == "Keep editing" })

        // The host stayed mounted to test the close callback. Save the same draft from the prompt.
        rule.onNodeWithTag("settings-done").performClick()
        rule.onNodeWithText("Save").performClick()
        assertTrue(state.availableLogFormatProfiles().any { it.name == "Keep editing" })
    }

    @Test
    fun nativeCsvGuideReadsOriginalColumnsAndKeepsQuotedCells() {
        val state = newState()
        val file = File(requireNotNull(tempDir), "source.csv")
        file.writeText(
            "time,level,component,pid,tid,message,request_id,duration_ms\n" +
                "2026-11-16T12:00:00Z,INFO,Checkout,4100,4200,loaded,req-001,20\n" +
                "2026-11-16T12:00:01Z,ERROR,Payment,4101,4201,\"timeout,\nsecond line\",req-002,35\n",
        )
        val id = requireNotNull(state.openFileWithLogFormatProfile(file, "plain-text"))
        rule.waitUntil(15_000) { state.tab(id)?.contentState == com.indagium.model.TabContentState.LOADED }
        state.activeTabId = id
        openPage(state)
        rule.onNodeWithTag("settings-log-format-new").performScrollTo().performClick()
        rule.onNodeWithTag("settings-log-format-start-csv").performScrollTo().performClick()
        rule.onNodeWithText("Paste source").performScrollTo().performClick()
        rule.onNodeWithTag("settings-csv-guide-sample").performScrollTo().performTextReplacement("I/RAW  time,level,message")
        rule.onNodeWithText("This sample starts with an I/RAW-style display prefix.", substring = true).assertExists()
        rule.onNodeWithTag("settings-csv-guide-preview").assertIsNotEnabled()
        rule.onNodeWithText("Current tab").performScrollTo().performClick()
        rule.onNodeWithTag("settings-csv-guide-preview").performScrollTo().performClick()
        val suggest = rule.onNodeWithTag("settings-csv-guide-suggest")
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("settings-csv-guide-suggest").fetchSemanticsNodes().isNotEmpty() }
        suggest.performScrollTo().performClick()
        rule.onNodeWithTag("settings-csv-guide-preview").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("2 parsed records", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("settings-log-format-save-footer").assertIsEnabled().performClick()
        val profile = state.availableLogFormatProfiles().single { !it.readOnly }
        assertEquals(LogFormatEngine.CSV, profile.engine)
        assertEquals("time", profile.fieldMappings[LogFieldRole.TIMESTAMP])
        assertEquals("request_id", profile.fieldMappings[LogFieldRole.REQUEST_ID])
        val result = file.inputStream().use { com.indagium.utils.parseLogContent(it, fileName = file.name, profile = profile) }
        assertEquals(listOf("loaded", "timeout,\nsecond line"), result.entries.map { it.msg })
        assertEquals(CapabilityAvailability.AVAILABLE, result.capabilities[LogCapability.DELTA_TIME]?.availability)

        rule.onNodeWithTag("settings-log-format-profile").performScrollTo().performClick()
        rule.onNodeWithTag("settings-log-format-plain-text").performScrollTo().performClick()
        rule.onNodeWithTag("settings-log-format-profile").performScrollTo().performClick()
        rule.onNodeWithTag("settings-log-format-${profile.id}").performScrollTo().performClick()
        val type = rule.onNodeWithTag("settings-csv-guide-type-7")
        type.performScrollTo().assert(hasText("Number")).performClick()
        rule.onNodeWithTag("settings-csv-guide-type-7-option-0").performScrollTo().performClick()
        rule.onNodeWithTag("settings-log-format-save-footer").performClick()
        val edited = state.availableLogFormatProfiles().single { it.id == profile.id }
        assertEquals(profile.fieldMappings, edited.fieldMappings)
        assertEquals(profile.visibleColumns, edited.visibleColumns)
        assertEquals(LogProfileValueType.TEXT, edited.attributes.single().type)
    }

    private fun recordScreenshot(name: String, tag: String? = null) {
        val image = if (tag == null) rule.onRoot().captureToImage() else rule.onNodeWithTag(tag).captureToImage()
        val pixels = image.toPixelMap()
        val rendered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) rendered.setRGB(x, y, pixels[x, y].toArgb())
        }
        val directory = File("build/log-format-qa").apply { mkdirs() }
        assertTrue(ImageIO.write(rendered, "png", File(directory, "$name.png")))
    }

    private fun anchorsOnScreen(): Set<String> = rule
        .onAllNodes(SemanticsMatcher.keyIsDefined(SettingsAnchorKey), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .map { it.config[SettingsAnchorKey] }
        .toSet()

    private fun assertEveryIndexedAnchorRenders(context: String) {
        val shown = anchorsOnScreen()
        val missing = SETTINGS_SEARCH_INDEX.filter { it.page == SettingsPage.LogFormats && it.anchor !in shown }.map { it.anchor }
        assertTrue(missing.isEmpty(), "$context: indexed anchors that do not render: $missing")
    }

    /** Starts a new custom profile on [engineLabel] (the label the engine dropdown shows for it). */
    private fun newProfileWithEngine(engineLabel: String) {
        rule.onNodeWithTag("settings-log-format-new").performScrollTo().performClick()
        rule.waitForIdle()
        val discardPrompt = rule.onNode(hasText("Discard") and !hasTestTag("settings-log-format-revert"))
        if (rule.onAllNodes(hasText("Save changes to", substring = true)).fetchSemanticsNodes().isNotEmpty()) {
            discardPrompt.performClick()
        }
        rule.onNodeWithTag("settings-log-format-engine").performScrollTo().performClick()
        rule.onNodeWithText(engineLabel).performClick()
        rule.waitForIdle()
    }

    private fun assertTagsAbsent(vararg tags: String) = tags.forEach { rule.onNodeWithTag(it).assertDoesNotExist() }

    private fun assertTagsPresent(vararg tags: String) = tags.forEach { rule.onNodeWithTag(it).assertExists() }

    @Test
    fun builtInProfilesAreReadOnlyAndCanBeDuplicatedIntoAnEditableProfile() {
        val state = newState()
        openPage(state)
        recordScreenshot("settings-editor")

        assertTrue(state.availableLogFormatProfiles().first().readOnly)
        // A built-in shows its definition as plain rows: no editor fields, no Save button.
        assertTagsPresent("settings-log-format-summary", "settings-log-format-builtin-header", "settings-log-format-duplicate-to-edit")
        rule.onNodeWithTag("settings-log-format-summary-parser-engine", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Built-in · read-only", substring = true).assertExists()
        assertTagsAbsent(
            "settings-log-format-name",
            "settings-log-format-description",
            "settings-log-format-engine",
            "settings-log-format-extensions",
            "settings-log-format-header",
            "settings-log-format-content-regex",
            "settings-log-format-time-kind",
            "settings-log-format-save",
            "settings-log-format-delete",
            "settings-log-format-builder-sample",
        )
        rule.onNodeWithTag("settings-log-format-preview").performScrollTo().assertIsEnabled().performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText("1 parsed record", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        val profileCount = state.availableLogFormatProfiles().size
        rule.onNodeWithTag("settings-log-format-duplicate-to-edit").performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals(profileCount + 1, state.availableLogFormatProfiles().size)
        val duplicate = state.availableLogFormatProfiles().last()
        assertFalse(duplicate.readOnly)
        assertNotEquals("android-logcat", duplicate.id)
        // The copy is selected and opens in the editor.
        rule.onNodeWithTag("settings-log-format-summary").assertDoesNotExist()
        rule.onNodeWithTag("settings-log-format-builtin-header").assertDoesNotExist()
        rule.onNodeWithTag("settings-log-format-save").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("settings-log-format-profile-menu").performScrollTo().assertExists()
        rule.onNodeWithTag("settings-log-format-name").performScrollTo().assertExists()
    }

    private fun openMenu(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun assertNoSaveInPreviewGroup() {
        // The preview buttons are there, Save is not: it lives in the profile header.
        rule.onNodeWithTag("settings-log-format-preview").performScrollTo().assertExists()
        rule.onNodeWithTag("settings-log-format-preview-current").assertExists()
        rule.onNodeWithText("Preview and save").assertDoesNotExist()
        rule.onNodeWithText("Preview", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theProfileRowIsADropdownNewProfileAndAnImportExportMenu() {
        val state = newState()
        openPage(state)

        assertTagsPresent("settings-log-format-profile", "settings-log-format-new", "settings-log-format-library-menu")
        rule.onNodeWithText("Library actions").assertDoesNotExist()
        rule.onNodeWithText("Duplicate", substring = false).assertDoesNotExist()
        assertTagsAbsent(
            "settings-log-format-import",
            "settings-log-format-export-current",
            "settings-log-format-export-bundle",
            "settings-log-format-delete",
        )
        rule.onNodeWithContentDescription("Import and export").assertExists()

        openMenu("settings-log-format-library-menu")
        rule.onNodeWithTag("settings-log-format-import").assert(hasText("Import…"))
        rule.onNodeWithTag("settings-log-format-export-current").assert(hasText("Export current…"))
        rule.onNodeWithTag("settings-log-format-export-bundle").assert(hasText("Export bundle…"))
        // Each item still does what its button did: the bundle item opens the export dialog.
        rule.onNodeWithTag("settings-log-format-export-bundle").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("settings-log-format-export-all").assertExists()
        // Closing the menu left nothing behind in the profile row.
        rule.onNodeWithText("Library actions").assertDoesNotExist()
    }

    @Test
    fun importingProfilesGuardsUnsavedDraftAndCancelLeavesTheImportPending() {
        val state = newState()
        openPage(state)
        rule.onNodeWithTag("settings-log-format-new").performScrollTo().performClick()
        rule.onNodeWithTag("settings-log-format-name").performScrollTo().performTextReplacement("Unsaved draft")
        val imported = LogFormatProfile(id = "imported-profile", name = "Imported profile", engine = LogFormatEngine.PLAIN_TEXT)
        rule.runOnIdle {
            val file = File(tempDir, "profiles.json").apply {
                writeText(com.indagium.utils.logFormatProfilesJson(listOf(imported)))
            }
            assertTrue(state.previewLogFormatProfilesImport(file).isSuccess)
        }

        rule.onNodeWithTag("settings-log-format-import-confirm").performScrollTo().performClick()
        rule.onNodeWithText("Save changes to Unsaved draft?").assertExists()
        rule.onNode(hasText("Cancel") and !hasTestTag("settings-log-format-import-cancel")).performClick()
        assertTrue(state.settings.customLogFormatProfiles.isEmpty())
        assertTrue(state.pendingLogFormatImport != null)

        rule.onNodeWithTag("settings-log-format-import-confirm").performScrollTo().performClick()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertTrue(state.settings.customLogFormatProfiles.any { it.name == "Unsaved draft" })
        assertTrue(state.settings.customLogFormatProfiles.any { it.id == imported.id })
        assertEquals(null, state.pendingLogFormatImport)
    }

    @Test
    fun aCustomProfileHeaderHoldsTheUnsavedStateRevertSaveAndTheMenu() {
        val state = newState()
        val profile = LogFormatProfile(
            id = "ui-header",
            name = "Header profile",
            description = "",
            engine = LogFormatEngine.PLAIN_TEXT,
        )
        assertTrue(state.saveLogFormatProfile(profile).isSuccess)
        openPage(state)
        rule.onNodeWithTag("settings-log-format-profile").performScrollTo().performClick()
        rule.onNodeWithTag("settings-log-format-ui-header").performScrollTo().performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("settings-log-format-custom-header").performScrollTo().assertExists()
        rule.onNodeWithText("Custom · revision 1", substring = true, useUnmergedTree = true).assertExists()
        // Right after selecting there is nothing to save or revert.
        rule.onNodeWithText("Unsaved changes").assertDoesNotExist()
        rule.onNodeWithTag("settings-log-format-revert").assertIsNotEnabled()
        assertNoSaveInPreviewGroup()
        rule.onNodeWithTag("settings-log-format-profile").performScrollTo()
        recordScreenshot("settings-editor-custom")

        rule.onNodeWithTag("settings-log-format-name").performScrollTo().performTextClearance()
        rule.onNodeWithTag("settings-log-format-name").performTextInput("Edited name")
        rule.waitForIdle()
        rule.onNodeWithText("Unsaved changes", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("settings-log-format-revert").performScrollTo().assertIsEnabled()
        rule.onNodeWithTag("settings-log-format-profile").performScrollTo()
        recordScreenshot("settings-editor-custom-dirty")

        rule.onNodeWithTag("settings-log-format-revert").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Unsaved changes").assertDoesNotExist()
        rule.onNodeWithTag("settings-log-format-revert").assertIsNotEnabled()
        rule.onNodeWithTag("settings-log-format-name").performScrollTo().assert(hasText("Header profile"))
        assertEquals("Header profile", state.availableLogFormatProfiles().single { it.id == "ui-header" }.name)

        rule.onNodeWithTag("settings-log-format-name").performTextClearance()
        rule.onNodeWithTag("settings-log-format-name").performTextInput("Saved name")
        rule.waitForIdle()
        rule.onNodeWithTag("settings-log-format-save").performScrollTo().performClick()
        rule.waitForIdle()
        val saved = state.availableLogFormatProfiles().single { it.id == "ui-header" }
        assertEquals("Saved name", saved.name)
        assertEquals(2, saved.revision)
        rule.onNodeWithText("Unsaved changes").assertDoesNotExist()
        rule.onNodeWithText("Custom · revision 2", substring = true, useUnmergedTree = true).assertExists()

        // A failed save is reported under the header, where Save was pressed.
        rule.onNodeWithTag("settings-log-format-name").performScrollTo().performTextClearance()
        rule.onNodeWithTag("settings-log-format-save").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("settings-log-format-error").performScrollTo().assertExists()
        assertEquals("Saved name", state.availableLogFormatProfiles().single { it.id == "ui-header" }.name)
        rule.onNodeWithText("Untitled profile", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theCustomProfileMenuDuplicatesAndOpensTheDeleteConfirmation() {
        val state = newState()
        val profile = LogFormatProfile(id = "ui-menu", name = "Menu profile", description = "", engine = LogFormatEngine.PLAIN_TEXT)
        assertTrue(state.saveLogFormatProfile(profile).isSuccess)
        openPage(state)
        rule.onNodeWithTag("settings-log-format-profile").performScrollTo().performClick()
        rule.onNodeWithTag("settings-log-format-ui-menu").performScrollTo().performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("settings-log-format-duplicate").assertDoesNotExist()
        openMenu("settings-log-format-profile-menu")
        rule.onNodeWithTag("settings-log-format-duplicate").assert(hasText("Duplicate"))
        rule.onNodeWithTag("settings-log-format-delete").assert(hasText("Delete profile…"))
        rule.onNodeWithTag("settings-log-format-delete").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete Menu profile?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertTrue(state.availableLogFormatProfiles().any { it.id == "ui-menu" })

        val count = state.availableLogFormatProfiles().size
        openMenu("settings-log-format-profile-menu")
        rule.onNodeWithTag("settings-log-format-duplicate").performClick()
        rule.waitForIdle()
        assertEquals(count + 1, state.availableLogFormatProfiles().size)
    }

    @Test
    fun aNewProfileIsNotSavedAndDiscardReturnsToTheFirstProfile() {
        val state = newState()
        openPage(state)
        rule.onNodeWithTag("settings-log-format-new").performScrollTo().performClick()
        rule.waitForIdle()

        rule.onNodeWithText("New · not saved", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Custom · revision", substring = true, useUnmergedTree = true).assertDoesNotExist()
        // Nothing to duplicate or delete yet, so there is no menu.
        assertTagsAbsent("settings-log-format-profile-menu", "settings-log-format-duplicate", "settings-log-format-delete")
        rule.onNodeWithTag("settings-log-format-save").assertIsEnabled()
        assertNoSaveInPreviewGroup()
        rule.onNodeWithTag("settings-log-format-profile").performScrollTo()
        recordScreenshot("settings-editor-new")

        rule.onNodeWithTag("settings-log-format-revert").performScrollTo().assert(hasText("Discard"))
        rule.onNodeWithTag("settings-log-format-revert").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertTagsPresent("settings-log-format-builtin-header", "settings-log-format-summary")
        assertTagsAbsent("settings-log-format-custom-header", "settings-log-format-save")
        rule.onNodeWithTag("settings-log-format-profile").assert(hasText(state.availableLogFormatProfiles().first().name))
        assertTrue(state.availableLogFormatProfiles().none { !it.readOnly })
    }

    @Test
    fun theBuiltInSummaryShowsEachValueOfTheProfile() {
        val state = newState()
        openPage(state)

        fun assertValue(label: String, text: String) {
            rule.onNodeWithTag("settings-log-format-summary-$label", useUnmergedTree = true)
                .performScrollTo()
                .assert(hasAnyDescendant(hasText(text, substring = true)))
        }
        // Android Logcat reads its own fields, time and severity.
        assertValue("parser-engine", "Android Logcat")
        assertValue("fields", "Logcat rows provide")
        assertValue("timestamp-interpretation", "Read directly by this engine.")
        assertValue("severity-mapping", "Read directly by this engine.")
        assertValue("file-extensions", "log, txt")
        assertValue("compatible-analyzers", "None")
        assertValue("default-columns", "Timestamp, Process ID, Thread ID, Severity, Component, Message")
    }

    @Test
    fun everyIndexedLogFormatsAnchorRendersForABuiltInAndForCustomProfilesOfEveryEngineKind() {
        val state = newState()
        openPage(state)
        assertEveryIndexedAnchorRenders("built-in profile")

        // Plain text has neither field mappings, timestamps nor severity mapping.
        rule.onNodeWithTag("settings-log-format-new").performScrollTo().performClick()
        rule.waitForIdle()
        assertEveryIndexedAnchorRenders("custom plain text")

        newProfileWithEngine("JSON with metadata")
        assertEveryIndexedAnchorRenders("custom JSON with metadata")

        newProfileWithEngine("JSON Lines")
        assertEveryIndexedAnchorRenders("custom JSON Lines")

        newProfileWithEngine("CSV / TSV")
        assertEveryIndexedAnchorRenders("custom CSV")

        newProfileWithEngine("Regular expression")
        assertEveryIndexedAnchorRenders("custom regex")
    }

    @Test
    fun aCustomJsonLinesProfileDrawsNoRowsThatOnlyOtherEnginesUse() {
        val state = newState()
        openPage(state)
        newProfileWithEngine("JSON Lines")

        assertTagsAbsent(
            "settings-log-format-regex",
            "settings-log-format-multiline",
            "settings-log-format-join-lines",
            "settings-log-format-delimiter",
            "settings-log-format-has-header",
        )
        assertTagsPresent(
            "settings-log-format-time-kind",
            "settings-log-format-epoch-unit",
            "settings-log-format-time-pattern",
            "settings-log-format-timezone",
            "settings-log-format-relative-clock",
            "settings-log-format-severity-add",
            "settings-log-format-attribute-add",
            "settings-log-format-field-timestamp",
        )
    }

    @Test
    fun aCustomMetadataJsonProfileShowsCompactReadOnlyLinesInsteadOfDimmedControls() {
        val state = newState()
        openPage(state)
        newProfileWithEngine("JSON with metadata")

        assertTagsAbsent(
            "settings-log-format-regex",
            "settings-log-format-multiline",
            "settings-log-format-join-lines",
            "settings-log-format-delimiter",
            "settings-log-format-time-kind",
            "settings-log-format-epoch-unit",
            "settings-log-format-time-pattern",
            "settings-log-format-timezone",
            "settings-log-format-relative-clock",
            "settings-log-format-severity-add",
            "settings-log-format-attribute-add",
            "settings-log-format-field-timestamp",
        )
        rule.onNodeWithTag("settings-log-format-summary-timestamp-interpretation", useUnmergedTree = true).performScrollTo().assertExists()
        rule.onNodeWithTag("settings-log-format-summary-severity-mapping", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("settings-log-format-summary-extra-attributes", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("This engine does not expose additional source fields.").assertExists()
        // A new custom profile keeps its editor and an enabled Save button.
        rule.onNodeWithTag("settings-log-format-save").performScrollTo().assertIsEnabled()
    }

    @Test
    fun aCustomRegexProfileHasTheRecordRegexAndMultilineRowsAndNoCsvRow() {
        val state = newState()
        openPage(state)
        newProfileWithEngine("Regular expression")

        assertTagsPresent(
            "settings-log-format-regex",
            "settings-log-format-multiline",
            "settings-log-format-join-lines",
            "settings-log-format-time-kind",
        )
        assertTagsAbsent("settings-log-format-delimiter", "settings-log-format-has-header")
        rule.onNodeWithTag("settings-log-format-join-lines").performScrollTo().assertIsEnabled()
        // A multiline record start decides where a record ends, so the join choice stops applying (the row stays, dimmed).
        rule.onNodeWithTag("settings-log-format-multiline").performScrollTo().performTextInput("^\\d{4}-")
        rule.waitForIdle()
        rule.onNodeWithTag("settings-log-format-join-lines").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aCustomCsvProfileHasTheCsvRowAndNoRegexRows() {
        val state = newState()
        openPage(state)
        newProfileWithEngine("CSV / TSV")

        assertTagsPresent("settings-log-format-delimiter", "settings-log-format-has-header", "settings-log-format-time-kind")
        assertTagsAbsent("settings-log-format-regex", "settings-log-format-multiline", "settings-log-format-join-lines")
    }

    @Test
    fun aSampleLineAndAChoicePerColumnFillInTheParsingRuleWithoutWritingARegex() {
        val state = newState()
        openPage(state)
        rule.onNodeWithTag("settings-log-format-new").performScrollTo().performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("settings-log-format-builder-sample").performScrollTo()
            .performTextInput("ERROR Connection lost 2024-03-01T10:00:00Z")
        rule.waitForIdle()
        // Four words, guessed as level, text, text, timestamp.
        rule.onNodeWithTag("settings-log-format-builder-piece-3").performScrollTo().assertExists()
        recordScreenshot("column-builder")
        rule.onNodeWithTag("settings-log-format-builder-apply").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("settings-log-format-builder-applied").performScrollTo().assertExists()

        rule.onNodeWithTag("settings-log-format-save").performScrollTo().performClick()
        rule.waitForIdle()
        val saved = state.availableLogFormatProfiles().last()
        assertFalse(saved.readOnly)
        assertEquals(LogFormatEngine.REGEX, saved.engine)
        assertEquals(
            mapOf(LogFieldRole.SEVERITY to "1", LogFieldRole.MESSAGE to "2", LogFieldRole.TIMESTAMP to "3"),
            saved.fieldMappings,
        )
        assertEquals(ProfileTimestampKind.ISO_8601, saved.timestampKind)
        val row = state.previewLogFormatProfile(saved, "WARN disk is almost full 2024-03-01T10:00:05Z").entries.single()
        assertEquals("disk is almost full", row.msg)
        assertEquals(LogLevel.W, row.level)
    }

    @Test
    fun newProfileFormSavesEngineAndFieldMappingsAndSamplePreviewShowsCoverage() {
        val state = newState()
        openPage(state)
        rule.onNodeWithTag("settings-log-format-new").performScrollTo().performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("settings-log-format-name").performScrollTo().performTextClearance()
        rule.onNodeWithTag("settings-log-format-name").performTextInput("Service JSON")
        rule.onNodeWithTag("settings-log-format-engine").performScrollTo().performClick()
        rule.onNodeWithText("JSON Lines").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("settings-log-format-preview").performScrollTo().performClick()
        rule.waitForIdle()
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText("2 parsed records", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Capabilities").performScrollTo().assertExists()
        rule.onNodeWithText("Text search · available").performScrollTo().assertExists()
        recordScreenshot("preview", "settings-log-format-preview-results")

        rule.onNodeWithTag("settings-log-format-save").performScrollTo().performClick()
        rule.waitForIdle()
        val saved = state.availableLogFormatProfiles().single { it.name == "Service JSON" }
        assertEquals(LogFormatEngine.JSON_LINES, saved.engine)
        assertEquals("timestamp", saved.fieldMappings[LogFieldRole.TIMESTAMP])
    }

    @Test
    fun everyCapabilityHasAHumanLabelAndAChipThatSaysItsAvailabilityInWords() {
        val labels = LogCapability.entries.map { capabilityLabel(it) }
        assertEquals(labels.size, labels.toSet().size, "labels must be distinct: $labels")
        labels.forEach { label ->
            assertFalse(label.contains('_'), "label has an underscore: $label")
            assertEquals(label.replaceFirstChar(Char::uppercase), label, "label is not sentence case: $label")
            assertEquals(label, label.trim())
        }
        assertEquals("Text search · available", capabilityChipText(LogCapability.TEXT_SEARCH, CapabilityAvailability.AVAILABLE))
        assertEquals("Live tail · partial", capabilityChipText(LogCapability.INCREMENTAL_TAIL, CapabilityAvailability.PARTIAL))
        assertEquals("Merge · unavailable", capabilityChipText(LogCapability.MERGE, CapabilityAvailability.UNAVAILABLE))
    }

    @Test
    fun aPreviewedRecordShowsItsSeverityInTheLevelColourAndTheFieldsOneToALine() {
        val unknown = Color(0xFF123456)

        fun entry(level: LogLevel, known: Boolean) = LogEntry(
            id = 1, ts = "12:00:00", level = level, tag = "app", msg = "hello",
            recordMetadata = if (known) null else LogRecordMetadata(severityKnown = false),
        )
        LogLevel.entries.forEach { level ->
            assertEquals(level.defaultColor, previewSeverityColor(entry(level, known = true), unknown))
        }
        assertEquals(unknown, previewSeverityColor(entry(LogLevel.E, known = false), unknown))

        val state = newState()
        openPage(state)
        rule.onNodeWithTag("settings-log-format-new").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("settings-log-format-engine").performScrollTo().performClick()
        rule.onNodeWithText("JSON Lines").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("settings-log-format-preview").performScrollTo().performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText("2 parsed records", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        // The sample is one INFO and one WARN line: the letters are drawn bold in those levels' colours, not in the accent.
        fun colourOf(text: String): Pair<Color, FontWeight?> {
            val node = rule.onNode(
                hasText(text) and hasAnyAncestor(hasTestTag("settings-log-format-preview-results")),
                useUnmergedTree = true,
            ).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val style = layouts.single().layoutInput.style
            return style.color to style.fontWeight
        }
        assertEquals(LogLevel.I.defaultColor to FontWeight.Bold, colourOf("I"))
        assertEquals(LogLevel.W.defaultColor to FontWeight.Bold, colourOf("W"))
    }

    @Test
    fun linesThatDoNotMatchIsASwitchThatTogglesTheDraft() {
        val state = newState()
        openPage(state)
        newProfileWithEngine("Regular expression")

        val tag = "settings-log-format-join-lines"
        rule.onNodeWithTag(tag).performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        val before = rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ToggleableState]
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
        val after = rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ToggleableState]
        assertNotEquals(before, after)
        rule.onNodeWithTag("settings-log-format-multiline").performScrollTo()
        recordScreenshot("settings-editor-regex")
        rule.onNodeWithTag("settings-log-format-save").performScrollTo().performClick()
        rule.waitForIdle()
        val saved = state.availableLogFormatProfiles().last()
        assertEquals(after == ToggleableState.On, saved.joinUnmatchedLines)
    }

    @Test
    fun firstRowContainsFieldNamesIsItsOwnSwitchRowUnderTheDelimiter() {
        val state = newState()
        openPage(state)
        newProfileWithEngine("CSV / TSV")

        val tag = "settings-log-format-has-header"
        rule.onNodeWithTag("settings-log-format-delimiter").performScrollTo().assertExists()
        rule.onNodeWithText("First row contains field names").performScrollTo().assertExists()
        rule.onNodeWithTag(tag).performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        val before = rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ToggleableState]
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
        val after = rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ToggleableState]
        assertNotEquals(before, after)
        rule.onNodeWithTag(tag).performScrollTo()
        recordScreenshot("settings-editor-csv")
        rule.onNodeWithTag("settings-log-format-save").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(after == ToggleableState.On, state.availableLogFormatProfiles().last().hasHeader)
    }

    @Test
    fun valueFieldsOfTheEditorAllStartAtTheSameLeftEdge() {
        val state = newState()
        openPage(state)
        newProfileWithEngine("JSON Lines")

        val tags = listOf(
            "settings-log-format-name",
            "settings-log-format-description",
            "settings-log-format-extensions",
            "settings-log-format-field-timestamp",
            "settings-log-format-field-message",
            "settings-log-format-time-pattern",
            "settings-log-format-timezone",
            "settings-log-format-relative-clock",
        )
        val lefts = tags.map { tag ->
            rule.onNodeWithTag(tag).performScrollTo()
            rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.left
        }
        assertEquals(1, lefts.map { it.toInt() }.toSet().size, "fields start at different x: ${tags.zip(lefts)}")
        rule.onNodeWithTag("settings-log-format-time-kind").performScrollTo()
        recordScreenshot("settings-editor-time")
        rule.onNodeWithTag("settings-log-format-field-timestamp").performScrollTo()
        recordScreenshot("settings-editor-fields")
    }

    @Test
    fun removingAnItemIsAnXButtonNamedAfterTheItem() {
        val state = newState()
        openPage(state)
        newProfileWithEngine("JSON Lines")

        // Attribute
        rule.onNodeWithTag("settings-log-format-issue-add").performScrollTo().performClick()
        rule.onNodeWithTag("settings-log-format-severity-add").performScrollTo().performClick()
        rule.onNodeWithTag("settings-log-format-attribute-add").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("settings-log-format-severity-add").performScrollTo()
        recordScreenshot("settings-editor-severity")
        rule.onNodeWithTag("settings-log-format-attribute-add").performScrollTo()
        recordScreenshot("settings-editor-items")
        rule.onNodeWithTag("settings-log-format-issue-add").performScrollTo()
        recordScreenshot("settings-editor-rules")
        rule.onNodeWithTag("settings-log-format-attribute-id-0").performScrollTo().assertExists()
        rule.onNodeWithText("Remove attribute", substring = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("Remove attribute field_1").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "settings-log-format-attribute-remove-0"))
            .performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("settings-log-format-attribute-id-0").assertDoesNotExist()

        // Issue rule
        rule.onNodeWithTag("settings-log-format-issue-id-0").performScrollTo().assertExists()
        rule.onNodeWithContentDescription("Remove rule rule_1").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "settings-log-format-issue-remove-0"))
            .performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("settings-log-format-issue-id-0").assertDoesNotExist()

        // Severity mapping
        rule.onNodeWithTag("settings-log-format-severity-source-0").performScrollTo().assertExists()
        rule.onNodeWithContentDescription("Remove mapping TRACE").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "settings-log-format-severity-remove-0"))
            .performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("settings-log-format-severity-source-0").assertDoesNotExist()
    }

    @Test
    fun malformedRegexShowsValidationBeforeProfileMutation() {
        val state = newState()
        openPage(state)
        rule.onNodeWithTag("settings-log-format-new").performScrollTo().performClick()
        rule.onNodeWithTag("settings-log-format-engine").performScrollTo().performClick()
        rule.onNodeWithText("Regular expression").performClick()
        rule.onNodeWithTag("settings-log-format-regex").performScrollTo().performTextInput("(")
        val countBeforePreview = state.settings.customLogFormatProfiles.size

        rule.onNodeWithTag("settings-log-format-preview").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Regex syntax is invalid.").performScrollTo().assertExists()
        assertEquals(countBeforePreview, state.settings.customLogFormatProfiles.size)
    }

    @Test
    fun dltProfileUsesCurrentSourcePreviewInsteadOfTextSamples() {
        val state = newState()
        val source = File(requireNotNull(tempDir), "active.log").apply {
            writeText("10-31 12:00:00.123  123  124 I App: started")
        }
        val activeTabId = requireNotNull(state.openFileWithLogFormatProfile(source, "android-logcat"))
        rule.waitUntil(10_000) {
            state.tab(activeTabId)?.contentState == com.indagium.model.TabContentState.LOADED
        }
        openPage(state)

        rule.onNodeWithTag("settings-log-format-profile").performScrollTo().performClick()
        rule.onNodeWithText("Automotive DLT").performClick()
        rule.waitForIdle()

        assertTrue(rule.onAllNodes(hasText("Paste sample records", substring = true)).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(hasText("Text samples cannot represent DLT records.")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodes(hasText("Preview sample")).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("settings-log-format-preview-current").performScrollTo().assertIsEnabled()
    }

    @Test
    fun perTabProfilePickerPreviewsAndCreatesANewIndependentTab() {
        val state = newState()
        val directory = requireNotNull(tempDir)
        val source = File(directory, "service.jsonl").apply {
            writeText(
                """{"timestamp":"2026-10-31T12:00:00Z","level":"INFO","logger":"api","message":"ready"}""" + "\n" +
                    """{"timestamp":"2026-10-31T12:00:01Z","level":"ERROR","logger":"api","message":"failed"}""",
            )
        }
        val originalTabId = requireNotNull(state.openFileWithLogFormatProfile(source, "json-lines"))
        rule.waitUntil(10_000) { state.tab(originalTabId)?.contentState == com.indagium.model.TabContentState.LOADED }
        val original = requireNotNull(state.tab(originalTabId))
        val originalIds = original.logData.map { it.id }
        val selectedRowId = originalIds.first()
        state.upTab(originalTabId) {
            it.copy(
                selected = setOf(selectedRowId),
                manualBlocks = listOf(
                    ManualCollapseBlock("original-fold", selectedRowId, ManualCollapseDirection.TO_START),
                ),
            )
        }
        state.upAnn(originalTabId) { tab ->
            tab.copy(
                annotations = tab.annotations.copy(
                    blocks = listOf(
                        AnnBlock.Note("original-note", "Keep this note on the original tab."),
                        AnnBlock.LogRef(
                            "original-row-ref",
                            listOf(selectedRowId),
                            "Selected source row",
                            sourceEntries = listOf(tab.rmap.getValue(selectedRowId)),
                        ),
                    ),
                ),
            )
        }
        val originalAnnotations = requireNotNull(state.tab(originalTabId)).annotations
        val customProfile = LogFormatProfile(
            id = "ui-json-copy",
            name = "Service JSON copy",
            description = "A second JSON Lines profile for the dialog test.",
            engine = LogFormatEngine.JSON_LINES,
            timestampKind = ProfileTimestampKind.ISO_8601,
            visibleColumns = listOf(LogFieldRole.TIMESTAMP, LogFieldRole.SEVERITY, LogFieldRole.MESSAGE),
        )
        assertTrue(state.saveLogFormatProfile(customProfile).isSuccess)

        rule.setContent {
            Box(Modifier.size(1120.dp, 700.dp)) {
                state.tab(originalTabId)?.let { tab ->
                    FileView(state, tab)
                    if (state.tabCtx != null) LogFormatTabContextAction(state, tab)
                }
                ActiveLogFormatDetailsDialog(state)
            }
        }
        rule.onNodeWithTag("tab-format-details-$originalTabId").assertDoesNotExist()
        rule.runOnIdle { state.tabCtx = com.indagium.model.TabCtxMenuState(originalTabId, 10f, 10f) }
        rule.onNodeWithText("Format details").performClick()
        rule.onNodeWithText("JSON Lines").assertExists()
        rule.onNodeWithTag("tab-log-format-profile").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tab-log-format-option-ui-json-copy").performScrollTo().performClick()
        rule.onNodeWithTag("tab-log-format-preview").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText("2 records in the source preview", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        recordScreenshot("format-details", "tab-log-format-details-dialog")
        rule.onNodeWithTag("tab-log-format-reparse-new").performScrollTo().assertIsEnabled().performClick()
        rule.waitUntil(10_000) { state.tabs.size == 2 }
        val newTab = state.tabs.single { it.id != originalTabId }

        assertEquals(originalIds, state.tab(originalTabId)?.logData?.map { it.id })
        assertEquals(originalAnnotations, state.tab(originalTabId)?.annotations)
        assertEquals(setOf(selectedRowId), state.tab(originalTabId)?.selected)
        assertEquals("original-fold", state.tab(originalTabId)?.manualBlocks?.single()?.id)
        assertEquals(customProfile.id, newTab.logFormatProfileId)
        assertNotEquals(originalTabId, newTab.id)
        assertTrue(newTab.selected.isEmpty())
        assertTrue(newTab.manualBlocks.isEmpty())
        assertTrue(newTab.annotations.blocks.isEmpty())
    }
}
