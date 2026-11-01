package com.indagium.ui.holiday

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.indagium.model.AppSettings
import com.indagium.model.HolidayIntensity
import com.indagium.model.HolidayLightness
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.model.HolidaySettings
import com.indagium.ui.holiday.candy.*
import com.indagium.ui.holiday.pumpkin.PumpkinIcons
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDate
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TrickOrTreatThemeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun registrationDatesPaletteAndEffects() {
        val settings = HolidaySettings(mode = HolidayMode.AUTOMATIC, pack = HolidayPack.TRICK_OR_TREAT)
        assertNull(activeHolidayPack(settings, LocalDate.of(2026, 10, 23)))
        assertEquals(HolidayPack.TRICK_OR_TREAT, activeHolidayPack(settings, LocalDate.of(2026, 10, 24)))
        assertEquals(HolidayPack.TRICK_OR_TREAT, activeHolidayPack(settings, LocalDate.of(2026, 11, 1)))
        assertNull(activeHolidayPack(settings, LocalDate.of(2026, 11, 2)))
        assertEquals(HolidayPack.PUMPKIN_PATCH, HolidaySettings().pack)
        for ((mode, palette) in listOf(HolidayLightness.DARK to CANDY_DARK, HolidayLightness.LIGHT to CANDY_LIGHT)) {
            val look = resolveHolidayLook(AppSettings().copy(holiday = settings.copy(mode = HolidayMode.ALWAYS, lightness = mode)), LocalDate.of(2026, 5, 1))
            assertNotNull(look)
            assertSame(palette, look.colors)
        }
        assertSame(PumpkinIcons, TrickOrTreatTheme.icons)
        val fx = resolveHolidayEffects(settings, TrickOrTreatTheme)
        assertEquals(listOf("candy.rain", "candy.landing"), fx.activeEffects.map { it.id })
        assertFalse(CandyLightning.defaultOn)
        assertTrue(CandyLanding is HolidayLandingEffect)
        assertEquals(0, CandyRain.spawn(1, 1f, 0f).count)
        assertEquals(26, CandyRain.spawn(1, 1f, 1f).count)
        assertEquals(12, CandyLanding.spawn(1, 1f, 1f).count)
        assertTrue(resolveHolidayEffects(settings.copy(intensity = HolidayIntensity.OFF), TrickOrTreatTheme).activeEffects.isEmpty())
        val areas = HolidayLandingAreas()
        val key = Any()
        areas.set(key, Rect(20f, 30f, 220f, 120f))
        assertEquals(1, areas.bounds().size)
        areas.remove(key)
        assertTrue(areas.bounds().isEmpty())
    }

    @Test fun artHasTransparentEdgesAndRendersBothPalettes() {
        rule.setContent {
            Row(Modifier.testTag("candy-art")) {
                for (dark in listOf(true, false)) {
                    val bg = if (dark) Color(0xFF1a1230) else Color(0xFFfff7ec)
                    Canvas(Modifier.size(255.dp, 357.dp).background(bg)) { scale(size.width / 300f, pivot = Offset.Zero) { CandyPicture.draw(this, 0f, dark) } }
                    Canvas(Modifier.size(204.dp, 204.dp).background(bg)) { scale(size.width / 240f, pivot = Offset.Zero) { CandyPail.draw(this, 0f, dark) } }
                }
            }
        }
        val image = rule.onNodeWithTag("candy-art").captureToImage()
        val pixels = image.toPixelMap()
        if (java.lang.Boolean.getBoolean("indagium.captureCandy")) {
            val result = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until image.height) for (x in 0 until image.width) result.setRGB(x, y, pixels[x, y].toArgb())
            ImageIO.write(result, "png", File(System.getProperty("java.io.tmpdir"), "trick-or-treat-art.png"))
        }
        val density = rule.density.density
        assertEquals(Color(0xFF1a1230).toArgb(), pixels[(2f * density).toInt(), (331f * density).toInt()].toArgb())
        assertEquals(Color(0xFFfff7ec).toArgb(), pixels[(461f * density).toInt(), (331f * density).toInt()].toArgb())
    }

    @Test fun landedCandyFollowsLiveTileBounds() {
        val field = CandyLanding.spawn(42, 1f, 1f) as HolidayLandingField
        rule.setContent {
            Canvas(Modifier.size(300.dp, 200.dp).background(Color.Black).testTag("landed-candy")) {
                scale(density, pivot = Offset.Zero) {
                    field.drawLanding(this, 0f, 300f, 200f, true, listOf(Rect(40f, 100f, 260f, 180f)))
                }
            }
        }
        val pixels = rule.onNodeWithTag("landed-candy").captureToImage().toPixelMap()
        val d = rule.density.density
        val nearEdge = (40..260).any { x -> (90..100).any { y -> pixels[(x * d).toInt(), (y * d).toInt()] != Color.Black } }
        val emptyArea = (40..260).all { x -> (130..140).all { y -> pixels[(x * d).toInt(), (y * d).toInt()] == Color.Black } }
        assertTrue(nearEdge, "landed sweets appear at the tile's top edge")
        assertTrue(emptyArea, "landed sweets do not use arbitrary window coordinates")
    }

    @Test fun landingAnchorsAreRemovedWhenTheirTilesDisappear() {
        val settings = AppSettings().copy(holiday = HolidaySettings(mode = HolidayMode.ALWAYS, pack = HolidayPack.TRICK_OR_TREAT))
        val look = resolveHolidayLook(settings, LocalDate.of(2026, 10, 31))
        val areas = HolidayLandingAreas()
        var visible by mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalHoliday provides look, LocalHolidayLandingAreas provides areas) {
                if (visible) Box(Modifier.size(100.dp, 40.dp).holidayLandingAnchor())
            }
        }
        rule.waitForIdle()
        assertEquals(1, areas.bounds().size)
        rule.runOnIdle { visible = false }
        rule.waitForIdle()
        assertTrue(areas.bounds().isEmpty())
    }
}
