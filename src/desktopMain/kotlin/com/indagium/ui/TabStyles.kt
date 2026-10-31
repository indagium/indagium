@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.LogTab
import com.indagium.model.TabCloseButtonPosition
import com.indagium.model.TabStripPosition
import com.indagium.model.TabStripStyle

// How each tab strip style (Settings > Appearance > Tabs) draws one tab. The shell keeps one structure for every style (a stripe
// overlay and a Row of [close?] [live dot] [badge] [label] [close?]) and only the metrics, the chrome behind the Row and the label's type
// change, so a behaviour (click, right-click menu, tooltip, drag, close) is the same in every style. CURRENT is the long-standing
// look and produces exactly the modifiers the shell had before styles existed. A strip on another window edge (TabAppearance.position)
// turns the same drawing around: the accent mark, a raised card's open side and the source stripe move to the matching side of the tab.

private val RAISED_RADIUS = 8.dp
private val SEGMENT_RADIUS = 6.dp
private val STRIPE_HEIGHT = 3.dp
private val ACCENT_UNDERLINE = 2.dp
private val SEGMENT_DOT = 5.dp
private val CLOSE_SLOT = 24.dp
private const val PILL_CORNER_PERCENT = 50
private const val QUARTER_CIRCLE_CUBIC = 0.55228475f

private data class TabMetrics(
    val padStart: Dp,
    val padEnd: Dp,
    val gap: Dp,
    // The space between the tab cell and the drawn tab (the raised card's gap above, the segmented item's margin).
    val inset: PaddingValues,
    val stripeInsetHorizontal: Dp,
    val stripeTop: Dp,
)

// The raised card's gap sits on the side away from the content: above a top strip's tabs, below a bottom strip's, beside a side strip's.
private fun raisedInset(position: TabStripPosition): PaddingValues = when (position) {
    TabStripPosition.TOP -> PaddingValues(start = 1.dp, end = 1.dp, top = 4.dp)
    TabStripPosition.BOTTOM -> PaddingValues(start = 1.dp, end = 1.dp, bottom = 4.dp)
    TabStripPosition.LEFT -> PaddingValues(start = 4.dp, top = 1.dp, bottom = 1.dp)
    TabStripPosition.RIGHT -> PaddingValues(end = 4.dp, top = 1.dp, bottom = 1.dp)
}

private fun tabMetrics(style: TabStripStyle, position: TabStripPosition): TabMetrics = when (style) {
    TabStripStyle.RAISED -> TabMetrics(12.dp, 6.dp, 7.dp, raisedInset(position), 8.dp, 4.dp)
    TabStripStyle.SEGMENTED -> TabMetrics(10.dp, 4.dp, 6.dp, PaddingValues(horizontal = 2.dp, vertical = 3.dp), 8.dp, 3.dp)
    TabStripStyle.OUTLINED -> TabMetrics(12.dp, 6.dp, 7.dp, PaddingValues(horizontal = 2.dp, vertical = 2.dp), 0.dp, 0.dp)
    TabStripStyle.PILL -> TabMetrics(12.dp, 6.dp, 7.dp, PaddingValues(horizontal = 2.dp, vertical = 3.dp), 0.dp, 0.dp)
    TabStripStyle.COMPACT, TabStripStyle.QUIET -> TabMetrics(10.dp, 4.dp, 6.dp, PaddingValues(), 0.dp, 0.dp)
    else -> TabMetrics(12.dp, 6.dp, 7.dp, PaddingValues(), 0.dp, 0.dp)
}

private data class TabLabelStyle(val size: TextUnit, val family: FontFamily, val weight: FontWeight, val color: Color)

@Composable
private fun tabLabelStyle(style: TabStripStyle, active: Boolean, lit: Boolean, tc: ThemeColors): TabLabelStyle {
    val weight = if (active) FontWeight.SemiBold else FontWeight.Normal
    return when (style) {
        TabStripStyle.COMPACT -> TabLabelStyle(11.sp, MONO, FontWeight.Normal, if (lit) tc.tx else tc.ts)
        TabStripStyle.SEGMENTED -> TabLabelStyle(12.sp, UI, weight, if (lit) tc.tx else tc.ts)
        TabStripStyle.QUIET -> TabLabelStyle(12.sp, UI, weight, if (active) tc.ac else if (lit) tc.tx else tc.ts)
        TabStripStyle.OUTLINED -> TabLabelStyle(12.sp, UI, weight, if (lit) tc.tx else tc.ts)
        TabStripStyle.PILL -> TabLabelStyle(12.sp, UI, weight, if (active) tc.ac else if (lit) tc.tx else tc.ts)
        else -> TabLabelStyle(12.sp, MONO, FontWeight.Normal, if (lit) tc.tx else tc.ts)
    }
}

/** The label font of an idle (not active) tab in [style], for measuring a label before it is drawn; [uiFont] is the interface family. */
internal fun tabLabelMeasureStyle(style: TabStripStyle, uiFont: FontFamily): TextStyle = when (style) {
    TabStripStyle.COMPACT -> TextStyle(fontSize = 11.sp, fontFamily = MONO, fontWeight = FontWeight.Normal)
    TabStripStyle.SEGMENTED, TabStripStyle.QUIET, TabStripStyle.OUTLINED, TabStripStyle.PILL ->
        TextStyle(fontSize = 12.sp, fontFamily = uiFont, fontWeight = FontWeight.Normal)
    else -> TextStyle(fontSize = 12.sp, fontFamily = MONO, fontWeight = FontWeight.Normal)
}

/** The width in dp a tab of [style] adds around its content: the start and end padding plus one gap (between a leading slot and the label). */
internal fun tabChromeWidthDp(style: TabStripStyle): Float {
    val metrics = tabMetrics(style, TabStripPosition.TOP)
    return metrics.padStart.value + metrics.padEnd.value + metrics.gap.value
}

/** The strip's own background: the app background for QUIET, the panel tone for every other style. */
internal fun tabStripBackground(style: TabStripStyle, tc: ThemeColors): Color = if (style == TabStripStyle.QUIET) tc.bg else tc.p2

// The raised style's active card: filled like the content beside it and outlined on every side but the one facing the content, so it
// opens into the content.
private fun raisedShape(position: TabStripPosition): RoundedCornerShape = when (position) {
    TabStripPosition.TOP -> RoundedCornerShape(topStart = RAISED_RADIUS, topEnd = RAISED_RADIUS)
    TabStripPosition.BOTTOM -> RoundedCornerShape(bottomStart = RAISED_RADIUS, bottomEnd = RAISED_RADIUS)
    TabStripPosition.LEFT -> RoundedCornerShape(topStart = RAISED_RADIUS, bottomStart = RAISED_RADIUS)
    TabStripPosition.RIGHT -> RoundedCornerShape(topEnd = RAISED_RADIUS, bottomEnd = RAISED_RADIUS)
}

/**
 * The outline of one tab in [style] on a strip at [position]: the raised card's open-sided rounded shape, the segmented or outlined card,
 * the pill, and a plain rectangle for the other styles. Everything that belongs to the tab follows it: its fill and hover highlight,
 * plus the press and focus highlights that [Modifier.clickable] draws, cut to this shape instead of lighting up the whole cell.
 */
internal fun tabShape(style: TabStripStyle, position: TabStripPosition): Shape = when (style) {
    TabStripStyle.RAISED -> raisedShape(position)
    TabStripStyle.SEGMENTED -> RoundedCornerShape(SEGMENT_RADIUS)
    TabStripStyle.OUTLINED -> RoundedCornerShape(SEGMENT_RADIUS)
    TabStripStyle.PILL -> RoundedCornerShape(PILL_CORNER_PERCENT)
    else -> RectangleShape
}

private fun tabCloseButtonShape(style: TabStripStyle, position: TabStripPosition): Shape = when (style) {
    TabStripStyle.RAISED, TabStripStyle.SEGMENTED, TabStripStyle.OUTLINED, TabStripStyle.PILL -> tabShape(style, position)
    else -> CORNER_MD
}

private fun Modifier.raisedCard(tc: ThemeColors, position: TabStripPosition, tint: Color?): Modifier {
    val shape = raisedShape(position)
    return background(tc.bg, shape).groupTint(tint, shape).drawBehind {
        val inset = 0.5.dp.toPx()
        val overshoot = RAISED_RADIUS.toPx() * 2
        // The outline is a rounded rect that runs past the open side, which the clip then cuts off.
        val (topLeft, outline) = when (position) {
            TabStripPosition.TOP -> Offset(inset, inset) to Size(size.width - 2 * inset, size.height + overshoot)
            TabStripPosition.BOTTOM -> Offset(inset, -overshoot) to Size(size.width - 2 * inset, size.height + overshoot - inset)
            TabStripPosition.LEFT -> Offset(inset, inset) to Size(size.width + overshoot, size.height - 2 * inset)
            TabStripPosition.RIGHT -> Offset(-overshoot, inset) to Size(size.width + overshoot - inset, size.height - 2 * inset)
        }
        clipRect {
            drawRoundRect(
                color = tc.br,
                topLeft = topLeft,
                size = outline,
                cornerRadius = CornerRadius(RAISED_RADIUS.toPx()),
                style = Stroke(1.dp.toPx()),
            )
        }
    }
}

private fun opposite(position: TabStripPosition): TabStripPosition = when (position) {
    TabStripPosition.TOP -> TabStripPosition.BOTTOM
    TabStripPosition.BOTTOM -> TabStripPosition.TOP
    TabStripPosition.LEFT -> TabStripPosition.RIGHT
    TabStripPosition.RIGHT -> TabStripPosition.LEFT
}

/** Radius of the tab's two corners along [edge]. Raised cards round only their outer edge; pills scale with their short side. */
private fun DrawScope.tabEdgeRadius(style: TabStripStyle, position: TabStripPosition, edge: TabStripPosition): Float = when (style) {
    TabStripStyle.RAISED -> if (edge == position) RAISED_RADIUS.toPx() else 0f
    TabStripStyle.SEGMENTED, TabStripStyle.OUTLINED -> SEGMENT_RADIUS.toPx()
    TabStripStyle.PILL -> minOf(size.width, size.height) / 2f
    else -> 0f
}

/** Stroke a colored band around one tab edge, turning through the same corner arcs as its rounded outer shape. */
private fun DrawScope.drawEdgeContour(edge: TabStripPosition, thickness: Float, radius: Float, color: Color) {
    if (radius <= 0f) {
        when (edge) {
            TabStripPosition.TOP -> drawRect(color, Offset(0f, 0f), Size(size.width, thickness))
            TabStripPosition.BOTTOM -> drawRect(color, Offset(0f, size.height - thickness), Size(size.width, thickness))
            TabStripPosition.LEFT -> drawRect(color, Offset(0f, 0f), Size(thickness, size.height))
            TabStripPosition.RIGHT -> drawRect(color, Offset(size.width - thickness, 0f), Size(thickness, size.height))
        }
        return
    }

    val half = thickness / 2f
    val r = radius.coerceAtMost(minOf(size.width, size.height) / 2f)
    val innerRadius = (r - half).coerceAtLeast(0f)
    val k = QUARTER_CIRCLE_CUBIC * innerRadius
    val path = Path()
    when (edge) {
        TabStripPosition.TOP -> {
            path.moveTo(half, r)
            path.cubicTo(half, r - k, r - k, half, r, half)
            path.lineTo(size.width - r, half)
            path.cubicTo(size.width - r + k, half, size.width - half, r - k, size.width - half, r)
        }
        TabStripPosition.BOTTOM -> {
            path.moveTo(half, size.height - r)
            path.cubicTo(half, size.height - r + k, r - k, size.height - half, r, size.height - half)
            path.lineTo(size.width - r, size.height - half)
            path.cubicTo(size.width - r + k, size.height - half, size.width - half, size.height - r + k, size.width - half, size.height - r)
        }
        TabStripPosition.LEFT -> {
            path.moveTo(r, half)
            path.cubicTo(r - k, half, half, r - k, half, r)
            path.lineTo(half, size.height - r)
            path.cubicTo(half, size.height - r + k, r - k, size.height - half, r, size.height - half)
        }
        TabStripPosition.RIGHT -> {
            path.moveTo(size.width - r, half)
            path.cubicTo(size.width - r + k, half, size.width - half, r - k, size.width - half, r)
            path.lineTo(size.width - half, size.height - r)
            path.cubicTo(size.width - half, size.height - r + k, size.width - r + k, size.height - half, size.width - r, size.height - half)
        }
    }
    drawPath(path, color, style = Stroke(width = thickness, cap = StrokeCap.Butt))
}

// A mark along the side of the tab facing content. Rounded styles bend the line around the two matching corner arcs; rectangular
// styles retain the original straight drawRect geometry pixel for pixel.
private fun DrawScope.contentSideMark(style: TabStripStyle, position: TabStripPosition, thickness: Float, color: Color) {
    val edge = opposite(position)
    drawEdgeContour(edge, thickness, tabEdgeRadius(style, position, edge), color)
}

// A group member's tint: the group colour (already at its alpha) over the tab's own fill, inside its shape. It sits between the fill and
// everything drawn after it (outline, accent mark, label, source stripe), so those keep their pixels.
private fun Modifier.groupTint(tint: Color?, shape: Shape): Modifier = if (tint == null) this else background(tint, shape)

// The chrome behind one tab: its fill, [tint] (a group member's), outline and the accent mark of the active tab.
private fun Modifier.tabChrome(
    style: TabStripStyle,
    isActive: Boolean,
    hovered: Boolean,
    dragging: Boolean,
    tc: ThemeColors,
    position: TabStripPosition,
    tint: Color? = null,
): Modifier {
    val accent = tc.ac
    val lit = hovered || dragging
    return when (style) {
        TabStripStyle.RAISED -> raisedTabChrome(tc, position, isActive, lit, tint)
        TabStripStyle.SEGMENTED -> segmentedTabChrome(style, tc, position, isActive, lit, tint)
        TabStripStyle.OUTLINED -> outlinedTabChrome(style, tc, position, isActive, lit, accent, tint)
        TabStripStyle.PILL -> pillTabChrome(style, tc, position, isActive, lit, accent, tint)
        TabStripStyle.QUIET -> quietTabChrome(style, position, isActive, accent, tc.br, tint)
        else -> classicTabChrome(style, tc, position, isActive, lit, accent, tint)
    }
}

// The hovered raised tab keeps the card's open-sided shape (open toward the content).
private fun Modifier.raisedTabChrome(tc: ThemeColors, position: TabStripPosition, isActive: Boolean, lit: Boolean, tint: Color?): Modifier =
    if (isActive) {
        raisedCard(tc, position, tint)
    } else {
        val shape = tabShape(TabStripStyle.RAISED, position)
        background(if (lit) tc.hv else Color.Transparent, shape).groupTint(tint, shape)
    }

private fun Modifier.segmentedTabChrome(
    style: TabStripStyle,
    tc: ThemeColors,
    position: TabStripPosition,
    isActive: Boolean,
    lit: Boolean,
    tint: Color?,
): Modifier {
    val shape = tabShape(style, position)
    return when {
        isActive -> background(tc.p, shape).groupTint(tint, shape).border(0.5.dp, tc.br, shape)
        lit -> background(tc.hv, shape).groupTint(tint, shape)
        else -> groupTint(tint, shape)
    }
}

private fun Modifier.outlinedTabChrome(
    style: TabStripStyle,
    tc: ThemeColors,
    position: TabStripPosition,
    isActive: Boolean,
    lit: Boolean,
    accent: Color,
    tint: Color?,
): Modifier {
    val shape = tabShape(style, position)
    val border = if (isActive) accent else tc.br
    return background(if (isActive) tc.p else if (lit) tc.hv else Color.Transparent, shape)
        .groupTint(tint, shape)
        .border(BorderStroke(1.dp, border), shape)
        .drawBehind { if (isActive) contentSideMark(style, position, ACCENT_UNDERLINE.toPx(), accent) }
}

private fun Modifier.pillTabChrome(
    style: TabStripStyle,
    tc: ThemeColors,
    position: TabStripPosition,
    isActive: Boolean,
    lit: Boolean,
    accent: Color,
    tint: Color?,
): Modifier {
    val shape = tabShape(style, position)
    val backgroundColor = if (isActive) accent.copy(alpha = 0.14f) else if (lit) tc.hv else tc.p
    val border = if (isActive) accent else tc.br.copy(alpha = 0.7f)
    return background(backgroundColor, shape)
        .groupTint(tint, shape)
        .border(BorderStroke(1.dp, border), shape)
        .drawBehind { if (isActive) contentSideMark(style, position, ACCENT_UNDERLINE.toPx(), accent) }
}

private fun Modifier.quietTabChrome(
    style: TabStripStyle,
    position: TabStripPosition,
    isActive: Boolean,
    accent: Color,
    border: Color,
    tint: Color?,
): Modifier = groupTint(tint, tabShape(style, position))
    .drawBehind { contentSideMark(style, position, ACCENT_UNDERLINE.toPx(), if (isActive) accent else border) }

private fun Modifier.classicTabChrome(
    style: TabStripStyle,
    tc: ThemeColors,
    position: TabStripPosition,
    isActive: Boolean,
    lit: Boolean,
    accent: Color,
    tint: Color?,
): Modifier = background(if (isActive) tc.bg else if (lit) tc.p else tc.p2)
    .groupTint(tint, tabShape(style, position))
    .border(BorderStroke(1.dp, tc.br.copy(alpha = 0.95f)))
    .drawBehind { if (isActive) contentSideMark(style, position, ACCENT_UNDERLINE.toPx(), accent) }

@Composable
private fun TabBadge(text: String, tag: String) {
    val tc = tc()
    Box(Modifier.testTag(tag).background(tc.hv, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        AppText(text, color = tc.ts, fontSize = 9.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TabTooltipBody(tooltip: String) {
    val tc = tc()
    val tooltipScroll = rememberScrollState()
    Box(
        Modifier
            .widthIn(max = 760.dp)
            .heightIn(max = 180.dp)
            .background(tc.p2, RoundedCornerShape(4.dp))
            .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
            .verticalScroll(tooltipScroll)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        AppText(
            tooltip,
            color = tc.tx,
            fontSize = 11.sp,
            fontFamily = MONO,
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Clip,
        )
    }
}

/** The log-tab visual recipe, generalised so [TabOverflowRow] can render a diagram tab through the
 *  exact same shell instead of an ad-hoc pill.  [pointerKey] replaces `tab.id` as the
 *  `pointerInput` restart key (cross-tab/cross-workspace id collisions are the same hazard either
 *  way — see the ID-collision note in CLAUDE.md); [tooltip] is the plain-text tooltip body
 *  (log tabs show the source path, diagram tabs show title/range/source); [leading] is an
 *  optional composable slot before the label (log tabs use it for the live-tailing `●`).
 *  [appearance] picks the strip style and details; the default draws the long-standing tab.
 */
@Composable
internal fun TabShell(
    pointerKey: Any,
    label: String,
    tooltip: String,
    isActive: Boolean,
    showClose: Boolean,
    dragging: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onCtxMenu: (Float, Float) -> Unit = { _, _ -> },
    appearance: TabAppearance = TabAppearance.Default,
) {
    val tc = tc()
    val density = LocalDensity.current.density
    var hov by remember { mutableStateOf(false) }
    var rowRoot by remember { mutableStateOf(Offset.Zero) }
    val style = appearance.style
    val position = appearance.position
    val metrics = tabMetrics(style, position)
    val shape = tabShape(style, position)
    val drawSourceContour = appearance.showStripe && shape != RectangleShape
    val sourceColor = if (appearance.showStripe) tabSourceColor(appearance.kind, tc) else Color.Transparent
    val lit = isActive || dragging
    val labelStyle = tabLabelStyle(style, isActive, lit, tc)
    val groupTint = appearance.groupColor?.takeIf { appearance.groupTintAlpha > 0f }?.copy(alpha = appearance.groupTintAlpha)
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().height(tabStripHeightDp(style).dp)
                    .padding(metrics.inset)
                    .testTag("tab-$pointerKey")
                    .tabChrome(style, isActive, hov, dragging, tc, position, groupTint)
                    // Cuts what is drawn after the chrome (the hover / press / focus highlights of the click below) to the tab's own shape. A plain
                    // rectangle needs no clip, so the default style adds no layer and draws exactly what it always did.
                    .then(if (shape == RectangleShape) Modifier else Modifier.clip(shape))
                    .onGloballyPositioned { rowRoot = it.positionInRoot() }
                    .onPointerEvent(PointerEventType.Enter) { hov = true }
                    .onPointerEvent(PointerEventType.Exit) { hov = false }
                    .pointerInput(pointerKey) {
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                if (ev.type == PointerEventType.Press && ev.buttons.isSecondaryPressed) {
                                    val ch = ev.changes.firstOrNull() ?: continue
                                    ch.consume()
                                    onCtxMenu((rowRoot.x + ch.position.x) / density, (rowRoot.y + ch.position.y) / density)
                                }
                            }
                        }
                    }
                    .clickable(onClick = onClick)
                    .then(
                        if (drawSourceContour) {
                            Modifier.testTag("tab-stripe-$pointerKey")
                                .drawWithContent {
                                    drawContent()
                                    drawEdgeContour(position, STRIPE_HEIGHT.toPx(), tabEdgeRadius(style, position, position), sourceColor)
                                }
                        } else {
                            Modifier
                        },
                    )
                    .padding(start = metrics.padStart, end = metrics.padEnd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metrics.gap),
            ) {
                if (appearance.closeButtonPosition == TabCloseButtonPosition.LEFT) {
                    TabCloseSlot(
                        pointerKey = pointerKey,
                        showClose = showClose,
                        visible = appearance.closeAlways || lit || hov,
                        style = style,
                        position = position,
                        onClose = onClose,
                    )
                }
                leading?.invoke()
                if (style == TabStripStyle.SEGMENTED && isActive) {
                    Box(Modifier.testTag("tab-dot-$pointerKey").size(SEGMENT_DOT).background(tc.ac, CircleShape))
                }
                if (appearance.showBadge) TabBadge(tabKindBadge(appearance.kind), "tab-badge-$pointerKey")
                TooltipArea(tooltip = { TabTooltipBody(tooltip) }, modifier = Modifier.weight(1f).testTag("tab-label-$pointerKey")) {
                    TabLabelText(label, appearance.secondLine, labelStyle)
                }
                if (appearance.closeButtonPosition == TabCloseButtonPosition.RIGHT) {
                    TabCloseSlot(
                        pointerKey = pointerKey,
                        showClose = showClose,
                        visible = appearance.closeAlways || lit || hov,
                        style = style,
                        position = position,
                        onClose = onClose,
                    )
                }
            }
        }
        if (appearance.showStripe && shape == RectangleShape) {
            TabSourceStripe(
                pointerKey = pointerKey,
                position = position,
                metrics = metrics,
                color = sourceColor,
                modifier = Modifier.align(
                    when (position) {
                        TabStripPosition.TOP, TabStripPosition.LEFT -> Alignment.TopStart
                        TabStripPosition.BOTTOM -> Alignment.BottomStart
                        TabStripPosition.RIGHT -> Alignment.TopEnd
                    },
                ),
            )
        }
    }
}

/** The close hit target and its hover-only placeholder share one slot so the label never shifts. */
@Composable
private fun TabCloseSlot(
    pointerKey: Any,
    showClose: Boolean,
    visible: Boolean,
    style: TabStripStyle,
    position: TabStripPosition,
    onClose: () -> Unit,
) {
    if (!showClose) return
    if (visible) {
        CloseButton(
            onClick = onClose,
            modifier = Modifier.testTag("tab-close-$pointerKey"),
            shape = tabCloseButtonShape(style, position),
        )
    } else {
        // Keeps the label's room stable: the button appears on hover without the text reflowing.
        Spacer(Modifier.size(CLOSE_SLOT).testTag("tab-close-slot-$pointerKey"))
    }
}

// The source-colour stripe along the tab's outer side (away from the content): across the top of a top strip's tab, along the left of a
// left strip's, and so on.
@Composable
private fun TabSourceStripe(pointerKey: Any, position: TabStripPosition, metrics: TabMetrics, color: Color, modifier: Modifier) {
    val inset = metrics.stripeInsetHorizontal
    val offset = metrics.stripeTop
    val tag = Modifier.testTag("tab-stripe-$pointerKey")
    when (position) {
        TabStripPosition.TOP -> Box(
            modifier.fillMaxWidth().padding(top = offset, start = inset, end = inset).height(STRIPE_HEIGHT).then(tag)
                .background(color, RoundedCornerShape(bottomStart = 1.dp, bottomEnd = 1.dp)),
        )
        TabStripPosition.BOTTOM -> Box(
            modifier.fillMaxWidth().padding(bottom = offset, start = inset, end = inset).height(STRIPE_HEIGHT).then(tag)
                .background(color, RoundedCornerShape(topStart = 1.dp, topEnd = 1.dp)),
        )
        TabStripPosition.LEFT -> Box(
            modifier.fillMaxHeight().padding(start = offset, top = inset, bottom = inset).width(STRIPE_HEIGHT).then(tag)
                .background(color, RoundedCornerShape(topEnd = 1.dp, bottomEnd = 1.dp)),
        )
        TabStripPosition.RIGHT -> Box(
            modifier.fillMaxHeight().padding(end = offset, top = inset, bottom = inset).width(STRIPE_HEIGHT).then(tag)
                .background(color, RoundedCornerShape(topStart = 1.dp, bottomStart = 1.dp)),
        )
    }
}

// One line of label, or (TWO_LINE) the label over a dim second line.
@Composable
private fun TabLabelText(label: String, secondLine: String?, style: TabLabelStyle) {
    val tc = tc()
    val primary: @Composable () -> Unit = {
        AppText(
            label,
            color = style.color,
            fontSize = style.size,
            fontFamily = style.family,
            fontWeight = style.weight,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
    }
    if (secondLine == null) {
        primary()
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            primary()
            AppText(
                secondLine,
                color = tc.td,
                fontSize = 10.sp,
                fontFamily = UI,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun TabItem(
    tab: LogTab, isActive: Boolean, showClose: Boolean,
    label: String = tab.filename,
    dragging: Boolean = false, onClick: () -> Unit, onClose: () -> Unit,
    onCtxMenu: (Float, Float) -> Unit = { _, _ -> },
    appearance: TabAppearance = TabAppearance.Default,
) {
    val tc = tc()
    TabShell(
        pointerKey = tab.id,
        label = label,
        tooltip = tab.sourcePath ?: tab.filename,
        isActive = isActive,
        showClose = showClose,
        dragging = dragging,
        // Live tailing (utils/FileTailer.kt) is toggled from the tab's right-click context menu,
        // not a clickable button here — this is purely an indicator, plain and non-interactive.
        leading = if (tab.tailing) {
            {
                TooltipArea(
                    tooltip = {
                        Box(
                            Modifier.background(tc.p2, RoundedCornerShape(4.dp))
                                .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            AppText("Live tailing — watching file for new lines", color = tc.tx, fontSize = 11.sp, fontFamily = MONO)
                        }
                    },
                ) {
                    AppText("●", color = DANGER_RED, fontSize = 10.sp, modifier = Modifier.testTag("tab-tailing-${tab.id}"))
                }
            }
        } else {
            null
        },
        onClick = onClick,
        onClose = onClose,
        onCtxMenu = onCtxMenu,
        appearance = appearance,
    )
}
