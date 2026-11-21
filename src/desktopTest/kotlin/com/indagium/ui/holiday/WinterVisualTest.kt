// Pixel sizes, times and counts of the renders under test are literal on purpose.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import com.indagium.model.HolidayPack
import com.indagium.model.TabStripPosition
import com.indagium.ui.holiday.cozy.COZY_LIGHTS
import com.indagium.ui.holiday.cozy.GiftTagShape
import com.indagium.ui.holiday.snow.ChristmasBow
import com.indagium.ui.holiday.snow.ChristmasBowAndStar
import com.indagium.ui.holiday.snow.SantasSleigh
import com.indagium.ui.holiday.snow.SnowCap
import com.indagium.ui.holiday.snow.StringOfLights
import com.indagium.ui.holiday.snow.starTwinkle
import com.indagium.ui.holiday.xmas.XMAS_LIGHTS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the three winter packs paint: their pictures, companions and title bars, the string of lights, the effects and the tab ornaments. */
class WinterVisualTest {
    private val themes get() = listOf(HolidayPack.FIRST_SNOW, HolidayPack.COZY_CHRISTMAS, HolidayPack.CLASSIC_CHRISTMAS).map { HOLIDAY_THEMES.getValue(it) }

    private fun picture(theme: HolidayTheme, t: Float, dark: Boolean): ImageBitmap {
        val painter = checkNotNull(theme.picture)
        return renderBitmap(painter.designWidthDp.toInt(), painter.designHeightDp.toInt()) { painter.draw(this, t, dark) }
    }

    private fun companion(theme: HolidayTheme, t: Float, dark: Boolean): ImageBitmap {
        val painter = checkNotNull(theme.companion)
        return renderBitmap(painter.designWidthDp.toInt(), painter.designHeightDp.toInt()) { painter.draw(this, t, dark) }
    }

    private fun scene(theme: HolidayTheme, time: Float, dark: Boolean, effects: Set<String>, width: Int = 1600): ImageBitmap {
        val painter = checkNotNull(theme.tabScene)
        val layout = painter.layout(Rect(0f, 0f, width.toFloat(), 30f))
        return renderBitmap(width, 30) { painter.drawFrame(this, layout, dark, time, effects) }
    }

    private fun HolidayTheme.effectIds() = effects.map { it.id }.toSet()

    @Test fun everyPictureAndCompanionHasInkInBothPalettesAtTheStillPose() {
        for (theme in themes) {
            for (dark in listOf(true, false)) {
                assertTrue(paintedPixels(picture(theme, HOLIDAY_STILL_TIME, dark)) > 2000, "${theme.pack} picture, dark=$dark")
                assertTrue(paintedPixels(companion(theme, HOLIDAY_STILL_TIME, dark)) > 2000, "${theme.pack} companion, dark=$dark")
            }
            val painter = checkNotNull(theme.picture)
            assertEquals(300f, painter.designWidthDp)
            assertEquals(420f, painter.designHeightDp)
            assertEquals(240f, checkNotNull(theme.companion).designWidthDp)
        }
    }

    @Test fun theStillPoseIsStableAndAMovingFrameDiffersFromIt() {
        for (theme in themes) {
            assertNull(differingBounds(picture(theme, HOLIDAY_STILL_TIME, true), picture(theme, HOLIDAY_STILL_TIME, true)), "${theme.pack} still picture")
            assertNull(differingBounds(companion(theme, HOLIDAY_STILL_TIME, true), companion(theme, HOLIDAY_STILL_TIME, true)), "${theme.pack} still companion")
            assertNotNull(differingBounds(picture(theme, .3f, true), picture(theme, 1.9f, true)), "${theme.pack} picture moves")
            assertNotNull(differingBounds(companion(theme, .3f, true), companion(theme, 1.9f, true)), "${theme.pack} companion moves")
            assertNotNull(differingBounds(picture(theme, 1.9f, true), picture(theme, HOLIDAY_STILL_TIME, true)), "${theme.pack} still is calm, not a frame")
        }
    }

    @Test fun firstSnowAndClassicChristmasPaintTheirTitleBarsAndCozyOnlyItsLights() {
        val snow = HOLIDAY_THEMES.getValue(HolidayPack.FIRST_SNOW)
        val cozy = HOLIDAY_THEMES.getValue(HolidayPack.COZY_CHRISTMAS)
        val classic = HOLIDAY_THEMES.getValue(HolidayPack.CLASSIC_CHRISTMAS)
        for (dark in listOf(true, false)) {
            assertTrue(paintedPixels(scene(snow, HOLIDAY_STILL_TIME, dark, snow.effectIds())) > 500, "First snow's hills, trees and moon, dark=$dark")
            assertTrue(paintedPixels(scene(classic, HOLIDAY_STILL_TIME, dark, emptySet())) > 800, "Classic's village and moon without the lights, dark=$dark")
            // Cozy's design title bar is only the garland: with the lights off nothing is painted, with them on there are bulbs and a wire.
            assertEquals(0, paintedPixels(scene(cozy, HOLIDAY_STILL_TIME, dark, emptySet())), "Cozy without its lights is bare, dark=$dark")
            assertTrue(paintedPixels(scene(cozy, HOLIDAY_STILL_TIME, dark, cozy.effectIds())) > 400, "Cozy's string of lights, dark=$dark")
            val withLights = paintedPixels(scene(classic, HOLIDAY_STILL_TIME, dark, classic.effectIds()))
            assertTrue(withLights > paintedPixels(scene(classic, HOLIDAY_STILL_TIME, dark, emptySet())), "the lights add to Classic's bar, dark=$dark")
        }
    }

    @Test fun theLightsTwinkleOnlyWhileTheClockRunsAndStandStillAtTheStillPose() {
        for (theme in listOf(HOLIDAY_THEMES.getValue(HolidayPack.COZY_CHRISTMAS), HOLIDAY_THEMES.getValue(HolidayPack.CLASSIC_CHRISTMAS))) {
            val ids = theme.effectIds()
            assertNotNull(differingBounds(scene(theme, 4.1f, true, ids), scene(theme, 4.9f, true, ids)), "${theme.pack}: the bulbs blink")
            assertNull(differingBounds(scene(theme, HOLIDAY_STILL_TIME, true, ids), scene(theme, HOLIDAY_STILL_TIME, true, ids)))
            assertTrue(checkNotNull(theme.tabScene).animated)
        }
        // The Layout menu's thumbnail asks for no effects: the plain draw shows the lights, like the bear's aurora.
        val cozy = HOLIDAY_THEMES.getValue(HolidayPack.COZY_CHRISTMAS)
        val painter = checkNotNull(cozy.tabScene)
        val layout = painter.layout(Rect(0f, 0f, 1000f, 30f))
        assertTrue(paintedPixels(renderBitmap(1000, 30) { painter.draw(this, layout, true) }) > 200)
    }

    @Test fun theStringOfLightsHangsThreeBulbsPerSwagAndBlinksLikeTheDesign() {
        val bulbs = StringOfLights.bulbsAlong(1600f)
        assertEquals(30, bulbs.size, "ten 160-unit swags of three")
        assertEquals(listOf(0, 1, 2, 3, 0, 1, 2, 3), bulbs.take(8).map { it.colorIndex })
        assertEquals(listOf(0f, .45f, .9f, 1.35f), bulbs.take(4).map { (it.delaySec * 100f).let { d -> kotlin.math.round(d) / 100f } })
        assertEquals(160f * .2f - 3.5f, bulbs[0].x, .001f)
        assertEquals(160f * .5f - 3.5f, bulbs[1].x, .001f)
        assertTrue(bulbs[1].y > bulbs[0].y, "the middle of a swag hangs lowest")
        assertEquals(StringOfLights.HOOK_Y + StringOfLights.SAG, StringOfLights.swag(.5f), .001f)
        assertEquals(StringOfLights.HOOK_Y, StringOfLights.swag(0f), .001f)
        assertEquals(1f, StringOfLights.blink(HOLIDAY_STILL_TIME, 0f), "calm at the still pose")
        assertEquals(1f, StringOfLights.blink(.2f, .45f), "before its delay a bulb is full")
        assertEquals(.35f, StringOfLights.blink(.9f, 0f), .001f, "half-way through the 1.8 s blink it is at .35")
        assertEquals(1f, StringOfLights.blink(1.8f, 0f), .001f)
        assertEquals(4, (0 until 4).map { COZY_LIGHTS.bulb(it) }.toSet().size)
        assertEquals(Color(0xFFe0343c), XMAS_LIGHTS.bulb(1))
        assertEquals(XMAS_LIGHTS.bulb(1), XMAS_LIGHTS.bulb(3), "Classic alternates its two reds with pale and warm white")
    }

    // The brightest opaque-ish pixel (red + green + blue) of a 400 x 300 render.
    private fun brightest(p: androidx.compose.ui.graphics.PixelMap) = (0 until 300).maxOf { y ->
        (0 until 400).maxOf { x -> p[x, y].let { if (it.alpha > .4f) it.red + it.green + it.blue else 0f } }
    }

    private fun field(effect: HolidayEffectSpec, seed: Int = 7) = (effect as HolidayParticleEffect).spawn(seed, 1f, 1f)

    private fun drawField(field: HolidayParticleField, t: Float, dark: Boolean = true, w: Int = 400, h: Int = 300) =
        renderBitmap(w, h) { field.draw(this, t, w.toFloat(), h.toFloat(), dark) }

    @Test fun snowfallDrawsFlakesThatMoveAndChangeWithThePalette() {
        for (theme in themes) {
            val snowfall = field(theme.effects[0])
            val a = drawField(snowfall, 1f)
            assertTrue(paintedPixels(a) > 100, "${theme.pack} flakes are drawn")
            assertNotNull(differingBounds(a, drawField(snowfall, 3f)), "${theme.pack} flakes fall")
            val inkDark = drawField(snowfall, 1f, dark = true).toPixelMap()
            val inkLight = drawField(snowfall, 1f, dark = false).toPixelMap()
            assertTrue(brightest(inkDark) > brightest(inkLight), "${theme.pack}: white flakes on dark, tinted ones on light")
        }
    }

    @Test fun settlingSnowLandsOnTheTopEdgesOfTheTabsAndTilesOnly() {
        for (theme in listOf(HOLIDAY_THEMES.getValue(HolidayPack.FIRST_SNOW), HOLIDAY_THEMES.getValue(HolidayPack.CLASSIC_CHRISTMAS))) {
            val landing = field(theme.effects[1]) as HolidayLandingField
            val anchors = listOf(Rect(40f, 40f, 160f, 80f), Rect(200f, 120f, 380f, 280f))

            fun frame(t: Float, areas: List<Rect>) = renderBitmap(400, 300) { landing.drawLanding(this, t, 400f, 300f, true, areas) }

            val times = (0 until 40).map { it * .7f }
            assertTrue(times.any { paintedPixels(frame(it, anchors)) > 0 }, "${theme.pack}: flakes settle on an anchor")
            assertTrue(times.all { paintedPixels(frame(it, emptyList())) == 0 }, "no anchors, nothing to land on")
            assertTrue(times.all { paintedPixels(frame(it, listOf(Rect(40f, 40f, 60f, 80f)))) == 0 }, "a sliver is too narrow to hold snow")
            val resting = times.map { frame(it, anchors) }.first { paintedPixels(it) > 0 }
            val bounds = resting.toPixelMap().let { p ->
                val ys = (0 until 300).filter { y -> (0 until 400).any { x -> p[x, y].alpha > 0f } }
                ys.min() to ys.max()
            }
            assertTrue(bounds.first >= 0 && bounds.second <= 280, "${theme.pack}: bounds $bounds")
            // Plain draw is a no-op: a landing field only draws through drawLanding.
            assertEquals(0, paintedPixels(drawField(landing, 1f)))
        }
    }

    @Test fun theSleighCrossesTheWindowAndBobs() {
        val w = 800f
        assertEquals(-80f, SantasSleigh.position(18f, w).x, .01f, "it enters 80 dp before the left edge")
        val later = SantasSleigh.position(18f + 12f, w)
        assertEquals(-80f + .5f * (w + 100f + 80f), later.x, .01f, "half-way through its 24 s the sleigh is half-way across")
        assertTrue(SantasSleigh.position(0f, w).x < SantasSleigh.position(5f, w).x, "it flies left to right")
        val sleigh = field(SantasSleigh)
        assertEquals(1, sleigh.count)
        val a = renderBitmap(800, 100) { sleigh.draw(this, 0f, 800f, 100f, true) }
        val b = renderBitmap(800, 100) { sleigh.draw(this, 5f, 800f, 100f, true) }
        assertTrue(paintedPixels(a) > 300, "a sleigh and four reindeer")
        assertNotNull(differingBounds(a, b))
        val light = renderBitmap(800, 100) { sleigh.draw(this, 0f, 800f, 100f, false) }
        assertEquals(paintedPixels(a), paintedPixels(light), "the silhouette is the same in the light palette")
        assertEquals(0, SantasSleigh.spawn(7, 1f, 0f).count)
    }

    private fun ornament(draw: DrawScope.() -> Unit) = renderBitmap(120, 48, 1f, draw)

    @Test fun theSnowCapSitsOverTheTabsTopEdgeInBothPalettes() {
        for (dark in listOf(true, false)) {
            val image = ornament { SnowCap.draw(this, TabStripPosition.TOP, 10f, dark) }
            val p = image.toPixelMap()
            assertTrue(p[60, 8].alpha > .9f, "snow above the tab's top edge (dark=$dark)")
            assertEquals(Color(if (dark) 0xFFeef5fc else 0xFFffffff).toArgb(), p[60, 8].toArgb())
            assertEquals(0f, p[60, 30].alpha, "nothing far below the edge")
            assertEquals(0f, p[60, 0].alpha, "nothing 10 dp above it")
        }
        // A side strip draws it beside the tab's outer edge, not above.
        val left = ornament { SnowCap.draw(this, TabStripPosition.LEFT, 10f, true) }.toPixelMap()
        assertTrue(left[8, 24].alpha > .9f, "snow beside a left strip's outer edge")
        assertEquals(0f, left[60, 8].alpha)
        val bottom = ornament { SnowCap.draw(this, TabStripPosition.BOTTOM, 10f, true) }.toPixelMap()
        assertTrue(bottom[60, 48 - 8].alpha > .9f, "snow under a bottom strip's outer edge")
    }

    @Test fun theBowSitsOnTopOfTheTabAndTheStarTwinklesOnItsCorner() {
        val bow = ornament { ChristmasBow.draw(this, TabStripPosition.TOP, 10f, true) }
        assertTrue(paintedPixels(bow) in 60..200, "a 16 x 9 bow: ${paintedPixels(bow)}")
        val green = bow.toPixelMap()[60 - 4, 6]
        assertTrue(green.alpha > .9f && green.green > green.red, "a green loop left of the gold knot")
        val still = ornament { ChristmasBowAndStar.draw(this, TabStripPosition.TOP, 10f, true) }
        val stillFrame = ornament { ChristmasBowAndStar.drawFrame(this, TabStripPosition.TOP, 10f, true, HOLIDAY_STILL_TIME) }
        assertNull(differingBounds(still, stillFrame), "draw is the still pose")
        val dim = ornament { ChristmasBowAndStar.drawFrame(this, TabStripPosition.TOP, 10f, true, 0f) }
        val full = ornament { ChristmasBowAndStar.drawFrame(this, TabStripPosition.TOP, 10f, true, .8f) }
        assertNotNull(differingBounds(dim, full), "the star twinkles")
        assertTrue(paintedPixels(still) > paintedPixels(bow), "the star is on top of the bow")
        val star = differingBounds(bow, still)
        assertTrue(star != null && star.left > 60f, "the star is on the tab's right corner: $star")
        assertEquals(1f to 1f, starTwinkle(HOLIDAY_STILL_TIME))
        assertEquals(.35f to .7f, starTwinkle(0f))
        assertEquals(1f, starTwinkle(.8f).first, .001f)
        assertEquals(1f, starTwinkle(.8f).second, .001f)
    }

    @Test fun theGiftTagIsPointedOnTheLeftWithAHoleThroughIt() {
        val w = 120
        val h = 28
        val image = renderBitmap(w, h) {
            drawPath(GiftTagShape.path(Size(w.toFloat(), h.toFloat()), 1f), Color.Red)
        }
        val p = image.toPixelMap()
        assertEquals(0f, p[1, 1].alpha, "the point cuts the top-left corner")
        assertEquals(0f, p[1, h - 2].alpha, "and the bottom-left one")
        assertTrue(p[3, h / 2].alpha > .9f, "the point itself is at the middle of the left edge")
        assertTrue(p[20, 2].alpha > .9f && p[w - 2, 2].alpha > .9f && p[w - 2, h - 2].alpha > .9f, "square on the right")
        assertEquals(0f, p[14, h / 2].alpha, "the hole, 14.5 dp in, is punched through")
        assertTrue(p[22, h / 2].alpha > .9f, "solid again beyond the hole")
        assertTrue(p[14, h / 2 - 6].alpha > .9f, "and above it")
    }
}
