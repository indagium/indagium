package com.indagium

import com.indagium.model.Filter
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogViewPresetId
import com.indagium.model.LogViewSectionId
import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import com.indagium.model.ToolbarPlacement
import com.indagium.ui.AppState
import com.indagium.ui.LogViewLayoutPreset
import com.indagium.ui.LogViewLegacySizes
import com.indagium.ui.LogViewPanels
import com.indagium.ui.applyCustomLogViewLayout
import com.indagium.ui.applyLogViewPreset
import com.indagium.ui.captureCustomLogViewLayout
import com.indagium.ui.classicLogViewLayout
import com.indagium.ui.columnTiles
import com.indagium.ui.commitLogViewLayout
import com.indagium.ui.currentLogViewLayout
import com.indagium.ui.hideLogViewTile
import com.indagium.ui.isLogViewTileCollapsed
import com.indagium.ui.lastLogViewResetLabel
import com.indagium.ui.legacyLogViewSizes
import com.indagium.ui.logViewDefaultDp
import com.indagium.ui.logViewLayoutModified
import com.indagium.ui.logViewPresetActive
import com.indagium.ui.logViewTileContext
import com.indagium.ui.logViewTileVisible
import com.indagium.ui.matchesCustomLogViewLayout
import com.indagium.ui.mkTab
import com.indagium.ui.resetLogViewLayout
import com.indagium.ui.revealFilterTiles
import com.indagium.ui.settingsFromJson
import com.indagium.ui.settingsJson
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
        assertEquals(
            setOf(LogViewSectionId.DIAGRAMS, LogViewSectionId.VIDEO, LogViewSectionId.NOTES, LogViewSectionId.AI),
            state.settings.logViewHidden.toSet(),
        )
        assertFalse(visible(LogViewSectionId.NOTES))
        assertFalse(visible(LogViewSectionId.AI))
        assertFalse(visible(LogViewSectionId.VIDEO))
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
        assertFalse(state.logViewLayoutModified(), "applying a preset leaves the layout unmodified")
    }

    @Test
    fun customSnapshotRestoresItsOwnToolbarMembershipAndFilterBarState() {
        state.updateSettings { it.copy(toolbarPlacement = ToolbarPlacement.IN_LAYOUT) }
        state.updateFilterBarVisible(true)
        val snapshot = state.captureCustomLogViewLayout("Toolbar and filter bar")
        assertTrue(LogViewSectionId.TOOLBAR in tileLeafSections(snapshot.tree))

        state.updateSettings { it.copy(toolbarPlacement = ToolbarPlacement.WITH_TABS_END) }
        state.updateFilterBarVisible(false)
        state.applyCustomLogViewLayout(snapshot)

        assertTrue(LogViewSectionId.TOOLBAR in tileLeafSections(assertNotNull(state.settings.logViewLayout)))
        assertTrue(state.filterBarVisible)
    }

    @Test
    fun savedSnapshotEqualityIncludesLayoutStateAndProjectsTheToolbarToCurrentChrome() {
        state.updateSettings { it.copy(toolbarPlacement = ToolbarPlacement.IN_LAYOUT) }
        state.filterPanelWidth = 335f
        state.annotationPanelWidth = 425f
        state.rightSidebarSplit = 0.31f
        state.updateFilterBarVisible(true)
        state.hideLogViewTile(LogViewSectionId.NOTES)
        state.toggleLogViewTileCollapsed(LogViewSectionId.DIAGRAMS)
        state.toggleLogViewTilesLocked()
        val saved = state.captureCustomLogViewLayout("Review")
        assertTrue(state.matchesCustomLogViewLayout(saved))

        assertTrue(state.matchesCustomLogViewLayout(saved.copy(hidden = saved.hidden.reversed(), collapsed = saved.collapsed.reversed())))
        assertTrue(state.matchesCustomLogViewLayout(saved.copy(filterBarVisible = null)), "legacy snapshots leave the current filter-bar state alone")

        fun resizeTags(node: TileNode<LogViewSectionId>): TileNode<LogViewSectionId> = when (node) {
            is TileLeaf -> if (node.section == LogViewSectionId.TAGS) node.copy(sizeDp = 260f) else node
            is TileSplit -> node.copy(children = node.children.map(::resizeTags))
        }
        assertFalse(state.matchesCustomLogViewLayout(saved.copy(tree = resizeTags(saved.tree))), "tree sizing is part of the snapshot")
        assertFalse(state.matchesCustomLogViewLayout(saved.copy(collapsed = saved.collapsed + LogViewSectionId.DIAGRAMS)))

        state.updateSettings { it.copy(toolbarPlacement = ToolbarPlacement.WITH_TABS_END) }
        assertTrue(state.matchesCustomLogViewLayout(saved), "the saved toolbar is compared after projection to the current chrome")
        assertFalse(state.matchesCustomLogViewLayout(saved.copy(filterPanelWidth = saved.filterPanelWidth + 1f)))
        assertFalse(state.matchesCustomLogViewLayout(saved.copy(rightSidebarSplit = 0.5f)))
        assertFalse(state.matchesCustomLogViewLayout(saved.copy(tilesLocked = !saved.tilesLocked)))
        assertFalse(state.matchesCustomLogViewLayout(saved.copy(filterVisible = !saved.filterVisible)))

        state.updateFilterBarVisible(false)
        assertFalse(state.matchesCustomLogViewLayout(saved), "filter-bar presentation is part of the snapshot")
        state.updateFilterBarVisible(true)
        state.showLogViewTile(LogViewSectionId.NOTES)
        assertFalse(state.matchesCustomLogViewLayout(saved), "hidden tiles are part of the snapshot")
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
    fun analysisTurnsOnTheNotesGroupForItsArtifactColumn() {
        state.updateAnnotationVisible(false)
        state.applyLogViewPreset(LogViewLayoutPreset.ANALYSIS)

        assertTrue(state.filterVisible)
        assertTrue(state.annotationVisible)
        assertTrue(visible(LogViewSectionId.NOTES))
        assertTrue(visible(LogViewSectionId.SEQUENCES))
        assertTrue(visible(LogViewSectionId.ISSUES))
    }

    @Test
    fun theFilterBarPresetIsLogFocusWithTheBarOnAndSurvivesASettingsRoundTrip() {
        state.applyLogViewPreset(LogViewLayoutPreset.FILTER_BAR)

        assertTrue(state.filterBarVisible)
        assertFalse(state.filterVisible, "the filter sidebar is off, like Log focus")
        assertEquals(LogViewLayoutPreset.LOG_FOCUS.hidden, LogViewLayoutPreset.FILTER_BAR.hidden)
        assertEquals(LogViewLayoutPreset.FILTER_BAR.id, state.settings.lastLogViewPreset)
        assertEquals(LogViewPresetId.FILTER_BAR, LogViewLayoutPreset.of(LogViewPresetId.FILTER_BAR).id)
        assertTrue(state.logViewPresetActive(LogViewLayoutPreset.FILTER_BAR))
        assertFalse(state.logViewPresetActive(LogViewLayoutPreset.LOG_FOCUS))
        assertFalse(state.logViewPresetActive(LogViewLayoutPreset.CLASSIC))
        assertFalse(state.logViewLayoutModified(), "applying a preset leaves the layout unmodified")

        val restored = assertNotNull(settingsFromJson(state.settings.settingsJson()))
        assertEquals(LogViewPresetId.FILTER_BAR, restored.lastLogViewPreset)
        assertEquals(state.settings.logViewHidden.toSet(), restored.logViewHidden.toSet())
    }

    @Test
    fun theFilterBarFlagTellsFilterBarAndLogFocusApart() {
        state.applyLogViewPreset(LogViewLayoutPreset.LOG_FOCUS)
        assertFalse(state.filterBarVisible, "Log focus has no filter bar")
        assertTrue(state.logViewPresetActive(LogViewLayoutPreset.LOG_FOCUS))
        assertFalse(state.logViewPresetActive(LogViewLayoutPreset.FILTER_BAR))

        // The bar alone turns Log focus into neither preset (Filter bar also shows Notes); Notes as well makes it Filter bar.
        state.updateFilterBarVisible(true)
        assertFalse(state.logViewPresetActive(LogViewLayoutPreset.LOG_FOCUS))
        assertFalse(state.logViewPresetActive(LogViewLayoutPreset.FILTER_BAR))
        state.updateAnnotationVisible(true)
        assertTrue(state.logViewPresetActive(LogViewLayoutPreset.FILTER_BAR))
        assertFalse(state.logViewPresetActive(LogViewLayoutPreset.LOG_FOCUS))
    }

    @Test
    fun everyPresetSetsItsFiveFlagsFromOnePlace() {
        // filter sidebar, filter bar, Notes, Video, AI
        val table = mapOf(
            LogViewLayoutPreset.CLASSIC to LogViewPanels(filter = true, filterBar = false, notes = true, video = true, ai = false),
            LogViewLayoutPreset.LOG_FOCUS to LogViewPanels(filter = false, filterBar = false, notes = false, video = false, ai = false),
            LogViewLayoutPreset.FILTER_BAR to LogViewPanels(filter = false, filterBar = true, notes = true, video = false, ai = false),
            LogViewLayoutPreset.FILTERING to LogViewPanels(filter = true, filterBar = false, notes = false, video = false, ai = false),
            LogViewLayoutPreset.ANALYSIS to LogViewPanels(filter = true, filterBar = false, notes = true, video = false, ai = false),
        )
        assertEquals(table.keys, LogViewLayoutPreset.forScope(com.indagium.model.LogViewScope.SINGLE).toSet())
        for ((preset, expected) in table) {
            assertEquals(expected, preset.panels, preset.label)
            // From the opposite state of every flag, so each one has to be written.
            state.updateFilterVisible(!expected.filter)
            state.updateFilterBarVisible(!expected.filterBar)
            state.updateAnnotationVisible(!expected.notes)
            state.updateVideoPanelVisible(!expected.video)
            state.updateAiPanelVisible(!expected.ai)
            state.applyLogViewPreset(preset)
            assertEquals(
                expected,
                LogViewPanels(state.filterVisible, state.filterBarVisible, state.annotationVisible, state.videoPanelVisible, state.aiPanelVisible),
                preset.label,
            )
            assertTrue(state.logViewPresetActive(preset), "${preset.label} is active right after applying it")
        }
        assertNull(LogViewLayoutPreset.REVIEW.panels, "the legacy presets leave Notes, Video and AI alone")
        assertNull(LogViewLayoutPreset.FILTERS_ON_TOP.panels)
    }

    @Test
    fun togglingAPanelMakesThePresetModifiedAndRevertRestoresIt() {
        for (preset in LogViewLayoutPreset.forScope(com.indagium.model.LogViewScope.SINGLE)) {
            state.applyLogViewPreset(preset)
            assertTrue(state.logViewPresetActive(preset), preset.label)

            state.updateAiPanelVisible(!state.aiPanelVisible)
            assertFalse(state.logViewPresetActive(preset), "${preset.label}: an AI toggle is a modification")
            state.resetLogViewLayout()
            assertTrue(state.logViewPresetActive(preset), "${preset.label}: Revert restores the flags")

            state.updateVideoPanelVisible(!state.videoPanelVisible)
            assertFalse(state.logViewPresetActive(preset), "${preset.label}: a Video toggle is a modification")
            state.resetLogViewLayout()
            assertTrue(state.logViewPresetActive(preset))
        }
    }

    @Test
    fun everyOtherSinglePresetSwitchesTheFilterBarOff() {
        for (preset in LogViewLayoutPreset.forScope(com.indagium.model.LogViewScope.SINGLE) - LogViewLayoutPreset.FILTER_BAR) {
            state.applyLogViewPreset(LogViewLayoutPreset.FILTER_BAR)
            assertTrue(state.filterBarVisible)
            state.applyLogViewPreset(preset)
            assertFalse(state.filterBarVisible, preset.label)
        }
        assertEquals(
            listOf("Classic", "Log focus", "Filter bar", "Filtering", "Analysis"),
            LogViewLayoutPreset.forScope(com.indagium.model.LogViewScope.SINGLE).map { it.label },
        )
    }

    @Test
    fun resettingToTheFilterBarPresetBringsTheBarBack() {
        state.applyLogViewPreset(LogViewLayoutPreset.FILTER_BAR)
        state.updateFilterBarVisible(false)
        state.hideLogViewTile(LogViewSectionId.NOTES)
        state.resetLogViewLayout()
        assertTrue(state.filterBarVisible)
        assertTrue(state.logViewPresetActive(LogViewLayoutPreset.FILTER_BAR))
        assertEquals("Filter bar", state.lastLogViewResetLabel())
    }

    @Test
    fun renamingASavedLayoutKeepsItsIdAndMakesTheNameUnique() {
        val first = state.saveCurrentAsLogViewLayout("First")
        val second = state.saveCurrentAsLogViewLayout("Second")

        state.renameSavedLogViewLayout(second.id, "  Renamed  ")
        assertEquals(listOf("First", "Renamed"), state.settings.customLogViewLayouts.map { it.name })
        assertEquals(listOf(first.id, second.id), state.settings.customLogViewLayouts.map { it.id })

        state.renameSavedLogViewLayout(second.id, "first")
        assertEquals("first (2)", state.settings.customLogViewLayouts.last().name, "another layout's name is de-duplicated")
        state.renameSavedLogViewLayout(second.id, "first (2)")
        assertEquals("first (2)", state.settings.customLogViewLayouts.last().name, "its own name is not a clash")
        state.renameSavedLogViewLayout(second.id, "   ")
        state.renameSavedLogViewLayout("custom-log-view-gone", "X")
        assertEquals(listOf("First", "first (2)"), state.settings.customLogViewLayouts.map { it.name })
        assertTrue(state.matchesCustomLogViewLayout(state.settings.customLogViewLayouts.last()), "renaming leaves the layout's content alone")
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
        state.applyLogViewPreset(LogViewLayoutPreset.ANALYSIS)
        state.hideLogViewTile(LogViewSectionId.AI)
        state.commitLogViewLayout(TileLeaf(LogViewSectionId.LOG))
        assertFalse(state.logViewPresetActive(LogViewLayoutPreset.ANALYSIS))
        state.resetLogViewLayout()
        assertTrue(state.logViewPresetActive(LogViewLayoutPreset.ANALYSIS))
        assertEquals(LogViewLayoutPreset.ANALYSIS.id, state.settings.lastLogViewPreset)
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
    fun theLayoutIsModifiedOnlyWhenItNoLongerMatchesItsPresetOrSavedLayout() {
        assertFalse(state.logViewLayoutModified(), "the default layout matches Classic")
        state.hideLogViewTile(LogViewSectionId.NOTES)
        assertTrue(state.logViewLayoutModified(), "hiding a tile the preset shows modifies it")
        state.showLogViewTile(LogViewSectionId.NOTES)
        assertFalse(state.logViewLayoutModified(), "putting it back matches again")

        val saved = state.saveCurrentAsLogViewLayout("Mine")
        assertFalse(state.logViewLayoutModified(), "right after saving the layout matches the saved snapshot")
        state.hideLogViewTile(LogViewSectionId.NOTES)
        assertTrue(state.logViewLayoutModified(), "a change against the selected saved layout is a modification")
        state.updateSavedLogViewLayoutFromCurrent(saved.id)
        assertFalse(state.logViewLayoutModified(), "updating the saved layout clears it")
        state.resetLogViewLayout()
        assertFalse(state.logViewLayoutModified())
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
