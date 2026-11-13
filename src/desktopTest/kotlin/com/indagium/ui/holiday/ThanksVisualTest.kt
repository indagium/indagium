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
import com.indagium.ui.holiday.thanksgiving.ThanksTheme
import com.indagium.ui.holiday.thanksgiving.geeseTravel
import com.indagium.ui.holiday.thanksgiving.gooseFlap
import com.indagium.ui.holiday.thanksgiving.thanksLidsClosed
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThanksVisualTest {
    private val theme = ThanksTheme

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
            assertTrue(differs(picture(.3f, dark), picture(1.9f, dark)), "the sun, geese, leaves, flames and smoke move")
            assertTrue(differs(companion(.3f, dark), companion(1.9f, dark)), "the turkey fans its tail and bobs")
            assertTrue(differs(scene(.3f, dark), scene(1.9f, dark)), "the wheat sways")
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
        assertFalse(thanksLidsClosed(HOLIDAY_STILL_TIME))
    }

    @Test fun theLidsCloseOnlyDuringTheBlinkWindow() {
        assertTrue(thanksLidsClosed(4.95f))
        assertFalse(thanksLidsClosed(1f))
        assertFalse(thanksLidsClosed(5.1f))
        // 4.95 s and 12.75 s are at the same point of the 2.6 s head bob (7.8 s is three of them); only the first has the lids drawn.
        // The region is the left eye itself, which only the head (not the fanning tail behind it) paints.
        val eyes = Rect(104f, 89f, 116f, 101f)
        assertTrue(differs(companion(4.95f), companion(12.75f), eyes), "closed eyelids cover the eyes at 4.95 s")
        assertFalse(differs(companion(1f), companion(1f + 7.8f), eyes), "no blink away from the window")
    }

    @Test fun animationCurvesFollowTheKeyframes() {
        assertEquals(1f, gooseFlap(0f), .001f)
        assertEquals(.45f, gooseFlap(.45f), .001f)
        assertEquals(1f, gooseFlap(.9f), .001f)
        assertEquals(-120f, geeseTravel(0f), .001f)
        assertEquals(55f, geeseTravel(8f), .001f)
        assertEquals(-120f, geeseTravel(16f), .01f)
    }

    @Test fun everySvgLayerLoadsAndRendersSomething() {
        val layers = listOf(
            Triple("picture-back", 300, 420), Triple("picture-window", 300, 420), Triple("picture-room", 300, 420),
            Triple("companion-tail", 240, 240), Triple("companion-body", 240, 240), Triple("companion-head", 240, 240),
            Triple("companion-lids", 240, 240),
        ) + listOf(
            "scene-far-dark", "scene-far-light", "scene-near-dark", "scene-near-light", "scene-bales", "scene-shocks", "scene-barn",
        ).map { Triple(it, 1600, 30) } + (0..5).map { Triple("scene-wheat-$it", 1600, 30) }
        for ((name, w, h) in layers) {
            val svg = holidaySvg("thanksgiving", name)
            val pixels = render(w, h) { drawSvg(svg) }.toPixelMap()
            val painted = (0 until h).sumOf { y -> (0 until w step 2).count { x -> pixels[x, y].alpha > 0f } }
            assertTrue(painted > 20, "$name renders (painted $painted)")
        }
    }

    @Test fun sceneSunStaysRoundBesideTheBarnInANarrowBand() {
        val painter = theme.tabScene
        val layout = painter.layout(Rect(0f, 0f, 700f, 30f))
        val sun = layout.elementBounds.first()
        assertEquals(sun.width, 48f, .01f, "the sun's box keeps its design width (it is not stretched with the fields)")
        assertTrue(sun.right <= 700f && sun.left > 0f, "the sun is placed right-aligned with the barn")
    }

    @Test fun sceneDropsWholeElementsInANarrowBand() {
        val painter = theme.tabScene
        assertEquals(5, painter.layout(Rect(0f, 0f, 1600f, 30f)).elementBounds.size, "the sun, bales, shocks, barn and wheat")
        val narrow = painter.layout(Rect(0f, 0f, 400f, 30f)).elementBounds.size
        assertTrue(narrow in 1 until 5, "a narrower band drops the leftmost whole elements ($narrow of 5)")
        // The fields are a backdrop: they are drawn whatever the band, even with every element dropped.
        val tiny = Rect(0f, 0f, 100f, 30f)
        assertEquals(0, painter.layout(tiny).elementBounds.size)
        val pixels = render(100, 30) { painter.drawFrame(this, painter.layout(tiny), true, 2.4f) }.toPixelMap()
        assertTrue((0 until 100).any { x -> pixels[x, 29].alpha > 0f }, "the near field still runs along the bottom")
    }

    @Test fun renderDarkAndLightArtworkProof() {
        val captureDir = System.getenv("INDAGIUM_CAPTURE_THANKS") ?: return
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
        ImageIO.write(output, "png", File(captureDir, "thanksgiving-artwork.png"))
    }
}
