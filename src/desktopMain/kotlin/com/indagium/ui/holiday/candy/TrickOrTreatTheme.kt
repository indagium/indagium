@file:Suppress("MagicNumber")
package com.indagium.ui.holiday.candy

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.indagium.model.HolidayPack
import com.indagium.model.TabStripPosition
import com.indagium.ui.ThemeColors
import com.indagium.ui.theme
import com.indagium.ui.holiday.*
import com.indagium.ui.holiday.pumpkin.PumpkinIcons
import java.time.MonthDay

internal val CANDY_DARK = theme(
    0xFF1a1230, 0xFF23193c, 0xFF2b2048, 0xFF3e2f62, 0xFFf5edff, 0xFFc8b8e5,
    0xFF9283b0, 0xFFffb21f, 0xFFd8b4fe, 0xFFffb21f, onAc = 0xFF2a1400,
)
internal val CANDY_LIGHT = theme(
    0xFFfff7ec, 0xFFffffff, 0xFFfdeedb, 0xFFefd4b2, 0xFF2b1a3d, 0xFF67567c,
    0xFF9a8aa8, 0xFF7c3aed, 0xFF7c3aed, 0xFFb45309, hv = Color.Black.copy(alpha = .04f),
)

private object CandyTabShape : HolidayTabShape {
    override fun shape(position: TabStripPosition): Shape = RoundedCornerShape(50)
}

/** Candy-corn stripe on the content-facing edge of the active pill. */
private object CandyTabStripe : HolidayTabOrnament {
    override fun draw(scope: DrawScope, position: TabStripPosition, outerEdgePx: Float, dark: Boolean) = with(scope) {
        val colors = candyStripe(dark)
        val length = if (position == TabStripPosition.TOP || position == TabStripPosition.BOTTOM) size.width else size.height
        val inset = 20.dp.toPx().coerceAtMost(length / 4f)
        val span = (length - 2f * inset).coerceAtLeast(0f)
        val thick = 3.dp.toPx()
        colors.forEachIndexed { i, color ->
            val begin = inset + span * i / 3f
            val end = inset + span * (i + 1) / 3f
            val origin = when (position) {
                TabStripPosition.TOP -> Offset(begin, size.height - thick)
                TabStripPosition.BOTTOM -> Offset(begin, 0f)
                TabStripPosition.LEFT -> Offset(size.width - thick, begin)
                TabStripPosition.RIGHT -> Offset(0f, begin)
            }
            val extent = if (position == TabStripPosition.TOP || position == TabStripPosition.BOTTOM) Size(end - begin, thick) else Size(thick, end - begin)
            drawRect(color, origin, extent)
        }
    }
}

internal fun candyStripe(dark: Boolean): List<Color> = if (dark) listOf(Color(0xFFFFD23F), Color(0xFFFF8A2A), Color(0xFFFFF2DF))
else listOf(Color(0xFFF5B800), Color(0xFFFF8A2A), Color(0xFFE9D9C4))

internal object TrickOrTreatTheme : HolidayTheme {
    override val pack = HolidayPack.TRICK_OR_TREAT
    override val label = "Trick or treat"
    override val occasion = "Halloween"
    override val dates = HolidayDates(MonthDay.of(10, 24), MonthDay.of(11, 1))
    override val keywords = listOf("halloween", "trick", "treat", "candy", "cauldron", "potion", "lollipop", "pail", "lightning")
    override val corners = HolidayCorners(tabDp = 16f, toolbarDp = 16, tileDp = 14)
    override val tabShape: HolidayTabShape = CandyTabShape
    override val tabCrossAxisInsetDp = 5f
    override val tabTexture = HolidayTabTexture.PLAIN
    override val tintTabsByGroup = false
    override val tabOrnament: HolidayTabOrnament = CandyTabStripe
    override val newButtonStick = true
    override val icons = PumpkinIcons
    override val tabScene = CandyTabScene
    override val picture = CandyPicture
    override val companion = CandyPail
    override val effects = listOf(CandyRain, CandyLanding, CandyLightning)
    override fun palette(dark: Boolean): ThemeColors = if (dark) CANDY_DARK else CANDY_LIGHT
    override fun chrome(dark: Boolean) = if (dark) HolidayChromeColors(Color(0xFF1e1536), Color(0xFF2f2353), Color.Transparent)
        else HolidayChromeColors(Color(0xFFfbe6cc), Color(0xFFf6dcbc), Color.Transparent)
    override fun tileDividerColors(dark: Boolean): List<Color> = candyStripe(dark)
}
