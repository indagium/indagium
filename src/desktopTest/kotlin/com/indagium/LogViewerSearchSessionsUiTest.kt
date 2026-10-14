package com.indagium

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import com.indagium.model.Filter
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.SearchMode
import com.indagium.model.SearchScope
import com.indagium.model.SequenceDef
import com.indagium.model.searchFor
import com.indagium.ui.AppState
import com.indagium.ui.CompareView
import com.indagium.ui.LogViewer
import com.indagium.ui.SearchNavigationRequest
import com.indagium.ui.mkTab
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Integration coverage for the independent Find sessions in the real split log viewer. */
@OptIn(ExperimentalTestApi::class)
class LogViewerSearchSessionsUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun linkedFilteredDeselectionClearsOriginalSelection() {
        val tabId = "linked-deselect-ui"
        val rows = (1..40).map { id -> LogEntry(id, "10:00:00.$id", LogLevel.I, "App", "row $id") }
        val state = AppState()
        state.tabs = listOf(mkTab(tabId, "linked.log", rows).copy(showUnfiltered = true, selected = setOf(2)))
        state.openSearch(tabId)
        rule.setContent { StateBackedLogViewer(state, tabId) }

        rule.onNodeWithTag("log-row-$tabId:main-2").assertIsSelected().performClick()
        rule.waitUntil(5_000) { state.tab(tabId)!!.selected.isEmpty() }
        rule.onNodeWithTag("log-row-$tabId:original-2").assertIsNotSelected()
    }

    @Test
    fun unmodifiedDoubleClickStillSelectsTextWithoutDeselectingTheRow() {
        val tabId = "unmodified-word-selection-ui"
        val state = AppState()
        state.tabs = listOf(
            mkTab(tabId, "word-selection.log", listOf(
                LogEntry(1, "10:00:00.001", LogLevel.I, "App", "alpha beta gamma"),
            )),
        )
        rule.setContent { StateBackedLogViewer(state, tabId) }

        val row = rule.onNodeWithTag("log-row-$tabId:main-1")
        row.performMouseInput { doubleClick(Offset(180f, center.y)) }
        val text = rule.onNodeWithText("alpha beta gamma", substring = true)
        rule.waitUntil(5_000) {
            runCatching {
                !text.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange].collapsed
            }.getOrDefault(false)
        }
        rule.runOnIdle { assertEquals(setOf(1), state.tab(tabId)!!.selected) }
        row.assertIsSelected()
        val selectedTextRange = text.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertFalse(selectedTextRange.collapsed)
        assertTrue(selectedTextRange != TextRange.Zero)
    }

    @Test
    fun linkedModifierClicksMirrorTheFullFilteredSelection() {
        val tabId = "linked-modifiers-ui"
        val rows = (1..40).map { id -> LogEntry(id, "10:00:00.$id", LogLevel.I, "App", "row $id") }
        val state = AppState()
        state.tabs = listOf(mkTab(tabId, "modifiers.log", rows).copy(showUnfiltered = true))
        state.openSearch(tabId)
        rule.setContent { StateBackedLogViewer(state, tabId) }

        rule.onNodeWithTag("log-row-$tabId:main-2").performClick()
        rule.onNodeWithTag("log-row-$tabId:original-2").assertIsSelected()
        rule.onRoot().performKeyInput { keyDown(Key.CtrlLeft) }
        rule.onNodeWithTag("log-row-$tabId:main-3").performClick()
        rule.onRoot().performKeyInput { keyUp(Key.CtrlLeft) }
        rule.runOnIdle { assertEquals(setOf(2, 3), state.tab(tabId)!!.selected) }
        rule.onNodeWithTag("log-row-$tabId:original-2").assertIsSelected()
        rule.onNodeWithTag("log-row-$tabId:original-3").assertIsSelected()

        rule.onRoot().performKeyInput { keyDown(Key.ShiftLeft) }
        rule.onNodeWithTag("log-row-$tabId:main-5").performClick()
        rule.onRoot().performKeyInput { keyUp(Key.ShiftLeft) }
        rule.runOnIdle { assertEquals(setOf(3, 4, 5), state.tab(tabId)!!.selected) }
        rule.onNodeWithTag("log-row-$tabId:original-2").assertIsNotSelected()
        for (id in 3..5) rule.onNodeWithTag("log-row-$tabId:original-$id").assertIsSelected()
    }

    @Test
    fun syncToggleDoesNotReplaySelectionAndResumesMirroringOnNextFilteredClick() {
        val tabId = "linked-toggle-roundtrip-ui"
        val rows = (1..100).map { id -> LogEntry(id, "10:00:00.$id", LogLevel.I, "App", "row $id") }
        val state = AppState()
        state.tabs = listOf(mkTab(tabId, "linked-toggle.log", rows))
        state.setSearchMode(tabId, SearchMode.TWO_SEARCHES)
        rule.setContent { StateBackedLogViewer(state, tabId) }

        val store = state.logViewerScrollStateStore
        rule.runOnIdle { store.lazyState("$tabId:original").requestScrollToItem(54) }
        rule.waitUntil(5_000) { store.lazyState("$tabId:original").firstVisibleItemIndex >= 54 }
        val originalRow = rule.onNodeWithTag("log-row-$tabId:original-55")
        originalRow.performClick()
        originalRow.assertIsSelected()
        rule.runOnIdle { store.lazyState("$tabId:original").requestScrollToItem(49) }
        rule.waitUntil(5_000) { store.lazyState("$tabId:original").firstVisibleItemIndex >= 49 }
        var originalAnchor = 0
        rule.runOnIdle { originalAnchor = store.lazyState("$tabId:original").firstVisibleItemIndex }

        // Turn linking off through the real Find dropdown. A subsequent Filtered click must keep
        // Original's independent selection and scroll anchor local.
        rule.onNodeWithTag("search-mode-dropdown").performClick()
        rule.onNodeWithTag("search-link-toggle").performClick()
        rule.onNodeWithText("Independent").assertExists()
        rule.onNodeWithTag("search-mode-dropdown").performClick()
        rule.runOnIdle { store.lazyState("$tabId:main").requestScrollToItem(49) }
        rule.waitUntil(5_000) { store.lazyState("$tabId:main").firstVisibleItemIndex >= 49 }
        rule.onNodeWithTag("log-row-$tabId:main-50").performClick()
        rule.waitUntil(5_000) { state.tab(tabId)!!.selected == setOf(50) }
        rule.runOnIdle {
            assertEquals(originalAnchor, store.lazyState("$tabId:original").firstVisibleItemIndex)
        }
        originalRow.assertIsSelected()
        rule.onNodeWithTag("log-row-$tabId:original-50").assertIsNotSelected()

        // Re-enabling is not itself a selection event: Original stays where it was until the next
        // Filtered click, which resumes both selection and scroll mirroring.
        rule.onNodeWithTag("search-mode-dropdown").performClick()
        rule.onNodeWithTag("search-link-toggle").performClick()
        rule.onNodeWithText("Linked").assertExists()
        rule.onNodeWithTag("search-mode-dropdown").performClick()
        rule.runOnIdle {
            assertEquals(originalAnchor, store.lazyState("$tabId:original").firstVisibleItemIndex)
        }
        originalRow.assertIsSelected()
        rule.onNodeWithTag("log-row-$tabId:original-50").assertIsNotSelected()

        rule.runOnIdle { store.lazyState("$tabId:main").requestScrollToItem(69) }
        rule.waitUntil(5_000) { store.lazyState("$tabId:main").firstVisibleItemIndex >= 69 }
        rule.onNodeWithTag("log-row-$tabId:main-70").performClick()
        rule.waitUntil(5_000) {
            state.tab(tabId)!!.selected == setOf(70) &&
                store.lazyState("$tabId:original").firstVisibleItemIndex > originalAnchor
        }
        rule.onNodeWithTag("log-row-$tabId:original-70").assertIsDisplayed().assertIsSelected()
        rule.runOnIdle { store.lazyState("$tabId:original").requestScrollToItem(49) }
        rule.waitUntil(5_000) { store.lazyState("$tabId:original").firstVisibleItemIndex >= 49 }
        rule.onNodeWithTag("log-row-$tabId:original-55").assertIsNotSelected()
    }

    @Test
    fun syncToggleRefreshesCallbacksForRowsThatStayMounted() {
        val tabId = "linked-toggle-mounted-row-ui"
        val rows = (1..100).map { id -> LogEntry(id, "10:00:00.$id", LogLevel.I, "App", "row $id") }
        val state = AppState()
        // Start with row 50 selected only in Filtered. Original then gets its own selection while
        // linking is on, and that local selection must survive turning linking off.
        state.tabs = listOf(mkTab(tabId, "linked-toggle-mounted-row.log", rows).copy(selected = setOf(50)))
        state.setSearchMode(tabId, SearchMode.TWO_SEARCHES)
        rule.setContent { StateBackedLogViewer(state, tabId) }

        val store = state.logViewerScrollStateStore
        rule.runOnIdle { store.lazyState("$tabId:original").requestScrollToItem(79) }
        rule.waitUntil(5_000) { store.lazyState("$tabId:original").firstVisibleItemIndex >= 79 }
        val originalRow = rule.onNodeWithTag("log-row-$tabId:original-80")
        originalRow.performClick()
        originalRow.assertIsSelected()

        // Keep Filtered row 50 composed at the same viewport position for the whole round trip.
        rule.runOnIdle { store.lazyState("$tabId:main").requestScrollToItem(49) }
        rule.waitUntil(5_000) { store.lazyState("$tabId:main").firstVisibleItemIndex >= 49 }
        val filteredRow = rule.onNodeWithTag("log-row-$tabId:main-50")
        filteredRow.assertIsDisplayed().assertIsSelected()
        val originalAnchor = store.lazyState("$tabId:original").firstVisibleItemIndex

        // Off click deselects the already-selected Filtered row. It must not clear Original's
        // independent selection or move its anchor.
        rule.onNodeWithTag("search-mode-dropdown").performClick()
        rule.onNodeWithTag("search-link-toggle").performClick()
        rule.onNodeWithText("Independent").assertExists()
        rule.onNodeWithTag("search-mode-dropdown").performClick() // dismiss the native popup
        filteredRow.assertIsDisplayed()
        val firstClickAt = System.currentTimeMillis()
        filteredRow.performClick()
        rule.waitUntil(5_000) {
            state.tab(tabId)!!.selected.isEmpty() && System.currentTimeMillis() - firstClickAt >= 600
        }
        rule.runOnIdle {
            assertEquals(originalAnchor, store.lazyState("$tabId:original").firstVisibleItemIndex)
        }
        originalRow.assertIsSelected()

        // Re-enabling alone must not replay Filtered's current empty selection into Original.
        rule.onNodeWithTag("search-mode-dropdown").performClick()
        rule.onNodeWithTag("search-link-toggle").performClick()
        rule.onNodeWithText("Linked").assertExists()
        rule.onNodeWithTag("search-mode-dropdown").performClick()
        filteredRow.assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(originalAnchor, store.lazyState("$tabId:original").firstVisibleItemIndex)
        }
        originalRow.assertIsSelected()

        // This is the exact same already-composed row. Its next click selects it again and must
        // use the latest Linked callback, mirroring selection and scrolling Original to row 50.
        filteredRow.performClick()
        rule.waitUntil(5_000) { state.tab(tabId)!!.selected == setOf(50) }
        rule.waitUntil(5_000) { store.lazyState("$tabId:original").firstVisibleItemIndex < originalAnchor }
        rule.onNodeWithTag("log-row-$tabId:original-50").assertIsDisplayed().assertIsSelected()
        rule.runOnIdle { assertEquals(setOf(50), state.tab(tabId)!!.selected) }
    }

    @Test
    fun delayedDeselectionKeepsTheSyncPolicyFromItsClick() {
        val tabId = "linked-delayed-toggle-ui"
        val rows = (1..100).map { id -> LogEntry(id, "10:00:00.$id", LogLevel.I, "App", "row $id") }
        val state = AppState()
        state.tabs = listOf(mkTab(tabId, "linked-delayed-toggle.log", rows).copy(selected = setOf(50)))
        state.setSearchMode(tabId, SearchMode.TWO_SEARCHES)
        rule.setContent { StateBackedLogViewer(state, tabId) }

        val store = state.logViewerScrollStateStore
        rule.runOnIdle { store.lazyState("$tabId:original").requestScrollToItem(79) }
        rule.waitUntil(5_000) { store.lazyState("$tabId:original").firstVisibleItemIndex >= 79 }
        val originalRow = rule.onNodeWithTag("log-row-$tabId:original-80")
        originalRow.performClick()
        originalRow.assertIsSelected()
        val originalAnchor = store.lazyState("$tabId:original").firstVisibleItemIndex
        rule.runOnIdle { store.lazyState("$tabId:main").requestScrollToItem(49) }
        rule.waitUntil(5_000) { store.lazyState("$tabId:main").firstVisibleItemIndex >= 49 }
        val filteredRow = rule.onNodeWithTag("log-row-$tabId:main-50")
        filteredRow.assertIsDisplayed().assertIsSelected()

        rule.onNodeWithTag("search-mode-dropdown").performClick()
        rule.onNodeWithTag("search-link-toggle").performClick()
        rule.onNodeWithText("Independent").assertExists()
        rule.onNodeWithTag("search-mode-dropdown").performClick()

        // Freeze Compose time so the selected-row delay stays pending while the UI toggles back
        // to Linked. The delayed Off click must retain its original policy when it finally runs.
        rule.mainClock.autoAdvance = false
        filteredRow.performClick()
        rule.onNodeWithTag("search-mode-dropdown").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("search-link-toggle").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Linked").assertExists()
        rule.onNodeWithTag("search-mode-dropdown").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()

        rule.runOnIdle {
            assertTrue(state.tab(tabId)!!.selected.isEmpty())
            assertEquals(originalAnchor, store.lazyState("$tabId:original").firstVisibleItemIndex)
        }
        originalRow.assertIsSelected()
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun syncToggleRefreshesCallbacksForMountedGroupHeaders() {
        val tabId = "linked-toggle-header-ui"
        val rows = buildList {
            add(LogEntry(1, "10:00:00.001", LogLevel.I, "App", "start marker"))
            add(LogEntry(2, "10:00:00.002", LogLevel.I, "App", "inside group"))
            add(LogEntry(3, "10:00:00.003", LogLevel.I, "App", "end marker"))
            for (id in 4..100) add(LogEntry(id, "10:00:00.$id", LogLevel.I, "App", "row $id"))
        }
        val sequence = SequenceDef(
            "header-sync", "start marker", priority = 1, color = Color.Red,
            tag = "App", endMatchText = "end marker",
        )
        val state = AppState()
        state.tabs = listOf(
            mkTab(tabId, "linked-toggle-header.log", rows)
                .copy(filter = Filter(sequences = listOf(sequence)), selected = setOf(1)),
        )
        state.setSearchMode(tabId, SearchMode.TWO_SEARCHES)
        rule.setContent { StateBackedLogViewer(state, tabId) }

        val store = state.logViewerScrollStateStore
        rule.runOnIdle { store.lazyState("$tabId:original").requestScrollToItem(77) }
        rule.waitUntil(5_000) { store.lazyState("$tabId:original").firstVisibleItemIndex >= 77 }
        val originalRow = rule.onNodeWithTag("log-row-$tabId:original-80")
        originalRow.performClick()
        originalRow.assertIsSelected()
        val filteredHeader = rule.onNodeWithText("start marker", substring = true)
        filteredHeader.assertExists()
        val originalAnchor = store.lazyState("$tabId:original").firstVisibleItemIndex

        // The header remains at the top of Filtered while the independent setting changes twice.
        rule.onNodeWithTag("search-mode-dropdown").performClick()
        rule.onNodeWithTag("search-link-toggle").performClick()
        rule.onNodeWithText("Independent").assertExists()
        rule.onNodeWithTag("search-mode-dropdown").performClick()
        val firstClickAt = System.currentTimeMillis()
        filteredHeader.performClick() // deselect the preselected header while Independent
        rule.waitUntil(5_000) {
            state.tab(tabId)!!.selected.isEmpty() && System.currentTimeMillis() - firstClickAt >= 600
        }
        originalRow.assertIsSelected()
        rule.runOnIdle {
            assertEquals(originalAnchor, store.lazyState("$tabId:original").firstVisibleItemIndex)
        }

        rule.onNodeWithTag("search-mode-dropdown").performClick()
        rule.onNodeWithTag("search-link-toggle").performClick()
        rule.onNodeWithText("Linked").assertExists()
        rule.onNodeWithTag("search-mode-dropdown").performClick()
        originalRow.assertIsSelected()
        rule.runOnIdle {
            assertEquals(originalAnchor, store.lazyState("$tabId:original").firstVisibleItemIndex)
        }

        // The same header is still composed. Its next click must mirror selection and scroll.
        filteredHeader.performClick()
        rule.waitUntil(5_000) { state.tab(tabId)!!.selected == setOf(1) }
        rule.waitUntil(5_000) { store.lazyState("$tabId:original").firstVisibleItemIndex < originalAnchor }
        rule.runOnIdle { store.lazyState("$tabId:original").requestScrollToItem(77) }
        rule.waitUntil(5_000) { store.lazyState("$tabId:original").firstVisibleItemIndex >= 77 }
        originalRow.assertIsNotSelected()
    }

    @Test
    fun linkedFindMovesBothPanelsWithoutChangingTheOtherQuery() {
        val tabId = "linked-find-ui"
        val rows = (1..100).map { id ->
            LogEntry(id, "10:00:00.$id", LogLevel.I, "App", when (id) {
                70 -> "filtered needle"
                90 -> "original needle"
                else -> "row $id"
            })
        }
        val state = AppState()
        state.tabs = listOf(mkTab(tabId, "linked-find.log", rows))
        state.setSearchMode(tabId, SearchMode.TWO_SEARCHES)
        state.setSearchQuery(tabId, "filtered needle", SearchScope.FILTERED)
        state.setSearchQuery(tabId, "original needle", SearchScope.UNFILTERED)
        rule.waitUntil(5_000) {
            state.tab(tabId)!!.searchFor(SearchScope.FILTERED).matchIds.toList() == listOf(70) &&
                state.tab(tabId)!!.searchFor(SearchScope.UNFILTERED).matchIds.toList() == listOf(90)
        }
        rule.setContent { StateBackedLogViewer(state, tabId) }
        rule.onNodeWithTag("search-next-filtered").performClick()
        rule.waitUntil(5_000) { state.searchNavigationFor(tabId, SearchScope.FILTERED) == null }
        rule.onNodeWithTag("log-row-$tabId:original-70").assertIsDisplayed().assertIsSelected()
        rule.onNodeWithTag("search-next-unfiltered").performClick()
        rule.waitUntil(5_000) { state.searchNavigationFor(tabId, SearchScope.UNFILTERED) == null }
        rule.onNodeWithTag("log-row-$tabId:main-90").assertIsDisplayed().assertIsSelected()
        rule.onNodeWithTag("log-row-$tabId:original-90").assertIsDisplayed().assertIsSelected()
        rule.onNodeWithTag("search-field-filtered").assertTextEquals("filtered needle")
        rule.onNodeWithTag("search-field-unfiltered").assertTextEquals("original needle")
    }

    @Test
    fun twoSearchesKeepPanelNavigationAndSelectionLocalWhenSyncIsTurnedOff() {
        val tabId = "search-scope-ui"
        val rows = (1..80).map { id ->
            LogEntry(
                id = id,
                ts = "10:00:%02d.%03d".format(id / 1000, id % 1000),
                level = LogLevel.I,
                tag = "App",
                msg = when (id) {
                    70 -> "filtered needle"
                    75 -> "original needle"
                    else -> "ordinary row $id"
                },
            )
        }
        val state = AppState()
        state.tabs = listOf(mkTab(tabId, "search-scope-ui.log", rows))
        state.setSearchMode(tabId, SearchMode.TWO_SEARCHES)
        state.setSearchQuery(tabId, "filtered needle", SearchScope.FILTERED)
        state.setSearchQuery(tabId, "original needle", SearchScope.UNFILTERED)
        rule.waitUntil(5_000) {
            state.tab(tabId)?.searchFor(SearchScope.FILTERED)?.matchIds?.contentEquals(intArrayOf(70)) == true &&
                state.tab(tabId)?.searchFor(SearchScope.UNFILTERED)?.matchIds?.contentEquals(intArrayOf(75)) == true
        }

        rule.setContent { StateBackedLogViewer(state, tabId) }
        rule.onNodeWithTag("search-field-filtered").assertTextEquals("filtered needle")
        rule.onNodeWithTag("search-field-unfiltered").assertTextEquals("original needle")

        // Use the actual two-searches control to disable mirroring, then navigate with the
        // Filtered field. Only the main panel should move to its distant match.
        rule.onNodeWithTag("search-mode-dropdown").performClick()
        rule.onNodeWithTag("search-mode-dropdown").assertExists()
        rule.onNodeWithTag("search-field-filtered").assertExists()
        rule.onNodeWithTag("search-link-toggle").performClick()
        rule.onNodeWithText("Independent").assertExists()
        rule.onNodeWithTag("search-next-filtered").performClick()
        val filteredField = rule.onNodeWithTag("search-field-filtered")
        filteredField.assertIsFocused()
        filteredField.performKeyInput { pressKey(Key.Enter) }
        filteredField.performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Enter)
            keyUp(Key.ShiftLeft)
        }
        filteredField.assertIsFocused()

        val store = state.logViewerScrollStateStore
        rule.waitUntil(5_000) { store.lazyState("$tabId:main").firstVisibleItemIndex > 0 }
        rule.runOnIdle {
            assertEquals(0, store.lazyState("$tabId:original").firstVisibleItemIndex)
        }

        val filteredMatch = rule.onNodeWithTag("log-row-$tabId:main-70")
        filteredMatch.assertIsDisplayed()
        filteredMatch.performClick()
        filteredMatch.assertIsSelected()
        rule.runOnIdle {
            assertEquals(setOf(70), state.tab(tabId)!!.selected)
            assertEquals(0, store.lazyState("$tabId:original").firstVisibleItemIndex)
        }

        // Navigation from the other field owns the Original anchor and must not move Filtered.
        val filteredIndexBefore = store.lazyState("$tabId:main").firstVisibleItemIndex
        rule.onNodeWithTag("search-next-unfiltered").performClick()
        val unfilteredField = rule.onNodeWithTag("search-field-unfiltered")
        unfilteredField.assertIsFocused()
        rule.runOnIdle { assertEquals(SearchScope.UNFILTERED, state.tab(tabId)!!.focusedSearchScope) }
        unfilteredField.performKeyInput { pressKey(Key.Enter) }
        unfilteredField.performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Enter)
            keyUp(Key.ShiftLeft)
        }
        unfilteredField.assertIsFocused()
        rule.waitUntil(5_000) { store.lazyState("$tabId:original").firstVisibleItemIndex > 0 }
        rule.runOnIdle {
            assertEquals(filteredIndexBefore, store.lazyState("$tabId:main").firstVisibleItemIndex)
        }
        unfilteredField.performKeyInput { pressKey(Key.Escape) }
        rule.onAllNodesWithTag("search-field-unfiltered").assertCountEquals(0)
        rule.onNodeWithTag("search-field-filtered").assertExists()
        // Unfiltered navigation did move only Original to its own match. The main-panel anchor
        // remains where Filtered navigation left it, despite the Original row now being selected.
        rule.onNodeWithTag("log-row-$tabId:original-75").assertIsSelected()
        rule.runOnIdle {
            assertEquals(filteredIndexBefore, store.lazyState("$tabId:main").firstVisibleItemIndex)
        }
    }

    @Test
    fun turningSyncOffBeforeAQueuedSearchNavigationPreventsOriginalMovement() {
        val tabId = "search-pending-sync-ui"
        val rows = (1..80).map { id ->
            LogEntry(id, "10:00:00.$id", LogLevel.I, "App", if (id == 75) "needle" else "row $id")
        }
        val state = AppState()
        state.tabs = listOf(mkTab(tabId, "pending.log", rows))
        state.setSearchMode(tabId, SearchMode.TWO_SEARCHES)
        state.setSearchQuery(tabId, "needle", SearchScope.FILTERED)
        rule.waitUntil(5_000) {
            state.tab(tabId)?.searchFor(SearchScope.FILTERED)?.matchIds?.contentEquals(intArrayOf(75)) == true
        }
        // Queue while sync is On, then turn it Off before LogViewer composes/consumes the request.
        state.searchNext(tabId, SearchScope.FILTERED)
        state.updateSettings { it.copy(syncSplitPanelSelectionAndScroll = false) }

        rule.setContent { StateBackedLogViewer(state, tabId) }
        val store = state.logViewerScrollStateStore
        rule.waitUntil(5_000) {
            store.lazyState("$tabId:main").firstVisibleItemIndex > 0 && state.searchNavigationFor(tabId, SearchScope.FILTERED) == null
        }
        rule.runOnIdle {
            assertEquals(0, store.lazyState("$tabId:original").firstVisibleItemIndex)
            assertEquals(setOf(75), state.tab(tabId)!!.selected)
        }
        rule.runOnIdle { store.lazyState("$tabId:original").requestScrollToItem(75) }
        rule.waitUntil(5_000) { store.lazyState("$tabId:original").firstVisibleItemIndex >= 75 }
        rule.onNodeWithTag("log-row-$tabId:original-75").assertIsNotSelected()
    }

    @Test
    fun independentPreferenceAppliesInEverySplitModeAndInvalidatesPendingMirrorWork() {
        val tabId = "search-effective-linking-ui"
        val rows = (1..80).map { id ->
            LogEntry(id, "10:00:00.$id", LogLevel.I, "App", if (id == 70) "needle" else "row $id")
        }
        val state = AppState()
        state.tabs = listOf(mkTab(tabId, "effective-linking.log", rows))
        state.setSearchMode(tabId, SearchMode.TWO_SEARCHES)
        state.setSearchQuery(tabId, "needle", SearchScope.FILTERED)
        rule.waitUntil(5_000) {
            state.tab(tabId)?.searchFor(SearchScope.FILTERED)?.matchIds?.contentEquals(intArrayOf(70)) == true
        }

        rule.setContent { StateBackedLogViewer(state, tabId) }

        val store = state.logViewerScrollStateStore
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(0, store.lazyState("$tabId:original").firstVisibleItemIndex)
        }

        // Queue a Find request while Linked, then turn the global setting Off before the viewer
        // consumes it. Its old revision must not mirror into Original.
        var queuedRevision = -1L
        rule.runOnIdle {
            state.searchNext(tabId, SearchScope.FILTERED)
            queuedRevision = state.searchNavigationFor(tabId, SearchScope.FILTERED)!!.syncRevision
            state.updateSettings { it.copy(syncSplitPanelSelectionAndScroll = false) }
        }
        assertTrue(state.splitSyncRevision > queuedRevision)
        rule.waitUntil(5_000) {
            store.lazyState("$tabId:main").firstVisibleItemIndex > 0 &&
                state.searchNavigationFor(tabId, SearchScope.FILTERED) == null
        }
        rule.runOnIdle {
            assertEquals(0, store.lazyState("$tabId:original").firstVisibleItemIndex)
            assertFalse(state.settings.syncSplitPanelSelectionAndScroll)
        }

        // Switching to the single-field mode does not override the saved global Off setting.
        state.setSearchMode(tabId, SearchMode.FILTERED)
        rule.onNodeWithTag("search-next-filtered").performClick()
        rule.waitUntil(5_000) { store.lazyState("$tabId:main").firstVisibleItemIndex > 0 }
        rule.runOnIdle {
            assertEquals(0, store.lazyState("$tabId:original").firstVisibleItemIndex)
        }

        // Ordinary Filtered row selection also stays local in the single-field split.
        rule.runOnIdle { store.lazyState("$tabId:main").requestScrollToItem(19) }
        rule.waitUntil(5_000) { store.lazyState("$tabId:main").firstVisibleItemIndex == 19 }
        rule.onNodeWithTag("log-row-$tabId:main-20").assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(setOf(20), state.tab(tabId)!!.selected)
            assertEquals(0, store.lazyState("$tabId:original").firstVisibleItemIndex)
        }

        // Returning to Two searches keeps the same global choice.
        rule.onNodeWithTag("search-mode-dropdown").performClick()
        rule.onNodeWithTag("search-mode-option-two_searches").performClick()
        rule.waitUntil(5_000) { state.tab(tabId)?.searchMode == SearchMode.TWO_SEARCHES }
        rule.runOnIdle {
            assertFalse(state.settings.syncSplitPanelSelectionAndScroll)
            store.lazyState("$tabId:original").requestScrollToItem(0)
            store.lazyState("$tabId:main").requestScrollToItem(0)
        }
        rule.waitUntil(5_000) {
            store.lazyState("$tabId:original").firstVisibleItemIndex == 0 &&
                store.lazyState("$tabId:main").firstVisibleItemIndex == 0
        }
        rule.onNodeWithTag("search-mode-dropdown").performClick()
        rule.onNodeWithText("Independent").assertExists()
        rule.onNodeWithTag("search-mode-dropdown").performClick()
        rule.onNodeWithTag("search-next-filtered").performClick()
        rule.waitUntil(5_000) { store.lazyState("$tabId:main").firstVisibleItemIndex > 0 }
        rule.runOnIdle {
            assertEquals(0, store.lazyState("$tabId:original").firstVisibleItemIndex)
        }
    }

    @Test
    fun singlePanelFindUsesScopedNavigationAndRevealsCollapsedMatches() {
        val tabId = "search-single-collapsed-ui"
        val rows = listOf(
            LogEntry(1, "10:00:00.001", LogLevel.I, "App", "start marker"),
            LogEntry(2, "10:00:00.002", LogLevel.I, "App", "needle inside collapsed group"),
            LogEntry(3, "10:00:00.003", LogLevel.I, "App", "end marker"),
            LogEntry(4, "10:00:00.004", LogLevel.I, "App", "after group"),
        )
        val sequence = SequenceDef(
            "seq", "start marker", priority = 1, color = Color.Red,
            tag = "App", endMatchText = "end marker",
        )
        val state = AppState()
        state.tabs = listOf(mkTab(tabId, "collapsed.log", rows).copy(filter = Filter(sequences = listOf(sequence))))
        state.openSearch(tabId, SearchScope.FILTERED)
        state.setSearchQuery(tabId, "needle", SearchScope.FILTERED)
        rule.waitUntil(5_000) {
            state.tab(tabId)?.searchFor(SearchScope.FILTERED)?.matchIds?.contentEquals(intArrayOf(2)) == true
        }

        // FileView passes navigation through the scoped map. A single-panel LogViewer must consume
        // that request, validate its owner revision, expand the collapsed group, and reveal row 2.
        rule.setContent { StateBackedLogViewer(state, tabId) }
        rule.onNodeWithTag("search-next-filtered").performClick()
        rule.waitUntil(5_000) {
            state.searchNavigationFor(tabId, SearchScope.FILTERED) == null &&
                state.tab(tabId)!!.expanded.isNotEmpty()
        }
        rule.onNodeWithTag("log-row-$tabId:main-2").assertIsDisplayed()
    }

    @Test
    fun compareViewerConsumesScopedSinglePanelFindNavigation() {
        val leftId = "search-compare-left-ui"
        val rightId = "search-compare-right-ui"
        val rows = (1..100).map { id ->
            LogEntry(id, "10:00:00.$id", LogLevel.I, "App", if (id == 90) "needle" else "row $id")
        }
        val state = AppState()
        state.tabs = listOf(mkTab(leftId, "left.log", rows), mkTab(rightId, "right.log", rows))
        state.activeTabId = leftId
        state.compareTabId = rightId
        state.updateCompareMode(true)
        state.openSearch(rightId)
        state.setSearchQuery(rightId, "needle", SearchScope.FILTERED)
        rule.waitUntil(5_000) {
            state.tab(rightId)?.searchFor(SearchScope.FILTERED)?.matchIds?.contentEquals(intArrayOf(90)) == true
        }

        rule.setContent { CompareView(state) }
        rule.onNodeWithTag("search-next-filtered").performClick()
        rule.waitUntil(5_000) {
            state.searchNavigationFor(rightId, SearchScope.FILTERED) == null &&
                state.logViewerScrollStateStore.lazyState("$rightId:main").firstVisibleItemIndex > 0
        }
    }

    @Test
    fun singlePanelDiscardsNavigationFromAnOldOwningSearchRevision() {
        val tabId = "search-stale-single-ui"
        val rows = (1..80).map { id ->
            LogEntry(id, "10:00:00.$id", LogLevel.I, "App", if (id == 70) "needle" else "row $id")
        }
        val state = AppState()
        state.tabs = listOf(mkTab(tabId, "stale.log", rows))
        state.openSearch(tabId, SearchScope.FILTERED)
        state.setSearchQuery(tabId, "needle", SearchScope.FILTERED)
        rule.waitUntil(5_000) {
            state.tab(tabId)?.searchFor(SearchScope.FILTERED)?.matchIds?.contentEquals(intArrayOf(70)) == true
        }
        state.searchNext(tabId, SearchScope.FILTERED)
        val currentRequest = state.searchNavigationFor(tabId, SearchScope.FILTERED)!!
        val staleRequest = currentRequest.copy(inputRevision = currentRequest.inputRevision - 1)

        rule.setContent { StateBackedLogViewer(state, tabId, searchNavigationOverride = staleRequest) }
        rule.waitUntil(5_000) { state.searchNavigationFor(tabId, SearchScope.FILTERED) == null }
        rule.runOnIdle {
            assertEquals(0, state.logViewerScrollStateStore.lazyState("$tabId:main").firstVisibleItemIndex)
        }
    }

    @Test
    fun closingOriginalFindRestoresItsPanelFocusWithoutAutofocusingFiltered() {
        val tabId = "search-close-focus-ui"
        val rows = (1..80).map { id ->
            LogEntry(
                id, "10:00:00.$id", LogLevel.I, "App",
                when (id) {
                    30, 70 -> "filtered needle"
                    40, 75 -> "original needle"
                    else -> "row $id"
                },
            )
        }
        val state = AppState()
        state.tabs = listOf(mkTab(tabId, "focus.log", rows))
        state.setSearchMode(tabId, SearchMode.TWO_SEARCHES)
        state.setSearchQuery(tabId, "filtered needle", SearchScope.FILTERED)
        state.setSearchQuery(tabId, "original needle", SearchScope.UNFILTERED)
        rule.waitUntil(5_000) {
            state.tab(tabId)?.searchFor(SearchScope.FILTERED)?.matchIds?.size == 2 &&
                state.tab(tabId)?.searchFor(SearchScope.UNFILTERED)?.matchIds?.size == 2
        }
        state.searchNext(tabId, SearchScope.FILTERED)
        state.searchNext(tabId, SearchScope.UNFILTERED)
        SearchScope.entries.forEach { scope ->
            state.searchNavigationFor(tabId, scope)?.let { state.consumeSearchNavigation(it.id) }
        }
        val filteredBefore = state.tab(tabId)!!.searchFor(SearchScope.FILTERED)
        val originalBefore = state.tab(tabId)!!.searchFor(SearchScope.UNFILTERED)

        rule.setContent { StateBackedLogViewer(state, tabId) }
        val original = rule.onNodeWithTag("search-field-unfiltered")
        original.performClick()
        original.performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()

        rule.onNodeWithTag("search-field-filtered").assertIsNotFocused()
        rule.runOnIdle {
            val tab = state.tab(tabId)!!
            assertFalse(tab.searchFor(SearchScope.UNFILTERED).active)
            assertEquals(originalBefore.query, tab.searchFor(SearchScope.UNFILTERED).query)
            assertEquals(originalBefore.currentIdx, tab.searchFor(SearchScope.UNFILTERED).currentIdx)
            assertEquals(filteredBefore.query, tab.searchFor(SearchScope.FILTERED).query)
            assertEquals(filteredBefore.currentIdx, tab.searchFor(SearchScope.FILTERED).currentIdx)
            assertTrue(tab.searchFor(SearchScope.FILTERED).active)
        }

        // The explicit Unfiltered shortcut route can reopen the closed slot and focus it again.
        rule.runOnIdle { state.openSearch(tabId, SearchScope.UNFILTERED) }
        rule.onNodeWithTag("search-field-unfiltered").assertIsFocused()
    }

    @Test
    fun searchAllLinesMovesTheQueryToAFocusedUnfilteredFieldInOriginal() {
        val tabId = "search-all-lines-ui"
        val rows = listOf(
            LogEntry(1, "10:00:00.001", LogLevel.I, "App", "visible row"),
            LogEntry(2, "10:00:00.002", LogLevel.W, "App", "hidden target"),
            LogEntry(3, "10:00:00.003", LogLevel.I, "App", "another visible row"),
        )
        val state = AppState()
        state.tabs = listOf(mkTab(tabId, "all-lines.log", rows))
        state.toggleLevel(tabId, LogLevel.W)
        state.openSearch(tabId, SearchScope.FILTERED)

        rule.setContent { StateBackedLogViewer(state, tabId) }
        val field = rule.onNodeWithTag("search-field-filtered")
        field.performTextInput("hidden")
        rule.waitUntil(5_000) {
            val slot = state.tab(tabId)?.searchFor(SearchScope.FILTERED)
            slot != null && slot.query == "hidden" && !slot.isSearching
        }
        // Nothing changes on its own: Original stays hidden until the user asks.
        assertFalse(state.tab(tabId)!!.showUnfiltered)

        rule.onNodeWithTag("search-all-lines").performClick()
        rule.waitUntil(5_000) { state.tab(tabId)?.searchFor(SearchScope.UNFILTERED)?.matchIds?.toList() == listOf(2) }

        val original = rule.onNodeWithTag("search-field-unfiltered")
        original.assertTextEquals("hidden")
        original.assertIsFocused()
        rule.onNodeWithTag("log-row-$tabId:original-2").assertExists()
        rule.onAllNodesWithTag("search-field-filtered").assertCountEquals(0)
    }
}

@Composable
private fun StateBackedLogViewer(
    state: AppState,
    tabId: String,
    searchNavigationOverride: SearchNavigationRequest? = null,
) {
    val tab = state.tab(tabId) ?: return
    val liveSearchRequests = SearchScope.entries.mapNotNull { scope ->
        state.searchNavigationFor(tabId, scope)?.let { scope to it }
    }.toMap()
    val searchRequests = searchNavigationOverride?.let { mapOf(it.scope to it) } ?: liveSearchRequests
    LogViewer(
        tab = tab,
        modifier = Modifier.fillMaxSize(),
        settings = state.settings,
        onSelRow = { id, multi, range -> state.selRow(tabId, id, multi, range) },
        onSelRowRange = { state.setSelectedRows(tabId, it) },
        onCtxMenu = { _, _, _, _, _ -> },
        onToggleGroup = { state.toggleGroup(tabId, it) },
        onClearFilter = { state.requestClearFilter(tabId) },
        onExpandAll = { state.expandAll(tabId) },
        onCollapseAll = { state.collapseAll(tabId) },
        onToggleUnfiltered = { state.toggleUnfiltered(tabId) },
        onOpenSearch = { state.openSearch(tabId) },
        onExportTxt = {},
        onExportCsv = {},
        scrollStateStore = state.logViewerScrollStateStore,
        searchNavigationRequests = searchRequests,
        onConsumeSearchNavigation = { state.consumeSearchNavigation(it) },
        onSearchQueryChange = { scope, query -> state.setSearchQuery(tabId, query, scope) },
        onSearchToggleCase = { state.toggleSearchCase(tabId, it) },
        onSearchNext = { state.searchNext(tabId, it) },
        onSearchPrev = { state.searchPrev(tabId, it) },
        onSearchClose = { state.closeSearch(tabId, it) },
        showSearchScopeChip = true,
        onSearchToggleScope = { state.setSearchMode(tabId, it) },
        onToggleSearchSync = {
            state.updateSettings { it.copy(syncSplitPanelSelectionAndScroll = !it.syncSplitPanelSelectionAndScroll) }
        },
        onSearchFocused = { state.focusSearch(tabId, it) },
        onSearchAllLines = { state.searchAllLines(tabId) },
        syncSelectionAndScroll = state.settings.syncSplitPanelSelectionAndScroll,
        syncSelectionAndScrollRevision = state.splitSyncRevision,
        onVisibleItems = { state.noteVisibleItems(tabId, it) },
    )
}
