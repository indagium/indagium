package com.indagium.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.indagium.model.ChromeEdge
import com.indagium.model.LogTab
import com.indagium.model.TabStripPosition
import com.indagium.model.ToolbarAlign
import com.indagium.model.ToolbarPlacement
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real [ChromeScaffold]: the default arrangement is the long-standing strip with the toolbar at its end, each tab position puts the
 * strip on its edge, moving the strip or toolbar never rebuilds the content, locking hides the grips, a vertical strip's tabs click and
 * reorder, and a grip drag writes the settings once, on release, after showing the drop strips.
 */
@OptIn(ExperimentalTestApi::class)
class ChromeScaffoldUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var directory: File
    private lateinit var state: AppState

    private companion object {
        // Pixel rounding of a dp position in a node's bounds.
        const val NEAR_DP = 1.5f

        // A card bar: 6 dp margin (the log view's host padding) from the window edge and at both ends, 3 dp padding inside it; its region is 6 + 2 x 2 dp
        // thicker than a flat bar's.
        const val CARD_MARGIN = 6f
        const val CARD_PAD = 3f
        const val CARD_GROWTH = 10f
    }

    private val hostWidth = 1000
    private val hostHeight = 640

    @After
    fun tearDown() {
        if (::state.isInitialized) state.close()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    private fun newState(): AppState {
        directory = createTempDirectory("chrome-scaffold-ui").toFile()
        return AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archives"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        )
    }

    private fun tab(id: String, name: String): LogTab = mkTab(id, name, emptyList()).copy(sourcePath = "/logs/$id/$name")

    private val tabs = listOf(tab("short", "a.log"), tab("long", "a-much-longer-file-name.log"), tab("mid", "middle.log"))

    @Composable
    private fun CountingContent() {
        var clicks by remember { mutableStateOf(0) }
        Box(Modifier.fillMaxSize().testTag("content")) {
            Text("clicks $clicks", Modifier.testTag("counter").clickable { clicks++ })
        }
    }

    private fun setup(configure: (AppState) -> Unit = {}) {
        state = newState()
        state.tabs = tabs
        state.activeTabId = tabs.first().id
        configure(state)
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(hostWidth.dp, hostHeight.dp)) { ChromeScaffold(state) { CountingContent() } }
            }
        }
        rule.waitForIdle()
    }

    private fun place(
        position: TabStripPosition = TabStripPosition.TOP,
        placement: ToolbarPlacement = ToolbarPlacement.WITH_TABS_END,
        edge: ChromeEdge = ChromeEdge.TOP,
    ) {
        state.updateSettings { it.copy(tabStripPosition = position, toolbarPlacement = placement, toolbarEdge = edge) }
        rule.waitForIdle()
    }

    private fun bounds(tag: String): DpRect = rule.onNodeWithTag(tag).getBoundsInRoot()

    private fun exists(tag: String): Boolean = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun assertNear(expected: Float, actual: Float, message: String) = assertEquals(expected, actual, NEAR_DP, message)

    // ── Positions ──────────────────────────────────────────────────

    @Test
    fun theDefaultLayoutKeepsTheStripOnTopWithTheToolbarAtItsEnd() {
        setup()
        val strip = bounds("tab-strip")
        val toolbar = bounds("main-toolbar")
        assertEquals(0.dp, strip.top)
        assertEquals(36.dp, strip.height)
        assertEquals(hostWidth.dp, strip.width)
        assertNear(strip.right.value, toolbar.right.value, "the toolbar ends where the strip does")
        assertTrue(toolbar.left > strip.left + 100.dp, "the toolbar is at the end, not the start")
        // The tabs fill what the toolbar leaves, one after another from the strip's start.
        assertTrue(bounds("tab-mid").right <= toolbar.left + 1.dp)
        assertEquals(0.dp, bounds("tab-short").left)
        assertEquals(36.dp, bounds("content").top)
        assertEquals(hostHeight.dp, bounds("content").bottom)
        assertEquals(1.dp, bounds("tab-short").top)
        assertEquals(34.dp, bounds("tab-short").height)
    }

    @Test
    fun theToolbarAtTheStartOfTheStripIsLeftOfTheTabs() {
        setup()
        place(placement = ToolbarPlacement.WITH_TABS_START)
        assertEquals(0.dp, bounds("main-toolbar").left)
        assertTrue(bounds("tab-short").left >= bounds("main-toolbar").right - 1.dp)
        assertEquals(36.dp, bounds("tab-strip").height)
    }

    @Test
    fun aLeftStripStacksTheTabsAtTheLeftEdgeAsACard() {
        setup()
        place(TabStripPosition.LEFT)
        val strip = bounds("tab-strip")
        // A card: 6 dp in from the window edge and from the top and bottom, the bar 2 x 2 dp wider than the tabs it holds.
        assertNear(CARD_MARGIN, strip.left.value, "the card's margin")
        assertNear(CHROME_VERTICAL_TAB_BAR_DP + 4f, strip.width.value, "the bar's width")
        assertNear(hostHeight - 2 * CARD_MARGIN, strip.height.value, "the bar's height")
        assertNear(CHROME_VERTICAL_TAB_BAR_DP + CARD_GROWTH, bounds("content").left.value, "the content starts after the card's region")
        assertEquals(hostWidth.dp, bounds("content").right)
        // Tabs top to bottom, each one style height (36) tall, inside the card's padding.
        assertEquals(bounds("tab-short").bottom, bounds("tab-long").top)
        assertEquals(bounds("tab-long").bottom, bounds("tab-mid").top)
        assertEquals(36.dp, bounds("tab-short").height)
        assertNear(strip.left.value + CARD_PAD, bounds("tab-short").left.value, "the card padding")
        // The toolbar is a row at the bottom of the same bar.
        assertTrue(bounds("main-toolbar").height < bounds("tab-strip").height)
        assertNear(strip.bottom.value - CARD_PAD, bounds("main-toolbar").bottom.value, "the toolbar ends the bar")
        assertTrue(bounds("main-toolbar").top > bounds("tab-mid").bottom, "the toolbar is under the tabs")
    }

    @Test
    fun aRightStripSitsAtTheRightEdge() {
        setup()
        place(TabStripPosition.RIGHT)
        val strip = bounds("tab-strip")
        assertNear(hostWidth - CARD_MARGIN, strip.right.value, "the card's margin")
        assertNear(CHROME_VERTICAL_TAB_BAR_DP + 4f, strip.width.value, "the bar's width")
        assertEquals(0.dp, bounds("content").left)
        assertNear(hostWidth - CHROME_VERTICAL_TAB_BAR_DP - CARD_GROWTH, bounds("content").right.value, "the content ends before the card's region")
    }

    @Test
    fun aBottomStripSitsUnderTheContent() {
        setup()
        place(TabStripPosition.BOTTOM)
        val strip = bounds("tab-strip")
        assertNear(hostHeight - CARD_MARGIN, strip.bottom.value, "the card's margin")
        assertNear(40f, strip.height.value, "the card is 4 dp taller than the tabs' bar")
        assertEquals(0.dp, bounds("content").top)
        assertNear(hostHeight - 36f - CARD_GROWTH, bounds("content").bottom.value, "the content ends before the card's region")
        assertNear(strip.right.value - CARD_PAD, bounds("main-toolbar").right.value, "still at the end")
    }

    @Test
    fun besideTabsMakesATallToolbarBarNextToAVerticalStrip() {
        setup()
        place(TabStripPosition.LEFT, ToolbarPlacement.BESIDE_TABS)
        val bar = bounds("main-toolbar-bar")
        // The legacy value is the toolbar's own bar on the same edge, on the content side of the tabs: icon-only buttons, 40 + 4 dp wide.
        assertTrue(bar.left >= bounds("tab-strip").right)
        assertNear(44f, bar.width.value, "an icon-only column")
        assertNear(CHROME_VERTICAL_TAB_BAR_DP + CARD_GROWTH + 40f + CARD_GROWTH, bounds("content").left.value, "the content starts after both cards")
        // The buttons are stacked: same left, ascending tops.
        val newTab = rule.onNodeWithContentDescription("New tab").getBoundsInRoot()
        val filter = rule.onNodeWithContentDescription("Toggle filter panel").getBoundsInRoot()
        val notes = rule.onNodeWithContentDescription("Toggle notes panel").getBoundsInRoot()
        assertTrue(newTab.top < filter.top && filter.top < notes.top)
        assertNear(filter.left.value, notes.left.value, "one column")
        // The tabs have the whole of their bar: the toolbar is not in it.
        assertNear(CHROME_VERTICAL_TAB_BAR_DP + 4f, bounds("tab-strip").width.value, "the tab bar")
    }

    @Test
    fun anOwnEdgeToolbarSitsOnItsEdge() {
        setup()
        place(placement = ToolbarPlacement.OWN_EDGE, edge = ChromeEdge.BOTTOM)
        assertNear(hostHeight - CARD_MARGIN, bounds("main-toolbar-bar").bottom.value, "a card on the bottom edge")
        assertNear(CARD_MARGIN, bounds("tab-strip").top.value, "the tabs' card on top")
        assertNear(40f, bounds("tab-strip").height.value, "the tabs' card")
        assertNear(36f + CARD_GROWTH, bounds("content").top.value, "the content starts below the tabs' region")
        assertNear(hostHeight - 36f - CARD_GROWTH, bounds("content").bottom.value, "and ends above the toolbar's")
        // Alone on its bar the toolbar starts at the bar's start.
        assertNear(bounds("main-toolbar-bar").left.value + CARD_PAD, bounds("main-toolbar").left.value, "at the start")
    }

    @Test
    fun anInvalidBesideTabsWithHorizontalTabsFallsBackToTheEndOfTheStrip() {
        setup()
        place(TabStripPosition.TOP, ToolbarPlacement.BESIDE_TABS)
        assertNear(bounds("tab-strip").right.value, bounds("main-toolbar").right.value, "sanitized to the end of the strip")
        assertEquals(ToolbarPlacement.BESIDE_TABS, state.settings.toolbarPlacement, "the stored value is not rewritten")
    }

    // ── The content is never rebuilt ───────────────────────────────

    @Test
    fun movingTheTabsOrTheToolbarKeepsTheContentsState() {
        setup()
        rule.onNodeWithTag("counter").performClick()
        rule.onNodeWithTag("counter").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("counter").assertTextEquals("clicks 2")
        listOf(TabStripPosition.LEFT, TabStripPosition.RIGHT, TabStripPosition.BOTTOM, TabStripPosition.TOP).forEach { p ->
            place(p)
            rule.onNodeWithTag("counter").assertTextEquals("clicks 2")
        }
        place(TabStripPosition.LEFT, ToolbarPlacement.BESIDE_TABS)
        rule.onNodeWithTag("counter").assertTextEquals("clicks 2")
        place(TabStripPosition.LEFT, ToolbarPlacement.OWN_EDGE, ChromeEdge.RIGHT)
        rule.onNodeWithTag("counter").assertTextEquals("clicks 2")
        place(TabStripPosition.BOTTOM, ToolbarPlacement.WITH_TABS_START)
        rule.onNodeWithTag("counter").assertTextEquals("clicks 2")
        rule.onNodeWithTag("counter").performClick()
        rule.onNodeWithTag("counter").assertTextEquals("clicks 3")
    }

    // ── Locking ────────────────────────────────────────────────────

    @Test
    fun lockingHidesTheGripsButNeverTheTabsOrTheToolbar() {
        setup()
        assertTrue(exists("chrome-grip-tabs"))
        assertTrue(exists("chrome-grip-toolbar"))
        state.updateSettings { it.copy(chromeLocked = true) }
        rule.waitForIdle()
        assertFalse(exists("chrome-grip-tabs"))
        assertFalse(exists("chrome-grip-toolbar"))
        assertTrue(exists("tab-strip") && exists("main-toolbar") && exists("tab-short"))
        // A vertical strip too, and the room its grip headers took is given back.
        place(TabStripPosition.LEFT)
        assertFalse(exists("chrome-grip-tabs"))
        val lockedTop = bounds("tab-short").top
        state.updateSettings { it.copy(chromeLocked = false) }
        rule.waitForIdle()
        assertTrue(exists("chrome-grip-tabs"))
        assertTrue(bounds("tab-short").top > lockedTop, "the unlocked vertical strip reserves a grip header")
    }

    // ── Vertical tabs ──────────────────────────────────────────────

    @Test
    fun clickingATabOfAVerticalStripSelectsIt() {
        setup()
        place(TabStripPosition.LEFT)
        rule.onNodeWithTag("tab-long").performClick()
        rule.waitForIdle()
        assertEquals("long", state.activeTabId)
        place(TabStripPosition.RIGHT)
        rule.onNodeWithTag("tab-mid").performClick()
        rule.waitForIdle()
        assertEquals("mid", state.activeTabId)
    }

    @Test
    fun theCloseButtonOfAVerticalTabClosesIt() {
        setup()
        place(TabStripPosition.LEFT)
        rule.onNodeWithTag("tab-close-long").performClick()
        rule.waitForIdle()
        assertEquals(listOf("short", "mid"), state.tabs.map { it.id })
    }

    // Press, drag down by [dy] px, let the strip recompose (a real drag has frames between moves), then release.
    private fun dragShort(dy: Float) {
        rule.onNodeWithTag("tab-short").performTouchInput {
            down(center)
            moveBy(Offset(0f, 20f))
            moveBy(Offset(0f, dy - 20f))
        }
        rule.waitForIdle()
        rule.onNodeWithTag("tab-short").performTouchInput { up() }
        rule.waitForIdle()
    }

    @Test
    fun dragReorderWorksAlongAVerticalStrip() {
        setup { it.updateSettings { s -> s.copy(tabStripPosition = TabStripPosition.LEFT, chromeLocked = true) } }
        val d = rule.density.density
        assertEquals(listOf("short", "long", "mid"), state.tabs.map { it.id })
        // A little way leaves the order alone.
        dragShort(12f * d)
        assertEquals(listOf("short", "long", "mid"), state.tabs.map { it.id })
        // Past the second tab's centre but not the third's: it lands second.
        dragShort(40f * d)
        assertEquals(listOf("long", "short", "mid"), state.tabs.map { it.id })
        assertTrue(bounds("tab-long").bottom <= bounds("tab-short").top + 1.dp)
        assertTrue(bounds("tab-short").bottom <= bounds("tab-mid").top + 1.dp)
    }

    @Test
    fun aVerticalStripThatIsTooShortCollapsesTheRestIntoTheOverflowButton() {
        directory = createTempDirectory("chrome-scaffold-overflow").toFile()
        state = AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archives"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        )
        val many = (1..12).map { tab("t$it", "file$it.log") }
        state.tabs = many
        state.activeTabId = many.last().id
        state.updateSettings { it.copy(tabStripPosition = TabStripPosition.LEFT, toolbarPlacement = ToolbarPlacement.OWN_EDGE, toolbarEdge = ChromeEdge.RIGHT) }
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Box(Modifier.size(600.dp, 240.dp)) { ChromeScaffold(state) { CountingContent() } }
            }
        }
        rule.waitForIdle()
        val shown = many.count { exists("tab-${it.id}") }
        assertTrue(shown in 1..6, "only the tabs that fit are shown, not all 12: $shown")
        assertTrue(exists("tab-t12"), "the active, last tab is among them")
        // Every shown tab lies inside the strip.
        many.filter { exists("tab-${it.id}") }.forEach { assertTrue(bounds("tab-${it.id}").bottom <= bounds("tab-strip").bottom) }
    }

    // ── Dragging the grips ─────────────────────────────────────────

    // Press [grip], pull it past the touch slop, and move to the centre of [dockTag]'s strip (which exists only while dragging).
    private fun dragGripTo(grip: String, dockTag: String) {
        val node = rule.onNodeWithTag(grip)
        val origin = bounds(grip)
        val d = rule.density.density
        node.performTouchInput {
            down(center)
            moveBy(Offset(40f, 40f))
        }
        rule.waitForIdle()
        val dock = bounds(dockTag)
        val target = Offset(
            ((dock.left.value + dock.width.value / 2f) - origin.left.value) * d,
            ((dock.top.value + dock.height.value / 2f) - origin.top.value) * d,
        )
        node.performTouchInput { moveTo(target) }
        rule.waitForIdle()
    }

    @Test
    fun draggingTheToolbarShowsTheDropStripsAndWritesTheSettingsOnceOnRelease() {
        setup()
        val before = state.settings
        assertFalse(exists("chrome-dock-bottom-end"))
        dragGripTo("chrome-grip-toolbar", "chrome-dock-bottom-end")
        listOf(
            "top-before-start", "top-before-end", "top-after-start", "top-after-end", "bottom-start", "bottom-end",
            "left-start", "left-end", "right-start", "right-end", "tabs-start", "tabs-end",
        ).forEach { assertTrue(exists("chrome-dock-$it"), "dock strip $it") }
        assertEquals(before, state.settings, "nothing is written while dragging")
        rule.onNodeWithTag("chrome-grip-toolbar").performTouchInput { up() }
        rule.waitForIdle()
        assertFalse(exists("chrome-dock-bottom-end"), "the strips go away on release")
        assertEquals(ToolbarPlacement.OWN_EDGE, state.settings.toolbarPlacement)
        assertEquals(ChromeEdge.BOTTOM, state.settings.toolbarEdge)
        assertEquals(ToolbarAlign.END, state.settings.toolbarAlign, "dropped on the end half: the buttons are at the end")
        assertEquals(TabStripPosition.TOP, state.settings.tabStripPosition)
        assertNear(hostHeight - CARD_MARGIN, bounds("main-toolbar-bar").bottom.value, "a card on the bottom edge")
        assertNear(bounds("main-toolbar-bar").right.value - CARD_PAD, bounds("main-toolbar").right.value, "at the right end of its bar")
    }

    @Test
    fun draggingTheTabsToTheLeftEdgeMovesOnlyTheTabsAndTheToolbarStaysOnTop() {
        setup()
        dragGripTo("chrome-grip-tabs", "chrome-dock-left")
        listOf("top", "bottom", "left", "right").forEach { assertTrue(exists("chrome-dock-$it"), "dock strip $it") }
        assertFalse(exists("chrome-dock-tabs-start"), "the tab strip is not dropped on itself")
        rule.onNodeWithTag("chrome-grip-tabs").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(TabStripPosition.LEFT, state.settings.tabStripPosition)
        // The toolbar was at the end of the top bar: it stays there as a bar of its own, its buttons at the end.
        assertEquals(ToolbarPlacement.OWN_EDGE, state.settings.toolbarPlacement)
        assertEquals(ChromeEdge.TOP, state.settings.toolbarEdge)
        assertEquals(ToolbarAlign.END, state.settings.toolbarAlign)
        assertNear(CARD_MARGIN, bounds("tab-strip").left.value, "the tabs are a card at the left edge")
        assertTrue(bounds("main-toolbar-bar").top < bounds("tab-strip").top, "the toolbar's bar is still along the top")
        assertNear(bounds("main-toolbar-bar").right.value - CARD_PAD, bounds("main-toolbar").right.value, "still at the right")
    }

    @Test
    fun droppingTheToolbarBackOnTheTabBarJoinsThemAgain() {
        setup()
        dragGripTo("chrome-grip-tabs", "chrome-dock-left")
        rule.onNodeWithTag("chrome-grip-tabs").performTouchInput { up() }
        rule.waitForIdle()
        dragGripTo("chrome-grip-toolbar", "chrome-dock-tabs-end")
        rule.onNodeWithTag("chrome-grip-toolbar").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(ToolbarPlacement.WITH_TABS_END, state.settings.toolbarPlacement)
        assertFalse(exists("chrome-dock-tabs-end"))
        assertTrue(bounds("main-toolbar").bottom <= bounds("tab-strip").bottom, "the toolbar is in the vertical tab bar again")
    }

    @Test
    fun draggingTheToolbarBeforeTheTabsPutsItsBarAboveThem() {
        setup()
        dragGripTo("chrome-grip-toolbar", "chrome-dock-top-before-end")
        rule.onNodeWithTag("chrome-grip-toolbar").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(ToolbarPlacement.OWN_EDGE, state.settings.toolbarPlacement)
        assertEquals(ChromeEdge.TOP, state.settings.toolbarEdge)
        assertTrue(state.settings.toolbarOuter)
        assertEquals(ToolbarAlign.END, state.settings.toolbarAlign)
        assertTrue(bounds("main-toolbar-bar").bottom <= bounds("tab-strip").top + NEAR_DP.dp, "the toolbar bar is above the tabs")
        assertNear(bounds("main-toolbar-bar").right.value - CARD_PAD, bounds("main-toolbar").right.value, "at the right")
    }

    @Test
    fun draggingTheToolbarOntoTheStartOfTheTabsJoinsThemThere() {
        setup()
        dragGripTo("chrome-grip-toolbar", "chrome-dock-tabs-start")
        rule.onNodeWithTag("chrome-grip-toolbar").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(ToolbarPlacement.WITH_TABS_START, state.settings.toolbarPlacement)
        assertEquals(0.dp, bounds("main-toolbar").left)
    }

    @Test
    fun releasingOutsideEveryStripChangesNothing() {
        setup()
        val before = state.settings
        val grip = rule.onNodeWithTag("chrome-grip-toolbar")
        val d = rule.density.density
        grip.performTouchInput {
            down(center)
            moveBy(Offset(40f, 40f))
            // The middle of the content: no strip there.
            moveTo(Offset(-300f * d, 300f * d))
        }
        rule.waitForIdle()
        grip.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(before, state.settings)
        assertFalse(exists("chrome-dock-top"))
    }

    // ── Order, alignment, width, wrapping, labels, cards ───────────

    @Test
    fun theToolbarsBarCanBeBelowOrAboveTheTabsOnTheirEdge() {
        setup()
        place(placement = ToolbarPlacement.OWN_EDGE, edge = ChromeEdge.TOP)
        assertTrue(bounds("main-toolbar-bar").top >= bounds("tab-strip").bottom, "after the tabs by default")
        state.updateSettings { it.copy(toolbarOuter = true) }
        rule.waitForIdle()
        assertTrue(bounds("main-toolbar-bar").bottom <= bounds("tab-strip").top + NEAR_DP.dp, "before the tabs when outer")
        // Tabs on the left: the toolbar's bar at the window edge, then the tabs.
        place(TabStripPosition.LEFT, ToolbarPlacement.OWN_EDGE, ChromeEdge.LEFT)
        assertNear(CARD_MARGIN, bounds("main-toolbar-bar").left.value, "the toolbar is first from the window edge")
        assertTrue(bounds("tab-strip").left >= bounds("main-toolbar-bar").right)
        state.updateSettings { it.copy(toolbarOuter = false) }
        rule.waitForIdle()
        assertNear(CARD_MARGIN, bounds("tab-strip").left.value, "the tabs are first")
        assertTrue(bounds("main-toolbar-bar").left >= bounds("tab-strip").right)
    }

    @Test
    fun theToolbarsButtonsCanSitAtTheEndOrCentreOfTheirBar() {
        setup()
        place(placement = ToolbarPlacement.OWN_EDGE, edge = ChromeEdge.BOTTOM)
        assertNear(bounds("main-toolbar-bar").left.value + CARD_PAD, bounds("main-toolbar").left.value, "start")
        state.updateSettings { it.copy(toolbarAlign = ToolbarAlign.END) }
        rule.waitForIdle()
        assertNear(bounds("main-toolbar-bar").right.value - CARD_PAD, bounds("main-toolbar").right.value, "end")
        state.updateSettings { it.copy(toolbarAlign = ToolbarAlign.CENTER) }
        rule.waitForIdle()
        val bar = bounds("main-toolbar-bar")
        val toolbar = bounds("main-toolbar")
        assertNear((bar.left.value + bar.right.value) / 2f, (toolbar.left.value + toolbar.right.value) / 2f, "centre")
    }

    @Test
    fun aVerticalToolbarBarAlignsToItsBottomToo() {
        setup()
        place(placement = ToolbarPlacement.OWN_EDGE, edge = ChromeEdge.RIGHT)
        assertNear(bounds("main-toolbar-bar").top.value + CARD_PAD + 14f, bounds("main-toolbar").top.value, "start: under its grip header")
        state.updateSettings { it.copy(toolbarAlign = ToolbarAlign.END) }
        rule.waitForIdle()
        assertNear(bounds("main-toolbar-bar").bottom.value - CARD_PAD, bounds("main-toolbar").bottom.value, "end")
    }

    // Drags the vertical tab bar's splitter by [dx] dp (pointer pixels divided by density) and lets the bar redraw, without releasing.
    private fun pullSplitter(dx: Float) {
        val d = rule.density.density
        rule.onNodeWithTag("chrome-splitter-tabs").performTouchInput {
            down(center)
            moveBy(Offset(24f, 0f))
            moveBy(Offset((dx - 24f / d) * d, 0f))
        }
        rule.waitForIdle()
    }

    @Test
    fun theVerticalTabBarsSplitterResizesItLiveAndWritesTheWidthOnRelease() {
        setup()
        place(TabStripPosition.LEFT)
        val before = bounds("tab-strip").width
        assertEquals(CHROME_VERTICAL_TAB_BAR_DP, state.settings.verticalTabBarWidthDp)
        pullSplitter(60f)
        val during = bounds("tab-strip").width
        assertTrue(during > before + 40.dp, "the bar follows the pointer: $before -> $during")
        assertEquals(CHROME_VERTICAL_TAB_BAR_DP, state.settings.verticalTabBarWidthDp, "nothing is written while dragging")
        rule.onNodeWithTag("chrome-splitter-tabs").performTouchInput { up() }
        rule.waitForIdle()
        assertNear(during.value - 4f, state.settings.verticalTabBarWidthDp, "the final width is written")
        assertNear(during.value, bounds("tab-strip").width.value, "and the bar stays")
        assertTrue(state.settings.verticalTabBarWidthDp > CHROME_VERTICAL_TAB_BAR_DP + 30f)
    }

    @Test
    fun theSplitterClampsTheWidthAndADoubleClickResetsIt() {
        setup()
        place(TabStripPosition.LEFT)
        pullSplitter(2000f)
        rule.onNodeWithTag("chrome-splitter-tabs").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(480f, state.settings.verticalTabBarWidthDp)
        rule.onNodeWithTag("chrome-splitter-tabs").performTouchInput { doubleClick() }
        rule.waitForIdle()
        assertEquals(CHROME_VERTICAL_TAB_BAR_DP, state.settings.verticalTabBarWidthDp)
        assertNear(CHROME_VERTICAL_TAB_BAR_DP + 4f, bounds("tab-strip").width.value, "back to the default width")
    }

    @Test
    fun theSplitterOfARightBarGrowsTowardsTheContentAndWorksWithTheToolbarElsewhere() {
        setup()
        place(TabStripPosition.RIGHT, ToolbarPlacement.OWN_EDGE, ChromeEdge.TOP)
        val d = rule.density.density
        rule.onNodeWithTag("chrome-splitter-tabs").performTouchInput {
            down(center)
            moveBy(Offset(-24f, 0f))
            moveBy(Offset(-60f * d, 0f))
            up()
        }
        rule.waitForIdle()
        assertTrue(state.settings.verticalTabBarWidthDp > CHROME_VERTICAL_TAB_BAR_DP + 30f, "dragging left widens a bar on the right")
        state.updateSettings { it.copy(chromeLocked = true) }
        rule.waitForIdle()
        assertTrue(exists("chrome-splitter-tabs"), "locking only removes the grips")
    }

    @Test
    fun theToolbarInAVerticalTabBarIsARowAtItsStartOrEnd() {
        setup()
        place(TabStripPosition.LEFT, ToolbarPlacement.WITH_TABS_START)
        val newTab = rule.onNodeWithContentDescription("New tab").getBoundsInRoot()
        val filter = rule.onNodeWithContentDescription("Toggle filter panel").getBoundsInRoot()
        val notes = rule.onNodeWithContentDescription("Toggle notes panel").getBoundsInRoot()
        // The groups wrap whole: the joined toggles may drop to the line under New tab, but they share a line with each other, not a column.
        assertNear(filter.top.value, notes.top.value, "buttons share a line, not a column")
        assertTrue(newTab.top <= filter.top && newTab.left <= filter.left && filter.left < notes.left)
        val strip = bounds("tab-strip")
        assertTrue(bounds("main-toolbar").left >= strip.left && bounds("main-toolbar").right <= strip.right, "inside the bar")
        assertTrue(bounds("main-toolbar").bottom <= bounds("tab-short").top, "at the top of the tab column")
        place(TabStripPosition.LEFT, ToolbarPlacement.WITH_TABS_END)
        assertTrue(bounds("main-toolbar").top >= bounds("tab-mid").bottom, "at the bottom of the tab column")
    }

    @Test
    fun aNarrowTabBarWrapsTheToolbarOntoMoreLines() {
        setup()
        state.updateSettings { it.copy(verticalTabBarWidthDp = 140f) }
        place(TabStripPosition.LEFT, ToolbarPlacement.WITH_TABS_END)
        val newTab = rule.onNodeWithContentDescription("New tab").getBoundsInRoot()
        val settings = rule.onNodeWithText("⚙").getBoundsInRoot()
        assertTrue(settings.top > newTab.top, "Settings wrapped onto a later line: ${newTab.top} / ${settings.top}")
        assertTrue(bounds("main-toolbar").right <= bounds("tab-strip").right)
        // Wide enough for everything on one line.
        state.updateSettings { it.copy(verticalTabBarWidthDp = 480f) }
        rule.waitForIdle()
        val wideNew = rule.onNodeWithContentDescription("New tab").getBoundsInRoot()
        val wideSettings = rule.onNodeWithText("⚙").getBoundsInRoot()
        assertNear(wideNew.top.value, wideSettings.top.value, "one line")
    }

    @Test
    fun aVerticalToolbarBarShowsLabelsWhenTheyAreOnAndGrowsToFitThem() {
        setup()
        place(placement = ToolbarPlacement.OWN_EDGE, edge = ChromeEdge.RIGHT)
        assertNear(44f, bounds("main-toolbar-bar").width.value, "icon-only: a slim column")
        assertTrue(rule.onAllNodesWithText("Filter").fetchSemanticsNodes().isEmpty())
        state.updateSettings { it.copy(toolbarIconOnlyButtons = false) }
        rule.waitForIdle()
        rule.onNodeWithText("Filter").assertExists()
        rule.onNodeWithText("Compare").assertExists()
        assertNear(100f, bounds("main-toolbar-bar").width.value, "the bar grew to fit the labels")
        assertNear(hostWidth - CARD_MARGIN, bounds("main-toolbar-bar").right.value, "still on its edge")
        // On the left too.
        place(placement = ToolbarPlacement.OWN_EDGE, edge = ChromeEdge.LEFT)
        rule.onNodeWithText("Notes").assertExists()
    }

    @Test
    fun barsOffTheDefaultTopBarAreCardsLikeTheLogViewsTiles() {
        setup()
        // The default joined top bar is flat: it starts at the window's corner and its corner pixel is the bar's fill.
        assertEquals(0.dp, bounds("tab-strip").left)
        assertEquals(0.dp, bounds("tab-strip").top)
        val flat = rule.onNodeWithTag("tab-strip").captureToImage().toPixelMap()
        assertEquals(flat[flat.width / 2, 0], flat[0, 0], "a square corner")
        listOf(
            { place(TabStripPosition.LEFT) },
            { place(TabStripPosition.BOTTOM) },
            { place(placement = ToolbarPlacement.OWN_EDGE, edge = ChromeEdge.TOP) },
        ).forEach { arrange ->
            arrange()
            val strip = bounds("tab-strip")
            val gap = minOf(strip.left.value, strip.top.value, hostWidth - strip.right.value, hostHeight - strip.bottom.value)
            assertNear(LOG_VIEW_HOST_PAD_DP.toFloat(), gap, "the tile host's gap")
            val card = rule.onNodeWithTag("tab-strip").captureToImage().toPixelMap()
            assertTrue(card[card.width / 2, 0] != card[0, 0], "a rounded corner like the tiles' (${LOG_VIEW_CHROME_SPEC.cornerDp} dp)")
        }
        // The toolbar's own bar is a card too.
        assertNear(LOG_VIEW_HOST_PAD_DP.toFloat(), bounds("main-toolbar-bar").top.value - bounds("tab-strip").bottom.value, "gapped like a tile")
        val bar = rule.onNodeWithTag("main-toolbar-bar").captureToImage().toPixelMap()
        assertTrue(bar[bar.width / 2, 0] != bar[0, 0])
    }

    // ── The toolbar in the log view's layout ───────────────────────

    @Test
    fun aToolbarInTheLayoutHasNoBarOnALogTabButFallsBackOnTheNewTab() {
        setup { it.updateSettings { s -> s.copy(toolbarPlacement = ToolbarPlacement.IN_LAYOUT) } }
        // A log tab shows the tiles, where the toolbar is: the chrome draws none (and the tab strip is a card without it).
        assertFalse(exists("main-toolbar"))
        assertFalse(exists("chrome-grip-toolbar"))
        assertTrue(exists("tab-strip"))
        assertNear(CARD_MARGIN, bounds("tab-strip").top.value, "the tab bar alone on top is not the default joined bar")
        // The New tab has no tiles: the toolbar is a bar again, at the stored fallback (the default top bar, end of the tabs).
        state.openHomeTab()
        rule.waitForIdle()
        assertTrue(exists("main-toolbar"))
        assertEquals(0.dp, bounds("tab-strip").top, "the long-standing joined bar")
        assertNear(bounds("tab-strip").right.value, bounds("main-toolbar").right.value, "at the end of the tabs")
        assertEquals(ToolbarPlacement.IN_LAYOUT, state.settings.toolbarPlacement, "the choice itself is untouched")
    }

    @Test
    fun theFallbackBarFollowsTheStoredFallbackPlacement() {
        setup {
            it.updateSettings { s ->
                s.copy(toolbarPlacement = ToolbarPlacement.IN_LAYOUT, toolbarFallbackPlacement = ToolbarPlacement.OWN_EDGE, toolbarEdge = ChromeEdge.BOTTOM)
            }
        }
        state.openHomeTab()
        rule.waitForIdle()
        assertNear(hostHeight - CARD_MARGIN, bounds("main-toolbar-bar").bottom.value, "the fallback is the bottom bar")
    }

    // ── The default bar is still the long-standing one ─────────────

    @Test
    fun theDefaultScaffoldPaintsTheSameStripAsTheOneBarTabBar() {
        state = newState()
        state.tabs = tabs
        state.activeTabId = tabs.first().id
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Column(Modifier.width(900.dp)) {
                    Box(Modifier.width(900.dp).testTag("legacy-bar")) { TabBar(state) }
                    Box(Modifier.size(900.dp, 120.dp).testTag("scaffold")) { ChromeScaffold(state) { Box(Modifier.fillMaxSize()) } }
                }
            }
        }
        rule.waitForIdle()
        val legacy = rule.onNodeWithTag("legacy-bar").captureToImage().toPixelMap()
        val scaffold = rule.onNodeWithTag("scaffold").captureToImage().toPixelMap()
        val rows = legacy.height
        assertEquals(legacy.width, scaffold.width)
        var differing = 0
        for (x in 0 until legacy.width) for (y in 0 until rows) if (legacy[x, y] != scaffold[x, y]) differing++
        assertEquals(0, differing, "the scaffold's default strip differs from the one-bar TabBar in $differing pixels")
    }

    // ── Settings ───────────────────────────────────────────────────

    @Test
    fun theSettingsPickThePositionThePlacementTheEdgeAndTheLock() {
        state = newState()
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Column(Modifier.width(700.dp)) { TabsAppearanceSettings(state) }
            }
        }
        rule.waitForIdle()
        // Beside tabs needs a vertical strip: with the strip on top a click on it does nothing.
        rule.onNodeWithTag("toolbar-placement-BESIDE_TABS", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(ToolbarPlacement.WITH_TABS_END, state.settings.toolbarPlacement)
        // The strip's position, in the order Top, Left, Right, Bottom.
        rule.onNodeWithText("Left").performClick()
        rule.waitForIdle()
        assertEquals(TabStripPosition.LEFT, state.settings.tabStripPosition)
        rule.onNodeWithTag("toolbar-placement-BESIDE_TABS", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(ToolbarPlacement.BESIDE_TABS, state.settings.toolbarPlacement)
        rule.onNodeWithTag("toolbar-placement-WITH_TABS_START", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(ToolbarPlacement.WITH_TABS_START, state.settings.toolbarPlacement)
        // The edge appears only for Own edge.
        assertFalse(exists("toolbar-edge"))
        rule.onNodeWithTag("toolbar-placement-OWN_EDGE", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(ToolbarPlacement.OWN_EDGE, state.settings.toolbarPlacement)
        rule.onNodeWithTag("toolbar-edge").assertExists()
        // "Bottom" is both a tab position and an edge choice; the edge's is the second.
        rule.onAllNodesWithText("Bottom")[1].performClick()
        rule.waitForIdle()
        assertEquals(ChromeEdge.BOTTOM, state.settings.toolbarEdge)
        assertFalse(state.settings.chromeLocked)
        rule.onNodeWithTag("chrome-lock", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue(state.settings.chromeLocked)
    }

    @Test
    fun theSettingsPickTheAlignmentTheOrderAndTheLogViewLayoutPlacement() {
        state = newState()
        state.updateSettings { it.copy(toolbarPlacement = ToolbarPlacement.OWN_EDGE, toolbarEdge = ChromeEdge.TOP) }
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides LIGHT_THEME) {
                Column(Modifier.width(700.dp)) { TabsAppearanceSettings(state) }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("toolbar-align").assertExists()
        rule.onNodeWithText("End").performClick()
        rule.waitForIdle()
        assertEquals(ToolbarAlign.END, state.settings.toolbarAlign)
        // The toolbar and the tab strip share the top edge, so the order shows.
        rule.onNodeWithTag("toolbar-order").assertExists()
        rule.onNodeWithText("Before tabs").performClick()
        rule.waitForIdle()
        assertTrue(state.settings.toolbarOuter)
        rule.onNodeWithText("After tabs").performClick()
        rule.waitForIdle()
        assertFalse(state.settings.toolbarOuter)
        // On another edge there is no order to choose.
        rule.onAllNodesWithText("Bottom")[1].performClick()
        rule.waitForIdle()
        assertFalse(exists("toolbar-order"))
        assertTrue(exists("toolbar-align"))
        // In the log view layout: remembers the placement it replaces; the own-bar controls have nothing to say.
        rule.onNodeWithTag("toolbar-placement-IN_LAYOUT", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(ToolbarPlacement.IN_LAYOUT, state.settings.toolbarPlacement)
        assertEquals(ToolbarPlacement.OWN_EDGE, state.settings.toolbarFallbackPlacement)
        assertFalse(exists("toolbar-align"))
        rule.onNodeWithTag("toolbar-placement-WITH_TABS_END", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(ToolbarPlacement.WITH_TABS_END, state.settings.toolbarPlacement)
    }
}
