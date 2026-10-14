package com.indagium

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.indagium.model.LogSearchState
import com.indagium.model.SearchMode
import com.indagium.model.SearchScopeBehavior
import com.indagium.model.SearchScope
import com.indagium.ui.SearchBar
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val POPUP_TEST_WINDOW_HEIGHT_DP = 700
private const val MIN_READABLE_POPUP_WIDTH_DP = 150f

/** The Find field stays editable and focused while results recompute, and its mode controls behave. */
@OptIn(ExperimentalTestApi::class)
class SearchBarUiTest {
    @get:Rule
    val rule = createComposeRule()

    private class TestWindowInfo(initialSize: IntSize) : WindowInfo {
        override val isWindowFocused: Boolean = true
        override var containerSize by mutableStateOf(initialSize)
    }

    private fun savePopupImage(name: String) {
        saveTaggedImage(name, "search-mode-popup")
    }

    private fun saveTaggedImage(name: String, tag: String) {
        val image = rule.onNodeWithTag(tag).captureToImage()
        val pixels = image.toPixelMap()
        val rendered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            rendered.setRGB(x, y, pixels[x, y].toArgb())
        }
        val directory = File("build/search-dropdown-qa").apply { mkdirs() }
        assertTrue(ImageIO.write(rendered, "png", File(directory, "$name.png")))
    }

    @Test
    fun modeTriggerTooltipUsesShortRowsAndStaysNarrow() {
        var density = 1f
        lateinit var changePolicy: (SearchScopeBehavior) -> Unit
        rule.setContent {
            density = LocalDensity.current.density
            var behavior by remember { mutableStateOf(SearchScopeBehavior.FOLLOW_VIEW) }
            changePolicy = { behavior = it }
            Box(Modifier.width(900.dp).height(160.dp)) {
                SearchBar(
                    tabId = "tooltip-tab",
                    search = LogSearchState(active = true, query = "", scope = SearchScope.FILTERED),
                    mode = SearchMode.TWO_SEARCHES,
                    showModeSelector = true,
                    searchScopeBehavior = behavior,
                    lastManualSearchMode = SearchMode.TWO_SEARCHES,
                    onQueryChange = { _, _ -> },
                    onToggleCase = {}, onNext = {}, onPrev = {}, onClose = {},
                )
            }
        }

        rule.onNodeWithTag("search-mode-dropdown").performMouseInput { moveTo(center) }
        rule.waitUntil(3_000) {
            rule.onAllNodesWithTag("search-mode-tooltip").fetchSemanticsNodes().isNotEmpty()
        }
        val tooltip = rule.onNodeWithTag("search-mode-tooltip")
        val widthDp = tooltip.fetchSemanticsNode().boundsInRoot.width / density
        assertTrue(widthDp <= 270f, "Tooltip width was ${widthDp}dp")
        rule.onNodeWithText("Two fields; separate queries and case sensitivity.", substring = true).assertExists()
        rule.onNodeWithText("Enter / Shift+Enter: next / previous", substring = true).assertExists()
        rule.onNodeWithText("Esc: close this field", substring = true).assertExists()
        rule.onNodeWithText("Manual choice resets on Find/view change.", substring = true).assertExists()
        saveTaggedImage("search-mode-tooltip", "search-mode-tooltip")

        rule.runOnIdle { changePolicy(SearchScopeBehavior.LAST_USED) }
        rule.waitForIdle()
        rule.onNodeWithText("last mode (Two searches)", substring = true).assertExists()
        saveTaggedImage("search-mode-tooltip-last-used", "search-mode-tooltip")
    }

    @Test
    fun typingContinuesInTheFieldWhileAnEditIsRecomputingAndTheCounterKeepsThePreviousCount() {
        rule.setContent {
            var query by remember { mutableStateOf("hidden") }
            var searching by remember { mutableStateOf(false) }
            val search = LogSearchState(
                active = true,
                query = query,
                isSearching = searching,
                matchIds = intArrayOf(1, 2, 3),
                currentIdx = 1,
                focusNonce = 1,
                scope = SearchScope.FILTERED,
            )
            SearchBar(
                tabId = "test-tab",
                search = search,
                autoFocus = true,
                onQueryChange = { _, value ->
                    query = value
                    searching = true
                },
                onToggleCase = {},
                onNext = {},
                onPrev = {},
                onClose = {},
            )
        }

        val field = rule.onNodeWithTag("search-field-filtered")
        rule.waitForIdle()
        field.assertIsFocused()
        rule.onNodeWithTag("search-counter-filtered").assertTextEquals("2/3")

        // Opening Find selects the existing query. Put the caret at its end before simulating
        // further typing; a recomputing result must not select the whole field again.
        field.performTextInputSelection(TextRange("hidden".length))
        field.performTextInput(" by filter")
        field.assertTextEquals("hidden by filter")
        field.assertIsFocused()
        // Still the previous count (dimmed), not a wider "Searching…" label.
        rule.onNodeWithTag("search-counter-filtered").assertTextEquals("2/3")
    }

    @Test
    fun searchAllLinesAppearsOnlyForAFinishedValidFilteredSearchWithNoMatches() {
        var clicks = 0
        var search by mutableStateOf(
            LogSearchState(active = true, query = "hidden", isSearching = true, scope = SearchScope.FILTERED),
        )
        rule.setContent {
            SearchBar(
                tabId = "test-tab",
                search = search,
                onSearchAllLines = { clicks++ },
                onQueryChange = { _, _ -> },
                onToggleCase = {},
                onNext = {},
                onPrev = {},
                onClose = {},
            )
        }

        rule.onAllNodesWithTag("search-all-lines").assertCountEquals(0)
        search = search.copy(isSearching = false, invalidPattern = true)
        rule.waitForIdle()
        rule.onAllNodesWithTag("search-all-lines").assertCountEquals(0)

        search = search.copy(invalidPattern = false)
        rule.waitForIdle()
        rule.onNodeWithTag("search-all-lines").performClick()
        assertEquals(1, clicks)

        search = search.copy(matchIds = intArrayOf(2))
        rule.waitForIdle()
        rule.onAllNodesWithTag("search-all-lines").assertCountEquals(0)
    }

    @Test
    fun twoSearchesDropdownKeepsFieldQueriesSeparateAndExposesSyncSwitch() {
        rule.setContent {
            var mode by remember { mutableStateOf(SearchMode.FILTERED) }
            var filteredQuery by remember { mutableStateOf("") }
            var unfilteredQuery by remember { mutableStateOf("") }
            var sync by remember { mutableStateOf(true) }
            Column {
                if (mode != SearchMode.UNFILTERED) {
                    SearchBar(
                        tabId = "dual-tab",
                        search = LogSearchState(
                            active = true,
                            query = filteredQuery,
                            focusNonce = 1,
                            scope = SearchScope.FILTERED,
                        ),
                        fieldLabel = if (mode == SearchMode.TWO_SEARCHES) "Find in Filtered" else null,
                        mode = mode,
                        showModeSelector = true,
                        showSyncToggle = mode == SearchMode.TWO_SEARCHES,
                        syncEnabled = sync,
                        onSyncToggle = { sync = !sync },
                        onModeChange = { mode = it },
                        onQueryChange = { _, value -> filteredQuery = value },
                        onToggleCase = {}, onNext = {}, onPrev = {}, onClose = {},
                    )
                }
                if (mode != SearchMode.FILTERED) {
                    SearchBar(
                        tabId = "dual-tab",
                        search = LogSearchState(
                            active = true,
                            query = unfilteredQuery,
                            focusNonce = 1,
                            scope = SearchScope.UNFILTERED,
                        ),
                        fieldLabel = if (mode == SearchMode.TWO_SEARCHES) "Find in Unfiltered" else null,
                        mode = mode,
                        showModeSelector = mode == SearchMode.UNFILTERED,
                        showSyncToggle = mode == SearchMode.TWO_SEARCHES,
                        syncEnabled = sync,
                        onSyncToggle = { sync = !sync },
                        onModeChange = { mode = it },
                        onQueryChange = { _, value -> unfilteredQuery = value },
                        onToggleCase = {}, onNext = {}, onPrev = {}, onClose = {},
                    )
                }
            }
        }

        rule.onNodeWithTag("search-mode-dropdown").performClick()
        rule.onNodeWithText("Two searches").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Find in Filtered").assertExists()
        rule.onNodeWithText("Find in Unfiltered").assertExists()

        val filtered = rule.onNodeWithTag("search-field-filtered")
        filtered.performClick()
        filtered.performTextInput("timeout")
        val unfiltered = rule.onNodeWithTag("search-field-unfiltered")
        unfiltered.performClick()
        unfiltered.performTextInput("socket closed")
        filtered.assertTextEquals("timeout")
        unfiltered.assertTextEquals("socket closed")

        rule.onNodeWithTag("search-mode-dropdown").performClick()
        rule.onNodeWithText("Link selection & Find navigation").assertExists()
        rule.onNodeWithText(
            "In any split view, selecting a Filtered row or finding a row can move both panels. " +
                "Original row clicks stay local; queries stay separate.",
        ).assertExists()
        rule.onNodeWithTag("search-link-toggle").performClick()
        rule.onNodeWithText("Independent").assertExists()
        rule.onNodeWithText(
            "Each panel keeps its own selection and scroll position in every Find mode. " +
                "Selecting or finding a row does not move the other panel. Queries stay separate.",
        ).assertExists()
        savePopupImage("independent")
        filtered.assertTextEquals("timeout")
        unfiltered.assertTextEquals("socket closed")
    }

    @Test
    fun selectingTheCurrentModeRestoresCaretWithoutReopeningOrSelectingAll() {
        rule.setContent {
            SearchBar(
                tabId = "steady-mode-tab",
                search = LogSearchState(active = true, query = "timeout", focusNonce = 1, scope = SearchScope.FILTERED),
                mode = SearchMode.FILTERED,
                showModeSelector = true,
                autoFocus = true,
                onQueryChange = { _, _ -> },
                onToggleCase = {}, onNext = {}, onPrev = {}, onClose = {},
            )
        }

        val field = rule.onNodeWithTag("search-field-filtered")
        rule.waitForIdle()
        field.performTextInputSelection(TextRange("timeout".length))
        rule.onNodeWithTag("search-mode-dropdown").performClick()
        rule.onNodeWithTag("search-mode-option-filtered").performClick()
        rule.waitForIdle()

        field.assertIsFocused()
        field.performTextInput("!")
        field.assertTextEquals("timeout!")

        val dropdown = rule.onNodeWithTag("search-mode-dropdown")
        dropdown.performClick()
        dropdown.performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("search-mode-popup").assertCountEquals(0)
        field.assertIsFocused()
        field.performTextInput("?")
        field.assertTextEquals("timeout!?")
    }

    @Test
    fun popupIsCappedAndAnchoredInAWideWindow() = assertPopupBoundsAndCapture(
        name = "wide",
        windowWidthDp = 1800,
        rootWidthDp = 1400,
        maxExpectedWidthDp = 286f,
    )

    @Test
    fun popupFitsANarrowWindowAndKeepsItsTriggerAnchor() = assertPopupBoundsAndCapture(
        name = "narrow",
        windowWidthDp = 250,
        rootWidthDp = 234,
        maxExpectedWidthDp = 234f,
    )

    private fun assertPopupBoundsAndCapture(
        name: String,
        windowWidthDp: Int,
        rootWidthDp: Int,
        maxExpectedWidthDp: Float,
    ) {
        lateinit var window: TestWindowInfo
        var density = 1f
        rule.setContent {
            density = LocalDensity.current.density
            window = remember {
                TestWindowInfo(IntSize((windowWidthDp * density).toInt(), (POPUP_TEST_WINDOW_HEIGHT_DP * density).toInt()))
            }
            CompositionLocalProvider(LocalWindowInfo provides window) {
                Box(Modifier.width(rootWidthDp.dp).height(620.dp)) {
                    SearchBar(
                        tabId = "popup-$name",
                        search = LogSearchState(active = true, query = "", scope = SearchScope.FILTERED),
                        mode = SearchMode.TWO_SEARCHES,
                        showModeSelector = true,
                        showSyncToggle = true,
                        modifier = Modifier.fillMaxWidth(),
                        onQueryChange = { _, _ -> },
                        onToggleCase = {}, onNext = {}, onPrev = {}, onClose = {},
                    )
                }
            }
        }

        rule.onNodeWithTag("search-mode-dropdown").performClick()
        rule.waitForIdle()
        val popup = rule.onNodeWithTag("search-mode-popup").fetchSemanticsNode().boundsInRoot
        val trigger = rule.onNodeWithTag("search-mode-dropdown").fetchSemanticsNode().boundsInRoot
        val widthDp = popup.width / density
        assertTrue(widthDp <= maxExpectedWidthDp + 1f, "$name popup width is $widthDp dp, expected <= $maxExpectedWidthDp dp")
        assertTrue(widthDp > MIN_READABLE_POPUP_WIDTH_DP, "$name popup should remain wide enough for readable mode labels")
        if (name == "narrow") {
            // When the menu is wider than the space between the window edge and its trigger,
            // Compose clamps it to the left edge while keeping the full menu inside the window.
            assertTrue(popup.left >= -1f, "$name popup should stay inside the left window edge: $popup")
            assertTrue(popup.right <= windowWidthDp * density + 2f * density,
                "$name popup should stay inside the narrow window: popup=$popup windowWidth=$windowWidthDp dp")
            assertTrue(popup.right >= trigger.left && popup.left <= trigger.right,
                "$name popup should remain horizontally adjacent to its trigger: popup=$popup trigger=$trigger")
        } else {
            assertTrue(abs(popup.right - trigger.right) <= 3f * density,
                "$name popup should align its right edge with the trigger: popup=$popup trigger=$trigger")
        }
        assertTrue(popup.top >= trigger.bottom - 2f * density,
            "$name popup should appear below its trigger: popup=$popup trigger=$trigger")
        savePopupImage(name)
    }
}
