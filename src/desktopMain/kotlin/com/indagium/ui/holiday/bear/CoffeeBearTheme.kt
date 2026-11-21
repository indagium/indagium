@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.bear

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.model.HolidayPack
import com.indagium.ui.holiday.*
import com.indagium.ui.theme
import java.time.LocalDateTime
import java.time.MonthDay
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

internal object CoffeeBearTheme : HolidayTheme {
    override val pack = HolidayPack.COFFEE_BEAR
    override val label = "Coffee bear"
    override val category = HolidayCategory.PAINTING
    override val occasion = "Any time"
    override val dates = HolidayDates(MonthDay.of(1, 1), MonthDay.of(12, 31))

    override fun automaticAt(now: LocalDateTime) = false

    override val supportsAutomatic = false
    override val keywords = listOf("bear", "coffee", "winter", "snow", "polar", "aurora", "painting")
    override val corners = HolidayCorners(tabDp = 12f, toolbarDp = 12, tileDp = 14)
    override val tabTexture = HolidayTabTexture.PLAIN
    override val tintTabsByGroup = false
    override val activeTabUnderlineDp = 2f

    override fun tileDividerGradient(dark: Boolean): List<Color>? = null

    override val picture: HolidayPicturePainter = CoffeeBearPicture
    override val companion: HolidayCompanionPainter = CoffeeBearCompanion
    override val tabScene: HolidayScenePainter = CoffeeBearScene
    override val icons = CoffeeBearIcons
    override val effects: List<HolidayEffectSpec> = listOf(BearSnow, BearAurora)

    override fun palette(dark: Boolean) = if (dark) theme(
        0xFF0f1b2b, 0xFF15243a, 0xFF1b2d47, 0xFF2b4466, 0xFFf3ecd8,
        0xFFc9d6e3, 0xFF8ea3b8, 0xFFff7466, 0xFFff7466, 0xFFf1e6b8, onAc = 0xFF2a0805,
    ).copy(abg = Color(0xFFff7466).copy(alpha = .15f), sl = Color(0xFFff7466).copy(alpha = .18f),
        hv = Color.White.copy(alpha = .05f), searchMatchBg = Color(0xFFf1e6b8).copy(alpha = .3f),
        searchCurrentBg = Color(0xFFff7466).copy(alpha = .5f)) else theme(
        0xFFf6f3ea, 0xFFffffff, 0xFFeef3f6, 0xFFc9d8e0, 0xFF1d2a3a,
        0xFF46596d, 0xFF5f7081, 0xFFc8322b, 0xFFc8322b, 0xFF97531e,
        hv = Color.Black.copy(alpha = .04f),
    ).copy(abg = Color(0xFFc8322b).copy(alpha = .1f), sl = Color(0xFFc8322b).copy(alpha = .12f),
        searchMatchBg = Color(0xFFf2b33d).copy(alpha = .3f), searchCurrentBg = Color(0xFFc8322b).copy(alpha = .3f))

    override fun chrome(dark: Boolean) = if (dark)
        HolidayChromeColors(Color(0xFF0d1726), Color(0xFF1b2d47), Color.Transparent)
    else HolidayChromeColors(Color(0xFFe6eef2), Color(0xFFdbe7ed), Color.Transparent)

    override val borderWidthDp = 1f

    override fun tileDividerDashes(dark: Boolean) = HolidayDividerDashes(
        if (dark) Color(0xFFbfe3f2).copy(alpha = .35f) else Color(0xFFc9d8e0),
        if (dark) Color(0xFFbfe3f2).copy(alpha = .35f) else Color(0xFFc9d8e0), heightDp = 2f,
    )
}

private object BearAurora : HolidaySceneEffect {
    override val id = "coffee_bear.aurora"
    override val label = "Northern lights in the title bar"
    override val noun = "northern lights"
    override val defaultOn = true
}

private val auroraPath = parsePath("M900 20C980 6 1060 22 1140 10S1300 2 1380 15 1520 6 1600 12V0H900z")
private val snowBank = parsePath("M0 30V26Q200 20 420 25T840 23T1260 26T1600 22V30Z")
private val igloo = parsePath("M1236 30a16 13 0 0 1 32 0z")
private val blocks = parsePath("M1238.5 25.5h27M1243 21h18M1252 17v4M1246 21v4.5M1258 21v4.5")
private val door = parsePath("M1248 30v-3.2a4 4 0 0 1 8 0V30z")
private val cup = parsePath("M1320 30v-6l3-9 3 9v6z")

private object CoffeeBearScene : HolidayScenePainter {
    override val animated = true
    private val parts = listOf(HolidayBandPart("igloo", Rect(1234f, 15f, 1270f, 30f)), HolidayBandPart("cup", Rect(1317f, 13f, 1329f, 30f)))
    private val stars = List(34) { i -> val r = Random(101 + i); Triple(r.nextFloat() * 1600f, 3f + r.nextFloat() * 17f, .35f + r.nextFloat() * .55f) }

    override fun layout(band: Rect): HolidaySceneLayout = HolidayBandLayout(HolidayBandSpace(band), parts)

    override fun draw(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean) = drawFrame(scope, layout, dark, HOLIDAY_STILL_TIME, setOf(BearAurora.id))

    override fun drawFrame(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean, time: Float) =
        drawFrame(scope, layout, dark, time, setOf(BearAurora.id))

    override fun drawFrame(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean, time: Float, enabledEffects: Set<String>) {
        val l = layout as HolidayBandLayout
        l.space.drawBackdrop(scope) {
            val starInk = if (dark) Color.White else Color(0xFF8eb9cf)
            stars.forEach { (x, y, alpha) -> drawCircle(starInk.copy(alpha = alpha), .75f, Offset(x, y)) }
            drawPath(snowBank, if (dark) Color(0xFFe8f3f8) else Color.White)
        }
        l.space.drawElements(scope) {
            if (BearAurora.id in enabledEffects) {
                val shift = if (time == HOLIDAY_STILL_TIME) 0f else 30f * sin(time * 2f * PI.toFloat() / 18f)
                withTransform({ translate(shift, 0f) }) {
                    val glow = Brush.horizontalGradient(
                        0f to Color(0x005ef2c2), .25f to Color(0xFF5ef2c2).copy(alpha = .38f),
                        .6f to Color(0xFF59c7ff).copy(alpha = .38f), 1f to Color(0xFFb48cff).copy(alpha = .076f),
                        startX = 900f, endX = 1600f,
                    )
                    drawPath(auroraPath, glow)
                }
            }
            if (l.has("igloo")) {
                drawPath(igloo, if (dark) Color(0xFFe8f3f8) else Color.White)
                drawPath(blocks, if (dark) Color(0xFF9fc3d6) else Color(0xFF8eb9cf), style = Stroke(.8f))
                drawPath(door, Color(0xFF2b4466))
            }
            if (l.has("cup")) {
                drawPath(cup, Color(0xFFe0453b))
                drawOval(Color(0xFF3a2418), Offset(1320f, 14.1f), Size(6f, 1.8f))
            }
        }
    }
}

private data class Flake(val x: Float, val size: Float, val alpha: Float, val period: Float, val phase: Float)

private object BearSnow : HolidayParticleEffect {
    override val id = "coffee_bear.snow"
    override val label = "Snowfall"
    override val noun = "snow"
    override val defaultOn = true

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField {
        val random = Random(seed)

        fun range(a: Float, b: Float) = a + random.nextFloat() * (b - a)
        val flakes = List(if (speedScale <= 0f) 0 else (40 * countScale).roundToInt().coerceAtLeast(0)) {
            Flake(random.nextFloat(), range(2f, 4.5f), range(.5f, .9f), range(11f, 19f) / speedScale, random.nextFloat())
        }
        return object : HolidayParticleField {
            override val count = flakes.size

            override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) = with(scope) {
                flakes.forEach { f ->
                    val p0 = t / f.period + f.phase
                    val p = p0 - floor(p0)
                    drawCircle((if (dark) Color.White else Color(0xFFa9c9db)).copy(alpha = f.alpha), f.size / 2f,
                        Offset(f.x * widthDp + 10f * sin(p * 2f * PI.toFloat()), -f.size + p * (heightDp + 2f * f.size)))
                }
            }
        }
    }
}
