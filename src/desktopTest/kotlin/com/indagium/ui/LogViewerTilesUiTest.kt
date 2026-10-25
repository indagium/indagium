package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import kotlin.test.assertTrue

private val LOG_VIEW_TEST_SPEC = TileChromeSpec<LogViewSectionId>(
    headerDp = LOG_VIEW_TILE_HEADER_DP,
    titleSp = 12f,
    gapRowDp = LOG_VIEW_GAP_ROW_DP,
    gapColumnDp = LOG_VIEW_GAP_COLUMN_DP,
    cornerDp = 8,
    sizeLabel = TileSizeLabelMode.ON_HOVER_OR_DRAG,
    sizeLabelMinDp = 0f,
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

    private fun rows(withPids: Boolean = false) = (1..40).map { id ->
        LogEntry(id, "10:00:00.$id", LogLevel.I, "App", "row $id", pid = if (withPids) PID else 0, tid = if (withPids) TID else 0)
    }

    private fun host(state: AppState, tabId: String, hostWidth: Int = 1000) {
        rule.setContent {
            val tab = state.tab(tabId) ?: return@setContent
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
}

@Composable
private fun LogViewerHarness(state: AppState, tabId: String, scope: TileScope<LogViewSectionId>) {
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
        tiles = LogViewerTiles(scope, LogViewSectionId.LOG, LogViewSectionId.ORIGINAL),
    )
}
