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
import kotlin.math.roundToInt

// A tag participant list longer than this stops being a readable diagram regardless of how it
// was produced — caps the auto-derive path (resolveTagParticipants) the same way
// DiagramOptions.maxMessages caps the arrow count. Deliberately not configurable: a caller who
// wants more control supplies spec.participants explicitly instead (which is never capped here).
private const val DEFAULT_MAX_AUTO_PARTICIPANTS = 8

/**
 * Builds a [SeqDiagram] from a range of [tab]'s entries. Always scans over
 * `utils.visibleEntries(tab, applyFilter = true)` — the exact set the filter panel currently
 * shows — so a generated diagram never includes a line the user has filtered away, and a
 * `DiagramRange.SeqGroupRef`'s indices (computed against that same call, see Filter.kt's
 * computeItems) line up without re-deriving them.
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
 */
fun buildSequenceDiagram(
    tab: LogTab,
    spec: SeqDiagramSpec,
    resolveLabel: (LogEntry) -> String? = { null },
    cancellationCheck: CancellationCheck = CancellationCheck {},
    resolveSourceInteractions: (LogEntry) -> List<DiagramSourceInteraction> = { emptyList() },
): SeqDiagram {
    val warnings = mutableListOf<String>()
    val allVisible = visibleEntries(tab, applyFilter = true)
    val regexContext = RegexEvaluationContext()

    val resolved = resolveRange(tab, spec.range, allVisible, cancellationCheck, warnings)
    val candidateEntries = resolved.entries

    val participantResolution = if (spec.components.isNotEmpty()) {
        resolveComponentParticipants(spec, candidateEntries)
    } else {
        resolveTagParticipants(spec.participants, candidateEntries)
    }
    val registry = ParticipantRegistry(
        participantResolution.participants,
        participantResolution.groupedTags,
        participantResolution.tagToParticipantId,
    )
    val entryPointIdx = spec.participants
        .firstOrNull { it.kind == ParticipantKind.ACTOR && it.isEntryPoint }
        ?.let { registry.indexForId(it.id) }
    val exitPointIdx = spec.participants
        .firstOrNull { it.kind == ParticipantKind.ACTOR && it.isExitPoint }
        ?.let { registry.indexForId(it.id) }

    val gen = RawGen()
    // Elapsed-time labels anchor to the RANGE's own first entry, not filteredEntries' first — a
    // tag excluded by the diagram-specific participant filter should not shift what "+0.000"
    // means for everything that survived the filter.
    val firstTs = candidateEntries.firstOrNull()?.ts

    when (spec.mode) {
        ArrowMode.TAG_TRANSITION -> runTagTransition(
            participantResolution.representedEntries, registry, entryPointIdx, exitPointIdx, spec.options, resolveLabel, firstTs, cancellationCheck, gen,
        )

        ArrowMode.RULES -> runRules(
            participantResolution.representedEntries, registry, spec.rules, spec.options, resolveLabel, firstTs, regexContext, cancellationCheck, gen, warnings,
        )

        ArrowMode.LINE_PER_MESSAGE -> runLinePerMessage(
            participantResolution.representedEntries, registry, spec.options, resolveLabel, firstTs, cancellationCheck, gen,
        )
    }

    val sourceResult = if (spec.sourceEnrichment.enabled) {
        // Runtime interactions retain priority: enrichment may supplement only the remaining
        // ordered output capacity, never force an unbounded source scan or displace them.
        val remainingSourceBudget = (spec.options.maxMessages - gen.messages.size).coerceAtLeast(0)
        buildSourceInteractions(
            participantResolution.representedEntries, registry, spec.options, spec.sourceEnrichment,
            resolveSourceInteractions, cancellationCheck, warnings, remainingSourceBudget,
        )
    } else {
        SourceInteractionResult()
    }
    // Frames rely on entry-id order; sorting is stable, so source edges for one entry stay after
    // that entry's runtime edge while no longer being appended after the whole range.
    val rawMessages = applyActorMirrors(
        (gen.messages + sourceResult.messages).sortedBy { it.entryId }, registry, spec.actors,
    )
    val collapsed = if (spec.options.collapseRepeats) collapseRepeats(rawMessages) else identityCollapse(rawMessages)
    val truncated = collapsed.messages.size > spec.options.maxMessages || sourceResult.truncated
    val cappedMessages = if (truncated) collapsed.messages.subList(0, spec.options.maxMessages).toList() else collapsed.messages
    val notes = buildNotes(gen.notes, collapsed.rawToCollapsedIndex, cappedMessages.size)

    val frames = if (spec.options.seqGroupFrames) {
        buildFrames(
            tab, allVisible, resolved.startIdx, resolved.endIdxExclusive,
            rawMessages, collapsed.rawToCollapsedIndex, cappedMessages.size, cancellationCheck,
        )
    } else {
        emptyList()
    }
    val activations = if (spec.options.activationPolicy == ActivationPolicy.EVIDENCE_BACKED) {
        buildActivationSpans(cappedMessages)
    } else {
        emptyList()
    }

    return SeqDiagram(
        spec = spec,
        participants = registry.list.toList(),
        messages = cappedMessages,
        frames = frames,
        notes = notes,
        activationSpans = activations,
        truncated = truncated,
        scannedEntries = candidateEntries.size,
        coverage = participantResolution.coverage,
        warnings = warnings,
    )
}

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

private fun resolveIdsRange(allVisible: List<LogEntry>, range: DiagramRange.Ids): RangeResolution {
    if (allVisible.isEmpty()) return RangeResolution(emptyList(), 0, 0)
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

/** Returns range-correct candidates for the participant inspector.  It resolves [spec.range]
 * through the active filter first, exactly as [buildSequenceDiagram] does. */
fun diagramParticipantCandidates(
    tab: LogTab,
    spec: SeqDiagramSpec,
    cancellationCheck: CancellationCheck = CancellationCheck {},
): List<DiagramParticipantCandidate> {
    val warnings = mutableListOf<String>()
    val resolved = resolveRange(tab, spec.range, visibleEntries(tab, applyFilter = true), cancellationCheck, warnings)
    if (spec.components.isNotEmpty()) return componentCandidates(spec, resolved.entries, cancellationCheck)
    val configured = spec.participants.filter { it.kind == ParticipantKind.TAG && it.tag != null }.associateBy { it.tag!! }
    var previousTag: String? = null
    val counts = LinkedHashMap<String, Int>()
    val transitions = HashMap<String, Int>()
    val errors = HashMap<String, Int>()
    for (entry in resolved.entries) {
        counts[entry.tag] = (counts[entry.tag] ?: 0) + 1
        if (errorLevel(entry.level)) errors[entry.tag] = (errors[entry.tag] ?: 0) + 1
        previousTag?.takeIf { it != entry.tag }?.let { prior ->
            transitions[prior] = (transitions[prior] ?: 0) + 1
            transitions[entry.tag] = (transitions[entry.tag] ?: 0) + 1
        }
        previousTag = entry.tag
    }
    val automaticallyShown = if (configured.isEmpty()) {
        counts.entries.sortedByDescending { it.value }.take(DEFAULT_MAX_AUTO_PARTICIPANTS).map { it.key }.toSet()
    } else {
        emptySet()
    }
    return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { (tag, count) ->
        val participant = configured[tag]
        DiagramParticipantCandidate(
            tag = tag,
            entryCount = count,
            transitionCount = transitions[tag] ?: 0,
            errorCount = errors[tag] ?: 0,
            representation = participant?.representation
                ?: when {
                    configured.isNotEmpty() -> DiagramParticipantRepresentation.HIDE
                    tag !in automaticallyShown -> DiagramParticipantRepresentation.OTHER
                    else -> DiagramParticipantRepresentation.SHOW
                },
            participant = participant,
        )
    }
}

private fun componentCandidates(
    spec: SeqDiagramSpec,
    entries: List<LogEntry>,
    cancellationCheck: CancellationCheck,
): List<DiagramParticipantCandidate> {
    val owner = LinkedHashMap<String, DiagramComponent>()
    spec.components.filter { it.enabled }.forEach { component -> component.tagIds.forEach { owner.putIfAbsent(it, component) } }
    val counts = LinkedHashMap<String, Int>()
    val transitions = HashMap<String, Int>()
    val errors = HashMap<String, Int>()
    var previous: String? = null
    entries.forEachIndexed { index, entry ->
        if (index % CANCELLATION_CHECK_INTERVAL == 0) cancellationCheck()
        counts[entry.tag] = (counts[entry.tag] ?: 0) + 1
        if (errorLevel(entry.level)) errors[entry.tag] = (errors[entry.tag] ?: 0) + 1
        previous?.takeIf { it != entry.tag }?.let { prior ->
            transitions[prior] = (transitions[prior] ?: 0) + 1
            transitions[entry.tag] = (transitions[entry.tag] ?: 0) + 1
        }
        previous = entry.tag
    }
    return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { (tag, count) ->
        val component = owner[tag]
        DiagramParticipantCandidate(
            tag = tag,
            entryCount = count,
            transitionCount = transitions[tag] ?: 0,
            errorCount = errors[tag] ?: 0,
            representation = when {
                component != null -> DiagramParticipantRepresentation.SHOW
                spec.unmappedTagPolicy == UnmappedTagPolicy.GROUP_AS_OTHER -> DiagramParticipantRepresentation.OTHER
                else -> DiagramParticipantRepresentation.HIDE
            },
            participant = component?.let { DiagramParticipant(it.id, it.displayName, ParticipantKind.TAG, tag = tag) },
        )
    }
}

private fun resolveTagParticipants(
    specParticipants: List<DiagramParticipant>,
    candidateEntries: List<LogEntry>,
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
        val rankedTags = candidateEntries.asSequence()
            .groupingBy { it.tag }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
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
    val enabled = spec.components.filter { it.enabled }
    val tagToComponent = LinkedHashMap<String, DiagramComponent>()
    enabled.forEach { component -> component.tagIds.forEach { tag -> tagToComponent.putIfAbsent(tag, component) } }
    val participants = buildList {
        // Preserve old entry/exit actors when a migrated/partially edited spec still has them.
        addAll(spec.participants.filter { it.kind == ParticipantKind.ACTOR })
        spec.actors.forEach { actor ->
            if (none { it.id == actor.id }) add(DiagramParticipant(actor.id, actor.label, ParticipantKind.ACTOR))
        }
        enabled.forEach { component ->
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
) {
    val list: MutableList<DiagramParticipant> = initial.toMutableList()
    private val idxByTag = HashMap<String, Int>()
    private val idxById = HashMap<String, Int>()

    init {
        list.forEachIndexed { i, p ->
            if (p.kind == ParticipantKind.TAG && p.tag != null) idxByTag[p.tag] = i
            idxById[p.id] = i
        }
        val otherIdx = list.indexOfFirst { it.kind == ParticipantKind.TAG && it.representation == DiagramParticipantRepresentation.OTHER }
        if (otherIdx >= 0) groupedTags.forEach { idxByTag[it] = otherIdx }
        tagToParticipantId.forEach { (tag, participantId) -> idxById[participantId]?.let { idxByTag[tag] = it } }
    }

    fun indexForTag(tag: String): Int? = idxByTag[tag]

    fun indexForId(id: String): Int? = idxById[id]

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
}

// ── Message generation ────────────────────────────────────────────────────────────────────────

private fun errorLevel(level: LogLevel): Boolean = level == LogLevel.E || level == LogLevel.A

private class PendingNote(val rawIndex: Int, val participantIdx: Int, val text: String)

private class RawGen(
    val messages: MutableList<DiagramMessage> = mutableListOf(),
    val notes: MutableList<PendingNote> = mutableListOf(),
)

@Suppress("LongParameterList")
private fun runTagTransition(
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
    // Seeding `current` with the entry-point actor (rather than null + a special "first message"
    // branch) means the very first transition — actor to the range's first tag — falls out of the
    // exact same CALL/SELF logic as every later one; no first-entry special case needed.
    var current: Int? = entryPointIdx
    var sinceCancellationCheck = 0
    for (entry in entries) {
        if (++sinceCancellationCheck >= CANCELLATION_CHECK_INTERVAL) {
            sinceCancellationCheck = 0
            cancellationCheck()
        }
        val tagIdx = registry.indexForTag(entry.tag) ?: continue
        val cur = current
        if (cur == null) {
            // Bootstrap: no entry-point actor and this is the range's first entry — nothing exists
            // yet to draw an arrow FROM, so this entry only establishes the starting lifeline.
            current = tagIdx
            continue
        }
        val label = buildLabel(entry, resolveLabel, options, firstTs)
        val kind = if (tagIdx == cur) MessageKind.SELF else MessageKind.CALL
        gen.messages += DiagramMessage(cur, tagIdx, label, entry.id, entry.ts, entry.level, kind)
        if (options.notesForErrors && errorLevel(entry.level)) gen.notes += PendingNote(gen.messages.lastIndex, tagIdx, label)
        current = tagIdx
    }
    val last = entries.lastOrNull()
    val cur = current
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
    var sinceCancellationCheck = 0
    for ((entryIndex, entry) in entries.withIndex()) {
        if (++sinceCancellationCheck >= CANCELLATION_CHECK_INTERVAL) {
            sinceCancellationCheck = 0
            cancellationCheck()
        }
        val label = buildLabel(entry, resolveLabel, options, firstTs)
        val match = enabledRules.firstNotNullOfOrNull { rule ->
            firstRegexMatchResult(entry.msg, rule.pattern, regexContext = regexContext)?.let { rule to it }
        }
        current = if (match == null) {
            runRulesFallthrough(entry, registry, current, label, options, gen)
        } else {
            runRulesMatched(entry, match.first, match.second, registry, label, options, gen, warnings)
        }
    }
}

private fun runRulesFallthrough(
    entry: LogEntry,
    registry: ParticipantRegistry,
    current: Int?,
    label: String,
    options: DiagramOptions,
    gen: RawGen,
): Int? {
    val tagIdx = registry.indexForTag(entry.tag) ?: return current
    if (current == null) return tagIdx
    val kind = if (tagIdx == current) MessageKind.SELF else MessageKind.CALL
    gen.messages += DiagramMessage(current, tagIdx, label, entry.id, entry.ts, entry.level, kind)
    if (options.notesForErrors && errorLevel(entry.level)) gen.notes += PendingNote(gen.messages.lastIndex, tagIdx, label)
    return tagIdx
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
): Int {
    val fromName = substituteTemplate(rule.fromTemplate, matchResult, entry)
    val toName = substituteTemplate(rule.toTemplate, matchResult, entry)
    val rawLabel = substituteTemplate(rule.labelTemplate, matchResult, entry).ifBlank { fallbackLabel }
    val label = truncateLabel(collapseWhitespace(rawLabel), options.labelMaxChars)
    val (fromIdx, fromCreated) = registry.resolveOrCreateActor(fromName)
    val (toIdx, toCreated) = registry.resolveOrCreateActor(toName)
    if (fromCreated) warnings += "Rule '${rule.id}' referenced unknown participant '$fromName' — added it as an actor."
    if (toCreated) warnings += "Rule '${rule.id}' referenced unknown participant '$toName' — added it as an actor."
    val kind = if (fromIdx == toIdx) MessageKind.SELF else MessageKind.CALL
    gen.messages += DiagramMessage(fromIdx, toIdx, label, entry.id, entry.ts, entry.level, kind, evidence = MessageEvidence.RULE)
    if (options.notesForErrors && errorLevel(entry.level)) gen.notes += PendingNote(gen.messages.lastIndex, toIdx, label)
    return toIdx
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

// Budget and provenance validation are intentionally co-located so every callback is bounded
// before it can append a message; extracting the loop would obscure that security invariant.
@Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
private fun buildSourceInteractions(
    entries: List<LogEntry>,
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
    for ((entryIndex, entry) in entries.withIndex()) {
        if (attempts >= messageBudget || messages.size >= messageBudget) {
            truncated = true
            break
        }
        attempts++
        if (entryIndex % CANCELLATION_CHECK_INTERVAL == 0) cancellationCheck()
        val interactions = resolveSourceInteractions(entry)
        if (interactions.size > MAX_SOURCE_INTERACTIONS_PER_ENTRY) truncated = true
        for (interaction in interactions.take(MAX_SOURCE_INTERACTIONS_PER_ENTRY)) {
            val from = registry.indexForId(interaction.fromComponentId)
            val to = registry.indexForId(interaction.toComponentId)
            if (from == null || to == null) {
                warnings += "Source interaction '${interaction.fromComponentId}' → '${interaction.toComponentId}' does not match an enabled component."
                continue
            }
            if (from == to) continue
            val needsReturn = enrichment.addReturnArrows && !interaction.returnLabel.isNullOrBlank()
            val messageCost = if (needsReturn) 2 else 1
            if (messages.size + messageCost > messageBudget) {
                truncated = true
                break
            }
            messages += DiagramMessage(
                from, to, truncateLabel(collapseWhitespace(interaction.label), options.labelMaxChars),
                entry.id, entry.ts, entry.level, MessageKind.CALL, evidence = MessageEvidence.SOURCE_INFERRED,
            )
            if (needsReturn) {
                messages += DiagramMessage(
                    to, from,
                    truncateLabel(collapseWhitespace(interaction.returnLabel.orEmpty()), options.labelMaxChars),
                    entry.id, entry.ts, entry.level, MessageKind.RETURN, evidence = MessageEvidence.SOURCE_INFERRED,
                )
            }
        }
    }
    if (truncated) warnings += "Source enrichment was capped to preserve the diagram message budget."
    return SourceInteractionResult(messages, truncated)
}

/** Duplicate component edges for explicitly mirrored actors, immediately after their original. */
private fun applyActorMirrors(
    messages: List<DiagramMessage>,
    registry: ParticipantRegistry,
    actors: List<DiagramActor>,
): List<DiagramMessage> {
    val mirrors = actors.mapNotNull { actor ->
        val component = actor.mirrorComponentId?.let(registry::indexForId) ?: return@mapNotNull null
        val actorIdx = registry.indexForId(actor.id) ?: return@mapNotNull null
        Triple(component, actorIdx, actor.mirrorDirection)
    }
    if (mirrors.isEmpty()) return messages
    return buildList(messages.size + mirrors.size) {
        messages.forEach { message ->
            add(message)
            if (message.fromIdx == message.toIdx) return@forEach
            mirrors.forEach { (componentIdx, actorIdx, direction) ->
                val outbound = message.fromIdx == componentIdx && direction != MirrorDirection.INBOUND
                val inbound = message.toIdx == componentIdx && direction != MirrorDirection.OUTBOUND
                when {
                    outbound && actorIdx != message.toIdx -> add(message.copy(fromIdx = actorIdx, evidence = MessageEvidence.ACTOR_MIRROR))
                    inbound && actorIdx != message.fromIdx -> add(message.copy(toIdx = actorIdx, evidence = MessageEvidence.ACTOR_MIRROR))
                }
            }
        }
    }
}

/** Activations are emitted only for correlated call/return pairs, never for a bare transition. */
private fun buildActivationSpans(messages: List<DiagramMessage>): List<DiagramActivationSpan> {
    data class OpenCall(val from: Int, val to: Int, val index: Int, val evidence: MessageEvidence)
    val open = ArrayDeque<OpenCall>()
    val spans = mutableListOf<DiagramActivationSpan>()
    messages.forEachIndexed { index, message ->
        when {
            message.kind == MessageKind.CALL && message.fromIdx != message.toIdx && message.evidence != MessageEvidence.ACTOR_MIRROR ->
                open.addLast(OpenCall(message.fromIdx, message.toIdx, index, message.evidence))
            message.kind == MessageKind.RETURN && message.evidence != MessageEvidence.ACTOR_MIRROR -> {
                val match = open.lastOrNull { it.from == message.toIdx && it.to == message.fromIdx && it.evidence == message.evidence }
                if (match != null) {
                    while (open.isNotEmpty() && open.last() != match) open.removeLast()
                    open.removeLast()
                    spans += DiagramActivationSpan(match.to, match.index, index, match.evidence)
                }
            }
        }
    }
    return spans
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
        out += first.copy(repeatCount = count)
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
