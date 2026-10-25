package com.indagium.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// The one look of a toolbar that sits at the top of a tile's body: the log view's toolbar (Export / filter / Δt / search ...) and the Notes tile's
// button bar (Preview / Copy / Save / New / Open). Buttons are the same `AppButton` (outlined, rounded, transparent on the card, natural height) spaced
// by TILE_HEADER_GAP_DP; the bar has no band of its own, only a hairline divider under it, and the same height and side padding as a tile header.

/** Height (dp) of a tile toolbar with one row of buttons (it grows when the buttons wrap). */
internal const val TILE_TOOLBAR_HEIGHT_DP = 34f

/** Padding (dp) at the left and right of a tile toolbar's buttons, the same as a tile header's. */
internal const val TILE_TOOLBAR_PADDING_DP = 8f

/** The 1 dp divider along the bottom edge of a tile toolbar, drawn in [color] (the border colour). */
internal fun Modifier.tileToolbarDivider(color: Color): Modifier = drawBehind {
    drawRect(color, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
}
