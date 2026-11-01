@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.font.FontFamily
import com.indagium.model.HolidayIntensity
import com.indagium.model.HolidayLightness
import com.indagium.model.HolidayMode
import com.indagium.model.HolidayPack
import com.indagium.model.HolidaySettings
import com.indagium.ui.holiday.manor.HauntedManorTheme
import com.indagium.ui.holiday.manor.MANOR_DARK
import com.indagium.ui.holiday.manor.MANOR_LIGHT
import com.indagium.ui.holiday.manor.ManorFog
import com.indagium.ui.holiday.manor.ManorGhosts
import com.indagium.ui.holiday.manor.ManorLightning
import com.indagium.ui.holiday.manor.manorFogAt
import com.indagium.ui.holiday.manor.manorGhostAt
import com.indagium.ui.holiday.manor.manorTabSceneLayout
import com.indagium.ui.holiday.manor.ManorFogParticle
import com.indagium.ui.holiday.manor.ManorGhostParticle
import com.indagium.ui.holiday.manor.manorBlinkScale
import com.indagium.ui.holiday.resolveHolidayEffects
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HauntedManorThemeTest {
    @Test
    fun pumpkinRemainsTheDefaultAndFirstFallbackWhileManorCanBeSelectedForTheSameDates() {
        assertEquals(HolidayPack.PUMPKIN_PATCH, HolidaySettings().pack)
        assertEquals(HolidayPack.PUMPKIN_PATCH, HOLIDAY_THEMES.values.first().pack)
        assertEquals(listOf(HolidayPack.PUMPKIN_PATCH, HolidayPack.HAUNTED_MANOR), HOLIDAY_THEMES.keys.toList())

        val auto = HolidaySettings(mode = HolidayMode.AUTOMATIC, pack = HolidayPack.HAUNTED_MANOR)
        assertNull(activeHolidayPack(auto, LocalDate.of(2026, 10, 23)))
        assertEquals(HolidayPack.HAUNTED_MANOR, activeHolidayPack(auto, LocalDate.of(2026, 10, 24)))
        assertEquals(HolidayPack.HAUNTED_MANOR, activeHolidayPack(auto, LocalDate.of(2026, 11, 1)))
        assertNull(activeHolidayPack(auto, LocalDate.of(2026, 11, 2)))
        assertEquals(HolidayPack.PUMPKIN_PATCH, activeHolidayPack(auto.copy(pack = HolidayPack.PUMPKIN_PATCH), LocalDate.of(2026, 10, 31)))
    }

    @Test
    fun manorLookUsesItsOwnLightAndDarkPalettesAndSerifChrome() {
        val always = HolidaySettings(mode = HolidayMode.ALWAYS, pack = HolidayPack.HAUNTED_MANOR)
        val dark = resolveHolidayLook(
            com.indagium.model.AppSettings().copy(holiday = always.copy(lightness = HolidayLightness.DARK)),
            LocalDate.of(2026, 5, 1),
        )
        assertNotNull(dark)
        assertTrue(dark.isDark)
        assertSame(MANOR_DARK, dark.colors)
        val light = resolveHolidayLook(
            com.indagium.model.AppSettings().copy(holiday = always.copy(lightness = HolidayLightness.LIGHT)),
            LocalDate.of(2026, 5, 1),
        )
        assertNotNull(light)
        assertFalse(light.isDark)
        assertSame(MANOR_LIGHT, light.colors)
        assertEquals(FontFamily.Serif, HauntedManorTheme.labelFontFamily)
        assertEquals(3, HauntedManorTheme.corners.tileDp)
        assertEquals(HolidayTabTexture.STONE, HauntedManorTheme.tabTexture)
        assertFalse(HauntedManorTheme.tintTabsByGroup)
        assertNull(HauntedManorTheme.tabOrnament)
        assertTrue(ManorGhosts.behindContent)
    }

    @Test
    fun manorEffectsExposeAllSwitchesAndRespectIntensityAndIndividualChoices() {
        val full = resolveHolidayEffects(HolidaySettings(), HauntedManorTheme)
        assertEquals(listOf("manor.ghosts", "manor.fog", "manor.lightning"), full.activeEffects.map { it.id })
        assertTrue(full.moving && full.overLog)
        val calm = resolveHolidayEffects(HolidaySettings(intensity = HolidayIntensity.CALM), HauntedManorTheme)
        assertFalse(calm.overLog)
        assertEquals(45f, calm.flashMinGapSec)
        val quiet = resolveHolidayEffects(
            HolidaySettings(effectToggles = mapOf(ManorGhosts.id to false, ManorLightning.id to false)),
            HauntedManorTheme,
        )
        assertEquals(listOf(ManorFog.id), quiet.activeEffects.map { it.id })
        val still = resolveHolidayEffects(HolidaySettings(intensity = HolidayIntensity.OFF), HauntedManorTheme)
        assertTrue(still.activeEffects.isEmpty())
        assertFalse(still.moving)
        assertTrue(still.tabScene && still.filterPicture && still.notesCompanion && still.funIcons)
        assertEquals("manor.lightning", ManorLightning.id)
    }

    @Test
    fun seededParticlesAndSceneGeometryStayStableAndInsideTheirSlots() {
        val ghostsA = ManorGhosts.spawn(seed = 19, countScale = 1f, speedScale = 1f)
        val ghostsB = ManorGhosts.spawn(seed = 19, countScale = 1f, speedScale = 1f)
        assertEquals(ghostsA.count, ghostsB.count)
        assertEquals(3, ghostsA.count)
        assertEquals(0, ManorFog.spawn(seed = 3, countScale = 1f, speedScale = 0f).count)
        assertEquals(2, ManorFog.spawn(seed = 3, countScale = 1f, speedScale = 1f).count)
        val ghost = ManorGhostParticle(0.45f, 32f, 20f, 0.2f, 0.5f)
        val fog = ManorFogParticle(0.4f, 0.72f, 54f, 30f, 0.3f)
        assertTrue(manorGhostAt(ghost, 7f, 800f, 700f).alpha in 0f..1f)
        assertTrue(manorFogAt(fog, 7f, 800f, 700f).alpha in 0f..0.2f)
        val lowFog = ManorFogParticle(0.3f, 0.984f, 500f, 19f, 0f)
        assertTrue(manorFogAt(lowFog, 7f, 800f, 700f).y >= 680f, "fog hugs the bottom edge")
        assertTrue(manorGhostAt(ghost, 7f, 800f, 700f).y in 530f..545f, "one ghost bobs in place")
        assertEquals(1f, manorBlinkScale(0f))
        assertTrue(manorBlinkScale(4.6f) < 0.1f, "ghosts blink briefly")

        val slots = listOf(
            Rect(100f, 0f, 260f, 36f) to false,
            Rect(40f, 5f, 310f, 58f) to false,
            Rect(0f, 300f, 200f, 800f) to true,
            Rect(20f, 100f, 160f, 510f) to true,
        )
        slots.forEach { (slot, vertical) ->
            val layout = manorTabSceneLayout(slot, vertical)
            layout.elementBounds.forEach { bounds ->
                assertTrue(bounds.left >= slot.left - 0.01f && bounds.right <= slot.right + 0.01f, "$bounds escapes $slot")
                assertTrue(bounds.top >= slot.top - 0.01f && bounds.bottom <= slot.bottom + 0.01f, "$bounds escapes $slot")
            }
        }
    }
}
