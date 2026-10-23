@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.HomeLayoutNode
import com.indagium.model.HomeSectionId
import com.indagium.model.HomeSplitAxis
import kotlin.math.roundToInt

// The New tab's tiling host (design A2): sections as cards in a window-manager-style layout whose arrangement comes from
// AppSettings.homeLayout (pure model + geometry in HomeLayout.kt). Moving a section is a manual pointer drag on its grip,
// NOT Compose/AWT drag-and-drop, so App.kt's file-drop target never sees it and a test can drive it.

/** Below this host width (dp) the sections stack in one vertical scroll in tree order, with no grips or splitters. */
internal const val HOME_STACK_BELOW_DP: Int = 720

/** The tip shown under the title until dismissed (and again from the "?" button). */
internal const val HOME_LAYOUT_TIP: String =
    "Tip: drag ⠿ onto a section's edge to split it, or onto the page edge to span it. Drag a gap to resize."

private const val HOME_TILE_GAP_DP = 12f
private const val HOME_SIZE_LABEL_MIN_CARD_DP = 360f
private const val HOME_SPLITTER_KEY_STEP_DP = 24f
private const val HOME_STACKED_HEIGHT_SHARE = 0.75f
private const val HOME_STACKED_MIN_HEIGHT_DP = 320f
private const val HOME_STACKED_MAX_HEIGHT_DP = 560f
private const val HOME_DRAGGED_CARD_ALPHA = 0.45f
private const val HOME_PREVIEW_FILL_ALPHA = 0.18f
private const val HOME_STRIP_FILL_ALPHA = 0.12f
private const val HOME_STRIP_BORDER_ALPHA = 0.55f
private const val HOME_STRIP_DASH_DP = 5f
private const val HOME_VERTICAL_TEXT_DEGREES = -90f

private val HOME_CARD_SHAPE = RoundedCornerShape(8.dp)

/**
 * One section's content for [HomeTilingHost]: the [icon] and [title] in its card header, an optional [action] composable
 * next to the hide button, and the [body] that fills the rest of the card (the body does its own scrolling).
 */
internal class HomeSectionSlot(
    val icon: ImageVector,
    val title: String,
    val action: (@Composable () -> Unit)? = null,
    val body: @Composable () -> Unit,
)

/** Arrow-key resize of a focused splitter: left/right on a ROW gap, up/down on a COLUMN gap; null for any other key. */
internal fun homeSplitterKeyDelta(axis: HomeSplitAxis, key: Key): Float? = when (axis) {
    HomeSplitAxis.ROW -> when (key) {
        Key.DirectionLeft -> -HOME_SPLITTER_KEY_STEP_DP
        Key.DirectionRight -> HOME_SPLITTER_KEY_STEP_DP
        else -> null
    }
    HomeSplitAxis.COLUMN -> when (key) {
        Key.DirectionUp -> -HOME_SPLITTER_KEY_STEP_DP
        Key.DirectionDown -> HOME_SPLITTER_KEY_STEP_DP
        else -> null
    }
}

/** Card height (dp) in the stacked narrow layout: 75% of the viewport, kept between 320 and 560. */
internal fun homeStackedCardHeight(viewportHeightDp: Float): Float =
    (viewportHeightDp * HOME_STACKED_HEIGHT_SHARE).coerceIn(HOME_STACKED_MIN_HEIGHT_DP, HOME_STACKED_MAX_HEIGHT_DP)

private class HomeDragCallbacks(
    val onStart: (HomeSectionId) -> Unit,
    val onMove: (rootPx: Offset) -> Unit,
    val onEnd: (HomeSectionId) -> Unit,
    val onCancel: () -> Unit,
)

private data class HomeGapKey(val index: Int)

/**
 * The New tab's body: top bar (title, tip, layout presets, hidden-section chips) over the section cards. [slots] must hold
 * a slot for every [HomeSectionId]. [onReclaimFocus] gives root keyboard focus back after any click or drag here (see
 * CLAUDE.md's clickable-steals-focus gotcha).
 */
@Composable
internal fun HomeTilingHost(
    state: AppState,
    slots: Map<HomeSectionId, HomeSectionSlot>,
    onReclaimFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        HomeTopBar(state, slots, onReclaimFocus)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            if (maxWidth.value < HOME_STACK_BELOW_DP) {
                HomeStackedSections(state, slots, maxHeight.value, onReclaimFocus)
            } else {
                HomeTiledSections(state, slots, maxWidth.value, maxHeight.value, onReclaimFocus)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun HomeTopBar(state: AppState, slots: Map<HomeSectionId, HomeSectionSlot>, onReclaimFocus: () -> Unit) {
    val tc = tc()
    val settings = state.settings
    var tipShownAgain by remember { mutableStateOf(false) }
    val tipVisible = !settings.homeLayoutTipDismissed || tipShownAgain
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppText("Start", fontSize = 20.sp, color = tc.tx)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (tipVisible) {
                    AppText(
                        HOME_LAYOUT_TIP,
                        color = tc.td,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).testTag("home-tip"),
                    )
                    HomeIconButton(Icons.Outlined.Close, "Dismiss tip", "Dismiss tip", "home-tip-dismiss") {
                        tipShownAgain = false
                        state.updateSettings { it.withHomeLayoutTipDismissed(true) }
                        onReclaimFocus()
                    }
                } else {
                    HomeIconButton(Icons.AutoMirrored.Outlined.HelpOutline, "Show layout tip", HOME_LAYOUT_TIP, "home-tip-help") {
                        tipShownAgain = true
                        onReclaimFocus()
                    }
                }
            }
            AppText("Layout", color = tc.td, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HomeLayoutPreset.entries.forEach { preset ->
                    HomePresetButton(preset, active = preset.isActiveIn(settings)) {
                        state.updateSettings { it.withHomeLayoutPreset(preset) }
                        onReclaimFocus()
                    }
                }
            }
        }
        if (settings.homeHiddenSections.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppText("Hidden:", color = tc.td, fontSize = 11.sp)
                settings.homeHiddenSections.forEach { id ->
                    slots[id]?.let { slot ->
                        HomeHiddenChip(id, slot) {
                            state.updateSettings { it.withHomeSectionShown(id) }
                            onReclaimFocus()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeIconButton(
    icon: ImageVector,
    contentDescription: String,
    tooltip: String,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tc = tc()
    TooltipArea(tooltip = { ToolbarTooltip(tooltip, maxLines = 4) }) {
        HoverBox(
            modifier = Modifier
                .size(22.dp)
                .clip(CORNER_MD)
                .testTag(tag)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            hoverEnabled = enabled,
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (enabled) tc.ts else tc.td.copy(alpha = 0.4f),
                modifier = Modifier.align(Alignment.Center).size(15.dp),
            )
        }
    }
}

@Composable
private fun HomePresetButton(preset: HomeLayoutPreset, active: Boolean, onClick: () -> Unit) {
    val tc = tc()
    val fill = if (active) tc.ac else tc.td.copy(alpha = 0.55f)
    TooltipArea(tooltip = { ToolbarTooltip(preset.label) }) {
        Box(
            Modifier
                .size(width = 34.dp, height = 24.dp)
                .clip(CORNER_MD)
                .background(if (active) tc.ac.copy(alpha = 0.12f) else Color.Transparent)
                .border(1.dp, if (active) tc.ac else tc.br, CORNER_MD)
                .testTag("home-preset-${preset.name}")
                .semantics {
                    contentDescription = preset.label
                    selected = active
                }
                .clickable(role = Role.Button, onClick = onClick)
                .padding(3.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val geometry = homeGeometry(preset.layout, size.width, size.height, 2.dp.toPx())
                geometry.cards.forEach { card ->
                    drawRoundRect(
                        color = fill,
                        topLeft = Offset(card.rect.x, card.rect.y),
                        size = Size(card.rect.w, card.rect.h),
                        cornerRadius = CornerRadius(1.5.dp.toPx()),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeHiddenChip(id: HomeSectionId, slot: HomeSectionSlot, onClick: () -> Unit) {
    val tc = tc()
    Row(
        Modifier
            .clip(CORNER_MD)
            .border(1.dp, tc.br, CORNER_MD)
            .testTag("home-hidden-chip-${id.key}")
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(slot.icon, contentDescription = null, tint = tc.ts, modifier = Modifier.size(13.dp))
        AppText(slot.title, color = tc.ts, fontSize = 11.sp)
        Icon(Icons.Outlined.Add, contentDescription = null, tint = tc.ts, modifier = Modifier.size(13.dp))
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Narrow: stacked
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun HomeStackedSections(
    state: AppState,
    slots: Map<HomeSectionId, HomeSectionSlot>,
    viewportHeightDp: Float,
    onReclaimFocus: () -> Unit,
) {
    val order = homeLeafSections(state.settings.homeLayout)
    val cardHeight = homeStackedCardHeight(viewportHeightDp).dp
    ScrollColumnWithBar(Modifier.fillMaxSize(), endGutter = 8.dp, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        order.forEach { id ->
            val slot = slots[id] ?: return@forEach
            key(id) {
                HomeSectionCard(
                    section = id,
                    slot = slot,
                    sizeLabel = null,
                    canHide = order.size > 1,
                    dragging = false,
                    drag = null,
                    onHide = {
                        state.updateSettings { it.withHomeSectionHidden(id) }
                        onReclaimFocus()
                    },
                    modifier = Modifier.fillMaxWidth().height(cardHeight),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Wide: tiled
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun HomeTiledSections(
    state: AppState,
    slots: Map<HomeSectionId, HomeSectionSlot>,
    widthDp: Float,
    heightDp: Float,
    onReclaimFocus: () -> Unit,
) {
    val density = LocalDensity.current.density
    // A splitter drag only edits this local draft; it is committed to settings once, on release (updateSettings autosaves synchronously).
    var draft by remember { mutableStateOf<HomeLayoutNode?>(null) }
    var dragged by remember { mutableStateOf<HomeSectionId?>(null) }
    var pointer by remember { mutableStateOf<Offset?>(null) }
    var hostRoot by remember { mutableStateOf(Offset.Zero) }
    val layout = draft ?: state.settings.homeLayout
    val geometry = remember(layout, widthDp, heightDp) { homeGeometry(layout, widthDp, heightDp, HOME_TILE_GAP_DP) }
    val target = dragged?.let { section ->
        pointer?.let { homeDropTargetAt(geometry, widthDp, heightDp, it.x, it.y, section) }
    }
    val dragCallbacks = HomeDragCallbacks(
        onStart = { section ->
            dragged = section
            pointer = null
        },
        onMove = { rootPx -> pointer = Offset((rootPx.x - hostRoot.x) / density, (rootPx.y - hostRoot.y) / density) },
        onEnd = { section ->
            // Hit-tested again from the latest pointer rather than the last composed `target`: the release can land before the next frame.
            val drop = pointer?.let { homeDropTargetAt(geometry, widthDp, heightDp, it.x, it.y, section) }
            if (drop != null) state.updateSettings { it.withHomeLayout(dropHomeSection(it.homeLayout, section, drop)) }
            dragged = null
            pointer = null
            onReclaimFocus()
        },
        onCancel = {
            dragged = null
            pointer = null
            onReclaimFocus()
        },
    )
    Box(Modifier.fillMaxSize().onGloballyPositioned { hostRoot = it.positionInRoot() }) {
        HomeTilingLayout(Modifier.fillMaxSize(), geometry) {
            // Fixed composition order (enum order, not tree order): moving a section only changes its rect, never its place in the
            // composition, so its remembered state and effects (the capture device poll, pairing dialogs, filters) survive drops.
            HomeSectionId.entries.forEach { id ->
                val rect = geometry.cardOf(id)
                val slot = slots[id]
                if (rect != null && slot != null) {
                    key(id) {
                        HomeSectionCard(
                            section = id,
                            slot = slot,
                            sizeLabel = if (rect.w >= HOME_SIZE_LABEL_MIN_CARD_DP) homeSizeLabel(rect, widthDp, heightDp) else null,
                            canHide = geometry.cards.size > 1,
                            dragging = dragged == id,
                            drag = dragCallbacks,
                            onHide = {
                                state.updateSettings { it.withHomeSectionHidden(id) }
                                onReclaimFocus()
                            },
                            modifier = Modifier.layoutId(id),
                        )
                    }
                }
            }
            geometry.gaps.forEachIndexed { index, gap ->
                key(index) {
                    HomeSplitter(
                        index = index,
                        gap = gap,
                        onDelta = { delta ->
                            draft = resizeHomeSplit(draft ?: state.settings.homeLayout, gap.path, gap.index, delta, gap.splitExtent)
                        },
                        onDragEnd = {
                            val committed = draft
                            if (committed != null) state.updateSettings { it.withHomeLayout(committed) }
                            draft = null
                            onReclaimFocus()
                        },
                        onKeyResize = { delta ->
                            state.updateSettings { it.withHomeLayout(resizeHomeSplit(it.homeLayout, gap.path, gap.index, delta, gap.splitExtent)) }
                        },
                        modifier = Modifier.layoutId(HomeGapKey(index)),
                    )
                }
            }
        }
        if (dragged != null) HomeDropOverlay(geometry, widthDp, heightDp, target)
    }
}

/** Lays every child out at its rect of [geometry]: cards by [HomeSectionId] layout id, splitters by [HomeGapKey]. */
@Composable
private fun HomeTilingLayout(modifier: Modifier, geometry: HomeGeometry, content: @Composable () -> Unit) {
    val density = LocalDensity.current.density
    val policy = remember(geometry, density) {
        MeasurePolicy { measurables, constraints ->
            val placed = measurables.mapNotNull { measurable ->
                val rect = homeRectOf(measurable, geometry) ?: return@mapNotNull null
                // Rounded per edge, so neighbouring cards and splitters never open a pixel seam.
                val left = (rect.x * density).roundToInt()
                val top = (rect.y * density).roundToInt()
                val width = ((rect.x + rect.w) * density).roundToInt() - left
                val height = ((rect.y + rect.h) * density).roundToInt() - top
                val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(width.coerceAtLeast(0), height.coerceAtLeast(0)))
                Triple(placeable, left, top)
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placed.forEach { (placeable, x, y) -> placeable.place(x, y) }
            }
        }
    }
    Layout(content = content, modifier = modifier, measurePolicy = policy)
}

private fun homeRectOf(measurable: Measurable, geometry: HomeGeometry): HomeRect? = when (val id = measurable.layoutId) {
    is HomeSectionId -> geometry.cardOf(id)
    is HomeGapKey -> geometry.gaps.getOrNull(id.index)?.rect
    else -> null
}

// ---------------------------------------------------------------------------------------------------------------
// Card
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun HomeSectionCard(
    section: HomeSectionId,
    slot: HomeSectionSlot,
    sizeLabel: String?,
    canHide: Boolean,
    dragging: Boolean,
    drag: HomeDragCallbacks?,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    Column(
        modifier
            .alpha(if (dragging) HOME_DRAGGED_CARD_ALPHA else 1f)
            .clip(HOME_CARD_SHAPE)
            .background(tc.p)
            .border(1.dp, tc.br, HOME_CARD_SHAPE)
            .testTag("home-card-${section.key}"),
    ) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (drag != null) HomeGrip(section, slot.title, drag)
            Icon(slot.icon, contentDescription = null, tint = tc.ts, modifier = Modifier.size(15.dp))
            AppText(
                slot.title,
                color = tc.tx,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            sizeLabel?.let { AppText(it, color = tc.td, fontSize = 10.sp, modifier = Modifier.testTag("home-size-${section.key}")) }
            slot.action?.invoke()
            HomeIconButton(Icons.Outlined.VisibilityOff, "Hide ${slot.title}", "Hide section", "home-hide-${section.key}", enabled = canHide, onClick = onHide)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
        Box(Modifier.weight(1f).fillMaxWidth()) { slot.body() }
    }
}

@Composable
private fun HomeGrip(section: HomeSectionId, title: String, drag: HomeDragCallbacks) {
    val tc = tc()
    val currentDrag by rememberUpdatedState(drag)
    var gripRoot by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .size(22.dp)
            .moveCursor()
            .onGloballyPositioned { gripRoot = it.positionInRoot() }
            .testTag("home-grip-${section.key}")
            .semantics { contentDescription = "Move $title" }
            .pointerInput(section) {
                detectMoveDragGestures(
                    onDragStart = { down ->
                        currentDrag.onStart(section)
                        currentDrag.onMove(gripRoot + down)
                    },
                    onDragEnd = { currentDrag.onEnd(section) },
                    onDragCancel = { currentDrag.onCancel() },
                    onDrag = { change, _ ->
                        change.consume()
                        currentDrag.onMove(gripRoot + change.position)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.DragIndicator, contentDescription = null, tint = tc.td, modifier = Modifier.size(16.dp))
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Splitter
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun HomeSplitter(
    index: Int,
    gap: HomeGap,
    onDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onKeyResize: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val currentKeyResize by rememberUpdatedState(onKeyResize)
    Box(
        modifier
            .testTag("home-splitter-$index")
            .semantics { contentDescription = "Resize sections" }
            .focusable(interactionSource = interaction)
            .onKeyEvent { event ->
                val delta = if (event.type == KeyEventType.KeyDown) homeSplitterKeyDelta(gap.axis, event.key) else null
                delta?.let { currentKeyResize(it) }
                delta != null
            },
        contentAlignment = Alignment.Center,
    ) {
        if (gap.axis == HomeSplitAxis.ROW) HDivider(onDragEnd = onDragEnd, onDelta = onDelta) else VDivider(onDragEnd = onDragEnd, onDelta = onDelta)
        if (focused) Box(Modifier.matchParentSizeBorder(tc.ac))
    }
}

private fun Modifier.matchParentSizeBorder(color: Color): Modifier = fillMaxSize().border(1.dp, color, RoundedCornerShape(2.dp))

// ---------------------------------------------------------------------------------------------------------------
// Drag overlay
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun HomeDropOverlay(geometry: HomeGeometry, widthDp: Float, heightDp: Float, target: HomeDropTarget?) {
    val tc = tc()
    val preview = target?.let { homeDropPreview(geometry, widthDp, heightDp, it) }
    Box(Modifier.fillMaxSize()) {
        HomeEdge.entries.forEach { edge ->
            HomePageStrip(edge, widthDp, heightDp, active = target == HomeDropTarget.OnPage(edge))
        }
        if (target != null && preview != null) {
            Box(
                Modifier
                    .offset(preview.x.dp, preview.y.dp)
                    .size(preview.w.dp, preview.h.dp)
                    .testTag("home-drop-preview")
                    .background(tc.ac.copy(alpha = HOME_PREVIEW_FILL_ALPHA), HOME_CARD_SHAPE)
                    .border(2.dp, tc.ac, HOME_CARD_SHAPE),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.background(tc.ac, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    AppText(homeDropLabel(target), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun HomePageStrip(edge: HomeEdge, widthDp: Float, heightDp: Float, active: Boolean) {
    val tc = tc()
    val band = HOME_PAGE_BAND_DP
    val vertical = edge.axis == HomeSplitAxis.ROW
    val x = if (edge == HomeEdge.RIGHT) widthDp - band else 0f
    val y = if (edge == HomeEdge.BOTTOM) heightDp - band else 0f
    val label = homeDropLabel(HomeDropTarget.OnPage(edge))
    Box(
        Modifier
            .offset(x.dp, y.dp)
            .size(if (vertical) band.dp else widthDp.dp, if (vertical) heightDp.dp else band.dp)
            .testTag("home-page-strip-${edge.name.lowercase()}")
            .drawBehind {
                val dash = HOME_STRIP_DASH_DP.dp.toPx()
                if (active) drawRect(tc.ac.copy(alpha = HOME_STRIP_FILL_ALPHA))
                drawRoundRect(
                    color = tc.ac.copy(alpha = if (active) 1f else HOME_STRIP_BORDER_ALPHA),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            label,
            color = tc.ac,
            fontSize = 10.sp,
            maxLines = 1,
            modifier = Modifier.wrapContentSize(unbounded = true).then(if (vertical) Modifier.rotate(HOME_VERTICAL_TEXT_DEGREES) else Modifier),
        )
    }
}
