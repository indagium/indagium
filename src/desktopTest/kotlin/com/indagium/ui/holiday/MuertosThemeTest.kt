@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.indagium.model.HolidayIntensity
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.model.HolidaySettings
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MuertosThemeTest {
    @get:Rule val rule = createComposeRule()

    private val theme get() = HOLIDAY_THEMES.getValue(HolidayPack.DIA_DE_MUERTOS)

    @Test fun isTheLastRegisteredPackSoTheHalloweenPacksKeepTheirTieBreak() {
        assertEquals(HolidayPack.DIA_DE_MUERTOS, HOLIDAY_THEMES.keys.last())
        assertEquals("Día de Muertos", theme.label)
        assertEquals("Día de Muertos", theme.occasion)
        assertTrue(theme.supportsAutomatic)
    }

    @Test fun isAutomaticFromOctober31ToNovember2WhenSelected() {
        val settings = HolidaySettings(mode = HolidayMode.AUTOMATIC, pack = HolidayPack.DIA_DE_MUERTOS)
        // Oct 30 is Halloween season: Muertos is not active, the registry falls back to the pumpkin.
        assertEquals(HolidayPack.PUMPKIN_PATCH, activeHolidayPack(settings, LocalDate.of(2026, 10, 30)))
        assertEquals(HolidayPack.DIA_DE_MUERTOS, activeHolidayPack(settings, LocalDate.of(2026, 10, 31)))
        assertEquals(HolidayPack.DIA_DE_MUERTOS, activeHolidayPack(settings, LocalDate.of(2026, 11, 1)))
        assertEquals(HolidayPack.DIA_DE_MUERTOS, activeHolidayPack(settings, LocalDate.of(2026, 11, 2)))
        assertNull(activeHolidayPack(settings, LocalDate.of(2026, 11, 3)))
    }

    @Test fun whenPumpkinPatchIsSelectedItWinsNovember1AndTheRegistryFallbackGivesNovember2ToMuertos() {
        val settings = HolidaySettings(mode = HolidayMode.AUTOMATIC, pack = HolidayPack.PUMPKIN_PATCH)
        assertEquals(HolidayPack.PUMPKIN_PATCH, activeHolidayPack(settings, LocalDate.of(2026, 11, 1)))
        assertEquals(HolidayPack.DIA_DE_MUERTOS, activeHolidayPack(settings, LocalDate.of(2026, 11, 2)))
        // On the days both windows hold, the Halloween pack is first in the registry.
        val anytime = HolidaySettings(mode = HolidayMode.AUTOMATIC, pack = HolidayPack.TULIPS)
        assertEquals(HolidayPack.PUMPKIN_PATCH, activeHolidayPack(anytime, LocalDate.of(2026, 10, 31)))
    }

    @Test fun usesItsOwnPalettesAndChrome() {
        assertEquals(Color(0xFF180c22), theme.palette(true).bg)
        assertEquals(Color(0xFFff5fae), theme.palette(true).ac)
        assertEquals(Color(0xFFfff3e0), theme.palette(false).bg)
        assertEquals(Color(0xFFc4006a), theme.palette(false).ac)
        assertEquals(Color(0xFFffb03a), theme.palette(true).seq2)
        assertEquals(Color(0xFFb4530a), theme.palette(false).seq2)
        assertEquals(Color(0xFF140a1d), theme.chrome(true).tabBar)
        assertEquals(Color(0xFFfbe0b8), theme.chrome(false).tabBar)
        assertEquals("Find", assertNotNull(theme.icons?.findIcon(Color.Black)).name)
        assertEquals(HolidayTabTexture.PLAIN, theme.tabTexture)
        assertFalse(theme.tintTabsByGroup)
    }

    @Test fun effectsAreLabelledRespectSwitchesAndStopMovingAtOff() {
        assertEquals(listOf("muertos.petals", "muertos.monarchs"), theme.effects.map { it.id })
        assertEquals(listOf("Marigold petals", "Monarch butterflies"), theme.effects.map { it.label })
        assertEquals(listOf("petals", "monarchs"), theme.effects.map { it.noun })
        val settings = HolidaySettings(mode = HolidayMode.ALWAYS, pack = HolidayPack.DIA_DE_MUERTOS)
        val full = resolveHolidayEffects(settings, theme)
        assertEquals(2, full.activeEffects.size)
        for (effect in full.activeEffects) {
            val toggled = resolveHolidayEffects(settings.copy(effectToggles = mapOf(effect.id to false)), theme)
            assertFalse(toggled.activeEffects.any { it.id == effect.id })
        }
        val off = resolveHolidayEffects(settings.copy(intensity = HolidayIntensity.OFF), theme)
        assertTrue(off.activeEffects.none { it is HolidayParticleEffect || it.moves }, "Off has no moving effects")
    }

    @Test fun particleFieldsHaveTheDesignCounts() {
        val petals = (theme.effects[0] as HolidayParticleEffect).spawn(7, 1f, 1f)
        val monarchs = (theme.effects[1] as HolidayParticleEffect).spawn(7, 1f, 1f)
        assertEquals(26, petals.count)
        assertEquals(4, monarchs.count)
        assertEquals(0, (theme.effects[0] as HolidayParticleEffect).spawn(7, 1f, 0f).count)
    }

    @Test fun paperPicadoDividerIsReturnedForBothPalettes() {
        val expected = listOf(0xFFe6007e, 0xFFff9a1f, 0xFF12b5b0, 0xFF8a3fd1, 0xFFffc02e).map { Color(it) }
        for (dark in listOf(true, false)) {
            val blocks = assertNotNull(theme.tileDividerBlocks(dark))
            assertEquals(expected, blocks.colors)
            assertEquals(34f, blocks.blockDp)
            assertEquals(3f, blocks.heightDp)
            assertNull(theme.tileDividerColors(dark))
            assertNull(theme.tileDividerGradient(dark))
            assertNull(theme.tileDividerDashes(dark))
        }
        assertTrue(HOLIDAY_THEMES.values.filter { it.pack != HolidayPack.DIA_DE_MUERTOS }.all { it.tileDividerBlocks(true) == null })
    }

    @Test fun blockDividerPaintsHardBlocksThatRepeat() {
        val blocks = assertNotNull(theme.tileDividerBlocks(true))
        rule.setContent { Box(Modifier.width(204.dp).testTag("divider")) { HolidayBlockDivider(blocks) } }
        val pixels = rule.onNodeWithTag("divider").captureToImage().toPixelMap()
        val density = rule.density.density

        fun colourAt(dp: Float) = pixels[(dp * density).toInt(), 1].toArgb()
        blocks.colors.forEachIndexed { i, c -> assertEquals(c.toArgb(), colourAt(i * 34f + 17f), "block $i") }
        assertEquals(blocks.colors[0].toArgb(), colourAt(5 * 34f + 10f), "the stripe repeats from the first colour")
        assertEquals(pixels.height, (3 * density).toInt(), "three dp tall")
    }
}
