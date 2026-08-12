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
//
// measure()/paint()/buildHits() share one more thing beyond Metrics: every message's WRAPPED,
// SUFFIXED label text and its measured line widths are computed exactly once, in measure()'s
// measureLabels() pass, and carried on each row's own RowLayout. paint() and buildHits() only ever
// read row.lines/row.lineW — neither one re-wraps or re-measures a label — which is what keeps the
// three phases from silently drifting apart the way recomputing the same wrap twice invites.

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
data class ArrowHit(
    val messageIndex: Int,
    val entryId: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    /** Stable durable identity for a manual occurrence, independent of rendered list position. */
    val manualInteractionId: String? = null,
    /** Stable grouped-row identity, when the message represents a repeated manual group. */
    val groupKey: String? = null,
) {
    /** Alias used by workspace association code when it does not need to distinguish legacy hits. */
    val interactionId: String? get() = manualInteractionId
}

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

private const val BASE_COLUMN_GAP = 70f // minimum clear space between adjacent lifeline columns

// Per-gap ceiling: without one, a single pathological label could push widthPx past
// MAX_IMAGE_DIM_PX and trigger the global shrink in renderSequenceDiagram, making EVERY label
// smaller — the opposite of what widening a gap for one wide label is meant to achieve. A label
// that still doesn't fit its capped gap was already wrapped/ellipsized against BASE_LABEL_MAX_W
// (comfortably under this cap — see solveColumnGaps' own doc), so hitting the cap in practice
// means unusually wide PARTICIPANT boxes between the two ends, not an unwrapped label.
private const val BASE_COLUMN_GAP_MAX = 420f

// Wrap width budget for a MESSAGE label (participant labels use wrapTwoLines/BASE_PARTICIPANT_BOX_MAX_W
// instead). Deliberately independent of any one message's actual column gap — measureLabels() runs
// BEFORE column positions are solved, and solveColumnGaps() widens gaps to fit what this already
// decided, never the other way around. Kept a PLAIN scaled constant (not participant/gap-derived) so
// wrap width stays exactly proportional to scale — see DiagramRendererTest's scale-doubling test.
private const val BASE_LABEL_MAX_W = 320f
private const val BASE_LABEL_PAD = 6f // clear space between a label's text and the arrow/loop it labels

private const val BASE_ROW_H = 42f // minimum vertical pitch of one non-SELF message row
private const val BASE_SELF_EXTRA = 26f // minimum pitch a SELF row's loop needs, reserved AFTER its arrow y
private const val BASE_SELF_LOOP_W = 46f // how far a SELF loop bows out from its lifeline
private const val BASE_TARGETLESS_STUB_W = 38f // short unresolved outgoing stub, not a lifeline
private const val BASE_MIN_SELF_LOOP_H = 10f

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

// Mirrors DiagramSpecCodec's own MAX_LABEL_LINES (that file validates it on *decode*; this is the
// renderer's own defensive clamp for a SeqDiagram built directly in-process, e.g. by a test, that
// never went through the codec at all).
private const val RENDERER_MAX_LABEL_LINES = 8

private const val ACTIVATION_BAR_WIDTH_MULTIPLIER = 5
private const val MIN_ACTIVATION_BAR_WIDTH = 4
private const val FRAME_LOCAL_HEIGHT_RATIO = .72f
private const val FRAME_BORDER_STROKE = 1.5f
private const val NOTE_FILL_ALPHA = 50
private const val MIN_RENDER_SCALE = .05f

private const val ELLIPSIS = "…"

// ── Scaled constants bundle ───────────────────────────────────────────────────────────────────

// `scale` must be a property, not a plain constructor parameter: a constructor parameter is in
// scope for property initializers but NOT for member function bodies, and s()/sf() below are
// member functions called from those initializers.
internal class Metrics(val scale: Float) {
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
    val columnGapMax = s(BASE_COLUMN_GAP_MAX)
    val labelMaxW = s(BASE_LABEL_MAX_W)
    val labelPad = s(BASE_LABEL_PAD)

    val rowH = s(BASE_ROW_H)
    val selfExtra = s(BASE_SELF_EXTRA)
    val selfLoopW = s(BASE_SELF_LOOP_W)
    val targetlessStubW = s(BASE_TARGETLESS_STUB_W)
    val hitInset = s(BASE_HIT_INSET)
    val minSelfLoopH = s(BASE_MIN_SELF_LOOP_H)

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

    // A short dash makes non-blocking dispatch visually distinct from both a filled CALL and
    // the longer-dashed RETURN, while the open arrowhead below provides a second clear cue.
    val dashAsync = floatArrayOf(sf(3f), sf(3f))
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

/** One message row's vertical geometry AND its already-wrapped/suffixed label — see this file's
 *  header doc for why paint()/buildHits() must only ever read [lines]/[lineW] from here rather
 *  than re-deriving them. [above] is the space this row reserves ABOVE its own [arrowY] (room for
 *  a direct message's stacked label lines, or a fixed minimum for a self message); [below] is the
 *  space reserved AFTER it (0 for a direct message, room for the loop + its label for a self one).
 *  [loopH] is only meaningful for a SELF row. */
private class RowLayout(
    val arrowY: Int,
    val kind: MessageKind,
    val lines: List<String>,
    val lineW: Int,
    val above: Int,
    val below: Int,
    val loopH: Int,
)

private class FrameLayout(val frame: DiagramFrame, val left: Int, val top: Int, val right: Int, val bottom: Int)

private class NoteLayout(val note: DiagramNoteMark, val x: Int, val y: Int, val width: Int, val height: Int, val lines: List<String>)

private class ActivationLayout(val span: DiagramActivationSpan, val x: Int, val top: Int, val bottom: Int, val width: Int)

// A measured layout is intentionally one immutable transport object shared by paint and hit-test.
@Suppress("LongParameterList")
private class DiagramLayout(
    val widthPx: Int,
    val heightPx: Int,
    // Baseline for the title text, -1 when spec.title is blank.
    val titleY: Int,
    val lifelineBottom: Int,
    val participants: List<ParticipantLayout>,
    val rows: List<RowLayout>,
    val frames: List<FrameLayout>,
    val notes: List<NoteLayout>,
    val activations: List<ActivationLayout>,
    // Baseline for the truncation footer text, -1 when the diagram isn't truncated.
    val footerY: Int,
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

// Bounds the rendered participant label by characters and lines, then wraps at the actual rendered
// width. Single-token names are hard-broken rather than allowed to overflow the participant box.
private fun wrapParticipantLines(label: String, fm: FontMetrics, maxWidth: Int, maxChars: Int, maxLines: Int): List<String> {
    if (label.isEmpty()) return listOf("")
    val bounded = if (label.length > maxChars) label.take(maxChars.coerceAtLeast(1)) + ELLIPSIS else label
    return wrapLines(bounded, fm, maxWidth, maxLines.coerceIn(1, RENDERER_MAX_LABEL_LINES))
}

// Cuts a single unbreakable token into as many maxWidth-wide pieces as it takes, the same
// grow-until-it-doesn't-fit-then-back-off loop wrapTwoLines uses for its own hard-break case.
// wrapLines' greedy fill (below) calls this only when a lone word already exceeds maxWidth on an
// otherwise-empty line — without it, that one word would sail past maxWidth as a single
// over-length line rather than actually wrapping, which is exactly the gap this closes.
private fun hardBreakWord(word: String, fm: FontMetrics, maxWidth: Int): List<String> {
    if (word.isEmpty() || maxWidth <= 0) return listOf(word)
    val pieces = mutableListOf<String>()
    var remaining = word
    while (fm.stringWidth(remaining) > maxWidth && remaining.length > 1) {
        var cut = 1
        while (cut < remaining.length && fm.stringWidth(remaining.substring(0, cut)) <= maxWidth) cut++
        val safeCut = (cut - 1).coerceAtLeast(1)
        pieces += remaining.substring(0, safeCut)
        remaining = remaining.substring(safeCut)
    }
    pieces += remaining
    return pieces
}

// General word-wrap up to [maxLines], used for message labels, notes, and the empty-diagram
// placeholder — greedily fills as many lines as it's given rather than always splitting near the
// middle (wrapTwoLines' own strategy), since these are prose-length texts, not a short participant
// label. A lone word that alone exceeds [maxWidth] is hard-broken via [hardBreakWord] rather than
// left to overflow its line — see that function's own doc for why this is necessary.
@Suppress("CyclomaticComplexMethod")
private fun wrapLines(text: String, fm: FontMetrics, maxWidth: Int, maxLines: Int): List<String> {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return listOf("")
    val lines = mutableListOf<String>()
    var current = StringBuilder()
    var idx = 0
    while (idx < words.size && lines.size < maxLines) {
        val w = words[idx]
        if (current.isEmpty() && fm.stringWidth(w) > maxWidth) {
            val pieces = hardBreakWord(w, fm, maxWidth)
            for (i in 0 until pieces.size - 1) {
                if (lines.size >= maxLines) break
                lines += pieces[i]
            }
            current = if (lines.size < maxLines) StringBuilder(pieces.last()) else StringBuilder()
            idx++
            continue
        }
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

// The label text BEFORE any repeat-count suffix, and the suffix itself, kept as two functions
// (rather than the one withRepeatSuffix used to be) so measureLabels can wrap/ellipsize the BASE
// text first and append the suffix to the last wrapped line afterward — appending before
// ellipsizing (the old order) meant the repeat count was the first thing an over-long label lost.
// DiagramEmitters.kt's own labelBase-shaped `msg.label` / repeatSuffix(count) already gets this
// split right for dialect text, which carries no width budget; this is the same split adapted to
// one that does.
private fun labelBase(m: DiagramMessage): String =
    if (m.targetless) "${m.label} · needs target" else m.label

private fun repeatSuffixText(m: DiagramMessage): String = if (m.repeatCount > 1) " ×${m.repeatCount}" else ""

// ── Label wrapping (Part of measure — see this file's header doc) ────────────────────────────

/** [lines] is the message's final, already-suffixed label text, one entry per wrapped/ellipsized
 *  line; [maxLineW] is the widest of those lines' pixel widths — what solveColumnGaps and the
 *  self-message hit box both need, and what RowLayout carries forward for paint(). */
internal class LabelMeasure(val lines: List<String>, val maxLineW: Int)

// Wraps every message's label exactly once, up front, against a FIXED width budget
// (metrics.labelMaxW) — independent of any one message's actual column gap, which is solved
// AFTER this (see solveColumnGaps). The repeat-count suffix (if any) is reserved for on the LAST
// line's budget before wrapping, then appended to it afterward, so a long-but-repeated label can
// never lose its "×N" the way the pre-Part-2 single ellipsize(label + " ×N", …) call could.
internal fun measureLabels(messages: List<DiagramMessage>, fm: FontMetrics, maxLines: Int, maxWidthPx: Int): List<LabelMeasure> {
    val clampedMaxLines = maxLines.coerceIn(1, RENDERER_MAX_LABEL_LINES)
    return messages.map { m ->
        val suffix = repeatSuffixText(m)
        val suffixW = if (suffix.isEmpty()) 0 else fm.stringWidth(suffix)
        val budget = (maxWidthPx - suffixW).coerceAtLeast(fm.stringWidth(ELLIPSIS))
        val wrapped = wrapLines(labelBase(m), fm, budget, clampedMaxLines)
        val withSuffix = if (suffix.isEmpty()) {
            wrapped
        } else {
            wrapped.toMutableList().also { it[it.lastIndex] = it.last() + suffix }
        }
        val maxLineW = withSuffix.maxOfOrNull { fm.stringWidth(it) } ?: 0
        LabelMeasure(withSuffix, maxLineW)
    }
}

/** [gaps] is one entry per adjacent-column gap (size participantCount - 1), each already widened
 *  (never shrunk) to fit every message that spans it, and clamped to [Metrics.columnGapMax].
 *  [lastColumnSelfExtra] is the extra clear space a self message on the RIGHTMOST participant
 *  needs to its right — there is no gap to widen for that column, so this is folded into the
 *  canvas width directly in measure() instead (this is specifically the "self loop clipped off
 *  the right edge of the image" bug this task fixes). */
internal class SolvedGaps(val gaps: IntArray, val lastColumnSelfExtra: Int)

// Widens each column gap to fit every message that crosses it, distributing one message's own
// requirement evenly across however many gaps it spans (k = |toIdx - fromIdx|), and NEVER shrinks
// a gap a previous message already widened — order of iteration therefore never changes the
// result, only ever grows it further.
internal fun solveColumnGaps(
    messages: List<DiagramMessage>,
    labelMeasures: List<LabelMeasure>,
    boxWidths: IntArray,
    metrics: Metrics,
): SolvedGaps {
    val n = boxWidths.size
    val gaps = IntArray((n - 1).coerceAtLeast(0)) { metrics.columnGap }
    var lastColumnSelfExtra = 0
    messages.forEachIndexed { i, m ->
        val measure = labelMeasures.getOrNull(i) ?: return@forEachIndexed
        if (m.fromIdx !in 0 until n || m.toIdx !in 0 until n) return@forEachIndexed
        if (m.targetless) {
            val c = m.fromIdx
            val need = metrics.targetlessStubW + metrics.labelPad + measure.maxLineW
            if (c == n - 1) {
                lastColumnSelfExtra = max(lastColumnSelfExtra, need)
            } else if (c in 0 until n - 1) {
                val required = need - boxWidths[c] / 2
                if (required > gaps[c]) gaps[c] = required
            }
            return@forEachIndexed
        }
        if (m.kind == MessageKind.SELF) {
            val c = m.fromIdx
            val need = metrics.selfLoopW + metrics.labelPad + measure.maxLineW
            if (c == n - 1) {
                lastColumnSelfExtra = max(lastColumnSelfExtra, need)
            } else if (c in 0 until n - 1) {
                val required = need - boxWidths[c] / 2
                if (required > gaps[c]) gaps[c] = required
            }
            return@forEachIndexed
        }
        val a = min(m.fromIdx, m.toIdx)
        val b = max(m.fromIdx, m.toIdx)
        if (a == b) return@forEachIndexed
        val k = b - a
        val required = measure.maxLineW + 2 * metrics.labelPad + metrics.arrowheadLen
        var fixed = boxWidths[a] / 2 + boxWidths[b] / 2
        for (c in a + 1 until b) fixed += boxWidths[c]
        var currentSum = 0
        for (j in a until b) currentSum += gaps[j]
        val deficit = required - fixed - currentSum
        if (deficit > 0) {
            val base = deficit / k
            val extra = deficit % k
            for (offset in 0 until k) gaps[a + offset] += base + if (offset < extra) 1 else 0
        }
    }
    for (j in gaps.indices) gaps[j] = gaps[j].coerceAtMost(metrics.columnGapMax)
    return SolvedGaps(gaps, lastColumnSelfExtra)
}

// ── Measure ────────────────────────────────────────────────────────────────────────────────────

// Measurement has coordinated branches for every renderable construct; splitting it would make
// the shared coordinate system less auditable than retaining this single layout pass. The two
// genuinely independent sub-problems (label wrapping, column-gap solving) ARE split out, above,
// into measureLabels/solveColumnGaps — both callable and testable on their own.
@Suppress("CyclomaticComplexMethod", "LongMethod")
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
        // ── Participant box sizes (prelim only — x-positions are solved after label widths) ──
        val pFm = scratch.getFontMetrics(participantFont)
        val maxTextW = metrics.participantBoxMaxW - 2 * metrics.participantPadH

        data class Prelim(val lines: List<String>, val boxWidth: Int, val headerH: Int)

        val participantNames = participantDisplayNames(diagram.participants)
        val participantMaxChars = diagram.spec.options.participantLabelMaxChars.coerceAtLeast(1)
        val participantMaxLines = diagram.spec.options.participantLabelMaxLines.coerceAtLeast(1)
        // A one-line label is an explicit request not to wrap. Let its lifeline box grow to the
        // configured character budget instead of silently ellipsizing at the old compact-box cap.
        val oneLineTextW = if (participantMaxLines == 1) {
            pFm.stringWidth("W".repeat(participantMaxChars))
        } else {
            0
        }
        val prelim = diagram.participants.mapIndexed { index, p ->
            val lines = wrapParticipantLines(
                participantNames[index],
                pFm,
                max(maxTextW, oneLineTextW),
                participantMaxChars,
                participantMaxLines,
            )
            val textW = lines.maxOf { pFm.stringWidth(it) }
            when (p.kind) {
                ParticipantKind.TAG -> {
                    val measured = textW + 2 * metrics.participantPadH
                    val boxW = if (participantMaxLines == 1) measured.coerceAtLeast(metrics.participantBoxMinW)
                    else measured.coerceIn(metrics.participantBoxMinW, metrics.participantBoxMaxW)
                    val boxH = 2 * metrics.participantPadV + metrics.participantLineH * lines.size
                    Prelim(lines, boxW, boxH)
                }
                ParticipantKind.ACTOR -> {
                    val labelW = if (participantMaxLines == 1) textW + 2 * metrics.participantPadH
                    else (textW + 2 * metrics.participantPadH).coerceAtMost(metrics.participantBoxMaxW)
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

        // ── Message label wrapping + per-gap column widths ──
        // Must run BEFORE column x-positions are fixed: a wide label may need to widen every gap
        // it spans (see solveColumnGaps' own doc).
        val labelFm = scratch.getFontMetrics(labelFont)
        val maxLabelLines = diagram.spec.options.labelMaxLines
        val labelMeasures = measureLabels(diagram.messages, labelFm, maxLabelLines, metrics.labelMaxW)
        val boxWidths = IntArray(prelim.size) { prelim[it].boxWidth }
        val solvedGaps = solveColumnGaps(diagram.messages, labelMeasures, boxWidths, metrics)

        // A gap is added after EVERY box, including the last one (a fallback baseline value, since
        // solvedGaps.gaps has no real entry past the last real inter-column gap) — then subtracted
        // back off once at the end. This mirrors the pre-Part-2 renderer's own "always add
        // columnGap, subtract the trailing one" shape exactly, just per-gap instead of constant;
        // getting the trailing subtraction wrong here previously clipped the whole canvas short by
        // one real (possibly widened) gap width instead of the unused fallback one.
        var cursorX = metrics.margin
        val participantLayouts = diagram.participants.mapIndexed { i, p ->
            val pr = prelim[i]
            val boxLeft = cursorX
            val centerX = boxLeft + pr.boxWidth / 2
            val gap = solvedGaps.gaps.getOrElse(i) { metrics.columnGap }
            cursorX = boxLeft + pr.boxWidth + gap
            ParticipantLayout(centerX, boxLeft, headerTop, pr.boxWidth, pr.headerH, pr.lines, p.kind)
        }
        val trailingGap = solvedGaps.gaps.getOrElse(participantLayouts.size - 1) { metrics.columnGap }
        val contentRight = if (participantLayouts.isNotEmpty()) cursorX - trailingGap else metrics.margin
        // This is the fix for a self message on the LAST lifeline being clipped off the image: with
        // no gap to its right to widen, the loop+label's own required width instead extends the
        // canvas directly (folded into widthPx below), rather than being silently cut off.
        val maxSelfLabelRight = if (participantLayouts.isNotEmpty() && solvedGaps.lastColumnSelfExtra > 0) {
            participantLayouts.last().centerX + solvedGaps.lastColumnSelfExtra
        } else {
            0
        }

        // ── Message rows (variable pitch) ──
        // arrowY[i] = messagesTop + (sum of every prior row's own pitch) + above[i]: every row
        // reserves `above` space ABOVE its own arrow line (room for a direct label's stacked
        // lines, or a fixed minimum for a self row) and `below` space AFTER it (0 for a direct
        // message; room for a self row's loop AND its label, whichever needs more). Uniform rowH
        // is not an option once labels can wrap onto more than one line — see RowLayout's own doc.
        val messagesTop = headerTop + maxHeaderHeight + metrics.headerToRowsGap
        var acc = 0
        val rows = diagram.messages.mapIndexed { i, m ->
            val measured = labelMeasures.getOrElse(i) { LabelMeasure(listOf(""), 0) }
            val above: Int
            val below: Int
            val loopH: Int
            if (m.kind == MessageKind.SELF) {
                above = metrics.rowH
                below = max(metrics.selfExtra, labelFm.height * measured.lines.size + 2 * metrics.hitInset)
                loopH = (below - 2 * metrics.hitInset).coerceAtLeast(metrics.minSelfLoopH)
            } else {
                above = max(metrics.rowH, labelFm.height * measured.lines.size + metrics.labelGapAboveLine + labelFm.descent)
                below = 0
                loopH = 0
            }
            val arrowY = messagesTop + acc + above
            acc += above + below
            RowLayout(arrowY, m.kind, measured.lines, measured.maxLineW, above, below, loopH)
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
            val firstRow = rows[f.firstMsg]
            val lastRow = rows[f.lastMsg]
            var top = firstRow.arrowY - firstRow.above / 2 - metrics.framePadV - metrics.frameLabelH + depthInset / 2
            var bottom = lastRow.arrowY + max(lastRow.below, metrics.rowH / 2) + metrics.framePadV - depthInset / 2
            // Clamped exactly like a hit box (buildHits below): a frame edge must never reach into
            // the reserved space of the row just outside its own span.
            val topLimit = if (f.firstMsg > 0) rows[f.firstMsg - 1].let { it.arrowY + it.below } else Int.MIN_VALUE
            val bottomLimit = if (f.lastMsg < rows.lastIndex) rows[f.lastMsg + 1].let { it.arrowY - it.above } else Int.MAX_VALUE
            top = max(top, topLimit)
            bottom = min(bottom, bottomLimit)
            FrameLayout(f, left, top, max(left + 1, right), max(top + 1, bottom))
        }

        val activationLayouts = diagram.activationSpans.mapNotNull { span ->
            val invalidSpan = span.participantIdx !in participantLayouts.indices ||
                span.startMessage !in rows.indices ||
                span.endMessage !in rows.indices ||
                span.startMessage >= span.endMessage
            if (invalidSpan) return@mapNotNull null
            val width = (metrics.strokeThick * ACTIVATION_BAR_WIDTH_MULTIPLIER)
                .roundToInt()
                .coerceAtLeast(MIN_ACTIVATION_BAR_WIDTH)
            val x = participantLayouts[span.participantIdx].centerX - width / 2
            ActivationLayout(span, x, rows[span.startMessage].arrowY, rows[span.endMessage].arrowY, width)
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
            val row = rows[n.afterMsg]
            // A self label now occupies the same strip (centerX + noteGap) a note would anchor at
            // when they share a row and participant — push the note clear of the label instead of
            // overlapping it.
            val afterMsgObj = diagram.messages.getOrNull(n.afterMsg)
            val selfCollision = row.kind == MessageKind.SELF && afterMsgObj?.fromIdx == n.participantIdx
            val baseX = px + metrics.noteGapFromLifeline
            val x = if (selfCollision) {
                val selfLabelRight = px + metrics.selfLoopW + metrics.labelPad + row.lineW
                max(baseX, selfLabelRight + metrics.notePad)
            } else {
                baseX
            }
            val y = row.arrowY - h / 2
            NoteLayout(n, x, y, metrics.noteW, h, lines)
        }

        val maxNoteRight = noteLayouts.maxOfOrNull { it.x + it.width } ?: 0
        val widthPx = maxOf(contentRight, maxNoteRight, maxSelfLabelRight) + metrics.margin

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
            activations = activationLayouts,
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

private class MsgGeom(
    val isSelf: Boolean,
    val isTargetless: Boolean,
    val x1: Int,
    val x2: Int,
    val y: Int,
    val loopH: Int,
)

private fun messageGeometry(m: DiagramMessage, row: RowLayout, layout: DiagramLayout): MsgGeom? {
    val fromP = layout.participants.getOrNull(m.fromIdx) ?: return null
    val toP = layout.participants.getOrNull(m.toIdx) ?: return null
    return if (m.targetless) {
        MsgGeom(false, true, fromP.centerX, fromP.centerX + layout.metrics.targetlessStubW, row.arrowY, 0)
    } else if (m.kind == MessageKind.SELF) {
        MsgGeom(true, false, fromP.centerX, fromP.centerX + layout.metrics.selfLoopW, row.arrowY, row.loopH)
    } else {
        MsgGeom(false, false, fromP.centerX, toP.centerX, row.arrowY, 0)
    }
}

private fun buildHits(diagram: SeqDiagram, layout: DiagramLayout): List<ArrowHit> {
    val hitInset = layout.metrics.hitInset
    return diagram.messages.mapIndexedNotNull { i, m ->
        val row = layout.rows.getOrNull(i) ?: return@mapIndexedNotNull null
        val geo = messageGeometry(m, row, layout) ?: return@mapIndexedNotNull null
        if (geo.isTargetless) {
            val width = abs(geo.x2 - geo.x1) + layout.metrics.labelPad + row.lineW
            ArrowHit(i, m.entryId, geo.x1, geo.y - hitInset / 2, width, row.above + row.below + hitInset,
                manualInteractionId = m.originKeys.firstNotNullOfOrNull { it.manualInteractionId },
                groupKey = m.manualGroupKey,
            )
        } else if (geo.isSelf) {
            // The box now covers the loop AND its label — the label is what users actually click,
            // and row.lineW already reflects the wrapped/suffixed text this row settled on.
            val width = layout.metrics.selfLoopW + layout.metrics.labelPad + row.lineW
            ArrowHit(i, m.entryId, geo.x1, geo.y - hitInset / 2, width, row.loopH + hitInset,
                manualInteractionId = m.originKeys.firstNotNullOfOrNull { it.manualInteractionId },
                groupKey = m.manualGroupKey,
            )
        } else {
            // Clamped into the REAL gap between this row's neighbors (row pitch is no longer
            // uniform once labels can wrap), inset by hitInset so consecutive boxes never touch.
            val topLimit = layout.rows.getOrNull(i - 1)?.let { it.arrowY + it.below } ?: (row.arrowY - row.above)
            val bottomLimit = layout.rows.getOrNull(i + 1)?.let { it.arrowY - it.above }
                ?: (row.arrowY + max(row.below, layout.metrics.rowH / 2))
            val top = topLimit + hitInset
            val bottom = (bottomLimit - hitInset).coerceAtLeast(top + 1)
            val x = min(geo.x1, geo.x2)
            val w = abs(geo.x2 - geo.x1)
            ArrowHit(i, m.entryId, x, top, w, bottom - top,
                manualInteractionId = m.originKeys.firstNotNullOfOrNull { it.manualInteractionId },
                groupKey = m.manualGroupKey,
            )
        }
    }
}

// ── Paint ──────────────────────────────────────────────────────────────────────────────────────

private fun paint(g: Graphics2D, diagram: SeqDiagram, theme: DiagramTheme, layout: DiagramLayout) {
    g.color = theme.background
    g.fillRect(0, 0, layout.widthPx, layout.heightPx)

    layout.frames.forEach { paintFrame(g, it, layout, theme) }
    paintLifelines(g, layout, theme)
    layout.activations.forEach { paintActivation(g, it, theme) }
    layout.participants.forEach { paintParticipantHeader(g, it, layout, theme) }
    diagram.messages.forEachIndexed { i, m -> layout.rows.getOrNull(i)?.let { paintMessage(g, m, it, layout, theme) } }
    layout.notes.forEach { paintNote(g, it, layout, theme) }
    paintTitle(g, diagram, layout, theme)
    paintFooter(g, diagram, layout, theme)
}

private fun paintActivation(g: Graphics2D, activation: ActivationLayout, theme: DiagramTheme) {
    val height = (activation.bottom - activation.top).coerceAtLeast(1)
    // Activation bars are UML execution markers, not evidence/highlighter swatches. Keep the
    // body empty so source/rule evidence is communicated by the arrow style and tooltip, while
    // the neutral outline remains visible over the participant lifeline.
    g.color = theme.background
    g.fillRect(activation.x, activation.top, activation.width, height)
    g.color = theme.arrow
    g.stroke = BasicStroke(1f)
    g.drawRect(activation.x, activation.top, (activation.width - 1).coerceAtLeast(1), height)
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
    when {
        geo.isSelf -> paintSelfMessage(g, geo, row, fm, layout, theme)
        geo.isTargetless -> paintTargetlessMessage(g, geo, row, fm, layout, theme)
        else -> paintDirectMessage(g, m, geo, row, fm, layout, theme)
    }
}

private fun paintTargetlessMessage(
    g: Graphics2D,
    geo: MsgGeom,
    row: RowLayout,
    fm: FontMetrics,
    layout: DiagramLayout,
    theme: DiagramTheme,
) {
    val metrics = layout.metrics
    g.color = theme.errorAccent
    g.stroke = BasicStroke(metrics.strokeThin, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, metrics.dashReturn, 0f)
    g.drawLine(geo.x1, geo.y, geo.x2, geo.y)
    g.drawOval(geo.x2 - metrics.arrowheadW, geo.y - metrics.arrowheadW, metrics.arrowheadW * 2, metrics.arrowheadW * 2)
    g.font = layout.labelFont
    g.color = theme.errorAccent
    var lineY = geo.y - metrics.labelGapAboveLine - fm.descent
    for (line in row.lines.asReversed()) {
        g.drawString(line, geo.x1 + metrics.labelPad, lineY)
        lineY -= fm.height
    }
}

private fun paintDirectMessage(
    g: Graphics2D,
    m: DiagramMessage,
    geo: MsgGeom,
    row: RowLayout,
    fm: FontMetrics,
    layout: DiagramLayout,
    theme: DiagramTheme,
) {
    val metrics = layout.metrics
    val x1 = geo.x1
    val x2 = geo.x2
    val y = geo.y
    g.color = theme.arrow
    g.stroke = when (m.kind) {
        MessageKind.RETURN ->
            BasicStroke(metrics.strokeThin, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, metrics.dashReturn, 0f)
        MessageKind.ASYNC ->
            BasicStroke(metrics.strokeThin, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, metrics.dashAsync, 0f)
        else -> BasicStroke(metrics.strokeThick)
    }
    g.drawLine(x1, y, x2, y)
    val pointingRight = x2 > x1
    drawArrowhead(g, x2, y, pointingRight, filled = m.kind != MessageKind.RETURN && m.kind != MessageKind.ASYNC, metrics, theme.arrow)

    // row.lines is the wrapped/suffixed label measure() already settled on — never re-wrapped or
    // re-ellipsized here, so what's drawn can never disagree with the row pitch/column gap that
    // was solved around it. Stacked UPWARD from the arrow line, so the last (bottom) line sits
    // closest to the arrow, matching a direct message's label always having sat just above its line.
    g.color = theme.label
    val centerX = (x1 + x2) / 2
    var lineY = y - metrics.labelGapAboveLine - fm.descent
    for (line in row.lines.asReversed()) {
        val tw = fm.stringWidth(line)
        g.drawString(line, centerX - tw / 2, lineY)
        lineY -= fm.height
    }
}

private fun paintSelfMessage(g: Graphics2D, geo: MsgGeom, row: RowLayout, fm: FontMetrics, layout: DiagramLayout, theme: DiagramTheme) {
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

    // Left-aligned at the loop's outer edge, block vertically centered on the loop — same
    // row.lines measure() already settled on, never re-measured here (see paintDirectMessage's
    // own comment). This is where the reported "+0.123 …" clipping bug is actually fixed: the
    // available width now comes from the column gap solveColumnGaps widened for this exact label,
    // not a fixed columnGap-minus-loop-width budget that a timestamp prefix alone could exhaust.
    g.color = theme.label
    val totalTextH = fm.height * row.lines.size
    var lineY = (yTop + yBot) / 2 - totalTextH / 2 + fm.ascent
    for (line in row.lines) {
        g.drawString(line, xOut + metrics.labelPad, lineY)
        lineY += fm.height
    }
}

private fun paintFrame(g: Graphics2D, fl: FrameLayout, layout: DiagramLayout, theme: DiagramTheme) {
    val base = fl.frame.colorArgb?.let { Color(it, true) } ?: theme.frame
    val w = fl.right - fl.left
    val h = fl.bottom - fl.top
    val arc = 10
    // A frame spanning most of the canvas gives no grouping cue and used to turn the whole
    // preview into a tinted slab. Keep its border/label, but reserve the wash for local groups.
    if (h < layout.heightPx * FRAME_LOCAL_HEIGHT_RATIO) {
        g.color = Color(base.red, base.green, base.blue, BASE_FRAME_WASH_ALPHA)
        g.fillRoundRect(fl.left, fl.top, w, h, arc, arc)
    }
    g.color = Color(base.red, base.green, base.blue, BASE_FRAME_BORDER_ALPHA)
    g.stroke = BasicStroke(FRAME_BORDER_STROKE)
    g.drawRoundRect(fl.left, fl.top, w, h, arc, arc)
    g.color = Color(base.red, base.green, base.blue)
    val label = "  ".repeat(fl.frame.depth) + frameLabelText(fl.frame)
    g.drawString(label, fl.left + 6, fl.top + 12)
}

private fun paintNote(g: Graphics2D, nl: NoteLayout, layout: DiagramLayout, theme: DiagramTheme) {
    val color = if (nl.note.isError) theme.errorAccent else theme.note
    val arc = layout.metrics.noteArc
    g.color = Color(color.red, color.green, color.blue, NOTE_FILL_ALPHA)
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
        effectiveScale = (effectiveScale * shrink).coerceAtLeast(MIN_RENDER_SCALE)
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
