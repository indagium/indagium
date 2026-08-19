package com.indagium.diagram3

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

// ── Seq3Layout -> BufferedImage/PNG ─────────────────────────────────────────────────────────
//
// Headless Graphics2D rasterizer. Mandatory, not optional (this phase's brief): notes embed
// `diagram-NN.png`, `utils/AnnotationHtml.kt` inlines a data-URI, and the rich-clipboard path needs
// real bytes — none of that can wait for phase 4's Compose canvas to exist.
//
// The one rule that keeps this file from ever drifting off what the user saw on screen: it NEVER
// recomputes a position or re-wraps a label. Every number it draws comes straight off the
// [Seq3Layout] Phase 4's Compose canvas will also consume — see Seq3Layout.kt's own header for why
// that file, not this one, owns the geometry. To reconcile "one shared 1x-unit layout" with
// "this needs to render at an arbitrary DPI/zoom", this file scales the Graphics2D TRANSFORM
// (`g.scale(scale, scale)`) rather than multiplying coordinates by hand: painting stays written
// entirely in 1x logical units and AWT stretches strokes, fills, AND text together, exactly like
// `diagram/SeqDiagramRenderer.kt`'s own `Metrics(scale)` scaled every constant, just without this
// file needing a second measurement pass at the target scale — [Seq3TextMetricsAwt] below measures
// once, at [Seq3FontRole.basePointSize], and the transform does the rest.
//
// Modeled on `diagram/SeqDiagramRenderer.kt`'s paint pass (arrowheads, dashed strokes, note fill) —
// see that file's `drawArrowhead`/`paintSelfMessage`/`paintTargetlessMessage` for the shapes this
// mirrors — but driving every position from [Seq3Layout] instead of recomputing it.

/** Every color as a plain ARGB [Int] (`0xAARRGGBB`) so this package stays Compose-free — phase 3
 *  maps the app's live `ui.Theme.ThemeColors` onto one of these at the call site, the same
 *  boundary `diagram.DiagramTheme` drew for the old renderer (see that file's own doc). */
data class Seq3RasterTheme(
    val background: Int,
    val lifeline: Int,
    val headerFill: Int,
    val headerBorder: Int,
    val headerText: Int,
    val arrow: Int,
    val label: Int,
    val badgeBg: Int,
    val badgeText: Int,
    val fragmentBorder: Int,
    val fragmentWash: Int,
    val noteFill: Int,
    val noteBorder: Int,
    val noteText: Int,
    /** The design spec's "needs target" amber (`warn`/`warnBg` in `ui.Theme.ThemeColors`, added by
     *  this rewrite's phase 3) — used for the unresolved dashed stub and its drop pill. */
    val warn: Int,
    val warnBg: Int,
) {
    companion object {
        /** Loosely mirrors `diagram.DiagramTheme.LIGHT`/`.DARK`'s own hand-copied constants — a
         *  real call site always supplies its own theme built from the live app theme; this exists
         *  so a test/tool can render without one. */
        val DEFAULT_LIGHT = Seq3RasterTheme(
            background = 0xFFF6F8FA.toInt(),
            lifeline = 0xFFD0D7DE.toInt(),
            headerFill = 0xFFFFFFFF.toInt(),
            headerBorder = 0xFFD0D7DE.toInt(),
            headerText = 0xFF1F2328.toInt(),
            arrow = 0xFF636C76.toInt(),
            label = 0xFF1F2328.toInt(),
            badgeBg = 0xFFE8ECF0.toInt(),
            badgeText = 0xFF57606A.toInt(),
            fragmentBorder = 0xFF0969DA.toInt(),
            fragmentWash = 0x120969DA,
            noteFill = 0x33BC4C00,
            noteBorder = 0xFFBC4C00.toInt(),
            noteText = 0xFF1F2328.toInt(),
            warn = 0xFFB07216.toInt(),
            warnBg = 0xFFFAEDD9.toInt(),
        )
    }
}

data class RenderedSeq3(val image: BufferedImage, val widthPx: Int, val heightPx: Int, val scale: Float)

// Guards against a pathological layout allocating a gigapixel raster — mirrors
// `diagram.SeqDiagramRenderer`'s own MAX_IMAGE_DIM_PX.
private const val MAX_IMAGE_DIM_PX = 8000
private const val MIN_RENDER_SCALE = 0.05f
private const val PLACEHOLDER_W = 360
private const val PLACEHOLDER_H = 100

/**
 * Renders [layout] into a raster. Never throws and never returns a zero-sized image: an empty
 * layout (see [layoutSeq3]'s own doc for when that happens) renders a small explanatory placeholder
 * instead of an empty canvas, matching `diagram.SeqDiagramRenderer.renderSequenceDiagram`'s posture.
 */
fun renderSeq3(layout: Seq3Layout, theme: Seq3RasterTheme, scale: Float = 2f): RenderedSeq3 {
    val safeScale = if (scale.isFinite() && scale > 0f) scale else 1f
    if (layout.lifelines.isEmpty()) return renderSeq3Placeholder(theme, safeScale)

    var effectiveScale = safeScale
    var w = (layout.width * effectiveScale).roundToInt().coerceAtLeast(1)
    var h = (layout.height * effectiveScale).roundToInt().coerceAtLeast(1)
    if (w > MAX_IMAGE_DIM_PX || h > MAX_IMAGE_DIM_PX) {
        val shrink = minOf(MAX_IMAGE_DIM_PX.toFloat() / w, MAX_IMAGE_DIM_PX.toFloat() / h)
        effectiveScale = (effectiveScale * shrink).coerceAtLeast(MIN_RENDER_SCALE)
        w = (layout.width * effectiveScale).roundToInt().coerceIn(1, MAX_IMAGE_DIM_PX)
        h = (layout.height * effectiveScale).roundToInt().coerceIn(1, MAX_IMAGE_DIM_PX)
    }

    val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
        applyRenderingHints(g)
        g.scale(effectiveScale.toDouble(), effectiveScale.toDouble())
        paintSeq3(g, layout, theme)
    } finally {
        g.dispose()
    }
    return RenderedSeq3(image, w, h, effectiveScale)
}

private fun renderSeq3Placeholder(theme: Seq3RasterTheme, scale: Float): RenderedSeq3 {
    val w = (PLACEHOLDER_W * scale).roundToInt().coerceAtLeast(1)
    val h = (PLACEHOLDER_H * scale).roundToInt().coerceAtLeast(1)
    val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
        applyRenderingHints(g)
        g.color = Color(theme.background, true)
        g.fillRect(0, 0, w, h)
        g.color = Color(theme.label, true)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, (12 * scale).roundToInt().coerceAtLeast(1))
        val fm = g.fontMetrics
        val text = "No lifelines to diagram"
        val tw = fm.stringWidth(text)
        g.drawString(text, ((w - tw) / 2).coerceAtLeast(0), h / 2 + fm.ascent / 2)
    } finally {
        g.dispose()
    }
    return RenderedSeq3(image, w, h, scale)
}

private fun applyRenderingHints(g: Graphics2D) {
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
}

/** `ImageIO.write` to an in-memory PNG — same idiom as `diagram.RenderedDiagram.toPngBytes`,
 *  `video/VideoPlayerController.kt`'s `encodePng`, and `ui/VideoPanel.kt`'s rotated-frame export. */
fun RenderedSeq3.toPngBytes(): ByteArray {
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    return out.toByteArray()
}

// ── Fonts, by role, at the UNSCALED base point size — see this file's header ───────────────────

private fun fontFor(role: Seq3FontRole): Font {
    val style = if (role == Seq3FontRole.LIFELINE) Font.PLAIN else Font.PLAIN
    return Font(Font.SANS_SERIF, style, role.basePointSize.roundToInt().coerceAtLeast(1))
}

private const val STROKE_THIN = 1f
private const val STROKE_THICK = 1.6f
private val DASH_WARN = floatArrayOf(4f, 3f)
private val DASH_LIFELINE = floatArrayOf(4f, 4f)
private const val ARROWHEAD_LEN = 9.0
private const val ARROWHEAD_W = 7.0
private const val BADGE_ARC = 8f
private const val PILL_ARC = 10f
private const val NOTE_ARC = 6f
private const val HEADER_ARC = 8f

// Actor glyph (WP2 item 2): a stick figure drawn ABOVE the name box, occupying the vertical band
// [col.header.y - ACTOR_GLYPH_H - ACTOR_GLYPH_GAP, col.header.y - ACTOR_GLYPH_GAP] — which fits
// inside Seq3Layout's own ACTOR_HEADER_RESERVE reservation (34.0) with room to spare, since this
// file draws the glyph, not the space for it (see Seq3LifelineColumn's own doc on why layout only
// reserves the band and each renderer decides what to paint inside it).
private const val ACTOR_GLYPH_W = 16.0
private const val ACTOR_GLYPH_H = 26.0
private const val ACTOR_GLYPH_GAP = 4.0

private fun paintSeq3(g: Graphics2D, layout: Seq3Layout, theme: Seq3RasterTheme) {
    g.color = Color(theme.background, true)
    g.fillRect(0, 0, layout.width.roundToInt(), layout.height.roundToInt())

    layout.fragments.forEach { paintFragment(g, it, theme) }
    paintLifelines(g, layout, theme)
    layout.lifelines.forEach { paintHeader(g, it, theme) }
    layout.rows.forEach { paintRow(g, it, theme) }
    layout.notes.forEach { paintNoteBox(g, it, theme) }
    layout.delays.forEach { paintDelayBox(g, it, theme) }
}

private fun paintLifelines(g: Graphics2D, layout: Seq3Layout, theme: Seq3RasterTheme) {
    g.color = Color(theme.lifeline, true)
    g.stroke = BasicStroke(STROKE_THIN, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, DASH_LIFELINE, 0f)
    layout.lifelines.forEach { l ->
        g.draw(java.awt.geom.Line2D.Double(l.centerX, l.lifelineTop, l.centerX, l.lifelineBottom))
    }
}

private fun paintHeader(g: Graphics2D, col: Seq3LifelineColumn, theme: Seq3RasterTheme) {
    if (col.kind == Seq3LifelineKind.ACTOR) {
        paintActorGlyph(g, col, theme)
        paintHeaderLabelLines(g, col, theme, box = col.header)
        return
    }
    val box = col.header
    g.color = Color(theme.headerFill, true)
    g.fillRoundRect(box.x.roundToInt(), box.y.roundToInt(), box.width.roundToInt(), box.height.roundToInt(), HEADER_ARC.toInt(), HEADER_ARC.toInt())
    g.color = Color(theme.headerBorder, true)
    g.stroke = BasicStroke(STROKE_THIN)
    g.drawRoundRect(box.x.roundToInt(), box.y.roundToInt(), box.width.roundToInt(), box.height.roundToInt(), HEADER_ARC.toInt(), HEADER_ARC.toInt())
    paintHeaderLabelLines(g, col, theme, box)
}

/** Item 2: draws every entry of [Seq3LifelineColumn.labelLines], vertically centred as a BLOCK
 *  inside [box] (single-line input reduces to the same centred baseline the old one-line version
 *  used). Shared by both the participant chip and the actor glyph paths above so the two never
 *  drift apart in how they lay out multi-line text. */
private fun paintHeaderLabelLines(g: Graphics2D, col: Seq3LifelineColumn, theme: Seq3RasterTheme, box: Seq3Box) {
    g.color = Color(theme.headerText, true)
    g.font = fontFor(Seq3FontRole.LIFELINE)
    val fm = g.fontMetrics
    val lines = col.labelLines.ifEmpty { listOf(col.label) }
    val totalTextH = lines.size * fm.height
    var ty = box.y + (box.height - totalTextH) / 2 + fm.ascent
    lines.forEach { line ->
        val tw = fm.stringWidth(line)
        g.drawString(line, (col.centerX - tw / 2).roundToInt(), ty.roundToInt())
        ty += fm.height
    }
}

/** Item 2 (actor glyph): a plain stick figure — circle head, line body/arms/legs — drawn in the
 *  band Seq3Layout reserved above [Seq3LifelineColumn.header] for every column the moment ANY
 *  lifeline in the document is [Seq3LifelineKind.ACTOR] (see ACTOR_HEADER_RESERVE's own doc). No
 *  fill/border box behind it — replaces the rounded participant chip entirely, matching this task's
 *  brief ("draw a stick figure above the name INSTEAD OF the rounded chip"). */
private fun paintActorGlyph(g: Graphics2D, col: Seq3LifelineColumn, theme: Seq3RasterTheme) {
    val cx = col.centerX
    val glyphBottom = col.header.y - ACTOR_GLYPH_GAP
    val glyphTop = glyphBottom - ACTOR_GLYPH_H
    val headR = ACTOR_GLYPH_W / 4
    val headCenterY = glyphTop + headR
    val bodyTop = headCenterY + headR
    val legSplit = glyphBottom - ACTOR_GLYPH_H * 0.28
    g.color = Color(theme.headerBorder, true)
    g.stroke = BasicStroke(STROKE_THIN, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    g.draw(java.awt.geom.Ellipse2D.Double(cx - headR, headCenterY - headR, headR * 2, headR * 2))
    g.draw(java.awt.geom.Line2D.Double(cx, bodyTop, cx, legSplit))
    val armY = bodyTop + (legSplit - bodyTop) * 0.35
    g.draw(java.awt.geom.Line2D.Double(cx - ACTOR_GLYPH_W / 2, armY, cx + ACTOR_GLYPH_W / 2, armY))
    g.draw(java.awt.geom.Line2D.Double(cx, legSplit, cx - ACTOR_GLYPH_W / 2, glyphBottom))
    g.draw(java.awt.geom.Line2D.Double(cx, legSplit, cx + ACTOR_GLYPH_W / 2, glyphBottom))
}

private fun paintRow(g: Graphics2D, row: Seq3RowGeometry, theme: Seq3RasterTheme) {
    when (row) {
        is Seq3ArrowRow -> paintArrowRow(g, row, theme)
        is Seq3SelfLoopRow -> paintSelfLoopRow(g, row, theme)
        is Seq3UnresolvedStubRow -> paintStubRow(g, row, theme)
        is Seq3MessageNoteRow -> paintMessageNoteRow(g, row, theme)
        is Seq3ElisionRow -> paintElisionRow(g, row, theme)
    }
}

// Consumes the shared [seq3ArrowStyle] descriptor for width/dash/solid-vs-dashed instead of its
// own former parallel `when` over every field — see Seq3ArrowStyle.kt's header for why this used
// to duplicate `strokeFor`'s own DASH_RETURN/DASH_ASYNC constants inline. The `when` that remains
// here is deliberately narrower than before: it picks ONLY the AWT cap/join style (CAP_BUTT vs
// CAP_ROUND), a rendering nuance [Seq3ArrowStyle] doesn't carry because Compose's `DrawScope` has
// no shared vocabulary for it — see that file's own doc for why that's not an oversight.
private fun strokeFor(kind: Seq3Kind): BasicStroke {
    val style = seq3ArrowStyle(kind)
    val width = if (style.thin) STROKE_THIN else STROKE_THICK
    val dash = style.dash?.toFloatArray()
    return when (kind) {
        Seq3Kind.RETURN -> BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, 0f)
        Seq3Kind.ASYNC -> BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, dash, 0f)
        else -> BasicStroke(width)
    }
}

private fun drawArrowhead(g: Graphics2D, tipX: Double, tipY: Double, pointingRight: Boolean, filled: Boolean, color: Int) {
    val dx = if (pointingRight) -ARROWHEAD_LEN else ARROWHEAD_LEN
    val backX = tipX + dx
    g.color = Color(color, true)
    if (filled) {
        g.fill(
            Polygon(
                intArrayOf(tipX.roundToInt(), backX.roundToInt(), backX.roundToInt()),
                intArrayOf(tipY.roundToInt(), (tipY - ARROWHEAD_W).roundToInt(), (tipY + ARROWHEAD_W).roundToInt()),
                3,
            ),
        )
    } else {
        g.stroke = BasicStroke(STROKE_THICK, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(java.awt.geom.Line2D.Double(tipX, tipY, backX, tipY - ARROWHEAD_W))
        g.draw(java.awt.geom.Line2D.Double(tipX, tipY, backX, tipY + ARROWHEAD_W))
    }
}

private fun paintArrowRow(g: Graphics2D, row: Seq3ArrowRow, theme: Seq3RasterTheme) {
    g.color = Color(theme.arrow, true)
    g.stroke = strokeFor(row.kind)
    g.draw(java.awt.geom.Line2D.Double(row.fromX, row.y, row.toX, row.y))
    val pointingRight = row.toX > row.fromX
    drawArrowhead(g, row.toX, row.y, pointingRight, filled = seq3ArrowStyle(row.kind).filledHead, theme.arrow)
    paintLabel(g, row.label, row.labelBox, theme.label, centered = true)
    row.badgeBox?.let { paintBadge(g, "×${row.repeatCount}", it, theme) }
}

private fun paintSelfLoopRow(g: Graphics2D, row: Seq3SelfLoopRow, theme: Seq3RasterTheme) {
    val xOut = row.x + row.loopWidth
    g.color = Color(theme.arrow, true)
    g.stroke = BasicStroke(STROKE_THICK, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    g.draw(java.awt.geom.Line2D.Double(row.x, row.y, xOut, row.y))
    g.draw(java.awt.geom.Line2D.Double(xOut, row.y, xOut, row.loopBottomY))
    g.draw(java.awt.geom.Line2D.Double(xOut, row.loopBottomY, row.x, row.loopBottomY))
    drawArrowhead(g, row.x, row.loopBottomY, pointingRight = false, filled = true, theme.arrow)
    paintLabel(g, row.label, row.labelBox, theme.label, centered = false)
    row.badgeBox?.let { paintBadge(g, "×${row.repeatCount}", it, theme) }
}

private fun paintStubRow(g: Graphics2D, row: Seq3UnresolvedStubRow, theme: Seq3RasterTheme) {
    g.color = Color(theme.warn, true)
    g.stroke = BasicStroke(STROKE_THIN, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, DASH_WARN, 0f)
    g.draw(java.awt.geom.Line2D.Double(row.fromX, row.y, row.stubEndX, row.y))
    val r = ARROWHEAD_W
    g.draw(java.awt.geom.Ellipse2D.Double(row.stubEndX - r, row.y - r, r * 2, r * 2))
    val pill = row.dropPill
    g.color = Color(theme.warnBg, true)
    g.fillRoundRect(pill.x.roundToInt(), pill.y.roundToInt(), pill.width.roundToInt(), pill.height.roundToInt(), PILL_ARC.toInt(), PILL_ARC.toInt())
    g.color = Color(theme.warn, true)
    g.stroke = BasicStroke(STROKE_THIN)
    g.drawRoundRect(pill.x.roundToInt(), pill.y.roundToInt(), pill.width.roundToInt(), pill.height.roundToInt(), PILL_ARC.toInt(), PILL_ARC.toInt())
    g.font = fontFor(Seq3FontRole.STUB)
    val fm = g.fontMetrics
    g.drawString(row.label, (pill.x + PILL_ARC / 2).roundToInt(), (pill.y + pill.height / 2 + fm.ascent / 2).roundToInt())
}

private fun paintMessageNoteRow(g: Graphics2D, row: Seq3MessageNoteRow, theme: Seq3RasterTheme) {
    val box = row.box
    g.color = Color(theme.noteFill, true)
    g.fillRoundRect(box.x.roundToInt(), box.y.roundToInt(), box.width.roundToInt(), box.height.roundToInt(), NOTE_ARC.toInt(), NOTE_ARC.toInt())
    g.color = Color(theme.noteBorder, true)
    g.stroke = BasicStroke(STROKE_THIN)
    g.drawRoundRect(box.x.roundToInt(), box.y.roundToInt(), box.width.roundToInt(), box.height.roundToInt(), NOTE_ARC.toInt(), NOTE_ARC.toInt())
    g.font = fontFor(Seq3FontRole.NOTE)
    g.color = Color(theme.noteText, true)
    val fm = g.fontMetrics
    var ty = box.y + fm.ascent + NOTE_ARC / 2
    row.lines.forEach { line ->
        g.drawString(line, (box.x + NOTE_ARC / 2).roundToInt(), ty.roundToInt())
        ty += fm.height
    }
}

private fun paintElisionRow(g: Graphics2D, row: Seq3ElisionRow, theme: Seq3RasterTheme) {
    g.font = fontFor(Seq3FontRole.BADGE)
    g.color = Color(theme.badgeText, true)
    val text = "⋯ ×${row.elidedCount} elided"
    val fm = g.fontMetrics
    val tw = fm.stringWidth(text)
    val cx = row.box.x + row.box.width / 2
    g.drawString(text, (cx - tw / 2).roundToInt(), (row.box.y + row.box.height / 2 + fm.ascent / 2).roundToInt())
}

private fun paintLabel(g: Graphics2D, text: String, box: Seq3Box, color: Int, centered: Boolean) {
    if (text.isEmpty()) return
    g.font = fontFor(Seq3FontRole.LABEL)
    g.color = Color(color, true)
    val fm = g.fontMetrics
    val x = if (centered) box.x + (box.width - fm.stringWidth(text)) / 2 else box.x
    g.drawString(text, x.roundToInt(), (box.y + box.height - fm.descent).roundToInt())
}

private fun paintBadge(g: Graphics2D, text: String, box: Seq3Box, theme: Seq3RasterTheme) {
    g.color = Color(theme.badgeBg, true)
    g.fillRoundRect(box.x.roundToInt(), box.y.roundToInt(), box.width.roundToInt(), box.height.roundToInt(), BADGE_ARC.toInt(), BADGE_ARC.toInt())
    g.font = fontFor(Seq3FontRole.BADGE)
    g.color = Color(theme.badgeText, true)
    val fm = g.fontMetrics
    val tx = box.x + (box.width - fm.stringWidth(text)) / 2
    g.drawString(text, tx.roundToInt(), (box.y + box.height / 2 + fm.ascent / 2).roundToInt())
}

private fun paintFragment(g: Graphics2D, fragment: Seq3FragmentBox, theme: Seq3RasterTheme) {
    val box = fragment.box
    val arc = HEADER_ARC.toInt()
    g.color = Color(theme.fragmentWash, true)
    g.fillRoundRect(box.x.roundToInt(), box.y.roundToInt(), box.width.roundToInt(), box.height.roundToInt(), arc, arc)
    g.color = Color(theme.fragmentBorder, true)
    g.stroke = BasicStroke(STROKE_THIN)
    g.drawRoundRect(box.x.roundToInt(), box.y.roundToInt(), box.width.roundToInt(), box.height.roundToInt(), arc, arc)
    g.font = fontFor(Seq3FontRole.FRAGMENT)
    val fm = g.fontMetrics
    g.drawString(fragment.label, (box.x + 6).roundToInt(), (box.y + fm.ascent + 2).roundToInt())
}

private fun paintNoteBox(g: Graphics2D, note: Seq3NoteBox, theme: Seq3RasterTheme) {
    val box = note.box
    g.color = Color(theme.noteFill, true)
    g.fillRoundRect(box.x.roundToInt(), box.y.roundToInt(), box.width.roundToInt(), box.height.roundToInt(), NOTE_ARC.toInt(), NOTE_ARC.toInt())
    g.color = Color(theme.noteBorder, true)
    g.stroke = BasicStroke(STROKE_THIN)
    g.drawRoundRect(box.x.roundToInt(), box.y.roundToInt(), box.width.roundToInt(), box.height.roundToInt(), NOTE_ARC.toInt(), NOTE_ARC.toInt())
    g.font = fontFor(Seq3FontRole.NOTE)
    g.color = Color(theme.noteText, true)
    val fm = g.fontMetrics
    g.drawString(note.text, (box.x + NOTE_ARC / 2).roundToInt(), (box.y + fm.ascent + NOTE_ARC / 2).roundToInt())
}

// WP11: a labelled vertical gap spanning the full diagram width — a horizontal dashed rule with
// its label centered on top, deliberately NOT a filled box like paintFragment/paintNoteBox (a
// time-gap marker is a break in the timeline, not a region grouping messages, so it reads as a
// divider rather than a container). Reuses `arrow`/`label` theme roles rather than adding new
// Seq3RasterTheme fields purely for this one marker — see this file's own header on why every
// number/colour here comes from [Seq3Layout]/[theme] rather than being invented locally.
private val DASH_DELAY = floatArrayOf(6f, 4f)

private fun paintDelayBox(g: Graphics2D, delay: Seq3DelayBox, theme: Seq3RasterTheme) {
    val box = delay.box
    val midY = (box.y + box.height / 2).roundToInt()
    g.color = Color(theme.arrow, true)
    g.stroke = BasicStroke(STROKE_THIN, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, DASH_DELAY, 0f)
    g.drawLine(box.x.roundToInt(), midY, (box.x + box.width).roundToInt(), midY)
    g.font = fontFor(Seq3FontRole.NOTE)
    g.color = Color(theme.label, true)
    val fm = g.fontMetrics
    val text = delay.label
    val tx = box.x + (box.width - fm.stringWidth(text)) / 2
    g.drawString(text, tx.roundToInt(), (box.y + fm.ascent).roundToInt())
}

// ── Real AWT-backed Seq3TextMetrics — the source of truth layoutSeq3 measures against ─────────

/**
 * Measures text with real `java.awt.FontMetrics` at each role's [Seq3FontRole.basePointSize] —
 * the implementation a live caller (export path, MCP handler, phase 4's canvas measurement) uses.
 * A fresh 1x1 scratch image's `Graphics2D` is exactly `diagram.SeqDiagramRenderer`'s own
 * `scratchGraphics()` idiom, kept private there and duplicated here rather than shared cross-package
 * for the same reason Seq3Emitters.kt duplicates `diagram.DiagramEmitters`' escaping instead of
 * importing `diagram` (this rewrite's whole point is `diagram3` never depending on `diagram`).
 */
class Seq3AwtTextMetrics : Seq3TextMetrics {
    private val scratch: Graphics2D = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().also { applyRenderingHints(it) }
    private val metricsByRole = Seq3FontRole.entries.associateWith { role -> scratch.getFontMetrics(fontFor(role)) }

    override fun width(role: Seq3FontRole, text: String): Double = metricsByRole.getValue(role).stringWidth(text).toDouble()

    override fun lineHeight(role: Seq3FontRole): Double = metricsByRole.getValue(role).height.toDouble()
}
