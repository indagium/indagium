package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.indagium.model.AppSettings
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.model.ShapeStyle
import com.indagium.model.TabStripPosition
import com.indagium.model.TabStripStyle
import com.indagium.ui.holiday.HolidayLook
import com.indagium.ui.holiday.LocalHoliday
import com.indagium.ui.holiday.resolveHolidayLook
import com.indagium.ui.holiday.withHoliday
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The Shape style's tab corner and "My tab style" under a seasonal pack, on the real [TabShell] and [TabBar]. */
@OptIn(ExperimentalTestApi::class)
class ShapeStyleTabsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private var directory: File? = null
    private var state: AppState? = null

    @After
    fun tearDown() {
        state?.close()
        directory?.deleteRecursively()
    }

    private val day = LocalDate.parse("2026-10-31")

    private companion object {
        const val STRIP_PAD_DP = 2
        const val RAISED_GAP_DP = 4
    }

    private fun lookFor(pack: HolidayPack = HolidayPack.PUMPKIN_PATCH, own: Boolean = false): HolidayLook {
        val settings = AppSettings().withHoliday {
            it.copy(mode = HolidayMode.ALWAYS, pack = pack, ownTabStylePacks = if (own) setOf(pack) else emptySet())
        }
        return checkNotNull(resolveHolidayLook(settings, day))
    }

    // One active tab, drawn alone on a magenta backdrop, so a corner pixel that is still magenta is outside the tab's shape.
    private fun render(look: HolidayLook?, shapeStyle: ShapeStyle, tabStyle: TabStripStyle) {
        rule.setContent {
            CompositionLocalProvider(
                LocalTheme provides (look?.colors ?: LIGHT_THEME),
                LocalHoliday provides look,
                LocalUiCorners provides resolveUiCorners(shapeStyle, look),
            ) {
                Box(Modifier.width(200.dp).background(Color.Magenta).testTag("host")) {
                    TabShell(
                        "k", "Tab", "Tab", true, false, onClick = {}, onClose = {},
                        appearance = TabAppearance(style = tabStyle, position = TabStripPosition.TOP),
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun pixelAt(dpX: Float, dpY: Float): Color {
        val px = rule.onNodeWithTag("tab-k").captureToImage().toPixelMap()
        val d = rule.density.density
        return px[(dpX * d).toInt(), (dpY * d).toInt()]
    }

    private fun outside(dpX: Float, dpY: Float) = pixelAt(dpX, dpY) == Color.Magenta

    // The arc of a radius r passes the diagonal at r * 0.29 from the corner: (1,1) is outside any radius of 4 dp or more, (3,3) is outside
    // from about 10 dp, (7,7) is inside up to 24 dp.
    @Test
    fun raisedWithNoPackKeepsItsOwnEightDpUnderThemeDefault() {
        render(null, ShapeStyle.THEME, TabStripStyle.RAISED)
        assertTrue(outside(1f, 1f))
        assertFalse(outside(3f, 3f), "8 dp: the arc is 2.3 dp in on the diagonal")
    }

    @Test
    fun squareMakesRaisedTabsSquareWithNoPack() {
        render(null, ShapeStyle.SQUARE, TabStripStyle.RAISED)
        assertFalse(outside(0.5f, 0.5f))
        assertFalse(outside(1f, 1f))
    }

    @Test
    fun roundMakesRaisedTabsFourteenDp() {
        render(null, ShapeStyle.ROUND, TabStripStyle.RAISED)
        assertTrue(outside(3f, 3f), "14 dp: the arc is 4.1 dp in on the diagonal")
        assertFalse(outside(5f, 5f))
    }

    @Test
    fun tabShapeTakesTheStyleCornerForRoundedStylesAndLeavesTheOthers() {
        assertEquals(tabShape(TabStripStyle.RAISED, TabStripPosition.TOP), tabShape(TabStripStyle.RAISED, TabStripPosition.TOP, null))
        assertEquals(RectangleShape, tabShape(TabStripStyle.RAISED, TabStripPosition.TOP, 0f))
        assertEquals(RectangleShape, tabShape(TabStripStyle.SEGMENTED, TabStripPosition.LEFT, 0f))
        assertEquals(RectangleShape, tabShape(TabStripStyle.OUTLINED, TabStripPosition.BOTTOM, 0f))
        assertNotEquals(tabShape(TabStripStyle.SEGMENTED, TabStripPosition.TOP), tabShape(TabStripStyle.SEGMENTED, TabStripPosition.TOP, 14f))
        // The pill keeps its capsule and the flat styles stay flat, whatever the corner.
        listOf(null, 0f, 6f, 14f).forEach { corner ->
            assertEquals(tabShape(TabStripStyle.PILL, TabStripPosition.TOP), tabShape(TabStripStyle.PILL, TabStripPosition.TOP, corner))
            listOf(TabStripStyle.CURRENT, TabStripStyle.QUIET, TabStripStyle.COMPACT, TabStripStyle.TYPED).forEach {
                assertEquals(RectangleShape, tabShape(it, TabStripPosition.TOP, corner), "$it")
            }
        }
    }

    @Test
    fun theUserCornerOnlyAppliesWhenAShapeStyleIsChosen() {
        val look = lookFor()
        assertEquals(null, resolveUiCorners(ShapeStyle.THEME, null).ownTabDp)
        assertEquals(null, resolveUiCorners(ShapeStyle.THEME, look).ownTabDp, "a pack's own tab radius never reaches the user's tab style")
        assertEquals(0f, resolveUiCorners(ShapeStyle.SQUARE, look).ownTabDp)
    }

    private fun assertPillCapsule(style: ShapeStyle) {
        render(null, style, TabStripStyle.PILL)
        assertTrue(outside(1f, 1f), "$style")
        assertTrue(outside(3f, 3f), "$style: a capsule's arc is far in")
    }

    @Test
    fun pillIsACapsuleUnderThemeDefault() = assertPillCapsule(ShapeStyle.THEME)

    @Test
    fun pillIsACapsuleUnderSquare() = assertPillCapsule(ShapeStyle.SQUARE)

    @Test
    fun pillIsACapsuleUnderRound() = assertPillCapsule(ShapeStyle.ROUND)

    @Test
    fun aPackTabKeepsThePacksFourteenDpUnderThemeDefaultAndSquareMakesItSquare() {
        render(lookFor(), ShapeStyle.THEME, TabStripStyle.CURRENT)
        assertTrue(outside(3f, 3f), "the pumpkin patch's 14 dp tab")
    }

    @Test
    fun squareMakesTheGenericPackTabSquare() {
        render(lookFor(), ShapeStyle.SQUARE, TabStripStyle.CURRENT)
        assertFalse(outside(0.5f, 0.5f))
    }

    @Test
    fun roundGivesTheGenericPackTabFourteenDpAndSoftSix() {
        render(lookFor(), ShapeStyle.ROUND, TabStripStyle.CURRENT)
        assertTrue(outside(3f, 3f))
        assertFalse(outside(5f, 5f))
    }

    @Test
    fun softGivesTheGenericPackTabSixDp() {
        render(lookFor(), ShapeStyle.SOFT, TabStripStyle.CURRENT)
        assertTrue(outside(0.5f, 0.5f))
        assertFalse(outside(3f, 3f), "6 dp: the arc is 1.8 dp in on the diagonal")
    }

    @Test
    fun aPacksOwnSilhouetteKeepsItsShapeUnderSquare() {
        val manor = lookFor(HolidayPack.HAUNTED_MANOR)
        assertTrue(manor.theme.tabShape != null, "the manor draws its own tombstone")
        render(manor, ShapeStyle.THEME, TabStripStyle.CURRENT)
        val before = pixelAt(0.5f, 0.5f)
        render(manor, ShapeStyle.SQUARE, TabStripStyle.CURRENT)
        assertEquals(before, pixelAt(0.5f, 0.5f))
    }

    @Test
    fun myTabStyleUnderAPackDrawsTheUsersRaisedCardInThePacksColours() {
        val look = lookFor(own = true)
        assertTrue(look.ownTabStyle)
        render(look, ShapeStyle.THEME, TabStripStyle.RAISED)
        // The raised card's own 8 dp (not the pack's 14), filled with the pack's background.
        assertTrue(outside(1f, 1f))
        assertFalse(outside(3f, 3f), "8 dp, not the pack's 14")
        assertEquals(look.colors.bg, pixelAt(150f, 12f))
        rule.onAllNodesWithTag("tab-stem-k").assertCountEquals(0)
    }

    @Test
    fun withoutMyTabStyleThePackTabStillHasItsStemAndShape() {
        val look = lookFor(own = false)
        assertFalse(look.ownTabStyle)
        render(look, ShapeStyle.THEME, TabStripStyle.RAISED)
        rule.onNodeWithTag("tab-stem-k").assertExists()
        assertTrue(outside(3f, 3f), "the pack's 14 dp, whatever the user's style")
    }

    @Test
    fun squareShapeStyleSquaresMyTabStyleToo() {
        render(lookFor(own = true), ShapeStyle.SQUARE, TabStripStyle.RAISED)
        assertFalse(outside(0.5f, 0.5f))
    }

    @Test
    fun myTabStyleInTheStripTakesTheStylesInsetAndNoStem() {
        val raisedHeight = tabStripHeightDp(TabStripStyle.RAISED)

        fun activeHeightWith(own: Boolean): Pair<Float, Boolean> {
            directory = createTempDirectory("shape-style-tabs").toFile()
            val s = AppState(
                autosaveFile = File(directory, "state.cache"),
                notesDir = File(directory, "notes"),
                archiveCacheDir = File(directory, "archives"),
                customCommandsDir = File(directory, "commands"),
                autoExportNotes = false,
            )
            state = s
            s.tabs = listOf(mkTab("a", "a.log", emptyList()), mkTab("b", "b.log", emptyList()))
            s.activeTabId = "b"
            s.updateSettings { it.copy(tabStripStyle = TabStripStyle.RAISED) }
            val look = lookFor(own = own)
            rule.setContent {
                CompositionLocalProvider(LocalTheme provides look.colors, LocalHoliday provides look) {
                    Box(Modifier.width(600.dp)) { TabBar(s) }
                }
            }
            rule.waitForIdle()
            val height = rule.onNodeWithTag("tab-b").getBoundsInRoot().height.value
            val stem = rule.onAllNodesWithTag("tab-stem-b").fetchSemanticsNodes().isNotEmpty()
            state?.close()
            state = null
            directory?.deleteRecursively()
            return height to stem
        }
        // The strip's 1 dp above and below, then the raised style's 4 dp gap above the card; the pack's own tab would add its outer inset.
        val (ownHeight, ownStem) = activeHeightWith(own = true)
        assertEquals((raisedHeight - STRIP_PAD_DP - RAISED_GAP_DP).toFloat(), ownHeight, 1.5f)
        assertFalse(ownStem, "no pumpkin stem on My tab style")
    }
}
