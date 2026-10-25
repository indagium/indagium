package com.indagium.ui

import com.indagium.model.AppSettings
import com.indagium.model.ChromeEdge
import com.indagium.model.DEFAULT_VERTICAL_TAB_BAR_DP
import com.indagium.model.MAX_VERTICAL_TAB_BAR_DP
import com.indagium.model.MIN_VERTICAL_TAB_BAR_DP
import com.indagium.model.TabStripPosition
import com.indagium.model.ToolbarAlign
import com.indagium.model.ToolbarPlacement
import kotlin.math.max
import kotlin.math.min

// The pure side of the window chrome (ui/ChromeScaffold.kt draws it): where the tab strip and the main toolbar sit and the rects of
// every part. The dock targets and what a drop does are ChromeDock.kt; popups are ChromePopups.kt. No Compose here, so all of it is
// unit-tested directly (ChromeLayoutTest). Lengths are plain floats in one unit (dp for hit-testing, px inside the scaffold's Layout).
//
// The model: the tab strip sits on one window edge (AppSettings.tabStripPosition). The toolbar either shares the tab strip's bar, at its
// start or end (WITH_TABS_START / WITH_TABS_END), or has a bar of its own: OWN_EDGE (docked to toolbarEdge; the legacy BESIDE_TABS is
// that same edge as a vertical strip, on its content side), or it is a tile of the log view's layout (IN_LAYOUT; a surface without those
// tiles shows it as a bar at the fallback placement instead). Top and bottom bars span the window; left and right bars fill what is
// between them. Two bars on one edge stack with the tab strip outermost, or the toolbar's when toolbarOuter. A toolbar alone on its
// bar sits at its start, centre or end (toolbarAlign). Everything but the default joined top bar is drawn as a rounded card.

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
 * [toolbarOuter] says that the toolbar's bar is the outer one (window side) when it shares the tab strip's edge; [toolbarAlign] where
 * the buttons sit along a bar of their own.
 */
internal data class ChromeArrangement(
    val tabPosition: TabStripPosition = TabStripPosition.TOP,
    val placement: ToolbarPlacement = ToolbarPlacement.WITH_TABS_END,
    val toolbarEdge: ChromeEdge = ChromeEdge.TOP,
    val toolbarOuter: Boolean = false,
    val toolbarAlign: ToolbarAlign = ToolbarAlign.START,
) {
    val tabEdge: ChromeEdge get() = tabPosition.toEdge()

    val tabsOrientation: ChromeOrientation get() = tabEdge.orientation

    /** True when the toolbar shares the tab strip's bar (WITH_TABS_START / WITH_TABS_END). */
    val toolbarInTabBar: Boolean get() = placement == ToolbarPlacement.WITH_TABS_START || placement == ToolbarPlacement.WITH_TABS_END

    /** True when the toolbar is a tile of the log view's layout and the chrome draws none (IN_LAYOUT). */
    val toolbarInLayout: Boolean get() = placement == ToolbarPlacement.IN_LAYOUT

    /** The edge of the toolbar's own bar, or null when it shares the tab strip's bar or is in the layout. */
    val toolbarBarEdge: ChromeEdge?
        get() = when (placement) {
            ToolbarPlacement.WITH_TABS_START, ToolbarPlacement.WITH_TABS_END, ToolbarPlacement.IN_LAYOUT -> null
            ToolbarPlacement.BESIDE_TABS -> tabEdge
            ToolbarPlacement.OWN_EDGE -> toolbarEdge
        }

    /** True when the toolbar has a bar of its own on the tab strip's edge (so [toolbarOuter] matters). */
    val toolbarSharesTabEdge: Boolean get() = toolbarBarEdge == tabEdge

    /**
     * The way the toolbar lays its buttons out: along a horizontal bar, and as a wrapping row inside a vertical tab bar; stacked in a vertical
     * bar of its own.
     */
    val toolbarOrientation: ChromeOrientation
        get() = if (toolbarInTabBar || toolbarInLayout) ChromeOrientation.HORIZONTAL else (toolbarBarEdge ?: tabEdge).orientation

    /** True when the toolbar lies along a vertical bar: its length is then its height (a wrapping row inside a vertical tab bar included). */
    val toolbarAxisVertical: Boolean get() = (toolbarBarEdge ?: tabEdge).isVertical

    /** True when the toolbar is a horizontal row inside a vertical tab bar, so it wraps onto further lines to the bar's width. */
    val toolbarWrapped: Boolean get() = toolbarInTabBar && tabEdge.isVertical

    /** True when the bars are drawn as rounded cards like the log view's tiles: always, but for the default joined top bar. */
    val cards: Boolean get() = !(tabEdge == ChromeEdge.TOP && toolbarInTabBar)

    /**
     * This arrangement with an invalid or legacy combination turned into a plain one: BESIDE_TABS is the toolbar's own bar on a vertical
     * strip's edge, on its content side (OWN_EDGE, inner), and with a horizontal strip it is the end of the strip's bar.
     */
    fun sanitized(): ChromeArrangement = when {
        placement != ToolbarPlacement.BESIDE_TABS -> this
        tabEdge.isVertical -> copy(placement = ToolbarPlacement.OWN_EDGE, toolbarEdge = tabEdge, toolbarOuter = false)
        else -> copy(placement = ToolbarPlacement.WITH_TABS_END)
    }
}

/**
 * The arrangement the settings ask for, sanitized (the stored values are not rewritten, so a later valid tab position restores Beside
 * tabs). While the toolbar is IN_LAYOUT but [toolbarHosted] is false (this surface has no log-view tiles) it is the stored fallback bar.
 */
internal fun chromeArrangement(settings: AppSettings, toolbarHosted: Boolean = false): ChromeArrangement {
    val placement = if (settings.toolbarPlacement == ToolbarPlacement.IN_LAYOUT && !toolbarHosted) {
        settings.toolbarFallbackPlacement.takeIf { it != ToolbarPlacement.IN_LAYOUT } ?: ToolbarPlacement.WITH_TABS_END
    } else {
        settings.toolbarPlacement
    }
    return ChromeArrangement(settings.tabStripPosition, placement, settings.toolbarEdge, settings.toolbarOuter, settings.toolbarAlign).sanitized()
}

/**
 * [this] with the arrangement written (sanitized first). While the toolbar is stored IN_LAYOUT and [arrangement] is the bar arrangement of
 * a surface without tiles, the edit is to the fallback: the placement stays IN_LAYOUT and the fallback placement is what changes.
 */
internal fun AppSettings.withChromeArrangement(arrangement: ChromeArrangement): AppSettings {
    val a = arrangement.sanitized()
    val editsFallback = toolbarPlacement == ToolbarPlacement.IN_LAYOUT && a.placement != ToolbarPlacement.IN_LAYOUT
    val next = copy(
        tabStripPosition = a.tabPosition,
        toolbarPlacement = if (editsFallback) toolbarPlacement else a.placement,
        toolbarFallbackPlacement = if (editsFallback) a.placement else toolbarFallbackPlacement,
        toolbarEdge = a.toolbarEdge,
        toolbarOuter = a.toolbarOuter,
        toolbarAlign = a.toolbarAlign,
    )
    return if (next == this) this else next
}

// ---------------------------------------------------------------------------------------------------------------
// Metrics and frames
// ---------------------------------------------------------------------------------------------------------------

/** Width (dp) of a vertical tab strip's bar before the splitter changes it. */
internal const val CHROME_VERTICAL_TAB_BAR_DP: Float = DEFAULT_VERTICAL_TAB_BAR_DP

/** Height (dp) of a horizontal bar that holds only the toolbar. */
internal const val CHROME_TOOLBAR_BAR_HORIZONTAL_DP: Float = 36f

/** Width (dp) of a vertical bar that holds only the toolbar (icon-only buttons stacked). */
internal const val CHROME_TOOLBAR_BAR_VERTICAL_DP: Float = 40f

/** Width (dp) of that bar when the buttons show their labels: wide enough for "Compare" and the icon, longer labels take an ellipsis. */
internal const val CHROME_TOOLBAR_BAR_VERTICAL_LABELS_DP: Float = 96f

/** The padding (dp) a flat bar keeps on its cross axis, inside its border: 1 dp, as the tab strip always had. */
internal const val CHROME_BAR_PADDING_DP: Float = 1f

/** The padding (dp) a card bar keeps on both axes: enough to keep square tab corners clear of the card's rounded ones. */
internal const val CHROME_CARD_PAD_DP: Float = 3f

/** Length (dp) of the grip header a vertical bar reserves for each of its parts; a horizontal part's grip is an overlay and takes no room. */
internal const val CHROME_GRIP_RESERVE_DP: Float = 14f

/** Length (dp) along a horizontal part that its overlay grip covers. */
internal const val CHROME_GRIP_OVERLAY_DP: Float = 12f

/** The room (dp) a vertical tab bar always keeps for its tabs: a toolbar sharing the bar scrolls rather than push the tabs out. */
internal const val CHROME_VERTICAL_MIN_TABS_DP: Float = 150f

/** Thickness (dp) of the window-edge drop strips. */
internal const val CHROME_DOCK_BAND_DP: Float = 32f

/** Width (dp) of the vertical tab bar's splitter handle, and how far (dp) it reaches into the bar. */
internal const val CHROME_SPLITTER_DP: Float = 8f
internal const val CHROME_SPLITTER_INSIDE_DP: Float = 5f

/** [width] kept inside the range the vertical tab bar's splitter allows (a NaN gives the default). */
internal fun clampVerticalTabBarDp(width: Float): Float =
    if (width.isNaN()) CHROME_VERTICAL_TAB_BAR_DP else width.coerceIn(MIN_VERTICAL_TAB_BAR_DP, MAX_VERTICAL_TAB_BAR_DP)

/**
 * The sizes the frames are computed from, in one unit. [tabBarHeight] is the horizontal tab bar's height (it follows the tab style).
 * [cardMargin] is the gap a card bar keeps from the window edge (or the bar before it) and at both ends (the log view's host padding, so the
 * gaps match the tiles'); [cardPad] its inner padding.
 */
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
    val cardMargin: Float = LOG_VIEW_HOST_PAD_DP.toFloat(),
    val cardPad: Float = CHROME_CARD_PAD_DP,
    val splitter: Float = CHROME_SPLITTER_DP,
    val splitterInside: Float = CHROME_SPLITTER_INSIDE_DP,
) {
    /** Every length multiplied by [factor] (dp to px). */
    fun scaled(factor: Float) = ChromeMetrics(
        tabBarHeight * factor, verticalTabBar * factor, toolbarBarHorizontal * factor, toolbarBarVertical * factor,
        barPadding * factor, gripReserve * factor, gripOverlay * factor, dockBand * factor, verticalMinTabs * factor,
        cardMargin * factor, cardPad * factor, splitter * factor, splitterInside * factor,
    )

    /** The thickness of the tab strip's bar for [arrangement] (its parts' room across, plus the bar padding both sides). */
    fun tabBarThickness(arrangement: ChromeArrangement): Float = if (arrangement.tabEdge.isVertical) verticalTabBar else tabBarHeight

    /** The thickness of the toolbar's own bar (only meaningful when the toolbar has one). */
    fun toolbarBarThickness(edge: ChromeEdge): Float = if (edge.isVertical) toolbarBarVertical else toolbarBarHorizontal

    /**
     * The longest the toolbar may be along its main axis in a window [windowLength] long on that axis. A toolbar in a vertical tab bar
     * leaves the tabs [verticalMinTabs] (at least half the bar); every other toolbar may use the whole window.
     */
    fun toolbarMaxLength(arrangement: ChromeArrangement, windowLength: Float): Float =
        if (arrangement.toolbarInTabBar && arrangement.tabEdge.isVertical) max(windowLength / 2f, windowLength - verticalMinTabs) else windowLength

    /**
     * The cross-axis size the toolbar is given: its bar's thickness less the flat bar's padding both sides (a card bar grows by what its
     * larger padding takes).
     */
    fun toolbarCross(arrangement: ChromeArrangement): Float {
        val bar = arrangement.toolbarBarEdge?.let(::toolbarBarThickness) ?: tabBarThickness(arrangement)
        return max(0f, bar - 2 * barPadding)
    }
}

/**
 * Where every part of the chrome goes in a window. [tabBar] and [toolbarBar] (null while the toolbar shares the tab bar or is in the
 * layout) are the bars with their background and border (a card's rect, inside its margin, when [cards]); [tabs] and [toolbar] are the
 * parts themselves, inside the bars (the toolbar has no size while it is in the layout); [content] is what is left for the app.
 * [tabsGrip] and [toolbarGrip] are null while the chrome is locked, [tabSplitter] while the tab strip is not vertical.
 */
internal data class ChromeFrames(
    val tabBar: TileRect,
    val tabs: TileRect,
    val toolbarBar: TileRect?,
    val toolbar: TileRect,
    val content: TileRect,
    val tabsGrip: TileRect?,
    val toolbarGrip: TileRect?,
    val tabSplitter: TileRect? = null,
    val cards: Boolean = false,
)

internal fun TileRect.inset(cross: Float, vertical: Boolean): TileRect =
    if (vertical) TileRect(x + cross, y, max(0f, w - 2 * cross), h) else TileRect(x, y + cross, w, max(0f, h - 2 * cross))

// A card bar's rect inside its region: [margin] in from the window edge and from both ends, none on the content side (the content, or the
// next bar's own margin, supplies that gap). Never more than the room there is.
internal fun TileRect.cardInset(edge: ChromeEdge, margin: Float): TileRect {
    val m = if (edge.isVertical) min(margin, h / 2f) else min(margin, w / 2f)
    val outer = min(margin, if (edge.isVertical) w else h)
    return when (edge) {
        ChromeEdge.TOP -> TileRect(x + m, y + outer, w - 2 * m, h - outer)
        ChromeEdge.BOTTOM -> TileRect(x + m, y, w - 2 * m, h - outer)
        ChromeEdge.LEFT -> TileRect(x + outer, y + m, w - outer, h - 2 * m)
        ChromeEdge.RIGHT -> TileRect(x, y + m, w - outer, h - 2 * m)
    }
}

// Never more than half the width / height, so a rect squeezed to nothing stays inside where it was.
internal fun TileRect.insetAll(by: Float): TileRect {
    val dx = min(by, w / 2f)
    val dy = min(by, h / 2f)
    return TileRect(x + dx, y + dy, w - 2 * dx, h - 2 * dy)
}

internal fun TileRect.mainLength(vertical: Boolean): Float = if (vertical) h else w

// The first [length] of the main axis.
internal fun TileRect.head(length: Float, vertical: Boolean): TileRect =
    if (vertical) TileRect(x, y, w, min(length, h)) else TileRect(x, y, min(length, w), h)

// What is left after the first [length] of the main axis.
internal fun TileRect.afterHead(length: Float, vertical: Boolean): TileRect =
    if (vertical) TileRect(x, y + min(length, h), w, max(0f, h - length)) else TileRect(x + min(length, w), y, max(0f, w - length), h)

// The last [length] of the main axis.
internal fun TileRect.tail(length: Float, vertical: Boolean): TileRect =
    if (vertical) TileRect(x, bottom - min(length, h), w, min(length, h)) else TileRect(right - min(length, w), y, min(length, w), h)

// What is left before the last [length] of the main axis.
internal fun TileRect.beforeTail(length: Float, vertical: Boolean): TileRect =
    if (vertical) TileRect(x, y, w, max(0f, h - length)) else TileRect(x, y, max(0f, w - length), h)

// [length] of the main axis, centred.
private fun TileRect.centered(length: Float, vertical: Boolean): TileRect {
    val l = min(length, mainLength(vertical))
    val offset = (mainLength(vertical) - l) / 2f
    return if (vertical) TileRect(x, y + offset, w, l) else TileRect(x + offset, y, l, h)
}

/** A part's region split into its grip and its body: a horizontal part's grip overlays the start of the body, a vertical part's is a header above it. */
private fun splitGrip(region: TileRect, vertical: Boolean, grips: Boolean, m: ChromeMetrics): Pair<TileRect?, TileRect> = when {
    !grips -> null to region
    vertical -> region.head(m.gripReserve, true) to region.afterHead(m.gripReserve, true)
    else -> region.head(m.gripOverlay, false) to region
}

private data class ChromeBarSpec(val slot: ChromeSlot, val edge: ChromeEdge, val thickness: Float)

// The bars in the order they stack from the window edge inwards: the tab strip first, unless the toolbar shares its edge and is the outer one.
private fun barSpecs(a: ChromeArrangement, metrics: ChromeMetrics): List<ChromeBarSpec> {
    val card = a.cards
    val growth = 2 * ((if (card) metrics.cardPad else metrics.barPadding) - metrics.barPadding) + if (card) metrics.cardMargin else 0f
    val tabs = ChromeBarSpec(ChromeSlot.TABS, a.tabEdge, metrics.tabBarThickness(a) + growth)
    val toolbar = a.toolbarBarEdge?.let { ChromeBarSpec(ChromeSlot.TOOLBAR, it, metrics.toolbarBarThickness(it) + growth) }
    return when {
        toolbar == null -> listOf(tabs)
        a.toolbarOuter && toolbar.edge == a.tabEdge -> listOf(toolbar, tabs)
        else -> listOf(tabs, toolbar)
    }
}

// Stacks the bars along the window edges: top and bottom bars span the window, left and right bars fill what is between them.
private fun stackBars(bars: List<ChromeBarSpec>, width: Float, height: Float): Pair<Map<ChromeSlot, TileRect>, TileRect> {
    val rects = HashMap<ChromeSlot, TileRect>()
    var top = 0f
    var bottom = height
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
    return rects to TileRect(left, top, max(0f, right - left), max(0f, bottom - top))
}

/** The splitter handle along the content-side edge of a vertical tab bar [tabBar]. */
private fun tabSplitterRect(tabBar: TileRect, edge: ChromeEdge, m: ChromeMetrics): TileRect {
    val x = if (edge == ChromeEdge.LEFT) tabBar.right - m.splitterInside else tabBar.x - (m.splitter - m.splitterInside)
    return TileRect(x, tabBar.y, m.splitter, tabBar.h)
}

/**
 * The frames for [arrangement] in a [width] x [height] window. [toolbarLength] is the toolbar's natural length along its main axis (its
 * width in a horizontal bar, its height in a vertical one or in a vertical tab bar), measured by the caller; [grips] is false while the
 * chrome is locked.
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
    val cards = a.cards
    val margin = if (cards) metrics.cardMargin else 0f
    val (regions, content) = stackBars(barSpecs(a, metrics), width, height)
    val tabBar = regions.getValue(ChromeSlot.TABS).cardInset(a.tabEdge, margin)
    val toolbarBar = regions[ChromeSlot.TOOLBAR]?.let { region -> a.toolbarBarEdge?.let { region.cardInset(it, margin) } }
    val tabsVertical = a.tabEdge.isVertical
    val axisVertical = a.toolbarAxisVertical

    // A card keeps a padding on both axes (square tab corners must clear its rounded ones); a flat bar only across.
    fun partsArea(bar: TileRect, vertical: Boolean) = if (cards) bar.insetAll(metrics.cardPad) else bar.inset(metrics.barPadding, vertical)
    val tabsArea = partsArea(tabBar, tabsVertical)
    val toolbarRegionLength = toolbarLength + if (axisVertical && grips) metrics.gripReserve else 0f
    val tabsRegion: TileRect
    val toolbarRegion: TileRect
    when {
        a.toolbarInLayout -> {
            tabsRegion = tabsArea
            toolbarRegion = TileRect(tabBar.x, tabBar.y, 0f, 0f)
        }
        toolbarBar == null -> {
            val length = min(toolbarRegionLength, tabsArea.mainLength(tabsVertical))
            if (a.placement == ToolbarPlacement.WITH_TABS_START) {
                toolbarRegion = tabsArea.head(length, tabsVertical)
                tabsRegion = tabsArea.afterHead(length, tabsVertical)
            } else {
                toolbarRegion = tabsArea.tail(length, tabsVertical)
                tabsRegion = tabsArea.beforeTail(length, tabsVertical)
            }
        }
        else -> {
            tabsRegion = tabsArea
            val area = partsArea(toolbarBar, axisVertical)
            val length = min(toolbarRegionLength, area.mainLength(axisVertical))
            toolbarRegion = when (a.toolbarAlign) {
                ToolbarAlign.START -> area.head(length, axisVertical)
                ToolbarAlign.END -> area.tail(length, axisVertical)
                ToolbarAlign.CENTER -> area.centered(length, axisVertical)
            }
        }
    }
    val (tabsGrip, tabs) = splitGrip(tabsRegion, tabsVertical, grips, metrics)
    val (toolbarGrip, toolbar) = if (a.toolbarInLayout) null to toolbarRegion else splitGrip(toolbarRegion, axisVertical, grips, metrics)
    val splitter = if (tabsVertical) tabSplitterRect(tabBar, a.tabEdge, metrics) else null
    return ChromeFrames(tabBar, tabs, toolbarBar, toolbar, content, tabsGrip, toolbarGrip, splitter, cards)
}
