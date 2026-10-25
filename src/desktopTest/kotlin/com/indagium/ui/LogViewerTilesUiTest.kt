package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogViewSectionId
import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val LOG_VIEW_TEST_SPEC = TileChromeSpec<LogViewSectionId>(
    headerDp = LOG_VIEW_TILE_HEADER_DP,
    titleSp = 12f,
    gapRowDp = LOG_VIEW_GAP_ROW_DP,
    gapColumnDp = LOG_VIEW_GAP_COLUMN_DP,
    cornerDp = 8,
    minTileDp = LOG_VIEW_MIN_TILE_DP,
    minPairShare = LOG_VIEW_MIN_PAIR_SHARE,
    tagPrefix = "lv",
    keyOf = { it.key },
)

private fun leaf(id: LogViewSectionId, weight: Float = 1f): TileNode<LogViewSectionId> = TileLeaf(id, weight)

private val ORIGINAL_OVER_LOG: TileNode<LogViewSectionId> =
    TileSplit(TileAxis.COLUMN, listOf(leaf(LogViewSectionId.ORIGINAL), leaf(LogViewSectionId.LOG)))

private val ORIGINAL_LEFT_OF_LOG: TileNode<LogViewSectionId> =
    TileSplit(TileAxis.ROW, listOf(leaf(LogViewSectionId.ORIGINAL), leaf(LogViewSectionId.LOG, 3f)))

private val ORIGINAL_RIGHT_OF_LOG: TileNode<LogViewSectionId> =
    TileSplit(TileAxis.ROW, listOf(leaf(LogViewSectionId.LOG), leaf(LogViewSectionId.ORIGINAL)))

private const val HOST_H = 600
private const val PID = 4242
private const val TID = 7373

// Every text the row's semantics expose (the single BasicTextField's text), joined.
@OptIn(ExperimentalTestApi::class)
private fun ComposeContentTestRule.rowText(tag: String): String {
    fun SemanticsNode.collect(): List<String> =
        listOfNotNull(
            config.getOrNull(SemanticsProperties.EditableText)?.text,
            config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text },
        ) + children.flatMap { it.collect() }
    return onNodeWithTag(tag).fetchSemanticsNode().collect().joinToString(" ")
}

/** Renders the real LogViewer as the Original and Log tiles of a TilingSurface (no FileView yet). */
@OptIn(ExperimentalTestApi::class)
class LogViewerTilesUiTest {
    @get:Rule
    val rule = createComposeRule()

    private var layout by mutableStateOf(ORIGINAL_OVER_LOG)
    private var hostWidthDp by mutableStateOf(1000)

    private fun rows(withPids: Boolean = false) = (1..40).map { id ->
        LogEntry(id, "10:00:00.$id", LogLevel.I, "App", "row $id", pid = if (withPids) PID else 0, tid = if (withPids) TID else 0)
    }

    private fun host(state: AppState, tabId: String, hostWidth: Int = 1000) {
        hostWidthDp = hostWidth
        rule.setContent {
            val tab = state.tab(tabId) ?: return@setContent
            val hostWidth = hostWidthDp
            Box(Modifier.size(hostWidth.dp, HOST_H.dp)) {
                TilingSurface(
                    fullLayout = layout,
                    isVisible = { it != LogViewSectionId.ORIGINAL || tab.showUnfiltered },
                    collapsed = emptySet(),
                    parked = emptySet(),
                    maximized = null,
                    locked = false,
                    widthDp = hostWidth.toFloat(),
                    heightDp = HOST_H.toFloat(),
                    spec = LOG_VIEW_TEST_SPEC,
                    callbacks = TilingCallbacks(
                        onCommitLayout = { layout = it },
                        onHide = {},
                        onToggleCollapse = {},
                        onMaximize = {},
                        onReclaimFocus = {},
                        dropLabel = { "drop" },
                    ),
                ) {
                    LogViewerHarness(state, tabId, this)
                }
            }
        }
    }

    private fun stateWith(tabId: String, rows: List<LogEntry>, showUnfiltered: Boolean = true): AppState {
        val state = AppState()
        state.tabs = listOf(mkTab(tabId, "tiles.log", rows).copy(showUnfiltered = showUnfiltered))
        return state
    }

    @Test
    fun bothTilesAreEmittedAndOriginalSitsAboveTheLog() {
        val tabId = "tiles-order-ui"
        val state = stateWith(tabId, rows())
        host(state, tabId)

        val original = rule.onNodeWithTag("lv-card-original").getBoundsInRoot()
        val log = rule.onNodeWithTag("lv-card-log").getBoundsInRoot()
        assertTrue(original.top < log.top, "Original is above the log")
        assertTrue(original.bottom <= log.top, "the tiles do not overlap")
        // The Log tile is headerless (its toolbar is its header); Original has the standard header with its summary.
        rule.onNodeWithTag("lv-header-log").assertDoesNotExist()
        rule.onNodeWithTag("lv-header-original").assertExists()
        rule.onNodeWithTag("lv-grip-log").assertExists()
        rule.onNodeWithTag("lv-maximize-log").assertExists()
        rule.onNodeWithTag("log-row-$tabId:original-2").assertExists()
        rule.onNodeWithTag("log-row-$tabId:main-2").assertExists()
        state.close()
    }

    @Test
    fun withoutUnfilteredThereIsOnlyTheLogTile() {
        val tabId = "tiles-single-ui"
        val state = stateWith(tabId, rows(), showUnfiltered = false)
        host(state, tabId)

        rule.onNodeWithTag("lv-card-original").assertDoesNotExist()
        rule.onNodeWithTag("lv-card-log").assertExists()
        rule.onNodeWithTag("log-row-$tabId:main-2").assertExists()
        state.close()
    }

    @Test
    fun clickingAFilteredRowSelectsTheMatchingOriginalRow() {
        val tabId = "tiles-linked-ui"
        val state = stateWith(tabId, rows())
        host(state, tabId)

        rule.onNodeWithTag("log-row-$tabId:original-2").assertIsNotSelected()
        rule.onNodeWithTag("log-row-$tabId:main-2").performClick()
        rule.waitUntil(5_000) { state.tab(tabId)!!.selected == setOf(2) }
        rule.onNodeWithTag("log-row-$tabId:main-2").assertIsSelected()
        rule.onNodeWithTag("log-row-$tabId:original-2").assertIsSelected()
        state.close()
    }

    @Test
    fun movingOriginalBesideTheLogKeepsItsSelection() {
        val tabId = "tiles-move-ui"
        val state = stateWith(tabId, rows())
        host(state, tabId)

        rule.onNodeWithTag("log-row-$tabId:main-2").performClick()
        rule.waitUntil(5_000) { state.tab(tabId)!!.selected == setOf(2) }
        // Original's own selection, not the tab's: a different row, clicked in the Original pane (panel-local).
        rule.onNodeWithTag("log-row-$tabId:original-4").performClick()
        rule.onNodeWithTag("log-row-$tabId:original-4").assertIsSelected()
        assertEquals(setOf(2), state.tab(tabId)!!.selected, "Original's selection stays panel-local")

        layout = ORIGINAL_RIGHT_OF_LOG
        rule.waitForIdle()

        val original = rule.onNodeWithTag("lv-card-original").getBoundsInRoot()
        val log = rule.onNodeWithTag("lv-card-log").getBoundsInRoot()
        assertTrue(original.left >= log.right, "Original moved to the right of the log")
        rule.onNodeWithTag("log-row-$tabId:original-4").assertIsSelected()
        rule.onNodeWithTag("log-row-$tabId:original-2").assertIsNotSelected()
        state.close()
    }

    @Test
    fun closingOriginalTurnsUnfilteredOff() {
        val tabId = "tiles-close-ui"
        val state = stateWith(tabId, rows())
        host(state, tabId)

        rule.onNodeWithTag(ORIGINAL_CLOSE_TAG).performClick()
        rule.waitUntil(5_000) { !state.tab(tabId)!!.showUnfiltered }
        rule.waitForIdle()
        rule.onNodeWithTag("lv-card-original").assertDoesNotExist()
        rule.onNodeWithTag("lv-card-log").assertExists()
        state.close()
    }

    @Test
    fun theFollowSelectionPillTogglesTheSetting() {
        val tabId = "tiles-follow-ui"
        val state = stateWith(tabId, rows())
        host(state, tabId)

        assertTrue(state.settings.syncSplitPanelSelectionAndScroll)
        rule.onNodeWithTag(ORIGINAL_FOLLOW_TAG).performClick()
        rule.waitUntil(5_000) { !state.settings.syncSplitPanelSelectionAndScroll }
        rule.onNodeWithTag("log-row-$tabId:main-2").performClick()
        rule.waitUntil(5_000) { state.tab(tabId)!!.selected == setOf(2) }
        rule.waitForIdle()
        rule.onNodeWithTag("log-row-$tabId:original-2").assertIsNotSelected()
        state.close()
    }

    @Test
    fun aNarrowPaneHasNoPidHeaderAndAWideOneHasIt() {
        val tabId = "tiles-pid-ui"
        val state = stateWith(tabId, rows(withPids = true))
        // 1000 dp: Original (1/4, ~248 dp) beside the log (3/4, ~744 dp) -> only the log pane keeps PID.
        layout = ORIGINAL_LEFT_OF_LOG
        host(state, tabId)
        rule.onAllNodesWithTag("colheader-pid").assertCountEquals(1)

        // Stacked in a column both panes are the full width: both keep it.
        layout = ORIGINAL_OVER_LOG
        rule.waitForIdle()
        rule.onAllNodesWithTag("colheader-pid").assertCountEquals(2)
        state.close()
    }

    @Test
    fun aNarrowPaneRendersRowsWithoutPidTidTextAndAWideOneKeepsIt() {
        val tabId = "tiles-pid-rows-ui"
        val state = stateWith(tabId, rows(withPids = true))
        layout = ORIGINAL_LEFT_OF_LOG
        host(state, tabId)

        // Original (narrow) drops the pid/tid text from its rows; the wide log pane keeps it.
        val narrow = rule.rowText("log-row-$tabId:original-2")
        assertTrue("row 2" in narrow, "the row still renders its message: $narrow")
        assertTrue("$PID" !in narrow && "$TID" !in narrow, "no pid/tid in a narrow pane: $narrow")
        val wide = rule.rowText("log-row-$tabId:main-2")
        assertTrue("$PID" in wide && "$TID" in wide, "the wide pane keeps pid/tid: $wide")

        // Stacked, both panes are wide: Original's rows get the pid/tid text back.
        layout = ORIGINAL_OVER_LOG
        rule.waitForIdle()
        val restored = rule.rowText("log-row-$tabId:original-2")
        assertTrue("$PID" in restored && "$TID" in restored, "Original regains pid/tid when wide: $restored")
        state.close()
    }

    @Test
    fun aHostNarrowerThanTheThresholdHidesPidInEveryPane() {
        val tabId = "tiles-pid-narrow-ui"
        val state = stateWith(tabId, rows(withPids = true))
        host(state, tabId, hostWidth = 480)
        rule.onAllNodesWithTag("colheader-pid").assertCountEquals(0)
        assertTrue(rule.onNodeWithTag("lv-card-log").getBoundsInRoot().width < 520.dp)
        assertTrue("$PID" !in rule.rowText("log-row-$tabId:main-2"))
        assertTrue("$PID" !in rule.rowText("log-row-$tabId:original-2"))
        state.close()
    }

    // --- the toolbar in a narrow tile ---------------------------------------------------------------------------------

    private fun exists(tag: String): Boolean = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun toolbarState(tabId: String): AppState = stateWith(tabId, rows())

    @Test
    fun aWideTileKeepsEveryLabel() {
        val tabId = "tiles-toolbar-wide-ui"
        val state = toolbarState(tabId)
        host(state, tabId, hostWidth = 1000)
        rule.onNodeWithText("Expand all").assertExists()
        rule.onNodeWithText("Collapse all").assertExists()
        rule.onNodeWithText("Hide original").assertExists()
        rule.onNodeWithText("40 / 40 entries").assertExists()
        assertFalse(exists(LOG_TOOLBAR_OVERFLOW_TAG))
        state.close()
    }

    @Test
    fun aMidTileTurnsTheLabelsIntoIconButtonsAndKeepsTheCount() {
        val tabId = "tiles-toolbar-icons-ui"
        val state = toolbarState(tabId)
        host(state, tabId, hostWidth = 500)
        rule.onNodeWithText("Expand all").assertDoesNotExist()
        rule.onNodeWithText("Hide original").assertDoesNotExist()
        rule.onNodeWithTag(logToolbarTag("expand-all")).assertContentDescriptionEquals("Expand all")
        rule.onNodeWithTag(logToolbarTag("collapse-all")).assertContentDescriptionEquals("Collapse all")
        rule.onNodeWithTag(logToolbarTag("unfiltered")).assertContentDescriptionEquals("Hide original")
        rule.onNodeWithText("40 / 40 entries").assertExists()
        assertFalse(exists(LOG_TOOLBAR_OVERFLOW_TAG))
        // The Unfiltered icon is the same toggle as the labelled button.
        rule.onNodeWithTag(logToolbarTag("unfiltered")).performClick()
        rule.waitUntil(5_000) { !state.tab(tabId)!!.showUnfiltered }
        rule.onNodeWithTag(logToolbarTag("unfiltered")).assertContentDescriptionEquals("Show unfiltered original")
        state.close()
    }

    @Test
    fun aNarrowTileDropsTheCountToATooltipButKeepsTheIcons() {
        val tabId = "tiles-toolbar-nocount-ui"
        val state = toolbarState(tabId)
        host(state, tabId, hostWidth = 400)
        rule.onNodeWithText("40 / 40 entries").assertDoesNotExist()
        assertTrue(exists(LOG_TOOLBAR_COUNT_TAG), "the empty space carries the count tooltip")
        assertTrue(exists(logToolbarTag("expand-all")))
        assertTrue(exists(logToolbarTag("collapse-all")))
        assertTrue(exists(logToolbarTag("unfiltered")))
        assertFalse(exists(LOG_TOOLBAR_OVERFLOW_TAG))
        state.close()
    }

    @Test
    fun aVeryNarrowTileKeepsEveryActionInTheOverflowMenu() {
        val tabId = "tiles-toolbar-overflow-ui"
        val state = toolbarState(tabId)
        host(state, tabId, hostWidth = 300)
        assertTrue(exists(logToolbarTag("export")))
        assertTrue(exists(logToolbarTag("search")))
        assertTrue(exists(logToolbarTag("time-delta")))
        assertFalse(exists(logToolbarTag("expand-all")))
        assertFalse(exists(logToolbarTag("unfiltered")))
        // The grip and maximize button always stay, inside the card.
        rule.onNodeWithTag("lv-grip-log").assertExists()
        rule.onNodeWithTag("lv-maximize-log").assertExists()
        val card = rule.onNodeWithTag("lv-card-log").getBoundsInRoot()
        assertTrue(rule.onNodeWithTag("lv-maximize-log").getBoundsInRoot().right <= card.right + 0.5.dp)

        rule.onNodeWithTag(LOG_TOOLBAR_OVERFLOW_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(LOG_TOOLBAR_OVERFLOW_COUNT_TAG).assertExists()
        listOf(LogToolbarItem.EXPAND_ALL, LogToolbarItem.COLLAPSE_ALL, LogToolbarItem.UNFILTERED).forEach {
            assertTrue(exists(logToolbarOverflowItemTag(it.id)), "${it.id} is in the menu")
        }
        // A menu row runs the action and closes the menu.
        rule.onNodeWithTag(logToolbarOverflowItemTag("unfiltered")).performClick()
        rule.waitUntil(5_000) { !state.tab(tabId)!!.showUnfiltered }
        rule.waitForIdle()
        assertFalse(exists(logToolbarOverflowItemTag("unfiltered")), "the menu closed")
        state.close()
    }

    @Test
    fun theOverflowMenuHoldsTimeDeltaAtTheNarrowestWidth() {
        val tabId = "tiles-toolbar-narrowest-ui"
        val state = toolbarState(tabId)
        host(state, tabId, hostWidth = 230)
        assertFalse(exists(logToolbarTag("time-delta")))
        rule.onNodeWithTag(LOG_TOOLBAR_OVERFLOW_TAG).performClick()
        rule.waitForIdle()
        assertFalse(state.tab(tabId)!!.showTimeDelta)
        rule.onNodeWithTag(logToolbarOverflowItemTag("time-delta")).performClick()
        rule.waitUntil(5_000) { state.tab(tabId)!!.showTimeDelta }
        state.close()
    }

    @Test
    fun noToolbarButtonEverLeavesItsCardOrOverlapsAnother() {
        val tabId = "tiles-toolbar-sweep-ui"
        val state = toolbarState(tabId)
        host(state, tabId, hostWidth = 1000)
        val ids = LogToolbarItem.entries.map { logToolbarTag(it.id) } + LOG_TOOLBAR_OVERFLOW_TAG + "lv-grip-log" + "lv-maximize-log"
        // Includes the widths right at each variant's threshold (the estimates must not undershoot the real widths).
        for (width in listOf(214, 216, 260, 272, 274, 300, 352, 354, 400, 450, 472, 474, 520, 600, 644, 646, 648, 700, 1000)) {
            hostWidthDp = width
            rule.waitForIdle()
            val card = rule.onNodeWithTag("lv-card-log").getBoundsInRoot()
            val boxes = ids.filter { exists(it) }.map { it to rule.onNodeWithTag(it).getBoundsInRoot() }.sortedBy { it.second.left.value }
            boxes.forEach { (tag, box) ->
                assertTrue(box.left >= card.left - 0.5.dp && box.right <= card.right + 0.5.dp, "$tag is outside the ${width}dp card: $box vs $card")
            }
            // The count is what absorbs an underestimated button width, so it must keep close to the room reserved for it.
            val count = rule.onAllNodesWithText("40 / 40 entries")
            if (count.fetchSemanticsNodes().isNotEmpty()) {
                val countWidth = count[0].getBoundsInRoot().width
                println("toolbar sweep: ${width}dp tile leaves the count ${countWidth.value}dp")
                assertTrue(countWidth >= 100.dp, "the count is squeezed to $countWidth at ${width}dp")
            }
            boxes.zipWithNext().forEach { (a, b) ->
                assertTrue(a.second.right <= b.second.left + 0.5.dp, "${a.first} overlaps ${b.first} at ${width}dp: ${a.second} / ${b.second}")
            }
        }
        state.close()
    }

    @Test
    fun theKeyboardCursorStillReachesAnActionThatLivesInTheOverflowMenu() {
        val tabId = "tiles-toolbar-keys-ui"
        val state = toolbarState(tabId)
        host(state, tabId, hostWidth = 300)
        assertFalse(exists(logToolbarTag("unfiltered")))
        rule.onNodeWithTag("log-row-$tabId:main-1").performClick()
        rule.waitUntil(5_000) { state.tab(tabId)!!.selected == setOf(1) }
        // Up from the first row enters the toolbar at its first action; Left wraps to the last enabled one (Unfiltered, in the menu).
        rule.onNodeWithTag("log-row-$tabId:main-1").performKeyInput { pressKey(Key.DirectionUp) }
        rule.onNodeWithTag("log-row-$tabId:main-1").performKeyInput { pressKey(Key.DirectionLeft) }
        rule.onNodeWithTag("log-row-$tabId:main-1").performKeyInput { pressKey(Key.DirectionLeft) }
        rule.onNodeWithTag("log-row-$tabId:main-1").performKeyInput { pressKey(Key.Enter) }
        rule.waitUntil(5_000) { !state.tab(tabId)!!.showUnfiltered }
        state.close()
    }

    @Test
    fun theUntiledToolbarKeepsItsLabelsInANarrowPane() {
        val tabId = "tiles-toolbar-legacy-ui"
        val state = toolbarState(tabId)
        rule.setContent {
            Box(Modifier.size(300.dp, HOST_H.dp)) { LogViewerHarness(state, tabId, null) }
        }
        rule.onNodeWithText("Expand all").assertExists()
        rule.onNodeWithText("Collapse all").assertExists()
        rule.onNodeWithText("Hide original").assertExists()
        rule.onNodeWithText("Export ▾").assertExists()
        assertFalse(exists(LOG_TOOLBAR_OVERFLOW_TAG))
        assertFalse(exists(LOG_TOOLBAR_COUNT_TAG))
        state.close()
    }
}

@Composable
private fun LogViewerHarness(state: AppState, tabId: String, scope: TileScope<LogViewSectionId>?) {
    val tab = state.tab(tabId) ?: return
    LogViewer(
        tab = tab,
        settings = state.settings,
        onSelRow = { id, multi, range -> state.selRow(tabId, id, multi, range) },
        onSelRowRange = { state.setSelectedRows(tabId, it) },
        onCtxMenu = { _, _, _, _, _, _ -> },
        onToggleGroup = { state.toggleGroup(tabId, it) },
        onClearFilter = { state.requestClearFilter(tabId) },
        onExpandAll = { state.expandAll(tabId) },
        onCollapseAll = { state.collapseAll(tabId) },
        onToggleUnfiltered = { state.toggleUnfiltered(tabId) },
        onToggleTimeDelta = { state.toggleTimeDelta(tabId) },
        onOpenSearch = { state.openSearch(tabId) },
        onExportTxt = {},
        onExportCsv = {},
        scrollStateStore = state.logViewerScrollStateStore,
        showSearchScopeChip = true,
        onToggleSearchSync = {
            state.updateSettings { it.copy(syncSplitPanelSelectionAndScroll = !it.syncSplitPanelSelectionAndScroll) }
        },
        syncSelectionAndScroll = state.settings.syncSplitPanelSelectionAndScroll,
        syncSelectionAndScrollRevision = state.splitSyncRevision,
        onVisibleItems = { state.noteVisibleItems(tabId, it) },
        tiles = scope?.let { LogViewerTiles(it, LogViewSectionId.LOG, LogViewSectionId.ORIGINAL) },
    )
}
