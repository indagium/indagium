// The monarch, the flags and the petal the Día de Muertos art is made of. The keyframes they share with other packs are in
// ui/holiday/HolidayKeyframes.kt; the numbers here are the canvas' keyframe stops and durations, not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.muertos

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.holiday.alternateEase
import com.indagium.ui.holiday.drawSvg
import com.indagium.ui.holiday.holidaySvg
import com.indagium.ui.holiday.parsePath
import com.indagium.ui.holiday.petalDrop
import com.indagium.ui.holiday.petalOpacity
import com.indagium.ui.holiday.petalPhase

/** `s-float`: how far through its up-and-down 0..1 a monarch is, for the rhythm of [float] (1: 4.2 s; 2: 5.4 s, -1.8 s; 3: 3.6 s, -.9 s). */
internal fun floatAmount(t: Float, float: Int): Float = when (float) {
    2 -> alternateEase(t, 5.4f / 2f, 1.8f)
    3 -> alternateEase(t, 3.6f / 2f, .9f)
    else -> alternateEase(t, 4.2f / 2f)
}

/** `dm-flap`: the wings' horizontal scale, 1 down to .58 and back over .75 s each way. */
internal fun wingFlap(t: Float): Float = 1f - .42f * alternateEase(t, .75f)

/** The petal's outline (a 4 x 10 teardrop from its tip), the same for the picture, the companion and the falling effect. */
internal val PETAL_PATH: Path by lazy { parsePath(PETAL) }

/** The monarch's body and antennae. */
internal val MONARCH_BODY_PATH: Path by lazy { parsePath(MONARCH_BODY) }
internal val MONARCH_ANTENNAE_PATH: Path by lazy { parsePath(MONARCH_ANTENNAE) }
internal val MONARCH_INK = Color(0xFF1d1016)

/** A flag's cut-out path, filled even-odd so the cut-outs show the bar behind. */
internal fun flagPath(kind: Int): Path = FLAG_PATHS[kind]

private val FLAG_PATHS: List<Path> by lazy {
    FLAG_SHAPES.map { parsePath(it).apply { fillType = PathFillType.EvenOdd } }
}

/** The wings, centred on the body: the SVG's viewBox starts at (-32, -23). */
internal val MONARCH_WINGS by lazy { holidaySvg("muertos", "monarch-wings") }

/**
 * One monarch at the origin of the current transform (the 60-unit wingspan is the design's), its wings flapped to [flap] across the body:
 * wings first, then the body and the antennae as round strokes on top.
 */
internal fun DrawScope.drawMonarch(flap: Float) {
    withTransform({
        scale(flap, 1f, Offset.Zero)
        translate(-32f, -23f)
    }) { drawSvg(MONARCH_WINGS) }
    drawPath(MONARCH_BODY_PATH, MONARCH_INK, style = Stroke(MONARCH_BODY_WIDTH, cap = StrokeCap.Round))
    drawPath(MONARCH_ANTENNAE_PATH, MONARCH_INK, style = Stroke(MONARCH_ANTENNAE_WIDTH, cap = StrokeCap.Round))
}

/** A falling petal of the picture or the companion at time [t] (a calm mid-fall pose when [still]). */
internal fun DrawScope.drawArtPetal(p: MuertosPetal, t: Float, still: Boolean) {
    val u = if (still) .3f else petalPhase(t, p.slow)
    withTransform({
        translate(0f, petalDrop(u))
        translate(p.x, p.y)
        rotate(p.rotation, Offset(0f, 5f))
    }) { drawPath(PETAL_PATH, p.color.copy(alpha = if (still) 1f else petalOpacity(u))) }
}

/**
 * An art monarch (picture or companion) floating at [t]. The canvas animates with `transform-box: fill-box`, so `transform-origin: center`
 * is the monarch's own centre: it bobs and tilts about its body, not about the middle of the picture.
 */
internal fun DrawScope.drawArtMonarch(m: MuertosMonarch, t: Float, still: Boolean) {
    val amount = if (still) .5f else floatAmount(t, m.float)
    withTransform({
        translate(0f, -10f * amount)
        rotate(-1.5f + 3f * amount, Offset(m.x, m.y))
        translate(m.x, m.y)
        rotate(m.rotation, Offset.Zero)
        scale(m.scale, m.scale, Offset.Zero)
    }) { drawMonarch(if (still) .79f else wingFlap(t)) }
}
