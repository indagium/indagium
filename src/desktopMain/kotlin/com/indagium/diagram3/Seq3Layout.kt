@file:Suppress("TooManyFunctions")

package com.indagium.diagram3

import com.indagium.utils.elapsedMillisOfDay
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
    /** WP10: true when a `CREATE` arrow targeting this lifeline moved [header]/[lifelineTop] down
     *  from the shared top margin to the row that constructs it — see [layoutSeq3]'s own
     *  create/destroy resolution for the exact rule (first CREATE wins). Purely descriptive: no
     *  renderer branches on it today (both already read [header]/[lifelineTop] unconditionally),
     *  kept for the same reason [kind] is duplicated here rather than re-read from the document —
     *  and so a future caller (or a test) never has to reverse-engineer "was this one created?"
     *  from a `lifelineTop` comparison against the shared header band. */
    val created: Boolean = false,
    /** WP10: true when a `DESTROY` arrow targeting this lifeline moved [lifelineBottom] up from
     *  the diagram's last row to the row that destroys it (last DESTROY wins — see [layoutSeq3]).
     *  Drives the destroy-X paint in both renderers instead of them re-deriving "was this
     *  destroyed?" from a `lifelineBottom` comparison against the layout's overall height. */
    val destroyed: Boolean = false,
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

/** What a [Seq3UnresolvedStubRow] draws at its far ([Seq3UnresolvedStubRow.stubEndX]) end — added
 *  WP9 so one row shape can serve two different stories without a whole new [Seq3RowGeometry]
 *  subtype (that would cost nine exhaustive-`when` updates across Seq3Layout/Seq3Raster/Seq3Canvas
 *  for what is, visually, the same line-plus-terminal row). [DROP_PILL] is the pre-WP9 shape: a
 *  genuinely unresolved message, dashed amber, ending in the "drop on a lifeline" affordance.
 *  [LOST]/[FOUND] are UML's own lost/found message terminal — a small FILLED circle, no drop pill
 *  (the message is already resolved; offering to "drop" it onto a lifeline would misrepresent
 *  that) — see [Seq3Kind.LOST]/[Seq3Kind.FOUND]'s own doc. */
enum class Seq3StubTerminal { DROP_PILL, LOST, FOUND }

/** An unresolved ([Seq3Message.toLifelineId] == null) message — design spec §04: "Unresolved
 *  messages draw as a dashed amber stub ending in a `drop on a lifeline` pill — never as nothing."
 *  Always exactly ONE row per message regardless of [Seq3Message.repeat]: there is no second
 *  lifeline to fan the repeat out across yet, so [repeatCount] is carried for the badge instead of
 *  being expanded into multiple stub rows.
 *
 *  WP9: this row shape is now also how [Seq3Kind.LOST]/[Seq3Kind.FOUND] lay out — both also have
 *  a null `toLifelineId` by construction, and the row is otherwise exactly what a lost/found
 *  message needs (one real endpoint, a line running off toward the unobservable side). [terminal]
 *  (appended LAST, per this codebase's field-versioning convention) is the only thing that tells
 *  the two renderers which of the three stories to paint; [dropPill] stays non-null and populated
 *  for a LOST/FOUND row too (cheaper than making it nullable for a box neither renderer reads in
 *  that case) but must not be painted — see Seq3Raster's `paintStubRow`/Seq3Canvas's
 *  `Seq3RowOverlay` for the actual branch. */
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
    val terminal: Seq3StubTerminal = Seq3StubTerminal.DROP_PILL,
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

/** WP6: one resolved UML InteractionOperand divider drawn inside a [Seq3FragmentBox] — real
 *  geometry for the `else`/`and`/`option` line WP5's emitters already write as text
 *  (`Seq3Emitters.operandDividersByAnchor`). [y] is the midpoint between the row this operand's
 *  anchor resolves to and the row immediately before it — see [layoutFragments]' own resolution
 *  doc for exactly how that row is picked — so the divider sits in the GAP above its own first
 *  row, never overlapping either row's vertical band. Not a [Seq3RowGeometry] subtype: like
 *  [Seq3ActivationBar], it adds no pitch of its own and lives entirely inside a fragment box's
 *  already-computed geometry (see that type's own doc for the identical "why not a row" framing).
 */
data class Seq3FragmentDivider(val guard: String, val y: Double)

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
    /** WP6: UML operand dividers inside this box — see [Seq3FragmentDivider]'s own doc. Empty for
     *  every kind but ALT/PAR/CRITICAL (UML gives OPT/LOOP/BREAK exactly one operand; GROUP isn't a
     *  UML operator at all — see [Seq3FragmentKind]'s own doc) and for a fragment whose
     *  [Seq3Fragment.elseOperands] resolve to nothing inside this box's own CLAMPED range — the
     *  same "absent list, nothing drawn" default every other optional list on this file's types
     *  already uses ([Seq3Layout.delays]/[Seq3Layout.activations]). Appended LAST — this file's own
     *  versioning rule (see [Seq3Layout.activations]' own doc for why that rule matters here too).
     */
    val dividers: List<Seq3FragmentDivider> = emptyList(),
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

/**
 * A promoted [Seq3StateInvariant]'s drawn geometry (WP18) — a small marker anchored ON its
 * lifeline, at exactly the row that message occurrence drew at. [text] is that row's OWN capture
 * value (or [collapsedStateInvariantValue]'s summary for a row that stands for more than one
 * occurrence — see [buildStateInvariantBoxes]' own doc for the exact rule), never an authored
 * string: the whole point of promoting a capture instead of adding a plain note is that it says
 * something DIFFERENT at each row a repeated message draws.
 *
 * Deliberately NOT a [Seq3RowGeometry] subtype, for the identical reason [Seq3DelayBox]/
 * [Seq3ActivationBar] aren't: it adds no pitch of its own (drawn in space an existing row already
 * occupies — see [buildStateInvariantBoxes]' own "zero pitch" doc) and a new sealed subtype would
 * force nine exhaustive `when` updates across this file/`Seq3Raster.kt`/`ui/Seq3Canvas.kt` for a
 * shape that needs none of that dispatch. [Seq3StateInvariantBox] shares [Seq3DelayBox]'s own
 * three-field shape (id-ish label, box, text) on purpose — this deliverable's own instruction to
 * follow that precedent exactly.
 */
data class Seq3StateInvariantBox(val lifelineId: String, val box: Seq3Box, val text: String)

/**
 * A [Seq3ActivationSpan] (WP1's pure call/return pairing, `Seq3Activation.kt`) turned into an
 * actual rectangle — WP2's ONLY geometry contribution for UML activation bars. [box] is already
 * the bar's full drawn extent (x, top y, width, height); a renderer paints exactly this rect and
 * nothing else, the same "geometry decisions live here, painting decisions don't" split every
 * other box in this file follows (see [Seq3DelayBox]'s own doc for the identical framing).
 *
 * [depth] and [unmatched] are carried straight through from [Seq3ActivationSpan] — see that type's
 * own doc for what each means to a renderer (nested inset; omit the closing edge). This is a
 * document-level list on [Seq3Layout], not a new [Seq3RowGeometry] subtype: a bar is not a row (it
 * spans MULTIPLE rows, adds no pitch, and both endpoints of a span can each independently fail to
 * resolve — see [layoutSeq3]'s own "index-space trap" note) and a new sealed subtype would force
 * nine exhaustive `when` updates across this file, `Seq3Raster.kt`, and `ui/Seq3Canvas.kt` for a
 * shape that needs none of that dispatch — exactly why [Seq3DelayBox] already took this shape
 * instead.
 */
data class Seq3ActivationBar(
    val lifelineId: String,
    val box: Seq3Box,
    val depth: Int,
    val unmatched: Boolean,
)

/** One vertical run of a lifeline's dashed guide line: the ordinary dash pattern outside a delay
 *  ([isDotted] false), or a denser dotted pattern for the height of one it crosses ([isDotted]
 *  true) — user-observed correction mirroring PlantUML's own `...` convention, where a lifeline
 *  switches from its usual dashes to closely-spaced dots for the width of a delay marker, then
 *  reverts. See [seq3LifelineSegments]. */
data class Seq3LifelineSegment(val fromY: Double, val toY: Double, val isDotted: Boolean)

/**
 * Splits one lifeline's full vertical extent ([top]..[bottom]) into alternating
 * [Seq3LifelineSegment]s around every delay it crosses. Every [Seq3DelayBox.box] already spans
 * the FULL diagram width (that struct's own doc), so every lifeline crosses every delay at the
 * same y — this needs no per-column filtering, just the same [delays] list for every column
 * clamped to THIS column's own [top]/[bottom] (WP10: no longer the whole canvas height for every
 * lifeline — a created column's [top] starts at its construction row and a destroyed column's
 * [bottom] ends at its destruction row, see [Seq3LifelineColumn]/[layoutSeq3]'s own create/destroy
 * resolution). A delay whose band falls entirely outside [top]..[bottom] (routine now, for any
 * delay anchored before a lifeline is created or after it is destroyed) contributes no segment —
 * the `coerceIn` calls below already handle that with no special-casing needed here.
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
    /** WP2 UML activation bars — see [Seq3ActivationBar]'s own doc. Gated entirely on
     *  [Seq3Document.showActivations]: empty (and, per [layoutSeq3]'s own gate, never even
     *  COMPUTED) for a document with the toggle off, the same default-false/zero-cost contract
     *  every other WP1/WP2 addition in this package follows. Appended LAST, like [delays] above
     *  it — this file's own versioning rule (see the class's own positional construction site in
     *  [layoutSeq3] for why that site never needed to change for this field). */
    val activations: List<Seq3ActivationBar> = emptyList(),
    /** WP10: `MARGIN + headerHeight` — the shared header band's bottom edge, the same y ordinary
     *  (non-created) rows/columns already start at. Before create/destroy, a fixed
     *  `lifelines.first().lifelineTop` happened to equal this for every layout (no column ever
     *  differed), so several existing tests read it as a stand-in for "the header band bottom" —
     *  that reading rotted the moment a CREATEd column could report a lifelineTop of its own,
     *  lower than this shared value. Exposed here explicitly so those tests (and any future one)
     *  have an honest name for the value they actually mean, instead of reaching into column 0 and
     *  hoping it was never created. */
    val headerBandBottom: Double = 0.0,
    /** WP18: promoted-capture StateInvariant markers — see [Seq3StateInvariantBox]'s own doc.
     *  Empty for a document with no [Seq3Document.stateInvariants], same "absent list, nothing
     *  drawn" contract as [delays]/[activations]. Appended LAST — this file's own versioning rule
     *  (see [activations]' own doc for why that rule matters here too). */
    val stateInvariants: List<Seq3StateInvariantBox> = emptyList(),
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

// WP2: UML activation-bar geometry. ACTIVATION_W is the bar's own width, centered on its
// lifeline's centerX like every other on-lifeline element (self-loop origin, note anchor);
// ACTIVATION_NEST_OFFSET stairsteps each successive [Seq3ActivationSpan.depth] to the right by
// this much so nested activation reads as nested rectangles, not one solid block over-painted on
// itself — mirrors FRAGMENT_INSET_PER_DEPTH's identical role for nested fragment brackets, just a
// smaller value since a bar is much narrower than a fragment box to begin with. ACTIVATION_MIN_H
// is the floor `layoutSeq3` clamps a bar's height to: a CALL immediately followed by its own
// RETURN on the very next drawn row would otherwise compute a near-zero-height span (both
// endpoints landing on adjacent rows, `bottom - top` only a hair over 0) that all but disappears
// on screen — ROW_H/2 keeps even the shortest real activation visibly a rectangle, not a hairline.
private const val ACTIVATION_W = 10.0
private const val ACTIVATION_NEST_OFFSET = 4.0
private const val ACTIVATION_MIN_H = ROW_H / 2

// WP18: a StateInvariant marker's own geometry — sized like a small badge (BADGE_H/BADGE_PAD_H's
// own role) rather than a NOTE box, since it sits directly on the lifeline instead of floating
// beside it. Deliberately its own constants, not a reuse of BADGE_H/BADGE_PAD_H: a badge counts
// occurrences (`×3`) and a state marker shows a value, two different things that happen to want a
// similar-sized chip today but have no reason to be pinned to the same number forever.
private const val STATE_INVARIANT_H = 16.0
private const val STATE_INVARIANT_PAD_H = 6.0

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
    // Midnight-rollover fix: `elapsedByEntryId` (Seq3LabelSummary.kt) is the day-unrolled,
    // monotonic value per occurrence — looked up by `entryId` rather than added as a field to
    // `Emission` itself, since an emission's occurrence identity (`entryId`) is already exactly what
    // this needs and a new field on every one of `Emission`'s five subtypes would duplicate data
    // this map already has. A miss (an emission with no occurrence — an authored arrow/stub/note, or
    // `Emission.Elision`, which never carries one) falls back to `emission.timestampMillis`, its own
    // pre-existing (and, for those cases, already-correct — see `Seq3Message.primaryElapsedMillis`'s
    // own doc on manual overrides) value.
    val elapsedByEntryId = seq3ElapsedByEntryId(doc)
    val chronologicalEmissions = seq3ChronologicalOrder(
        doc,
        visibleMessages.flatMap(::expandForLayout),
        messageIdOf = { emission -> emission.messageId },
        timestampMillisOf = { emission -> emission.entryId?.let(elapsedByEntryId::get) ?: emission.timestampMillis },
        entryIdOf = { emission -> emission.entryId },
    )
    // WP10 (item 7): number/timestamp-prefix each call's label BEFORE measurement — see
    // prefixEmissionLabels' own doc for why doing this after measureRequirement would reintroduce
    // WP9's clipping bug in a worse form (a toggle the user can flip live, not just a one-off typo).
    val emissions = prefixEmissionLabels(chronologicalEmissions, doc.showSequenceNumbers, doc.showTimestamps, doc.showElapsed, elapsedByEntryId)
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

    // WP10: which row, if any, creates/destroys each lifeline — see resolveSeq3Lifecycle's own
    // doc. Pulled into its own function purely to keep layoutSeq3's own Cyclomatic Complexity
    // under this file's detekt threshold, the identical reason buildActivationBars below is its
    // own function rather than inlined here.
    val lifecycle = resolveSeq3Lifecycle(emissions, lifelineIndex, rowBuild.rowYByIndex)

    // WP10: a CREATE arrow's target box is drawn LOWER than `centers[toIdx]`'s usual full-height
    // position (see the lifelineColumns build below) — see applyCreateArrowStops' own doc. Also
    // its own function for the same CyclomaticComplexMethod reason as resolveSeq3Lifecycle above.
    // MUST run before `layoutFragments` below, which consumes `rowBuild.rightExtra`/fragment spans
    // derived from row x-extents — an unrewritten `toX` would size those against the wrong edge.
    val rowsWithCreateStop = applyCreateArrowStops(rowBuild.rows, lifelineIndex, centers, headerWidths)

    val fragments = layoutFragments(doc.fragments, rowBuild.firstRowIndex, rowBuild.lastRowIndex, rowsWithCreateStop, headerHeight)
    val notes = layoutNotes(doc.notes, rowBuild.lastRowIndex, rowsWithCreateStop, centers, tm)

    // WP2: UML activation bars — see buildActivationBars' own doc. Pulled into its own function
    // (rather than inlined here like the delay-band resolution above it) purely to keep
    // layoutSeq3's own Cyclomatic Complexity under this file's detekt threshold — the branching
    // this needs (the showActivations gate, plus one mapNotNull per dangling-reference check) is
    // real work, just work that reads more clearly, and counts against a fresh budget, as its own
    // named function rather than another inline block bolted onto an already-long orchestrator.
    val activationBars = buildActivationBars(doc, emissions, rowBuild.rowYByIndex, lifelineIndex, centers)
    // WP18: promoted-capture StateInvariant markers — a pure post-pass over the already-placed rows,
    // like buildActivationBars just above; see buildStateInvariantBoxes' own doc for why it needs
    // nothing rowsWithCreateStop itself doesn't already hand back.
    val stateInvariantBoxes = buildStateInvariantBoxes(doc, rowsWithCreateStop, lifelineIndex, centers, tm)
    // Folded into the existing rightEdge maxOf below with one extra term — a bar on the rightmost
    // column (nested activation stairstepping it further right still via ACTIVATION_NEST_OFFSET)
    // must never get clipped, the identical reasoning gapSolve.rightExtra/rowBuild.rightExtra
    // already apply to a self-loop/note/stub on that same column.
    val activationRightExtra = activationBars.maxOfOrNull { it.box.x + it.box.width } ?: 0.0

    val rightEdge = maxOf(contentRight, gapSolve.rightExtra + (centers.lastOrNull() ?: 0.0), rowBuild.rightExtra, activationRightExtra)
    val noteRight = notes.maxOfOrNull { it.box.x + it.box.width } ?: 0.0
    val noteBottom = notes.maxOfOrNull { it.box.y + it.box.height } ?: 0.0
    val width = maxOf(rightEdge, noteRight) + MARGIN
    val height = maxOf(rowBuild.bottomY, noteBottom) + BOTTOM_MARGIN

    // headerHeight itself is UNCHANGED by any of this — still computed above over every visible
    // lifeline (created ones included, so a created lifeline's own wrapped label still contributes
    // to `maxLabelLineCount` and the shared band stays tall enough) and `buildRows`' own
    // `y = MARGIN + headerHeight + topGap` origin never moves. Only a created column's OWN header
    // box relocates, below, from that shared band down to its creation row — NO ROW MOVES.
    val minLifelineTop = MARGIN + headerHeight
    val lifelineColumns = lifelinesSorted.mapIndexed { i, l ->
        resolveSeq3LifelineColumn(
            i, l, lifecycle, minLifelineTop, rowBuild.bottomY, nameBoxHeight,
            actorReserve, labelLinesPerLifeline, lefts, headerWidths, centers,
        )
    }
    return Seq3Layout(
        width, height, lifelineColumns, rowsWithCreateStop, fragments, notes, crossingCount,
        rowBuild.delayBoxes, activationBars, headerBandBottom = minLifelineTop,
        stateInvariants = stateInvariantBoxes,
    )
}

// WP10: which row, if any, creates/destroys each lifeline (by column index). FIRST CREATE wins —
// a later one targeting an already-constructed lifeline is ignored, the box already exists. LAST
// DESTROY wins — truncating at the FIRST would hide any row drawn after it, and a too-long
// lifeline is a strictly better failure than silently hidden evidence (this package's usual
// "never throw/hide on degenerate input" posture — see Seq3Generator.kt's header). Resolved from
// `emissions`/`rowYByIndex`, not `rowBuild.rows`, for the same reason `buildActivationBars` below
// reads `rowYByIndex`: it is keyed by EMISSION index and survives a dropped row, where `rows`' own
// index does not (RowBuildResult's own "index-space trap" doc). Its own function (like
// buildActivationBars below it) purely to keep layoutSeq3's own Cyclomatic Complexity under this
// file's detekt threshold.
private class Seq3Lifecycle(val createRowYByLifeline: Map<Int, Double>, val destroyRowYByLifeline: Map<Int, Double>)

private fun resolveSeq3Lifecycle(emissions: List<Emission>, lifelineIndex: Map<String, Int>, rowYByIndex: Map<Int, Pair<Double, Double>>): Seq3Lifecycle {
    val createRowYByLifeline = HashMap<Int, Double>()
    val destroyRowYByLifeline = HashMap<Int, Double>()
    emissions.forEachIndexed { i, emission ->
        if (emission !is Emission.Arrow) return@forEachIndexed
        val toIdx = lifelineIndex[emission.toLifelineId] ?: return@forEachIndexed
        val rowY = rowYByIndex[i]?.first ?: return@forEachIndexed
        when (emission.kind) {
            Seq3Kind.CREATE -> createRowYByLifeline.putIfAbsent(toIdx, rowY) // first wins
            Seq3Kind.DESTROY -> destroyRowYByLifeline[toIdx] = rowY // keep overwriting: last wins
            else -> Unit
        }
    }
    return Seq3Lifecycle(createRowYByLifeline, destroyRowYByLifeline)
}

// WP10: builds one Seq3LifelineColumn, resolving its create/destroy geometry — pulled out of
// layoutSeq3's own `lifelineColumns = lifelinesSorted.mapIndexed { ... }` for the identical
// CyclomaticComplexMethod reason as resolveSeq3Lifecycle/applyCreateArrowStops above (this file's
// detekt threshold). headerHeight itself is untouched by any of this (layoutSeq3's own doc); only
// THIS column's header/lifelineTop/lifelineBottom moves off the shared band.
// every parameter is read-only geometry already computed once in layoutSeq3; bundling them into
// a struct would just be a second copy of layoutSeq3's own locals.
@Suppress("LongParameterList")
private fun resolveSeq3LifelineColumn(
    i: Int,
    l: Seq3Lifeline,
    lifecycle: Seq3Lifecycle,
    minLifelineTop: Double,
    naturalBottomY: Double,
    nameBoxHeight: Double,
    actorReserve: Double,
    labelLinesPerLifeline: List<List<String>>,
    lefts: DoubleArray,
    headerWidths: List<Double>,
    centers: DoubleArray,
): Seq3LifelineColumn {
    val lifelineBottom = lifecycle.destroyRowYByLifeline[i]?.coerceIn(minLifelineTop, naturalBottomY) ?: naturalBottomY
    val createRowY = lifecycle.createRowYByLifeline[i]
    // Created column: the header box CENTRES on the create arrow's y (the UML convention) and the
    // guide line starts at the box's bottom — exactly [header.y + nameBoxHeight == lifelineTop],
    // the same invariant the ordinary (non-created) branch below already has ([MARGIN +
    // actorReserve] + nameBoxHeight == [MARGIN + headerHeight]). Clamping `lifelineTop` first and
    // deriving `header.y` FROM the clamped value (rather than clamping each independently) is what
    // keeps that invariant true even at the clamp's own boundary.
    val lifelineTop = if (createRowY != null) {
        (createRowY + nameBoxHeight / 2.0).coerceIn(minLifelineTop, lifelineBottom)
    } else {
        minLifelineTop
    }
    val headerY = if (createRowY != null) lifelineTop - nameBoxHeight else MARGIN + actorReserve
    return Seq3LifelineColumn(
        lifelineId = l.id,
        label = l.name,
        labelLines = labelLinesPerLifeline[i],
        kind = l.kind,
        centerX = centers[i],
        // The NAME box sits below the actor-glyph reserve (see Seq3LifelineColumn's own doc):
        // MARGIN + actorReserve, not just MARGIN, so a participant column also leaves the same
        // blank band an actor column's stick figure occupies — that's what keeps every chip's
        // bottom edge (and therefore lifelineTop below) aligned regardless of kind. A created
        // column overrides this with `headerY`, computed above from its own creation row.
        header = Seq3Box(lefts[i], headerY, headerWidths[i], nameBoxHeight),
        lifelineTop = lifelineTop,
        lifelineBottom = lifelineBottom,
        created = createRowY != null,
        destroyed = lifecycle.destroyRowYByLifeline.containsKey(i),
    )
}

// WP10: a CREATE arrow's target box is drawn LOWER than `centers[toIdx]`'s usual full-height
// position (see layoutSeq3's lifelineColumns build), and both renderers paint headers BEFORE rows
// — an unmodified create arrow's `toX` (still the column centre) would draw straight over/through
// the box it constructs. Rewrite it to stop at the box's own near edge instead — the SAME
// relationship every other arrow already has with an ordinary column's centre-aligned box, just
// applied to a box that has moved. A small post-pass over already-built rows, rather than
// threading the created-row-Y map into `buildArrowRow` itself, because at the point `buildArrowRow`
// runs no lifeline's create/destroy row is known yet (it can't be: resolving it needs
// `rowYByIndex`, which `buildRows` only finishes producing once every row is built).
private fun applyCreateArrowStops(
    rows: List<Seq3RowGeometry>,
    lifelineIndex: Map<String, Int>,
    centers: DoubleArray,
    headerWidths: List<Double>,
): List<Seq3RowGeometry> = rows.map { row ->
    if (row is Seq3ArrowRow && row.kind == Seq3Kind.CREATE) {
        val toIdx = lifelineIndex[row.toLifelineId] ?: return@map row
        val boxCenterX = centers[toIdx]
        val halfWidth = headerWidths[toIdx] / 2.0
        // Stop at whichever edge faces the sender — the near edge, exactly like any other arrow's
        // arrowhead sits at the near edge of an ordinary (centre-drawn) box today.
        val edgeX = if (row.fromX <= boxCenterX) boxCenterX - halfWidth else boxCenterX + halfWidth
        row.copy(toX = edgeX)
    } else {
        row
    }
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
        // WP16: appended LAST (this file's own field-versioning convention — file-local sealed
        // type, no codec, no round-trip; see this field's twin on [Self] and
        // Seq3Emitters.kt's `Seq3Emission.Arrow` for the full doc). Non-null ONLY on a
        // [Seq3Repeat.COLLAPSE_ABOVE] row above threshold, where it is
        // `occurrences.last().timestampMillis` — the row's own internal span end, as opposed to
        // [timestampMillis] (the span START, i.e. the first occurrence this row stands for). See
        // [expandForLayout]'s COLLAPSE_ABOVE branch for where it's set and
        // [seq3PrefixedLabel]'s doc for how it renders.
        val spanEndTimestampMillis: Long? = null,
    ) : Emission()

    data class Self(
        override val messageId: String,
        override val fromLifelineId: String,
        val label: String,
        val repeatCount: Int,
        override val entryId: Int?,
        override val timestampMillis: Long?,
        val rawTimestamp: String,
        // WP16: see [Arrow.spanEndTimestampMillis]'s own doc — identical meaning here.
        val spanEndTimestampMillis: Long? = null,
    ) : Emission()

    data class Stub(
        override val messageId: String,
        override val fromLifelineId: String,
        val label: String,
        val repeatCount: Int,
        override val entryId: Int?,
        override val timestampMillis: Long?,
        val rawTimestamp: String,
        /** WP9: which [Seq3StubTerminal] `buildStubRow` should draw — derived from the owning
         *  message's [Seq3Kind] once, here, rather than re-deriving it at build time. */
        val kind: Seq3Kind,
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
        // WP15 Part 2: was the bare `message.labelTemplate` — a capture-bearing NOTE drew its
        // literal `{name}` slots on screen with no value in sight. `collapsedRepeatLabel` already
        // does exactly the right thing here with zero new logic: it substitutes the one occurrence
        // when there is exactly one, summarizes a few distinct values as `A|B|C`, falls back to the
        // honest template above COLLAPSED_SUMMARY_MAX_DISTINCT, and — the case that matters for an
        // authored NOTE with no occurrences at all — returns the template unchanged when there is
        // nothing to substitute from (distinctLabels.size == 0 misses its 1..3 range).
        return listOf(
            Emission.Note(
                message.id,
                message.fromLifelineId,
                collapsedRepeatLabel(message, visibleOccurrences),
                occ?.entryId,
                message.primaryTimestampMillis,
                message.primaryRawTimestamp,
            ),
        )
    }
    if (message.toLifelineId == null) {
        val occ = visibleOccurrences.firstOrNull()
        // WP15 Part 2: same fix as the NOTE branch above, same reasoning — an unresolved/LOST/FOUND
        // stub used to draw the bare template too.
        return listOf(
            Emission.Stub(
                message.id,
                message.fromLifelineId,
                collapsedRepeatLabel(message, visibleOccurrences),
                visibleOccurrences.size.coerceAtLeast(1),
                occ?.entryId,
                message.primaryTimestampMillis,
                message.primaryRawTimestamp,
                message.kind,
            ),
        )
    }
    val occurrences = visibleOccurrences
    val isSelf = message.kind == Seq3Kind.SELF

    // spanEndTimestampMillis: trailing optional, omitted by every caller except the COLLAPSE_ABOVE
    // above-threshold branch below — see [Emission.Arrow.spanEndTimestampMillis]'s own doc. Kept as
    // a 6th default param (not a 6-arg call everywhere) so `::arrow` still adapts to
    // [firstLastEmissions]'s 5-arg function-type parameter (Kotlin's callable-reference-to-
    // default-arg-function adaptation), rather than duplicating the isSelf branch a second time.
    fun arrow(
        label: String,
        count: Int,
        entryId: Int?,
        timestampMillis: Long?,
        rawTimestamp: String,
        spanEndTimestampMillis: Long? = null,
    ): Emission = if (isSelf) {
        Emission.Self(message.id, message.fromLifelineId, label, count, entryId, timestampMillis, rawTimestamp, spanEndTimestampMillis)
    } else {
        Emission.Arrow(
            message.id, message.fromLifelineId, message.toLifelineId, message.kind,
            label, count, entryId, timestampMillis, rawTimestamp, spanEndTimestampMillis,
        )
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
                    // WP16: the row's own internal span end. `occurrences` here may be a
                    // trimSeq3MessageOccurrences first/last WINDOW rather than the true full
                    // evidence (when message.totalOccurrenceCount != null) — but that trim keeps
                    // exactly the first and last real occurrences (Seq3Generator.kt's
                    // trimSeq3MessageOccurrences: `occurrences.take(keepFirst) + occurrences
                    // .takeLast(keepLast)`, itself walking an already-chronologically-sorted list),
                    // so `.last()` is still the TRUE last occurrence's timestamp even on a trimmed
                    // message — the span stays honest.
                    occurrences.last().timestampMillis,
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

// ── WP2: Emission -> Seq3ActivationEvent ────────────────────────────────────────────────────
//
// `seq3ActivationSpans` (Seq3Activation.kt, WP1) needs one [Seq3ActivationEvent] per drawn row, in
// row order — exactly what `emissions` (post `seq3ChronologicalOrder`, pre `prefixEmissionLabels`
// or post it, doesn't matter — this reads no label) already is. [index] is deliberately the
// position in THIS list, the same index space `rowYByIndex` above is keyed by — see
// [layoutSeq3]'s own "index-space trap" note for why that space, not `rows`' own, is the one that
// matters here.

/**
 * Maps one [Emission] to the bare per-row fact [seq3ActivationSpans] needs. Only [Emission.Arrow]
 * carries a real UML message [Seq3Kind] (CALL/RETURN/ASYNC) AND both endpoints a bar could
 * open/close on — every other case in this file's own `Emission` hierarchy maps to
 * [Seq3Kind.NOTE], [seq3ActivationSpans]' designated neutral no-push/no-pop case:
 *  - [Emission.Self] never carries a [Seq3Kind] of its own (a same-lifeline call IS the self kind,
 *    always) — mapped with its real [Seq3Kind.SELF], which [seq3ActivationSpans] already treats as
 *    neutral, so this is the honest kind rather than a second alias for "neutral".
 *  - [Emission.Stub] has no `toLifelineId` (design spec: unresolved messages draw as a dashed
 *    stub with no target) — there is no lifeline a bar could open ON, so it cannot be a CALL/
 *    RETURN either, regardless of what the underlying message's own (unresolved) kind might be.
 *  - [Emission.Note]/[Emission.Elision] are annotations, not calls — they carry no execution
 *    semantics at all.
 * NONE of these four are skipped, even though all four map to the same neutral kind: every one
 * still becomes an event so it participates in [seq3ActivationSpans]' rule-1 fallback ("an
 * unmatched call closes at the last row index that touches its lifeline") — a self-call or note
 * logged after a lifeline's last real RETURN must still push an unmatched bar's close index out to
 * cover it, not leave the bar ending one row too early. Skipping these emissions instead of giving
 * them a neutral event would silently shrink that fallback's own row-order evidence.
 */
private fun activationEventOf(index: Int, emission: Emission): Seq3ActivationEvent = when (emission) {
    is Emission.Arrow -> Seq3ActivationEvent(index, emission.messageId, emission.kind, emission.fromLifelineId, emission.toLifelineId)
    is Emission.Self -> Seq3ActivationEvent(index, emission.messageId, Seq3Kind.SELF, emission.fromLifelineId, emission.fromLifelineId)
    is Emission.Stub -> Seq3ActivationEvent(index, emission.messageId, Seq3Kind.NOTE, emission.fromLifelineId, null)
    is Emission.Note -> Seq3ActivationEvent(index, emission.messageId, Seq3Kind.NOTE, emission.fromLifelineId, null)
    is Emission.Elision -> Seq3ActivationEvent(index, emission.messageId, Seq3Kind.NOTE, emission.fromLifelineId, null)
}

/**
 * Turns [emissions] into [Seq3ActivationBar]s, entirely gated on [Seq3Document.showActivations] so
 * an old/toggled-off document allocates NOTHING here — not even the `events` list — matching the
 * load-bearing invariant this work package's brief calls out: bars must add zero vertical pitch
 * AND zero cost when off.
 *
 * [seq3ActivationSpans] (Seq3Activation.kt, WP1) works in EMISSION-index space — see
 * [activationEventOf]'s own doc — so [rowYByIndex] (also emission-index-keyed, precisely to make
 * this lookup possible; see `buildRows`' own doc on why that map exists) converts each span's
 * start/end index back to real y geometry. A span whose either endpoint's row never got built
 * (`buildRow` returned null — an unresolvable lifeline) is dropped entirely via `mapNotNull`:
 * "dangling reference draws nothing, never crash" is this package's documented contract for every
 * geometry lookup that can fail, the same posture [layoutFragments]/[layoutNotes] already take on
 * their own dangling references.
 */
private fun buildActivationBars(
    doc: Seq3Document,
    emissions: List<Emission>,
    rowYByIndex: Map<Int, Pair<Double, Double>>,
    lifelineIndex: Map<String, Int>,
    centers: DoubleArray,
): List<Seq3ActivationBar> {
    if (!doc.showActivations) return emptyList()
    val events = emissions.mapIndexed(::activationEventOf)
    return seq3ActivationSpans(events, emissions.lastIndex).mapNotNull { span ->
        val (top, _) = rowYByIndex[span.startIndex] ?: return@mapNotNull null
        val (_, bottom) = rowYByIndex[span.endIndex] ?: return@mapNotNull null
        val lifelineIdx = lifelineIndex[span.lifelineId] ?: return@mapNotNull null
        val x = centers[lifelineIdx] - ACTIVATION_W / 2 + span.depth * ACTIVATION_NEST_OFFSET
        // max(..., ACTIVATION_MIN_H): a CALL immediately followed by its own RETURN on the very
        // next drawn row would otherwise compute a near-zero-height span — see ACTIVATION_MIN_H's
        // own doc.
        val height = max(bottom - top, ACTIVATION_MIN_H)
        Seq3ActivationBar(span.lifelineId, Seq3Box(x, top, ACTIVATION_W, height), span.depth, span.unmatched)
    }
}

/**
 * WP18: turns every VISIBLE [Seq3Document.stateInvariants] into [Seq3StateInvariantBox]es, one per
 * DRAWN ROW of the message it promotes a capture from — see [Seq3StateInvariantBox]'s own doc for
 * why the text differs per row. A pure post-pass over [rows] (already-placed geometry, like
 * [buildActivationBars] above), not folded into `buildRows`/`expandForLayout` themselves: those two
 * only ever see ONE message at a time and have no reason to know `doc.stateInvariants` exists at
 * all — this needs nothing they don't already hand back (`messageId`/`y`/`occurrenceEntryId`, all on
 * [Seq3RowGeometry]'s own base class).
 *
 * **Zero added vertical pitch, pinned by its own test** (`aStateInvariantAddsZeroVerticalPitch...`,
 * Seq3LayoutTest.kt): every box is placed at an EXISTING row's own `y` — nothing here advances a
 * y-cursor or widens a row's own pitch, mirroring [Seq3DelayBox]/[Seq3ActivationBar]'s identical
 * "decorate space a row already occupies" contract; toggling a promotion on/off can never reflow the
 * diagram, matching WP2's own activation-bar precedent (`rowYPositionsAreIdenticalWithActivationsOn
 * AndOff`).
 *
 * **The collapsed-row decision** (deliverable's own open question): a message that draws FEWER rows
 * than it has visible occurrences — a [Seq3Repeat.COLLAPSE_ABOVE] row above threshold (one row, all
 * occurrences), or a [Seq3Kind.NOTE]/needs-target message (always exactly one row regardless of
 * `repeat`, see `expandForLayout`'s own NOTE/stub branches) — has no single occurrence for that lone
 * row to represent. [collapsedStateInvariantValue] (Seq3LabelSummary.kt) already solves this EXACT
 * problem for [collapsedRepeatLabel]'s own label text, so it is reused here rather than a second
 * answer invented for a value instead of a label: substitute the one value when every occurrence
 * agrees, a compact `A|B|C` summary for a few distinct values, and an honest `{captureName}`
 * placeholder above [COLLAPSED_SUMMARY_MAX_DISTINCT] (Seq3LabelSummary.kt) distinct values — a
 * collapsed row genuinely stands for many different states at that point, and a marker that tried to
 * cram them all in would be worse than admitting it can't. `messageRows.size == 1 && visibleOccurrences
 * .size > 1` is the exact, type-agnostic test for "this one row stands for more than one occurrence":
 * it is true for a collapsed-above-threshold [Seq3ArrowRow]/[Seq3SelfLoopRow], a NOTE's
 * [Seq3MessageNoteRow], and a needs-target/LOST/FOUND [Seq3UnresolvedStubRow] alike, and false for
 * EVERY/FIRST_LAST/below-threshold COLLAPSE_ABOVE (one row genuinely IS one occurrence there) without
 * this function needing to `when` on the row's own subtype at all.
 *
 * **Dangling references never throw**: an [Seq3StateInvariant.messageId] naming no message in the
 * document, one whose [Seq3Message.fromLifelineId] is hidden/unresolvable, and a [Seq3StateInvariant
 * .captureName] no longer present on ANY visible occurrence (the pattern was edited after
 * promotion — [collapsedStateInvariantValue] returns null for that case) all drop out silently via
 * the `?:`/`mapNotNull` chain below — the same "a dangling reference draws, never crashes" contract
 * this package documents everywhere else a stored id can go stale.
 */
private fun buildStateInvariantBoxes(
    doc: Seq3Document,
    rows: List<Seq3RowGeometry>,
    lifelineIndex: Map<String, Int>,
    centers: DoubleArray,
    tm: Seq3TextMetrics,
): List<Seq3StateInvariantBox> {
    if (doc.stateInvariants.isEmpty()) return emptyList()
    val messagesById = doc.messages.associateBy { it.id }
    val rowsByMessageId = rows.groupBy { it.messageId }
    return doc.stateInvariants.filter { it.visibility == Seq3Visibility.VISIBLE }.flatMap { invariant ->
        val message = messagesById[invariant.messageId] ?: return@flatMap emptyList()
        val lifelineIdx = lifelineIndex[message.fromLifelineId] ?: return@flatMap emptyList()
        val centerX = centers[lifelineIdx]
        val messageRows = rowsByMessageId[invariant.messageId].orEmpty()
        val visibleOccurrences = message.occurrences.filter { it.visibility == Seq3Visibility.VISIBLE }
        // See this function's own "collapsed-row decision" doc above for exactly why this predicate
        // (not a per-row-type `when`) is the right test.
        val collapsed = messageRows.size == 1 && visibleOccurrences.size > 1
        messageRows.mapNotNull { row ->
            val text = if (collapsed) {
                collapsedStateInvariantValue(invariant.captureName, visibleOccurrences)
            } else {
                val entryId = row.occurrenceEntryId ?: return@mapNotNull null
                visibleOccurrences.firstOrNull { it.entryId == entryId }?.captureValues?.get(invariant.captureName)
            } ?: return@mapNotNull null
            val width = withMeasurementSlack(tm.width(Seq3FontRole.BADGE, text)) + 2 * STATE_INVARIANT_PAD_H
            Seq3StateInvariantBox(
                message.fromLifelineId,
                Seq3Box(centerX - width / 2, row.y - STATE_INVARIANT_H / 2, width, STATE_INVARIANT_H),
                text,
            )
        }
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
// WP15 Part 1: [showElapsed] adds a fourth tag, `[+0.140]` — the measured gap from the previous
// DRAWN row's real timestamp to this row's own, per THE FOUR RULES (this function's own call site
// doc / the work package brief). This is now a fold carrying `lastRealTimestampMillis` alongside
// the pre-existing `callNumber` var — same "mutate a local while mapping in emission order" shape,
// just a second piece of running state. The accumulator advances on EVERY emission, numbered or
// not (rule 4): a [Emission.Note]/[Emission.Elision] row is never itself tagged, but its own
// [Emission.timestampMillis] still becomes the next row's "previous real timestamp" — this is what
// makes [Seq3Repeat.FIRST_LAST]'s elision row come out right. That row is seeded with the FIRST
// occurrence's own timestamp (see [Emission.Elision]'s own doc), so folding it through unchanged
// means the LAST row's tag measures the true span of the whole elided run, not just its own
// immediate (and meaningless) predecessor. [elapsedFor] returns null — never a fabricated `+0.000`
// — the moment either endpoint is unknown (rule 1), and deliberately does NOT fall back to an older
// non-null accumulator value once a null has been folded through: reaching further back past a null
// predecessor would silently measure across a gap this function has no evidence for and report it
// as if it were real. [elapsedMillisOfDay] (not plain subtraction) is still what performs the
// subtraction (rule 3), but — midnight-rollover fix — what it now reads is [orderingValue]'s
// day-unrolled, monotonic value (via [elapsedByEntryId], falling back to the emission's own raw
// [Emission.timestampMillis] where no unrolled value exists), not [Emission.timestampMillis]
// directly. Before [elapsedByEntryId] existed, this fold measured gaps off bare millis-of-day, so a
// message range that crossed midnight had ALREADY been drawn in swapped, wrong order by the time
// this fold ever ran (see `seq3ChronologicalOrder`'s own history) — the row right after the seam
// then read back a spurious ~24h `[+86399.860]` tag instead of the true short gap, and
// [elapsedMillisOfDay]'s own rollover correction inside THIS fold could never catch that: both
// values it ever saw were already millis-of-day and already on the "wrong" side of each other
// relative to real time, which looks like an ordinary small gap to the correction, not a crossing.
// Feeding it the unrolled value instead fixes the problem at its source — the fold now walks
// genuinely ascending elapsed time, and the tag it reports for that same seam is the true short
// gap. [seq3PrefixedLabel]'s own `timestampMillis` argument below is deliberately left reading
// [Emission.timestampMillis] (never [orderingValue]) in every call — that argument is what gets
// DISPLAYED as `[HH:MM:SS.mmm]`, and unrolled elapsed time is not a wall-clock reading.
private fun prefixEmissionLabels(
    emissions: List<Emission>,
    showSequenceNumbers: Boolean,
    showTimestamps: Boolean,
    showElapsed: Boolean,
    elapsedByEntryId: Map<Int, Long>,
): List<Emission> {
    if (!showSequenceNumbers && !showTimestamps && !showElapsed) return emissions
    var callNumber = 0
    var lastRealElapsedMillis: Long? = null
    // The value this fold's ACCUMULATOR reads and measures gaps on — see this function's own header
    // for why this must not be [Emission.timestampMillis] itself. A miss (no occurrence, e.g. an
    // authored arrow/stub/note, or [Emission.Elision]) falls back to that same raw value, exactly
    // preserving pre-fix behaviour for a message/document with no unrolled data.
    fun orderingValue(emission: Emission): Long? = emission.entryId?.let(elapsedByEntryId::get) ?: emission.timestampMillis
    fun elapsedFor(currentElapsedMillis: Long?): Long? {
        val previous = lastRealElapsedMillis
        return if (previous != null && currentElapsedMillis != null) elapsedMillisOfDay(previous, currentElapsedMillis) else null
    }
    return emissions.map { emission ->
        when (emission) {
            is Emission.Arrow -> {
                callNumber++
                val elapsed = elapsedFor(orderingValue(emission))
                val result = emission.copy(
                    label = seq3PrefixedLabel(
                        emission.label,
                        callNumber,
                        emission.rawTimestamp,
                        emission.timestampMillis,
                        showSequenceNumbers,
                        showTimestamps,
                        elapsed,
                        showElapsed,
                        emission.spanEndTimestampMillis,
                    ),
                )
                lastRealElapsedMillis = orderingValue(emission)
                result
            }
            is Emission.Self -> {
                callNumber++
                val elapsed = elapsedFor(orderingValue(emission))
                val result = emission.copy(
                    label = seq3PrefixedLabel(
                        emission.label,
                        callNumber,
                        emission.rawTimestamp,
                        emission.timestampMillis,
                        showSequenceNumbers,
                        showTimestamps,
                        elapsed,
                        showElapsed,
                        emission.spanEndTimestampMillis,
                    ),
                )
                lastRealElapsedMillis = orderingValue(emission)
                result
            }
            is Emission.Stub -> {
                callNumber++
                val elapsed = elapsedFor(orderingValue(emission))
                val result = emission.copy(
                    label = seq3PrefixedLabel(
                        emission.label,
                        callNumber,
                        emission.rawTimestamp,
                        emission.timestampMillis,
                        showSequenceNumbers,
                        showTimestamps,
                        elapsed,
                        showElapsed,
                    ),
                )
                lastRealElapsedMillis = orderingValue(emission)
                result
            }
            is Emission.Note, is Emission.Elision -> {
                // Untagged (rule 4's own case), but the accumulator still advances — see this
                // function's own header.
                lastRealElapsedMillis = orderingValue(emission)
                emission
            }
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
    // WP2: each successfully-built row's own (top, bottom) y-extent, keyed by its EMISSION index
    // (the position in the `emissions` list buildRows iterates) — NOT `rows.size`/`rows.lastIndex`,
    // because a null buildRow result (unresolvable lifeline) skips a `rows` append but NOT an
    // emissions index, so the two index spaces silently diverge the moment any row is dropped (see
    // layoutSeq3's own "index-space trap" note). seq3ActivationSpans works entirely in
    // emission-index space, so this map is the one lookup that lets a span's startIndex/endIndex
    // resolve back to real geometry without buildRows itself needing to know anything about
    // activation bars.
    val rowYByIndex: Map<Int, Pair<Double, Double>> = emptyMap(),
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
    val rowYByIndex = HashMap<Int, Pair<Double, Double>>()
    val topGap = if (fragmentsPresent) max(HEADER_TO_ROWS_GAP, FRAGMENT_TOP_RESERVE) else HEADER_TO_ROWS_GAP
    var y = MARGIN + headerHeight + topGap
    var rightExtra = 0.0
    emissions.forEachIndexed { i, emission ->
        val req = requirements[i]
        val built = buildRow(emission, req, lifelineIndex, centers, y) ?: return@forEachIndexed
        first.getOrPut(emission.messageId) { rows.size }
        rows += built.geometry
        last[emission.messageId] = rows.lastIndex
        rowYByIndex[i] = rowVerticalExtent(built.geometry)
        y += built.pitch
        rightExtra = max(rightExtra, built.rightEdge)
        delaysByRowIndex[i]?.forEach { delay ->
            val box = Seq3Box(bandLeft, y, (bandRight - bandLeft).coerceAtLeast(1.0), DELAY_BAND_H)
            delayBoxes += Seq3DelayBox(delay.id, delay.label, box)
            y += DELAY_BAND_H
        }
    }
    return RowBuildResult(rows, first, last, y, rightExtra, delayBoxes, rowYByIndex)
}

/**
 * WP2: a row's own (top, bottom) y-extent — where an activation bar's endpoint lands when this
 * row is a [Seq3ActivationSpan.startIndex] or [Seq3ActivationSpan.endIndex]. Every row type except
 * [Seq3SelfLoopRow] is drawn as a single horizontal line AT its own `y` (see
 * `Seq3Raster.paintArrowRow`'s `Line2D.Double(row.fromX, row.y, row.toX, row.y)`, for instance) —
 * top and bottom both collapse to that same `y`, which is exactly right: a CALL bar should start
 * where the arrow LANDS and a RETURN bar should end where the arrow DEPARTS, both at `y`, never at
 * some padded row-band edge above/below it (that would be the "assume y ± ROW_H/2" shortcut this
 * function's own call site warns against). [Seq3SelfLoopRow] is the one row with genuine vertical
 * extent of its own — its loop drops from `y` down to `loopBottomY` — so only that case reports a
 * real (top, bottom) pair; every other subtype has nothing else honest to report.
 */
private fun rowVerticalExtent(geometry: Seq3RowGeometry): Pair<Double, Double> = when (geometry) {
    is Seq3SelfLoopRow -> geometry.y to geometry.loopBottomY
    else -> geometry.y to geometry.y
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
// No `else`: exhaustive on purpose (WP8) so a new Seq3Kind forces a decision here instead of
// silently inheriting the DROP_PILL terminal meant for a genuinely unresolved CALL/RETURN/ASYNC/
// SELF. NOTE never reaches buildStubRow (expandForLayout intercepts it into Emission.Note first).
private fun seq3StubTerminalFor(kind: Seq3Kind): Seq3StubTerminal = when (kind) {
    Seq3Kind.LOST -> Seq3StubTerminal.LOST
    Seq3Kind.FOUND -> Seq3StubTerminal.FOUND
    // WP10: an unresolved CREATE/DESTROY (no target lifeline picked yet) is the exact same
    // "needs target" state a not-yet-targeted CALL/RETURN/ASYNC is in — it still needs a real
    // lifeline to construct/destroy, it just hasn't been given one. Not LOST/FOUND's bucket: those
    // are RESOLVED facts with a permanently unobservable other end (Seq3Kind's own doc); an
    // unresolved CREATE/DESTROY is the opposite — it WILL get a concrete `toLifelineId`, it just
    // doesn't have one yet, so it reads as amber/pending exactly like the others below.
    Seq3Kind.CALL, Seq3Kind.RETURN, Seq3Kind.ASYNC, Seq3Kind.SELF, Seq3Kind.NOTE, Seq3Kind.CREATE, Seq3Kind.DESTROY -> Seq3StubTerminal.DROP_PILL
}

private fun buildStubRow(e: Emission.Stub, req: RowRequirement, lifelineIndex: Map<String, Int>, centers: DoubleArray, y: Double): BuiltRow? {
    val idx = lifelineIndex[e.fromLifelineId] ?: return null
    val fromX = centers[idx]
    val terminal = seq3StubTerminalFor(e.kind)
    // FOUND is the deliberate mirror of DROP_PILL/LOST's leftward layout, not a reuse of it: the
    // WP7 comment above this function (see its own header on why an ordinary unresolved stub
    // extends LEFT — "the tag's own lifeline reads as the CALLEE") doesn't apply to FOUND, whose
    // whole point is the opposite reading ("an external stimulus arrived HERE"). Extending right
    // keeps a found terminal visually distinct from a lost one at a glance instead of the two
    // differing only in fill color, and stops the two from ever literally overlapping when a
    // document has both kinds on adjacent rows of the same lifeline.
    val extendRight = terminal == Seq3StubTerminal.FOUND
    val stubEndX = if (extendRight) fromX + STUB_W else fromX - STUB_W
    val pillWidth = req.labelWidth.coerceAtLeast(1.0) + 2 * PILL_PAD_H
    val labelBox: Seq3Box
    val pill: Seq3Box
    if (extendRight) {
        val leftAlignX = stubEndX + LABEL_PAD
        labelBox = Seq3Box(leftAlignX, y - ROW_H / 2, req.labelWidth, ROW_H / 2)
        pill = Seq3Box(leftAlignX, y + STUB_LABEL_PILL_GAP, pillWidth, PILL_H)
    } else {
        val rightAlignX = stubEndX - LABEL_PAD
        labelBox = Seq3Box(rightAlignX - req.labelWidth, y - ROW_H / 2, req.labelWidth, ROW_H / 2)
        pill = Seq3Box(rightAlignX - pillWidth, y + STUB_LABEL_PILL_GAP, pillWidth, PILL_H)
    }
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
        terminal,
    )
    val pitch = ROW_H / 2 + STUB_LABEL_PILL_GAP + PILL_H + ROW_H / 2
    // Unlike the old right-pointing stub, a leftward (DROP_PILL/LOST) row no longer extends past
    // its own lifeline's center on the right — its rightmost touched x is simply fromX. A FOUND
    // row is the one exception, deliberately: it extends right, so ITS rightmost touched x is the
    // far end of the stub, not fromX (rowXExtent already reports both fromX and stubEndX for
    // fragment/bounds purposes regardless of which is numerically larger).
    return BuiltRow(row, pitch, if (extendRight) stubEndX else fromX)
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
        result += fragmentBoxFrom(clamped.fragment, clamped.range, depth, rows, minTop, firstRowIndex)
    }
    return result
}

// ── WP6: fragment operand dividers ──────────────────────────────────────────────────────────
//
// Only ALT/PAR/CRITICAL are UML combined-fragment operators whose second-and-later
// InteractionOperand gets a divider at all — UML gives OPT/LOOP/BREAK exactly one operand (no
// `else`), and GROUP isn't a UML operator to begin with (see [Seq3FragmentKind]'s own doc). This is
// deliberately the SAME three kinds `Seq3Emitters.mermaidFragmentDividerLine`/
// `plantUmlFragmentDividerLine` gate their per-dialect KEYWORD lookup on, but the canvas is not a
// dialect: PlantUML happens to have no `critical` divider word to fall back to (that emitter simply
// returns null for CRITICAL, folding the operand's messages into the preceding branch in TEXT), but
// the operand itself is real UML and genuinely exists in the document, so the canvas still draws
// its bracket regardless of which dialect a user might later export to. If this set and the
// emitters' per-kind table ever disagreed about WHICH kinds admit a divider at all (as opposed to
// what word each dialect spells it with), that would be real drift; this set exists so the two can
// only ever disagree about vocabulary, never about which kinds have dividers in the first place.
private val DIVIDER_FRAGMENT_KINDS = setOf(Seq3FragmentKind.ALT, Seq3FragmentKind.PAR, Seq3FragmentKind.CRITICAL)

/** [Seq3Operand.startsAtMessageId]/[Seq3Operand.startsAtOccurrenceEntryId] resolved to a ROW index
 *  — this file's OWN index space, [rows] itself (only successfully built rows, i.e. resolved
 *  lifelines — see [buildRows]' own doc), not `Seq3Emitters`' emission-index space. Mirrors
 *  [fragmentRowIndices]'s own `exact`-vs-message-id split, one field at a time instead of a whole
 *  fragment: a pinned occurrence resolves via the identical row-list scan for the exact
 *  (messageId, entryId) pair that function's `exact` branch already does for a
 *  [Seq3OccurrenceRef]; the un-pinned fallback is [firstRowIndex] — [Seq3Operand
 *  .startsAtMessageId]'s own documented contract ("begins at the FIRST drawn row of its anchor
 *  message") is the exact same contract `Seq3Emitters.operandAnchorIndex` resolves via
 *  `plan.firstIndexByMessage`, just against this file's row-index space instead of that file's
 *  emission-index space — the two can never disagree about WHICH row an un-pinned operand starts
 *  at, only about which index-numbering scheme names it. A dangling anchor (a message id absent
 *  from the document, or an entryId that never got drawn) resolves to null — "drop this divider,
 *  never crash" is [Seq3Operand]'s own documented contract, the same one [Seq3Delay]'s anchor
 *  already follows. */
private fun resolveOperandAnchorRowIndex(
    operand: Seq3Operand,
    firstRowIndex: Map<String, Int>,
    rows: List<Seq3RowGeometry>,
): Int? =
    operand.startsAtOccurrenceEntryId
        ?.let { entryId ->
            rows.indexOfFirst { row -> row.messageId == operand.startsAtMessageId && row.occurrenceEntryId == entryId }
                .takeIf { it >= 0 }
        }
        ?: firstRowIndex[operand.startsAtMessageId]

/**
 * Resolves [fragment]'s [Seq3Fragment.elseOperands] to [Seq3FragmentDivider]s for one already-
 * CLAMPED bracket [range] — same trap `Seq3Emitters.operandDividersByAnchor`'s own "THE TRAP" doc
 * describes: [range] here is [layoutFragments]' CLAMPED range (the output of its own clamp-to-
 * parent walk, kept a THIRD time in this file — see that function's header), never a fragment's
 * raw, un-clamped [fragmentRowIndices] bounds. Resolving against the raw bounds would let a
 * crossing fragment's divider land past its own CLAMPED bottom edge — drawn below the bracket that
 * is supposed to contain it. Dedicated coverage: `aDividerOnACrossingFragmentIsClampedAwayFromTheLayout`
 * in Seq3LayoutTest.kt (and see that test's own comment for the exact fixture that trips this).
 *
 * Applies the same four rules `operandDividersByAnchor` applies on the emitter side, just producing
 * a row `y` instead of a text line:
 *  - an unresolved anchor drops the divider ([resolveOperandAnchorRowIndex] returning null);
 *  - an index outside the CLAMPED [range] drops it (the crossing-fragment trap above);
 *  - an index equal to [range].first is operand zero's own row — no divider belongs there, since
 *    the fragment's own box/label already carries operand zero's guard;
 *  - ties are kept, not deduplicated: two operands resolving to the same row are both real user
 *    intent (e.g. a guard describing an intentionally empty branch immediately followed by
 *    another) — [Seq3Fragment.elseOperands]' own declared order survives as the deterministic
 *    tiebreak via [List.sortedBy]'s stable sort, same as the emitter side relies on.
 * Gated on [DIVIDER_FRAGMENT_KINDS] — see that set's own doc for why the canvas gates on kind
 * alone, never per-dialect.
 */
private fun fragmentDividers(
    fragment: Seq3Fragment,
    range: IntRange,
    firstRowIndex: Map<String, Int>,
    rows: List<Seq3RowGeometry>,
): List<Seq3FragmentDivider> {
    if (fragment.kind !in DIVIDER_FRAGMENT_KINDS) return emptyList()
    return fragment.elseOperands
        .mapNotNull { operand ->
            val index = resolveOperandAnchorRowIndex(operand, firstRowIndex, rows) ?: return@mapNotNull null
            if (index !in range) return@mapNotNull null
            if (index == range.first) return@mapNotNull null
            // index > range.first (dropped above) and range.first >= 0, so index - 1 is always a
            // valid, already-built row — never the row before the fragment's own first row.
            val prevY = rows.getOrNull(index - 1)?.y ?: return@mapNotNull null
            val curY = rows.getOrNull(index)?.y ?: return@mapNotNull null
            index to Seq3FragmentDivider(operand.guard, (prevY + curY) / 2.0)
        }
        .sortedBy { it.first }
        .map { it.second }
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
private fun fragmentBoxFrom(
    fragment: Seq3Fragment,
    range: IntRange,
    depth: Int,
    rows: List<Seq3RowGeometry>,
    minTop: Double,
    firstRowIndex: Map<String, Int>,
): Seq3FragmentBox {
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
        fragmentDividers(fragment, range, firstRowIndex, rows),
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
