@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.manor

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.indagium.model.TabStripPosition
import com.indagium.ui.holiday.HolidayTabShape

/** The outer edge of each tab curves like a stone crown; the edge against the content stays nearly square. */
internal object ManorTabShape : HolidayTabShape {
    override fun shape(position: TabStripPosition): Shape = when (position) {
        TabStripPosition.TOP -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 2.dp, bottomEnd = 2.dp)
        TabStripPosition.BOTTOM -> RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        TabStripPosition.LEFT -> RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp, topEnd = 2.dp, bottomEnd = 2.dp)
        TabStripPosition.RIGHT -> RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp, topEnd = 16.dp, bottomEnd = 16.dp)
    }
}

internal fun DrawScope.drawManorWeb(origin: Offset, reach: Float, color: Color, xSign: Float = 1f, ySign: Float = 1f, alpha: Float = 1f) {
    val x = origin.x
    val y = origin.y
    val end = reach * 0.9f
    val width = 1.dp.toPx()
    val diagonal = Path().apply {
        moveTo(x, y); lineTo(x + xSign * end, y)
        moveTo(x, y); lineTo(x, y + ySign * end)
        moveTo(x, y); lineTo(x + xSign * end * 0.72f, y + ySign * end * 0.72f)
    }
    drawPath(diagonal, color.copy(alpha = alpha), style = Stroke(width))
    for (fraction in listOf(0.25f, 0.5f, 0.75f)) {
        val d = end * fraction
        val web = Path().apply {
            moveTo(x + xSign * d, y)
            quadraticTo(x + xSign * d * 0.7f, y + ySign * d * 0.7f, x, y + ySign * d)
        }
        drawPath(web, color.copy(alpha = alpha), style = Stroke(width))
    }
}
