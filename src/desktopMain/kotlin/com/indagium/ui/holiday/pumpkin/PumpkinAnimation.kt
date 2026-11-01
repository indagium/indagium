// Keyframe times and amplitudes are the design canvas' CSS animation values (holiday.css .scn rules), not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.pumpkin

import com.indagium.ui.*
import kotlin.math.floor

// The seasonal pictures' motion: pure functions of time in seconds (so they are testable and periodic); HolidayClock.kt feeds them the time.
// The CSS keyframes of the canvas are ported as piecewise-linear or ease-in-out curves; every function is periodic in its own period.

private fun ease(u: Float): Float = u * u * (3f - 2f * u)

private fun lerp(a: Float, b: Float, u: Float): Float = a + (b - a) * u

/** Position in [0, 1) of time [t] in a cycle of [period] seconds, shifted by [delay] (a negative CSS delay starts mid-cycle). */
internal fun cyclePhase(t: Float, period: Float, delay: Float = 0f): Float {
    val x = (t - delay) / period
    return x - floor(x)
}

// 0 -> 1 -> 0 over one cycle (the CSS "alternate"), eased at both turns.
private fun easedTriangle(phase: Float): Float = ease(if (phase < 0.5f) phase * 2f else 2f - phase * 2f)

/** A gentle hover: [dy] dp up (negative) and [deg] degrees of tilt, as the canvas' s-float keyframes. */
internal data class Bob(val dy: Float, val deg: Float)

/** The s-float curve: 0 to -10 and back with a -1.5 to 1.5 degree tilt, every [period] seconds. */
internal fun floatBob(t: Float, period: Float, delay: Float): Bob {
    val e = easedTriangle(cyclePhase(t, period, delay))
    return Bob(dy = -10f * e, deg = lerp(-1.5f, 1.5f, e))
}

private val BAT_BOB = listOf(4.2f to 0f, 5.4f to -1.8f)
private val FIREFLY_BOB = listOf(4.2f to 0f, 5.4f to -1.8f, 3.6f to -0.9f, 4.2f to 0f)

/** The bob of bat [i] (0 = the big bat by the moon, 1 = the small one) at time [t]. */
internal fun batBob(i: Int, t: Float): Bob = BAT_BOB[i % BAT_BOB.size].let { (period, delay) -> floatBob(t, period, delay) }

/** The bob of firefly [i] (0..3) at time [t]. */
internal fun fireflyAt(i: Int, t: Float): Bob = FIREFLY_BOB[i % FIREFLY_BOB.size].let { (period, delay) -> floatBob(t, period, delay) }

/** The vertical scale of a bat's wings: 1 down to 0.35 and back, a full flap every 0.6 s. */
internal fun batFlap(t: Float): Float = lerp(1f, 0.35f, easedTriangle(cyclePhase(t, 0.6f)))

// The s-flicker keyframes: (percent of the cycle, opacity), linear between.
private val FLICKER = listOf(0f to 1f, 7f to 0.78f, 11f to 0.96f, 38f to 0.86f, 42f to 1f, 68f to 0.9f, 73f to 1f, 100f to 1f)

/** The candle-like opacity of a pumpkin's glow, 0.78..1, every [period] seconds (the canvas' s-flicker is 2.6 s, s-flicker2 2.1 s with a -0.7 s delay). */
internal fun pumpkinFlicker(t: Float, period: Float = 2.6f, delay: Float = 0f): Float {
    val pct = cyclePhase(t, period, delay) * 100f
    val next = FLICKER.indexOfFirst { it.first >= pct }.coerceAtLeast(1)
    val (p0, v0) = FLICKER[next - 1]
    val (p1, v1) = FLICKER[next]
    return if (p1 == p0) v1 else lerp(v0, v1, (pct - p0) / (p1 - p0))
}

/** The halo behind the moon: opacity 0.8..1 and scale 1..1.07 every 3.2 s. */
internal data class Pulse(val alpha: Float, val scale: Float)

internal fun moonPulse(t: Float): Pulse {
    val e = easedTriangle(cyclePhase(t, 3.2f))
    return Pulse(alpha = lerp(0.8f, 1f, e), scale = lerp(1f, 1.07f, e))
}

/** The cat's tail swish: -7 to 9 degrees and back, every 2.8 s (1.4 s each way). */
internal fun catTailAngle(t: Float): Float = lerp(-7f, 9f, easedTriangle(cyclePhase(t, 2.8f)))

/** The cat's eye height: open (1) most of the time, closed to 0.12 at 94% of a 4.6 s cycle. */
internal fun catBlink(t: Float): Float {
    val pct = cyclePhase(t, 4.6f) * 100f
    return when {
        pct <= 90f -> 1f
        pct <= 94f -> lerp(1f, 0.12f, (pct - 90f) / 4f)
        else -> lerp(0.12f, 1f, (pct - 94f) / 6f)
    }
}
