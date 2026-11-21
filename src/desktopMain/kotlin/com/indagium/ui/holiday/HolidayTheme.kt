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

/**
 * An ornament drawn on the active tab (the pumpkin's stem), at the tab's outer edge [outerEdgePx] in from its cell's side away from the content.
 * One that moves ([animated], a twinkling star) is given the clock through [drawFrame]; the others only implement [draw].
 */
internal fun interface HolidayTabOrnament {
    fun draw(scope: DrawScope, position: TabStripPosition, outerEdgePx: Float, dark: Boolean)

    /** Whether [drawFrame] depends on its time (the tab then runs a frame clock while pictures run). */
    val animated: Boolean get() = false

    /** Draws the ornament at [time] seconds (the still pose at HOLIDAY_STILL_TIME); the default is the unmoving [draw]. */
    fun drawFrame(scope: DrawScope, position: TabStripPosition, outerEdgePx: Float, dark: Boolean, time: Float) = draw(scope, position, outerEdgePx, dark)
}

/** A dashed divider: [dash] runs of [dashDp] separated by [gap] runs of [gapDp], [heightDp] tall. */
internal data class HolidayDividerDashes(val dash: Color, val gap: Color, val dashDp: Float = 18f, val gapDp: Float = 2f, val heightDp: Float = 2f)

/**
 * A divider of repeating hard-edged blocks: each of [colors] in turn, [blockDp] wide, [heightDp] tall (the papel picado stripe). With [widthsDp]
 * block i is `widthsDp[i]` wide instead (an uneven ribbon: red 7, green 6, gold 1 ...) and the pattern repeats; [colors] and [widthsDp] are
 * walked independently, so they need not be the same length.
 */
internal data class HolidayDividerBlocks(val colors: List<Color>, val blockDp: Float, val heightDp: Float, val widthsDp: List<Float>? = null)

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

    /** Scene switches remain available even when intensity makes all motion still. */
    fun drawFrame(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean, time: Float, enabledEffects: Set<String>) =
        drawFrame(scope, layout, dark, time)
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

/** The group a theme's card is filed under on the Special themes tab; [label] is its filter button. Declaration order is the filter's order. */
internal enum class HolidayCategory(val label: String) {
    /** A dated celebration (Halloween, Thanksgiving). */
    HOLIDAY("Holidays"),

    /** A playful any-time look (beer, the retro prompt). */
    FUN("Playful"),

    /** Made from the app author's own paintings (tulips, the coffee bear). */
    PAINTING("Painted"),
}

/** The themes of [themes] in [category] (all of them for null), in registry order. */
internal fun holidayThemesIn(themes: Map<HolidayPack, HolidayTheme>, category: HolidayCategory?): List<HolidayTheme> =
    themes.values.filter { category == null || it.category == category }

/** One seasonal theme. See the file comment; the optional parts default to "not provided". */
internal interface HolidayTheme {
    /** The persisted id (the enum stays the stored value). */
    val pack: HolidayPack
    val label: String

    /** The filter group its card is shown under in Settings. Not persisted. */
    val category: HolidayCategory

    /** The holiday it celebrates, for its card ("Halloween"). */
    val occasion: String

    /** The days it applies in Automatic mode. */
    val dates: HolidayDates

    /** Whether Automatic mode applies at this local time; packs may use schedules other than yearly dates. */
    fun automaticAt(now: LocalDateTime): Boolean = dates.contains(now.toLocalDate())

    /** Human-readable description of the Automatic schedule. */
    val automaticLabel: String? get() = null

    /** False for themes that are deliberately selected only by hand. */
    val supportsAutomatic: Boolean get() = true

    /** Extra words that find the theme's Settings rows in the settings search. */
    val keywords: List<String> get() = emptyList()

    fun palette(dark: Boolean): ThemeColors

    fun chrome(dark: Boolean): HolidayChromeColors

    val corners: HolidayCorners get() = HolidayCorners.DEFAULT

    /** Optional label typeface. Log text and explicitly chosen monospace text keep their own fonts. */
    val labelFontFamily: FontFamily? get() = null
    val labelSizeSp: Float? get() = null
    val uppercaseLabels: Boolean get() = false
    val activeTabCursor: Boolean get() = false

    /** Text drawn before every tab's label in the theme's dim text colour (`"~/ "`); not part of the tab's name. */
    val tabLabelPrefix: String? get() = null

    /** Room kept before every tab's label, past the tab's own start padding, where the pack's [tabShape] needs it (Cozy's tag point and hole). */
    val tabLabelInsetDp: Float get() = 0f
    val borderWidthDp: Float get() = 1f
    val tabShape: HolidayTabShape? get() = null

    /** Optional symmetric inset on the cross-axis of a tab strip. */
    val tabCrossAxisInsetDp: Float? get() = null
    val tabTexture: HolidayTabTexture get() = HolidayTabTexture.RIBS
    val tintTabsByGroup: Boolean get() = true
    val inverseActiveTab: Boolean get() = false
    val tabOrnament: HolidayTabOrnament? get() = null

    /** Optional decoration on toolbar button groups. */
    val toolbarOrnament: HolidayTabOrnament? get() = null
    val newButtonStick: Boolean get() = false

    /** Optional three colors for the three segments of an expanded tile's divider. */
    fun tileDividerColors(dark: Boolean): List<Color>? = null

    /** Optional smooth gradient for an expanded tile's three-dp divider. */
    fun tileDividerGradient(dark: Boolean): List<Color>? = null

    /** Accent line on the content edge of the active tab. */
    val activeTabUnderlineDp: Float get() = 0f

    /**
     * Optional line along the outer edge of every tab, inside its outline and [tabEdgeLineDp] thick (Classic Christmas: pine green on an idle tab,
     * red on the active one). Null draws none.
     */
    fun tabEdgeLine(active: Boolean, dark: Boolean): Color? = null

    val tabEdgeLineDp: Float get() = 3f

    /** Optional dashed divider under an open tile's header (replaces the plain line; ignored when [tileDividerColors] is given). */
    fun tileDividerDashes(dark: Boolean): HolidayDividerDashes? = null

    /** Optional divider of repeating colour blocks under an open tile's header (ignored when colours, a gradient or dashes are given). */
    fun tileDividerBlocks(dark: Boolean): HolidayDividerBlocks? = null

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
