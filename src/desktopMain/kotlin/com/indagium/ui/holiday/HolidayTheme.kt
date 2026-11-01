package com.indagium.ui.holiday

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontFamily
import com.indagium.model.HolidayPack
import com.indagium.model.TabStripPosition
import com.indagium.ui.ThemeColors
import java.time.LocalDateTime

// The contract every seasonal theme implements (see docs/SAAD.md "Seasonal themes"). The framework in this package (resolver, overlay,
// chrome, Layout menu, Settings section, codec) knows nothing pack-specific: it only calls what is declared here. A new pack is ONE
// HolidayTheme object (plus its art) registered in HOLIDAY_THEMES; the optional capabilities are nullable / empty, and every row or
// drawing that depends on one disappears when the theme leaves it out.

/** The few colours the framework itself draws with: the tab bar's background, an idle tab's fill and the faint ribs on it. */
internal data class HolidayChromeColors(val tabBar: Color, val tabBg: Color, val rib: Color)

/** Corner radii in dp. The defaults are the framework's; a theme may return its own. */
internal data class HolidayCorners(val tabDp: Float = DEFAULT_TAB_DP, val toolbarDp: Int = DEFAULT_TOOLBAR_DP, val tileDp: Int = DEFAULT_TILE_DP) {
    companion object {
        const val DEFAULT_TAB_DP = 14f
        const val DEFAULT_TOOLBAR_DP = 14
        const val DEFAULT_TILE_DP = 12
        val DEFAULT = HolidayCorners()
    }
}

/** An ornament drawn on the active tab (the pumpkin's stem), at the tab's outer edge [outerEdgePx] in from its cell's side away from the content. */
internal fun interface HolidayTabOrnament {
    fun draw(scope: DrawScope, position: TabStripPosition, outerEdgePx: Float, dark: Boolean)
}

/** A dashed divider: [dash] runs of [dashDp] separated by [gap] runs of [gapDp], [heightDp] tall. */
internal data class HolidayDividerDashes(val dash: Color, val gap: Color, val dashDp: Float = 18f, val gapDp: Float = 2f, val heightDp: Float = 2f)

/** Optional pack-specific tab silhouette (for example, a tombstone with a rounded crown and a flat base). */
internal fun interface HolidayTabShape {
    fun shape(position: TabStripPosition): Shape
}

internal enum class HolidayTabTexture { RIBS, STONE, PLAIN }

/** The theme's toolbar pictures: name, mnemonic and vector per [ToolbarIconId], line work in [outline] (the toolbar's text colour). */
internal fun interface HolidayIconSet {
    fun icons(outline: Color): Map<ToolbarIconId, HolidayIcon>

    fun findIcon(outline: Color): HolidayIcon? = null
}

/** The geometry of a scene inside its band: [band] and the rectangle of every element it draws (all of them must lie inside [band]). */
internal interface HolidaySceneLayout {
    val band: Rect
    val elementBounds: List<Rect>
}

/**
 * The picture behind the tabs of a horizontal tab bar on the top edge (the native title bar cannot be themed, so this stands in for it). The
 * framework fills the bar with the chrome's bar colour and gives the painter the tab strip's span of it (as tall as the bar, never under a
 * toolbar sharing the bar); the painter lays its composition out on the 1600 x 30 title bar it was authored on (see [HolidayBandSpace]) and
 * draws only over that colour. The tabs are drawn over it.
 */
internal interface HolidayScenePainter {
    val animated: Boolean get() = false

    /** Lays the scene out inside [band] (pixels: the tab strip's span of the bar). */
    fun layout(band: Rect): HolidaySceneLayout

    /** Paints [layout] (its own coordinates, the band's pixels). The bar colour itself is drawn by the framework. */
    fun draw(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean)

    fun drawFrame(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean, time: Float) = draw(scope, layout, dark)
}

/** A backdrop art: the picture under the filter sections, drawn at time [t] seconds in its own [designWidthDp] x [designHeightDp] coordinates. */
internal interface HolidayPicturePainter {
    val designWidthDp: Float
    val designHeightDp: Float

    fun draw(scope: DrawScope, t: Float, dark: Boolean)
}

/**
 * The figure in the bottom-right corner of the side column that holds the Notes tile: a [designWidthDp] x [designHeightDp] picture (most are
 * square, a theme may add something above or beside its figure). Like the filter picture it is a backdrop art the framework places and fades
 * behind covering cards (HolidayBackdropLayout.kt); the theme only draws it, inside that rectangle.
 */
internal interface HolidayCompanionPainter {
    /** Shown in the Layout menu and Settings ("Black cat in the Notes corner"). */
    val label: String
    val designWidthDp: Float
    val designHeightDp: Float

    fun draw(scope: DrawScope, t: Float, dark: Boolean)
}

/** One seasonal theme. See the file comment; the optional parts default to "not provided". */
internal interface HolidayTheme {
    /** The persisted id (the enum stays the stored value). */
    val pack: HolidayPack
    val label: String

    /** The holiday it celebrates, for its card ("Halloween"). */
    val occasion: String

    /** The days it applies in Automatic mode. */
    val dates: HolidayDates

    /** Whether Automatic mode applies at this local time; packs may use schedules other than yearly dates. */
    fun automaticAt(now: LocalDateTime): Boolean = dates.contains(now.toLocalDate())

    /** Human-readable description of the Automatic schedule. */
    val automaticLabel: String? get() = null

    /** Extra words that find the theme's Settings rows in the settings search. */
    val keywords: List<String> get() = emptyList()

    fun palette(dark: Boolean): ThemeColors

    fun chrome(dark: Boolean): HolidayChromeColors

    val corners: HolidayCorners get() = HolidayCorners.DEFAULT

    /** Optional label typeface. Log text and explicitly chosen monospace text keep their own fonts. */
    val labelFontFamily: FontFamily? get() = null
    val tabShape: HolidayTabShape? get() = null

    /** Optional symmetric inset on the cross-axis of a tab strip. */
    val tabCrossAxisInsetDp: Float? get() = null
    val tabTexture: HolidayTabTexture get() = HolidayTabTexture.RIBS
    val tintTabsByGroup: Boolean get() = true
    val tabOrnament: HolidayTabOrnament? get() = null

    /** Optional decoration on toolbar button groups. */
    val toolbarOrnament: HolidayTabOrnament? get() = null
    val newButtonStick: Boolean get() = false

    /** Optional three colors for the three segments of an expanded tile's divider. */
    fun tileDividerColors(dark: Boolean): List<Color>? = null

    /** Optional dashed divider under an open tile's header (replaces the plain line; ignored when [tileDividerColors] is given). */
    fun tileDividerDashes(dark: Boolean): HolidayDividerDashes? = null

    /**
     * Optional wash on the active tab: the accent at this alpha at the tab's outer edge, fading to transparent over three quarters of its
     * depth. Null leaves the plain fill.
     */
    fun activeTabWashAlpha(dark: Boolean): Float? = null

    val icons: HolidayIconSet? get() = null

    /** The picture behind the tabs of a horizontal top tab bar (the "Scene in the title bar" switch). */
    val tabScene: HolidayScenePainter? get() = null
    val picture: HolidayPicturePainter? get() = null
    val companion: HolidayCompanionPainter? get() = null
    val effects: List<HolidayEffectSpec> get() = emptyList()
}
