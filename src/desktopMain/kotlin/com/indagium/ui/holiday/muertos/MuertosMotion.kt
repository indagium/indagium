// The design's CSS keyframes as pure functions of time. The numbers are the canvas' keyframe stops and durations, not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.muertos

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.skiaCanvas
import com.indagium.ui.holiday.parsePath
import org.jetbrains.skia.Data
import org.jetbrains.skia.svg.SVGDOM
import kotlin.math.floor

// CSS `ease-in-out` is cubic-bezier(.42, 0, .58, 1): solve x(s) = u by bisection, then read y(s).
private const val EASE_X1 = 0.42f
private const val EASE_X2 = 0.58f
private const val EASE_STEPS = 24

private fun bezier(s: Float, a: Float, b: Float): Float {
    val r = 1f - s
    return 3f * r * r * s * a + 3f * r * s * s * b + s * s * s
}

/** CSS `ease-in-out` of a progress in 0..1. */
internal fun easeInOut(u: Float): Float {
    if (u <= 0f) return 0f
    if (u >= 1f) return 1f
    var lo = 0f
    var hi = 1f
    repeat(EASE_STEPS) {
        val mid = (lo + hi) / 2f
        if (bezier(mid, EASE_X1, EASE_X2) < u) lo = mid else hi = mid
    }
    return bezier((lo + hi) / 2f, 0f, 1f)
}

/** Position in a looping animation of [period] seconds that started [delay] seconds ago at t = 0 (a CSS negative delay is a positive [delay] here). */
internal fun cycle(t: Float, period: Float, delay: Float = 0f): Float {
    val raw = (t + delay) / period
    return raw - floor(raw)
}

/** A looping `alternate` animation's progress 0..1..0 over two [period]s, with CSS `ease-in-out` on each way. */
internal fun alternateEase(t: Float, period: Float, delay: Float = 0f): Float {
    val p = cycle(t, 2f * period, delay) * 2f
    return easeInOut(if (p <= 1f) p else 2f - p)
}

/** Keyframe values at [stops] (0..1, ascending); each segment eased with `ease-in-out` when [eased], linear otherwise. */
internal fun keyframes(u: Float, stops: FloatArray, values: FloatArray, eased: Boolean): Float {
    if (u <= stops.first()) return values.first()
    for (i in 1 until stops.size) {
        if (u <= stops[i]) {
            val raw = (u - stops[i - 1]) / (stops[i] - stops[i - 1])
            return values[i - 1] + (values[i] - values[i - 1]) * (if (eased) easeInOut(raw) else raw)
        }
    }
    return values.last()
}

private val FLICKER_STOPS = floatArrayOf(0f, .07f, .11f, .38f, .42f, .68f, .73f, 1f)
private val FLICKER_VALUES = floatArrayOf(1f, .78f, .96f, .86f, 1f, .9f, 1f, 1f)

/** `s-flicker`: the candle glow's opacity (2.6 s, linear). */
internal fun glowFlicker(t: Float): Float = keyframes(cycle(t, 2.6f), FLICKER_STOPS, FLICKER_VALUES, eased = false)

private val FLAME_STOPS = floatArrayOf(0f, .25f, .5f, .75f, 1f)
private val FLAME_SCALE = floatArrayOf(1f, 1.14f, .9f, 1.07f, 1f)
private val FLAME_TURN = floatArrayOf(-2f, 2f, -1f, 1.5f, -2f)

/** `s-flame`: a flame's vertical scale and turn in degrees at [t], for the rhythm of [variant] (1: 1.1 s; 2: 1.3 s, -.4 s; 3: .9 s, -.7 s). */
internal fun flameShape(t: Float, variant: Int): Pair<Float, Float> {
    val u = when (variant) {
        2 -> cycle(t, 1.3f, .4f)
        3 -> cycle(t, .9f, .7f)
        else -> cycle(t, 1.1f)
    }
    return keyframes(u, FLAME_STOPS, FLAME_SCALE, eased = true) to keyframes(u, FLAME_STOPS, FLAME_TURN, eased = true)
}

/** The smoke's position in its 3.4 s rise (`late` runs half a cycle behind), 0..1. */
internal fun steamPhase(t: Float, late: Boolean): Float = cycle(t, 3.4f, if (late) 1.7f else 0f)

/** `s-steam`: the smoke's opacity at [u]: up to .8 by 35 %, down to nothing by the end, each leg eased. */
internal fun steamOpacity(u: Float): Float =
    if (u < .35f) .8f * easeInOut(u / .35f) else .8f * (1f - easeInOut((u - .35f) / .65f))

/** `s-snow`: a falling petal's position in its cycle (7 s, or 8.5 s from -3 s when [slow]). */
internal fun petalPhase(t: Float, slow: Boolean): Float = if (slow) cycle(t, 8.5f, 3f) else cycle(t, 7f)

/** `s-snow` opacity: fades in over the first tenth and out over the last. */
internal fun petalOpacity(u: Float): Float = if (u < .1f) u / .1f else if (u > .9f) (1f - u) / .1f else 1f

/** `s-float`: how far through its up-and-down 0..1 a monarch is, for the rhythm of [float] (1: 4.2 s; 2: 5.4 s, -1.8 s; 3: 3.6 s, -.9 s). */
internal fun floatAmount(t: Float, float: Int): Float = when (float) {
    2 -> alternateEase(t, 5.4f / 2f, 1.8f)
    3 -> alternateEase(t, 3.6f / 2f, .9f)
    else -> alternateEase(t, 4.2f / 2f)
}

/** `dm-flap`: the wings' horizontal scale, 1 down to .58 and back over .75 s each way. */
internal fun wingFlap(t: Float): Float = 1f - .42f * alternateEase(t, .75f)

/** `s-sway`: an angle swinging between -1.6 and 1.6 degrees, `alternate` over [period] seconds each way. */
internal fun swayAngle(t: Float, period: Float, delay: Float = 0f): Float = -1.6f + 3.2f * alternateEase(t, period, delay)

/** The petal's outline (a 4 x 10 teardrop from its tip), the same for the picture, the companion and the falling effect. */
internal val PETAL_PATH: Path by lazy { parsePath(PETAL) }

/** The monarch's body and antennae. */
internal val MONARCH_BODY_PATH: Path by lazy { parsePath(MONARCH_BODY) }
internal val MONARCH_ANTENNAE_PATH: Path by lazy { parsePath(MONARCH_ANTENNAE) }
internal val MONARCH_INK = Color(0xFF1d1016)

/** A falling petal's translate-y at [u] in its cycle: `s-snow`'s -30 down to 320. */
internal fun petalDrop(u: Float): Float = -30f + 350f * u

/** A flag's cut-out path, filled even-odd so the cut-outs show the bar behind. */
internal fun flagPath(kind: Int): Path = FLAG_PATHS[kind]

private val FLAG_PATHS: List<Path> by lazy {
    FLAG_SHAPES.map { parsePath(it).apply { fillType = PathFillType.EvenOdd } }
}

internal fun svgResource(name: String): SVGDOM {
    val bytes = requireNotNull(MuertosTheme::class.java.getResourceAsStream("/holiday/muertos/$name.svg")) { "missing $name.svg" }.use { it.readBytes() }
    return SVGDOM(Data.makeFromBytes(bytes))
}

internal fun DrawScope.drawSvg(svg: SVGDOM) = drawIntoCanvas { svg.render(it.skiaCanvas) }

/** The wings, centred on the body: the SVG's viewBox starts at (-32, -23). */
internal val MONARCH_WINGS by lazy { svgResource("monarch-wings") }

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
