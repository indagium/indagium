// Normalized scene geometry for the free tab-strip area; every reported bound stays within its slot.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.manor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.ui.holiday.HolidaySceneLayout
import com.indagium.ui.holiday.HolidayScenePainter
import kotlin.math.min

internal data class ManorGrave(val bounds: Rect, val cross: Boolean)
internal data class ManorWeb(val origin: Offset, val reach: Float, val xSign: Float, val ySign: Float)

internal data class ManorTabSceneLayout(
    override val slot: Rect,
    val scale: Float,
    val moon: Offset,
    val moonRadius: Float,
    val house: Rect?,
    val graves: List<ManorGrave>,
    val webs: List<ManorWeb>,
    val fogBand: Rect,
) : HolidaySceneLayout {
    override val elementBounds: List<Rect> get() = buildList {
        if (moonRadius > 0f) add(Rect(moon.x - moonRadius, moon.y - moonRadius, moon.x + moonRadius, moon.y + moonRadius))
        house?.let(::add)
        graves.mapTo(this) { it.bounds }
        webs.mapTo(this) { w ->
            Rect(
                min(w.origin.x, w.origin.x + w.xSign * w.reach),
                min(w.origin.y, w.origin.y + w.ySign * w.reach),
                maxOf(w.origin.x, w.origin.x + w.xSign * w.reach),
                maxOf(w.origin.y, w.origin.y + w.ySign * w.reach),
            )
        }
        add(fogBand)
    }
}

internal fun manorTabSceneLayout(slot: Rect, vertical: Boolean): ManorTabSceneLayout = if (vertical) verticalManorLayout(slot) else horizontalManorLayout(slot)

private fun horizontalManorLayout(s: Rect): ManorTabSceneLayout {
    val scale = min(s.height / 38f, s.width / 190f).coerceAtLeast(0f)
    if (scale == 0f) return ManorTabSceneLayout(s, 0f, Offset(s.left, s.top), 0f, null, emptyList(), emptyList(), Rect(s.left, s.top, s.right, s.bottom))
    val base = s.bottom - 2f * scale
    val house = Rect(s.left + 49f * scale, base - 28f * scale, s.left + 101f * scale, base)
    val graves = listOf(
        ManorGrave(Rect(s.left + 119f * scale, base - 12f * scale, s.left + 130f * scale, base), cross = false),
        ManorGrave(Rect(s.left + 143f * scale, base - 15f * scale, s.left + 153f * scale, base), cross = true),
        ManorGrave(Rect(s.left + 169f * scale, base - 10f * scale, s.left + 178f * scale, base), cross = false),
    )
    val webOrigin = Offset(s.right, s.top)
    return ManorTabSceneLayout(
        slot = s, scale = scale,
        moon = Offset(s.left + 17f * scale, s.top + 12f * scale), moonRadius = 6.5f * scale,
        house = house, graves = graves,
        webs = listOf(ManorWeb(webOrigin, 15f * scale, -1f, 1f)),
        fogBand = Rect(s.left, s.bottom - 8f * scale, s.right, s.bottom),
    )
}

private fun verticalManorLayout(s: Rect): ManorTabSceneLayout {
    val scale = min(s.width / 110f, s.height / 280f).coerceAtLeast(0f)
    if (scale == 0f) return ManorTabSceneLayout(s, 0f, Offset(s.left, s.top), 0f, null, emptyList(), emptyList(), Rect(s.left, s.top, s.right, s.bottom))
    val centerX = (s.left + s.right) / 2f
    val base = s.top + s.height * 0.84f
    val house = Rect(centerX - 27f * scale, base - 40f * scale, centerX + 27f * scale, base)
    val graves = listOf(
        ManorGrave(Rect(s.left + s.width * 0.16f, base + 3f * scale, s.left + s.width * 0.27f, base + 17f * scale), cross = false),
        ManorGrave(Rect(s.right - s.width * 0.29f, base + 1f * scale, s.right - s.width * 0.18f, base + 17f * scale), cross = true),
    )
    return ManorTabSceneLayout(
        slot = s, scale = scale,
        moon = Offset(centerX, s.top + 31f * scale), moonRadius = 12f * scale,
        house = house, graves = graves,
        webs = listOf(
            ManorWeb(Offset(s.left, s.top), 17f * scale, 1f, 1f),
            ManorWeb(Offset(s.right, s.top), 17f * scale, -1f, 1f),
        ),
        fogBand = Rect(s.left, s.bottom - 15f * scale, s.right, s.bottom),
    )
}

internal fun DrawScope.drawManorTabScene(layout: ManorTabSceneLayout, colors: ManorSceneColors) {
    val s = layout.scale
    if (s <= 0f) return
    val slot = layout.slot
    drawRect(colors.sky.copy(alpha = 0.30f), topLeft = slot.topLeft, size = slot.size)
    withTransform({ clipRect(slot.left, slot.top, slot.right, slot.bottom) }) {
        drawCircle(colors.moon.copy(alpha = 0.82f), layout.moonRadius, layout.moon)
        drawCircle(colors.crater.copy(alpha = 0.45f), layout.moonRadius * 0.18f, Offset(layout.moon.x - layout.moonRadius * 0.23f, layout.moon.y - layout.moonRadius * 0.1f))
        layout.house?.let { drawManorSilhouette(it, colors, s) }
        layout.graves.forEach { drawGrave(it, colors, s) }
        layout.webs.forEach { drawManorWeb(it.origin, it.reach, colors.web, it.xSign, it.ySign, 0.55f) }
        drawFogBand(layout.fogBand, colors)
    }
}

private fun DrawScope.drawManorSilhouette(r: Rect, colors: ManorSceneColors, scale: Float) {
    val house = Path().apply {
        moveTo(r.left, r.bottom)
        lineTo(r.left, r.top + 7f * scale)
        lineTo(r.left + 12f * scale, r.top + 13f * scale)
        lineTo(r.left + 12f * scale, r.top + 5f * scale)
        lineTo(r.left + 23f * scale, r.top)
        lineTo(r.left + 33f * scale, r.top + 7f * scale)
        lineTo(r.left + 40f * scale, r.top + 4f * scale)
        lineTo(r.right, r.top + 12f * scale)
        lineTo(r.right, r.bottom)
        close()
    }
    drawPath(house, colors.hill)
    val roof = Path().apply {
        moveTo(r.left + 12f * scale, r.top + 8f * scale)
        lineTo(r.left + 23f * scale, r.top)
        lineTo(r.left + 33f * scale, r.top + 7f * scale)
        lineTo(r.left + 40f * scale, r.top + 4f * scale)
        lineTo(r.right, r.top + 12f * scale)
    }
    drawPath(roof, colors.stone, style = Stroke(1.2f * scale))
    val windowSize = 3f * scale
    for (wx in listOf(r.left + 19f * scale, r.left + 37f * scale)) {
        drawRect(colors.lantern.copy(alpha = 0.72f), Offset(wx, r.top + 16f * scale), androidx.compose.ui.geometry.Size(windowSize, 5f * scale))
    }
    drawRect(colors.lantern.copy(alpha = 0.58f), Offset(r.left + 30f * scale, r.top + 20f * scale), androidx.compose.ui.geometry.Size(4f * scale, 8f * scale))
}

private fun DrawScope.drawGrave(g: ManorGrave, colors: ManorSceneColors, scale: Float) {
    val r = g.bounds
    val top = r.top
    val arch = Path().apply {
        moveTo(r.left, r.bottom)
        lineTo(r.left, top + 3f * scale)
        quadraticTo(r.center.x, top - 1f * scale, r.right, top + 3f * scale)
        lineTo(r.right, r.bottom)
        close()
    }
    drawPath(arch, colors.stone)
    drawLine(colors.web.copy(alpha = 0.68f), Offset(r.left + 2f * scale, top + 5f * scale), Offset(r.right - 2f * scale, top + 5f * scale), 0.8f * scale)
    if (g.cross) {
        drawLine(colors.web, Offset(r.center.x, top + 6f * scale), Offset(r.center.x, top + 10f * scale), 0.8f * scale)
        drawLine(colors.web, Offset(r.center.x - 2f * scale, top + 7f * scale), Offset(r.center.x + 2f * scale, top + 7f * scale), 0.8f * scale)
    }
}

private fun DrawScope.drawFogBand(r: Rect, colors: ManorSceneColors) {
    val p = Path().apply {
        moveTo(r.left, r.bottom)
        lineTo(r.left, r.top + r.height * 0.55f)
        cubicTo(r.left + r.width * 0.22f, r.top, r.left + r.width * 0.36f, r.bottom, r.left + r.width * 0.52f, r.top + r.height * 0.32f)
        cubicTo(r.left + r.width * 0.72f, r.top - r.height * 0.1f, r.left + r.width * 0.84f, r.bottom, r.right, r.top + r.height * 0.4f)
        lineTo(r.right, r.bottom)
        close()
    }
    drawPath(p, colors.fog)
}

internal object ManorTabScenePainter : HolidayScenePainter {
    override fun layout(slot: Rect, vertical: Boolean): HolidaySceneLayout = manorTabSceneLayout(slot, vertical)

    override fun draw(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean) {
        with(scope) { drawManorTabScene(layout as ManorTabSceneLayout, manorSceneColors(dark)) }
    }
}
