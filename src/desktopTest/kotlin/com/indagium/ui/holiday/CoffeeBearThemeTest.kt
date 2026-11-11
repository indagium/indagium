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

class CoffeeBearThemeTest {
    private val theme get() = HOLIDAY_THEMES.getValue(HolidayPack.COFFEE_BEAR)

    @Test fun selectingCoffeeBearUsesItsOwnPaletteAndTurnsItOn() {
        val settings = HolidaySettings(mode = HolidayMode.AUTOMATIC).withPackOn(HolidayPack.COFFEE_BEAR)
        assertEquals(HolidayMode.ALWAYS, settings.mode)
        assertEquals(theme, activeHolidayTheme(settings, LocalDateTime.of(2026, 4, 1, 12, 0)))
        assertFalse(theme.supportsAutomatic)
        assertEquals(Color(0xFF0f1b2b), theme.palette(true).bg)
        assertEquals(Color(0xFFf6f3ea), theme.palette(false).bg)
        assertEquals(Color(0xFFff7466), theme.palette(true).ac)
        assertEquals(Color(0xFFc8322b), theme.palette(false).ac)
        assertEquals("Find", assertNotNull(theme.icons?.findIcon(Color.Black)).name)
    }

    @Test fun effectsRespectIndividualSwitchesAndIntensity() {
        val settings = HolidaySettings(mode = HolidayMode.ALWAYS, pack = HolidayPack.COFFEE_BEAR)
        val full = resolveHolidayEffects(settings, theme)
        assertTrue(full.activeEffects.isNotEmpty())
        for (effect in full.activeEffects) {
            val toggled = resolveHolidayEffects(settings.copy(effectToggles = mapOf(effect.id to false)), theme)
            assertFalse(toggled.activeEffects.any { it.id == effect.id })
        }
        assertTrue(resolveHolidayEffects(settings.copy(intensity = HolidayIntensity.CALM), theme).countScale < full.countScale)
        val off = resolveHolidayEffects(settings.copy(intensity = HolidayIntensity.OFF), theme)
        assertTrue(off.activeEffects.none { it is HolidayParticleEffect || it.moves }, "Off retains still scenery but stops overlay effects")
    }

    @Test fun northernLightsCanBeSwitchedOffIndependentlyAndRemainStillAtOffIntensity() {
        val lights = theme.effects.filterIsInstance<HolidaySceneEffect>().single()
        val settings = HolidaySettings(mode = HolidayMode.ALWAYS, pack = HolidayPack.COFFEE_BEAR, intensity = HolidayIntensity.OFF)
        assertTrue(resolveHolidayEffects(settings, theme).activeEffects.any { it.id == lights.id })
        assertFalse(resolveHolidayEffects(settings.copy(effectToggles = mapOf(lights.id to false)), theme).activeEffects.any { it.id == lights.id })
        val scene = assertNotNull(theme.tabScene)
        val layout = scene.layout(Rect(0f, 0f, 1600f, 30f))
        fun frame(on: Boolean): ImageBitmap = ImageBitmap(1600, 30).also { bitmap ->
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(1600f, 30f)) {
                scene.drawFrame(this, layout, true, HOLIDAY_STILL_TIME, if (on) setOf(lights.id) else emptySet())
            }
        }
        val on = frame(true).toPixelMap(); val off = frame(false).toPixelMap()
        assertTrue((0 until 1600).any { x -> (0 until 30).any { y -> on[x,y].toArgb() != off[x,y].toArgb() } }, "the scene honors the northern-lights switch")
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
        System.getenv("INDAGIUM_CAPTURE_BEAR")?.let { folder ->
            File(folder).mkdirs()
            val image = BufferedImage(1080, 460, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 460) for (x in 0 until 1080) image.setRGB(x, y, pixels[x, y].toArgb())
            ImageIO.write(image, "png", File(folder, "bear-artwork.png"))
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
            System.getenv("INDAGIUM_CAPTURE_BEAR")?.let { folder ->
                File(folder).mkdirs()
                val image = BufferedImage(1600, 30, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until 30) for (x in 0 until 1600) image.setRGB(x, y, pixels[x, y].toArgb())
                ImageIO.write(image, "png", File(folder, "bear-scene-${if (dark) "dark" else "light"}.png"))
            }
        }
    }
}
