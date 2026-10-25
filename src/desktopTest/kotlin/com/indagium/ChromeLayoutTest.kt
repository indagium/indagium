package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.ChromeEdge
import com.indagium.model.TabStripPosition
import com.indagium.model.ToolbarAlign
import com.indagium.model.ToolbarPlacement
import com.indagium.ui.CHROME_VERTICAL_TAB_BAR_DP
import com.indagium.ui.ChromeArrangement
import com.indagium.ui.ChromeDockTarget
import com.indagium.ui.ChromeMetrics
import com.indagium.ui.ChromeOrientation
import com.indagium.ui.ChromeSlot
import com.indagium.ui.TileRect
import com.indagium.ui.ToolbarOrder
import com.indagium.ui.applyChromeDock
import com.indagium.ui.chromeArrangement
import com.indagium.ui.chromeDockLabel
import com.indagium.ui.chromeDockTargetAt
import com.indagium.ui.chromeDockZones
import com.indagium.ui.chromeFrames
import com.indagium.ui.chromePopupOrigin
import com.indagium.ui.clampVerticalTabBarDp
import com.indagium.ui.isVertical
import com.indagium.ui.key
import com.indagium.ui.recentMenuEdge
import com.indagium.ui.recentMenuPlacement
import com.indagium.ui.toEdge
import com.indagium.ui.toTabStripPosition
import com.indagium.ui.withChromeArrangement
import com.indagium.ui.withToolbarLeavingLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// The pure chrome maths (ui/ChromeLayout.kt, ChromeDock.kt): the arrangement the settings ask for, the frames of the bars, the dock zones of a
// dragged tab strip or toolbar, what a drop writes back, and the popup placement.
class ChromeLayoutTest {
    private val metrics = ChromeMetrics(tabBarHeight = 36f)
    private val width = 1200f
    private val height = 800f
    private val toolbarLength = 400f

    // A card bar: the parts keep the room a flat bar gives them (the thickness less 2 dp) inside 3 dp of padding; the card is 6 dp in from the window
    // edge (or the bar before it) and from both ends, and has no margin on its content side.
    private val margin = 6f
    private val pad = 3f
    private val growth = 2 * (pad - 1f) + margin

    private fun arrangement(
        position: TabStripPosition = TabStripPosition.TOP,
        placement: ToolbarPlacement = ToolbarPlacement.WITH_TABS_END,
        edge: ChromeEdge = ChromeEdge.TOP,
        outer: Boolean = false,
        align: ToolbarAlign = ToolbarAlign.START,
    ) = ChromeArrangement(position, placement, edge, outer, align)

    private fun frames(a: ChromeArrangement, grips: Boolean = false, toolbar: Float = toolbarLength, m: ChromeMetrics = metrics) =
        chromeFrames(a, width, height, m, toolbar, grips)

    // ── Arrangement ────────────────────────────────────────────────

    @Test
    fun theDefaultSettingsGiveTopTabsWithTheToolbarAtTheirEnd() {
        val a = chromeArrangement(AppSettings())
        assertEquals(TabStripPosition.TOP, a.tabPosition)
        assertEquals(ToolbarPlacement.WITH_TABS_END, a.placement)
        assertEquals(ChromeOrientation.HORIZONTAL, a.tabsOrientation)
        assertEquals(ChromeOrientation.HORIZONTAL, a.toolbarOrientation)
        assertTrue(a.toolbarInTabBar)
        assertNull(a.toolbarBarEdge)
        assertFalse(a.cards, "the default joined top bar stays flat")
        assertFalse(a.toolbarOuter)
        assertEquals(ToolbarAlign.START, a.toolbarAlign)
    }

    @Test
    fun theArrangementFollowsTheSettings() {
        val a = chromeArrangement(
            AppSettings(
                tabStripPosition = TabStripPosition.LEFT,
                toolbarPlacement = ToolbarPlacement.OWN_EDGE,
                toolbarEdge = ChromeEdge.BOTTOM,
                toolbarOuter = true,
                toolbarAlign = ToolbarAlign.END,
            ),
        )
        assertEquals(ChromeEdge.LEFT, a.tabEdge)
        assertEquals(ChromeOrientation.VERTICAL, a.tabsOrientation)
        assertEquals(ChromeEdge.BOTTOM, a.toolbarBarEdge)
        assertEquals(ChromeOrientation.HORIZONTAL, a.toolbarOrientation)
        assertFalse(a.toolbarInTabBar)
        assertTrue(a.toolbarOuter)
        assertEquals(ToolbarAlign.END, a.toolbarAlign)
    }

    @Test
    fun theToolbarInATabBarIsAlwaysARowAndWrapsInAVerticalOne() {
        val vertical = arrangement(TabStripPosition.RIGHT, ToolbarPlacement.WITH_TABS_START)
        assertEquals(ChromeOrientation.HORIZONTAL, vertical.toolbarOrientation, "a row, not a column of full-width buttons")
        assertTrue(vertical.toolbarAxisVertical, "but it lies along the vertical bar")
        assertTrue(vertical.toolbarWrapped)
        val horizontal = arrangement(TabStripPosition.BOTTOM, ToolbarPlacement.WITH_TABS_END)
        assertEquals(ChromeOrientation.HORIZONTAL, horizontal.toolbarOrientation)
        assertFalse(horizontal.toolbarAxisVertical)
        assertFalse(horizontal.toolbarWrapped)
        // A standalone vertical toolbar bar stays a stack.
        val own = arrangement(placement = ToolbarPlacement.OWN_EDGE, edge = ChromeEdge.LEFT)
        assertEquals(ChromeOrientation.VERTICAL, own.toolbarOrientation)
        assertFalse(own.toolbarWrapped)
    }

    @Test
    fun onlyTheDefaultJoinedTopBarIsFlat() {
        assertFalse(arrangement(placement = ToolbarPlacement.WITH_TABS_START).cards)
        assertFalse(arrangement(placement = ToolbarPlacement.WITH_TABS_END).cards)
        TabStripPosition.entries.filter { it != TabStripPosition.TOP }.forEach { p ->
            assertTrue(arrangement(p).cards, "$p tabs are a card")
        }
        assertTrue(arrangement(placement = ToolbarPlacement.OWN_EDGE).cards, "a separate toolbar bar, even on top")
        assertTrue(arrangement(placement = ToolbarPlacement.IN_LAYOUT).cards)
    }

    @Test
    fun besideTabsIsTheToolbarsOwnInnerBarOnAVerticalStripsEdge() {
        listOf(TabStripPosition.LEFT, TabStripPosition.RIGHT).forEach { p ->
            val a = arrangement(p, ToolbarPlacement.BESIDE_TABS, ChromeEdge.TOP, outer = true).sanitized()
            assertEquals(ToolbarPlacement.OWN_EDGE, a.placement, "$p: the legacy value is the same edge as the strip")
            assertEquals(p.toEdge(), a.toolbarBarEdge)
            assertTrue(a.toolbarSharesTabEdge)
            assertFalse(a.toolbarOuter, "on the content side")
        }
    }

    @Test
    fun besideTabsWithHorizontalTabsFallsBackToTheEndOfTheBar() {
        listOf(TabStripPosition.TOP, TabStripPosition.BOTTOM).forEach { p ->
            val a = arrangement(p, ToolbarPlacement.BESIDE_TABS).sanitized()
            assertEquals(ToolbarPlacement.WITH_TABS_END, a.placement, "$p falls back to the end of the tab bar")
        }
    }

    @Test
    fun everyOtherPlacementSurvivesSanitizing() {
        TabStripPosition.entries.forEach { p ->
            listOf(
                ToolbarPlacement.WITH_TABS_START, ToolbarPlacement.WITH_TABS_END, ToolbarPlacement.OWN_EDGE, ToolbarPlacement.IN_LAYOUT,
            ).forEach { placement ->
                assertEquals(arrangement(p, placement, ChromeEdge.LEFT), arrangement(p, placement, ChromeEdge.LEFT).sanitized())
            }
        }
    }

    @Test
    fun anInvalidStoredCombinationIsSanitizedWithoutRewritingTheSettings() {
        val stored = AppSettings(tabStripPosition = TabStripPosition.TOP, toolbarPlacement = ToolbarPlacement.BESIDE_TABS)
        assertEquals(ToolbarPlacement.WITH_TABS_END, chromeArrangement(stored).placement)
        assertEquals(ToolbarPlacement.BESIDE_TABS, stored.toolbarPlacement)
    }

    @Test
    fun theOldBesideTabsSettingsGiveTheSameVisualResultAsBefore() {
        // Left tabs, the toolbar's own bar on the content side, its buttons at the start: what BESIDE_TABS always drew.
        val old = AppSettings(tabStripPosition = TabStripPosition.LEFT, toolbarPlacement = ToolbarPlacement.BESIDE_TABS)
        val f = frames(chromeArrangement(old))
        val bar = assertNotNull(f.toolbarBar)
        assertTrue(bar.x > f.tabBar.right - 0.001f, "the toolbar bar is on the content side of the tab bar")
        assertEquals(bar.right, f.content.x)
        assertEquals(f.toolbarBar?.y, f.toolbar.y - pad)
    }

    @Test
    fun anInLayoutToolbarOnASurfaceWithoutTilesIsTheFallbackBar() {
        val stored = AppSettings(
            toolbarPlacement = ToolbarPlacement.IN_LAYOUT,
            toolbarFallbackPlacement = ToolbarPlacement.OWN_EDGE,
            toolbarEdge = ChromeEdge.BOTTOM,
        )
        val bar = chromeArrangement(stored)
        assertEquals(ToolbarPlacement.OWN_EDGE, bar.placement)
        assertEquals(ChromeEdge.BOTTOM, bar.toolbarBarEdge)
        assertFalse(bar.toolbarInLayout)
        val hosted = chromeArrangement(stored, toolbarHosted = true)
        assertTrue(hosted.toolbarInLayout)
        assertNull(hosted.toolbarBarEdge)
        // The default fallback is the long-standing top bar, and a fallback can never be IN_LAYOUT itself.
        assertEquals(ToolbarPlacement.WITH_TABS_END, chromeArrangement(AppSettings(toolbarPlacement = ToolbarPlacement.IN_LAYOUT)).placement)
        val broken = AppSettings(toolbarPlacement = ToolbarPlacement.IN_LAYOUT, toolbarFallbackPlacement = ToolbarPlacement.IN_LAYOUT)
        assertEquals(ToolbarPlacement.WITH_TABS_END, chromeArrangement(broken).placement)
        // A stored placement other than IN_LAYOUT ignores the flag.
        assertEquals(ToolbarPlacement.WITH_TABS_START, chromeArrangement(AppSettings(toolbarPlacement = ToolbarPlacement.WITH_TABS_START), true).placement)
    }

    @Test
    fun positionsAndEdgesConvertBothWays() {
        TabStripPosition.entries.forEach { assertEquals(it, it.toEdge().toTabStripPosition()) }
        ChromeEdge.entries.forEach { assertEquals(it, it.toTabStripPosition().toEdge()) }
        assertTrue(ChromeEdge.LEFT.isVertical && ChromeEdge.RIGHT.isVertical)
        assertFalse(ChromeEdge.TOP.isVertical || ChromeEdge.BOTTOM.isVertical)
    }

    @Test
    fun withChromeArrangementWritesTheFieldsAndSanitizes() {
        val settings = AppSettings()
        val moved = settings.withChromeArrangement(
            arrangement(TabStripPosition.LEFT, ToolbarPlacement.OWN_EDGE, ChromeEdge.RIGHT, outer = true, align = ToolbarAlign.END),
        )
        assertEquals(TabStripPosition.LEFT, moved.tabStripPosition)
        assertEquals(ToolbarPlacement.OWN_EDGE, moved.toolbarPlacement)
        assertEquals(ChromeEdge.RIGHT, moved.toolbarEdge)
        assertTrue(moved.toolbarOuter)
        assertEquals(ToolbarAlign.END, moved.toolbarAlign)
        val invalid = settings.withChromeArrangement(arrangement(TabStripPosition.BOTTOM, ToolbarPlacement.BESIDE_TABS))
        assertEquals(ToolbarPlacement.WITH_TABS_END, invalid.toolbarPlacement)
        val legacy = settings.withChromeArrangement(arrangement(TabStripPosition.LEFT, ToolbarPlacement.BESIDE_TABS))
        assertEquals(ToolbarPlacement.OWN_EDGE, legacy.toolbarPlacement, "the legacy value is written as its plain equivalent")
        assertEquals(ChromeEdge.LEFT, legacy.toolbarEdge)
        assertSame(settings, settings.withChromeArrangement(chromeArrangement(settings)), "no change returns the same settings")
    }

    @Test
    fun anEditOfTheFallbackBarKeepsTheToolbarInTheLayout() {
        val stored = AppSettings(toolbarPlacement = ToolbarPlacement.IN_LAYOUT)
        // A tab drag on a surface without tiles: the arrangement is the bar one, and only the fallback changes.
        val dragged = applyChromeDock(chromeArrangement(stored), ChromeSlot.TABS, ChromeDockTarget.Edge(ChromeEdge.LEFT))
        val written = stored.withChromeArrangement(dragged)
        assertEquals(ToolbarPlacement.IN_LAYOUT, written.toolbarPlacement)
        assertEquals(TabStripPosition.LEFT, written.tabStripPosition)
        assertEquals(ToolbarPlacement.OWN_EDGE, written.toolbarFallbackPlacement, "the joined toolbar stayed behind on the old edge")
        assertEquals(ChromeEdge.TOP, written.toolbarEdge)
        // On a surface with tiles the arrangement itself is IN_LAYOUT, and a tab drag leaves the fallback alone.
        val hosted = applyChromeDock(chromeArrangement(stored, true), ChromeSlot.TABS, ChromeDockTarget.Edge(ChromeEdge.BOTTOM))
        val hostedWritten = stored.withChromeArrangement(hosted)
        assertEquals(ToolbarPlacement.IN_LAYOUT, hostedWritten.toolbarPlacement)
        assertEquals(ToolbarPlacement.WITH_TABS_END, hostedWritten.toolbarFallbackPlacement)
        assertEquals(TabStripPosition.BOTTOM, hostedWritten.tabStripPosition)
    }

    @Test
    fun movingTheToolbarOutOfTheLayoutLeavesIt() {
        val stored = AppSettings(toolbarPlacement = ToolbarPlacement.IN_LAYOUT)
        val next = applyChromeDock(chromeArrangement(stored, true), ChromeSlot.TOOLBAR, ChromeDockTarget.ToolbarEdge(ChromeEdge.RIGHT, ToolbarAlign.END))
        val written = stored.withToolbarLeavingLayout(next)
        assertEquals(ToolbarPlacement.OWN_EDGE, written.toolbarPlacement)
        assertEquals(ChromeEdge.RIGHT, written.toolbarEdge)
        assertEquals(ToolbarAlign.END, written.toolbarAlign)
    }

    // ── Frames ─────────────────────────────────────────────────────

    @Test
    fun theDefaultFramesAreTheLongStandingStripWithTheToolbarAtItsEnd() {
        val f = frames(arrangement())
        assertEquals(TileRect(0f, 0f, width, 36f), f.tabBar)
        // 1 dp of padding above and below, the toolbar's own length at the end, the tabs in what is left.
        assertEquals(TileRect(0f, 1f, width - toolbarLength, 34f), f.tabs)
        assertEquals(TileRect(width - toolbarLength, 1f, toolbarLength, 34f), f.toolbar)
        assertEquals(TileRect(0f, 36f, width, height - 36f), f.content)
        assertNull(f.toolbarBar)
        assertNull(f.tabsGrip)
        assertNull(f.toolbarGrip)
        assertNull(f.tabSplitter)
        assertFalse(f.cards)
    }

    @Test
    fun theToolbarAtTheStartPutsTheTabsAfterIt() {
        val f = frames(arrangement(placement = ToolbarPlacement.WITH_TABS_START))
        assertEquals(TileRect(0f, 1f, toolbarLength, 34f), f.toolbar)
        assertEquals(TileRect(toolbarLength, 1f, width - toolbarLength, 34f), f.tabs)
    }

    @Test
    fun aBottomStripIsACardWithTheContentAboveIt() {
        val f = frames(arrangement(TabStripPosition.BOTTOM))
        assertTrue(f.cards)
        val region = 36f + growth
        // The card sits inside the 6 dp margin; the tabs keep their 34 dp.
        assertEquals(TileRect(margin, height - region, width - 2 * margin, 36f + 2 * (pad - 1f)), f.tabBar)
        assertEquals(TileRect(0f, 0f, width, height - region), f.content)
        assertEquals(34f, f.tabs.h)
        assertEquals(f.tabBar.y + pad, f.tabs.y)
        assertEquals(f.tabBar.right - pad, f.toolbar.right, "the toolbar is still at the end")
    }

    @Test
    fun aVerticalStripIsACardColumnWithTheToolbarAtItsBottom() {
        val left = frames(arrangement(TabStripPosition.LEFT), toolbar = 300f)
        val region = CHROME_VERTICAL_TAB_BAR_DP + growth
        assertEquals(TileRect(margin, margin, CHROME_VERTICAL_TAB_BAR_DP + 2 * (pad - 1f), height - 2 * margin), left.tabBar)
        assertEquals(TileRect(region, 0f, width - region, height), left.content)
        // The toolbar, a wrapping row, at the end (bottom) of the bar, the tabs above it; both as wide as the bar less its padding.
        val area = left.tabBar.let { TileRect(it.x + pad, it.y + pad, it.w - 2 * pad, it.h - 2 * pad) }
        assertEquals(TileRect(area.x, area.bottom - 300f, area.w, 300f), left.toolbar)
        assertEquals(TileRect(area.x, area.y, area.w, area.h - 300f), left.tabs)
        assertEquals(CHROME_VERTICAL_TAB_BAR_DP - 2f, left.tabs.w, "the tabs keep the bar's width less 2 dp")
        val right = frames(arrangement(TabStripPosition.RIGHT), toolbar = 300f)
        assertEquals(TileRect(0f, 0f, width - region, height), right.content)
        assertEquals(width - margin, right.tabBar.right)
    }

    @Test
    fun theVerticalBarFollowsTheMetricsWidth() {
        val wide = frames(arrangement(TabStripPosition.LEFT), m = ChromeMetrics(tabBarHeight = 36f, verticalTabBar = 320f))
        assertEquals(320f + growth, wide.content.x)
        assertEquals(320f - 2f, wide.tabs.w)
        assertEquals(CHROME_VERTICAL_TAB_BAR_DP + 20f, clampVerticalTabBarDp(220f))
        assertEquals(140f, clampVerticalTabBarDp(20f))
        assertEquals(480f, clampVerticalTabBarDp(900f))
        assertEquals(CHROME_VERTICAL_TAB_BAR_DP, clampVerticalTabBarDp(Float.NaN))
    }

    @Test
    fun aVerticalBarHasASplitterOnItsContentSide() {
        val left = frames(arrangement(TabStripPosition.LEFT))
        val l = assertNotNull(left.tabSplitter)
        assertEquals(left.tabBar.right - 5f, l.x)
        assertEquals(8f, l.w)
        assertEquals(left.tabBar.h, l.h)
        assertTrue(l.right <= left.content.x + 3.001f, "the handle reaches only 3 dp into the content")
        val right = frames(arrangement(TabStripPosition.RIGHT))
        val r = assertNotNull(right.tabSplitter)
        assertEquals(right.tabBar.x - 3f, r.x)
        assertTrue(r.x >= right.content.right - 3.001f)
        assertNull(frames(arrangement(TabStripPosition.BOTTOM)).tabSplitter)
        // It is there whether or not the toolbar is in the bar, and whether or not the chrome is locked.
        assertNotNull(frames(arrangement(TabStripPosition.LEFT, ToolbarPlacement.OWN_EDGE, ChromeEdge.RIGHT)).tabSplitter)
    }

    @Test
    fun anOwnEdgeToolbarGetsACardBarOnThatEdgeStartingAtTheStart() {
        val f = frames(arrangement(placement = ToolbarPlacement.OWN_EDGE, edge = ChromeEdge.BOTTOM))
        val bar = assertNotNull(f.toolbarBar)
        val region = 36f + growth
        assertEquals(margin, f.tabBar.y)
        assertEquals(height - region, bar.y)
        assertEquals(TileRect(0f, region, width, height - 2 * region), f.content)
        assertEquals(width - 2 * margin - 2 * pad, f.tabs.w, "the tabs have the whole top bar")
        assertEquals(bar.x + pad, f.toolbar.x)
    }

    @Test
    fun theToolbarsButtonsAlignToTheStartCentreOrEndOfItsBar() {
        fun toolbar(align: ToolbarAlign, edge: ChromeEdge) =
            frames(arrangement(placement = ToolbarPlacement.OWN_EDGE, edge = edge, align = align)).let { assertNotNull(it.toolbarBar) to it.toolbar }
        val (bar, start) = toolbar(ToolbarAlign.START, ChromeEdge.BOTTOM)
        assertEquals(bar.x + pad, start.x)
        assertEquals(toolbarLength, start.w)
        val (_, end) = toolbar(ToolbarAlign.END, ChromeEdge.BOTTOM)
        assertEquals(bar.right - pad, end.right, "at the right end of a horizontal bar")
        val (_, centre) = toolbar(ToolbarAlign.CENTER, ChromeEdge.BOTTOM)
        assertEquals(bar.x + (bar.w - toolbarLength) / 2f, centre.x, 0.001f)
        // A bar on a side: the end is the bottom.
        val (rightBar, rightEnd) = toolbar(ToolbarAlign.END, ChromeEdge.RIGHT)
        assertEquals(rightBar.bottom - pad, rightEnd.bottom)
        val (rightBar2, rightStart) = toolbar(ToolbarAlign.START, ChromeEdge.RIGHT)
        assertEquals(rightBar2.y + pad, rightStart.y)
    }

    @Test
    fun theToolbarCanBeTheOuterOrTheInnerBarOnTheTabsEdge() {
        // Tabs on top with the toolbar on its own top bar: below the tabs by default, above them when outer.
        val inner = frames(arrangement(placement = ToolbarPlacement.OWN_EDGE, edge = ChromeEdge.TOP))
        assertTrue(assertNotNull(inner.toolbarBar).y > inner.tabBar.y, "after the tabs")
        val outer = frames(arrangement(placement = ToolbarPlacement.OWN_EDGE, edge = ChromeEdge.TOP, outer = true))
        assertTrue(assertNotNull(outer.toolbarBar).y < outer.tabBar.y, "before the tabs")
        assertEquals(inner.content, outer.content, "the same room is left either way")
        assertEquals(margin, inner.toolbarBar!!.y - inner.tabBar.bottom, "the cards are a tile gap apart")
        // Tabs on the left: the toolbar's bar at the window edge, or on the content side.
        val leftInner = frames(arrangement(TabStripPosition.LEFT, ToolbarPlacement.OWN_EDGE, ChromeEdge.LEFT))
        assertTrue(assertNotNull(leftInner.toolbarBar).x > leftInner.tabBar.x)
        val leftOuter = frames(arrangement(TabStripPosition.LEFT, ToolbarPlacement.OWN_EDGE, ChromeEdge.LEFT, outer = true))
        assertEquals(margin, assertNotNull(leftOuter.toolbarBar).x, "the toolbar is at the window edge")
        assertTrue(leftOuter.tabBar.x > leftOuter.toolbarBar!!.right)
        // The order only matters when they share an edge.
        val apart = frames(arrangement(placement = ToolbarPlacement.OWN_EDGE, edge = ChromeEdge.BOTTOM, outer = true))
        assertEquals(margin, apart.tabBar.y)
    }

    @Test
    fun sideBarsFillWhatTheTopAndBottomBarsLeave() {
        val f = frames(arrangement(TabStripPosition.LEFT, ToolbarPlacement.OWN_EDGE, ChromeEdge.TOP))
        val toolbarBar = assertNotNull(f.toolbarBar)
        val region = 36f + growth
        assertEquals(TileRect(margin, margin, width - 2 * margin, 36f + 2 * (pad - 1f)), toolbarBar)
        assertEquals(region, f.tabBar.y - margin)
        assertEquals(TileRect(CHROME_VERTICAL_TAB_BAR_DP + growth, region, width - CHROME_VERTICAL_TAB_BAR_DP - growth, height - region), f.content)
    }

    @Test
    fun theTabBarHeightFollowsTheTabStyle() {
        val compact = chromeFrames(arrangement(), width, height, ChromeMetrics(tabBarHeight = 28f), toolbarLength, false)
        assertEquals(28f, compact.tabBar.h)
        assertEquals(26f, compact.tabs.h)
        assertEquals(28f, compact.content.y)
    }

    @Test
    fun aHorizontalGripOverlaysThePartAndAVerticalOneReservesAHeader() {
        val horizontal = frames(arrangement(), grips = true)
        // The overlay takes no room: the tabs and the toolbar are where they are without grips.
        assertEquals(frames(arrangement()).tabs, horizontal.tabs)
        assertEquals(frames(arrangement()).toolbar, horizontal.toolbar)
        assertEquals(TileRect(0f, 1f, 12f, 34f), horizontal.tabsGrip)
        assertEquals(TileRect(width - toolbarLength, 1f, 12f, 34f), horizontal.toolbarGrip)
        val vertical = frames(arrangement(TabStripPosition.LEFT), grips = true, toolbar = 300f)
        val tabsGrip = assertNotNull(vertical.tabsGrip)
        val toolbarGrip = assertNotNull(vertical.toolbarGrip)
        assertEquals(14f, tabsGrip.h)
        assertEquals(tabsGrip.bottom, vertical.tabs.y)
        assertEquals(14f, toolbarGrip.h)
        assertEquals(toolbarGrip.bottom, vertical.toolbar.y)
        assertEquals(vertical.tabBar.bottom - pad, vertical.toolbar.bottom)
    }

    @Test
    fun aToolbarInTheLayoutHasNoRoomInTheChrome() {
        val f = frames(arrangement(placement = ToolbarPlacement.IN_LAYOUT), grips = true)
        assertNull(f.toolbarBar)
        assertNull(f.toolbarGrip)
        assertEquals(0f, f.toolbar.w)
        assertEquals(0f, f.toolbar.h)
        assertEquals(width - 2 * margin - 2 * pad, f.tabs.w, "the tabs have the whole bar")
        assertTrue(f.cards)
    }

    @Test
    fun anOversizedToolbarOrBarNeverOverflowsTheWindow() {
        val f = chromeFrames(arrangement(), 300f, 20f, metrics, 900f, true)
        assertTrue(f.toolbar.w <= 300f && f.tabs.w >= 0f)
        assertTrue(f.content.h >= 0f && f.content.w >= 0f)
        val tiny = chromeFrames(arrangement(TabStripPosition.LEFT, ToolbarPlacement.OWN_EDGE, ChromeEdge.LEFT), 100f, 100f, metrics, 50f, false)
        assertTrue(tiny.content.w >= 0f)
        assertTrue(tiny.tabBar.right <= 100f && assertNotNull(tiny.toolbarBar).right <= 100f)
        val bottomCentre = arrangement(placement = ToolbarPlacement.OWN_EDGE, edge = ChromeEdge.BOTTOM, align = ToolbarAlign.CENTER)
        val centred = chromeFrames(bottomCentre, 300f, 200f, metrics, 900f, true)
        assertTrue(centred.toolbar.x >= 0f && centred.toolbar.right <= 300f)
    }

    @Test
    fun aToolbarInAVerticalTabBarLeavesTheTabsRoom() {
        val left = arrangement(TabStripPosition.LEFT)
        assertEquals(650f, metrics.toolbarMaxLength(left, 800f))
        // A short window: at least half of the bar stays with the tabs.
        assertEquals(100f, metrics.toolbarMaxLength(left, 200f))
        // Everything else may use the whole window.
        assertEquals(800f, metrics.toolbarMaxLength(arrangement(TabStripPosition.LEFT, ToolbarPlacement.OWN_EDGE, ChromeEdge.LEFT), 800f))
        assertEquals(1200f, metrics.toolbarMaxLength(arrangement(), 1200f))
        assertEquals(800f, metrics.toolbarMaxLength(arrangement(placement = ToolbarPlacement.OWN_EDGE, edge = ChromeEdge.LEFT), 800f))
    }

    // ── Dock zones ─────────────────────────────────────────────────

    private fun zones(slot: ChromeSlot, a: ChromeArrangement = arrangement()) =
        chromeDockZones(slot, a, frames(a), metrics)

    private fun keys(slot: ChromeSlot, a: ChromeArrangement = arrangement()) = zones(slot, a).map { it.target.key() }

    @Test
    fun theTabStripCanBeDroppedOnTheFourEdges() {
        val keys = keys(ChromeSlot.TABS)
        assertEquals(setOf("top", "bottom", "left", "right"), keys.toSet())
        assertEquals(4, keys.size)
    }

    @Test
    fun theToolbarCanBeDroppedOnTheStartOrEndOfEveryEdgeBeforeOrAfterTheTabsAndOnTheTabBar() {
        val keys = keys(ChromeSlot.TOOLBAR)
        assertEquals(
            setOf(
                "top-after-start", "top-after-end", "top-before-start", "top-before-end",
                "bottom-start", "bottom-end", "left-start", "left-end", "right-start", "right-end",
                "tabs-start", "tabs-end",
            ),
            keys.toSet(),
        )
        assertEquals(12, keys.size)
    }

    @Test
    fun beforeAndAfterTheTabsFollowTheTabStripsEdge() {
        TabStripPosition.entries.forEach { p ->
            val edge = p.name.lowercase()
            val k = keys(ChromeSlot.TOOLBAR, arrangement(p)).toSet()
            listOf("before", "after").forEach { order -> listOf("start", "end").forEach { assertTrue("$edge-$order-$it" in k, "$p offers $edge-$order-$it") } }
            // Every other edge has just its start and end.
            ChromeEdge.entries.filter { it != p.toEdge() }.forEach { other ->
                assertTrue("${other.name.lowercase()}-start" in k && "${other.name.lowercase()}-end" in k)
            }
        }
    }

    @Test
    fun theZonesNeverOverlapAndLieInsideTheWindow() {
        ToolbarPlacement.entries.forEach { placement ->
            TabStripPosition.entries.forEach { p ->
                ChromeSlot.entries.forEach { slot ->
                    val a = arrangement(p, placement, ChromeEdge.RIGHT)
                    val z = zones(slot, a)
                    z.forEach { zone ->
                        val r = zone.rect
                        assertTrue(r.x >= 0f && r.y >= 0f && r.right <= width && r.bottom <= height, "$slot/$p/$placement/${zone.target}: $r")
                    }
                    for (i in z.indices) for (j in i + 1 until z.size) {
                        val x = z[i].rect
                        val y = z[j].rect
                        val overlap = x.x < y.right && y.x < x.right && x.y < y.bottom && y.y < x.bottom
                        assertFalse(overlap, "$slot/$p/$placement: ${z[i].target} overlaps ${z[j].target}")
                    }
                }
            }
        }
    }

    @Test
    fun theTabBarsOuterHalfIsBeforeTheTabsAndItsInnerHalfJoinsThem() {
        val a = arrangement()
        val f = frames(a)
        val z = zones(ChromeSlot.TOOLBAR, a).associate { it.target to it.rect }
        // A top bar: the half at the window edge is "before", the half under it joins.
        val bar = f.tabBar
        assertEquals(TileRect(0f, 0f, width / 2f, 18f), z.getValue(ChromeDockTarget.ToolbarEdge(ChromeEdge.TOP, ToolbarAlign.START, ToolbarOrder.BEFORE_TABS)))
        assertEquals(TileRect(width / 2f, 18f, width / 2f, 18f), z.getValue(ChromeDockTarget.TabsEnd))
        assertEquals(TileRect(0f, 18f, width / 2f, 18f), z.getValue(ChromeDockTarget.TabsStart))
        assertEquals(bar.bottom, f.content.y)
        // A left bar: the half at the window edge is "before"; its halves run top and bottom.
        val left = arrangement(TabStripPosition.LEFT)
        val lf = frames(left)
        val lz = zones(ChromeSlot.TOOLBAR, left).associate { it.target to it.rect }
        val before = lz.getValue(ChromeDockTarget.ToolbarEdge(ChromeEdge.LEFT, ToolbarAlign.END, ToolbarOrder.BEFORE_TABS))
        assertEquals(lf.tabBar.x, before.x)
        assertEquals(lf.tabBar.w / 2f, before.w)
        assertEquals(lf.tabBar.y + lf.tabBar.h / 2f, before.y, "the end half is the lower one")
        val join = lz.getValue(ChromeDockTarget.TabsStart)
        assertEquals(lf.tabBar.x + lf.tabBar.w / 2f, join.x)
    }

    @Test
    fun theEdgeStripsSitInsideTheContentAlongTheWindowEdges() {
        val a = arrangement()
        val f = frames(a)
        val z = zones(ChromeSlot.TABS, a).associate { (it.target as ChromeDockTarget.Edge).edge to it.rect }
        assertEquals(f.content.y, z.getValue(ChromeEdge.TOP).y)
        assertEquals(f.content.bottom, z.getValue(ChromeEdge.BOTTOM).bottom)
        assertEquals(0f, z.getValue(ChromeEdge.LEFT).x)
        assertEquals(width, z.getValue(ChromeEdge.RIGHT).right)
        assertEquals(32f, z.getValue(ChromeEdge.TOP).h)
        assertEquals(32f, z.getValue(ChromeEdge.LEFT).w)
        // The toolbar's strips are the halves of those.
        val t = zones(ChromeSlot.TOOLBAR, a).associate { it.target.key() to it.rect }
        assertEquals(TileRect(0f, f.content.y, width / 2f, 32f), t.getValue("top-after-start"))
        assertEquals(TileRect(width / 2f, f.content.y, width / 2f, 32f), t.getValue("top-after-end"))
        val leftEnd = t.getValue("left-end")
        assertEquals(z.getValue(ChromeEdge.LEFT).bottom, leftEnd.bottom)
        assertEquals(z.getValue(ChromeEdge.LEFT).h / 2f, leftEnd.h)
    }

    @Test
    fun theTargetUnderAPointIsFoundAndNullOutsideEveryZone() {
        val z = zones(ChromeSlot.TOOLBAR)
        assertEquals(ChromeDockTarget.TabsStart, chromeDockTargetAt(z, 10f, 30f))
        assertEquals(ChromeDockTarget.TabsEnd, chromeDockTargetAt(z, width - 10f, 30f))
        assertEquals(
            ChromeDockTarget.ToolbarEdge(ChromeEdge.TOP, ToolbarAlign.END, ToolbarOrder.BEFORE_TABS),
            chromeDockTargetAt(z, width - 10f, 5f),
        )
        assertEquals(ChromeDockTarget.ToolbarEdge(ChromeEdge.BOTTOM, ToolbarAlign.START), chromeDockTargetAt(z, 100f, height - 5f))
        assertEquals(ChromeDockTarget.ToolbarEdge(ChromeEdge.BOTTOM, ToolbarAlign.END), chromeDockTargetAt(z, width - 100f, height - 5f))
        assertEquals(ChromeDockTarget.ToolbarEdge(ChromeEdge.LEFT, ToolbarAlign.START), chromeDockTargetAt(z, 5f, 100f + 40f))
        assertEquals(ChromeDockTarget.ToolbarEdge(ChromeEdge.RIGHT, ToolbarAlign.END), chromeDockTargetAt(z, width - 5f, height - 100f))
        assertNull(chromeDockTargetAt(z, width / 2f, height / 2f))
    }

    @Test
    fun dockKeysAndLabelsAreStable() {
        assertEquals("top", ChromeDockTarget.Edge(ChromeEdge.TOP).key())
        assertEquals("tabs-start", ChromeDockTarget.TabsStart.key())
        assertEquals("tabs-end", ChromeDockTarget.TabsEnd.key())
        assertEquals("bottom-start", ChromeDockTarget.ToolbarEdge(ChromeEdge.BOTTOM, ToolbarAlign.START).key())
        assertEquals("left-before-end", ChromeDockTarget.ToolbarEdge(ChromeEdge.LEFT, ToolbarAlign.END, ToolbarOrder.BEFORE_TABS).key())
        assertEquals("With tabs · start", chromeDockLabel(ChromeSlot.TOOLBAR, ChromeDockTarget.TabsStart))
        assertEquals("Tabs · Left", chromeDockLabel(ChromeSlot.TABS, ChromeDockTarget.Edge(ChromeEdge.LEFT)))
        assertEquals("Toolbar · Bottom · right", chromeDockLabel(ChromeSlot.TOOLBAR, ChromeDockTarget.ToolbarEdge(ChromeEdge.BOTTOM, ToolbarAlign.END)))
        assertEquals("Toolbar · Left · top", chromeDockLabel(ChromeSlot.TOOLBAR, ChromeDockTarget.ToolbarEdge(ChromeEdge.LEFT, ToolbarAlign.START)))
        assertEquals(
            "Before tabs · left",
            chromeDockLabel(ChromeSlot.TOOLBAR, ChromeDockTarget.ToolbarEdge(ChromeEdge.TOP, ToolbarAlign.START, ToolbarOrder.BEFORE_TABS)),
        )
        assertEquals(
            "After tabs · bottom",
            chromeDockLabel(ChromeSlot.TOOLBAR, ChromeDockTarget.ToolbarEdge(ChromeEdge.LEFT, ToolbarAlign.END, ToolbarOrder.AFTER_TABS)),
        )
    }

    // ── Drop apply ─────────────────────────────────────────────────

    @Test
    fun droppingTheTabStripOnAnEdgeMovesOnlyTheTabs() {
        ChromeEdge.entries.filter { it != ChromeEdge.TOP }.forEach { edge ->
            val next = applyChromeDock(arrangement(), ChromeSlot.TABS, ChromeDockTarget.Edge(edge))
            assertEquals(edge.toTabStripPosition(), next.tabPosition)
            // The toolbar was at the tab bar's end: it stays there, as a bar of its own on the old edge, its buttons at the end.
            assertEquals(ToolbarPlacement.OWN_EDGE, next.placement, "the toolbar does not travel with the tab strip")
            assertEquals(ChromeEdge.TOP, next.toolbarEdge)
            assertEquals(ToolbarAlign.END, next.toolbarAlign)
        }
    }

    @Test
    fun aToolbarAtTheStartOfTheTabBarStaysAtTheStartOfTheOldEdge() {
        val next = applyChromeDock(arrangement(placement = ToolbarPlacement.WITH_TABS_START), ChromeSlot.TABS, ChromeDockTarget.Edge(ChromeEdge.LEFT))
        assertEquals(TabStripPosition.LEFT, next.tabPosition)
        assertEquals(ToolbarPlacement.OWN_EDGE, next.placement)
        assertEquals(ChromeEdge.TOP, next.toolbarEdge)
        assertEquals(ToolbarAlign.START, next.toolbarAlign)
        // On screen it is where it was: a bar along the top, the tabs now at the left.
        val f = frames(next)
        assertNotNull(f.toolbarBar)
        assertEquals(ChromeEdge.TOP, next.toolbarBarEdge)
    }

    @Test
    fun movingTheTabsOfAVerticalBarLeavesTheToolbarOnTheOldEdge() {
        val joined = arrangement(TabStripPosition.LEFT, ToolbarPlacement.WITH_TABS_END)
        val next = applyChromeDock(joined, ChromeSlot.TABS, ChromeDockTarget.Edge(ChromeEdge.RIGHT))
        assertEquals(TabStripPosition.RIGHT, next.tabPosition)
        assertEquals(ChromeEdge.LEFT, next.toolbarEdge)
        assertEquals(ToolbarAlign.END, next.toolbarAlign, "still at the bottom of the left edge")
        assertEquals(ChromeOrientation.VERTICAL, next.toolbarOrientation, "now a stack on a bar of its own")
    }

    @Test
    fun droppingTheTabStripOnItsOwnEdgeOrElsewhereChangesNothing() {
        val a = arrangement(TabStripPosition.LEFT)
        assertEquals(a, applyChromeDock(a, ChromeSlot.TABS, ChromeDockTarget.Edge(ChromeEdge.LEFT)))
        listOf(ChromeDockTarget.TabsStart, ChromeDockTarget.TabsEnd, ChromeDockTarget.ToolbarEdge(ChromeEdge.TOP, ToolbarAlign.END)).forEach {
            assertEquals(a, applyChromeDock(a, ChromeSlot.TABS, it))
        }
    }

    @Test
    fun movingTheTabStripKeepsAnOwnEdgeToolbarWhereItWas() {
        val own = arrangement(placement = ToolbarPlacement.OWN_EDGE, edge = ChromeEdge.BOTTOM, align = ToolbarAlign.END)
        val moved = applyChromeDock(own, ChromeSlot.TABS, ChromeDockTarget.Edge(ChromeEdge.LEFT))
        assertEquals(ToolbarPlacement.OWN_EDGE, moved.placement)
        assertEquals(ChromeEdge.BOTTOM, moved.toolbarEdge)
        assertEquals(ToolbarAlign.END, moved.toolbarAlign)
        // The legacy beside-tabs toolbar is the same: it stays on the strip's old edge.
        val beside = arrangement(TabStripPosition.LEFT, ToolbarPlacement.BESIDE_TABS)
        val away = applyChromeDock(beside, ChromeSlot.TABS, ChromeDockTarget.Edge(ChromeEdge.TOP))
        assertEquals(TabStripPosition.TOP, away.tabPosition)
        assertEquals(ToolbarPlacement.OWN_EDGE, away.placement)
        assertEquals(ChromeEdge.LEFT, away.toolbarEdge)
    }

    @Test
    fun aToolbarInTheLayoutIsLeftAloneByATabDrag() {
        val a = arrangement(placement = ToolbarPlacement.IN_LAYOUT)
        val next = applyChromeDock(a, ChromeSlot.TABS, ChromeDockTarget.Edge(ChromeEdge.RIGHT))
        assertEquals(ToolbarPlacement.IN_LAYOUT, next.placement)
        assertEquals(TabStripPosition.RIGHT, next.tabPosition)
    }

    @Test
    fun droppingTheToolbarOnAnEdgeHalfDocksItThereAtThatEnd() {
        val start = applyChromeDock(arrangement(), ChromeSlot.TOOLBAR, ChromeDockTarget.ToolbarEdge(ChromeEdge.BOTTOM, ToolbarAlign.START))
        assertEquals(ToolbarPlacement.OWN_EDGE, start.placement)
        assertEquals(ChromeEdge.BOTTOM, start.toolbarEdge)
        assertEquals(ToolbarAlign.START, start.toolbarAlign)
        assertEquals(TabStripPosition.TOP, start.tabPosition)
        val end = applyChromeDock(arrangement(), ChromeSlot.TOOLBAR, ChromeDockTarget.ToolbarEdge(ChromeEdge.LEFT, ToolbarAlign.END))
        assertEquals(ToolbarAlign.END, end.toolbarAlign)
        assertEquals(ChromeEdge.LEFT, end.toolbarEdge)
    }

    @Test
    fun droppingTheToolbarBeforeOrAfterTheTabsSetsTheOrder() {
        val beforeTarget = ChromeDockTarget.ToolbarEdge(ChromeEdge.TOP, ToolbarAlign.END, ToolbarOrder.BEFORE_TABS)
        val before = applyChromeDock(arrangement(), ChromeSlot.TOOLBAR, beforeTarget)
        assertEquals(ToolbarPlacement.OWN_EDGE, before.placement)
        assertEquals(ChromeEdge.TOP, before.toolbarEdge)
        assertTrue(before.toolbarOuter)
        assertEquals(ToolbarAlign.END, before.toolbarAlign)
        val after = applyChromeDock(before, ChromeSlot.TOOLBAR, ChromeDockTarget.ToolbarEdge(ChromeEdge.TOP, ToolbarAlign.START, ToolbarOrder.AFTER_TABS))
        assertFalse(after.toolbarOuter)
        // On the left too: at the window edge, or on the content side.
        val leftBefore = applyChromeDock(
            arrangement(TabStripPosition.LEFT), ChromeSlot.TOOLBAR, ChromeDockTarget.ToolbarEdge(ChromeEdge.LEFT, ToolbarAlign.START, ToolbarOrder.BEFORE_TABS),
        )
        assertTrue(leftBefore.toolbarOuter)
        assertTrue(leftBefore.toolbarSharesTabEdge)
        // Another edge leaves the stored order alone.
        assertTrue(applyChromeDock(before, ChromeSlot.TOOLBAR, ChromeDockTarget.ToolbarEdge(ChromeEdge.BOTTOM, ToolbarAlign.START)).toolbarOuter)
    }

    @Test
    fun droppingTheToolbarOnTheTabBarJoinsIt() {
        val own = arrangement(placement = ToolbarPlacement.OWN_EDGE, edge = ChromeEdge.BOTTOM)
        assertEquals(ToolbarPlacement.WITH_TABS_START, applyChromeDock(own, ChromeSlot.TOOLBAR, ChromeDockTarget.TabsStart).placement)
        assertEquals(ToolbarPlacement.WITH_TABS_END, applyChromeDock(own, ChromeSlot.TOOLBAR, ChromeDockTarget.TabsEnd).placement)
        // The edge it was docked to is remembered for a later return to Own edge.
        assertEquals(ChromeEdge.BOTTOM, applyChromeDock(own, ChromeSlot.TOOLBAR, ChromeDockTarget.TabsEnd).toolbarEdge)
    }

    @Test
    fun joiningTheTabsAgainAfterATabDragRestoresASharedBar() {
        val dragged = applyChromeDock(arrangement(), ChromeSlot.TABS, ChromeDockTarget.Edge(ChromeEdge.LEFT))
        val joined = applyChromeDock(dragged, ChromeSlot.TOOLBAR, ChromeDockTarget.TabsStart)
        assertTrue(joined.toolbarInTabBar)
        assertEquals(TabStripPosition.LEFT, joined.tabPosition)
    }

    @Test
    fun everyOfferedZoneAppliesToAValidArrangement() {
        TabStripPosition.entries.forEach { p ->
            ToolbarPlacement.entries.forEach { placement ->
                val a = arrangement(p, placement, ChromeEdge.RIGHT).sanitized()
                ChromeSlot.entries.forEach { slot ->
                    zones(slot, a).forEach { zone ->
                        val next = applyChromeDock(a, slot, zone.target)
                        assertEquals(next, next.sanitized(), "$slot -> ${zone.target} from $a")
                        assertTrue(next.placement != ToolbarPlacement.BESIDE_TABS)
                    }
                }
            }
        }
    }

    // ── Popups ─────────────────────────────────────────────────────

    @Test
    fun aTopStripDropsItsPopupsBelowTheAnchorAsItAlwaysDid() {
        assertEquals(10 to 36, chromePopupOrigin(TabStripPosition.TOP, 10, 0, 50, 200, 300, 1000, 800, 36))
        // Not clamped: a top strip's popup keeps the place the strip has always given it.
        assertEquals(900 to 36, chromePopupOrigin(TabStripPosition.TOP, 900, 0, 950, 200, 300, 1000, 800, 36))
    }

    @Test
    fun otherStripsOpenPopupsOnTheContentSideInsideTheWindow() {
        assertEquals(200 to 100, chromePopupOrigin(TabStripPosition.LEFT, 0, 100, 200, 240, 300, 1000, 800, 36))
        assertEquals(560 to 100, chromePopupOrigin(TabStripPosition.RIGHT, 1000 - 200, 100, 1000, 240, 300, 1000, 800, 36))
        assertEquals(10 to 764 - 300, chromePopupOrigin(TabStripPosition.BOTTOM, 10, 764, 50, 200, 300, 1000, 800, 36))
        // Clamped into the window.
        assertEquals(200 to 500, chromePopupOrigin(TabStripPosition.LEFT, 0, 780, 200, 240, 300, 1000, 800, 36))
    }

    @Test
    fun theRecentMenuKeepsItsOldPlaceUnderATopToolbar() {
        val anchor = TileRect(900f, 1f, 18f, 34f)
        val p = recentMenuPlacement(ChromeEdge.TOP, anchor, width, height)
        assertTrue(p.alignEnd && !p.alignBottom)
        assertEquals(8f, p.dx)
        assertEquals(44f, p.dy)
        assertEquals(p, recentMenuPlacement(ChromeEdge.LEFT, null, width, height), "no anchor yet: the old place")
    }

    @Test
    fun theRecentMenuOpensOnTheContentSideOfOtherEdges() {
        val anchor = TileRect(10f, 300f, 38f, 18f)
        val left = recentMenuPlacement(ChromeEdge.LEFT, anchor, width, height)
        assertFalse(left.alignEnd)
        assertEquals(anchor.right + 8f, left.dx)
        assertEquals(300f, left.dy)
        val right = recentMenuPlacement(ChromeEdge.RIGHT, TileRect(width - 40f, 300f, 38f, 18f), width, height)
        assertTrue(right.alignEnd)
        assertEquals(48f, right.dx)
        val bottom = recentMenuPlacement(ChromeEdge.BOTTOM, TileRect(900f, height - 35f, 18f, 34f), width, height)
        assertTrue(bottom.alignBottom && bottom.alignEnd)
        assertEquals(43f, bottom.dy)
    }

    @Test
    fun theRecentMenuFollowsTheToolbarTileToTheSideOfTheWindowItIsOn() {
        val tile = arrangement(placement = ToolbarPlacement.IN_LAYOUT)
        assertEquals(ChromeEdge.LEFT, recentMenuEdge(tile, TileRect(100f, 100f, 18f, 20f), width))
        assertEquals(ChromeEdge.RIGHT, recentMenuEdge(tile, TileRect(width - 100f, 100f, 18f, 20f), width))
        assertEquals(ChromeEdge.LEFT, recentMenuEdge(tile, null, width))
        // A toolbar in a bar keeps its bar's edge, the tab strip's when it shares the bar.
        assertEquals(ChromeEdge.TOP, recentMenuEdge(arrangement(), null, width))
        assertEquals(ChromeEdge.BOTTOM, recentMenuEdge(arrangement(placement = ToolbarPlacement.OWN_EDGE, edge = ChromeEdge.BOTTOM), null, width))
    }
}
