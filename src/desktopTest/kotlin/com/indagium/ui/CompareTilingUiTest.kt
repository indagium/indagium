package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.indagium.debug.AppLogger
import com.indagium.model.CompareFilterTarget
import com.indagium.model.Filter
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogViewPresetId
import com.indagium.model.LogViewSectionId
import com.indagium.model.TileLeaf
import com.indagium.model.TileSplit
import com.indagium.model.VoiceInputSettings
import com.indagium.model.VoiceRecognitionEngine
import com.indagium.voice.VoiceAudio
import com.indagium.voice.VoiceCapture
import com.indagium.voice.VoiceCaptureResult
import com.indagium.voice.VoiceCaptureSession
import com.indagium.voice.VoiceCaptureStartResult
import com.indagium.voice.VoiceInputController
import com.indagium.voice.VoiceInputRuntime
import com.indagium.voice.VoiceInputSessionCoordinator
import com.indagium.voice.VoiceModelInstaller
import com.indagium.voice.VoiceTranscript
import com.indagium.voice.VoiceTranscriptionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val A_ID = "cmp-ui-a"
private const val B_ID = "cmp-ui-b"
private const val C_ID = "cmp-ui-c"
private const val SECONDS_PER_MINUTE = 60

/** The real CompareView on tiles: the 2 x 2 arrangement, the A | B | Both switch, the A / B badges, the scroll link and the Layout menu. */
@OptIn(ExperimentalTestApi::class)
class CompareTilingUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var directory: File
    private lateinit var state: AppState
    private var scope: CoroutineScope? = null

    // The AI tile's dictation button needs the app-level voice runtime; a model-less one is enough for layout tests.
    private val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val voiceUi: VoiceInputUi by lazy {
        val controller = VoiceInputController(
            hasInstalledModel = { false },
            capture = object : VoiceCapture {
                override fun start(): VoiceCaptureStartResult = VoiceCaptureStartResult.Started(object : VoiceCaptureSession {
                    override fun stop() = VoiceCaptureResult.Captured(VoiceAudio(byteArrayOf(0, 64)))

                    override fun cancel() = Unit
                })
            },
            transcriber = { _, _ -> VoiceTranscriptionResult.Success(VoiceTranscript("")) },
            scope = voiceScope,
        )
        VoiceInputUi(
            VoiceInputRuntime(controller, VoiceModelInstaller(createTempDirectory("compare-tiling-voice").toFile()), VoiceRecognitionEngine.WHISPER),
            VoiceInputSessionCoordinator(controller, voiceScope),
            VoiceInputSettings(),
        ) {}
    }

    @After
    fun tearDown() {
        voiceScope.cancel()
        if (::state.isInitialized) state.close()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    // One row per second, so both tabs' rows share timestamps and a linked scroll lands on the same index.
    private fun rows(tag: String, count: Int = 40) = (1..count).map { id ->
        LogEntry(id, "10:%02d:%02d.000".format(id / SECONDS_PER_MINUTE, id % SECONDS_PER_MINUTE), LogLevel.I, tag, "row $id", pid = 100, tid = 1)
    }

    private fun setup(
        width: Int = 1300,
        height: Int = 800,
        leftUnfiltered: Boolean = true,
        rightUnfiltered: Boolean = true,
        rowCount: Int = 40,
        configure: (AppState) -> Unit = {},
    ) {
        directory = createTempDirectory("compare-tiling-ui").toFile()
        state = AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archives"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        )
        state.tabs = listOf(
            mkTab(A_ID, "alpha.log", rows("AlphaTag", rowCount)).copy(showUnfiltered = leftUnfiltered),
            mkTab(B_ID, "beta.log", rows("BetaTag", rowCount)).copy(showUnfiltered = rightUnfiltered),
        )
        state.activeTabId = A_ID
        state.compareTabId = B_ID
        state.updateCompareMode(true)
        configure(state)
        rule.setContent {
            scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME, LocalVoiceInputUi provides voiceUi) {
                Column(Modifier.size(width.dp, height.dp)) {
                    Box(Modifier.fillMaxWidth().height(36.dp)) { LayoutMenuButton(state, onReclaimFocus = {}) }
                    Box(Modifier.weight(1f).fillMaxWidth()) { CompareView(state) }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String): DpRect = rule.onNodeWithTag(tag).getBoundsInRoot()

    private fun exists(tag: String): Boolean = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    // Whether a fresh AppState restored from this test's autosave file sees the link switched on.
    private fun restoredSyncScroll(): Boolean {
        val restored = AppState(File(directory, "state.cache"), restoreOnCreate = true)
        return try {
            restored.settings.compareSyncScroll
        } finally {
            restored.close()
        }
    }

    private fun segment(label: String): SemanticsMatcher = hasText(label) and hasAnyAncestor(hasTestTag(COMPARE_TARGET_TAG))

    @Test
    fun bothTabsShowingOriginalMakeAGridWithOriginalsOnTopAndAOnTheLeft() {
        setup()
        listOf("original_a", "log_a", "original_b", "log_b").forEach { assertTrue(exists("tile-card-$it"), "tile $it is present") }
        val originalA = bounds("tile-card-original_a")
        val logA = bounds("tile-card-log_a")
        val originalB = bounds("tile-card-original_b")
        val logB = bounds("tile-card-log_b")
        assertTrue(originalA.bottom <= logA.top, "A: Original above Log")
        assertTrue(originalB.bottom <= logB.top, "B: Original above Log")
        assertTrue(logA.right <= logB.left, "A is left of B")
        assertTrue(originalA.right <= originalB.left)
        // The filter tiles stay on the left, Notes on the right, as in the single view.
        assertTrue(bounds("tile-card-tags").right <= originalA.left)
        assertTrue(logB.right <= bounds("tile-card-notes").left)
    }

    @Test
    fun theFilterColumnOfTheCompareViewScrollsWhenItsTilesAreTallerThanTheWindow() {
        val grid = compareGridLayout(LogViewLegacySizes()) as TileSplit<LogViewSectionId>
        val filters = grid.children.first() as TileSplit<LogViewSectionId>
        val tall = filters.copy(children = filters.children.map { (it as TileLeaf<LogViewSectionId>).copy(sizeDp = 240f) })
        setup(height = 560) { it.updateSettings { s -> s.withCompareLayout(grid.copy(children = listOf(tall) + grid.children.drop(1))) } }
        assertTrue(exists("tile-scrollbar-tags"), "the compare view's filter column overflows and scrolls too")
        val logBefore = bounds("tile-card-log_a")
        val tagsTop = rule.onNodeWithTag("tile-card-tags").getUnclippedBoundsInRoot().top
        rule.onNodeWithTag("tile-card-tags").performMouseInput {
            moveTo(center)
            scroll(300f)
        }
        rule.waitForIdle()
        assertTrue(rule.onNodeWithTag("tile-card-tags").getUnclippedBoundsInRoot().top < tagsTop, "the wheel scrolled the filter column")
        assertEquals(logBefore, bounds("tile-card-log_a"), "the logs did not move")
        assertFalse(exists("tile-scrollbar-log_a"))
    }

    @Test
    fun theCompareFilterColumnIsSizedByItsContentWithAScrollbarGutterAndAHandleUnderItsLastTile() {
        setup(height = 560)
        // Tags with nothing active is its header and input, not a share of the window.
        assertTrue(bounds("tile-card-tags").height.value < 90f, "Tags is ${bounds("tile-card-tags").height}")
        assertTrue(exists("tile-scrollbar-tags"), "the eight tiles together are taller than 560 dp")
        val bar = rule.onNodeWithTag("tile-scrollbar-tags").getUnclippedBoundsInRoot()
        LOG_VIEW_FILTER_TILES.forEach { tile ->
            val card = rule.onNodeWithTag("tile-card-${tile.key}").getUnclippedBoundsInRoot()
            assertTrue(card.right.value <= bar.left.value, "$tile ends at ${card.right}, the scrollbar starts at ${bar.left}")
        }
        // Notes is the only tile of its column and has a handle right under it, inside the window.
        val notes = rule.onNodeWithTag("tile-card-notes").getBoundsInRoot()
        val handles = rule.onAllNodesWithContentDescription("Resize sections")
        val under = (0 until handles.fetchSemanticsNodes().size).map { handles[it].getBoundsInRoot() }
            .firstOrNull { kotlin.math.abs(it.top.value - notes.bottom.value) < 1f }
        assertTrue(under != null, "a handle under the last tile of the right column")
    }

    @Test
    fun aFlexTileOfTheCompareFilterColumnResizesTallerThanItsEmptyListAndWritesTheCompareLayout() {
        setup(height = 900)
        val tags = bounds("tile-card-tags")
        val handles = rule.onAllNodesWithContentDescription("Resize sections")
        val handle = (0 until handles.fetchSemanticsNodes().size).map { handles[it] }
            .firstOrNull { kotlin.math.abs(it.getBoundsInRoot().top.value - tags.bottom.value) < 1f }
        assertNotNull(handle, "Tags has nothing in its list yet and still has a handle")
        val density = rule.density.density
        handle.performTouchInput {
            down(center)
            moveBy(Offset(0f, 60f * density))
            moveBy(Offset(0f, 60f * density))
            up()
        }
        rule.waitForIdle()
        val grown = bounds("tile-card-tags").height.value - tags.height.value
        assertTrue(grown > 90f, "Tags grew by $grown past its (empty) list")
        val written = (state.settings.compareLayout as TileSplit<LogViewSectionId>).children.first() as TileSplit<LogViewSectionId>
        assertEquals(tags.height.value + grown, (written.children.first { (it as TileLeaf).section == LogViewSectionId.TAGS } as TileLeaf).sizeDp!!, 1f)
    }

    @Test
    fun anOriginalTileShowsOnlyForTheTabThatShowsUnfiltered() {
        setup(rightUnfiltered = false)
        assertTrue(exists("tile-card-original_a"))
        assertFalse(exists("tile-card-original_b"))
        assertTrue(exists("tile-card-log_a"))
        assertTrue(exists("tile-card-log_b"))
    }

    @Test
    fun theBadgesNameTheSideWhoseFilterIsEdited() {
        setup()
        // The default target is Both: both sides carry the accent badge.
        rule.onNodeWithTag(compareBadgeTag("A")).assertContentDescriptionEquals("A, filters apply here")
        rule.onNodeWithTag(compareBadgeTag("B")).assertContentDescriptionEquals("B, filters apply here")
        rule.runOnIdle { state.updateCompareFilterTarget(CompareFilterTarget.B) }
        rule.waitForIdle()
        rule.onNodeWithTag(compareBadgeTag("A")).assertContentDescriptionEquals("A, filters do not apply here")
        rule.onNodeWithTag(compareBadgeTag("B")).assertContentDescriptionEquals("B, filters apply here")
        rule.runOnIdle { state.updateCompareFilterTarget(CompareFilterTarget.A) }
        rule.waitForIdle()
        rule.onNodeWithTag(compareBadgeTag("A")).assertContentDescriptionEquals("A, filters apply here")
        rule.onNodeWithTag(compareBadgeTag("B")).assertContentDescriptionEquals("B, filters do not apply here")
    }

    private fun pill(label: String): SemanticsMatcher = hasText(label) and hasAnyAncestor(hasTestTag(COMPARE_PICKER_TAG))

    @Test
    fun theStripShowsTheFullTabPickerAndSelectingAPillChangesTheComparedTab() {
        setup {
            it.tabs = it.tabs + mkTab(C_ID, "gamma.log", rows("GammaTag"))
        }
        // Every tab is a pill, as before the compare view was tiled (not just the compared one), in the strip above the tiles.
        val strip = bounds(COMPARE_PICKER_TAG)
        assertTrue(strip.bottom <= bounds("tile-card-original_a").top, "the picker is above the tiles")
        rule.onNode(pill("alpha.log")).assertExists()
        rule.onNode(pill("beta.log")).assertExists()
        rule.onNode(pill("gamma.log")).assertExists()
        assertEquals(B_ID, state.compareTabId)
        rule.onNode(pill("gamma.log")).performClick()
        rule.waitForIdle()
        assertEquals(C_ID, state.compareTabId)
        // B's own header names the new tab, next to the B badge, without a picker of its own.
        rule.onNodeWithTag(compareNameTag("B")).assertTextEquals("gamma.log")
        assertEquals(1, rule.onAllNodes(hasText("gamma.log") and hasAnyAncestor(hasTestTag("tile-card-log_b"))).fetchSemanticsNodes().size)
        rule.onAllNodesWithTag(COMPARE_PICKER_TAG).assertCountEquals(1)
        assertTrue(rule.onAllNodes(hasTestTag(COMPARE_PICKER_TAG) and hasAnyAncestor(hasTestTag("tile-card-log_b"))).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun selectingTheLeftTabInThePickerSwapsTheSides() {
        setup()
        rule.onNode(pill("alpha.log")).performClick()
        rule.waitForIdle()
        assertEquals(A_ID, state.compareTabId)
        assertEquals(B_ID, state.activeTabId, "A moved to the tab B showed")
    }

    @Test
    fun aNarrowCompareTileKeepsItsToolbarActionsReachableAndNamesTheFile() {
        setup(width = 900)
        // Each log tile is well under 450 dp wide: Expand all and friends are icons or in the overflow menu, never clipped away.
        listOf("log_a", "log_b").forEach { id ->
            val card = bounds("tile-card-$id")
            assertTrue(card.right - card.left < 450.dp, "the $id tile is narrow: $card")
        }
        rule.onAllNodesWithTag(logToolbarTag("export")).assertCountEquals(2)
        rule.onAllNodesWithTag(logToolbarTag("search")).assertCountEquals(2)
        rule.onNodeWithTag(compareNameTag("A")).assertTextEquals("alpha.log")
        rule.onNodeWithTag(compareNameTag("B")).assertTextEquals("beta.log")
        val reachable = exists(LOG_TOOLBAR_OVERFLOW_TAG) || exists(logToolbarTag("expand-all"))
        assertTrue(reachable, "Expand all is an icon or in the overflow menu")
    }

    @Test
    fun theSwitchChangesTheTarget() {
        setup()
        rule.onNode(segment("B")).performClick()
        rule.waitForIdle()
        assertEquals(CompareFilterTarget.B, state.compareFilterTarget)
        rule.onNode(segment("A")).performClick()
        rule.waitForIdle()
        assertEquals(CompareFilterTarget.A, state.compareFilterTarget)
        rule.onNode(segment("Both")).performClick()
        rule.waitForIdle()
        assertEquals(CompareFilterTarget.BOTH, state.compareFilterTarget)
    }

    @Test
    fun targetBBindsTheFilterTilesToTheRightTab() {
        setup()
        // The Tags tile suggests the tags of the tab it edits: A's while A is edited (Both mirrors A's filter onto B), B's once B is.
        rule.onNodeWithTag("filter-panel-tags-input").performTextReplacement("Tag")
        rule.waitUntil(5_000) { rule.onAllNodesWithText("AlphaTag").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodesWithText("BetaTag").fetchSemanticsNodes().isEmpty())

        rule.onNode(segment("B")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("filter-panel-tags-input").performTextReplacement("Tag")
        rule.waitUntil(5_000) { rule.onAllNodesWithText("BetaTag").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodesWithText("AlphaTag").fetchSemanticsNodes().isEmpty())

        // Picking the suggestion (Down, Enter in the field) edits the filter of the tab the panel is bound to.
        rule.onNodeWithTag("filter-panel-tags-input").performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        rule.waitForIdle()
        assertEquals(setOf("BetaTag"), state.tab(B_ID)!!.filter.activeTags, "the pick edited B's filter")
        assertEquals(Filter(), state.tab(A_ID)!!.filter, "A's own filter is untouched")
    }

    @Test
    fun theScrollLinkTogglePersistsInTheSettings() {
        setup()
        assertFalse(state.settings.compareSyncScroll)
        rule.onNodeWithTag(COMPARE_SYNC_TAG).performClick()
        rule.waitForIdle()
        assertTrue(state.settings.compareSyncScroll)
        rule.waitUntil(10_000) { restoredSyncScroll() }
        rule.onNodeWithTag(COMPARE_SYNC_TAG).performClick()
        rule.waitForIdle()
        assertFalse(state.settings.compareSyncScroll)
    }

    @Test
    fun theLayoutMenuOffersTheComparePresetsInCompare() {
        setup()
        rule.onNodeWithTag("layout-menu-button").performClick()
        rule.waitForIdle()
        assertTrue(exists("layout-menu-preset-${LogViewPresetId.COMPARE_GRID.name}"))
        assertTrue(exists("layout-menu-preset-${LogViewPresetId.COMPARE_COLUMNS.name}"))
        assertFalse(exists("layout-menu-preset-${LogViewPresetId.CLASSIC.name}"), "no single-view presets in compare")
        // Panels: the shared tiles only (no log or Original tiles).
        assertTrue(exists("layout-menu-panel-notes"))
        assertFalse(exists("layout-menu-panel-log_a"))
        assertFalse(exists("layout-menu-panel-original_a"))

        rule.onNodeWithTag("layout-menu-preset-${LogViewPresetId.COMPARE_COLUMNS.name}").performClick()
        rule.waitForIdle()
        assertEquals(LogViewPresetId.COMPARE_COLUMNS, state.settings.lastComparePreset)
        assertNotNull(state.settings.compareLayout)
        // Compare rows: both Originals share the top row, both Logs the bottom row.
        assertEquals(bounds("tile-card-original_a").top, bounds("tile-card-original_b").top)
        assertEquals(bounds("tile-card-log_a").top, bounds("tile-card-log_b").top)
        assertTrue(bounds("tile-card-original_a").bottom <= bounds("tile-card-log_a").top)
        assertEquals(null, state.settings.logViewLayout, "the single view's layout is left alone")
    }

    @Test
    fun hidingNotesInCompareUsesTheCompareHiddenList() {
        setup()
        rule.onNodeWithTag("tile-hide-notes").performClick()
        rule.waitForIdle()
        assertTrue(LogViewSectionId.NOTES in state.settings.compareHidden)
        assertFalse(LogViewSectionId.NOTES in state.settings.logViewHidden)
        assertFalse(exists("tile-card-notes"))
    }

    @Test
    fun revealingAFilterTileInCompareUnhidesItInTheCompareList() {
        setup()
        rule.runOnIdle { state.hideLogViewTile(LogViewSectionId.HIGHLIGHTERS) }
        rule.waitForIdle()
        assertTrue(LogViewSectionId.HIGHLIGHTERS in state.settings.compareHidden)
        assertFalse(exists("tile-card-highlighters"))
        rule.runOnIdle { state.revealFilterTiles(listOf(LogViewSectionId.HIGHLIGHTERS)) }
        rule.waitForIdle()
        assertFalse(LogViewSectionId.HIGHLIGHTERS in state.settings.compareHidden)
        assertTrue(exists("tile-card-highlighters"))
        assertEquals(emptyList(), state.settings.logViewHidden, "the single view's hidden list is left alone")
    }

    @Test
    fun linkedScrollMovesTheOtherPaneToTheSameTime() {
        setup(rowCount = 300, leftUnfiltered = false, rightUnfiltered = false) { it.updateSettings { s -> s.copy(compareSyncScroll = true) } }
        val left = state.logViewerScrollStateStore.lazyState("$A_ID:main")
        val right = state.logViewerScrollStateStore.lazyState("$B_ID:main")
        rule.waitUntil(5_000) { state.visibleItemsSummaryOf(A_ID) != null && state.visibleItemsSummaryOf(B_ID) != null }
        assertEquals(0, right.firstVisibleItemIndex)
        rule.runOnIdle { scope!!.launch { left.scrollToItem(150) } }
        rule.waitUntil(5_000) { right.firstVisibleItemIndex > 100 }
        assertTrue(kotlin.math.abs(right.firstVisibleItemIndex - left.firstVisibleItemIndex) <= 1, "B followed A to the same timestamp")
        // And the other way round.
        rule.runOnIdle { scope!!.launch { right.scrollToItem(40) } }
        rule.waitUntil(5_000) { left.firstVisibleItemIndex < 80 }
        assertTrue(kotlin.math.abs(right.firstVisibleItemIndex - left.firstVisibleItemIndex) <= 1)
    }

    @Test
    fun withoutTheLinkThePanesScrollIndependently() {
        setup(rowCount = 300, leftUnfiltered = false, rightUnfiltered = false)
        val left = state.logViewerScrollStateStore.lazyState("$A_ID:main")
        val right = state.logViewerScrollStateStore.lazyState("$B_ID:main")
        rule.runOnIdle { scope!!.launch { left.scrollToItem(150) } }
        rule.waitUntil(5_000) { left.firstVisibleItemIndex == 150 }
        rule.waitForIdle()
        assertEquals(0, right.firstVisibleItemIndex)
    }

    @Test
    fun nothingIsEmittedOutsideATileWithEveryTileShown() {
        val logFile = createTempDirectory("compare-tiling-log").toFile().resolve("app.log")
        assertEquals(null, AppLogger.configure(true, logFile.path))
        try {
            setup {
                it.aiPanelVisible = true
                it.updateSettings { s -> s.withCompareTileHidden(LogViewSectionId.DIAGRAMS, false) }
            }
            assertTrue(exists("tile-card-diagrams"))
            assertTrue(exists("tile-card-ai"))
            assertTrue(exists("tile-card-original_a"))
            assertTrue(exists("tile-card-original_b"))
        } finally {
            AppLogger.configure(false, null)
        }
        val logged = if (logFile.exists()) logFile.readText() else ""
        assertFalse("no tile layout id" in logged, "a composable was emitted outside a Tile: $logged")
    }
}
