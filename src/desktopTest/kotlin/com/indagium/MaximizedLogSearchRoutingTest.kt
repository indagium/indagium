package com.indagium

import com.indagium.model.CtrlFTarget
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogViewSectionId
import com.indagium.model.SearchMode
import com.indagium.model.SearchScope
import com.indagium.model.SearchScopeBehavior
import com.indagium.model.searchFor
import com.indagium.ui.AppState
import com.indagium.ui.mkTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaximizedLogSearchRoutingTest {
    @Test
    fun maximizedOriginalTargetsItsUnfilteredFindAcrossGlobalCtrlFPreferences() {
        val state = stateWithTabs("log")
        try {
            state.tabs = state.tabs.map { if (it.id == "log") it.copy(showUnfiltered = true) else it }
            state.logViewMaximized = LogViewSectionId.ORIGINAL

            for (target in CtrlFTarget.entries) {
                for (behavior in SearchScopeBehavior.entries) {
                    val openOriginal = target.ordinal % 2 == 0
                    state.updateSettings {
                        it.copy(
                            ctrlFTarget = target,
                            openUnfilteredOnCtrlF = openOriginal,
                            searchScopeBehavior = behavior,
                            lastManualSearchMode = SearchMode.TWO_SEARCHES,
                        )
                    }
                    val settingsBefore = state.settings

                    assertTrue(state.openSearchInMaximizedLogView(), "target=$target behavior=$behavior")

                    val opened = state.tab("log")!!
                    assertTrue(opened.searchFor(SearchScope.UNFILTERED).active)
                    assertFalse(opened.searchFor(SearchScope.FILTERED).active)
                    assertEquals(SearchScope.UNFILTERED, opened.focusedSearchScope)
                    assertEquals(settingsBefore, state.settings, "maximized Find must leave preferences unchanged")
                    assertEquals(LogViewSectionId.ORIGINAL, state.logViewMaximized)
                }
            }
        } finally {
            state.close()
        }
    }

    @Test
    fun maximizedFilteredTargetsFilteredFindWithoutRevealingOriginal() {
        val state = stateWithTabs("log")
        try {
            state.logViewMaximized = LogViewSectionId.LOG
            state.updateSettings {
                it.copy(
                    ctrlFTarget = CtrlFTarget.KEYWORD_REGEX,
                    openUnfilteredOnCtrlF = true,
                    searchScopeBehavior = SearchScopeBehavior.UNFILTERED,
                    lastManualSearchMode = SearchMode.UNFILTERED,
                )
            }
            val settingsBefore = state.settings

            assertTrue(state.openSearchInMaximizedLogView())

            val opened = state.tab("log")!!
            assertTrue(opened.searchFor(SearchScope.FILTERED).active)
            assertFalse(opened.searchFor(SearchScope.UNFILTERED).active)
            assertEquals(SearchScope.FILTERED, opened.focusedSearchScope)
            assertFalse(opened.showUnfiltered)
            assertEquals(settingsBefore, state.settings)
            assertEquals(LogViewSectionId.LOG, state.logViewMaximized)
        } finally {
            state.close()
        }
    }

    @Test
    fun maximizedScopeGetsFocusWhileAnActiveSiblingFindRemainsOpen() {
        val state = stateWithTabs("log")
        try {
            state.tabs = state.tabs.map { if (it.id == "log") it.copy(showUnfiltered = true) else it }
            state.openSearch("log", SearchScope.FILTERED)
            state.setSearchMode("log", SearchMode.TWO_SEARCHES)
            state.logViewMaximized = LogViewSectionId.ORIGINAL

            assertTrue(state.openSearchInMaximizedLogView())

            val bothOpen = state.tab("log")!!
            assertEquals(SearchMode.TWO_SEARCHES, bothOpen.searchMode)
            assertTrue(bothOpen.searchFor(SearchScope.FILTERED).active)
            assertTrue(bothOpen.searchFor(SearchScope.UNFILTERED).active)
            assertEquals(SearchScope.UNFILTERED, bothOpen.focusedSearchScope)

            state.logViewMaximized = LogViewSectionId.LOG
            assertTrue(state.openSearchInMaximizedLogView())
            val filteredFocused = state.tab("log")!!
            assertEquals(SearchMode.TWO_SEARCHES, filteredFocused.searchMode)
            assertTrue(filteredFocused.searchFor(SearchScope.UNFILTERED).active)
            assertEquals(SearchScope.FILTERED, filteredFocused.focusedSearchScope)

            // A closed sibling must stay closed even if the tab remembers Two searches as its mode.
            state.closeSearch("log", SearchScope.UNFILTERED)
            assertTrue(state.openSearchInMaximizedLogView())
            val closedSibling = state.tab("log")!!
            assertTrue(closedSibling.searchFor(SearchScope.FILTERED).active)
            assertFalse(closedSibling.searchFor(SearchScope.UNFILTERED).active)
            assertEquals(SearchScope.FILTERED, closedSibling.focusedSearchScope)
        } finally {
            state.close()
        }
    }

    @Test
    fun maximizedCompareLogUsesTheVisiblePaneInsteadOfStaleSearchFocus() {
        for (maximized in listOf(
            LogViewSectionId.LOG_A,
            LogViewSectionId.ORIGINAL_A,
            LogViewSectionId.LOG_B,
            LogViewSectionId.ORIGINAL_B,
        )) {
            val state = stateWithTabs("left", "right")
            try {
                val targetId = if (maximized == LogViewSectionId.LOG_B || maximized == LogViewSectionId.ORIGINAL_B) "right" else "left"
                state.tabs = state.tabs.map { tab ->
                    if (tab.id == targetId) tab.copy(showUnfiltered = true) else tab
                }
                state.activeTabId = "left"
                state.compareTabId = "right"
                state.compareMode = true
                state.searchFocusTabId = if (maximized == LogViewSectionId.LOG_B || maximized == LogViewSectionId.ORIGINAL_B) {
                    "left"
                } else {
                    "right"
                }
                state.logViewMaximized = maximized
                state.updateSettings { it.copy(ctrlFTarget = CtrlFTarget.TAGS, openUnfilteredOnCtrlF = true) }
                val settingsBefore = state.settings
                val siblingId = if (targetId == "left") "right" else "left"
                val scope = if (maximized == LogViewSectionId.ORIGINAL_A || maximized == LogViewSectionId.ORIGINAL_B) {
                    SearchScope.UNFILTERED
                } else {
                    SearchScope.FILTERED
                }

                assertTrue(state.openSearchInMaximizedLogView(), "maximized=$maximized")

                assertTrue(state.tab(targetId)!!.searchFor(scope).active, "maximized=$maximized")
                assertFalse(state.tab(targetId)!!.searchFor(if (scope == SearchScope.FILTERED) SearchScope.UNFILTERED else SearchScope.FILTERED).active)
                assertFalse(state.tab(siblingId)!!.searchFor(SearchScope.FILTERED).active, "maximized=$maximized")
                assertEquals(scope, state.tab(targetId)!!.focusedSearchScope)
                assertEquals(settingsBefore, state.settings)
                assertEquals(maximized, state.logViewMaximized)
            } finally {
                state.close()
            }
        }
    }

    @Test
    fun ordinaryCompareFindAndExplicitSearchAllLinesRemainFiltered() {
        val state = stateWithTabs("left", "right")
        try {
            state.activeTabId = "left"
            state.compareTabId = "right"
            state.compareMode = true

            state.openSearch("right")
            var right = state.tab("right")!!
            assertTrue(right.searchFor(SearchScope.FILTERED).active)
            assertFalse(right.searchFor(SearchScope.UNFILTERED).active)

            state.closeSearch("right")
            state.openSearch("right", SearchScope.UNFILTERED)
            right = state.tab("right")!!
            assertTrue(right.searchFor(SearchScope.FILTERED).active)
            assertFalse(right.searchFor(SearchScope.UNFILTERED).active)
        } finally {
            state.close()
        }
    }

    private fun stateWithTabs(vararg ids: String): AppState = AppState().apply {
        val row = LogEntry(1, "10:00:00.000", LogLevel.I, "App", "sample")
        tabs = ids.map { id -> mkTab(id, "$id.log", listOf(row)) }
        activeTabId = ids.first()
    }
}
