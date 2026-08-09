package com.indagium.diagram

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ── Sequence-diagram renderer ─────────────────────────────────────────────────────────────────
//
// One drawing routine, two sinks: the SAME BufferedImage this file produces is what Phase 3 shows
// on screen (converted via org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap(),
// the path already used at ui/AnnotationPanel.kt:1429) and what gets written to PNG for export —
// there is no separate Compose DrawScope path, so the two views can never drift apart. This file is
// therefore plain java.awt.Graphics2D on a raster, not a Compose composable, and carries no
// androidx.compose import at all — matching the rest of this UI-free `diagram` package.
//
// Two-pass structure, kept as two clearly separate phases (measure() then paint()) rather than
// interleaved: measuring needs a live FontMetrics, which needs a live Graphics2D, so it's tempting
// to fold measurement into the paint pass itself. Keeping them apart is what lets
// DiagramRendererTest assert on layout numbers (column x-positions, row y-positions, canvas size)
// without decoding a single pixel — pixel-content assertions would be brittle across JDK/font-
// rendering differences between CI and a dev machine, exactly the trap the task spec calls out.
// Both phases share one [Metrics] bundle (every layout constant already multiplied by [scale]) so
// a distance computed while measuring and the same distance used while painting can never drift.

/** Plain java.awt.Color so this package stays Compose-free — Phase 3 builds one of these from the
 *  app's active ui.Theme.ThemeColors at the call site. The LIGHT/DARK defaults below loosely mirror
 *  ui/Theme.kt's LIGHT_THEME / DARK_GITHUB presets (bg/p/br/tx/ac) and DANGER_RED, hand-copied as
 *  plain RGB rather than imported — this package cannot depend on `ui` (which is Compose-typed) —
 *  so if those presets move this drifts cosmetically, never a correctness bug: every real call site
 *  supplies its own DiagramTheme built from the live theme anyway. */
data class DiagramTheme(
    val background: Color,
    val lifeline: Color,
    val participantFill: Color,
    val participantBorder: Color,
    val participantText: Color,
    val arrow: Color,
    val label: Color,
    val frame: Color,
    val note: Color,
    val errorAccent: Color,
) {
    companion object {
        val DARK = DiagramTheme(
            background = Color(0x0d, 0x11, 0x17),
            lifeline = Color(0x3b, 0x46, 0x52),
            participantFill = Color(0x16, 0x1b, 0x22),
            participantBorder = Color(0x3b, 0x46, 0x52),
            participantText = Color(0xc9, 0xd1, 0xd9),
            arrow = Color(0x8b, 0x94, 0x9e),
            label = Color(0xc9, 0xd1, 0xd9),
            frame = Color(0x38, 0x8b, 0xfd),
            note = Color(0xf0, 0x88, 0x3e),
            errorAccent = Color(0xf8, 0x51, 0x49),
        )
        val LIGHT = DiagramTheme(
            background = Color(0xf6, 0xf8, 0xfa),
            lifeline = Color(0xd0, 0xd7, 0xde),
            participantFill = Color(0xff, 0xff, 0xff),
            participantBorder = Color(0xd0, 0xd7, 0xde),
            participantText = Color(0x1f, 0x23, 0x28),
            arrow = Color(0x63, 0x6c, 0x76),
            label = Color(0x1f, 0x23, 0x28),
            frame = Color(0x09, 0x69, 0xda),
            note = Color(0xbc, 0x4c, 0x00),
            errorAccent = Color(0xcf, 0x22, 0x2e),
        )
    }
}

/** Where message [messageIndex] was drawn, in IMAGE pixel coordinates (already multiplied by the
 *  render scale) — [x]/[y] is the hit-box's top-left corner, the same convention java.awt.Rectangle
 *  uses. Phase 3 hit-tests a click (converted to image pixels the same way it already loaded the
 *  bitmap) against these to jump to [entryId]. */
data class ArrowHit(val messageIndex: Int, val entryId: Int, val x: Int, val y: Int, val width: Int, val height: Int)

data class RenderedDiagram(
    val image: BufferedImage,
    val hits: List<ArrowHit>,
    val widthPx: Int,
    val heightPx: Int,
    val scale: Float,
)

// ── Layout constants ──────────────────────────────────────────────────────────────────────────
// All of these are UNSCALED "1x" pixel values. measure() multiplies every one of them by the
// effective render scale exactly once, into a [Metrics] bundle, so painting never has to re-derive
// a size — and so a shrink applied for the MAX_IMAGE_DIM_PX clamp (see renderSequenceDiagram)
// automatically scales every constant down together, not just the raster.

private const val BASE_MARGIN = 24f
private const val BASE_TITLE_FONT = 16f
private const val BASE_TITLE_GAP = 12f // space below the title before participant headers
private const val BASE_HEADER_TO_ROWS_GAP = 22f // space below the shortest/tallest header before row 0
private const val BASE_BOTTOM_MARGIN = 20f

private const val BASE_PARTICIPANT_FONT = 13f
private const val BASE_PARTICIPANT_BOX_MIN_W = 90f
private const val BASE_PARTICIPANT_BOX_MAX_W = 200f
private const val BASE_PARTICIPANT_LINE_H = 15f
private const val BASE_PARTICIPANT_PAD_H = 12f
private const val BASE_PARTICIPANT_PAD_V = 6f
private const val BASE_PARTICIPANT_ARC = 10f

// ACTOR renders as a classic UML stick figure (head + body + arms + legs) above its label, in place
// of the rounded box a TAG participant gets — the two are meant to read as different KINDS of thing
// at a glance (a tag is part of the app; an actor is external to it), and a stick figure is the one
// shape nobody mistakes for "just another box".
private const val BASE_ACTOR_HEAD_R = 6f
private const val BASE_ACTOR_BODY_H = 14f
private const val BASE_ACTOR_LIMB_SPAN = 10f
private const val BASE_ACTOR_LABEL_GAP = 4f

private const val BASE_COLUMN_GAP = 70f // clear space between adjacent lifeline columns

private const val BASE_ROW_H = 42f // vertical pitch of one non-SELF message row
private const val BASE_SELF_EXTRA = 26f // extra pitch a SELF row's loop needs, reserved AFTER its arrow y
private const val BASE_SELF_LOOP_W = 46f // how far a SELF loop bows out from its lifeline

private const val BASE_ARROWHEAD_LEN = 9f
private const val BASE_ARROWHEAD_W = 7f
private const val BASE_LABEL_FONT = 12f
private const val BASE_LABEL_GAP_ABOVE_LINE = 4f
private const val BASE_HIT_INSET = 4f // shrinks a hit box vs. its row's own pitch so neighbors never touch

private const val BASE_FRAME_LABEL_FONT = 11f
private const val BASE_FRAME_INSET_PER_DEPTH = 10f
private const val BASE_FRAME_PAD_V = 10f
private const val BASE_FRAME_LABEL_H = 16f
private const val BASE_FRAME_ARC = 10f
private const val BASE_FRAME_WASH_ALPHA = 18
private const val BASE_FRAME_BORDER_ALPHA = 150

private const val BASE_NOTE_FONT = 11f
private const val BASE_NOTE_W = 130f
private const val BASE_NOTE_PAD = 6f
private const val BASE_NOTE_LINE_H = 13f
private const val BASE_NOTE_MAX_LINES = 3
private const val BASE_NOTE_ARC = 8f
private const val BASE_NOTE_GAP_FROM_LIFELINE = 10f

private const val BASE_FOOTER_FONT = 11f
private const val BASE_FOOTER_GAP_ABOVE = 14f
private const val BASE_FOOTER_H = 30f

private const val BASE_PLACEHOLDER_W = 360f
private const val BASE_PLACEHOLDER_H = 100f
private const val BASE_PLACEHOLDER_FONT = 13f

/** Hard ceiling on either raster dimension, in FINAL image pixels — guards against a pathological
 *  input (a caller-supplied spec.participants list is never capped the way auto-derived TAG
 *  participants are, see SeqDiagramBuilder's resolveTagParticipants) trying to allocate a
 *  gigapixel BufferedImage. renderSequenceDiagram shrinks the effective scale, re-measures, and
 *  reports the SHRUNKEN scale back in RenderedDiagram.scale — every constant (fonts included)
 *  scales down together, so a clamped diagram is smaller and crisper, never just cropped. */
private const val MAX_IMAGE_DIM_PX = 8000

private const val ELLIPSIS = "…"

// ── Scaled constants bundle ───────────────────────────────────────────────────────────────────

// `scale` must be a property, not a plain constructor parameter: a constructor parameter is in
// scope for property initializers but NOT for member function bodies, and s()/sf() below are
// member functions called from those initializers.
private class Metrics(val scale: Float) {
    private fun s(v: Float): Int = (v * scale).roundToInt()
    private fun sf(v: Float): Float = max(1f, v * scale) // stroke widths/dash lengths must stay > 0

    val margin = s(BASE_MARGIN)
    val titleGap = s(BASE_TITLE_GAP)
    val headerToRowsGap = s(BASE_HEADER_TO_ROWS_GAP)
    val bottomMargin = s(BASE_BOTTOM_MARGIN)

    val participantBoxMinW = s(BASE_PARTICIPANT_BOX_MIN_W)
    val participantBoxMaxW = s(BASE_PARTICIPANT_BOX_MAX_W)
    val participantLineH = s(BASE_PARTICIPANT_LINE_H)
    val participantPadH = s(BASE_PARTICIPANT_PAD_H)
    val participantPadV = s(BASE_PARTICIPANT_PAD_V)
    val participantArc = s(BASE_PARTICIPANT_ARC)

    val actorHeadR = s(BASE_ACTOR_HEAD_R)
    val actorBodyH = s(BASE_ACTOR_BODY_H)
    val actorLimbSpan = s(BASE_ACTOR_LIMB_SPAN)
    val actorLabelGap = s(BASE_ACTOR_LABEL_GAP)
    val actorFigureW = s(BASE_ACTOR_LIMB_SPAN) * 2
    val actorFigureH = s(BASE_ACTOR_HEAD_R) * 2 + s(BASE_ACTOR_BODY_H)

    val columnGap = s(BASE_COLUMN_GAP)

    val rowH = s(BASE_ROW_H)
    val selfExtra = s(BASE_SELF_EXTRA)
    val selfLoopW = s(BASE_SELF_LOOP_W)
    val hitInset = s(BASE_HIT_INSET)

    // Reserve headroom on both ends of the loop (its top sits at the row's own arrowY, same as any
    // other row) so the SELF hit box (loopH + hitInset tall) never reaches into the NEXT row's own
    // reserved space, which starts exactly selfExtra below this row's arrowY.
    val selfLoopH = (selfExtra - 2 * hitInset).coerceAtLeast(s(10f))

    val arrowheadLen = s(BASE_ARROWHEAD_LEN)
    val arrowheadW = s(BASE_ARROWHEAD_W)
    val labelGapAboveLine = s(BASE_LABEL_GAP_ABOVE_LINE)

    val frameInsetPerDepth = s(BASE_FRAME_INSET_PER_DEPTH)
    val framePadV = s(BASE_FRAME_PAD_V)
    val frameLabelH = s(BASE_FRAME_LABEL_H)
    val frameArc = s(BASE_FRAME_ARC)

    val noteW = s(BASE_NOTE_W)
    val notePad = s(BASE_NOTE_PAD)
    val noteLineH = s(BASE_NOTE_LINE_H)
    val noteArc = s(BASE_NOTE_ARC)
    val noteGapFromLifeline = s(BASE_NOTE_GAP_FROM_LIFELINE)

    val footerGapAbove = s(BASE_FOOTER_GAP_ABOVE)
    val footerH = s(BASE_FOOTER_H)

    val strokeThin = sf(1f)
    val strokeThick = sf(1.6f)
    val dashLifeline = floatArrayOf(sf(4f), sf(4f))
    val dashReturn = floatArrayOf(sf(6f), sf(4f))
}

// ── Measured layout (pure data — no Graphics2D held past measure()) ──────────────────────────

private class ParticipantLayout(
    val centerX: Int,
    val boxLeft: Int,
    val headerTop: Int,
    val boxWidth: Int,
    val ownHeaderHeight: Int,
    val lines: List<String>,
    val kind: ParticipantKind,
)

private class RowLayout(val arrowY: Int, val kind: MessageKind)

private class FrameLayout(val frame: DiagramFrame, val left: Int, val top: Int, val right: Int, val bottom: Int)

private class NoteLayout(val note: DiagramNoteMark, val x: Int, val y: Int, val width: Int, val height: Int, val lines: List<String>)

private class DiagramLayout(
    val widthPx: Int,
    val heightPx: Int,
    val titleY: Int, // baseline for the title text, -1 when spec.title is blank
    val lifelineBottom: Int,
    val participants: List<ParticipantLayout>,
    val rows: List<RowLayout>,
    val frames: List<FrameLayout>,
    val notes: List<NoteLayout>,
    val footerY: Int, // baseline for the truncation footer text, -1 when the diagram isn't truncated
    val metrics: Metrics,
    val titleFont: Font,
    val participantFont: Font,
    val labelFont: Font,
    val frameFont: Font,
    val noteFont: Font,
    val footerFont: Font,
)

// ── Text measuring helpers ────────────────────────────────────────────────────────────────────

private fun ellipsize(s: String, fm: FontMetrics, maxWidth: Int): String {
    if (maxWidth <= 0) return ""
    if (fm.stringWidth(s) <= maxWidth) return s
    var end = s.length
    while (end > 0 && fm.stringWidth(s.substring(0, end) + ELLIPSIS) > maxWidth) end--
    return if (end <= 0) ELLIPSIS else s.substring(0, end) + ELLIPSIS
}

// Wraps to at most 2 lines, splitting at the space closest to the string's midpoint rather than the
// first space that crosses the width limit — "ServiceManager" style single tokens with no good split
// point hard-break instead of overflowing.
private fun wrapTwoLines(label: String, fm: FontMetrics, maxWidth: Int): List<String> {
    if (label.isEmpty()) return listOf("")
    if (fm.stringWidth(label) <= maxWidth) return listOf(label)
    val mid = label.length / 2
    var splitAt = -1
    var bestDist = Int.MAX_VALUE
    label.forEachIndexed { i, c ->
        if (c == ' ') {
            val d = abs(i - mid)
            if (d < bestDist) {
                bestDist = d
                splitAt = i
            }
        }
    }
    val (line1, rest) = if (splitAt >= 0) {
        label.substring(0, splitAt) to label.substring(splitAt + 1)
    } else {
        var cut = 1
        while (cut < label.length && fm.stringWidth(label.substring(0, cut)) <= maxWidth) cut++
        val safeCut = (cut - 1).coerceAtLeast(1)
        label.substring(0, safeCut) to label.substring(safeCut)
    }
    return listOf(line1, ellipsize(rest, fm, maxWidth))
}

// General word-wrap up to [maxLines], used for notes and the empty-diagram placeholder — unlike
// wrapTwoLines this greedily fills as many lines as it's given rather than always splitting near
// the middle, since a note's text is prose-length, not a short participant label.
private fun wrapLines(text: String, fm: FontMetrics, maxWidth: Int, maxLines: Int): List<String> {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return listOf("")
    val lines = mutableListOf<String>()
    var current = StringBuilder()
    var idx = 0
    while (idx < words.size && lines.size < maxLines) {
        val w = words[idx]
        val candidate = if (current.isEmpty()) w else "$current $w"
        if (current.isEmpty() || fm.stringWidth(candidate) <= maxWidth) {
            current = StringBuilder(candidate)
            idx++
        } else {
            lines += current.toString()
            current = StringBuilder()
        }
    }
    if (current.isNotEmpty() && lines.size < maxLines) {
        lines += current.toString()
        idx = words.size // the trailing partial line was flushed uncut, so nothing was truncated
    }
    if (idx < words.size && lines.isNotEmpty()) {
        // More words remained than maxLines could hold — mark the cut on the last accepted line.
        lines[lines.lastIndex] = ellipsize("${lines.last()} …", fm, maxWidth).let {
            if (it == ELLIPSIS) lines.last() else it
        }
    }
    return lines.ifEmpty { listOf("") }
}

private fun drawCenteredLines(g: Graphics2D, lines: List<String>, fm: FontMetrics, centerX: Int, blockTop: Int, blockHeight: Int) {
    val lineH = fm.height
    val totalTextH = lineH * lines.size
    var y = blockTop + (blockHeight - totalTextH) / 2 + fm.ascent
    for (line in lines) {
        val w = fm.stringWidth(line)
        g.drawString(line, centerX - w / 2, y)
        y += lineH
    }
}

private fun scratchGraphics(): Graphics2D {
    val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    applyRenderingHints(g)
    return g
}

private fun applyRenderingHints(g: Graphics2D) {
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
}

private fun frameLabelText(f: DiagramFrame): String = f.label.ifBlank { "sequence" }

// '×' is appended after any width/ellipsis measurement of the base label — this only ever wraps
// the fold count collapseRepeats produced in the builder, never arbitrary user text.
private fun withRepeatSuffix(m: DiagramMessage): String = if (m.repeatCount > 1) "${m.label} ×${m.repeatCount}" else m.label

// ── Measure ────────────────────────────────────────────────────────────────────────────────────

private fun measure(diagram: SeqDiagram, scale: Float): DiagramLayout {
    val metrics = Metrics(scale)
    val titleFont = Font(Font.SANS_SERIF, Font.BOLD, (BASE_TITLE_FONT * scale).roundToInt().coerceAtLeast(1))
    val participantFont = Font(Font.SANS_SERIF, Font.PLAIN, (BASE_PARTICIPANT_FONT * scale).roundToInt().coerceAtLeast(1))
    val labelFont = Font(Font.SANS_SERIF, Font.PLAIN, (BASE_LABEL_FONT * scale).roundToInt().coerceAtLeast(1))
    val frameFont = Font(Font.SANS_SERIF, Font.PLAIN, (BASE_FRAME_LABEL_FONT * scale).roundToInt().coerceAtLeast(1))
    val noteFont = Font(Font.SANS_SERIF, Font.PLAIN, (BASE_NOTE_FONT * scale).roundToInt().coerceAtLeast(1))
    val footerFont = Font(Font.SANS_SERIF, Font.ITALIC, (BASE_FOOTER_FONT * scale).roundToInt().coerceAtLeast(1))

    val scratch = scratchGraphics()
    try {
        // ── Participant columns ──
        val pFm = scratch.getFontMetrics(participantFont)
        val maxTextW = metrics.participantBoxMaxW - 2 * metrics.participantPadH
        data class Prelim(val lines: List<String>, val boxWidth: Int, val headerH: Int)

        val prelim = diagram.participants.map { p ->
            val lines = wrapTwoLines(p.label, pFm, maxTextW)
            val textW = lines.maxOf { pFm.stringWidth(it) }
            when (p.kind) {
                ParticipantKind.TAG -> {
                    val boxW = (textW + 2 * metrics.participantPadH).coerceIn(metrics.participantBoxMinW, metrics.participantBoxMaxW)
                    val boxH = 2 * metrics.participantPadV + metrics.participantLineH * lines.size
                    Prelim(lines, boxW, boxH)
                }
                ParticipantKind.ACTOR -> {
                    val labelW = (textW + 2 * metrics.participantPadH).coerceAtMost(metrics.participantBoxMaxW)
                    val boxW = max(metrics.actorFigureW, labelW).coerceAtLeast(metrics.participantBoxMinW)
                    val labelH = metrics.participantLineH * lines.size
                    val headerH = metrics.actorFigureH + metrics.actorLabelGap + labelH
                    Prelim(lines, boxW, headerH)
                }
            }
        }
        val maxHeaderHeight = prelim.maxOfOrNull { it.headerH } ?: 0

        val titleH = if (diagram.spec.title.isNotBlank()) scratch.getFontMetrics(titleFont).height + metrics.titleGap else 0
        val titleY = if (diagram.spec.title.isNotBlank()) metrics.margin + scratch.getFontMetrics(titleFont).ascent else -1
        val headerTop = metrics.margin + titleH

        var cursorX = metrics.margin
        val participantLayouts = diagram.participants.mapIndexed { i, p ->
            val pr = prelim[i]
            val boxLeft = cursorX
            val centerX = boxLeft + pr.boxWidth / 2
            cursorX = boxLeft + pr.boxWidth + metrics.columnGap
            ParticipantLayout(centerX, boxLeft, headerTop, pr.boxWidth, pr.headerH, pr.lines, p.kind)
        }
        val contentRight = if (participantLayouts.isNotEmpty()) cursorX - metrics.columnGap else metrics.margin

        // ── Message rows ──
        // arrowY[i] = messagesTop + (sum of every prior row's own pitch) + rowH: every row reserves
        // rowH of space ABOVE its own arrow line (room for the label), and a SELF row additionally
        // reserves selfExtra BELOW its own line (room for the loop) before the next row's "above"
        // space begins. This is what keeps arrowY strictly increasing by at least rowH regardless of
        // which rows are SELF, and keeps a SELF loop from ever reaching into its neighbor's hit box.
        val messagesTop = headerTop + maxHeaderHeight + metrics.headerToRowsGap
        var acc = 0
        val rows = diagram.messages.map { m ->
            val row = RowLayout(messagesTop + acc + metrics.rowH, m.kind)
            acc += metrics.rowH + if (m.kind == MessageKind.SELF) metrics.selfExtra else 0
            row
        }
        val lifelineBottom = messagesTop + acc

        // ── Frames (behind the rows they span) ──
        val frameLayouts = diagram.frames.mapNotNull { f ->
            if (f.firstMsg !in diagram.messages.indices || f.lastMsg !in diagram.messages.indices || f.firstMsg > f.lastMsg) return@mapNotNull null
            val touched = (f.firstMsg..f.lastMsg)
                .flatMap { i -> diagram.messages.getOrNull(i)?.let { listOf(it.fromIdx, it.toIdx) }.orEmpty() }
                .distinct()
            if (touched.isEmpty()) return@mapNotNull null
            val loX = participantLayouts.getOrNull(touched.min())?.centerX ?: return@mapNotNull null
            val hiX = participantLayouts.getOrNull(touched.max())?.centerX ?: return@mapNotNull null
            val depthInset = f.depth * metrics.frameInsetPerDepth
            val left = min(loX, hiX) - metrics.columnGap / 3 + depthInset
            val right = max(loX, hiX) + metrics.columnGap / 3 - depthInset
            val top = rows[f.firstMsg].arrowY - metrics.rowH / 2 - metrics.framePadV - metrics.frameLabelH + depthInset / 2
            val bottom = rows[f.lastMsg].arrowY + metrics.rowH / 2 + metrics.framePadV - depthInset / 2
            FrameLayout(f, left, top, max(left + 1, right), max(top + 1, bottom))
        }

        // ── Notes ──
        val noteFm = scratch.getFontMetrics(noteFont)
        val noteInnerW = metrics.noteW - 2 * metrics.notePad
        val noteLayouts = diagram.notes.mapNotNull { n ->
            if (n.afterMsg !in rows.indices) return@mapNotNull null
            val px = participantLayouts.getOrNull(n.participantIdx)?.centerX ?: return@mapNotNull null
            val lines = wrapLines(n.text, noteFm, noteInnerW, BASE_NOTE_MAX_LINES)
            val h = 2 * metrics.notePad + lines.size * metrics.noteLineH
            // A note simply widens the canvas rather than being clamped into the existing width:
            // maxNoteRight below folds it into widthPx. Clamping instead would push a note left over
            // its own participant's lifeline, which reads far worse than a slightly wider image.
            val x = px + metrics.noteGapFromLifeline
            val y = rows[n.afterMsg].arrowY - h / 2
            NoteLayout(n, x, y, metrics.noteW, h, lines)
        }

        val maxNoteRight = noteLayouts.maxOfOrNull { it.x + it.width } ?: 0
        val widthPx = max(contentRight, maxNoteRight) + metrics.margin

        var heightPx = lifelineBottom + metrics.bottomMargin
        val footerY = if (diagram.truncated) {
            val y = heightPx + metrics.footerGapAbove + scratch.getFontMetrics(footerFont).ascent
            heightPx += metrics.footerH
            y
        } else {
            -1
        }

        return DiagramLayout(
            widthPx = widthPx.coerceAtLeast(1),
            heightPx = heightPx.coerceAtLeast(1),
            titleY = titleY,
            lifelineBottom = lifelineBottom,
            participants = participantLayouts,
            rows = rows,
            frames = frameLayouts,
            notes = noteLayouts,
            footerY = footerY,
            metrics = metrics,
            titleFont = titleFont,
            participantFont = participantFont,
            labelFont = labelFont,
            frameFont = frameFont,
            noteFont = noteFont,
            footerFont = footerFont,
        )
    } finally {
        scratch.dispose()
    }
}

// ── Message geometry shared between painting and hit-box building ────────────────────────────
// One function computing "where does this arrow actually live" so the pixels drawn and the pixels
// reported as clickable can never disagree — see the file header's "one drawing routine" doc.

private class MsgGeom(val isSelf: Boolean, val x1: Int, val x2: Int, val y: Int, val loopH: Int)

private fun messageGeometry(m: DiagramMessage, row: RowLayout, layout: DiagramLayout): MsgGeom? {
    val fromP = layout.participants.getOrNull(m.fromIdx) ?: return null
    val toP = layout.participants.getOrNull(m.toIdx) ?: return null
    return if (m.kind == MessageKind.SELF) {
        MsgGeom(true, fromP.centerX, fromP.centerX + layout.metrics.selfLoopW, row.arrowY, layout.metrics.selfLoopH)
    } else {
        MsgGeom(false, fromP.centerX, toP.centerX, row.arrowY, 0)
    }
}

private fun buildHits(diagram: SeqDiagram, layout: DiagramLayout): List<ArrowHit> {
    val hitInset = layout.metrics.hitInset
    return diagram.messages.mapIndexedNotNull { i, m ->
        val row = layout.rows.getOrNull(i) ?: return@mapIndexedNotNull null
        val geo = messageGeometry(m, row, layout) ?: return@mapIndexedNotNull null
        if (geo.isSelf) {
            // A SELF loop hangs BELOW its arrowY (that's what selfExtra reserves), so its box starts
            // at the line and covers the loop.
            ArrowHit(i, m.entryId, geo.x1, geo.y - hitInset / 2, geo.x2 - geo.x1, geo.loopH + hitInset)
        } else {
            // Centered on the arrow line rather than hanging below it, because a direct message's
            // LABEL is drawn above the line (see paintDirectMessage) and clicking the label text is
            // the gesture users actually make. Safe on both sides: the box top sits rowH/2 above the
            // line, which clears a preceding SELF row's loop (that loop ends selfExtra - 2*hitInset
            // below ITS line, and the next arrowY is a full rowH + selfExtra further down), and the
            // box bottom sits rowH/2 above the next row's line, so consecutive boxes never touch.
            val x = min(geo.x1, geo.x2)
            val w = abs(geo.x2 - geo.x1)
            val h = (layout.metrics.rowH - hitInset).coerceAtLeast(layout.metrics.rowH / 2)
            ArrowHit(i, m.entryId, x, geo.y - h / 2, w, h)
        }
    }
}

// ── Paint ──────────────────────────────────────────────────────────────────────────────────────

private fun paint(g: Graphics2D, diagram: SeqDiagram, theme: DiagramTheme, layout: DiagramLayout) {
    g.color = theme.background
    g.fillRect(0, 0, layout.widthPx, layout.heightPx)

    layout.frames.forEach { paintFrame(g, it, layout, theme) }
    paintLifelines(g, layout, theme)
    layout.participants.forEach { paintParticipantHeader(g, it, layout, theme) }
    diagram.messages.forEachIndexed { i, m -> layout.rows.getOrNull(i)?.let { paintMessage(g, m, it, layout, theme) } }
    layout.notes.forEach { paintNote(g, it, layout, theme) }
    paintTitle(g, diagram, layout, theme)
    paintFooter(g, diagram, layout, theme)
}

private fun paintLifelines(g: Graphics2D, layout: DiagramLayout, theme: DiagramTheme) {
    g.color = theme.lifeline
    g.stroke = BasicStroke(layout.metrics.strokeThin, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, layout.metrics.dashLifeline, 0f)
    layout.participants.forEach { p ->
        g.drawLine(p.centerX, p.headerTop + p.ownHeaderHeight, p.centerX, layout.lifelineBottom)
    }
}

private fun paintParticipantHeader(g: Graphics2D, p: ParticipantLayout, layout: DiagramLayout, theme: DiagramTheme) {
    g.font = layout.participantFont
    val fm = g.fontMetrics
    when (p.kind) {
        ParticipantKind.TAG -> {
            val arc = layout.metrics.participantArc
            g.color = theme.participantFill
            g.fillRoundRect(p.boxLeft, p.headerTop, p.boxWidth, p.ownHeaderHeight, arc, arc)
            g.color = theme.participantBorder
            g.stroke = BasicStroke(layout.metrics.strokeThin)
            g.drawRoundRect(p.boxLeft, p.headerTop, p.boxWidth, p.ownHeaderHeight, arc, arc)
            g.color = theme.participantText
            drawCenteredLines(g, p.lines, fm, p.centerX, p.headerTop, p.ownHeaderHeight)
        }
        ParticipantKind.ACTOR -> {
            paintActorFigure(g, p, layout, theme)
            g.color = theme.participantText
            val labelTop = p.headerTop + layout.metrics.actorFigureH + layout.metrics.actorLabelGap
            val labelH = p.ownHeaderHeight - layout.metrics.actorFigureH - layout.metrics.actorLabelGap
            drawCenteredLines(g, p.lines, fm, p.centerX, labelTop, labelH)
        }
    }
}

private fun paintActorFigure(g: Graphics2D, p: ParticipantLayout, layout: DiagramLayout, theme: DiagramTheme) {
    val m = layout.metrics
    g.color = theme.participantText
    g.stroke = BasicStroke(layout.metrics.strokeThick, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    val cx = p.centerX
    val headTop = p.headerTop
    val headCenterY = headTop + m.actorHeadR
    g.drawOval(cx - m.actorHeadR, headTop, m.actorHeadR * 2, m.actorHeadR * 2)
    val neckY = headCenterY + m.actorHeadR
    val hipY = neckY + m.actorBodyH
    g.drawLine(cx, neckY, cx, hipY)
    val armY = neckY + m.actorBodyH / 3
    g.drawLine(cx - m.actorLimbSpan, armY, cx + m.actorLimbSpan, armY)
    val legY = headTop + m.actorFigureH
    g.drawLine(cx, hipY, cx - m.actorLimbSpan, legY)
    g.drawLine(cx, hipY, cx + m.actorLimbSpan, legY)
}

private fun drawArrowhead(g: Graphics2D, tipX: Int, tipY: Int, pointingRight: Boolean, filled: Boolean, m: Metrics, color: Color) {
    val dx = if (pointingRight) -m.arrowheadLen else m.arrowheadLen
    val backX = tipX + dx
    val p1x = backX
    val p1y = tipY - m.arrowheadW
    val p2x = backX
    val p2y = tipY + m.arrowheadW
    g.color = color
    if (filled) {
        g.fill(Polygon(intArrayOf(tipX, p1x, p2x), intArrayOf(tipY, p1y, p2y), 3))
    } else {
        g.stroke = BasicStroke(m.strokeThick, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.drawLine(tipX, tipY, p1x, p1y)
        g.drawLine(tipX, tipY, p2x, p2y)
    }
}

private fun paintMessage(g: Graphics2D, m: DiagramMessage, row: RowLayout, layout: DiagramLayout, theme: DiagramTheme) {
    val geo = messageGeometry(m, row, layout) ?: return
    g.font = layout.labelFont
    val fm = g.fontMetrics
    val text = withRepeatSuffix(m)
    if (geo.isSelf) paintSelfMessage(g, geo, text, fm, layout, theme) else paintDirectMessage(g, m, geo, text, fm, layout, theme)
}

private fun paintDirectMessage(
    g: Graphics2D,
    m: DiagramMessage,
    geo: MsgGeom,
    text: String,
    fm: FontMetrics,
    layout: DiagramLayout,
    theme: DiagramTheme,
) {
    val metrics = layout.metrics
    val x1 = geo.x1
    val x2 = geo.x2
    val y = geo.y
    g.color = theme.arrow
    g.stroke = if (m.kind == MessageKind.RETURN) {
        BasicStroke(metrics.strokeThin, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, metrics.dashReturn, 0f)
    } else {
        BasicStroke(metrics.strokeThick)
    }
    g.drawLine(x1, y, x2, y)
    val pointingRight = x2 > x1
    drawArrowhead(g, x2, y, pointingRight, filled = m.kind != MessageKind.RETURN, metrics, theme.arrow)

    val avail = (abs(x2 - x1) - metrics.arrowheadLen).coerceAtLeast(metrics.arrowheadLen)
    val clipped = ellipsize(text, fm, avail)
    val tw = fm.stringWidth(clipped)
    g.color = theme.label
    g.drawString(clipped, (x1 + x2) / 2 - tw / 2, y - metrics.labelGapAboveLine - fm.descent)
}

private fun paintSelfMessage(g: Graphics2D, geo: MsgGeom, text: String, fm: FontMetrics, layout: DiagramLayout, theme: DiagramTheme) {
    val metrics = layout.metrics
    val x0 = geo.x1
    val xOut = geo.x2
    val yTop = geo.y
    val yBot = geo.y + geo.loopH
    g.color = theme.arrow
    g.stroke = BasicStroke(metrics.strokeThick, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    g.drawLine(x0, yTop, xOut, yTop)
    g.drawLine(xOut, yTop, xOut, yBot)
    g.drawLine(xOut, yBot, x0, yBot)
    drawArrowhead(g, x0, yBot, pointingRight = false, filled = true, m = metrics, color = theme.arrow)

    val avail = (metrics.columnGap - metrics.selfLoopW).coerceAtLeast(metrics.arrowheadLen * 2)
    val clipped = ellipsize(text, fm, avail)
    g.color = theme.label
    g.drawString(clipped, xOut + metrics.labelGapAboveLine, (yTop + yBot) / 2 + fm.ascent / 2)
}

private fun paintFrame(g: Graphics2D, fl: FrameLayout, layout: DiagramLayout, theme: DiagramTheme) {
    val base = fl.frame.colorArgb?.let { Color(it, true) } ?: theme.frame
    val w = fl.right - fl.left
    val h = fl.bottom - fl.top
    val arc = 10
    // A frame spanning most of the canvas gives no grouping cue and used to turn the whole
    // preview into a tinted slab. Keep its border/label, but reserve the wash for local groups.
    if (h < layout.heightPx * .72f) {
        g.color = Color(base.red, base.green, base.blue, BASE_FRAME_WASH_ALPHA)
        g.fillRoundRect(fl.left, fl.top, w, h, arc, arc)
    }
    g.color = Color(base.red, base.green, base.blue, BASE_FRAME_BORDER_ALPHA)
    g.stroke = BasicStroke(1.5f)
    g.drawRoundRect(fl.left, fl.top, w, h, arc, arc)
    g.color = Color(base.red, base.green, base.blue)
    val label = "  ".repeat(fl.frame.depth) + frameLabelText(fl.frame)
    g.drawString(label, fl.left + 6, fl.top + 12)
}

private fun paintNote(g: Graphics2D, nl: NoteLayout, layout: DiagramLayout, theme: DiagramTheme) {
    val color = if (nl.note.isError) theme.errorAccent else theme.note
    val arc = layout.metrics.noteArc
    g.color = Color(color.red, color.green, color.blue, 50)
    g.fillRoundRect(nl.x, nl.y, nl.width, nl.height, arc, arc)
    g.color = color
    g.stroke = BasicStroke(layout.metrics.strokeThin)
    g.drawRoundRect(nl.x, nl.y, nl.width, nl.height, arc, arc)
    g.font = layout.noteFont
    val fm = g.fontMetrics
    var ty = nl.y + layout.metrics.notePad + fm.ascent
    nl.lines.forEach { line ->
        g.drawString(line, nl.x + layout.metrics.notePad, ty)
        ty += layout.metrics.noteLineH
    }
}

private fun paintTitle(g: Graphics2D, diagram: SeqDiagram, layout: DiagramLayout, theme: DiagramTheme) {
    if (layout.titleY < 0) return
    g.font = layout.titleFont
    g.color = theme.label
    val fm = g.fontMetrics
    val clipped = ellipsize(diagram.spec.title, fm, layout.widthPx - 2 * layout.metrics.margin)
    g.drawString(clipped, layout.metrics.margin, layout.titleY)
}

private fun paintFooter(g: Graphics2D, diagram: SeqDiagram, layout: DiagramLayout, theme: DiagramTheme) {
    if (layout.footerY < 0) return
    // A silently-truncated diagram is a correctness trap for the user (see task spec) — this line is
    // deliberately drawn in errorAccent, not a muted label color, so it can't be mistaken for a
    // caption.
    g.font = layout.footerFont
    g.color = theme.errorAccent
    val fm = g.fontMetrics
    val text = "Diagram truncated — showing the first ${diagram.messages.size} of ${diagram.scannedEntries} " +
        "scanned entries (cap: ${diagram.spec.options.maxMessages} messages). Some activity after this point is not shown."
    val clipped = ellipsize(text, fm, layout.widthPx - 2 * layout.metrics.margin)
    g.drawString(clipped, layout.metrics.margin, layout.footerY)
}

// ── Public entry points ───────────────────────────────────────────────────────────────────────

/**
 * Renders [diagram] into a single [RenderedDiagram] — the SAME BufferedImage a caller displays on
 * screen and exports to PNG (see this file's header doc). Never throws and never returns a zero-
 * sized image: a diagram with no participants or no messages renders a small explanatory
 * placeholder instead (see [renderPlaceholder]), and a diagram whose natural size would exceed
 * [MAX_IMAGE_DIM_PX] on either axis is re-measured at a smaller effective scale rather than
 * allocating an oversized raster — [RenderedDiagram.scale] always reports the scale actually used.
 */
fun renderSequenceDiagram(diagram: SeqDiagram, theme: DiagramTheme, scale: Float = 2f): RenderedDiagram {
    val safeScale = if (scale.isFinite() && scale > 0f) scale else 1f
    if (diagram.participants.isEmpty() || diagram.messages.isEmpty()) return renderPlaceholder(theme, safeScale)

    var effectiveScale = safeScale
    var layout = measure(diagram, effectiveScale)
    if (layout.widthPx > MAX_IMAGE_DIM_PX || layout.heightPx > MAX_IMAGE_DIM_PX) {
        val shrink = min(
            MAX_IMAGE_DIM_PX.toFloat() / layout.widthPx,
            MAX_IMAGE_DIM_PX.toFloat() / layout.heightPx,
        )
        effectiveScale = (effectiveScale * shrink).coerceAtLeast(0.05f)
        layout = measure(diagram, effectiveScale)
    }
    // Integer rounding across many small constants is not perfectly linear under a shrink factor, so
    // this is a defensive backstop, not the primary clamp mechanism above.
    val w = layout.widthPx.coerceIn(1, MAX_IMAGE_DIM_PX)
    val h = layout.heightPx.coerceIn(1, MAX_IMAGE_DIM_PX)

    val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    val hits: List<ArrowHit>
    try {
        applyRenderingHints(g)
        paint(g, diagram, theme, layout)
        hits = buildHits(diagram, layout)
    } finally {
        g.dispose()
    }
    return RenderedDiagram(image, hits, w, h, effectiveScale)
}

private fun renderPlaceholder(theme: DiagramTheme, scale: Float): RenderedDiagram {
    val w = (BASE_PLACEHOLDER_W * scale).roundToInt().coerceAtLeast(1)
    val h = (BASE_PLACEHOLDER_H * scale).roundToInt().coerceAtLeast(1)
    val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
        applyRenderingHints(g)
        g.color = theme.background
        g.fillRect(0, 0, w, h)
        g.color = theme.label
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, (BASE_PLACEHOLDER_FONT * scale).roundToInt().coerceAtLeast(1))
        val fm = g.fontMetrics
        val margin = (12 * scale).roundToInt()
        val lines = wrapLines("No messages to diagram — nothing was in range, or every entry was filtered out.", fm, w - 2 * margin, 3)
        var y = h / 2 - (lines.size * fm.height) / 2 + fm.ascent
        lines.forEach { line ->
            val lw = fm.stringWidth(line)
            g.drawString(line, ((w - lw) / 2).coerceAtLeast(0), y)
            y += fm.height
        }
    } finally {
        g.dispose()
    }
    return RenderedDiagram(image, emptyList(), w, h, scale)
}

/** `ImageIO.write` to an in-memory PNG — same idiom as video/VideoPlayerController.kt's encodePng
 *  and ui/VideoPanel.kt's rotated-frame export. */
fun RenderedDiagram.toPngBytes(): ByteArray {
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    return out.toByteArray()
}
