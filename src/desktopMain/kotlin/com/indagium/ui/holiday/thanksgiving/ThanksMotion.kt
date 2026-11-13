// The harvest shapes (leaf, goose) and their keyframes. The numbers are the canvas' keyframe stops and durations, not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.thanksgiving

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.holiday.alternateEase
import com.indagium.ui.holiday.cycle
import com.indagium.ui.holiday.holidaySvg
import com.indagium.ui.holiday.parsePath
import com.indagium.ui.holiday.petalDrop
import com.indagium.ui.holiday.petalOpacity
import com.indagium.ui.holiday.petalPhase

internal fun thanksSvg(name: String) = holidaySvg("thanksgiving", name)

private val MAPLE_PATH: Path by lazy { parsePath(MAPLE) }
private val OAK_PATH: Path by lazy { parsePath(OAK) }
private val LEAF_VEIN_PATH: Path by lazy { parsePath(LEAF_VEIN) }
private val GOOSE_PATH: Path by lazy { parsePath(GOOSE) }
private val LEAF_VEIN_COLOR = Color.Black.copy(alpha = .22f)
private const val LEAF_VEIN_WIDTH = 1.2f

/** The units of the 20 x 20 leaf shape, so a size in dp is `size / LEAF_SIZE`. */
internal const val LEAF_SIZE = 20f

/** The leaf at the origin of the current transform: [kind] 0 is the maple, 1 the oak; the stem is down, the vein drawn over it at [alpha]. */
internal fun DrawScope.drawLeaf(kind: Int, color: Color, alpha: Float = 1f) {
    drawPath(if (kind == 0) MAPLE_PATH else OAK_PATH, color, alpha)
    drawPath(LEAF_VEIN_PATH, LEAF_VEIN_COLOR, alpha, Stroke(LEAF_VEIN_WIDTH))
}

/** A falling leaf of the picture or the companion at time [t] (a calm mid-fall pose when [still]). */
internal fun DrawScope.drawArtLeaf(l: ThanksLeaf, t: Float, still: Boolean) {
    val u = if (still) .3f else petalPhase(t, l.slow)
    withTransform({
        translate(0f, petalDrop(u))
        translate(l.x, l.y)
        rotate(l.rotation, Offset.Zero)
        scale(l.scale, l.scale, Offset.Zero)
    }) { drawLeaf(l.kind, l.color, if (still) 1f else petalOpacity(u)) }
}

/** `tg-flapy`: a goose's vertical scale, 1 down to .45 and back over .45 s each way. */
internal fun gooseFlap(t: Float): Float = 1f - .55f * alternateEase(t, .45f)

/** A goose at the origin of the current transform (18 units wide), its wings flapped to [flap] about its bottom centre, stroked in [color]. */
internal fun DrawScope.drawGoose(flap: Float, color: Color) {
    withTransform({ scale(1f, flap, Offset.Zero) }) {
        drawPath(GOOSE_PATH, color, style = Stroke(GOOSE_STROKE_WIDTH, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** `tg-geese`: how far the picture's V has crossed the window, translateX -120 to 230 over 16 s, linear. */
internal fun geeseTravel(t: Float): Float = -120f + 350f * cycle(t, 16f)
