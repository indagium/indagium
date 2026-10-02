package com.indagium.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.unit.dp
import com.indagium.model.LogAnalysis
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import org.junit.Rule
import org.junit.Test

/**
 * Smoke test of the whole Filters panel in its splitter layout (synthetic entries only): every
 * section expanded at once must compose and lay out without tripping Compose's nested-scroll
 * infinite-height check, and every header must stay visible.
 */
@OptIn(ExperimentalTestApi::class)
class FilterPanelSplitterUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun everySectionExpandedComposesAndKeepsItsHeaderVisible() {
        val entries = (1..40).map { LogEntry(it, "10:00:%02d.000".format(it), LogLevel.I, "Tag${it % 7}", "message number $it") }
        val tab = LogTab(
            id = "splitter-ui",
            filename = "splitter-ui.log",
            logData = entries,
            rmap = entries.associateBy { it.id },
            analysis = LogAnalysis(tagCounts = entries.groupingBy { it.tag }.eachCount(), pending = false),
        )
        val state = AppState()
        state.tabs = listOf(tab)
        state.activeTabId = tab.id
        state.filterVisible = true
        state.fpState.logCompositionExpanded = true
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides WARM_PAPER) {
                Row(Modifier.height(760.dp)) {
                    BoundFilterPanel(state, tab, width = 300f, liveMaxWidth = { 300f })
                }
            }
        }
        rule.waitForIdle()

        listOf("Tags", "Message rules", "Log composition", "Highlighters", "Log level", "Sequences", "Issues", "Saved filters")
            .forEach { header ->
                // "Tags" also labels the mode tab above its section header; the header is the last match.
                rule.onAllNodesWithText(header).onLast().assertIsDisplayed()
            }
    }
}
