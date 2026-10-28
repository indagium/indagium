package com.indagium

import com.indagium.model.Filter
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogViewSectionId
import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileSplit
import com.indagium.ui.AppState
import com.indagium.ui.LogViewLayoutPreset
import com.indagium.ui.LogViewLegacySizes
import com.indagium.ui.applyLogViewPreset
import com.indagium.ui.classicLogViewLayout
import com.indagium.ui.columnTiles
import com.indagium.ui.commitLogViewLayout
import com.indagium.ui.currentLogViewLayout
import com.indagium.ui.hideLogViewTile
import com.indagium.ui.isLogViewTileCollapsed
import com.indagium.ui.legacyLogViewSizes
import com.indagium.ui.logViewBadgeCount
import com.indagium.ui.logViewDefaultDp
import com.indagium.ui.logViewPresetActive
import com.indagium.ui.logViewTileContext
import com.indagium.ui.logViewTileVisible
import com.indagium.ui.mkTab
import com.indagium.ui.resetLogViewLayout
import com.indagium.ui.revealFilterTiles
import com.indagium.ui.showLogViewTile
import com.indagium.ui.tileLeafSections
import com.indagium.ui.toggleLogViewTileCollapsed
import com.indagium.ui.toggleLogViewTilesLocked
import com.indagium.ui.videoSlot
import com.indagium.ui.withVideoSlot
import org.junit.After
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TAB = "log-view-layout-state"

/** The single log view's tiling state on AppState: commit / hide / show / presets / reset / lock / collapse / reveal. */
class LogViewLayoutStateTest {
    private val directory: File = createTempDirectory("log-view-layout-state").toFile()
    private val state = AppState(
        autosaveFile = File(directory, "state.cache"),
        notesDir = File(directory, "notes"),
        archiveCacheDir = File(directory, "archives"),
        customCommandsDir = File(directory, "commands"),
        autoExportNotes = false,
    ).also {
        it.tabs = listOf(mkTab(TAB, "state.log", listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "Tag", "row"))))
        it.activeTabId = TAB
    }

    @After
    fun tearDown() {
        state.close()
        directory.deleteRecursively()
    }

    private fun visible(id: LogViewSectionId): Boolean =
        logViewTileVisible(id, state.logViewTileContext(assertNotNull(state.tab(TAB))), state.settings.logViewHidden)

    @Test
    fun legacySizesReadTheLegacyFieldsAndSeedClassic() {
        state.filterPanelWidth = 260f
        state.annotationPanelWidth = 400f
        state.rightSidebarSplit = 0.3f
        val sizes = state.legacyLogViewSizes()
        assertEquals(LogViewLegacySizes(260f, 400f, 0.3f, state.compareSplit), sizes)
        assertNull(state.settings.logViewLayout)
        assertEquals(classicLogViewLayout(sizes), state.currentLogViewLayout())
    }

    @Test
    fun theTileContextFollowsTheGroupFlagsAndTheTab() {
        val tab = assertNotNull(state.tab(TAB))
        var ctx = state.logViewTileContext(tab)
        assertTrue(ctx.groupOn(com.indagium.model.LogViewTileGroup.FILTERS))
        assertFalse(ctx.groupOn(com.indagium.model.LogViewTileGroup.AI))
        assertFalse(ctx.showUnfiltered)
        assertFalse(ctx.hasVideoPanel)
        state.aiPanelVisible = true
        state.filterBarVisible = true
        ctx = state.logViewTileContext(tab.copy(showUnfiltered = true))
        assertTrue(ctx.groupOn(com.indagium.model.LogViewTileGroup.AI))
        assertTrue(ctx.showUnfiltered)
        assertTrue(ctx.filterBarVisible)
    }

    @Test
    fun commitSanitizesAndStoresTheLayout() {
        val partial = TileSplit(TileAxis.ROW, listOf(TileLeaf(LogViewSectionId.LOG), TileLeaf(LogViewSectionId.NOTES)))
        state.commitLogViewLayout(partial)
        val stored = assertNotNull(state.settings.logViewLayout)
        assertEquals(
            LogViewSectionId.entries.filter { it.scope != com.indagium.model.LogViewScope.COMPARE && it != LogViewSectionId.TOOLBAR }.toSet(),
            tileLeafSections(stored).toSet(),
        )
    }

    @Test
    fun hidingATileListsItAndLeavesTheGroupFlag() {
        state.hideLogViewTile(LogViewSectionId.NOTES)
        assertTrue(LogViewSectionId.NOTES in state.settings.logViewHidden)
        assertTrue(state.annotationVisible)
        assertFalse(visible(LogViewSectionId.NOTES))
        state.logViewMaximized = LogViewSectionId.NOTES
        state.hideLogViewTile(LogViewSectionId.NOTES)
        assertNull(state.logViewMaximized, "hiding the maximized tile restores")
    }

    @Test
    fun showingATileUnhidesItAndSwitchesItsGroupOn() {
        assertFalse(LogViewSectionId.DIAGRAMS in state.settings.logViewHidden)
        assertTrue(state.isLogViewTileCollapsed(LogViewSectionId.DIAGRAMS))
        assertTrue(visible(LogViewSectionId.DIAGRAMS), "the Diagram library is visible by default")
        state.hideLogViewTile(LogViewSectionId.NOTES)
        state.annotationVisible = false
        state.showLogViewTile(LogViewSectionId.NOTES)
        assertFalse(LogViewSectionId.NOTES in state.settings.logViewHidden)
        assertTrue(state.annotationVisible)
        assertTrue(visible(LogViewSectionId.NOTES))
    }

    @Test
    fun logFocusSwitchesTheFilterGroupOffInsteadOfHidingEightTiles() {
        state.applyLogViewPreset(LogViewLayoutPreset.LOG_FOCUS)
        assertFalse(state.filterVisible)
        assertEquals(listOf(LogViewSectionId.DIAGRAMS), state.settings.logViewHidden)
        assertEquals(LogViewLayoutPreset.LOG_FOCUS.id, state.settings.lastLogViewPreset)
        val stored = assertNotNull(state.settings.logViewLayout)
        // The filter tiles are still in the stored tree, so switching the group back on shows them somewhere.
        assertTrue(tileLeafSections(stored).containsAll(LogViewSectionId.entries.filter { it.group == com.indagium.model.LogViewTileGroup.FILTERS }))
        // ... and they come back where Classic has them: a pinned column at the left, not a weighted one beside the log.
        val root = stored as TileSplit
        assertEquals(TileAxis.ROW, root.axis)
        assertEquals(LogViewSectionId.TAGS, tileLeafSections(root.children.first()).first())
        assertNotNull(root.children.first().sizeDp)
        assertTrue(state.logViewPresetActive(LogViewLayoutPreset.LOG_FOCUS))
        assertFalse(state.logViewPresetActive(LogViewLayoutPreset.CLASSIC))
        assertEquals(0, state.logViewBadgeCount(state.logViewTileContext(assertNotNull(state.tab(TAB)))))
    }

    @Test
    fun presetsThatShowFiltersSwitchTheGroupBackOn() {
        state.applyLogViewPreset(LogViewLayoutPreset.LOG_FOCUS)
        state.applyLogViewPreset(LogViewLayoutPreset.REVIEW)
        assertTrue(state.filterVisible)
        assertTrue(state.logViewPresetActive(LogViewLayoutPreset.REVIEW))
        state.applyLogViewPreset(LogViewLayoutPreset.CLASSIC)
        assertTrue(state.logViewPresetActive(LogViewLayoutPreset.CLASSIC))
        assertEquals(emptyList(), state.settings.logViewHidden)
        assertTrue(state.isLogViewTileCollapsed(LogViewSectionId.DIAGRAMS))
    }

    @Test
    fun switchingTheFilterGroupOffDoesNotMakeClassicOrLogFocusActiveWhileDiagramRemainsVisible() {
        assertTrue(state.logViewPresetActive(LogViewLayoutPreset.CLASSIC))
        state.updateFilterVisible(false)
        assertFalse(state.logViewPresetActive(LogViewLayoutPreset.LOG_FOCUS))
        assertFalse(state.logViewPresetActive(LogViewLayoutPreset.CLASSIC))
        assertTrue(visible(LogViewSectionId.DIAGRAMS), "filter visibility does not hide the Diagram library")
    }

    @Test
    fun resetReappliesTheLastPreset() {
        state.applyLogViewPreset(LogViewLayoutPreset.REVIEW)
        state.hideLogViewTile(LogViewSectionId.AI)
        state.commitLogViewLayout(TileLeaf(LogViewSectionId.LOG))
        assertFalse(state.logViewPresetActive(LogViewLayoutPreset.REVIEW))
        state.resetLogViewLayout()
        assertTrue(state.logViewPresetActive(LogViewLayoutPreset.REVIEW))
        assertEquals(LogViewLayoutPreset.REVIEW.id, state.settings.lastLogViewPreset)
    }

    @Test
    fun lockingToggles() {
        assertFalse(state.settings.logViewTilesLocked)
        state.toggleLogViewTilesLocked()
        assertTrue(state.settings.logViewTilesLocked)
        state.toggleLogViewTilesLocked()
        assertFalse(state.settings.logViewTilesLocked)
    }

    @Test
    fun collapsingUsesTheFilterPanelFlagsWhereTheyExistAndTheSettingsElsewhere() {
        assertFalse(state.isLogViewTileCollapsed(LogViewSectionId.HIGHLIGHTERS))
        state.toggleLogViewTileCollapsed(LogViewSectionId.HIGHLIGHTERS)
        assertFalse(state.fpState.highlightersExpanded)
        assertTrue(state.isLogViewTileCollapsed(LogViewSectionId.HIGHLIGHTERS))
        assertTrue(LogViewSectionId.HIGHLIGHTERS !in state.settings.logViewCollapsed)
        state.toggleLogViewTileCollapsed(LogViewSectionId.HIGHLIGHTERS)
        assertTrue(state.fpState.highlightersExpanded)

        // Log composition starts collapsed (its legacy section did).
        assertTrue(state.isLogViewTileCollapsed(LogViewSectionId.LOG_COMPOSITION))
        state.toggleLogViewTileCollapsed(LogViewSectionId.LOG_COMPOSITION)
        assertTrue(state.fpState.logCompositionExpanded)

        state.toggleLogViewTileCollapsed(LogViewSectionId.NOTES)
        assertTrue(LogViewSectionId.NOTES in state.settings.logViewCollapsed)
        assertTrue(state.isLogViewTileCollapsed(LogViewSectionId.NOTES))
        state.toggleLogViewTileCollapsed(LogViewSectionId.NOTES)
        assertFalse(state.isLogViewTileCollapsed(LogViewSectionId.NOTES))
    }

    @Test
    fun revealingFilterTilesUnhidesExpandsAndClearsMaximize() {
        state.hideLogViewTile(LogViewSectionId.HIGHLIGHTERS)
        state.fpState.highlightersExpanded = false
        state.updateFilterVisible(false)
        state.logViewMaximized = LogViewSectionId.NOTES
        state.revealFilterTiles(listOf(LogViewSectionId.HIGHLIGHTERS))
        assertTrue(state.filterVisible)
        assertTrue(visible(LogViewSectionId.HIGHLIGHTERS))
        assertTrue(state.fpState.highlightersExpanded)
        assertNull(state.logViewMaximized)
    }

    @Test
    fun revealingWithNoVisibleFilterTileUnhidesThemAll() {
        com.indagium.model.LogViewSectionId.entries.filter { it.group == com.indagium.model.LogViewTileGroup.FILTERS }
            .forEach { state.hideLogViewTile(it) }
        state.updateFilterVisible(false)
        state.revealFilterTiles()
        assertTrue(state.filterVisible)
        assertTrue(visible(LogViewSectionId.TAGS))
        assertTrue(visible(LogViewSectionId.SAVED_FILTERS))
    }

    @Test
    fun theResidualChipRevealsAndExpandsTheSectionsItSummarizes() {
        val tab = assertNotNull(state.tab(TAB))
        state.tabs = listOf(tab.copy(filter = Filter(levels = setOf(LogLevel.E))))
        state.hideLogViewTile(LogViewSectionId.LOG_LEVEL)
        state.fpState.lvlExpanded = false
        state.updateFilterVisible(false)
        state.openFilterPanelRevealing(TAB)
        assertTrue(state.filterVisible)
        assertTrue(visible(LogViewSectionId.LOG_LEVEL))
        assertTrue(state.fpState.lvlExpanded)
    }

    @Test
    fun theBadgeCountsOnlyWhatTheLastPresetDoesNotHide() {
        val ctx = state.logViewTileContext(assertNotNull(state.tab(TAB)))
        assertEquals(0, state.logViewBadgeCount(ctx), "the default layout shows the Diagram library")
        state.hideLogViewTile(LogViewSectionId.NOTES)
        assertEquals(1, state.logViewBadgeCount(ctx))
        state.showLogViewTile(LogViewSectionId.DIAGRAMS)
        assertEquals(1, state.logViewBadgeCount(ctx), "showing a tile the preset hid never drives the count negative")
    }

    // ---- pure helpers ---------------------------------------------------------------------------------------------

    @Test
    fun theVideoSlotCanBeReadAndReplaced() {
        val tree = classicLogViewLayout(LogViewLegacySizes())
        val (weight, pin) = assertNotNull(tree.videoSlot())
        assertNull(pin)
        val pinned = tree.withVideoSlot(weight, 240f)
        assertEquals(240f, pinned.videoSlot()?.second)
        assertEquals(tree, pinned.withVideoSlot(weight, null))
    }

    @Test
    fun columnTilesCollectsTheLeavesDirectlyInsideColumnSplits() {
        val tiles = columnTiles(classicLogViewLayout(LogViewLegacySizes()))
        assertTrue(LogViewSectionId.TAGS in tiles && LogViewSectionId.NOTES in tiles && LogViewSectionId.VIDEO in tiles)
        assertTrue(LogViewSectionId.LOG in tiles, "Original over Log is a column too")
        val flat = TileSplit(TileAxis.ROW, listOf(TileLeaf(LogViewSectionId.LOG), TileLeaf(LogViewSectionId.NOTES)))
        assertEquals(emptySet(), columnTiles(flat))
    }

    @Test
    fun splitterResetSizesAreTheLegacyColumnWidths() {
        val tree = classicLogViewLayout(LogViewLegacySizes(filterPanelWidth = 300f, annotationPanelWidth = 500f)) as TileSplit
        assertEquals(220f, logViewDefaultDp(tree.children.first()))
        assertEquals(360f, logViewDefaultDp(tree.children.last()))
        assertNull(logViewDefaultDp(tree.children[1]), "the weighted middle column equalizes instead")
        val levelLeaf = (tree.children.first() as TileSplit).children.first { (it as? TileLeaf)?.section == LogViewSectionId.LOG_LEVEL }
        assertEquals(80f, logViewDefaultDp(levelLeaf))
    }
}
