// The palettes are the approved manor canvas values; the scene colours are illustration details.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.manor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.indagium.model.HolidayPack
import com.indagium.ui.ThemeColors
import com.indagium.ui.holiday.HolidayChromeColors
import com.indagium.ui.holiday.HolidayCorners
import com.indagium.ui.holiday.HolidayDates
import com.indagium.ui.holiday.HolidayTabShape
import com.indagium.ui.holiday.HolidayTabTexture
import com.indagium.ui.holiday.HolidayTheme
import com.indagium.ui.holiday.pumpkin.PumpkinIcons
import com.indagium.ui.theme
import java.time.MonthDay

internal data class ManorSceneColors(
    val tabBar: Color,
    val tabBg: Color,
    val sky: Color,
    val hill: Color,
    val moon: Color,
    val crater: Color,
    val bat: Color,
    val fog: Color,
    val flash: Color,
    val lantern: Color,
    val candle: Color,
    val web: Color,
    val stone: Color,
    val rib: Color,
)

val MANOR_DARK: ThemeColors = theme(
    0xFF0d1311, 0xFF141c19, 0xFF1a2420, 0xFF27352f,
    0xFFdfe3d6, 0xFFa2ad9d, 0xFF717d6f,
    0xFF7fe0a6, 0xFF7fe0a6, 0xFFd29922,
    onAc = 0xFF06160d,
)

val MANOR_LIGHT: ThemeColors = theme(
    0xFFedf0ea, 0xFFf9faf6, 0xFFe3e8df, 0xFFc5cec0,
    0xFF1d2620, 0xFF526057, 0xFF808c83,
    0xFF2e7a4c, 0xFF2e7a4c, 0xFFbc7a1f,
    hv = Color.Black.copy(alpha = 0.04f),
)

private val MANOR_SCENE_DARK = ManorSceneColors(
    tabBar = Color(0xFF0f1714), tabBg = Color(0xFF26302c), sky = Color(0xFF1f302a), hill = Color(0xFF060a08),
    moon = Color(0xFFe8f3dc), crater = Color(0xFFcfdcc3), bat = Color(0xFF030504),
    fog = Color(0xFFbed7c8).copy(alpha = 0.13f), flash = Color(0xFFe3f6ea), lantern = Color(0xFFffd166),
    candle = Color(0xFFf3c96b), web = Color(0xFFa2ad9d), stone = Color(0xFF34443a), rib = Color.White.copy(alpha = 0.12f),
)
private val MANOR_SCENE_LIGHT = ManorSceneColors(
    tabBar = Color(0xFFdde3d7), tabBg = Color(0xFFcdd5c7), sky = Color(0xFFcfd8cb), hill = Color(0xFF6f7d6c),
    moon = Color.White, crater = Color(0xFFe3e9de), bat = Color(0xFF3c463f),
    fog = Color(0xFFffffff).copy(alpha = 0.5f), flash = Color.White, lantern = Color(0xFFe0a526),
    candle = Color(0xFFbd7e27), web = Color(0xFF526057), stone = Color(0xFF7b887a), rib = Color(0xFF304334).copy(alpha = 0.14f),
)

internal fun manorSceneColors(dark: Boolean): ManorSceneColors = if (dark) MANOR_SCENE_DARK else MANOR_SCENE_LIGHT

/** Halloween's weathered manor look. Pumpkin patch remains the default pack and registry fallback. */
internal object HauntedManorTheme : HolidayTheme {
    override val pack = HolidayPack.HAUNTED_MANOR
    override val label = "Haunted manor"
    override val occasion = "Halloween"
    override val dates = HolidayDates(MonthDay.of(10, 24), MonthDay.of(11, 1))
    override val keywords = listOf("halloween", "haunted", "manor", "ghost", "cobweb", "candle", "spider", "tombstone", "fog", "lightning")
    override val corners = HolidayCorners(tabDp = 16f, toolbarDp = 3, tileDp = 3)
    override val labelFontFamily: FontFamily = FontFamily.Serif
    override val tabShape: HolidayTabShape = ManorTabShape
    override val tabTexture = HolidayTabTexture.STONE
    override val tintTabsByGroup = false
    override val icons = PumpkinIcons
    override val tabScene = ManorTabScenePainter
    override val picture = ManorPicture
    override val companion = ManorCompanion
    override val effects = listOf(ManorGhosts, ManorFog, ManorLightning)

    override fun palette(dark: Boolean): ThemeColors = if (dark) MANOR_DARK else MANOR_LIGHT

    override fun chrome(dark: Boolean): HolidayChromeColors = manorSceneColors(dark).let { HolidayChromeColors(it.tabBar, it.tabBg, it.rib) }
}
