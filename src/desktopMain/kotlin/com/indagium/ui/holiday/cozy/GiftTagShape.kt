// The tag's point and hole are the design canvas' sizes (a 14 dp point, a 5 dp hole 12 dp in), not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.cozy

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.indagium.model.TabStripPosition
import com.indagium.ui.holiday.HolidayTabShape

/**
 * Cozy Christmas' tab: a gift tag, square on the right with its left end cut to a point (the design's `clip-path: polygon(14px 0, 100% 0, 100% 100%,
 * 14px 100%, 0 50%)`), and a hole punched through it [HOLE_CENTER_DP] in from the point. The hole is part of the outline, so the fill, the
 * border and the clip all stop at it and the bar shows through, like a real tag's.
 */
internal object GiftTagShape : HolidayTabShape {
    /** How far the tag's left end is cut: the corners sit this far in from the point. */
    const val POINT_DP = 14f

    /** The hole: its centre's distance from the tag's left edge, and its radius. */
    const val HOLE_CENTER_DP = 14.5f
    const val HOLE_RADIUS_DP = 2.5f

    /** The tag's outline in [size] pixels at [density] pixels per dp. */
    fun path(size: Size, density: Float): Path {
        val point = (POINT_DP * density).coerceAtMost(size.width / 2f)
        val tag = Path().apply {
            moveTo(point, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(point, size.height)
            lineTo(0f, size.height / 2f)
            close()
        }
        val centre = Offset(HOLE_CENTER_DP * density, size.height / 2f)
        val r = HOLE_RADIUS_DP * density
        // A tab too narrow to hold the hole keeps the plain tag.
        if (centre.x + r > size.width) return tag
        val hole = Path().apply { addOval(Rect(centre.x - r, centre.y - r, centre.x + r, centre.y + r)) }
        return Path.combine(PathOperation.Difference, tag, hole)
    }

    private val TAG: Shape = object : Shape {
        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
            Outline.Generic(path(size, density.density))
    }

    override fun shape(position: TabStripPosition): Shape = TAG
}
