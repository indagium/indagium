// SVG path data, gradient stops and coordinates are the approved design canvas' illustration values, not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import com.indagium.ui.*

// The shared pieces of the seasonal illustrations (any theme's picture, companion or effect art): parsed paths, gradients and the helpers that
// paint an SVG-style gradient. The illustrations use their own fixed colours (they are pictures, not theme roles). Gradients are built once
// in the picture's own coordinates; only an alpha changes per frame.

internal typealias Stops = Array<Pair<Float, Color>>

internal fun hex(rgb: Long, alpha: Float = 1f): Color = Color(0xFF000000 or rgb).copy(alpha = alpha)

internal fun shadowStops(alpha: Float): Stops = arrayOf(0f to Color.Black.copy(alpha = alpha), 1f to Color.Black.copy(alpha = 0f))

internal fun washStops(rgb: Long, alpha: Float): Stops = arrayOf(0f to hex(rgb, alpha), 1f to hex(rgb, 0f))

// Unit radial gradients (centre at the origin, radius 1): painted through a translate + scale so one brush serves any ellipse.
internal fun unitRadial(stops: Stops): Brush = Brush.radialGradient(colorStops = stops, center = Offset.Zero, radius = 1f)

internal fun parsePath(d: String): Path = PathParser().parsePathString(d).toPath()

internal fun ovalPath(cx: Float, cy: Float, rx: Float, ry: Float): Path = Path().apply { addOval(Rect(cx - rx, cy - ry, cx + rx, cy + ry)) }

/** A parsed path with its bounds, for SVG gradients that are relative to the shape's bounding box. */
internal class ArtPath(d: String) {
    val path: Path = parsePath(d)
    val bounds: Rect = path.getBounds()
}

/** An ellipse filled with a vertical (body) or horizontal (mid) gradient across its own extent. */
internal class OvalFill(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val brush: Brush) {
    val topLeft = Offset(cx - rx, cy - ry)
    val size = Size(2f * rx, 2f * ry)
}

internal fun DrawScope.drawOvalFill(o: OvalFill, alpha: Float = 1f) = drawOval(o.brush, o.topLeft, o.size, alpha)

/** A diagonal (top-left to bottom-right) gradient over [bounds], as the SVG's x1,y1 = 0,0 / x2,y2 = 1,1 objectBoundingBox default. */
internal fun diagonalBrush(bounds: Rect, stops: Stops): Brush = Brush.linearGradient(colorStops = stops, start = bounds.topLeft, end = bounds.bottomRight)

/** A circular gradient in a path's box, for strokes (a stroke can't be clipped like a fill): centred at the fractions, radius a fraction of the width. */
internal fun boxRadialBrush(bounds: Rect, stops: Stops, cx: Float, cy: Float, r: Float): Brush =
    Brush.radialGradient(colorStops = stops, center = Offset(bounds.left + cx * bounds.width, bounds.top + cy * bounds.height), radius = r * bounds.width)

// Paints a unit gradient as an ellipse centred at (cx, cy) with radii (rx, ry), cut to [clip]: the SVG radial gradient of a shape's box.
internal fun DrawScope.fillElliptical(clip: Path, cx: Float, cy: Float, rx: Float, ry: Float, unit: Brush, alpha: Float = 1f) {
    clipPath(clip) {
        withTransform({
            translate(cx, cy)
            scale(rx, ry, Offset.Zero)
        }) { drawRect(unit, Offset(-ELLIPSE_REACH, -ELLIPSE_REACH), Size(2f * ELLIPSE_REACH, 2f * ELLIPSE_REACH), alpha) }
    }
}

private const val ELLIPSE_REACH = 400f

/** A unit radial gradient drawn as the whole ellipse (halo, shadow, firefly): its box is its extent, so no clip is needed. */
internal fun DrawScope.drawUnitEllipse(unit: Brush, cx: Float, cy: Float, rx: Float, ry: Float, alpha: Float = 1f) {
    withTransform({
        translate(cx, cy)
        scale(rx, ry, Offset.Zero)
    }) { drawCircle(unit, radius = 1f, center = Offset.Zero, alpha = alpha) }
}

/** An ellipse filled with a vertical gradient across its own extent. */
internal fun verticalOval(cx: Float, cy: Float, rx: Float, ry: Float, stops: Stops) =
    OvalFill(cx, cy, rx, ry, Brush.verticalGradient(colorStops = stops, startY = cy - ry, endY = cy + ry))

/** An ellipse filled with a horizontal gradient across its own extent. */
internal fun horizontalOval(cx: Float, cy: Float, rx: Float, ry: Float, stops: Stops) =
    OvalFill(cx, cy, rx, ry, Brush.horizontalGradient(colorStops = stops, startX = cx - rx, endX = cx + rx))
