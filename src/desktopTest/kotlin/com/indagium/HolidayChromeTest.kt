// Pixel sizes of the strips under test are literal on purpose.
@file:Suppress("MagicNumber")

package com.indagium

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.indagium.model.TabStripPosition
import com.indagium.ui.LOG_VIEW_CHROME_SPEC
import com.indagium.ui.holiday.*
import com.indagium.ui.holiday.HOLIDAY_TAB_OUTER_INSET
import com.indagium.ui.holiday.HOLIDAY_TAB_RADIUS
import com.indagium.ui.holiday.HolidayCorners
import com.indagium.ui.holiday.holidayOuterEdgeInset
import com.indagium.ui.holiday.holidayTabInset
import com.indagium.ui.holiday.holidayTabShape
import com.indagium.ui.holiday.pumpkin.PumpkinPatchTheme
import com.indagium.ui.holiday.pumpkin.pumpkinTabSceneLayout
import com.indagium.ui.logViewHolidaySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HolidayChromeTest {
    private val eps = 0.01f
    private val density = Density(1f)

    private data class Corners(val topStart: Float, val topEnd: Float, val bottomEnd: Float, val bottomStart: Float)

    private fun corners(position: TabStripPosition): Corners {
        val outline = holidayTabShape(position).createOutline(Size(200f, 100f), LayoutDirection.Ltr, density) as Outline.Rounded
        val r = outline.roundRect
        return Corners(r.topLeftCornerRadius.x, r.topRightCornerRadius.x, r.bottomRightCornerRadius.x, r.bottomLeftCornerRadius.x)
    }

    @Test
    fun tabCornersAreRoundedOnlyOnTheSideAwayFromTheContent() {
        val radius = HOLIDAY_TAB_RADIUS.value
        assertEquals(14f, radius)
        assertEquals(Corners(radius, radius, 0f, 0f), corners(TabStripPosition.TOP))
        assertEquals(Corners(0f, 0f, radius, radius), corners(TabStripPosition.BOTTOM))
        assertEquals(Corners(radius, 0f, 0f, radius), corners(TabStripPosition.LEFT))
        assertEquals(Corners(0f, radius, radius, 0f), corners(TabStripPosition.RIGHT))
    }

    @Test
    fun theExtraInsetIsOnTheOuterSideOnly() {
        val base = PaddingValues(start = 1.dp, top = 4.dp, end = 2.dp, bottom = 3.dp)
        val extra = HOLIDAY_TAB_OUTER_INSET

        fun sides(p: PaddingValues) = listOf(
            p.calculateLeftPadding(LayoutDirection.Ltr), p.calculateTopPadding(), p.calculateRightPadding(LayoutDirection.Ltr), p.calculateBottomPadding(),
        )
        val before = sides(base)
        val expected = mapOf(
            TabStripPosition.LEFT to listOf(before[0] + extra, before[1], before[2], before[3]),
            TabStripPosition.TOP to listOf(before[0], before[1] + extra, before[2], before[3]),
            TabStripPosition.RIGHT to listOf(before[0], before[1], before[2] + extra, before[3]),
            TabStripPosition.BOTTOM to listOf(before[0], before[1], before[2], before[3] + extra),
        )
        TabStripPosition.entries.forEach { position ->
            assertEquals(expected.getValue(position), sides(holidayTabInset(base, position)), "$position")
            val outer = when (position) {
                TabStripPosition.LEFT -> before[0]
                TabStripPosition.TOP -> before[1]
                TabStripPosition.RIGHT -> before[2]
                TabStripPosition.BOTTOM -> before[3]
            }
            assertEquals(outer + extra, holidayOuterEdgeInset(holidayTabInset(base, position), position), "$position outer edge")
        }
    }

    private fun band(width: Float, height: Float = HOLIDAY_SCENE_REFERENCE_HEIGHT_DP) = Rect(0f, 0f, width, height)

    @Test
    fun theSceneShowsOnlyForASceneOnAHorizontalTopStrip() {
        assertTrue(holidaySceneShown(sceneOn = true, tabPosition = TabStripPosition.TOP))
        assertFalse(holidaySceneShown(sceneOn = false, tabPosition = TabStripPosition.TOP))
        listOf(TabStripPosition.BOTTOM, TabStripPosition.LEFT, TabStripPosition.RIGHT).forEach {
            assertFalse(holidaySceneShown(sceneOn = true, tabPosition = it), "$it")
        }
    }

    @Test
    fun theCompositionIsAuthoredOnA1600By30BarAndScalesWithTheBarHeight() {
        assertEquals(30f, HOLIDAY_SCENE_REFERENCE_HEIGHT_DP)
        assertEquals(1600f, HOLIDAY_SCENE_REFERENCE_WIDTH)
        val space = HolidayBandSpace(band(1600f))
        assertEquals(1f, space.unit)
        assertEquals(0f, space.shift, "a 1600 band shows the reference as authored")
        assertEquals(1.2f, HolidayBandSpace(band(1000f, 36f)).unit, 0.001f, "a taller tab bar scales the composition to its height")
    }

    @Test
    fun elementsAreRightAlignedAtNaturalScale() {
        val ref = Rect(1000f, 4f, 1100f, 26f)
        assertEquals(Rect(1000f, 4f, 1100f, 26f), HolidayBandSpace(band(1600f)).fit(ref))
        assertEquals(Rect(1400f, 4f, 1500f, 26f), HolidayBandSpace(band(2000f)).fit(ref), "a wider band moves the element with the right edge")
        assertEquals(Rect(400f, 4f, 500f, 26f), HolidayBandSpace(band(1000f)).fit(ref), "a narrower one keeps its size")
        assertEquals(null, HolidayBandSpace(band(550f)).fit(ref), "an element that does not fit whole is left out")
    }

    @Test
    fun everySceneKeepsItsElementsInsideTheBandAtSeveralWidths() {
        HOLIDAY_THEMES.values.mapNotNull { t -> t.tabScene?.let { t to it } }.forEach { (theme, painter) ->
            listOf(300f, 700f, 1000f, 1600f, 2600f).forEach { width ->
                listOf(30f, 45f, 60f).forEach { height ->
                    val b = band(width, height)
                    val layout = painter.layout(b)
                    assertEquals(b, layout.band)
                    layout.elementBounds.forEach {
                        val inside = it.left >= b.left - eps && it.right <= b.right + eps && it.top >= b.top - eps && it.bottom <= b.bottom + eps
                        assertTrue(inside, "${theme.pack} $it escapes $b")
                    }
                }
            }
        }
    }

    @Test
    fun theWideBandsMiddleStaysClear() {
        // Every scene keeps its pieces in the right half of a 1600 band (the candy bunting runs the whole width but leaves the middle).
        HOLIDAY_THEMES.values.mapNotNull { it.tabScene }.forEach { painter ->
            val layout = painter.layout(band(1600f))
            assertTrue(layout.elementBounds.isNotEmpty())
            layout.elementBounds.forEach { assertTrue(it.right <= 800f || it.left >= 800f, "an element sits over the middle: $it") }
        }
    }

    @Test
    fun thePumpkinSceneHoldsTheMoonAndThreePumpkinsAtTheRight() {
        val layout = pumpkinTabSceneLayout(band(1600f))
        assertTrue(layout.has("moon") && layout.has("pumpkins"))
        assertTrue(pumpkinTabSceneLayout(band(250f)).elementBounds.isEmpty(), "a narrow band leaves the elements out and keeps the hills")
    }

    @Test
    fun theLogViewTilesAreRounderUnderThePackAndOtherwiseIdentical() {
        assertEquals(8, LOG_VIEW_CHROME_SPEC.cornerDp)
        val spec = logViewHolidaySpec(PumpkinPatchTheme.corners.tileDp)
        assertEquals(12, spec.cornerDp)
        assertEquals(LOG_VIEW_CHROME_SPEC, spec.copy(cornerDp = LOG_VIEW_CHROME_SPEC.cornerDp))
        assertSame(spec, logViewHolidaySpec(12), "one stable instance per radius")
    }

    @Test
    fun theToolbarCornerIs14UnderThePack() {
        assertEquals(14, PumpkinPatchTheme.corners.toolbarDp)
        assertEquals(HolidayCorners.DEFAULT, PumpkinPatchTheme.corners, "the pumpkin patch keeps the framework defaults")
    }
}
