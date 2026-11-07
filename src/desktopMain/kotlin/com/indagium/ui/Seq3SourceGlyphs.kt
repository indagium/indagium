package com.indagium.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** The small picture in front of a source suggestion, drawn with the canvas: no image assets. */
internal enum class Seq3SourceGlyph {
    /** A call: solid line, filled head. */
    ARROW_SOLID,

    /** A return: dashed line, open head. */
    ARROW_DASHED,

    /** An async message: solid line, open head. */
    ARROW_ASYNC,

    /** A block opener: a frame with its label tab. */
    BLOCK,

    /** `else`, `and`, `option`: a frame cut by a dashed divider. */
    DIVIDER,

    /** `end`: the closing corner of a frame. */
    END,

    NOTE,

    /** An activation bar on a lifeline. */
    ACTIVATION,

    PARTICIPANT,

    ACTOR,

    TITLE,

    /** A lifeline created by an arrow. */
    CREATE,

    /** A lifeline ended by a cross. */
    DESTROY,
}

@Composable
internal fun Seq3SourceGlyphIcon(glyph: Seq3SourceGlyph, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(SEQ3_GLYPH_WIDTH, SEQ3_GLYPH_HEIGHT)) { drawSeq3SourceGlyph(glyph, color) }
}

private val SEQ3_GLYPH_WIDTH = 22.dp
private val SEQ3_GLYPH_HEIGHT = 16.dp

private fun DrawScope.drawSeq3SourceGlyph(glyph: Seq3SourceGlyph, color: Color) {
    val w = size.width
    val h = size.height
    val line = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val dash = Stroke(
        width = 1.3.dp.toPx(), cap = StrokeCap.Butt,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5.dp.toPx(), 2.dp.toPx())),
    )
    val mid = h / 2
    val edge = 2.dp.toPx()

    /** An open arrow head with its tip at the given point; [direction] is 1 for a head pointing right, -1 for left. */
    fun openHead(tipX: Float, tipY: Float, length: Float, direction: Float = 1f) {
        drawLine(color, Offset(tipX - direction * length, tipY - length), Offset(tipX, tipY), line.width, StrokeCap.Round)
        drawLine(color, Offset(tipX - direction * length, tipY + length), Offset(tipX, tipY), line.width, StrokeCap.Round)
    }

    fun lifeline(x: Float, fromY: Float) {
        drawLine(color, Offset(x, fromY), Offset(x, h - edge), dash.width, pathEffect = dash.pathEffect)
    }

    when (glyph) {
        Seq3SourceGlyph.ARROW_SOLID -> {
            drawLine(color, Offset(edge, mid), Offset(w - 4.dp.toPx(), mid), line.width, StrokeCap.Round)
            val head = Path().apply {
                moveTo(w - edge, mid)
                lineTo(w - 6.5.dp.toPx(), mid - 3.dp.toPx())
                lineTo(w - 6.5.dp.toPx(), mid + 3.dp.toPx())
                close()
            }
            drawPath(head, color, style = Fill)
        }
        Seq3SourceGlyph.ARROW_DASHED -> {
            drawLine(color, Offset(w - edge, mid), Offset(edge + 1.dp.toPx(), mid), dash.width, pathEffect = dash.pathEffect)
            openHead(edge + 1.dp.toPx(), mid, 3.dp.toPx(), direction = -1f)
        }
        Seq3SourceGlyph.ARROW_ASYNC -> {
            drawLine(color, Offset(edge, mid), Offset(w - edge, mid), line.width, StrokeCap.Round)
            openHead(w - edge, mid, 3.dp.toPx())
        }
        Seq3SourceGlyph.BLOCK -> {
            drawRect(color, Offset(edge, edge), Size(w - 2 * edge, h - 2 * edge), style = line)
            drawRect(color, Offset(edge, edge), Size(8.dp.toPx(), 4.5.dp.toPx()), style = Fill)
        }
        Seq3SourceGlyph.DIVIDER -> {
            drawRect(color, Offset(edge, edge), Size(w - 2 * edge, h - 2 * edge), style = line)
            drawLine(color, Offset(edge, mid + 1.dp.toPx()), Offset(w - edge, mid + 1.dp.toPx()), dash.width, pathEffect = dash.pathEffect)
        }
        Seq3SourceGlyph.END -> {
            val corner = Path().apply {
                moveTo(edge, edge)
                lineTo(edge, h - edge)
                lineTo(w - edge, h - edge)
                lineTo(w - edge, h - edge - 3.dp.toPx())
            }
            drawPath(corner, color, style = line)
        }
        Seq3SourceGlyph.NOTE -> {
            val fold = 4.dp.toPx()
            val outline = Path().apply {
                moveTo(edge, edge)
                lineTo(w - edge - fold, edge)
                lineTo(w - edge, edge + fold)
                lineTo(w - edge, h - edge)
                lineTo(edge, h - edge)
                close()
            }
            drawPath(outline, color, style = line)
            drawLine(color, Offset(w - edge - fold, edge), Offset(w - edge - fold, edge + fold), line.width)
            drawLine(color, Offset(w - edge - fold, edge + fold), Offset(w - edge, edge + fold), line.width)
            drawLine(color, Offset(edge + 3.dp.toPx(), mid + 1.dp.toPx()), Offset(w - edge - 3.dp.toPx(), mid + 1.dp.toPx()), line.width)
        }
        Seq3SourceGlyph.ACTIVATION -> {
            lifeline(w / 2, edge)
            drawRect(color, Offset(w / 2 - 2.5.dp.toPx(), 4.dp.toPx()), Size(5.dp.toPx(), h - 8.dp.toPx()), style = Fill)
        }
        Seq3SourceGlyph.PARTICIPANT -> {
            drawRect(color, Offset(w / 2 - 6.dp.toPx(), edge), Size(12.dp.toPx(), 6.dp.toPx()), style = line)
            lifeline(w / 2, edge + 6.dp.toPx())
        }
        Seq3SourceGlyph.ACTOR -> {
            val cx = w / 2
            drawCircle(color, radius = 2.2.dp.toPx(), center = Offset(cx, edge + 2.2.dp.toPx()), style = line)
            drawLine(color, Offset(cx, edge + 4.4.dp.toPx()), Offset(cx, 11.dp.toPx()), line.width, StrokeCap.Round)
            drawLine(color, Offset(cx - 4.dp.toPx(), 7.dp.toPx()), Offset(cx + 4.dp.toPx(), 7.dp.toPx()), line.width, StrokeCap.Round)
            drawLine(color, Offset(cx, 11.dp.toPx()), Offset(cx - 3.dp.toPx(), h - edge), line.width, StrokeCap.Round)
            drawLine(color, Offset(cx, 11.dp.toPx()), Offset(cx + 3.dp.toPx(), h - edge), line.width, StrokeCap.Round)
        }
        Seq3SourceGlyph.TITLE -> {
            drawLine(color, Offset(edge + 2.dp.toPx(), 4.dp.toPx()), Offset(w - edge - 2.dp.toPx(), 4.dp.toPx()), 2.2.dp.toPx(), StrokeCap.Round)
            drawLine(color, Offset(edge + 4.dp.toPx(), 9.dp.toPx()), Offset(w - edge - 4.dp.toPx(), 9.dp.toPx()), line.width, StrokeCap.Round)
            drawLine(color, Offset(edge + 6.dp.toPx(), 12.5.dp.toPx()), Offset(w - edge - 6.dp.toPx(), 12.5.dp.toPx()), line.width, StrokeCap.Round)
        }
        Seq3SourceGlyph.CREATE -> {
            drawLine(color, Offset(edge, mid), Offset(w - 10.dp.toPx(), mid), dash.width, pathEffect = dash.pathEffect)
            openHead(w - 10.dp.toPx(), mid, 2.5.dp.toPx())
            drawRect(color, Offset(w - 9.dp.toPx(), mid - 3.5.dp.toPx()), Size(7.dp.toPx(), 7.dp.toPx()), style = line)
        }
        Seq3SourceGlyph.DESTROY -> {
            lifeline(w / 2, edge)
            val arm = 4.dp.toPx()
            val crossY = h - edge - arm
            drawLine(color, Offset(w / 2 - arm, crossY - arm), Offset(w / 2 + arm, crossY + arm), line.width, StrokeCap.Round)
            drawLine(color, Offset(w / 2 - arm, crossY + arm), Offset(w / 2 + arm, crossY - arm), line.width, StrokeCap.Round)
        }
    }
}
