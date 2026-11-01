// Pixel sizes of the strips under test are literal on purpose.
@file:Suppress("MagicNumber")

package com.indagium

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Offset
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
import com.indagium.ui.holiday.HolidaySceneSlot
import com.indagium.ui.holiday.holidayOuterEdgeInset
import com.indagium.ui.holiday.holidaySceneSlotPx
import com.indagium.ui.holiday.holidaySceneSlotRange
import com.indagium.ui.holiday.holidayTabInset
import com.indagium.ui.holiday.holidayTabShape
import com.indagium.ui.holiday.pumpkin.PumpkinPatchTheme
import com.indagium.ui.holiday.pumpkin.PumpkinTabSceneLayout
import com.indagium.ui.holiday.pumpkin.pumpkinTabSceneLayout
import com.indagium.ui.logViewHolidaySpec
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun assertInside(layout: PumpkinTabSceneLayout, label: String) {
        val slot = layout.slot

        fun circle(name: String, cx: Float, cy: Float, r: Float) {
            assertTrue(
                cx - r >= slot.left - eps && cx + r <= slot.right + eps && cy - r >= slot.top - eps && cy + r <= slot.bottom + eps,
                "$label $name ($cx,$cy,r=$r) escapes $slot",
            )
        }
        layout.moon?.let { circle("moon", it.cx, it.cy, it.r) }
        layout.craters.forEach {
            circle("crater", it.cx, it.cy, it.r)
            val moon = checkNotNull(layout.moon)
            val dx = it.cx - moon.cx
            val dy = it.cy - moon.cy
            assertTrue(kotlin.math.sqrt(dx * dx + dy * dy) + it.r <= moon.r + 0.01f, "$label crater is off the moon")
        }
        layout.pumpkins.forEach {
            assertTrue(
                it.cx - it.rx >= slot.left - eps && it.cx + it.rx <= slot.right + eps &&
                    it.cy - it.ry >= slot.top - eps && it.cy + it.ry <= slot.bottom + eps,
                "$label pumpkin escapes $slot",
            )
        }
        assertTrue(layout.hill.size >= 2, "$label hill needs an outline")
        layout.hill.forEach { assertTrue(it.x in slot.left..slot.right && it.y in slot.top..slot.bottom, "$label hill point $it escapes") }
        assertEquals(slot.left, layout.hill.first().x, "$label hill starts at the slot's start")
        assertEquals(slot.right, layout.hill.last().x, 0.01f, "$label hill ends at the slot's end")
    }

    @Test
    fun theHorizontalSceneStaysInsideTheSlotAndKeepsToTheBottomHills() {
        // A 160 px slot at the end of a 1200 px bar, 36 px tall.
        val slot = Rect(1040f, 0f, 1200f, 36f)
        val layout = pumpkinTabSceneLayout(slot, vertical = false)
        assertInside(layout, "$slot")
        assertTrue(layout.hill.all { it.y >= 36f * 0.5f }, "hills hug the bottom of the strip")
        assertEquals(3, layout.pumpkins.size)
        val moon = checkNotNull(layout.moon)
        assertTrue(layout.pumpkins.all { it.cx > moon.cx }, "the moon sits left of the pumpkins")
        val pair = layout.pumpkins.take(2)
        assertTrue(layout.pumpkins[2].cx > pair.maxOf { it.cx }, "the small pumpkin is furthest right")
    }

    @Test
    fun theHorizontalSceneStaysInsideSlotsOfOtherSizes() {
        listOf(
            Rect(0f, 0f, 160f, 24f), Rect(300f, 0f, 460f, 48f), Rect(50f, 0f, 2450f, 36f), Rect(0f, 0f, 90f, 36f), Rect(0f, 0f, 160f, 60f),
        ).forEach { assertInside(pumpkinTabSceneLayout(it, vertical = false), "$it") }
    }

    @Test
    fun theVerticalSceneStaysInsideTheSlotUnderTheTabs() {
        // 200 px wide bar, the tabs fill the first 300 px, the free slot is below them.
        val slot = Rect(0f, 300f, 200f, 800f)
        val layout = pumpkinTabSceneLayout(slot, vertical = true)
        assertInside(layout, "$slot")
        assertTrue(layout.hill.all { it.y >= slot.bottom - 72f }, "the hill band is a short strip along the bottom of the slot")
        assertEquals(1, layout.pumpkins.size)
        assertTrue(layout.moon != null)
    }

    @Test
    fun theVerticalSceneKeepsOnlyHillsWhenTheTabsReachTheBand() {
        val slot = Rect(0f, 760f, 200f, 800f)
        val layout = pumpkinTabSceneLayout(slot, vertical = true)
        assertInside(layout, "$slot")
        assertEquals(null, layout.moon)
        assertTrue(layout.pumpkins.isEmpty() && layout.craters.isEmpty())
    }

    @Test
    fun theVerticalSceneStaysInsideOtherSlots() {
        listOf(Rect(0f, 0f, 120f, 300f), Rect(10f, 100f, 370f, 1500f)).forEach { assertInside(pumpkinTabSceneLayout(it, vertical = true), "$it") }
    }

    @Test
    fun theSlotIsReservedOnlyWhileThePackIsOnAndTheTabsKeepTheirMinimumWidth() {
        // 4 tabs of at least 80 px need 320 px; a 160 px slot fits in a 600 px tab area.
        assertEquals(160f, holidaySceneSlotPx(600f, 320f, 160f, active = true))
        // Exactly enough room still reserves it.
        assertEquals(160f, holidaySceneSlotPx(480f, 320f, 160f, active = true))
        // One px short: the tabs would drop below their minimum, so the scene has no slot.
        assertEquals(0f, holidaySceneSlotPx(479f, 320f, 160f, active = true))
        assertEquals(0f, holidaySceneSlotPx(0f, 0f, 160f, active = true))
        // Without a pack nothing is reserved.
        assertEquals(0f, holidaySceneSlotPx(600f, 320f, 160f, active = false))
    }

    @Test
    fun theSceneRangeIsTheFreeSpaceAfterTheLastTabAndNeedsTheMinimumWidth() {
        assertEquals(440f..600f, holidaySceneSlotRange(600f, 440f, 160f))
        assertEquals(100f..600f, holidaySceneSlotRange(600f, 100f, 160f))
        assertEquals(null, holidaySceneSlotRange(600f, 450f, 160f))
        assertEquals(null, holidaySceneSlotRange(600f, 600f, 160f))
        // A vertical strip needs only a pixel.
        assertEquals(500f..600f, holidaySceneSlotRange(600f, 500f, 1f))
    }

    @Test
    fun theSlotIsStretchedOverTheBarsCrossAxisAndCutToTheBar() {
        val holder = HolidaySceneSlot()
        assertEquals(null, holder.localRect(Size(1000f, 36f), vertical = false), "no slot reported, nothing to draw")
        holder.barOrigin = Offset(10f, 20f)
        // A tab area inset 1 px in the bar, ending before the toolbar at x = 700 (root).
        holder.rootRect = Rect(550f, 21f, 700f, 55f)
        assertEquals(Rect(540f, 0f, 690f, 36f), holder.localRect(Size(1000f, 36f), vertical = false))
        // The same slot in a bar that ends first is cut to it.
        assertEquals(Rect(540f, 0f, 600f, 36f), holder.localRect(Size(600f, 36f), vertical = false))
        holder.rootRect = Rect(11f, 320f, 190f, 800f)
        assertEquals(Rect(0f, 300f, 200f, 780f), holder.localRect(Size(200f, 780f), vertical = true))
        holder.rootRect = null
        assertEquals(null, holder.localRect(Size(200f, 780f), vertical = true))
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
