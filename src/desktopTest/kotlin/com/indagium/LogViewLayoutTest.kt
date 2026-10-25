package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.LogViewPresetId
import com.indagium.model.LogViewScope
import com.indagium.model.LogViewSectionId
import com.indagium.model.LogViewSectionId.AI
import com.indagium.model.LogViewSectionId.DIAGRAMS
import com.indagium.model.LogViewSectionId.HIGHLIGHTERS
import com.indagium.model.LogViewSectionId.ISSUES
import com.indagium.model.LogViewSectionId.LOG
import com.indagium.model.LogViewSectionId.LOG_A
import com.indagium.model.LogViewSectionId.LOG_B
import com.indagium.model.LogViewSectionId.LOG_COMPOSITION
import com.indagium.model.LogViewSectionId.LOG_LEVEL
import com.indagium.model.LogViewSectionId.MESSAGE_RULES
import com.indagium.model.LogViewSectionId.NOTES
import com.indagium.model.LogViewSectionId.ORIGINAL
import com.indagium.model.LogViewSectionId.ORIGINAL_A
import com.indagium.model.LogViewSectionId.ORIGINAL_B
import com.indagium.model.LogViewSectionId.SAVED_FILTERS
import com.indagium.model.LogViewSectionId.SEQUENCES
import com.indagium.model.LogViewSectionId.TAGS
import com.indagium.model.LogViewSectionId.TOOLBAR
import com.indagium.model.LogViewSectionId.VIDEO
import com.indagium.model.LogViewTileGroup
import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit
import com.indagium.model.ToolbarPlacement
import com.indagium.ui.LOG_VIEW_FILTER_TILES
import com.indagium.ui.LOG_VIEW_TILE_SPEC
import com.indagium.ui.LogViewFocusPanel
import com.indagium.ui.LogViewLayoutPreset
import com.indagium.ui.LogViewLegacySizes
import com.indagium.ui.LogViewTileContext
import com.indagium.ui.TOOLBAR_TILE_COLUMN_DP
import com.indagium.ui.TOOLBAR_TILE_ROW_DP
import com.indagium.ui.TileDropTarget
import com.indagium.ui.TileEdge
import com.indagium.ui.classicLogViewLayout
import com.indagium.ui.compareColumnsLayout
import com.indagium.ui.compareGridLayout
import com.indagium.ui.effectiveCompareLayout
import com.indagium.ui.effectiveLogViewLayout
import com.indagium.ui.isLogTile
import com.indagium.ui.layoutMenuPanels
import com.indagium.ui.logViewDropLabel
import com.indagium.ui.logViewFocusOrder
import com.indagium.ui.logViewHiddenBadgeCount
import com.indagium.ui.logViewPresetIsActive
import com.indagium.ui.logViewTileApplicable
import com.indagium.ui.logViewTileTitle
import com.indagium.ui.logViewTileVisible
import com.indagium.ui.neverHidden
import com.indagium.ui.pinToolbarTile
import com.indagium.ui.projectTileLayout
import com.indagium.ui.removeTile
import com.indagium.ui.sanitizeLogViewLayoutState
import com.indagium.ui.tileGeometry
import com.indagium.ui.tileLayoutShapeEquals
import com.indagium.ui.tileLeafSections
import com.indagium.ui.withCompareLayout
import com.indagium.ui.withComparePreset
import com.indagium.ui.withCompareTileHidden
import com.indagium.ui.withLogViewLayout
import com.indagium.ui.withLogViewPreset
import com.indagium.ui.withLogViewTileCollapsed
import com.indagium.ui.withLogViewTileHidden
import com.indagium.ui.withLogViewTilesLocked
import com.indagium.ui.withToolbarDroppedInLayout
import com.indagium.ui.withToolbarPlacement
import com.indagium.ui.withToolbarTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LogViewLayoutTest {
    private val sizes = LogViewLegacySizes(filterPanelWidth = 300f, annotationPanelWidth = 420f, rightSidebarSplit = 0.3f, compareSplit = 0.4f)

    // The tiles of a stored single-view tree: every single-view tile but the toolbar, which is in it only while the toolbar is IN_LAYOUT.
    private val singleIds = LogViewSectionId.entries.filter { it.scope != LogViewScope.COMPARE && it != LogViewSectionId.TOOLBAR }.toSet()
    private val compareIds = LogViewSectionId.entries.filter { it.scope != LogViewScope.SINGLE }.toSet()

    private fun ctx(
        compare: Boolean = false,
        showUnfiltered: Boolean = false,
        left: Boolean = false,
        right: Boolean = false,
        filterBar: Boolean = false,
        tagsMode: Boolean = true,
        video: Boolean = true,
        groups: Set<LogViewTileGroup> = LogViewTileGroup.entries.toSet(),
    ) = LogViewTileContext(compare, showUnfiltered, left, right, filterBar, tagsMode, video) { it in groups }

    private fun children(node: TileNode<LogViewSectionId>): List<TileNode<LogViewSectionId>> = (node as TileSplit).children

    private fun leafIds(node: TileNode<LogViewSectionId>): List<LogViewSectionId> = tileLeafSections(node)

    // ---- legacy derivation -------------------------------------------------------------------------------------

    @Test
    fun classicLayoutPinsTheSideColumnsToTheLegacyWidths() {
        val root = classicLogViewLayout(sizes) as TileSplit
        assertEquals(TileAxis.ROW, root.axis)
        assertEquals(3, root.children.size)
        val (filters, middle, right) = root.children
        assertEquals(300f, filters.sizeDp)
        assertNull(middle.sizeDp)
        assertEquals(420f, right.sizeDp)
        assertEquals(LOG_VIEW_FILTER_TILES, leafIds(filters))
        assertEquals(listOf(ORIGINAL, LOG), leafIds(middle))
        assertEquals(listOf(VIDEO, NOTES, AI, DIAGRAMS), leafIds(right))
        // Tags, Message rules and Saved filters start taller; Log level is pinned to its natural height.
        val byId = children(filters).associate { (it as TileLeaf).section to it }
        assertEquals(2.2f, byId.getValue(TAGS).weight)
        assertEquals(1.4f, byId.getValue(MESSAGE_RULES).weight)
        assertEquals(1.2f, byId.getValue(SAVED_FILTERS).weight)
        assertEquals(1f, byId.getValue(HIGHLIGHTERS).weight)
        assertEquals(80f, byId.getValue(LOG_LEVEL).sizeDp)
        assertNull(byId.getValue(TAGS).sizeDp)
    }

    @Test
    fun notesAndAiWeightsComeFromTheRightSidebarSplit() {
        val right = children(classicLogViewLayout(sizes))[2]
        val byId = children(right).associate { (it as TileLeaf).section to it.weight }
        assertEquals(0.3f, byId.getValue(NOTES), 1e-6f)
        assertEquals(0.7f, byId.getValue(AI), 1e-6f)
    }

    @Test
    fun originalAndLogShareTheMiddleColumnEqually() {
        val middle = children(classicLogViewLayout(sizes))[1]
        assertEquals(listOf(1f, 1f), children(middle).map { it.weight })
    }

    @Test
    fun legacySizesThatAreNotUsableFallBackToDefaults() {
        val layout = classicLogViewLayout(LogViewLegacySizes(Float.NaN, -5f, Float.POSITIVE_INFINITY, 0f))
        val root = layout as TileSplit
        assertEquals(220f, root.children[0].sizeDp)
        assertEquals(360f, root.children[2].sizeDp)
        val notes = children(root.children[2]).first { (it as TileLeaf).section == NOTES }
        assertEquals(0.5f, notes.weight)
    }

    @Test
    fun compareGridWeightsABFromCompareSplit() {
        val root = compareGridLayout(sizes) as TileSplit
        assertEquals(4, root.children.size)
        assertEquals(300f, root.children[0].sizeDp)
        assertEquals(listOf(ORIGINAL_A, LOG_A), leafIds(root.children[1]))
        assertEquals(listOf(ORIGINAL_B, LOG_B), leafIds(root.children[2]))
        assertEquals(0.4f, root.children[1].weight, 1e-6f)
        assertEquals(0.6f, root.children[2].weight, 1e-6f)
        assertEquals(420f, root.children[3].sizeDp)
    }

    @Test
    fun compareColumnsIsTheRowMajorVariant() {
        val root = compareColumnsLayout(sizes) as TileSplit
        val middle = root.children[1] as TileSplit
        assertEquals(TileAxis.COLUMN, middle.axis)
        assertEquals(listOf(ORIGINAL_A, ORIGINAL_B), leafIds(middle.children[0]))
        assertEquals(listOf(LOG_A, LOG_B), leafIds(middle.children[1]))
        assertEquals(0.4f, children(middle.children[1])[0].weight, 1e-6f)
    }

    @Test
    fun effectiveLayoutsDeriveFromLegacySizesUntilSomethingIsStored() {
        val fresh = AppSettings()
        assertEquals(classicLogViewLayout(sizes), effectiveLogViewLayout(fresh, sizes))
        assertEquals(compareGridLayout(sizes), effectiveCompareLayout(fresh, sizes))
        val stored = TileSplit(TileAxis.ROW, listOf(TileLeaf(LOG), TileLeaf(NOTES)))
        assertSame(stored, effectiveLogViewLayout(fresh.copy(logViewLayout = stored), sizes))
        assertSame(stored, effectiveCompareLayout(fresh.copy(compareLayout = stored), sizes))
    }

    // ---- applicability and visibility --------------------------------------------------------------------------

    @Test
    fun applicabilityMatrix() {
        val single = ctx()
        assertFalse(logViewTileApplicable(ORIGINAL, single))
        assertTrue(logViewTileApplicable(ORIGINAL, ctx(showUnfiltered = true)))
        assertTrue(logViewTileApplicable(LOG, single))
        assertFalse(logViewTileApplicable(LOG_A, single))
        assertFalse(logViewTileApplicable(ORIGINAL_A, ctx(left = true)))
        assertFalse(logViewTileApplicable(VIDEO, ctx(video = false)))
        assertTrue(logViewTileApplicable(VIDEO, single))
        assertFalse(logViewTileApplicable(TAGS, ctx(filterBar = true)))
        assertTrue(logViewTileApplicable(TAGS, single))
        assertFalse(logViewTileApplicable(MESSAGE_RULES, ctx(filterBar = true)))
        assertFalse(logViewTileApplicable(MESSAGE_RULES, ctx(tagsMode = false)))
        assertTrue(logViewTileApplicable(MESSAGE_RULES, single))
        assertTrue(logViewTileApplicable(HIGHLIGHTERS, ctx(filterBar = true, tagsMode = false)))

        val compare = ctx(compare = true, left = true, right = false)
        assertFalse(logViewTileApplicable(LOG, compare))
        assertFalse(logViewTileApplicable(ORIGINAL, ctx(compare = true, showUnfiltered = true)))
        assertTrue(logViewTileApplicable(LOG_A, compare))
        assertTrue(logViewTileApplicable(LOG_B, compare))
        assertTrue(logViewTileApplicable(ORIGINAL_A, compare))
        assertFalse(logViewTileApplicable(ORIGINAL_B, compare))
        assertTrue(logViewTileApplicable(ORIGINAL_B, ctx(compare = true, right = true)))
        assertTrue(logViewTileApplicable(NOTES, compare))
    }

    @Test
    fun visibilityCombinesApplicabilityGroupsAndTheHiddenList() {
        val on = ctx()
        assertTrue(logViewTileVisible(TAGS, on, emptyList()))
        assertFalse(logViewTileVisible(TAGS, on, listOf(TAGS)))
        assertFalse(logViewTileVisible(TAGS, ctx(groups = setOf(LogViewTileGroup.NOTES)), emptyList()))
        assertFalse(logViewTileVisible(NOTES, ctx(groups = setOf(LogViewTileGroup.FILTERS)), emptyList()))
        assertTrue(logViewTileVisible(NOTES, ctx(groups = setOf(LogViewTileGroup.NOTES)), emptyList()))
        assertFalse(logViewTileVisible(DIAGRAMS, ctx(groups = setOf(LogViewTileGroup.NOTES)), listOf(DIAGRAMS)))
        assertFalse(logViewTileVisible(AI, ctx(groups = setOf(LogViewTileGroup.FILTERS)), emptyList()))
        assertFalse(logViewTileVisible(VIDEO, ctx(video = false), emptyList()))
        assertFalse(logViewTileVisible(ORIGINAL, on, emptyList()))
        assertTrue(logViewTileVisible(ORIGINAL, ctx(showUnfiltered = true, groups = emptySet()), emptyList()))
    }

    @Test
    fun theLogTilesAreAlwaysVisibleAndNeverHideable() {
        val none = ctx(groups = emptySet())
        assertTrue(logViewTileVisible(LOG, none, listOf(LOG)))
        assertTrue(logViewTileVisible(LOG_A, ctx(compare = true, groups = emptySet()), listOf(LOG_A, LOG_B)))
        val s = AppSettings()
        assertSame(s, s.withLogViewTileHidden(LOG, true))
        assertSame(s, s.withCompareTileHidden(LOG_A, true))
        val state = sanitizeLogViewLayoutState(classicLogViewLayout(sizes), listOf(LOG, TAGS), LogViewScope.SINGLE)
        assertEquals(listOf(TAGS), state.second)
    }

    @Test
    fun badgeCountsApplicableTilesHiddenViaTheListOnly() {
        val hidden = listOf(DIAGRAMS, VIDEO, TAGS, ORIGINAL, LOG_A, ORIGINAL_A)
        // Single: Diagrams, Video and Tags count; Original is not shown, LOG_A / ORIGINAL_A belong to compare.
        assertEquals(3, logViewHiddenBadgeCount(ctx(), hidden, LogViewScope.SINGLE))
        assertEquals(2, logViewHiddenBadgeCount(ctx(video = false), hidden, LogViewScope.SINGLE))
        assertEquals(4, logViewHiddenBadgeCount(ctx(showUnfiltered = true), hidden, LogViewScope.SINGLE))
        assertEquals(1, logViewHiddenBadgeCount(ctx(filterBar = true), listOf(TAGS, DIAGRAMS), LogViewScope.SINGLE))
        // Group switches never change the count: the tiles are not in the hidden list.
        assertEquals(0, logViewHiddenBadgeCount(ctx(groups = emptySet()), emptyList(), LogViewScope.SINGLE))
        assertEquals(1, logViewHiddenBadgeCount(ctx(groups = emptySet()), listOf(AI), LogViewScope.SINGLE))
        // Compare: ORIGINAL_A counts only when the left pane shows its unfiltered log.
        val compare = ctx(compare = true, left = true)
        assertEquals(4, logViewHiddenBadgeCount(compare, hidden, LogViewScope.COMPARE))
        assertEquals(0, logViewHiddenBadgeCount(compare, emptyList(), LogViewScope.COMPARE))
    }

    // ---- sanitizer -----------------------------------------------------------------------------------------------

    private fun leaf(id: LogViewSectionId, weight: Float = 1f, dp: Float? = null) = TileLeaf(id, weight, dp)

    private fun row(vararg c: TileNode<LogViewSectionId>) = TileSplit(TileAxis.ROW, c.toList())

    private fun column(vararg c: TileNode<LogViewSectionId>) = TileSplit(TileAxis.COLUMN, c.toList())

    private fun assertComplete(tree: TileNode<LogViewSectionId>, scope: LogViewScope) {
        val ids = leafIds(tree)
        assertEquals(ids.size, ids.toSet().size, "duplicate tile in $tree")
        assertEquals(if (scope == LogViewScope.COMPARE) compareIds else singleIds, ids.toSet())
    }

    @Test
    fun firstDuplicateWinsAndTilesOfTheOtherScopeAreDropped() {
        val tree = row(leaf(LOG, 2f), leaf(NOTES), column(leaf(LOG_A), leaf(LOG), leaf(AI), leaf(ORIGINAL_B)))
        val (sanitized, _) = sanitizeLogViewLayoutState(tree, emptyList(), LogViewScope.SINGLE)
        assertComplete(sanitized, LogViewScope.SINGLE)

        val compareTree = row(leaf(LOG_A), leaf(LOG), leaf(ORIGINAL), leaf(LOG_B))
        assertComplete(sanitizeLogViewLayoutState(compareTree, emptyList(), LogViewScope.COMPARE).first, LogViewScope.COMPARE)
    }

    @Test
    fun missingTilesAreInsertedAtTheirDefaultAnchors() {
        val (tree, hidden) = sanitizeLogViewLayoutState(row(leaf(LOG), leaf(NOTES)), listOf(DIAGRAMS), LogViewScope.SINGLE)
        assertComplete(tree, LogViewScope.SINGLE)
        assertEquals(listOf(DIAGRAMS), hidden)
        val geometry = tileGeometry(tree, 1600f, 2400f, LOG_VIEW_TILE_SPEC)
        val log = geometry.cardOf(LOG)!!
        val original = geometry.cardOf(ORIGINAL)!!
        assertEquals(log.x, original.x)
        assertTrue(original.bottom <= log.y, "Original sits above Log")
        val notes = geometry.cardOf(NOTES)!!
        val video = geometry.cardOf(VIDEO)!!
        val ai = geometry.cardOf(AI)!!
        val diagrams = geometry.cardOf(DIAGRAMS)!!
        assertEquals(notes.x, video.x)
        assertTrue(video.bottom <= notes.y, "Video above Notes")
        assertTrue(ai.y >= notes.bottom, "AI below Notes")
        assertTrue(diagrams.y >= notes.bottom, "Diagram library below Notes")
    }

    @Test
    fun missingFilterTilesFollowTheirPredecessorsAndTagsGoesAboveTheFirst() {
        val (tree, _) = sanitizeLogViewLayoutState(row(leaf(SEQUENCES), leaf(LOG), leaf(NOTES)), emptyList(), LogViewScope.SINGLE)
        assertComplete(tree, LogViewScope.SINGLE)
        val geometry = tileGeometry(tree, 1600f, 4000f, LOG_VIEW_TILE_SPEC)
        val ys = LOG_VIEW_FILTER_TILES.map { geometry.cardOf(it)!! }
        assertTrue(ys.map { it.x }.toSet().size == 1, "all filter tiles share one column")
        assertEquals(ys.sortedBy { it.y }, ys, "filter tiles keep the filter panel's order")
    }

    @Test
    fun anEmptyOrUnusableTreeFallsBackToTheDefaultPreset() {
        val (empty, hidden) = sanitizeLogViewLayoutState(null, listOf(TAGS), LogViewScope.SINGLE)
        assertComplete(empty, LogViewScope.SINGLE)
        assertEquals(listOf(TAGS), hidden)
        assertTrue(tileLayoutShapeEquals(classicLogViewLayout(LogViewLegacySizes()), empty))
        // A tree holding only tiles of the other scope is as good as none.
        val wrong = sanitizeLogViewLayoutState(row(leaf(LOG_A), leaf(LOG_B)), emptyList(), LogViewScope.SINGLE).first
        assertTrue(tileLayoutShapeEquals(classicLogViewLayout(LogViewLegacySizes()), wrong))
        val compare = sanitizeLogViewLayoutState(null, emptyList(), LogViewScope.COMPARE).first
        assertTrue(tileLayoutShapeEquals(compareGridLayout(LogViewLegacySizes()), compare))
    }

    @Test
    fun pinnedSizesAreClampedTo80Through2000AndWeightsSanitized() {
        val tree = row(leaf(LOG, Float.NaN), column(leaf(NOTES), leaf(AI), TileLeaf(TAGS, 1f, 5f)).copy(sizeDp = 5000f), leaf(DIAGRAMS, 1f, 10f))
        val (sanitized, _) = sanitizeLogViewLayoutState(tree, emptyList(), LogViewScope.SINGLE)

        fun nodes(n: TileNode<LogViewSectionId>): List<TileNode<LogViewSectionId>> =
            listOf(n) + ((n as? TileSplit)?.children?.flatMap { nodes(it) } ?: emptyList())
        val all = nodes(sanitized)
        assertTrue(all.all { it.weight.isFinite() && it.weight > 0f })
        assertTrue(all.mapNotNull { it.sizeDp }.all { it in 80f..2000f })
        val diagrams = all.first { it is TileLeaf && it.section == DIAGRAMS }
        assertEquals(80f, diagrams.sizeDp)
        assertTrue(all.any { it.sizeDp == 2000f })
    }

    @Test
    fun hiddenTilesStayInTheTreeAndTheListIsCleaned() {
        val (tree, hidden) = sanitizeLogViewLayoutState(
            classicLogViewLayout(sizes),
            listOf(AI, AI, LOG, LOG_A, ORIGINAL_B, TAGS),
            LogViewScope.SINGLE,
        )
        assertEquals(listOf(AI, TAGS), hidden)
        assertTrue(AI in leafIds(tree))
    }

    @Test
    fun sanitizingIsIdempotent() {
        val messy = row(leaf(LOG), column(leaf(AI), leaf(LOG_A), leaf(AI)), leaf(TAGS, 3f, 10f))
        val once = sanitizeLogViewLayoutState(messy, listOf(DIAGRAMS, LOG), LogViewScope.SINGLE)
        assertEquals(once, sanitizeLogViewLayoutState(once.first, once.second, LogViewScope.SINGLE))
    }

    // ---- presets -------------------------------------------------------------------------------------------------

    @Test
    fun everyPresetHoldsEveryTileOfItsScopeExactlyOnceAndSurvivesSanitizing() {
        for (preset in LogViewLayoutPreset.entries) {
            val layout = preset.layout(sizes)
            val ids = leafIds(layout)
            assertEquals(ids.size, ids.toSet().size, "${preset.label}: a tile appears twice")
            val expected = if (preset.scope == LogViewScope.COMPARE) compareIds else singleIds
            assertEquals(expected, ids.toSet() + preset.hidden, preset.label)
            assertTrue(preset.hidden.none { it.isLogTile }, "${preset.label}: hides a log tile")

            val (sanitized, hidden) = sanitizeLogViewLayoutState(layout, preset.hidden.toList(), preset.scope)
            assertComplete(sanitized, preset.scope)
            assertEquals(preset.hidden, hidden.toSet(), preset.label)

            fun shown(tree: TileNode<LogViewSectionId>) =
                preset.hidden.fold(tree as TileNode<LogViewSectionId>?) { acc, id -> acc?.let { removeTile(it, id) } }
            assertTrue(tileLayoutShapeEquals(shown(sanitized)!!, shown(layout)!!), "${preset.label}: sanitizing changed the arrangement")
        }
    }

    @Test
    fun presetScopesAndMenuGroups() {
        assertEquals(
            listOf(LogViewPresetId.CLASSIC, LogViewPresetId.REVIEW, LogViewPresetId.LOG_FOCUS, LogViewPresetId.FILTERS_ON_TOP),
            LogViewLayoutPreset.forScope(LogViewScope.SINGLE).map { it.id },
        )
        assertEquals(
            listOf(LogViewPresetId.COMPARE_GRID, LogViewPresetId.COMPARE_COLUMNS),
            LogViewLayoutPreset.forScope(LogViewScope.COMPARE).map { it.id },
        )
        LogViewPresetId.entries.forEach { assertEquals(it, LogViewLayoutPreset.of(it).id) }
        // Log focus hides the filter tiles; Review shows the Diagram library.
        assertTrue(LOG_VIEW_FILTER_TILES.all { it in LogViewLayoutPreset.LOG_FOCUS.hidden })
        assertFalse(DIAGRAMS in LogViewLayoutPreset.REVIEW.hidden)
        assertEquals(setOf(DIAGRAMS), LogViewLayoutPreset.CLASSIC.hidden)
    }

    @Test
    fun presetActiveDetectionFollowsTheStoredArrangementAndHiddenSet() {
        var s = AppSettings()
        assertTrue(logViewPresetIsActive(LogViewLayoutPreset.CLASSIC, s, sizes))
        assertFalse(logViewPresetIsActive(LogViewLayoutPreset.REVIEW, s, sizes))
        for (preset in LogViewLayoutPreset.forScope(LogViewScope.SINGLE)) {
            s = s.withLogViewPreset(preset, sizes)
            assertEquals(preset.id, s.lastLogViewPreset)
            assertEquals(preset.hidden, s.logViewHidden.toSet())
            assertComplete(s.logViewLayout!!, LogViewScope.SINGLE)
            LogViewLayoutPreset.forScope(LogViewScope.SINGLE).forEach {
                assertEquals(it == preset, logViewPresetIsActive(it, s, sizes), "${it.label} while ${preset.label} is applied")
            }
        }
        val classic = AppSettings().withLogViewPreset(LogViewLayoutPreset.CLASSIC, sizes)
        assertFalse(logViewPresetIsActive(LogViewLayoutPreset.CLASSIC, classic.withLogViewTileHidden(TAGS, true), sizes))
        assertTrue(logViewPresetIsActive(LogViewLayoutPreset.CLASSIC, classic.withLogViewTileHidden(TAGS, true).withLogViewTileHidden(TAGS, false), sizes))

        var c = AppSettings()
        assertTrue(logViewPresetIsActive(LogViewLayoutPreset.COMPARE_GRID, c, sizes))
        c = c.withComparePreset(LogViewLayoutPreset.COMPARE_COLUMNS, sizes)
        assertEquals(LogViewPresetId.COMPARE_COLUMNS, c.lastComparePreset)
        assertTrue(logViewPresetIsActive(LogViewLayoutPreset.COMPARE_COLUMNS, c, sizes))
        assertFalse(logViewPresetIsActive(LogViewLayoutPreset.COMPARE_GRID, c, sizes))
    }

    @Test
    fun presetsOfTheWrongScopeAreRefused() {
        val s = AppSettings()
        assertSame(s, s.withLogViewPreset(LogViewLayoutPreset.COMPARE_GRID, sizes))
        assertSame(s, s.withComparePreset(LogViewLayoutPreset.CLASSIC, sizes))
    }

    // ---- mutators ------------------------------------------------------------------------------------------------

    @Test
    fun hideShowCollapseAndLockMutators() {
        val s = AppSettings()
        assertEquals(listOf(DIAGRAMS, TAGS), s.withLogViewTileHidden(TAGS, true).logViewHidden)
        assertEquals(listOf(DIAGRAMS), s.withLogViewTileHidden(TAGS, true).withLogViewTileHidden(TAGS, false).logViewHidden)
        assertEquals(listOf(DIAGRAMS, TAGS), s.withLogViewTileHidden(TAGS, true).withLogViewTileHidden(TAGS, true).logViewHidden)
        assertSame(s, s.withLogViewTileHidden(LOG_A, true))
        assertSame(s, s.withCompareTileHidden(ORIGINAL, true))
        assertEquals(listOf(DIAGRAMS, AI), s.withCompareTileHidden(AI, true).compareHidden)
        assertNull(s.withLogViewTileHidden(TAGS, true).logViewLayout)

        assertEquals(listOf(TAGS), s.withLogViewTileCollapsed(TAGS, true).logViewCollapsed)
        assertEquals(emptyList(), s.withLogViewTileCollapsed(TAGS, true).withLogViewTileCollapsed(TAGS, false).logViewCollapsed)
        assertSame(s, s.withLogViewTileCollapsed(LOG, true))
        assertTrue(s.withLogViewTilesLocked(true).logViewTilesLocked)
        assertSame(s, s.withLogViewTilesLocked(false))
    }

    @Test
    fun storingALayoutSanitizesItAgainstTheHiddenList() {
        val s = AppSettings().withLogViewLayout(row(leaf(LOG), leaf(LOG), leaf(NOTES)))
        assertComplete(s.logViewLayout!!, LogViewScope.SINGLE)
        assertEquals(listOf(DIAGRAMS), s.logViewHidden)
        val c = AppSettings().withCompareLayout(row(leaf(LOG_A), leaf(LOG)))
        assertComplete(c.compareLayout!!, LogViewScope.COMPARE)
    }

    // ---- titles, labels, focus order -----------------------------------------------------------------------------

    @Test
    fun titlesAndDropLabels() {
        val expected = listOf(
            "Tags", "Message rules", "Log composition", "Highlighters", "Log level", "Sequences", "Issues", "Saved filters",
            "Log", "Original", "Log A", "Log B", "Original A", "Original B", "Notes", "Diagram library", "AI assistant", "Video", "Toolbar",
        )
        assertEquals(expected, LogViewSectionId.entries.map { logViewTileTitle(it) })
        assertEquals("Below Notes", logViewDropLabel(TileDropTarget.OnSection(NOTES, TileEdge.BOTTOM)))
        assertEquals("Left of Log", logViewDropLabel(TileDropTarget.OnSection(LOG, TileEdge.LEFT)))
        assertEquals("Right of Tags", logViewDropLabel(TileDropTarget.OnSection(TAGS, TileEdge.RIGHT)))
        assertEquals("Above Diagram library", logViewDropLabel(TileDropTarget.OnSection(DIAGRAMS, TileEdge.TOP)))
        assertEquals("Full height", logViewDropLabel(TileDropTarget.OnPage(TileEdge.LEFT)))
        assertEquals("Full width", logViewDropLabel(TileDropTarget.OnPage(TileEdge.BOTTOM)))
    }

    @Test
    fun focusOrderIsReadingOrderWithTheFilterTilesAsOneStop() {
        val all = logViewFocusOrder(classicLogViewLayout(sizes))
        assertEquals(
            listOf(
                LogViewFocusPanel.FILTERS, LogViewFocusPanel.ORIGINAL, LogViewFocusPanel.LOG, LogViewFocusPanel.VIDEO,
                LogViewFocusPanel.NOTES, LogViewFocusPanel.AI, LogViewFocusPanel.DIAGRAMS,
            ),
            all,
        )
        val shown = setOf(TAGS, LOG_LEVEL, LOG, NOTES, AI)
        val projection = projectTileLayout(classicLogViewLayout(sizes)) { it in shown }!!
        assertEquals(
            listOf(LogViewFocusPanel.FILTERS, LogViewFocusPanel.LOG, LogViewFocusPanel.NOTES, LogViewFocusPanel.AI),
            logViewFocusOrder(projection.root),
        )
        val compare = logViewFocusOrder(compareGridLayout(sizes))
        assertEquals(
            listOf(
                LogViewFocusPanel.FILTERS, LogViewFocusPanel.ORIGINAL_A, LogViewFocusPanel.LOG_A, LogViewFocusPanel.ORIGINAL_B,
                LogViewFocusPanel.LOG_B, LogViewFocusPanel.VIDEO, LogViewFocusPanel.NOTES, LogViewFocusPanel.AI, LogViewFocusPanel.DIAGRAMS,
            ),
            compare,
        )
    }

    // ---- geometry ------------------------------------------------------------------------------------------------

    @Test
    fun classicGeometryKeepsTheFilterColumnAtTheLegacyWidth() {
        val legacy = LogViewLegacySizes(filterPanelWidth = 220f, annotationPanelWidth = 360f, rightSidebarSplit = 0.5f)
        val visible = LogViewSectionId.entries.toSet() - DIAGRAMS - VIDEO - ORIGINAL
        val projection = projectTileLayout(classicLogViewLayout(legacy)) { it in visible }!!
        val geometry = tileGeometry(projection.root, 1440f, 860f, LOG_VIEW_TILE_SPEC)
        assertEquals((visible intersect singleIds).size, geometry.cards.size, "one card per visible tile")
        for (id in LOG_VIEW_FILTER_TILES) assertEquals(220f, geometry.cardOf(id)!!.w, 0.01f, "$id")
        val log = geometry.cardOf(LOG)!!
        assertEquals(220f + 8f, log.x, 0.01f)
        assertEquals(1440f - 220f - 360f - 16f, log.w, 0.01f)
        assertEquals(860f, log.h, 0.01f)
        assertEquals(360f, geometry.cardOf(NOTES)!!.w, 0.01f)
        assertEquals(80f, geometry.cardOf(LOG_LEVEL)!!.h, 0.01f)
        // Tags takes more height than Highlighters (weights 2.2 vs 1).
        assertTrue(geometry.cardOf(TAGS)!!.h > geometry.cardOf(HIGHLIGHTERS)!!.h * 2f)
        assertNotNull(geometry.cardOf(ISSUES))
        assertNotNull(geometry.cardOf(LOG_COMPOSITION))
    }

    // ---- the toolbar tile ----------------------------------------------------------------------------------------

    private fun inLayoutCtx(compare: Boolean = false) = ctx(compare = compare).copy(toolbarInLayout = true)

    private fun filterColumn(tree: TileNode<LogViewSectionId>) = (tree as TileSplit).children.first() as TileSplit

    @Test
    fun theToolbarTileIsInAStoredTreeExactlyWhileTheToolbarIsInTheLayout() {
        val plain = sanitizeLogViewLayoutState(classicLogViewLayout(sizes), emptyList(), LogViewScope.SINGLE).first
        assertFalse(TOOLBAR in leafIds(plain))
        val inLayout = sanitizeLogViewLayoutState(classicLogViewLayout(sizes), emptyList(), LogViewScope.SINGLE, toolbarInLayout = true).first
        assertEquals(singleIds + TOOLBAR, leafIds(inLayout).toSet())
        assertEquals(leafIds(inLayout).size, leafIds(inLayout).toSet().size, "once")
        // Out of the layout again, the next sanitize removes it.
        assertFalse(TOOLBAR in leafIds(sanitizeLogViewLayoutState(inLayout, emptyList(), LogViewScope.SINGLE).first))
        // The compare view never has it.
        assertFalse(TOOLBAR in leafIds(sanitizeLogViewLayoutState(null, emptyList(), LogViewScope.COMPARE, toolbarInLayout = true).first))
        assertFalse(TOOLBAR in leafIds(sanitizeLogViewLayoutState(inLayout, emptyList(), LogViewScope.COMPARE, toolbarInLayout = true).first))
        // Idempotent.
        val once = sanitizeLogViewLayoutState(inLayout, emptyList(), LogViewScope.SINGLE, true)
        assertEquals(once, sanitizeLogViewLayoutState(once.first, once.second, LogViewScope.SINGLE, true))
    }

    @Test
    fun aMissingToolbarTileIsReinsertedSlimAtTheTopOfTheLeftColumn() {
        val column = filterColumn(sanitizeLogViewLayoutState(classicLogViewLayout(sizes), emptyList(), LogViewScope.SINGLE, true).first)
        val first = column.children.first() as TileLeaf
        assertEquals(TOOLBAR, first.section)
        assertEquals(TOOLBAR_TILE_COLUMN_DP, first.sizeDp, "slim: pinned to its natural height")
        assertEquals(TAGS, (column.children[1] as TileLeaf).section, "above Tags, the first tile of the column")
        // From nothing at all, or with the tile hidden or lost with a malformed tree.
        val fromScratch = sanitizeLogViewLayoutState(null, emptyList(), LogViewScope.SINGLE, true)
        assertEquals(TOOLBAR, tileLeafSections(fromScratch.first).first())
        val hiddenToo = sanitizeLogViewLayoutState(classicLogViewLayout(sizes), listOf(TOOLBAR, NOTES), LogViewScope.SINGLE, true)
        assertEquals(listOf(NOTES), hiddenToo.second, "the toolbar tile is never in the hidden list")
        assertTrue(TOOLBAR in leafIds(hiddenToo.first))
    }

    @Test
    fun effectiveLayoutHasTheToolbarTileOnlyWhileInLayoutWithoutAStoredEdit() {
        val derived = effectiveLogViewLayout(AppSettings(toolbarPlacement = ToolbarPlacement.IN_LAYOUT), sizes)
        assertEquals(TOOLBAR, tileLeafSections(derived).first())
        assertEquals(singleIds + TOOLBAR, leafIds(derived).toSet())
        // A stored tree that still holds the tile after the toolbar left the layout is drawn without it.
        val stale = AppSettings(logViewLayout = derived)
        assertFalse(TOOLBAR in leafIds(effectiveLogViewLayout(stale, sizes)))
        // Everything else is returned as stored.
        val stored = classicLogViewLayout(sizes)
        assertSame(stored, effectiveLogViewLayout(AppSettings(logViewLayout = stored), sizes))
        assertEquals(classicLogViewLayout(sizes), effectiveLogViewLayout(AppSettings(), sizes))
    }

    @Test
    fun withToolbarTileInsertsOrRemovesAndLeavesAnAlreadyRightTreeAlone() {
        val tree = classicLogViewLayout(sizes)
        assertSame(tree, withToolbarTile(tree, false))
        val with = withToolbarTile(tree, true)
        assertSame(with, withToolbarTile(with, true))
        assertEquals(tree, withToolbarTile(with, false).let { removeTile(it, TOOLBAR) ?: it })
    }

    @Test
    fun theToolbarTileAppliesOnlyInTheSingleViewWhileInLayoutAndIsNeverHidden() {
        assertFalse(logViewTileApplicable(TOOLBAR, ctx()))
        assertTrue(logViewTileApplicable(TOOLBAR, inLayoutCtx()))
        assertFalse(logViewTileApplicable(TOOLBAR, inLayoutCtx(compare = true)))
        // Whatever the group switches and the hidden list say.
        assertTrue(logViewTileVisible(TOOLBAR, inLayoutCtx().copy(groupOn = { false }), listOf(TOOLBAR, DIAGRAMS)))
        assertFalse(logViewTileVisible(TOOLBAR, ctx(), emptyList()))
        assertTrue(TOOLBAR.neverHidden && LOG.neverHidden && !NOTES.neverHidden)
        assertFalse(TOOLBAR.isLogTile)
        val settings = AppSettings()
        assertSame(settings, settings.withLogViewTileHidden(TOOLBAR, true), "refused")
        assertSame(settings, settings.withLogViewTileCollapsed(TOOLBAR, true), "it does not collapse either")
        assertEquals(0, logViewHiddenBadgeCount(inLayoutCtx(), listOf(TOOLBAR), LogViewScope.SINGLE))
    }

    @Test
    fun theLayoutMenusPanelsNeverListTheToolbar() {
        assertFalse(TOOLBAR in layoutMenuPanels(inLayoutCtx()))
        assertTrue(NOTES in layoutMenuPanels(inLayoutCtx()))
    }

    @Test
    fun theToolbarTileIsNoF6Stop() {
        val tree = withToolbarTile(classicLogViewLayout(sizes), true)
        assertEquals(logViewFocusOrder(classicLogViewLayout(sizes)), logViewFocusOrder(tree))
    }

    @Test
    fun aPresetStaysActiveWhileTheToolbarTileIsInTheLayout() {
        val inLayout = AppSettings(toolbarPlacement = ToolbarPlacement.IN_LAYOUT).withLogViewPreset(LogViewLayoutPreset.CLASSIC, sizes)
        assertTrue(TOOLBAR in leafIds(assertNotNull(inLayout.logViewLayout)), "a preset keeps the toolbar in the layout")
        assertTrue(logViewPresetIsActive(LogViewLayoutPreset.CLASSIC, inLayout, sizes))
        assertFalse(logViewPresetIsActive(LogViewLayoutPreset.REVIEW, inLayout, sizes))
    }

    @Test
    fun choosingTheToolbarPlacementMovesTheTileInAndOutOfTheStoredLayout() {
        val base = AppSettings(toolbarPlacement = ToolbarPlacement.OWN_EDGE, logViewLayout = classicLogViewLayout(sizes))
        val into = base.withToolbarPlacement(ToolbarPlacement.IN_LAYOUT)
        assertEquals(ToolbarPlacement.IN_LAYOUT, into.toolbarPlacement)
        assertEquals(ToolbarPlacement.OWN_EDGE, into.toolbarFallbackPlacement, "the placement it replaces is the fallback")
        assertTrue(TOOLBAR in leafIds(assertNotNull(into.logViewLayout)))
        val out = into.withToolbarPlacement(ToolbarPlacement.WITH_TABS_START)
        assertEquals(ToolbarPlacement.WITH_TABS_START, out.toolbarPlacement)
        assertEquals(ToolbarPlacement.OWN_EDGE, out.toolbarFallbackPlacement, "the fallback is not touched by leaving")
        assertFalse(TOOLBAR in leafIds(assertNotNull(out.logViewLayout)))
        assertSame(into, into.withToolbarPlacement(ToolbarPlacement.IN_LAYOUT))
        // Choosing IN_LAYOUT twice keeps the real fallback.
        assertEquals(ToolbarPlacement.OWN_EDGE, into.withToolbarPlacement(ToolbarPlacement.IN_LAYOUT).toolbarFallbackPlacement)
    }

    @Test
    fun droppingTheToolbarIntoTheLayoutPinsItSlimInItsParentsAxis() {
        val settings = AppSettings(toolbarPlacement = ToolbarPlacement.WITH_TABS_END, logViewLayout = classicLogViewLayout(sizes))
        val tree = dropToolbar(classicLogViewLayout(sizes), TileDropTarget.OnSection(LOG, TileEdge.LEFT))
        val dropped = settings.withToolbarDroppedInLayout(tree)
        assertEquals(ToolbarPlacement.IN_LAYOUT, dropped.toolbarPlacement)
        assertEquals(ToolbarPlacement.WITH_TABS_END, dropped.toolbarFallbackPlacement)
        val stored = assertNotNull(dropped.logViewLayout)
        val leaf = findLeaf(stored, TOOLBAR)
        assertEquals(TOOLBAR_TILE_ROW_DP, leaf.sizeDp, "beside the log, in a row: pinned to a slim width")
        val above = dropToolbar(classicLogViewLayout(sizes), TileDropTarget.OnSection(LOG, TileEdge.TOP))
        assertEquals(TOOLBAR_TILE_COLUMN_DP, findLeaf(assertNotNull(settings.withToolbarDroppedInLayout(above).logViewLayout), TOOLBAR).sizeDp)
        // A size the user already chose is kept.
        val sized = pinToolbarTile(TileSplit(TileAxis.ROW, listOf(TileLeaf(TOOLBAR, 1f, 222f), TileLeaf(LOG))))
        assertEquals(222f, findLeaf(sized, TOOLBAR).sizeDp)
    }

    private fun dropToolbar(tree: TileNode<LogViewSectionId>, target: TileDropTarget<LogViewSectionId>) =
        com.indagium.ui.dropTile(tree, TOOLBAR, target)

    private fun findLeaf(tree: TileNode<LogViewSectionId>, id: LogViewSectionId): TileLeaf<LogViewSectionId> = when (tree) {
        is TileLeaf -> tree.takeIf { it.section == id } ?: error("no $id")
        is TileSplit -> tree.children.firstNotNullOf { runCatching { findLeaf(it, id) }.getOrNull() }
    }
}
