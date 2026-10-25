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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.indagium.debug.AppLogger
import com.indagium.model.FilterMode
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogViewSectionId
import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
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
    private fun setup(width: Int = 1100, height: Int = 700, showUnfiltered: Boolean = false, configure: (AppState) -> Unit = {}) {
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
                Column(Modifier.size(width.dp, height.dp)) {
                    Box(Modifier.fillMaxWidth().height(36.dp)) { LayoutMenuButton(state, onReclaimFocus = {}) }
                    Box(Modifier.weight(1f).fillMaxWidth()) { state.tab(TAB_ID)?.let { FileView(state, it) } }
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
}
