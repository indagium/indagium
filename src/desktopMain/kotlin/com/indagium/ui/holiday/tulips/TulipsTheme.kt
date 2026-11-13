@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.tulips

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.model.HolidayPack
import com.indagium.ui.holiday.*
import com.indagium.ui.theme
import java.time.LocalDateTime
import java.time.MonthDay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** A personal, anytime theme built around the supplied painting. */
internal object TulipsTheme : HolidayTheme {
    override val pack = HolidayPack.TULIPS
    override val label = "Tulips"
    override val occasion = "Any time"
    override val dates = HolidayDates(MonthDay.of(1, 1), MonthDay.of(12, 31))

    override fun automaticAt(now: LocalDateTime) = false

    override val supportsAutomatic = false
    override val keywords = listOf("tulip", "flower", "painting", "petal", "butterfly", "spring", "sage")
    override val corners = HolidayCorners(tabDp = 10f, toolbarDp = 10, tileDp = 12)
    override val tabTexture = HolidayTabTexture.PLAIN
    override val tintTabsByGroup = false
    override val activeTabUnderlineDp = 3f

    override fun tileDividerGradient(dark: Boolean) = bouquetColors

    override val tabScene: HolidayScenePainter = TulipsScene
    override val picture: HolidayPicturePainter = TulipsPicture
    override val companion: HolidayCompanionPainter = TulipsCompanion
    override val icons = TulipsIcons
    override val effects: List<HolidayEffectSpec> = listOf(TulipPetals, TulipButterflies)

    // The backgrounds lean toward the painting's sage wall (about #7a8676) so the picture under the filters fades into them gently.
    override fun palette(dark: Boolean) = if (dark) theme(
        0xFF283027, 0xFF2e352c, 0xFF373f35, 0xFF475043, 0xFFeef0e6,
        0xFFc3cbb8, 0xFF8f9a86, 0xFFb48cf0, 0xFFb48cf0, 0xFFf2d21c, onAc = 0xFF1c0b33,
    ).copy(abg = Color(0xFFb48cf0).copy(alpha = .16f), sl = Color(0xFFb48cf0).copy(alpha = .2f),
        hv = Color.White.copy(alpha = .05f), searchMatchBg = Color(0xFFf2d21c).copy(alpha = .3f),
        searchCurrentBg = Color(0xFFb48cf0).copy(alpha = .5f)) else theme(
        0xFFe1e6d9, 0xFFfbfcf8, 0xFFd8ded0, 0xFFbcc5ad, 0xFF23291f,
        0xFF4c5644, 0xFF5d6754, 0xFF7a2fbf, 0xFF7a2fbf, 0xFFd4252e,
        hv = Color.Black.copy(alpha = .04f),
    ).copy(abg = Color(0xFF7a2fbf).copy(alpha = .1f), sl = Color(0xFF7a2fbf).copy(alpha = .12f),
        searchMatchBg = Color(0xFFf2d21c).copy(alpha = .35f), searchCurrentBg = Color(0xFF7a2fbf).copy(alpha = .3f))

    override fun chrome(dark: Boolean) = if (dark)
        HolidayChromeColors(Color(0xFF232a22), Color(0xFF373f35), Color.Transparent)
    else HolidayChromeColors(Color(0xFFd5dccb), Color(0xFFc8d0bb), Color.Transparent)
}

private val bouquetColors = listOf(0xFF7b2fbf, 0xFFd4252e, 0xFFf2d21c, 0xFFe46a9a, 0xFF6fb33f).map(::Color)
private val sceneFlower = parsePath("M-4 0c0 4 1.6 6.4 4 6.4s4-2.4 4-6.4l-2 2-2-3-2 3z")
private val sceneLeaf = parsePath("M0 27c-4-1-6-4-6-7 3 1 5 3 6 7z")

private data class Flower(val x: Float, val top: Float, val color: Long)

private val flowers = listOf(
    Flower(970f, 9f, 0xFF7b2fbf), Flower(990f, 11f, 0xFFd4252e), Flower(1012f, 13f, 0xFFf2d21c),
    Flower(1268f, 9f, 0xFFf6f4e6), Flower(1288f, 11f, 0xFFe46a9a), Flower(1310f, 13f, 0xFFd4252e),
    Flower(1440f, 9f, 0xFFa8204f), Flower(1462f, 11f, 0xFFf2d21c), Flower(1486f, 13f, 0xFF7b2fbf),
)

private object TulipsScene : HolidayScenePainter {
    override val animated = true
    private val parts = listOf(
        HolidayBandPart("left", Rect(960f, 3f, 1020f, 30f)),
        HolidayBandPart("middle", Rect(1258f, 3f, 1318f, 30f)),
        HolidayBandPart("right", Rect(1430f, 3f, 1494f, 30f)),
    )

    override fun layout(band: Rect): HolidaySceneLayout = HolidayBandLayout(HolidayBandSpace(band), parts)

    override fun draw(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean) = drawFrame(scope, layout, dark, HOLIDAY_STILL_TIME)

    override fun drawFrame(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean, time: Float) {
        val l = layout as HolidayBandLayout
        l.space.drawElements(scope) {
            val leaf = Color(if (dark) 0xFF7fc24c else 0xFF5a9a32)
            flowers.forEachIndexed { i, flower ->
                if (!l.has(listOf("left", "middle", "right")[i / 3])) return@forEachIndexed
                val period = if (i % 2 == 0) 2.8f else 3.4f
                val offset = if (i % 2 == 0) 0f else 1.3f
                val sway = if (time == HOLIDAY_STILL_TIME) 0f else sin(time * (2f * PI.toFloat() / period) + offset) * 4f
                withTransform({ rotate(sway, Offset(flower.x, 30f)); translate(flower.x, 0f) }) {
                    drawLine(leaf, Offset(0f, flower.top + 6f), Offset(0f, 30f), strokeWidth = 1.4f)
                    drawPath(sceneLeaf, leaf)
                    withTransform({ translate(0f, flower.top) }) { drawPath(sceneFlower, Color(flower.color)) }
                }
            }
        }
    }
}

private val petalPath = parsePath("M6 1C9 3 10 7 6 11 2 7 3 3 6 1z")
private val wingTop = parsePath("M13 10C9 1 1 1 2 7s7 5 11 3zM13 10c4-9 12-9 11-3s-7 5-11 3z")
private val wingBottom = parsePath("M13 11c-3 4-8 8-9 5s5-5 9-5zM13 11c3 4 8 8 9 5s-5-5-9-5z")
private val petalColors = listOf(0xFF7b2fbf, 0xFFd4252e, 0xFFf2d21c, 0xFFe46a9a, 0xFFf6f4e6).map(::Color)
private val butterflyTop = listOf(0xFFffb84d, 0xFF7b2fbf, 0xFF8fd3ff).map(::Color)
private val butterflyBottom = listOf(0xFFf2d21c, 0xFFe46a9a, 0xFF9be27a).map(::Color)

private data class Particle(val x: Float, val y: Float, val size: Float, val period: Float, val phase: Float, val angle: Float)

private fun phase(value: Float) = value - floor(value)

private fun randomRange(random: Random, from: Float, to: Float) = from + random.nextFloat() * (to - from)

private object TulipPetals : HolidayParticleEffect {
    override val id = "tulips.petals"
    override val label = "Falling petals"
    override val noun = "petals"
    override val defaultOn = true

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField {
        val rng = Random(seed)
        val particles = List(if (speedScale <= 0f) 0 else (24 * countScale).roundToInt().coerceAtLeast(0)) {
            Particle(rng.nextFloat(), 0f, randomRange(rng, 8f, 13f), randomRange(rng, 10f, 17f) / speedScale, rng.nextFloat(), randomRange(rng, 0f, 360f))
        }
        return object : HolidayParticleField {
            override val count = particles.size

            override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) = with(scope) {
                particles.forEachIndexed { i, p ->
                    val u = phase(t / p.period + p.phase)
                    val x = p.x * widthDp + 18f * sin(u * 4f * PI.toFloat())
                    val y = -p.size + u * (heightDp + 2f * p.size)
                    withTransform({
                        translate(x, y)
                        rotate(p.angle + u * 360f, Offset(p.size / 2f, p.size / 2f))
                        scale(p.size / 12f, p.size / 12f, Offset.Zero)
                    }) {
                        drawPath(petalPath, petalColors[i % petalColors.size])
                        drawLine(Color.White.copy(alpha = .5f), Offset(6f, 2.5f), Offset(6f, 9.5f), strokeWidth = .8f)
                    }
                }
            }
        }
    }
}

private object TulipButterflies : HolidayParticleEffect {
    override val id = "tulips.butterflies"
    override val label = "Butterflies"
    override val noun = "butterflies"
    override val defaultOn = true

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField {
        val rng = Random(seed)
        val particles = List(if (speedScale <= 0f) 0 else (3 * countScale).roundToInt().coerceAtLeast(0)) {
            Particle(0f, randomRange(rng, 4f, 170f), 26f, randomRange(rng, 18f, 28f) / speedScale, rng.nextFloat(), 0f)
        }
        return object : HolidayParticleField {
            override val count = particles.size

            override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) = with(scope) {
                particles.forEachIndexed { i, p ->
                    val u = phase(t / p.period + p.phase)
                    val x = -40f + u * (widthDp + 80f)
                    val y = (p.y + 8f * sin(u * 4f * PI.toFloat())).coerceAtMost(heightDp)
                    val flap = .65f + .35f * cos(t * speedScale * 2f * PI.toFloat() / .36f)
                    withTransform({ translate(x, y) }) {
                        withTransform({ scale(flap, 1f, Offset(13f, 10f)) }) {
                            drawPath(wingTop, butterflyTop[i % butterflyTop.size])
                            drawPath(wingBottom, butterflyBottom[i % butterflyBottom.size])
                        }
                        drawLine(Color(0xFF3a2a1a), Offset(13f, 5f), Offset(13f, 16f), strokeWidth = 1.6f)
                    }
                }
            }
        }
    }
}
