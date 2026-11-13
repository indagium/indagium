@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.ready

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import com.indagium.model.HolidayPack
import com.indagium.ui.holiday.*
import com.indagium.ui.theme
import org.jetbrains.skia.Data
import org.jetbrains.skia.svg.SVGDOM
import java.time.LocalDateTime
import java.time.MonthDay
import kotlin.math.cos

internal object ReadyTheme : HolidayTheme {
    override val pack = HolidayPack.READY_PROMPT
    override val label = "Ready prompt"
    override val occasion = "Any time"
    override val dates = HolidayDates(MonthDay.of(1, 1), MonthDay.of(12, 31))

    // An anytime theme is selected explicitly; it must not displace scheduled holidays.
    override fun automaticAt(now: LocalDateTime) = false

    override val supportsAutomatic = false
    override val keywords = listOf("computer", "8-bit", "retro", "pixel", "c64", "scanlines")
    override val corners = HolidayCorners(tabDp = 0f, toolbarDp = 0, tileDp = 0)
    override val labelFontFamily = FontFamily(Font("holiday/ready/PressStart2P-Regular.ttf"))
    override val labelSizeSp = 8f
    override val uppercaseLabels = true
    override val activeTabCursor = true
    override val borderWidthDp = 2f
    override val tabTexture = HolidayTabTexture.PLAIN
    override val tintTabsByGroup = false
    override val inverseActiveTab = true
    override val picture: HolidayPicturePainter = ReadyPicture
    override val companion: HolidayCompanionPainter = ReadyCompanion
    override val tabScene: HolidayScenePainter = ReadyScene
    override val icons = ReadyIcons
    override val effects: List<HolidayEffectSpec> = listOf(ReadyEffect(false), ReadyEffect(true))

    override fun palette(dark: Boolean) = if (dark) theme(
        0xFF2a2165, 0xFF352879, 0xFF40318d, 0xFF6a5fc0, 0xFFd6d2ff,
        0xFFa59eff, 0xFF8f87e8, 0xFFa59eff, 0xFFa59eff, 0xFFeeee77, onAc = 0xFF1e1750,
    ).copy(abg = Color(0xFFa59eff).copy(alpha = .16f), sl = Color(0xFFa59eff).copy(alpha = .2f),
        hv = Color.White.copy(alpha = .06f), searchMatchBg = Color(0xFFeeee77).copy(alpha = .32f),
        searchCurrentBg = Color(0xFFa59eff).copy(alpha = .5f)) else theme(
        0xFFece6d6, 0xFFf7f3e8, 0xFFe3dbc6, 0xFFb4a684, 0xFF241c5c,
        0xFF40318d, 0xFF5f5790, 0xFF40318d, 0xFF40318d, 0xFFc0483e, hv = Color.Black.copy(alpha = .04f),
    ).copy(abg = Color(0xFF40318d).copy(alpha = .12f), sl = Color(0xFF40318d).copy(alpha = .14f),
        searchMatchBg = Color(0xFFdd8855).copy(alpha = .32f), searchCurrentBg = Color(0xFF40318d).copy(alpha = .3f))

    override fun chrome(dark: Boolean) = if (dark) HolidayChromeColors(Color(0xFF2a2165), Color(0xFF352879), Color.Black.copy(alpha = .2f))
    else HolidayChromeColors(Color(0xFFddd3bb), Color(0xFFd2c6aa), Color.Black.copy(alpha = .1f))
}

private fun artSource(name: String): String =
    requireNotNull(ReadyTheme::class.java.getResourceAsStream("/holiday/ready/$name.svg")).bufferedReader().use { it.readText() }

private fun art(source: String): SVGDOM = SVGDOM(Data.makeFromBytes(source.toByteArray(Charsets.UTF_8)))

private object ReadyPicture : HolidayPicturePainter {
    override val designWidthDp = 300f
    override val designHeightDp = 420f
    private val sprite by lazy { art(artSource("sprite")) }

    // SVGDOM does not execute CSS animation. Cache the discrete cursor/light frames instead.
    private val frames by lazy {
        val source = artSource("picture")
        List(4) { frame ->
            var svg = source
            if (frame and 1 != 0) svg = svg.replace("class=\"s-cursor\"", "opacity=\"0\"")
            if (frame and 2 != 0) svg = svg.replace("class=\"s-light\"", "opacity=\".35\"")
            art(svg)
        }
    }

    override fun draw(scope: DrawScope, t: Float, dark: Boolean) = with(scope) {
        val frame = (if (t % 1f >= .5f) 1 else 0) + (if (t % 1.6f >= .8f) 2 else 0)

        // Skia SVG does not reliably resolve the reference's radial gradients.
        fun glow(cx: Float, cy: Float, rx: Float, ry: Float, color: Color) {
            withTransform({ translate(cx, cy); scale(rx, ry, Offset.Zero) }) {
                drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), Offset.Zero, 1f), 1f, Offset.Zero)
            }
        }
        val pulse = .4f + .05f * cos(t * (2f * Math.PI.toFloat() / 3.2f))
        glow(140f, 132f, 150f, 120f, Color(0xFF7869c4).copy(alpha = pulse))
        glow(140f, 262f, 104f, 8f, Color.Black.copy(alpha = .35f))
        glow(150f, 344f, 142f, 8f, Color.Black.copy(alpha = .35f))
        glow(252f, 392f, 34f, 5f, Color.Black.copy(alpha = .35f))
        withTransform({ translate(0f, (cos(t * (2f * Math.PI.toFloat() / 4.2f)) - 1f) * 5f) }) {
            drawIntoCanvas { sprite.render(it.skiaCanvas) }
        }
        drawIntoCanvas { frames[frame].render(it.skiaCanvas) }
    }
}

private object ReadyCompanion : HolidayCompanionPainter {
    override val label = "Sprite balloon in the Notes corner"
    override val designWidthDp = 240f
    override val designHeightDp = 240f
    private val frames by lazy {
        val source = artSource("companion")
        listOf(art(source), art(source.replace("class=\"s-blink\"", "transform=\"translate(0 90) scale(1 .12) translate(0 -90)\"")))
    }

    override fun draw(scope: DrawScope, t: Float, dark: Boolean) = with(scope) {
        val blink = t % 4.6f in 4.14f..4.42f
        withTransform({ translate(0f, (cos(t * (2f * Math.PI.toFloat() / 4.2f)) - 1f) * 5f) }) {
            drawIntoCanvas { frames[if (blink) 1 else 0].render(it.skiaCanvas) }
        }
    }
}

private object ReadyScene : HolidayScenePainter {
    private val parts = listOf(HolidayBandPart("ready", Rect(1250f, 3f, 1500f, 27f)))

    override fun layout(band: Rect): HolidaySceneLayout = HolidayBandLayout(HolidayBandSpace(band), parts)

    override val animated = true
    private val prompts by lazy {
        val source = artSource("prompt")
        listOf(art(source), art(source.replace("#a59eff", "#40318d")))
    }

    override fun draw(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean) = drawFrame(scope, layout, dark, HOLIDAY_STILL_TIME)

    override fun drawFrame(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean, time: Float) {
        val l = layout as HolidayBandLayout
        if (!l.has("ready")) return
        l.space.drawElements(scope) {
            val colors = listOf(0xFFc0483e, 0xFFdd8855, 0xFFeeee77, 0xFF5cab5e, 0xFF7869c4)
            colors.forEachIndexed { i, c -> drawRect(Color(c), Offset(1450f + i * 7f, 8f), Size(7f, 14f)) }
            val ink = if (dark) Color(0xFFa59eff) else Color(0xFF40318d)
            withTransform({ translate(1375f, 10f) }) {
                drawIntoCanvas { prompts[if (dark) 0 else 1].render(it.skiaCanvas) }
            }
            if (time % 1f < .5f) drawRect(ink, Offset(1426f, 10f), Size(7f, 10f))
        }
    }
}

private class ReadyEffect(private val tape: Boolean) : HolidayParticleEffect {
    override val id = if (tape) "ready.rainbow" else "ready.scanlines"
    override val label = if (tape) "Rainbow tape" else "CRT scanlines"
    override val noun = if (tape) "rainbow tape" else "scanlines"
    override val defaultOn = true
    override val moves get() = tape

    override fun spawn(seed: Int, countScale: Float, speedScale: Float): HolidayParticleField = object : HolidayParticleField {
        override val count = 1

        override fun draw(scope: DrawScope, t: Float, widthDp: Float, heightDp: Float, dark: Boolean) = with(scope) {
            if (tape) {
                val colors = listOf(0xFFc0483e, 0xFFdd8855, 0xFFeeee77, 0xFF5cab5e, 0xFF7869c4)
                var y = -70f + (t * 10f * speedScale % 70f)
                while (y < heightDp) {
                    colors.forEachIndexed { i, c ->
                        val top = (y + i * 14f).coerceAtLeast(0f)
                        val bottom = (y + (i + 1) * 14f).coerceAtMost(heightDp)
                        if (bottom > top) {
                            drawRect(Color(c).copy(alpha = .8f), Offset(0f, top), Size(5f, bottom - top))
                            drawRect(Color(c).copy(alpha = .8f), Offset((widthDp - 5f).coerceAtLeast(0f), top), Size(5f, bottom - top))
                        }
                    }
                    y += 70f
                }
            } else {
                var y = 0f
                while (y < heightDp) { drawRect(Color.Black.copy(alpha = if (dark) .2f else .05f), Offset(0f, y), Size(widthDp, 1f)); y += 3f }
            }
        }
    }
}
