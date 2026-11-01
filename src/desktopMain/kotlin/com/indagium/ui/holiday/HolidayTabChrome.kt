// Literal drawing geometry (rib spacing, stem size and lean) from the approved holiday canvas, not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.indagium.model.TabStripPosition
import com.indagium.ui.*

// The seasonal pack's tab: a rounded tab with ribs when idle and a panel card with an accent border when active (the theme may add an
// ornament on the active one, HolidayTabOrnament). It replaces only what is drawn (shape, fill, border); the user's TabStripStyle, the tab's width maths
// (tabChromeWidthDp) and every behaviour stay as they are. See HolidayThemes.kt for when a pack is active.

/** Radius of the two tab corners on the side away from the content. */
internal val HOLIDAY_TAB_RADIUS = HolidayCorners.DEFAULT_TAB_DP.dp

/** Extra space between the tab cell and the drawn tab on the side away from the content; the active tab's stem grows into it. */
internal val HOLIDAY_TAB_OUTER_INSET = 6.dp

private val ACTIVE_BORDER = 1.5.dp
private val RIB_SPACING = 18.dp
private val RIB_WIDTH = 1.dp
private const val IDLE_FILL_ALPHA = 0.85f

/** The pack's tab outline on a strip at [position]: rounded on the side away from the content, like the raised card. */
internal fun holidayTabShape(position: TabStripPosition, radius: Dp = HOLIDAY_TAB_RADIUS): RoundedCornerShape = when (position) {
    TabStripPosition.TOP -> RoundedCornerShape(topStart = radius, topEnd = radius)
    TabStripPosition.BOTTOM -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
    TabStripPosition.LEFT -> RoundedCornerShape(topStart = radius, bottomStart = radius)
    TabStripPosition.RIGHT -> RoundedCornerShape(topEnd = radius, bottomEnd = radius)
}

/** [base] plus [HOLIDAY_TAB_OUTER_INSET] on the side away from the content only (above a top strip's tabs, beside a side strip's). */
internal fun holidayTabInset(base: PaddingValues, position: TabStripPosition): PaddingValues {
    val extra = HOLIDAY_TAB_OUTER_INSET
    val left = base.calculateLeftPadding(LayoutDirection.Ltr)
    val right = base.calculateRightPadding(LayoutDirection.Ltr)
    val top = base.calculateTopPadding()
    val bottom = base.calculateBottomPadding()
    return when (position) {
        TabStripPosition.TOP -> PaddingValues(left, top + extra, right, bottom)
        TabStripPosition.BOTTOM -> PaddingValues(left, top, right, bottom + extra)
        TabStripPosition.LEFT -> PaddingValues(left + extra, top, right, bottom)
        TabStripPosition.RIGHT -> PaddingValues(left, top, right + extra, bottom)
    }
}

/** The inset of [inset] on the side away from the content: where the drawn tab's outer edge sits inside its cell. */
internal fun holidayOuterEdgeInset(inset: PaddingValues, position: TabStripPosition): Dp = when (position) {
    TabStripPosition.TOP -> inset.calculateTopPadding()
    TabStripPosition.BOTTOM -> inset.calculateBottomPadding()
    TabStripPosition.LEFT -> inset.calculateLeftPadding(LayoutDirection.Ltr)
    TabStripPosition.RIGHT -> inset.calculateRightPadding(LayoutDirection.Ltr)
}

/**
 * The pack's tab fill: an idle tab is the chrome's tab colour with faint vertical ribs (lit by [hovered]); the active one is the panel
 * colour with an accent border. [tint] is a group member's wash, over the fill.
 */
internal fun Modifier.holidayTabChrome(
    active: Boolean,
    hovered: Boolean,
    tc: ThemeColors,
    chrome: HolidayChromeColors,
    position: TabStripPosition,
    tint: Color? = null,
    radius: Dp = HOLIDAY_TAB_RADIUS,
    shape: Shape = holidayTabShape(position, radius),
    texture: HolidayTabTexture = HolidayTabTexture.RIBS,
): Modifier {
    val fill = background(if (active) tc.p else if (texture == HolidayTabTexture.STONE) chrome.tabBg else chrome.tabBg.copy(alpha = IDLE_FILL_ALPHA), shape)
        .drawBehind {
            when (texture) {
                HolidayTabTexture.RIBS -> if (!active) drawRibs(shape, chrome.rib)
                HolidayTabTexture.STONE -> drawStoneSpeckles(shape)
                HolidayTabTexture.PLAIN -> Unit
            }
        }
    return fill
        .then(if (tint == null) Modifier else Modifier.background(tint, shape))
        .then(if (!active && hovered) Modifier.background(tc.hv, shape) else Modifier)
        .then(if (active) Modifier.border(ACTIVE_BORDER, tc.ac, shape) else if (texture != HolidayTabTexture.RIBS) Modifier.border(1.dp, tc.br, shape) else Modifier)
}

// The manor reference uses three faint, fixed stone marks instead of the pumpkin's repeated ribs.
private fun DrawScope.drawStoneSpeckles(shape: Shape) {
    val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawStoneSpeckles)) }
    clipPath(outline) {
        drawCircle(Color.Black.copy(alpha = .09f), 1.5.dp.toPx(), Offset(size.width * .22f, size.height * .72f))
        drawCircle(Color.Black.copy(alpha = .07f), 1.5.dp.toPx(), Offset(size.width * .63f, size.height * .40f))
        drawCircle(Color.Black.copy(alpha = .06f), 1.dp.toPx(), Offset(size.width * .86f, size.height * .80f))
    }
}

// Vertical hairlines every RIB_SPACING, cut to the tab's rounded outline so none pokes past a corner.
private fun DrawScope.drawRibs(shape: Shape, color: Color) {
    val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawRibs)) }
    val spacing = RIB_SPACING.toPx()
    val width = RIB_WIDTH.toPx()
    clipPath(outline) {
        var x = spacing
        while (x < size.width) {
            drawRect(color, Offset(x - width, 0f), Size(width, size.height))
            x += spacing
        }
    }
}
