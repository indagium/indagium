// Pixel sizes, times and counts of the renders under test are literal on purpose.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import com.indagium.model.HolidayPack
import com.indagium.model.TabStripPosition
import com.indagium.ui.holiday.newyear.NewYearStar
import com.indagium.ui.holiday.newyear.burstFrame
import com.indagium.ui.holiday.retro.NeonCar
import com.indagium.ui.holiday.retro.NeonGlow
import com.indagium.ui.holiday.retro.streakFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What New Year's Eve, Programmers' Day and Retro paint: pictures, companions, title bars, effects and tab ornaments. */
class PartyVisualTest {
    private val themes get() = listOf(HolidayPack.NEW_YEARS_EVE, HolidayPack.PROGRAMMERS_DAY, HolidayPack.RETRO).map { HOLIDAY_THEMES.getValue(it) }

    private fun picture(theme: HolidayTheme, t: Float, dark: Boolean): ImageBitmap {
        val painter = checkNotNull(theme.picture)
        return renderBitmap(painter.designWidthDp.toInt(), painter.designHeightDp.toInt()) { painter.draw(this, t, dark) }
    }

    private fun companion(theme: HolidayTheme, t: Float, dark: Boolean): ImageBitmap {
        val painter = checkNotNull(theme.companion)
        return renderBitmap(painter.designWidthDp.toInt(), painter.designHeightDp.toInt()) { painter.draw(this, t, dark) }
    }

    private fun scene(theme: HolidayTheme, dark: Boolean, width: Int = 1600): ImageBitmap {
        val painter = checkNotNull(theme.tabScene)
        val layout = painter.layout(Rect(0f, 0f, width.toFloat(), 30f))
        return renderBitmap(width, 30) { painter.drawFrame(this, layout, dark, HOLIDAY_STILL_TIME) }
    }

    @Test fun everyPictureCompanionAndTitleBarHasInkInBothPalettesAtTheStillPose() {
        for (theme in themes) {
            for (dark in listOf(true, false)) {
                assertTrue(paintedPixels(picture(theme, HOLIDAY_STILL_TIME, dark)) > 2000, "${theme.pack} picture, dark=$dark")
                assertTrue(paintedPixels(companion(theme, HOLIDAY_STILL_TIME, dark)) > 1500, "${theme.pack} companion, dark=$dark")
                assertTrue(paintedPixels(scene(theme, dark)) > 400, "${theme.pack} title bar, dark=$dark")
            }
            assertEquals(300f, checkNotNull(theme.picture).designWidthDp)
            assertEquals(420f, checkNotNull(theme.picture).designHeightDp)
            assertEquals(240f, checkNotNull(theme.companion).designHeightDp)
        }
    }

    @Test fun theStillPoseIsStableAndAMovingFrameDiffersFromIt() {
        for (theme in themes) {
            assertNull(differingBounds(picture(theme, HOLIDAY_STILL_TIME, true), picture(theme, HOLIDAY_STILL_TIME, true)), "${theme.pack} still picture")
            assertNull(differingBounds(companion(theme, HOLIDAY_STILL_TIME, true), companion(theme, HOLIDAY_STILL_TIME, true)), "${theme.pack} still companion")
            assertNotNull(differingBounds(picture(theme, .3f, true), picture(theme, 1.9f, true)), "${theme.pack} picture moves")
            assertNotNull(differingBounds(companion(theme, .3f, true), companion(theme, 1.9f, true)), "${theme.pack} companion moves")
        }
    }

    @Test fun theTitleBarsStayInsideTheirBandAtNarrowWidths() {
        for (theme in themes) {
            for (width in listOf(300, 700, 1000)) {
                val layout = checkNotNull(theme.tabScene).layout(Rect(0f, 0f, width.toFloat(), 30f))
                assertTrue(layout.elementBounds.all { it.left >= 0f && it.right <= width }, "${theme.pack} at $width")
            }
        }
    }

    private fun field(effect: HolidayEffectSpec, seed: Int = 7) = (effect as HolidayParticleEffect).spawn(seed, 1f, 1f)

    private fun drawField(field: HolidayParticleField, t: Float, dark: Boolean = true, w: Int = 800, h: Int = 400) =
        renderBitmap(w, h) { field.draw(this, t, w.toFloat(), h.toFloat(), dark) }

    private fun HolidayTheme.effect(id: String) = effects.first { it.id == id }

    private fun PixelMap.rowsWithInk(w: Int, h: Int) = (0 until h).filter { y -> (0 until w).any { x -> this[x, y].alpha > 0f } }

    @Test fun confettiFallsInThePalettesColoursAndMoves() {
        val confetti = field(HOLIDAY_THEMES.getValue(HolidayPack.NEW_YEARS_EVE).effect("newyear.confetti"))
        val a = drawField(confetti, 2f)
        assertTrue(paintedPixels(a) > 200, "pieces are drawn")
        assertNotNull(differingBounds(a, drawField(confetti, 5f)), "pieces fall")
        assertTrue(paintedPixels(drawField(confetti, 2f, dark = false)) > 200)
        val pixels = a.toPixelMap()
        val argb = (0 until 400).flatMap { y -> (0 until 800).filter { x -> pixels[x, y].alpha > .99f }.map { x -> pixels[x, y].toArgb() } }.toSet()
        assertTrue(argb.size >= 3, "several colours: ${argb.size}")
    }

    @Test fun confettiSettlesOnTheTopEdgesOfTheTabsAndTilesOnly() {
        val landing = field(HOLIDAY_THEMES.getValue(HolidayPack.NEW_YEARS_EVE).effect("newyear.landing")) as HolidayLandingField
        val anchors = listOf(Rect(40f, 40f, 160f, 80f), Rect(200f, 120f, 380f, 280f))

        fun frame(t: Float, areas: List<Rect>) = renderBitmap(400, 300) { landing.drawLanding(this, t, 400f, 300f, true, areas) }

        val times = (0 until 40).map { it * .7f }
        assertTrue(times.any { paintedPixels(frame(it, anchors)) > 0 }, "pieces settle on an anchor")
        assertTrue(times.all { paintedPixels(frame(it, emptyList())) == 0 })
        assertTrue(times.all { paintedPixels(frame(it, listOf(Rect(40f, 40f, 60f, 80f)))) == 0 }, "a sliver is too narrow")
        assertEquals(0, paintedPixels(drawField(landing, 1f)), "a landing field only draws through drawLanding")
    }

    @Test fun theBurstFollowsHolBurstAndTheFireworksStayNearTheTopOfTheWindow() {
        assertEquals(.15f to 0f, burstFrame(0f))
        assertEquals(1f, burstFrame(.08f).second, .001f)
        assertEquals(.9f, burstFrame(.55f).second, .001f)
        assertEquals(1f to 0f, burstFrame(1f))
        assertTrue(burstFrame(.3f).first in .15f..1f)
        val fireworks = field(HOLIDAY_THEMES.getValue(HolidayPack.NEW_YEARS_EVE).effect("newyear.fireworks"))
        val frames = (0 until 30).map { drawField(fireworks, it * .5f) }
        assertTrue(frames.any { paintedPixels(it) > 100 }, "bursts are drawn")
        assertTrue(frames.all { f -> f.toPixelMap().rowsWithInk(800, 400).all { it < 120 } }, "they burst in the upper part of the window")
        assertNotNull(differingBounds(frames[1], frames[7]), "and grow and fade")
    }

    @Test fun binaryRainDrawsGreenDigitsThatFall() {
        val rain = field(HOLIDAY_THEMES.getValue(HolidayPack.PROGRAMMERS_DAY).effect("devday.rain"))
        val a = drawField(rain, 3f)
        assertTrue(paintedPixels(a) > 150, "digits are drawn")
        assertNotNull(differingBounds(a, drawField(rain, 6f)), "they fall")
        val p = a.toPixelMap()
        val ink = (0 until 400).flatMap { y -> (0 until 800).filter { x -> p[x, y].alpha > .25f }.map { x -> p[x, y] } }
        assertTrue(ink.isNotEmpty() && ink.all { it.green > it.red && it.green > it.blue }, "green phosphor")
        val light = drawField(rain, 3f, dark = false).toPixelMap()
        assertTrue((0 until 400).any { y -> (0 until 800).any { x -> light[x, y].alpha > .2f } }, "and a darker green in the light palette")
    }

    @Test fun scanlinesAreOneDpEveryThreeAndStatic() {
        for ((pack, id) in listOf(HolidayPack.PROGRAMMERS_DAY to "devday.scanlines", HolidayPack.RETRO to "retro.scanlines")) {
            val lines = field(HOLIDAY_THEMES.getValue(pack).effect(id))
            val p = drawField(lines, 1f, w = 40, h = 30).toPixelMap()
            assertTrue(p[5, 0].alpha > 0f && p[5, 3].alpha > 0f && p[5, 27].alpha > 0f, "$id: a line every 3 dp")
            assertEquals(0f, p[5, 1].alpha, "$id: and nothing between")
            assertEquals(0f, p[5, 2].alpha)
            assertNull(differingBounds(drawField(lines, 1f, w = 40, h = 30), drawField(lines, 9f, w = 40, h = 30)), "$id is static")
            val dark = drawField(lines, 1f, dark = true, w = 40, h = 30).toPixelMap()[5, 0].alpha
            val light = drawField(lines, 1f, dark = false, w = 40, h = 30).toPixelMap()[5, 0].alpha
            assertTrue(dark > light, "$id is fainter on the light palette")
        }
    }

    @Test fun shootingStarsStreakInFlightOnlyAndTheCarCrossesTheWindow() {
        assertEquals(0f to 0f, streakFrame(0f))
        assertEquals(0f to 1f, streakFrame(.04f))
        assertEquals(1f to 0f, streakFrame(.22f))
        assertEquals(1f to 0f, streakFrame(.6f), "it rests invisible until the cycle ends")
        val stars = field(HOLIDAY_THEMES.getValue(HolidayPack.RETRO).effect("retro.stars"))
        val frames = (0 until 30).map { drawField(stars, it * .4f) }
        assertTrue(frames.any { paintedPixels(it) > 60 }, "streaks are drawn")
        assertNotNull(differingBounds(frames[2], frames[9]), "and fly")
        val car = field(HOLIDAY_THEMES.getValue(HolidayPack.RETRO).effect("retro.car"))
        assertEquals(1, car.count)
        val a = drawField(car, 0f, h = 100)
        assertTrue(paintedPixels(a) > 300, "a car with speed lines, wheels and lights")
        assertNotNull(differingBounds(a, drawField(car, 4f, h = 100)))
        assertTrue(NeonCar.position(0f, 800f).x < NeonCar.position(4f, 800f).x, "it drives left to right")
        assertEquals(paintedPixels(a), paintedPixels(drawField(car, 0f, dark = false, h = 100)), "the car is the same in the light palette")
    }

    private fun ornament(draw: DrawScope.() -> Unit) = renderBitmap(120, 48, 1f, draw)

    @Test fun theNeonGlowRingsTheActiveTabAndBarsItsInnerEdgeInCyan() {
        for (dark in listOf(true, false)) {
            val neon = if (dark) Color(0xFFff4fd8) else Color(0xFFc42aa8)
            val cyan = if (dark) Color(0xFF00f0ff) else Color(0xFF0097b2)
            val p = ornament { NeonGlow.draw(this, TabStripPosition.TOP, 10f, dark) }.toPixelMap()
            assertEquals(neon.toArgb(), p[60, 9].toArgb(), "the 1 dp ring just above the tab's top edge (dark=$dark)")
            assertEquals(cyan.toArgb(), p[60, 45].toArgb(), "the cyan bar along its inner edge")
            assertTrue(p[60, 20].alpha == 0f, "nothing over the tab's middle")
            assertTrue(p[60, 4].alpha > 0f && p[60, 4].alpha < .2f, "a faint glow outside the ring: ${p[60, 4].alpha}")
            assertEquals(0f, p[60, 0].alpha, "which fades out before the cell's top")
        }
        val bottom = ornament { NeonGlow.draw(this, TabStripPosition.BOTTOM, 10f, true) }.toPixelMap()
        assertEquals(Color(0xFFff4fd8).toArgb(), bottom[60, 48 - 10].toArgb(), "a bottom strip's ring is under the tab")
    }

    @Test fun theNewYearStarTwinklesOnTheCornerInTheAccentColour() {
        val still = ornament { NewYearStar.draw(this, TabStripPosition.TOP, 10f, true) }

        fun at(time: Float) = ornament { NewYearStar.drawFrame(this, TabStripPosition.TOP, 10f, true, time) }
        assertNull(differingBounds(still, at(HOLIDAY_STILL_TIME)), "draw is the still pose")
        assertNotNull(differingBounds(at(0f), at(.8f)))
        val bounds = assertNotNull(differingBounds(ornament {}, still))
        assertTrue(bounds.left > 90f && bounds.bottom < 20f, "on the corner, above the tab: $bounds")
        val gold = still.toPixelMap().let { p -> (0 until 120).flatMap { x -> (0 until 20).map { y -> p[x, y] } }.first { it.alpha > .99f } }
        assertEquals(Color(0xFFf0c75e).toArgb(), gold.toArgb(), "dark accent")
        val light = ornament { NewYearStar.draw(this, TabStripPosition.TOP, 10f, false) }.toPixelMap()
        val solid = (0 until 120).flatMap { x -> (0 until 20).map { y -> light[x, y] } }.first { it.alpha > .99f }
        assertEquals(Color(0xFF8a6a10).toArgb(), solid.toArgb(), "light accent")
    }
}
