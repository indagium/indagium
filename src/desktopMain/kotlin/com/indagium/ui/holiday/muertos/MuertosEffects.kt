// Particle counts, sizes and periods are the design's values, not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.muertos

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

// The window-wide effects: marigold petals drifting down and monarchs crossing the window. Both are seeded fields, so a frame is a function of
// the clock only. The petal is the picture's petal and the monarch is the picture's (wings from monarch-wings.svg, flapping the same way).

internal val MUERTOS_EFFECTS: List<HolidayEffectSpec> = listOf(MarigoldPetals, MonarchButterflies)

private val petalColors = listOf(0xFFff9a1f, 0xFFffc02e, 0xFFe8650c, 0xFFffb347, 0xFFff9a1f, 0xFFe6007e).map(::Color)

private class Drifter(val x: Float, val y: Float, val size: Float, val period: Float, val phase: Float, val angle: Float)

private fun phase(value: Float) = value - floor(value)

private fun randomRange(random: Random, from: Float, to: Float) = from + random.nextFloat() * (to - from)

private object MarigoldPetals : HolidayParticleEffect {
    override val id = "muertos.petals"
    override val label = "Marigold petals"
    override val noun = "petals"
    override val defaultOn = true

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField {
        val rng = Random(seed)
        val particles = List(if (speedScale <= 0f) 0 else (26 * countScale).roundToInt().coerceAtLeast(0)) {
            Drifter(rng.nextFloat(), 0f, randomRange(rng, 8f, 13f), randomRange(rng, 10f, 17f) / speedScale, rng.nextFloat(), randomRange(rng, 0f, 360f))
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
                        rotate(p.angle + u * 360f, Offset(0f, p.size / 2f))
                        scale(p.size / 10f, p.size / 10f, Offset.Zero)
                    }) { drawPath(PETAL_PATH, petalColors[i % petalColors.size]) }
                }
            }
        }
    }
}

private object MonarchButterflies : HolidayParticleEffect {
    override val id = "muertos.monarchs"
    override val label = "Monarch butterflies"
    override val noun = "monarchs"
    override val defaultOn = true

    // The wings are 64 x 48 in the art: this makes a monarch about 30 x 24 dp.
    private const val MONARCH_SCALE = 30f / 64f

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField {
        val rng = Random(seed)
        val particles = List(if (speedScale <= 0f) 0 else (4 * countScale).roundToInt().coerceAtLeast(0)) {
            Drifter(0f, randomRange(rng, 4f, 170f), 26f, randomRange(rng, 20f, 30f) / speedScale, rng.nextFloat(), 0f)
        }
        return object : HolidayParticleField {
            override val count = particles.size

            override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) = with(scope) {
                particles.forEach { p ->
                    val u = phase(t / p.period + p.phase)
                    val x = -40f + u * (widthDp + 80f)
                    val y = (p.y + 8f * sin(u * 4f * PI.toFloat())).coerceAtMost(heightDp)
                    // The real-time clock speeds the flap with the intensity, like the drift; each monarch flaps out of step with the others.
                    val flap = wingFlap(t * speedScale + p.phase * 1.5f)
                    withTransform({
                        translate(x, y)
                        scale(MONARCH_SCALE, MONARCH_SCALE, Offset.Zero)
                    }) { drawMonarch(flap) }
                }
            }
        }
    }
}
