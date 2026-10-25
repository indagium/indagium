@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.HomeSectionId
import com.indagium.model.HomeSplitAxis

// The New tab's tiling host (design A2): sections as cards in a window-manager-style layout whose arrangement comes from
// AppSettings.homeLayout (pure model + geometry in HomeLayout.kt). The cards, grips, splitters and drop overlay are the generic
// TilingSurface (TilingHost.kt); this file is the New tab's top bar plus its spec and callbacks. Moving a section is a manual
// pointer drag on its grip, NOT Compose/AWT drag-and-drop, so App.kt's file-drop target never sees it and a test can drive it.

/** Below this host width (dp) the sections stack in one vertical scroll in tree order, with no grips or splitters. */
internal const val HOME_STACK_BELOW_DP: Int = 720

/** The tip shown next to the title until dismissed (and again from the "?" button). */
internal const val HOME_LAYOUT_TIP: String =
    "Tip: drag ⠿ onto a section's edge to split it, or onto the page edge to span it. Drag a gap to resize."

private const val HOME_TILE_GAP_DP = 12f
private const val HOME_STACKED_HEIGHT_SHARE = 0.75f
private const val HOME_STACKED_MIN_HEIGHT_DP = 320f
private const val HOME_STACKED_MAX_HEIGHT_DP = 560f
private const val HOME_HEADER_DP = 36f
private const val HOME_TITLE_SP = 13f

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

/** The New tab's chrome: 36dp headers, 12dp gaps, no collapse or maximize. */
private val HOME_TILE_SPEC = TileChromeSpec<HomeSectionId>(
    headerDp = HOME_HEADER_DP,
    titleSp = HOME_TITLE_SP,
    gapRowDp = HOME_TILE_GAP_DP,
    gapColumnDp = HOME_TILE_GAP_DP,
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
 * The New tab's body: a one-row top bar (title, tip, hidden-section chips, layout presets) over the section cards. [slots] must hold
 * a slot for every [HomeSectionId]. [onReclaimFocus] gives root keyboard focus back after any click or drag here (see
 * CLAUDE.md's clickable-steals-focus gotcha).
 */
@Composable
internal fun HomeTilingHost(
    state: AppState,
    slots: Map<HomeSectionId, HomeSectionSlot>,
    onReclaimFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        HomeTopBar(state, slots, onReclaimFocus)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
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
}

// ---------------------------------------------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun HomeTopBar(state: AppState, slots: Map<HomeSectionId, HomeSectionSlot>, onReclaimFocus: () -> Unit) {
    val tc = tc()
    val settings = state.settings
    var tipShownAgain by remember { mutableStateOf(false) }
    val tipVisible = !settings.homeLayoutTipDismissed || tipShownAgain
    // One row only: the hidden-section chips sit between the tip and the presets, so hiding a section never adds a line (and never
    // moves the cards). When space runs out the tip, the only weighted child, ellipsizes first.
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppText("Start", fontSize = 20.sp, color = tc.tx)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (tipVisible) {
                AppText(
                    HOME_LAYOUT_TIP,
                    color = tc.td,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).testTag("home-tip"),
                )
                TileIconButton(Icons.Outlined.Close, "Dismiss tip", "Dismiss tip", "home-tip-dismiss") {
                    tipShownAgain = false
                    state.updateSettings { it.withHomeLayoutTipDismissed(true) }
                    onReclaimFocus()
                }
            } else {
                TileIconButton(Icons.AutoMirrored.Outlined.HelpOutline, "Show layout tip", HOME_LAYOUT_TIP, "home-tip-help") {
                    tipShownAgain = true
                    onReclaimFocus()
                }
            }
        }
        if (settings.homeHiddenSections.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppText("Hidden:", color = tc.td, fontSize = 11.sp, maxLines = 1)
                settings.homeHiddenSections.forEach { id ->
                    slots[id]?.let { slot ->
                        HomeHiddenChip(id, slot) {
                            state.updateSettings { it.withHomeSectionShown(id) }
                            onReclaimFocus()
                        }
                    }
                }
            }
        }
        AppText("Layout", color = tc.td, fontSize = 11.sp, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HomeLayoutPreset.entries.forEach { preset ->
                HomePresetButton(preset, active = preset.isActiveIn(settings)) {
                    state.updateSettings { it.withHomeLayoutPreset(preset) }
                    onReclaimFocus()
                }
            }
        }
    }
}

@Composable
private fun HomePresetButton(preset: HomeLayoutPreset, active: Boolean, onClick: () -> Unit) {
    val tc = tc()
    val fill = if (active) tc.ac else tc.td.copy(alpha = 0.55f)
    TooltipArea(tooltip = { ToolbarTooltip(preset.label) }) {
        Box(
            Modifier
                .size(width = 34.dp, height = 24.dp)
                .clip(CORNER_MD)
                .background(if (active) tc.ac.copy(alpha = 0.12f) else Color.Transparent)
                .border(1.dp, if (active) tc.ac else tc.br, CORNER_MD)
                .testTag("home-preset-${preset.name}")
                .semantics {
                    contentDescription = preset.label
                    selected = active
                }
                .clickable(role = Role.Button, onClick = onClick)
                .padding(3.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val geometry = homeGeometry(preset.layout, size.width, size.height, 2.dp.toPx())
                geometry.cards.forEach { card ->
                    drawRoundRect(
                        color = fill,
                        topLeft = Offset(card.rect.x, card.rect.y),
                        size = Size(card.rect.w, card.rect.h),
                        cornerRadius = CornerRadius(1.5.dp.toPx()),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeHiddenChip(id: HomeSectionId, slot: HomeSectionSlot, onClick: () -> Unit) {
    val tc = tc()
    Row(
        Modifier
            .clip(CORNER_MD)
            .border(1.dp, tc.br, CORNER_MD)
            .testTag("home-hidden-chip-${id.key}")
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(slot.icon, contentDescription = null, tint = tc.ts, modifier = Modifier.size(13.dp))
        AppText(slot.title, color = tc.ts, fontSize = 11.sp)
        Icon(Icons.Outlined.Add, contentDescription = null, tint = tc.ts, modifier = Modifier.size(13.dp))
    }
}
