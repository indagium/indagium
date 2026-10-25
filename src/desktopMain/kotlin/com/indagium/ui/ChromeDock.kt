package com.indagium.ui

import com.indagium.model.AppSettings
import com.indagium.model.ChromeEdge
import com.indagium.model.ToolbarAlign
import com.indagium.model.ToolbarPlacement
import kotlin.math.max
import kotlin.math.min

// The dock targets a dragged tab strip or toolbar may be dropped on, the zones that show them, and what a drop does to the arrangement.
// Pure (ChromeLayoutTest); ChromeLayout.kt has the arrangement and the frames the zones are computed from.
//
// The tab strip is dropped on a window edge. The toolbar is dropped on the start or end half of a window edge (a bar of its own there,
// its buttons at that end); on the tab strip's own edge there are two such bars, so it is dropped before the tabs (the bar nearer the
// window, over the outer half of the tab bar) or after them (the content-side strip); on the inner half of the tab bar it joins the tabs
// at the start or end. Dragging the tab strip leaves the toolbar where it is on screen.

/** Whether a toolbar's bar sits before (window side of) or after (content side of) the tab bar on the tab strip's own edge. */
internal enum class ToolbarOrder { BEFORE_TABS, AFTER_TABS }

/** Where a dragged tab strip or toolbar can be dropped. */
internal sealed interface ChromeDockTarget {
    /** A window edge, for the tab strip: its new position. */
    data class Edge(val edge: ChromeEdge) : ChromeDockTarget

    /**
     * For the toolbar: a bar of its own on [edge], its buttons at [align]. [order] says before or after the tab bar and is null on every
     * edge but the tab strip's own.
     */
    data class ToolbarEdge(val edge: ChromeEdge, val align: ToolbarAlign, val order: ToolbarOrder? = null) : ChromeDockTarget

    /** The start (left or top) of the tab strip's bar: the toolbar joins the tab strip there. */
    data object TabsStart : ChromeDockTarget

    /** The end (right or bottom) of the tab strip's bar. */
    data object TabsEnd : ChromeDockTarget
}

/** The stable name a dock zone's test tag uses (`chrome-dock-<key>`). */
internal fun ChromeDockTarget.key(): String = when (this) {
    is ChromeDockTarget.Edge -> edge.name.lowercase()
    is ChromeDockTarget.ToolbarEdge -> listOfNotNull(
        edge.name.lowercase(),
        when (order) {
            ToolbarOrder.BEFORE_TABS -> "before"
            ToolbarOrder.AFTER_TABS -> "after"
            null -> null
        },
        align.name.lowercase(),
    ).joinToString("-")
    ChromeDockTarget.TabsStart -> "tabs-start"
    ChromeDockTarget.TabsEnd -> "tabs-end"
}

// The side of [edge] a toolbar's buttons are at for [align]: left / right along a horizontal edge, top / bottom along a vertical one.
private fun alignWord(edge: ChromeEdge, align: ToolbarAlign): String = when (align) {
    ToolbarAlign.START -> if (edge.isVertical) "top" else "left"
    ToolbarAlign.CENTER -> "centre"
    ToolbarAlign.END -> if (edge.isVertical) "bottom" else "right"
}

/** The caption of a dock zone while dragging [slot]. */
internal fun chromeDockLabel(slot: ChromeSlot, target: ChromeDockTarget): String = when (target) {
    is ChromeDockTarget.Edge -> (if (slot == ChromeSlot.TABS) "Tabs" else "Toolbar") + " · " + target.edge.name.lowercase().replaceFirstChar { it.uppercase() }
    is ChromeDockTarget.ToolbarEdge -> when (target.order) {
        ToolbarOrder.BEFORE_TABS -> "Before tabs · " + alignWord(target.edge, target.align)
        ToolbarOrder.AFTER_TABS -> "After tabs · " + alignWord(target.edge, target.align)
        null -> "Toolbar · " + target.edge.name.lowercase().replaceFirstChar { it.uppercase() } + " · " + alignWord(target.edge, target.align)
    }
    ChromeDockTarget.TabsStart -> "With tabs · start"
    ChromeDockTarget.TabsEnd -> "With tabs · end"
}

internal data class ChromeDockZone(val target: ChromeDockTarget, val rect: TileRect)

/** The window-edge strips along the inside of [content], top and bottom full width, left and right between them (so none overlap). */
private fun edgeBands(content: TileRect, band: Float): List<Pair<ChromeEdge, TileRect>> {
    val horizontalBand = min(band, content.h / 3f)
    val verticalBand = min(band, content.w / 3f)
    val middleY = content.y + horizontalBand
    val middleH = max(0f, content.h - 2 * horizontalBand)
    return listOf(
        ChromeEdge.TOP to TileRect(content.x, content.y, content.w, horizontalBand),
        ChromeEdge.BOTTOM to TileRect(content.x, content.bottom - horizontalBand, content.w, horizontalBand),
        ChromeEdge.LEFT to TileRect(content.x, middleY, verticalBand, middleH),
        ChromeEdge.RIGHT to TileRect(content.right - verticalBand, middleY, verticalBand, middleH),
    )
}

// The start and end halves of [rect] along its main axis (left / right of a horizontal strip, top / bottom of a vertical one).
private fun halves(rect: TileRect, vertical: Boolean): List<Pair<ToolbarAlign, TileRect>> {
    val half = rect.mainLength(vertical) / 2f
    return listOf(ToolbarAlign.START to rect.head(half, vertical), ToolbarAlign.END to rect.tail(half, vertical))
}

// The half of the tab bar nearer the window edge (before the tabs) and the half nearer the content (where the toolbar joins them).
private fun tabBarHalves(bar: TileRect, edge: ChromeEdge): Pair<TileRect, TileRect> {
    val vertical = edge.isVertical
    val half = (if (vertical) bar.w else bar.h) / 2f
    val first = if (vertical) TileRect(bar.x, bar.y, half, bar.h) else TileRect(bar.x, bar.y, bar.w, half)
    val second = if (vertical) TileRect(bar.x + half, bar.y, bar.w - half, bar.h) else TileRect(bar.x, bar.y + half, bar.w, bar.h - half)
    return if (edge == ChromeEdge.TOP || edge == ChromeEdge.LEFT) first to second else second to first
}

/**
 * The zones a dragged [slot] can be dropped on, for the window laid out as [frames]. Dragging the tab strip offers the four edges.
 * Dragging the toolbar offers the start and end half of each edge's strip (on the tab strip's own edge that strip is "after the tabs"),
 * "before the tabs" over the outer half of the tab bar, and the inner half of the tab bar, as its start and end halves, to join the tabs.
 */
internal fun chromeDockZones(slot: ChromeSlot, arrangement: ChromeArrangement, frames: ChromeFrames, metrics: ChromeMetrics): List<ChromeDockZone> {
    val a = arrangement.sanitized()
    val bands = edgeBands(frames.content, metrics.dockBand)
    if (slot == ChromeSlot.TABS) return bands.map { (edge, rect) -> ChromeDockZone(ChromeDockTarget.Edge(edge), rect) }
    val edgeZones = bands.flatMap { (edge, rect) ->
        val order = if (edge == a.tabEdge) ToolbarOrder.AFTER_TABS else null
        halves(rect, edge.isVertical).map { (align, half) -> ChromeDockZone(ChromeDockTarget.ToolbarEdge(edge, align, order), half) }
    }
    val tabsVertical = a.tabEdge.isVertical
    val (outer, inner) = tabBarHalves(frames.tabBar, a.tabEdge)
    val before = halves(outer, tabsVertical).map { (align, half) ->
        ChromeDockZone(ChromeDockTarget.ToolbarEdge(a.tabEdge, align, ToolbarOrder.BEFORE_TABS), half)
    }
    val join = halves(inner, tabsVertical).map { (align, half) ->
        ChromeDockZone(if (align == ToolbarAlign.START) ChromeDockTarget.TabsStart else ChromeDockTarget.TabsEnd, half)
    }
    return before + join + edgeZones
}

/** The target under the point ([x], [y]), or null. Zones never overlap, so the order does not matter. */
internal fun chromeDockTargetAt(zones: List<ChromeDockZone>, x: Float, y: Float): ChromeDockTarget? =
    zones.firstOrNull { it.rect.contains(x, y) }?.target

// The tab strip moves; a toolbar that shared its bar stays put on screen, as a bar of its own on the strip's old edge at the side it was.
private fun ChromeArrangement.withTabsMovedTo(edge: ChromeEdge): ChromeArrangement {
    if (edge == tabEdge) return this

    fun staysBehind(align: ToolbarAlign) =
        copy(placement = ToolbarPlacement.OWN_EDGE, toolbarEdge = tabEdge, toolbarOuter = false, toolbarAlign = align)
    val left = when (placement) {
        ToolbarPlacement.WITH_TABS_START -> staysBehind(ToolbarAlign.START)
        ToolbarPlacement.WITH_TABS_END -> staysBehind(ToolbarAlign.END)
        else -> this
    }
    return left.copy(tabPosition = edge.toTabStripPosition())
}

/**
 * What dropping [slot] on [target] does to [arrangement]. The tab strip dropped on an edge moves there and the toolbar stays where it was
 * on screen (a toolbar that shared the tab bar becomes a bar of its own on the old edge, aligned to the side it was at). The toolbar
 * dropped on an edge strip gets a bar of its own there with its buttons at the half dropped on (before or after the tab bar on the tab
 * strip's edge); dropped on the tab bar's halves it joins the tabs. A drop that makes no sense for [slot] changes nothing; the result
 * is sanitized.
 */
internal fun applyChromeDock(arrangement: ChromeArrangement, slot: ChromeSlot, target: ChromeDockTarget): ChromeArrangement {
    val current = arrangement.sanitized()
    val next = when (slot) {
        ChromeSlot.TABS -> (target as? ChromeDockTarget.Edge)?.let { current.withTabsMovedTo(it.edge) } ?: current
        ChromeSlot.TOOLBAR -> when (target) {
            is ChromeDockTarget.ToolbarEdge -> current.copy(
                placement = ToolbarPlacement.OWN_EDGE,
                toolbarEdge = target.edge,
                toolbarAlign = target.align,
                toolbarOuter = target.order?.let { it == ToolbarOrder.BEFORE_TABS } ?: current.toolbarOuter,
            )
            ChromeDockTarget.TabsStart -> current.copy(placement = ToolbarPlacement.WITH_TABS_START)
            ChromeDockTarget.TabsEnd -> current.copy(placement = ToolbarPlacement.WITH_TABS_END)
            is ChromeDockTarget.Edge -> current
        }
    }
    return next.sanitized()
}

/**
 * [this] with the toolbar put where [arrangement] says AND taken out of the log view's layout: the explicit move of the toolbar tile to
 * a window edge ([withChromeArrangement] would only edit the fallback bar while the toolbar is stored IN_LAYOUT).
 */
internal fun AppSettings.withToolbarLeavingLayout(arrangement: ChromeArrangement): AppSettings {
    val a = arrangement.sanitized()
    val placed = copy(
        tabStripPosition = a.tabPosition,
        toolbarPlacement = a.placement.takeIf { it != ToolbarPlacement.IN_LAYOUT } ?: ToolbarPlacement.WITH_TABS_END,
        toolbarEdge = a.toolbarEdge,
        toolbarOuter = a.toolbarOuter,
        toolbarAlign = a.toolbarAlign,
    )
    // The stored layout loses its toolbar tile now (the sanitizer removes it when the toolbar is no longer IN_LAYOUT).
    return placed.logViewLayout?.let { placed.withLogViewLayout(it) } ?: placed
}
