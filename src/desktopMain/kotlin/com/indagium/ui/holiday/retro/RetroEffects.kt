// Counts, sizes, periods and the car's artwork are the design canvas' values, not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.retro

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.holiday.HolidayParticleEffect
import com.indagium.ui.holiday.HolidayParticleField
import com.indagium.ui.holiday.cycle
import com.indagium.ui.holiday.keyframes
import com.indagium.ui.holiday.parsePath
import com.indagium.ui.holiday.snow.flightPosition
import com.indagium.ui.holiday.snow.span
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// Retro's window-wide effects: shooting stars streaking across the window, a neon car driving along its top edge, and VHS scanlines over it.

private val STREAK_CYAN_DARK = Color(0xFF00f0ff)
private val STREAK_CYAN_LIGHT = Color(0xFF0097b2)

private class Streak(val x: Float, val y: Float, val period: Float, val phase: Float)

private const val STREAK_LENGTH = 90f
private const val STREAK_ANGLE_DEG = 155f
private const val STREAK_REACH_X = -560f
private const val STREAK_REACH_Y = 260f
private const val STREAK_FLIGHT = .22f
private const val STREAK_DESIGN_WIDTH = 1600f
private val STREAK_STOPS = floatArrayOf(0f, .04f, STREAK_FLIGHT, 1f)
private val STREAK_OPACITY = floatArrayOf(0f, 1f, 0f, 0f)
private val STREAK_TRAVEL = floatArrayOf(0f, 0f, 1f, 1f)

/** A streak's travel (0..1 of its flight) and opacity at [u] in its cycle (`hol-streak`: fades in by 4 %, is gone by 22 % and rests until the end). */
internal fun streakFrame(u: Float): Pair<Float, Float> =
    keyframes(u, STREAK_STOPS, STREAK_TRAVEL, eased = false) to keyframes(u, STREAK_STOPS, STREAK_OPACITY, eased = false)

/** Nine shooting stars, a 90 x 2 dp streak with a white head and a cyan body flying 560 left and 260 down over 22 % of a 5 to 9 s cycle. */
internal object ShootingStars : HolidayParticleEffect {
    override val id = "retro.stars"
    override val label = "Shooting stars"
    override val noun = "shooting stars"
    override val defaultOn = true

    private const val BASE_COUNT = 9

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField {
        val random = Random(seed)
        val streaks = List(if (speedScale <= 0f) 0 else (BASE_COUNT * countScale).roundToInt().coerceAtLeast(0)) {
            val x = span(500f, 1750f).pick(random) / STREAK_DESIGN_WIDTH
            Streak(x, span(-20f, 320f).pick(random), span(5f, 9f).pick(random) / speedScale, random.nextFloat())
        }
        val heading = Offset(cos(STREAK_ANGLE_DEG * PI.toFloat() / 180f), sin(STREAK_ANGLE_DEG * PI.toFloat() / 180f))
        return object : HolidayParticleField {
            override val count = streaks.size

            override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) = with(scope) {
                val cyan = if (dark) STREAK_CYAN_DARK else STREAK_CYAN_LIGHT
                streaks.forEach { s ->
                    val (travel, opacity) = streakFrame(cycle(t, s.period, s.phase * s.period))
                    if (opacity <= 0f) return@forEach
                    val centre = Offset(s.x * widthDp + STREAK_LENGTH / 2f + STREAK_REACH_X * travel, s.y + 1f + STREAK_REACH_Y * travel)
                    val head = centre + heading * (STREAK_LENGTH / 2f)
                    val tail = centre - heading * (STREAK_LENGTH / 2f)
                    val brush = Brush.linearGradient(
                        colorStops = arrayOf(0f to cyan.copy(alpha = 0f), .6f to cyan, 1f to Color.White), start = tail, end = head,
                    )
                    drawLine(brush, tail, head, strokeWidth = 2f, cap = StrokeCap.Round, alpha = opacity)
                }
            }
        }
    }
}

private val CAR_LINES = parsePath("M0 12h14M2 17h12M4 22h10")
private val CAR_BODY = parsePath("M20 21h72l-3-6-18-3-10-7H40l-12 7-8 3z")
private val CAR_WINDOW = parsePath("M42 7l-8 6h26l-8-6z")
private val CAR_HEADLIGHT = parsePath("M90 15h4")
private val CAR_TAIL_LIGHT = parsePath("M21 16h3")
private val CAR_CYAN = Color(0xFF00f0ff)
private val CAR_NEON = Color(0xFFff4fd8)

private fun DrawScope.line(path: Path, color: Color, width: Float, alpha: Float = 1f) =
    drawPath(path, color, alpha, Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))

/** The design's neon car sprite (96 x 28): speed lines, a dark body with a magenta outline, a cyan window, two cyan wheels and the two lights. */
internal fun DrawScope.drawNeonCar() {
    line(CAR_LINES, CAR_CYAN, 1.2f, .7f)
    drawPath(CAR_BODY, Color(0xFF1a0838))
    line(CAR_BODY, CAR_NEON, 1.6f)
    drawPath(CAR_WINDOW, CAR_CYAN, .35f)
    listOf(34f, 78f).forEach { x ->
        drawCircle(Color(0xFF0a0418), 4.5f, Offset(x, 22f))
        drawCircle(CAR_CYAN, 4.5f, Offset(x, 22f), style = Stroke(1.4f))
    }
    line(CAR_HEADLIGHT, Color(0xFFffe14d), 2.4f)
    line(CAR_TAIL_LIGHT, Color(0xFFff3b3b), 2.4f)
}

/** A neon car crossing the window every 13 s along its top edge (`hol-fly`). */
internal object NeonCar : HolidayParticleEffect {
    override val id = "retro.car"
    override val label = "Neon car"
    override val noun = "car"
    override val defaultOn = true

    private const val PERIOD_SEC = 13f
    private const val STARTED_SEC = 3f
    private const val TOP_DP = 1f

    /** The car's top-left corner at [t] seconds in a [widthDp]-wide window. */
    fun position(t: Float, widthDp: Float): Offset = flightPosition(t, widthDp, PERIOD_SEC, STARTED_SEC, TOP_DP)

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField {
        val cars = if (speedScale <= 0f) 0 else 1
        return object : HolidayParticleField {
            override val count = cars

            override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) {
                if (cars == 0) return
                val at = position(t * speedScale, widthDp)
                scope.withTransform({ translate(at.x, at.y) }) { drawNeonCar() }
            }
        }
    }
}

/** VHS scanlines: a 1 dp magenta line every 3 dp over the window (the design's `.crt`), faint in the light palette. Static: no frame clock. */
internal object VhsScanlines : HolidayParticleEffect {
    override val id = "retro.scanlines"
    override val label = "VHS scanlines"
    override val noun = "scanlines"
    override val defaultOn = true
    override val moves get() = false

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField = object : HolidayParticleField {
        override val count = 1

        override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) = with(scope) {
            val line = (if (dark) Color(0xFFff4fd8) else Color(0xFFb8148e)).copy(alpha = if (dark) .07f else .04f)
            var y = 0f
            while (y < heightDp) {
                drawRect(line, Offset(0f, y), Size(widthDp, 1f))
                y += 3f
            }
        }
    }
}
