// Counts, sizes, periods and the burst sprite are the design canvas' values, not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.newyear

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.holiday.HolidayParticleEffect
import com.indagium.ui.holiday.HolidayParticleField
import com.indagium.ui.holiday.cycle
import com.indagium.ui.holiday.easeOut
import com.indagium.ui.holiday.keyframesWith
import com.indagium.ui.holiday.parsePath
import com.indagium.ui.holiday.snow.SettlingEffect
import com.indagium.ui.holiday.snow.span
import kotlin.math.roundToInt
import kotlin.random.Random

// New Year's Eve's window-wide effects: confetti falling over the window and settling on the tabs and tiles, and fireworks bursting in the upper
// part of the window. Every field is seeded, so a frame is a function of the clock only. The fireworks are small sprites that grow and fade
// (`hol-burst`), not window flashes, so they sit outside the flash limits.

/** The design's confetti colours, one palette each (`C[i % 5]`; the fireworks use the first four). */
private val CONFETTI_DARK = listOf(0xFFf0c75e, 0xFFff6fae, 0xFF5ad1c8, 0xFF7c5cff, 0xFFffffff).map(::Color)
private val CONFETTI_LIGHT = listOf(0xFFd9a520, 0xFFec4899, 0xFF14b8a6, 0xFF7c3aed, 0xFFf59e0b).map(::Color)

internal fun confettiColors(dark: Boolean): List<Color> = if (dark) CONFETTI_DARK else CONFETTI_LIGHT

private class Piece(val x: Float, val w: Float, val h: Float, val turn: Float, val period: Float, val phase: Float)

/** Confetti falling over the window: a 4 to 7 by 7 to 11 dp piece drifting 12 dp right while it turns 540 degrees (`hol-fallspin`, linear). */
internal object Confetti : HolidayParticleEffect {
    override val id = "newyear.confetti"
    override val label = "Confetti"
    override val noun = "confetti"
    override val defaultOn = true

    private const val BASE_COUNT = 34

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField {
        val random = Random(seed)
        val pieces = List(if (speedScale <= 0f) 0 else (BASE_COUNT * countScale).roundToInt().coerceAtLeast(0)) {
            Piece(
                random.nextFloat(), span(4f, 7f).pick(random), span(7f, 11f).pick(random), span(0f, 180f).pick(random),
                span(8f, 14f).pick(random) / speedScale, random.nextFloat(),
            )
        }
        return object : HolidayParticleField {
            override val count = pieces.size

            override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) = with(scope) {
                val colors = confettiColors(dark)
                pieces.forEachIndexed { i, p ->
                    val u = cycle(t, p.period, p.phase * p.period)
                    val centre = Offset(p.x * widthDp + 12f * u, -40f + u * (heightDp + 80f))
                    withTransform({
                        translate(centre.x, centre.y)
                        rotate(p.turn + 540f * u, Offset.Zero)
                    }) { drawRect(colors[i % colors.size], Offset(-p.w / 2f, -p.h / 2f), Size(p.w, p.h)) }
                }
            }
        }
    }
}

/** Confetti that lands on the tabs and tiles: a 5 to 8 by 3 dp strip lying on the edge, a little askew (the design's `.p-conf.land`). */
internal object ConfettiSettling : SettlingEffect(
    "newyear.landing", "Confetti lands on tabs and tiles", "landed confetti", 18, span(5f, 8f),
    { bottom, size, index, alpha, dark ->
        val colors = confettiColors(dark)
        val tilt = ((index * 37) % 41 - 20).toFloat()
        withTransform({
            translate(bottom.x, bottom.y - 1.5f)
            rotate(tilt, Offset.Zero)
        }) { drawRect(colors[index % colors.size].copy(alpha = alpha), Offset(-size / 2f, -1.5f), Size(size, 3f)) }
    },
)

private val BURST_RAYS = parsePath("M0-7V-16M0 7V16M-7 0H-16M7 0H16M5-5L11.3-11.3M-5 5L-11.3 11.3M5 5L11.3 11.3M-5-5L-11.3-11.3")
private val BURST_DOTS = listOf(Offset(0f, -18.5f), Offset(0f, 18.5f), Offset(-18.5f, 0f), Offset(18.5f, 0f))

/** The burst sprite of the design (`viewBox -20 -20 40 40`): eight rays and four dots in [color], centred on the current origin. */
internal fun DrawScope.drawBurstSprite(color: Color) {
    drawPath(BURST_RAYS, color, style = Stroke(1.4f, cap = StrokeCap.Round))
    BURST_DOTS.forEach { drawCircle(color, 1.2f, it) }
}

private val BURST_STOPS = floatArrayOf(0f, .08f, .55f, 1f)
private val BURST_OPACITY = floatArrayOf(0f, 1f, .9f, 0f)

/** `hol-burst` at [u] (0..1 through one burst): the sprite's scale (.15 growing to 1, ease-out) and opacity (up to 1 by 8 %, .9 at 55 %, gone at the end). */
internal fun burstFrame(u: Float): Pair<Float, Float> = (.15f + .85f * easeOut(u)) to keyframesWith(u, BURST_STOPS, BURST_OPACITY, ::easeOut)

private class Burst(val x: Float, val y: Float, val size: Float, val colorIndex: Int, val period: Float, val phase: Float)

/** Fireworks bursting near the top of the window, left and right of its middle (`hol-burst` on a 36 to 64 dp sprite). */
internal object Fireworks : HolidayParticleEffect {
    override val id = "newyear.fireworks"
    override val label = "Fireworks"
    override val noun = "fireworks"
    override val defaultOn = true

    private const val BASE_COUNT = 6
    private const val DESIGN_WIDTH = 1600f

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField {
        val random = Random(seed)
        val bursts = List(if (speedScale <= 0f) 0 else (BASE_COUNT * countScale).roundToInt().coerceAtLeast(0)) { i ->
            val x = if (i % 2 == 1) span(940f, 1300f).pick(random) else span(260f, 640f).pick(random)
            val y = span(-26f, -8f).pick(random)
            val size = span(40f, 64f).pick(random)
            val period = span(3.2f, 4.6f).pick(random) / speedScale
            Burst(x / DESIGN_WIDTH, y, size, i % 4, period, random.nextFloat())
        }
        return object : HolidayParticleField {
            override val count = bursts.size

            override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) = with(scope) {
                val colors = confettiColors(dark)
                bursts.forEach { b ->
                    val (scale, opacity) = burstFrame(cycle(t, b.period, b.phase * b.period))
                    if (opacity <= 0f) return@forEach
                    val k = b.size / 40f * scale
                    withTransform({
                        translate(b.x * widthDp + b.size / 2f, b.y + b.size / 2f)
                        scale(k, k, Offset.Zero)
                    }) { drawBurstSprite(colors[b.colorIndex].copy(alpha = opacity)) }
                }
            }
        }
    }
}
