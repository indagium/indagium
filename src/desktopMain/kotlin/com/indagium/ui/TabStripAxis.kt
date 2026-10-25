package com.indagium.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import com.indagium.model.TabStripPosition

// Small pieces the tab strip shares between its two orientations (ui/TabBar.kt's TabOverflowRow): a container that is a row or a column,
// and the popup placement that opens on the content side of whichever edge the strip sits on.

/** What [TabStripAxis] hands its content: the one modifier that differs between a row and a column. */
internal interface TabStripAxisScope {
    /** Fills the strip's main axis (the room left of the overflow button) and its cross axis. */
    fun Modifier.fillStrip(): Modifier
}

/** A row of [content] (horizontal) or a column of it ([vertical]); the content is emitted the same either way. */
@Composable
internal fun TabStripAxis(vertical: Boolean, modifier: Modifier, content: @Composable TabStripAxisScope.() -> Unit) {
    if (vertical) {
        Column(modifier) {
            content(ColumnAxisScope(this))
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            content(RowAxisScope(this))
        }
    }
}

private class RowAxisScope(private val row: RowScope) : TabStripAxisScope {
    override fun Modifier.fillStrip(): Modifier = with(row) { weight(1f).fillMaxHeight() }
}

private class ColumnAxisScope(private val column: ColumnScope) : TabStripAxisScope {
    override fun Modifier.fillStrip(): Modifier = with(column) { weight(1f).fillMaxWidth() }
}

/**
 * Places a popup on the content side of its anchor for a tab strip at [position] (see [chromePopupOrigin]); [topDropPx] is how far
 * below its anchor a TOP strip drops it.
 */
internal class ChromePopupPositionProvider(private val position: TabStripPosition, private val topDropPx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val (x, y) = chromePopupOrigin(
            position, anchorBounds.left, anchorBounds.top, anchorBounds.right,
            popupContentSize.width, popupContentSize.height, windowSize.width, windowSize.height, topDropPx,
        )
        return IntOffset(x, y)
    }
}
