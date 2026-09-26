package com.indagium

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.FilterMode.TAGS
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.ui.FilterBar
import com.indagium.ui.FilterBarActions
import com.indagium.ui.FilterBarModel
import com.indagium.ui.FilterBarToolbarButton
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real desktop Compose interaction coverage for the horizontal bar's Tags/Message/Regex paths.
 * The test tags are intentionally attached to the rendered controls in FilterBar rather than to
 * state helpers, so these tests catch regressions in focus, popup visibility, clear affordances,
 * and dialog actions.
 */
@OptIn(ExperimentalTestApi::class)
class FilterBarUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun filterBarToolbarButtonTogglesAndExplainsItsCurrentAction() {
        rule.setContent {
            var visible by remember { mutableStateOf(false) }
            FilterBarToolbarButton(
                visible = visible,
                onToggle = { visible = !visible },
            )
        }

        rule.onNodeWithContentDescription("Show filter bar")
            .assertExists()
            .performClick()
        rule.onNodeWithContentDescription("Hide filter bar").assertExists()
    }

    @Test
    fun regexBarSummaryIsOffByDefaultAndLabelsRetainedSelectorsWhenEnabled() {
        installBar(Filter(mode = FilterMode.KEYWORD), onFilterChanged = {})
        rule.onNodeWithTag("filter-bar-regex-summary").assertDoesNotExist()

        installBar(
            Filter(
                mode = FilterMode.KEYWORD,
                levels = setOf(LogLevel.I),
                activeTags = setOf("Car_SDK"),
                pkgPrefixes = setOf("com.example"),
                excludeTags = setOf("Noise"),
                excludePkgPrefixes = setOf("vendor"),
                excludeKw = "ignore",
                pidTidFilter = "123",
                messageRules = listOf(
                    com.indagium.model.MessageRule("r1", true, "ready"),
                ),
            ),
            model = TEST_MODEL.copy(showRegexFilterSummary = true),
            onFilterChanged = {},
        )
        rule.onNodeWithTag("filter-bar-regex-summary").assertIsDisplayed()
    }

    @Test
    fun focusedBlankTagsFieldShowsFrequentTagsAndClearKeepsThemVisible() {
        var latestFilter = Filter(mode = TAGS)
        installBar(latestFilter) { latestFilter = it }

        rule.onNodeWithTag(TAG_INPUT).performClick()
        waitForTagCandidates()
        rule.onNodeWithTag(TAG_CANDIDATES).assertExists()
        rule.onNodeWithText("com.example.Alpha").assertExists()

        rule.onNodeWithTag(TAG_INPUT).performTextInput("does-not-match")
        rule.onNodeWithTag(TAG_CLEAR).performClick()
        waitForTagCandidates()

        assertEquals("", latestFilter.kwInTag)
        rule.onNodeWithTag(TAG_CANDIDATES).assertExists()
        rule.onNodeWithText("com.example.Alpha").assertExists()
    }

    @Test
    fun tagCandidatesReopenAfterDismissalAndSupportMouseIncludeExcludeAndPackagePrefix() {
        var latestFilter = Filter(mode = TAGS)
        installBar(latestFilter) { latestFilter = it }

        rule.onNodeWithTag(TAG_INPUT).performClick()
        waitForTagCandidates()
        rule.onNodeWithTag(MESSAGE_INPUT).performClick()
        rule.waitUntil(2_000) { rule.onAllNodes(hasTestTag(TAG_CANDIDATES)).fetchSemanticsNodes().isEmpty() }

        rule.onNodeWithTag(TAG_INPUT).performClick()
        waitForTagCandidates()
        rule.onNodeWithTag(TAG_INPUT).performTextInput("com.example")
        rule.waitUntil(2_000) { rule.onAllNodesWithText("pkg").fetchSemanticsNodes().isNotEmpty() }

        // The first candidate is the shared package prefix. Mouse selection must add it as a
        // prefix, while the following tag candidate exposes independent include/exclude actions.
        rule.onNodeWithTag("filter-bar-tags-candidate-0").performClick()
        rule.waitUntil(2_000) { "com.example" in latestFilter.pkgPrefixes }
        rule.waitUntilAtLeastOneExists(hasTestTag("filter-bar-tags-candidate-1-exclude"), 2_000)
        rule.onNodeWithTag("filter-bar-tags-candidate-1-include").performClick()
        assertTrue("com.example.Alpha" in latestFilter.activeTags)
        rule.onNodeWithTag("filter-bar-tags-candidate-1-exclude").performClick()
        assertTrue("com.example.Alpha" in latestFilter.excludeTags)
    }

    @Test
    fun deepTagSelectionStaysVisibleWhenMovingDownAndUp() {
        val tags = (0 until 40).map { "com.example.Tag%02d".format(it) }
        installBar(
            Filter(mode = TAGS),
            model = TEST_MODEL.copy(
                sortedTags = tags,
                tagUsage = tags.associateWith { 1 },
            ),
        ) { }

        rule.onNodeWithTag(TAG_INPUT).performClick()
        rule.onNodeWithTag(TAG_INPUT).performTextInput("Tag")
        waitForTagCandidates()
        repeat(30) {
            rule.onNodeWithTag(TAG_INPUT).performKeyInput { pressKey(Key.DirectionDown) }
        }
        rule.waitUntilAtLeastOneExists(hasTestTag("filter-bar-tags-candidate-29"), 2_000)
        rule.onNodeWithTag("filter-bar-tags-candidate-29").assertIsDisplayed()

        repeat(20) {
            rule.onNodeWithTag(TAG_INPUT).performKeyInput { pressKey(Key.DirectionUp) }
        }
        rule.waitUntilAtLeastOneExists(hasTestTag("filter-bar-tags-candidate-9"), 2_000)
        rule.onNodeWithTag("filter-bar-tags-candidate-9").assertIsDisplayed()
    }

    @Test
    fun tagsEscapeClearsInputAndHidesCandidates() {
        var latestFilter = Filter(mode = TAGS)
        installBar(latestFilter) { latestFilter = it }

        rule.onNodeWithTag(TAG_INPUT).performClick()
        rule.onNodeWithTag(TAG_INPUT).performTextInput("temporary")
        waitForTagCandidates()
        rule.onNodeWithTag(TAG_INPUT).performKeyInput { pressKey(Key.Escape) }

        assertEquals("", latestFilter.kwInTag)
        rule.onNodeWithTag(TAG_CANDIDATES).assertDoesNotExist()
    }

    @Test
    fun messageCandidatesReopenAndSupportIncludeExcludeScopedRules() {
        var latestFilter = Filter(mode = TAGS)
        installBar(latestFilter, logData = SAMPLE_LOG) { latestFilter = it }

        rule.onNodeWithTag(MESSAGE_INPUT).performClick()
        rule.onNodeWithTag(MESSAGE_INPUT).performTextInput("timeout")
        waitForMessageCandidates()
        rule.onNodeWithTag(MESSAGE_INPUT).performKeyInput { pressKey(Key.Escape) }
        rule.waitUntil(2_000) { rule.onAllNodes(hasTestTag(MESSAGE_CANDIDATES)).fetchSemanticsNodes().isEmpty() }

        rule.onNodeWithTag(MESSAGE_INPUT).performClick()
        rule.onNodeWithTag(MESSAGE_INPUT).performTextInput("timeout")
        waitForMessageCandidates()
        rule.onNodeWithTag("filter-bar-message-candidate-0-include").performClick()
        waitForScopeChooser()
        rule.onNodeWithTag(SCOPE_ALL).performClick()
        assertTrue(latestFilter.messageRules.any { it.include && it.pattern == "timeout happened" && it.tag == null })

        waitForMessageCandidates()
        rule.onNodeWithTag("filter-bar-message-candidate-0-exclude").performClick()
        waitForScopeChooser()
        rule.onNodeWithTag(SCOPE_ALL).performClick()
        assertTrue(latestFilter.messageRules.any { !it.include && it.pattern == "timeout happened" })
        assertFalse(latestFilter.messageRules.any { it.include && it.pattern == "timeout happened" })
    }

    @Test
    fun messageClearPreservesScopeChooserAndOutsideClickDoesNotDismissIt() {
        var latestFilter = Filter(mode = TAGS)
        var clearWrites = 0
        installBar(latestFilter) {
            latestFilter = it
            if (it.kwInTag.isEmpty()) clearWrites++
        }

        rule.onNodeWithTag(MESSAGE_INPUT).performTextInput("timeout")
        rule.waitUntil(2_000) { latestFilter.kwInTag == "timeout" }
        rule.onNodeWithTag(MESSAGE_INPUT).performKeyInput { pressKey(Key.Enter) }
        waitForScopeChooser()

        rule.onNodeWithTag(MESSAGE_CLEAR).assertHasClickAction()
        rule.onNodeWithTag(MESSAGE_CLEAR).performClick()
        assertEquals(1, clearWrites, "message clear callback must synchronously publish an empty query")
        assertEquals("", latestFilter.kwInTag)
        rule.onNodeWithTag(SCOPE_CHOOSER).assertExists()

        // The tag field is outside the scope chooser. The chooser Popup deliberately disables
        // outside-click dismissal, so a real click on this other rendered control must not cancel
        // the pending rule.
        rule.onNodeWithTag(TAG_INPUT).performClick()
        rule.onNodeWithTag(SCOPE_CHOOSER).assertExists()
    }

    @Test
    fun messageScopeChooserClosesOnlyThroughExplicitCancelEscapeOrCommit() {
        var latestFilter = Filter(mode = TAGS)
        installBar(latestFilter) { latestFilter = it }

        rule.onNodeWithTag(MESSAGE_INPUT).performTextInput("timeout")
        rule.onNodeWithTag(MESSAGE_INPUT).performKeyInput { pressKey(Key.Enter) }
        waitForScopeChooser()
        rule.onNodeWithTag(SCOPE_CANCEL).performClick()
        rule.onNodeWithTag(SCOPE_CHOOSER).assertDoesNotExist()

        rule.onNodeWithTag(MESSAGE_INPUT).performTextInput("timeout")
        rule.onNodeWithTag(MESSAGE_INPUT).performKeyInput { pressKey(Key.Enter) }
        waitForScopeChooser()
        rule.onNodeWithTag(SCOPE_INPUT).performKeyInput { pressKey(Key.Escape) }
        rule.onNodeWithTag(SCOPE_CHOOSER).assertDoesNotExist()

        rule.onNodeWithTag(MESSAGE_INPUT).performTextInput("timeout")
        rule.onNodeWithTag(MESSAGE_INPUT).performKeyInput { pressKey(Key.Enter) }
        waitForScopeChooser()
        rule.onNodeWithTag(SCOPE_ALL).performClick()
        rule.onNodeWithTag(SCOPE_CHOOSER).assertDoesNotExist()
        assertTrue(latestFilter.messageRules.any { it.pattern == "timeout" })
    }

    @Test
    fun messageScopeQueryFiltersRenderedOptionsAndCommitsTheSelectedScope() {
        var latestFilter = Filter(mode = TAGS)
        installBar(latestFilter) { latestFilter = it }

        rule.onNodeWithTag(MESSAGE_INPUT).performTextInput("timeout")
        rule.onNodeWithTag(MESSAGE_INPUT).performKeyInput { pressKey(Key.Enter) }
        waitForScopeChooser()
        rule.onNodeWithTag(SCOPE_INPUT).performTextInput("Alpha")
        rule.waitUntilAtLeastOneExists(hasTestTag(SCOPE_OPTION_1), 2_000)
        // The scope field has now handed focus to the popup. An outside click must still leave
        // the pending chooser intact because dismissOnClickOutside is disabled in both phases.
        rule.onNodeWithTag(TAG_INPUT).performClick()
        rule.onNodeWithTag(SCOPE_CHOOSER).assertExists()
        rule.onNodeWithText("com.example.Alpha").performClick()

        rule.onNodeWithTag(SCOPE_CHOOSER).assertDoesNotExist()
        assertTrue(latestFilter.messageRules.any { it.pattern == "timeout" && it.tag == "com.example.Alpha" })
    }

    @Test
    fun regexClearAndEscapeApplySynchronouslyAndLineBreaksAreStripped() {
        var latestFilter = Filter(mode = FilterMode.KEYWORD, kwText = "initial")
        installBar(latestFilter) { latestFilter = it }

        rule.onNodeWithTag(REGEX_CLEAR).performClick()
        assertEquals("", latestFilter.kwText, "regex clear must update the filter without debounce")

        rule.onNodeWithTag(REGEX_INPUT).performTextInput("temporary")
        rule.onNodeWithTag(REGEX_INPUT).performKeyInput { pressKey(Key.Escape) }
        assertEquals("", latestFilter.kwText, "regex Escape must update the filter without debounce")
        rule.onNodeWithTag(REGEX_HISTORY).assertDoesNotExist()

        // The field wraps (multi-line) but a pasted line break must not end up in the pattern.
        rule.onNodeWithTag(REGEX_INPUT).performTextInput("a\nb")
        rule.onNodeWithTag(REGEX_INPUT).performKeyInput { pressKey(Key.Enter) }
        assertEquals("ab", latestFilter.kwText)
        rule.onNodeWithTag(REGEX_EXPAND).assertDoesNotExist()
    }

    @Test
    fun regexHistoryAutocompleteValidationAndEnterCommitRemainAvailable() {
        var latestFilter = Filter(mode = FilterMode.KEYWORD)
        var remembered = 0
        installBar(latestFilter, onFilterChanged = { latestFilter = it }, onRememberRegex = { remembered++ })

        rule.onNodeWithTag(REGEX_INPUT).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag(REGEX_HISTORY), 2_000)
        rule.onNodeWithText("initial").performClick()
        assertEquals("initial", latestFilter.kwText)
        assertEquals(1, remembered)

        rule.onNodeWithTag(REGEX_INPUT).performTextInput("[")
        rule.waitUntilAtLeastOneExists(hasTestTag(REGEX_INVALID), 2_000)
        rule.onNodeWithTag(REGEX_INPUT).performKeyInput { pressKey(Key.Escape) }
        assertEquals("", latestFilter.kwText)

        rule.onNodeWithTag(REGEX_INPUT).performTextInput("enter-committed")
        rule.onNodeWithTag(REGEX_INPUT).performKeyInput { pressKey(Key.Enter) }
        assertEquals("enter-committed", latestFilter.kwText)
        assertEquals(2, remembered)
    }

    @Test
    fun leavingTheRegexFieldRecordsThePatternButAQuickRefocusDoesNot() {
        val remembered = mutableListOf<String>()
        installBar(Filter(mode = FilterMode.KEYWORD), onRememberRegex = { remembered += it }, onFilterChanged = {})

        // The ".*" button takes focus and hands it straight back — must not record "main".
        rule.onNodeWithTag(REGEX_INPUT).performTextInput("main")
        rule.onNodeWithTag(REGEX_SNIPPETS_BUTTON).performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(emptyList(), remembered)

        // Clicking away (e.g. a log row) without pressing Enter records the pattern.
        rule.onNodeWithTag(REGEX_SNIPPETS_BUTTON).performClick() // close the menu again
        rule.onNodeWithTag(OUTSIDE).performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(listOf("main"), remembered)
    }

    @Test
    fun regexHistoryButtonOpensTheFullHistoryList() {
        var latestFilter = Filter(mode = FilterMode.KEYWORD, kwText = "initial")
        installBar(latestFilter, onFilterChanged = { latestFilter = it })

        rule.onNodeWithTag(REGEX_HISTORY_BUTTON).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag(REGEX_HISTORY), 2_000)
        rule.onNodeWithText("other").assertExists()
        rule.onNodeWithTag(REGEX_HISTORY_BUTTON).performClick()
        rule.waitUntil(2_000) { rule.onAllNodes(hasTestTag(REGEX_HISTORY)).fetchSemanticsNodes().isEmpty() }

        assertEquals("initial", latestFilter.kwText)
    }

    private fun installBar(
        initialFilter: Filter,
        logData: List<LogEntry> = emptyList(),
        model: FilterBarModel = TEST_MODEL,
        onRememberRegex: (String) -> Unit = {},
        onFilterChanged: (Filter) -> Unit,
    ) {
        val initialTab = testTab(initialFilter, logData)
        rule.setContent {
            var tab by remember { mutableStateOf(initialTab) }
            val updateFilter: (Filter) -> Unit = { next ->
                onFilterChanged(next)
                tab = tab.copy(filter = next)
            }
            Column {
                FilterBar(
                    tab = tab,
                    model = model,
                    actions = FilterBarActions(
                        onSetFilterMode = { mode -> updateFilter(tab.filter.copy(mode = mode)) },
                        onStartRegexSearch = { updateFilter(tab.filter.copy(mode = FilterMode.KEYWORD)) },
                        onToggleTag = { value ->
                            updateFilter(tab.filter.copy(activeTags = tab.filter.activeTags.toggle(value)))
                        },
                        onToggleExcludeTag = { value ->
                            updateFilter(tab.filter.copy(excludeTags = tab.filter.excludeTags.toggle(value)))
                        },
                        onAddPkgPrefix = { value ->
                            updateFilter(tab.filter.copy(pkgPrefixes = tab.filter.pkgPrefixes + value))
                        },
                        onRemovePkgPrefix = { value ->
                            updateFilter(tab.filter.copy(pkgPrefixes = tab.filter.pkgPrefixes - value))
                        },
                        onAddExcludePkgPrefix = { value ->
                            updateFilter(tab.filter.copy(excludePkgPrefixes = tab.filter.excludePkgPrefixes + value))
                        },
                        onRemoveExcludePkgPrefix = { value ->
                            updateFilter(tab.filter.copy(excludePkgPrefixes = tab.filter.excludePkgPrefixes - value))
                        },
                        onSetKwInTag = { value -> updateFilter(tab.filter.copy(kwInTag = value)) },
                        onToggleKwInTagRegex = {},
                        onSetKw = { value -> updateFilter(tab.filter.copy(kwText = value)) },
                        onAddMessageRule = { include, pattern, regex, tag, packagePrefix, target ->
                            val next = com.indagium.model.MessageRule(
                                id = "test-${tab.filter.messageRules.size}",
                                include = include,
                                pattern = pattern,
                                regex = regex,
                                tag = tag,
                                packagePrefix = packagePrefix,
                                target = target,
                                mode = tab.filter.mode,
                            )
                            updateFilter(tab.filter.copy(messageRules = (tab.filter.messageRules
                                .filterNot {
                                    it.pattern == pattern && it.regex == regex && it.tag == tag &&
                                        it.packagePrefix == packagePrefix && it.target == target && it.mode == tab.filter.mode
                                } + next)))
                        },
                        onRemoveMessageRule = {},
                        onRememberRegexPattern = { onRememberRegex(it) },
                        onClearRegexHistory = {},
                        onOpenFilterPanel = {},
                    ),
                    logFocusRequester = null,
                )
                // Something outside the bar to click, standing in for a log row.
                Box(Modifier.size(20.dp).testTag(OUTSIDE).clickable {})
            }
        }
    }

    private fun waitForTagCandidates() {
        rule.waitUntilAtLeastOneExists(hasTestTag(TAG_CANDIDATES), POPUP_TIMEOUT_MS)
    }

    private fun waitForMessageCandidates() {
        rule.waitUntilAtLeastOneExists(hasTestTag(MESSAGE_CANDIDATES), POPUP_TIMEOUT_MS)
    }

    private fun waitForScopeChooser() {
        rule.waitUntilAtLeastOneExists(hasTestTag(SCOPE_CHOOSER), POPUP_TIMEOUT_MS)
    }

    private fun testTab(filter: Filter, logData: List<LogEntry> = emptyList()) = LogTab(
        id = "filter-bar-ui-test",
        filename = "filter-bar-ui-test.log",
        logData = logData,
        rmap = logData.associateBy { it.id },
        filter = filter,
        analysis = com.indagium.model.LogAnalysis(
            tagCounts = logData.groupingBy { it.tag }.eachCount(),
            pending = false,
        ),
    )

    private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

    private companion object {
        const val TAG_INPUT = "filter-bar-tags-input"
        const val TAG_CLEAR = "filter-bar-tags-clear"
        const val TAG_CANDIDATES = "filter-bar-tags-candidates"
        const val MESSAGE_INPUT = "filter-bar-message-input"
        const val MESSAGE_CLEAR = "filter-bar-message-clear"
        const val MESSAGE_CANDIDATES = "filter-bar-message-candidates"
        const val SCOPE_CHOOSER = "filter-bar-message-scope-chooser"
        const val SCOPE_CANCEL = "filter-bar-message-scope-cancel"
        const val SCOPE_INPUT = "filter-bar-message-scope-input"
        const val SCOPE_ALL = "filter-bar-message-scope-all"
        const val SCOPE_OPTION_1 = "filter-bar-message-scope-option-1"
        const val REGEX_INPUT = "filter-bar-regex-input"
        const val REGEX_CLEAR = "filter-bar-regex-clear"
        const val REGEX_HISTORY = "filter-bar-regex-history"
        const val REGEX_HISTORY_BUTTON = "filter-bar-regex-history-button"
        const val REGEX_SNIPPETS_BUTTON = "filter-bar-regex-snippets-button"
        const val OUTSIDE = "outside-the-bar"
        const val POPUP_TIMEOUT_MS = 2_000L
        const val REGEX_INVALID = "filter-bar-regex-invalid"
        const val REGEX_EXPAND = "filter-bar-regex-expand"

        val TEST_MODEL = FilterBarModel(
            sortedTags = listOf("com.example.Alpha", "com.example.Beta"),
            tagUsage = mapOf("com.example.Alpha" to 10, "com.example.Beta" to 5),
            mostUsedTagLimit = 4,
            regexHistory = listOf("initial", "other"),
        )

        val SAMPLE_LOG = listOf(
            LogEntry(1, "00:00:00", LogLevel.I, "com.example.Alpha", "timeout happened", pid = 1234, tid = 1),
            LogEntry(2, "00:00:01", LogLevel.D, "com.example.Beta", "ready", pid = 1234, tid = 1),
        )
    }
}
