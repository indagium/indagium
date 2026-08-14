package com.indagium.diagram3

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.utils.TS_UNKNOWN
import com.indagium.utils.parseMillisOfDay

// ── Log entries -> Seq3Document ─────────────────────────────────────────────────────────────
//
// Pipeline: resolve the range -> rank tags into lifelines (ported from
// `diagram/SeqDiagramBuilder.kt:1004-1168`'s `tagStats`/`resolveTagParticipants`) -> group
// near-identical occurrences per tag and tokenize each group into one Seq3Message (Seq3Tokenizer)
// -> infer each message's target lifeline from adjacent-entry evidence (Seq3Correlation), above a
// confidence bar, else leave it null.
//
// What this deliberately does NOT do (see this phase's brief): no source-index enrichment, no
// `ArrowMode.RULES` path, no `DiagramCallOverride`/`DiagramSourceSiteOverride` machinery, no
// activation spans. All of that belonged to the inference engine `diagram/SeqDiagramBuilder.kt`
// is being deleted with; v3's answer to "I don't like what the generator inferred" is the queue's
// own edit affordances (phase 2+), not more inference knobs here.
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
 * huge range abort promptly by having it throw.
 */
fun generateSeq3(
    entries: List<LogEntry>,
    range: Seq3Range,
    options: Seq3GenerateOptions = Seq3GenerateOptions(),
    cancellationCheck: () -> Unit = {},
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

    val messages = mutableListOf<Seq3Message>()
    byTag.forEach { (tag, tagEntries) ->
        cancellationCheck()
        groupByShape(tagEntries).forEach { group ->
            cancellationCheck()
            messages += buildMessages(tag, group, lifelineIdByTag, resolved, entryIndexById, options)
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
private val SHAPE_NUMBER_RE = Regex("""\b\d+(?:\.\d+)?\b""")
private val SHAPE_WHITESPACE_RE = Regex("\\s+")

/** A cheap, lossy grouping key — NOT the final template (Seq3Tokenizer proves that). Two
 *  occurrences with the same masked shape are merely CANDIDATES for one Seq3Tokenizer call;
 *  `buildMessages` still falls back to one literal message per occurrence if the tokenizer can't
 *  actually prove a shared pattern across the group (e.g. two candidates share a shape but differ
 *  in unrelated ways the tokenizer's stricter single-run-or-named-values rule rejects). */
private fun messageShapeKey(message: String): String = message
    .lowercase()
    .replace(SHAPE_UUID_RE, " u")
    .replace(SHAPE_HEX_RE, " h")
    .replace(SHAPE_NUMBER_RE, " n")
    .replace(SHAPE_WHITESPACE_RE, " ")
    .trim()

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
): List<Seq3Message> {
    val result = tokenizeSeq3Messages(tag, group.map { Seq3TokenizeInput(it.id.toString(), it.msg) })
    val match = result.match
    if (result.compiled && match != null) {
        return listOf(toMessage(tag, match, group, result.captureValuesByOccurrence, lifelineIdByTag, resolved, entryIndexById, options))
    }
    // The shape-grouped candidates turned out not to share a provable pattern — fall back to one
    // literal message per occurrence rather than dropping any log row. A single-occurrence
    // tokenizeSeq3Messages call always compiles (see that function's own doc), so `!!` here can
    // never actually fail.
    return group.map { entry ->
        val single = tokenizeSeq3Messages(tag, listOf(Seq3TokenizeInput(entry.id.toString(), entry.msg)))
        toMessage(tag, single.match!!, listOf(entry), single.captureValuesByOccurrence, lifelineIdByTag, resolved, entryIndexById, options)
    }
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
): Seq3Message {
    val occurrences = group.map { entry ->
        val millis = parseMillisOfDay(entry.ts)
        Seq3Occurrence(
            entryId = entry.id,
            timestampMillis = millis.takeIf { it != TS_UNKNOWN },
            rawTimestamp = entry.ts,
            pid = entry.pid,
            tid = entry.tid,
            level = entry.level.key,
            text = entry.msg,
            captureValues = captureValuesByOccurrence[entry.id.toString()].orEmpty(),
        )
    }
    return Seq3Message(
        // reassigned once every tag's messages are merged and sorted into log-clock order
        id = "",
        match = match,
        fromLifelineId = lifelineIdByTag.getValue(tag),
        toLifelineId = inferTarget(tag, group, resolved, entryIndexById, lifelineIdByTag, options),
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

private const val TARGET_CONFIDENCE_RATIO = 0.6
private const val MIN_TARGET_EVIDENCE_COUNT = 1

private fun inferTarget(
    tag: String,
    group: List<LogEntry>,
    resolved: List<LogEntry>,
    entryIndexById: Map<Int, Int>,
    lifelineIdByTag: Map<String, String>,
    options: Seq3GenerateOptions,
): String? {
    val candidateCounts = LinkedHashMap<String, Int>()
    var consideredCount = 0
    group.forEach { entry ->
        val index = entryIndexById[entry.id] ?: return@forEach
        val next = resolved.getOrNull(index + 1) ?: return@forEach
        if (next.tag == tag) return@forEach
        val evidenced = (options.threadHandoffEnabled && isThreadHandoff(next, entry)) ||
            (options.correlationTokenEnabled && hasSharedCorrelationToken(next, entry))
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
