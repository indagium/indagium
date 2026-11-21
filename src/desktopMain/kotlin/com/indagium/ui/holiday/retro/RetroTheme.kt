@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.retro

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.indagium.model.HolidayPack
import com.indagium.model.TabStripPosition
import com.indagium.ui.holiday.*
import com.indagium.ui.holiday.snow.withTabFrame
import com.indagium.ui.theme
import java.time.LocalDateTime
import java.time.MonthDay

private val NEON_DARK = Color(0xFFff4fd8)
private val NEON_LIGHT = Color(0xFFc42aa8)
private val CYAN_DARK = Color(0xFF00f0ff)
private val CYAN_LIGHT = Color(0xFF0097b2)

private const val GLOW_STEPS = 5

/**
 * Retro's neon on the active tab (the design's `box-shadow: 0 0 0 1px neon, 0 0 10px accent-bg, inset 0 -2px 0 cyan`): a 1 dp neon ring just outside
 * the tab, a faint glow of widening rings around it, and a 2 dp cyan bar along the tab's inner edge. Drawn in the tab frame ([withTabFrame]) over the
 * tab, outside its clip; the tab sits 1 dp in from its cell's ends and its inner side is the cell's.
 */
internal object NeonGlow : HolidayTabOrnament {
    override fun draw(scope: DrawScope, position: TabStripPosition, outerEdgePx: Float, dark: Boolean) = with(scope) {
        val dp = 1.dp.toPx()
        val neon = if (dark) NEON_DARK else NEON_LIGHT
        val cyan = if (dark) CYAN_DARK else CYAN_LIGHT
        val across = if (position == TabStripPosition.TOP || position == TabStripPosition.BOTTOM) size.height else size.width
        val depth = across - outerEdgePx
        withTabFrame(position, outerEdgePx) { length ->
            val left = 1f * dp
            val width = length - 2f * dp
            val radius = CornerRadius(2f * dp)
            // The glow: rings widening from the ring, each fainter (together about the design's .15 over 10 px).
            for (step in GLOW_STEPS downTo 1) {
                val grow = (1f + step * 1.3f) * dp
                drawRoundRect(
                    neon.copy(alpha = .05f * (GLOW_STEPS - step + 1) / GLOW_STEPS), Offset(left - grow, -grow), Size(width + 2f * grow, depth + 2f * grow),
                    CornerRadius(2f * dp + grow), Stroke(2f * dp),
                )
            }
            drawRoundRect(neon, Offset(left - .5f * dp, -.5f * dp), Size(width + dp, depth + dp), radius, Stroke(dp))
            drawRect(cyan, Offset(left + 1.5f * dp, depth - 3.5f * dp), Size(width - 3f * dp, 2f * dp))
        }
    }
}

/**
 * Retro: a grid sun, palms and a cassette on a deep purple night (dark) or pale pink (light), with neon-magenta accents, cyan details, a neon glow
 * on the active tab and a neon-to-cyan rule under open tiles. A theme chosen by hand (like Ready prompt), so it has no dates of its own and never
 * displaces a scheduled holiday. The still art is SVG (resources/holiday/retro); the shooting stars, the neon car and the scanlines are native.
 */
internal object RetroTheme : HolidayTheme {
    override val pack = HolidayPack.RETRO
    override val label = "Retro"
    override val category = HolidayCategory.FUN
    override val occasion = "Any time"
    override val dates = HolidayDates(MonthDay.of(1, 1), MonthDay.of(12, 31))

    // An anytime theme is selected explicitly; it must not displace scheduled holidays.
    override fun automaticAt(now: LocalDateTime) = false

    override val supportsAutomatic = false
    override val keywords = listOf(
        "retro", "synthwave", "vaporwave", "80s", "eighties", "neon", "vhs", "cassette", "sunset", "grid", "palm", "scanlines", "car",
    )
    override val corners = HolidayCorners(tabDp = 2f, toolbarDp = 2, tileDp = 4)
    override val tabTexture = HolidayTabTexture.PLAIN
    override val tintTabsByGroup = false
    override val tabOrnament: HolidayTabOrnament = NeonGlow

    // The design's `linear-gradient(90deg, neon, cyan)` under an open tile, 2 dp in the design and the 3 dp of a gradient divider here.
    override fun tileDividerGradient(dark: Boolean) = if (dark) listOf(NEON_DARK, CYAN_DARK) else listOf(NEON_LIGHT, CYAN_LIGHT)

    override val icons = RetroIcons
    override val tabScene: HolidayScenePainter = HolidayBandScene("retro", RETRO_TITLE)
    override val picture: HolidayPicturePainter = HolidayLayerPicture("retro", RETRO_PICTURE)
    override val companion: HolidayCompanionPainter = HolidayLayerCompanion("Mixtape in the Notes corner", "retro", RETRO_COMPANION)
    override val effects: List<HolidayEffectSpec> = listOf(ShootingStars, NeonCar, VhsScanlines)

    override fun palette(dark: Boolean) = if (dark) theme(
        0xFF120a24, 0xFF1a0f33, 0xFF22143f, 0xFF3d2470, 0xFFf5e9ff,
        0xFFc9aaef, 0xFF8f77b8, 0xFFff4fd8, 0xFFff4fd8, 0xFF00f0ff, onAc = 0xFF1a0020,
    ).copy(abg = Color(0xFFff4fd8).copy(alpha = .15f), sl = Color(0xFFff4fd8).copy(alpha = .18f),
        hv = Color.White.copy(alpha = .05f), searchMatchBg = Color(0xFF00f0ff).copy(alpha = .3f),
        searchCurrentBg = Color(0xFFff4fd8).copy(alpha = .5f)) else theme(
        0xFFfdf4ff, 0xFFffffff, 0xFFf5e6ff, 0xFFe0c4f5, 0xFF2a1040,
        0xFF6b4a8c, 0xFF9a85b0, 0xFFb8148e, 0xFFb8148e, 0xFF0097b2,
        hv = Color.Black.copy(alpha = .04f),
    ).copy(abg = Color(0xFFb8148e).copy(alpha = .1f), sl = Color(0xFFb8148e).copy(alpha = .13f),
        searchMatchBg = Color(0xFF00aac8).copy(alpha = .25f), searchCurrentBg = Color(0xFFb8148e).copy(alpha = .28f))

    override fun chrome(dark: Boolean) = if (dark)
        HolidayChromeColors(Color(0xFF150c2c), Color(0xFF261749), Color.Transparent)
    else HolidayChromeColors(Color(0xFFf0dcff), Color(0xFFe8ccfa), Color.Transparent)
}
