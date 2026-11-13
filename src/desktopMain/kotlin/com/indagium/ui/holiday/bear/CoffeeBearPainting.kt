@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.bear

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import com.indagium.ui.holiday.HOLIDAY_STILL_TIME
import com.indagium.ui.holiday.HolidayCompanionPainter
import com.indagium.ui.holiday.HolidayPicturePainter
import com.indagium.ui.holiday.parsePath
import org.jetbrains.skia.Image
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

private val painting by lazy {
    val bytes = requireNotNull(CoffeeBearTheme::class.java.getResourceAsStream("/holiday/bear/painting.png")).use { it.readBytes() }
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
}
private val steam1 = parsePath("M172 168c-5 -7 5 -11 0 -18s5 -11 0 -16")
private val steam2 = parsePath("M181 166c-4 -6 4 -9 0 -15s4 -9 0 -13")
private val companionSteam1 = parsePath("M147 178c-4.5 -6.3 4.5 -9.9 0 -16.2s4.5 -9.9 0 -14.4")
private val companionSteam2 = parsePath("M155.1 176.2c-3.6 -5.4 3.6 -8.1 0 -13.5s3.6 -8.1 0 -11.7")
private val snowbank = parsePath("M0 366Q80 344 150 356T300 350V420H0z")
private val blink = parsePath("M-3 0q3 1.6 6 0")

private data class SnowSpot(val x: Float, val y: Float, val radius: Float, val period: Float, val delay: Float)

private val snow = listOf(SnowSpot(24f, 30f, 2.2f, 6f, 0f), SnowSpot(62f, 90f, 1.6f, 7.5f, -2.5f), SnowSpot(98f, 20f, 2f, 5.2f, -4f),
    SnowSpot(226f, 60f, 1.8f, 6f, 0f), SnowSpot(262f, 120f, 2.4f, 7.5f, -2.5f), SnowSpot(284f, 30f, 1.5f, 5.2f, -4f),
    SnowSpot(40f, 200f, 1.6f, 6f, 0f), SnowSpot(268f, 240f, 1.8f, 7.5f, -2.5f), SnowSpot(18f, 300f, 2f, 5.2f, -4f), SnowSpot(286f, 300f, 1.6f, 6f, 0f))

/** The PSD canvas the crops below are measured in. The bundled PNG is a smaller export of it, drawn back at this size. */
private val PSD_CANVAS = IntSize(1668, 2388)

/** Crops in the 1668 x 2388 PSD canvas; the picture includes the complete authored feet. */
private fun DrawScope.paintBear(x: Float, y: Float, w: Float, h: Float, sourceHeight: Float) {
    withTransform({ translate(x, y); scale(w / 911f, h / sourceHeight, Offset.Zero); translate(-424f, -494f) }) {
        drawImage(painting, dstSize = PSD_CANVAS)
    }
}

private fun DrawScope.drawSteam(time: Float, companion: Boolean) {
    val paths = if (companion) listOf(companionSteam1, companionSteam2) else listOf(steam1, steam2)
    paths.forEachIndexed { i, path ->
        val still = time == HOLIDAY_STILL_TIME
        val p = ((time + if (i == 1) 1.7f else 0f) % 3.4f) / 3.4f
        val alpha = if (still) 1f else if (p < .35f) p / .35f else (1f - p) / .65f
        withTransform({ translate(0f, if (still) 0f else 6f - p * 26f) }) {
            val ink = Color.White.copy(alpha = alpha.coerceIn(0f, 1f) * if (i == 0) .8f else .7f)
            drawPath(path, ink, style = Stroke(if (i == 0) 3f else 2.4f, cap = StrokeCap.Round))
        }
    }
}

private fun DrawScope.drawLids(time: Float, companion: Boolean) {
    val p = time % 5.2f / 5.2f
    if (time == 0f || p < .94f || p >= .98f) return
    val y = if (companion) 66.5f else 82f
    val eyes = if (companion) listOf(103f, 142f) else listOf(137f, 168.5f)
    eyes.forEach { x ->
        drawOval(Color(0xFFefe5bb), Offset(x - 4.4f, y - 4f), Size(8.8f, 8f))
        withTransform({ translate(x, y + .4f) }) { drawPath(blink, Color(0xFF2b2b2b), style = Stroke(1.4f, cap = StrokeCap.Round)) }
    }
}

internal object CoffeeBearPicture : HolidayPicturePainter {
    override val designWidthDp = 300f
    override val designHeightDp = 420f

    override fun draw(scope: DrawScope, t: Float, dark: Boolean) = with(scope) {
        clipRect(0f, 0f, 300f, 420f) {
            val pulse = .9f + .05f * cos(t * 2f * PI.toFloat() / 3.2f)
            val halo = Brush.radialGradient(
                0f to Color(0xFFc6e2ef).copy(alpha = pulse), .7f to Color(0xFFd6ecf5).copy(alpha = .6f), 1f to Color.Transparent,
                center = Offset(150f, 180f), radius = 140f,
            )
            drawCircle(halo, 140f, Offset(150f, 180f))
            snow.forEach { f ->
                val phase = if (t == HOLIDAY_STILL_TIME) {
                    0f
                } else { val p = (t + f.delay) / f.period; p - floor(p) }
                drawCircle(Color.White.copy(alpha = .9f), f.radius, Offset(f.x, (f.y + phase * 420f) % 420f))
            }
            drawPath(snowbank, Brush.verticalGradient(listOf(Color.White, Color(0xFFd6eaf3)), startY = 350f, endY = 420f))
            drawOval(Color(0xFF8fb6c9).copy(alpha = .4f), Offset(78f, 384f), Size(144f, 16f))
            paintBear(54f, 30f, 193f, 360f, 1740f)
            drawSteam(t, false)
            drawLids(t, false)
        }
    }
}

internal object CoffeeBearCompanion : HolidayCompanionPainter {
    override val label = "Coffee bear in the Notes corner"
    override val designWidthDp = 240f
    override val designHeightDp = 240f

    override fun draw(scope: DrawScope, t: Float, dark: Boolean) = with(scope) {
        clipRect(0f, 0f, 240f, 240f) {
            drawCircle(Color(0xFFd9eef7).copy(alpha = .55f), 110f, Offset(122f, 128f))
            paintBear(0f, 2f, 240f, 237f, 910f)
            drawSteam(t, true)
            drawLids(t, true)
        }
    }
}
