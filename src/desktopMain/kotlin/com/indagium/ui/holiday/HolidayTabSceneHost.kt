package com.indagium.ui.holiday

import androidx.compose.foundation.background
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawTransform
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import com.indagium.model.TabStripPosition

// The scene in the tab bar: under a seasonal pack the tab bar's own background is the theme's [HolidayScenePainter] picture (the native title
// bar can't be themed, so this stands in for it). It is drawn BEHIND the tabs, over the tab strip's span of the bar only (ChromeLayout.kt's
// `sceneSpan`: the bar less a toolbar sharing it), so tabs keep their whole width, cover it where they are, and nothing is painted under the
// toolbar. It shows for a horizontal tab strip on the TOP edge while the pack's "Scene in the title bar" switch is on. This file is the
// framework side: when it shows, the space a composition is laid out in and the bar's background modifier.

/** Height (dp) of the title bar the compositions are authored on (1600 x 30): one design unit is one dp on a bar of this height. */
internal const val HOLIDAY_SCENE_REFERENCE_HEIGHT_DP = 30f

/** Width of the title bar the compositions were authored on; the elements are placed on it. */
internal const val HOLIDAY_SCENE_REFERENCE_WIDTH = 1600f

// How far (design units) a right-aligned element keeps from the span's left edge: a narrower span leaves it out instead of cutting it.
private const val BAND_EDGE_MARGIN = 6f

/**
 * Whether the scene shows: while a pack's scene is on ([sceneOn]: a pack with a scene and its "Scene in the title bar" switch) and the tab
 * strip is horizontal on the TOP edge. A bottom, left or right tab strip (a vertical one included) has none.
 */
internal fun holidaySceneShown(sceneOn: Boolean, tabPosition: TabStripPosition): Boolean = sceneOn && tabPosition == TabStripPosition.TOP

/**
 * The part of a horizontal tab bar the scene is drawn in, in the bar's own pixels, written by the scaffold's Layout from the chrome frames
 * and read while drawing. [left] / [right] are the tab strip's span; both 0 while there is none.
 */
internal class HolidaySceneSpan {
    var left by mutableStateOf(0f)
        private set
    var right by mutableStateOf(0f)
        private set

    /** Sets the span from [range] (null for none). */
    fun set(range: ClosedFloatingPointRange<Float>?) {
        val l = range?.start ?: 0f
        val r = range?.endInclusive ?: 0f
        if (l != left) left = l
        if (r != right) right = r
    }
}

/**
 * The room a composition authored on the 1600 x 30 title bar is laid out in, for a [band] in pixels (the tab strip's span of the bar, as tall as
 * the bar). Elements scale with the bar's height ([unit] pixels per design unit: natural size on a 30 dp bar) and are right-aligned ([shift]),
 * the middle of a wide band staying clear; a backdrop (hills, ground) is stretched over the whole width instead.
 */
internal class HolidayBandSpace(val band: Rect) {
    val unit: Float = (band.height / HOLIDAY_SCENE_REFERENCE_HEIGHT_DP).coerceAtLeast(0f)

    /** Pixels the reference x = 0 of a right-aligned element sits from the band's left edge (negative in a band narrower than the reference). */
    val shift: Float = band.width - HOLIDAY_SCENE_REFERENCE_WIDTH * unit

    /** The reference rectangle [ref] (design units) in band pixels, right-aligned. */
    fun place(ref: Rect): Rect = Rect(
        band.left + shift + ref.left * unit, band.top + ref.top * unit,
        band.left + shift + ref.right * unit, band.top + ref.bottom * unit,
    )

    /** [ref] placed, or null when the band is too narrow to hold it whole. */
    fun fit(ref: Rect): Rect? = place(ref).takeIf { unit > 0f && it.left >= band.left + BAND_EDGE_MARGIN * unit && it.right <= band.right }

    /** Draws [block] in reference coordinates with right-aligned elements, clipped to the band. */
    fun drawElements(scope: DrawScope, block: DrawScope.() -> Unit) = scope.withTransform({
        clipBand()
        translate(band.left + shift, band.top)
        scale(unit, unit, Offset.Zero)
    }, block)

    /** Draws [block] in reference coordinates stretched over the band's whole width (and its height at [unit]), clipped to the band. */
    fun drawBackdrop(scope: DrawScope, block: DrawScope.() -> Unit) = scope.withTransform({
        clipBand()
        translate(band.left, band.top)
        scale(band.width / HOLIDAY_SCENE_REFERENCE_WIDTH, unit, Offset.Zero)
    }, block)

    /** Draws [block] in design units from the band's left edge (a composition that runs the whole width), clipped to the band. */
    fun drawFromStart(scope: DrawScope, block: DrawScope.() -> Unit) = scope.withTransform({
        clipBand()
        translate(band.left, band.top)
        scale(unit, unit, Offset.Zero)
    }, block)

    private fun DrawTransform.clipBand() = clipRect(band.left, band.top, band.right, band.bottom)
}

/** One element of a band composition: a [key] the painter draws by and its rectangle [ref] on the 1600 x 30 reference title bar. */
internal class HolidayBandPart(val key: String, val ref: Rect)

/**
 * The layout of a right-aligned composition: the [parts] that fit whole in [space]'s band (a narrower band leaves the ones that do not out,
 * so no element is ever cut). [elementBounds] are their rectangles in band pixels, all inside the band.
 */
internal class HolidayBandLayout(val space: HolidayBandSpace, parts: List<HolidayBandPart>) : HolidaySceneLayout {
    override val band: Rect = space.band
    private val fitted: Map<String, Rect> = parts.mapNotNull { part -> space.fit(part.ref)?.let { part.key to it } }.toMap()
    override val elementBounds: List<Rect> = fitted.values.toList()

    /** Whether the part named [key] fits the band and is drawn. */
    fun has(key: String): Boolean = key in fitted
}

/**
 * The tab bar's background under a seasonal pack: the chrome's bar colour, cut to [shape] (a card bar's corners; the default joined bar passes
 * null and stays square), with the theme's [painter] picture drawn over [span] only, the tab strip's part of the bar. The tabs are drawn over
 * it. [clock] drives an animated scene (the caller passes it only while one runs); without it the scene is a still frame.
 */
internal fun Modifier.holidayTabBarScene(
    look: HolidayLook,
    painter: HolidayScenePainter,
    shape: Shape? = null,
    span: HolidaySceneSpan? = null,
    clock: State<Float>? = null,
): Modifier {
    val outline = shape ?: RectangleShape
    return background(look.chrome.tabBar, outline).drawWithCache {
        val left = span?.left ?: 0f
        val right = minOf(span?.right ?: 0f, size.width)
        val layout = if (right - left > 0f) painter.layout(Rect(left, 0f, right, size.height)) else null
        val clip = Path().apply { addOutline(outline.createOutline(size, layoutDirection, this@drawWithCache)) }
        onDrawBehind {
            if (layout != null) {
                val time = if (look.effects.animatePicture) (clock?.value ?: HOLIDAY_STILL_TIME) * look.effects.speedScale else HOLIDAY_STILL_TIME
                clipPath(clip) { clipRect(left, 0f, right, size.height) { painter.drawFrame(this, layout, look.isDark, time) } }
            }
        }
    }
}
