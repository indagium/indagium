// Dp sizes of the synthetic content areas under test are literal on purpose.
@file:Suppress("MagicNumber")

package com.indagium

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.indagium.ui.TileRect
import com.indagium.ui.holiday.HolidayArtAnchor
import com.indagium.ui.holiday.HolidayArtAnchor.BOTTOM_END
import com.indagium.ui.holiday.HolidayArtAnchor.BOTTOM_START
import com.indagium.ui.holiday.HolidayBackdropArt
import com.indagium.ui.holiday.holidayArtPlacements
import com.indagium.ui.holiday.holidayArtRect
import com.indagium.ui.holiday.holidayDropOverlapping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The backdrop arts sit in the bottom corners of the window's content area, sized by the area only. */
class HolidayArtPlacementTest {
    private fun rect(anchor: HolidayArtAnchor, w: Float, h: Float, dw: Float = 300f, dh: Float = 420f) = holidayArtRect(anchor, w, h, dw, dh)

    @Test
    fun inA1400By860WindowThePictureKeepsItsDesignSizeInTheBottomLeft() {
        assertEquals(TileRect(0f, 440f, 300f, 420f), rect(BOTTOM_START, 1400f, 860f))
    }

    @Test
    fun theCompanionSitsInTheBottomRightAtItsDesignSize() {
        assertEquals(TileRect(1160f, 620f, 240f, 240f), rect(BOTTOM_END, 1400f, 860f, 240f, 240f))
    }

    @Test
    fun aBiggerWindowNeverGrowsAnArtPastItsDesignSize() {
        val r = assertNotNull(rect(BOTTOM_START, 3000f, 2000f))
        assertEquals(300f, r.w)
        assertEquals(420f, r.h)
        assertEquals(2000f, r.bottom)
    }

    @Test
    fun aSmallerWindowScalesTheArtByTheTighterOfWidthAndHeight() {
        // 1000 x 700: width allows 240 / 300 = 0.8, height allows 350 / 420 = 0.833: the width wins.
        val wide = assertNotNull(rect(BOTTOM_START, 1000f, 700f))
        assertEquals(240f, wide.w, 0.01f)
        assertEquals(336f, wide.h, 0.01f)
        // 1400 x 500: height allows 250 / 420 = 0.595: the height wins.
        val short = assertNotNull(rect(BOTTOM_START, 1400f, 500f))
        assertEquals(250f, short.h, 0.01f)
        assertEquals(300f * 250f / 420f, short.w, 0.01f)
        assertEquals(500f, short.bottom, 0.01f)
        // The aspect ratio always survives.
        listOf(500f to 400f, 900f to 600f, 1600f to 1000f).forEach { (w, h) ->
            val r = assertNotNull(rect(BOTTOM_END, w, h))
            assertEquals(300f / 420f, r.w / r.h, 0.001f)
            assertEquals(w, r.right, 0.01f)
        }
    }

    @Test
    fun noArtUnderTheMinimumContentSize() {
        assertNotNull(rect(BOTTOM_START, 480f, 360f))
        assertNull(rect(BOTTOM_START, 479f, 800f))
        assertNull(rect(BOTTOM_END, 1400f, 359f))
    }

    @Test
    fun overlappingArtsKeepTheFirstAndTouchingOnesBothStay() {
        val a = TileRect(0f, 0f, 100f, 100f)
        assertEquals(listOf("a"), holidayDropOverlapping(listOf("a" to a, "b" to TileRect(99f, 50f, 100f, 100f))).map { it.first })
        assertEquals(listOf("a", "b"), holidayDropOverlapping(listOf("a" to a, "b" to TileRect(100f, 0f, 100f, 100f))).map { it.first })
        assertEquals(listOf("a", "b"), holidayDropOverlapping(listOf("a" to a, "b" to TileRect(500f, 500f, 10f, 10f))).map { it.first })
    }

    @Test
    fun placementsUseTheSharedRuleForEveryArtAndDropAnOverlappingCompanion() {
        val noDraw: (DrawScope, Float, Boolean) -> Unit = { _, _, _ -> }
        val picture = HolidayBackdropArt(BOTTOM_START, 300f, 420f, "p", noDraw)
        val companion = HolidayBackdropArt(BOTTOM_END, 240f, 240f, "c", noDraw)
        assertEquals(listOf("p", "c"), holidayArtPlacements(listOf(picture, companion), 1400f, 860f).map { it.first.tag })
        assertTrue(holidayArtPlacements(listOf(picture, companion), 300f, 200f).isEmpty(), "too small a window")
        // Each art takes at most 24% of the width, so the two never meet at any size; the drop rule is the guard for a future wider art.
        listOf(480f to 360f, 700f to 500f, 1400f to 860f, 3000f to 2000f).forEach { (w, h) ->
            assertEquals(2, holidayArtPlacements(listOf(picture, companion), w, h).size, "${w}x$h")
        }
    }
}
