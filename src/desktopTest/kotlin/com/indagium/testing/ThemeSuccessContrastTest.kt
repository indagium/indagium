package com.indagium.testing

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.indagium.model.ThemePreset
import com.indagium.ui.themeColors
import kotlin.test.Test
import kotlin.test.assertTrue

/** The Tests workspace colours its pass statuses with `success`: it must stay legible and green on every preset. */
class ThemeSuccessContrastTest {
    private val minimumContrast = 3.0
    private val luminanceOffset = 0.05 // WCAG relative-luminance flare term

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + luminanceOffset) / (minOf(la, lb) + luminanceOffset)
    }

    @Test
    fun successReachesThreeToOneOnEveryPresetBackgroundAndPanel() {
        ThemePreset.entries.forEach { preset ->
            val colors = themeColors(preset)
            assertTrue(contrast(colors.success, colors.bg) >= minimumContrast, "$preset success on bg: ${contrast(colors.success, colors.bg)}")
            assertTrue(contrast(colors.success, colors.p) >= minimumContrast, "$preset success on panel: ${contrast(colors.success, colors.p)}")
        }
    }

    @Test
    fun successIsGreenerThanTheSeqColourThatOkReuses() {
        // `ok` is a preset's seq1 (purple or blue in most presets); `success` must read as green on all of them.
        ThemePreset.entries.forEach { preset ->
            val success = themeColors(preset).success
            assertTrue(success.green > success.red && success.green > success.blue, "$preset success is not green: $success")
        }
    }
}
