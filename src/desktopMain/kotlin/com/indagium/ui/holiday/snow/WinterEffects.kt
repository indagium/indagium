// Particle counts, sizes, periods and the sleigh's flight are the design canvas' values, not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.snow

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.holiday.HolidayLandingEffect
import com.indagium.ui.holiday.HolidayLandingField
import com.indagium.ui.holiday.HolidayParticleEffect
import com.indagium.ui.holiday.HolidayParticleField
import com.indagium.ui.holiday.cycle
import com.indagium.ui.holiday.keyframes
import com.indagium.ui.holiday.parsePath
import kotlin.math.roundToInt
import kotlin.random.Random

// The window-wide winter effects shared by First snow, Cozy Christmas and Classic Christmas: snow falling over the window, snow settling on the
// tabs and tiles, and Santa's sleigh. Each pack builds its own specs with the numbers its design gives (the ids stay unique per pack). Every field
// is seeded, so a frame is a function of the clock only.

/** A design range `a..b`, drawn from [random]. */
internal class FloatSpan(val from: Float, val to: Float) {
    fun pick(random: Random): Float = from + random.nextFloat() * (to - from)
}

internal fun span(from: Float, to: Float) = FloatSpan(from, to)

private fun phase(value: Float) = value - kotlin.math.floor(value)

/** The flake colour: the design's `--flake`, one per palette. */
internal class FlakeInk(val dark: Color, val light: Color) {
    fun of(isDark: Boolean) = if (isDark) dark else light
}

private class Flake(val x: Float, val size: Float, val alpha: Float, val period: Float, val phase: Float)

/** Snow falling over the whole window (the design's `hol-fall`: a flake drifts 14 right, then 20 left over its fall, linear). */
internal class Snowfall(
    override val id: String,
    private val base: Int,
    private val sizes: FloatSpan,
    private val alphas: FloatSpan,
    private val periods: FloatSpan,
    private val ink: FlakeInk,
) : HolidayParticleEffect {
    override val label = "Snowfall"
    override val noun = "snow"
    override val defaultOn = true

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField {
        val random = Random(seed)
        val flakes = List(if (speedScale <= 0f) 0 else (base * countScale).roundToInt().coerceAtLeast(0)) {
            Flake(random.nextFloat(), sizes.pick(random), alphas.pick(random), periods.pick(random) / speedScale, random.nextFloat())
        }
        return object : HolidayParticleField {
            override val count = flakes.size

            override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) = with(scope) {
                val color = ink.of(dark)
                flakes.forEach { f ->
                    val u = phase(t / f.period + f.phase)
                    val drift = keyframes(u, DRIFT_STOPS, DRIFT_X, eased = false)
                    drawCircle(color.copy(alpha = f.alpha), f.size / 2f, Offset(f.x * widthDp + drift, -FALL_MARGIN + u * (heightDp + 2f * FALL_MARGIN)))
                }
            }
        }
    }

    private companion object {
        const val FALL_MARGIN = 40f
        val DRIFT_STOPS = floatArrayOf(0f, .5f, 1f)
        val DRIFT_X = floatArrayOf(0f, 14f, -6f)
    }
}

private class Settler(val x: Float, val size: Float, val fall: Float, val phase: Float)

/**
 * Particles that settle on the tabs and tiles: one falls from 260 dp above onto the top edge of a visible tab or tile (`hol-land`: fades in over
 * the first tenth, eases out to its place by 82 %), rests there, and melts away (the design lands each one once; a loop keeps the effect alive).
 * [drawFlake] draws one standing on [bottom] (the point on the edge it rests on): its [size] from [sizes], its index, its [alpha] and the palette.
 */
internal open class SettlingEffect(
    final override val id: String,
    final override val label: String,
    final override val noun: String,
    private val base: Int,
    private val sizes: FloatSpan,
    private val drawFlake: DrawScope.(bottom: Offset, size: Float, index: Int, alpha: Float, dark: Boolean) -> Unit,
) : HolidayLandingEffect {
    override val defaultOn = true

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField {
        val random = Random(seed)
        val flakes = List(if (speedScale <= 0f) 0 else (base * countScale).roundToInt().coerceAtLeast(0)) {
            Settler(random.nextFloat(), sizes.pick(random), 2.5f + random.nextFloat() * 1.5f, random.nextFloat())
        }
        return object : HolidayLandingField {
            override val count = flakes.size

            override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) = Unit

            override fun drawLanding(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean, anchors: List<Rect>) = with(scope) {
                val visible = anchors.filter {
                    it.width >= MIN_ANCHOR_WIDTH && it.right > 0f && it.left < widthDp && it.top >= MIN_ANCHOR_TOP && it.top < heightDp
                }
                if (visible.isEmpty()) return@with
                flakes.forEachIndexed { i, f ->
                    val anchor = visible[i % visible.size]
                    val total = f.fall + REST_SEC + MELT_SEC
                    val local = phase((t * speedScale) / total + f.phase) * total
                    val alpha = when {
                        local < f.fall -> (local / f.fall / FADE_IN).coerceAtMost(1f)
                        local < f.fall + REST_SEC -> 1f
                        else -> 1f - (local - f.fall - REST_SEC) / MELT_SEC
                    } * RESTING_ALPHA
                    if (alpha <= 0f) return@forEachIndexed
                    val progress = (local / f.fall / LANDED_AT).coerceIn(0f, 1f)
                    val offset = -FALL_HEIGHT * (1f - progress) * (1f - progress)
                    val x = (anchor.left + anchor.width * (.1f + .8f * f.x)).coerceIn(0f, widthDp)
                    drawFlake(Offset(x, anchor.top + offset), f.size, i, alpha, dark)
                }
            }
        }
    }

    private companion object {
        const val MIN_ANCHOR_WIDTH = 30f
        const val MIN_ANCHOR_TOP = 10f
        const val FALL_HEIGHT = 260f
        const val FADE_IN = .1f
        const val LANDED_AT = .82f
        const val RESTING_ALPHA = .95f
        const val REST_SEC = 5f
        const val MELT_SEC = 2f
    }
}

/** Snow that settles on the tabs and tiles: a round flake of [sizes] dp resting on the edge. */
internal class SnowSettling(id: String, base: Int, sizes: FloatSpan, ink: FlakeInk) : SettlingEffect(
    id, "Snow settles on tabs and tiles", "settled snow", base, sizes,
    { bottom, size, _, alpha, dark -> drawCircle(ink.of(dark).copy(alpha = alpha), size / 2f, Offset(bottom.x, bottom.y - size / 2f)) },
)

private val SLEIGH_BODY = parsePath("M4 15h26c4 0 6 3 4 6l-2 2H10c-4 0-7-3-6-8z")
private val SLEIGH_DEER = parsePath(
    "M53 15.5c0-2.2 3.8-3.6 9-3.6s8 1.4 8 3.6-3 3.4-8 3.4-9-1.2-9-3.4zM69 14l4-5h4l-1 3h-3l-2 3z" +
        "M79 14.5c0-2.2 3.8-3.6 9-3.6s8 1.4 8 3.6-3 3.4-8 3.4-9-1.2-9-3.4zM95 13l4-5h4l-1 3h-3l-2 3z" +
        "M105 13.5c0-2.2 3.8-3.6 9-3.6s8 1.4 8 3.6-3 3.4-8 3.4-9-1.2-9-3.4zM121 12l4-5h4l-1 3h-3l-2 3z",
)
private val SLEIGH_RUNNER = parsePath("M6 25.5h28c3 0 4-2 4-3")
private val SLEIGH_REINS = parsePath("M32 13L54 14.5M70 14L80 13.5M96 13L106 12.5")
private val SLEIGH_ANTLERS = parsePath(
    "M73 9l-1-4M73 7l-2-1M75 9l1-4M76 7l2-1M99 8l-1-4M99 6l-2-1M101 8l1-4M102 6l2-1M125 7l-1-4M125 5l-2-1M127 7l1-4M128 5l2-1",
)
private val SLEIGH_LEGS = parsePath(
    "M56 17l-4 4M59 18l-1 5M65 18l3 4M68 17l4 3M82 16l-4 4M85 17l-1 5M91 17l3 4M94 16l4 3M108 15l-4 4M111 16l-1 5M117 16l3 4M120 15l4 3",
)

private val SLEIGH_NOSE = Color(0xFFff4d4d)
private val SLEIGH_INK_DARK = Color(0xFF030805)
private val SLEIGH_INK_LIGHT = Color(0xFF2f4a38)

private fun DrawScope.stroke(path: Path, ink: Color, width: Float) = drawPath(path, ink, style = Stroke(width, cap = StrokeCap.Round))

/** The design's sleigh sprite (140 x 30): Santa's sleigh behind four reindeer, the lead one with a red nose, in the palette's silhouette ink. */
internal fun DrawScope.drawSleigh(dark: Boolean) {
    val ink = if (dark) SLEIGH_INK_DARK else SLEIGH_INK_LIGHT
    drawPath(SLEIGH_BODY, ink)
    drawCircle(ink, 5f, Offset(22f, 10f))
    drawCircle(ink, 6f, Offset(12f, 10f))
    drawCircle(ink, 3f, Offset(23f, 4f))
    drawPath(SLEIGH_DEER, ink)
    stroke(SLEIGH_RUNNER, ink, 1.3f)
    stroke(SLEIGH_REINS, ink, .8f)
    stroke(SLEIGH_ANTLERS, ink, 1.3f)
    stroke(SLEIGH_LEGS, ink, 1.3f)
    drawCircle(SLEIGH_NOSE, 1.6f, Offset(128f, 10f))
}

private const val FLIGHT_ENTER_X = -80f
private const val FLIGHT_EXIT_MARGIN = 100f
private val FLIGHT_STOPS = floatArrayOf(0f, .25f, .5f, .75f, 1f)
private val FLIGHT_BOB_Y = floatArrayOf(0f, -10f, 6f, -8f, 2f)

/**
 * The top-left corner of a sprite of the design's `hol-fly` at [t] seconds: it crosses a [widthDp]-wide window left to right every [period] seconds
 * (started [startedSec] ago), bobbing a few dp, [top] dp from the window's top.
 */
internal fun flightPosition(t: Float, widthDp: Float, period: Float, startedSec: Float, top: Float): Offset {
    val u = cycle(t, period, startedSec)
    return Offset(FLIGHT_ENTER_X + u * (widthDp + FLIGHT_EXIT_MARGIN - FLIGHT_ENTER_X), top + keyframes(u, FLIGHT_STOPS, FLIGHT_BOB_Y, eased = false))
}

/** Santa's sleigh crossing the window once every 24 s (`hol-fly`: left to right over the window's width, bobbing a few dp, linear). */
internal object SantasSleigh : HolidayParticleEffect {
    override val id = "xmas.sleigh"
    override val label = "Santa's sleigh"
    override val noun = "sleigh"
    override val defaultOn = true

    private const val PERIOD_SEC = 24f
    private const val STARTED_SEC = 6f
    private const val TOP_DP = 8f

    /** The sleigh's top-left corner at [t] seconds in a [widthDp]-wide window. */
    fun position(t: Float, widthDp: Float): Offset = flightPosition(t, widthDp, PERIOD_SEC, STARTED_SEC, TOP_DP)

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField {
        val flights = if (speedScale <= 0f) 0 else 1
        return object : HolidayParticleField {
            override val count = flights

            override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) {
                if (flights == 0) return
                val at = position(t, widthDp)
                scope.withTransform({ translate(at.x, at.y) }) { drawSleigh(dark) }
            }
        }
    }
}
