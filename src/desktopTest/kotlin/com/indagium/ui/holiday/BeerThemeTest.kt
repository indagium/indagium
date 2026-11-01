@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.indagium.model.HolidayIntensity
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.model.HolidaySettings
import com.indagium.ui.holiday.beer.*
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BeerThemeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun packsFollowFridayScheduleAndUseTheirOwnPalettes() {
        val before = LocalDateTime.of(2026, 7, 3, 16, 59)
        val after = before.plusMinutes(1)
        for (pack in listOf(HolidayPack.BEER_TIME_YELLOW, HolidayPack.BEER_TIME_GREEN)) {
            val theme = assertNotNull(HOLIDAY_THEMES[pack])
            assertEquals("Find", assertNotNull(theme.icons?.findIcon(Color.Black)).name)
            val settings = HolidaySettings(mode = HolidayMode.AUTOMATIC, pack = pack)
            assertNull(activeHolidayTheme(settings, before))
            assertEquals(theme, activeHolidayTheme(settings, after))
            assertNull(activeHolidayTheme(settings, after.plusDays(1)))
            assertEquals("Fridays from 17:00", holidayDatesLabel(theme))
            assertEquals(1, resolveHolidayEffects(settings, theme).activeEffects.size)
            val bubbles = theme.effects.single()
            val noBubbles = settings.copy(effectToggles = mapOf(bubbles.id to false))
            assertTrue(resolveHolidayEffects(noBubbles, theme).activeEffects.isEmpty())
            assertTrue(resolveHolidayEffects(settings.copy(intensity = HolidayIntensity.CALM), theme).countScale < 1f)
            assertTrue(resolveHolidayEffects(settings.copy(intensity = HolidayIntensity.OFF), theme).activeEffects.isEmpty())
            assertEquals(theme, activeHolidayTheme(settings, LocalDateTime.of(2026, 10, 30, 17, 0)))
        }
        assertEquals(Color(0xFF160f09), BeerYellowTheme.palette(true).bg)
        assertEquals(Color(0xFFfbf6ea), BeerYellowTheme.palette(false).bg)
        assertEquals(Color(0xFF0b1a10), BeerGreenTheme.palette(true).bg)
        assertEquals(Color(0xFFf4f8f3), BeerGreenTheme.palette(false).bg)
    }

    @Test fun pictureAndCompanionRender() {
        rule.setContent {
            Row(Modifier.testTag("beer-art")) {
                for (dark in listOf(true, false)) {
                    val bg = if (dark) Color(0xFF160f09) else Color(0xFFfbf6ea)
                    Canvas(Modifier.size(300.dp, 420.dp).background(bg)) {
                        scale(size.width / 300f, pivot = Offset.Zero) { BeerPicture.draw(this, 1.25f, dark) }
                    }
                    Canvas(Modifier.size(240.dp, 316.dp).background(bg)) {
                        scale(size.width / 240f, pivot = Offset.Zero) { BeerCompanion(false).draw(this, 1.25f, dark) }
                    }
                }
            }
        }
        val image = rule.onNodeWithTag("beer-art").captureToImage()
        val pixels = image.toPixelMap()
        val captureDir = System.getenv("INDAGIUM_CAPTURE_BEER")
        if (captureDir != null || java.lang.Boolean.getBoolean("indagium.captureBeer")) {
            val result = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until image.height) for (x in 0 until image.width) result.setRGB(x, y, pixels[x, y].toArgb())
            ImageIO.write(result, "png", File(captureDir ?: System.getProperty("java.io.tmpdir"), "indagium-beer-art.png"))
        }
        val density = rule.density.density
        assertTrue(pixels[(130 * density).toInt(), (260 * density).toInt()] != Color(0xFF160f09))
        assertTrue(pixels[(410 * density).toInt(), (140 * density).toInt()] != Color(0xFF160f09))
    }

    private fun render(width: Int, height: Int, block: DrawScope.() -> Unit): androidx.compose.ui.graphics.ImageBitmap {
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat()), block)
        return bitmap
    }

    @Test fun theSignLetteringIsBuiltOnceAndReused() {
        val first = BeerTextPaths.outline("IT'S BEER TIME", BeerTypeface.ROUNDED, 11f, 1.5f)
        val builds = BeerTextPaths.buildCount
        assertSame(first, BeerTextPaths.outline("IT'S BEER TIME", BeerTypeface.ROUNDED, 11f, 1.5f))
        assertEquals(builds, BeerTextPaths.buildCount)
        assertTrue(first.width > 0f && !first.path.isEmpty)
        // Drawing the picture, the companion and the band over and over builds no further outlines.
        val picture = BeerPicture
        val companion = BeerCompanion(false)
        val scene = BeerScene(false)
        val band = Rect(0f, 0f, 1600f, 30f)

        fun frame(t: Float) {
            render(300, 420) { picture.draw(this, t, true) }
            render(240, 316) { companion.draw(this, t, true) }
            render(1600, 30) { scene.drawFrame(this, scene.layout(band), true, t) }
        }
        frame(0f)
        val afterFirst = BeerTextPaths.buildCount
        frame(1.7f)
        frame(3.1f)
        assertEquals(afterFirst, BeerTextPaths.buildCount, "the outlines are cached, not rebuilt per frame")
    }

    @Test fun theSignLetteringIsOnePathNotLooseGlyphs() {
        // The outline starts at the origin of its baseline and has no hinting: its width is the sum of the advances (no pixel rounding).
        val outline = BeerTextPaths.outline("BEER TIME", BeerTypeface.SIGN, 21f)
        val bounds = outline.path.getBounds()
        assertTrue(bounds.left >= -1f && bounds.right <= outline.width + 1f, "the glyphs lie within the measured width: $bounds vs ${outline.width}")
        assertTrue(bounds.top < 0f && bounds.bottom < 8f, "the letters stand on the baseline: $bounds")
    }

    @Test fun theCompanionHoldsTheMugAndATiltedSignInsideItsOwnRectangle() {
        for (green in listOf(false, true)) {
            val companion = BeerCompanion(green)
            assertEquals(240f, companion.designWidthDp)
            assertTrue(companion.designHeightDp > companion.designWidthDp, "the companion is taller than wide: the sign sits above the mug")
            for (dark in listOf(true, false)) for (t in listOf(0f, 1.25f, 3.3f)) {
                val pad = 40
                val w = companion.designWidthDp.toInt()
                val h = companion.designHeightDp.toInt()
                val bitmap = render(w + 2 * pad, h + 2 * pad) { translate(pad.toFloat(), pad.toFloat()) { companion.draw(this, t, dark) } }
                val pixels = bitmap.toPixelMap()
                var inside = 0
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    val onArt = pixels[x, y].alpha > 0.02f
                    val within = x in pad until pad + w && y in pad until pad + h
                    if (onArt && !within) throw AssertionError("art at ($x,$y) escapes the ${w}x$h rectangle (green=$green dark=$dark t=$t)")
                    if (onArt && y < pad + SIGN_BAND_BOTTOM) inside++
                }
                assertTrue(inside > 200, "the neon sign is drawn above the mug (green=$green dark=$dark)")
            }
        }
    }

    @Test fun theSignInTheCompanionTakesTheBandsNeonColour() {
        for (green in listOf(false, true)) for (dark in listOf(true, false)) {
            val neon = beerNeon(green, dark)
            val bitmap = render(240, 316) { BeerCompanion(green).draw(this, HOLIDAY_STILL_TIME, dark) }
            val pixels = bitmap.toPixelMap()
            var found = false
            for (y in 0 until SIGN_BAND_BOTTOM) for (x in 0 until 240) {
                val c = pixels[x, y]
                if (c.alpha > .9f && abs(c.red - neon.red) < .08f && abs(c.green - neon.green) < .08f && abs(c.blue - neon.blue) < .08f) found = true
            }
            assertTrue(found, "neon pixels of $neon in the sign (green=$green dark=$dark)")
        }
    }

    @Test fun theCompanionMovesTheMugButNotTheSignWhenItsRectangleIsSmaller() {
        // The framework scales the whole design size: a non-square companion keeps its aspect ratio.
        val big = holidayArtRect(HolidayArtAnchor.BOTTOM_END, 1400f, 860f, 240f, 316f)!!
        assertEquals(240f, big.w, 0.01f)
        assertEquals(316f, big.h, 0.01f)
        val small = holidayArtRect(HolidayArtAnchor.BOTTOM_END, 700f, 500f, 240f, 316f)!!
        assertEquals(240f / 316f, small.w / small.h, 0.001f)
        assertTrue(small.h <= 500f * HOLIDAY_ART_HEIGHT_SHARE + 0.01f)
    }

    @Test fun theBandSceneIsTheDesignsShelfRightAlignedAndDropsWhatDoesNotFit() {
        val scene = BeerScene(false)
        val wide = scene.layout(Rect(0f, 0f, 1600f, 30f)).elementBounds
        assertEquals(2, wide.size, "the shelf and the neon sign")
        assertEquals(Rect(940f, 5f, 1500f, 30f), wide.first(), "as authored on the 1600 bar")
        assertEquals(1, scene.layout(Rect(0f, 0f, 500f, 30f)).elementBounds.size, "the sign alone in a narrow band")
        assertTrue(scene.layout(Rect(0f, 0f, 150f, 30f)).elementBounds.isEmpty())
        assertTrue(scene.animated)
    }

    private companion object {
        const val SIGN_BAND_BOTTOM = 90
    }
}
