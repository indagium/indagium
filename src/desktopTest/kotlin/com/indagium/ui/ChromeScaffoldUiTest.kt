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
    fun aLeftStripStacksTheTabsAtTheLeftEdge() {
        setup()
        place(TabStripPosition.LEFT)
        val strip = bounds("tab-strip")
        assertEquals(0.dp, strip.left)
        assertEquals(CHROME_VERTICAL_TAB_BAR_DP.dp, strip.width)
        assertEquals(hostHeight.dp, strip.height)
        assertEquals(CHROME_VERTICAL_TAB_BAR_DP.dp, bounds("content").left)
        assertEquals(hostWidth.dp, bounds("content").right)
        // Tabs top to bottom, each one style height (36) tall, inside the bar's 1 dp padding.
        assertEquals(bounds("tab-short").bottom, bounds("tab-long").top)
        assertEquals(bounds("tab-long").bottom, bounds("tab-mid").top)
        assertEquals(36.dp, bounds("tab-short").height)
        assertEquals(1.dp, bounds("tab-short").left)
        // The toolbar is a column at the bottom of the same bar.
        assertTrue(bounds("main-toolbar").height < bounds("tab-strip").height)
        assertNear(strip.bottom.value, bounds("main-toolbar").bottom.value, "the toolbar ends the bar")
        assertTrue(bounds("main-toolbar").top > bounds("tab-mid").bottom, "the toolbar is under the tabs")
    }

    @Test
    fun aRightStripSitsAtTheRightEdge() {
        setup()
        place(TabStripPosition.RIGHT)
        val strip = bounds("tab-strip")
        assertEquals(hostWidth.dp, strip.right)
        assertEquals(CHROME_VERTICAL_TAB_BAR_DP.dp, strip.width)
        assertEquals(0.dp, bounds("content").left)
        assertEquals((hostWidth - CHROME_VERTICAL_TAB_BAR_DP.toInt()).dp, bounds("content").right)
    }

    @Test
    fun aBottomStripSitsUnderTheContent() {
        setup()
        place(TabStripPosition.BOTTOM)
        val strip = bounds("tab-strip")
        assertEquals(hostHeight.dp, strip.bottom)
        assertEquals(36.dp, strip.height)
        assertEquals(0.dp, bounds("content").top)
        assertEquals((hostHeight - 36).dp, bounds("content").bottom)
        assertNear(strip.right.value, bounds("main-toolbar").right.value, "still at the end")
    }

    @Test
    fun besideTabsMakesATallToolbarBarNextToAVerticalStrip() {
        setup()
        place(TabStripPosition.LEFT, ToolbarPlacement.BESIDE_TABS)
        assertEquals((CHROME_VERTICAL_TAB_BAR_DP + 40f).dp, bounds("content").left)
        assertEquals(CHROME_VERTICAL_TAB_BAR_DP.dp, bounds("main-toolbar-bar").left)
        assertEquals(40.dp, bounds("main-toolbar-bar").width)
        // The buttons are stacked: same left, ascending tops.
        val newTab = rule.onNodeWithContentDescription("New tab").getBoundsInRoot()
        val filter = rule.onNodeWithContentDescription("Toggle filter panel").getBoundsInRoot()
        val notes = rule.onNodeWithContentDescription("Toggle notes panel").getBoundsInRoot()
        assertTrue(newTab.top < filter.top && filter.top < notes.top)
        assertNear(filter.left.value, notes.left.value, "one column")
        // The tabs have the whole of their bar: the toolbar is not in it.
        assertTrue(bounds("tab-strip").width == CHROME_VERTICAL_TAB_BAR_DP.dp)
    }

    @Test
    fun anOwnEdgeToolbarSitsOnItsEdge() {
        setup()
        place(placement = ToolbarPlacement.OWN_EDGE, edge = ChromeEdge.BOTTOM)
        assertEquals(hostHeight.dp, bounds("main-toolbar-bar").bottom)
        assertEquals(36.dp, bounds("tab-strip").height)
        assertEquals(0.dp, bounds("tab-strip").top)
        assertEquals(36.dp, bounds("content").top)
        assertEquals((hostHeight - 36).dp, bounds("content").bottom)
        // Alone on its bar the toolbar starts at the bar's start.
        assertEquals(0.dp, bounds("main-toolbar").left)
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
        assertFalse(exists("chrome-dock-bottom"))
        dragGripTo("chrome-grip-toolbar", "chrome-dock-bottom")
        listOf("top", "bottom", "left", "right", "tabs-start", "tabs-end").forEach { assertTrue(exists("chrome-dock-$it"), "dock strip $it") }
        assertFalse(exists("chrome-dock-beside-tabs"), "beside tabs needs a vertical strip")
        assertEquals(before, state.settings, "nothing is written while dragging")
        rule.onNodeWithTag("chrome-grip-toolbar").performTouchInput { up() }
        rule.waitForIdle()
        assertFalse(exists("chrome-dock-bottom"), "the strips go away on release")
        assertEquals(ToolbarPlacement.OWN_EDGE, state.settings.toolbarPlacement)
        assertEquals(ChromeEdge.BOTTOM, state.settings.toolbarEdge)
        assertEquals(TabStripPosition.TOP, state.settings.tabStripPosition)
        assertEquals(hostHeight.dp, bounds("main-toolbar-bar").bottom)
    }

    @Test
    fun draggingTheTabsToTheLeftEdgeMovesTheStripAndTheToolbarWithIt() {
        setup()
        dragGripTo("chrome-grip-tabs", "chrome-dock-left")
        listOf("top", "bottom", "left", "right").forEach { assertTrue(exists("chrome-dock-$it"), "dock strip $it") }
        assertFalse(exists("chrome-dock-tabs-start"), "the tab strip is not dropped on itself")
        rule.onNodeWithTag("chrome-grip-tabs").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(TabStripPosition.LEFT, state.settings.tabStripPosition)
        assertEquals(ToolbarPlacement.WITH_TABS_END, state.settings.toolbarPlacement)
        assertEquals(0.dp, bounds("tab-strip").left)
        assertEquals(CHROME_VERTICAL_TAB_BAR_DP.dp, bounds("tab-strip").width)
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
}
