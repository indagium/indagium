@file:Suppress("MagicNumber")
package com.indagium.ui.holiday.candy

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.indagium.ui.holiday.HolidaySceneLayout
import com.indagium.ui.holiday.HolidayScenePainter

private data class Bunting(override val slot: Rect) : HolidaySceneLayout {
    override val elementBounds: List<Rect> = listOf(slot)
}

/** The reference's multicolored bunting, fitted to the space reserved in the tab strip. */
internal object CandyTabScene : HolidayScenePainter {
    override fun layout(slot: Rect, vertical: Boolean): HolidaySceneLayout = Bunting(slot)
    override fun draw(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean) = with(scope) {
        val s = layout.slot
        val colors = if (dark) listOf(Color(0xFFFFD23F), Color(0xFFFF8A2A), Color(0xFFA855F7), Color(0xFFFFF2DF))
            else listOf(Color(0xFFF5B800), Color(0xFFFF8A2A), Color(0xFF7C3AED), Color(0xFF3FB950))
        val wire = Path().apply { moveTo(s.left, s.top + s.height * .22f); quadraticTo(s.center.x, s.top + s.height * .65f, s.right, s.top + s.height * .22f) }
        drawPath(wire, (if (dark) Color(0xFFC8B8E5) else Color(0xFF67567C)).copy(alpha = .5f), style = Stroke(1f))
        repeat(5) { i ->
            val u = (i + .5f) / 5f
            val x = s.left + s.width * u
            val y = s.top + s.height * (.22f + .215f * (1f - (2f * u - 1f) * (2f * u - 1f)))
            val w = minOf(s.width / 16f, s.height * .18f)
            val h = minOf(s.height * .34f, s.bottom - y)
            val flag = Path().apply { moveTo(x - w, y); lineTo(x + w, y); lineTo(x, y + h); close() }
            drawPath(flag, colors[i % colors.size])
        }
    }
}
