package com.indagium.ui.holiday

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.indagium.ui.*

// The picture behind the tab strip (the native title bar can't be themed, so this stands in for it) is the theme's [HolidayScenePainter],
// laid out inside a "slot" of the bar that the tab strip keeps clear of tabs (reserved at the end of a FIXED strip's tab area, the free
// space after the last tab in FIT mode, the free space under the last tab of a vertical strip), so tabs never hide it and it never reaches
// under the toolbar. This file is the framework side: the slot maths, the strip-to-bar hand-over and the bar's background modifier.

/** Width (dp) of the slot a horizontal strip keeps free for the scene. */
internal const val HOLIDAY_SCENE_SLOT_DP = 160f

private const val SLOT_ROUNDING_PX = 0.5f

/**
 * The room a horizontal strip's tabs leave for the scene, in pixels, at the end of [tabAreaPx]: [slotPx] while the scene is [active] and
 * the visible tabs still get [minTabsPx] (their minimum widths added up) without it, else 0. Without a pack nothing changes.
 */
internal fun holidaySceneSlotPx(tabAreaPx: Float, minTabsPx: Float, slotPx: Float, active: Boolean): Float =
    if (active && tabAreaPx - slotPx >= minTabsPx) slotPx else 0f

/**
 * Where the scene may draw along the strip's main axis: from the end of the last tab ([tabsEndPx]) to the end of the tab area
 * ([tabAreaPx]), provided that is at least [minSlotPx]; null (no scene) otherwise.
 */
internal fun holidaySceneSlotRange(tabAreaPx: Float, tabsEndPx: Float, minSlotPx: Float): ClosedFloatingPointRange<Float>? =
    if (tabAreaPx - tabsEndPx >= minSlotPx - SLOT_ROUNDING_PX && tabAreaPx > tabsEndPx) tabsEndPx..tabAreaPx else null

/**
 * Where the tab strip says the scene may draw (window root pixels), shared with the bar behind it. The strip writes [rootRect]; the bar's
 * [holidayTabBarScene] reads it and its own [barOrigin]. Null [rootRect] means no slot, so nothing but the bar colour is drawn.
 */
internal class HolidaySceneSlot {
    var rootRect by mutableStateOf<Rect?>(null)
    var barOrigin by mutableStateOf(Offset.Zero)

    /** The slot in a bar of [barSize]'s own pixels, stretched over the bar's whole cross axis, or null when there is none. */
    fun localRect(barSize: Size, vertical: Boolean): Rect? {
        val r = rootRect ?: return null
        val x = r.left - barOrigin.x
        val y = r.top - barOrigin.y
        val stretched = if (vertical) Rect(0f, y, barSize.width, y + r.height) else Rect(x, 0f, x + r.width, barSize.height)
        return stretched.intersect(Rect(Offset.Zero, barSize)).takeIf { it.width > 0f && it.height > 0f }
    }
}

/** Provided by [ChromeScaffold] around its tab strip; null elsewhere (the strip then reports nothing). */
internal val LocalHolidaySceneSlot = staticCompositionLocalOf<HolidaySceneSlot?> { null }

/** Reports the strip's scene slot to the bar: [range] along the main axis of the tab area at [origin] (root px) whose cross size is [crossPx]. */
@Composable
internal fun ReportHolidaySceneSlot(origin: Offset, crossPx: Float, vertical: Boolean, range: ClosedFloatingPointRange<Float>?) {
    val holder = LocalHolidaySceneSlot.current ?: return
    SideEffect {
        holder.rootRect = range?.let {
            if (vertical) {
                Rect(origin.x, origin.y + it.start, origin.x + crossPx, origin.y + it.endInclusive)
            } else {
                Rect(origin.x + it.start, origin.y, origin.x + it.endInclusive, origin.y + crossPx)
            }
        }
    }
    DisposableEffect(holder) { onDispose { holder.rootRect = null } }
}

/**
 * The tab bar's background under a seasonal pack: the chrome's bar colour, cut to [shape] (a card bar's corners; the default joined bar
 * passes null and stays square), with the theme's [painter] picture only inside [slot]'s rectangle, the room the tab strip keeps free of
 * tabs and clear of the toolbar. Without a [slot], or while it has none, only the colour is drawn. [vertical] is a side tab bar.
 */
internal fun Modifier.holidayTabBarScene(
    look: HolidayLook,
    painter: HolidayScenePainter,
    vertical: Boolean,
    shape: Shape? = null,
    slot: HolidaySceneSlot? = null,
    clock: State<Float>? = null,
): Modifier {
    val outline = shape ?: RectangleShape
    return background(look.chrome.tabBar, outline)
        .onGloballyPositioned { slot?.barOrigin = it.positionInRoot() }
        .drawWithCache {
            val local = slot?.localRect(size, vertical)
            val layout = local?.let { painter.layout(it, vertical) }
            val clip = Path().apply { addOutline(outline.createOutline(size, layoutDirection, this@drawWithCache)) }
            onDrawBehind {
                if (layout != null) {
                    val r = layout.slot
                    val time = if (look.effects.animatePicture) (clock?.value ?: HOLIDAY_STILL_TIME) * look.effects.speedScale else HOLIDAY_STILL_TIME
                    clipPath(clip) { clipRect(r.left, r.top, r.right, r.bottom) { painter.drawFrame(this, layout, look.isDark, time) } }
                }
            }
        }
}
