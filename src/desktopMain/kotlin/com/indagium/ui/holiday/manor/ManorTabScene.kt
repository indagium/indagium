// The scene's coordinates are literal composition values from the approved holiday canvas (its 1600 x 30 title bar), not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.manor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.indagium.ui.holiday.HolidayBandLayout
import com.indagium.ui.holiday.HolidayBandPart
import com.indagium.ui.holiday.HolidayBandSpace
import com.indagium.ui.holiday.HolidaySceneLayout
import com.indagium.ui.holiday.HolidayScenePainter
import com.indagium.ui.holiday.parsePath

// The manor's picture in the scene band, as in the design's title bar (`is.manor`): a pale moon, low hills along the bottom, the manor's
// silhouette with three lit windows, a few gravestones and a bare tree. The hills are a backdrop stretched over the band; everything else keeps
// its natural size at the right (the middle stays clear), the pieces a narrow band cannot hold whole are left out.

private val PARTS = listOf(
    HolidayBandPart("moon", Rect(1030f, 3f, 1050f, 23f)),
    HolidayBandPart("graves1", Rect(1099f, 22f, 1117f, 29.5f)),
    HolidayBandPart("house", Rect(1139f, 0f, 1201f, 30f)),
    HolidayBandPart("grave2", Rect(1249f, 21f, 1256f, 28.5f)),
    HolidayBandPart("tree", Rect(1283f, 8f, 1297f, 28.5f)),
)

private val HILL = parsePath("M0 30V25Q200 18 420 24T840 21T1260 25T1600 22V30Z")
private val HOUSE = parsePath("M1140 30V17l6-6 6 6v-3h9V7l6-7 6 7v7h9v3l6-6 6 6v13Z")
private val GRAVES_LEFT = parsePath("M1100 29v-4a2.5 2.5 0 0 1 5 0v4zM1112 29v-3a2 2 0 0 1 4 0v3z")
private val GRAVE_RIGHT = parsePath("M1250 28v-4a2.5 2.5 0 0 1 5 0v4z")
private val TREE = parsePath("M1290 28V14M1290 18l-5-5M1290 15l4-6M1290 22l6-3")
private const val SKY_ALPHA = 0.30f
private const val TREE_STROKE = 1.4f

internal fun manorTabSceneLayout(band: Rect): HolidayBandLayout = HolidayBandLayout(HolidayBandSpace(band), PARTS)

internal fun DrawScope.drawManorTabScene(layout: HolidayBandLayout, colors: ManorSceneColors) {
    val band = layout.band
    drawRect(colors.sky.copy(alpha = SKY_ALPHA), topLeft = band.topLeft, size = band.size)
    if (layout.has("moon")) {
        layout.space.drawElements(this) {
            drawCircle(colors.moon, 9f, Offset(1040f, 13f))
            drawCircle(colors.crater, 1.8f, Offset(1037f, 11f))
        }
    }
    layout.space.drawBackdrop(this) { drawPath(HILL, colors.hill) }
    layout.space.drawElements(this) {
        if (layout.has("house")) {
            drawPath(HOUSE, colors.hill)
            listOf(Offset(1166f, 18f), Offset(1178f, 8f), Offset(1190f, 19f)).forEach { drawRect(colors.lantern, it, Size(2.5f, 3f)) }
        }
        if (layout.has("graves1")) drawPath(GRAVES_LEFT, colors.hill)
        if (layout.has("grave2")) drawPath(GRAVE_RIGHT, colors.hill)
        if (layout.has("tree")) drawPath(TREE, colors.hill, style = Stroke(TREE_STROKE, cap = StrokeCap.Round))
    }
}

internal object ManorTabScenePainter : HolidayScenePainter {
    override fun layout(band: Rect): HolidaySceneLayout = manorTabSceneLayout(band)

    override fun draw(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean) {
        with(scope) { drawManorTabScene(layout as HolidayBandLayout, manorSceneColors(dark)) }
    }
}
