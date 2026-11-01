// Dates and counts of the fake theme are literal on purpose.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.indagium.model.HolidayPack
import com.indagium.ui.ThemeColors
import com.indagium.ui.holiday.pumpkin.PumpkinPatchTheme
import java.time.MonthDay

/** A test-only particle effect with no art; off by default, so it also proves the "spec default" rule. */
internal object FakeSparkles : HolidayParticleEffect {
    override val id = "fake.sparkles"
    override val label = "Sparkles"
    override val noun = "sparkles"
    override val defaultOn = false

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField = object : HolidayParticleField {
        override val count = (2 * countScale).toInt()

        override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) = Unit
    }
}

/**
 * A theme with ONLY the required parts: no tab scene, no ornament, no icons, no picture, no companion, one effect. It borrows the pumpkin
 * colours (a test theme needs some) but nothing else, so any pumpkin assumption left in the framework shows up as a crash or a stray row.
 */
internal object FakeHolidayTheme : HolidayTheme {
    override val pack = HolidayPack.PUMPKIN_PATCH
    override val label = "Fake fest"
    override val occasion = "Testing"
    override val dates = HolidayDates(MonthDay.of(6, 1), MonthDay.of(6, 30))
    override val effects = listOf<HolidayEffectSpec>(FakeSparkles)

    override fun palette(dark: Boolean): ThemeColors = PumpkinPatchTheme.palette(dark)

    override fun chrome(dark: Boolean): HolidayChromeColors = PumpkinPatchTheme.chrome(dark)
}

internal val FAKE_THEMES: Map<HolidayPack, HolidayTheme> = holidayThemeRegistry(FakeHolidayTheme)

/** A theme with a companion and no picture: its only backdrop art is the Notes-column one. */
internal object FakeCompanionTheme : HolidayTheme by FakeHolidayTheme {
    override val companion = object : HolidayCompanionPainter {
        override val label = "Little ghost in the Notes corner"
        override val designWidthDp = 120f
        override val designHeightDp = 120f

        override fun draw(scope: DrawScope, t: Float, dark: Boolean) = Unit
    }
}
