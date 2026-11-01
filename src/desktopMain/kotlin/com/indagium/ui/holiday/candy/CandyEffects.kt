@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.candy

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.holiday.*
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

private data class Sweet(val x: Float, val phase: Float, val period: Float, val angle: Float, val kind: Int)

private class Sweets(private val sweets: List<Sweet>) : HolidayParticleField {
    override val count get() = sweets.size

    override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) = with(scope) {
        sweets.forEach { sweet ->
            val u0 = t / sweet.period + sweet.phase
            val u = u0 - floor(u0)
            val x = sweet.x * widthDp
            drawSmallSweet(sweet, x, u * (heightDp + 40f) - 20f, sweet.angle + u * 360f)
        }
    }
}

private fun DrawScope.drawSmallSweet(sweet: Sweet, x: Float, y: Float, angle: Float, alpha: Float = 1f) {
    withTransform({ translate(x, y); rotate(angle, Offset.Zero); scale(.20f, .20f, Offset.Zero) }) {
        if (alpha >= 1f) drawSweetGlyph(sweet) else drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(-40f, -30f, 40f, 75f), Paint().apply { this.alpha = alpha })
            drawSweetGlyph(sweet)
            canvas.restore()
        }
    }
}

private fun DrawScope.drawSweetGlyph(sweet: Sweet) {
    if (sweet.kind == 0) {
        drawCandyCorn()
    } else if (sweet.kind == 1) {
        drawWrappedCandy()
    } else {
        drawLine(Color(0xFFE9E1D4), Offset.Zero, Offset(0f, 65f), 8f)
        drawCircle(Color(0xFFEC4899), 24f, Offset.Zero)
        drawCircle(Color.White.copy(alpha = .8f), 8f, Offset.Zero)
    }
}

private class LandingSweets(private val sweets: List<Sweet>) : HolidayLandingField {
    override val count get() = sweets.size

    override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) = Unit

    override fun drawLanding(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean, anchors: List<Rect>) = with(scope) {
        val visible = anchors.filter { it.width >= 30f && it.right > 0f && it.left < widthDp && it.top >= 10f && it.top < heightDp }
        if (visible.isEmpty()) return@with
        sweets.forEachIndexed { i, sweet ->
            val anchor = visible[i % visible.size]
            val x = (anchor.left + anchor.width * (.15f + .7f * sweet.x)).coerceIn(0f, widthDp)
            val u0 = t / sweet.period + sweet.phase
            val u = u0 - floor(u0)
            if (u < .8f) drawSmallSweet(sweet, x, anchor.top - 3f, sweet.angle * .15f, ((.8f - u) / .15f).coerceIn(0f, 1f))
        }
    }
}

private fun spawnSweets(seed: Int, countScale: Float, speedScale: Float, base: Int, landing: Boolean): HolidayParticleField {
    if (speedScale <= 0f) return if (landing) LandingSweets(emptyList()) else Sweets(emptyList())
    val rng = Random(seed)
    val sweets = List((base * countScale).roundToInt().coerceAtLeast(0)) { i ->
        Sweet(rng.nextFloat(), rng.nextFloat(), (if (landing) 3.5f else 9f + rng.nextFloat() * 6f) / speedScale, rng.nextFloat() * 360f, i % 3)
    }
    return if (landing) LandingSweets(sweets) else Sweets(sweets)
}

internal object CandyRain : HolidayParticleEffect {
    override val id = "candy.rain"
    override val label = "Candy rain"
    override val noun = "candy"
    override val defaultOn = true

    override fun spawn(seed: Int, countScale: Float, speedScale: Float) = spawnSweets(seed, countScale, speedScale, 26, false)
}

internal object CandyLanding : HolidayLandingEffect {
    override val id = "candy.landing"
    override val label = "Candy lands on tabs and tiles"
    override val noun = "landed sweets"
    override val defaultOn = true

    override fun spawn(seed: Int, countScale: Float, speedScale: Float) = spawnSweets(seed, countScale, speedScale, 12, true)
}

internal object CandyLightning : HolidayFlashEffect {
    override val id = "candy.lightning"
    override val label = "Lightning"
    override val noun = "lightning"
    override val defaultOn = false

    override fun draw(scope: DrawScope, alpha: Float, widthDp: Float, heightDp: Float, dark: Boolean) = with(scope) {
        drawRect((if (dark) Color(0xFFFFF6C4) else Color.White).copy(alpha = alpha), Offset.Zero, Size(widthDp, heightDp))
    }
}
