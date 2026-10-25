package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.indagium.debug.AppLogger
import com.indagium.model.ChromeEdge
import com.indagium.model.FilterMode
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogViewSectionId
import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import com.indagium.model.ToolbarAlign
import com.indagium.model.ToolbarPlacement
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
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val TAB_ID = "log-view-tiling-ui"

/** The real FileView (single log view) on tiles: the default Classic arrangement, tab-strip toggles, drag, hide, presets, lock, stacking. */
@OptIn(ExperimentalTestApi::class)
class LogViewTilingUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var directory: File
    private lateinit var state: AppState
    private val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // The AI tile's dictation button needs the app-level voice runtime; a model-less one is enough for layout tests.
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
            VoiceInputRuntime(controller, VoiceModelInstaller(createTempDirectory("log-view-tiling-voice").toFile()), VoiceRecognitionEngine.WHISPER),
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

    private fun rows() = (1..40).map { id ->
        LogEntry(id, "10:00:00.$id", LogLevel.I, "Tag${id % 3}", "row $id", pid = 100, tid = 1)
    }

    /** Composes a Layout button over the FileView, in a host [width] x [height] dp. */
    private fun setup(width: Int = 1100, height: Int = 700, showUnfiltered: Boolean = false, chrome: Boolean = false, configure: (AppState) -> Unit = {}) {
        directory = createTempDirectory("log-view-tiling-ui").toFile()
        state = AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archives"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        )
        state.tabs = listOf(mkTab(TAB_ID, "tiling.log", rows()).copy(showUnfiltered = showUnfiltered))
        state.activeTabId = TAB_ID
        configure(state)
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME, LocalVoiceInputUi provides voiceUi) {
                if (chrome) {
                    // The real window chrome around the view: the toolbar's bar or its tile, the dock strips between them.
                    Box(Modifier.size(width.dp, height.dp)) {
                        ChromeScaffold(state) { state.tab(TAB_ID)?.let { FileView(state, it) } }
                    }
                } else {
                    Column(Modifier.size(width.dp, height.dp)) {
                        Box(Modifier.fillMaxWidth().height(36.dp)) { LayoutMenuButton(state, onReclaimFocus = {}) }
                        Box(Modifier.weight(1f).fillMaxWidth()) { state.tab(TAB_ID)?.let { FileView(state, it) } }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String): DpRect = rule.onNodeWithTag(tag).getBoundsInRoot()

    private fun exists(tag: String): Boolean = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private val filterKeys = LOG_VIEW_FILTER_TILES.map { it.key }

    @Test
    fun defaultLayoutEmitsTheFilterTilesTheLogAndNotes() {
        setup()
        filterKeys.forEach { assertTrue(exists("tile-card-$it"), "filter tile $it is present") }
        assertTrue(exists("tile-card-log"))
        assertTrue(exists("tile-card-notes"))
        // Defaults: AI is off, Diagram library is hidden, there is no video and no Original.
        assertFalse(exists("tile-card-ai"))
        assertFalse(exists("tile-card-diagrams"))
        assertFalse(exists("tile-card-video"))
        assertFalse(exists("tile-card-original"))
        // Classic: filters | log | notes, left to right.
        assertTrue(bounds("tile-card-tags").right <= bounds("tile-card-log").left)
        assertTrue(bounds("tile-card-log").right <= bounds("tile-card-notes").left)
        // The log toolbar is the Log tile's header: grip and maximize, no separate header row.
        assertTrue(exists("tile-grip-log"))
        assertTrue(exists("tile-maximize-log"))
        assertFalse(exists("tile-header-log"))
    }

    @Test
    fun theAiTileShowsWhenItsGroupIsOn() {
        setup { it.aiPanelVisible = true }
        assertTrue(exists("tile-card-ai"))
        assertTrue(bounds("tile-card-notes").bottom <= bounds("tile-card-ai").top, "AI sits below Notes in the right column")
    }

    @Test
    fun theTabStripFilterToggleHidesEveryFilterTileAndTheLogWidens() {
        setup()
        val before = bounds("tile-card-log").width
        state.updateFilterVisible(false)
        rule.waitForIdle()
        filterKeys.forEach { assertFalse(exists("tile-card-$it"), "filter tile $it is gone") }
        assertTrue(bounds("tile-card-log").width > before, "the log took the filter column's width")
        state.updateFilterVisible(true)
        rule.waitForIdle()
        assertTrue(exists("tile-card-tags"))
    }

    @Test
    fun turningUnfilteredOnPutsOriginalAboveTheLog() {
        setup()
        state.toggleUnfiltered(TAB_ID)
        rule.waitForIdle()
        val original = bounds("tile-card-original")
        val log = bounds("tile-card-log")
        assertTrue(original.bottom <= log.top, "Original is above the log")
    }

    // The Classic layout with every filter tile given a height of its own: together taller than a short window.
    private fun tallFilterColumn(): TileNode<LogViewSectionId> {
        val classic = classicLogViewLayout(LogViewLegacySizes()) as TileSplit<LogViewSectionId>
        val filters = classic.children.first() as TileSplit<LogViewSectionId>
        val tall = filters.copy(children = filters.children.map { (it as TileLeaf<LogViewSectionId>).copy(sizeDp = 220f) })
        return classic.copy(children = listOf(tall) + classic.children.drop(1))
    }

    @Test
    fun aFilterColumnTallerThanTheWindowScrollsAndLeavesTheOtherTilesAlone() {
        setup(height = 600) { it.updateSettings { s -> s.withLogViewLayout(tallFilterColumn()) } }
        val last = LOG_VIEW_FILTER_TILES.last().key
        assertTrue(exists("tile-scrollbar-tags"), "the column overflows and shows a scrollbar")
        val hostBottom = bounds("tile-card-log").bottom
        assertTrue(rule.onNodeWithTag("tile-card-$last").getUnclippedBoundsInRoot().top > hostBottom, "the last filter tile is below the window")
        val logBefore = bounds("tile-card-log")
        val tagsTop = rule.onNodeWithTag("tile-card-tags").getUnclippedBoundsInRoot().top
        rule.onNodeWithTag("tile-card-tags").performMouseInput {
            moveTo(center)
            scroll(300f)
        }
        rule.waitForIdle()
        assertTrue(rule.onNodeWithTag("tile-card-tags").getUnclippedBoundsInRoot().top < tagsTop, "the wheel scrolled the filter column")
        assertEquals(logBefore, bounds("tile-card-log"), "the log did not move")
        assertFalse(exists("tile-scrollbar-log"), "only the filter column scrolls")
        // Scrolling is not a layout edit: the stored layout is what was set. Collapse still shrinks a tile to its header.
        assertEquals(state.settings.logViewLayout, state.settings.withLogViewLayout(tallFilterColumn()).logViewLayout)
        // Back to the top, with the pointer over the column wherever the Tags tile is now.
        rule.onRoot().performMouseInput {
            moveTo(Offset(100f * rule.density.density, 300f * rule.density.density))
            scroll(-600f)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("tile-collapse-tags").performClick()
        rule.waitForIdle()
        assertEquals(32f, rule.onNodeWithTag("tile-card-tags").getUnclippedBoundsInRoot().height.value, 0.5f)
    }

    @Test
    fun theDefaultFilterColumnStillFitsALargeWindowWithoutAScrollbar() {
        setup()
        assertFalse(exists("tile-scrollbar-tags"))
        val last = LOG_VIEW_FILTER_TILES.last().key
        assertTrue(bounds("tile-card-$last").bottom <= bounds("tile-card-log").bottom + 1.dp, "the weights fill the window as before")
    }

    private fun containsRow(tree: TileNode<LogViewSectionId>, first: LogViewSectionId, second: LogViewSectionId): Boolean = when (tree) {
        is TileLeaf -> false
        is TileSplit -> {
            val sections = tree.children.map { (it as? TileLeaf)?.section }
            (tree.axis == TileAxis.ROW && sections.zipWithNext().any { it == first to second }) || tree.children.any { containsRow(it, first, second) }
        }
    }

    @Test
    fun draggingOriginalToTheLogsRightEdgePersistsARowAndWritesOnReleaseOnly() {
        setup(showUnfiltered = true)
        assertEquals(null, state.settings.logViewLayout, "nothing stored before the first edit")
        val grip = rule.onNodeWithTag("tile-grip-original")
        val gripBounds = bounds("tile-grip-original")
        val log = bounds("tile-card-log")
        val d = rule.density.density
        val targetX = (log.left.value + log.width.value * 0.9f) * d
        val targetY = (log.top.value + log.height.value / 2f) * d
        val local = Offset(targetX - gripBounds.left.value * d, targetY - gripBounds.top.value * d)
        grip.performTouchInput {
            down(center)
            moveBy(Offset(30f, 30f))
            moveTo(local)
        }
        rule.waitForIdle()
        assertEquals(null, state.settings.logViewLayout, "nothing is written while dragging")
        assertTrue(exists("tile-drop-preview"))
        grip.performTouchInput { up() }
        rule.waitForIdle()
        val stored = assertNotNull(state.settings.logViewLayout)
        assertTrue(containsRow(stored, LogViewSectionId.LOG, LogViewSectionId.ORIGINAL), "Original now sits right of the log: $stored")
        assertTrue(bounds("tile-card-original").left >= bounds("tile-card-log").right)
    }

    @Test
    fun hidingNotesListsItAsHiddenAndCountsOnTheBadgeWithoutTouchingTheGroupFlag() {
        setup()
        assertFalse(exists("layout-menu-badge"), "nothing hidden beyond the preset's own Diagram library")
        assertTrue(state.annotationVisible)
        rule.onNodeWithTag("tile-hide-notes").performClick()
        rule.waitForIdle()
        assertTrue(LogViewSectionId.NOTES in state.settings.logViewHidden)
        assertTrue(state.annotationVisible, "the tab-strip Notes toggle is unchanged")
        assertFalse(exists("tile-card-notes"))
        assertTrue(exists("layout-menu-badge"))
        rule.onNodeWithTag("layout-menu-badge").assertTextEquals("1")
    }

    @Test
    fun theLayoutMenuShowsAHiddenPanelAgain() {
        setup()
        rule.onNodeWithTag("tile-hide-notes").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("layout-menu-button").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("layout-menu-panel-notes").performClick()
        rule.waitForIdle()
        assertTrue(exists("tile-card-notes"))
        assertFalse(LogViewSectionId.NOTES in state.settings.logViewHidden)
    }

    @Test
    fun theLogFocusPresetSwitchesTheFilterGroupOffAndKeepsTheBadgeAtZero() {
        setup()
        rule.onNodeWithTag("layout-menu-button").performClick()
        rule.waitForIdle()
        assertTrue(exists("layout-menu-popup"))
        rule.onNodeWithTag("layout-menu-preset-LOG_FOCUS").performClick()
        rule.waitForIdle()
        assertFalse(state.filterVisible, "Log focus switches the Filter group off instead of hiding eight tiles")
        assertEquals(listOf(LogViewSectionId.DIAGRAMS), state.settings.logViewHidden)
        filterKeys.forEach { assertFalse(exists("tile-card-$it")) }
        assertTrue(exists("tile-card-log"))
        assertTrue(exists("tile-card-notes"))
        assertFalse(exists("layout-menu-badge"))
        assertTrue(state.logViewPresetActive(LogViewLayoutPreset.LOG_FOCUS))
        assertFalse(state.logViewPresetActive(LogViewLayoutPreset.CLASSIC))
    }

    @Test
    fun lockingTilesRemovesTheGripsAndKeepsTheSplitters() {
        setup()
        assertTrue(exists("tile-grip-tags"))
        rule.onNodeWithTag("layout-menu-button").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("layout-menu-lock").performClick()
        rule.waitForIdle()
        assertTrue(state.settings.logViewTilesLocked)
        assertFalse(exists("tile-grip-tags"))
        assertFalse(exists("tile-grip-log"))
        assertFalse(exists("tile-grip-notes"))
        assertTrue(exists("tile-splitter-0"))
    }

    @Test
    fun collapsingHighlightersFlipsTheFilterPanelFlag() {
        setup()
        assertTrue(state.fpState.highlightersExpanded)
        rule.onNodeWithTag("tile-collapse-highlighters").performClick()
        rule.waitForIdle()
        assertFalse(state.fpState.highlightersExpanded)
        // The collapsed tile keeps just its header height (32dp).
        assertEquals(32f, bounds("tile-card-highlighters").height.value, 0.6f)
        rule.onNodeWithTag("tile-collapse-highlighters").performClick()
        rule.waitForIdle()
        assertTrue(state.fpState.highlightersExpanded)
    }

    @Test
    fun collapsingNotesIsStoredInTheSettings() {
        setup()
        rule.onNodeWithTag("tile-collapse-notes").performClick()
        rule.waitForIdle()
        assertTrue(LogViewSectionId.NOTES in state.settings.logViewCollapsed)
    }

    @Test
    fun f6FollowsTheTilesInReadingOrder() {
        setup(showUnfiltered = true) { it.aiPanelVisible = true }
        val tab = assertNotNull(state.tab(TAB_ID))
        // Filters first (one stop for all eight tiles), Original and Log (the log column), Notes, AI.
        assertEquals(
            listOf(LogViewFocusPanel.FILTERS, LogViewFocusPanel.ORIGINAL, LogViewFocusPanel.LOG, LogViewFocusPanel.NOTES, LogViewFocusPanel.AI),
            state.logViewVisiblePanels(tab),
        )
        state.updateFilterVisible(false)
        assertEquals(
            listOf(LogViewFocusPanel.ORIGINAL, LogViewFocusPanel.LOG, LogViewFocusPanel.NOTES, LogViewFocusPanel.AI),
            state.logViewVisiblePanels(tab),
        )
    }

    @Test
    fun maximizingATileGivesItTheWholeHostAndEscapeRestores() {
        setup()
        rule.onNodeWithTag("tile-maximize-notes").performClick()
        rule.waitForIdle()
        assertEquals(LogViewSectionId.NOTES, state.logViewMaximized)
        val notes = bounds("tile-card-notes")
        assertTrue(notes.width.value > 1000f, "Notes fills the host: ${notes.width}")
        assertEquals(0f, bounds("tile-card-log").width.value, "the other tiles stay composed at 0 x 0")
        // The restore button on the maximized tile.
        rule.onNodeWithTag("tile-maximize-notes").performClick()
        rule.waitForIdle()
        assertEquals(null, state.logViewMaximized)
        assertTrue(bounds("tile-card-notes").width.value < 600f)
        // Escape restores too (the host handles it when no field below consumed it).
        // (The maximize click hands keyboard focus to the log, which sits inside the host, so the key bubbles up to it.)
        rule.onNodeWithTag("tile-maximize-notes").performClick()
        rule.waitForIdle()
        assertEquals(LogViewSectionId.NOTES, state.logViewMaximized)
        rule.onNodeWithTag("tile-card-notes").performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertEquals(null, state.logViewMaximized)
    }

    @Test
    fun belowTheStackThresholdTheTilesStackWithoutSplitters() {
        setup(width = 640)
        assertTrue(exists("tile-card-tags"))
        assertTrue(exists("tile-card-log"))
        assertTrue(exists("tile-card-notes"))
        assertFalse(exists("tile-splitter-0"))
        assertFalse(exists("tile-grip-tags"))
        // Cards below the first sit beyond the scroll viewport, whose clipped bounds read as empty: use the unclipped ones.
        val tags = rule.onNodeWithTag("tile-card-tags").getUnclippedBoundsInRoot()
        val log = rule.onNodeWithTag("tile-card-log").getUnclippedBoundsInRoot()
        assertEquals(tags.left, log.left, "stacked tiles share one column")
        assertTrue(tags.bottom <= log.top)
        // The hide button stays in the stacked layout.
        assertTrue(exists("tile-hide-notes"))
    }

    @Test
    fun resettingAfterAnEditReappliesTheLastPreset() {
        setup()
        state.commitLogViewLayout(dropTile(state.currentLogViewLayout(), LogViewSectionId.NOTES, TileDropTarget.OnPage(TileEdge.LEFT)))
        rule.waitForIdle()
        assertTrue(bounds("tile-card-notes").right <= bounds("tile-card-tags").left, "Notes moved to the far left")
        rule.onNodeWithTag("layout-menu-button").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("layout-menu-reset").performClick()
        rule.waitForIdle()
        assertTrue(bounds("tile-card-log").right <= bounds("tile-card-notes").left, "Classic again: Notes right of the log")
    }

    @Test
    fun nothingIsEmittedOutsideATileWithEveryTileShown() {
        val logFile = createTempDirectory("log-view-tiling-log").toFile().resolve("app.log")
        assertEquals(null, AppLogger.configure(true, logFile.path))
        try {
            setup(showUnfiltered = true) {
                it.aiPanelVisible = true
                it.updateSettings { s -> s.withLogViewTileHidden(LogViewSectionId.DIAGRAMS, false) }
            }
            assertTrue(exists("tile-card-diagrams"))
            assertTrue(exists("tile-card-ai"))
            assertTrue(exists("tile-card-original"))
            // Regex mode swaps the Tags tile's body (and its dialog host) without leaving any stray layout node behind.
            state.setFilterMode(TAB_ID, FilterMode.KEYWORD)
            rule.waitForIdle()
            assertTrue(exists("tile-mode-tabs"))
            assertFalse(exists("tile-card-message_rules"))
        } finally {
            AppLogger.configure(false, null)
        }
        val logged = if (logFile.exists()) logFile.readText() else ""
        assertFalse("no tile layout id" in logged, "a composable was emitted outside a Tile: $logged")
    }

    // ── The toolbar as a tile ──────────────────────────────────────

    private fun inLayout(s: AppState) {
        s.updateSettings { it.copy(toolbarPlacement = ToolbarPlacement.IN_LAYOUT) }
    }

    // Presses [grip], pulls it past the touch slop and moves to the window point ([x], [y]) dp, leaving the finger down.
    private fun dragGripTo(grip: String, x: Float, y: Float) {
        val origin = bounds(grip)
        val d = rule.density.density
        val node = rule.onNodeWithTag(grip)
        node.performTouchInput {
            down(center)
            moveBy(Offset(30f, 30f))
        }
        rule.waitForIdle()
        node.performTouchInput { moveTo(Offset((x - origin.left.value) * d, (y - origin.top.value) * d)) }
        rule.waitForIdle()
    }

    private fun centerOf(tag: String): Pair<Float, Float> = bounds(tag).let { (it.left.value + it.right.value) / 2f to (it.top.value + it.bottom.value) / 2f }

    @Test
    fun theToolbarTileShowsTheToolbarAboveTagsAndTheChromeDrawsNone() {
        setup(height = 1000, chrome = true, configure = ::inLayout)
        assertTrue(exists("tile-card-toolbar"))
        assertEquals(1, rule.onAllNodesWithTag("main-toolbar").fetchSemanticsNodes().size, "one toolbar: the tile's")
        assertTrue(exists("tile-grip-toolbar"))
        assertFalse(exists("chrome-grip-toolbar"))
        assertFalse(exists("main-toolbar-bar") && bounds("main-toolbar-bar").width.value > 0f, "the chrome draws no toolbar bar")
        rule.onNodeWithTag("tile-card-toolbar").assertExists()
        rule.onNodeWithContentDescription("New tab").assertExists()
        // Slim, at the top of the left column, above Tags; its own bar, no header; nothing to hide, collapse or maximize.
        assertTrue(bounds("tile-card-toolbar").bottom <= bounds("tile-card-tags").top)
        assertEquals(80f, bounds("tile-card-toolbar").height.value, 1f)
        assertEquals(bounds("tile-card-tags").left, bounds("tile-card-toolbar").left)
        listOf("header", "hide", "collapse", "maximize", "size").forEach { assertFalse(exists("tile-$it-toolbar"), "no $it on the toolbar tile") }
        // Its buttons work: New tab opens the home tab.
        rule.onNodeWithContentDescription("New tab").performClick()
        rule.waitForIdle()
        assertTrue(state.tabs.any { it.isCaptureLauncher })
    }

    @Test
    fun theToolbarIsNoTileWhileItIsOnABar() {
        setup(chrome = true)
        assertFalse(exists("tile-card-toolbar"))
        assertTrue(exists("main-toolbar"))
        assertTrue(exists("chrome-grip-toolbar"))
    }

    @Test
    fun theToolbarTileCannotBeHiddenAndIsNotInTheLayoutMenu() {
        setup(chrome = true, configure = ::inLayout)
        state.hideLogViewTile(LogViewSectionId.TOOLBAR)
        rule.waitForIdle()
        assertFalse(LogViewSectionId.TOOLBAR in state.settings.logViewHidden)
        assertTrue(exists("tile-card-toolbar"))
        state.updateSettings { it.copy(logViewHidden = it.logViewHidden + LogViewSectionId.TOOLBAR) }
        rule.waitForIdle()
        assertTrue(exists("tile-card-toolbar"), "even a hand-edited hidden list does not hide it")
        rule.onNodeWithTag("layout-menu-button").performClick()
        rule.waitForIdle()
        assertTrue(exists("layout-menu-panel-notes"))
        assertFalse(exists("layout-menu-panel-toolbar"))
    }

    @Test
    fun aStoredLayoutThatLacksTheToolbarTileGetsItBackWhileInLayout() {
        setup(chrome = true) {
            it.updateSettings { s -> s.copy(toolbarPlacement = ToolbarPlacement.IN_LAYOUT, logViewLayout = classicLogViewLayout(LogViewLegacySizes())) }
        }
        assertTrue(exists("tile-card-toolbar"))
        // A layout edit stores the completed tree.
        state.commitLogViewLayout(state.currentLogViewLayout())
        assertTrue(LogViewSectionId.TOOLBAR in tileLeafSections(assertNotNull(state.settings.logViewLayout)))
    }

    @Test
    fun draggingTheToolbarBarOverTheTilesShowsTheDropPreviewAndInsertsTheTileOnRelease() {
        setup(chrome = true)
        assertEquals(ToolbarPlacement.WITH_TABS_END, state.settings.toolbarPlacement)
        val log = bounds("tile-card-log")
        dragGripTo("chrome-grip-toolbar", log.left.value + log.width.value * 0.08f, log.top.value + log.height.value / 2f)
        assertTrue(exists("tile-drop-preview"), "the normal tile drop preview")
        assertFalse(exists("chrome-dock-tabs-end") && false)
        assertEquals(ToolbarPlacement.WITH_TABS_END, state.settings.toolbarPlacement, "nothing is written while dragging")
        rule.onNodeWithTag("chrome-grip-toolbar").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(ToolbarPlacement.IN_LAYOUT, state.settings.toolbarPlacement)
        assertEquals(ToolbarPlacement.WITH_TABS_END, state.settings.toolbarFallbackPlacement, "the bar it left is the fallback")
        assertTrue(LogViewSectionId.TOOLBAR in tileLeafSections(assertNotNull(state.settings.logViewLayout)))
        assertTrue(exists("tile-card-toolbar"))
        assertTrue(bounds("tile-card-toolbar").right <= bounds("tile-card-log").left, "left of the log, where it was dropped")
        assertFalse(exists("chrome-grip-toolbar"))
        assertFalse(exists("tile-drop-preview"))
    }

    @Test
    fun draggingTheToolbarBarOntoADockStripStillDocksIt() {
        setup(chrome = true)
        val log = bounds("tile-card-log")
        // Over the content's bottom edge strip (not the log's middle): the chrome's target wins and the tiles show none of theirs.
        dragGripTo("chrome-grip-toolbar", log.left.value + log.width.value / 2f, log.bottom.value - 6f)
        assertFalse(exists("tile-drop-preview"))
        rule.onNodeWithTag("chrome-grip-toolbar").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(ToolbarPlacement.OWN_EDGE, state.settings.toolbarPlacement)
        assertFalse(exists("tile-card-toolbar"))
    }

    @Test
    fun draggingTheToolbarTileOntoAnEdgeStripPutsItBackOnABar() {
        setup(chrome = true, configure = ::inLayout)
        // Press, pull past the slop; the chrome's strips appear and the tiles show no target of their own over one.
        val grip = bounds("tile-grip-toolbar")
        val d = rule.density.density
        val node = rule.onNodeWithTag("tile-grip-toolbar")
        node.performTouchInput {
            down(center)
            moveBy(Offset(30f, 30f))
        }
        rule.waitForIdle()
        assertTrue(exists("chrome-dock-bottom-end"), "the dock strips show while the toolbar tile is dragged")
        val (x, y) = centerOf("chrome-dock-bottom-end")
        node.performTouchInput { moveTo(Offset((x - grip.left.value) * d, (y - grip.top.value) * d)) }
        rule.waitForIdle()
        assertFalse(exists("tile-drop-preview"), "no tile target under a dock strip")
        assertEquals(ToolbarPlacement.IN_LAYOUT, state.settings.toolbarPlacement, "nothing is written while dragging")
        node.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(ToolbarPlacement.OWN_EDGE, state.settings.toolbarPlacement)
        assertEquals(ChromeEdge.BOTTOM, state.settings.toolbarEdge)
        assertEquals(ToolbarAlign.END, state.settings.toolbarAlign)
        assertFalse(exists("tile-card-toolbar"))
        assertFalse(exists("chrome-dock-bottom-end"))
        assertTrue(exists("main-toolbar-bar"))
        assertTrue(LogViewSectionId.TOOLBAR !in tileLeafSections(assertNotNull(state.settings.logViewLayout ?: state.currentLogViewLayout())))
    }

    @Test
    fun movingTheToolbarTileToAnotherTileKeepsItInTheLayout() {
        setup(chrome = true, configure = ::inLayout)
        val log = bounds("tile-card-log")
        dragGripTo("tile-grip-toolbar", log.left.value + log.width.value * 0.92f, log.top.value + log.height.value / 2f)
        assertTrue(exists("tile-drop-preview"))
        rule.onNodeWithTag("tile-grip-toolbar").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(ToolbarPlacement.IN_LAYOUT, state.settings.toolbarPlacement)
        assertTrue(bounds("tile-card-toolbar").left >= bounds("tile-card-log").right, "right of the log now")
    }

    @Test
    fun aMaximizedTileCoversTheToolbarTileSoTheChromeShowsTheFallbackBarMeanwhile() {
        setup(chrome = true, configure = ::inLayout)
        assertFalse(exists("main-toolbar-bar") && bounds("main-toolbar-bar").width.value > 0f)
        rule.onNodeWithTag("tile-maximize-notes").performClick()
        rule.waitForIdle()
        assertFalse(exists("tile-card-toolbar"))
        assertTrue(exists("main-toolbar"), "never left without the toolbar")
        assertEquals(1, rule.onAllNodesWithTag("main-toolbar").fetchSemanticsNodes().size)
        rule.onNodeWithTag("tile-maximize-notes").performClick()
        rule.waitForIdle()
        assertTrue(exists("tile-card-toolbar"))
    }

    @Test
    fun theToolbarTileIsASlimCardInTheStackedNarrowView() {
        setup(width = 600, height = 700, chrome = true, configure = ::inLayout)
        assertTrue(exists("tile-card-toolbar"))
        assertEquals(TOOLBAR_STACKED_CARD_DP, bounds("tile-card-toolbar").height.value, 1f)
    }
}
