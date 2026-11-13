// The design's CSS keyframes as pure functions of time. The numbers are the canvas' keyframe stops and durations, not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.skiaCanvas
import org.jetbrains.skia.Data
import org.jetbrains.skia.svg.SVGDOM
import kotlin.math.floor

// The pack-independent half of the animated seasonal art: the keyframes every pack's design canvas shares (candle flicker and flame, rising
// smoke, a falling petal or leaf, the swaying string, the blink) and the loader/painter of a pack's SVG layers. A pack draws its own shapes with
// them; Skia's SVG runs no CSS animation, so everything that moves is native and a frame is a pure function of the clock.

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

// The smoke's travel: s-steam goes from 6 down to -26 (a 32-unit rise) while it widens from .9 to 1.25.
private const val STEAM_START = 6f
private const val STEAM_RISE = 32f
private const val STEAM_NARROW = .9f
private const val STEAM_WIDEN = .35f
private const val STEAM_STILL = .35f

/**
 * One wisp of smoke: the stroke [path] (its [bounds]) at [width] and [alpha], rising and widening about its own base as `s-steam` does
 * (`late` runs half a cycle behind). The still pose is a visible mid-rise wisp.
 */
internal fun DrawScope.drawSteamWisp(path: Path, bounds: Rect, width: Float, alpha: Float, late: Boolean, t: Float, still: Boolean) {
    val u = if (still) STEAM_STILL else steamPhase(t, late)
    val move = easeInOut(u)
    val origin = Offset(bounds.center.x, bounds.bottom)
    withTransform({
        translate(0f, STEAM_START - STEAM_RISE * move)
        scale(STEAM_NARROW + STEAM_WIDEN * move, 1f, origin)
    }) {
        drawPath(path, Color.White.copy(alpha = alpha * steamOpacity(u)), style = Stroke(width, cap = StrokeCap.Round))
    }
}

/** `s-snow`: a falling petal's (or leaf's) position in its cycle (7 s, or 8.5 s from -3 s when [slow]). */
internal fun petalPhase(t: Float, slow: Boolean): Float = if (slow) cycle(t, 8.5f, 3f) else cycle(t, 7f)

/** `s-snow` opacity: fades in over the first tenth and out over the last. */
internal fun petalOpacity(u: Float): Float = if (u < .1f) u / .1f else if (u > .9f) (1f - u) / .1f else 1f

/** A falling petal's translate-y at [u] in its cycle: `s-snow`'s -30 down to 320. */
internal fun petalDrop(u: Float): Float = -30f + 350f * u

/** `s-sway`: an angle swinging between -1.6 and 1.6 degrees, `alternate` over [period] seconds each way. */
internal fun swayAngle(t: Float, period: Float, delay: Float = 0f): Float = -1.6f + 3.2f * alternateEase(t, period, delay)

/** Whether a companion's eyes are closed at [t]: the last 4 % of every 5.2 s blink, `cf-lid`'s 94 % to 98 %. */
internal fun holidayBlinkClosed(t: Float): Boolean = cycle(t, 5.2f).let { it >= .94f && it < .98f }

/** Loads the SVG layer `/holiday/[folder]/[name].svg` from the resources. */
internal fun holidaySvg(folder: String, name: String): SVGDOM {
    val stream = requireNotNull(HolidayTheme::class.java.getResourceAsStream("/holiday/$folder/$name.svg")) { "missing $folder/$name.svg" }
    val bytes = stream.use { it.readBytes() }
    return SVGDOM(Data.makeFromBytes(bytes))
}

internal fun DrawScope.drawSvg(svg: SVGDOM) = drawIntoCanvas { svg.render(it.skiaCanvas) }
