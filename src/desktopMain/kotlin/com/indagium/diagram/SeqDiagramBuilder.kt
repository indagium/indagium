package com.indagium.diagram

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.model.SequenceDef
import com.indagium.utils.CANCELLATION_CHECK_INTERVAL
import com.indagium.utils.CancellationCheck
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.TS_UNKNOWN
import com.indagium.utils.cachedSeqGroupsFor
import com.indagium.utils.computeSeqGroups
import com.indagium.utils.deltaMillis
import com.indagium.utils.firstRegexMatchResult
import com.indagium.utils.formatDelta
import com.indagium.utils.parseMillisOfDay
import com.indagium.utils.visibleEntries
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.roundToInt

// A tag participant list longer than this stops being a readable diagram regardless of how it
// was produced — caps the auto-derive path (resolveTagParticipants) the same way
// DiagramOptions.maxMessages caps the arrow count. Deliberately not configurable: a caller who
// wants more control supplies spec.participants explicitly instead (which is never capped here).
private const val DEFAULT_MAX_AUTO_PARTICIPANTS = 8

// Bounds DiagramParticipantCandidate.pids per tag. This scan runs in the cancellable IO lane over
// ranges up to 10M entries (see requestCandidates); an uncapped HashMap<String, MutableSet<Int>>
// is exactly the boxing hazard resolveTimeRange's carry-forward comment (below) and Filter.kt's
// idBitSet exist to avoid, so pid collection stops the moment a tag's set reaches this cap.
private const val MAX_CANDIDATE_PIDS = 8

// Bounds TagStats.threadPeers per tag (see signalScore) — a tag realistically shares a thread with
// only a handful of others; this just stops the bonus from growing unbounded on a pathological log.
private const val MAX_THREAD_PEER_TAGS = 5

// Weight per distinct tag a tag shares a real pid+tid with — deliberately large enough to outrank
// signalScore's own min(count, 10) ceiling (see its doc), so a tag resolveEvidenceEdge's
// thread-handoff branch would actually draw a CALL for can't be pushed into the shared "Other"
// lifeline purely for having modest error/template diversity. See signalScore's own doc for why
// this bonus exists at all.
private const val THREAD_PEER_SCORE_WEIGHT = 3

/**
 * Builds a [SeqDiagram] from a range of [tab]'s entries. New specs scan the raw log by default so
 * explicit diagram selections can include rows hidden by the active filter; setting
 * [DiagramOptions.includeRowsHiddenByFilter] to false retains the legacy filtered-view behavior.
 * Range indices are always resolved against the exact list selected for that build.
 *
 * [resolveLabel] backs `DiagramOptions.labelSource == SOURCE_METHOD`/`BOTH` — Phase 1 has no
 * opinion on where a label comes from (source-index resolution lives in the `source` package,
 * which this UI-free package deliberately doesn't depend on); a caller wires it to
 * `LogSourceResolver` or similar. Returning null for a given entry falls back to its message.
 *
 * Never throws: every failure mode (an unresolvable range, a malformed rule, a tab with no
 * parseable timestamp for a Time range) degrades to a smaller-than-expected diagram plus an
 * entry in [SeqDiagram.warnings], exactly like the rest of this codebase's regex/filter layer
 * (see utils/TextMatch.kt's own "never throw on a bad pattern" posture).
 *
 * ## Arrow modes and the "evidence, not guessing" rule
 *
 * [ArrowMode.EVIDENCE_FLOW] (the default, `runEvidenceFlow` below) draws a cross-lifeline arrow
 * ONLY where the log itself carries actual evidence of one: a user-declared or transient
 * entry-point actor's opening call, a same-thread (pid+tid, bounded time gap) handoff, or — for
 * [ArrowMode.RULES]' own fallthrough, which shares the same decision via `emitEvidenceMessage` —
 * nothing else. Every entry that clears neither test renders as a [MessageKind.SELF] event on its
 * own lifeline rather than a guessed CALL. This deliberately replaces the old "draw a CALL
 * whenever the active tag differs from the previous surviving line's" heuristic (once named
 * `TAG_TRANSITION`), which had no pid/tid, stack, or call/return correlation behind it at all —
 * three unrelated adjacent lines from tags A, B, C used to render as "A called B, B called C",
 * which was simply false. [ArrowMode.RULES] matched lines remain the one place a CALL is drawn
 * from genuine structure (`runRulesMatched`, untouched by this change: a rule naming `from`/`to`
 * IS the evidence). [ArrowMode.LINE_PER_MESSAGE] is the same "one SELF per line" shape
 * `EVIDENCE_FLOW`'s own uncorrelated fallback now shares, made explicit as its own mode for a
 * caller who wants that flat view unconditionally.
 */
fun buildSequenceDiagram(
    tab: LogTab,
    spec: SeqDiagramSpec,
    resolveLabel: (LogEntry) -> String? = { null },
    cancellationCheck: CancellationCheck = CancellationCheck {},
    resolveSourceInteractions: (LogEntry) -> List<DiagramSourceInteraction> = { emptyList() },
    /** New range-level source trace resolver. The legacy callback remains as a migration adapter. */
    resolveTrace: ((List<LogEntry>) -> DiagramResolvedTrace)? = null,
): SeqDiagram {
    val warnings = mutableListOf<String>()
    val regexContext = RegexEvaluationContext()

    val range = resolveRangeAndParticipants(tab, spec, cancellationCheck, warnings)
    val allVisible = range.allVisible
    val resolved = range.resolved
    val candidateEntries = range.candidateEntries
    val participantResolution = range.participantResolution
    // Accepted manual interactions are a complete authored document.  Deliberately stop before
    // source resolution, actor mirroring, inferred overrides, and trace participant synthesis:
    // none may mutate a manual diagram behind the user's back.
    if (spec.authoringMode == DiagramAuthoringMode.MANUAL) {
        return buildManualSequenceDiagram(
            spec = spec,
            entries = participantResolution.representedEntries,
            participants = participantResolution.participants,
            coverage = participantResolution.coverage,
            warnings = warnings,
        )
    }
    val traceReg = resolveTraceAndRegistry(spec, resolveTrace, participantResolution, warnings)
    val projectedTrace = traceReg.projectedTrace
    val traceParticipantResolution = traceReg.traceParticipantResolution
    val sourceTraceUsable = traceReg.sourceTraceUsable
    val sourceTraceComplete = traceReg.sourceTraceComplete
    val registry = traceReg.registry
    val entryPointIdx = traceReg.entryPointIdx
    val exitPointIdx = traceReg.exitPointIdx

    val gen = RawGen()
    // Elapsed-time labels anchor to the RANGE's own first entry, not filteredEntries' first — a
    // tag excluded by the diagram-specific participant filter should not shift what "+0.000"
    // means for everything that survived the filter.
    val firstTs = candidateEntries.firstOrNull()?.ts

    if (!sourceTraceUsable) {
        runArrowModeAndCheckAllSelf(
            spec, traceParticipantResolution.representedEntries, registry, entryPointIdx, exitPointIdx,
            resolveLabel, firstTs, regexContext, cancellationCheck, gen, warnings,
        )
    }

    val sourceResult = resolveLegacySourceResult(
        spec, candidateEntries, traceParticipantResolution.representedEntries, registry,
        resolveTrace, resolveSourceInteractions, cancellationCheck, warnings,
    )
    // Frames rely on entry-id order; sorting is stable, so source edges for one entry stay after
    // that entry's runtime edge while no longer being appended after the whole range.
    // A uniquely resolved source relationship is stronger evidence than the fallback SELF that
    // evidence-flow emits for a line. Promote only one source CALL per entry; if the source
    // resolver returns several candidates, keep the original SELF and leave the enrichment
    // messages supplemental rather than choosing an arbitrary endpoint.
    val sourceResolution = resolveDiagramSourceResult(
        projectedTrace, sourceTraceUsable, sourceTraceComplete, traceParticipantResolution.representedEntries,
        registry, spec, resolveLabel, firstTs, warnings, sourceResult, entryPointIdx,
    )
    val selectedSourceResult = sourceResolution.selectedSourceResult
    val selectedResultWithTransientCaller = sourceResolution.withTransientCaller
    appendTraceDiagnosticWarnings(projectedTrace, warnings)
    val mergedMessages = when {
        sourceTraceUsable -> selectedResultWithTransientCaller.messages
        // A source resolver was present but could not reconstruct a compatible trace: explicit
        // log/evidence fallback, with no one-hop overlay semantics.
        resolveTrace != null -> gen.messages
        else -> promoteUniqueSourceCalls(gen.messages, selectedSourceResult.messages, spec.options)
    }
    val postProcessed = postProcessDiagramMessages(
        mergedMessages, spec, registry, gen.notes, traceParticipantResolution.representedEntries, warnings,
    )
    val rawMessages = postProcessed.rawMessages
    val cappedMessages = postProcessed.cappedMessages
    val truncated = postProcessed.cappingTruncated || selectedResultWithTransientCaller.truncated
    val finalMapping = postProcessed.finalMapping
    val notes = postProcessed.notes

    val frames = buildDiagramFrames(spec, tab, allVisible, resolved, rawMessages, finalMapping, cappedMessages.size, cancellationCheck)
    val activations = buildDiagramActivations(spec, sourceTraceUsable, projectedTrace, rawMessages, finalMapping, cappedMessages)

    return reorderDiagramLifelines(SeqDiagram(
        spec = spec,
        participants = registry.list.toList(),
        messages = cappedMessages,
        frames = frames,
        notes = notes,
        activationSpans = activations,
        truncated = truncated,
        scannedEntries = candidateEntries.size,
        coverage = traceParticipantResolution.coverage,
        warnings = warnings,
        resolvedTrace = projectedTrace,
        traceMode = when {
            sourceTraceComplete -> SourceTraceMode.SOURCE_TRACE
            sourceTraceUsable -> SourceTraceMode.PARTIAL_SOURCE_TRACE
            spec.sourceEnrichment.enabled -> SourceTraceMode.FALLBACK
            else -> SourceTraceMode.DISABLED
        },
    ), spec.lifelineOrder)
}

// The helpers below carry buildSequenceDiagram's own range/participant resolution, trace/registry
// resolution, arrow-mode dispatch, legacy source-result resolution, entry-point resolution,
// source-result resolution (trace vs. legacy fallback, plus the transient-caller opening-call
// promotion), and message post-processing pipeline (ordinals through notes) — pulled out purely to
// keep buildSequenceDiagram's own complexity/length down; each does exactly what its inline block
// used to, over the exact same inputs buildSequenceDiagram already resolved at that point.

private class RangeAndParticipants(
    val allVisible: List<LogEntry>,
    val resolved: RangeResolution,
    val candidateEntries: List<LogEntry>,
    val participantResolution: ParticipantResolution,
)

private fun resolveRangeAndParticipants(
    tab: LogTab,
    spec: SeqDiagramSpec,
    cancellationCheck: CancellationCheck,
    warnings: MutableList<String>,
): RangeAndParticipants {
    // A diagram range is an explicit data-selection surface. New specs include rows hidden by
    // the log filter so selecting a tag never makes its rows disappear from the diagram. The
    // option remains persisted for callers that need the legacy filtered-view behavior.
    val allVisible = if (spec.options.includeRowsHiddenByFilter) tab.logData else visibleEntries(tab, applyFilter = true)
    val resolved = resolveRange(tab, spec.range, allVisible, cancellationCheck, warnings)
    val candidateEntries = resolved.entries
    val participantResolution = if (spec.components.isNotEmpty()) {
        resolveComponentParticipants(spec, candidateEntries)
    } else {
        resolveTagParticipants(spec.participants, candidateEntries, cancellationCheck)
    }
    return RangeAndParticipants(allVisible, resolved, candidateEntries, participantResolution)
}

private class TraceRegistryResolution(
    val projectedTrace: DiagramResolvedTrace?,
    val traceParticipantResolution: ParticipantResolution,
    val sourceTraceUsable: Boolean,
    val sourceTraceComplete: Boolean,
    val registry: ParticipantRegistry,
    val entryPointIdx: Int?,
    val exitPointIdx: Int?,
)

private fun resolveTraceAndRegistry(
    spec: SeqDiagramSpec,
    resolveTrace: ((List<LogEntry>) -> DiagramResolvedTrace)?,
    participantResolution: ParticipantResolution,
    warnings: MutableList<String>,
): TraceRegistryResolution {
    val resolvedTrace = if (spec.sourceEnrichment.enabled && resolveTrace != null) {
        // Source reconstruction must see exactly the rows represented by configured lifelines.
        // An intentionally hidden/unmapped row cannot invalidate an otherwise verifiable trace.
        runCatching { resolveTrace(participantResolution.representedEntries) }
            .onFailure { warnings += "Source trace inference failed: ${it.message ?: "unknown error"}" }
            .getOrNull()
    } else {
        null
    }
    val projectedTrace = resolvedTrace?.projectTo(participantResolution.representedEntries)
    val traceParticipantResolution = addTraceParticipants(participantResolution, spec, projectedTrace)
    // A complete source trace is a separate semantic model from evidence flow.  It must not be
    // seeded with legacy log arrows and then patched with source calls afterwards.
    val sourceTraceUsable = projectedTrace != null && projectedTrace.events.isNotEmpty() &&
        traceParticipantResolution.representedEntries.isNotEmpty()
    val sourceTraceComplete = sourceTraceUsable &&
        projectedTrace.events.map { it.entryId }.toSet() == traceParticipantResolution.representedEntries.map { it.id }.toSet() &&
        projectedTrace.diagnostics.diagnostics.none { diagnostic ->
            diagnostic.reason in SOURCE_TRACE_HARD_FAILURES
        }
    val registry = ParticipantRegistry(
        traceParticipantResolution.participants,
        traceParticipantResolution.groupedTags,
        traceParticipantResolution.tagToParticipantId,
        sourceOwnerBindings(spec, traceParticipantResolution.participants),
    )
    val entryPointIdx = resolveEntryPointIdx(spec, registry, sourceTraceComplete, traceParticipantResolution.representedEntries)
    val exitPointIdx = spec.participants
        .firstOrNull { it.kind == ParticipantKind.ACTOR && it.isExitPoint }
        ?.let { registry.indexForId(it.id) }
    return TraceRegistryResolution(
        projectedTrace, traceParticipantResolution, sourceTraceUsable, sourceTraceComplete, registry, entryPointIdx, exitPointIdx,
    )
}

// Only ever called when !sourceTraceUsable (see the call site), so — unlike the pre-extraction
// inline code — neither this dispatch nor the all-self warning check below needs to repeat that
// condition themselves.
@Suppress("LongParameterList")
private fun runArrowModeAndCheckAllSelf(
    spec: SeqDiagramSpec,
    entries: List<LogEntry>,
    registry: ParticipantRegistry,
    entryPointIdx: Int?,
    exitPointIdx: Int?,
    resolveLabel: (LogEntry) -> String?,
    firstTs: String?,
    regexContext: RegexEvaluationContext,
    cancellationCheck: CancellationCheck,
    gen: RawGen,
    warnings: MutableList<String>,
) {
    when (spec.mode) {
        ArrowMode.EVIDENCE_FLOW -> runEvidenceFlow(
            entries, registry, entryPointIdx, exitPointIdx, spec.options, resolveLabel, firstTs, cancellationCheck, gen,
        )

        ArrowMode.RULES -> runRules(
            entries, registry, spec.rules, spec.options, resolveLabel,
            firstTs, regexContext, cancellationCheck, gen, warnings,
        )

        ArrowMode.LINE_PER_MESSAGE -> runLinePerMessage(
            entries, registry, spec.options, resolveLabel, firstTs, cancellationCheck, gen,
        )
    }
    // EVIDENCE_FLOW's whole point is to stop guessing arrows — but a diagram that is ENTIRELY
    // self-events (no entry-point actor declared, no thread handoffs on, no correlation found at
    // all) can read as "the feature is broken" rather than "this log has no correlatable
    // structure". Checked on the mode's own raw output, before source enrichment/actor mirrors
    // (which have their own, separate warnings) — this is specifically about EVIDENCE_FLOW's own
    // inference finding nothing to draw.
    if (spec.mode == ArrowMode.EVIDENCE_FLOW && gen.messages.isNotEmpty() && gen.messages.none { it.fromIdx != it.toIdx }) {
        warnings += "No correlated interactions found — every line is shown as an event. " +
            "Enable same-thread handoffs or add interaction rules to draw arrows."
    }
}

// A source-enabled diagram with a trace resolver has one semantic owner. If that resolver
// cannot produce a compatible path, use the ordinary log/evidence fallback; do not mix its
// partial result with the legacy one-hop overlay. The callback-only legacy adapter remains for
// non-source-trace callers and existing explicit component tests.
@Suppress("LongParameterList")
private fun resolveLegacySourceResult(
    spec: SeqDiagramSpec,
    candidateEntries: List<LogEntry>,
    representedEntries: List<LogEntry>,
    registry: ParticipantRegistry,
    resolveTrace: ((List<LogEntry>) -> DiagramResolvedTrace)?,
    resolveSourceInteractions: (LogEntry) -> List<DiagramSourceInteraction>,
    cancellationCheck: CancellationCheck,
    warnings: MutableList<String>,
): SourceInteractionResult {
    val sourceResult = if (spec.sourceEnrichment.enabled && resolveTrace == null) {
        // Resolve the same bounded entry window even when runtime fallbacks already fill the
        // message cap. A uniquely resolved source call can replace that entry's SELF event, so
        // budgeting source work against the unused capacity would make source enrichment silently
        // disappear on every ordinary (60-line) diagram.
        val sourceBudget = spec.options.maxMessages
        val stackInteractions = inferStackInteractions(candidateEntries, spec.components)
        buildSourceInteractions(
            representedEntries, candidateEntries, stackInteractions,
            registry, spec.options, spec.sourceEnrichment,
            resolveSourceInteractions, cancellationCheck, warnings, sourceBudget,
        )
    } else {
        SourceInteractionResult()
    }
    // The initial evidence-flow pass can only see pid/tid handoffs and actors.  Source enrichment
    // is a second, stronger evidence pass, so an all-self warning produced before it ran would be
    // false for a source-backed diagram (and was particularly confusing in the inspector).
    if (sourceResult.messages.any { it.fromIdx != it.toIdx }) {
        warnings.removeAll { it.startsWith("No correlated interactions found") }
    }
    return sourceResult
}

private fun appendTraceDiagnosticWarnings(projectedTrace: DiagramResolvedTrace?, warnings: MutableList<String>) {
    if (projectedTrace == null) return
    projectedTrace.diagnostics.droppedByReason.forEach { (reason, count) ->
        warnings += "Source trace dropped $count candidate(s): ${reason.name.lowercase()}"
    }
    projectedTrace.diagnostics.diagnostics.take(32).forEach { diagnostic ->
        val entry = diagnostic.entryId?.let { " entry $it" }.orEmpty()
        val detail = diagnostic.detail?.let { ": $it" }.orEmpty()
        warnings += "Source trace ${diagnostic.reason.name.lowercase()}$entry$detail"
    }
}

@Suppress("LongParameterList")
private fun buildDiagramFrames(
    spec: SeqDiagramSpec,
    tab: LogTab,
    allVisible: List<LogEntry>,
    resolved: RangeResolution,
    rawMessages: List<DiagramMessage>,
    finalMapping: IntArray,
    cappedMessageCount: Int,
    cancellationCheck: CancellationCheck,
): List<DiagramFrame> {
    if (!spec.options.seqGroupFrames) return emptyList()
    return buildFrames(tab, allVisible, resolved.startIdx, resolved.endIdxExclusive, rawMessages, finalMapping, cappedMessageCount, cancellationCheck)
}

private fun buildDiagramActivations(
    spec: SeqDiagramSpec,
    sourceTraceUsable: Boolean,
    projectedTrace: DiagramResolvedTrace?,
    rawMessages: List<DiagramMessage>,
    finalMapping: IntArray,
    cappedMessages: List<DiagramMessage>,
): List<DiagramActivationSpan> {
    if (spec.options.activationPolicy != ActivationPolicy.EVIDENCE_BACKED) return emptyList()
    // sourceTraceUsable already implies projectedTrace != null (see resolveTraceAndRegistry); the
    // explicit null-check here is only to satisfy the compiler, since that implication doesn't
    // hold as a directly-inlined boolean expression once the two live in separate parameters.
    if (sourceTraceUsable && projectedTrace != null) {
        return buildTraceActivationSpansFromTrace(rawMessages, projectedTrace.calls, finalMapping, cappedMessages.size)
    }
    return buildLegacyActivationSpans(cappedMessages)
}

private fun resolveEntryPointIdx(
    spec: SeqDiagramSpec,
    registry: ParticipantRegistry,
    sourceTraceComplete: Boolean,
    representedEntries: List<LogEntry>,
): Int? {
    val explicitEntryPointIdx = spec.participants
        .firstOrNull { it.kind == ParticipantKind.ACTOR && it.isEntryPoint }
        ?.let { registry.indexForId(it.id) }
    if (explicitEntryPointIdx != null) return explicitEntryPointIdx
    val shouldCreateTransientCaller = spec.authoringMode == DiagramAuthoringMode.INFERRED &&
        spec.mode == ArrowMode.EVIDENCE_FLOW &&
        !sourceTraceComplete &&
        representedEntries.any { registry.indexForTag(it.tag) != null }
    return if (shouldCreateTransientCaller) registry.resolveOrCreateTransientCaller() else null
}

private class DiagramSourceResolution(val selectedSourceResult: SourceInteractionResult, val withTransientCaller: SourceInteractionResult)

@Suppress("LongParameterList")
private fun resolveDiagramSourceResult(
    projectedTrace: DiagramResolvedTrace?,
    sourceTraceUsable: Boolean,
    sourceTraceComplete: Boolean,
    representedEntries: List<LogEntry>,
    registry: ParticipantRegistry,
    spec: SeqDiagramSpec,
    resolveLabel: (LogEntry) -> String?,
    firstTs: String?,
    warnings: MutableList<String>,
    fallbackSourceResult: SourceInteractionResult,
    entryPointIdx: Int?,
): DiagramSourceResolution {
    // Frames rely on entry-id order; sorting is stable, so source edges for one entry stay after
    // that entry's runtime edge while no longer being appended after the whole range.
    // A uniquely resolved source relationship is stronger evidence than the fallback SELF that
    // evidence-flow emits for a line. Promote only one source CALL per entry; if the source
    // resolver returns several candidates, keep the original SELF and leave the enrichment
    // messages supplemental rather than choosing an arbitrary endpoint.
    val traceResult = projectedTrace?.takeIf { sourceTraceUsable }?.let {
        buildTraceMessages(it, representedEntries, registry, spec.options, resolveLabel, firstTs, warnings)
    }
    val selectedSourceResult = traceResult ?: fallbackSourceResult
    val withTransientCaller = if (
        !sourceTraceComplete && entryPointIdx != null && selectedSourceResult.messages.isNotEmpty()
    ) {
        val firstPrimary = selectedSourceResult.messages.indexOfFirst { it.primary }
        if (firstPrimary >= 0) {
            selectedSourceResult.copy(messages = selectedSourceResult.messages.toMutableList().also { messages ->
                val first = messages[firstPrimary]
                messages[firstPrimary] = first.copy(
                    fromIdx = entryPointIdx,
                    kind = MessageKind.CALL,
                    evidence = MessageEvidence.LOG,
                )
            })
        } else {
            selectedSourceResult
        }
    } else {
        selectedSourceResult
    }
    return DiagramSourceResolution(selectedSourceResult, withTransientCaller)
}

private class MessagePostProcessResult(
    val rawMessages: List<DiagramMessage>,
    val cappedMessages: List<DiagramMessage>,
    val cappingTruncated: Boolean,
    val notes: List<DiagramNoteMark>,
    val finalMapping: IntArray,
)

private fun postProcessDiagramMessages(
    mergedMessages: List<DiagramMessage>,
    spec: SeqDiagramSpec,
    registry: ParticipantRegistry,
    pendingNotes: List<PendingNote>,
    representedEntries: List<LogEntry>,
    warnings: MutableList<String>,
): MessagePostProcessResult {
    val orderedMessages = assignEdgeOrdinals(mergedMessages.sortedWith(
        compareBy<DiagramMessage> { it.entryId }
            .thenBy { if (!it.primary && it.kind == MessageKind.CALL) 0 else 1 },
    ))
    val correctedMessages = applyCallOverrides(orderedMessages, spec.callOverrides, registry)
    val rawMessages = applyActorMirrors(correctedMessages, registry, spec.actors)
    // showSelfMessages/showSourceInferred drop messages BEFORE collapsing — buildNotes/buildFrames
    // still address messages by their position in rawMessages (an index space neither the filter
    // nor collapseRepeats can be allowed to desync), so filterMessages hands back a mapping from
    // every raw index to where that message (or, for a dropped one, the nearest SURVIVING message
    // that comes after it) landed post-filter, composed below with collapseRepeats' own raw(now
    // filtered)-to-collapsed mapping. See filterMessages' own doc for the out-of-range sentinel.
    val filterResult = filterMessages(rawMessages, spec.options)
    val collapsed = if (spec.options.collapseRepeats) collapseRepeats(filterResult.messages) else identityCollapse(filterResult.messages)
    // Origin-addressed overrides deliberately run after repeat collapse and actor mirroring. A
    // correction therefore fans out to every rendered representation carrying that origin rather
    // than accidentally changing just one pre-collapse raw row.
    val overrideResult = applyMessageOverrides(collapsed.messages, spec.messageOverrides, registry)
    val primaryMessages = overrideResult.messages.filter { it.primary }
    // Structural interactions are optional; selected log evidence is not. Retain every primary
    // event even if that exceeds maxMessages, then use any remaining budget for structure.
    val structuralBudget = (spec.options.maxMessages - primaryMessages.size).coerceAtLeast(0)
    val retainedStructural = overrideResult.messages.filterNot { it.primary }.take(structuralBudget).toSet()
    val cappedMessages = overrideResult.messages.filter { it.primary || it in retainedStructural }
    val cappingTruncated = cappedMessages.size < overrideResult.messages.size
    val expectedPrimaryIds = representedEntries.mapTo(linkedSetOf()) { it.id }
    val actualPrimaryIds = cappedMessages.filter { it.primary }.flatMapTo(linkedSetOf()) { it.representedEntryIds }
    if (actualPrimaryIds != expectedPrimaryIds) {
        warnings += "Primary event invariant failed; falling back to the represented log rows."
    }
    val finalMapping = IntArray(rawMessages.size) { i ->
        overrideResult.oldToNew.getOrElse(
            collapsed.rawToCollapsedIndex.getOrElse(filterResult.rawToFiltered[i]) { collapsed.messages.size },
        ) { overrideResult.messages.size }
    }
    val notes = buildNotes(pendingNotes, finalMapping, cappedMessages.size)
    return MessagePostProcessResult(rawMessages, cappedMessages, cappingTruncated, notes, finalMapping)
}

private val SOURCE_TRACE_HARD_FAILURES = setOf(
    TraceDiagnosticReason.AMBIGUOUS_SOURCE_SITE,
    TraceDiagnosticReason.LOW_CONFIDENCE,
    TraceDiagnosticReason.STALE_SOURCE_SITE,
    TraceDiagnosticReason.BRANCH_INCOMPATIBLE,
    TraceDiagnosticReason.CALL_GRAPH_GAP,
    TraceDiagnosticReason.UNMAPPED_CALLER,
    TraceDiagnosticReason.UNMAPPED_CALLEE,
)

private val ASYNC_INVOCATION_KINDS = setOf(
    TraceInvocationKind.COROUTINE_LAUNCH,
    TraceInvocationKind.CALLBACK_REGISTRATION,
    TraceInvocationKind.EXECUTOR_DISPATCH,
    TraceInvocationKind.BINDER_OR_RPC,
    TraceInvocationKind.UNKNOWN_ASYNC,
)

// ── Range resolution ──────────────────────────────────────────────────────────────────────────

// startIdx/endIdxExclusive are index bounds into [entries]' own SOURCE list (allVisible) — needed
// afterwards by buildFrames to test seq-group overlap against the SAME index space SeqComputer
// produced its groups in, which [entries] itself (already filtered/reordered for Time ranges)
// can no longer represent on its own.
private class RangeResolution(val entries: List<LogEntry>, val startIdx: Int, val endIdxExclusive: Int)

private fun resolveRange(
    tab: LogTab,
    range: DiagramRange,
    allVisible: List<LogEntry>,
    cancellationCheck: CancellationCheck,
    warnings: MutableList<String>,
): RangeResolution = when (range) {
    is DiagramRange.VisibleView -> RangeResolution(allVisible, 0, allVisible.size)
    is DiagramRange.Ids -> resolveIdsRange(allVisible, range)
    is DiagramRange.Time -> resolveTimeRange(tab, allVisible, range, warnings)
    is DiagramRange.SeqGroupRef -> resolveSeqGroupRange(tab, allVisible, range, cancellationCheck, warnings)
}

private fun assignEdgeOrdinals(messages: List<DiagramMessage>): List<DiagramMessage> {
    val next = HashMap<Int, Int>()
    return messages.map { message ->
        val ordinal = next[message.entryId] ?: 0
        next[message.entryId] = ordinal + 1
        val fallbackOrigin = MessageOriginKey(
            entryId = message.entryId,
            sourceOperationId = message.sourceOperationId,
            sourceLogSiteId = message.sourceLogSiteId,
            invocationId = message.invocationId,
            generatedOrdinal = ordinal,
        )
        message.copy(edgeOrdinal = ordinal, originKeys = message.originKeys.ifEmpty { setOf(fallbackOrigin) })
    }
}

private fun applyCallOverrides(
    messages: List<DiagramMessage>,
    overrides: List<DiagramCallOverride>,
    registry: ParticipantRegistry,
): List<DiagramMessage> {
    if (overrides.isEmpty()) return messages
    val byKey = overrides.associateBy { it.entryId to it.edgeOrdinal }
    return messages.map { message ->
        val override = byKey[message.entryId to message.edgeOrdinal] ?: return@map message
        val from = registry.indexForId(override.fromParticipantId) ?: return@map message
        val to = registry.indexForId(override.toParticipantId) ?: return@map message
        message.copy(
            fromIdx = from,
            toIdx = to,
            kind = if (from == to) MessageKind.SELF else MessageKind.CALL,
            evidence = MessageEvidence.MANUAL_OVERRIDE,
        )
    }
}

/** Applies typed, origin-addressed corrections after legacy entry/ordinal endpoint corrections. */
private class MessageOverrideResult(val messages: List<DiagramMessage>, val oldToNew: IntArray)

private fun applyMessageOverrides(
    messages: List<DiagramMessage>,
    overrides: List<DiagramMessageOverride>,
    registry: ParticipantRegistry,
): MessageOverrideResult {
    if (overrides.isEmpty()) return MessageOverrideResult(messages, IntArray(messages.size) { it })
    val byOrigin = overrides.associateBy { it.origin }
    val kept = BooleanArray(messages.size)
    val indexIfKept = IntArray(messages.size) { -1 }
    val result = ArrayList<DiagramMessage>(messages.size)
    messages.forEachIndexed { index, message ->
        val override = message.originKeys.asSequence().mapNotNull(byOrigin::get).firstOrNull()
        if (override == null) {
            kept[index] = true
            indexIfKept[index] = result.size
            result += message
            return@forEachIndexed
        }
        if (!override.enabled) return@forEachIndexed
        val from = override.fromParticipantId?.let(registry::indexForId) ?: message.fromIdx
        val to = override.toParticipantId?.let(registry::indexForId) ?: message.toIdx
        val label = override.label ?: override.parameters?.let { parameters ->
            val args = parameters.joinToString(", ") { if (it.name.isBlank()) it.value else "${it.name}=${it.value}" }
            "${message.label}($args)"
        } ?: message.label
        val kind = override.kind ?: message.kind
        kept[index] = true
        indexIfKept[index] = result.size
        result += message.copy(
            fromIdx = from,
            toIdx = to,
            label = label,
            kind = if (from == to && kind != MessageKind.RETURN) MessageKind.SELF else kind,
            evidence = MessageEvidence.MANUAL_OVERRIDE,
        )
    }
    val oldToNew = IntArray(messages.size)
    var nextSurviving = result.size
    for (index in messages.indices.reversed()) {
        if (kept[index]) nextSurviving = indexIfKept[index]
        oldToNew[index] = nextSurviving
    }
    return MessageOverrideResult(result, oldToNew)
}

private fun promoteUniqueSourceCalls(
    runtimeMessages: List<DiagramMessage>,
    sourceMessages: List<DiagramMessage>,
    options: DiagramOptions,
): List<DiagramMessage> {
    if (!options.showSourceInferred || sourceMessages.isEmpty()) return runtimeMessages + sourceMessages

    val sourceByEntry = sourceMessages.groupBy { it.entryId }
    val promotableCalls = sourceByEntry.mapNotNull { (entryId, messages) ->
        messages.filter { it.kind == MessageKind.CALL || it.kind == MessageKind.SELF }
            .singleOrNull()
            ?.let { entryId to it }
    }.toMap()
    if (promotableCalls.isEmpty()) return runtimeMessages
    val promotableInvocationIds = promotableCalls.values.mapNotNull { it.invocationId }.toSet()

    val overlaidMessages = mutableSetOf<DiagramMessage>()
    val correctedRuntime = runtimeMessages.map { runtime ->
        overlayRuntimeMessage(runtime, sourceByEntry, promotableCalls, promotableInvocationIds, overlaidMessages)
    }
    return correctedRuntime + sourceMessages.filter { source ->
        isRetainableSourceMessage(source, promotableCalls, promotableInvocationIds, overlaidMessages)
    }
}

// Extracted from promoteUniqueSourceCalls's own per-runtime overlay resolution and per-source-
// message retention check — pulled out purely to keep the caller's own complexity down; each
// does exactly what its inline block used to.
private fun overlayRuntimeMessage(
    runtime: DiagramMessage,
    sourceByEntry: Map<Int, List<DiagramMessage>>,
    promotableCalls: Map<Int, DiagramMessage>,
    promotableInvocationIds: Set<String>,
    overlaidMessages: MutableSet<DiagramMessage>,
): DiagramMessage {
    val sourceEdges = sourceByEntry[runtime.entryId].orEmpty()
    val call = promotableCalls[runtime.entryId]
    val outcome = sourceEdges.singleOrNull { it.kind == MessageKind.RETURN }
    val overlay = when {
        // A source trace can contain only a selected result row.  Its inferred call is
        // supplemental structure, while the selected row remains the primary return.
        call != null && outcome?.traceStatus != null -> outcome
        call != null -> call
        outcome?.invocationId in promotableInvocationIds -> outcome
        else -> null
    } ?: return runtime
    overlaidMessages += overlay
    // The log row is immutable primary evidence. Source inference may improve its endpoints
    // and provenance, but must retain its original label, entry id, timestamp and severity.
    return runtime.copy(
        fromIdx = overlay.fromIdx,
        toIdx = overlay.toIdx,
        kind = when (overlay.kind) {
            MessageKind.RETURN -> MessageKind.RETURN
            else -> if (overlay.fromIdx == overlay.toIdx) MessageKind.SELF else MessageKind.CALL
        },
        evidence = overlay.evidence,
        invocationId = overlay.invocationId,
        traceStatus = overlay.traceStatus,
        invocationKind = overlay.invocationKind,
        primary = runtime.primary,
    )
}

// A tie is not supplemental evidence: emitting both candidates would draw every possible
// caller. Retain a return only when its matching call was uniquely resolved.
private fun isRetainableSourceMessage(
    source: DiagramMessage,
    promotableCalls: Map<Int, DiagramMessage>,
    promotableInvocationIds: Set<String>,
    overlaidMessages: Set<DiagramMessage>,
): Boolean {
    val isUniqueCall = promotableCalls[source.entryId] === source
    val isOutcomeForUniqueCall = source.kind == MessageKind.RETURN &&
        (promotableCalls.containsKey(source.entryId) ||
            (source.invocationId != null && source.invocationId in promotableInvocationIds))
    return (isUniqueCall || isOutcomeForUniqueCall) && source !in overlaidMessages
}

private fun resolveIdsRange(allVisible: List<LogEntry>, range: DiagramRange.Ids): RangeResolution {
    if (allVisible.isEmpty()) return RangeResolution(emptyList(), 0, 0)
    if (range.selectedIds.isNotEmpty()) {
        val exact = allVisible.filter { it.id in range.selectedIds }
        val first = allVisible.indexOfFirst { it.id in range.selectedIds }.coerceAtLeast(0)
        return RangeResolution(exact, first, (allVisible.indexOfLast { it.id in range.selectedIds } + 1).coerceAtLeast(first))
    }
    val minId = minOf(range.from, range.to)
    val maxId = maxOf(range.from, range.to)
    val ids = IntArray(allVisible.size) { allVisible[it].id }
    // Clamp to the nearest in-range index rather than requiring an exact id match — a caller's
    // selection can legitimately name ids the current filter is hiding (see DiagramRange.Ids' doc).
    var lo = 0
    var hi = ids.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (ids[mid] < minId) lo = mid + 1 else hi = mid
    }
    val startIdx = lo
    lo = 0
    hi = ids.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (ids[mid] <= maxId) lo = mid + 1 else hi = mid
    }
    val endIdxExclusive = lo
    if (startIdx >= endIdxExclusive) return RangeResolution(emptyList(), startIdx, startIdx)
    return RangeResolution(allVisible.subList(startIdx, endIdxExclusive), startIdx, endIdxExclusive)
}

private fun resolveTimeRange(
    tab: LogTab,
    allVisible: List<LogEntry>,
    range: DiagramRange.Time,
    warnings: MutableList<String>,
): RangeResolution {
    // "No parseable ts anywhere in the tab" is the one hard-failure case this function reports via
    // warnings (see DiagramRange.Time's own doc) — checked with its own cheap, allocation-free scan
    // rather than folded into the merged walk below, so it still wins over a malformed range bound
    // even when both are true at once, exactly like before this function stopped building a map.
    val anyParsed = tab.logData.any { parseMillisOfDay(it.ts) != TS_UNKNOWN }
    if (!anyParsed) {
        warnings += "No row in this tab has a parseable HH:MM:SS timestamp — cannot resolve a time range."
        return RangeResolution(emptyList(), 0, 0)
    }
    val fromMs = parseMillisOfDay(range.fromTs)
    val toMs = parseMillisOfDay(range.toTs)
    if (fromMs == TS_UNKNOWN || toMs == TS_UNKNOWN) {
        val bad = if (fromMs == TS_UNKNOWN) range.fromTs else range.toTs
        warnings += "Time range bound '$bad' is not a parseable HH:MM:SS[.mmm] timestamp."
        return RangeResolution(emptyList(), 0, 0)
    }
    val minMs = minOf(fromMs, toMs)
    val maxMs = maxOf(fromMs, toMs)

    // Carry-forward runs over the WHOLE tab (not just the filtered view) so a filter that happens
    // to hide the one row carrying a real timestamp doesn't strand every visible row as
    // unparseable. tab.logData and allVisible are both strictly ascending by id, and allVisible is
    // a subsequence of tab.logData, so a single merged walk — a cursor into allVisible advanced in
    // lockstep with the tab.logData scan — finds every matching visible row in O(n) time and
    // O(matches) space. This replaces a HashMap<Int, Long> keyed by every entry id in the tab: at
    // this app's documented 10M-line scale (largeFileMode, the perf comments throughout Filter.kt)
    // that map was ~500MB-1GB of boxed Integer/Long entries just to answer "which visible rows fall
    // in this window" — the exact boxing cost Filter.kt's idBitSet helper was written to avoid.
    var prevMs = TS_UNKNOWN
    var cursor = 0
    val matchedIndices = ArrayList<Int>()
    for (e in tab.logData) {
        val ms = parseMillisOfDay(e.ts)
        if (ms != TS_UNKNOWN) prevMs = ms
        // Skip past any allVisible entries the current logData position has already passed (can
        // only happen right after a match below); then, if this logData row IS the next visible
        // one, test its carried timestamp and consume it. A row absent from allVisible (filtered
        // out) is simply never a cursor match, so it contributes to prevMs's carry-forward without
        // ever being collected — same as the old map-based version's behavior.
        while (cursor < allVisible.size && allVisible[cursor].id < e.id) cursor++
        if (cursor < allVisible.size && allVisible[cursor].id == e.id) {
            if (prevMs != TS_UNKNOWN && prevMs in minMs..maxMs) matchedIndices += cursor
            cursor++
        }
    }
    if (matchedIndices.isEmpty()) return RangeResolution(emptyList(), 0, 0)
    return RangeResolution(matchedIndices.map { allVisible[it] }, matchedIndices.first(), matchedIndices.last() + 1)
}

private fun resolveSeqGroupRange(
    tab: LogTab,
    allVisible: List<LogEntry>,
    range: DiagramRange.SeqGroupRef,
    cancellationCheck: CancellationCheck,
    warnings: MutableList<String>,
): RangeResolution {
    // cachedSeqGroupsFor null means "no cheap answer available right now", never "no groups
    // exist" — fall back to computing them fresh against the exact same list, exactly like every
    // other cachedSeqGroupsFor caller in this codebase.
    val groups = cachedSeqGroupsFor(tab, true) ?: computeSeqGroups(allVisible, tab.filter.sequences, cancellationCheck)
    val ids = IntArray(allVisible.size) { allVisible[it].id }

    fun idxOf(id: Int): Int? = Arrays.binarySearch(ids, id).takeIf { it >= 0 }

    for (sg in groups) {
        if (sg.gid == range.gid) {
            val startIdx = idxOf(sg.rid) ?: continue
            val endEx = minOf(sg.endExclusive, allVisible.size)
            return RangeResolution(allVisible.subList(startIdx, endEx), startIdx, endEx)
        }
        for (ng in sg.nested) {
            if (ng.gid == range.gid) {
                val startIdx = idxOf(ng.rid) ?: continue
                val endEx = minOf(ng.endExclusive, allVisible.size)
                return RangeResolution(allVisible.subList(startIdx, endEx), startIdx, endEx)
            }
        }
    }
    warnings += "Sequence group '${range.gid}' was not found in the current view."
    return RangeResolution(emptyList(), 0, 0)
}

// ── Participant derivation ────────────────────────────────────────────────────────────────────

// Splits caller-supplied participants into ACTORs (always kept verbatim) and TAG participants
// (kept verbatim if the caller supplied any; otherwise auto-derived from the candidate range's own
// tag frequency, capped at DEFAULT_MAX_AUTO_PARTICIPANTS) — see SeqDiagramSpec.participants' doc.
// The returned entry list drops anything whose tag isn't among the resulting TAG participants, so
// every downstream mode only ever sees entries with a home lifeline.
private data class ParticipantResolution(
    val participants: List<DiagramParticipant>,
    val representedEntries: List<LogEntry>,
    val groupedTags: Set<String>,
    val coverage: DiagramCoverage,
    val tagToParticipantId: Map<String, String> = emptyMap(),
)

/** Apply the enabled tag/component projection before source evidence reaches participants. */
private fun DiagramResolvedTrace.projectTo(entries: List<LogEntry>): DiagramResolvedTrace {
    val representedIds = entries.mapTo(HashSet()) { it.id }
    return copy(
        events = events.filter { it.entryId in representedIds },
        calls = calls.filter { it.callEntryId in representedIds },
    )
}

private fun addTraceParticipants(
    base: ParticipantResolution,
    spec: SeqDiagramSpec,
    trace: DiagramResolvedTrace?,
): ParticipantResolution {
    if (trace == null) return base
    val owners = (trace.calls.flatMap { listOf(it.callerOwnerType, it.calleeOwnerType) } +
        trace.events.mapNotNull { it.ownerType })
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()
    if (owners.isEmpty()) return base
    val participants = base.participants.toMutableList()
    val usedIds = participants.mapTo(HashSet()) { it.id }
    owners.forEach { owner ->
        val receiverRole = trace.calls.firstOrNull {
            it.calleeOwnerType == owner && !it.receiverRole.isNullOrBlank()
        }?.receiverRole
        val explicitlyBound = spec.components.count { component ->
            component.enabled && (owner in component.sourceOwnerTypes || owner.substringBefore('$') in component.sourceOwnerTypes)
        }
        val alreadyBound = participants.any { participant ->
            participant.sourceOwnerType == owner ||
                participant.id == owner || participant.id == owner.substringAfterLast('.')
        }
        if (explicitlyBound == 0 && !alreadyBound) {
            val baseId = "source:$owner"
            val id = generateSequence(baseId) { "${it}_" }.first { it !in usedIds }
            usedIds += id
            participants += DiagramParticipant(
                id = id,
                label = owner.substringAfterLast('.').let { simple ->
                    receiverRole?.takeUnless { it.equals("UNKNOWN", true) }?.let { role ->
                        "$simple [${role.lowercase().replace('_', ' ')}]"
                    } ?: simple
                },
                kind = ParticipantKind.TAG,
                sourceOwnerType = owner,
                receiverRole = receiverRole?.takeUnless { it.equals("UNKNOWN", true) },
                inferred = true,
            )
        }
    }
    return base.copy(participants = participants)
}

private fun sourceOwnerBindings(spec: SeqDiagramSpec, participants: List<DiagramParticipant>): Map<String, String> {
    val bindings = LinkedHashMap<String, String>()
    spec.components.filter { it.enabled }.forEach { component ->
        component.sourceOwnerTypes.forEach { owner ->
            if (owner.isNotBlank()) bindings.putIfAbsent(owner, component.id)
            if (owner.contains('$')) bindings.putIfAbsent(owner.substringBefore('$'), component.id)
        }
    }
    participants.forEach { participant ->
        participant.sourceOwnerType?.takeIf { it.isNotBlank() }?.let { bindings.putIfAbsent(it, participant.id) }
    }
    return bindings
}

/** Returns range-correct candidates for the participant inspector. It resolves [spec.range] against
 * the same raw-or-filtered source list selected by [DiagramOptions.includeRowsHiddenByFilter] as
 * [buildSequenceDiagram]. */
fun diagramParticipantCandidates(
    tab: LogTab,
    spec: SeqDiagramSpec,
    cancellationCheck: CancellationCheck = CancellationCheck {},
): List<DiagramParticipantCandidate> {
    val warnings = mutableListOf<String>()
    val sourceEntries = if (spec.options.includeRowsHiddenByFilter) tab.logData else visibleEntries(tab, applyFilter = true)
    val resolved = resolveRange(tab, spec.range, sourceEntries, cancellationCheck, warnings)
    if (spec.components.isNotEmpty()) return componentCandidates(spec, resolved.entries, cancellationCheck)
    val configured = spec.participants.filter { it.kind == ParticipantKind.TAG && it.tag != null }.associateBy { it.tag!! }
    val stats = tagStats(resolved.entries, cancellationCheck)
    val automaticallyShown = if (configured.isEmpty()) {
        stats.counts.entries.sortedWith(tagScoreComparator(stats)).take(DEFAULT_MAX_AUTO_PARTICIPANTS).map { it.key }.toSet()
    } else {
        emptySet()
    }
    return stats.counts.entries.sortedWith(tagScoreComparator(stats)).map { (tag, count) ->
        val participant = configured[tag]
        DiagramParticipantCandidate(
            tag = tag,
            entryCount = count,
            transitionCount = stats.transitions[tag] ?: 0,
            errorCount = stats.errors[tag] ?: 0,
            representation = participant?.representation
                ?: when {
                    configured.isNotEmpty() -> DiagramParticipantRepresentation.HIDE
                    tag !in automaticallyShown -> DiagramParticipantRepresentation.OTHER
                    else -> DiagramParticipantRepresentation.SHOW
                },
            participant = participant,
            pids = stats.pids[tag]?.toSet() ?: emptySet(),
            signalScore = stats.signalScore(tag),
        )
    }
}

// Per-tag counts from a single scan over a resolved range. Shared by both candidate paths —
// diagramParticipantCandidates' legacy branch just above (the one actually reachable from the UI:
// SeqDiagramCoordinator.requestCandidates always normalises spec.components to emptyList() before
// calling in, so the componentCandidates guard right below never fires in practice) and
// componentCandidates itself — so a candidate's pids are populated regardless of which branch runs.
private class TagStats {
    val counts = LinkedHashMap<String, Int>()
    val transitions = HashMap<String, Int>()
    val errors = HashMap<String, Int>()
    val pids = HashMap<String, MutableSet<Int>>()
    val templates = HashMap<String, MutableSet<String>>()

    // Tags observed sharing a real (non-zero) pid+tid with this tag — the exact evidence
    // resolveEvidenceEdge's thread-handoff branch requires to draw a CALL. Without this signal, a
    // high-volume/low-diversity tag that is genuinely part of a pid/tid call chain scores low on
    // errors/templates alone and gets ranked into the shared "Other" lifeline; if both ends of a
    // handoff land in "Other", fromIdx == toIdx and the CALL silently degrades into a same-lifeline
    // SELF. See signalScore.
    val threadPeers = HashMap<String, MutableSet<String>>()

    fun signalScore(tag: String): Int =
        4 * (errors[tag] ?: 0) + 2 * (templates[tag]?.size ?: 0) +
            THREAD_PEER_SCORE_WEIGHT * (threadPeers[tag]?.size ?: 0) + minOf(counts[tag] ?: 0, 10)
}

private fun tagStats(entries: List<LogEntry>, cancellationCheck: CancellationCheck): TagStats {
    val stats = TagStats()
    var previous: String? = null
    // Distinct real (pid, tid) pairs seen, each mapped to the tags observed under it. Cardinality is
    // bounded by actual thread diversity (not entry count), so this stays cheap even at this file's
    // documented large-range scale.
    val tagsByThread = HashMap<Pair<Int, Int>, MutableSet<String>>()
    entries.forEachIndexed { index, entry ->
        if (index % CANCELLATION_CHECK_INTERVAL == 0) cancellationCheck()
        stats.counts[entry.tag] = (stats.counts[entry.tag] ?: 0) + 1
        if (errorLevel(entry.level)) stats.errors[entry.tag] = (stats.errors[entry.tag] ?: 0) + 1
        stats.templates.getOrPut(entry.tag) { linkedSetOf() }.add(normalizeMessageTemplate(entry.msg))
        previous?.takeIf { it != entry.tag }?.let { prior ->
            stats.transitions[prior] = (stats.transitions[prior] ?: 0) + 1
            stats.transitions[entry.tag] = (stats.transitions[entry.tag] ?: 0) + 1
        }
        previous = entry.tag
        val pidsForTag = stats.pids.getOrPut(entry.tag) { HashSet() }
        if (pidsForTag.size < MAX_CANDIDATE_PIDS) pidsForTag.add(entry.pid)
        if (entry.pid != 0 && entry.tid != 0) {
            tagsByThread.getOrPut(entry.pid to entry.tid) { HashSet() }.add(entry.tag)
        }
    }
    tagsByThread.values.forEach { threadTags ->
        if (threadTags.size < 2) return@forEach
        threadTags.forEach { tag ->
            val peers = stats.threadPeers.getOrPut(tag) { HashSet() }
            if (peers.size < MAX_THREAD_PEER_TAGS) peers += (threadTags - tag).take(MAX_THREAD_PEER_TAGS - peers.size)
        }
    }
    return stats
}

private fun tagScoreComparator(stats: TagStats): Comparator<Map.Entry<String, Int>> =
    compareByDescending<Map.Entry<String, Int>> { stats.signalScore(it.key) }
        .thenByDescending { it.value }
        .thenBy { it.key }

private val TEMPLATE_UUID_RE = Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""")
private val TEMPLATE_HEX_RE = Regex("""(?i)\b[0-9a-f]{16,}\b""")
private val TEMPLATE_NUMBER_RE = Regex("""\b\d+(?:\.\d+)?\b""")

private fun normalizeMessageTemplate(message: String): String = message
    .lowercase()
    .replace(TEMPLATE_UUID_RE, "<uuid>")
    .replace(TEMPLATE_HEX_RE, "<hex>")
    .replace(TEMPLATE_NUMBER_RE, "<num>")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun componentCandidates(
    spec: SeqDiagramSpec,
    entries: List<LogEntry>,
    cancellationCheck: CancellationCheck,
): List<DiagramParticipantCandidate> {
    val owner = LinkedHashMap<String, DiagramComponent>()
    spec.components.filter { it.enabled }.forEach { component -> component.tagIds.forEach { owner.putIfAbsent(it, component) } }
    val stats = tagStats(entries, cancellationCheck)
    return stats.counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { (tag, count) ->
        val component = owner[tag]
        DiagramParticipantCandidate(
            tag = tag,
            entryCount = count,
            transitionCount = stats.transitions[tag] ?: 0,
            errorCount = stats.errors[tag] ?: 0,
            representation = when {
                component != null -> DiagramParticipantRepresentation.SHOW
                spec.unmappedTagPolicy == UnmappedTagPolicy.GROUP_AS_OTHER -> DiagramParticipantRepresentation.OTHER
                else -> DiagramParticipantRepresentation.HIDE
            },
            participant = component?.let { DiagramParticipant(it.id, it.displayName, ParticipantKind.TAG, tag = tag) },
            pids = stats.pids[tag]?.toSet() ?: emptySet(),
        )
    }
}

private fun resolveTagParticipants(
    specParticipants: List<DiagramParticipant>,
    candidateEntries: List<LogEntry>,
    cancellationCheck: CancellationCheck,
): ParticipantResolution {
    val actors = specParticipants.filter { it.kind == ParticipantKind.ACTOR }
    val givenTags = specParticipants.filter { it.kind == ParticipantKind.TAG }
    val tagParticipants: List<DiagramParticipant>
    val groupedTags: Set<String>
    val hiddenTags: Set<String>
    if (givenTags.isNotEmpty()) {
        tagParticipants = givenTags.filter { it.representation == DiagramParticipantRepresentation.SHOW }
        groupedTags = givenTags.filter { it.representation == DiagramParticipantRepresentation.OTHER }.mapNotNull { it.tag }.toSet()
        // A supplied TAG list is an explicit curation. Tags not in it retain the legacy meaning:
        // they are hidden rather than silently becoming lifelines again.
        hiddenTags = candidateEntries.asSequence().map { it.tag }.filter { tag ->
            givenTags.none { it.tag == tag && it.representation != DiagramParticipantRepresentation.HIDE }
        }.toSet() + givenTags.filter { it.representation == DiagramParticipantRepresentation.HIDE }.mapNotNull { it.tag }
    } else {
        val stats = tagStats(candidateEntries, cancellationCheck)
        val rankedTags = stats.counts.entries.sortedWith(tagScoreComparator(stats))
        tagParticipants = rankedTags.take(DEFAULT_MAX_AUTO_PARTICIPANTS)
            .map { (tag, _) -> DiagramParticipant(id = tag, label = tag, kind = ParticipantKind.TAG, tag = tag) }
        groupedTags = rankedTags.drop(DEFAULT_MAX_AUTO_PARTICIPANTS).map { it.key }.toSet()
        hiddenTags = emptySet()
    }
    val other = groupedTags.takeIf { it.isNotEmpty() }?.let {
        val ids = (actors + tagParticipants).map { p -> p.id }.toHashSet()
        val id = generateSequence("Other") { "${it}_" }.first { candidate -> candidate !in ids }
        DiagramParticipant(id = id, label = "Other", kind = ParticipantKind.TAG, representation = DiagramParticipantRepresentation.OTHER)
    }
    val shownTags = tagParticipants.mapNotNull { it.tag }.toSet()
    val representedEntries = candidateEntries.filter { it.tag in shownTags || it.tag in groupedTags }
    val coverage = DiagramCoverage(
        scannedEntries = candidateEntries.size,
        shownEntries = candidateEntries.count { it.tag in shownTags },
        groupedEntries = candidateEntries.count { it.tag in groupedTags },
        hiddenEntries = candidateEntries.count { it.tag in hiddenTags || (it.tag !in shownTags && it.tag !in groupedTags) },
    )
    return ParticipantResolution(actors + tagParticipants + listOfNotNull(other), representedEntries, groupedTags, coverage)
}

/** Component selection is intentionally separate from legacy participants: an enabled component
 * owns all of its tags, and one global policy controls every remaining in-range tag. */
private fun resolveComponentParticipants(spec: SeqDiagramSpec, candidateEntries: List<LogEntry>): ParticipantResolution {
    // Manual authoring treats configured components as an available editing palette. Rendering
    // still prunes unused lifelines in ManualDiagramBuilder, so this does not make disabled
    // components appear until an interaction is assigned to them.
    val available = spec.components.filter { it.enabled || spec.authoringMode == DiagramAuthoringMode.MANUAL }
    val tagToComponent = LinkedHashMap<String, DiagramComponent>()
    available.forEach { component -> component.tagIds.forEach { tag -> tagToComponent.putIfAbsent(tag, component) } }
    val participants = buildList {
        // Preserve old entry/exit actors when a migrated/partially edited spec still has them.
        addAll(spec.participants.filter { it.kind == ParticipantKind.ACTOR })
        spec.actors.forEach { actor ->
            if (none { it.id == actor.id }) add(DiagramParticipant(actor.id, actor.label, ParticipantKind.ACTOR))
        }
        available.forEach { component ->
            add(DiagramParticipant(component.id, component.displayName, ParticipantKind.TAG))
        }
    }
    val unmapped = candidateEntries.asSequence().map { it.tag }.filter { it !in tagToComponent }.toSet()
    val groupedTags = if (spec.unmappedTagPolicy == UnmappedTagPolicy.GROUP_AS_OTHER) unmapped else emptySet()
    val withOther = if (groupedTags.isEmpty()) {
        participants
    } else {
        val existing = participants.mapTo(HashSet()) { it.id }
        val id = generateSequence("Other") { "${it}_" }.first { it !in existing }
        participants + DiagramParticipant(id, "Other", ParticipantKind.TAG, representation = DiagramParticipantRepresentation.OTHER)
    }
    val shownTags = tagToComponent.keys
    val represented = candidateEntries.filter { it.tag in shownTags || it.tag in groupedTags }
    return ParticipantResolution(
        participants = withOther,
        representedEntries = represented,
        groupedTags = groupedTags,
        coverage = DiagramCoverage(
            scannedEntries = candidateEntries.size,
            shownEntries = candidateEntries.count { it.tag in shownTags },
            groupedEntries = candidateEntries.count { it.tag in groupedTags },
            hiddenEntries = candidateEntries.count { it.tag !in shownTags && it.tag !in groupedTags },
        ),
        tagToParticipantId = tagToComponent.mapValues { it.value.id },
    )
}

// Mutable participant list threaded through a scan — a TAG participant is fixed up front, but an
// ACTOR referenced by an unrecognized RULES-mode from/to name is only discovered mid-scan, so the
// final SeqDiagram.participants list can't be known until the scan completes.
private class ParticipantRegistry(
    initial: List<DiagramParticipant>,
    groupedTags: Set<String> = emptySet(),
    tagToParticipantId: Map<String, String> = emptyMap(),
    sourceOwnerBindings: Map<String, String> = emptyMap(),
) {
    val list: MutableList<DiagramParticipant> = initial.toMutableList()
    private val idxByTag = HashMap<String, Int>()
    private val idxById = HashMap<String, Int>()
    private val idxBySourceOwner = HashMap<String, Int>()

    init {
        list.forEachIndexed { i, p ->
            if (p.kind == ParticipantKind.TAG && p.tag != null) idxByTag[p.tag] = i
            idxById[p.id] = i
        }
        val otherIdx = list.indexOfFirst { it.kind == ParticipantKind.TAG && it.representation == DiagramParticipantRepresentation.OTHER }
        if (otherIdx >= 0) groupedTags.forEach { idxByTag[it] = otherIdx }
        tagToParticipantId.forEach { (tag, participantId) -> idxById[participantId]?.let { idxByTag[tag] = it } }
        sourceOwnerBindings.forEach { (owner, participantId) ->
            idxById[participantId]?.let { idxBySourceOwner[owner] = it }
        }
        list.forEachIndexed { index, participant ->
            participant.sourceOwnerType?.takeIf { it.isNotBlank() }?.let { owner ->
                idxBySourceOwner.putIfAbsent(owner, index)
                idxBySourceOwner.putIfAbsent(owner.substringBefore('$'), index)
            }
        }
    }

    fun indexForTag(tag: String): Int? = idxByTag[tag]

    fun indexForId(id: String): Int? = idxById[id]

    fun indexForSourceOwner(owner: String): Int? {
        val clean = owner.trim()
        if (clean.isEmpty()) return null
        return idxBySourceOwner[clean]
            ?: idxBySourceOwner[clean.substringBefore('$')]
            ?: idxById[clean]
            ?: idxById[clean.substringAfterLast('.')]
    }

    /** Resolves [name] against both the id and tag namespaces first (a rule can legitimately
     *  name an existing TAG participant), only creating a brand-new ACTOR when neither matches.
     *  Second element of the pair is true exactly when a new participant was created. */
    fun resolveOrCreateActor(name: String): Pair<Int, Boolean> {
        val key = name.ifBlank { "?" }
        idxById[key]?.let { return it to false }
        idxByTag[key]?.let { return it to false }
        val participant = DiagramParticipant(id = key, label = key, kind = ParticipantKind.ACTOR)
        list += participant
        val idx = list.lastIndex
        idxById[key] = idx
        return idx to true
    }

    /** Creates only an explicitly declared typed-rule actor. Never used for captured values. */
    fun resolveOrCreateExplicitActor(id: String, label: String): Int? {
        val key = id.trim()
        if (key.isEmpty()) return null
        idxById[key]?.let { return it }
        val participant = DiagramParticipant(id = key, label = label.ifBlank { key }, kind = ParticipantKind.ACTOR)
        list += participant
        val idx = list.lastIndex
        idxById[key] = idx
        return idx
    }

    /** Adds the inferred opening actor only to this build's registry, never to the durable spec. */
    fun resolveOrCreateTransientCaller(): Int {
        val id = generateSequence("Caller") { "${it}_" }.first { it !in idxById }
        val participant = DiagramParticipant(
            id = id,
            label = "Caller",
            kind = ParticipantKind.ACTOR,
            isEntryPoint = true,
            inferred = true,
        )
        list += participant
        idxById[id] = list.lastIndex
        return list.lastIndex
    }
}

// ── Message generation ────────────────────────────────────────────────────────────────────────

private fun errorLevel(level: LogLevel): Boolean = level == LogLevel.E || level == LogLevel.A

private class PendingNote(val rawIndex: Int, val participantIdx: Int, val text: String)

private class RawGen(
    val messages: MutableList<DiagramMessage> = mutableListOf(),
    val notes: MutableList<PendingNote> = mutableListOf(),
)

// Two thread-pool tasks reusing a recycled tid minutes or hours apart are not a call — this bounds
// runEvidenceFlow's same-thread handoff correlation to a gap short enough that it can only
// plausibly be one synchronous call chain still running on the same OS thread. Not user-tunable:
// a caller who needs a different bound has interaction rules (ArrowMode.RULES) available instead.
private const val THREAD_HANDOFF_MAX_GAP_MS = 250L

/** The single place that decides CALL-with-evidence vs. bare SELF for one entry, shared by
 *  [runEvidenceFlow] and [runRulesFallthrough] so the decision can never diverge between the two
 *  callers — see this file's own header doc for the "evidence, not guessing" rule this encodes. */
private class EvidenceEdge(val fromIdx: Int, val kind: MessageKind, val evidence: MessageEvidence)

// Same-thread handoff, opt-in: a real (non-zero) pid+tid match within a short time bound is
// actual OS-level evidence that this line is a continuation of the previous one's call chain.
// entry.tid != 0 is non-negotiable: LogEntry.pid/tid both default to 0, so brief/RAW logcat
// (which carries neither) would otherwise correlate an entire log into one fake thread —
// exactly reproducing the bug this mode exists to remove. Extracted (along with the two
// conditions below) purely to keep resolveEvidenceEdge's own complexity down.
private fun isThreadHandoff(idx: Int, entry: LogEntry, options: DiagramOptions, prevIdx: Int?, prevEntry: LogEntry?): Boolean =
    options.threadHandoffArrows && prevIdx != null && prevEntry != null && prevIdx != idx &&
        entry.pid != 0 && entry.tid != 0 && prevEntry.pid != 0 && prevEntry.tid != 0 &&
        entry.tid == prevEntry.tid && entry.pid == prevEntry.pid &&
        deltaMillis(prevEntry.ts, entry.ts)?.let { abs(it) <= THREAD_HANDOFF_MAX_GAP_MS } == true

// A token is weaker than an OS-thread handoff and is intentionally limited to the immediately
// preceding represented row. Parsed timestamps are mandatory: a brief/RAW row must never make
// a repeated identifier look like a causal edge.
private fun hasSharedCorrelationToken(idx: Int, entry: LogEntry, prevIdx: Int?, prevEntry: LogEntry?): Boolean =
    prevIdx != null && prevEntry != null && prevIdx != idx &&
        deltaMillis(prevEntry.ts, entry.ts)?.let { abs(it) <= THREAD_HANDOFF_MAX_GAP_MS } == true &&
        sharedCorrelationToken(prevEntry.msg, entry.msg) != null

@Suppress("LongParameterList")
private fun resolveEvidenceEdge(
    idx: Int,
    entry: LogEntry,
    emittedAny: Boolean,
    entryPointIdx: Int?,
    prevIdx: Int?,
    prevEntry: LogEntry?,
    options: DiagramOptions,
): EvidenceEdge = when {
    // The entry-point actor is user-declared configuration, not inference — it is always genuine
    // evidence for exactly the one arrow that opens the diagram.
    !emittedAny && entryPointIdx != null -> EvidenceEdge(entryPointIdx, MessageKind.CALL, MessageEvidence.LOG)
    isThreadHandoff(idx, entry, options, prevIdx, prevEntry) ->
        EvidenceEdge(prevIdx!!, MessageKind.CALL, MessageEvidence.THREAD_HANDOFF)
    hasSharedCorrelationToken(idx, entry, prevIdx, prevEntry) ->
        EvidenceEdge(prevIdx!!, MessageKind.CALL, MessageEvidence.CORRELATION_TOKEN)
    // No correlation found — an honest event on its own lifeline, never a guessed CALL.
    else -> EvidenceEdge(idx, MessageKind.SELF, MessageEvidence.LOG)
}

@Suppress("LongParameterList")
private fun emitEvidenceMessage(
    idx: Int,
    entry: LogEntry,
    label: String,
    emittedAny: Boolean,
    entryPointIdx: Int?,
    prevIdx: Int?,
    prevEntry: LogEntry?,
    options: DiagramOptions,
    gen: RawGen,
): Int {
    val edge = resolveEvidenceEdge(idx, entry, emittedAny, entryPointIdx, prevIdx, prevEntry, options)
    gen.messages += DiagramMessage(edge.fromIdx, idx, label, entry.id, entry.ts, entry.level, edge.kind, evidence = edge.evidence)
    if (options.notesForErrors && errorLevel(entry.level)) gen.notes += PendingNote(gen.messages.lastIndex, idx, label)
    return idx
}

@Suppress("LongParameterList")
private fun runEvidenceFlow(
    entries: List<LogEntry>,
    registry: ParticipantRegistry,
    entryPointIdx: Int?,
    exitPointIdx: Int?,
    options: DiagramOptions,
    resolveLabel: (LogEntry) -> String?,
    firstTs: String?,
    cancellationCheck: CancellationCheck,
    gen: RawGen,
) {
    // With no chain to seed, there is nothing to bootstrap — every entry with a resolvable
    // lifeline now produces a message (a CALL when evidenced, a SELF event otherwise), unlike the
    // old TAG_TRANSITION bootstrap that silently swallowed the range's very first entry.
    var prevIdx: Int? = null
    var prevEntry: LogEntry? = null
    var emittedAny = false
    var sinceCancellationCheck = 0
    for (entry in entries) {
        if (++sinceCancellationCheck >= CANCELLATION_CHECK_INTERVAL) {
            sinceCancellationCheck = 0
            cancellationCheck()
        }
        val idx = registry.indexForTag(entry.tag) ?: continue
        val label = buildLabel(entry, resolveLabel, options, firstTs)
        emitEvidenceMessage(idx, entry, label, emittedAny, entryPointIdx, prevIdx, prevEntry, options, gen)
        emittedAny = true
        prevIdx = idx
        prevEntry = entry
    }
    val last = entries.lastOrNull()
    val cur = prevIdx
    if (exitPointIdx != null && cur != null && last != null) {
        gen.messages += DiagramMessage(cur, exitPointIdx, "", last.id, last.ts, last.level, MessageKind.RETURN)
    }
}

@Suppress("LongParameterList")
private fun runRules(
    entries: List<LogEntry>,
    registry: ParticipantRegistry,
    rules: List<DiagramMessageRule>,
    options: DiagramOptions,
    resolveLabel: (LogEntry) -> String?,
    firstTs: String?,
    regexContext: RegexEvaluationContext,
    cancellationCheck: CancellationCheck,
    gen: RawGen,
    warnings: MutableList<String>,
) {
    val enabledRules = rules.filter { it.enabled && it.pattern.isNotBlank() }
    var current: Int? = null
    var currentEntry: LogEntry? = null
    var sinceCancellationCheck = 0
    for (entry in entries) {
        if (++sinceCancellationCheck >= CANCELLATION_CHECK_INTERVAL) {
            sinceCancellationCheck = 0
            cancellationCheck()
        }
        val label = buildLabel(entry, resolveLabel, options, firstTs)
        val match = enabledRules.firstNotNullOfOrNull { rule ->
            firstRegexMatchResult(entry.msg, rule.pattern, regexContext = regexContext)?.let { rule to it }
        }
        val result = if (match == null) {
            runRulesFallthrough(entry, registry, current, currentEntry, label, options, gen)
        } else {
            runRulesMatched(entry, match.first, match.second, registry, label, options, gen, warnings)
        }
        if (result != null) {
            current = result.first
            currentEntry = result.second
        }
    }
}

// Shares emitEvidenceMessage with runEvidenceFlow (this file's header doc) so an unmatched line
// gets exactly the same CALL-vs-SELF decision an EVIDENCE_FLOW-mode entry would: never a guessed
// CALL from a bare tag change. No entry-point actor concept applies to RULES mode (that reads as
// EVIDENCE_FLOW-specific configuration), so entryPointIdx is always null here.
private fun runRulesFallthrough(
    entry: LogEntry,
    registry: ParticipantRegistry,
    current: Int?,
    currentEntry: LogEntry?,
    label: String,
    options: DiagramOptions,
    gen: RawGen,
): Pair<Int, LogEntry>? {
    val tagIdx = registry.indexForTag(entry.tag) ?: return null
    emitEvidenceMessage(
        tagIdx, entry, label, emittedAny = true, entryPointIdx = null,
        prevIdx = current, prevEntry = currentEntry, options = options, gen = gen,
    )
    return tagIdx to entry
}

@Suppress("LongParameterList")
private fun runRulesMatched(
    entry: LogEntry,
    rule: DiagramMessageRule,
    matchResult: MatchResult,
    registry: ParticipantRegistry,
    fallbackLabel: String,
    options: DiagramOptions,
    gen: RawGen,
    warnings: MutableList<String>,
): Pair<Int, LogEntry>? {
    val fromName = substituteTemplate(rule.fromTemplate, matchResult, entry)
    val toName = substituteTemplate(rule.toTemplate, matchResult, entry)
    val rawLabel = substituteTemplate(rule.labelTemplate, matchResult, entry).ifBlank { fallbackLabel }
    val label = truncateLabel(collapseWhitespace(rawLabel), options.labelMaxChars)
    val fromIdx = resolveRuleEndpoint(rule.fromEndpoint, fromName, matchResult, entry, registry, rule, warnings) ?: run {
        warnings += "Rule '${rule.id}' has no resolved source endpoint."
        return null
    }
    val toIdx = resolveRuleEndpoint(rule.toEndpoint, toName, matchResult, entry, registry, rule, warnings) ?: run {
        warnings += "Rule '${rule.id}' has no resolved destination endpoint."
        return null
    }
    val kind = if (fromIdx == toIdx) MessageKind.SELF else MessageKind.CALL
    gen.messages += DiagramMessage(
        fromIdx, toIdx, label, entry.id, entry.ts, entry.level, kind,
        evidence = MessageEvidence.RULE,
        originKeys = setOf(MessageOriginKey(entry.id, ruleId = rule.id)),
    )
    if (options.notesForErrors && errorLevel(entry.level)) gen.notes += PendingNote(gen.messages.lastIndex, toIdx, label)
    return toIdx to entry
}

private fun resolveRuleEndpoint(
    endpoint: DiagramRuleEndpoint?,
    legacyName: String,
    matchResult: MatchResult,
    entry: LogEntry,
    registry: ParticipantRegistry,
    rule: DiagramMessageRule,
    warnings: MutableList<String>,
): Int? = when (endpoint) {
    null -> {
        val (index, created) = registry.resolveOrCreateActor(legacyName)
        if (created) warnings += "Rule '${rule.id}' referenced unknown participant '$legacyName' — added it as an actor."
        index
    }
    is DiagramRuleEndpoint.ExistingParticipant -> registry.indexForId(endpoint.participantId).also {
        if (it == null) warnings += "Rule '${rule.id}' references missing participant '${endpoint.participantId}'."
    }
    DiagramRuleEndpoint.CurrentEntry -> registry.indexForTag(entry.tag).also {
        if (it == null) warnings += "Rule '${rule.id}' cannot bind current entry tag '${entry.tag}'."
    }
    is DiagramRuleEndpoint.CapturedValue -> {
        val captured = matchResult.groups[endpoint.captureName]?.value.orEmpty()
        val participantId = endpoint.bindings.firstOrNull { it.capturedValue == captured }?.participantId
        participantId?.let(registry::indexForId).also {
            if (it == null) warnings += "Rule '${rule.id}' has no explicit binding for capture '${endpoint.captureName}' = '$captured'."
        }
    }
    is DiagramRuleEndpoint.ExplicitActor -> registry.resolveOrCreateExplicitActor(endpoint.id, endpoint.label)
}

private fun runLinePerMessage(
    entries: List<LogEntry>,
    registry: ParticipantRegistry,
    options: DiagramOptions,
    resolveLabel: (LogEntry) -> String?,
    firstTs: String?,
    cancellationCheck: CancellationCheck,
    gen: RawGen,
) {
    var sinceCancellationCheck = 0
    for (entry in entries) {
        if (++sinceCancellationCheck >= CANCELLATION_CHECK_INTERVAL) {
            sinceCancellationCheck = 0
            cancellationCheck()
        }
        val idx = registry.indexForTag(entry.tag) ?: continue
        val label = buildLabel(entry, resolveLabel, options, firstTs)
        gen.messages += DiagramMessage(idx, idx, label, entry.id, entry.ts, entry.level, MessageKind.SELF)
        if (options.notesForErrors && errorLevel(entry.level)) gen.notes += PendingNote(gen.messages.lastIndex, idx, label)
    }
}

private data class SourceInteractionResult(
    val messages: List<DiagramMessage> = emptyList(),
    val truncated: Boolean = false,
)

/** Converts the shared range-level trace into messages. Returns are emitted only when the trace
 * has an observed return/failure entry; a source declaration alone is never enough to create an
 * arrow back to the caller. */
private fun buildTraceMessages(
    trace: DiagramResolvedTrace,
    entries: List<LogEntry>,
    registry: ParticipantRegistry,
    options: DiagramOptions,
    resolveLabel: (LogEntry) -> String?,
    firstTs: String?,
    warnings: MutableList<String>,
): SourceInteractionResult {
    val entriesById = entries.associateBy { it.id }
    val eventsByEntry = trace.events.associateBy { it.entryId }
    val operationsByInvocation = trace.operations.filter { it.invocationId != null }.groupBy { it.invocationId }
    val logOperationByEntry = trace.operations
        .filter { it.kind == TraceOperationKind.LOG_EVENT }
        .associateBy { it.entryId }
    val callsByEntry = trace.calls.groupBy { it.callEntryId }
    val returnsByEntry = trace.calls.filter { it.returnEntryId != null }.groupBy { it.returnEntryId!! }
    val messages = ArrayList<DiagramMessage>(entries.size + trace.calls.size * 2)
    // Preserve the selected runtime order. Entry IDs are per-tab counters and are not a semantic
    // clock after merged logs or explicit selections.
    entries.forEach { entry ->
        callsByEntry[entry.id].orEmpty().sortedBy { it.invocationId }.forEach { call ->
            addTraceCallMessage(call, registry, options, entriesById, operationsByInvocation, warnings, messages)
        }
        returnsByEntry[entry.id].orEmpty().sortedBy { it.invocationId }.forEach { call ->
            addTraceReturnMessage(call, registry, options, entriesById, operationsByInvocation, messages)
        }
        addTraceLogMessage(entry, eventsByEntry[entry.id], registry, options, resolveLabel, firstTs, logOperationByEntry, warnings, messages)
    }
    return SourceInteractionResult(messages)
}

// The three helpers below carry buildTraceMessages' own per-call/per-return/per-log-event
// message construction — pulled out purely to keep the caller's own complexity down; each does
// exactly what its inline block used to.
@Suppress("LongParameterList")
private fun addTraceCallMessage(
    call: DiagramTraceCall,
    registry: ParticipantRegistry,
    options: DiagramOptions,
    entriesById: Map<Int, LogEntry>,
    operationsByInvocation: Map<String?, List<DiagramTraceOperation>>,
    warnings: MutableList<String>,
    messages: MutableList<DiagramMessage>,
) {
    val from = registry.indexForSourceOwner(call.callerOwnerType)
    val to = registry.indexForSourceOwner(call.calleeOwnerType)
    if (from == null) {
        warnings += "Source trace caller '${call.callerOwnerType}' is not bound to a participant."
        return
    }
    if (to == null) {
        warnings += "Source trace callee '${call.calleeOwnerType}' is not bound to a participant."
        return
    }
    val callEntry = entriesById[call.callEntryId]
    if (callEntry == null) {
        warnings += "Source trace call ${call.invocationId} points outside the selected range."
        return
    }
    val callLabel = truncateLabel(collapseWhitespace(call.callLabel), options.labelMaxChars)
    messages += DiagramMessage(
        fromIdx = from,
        toIdx = to,
        label = callLabel,
        entryId = callEntry.id,
        ts = callEntry.ts,
        level = callEntry.level,
        kind = when {
            from == to -> MessageKind.SELF
            call.invocationKind in ASYNC_INVOCATION_KINDS -> MessageKind.ASYNC
            else -> MessageKind.CALL
        },
        evidence = MessageEvidence.SOURCE_INFERRED,
        invocationId = call.invocationId,
        traceStatus = call.status,
        invocationKind = call.invocationKind,
        primary = false,
        sourceOperationId = operationsByInvocation[call.invocationId]
            ?.firstOrNull { it.kind == TraceOperationKind.SOURCE_CALL || it.kind == TraceOperationKind.ASYNC_HANDOFF }
            ?.id,
    )
}

private fun addTraceReturnMessage(
    call: DiagramTraceCall,
    registry: ParticipantRegistry,
    options: DiagramOptions,
    entriesById: Map<Int, LogEntry>,
    operationsByInvocation: Map<String?, List<DiagramTraceOperation>>,
    messages: MutableList<DiagramMessage>,
) {
    val from = registry.indexForSourceOwner(call.callerOwnerType)
    val to = registry.indexForSourceOwner(call.calleeOwnerType)
    val returnEntry = call.returnEntryId?.let(entriesById::get)
    if (from == null || to == null || returnEntry == null) return
    if (call.status !in setOf(TraceCallStatus.RETURNED, TraceCallStatus.THREW, TraceCallStatus.TERMINAL_FAILURE)) return
    val outcomeLabel = when (call.status) {
        TraceCallStatus.THREW -> call.returnLabel ?: "throws"
        TraceCallStatus.TERMINAL_FAILURE -> call.returnLabel ?: "failure"
        else -> call.returnLabel ?: "return"
    }
    messages += DiagramMessage(
        fromIdx = to,
        toIdx = from,
        label = truncateLabel(collapseWhitespace(outcomeLabel), options.labelMaxChars),
        entryId = returnEntry.id,
        ts = returnEntry.ts,
        level = returnEntry.level,
        kind = if (from == to) MessageKind.SELF else MessageKind.RETURN,
        evidence = MessageEvidence.SOURCE_INFERRED,
        invocationId = call.invocationId,
        traceStatus = call.status,
        invocationKind = call.invocationKind,
        primary = false,
        sourceOperationId = operationsByInvocation[call.invocationId]
            ?.firstOrNull { it.kind == TraceOperationKind.SOURCE_RETURN || it.kind == TraceOperationKind.THROW }
            ?.id,
    )
}

@Suppress("LongParameterList")
private fun addTraceLogMessage(
    entry: LogEntry,
    event: DiagramTraceEvent?,
    registry: ParticipantRegistry,
    options: DiagramOptions,
    resolveLabel: (LogEntry) -> String?,
    firstTs: String?,
    logOperationByEntry: Map<Int, DiagramTraceOperation>,
    warnings: MutableList<String>,
    messages: MutableList<DiagramMessage>,
) {
    val target = event?.ownerType?.let(registry::indexForSourceOwner) ?: registry.indexForTag(entry.tag)
    if (target == null) {
        warnings += "Source trace log ${entry.id} has no bound participant."
        return
    }
    messages += DiagramMessage(
        fromIdx = target,
        toIdx = target,
        label = buildLabel(entry, { resolveLabel(entry) ?: event?.methodName }, options, firstTs),
        entryId = entry.id,
        ts = entry.ts,
        level = entry.level,
        kind = MessageKind.SELF,
        evidence = MessageEvidence.LOG,
        primary = true,
        sourceLogSiteId = event?.sourceLogSiteId,
        sourceOperationId = logOperationByEntry[entry.id]?.id,
    )
}

/** Crash/watchdog markers prove that a source call did not complete in this selected window. */
private fun isTerminalFailure(entry: LogEntry): Boolean {
    val message = entry.msg
    return message.contains("FATAL EXCEPTION", ignoreCase = true) ||
        message.contains("ANR in", ignoreCase = true) ||
        message.contains("StackOverflowError", ignoreCase = true) ||
        message.contains("stack overflow", ignoreCase = true) ||
        message.contains("Fatal signal", ignoreCase = true)
}

private data class StackFrame(
    val ownerType: String,
    val methodName: String,
    val fileName: String,
    val line: Int,
)

private data class StackInteraction(
    val entryId: Int,
    val interaction: DiagramSourceInteraction,
)

// Android's Java/Kotlin renderer emits the innermost frame first. Restricting this parser to
// application-looking `com.*` owners and real .java/.kt locations keeps ordinary log prose,
// native frames, and framework frames out of source inference.
private val ANDROID_SOURCE_FRAME = Regex(
    """^\s*at\s+(com\.[\w$]+(?:\.[\w$]+)*)\.([\w$<>]+)\(([^():]+\.(?:java|kt)):(\d+)\)\s*$""",
)

private fun parseStackFrame(message: String): StackFrame? {
    val match = ANDROID_SOURCE_FRAME.matchEntire(message) ?: return null
    return StackFrame(
        ownerType = match.groupValues[1],
        methodName = match.groupValues[2],
        fileName = match.groupValues[3],
        line = match.groupValues[4].toIntOrNull() ?: return null,
    )
}

private fun componentForSourceOwner(ownerType: String, components: List<DiagramComponent>): String? {
    val exact = components.filter { component ->
        component.enabled && component.sourceOwnerTypes.any { it == ownerType }
    }.singleOrNull()
    if (exact != null) return exact.id

    // Keep old component specs useful when callers used the fully-qualified type as an id/name.
    val simple = ownerType.substringAfterLast('.')
    return components.filter { component ->
        component.enabled && listOf(component.id, component.displayName).any { value ->
            value == ownerType || value == simple
        }
    }.singleOrNull()?.id
}

// Extracted from inferStackInteractions' own flush() loop body — pulled out purely to replace two
// `continue` statements with early returns, which detekt counts against the loop itself; behavior
// (skip an unresolvable component, skip a same-component non-recursive pair) is unchanged.
private fun stackInteractionForPair(
    callerEntry: LogEntry,
    caller: StackFrame,
    callee: StackFrame,
    components: List<DiagramComponent>,
): StackInteraction? {
    val from = componentForSourceOwner(caller.ownerType, components) ?: return null
    val to = componentForSourceOwner(callee.ownerType, components) ?: return null
    val recursive = caller.ownerType == callee.ownerType && caller.methodName == callee.methodName
    if (from == to && !recursive) return null
    return StackInteraction(
        entryId = callerEntry.id,
        interaction = DiagramSourceInteraction(
            fromComponentId = from,
            toComponentId = to,
            label = "${callee.ownerType}.${callee.methodName}(${callee.fileName}:${callee.line})",
            allowSelfCall = recursive,
        ),
    )
}

private fun inferStackInteractions(
    entries: List<LogEntry>,
    components: List<DiagramComponent>,
): List<StackInteraction> {
    if (entries.size < 2 || components.none { it.enabled }) return emptyList()
    val result = mutableListOf<StackInteraction>()
    val run = mutableListOf<Pair<LogEntry, StackFrame>>()

    fun flush() {
        for (index in 0 until run.lastIndex) {
            val (calleeEntry, callee) = run[index]
            val (callerEntry, caller) = run[index + 1]
            stackInteractionForPair(callerEntry, caller, callee, components)?.let { result += it }
        }
        run.clear()
    }

    entries.forEach { entry ->
        val frame = parseStackFrame(entry.msg)
        if (frame == null) flush() else run += entry to frame
    }
    flush()
    return result
}

// Budget and provenance validation are intentionally co-located so every callback is bounded
// before it can append a message; extracting the loop would obscure that security invariant.
@Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
private fun buildSourceInteractions(
    entries: List<LogEntry>,
    selectedEntries: List<LogEntry>,
    stackInteractions: List<StackInteraction>,
    registry: ParticipantRegistry,
    options: DiagramOptions,
    enrichment: DiagramSourceEnrichment,
    resolveSourceInteractions: (LogEntry) -> List<DiagramSourceInteraction>,
    cancellationCheck: CancellationCheck,
    warnings: MutableList<String>,
    messageBudget: Int,
): SourceInteractionResult {
    if (messageBudget <= 0) {
        if (entries.isNotEmpty()) warnings += "Source enrichment skipped: runtime interactions reached the diagram message cap."
        return SourceInteractionResult(truncated = entries.isNotEmpty())
    }
    val messages = ArrayList<DiagramMessage>(messageBudget)
    var truncated = entries.size > messageBudget
    var attempts = 0
    val stackByEntry = stackInteractions.groupBy { it.entryId }
    val representedIds = entries.mapTo(HashSet()) { it.id }
    val stackIds = stackInteractions.mapTo(HashSet()) { it.entryId }
    val sourceEntries = selectedEntries.filter { it.id in representedIds || it.id in stackIds }
    val selectedIndexById = selectedEntries.withIndex().associate { it.value.id to it.index }
    val terminalFailureAfter = BooleanArray(selectedEntries.size)
    var terminalFailureSeen = false
    for (index in selectedEntries.indices.reversed()) {
        terminalFailureAfter[index] = terminalFailureSeen
        terminalFailureSeen = terminalFailureSeen || isTerminalFailure(selectedEntries[index])
    }
    for ((entryIndex, entry) in sourceEntries.withIndex()) {
        if (attempts >= messageBudget || messages.size >= messageBudget) {
            truncated = true
            break
        }
        attempts++
        if (entryIndex % CANCELLATION_CHECK_INTERVAL == 0) cancellationCheck()
        val interactions = buildList {
            addAll(stackByEntry[entry.id].orEmpty().map { it.interaction })
            if (entry.id in representedIds) addAll(resolveSourceInteractions(entry))
        }.distinct()
        val selectedIndex = selectedIndexById[entry.id] ?: -1
        if (interactions.size > MAX_SOURCE_INTERACTIONS_PER_ENTRY) truncated = true
        for (interaction in interactions.take(MAX_SOURCE_INTERACTIONS_PER_ENTRY)) {
            val from = registry.indexForId(interaction.fromComponentId)
            val to = registry.indexForId(interaction.toComponentId)
            if (from == null || to == null) {
                warnings += "Source interaction '${interaction.fromComponentId}' → '${interaction.toComponentId}' does not match an enabled component."
                continue
            }
            if (from == to && !interaction.allowSelfCall) continue
            // A declared return type is source metadata, not proof that the call returned. Keep
            // return arrows for completed windows, but never draw a synthetic return across a
            // later fatal exception, ANR, or stack overflow in the selected range.
            val needsReturn = enrichment.addReturnArrows &&
                !interaction.returnLabel.isNullOrBlank() &&
                (selectedIndex < 0 || !terminalFailureAfter[selectedIndex])
            val messageCost = if (needsReturn) 2 else 1
            if (messages.size + messageCost > messageBudget) {
                truncated = true
                break
            }
            messages += DiagramMessage(
                from, to, truncateLabel(collapseWhitespace(interaction.label), options.labelMaxChars),
                entry.id, entry.ts, entry.level,
                if (from == to) MessageKind.SELF else MessageKind.CALL,
                evidence = MessageEvidence.SOURCE_INFERRED, primary = false,
            )
            if (needsReturn) {
                messages += DiagramMessage(
                    to, from,
                    truncateLabel(collapseWhitespace(interaction.returnLabel.orEmpty()), options.labelMaxChars),
                    entry.id, entry.ts, entry.level, MessageKind.RETURN, evidence = MessageEvidence.SOURCE_INFERRED, primary = false,
                )
            }
        }
    }
    if (truncated) warnings += "Source enrichment was capped to preserve the diagram message budget."
    return SourceInteractionResult(messages, truncated)
}

/** Relays a mirrored component edge through the actor. */
private fun applyActorMirrors(
    messages: List<DiagramMessage>,
    registry: ParticipantRegistry,
    actors: List<DiagramActor>,
): List<DiagramMessage> {
    val mirrors = actors.flatMap { actor ->
        val componentIds = (actor.mirrorComponentIds + listOfNotNull(actor.mirrorComponentId)).distinct()
        val actorIdx = registry.indexForId(actor.id) ?: return@flatMap emptyList()
        componentIds.mapNotNull { componentId ->
            val component = registry.indexForId(componentId) ?: return@mapNotNull null
            Triple(component, actorIdx, actor.mirrorDirection)
        }
    }
    if (mirrors.isEmpty()) return messages
    return buildList(messages.size + mirrors.size) {
        messages.forEach { message ->
            if (message.fromIdx == message.toIdx) {
                add(message)
                return@forEach
            }
            mirrors.filter { (componentIdx, actorIdx, direction) ->
                message.fromIdx == componentIdx && direction != MirrorDirection.INBOUND && actorIdx != message.toIdx
            }.forEach { (componentIdx, actorIdx, _) ->
                add(message.copy(fromIdx = actorIdx, toIdx = componentIdx, evidence = MessageEvidence.ACTOR_MIRROR, primary = false))
            }
            add(message)
            mirrors.filter { (componentIdx, actorIdx, direction) ->
                message.toIdx == componentIdx && direction != MirrorDirection.OUTBOUND && actorIdx != message.fromIdx
            }.forEach { (componentIdx, actorIdx, _) ->
                add(message.copy(fromIdx = componentIdx, toIdx = actorIdx, evidence = MessageEvidence.ACTOR_MIRROR, primary = false))
            }
        }
    }
}

// ── Message filters (showSelfMessages / showSourceInferred) ──────────────────────────────────

/** [rawToFiltered] is sized to the RAW (pre-filter) message list: `rawToFiltered[i]` is the index
 *  message `i` landed at in [messages] when it survived, or the index of the nearest SURVIVING
 *  message that comes AFTER it when it was dropped — never before, so a note/frame boundary
 *  anchored on a dropped message still lands at-or-after where that message used to be, matching
 *  entryId order. `filtered.size` (i.e. [messages].size) when nothing survives after it; callers
 *  compose this with collapseRepeats' own mapping and rely on that same out-of-range convention
 *  (buildNotes/addFrame already drop an index `>= cappedSize`). */
private class FilterResult(val messages: List<DiagramMessage>, val rawToFiltered: IntArray)

private fun filterMessages(raw: List<DiagramMessage>, options: DiagramOptions): FilterResult {
    if (options.showSelfMessages && options.showSourceInferred) {
        return FilterResult(raw, IntArray(raw.size) { it })
    }
    val kept = BooleanArray(raw.size)
    val filtered = ArrayList<DiagramMessage>(raw.size)
    val filteredIndexIfKept = IntArray(raw.size) { -1 }
    raw.forEachIndexed { i, m ->
        // Visibility switches may suppress supplemental structure, but selected log evidence is
        // never a presentation casualty. A primary SELF remains the one representation of its row.
        val dropSelf = !options.showSelfMessages && !m.primary && m.kind == MessageKind.SELF
        val dropSourceInferred = !options.showSourceInferred && !m.primary && m.evidence == MessageEvidence.SOURCE_INFERRED
        if (!dropSelf && !dropSourceInferred) {
            kept[i] = true
            filteredIndexIfKept[i] = filtered.size
            filtered += m
        }
    }
    val rawToFiltered = IntArray(raw.size)
    var nextSurviving = filtered.size
    for (i in raw.indices.reversed()) {
        if (kept[i]) nextSurviving = filteredIndexIfKept[i]
        rawToFiltered[i] = nextSurviving
    }
    return FilterResult(filtered, rawToFiltered)
}

/** Projects invocation push/pop boundaries through presentation mappings without inferring them
 * from adjacent rendered messages. The invocation tree remains authoritative even when filters,
 * actor relays, repeat collapsing, or the message cap alter the final list. */
private fun buildTraceActivationSpansFromTrace(
    rawMessages: List<DiagramMessage>,
    invocations: List<DiagramTraceCall>,
    rawToFinal: IntArray,
    finalSize: Int,
): List<DiagramActivationSpan> {
    if (rawMessages.isEmpty() || finalSize == 0) return emptyList()
    val byInvocation = rawMessages.withIndex().filter { it.value.invocationId != null }
        .groupBy { it.value.invocationId!! }
    return invocations.mapNotNull { invocation ->
        if (invocation.invocationKind in setOf(
                TraceInvocationKind.COROUTINE_LAUNCH,
                TraceInvocationKind.CALLBACK_REGISTRATION,
                TraceInvocationKind.EXECUTOR_DISPATCH,
                TraceInvocationKind.BINDER_OR_RPC,
                TraceInvocationKind.UNKNOWN_ASYNC,
            )) return@mapNotNull null
        val rendered = byInvocation[invocation.invocationId].orEmpty()
        val call = rendered.firstOrNull { it.value.kind == MessageKind.CALL || it.value.kind == MessageKind.SELF }
            ?: return@mapNotNull null
        val returnMessage = rendered.lastOrNull { it.value.kind == MessageKind.RETURN }
        val start = rawToFinal.getOrNull(call.index) ?: return@mapNotNull null
        val end = when {
            returnMessage != null -> rawToFinal.getOrNull(returnMessage.index) ?: start
            invocation.status == TraceCallStatus.INCOMPLETE_WINDOW -> finalSize - 1
            else -> rendered.maxOfOrNull { rawToFinal.getOrNull(it.index) ?: start } ?: start
        }.coerceAtMost(finalSize - 1)
        if (end <= start) return@mapNotNull null
        val participantIdx = if (call.value.fromIdx == call.value.toIdx) call.value.fromIdx else call.value.toIdx
        DiagramActivationSpan(
            participantIdx = participantIdx,
            startMessage = start,
            endMessage = end,
            evidence = call.value.evidence,
            invocationId = invocation.invocationId,
            status = invocation.status,
            invocationKind = invocation.invocationKind,
        )
    }.distinctBy { Triple(it.participantIdx, it.startMessage, it.endMessage) }
}

private data class OpenActivationCall(val from: Int, val to: Int, val index: Int, val evidence: MessageEvidence)

private fun buildLegacyActivationSpans(messages: List<DiagramMessage>): List<DiagramActivationSpan> {
    val open = ArrayDeque<OpenActivationCall>()
    val spans = mutableListOf<DiagramActivationSpan>()
    messages.forEachIndexed { index, message -> processLegacyActivationMessage(open, spans, index, message) }
    closeUnmatchedSourceInferredActivations(open, spans, messages)
    return spans.distinctBy { Triple(it.participantIdx, it.startMessage, it.endMessage) }
}

// The two helpers below carry buildLegacyActivationSpans' own per-message matching and its
// trailing unclosed-call handling — pulled out purely to keep the caller's own complexity down;
// each does exactly what its inline block used to.
private fun processLegacyActivationMessage(
    open: ArrayDeque<OpenActivationCall>,
    spans: MutableList<DiagramActivationSpan>,
    index: Int,
    message: DiagramMessage,
) {
    when {
        message.invocationId == null && message.kind == MessageKind.CALL &&
            message.fromIdx != message.toIdx && message.evidence != MessageEvidence.ACTOR_MIRROR ->
            open.addLast(OpenActivationCall(message.fromIdx, message.toIdx, index, message.evidence))
        message.invocationId == null && message.kind == MessageKind.RETURN && message.evidence != MessageEvidence.ACTOR_MIRROR -> {
            val match = open.lastOrNull {
                it.from == message.toIdx && it.to == message.fromIdx &&
                    (it.evidence == message.evidence || it.evidence == MessageEvidence.MANUAL_OVERRIDE ||
                        message.evidence == MessageEvidence.MANUAL_OVERRIDE)
            }
            if (match != null) {
                while (open.isNotEmpty() && open.last() != match) open.removeLast()
                open.removeLast()
                spans += DiagramActivationSpan(match.to, match.index, index, match.evidence)
            }
        }
    }
}

// A source-inferred call without a return is still an evidence-backed activation, but do not
// stretch it over the entire window when the caller visibly makes progress. A subsequent
// caller self-event or another call is the nearest observable indication that the missing
// return happened. If no such boundary exists, the bounded log window remains the conservative
// end of the activation (important for ANR and recursive-stack ranges).
private fun closeUnmatchedSourceInferredActivations(
    open: ArrayDeque<OpenActivationCall>,
    spans: MutableList<DiagramActivationSpan>,
    messages: List<DiagramMessage>,
) {
    if (messages.isEmpty()) return
    open.filter { it.evidence == MessageEvidence.SOURCE_INFERRED }.forEach { call ->
        val callerProgress = (call.index + 1 until messages.size).firstOrNull { index ->
            val message = messages[index]
            message.evidence != MessageEvidence.ACTOR_MIRROR &&
                (message.fromIdx == call.from && message.toIdx == call.from ||
                    message.kind == MessageKind.CALL && message.fromIdx == call.from)
        }
        val endMessage = callerProgress ?: messages.lastIndex
        if (endMessage > call.index) {
            spans += DiagramActivationSpan(call.to, call.index, endMessage, call.evidence)
        }
    }
}

// ── Label formatting ──────────────────────────────────────────────────────────────────────────

private val WHITESPACE_RUN = Regex("[ \\t]{2,}")

// \r must be flattened here too, not just \n/\t — a label that still carries one reaches the
// emitter "single-line" in name only: mermaidEscape's \r\n/\r → \n → <br/> normalization would
// then turn it into a visible line break in Mermaid but not in PlantUML (which only escapes \n),
// so the same label would silently render differently per dialect despite having supposedly
// already been flattened. \r → ' ' first, same as \n/\t, so a "\r\n" pair collapses to one space
// via WHITESPACE_RUN below rather than leaving two.
private fun collapseWhitespace(text: String): String =
    text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').replace(WHITESPACE_RUN, " ").trim()

private fun truncateLabel(s: String, max: Int): String {
    if (max <= 0) return ""
    return if (s.length <= max) s else s.take(maxOf(0, max - 1)) + "…"
}

// Truncation is applied to the MESSAGE portion only, before any ts/elapsed prefix is added — a
// long message gets cut, but the prefix (when enabled) is never itself at risk of being chopped,
// so labelMaxChars stays predictable regardless of which display options are on.
private fun buildLabel(entry: LogEntry, resolveLabel: (LogEntry) -> String?, options: DiagramOptions, firstTs: String?): String {
    val base = when (options.labelSource) {
        LabelSource.MESSAGE -> entry.msg
        LabelSource.SOURCE_METHOD -> resolveLabel(entry) ?: entry.msg
        LabelSource.BOTH -> {
            val method = resolveLabel(entry)
            if (method.isNullOrBlank()) entry.msg else "$method — ${entry.msg}"
        }
    }
    val truncated = truncateLabel(collapseWhitespace(base), options.labelMaxChars)
    return buildString {
        if (options.showTimestamps) {
            append(entry.ts)
            append("  ")
        }
        if (options.showElapsed && firstTs != null) {
            deltaMillis(firstTs, entry.ts)?.let {
                append(formatDelta(it))
                append("  ")
            }
        }
        append(truncated)
    }
}

// ── RULES template substitution ───────────────────────────────────────────────────────────────

private val TEMPLATE_TOKEN = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}")

// ${msg} always expands to the whole entry's message; ${anyOtherName} expands to that named
// capture group's text, or "" if the group is absent from the pattern or didn't participate in
// this particular match — a malformed rule (typo'd group name, group defined only in an
// alternate branch that didn't fire) degrades to a blank substitution rather than throwing, per
// this package's "never throw on malformed input" rule. MatchResult.groups[name] itself throws
// for a name the underlying Pattern never declared at all, hence the runCatching.
private fun substituteTemplate(template: String, matchResult: MatchResult, entry: LogEntry): String =
    TEMPLATE_TOKEN.replace(template) { m ->
        val name = m.groupValues[1]
        if (name == "msg") entry.msg else runCatching { matchResult.groups[name]?.value }.getOrNull().orEmpty()
    }

// ── Repeat collapsing ─────────────────────────────────────────────────────────────────────────

private val HEX_RUN = Regex("0[xX][0-9a-fA-F]+")
private val DIGIT_RUN = Regex("\\d+")

// "retry 1"/"retry 2"/"retry 10" all normalize to "retry #" so a repeated operation with an
// incrementing counter (or a changing pointer/hex address) still collapses — the ORIGINAL label
// of the first occurrence is what's kept in the emitted message, this is comparison-only.
private fun normalizeForRepeatCollapse(label: String): String = label.replace(HEX_RUN, "#").replace(DIGIT_RUN, "#")

private class CollapseResult(val messages: List<DiagramMessage>, val rawToCollapsedIndex: IntArray)

private fun identityCollapse(raw: List<DiagramMessage>): CollapseResult =
    CollapseResult(raw, IntArray(raw.size) { it })

// Folds a run of CONSECUTIVE messages sharing (fromIdx, toIdx, normalized label) into one with
// repeatCount = run length, keeping the first occurrence's label/entryId/ts — never merges
// non-adjacent repeats (a diagram where the same call recurs after something else happened in
// between is meaningfully different from a tight retry loop, and folding across the gap would
// hide that). rawToCollapsedIndex maps each ORIGINAL message index to the index it landed at in
// the folded list, so callers (buildNotes, buildFrames) that recorded a raw index while messages
// were still being generated can find where that evidence ended up afterward.
private fun collapseRepeats(raw: List<DiagramMessage>): CollapseResult {
    if (raw.isEmpty()) return CollapseResult(emptyList(), IntArray(0))
    val out = ArrayList<DiagramMessage>()
    val mapping = IntArray(raw.size)
    var i = 0
    while (i < raw.size) {
        val first = raw[i]
        val normFirst = normalizeForRepeatCollapse(first.label)
        mapping[i] = out.size
        var j = i + 1
        var count = 1
        while (j < raw.size) {
            val cand = raw[j]
            val sameInteraction = cand.fromIdx == first.fromIdx &&
                cand.toIdx == first.toIdx &&
                cand.kind == first.kind &&
                cand.evidence == first.evidence &&
                normalizeForRepeatCollapse(cand.label) == normFirst
            if (sameInteraction) {
                mapping[j] = out.size
                count++
                j++
            } else {
                break
            }
        }
        out += first.copy(
            repeatCount = count,
            representedEntryIds = raw.subList(i, j).flatMapTo(linkedSetOf()) { it.representedEntryIds },
            originKeys = raw.subList(i, j).flatMapTo(linkedSetOf()) { it.originKeys },
        )
        i = j
    }
    return CollapseResult(out, mapping)
}

private fun buildNotes(pending: List<PendingNote>, mapping: IntArray, cappedSize: Int): List<DiagramNoteMark> =
    pending.mapNotNull { pn ->
        val collapsedIdx = mapping.getOrNull(pn.rawIndex) ?: return@mapNotNull null
        if (collapsedIdx >= cappedSize) return@mapNotNull null
        DiagramNoteMark(pn.participantIdx, collapsedIdx, pn.text, isError = true)
    }

// ── Sequence-group frames ─────────────────────────────────────────────────────────────────────

@Suppress("LongParameterList")
private fun buildFrames(
    tab: LogTab,
    allVisible: List<LogEntry>,
    rangeStartIdx: Int,
    rangeEndIdxExclusive: Int,
    rawMessages: List<DiagramMessage>,
    mapping: IntArray,
    cappedSize: Int,
    cancellationCheck: CancellationCheck,
): List<DiagramFrame> {
    val sequences = tab.filter.sequences
    if (sequences.none { it.enabled }) return emptyList()
    val seqGroups = cachedSeqGroupsFor(tab, true) ?: computeSeqGroups(allVisible, sequences, cancellationCheck)
    if (seqGroups.isEmpty()) return emptyList()
    val defMap = sequences.associateBy { it.id }
    val ids = IntArray(allVisible.size) { allVisible[it].id }

    fun idxOf(id: Int): Int? = Arrays.binarySearch(ids, id).takeIf { it >= 0 }

    fun idAt(idx: Int): Int = allVisible[idx].id

    val frames = mutableListOf<DiagramFrame>()
    for (sg in seqGroups) {
        val rootIdx = idxOf(sg.rid) ?: continue
        if (sg.endExclusive <= rangeStartIdx || rootIdx >= rangeEndIdxExclusive) continue
        val endEx = minOf(sg.endExclusive, allVisible.size)
        addFrame(frames, rawMessages, mapping, cappedSize, defMap[sg.defId], idAt(rootIdx), idAt(endEx - 1), depth = 0)
        for (ng in sg.nested) {
            val nRootIdx = idxOf(ng.rid) ?: continue
            if (ng.endExclusive <= rangeStartIdx || nRootIdx >= rangeEndIdxExclusive) continue
            val nEndEx = minOf(ng.endExclusive, allVisible.size)
            addFrame(frames, rawMessages, mapping, cappedSize, defMap[ng.defId], idAt(nRootIdx), idAt(nEndEx - 1), depth = 1)
        }
    }
    return frames
}

@Suppress("LongParameterList")
private fun addFrame(
    out: MutableList<DiagramFrame>,
    rawMessages: List<DiagramMessage>,
    mapping: IntArray,
    cappedSize: Int,
    def: SequenceDef?,
    startId: Int,
    endIdInclusive: Int,
    depth: Int,
) {
    // rawMessages' entryId is ascending (built by walking entries in ascending-id order), aside
    // from the one synthetic exit-point RETURN which repeats the last real entry's id — harmless
    // here since it can only ever equal, never precede, that last id.
    val firstRaw = firstAtOrAfter(rawMessages, startId)
    if (firstRaw >= rawMessages.size || rawMessages[firstRaw].entryId > endIdInclusive) return
    val lastRaw = lastAtOrBefore(rawMessages, endIdInclusive)
    if (lastRaw < 0 || rawMessages[lastRaw].entryId < startId) return
    val firstCollapsed = mapping.getOrNull(firstRaw) ?: return
    if (firstCollapsed >= cappedSize) return
    val lastCollapsed = minOf(mapping.getOrNull(lastRaw) ?: firstCollapsed, cappedSize - 1)
    val label = def?.matchText?.ifBlank { null } ?: def?.id ?: "sequence"
    out += DiagramFrame(label, def?.colorArgb(), firstCollapsed, maxOf(firstCollapsed, lastCollapsed), depth)
}

private fun firstAtOrAfter(messages: List<DiagramMessage>, id: Int): Int {
    var lo = 0
    var hi = messages.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (messages[mid].entryId < id) lo = mid + 1 else hi = mid
    }
    return lo
}

private fun lastAtOrBefore(messages: List<DiagramMessage>, id: Int): Int {
    var lo = 0
    var hi = messages.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (messages[mid].entryId <= id) lo = mid + 1 else hi = mid
    }
    return lo - 1
}

private const val COLOR_CHANNEL_MAX = 255
private const val COLOR_CHANNEL_MAX_F = 255f

// Hand-packs SequenceDef.color (a Compose Color, defined in `model`) into ARGB without this file
// ever spelling out "androidx.compose.ui.graphics.Color" as a type — `color` below is used purely
// via inferred-type member access (.alpha/.red/.green/.blue are plain Float properties on the
// class), so no import of that package is needed here. See DiagramFrame's own doc for why the
// model insists on a plain Int rather than carrying the Color type itself.
//
// roundToInt(), not toInt(): a channel like 0x11 (17) round-trips through Color's Float storage
// as something like 0.0667f, and 0.0667f * 255f can land a hair under 17.0f (16.999998f) — a
// truncating toInt() would then silently produce 16, one off from every ARGB literal a caller
// (or a test) constructed the SequenceDef with. IndagiumToolOperations.kt's own colorToHex hits
// this exact channel-precision issue and uses roundToInt() for the same reason.
private fun SequenceDef.colorArgb(): Int {
    val c = color
    val a = (c.alpha * COLOR_CHANNEL_MAX_F).roundToInt().coerceIn(0, COLOR_CHANNEL_MAX)
    val r = (c.red * COLOR_CHANNEL_MAX_F).roundToInt().coerceIn(0, COLOR_CHANNEL_MAX)
    val g = (c.green * COLOR_CHANNEL_MAX_F).roundToInt().coerceIn(0, COLOR_CHANNEL_MAX)
    val b = (c.blue * COLOR_CHANNEL_MAX_F).roundToInt().coerceIn(0, COLOR_CHANNEL_MAX)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
