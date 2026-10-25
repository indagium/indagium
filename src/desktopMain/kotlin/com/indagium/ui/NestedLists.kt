package com.indagium.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

// One scrollbar per list. A tile's flex region (TileFlexRegion.kt) is the one scrollable part of its body, so a list that sits inside it must not
// scroll (and draw a scrollbar) of its own: two bars at the same right edge overlay each other, and a wheel turn would be caught by the inner list
// first. Lists that can be nested like that ask [NestedScrollableItems] instead of ScrollableItems, which is the bounded scrolling list
// everywhere else (a dropdown, the legacy single-column panel, a dialog).

/** True while composing inside a tiling surface's card (set by the card), false elsewhere. */
internal val LocalInTile = compositionLocalOf { false }

/** True inside a tile's flex region: the region scrolls, so a list in it shows all of its rows. Set by [FlexRegion]. */
internal val LocalFlatLists = compositionLocalOf { false }

/**
 * [ScrollableItems] outside a tile's flex region; inside one, a plain column of all the rows (the region scrolls them). A list with no rows
 * draws nothing in either case.
 */
@Composable
internal fun NestedScrollableItems(
    itemCount: Int,
    rowDp: Int = 28,
    maxDp: Int = 150,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (itemCount == 0) return
    if (LocalFlatLists.current) {
        Column(modifier.fillMaxWidth(), content = content)
    } else {
        ScrollableItems(itemCount, rowDp = rowDp, maxDp = maxDp, modifier = modifier, content = content)
    }
}
