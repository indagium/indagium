package com.indagium.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.indagium.model.ShapeStyle
import com.indagium.ui.holiday.HolidayLook
import com.indagium.ui.holiday.LocalHoliday

// The one place that answers "how round is this element": the tiles (and the tab and toolbar bars when they are drawn as cards, which follow
// the tiles) by the Shape style setting (AppSettings.shapeStyle), and the toolbar's button groups by the Toolbar shape setting
// (AppSettings.toolbarShapeStyle). Under THEME the active seasonal pack's own corners decide. Tabs are never reshaped here: they keep
// the user's Tab style or the pack's own tabs. Segmented controls, fields, chips, menus and dialogs keep their own shapes.

/** The regular themes' tile corner (what [LOG_VIEW_CHROME_SPEC] has always used). */
private const val REGULAR_TILE_DP = 8

/** The regular themes' toolbar button-group corner (what the toolbar has always used). */
private const val REGULAR_TOOLBAR_DP = 7

private const val ROUNDED_TILE_DP = 12
private const val ROUND_TILE_DP = 16
private const val ROUNDED_TOOLBAR_DP = 10

/**
 * Marks a toolbar corner as a capsule: the radius is half the bar's thickness, whatever that is. A sentinel in the Int field keeps
 * [UiCorners] one flat value; use [isCapsuleCorner] to test for it and never `.dp` it.
 */
internal const val UI_CORNER_CAPSULE: Int = Int.MAX_VALUE

/** True when [toolbarDp] is [UI_CORNER_CAPSULE]. */
internal fun isCapsuleCorner(toolbarDp: Int): Boolean = toolbarDp == UI_CORNER_CAPSULE

/**
 * The corner radii for the current look, in dp.
 *
 * [tileDp] is the log view's tile and the window chrome's card-bar corner. [workspaceTileDp] is the corner of the New tab, diagram
 * workspace and Tests workspace tiles: packs never changed those, so it is 8 under [ShapeStyle.THEME] (with or without a pack) and
 * follows [tileDp] under every fixed style. [toolbarDp] is the toolbar's button-group corner (its own setting, not the tiles') and may be
 * [UI_CORNER_CAPSULE].
 */
internal data class UiCorners(val tileDp: Int, val toolbarDp: Int, val workspaceTileDp: Int)

/**
 * The corners for the tiles' [style] and the toolbar's [toolbarStyle] under [look] (the active seasonal pack's look, or null). THEME is
 * exactly today's values for each: the regular 8 / 7 dp, or the pack's own `corners`. The two styles are independent: [style] only
 * drives [UiCorners.tileDp] and [UiCorners.workspaceTileDp], [toolbarStyle] only [UiCorners.toolbarDp].
 */
internal fun resolveUiCorners(style: ShapeStyle, toolbarStyle: ShapeStyle, look: HolidayLook?): UiCorners {
    val own = look?.theme?.corners
    val tile = when (style) {
        ShapeStyle.THEME -> own?.tileDp ?: REGULAR_TILE_DP
        ShapeStyle.SQUARE -> 0
        ShapeStyle.SOFT -> REGULAR_TILE_DP
        ShapeStyle.ROUNDED -> ROUNDED_TILE_DP
        ShapeStyle.ROUND -> ROUND_TILE_DP
    }
    val workspace = if (style == ShapeStyle.THEME) REGULAR_TILE_DP else tile
    val toolbar = when (toolbarStyle) {
        ShapeStyle.THEME -> own?.toolbarDp ?: REGULAR_TOOLBAR_DP
        ShapeStyle.SQUARE -> 0
        ShapeStyle.SOFT -> REGULAR_TOOLBAR_DP
        ShapeStyle.ROUNDED -> ROUNDED_TOOLBAR_DP
        ShapeStyle.ROUND -> UI_CORNER_CAPSULE
    }
    return UiCorners(tile, toolbar, workspace)
}

/**
 * The corners App provides. Null means nobody provided any (a test host), in which case [currentUiCorners] resolves THEME against
 * whatever [LocalHoliday] says, so a host that only provides a pack keeps that pack's corners.
 */
internal val LocalUiCorners = compositionLocalOf<UiCorners?> { null }

/** The corners for the current look. */
@Composable
internal fun currentUiCorners(): UiCorners = LocalUiCorners.current ?: resolveUiCorners(ShapeStyle.THEME, ShapeStyle.THEME, LocalHoliday.current)

/**
 * [this] with the current look's workspace tile corner ([UiCorners.workspaceTileDp]): the spec itself under THEME, a copy under a fixed
 * shape style, one stable instance per radius while composed.
 */
@Composable
internal fun <K> TileChromeSpec<K>.withWorkspaceCorners(): TileChromeSpec<K> {
    val dp = currentUiCorners().workspaceTileDp
    return remember(this, dp) { if (cornerDp == dp) this else copy(cornerDp = dp) }
}
