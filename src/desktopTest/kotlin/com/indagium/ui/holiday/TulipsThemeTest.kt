@file:Suppress("MagicNumber")
package com.indagium.ui.holiday

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.indagium.model.HolidayIntensity
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.model.HolidaySettings
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import javax.imageio.ImageIO
import kotlin.test.*

class TulipsThemeTest {
    private val theme get() = HOLIDAY_THEMES.getValue(HolidayPack.TULIPS)

    @Test fun selectingTulipsUsesItsOwnPaletteAndTurnsItOn() {
        val settings = HolidaySettings(mode = HolidayMode.AUTOMATIC).withPackOn(HolidayPack.TULIPS)
        assertEquals(HolidayMode.ALWAYS, settings.mode)
        assertEquals(theme, activeHolidayTheme(settings, LocalDateTime.of(2026, 4, 1, 12, 0)))
        assertFalse(theme.supportsAutomatic)
        assertEquals(Color(0xFF1e231d), theme.palette(true).bg)
        assertEquals(Color(0xFFeef0e8), theme.palette(false).bg)
        assertEquals(Color(0xFFb48cf0), theme.palette(true).ac)
        assertEquals(Color(0xFF7a2fbf), theme.palette(false).ac)
        assertEquals("Find", assertNotNull(theme.icons?.findIcon(Color.Black)).name)
    }

    @Test fun bouquetDividerUsesAllFiveReferenceColors() {
        val expected = listOf(0xFF7b2fbf, 0xFFd4252e, 0xFFf2d21c, 0xFFe46a9a, 0xFF6fb33f).map { Color(it) }
        for (dark in listOf(true, false)) assertEquals(expected, theme.tileDividerGradient(dark))
        assertNull(theme.tileDividerColors(true), "the bouquet uses a smooth gradient rather than three hard segments")
    }

    @Test fun effectsRespectIndividualSwitchesAndIntensity() {
        val settings = HolidaySettings(mode = HolidayMode.ALWAYS, pack = HolidayPack.TULIPS)
        val full = resolveHolidayEffects(settings, theme)
        assertTrue(full.activeEffects.isNotEmpty())
        for (effect in full.activeEffects) {
            val toggled = resolveHolidayEffects(settings.copy(effectToggles = mapOf(effect.id to false)), theme)
            assertFalse(toggled.activeEffects.any { it.id == effect.id })
        }
        assertTrue(resolveHolidayEffects(settings.copy(intensity = HolidayIntensity.CALM), theme).countScale < full.countScale)
        assertTrue(resolveHolidayEffects(settings.copy(intensity = HolidayIntensity.OFF), theme).activeEffects.isEmpty())
    }

    @Test fun paintingBackdropsRenderInBothPalettes() {
        val picture = assertNotNull(theme.picture)
        val companion = assertNotNull(theme.companion)
        val bitmap = ImageBitmap(1080, 460)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(1080f, 460f)) {
            for ((index, dark) in listOf(true, false).withIndex()) {
                val left = index * 540f
                drawRect(theme.palette(dark).bg, Offset(left, 0f), Size(540f, 460f))
                withTransform({ translate(left, 20f) }) { picture.draw(this, 0f, dark) }
                withTransform({ translate(left + 300f, 200f) }) { companion.draw(this, 0f, dark) }
            }
        }
        val pixels = bitmap.toPixelMap()
        for ((index, dark) in listOf(true, false).withIndex()) {
            val bg = theme.palette(dark).bg.toArgb()
            assertNotEquals(bg, pixels[index * 540 + 150, 220].toArgb(), "painting is drawn")
            assertNotEquals(bg, pixels[index * 540 + 420, 320].toArgb(), "companion is drawn")
        }
        System.getenv("INDAGIUM_CAPTURE_TULIPS")?.let { folder ->
            File(folder).mkdirs()
            val image = BufferedImage(1080, 460, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 460) for (x in 0 until 1080) image.setRGB(x, y, pixels[x, y].toArgb())
            ImageIO.write(image, "png", File(folder, "tulips-artwork.png"))
        }
    }

    @Test fun sceneRendersInBothPalettesAndStaysWithinItsBand() {
        val scene = assertNotNull(theme.tabScene)
        val band = Rect(0f, 0f, 1600f, 30f)
        val layout = scene.layout(band)
        assertTrue(layout.elementBounds.isNotEmpty())
        layout.elementBounds.forEach { assertTrue(it.left >= band.left && it.right <= band.right && it.top >= band.top && it.bottom <= band.bottom) }
        for (dark in listOf(true, false)) {
            val bitmap = ImageBitmap(1600, 30)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(1600f, 30f)) {
                drawRect(theme.chrome(dark).tabBar)
                scene.drawFrame(this, layout, dark, 0f)
            }
            val pixels = bitmap.toPixelMap()
            assertTrue((0 until 1600).any { x -> (0 until 30).any { y -> pixels[x, y].toArgb() != theme.chrome(dark).tabBar.toArgb() } })
            System.getenv("INDAGIUM_CAPTURE_TULIPS")?.let { folder ->
                File(folder).mkdirs()
                val image = BufferedImage(1600, 30, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until 30) for (x in 0 until 1600) image.setRGB(x, y, pixels[x, y].toArgb())
                ImageIO.write(image, "png", File(folder, "tulips-scene-${if (dark) "dark" else "light"}.png"))
            }
        }
    }
}
