package com.indagium.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.indagium.model.ShapeStyle
import com.indagium.ui.holiday.HolidayLook
import com.indagium.ui.holiday.LocalHoliday

// The one place that answers "how round is this element": the tiles and the toolbar's button groups (and the tab and toolbar bars when
// they are drawn as cards, which follow the tiles). The Shape style setting (AppSettings.shapeStyle) and, under THEME, the active seasonal
// pack's own corners decide. Tabs are never reshaped here: they keep the user's Tab style or the pack's own tabs. Segmented controls,
// fields, chips, menus and dialogs keep their own shapes.

/** The regular themes' tile corner (what [LOG_VIEW_CHROME_SPEC] has always used). */
private const val REGULAR_TILE_DP = 8

/** The regular themes' toolbar button-group corner (what the toolbar has always used). */
private const val REGULAR_TOOLBAR_DP = 7

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
 * follows [tileDp] under every fixed style. [toolbarDp] is the toolbar's button-group corner and may be [UI_CORNER_CAPSULE].
 */
internal data class UiCorners(val tileDp: Int, val toolbarDp: Int, val workspaceTileDp: Int)

private val THEME_NO_PACK = UiCorners(REGULAR_TILE_DP, REGULAR_TOOLBAR_DP, REGULAR_TILE_DP)

/** The corners for [style] under [look] (the active seasonal pack's look, or null). [ShapeStyle.THEME] is exactly today's values. */
internal fun resolveUiCorners(style: ShapeStyle, look: HolidayLook?): UiCorners = when (style) {
    ShapeStyle.THEME -> look?.theme?.corners?.let { UiCorners(it.tileDp, it.toolbarDp, REGULAR_TILE_DP) } ?: THEME_NO_PACK
    ShapeStyle.SQUARE -> UiCorners(0, 0, 0)
    ShapeStyle.SOFT -> UiCorners(8, 7, 8)
    ShapeStyle.ROUNDED -> UiCorners(12, 10, 12)
    ShapeStyle.ROUND -> UiCorners(16, UI_CORNER_CAPSULE, 16)
}

/**
 * The corners App provides. Null means nobody provided any (a test host), in which case [currentUiCorners] resolves THEME against
 * whatever [LocalHoliday] says, so a host that only provides a pack keeps that pack's corners.
 */
internal val LocalUiCorners = compositionLocalOf<UiCorners?> { null }

/** The corners for the current look. */
@Composable
internal fun currentUiCorners(): UiCorners = LocalUiCorners.current ?: resolveUiCorners(ShapeStyle.THEME, LocalHoliday.current)

/**
 * [this] with the current look's workspace tile corner ([UiCorners.workspaceTileDp]): the spec itself under THEME, a copy under a fixed
 * shape style, one stable instance per radius while composed.
 */
@Composable
internal fun <K> TileChromeSpec<K>.withWorkspaceCorners(): TileChromeSpec<K> {
    val dp = currentUiCorners().workspaceTileDp
    return remember(this, dp) { if (cornerDp == dp) this else copy(cornerDp = dp) }
}
