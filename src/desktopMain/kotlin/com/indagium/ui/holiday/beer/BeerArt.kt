@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.beer

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.skiaCanvas
import com.indagium.ui.holiday.*
import org.jetbrains.skia.Data
import org.jetbrains.skia.svg.SVGDOM
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private fun svg(name: String): SVGDOM {
    val bytes = requireNotNull(BeerPicture::class.java.getResourceAsStream("/holiday/beer/$name.svg")).use { it.readBytes() }
    return SVGDOM(Data.makeFromBytes(bytes))
}

private class BeerLayers(name: String) {
    val base = svg(name)
    val eyes = svg("$name-s-blink")
    val foam = svg("$name-s-dangle")
    val sway = svg("$name-${if (name == "picture") "s-lsway" else "s-float"}")
}

private fun DrawScope.renderSvg(svg: SVGDOM) = drawIntoCanvas { svg.render(it.skiaCanvas) }

private val glow = unitRadial(washStops(0xffc861, .45f))
private val signGold = Color(0xFFF2B33D)
private val signCream = Color(0xFFFFF4DC)
private val lagerGreen = Color(0xFF1D6E2C)

// The sign's and the label's lettering is built once as glyph outlines (BeerText.kt) and drawn in the same transform as the board.
private val signSmallText by lazy { BeerTextPaths.outline("IT'S", BeerTypeface.SIGN, 14f) }
private val signLargeText by lazy { BeerTextPaths.outline("BEER TIME", BeerTypeface.SIGN, 21f) }
private val lagerText by lazy { BeerTextPaths.outline("LAGER", BeerTypeface.SIGN, 7.6f) }

/** The neon colour of the pack: the title-bar sign and the companion's sign share it. */
internal fun beerNeon(green: Boolean, dark: Boolean): Color = when {
    green -> if (dark) Color(0xFFff4d55) else Color(0xFFd11f2a)
    dark -> Color(0xFFff7a3d)
    else -> Color(0xFFd9480f)
}

internal fun neonOpacity(time: Float): Float {
    val phase = time % 3.4f / 3.4f
    return when { phase < .07f -> 1f - phase / .07f * .18f; phase < .11f -> .82f + (phase - .07f) / .04f * .16f; else -> 1f }
}

private fun DrawScope.drawOutline(outline: BeerTextOutline, centerX: Float, baseline: Float, color: Color) {
    withTransform({ translate(centerX - outline.width / 2f, baseline) }) { drawPath(outline.path, color) }
}

private fun DrawScope.signText() {
    drawOutline(signSmallText, 150f, 71f, signGold)
    drawOutline(signLargeText, 150f, 98f, signCream)
}

private fun DrawScope.drawLayers(layers: BeerLayers, t: Float, companion: Boolean) {
    if (!companion) drawUnitEllipse(glow, 140f, 250f, 140f, 130f, .8f + .2f * sin(t))
    renderSvg(layers.base)
    if (!companion) drawOutline(lagerText, 258f, 309.5f, lagerGreen)
    val sway = sin(t * 1.1f)
    withTransform({
        if (companion) translate(0f, sway * 1.5f) else rotate(-1.6f * cos(t * (kotlin.math.PI.toFloat() / 2.8f)), Offset(150f, 0f))
    }) {
        renderSvg(layers.sway)
        if (!companion) signText()
    }
    withTransform({ translate(0f, sway) }) { renderSvg(layers.foam) }
    val blink = if ((t % 5.5f) in 5.25f..5.4f) .08f else 1f
    val eyeY = if (companion) 142f else 262f
    withTransform({ scale(1f, blink, Offset(0f, eyeY)) }) { renderSvg(layers.eyes) }
    val (left, top, bottom) = if (companion) Triple(72f, 100f, 206f) else Triple(84f, 198f, 344f)
    repeat(5) { i ->
        val u = t / (2.8f + i * .28f) + i * .23f
        val phase = u - floor(u)
        drawCircle(Color(0xFFFFF4DC).copy(alpha = .65f), 1.4f + i % 2, Offset(left + i * 20f, bottom - phase * (bottom - top)))
    }
    if (companion) repeat(3) { i ->
        val u = t / (2.5f + i * .3f) + i * .35f
        val phase = u - floor(u)
        drawCircle(Color(0xFFFFF4DC).copy(alpha = (1f - phase) * .8f), 1.5f + i * .4f, Offset(94f + i * 24f, 66f - phase * 22f))
    }
}

internal object BeerPicture : HolidayPicturePainter {
    override val designWidthDp = 300f
    override val designHeightDp = 420f
    private val layers by lazy { BeerLayers("picture") }

    override fun draw(scope: DrawScope, t: Float, dark: Boolean) = with(scope) { drawLayers(layers, t, false) }
}

/**
 * The Mug buddy with a tilted neon "IT'S BEER TIME" sign above it, in one [BEER_COMPANION_WIDTH] x [BEER_COMPANION_HEIGHT] picture (the
 * sign is part of the companion, so the "Mug buddy" switch covers both). The sign flickers like the title-bar neon ([neonOpacity]).
 */
internal class BeerCompanion(private val green: Boolean) : HolidayCompanionPainter {
    override val label = "Mug buddy in the Notes corner"
    override val designWidthDp = BEER_COMPANION_WIDTH
    override val designHeightDp = BEER_COMPANION_HEIGHT
    private val layers by lazy { BeerLayers("companion") }

    override fun draw(scope: DrawScope, t: Float, dark: Boolean) = with(scope) {
        drawNeonSign(beerNeon(green, dark).copy(alpha = neonOpacity(t)))
        withTransform({ translate(0f, BEER_COMPANION_MUG_TOP) }) { drawLayers(layers, t, true) }
    }
}

internal const val BEER_COMPANION_WIDTH = 240f
internal const val BEER_COMPANION_HEIGHT = 316f
private const val BEER_COMPANION_MUG_TOP = 76f

// The neon sign: a rounded tube outline and the lettering, tilted about its centre, with a soft glow (stacked wider, fainter strokes).
private const val SIGN_TILT_DEG = -8f
private val signCenter = Offset(120f, 44f)
private val signBox = Size(204f, 46f)
private const val SIGN_TEXT_SHARE = .86f
private val neonText by lazy { BeerTextPaths.outline("IT'S BEER TIME", BeerTypeface.ROUNDED, 17f, letterSpacing = 2.2f) }

private fun DrawScope.drawNeonSign(neon: Color) {
    val topLeft = Offset(signCenter.x - signBox.width / 2f, signCenter.y - signBox.height / 2f)
    val corner = CornerRadius(12f)
    withTransform({ rotate(SIGN_TILT_DEG, signCenter) }) {
        drawRoundRect(neon.copy(alpha = neon.alpha * .08f), topLeft, signBox, corner)
        listOf(11f to .08f, 6f to .14f, 3.4f to .24f).forEach { (width, alpha) ->
            drawRoundRect(neon.copy(alpha = neon.alpha * alpha), topLeft, signBox, corner, style = Stroke(width))
        }
        drawRoundRect(neon, topLeft, signBox, corner, style = Stroke(1.8f))
        val fit = minOf(1f, signBox.width * SIGN_TEXT_SHARE / neonText.width)
        withTransform({
            translate(signCenter.x - neonText.width * fit / 2f, signCenter.y + 5.6f * fit)
            scale(fit, fit, Offset.Zero)
        }) {
            drawPath(neonText.path, neon.copy(alpha = neon.alpha * .3f), style = Stroke(5f, join = StrokeJoin.Round))
            drawPath(neonText.path, neon)
        }
    }
}

private data class BeerBubble(val x: Float, val phase: Float, val period: Float, val radius: Float)

private class BeerBubbleField(private val bubbles: List<BeerBubble>, private val green: Boolean) : HolidayParticleField {
    override val count get() = bubbles.size

    override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) = with(scope) {
        bubbles.forEach { bubble ->
            val u = t / bubble.period + bubble.phase
            val p = u - floor(u)
            val alpha = minOf(1f, p * 8f, (1f - p) * 5f) * if (dark) .7f else .42f
            val c = if (dark) Color(0xFFfff4dc) else if (green) Color(0xFF13792f) else Color(0xFFa8560a)
            val center = Offset(bubble.x * widthDp + sin(p * 6f) * 5f, heightDp - p * minOf(heightDp, 540f))
            drawCircle(c.copy(alpha = alpha), bubble.radius, center, style = Stroke(1.2f))
            drawCircle(Color.White.copy(alpha = alpha * .55f), bubble.radius * .25f, center + Offset(-bubble.radius * .3f, -bubble.radius * .3f))
        }
    }
}

internal class BeerBubbles(override val id: String, private val green: Boolean) : HolidayParticleEffect {
    override val label = "Rising bubbles"
    override val noun = "bubbles"
    override val defaultOn = true

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField {
        if (speedScale <= 0f) return BeerBubbleField(emptyList(), green)
        val rng = Random(seed)
        return BeerBubbleField(List((30 * countScale).roundToInt().coerceAtLeast(0)) {
            BeerBubble(rng.nextFloat(), rng.nextFloat(), (7f + rng.nextFloat() * 6f) / speedScale, 2f + rng.nextFloat() * 3f)
        }, green)
    }
}
