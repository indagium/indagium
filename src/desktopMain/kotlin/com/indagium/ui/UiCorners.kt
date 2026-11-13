package com.indagium.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.indagium.model.ShapeStyle
import com.indagium.ui.holiday.HolidayLook
import com.indagium.ui.holiday.LocalHoliday

// The one place that answers "how round is this element": the tiles, the toolbar's button groups and the tabs. The Shape style setting
// (AppSettings.shapeStyle) and, under THEME, the active seasonal pack's own corners decide. Segmented controls, fields, chips, menus and
// dialogs keep their own shapes.

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
 * follows [tileDp] under every fixed style. [toolbarDp] is the toolbar's button-group corner and may be [UI_CORNER_CAPSULE]. [tabDp] is
 * the tab corner of a seasonal pack's generic tab, or null when the tab style draws its own corners. [ownTabDp] is the corner the user's own
 * tab style (a regular theme's tabs, or a pack's "My tab style") takes: null under [ShapeStyle.THEME] (every style keeps its own radius),
 * the fixed style's tab corner otherwise.
 */
internal data class UiCorners(val tileDp: Int, val toolbarDp: Int, val tabDp: Float?, val workspaceTileDp: Int, val ownTabDp: Float? = null)

private val THEME_NO_PACK = UiCorners(REGULAR_TILE_DP, REGULAR_TOOLBAR_DP, null, REGULAR_TILE_DP)

/** The corners for [style] under [look] (the active seasonal pack's look, or null). [ShapeStyle.THEME] is exactly today's values. */
internal fun resolveUiCorners(style: ShapeStyle, look: HolidayLook?): UiCorners = when (style) {
    ShapeStyle.THEME -> look?.theme?.corners?.let { UiCorners(it.tileDp, it.toolbarDp, it.tabDp, REGULAR_TILE_DP) } ?: THEME_NO_PACK
    ShapeStyle.SQUARE -> UiCorners(0, 0, 0f, 0, 0f)
    ShapeStyle.SOFT -> UiCorners(8, 7, 6f, 8, 6f)
    ShapeStyle.ROUNDED -> UiCorners(12, 10, 10f, 12, 10f)
    ShapeStyle.ROUND -> UiCorners(16, UI_CORNER_CAPSULE, 14f, 16, 14f)
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
