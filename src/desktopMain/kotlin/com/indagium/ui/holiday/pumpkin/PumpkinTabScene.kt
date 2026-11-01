// The scene's coordinates are literal composition values from the approved holiday canvas (its 1600 x 30 title bar), not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.pumpkin

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.indagium.ui.holiday.HolidayBandLayout
import com.indagium.ui.holiday.HolidayBandPart
import com.indagium.ui.holiday.HolidayBandSpace
import com.indagium.ui.holiday.HolidaySceneLayout
import com.indagium.ui.holiday.HolidayScenePainter
import com.indagium.ui.holiday.parsePath

// The pumpkin patch's picture in the scene band: a night sky, a moon with craters, rolling hills along the bottom and a few pumpkins, as in the
// design's title bar (`is.pumpkin`). The hills are a backdrop stretched over the whole band; the moon and the pumpkins keep their natural size
// and sit at the right (the middle stays clear), the ones a narrow band cannot hold whole are left out.

private val MOON = Rect(1069f, 3f, 1091f, 25f)
private val PUMPKINS = Rect(1143f, 16f, 1336f, 29f)
private val PARTS = listOf(HolidayBandPart("moon", MOON), HolidayBandPart("pumpkins", PUMPKINS))

private val HILL = parsePath("M0 30V24Q120 16 260 23T560 21T860 25T1160 18T1460 23T1600 20V30Z")
private const val SKY_TOP_ALPHA = 0f

internal fun pumpkinTabSceneLayout(band: Rect): HolidayBandLayout = HolidayBandLayout(HolidayBandSpace(band), PARTS)

/** Paints the scene in the band's own coordinates. The bar colour itself (scene.tabBar) is drawn by the caller. */
internal fun DrawScope.drawPumpkinTabScene(layout: HolidayBandLayout, scene: PumpkinSceneColors) {
    val band = layout.band
    // A faint wash from the bar colour at the top to the sky colour at the bottom of the band.
    drawRect(
        Brush.verticalGradient(listOf(scene.sky.copy(alpha = SKY_TOP_ALPHA), scene.sky), startY = band.top, endY = band.bottom),
        topLeft = band.topLeft,
        size = band.size,
    )
    if (layout.has("moon")) {
        layout.space.drawElements(this) {
            drawCircle(scene.moon, 10f, Offset(1080f, 14f))
            drawCircle(scene.crater, 2f, Offset(1076f, 11f))
            drawCircle(scene.crater, 1.5f, Offset(1084f, 18f))
        }
    }
    layout.space.drawBackdrop(this) { drawPath(HILL, scene.hill) }
    if (layout.has("pumpkins")) {
        layout.space.drawElements(this) {
            drawOval(scene.pumpkin, Offset(1150f - 6f, 23f - 4.4f), Size(12f, 8.8f))
            drawOval(scene.pumpkinLight, Offset(1162f - 4.4f, 25f - 3.2f), Size(8.8f, 6.4f))
            drawLine(scene.stem, Offset(1150f, 19f), Offset(1150f, 16.4f), 1.6f, StrokeCap.Round)
            drawOval(scene.pumpkin, Offset(1330f - 5f, 24f - 3.8f), Size(10f, 7.6f))
        }
    }
}

/** The pumpkin patch's [HolidayScenePainter]. */
internal object PumpkinTabScenePainter : HolidayScenePainter {
    override fun layout(band: Rect): HolidaySceneLayout = pumpkinTabSceneLayout(band)

    override fun draw(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean) {
        with(scope) { drawPumpkinTabScene(layout as HolidayBandLayout, pumpkinSceneColors(dark)) }
    }
}
