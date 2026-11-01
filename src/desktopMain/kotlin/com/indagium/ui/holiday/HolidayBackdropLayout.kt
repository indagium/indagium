package com.indagium.ui.holiday

import com.indagium.ui.TileRect
import kotlin.math.min

// Where the theme's backdrop arts go. An art is a picture the framework places in the bottom corners of the app window's CONTENT area (below the
// tab strip and the toolbar), on every screen and in every layout, whatever is shown over it: the theme only declares its design size and how it
// draws. All maths here is in dp of the content area, with the origin at its top-left.

/** The share of the content width and of its height an art may take (at most): the arts shrink in a small window, never grow past their design size. */
internal const val HOLIDAY_ART_WIDTH_SHARE = 0.24f
internal const val HOLIDAY_ART_HEIGHT_SHARE = 0.5f

/** A content area narrower or shorter than this gets no art at all. */
internal const val HOLIDAY_ART_MIN_CONTENT_WIDTH_DP = 480f
internal const val HOLIDAY_ART_MIN_CONTENT_HEIGHT_DP = 360f

/** Which bottom corner of the content area an art is anchored to. */
internal enum class HolidayArtAnchor {
    /** Bottom-left (the picture). */
    BOTTOM_START,

    /** Bottom-right (the companion). */
    BOTTOM_END,
}

/**
 * The rectangle of an art of [designWidthDp] x [designHeightDp] anchored to a bottom corner of a [contentWidthDp] x [contentHeightDp] content
 * area, or null when the area is under the minimum size. Its size is the design size scaled by
 * `min(1, contentWidth * 0.24 / designWidth, contentHeight * 0.5 / designHeight)`: about the design size in a 1400 x 860 window, smaller below.
 */
internal fun holidayArtRect(
    anchor: HolidayArtAnchor,
    contentWidthDp: Float,
    contentHeightDp: Float,
    designWidthDp: Float,
    designHeightDp: Float,
): TileRect? {
    if (contentWidthDp < HOLIDAY_ART_MIN_CONTENT_WIDTH_DP || contentHeightDp < HOLIDAY_ART_MIN_CONTENT_HEIGHT_DP) return null
    val scale = min(1f, min(contentWidthDp * HOLIDAY_ART_WIDTH_SHARE / designWidthDp, contentHeightDp * HOLIDAY_ART_HEIGHT_SHARE / designHeightDp))
    val w = designWidthDp * scale
    val h = designHeightDp * scale
    val x = if (anchor == HolidayArtAnchor.BOTTOM_START) 0f else contentWidthDp - w
    return TileRect(x, contentHeightDp - h, w, h)
}

/** True when the two rectangles share area (touching edges do not count). */
internal fun holidayRectsOverlap(a: TileRect, b: TileRect): Boolean = a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom

/** [placed] in order, minus any art whose rectangle overlaps one kept before it (the picture wins over the companion in a narrow window). */
internal fun <T> holidayDropOverlapping(placed: List<Pair<T, TileRect>>): List<Pair<T, TileRect>> {
    val kept = mutableListOf<Pair<T, TileRect>>()
    placed.forEach { p -> if (kept.none { holidayRectsOverlap(it.second, p.second) }) kept += p }
    return kept
}

/** The rectangles (content dp) of [arts] in a [contentWidthDp] x [contentHeightDp] area: each at its corner, the companion dropped if it would overlap. */
internal fun holidayArtPlacements(arts: List<HolidayBackdropArt>, contentWidthDp: Float, contentHeightDp: Float): List<Pair<HolidayBackdropArt, TileRect>> =
    holidayDropOverlapping(
        arts.mapNotNull { art ->
            holidayArtRect(art.anchor, contentWidthDp, contentHeightDp, art.designWidthDp, art.designHeightDp)?.let { art to it }
        },
    )
