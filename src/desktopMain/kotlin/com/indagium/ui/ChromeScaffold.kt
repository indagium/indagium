@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// The window chrome: the tab strip and the main toolbar on whichever window edges Settings > Appearance > Tabs says, with the app's
// content in what is left. The arrangement maths is the pure ChromeLayout.kt; this file lays it out and adds the grips that move the
// two parts.
//
// ChromeScaffold is one Layout that always emits the same children in the same order (the two bar backdrops, the tab strip, the
// toolbar, the two bar borders, the content, the two grips); only their rects change. So the content is never re-parented when the tabs or the toolbar
// move, and what it remembers (scroll positions, a playing video) survives the move. Moving a part is a manual pointer drag on its grip,
// NOT Compose/AWT drag-and-drop (App.kt's file-drop target must never see it, and a test can drive it). While one is dragged the
// window-edge drop strips show, and the settings are written once, on release. A locked chrome (AppSettings.chromeLocked) has no grips.
// The tab strip and the toolbar are never hideable.

private const val DOCK_FILL_ALPHA = 0.12f
private const val DOCK_BORDER_ALPHA = 0.55f
private const val DOCK_DASH_DP = 5f
private const val DOCK_VERTICAL_TEXT_DEGREES = -90f

/** Where the toolbar's buttons are on screen, for the popups that open next to them (the Recent files menu). Root coordinates, dp. */
internal class ChromeAnchors {
    var recentButton by mutableStateOf<TileRect?>(null)
}

/** The ids the scaffold's Layout places its children by. */
private enum class ChromePart { TAB_BAR, TOOLBAR_BAR, TABS, TOOLBAR, TAB_BAR_BORDER, TOOLBAR_BAR_BORDER, CONTENT, TABS_GRIP, TOOLBAR_GRIP }

/** The interaction state of one scaffold: which part is being dragged and where the pointer is (host dp). */
private class ChromeDragState {
    var dragged by mutableStateOf<ChromeSlot?>(null)
    var pointer by mutableStateOf<Offset?>(null)
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
    val arrangement = chromeArrangement(settings)
    val locked = settings.chromeLocked
    val metrics = ChromeMetrics(tabBarHeight = tabStripHeightDp(settings.tabStripStyle).toFloat())
    val drag = remember { ChromeDragState() }
    var hostPx by remember { mutableStateOf(IntSize.Zero) }
    var toolbarLengthPx by remember { mutableStateOf(0) }
    val toolbarVertical = arrangement.toolbarOrientation == ChromeOrientation.VERTICAL
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

    fun cancelDrag() {
        drag.dragged = null
        drag.pointer = null
        currentReclaim()
    }

    fun endDrag(slot: ChromeSlot) {
        // Hit-tested again from the latest pointer rather than the last composed target: the release can land before the next frame.
        val target = drag.pointer?.let { p -> chromeDockTargetAt(currentZonesFor(slot), p.x, p.y) }
        val next = target?.let { applyChromeDock(currentArrangement, slot, it) }
        cancelDrag()
        if (next != null) state.updateSettings { it.withChromeArrangement(next) }
    }

    val gripCallbacks = ChromeGripCallbacks(
        onStart = { slot ->
            drag.dragged = slot
            drag.pointer = null
        },
        onMove = { rootPx -> drag.pointer = Offset((rootPx.x - drag.hostRoot.x) / density, (rootPx.y - drag.hostRoot.y) / density) },
        onEnd = ::endDrag,
        onCancel = ::cancelDrag,
        isDragging = { drag.dragged == it },
    )
    Box(
        modifier.fillMaxSize()
            .onSizeChanged { hostPx = it }
            .onGloballyPositioned { drag.hostRoot = it.positionInRoot() },
    ) {
        Layout(
            content = {
                // The same children in the same order whatever the arrangement; see the header comment.
                Box(Modifier.layoutId(ChromePart.TAB_BAR).testTag("tab-strip").background(tabStripBackground(settings.tabStripStyle, tc)))
                Box(Modifier.layoutId(ChromePart.TOOLBAR_BAR).testTag("main-toolbar-bar").background(tabStripBackground(settings.tabStripStyle, tc)))
                Box(Modifier.layoutId(ChromePart.TABS)) {
                    TabStrip(state, arrangement.tabsOrientation, Modifier.fillMaxSize(), onReclaimFocus)
                }
                Box(
                    Modifier.layoutId(ChromePart.TOOLBAR)
                        .onSizeChanged { toolbarLengthPx = if (toolbarVertical) it.height else it.width },
                ) {
                    MainToolbar(
                        state = state,
                        orientation = arrangement.toolbarOrientation,
                        onReclaimFocus = onReclaimFocus,
                        modifier = if (toolbarVertical) Modifier.fillMaxWidth() else Modifier.fillMaxHeight(),
                        labelsFit = arrangement.toolbarInTabBar && tabsVertical,
                        onRecentAnchor = { anchors.recentButton = it },
                    )
                }
                // A bar's border is drawn over its tabs and buttons, as the one-piece bar always did (Modifier.border paints after its content).
                Box(Modifier.layoutId(ChromePart.TAB_BAR_BORDER).border(BorderStroke(1.dp, tc.br)))
                Box(
                    Modifier.layoutId(ChromePart.TOOLBAR_BAR_BORDER)
                        .then(if (arrangement.toolbarInTabBar) Modifier else Modifier.border(BorderStroke(1.dp, tc.br))),
                )
                Box(Modifier.layoutId(ChromePart.CONTENT)) { content() }
                Box(Modifier.layoutId(ChromePart.TABS_GRIP)) {
                    if (!locked) ChromeGrip(ChromeSlot.TABS, tabsVertical, "Move tabs", "chrome-grip-tabs", gripCallbacks)
                }
                Box(Modifier.layoutId(ChromePart.TOOLBAR_GRIP)) {
                    if (!locked) ChromeGrip(ChromeSlot.TOOLBAR, toolbarVertical, "Move toolbar", "chrome-grip-toolbar", gripCallbacks)
                }
            },
            modifier = Modifier.fillMaxSize(),
            measurePolicy = policy,
        )
        val dragged = drag.dragged
        if (dragged != null) {
            val zones = zonesFor(dragged)
            val target = drag.pointer?.let { chromeDockTargetAt(zones, it.x, it.y) }
            ChromeDockOverlay(dragged, zones, target)
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
        val toolbarVertical = arrangement.toolbarOrientation == ChromeOrientation.VERTICAL
        val cross = m.toolbarCross(arrangement).roundToInt()
        val toolbarPlaceable = parts[ChromePart.TOOLBAR]?.measure(
            if (toolbarVertical) {
                Constraints(minWidth = cross, maxWidth = cross, minHeight = 0, maxHeight = m.toolbarMaxLength(arrangement, height.toFloat()).roundToInt())
            } else {
                Constraints(0, width, cross, cross)
            },
        )
        val toolbarLength = toolbarPlaceable?.let { if (toolbarVertical) it.height else it.width } ?: 0
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
            strays.forEach { it.place(0, 0) }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Grips
// ---------------------------------------------------------------------------------------------------------------

private class ChromeGripCallbacks(
    val onStart: (ChromeSlot) -> Unit,
    val onMove: (Offset) -> Unit,
    val onEnd: (ChromeSlot) -> Unit,
    val onCancel: () -> Unit,
    val isDragging: (ChromeSlot) -> Boolean,
)

// The move handle of the tab strip or the toolbar. A vertical part has a header for it that is always drawn; a horizontal part's grip
// overlays the part's first few dp, takes no room (so a horizontal bar looks exactly as it always did) and shows only under the pointer.
@Composable
private fun ChromeGrip(slot: ChromeSlot, partVertical: Boolean, description: String, tag: String, callbacks: ChromeGripCallbacks) {
    val tc = tc()
    val current by rememberUpdatedState(callbacks)
    var gripRoot by remember { mutableStateOf(Offset.Zero) }
    var hovered by remember { mutableStateOf(false) }
    // A drag whose grip leaves the composition (the chrome got locked mid-drag) never reports an end: drop its state here.
    DisposableEffect(slot) {
        onDispose { if (current.isDragging(slot)) current.onCancel() }
    }
    val dragging = callbacks.isDragging(slot)
    val visible = partVertical || hovered || dragging
    Box(
        Modifier
            .fillMaxSize()
            .moveCursor()
            .onGloballyPositioned { gripRoot = it.positionInRoot() }
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .testTag(tag)
            .semantics { contentDescription = description }
            .pointerInput(slot) {
                detectMoveDragGestures(
                    onDragStart = { down ->
                        current.onStart(slot)
                        current.onMove(gripRoot + down)
                    },
                    onDragEnd = { current.onEnd(slot) },
                    onDragCancel = { current.onCancel() },
                    onDrag = { change, _ ->
                        change.consume()
                        current.onMove(gripRoot + change.position)
                    },
                )
            }
            .then(if (hovered || dragging) Modifier.background(tc.hv) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (visible) {
            Icon(
                Icons.Outlined.DragIndicator,
                contentDescription = null,
                tint = if (hovered || dragging) tc.ts else tc.td,
                modifier = Modifier.size(12.dp).then(if (partVertical) Modifier.rotate(90f) else Modifier),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Drop strips
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun ChromeDockOverlay(slot: ChromeSlot, zones: List<ChromeDockZone>, target: ChromeDockTarget?) {
    Box(Modifier.fillMaxSize()) {
        zones.forEach { zone ->
            key(zone.target.key()) { ChromeDockStrip(slot, zone, active = zone.target == target) }
        }
    }
}

@Composable
private fun ChromeDockStrip(slot: ChromeSlot, zone: ChromeDockZone, active: Boolean) {
    val tc = tc()
    val rect = zone.rect
    val edge = (zone.target as? ChromeDockTarget.Edge)?.edge
    val sideways = edge != null && edge.isVertical
    Box(
        Modifier
            .offset(rect.x.dp, rect.y.dp)
            .size(rect.w.dp, rect.h.dp)
            .testTag("chrome-dock-${zone.target.key()}")
            .drawBehind {
                val dash = DOCK_DASH_DP.dp.toPx()
                if (active) drawRect(tc.ac.copy(alpha = DOCK_FILL_ALPHA))
                drawRoundRect(
                    color = tc.ac.copy(alpha = if (active) 1f else DOCK_BORDER_ALPHA),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            chromeDockLabel(slot, zone.target),
            color = tc.ac,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.wrapContentSize(unbounded = true).then(if (sideways) Modifier.rotate(DOCK_VERTICAL_TEXT_DEGREES) else Modifier),
        )
    }
}
