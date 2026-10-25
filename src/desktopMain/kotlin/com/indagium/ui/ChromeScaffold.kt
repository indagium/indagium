package com.indagium.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// The window chrome: the tab strip and the main toolbar on whichever window edges Settings > Appearance > Tabs says (or the toolbar as a
// tile of the log view, ToolbarTile.kt), with the app's content in what is left. The arrangement maths is the pure ChromeLayout.kt /
// ChromeDock.kt; this file lays it out and wires the grips (ChromeGrips.kt) that move the two parts.
//
// ChromeScaffold is one Layout that always emits the same children in the same order (the two bar backdrops, the tab strip, the
// toolbar, the two bar borders, the content, the two grips, the tab bar's splitter); only their rects change. So the content is never
// re-parented when the tabs or the toolbar move, and what it remembers (scroll positions, a playing video) survives the move. Moving a
// part is a manual pointer drag on its grip, NOT Compose/AWT drag-and-drop. While one is dragged the window-edge drop strips show, and
// the settings are written once, on release. A locked chrome (AppSettings.chromeLocked) has no grips. The tab strip and the toolbar are
// never hideable; the toolbar tile and the toolbar bar are one toolbar, shown in one place at a time.

/** Where the toolbar's buttons are on screen, for the popups that open next to them (the Recent files menu). Root coordinates, dp. */
internal class ChromeAnchors {
    var recentButton by mutableStateOf<TileRect?>(null)
}

/** The ids the scaffold's Layout places its children by. */
private enum class ChromePart { TAB_BAR, TOOLBAR_BAR, TABS, TOOLBAR, TAB_BAR_BORDER, TOOLBAR_BAR_BORDER, CONTENT, TABS_GRIP, TOOLBAR_GRIP, TAB_SPLITTER }

/** The interaction state of one scaffold: which part is being dragged, where the pointer is (host dp and window root px), a bar width draft. */
private class ChromeDragState {
    var dragged by mutableStateOf<ChromeSlot?>(null)
    var pointer by mutableStateOf<Offset?>(null)
    var pointerRoot: Offset? = null
    var barWidthDraft by mutableStateOf<Float?>(null)
    var hostRoot: Offset = Offset.Zero
}

/**
 * Lays [content] out in what the tab strip and the toolbar leave of the window, with those two where [AppState.settings] puts them.
 * [onReclaimFocus] must give root keyboard focus back (CLAUDE.md's clickable-steals-focus gotcha); it is called after every drag.
 * [anchors] is told where the Recent files button is.
 */
@Composable
internal fun ChromeScaffold(
    state: AppState,
    modifier: Modifier = Modifier,
    onReclaimFocus: () -> Unit = {},
    anchors: ChromeAnchors = remember { ChromeAnchors() },
    content: @Composable () -> Unit,
) {
    val tc = tc()
    val density = LocalDensity.current.density
    val settings = state.settings
    val arrangement = chromeArrangement(settings, state.toolbarHostedInLayout())
    val locked = settings.chromeLocked
    val drag = remember { ChromeDragState() }
    val bridge = remember { ToolbarDockBridge() }
    val metrics = ChromeMetrics(
        tabBarHeight = tabStripHeightDp(settings.tabStripStyle).toFloat(),
        verticalTabBar = clampVerticalTabBarDp(drag.barWidthDraft ?: settings.verticalTabBarWidthDp),
        toolbarBarVertical = if (settings.toolbarIconOnlyButtons) CHROME_TOOLBAR_BAR_VERTICAL_DP else CHROME_TOOLBAR_BAR_VERTICAL_LABELS_DP,
    )
    var hostPx by remember { mutableStateOf(IntSize.Zero) }
    var toolbarLengthPx by remember { mutableStateOf(0) }
    val axisVertical = arrangement.toolbarAxisVertical
    val tabsVertical = arrangement.tabsOrientation == ChromeOrientation.VERTICAL
    val policy = remember(arrangement, metrics, locked) { ChromeMeasurePolicy(arrangement, metrics, grips = !locked) }
    // The zones of the drop strips, for the part being dragged, in host dp (the same maths the Layout uses, in px).
    val zonesFor: (ChromeSlot) -> List<ChromeDockZone> = { slot ->
        val frames = chromeFrames(arrangement, hostPx.width / density, hostPx.height / density, metrics, toolbarLengthPx / density, !locked)
        chromeDockZones(slot, arrangement, frames, metrics)
    }
    val currentZonesFor by rememberUpdatedState(zonesFor)
    val currentArrangement by rememberUpdatedState(arrangement)
    val currentReclaim by rememberUpdatedState(onReclaimFocus)
    val hostDp: (Offset) -> Offset = { root -> Offset((root.x - drag.hostRoot.x) / density, (root.y - drag.hostRoot.y) / density) }
    val zoneAt: (Offset) -> ChromeDockTarget? = { root -> hostDp(root).let { chromeDockTargetAt(currentZonesFor(ChromeSlot.TOOLBAR), it.x, it.y) } }
    SideEffect {
        bridge.zoneAt = zoneAt
        bridge.tileRelease = { root ->
            val target = zoneAt(root)
            if (target != null) state.updateSettings { it.withToolbarLeavingLayout(applyChromeDock(currentArrangement, ChromeSlot.TOOLBAR, target)) }
            target != null
        }
    }

    fun cancelDrag() {
        drag.dragged = null
        drag.pointer = null
        drag.pointerRoot = null
        bridge.external.pointerRoot = null
        currentReclaim()
    }

    fun endDrag(slot: ChromeSlot) {
        // Hit-tested again from the latest pointer rather than the last composed target: the release can land before the next frame.
        val target = drag.pointer?.let { p -> chromeDockTargetAt(currentZonesFor(slot), p.x, p.y) }
        // Over no strip, the toolbar bar can still be dropped into the log view's tiles, on whatever the tile drag would drop it on.
        val tileDrop = if (target == null && slot == ChromeSlot.TOOLBAR) drag.pointerRoot?.let { bridge.external.resolver?.invoke(it) } else null
        val next = target?.let { applyChromeDock(currentArrangement, slot, it) }
        cancelDrag()
        if (next != null) state.updateSettings { it.withChromeArrangement(next) }
        if (tileDrop != null) bridge.dropInLayout?.invoke(tileDrop)
    }

    val gripCallbacks = ChromeGripCallbacks(
        onStart = { slot ->
            drag.dragged = slot
            drag.pointer = null
            drag.pointerRoot = null
        },
        onMove = { rootPx ->
            drag.pointerRoot = rootPx
            val p = hostDp(rootPx)
            drag.pointer = p
            // The tiles show the toolbar's drop target wherever no dock strip is under the pointer.
            val overStrip = drag.dragged?.let { chromeDockTargetAt(currentZonesFor(it), p.x, p.y) } != null
            bridge.external.pointerRoot = if (drag.dragged == ChromeSlot.TOOLBAR && !overStrip) rootPx else null
        },
        onEnd = ::endDrag,
        onCancel = ::cancelDrag,
        isDragging = { drag.dragged == it },
    )
    val cardShape = RoundedCornerShape(LOG_VIEW_CHROME_SPEC.cornerDp.dp)
    // The default joined top bar keeps its flat, square frame; every other bar is a card like the log view's tiles.
    val barLook = if (arrangement.cards) Modifier.background(tc.p, cardShape) else Modifier.background(tabStripBackground(settings.tabStripStyle, tc))
    val borderLook = if (arrangement.cards) Modifier.border(BorderStroke(1.dp, tc.br), cardShape) else Modifier.border(BorderStroke(1.dp, tc.br))
    CompositionLocalProvider(LocalToolbarDock provides bridge, LocalChromeAnchors provides anchors) {
        Box(
            modifier.fillMaxSize()
                .onSizeChanged { hostPx = it }
                .onGloballyPositioned { drag.hostRoot = it.positionInRoot() },
        ) {
            Layout(
                content = {
                    // The same children in the same order whatever the arrangement; see the header comment.
                    Box(Modifier.layoutId(ChromePart.TAB_BAR).testTag("tab-strip").then(barLook))
                    Box(Modifier.layoutId(ChromePart.TOOLBAR_BAR).testTag("main-toolbar-bar").then(barLook))
                    Box(Modifier.layoutId(ChromePart.TABS)) {
                        TabStrip(state, arrangement.tabsOrientation, Modifier.fillMaxSize(), onReclaimFocus)
                    }
                    Box(
                        Modifier.layoutId(ChromePart.TOOLBAR)
                            .onSizeChanged { toolbarLengthPx = if (axisVertical) it.height else it.width },
                    ) {
                        // In the log view's layout the toolbar is a tile there, and the chrome shows none.
                        if (!arrangement.toolbarInLayout) {
                            MainToolbar(
                                state = state,
                                orientation = arrangement.toolbarOrientation,
                                onReclaimFocus = onReclaimFocus,
                                modifier = if (axisVertical) Modifier.fillMaxWidth() else Modifier.fillMaxHeight(),
                                onRecentAnchor = { anchors.recentButton = it },
                                wrap = arrangement.toolbarWrapped,
                                popupsBeside = axisVertical,
                            )
                        }
                    }
                    // A bar's border is drawn over its tabs and buttons, as the one-piece bar always did (Modifier.border paints after its content).
                    Box(Modifier.layoutId(ChromePart.TAB_BAR_BORDER).then(borderLook))
                    Box(
                        Modifier.layoutId(ChromePart.TOOLBAR_BAR_BORDER)
                            .then(if (arrangement.toolbarBarEdge != null) borderLook else Modifier),
                    )
                    Box(Modifier.layoutId(ChromePart.CONTENT)) { content() }
                    Box(Modifier.layoutId(ChromePart.TABS_GRIP)) {
                        if (!locked) ChromeGrip(ChromeSlot.TABS, tabsVertical, "Move tabs", "chrome-grip-tabs", gripCallbacks)
                    }
                    Box(Modifier.layoutId(ChromePart.TOOLBAR_GRIP)) {
                        if (!locked && !arrangement.toolbarInLayout) {
                            ChromeGrip(ChromeSlot.TOOLBAR, axisVertical, "Move toolbar", "chrome-grip-toolbar", gripCallbacks)
                        }
                    }
                    Box(Modifier.layoutId(ChromePart.TAB_SPLITTER)) {
                        if (tabsVertical) {
                            ChromeSplitter(
                                edge = arrangement.tabEdge,
                                widthNow = { clampVerticalTabBarDp(drag.barWidthDraft ?: state.settings.verticalTabBarWidthDp) },
                                onDraft = { drag.barWidthDraft = clampVerticalTabBarDp(it) },
                                onCommit = {
                                    val width = drag.barWidthDraft
                                    drag.barWidthDraft = null
                                    if (width != null) state.updateSettings { it.copy(verticalTabBarWidthDp = width) }
                                    currentReclaim()
                                },
                                onReset = {
                                    drag.barWidthDraft = null
                                    state.updateSettings { it.copy(verticalTabBarWidthDp = CHROME_VERTICAL_TAB_BAR_DP) }
                                    currentReclaim()
                                },
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                measurePolicy = policy,
            )
            // The strips show while a grip is dragged, and while the toolbar TILE is dragged (it can go back to an edge).
            val tilePointer = bridge.tileDragPointerRoot
            val shownSlot = drag.dragged ?: ChromeSlot.TOOLBAR.takeIf { tilePointer != null }
            if (shownSlot != null) {
                val zones = zonesFor(shownSlot)
                val pointer = drag.pointer ?: tilePointer?.let(hostDp)
                val target = pointer?.let { chromeDockTargetAt(zones, it.x, it.y) }
                ChromeDockOverlay(shownSlot, zones, target)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Layout
// ---------------------------------------------------------------------------------------------------------------

// Lays every child out at its rect of chromeFrames, in px. The toolbar is measured first (with its cross size fixed) because its
// natural length along the bar decides how much of a shared bar is left for the tabs. A child with no recognised id (a Popup's anchor)
// is measured 0 x 0.
private class ChromeMeasurePolicy(
    private val arrangement: ChromeArrangement,
    private val metricsDp: ChromeMetrics,
    private val grips: Boolean,
) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val m = metricsDp.scaled(density)
        val parts = HashMap<ChromePart, Measurable>()
        measurables.forEach { child -> (child.layoutId as? ChromePart)?.let { parts[it] = child } }
        val axisVertical = arrangement.toolbarAxisVertical
        val cross = m.toolbarCross(arrangement).roundToInt()
        val toolbarPlaceable = parts[ChromePart.TOOLBAR]?.measure(
            when {
                arrangement.toolbarInLayout -> Constraints.fixed(0, 0)
                axisVertical -> {
                    val maxLength = m.toolbarMaxLength(arrangement, height.toFloat()).roundToInt()
                    Constraints(minWidth = cross, maxWidth = cross, minHeight = 0, maxHeight = maxLength)
                }
                else -> Constraints(0, width, cross, cross)
            },
        )
        val toolbarLength = toolbarPlaceable?.let { if (axisVertical) it.height else it.width } ?: 0
        val frames = chromeFrames(arrangement, width.toFloat(), height.toFloat(), m, toolbarLength.toFloat(), grips)

        fun fixed(part: ChromePart, rect: TileRect?): Placeable? {
            val w = rect?.w?.roundToInt()?.coerceAtLeast(0) ?: 0
            val h = rect?.h?.roundToInt()?.coerceAtLeast(0) ?: 0
            return parts[part]?.measure(Constraints.fixed(w, h))
        }
        val tabBar = fixed(ChromePart.TAB_BAR, frames.tabBar)
        val toolbarBar = fixed(ChromePart.TOOLBAR_BAR, frames.toolbarBar)
        val tabBarBorder = fixed(ChromePart.TAB_BAR_BORDER, frames.tabBar)
        val toolbarBarBorder = fixed(ChromePart.TOOLBAR_BAR_BORDER, frames.toolbarBar)
        val tabs = fixed(ChromePart.TABS, frames.tabs)
        val content = fixed(ChromePart.CONTENT, frames.content)
        val tabsGrip = fixed(ChromePart.TABS_GRIP, frames.tabsGrip)
        val toolbarGrip = fixed(ChromePart.TOOLBAR_GRIP, frames.toolbarGrip)
        val splitter = fixed(ChromePart.TAB_SPLITTER, frames.tabSplitter)
        // A child with no id is laid out too, at nothing, so the layout never throws on one it does not know.
        val strays = measurables.filter { it.layoutId !is ChromePart }.map { it.measure(Constraints.fixed(0, 0)) }
        return layout(width, height) {
            fun Placeable?.at(rect: TileRect?) = this?.place(rect?.x?.roundToInt() ?: 0, rect?.y?.roundToInt() ?: 0)
            // In emission order, so the grips are above the bars and the bars above the backdrops.
            tabBar.at(frames.tabBar)
            toolbarBar.at(frames.toolbarBar)
            tabs.at(frames.tabs)
            toolbarPlaceable.at(frames.toolbar)
            tabBarBorder.at(frames.tabBar)
            toolbarBarBorder.at(frames.toolbarBar)
            content.at(frames.content)
            tabsGrip.at(frames.tabsGrip)
            toolbarGrip.at(frames.toolbarGrip)
            splitter.at(frames.tabSplitter)
            strays.forEach { it.place(0, 0) }
        }
    }
}
