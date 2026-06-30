package com.openlog

import com.openlog.model.ThemePreset
import com.openlog.ui.themeColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ThemePaletteTest {
    @Test
    fun themePresetListIncludesAdditionalLightAndSpecialtyThemes() {
        assertEquals(
            listOf(
                "Light",
                "Light Pro",
                "Light Contrast",
                "Nord Light",
                "Warm Paper",
                "Sage Paper",
                "Rose Paper",
                "Ink Paper",
                "Dark (GitHub)",
                "Dracula",
                "Solarized Dark",
                "Graphite Dim",
                "Terminal Dark",
            ),
            ThemePreset.entries.map { it.label },
        )
    }

    @Test
    fun everyThemePresetResolvesToAUsablePalette() {
        ThemePreset.entries.forEach { preset ->
            val colors = themeColors(preset)

            assertNotEquals(colors.bg, colors.p, "${preset.name} needs a distinct panel color")
            assertNotEquals(colors.tx, colors.bg, "${preset.name} needs readable primary text")
            assertNotEquals(colors.ac, colors.bg, "${preset.name} needs a visible accent")
        }
    }

    @Test
    fun lightProUsesAVisiblyDifferentPaletteFromDefaultLight() {
        val light = themeColors(ThemePreset.LIGHT)
        val lightPro = themeColors(ThemePreset.LIGHT_PRO)

        assertNotEquals(light.bg, lightPro.bg)
        assertNotEquals(light.p, lightPro.p)
        assertNotEquals(light.p2, lightPro.p2)
        assertNotEquals(light.ac, lightPro.ac)
    }
}
