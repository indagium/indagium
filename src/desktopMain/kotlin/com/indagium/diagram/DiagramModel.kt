package com.indagium.diagram

import com.indagium.model.LogLevel

/** Shared source-enrichment contract for MCP/UI adapters and the builder. */
const val MAX_SOURCE_INTERACTIONS_PER_ENTRY = 10

// ── UML sequence-diagram model ──────────────────────────────────────────────────────────────
//
// This package turns a range of a tab's log entries into a dialect-neutral sequence-diagram
// model (this file), builds that model from a LogTab (SeqDiagramBuilder.kt), emits Mermaid/
// PlantUML text from it (DiagramEmitters.kt), and encodes/decodes the on-disk note-block
// convention (DiagramSpecCodec.kt). Deliberately UI-free: a generated diagram is stored as an
// ordinary AnnBlock.Note (see DiagramSpecCodec.kt's header comment) rather than a new AnnBlock
// variant, so nothing about the .ann/autosave format changes here. Colors are plain ARGB Ints,
// never androidx.compose.ui.graphics.Color — see DiagramFrame's own doc for why.

/** Which text dialect a [SeqDiagram] is rendered as. Both draw the same abstract model; only
 *  [DiagramEmitters.kt] differs per dialect. */
enum class DiagramDialect { MERMAID, PLANTUML }

/** TAG participants are lifelines derived from (or matched against) `LogEntry.tag`. ACTOR
 *  participants are external entities with no corresponding tag — a human, a backend server, a
 *  piece of hardware — that a message can originate from or terminate at (see
 *  [DiagramParticipant.isEntryPoint]/[isExitPoint]) or that a [DiagramMessageRule] can name
 *  explicitly via its `fromTemplate`/`toTemplate`. */
enum class ParticipantKind { TAG, ACTOR }

/** How a tag from the resolved diagram range is represented.  TAG entries marked [SHOW] keep
 * their own lifeline, [OTHER] share the generated `Other` lifeline, and [HIDE] are deliberately
 * omitted.  It is meaningful only for TAG participants; actors are always shown. */
enum class DiagramParticipantRepresentation { SHOW, OTHER, HIDE }

/** The workspace-level policy for rows which do not belong to an enabled component. */
enum class UnmappedTagPolicy { HIDE, GROUP_AS_OTHER }

/** A durable, user-facing component.  A component can own any number of raw log tags. */
data class DiagramComponent(
    val id: String,
    val displayName: String,
    val tagIds: Set<String>,
    val enabled: Boolean = true,
)

/** Direction(s) in which an actor mirrors the component it represents. */
enum class MirrorDirection { INBOUND, OUTBOUND, BOTH }

/** A workspace actor.  A mirrored actor duplicates, rather than replaces, component edges. */
data class DiagramActor(
    val id: String,
    val label: String,
    val mirrorComponentId: String? = null,
    val mirrorDirection: MirrorDirection = MirrorDirection.BOTH,
)

/** Provenance for an interaction.  Renderer and emitters preserve this in the in-app model. */
enum class MessageEvidence { LOG, RULE, SOURCE_INFERRED, ACTOR_MIRROR }

/** When activation bars are included in the built model. */
enum class ActivationPolicy { NONE, EVIDENCE_BACKED }

/** Source-index enrichment is deliberately bounded to one direct edge. */
data class DiagramSourceEnrichment(
    val enabled: Boolean = false,
    val directCallDepth: Int = 1,
    val addReturnArrows: Boolean = true,
)

/** One high-confidence, one-hop source-index edge.  IDs refer to components (or explicit
 * participants), never raw tags, so source enrichment remains stable after tag merging. */
data class DiagramSourceInteraction(
    val fromComponentId: String,
    val toComponentId: String,
    val label: String,
    /** Usually a declared return type. Runtime values must not be placed here by source-only code. */
    val returnLabel: String? = null,
)

/** How [SeqDiagramBuilder.buildSequenceDiagram] turns a scanned entry into an arrow.
 *  [TAG_TRANSITION] (the default) infers a CALL whenever the active tag changes and a SELF
 *  message when it repeats — no configuration needed, works on any log. [RULES] matches each
 *  entry's message against [SeqDiagramSpec.rules] and lets the matched rule name the exact
 *  endpoints and label; an entry matched by no enabled rule falls through to TAG_TRANSITION
 *  behavior for that one entry (see the builder's own doc). [LINE_PER_MESSAGE] emits one SELF
 *  message per entry on its own tag's lifeline — a flat "everything that happened, in order"
 *  view with no attempt to infer who talked to whom. */
enum class ArrowMode { TAG_TRANSITION, RULES, LINE_PER_MESSAGE }

/** What text an arrow's label is built from. [SOURCE_METHOD] needs a `resolveLabel` callback
 *  (see the builder) — when that returns null for a given entry (no source index, or no call
 *  site resolved for that line) it falls back to [MESSAGE] for that one entry rather than
 *  producing a blank arrow. */
enum class LabelSource { MESSAGE, SOURCE_METHOD, BOTH }

/** CALL = a tag change (A talked to B); RETURN = the synthetic closing arrow to an exit-point
 *  ACTOR (see [ArrowMode.TAG_TRANSITION]'s doc); SELF = the same tag/participant twice in a row. */
enum class MessageKind { CALL, RETURN, SELF }

data class DiagramParticipant(
    // Stable identifier. For a builder-derived TAG participant this is the raw tag string; for a
    // caller-supplied participant it's whatever the caller assigned. NOT guaranteed to already be
    // a syntactically valid Mermaid/PlantUML alias (a tag can contain '.', '/', spaces, …) — that
    // sanitization, plus deduping two participants that would otherwise collide once sanitized, is
    // deliberately deferred to DiagramEmitters.kt's sanitizedAliases(), which sees every
    // participant at once and can dedupe correctly whichever dialect is being emitted.
    val id: String,
    val label: String,
    val kind: ParticipantKind,
    // TAG participants only: the logcat tag this lifeline represents. Null for ACTOR.
    val tag: String? = null,
    // ACTOR only: the diagram's very first message originates here (SeqDiagramBuilder emits a
    // leading CALL from this actor to whichever tag the range's first entry belongs to).
    val isEntryPoint: Boolean = false,
    // ACTOR only: a synthetic closing RETURN is appended from the last active tag to this actor.
    val isExitPoint: Boolean = false,
    /** A user-selected display name. This never changes [id] or [tag], which remain provenance
     * identities used by range selection, rules and regeneration. */
    val alias: String? = null,
    /** Per-tag presentation choice.  [SHOW] preserves the legacy behaviour. */
    val representation: DiagramParticipantRepresentation = DiagramParticipantRepresentation.SHOW,
)

/** The human-facing name for a lifeline.  An alias wins only when it contains visible text, so
 * clearing an alias reliably restores the generated/legacy [DiagramParticipant.label]. */
val DiagramParticipant.displayName: String
    get() = alias?.trim().takeUnless { it.isNullOrEmpty() } ?: label

/** Counts used by the participant inspector.  All counts are computed from the same resolved,
 * filtered range that generation scans; they must never be substituted with whole-tab counts. */
data class DiagramParticipantCandidate(
    val tag: String,
    val entryCount: Int,
    /** Number of tag boundaries touching this tag in the selected range. */
    val transitionCount: Int,
    val errorCount: Int,
    val representation: DiagramParticipantRepresentation,
    val participant: DiagramParticipant? = null,
    /** Distinct pids that logged this tag inside the resolved range, capped at MAX_CANDIDATE_PIDS.
     *  Runtime-only, like the counts above — never persisted. */
    val pids: Set<Int> = emptySet(),
)

/** Explicit accounting for the source rows selected for a diagram.  Grouped rows are represented
 * by the generated Other lifeline; hidden rows are intentionally absent from messages. */
data class DiagramCoverage(
    val scannedEntries: Int = 0,
    val shownEntries: Int = 0,
    val groupedEntries: Int = 0,
    val hiddenEntries: Int = 0,
) {
    val representedEntries: Int get() = shownEntries + groupedEntries
}

/** How a [SeqDiagram] selects which of a tab's entries to scan. All four are resolved against
 *  `utils.visibleEntries(tab, applyFilter = true)` — the same "what the user currently sees" set
 *  `computeItems` renders from — so a diagram never includes a line the filter panel is hiding. */
sealed class DiagramRange {
    /** The whole current filtered view, unbounded. */
    data object VisibleView : DiagramRange()

    /** Inclusive `LogEntry.id` bounds. Order-independent, like `ManualCollapseDirection.RANGE`
     *  (the builder takes `minOf`/`maxOf` of the two) — a caller building this from a drag
     *  selection shouldn't have to know which end the user started from. */
    data class Ids(val from: Int, val to: Int) : DiagramRange()

    /** Inclusive `"HH:MM:SS[.mmm]"` clock-time bounds, parsed the same way as `LogEntry.ts`
     *  everywhere else in the app (`utils.LogTime.parseMillisOfDay`). Order-independent, same as
     *  [Ids]. Rows with no parseable `ts` of their own (brief/RAW format) inherit the previous
     *  row's timestamp — see the builder for the exact carry-forward rule and its one hard-failure
     *  case (no parseable row anywhere in the tab). */
    data class Time(val fromTs: String, val toTs: String) : DiagramRange()

    /** Exactly the entries one auto-detected sequence group (root + every plain/nested child)
     *  swallows — the same `gid` space `SeqGroup`/`NestedSeqGroup` use, so a diagram can be
     *  generated directly from "this collapsed block" without the caller re-deriving its bounds. */
    data class SeqGroupRef(val gid: String) : DiagramRange()
}

/** One named-capture-group rule for [ArrowMode.RULES]. [pattern] is matched against
 *  `LogEntry.msg` (not tag+msg — a rule is meant to pull structure out of one line's own text).
 *  [fromTemplate]/[toTemplate]/[labelTemplate] are `${name}`-token templates: `${msg}` always
 *  expands to the whole matched entry's message, and `${anyOtherName}` expands to that named
 *  capture group's text (or "" if the group didn't participate in the match) — so a pattern like
 *  `sending to (?<peer>\w+)` paired with `toTemplate = "${peer}"` names the arrow's destination
 *  straight from the log line. A `from`/`to` naming a participant nobody declared auto-creates an
 *  ACTOR for it (see the builder) rather than silently dropping the message. */
data class DiagramMessageRule(
    val id: String,
    val pattern: String,
    val enabled: Boolean = true,
    val fromTemplate: String,
    val toTemplate: String,
    val labelTemplate: String,
)

data class DiagramOptions(
    // Fold a run of consecutive same-endpoints messages into one with a "×N" suffix — see the
    // builder's normalizeLabelForRepeatCollapse for exactly what counts as "the same" label.
    val collapseRepeats: Boolean = true,
    // A hard cap on the diagram's own size (not the scanned range's size) — a sequence diagram
    // with thousands of arrows is unreadable regardless of how it was produced. See
    // SeqDiagram.truncated.
    val maxMessages: Int = 60,
    val labelMaxChars: Int = 60,
    val labelSource: LabelSource = LabelSource.MESSAGE,
    val showTimestamps: Boolean = false,
    val showElapsed: Boolean = true,
    // Wrap each auto-detected sequence group (Filter's SeqGroup/NestedSeqGroup, one level of
    // nesting) that overlaps the diagram's range as a DiagramFrame.
    // A range-wide frame is visually louder than the interactions it is meant to organize.
    // Keep frames available, but let a newly-created diagram begin with the readable view.
    val seqGroupFrames: Boolean = false,
    // LogLevel.E/A entries also get a DiagramNoteMark, regardless of arrow mode.
    val notesForErrors: Boolean = true,
    /** Do not invent activations from unrelated transitions. */
    val activationPolicy: ActivationPolicy = ActivationPolicy.EVIDENCE_BACKED,
)

data class SeqDiagramSpec(
    val dialect: DiagramDialect = DiagramDialect.MERMAID,
    val title: String = "",
    // Caller-supplied participants (typically from a prior generation, or hand-picked in the
    // Phase 3 builder dialog). Empty means "derive TAG participants automatically" — see the
    // builder's resolveTagParticipants. ACTOR participants here are ALWAYS kept regardless of
    // whether TAG participants were supplied or derived.
    val participants: List<DiagramParticipant> = emptyList(),
    val range: DiagramRange = DiagramRange.VisibleView,
    val mode: ArrowMode = ArrowMode.TAG_TRANSITION,
    val rules: List<DiagramMessageRule> = emptyList(),
    val options: DiagramOptions = DiagramOptions(),
    // The log file this spec was built from (LogTab.filename), persisted purely so a later
    // "Regenerate" action (Phase 3) can tell whether the attached tab is still the same log this
    // diagram was drawn from, or warn before regenerating against a different one. Never read by
    // this package itself.
    val sourceFile: String? = null,
    /** New component workflow. Empty deliberately means use legacy [participants] behaviour. */
    val components: List<DiagramComponent> = emptyList(),
    val actors: List<DiagramActor> = emptyList(),
    val unmappedTagPolicy: UnmappedTagPolicy = UnmappedTagPolicy.HIDE,
    val sourceEnrichment: DiagramSourceEnrichment = DiagramSourceEnrichment(),
)

data class DiagramMessage(
    // Indices into the OWNING SeqDiagram.participants list — never spec.participants, which may
    // be missing auto-derived tags or rule-created actors the final diagram actually contains.
    val fromIdx: Int,
    val toIdx: Int,
    val label: String,
    // The LogEntry.id that produced this arrow. For the one synthetic exit-point RETURN (see
    // ArrowMode.TAG_TRANSITION's doc) this is the range's last real entry's id — there is no
    // entry of its own to attribute it to.
    val entryId: Int,
    val ts: String,
    val level: LogLevel,
    val kind: MessageKind,
    // > 1 when collapseRepeats folded a run of identical-shape consecutive messages into this one.
    val repeatCount: Int = 1,
    val evidence: MessageEvidence = MessageEvidence.LOG,
)

/** A correlated call/return activation interval, inclusive message indices. */
data class DiagramActivationSpan(
    val participantIdx: Int,
    val startMessage: Int,
    val endMessage: Int,
    val evidence: MessageEvidence,
)

/** One auto-detected sequence group rendered as a bracket around [firstMsg]..[lastMsg]
 *  (inclusive indices into the owning SeqDiagram.messages). [depth] is 0 for a top-level SeqGroup
 *  and 1 for one of its NestedSeqGroup children — this package supports exactly one level of
 *  nesting, matching the model's own (SeqGroup/NestedSeqGroup is itself only one level deep).
 *
 *  [colorArgb] is a plain packed ARGB Int, not a Compose Color — this package carries no
 *  androidx.compose import at all, so a SequenceDef's Color (defined in `model`, which IS
 *  Compose-typed) is converted to ARGB the moment the builder reads it, and every UI-side
 *  consumer (Phase 2's renderer) converts that Int back into whatever color type it needs. Null
 *  when the owning SequenceDef could no longer be found (e.g. deleted from the filter after this
 *  diagram was generated) — callers should fall back to a theme default, never treat null as
 *  "transparent" or "no frame". */
data class DiagramFrame(
    val label: String,
    val colorArgb: Int?,
    val firstMsg: Int,
    val lastMsg: Int,
    val depth: Int,
)

/** An error/assert-level annotation attached after a specific message. [participantIdx] is an
 *  index into SeqDiagram.participants (where the note visually anchors); [afterMsg] is an index
 *  into SeqDiagram.messages (which message it trails). */
data class DiagramNoteMark(
    val participantIdx: Int,
    val afterMsg: Int,
    val text: String,
    val isError: Boolean,
)

data class SeqDiagram(
    val spec: SeqDiagramSpec,
    val participants: List<DiagramParticipant>,
    val messages: List<DiagramMessage>,
    val frames: List<DiagramFrame> = emptyList(),
    val notes: List<DiagramNoteMark> = emptyList(),
    val activationSpans: List<DiagramActivationSpan> = emptyList(),
    // True when maxMessages capped the output — the diagram is a PREFIX of what the full range
    // would have produced, not a sample of it (see the builder: fold-then-cap, so this reports
    // "there were more messages after this point", not "some messages were skipped in the middle").
    val truncated: Boolean = false,
    // How many entries the resolved range actually covered, before the TAG-participant filter and
    // before collapsing/capping — lets a caller show "N lines summarized into M messages".
    val scannedEntries: Int = 0,
    /** Coverage of entries before arrow collapsing/truncation. */
    val coverage: DiagramCoverage = DiagramCoverage(scannedEntries = scannedEntries),
    // Never fatal — a malformed rule, an unresolvable seq-group-ref range, or a tab with no
    // parseable timestamps for a Time range all degrade to an empty-ish diagram plus an entry
    // here, rather than throwing. See SeqDiagramBuilder's own doc for the full list of cases.
    val warnings: List<String> = emptyList(),
)
