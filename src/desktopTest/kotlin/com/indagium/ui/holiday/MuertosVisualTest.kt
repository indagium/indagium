@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.indagium.ui.holiday.muertos.MuertosTheme
import com.indagium.ui.holiday.muertos.drawSvg
import com.indagium.ui.holiday.muertos.easeInOut
import com.indagium.ui.holiday.muertos.flameShape
import com.indagium.ui.holiday.muertos.glowFlicker
import com.indagium.ui.holiday.muertos.muertosLidsClosed
import com.indagium.ui.holiday.muertos.steamOpacity
import com.indagium.ui.holiday.muertos.svgResource
import com.indagium.ui.holiday.muertos.wingFlap
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MuertosVisualTest {
    private val theme = MuertosTheme

    private fun render(w: Int, h: Int, draw: DrawScope.() -> Unit): ImageBitmap = ImageBitmap(w, h).also {
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(it), Size(w.toFloat(), h.toFloat()), draw)
    }

    private fun differs(a: ImageBitmap, b: ImageBitmap, region: Rect? = null): Boolean {
        val pa = a.toPixelMap()
        val pb = b.toPixelMap()
        val xs = (region?.left?.toInt() ?: 0) until (region?.right?.toInt() ?: a.width)
        val ys = (region?.top?.toInt() ?: 0) until (region?.bottom?.toInt() ?: a.height)
        return ys.any { y -> xs.any { x -> pa[x, y].toArgb() != pb[x, y].toArgb() } }
    }

    private fun picture(t: Float, dark: Boolean = true) = render(300, 420) { theme.picture.draw(this, t, dark) }

    private fun companion(t: Float, dark: Boolean = true) = render(240, 240) { theme.companion.draw(this, t, dark) }

    private fun scene(t: Float, dark: Boolean = true): ImageBitmap {
        val painter = theme.tabScene
        val layout = painter.layout(Rect(0f, 0f, 1600f, 30f))
        return render(1600, 30) { painter.drawFrame(this, layout, dark, t) }
    }

    @Test fun pictureCompanionAndSceneFramesDifferBetweenTwoTimes() {
        for (dark in listOf(true, false)) {
            assertTrue(differs(picture(.3f, dark), picture(1.9f, dark)), "the altar's flames, smoke, petals and monarchs move")
            assertTrue(differs(companion(.3f, dark), companion(1.9f, dark)), "the calavera sways")
            assertTrue(differs(scene(.3f, dark), scene(1.9f, dark)), "the flags sway")
        }
    }

    @Test fun theStillFrameIsStableAndCalm() {
        assertFalse(differs(picture(HOLIDAY_STILL_TIME), picture(HOLIDAY_STILL_TIME)))
        assertFalse(differs(companion(HOLIDAY_STILL_TIME), companion(HOLIDAY_STILL_TIME)))
        assertFalse(differs(scene(HOLIDAY_STILL_TIME), scene(HOLIDAY_STILL_TIME)))
        // The still scene is the still frame the framework draws without a clock.
        val painter = theme.tabScene
        val layout = painter.layout(Rect(0f, 0f, 1600f, 30f))
        assertFalse(differs(scene(HOLIDAY_STILL_TIME), render(1600, 30) { painter.draw(this, layout, true) }))
        // Flags at rest: no sway, so the still scene is the unrotated flags (the first flag's top-left corner is solid).
        val corner = scene(HOLIDAY_STILL_TIME).toPixelMap()[97, 6]
        assertEquals(0xFFe6007e.toInt(), corner.toArgb(), "the first flag hangs straight")
        // Smoke is visible and eyes open in the still frame.
        assertTrue(steamOpacity(.35f) > .7f)
        assertFalse(muertosLidsClosed(HOLIDAY_STILL_TIME))
    }

    @Test fun theLidsCloseOnlyDuringTheBlinkWindow() {
        assertTrue(muertosLidsClosed(4.95f))
        assertFalse(muertosLidsClosed(1f))
        assertFalse(muertosLidsClosed(5.1f))
        // 4.95 s and 12.55 s are at the same point of the 7.6 s sway; only the first has the eyelids drawn over the eyes.
        val eyes = Rect(80f, 105f, 160f, 150f)
        assertTrue(differs(companion(4.95f), companion(12.55f), eyes), "closed eyelids cover the eyes at 4.95 s")
        assertFalse(differs(companion(1f), companion(1f + 7.6f), eyes), "no blink away from the window")
    }

    @Test fun animationCurvesFollowTheKeyframes() {
        assertEquals(.5f, easeInOut(.5f), .001f)
        assertEquals(1f, glowFlicker(0f), .001f)
        assertEquals(.78f, glowFlicker(.07f * 2.6f), .001f)
        assertEquals(.58f, wingFlap(.75f), .001f)
        assertEquals(1f, wingFlap(0f), .001f)
        assertEquals(1f, wingFlap(1.5f), .001f)
        assertTrue(abs(flameShape(1.1f * .25f, 1).first - 1.14f) < .001f)
    }

    @Test fun everySvgLayerLoadsAndRendersSomething() {
        for ((name, w, h) in listOf(
            Triple("picture-back", 300, 420), Triple("picture-front", 300, 420), Triple("companion", 240, 240),
            Triple("companion-lids", 240, 240), Triple("monarch-wings", 64, 48),
        )) {
            val svg = svgResource(name)
            val pixels = render(w, h) { drawSvg(svg) }.toPixelMap()
            val painted = (0 until h step 2).sumOf { y -> (0 until w step 2).count { x -> pixels[x, y].alpha > 0f } }
            assertTrue(painted > 20, "$name renders (painted $painted)")
        }
    }

    @Test fun sceneKeepsTheGapOverTheAppNameAndDropsGroupsInANarrowBand() {
        val painter = theme.tabScene
        val wide = painter.layout(Rect(0f, 0f, 1600f, 30f)).elementBounds.size
        val narrow = painter.layout(Rect(0f, 0f, 700f, 30f)).elementBounds.size
        assertEquals(8, wide, "two sides of four groups")
        assertTrue(narrow in 1 until wide, "a narrower band drops whole groups ($narrow of $wide)")
        val pixels = scene(HOLIDAY_STILL_TIME).toPixelMap()
        // Over the app name (690..910) only the wire is drawn, at the top.
        assertTrue((10 until 30).all { y -> (700 until 900).all { x -> pixels[x, y].alpha == 0f } }, "no flag over the app name")
    }

    @Test fun renderDarkAndLightArtworkProof() {
        val captureDir = System.getenv("INDAGIUM_CAPTURE_MUERTOS") ?: return
        val bitmap = render(1080, 520) {
            for ((index, dark) in listOf(true, false).withIndex()) {
                val origin = index * 540f
                drawRect(theme.palette(dark).bg, Offset(origin, 0f), Size(540f, 520f))
                withTransform({ translate(origin, 50f) }) { theme.picture.draw(this, 2.4f, dark) }
                withTransform({ translate(origin + 300f, 220f) }) { theme.companion.draw(this, 2.4f, dark) }
                val painter = theme.tabScene
                drawRect(theme.chrome(dark).tabBar, Offset(origin, 10f), Size(540f, 30f))
                withTransform({ translate(origin - 1060f, 10f) }) {
                    painter.drawFrame(this, painter.layout(Rect(0f, 0f, 1600f, 30f)), dark, 2.4f)
                }
            }
        }
        val pixels = bitmap.toPixelMap()
        val output = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) output.setRGB(x, y, pixels[x, y].toArgb())
        File(captureDir).mkdirs()
        ImageIO.write(output, "png", File(captureDir, "muertos-artwork.png"))
    }
}
