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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.LogTab
import com.indagium.model.TabStripPosition
import com.indagium.model.TabStripStyle

// How each tab strip style (Settings > Appearance > Tabs) draws one tab. The shell keeps one structure for every style (a stripe
// overlay and a Row of [live dot] [badge] [label] [close]) and only the metrics, the chrome behind the Row and the label's type
// change, so a behaviour (click, right-click menu, tooltip, drag, close) is the same in every style. CURRENT is the long-standing
// look and produces exactly the modifiers the shell had before styles existed. A strip on another window edge (TabAppearance.position)
// turns the same drawing around: the accent mark, a raised card's open side and the source stripe move to the matching side of the tab.

private val RAISED_RADIUS = 8.dp
private val SEGMENT_RADIUS = 6.dp
private val STRIPE_HEIGHT = 3.dp
private val ACCENT_UNDERLINE = 2.dp
private val SEGMENT_DOT = 5.dp
private val CLOSE_SLOT = 24.dp

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
        else -> TabLabelStyle(12.sp, MONO, FontWeight.Normal, if (lit) tc.tx else tc.ts)
    }
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

private fun Modifier.raisedCard(tc: ThemeColors, position: TabStripPosition): Modifier {
    val shape = raisedShape(position)
    return background(tc.bg, shape).drawBehind {
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

// A [thickness] mark along the side of the tab that faces the content: the bottom of a top strip's tab, the top of a bottom strip's,
// the right of a left strip's, the left of a right strip's.
private fun DrawScope.contentSideMark(position: TabStripPosition, thickness: Float, color: Color) {
    when (position) {
        TabStripPosition.TOP -> drawRect(color, Offset(0f, size.height - thickness), Size(size.width, thickness))
        TabStripPosition.BOTTOM -> drawRect(color, Offset.Zero, Size(size.width, thickness))
        TabStripPosition.LEFT -> drawRect(color, Offset(size.width - thickness, 0f), Size(thickness, size.height))
        TabStripPosition.RIGHT -> drawRect(color, Offset.Zero, Size(thickness, size.height))
    }
}

// The chrome behind one tab: its fill, outline and the accent mark of the active tab.
private fun Modifier.tabChrome(
    style: TabStripStyle,
    isActive: Boolean,
    hovered: Boolean,
    dragging: Boolean,
    tc: ThemeColors,
    position: TabStripPosition,
): Modifier {
    val accent = tc.ac
    val lit = hovered || dragging
    return when (style) {
        TabStripStyle.RAISED ->
            if (isActive) raisedCard(tc, position) else background(if (lit) tc.hv else Color.Transparent, RoundedCornerShape(SEGMENT_RADIUS))

        TabStripStyle.SEGMENTED -> {
            val shape = RoundedCornerShape(SEGMENT_RADIUS)
            when {
                isActive -> background(tc.p, shape).border(0.5.dp, tc.br, shape)
                lit -> background(tc.hv, shape)
                else -> this
            }
        }

        TabStripStyle.QUIET -> drawBehind { contentSideMark(position, ACCENT_UNDERLINE.toPx(), if (isActive) accent else tc.br) }

        else -> background(if (isActive) tc.bg else if (lit) tc.p else tc.p2)
            .border(BorderStroke(1.dp, tc.br.copy(alpha = 0.95f)))
            .drawBehind { if (isActive) contentSideMark(position, ACCENT_UNDERLINE.toPx(), accent) }
    }
}

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
    val lit = isActive || dragging
    val labelStyle = tabLabelStyle(style, isActive, lit, tc)
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(tabStripHeightDp(style).dp)
                .padding(metrics.inset)
                .testTag("tab-$pointerKey")
                .tabChrome(style, isActive, hov, dragging, tc, position)
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
                .clickable(onClick = onClick).padding(start = metrics.padStart, end = metrics.padEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            leading?.invoke()
            if (style == TabStripStyle.SEGMENTED && isActive) {
                Box(Modifier.testTag("tab-dot-$pointerKey").size(SEGMENT_DOT).background(tc.ac, CircleShape))
            }
            if (appearance.showBadge) TabBadge(tabKindBadge(appearance.kind), "tab-badge-$pointerKey")
            TooltipArea(tooltip = { TabTooltipBody(tooltip) }, modifier = Modifier.weight(1f)) {
                TabLabelText(label, appearance.secondLine, labelStyle)
            }
            if (showClose) {
                if (appearance.closeAlways || lit || hov) {
                    CloseButton(onClick = onClose, modifier = Modifier.testTag("tab-close-$pointerKey"))
                } else {
                    // Keeps the label's room stable: the button appears on hover without the text reflowing.
                    Spacer(Modifier.size(CLOSE_SLOT))
                }
            }
        }
        if (appearance.showStripe) {
            TabSourceStripe(
                pointerKey = pointerKey,
                position = position,
                metrics = metrics,
                color = tabSourceColor(appearance.kind, tc),
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
