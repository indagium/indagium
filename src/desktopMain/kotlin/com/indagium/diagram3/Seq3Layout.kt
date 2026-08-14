@file:Suppress("TooManyFunctions")

package com.indagium.diagram3

import kotlin.math.max
import kotlin.math.min

// ── Seq3Document -> Seq3Layout ──────────────────────────────────────────────────────────────
//
// The single geometry source for BOTH the on-screen Compose canvas (phase 4) and the headless PNG
// rasterizer (Seq3Raster.kt) — that is the whole point of this file existing separately from
// either renderer: compute the numbers exactly once, so an export can never draw something the
// user didn't see on screen. Every coordinate/size here is UNIT-LESS ("1x logical units"), not
// pixels — a consumer that wants pixels multiplies by its own scale (Compose: density; raster:
// Seq3RasterOptions.scale) using a Graphics2D/DrawScope transform, never by re-measuring text at a
// different font size. That discipline is why [Seq3TextMetrics] below is queried exactly once per
// label, here, and never again downstream — see Seq3Raster.kt's own header for the other half of
// this contract (it scales the CANVAS TRANSFORM, not the numbers).
//
// Column-gap solving is a fairly direct adaptation of `diagram/SeqDiagramRenderer.kt:517-569`'s
// `solveColumnGaps` (widen-only, distribute-across-span, never shrink), generalized from
// `DiagramMessage` to this package's own [Emission] shape. Label wrapping is a trimmed adaptation
// of that file's `wrapLines`/`ellipsize` (`:356-436`) — v3 defaults to one label line (the design
// mock never wraps a label), but the general N-line path is kept because [Seq3LayoutOptions] still
// exposes it.

// ── Text measurement (kept behind an interface — see this file's header) ───────────────────────

/** Which durable text role is being measured. [basePointSize] is the UNSCALED point size a real
 *  AWT-backed [Seq3TextMetrics] (Seq3Raster.kt) must build its `java.awt.Font` at — kept on the
 *  enum itself, not a second lookup table, so raster/canvas and this file can never disagree about
 *  what "the LABEL font" means. */
enum class Seq3FontRole(val basePointSize: Double) {
    LIFELINE(13.0),
    LABEL(12.0),
    BADGE(10.0),
    FRAGMENT(11.0),
    NOTE(11.0),
    STUB(10.0),
}

/** Single-line text measurement, abstracted so [layoutSeq3] can run against a real AWT
 *  `FontMetrics` (Seq3Raster.kt) or a deterministic stub (Seq3LayoutTest) without this file ever
 *  importing `java.awt` — keeping the geometry math itself testable without a live display. */
interface Seq3TextMetrics {
    fun width(role: Seq3FontRole, text: String): Double

    fun lineHeight(role: Seq3FontRole): Double
}

data class Seq3LayoutOptions(
    val textMetrics: Seq3TextMetrics,
    /** Matches `diagram.SeqDiagramSpecOptions.labelMaxLines`'s idea — the mock itself never wraps,
     *  so the default is 1 (== ellipsize, no wrap), but a caller may opt into more. */
    val maxLabelLines: Int = 1,
)

// ── Geometry ─────────────────────────────────────────────────────────────────────────────────

data class Seq3Box(val x: Double, val y: Double, val width: Double, val height: Double)

data class Seq3LifelineColumn(
    val lifelineId: String,
    val label: String,
    val centerX: Double,
    val header: Seq3Box,
    val lifelineTop: Double,
    val lifelineBottom: Double,
)

/** One drawn row on the canvas. [messageId] is always the owning [Seq3Message.id] so phase 4 can
 *  hit-test a click back to a queue row and vice versa (two-way row<->arrow, design spec §04).
 *  [occurrenceEntryId] is the real log line this exact drawn arrow jumps to on click — the FIRST
 *  occurrence's for a collapsed/badged row, the specific occurrence's for an EVERY/FIRST_LAST row. */
sealed class Seq3RowGeometry {
    abstract val messageId: String
    abstract val y: Double
    abstract val occurrenceEntryId: Int?
}

data class Seq3ArrowRow(
    override val messageId: String,
    override val y: Double,
    override val occurrenceEntryId: Int?,
    // never SELF/NOTE — those get their own row types below
    val kind: Seq3Kind,
    val fromLifelineId: String,
    val toLifelineId: String,
    val fromX: Double,
    val toX: Double,
    val label: String,
    val labelBox: Seq3Box,
    val repeatCount: Int,
    /** Non-null exactly when [repeatCount] > 1 — the design spec's "×n" badge box, drawn AFTER the
     *  label (see spec §04's row description), never baked into [label]'s own text. */
    val badgeBox: Seq3Box?,
) : Seq3RowGeometry()

/** [Seq3Kind.SELF] "draws as a loop, not a straight arrow" (design spec §03) — its own row type
 *  rather than a special case of [Seq3ArrowRow] so a consumer can never accidentally draw a self
 *  message as a straight line by forgetting to check `kind == SELF`. */
data class Seq3SelfLoopRow(
    override val messageId: String,
    override val y: Double,
    override val occurrenceEntryId: Int?,
    val lifelineId: String,
    val x: Double,
    val loopBottomY: Double,
    val loopWidth: Double,
    val label: String,
    val labelBox: Seq3Box,
    val repeatCount: Int,
    val badgeBox: Seq3Box?,
) : Seq3RowGeometry()

/** An unresolved ([Seq3Message.toLifelineId] == null) message — design spec §04: "Unresolved
 *  messages draw as a dashed amber stub ending in a `drop on a lifeline` pill — never as nothing."
 *  Always exactly ONE row per message regardless of [Seq3Message.repeat]: there is no second
 *  lifeline to fan the repeat out across yet, so [repeatCount] is carried for the badge instead of
 *  being expanded into multiple stub rows. */
data class Seq3UnresolvedStubRow(
    override val messageId: String,
    override val y: Double,
    override val occurrenceEntryId: Int?,
    val fromLifelineId: String,
    val fromX: Double,
    val stubEndX: Double,
    val label: String,
    val labelBox: Seq3Box,
    val dropPill: Seq3Box,
    val repeatCount: Int,
) : Seq3RowGeometry()

/** A [Seq3Kind.NOTE] message — renders anchored on one lifeline, distinct from a [Seq3NoteBox]
 *  (which spans a user's multi-message selection, design spec §06). */
data class Seq3MessageNoteRow(
    override val messageId: String,
    override val y: Double,
    override val occurrenceEntryId: Int?,
    val lifelineId: String,
    val box: Seq3Box,
    /** Already wrapped/ellipsized to [Seq3LayoutOptions.maxLabelLines] — [box]'s height was sized
     *  from exactly this many lines, so a renderer must draw these, never the message's own
     *  un-wrapped [Seq3Message.labelTemplate] text, or the two will silently disagree. */
    val lines: List<String>,
) : Seq3RowGeometry()

/** The elision marker between a [Seq3Repeat.FIRST_LAST] message's first and last drawn rows. */
data class Seq3ElisionRow(
    override val messageId: String,
    override val y: Double,
    val lifelineId: String,
    val elidedCount: Int,
    val box: Seq3Box,
) : Seq3RowGeometry() {
    override val occurrenceEntryId: Int? get() = null
}

data class Seq3FragmentBox(
    val fragmentId: String,
    val kind: Seq3FragmentKind,
    val label: String,
    val box: Seq3Box,
    /** Nesting depth, 0 = outermost — mirrors `diagram.DiagramFrame.depth`'s own convention, used
     *  by a renderer to inset nested boxes and stagger their label baselines. */
    val depth: Int,
)

data class Seq3NoteBox(val noteId: String, val box: Seq3Box, val text: String)

data class Seq3Layout(
    val width: Double,
    val height: Double,
    val lifelines: List<Seq3LifelineColumn>,
    val rows: List<Seq3RowGeometry>,
    val fragments: List<Seq3FragmentBox>,
    val notes: List<Seq3NoteBox>,
    /** Design spec §07: "a crossing count that tells you when an arrangement is bad" — see
     *  [computeCrossingCount] for the exact definition. Computed over the CURRENT lifeline
     *  ordinal order; dragging a lifeline chip (Seq3Queue, later) changes [Seq3Lifeline.ordinal]
     *  and this number changes with it on the next layout call. */
    val crossingCount: Int,
)

// ── Layout constants (all unit-less "1x" values — see this file's header) ──────────────────────

private const val MARGIN = 24.0
private const val HEADER_PAD_H = 12.0
private const val HEADER_PAD_V = 6.0
private const val HEADER_MIN_W = 90.0
private const val HEADER_MAX_W = 200.0
private const val HEADER_TO_ROWS_GAP = 22.0
private const val BOTTOM_MARGIN = 20.0

private const val COLUMN_GAP = 70.0
private const val COLUMN_GAP_MAX = 420.0
private const val LABEL_PAD = 6.0
private const val ARROWHEAD_LEN = 9.0

private const val ROW_H = 42.0
private const val SELF_LOOP_W = 46.0
private const val SELF_EXTRA = 26.0
private const val STUB_W = 38.0
private const val PILL_PAD_H = 8.0
private const val PILL_H = 18.0
private const val BADGE_PAD_H = 6.0
private const val BADGE_H = 16.0
private const val NOTE_ROW_W = 130.0
private const val NOTE_PAD = 6.0
private const val NOTE_LINE_H = 14.0
private const val ELISION_BOX_W = 40.0

private const val FRAGMENT_PAD = 10.0
private const val FRAGMENT_LABEL_H = 16.0
private const val FRAGMENT_INSET_PER_DEPTH = 10.0

private const val ELLIPSIS = "…"

// ── Public entry point ──────────────────────────────────────────────────────────────────────

/**
 * Lays out [doc] into unit-less canvas geometry. Never throws: an empty document (no lifelines, or
 * no visible messages) produces a minimal/empty [Seq3Layout] rather than failing, matching the rest
 * of this package's "never throw on bad/degenerate input" posture (see Seq3Generator.kt's header).
 */
fun layoutSeq3(doc: Seq3Document, opts: Seq3LayoutOptions): Seq3Layout {
    val lifelinesSorted = doc.lifelines.sortedBy { it.ordinal }
    val lifelineIndex = lifelinesSorted.withIndex().associate { (i, l) -> l.id to i }
    val visibleMessages = doc.messages.filter { it.visibility == Seq3Visibility.VISIBLE }
    val crossingCount = computeCrossingCount(visibleMessages, lifelineIndex)
    if (lifelinesSorted.isEmpty()) {
        return Seq3Layout(0.0, 0.0, emptyList(), emptyList(), emptyList(), emptyList(), crossingCount)
    }

    val tm = opts.textMetrics
    val headerWidths = lifelinesSorted.map { l ->
        (tm.width(Seq3FontRole.LIFELINE, l.name) + 2 * HEADER_PAD_H).coerceIn(HEADER_MIN_W, HEADER_MAX_W)
    }
    val headerHeight = tm.lineHeight(Seq3FontRole.LIFELINE) + 2 * HEADER_PAD_V

    val emissions = visibleMessages.flatMap(::expandForLayout)
    val requirements = emissions.map { measureRequirement(it, tm, opts.maxLabelLines) }
    val gapSolve = solveGaps(emissions, requirements, lifelineIndex, headerWidths)

    val (lefts, centers, contentRight) = placeColumns(headerWidths, gapSolve.gaps)
    val rowBuild = buildRows(emissions, requirements, lifelineIndex, centers, headerHeight)

    val fragments = layoutFragments(doc.fragments, rowBuild.firstRowIndex, rowBuild.lastRowIndex, rowBuild.rows)
    val notes = layoutNotes(doc.notes, rowBuild.lastRowIndex, rowBuild.rows, centers, tm)

    val rightEdge = maxOf(contentRight, gapSolve.rightExtra + (centers.lastOrNull() ?: 0.0), rowBuild.rightExtra)
    val noteRight = notes.maxOfOrNull { it.box.x + it.box.width } ?: 0.0
    val width = maxOf(rightEdge, noteRight) + MARGIN
    val height = rowBuild.bottomY + BOTTOM_MARGIN

    val lifelineColumns = lifelinesSorted.mapIndexed { i, l ->
        Seq3LifelineColumn(
            lifelineId = l.id,
            label = l.name,
            centerX = centers[i],
            header = Seq3Box(lefts[i], MARGIN, headerWidths[i], headerHeight),
            lifelineTop = MARGIN + headerHeight,
            lifelineBottom = rowBuild.bottomY,
        )
    }
    return Seq3Layout(width, height, lifelineColumns, rowBuild.rows, fragments, notes, crossingCount)
}

// ── Crossing count (design spec §07) ────────────────────────────────────────────────────────
//
// Defined as the standard graph-drawing notion of two arcs on a line crossing: for two message
// spans [a,b] and [c,d] (endpoints = lifeline column indices, a<b, c<d), they cross exactly when
// their endpoints properly interleave (a < c < b < d, or c < a < d < b) — overlapping but neither
// nested in nor disjoint from the other. Self/note/unresolved messages contribute no span (they
// have only one real endpoint, or none), matching the design spec's framing of this as a measure
// of the LIFELINE ARRANGEMENT, not of every message kind.

internal fun computeCrossingCount(visibleMessages: List<Seq3Message>, lifelineIndex: Map<String, Int>): Int {
    val spans = visibleMessages.mapNotNull { m -> messageSpan(m, lifelineIndex) }
    var crossings = 0
    for (i in spans.indices) {
        for (j in i + 1 until spans.size) {
            if (spansCross(spans[i], spans[j])) crossings++
        }
    }
    return crossings
}

private fun messageSpan(message: Seq3Message, lifelineIndex: Map<String, Int>): Pair<Int, Int>? {
    if (message.kind == Seq3Kind.SELF || message.kind == Seq3Kind.NOTE) return null
    val from = lifelineIndex[message.fromLifelineId] ?: return null
    val to = message.toLifelineId?.let(lifelineIndex::get) ?: return null
    if (from == to) return null
    return min(from, to) to max(from, to)
}

private fun spansCross(x: Pair<Int, Int>, y: Pair<Int, Int>): Boolean {
    val (a, b) = x
    val (c, d) = y
    return (a < c && c < b && b < d) || (c < a && a < d && d < b)
}

// ── Emission: one message -> one-or-more drawn rows ─────────────────────────────────────────
//
// Deliberately mirrors Seq3Emitters.kt's own private `expandMessage`/`Seq3Emission` shape (same
// repeat-mode fan-out rules) so the canvas and the exported Mermaid/PlantUML text can never
// disagree about how many arrows one message draws — but this is its OWN small copy, not a shared
// call, because Seq3Emitters.kt is phase-1 and this phase's brief says not to modify it.

private sealed class Emission {
    abstract val messageId: String
    abstract val fromLifelineId: String

    data class Arrow(
        override val messageId: String,
        override val fromLifelineId: String,
        val toLifelineId: String,
        val kind: Seq3Kind,
        val label: String,
        val repeatCount: Int,
        val entryId: Int?,
    ) : Emission()

    data class Self(
        override val messageId: String,
        override val fromLifelineId: String,
        val label: String,
        val repeatCount: Int,
        val entryId: Int?,
    ) : Emission()

    data class Stub(
        override val messageId: String,
        override val fromLifelineId: String,
        val label: String,
        val repeatCount: Int,
        val entryId: Int?,
    ) : Emission()

    data class Note(
        override val messageId: String,
        override val fromLifelineId: String,
        val text: String,
        val entryId: Int?,
    ) : Emission()

    data class Elision(
        override val messageId: String,
        override val fromLifelineId: String,
        val count: Int,
    ) : Emission()
}

private fun occurrenceLabel(message: Seq3Message, occurrence: Seq3Occurrence): String {
    if (message.match.captures.isEmpty()) return message.labelTemplate
    var label = message.labelTemplate
    message.match.captures.forEach { capture ->
        val value = occurrence.captureValues[capture.name] ?: return@forEach
        label = label.replace("{${capture.name}}", value)
    }
    return label
}

private fun expandForLayout(message: Seq3Message): List<Emission> {
    if (message.kind == Seq3Kind.NOTE) {
        val entryId = message.occurrences.firstOrNull()?.entryId
        return listOf(Emission.Note(message.id, message.fromLifelineId, message.labelTemplate, entryId))
    }
    if (message.toLifelineId == null) {
        val entryId = message.occurrences.firstOrNull()?.entryId
        return listOf(Emission.Stub(message.id, message.fromLifelineId, message.labelTemplate, message.occurrences.size, entryId))
    }
    val occurrences = message.occurrences
    if (occurrences.isEmpty()) return emptyList()
    val isSelf = message.kind == Seq3Kind.SELF

    fun arrow(label: String, count: Int, entryId: Int?): Emission = if (isSelf) {
        Emission.Self(message.id, message.fromLifelineId, label, count, entryId)
    } else {
        Emission.Arrow(message.id, message.fromLifelineId, message.toLifelineId, message.kind, label, count, entryId)
    }
    return when (message.repeat) {
        Seq3Repeat.EVERY -> occurrences.map { occ -> arrow(occurrenceLabel(message, occ), 1, occ.entryId) }
        Seq3Repeat.FIRST_LAST -> firstLastEmissions(message, occurrences, ::arrow)
        Seq3Repeat.COLLAPSE_ABOVE -> if (occurrences.size > message.repeatThreshold) {
            listOf(arrow(message.labelTemplate, occurrences.size, occurrences.first().entryId))
        } else {
            occurrences.map { occ -> arrow(occurrenceLabel(message, occ), 1, occ.entryId) }
        }
    }
}

private fun firstLastEmissions(
    message: Seq3Message,
    occurrences: List<Seq3Occurrence>,
    arrow: (String, Int, Int?) -> Emission,
): List<Emission> {
    if (occurrences.size <= 1) return listOf(arrow(occurrenceLabel(message, occurrences.first()), 1, occurrences.firstOrNull()?.entryId))
    val elided = occurrences.size - 2
    return buildList {
        add(arrow(occurrenceLabel(message, occurrences.first()), 1, occurrences.first().entryId))
        if (elided > 0) add(Emission.Elision(message.id, message.fromLifelineId, elided))
        add(arrow(occurrenceLabel(message, occurrences.last()), 1, occurrences.last().entryId))
    }
}

// ── Label/requirement measurement ───────────────────────────────────────────────────────────

private class RowRequirement(val lines: List<String>, val labelWidth: Double, val badgeWidth: Double)

private fun badgeText(count: Int): String = "×$count"

private fun measureRequirement(emission: Emission, tm: Seq3TextMetrics, maxLines: Int): RowRequirement {
    val (text, repeatCount) = when (emission) {
        is Emission.Arrow -> emission.label to emission.repeatCount
        is Emission.Self -> emission.label to emission.repeatCount
        is Emission.Stub -> emission.label to emission.repeatCount
        is Emission.Note -> emission.text to 1
        is Emission.Elision -> return RowRequirement(emptyList(), 0.0, 0.0)
    }
    val role = if (emission is Emission.Note) Seq3FontRole.NOTE else Seq3FontRole.LABEL
    val lines = wrapLines(text, tm, role, maxLines.coerceAtLeast(1))
    val labelWidth = lines.maxOfOrNull { tm.width(role, it) } ?: 0.0
    val badgeWidth = if (repeatCount > 1) tm.width(Seq3FontRole.BADGE, badgeText(repeatCount)) + 2 * BADGE_PAD_H else 0.0
    return RowRequirement(lines, labelWidth, badgeWidth)
}

private fun wrapLines(text: String, tm: Seq3TextMetrics, role: Seq3FontRole, maxLines: Int): List<String> {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return listOf("")
    if (maxLines <= 1) return listOf(ellipsize(text.trim(), tm, role, LABEL_ELLIPSIZE_BUDGET))
    val lines = mutableListOf<String>()
    var current = StringBuilder()
    var idx = 0
    while (idx < words.size && lines.size < maxLines) {
        val candidate = if (current.isEmpty()) words[idx] else "$current ${words[idx]}"
        if (current.isEmpty() || tm.width(role, candidate) <= LABEL_ELLIPSIZE_BUDGET) {
            current = StringBuilder(candidate)
            idx++
        } else {
            lines += current.toString()
            current = StringBuilder()
        }
    }
    if (current.isNotEmpty() && lines.size < maxLines) lines += current.toString()
    if (idx < words.size && lines.isNotEmpty()) lines[lines.lastIndex] = ellipsize("${lines.last()} …", tm, role, LABEL_ELLIPSIZE_BUDGET)
    return lines.ifEmpty { listOf("") }
}

// Deliberately generous — real width clamping happens via column-gap widening (solveGaps), not by
// truncating the label text itself; this budget only stops a single pathological label (megabytes
// of text) from producing an unbounded line.
private const val LABEL_ELLIPSIZE_BUDGET = 100_000.0

private fun ellipsize(text: String, tm: Seq3TextMetrics, role: Seq3FontRole, maxWidth: Double): String {
    if (tm.width(role, text) <= maxWidth) return text
    var end = text.length
    while (end > 0 && tm.width(role, text.substring(0, end) + ELLIPSIS) > maxWidth) end--
    return if (end <= 0) ELLIPSIS else text.substring(0, end) + ELLIPSIS
}

// ── Column-gap solving ──────────────────────────────────────────────────────────────────────
//
// Widen-only, never-shrink adaptation of `diagram/SeqDiagramRenderer.kt:517-569`'s
// `solveColumnGaps` — see this file's header. [rightExtra] plays the role that file's
// `lastColumnSelfExtra` did: a self-loop/stub/note anchored on the RIGHTMOST lifeline has no gap to
// widen, so its own required width instead grows the canvas directly.

private class GapSolveResult(val gaps: DoubleArray, val rightExtra: Double)

private fun solveGaps(
    emissions: List<Emission>,
    requirements: List<RowRequirement>,
    lifelineIndex: Map<String, Int>,
    headerWidths: List<Double>,
): GapSolveResult {
    val n = headerWidths.size
    val gaps = DoubleArray((n - 1).coerceAtLeast(0)) { COLUMN_GAP }
    var rightExtra = 0.0
    emissions.forEachIndexed { i, emission ->
        val req = requirements[i]
        when (emission) {
            is Emission.Arrow -> {
                val a = lifelineIndex[emission.fromLifelineId] ?: return@forEachIndexed
                val b = lifelineIndex[emission.toLifelineId] ?: return@forEachIndexed
                widenSpan(gaps, headerWidths, min(a, b), max(a, b), req.labelWidth + req.badgeWidth + 2 * LABEL_PAD + ARROWHEAD_LEN)
            }
            is Emission.Self -> {
                val c = lifelineIndex[emission.fromLifelineId] ?: return@forEachIndexed
                val need = SELF_LOOP_W + LABEL_PAD + req.labelWidth + req.badgeWidth
                rightExtra = widenSingle(gaps, headerWidths, c, need, rightExtra)
            }
            is Emission.Stub -> {
                val c = lifelineIndex[emission.fromLifelineId] ?: return@forEachIndexed
                val need = STUB_W + LABEL_PAD + req.labelWidth + 2 * PILL_PAD_H
                rightExtra = widenSingle(gaps, headerWidths, c, need, rightExtra)
            }
            is Emission.Note -> {
                val c = lifelineIndex[emission.fromLifelineId] ?: return@forEachIndexed
                val need = NOTE_PAD + max(NOTE_ROW_W, req.labelWidth + 2 * NOTE_PAD)
                rightExtra = widenSingle(gaps, headerWidths, c, need, rightExtra)
            }
            is Emission.Elision -> Unit
        }
    }
    for (j in gaps.indices) gaps[j] = gaps[j].coerceAtMost(COLUMN_GAP_MAX)
    return GapSolveResult(gaps, rightExtra)
}

private fun widenSpan(gaps: DoubleArray, headerWidths: List<Double>, a: Int, b: Int, required: Double) {
    if (a == b) return
    val k = b - a
    var fixed = headerWidths[a] / 2 + headerWidths[b] / 2
    for (c in a + 1 until b) fixed += headerWidths[c]
    var currentSum = 0.0
    for (j in a until b) currentSum += gaps[j]
    val deficit = required - fixed - currentSum
    if (deficit > 0) {
        val share = deficit / k
        for (offset in 0 until k) gaps[a + offset] += share
    }
}

private fun widenSingle(gaps: DoubleArray, headerWidths: List<Double>, c: Int, need: Double, rightExtra: Double): Double {
    val n = headerWidths.size
    return if (c == n - 1) {
        max(rightExtra, need)
    } else {
        val required = need - headerWidths[c] / 2
        if (required > gaps[c]) gaps[c] = required
        rightExtra
    }
}

// ── Column x-positions ──────────────────────────────────────────────────────────────────────

private class ColumnPlacement(val lefts: DoubleArray, val centers: DoubleArray, val contentRight: Double) {
    operator fun component1() = lefts

    operator fun component2() = centers

    operator fun component3() = contentRight
}

private fun placeColumns(headerWidths: List<Double>, gaps: DoubleArray): ColumnPlacement {
    val n = headerWidths.size
    val lefts = DoubleArray(n)
    val centers = DoubleArray(n)
    var cursor = MARGIN
    for (i in 0 until n) {
        lefts[i] = cursor
        centers[i] = cursor + headerWidths[i] / 2
        val gap = gaps.getOrElse(i) { COLUMN_GAP }
        cursor += headerWidths[i] + gap
    }
    val trailingGap = gaps.getOrElse(n - 1) { COLUMN_GAP }
    val contentRight = if (n > 0) cursor - trailingGap else MARGIN
    return ColumnPlacement(lefts, centers, contentRight)
}

// ── Row y-positions + geometry construction ─────────────────────────────────────────────────

private class RowBuildResult(
    val rows: List<Seq3RowGeometry>,
    val firstRowIndex: Map<String, Int>,
    val lastRowIndex: Map<String, Int>,
    val bottomY: Double,
    val rightExtra: Double,
)

private fun buildRows(
    emissions: List<Emission>,
    requirements: List<RowRequirement>,
    lifelineIndex: Map<String, Int>,
    centers: DoubleArray,
    headerHeight: Double,
): RowBuildResult {
    val rows = mutableListOf<Seq3RowGeometry>()
    val first = HashMap<String, Int>()
    val last = HashMap<String, Int>()
    var y = MARGIN + headerHeight + HEADER_TO_ROWS_GAP
    var rightExtra = 0.0
    emissions.forEachIndexed { i, emission ->
        val req = requirements[i]
        val built = buildRow(emission, req, lifelineIndex, centers, y) ?: return@forEachIndexed
        first.getOrPut(emission.messageId) { rows.size }
        rows += built.geometry
        last[emission.messageId] = rows.lastIndex
        y += built.pitch
        rightExtra = max(rightExtra, built.rightEdge)
    }
    return RowBuildResult(rows, first, last, y, rightExtra)
}

private class BuiltRow(val geometry: Seq3RowGeometry, val pitch: Double, val rightEdge: Double)

private fun buildRow(emission: Emission, req: RowRequirement, lifelineIndex: Map<String, Int>, centers: DoubleArray, y: Double): BuiltRow? =
    when (emission) {
        is Emission.Arrow -> buildArrowRow(emission, req, lifelineIndex, centers, y)
        is Emission.Self -> buildSelfRow(emission, req, lifelineIndex, centers, y)
        is Emission.Stub -> buildStubRow(emission, req, lifelineIndex, centers, y)
        is Emission.Note -> buildNoteRow(emission, req, lifelineIndex, centers, y)
        is Emission.Elision -> buildElisionRow(emission, lifelineIndex, centers, y)
    }

private fun buildArrowRow(e: Emission.Arrow, req: RowRequirement, lifelineIndex: Map<String, Int>, centers: DoubleArray, y: Double): BuiltRow? {
    val fromIdx = lifelineIndex[e.fromLifelineId] ?: return null
    val toIdx = lifelineIndex[e.toLifelineId] ?: return null
    val fromX = centers[fromIdx]
    val toX = centers[toIdx]
    val centerX = (fromX + toX) / 2
    val labelBox = Seq3Box(centerX - req.labelWidth / 2, y - ROW_H / 2, req.labelWidth, ROW_H / 2)
    val badgeBox = if (e.repeatCount > 1) Seq3Box(labelBox.x + labelBox.width + BADGE_PAD_H, labelBox.y, req.badgeWidth, BADGE_H) else null
    val arrow = Seq3ArrowRow(e.messageId, y, e.entryId, e.kind, e.fromLifelineId, e.toLifelineId, fromX, toX, e.label, labelBox, e.repeatCount, badgeBox)
    return BuiltRow(arrow, ROW_H, max(fromX, toX))
}

private fun buildSelfRow(e: Emission.Self, req: RowRequirement, lifelineIndex: Map<String, Int>, centers: DoubleArray, y: Double): BuiltRow? {
    val idx = lifelineIndex[e.fromLifelineId] ?: return null
    val x = centers[idx]
    val loopH = max(SELF_EXTRA, ROW_H / 2)
    val labelBox = Seq3Box(x + SELF_LOOP_W + LABEL_PAD, y, req.labelWidth, loopH)
    val badgeBox = if (e.repeatCount > 1) Seq3Box(labelBox.x + labelBox.width + BADGE_PAD_H, y, req.badgeWidth, BADGE_H) else null
    val row = Seq3SelfLoopRow(e.messageId, y, e.entryId, e.fromLifelineId, x, y + loopH, SELF_LOOP_W, e.label, labelBox, e.repeatCount, badgeBox)
    val rightEdge = x + SELF_LOOP_W + LABEL_PAD + req.labelWidth + (badgeBox?.width ?: 0.0)
    return BuiltRow(row, ROW_H + loopH, rightEdge)
}

private fun buildStubRow(e: Emission.Stub, req: RowRequirement, lifelineIndex: Map<String, Int>, centers: DoubleArray, y: Double): BuiltRow? {
    val idx = lifelineIndex[e.fromLifelineId] ?: return null
    val fromX = centers[idx]
    val stubEndX = fromX + STUB_W
    val pill = Seq3Box(stubEndX + LABEL_PAD, y - PILL_H / 2, req.labelWidth.coerceAtLeast(1.0) + 2 * PILL_PAD_H, PILL_H)
    val labelBox = Seq3Box(pill.x, y - ROW_H / 2, req.labelWidth, ROW_H / 2)
    val row = Seq3UnresolvedStubRow(e.messageId, y, e.entryId, e.fromLifelineId, fromX, stubEndX, e.label, labelBox, pill, e.repeatCount)
    return BuiltRow(row, ROW_H, pill.x + pill.width)
}

private fun buildNoteRow(e: Emission.Note, req: RowRequirement, lifelineIndex: Map<String, Int>, centers: DoubleArray, y: Double): BuiltRow? {
    val idx = lifelineIndex[e.fromLifelineId] ?: return null
    val cx = centers[idx]
    val width = max(NOTE_ROW_W, req.labelWidth + 2 * NOTE_PAD)
    val height = req.lines.size * NOTE_LINE_H + 2 * NOTE_PAD
    val box = Seq3Box(cx - width / 2, y - ROW_H / 2, width, height.coerceAtLeast(ROW_H / 2))
    val row = Seq3MessageNoteRow(e.messageId, y, e.entryId, e.fromLifelineId, box, req.lines)
    return BuiltRow(row, ROW_H, box.x + box.width)
}

private fun buildElisionRow(e: Emission.Elision, lifelineIndex: Map<String, Int>, centers: DoubleArray, y: Double): BuiltRow? {
    val idx = lifelineIndex[e.fromLifelineId] ?: return null
    val x = centers[idx]
    val box = Seq3Box(x - ELISION_BOX_W / 2, y - ROW_H / 4, ELISION_BOX_W, ROW_H / 2)
    val row = Seq3ElisionRow(e.messageId, y, e.fromLifelineId, e.count, box)
    return BuiltRow(row, ROW_H / 2, box.x + box.width)
}

// ── Fragments (correctly nested) ────────────────────────────────────────────────────────────
//
// Clamp-to-parent nesting, the same algorithm as `diagram.DiagramEmitters.normalizeFramesForNesting`
// (also re-adapted in Seq3Emitters.kt's own `normalizedBrackets`) — kept a THIRD time here rather
// than shared because this one operates on row Y-ranges instead of emission-index ranges; the
// invariant (never let a child's clamped range escape its parent's) is identical.

private fun layoutFragments(
    fragments: List<Seq3Fragment>,
    firstRowIndex: Map<String, Int>,
    lastRowIndex: Map<String, Int>,
    rows: List<Seq3RowGeometry>,
): List<Seq3FragmentBox> {
    if (fragments.isEmpty() || rows.isEmpty()) return emptyList()

    data class Bounds(val fragment: Seq3Fragment, val range: IntRange)

    val withBounds = fragments.mapNotNull { f ->
        val starts = f.messageIds.mapNotNull { firstRowIndex[it] }
        val ends = f.messageIds.mapNotNull { lastRowIndex[it] }
        if (starts.isEmpty() || ends.isEmpty()) null else Bounds(f, starts.min()..ends.max())
    }
    if (withBounds.isEmpty()) return emptyList()
    val sorted = withBounds.sortedWith(compareBy({ it.range.first }, { -it.range.last }))
    val stack = ArrayDeque<Pair<Bounds, Int>>() // (bounds, depth) with the CLAMPED end already applied
    val result = mutableListOf<Seq3FragmentBox>()
    sorted.forEach { bounds ->
        while (stack.isNotEmpty() && stack.last().first.range.last < bounds.range.first) stack.removeLast()
        val parent = stack.lastOrNull()
        val clampedEnd = parent?.let { min(bounds.range.last, it.first.range.last) } ?: bounds.range.last
        val depth = stack.size
        val clamped = Bounds(bounds.fragment, bounds.range.first..clampedEnd)
        stack.addLast(clamped to depth)
        result += fragmentBoxFrom(clamped.fragment, clamped.range, depth, rows)
    }
    return result
}

private fun fragmentBoxFrom(fragment: Seq3Fragment, range: IntRange, depth: Int, rows: List<Seq3RowGeometry>): Seq3FragmentBox {
    val spanned = range.mapNotNull { rows.getOrNull(it) }
    val xs = spanned.flatMap { rowXExtent(it) }
    val inset = depth * FRAGMENT_INSET_PER_DEPTH
    val left = (xs.minOrNull() ?: 0.0) - FRAGMENT_PAD + inset
    val right = (xs.maxOrNull() ?: 0.0) + FRAGMENT_PAD - inset
    val top = (spanned.firstOrNull()?.y ?: 0.0) - ROW_H / 2 - FRAGMENT_LABEL_H
    val bottom = (spanned.lastOrNull()?.y ?: 0.0) + ROW_H / 2
    val label = fragment.label.ifBlank { fragment.kind.name.lowercase() }
    return Seq3FragmentBox(fragment.id, fragment.kind, label, Seq3Box(left, top, max(1.0, right - left), max(1.0, bottom - top)), depth)
}

private fun rowXExtent(row: Seq3RowGeometry): List<Double> = when (row) {
    is Seq3ArrowRow -> listOf(row.fromX, row.toX)
    is Seq3SelfLoopRow -> listOf(row.x, row.x + row.loopWidth)
    is Seq3UnresolvedStubRow -> listOf(row.fromX, row.stubEndX)
    is Seq3MessageNoteRow -> listOf(row.box.x, row.box.x + row.box.width)
    is Seq3ElisionRow -> listOf(row.box.x, row.box.x + row.box.width)
}

// ── Notes (design spec §06 "Note" verb — spans a selection of messages) ────────────────────────

private fun layoutNotes(
    notes: List<Seq3Note>,
    lastRowIndex: Map<String, Int>,
    rows: List<Seq3RowGeometry>,
    centers: DoubleArray,
    tm: Seq3TextMetrics,
): List<Seq3NoteBox> {
    if (notes.isEmpty()) return emptyList()
    return notes.mapNotNull { note ->
        val anchorIdx = note.messageIds.mapNotNull { lastRowIndex[it] }.maxOrNull() ?: return@mapNotNull null
        val anchorRow = rows.getOrNull(anchorIdx) ?: return@mapNotNull null
        val touchedX = note.messageIds.mapNotNull { lastRowIndex[it] }.flatMap { rowXExtent(rows[it]) }
        val cx = touchedX.average().takeIf { !it.isNaN() } ?: centers.firstOrNull() ?: 0.0
        val width = max(NOTE_ROW_W, tm.width(Seq3FontRole.NOTE, note.text) + 2 * NOTE_PAD)
        val box = Seq3Box(cx - width / 2, anchorRow.y + ROW_H / 2, width, ROW_H / 2 + NOTE_PAD)
        Seq3NoteBox(note.id, box, note.text)
    }
}
