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

    // User-observed correction: a delay's label used to share NOTE's 11pt, which read as too
    // small/easy to miss floating alone in an otherwise-empty band — bumped to LIFELINE's size,
    // the largest role already in use, rather than inventing a new number.
    DELAY(13.0),
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
    /** The RAW, unshortened [Seq3Lifeline.name] — kept distinct from [labelLines] because a
     *  rename affordance (Seq3Canvas's double-click-to-rename) must seed its editor from the real
     *  name, never from a display-segments-shortened or wrapped rendering of it. */
    val label: String,
    /** [label] resolved through [seq3DisplayName] (per-lifeline override, else the document
     *  default) and wrapped through [seq3WrapDisplayName] against this column's own [header]
     *  width — what a renderer actually DRAWS. Always at least one entry, even for an empty name
     *  (matches [seq3WrapDisplayName]'s own "never empty" contract). */
    val labelLines: List<String>,
    /** [Seq3Lifeline.kind] carried onto the column so a renderer (raster or Compose) can pick the
     *  actor-glyph-vs-participant-chip paint path without a second lookup back into the source
     *  [Seq3Document] — mirrors why [label]/[labelLines] are duplicated here rather than re-read. */
    val kind: Seq3LifelineKind,
    val centerX: Double,
    /** The drawn NAME box only — for an [Seq3LifelineKind.ACTOR] column this sits BELOW the
     *  reserved stick-figure band (see [layoutSeq3]'s `actorReserve`), not at [lifelineTop]'s own
     *  margin; both renderers infer the glyph's vertical band as the gap above [header] and below
     *  the shared top margin, so no separate glyph box is threaded through here. */
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

    /** WP10 (item 7): the chronological value this row's [Emission] carried — [Emission
     *  .timestampMillis]'s own doc explains where it comes from. Carried on every row type (not
     *  just the ones whose [label][Seq3ArrowRow.label]-equivalent field is actually prefixed with
     *  it today), on the base class rather than duplicated per-subtype, matching how
     *  [messageId]/[y]/[occurrenceEntryId] are already shared here — a future consumer (e.g. WP11's
     *  time-gap markers) can read it off any row without a `when` dispatch. */
    abstract val timestampMillis: Long?

    /** Display counterpart of [timestampMillis] — see `Seq3LabelSummary.seq3DisplayTimestamp` for
     *  how a renderer turns this (or [timestampMillis]) into what's actually drawn. Empty, never
     *  null, so every subtype can default it the same way a missing raw string already defaults
     *  elsewhere in this package (e.g. [Seq3Message.manualRawTimestamp]). */
    abstract val rawTimestamp: String
}

data class Seq3ArrowRow(
    override val messageId: String,
    override val y: Double,
    override val occurrenceEntryId: Int?,
    override val timestampMillis: Long?,
    override val rawTimestamp: String,
    // never SELF/NOTE — those get their own row types below
    val kind: Seq3Kind,
    val fromLifelineId: String,
    val toLifelineId: String,
    val fromX: Double,
    val toX: Double,
    /** Already `[#n] [ts] label` prefixed when the document has either toggle on (WP10) — measured
     *  and drawn as exactly this string, never the bare [Seq3Message.labelTemplate]/occurrence
     *  substitution; see [prefixEmissionLabels]'s own doc for why prefixing happens before
     *  measurement, not after. */
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
    override val timestampMillis: Long?,
    override val rawTimestamp: String,
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
    override val timestampMillis: Long?,
    override val rawTimestamp: String,
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
    override val timestampMillis: Long?,
    override val rawTimestamp: String,
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
    override val timestampMillis: Long?,
    override val rawTimestamp: String,
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
    /** Carried through from [Seq3Fragment.hideKindLabel] (WP12) purely so the canvas overlay can
     *  decide whether to prefix [label] with the kind word — see that field's own doc. This layout
     *  box's own geometry is unaffected either way; the raster/Mermaid/PlantUML outputs always used
     *  the bare [label], never a "$kind: $label" prefix, so this field has nothing to do there. */
    val hideKindLabel: Boolean = false,
)

data class Seq3NoteBox(val noteId: String, val box: Seq3Box, val text: String)

/** A [Seq3Delay]'s drawn geometry (WP11) — a labelled band spanning the full diagram width,
 *  anchored right after its [Seq3Delay.afterMessageId]'s own drawn row (the exact occurrence when
 *  [Seq3Delay.afterOccurrenceEntryId] resolves, else the message's last one). [box] is the FULL
 *  reserved band (not just a label box, unlike [Seq3NoteBox]'s tighter-fit box) — a renderer
 *  centers the label inside it and, per [seq3LifelineSegments], switches every lifeline crossing
 *  it to a dotted pattern for the band's height — see `buildRows`' own doc for where [box] comes
 *  from. */
data class Seq3DelayBox(val delayId: String, val label: String, val box: Seq3Box)

/** One vertical run of a lifeline's dashed guide line: the ordinary dash pattern outside a delay
 *  ([isDotted] false), or a denser dotted pattern for the height of one it crosses ([isDotted]
 *  true) — user-observed correction mirroring PlantUML's own `...` convention, where a lifeline
 *  switches from its usual dashes to closely-spaced dots for the width of a delay marker, then
 *  reverts. See [seq3LifelineSegments]. */
data class Seq3LifelineSegment(val fromY: Double, val toY: Double, val isDotted: Boolean)

/**
 * Splits one lifeline's full vertical extent ([top]..[bottom]) into alternating
 * [Seq3LifelineSegment]s around every delay it crosses. Every [Seq3DelayBox.box] already spans
 * the FULL diagram width (that struct's own doc), so every lifeline crosses every delay
 * identically — this needs no per-column filtering, just the same [delays] list for every column.
 * A delay whose band falls entirely outside [top]..[bottom] (shouldn't happen — every lifeline
 * spans the whole canvas height — but a defensive clamp costs nothing) contributes no segment.
 * Delays are sorted by y first so out-of-order document.delays or overlapping bands (two delays
 * anchored to nearly the same row) still produce a monotonic, non-overlapping segment list.
 */
fun seq3LifelineSegments(top: Double, bottom: Double, delays: List<Seq3DelayBox>): List<Seq3LifelineSegment> {
    if (delays.isEmpty() || top >= bottom) return listOf(Seq3LifelineSegment(top, bottom, isDotted = false))
    val segments = mutableListOf<Seq3LifelineSegment>()
    var cursor = top
    delays.sortedBy { it.box.y }.forEach { delay ->
        val delayTop = delay.box.y.coerceIn(top, bottom)
        val delayBottom = (delay.box.y + delay.box.height).coerceIn(top, bottom)
        if (delayTop > cursor) segments += Seq3LifelineSegment(cursor, delayTop, isDotted = false)
        if (delayBottom > delayTop) segments += Seq3LifelineSegment(delayTop, delayBottom, isDotted = true)
        cursor = maxOf(cursor, delayBottom)
    }
    if (cursor < bottom) segments += Seq3LifelineSegment(cursor, bottom, isDotted = false)
    return segments
}

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
    /** WP11 time-gap markers — see [Seq3DelayBox]'s own doc. Empty for a document with no delays,
     *  same "absent list, nothing drawn" contract as [fragments]/[notes]. */
    val delays: List<Seq3DelayBox> = emptyList(),
)

// ── Layout constants (all unit-less "1x" values — see this file's header) ──────────────────────

private const val MARGIN = 24.0
private const val HEADER_PAD_H = 12.0
private const val HEADER_PAD_V = 6.0
private const val HEADER_MIN_W = 90.0
private const val HEADER_MAX_W = 200.0
private const val HEADER_TO_ROWS_GAP = 22.0
private const val BOTTOM_MARGIN = 20.0

// Extra vertical band reserved ABOVE every column's name box, ONLY when at least one lifeline in
// the document is Seq3LifelineKind.ACTOR (item 2's "actor glyph"). Reserved for ALL columns, not
// just the actor ones, because headerHeight/lifelineTop is shared geometry (Seq3LifelineColumn's
// own doc) — a participant column simply leaves this band blank while its neighbor's stick figure
// occupies it, so every header chip still bottom-aligns at the same lifelineTop. The exact glyph
// proportions drawn inside this band are each renderer's own paint-time decision (same split as
// e.g. BADGE_ARC/PILL_ARC below, which layout also doesn't dictate) — this constant only reserves
// the SPACE, matching the "geometry decisions belong in this file, painting decisions don't"
// contract this file's header describes.
private const val ACTOR_HEADER_RESERVE = 34.0

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

// Vertical gap between the stub's label (drawn above the dashed line, same as an arrow's label)
// and the "drop on a lifeline" pill (drawn below it) — see buildStubRow's own doc for why these
// two boxes used to share the same origin and overlap (item 10 of the phase-5 post-ship plan).
private const val STUB_LABEL_PILL_GAP = 6.0
private const val BADGE_PAD_H = 6.0
private const val BADGE_H = 16.0
private const val NOTE_ROW_W = 130.0
private const val NOTE_PAD = 6.0
private const val NOTE_LINE_H = 14.0
private const val ELISION_BOX_W = 40.0

private const val FRAGMENT_PAD = 10.0
private const val FRAGMENT_LABEL_H = 16.0
private const val FRAGMENT_INSET_PER_DEPTH = 10.0

// WP11: the vertical band a time-gap marker reserves between the row it follows and whatever
// comes next — tall enough for one centered label line plus a little breathing room above/below,
// mirroring how buildStubRow already grows a row's own pitch to fit an extra element (its pill)
// rather than overlapping it. Unlike every other row type, a delay is inserted BETWEEN two
// emissions rather than replacing one, so this constant is added to the y-cursor directly in
// buildRows rather than folded into a BuiltRow's own pitch.
private const val DELAY_BAND_H = 34.0

// The fixed intrusion a fragment box claims above its first row (ROW_H/2 + FRAGMENT_LABEL_H) — see
// fragmentBoxFrom's own doc. buildRows widens the header-to-rows gap by at least this much whenever
// the document has any fragment, so a fragment spanning the FIRST message can never claim space
// that belongs to the header band (item 5's regression — see this file's header/the plan doc).
private const val FRAGMENT_TOP_RESERVE = ROW_H / 2 + FRAGMENT_LABEL_H + 6.0

private const val ELLIPSIS = "…"

// ── Public entry point ──────────────────────────────────────────────────────────────────────

/**
 * Lays out [doc] into unit-less canvas geometry. Never throws: an empty document (no lifelines, or
 * no visible messages) produces a minimal/empty [Seq3Layout] rather than failing, matching the rest
 * of this package's "never throw on bad/degenerate input" posture (see Seq3Generator.kt's header).
 */
fun layoutSeq3(doc: Seq3Document, opts: Seq3LayoutOptions): Seq3Layout {
    val lifelinesSorted = doc.lifelines
        .filter { it.visibility == Seq3Visibility.VISIBLE }
        .sortedBy { it.ordinal }
    val lifelineIndex = lifelinesSorted.withIndex().associate { (i, l) -> l.id to i }
    // A message whose source or target lifeline is hidden cannot be drawn without inventing a
    // dangling endpoint. Keep it in the durable queue, but omit it from the canvas until all of
    // its required lifelines are visible again.
    val visibleMessages = doc.messages.filter {
        it.visibility == Seq3Visibility.VISIBLE &&
            it.fromLifelineId in lifelineIndex &&
            (it.toLifelineId == null || it.toLifelineId in lifelineIndex)
    }
    val crossingCount = computeCrossingCount(visibleMessages, lifelineIndex)
    if (lifelinesSorted.isEmpty()) {
        return Seq3Layout(0.0, 0.0, emptyList(), emptyList(), emptyList(), emptyList(), crossingCount)
    }

    val tm = opts.textMetrics
    // Item 2: resolve each lifeline's display name (per-lifeline displaySegments override, else
    // the document-wide default — see seq3DisplayName) and wrap it dot-boundary-first against the
    // header's own available text width, exactly like Seq3Layout already clamps a single-line
    // width to [HEADER_MIN_W, HEADER_MAX_W]. headerWidths is measured from the WIDEST resulting
    // line, not the raw (possibly much longer) resolved name, so a long dotted name widens its
    // column only as far as its longest wrapped line actually needs.
    val displayNames = lifelinesSorted.map { l -> seq3DisplayName(l.name, l.displaySegments, doc.lifelineDisplaySegments) }
    val labelLinesPerLifeline = displayNames.map { name -> seq3WrapDisplayName(name, HEADER_MAX_W - 2 * HEADER_PAD_H, tm) }
    val headerWidths = labelLinesPerLifeline.map { lines ->
        val widest = withMeasurementSlack(lines.maxOfOrNull { tm.width(Seq3FontRole.LIFELINE, it) } ?: 0.0)
        (widest + 2 * HEADER_PAD_H).coerceIn(HEADER_MIN_W, HEADER_MAX_W)
    }
    // headerHeight is ONE shared value across every column (Seq3LifelineColumn's own doc:
    // lifelineTop = MARGIN + headerHeight is shared geometry) — computed from the MAXIMUM line
    // count so no column's chip clips its own wrapped text, and every chip still bottom-aligns.
    val maxLabelLineCount = labelLinesPerLifeline.maxOfOrNull { it.size } ?: 1
    val nameBoxHeight = tm.lineHeight(Seq3FontRole.LIFELINE) * maxLabelLineCount + 2 * HEADER_PAD_V
    // Item 2 (actor glyph): reserve extra height for ALL columns the moment ANY lifeline is an
    // ACTOR — see ACTOR_HEADER_RESERVE's own doc for why this is document-wide, not per-column.
    val hasActorLifeline = lifelinesSorted.any { it.kind == Seq3LifelineKind.ACTOR }
    val actorReserve = if (hasActorLifeline) ACTOR_HEADER_RESERVE else 0.0
    val headerHeight = nameBoxHeight + actorReserve

    // Item 5 (phase-5 round-2 post-ship plan): the canvas must draw every arrow at its true
    // chronological position, interleaved with every other lifeline's activity exactly as it
    // happened — grouping a message's repeated occurrences together (`expandForLayout`, per
    // message) exists ONLY so the queue panel is convenient to edit; it must never dictate canvas
    // row order. A timestamp override is authoritative for every emission of that message. For an
    // untimestamped authored message, interpolate a stable fallback between its nearest timestamped
    // neighbours so inserting it before/after a row also places it there on the canvas.
    // Task 0 (round-2 corrections plan, WP11 prerequisite): the interpolation + comparator below
    // used to live here as a private, inline copy — Seq3Emitters.kt never sorted its own emissions
    // at all, so the exported Mermaid/PlantUML text could draw a different row order (and therefore
    // a different `[#n]` call number) than this canvas/PNG geometry for the same document. Both
    // files now call through `seq3ChronologicalOrder`/`seq3ChronologicalFallbacks`
    // (Seq3LabelSummary.kt) so the two can never again quietly disagree about order.
    val chronologicalEmissions = seq3ChronologicalOrder(
        doc,
        visibleMessages.flatMap(::expandForLayout),
        messageIdOf = { emission -> emission.messageId },
        timestampMillisOf = { emission -> emission.timestampMillis },
        entryIdOf = { emission -> emission.entryId },
    )
    // WP10 (item 7): number/timestamp-prefix each call's label BEFORE measurement — see
    // prefixEmissionLabels' own doc for why doing this after measureRequirement would reintroduce
    // WP9's clipping bug in a worse form (a toggle the user can flip live, not just a one-off typo).
    val emissions = prefixEmissionLabels(chronologicalEmissions, doc.showSequenceNumbers, doc.showTimestamps)
    val requirements = emissions.map { measureRequirement(it, tm, opts.maxLabelLines) }
    val gapSolve = solveGaps(emissions, requirements, lifelineIndex, headerWidths)

    val (lefts, centers, contentRight) = placeColumns(headerWidths, gapSolve.gaps, gapSolve.leftExtra)
    // WP11: every visible delay is resolved to the exact EMISSION INDEX it draws after, then handed
    // to buildRows keyed by that index, which is the one place that already knows each row's final
    // position — see buildRows' own doc for why the reservation happens there rather than as a
    // post-pass over already-placed rows.
    //
    // User-observed correction: a delay used to be grouped purely by `afterMessageId` and buildRows
    // always placed it after that message's LAST chronological row — so right-clicking the FIRST of
    // several repeated occurrences of a message and choosing "Insert delay after this" still landed
    // the delay after the LAST one. `afterOccurrenceEntryId` (null for every delay created before
    // that field existed) now picks out the exact occurrence's row when set, via the same
    // `entryId`-keyed lookup fragment/note boundary resolution already relies on elsewhere; falling
    // back to "after the last occurrence of the message" — this field's own pre-existing default —
    // when it's null or names an occurrence no longer emitted (hidden, or the row simply doesn't
    // repeat that many times any more).
    val delaysByRowIndex = HashMap<Int, MutableList<Seq3Delay>>()
    doc.delays.filter { it.visibility == Seq3Visibility.VISIBLE }.forEach { delay ->
        val candidateIndices = emissions.withIndex().filter { (_, e) -> e.messageId == delay.afterMessageId }.map { it.index }
        if (candidateIndices.isEmpty()) return@forEach
        val targetIndex = delay.afterOccurrenceEntryId
            ?.let { entryId -> candidateIndices.firstOrNull { i -> emissions[i].entryId == entryId } }
            ?: candidateIndices.last()
        delaysByRowIndex.getOrPut(targetIndex) { mutableListOf() } += delay
    }
    val bandLeft = lefts.firstOrNull() ?: MARGIN
    val rowBuild = buildRows(
        emissions,
        requirements,
        lifelineIndex,
        centers,
        headerHeight,
        doc.fragments.any { it.visibility == Seq3Visibility.VISIBLE },
        delaysByRowIndex,
        bandLeft,
        contentRight,
    )

    val fragments = layoutFragments(doc.fragments, rowBuild.firstRowIndex, rowBuild.lastRowIndex, rowBuild.rows, headerHeight)
    val notes = layoutNotes(doc.notes, rowBuild.lastRowIndex, rowBuild.rows, centers, tm)

    val rightEdge = maxOf(contentRight, gapSolve.rightExtra + (centers.lastOrNull() ?: 0.0), rowBuild.rightExtra)
    val noteRight = notes.maxOfOrNull { it.box.x + it.box.width } ?: 0.0
    val noteBottom = notes.maxOfOrNull { it.box.y + it.box.height } ?: 0.0
    val width = maxOf(rightEdge, noteRight) + MARGIN
    val height = maxOf(rowBuild.bottomY, noteBottom) + BOTTOM_MARGIN

    val lifelineColumns = lifelinesSorted.mapIndexed { i, l ->
        Seq3LifelineColumn(
            lifelineId = l.id,
            label = l.name,
            labelLines = labelLinesPerLifeline[i],
            kind = l.kind,
            centerX = centers[i],
            // The NAME box sits below the actor-glyph reserve (see Seq3LifelineColumn's own doc):
            // MARGIN + actorReserve, not just MARGIN, so a participant column also leaves the same
            // blank band an actor column's stick figure occupies — that's what keeps every chip's
            // bottom edge (and therefore lifelineTop below) aligned regardless of kind.
            header = Seq3Box(lefts[i], MARGIN + actorReserve, headerWidths[i], nameBoxHeight),
            lifelineTop = MARGIN + headerHeight,
            lifelineBottom = rowBuild.bottomY,
        )
    }
    return Seq3Layout(width, height, lifelineColumns, rowBuild.rows, fragments, notes, crossingCount, rowBuild.delayBoxes)
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

    /** True chronological position (item 5) — the sort key [layoutSeq3] reorders the flat emission
     *  list by, AFTER the per-message `expandForLayout` grouping, so canvas row order reflects the
     *  real log clock rather than which message it belongs to. */
    abstract val timestampMillis: Long?

    /** The real log line this emission jumps to on click — `null` only for [Elision] (which has no
     *  occurrence of its own); kept on the base class purely as this sort's stable tiebreak. */
    abstract val entryId: Int?

    data class Arrow(
        override val messageId: String,
        override val fromLifelineId: String,
        val toLifelineId: String,
        val kind: Seq3Kind,
        val label: String,
        val repeatCount: Int,
        override val entryId: Int?,
        override val timestampMillis: Long?,
        val rawTimestamp: String,
    ) : Emission()

    data class Self(
        override val messageId: String,
        override val fromLifelineId: String,
        val label: String,
        val repeatCount: Int,
        override val entryId: Int?,
        override val timestampMillis: Long?,
        val rawTimestamp: String,
    ) : Emission()

    data class Stub(
        override val messageId: String,
        override val fromLifelineId: String,
        val label: String,
        val repeatCount: Int,
        override val entryId: Int?,
        override val timestampMillis: Long?,
        val rawTimestamp: String,
    ) : Emission()

    data class Note(
        override val messageId: String,
        override val fromLifelineId: String,
        val text: String,
        override val entryId: Int?,
        override val timestampMillis: Long?,
        val rawTimestamp: String,
    ) : Emission()

    /** The "N more" marker between a [Seq3Repeat.FIRST_LAST] message's first and last drawn rows —
     *  represents the elided middle, so it has no occurrence (hence [entryId] is `null`) of its own.
     *  [timestampMillis] is seeded from the FIRST occurrence's own timestamp (see
     *  [firstLastEmissions]) so it sorts immediately after that row — a reasonable, defensible
     *  placement, not required to be exact. */
    data class Elision(
        override val messageId: String,
        override val fromLifelineId: String,
        val count: Int,
        override val timestampMillis: Long?,
        val rawTimestamp: String,
    ) : Emission() {
        override val entryId: Int? get() = null
    }
}

// occurrenceLabel/collapsedRepeatLabel/seq3EmissionTimestamp/seq3EmissionRawTimestamp now live in
// Seq3LabelSummary.kt, shared with Seq3Emitters — see that file's header on why (WP9: the two
// copies of occurrenceLabel had drifted apart once already, the same class of bug round 1 hit with
// arrow styles).

private fun expandForLayout(message: Seq3Message): List<Emission> {
    val visibleOccurrences = message.occurrences.filter { it.visibility == Seq3Visibility.VISIBLE }
    if (message.occurrences.isNotEmpty() && visibleOccurrences.isEmpty()) return emptyList()
    if (message.kind == Seq3Kind.NOTE) {
        val occ = visibleOccurrences.firstOrNull()
        return listOf(
            Emission.Note(message.id, message.fromLifelineId, message.labelTemplate, occ?.entryId, message.primaryTimestampMillis, message.primaryRawTimestamp),
        )
    }
    if (message.toLifelineId == null) {
        val occ = visibleOccurrences.firstOrNull()
        return listOf(
            Emission.Stub(
                message.id,
                message.fromLifelineId,
                message.labelTemplate,
                visibleOccurrences.size.coerceAtLeast(1),
                occ?.entryId,
                message.primaryTimestampMillis,
                message.primaryRawTimestamp,
            ),
        )
    }
    val occurrences = visibleOccurrences
    val isSelf = message.kind == Seq3Kind.SELF

    fun arrow(label: String, count: Int, entryId: Int?, timestampMillis: Long?, rawTimestamp: String): Emission = if (isSelf) {
        Emission.Self(message.id, message.fromLifelineId, label, count, entryId, timestampMillis, rawTimestamp)
    } else {
        Emission.Arrow(message.id, message.fromLifelineId, message.toLifelineId, message.kind, label, count, entryId, timestampMillis, rawTimestamp)
    }
    if (occurrences.isEmpty()) {
        return listOf(arrow(message.labelTemplate, 1, null, message.primaryTimestampMillis, message.primaryRawTimestamp))
    }
    return when (message.repeat) {
        Seq3Repeat.EVERY -> occurrences.map { occ ->
            arrow(
                occurrenceLabel(message, occ),
                1,
                occ.entryId,
                seq3EmissionTimestamp(message, occ.timestampMillis),
                seq3EmissionRawTimestamp(message, occ.rawTimestamp),
            )
        }
        Seq3Repeat.FIRST_LAST -> firstLastEmissions(message, occurrences, ::arrow)
        Seq3Repeat.COLLAPSE_ABOVE -> if (occurrences.size > message.repeatThreshold) {
            listOf(
                arrow(
                    collapsedRepeatLabel(message, occurrences),
                    // COUNT, not "how many rows do I draw" (this branch always draws exactly one) —
                    // read the true pre-trim total (W1a) when generation elided evidence, so the
                    // badge never under-reports how many times this call actually happened.
                    message.totalOccurrenceCount ?: occurrences.size,
                    occurrences.first().entryId,
                    seq3EmissionTimestamp(message, occurrences.first().timestampMillis),
                    seq3EmissionRawTimestamp(message, occurrences.first().rawTimestamp),
                ),
            )
        } else {
            occurrences.map { occ ->
                arrow(
                    occurrenceLabel(message, occ),
                    1,
                    occ.entryId,
                    seq3EmissionTimestamp(message, occ.timestampMillis),
                    seq3EmissionRawTimestamp(message, occ.rawTimestamp),
                )
            }
        }
    }
}

private fun firstLastEmissions(
    message: Seq3Message,
    occurrences: List<Seq3Occurrence>,
    arrow: (String, Int, Int?, Long?, String) -> Emission,
): List<Emission> {
    if (occurrences.size <= 1) {
        val only = occurrences.firstOrNull()
        return listOf(
            arrow(
                occurrenceLabel(message, occurrences.first()),
                1,
                only?.entryId,
                seq3EmissionTimestamp(message, only?.timestampMillis),
                seq3EmissionRawTimestamp(message, only?.rawTimestamp.orEmpty()),
            ),
        )
    }
    // COUNT, not "how many rows do I draw" — this function always draws exactly first + elision +
    // last regardless of list size. Read the true pre-trim total (W1a) when generation elided
    // evidence, so the elision row's own count never under-reports what got dropped.
    val elided = (message.totalOccurrenceCount ?: occurrences.size) - 2
    return buildList {
        add(
            arrow(
                occurrenceLabel(message, occurrences.first()),
                1,
                occurrences.first().entryId,
                seq3EmissionTimestamp(message, occurrences.first().timestampMillis),
                seq3EmissionRawTimestamp(message, occurrences.first().rawTimestamp),
            ),
        )
        if (elided > 0) {
            add(
                Emission.Elision(
                    message.id,
                    message.fromLifelineId,
                    elided,
                    seq3EmissionTimestamp(message, occurrences.first().timestampMillis),
                    seq3EmissionRawTimestamp(message, occurrences.first().rawTimestamp),
                ),
            )
        }
        add(
            arrow(
                occurrenceLabel(message, occurrences.last()),
                1,
                occurrences.last().entryId,
                seq3EmissionTimestamp(message, occurrences.last().timestampMillis),
                seq3EmissionRawTimestamp(message, occurrences.last().rawTimestamp),
            ),
        )
    }
}

// ── WP10 (item 7): inline call numbering / timestamps ───────────────────────────────────────
//
// Runs BEFORE [measureRequirement] (see [layoutSeq3]'s own call site) — measuring the bare label
// and only prefixing it afterward would size every labelBox to a string shorter than what actually
// gets drawn, exactly the class of bug [withMeasurementSlack] exists to guard against, just
// triggered by a toggle instead of a font-rasterizer mismatch. Only [Emission.Arrow]/[Emission
// .Self]/[Emission.Stub] are numbered — those are the rows that are actual CALLS; a [Emission.Note]
// isn't a call (nothing to number) and [Emission.Elision] represents elided rows it itself is not
// one of, so it never consumes a number either. A hidden message/occurrence never reaches this
// list at all ([expandForLayout] already dropped it), so "hidden rows don't consume a number" falls
// out for free rather than needing its own check here. A collapsed [Seq3Repeat.COLLAPSE_ABOVE] row
// above threshold is exactly ONE [Emission.Arrow]/[Emission.Self] at this point (see
// [expandForLayout]'s own COLLAPSE_ABOVE branch), so it takes exactly one number, matching what the
// design brief asks for.
private fun prefixEmissionLabels(emissions: List<Emission>, showSequenceNumbers: Boolean, showTimestamps: Boolean): List<Emission> {
    if (!showSequenceNumbers && !showTimestamps) return emissions
    var callNumber = 0
    return emissions.map { emission ->
        when (emission) {
            is Emission.Arrow -> {
                callNumber++
                emission.copy(
                    label = seq3PrefixedLabel(
                        emission.label,
                        callNumber,
                        emission.rawTimestamp,
                        emission.timestampMillis,
                        showSequenceNumbers,
                        showTimestamps,
                    ),
                )
            }
            is Emission.Self -> {
                callNumber++
                emission.copy(
                    label = seq3PrefixedLabel(
                        emission.label,
                        callNumber,
                        emission.rawTimestamp,
                        emission.timestampMillis,
                        showSequenceNumbers,
                        showTimestamps,
                    ),
                )
            }
            is Emission.Stub -> {
                callNumber++
                emission.copy(
                    label = seq3PrefixedLabel(
                        emission.label,
                        callNumber,
                        emission.rawTimestamp,
                        emission.timestampMillis,
                        showSequenceNumbers,
                        showTimestamps,
                    ),
                )
            }
            is Emission.Note, is Emission.Elision -> emission
        }
    }
}

// ── Label/requirement measurement ───────────────────────────────────────────────────────────

private class RowRequirement(val lines: List<String>, val labelWidth: Double, val badgeWidth: Double)

private fun badgeText(count: Int): String = "×$count"

// Item 9 (WP9 regression fix): a box sized to *exactly* an AWT `FontMetrics.stringWidth` and then
// drawn into by a different rasterizer (Compose/Skia — see Seq3Canvas's SEQ3_LABEL_FONT_SIZE doc)
// has zero margin for the two engines' advance widths disagreeing by even one subpixel, and
// `stringWidth` is itself an integer-rounded value, so exact equality was never actually safe even
// before that font-family split existed. Every measured width that becomes a drawn box's width —
// label boxes, the ×n badge, lifeline headers — goes through this once, so no caller can
// accidentally regress back to a zero-slack box.
private const val LABEL_SLACK_CONSTANT = 2.0
private const val LABEL_SLACK_RATIO = 0.02

private fun withMeasurementSlack(width: Double): Double =
    if (width <= 0.0) width else width + LABEL_SLACK_CONSTANT + width * LABEL_SLACK_RATIO

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
    val labelWidth = withMeasurementSlack(lines.maxOfOrNull { tm.width(role, it) } ?: 0.0)
    val badgeWidth = if (repeatCount > 1) withMeasurementSlack(tm.width(Seq3FontRole.BADGE, badgeText(repeatCount))) + 2 * BADGE_PAD_H else 0.0
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

private class GapSolveResult(val gaps: DoubleArray, val leftExtra: Double, val rightExtra: Double)

private fun solveGaps(
    emissions: List<Emission>,
    requirements: List<RowRequirement>,
    lifelineIndex: Map<String, Int>,
    headerWidths: List<Double>,
): GapSolveResult {
    val n = headerWidths.size
    val gaps = DoubleArray((n - 1).coerceAtLeast(0)) { COLUMN_GAP }
    var leftExtra = 0.0
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
                // WP7 item 2: the stub now draws to the LEFT of its own lifeline (buildStubRow's
                // own doc) — it needs room widened on the lifeline's LEFT side, the mirror image of
                // how a self-loop/note widens to its right. widenLeftSingle is the exact mirror of
                // widenSingle: for the leftmost column (c == 0) there is no gap before it to widen,
                // so the reservation grows `leftExtra` instead, which shifts EVERY column's start
                // (placeColumns) rather than a single gap — the only way to free space before the
                // very first column.
                val c = lifelineIndex[emission.fromLifelineId] ?: return@forEachIndexed
                val need = STUB_W + LABEL_PAD + req.labelWidth + 2 * PILL_PAD_H
                leftExtra = widenLeftSingle(gaps, headerWidths, c, need, leftExtra)
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
    return GapSolveResult(gaps, leftExtra, rightExtra)
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

/** The mirror image of [widenSingle]: widens the gap BEFORE column [c] (between `c-1` and `c`)
 *  instead of after it, so [need] worth of space (measured from column [c]'s own CENTER, same
 *  convention [widenSingle]/[rightExtra] already use) is free to its LEFT. For the leftmost column
 *  there is no gap before it — [leftExtra] plays [rightExtra]'s exact role there, consumed by
 *  [placeColumns] to shift every column's start rather than widen a single gap. */
private fun widenLeftSingle(gaps: DoubleArray, headerWidths: List<Double>, c: Int, need: Double, leftExtra: Double): Double =
    if (c == 0) {
        max(leftExtra, need)
    } else {
        val required = need - headerWidths[c] / 2
        if (required > gaps[c - 1]) gaps[c - 1] = required
        leftExtra
    }

// ── Column x-positions ──────────────────────────────────────────────────────────────────────

private class ColumnPlacement(val lefts: DoubleArray, val centers: DoubleArray, val contentRight: Double) {
    operator fun component1() = lefts

    operator fun component2() = centers

    operator fun component3() = contentRight
}

private fun placeColumns(headerWidths: List<Double>, gaps: DoubleArray, leftExtra: Double = 0.0): ColumnPlacement {
    val n = headerWidths.size
    val lefts = DoubleArray(n)
    val centers = DoubleArray(n)
    // WP7 item 2: an unresolved stub on the LEFTMOST lifeline needs room to its left that no gap
    // can provide (there is no column before it to widen a gap against) — solveGaps' leftExtra
    // covers exactly that by shifting every column's start past the usual MARGIN, the same way
    // rightExtra grows the canvas past the last column without moving anything. This keeps every
    // column's own header width and every gap between columns unchanged; the whole arrangement
    // just starts further right, so a leftmost-column stub can never render at a negative x or get
    // clipped against the canvas edge.
    var cursor = MARGIN + leftExtra
    for (i in 0 until n) {
        lefts[i] = cursor
        centers[i] = cursor + headerWidths[i] / 2
        val gap = gaps.getOrElse(i) { COLUMN_GAP }
        cursor += headerWidths[i] + gap
    }
    val trailingGap = gaps.getOrElse(n - 1) { COLUMN_GAP }
    val contentRight = if (n > 0) cursor - trailingGap else MARGIN + leftExtra
    return ColumnPlacement(lefts, centers, contentRight)
}

// ── Row y-positions + geometry construction ─────────────────────────────────────────────────

private class RowBuildResult(
    val rows: List<Seq3RowGeometry>,
    val firstRowIndex: Map<String, Int>,
    val lastRowIndex: Map<String, Int>,
    val bottomY: Double,
    val rightExtra: Double,
    val delayBoxes: List<Seq3DelayBox>,
)

private fun buildRows(
    emissions: List<Emission>,
    requirements: List<RowRequirement>,
    lifelineIndex: Map<String, Int>,
    centers: DoubleArray,
    headerHeight: Double,
    // Item 5: a document with at least one fragment reserves MORE than the plain
    // HEADER_TO_ROWS_GAP above the first row, because fragmentBoxFrom's top claims ROW_H/2 +
    // FRAGMENT_LABEL_H above whatever row it spans — without this, a fragment grouping the FIRST
    // message intrudes a fixed amount into the header band (see FRAGMENT_TOP_RESERVE's own doc and
    // fragmentBoxFrom's clamp below, which is the second, defensive half of this same fix).
    fragmentsPresent: Boolean,
    // WP11: every VISIBLE delay, keyed by the exact emission INDEX (into the same `emissions` list
    // this function iterates) it draws after — see layoutSeq3's own call site comment for why this
    // is resolved before buildRows rather than as a post-pass.
    delaysByRowIndex: Map<Int, List<Seq3Delay>> = emptyMap(),
    bandLeft: Double = MARGIN,
    bandRight: Double = MARGIN,
): RowBuildResult {
    val rows = mutableListOf<Seq3RowGeometry>()
    val delayBoxes = mutableListOf<Seq3DelayBox>()
    val first = HashMap<String, Int>()
    val last = HashMap<String, Int>()
    val topGap = if (fragmentsPresent) max(HEADER_TO_ROWS_GAP, FRAGMENT_TOP_RESERVE) else HEADER_TO_ROWS_GAP
    var y = MARGIN + headerHeight + topGap
    var rightExtra = 0.0
    emissions.forEachIndexed { i, emission ->
        val req = requirements[i]
        val built = buildRow(emission, req, lifelineIndex, centers, y) ?: return@forEachIndexed
        first.getOrPut(emission.messageId) { rows.size }
        rows += built.geometry
        last[emission.messageId] = rows.lastIndex
        y += built.pitch
        rightExtra = max(rightExtra, built.rightEdge)
        delaysByRowIndex[i]?.forEach { delay ->
            val box = Seq3Box(bandLeft, y, (bandRight - bandLeft).coerceAtLeast(1.0), DELAY_BAND_H)
            delayBoxes += Seq3DelayBox(delay.id, delay.label, box)
            y += DELAY_BAND_H
        }
    }
    return RowBuildResult(rows, first, last, y, rightExtra, delayBoxes)
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
    val arrow = Seq3ArrowRow(
        e.messageId,
        y,
        e.entryId,
        e.timestampMillis,
        e.rawTimestamp,
        e.kind,
        e.fromLifelineId,
        e.toLifelineId,
        fromX,
        toX,
        e.label,
        labelBox,
        e.repeatCount,
        badgeBox,
    )
    return BuiltRow(arrow, ROW_H, max(fromX, toX))
}

private fun buildSelfRow(e: Emission.Self, req: RowRequirement, lifelineIndex: Map<String, Int>, centers: DoubleArray, y: Double): BuiltRow? {
    val idx = lifelineIndex[e.fromLifelineId] ?: return null
    val x = centers[idx]
    val loopH = max(SELF_EXTRA, ROW_H / 2)
    val labelBox = Seq3Box(x + SELF_LOOP_W + LABEL_PAD, y, req.labelWidth, loopH)
    val badgeBox = if (e.repeatCount > 1) Seq3Box(labelBox.x + labelBox.width + BADGE_PAD_H, y, req.badgeWidth, BADGE_H) else null
    val row = Seq3SelfLoopRow(
        e.messageId,
        y,
        e.entryId,
        e.timestampMillis,
        e.rawTimestamp,
        e.fromLifelineId,
        x,
        y + loopH,
        SELF_LOOP_W,
        e.label,
        labelBox,
        e.repeatCount,
        badgeBox,
    )
    val rightEdge = x + SELF_LOOP_W + LABEL_PAD + req.labelWidth + (badgeBox?.width ?: 0.0)
    return BuiltRow(row, ROW_H + loopH, rightEdge)
}

// WP7 item 2: a class that logs a line is usually EXECUTING something it was asked to do, not
// initiating one — so the tag's own lifeline reads as the CALLEE, and an unresolved stub must draw
// with its arrowhead pointing INTO that lifeline, not away from it. The stub therefore extends to
// the LEFT of the tag's column (stubEndX < fromX) rather than to the right as it did before this
// package — dragging its drop pill onto another lifeline resolves THAT lifeline as the caller
// (`from`), reusing the tag's own column as `to` (see Seq3BulkAction.SetCaller). labelBox/pill are
// mirrored the same way: both RIGHT-align at `stubEndX - LABEL_PAD` (their shared edge nearest the
// dashed line) and extend further left, the exact mirror image of the old left-aligned-at-originX
// layout, so the pill still visually "collects" the label text the same way it always did. The
// vertical placement (label above the line, pill below, separated by STUB_LABEL_PILL_GAP so their
// y-ranges never overlap — item 10 of the phase-5 post-ship plan) is unchanged.
private fun buildStubRow(e: Emission.Stub, req: RowRequirement, lifelineIndex: Map<String, Int>, centers: DoubleArray, y: Double): BuiltRow? {
    val idx = lifelineIndex[e.fromLifelineId] ?: return null
    val fromX = centers[idx]
    val stubEndX = fromX - STUB_W
    val rightAlignX = stubEndX - LABEL_PAD
    val pillWidth = req.labelWidth.coerceAtLeast(1.0) + 2 * PILL_PAD_H
    val labelBox = Seq3Box(rightAlignX - req.labelWidth, y - ROW_H / 2, req.labelWidth, ROW_H / 2)
    val pill = Seq3Box(rightAlignX - pillWidth, y + STUB_LABEL_PILL_GAP, pillWidth, PILL_H)
    val row = Seq3UnresolvedStubRow(
        e.messageId,
        y,
        e.entryId,
        e.timestampMillis,
        e.rawTimestamp,
        e.fromLifelineId,
        fromX,
        stubEndX,
        e.label,
        labelBox,
        pill,
        e.repeatCount,
    )
    val pitch = ROW_H / 2 + STUB_LABEL_PILL_GAP + PILL_H + ROW_H / 2
    // Unlike the old right-pointing stub, this row no longer extends past its own lifeline's
    // center on the right — its rightmost touched x is simply fromX (rowXExtent already reports
    // both fromX and the (now leftward) stubEndX for fragment/bounds purposes).
    return BuiltRow(row, pitch, fromX)
}

private fun buildNoteRow(e: Emission.Note, req: RowRequirement, lifelineIndex: Map<String, Int>, centers: DoubleArray, y: Double): BuiltRow? {
    val idx = lifelineIndex[e.fromLifelineId] ?: return null
    val cx = centers[idx]
    val width = max(NOTE_ROW_W, req.labelWidth + 2 * NOTE_PAD)
    val height = req.lines.size * NOTE_LINE_H + 2 * NOTE_PAD
    val box = Seq3Box(cx - width / 2, y - ROW_H / 2, width, height.coerceAtLeast(ROW_H / 2))
    val row = Seq3MessageNoteRow(e.messageId, y, e.entryId, e.timestampMillis, e.rawTimestamp, e.fromLifelineId, box, req.lines)
    return BuiltRow(row, ROW_H, box.x + box.width)
}

private fun buildElisionRow(e: Emission.Elision, lifelineIndex: Map<String, Int>, centers: DoubleArray, y: Double): BuiltRow? {
    val idx = lifelineIndex[e.fromLifelineId] ?: return null
    val x = centers[idx]
    val box = Seq3Box(x - ELISION_BOX_W / 2, y - ROW_H / 4, ELISION_BOX_W, ROW_H / 2)
    val row = Seq3ElisionRow(e.messageId, y, e.timestampMillis, e.rawTimestamp, e.fromLifelineId, e.count, box)
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
    // Item 5's DEFENSIVE half: fragmentBoxFrom clamps its computed `top` to never rise above
    // `MARGIN + headerHeight + 4` regardless of what buildRows already reserved above the first
    // row — see fragmentBoxFrom's own doc for why this is needed even with buildRows' own
    // FRAGMENT_TOP_RESERVE widening (nested nested fragments share the exact same top formula with
    // no extra vertical inset per depth, so the outermost's own reserve doesn't automatically cover
    // every nested box without this second, direct clamp).
    headerHeight: Double,
): List<Seq3FragmentBox> {
    // Item 5 (hidden fragments): skip a HIDDEN fragment's bracket entirely — same "drop the box,
    // keep the row" contract Seq3Fragment.visibility documents, and the same filter hidden
    // lifelines/messages already get earlier in layoutSeq3.
    val visibleFragments = fragments.filter { it.visibility == Seq3Visibility.VISIBLE }
    if (visibleFragments.isEmpty() || rows.isEmpty()) return emptyList()
    val minTop = MARGIN + headerHeight + 4.0

    data class Bounds(val fragment: Seq3Fragment, val range: IntRange)

    val withBounds = visibleFragments.mapNotNull { f ->
        val indices = fragmentRowIndices(f, firstRowIndex, lastRowIndex, rows)
        if (indices.isEmpty()) null else Bounds(f, indices.min()..indices.max())
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
        result += fragmentBoxFrom(clamped.fragment, clamped.range, depth, rows, minTop)
    }
    return result
}

/** Resolves a fragment to the drawn rows it actually references. Ordinary queue-created
 * fragments only have message IDs and therefore include every rendered row for those messages.
 * Marquee-created fragments additionally carry occurrence refs; those refs narrow the span to the
 * exact arrows inside the selection rectangle instead of pulling in every similar occurrence. */
private fun fragmentRowIndices(
    fragment: Seq3Fragment,
    firstRowIndex: Map<String, Int>,
    lastRowIndex: Map<String, Int>,
    rows: List<Seq3RowGeometry>,
): List<Int> {
    val exactMessageIds = fragment.occurrenceRefs.mapTo(hashSetOf()) { it.messageId }
    val exact = fragment.occurrenceRefs.mapNotNull { ref ->
        rows.indexOfFirst { row -> row.messageId == ref.messageId && row.occurrenceEntryId == ref.entryId }
            .takeIf { it >= 0 }
    }
    val messageIndices = fragment.messageIds
        .filterNot { it in exactMessageIds }
        .flatMap { id ->
            val first = firstRowIndex[id] ?: return@flatMap emptyList()
            val last = lastRowIndex[id] ?: return@flatMap emptyList()
            (first..last).toList()
        }
    return (exact + messageIndices).distinct().sorted()
}

// Item 5: `top` is clamped to at least [minTop] — the raw formula (ROW_H/2 + FRAGMENT_LABEL_H
// above the first spanned row) claims 37 units above a fragment's first row while buildRows' plain
// HEADER_TO_ROWS_GAP only left 22, a fixed 15-unit intrusion into the header band for a fragment
// spanning the FIRST message. buildRows' own FRAGMENT_TOP_RESERVE widening (this file's other half
// of the same fix) should already prevent this in the common case, but nested fragments share this
// exact top formula with NO per-depth vertical inset (unlike left/right, which DO inset by
// `depth * FRAGMENT_INSET_PER_DEPTH`) — so a defensive clamp here is the only thing that also
// covers a nested box, and stays correct even if a future caller ever invokes this without having
// gone through buildRows' own reserve.
private fun fragmentBoxFrom(fragment: Seq3Fragment, range: IntRange, depth: Int, rows: List<Seq3RowGeometry>, minTop: Double): Seq3FragmentBox {
    val spanned = range.mapNotNull { rows.getOrNull(it) }
    val xs = spanned.flatMap { rowXExtent(it) }
    val inset = depth * FRAGMENT_INSET_PER_DEPTH
    val left = (xs.minOrNull() ?: 0.0) - FRAGMENT_PAD + inset
    val right = (xs.maxOrNull() ?: 0.0) + FRAGMENT_PAD - inset
    val rawTop = (spanned.firstOrNull()?.y ?: 0.0) - ROW_H / 2 - FRAGMENT_LABEL_H
    val top = max(rawTop, minTop)
    val bottom = (spanned.lastOrNull()?.y ?: 0.0) + ROW_H / 2
    val label = fragment.label.ifBlank { fragment.kind.name.lowercase() }
    return Seq3FragmentBox(
        fragment.id,
        fragment.kind,
        label,
        Seq3Box(left, top, max(1.0, right - left), max(1.0, bottom - top)),
        depth,
        fragment.hideKindLabel,
    )
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
    // Same "drop the box, keep the row" contract as hidden fragments above — see layoutFragments'
    // own doc.
    val visibleNotes = notes.filter { it.visibility == Seq3Visibility.VISIBLE }
    if (visibleNotes.isEmpty()) return emptyList()
    return visibleNotes.mapNotNull { note ->
        val naturalWidth = max(NOTE_ROW_W, tm.width(Seq3FontRole.NOTE, note.text) + 2 * NOTE_PAD)
        val naturalHeight = ROW_H / 2 + NOTE_PAD
        // WP7 item 3: a free-floating note (the empty-canvas "Add note here" context menu) carries
        // its own explicit geometry and an EMPTY messageIds — it has nothing to anchor to on
        // purpose. Resolve its box from that geometry FIRST, before ever touching messageIds/
        // lastRowIndex, so it is never dropped for having no message span (the old code's ?: return
        // null on an empty `note.messageIds.mapNotNull{...}.maxOrNull()` did exactly that).
        if (note.x != null && note.y != null && note.width != null && note.height != null) {
            val box = Seq3Box(note.x, note.y, max(NOTE_ROW_W, note.width), max(naturalHeight, note.height))
            return@mapNotNull Seq3NoteBox(note.id, box, note.text)
        }
        // Ordinary message-spanning note (design spec §06's "Note" verb): anchor to the LAST drawn
        // row among its messages, same as before this WP.
        val anchorIdx = note.messageIds.mapNotNull { lastRowIndex[it] }.maxOrNull() ?: return@mapNotNull null
        val anchorRow = rows.getOrNull(anchorIdx) ?: return@mapNotNull null
        val touchedX = note.messageIds.mapNotNull { lastRowIndex[it] }.flatMap { rowXExtent(rows[it]) }
        val cx = touchedX.average().takeIf { !it.isNaN() } ?: centers.firstOrNull() ?: 0.0
        val box = Seq3Box(cx - naturalWidth / 2, anchorRow.y + ROW_H / 2, naturalWidth, naturalHeight)
        Seq3NoteBox(note.id, box, note.text)
    }
}
