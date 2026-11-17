package com.indagium

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.indagium.model.AnnBlock
import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.LogLevel
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
        rule.onNodeWithTag("settings-log-format-delete").assert(hasText("Delete…"))
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
        rule.onNodeWithText("Available capabilities").performScrollTo().assertExists()
        rule.onNodeWithText("Text search").performScrollTo().assertExists()
        recordScreenshot("preview", "settings-log-format-preview-results")

        rule.onNodeWithTag("settings-log-format-save").performScrollTo().performClick()
        rule.waitForIdle()
        val saved = state.availableLogFormatProfiles().single { it.name == "Service JSON" }
        assertEquals(LogFormatEngine.JSON_LINES, saved.engine)
        assertEquals("timestamp", saved.fieldMappings[LogFieldRole.TIMESTAMP])
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
