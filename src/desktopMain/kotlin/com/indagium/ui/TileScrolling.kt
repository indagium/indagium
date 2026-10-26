@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.math.min

// The scrolling columns of a TilingSurface (the geometry side is TileLayout.kt's TileScrollRegion): the offsets, the wheel/trackpad
// scrollables and the clip that cuts a card or splitter to its column's viewport (the scrollbar is TileScrollbar.kt).
//
// The tiles stay children of the surface's one flat Layout (that is what keeps their composition when they move), so a scrolling
// column cannot be a verticalScroll container. Instead the surface places each card/splitter of a region shifted by the column's
// offset (read in the placement phase, so scrolling re-places and re-draws but does not recompose), every one of them carries the
// same [ScrollableState] (a wheel over any of them scrolls the column, and a nested scrollable inside that cannot scroll any more
// hands the rest to it), and a graphicsLayer clip, which clips drawing AND hit-testing, cuts the part outside the viewport.

/** Width (dp) of a scrolling column's scrollbar: the app's thin side-section bar ([ThinVerticalScrollbar]). */
internal const val TILE_SCROLLBAR_DP = THIN_SCROLLBAR_DP

/** Least clearance (dp) between a column's scrollbar and the card (or the host's edge) beside it. */
internal const val TILE_SCROLLBAR_CLEARANCE_DP = 1f

/** Gap (dp) an inner list (a flex region, the AI conversation) keeps between its content and its own thin scrollbar. */
internal const val TILE_SCROLLBAR_GAP_DP = 2f

/**
 * The room (dp) a list inside a card leaves at its right edge for its own thin scrollbar and the gap to its content. This is no longer
 * reserved by the scrolling column itself: a column's own scrollbar is drawn in the gap beside the column ([tileScrollbarRect]).
 */
internal const val TILE_SCROLL_GUTTER_DP = TILE_SCROLLBAR_DP + TILE_SCROLLBAR_GAP_DP

/** The scroll offsets (dp) of one surface's scrolling columns, keyed by [TileScrollRegion.key]. */
internal class TileScrollState<K> {
    private val offsets = mutableStateMapOf<K, Float>()
    private val scrollables = HashMap<K, ScrollableState>()

    /** The regions of the latest layout; the scrollables read them when a wheel event arrives. */
    var regions: List<TileScrollRegion<K>> = emptyList()

    /** Pixels per dp, for the wheel deltas. */
    var density: Float = 1f

    /** The offset of [region], kept within its range (the content can shrink under a stored offset). */
    fun offsetOf(region: TileScrollRegion<K>): Float = (offsets[region.key] ?: 0f).coerceIn(0f, region.maxOffset)

    /** Moves the column [key] to [offsetDp]; returns how far it actually moved. */
    fun scrollTo(key: K, offsetDp: Float): Float {
        val region = regions.firstOrNull { it.key == key } ?: return 0f
        val before = offsetOf(region)
        val after = offsetDp.coerceIn(0f, region.maxOffset)
        if (after != before) offsets[key] = after
        return after - before
    }

    fun scrollBy(key: K, deltaDp: Float): Float {
        val region = regions.firstOrNull { it.key == key } ?: return 0f
        return scrollTo(key, offsetOf(region) + deltaDp)
    }

    /** The scrollable state of column [key]; one per column for the life of the surface. */
    fun scrollableOf(key: K): ScrollableState = scrollables.getOrPut(key) { ScrollableState { px -> scrollBy(key, px / density) * density } }
}

// What a graphicsLayer clips to: the band [top, bottom) of the layer (px), full width.
private data class TileViewportShape(val top: Float, val bottom: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, top, size.width, max(top, bottom)))
}

/**
 * Makes an element that lies in scrolling column [region] at [rect] (unscrolled content coordinates, dp) part of that column: cut to the
 * column's viewport, scrolled by a wheel, and, when something inside it asks to be brought into view (a focused field), scrolling the
 * column just far enough.
 */
internal fun <K> Modifier.tileScrollRegion(scroll: TileScrollState<K>, region: TileScrollRegion<K>, rect: TileRect): Modifier =
    this
        .graphicsLayer {
            clip = true
            val shift = scroll.offsetOf(region)
            val top = (region.viewport.y - (rect.y - shift)).coerceIn(0f, rect.h)
            val bottom = (region.viewport.bottom - (rect.y - shift)).coerceIn(top, rect.h)
            shape = TileViewportShape(top * density, bottom * density)
        }
        .scrollable(scroll.scrollableOf(region.key), Orientation.Vertical, enabled = region.scrolls, reverseDirection = true)
        .bringIntoViewResponder(TileRegionBringIntoView(scroll, region, rect))

// Scrolls the column so the requested rect (px, in the element's own coordinates) is inside the viewport.
private class TileRegionBringIntoView<K>(
    private val scroll: TileScrollState<K>,
    private val region: TileScrollRegion<K>,
    private val rect: TileRect,
) : BringIntoViewResponder {
    override fun calculateRectForParent(localRect: Rect): Rect = localRect

    override suspend fun bringChildIntoView(localRect: () -> Rect?) {
        val wanted = localRect() ?: return
        val d = scroll.density
        val top = rect.y + wanted.top / d - region.viewport.y
        val bottom = rect.y + wanted.bottom / d - region.viewport.y
        val offset = scroll.offsetOf(region)
        when {
            top < offset -> scroll.scrollTo(region.key, top)
            bottom > offset + region.viewport.h -> scroll.scrollTo(region.key, max(0f, bottom - region.viewport.h))
        }
    }
}

/**
 * Where the scrollbar of [region] is drawn: [TILE_SCROLLBAR_DP] wide and centred in the room beyond the column's right edge (the gap to its
 * neighbour, or the room the surface keeps at the host's edge), so it sits between two cards with [TILE_SCROLLBAR_CLEARANCE_DP] to spare on
 * each side and the cards themselves never narrow for it. A room too small for the full bar gets a narrower one; a surface that keeps no room
 * at all (the bar's strip would be under 3 dp) draws it over the column's own right edge instead.
 */
internal fun <K> tileScrollbarRect(region: TileScrollRegion<K>): TileRect {
    val room = region.roomRightDp
    val view = region.viewport
    val width = min(TILE_SCROLLBAR_DP, room - 2 * TILE_SCROLLBAR_CLEARANCE_DP)
    if (width < TILE_SCROLLBAR_MIN_DP) return TileRect(view.right - TILE_SCROLLBAR_DP, view.y, TILE_SCROLLBAR_DP, view.h)
    return TileRect(view.right + (room - width) / 2, view.y, width, view.h)
}

private const val TILE_SCROLLBAR_MIN_DP = 3f
