package com.indagium.ui.holiday

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.indagium.model.HolidayPack
import com.indagium.model.TabStripPosition
import com.indagium.ui.ThemeColors

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

/** The theme's toolbar pictures: name, mnemonic and vector per [ToolbarIconId], line work in [outline] (the toolbar's text colour). */
internal fun interface HolidayIconSet {
    fun icons(outline: Color): Map<ToolbarIconId, HolidayIcon>
}

/** The geometry of a scene inside its slot: [slot] and the rectangle of every element it draws (all of them must lie inside [slot]). */
internal interface HolidaySceneLayout {
    val slot: Rect
    val elementBounds: List<Rect>
}

/** The picture behind the tab strip, laid out inside a slot the strip keeps clear of tabs and the toolbar. */
internal interface HolidayScenePainter {
    /** Lays the scene out inside [slot] (bar pixels); [vertical] is a side tab bar. */
    fun layout(slot: Rect, vertical: Boolean): HolidaySceneLayout

    /** Paints [layout] (its own coordinates, the bar's pixels). The bar colour itself is drawn by the framework. */
    fun draw(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean)
}

/** A backdrop art: the picture under the filter sections, drawn at time [t] seconds in its own [designWidthDp] x [designHeightDp] coordinates. */
internal interface HolidayPicturePainter {
    val designWidthDp: Float
    val designHeightDp: Float

    fun draw(scope: DrawScope, t: Float, dark: Boolean)
}

/**
 * The figure in the bottom-right corner of the side column that holds the Notes tile: a square [designSizeDp] picture. Like the filter
 * picture it is a backdrop art the framework places and fades behind covering cards (HolidayBackdropLayout.kt); the theme only draws it.
 */
internal interface HolidayCompanionPainter {
    /** Shown in the Layout menu and Settings ("Black cat in the Notes corner"). */
    val label: String
    val designSizeDp: Float

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

    /** Extra words that find the theme's Settings rows in the settings search. */
    val keywords: List<String> get() = emptyList()

    fun palette(dark: Boolean): ThemeColors

    fun chrome(dark: Boolean): HolidayChromeColors

    val corners: HolidayCorners get() = HolidayCorners.DEFAULT
    val tabOrnament: HolidayTabOrnament? get() = null
    val icons: HolidayIconSet? get() = null
    val tabScene: HolidayScenePainter? get() = null
    val picture: HolidayPicturePainter? get() = null
    val companion: HolidayCompanionPainter? get() = null
    val effects: List<HolidayEffectSpec> get() = emptyList()
}
