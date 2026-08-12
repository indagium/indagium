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
    /** Fully-qualified source types explicitly owned by this component. */
    val sourceOwnerTypes: Set<String> = emptySet(),
)

/** Direction(s) in which an actor mirrors the component it represents. */
enum class MirrorDirection { INBOUND, OUTBOUND, BOTH }

/** A workspace actor. A mirrored actor relays an evidenced component edge through the actor:
 * outbound is Actor → Component → Destination and inbound is Source → Component → Actor. Under
 * [ArrowMode.EVIDENCE_FLOW], a component with no evidenced (non-SELF) edge has nothing to relay. */
data class DiagramActor(
    val id: String,
    val label: String,
    val mirrorComponentId: String? = null,
    val mirrorDirection: MirrorDirection = MirrorDirection.BOTH,
    /** New multi-mirror representation; the legacy single ID remains for old callers/notes. */
    val mirrorComponentIds: Set<String> = emptySet(),
)

/** Provenance for an interaction.  Renderer and emitters preserve this in the in-app model.
 *  [THREAD_HANDOFF] is appended last (see this file's own "append last" convention) so existing
 *  persisted `.name` tokens are untouched — see [DiagramOptions.threadHandoffArrows]. */
enum class MessageEvidence { LOG, RULE, SOURCE_INFERRED, ACTOR_MIRROR, THREAD_HANDOFF, MANUAL_OVERRIDE }

/** When activation bars are included in the built model. */
enum class ActivationPolicy { NONE, EVIDENCE_BACKED }

/** Source-index enrichment is deliberately bounded to one direct edge. */
data class DiagramSourceEnrichment(
    // Source ownership is the primary call-direction evidence when an index is available. The
    // UI exposes this as an opt-out; when it cannot reconstruct a complete path, the builder
    // reports explicit fallback mode instead of mixing partial source edges into evidence flow.
    val enabled: Boolean = true,
    // Retained as the legacy persisted option; source-trace inference uses its own bounded
    // interprocedural search depth and does not reinterpret this old one-hop setting.
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
    /** Stack traces can prove a recursive self-call; ordinary source inference must not invent one. */
    val allowSelfCall: Boolean = false,
)

/** How [SeqDiagramBuilder.buildSequenceDiagram] turns a scanned entry into an arrow.
 *  [EVIDENCE_FLOW] (the default) draws an arrow only where the log actually carries evidence of
 *  one — a declared entry-point actor's opening call, an optional same-thread (pid+tid) handoff,
 *  or a matched rule/source-index edge — and represents every other line as a [MessageKind.SELF]
 *  event on its own lifeline rather than guessing who talked to whom from a mere tag change (that
 *  guess is exactly what the old `TAG_TRANSITION` name described, and exactly what this mode
 *  deliberately no longer does — see the builder's `runEvidenceFlow` doc). [RULES] matches each
 *  entry's message against [SeqDiagramSpec.rules] and lets the matched rule name the exact
 *  endpoints and label; an entry matched by no enabled rule falls through to the same
 *  evidence-only SELF behavior for that one entry. [LINE_PER_MESSAGE] emits one SELF message per
 *  entry on its own tag's lifeline — a flat "everything that happened, in order" view with no
 *  attempt to infer who talked to whom, which `EVIDENCE_FLOW`'s own fallback now shares. */
enum class ArrowMode { EVIDENCE_FLOW, RULES, LINE_PER_MESSAGE }

/** What text an arrow's label is built from. [SOURCE_METHOD] needs a `resolveLabel` callback
 *  (see the builder) — when that returns null for a given entry (no source index, or no call
 *  site resolved for that line) it falls back to [MESSAGE] for that one entry rather than
 *  producing a blank arrow. */
enum class LabelSource { MESSAGE, SOURCE_METHOD, BOTH }

/** CALL = an EVIDENCED interaction — an entry-point actor's opening call, a same-thread handoff,
 *  or a matched rule/source-index edge (see [ArrowMode.EVIDENCE_FLOW]'s doc), never a bare tag
 *  change; RETURN = the synthetic closing arrow to an exit-point ACTOR, or a source-inferred
 *  return; SELF = a single line shown as an event on its own participant's lifeline — the default
 *  shape for any entry [ArrowMode.EVIDENCE_FLOW] finds no evidence of a correlation for. */
enum class MessageKind { CALL, RETURN, SELF, ASYNC }

/** Visibility of a manually authored operation in UML-style labels. */
enum class ManualOperationVisibility { UNSPECIFIED, PUBLIC, PROTECTED, PACKAGE, PRIVATE }

/** Whether a diagram is rebuilt from inference or from its durable user-authored document. */
enum class DiagramAuthoringMode { INFERRED, MANUAL }

/**
 * Stable provenance for one generated or authored interaction.  Rendered message indices and
 * generated edge ordinals are presentation details and may change after filtering/collapsing;
 * this key instead follows the source operation, invocation, or the durable manual interaction.
 * `generatedOrdinal` is retained only as the legacy fallback for one log row with several
 * otherwise indistinguishable inferred edges.
 */
data class MessageOriginKey(
    val entryId: Int,
    /** Rule identity keeps a matched rule edge distinct from the entry's plain log event. */
    val ruleId: String? = null,
    val sourceOperationId: String? = null,
    val sourceLogSiteId: String? = null,
    val invocationId: String? = null,
    val manualInteractionId: String? = null,
    val generatedOrdinal: Int = 0,
)

/** One named value displayed as part of a manually authored operation. */
data class DiagramParameter(
    val name: String = "",
    val value: String = "",
)

/** An explicit, durable correction for a generated message identified by [origin]. */
data class DiagramMessageOverride(
    val origin: MessageOriginKey,
    val enabled: Boolean = true,
    val fromParticipantId: String? = null,
    val toParticipantId: String? = null,
    val label: String? = null,
    val kind: MessageKind? = null,
    val parameters: List<DiagramParameter>? = null,
)

/** A manually authored interaction.  [sourceEntryIds] preserves its selected log evidence. */
data class ManualDiagramInteraction(
    val id: String,
    val sourceEntryIds: Set<Int>,
    val fromParticipantId: String,
    val toParticipantId: String,
    val operation: String = "",
    val parameters: List<DiagramParameter> = emptyList(),
    val result: String? = null,
    /** Optional literal label; when absent it is formatted from operation/parameters/result. */
    val label: String? = null,
    val kind: MessageKind = MessageKind.CALL,
    val enabled: Boolean = true,
    /** Stable ordering key. Ties retain document order. */
    val order: Long = 0L,
    /** Non-blank interactions with the same key are shown as one editable group in the UI. */
    val groupKey: String? = null,
    /** Source-index provenance retained when this occurrence was seeded from inference. */
    val sourceMethodId: String? = null,
    val sourceLogSiteId: String? = null,
    val sourceOwnerType: String? = null,
    val visibility: ManualOperationVisibility = ManualOperationVisibility.UNSPECIFIED,
    /** Timestamp retained from the rendered message so this interaction survives a later range change. */
    val renderAnchorTs: String? = null,
    /** Severity retained alongside [renderAnchorTs] when no selected source row is available. */
    val renderAnchorLevel: LogLevel? = null,
)

/** A manually authored group/frame spanning its named interactions. */
data class ManualDiagramGroup(
    val id: String,
    val label: String,
    val interactionIds: List<String>,
    val enabled: Boolean = true,
)

/** A manually authored note anchored after one interaction. */
data class ManualDiagramNote(
    val id: String,
    val participantId: String,
    val afterInteractionId: String,
    val text: String,
    val isError: Boolean = false,
    val enabled: Boolean = true,
)

/** A manually authored activation span bounded by named interactions. */
data class ManualDiagramActivation(
    val id: String,
    val participantId: String,
    val startInteractionId: String,
    val endInteractionId: String,
    val enabled: Boolean = true,
)

/** The complete durable, source-index-independent document used by [DiagramAuthoringMode.MANUAL]. */
data class ManualDiagramDocument(
    val interactions: List<ManualDiagramInteraction> = emptyList(),
    val groups: List<ManualDiagramGroup> = emptyList(),
    val notes: List<ManualDiagramNote> = emptyList(),
    val activations: List<ManualDiagramActivation> = emptyList(),
)

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
    /** Source owner represented by a transient source-only lifeline, when known. */
    val sourceOwnerType: String? = null,
    /** Receiver role used to distinguish two fields/parameters of the same type. */
    val receiverRole: String? = null,
    /** True for a participant synthesized from the resolved trace rather than the spec. */
    val inferred: Boolean = false,
)

/** The human-facing name for a lifeline.  An alias wins only when it contains visible text, so
 * clearing an alias reliably restores the generated/legacy [DiagramParticipant.label]. */
fun shortDiagramName(value: String): String {
    val clean = value.trim()
    if (clean.isEmpty()) return clean
    return clean.substringAfterLast('.').substringAfterLast('$').ifBlank { clean }
}

val DiagramParticipant.displayName: String
    get() = alias?.trim().takeUnless { it.isNullOrEmpty() }
        ?: if (kind == ParticipantKind.TAG) shortDiagramName(label) else label

/**
 * Resolves labels shown in a diagram while retaining raw participant identity in the model.
 * Simple names are the default; colliding unaliased tag participants receive the smallest
 * distinct package suffix, such as `client.Service` and `server.Service`.
 */
fun participantDisplayNames(participants: List<DiagramParticipant>): List<String> {
    val base = participants.map { it.displayName }
    val result = base.toMutableList()
    base.withIndex().groupBy { it.value }.values.filter { it.size > 1 }.forEach { group ->
        val indices = group.map { it.index }.toSet()
        val candidates = group.map { indexed ->
            val participant = participants[indexed.index]
            if (participant.kind != ParticipantKind.TAG || !participant.alias.isNullOrBlank()) {
                null
            } else {
                val raw = participant.label.trim()
                val parts = raw.split('.')
                (2..parts.size).map { count -> parts.takeLast(count).joinToString(".") } + raw
            }
        }
        if (candidates.any { it == null }) return@forEach
        val outsideNames = base.withIndex().filter { it.index !in indices }.map { it.value }.toSet()
        val maxDepth = candidates.maxOf { it!!.size }
        val chosen = (0 until maxDepth).firstOrNull { depth ->
            val values = candidates.map { options -> options!![depth.coerceAtMost(options.lastIndex)] }
            values.distinct().size == values.size && values.none { it in outsideNames }
        }?.let { depth ->
            candidates.map { options -> options!![depth.coerceAtMost(options.lastIndex)] }
        }
        if (chosen != null) {
            group.forEachIndexed { offset, indexed -> result[indexed.index] = chosen[offset] }
        } else if (group.all { participants[it.index].kind == ParticipantKind.TAG }) {
            group.forEach { indexed -> result[indexed.index] = "${base[indexed.index]} (${participants[indexed.index].id})" }
        }
    }
    return result
}

/** Counts used by the participant inspector. All counts are computed from the same resolved range
 * and raw-or-filtered source list that generation scans; they must never be substituted with
 * whole-tab counts. */
data class DiagramParticipantCandidate(
    val tag: String,
    val entryCount: Int,
    /** Number of ADJACENT-LINE boundaries touching this tag in the selected range — a raw
     *  co-occurrence count, not a count of arrows [ArrowMode.EVIDENCE_FLOW] will actually draw
     *  (which needs real correlation: an entry-point actor, a same-thread handoff, or a matched
     *  rule/source edge). Kept for relative "how chatty is this tag" ranking in the inspector;
     *  callers must not present it as an arrow/interaction count. */
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
    data class Ids(
        val from: Int,
        val to: Int,
        /** Exact user selection. Empty preserves the legacy inclusive span behavior. */
        val selectedIds: Set<Int> = emptySet(),
    ) : DiagramRange()

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

/** A concrete binding from one captured string to an already-declared participant id. */
data class DiagramRuleCaptureBinding(
    val capturedValue: String,
    val participantId: String,
)

/**
 * Typed endpoint for a [DiagramMessageRule]. Typed rules never invent lifelines: a capture must
 * resolve through [CapturedValue.bindings], and an actor is created only when the rule explicitly
 * names [ExplicitActor]. Null typed endpoints preserve legacy template behaviour.
 */
sealed interface DiagramRuleEndpoint {
    data class ExistingParticipant(val participantId: String) : DiagramRuleEndpoint
    data object CurrentEntry : DiagramRuleEndpoint
    data class CapturedValue(
        val captureName: String,
        val bindings: List<DiagramRuleCaptureBinding>,
    ) : DiagramRuleEndpoint
    data class ExplicitActor(val id: String, val label: String) : DiagramRuleEndpoint
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
    /** Appended typed endpoint contract; null retains legacy template resolution. */
    val fromEndpoint: DiagramRuleEndpoint? = null,
    val toEndpoint: DiagramRuleEndpoint? = null,
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
    /** Prefix generated messages with elapsed time from the first selected row. */
    val showElapsed: Boolean = false,
    // Wrap each auto-detected sequence group (Filter's SeqGroup/NestedSeqGroup, one level of
    // nesting) that overlaps the diagram's range as a DiagramFrame.
    // A range-wide frame is visually louder than the interactions it is meant to organize.
    // Keep frames available, but let a newly-created diagram begin with the readable view.
    val seqGroupFrames: Boolean = false,
    // LogLevel.E/A entries also get a DiagramNoteMark, regardless of arrow mode.
    val notesForErrors: Boolean = true,
    /** Do not invent activations from unrelated transitions. */
    val activationPolicy: ActivationPolicy = ActivationPolicy.EVIDENCE_BACKED,
    // ── Appended fields below: append-only, see this file's own codec-versioning discipline
    // (DiagramSpecCodec.kt's optionsToMap/optionsFromMap already default every missing key off
    // this class's own defaults, so a v1/v2/v3 note with none of these keys decodes cleanly). ──
    // How many lines a message label may wrap onto before it is ellipsized. 1 reproduces the
    // pre-wrapping single-line layout exactly (see SeqDiagramRenderer's own compatibility note).
    val labelMaxLines: Int = 2,
    /** Opt-in [ArrowMode.EVIDENCE_FLOW] correlation: draw a CALL between two consecutive entries
     *  that share a real (non-zero) pid+tid within a short time bound, evidenced as
     *  [MessageEvidence.THREAD_HANDOFF]. Off by default — see the builder's own guard doc for why
     *  a brief/RAW-format log (pid==tid==0 for every row) must never correlate under this option. */
    val threadHandoffArrows: Boolean = false,
    /** When false, every [MessageKind.SELF] message is dropped from the built diagram — the
     *  "just show me the evidenced arrows" view. */
    val showSelfMessages: Boolean = true,
    /** When false, every [MessageEvidence.SOURCE_INFERRED] message is dropped. */
    val showSourceInferred: Boolean = true,
    /** Diagrams may deliberately include rows hidden by the log filter. */
    val includeRowsHiddenByFilter: Boolean = true,
    /** Participant labels have independent limits from message labels. */
    val participantLabelMaxChars: Int = 40,
    val participantLabelMaxLines: Int = 2,
)

/** A durable correction for one generated edge belonging to one log entry. */
data class DiagramCallOverride(
    val entryId: Int,
    val edgeOrdinal: Int,
    val fromParticipantId: String,
    val toParticipantId: String,
)

/** Explicit source-site correction retained by a diagram note. The inferred trace remains
 * transient; only this bounded user choice is persisted. */
data class DiagramSourceSiteOverride(
    val entryId: Int,
    val sourceLogSiteId: String,
    val edgeOrdinal: Int = 0,
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
    val mode: ArrowMode = ArrowMode.EVIDENCE_FLOW,
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
    val callOverrides: List<DiagramCallOverride> = emptyList(),
    val sourceSiteOverrides: List<DiagramSourceSiteOverride> = emptyList(),
    /** Inferred is the backwards-compatible default; manual never reads the source index. */
    val authoringMode: DiagramAuthoringMode = DiagramAuthoringMode.INFERRED,
    /** Full lifeline order. Missing current IDs append deterministically; stale IDs are ignored. */
    val lifelineOrder: List<String> = emptyList(),
    val messageOverrides: List<DiagramMessageOverride> = emptyList(),
    val manualDocument: ManualDiagramDocument = ManualDiagramDocument(),
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
    /** Deterministic generated-edge ordinal within the originating entry. */
    val edgeOrdinal: Int = 0,
    /** Stable transient trace identity; absent for legacy/runtime/rule messages. */
    val invocationId: String? = null,
    /** Runtime outcome associated with this message, when it belongs to a trace invocation. */
    val traceStatus: TraceCallStatus? = null,
    /** Invocation semantics used to suppress false blocking activations for async dispatch. */
    val invocationKind: TraceInvocationKind? = null,
    /** Exactly one primary message is retained for every selected, enabled log entry. */
    val primary: Boolean = true,
    /** All log rows represented by this message; repeat collapsing unions this set losslessly. */
    val representedEntryIds: Set<Int> = setOf(entryId),
    /** Source operation provenance for structural messages; null for legacy evidence-flow rows. */
    val sourceOperationId: String? = null,
    /** Exact source log-site provenance for primary source-trace events. */
    val sourceLogSiteId: String? = null,
    /** Stable source/authored provenance. Collapsed and mirrored messages retain all members. */
    val originKeys: Set<MessageOriginKey> = emptySet(),
)

/** A correlated call/return activation interval, inclusive message indices. */
data class DiagramActivationSpan(
    val participantIdx: Int,
    val startMessage: Int,
    val endMessage: Int,
    val evidence: MessageEvidence,
    val invocationId: String? = null,
    val status: TraceCallStatus? = null,
    val invocationKind: TraceInvocationKind? = null,
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
    /** Range-level source result. Kept transient unless explicitly copied into a note snapshot. */
    val resolvedTrace: DiagramResolvedTrace? = null,
    /** Explicitly distinguishes source-first output from the log-only fallback. */
    val traceMode: SourceTraceMode = SourceTraceMode.DISABLED,
) {
    /** Semantic primary evidence, including every ID retained by a collapsed primary message. */
    val primaryEntryIds: Set<Int> get() = messages.filter { it.primary }.flatMapTo(linkedSetOf()) { it.representedEntryIds }
}
