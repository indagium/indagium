package com.indagium.diagram3

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.source.SourceIndex
import com.indagium.source.SourceTraceInferenceEngine
import com.indagium.utils.CancellationCheck
import com.indagium.utils.TS_UNKNOWN
import com.indagium.utils.parseMillisOfDay

// ── Log entries -> Seq3Document ─────────────────────────────────────────────────────────────
//
// Pipeline: resolve the range -> rank tags into lifelines (ported from
// `diagram/SeqDiagramBuilder.kt:1004-1168`'s `tagStats`/`resolveTagParticipants`) -> group
// near-identical occurrences per tag and tokenize each group into one Seq3Message (Seq3Tokenizer)
// -> infer each message's target lifeline from adjacent-entry evidence (Seq3Correlation, plus an
// OPT-IN third signal from `source.SourceTraceInferenceEngine` when a caller supplies an already-
// built `SourceIndex` — see [inferTarget]'s own doc), above a confidence bar, else leave it null.
//
// What this deliberately does NOT do (see this phase's brief): no `ArrowMode.RULES` path, no
// `DiagramCallOverride`/`DiagramSourceSiteOverride` machinery, no activation spans. All of that
// belonged to the inference engine `diagram/SeqDiagramBuilder.kt` is being deleted with; v3's
// answer to "I don't like what the generator inferred" is the queue's own edit affordances
// (phase 2+), not more inference knobs here. Source-trace enrichment is the one deliberate
// exception (post-ship addition, see docs/plans/use-the-claude-design-mcp-compiled-lighthouse.md):
// it is read-only against an index the caller already built — this file never triggers indexing
// itself, and a null/disabled index degrades byte-for-byte to the original two-signal behaviour.
//
// Never throws: an unresolvable range (a bad Time bound, an empty entry list) degrades to a
// smaller-than-expected (possibly empty) Seq3Document, matching the rest of this codebase's
// "never throw on bad input" posture for the log-engine layer (see `utils/TextMatch.kt`'s own
// doc, or `diagram/SeqDiagramBuilder.kt`'s `warnings` list for the same idea one package over).

private const val CANCELLATION_CHECK_INTERVAL = 512

/**
 * Builds a [Seq3Document] from [entries] restricted to [range]. [entries] plays the role
 * `diagram.SeqDiagramBuilder`'s `allVisible` did: whichever raw-or-filtered view the caller wants
 * scanned, already resolved to a flat, ascending-by-id list. [cancellationCheck] is invoked at
 * every major phase boundary and periodically inside the two entry-count-sized scans, mirroring
 * `utils.Filter.computeItems`'s own polling convention (see that function's `CancellationCheck`
 * parameter) — a caller running this on a cancellable coroutine can make a long generation on a
 * huge range abort promptly by having it throw. [sourceIndex], when supplied, feeds the optional
 * third target-inference signal (see [inferTarget]'s own doc) — this function only ever READS it
 * (one [SourceTraceInferenceEngine.resolve] call, built ONCE for the whole call, never per-message);
 * it never builds, refreshes, or otherwise triggers indexing itself, keeping this function UI-free
 * and safe to call from the export path/MCP handler exactly as before. Defaulting to null keeps
 * every pre-existing caller's behaviour byte-for-byte unchanged.
 */
fun generateSeq3(
    entries: List<LogEntry>,
    range: Seq3Range,
    options: Seq3GenerateOptions = Seq3GenerateOptions(),
    cancellationCheck: () -> Unit = {},
    sourceIndex: SourceIndex? = null,
): Seq3Document {
    cancellationCheck()
    val resolved = resolveRange(entries, range)
    if (resolved.isEmpty()) {
        return Seq3Document(title = options.title, sourceFile = options.sourceFile, range = range, defaultRepeat = options.defaultRepeat)
    }

    val lifelines = rankLifelines(resolved, options.maxLifelines, cancellationCheck)
    val lifelineIdByTag = lifelines.flatMap { lifeline -> lifeline.tagIds.map { it to lifeline.id } }.toMap()
    if (lifelineIdByTag.isEmpty()) {
        return Seq3Document(title = options.title, sourceFile = options.sourceFile, range = range, defaultRepeat = options.defaultRepeat)
    }

    val entryIndexById = HashMap<Int, Int>(resolved.size * 2)
    resolved.forEachIndexed { index, entry -> entryIndexById[entry.id] = index }

    val byTag = LinkedHashMap<String, MutableList<LogEntry>>()
    resolved.forEach { entry -> if (entry.tag in lifelineIdByTag) byTag.getOrPut(entry.tag) { mutableListOf() }.add(entry) }

    val sourceTraceCallEntryIds = resolveSourceTraceCallEntryIds(resolved, options, sourceIndex, cancellationCheck)

    val messages = mutableListOf<Seq3Message>()
    byTag.forEach { (tag, tagEntries) ->
        cancellationCheck()
        groupByShape(tagEntries).forEach { group ->
            cancellationCheck()
            messages += buildMessages(tag, group, lifelineIdByTag, resolved, entryIndexById, options, sourceTraceCallEntryIds)
        }
    }

    // Derived order = log clock: each message sorts by its own first occurrence's timestamp, then
    // by that occurrence's LogEntry.id as the deterministic tiebreak for same-millisecond rows —
    // "No layout concerns here" per this phase's brief; a caller wanting a different VIEW order
    // (by lifeline, by occurrence count, by state) sorts this list itself (Seq3Queue, phase 2).
    val ordered = messages
        .sortedWith(compareBy({ it.occurrences.first().timestampMillis ?: Long.MAX_VALUE }, { it.occurrences.first().entryId }))
        .mapIndexed { index, message -> message.copy(id = "msg-${index + 1}") }

    return Seq3Document(
        title = options.title,
        sourceFile = options.sourceFile,
        range = range,
        lifelines = lifelines,
        messages = ordered,
        defaultRepeat = options.defaultRepeat,
    )
}

// ── Range resolution ─────────────────────────────────────────────────────────────────────────
//
// Ported from `diagram/SeqDiagramBuilder.kt:562-821`'s `resolveRange`/`resolveIdsRange`/
// `resolveTimeRange`. Simplified: the original distinguished the tab's whole `logData` (for
// Time's carry-forward walk) from the caller's already-filtered `allVisible` list; here [entries]
// plays both roles at once (the caller decides up front which view it wants scanned), so the
// merged two-cursor walk collapses into one straight pass.

private fun resolveRange(entries: List<LogEntry>, range: Seq3Range): List<LogEntry> = when (range) {
    is Seq3Range.VisibleView -> entries
    is Seq3Range.Ids -> resolveIdsRange(entries, range)
    is Seq3Range.Time -> resolveTimeRange(entries, range)
}

private fun resolveIdsRange(entries: List<LogEntry>, range: Seq3Range.Ids): List<LogEntry> {
    if (entries.isEmpty()) return emptyList()
    if (range.selectedIds.isNotEmpty()) return entries.filter { it.id in range.selectedIds }
    val minId = minOf(range.from, range.to)
    val maxId = maxOf(range.from, range.to)
    val ids = IntArray(entries.size) { entries[it].id }
    // Clamp to the nearest in-range index rather than requiring an exact id match — a caller's
    // selection can legitimately name ids the current filter is hiding, same as `diagram.
    // DiagramRange.Ids`'s own doc.
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
    if (startIdx >= endIdxExclusive) return emptyList()
    return entries.subList(startIdx, endIdxExclusive)
}

private fun resolveTimeRange(entries: List<LogEntry>, range: Seq3Range.Time): List<LogEntry> {
    if (entries.none { parseMillisOfDay(it.ts) != TS_UNKNOWN }) return emptyList()
    val fromMs = parseMillisOfDay(range.fromTs)
    val toMs = parseMillisOfDay(range.toTs)
    if (fromMs == TS_UNKNOWN || toMs == TS_UNKNOWN) return emptyList()
    val minMs = minOf(fromMs, toMs)
    val maxMs = maxOf(fromMs, toMs)
    // Carry-forward: a row with no parseable ts of its own (brief/RAW format) inherits the
    // previous row's timestamp — same rule as `diagram.DiagramRange.Time`'s own resolver.
    var prevMs = TS_UNKNOWN
    val matched = ArrayList<LogEntry>()
    entries.forEach { entry ->
        val ms = parseMillisOfDay(entry.ts)
        if (ms != TS_UNKNOWN) prevMs = ms
        if (prevMs != TS_UNKNOWN && prevMs in minMs..maxMs) matched += entry
    }
    return matched
}

// ── Lifeline ranking ─────────────────────────────────────────────────────────────────────────
//
// Ported from `diagram/SeqDiagramBuilder.kt:984-1041`'s `TagStats`/`tagStats`/`tagScoreComparator`.
// `transitionCount` (unused by [Seq3Lifeline] — it only ever backed a UI display counter on
// `DiagramParticipantCandidate`) and `pids` (same: display-only) are dropped; the ranking itself
// — errors, message-shape diversity, same-thread peers, a capped raw count — is unchanged.

private const val SIGNAL_ERROR_WEIGHT = 4
private const val SIGNAL_TEMPLATE_WEIGHT = 2
private const val SIGNAL_COUNT_CEILING = 10
private const val THREAD_PEER_SCORE_WEIGHT = 3
private const val MAX_THREAD_PEER_TAGS = 5

private class TagStats {
    val counts = LinkedHashMap<String, Int>()
    val errors = HashMap<String, Int>()
    val shapes = HashMap<String, MutableSet<String>>()
    val threadPeers = HashMap<String, MutableSet<String>>()

    fun signalScore(tag: String): Int =
        SIGNAL_ERROR_WEIGHT * (errors[tag] ?: 0) + SIGNAL_TEMPLATE_WEIGHT * (shapes[tag]?.size ?: 0) +
            THREAD_PEER_SCORE_WEIGHT * (threadPeers[tag]?.size ?: 0) + minOf(counts[tag] ?: 0, SIGNAL_COUNT_CEILING)
}

private fun errorLevel(entry: LogEntry): Boolean = entry.level == LogLevel.E || entry.level == LogLevel.A

private fun tagStats(entries: List<LogEntry>, cancellationCheck: () -> Unit): TagStats {
    val stats = TagStats()
    val tagsByThread = HashMap<Pair<Int, Int>, MutableSet<String>>()
    entries.forEachIndexed { index, entry ->
        if (index % CANCELLATION_CHECK_INTERVAL == 0) cancellationCheck()
        stats.counts[entry.tag] = (stats.counts[entry.tag] ?: 0) + 1
        if (errorLevel(entry)) stats.errors[entry.tag] = (stats.errors[entry.tag] ?: 0) + 1
        stats.shapes.getOrPut(entry.tag) { linkedSetOf() }.add(messageShapeKey(entry.msg))
        if (entry.pid != 0 && entry.tid != 0) tagsByThread.getOrPut(entry.pid to entry.tid) { HashSet() }.add(entry.tag)
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

private fun rankLifelines(entries: List<LogEntry>, maxLifelines: Int, cancellationCheck: () -> Unit): List<Seq3Lifeline> {
    val stats = tagStats(entries, cancellationCheck)
    return stats.counts.entries.sortedWith(tagScoreComparator(stats)).take(maxLifelines).mapIndexed { index, (tag, _) ->
        cancellationCheck()
        Seq3Lifeline(id = tag, name = tag, tagIds = setOf(tag), ordinal = index)
    }
}

// ── Near-identical grouping + message construction ──────────────────────────────────────────

private val SHAPE_UUID_RE = Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""")
private val SHAPE_HEX_RE = Regex("""(?i)\b[0-9a-f]{16,}\b""")

// Any run of letters/digits containing at least one digit — deliberately broader than the old
// "\b\d+\b"-only mask (see this function's own bug history, item 3 of the phase-5 post-ship plan):
// a short mixed alnum id (a USB device handle like "1a2b", a session id like "usbdev3") has no pure
// digit run bounded by \b on both sides — \b only fires at a transition between a word char and a
// non-word char, and letters/digits/underscore are ALL word chars, so "1a2b" or "dev3" never had a
// masked boundary under the old regex. Left unmasked, every occurrence produced a DIFFERENT shape
// key, so `groupByShape` never even offered the tokenizer a chance to prove one shared pattern —
// each occurrence became its own single-occurrence Seq3Message, sorted into the document by its own
// exact timestamp and interleaved with unrelated tags' messages instead of staying one grouped
// repeat (confirmed via Seq3GeneratorTest, not just static reading — see that test's own doc).
// Matching per alnum TOKEN (split on underscore/punctuation, not one blanket digit regex) keeps a
// stable non-digit prefix/suffix like "usbdev" or "poll_tick" as literal context in the shape key —
// only the actually-varying id-like run collapses to one placeholder. Over-masking here is always
// safe: a shape key is only a CANDIDATE grouping (this function's own doc, next paragraph) — the
// tokenizer below still has to prove a real shared pattern before two occurrences are ever actually
// merged into one message.
private val SHAPE_ALNUM_TOKEN_RE = Regex("[0-9A-Za-z]+")
private val SHAPE_WHITESPACE_RE = Regex("\\s+")

/** A cheap, lossy grouping key — NOT the final template (Seq3Tokenizer proves that). Two
 *  occurrences with the same masked shape are merely CANDIDATES for one Seq3Tokenizer call;
 *  `buildMessages` still falls back to one literal message per occurrence if the tokenizer can't
 *  actually prove a shared pattern across the group (e.g. two candidates share a shape but differ
 *  in unrelated ways the tokenizer's stricter single-run-or-named-values rule rejects). */
private fun messageShapeKey(message: String): String {
    val withStableTokensMasked = message
        .lowercase()
        .replace(SHAPE_UUID_RE, " u")
        .replace(SHAPE_HEX_RE, " h")
    val withDigitBearingTokensMasked = SHAPE_ALNUM_TOKEN_RE.replace(withStableTokensMasked) { m ->
        if (m.value.any(Char::isDigit)) "n" else m.value
    }
    return withDigitBearingTokensMasked.replace(SHAPE_WHITESPACE_RE, " ").trim()
}

private fun groupByShape(entries: List<LogEntry>): List<List<LogEntry>> {
    val groups = LinkedHashMap<String, MutableList<LogEntry>>()
    entries.forEach { entry -> groups.getOrPut(messageShapeKey(entry.msg)) { mutableListOf() }.add(entry) }
    return groups.values.toList()
}

private fun buildMessages(
    tag: String,
    group: List<LogEntry>,
    lifelineIdByTag: Map<String, String>,
    resolved: List<LogEntry>,
    entryIndexById: Map<Int, Int>,
    options: Seq3GenerateOptions,
    sourceTraceCallEntryIds: Set<Int> = emptySet(),
): List<Seq3Message> {
    val result = tokenizeSeq3Messages(tag, group.map { Seq3TokenizeInput(it.id.toString(), it.msg) })
    val match = result.match
    if (result.compiled && match != null) {
        return listOf(
            toMessage(tag, match, group, result.captureValuesByOccurrence, lifelineIdByTag, resolved, entryIndexById, options, sourceTraceCallEntryIds),
        )
    }
    // The shape-grouped candidates turned out not to share a provable pattern — fall back to one
    // literal message per occurrence rather than dropping any log row. A single-occurrence
    // tokenizeSeq3Messages call always compiles (see that function's own doc), so `!!` here can
    // never actually fail.
    return group.map { entry ->
        val single = tokenizeSeq3Messages(tag, listOf(Seq3TokenizeInput(entry.id.toString(), entry.msg)))
        toMessage(
            tag, single.match!!, listOf(entry), single.captureValuesByOccurrence, lifelineIdByTag, resolved, entryIndexById, options,
            sourceTraceCallEntryIds,
        )
    }
}

/** One [Seq3Occurrence] from a raw [LogEntry] plus whatever [captureValues] the tokenizer proved
 *  for it — the exact shape every occurrence in this package is built from, shared by [toMessage]
 *  and [addSeq3MessageFromSelection] so the two never drift on what an occurrence looks like. */
private fun toOccurrence(entry: LogEntry, captureValues: Map<String, String>): Seq3Occurrence {
    val millis = parseMillisOfDay(entry.ts)
    return Seq3Occurrence(
        entryId = entry.id,
        timestampMillis = millis.takeIf { it != TS_UNKNOWN },
        rawTimestamp = entry.ts,
        pid = entry.pid,
        tid = entry.tid,
        level = entry.level.key,
        text = entry.msg,
        captureValues = captureValues,
    )
}

private fun toMessage(
    tag: String,
    match: Seq3Match,
    group: List<LogEntry>,
    captureValuesByOccurrence: Map<String, Map<String, String>>,
    lifelineIdByTag: Map<String, String>,
    resolved: List<LogEntry>,
    entryIndexById: Map<Int, Int>,
    options: Seq3GenerateOptions,
    sourceTraceCallEntryIds: Set<Int> = emptySet(),
): Seq3Message {
    val occurrences = group.map { entry -> toOccurrence(entry, captureValuesByOccurrence[entry.id.toString()].orEmpty()) }
    return Seq3Message(
        // reassigned once every tag's messages are merged and sorted into log-clock order
        id = "",
        match = match,
        fromLifelineId = lifelineIdByTag.getValue(tag),
        toLifelineId = inferTarget(tag, group, resolved, entryIndexById, lifelineIdByTag, options, sourceTraceCallEntryIds),
        labelTemplate = match.template,
        repeat = options.defaultRepeat,
        repeatThreshold = options.defaultRepeatThreshold,
        occurrences = occurrences,
    )
}

// ── Target inference ─────────────────────────────────────────────────────────────────────────
//
// "From is reliable (the tag), so it gets no affordance; To is the one thing the generator cannot
// infer" — this is deliberately conservative. For each occurrence, only its IMMEDIATE successor in
// the resolved range is ever considered as a candidate target (the same one-hop-only posture
// `diagram.SeqDiagramBuilder`'s own thread-handoff/correlation evidence used), and a target is
// only assigned when a clear majority of the message's evidenced occurrences agree AND that
// candidate tag is itself a lifeline this document actually kept. Anything short of that leaves
// `toLifelineId == null` — never a guess, and never a fabricated lifeline for a tag that didn't
// make the cut (see Seq3Model.kt's own doc on why a null target must never be defaulted away).
//
// [sourceTraceCallEntryIds] is the third, OPT-IN signal (post-ship addition): the entryIds of every
// log line `SourceTraceInferenceEngine` independently proved is the callee-side evidence of a real
// source-level call (see [resolveSourceTraceCallEntryIds]'s own doc for exactly what that means and
// how it's computed once per [generateSeq3] call). It is folded into the SAME evidenced/considered
// vote thread-handoff and correlation-token already use — never a bypass of [TARGET_CONFIDENCE_RATIO]
// — so a caller who supplies no index, or turns the option off, sees byte-identical inference to
// before this signal existed (the set is simply empty).

private const val TARGET_CONFIDENCE_RATIO = 0.6
private const val MIN_TARGET_EVIDENCE_COUNT = 1

/**
 * Runs [SourceTraceInferenceEngine.resolve] ONCE over [resolved] — never per-message, which would be
 * wildly expensive — and reduces its result down to exactly what [inferTarget] needs: the set of
 * entryIds the source trace independently confirmed as the CALLEE side of a real call (a
 * `DiagramTraceCall.callEntryId`, per that type's own doc — the log line where source-level evidence
 * first proves a call landed in a different method, which is exactly the "did control actually cross
 * into this next tag" question target inference is trying to answer). Returns an empty set — the
 * same as if this signal did not exist — whenever the option is off or no index was supplied; this
 * function never builds or triggers a [SourceIndex] itself.
 */
private fun resolveSourceTraceCallEntryIds(
    resolved: List<LogEntry>,
    options: Seq3GenerateOptions,
    sourceIndex: SourceIndex?,
    cancellationCheck: () -> Unit,
): Set<Int> {
    if (!options.sourceTraceEnabled || sourceIndex == null) return emptySet()
    return SourceTraceInferenceEngine(sourceIndex)
        .resolve(resolved, CancellationCheck(cancellationCheck))
        .calls.mapTo(HashSet()) { it.callEntryId }
}

private fun inferTarget(
    tag: String,
    group: List<LogEntry>,
    resolved: List<LogEntry>,
    entryIndexById: Map<Int, Int>,
    lifelineIdByTag: Map<String, String>,
    options: Seq3GenerateOptions,
    sourceTraceCallEntryIds: Set<Int> = emptySet(),
): String? {
    val candidateCounts = LinkedHashMap<String, Int>()
    var consideredCount = 0
    group.forEach { entry ->
        val index = entryIndexById[entry.id] ?: return@forEach
        val next = resolved.getOrNull(index + 1) ?: return@forEach
        if (next.tag == tag) return@forEach
        val evidenced = (options.threadHandoffEnabled && isThreadHandoff(next, entry)) ||
            (options.correlationTokenEnabled && hasSharedCorrelationToken(next, entry)) ||
            next.id in sourceTraceCallEntryIds
        if (!evidenced) return@forEach
        consideredCount++
        if (next.tag in lifelineIdByTag) candidateCounts[next.tag] = (candidateCounts[next.tag] ?: 0) + 1
    }
    if (consideredCount == 0) return null
    val best = candidateCounts.entries.maxByOrNull { it.value } ?: return null
    if (best.value < MIN_TARGET_EVIDENCE_COUNT) return null
    if (best.value.toDouble() / consideredCount < TARGET_CONFIDENCE_RATIO) return null
    return lifelineIdByTag[best.key]
}

// ── Add one message from an arbitrary row selection (queue panel's "＋ Add") ────────────────────
//
// `generateSeq3` only ever discovers messages by scanning a whole range tag-by-tag; nothing lets a
// user build ONE message from rows they picked themselves. This is that capability — deliberately
// reusing [tokenizeSeq3Messages] and [toOccurrence] (the exact same pieces [buildMessages]/
// [toMessage] are built from) rather than a second implementation of "log rows -> Seq3Message".

/** Outcome of [addSeq3MessageFromSelection]. [Rejected.reason] is meant to be shown verbatim in the
 *  queue panel — see that function's own doc for the exact strings it returns. */
sealed class Seq3AddResult {
    data class Added(val document: Seq3Document, val newMessageId: String) : Seq3AddResult()

    data class Rejected(val reason: String) : Seq3AddResult()
}

/**
 * Builds ONE new [Seq3Message] from [selectedEntries] and appends it (and, if needed, a new
 * [Seq3Lifeline]) to [document]. [selectedEntries] must share exactly one [LogEntry.tag] — a
 * message's [Seq3Match.tag] is singular by construction (mirrors every generated message), so a
 * mixed-tag selection is rejected rather than guessing a dominant tag. An existing lifeline for that
 * tag is reused; a brand-new tag gets a fresh lifeline appended at the end of [Seq3Document.lifelines]
 * (`ordinal = document.lifelines.size`, same convention [rankLifelines] uses for a freshly ranked
 * one). [Seq3Message.toLifelineId] is always left null — "needs a target", the same conservative
 * default [inferTarget] leaves an unresolved message at; a user-picked row selection has no adjacent-
 * entry evidence of its own to infer from, so this function never attempts to.
 */
fun addSeq3MessageFromSelection(document: Seq3Document, selectedEntries: List<LogEntry>): Seq3AddResult {
    if (selectedEntries.isEmpty()) return Seq3AddResult.Rejected("Select at least one log row")
    val tags = selectedEntries.mapTo(linkedSetOf()) { it.tag }
    if (tags.size > 1) return Seq3AddResult.Rejected("Select rows from a single tag")
    val tag = tags.single()

    val existingLifeline = document.lifelines.firstOrNull { tag in it.tagIds }
    val lifeline = existingLifeline ?: Seq3Lifeline(id = tag, name = tag, tagIds = setOf(tag), ordinal = document.lifelines.size)
    val withLifeline = if (existingLifeline != null) document else document.copy(lifelines = document.lifelines + lifeline)

    val sorted = selectedEntries.sortedBy { it.id }
    val tokenized = tokenizeSeq3Messages(tag, sorted.map { Seq3TokenizeInput(it.id.toString(), it.msg) })
    val match = tokenized.match ?: return Seq3AddResult.Rejected(tokenized.error ?: "Selected rows do not share a provable pattern")
    val occurrences = sorted.map { entry -> toOccurrence(entry, tokenized.captureValuesByOccurrence[entry.id.toString()].orEmpty()) }

    val newMessageId = nextSeq3MessageId(withLifeline)
    val message = Seq3Message(
        id = newMessageId,
        match = match,
        fromLifelineId = lifeline.id,
        toLifelineId = null,
        labelTemplate = match.template,
        repeat = withLifeline.defaultRepeat,
        occurrences = occurrences,
    )
    return Seq3AddResult.Added(withLifeline.copy(messages = withLifeline.messages + message), newMessageId)
}

/**
 * Adds a message authored without log evidence. The caller must provide both endpoint choices for
 * arrow kinds, an explicit insertion position, and may optionally provide a timeline timestamp.
 * No synthetic [Seq3Occurrence] is created: custom rows have no real log line to navigate to, and
 * the renderer handles their evidence-free shape directly.
 *
 * If [Seq3CustomMessageSpec.fragmentId] is set, the new message is also added to that existing
 * semantic fragment. This supports inserting an authored message into an existing OPT/ALT/LOOP/PAR
 * section without manufacturing a second, overlapping fragment.
 */
@Suppress("ReturnCount")
fun addSeq3CustomMessage(document: Seq3Document, spec: Seq3CustomMessageSpec): Seq3CustomMessageResult {
    val text = spec.text.trim()
    if (text.isEmpty()) return Seq3CustomMessageResult.Rejected("Message text is required")
    val from = document.lifelines.firstOrNull { it.id == spec.fromLifelineId }
        ?: return Seq3CustomMessageResult.Rejected("Unknown source lifeline")
    val to = when (spec.kind) {
        Seq3Kind.NOTE -> {
            if (spec.toLifelineId != null) return Seq3CustomMessageResult.Rejected("A note message cannot have a target lifeline")
            null
        }
        Seq3Kind.SELF -> {
            if (spec.toLifelineId != null && spec.toLifelineId != from.id) {
                return Seq3CustomMessageResult.Rejected("A self message must target its source lifeline")
            }
            from.id
        }
        else -> {
            val targetId = spec.toLifelineId ?: return Seq3CustomMessageResult.Rejected("Target lifeline is required")
            if (document.lifelines.none { it.id == targetId }) return Seq3CustomMessageResult.Rejected("Unknown target lifeline")
            targetId
        }
    }
    val fragment = spec.fragmentId?.let { id ->
        document.fragments.firstOrNull { it.id == id }
            ?: return Seq3CustomMessageResult.Rejected("Unknown fragment")
    }
    val insertionIndex = resolveSeq3InsertionIndex(document.messages, spec.position)
        ?: return Seq3CustomMessageResult.Rejected("Invalid message insertion position")
    val newMessageId = nextSeq3MessageId(document)
    val tag = from.tagIds.firstOrNull() ?: from.id
    val rawTimestamp = spec.rawTimestamp.trim()
    val message = Seq3Message(
        id = newMessageId,
        match = Seq3Match(tag = tag, template = text),
        fromLifelineId = from.id,
        toLifelineId = to,
        labelTemplate = text,
        kind = spec.kind,
        repeat = spec.repeat,
        authoring = Seq3Authoring.EDITED,
        occurrences = emptyList(),
        manualTimestampMillis = spec.timestampMillis ?: parseSeq3Timestamp(rawTimestamp),
        manualRawTimestamp = rawTimestamp,
    )
    val messages = document.messages.toMutableList().apply { add(insertionIndex, message) }
    val updatedFragments = document.fragments.map { current ->
        if (current.id != fragment?.id) {
            current
        } else {
            val ids = (current.messageIds + newMessageId).distinct()
            current.copy(messageIds = ids.sortedBy { id -> messages.indexOfFirst { it.id == id } })
        }
    }
    return Seq3CustomMessageResult.Added(
        document = document.copy(messages = messages, fragments = updatedFragments),
        newMessageId = newMessageId,
        insertionIndex = insertionIndex,
    )
}

/** Changes only the author-controlled timestamp, retaining all immutable log evidence. Passing a
 *  null millis value and a blank raw value clears the override and restores evidence-based order. */
fun updateSeq3MessageTimestamp(
    document: Seq3Document,
    messageId: String,
    timestampMillis: Long?,
    rawTimestamp: String = "",
): Seq3MessageEditResult {
    if (document.messages.none { it.id == messageId }) return Seq3MessageEditResult.Rejected("Unknown message")
    val normalizedRawTimestamp = rawTimestamp.trim()
    val updated = document.copy(
        messages = document.messages.map { message ->
            if (message.id == messageId) {
                message.copy(
                    manualTimestampMillis = timestampMillis ?: parseSeq3Timestamp(normalizedRawTimestamp),
                    manualRawTimestamp = normalizedRawTimestamp,
                    authoring = Seq3Authoring.EDITED,
                )
            } else {
                message
            }
        },
    )
    return Seq3MessageEditResult.Updated(updated)
}

/** Changes visibility for one evidence row only. Parent message visibility remains separate, so
 * hiding a single occurrence never hides its siblings and hiding the parent still hides all of
 * the message's canvas output. */
fun setSeq3OccurrenceVisibility(
    document: Seq3Document,
    messageId: String,
    entryId: Int,
    visibility: Seq3Visibility,
): Seq3MessageEditResult {
    val message = document.messages.firstOrNull { it.id == messageId }
        ?: return Seq3MessageEditResult.Rejected("Unknown message")
    if (message.occurrences.none { it.entryId == entryId }) {
        return Seq3MessageEditResult.Rejected("Unknown occurrence")
    }
    val updated = document.copy(
        messages = document.messages.map { current ->
            if (current.id != messageId) current else current.copy(
                occurrences = current.occurrences.map { occurrence ->
                    if (occurrence.entryId == entryId) occurrence.copy(visibility = visibility) else occurrence
                },
            )
        },
    )
    return Seq3MessageEditResult.Updated(updated)
}

/** Splits one real log occurrence out of a repeated message. The extracted message keeps the
 *  source pattern, endpoints, kind and label, but receives its own stable id and is inserted in
 *  chronological queue position using that occurrence's timestamp. Unknown timestamps sort
 *  after known ones, matching the queue's existing ordering convention. */
fun moveSeq3OccurrenceOut(document: Seq3Document, messageId: String, entryId: Int): Seq3MessageEditResult {
    val sourceIndex = document.messages.indexOfFirst { it.id == messageId }
    if (sourceIndex < 0) return Seq3MessageEditResult.Rejected("Unknown message")
    val source = document.messages[sourceIndex]
    if (source.occurrences.size <= 1) return Seq3MessageEditResult.Rejected("The message has no repeated occurrence to move")
    val occurrence = source.occurrences.firstOrNull { it.entryId == entryId }
        ?: return Seq3MessageEditResult.Rejected("Unknown occurrence")
    val remainingOccurrences = source.occurrences.filterNot { it.entryId == entryId }
    val newMessageId = nextSeq3MessageId(document)
    val extracted = source.copy(
        id = newMessageId,
        occurrences = listOf(occurrence),
        authoring = Seq3Authoring.EDITED,
        movedOutFromMessageId = source.id,
        orderPin = null,
        manualTimestampMillis = null,
        manualRawTimestamp = "",
    )
    val updatedSource = source.copy(
        occurrences = remainingOccurrences,
        authoring = Seq3Authoring.EDITED,
        orderPin = null,
    )
    val remainingMessages = document.messages.toMutableList().apply { removeAt(sourceIndex) }
    fun entryIdOf(message: Seq3Message): Int = message.occurrences.firstOrNull()?.entryId ?: Int.MAX_VALUE
    fun comesAfter(left: Seq3Message, right: Seq3Message): Boolean {
        val leftTimestamp = left.primaryTimestampMillis ?: Long.MAX_VALUE
        val rightTimestamp = right.primaryTimestampMillis ?: Long.MAX_VALUE
        return leftTimestamp > rightTimestamp ||
            (leftTimestamp == rightTimestamp && entryIdOf(left) > entryIdOf(right))
    }
    fun insertChronologically(message: Seq3Message) {
        val index = remainingMessages.indexOfFirst { comesAfter(it, message) }
            .takeIf { it >= 0 } ?: remainingMessages.size
        remainingMessages.add(index, message)
    }
    insertChronologically(updatedSource)
    insertChronologically(extracted)

    fun repoint(ids: List<String>): List<String> = if (messageId !in ids) {
        ids
    } else {
        (ids + newMessageId).distinct().sortedBy { remainingMessages.indexOfFirst { message -> message.id == it } }
    }
    return Seq3MessageEditResult.Updated(
        document.copy(
            messages = remainingMessages,
            fragments = document.fragments.map { it.copy(messageIds = repoint(it.messageIds)) },
            notes = document.notes.map { it.copy(messageIds = repoint(it.messageIds)) },
        ),
    )
}

/** Moves several checked occurrences out as one undoable command. At least one occurrence must
 * remain in every source group; otherwise the group itself would disappear and the operation
 * would no longer mean "move out". */
fun moveSeq3OccurrencesOut(document: Seq3Document, references: List<Seq3OccurrenceRef>): Seq3MessageEditResult {
    val distinctReferences = references.distinct()
    if (distinctReferences.isEmpty()) return Seq3MessageEditResult.Rejected("Select at least one occurrence")
    val selectedByMessage = distinctReferences.groupBy { it.messageId }
    selectedByMessage.forEach { (messageId, refs) ->
        val message = document.messages.firstOrNull { it.id == messageId }
            ?: return Seq3MessageEditResult.Rejected("Unknown message")
        if (refs.size >= message.occurrences.size) {
            return Seq3MessageEditResult.Rejected("Keep at least one occurrence in each message group")
        }
    }
    var current = document
    distinctReferences.forEach { reference ->
        current = when (val result = moveSeq3OccurrenceOut(current, reference.messageId, reference.entryId)) {
            is Seq3MessageEditResult.Updated -> result.document
            is Seq3MessageEditResult.Rejected -> return result
        }
    }
    return Seq3MessageEditResult.Updated(current)
}

/** Parses the same clock format used by log rows. Unknown/free-form text remains displayable as
 *  [Seq3Message.manualRawTimestamp], but does not participate in chronological sorting. */
fun parseSeq3Timestamp(rawTimestamp: String): Long? =
    parseMillisOfDay(rawTimestamp.trim()).takeIf { it != TS_UNKNOWN }

/** Moves one message to an explicit queue position without changing its endpoints, text, evidence,
 *  or timestamp. The position is resolved after removing the message, so `AtIndex(0)` always means
 *  the first final row and moving relative to the message itself is rejected safely. */
fun moveSeq3Message(document: Seq3Document, messageId: String, position: Seq3InsertionPosition): Seq3MessageEditResult {
    val currentIndex = document.messages.indexOfFirst { it.id == messageId }
    if (currentIndex < 0) return Seq3MessageEditResult.Rejected("Unknown message")
    if (!document.messages[currentIndex].isCustom) {
        return Seq3MessageEditResult.Rejected("Only custom messages can be moved")
    }
    val remaining = document.messages.toMutableList().apply { removeAt(currentIndex) }
    val insertionIndex = resolveSeq3InsertionIndex(remaining, position)
        ?: return Seq3MessageEditResult.Rejected("Invalid message insertion position")
    if (insertionIndex == currentIndex && currentIndex <= remaining.size) {
        return Seq3MessageEditResult.Updated(document)
    }
    remaining.add(insertionIndex, document.messages[currentIndex])
    return Seq3MessageEditResult.Updated(document.copy(messages = remaining))
}

private fun resolveSeq3InsertionIndex(
    messages: List<Seq3Message>,
    position: Seq3InsertionPosition,
): Int? = when (position) {
    Seq3InsertionPosition.Start -> 0
    Seq3InsertionPosition.End -> messages.size
    is Seq3InsertionPosition.AtIndex -> position.index.takeIf { it in 0..messages.size }
    is Seq3InsertionPosition.BeforeMessage -> messages.indexOfFirst { it.id == position.messageId }.takeIf { it >= 0 }
    is Seq3InsertionPosition.AfterMessage -> messages.indexOfFirst { it.id == position.messageId }
        .takeIf { it >= 0 }
        ?.plus(1)
}

/** The next free `"msg-N"` id — [generateSeq3]'s own naming scheme (`"msg-${index + 1}"`), extended
 *  here to guarantee uniqueness against an EXISTING document's messages rather than a freshly
 *  generated, empty-to-start list: an incremental single-message add can't just reuse
 *  `document.messages.size + 1` blindly (a prior merge/split can leave that number already taken),
 *  so this walks forward from it until a free one is found. */
private fun nextSeq3MessageId(document: Seq3Document): String {
    val existingIds = document.messages.mapTo(HashSet()) { it.id }
    var candidate = document.messages.size + 1
    while ("msg-$candidate" in existingIds) candidate++
    return "msg-$candidate"
}
