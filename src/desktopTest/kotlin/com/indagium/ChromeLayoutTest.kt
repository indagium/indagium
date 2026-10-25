package com.indagium

import com.indagium.model.AppSettings
import com.indagium.model.ChromeEdge
import com.indagium.model.TabStripPosition
import com.indagium.model.ToolbarPlacement
import com.indagium.ui.CHROME_VERTICAL_TAB_BAR_DP
import com.indagium.ui.ChromeArrangement
import com.indagium.ui.ChromeDockTarget
import com.indagium.ui.ChromeMetrics
import com.indagium.ui.ChromeOrientation
import com.indagium.ui.ChromeSlot
import com.indagium.ui.TileRect
import com.indagium.ui.applyChromeDock
import com.indagium.ui.chromeArrangement
import com.indagium.ui.chromeDockLabel
import com.indagium.ui.chromeDockTargetAt
import com.indagium.ui.chromeDockZones
import com.indagium.ui.chromeFrames
import com.indagium.ui.chromePopupOrigin
import com.indagium.ui.isVertical
import com.indagium.ui.key
import com.indagium.ui.recentMenuPlacement
import com.indagium.ui.toEdge
import com.indagium.ui.toTabStripPosition
import com.indagium.ui.withChromeArrangement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// The pure chrome maths (ui/ChromeLayout.kt): the arrangement the settings ask for, the frames of the bars, the dock zones of a
// dragged tab strip or toolbar, what a drop writes back, and the popup placement.
class ChromeLayoutTest {
    private val metrics = ChromeMetrics(tabBarHeight = 36f)
    private val width = 1200f
    private val height = 800f
    private val toolbarLength = 400f

    private fun arrangement(
        position: TabStripPosition = TabStripPosition.TOP,
        placement: ToolbarPlacement = ToolbarPlacement.WITH_TABS_END,
        edge: ChromeEdge = ChromeEdge.TOP,
    ) = ChromeArrangement(position, placement, edge)

    private fun frames(a: ChromeArrangement, grips: Boolean = false, toolbar: Float = toolbarLength) =
        chromeFrames(a, width, height, metrics, toolbar, grips)

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
    }

    @Test
    fun theArrangementFollowsTheSettings() {
        val a = chromeArrangement(
            AppSettings(tabStripPosition = TabStripPosition.LEFT, toolbarPlacement = ToolbarPlacement.OWN_EDGE, toolbarEdge = ChromeEdge.BOTTOM),
        )
        assertEquals(ChromeEdge.LEFT, a.tabEdge)
        assertEquals(ChromeOrientation.VERTICAL, a.tabsOrientation)
        assertEquals(ChromeEdge.BOTTOM, a.toolbarBarEdge)
        assertEquals(ChromeOrientation.HORIZONTAL, a.toolbarOrientation)
        assertFalse(a.toolbarInTabBar)
    }

    @Test
    fun theToolbarInATabBarTakesItsOrientation() {
        assertEquals(ChromeOrientation.VERTICAL, arrangement(TabStripPosition.RIGHT, ToolbarPlacement.WITH_TABS_START).toolbarOrientation)
        assertEquals(ChromeOrientation.HORIZONTAL, arrangement(TabStripPosition.BOTTOM, ToolbarPlacement.WITH_TABS_END).toolbarOrientation)
    }

    @Test
    fun besideTabsIsOnlyValidWithVerticalTabs() {
        listOf(TabStripPosition.TOP, TabStripPosition.BOTTOM).forEach { p ->
            val a = arrangement(p, ToolbarPlacement.BESIDE_TABS).sanitized()
            assertEquals(ToolbarPlacement.WITH_TABS_END, a.placement, "$p falls back to the end of the tab bar")
        }
        listOf(TabStripPosition.LEFT, TabStripPosition.RIGHT).forEach { p ->
            val a = arrangement(p, ToolbarPlacement.BESIDE_TABS).sanitized()
            assertEquals(ToolbarPlacement.BESIDE_TABS, a.placement)
            assertEquals(p.toEdge(), a.toolbarBarEdge)
        }
    }

    @Test
    fun everyOtherPlacementSurvivesSanitizing() {
        TabStripPosition.entries.forEach { p ->
            listOf(ToolbarPlacement.WITH_TABS_START, ToolbarPlacement.WITH_TABS_END, ToolbarPlacement.OWN_EDGE).forEach { placement ->
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
    fun positionsAndEdgesConvertBothWays() {
        TabStripPosition.entries.forEach { assertEquals(it, it.toEdge().toTabStripPosition()) }
        ChromeEdge.entries.forEach { assertEquals(it, it.toTabStripPosition().toEdge()) }
        assertTrue(ChromeEdge.LEFT.isVertical && ChromeEdge.RIGHT.isVertical)
        assertFalse(ChromeEdge.TOP.isVertical || ChromeEdge.BOTTOM.isVertical)
    }

    @Test
    fun withChromeArrangementWritesTheThreeFieldsAndSanitizes() {
        val settings = AppSettings()
        val moved = settings.withChromeArrangement(arrangement(TabStripPosition.LEFT, ToolbarPlacement.BESIDE_TABS, ChromeEdge.RIGHT))
        assertEquals(TabStripPosition.LEFT, moved.tabStripPosition)
        assertEquals(ToolbarPlacement.BESIDE_TABS, moved.toolbarPlacement)
        assertEquals(ChromeEdge.RIGHT, moved.toolbarEdge)
        val invalid = settings.withChromeArrangement(arrangement(TabStripPosition.BOTTOM, ToolbarPlacement.BESIDE_TABS))
        assertEquals(ToolbarPlacement.WITH_TABS_END, invalid.toolbarPlacement)
        assertSame(settings, settings.withChromeArrangement(chromeArrangement(settings)), "no change returns the same settings")
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
    }

    @Test
    fun theToolbarAtTheStartPutsTheTabsAfterIt() {
        val f = frames(arrangement(placement = ToolbarPlacement.WITH_TABS_START))
        assertEquals(TileRect(0f, 1f, toolbarLength, 34f), f.toolbar)
        assertEquals(TileRect(toolbarLength, 1f, width - toolbarLength, 34f), f.tabs)
    }

    @Test
    fun aBottomStripLeavesTheContentAboveIt() {
        val f = frames(arrangement(TabStripPosition.BOTTOM))
        assertEquals(TileRect(0f, height - 36f, width, 36f), f.tabBar)
        assertEquals(TileRect(0f, 0f, width, height - 36f), f.content)
        assertEquals(height - 35f, f.tabs.y)
    }

    @Test
    fun aVerticalStripIsAColumnWithTheToolbarAtItsBottom() {
        val left = frames(arrangement(TabStripPosition.LEFT), toolbar = 300f)
        assertEquals(TileRect(0f, 0f, CHROME_VERTICAL_TAB_BAR_DP, height), left.tabBar)
        assertEquals(TileRect(CHROME_VERTICAL_TAB_BAR_DP, 0f, width - CHROME_VERTICAL_TAB_BAR_DP, height), left.content)
        // 1 dp of padding left and right; the toolbar at the end (bottom), the tabs above it.
        assertEquals(TileRect(1f, height - 300f, CHROME_VERTICAL_TAB_BAR_DP - 2f, 300f), left.toolbar)
        assertEquals(TileRect(1f, 0f, CHROME_VERTICAL_TAB_BAR_DP - 2f, height - 300f), left.tabs)
        val right = frames(arrangement(TabStripPosition.RIGHT), toolbar = 300f)
        assertEquals(TileRect(width - CHROME_VERTICAL_TAB_BAR_DP, 0f, CHROME_VERTICAL_TAB_BAR_DP, height), right.tabBar)
        assertEquals(TileRect(0f, 0f, width - CHROME_VERTICAL_TAB_BAR_DP, height), right.content)
    }

    @Test
    fun besideTabsGivesTheToolbarItsOwnBarOnTheContentSide() {
        val f = frames(arrangement(TabStripPosition.LEFT, ToolbarPlacement.BESIDE_TABS))
        val bar = assertNotNull(f.toolbarBar)
        assertEquals(TileRect(0f, 0f, CHROME_VERTICAL_TAB_BAR_DP, height), f.tabBar)
        assertEquals(TileRect(CHROME_VERTICAL_TAB_BAR_DP, 0f, 40f, height), bar)
        assertEquals(CHROME_VERTICAL_TAB_BAR_DP + 40f, f.content.x)
        // The tabs have their whole bar to themselves.
        assertEquals(height, f.tabs.h)
        assertEquals(toolbarLength, f.toolbar.h)
        assertEquals(38f, f.toolbar.w)
    }

    @Test
    fun anOwnEdgeToolbarGetsABarOnThatEdge() {
        val f = frames(arrangement(placement = ToolbarPlacement.OWN_EDGE, edge = ChromeEdge.BOTTOM))
        assertEquals(TileRect(0f, 0f, width, 36f), f.tabBar)
        assertEquals(TileRect(0f, height - 36f, width, 36f), assertNotNull(f.toolbarBar))
        assertEquals(TileRect(0f, 36f, width, height - 72f), f.content)
        // The tabs have the whole top bar.
        assertEquals(width, f.tabs.w)
        // The toolbar starts at the bar's start.
        assertEquals(0f, f.toolbar.x)
    }

    @Test
    fun anOwnEdgeToolbarOnTheTabEdgeStacksInsideTheTabs() {
        val f = frames(arrangement(placement = ToolbarPlacement.OWN_EDGE, edge = ChromeEdge.TOP))
        assertEquals(TileRect(0f, 0f, width, 36f), f.tabBar)
        assertEquals(TileRect(0f, 36f, width, 36f), assertNotNull(f.toolbarBar))
        assertEquals(72f, f.content.y)
    }

    @Test
    fun sideBarsFillWhatTheTopAndBottomBarsLeave() {
        val f = frames(arrangement(TabStripPosition.LEFT, ToolbarPlacement.OWN_EDGE, ChromeEdge.TOP))
        val toolbarBar = assertNotNull(f.toolbarBar)
        assertEquals(TileRect(0f, 0f, width, 36f), toolbarBar)
        assertEquals(TileRect(0f, 36f, CHROME_VERTICAL_TAB_BAR_DP, height - 36f), f.tabBar)
        assertEquals(TileRect(CHROME_VERTICAL_TAB_BAR_DP, 36f, width - CHROME_VERTICAL_TAB_BAR_DP, height - 36f), f.content)
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
        assertEquals(height, vertical.toolbar.bottom)
    }

    @Test
    fun anOversizedToolbarOrBarNeverOverflowsTheWindow() {
        val f = chromeFrames(arrangement(), 300f, 20f, metrics, 900f, true)
        assertTrue(f.toolbar.w <= 300f && f.tabs.w >= 0f)
        assertTrue(f.content.h >= 0f && f.content.w >= 0f)
        val tiny = chromeFrames(arrangement(TabStripPosition.LEFT, ToolbarPlacement.BESIDE_TABS), 100f, 100f, metrics, 50f, false)
        assertTrue(tiny.content.w >= 0f)
        assertTrue(tiny.tabBar.right <= 100f && assertNotNull(tiny.toolbarBar).right <= 100f)
    }

    @Test
    fun aToolbarInAVerticalTabBarLeavesTheTabsRoom() {
        val left = arrangement(TabStripPosition.LEFT)
        assertEquals(650f, metrics.toolbarMaxLength(left, 800f))
        // A short window: at least half of the bar stays with the tabs.
        assertEquals(100f, metrics.toolbarMaxLength(left, 200f))
        // Everything else may use the whole window.
        assertEquals(800f, metrics.toolbarMaxLength(arrangement(TabStripPosition.LEFT, ToolbarPlacement.BESIDE_TABS), 800f))
        assertEquals(1200f, metrics.toolbarMaxLength(arrangement(), 1200f))
        assertEquals(800f, metrics.toolbarMaxLength(arrangement(placement = ToolbarPlacement.OWN_EDGE, edge = ChromeEdge.LEFT), 800f))
    }

    // ── Dock zones ─────────────────────────────────────────────────

    private fun zones(slot: ChromeSlot, a: ChromeArrangement = arrangement()) =
        chromeDockZones(slot, a, frames(a), metrics)

    @Test
    fun theTabStripCanBeDroppedOnTheFourEdges() {
        val keys = zones(ChromeSlot.TABS).map { it.target.key() }
        assertEquals(setOf("top", "bottom", "left", "right"), keys.toSet())
        assertEquals(4, keys.size)
    }

    @Test
    fun theToolbarCanBeDroppedOnTheEdgesAndOnBothHalvesOfTheTabBar() {
        val keys = zones(ChromeSlot.TOOLBAR).map { it.target.key() }.toSet()
        assertEquals(setOf("top", "bottom", "left", "right", "tabs-start", "tabs-end"), keys)
    }

    @Test
    fun besideTabsReplacesTheTabEdgeOnlyWithVerticalTabs() {
        listOf(TabStripPosition.LEFT, TabStripPosition.RIGHT).forEach { p ->
            val keys = zones(ChromeSlot.TOOLBAR, arrangement(p)).map { it.target.key() }.toSet()
            assertTrue("beside-tabs" in keys, "$p offers beside tabs")
            assertFalse(p.name.lowercase() in keys, "$p: its own edge is the beside zone")
        }
        listOf(TabStripPosition.TOP, TabStripPosition.BOTTOM).forEach { p ->
            val keys = zones(ChromeSlot.TOOLBAR, arrangement(p)).map { it.target.key() }.toSet()
            assertFalse("beside-tabs" in keys, "$p never offers beside tabs")
            assertTrue(p.name.lowercase() in keys)
        }
    }

    @Test
    fun theZonesNeverOverlapAndLieInsideTheWindow() {
        TabStripPosition.entries.forEach { p ->
            ChromeSlot.entries.forEach { slot ->
                val z = zones(slot, arrangement(p))
                z.forEach { zone ->
                    val r = zone.rect
                    assertTrue(r.x >= 0f && r.y >= 0f && r.right <= width && r.bottom <= height, "$slot/$p/${zone.target}: $r")
                }
                for (i in z.indices) for (j in i + 1 until z.size) {
                    val a = z[i].rect
                    val b = z[j].rect
                    val overlap = a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom
                    assertFalse(overlap, "$slot/$p: ${z[i].target} overlaps ${z[j].target}")
                }
            }
        }
    }

    @Test
    fun theTabBarZonesAreItsStartAndEndHalves() {
        val a = arrangement()
        val z = zones(ChromeSlot.TOOLBAR, a).associate { it.target to it.rect }
        assertEquals(TileRect(0f, 0f, width / 2f, 36f), z.getValue(ChromeDockTarget.TabsStart))
        assertEquals(TileRect(width / 2f, 0f, width / 2f, 36f), z.getValue(ChromeDockTarget.TabsEnd))
        val left = zones(ChromeSlot.TOOLBAR, arrangement(TabStripPosition.LEFT)).associate { it.target to it.rect }
        assertEquals(TileRect(0f, 0f, CHROME_VERTICAL_TAB_BAR_DP, height / 2f), left.getValue(ChromeDockTarget.TabsStart))
        assertEquals(TileRect(0f, height / 2f, CHROME_VERTICAL_TAB_BAR_DP, height / 2f), left.getValue(ChromeDockTarget.TabsEnd))
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
    }

    @Test
    fun theTargetUnderAPointIsFoundAndNullOutsideEveryZone() {
        val z = zones(ChromeSlot.TOOLBAR)
        assertEquals(ChromeDockTarget.TabsStart, chromeDockTargetAt(z, 10f, 10f))
        assertEquals(ChromeDockTarget.TabsEnd, chromeDockTargetAt(z, width - 10f, 10f))
        assertEquals(ChromeDockTarget.Edge(ChromeEdge.BOTTOM), chromeDockTargetAt(z, width / 2f, height - 5f))
        assertEquals(ChromeDockTarget.Edge(ChromeEdge.LEFT), chromeDockTargetAt(z, 5f, height / 2f))
        assertEquals(ChromeDockTarget.Edge(ChromeEdge.RIGHT), chromeDockTargetAt(z, width - 5f, height / 2f))
        assertNull(chromeDockTargetAt(z, width / 2f, height / 2f))
    }

    @Test
    fun dockKeysAndLabelsAreStable() {
        assertEquals("top", ChromeDockTarget.Edge(ChromeEdge.TOP).key())
        assertEquals("tabs-start", ChromeDockTarget.TabsStart.key())
        assertEquals("tabs-end", ChromeDockTarget.TabsEnd.key())
        assertEquals("beside-tabs", ChromeDockTarget.BesideTabs.key())
        assertEquals("With tabs · start", chromeDockLabel(ChromeSlot.TOOLBAR, ChromeDockTarget.TabsStart))
        assertEquals("Beside tabs", chromeDockLabel(ChromeSlot.TOOLBAR, ChromeDockTarget.BesideTabs))
        assertEquals("Tabs · Left", chromeDockLabel(ChromeSlot.TABS, ChromeDockTarget.Edge(ChromeEdge.LEFT)))
        assertEquals("Toolbar · Bottom", chromeDockLabel(ChromeSlot.TOOLBAR, ChromeDockTarget.Edge(ChromeEdge.BOTTOM)))
    }

    // ── Drop apply ─────────────────────────────────────────────────

    @Test
    fun droppingTheTabStripOnAnEdgeMovesIt() {
        ChromeEdge.entries.forEach { edge ->
            val next = applyChromeDock(arrangement(), ChromeSlot.TABS, ChromeDockTarget.Edge(edge))
            assertEquals(edge.toTabStripPosition(), next.tabPosition)
            assertEquals(ToolbarPlacement.WITH_TABS_END, next.placement, "the toolbar travels with the tab strip")
        }
    }

    @Test
    fun droppingTheTabStripElsewhereChangesNothing() {
        val a = arrangement(TabStripPosition.LEFT)
        listOf(ChromeDockTarget.TabsStart, ChromeDockTarget.TabsEnd, ChromeDockTarget.BesideTabs).forEach {
            assertEquals(a, applyChromeDock(a, ChromeSlot.TABS, it))
        }
    }

    @Test
    fun movingTheTabStripToAHorizontalEdgeTurnsBesideTabsIntoTheEndOfTheBar() {
        val beside = arrangement(TabStripPosition.LEFT, ToolbarPlacement.BESIDE_TABS)
        val moved = applyChromeDock(beside, ChromeSlot.TABS, ChromeDockTarget.Edge(ChromeEdge.TOP))
        assertEquals(TabStripPosition.TOP, moved.tabPosition)
        assertEquals(ToolbarPlacement.WITH_TABS_END, moved.placement)
        // To the other vertical edge it stays beside.
        val right = applyChromeDock(beside, ChromeSlot.TABS, ChromeDockTarget.Edge(ChromeEdge.RIGHT))
        assertEquals(ToolbarPlacement.BESIDE_TABS, right.placement)
    }

    @Test
    fun movingTheTabStripKeepsAnOwnEdgeToolbarWhereItWas() {
        val own = arrangement(placement = ToolbarPlacement.OWN_EDGE, edge = ChromeEdge.BOTTOM)
        val moved = applyChromeDock(own, ChromeSlot.TABS, ChromeDockTarget.Edge(ChromeEdge.LEFT))
        assertEquals(ToolbarPlacement.OWN_EDGE, moved.placement)
        assertEquals(ChromeEdge.BOTTOM, moved.toolbarEdge)
    }

    @Test
    fun droppingTheToolbarOnAnEdgeDocksItThere() {
        val next = applyChromeDock(arrangement(), ChromeSlot.TOOLBAR, ChromeDockTarget.Edge(ChromeEdge.BOTTOM))
        assertEquals(ToolbarPlacement.OWN_EDGE, next.placement)
        assertEquals(ChromeEdge.BOTTOM, next.toolbarEdge)
        assertEquals(TabStripPosition.TOP, next.tabPosition)
    }

    @Test
    fun droppingTheToolbarOnTheTabStripsEdgeStacksItWhenHorizontalAndIsBesideWhenVertical() {
        val horizontal = applyChromeDock(arrangement(), ChromeSlot.TOOLBAR, ChromeDockTarget.Edge(ChromeEdge.TOP))
        assertEquals(ToolbarPlacement.OWN_EDGE, horizontal.placement)
        assertEquals(ChromeEdge.TOP, horizontal.toolbarEdge)
        val vertical = applyChromeDock(arrangement(TabStripPosition.RIGHT), ChromeSlot.TOOLBAR, ChromeDockTarget.Edge(ChromeEdge.RIGHT))
        assertEquals(ToolbarPlacement.BESIDE_TABS, vertical.placement)
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
    fun besideTabsDropIsSanitizedAwayWithHorizontalTabs() {
        val next = applyChromeDock(arrangement(TabStripPosition.TOP), ChromeSlot.TOOLBAR, ChromeDockTarget.BesideTabs)
        assertEquals(ToolbarPlacement.WITH_TABS_END, next.placement)
        val ok = applyChromeDock(arrangement(TabStripPosition.LEFT), ChromeSlot.TOOLBAR, ChromeDockTarget.BesideTabs)
        assertEquals(ToolbarPlacement.BESIDE_TABS, ok.placement)
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
}
