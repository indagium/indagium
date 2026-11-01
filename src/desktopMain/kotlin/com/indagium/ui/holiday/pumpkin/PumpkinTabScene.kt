// The scene's coordinates are literal composition values from the approved holiday canvas (a 1600 x 30 title bar), not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday.pumpkin

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.indagium.ui.*
import com.indagium.ui.holiday.HolidaySceneLayout
import com.indagium.ui.holiday.HolidayScenePainter

// The pumpkin patch's picture behind the tab strip: a night sky, a moon with craters, rolling hills along the bottom and a few pumpkins.
// [pumpkinTabSceneLayout] is pure geometry in pixels of the bar's slot; [drawPumpkinTabScene] only paints it.

/** A circle in bar pixels. */
internal data class SceneCircle(val cx: Float, val cy: Float, val r: Float)

/** A pumpkin: a body ellipse centred at ([cx], [cy]) with radii ([rx], [ry]); [stem] draws a small green stalk; [bright] picks the lighter orange. */
internal data class ScenePumpkin(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val stem: Boolean, val bright: Boolean)

/**
 * The scene's geometry inside [slot] (bar pixels). [hill] is a polyline from the slot's start to its end; it closes down to the slot's
 * bottom. [moon] is null (with no craters or pumpkins) when a vertical slot is too short to hold them.
 */
internal data class PumpkinTabSceneLayout(
    override val slot: Rect,
    val moon: SceneCircle?,
    val craters: List<SceneCircle>,
    val hill: List<Offset>,
    val pumpkins: List<ScenePumpkin>,
) : HolidaySceneLayout {
    override val elementBounds: List<Rect>
        get() = listOfNotNull(moon?.bounds()) + craters.map { it.bounds() } + pumpkins.map { it.bounds() } + hill.map { Rect(it.x, it.y, it.x, it.y) }

    private fun ScenePumpkin.bounds() = Rect(cx - rx, cy - ry, cx + rx, cy + ry)

    private fun SceneCircle.bounds() = Rect(cx - r, cy - r, cx + r, cy + r)
}

// The canvas's hill outline in its 1600 x 30 reference, as (x, y) pairs.
private val HILL_REFERENCE = listOf(
    0f to 24f, 260f to 23f, 560f to 21f, 860f to 25f, 1160f to 18f, 1460f to 23f, 1600f to 20f,
)
private const val REFERENCE_WIDTH = 1600f
private const val REFERENCE_HEIGHT = 30f

// The horizontal composition in units of u (the bar height / 30): the moon's left edge to the last pumpkin's right edge is
// MOON_RADIUS + MOON_TO_PUMPKIN * k + PUMPKIN_PAIR_GAP + PAIR_TO_SMALL * k + SMALL_RADIUS, where k in [MIN_SPREAD, 1] squeezes only the two
// long gaps so the elements keep their size in a narrow slot.
private const val MOON_RADIUS_U = 10f
private const val MOON_TO_PUMPKIN_U = 70f
private const val PUMPKIN_PAIR_GAP_U = 12f
private const val PAIR_TO_SMALL_U = 168f
private const val SMALL_RADIUS_U = 5f
private const val RIGHT_MARGIN_U = 4f
private const val FIXED_EXTENT_U = MOON_RADIUS_U + PUMPKIN_PAIR_GAP_U + SMALL_RADIUS_U + RIGHT_MARGIN_U
private const val SPREAD_EXTENT_U = MOON_TO_PUMPKIN_U + PAIR_TO_SMALL_U
private const val MIN_SPREAD = 0.25f
private const val MIN_EXTENT_U = FIXED_EXTENT_U + SPREAD_EXTENT_U * MIN_SPREAD

/**
 * Lays the scene out inside [slot]. A horizontal slot follows the canvas (moon, two pumpkins and a smaller one to its right, hills along
 * the bottom), right-aligned and scaled to the slot's height; the gaps squeeze when the slot is narrow. A [vertical] slot keeps it subtle:
 * a hill band along the bottom with the moon above it and a single pumpkin on the hill, all left out when the slot is too short.
 */
internal fun pumpkinTabSceneLayout(slot: Rect, vertical: Boolean): PumpkinTabSceneLayout =
    if (vertical) verticalLayout(slot) else horizontalLayout(slot)

private fun horizontalLayout(s: Rect): PumpkinTabSceneLayout {
    val u = minOf(s.height / REFERENCE_HEIGHT, s.width / MIN_EXTENT_U)

    fun y(ref: Float) = s.bottom - (REFERENCE_HEIGHT - ref) * u
    val spread = ((s.width / u - FIXED_EXTENT_U) / SPREAD_EXTENT_U).coerceIn(MIN_SPREAD, 1f)
    val small = s.right - (RIGHT_MARGIN_U + SMALL_RADIUS_U) * u
    val pair = small - PAIR_TO_SMALL_U * spread * u
    val first = pair - PUMPKIN_PAIR_GAP_U * u
    val moon = SceneCircle(first - MOON_TO_PUMPKIN_U * spread * u, y(14f), MOON_RADIUS_U * u)
    return PumpkinTabSceneLayout(
        slot = s,
        moon = moon,
        craters = listOf(
            SceneCircle(moon.cx - 4f * u, moon.cy - 3f * u, 2f * u),
            SceneCircle(moon.cx + 4f * u, moon.cy + 4f * u, 1.5f * u),
        ),
        hill = HILL_REFERENCE.map { (rx, ry) -> Offset(s.left + s.width * rx / REFERENCE_WIDTH, y(ry)) },
        pumpkins = listOf(
            ScenePumpkin(first, y(23f), 6f * u, 4.4f * u, stem = true, bright = false),
            ScenePumpkin(pair, y(25f), 4.4f * u, 3.2f * u, stem = false, bright = true),
            ScenePumpkin(small, y(24f), SMALL_RADIUS_U * u, 3.8f * u, stem = false, bright = false),
        ),
    )
}

private fun verticalLayout(s: Rect): PumpkinTabSceneLayout {
    val w = s.width
    val band = minOf(s.height * VERTICAL_BAND_SHARE, VERTICAL_BAND_MAX_PX)
    val top = s.bottom - band
    val hill = listOf(
        Offset(s.left, top + band * 0.35f), Offset(s.left + w * 0.3f, top + band * 0.2f),
        Offset(s.left + w * 0.65f, top + band * 0.45f), Offset(s.right, top + band * 0.3f),
    )
    val moonR = minOf(w * VERTICAL_MOON_SHARE, VERTICAL_MOON_MAX_PX)
    // The tabs reach the band: only the hills are left.
    if (top - s.top < moonR * VERTICAL_MOON_ROOM) return PumpkinTabSceneLayout(s, null, emptyList(), hill, emptyList())
    val moon = SceneCircle(s.left + w * VERTICAL_MOON_X, top - moonR * 1.6f, moonR)
    val pumpkinR = moonR * 0.7f
    return PumpkinTabSceneLayout(
        slot = s,
        moon = moon,
        craters = listOf(
            SceneCircle(moon.cx - moonR * 0.4f, moon.cy - moonR * 0.3f, moonR * 0.2f),
            SceneCircle(moon.cx + moonR * 0.4f, moon.cy + moonR * 0.4f, moonR * 0.15f),
        ),
        hill = hill,
        pumpkins = listOf(ScenePumpkin(s.left + w * 0.35f, top + band * 0.55f, pumpkinR, pumpkinR * 0.73f, stem = true, bright = false)),
    )
}

private const val VERTICAL_BAND_SHARE = 0.1f
private const val VERTICAL_BAND_MAX_PX = 72f
private const val VERTICAL_MOON_SHARE = 0.12f
private const val VERTICAL_MOON_MAX_PX = 18f
private const val VERTICAL_MOON_X = 0.72f
private const val VERTICAL_MOON_ROOM = 2.6f
private const val STEM_STROKE_SHARE = 0.3f
private const val STEM_HEIGHT_SHARE = 0.55f
private const val SKY_TOP_ALPHA = 0f

/** Paints the whole scene in the bar's own coordinates. The bar's background itself (scene.tabBar) is drawn by the caller. */
internal fun DrawScope.drawPumpkinTabScene(layout: PumpkinTabSceneLayout, scene: PumpkinSceneColors) {
    drawSky(layout.slot, scene)
    drawMoon(layout, scene)
    drawHills(layout, scene.hill)
    layout.pumpkins.forEach { drawScenePumpkin(it, scene) }
}

// A faint wash from the bar colour at the top to the sky colour at the bottom of the slot.
private fun DrawScope.drawSky(slot: Rect, scene: PumpkinSceneColors) {
    drawRect(
        Brush.verticalGradient(listOf(scene.sky.copy(alpha = SKY_TOP_ALPHA), scene.sky), startY = slot.top, endY = slot.bottom),
        topLeft = slot.topLeft,
        size = slot.size,
    )
}

private fun DrawScope.drawMoon(layout: PumpkinTabSceneLayout, scene: PumpkinSceneColors) {
    val moon = layout.moon ?: return
    drawCircle(scene.moon, moon.r, Offset(moon.cx, moon.cy))
    layout.craters.forEach { drawCircle(scene.crater, it.r, Offset(it.cx, it.cy)) }
}

// The hills as a smooth curve through the outline points (quadratic segments joined at midpoints), filled down to the slot's bottom edge.
private fun DrawScope.drawHills(layout: PumpkinTabSceneLayout, color: Color) {
    val pts = layout.hill
    if (pts.size < 2) return
    val bottom = layout.slot.bottom
    val path = Path().apply {
        moveTo(pts.first().x, bottom)
        lineTo(pts.first().x, pts.first().y)
        for (i in 1 until pts.size - 1) {
            val mid = Offset((pts[i].x + pts[i + 1].x) / 2f, (pts[i].y + pts[i + 1].y) / 2f)
            quadraticTo(pts[i].x, pts[i].y, mid.x, mid.y)
        }
        lineTo(pts.last().x, pts.last().y)
        lineTo(pts.last().x, bottom)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawScenePumpkin(p: ScenePumpkin, scene: PumpkinSceneColors) {
    val body = if (p.bright) scene.pumpkinLight else scene.pumpkin
    drawOval(body, Offset(p.cx - p.rx, p.cy - p.ry), Size(p.rx * 2f, p.ry * 2f))
    if (p.stem) {
        val top = Offset(p.cx, p.cy - p.ry + 1f)
        drawLine(scene.stem, top, Offset(top.x, top.y - p.ry * STEM_HEIGHT_SHARE * 2f), p.ry * STEM_STROKE_SHARE, StrokeCap.Round)
    }
}

/** The pumpkin patch's [HolidayScenePainter]. */
internal object PumpkinTabScenePainter : HolidayScenePainter {
    override fun layout(slot: Rect, vertical: Boolean): HolidaySceneLayout = pumpkinTabSceneLayout(slot, vertical)

    override fun draw(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean) {
        val l = layout as PumpkinTabSceneLayout
        with(scope) { drawPumpkinTabScene(l, pumpkinSceneColors(dark)) }
    }
}
