package com.indagium.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.LogAnalysis
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.utils.passesFilter
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FilterPanelPackagePidUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun packageSearchOpensPidPopupAndAddsUnscopedNumericRule() {
        val directory = createTempDirectory("filter-panel-package-pid-ui").toFile()
        val log = listOf(
            LogEntry(1, "00:00:00", LogLevel.I, "com.example.Alpha", "alpha", pid = 100, tid = 1),
            LogEntry(2, "00:00:01", LogLevel.D, "com.example.Beta", "beta", pid = 100, tid = 1),
            LogEntry(3, "00:00:02", LogLevel.I, "com.example.Alpha", "other pid", pid = 200, tid = 2),
            LogEntry(4, "00:00:03", LogLevel.I, "com.exampleSibling.Bad", "sibling prefix", pid = 300, tid = 3),
            LogEntry(5, "00:00:04", LogLevel.I, "OtherTag", "same process, outside prefix", pid = 100, tid = 1),
        )
        val tab = LogTab(
            id = "filter-panel-package-pid-ui",
            filename = "package-pid.log",
            logData = log,
            rmap = log.associateBy(LogEntry::id),
            filter = Filter(mode = FilterMode.TAGS),
            analysis = LogAnalysis(
                tagCounts = log.groupingBy(LogEntry::tag).eachCount(),
                tagPids = log.filter { it.pid > 0 }.groupBy(LogEntry::tag) { it.pid }.mapValues { it.value.toSet() },
                processNames = mapOf(100 to "main-app", 200 to "other-app", 300 to "sibling-app"),
                pending = false,
            ),
        )
        val state = AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archives"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        ).also { it.tabs = listOf(tab); it.filterPanelWidth = 320f }

        try {
            rule.setContent {
                CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                    Column(Modifier.size(360.dp, 900.dp)) {
                        BoundFilterPanel(state = state, tab = tab, width = 320f, liveMaxWidth = { 320f })
                    }
                }
            }

            val input = rule.onNodeWithTag("filter-panel-tags-input")
            input.performScrollTo()
            input.performClick()
            input.performTextInput("com.example")
            rule.waitUntilAtLeastOneExists(hasTestTag("filter-panel-tags-package-candidate-0"), 2_000)
            val packageRow = rule.onNodeWithTag("filter-panel-tags-package-candidate-0")
            packageRow.performScrollTo()
            packageRow.performMouseInput { moveTo(center) }
            rule.waitUntilAtLeastOneExists(hasTestTag("filter-panel-tags-candidate-0-pid"), 2_000)
            rule.onNodeWithTag("filter-panel-tags-candidate-0-pid").performMouseInput {
                moveTo(center)
                press()
                release()
            }
            rule.waitUntilAtLeastOneExists(hasTestTag("tag-process-add"), 2_000)
            rule.waitUntilAtLeastOneExists(hasTestTag("tag-process-item-100"), 2_000)
            rule.waitUntilAtLeastOneExists(hasTestTag("tag-process-item-200"), 2_000)
            rule.onNodeWithTag("tag-process-item-300").assertDoesNotExist()
            rule.onNodeWithTag("tag-process-keep-row").assertDoesNotExist()
            rule.onNodeWithTag("tag-process-add").performClick()

            rule.waitUntil(2_000) { state.tab(tab.id)?.filter?.messageRules?.isNotEmpty() == true }
            val updated = requireNotNull(state.tab(tab.id)).filter
            val pidRule = updated.messageRules.single()
            assertEquals(com.indagium.model.RuleTarget.PID_TID, pidRule.target)
            assertEquals("100", pidRule.pattern)
            assertTrue(pidRule.tag == null && pidRule.packagePrefix == null, "package selection adds an unscoped PID_TID rule")
            assertEquals(setOf(1, 2, 5), log.filter { passesFilter(it, updated) }.mapTo(mutableSetOf()) { it.id })
        } finally {
            state.close()
            directory.deleteRecursively()
        }
    }
}
