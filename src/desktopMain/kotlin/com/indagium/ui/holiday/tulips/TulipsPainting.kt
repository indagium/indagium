@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.tulips

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.indagium.ui.holiday.HolidayCompanionPainter
import com.indagium.ui.holiday.HolidayPicturePainter
import com.indagium.ui.holiday.parsePath
import org.jetbrains.skia.Image
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/** Original user-supplied painting, shared by both placements. */
private val painting by lazy {
    val bytes = requireNotNull(TulipsTheme::class.java.getResourceAsStream("/holiday/tulips/painting.png")).use { it.readBytes() }
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
}

// The PSD omits the canvas color. Restore the sage ground from the original supplied JPEG
// so translucent strokes retain the painting's colors in either application palette.
private val paintingGround = Color(0xFF798577)
private val petal = parsePath("M6 1C9 3 10 7 6 11 2 7 3 3 6 1z")

private data class ArtPetal(val x: Float, val y: Float, val color: Color, val angle: Float, val delayed: Boolean = false)

private val picturePetals = listOf(
    ArtPetal(60f, 30f, Color(0xFF7b2fbf), 20f), ArtPetal(230f, 60f, Color(0xFFd4252e), -30f, true),
    ArtPetal(150f, 10f, Color(0xFFf2d21c), 40f), ArtPetal(270f, 160f, Color(0xFFe46a9a), 10f, true),
)
private val companionPetals = listOf(
    ArtPetal(40f, 20f, Color(0xFF7b2fbf), 25f), ArtPetal(200f, 40f, Color(0xFFf2d21c), -20f, true),
)

private fun DrawScope.artPetals(items: List<ArtPetal>, time: Float, height: Float, size: Float) {
    items.forEach { p ->
        // The canvas uses two 7/8.5-second staggered falling-petal animations.
        val period = if (p.delayed) 8.5f else 7f
        val raw = time / period + if (p.delayed && time > 0f) -3f / period else 0f
        val phase = if (time == 0f) 0f else raw - floor(raw)
        val y = (p.y + phase * height) % height
        val x = p.x + if (time == 0f) 0f else 8f * sin(phase * 2f * PI.toFloat())
        withTransform({
            translate(x - size / 2f, y)
            rotate(p.angle + phase * 260f, Offset(size / 2f, size / 2f))
            scale(size / 12f, size / 12f, Offset.Zero)
        }) {
            drawPath(petal, p.color.copy(alpha = .9f))
        }
    }
}

internal object TulipsPicture : HolidayPicturePainter {
    override val designWidthDp = 300f
    override val designHeightDp = 420f
    private val clip = Path().apply { addRoundRect(RoundRect(Rect(2f, 0f, 298f, 420f), CornerRadius(6f))) }

    override fun draw(scope: DrawScope, t: Float, dark: Boolean) = with(scope) {
        clipPath(clip) {
            drawRect(paintingGround, Offset(2f, 0f), Size(296f, 420f))
            drawImage(painting, dstOffset = IntOffset(2, 0), dstSize = IntSize(296, 420))
        }
        clipRect(0f, 0f, 300f, 420f) { artPetals(picturePetals, t, 420f, 12f) }
    }
}

internal object TulipsCompanion : HolidayCompanionPainter {
    override val label = "Tulip bouquet in the Notes corner"
    override val designWidthDp = 240f
    override val designHeightDp = 240f

    override fun draw(scope: DrawScope, t: Float, dark: Boolean) = with(scope) {
        val image = painting
        val side = minOf(image.width, image.height)
        drawIntoCanvas { it.saveLayer(Rect(4f, 4f, 236f, 236f), Paint()) }
        drawRect(paintingGround, Offset(4f, 4f), Size(232f, 232f))
        drawImage(image, srcOffset = IntOffset((image.width - side) / 2, (image.height - side) / 2), srcSize = IntSize(side, side),
            dstOffset = IntOffset(4, 4), dstSize = IntSize(232, 232))
        drawRect(
            brush = Brush.radialGradient(0f to Color.White, .6f to Color.White, .71f to Color.Transparent, 1f to Color.Transparent,
                center = Offset(120f, 120f), radius = 164.05f),
            topLeft = Offset(4f, 4f), size = Size(232f, 232f), blendMode = BlendMode.DstIn,
        )
        drawIntoCanvas { it.restore() }
        clipRect(0f, 0f, 240f, 240f) { artPetals(companionPetals, t, 240f, 10.8f) }
    }
}
