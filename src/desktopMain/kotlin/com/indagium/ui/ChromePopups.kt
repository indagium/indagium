package com.indagium.ui

import com.indagium.model.ChromeEdge
import com.indagium.model.TabStripPosition
import kotlin.math.max

// The popups the chrome's parts open (the tab strip's overflow list and menus, the Recent files menu): where they go, so that they open
// on the content side of whichever edge the part is docked to. Pure, like ChromeLayout.kt.

/**
 * The top-left of a popup of [popupW] x [popupH] shown from an anchor (its [anchorLeft], [anchorTop] and [anchorRight] edges) in a
 * [windowW] x [windowH] window, on the content side of a tab strip at [position]: below it (TOP, dropped [topDrop] below the anchor's
 * top, which is how the horizontal strip has always placed them), above it (BOTTOM), or beside it (LEFT / RIGHT). Everything but TOP
 * is kept inside the window.
 */
internal fun chromePopupOrigin(
    position: TabStripPosition,
    anchorLeft: Int,
    anchorTop: Int,
    anchorRight: Int,
    popupW: Int,
    popupH: Int,
    windowW: Int,
    windowH: Int,
    topDrop: Int,
): Pair<Int, Int> {
    if (position == TabStripPosition.TOP) return anchorLeft to anchorTop + topDrop
    val x = when (position) {
        TabStripPosition.LEFT -> anchorRight
        TabStripPosition.RIGHT -> anchorLeft - popupW
        else -> anchorLeft
    }
    val y = when (position) {
        TabStripPosition.BOTTOM -> anchorTop - popupH
        else -> anchorTop
    }
    return x.coerceIn(0, max(0, windowW - popupW)) to y.coerceIn(0, max(0, windowH - popupH))
}

/**
 * Where the Recent files menu goes: [alignEnd] / [alignBottom] say which window corner it hangs from and [dx] / [dy] (dp) how far in from it.
 * A toolbar on the top edge keeps the place the menu always had (8 dp from the right, 44 dp down); on another edge the menu opens on the
 * content side of the Recent button ([anchor], null if unknown).
 */
internal data class ChromeMenuPlacement(val alignEnd: Boolean, val alignBottom: Boolean, val dx: Float, val dy: Float)

internal fun recentMenuPlacement(toolbarEdge: ChromeEdge, anchor: TileRect?, windowW: Float, windowH: Float): ChromeMenuPlacement {
    val legacy = ChromeMenuPlacement(alignEnd = true, alignBottom = false, dx = 8f, dy = 44f)
    if (anchor == null) return legacy
    return when (toolbarEdge) {
        ChromeEdge.TOP -> legacy
        ChromeEdge.BOTTOM -> ChromeMenuPlacement(alignEnd = true, alignBottom = true, dx = 8f, dy = max(8f, windowH - anchor.y + 8f))
        ChromeEdge.LEFT -> ChromeMenuPlacement(alignEnd = false, alignBottom = false, dx = anchor.right + 8f, dy = max(8f, anchor.y))
        ChromeEdge.RIGHT -> ChromeMenuPlacement(alignEnd = true, alignBottom = false, dx = max(8f, windowW - anchor.x + 8f), dy = max(8f, anchor.y))
    }
}

/**
 * The edge whose Recent files menu placement fits a toolbar: that of its bar (or of the tab bar it shares), and for the toolbar tile of the
 * log view the side of the window its [anchor] is on, so the menu opens beside the button.
 */
internal fun recentMenuEdge(arrangement: ChromeArrangement, anchor: TileRect?, windowW: Float): ChromeEdge = when {
    !arrangement.toolbarInLayout -> arrangement.toolbarBarEdge ?: arrangement.tabEdge
    anchor != null && anchor.x + anchor.w / 2f > windowW / 2f -> ChromeEdge.RIGHT
    else -> ChromeEdge.LEFT
}
