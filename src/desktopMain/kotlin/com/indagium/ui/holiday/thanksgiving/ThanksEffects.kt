// Particle counts, sizes and periods are the design's values, not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.thanksgiving

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.holiday.HolidayEffectSpec
import com.indagium.ui.holiday.HolidayParticleEffect
import com.indagium.ui.holiday.HolidayParticleField
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// The window-wide effects: leaves drifting down and geese crossing the window. Both are seeded fields, so a frame is a function of the clock
// only. The leaf is the picture's maple or oak, and a goose is the picture's, stroked in the palette's secondary text colour.

internal val THANKS_EFFECTS: List<HolidayEffectSpec> = listOf(FallingLeaves, GeeseFlyingSouth)

private val leafColors = listOf(0xFFb5451b, 0xFFe8a33a, 0xFFc2321f, 0xFFd9772b, 0xFF8a6d1a, 0xFFe8a33a).map(::Color)

private class Drifter(val x: Float, val size: Float, val period: Float, val phase: Float, val angle: Float)

private fun phase(value: Float) = value - floor(value)

private fun randomRange(random: Random, from: Float, to: Float) = from + random.nextFloat() * (to - from)

private object FallingLeaves : HolidayParticleEffect {
    override val id = "thanksgiving.leaves"
    override val label = "Falling leaves"
    override val noun = "leaves"
    override val defaultOn = true

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField {
        val rng = Random(seed)
        val particles = List(if (speedScale <= 0f) 0 else (26 * countScale).roundToInt().coerceAtLeast(0)) {
            Drifter(rng.nextFloat(), randomRange(rng, 13f, 20f), randomRange(rng, 11f, 18f) / speedScale, rng.nextFloat(), randomRange(rng, 0f, 360f))
        }
        return object : HolidayParticleField {
            override val count = particles.size

            override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) = with(scope) {
                particles.forEachIndexed { i, p ->
                    val u = phase(t / p.period + p.phase)
                    val x = p.x * widthDp + 18f * sin(u * 4f * PI.toFloat())
                    val y = -p.size + u * (heightDp + 2f * p.size)
                    withTransform({
                        translate(x, y)
                        rotate(p.angle + u * 360f, Offset.Zero)
                        scale(p.size / LEAF_SIZE, p.size / LEAF_SIZE, Offset.Zero)
                    }) { drawLeaf(i % 2, leafColors[i % leafColors.size]) }
                }
            }
        }
    }
}

private class Flock(val y: Float, val period: Float, val phase: Float)

private object GeeseFlyingSouth : HolidayParticleEffect {
    override val id = "thanksgiving.geese"
    override val label = "Geese flying south"
    override val noun = "geese"
    override val defaultOn = true

    private const val GOOSE_SCALE = .6f

    // A V of seven: the leader, then a pair behind it at (-13k, +-6k) for k = 1..3.
    private val V = listOf(0f to 0f) + (1..3).flatMap { k -> listOf(-13f * k to -6f * k, -13f * k to 6f * k) }

    // The stroke is the palette's secondary text colour, worked out once rather than per frame.
    private val inkDark by lazy { ThanksTheme.palette(true).ts }
    private val inkLight by lazy { ThanksTheme.palette(false).ts }

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField {
        val rng = Random(seed)
        val flocks = List(if (speedScale <= 0f) 0 else (2 * countScale).roundToInt().coerceAtLeast(0)) {
            Flock(randomRange(rng, 10f, 150f), randomRange(rng, 26f, 34f) / speedScale, rng.nextFloat())
        }
        return object : HolidayParticleField {
            override val count = flocks.size

            override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) = with(scope) {
                val ink = if (dark) inkDark else inkLight
                flocks.forEach { f ->
                    val u = phase(t / f.period + f.phase)
                    val x = -50f + u * (widthDp + 100f)
                    val y = (f.y + 5f * sin(u * 2f * PI.toFloat())).coerceAtMost(heightDp)
                    // The real-time clock speeds the flap with the intensity, like the drift; each flock flaps out of step with the other.
                    val flap = gooseFlap(t * speedScale + f.phase * .45f)
                    V.forEach { (dx, dy) ->
                        withTransform({
                            translate(x + dx, y + dy)
                            scale(GOOSE_SCALE, GOOSE_SCALE, Offset.Zero)
                        }) { drawGoose(flap, ink) }
                    }
                }
            }
        }
    }
}
