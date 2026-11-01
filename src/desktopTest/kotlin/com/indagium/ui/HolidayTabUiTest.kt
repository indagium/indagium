package com.indagium.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.indagium.model.AppSettings
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.model.LogTab
import com.indagium.model.TabSizeMode
import com.indagium.model.TabStripStyle
import com.indagium.ui.holiday.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The real [TabBar] under a seasonal pack: only the active tab gets the stem, and the user's tab style is never rewritten. */
@OptIn(ExperimentalTestApi::class)
class HolidayTabUiTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var directory: File
    private lateinit var state: AppState

    @After
    fun tearDown() {
        if (::state.isInitialized) state.close()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    private fun tab(id: String): LogTab = mkTab(id, "$id.log", emptyList()).copy(sourcePath = "/logs/$id/$id.log")

    private fun setup(look: HolidayLook?, style: TabStripStyle = TabStripStyle.CURRENT, widthDp: Int = 900, count: Int = 3) {
        directory = createTempDirectory("holiday-tab-ui").toFile()
        state = AppState(
            autosaveFile = File(directory, "state.cache"),
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archives"),
            customCommandsDir = File(directory, "commands"),
            autoExportNotes = false,
        )
        state.tabs = if (count == 3) listOf(tab("a"), tab("b"), tab("c")) else (1..count).map { tab("t$it") }
        state.activeTabId = "b"
        state.updateSettings { it.copy(tabStripStyle = style) }
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides (look?.colors ?: LIGHT_THEME), LocalHoliday provides look) {
                Box(Modifier.width(widthDp.dp)) { TabBar(state) }
            }
        }
        rule.waitForIdle()
    }

    private val pumpkin = resolveHolidayLook(AppSettings().withHoliday { it.copy(mode = HolidayMode.ALWAYS) }, LocalDate.of(2026, 10, 31))

    @Test
    fun onlyTheActiveTabGetsAStemUnderThePack() {
        assertNotNull(pumpkin)
        setup(pumpkin)
        rule.onNodeWithTag("tab-stem-b").assertExists()
        rule.onAllNodesWithTag("tab-stem-a").assertCountEquals(0)
        rule.onAllNodesWithTag("tab-stem-c").assertCountEquals(0)
        rule.onNodeWithTag("tab-b").assertExists()
    }

    @Test
    fun theStemFollowsTheActiveTab() {
        setup(pumpkin)
        rule.runOnIdle { state.activeTabId = "c" }
        rule.waitForIdle()
        rule.onNodeWithTag("tab-stem-c").assertExists()
        rule.onAllNodesWithTag("tab-stem-b").assertCountEquals(0)
    }

    @Test
    fun noStemWithoutAPackAndTheUsersStyleIsUntouched() {
        setup(null, TabStripStyle.SEGMENTED)
        rule.onAllNodesWithTag("tab-stem-b").assertCountEquals(0)
        assertEquals(TabStripStyle.SEGMENTED, state.settings.tabStripStyle)
    }

    @Test
    fun thePackLeavesTheTabStripStyleSettingAlone() {
        setup(pumpkin, TabStripStyle.RAISED)
        assertEquals(TabStripStyle.RAISED, state.settings.tabStripStyle)
        rule.onNodeWithTag("tab-stem-b").assertIsDisplayed()
    }

    @Test
    fun manorGroupOutlineFollowsTheRoundedTabShape() {
        val look = resolveHolidayLook(AppSettings().withHoliday { it.copy(mode = HolidayMode.ALWAYS, pack = HolidayPack.HAUNTED_MANOR) }, LocalDate.of(2026, 10, 31))!!
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides look.colors, LocalHoliday provides look) {
                Box(Modifier.width(200.dp).background(Color.Black).testTag("group-test")) {
                    TabShell("group", "Group", "Group", false, false, onClick = {}, onClose = {},
                        appearance = TabAppearance(outlineColor = Color.Red))
                }
            }
        }
        val pixels = rule.onNodeWithTag("group-test").captureToImage().toPixelMap()
        val density = rule.density.density
        fun pixel(x: Float, y: Float) = pixels[(x * density).toInt(), (y * density).toInt()]
        val corner = pixel(0f, 6f)
        val top = pixel(100f, 6f)
        assertTrue(corner.red < .5f, "the group outline must not form a square at the rounded corner")
        assertTrue(top.red > .5f && top.green < .3f, "the group outline follows the rounded tab's top edge")
    }

    private fun tabWidthDp(id: String): Float = rule.onNodeWithTag("tab-$id").getUnclippedBoundsInRoot().let { (it.right - it.left).value }

    private fun areaWidthDp(): Float = rule.onNodeWithTag("tab-strip-drag-idle").getUnclippedBoundsInRoot().let { (it.right - it.left).value }

    @Test
    fun withoutAPackFixedTabsShareTheWholeTabArea() {
        setup(null, widthDp = WIDE_DP)
        assertTrue(abs(tabWidthDp("a") * 3 - areaWidthDp()) < TOLERANCE_DP, "plain tabs share the area: ${tabWidthDp("a") * 3} vs ${areaWidthDp()}")
    }

    @Test
    fun aFixedStripLeavesTheScenesSlotAtTheEndUnderThePack() {
        setup(pumpkin, widthDp = WIDE_DP)
        val area = rule.onNodeWithTag("tab-strip-drag-idle").getUnclippedBoundsInRoot()
        val right = rule.onNodeWithTag("tab-c").getUnclippedBoundsInRoot().right
        assertTrue((area.right - right).value >= HOLIDAY_SCENE_SLOT_DP - TOLERANCE_DP, "last tab ends ${(area.right - right).value} dp before the end")
        assertTrue(abs(tabWidthDp("a") * 3 - ((area.right - area.left).value - HOLIDAY_SCENE_SLOT_DP)) < TOLERANCE_DP, "tabs share what is left")
    }

    @Test
    fun noSlotIsKeptWhenTheTabsWouldDropBelowTheirMinimumWidth() {
        setup(pumpkin, widthDp = WIDE_DP)
        val areaDp = areaWidthDp()
        // As many tabs as fit at 80 dp each, but not once the slot is taken off the area.
        val count = ((areaDp - HOLIDAY_SCENE_SLOT_DP) / MIN_TAB_DP).toInt() + 1
        assertTrue(count * MIN_TAB_DP <= areaDp, "the tabs fit the whole area")
        rule.runOnIdle {
            state.updateSettings { it.copy(visibleTabLimit = count) }
            state.tabs = (1..count).map { tab("t$it") }
            state.activeTabId = "t1"
        }
        rule.waitForIdle()
        val area = rule.onNodeWithTag("tab-strip-drag-idle").getUnclippedBoundsInRoot()
        val last = rule.onNodeWithTag("tab-t$count").getUnclippedBoundsInRoot()
        assertTrue((area.right - last.right).value < HOLIDAY_SCENE_SLOT_DP, "no slot was reserved")
        assertTrue(tabWidthDp("t1") >= MIN_TAB_DP - TOLERANCE_DP, "the tabs keep their minimum width")
    }

    @Test
    fun aFitStripNeedsNoReservation() {
        setup(pumpkin, widthDp = WIDE_DP)
        rule.runOnIdle { state.updateSettings { it.copy(tabSizeMode = TabSizeMode.FIT) } }
        rule.waitForIdle()
        // Tabs take their label's width, so the free space after the last one is the slot.
        val area = rule.onNodeWithTag("tab-strip-drag-idle").getUnclippedBoundsInRoot()
        val last = rule.onNodeWithTag("tab-c").getUnclippedBoundsInRoot()
        assertTrue((area.right - last.right).value >= HOLIDAY_SCENE_SLOT_DP, "the free space after the tabs is the slot")
    }

    private companion object {
        const val TOLERANCE_DP = 8f
        const val WIDE_DP = 1600
        const val MIN_TAB_DP = 80f
    }
}
