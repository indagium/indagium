@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.indagium.model.HomeSectionId
import com.indagium.model.HomeSplitAxis

// The New tab's tiling host (design A2): sections as cards in a window-manager-style layout whose arrangement comes from
// AppSettings.homeLayout (pure model + geometry in HomeLayout.kt). The cards, grips, splitters and drop overlay are the generic
// TilingSurface (TilingHost.kt); this file owns the New tab's tile spec and callbacks. Moving a section is a manual
// pointer drag on its grip, NOT Compose/AWT drag-and-drop, so App.kt's file-drop target never sees it and a test can drive it.

/** Below this host width (dp) the sections stack in one vertical scroll in tree order, with no grips or splitters. */
internal const val HOME_STACK_BELOW_DP: Int = 720

private const val HOME_TILE_GAP_ROW_DP = LOG_VIEW_GAP_ROW_DP
private const val HOME_TILE_GAP_COLUMN_DP = LOG_VIEW_GAP_COLUMN_DP
private const val HOME_STACKED_HEIGHT_SHARE = 0.75f
private const val HOME_STACKED_MIN_HEIGHT_DP = 320f
private const val HOME_STACKED_MAX_HEIGHT_DP = 560f

/** The section cards' corner radius; splitter lines stop this far short of both ends of their gap so they end where the corners start. */
private const val HOME_CARD_CORNER_DP = 8

/**
 * One section's content for [HomeTilingHost]: the [icon] and [title] in its card header, an optional [action] composable
 * next to the hide button, and the [body] that fills the rest of the card (the body does its own scrolling).
 */
internal class HomeSectionSlot(
    val icon: ImageVector,
    val title: String,
    val action: (@Composable () -> Unit)? = null,
    val body: @Composable () -> Unit,
)

/** Arrow-key resize of a focused splitter: left/right on a ROW gap, up/down on a COLUMN gap; null for any other key. */
internal fun homeSplitterKeyDelta(axis: HomeSplitAxis, key: Key): Float? = tileSplitterKeyDelta(axis, key)

/** Card height (dp) in the stacked narrow layout: 75% of the viewport, kept between 320 and 560. */
internal fun homeStackedCardHeight(viewportHeightDp: Float): Float =
    (viewportHeightDp * HOME_STACKED_HEIGHT_SHARE).coerceIn(HOME_STACKED_MIN_HEIGHT_DP, HOME_STACKED_MAX_HEIGHT_DP)

/** The New tab's chrome: same section spacing as the log view, with no collapse or maximize. */
private val HOME_TILE_SPEC = TileChromeSpec<HomeSectionId>(
    headerDp = LOG_VIEW_TILE_HEADER_DP,
    titleSp = 12f,
    gapRowDp = HOME_TILE_GAP_ROW_DP,
    gapColumnDp = HOME_TILE_GAP_COLUMN_DP,
    cornerDp = HOME_CARD_CORNER_DP,
    minTileDp = HOME_MIN_SECTION_DP,
    minPairShare = HOME_MIN_PAIR_SHARE,
    tagPrefix = "home",
    keyOf = { it.key },
)

private fun homeTilingCallbacks(state: AppState, onReclaimFocus: () -> Unit) = TilingCallbacks<HomeSectionId>(
    onCommitLayout = { layout -> state.updateSettings { it.withHomeLayout(layout) } },
    onHide = { id -> state.updateSettings { it.withHomeSectionHidden(id) } },
    onToggleCollapse = {},
    onMaximize = {},
    onReclaimFocus = onReclaimFocus,
    dropLabel = { homeDropLabel(it.toHome()) },
)

// The slots as tiles; the last visible section's hide button is disabled (withHomeSectionHidden refuses it anyway).
private fun homeTileSlots(slots: Map<HomeSectionId, HomeSectionSlot>, canHide: Boolean): Map<HomeSectionId, TileSlot> =
    slots.mapValues { (_, slot) ->
        val headerAction: (@Composable RowScope.() -> Unit)? = slot.action?.let { action -> { action() } }
        TileSlot(icon = slot.icon, title = slot.title, headerAction = headerAction, canHide = canHide, body = { slot.body() })
    }

// Fixed composition order (enum order, not tree order): moving a section only changes its rect, never its place in the
// composition, so its remembered state and effects (the capture device poll, pairing dialogs, filters) survive drops.
@Composable
private fun TileScope<HomeSectionId>.HomeTiles(tiles: Map<HomeSectionId, TileSlot>) {
    HomeSectionId.entries.forEach { id -> tiles[id]?.let { Tile(id, it) } }
}

/**
 * The New tab's body. Layout controls live in the tab-strip Layout menu; [slots] must hold a slot for every [HomeSectionId].
 * [onReclaimFocus] gives root keyboard focus back after any click or drag here (see
 * CLAUDE.md's clickable-steals-focus gotcha).
 */
@Composable
internal fun HomeTilingHost(
    state: AppState,
    slots: Map<HomeSectionId, HomeSectionSlot>,
    onReclaimFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier.fillMaxSize().padding(
            start = LOG_VIEW_HOST_PAD_DP.dp,
            end = LOG_VIEW_HOST_PAD_DP.dp,
            top = LOG_VIEW_HOST_PAD_DP.dp,
            bottom = LOG_VIEW_HOST_PAD_DP.dp,
        ),
    ) {
        val layout = state.settings.homeLayout
        val tiles = homeTileSlots(slots, canHide = homeLeafSections(layout).size > 1)
        val callbacks = homeTilingCallbacks(state, onReclaimFocus)
        if (maxWidth.value < HOME_STACK_BELOW_DP) {
            TileStackedSurface(
                fullLayout = layout,
                isVisible = { true },
                parked = emptySet(),
                cardHeightDp = homeStackedCardHeight(maxHeight.value),
                spec = HOME_TILE_SPEC,
                callbacks = callbacks,
            ) { HomeTiles(tiles) }
        } else {
            TilingSurface(
                fullLayout = layout,
                isVisible = { true },
                collapsed = emptySet(),
                parked = emptySet(),
                maximized = null,
                locked = false,
                widthDp = maxWidth.value,
                heightDp = maxHeight.value,
                spec = HOME_TILE_SPEC,
                callbacks = callbacks,
            ) { HomeTiles(tiles) }
        }
    }
}
