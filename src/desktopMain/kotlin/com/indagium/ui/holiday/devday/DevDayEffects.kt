// Counts, sizes and periods are the design canvas' values, not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.devday

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toArgb
import com.indagium.ui.holiday.HolidayParticleEffect
import com.indagium.ui.holiday.HolidayParticleField
import com.indagium.ui.holiday.cycle
import com.indagium.ui.holiday.holidayTypeface
import com.indagium.ui.holiday.snow.span
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import kotlin.math.roundToInt
import kotlin.random.Random

// Programmers' Day's window-wide effects: binary digits raining down the window and CRT scanlines over it.

private val BIT_DARK = Color(0xFF39ff6a)
private val BIT_LIGHT = Color(0xFF1f8a3c)

private class Bit(val x: Float, val size: Float, val alpha: Float, val period: Float, val phase: Float, val digit: String, val font: Font)

/** Bold monospace 0s and 1s of 10 to 14 dp falling the height of the window in 6 to 12 s (`hol-drop`, linear), 60 of them. */
internal object BinaryRain : HolidayParticleEffect {
    override val id = "devday.rain"
    override val label = "Binary rain"
    override val noun = "binary"
    override val defaultOn = true

    private const val BASE_COUNT = 60
    private const val DROP_MARGIN = 40f
    private const val BASELINE = .85f

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField {
        val random = Random(seed)
        val typeface = holidayTypeface(listOf("monospace"), 700, false)
        val bits = List(if (speedScale <= 0f) 0 else (BASE_COUNT * countScale).roundToInt().coerceAtLeast(0)) {
            val size = span(10f, 14f).pick(random)
            Bit(
                random.nextFloat(), size, span(.2f, .6f).pick(random), span(6f, 12f).pick(random) / speedScale, random.nextFloat(),
                if (random.nextFloat() < .5f) "0" else "1", Font(typeface, size).apply { isSubpixel = true },
            )
        }
        val paint = Paint().apply { isAntiAlias = true }
        return object : HolidayParticleField {
            override val count = bits.size

            override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) = with(scope) {
                val ink = if (dark) BIT_DARK else BIT_LIGHT
                drawIntoCanvas { canvas ->
                    bits.forEach { b ->
                        val u = cycle(t, b.period, b.phase * b.period)
                        paint.color = ink.copy(alpha = b.alpha).toArgb()
                        val y = -DROP_MARGIN + u * (heightDp + 2f * DROP_MARGIN) + b.size * BASELINE
                        canvas.skiaCanvas.drawString(b.digit, b.x * widthDp, y, b.font, paint)
                    }
                }
            }
        }
    }
}

/**
 * CRT scanlines: a 1 dp line every 3 dp over the whole window, black at .22 (dark) or .045 (light), as the design's `.crt`. Static, so it needs no
 * frame clock (the design keeps it independent of the other effects).
 */
internal object CrtScanlines : HolidayParticleEffect {
    override val id = "devday.scanlines"
    override val label = "CRT scanlines"
    override val noun = "scanlines"
    override val defaultOn = true
    override val moves get() = false

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField = object : HolidayParticleField {
        override val count = 1

        override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) = with(scope) {
            val line = Color.Black.copy(alpha = if (dark) .22f else .045f)
            var y = 0f
            while (y < heightDp) {
                drawRect(line, Offset(0f, y), Size(widthDp, 1f))
                y += 3f
            }
        }
    }
}
