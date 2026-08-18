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
 *  needs-target. */
enum class Seq3Kind { CALL, RETURN, ASYNC, SELF, NOTE }

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
        get() = if (toLifelineId == null && kind != Seq3Kind.NOTE) {
            // A NOTE renders anchored on `fromLifelineId` and has no target BY DEFINITION (see
            // [Seq3Kind.NOTE]), so a null target on one is not a defect to be queued. Without this
            // guard every note would inflate the "N messages need a target" banner and enter the
            // guided pass as a row the pass structurally cannot resolve.
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
 *  every one of these IS semantic — a user explicitly chose it — so Seq3Emitters renders the
 *  dialect's real `loop`/`alt`/`opt`/`par` block instead of a meaning-free note pairing. */
enum class Seq3FragmentKind { LOOP, ALT, OPT, PAR }

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
    /** The repeat policy newly generated messages start with; an already-[Seq3Authoring.EDITED]
     *  message's own [Seq3Message.repeat] is never overwritten by this. */
    val defaultRepeat: Seq3Repeat = Seq3Repeat.COLLAPSE_ABOVE,
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
