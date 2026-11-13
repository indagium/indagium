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
import com.indagium.ui.holiday.ready.ReadyTheme
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

class ReadyVisualTest {
    private fun render(w: Int, h: Int, draw: DrawScope.() -> Unit): ImageBitmap = ImageBitmap(w, h).also {
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(it), Size(w.toFloat(), h.toFloat()), draw)
    }

    private fun differs(a: ImageBitmap, b: ImageBitmap): Boolean {
        val pa = a.toPixelMap(); val pb = b.toPixelMap()
        return (0 until a.height).any { y -> (0 until a.width).any { x -> pa[x, y].toArgb() != pb[x, y].toArgb() } }
    }

    @Test fun pictureCompanionAndSceneHaveAnimatedFrames() {
        for (dark in listOf(true, false)) {
            assertTrue(differs(render(300, 420) { ReadyTheme.picture.draw(this, 0f, dark) },
                render(300, 420) { ReadyTheme.picture.draw(this, .75f, dark) }), "computer picture animates")
            assertTrue(differs(render(240, 240) { ReadyTheme.companion.draw(this, 0f, dark) },
                render(240, 240) { ReadyTheme.companion.draw(this, .75f, dark) }), "sprite companion animates")
            val scene = ReadyTheme.tabScene
            val layout = scene.layout(Rect(0f, 0f, 1600f, 30f))
            assertTrue(differs(render(1600, 30) { scene.drawFrame(this, layout, dark, 0f) },
                render(1600, 30) { scene.drawFrame(this, layout, dark, .75f) }), "READY cursor blinks")
        }
    }

    @Test fun computerGlowRemainsTranslucent() {
        val pixels = render(300, 420) { ReadyTheme.picture.draw(this, 0f, true) }.toPixelMap()
        val glow = pixels[10, 120]
        assertTrue(glow.alpha < .7f, "the monitor glow must not become an opaque black oval")
    }

    @Test fun renderDarkAndLightArtworkProof() {
        val captureDir = System.getenv("INDAGIUM_CAPTURE_READY") ?: return
        val bitmap = render(1080, 480) {
            for ((index, dark) in listOf(true, false).withIndex()) {
                val palette = ReadyTheme.palette(dark)
                val origin = index * 540f
                drawRect(palette.bg, Offset(origin, 0f), Size(540f, 480f))
                withTransform({ translate(origin, 50f) }) { ReadyTheme.picture.draw(this, 0f, dark) }
                withTransform({ translate(origin + 300f, 220f) }) { ReadyTheme.companion.draw(this, 0f, dark) }
                val scene = ReadyTheme.tabScene
                withTransform({ translate(origin - 1060f, 10f) }) {
                    scene.drawFrame(this, scene.layout(Rect(0f, 0f, 1600f, 30f)), dark, 0f)
                }
            }
        }
        val pixels = bitmap.toPixelMap()
        val output = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) output.setRGB(x, y, pixels[x, y].toArgb())
        File(captureDir).mkdirs()
        ImageIO.write(output, "png", File(captureDir, "ready-artwork.png"))
    }
}
