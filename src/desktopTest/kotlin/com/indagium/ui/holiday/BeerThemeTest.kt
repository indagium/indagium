@file:Suppress("MagicNumber")
package com.indagium.ui.holiday

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
                    Canvas(Modifier.size(300.dp, 420.dp).background(bg)) { scale(size.width / 300f, pivot = Offset.Zero) { BeerPicture.draw(this, 1.25f, dark) } }
                    Canvas(Modifier.size(240.dp, 240.dp).background(bg)) { scale(size.width / 240f, pivot = Offset.Zero) { BeerCompanion.draw(this, 1.25f, dark) } }
                }
            }
        }
        val image = rule.onNodeWithTag("beer-art").captureToImage()
        val pixels = image.toPixelMap()
        if (java.lang.Boolean.getBoolean("indagium.captureBeer")) {
            val result = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until image.height) for (x in 0 until image.width) result.setRGB(x, y, pixels[x, y].toArgb())
            ImageIO.write(result, "png", File(System.getProperty("java.io.tmpdir"), "indagium-beer-art.png"))
        }
        val density = rule.density.density
        assertTrue(pixels[(130 * density).toInt(), (260 * density).toInt()] != Color(0xFF160f09))
        assertTrue(pixels[(410 * density).toInt(), (140 * density).toInt()] != Color(0xFF160f09))
    }
}
