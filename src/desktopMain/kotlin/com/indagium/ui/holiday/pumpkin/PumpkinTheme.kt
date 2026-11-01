// The palettes and scene colours are literal design values (hex colours from the approved holiday canvas), not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.pumpkin

import androidx.compose.ui.graphics.Color
import com.indagium.model.HolidayPack
import com.indagium.ui.*
import com.indagium.ui.ALPHA_HOVER
import com.indagium.ui.ThemeColors
import com.indagium.ui.holiday.HolidayChromeColors
import com.indagium.ui.holiday.HolidayDates
import com.indagium.ui.holiday.HolidayTheme
import com.indagium.ui.theme
import java.time.MonthDay

/**
 * The colours the pumpkin patch's decorations draw with (tab-strip scene, pumpkin shapes, effects). Separate from [ThemeColors], which the
 * whole UI reads; these are private to this theme (the framework only sees [HolidayChromeColors]).
 */
internal data class PumpkinSceneColors(
    val tabBar: Color,
    val tabBg: Color,
    val sky: Color,
    val hill: Color,
    val moon: Color,
    val crater: Color,
    val pumpkin: Color,
    val pumpkinShade: Color,
    val pumpkinLight: Color,
    val stem: Color,
    val glow: Color,
    val bat: Color,
    val ember: Color,
    /** Faint vertical ribs on an inactive tab. */
    val rib: Color,
)

// Pumpkin patch (design canvas `.t-pumpkin.m-dark` / `.m-light`). The dark accent is bright orange, so text on it is near-black.
val PUMPKIN_DARK = theme(
    0xFF120d18, 0xFF1b1423, 0xFF231a2d, 0xFF36293f, 0xFFf1e6d6, 0xFFc4ae99,
    0xFF8f7c6b, 0xFFff8a2a, 0xFFb48cff, 0xFFffb547,
    onAc = 0xFF1d0d00,
)
val PUMPKIN_LIGHT = theme(
    0xFFfbf3e9, 0xFFfffaf4, 0xFFf6e6d2, 0xFFe6cdb0, 0xFF2b1c12, 0xFF6d5444,
    0xFF98806d, 0xFFc2410c, 0xFF7c3aed, 0xFFb45309,
    hv = Color.Black.copy(ALPHA_HOVER),
)

private val PUMPKIN_BODY = Color(0xFFf07a16)
private val PUMPKIN_SHADE = Color(0xFFa9440a)
private val PUMPKIN_HIGHLIGHT = Color(0xFFffb453)
private val PUMPKIN_STEM = Color(0xFF4f8f3a)
private val PUMPKIN_GLOW = Color(0xFFffd23a)
private val PUMPKIN_EMBER = Color(0xFFffae4a)

private val PUMPKIN_SCENE_DARK = PumpkinSceneColors(
    tabBar = Color(0xFF160f1d), tabBg = Color(0xFF2a1f35), sky = Color(0xFF2a1b42), hill = Color(0xFF0a0710),
    moon = Color(0xFFffe3a3), crater = Color(0xFFf0cc84),
    pumpkin = PUMPKIN_BODY, pumpkinShade = PUMPKIN_SHADE, pumpkinLight = PUMPKIN_HIGHLIGHT,
    stem = PUMPKIN_STEM, glow = PUMPKIN_GLOW, bat = Color(0xFF07040b), ember = PUMPKIN_EMBER,
    rib = Color.Black.copy(alpha = 0.2f),
)
private val PUMPKIN_SCENE_LIGHT = PumpkinSceneColors(
    tabBar = Color(0xFFf3dfc6), tabBg = Color(0xFFefd9bd), sky = Color(0xFFf8cfa0), hill = Color(0xFFb9824f),
    moon = Color(0xFFfff7e6), crater = Color(0xFFf4e2c0),
    pumpkin = PUMPKIN_BODY, pumpkinShade = PUMPKIN_SHADE, pumpkinLight = PUMPKIN_HIGHLIGHT,
    stem = PUMPKIN_STEM, glow = PUMPKIN_GLOW, bat = Color(0xFF4a2c3a), ember = PUMPKIN_EMBER,
    rib = Color(0xFF783c0a).copy(alpha = 0.12f),
)

internal fun pumpkinSceneColors(dark: Boolean): PumpkinSceneColors = if (dark) PUMPKIN_SCENE_DARK else PUMPKIN_SCENE_LIGHT

/** The pumpkin patch: Halloween, Oct 24 – Nov 1, the first [HolidayTheme]. Its art lives in the other Pumpkin* files of this package. */
internal object PumpkinPatchTheme : HolidayTheme {
    override val pack = HolidayPack.PUMPKIN_PATCH
    override val label = "Pumpkin patch"
    override val occasion = "Halloween"
    override val dates = HolidayDates(MonthDay.of(10, 24), MonthDay.of(11, 1))
    override val keywords = listOf("halloween", "pumpkin", "spooky", "orange", "bats", "embers", "lightning", "cat")

    override fun palette(dark: Boolean): ThemeColors = if (dark) PUMPKIN_DARK else PUMPKIN_LIGHT

    override fun chrome(dark: Boolean): HolidayChromeColors = pumpkinSceneColors(dark).let { HolidayChromeColors(it.tabBar, it.tabBg, it.rib) }

    override val tabOrnament = PumpkinStem
    override val icons = PumpkinIcons
    override val tabScene = PumpkinTabScenePainter
    override val picture = PumpkinPicture
    override val companion = PumpkinCat
    override val effects = listOf(PumpkinEmbers, PumpkinBats, PumpkinLightning)
}
