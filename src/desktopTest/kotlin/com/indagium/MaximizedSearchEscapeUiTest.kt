package com.indagium

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogViewSectionId
import com.indagium.model.SearchScope
import com.indagium.model.searchFor
import com.indagium.ui.AppState
import com.indagium.ui.CompareView
import com.indagium.ui.FileView
import com.indagium.ui.mkTab
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaximizedSearchEscapeUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun focusedFilteredFindClosesBeforeMaximizedLogRestores() {
        val state = stateWithTabs("log")
        try {
            state.logViewMaximized = LogViewSectionId.LOG
            assertTrue(state.openSearchInMaximizedLogView())
            state.setSearchQuery("log", "sample", SearchScope.FILTERED)
            rule.setContent { state.tab("log")?.let { FileView(state, it) } }

            val field = rule.onNodeWithTag("search-field-filtered")
            field.assertIsDisplayed().assertIsFocused().performKeyInput { pressKey(Key.Escape) }
            rule.waitForIdle()

            assertFalse(state.tab("log")!!.searchFor(SearchScope.FILTERED).active)
            assertEquals("sample", state.tab("log")!!.searchFor(SearchScope.FILTERED).query)
            assertEquals(LogViewSectionId.LOG, state.logViewMaximized)

            // SearchBar returns focus to the log list as it closes; no test-side refocus masks that handoff.
            rule.onRoot().performKeyInput { pressKey(Key.Escape) }
            assertNull(state.logViewMaximized)
        } finally {
            state.close()
        }
    }

    @Test
    fun focusedOriginalFindClosesBeforeMaximizedOriginalRestores() {
        val state = stateWithTabs("log", showOriginal = true)
        try {
            state.logViewMaximized = LogViewSectionId.ORIGINAL
            assertTrue(state.openSearchInMaximizedLogView())
            state.setSearchQuery("log", "sample", SearchScope.UNFILTERED)
            rule.setContent { state.tab("log")?.let { FileView(state, it) } }

            val field = rule.onNodeWithTag("search-field-unfiltered")
            field.assertIsDisplayed().assertIsFocused().performKeyInput { pressKey(Key.Escape) }
            rule.waitForIdle()

            assertFalse(state.tab("log")!!.searchFor(SearchScope.UNFILTERED).active)
            assertEquals("sample", state.tab("log")!!.searchFor(SearchScope.UNFILTERED).query)
            assertEquals(LogViewSectionId.ORIGINAL, state.logViewMaximized)

            rule.onRoot().performKeyInput { pressKey(Key.Escape) }
            assertNull(state.logViewMaximized)
        } finally {
            state.close()
        }
    }

    @Test
    fun focusedCompareOriginalFindClosesBeforeMaximizedPaneRestores() {
        val state = stateWithTabs("left", "right", showOriginal = true)
        try {
            state.compareMode = true
            state.compareTabId = "right"
            state.logViewMaximized = LogViewSectionId.ORIGINAL_B
            assertTrue(state.openSearchInMaximizedLogView())
            state.setSearchQuery("right", "sample", SearchScope.UNFILTERED)
            rule.setContent { CompareView(state) }

            val field = rule.onNodeWithTag("search-field-unfiltered")
            field.assertIsDisplayed().assertIsFocused().performKeyInput { pressKey(Key.Escape) }
            rule.waitForIdle()

            assertFalse(state.tab("right")!!.searchFor(SearchScope.UNFILTERED).active)
            assertEquals(LogViewSectionId.ORIGINAL_B, state.logViewMaximized)

            rule.onRoot().performKeyInput { pressKey(Key.Escape) }
            assertNull(state.logViewMaximized)
        } finally {
            state.close()
        }
    }

    @Test
    fun escapeFromLogRestoresMaximizedViewWhileFindRemainsOpen() {
        val state = stateWithTabs("log")
        try {
            state.logViewMaximized = LogViewSectionId.LOG
            assertTrue(state.openSearchInMaximizedLogView())
            rule.setContent { state.tab("log")?.let { FileView(state, it) } }

            val field = rule.onNodeWithTag("search-field-filtered")
            field.assertIsFocused()
            rule.onNodeWithTag("log-row-log:main-1").performClick()
            field.assertIsNotFocused()
            rule.onRoot().performKeyInput { pressKey(Key.Escape) }

            assertNull(state.logViewMaximized)
            assertTrue(state.tab("log")!!.searchFor(SearchScope.FILTERED).active)
        } finally {
            state.close()
        }
    }

    @Test
    fun ordinaryFindEscapeStillClosesWithoutMaximizeState() {
        val state = stateWithTabs("log")
        try {
            state.openSearch("log", SearchScope.FILTERED)
            rule.setContent { state.tab("log")?.let { FileView(state, it) } }

            rule.onNodeWithTag("search-field-filtered")
                .assertIsFocused()
                .performKeyInput { pressKey(Key.Escape) }

            assertFalse(state.tab("log")!!.searchFor(SearchScope.FILTERED).active)
            assertNull(state.logViewMaximized)
        } finally {
            state.close()
        }
    }

    private fun stateWithTabs(vararg ids: String, showOriginal: Boolean = false): AppState = AppState().apply {
        val row = LogEntry(1, "10:00:00.000", LogLevel.I, "App", "sample")
        tabs = ids.map { id -> mkTab(id, "$id.log", listOf(row)).copy(showUnfiltered = showOriginal) }
        activeTabId = ids.first()
    }
}
