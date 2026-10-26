package com.indagium.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The scrollbar of a tiling surface's scrolling column. It is drawn in the gap beside the column, which is also where the column's width splitter
// is, so the bar must not take that splitter's pointer: only the thumb (plus a small margin above and below it) is a pointer target. The track
// above and below the thumb is drawing only (no hit area, no click-to-jump), so a press there falls through to the splitter underneath: resize
// cursor, drag to resize, double-click to reset. Over the thumb a drag scrolls the column and the wheel still does.

/** Least height (dp) of a thumb, like the app's other thin bars ([appScrollbarStyle]). */
internal const val TILE_SCROLLBAR_MIN_THUMB_DP = 24f

/** How far (dp) above and below the thumb the pointer still counts as being on it (hover and grab). */
internal const val TILE_SCROLLBAR_GRAB_MARGIN_DP = 2f

// The corner radius (dp) of the drawn thumb: CORNER_SM, like the app's other bars.
private const val THUMB_RADIUS_DP = 3f

/** A thumb inside its track: [top] and [height] in dp from the track's top. */
internal data class TileScrollbarThumb(val top: Float, val height: Float) {
    val bottom: Float get() = top + height
}

/** The thumb of [region] scrolled to [offset]: as tall as the viewport's share of the content (at least the minimum), placed by the offset. */
internal fun <K> tileScrollbarThumb(region: TileScrollRegion<K>, offset: Float): TileScrollbarThumb {
    val track = region.viewport.h
    val content = max(region.contentHeight, track)
    val height = if (content <= 0f) track else (track * track / content).coerceIn(min(TILE_SCROLLBAR_MIN_THUMB_DP, track), track)
    val range = region.maxOffset
    val top = if (range <= 0f) 0f else (offset / range).coerceIn(0f, 1f) * (track - height)
    return TileScrollbarThumb(top, height)
}

/** The part of the track that belongs to the bar: the [thumb] and the grab margin around it, kept inside the track of height [track]. */
internal fun tileScrollbarGrab(thumb: TileScrollbarThumb, track: Float): TileScrollbarThumb {
    val top = max(0f, thumb.top - TILE_SCROLLBAR_GRAB_MARGIN_DP)
    return TileScrollbarThumb(top, min(track, thumb.bottom + TILE_SCROLLBAR_GRAB_MARGIN_DP) - top)
}

/** How far the column's offset moves (dp) when the thumb is dragged [dragDp]: the content's range over the thumb's travel. */
internal fun <K> tileScrollbarOffsetDelta(region: TileScrollRegion<K>, thumb: TileScrollbarThumb, dragDp: Float): Float {
    val travel = region.viewport.h - thumb.height
    return if (travel <= 0f) 0f else dragDp * region.maxOffset / travel
}

/**
 * The thin scrollbar of a scrolling column that overflows; the surface places it ([tileScrollbarRect]) and tags it. Only the thumb takes the
 * pointer (see above); the rest of the strip draws nothing and hits nothing.
 */
@Composable
internal fun <K> TileRegionScrollbar(scroll: TileScrollState<K>, region: TileScrollRegion<K>, tag: String) {
    val latest = rememberUpdatedState(region)
    val tc = tc()
    val style = appScrollbarStyle(tc)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val color by animateColorAsState(
        if (hovered || dragging) style.hoverColor else style.unhoverColor,
        tween(style.hoverDurationMillis),
        label = "tile-scrollbar-thumb",
    )

    // The thumb and its grab area are worked out from the scroll offset while laying out and drawing, so scrolling does not recompose.
    fun thumb(): TileScrollbarThumb = tileScrollbarThumb(latest.value, scroll.offsetOf(latest.value))
    Box(Modifier.fillMaxSize().testTag(tag)) {
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val d = scroll.density
                    val grab = tileScrollbarGrab(thumb(), latest.value.viewport.h)
                    val placeable = measurable.measure(
                        Constraints.fixed(constraints.maxWidth, (grab.height * d).roundToInt().coerceAtLeast(0)),
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, (grab.top * d).roundToInt()) }
                }
                .hoverable(hover)
                .scrollable(scroll.scrollableOf(region.key), Orientation.Vertical, reverseDirection = true)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, drag ->
                        change.consume()
                        // Pixel deltas are divided by the density: the offsets are dp.
                        val r = latest.value
                        scroll.scrollBy(r.key, tileScrollbarOffsetDelta(r, thumb(), drag.y / density))
                    }
                }
                .drawBehind {
                    val t = thumb()
                    val g = tileScrollbarGrab(t, latest.value.viewport.h)
                    drawRoundRect(color, Offset(0f, (t.top - g.top) * density), Size(size.width, t.height * density), CornerRadius(THUMB_RADIUS_DP * density))
                },
        )
    }
}
