package com.indagium.ui.holiday

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color

// The data shape of a pack's generated art (see the packs' generated `…ArtData.kt` files). A picture or companion is an ordered list of
// layers, drawn bottom first: each layer is a static SVG resource or a run of text (Skia's SVGDOM draws no text), and carries the
// stack of design-canvas animations that move it, outermost first. A title bar is an ordered list of band items: a backdrop item is
// stretched over the band, a part item is placed right-aligned and dropped whole when the band is too narrow (HolidayBandLayout).

/**
 * One CSS animation of the design canvas acting on a layer: its class ([cls], e.g. "s-twinkle"), the transform origin in the animated
 * element's own coordinates (the canvas animates with `transform-box: fill-box`, so this is already the right point of its box), and
 * [parent], the matrix from the element's parent to the art's root as `a b c d e f` (SVG order). The animated transform T (about the
 * origin) is applied to the root as `parent · T · parent⁻¹`.
 */
internal data class HolidayLayerAnim(val cls: String, val originX: Float, val originY: Float, val parent: List<Float>)

internal enum class HolidayTextAnchor { START, MIDDLE, END }

/** A linear gradient over the text's own bounding box ([x1]..[y2] in 0..1), as an SVG objectBoundingBox gradient. */
internal data class HolidayTextGradient(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val stops: List<Pair<Float, Color>>)

internal sealed interface HolidayArtLayer {
    val anims: List<HolidayLayerAnim>

    /** The SVG resource `/holiday/<folder>/<name>.svg`, in the art's own coordinates. */
    data class Svg(val name: String, override val anims: List<HolidayLayerAnim> = emptyList()) : HolidayArtLayer

    /**
     * A text run at ([x], [y]) (the baseline, anchored by [anchor]) under [matrix] (`a b c d e f`, the text element's own matrix to the
     * root). [families] are the CSS font families in preference order ("monospace", "serif", "sans-serif" and "cursive" are generic).
     * The fill is [fill] or [gradient]; a [strokeWidth] > 0 strokes it in [stroke], under the fill when [strokeFirst] (paint-order stroke).
     * [clip] (rounded by [clipRadius]) is a clip the design puts on an ancestor group, in the art's root coordinates: it stays put while
     * [anims] move the text (a scrolling terminal clipped to its screen). Null draws unclipped.
     */
    data class Text(
        val text: String,
        val x: Float,
        val y: Float,
        val matrix: List<Float>,
        val sizePx: Float,
        val families: List<String>,
        val weight: Int,
        val italic: Boolean,
        val fill: Color?,
        val gradient: HolidayTextGradient?,
        val stroke: Color?,
        val strokeWidth: Float,
        val strokeFirst: Boolean,
        val alpha: Float,
        val anchor: HolidayTextAnchor,
        override val anims: List<HolidayLayerAnim> = emptyList(),
        val clip: Rect? = null,
        val clipRadius: Float = 0f,
    ) : HolidayArtLayer
}

/**
 * One item of a generated title-bar scene, in draw order: SVG resources for the dark and the light palette ([dark] and [light], the
 * same name when the item uses no theme colour) or text, and [partKey] null for a backdrop (stretched) or the band part it belongs to,
 * whose [partRef] is that part's rectangle on the 1600 x 30 title bar.
 */
internal data class HolidayBandItem(
    val dark: HolidayArtLayer,
    val light: HolidayArtLayer,
    val partKey: String?,
    val partRef: Rect?,
)
