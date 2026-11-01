// The design's ambient layer has two low fog banks, one ghost and two small bats.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.manor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.holiday.HolidayFlashEffect
import com.indagium.ui.holiday.HolidayParticleEffect
import com.indagium.ui.holiday.HolidayParticleField
import com.indagium.ui.holiday.drawUnitEllipse
import com.indagium.ui.holiday.hex
import com.indagium.ui.holiday.parsePath
import com.indagium.ui.holiday.unitRadial
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

internal data class ManorGhostParticle(val xFrac: Float, val size: Float, val periodSec: Float, val phase: Float, val swayPhase: Float)

internal data class ManorFogParticle(val xFrac: Float, val yFrac: Float, val size: Float, val periodSec: Float, val phase: Float)

internal data class ManorParticlePosition(val x: Float, val y: Float, val alpha: Float, val scale: Float = 1f)

private fun fract(n: Float): Float = n - floor(n)

internal fun manorGhostAt(g: ManorGhostParticle, t: Float, width: Float, height: Float): ManorParticlePosition {
    val y = height * .766f + 5f * sin((t / g.periodSec + g.phase) * 2f * PI.toFloat())
    return ManorParticlePosition(g.xFrac * width, y, .95f)
}

internal fun manorFogAt(f: ManorFogParticle, t: Float, width: Float, height: Float): ManorParticlePosition {
    val drift = sin((t / f.periodSec + f.phase) * 2f * PI.toFloat())
    return ManorParticlePosition(f.xFrac * width + drift * width * .09f, height * f.yFrac, .13f)
}

private val fogDark = unitRadial(arrayOf(0f to hex(0xbed7c8, .13f), .55f to hex(0xbed7c8, .07f), 1f to Color.Transparent))
private val fogLight = unitRadial(arrayOf(0f to hex(0xffffff, .28f), .55f to hex(0xffffff, .14f), 1f to Color.Transparent))
private val ghostBody = parsePath("M6 64V28a22 22 0 0 1 44 0v36l-5.5-5-5.5 5-5.5-5-5.5 5-5.5-5-5.5 5-5.5-5z")
private val ghostArms = parsePath("M7 40c-6 2-9 6-8 11 4 0 7-3 9-5zM49 40c6 2 9 6 8 11-4 0-7-3-9-5z")
private val bat = parsePath(
    "M20 5c1.6 0 2.6 1 2.6 1l1-2.4.7 3C26.6 5 30.4 3.6 36 6c-2.6 1.2-3.8 3.6-3.8 6.2-2.6-1.2-5.2-1.2-7.2 1.4-1.6-1.2-3" +
        ".4-1.8-5-1.8s-3.4.6-5 1.8c-2-2.6-4.6-2.6-7.2-1.4C7.8 9.6 6.6 7.2 4 6c5.6-2.4 9.4-1 11.7.6l.7-3 1 2.4S18.4 5 20 5z",
)
private val bolt = parsePath("M74 0L37 90h25L27 190 104 77H76L92 0z")
private val ghostFill = Brush.linearGradient(0f to hex(0xffffff), .6f to hex(0xf1f6f4), 1f to hex(0xc9d7d2), start = Offset(6f, 6f), end = Offset(50f, 64f))

private class ManorCreaturesField(private val creatureCount: Int, private val seed: Int, private val speed: Float) : HolidayParticleField {
    override val count: Int get() = creatureCount

    override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) = with(scope) {
        if (creatureCount == 0) return@with
        val p = manorGhostAt(ManorGhostParticle(.806f, 60f, 3.6f / speed, 0f, 0f), t, widthDp, heightDp)
        withTransform({ translate(p.x, p.y) }) {
            drawPath(ghostBody, ghostFill, alpha = .95f); drawPath(ghostArms, ghostFill)
            for (x in listOf(21f, 35f)) drawOval(hex(0x1d2620), Offset(x - 3.6f, 23.2f), Size(7.2f, 9.6f))
            for (x in listOf(22.2f, 36.2f)) drawCircle(Color.White, 1.3f, Offset(x, 26f))
            drawOval(hex(0x1d2620), Offset(24.8f, 35f), Size(6.4f, 8f))
            for (x in listOf(15f, 41f)) drawOval(hex(0xff9fb2, .6f), Offset(x - 3.6f, 33.2f), Size(7.2f, 3.6f))
        }
        val random = Random(seed)
        repeat(creatureCount - 1) { i ->
            val y = 4f + random.nextFloat() * minOf(126f, heightDp * .15f)
            val period = (22f + random.nextFloat() * 8f) / speed
            val x = (1f - fract(t / period + i * .47f)) * (widthDp + 40f) - 20f
            val scale = .5f + random.nextFloat() * .2f
            withTransform({ translate(x, y); scale(scale, scale, Offset.Zero) }) { drawPath(bat, manorSceneColors(dark).bat) }
        }
    }
}

private class ManorFogField(private val banks: List<ManorFogParticle>) : HolidayParticleField {
    override val count get() = banks.size

    override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) = with(scope) {
        banks.forEach { bank ->
            val p = manorFogAt(bank, t, widthDp, heightDp)
            drawUnitEllipse(if (dark) fogDark else fogLight, p.x, p.y, widthDp * .34f, 70f)
        }
    }
}

internal object ManorGhosts : HolidayParticleEffect {
    override val id = "manor.ghosts"
    override val label = "Ghost and bats"
    override val noun = "ghost and bats"
    override val defaultOn = true
    override val behindContent = true

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField =
        ManorCreaturesField(if (speedScale <= 0f) 0 else (3f * countScale).roundToInt().coerceIn(0, 3), seed, speedScale)
}

internal object ManorFog : HolidayParticleEffect {
    override val id = "manor.fog"
    override val label = "Fog"
    override val noun = "fog banks"
    override val defaultOn = true

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField {
        if (speedScale <= 0f) return ManorFogField(emptyList())
        val banks = listOf(ManorFogParticle(.2375f, .984f, 500f, 19f / speedScale, 0f), ManorFogParticle(.7125f, .994f, 500f, 24f / speedScale, .46f))
        return ManorFogField(banks.take((2f * countScale).roundToInt().coerceIn(0, 2)))
    }
}

internal object ManorLightning : HolidayFlashEffect {
    override val id = "manor.lightning"
    override val label = "Lightning"
    override val noun = "lightning"
    override val defaultOn = true

    override fun draw(scope: DrawScope, alpha: Float, widthDp: Float, heightDp: Float, dark: Boolean) = with(scope) {
        val tint = if (dark) hex(0xe3f6ea) else Color.White
        drawRect(tint.copy(alpha = alpha), Offset.Zero, Size(widthDp, heightDp))
        withTransform({ translate(widthDp - 105f, 28f); scale(.72f, .72f, Offset.Zero) }) { drawPath(bolt, tint.copy(alpha = alpha)) }
    }
}
