// Literal font stacks and geometry of the design canvas, not tunables.
@file:Suppress("MagicNumber")

package com.indagium.ui.holiday

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toArgb
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontSlant
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.GradientStyle
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.svg.SVGDOM
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

// The painter of a pack's generated layered art (HolidayArtLayers.kt, the packs' `…ArtData.kt`). Layers are drawn bottom first. A static layer
// is a cached SVG resource (one SVGDOM per resource); text is drawn natively because Skia's SVGDOM draws none. A layer's animations (the design
// canvas' CSS classes, evaluated by holidayAnimFrame) move it as `parent · T · parent⁻¹` about its own origin, outermost animation first; their
// opacities multiply into one group alpha.

/** A 2D affine transform in SVG's `a b c d e f` order: x' = a x + c y + e, y' = b x + d y + f. */
internal class HolidayAffine(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    /** This applied after [o] (matrix product `this · o`). */
    operator fun times(o: HolidayAffine) = HolidayAffine(
        a * o.a + c * o.b, b * o.a + d * o.b, a * o.c + c * o.d, b * o.c + d * o.d, a * o.e + c * o.f + e, b * o.e + d * o.f + f,
    )

    /** The inverse, or null when the matrix is singular. */
    fun inverse(): HolidayAffine? {
        val det = a * d - b * c
        if (det == 0.0) return null
        return HolidayAffine(d / det, -b / det, -c / det, a / det, (c * f - d * e) / det, (b * e - a * f) / det)
    }

    fun toMatrix33() = Matrix33(a.toFloat(), c.toFloat(), e.toFloat(), b.toFloat(), d.toFloat(), f.toFloat(), 0f, 0f, 1f)

    /** The image of the point ([x], [y]). */
    fun map(x: Double, y: Double): Pair<Double, Double> = (a * x + c * y + e) to (b * x + d * y + f)

    companion object {
        val IDENTITY = HolidayAffine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        fun of(m: List<Float>): HolidayAffine = if (m.size == 6) {
            HolidayAffine(m[0].toDouble(), m[1].toDouble(), m[2].toDouble(), m[3].toDouble(), m[4].toDouble(), m[5].toDouble())
        } else {
            IDENTITY
        }

        fun translate(x: Double, y: Double) = HolidayAffine(1.0, 0.0, 0.0, 1.0, x, y)

        fun rotateDeg(deg: Double): HolidayAffine {
            val r = Math.toRadians(deg)
            return HolidayAffine(cos(r), sin(r), -sin(r), cos(r), 0.0, 0.0)
        }

        fun scale(x: Double, y: Double) = HolidayAffine(x, 0.0, 0.0, y, 0.0, 0.0)
    }
}

/**
 * The matrix an animation of [anim] in pose [frame] adds to the layer's root: `M · T · M⁻¹`, with M the layer's parent matrix and T the CSS
 * transform `translate rotate scale` about the origin (`translate(origin) · translate rotate scale · translate(-origin)`).
 */
internal fun holidayAnimMatrix(anim: HolidayLayerAnim, frame: HolidayAnimFrame): HolidayAffine {
    val ox = anim.originX.toDouble()
    val oy = anim.originY.toDouble()
    val t = HolidayAffine.translate(ox, oy) *
        HolidayAffine.translate(frame.translateX.toDouble(), frame.translateY.toDouble()) *
        HolidayAffine.rotateDeg(frame.rotateDeg.toDouble()) *
        HolidayAffine.scale(frame.scaleX.toDouble(), frame.scaleY.toDouble()) *
        HolidayAffine.translate(-ox, -oy)
    val m = HolidayAffine.of(anim.parent)
    val inverse = m.inverse() ?: return t
    return m * t * inverse
}

/** The poses of [anims] at [t] (the calm poses when [still]); a class the table does not know does not move its layer. */
private fun poses(anims: List<HolidayLayerAnim>, t: Float, still: Boolean): List<Pair<HolidayLayerAnim, HolidayAnimFrame>> = anims.mapNotNull { anim ->
    (if (still) holidayStillFrame(anim.cls) else holidayAnimFrame(anim.cls, t))?.let { anim to it }
}

private val SVG_LAYERS = ConcurrentHashMap<String, SVGDOM>()

// Skia's SVGDOM paints an `<ellipse>` that has a gradient fill or stroke in objectBoundingBox units as one flat colour (it has no bounding box
// for an ellipse; circle, rect and path are fine), which turns the soft shadows into black discs. A path of the same two arcs has one.
private val GRADIENT_ELLIPSE = Regex("""<ellipse\b([^>]*?)(/?)>""")
private val ELLIPSE_ATTRIBUTES = listOf("cx", "cy", "rx", "ry")

/** [svg] with every gradient-filled or stroked `<ellipse>` rewritten as the equivalent `<path>` (everything else untouched). */
internal fun holidayEllipsesAsPaths(svg: String): String = GRADIENT_ELLIPSE.replace(svg) { match ->
    val attributes = match.groupValues[1]
    if ("url(" !in attributes) return@replace match.value
    val values = ELLIPSE_ATTRIBUTES.associateWith { name -> Regex("""\b$name="([^"]*)"""").find(attributes)?.groupValues?.get(1)?.toDoubleOrNull() }
    val cx = values["cx"] ?: 0.0
    val cy = values["cy"] ?: 0.0
    val rx = values["rx"] ?: values["ry"] ?: return@replace match.value
    val ry = values["ry"] ?: rx
    val rest = ELLIPSE_ATTRIBUTES.fold(attributes) { acc, name -> acc.replace(Regex("""\s*\b$name="[^"]*""""), "") }
    "<path d=\"M${cx - rx} ${cy}A$rx $ry 0 1 0 ${cx + rx} ${cy}A$rx $ry 0 1 0 ${cx - rx} ${cy}Z\"$rest${match.groupValues[2]}>"
}

private fun loadLayerSvg(folder: String, name: String): SVGDOM {
    val stream = requireNotNull(HolidayTheme::class.java.getResourceAsStream("/holiday/$folder/$name.svg")) { "missing $folder/$name.svg" }
    val text = stream.use { it.readBytes() }.toString(Charsets.UTF_8)
    return SVGDOM(Data.makeFromBytes(holidayEllipsesAsPaths(text).toByteArray(Charsets.UTF_8)))
}

/** The parsed `/holiday/[folder]/[name].svg`, loaded once. */
internal fun cachedHolidaySvg(folder: String, name: String): SVGDOM = SVG_LAYERS.computeIfAbsent("$folder/$name") { loadLayerSvg(folder, name) }

/**
 * Draws [layers] bottom first, each moved by its animations at [t] seconds. With [still] the calm pose of every class is used instead
 * ([holidayStillFrame]): no motion and full opacity, bursts at their peak and rising sparks hidden. The scope's own transform applies.
 */
internal fun DrawScope.drawHolidayLayers(folder: String, layers: List<HolidayArtLayer>, t: Float, still: Boolean) {
    drawIntoCanvas { c ->
        val canvas = c.skiaCanvas
        for (layer in layers) drawLayer(canvas, folder, layer, t, still)
    }
}

private fun drawLayer(canvas: Canvas, folder: String, layer: HolidayArtLayer, t: Float, still: Boolean) {
    val posed = poses(layer.anims, t, still)
    var opacity = 1f
    posed.forEach { opacity *= it.second.opacity.coerceIn(0f, 1f) }
    if (layer is HolidayArtLayer.Text) opacity *= layer.alpha.coerceIn(0f, 1f)
    if (opacity <= 0f) return
    val saved = canvas.save()
    try {
        // A clip on an ancestor group does not move with the layer's own animation, so it goes in before them.
        (layer as? HolidayArtLayer.Text)?.clip?.let { clip ->
            canvas.clipRRect(RRect.makeLTRB(clip.left, clip.top, clip.right, clip.bottom, layer.clipRadius), true)
        }
        posed.forEach { (anim, frame) -> canvas.concat(holidayAnimMatrix(anim, frame).toMatrix33()) }
        if (opacity < 1f) {
            Paint().use { alphaPaint ->
                canvas.saveLayer(null, alphaPaint.setAlphaf(opacity))
            }
        }
        when (layer) {
            is HolidayArtLayer.Svg -> cachedHolidaySvg(folder, layer.name).let { dom -> synchronized(dom) { dom.render(canvas) } }
            is HolidayArtLayer.Text -> drawText(canvas, layer)
        }
    } finally {
        canvas.restoreToCount(saved)
    }
}

// CSS generic and system families on the machines the app runs on, in preference order; a family that is not installed is skipped.
private val MONOSPACE_FAMILIES = listOf("Menlo", "SF Mono", "Monaco", "Consolas", "Cascadia Mono", "DejaVu Sans Mono", "Liberation Mono", "Courier New")
private val SERIF_FAMILIES = listOf("Georgia", "Times New Roman", "Times", "DejaVu Serif", "Liberation Serif")
private val SANS_FAMILIES = listOf("Helvetica Neue", "Helvetica", "Segoe UI", "Arial", "Roboto", "Cantarell", "DejaVu Sans", "Liberation Sans")
private val CURSIVE_FAMILIES = listOf("Apple Chancery", "Snell Roundhand", "Brush Script MT", "Segoe Script", "Comic Sans MS", "URW Chancery L", "Z003")

/** The installed-font candidates for the CSS [families], generics and aliases expanded, in preference order. */
internal fun holidayFontCandidates(families: List<String>): List<String> = families.flatMap { family ->
    val name = family.trim().trim('"', '\'')
    when (name.lowercase()) {
        "monospace", "ui-monospace" -> MONOSPACE_FAMILIES
        "serif", "ui-serif" -> SERIF_FAMILIES
        "sans-serif", "system-ui", "-apple-system", "ui-sans-serif", "blinkmacsystemfont" -> SANS_FAMILIES
        "cursive" -> CURSIVE_FAMILIES
        else -> listOf(name)
    }
}.distinct()

private val TYPEFACES = ConcurrentHashMap<String, Typeface>()

/** The typeface for CSS [families] at [weight] (and [italic]): the first installed candidate, else the platform default. */
internal fun holidayTypeface(families: List<String>, weight: Int, italic: Boolean): Typeface =
    TYPEFACES.computeIfAbsent("${families.joinToString("|")}#$weight#$italic") {
        val style = FontStyle(weight.coerceIn(1, 1000), FontStyle.NORMAL.width, if (italic) FontSlant.ITALIC else FontSlant.UPRIGHT)
        val manager = FontMgr.default
        manager.matchFamiliesStyle(holidayFontCandidates(families).toTypedArray(), style) ?: manager.legacyMakeTypeface("", style) ?: Typeface.makeEmpty()
    }

/** Draws a text layer at its baseline with the layer's anchor, fill or gradient, stroke and matrix; the caller has applied the animations and the alpha. */
private fun drawText(canvas: Canvas, text: HolidayArtLayer.Text) {
    if (text.text.isEmpty()) return
    val saved = canvas.save()
    try {
        canvas.concat(HolidayAffine.of(text.matrix).toMatrix33())
        Font(holidayTypeface(text.families, text.weight, text.italic), text.sizePx).use { font ->
            font.isSubpixel = true
            val width = font.measureTextWidth(text.text)
            val x = when (text.anchor) {
                HolidayTextAnchor.START -> text.x
                HolidayTextAnchor.MIDDLE -> text.x - width / 2f
                HolidayTextAnchor.END -> text.x - width
            }
            val box = Rect(x, text.y + font.metrics.ascent, x + width, text.y + font.metrics.descent)
            val hasStroke = text.stroke != null && text.strokeWidth > 0f
            if (hasStroke && text.strokeFirst) strokeText(canvas, text, font, x)
            fillText(canvas, text, font, x, box)
            if (hasStroke && !text.strokeFirst) strokeText(canvas, text, font, x)
        }
    } finally {
        canvas.restoreToCount(saved)
    }
}

private fun fillText(canvas: Canvas, text: HolidayArtLayer.Text, font: Font, x: Float, box: Rect) {
    val gradient = text.gradient
    val fill = text.fill
    if (gradient == null && fill == null) return
    Paint().use { paint ->
        paint.isAntiAlias = true
        if (gradient != null) {
            paint.shader = Shader.makeLinearGradient(
                box.left + gradient.x1 * box.width, box.top + gradient.y1 * box.height,
                box.left + gradient.x2 * box.width, box.top + gradient.y2 * box.height,
                IntArray(gradient.stops.size) { gradient.stops[it].second.toArgb() },
                FloatArray(gradient.stops.size) { gradient.stops[it].first },
                GradientStyle.DEFAULT,
            )
        } else if (fill != null) {
            paint.color = fill.toArgb()
        }
        canvas.drawString(text.text, x, text.y, font, paint)
    }
}

private fun strokeText(canvas: Canvas, text: HolidayArtLayer.Text, font: Font, x: Float) {
    val stroke = text.stroke ?: return
    Paint().use { paint ->
        paint.isAntiAlias = true
        paint.mode = PaintMode.STROKE
        paint.strokeWidth = text.strokeWidth
        paint.strokeJoin = PaintStrokeJoin.ROUND
        paint.color = stroke.toArgb()
        canvas.drawString(text.text, x, text.y, font, paint)
    }
}

/**
 * A generated title-bar scene: [items] in draw order (a backdrop item stretched over the band, a part item placed right-aligned and dropped
 * whole when it does not fit), each drawing its dark or light layer through [drawHolidayLayers] so title-bar text and animations work too.
 */
internal class HolidayBandScene(private val folder: String, private val items: List<HolidayBandItem>) : HolidayScenePainter {
    override val animated: Boolean = items.any { it.dark.anims.isNotEmpty() || it.light.anims.isNotEmpty() }

    private val parts: List<HolidayBandPart> = items.mapNotNull { item ->
        val key = item.partKey
        val ref = item.partRef
        if (key != null && ref != null) HolidayBandPart(key, ref) else null
    }.distinctBy { it.key }

    override fun layout(band: Rect): HolidaySceneLayout = HolidayBandLayout(HolidayBandSpace(band), parts)

    override fun draw(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean) = drawFrame(scope, layout, dark, HOLIDAY_STILL_TIME)

    override fun drawFrame(scope: DrawScope, layout: HolidaySceneLayout, dark: Boolean, time: Float) {
        val l = layout as HolidayBandLayout
        val still = time == HOLIDAY_STILL_TIME
        for (item in items) {
            val layers = listOf(if (dark) item.dark else item.light)
            val key = item.partKey
            if (key == null) {
                l.space.drawBackdrop(scope) { drawHolidayLayers(folder, layers, time, still) }
            } else if (l.has(key)) {
                l.space.drawElements(scope) { drawHolidayLayers(folder, layers, time, still) }
            }
        }
    }
}
