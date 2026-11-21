@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toAwtImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proof renders of the six generated arts: with `INDAGIUM_CAPTURE_LAYERS=<dir>` every pack's picture, companion and title bar is written as a PNG
 * at 2x on a dark and on a light background (the still frame and an animated one). Without the variable the test does nothing.
 */
class HolidayLayersCaptureTest {
    private val darkBackground = Color(0xFF1B1D24)
    private val lightBackground = Color(0xFFF6F3EC)
    private val scale = 2f
    private val gutter = 20

    private fun DrawScope.art(folder: String, layers: List<HolidayArtLayer>, x: Int, y: Int, t: Float, still: Boolean) =
        withTransform({ translate(x * scale, y * scale); scale(scale, scale, Offset.Zero) }) { drawHolidayLayers(folder, layers, t, still) }

    private val sheetWidth = ((300 + 240 + 3 * gutter) * scale).toInt()
    private val sheetHeight = ((420 * 2 + 3 * gutter) * scale).toInt()

    private fun sheet(art: GeneratedArt, t: Float, still: Boolean) = renderBitmap(sheetWidth, sheetHeight) {
        for ((row, background) in listOf(darkBackground, lightBackground).withIndex()) {
            val top = gutter + row * (420 + gutter)
            drawRect(background, Offset(0f, (top - gutter / 2) * scale), androidx.compose.ui.geometry.Size(size.width, (420 + gutter) * scale))
            art(art.folder, art.picture, gutter, top, t, still)
            art(art.folder, art.companion, 300 + 2 * gutter, top, t, still)
        }
    }

    private fun title(art: GeneratedArt, width: Int) = HolidayBandScene(art.folder, art.title).let { scene ->
        val h = 30
        renderBitmap((width * scale).toInt(), (h * 2 * scale).toInt()) {
            for ((row, dark) in listOf(true, false).withIndex()) {
                val band = Rect(0f, row * h * scale, width * scale, (row + 1) * h * scale)
                drawRect(if (dark) darkBackground else lightBackground, band.topLeft, androidx.compose.ui.geometry.Size(band.width, band.height))
                scene.drawFrame(this, scene.layout(band), dark, HOLIDAY_STILL_TIME)
            }
        }
    }

    @Test fun writesTheProofRendersWhenAskedTo() {
        val dir = System.getenv("INDAGIUM_CAPTURE_LAYERS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        dir.mkdirs()
        for (art in GENERATED_ARTS) {
            ImageIO.write(sheet(art, HOLIDAY_STILL_TIME, true).toAwtImage(), "png", File(dir, "${art.folder}-still.png"))
            ImageIO.write(sheet(art, 2.3f, false).toAwtImage(), "png", File(dir, "${art.folder}-t2.3.png"))
            if (art.title.isNotEmpty()) ImageIO.write(title(art, 1600).toAwtImage(), "png", File(dir, "${art.folder}-title.png"))
        }
        assertTrue(dir.listFiles().orEmpty().size >= GENERATED_ARTS.size * 2)
    }
}
