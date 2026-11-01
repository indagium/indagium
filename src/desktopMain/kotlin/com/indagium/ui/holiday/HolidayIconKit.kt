package com.indagium.ui.holiday

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.indagium.ui.*

// The seasonal themes' toolbar pictures: ten multicolour 32 x 32 glyphs, each with the real name of the button and a one-line mnemonic for
// its tooltip ("Filters · the pumpkin"). A theme supplies one [IconSpec] per button (this file builds them into vectors) in the style of the
// design canvas' SVGs: a part with no colour of its own is drawn in the OUTLINE colour (the SVG's currentColor), so [holidayIconMap] takes the theme's
// text colour and the line work reads on both a dark and a light toolbar. Nothing here is drawn unless a pack is active and "Fun icons" is on.

/** The toolbar buttons the pack redraws. */
internal enum class ToolbarIconId { NEW, FILTERS, NOTES, AI, VIDEO, COMPARE, CASES, OPEN, LAYOUT, SETTINGS }

/** One pack icon: the picture, the real button name and the mnemonic ("the pumpkin"). */
internal data class HolidayIcon(val vector: ImageVector, val name: String, val mnemonic: String) {
    val tooltip: String get() = "$name · $mnemonic"
}

// Colour sentinels for a part (real colours are opaque, so 0 and 1 never collide with one).
internal const val NONE = 0L
internal const val OUTLINE = 1L

internal class IconPart(val d: String, val fill: Long, val stroke: Long, val width: Float?)

internal class IconSpec(val name: String, val mnemonic: String, val strokeWidth: Float, val parts: List<IconPart>)

internal fun part(d: String, fill: Long = NONE, stroke: Long = OUTLINE, width: Float? = null) = IconPart(d, fill, stroke, width)

internal fun circlePath(cx: Float, cy: Float, r: Float): String = ellipsePath(cx, cy, r, r)

internal fun ellipsePath(cx: Float, cy: Float, rx: Float, ry: Float): String =
    "M${cx - rx} ${cy}A$rx $ry 0 1 0 ${cx + rx} ${cy}A$rx $ry 0 1 0 ${cx - rx} ${cy}Z"

internal fun roundRectPath(x: Float, y: Float, w: Float, h: Float, r: Float): String =
    "M${x + r} ${y}H${x + w - r}A$r $r 0 0 1 ${x + w} ${y + r}V${y + h - r}A$r $r 0 0 1 ${x + w - r} ${y + h}" +
        "H${x + r}A$r $r 0 0 1 $x ${y + h - r}V${y + r}A$r $r 0 0 1 ${x + r} ${y}Z"

/** The icons of a theme: one [IconSpec] per [ToolbarIconId] from [specOf], line work in [outline]. */
internal fun holidayIconMap(outline: Color, specOf: (ToolbarIconId) -> IconSpec): Map<ToolbarIconId, HolidayIcon> =
    ToolbarIconId.entries.associateWith { id ->
        val spec = specOf(id)
        HolidayIcon(buildIcon("holiday-${id.name.lowercase()}", spec, outline), spec.name, spec.mnemonic)
    }

private fun buildIcon(name: String, spec: IconSpec, outline: Color): ImageVector {
    val builder = ImageVector.Builder(name, 32.dp, 32.dp, 32f, 32f)

    fun brush(c: Long) = if (c == NONE) null else SolidColor(if (c == OUTLINE) outline else Color(c))
    spec.parts.forEach { p ->
        builder.addPath(
            pathData = addPathNodes(p.d),
            fill = brush(p.fill),
            stroke = brush(p.stroke),
            strokeLineWidth = p.width ?: spec.strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}

/**
 * The pack icon for [id] under the current look, or null when no pack is active or "Fun icons" is off, so a call site falls back to its
 * ordinary icon. Rebuilt only when the pack or the text colour changes.
 */
@Composable
internal fun holidayToolbarIcon(id: ToolbarIconId): HolidayIcon? {
    val holiday = LocalHoliday.current ?: return null
    if (!holiday.effects.funIcons) return null
    val outline = tc().tx
    val icons = holiday.theme.icons ?: return null
    val set = remember(holiday.pack, outline) { icons.icons(outline) }
    return set.getValue(id)
}

@Composable
internal fun holidayFindIcon(): HolidayIcon? {
    val holiday = LocalHoliday.current ?: return null
    if (!holiday.effects.funIcons) return null
    val outline = tc().tx
    return remember(holiday.pack, outline) { holiday.theme.icons?.findIcon(outline) }
}
