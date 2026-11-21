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
internal fun keyframes(u: Float, stops: FloatArray, values: FloatArray, eased: Boolean): Float =
    keyframesWith(u, stops, values) { if (eased) easeInOut(it) else it }

/** Keyframe values at [stops] (0..1, ascending); every segment's progress goes through the timing function [ease] (CSS applies it per keyframe interval). */
internal fun keyframesWith(u: Float, stops: FloatArray, values: FloatArray, ease: (Float) -> Float): Float {
    if (u <= stops.first()) return values.first()
    for (i in 1 until stops.size) {
        if (u <= stops[i]) {
            val raw = (u - stops[i - 1]) / (stops[i] - stops[i - 1])
            return values[i - 1] + (values[i] - values[i - 1]) * ease(raw)
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

// The table-driven evaluator of the design canvas' CSS animations (holiday.css: `.scn .s-*` and their @keyframes), for the generated layered
// art (HolidaySvgLayers.kt). Every class is the CSS rule as written: duration, (negative) delay, timing function, `alternate`, and the
// keyframe stops of each animated property. CSS applies the timing function per keyframe interval and a property only between the keyframes
// that name it, so every property is its own track. The transform is `translate rotate scale` (CSS list order) about the layer's origin.

/** One animated class's pose at an instant: the translation, the rotation in degrees, the scale and the opacity; untouched ones are identity. */
internal data class HolidayAnimFrame(
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val rotateDeg: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val opacity: Float = 1f,
)

private typealias CssTiming = (Float) -> Float

private val CSS_LINEAR: CssTiming = { it }
private val CSS_EASE_IN_OUT: CssTiming = ::easeInOut

private fun cssBezier(x1: Float, y1: Float, x2: Float, y2: Float): CssTiming = { u ->
    if (u <= 0f) {
        0f
    } else if (u >= 1f) {
        1f
    } else {
        var lo = 0f
        var hi = 1f
        repeat(EASE_STEPS) {
            val mid = (lo + hi) / 2f
            if (bezier(mid, x1, x2) < u) lo = mid else hi = mid
        }
        bezier((lo + hi) / 2f, y1, y2)
    }
}

private val CSS_EASE_IN: CssTiming = cssBezier(.42f, 0f, 1f, 1f)
private val CSS_EASE_OUT: CssTiming = cssBezier(0f, 0f, .58f, 1f)

/** CSS `ease-out` of a progress in 0..1 (cubic-bezier(0, 0, .58, 1)). */
internal fun easeOut(u: Float): Float = CSS_EASE_OUT(u)

/** CSS `steps(n, end)`: holds each interval's start value until the interval ends. */
private fun cssStepsEnd(n: Int): CssTiming = { u -> if (u >= 1f) 1f else floor(u * n) / n }

/** SCALE is `scale(k)`: one track for both axes (SX and SY fall back to it). */
private enum class AnimProp { TX, TY, ROTATE, SX, SY, SCALE, OPACITY }

private class Track(val stops: FloatArray, val values: FloatArray)

private fun track(vararg keys: Pair<Float, Float>) = Track(FloatArray(keys.size) { keys[it].first }, FloatArray(keys.size) { keys[it].second })

private class AnimSpec(val period: Float, val delay: Float, val timing: CssTiming, val alternate: Boolean, val tracks: Map<AnimProp, Track>) {
    fun frameAt(t: Float): HolidayAnimFrame {
        val u = if (alternate) cycle(t, 2f * period, delay).let { if (it <= .5f) it * 2f else 2f - it * 2f } else cycle(t, period, delay)

        fun value(prop: AnimProp, identity: Float): Float {
            val track = tracks[prop] ?: tracks[AnimProp.SCALE].takeIf { prop == AnimProp.SX || prop == AnimProp.SY }
            return track?.let { keyframesWith(u, it.stops, it.values, timing) } ?: identity
        }

        return HolidayAnimFrame(
            translateX = value(AnimProp.TX, 0f),
            translateY = value(AnimProp.TY, 0f),
            rotateDeg = value(AnimProp.ROTATE, 0f),
            scaleX = value(AnimProp.SX, 1f),
            scaleY = value(AnimProp.SY, 1f),
            opacity = value(AnimProp.OPACITY, 1f),
        )
    }
}

/** An `infinite` animation of [period] seconds that started [delay] seconds ago (a CSS negative delay is positive here). */
private fun loop(period: Float, delay: Float, timing: CssTiming, vararg props: Pair<AnimProp, Track>) =
    AnimSpec(period, delay, timing, alternate = false, props.toMap())

/** An `infinite alternate` animation: the same, played forward then back over two periods. */
private fun swing(period: Float, delay: Float, timing: CssTiming, vararg props: Pair<AnimProp, Track>) =
    AnimSpec(period, delay, timing, alternate = true, props.toMap())

// s-snow: 0% translateY(-30px) opacity 0; 10% and 90% opacity 1; 100% translateY(320px) opacity 0.
private val SNOW_DROP = track(0f to -30f, 1f to 320f)
private val SNOW_FADE = track(0f to 0f, .1f to 1f, .9f to 1f, 1f to 0f)

private fun snow(period: Float, delay: Float) = loop(period, delay, CSS_LINEAR, AnimProp.TY to SNOW_DROP, AnimProp.OPACITY to SNOW_FADE)

// hol-twinkle: 0%,100% opacity .35 scale .7; 50% opacity 1 scale 1.
private fun twinkle(period: Float, delay: Float) = loop(
    period, delay, CSS_EASE_IN_OUT,
    AnimProp.OPACITY to track(0f to .35f, .5f to 1f, 1f to .35f), AnimProp.SCALE to track(0f to .7f, .5f to 1f, 1f to .7f),
)

// s-pulse: 0%,100% opacity .8 scale 1; 50% opacity 1 scale 1.07.
private fun pulse(period: Float) = loop(
    period, 0f, CSS_EASE_IN_OUT,
    AnimProp.OPACITY to track(0f to .8f, .5f to 1f, 1f to .8f), AnimProp.SCALE to track(0f to 1f, .5f to 1.07f, 1f to 1f),
)

// hol-blink: 0%,100% opacity 1; 50% opacity .35 (1.6 s).
private fun lightBlink(delay: Float) = loop(1.6f, delay, CSS_EASE_IN_OUT, AnimProp.OPACITY to track(0f to 1f, .5f to .35f, 1f to 1f))

// s-steam: 0% translateY(6px) scaleX(.9) opacity 0; 35% opacity .8; 100% translateY(-26px) scaleX(1.25) opacity 0.
private fun steam(delay: Float) = loop(
    3.4f, delay, CSS_EASE_IN_OUT,
    AnimProp.TY to track(0f to 6f, 1f to -26f), AnimProp.SX to track(0f to .9f, 1f to 1.25f), AnimProp.OPACITY to track(0f to 0f, .35f to .8f, 1f to 0f),
)

// s-ear: 0%,80%,100% rotate 0; 86% -9deg; 92% 6deg (3 s).
private fun ear(delay: Float) = loop(3f, delay, CSS_EASE_IN_OUT, AnimProp.ROTATE to track(0f to 0f, .8f to 0f, .86f to -9f, .92f to 6f, 1f to 0f))

// hol-burst: 0% scale .15 opacity 0; 8% opacity 1; 55% opacity .9; 100% scale 1 opacity 0 (3.4 s ease-out).
private fun burst(delay: Float) = loop(
    3.4f, delay, CSS_EASE_OUT,
    AnimProp.SCALE to track(0f to .15f, 1f to 1f), AnimProp.OPACITY to track(0f to 0f, .08f to 1f, .55f to .9f, 1f to 0f),
)

// s-rise: 0% translateY(0) opacity 0; 15% opacity 1; 100% translateY(-80px) opacity 0 (ease-in).
private fun rise(period: Float, delay: Float) = loop(
    period, delay, CSS_EASE_IN, AnimProp.TY to track(0f to 0f, 1f to -80f), AnimProp.OPACITY to track(0f to 0f, .15f to 1f, 1f to 0f),
)

// s-float: 0%,100% translateY(0) rotate(-1.5deg); 50% translateY(-10px) rotate(1.5deg).
private fun float(period: Float, delay: Float) = loop(
    period, delay, CSS_EASE_IN_OUT,
    AnimProp.TY to track(0f to 0f, .5f to -10f, 1f to 0f), AnimProp.ROTATE to track(0f to -1.5f, .5f to 1.5f, 1f to -1.5f),
)

// hol-cursor: 0% opacity 1; 50% opacity 0 (steps(1,end): on for the first half, off for the second).
private val CURSOR_BLINK = loop(1f, 0f, cssStepsEnd(1), AnimProp.OPACITY to track(0f to 1f, .5f to 0f, 1f to 1f))

private const val SCROLL_STEPS = 9
private const val SCROLL_DISTANCE = 162f

private val ANIM_TABLE: Map<String, AnimSpec> = mapOf(
    // s-sway: 3.8 s alternate, rotate -1.6deg to 1.6deg. s-wave: -7deg to 9deg (1.6 s, and 1.2 s for wave2).
    "s-sway" to swing(3.8f, 0f, CSS_EASE_IN_OUT, AnimProp.ROTATE to track(0f to -1.6f, 1f to 1.6f)),
    "s-wave" to swing(1.6f, 0f, CSS_EASE_IN_OUT, AnimProp.ROTATE to track(0f to -7f, 1f to 9f)),
    "s-wave2" to swing(1.2f, 0f, CSS_EASE_IN_OUT, AnimProp.ROTATE to track(0f to -7f, 1f to 9f)),
    "s-snow" to snow(6f, 0f),
    "s-snow2" to snow(7.5f, 2.5f),
    "s-snow3" to snow(5.2f, 4f),
    "s-twinkle" to twinkle(1.8f, 0f),
    "s-twinkle2" to twinkle(2.3f, 1f),
    "s-pulse" to pulse(3.2f),
    "s-star" to pulse(2.4f),
    "s-nose" to pulse(1.4f),
    "s-light" to lightBlink(0f),
    "s-light2" to lightBlink(.55f),
    "s-light3" to lightBlink(1.1f),
    "s-steam" to steam(0f),
    "s-steam2" to steam(1.7f),
    "s-ear" to ear(0f),
    "s-ear2" to ear(.2f),
    // s-blink: 0%,90%,100% scaleY(1); 94% scaleY(.12) (4.6 s).
    "s-blink" to loop(4.6f, 0f, CSS_EASE_IN_OUT, AnimProp.SY to track(0f to 1f, .9f to 1f, .94f to .12f, 1f to 1f)),
    "s-burst" to burst(0f),
    "s-burst2" to burst(1.2f),
    "s-burst3" to burst(2.3f),
    // s-tick: rotate -24, -18, -12, -6, 0 deg at 0..80 %, each held to the next stop (5 s steps(1,end)); the implicit 100% is 0.
    "s-tick" to loop(5f, 0f, cssStepsEnd(1), AnimProp.ROTATE to track(0f to -24f, .2f to -18f, .4f to -12f, .6f to -6f, .8f to 0f, 1f to 0f)),
    "s-rise" to rise(2.4f, 0f),
    "s-rise2" to rise(2.9f, 1f),
    "s-rise3" to rise(2.2f, 1.6f),
    "s-float" to float(4.2f, 0f),
    "s-float2" to float(5.4f, 1.8f),
    "s-float3" to float(3.6f, .9f),
    "s-grid" to loop(1.2f, 0f, CSS_LINEAR, AnimProp.TY to track(0f to 0f, 1f to 22f)),
    "s-spin" to loop(9f, 0f, CSS_LINEAR, AnimProp.ROTATE to track(0f to 0f, 1f to 360f)),
    "s-spin2" to loop(3.6f, 0f, CSS_LINEAR, AnimProp.ROTATE to track(0f to 0f, 1f to 360f)),
    "s-scroll" to loop(9f, 0f, cssStepsEnd(SCROLL_STEPS), AnimProp.TY to track(0f to 0f, 1f to -SCROLL_DISTANCE)),
    "s-cursor" to CURSOR_BLINK,
    "s-cursorbar" to CURSOR_BLINK,
)

// The design's `.scn.still` rules: the animation is off (identity), except a burst shows at its .9 peak (full size) and a rising spark is
// hidden (nothing to show at rest).
private val STILL_POSES: Map<String, HolidayAnimFrame> = mapOf(
    "s-burst" to HolidayAnimFrame(opacity = .9f),
    "s-burst2" to HolidayAnimFrame(opacity = .9f),
    "s-burst3" to HolidayAnimFrame(opacity = .9f),
    "s-rise" to HolidayAnimFrame(opacity = 0f),
    "s-rise2" to HolidayAnimFrame(opacity = 0f),
    "s-rise3" to HolidayAnimFrame(opacity = 0f),
)

/** Whether [cls] is a canvas animation class [holidayAnimFrame] knows. */
internal fun isHolidayAnimClass(cls: String): Boolean = cls in ANIM_TABLE

/** Every canvas animation class [holidayAnimFrame] knows. */
internal val HOLIDAY_ANIM_CLASSES: Set<String> get() = ANIM_TABLE.keys

/** The pose of the canvas animation class [cls] at [t] seconds (looping, as the CSS runs), or null for a class this table does not know. */
internal fun holidayAnimFrame(cls: String, t: Float): HolidayAnimFrame? = ANIM_TABLE[cls]?.frameAt(t)

/** The calm pose of [cls] (the design's `.still` rules: identity, bursts at their peak, rising sparks hidden), or null for an unknown class. */
internal fun holidayStillFrame(cls: String): HolidayAnimFrame? = if (cls in ANIM_TABLE) STILL_POSES[cls] ?: HolidayAnimFrame() else null

/** Loads the SVG layer `/holiday/[folder]/[name].svg` from the resources. */
internal fun holidaySvg(folder: String, name: String): SVGDOM {
    val stream = requireNotNull(HolidayTheme::class.java.getResourceAsStream("/holiday/$folder/$name.svg")) { "missing $folder/$name.svg" }
    val bytes = stream.use { it.readBytes() }
    return SVGDOM(Data.makeFromBytes(bytes))
}

internal fun DrawScope.drawSvg(svg: SVGDOM) = drawIntoCanvas { svg.render(it.skiaCanvas) }
