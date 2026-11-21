package com.indagium.ui.holiday

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect

// The backdrop arts of a pack whose picture and companion are generated layers (HolidayArtLayers.kt): the theme only names the SVG folder and
// hands over the layers, and the layered painter (HolidaySvgLayers.kt) draws them, moved by the clock; at HOLIDAY_STILL_TIME it draws the calm pose.

/** The picture under the filters, drawn from [layers] of `/holiday/[folder]` in its own [designWidthDp] x [designHeightDp] (300 x 420 by default). */
internal class HolidayLayerPicture(
    private val folder: String,
    private val layers: List<HolidayArtLayer>,
    override val designWidthDp: Float = PICTURE_WIDTH,
    override val designHeightDp: Float = PICTURE_HEIGHT,
) : HolidayPicturePainter {
    override fun draw(scope: DrawScope, t: Float, dark: Boolean) = with(scope) {
        clipRect(0f, 0f, designWidthDp, designHeightDp) { drawHolidayLayers(folder, layers, t, t == HOLIDAY_STILL_TIME) }
    }

    private companion object {
        const val PICTURE_WIDTH = 300f
        const val PICTURE_HEIGHT = 420f
    }
}

/** The companion in the Notes corner, drawn from [layers] of `/holiday/[folder]` in its own [designWidthDp] x [designHeightDp] (240 x 240 by default). */
internal class HolidayLayerCompanion(
    override val label: String,
    private val folder: String,
    private val layers: List<HolidayArtLayer>,
    override val designWidthDp: Float = COMPANION_SIZE,
    override val designHeightDp: Float = COMPANION_SIZE,
) : HolidayCompanionPainter {
    override fun draw(scope: DrawScope, t: Float, dark: Boolean) = with(scope) {
        clipRect(0f, 0f, designWidthDp, designHeightDp) { drawHolidayLayers(folder, layers, t, t == HOLIDAY_STILL_TIME) }
    }

    private companion object {
        const val COMPANION_SIZE = 240f
    }
}
