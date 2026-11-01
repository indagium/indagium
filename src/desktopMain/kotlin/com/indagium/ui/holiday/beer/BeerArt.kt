@file:Suppress("MagicNumber")
package com.indagium.ui.holiday.beer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.holiday.*
import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Paint
import org.jetbrains.skia.svg.SVGDOM
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.random.Random
import kotlin.math.roundToInt

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
private val signTypeface = FontMgr.default.matchFamilyStyle("Georgia", FontStyle.BOLD)
    ?: FontMgr.default.matchFamilyStyle("serif", FontStyle.BOLD)
private val signSmallFont = Font(signTypeface, 14f)
private val signLargeFont = Font(signTypeface, 21f)
private val bottleFont = Font(signTypeface, 7.6f)
private val signPaint = Paint().apply { color = 0xFFFFF4DC.toInt() }
private val signGoldPaint = Paint().apply { color = 0xFFF2B33D.toInt() }
private val bottlePaint = Paint().apply { color = 0xFF1D6E2C.toInt() }
private val shelfTypeface = FontMgr.default.matchFamilyStyle("Arial", FontStyle.BOLD) ?: signTypeface
private val shelfFont = Font(shelfTypeface, 11f)
private fun neonOpacity(time: Float): Float {
    val phase = time % 3.4f / 3.4f
    return when { phase < .07f -> 1f - phase / .07f * .18f; phase < .11f -> .82f + (phase - .07f) / .04f * .16f; else -> 1f }
}
private fun DrawScope.signText() = drawIntoCanvas { canvas ->
    canvas.skiaCanvas.drawString("IT'S", 150f - signSmallFont.measureTextWidth("IT'S") / 2f, 71f, signSmallFont, signGoldPaint)
    canvas.skiaCanvas.drawString("BEER TIME", 150f - signLargeFont.measureTextWidth("BEER TIME") / 2f, 98f, signLargeFont, signPaint)
}
private fun DrawScope.drawLayers(layers: BeerLayers, t: Float, companion: Boolean) {
    if (!companion) drawUnitEllipse(glow, 140f, 250f, 140f, 130f, .8f + .2f * sin(t))
    renderSvg(layers.base)
    if (!companion) drawIntoCanvas { canvas ->
        canvas.skiaCanvas.drawString("LAGER", 258f - bottleFont.measureTextWidth("LAGER") / 2f, 309.5f, bottleFont, bottlePaint)
    }
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
internal object BeerCompanion : HolidayCompanionPainter {
    override val label = "Mug buddy in the Notes corner"
    override val designSizeDp = 240f
    private val layers by lazy { BeerLayers("companion") }
    override fun draw(scope: DrawScope, t: Float, dark: Boolean) = with(scope) { drawLayers(layers, t, true) }
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
private data class BeerSceneLayout(override val slot: Rect) : HolidaySceneLayout {
    override val elementBounds = listOf(slot)
}
private val shelfBottle = parsePath("M0 25v-9q0-2.5 2.5-3.5v-3.5h3v3.5q2.5 1 2.5 3.5v9z")
private val shelfMugFoam = parsePath("M0 0c0-2 2-2.6 3-1.6 1-1.6 3.6-1.6 4.4 0 1.2-1 3.4 0 3.4 1.6z")
private val shelfMugHandle = parsePath("M0 0h2a2 2 0 0 1 2 2v1.6a2 2 0 0 1-2 2H0")
internal class BeerScene(private val green: Boolean) : HolidayScenePainter {
    override val animated = true
    override fun layout(slot: Rect, vertical: Boolean): HolidaySceneLayout = BeerSceneLayout(slot)
    override fun draw(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean) = drawFrame(scope, layout, dark, HOLIDAY_STILL_TIME)
    override fun drawFrame(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean, time: Float) = with(scope) {
        val slot = layout.slot
        val scale = minOf(slot.width / 560f, slot.height / 30f)
        if (scale <= 0f) return@with
        val wood = if (green) { if (dark) Color(0xFF24402c) else Color(0xFF8fae96) }
            else if (dark) Color(0xFF4a2f16) else Color(0xFFa8743e)
        val metal = if (green) Color(0xFFc9cdd2) else if (dark) Color(0xFF6e4622) else Color(0xFFc79457)
        val neon = (if (green) { if (dark) Color(0xFFff4d55) else Color(0xFFd11f2a) }
            else if (dark) Color(0xFFff7a3d) else Color(0xFFd9480f)).copy(alpha = neonOpacity(time))
        withTransform({ translate(slot.center.x - 280f * scale, slot.bottom - 30f * scale); scale(scale, scale, Offset.Zero) }) {
            drawRect(wood, Offset(0f, 25f), Size(560f, 5f))
            listOf(20f, 36f, 484f, 500f, 516f).forEachIndexed { index, x ->
                withTransform({ translate(x, 0f) }) {
                    drawPath(shelfBottle,
                        if (index % 2 == 0) Color(0xFF2a8a38) else Color(0xFF1d6e2c))
                    drawOval(Color(0xFFf4f6f3), Offset(1.2f, 17.4f), Size(5.6f, 4.4f))
                    drawCircle(Color(0xFFe1252e), .8f, Offset(4f, 10.6f))
                    drawRect(Color(0xFFc9cdd2), Offset(2.2f, 7.6f), Size(3.6f, 1.6f))
                }
            }
            listOf(70f, 94f).forEach { x ->
                drawRoundRect(Color(0xFFf2b33d), Offset(x, 15f), Size(11f, 10f), androidx.compose.ui.geometry.CornerRadius(1.2f))
                withTransform({ translate(x - .5f, 15.5f) }) { drawPath(shelfMugFoam, Color(0xFFfff4dc)) }
                withTransform({ translate(x + 11f, 17.5f) }) { drawPath(shelfMugHandle, Color(0xFFfff4dc), style = Stroke(1.2f)) }
            }
            drawRoundRect(neon, Offset(220f, 6f), Size(156f, 18f), androidx.compose.ui.geometry.CornerRadius(5f), style = Stroke(1.2f))
            drawIntoCanvas { canvas ->
                val message = "IT'S BEER TIME"
                val paint = Paint().apply { color = neon.toArgb() }
                try {
                    canvas.skiaCanvas.drawString(message, 298f - shelfFont.measureTextWidth(message) / 2f, 19f, shelfFont, paint)
                } finally { paint.close() }
            }
            drawCircle(Color.White, 9f, Offset(400f, 15f))
            drawCircle(metal, 9f, Offset(400f, 15f), style = Stroke(1.4f))
            drawLine(wood, Offset(400f, 15f), Offset(400f, 8.5f), 1.6f)
            drawLine(wood, Offset(400f, 15f), Offset(402.3f, 18.9f), 1.6f)
        }
    }
}
