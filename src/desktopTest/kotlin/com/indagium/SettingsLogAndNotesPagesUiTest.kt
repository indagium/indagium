package com.indagium

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.indagium.diagram3.DiagramExportMode
import com.indagium.model.AnnotationCopyFormat
import com.indagium.model.AnnotationLogBlockStyle
import com.indagium.model.AppSettings
import com.indagium.model.CustomIssueRule
import com.indagium.ui.AppState
import com.indagium.ui.LogPageTags
import com.indagium.ui.NotesPageTags
import com.indagium.ui.SETTINGS_EMPTY_STATE_TAG
import com.indagium.ui.SettingsDialog
import com.indagium.ui.SettingsPage
import com.indagium.ui.SourcePageTags
import com.indagium.ui.settingsNavTag
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Reading logs and Notes pages of the Settings dialog: Log view, Search & filters, Issue categories, Source code, Notes & writing, Copy & export. */
@OptIn(ExperimentalTestApi::class)
class SettingsLogAndNotesPagesUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun openPage(state: AppState, page: SettingsPage) {
        rule.setContent { SettingsDialog(state, onDismiss = {}) }
        rule.onNodeWithTag(settingsNavTag(page)).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun click(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    /** Clicks an option of an open dropdown popup (a layer of its own, which no page scroll reaches). */
    private fun clickOption(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    private fun clickText(text: String) {
        rule.onNodeWithText(text).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun assertGroups(vararg titles: String) = titles.forEach { rule.onNodeWithText(it).assertExists() }

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

    // ---- Log view ----

    @Test
    fun theLogViewPageShowsItsThreeGroupsAndEverySwitchFlipsItsSetting() {
        val state = AppState()
        openPage(state, SettingsPage.LogView)
        assertGroups("Rows", "Highlights", "Panels and live logs")
        rule.onNodeWithText("Row numbers").assertExists()
        rule.onNodeWithText("Open files with Original").assertExists()

        assertSwitchFlips(state, LogPageTags.ROW_NUMBERS) { it.showRowNumbers }
        assertSwitchFlips(state, LogPageTags.PROCESS_NAMES) { it.showProcessNamesInNewTabs }
        assertSwitchFlips(state, LogPageTags.CRASH_ROWS) { it.highlightEntireCrashGroup }
        assertSwitchFlips(state, LogPageTags.OPEN_WITH_ORIGINAL) { it.openNewFilesWithUnfiltered }
        assertSwitchFlips(state, LogPageTags.MINIMAP) { it.showMinimap }
        assertSwitchFlips(state, LogPageTags.FOLLOW_LIVE) { it.autoScrollWhileTailing }
        assertSwitchFlips(state, "error-highlight-global-switch") { it.errorHighlightStyle.enabled }
    }

    @Test
    fun rowWrappingStillTogglesAutoOnTheLogViewPage() {
        val state = AppState()
        openPage(state, SettingsPage.LogView)
        assertTrue(state.settings.autoLogRowWrap)
        // "Wrap at" belongs to Fixed: dimmed and locked while Auto is on, its value kept.
        rule.onNodeWithTag(LogPageTags.ROW_WRAP_COLUMNS).performScrollTo().assertIsNotEnabled()
        clickText("Fixed")
        assertFalse(state.settings.autoLogRowWrap)
        rule.onNodeWithTag(LogPageTags.ROW_WRAP_COLUMNS).assertIsEnabled()
        rule.onNodeWithTag(LogPageTags.ROW_WRAP_COLUMNS).performTextClearance()
        rule.onNodeWithTag(LogPageTags.ROW_WRAP_COLUMNS).performTextInput("500")
        assertEquals(500, state.settings.logRowWrapLimitChars)
        // Typing below the lowest column keeps the nearest allowed one.
        rule.onNodeWithTag(LogPageTags.ROW_WRAP_COLUMNS).performTextClearance()
        rule.onNodeWithTag(LogPageTags.ROW_WRAP_COLUMNS).performTextInput("10")
        assertEquals(80, state.settings.logRowWrapLimitChars)
        clickText("Auto")
        assertTrue(state.settings.autoLogRowWrap)
        rule.onNodeWithTag(LogPageTags.ROW_WRAP_COLUMNS).assertIsNotEnabled()
    }

    // ---- Search & filters ----

    @Test
    fun theSearchPageShowsItsFourGroupsAndTheRegexSummaryIsAnOrdinarySwitch() {
        val state = AppState()
        openPage(state, SettingsPage.SearchFilters)
        assertGroups("Ctrl/Cmd+F", "Filter panel", "Keyboard")
        rule.onNodeWithText("Find searches").assertExists()
        rule.onNodeWithText("List height").assertExists()
        rule.onNodeWithText("Scroll margin").assertExists()

        // It used to be an Off | On control; now On is the right-hand, accent state like every other switch.
        assertFalse(state.settings.showRegexFilterSummary)
        rule.onNodeWithTag(LogPageTags.REGEX_SUMMARY).performScrollTo().assertIsOff()
        click(LogPageTags.REGEX_SUMMARY)
        assertTrue(state.settings.showRegexFilterSummary)
        rule.onNodeWithTag(LogPageTags.REGEX_SUMMARY).assertIsOn()
        click(LogPageTags.REGEX_SUMMARY)
        assertFalse(state.settings.showRegexFilterSummary)

        assertSwitchFlips(state, LogPageTags.ALSO_SHOW_ORIGINAL) { it.openUnfilteredOnCtrlF }
        assertSwitchFlips(state, LogPageTags.LINK_SPLIT) { it.syncSplitPanelSelectionAndScroll }
    }

    @Test
    fun theSteppersMoveToTheNextLimit() {
        val state = AppState()
        openPage(state, SettingsPage.SearchFilters)
        val tags = state.settings.mostUsedTagLimit
        val rows = state.settings.filterListRows
        val margin = state.settings.navScrollMargin
        listOf(LogPageTags.MOST_USED_TAGS, LogPageTags.LIST_HEIGHT, LogPageTags.SCROLL_MARGIN).forEach { tag ->
            rule.onNode(hasText("+") and hasAnyAncestor(hasTestTag(tag))).performScrollTo().performClick()
            rule.waitForIdle()
        }
        assertTrue(state.settings.mostUsedTagLimit > tags || tags == 20)
        assertTrue(state.settings.filterListRows > rows || rows == 15)
        assertTrue(state.settings.navScrollMargin > margin || margin == 12)
    }

    @Test
    fun theFindSelectorsKeepTheirTagsAndWriteTheirSettings() {
        val state = AppState()
        openPage(state, SettingsPage.SearchFilters)
        click("ctrl-f-target-selector")
        clickOption("ctrl-f-target-option-tags")
        assertEquals(com.indagium.model.CtrlFTarget.TAGS, state.settings.ctrlFTarget)
        click("search-scope-behavior-selector")
        clickOption("search-scope-behavior-option-two_searches")
        assertEquals(com.indagium.model.SearchScopeBehavior.TWO_SEARCHES, state.settings.searchScopeBehavior)
    }

    // ---- Issue categories ----

    @Test
    fun issueCategoriesAreDraftsWithASwitchPerRuleThatSaveTogether() {
        val state = AppState()
        state.updateSettings { it.copy(customIssueRules = listOf(CustomIssueRule("a", "Timeouts", "timeout"))) }
        openPage(state, SettingsPage.IssueCategories)
        assertGroups("Categories")
        rule.onNode(hasText("Each enabled regex", substring = true)).assertExists()
        rule.onNodeWithTag("${SourcePageTags.CATEGORY_SWITCH_PREFIX}0").assertIsOn()
        rule.onNodeWithTag(SourcePageTags.SAVE_CATEGORIES).assertIsNotEnabled()

        click("${SourcePageTags.CATEGORY_SWITCH_PREFIX}0")
        rule.onNodeWithTag("${SourcePageTags.CATEGORY_SWITCH_PREFIX}0").assertIsOff()
        assertTrue(state.settings.customIssueRules.single().enabled, "a draft is not saved until Save changes")
        rule.onNodeWithTag(SourcePageTags.SAVE_CATEGORIES).assertIsEnabled()
        click(SourcePageTags.SAVE_CATEGORIES)
        assertFalse(state.settings.customIssueRules.single().enabled)
        assertEquals("Timeouts", state.settings.customIssueRules.single().name)

        click(SourcePageTags.ADD_CATEGORY)
        rule.onNodeWithTag("${SourcePageTags.CATEGORY_SWITCH_PREFIX}1").assertExists()
        rule.onNodeWithText("Category 2 needs a name.").assertExists()
        rule.onNodeWithTag(SourcePageTags.SAVE_CATEGORIES).assertIsNotEnabled()
    }

    @Test
    fun issueCategoriesShowACompactEmptyStateInsteadOfAnEmptyListBox() {
        val state = AppState()
        state.updateSettings { it.copy(customIssueRules = emptyList()) }
        openPage(state, SettingsPage.IssueCategories)
        rule.onNodeWithTag(SETTINGS_EMPTY_STATE_TAG).performScrollTo().assertExists()
        rule.onNodeWithText("No custom issue categories yet.").assertExists()

        click(SourcePageTags.ADD_CATEGORY)
        rule.onNodeWithTag(SETTINGS_EMPTY_STATE_TAG).assertDoesNotExist()
        rule.onNodeWithTag("${SourcePageTags.CATEGORY_SWITCH_PREFIX}0").assertExists()
    }

    // ---- Source code ----

    @Test
    fun theSourceCodePageShowsItsGroupsAndTheWrapperDiscoveryIsASwitch() {
        val state = AppState()
        openPage(state, SettingsPage.SourceCode)
        assertGroups("Source folders", "Opening code", "Log wrappers")
        // Adding is the group's header button; the group is the list.
        rule.onNodeWithTag(SourcePageTags.REGISTER_SOURCE).assertExists()
        rule.onNodeWithText("No folders registered yet.").assertExists()
        rule.onNodeWithTag("editor-choice-dropdown").assertExists()
        rule.onNodeWithTag(SourcePageTags.EDITOR_RESCAN).assertExists()
        rule.onNodeWithText("Logging configurations").assertExists()
        rule.onNodeWithText("No custom logging configurations yet.").assertExists()

        assertSwitchFlips(state, SourcePageTags.DISCOVER_WRAPPERS) { it.sourceAutoDiscoveryEnabled }
    }

    @Test
    fun theOpenCommandDropdownWritesTheChoiceAndTheCustomCommandShowsItsField() {
        val state = AppState()
        state.updateSettings { it.copy(editorChoice = "custom") }
        openPage(state, SettingsPage.SourceCode)
        rule.onNodeWithText("idea --line {line} {file}").assertExists()

        click("editor-choice-dropdown")
        clickOption("editor-choice-dropdown-option-0")
        assertEquals("auto", state.settings.editorChoice)
        rule.onNodeWithText("idea --line {line} {file}").assertDoesNotExist()
    }

    // ---- Notes & writing ----

    @Test
    fun theNotesPageShowsItsGroupsAndLogBlocksKeepTheirValuesUnderTheNewLabels() {
        val state = AppState()
        openPage(state, SettingsPage.NotesWriting)
        assertGroups("Note blocks", "Proofreading")
        rule.onNodeWithText("Log excerpts").assertExists()

        clickText("Jira wiki")
        assertEquals(AnnotationLogBlockStyle.JIRA_JAVA, state.settings.annotationLogBlockStyle)
        clickText("Jira Cloud")
        assertEquals(AnnotationLogBlockStyle.JIRA_CLOUD, state.settings.annotationLogBlockStyle)
        clickText("Indented")
        assertEquals(AnnotationLogBlockStyle.INDENTED, state.settings.annotationLogBlockStyle)
    }

    @Test
    fun theNotesSwitchesAndSegmentedChoicesWriteTheirSettings() {
        val state = AppState()
        openPage(state, SettingsPage.NotesWriting)
        assertSwitchFlips(state, NotesPageTags.NUMBER_BLOCKS) { it.numberAnnotationBlocks }
        assertSwitchFlips(state, NotesPageTags.INLINE_MARKDOWN) { it.renderAnnotationMarkdownInline }
        assertSwitchFlips(state, NotesPageTags.PROOFREADING) { it.proofreading.enabled }

        clickText("Link")
        assertTrue(state.settings.diagramLinkedNotePrimary)
        clickText("Snapshot")
        assertFalse(state.settings.diagramLinkedNotePrimary)
        clickText("UK")
        assertEquals("en-GB", state.settings.proofreading.language)
        clickText("US")
        assertEquals("en-US", state.settings.proofreading.language)
    }

    @Test
    fun theVocabularyStaysEditableWhileProofreadingIsOffAndTheSpellingRowIsIndentedUnderTheSwitch() {
        val state = AppState()
        state.updateSettings { it.copy(proofreading = it.proofreading.copy(enabled = false)) }
        openPage(state, SettingsPage.NotesWriting)
        rule.onNodeWithTag(NotesPageTags.PROOFREADING).assertIsOff()
        rule.onNodeWithTag("proofreading-vocabulary-manager").assertExists()
        rule.onNodeWithTag("proofreading-vocabulary-query").performScrollTo().performTextInput("Indagium")
        click("proofreading-vocabulary-add")
        assertEquals(listOf("Indagium"), state.settings.proofreading.vocabulary)
        rule.onNodeWithText("1 word").assertExists()
    }

    // ---- Copy & export ----

    @Test
    fun theCopyPageShowsItsThreeGroups() {
        val state = AppState()
        openPage(state, SettingsPage.CopyExport)
        assertGroups("Copying", "Included in copied rows", "Masking", "Exported notes")
        rule.onNodeWithText("Note format").assertExists()
        rule.onNodeWithText("Source line label").assertExists()
    }

    @Test
    fun theProcessNameRowIsDisabledWhileProcessIdsAreOffAndKeepsItsStoredValue() {
        val state = AppState()
        state.updateSettings { it.copy(copyPidTid = false, copyPidAsName = true) }
        openPage(state, SettingsPage.CopyExport)
        rule.onNodeWithTag(NotesPageTags.COPY_PID_AS_NAME).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(NotesPageTags.COPY_PID_AS_NAME).performClick()
        rule.waitForIdle()
        assertTrue(state.settings.copyPidAsName, "a click on the disabled switch changes nothing")

        click(NotesPageTags.COPY_PID_TID)
        assertTrue(state.settings.copyPidTid)
        rule.onNodeWithTag(NotesPageTags.COPY_PID_AS_NAME).assertIsEnabled()
        click(NotesPageTags.COPY_PID_AS_NAME)
        assertFalse(state.settings.copyPidAsName)

        // Turning PID and TID off again leaves the name choice as the user set it.
        click(NotesPageTags.COPY_PID_TID)
        assertFalse(state.settings.copyPidTid)
        assertFalse(state.settings.copyPidAsName)
        rule.onNodeWithTag(NotesPageTags.COPY_PID_AS_NAME).assertIsNotEnabled()
    }

    @Test
    fun theCopySwitchesAndChoicesWriteTheirSettings() {
        val state = AppState()
        openPage(state, SettingsPage.CopyExport)
        assertSwitchFlips(state, NotesPageTags.COPY_ROW_NUMBER) { it.copyRowNumber }
        assertSwitchFlips(state, NotesPageTags.COPY_TIME_DELTA) { it.copyTimeDelta }

        click(NotesPageTags.COPY_FORMAT)
        clickOption("${NotesPageTags.COPY_FORMAT}-option-${AnnotationCopyFormat.entries.indexOf(AnnotationCopyFormat.MARKDOWN)}")
        assertEquals(AnnotationCopyFormat.MARKDOWN, state.settings.annotationCopyFormat)

        clickText("Source")
        assertEquals(DiagramExportMode.SOURCE, state.settings.diagramDefaultExportMode)
        clickText("Image")
        assertEquals(DiagramExportMode.IMAGE, state.settings.diagramDefaultExportMode)

        rule.onNodeWithTag(NotesPageTags.PREFIX_FIELD).performScrollTo().performTextClearance()
        rule.onNodeWithTag(NotesPageTags.PREFIX_FIELD).performTextInput("Taken from")
        rule.waitForIdle()
        assertEquals("Taken from", state.settings.annotationPrefixLabel)
        rule.onNodeWithText("Preview: Taken from app.log").assertExists()
    }

    @Test
    fun theMaskingGroupShowsItsPairsTableOnlyWhileTheSwitchIsOn() {
        val state = AppState()
        assertFalse(state.settings.maskWordOnCopy)
        openPage(state, SettingsPage.CopyExport)
        rule.onNodeWithTag(NotesPageTags.MASK_ADD).assertDoesNotExist()
        rule.onNodeWithText("Replacement").assertDoesNotExist()

        click(NotesPageTags.MASK_SWITCH)
        assertTrue(state.settings.maskWordOnCopy)
        rule.onNodeWithText("Replacement").assertExists()
        val pairs = state.settings.copyMaskRules.size
        click(NotesPageTags.MASK_ADD)
        assertEquals(pairs + 1, state.settings.copyMaskRules.size)

        click(NotesPageTags.MASK_SWITCH)
        assertFalse(state.settings.maskWordOnCopy)
        rule.onNodeWithTag(NotesPageTags.MASK_ADD).assertDoesNotExist()
    }
}
