package com.indagium.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarAdapter
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.v2.ScrollbarAdapter as ScrollbarAdapterV2

// The one scrollbar of the side sections: the thin bar (THIN_SCROLLBAR_DP wide, `appScrollbarStyle`) that the filter sections' lists, the Notes
// list, the AI conversation, the log view's row list and the tiling surface's scrolling columns all use. `appScrollbarStyle` alone is a 10 dp bar
// (the default thickness of its style); it is the explicit width that makes it thin, so every caller goes through here instead of repeating
// `.width(6.dp)` (or forgetting it).

/** Width (dp) of the app's thin side-section scrollbar. */
internal const val THIN_SCROLLBAR_DP = 6f

/** The thin scrollbar for a plain scrolling column; the caller aligns and sizes it in [modifier] (`align(CenterEnd).fillMaxHeight()`). */
@Composable
internal fun ThinVerticalScrollbar(scrollState: ScrollState, modifier: Modifier = Modifier) =
    ThinVerticalScrollbar(rememberScrollbarAdapter(scrollState), modifier)

/** The thin scrollbar for a lazy list. */
@Composable
internal fun ThinVerticalScrollbar(lazyState: LazyListState, modifier: Modifier = Modifier) =
    ThinVerticalScrollbar(rememberScrollbarAdapter(lazyState), modifier)

/** The thin scrollbar for an adapter that was built elsewhere. */
@Composable
internal fun ThinVerticalScrollbar(adapter: ScrollbarAdapter, modifier: Modifier = Modifier) {
    VerticalScrollbar(adapter = adapter, modifier = modifier.width(THIN_SCROLLBAR_DP.dp), style = appScrollbarStyle(tc()))
}

/** The thin horizontal bar (the log view's), [THIN_SCROLLBAR_DP] tall. */
@Composable
internal fun ThinHorizontalScrollbar(scrollState: ScrollState, modifier: Modifier = Modifier) {
    HorizontalScrollbar(
        adapter = rememberScrollbarAdapter(scrollState),
        modifier = modifier.height(THIN_SCROLLBAR_DP.dp),
        style = appScrollbarStyle(tc()),
    )
}

/** The thin scrollbar for a v2 adapter (the tiling surface's scrolling columns). */
@Composable
internal fun ThinVerticalScrollbar(adapter: ScrollbarAdapterV2, modifier: Modifier = Modifier) {
    VerticalScrollbar(adapter = adapter, modifier = modifier.width(THIN_SCROLLBAR_DP.dp), style = appScrollbarStyle(tc()))
}
