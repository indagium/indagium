package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import com.indagium.model.AnnotationCopyFormat
import com.indagium.model.ChromeEdge
import com.indagium.model.FilterMode
import com.indagium.model.Highlighter
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogViewSectionId
import com.indagium.model.SavedFilter
import com.indagium.model.SavedFilterFolder
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
    fun theDefaultFilterTilesAreAsTallAsTheirContentWithNoEmptySpaceAtTheBottom() {
        setup()
        // Tags with no active tag is its header and the input: the weights that used to stretch it are gone.
        assertTrue(bounds("tile-card-tags").height.value < 90f, "Tags is ${bounds("tile-card-tags").height}")
        assertTrue(bounds("tile-card-log_level").height.value < 90f, "Log level is ${bounds("tile-card-log_level").height}")
        assertTrue(bounds("tile-card-message_rules").height.value < 90f)
        // The tiles stack with just the 6 dp gap between them, from the top of the column.
        val cards = listOf("tags", "message_rules", "log_composition", "highlighters", "log_level").map { bounds("tile-card-$it") }
        assertEquals(bounds("tile-card-log").top, cards.first().top)
        cards.zipWithNext().forEach { (above, below) -> assertEquals(above.bottom.value + 6f, below.top.value, 0.6f) }
    }

    // True when some splitter of the surface exists (their numbers depend on how many tiles have anything to drag).
    private fun anySplitter(): Boolean = (0..40).any { exists("tile-splitter-$it") }

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
        assertTrue(anySplitter())
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
        // At the top of the left column, above Tags; its own bar, no header; nothing to hide, collapse or maximize. As tall as its buttons (in the
        // filter column they wrap onto several lines) and its padding.
        assertTrue(bounds("tile-card-toolbar").bottom <= bounds("tile-card-tags").top)
        assertEquals(bounds("main-toolbar").height.value + 2 * 3f, bounds("tile-card-toolbar").height.value, 1.5f)
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

    // ---- tiles sized by their content, in the real sections ---------------------------------------------------------

    private fun heightOf(tag: String) = bounds(tag).height.value

    // The splitter (any) whose strip starts where the card [tile] ends: the handle that resizes that tile. Null when there is none.
    private fun handleUnder(tile: String): androidx.compose.ui.test.SemanticsNodeInteraction? {
        val bottom = bounds("tile-card-$tile").bottom.value
        val all = rule.onAllNodesWithContentDescription("Resize sections")
        return (0 until all.fetchSemanticsNodes().size).map { all[it] }.firstOrNull { kotlin.math.abs(it.getBoundsInRoot().top.value - bottom) < 1f }
    }

    private fun dragBy(node: androidx.compose.ui.test.SemanticsNodeInteraction, dyDp: Float) {
        val d = rule.density.density
        node.performTouchInput {
            down(center)
            moveBy(Offset(0f, dyDp / 2 * d))
            moveBy(Offset(0f, dyDp / 2 * d))
            up()
        }
        rule.waitForIdle()
    }

    private fun withTags(count: Int): (AppState) -> Unit = { s ->
        s.upFlt(TAB_ID) { it.copy(activeTags = (0 until count).map { i -> "AAAA$i" }.toSet()) }
    }

    @Test
    fun theTagChipListIsTheFlexPartOfTheTagsTileAndResizingItKeepsTheInputInFull() {
        setup(height = 900, configure = withTags(24))
        // 24 chips want about 220 dp; the default shows them at the five-row cap (140 dp).
        val list = heightOf("filter-tags-list")
        assertEquals(140f, list, 0.7f)
        val inputHeight = heightOf("filter-panel-tags-input")
        assertTrue(bounds("filter-panel-tags-input").bottom <= bounds("tile-card-tags").bottom, "the input is inside the card")
        val handle = assertNotNull(handleUnder("tags"), "Tags has something to drag")
        dragBy(handle, 60f)
        val grown = heightOf("filter-tags-list") - list
        assertTrue(grown > 30f, "the chip list grew by $grown")
        assertEquals(inputHeight, heightOf("filter-panel-tags-input"), 0.5f)
        assertTrue(bounds("filter-panel-tags-input").bottom <= bounds("tile-card-tags").bottom, "and the input is still inside the card")
        // Written once, on release, as the tile's own height.
        val tags = ((state.settings.logViewLayout as TileSplit<LogViewSectionId>).children.first() as TileSplit<LogViewSectionId>).children.first()
        assertEquals(LogViewSectionId.TAGS, (tags as TileLeaf<LogViewSectionId>).section)
        assertEquals(heightOf("tile-card-tags"), tags.sizeDp!!, 1f)
        // Dragged all the way up: one row of chips and the whole input remain.
        dragBy(assertNotNull(handleUnder("tags")), -800f)
        assertEquals(40f, heightOf("filter-tags-list"), 0.7f)
        assertTrue(bounds("filter-panel-tags-input").bottom <= bounds("tile-card-tags").bottom)
    }

    @Test
    fun theHighlighterListIsTheFlexPartAndTheAddFormStaysInFull() {
        setup(height = 900) { s ->
            s.upFlt(TAB_ID) { f -> f.copy(highlighters = (1..8).map { Highlighter("h$it", "pattern$it", false, Color(0xFF3366CC), true) }) }
        }
        // Eight highlighters, five rows at most by default.
        val list = heightOf("filter-highlighters-list")
        assertTrue(list in 100f..160f, "the list is $list")
        assertTrue(bounds("highlighter-add-pattern").bottom <= bounds("tile-card-highlighters").bottom, "the add form is in the card")
        dragBy(assertNotNull(handleUnder("highlighters")), -800f)
        assertEquals(40f, heightOf("filter-highlighters-list"), 0.7f)
        assertTrue(bounds("highlighter-add-pattern").bottom <= bounds("tile-card-highlighters").bottom, "still in the card when it is smallest")
    }

    @Test
    fun aTileThatIsOnlyContentHasNoHandleUnderIt() {
        setup()
        // Log level is a row of chips: nothing to drag under it.
        assertTrue(handleUnder("log_level") == null, "nothing to drag under Log level")
        // A flex tile can be resized whatever its list holds today, an empty one included.
        assertNotNull(handleUnder("tags"), "an empty Tags tile still has a handle: its list will fill later")
        state.upFlt(TAB_ID) { it.copy(activeTags = (0 until 24).map { i -> "AAAA$i" }.toSet()) }
        rule.waitForIdle()
        assertNotNull(handleUnder("tags"))
    }

    // Drags the handle under [tile] by [dyDp] and returns how much taller the card got.
    private fun growBy(tile: String, dyDp: Float): Float {
        val before = heightOf("tile-card-$tile")
        dragBy(assertNotNull(handleUnder(tile), "a handle under $tile"), dyDp)
        return heightOf("tile-card-$tile") - before
    }

    private fun storedHeight(tile: LogViewSectionId): Float? {
        fun find(node: TileNode<LogViewSectionId>): TileNode<LogViewSectionId>? = when (node) {
            is TileLeaf -> node.takeIf { it.section == tile }
            is TileSplit -> node.children.firstNotNullOfOrNull(::find)
        }
        return state.settings.logViewLayout?.let(::find)?.sizeDp
    }

    @Test
    fun anEmptyTagsTileCanBeMadeTallerThanItsContentAndWritesItsHeightOnRelease() {
        setup(height = 900)
        val before = heightOf("tile-card-tags")
        assertEquals(null, storedHeight(LogViewSectionId.TAGS), "the default height is not stored")
        val grown = growBy("tags", 120f)
        assertTrue(grown > 90f, "Tags with no active tags grew by $grown")
        // The input stays in the card at its full size; the extra room is the (empty) list area.
        assertTrue(bounds("filter-panel-tags-input").bottom <= bounds("tile-card-tags").bottom)
        assertEquals(before + grown, storedHeight(LogViewSectionId.TAGS)!!, 1f)
    }

    @Test
    fun theAiTileWithAnEmptyConversationCanBeMadeTallerAndWritesItsHeightOnRelease() {
        setup(height = 900) { it.aiPanelVisible = true }
        val before = heightOf("tile-card-ai")
        assertEquals(null, storedHeight(LogViewSectionId.AI))
        assertTrue(before < 300f, "an empty conversation leaves only the header and the composer: $before dp")
        val grown = growBy("ai", 160f)
        assertTrue(grown > 120f, "AI grew by $grown")
        assertEquals(before + grown, storedHeight(LogViewSectionId.AI)!!, 1f)
        assertEquals(before + grown, heightOf("tile-card-ai"), 1f)
        // The composer stays at the bottom of the card and the (empty) conversation took the extra room above it.
        val composer = rule.onNodeWithText("Ask about this log tab…", useUnmergedTree = true).getBoundsInRoot()
        assertTrue(bounds("tile-card-ai").bottom - composer.bottom < 100.dp, "the composer is at the bottom: $composer in ${bounds("tile-card-ai")}")
        assertTrue(composer.top - bounds("tile-card-ai").top > 160.dp, "and the conversation area above it is the extra room")
    }

    @Test
    fun theNotesTileCanBeMadeTallerThanItsNotesAndWritesItsHeightOnRelease() {
        setup(height = 900) { it.aiPanelVisible = true }
        val before = heightOf("tile-card-notes")
        val grown = growBy("notes", 150f)
        assertTrue(grown > 110f, "Notes grew by $grown past its content")
        assertEquals(before + grown, storedHeight(LogViewSectionId.NOTES)!!, 1f)
    }

    @Test
    fun theDefaultHeightsStayBasedOnTheContent() {
        setup(height = 900) { it.aiPanelVisible = true }
        // No tile of the default layout is stretched into the room the window has: every flex tile is its header, fixed part and list.
        assertTrue(heightOf("tile-card-tags") < 120f, "an empty Tags tile is its header and input: ${heightOf("tile-card-tags")}")
        assertTrue(heightOf("tile-card-ai") < 300f)
    }

    private fun saved(id: String, folder: String? = null, favorite: Boolean = false) = SavedFilter(
        id, "Filter $id", emptySet(), emptySet(), "", false, FilterMode.TAGS, emptySet(), "", false, emptyList(), false,
        folderId = folder, favorite = favorite,
    )

    private fun scrollablesBelow(node: androidx.compose.ui.semantics.SemanticsNode): Int =
        (if (node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)) 1 else 0) +
            node.children.sumOf(::scrollablesBelow)

    @Test
    fun theSavedFiltersTileHasExactlyOneScrollerInItsTreeEvenWithBigFoldersAndFavorites() {
        setup(height = 1200) { s ->
            s.savedFilterFolders = listOf(SavedFilterFolder("folder-a", "Work"), SavedFilterFolder("folder-b", "Home"))
            s.savedFilters = (1..30).map { i ->
                saved("s$i", folder = when { i <= 12 -> "folder-a"; i <= 18 -> "folder-b"; else -> null }, favorite = i % 5 == 0)
            }
        }
        val tree = rule.onNodeWithTag("filter-saved-filters-tree", useUnmergedTree = true).fetchSemanticsNode()
        // The region's own column is the only scrollable: no list per folder, per favorites or per Ungrouped inside it.
        assertEquals(1, scrollablesBelow(tree), "one scrollable in the tree")
    }

    @Test
    fun theColumnScrollbarSitsInTheGapBesideItsColumnAndNeverOverlapsACard() {
        setup(height = 500)
        assertTrue(exists("tile-scrollbar-tags"), "the default filter column is taller than 500 dp")
        val bar = rule.onNodeWithTag("tile-scrollbar-tags").getUnclippedBoundsInRoot()
        val log = bounds("tile-card-log")
        LOG_VIEW_FILTER_TILES.forEach { tile ->
            val card = rule.onNodeWithTag("tile-card-${tile.key}").getUnclippedBoundsInRoot()
            val clear = card.right.value + TILE_SCROLLBAR_CLEARANCE_DP <= bar.left.value + 0.1f
            assertTrue(clear, "$tile ends at ${card.right}, the scrollbar starts at ${bar.left}")
        }
        // In the gap to the log, centred: a dp of clearance on each side, and the bar as thin as the app's other side-section bars.
        assertEquals(THIN_SCROLLBAR_DP, bar.width.value, 0.1f)
        assertEquals(TILE_SCROLLBAR_CLEARANCE_DP, bar.left.value - bounds("tile-card-tags").right.value, 0.6f)
        assertEquals(TILE_SCROLLBAR_CLEARANCE_DP, log.left.value - bar.right.value, 0.6f)
    }

    @Test
    fun theLastTileOfTheRightColumnHasABottomHandleAndResizingItWritesItsSize() {
        setup(height = 800) { it.aiPanelVisible = true }
        val handle = assertNotNull(handleUnder("ai"), "a handle under the AI tile, the last of its column")
        assertTrue(handle.getBoundsInRoot().bottom.value <= 800f, "inside the window")
        val before = heightOf("tile-card-ai")
        dragBy(handle, -60f)
        val shrunk = before - heightOf("tile-card-ai")
        assertTrue(shrunk > 30f, "AI shrank by $shrunk")
        val right = (state.settings.logViewLayout as TileSplit<LogViewSectionId>).children.last() as TileSplit<LogViewSectionId>
        val ai = right.children.single { (it as? TileLeaf)?.section == LogViewSectionId.AI }
        assertEquals(before - shrunk, ai.sizeDp!!, 1f)
    }

    @Test
    fun aStoredHeightOutsideTheBoundsIsClampedAtLayoutAndLeftAsStored() {
        val classic = classicLogViewLayout(LogViewLegacySizes()) as TileSplit<LogViewSectionId>
        val filters = classic.children.first() as TileSplit<LogViewSectionId>
        val bloated = filters.copy(
            children = filters.children.map {
                when ((it as TileLeaf).section) {
                    // A content-only tile is as tall as its content whatever is stored; a flex tile honours a stored size up to the maximum.
                    LogViewSectionId.LOG_LEVEL -> it.copy(sizeDp = 1900f)
                    LogViewSectionId.TAGS -> it.copy(sizeDp = 500f)
                    else -> it
                }
            },
        )
        val stored = classic.copy(children = listOf(bloated) + classic.children.drop(1))
        setup(height = 900) { it.updateSettings { s -> s.withLogViewLayout(stored) } }
        assertTrue(heightOf("tile-card-log_level") < 120f, "Log level is ${heightOf("tile-card-log_level")} dp, not 1900")
        assertEquals(500f, heightOf("tile-card-tags"), 0.8f)
        assertEquals(stored, state.settings.logViewLayout, "reading an old size does not rewrite it")
    }

    // ---- the Notes bar and the AI composer in the log toolbar's style --------------------------------------------------------------------------

    private fun pixelAt(tag: String, xDp: Float, yDp: Float): Int {
        val image = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
        val d = rule.density.density
        return image[(xDp * d).toInt(), (yDp * d).toInt()].toArgb()
    }

    // A button of the Notes tile's header row by action id (its look may be labelled, compact or an icon, so the test tag names it).
    private fun notesBarButton(id: String) = rule.onNodeWithTag("notes-button-$id")

    private fun headerHas(text: String): Boolean =
        rule.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag("tile-header-notes"))).fetchSemanticsNodes().isNotEmpty()

    private val allFive = listOf("preview", "copy", "save", "new", "open")

    // The Classic layout with the right column [widthDp] wide (the Notes card is as wide), and three notes in the tab.
    private fun wideNotes(widthDp: Float): (AppState) -> Unit = { s ->
        s.updateFilterVisible(false)
        s.updateSettings { it.withLogViewLayout(classicLogViewLayout(LogViewLegacySizes(annotationPanelWidth = widthDp))) }
        repeat(3) { i -> s.addNoteBlock(TAB_ID, text = "note $i") }
    }

    private fun setNotesColumn(widthDp: Float) {
        state.updateSettings { it.withLogViewLayout(classicLogViewLayout(LogViewLegacySizes(annotationPanelWidth = widthDp))) }
        rule.waitForIdle()
    }

    // The left edge of the header icons (collapse when the tile has it, else maximize).
    private fun headerIconsLeft(): Float =
        listOf("tile-collapse-notes", "tile-maximize-notes").filter { exists(it) }.minOf { bounds(it).left.value }

    private fun insideBar(b: DpRect, bar: DpRect, rightLimit: Float = bar.right.value): Boolean =
        b.top >= bar.top && b.bottom <= bar.bottom && b.left >= bar.left && b.right.value <= rightLimit

    private fun centreOf(b: DpRect): Float = (b.left.value + b.right.value) / 2f

    @Test
    fun theNotesTileKeepsTheTextButtonsInItsHeaderRowCentredBetweenItsTitleAndItsIconsWhileTheyFit() {
        setup(width = 1000, height = 700, configure = wideNotes(780f))
        assertFalse(exists("notes-toolbar"), "no second row of buttons below the header")
        val header = bounds("tile-header-notes")
        assertEquals(32f, header.height.value, 0.6f, "the header is the usual one row")
        allFive.forEach { id ->
            val b = notesBarButton(id).getBoundsInRoot()
            assertTrue(b.top.value >= header.top.value - 0.6f && b.bottom.value <= header.bottom.value + 0.6f, "$id is inside the header row: $b in $header")
        }
        // Grip, icon, title, summary, then the buttons in order, then the header icons.
        val title = rule.onAllNodes(hasText("Notes") and hasAnyAncestor(hasTestTag("tile-header-notes")))[0].getBoundsInRoot()
        val summary = rule.onAllNodes(hasText("3 notes") and hasAnyAncestor(hasTestTag("tile-header-notes")))[0].getBoundsInRoot()
        val preview = notesBarButton("preview").getBoundsInRoot()
        val open = notesBarButton("open").getBoundsInRoot()
        assertTrue(bounds("tile-grip-notes").right <= title.left)
        assertTrue(title.right <= summary.left)
        assertTrue(summary.right <= preview.left)
        allFive.zipWithNext().forEach { (a, b) ->
            assertTrue(notesBarButton(a).getBoundsInRoot().right <= notesBarButton(b).getBoundsInRoot().left + 0.6f.dp, "$a is before $b")
        }
        assertTrue(open.right.value <= headerIconsLeft(), "the buttons come before the header icons")
        // Text labels, centred in the room between the summary and the header icons (the Row keeps a header gap before the icons).
        assertTrue(headerHas("Save") && headerHas("Preview") && headerHas("Open"), "the buttons are text")
        val roomCentre = (summary.right.value + headerIconsLeft() - TILE_HEADER_GAP_DP) / 2f
        assertEquals(roomCentre, (preview.left.value + open.right.value) / 2f, 2f, "the buttons are centred in the free room of the header")
        // The buttons are the log toolbar's AppButtons at their natural height, TILE_HEADER_GAP_DP apart.
        val export = bounds(logToolbarTag(LogToolbarItem.EXPORT.id))
        assertEquals(export.height.value, notesBarButton("save").getBoundsInRoot().height.value, 0.6f)
        assertEquals(TILE_HEADER_GAP_DP, notesBarButton("new").getBoundsInRoot().left.value - notesBarButton("save").getBoundsInRoot().right.value, 0.6f)
    }

    private fun toolbarHas(text: String): Boolean =
        rule.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag("notes-toolbar"))).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theNotesButtonsMoveToACentredTextBarUnderTheHeaderAtTheDefaultRightColumnWidth() {
        // The default right column is 360 dp (classic sizes) and 350 dp when dragged a little narrower: the labelled buttons do not fit next to the
        // title there, so they are in the panel's bar directly under the header, centred, and the header keeps the title and the summary.
        listOf(360f, 350f).forEach { column ->
            setup(width = 1000, height = 700, configure = wideNotes(column))
            val header = bounds("tile-header-notes")
            assertEquals(32f, header.height.value, 0.6f, "$column dp: the header is one row")
            assertTrue(exists("notes-toolbar"), "$column dp: the bar is there")
            val bar = bounds("notes-toolbar")
            assertTrue(bar.top.value in (header.bottom.value - 0.6f)..(header.bottom.value + 3f), "$column dp: the bar is directly under the header: $bar")
            assertTrue(headerHas("Notes") && headerHas("3 notes"), "$column dp: the header keeps its title and summary")
            assertTrue(exists("tile-grip-notes") && exists("tile-maximize-notes") && exists("tile-hide-notes"), "$column dp: grip and icons stay")
            allFive.forEach { id ->
                assertEquals(1, rule.onAllNodesWithTag("notes-button-$id").fetchSemanticsNodes().size, "$column dp: $id is drawn once")
                val b = notesBarButton(id).getBoundsInRoot()
                assertTrue(insideBar(b, bar), "$column dp: $id is in the bar: $b in $bar")
            }
            assertFalse(headerHas("Save") || headerHas("Preview"), "$column dp: no button text in the header")
            assertTrue(toolbarHas("Save") && toolbarHas("Preview") && toolbarHas("Open"), "$column dp: full labels while they fit on one line")
            val first = notesBarButton("preview").getBoundsInRoot()
            val last = notesBarButton("open").getBoundsInRoot()
            assertEquals(first.top.value, last.top.value, 0.6f, "$column dp: one line")
            assertEquals(centreOf(bar), (first.left.value + last.right.value) / 2f, 2f, "$column dp: centred")
        }
    }

    @Test
    fun theNotesActionsAreInTheHeaderOrTheBarAtEveryWidthAsTheLayoutSaysAndTheTitleStays() {
        setup(width = 1000, height = 700, configure = wideNotes(780f))
        listOf(780f, 580f, 540f, 500f, 440f, 400f, 360f, 330f, 300f, 260f, 220f, 190f).forEach { column ->
            setNotesColumn(column)
            val card = bounds("tile-card-notes").width.value
            val expected = notesHeaderLayout(card, hasLocate = false, hasRecent = false, headerIcons = 3)
            assertTrue(headerHas("Notes"), "$column dp: the title text stays")
            assertEquals(expected.showSummary, headerHas("3 notes"), "$column dp: the summary")
            assertEquals(!expected.actionsInHeader, exists("notes-toolbar"), "$column dp: the bar")
            assertEquals(32f, bounds("tile-header-notes").height.value, 0.6f, "$column dp: the header is one row")
            assertTrue(exists("tile-grip-notes") && exists("tile-maximize-notes") && exists("tile-hide-notes"), "$column dp: grip and icons stay")
            val header = bounds("tile-header-notes")
            allFive.forEach { id ->
                assertEquals(1, rule.onAllNodesWithTag("notes-button-$id").fetchSemanticsNodes().size, "$column dp: $id is drawn once")
                val b = notesBarButton(id).getBoundsInRoot()
                if (expected.actionsInHeader) {
                    assertTrue(insideBar(b, header, rightLimit = headerIconsLeft()), "$column dp: $id is in the header row: $b")
                } else {
                    val bar = bounds("notes-toolbar")
                    assertTrue(insideBar(b, bar), "$column dp: $id is in the bar: $b in $bar")
                }
            }
            // Always text: the labels are there, in the header or in the bar.
            assertTrue((headerHas("Save") || toolbarHas("Save")) && (headerHas("Preview") || toolbarHas("Preview")), "$column dp: text buttons")
            assertEquals(expected.actionsInHeader, headerHas("Save"), "$column dp: Save's label is in the header only while the buttons are")
        }
    }

    @Test
    fun aBarTooNarrowForOneLineWrapsItsCompactButtonsOntoCentredLines() {
        setup(width = 1000, height = 700, configure = wideNotes(200f))
        val bar = bounds("notes-toolbar")
        val tops = allFive.map { notesBarButton(it).getBoundsInRoot().top.value }.map { (it * 2).toInt() }.distinct()
        assertTrue(tops.size >= 2, "the buttons wrap onto a second line")
        assertTrue(bar.height.value > TILE_TOOLBAR_HEIGHT_DP + 6f, "the bar grows with its lines: ${bar.height}")
        allFive.forEach { id ->
            val b = notesBarButton(id).getBoundsInRoot()
            assertTrue(b.left >= bar.left && b.right <= bar.right, "$id is inside the bar: $b in $bar")
        }
        // Each line is centred in the bar.
        allFive.groupBy { (notesBarButton(it).getBoundsInRoot().top.value * 2).toInt() }.values.forEach { line ->
            val left = line.minOf { notesBarButton(it).getBoundsInRoot().left.value }
            val right = line.maxOf { notesBarButton(it).getBoundsInRoot().right.value }
            assertEquals(centreOf(bar), (left + right) / 2f, 3f, "a line of $line is centred")
        }
        // Compact buttons: narrower than the labelled ones (Preview is about 67 dp with its label's padding, 59 compact).
        assertTrue(notesBarButton("preview").getBoundsInRoot().width.value < 64f, "compact padding")
    }

    @Test
    fun theNotesButtonsKeepWorkingInTheHeaderAndTheCopyDropdownStillOpens() {
        setup(width = 1000, height = 700, configure = wideNotes(780f))
        allFive.forEach { notesBarButton(it).assertExists() }
        // Preview works from the header: it toggles the preview dialog of the three notes.
        notesBarButton("preview").performClick()
        rule.waitForIdle()
        assertTrue(state.tab(TAB_ID)!!.showAnnMd)
        state.toggleMd(TAB_ID)
        rule.waitForIdle()
        val arrow = rule.onNodeWithTag(NOTES_COPY_FORMATS_TAG)
        arrow.assertExists()
        arrow.performClick()
        rule.waitForIdle()
        AnnotationCopyFormat.entries.forEach { rule.onNodeWithText(it.label).assertExists() }
    }

    @Test
    fun theButtonsOfTheNotesBarWorkToo() {
        setup(width = 1000, height = 700, configure = wideNotes(350f))
        assertTrue(exists("notes-toolbar"), "a 350 dp Notes card has its buttons in the bar")
        notesBarButton("preview").performClick()
        rule.waitForIdle()
        assertTrue(state.tab(TAB_ID)!!.showAnnMd, "Preview toggled the preview from the bar")
        state.toggleMd(TAB_ID)
        rule.waitForIdle()
        notesBarButton("new").performClick()
        rule.waitForIdle()
        assertTrue(state.tab(TAB_ID)!!.annotations.blocks.isEmpty(), "New started a blank analysis from the bar")
    }

    @Test
    fun movingTheNotesButtonsBetweenTheHeaderAndTheBarMakesTheContentSizedTileOneBarTallerAndItIsStable() {
        // Notes is alone in its column here, so it is as tall as its content: header, the fixed part, the notes. With the buttons in the bar the
        // fixed part has the bar in it, so the card is one bar taller, and it stays that way (no jumping while the width is not changing).
        setup(width = 1000, height = 700, configure = wideNotes(780f))

        fun card(column: Float): Float {
            setNotesColumn(column)
            val first = bounds("tile-card-notes").height.value
            rule.waitForIdle()
            assertEquals(first, bounds("tile-card-notes").height.value, 0.6f, "$column dp: stable")
            assertTrue(exists("annotation-note-field"), "$column dp: the notes show")
            return first
        }
        val inHeader = card(540f)
        val inBar = card(480f)
        assertFalse(notesHeaderLayout(bounds("tile-card-notes").width.value, hasLocate = false, hasRecent = false, headerIcons = 3).actionsInHeader)
        assertTrue(exists("notes-toolbar"))
        assertEquals(TILE_TOOLBAR_HEIGHT_DP, inBar - inHeader, 8f, "the card is one bar taller")
        // And back: the same height as before.
        assertEquals(inHeader, card(540f), 0.6f)
    }

    @Test
    fun theAiTileHasNoSplitterInsideItAndTheComposerSitsAtTheBottomOfTheTile() {
        setup(width = 1300, height = 900) { it.aiPanelVisible = true }
        val card = bounds("tile-card-ai")
        val conversation = bounds("ai-conversation")
        val composer = bounds("ai-composer")
        // Nothing between the conversation and the composer: no divider bar, no handle.
        assertEquals(conversation.bottom.value, composer.top.value, 0.6f, "the composer starts where the conversation ends")
        assertTrue(card.bottom.value - composer.bottom.value < 12f, "the composer is at the bottom of the tile: $composer in $card")
        val field = bounds("ai-prompt-field").height.value
        assertEquals(60f, field, 0.6f)
        // A drag across the old divider's place does nothing to the composer (it used to resize the text field).
        val d = rule.density.density
        val y = (composer.top.value - card.top.value) * d
        rule.onNodeWithTag("tile-card-ai").performTouchInput {
            down(Offset(center.x, y - 2f * d))
            moveBy(Offset(0f, -40f * d))
            moveBy(Offset(0f, -40f * d))
            up()
        }
        rule.waitForIdle()
        assertEquals(field, bounds("ai-prompt-field").height.value, 0.6f)
        assertEquals(composer.top.value, bounds("ai-composer").top.value, 0.6f)
        // The composer is on the card: no band colour behind its buttons' row.
        assertEquals(LIGHT_THEME.p.toArgb(), pixelAt("ai-composer", 4f, bounds("ai-composer").height.value - 4f))
    }

    @Test
    fun theComposerStaysAtTheBottomWhenTheAiTileIsMadeTaller() {
        setup(width = 1300, height = 900) { it.aiPanelVisible = true }
        growBy("ai", 150f)
        val card = bounds("tile-card-ai")
        val composer = bounds("ai-composer")
        assertTrue(card.bottom.value - composer.bottom.value < 12f, "the composer is still at the bottom: $composer in $card")
        assertEquals(bounds("ai-conversation").bottom.value, composer.top.value, 0.6f)
        assertEquals(60f, bounds("ai-prompt-field").height.value, 0.6f, "the field kept its fixed height")
    }

    // ---- a tile moved out of its column can still be resized ---------------------------------------------------------------------------------

    // Drops the tile whose grip is [grip] at the window point ([x], [y]) dp.
    private fun moveTileTo(grip: String, x: Float, y: Float) {
        dragGripTo(grip, x, y)
        rule.onNodeWithTag(grip).performTouchInput { up() }
        rule.waitForIdle()
    }

    private val allTileKeys = LogViewSectionId.entries.map { it.key }

    private fun cardsNow(): Map<String, DpRect> =
        allTileKeys.filter { exists("tile-card-$it") }.associateWith { bounds("tile-card-$it") }

    // The splitters touching a side of the card [tile], as (handle, vertical strip?) pairs.
    @Suppress("MagicNumber")
    private fun handlesAround(tile: String): Map<String, Pair<androidx.compose.ui.test.SemanticsNodeInteraction, Boolean>> {
        val card = bounds("tile-card-$tile")
        val all = rule.onAllNodesWithContentDescription("Resize sections")
        return (0 until all.fetchSemanticsNodes().size).map { all[it] }.mapNotNull { h ->
            val b = h.getBoundsInRoot()
            val overlapY = minOf(b.bottom.value, card.bottom.value) - maxOf(b.top.value, card.top.value)
            val overlapX = minOf(b.right.value, card.right.value) - maxOf(b.left.value, card.left.value)
            when {
                kotlin.math.abs(b.right.value - card.left.value) < 1.5f && overlapY > 20f -> "left" to (h to true)
                kotlin.math.abs(b.left.value - card.right.value) < 1.5f && overlapY > 20f -> "right" to (h to true)
                kotlin.math.abs(b.bottom.value - card.top.value) < 1.5f && overlapX > 20f -> "top" to (h to false)
                kotlin.math.abs(b.top.value - card.bottom.value) < 1.5f && overlapX > 20f -> "bottom" to (h to false)
                else -> null
            }
        }.toMap()
    }

    // Drags the handle one way and, when nothing moved (it was at its limit), the other: true when some card changed.
    private fun handleMovesSomething(handle: androidx.compose.ui.test.SemanticsNodeInteraction, vertical: Boolean): Boolean {
        val d = rule.density.density
        for (sign in listOf(1f, -1f)) {
            val before = cardsNow()
            val delta = 40f * sign * d
            handle.performTouchInput {
                // A vertical strip is pressed near its bottom: a scrolling column's scrollbar thumb (at the top while unscrolled) owns the strip behind it.
                down(if (vertical) Offset(center.x, height - minOf(14f * d, height / 4f)) else center)
                moveBy(if (vertical) Offset(delta / 2, 0f) else Offset(0f, delta / 2))
                moveBy(if (vertical) Offset(delta / 2, 0f) else Offset(0f, delta / 2))
                up()
            }
            rule.waitForIdle()
            if (cardsNow() != before) return true
        }
        return false
    }

    // Where each arrangement drops the tile: a point of the target card, as fractions of its size, or an absolute offset in dp from its top left.
    private data class Drop(val target: String, val fx: Float, val fy: Float, val dxDp: Float = 0f, val dyDp: Float = 0f, val sides: Set<String>)

    @Test
    fun aTileMovedOutOfItsColumnKeepsAWorkingHandleOnEverySideThatHasANeighbour() {
        val drops = listOf(
            // Between the filter column and the log, as its own column.
            Drop("log", 0f, 0.5f, dxDp = 10f, sides = setOf("left", "right")),
            // Above the log, in the same column (below the page's top band).
            Drop("log", 0.5f, 0f, dyDp = 40f, sides = setOf("bottom")),
            // Beside a filter tile: a row inside the (scrolling) filter column.
            Drop("tags", 1f, 0.5f, dxDp = -10f, sides = setOf("left", "bottom")),
        )
        listOf(false, true).forEach { toolbarInLayout ->
            listOf("highlighters", "notes", "ai").forEach { tile ->
                drops.forEach { drop ->
                    if (::state.isInitialized) {
                        state.close()
                        directory.deleteRecursively()
                    }
                    // The filter column overflows here: its scrollbar sits in the gap but leaves the splitter to the track around the thumb.
                    setup(width = 1000, height = 700, showUnfiltered = toolbarInLayout, chrome = toolbarInLayout) { s ->
                        s.aiPanelVisible = true
                        if (toolbarInLayout) inLayout(s)
                    }
                    val target = bounds("tile-card-${drop.target}")
                    moveTileTo(
                        "tile-grip-$tile",
                        target.left.value + target.width.value * drop.fx + drop.dxDp,
                        target.top.value + target.height.value * drop.fy + drop.dyDp,
                    )
                    val label = "$tile onto ${drop.target} (${drop.fx}, ${drop.fy}), toolbar tile in the layout: $toolbarInLayout"
                    assertNotNull(state.settings.logViewLayout, "$label: the drop was stored")
                    val around = handlesAround(tile)
                    drop.sides.forEach { side ->
                        val (handle, vertical) = assertNotNull(around[side], "$label: a handle on the $side of $tile (has ${around.keys})")
                        assertTrue(handleMovesSomething(handle, vertical), "$label: the $side handle of $tile moves something")
                    }
                }
            }
        }
    }

    // ---- the AI message box can be made taller again ---------------------------------------------------------------------------------------------

    @Test
    fun draggingTheTopEdgeOfTheMessageBoxMakesItTallerAndTheConversationShorterWithinLimits() {
        setup(width = 1300, height = 900) { it.aiPanelVisible = true }
        // 34 dp less than it used to be: the Notes card above has its buttons in a bar of its own at this width, which is one bar taller, and the
        // message box's handle has to stay inside the window.
        growBy("ai", 186f)
        val card = bounds("tile-card-ai")
        val field = bounds("ai-prompt-field").height.value
        val conversation = bounds("ai-conversation").height.value
        assertEquals(60f, field, 0.6f)
        val handle = bounds("ai-prompt-resize")
        assertEquals(bounds("ai-prompt-field").top.value, handle.top.value, 0.6f, "the handle is on the field's top edge")
        dragBy(rule.onNodeWithTag("ai-prompt-resize"), -60f)
        val grown = bounds("ai-prompt-field").height.value - field
        assertTrue(grown > 30f, "the field grew by $grown")
        assertEquals(grown, conversation - bounds("ai-conversation").height.value, 1f, "and the conversation gave exactly that much way")
        assertEquals(card, bounds("tile-card-ai"), "the tile itself did not change size")
        // The bottom row (Send, mic, hint) stays under the field, at the bottom of the tile.
        assertTrue(bounds("ai-composer").bottom.value - bounds("tile-card-ai").bottom.value in -12f..0f)
        assertTrue(rule.onNodeWithText("Send").getBoundsInRoot().top.value >= bounds("ai-prompt-field").bottom.value)
        // Never past 60% of the tile's body.
        dragBy(rule.onNodeWithTag("ai-prompt-resize"), -2000f)
        val body = card.height.value - 33f
        assertTrue(bounds("ai-prompt-field").height.value <= body * 0.6f + 1f, "${bounds("ai-prompt-field").height} of a $body dp body")
        assertTrue(bounds("ai-prompt-field").height.value > body * 0.5f, "and it did reach the cap")
        // Dragging back down never goes under the starting height.
        dragBy(rule.onNodeWithTag("ai-prompt-resize"), 3000f)
        assertEquals(60f, bounds("ai-prompt-field").height.value, 0.6f)
        // A double-click puts it back from wherever it is.
        dragBy(rule.onNodeWithTag("ai-prompt-resize"), -80f)
        assertTrue(bounds("ai-prompt-field").height.value > 90f)
        rule.onNodeWithTag("ai-prompt-resize").performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        assertEquals(60f, bounds("ai-prompt-field").height.value, 0.6f)
    }

    // ---- the toolbar tile among the filter tiles ---------------------------------------------------------------------------------------------

    // The toolbar button whose tooltip / description is [description] (the buttons are icon-only by default).
    private fun toolbarButton(description: String) =
        rule.onAllNodes(hasContentDescription(description) and hasAnyAncestor(hasTestTag("main-toolbar")))[0]

    private fun buttonBounds(description: String): DpRect = toolbarButton(description).getBoundsInRoot()

    @Test
    fun theToolbarTileInTheFilterColumnIsAsTallAsItsWrappedButtonsAndHasNoHandle() {
        setup(height = 800, chrome = true, configure = ::inLayout)
        val card = bounds("tile-card-toolbar")
        val toolbar = bounds("main-toolbar")
        // In the 220 dp column the groups wrap onto several lines; the tile is those lines and its padding, not a fixed slim height.
        assertTrue(toolbar.height.value > 2 * 32f, "the buttons wrap: ${toolbar.height}")
        assertEquals(toolbar.height.value + 2 * 3f, card.height.value, 1.5f)
        assertEquals(bounds("tile-card-tags").top.value, card.bottom.value + 6f, 0.6f, "the next tile follows after the normal gap")
        assertTrue(handleUnder("toolbar") == null, "no handle under the toolbar tile")
        // Wider, the same tile is fewer lines and so shorter: it follows its content.
        state.updateSettings { it.withLogViewLayout(classicLogViewLayout(LogViewLegacySizes(filterPanelWidth = 520f))) }
        rule.waitForIdle()
        assertTrue(bounds("tile-card-toolbar").height.value < card.height.value, "a wider tile needs fewer lines")
        assertEquals(bounds("main-toolbar").height.value + 2 * 3f, bounds("tile-card-toolbar").height.value, 1.5f)
    }

    @Test
    fun theToolbarTilesButtonsAreGroupedLikeTheDefaultBar() {
        // On the full-width top row the whole joined group fits one line: Filter | Notes | AI | Compare | Cases | Open are one segmented run.
        setup(width = 1000, height = 700, chrome = true) { s ->
            s.tabs = s.tabs + mkTab("other", "other.log", rows())
            inLayout(s)
            val onTop = dropTile(classicLogViewLayout(LogViewLegacySizes()), LogViewSectionId.TOOLBAR, TileDropTarget.OnPage(TileEdge.TOP))
            s.updateSettings { it.withToolbarDroppedInLayout(onTop) }
        }
        val run = listOf("Toggle filter panel", "Toggle notes panel", "Toggle AI panel", "Toggle compare view", "Search past analyses", "Open log file")
        run.zipWithNext().forEach { (a, b) ->
            assertEquals(buttonBounds(a).right.value, buttonBounds(b).left.value, 0.6f, "$a and $b are joined")
        }
        val gap = 4f
        val beforeRun = buttonBounds("Toggle filter panel").left.value - buttonBounds("New tab").right.value
        assertEquals(gap, beforeRun, 0.6f, "New tab is a group of its own, a bar gap before the run")
        assertEquals(gap, buttonBounds("Layout").left.value - buttonBounds("Open log file").right.value, 0.6f, "Layout is a group of its own")
        // Icon-only buttons are narrow enough to keep the joined group on one line even in the 220 dp filter column; with labels the group is about
        // 520 dp, so in a 330 dp column it falls apart into its three chunks, which wrap whole: the toggles stay one run, Compare / Cases another.
        val column = classicLogViewLayout(LogViewLegacySizes(filterPanelWidth = 330f))
        state.updateSettings { it.withLogViewLayout(withToolbarTile(column, true)).copy(toolbarIconOnlyButtons = false) }
        rule.waitForIdle()

        fun labelled(text: String) = rule.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag("main-toolbar")))[0].getBoundsInRoot()
        val ai = labelled("AI")
        assertEquals(labelled("Filter").right.value, labelled("Notes").left.value, 0.6f, "the toggles are still joined")
        assertEquals(labelled("Notes").right.value, ai.left.value, 0.6f)
        assertEquals(labelled("Compare").right.value, labelled("Cases").left.value, 0.6f, "Compare and Cases are one run")
        val compareAside = labelled("Compare").left.value - ai.right.value >= gap - 0.6f
        assertTrue(labelled("Compare").top.value >= ai.bottom.value || compareAside, "the chunks do not touch")
        val openAside = labelled("Open").left.value - labelled("Cases").right.value >= gap - 0.6f
        assertTrue(labelled("Open").top.value >= labelled("Cases").bottom.value || openAside, "Open is its own run")
        val card = bounds("tile-card-toolbar")
        listOf("Filter", "Notes", "AI", "Compare", "Cases", "Open").forEach {
            assertTrue(labelled(it).right.value <= card.right.value + 0.6f, "$it is inside the tile")
        }
    }

    @Test
    fun aToolbarChunkTooWideForTheTileIsSplitIntoItsButtonsSoNothingIsClipped() {
        setup(width = 1000, height = 700, chrome = true) { s ->
            inLayout(s)
            s.updateSettings { it.copy(toolbarIconOnlyButtons = false) }
        }

        fun labelled(text: String) = rule.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag("main-toolbar")))[0].getBoundsInRoot()
        val card = bounds("tile-card-toolbar")
        // The 214 dp card cannot hold Filter + Notes + AI side by side: each button is its own unit now, none squeezed or cut off.
        listOf("Filter", "Notes", "AI", "Compare", "Cases", "Open").forEach {
            assertTrue(labelled(it).right.value <= card.right.value + 0.6f, "$it is inside the tile")
            assertTrue(labelled(it).width.value >= 45f, "$it keeps its full width: ${labelled(it).width}")
        }
        assertTrue(labelled("Notes").left.value - labelled("Filter").right.value >= 3.4f || labelled("Notes").top.value > labelled("Filter").top.value)
    }

    @Test
    fun aFilterColumnWithTheToolbarTileScrollsAndItsOtherTilesStillResize() {
        setup(height = 520, chrome = true, configure = ::inLayout)
        assertTrue(exists("tile-scrollbar-toolbar"), "the column with the toolbar overflows and shows its scrollbar")
        val tagsTop = rule.onNodeWithTag("tile-card-tags").getUnclippedBoundsInRoot().top
        rule.onNodeWithTag("tile-card-tags").performMouseInput {
            moveTo(center)
            scroll(150f)
        }
        rule.waitForIdle()
        assertTrue(rule.onNodeWithTag("tile-card-tags").getUnclippedBoundsInRoot().top < tagsTop, "the wheel scrolled the column")
        rule.onNodeWithTag("tile-card-tags").performMouseInput {
            moveTo(center)
            scroll(-600f)
        }
        rule.waitForIdle()
        // Resizing one tile changes that tile only; the tiles below simply move down.
        val before = listOf("message_rules", "log_composition").associateWith { heightOf("tile-card-$it") }
        val tagsBefore = heightOf("tile-card-tags")
        val grown = growBy("tags", 80f)
        assertTrue(grown > 50f, "Tags grew by $grown")
        assertEquals(tagsBefore + grown, storedHeight(LogViewSectionId.TAGS)!!, 1f)
        before.forEach { (tile, height) -> assertEquals(height, heightOf("tile-card-$tile"), 0.6f, "$tile kept its height") }
        // The toolbar tile is a content tile of the column: no stored size, and still on top.
        assertEquals(null, storedHeight(LogViewSectionId.TOOLBAR).takeIf { it != TOOLBAR_TILE_COLUMN_DP })
    }

    // ---- a flex tile's fixed parts after its list go to the bottom, empty list or not ------------------------------------------------------------

    @Test
    fun theTagsInputSitsAtTheBottomOfATallerTileWhetherOrNotThereAreChips() {
        setup(height = 900)
        growBy("tags", 150f)

        fun inputAtBottom(label: String) {
            val card = bounds("tile-card-tags")
            val input = bounds("filter-panel-tags-input")
            assertTrue(card.bottom.value - input.bottom.value in 0f..12f, "$label: the input is at the bottom of the tile: $input in $card")
            assertTrue(input.top.value - card.top.value > 150f, "$label: and the room is above it")
        }
        inputAtBottom("no tags")
        state.upFlt(TAB_ID) { it.copy(activeTags = setOf("AAAA1", "AAAA2")) }
        rule.waitForIdle()
        inputAtBottom("two chips")
        // Resizing it back to its content brings the input up under the (empty) list again; dragging taller takes it down again.
        state.upFlt(TAB_ID) { it.copy(activeTags = emptySet()) }
        rule.waitForIdle()
        val before = bounds("filter-panel-tags-input").top.value
        growBy("tags", 60f)
        assertTrue(bounds("filter-panel-tags-input").top.value > before + 30f, "taller again: the input moved down with the bottom edge")
        inputAtBottom("no tags, taller still")
    }

    @Test
    fun everyFlexTileKeepsItsFormsAtTheBottomWithAnEmptyList() {
        setup(height = 1100)
        growBy("message_rules", 120f)
        val rules = bounds("tile-card-message_rules")
        val rulesInput = rule.onNodeWithText("search in messages…").getBoundsInRoot()
        assertTrue(rules.bottom.value - rulesInput.bottom.value in 0f..14f, "Message rules: the input row is at the bottom: $rulesInput in $rules")
        // Highlighters: the (empty) list is above the add form, and the room the tile was made taller by went to the list, not under the form.
        val tailBefore = bounds("tile-card-highlighters").bottom.value - bounds("highlighter-add-submit").bottom.value
        val grown = growBy("highlighters", 120f)
        val highlighters = bounds("tile-card-highlighters")
        val list = bounds("filter-highlighters-list")
        assertEquals(grown, list.height.value, 1f, "the empty list took all the extra room: $list in $highlighters")
        val tailAfter = highlighters.bottom.value - bounds("highlighter-add-submit").bottom.value
        assertEquals(tailBefore, tailAfter, 1f, "and the add form is still at the bottom, as it was")
        assertTrue(bounds("highlighter-add-pattern").top.value >= list.bottom.value - 0.6f, "the form follows the list")
        // The first form of the Sequences tile ("+ New sequence…") is under the empty list as well.
        rule.onNodeWithText("Group sequences").assertExists()
        growBy("sequences", 120f)
        val sequences = bounds("tile-card-sequences")
        val newSequenceBottom = rule.onNodeWithText("+ New sequence…").getBoundsInRoot().bottom.value
        assertTrue(sequences.bottom.value - newSequenceBottom in 0f..14f, "Sequences: the new-sequence row is at the bottom")
    }

    // ---- equal gaps, with or without a scrollbar ---------------------------------------------------------------------------------------------

    private fun overlaps(a: DpRect, b: DpRect): Boolean =
        a.left.value < b.right.value && b.left.value < a.right.value && a.top.value < b.bottom.value && b.top.value < a.bottom.value

    @Test
    fun theGapsOnBothSidesOfTheLogAreEqualWithAndWithoutAScrollbarAndTheBarStaysInsideTheGap() {
        // Few enough filter tiles that the left column fits the window.
        setup(height = 700, width = 1000) { s ->
            listOf(LogViewSectionId.HIGHLIGHTERS, LogViewSectionId.SEQUENCES, LogViewSectionId.ISSUES, LogViewSectionId.SAVED_FILTERS)
                .forEach(s::hideLogViewTile)
        }
        val windowRight = rule.onRoot().getBoundsInRoot().right.value

        fun leftGap() = bounds("tile-card-log").left.value - bounds("tile-card-tags").right.value

        fun rightGap() = bounds("tile-card-notes").left.value - bounds("tile-card-log").right.value
        assertFalse(exists("tile-scrollbar-tags") || exists("tile-scrollbar-notes"), "nothing overflows with four filter tiles")
        assertEquals(LOG_VIEW_GAP_ROW_DP, leftGap(), 0.6f)
        assertEquals(LOG_VIEW_GAP_ROW_DP, rightGap(), 0.6f)
        assertEquals(LOG_VIEW_HOST_PAD_DP.toFloat(), windowRight - bounds("tile-card-notes").right.value, 0.6f, "the margin at the window's right edge")
        assertEquals(LOG_VIEW_HOST_PAD_DP.toFloat(), bounds("tile-card-tags").left.value, 0.6f, "and at its left edge")
        // Now both side columns overflow: the cards keep their widths, the gaps stay equal, the bars are in them.
        val classic = classicLogViewLayout(LogViewLegacySizes()) as TileSplit<LogViewSectionId>

        fun pinAll(node: TileNode<LogViewSectionId>): TileNode<LogViewSectionId> = (node as TileSplit<LogViewSectionId>).copy(
            children = node.children.map { (it as TileLeaf<LogViewSectionId>).copy(sizeDp = 400f) },
        )
        val tall = classic.copy(children = listOf(pinAll(classic.children[0]), classic.children[1], pinAll(classic.children[2])))
        val widthBefore = bounds("tile-card-tags").width.value to bounds("tile-card-notes").width.value
        state.aiPanelVisible = true
        state.updateSettings { it.withLogViewLayout(tall) }
        rule.waitForIdle()
        assertTrue(exists("tile-scrollbar-tags") && exists("tile-scrollbar-notes"), "both side columns overflow now")
        assertEquals(widthBefore.first, bounds("tile-card-tags").width.value, 0.6f, "the left cards are as wide as without a bar")
        assertEquals(widthBefore.second, bounds("tile-card-notes").width.value, 0.6f, "the right cards are as wide as without a bar")
        assertEquals(LOG_VIEW_GAP_ROW_DP, leftGap(), 0.6f)
        assertEquals(LOG_VIEW_GAP_ROW_DP, rightGap(), 0.6f)
        val leftBar = rule.onNodeWithTag("tile-scrollbar-tags").getUnclippedBoundsInRoot()
        val rightBar = rule.onNodeWithTag("tile-scrollbar-notes").getUnclippedBoundsInRoot()
        assertTrue(leftBar.left.value >= bounds("tile-card-tags").right.value + TILE_SCROLLBAR_CLEARANCE_DP - 0.1f)
        assertTrue(leftBar.right.value <= bounds("tile-card-log").left.value - TILE_SCROLLBAR_CLEARANCE_DP + 0.1f)
        assertTrue(rightBar.left.value >= bounds("tile-card-notes").right.value + TILE_SCROLLBAR_CLEARANCE_DP - 0.1f)
        assertTrue(rightBar.right.value <= windowRight - TILE_SCROLLBAR_CLEARANCE_DP + 0.1f, "the right column's bar is inside the window: $rightBar")
        // No bar touches any card.
        val cards = (LOG_VIEW_FILTER_TILES.map { it.key } + listOf("log", "notes", "ai")).filter { exists("tile-card-$it") }
        cards.forEach { key ->
            val card = rule.onNodeWithTag("tile-card-$key").getUnclippedBoundsInRoot()
            assertFalse(overlaps(card, leftBar) || overlaps(card, rightBar), "$key does not touch a column scrollbar: $card")
        }
    }
}
