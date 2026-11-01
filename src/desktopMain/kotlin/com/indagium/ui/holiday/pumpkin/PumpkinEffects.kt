// Counts, ranges and timings are the design canvas' effect values (holiday.css .p-* rules, HolidayApp renderVals), not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.pumpkin

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.*
import com.indagium.ui.holiday.HolidayFlashEffect
import com.indagium.ui.holiday.HolidayParticleEffect
import com.indagium.ui.holiday.HolidayParticleField
import com.indagium.ui.holiday.parsePath
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// The pumpkin patch's window-wide effects: rising embers, bats flying across and a faint lightning flash. The embers and bats are pure, seeded
// particle fields so a frame is only a function of the clock; the framework's overlay (HolidayEffectsOverlay.kt) draws them and schedules
// the flash. Sizes are in dp.

private const val FULL_EMBERS = 36
private const val FULL_BATS = 3
private val FLASH_TINT = Color(0xFFFFF6C4)
private val BAT_DESIGN = Offset(40f, 18f)
private const val EMBER_RISE_DP = 320f
private const val EMBER_FLOOR_DP = 4f
private const val EMBER_SWAY_DP = 10f
private const val BAT_WAVE_DP = 8f
private const val BAT_MAX_Y_DP = 170f
private const val BAT_TOP_DP = 2f

/** An ember: [xFrac] across the window, [size] dp, one rise every [periodSec] seconds, offset by [phase] (0..1). */
internal data class Ember(val xFrac: Float, val size: Float, val periodSec: Float, val phase: Float, val swayPhase: Float)

/** A bat: flies across in [periodSec] seconds at about [y] dp from the top, [size] dp wide, offset by [phase] (0..1). */
internal data class Bat(val y: Float, val size: Float, val periodSec: Float, val phase: Float, val wavePhase: Float)

/** Where a particle is at an instant: its centre ([x], [y]) in dp, its [alpha], and [extra] (a bat's wing height factor; unused for embers). */
internal data class Placed(val x: Float, val y: Float, val alpha: Float, val extra: Float = 1f)

internal class Rng(seed: Int) {
    private val random = Random(seed)

    fun range(a: Float, b: Float): Float = a + random.nextFloat() * (b - a)
}

internal fun scaled(base: Int, scale: Float): Int = (base * scale).roundToInt().coerceAtLeast(0)

private fun frac(x: Float): Float = x - floor(x)

private fun sine(cycles: Float): Float = sin(cycles * 2f * PI.toFloat())

/** The ember's place at [t] seconds in a [w] x [h] dp window: rising from the bottom with a gentle sway, fading in then out (`hol-rise`). */
internal fun emberAt(e: Ember, t: Float, w: Float, h: Float): Placed {
    val u = frac(t / e.periodSec + e.phase)
    val alpha = if (u < 0.15f) 0.85f * u / 0.15f else 0.85f * (1f - (u - 0.15f) / 0.85f)
    return Placed(e.xFrac * w + EMBER_SWAY_DP * sine(u + e.swayPhase), h - EMBER_FLOOR_DP - u * EMBER_RISE_DP, alpha)
}

/** The bat's place at [t]: flying left to right across the [w] dp window (entering and leaving off-screen) with a slight wave; `extra` is the wing factor. */
internal fun batAt(b: Bat, t: Float, w: Float, h: Float): Placed {
    val u = frac(t / b.periodSec + b.phase)
    val margin = 2f * b.size
    val y = (b.y + BAT_WAVE_DP * sine(2f * u + b.wavePhase)).coerceIn(0f, h)
    return Placed(-margin + u * (w + 2f * margin), y, 0.9f, batWing(t))
}

/** The wing height factor: a flap between 1 and 0.35 every 0.52 s (`hol-flap` .26 s, alternate). */
internal fun batWing(t: Float): Float {
    val p = frac(t / 0.52f)
    val tri = if (p < 0.5f) p * 2f else 2f - p * 2f
    return 1f - 0.65f * tri * tri * (3f - 2f * tri)
}

// The art of the bats and the lightning bolt, parsed once.
private object EffectArt {
    val bat = parsePath(
        "M20 5c1.6 0 2.6 1 2.6 1l1-2.4.7 3C26.6 5 30.4 3.6 36 6c-2.6 1.2-3.8 3.6-3.8 6.2-2.6-1.2-5.2-1.2-7.2 1.4-1.6-1.2-3.4-1.8-5-1.8" +
            "s-3.4.6-5 1.8c-2-2.6-4.6-2.6-7.2-1.4C7.8 9.6 6.6 7.2 4 6c5.6-2.4 9.4-1 11.7.6l.7-3 1 2.4S18.4 5 20 5z",
    )
    val bolt = parsePath("M96 0L40 140h36L20 330 128 112H88L120 0z")
}

internal class EmberField(val embers: List<Ember>) : HolidayParticleField {
    override val count get() = embers.size

    override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) {
        val color = pumpkinSceneColors(dark).ember
        embers.forEach { with(scope) { drawEmber(emberAt(it, t, widthDp, heightDp), it.size, color) } }
    }
}

internal class BatField(val bats: List<Bat>) : HolidayParticleField {
    override val count get() = bats.size

    override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) {
        val color = pumpkinSceneColors(dark).bat
        bats.forEach { with(scope) { drawBat(batAt(it, t, widthDp, heightDp), it.size, color) } }
    }
}

/** Embers rising from the bottom of the window: 36 on Full, [countScale] of that on Calm. */
internal object PumpkinEmbers : HolidayParticleEffect {
    override val id = "pumpkin.embers"
    override val label = "Rising embers"
    override val noun = "embers"
    override val defaultOn = true

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField {
        if (speedScale <= 0f) return EmberField(emptyList())
        val rng = Rng(seed)
        return EmberField(
            List(scaled(FULL_EMBERS, countScale)) {
                Ember(rng.range(0.02f, 0.98f), rng.range(2f, 3.6f), rng.range(6f, 11f) / speedScale, rng.range(0f, 1f), rng.range(0f, 1f))
            },
        )
    }
}

/** Bats flying across the window: 3 on Full, [countScale] of that on Calm. */
internal object PumpkinBats : HolidayParticleEffect {
    override val id = "pumpkin.bats"
    override val label = "Bats"
    override val noun = "bats"
    override val defaultOn = true

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField {
        if (speedScale <= 0f) return BatField(emptyList())
        val rng = Rng(seed)
        return BatField(
            List(scaled(FULL_BATS, countScale)) {
                Bat(rng.range(BAT_TOP_DP, BAT_MAX_Y_DP), rng.range(18f, 30f), rng.range(16f, 26f) / speedScale, rng.range(0f, 1f), rng.range(0f, 1f))
            },
        )
    }
}

/** The faint lightning flash: the whole window lightens a touch for an instant and a faint bolt shows near the top right while it does. */
internal object PumpkinLightning : HolidayFlashEffect {
    override val id = "pumpkin.lightning"
    override val label = "Lightning"
    override val noun = "lightning"
    override val defaultOn = true

    override fun draw(scope: DrawScope, alpha: Float, widthDp: Float, heightDp: Float, dark: Boolean) {
        with(scope) {
            drawRect(FLASH_TINT.copy(alpha = alpha), Offset.Zero, Size(widthDp, heightDp))
            withTransform({
                translate(widthDp - 190f, 40f)
                scale(0.4f, 0.4f, Offset.Zero)
            }) { drawPath(EffectArt.bolt, FLASH_TINT.copy(alpha = alpha * 2f)) }
        }
    }
}

private fun DrawScope.drawEmber(p: Placed, size: Float, color: Color) {
    if (p.alpha <= 0f) return
    drawCircle(color.copy(alpha = p.alpha * 0.25f), size * 2.2f, Offset(p.x, p.y))
    drawCircle(color.copy(alpha = p.alpha), size / 2f, Offset(p.x, p.y))
}

// A bat of [width] dp wide (the design's 40 x 18 box scaled), its wings flapping as a vertical squash about the body.
private fun DrawScope.drawBat(p: Placed, width: Float, color: Color) {
    val s = width / BAT_DESIGN.x
    withTransform({
        translate(p.x - width / 2f, p.y - BAT_DESIGN.y * s / 2f)
        scale(s, s, Offset.Zero)
        scale(1f, p.extra, Offset(20f, 8f))
    }) { drawPath(EffectArt.bat, color.copy(alpha = p.alpha)) }
}
