package com.indagium.ui

import com.indagium.model.AppSettings
import com.indagium.model.ChromeEdge
import com.indagium.model.TabStripPosition
import com.indagium.model.ToolbarPlacement
import kotlin.math.max
import kotlin.math.min

// The pure side of the window chrome (ui/ChromeScaffold.kt draws it): where the tab strip and the main toolbar sit, the dock targets
// a dragged tab strip / toolbar may be dropped on, and what a drop does to the settings. No Compose here, so all of it is unit-tested
// directly (ChromeLayoutTest). Lengths are plain floats in one unit (dp for hit-testing, px inside the scaffold's Layout).
//
// The model: the tab strip sits on one window edge (AppSettings.tabStripPosition). The toolbar either shares the tab strip's bar, at its
// start or end (WITH_TABS_START / WITH_TABS_END), or has a bar of its own: BESIDE_TABS (next to a vertical tab strip, on its content side)
// or OWN_EDGE (docked to toolbarEdge). Top and bottom bars span the window; left and right bars fill what is between them. Two bars on
// one edge stack with the tab strip outermost.

/** The orientation of a bar or a part of the chrome: a horizontal one is a row (top / bottom edge), a vertical one a column (left / right). */
internal enum class ChromeOrientation { HORIZONTAL, VERTICAL }

internal val ChromeEdge.isVertical: Boolean get() = this == ChromeEdge.LEFT || this == ChromeEdge.RIGHT

internal val ChromeEdge.orientation: ChromeOrientation get() = if (isVertical) ChromeOrientation.VERTICAL else ChromeOrientation.HORIZONTAL

internal fun TabStripPosition.toEdge(): ChromeEdge = when (this) {
    TabStripPosition.TOP -> ChromeEdge.TOP
    TabStripPosition.LEFT -> ChromeEdge.LEFT
    TabStripPosition.RIGHT -> ChromeEdge.RIGHT
    TabStripPosition.BOTTOM -> ChromeEdge.BOTTOM
}

internal fun ChromeEdge.toTabStripPosition(): TabStripPosition = when (this) {
    ChromeEdge.TOP -> TabStripPosition.TOP
    ChromeEdge.LEFT -> TabStripPosition.LEFT
    ChromeEdge.RIGHT -> TabStripPosition.RIGHT
    ChromeEdge.BOTTOM -> TabStripPosition.BOTTOM
}

/** The two parts of the chrome a user can drag to a new place. */
internal enum class ChromeSlot { TABS, TOOLBAR }

// ---------------------------------------------------------------------------------------------------------------
// Arrangement
// ---------------------------------------------------------------------------------------------------------------

/**
 * Where the chrome sits: the tab strip's [tabPosition], the toolbar's [placement] and, for OWN_EDGE, the [toolbarEdge] it is docked to.
 * [toolbarEdge] is kept even while another placement is in force so that switching back to OWN_EDGE returns to the edge last used.
 */
internal data class ChromeArrangement(
    val tabPosition: TabStripPosition = TabStripPosition.TOP,
    val placement: ToolbarPlacement = ToolbarPlacement.WITH_TABS_END,
    val toolbarEdge: ChromeEdge = ChromeEdge.TOP,
) {
    val tabEdge: ChromeEdge get() = tabPosition.toEdge()

    val tabsOrientation: ChromeOrientation get() = tabEdge.orientation

    /** True when the toolbar shares the tab strip's bar (WITH_TABS_START / WITH_TABS_END). */
    val toolbarInTabBar: Boolean get() = placement == ToolbarPlacement.WITH_TABS_START || placement == ToolbarPlacement.WITH_TABS_END

    /** The edge of the toolbar's own bar, or null when it shares the tab strip's bar. */
    val toolbarBarEdge: ChromeEdge?
        get() = when (placement) {
            ToolbarPlacement.WITH_TABS_START, ToolbarPlacement.WITH_TABS_END -> null
            ToolbarPlacement.BESIDE_TABS -> tabEdge
            ToolbarPlacement.OWN_EDGE -> toolbarEdge
        }

    /** The orientation the toolbar lays its buttons out in: that of the bar it is in. */
    val toolbarOrientation: ChromeOrientation get() = (toolbarBarEdge ?: tabEdge).orientation

    /** This arrangement with an invalid combination turned into a valid one: BESIDE_TABS needs a vertical tab strip (else WITH_TABS_END). */
    fun sanitized(): ChromeArrangement =
        if (placement == ToolbarPlacement.BESIDE_TABS && !tabEdge.isVertical) copy(placement = ToolbarPlacement.WITH_TABS_END) else this
}

/** The arrangement the settings ask for, sanitized (the stored values are not rewritten, so a later valid tab position restores BESIDE_TABS). */
internal fun chromeArrangement(settings: AppSettings): ChromeArrangement =
    ChromeArrangement(settings.tabStripPosition, settings.toolbarPlacement, settings.toolbarEdge).sanitized()

/** [this] with the arrangement's three fields written (sanitized first). */
internal fun AppSettings.withChromeArrangement(arrangement: ChromeArrangement): AppSettings {
    val a = arrangement.sanitized()
    return if (tabStripPosition == a.tabPosition && toolbarPlacement == a.placement && toolbarEdge == a.toolbarEdge) {
        this
    } else {
        copy(tabStripPosition = a.tabPosition, toolbarPlacement = a.placement, toolbarEdge = a.toolbarEdge)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Metrics and frames
// ---------------------------------------------------------------------------------------------------------------

/** Width (dp) of a vertical tab strip's bar. */
internal const val CHROME_VERTICAL_TAB_BAR_DP: Float = 200f

/** Height (dp) of a horizontal bar that holds only the toolbar. */
internal const val CHROME_TOOLBAR_BAR_HORIZONTAL_DP: Float = 36f

/** Width (dp) of a vertical bar that holds only the toolbar (icon-only buttons stacked). */
internal const val CHROME_TOOLBAR_BAR_VERTICAL_DP: Float = 40f

/** The padding (dp) a bar keeps on its cross axis, inside its border: 1 dp, as the tab strip always had. */
internal const val CHROME_BAR_PADDING_DP: Float = 1f

/** Length (dp) of the grip header a vertical bar reserves for each of its parts; a horizontal part's grip is an overlay and takes no room. */
internal const val CHROME_GRIP_RESERVE_DP: Float = 14f

/** Length (dp) along a horizontal part that its overlay grip covers. */
internal const val CHROME_GRIP_OVERLAY_DP: Float = 12f

/** The room (dp) a vertical tab bar always keeps for its tabs: a toolbar sharing the bar scrolls rather than push the tabs out. */
internal const val CHROME_VERTICAL_MIN_TABS_DP: Float = 150f

/** Thickness (dp) of the window-edge drop strips. */
internal const val CHROME_DOCK_BAND_DP: Float = 32f

/** The sizes the frames are computed from, in one unit. [tabBarHeight] is the horizontal tab bar's height (it follows the tab style). */
internal data class ChromeMetrics(
    val tabBarHeight: Float,
    val verticalTabBar: Float = CHROME_VERTICAL_TAB_BAR_DP,
    val toolbarBarHorizontal: Float = CHROME_TOOLBAR_BAR_HORIZONTAL_DP,
    val toolbarBarVertical: Float = CHROME_TOOLBAR_BAR_VERTICAL_DP,
    val barPadding: Float = CHROME_BAR_PADDING_DP,
    val gripReserve: Float = CHROME_GRIP_RESERVE_DP,
    val gripOverlay: Float = CHROME_GRIP_OVERLAY_DP,
    val dockBand: Float = CHROME_DOCK_BAND_DP,
    val verticalMinTabs: Float = CHROME_VERTICAL_MIN_TABS_DP,
) {
    /** Every length multiplied by [factor] (dp to px). */
    fun scaled(factor: Float) = ChromeMetrics(
        tabBarHeight * factor, verticalTabBar * factor, toolbarBarHorizontal * factor, toolbarBarVertical * factor,
        barPadding * factor, gripReserve * factor, gripOverlay * factor, dockBand * factor, verticalMinTabs * factor,
    )

    /** The thickness of the tab strip's bar for [arrangement]. */
    fun tabBarThickness(arrangement: ChromeArrangement): Float = if (arrangement.tabEdge.isVertical) verticalTabBar else tabBarHeight

    /** The thickness of the toolbar's own bar (only meaningful when the toolbar has one). */
    fun toolbarBarThickness(edge: ChromeEdge): Float = if (edge.isVertical) toolbarBarVertical else toolbarBarHorizontal

    /**
     * The longest the toolbar may be along its main axis in a window [windowLength] long on that axis. A toolbar in a vertical tab bar
     * leaves the tabs [verticalMinTabs] (at least half the bar); every other toolbar may use the whole window.
     */
    fun toolbarMaxLength(arrangement: ChromeArrangement, windowLength: Float): Float =
        if (arrangement.toolbarInTabBar && arrangement.tabEdge.isVertical) max(windowLength / 2f, windowLength - verticalMinTabs) else windowLength

    /** The cross-axis size the toolbar is given: its bar's thickness less the padding both sides. */
    fun toolbarCross(arrangement: ChromeArrangement): Float {
        val bar = arrangement.toolbarBarEdge?.let(::toolbarBarThickness) ?: tabBarThickness(arrangement)
        return max(0f, bar - 2 * barPadding)
    }
}

/**
 * Where every part of the chrome goes in a window. [tabBar] and [toolbarBar] (null while the toolbar shares the tab bar) are the bars
 * with their background and border; [tabs] and [toolbar] are the parts themselves, inside the bars; [content] is what is left for the
 * app. [tabsGrip] and [toolbarGrip] are null while the chrome is locked.
 */
internal data class ChromeFrames(
    val tabBar: TileRect,
    val tabs: TileRect,
    val toolbarBar: TileRect?,
    val toolbar: TileRect,
    val content: TileRect,
    val tabsGrip: TileRect?,
    val toolbarGrip: TileRect?,
)

private fun TileRect.inset(cross: Float, vertical: Boolean): TileRect =
    if (vertical) TileRect(x + cross, y, max(0f, w - 2 * cross), h) else TileRect(x, y + cross, w, max(0f, h - 2 * cross))

private fun TileRect.mainLength(vertical: Boolean): Float = if (vertical) h else w

// The first [length] of the main axis.
private fun TileRect.head(length: Float, vertical: Boolean): TileRect =
    if (vertical) TileRect(x, y, w, min(length, h)) else TileRect(x, y, min(length, w), h)

// What is left after the first [length] of the main axis.
private fun TileRect.afterHead(length: Float, vertical: Boolean): TileRect =
    if (vertical) TileRect(x, y + min(length, h), w, max(0f, h - length)) else TileRect(x + min(length, w), y, max(0f, w - length), h)

// The last [length] of the main axis.
private fun TileRect.tail(length: Float, vertical: Boolean): TileRect =
    if (vertical) TileRect(x, bottom - min(length, h), w, min(length, h)) else TileRect(right - min(length, w), y, min(length, w), h)

// What is left before the last [length] of the main axis.
private fun TileRect.beforeTail(length: Float, vertical: Boolean): TileRect =
    if (vertical) TileRect(x, y, w, max(0f, h - length)) else TileRect(x, y, max(0f, w - length), h)

/** A part's region split into its grip and its body: a horizontal part's grip overlays the start of the body, a vertical part's is a header above it. */
private fun splitGrip(region: TileRect, vertical: Boolean, grips: Boolean, m: ChromeMetrics): Pair<TileRect?, TileRect> = when {
    !grips -> null to region
    vertical -> region.head(m.gripReserve, true) to region.afterHead(m.gripReserve, true)
    else -> region.head(m.gripOverlay, false) to region
}

private data class ChromeBarSpec(val slot: ChromeSlot, val edge: ChromeEdge, val thickness: Float)

/**
 * The frames for [arrangement] in a [width] x [height] window. [toolbarLength] is the toolbar's natural length along its main axis (its
 * width in a horizontal bar, its height in a vertical one), measured by the caller; [grips] is false while the chrome is locked.
 */
internal fun chromeFrames(
    arrangement: ChromeArrangement,
    width: Float,
    height: Float,
    metrics: ChromeMetrics,
    toolbarLength: Float,
    grips: Boolean,
): ChromeFrames {
    val a = arrangement.sanitized()
    val bars = buildList {
        add(ChromeBarSpec(ChromeSlot.TABS, a.tabEdge, metrics.tabBarThickness(a)))
        a.toolbarBarEdge?.let { add(ChromeBarSpec(ChromeSlot.TOOLBAR, it, metrics.toolbarBarThickness(it))) }
    }
    val rects = HashMap<ChromeSlot, TileRect>()
    var top = 0f
    var bottom = height
    // Top and bottom bars span the window; the tab strip is added first, so it is outermost on its edge.
    bars.filter { !it.edge.isVertical }.forEach { bar ->
        val t = min(bar.thickness, max(0f, bottom - top))
        if (bar.edge == ChromeEdge.TOP) {
            rects[bar.slot] = TileRect(0f, top, width, t)
            top += t
        } else {
            bottom -= t
            rects[bar.slot] = TileRect(0f, bottom, width, t)
        }
    }
    var left = 0f
    var right = width
    bars.filter { it.edge.isVertical }.forEach { bar ->
        val t = min(bar.thickness, max(0f, right - left))
        if (bar.edge == ChromeEdge.LEFT) {
            rects[bar.slot] = TileRect(left, top, t, max(0f, bottom - top))
            left += t
        } else {
            right -= t
            rects[bar.slot] = TileRect(right, top, t, max(0f, bottom - top))
        }
    }
    val content = TileRect(left, top, max(0f, right - left), max(0f, bottom - top))
    val tabBar = rects.getValue(ChromeSlot.TABS)
    val toolbarBar = rects[ChromeSlot.TOOLBAR]
    val tabsVertical = a.tabEdge.isVertical
    val toolbarVertical = a.toolbarOrientation == ChromeOrientation.VERTICAL
    val toolbarRegionLength = toolbarLength + if (toolbarVertical && grips) metrics.gripReserve else 0f
    val tabsRegion: TileRect
    val toolbarRegion: TileRect
    if (toolbarBar == null) {
        val inner = tabBar.inset(metrics.barPadding, tabsVertical)
        val length = min(toolbarRegionLength, inner.mainLength(tabsVertical))
        if (a.placement == ToolbarPlacement.WITH_TABS_START) {
            toolbarRegion = inner.head(length, tabsVertical)
            tabsRegion = inner.afterHead(length, tabsVertical)
        } else {
            toolbarRegion = inner.tail(length, tabsVertical)
            tabsRegion = inner.beforeTail(length, tabsVertical)
        }
    } else {
        tabsRegion = tabBar.inset(metrics.barPadding, tabsVertical)
        toolbarRegion = toolbarBar.inset(metrics.barPadding, toolbarVertical).head(toolbarRegionLength, toolbarVertical)
    }
    val (tabsGrip, tabs) = splitGrip(tabsRegion, tabsVertical, grips, metrics)
    val (toolbarGrip, toolbar) = splitGrip(toolbarRegion, toolbarVertical, grips, metrics)
    return ChromeFrames(tabBar, tabs, toolbarBar, toolbar, content, tabsGrip, toolbarGrip)
}

// ---------------------------------------------------------------------------------------------------------------
// Dock targets
// ---------------------------------------------------------------------------------------------------------------

/** Where a dragged tab strip or toolbar can be dropped. */
internal sealed interface ChromeDockTarget {
    /** A window edge: for the tab strip its new position, for the toolbar a bar of its own on that edge. */
    data class Edge(val edge: ChromeEdge) : ChromeDockTarget

    /** The start (left or top) of the tab strip's bar: the toolbar joins the tab strip there. */
    data object TabsStart : ChromeDockTarget

    /** The end (right or bottom) of the tab strip's bar. */
    data object TabsEnd : ChromeDockTarget

    /** A bar of its own next to a vertical tab strip, on its content side. */
    data object BesideTabs : ChromeDockTarget
}

/** The stable name a dock zone's test tag uses (`chrome-dock-<key>`). */
internal fun ChromeDockTarget.key(): String = when (this) {
    is ChromeDockTarget.Edge -> edge.name.lowercase()
    ChromeDockTarget.TabsStart -> "tabs-start"
    ChromeDockTarget.TabsEnd -> "tabs-end"
    ChromeDockTarget.BesideTabs -> "beside-tabs"
}

/** The caption of a dock zone while dragging [slot]. */
internal fun chromeDockLabel(slot: ChromeSlot, target: ChromeDockTarget): String = when (target) {
    is ChromeDockTarget.Edge -> (if (slot == ChromeSlot.TABS) "Tabs" else "Toolbar") + " · " + target.edge.name.lowercase().replaceFirstChar { it.uppercase() }
    ChromeDockTarget.TabsStart -> "With tabs · start"
    ChromeDockTarget.TabsEnd -> "With tabs · end"
    ChromeDockTarget.BesideTabs -> "Beside tabs"
}

internal data class ChromeDockZone(val target: ChromeDockTarget, val rect: TileRect)

/** The window-edge strips along the inside of [content], top and bottom full width, left and right between them (so none overlap). */
private fun edgeZones(content: TileRect, band: Float): List<ChromeDockZone> {
    val horizontalBand = min(band, content.h / 3f)
    val verticalBand = min(band, content.w / 3f)
    val middleY = content.y + horizontalBand
    val middleH = max(0f, content.h - 2 * horizontalBand)
    return listOf(
        ChromeDockZone(ChromeDockTarget.Edge(ChromeEdge.TOP), TileRect(content.x, content.y, content.w, horizontalBand)),
        ChromeDockZone(ChromeDockTarget.Edge(ChromeEdge.BOTTOM), TileRect(content.x, content.bottom - horizontalBand, content.w, horizontalBand)),
        ChromeDockZone(ChromeDockTarget.Edge(ChromeEdge.LEFT), TileRect(content.x, middleY, verticalBand, middleH)),
        ChromeDockZone(ChromeDockTarget.Edge(ChromeEdge.RIGHT), TileRect(content.right - verticalBand, middleY, verticalBand, middleH)),
    )
}

/**
 * The zones a dragged [slot] can be dropped on, for the window laid out as [frames]. Dragging the tab strip offers the four edges.
 * Dragging the toolbar offers the four edges too, plus the two halves of the tab strip's bar (with tabs, start / end); next to a
 * vertical tab strip "beside tabs" takes the place of that strip's own edge (they are the same spot).
 */
internal fun chromeDockZones(slot: ChromeSlot, arrangement: ChromeArrangement, frames: ChromeFrames, metrics: ChromeMetrics): List<ChromeDockZone> {
    val a = arrangement.sanitized()
    val edges = edgeZones(frames.content, metrics.dockBand)
    if (slot == ChromeSlot.TABS) return edges
    val tabsVertical = a.tabEdge.isVertical
    val besideEdge = if (tabsVertical) a.tabEdge else null
    val kept = edges.map { zone ->
        if (besideEdge != null && (zone.target as? ChromeDockTarget.Edge)?.edge == besideEdge) ChromeDockZone(ChromeDockTarget.BesideTabs, zone.rect) else zone
    }
    val bar = frames.tabBar
    val halfLength = bar.mainLength(tabsVertical) / 2f
    val tabZones = listOf(
        ChromeDockZone(ChromeDockTarget.TabsStart, bar.head(halfLength, tabsVertical)),
        ChromeDockZone(ChromeDockTarget.TabsEnd, bar.tail(halfLength, tabsVertical)),
    )
    return tabZones + kept
}

/** The target under the point ([x], [y]), or null. Zones never overlap, so the order does not matter. */
internal fun chromeDockTargetAt(zones: List<ChromeDockZone>, x: Float, y: Float): ChromeDockTarget? =
    zones.firstOrNull { it.rect.contains(x, y) }?.target

/**
 * What dropping [slot] on [target] does to [arrangement]. The tab strip dropped on an edge moves there (the toolbar travels with it
 * while it shares its bar); the toolbar dropped on an edge gets a bar of its own there, dropped on the tab strip's halves it joins its
 * bar, dropped beside the tabs it sits next to them. A drop that makes no sense for [slot] changes nothing; the result is sanitized.
 */
internal fun applyChromeDock(arrangement: ChromeArrangement, slot: ChromeSlot, target: ChromeDockTarget): ChromeArrangement {
    val current = arrangement.sanitized()
    val next = when (slot) {
        ChromeSlot.TABS -> when (target) {
            is ChromeDockTarget.Edge -> current.copy(tabPosition = target.edge.toTabStripPosition())
            else -> current
        }
        ChromeSlot.TOOLBAR -> when (target) {
            // Next to a vertical tab strip, its own edge and "beside tabs" are the same place: store it as the latter.
            is ChromeDockTarget.Edge ->
                if (current.tabEdge.isVertical && target.edge == current.tabEdge) {
                    current.copy(placement = ToolbarPlacement.BESIDE_TABS)
                } else {
                    current.copy(placement = ToolbarPlacement.OWN_EDGE, toolbarEdge = target.edge)
                }
            ChromeDockTarget.TabsStart -> current.copy(placement = ToolbarPlacement.WITH_TABS_START)
            ChromeDockTarget.TabsEnd -> current.copy(placement = ToolbarPlacement.WITH_TABS_END)
            ChromeDockTarget.BesideTabs -> current.copy(placement = ToolbarPlacement.BESIDE_TABS)
        }
    }
    return next.sanitized()
}

// ---------------------------------------------------------------------------------------------------------------
// Popups anchored to the chrome
// ---------------------------------------------------------------------------------------------------------------

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
