// Literal drawing geometry (stem size and lean) from the approved holiday canvas, not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.pumpkin

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.indagium.model.TabStripPosition
import com.indagium.ui.*
import com.indagium.ui.holiday.HolidayTabOrnament

private val STEM_WIDTH = 5.dp
private val STEM_HEIGHT = 8.dp
private val STEM_OVERLAP = 2.dp
private val STEM_CORNER = 2.dp
private const val STEM_LEAN_DEGREES = 14f

/** The pumpkin patch's tab ornament: the green stem on the active tab. */
internal object PumpkinStem : HolidayTabOrnament {
    override fun draw(scope: DrawScope, position: TabStripPosition, outerEdgePx: Float, dark: Boolean) {
        with(scope) { drawTabStem(position, outerEdgePx, pumpkinSceneColors(dark).stem) }
    }
}

/**
 * The active tab's green stem, centred on the tab's outer edge (which sits [outerEdgePx] in from the cell's side away from the content),
 * leaning [STEM_LEAN_DEGREES] and growing outward into the inset. Drawn over the tab, outside its clip.
 */
internal fun DrawScope.drawTabStem(position: TabStripPosition, outerEdgePx: Float, color: Color) {
    val (anchor, turn) = when (position) {
        TabStripPosition.TOP -> Offset(size.width / 2f, outerEdgePx) to 0f
        TabStripPosition.RIGHT -> Offset(size.width - outerEdgePx, size.height / 2f) to 90f
        TabStripPosition.BOTTOM -> Offset(size.width / 2f, size.height - outerEdgePx) to 180f
        TabStripPosition.LEFT -> Offset(outerEdgePx, size.height / 2f) to 270f
    }
    val w = STEM_WIDTH.toPx()
    val h = STEM_HEIGHT.toPx()
    val reach = h - STEM_OVERLAP.toPx()
    withTransform({
        translate(anchor.x, anchor.y)
        rotate(turn + STEM_LEAN_DEGREES, Offset.Zero)
    }) {
        drawRoundRect(color, Offset(-w / 2f, -reach), Size(w, h), CornerRadius(STEM_CORNER.toPx()))
    }
}
