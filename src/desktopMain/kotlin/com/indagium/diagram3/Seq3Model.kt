package com.indagium.diagram3

// ── UML sequence-diagram v3 model ───────────────────────────────────────────────────────────
//
// Everything below is a data class or enum — no behaviour, matching model/Model.kt's own
// convention. This is a FRESH model, not a v1-v5 compatibility layer over `diagram/DiagramModel.kt`
// (that package is deleted in phase 6 of the v3 rewrite — see
// docs/plans/use-the-claude-design-mcp-compiled-lighthouse.md): no `interactions` list, no
// `editorVersion` discriminator, no override machinery.
//
// The core reframe (the design spec's own words): a panel row is not a log line and not a rule —
// it is a MESSAGE, `from → to : label`, backed by *n* real log occurrences. [Seq3Message] is that
// row; [Seq3Occurrence] is one piece of its evidence.
//
// Deliberately UI-free, exactly like `diagram/DiagramModel.kt`: this package must run headless
// from the export path and the MCP handler, neither of which has a Compose composition (see
// docs/SAAD.md §9.7). Colors, if this package ever needs one, are plain ARGB Ints — never
// androidx.compose.ui.graphics.Color.

/** A tag participant list longer than this stops being a readable diagram regardless of how it
 *  was produced — see Seq3Generator's lifeline ranking. Mirrors
 *  `diagram/SeqDiagramBuilder.kt`'s `DEFAULT_MAX_AUTO_PARTICIPANTS`. */
const val DEFAULT_SEQ3_MAX_LIFELINES = 8

/** Default `×n` collapse threshold for [Seq3Repeat.COLLAPSE_ABOVE] — "collapse above 3" per the
 *  design spec's §03 table. */
const val DEFAULT_SEQ3_REPEAT_THRESHOLD = 3

// ── Lifelines ────────────────────────────────────────────────────────────────────────────────

/** Which UML glyph a lifeline's header draws: the default rounded participant box, or a stick
 *  figure for [ACTOR] (spec §07's "actor vs participant"). Purely a rendering/export-keyword
 *  choice (Mermaid/PlantUML emit `actor` instead of `participant` — see Seq3Emitters, WP2) — it
 *  never affects message routing, merge, or lifeline identity. */
enum class Seq3LifelineKind { PARTICIPANT, ACTOR }

/** One column on the canvas. A freshly generated lifeline owns exactly one raw log tag
 *  ([tagIds] is a singleton); more than one only after a user explicitly merges two lifelines
 *  that turned out to be the same actor under two tags (Seq3Queue, phase 2 — not built here). */
data class Seq3Lifeline(
    val id: String,
    val name: String,
    val tagIds: Set<String>,
    /** Display/column order. Lower sorts first; a stable id-based tiebreak lives with whatever
     *  sorts this list, not here. */
    val ordinal: Int,
    /** Hiding a lifeline is independent from message authoring and keeps its represented tags. */
    val visibility: Seq3Visibility = Seq3Visibility.VISIBLE,
    /** Actor vs participant glyph — see [Seq3LifelineKind]'s own doc. Deliberately has NO
     *  document-level default the way [displaySegments] does: every lifeline starts as
     *  [Seq3LifelineKind.PARTICIPANT] independent of its siblings, because the point of ACTOR is
     *  to call out the one or two human/external participants in an otherwise-participant
     *  diagram, not to flip the whole diagram's default glyph. */
    val kind: Seq3LifelineKind = Seq3LifelineKind.PARTICIPANT,
    /** Per-lifeline override of how many trailing dot-separated segments of [name] the header
     *  shows: null inherits [Seq3Document.lifelineDisplaySegments], 0 keeps the full name, and a
     *  positive n keeps the last n segments (`com.mycompany.myapp.Example1` at n=1 -> `Example1`).
     *  See [seq3DisplayName], the pure resolver both renderers and the panel call. */
    val displaySegments: Int? = null,
)

// ── Match / capture ──────────────────────────────────────────────────────────────────────────

/** Where one capture's value came from — mirrors `diagram.ManualCaptureSource`, kept as its own
 *  type here rather than reused so `diagram3` never imports from `diagram` (see Seq3Tokenizer's
 *  own header for why). [NAMED_VALUE] came from a `key=value`/`key: value` run whose key supplied
 *  the capture's name; [POSITIONAL_RUN] came from an anonymous varying substring whose name had to
 *  be generated (see Seq3Tokenizer); [AUTHOR] is reserved for a capture a user names by hand in a
 *  later phase — nothing in this package produces it yet. */
enum class Seq3CaptureSource { NAMED_VALUE, POSITIONAL_RUN, AUTHOR }

/** One named `{token}` slot inside a [Seq3Match.template]. */
data class Seq3Capture(
    val name: String,
    val source: Seq3CaptureSource,
)

/** A source-independent pattern proven against every occurrence it was compiled from (see
 *  Seq3Tokenizer.tokenizeSeq3Messages). [template] holds literal text with `{name}` slots at the
 *  varying runs — a template with an empty [captures] list is a literal match: every occurrence
 *  shares byte-for-byte identical [Seq3Occurrence.text]. [tag] is carried here (not just on the
 *  owning message) so a match stays self-describing if it is ever inspected outside a
 *  [Seq3Message] — it is always equal to the owning message's `fromLifelineId`'s represented tag. */
data class Seq3Match(
    val tag: String,
    val template: String,
    val captures: List<Seq3Capture> = emptyList(),
)

// ── Evidence ─────────────────────────────────────────────────────────────────────────────────

/** One real log line backing a [Seq3Message]. Evidence is append-only and never user-editable —
 *  the queue/inspector (phase 2+) only ever read this list, never write to it. [entryId] is
 *  load-bearing: it is what makes a diagram note's arrow clickable back into the log via
 *  `AppState.navigateToLogLine`, exactly like `diagram.DiagramMessage.entryId` today. */
data class Seq3Occurrence(
    val entryId: Int,
    /** Parsed `LogEntry.ts` in millis-of-day, or null for a brief/RAW row with no parseable
     *  timestamp — see `utils.parseMillisOfDay`'s own `TS_UNKNOWN` doc. */
    val timestampMillis: Long?,
    val rawTimestamp: String,
    val pid: Int,
    val tid: Int,
    /** `LogEntry.level.key` — a bare Char so this file never needs to import `model.LogLevel`
     *  for what is, here, just a display glyph. */
    val level: Char,
    val text: String,
    /** This occurrence's values for every [Seq3Capture] its message's [Seq3Match] declares. */
    val captureValues: Map<String, String> = emptyMap(),
    /** Per-occurrence display flag. Unlike a message hide, this never affects sibling evidence. */
    val visibility: Seq3Visibility = Seq3Visibility.VISIBLE,
)

/** Stable reference used by occurrence-level commands. */
data class Seq3OccurrenceRef(
    val messageId: String,
    val entryId: Int,
)

// ── Message ──────────────────────────────────────────────────────────────────────────────────

/** Arrow kind. [NOTE] is a message that renders as a canvas/text note anchored on
 *  [Seq3Message.fromLifelineId] rather than as an arrow at all — [Seq3Message.toLifelineId] is
 *  meaningless for it and emitters must not treat a null target on a NOTE message as
 *  needs-target.
 *
 *  [LOST] and [FOUND] are UML's own answer to "sender known, receiver not observable" (and its
 *  mirror, "receiver known, sender not observable") — appended last so `Seq3QueuePanel`'s
 *  positional `SegmentedControl` over `entries` keeps CALL/RETURN/ASYNC/SELF/NOTE at their
 *  existing indices. Exactly like [NOTE], both use ONLY [Seq3Message.fromLifelineId];
 *  [Seq3Message.toLifelineId] is meaningless for them and is never treated as needs-target
 *  (see [Seq3Message.state]). [LOST] draws from `fromLifelineId` out to a filled circle — "this
 *  component sent something; we cannot see who received it." [FOUND] draws from a filled circle
 *  in to `fromLifelineId` — "something outside the captured system triggered this component."
 *  Reading `fromLifelineId` as the *receiver* for FOUND (rather than adding a nullable source
 *  field) is what keeps both kinds inside the existing one-endpoint model. */
enum class Seq3Kind { CALL, RETURN, ASYNC, SELF, NOTE, LOST, FOUND, CREATE, DESTROY }

/** How a run of [Seq3Message.occurrences] draws on the canvas/in exported text.
 *  [Seq3Message.repeatThreshold] only matters for [COLLAPSE_ABOVE] — the other two modes ignore
 *  it entirely (see Seq3Emitters). */
enum class Seq3Repeat { COLLAPSE_ABOVE, EVERY, FIRST_LAST }

/** Whether a message still follows its generated shape or has been hand-edited. A hidden message
 *  (see [Seq3Visibility]) keeps whichever of these it already had — hiding is an orthogonal
 *  display flag, not a third authoring state. */
enum class Seq3Authoring { AUTO, EDITED }

/** [HIDDEN] drops the arrow but keeps the evidence and the struck-through queue row — a separate
 *  flag from [Seq3Authoring], never a state of its own (see the design spec's §03 table: "Hidden
 *  is a separate visibility flag, not a state"). */
enum class Seq3Visibility { VISIBLE, HIDDEN }

/** The queue badge shown for a message. Deliberately NOT stored on [Seq3Message] — see
 *  [Seq3Message.state]'s own doc for why deriving it beats persisting a copy that can drift. */
enum class Seq3State { AUTO, EDITED, NEEDS_TARGET }

/** Pins one message's order against a same-timestamp neighbour. Only meaningful when two
 *  messages' first occurrence genuinely tie on [Seq3Occurrence.timestampMillis] — the design
 *  spec's §07 "Pin appears only when two messages share a timestamp". */
data class Seq3OrderPin(
    val tiedTimestampMillis: Long,
    val tieRank: Int,
)

/**
 * The editable durable unit — one panel row / one canvas arrow, backed by *n* log occurrences.
 *
 * [fromLifelineId] is never null: the design spec calls `From` reliable because it IS the tag the
 * occurrences were scanned under. [toLifelineId] is the one field generation cannot always prove;
 * a null value IS the needs-target condition (see [state]) and must never be defaulted to a
 * fabricated/synthetic lifeline — see Seq3Generator's own header for why.
 */
data class Seq3Message(
    val id: String,
    val match: Seq3Match,
    val fromLifelineId: String,
    val toLifelineId: String?,
    /** What the arrow reads. Holds the same `{name}` slots as [match.template] — renaming a
     *  capture-bearing label once renames every occurrence's rendered text (Seq3Emitters
     *  substitutes real capture values back in only for a per-occurrence, uncollapsed arrow). */
    val labelTemplate: String,
    val kind: Seq3Kind = Seq3Kind.CALL,
    val repeat: Seq3Repeat = Seq3Repeat.COLLAPSE_ABOVE,
    val repeatThreshold: Int = DEFAULT_SEQ3_REPEAT_THRESHOLD,
    val visibility: Seq3Visibility = Seq3Visibility.VISIBLE,
    val authoring: Seq3Authoring = Seq3Authoring.AUTO,
    /** Set only on a standalone row created by Move out, so Move back can target its exact
     * original group instead of guessing from a compatible label. */
    val movedOutFromMessageId: String? = null,
    val orderPin: Seq3OrderPin? = null,
    val occurrences: List<Seq3Occurrence> = emptyList(),
    /** Optional author-supplied timeline timestamp. When present it overrides the first evidence
     *  timestamp for a manually-authored message, while leaving immutable log evidence untouched. */
    val manualTimestampMillis: Long? = null,
    /** The timestamp text entered by the author, if it cannot or should not be normalized to millis. */
    val manualRawTimestamp: String = "",
    /** Appended LAST (see CLAUDE.md's "append-last token/field versioning" invariant — inserting a
     *  field anywhere else in this class or in `Seq3Codec`'s field handling breaks every existing
     *  autosave and saved note). Null means "[occurrences] is complete" — every document ever
     *  written before this field existed decodes unchanged. Non-null records the TRUE occurrence
     *  count `Seq3Generator`'s document-wide water-fill budget measured BEFORE trimming
     *  [occurrences] down to a first/last window, so a consumer that wants "how many times did this
     *  really happen" (the `×n` badge, an elision row) can still report the honest number even
     *  though the persisted evidence list itself is smaller. Consumers that instead want "how many
     *  rows do I have evidence to draw" keep reading `occurrences.size` — see Seq3Generator.kt's own
     *  header for the exact line between the two. */
    val totalOccurrenceCount: Int? = null,
) {
    /** True for an author-created message without log evidence. Generated messages always retain
     *  at least one occurrence, so this is the durable distinction used by custom-only actions. */
    val isCustom: Boolean
        get() = occurrences.isEmpty()

    /** Timeline value shared by queue sorting, canvas layout, and authored-message editing. */
    val primaryTimestampMillis: Long?
        get() = manualTimestampMillis ?: occurrences.firstOrNull()?.timestampMillis

    /** Human-readable timestamp with the authored override taking precedence when present. */
    val primaryRawTimestamp: String
        get() = manualRawTimestamp.ifBlank { occurrences.firstOrNull()?.rawTimestamp.orEmpty() }

    /**
     * Design decision: [Seq3State] is deliberately NOT a stored field, even though the design
     * spec's §03 table lists "state" as one of the things a message carries. `NEEDS_TARGET` is
     * fully determined by `toLifelineId == null` (the spec says exactly this: "a null `toLifelineId`
     * IS the needs-target condition"), and a message resolving its target must always stop
     * counting toward "needs target" in the same instant `toLifelineId` becomes non-null — a
     * persisted `state` field could only do that if every mutator remembered to keep the two in
     * sync, and this codebase has a documented scar from exactly that kind of drift (see
     * `model/Model.kt`'s own note on `LogAnalysis.pending`). Deriving it here means it can't drift.
     */
    val state: Seq3State
        get() = if (toLifelineId == null && kind != Seq3Kind.NOTE && kind != Seq3Kind.LOST && kind != Seq3Kind.FOUND) {
            // A NOTE renders anchored on `fromLifelineId` and has no target BY DEFINITION (see
            // [Seq3Kind.NOTE]), so a null target on one is not a defect to be queued. LOST and
            // FOUND are the same story: their null `toLifelineId` is not an unresolved defect,
            // it's the honest UML shape (lost/found message) — a message whose receiver/sender
            // genuinely isn't in the captured range. Without this guard every one of them would
            // inflate the "N messages need a target" banner and enter the guided pass as a row
            // the pass structurally cannot resolve.
            Seq3State.NEEDS_TARGET
        } else if (authoring == Seq3Authoring.EDITED) {
            Seq3State.EDITED
        } else {
            Seq3State.AUTO
        }
}

/** Where a manually-authored message is inserted in the document's canonical message list. The
 *  list itself remains the durable queue order; timestamps additionally control chronological canvas
 *  placement when they are available. */
sealed class Seq3InsertionPosition {
    data object Start : Seq3InsertionPosition()

    data object End : Seq3InsertionPosition()

    data class AtIndex(val index: Int) : Seq3InsertionPosition()

    data class BeforeMessage(val messageId: String) : Seq3InsertionPosition()

    data class AfterMessage(val messageId: String) : Seq3InsertionPosition()
}

/** Explicit author input for a custom message. Lifeline values are IDs from [Seq3Document.lifelines],
 *  not display names, so a renamed lifeline does not make an existing custom message ambiguous. */
data class Seq3CustomMessageSpec(
    val fromLifelineId: String,
    val toLifelineId: String?,
    val text: String,
    val timestampMillis: Long? = null,
    val rawTimestamp: String = "",
    val position: Seq3InsertionPosition = Seq3InsertionPosition.End,
    val kind: Seq3Kind = Seq3Kind.CALL,
    val repeat: Seq3Repeat = Seq3Repeat.EVERY,
    /** Existing semantic fragment to include this message in, e.g. an OPT/ALT section. */
    val fragmentId: String? = null,
)

sealed class Seq3CustomMessageResult {
    data class Added(
        val document: Seq3Document,
        val newMessageId: String,
        val insertionIndex: Int,
    ) : Seq3CustomMessageResult()

    data class Rejected(val reason: String) : Seq3CustomMessageResult()
}

sealed class Seq3MessageEditResult {
    data class Updated(val document: Seq3Document) : Seq3MessageEditResult()

    data class Rejected(val reason: String) : Seq3MessageEditResult()
}

// ── Fragments / notes ───────────────────────────────────────────────────────────────────────

/** The UML fragment shape a selection is grouped into (design spec §06's `Group ▾` verb). Unlike
 *  the old `diagram.DiagramFrame` (a colorless auto-detected bracket with no semantic meaning),
 *  every one of these IS semantic — a user explicitly chose it — so Seq3Emitters' PlantUML branch
 *  renders the dialect's real `kind.name.lowercase()` block for every one of them, GROUP included
 *  (see that constant's own paragraph below), instead of a meaning-free note pairing.
 *
 *  [GROUP] is the one member that is **not** a UML 2.x combined-fragment operator at all — added
 *  for "frame these messages and say what they relate to" (WP12). UML defines exactly twelve
 *  combined-fragment operators (`seq, alt, opt, break, par, strict, loop, critical, neg, assert,
 *  ignore, consider`) and none of them means "these messages relate to X". PlantUML invented
 *  `group <label>` for exactly this case and Seq3Emitters' PlantUML branch reuses that verbatim.
 *
 *  [NEG], [STRICT], [CONSIDER] and [IGNORE] (WP11) ARE four of those twelve real UML operators —
 *  unlike GROUP, PlantUML needs no special case for them either, since `kind.name.lowercase()`
 *  already produces PlantUML's own `neg`/`strict`/`consider`/`ignore` keyword. Two of the four are
 *  unexpectedly well suited to a *log* diagram specifically, not just UML completeness: [NEG]
 *  frames an error path semantically ("this must not happen") instead of leaving it to adjacent
 *  prose, and [CONSIDER] ("this interaction only accounts for these message types") is literally
 *  what a filtered log diagram already is — it lets a diagram attached to a ticket admit its own
 *  lossiness to a reader who never saw the original log. [STRICT] and [IGNORE] round out UML's
 *  twelve without a log-specific case of their own.
 *
 *  Mermaid, though, has a real grammar to satisfy, and its sequence-diagram syntax has keywords for
 *  only six operators (`loop, alt/else, opt, par/and, critical/option, break`) plus `rect` — a bare
 *  `group`/`neg`/`strict`/`consider`/`ignore` is a Mermaid PARSE ERROR, not a degraded rendering.
 *  Seq3Emitters' Mermaid branch therefore fakes ALL FIVE of GROUP/NEG/STRICT/CONSIDER/IGNORE the
 *  same way: `rect rgb(...) … end` wrapping a `Note over` that carries the label (GROUP) or the
 *  operator word plus label (the four real operators — the word must survive the degradation, or a
 *  reader loses the one thing that made picking NEG/CONSIDER over LOOP/GROUP meaningful) — see that
 *  file's own "Fragment open lines" section for why this needs its own per-dialect branch instead
 *  of the `kind.name.lowercase()` call the other six kinds share unmodified.
 *
 *  [REF] (WP17) is UML's `InteractionUse` — "this region is detailed in another diagram", the
 *  collapse that keeps a 150-message diagram readable. It is neither of the two buckets above:
 *  it IS a real UML 2.x concept (unlike GROUP), but unlike NEG/STRICT/CONSIDER/IGNORE its real
 *  PlantUML syntax is NOT `kind.name.lowercase()` + label — PlantUML's actual grammar is
 *  `ref over A, B : label` (confirmed against plantuml.com's own sequence-diagram documentation,
 *  the same rigour WP11 applied to Mermaid's jison grammar; PlantUML publishes no public formal
 *  grammar file the way mermaid-js does, so its own docs are the best available primary source —
 *  see Seq3Emitters.kt's `plantUmlFragmentOpenLines` for the special case this forces, and its
 *  `toPlantUml` for why closing this bracket must NOT emit `end`: `ref over` is a standalone
 *  statement in real PlantUML, never a block that encloses other statements, so writing an `end`
 *  after one would be an unmatched, unparseable token). Mermaid has no `ref` keyword at all, so
 *  it joins the GROUP/NEG/STRICT/CONSIDER/IGNORE fallback set — see
 *  `MERMAID_FALLBACK_FRAGMENT_KINDS`'s own doc.
 *
 *  [Seq3Fragment.refDiagramId] carries WHICH diagram this points at; this enum member only marks
 *  the shape. Deliberately out of scope for this fragment kind (see that field's own doc): making
 *  a REF box HIDE the region it brackets, the way UML tooling sometimes collapses the referenced
 *  interaction away — that is a bigger UX question (does hiding move the messages, just the
 *  arrows, what does undo look like) than "add a fragment kind that points elsewhere", and is left
 *  for a future work package to decide deliberately rather than accreting as a side effect here. */
enum class Seq3FragmentKind { LOOP, ALT, OPT, PAR, CRITICAL, BREAK, GROUP, NEG, STRICT, CONSIDER, IGNORE, REF }

/** One `else`-divided branch of a combined fragment — a UML InteractionOperand, minus operand
 *  zero (see [Seq3Fragment.elseOperands] for why operand zero is not represented by this type).
 *
 *  Deliberately does **not** carry its own `messageIds`. [Seq3Fragment.messageIds] is documented
 *  as needing no physical contiguity — a fragment is a *span*, and its membership a *set* — but
 *  UML operands are an **ordered partition** of that span: `alt` divides its bracket into
 *  contiguous, sequential branches. If operand A held messages at emission indices {3, 7} and
 *  operand B held {5, 9}, there would be no valid divider position between them — the branches
 *  would interleave. A per-operand membership set can therefore express a state no renderer can
 *  draw. An anchor cannot: it resolves to exactly one index inside the span, or to nothing at all.
 *
 *  Anchor resolution follows [Seq3Delay]'s contract exactly: an anchor that no longer names a
 *  drawn row drops THIS divider and folds its guard's region into the preceding operand, rather
 *  than failing the fragment or the document — WP5/WP6 own that resolution logic; this type only
 *  stores the anchor. */
data class Seq3Operand(
    val id: String,
    val guard: String,
    /** This operand begins at the first drawn row of this message. */
    val startsAtMessageId: String,
    /** Pins the divider to one exact occurrence of a repeated message — same reasoning, and the
     *  same "null means the first/only occurrence" default, as [Seq3Delay.afterOccurrenceEntryId]. */
    val startsAtOccurrenceEntryId: Int? = null,
)

/** A labelled fragment box spanning the named messages. [messageIds] need not be a physically
 *  contiguous run of [Seq3Document.messages] — the bracket is drawn from the earliest to the
 *  latest referenced message, same as the old `DiagramFrame`'s bracket-around-a-range approach.
 *
 * [occurrenceRefs] is used when a canvas selection targets individual drawn occurrences of a
 * repeated message. In that case the fragment must not expand to every occurrence owned by the
 * same queue message. When present, occurrence references take precedence over [messageIds] for
 * their message IDs. */
data class Seq3Fragment(
    val id: String,
    val kind: Seq3FragmentKind,
    val label: String,
    val messageIds: List<String>,
    val occurrenceRefs: List<Seq3OccurrenceRef> = emptyList(),
    /** Same "drop the box/arrow but keep the row" meaning as [Seq3Message.visibility] — a hidden
     *  fragment's bracket is skipped by [Seq3Layout]/export (WP2) but the fragment itself, and
     *  every message it groups, is untouched. */
    val visibility: Seq3Visibility = Seq3Visibility.VISIBLE,
    /** WP12: when true, the canvas overlay shows just [label] instead of `"$kind: $label"`. A
     *  per-fragment (not document-level) flag on purpose — the user framed this as a property of
     *  the individual artifact ("or to not show what is it"), and a [GROUP] fragment in particular
     *  is meaningless with its operator word shown since "group" says nothing about what the
     *  messages relate to. Canvas-presentation only: [Seq3Layout]/[Seq3Raster] already draw the
     *  bare label with no kind prefix, so this has no effect there or on the emitted text. */
    val hideKindLabel: Boolean = false,
    /** WP4: the `else` branches after the first, for a combined fragment with two or more UML
     *  InteractionOperands (`alt`/`else`, `par`/`and`-or-`else`, `critical`/`option` — see the
     *  "which kinds" paragraph below). [label] is **operand zero's guard** — it always was, in
     *  UML terms: `alt <label>` already puts the label exactly where UML puts the first operand's
     *  guard. This field therefore holds only what comes *after* that: an absent/empty list is not
     *  a migration case to handle, it is the honest, exact description of every fragment ever
     *  written before this field existed — a single-operand fragment, which renders identically to
     *  today. That is a stronger backward-compatibility guarantee than the `totalOccurrenceCount` /
     *  `elidedMessageCount` precedents, which each needed a "null/absent means X" convention on
     *  top of the bare default — here the bare `emptyList()` default already *is* the correct
     *  historical meaning, nothing further to reconcile.
     *
     *  The one accepted cost: operand zero's guard is edited through the existing
     *  `SetFragmentLabel` command, while every operand in this list gets its own command in WP7 —
     *  an intentional asymmetry, not an oversight. The alternative — folding operand zero into
     *  this list too, as a full `operands: List<Seq3Operand>` — would need a [Seq3Operand] whose
     *  anchor is meaningless (there is nothing before the first operand to anchor it after),
     *  i.e. a permanently unrepresentable-but-typeable state. Keeping [label] as operand zero's
     *  guard is exactly what avoids that.
     *
     *  Which kinds this is meaningful for is a UML rule, deliberately **not enforced here**: UML
     *  gives `OPT`, `LOOP`, `BREAK` exactly one operand (no `else`), and [Seq3FragmentKind.GROUP]
     *  is not a UML combined-fragment operator at all (see that enum's own doc), so
     *  [elseOperands] is only ever rendered for `ALT`, `PAR`, `CRITICAL` — WP5's emitters and
     *  WP7's UI are the ones that gate on kind. A `SetFragmentKind` that moves a fragment away
     *  from one of those three and back (e.g. `ALT` -> `LOOP` -> `ALT`) preserves [elseOperands]
     *  across the round trip rather than clearing it — "keep the data, drop the drawing" is the
     *  rule this whole package already follows for [visibility] and [hideKindLabel], and a kind
     *  change that quietly discards a user's typed guards the moment they pick the wrong operator
     *  first would be a worse experience than briefly rendering operands that don't apply yet. */
    val elseOperands: List<Seq3Operand> = emptyList(),
    /** WP17: which saved diagram a [Seq3FragmentKind.REF] box points at — a
     *  [com.indagium.ui.DiagramLibraryItem.id] (a stable UUID minted by
     *  `ui/DiagramLibraryStore.kt`), the SAME id [Seq3AttachmentMetadata.diagramId] already stores
     *  and [com.indagium.ui.Seq3Session.openLibraryItem] already knows how to follow — this field
     *  reuses that id-following machinery rather than inventing a second one. Meaningless for
     *  every other kind and never read by them. Null covers three cases identically, on purpose,
     *  none of which is an error: every fragment kind but REF, a REF the user hasn't picked a
     *  target for yet, and (this field's own contract, matching [Seq3Delay]/[visibility]'s
     *  "a dangling reference draws, never crashes" rule) a REF whose target was later deleted from
     *  the library. `Seq3Layout`/`Seq3Raster`/the Mermaid and PlantUML emitters never read this
     *  field at all — the bracket and label are drawn from [kind]/[label]/[messageIds] exactly
     *  like any other fragment (see [Seq3FragmentKind.REF]'s own doc: a REF fragment brackets its
     *  messages, it does not hide them), so a dangling or absent id can never fail layout or
     *  export; only the interactive canvas overlay (`ui/Seq3Canvas.kt`) resolves it against the
     *  library, and only to decide what a click does and whether to show a "missing" affordance —
     *  a resolution failure there is a display choice, not a crash. Appended LAST — this file's own
     *  versioning rule (see [elseOperands]' own doc for why that rule matters here too); absent on
     *  every fragment written before this field existed -> decodes to null -> byte-identical
     *  rendering to today. */
    val refDiagramId: String? = null,
)

/** A canvas/text note spanning a selection of messages (design spec §06's `Note` verb) — distinct
 *  from a [Seq3Kind.NOTE] message, which is itself one queue row with its own evidence. */
data class Seq3Note(
    val id: String,
    val text: String,
    val messageIds: List<String>,
    /** Optional canvas placement. Null keeps the automatic message-span anchor. */
    val x: Double? = null,
    val y: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
    /** Same meaning as [Seq3Fragment.visibility] — see that field's own doc. */
    val visibility: Seq3Visibility = Seq3Visibility.VISIBLE,
)

// ── Time-gap markers (WP11) ─────────────────────────────────────────────────────────────────
//
// Deliberately NOT a [Seq3Kind]: a delay has no endpoints (nothing to draw an arrow between), no
// evidence (no [Seq3Occurrence] backs it — it is a pure authoring artifact, closer to [Seq3Note]
// than to [Seq3Message]), and must never enter the "N messages need a target" count or the message
// queue itself. The `+ message` dialog's kind `SegmentedControl` (`ui/Seq3QueuePanel.kt`) indexes
// [Seq3Kind.entries] POSITIONALLY, so adding a case there would silently renumber every existing
// kind button in that control — a document-level list of its own, exactly like [fragments]/[notes]
// above, sidesteps that trap entirely.

/** A labelled vertical gap in the timeline, anchored right after [afterMessageId]'s LAST drawn
 *  row by default — the design spec's "time-gap marker" (item 8) — or, when [afterOccurrenceEntryId]
 *  is set, right after that ONE specific occurrence instead. [id] is caller-generated (mirrors
 *  [Seq3Fragment.id]/[Seq3Note.id], both minted by the UI layer via `UUID.randomUUID()` before the
 *  bulk action that creates them). A delay whose [afterMessageId] no longer resolves to a visible
 *  row (the anchor message was hidden, merged away, or deleted) simply draws nothing — the same
 *  "drop the box, keep nothing to keep" contract [Seq3Fragment.visibility]/[Seq3Note.visibility]
 *  document for their own dangling references, so a stale delay is never a crash. Same contract
 *  for [afterOccurrenceEntryId]: an id that no longer names a VISIBLE occurrence of that message
 *  (hidden, or the row simply doesn't repeat that many times any more) falls back to "after the
 *  last occurrence" rather than drawing nothing — see [Seq3Layout]'s own anchoring code. */
data class Seq3Delay(
    val id: String,
    val afterMessageId: String,
    val label: String,
    /** Same meaning as [Seq3Fragment.visibility] — see that field's own doc. */
    val visibility: Seq3Visibility = Seq3Visibility.VISIBLE,
    /** User-observed correction: a delay used to always land after a message's LAST occurrence
     *  regardless of which one the canvas context menu's "Insert delay after this" was invoked
     *  on — right-clicking the FIRST of several repeated occurrences of the same message still
     *  inserted the delay after the LAST one, since [afterMessageId] alone can't tell them apart.
     *  Null (the default, and every delay created before this field existed) preserves that
     *  "after the last occurrence" behavior; a non-null [Seq3Occurrence.entryId] pins it to one
     *  exact occurrence instead, matching the row the user actually right-clicked. */
    val afterOccurrenceEntryId: Int? = null,
)

// ── State invariants (WP18) ─────────────────────────────────────────────────────────────────
//
// UML puts a `{state}` marker on a lifeline between messages: a StateInvariant, asserting what the
// participant's state is at that point. The log already carries this — a `NAMED_VALUE` capture
// (see [Seq3CaptureSource]) retains the key a `key=value` log line was parsed from, so a capture is
// a genuine candidate for one, and unlike a [Seq3CaptureSource.POSITIONAL_RUN] capture, its key is
// MEANINGFUL (it says WHAT the state is, not just that something varied there).
//
// Promotion is explicit, never auto-detected — this is the user's own decision, and it matches the
// lesson from A9 (cut precisely because a heuristic over log text mangles more than it helps: no
// "keys that look state-ish" rule lives anywhere in this package, here included). A document-level
// list, exactly like [Seq3Delay] just above it and for the identical reason: a state invariant has
// no endpoints of its own (nothing to draw an arrow between — it decorates ONE lifeline, the one
// that logged the value) and must never enter the message queue or the "N messages need a target"
// count. It is also what keeps this off the nine-site [Seq3RowGeometry] subtype tax [Seq3DelayBox]/
// [Seq3ActivationBar] (Seq3Layout.kt) already avoid for the same shape of reason — see those types'
// own doc.

/** Promotes one [Seq3Capture] of [messageId]'s [Seq3Message.match] to a UML StateInvariant, drawn
 *  as a small marker on [messageId]'s OWN [Seq3Message.fromLifelineId] — the component that logged
 *  the line is the one whose state it is, never [Seq3Message.toLifelineId] (meaningless here the
 *  same way it is for [Seq3Kind.NOTE]/[Seq3Kind.LOST]/[Seq3Kind.FOUND]).
 *
 *  [id] is caller-generated, mirroring [Seq3Delay.id]/[Seq3Fragment.id]/[Seq3Note.id] (all minted
 *  by the UI layer via `UUID.randomUUID()` before the bulk action that creates them).
 *
 *  The rendered text is the promoted capture's PER-OCCURRENCE value (`Seq3Occurrence.captureValues
 *  [captureName]`), never an authored string — that is the whole point of promoting a capture
 *  instead of just adding a note: it says something DIFFERENT at each row a repeated message draws,
 *  rather than repeating one constant. A [messageId] that no longer names a message in the document
 *  (deleted, merged away) and a [captureName] no longer present on the named message's own
 *  [Seq3Match.captures] (the pattern was edited after promotion) both draw nothing and never throw
 *  — the same "a dangling reference draws, never crashes" contract [Seq3Delay.afterMessageId]/
 *  [Seq3Fragment.refDiagramId] already document for themselves (see [Seq3Codec]'s own
 *  `occurrenceRefFromMap` posture: a malformed element drops out on DECODE rather than failing the
 *  whole document; a dangling-but-well-formed one, the case here, drops out on LAYOUT/EXPORT
 *  instead, same "never crash" outcome either way). */
data class Seq3StateInvariant(
    val id: String,
    val messageId: String,
    val captureName: String,
    /** Same meaning as [Seq3Delay.visibility] — see that field's own doc. */
    val visibility: Seq3Visibility = Seq3Visibility.VISIBLE,
)

// ── Range ────────────────────────────────────────────────────────────────────────────────────

/** How [Seq3Generator]'s `generateSeq3` selects which of the supplied entries to scan. Simplified
 *  from `diagram.DiagramRange`: no `SeqGroupRef` case (the design spec never asks for one, and the
 *  design brief for this phase explicitly says to drop it rather than port it trivially). */
sealed class Seq3Range {
    /** Every supplied entry, unbounded. */
    data object VisibleView : Seq3Range()

    /** Inclusive `LogEntry.id` bounds. Order-independent — a caller building this from a drag
     *  selection shouldn't have to know which end the user started from, so the resolver takes
     *  `minOf`/`maxOf` of the two itself. */
    data class Ids(
        val from: Int,
        val to: Int,
        /** Exact user selection; empty preserves the plain inclusive-span behaviour above. */
        val selectedIds: Set<Int> = emptySet(),
    ) : Seq3Range()

    /** Inclusive `"HH:MM:SS[.mmm]"` clock-time bounds, parsed the same way as `LogEntry.ts`
     *  everywhere else in the app (`utils.parseMillisOfDay`). Order-independent, same as [Ids].
     *  A row with no parseable `ts` of its own (brief/RAW format) inherits the previous row's
     *  timestamp, exactly like `diagram.DiagramRange.Time`'s own carry-forward rule. */
    data class Time(val fromTs: String, val toTs: String) : Seq3Range()
}

// ── Document ─────────────────────────────────────────────────────────────────────────────────

/** The whole generated-or-edited diagram. No `interactions` list and no `editorVersion`
 *  discriminator — see this file's own header for why: v3 never reads a v1/v2/v3-predecessor
 *  document, so there is nothing to discriminate against. */
data class Seq3Document(
    val title: String = "",
    /** `LogTab.filename` this document was built from, persisted purely so a later "Regenerate"
     *  (phase 2) can tell whether the attached tab is still the same log — never read by this
     *  package itself, same contract as `diagram.SeqDiagramSpec.sourceFile`. */
    val sourceFile: String? = null,
    val range: Seq3Range = Seq3Range.VisibleView,
    val lifelines: List<Seq3Lifeline> = emptyList(),
    val messages: List<Seq3Message> = emptyList(),
    val fragments: List<Seq3Fragment> = emptyList(),
    val notes: List<Seq3Note> = emptyList(),
    /** Time-gap markers (WP11) — see [Seq3Delay]'s own header for why this is a document-level
     *  list, not a [Seq3Kind]. Defaults empty so an older note decodes to its original rendering. */
    val delays: List<Seq3Delay> = emptyList(),
    /** The repeat policy newly generated messages start with; an already-[Seq3Authoring.EDITED]
     *  message's own [Seq3Message.repeat] is never overwritten by this. */
    val defaultRepeat: Seq3Repeat = Seq3Repeat.COLLAPSE_ABOVE,
    /** Diagram-wide default for [Seq3Lifeline.displaySegments] when a lifeline doesn't set its
     *  own override. 0 (full name, no shortening) matches every document's effective behaviour
     *  before this field existed, so an old note decodes with identical rendering. */
    val lifelineDisplaySegments: Int = 0,
    /** Per-diagram theme override. A plain [String] holding a `model.ThemePreset.name`, never the
     *  enum itself — this package must never import `com.indagium.model` (see this file's own
     *  header on [Seq3Occurrence.level]'s bare `Char` for the same reasoning). Null means "follow
     *  the app theme" (Settings' own diagram default, or the ambient theme if that too is unset);
     *  resolving that chain is `ui.Seq3Theme.resolveSeq3ThemeColors`'s job (WP4), not this file's. */
    val themePresetName: String? = null,
    /** Item 7 (WP10): prefix every drawn call's label with `[#n]`, counting drawn rows in canvas
     *  order — see `diagram3.Seq3LabelSummary.seq3PrefixedLabel`. Document-level, not view-only,
     *  because the user chose "canvas, PNG export, and both text dialects must all agree" — a
     *  view-only toggle could never keep an export in sync with what the panel showed when it was
     *  produced. Defaults false so an old note decodes to its original, unnumbered rendering. */
    val showSequenceNumbers: Boolean = false,
    /** Item 7 (WP10): prefix every drawn call's label with its `[HH:MM:SS.mmm]` timestamp — same
     *  export-parity reasoning as [showSequenceNumbers]. */
    val showTimestamps: Boolean = false,
    /** Appended LAST (see CLAUDE.md's "append-last field versioning" invariant). Null means
     *  "generation never dropped a message" — every document written before P3a existed, and every
     *  document generated under the message-count cap, decodes/behaves unchanged. Non-null records
     *  how many messages `Seq3Generator.generateSeq3`'s document-wide message-count cap dropped
     *  BEFORE this document was built, so the canvas status bar can report the true pre-cap total
     *  the same way [Seq3Message.totalOccurrenceCount] reports a message's true pre-trim occurrence
     *  count — see that field's own doc for the identical reasoning one level down. */
    val elidedMessageCount: Int? = null,
    /** WP1: master on/off switch for drawing UML activation bars (ExecutionSpecifications) —
     *  see `diagram3.Seq3ActivationSpan`'s own header for what a bar is and why the call/return
     *  pairing that produces one lives in a file shared by every consumer. Document-level, not
     *  view-only, for the same reason as [showSequenceNumbers]/[showTimestamps]: canvas, PNG
     *  export, and both text dialects must all agree on whether bars are present, and a view-only
     *  toggle could never keep an export in sync with what the panel showed when it was produced.
     *  Defaults false, and that default is LOAD-BEARING: WP2 (canvas/raster) and WP3 (Mermaid/
     *  PlantUML emitters) land their rendering behind this flag, so as long as it defaults off, no
     *  existing test's expected output changes the moment this field exists — every already-written
     *  note and every already-generated document decodes with activation bars off, exactly like
     *  before WP1. */
    val showActivations: Boolean = false,
    /** WP15: prefix every drawn call's label with its measured elapsed gap from the previous drawn
     *  row, e.g. `[+0.140]` — the one thing this generator can show that a hand-drawn UML diagram
     *  cannot, since it comes from the real log clock rather than being authored. Document-level,
     *  not view-only, for the same export-parity reason as [showSequenceNumbers]/[showTimestamps]/
     *  [showActivations]: canvas, PNG export, and both text dialects must all agree. Appended LAST
     *  (see CLAUDE.md's "append-last field versioning" invariant). Defaults false, and that default
     *  is LOAD-BEARING the same way [showActivations]'s is: every already-written note and every
     *  already-generated document decodes with elapsed tags off, so no existing test's expected
     *  output changes the moment this field exists. See `diagram3.Seq3LabelSummary.seq3PrefixedLabel`
     *  for where the tag is actually composed, and that function's own doc for why it is signed
     *  (`+`/`-`), never clamped. */
    val showElapsed: Boolean = false,
    /** WP18: UML StateInvariant markers, promoted from log captures by the user — see
     *  [Seq3StateInvariant]'s own doc for the whole shape and why this is a document-level list, not
     *  a [Seq3Kind]. Appended LAST (CLAUDE.md's "append-last field versioning" invariant). Defaults
     *  empty so an old note decodes to its original, marker-free rendering, exactly like [delays]. */
    val stateInvariants: List<Seq3StateInvariant> = emptyList(),
)

// ── Generation options ──────────────────────────────────────────────────────────────────────

/** Tuning knobs for `Seq3Generator.generateSeq3`. Kept here, not in Seq3Generator.kt, mirroring
 *  `diagram.DiagramOptions` living in `DiagramModel.kt` rather than `SeqDiagramBuilder.kt` — the
 *  model file is where every other data shape in this package lives. */
data class Seq3GenerateOptions(
    val title: String = "",
    val sourceFile: String? = null,
    val maxLifelines: Int = DEFAULT_SEQ3_MAX_LIFELINES,
    /** "As they are, not grouped" (design spec): a freshly generated message draws EVERY occurrence
     *  as its own arrow by default — collapsing a repeated call behind a `×n` badge is something a
     *  user opts into per-message via the Inspector, never the generator's own starting point. Only
     *  this canvas/export fan-out changes; [Seq3Tokenizer]'s occurrence-merging into one queue row
     *  is a completely separate axis and stays exactly as-is (see [Seq3Layout]'s `expandForLayout`). */
    val defaultRepeat: Seq3Repeat = Seq3Repeat.EVERY,
    val defaultRepeatThreshold: Int = DEFAULT_SEQ3_REPEAT_THRESHOLD,
    /** Same-thread (pid+tid, bounded gap) handoff evidence — see Seq3Correlation.isThreadHandoff.
     *  Not exposed as a tunable gap, same as `diagram.THREAD_HANDOFF_MAX_GAP_MS`: a caller who
     *  needs a different bound has the manual "set target" affordances instead. */
    val threadHandoffEnabled: Boolean = true,
    /** Shared correlation-token evidence between adjacent entries — see
     *  Seq3Correlation.hasSharedCorrelationToken. */
    val correlationTokenEnabled: Boolean = true,
    /** Third target-inference signal (Seq3Generator.inferTarget): a source-index-backed call trace,
     *  attempted only when the caller also supplies a non-null `SourceIndex` to `generateSeq3` — see
     *  that function's own doc. Off has zero cost (no engine constructed, no `.resolve()` call);
     *  on with no index supplied is equally a no-op, so this flag alone never triggers indexing. */
    val sourceTraceEnabled: Boolean = true,
    /** Fourth target-inference signal (WP5, Seq3Generator.inferTarget): a candidate line that looks
     *  like a fired callback (`Seq3Correlation.looksInboundCallback`) whose tag registered some
     *  callback/listener earlier in the scanned range (`Seq3Correlation.isCallbackRegistration`).
     *  Unlike [threadHandoffEnabled]/[correlationTokenEnabled] this needs no adjacency between the
     *  two lines — a listener registration and its eventual firing are essentially never on the
     *  same thread nor share an id, by construction — so it exists as its own toggle rather than
     *  folding into either of those, and defaults on for the same reason they do: it only ever adds
     *  one more vote to the same confidence-ratio gate, never a bypass of it. */
    val callbackInferenceEnabled: Boolean = true,
)

// ── Note export representation ──────────────────────────────────────────────────────────────
//
// Moved from `diagram/DiagramSpecCodec.kt` (where it lived as a bare two-value enum) during the
// v3 cutover. Its two constant NAMES are load-bearing, not just its own values: `AppSettings.
// diagramDefaultExportMode` (model/Model.kt) persists them literally via `.name` in
// AutosaveCodec.kt's settings JSON (`diagramDefaultExportMode`), so `IMAGE`/`SOURCE` must never be
// renamed or reordered, or every existing autosave's saved export-mode preference breaks on load.

/** Which representation a confirmed diagram note keeps beside its fenced source: a rasterized PNG
 *  ([IMAGE], portable to Markdown/Jira renderers with no Mermaid/PlantUML plugin) or the fenced
 *  [Seq3Dialect] source alone ([SOURCE], kept editable). Per-note (`Seq3Codec`'s header carries it),
 *  not just a global default — the default only seeds a NEWLY confirmed note. */
enum class DiagramExportMode { IMAGE, SOURCE }
