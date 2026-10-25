package com.indagium.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// The saved-filter tree's share of its tile's default height (the old sidebar grew with the folders).
private const val SAVED_FILTER_TREE_CAP_DP = 240

/**
 * The saved filters' folder tree (Favorites, the folders, Ungrouped, or the search results). In a tile it is the section's flex region and the tree's
 * one scrollbar: the group lists inside it (via [NestedScrollableItems]) show all their rows instead of scrolling and drawing a bar of their own.
 * In the legacy single-column panel it is a plain column in the panel's scroll, and each group's list is capped by the rows setting and scrolls, as
 * it always did.
 */
@Composable
internal fun SavedFilterTree(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    if (LocalInTile.current) {
        FlexScrollBox(SAVED_FILTER_TREE_CAP_DP, modifier, content = content)
    } else {
        Column(modifier.fillMaxWidth(), content = content)
    }
}
