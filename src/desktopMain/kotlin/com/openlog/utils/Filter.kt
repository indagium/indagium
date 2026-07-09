package com.openlog.utils

import androidx.compose.ui.graphics.Color
import com.openlog.model.*
import com.openlog.ui.DANGER_RED
import com.openlog.ui.SEQ_COLORS

// Tags-mode message/PID rules and the kwInTag live-search add matches on top of the base tag
// filter rather than replacing it — an entry passes if it satisfies a positive selector OR the
// base tag filter. Regex/Keyword mode is intentionally just kwText + kwRegex; persisted
// KEYWORD-mode rules are ignored so hidden rules cannot silently affect results.
// Negative rules and exclusions apply only when their owning feature is active.
fun passesFilter(entry: LogEntry, filter: Filter): Boolean {
    val enabledRules = if (filter.mode == FilterMode.TAGS) {
        filter.messageRules.filter { it.enabled && it.pattern.isNotBlank() && it.mode == FilterMode.TAGS }
    } else {
        emptyList()
    }
    if (!passesExclusions(entry, filter, enabledRules.filter { !it.include })) return false
    val posRules = enabledRules.filter { it.include }
    val hasKwInTag = filter.mode == FilterMode.TAGS && filter.kwInTag.isNotBlank()
    val hasPosPidTid = filter.pidTidFilter.isNotBlank()
    if (posRules.isNotEmpty() || hasKwInTag || hasPosPidTid) {
        return matchesPositiveSelectors(entry, posRules, hasKwInTag, hasPosPidTid, filter)
    }
    return passesTagOrKeywordFilter(entry, filter)
}

private fun passesExclusions(entry: LogEntry, filter: Filter, negativeRules: List<MessageRule>): Boolean {
    if (entry.level !in filter.levels) return false
    // Tag/package exclusion is a Tags-mode-flavored concept — kept out of Regex/Keyword mode so
    // it can't silently narrow results there, matching the same independence as message rules.
    if (filter.mode == FilterMode.TAGS) {
        if (entry.tag in filter.excludeTags) return false
        if (filter.excludePkgPrefixes.any { pfx -> tagMatchesPrefix(entry.tag, pfx) }) return false
    }
    if (filter.excludeKw.isNotBlank() &&
        tagMsgContainsPattern(entry.tag, entry.msg, filter.excludeKw, filter.excludeKwRegex)) return false
    return negativeRules.none { rule -> ruleScopeMatches(entry, rule) && matchesRule(entry, rule) }
}

private fun matchesPositiveSelectors(
    entry: LogEntry,
    posRules: List<MessageRule>,
    hasKwInTag: Boolean,
    hasPosPidTid: Boolean,
    filter: Filter,
): Boolean {
    // ruleScopeMatches is a no-op (always true) for unscoped rules, so this covers both.
    if (posRules.any { rule -> ruleScopeMatches(entry, rule) && matchesRule(entry, rule) }) return true
    if (hasKwInTag && containsPattern(entry.msg, filter.kwInTag, filter.kwInTagRegex)) return true
    if (hasPosPidTid && matchesPidTidFilter(entry, filter.pidTidFilter)) return true
    return hasActiveBaseFilter(filter) && passesTagOrKeywordFilter(entry, filter)
}

private fun hasActiveBaseFilter(filter: Filter): Boolean = when (filter.mode) {
    FilterMode.TAGS -> filter.activeTags.isNotEmpty() || filter.pkgPrefixes.isNotEmpty()
    FilterMode.KEYWORD -> filter.kwText.isNotBlank()
}

private fun matchesPidTidFilter(entry: LogEntry, pidTidFilter: String): Boolean {
    val tokens = pidTidFilter.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    return tokens.any { it == entry.pid.toString() || it == entry.tid.toString() }
}

private fun passesTagOrKeywordFilter(entry: LogEntry, filter: Filter): Boolean =
    when (filter.mode) {
        FilterMode.TAGS -> {
            if (filter.activeTags.isEmpty() && filter.pkgPrefixes.isEmpty()) {
                true
            } else {
                val selectedExactTagPass = entry.tag in filter.activeTags
                if (selectedExactTagPass) {
                    true
                } else {
                    filter.pkgPrefixes
                        .filter { pfx -> tagMatchesPrefix(entry.tag, pfx) }
                        .any { pfx ->
                            val scopedActiveTags = filter.activeTags.filter { tag -> tagMatchesPrefix(tag, pfx) }
                            scopedActiveTags.isEmpty()
                        }
                }
            }
        }

        FilterMode.KEYWORD -> {
            if (filter.kwText.isBlank()) {
                true
            } else {
                tagMsgContainsPattern(entry.tag, entry.msg, filter.kwText, filter.kwRegex)
            }
        }
    }

private fun tagMatchesPrefix(tag: String, prefix: String): Boolean =
    tag == prefix || tag.startsWith("$prefix.")

private fun matchesRule(entry: LogEntry, rule: MessageRule): Boolean = when (rule.target) {
    RuleTarget.PID_TID -> {
        val tokens = rule.pattern.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        tokens.any { it == entry.pid.toString() || it == entry.tid.toString() }
    }

    RuleTarget.MESSAGE -> rulePatternMatches(entry, rule)
}

private fun ruleScopeMatches(entry: LogEntry, rule: MessageRule): Boolean {
    val exact = rule.tag?.takeIf { it.isNotBlank() }
    val prefix = rule.packagePrefix?.takeIf { it.isNotBlank() }
    if (exact != null && entry.tag != exact) return false
    if (prefix != null && entry.tag != prefix && !entry.tag.startsWith("$prefix.")) return false
    return true
}

private fun rulePatternMatches(entry: LogEntry, rule: MessageRule): Boolean =
    containsPattern(entry.msg, rule.pattern, rule.regex)

// Single source of truth for "what counts as currently visible" — used by both computeItems()
// (applyFilter = true, the normal rendering path) and log export, so a filtered export always
// matches exactly what computeItems() would show before any collapse/expand folding.
fun visibleEntries(tab: LogTab, applyFilter: Boolean = true): List<LogEntry> =
    if (applyFilter) tab.logData.filter { passesFilter(it, tab.filter) } else tab.logData

// Ids are strictly increasing within a tab (parser, merge, and tailing all guarantee it) and
// dense enough that a BitSet id-set is ~1 bit/entry — the boxed HashSet<Int> equivalents these
// replace cost ~50 bytes/entry and dominated computeItems' GC churn on multi-million-line files.
private fun idBitSet(entries: List<LogEntry>): java.util.BitSet {
    val bits = java.util.BitSet((entries.lastOrNull()?.id ?: 0) + 1)
    entries.forEach { bits.set(it.id) }
    return bits
}

// Memo of the filter/sequence work from a tab's last computeItems call. An expand/collapse
// click changes only tab.expanded — but used to re-run the full-file filter pass (~4s at 10M
// lines with a keyword filter) and the sequence scan (seconds more with sequence defs enabled)
// just to splice different children into the item list. Everything here is invariant under
// `expanded`, so those clicks now reuse it and only rebuild the item list itself.
// Keyed per (tab, applyFilter) since the split "Original" panel computes applyFilter=false
// alongside the main panel's true. Invalidated by identity checks (logData/analysis are
// replaced wholesale on reload/tailing) plus Filter equality, and dropped on tab close.
private class TabComputeCache(
    val logData: List<LogEntry>,
    val stackGroupsRef: List<StackTraceGroup>,
    val filter: Filter,
    val visible: List<LogEntry>,
    val seqGroups: List<SeqGroup>?,
    val filteredStackGroups: List<StackTraceGroup>?,
    // Derived from seqGroups only, but linear in total swallowed lines — a sequence def without
    // an end pattern can swallow most of a 10M-line file, making these worth memoizing too.
    val seqOwnerBySwallowed: Map<Int, String>?,
    val seqChildBits: java.util.BitSet?,
    // The full result of the last compute, kept so a single stack-group toggle can splice member
    // rows in/out instead of re-materializing millions of LogItems (see spliceStackToggle).
    val items: List<LogItem>?,
    val expanded: Set<String>,
    val manualBlocks: List<ManualCollapseBlock>,
)

private val computeCacheByTab = java.util.concurrent.ConcurrentHashMap<String, TabComputeCache>()

fun invalidateComputeCache(tabId: String) {
    computeCacheByTab.remove("$tabId#true")
    computeCacheByTab.remove("$tabId#false")
}

// Fast path for the single most common expand/collapse: toggling one stack-trace ("crash")
// block. Its rendered footprint is strictly local — the header flips its `expanded` flag and the
// member rows appear/disappear immediately after it; nothing else in the item list changes
// (top-level filtering by owner-sequence expansion, skipIds, and nested placement all depend on
// sequence/manual gids, never on a stack gid). So instead of re-materializing millions of
// LogItems (~0.5s at 10M rows, the remaining expand latency after memoization), copy the cached
// list and splice. Any condition this can't prove — different toggle kind, multi-gid change,
// header not currently visible, unexpected neighborhood — returns null and the caller does the
// full rebuild, so the fallback is exactly the previous behavior.
@Suppress("ReturnCount")
private fun spliceStackToggle(tab: LogTab, prior: TabComputeCache): List<LogItem>? {
    val priorItems = prior.items ?: return null
    if (prior.manualBlocks != tab.manualBlocks) return null
    val added = tab.expanded - prior.expanded
    val removed = prior.expanded - tab.expanded
    if (added.size + removed.size != 1) return null
    val gid = added.firstOrNull() ?: removed.first()
    val expanding = added.isNotEmpty()
    val groups = prior.filteredStackGroups ?: tab.analysis.stackTraceGroups
    val group = groups.firstOrNull { it.gid == gid } ?: return null
    val idx = priorItems.indexOfFirst { it is LogItem.StackTraceHeader && it.gid == gid }
    if (idx < 0) return null
    val header = priorItems[idx] as LogItem.StackTraceHeader
    if (header.expanded == expanding) return null

    val result = ArrayList<LogItem>(priorItems.size + if (expanding) group.memberIds.size else 0)
    result.addAll(priorItems.subList(0, idx))
    result.add(header.copy(expanded = expanding))
    if (expanding) {
        group.memberIds.forEach { id ->
            tab.rmap[id]?.let { result.add(LogItem.Row(it, header.indent + 1, DANGER_RED)) }
        }
        result.addAll(priorItems.subList(idx + 1, priorItems.size))
    } else {
        val end = idx + 1 + group.memberIds.size
        if (end > priorItems.size) return null
        for (k in idx + 1 until end) if (priorItems[k] !is LogItem.Row) return null
        result.addAll(priorItems.subList(end, priorItems.size))
    }
    return result
}

// A child container to render within some range of `data`: either a top-level auto-detected
// sequence, a nested sub-sequence within one, or a user-created manual collapse range. All three
// resolve to an index range into `data`, which is what makes it possible to nest a manual block
// inside a sequence (or vice versa) without ever re-running sequence detection on a sub-list —
// see the long comment above the hosting-resolution block in computeItems for why that matters.
private sealed class ChildRef {
    abstract val start: Int

    // Upper bound used both to advance past this child in the parent's pointer walk and, when
    // this child is expanded, as the jump target after rendering it in full. For a manual range
    // that hosts a "crossing" sequence extending past its own declared end, this is that
    // sequence's endExclusive, not the manual block's own range — see ManualC.declaredEnd for the
    // manual block's own (unextended) bound, used when it's collapsed rather than expanded.
    abstract val end: Int

    data class SeqC(val sg: SeqGroup, override val start: Int) : ChildRef() {
        override val end get() = sg.endExclusive
    }

    data class NestedC(val ng: NestedSeqGroup, override val start: Int) : ChildRef() {
        override val end get() = ng.endExclusive
    }

    data class ManualC(val mr: ManualRange, val declaredEnd: Int, override val end: Int) : ChildRef() {
        override val start get() = mr.range.first
    }
}

private data class ManualRange(val block: ManualCollapseBlock, val range: IntRange)

// Reproduces, exactly, the selection rule the old per-index walk used: scanning left to right,
// the first (widest, per the sort below) range starting at each index wins; any other range whose
// start falls inside an already-selected range is silently dropped. `ranges` must already be
// sorted by `range.first` ascending, `range.last` descending, so the first entry recorded per
// start index in `byStart` is the widest one — matching the old `firstOrNull` tie-break. This is a
// pre-existing, out-of-scope limitation (overlapping manual blocks aren't reconciled) — preserved
// unchanged, just computed in O(n + m) instead of the old O(n * m).
private fun selectTopLevelManualRanges(dataSize: Int, ranges: List<ManualRange>): List<ManualRange> {
    val byStart = HashMap<Int, ManualRange>()
    for (r in ranges) byStart.putIfAbsent(r.range.first, r)
    val result = mutableListOf<ManualRange>()
    var i = 0
    while (i < dataSize) {
        val r = byStart[i]
        if (r == null) {
            i += 1
        } else { result += r; i = r.range.last + 1 }
    }
    return result
}

// Complexity is inherent: sequence detection, manual-collapse interleaving, and recursive
// container rendering are all coupled — splitting them would require passing shared mutable state.
@Suppress("CyclomaticComplexMethod", "LongMethod")
fun computeItems(tab: LogTab, applyFilter: Boolean): List<LogItem> {
    val sequences = tab.filter.sequences
    val cacheKey = "${tab.id}#$applyFilter"
    val prior = computeCacheByTab[cacheKey]?.takeIf {
        it.logData === tab.logData &&
            it.stackGroupsRef === tab.analysis.stackTraceGroups &&
            it.filter == tab.filter
    }
    val data = prior?.visible ?: visibleEntries(tab, applyFilter)
    var fullSeqGroups: List<SeqGroup>? = prior?.seqGroups
    var fullFilteredStackGroups: List<StackTraceGroup>? = prior?.filteredStackGroups
    var fullSeqOwner: Map<Int, String>? = prior?.seqOwnerBySwallowed
    var fullSeqChildBits: java.util.BitSet? = prior?.seqChildBits

    fun storeCache(items: List<LogItem>) {
        computeCacheByTab[cacheKey] = TabComputeCache(
            logData = tab.logData,
            stackGroupsRef = tab.analysis.stackTraceGroups,
            filter = tab.filter,
            visible = data,
            seqGroups = fullSeqGroups,
            filteredStackGroups = fullFilteredStackGroups,
            seqOwnerBySwallowed = fullSeqOwner,
            seqChildBits = fullSeqChildBits,
            items = items,
            expanded = tab.expanded,
            manualBlocks = tab.manualBlocks,
        )
    }

    if (prior != null) {
        spliceStackToggle(tab, prior)?.let { spliced ->
            storeCache(spliced)
            return spliced
        }
    }

    // Sequence groups are always computed exactly once, against the full filtered `data` — never
    // against a manual-collapse sub-range. A manual block's boundary must never truncate or split
    // an auto-detected sequence that spans across it; manual-block interleaving is handled purely
    // as a rendering/nesting concern below, layered on top of this single ground-truth pass.
    val seqGroups: List<SeqGroup> = if (tab.filter.seqOn && sequences.any { it.enabled }) {
        fullSeqGroups ?: computeSeqGroups(data, sequences).also { fullSeqGroups = it }
    } else {
        emptyList()
    }

    // Stack-trace folding is always-on, independent of user-defined sequences and of manual
    // blocks. Also always computed against the full `data` now, for the same reason as seqGroups
    // above — this incidentally fixes the same class of truncation bug for a stack trace that
    // straddles a manual-block boundary.
    val allStackGroups: List<StackTraceGroup> = run {
        val cached = tab.analysis.stackTraceGroups
        when {
            // Analysis still computing in the background after a load: render unfolded rather
            // than blocking this compute on a full multi-second stack-trace scan. When the
            // analysis lands, tab.analysis is replaced and the item list recomputes with folding.
            tab.analysis.pending -> emptyList()
            cached.isEmpty() -> computeStackTraceGroups(data)
            data.size == tab.logData.size -> cached
            else -> fullFilteredStackGroups ?: run {
                val dataIdBits = idBitSet(data)
                cached.mapNotNull { group ->
                    if (!dataIdBits.get(group.rid)) {
                        null
                    } else {
                        val visibleMembers = group.memberIds.filter { dataIdBits.get(it) }
                        group.copy(memberIds = visibleMembers).takeIf { visibleMembers.isNotEmpty() }
                    }
                }.also { fullFilteredStackGroups = it }
            }
        }
    }

    val manualBlocksEnabled = tab.manualBlocks.filter { it.enabled }
    if (seqGroups.isEmpty() && allStackGroups.isEmpty() && manualBlocksEnabled.isEmpty()) {
        return data.map { LogItem.Row(it, 0) }.also { storeCache(it) }
    }

    val defMap = sequences.associateBy { it.id }

    // A sequence with no explicit end pattern can swallow everything up to the next start match
    // (or end-of-log) as unstructured "plain" children — including an exception/ANR block that has
    // nothing to do with the sequence. Render it nested one level inside the sequence's plain
    // children *only while that sequence is already expanded* (a nice "this crash happened during
    // X" grouping); otherwise render it as its own independent, always-visible collapsible block —
    // crash navigation never has to search for or blindly expand a group to find it.
    val seqOwnerGidBySwallowedId = fullSeqOwner ?: buildMap<Int, String> {
        seqGroups.forEach { sg -> sg.plain.forEach { id -> put(id, sg.gid) } }
    }.also { fullSeqOwner = it }
    val stackGroups = allStackGroups.filter { g ->
        val ownerGid = seqOwnerGidBySwallowedId[g.rid]
        ownerGid == null || ownerGid !in tab.expanded
    }
    val stackGroupByRid = stackGroups.associateBy { it.rid }
    val nestedStackGroupByRid = (allStackGroups - stackGroups.toSet()).associateBy { it.rid }
    val stackClaimedIds = java.util.BitSet().also { bits ->
        allStackGroups.forEach { g ->
            bits.set(g.rid)
            g.memberIds.forEach(bits::set)
        }
    }

    // Kept in the memoization cache for TabComputeCache's shape/downstream tooling, though the
    // recursive renderer below no longer needs a global "is this id swallowed by some sequence"
    // bitset — coverage is resolved per recursion level instead (see renderRange), which correctly
    // distinguishes "covered by the sequence I'm currently rendering" from "covered by some other
    // sequence entirely," something a single global bitset could not.
    if (fullSeqChildBits == null) {
        fullSeqChildBits = java.util.BitSet().also { bits ->
            seqGroups.forEach { g ->
                g.plain.forEach(bits::set)
                g.nested.forEach { ng ->
                    bits.set(ng.rid)
                    ng.ch.forEach(bits::set)
                }
            }
        }
    }

    // Ids ascend within data, so id->index lookup is a binary search instead of a boxed map.
    val dataIds = IntArray(data.size) { data[it].id }

    fun indexOfId(id: Int): Int? = java.util.Arrays.binarySearch(dataIds, id).takeIf { it >= 0 }

    fun rootIdxOf(rid: Int): Int = indexOfId(rid) ?: -1

    val allManualRanges = manualBlocksEnabled.mapNotNull { block ->
        val anchor = indexOfId(block.anchorId) ?: return@mapNotNull null
        val range = when (block.direction) {
            ManualCollapseDirection.TO_START -> 0..anchor
            ManualCollapseDirection.TO_END -> anchor..data.lastIndex
            ManualCollapseDirection.RANGE -> {
                val end = block.endId?.let(::indexOfId) ?: return@mapNotNull null
                minOf(anchor, end)..maxOf(anchor, end)
            }
        }
        ManualRange(block, range)
    }.sortedWith(compareBy<ManualRange> { it.range.first }.thenByDescending { it.range.last })

    val topLevelManualCandidates = selectTopLevelManualRanges(data.size, allManualRanges)

    // ── Resolve sequence-vs-manual-block hosting ──────────────────────────────────────────────
    // Top-level SeqGroups never contain one another (SeqComputer only exposes roots at this
    // level), and topLevelManualCandidates never overlap each other (by construction above) — so
    // the only containment/crossing relationships left to resolve are sequence-vs-manual pairs, at
    // two possible depths: directly under a top-level SeqGroup's own plain area, or one level
    // deeper under one of its NestedSeqGroups. On a straddling ("crossing") pair — neither fully
    // contains the other — whichever starts first hosts the other's full extent, nested one level
    // in even past the host's own declared end; on an exact range tie, the manual block hosts (a
    // manual block deliberately wrapping a whole sequence reads as "sequence lives inside my
    // selection," matching pre-existing behavior for that case). This never changes either side's
    // own reported header count, it only changes where its content renders.
    val seqHostsManualDirect = HashMap<String, MutableList<ManualRange>>()
    val nestedHostsManual = HashMap<String, MutableList<ManualRange>>()
    val manualHostsSeq = HashMap<String, MutableList<SeqGroup>>()
    val seqHostedByManualGid = HashSet<String>()
    val manualHostedGid = HashSet<String>()

    for (m in topLevelManualCandidates) {
        val m0 = m.range.first
        val m1 = m.range.last + 1
        for (sg in seqGroups) {
            val s0 = rootIdxOf(sg.rid)
            val s1 = sg.endExclusive
            if (s1 <= m0 || m1 <= s0) continue
            when {
                m0 <= s0 && s1 <= m1 -> {
                    manualHostsSeq.getOrPut(m.block.id) { mutableListOf() } += sg
                    seqHostedByManualGid += sg.gid
                }

                s0 <= m0 && m1 <= s1 -> {
                    val ng = sg.nested.firstOrNull { n -> val n0 = rootIdxOf(n.rid); n0 <= m0 && m1 <= n.endExclusive }
                    if (ng != null) nestedHostsManual.getOrPut(ng.gid) { mutableListOf() } += m
                    else seqHostsManualDirect.getOrPut(sg.gid) { mutableListOf() } += m
                    manualHostedGid += m.block.id
                }

                m0 < s0 -> {
                    manualHostsSeq.getOrPut(m.block.id) { mutableListOf() } += sg
                    seqHostedByManualGid += sg.gid
                }

                else -> {
                    seqHostsManualDirect.getOrPut(sg.gid) { mutableListOf() } += m
                    manualHostedGid += m.block.id
                }
            }
        }
    }

    val topLevelManual = topLevelManualCandidates.filterNot { it.block.id in manualHostedGid }
    val topLevelSeqGroups = seqGroups.filterNot { it.gid in seqHostedByManualGid }

    fun seqEffectiveEnd(sg: SeqGroup): Int =
        maxOf(sg.endExclusive, seqHostsManualDirect[sg.gid]?.maxOfOrNull { it.range.last + 1 } ?: 0)

    fun manualEffectiveEnd(m: ManualRange): Int =
        maxOf(m.range.last + 1, manualHostsSeq[m.block.id]?.maxOfOrNull { it.endExclusive } ?: 0)

    // ── Unified recursive renderer ────────────────────────────────────────────────────────────
    // Walks index range [lo, hi) into `data`, rendering `children` (sorted by start, non-
    // overlapping at this level) wherever their start position falls, and plain/stack-header rows
    // everywhere else. `hi` is a soft bound: if an expanded child's own true end extends past it
    // (the crossing case resolved above), that child is still rendered in full via recursion and
    // the cursor simply jumps to its true end, which is >= hi — the `while (idx < hi)` loop then
    // exits on its own next check, no special-casing needed.
    //
    // A collapsed SeqHeader/NestedSeqHeader still walks its interior position-by-position (does
    // NOT jump) so an escaped stack-trace header inside it can still surface, matching pre-existing
    // sequence behavior. A collapsed ManualHeader, by contrast, always jumps straight to its own
    // declared end — manual blocks are a deliberate, harder collapse than sequences and already
    // fully hid their interior (including any escaped stack trace within it) before this change;
    // preserved as-is rather than changed as a side effect of this fix.
    fun renderRange(lo: Int, hi: Int, indent: Int, ambientColor: Color?, children: List<ChildRef>): List<LogItem> {
        val items = ArrayList<LogItem>(hi - lo)
        var childPtr = 0
        var idx = lo
        while (idx < hi) {
            while (childPtr < children.size && children[childPtr].end <= idx) childPtr++
            val child = children.getOrNull(childPtr)?.takeIf { it.start == idx }
            val entry = data[idx]
            when {
                child is ChildRef.SeqC -> {
                    val sg = child.sg
                    val exp = sg.gid in tab.expanded
                    val totalCh = sg.plain.size + sg.nested.sumOf { ng -> 1 + ng.ch.size }
                    val color = defMap[sg.defId]?.color ?: SEQ_COLORS.first()
                    items += LogItem.SeqHeader(entry, sg.gid, indent, exp, totalCh, color)
                    if (exp) {
                        val kids = (
                            sg.nested.map { ng -> ChildRef.NestedC(ng, rootIdxOf(ng.rid)) } +
                                (seqHostsManualDirect[sg.gid].orEmpty()).map { m ->
                                    ChildRef.ManualC(m, m.range.last + 1, manualEffectiveEnd(m))
                                }
                        ).sortedBy { it.start }
                        items += renderRange(idx + 1, sg.endExclusive, indent + 1, color, kids)
                        idx = seqEffectiveEnd(sg)
                    } else {
                        idx += 1
                    }
                }

                child is ChildRef.NestedC -> {
                    val ng = child.ng
                    val exp = ng.gid in tab.expanded
                    val color = defMap[ng.defId]?.color ?: ambientColor ?: SEQ_COLORS.first()
                    items += LogItem.SeqHeader(entry, ng.gid, indent, exp, ng.ch.size, color)
                    if (exp) {
                        val kids = nestedHostsManual[ng.gid].orEmpty()
                            .map { m -> ChildRef.ManualC(m, m.range.last + 1, m.range.last + 1) }
                            .sortedBy { it.start }
                        items += renderRange(idx + 1, ng.endExclusive, indent + 1, color, kids)
                        idx = ng.endExclusive
                    } else {
                        idx += 1
                    }
                }

                child is ChildRef.ManualC -> {
                    val mr = child.mr
                    val block = mr.block
                    val exp = block.id in tab.expanded
                    // The anchor entry (what the header displays) isn't necessarily at
                    // range.first — TO_END and some RANGE blocks anchor at the other end.
                    val headerEntry = indexOfId(block.anchorId)?.let { data[it] } ?: entry
                    items += LogItem.ManualHeader(headerEntry, block.id, block.direction, exp, mr.range.count(), block.color)
                    if (exp) {
                        val kids = manualHostsSeq[block.id].orEmpty()
                            .map { sg -> ChildRef.SeqC(sg, rootIdxOf(sg.rid)) }
                            .sortedBy { it.start }
                        // Render the manual block's full range (the anchor entry may sit at
                        // either end of it — TO_START/TO_END/RANGE all place it differently) and
                        // filter the anchor's own row out afterward, matching the header, which
                        // already displays that entry.
                        val inner = renderRange(mr.range.first, mr.range.last + 1, indent + 1, block.color, kids)
                        items += inner.filterNot { it is LogItem.Row && it.entry.id == block.anchorId }
                        idx = child.declaredEnd
                    } else {
                        idx = child.declaredEnd
                    }
                }

                stackGroupByRid[entry.id] != null -> {
                    val stg = stackGroupByRid.getValue(entry.id)
                    val exp = stg.gid in tab.expanded
                    items += LogItem.StackTraceHeader(entry, stg.gid, indent, exp, stg.memberIds.size)
                    if (exp) {
                        stg.memberIds.forEach { id -> tab.rmap[id]?.let { items += LogItem.Row(it, indent + 1, DANGER_RED) } }
                    }
                    idx += 1
                }

                nestedStackGroupByRid[entry.id] != null -> {
                    val stg = nestedStackGroupByRid.getValue(entry.id)
                    val exp = stg.gid in tab.expanded
                    items += LogItem.StackTraceHeader(entry, stg.gid, indent, exp, stg.memberIds.size)
                    if (exp) {
                        stg.memberIds.forEach { id -> tab.rmap[id]?.let { items += LogItem.Row(it, indent + 1, DANGER_RED) } }
                    }
                    idx += 1
                }

                childPtr < children.size && idx >= children[childPtr].start && idx < children[childPtr].end -> {
                    idx += 1 // covered by the current child's interior (collapsed, not yet reached its end)
                }

                stackClaimedIds.get(entry.id) -> idx += 1 // stack-trace member row, shown only under its header

                else -> {
                    items += LogItem.Row(entry, indent, ambientColor)
                    idx += 1
                }
            }
        }
        return items
    }

    val topChildren = (
        topLevelSeqGroups.map { sg -> ChildRef.SeqC(sg, rootIdxOf(sg.rid)) } +
            topLevelManual.map { m -> ChildRef.ManualC(m, m.range.last + 1, manualEffectiveEnd(m)) }
    ).sortedBy { it.start }

    val result = renderRange(0, data.size, indent = 0, ambientColor = null, children = topChildren)
    storeCache(result)
    return result
}

private fun sourcePrefixLabel(settings: AppSettings): String =
    settings.annotationPrefixLabel.trim().ifBlank { "From" }

fun buildMd(tab: LogTab, settings: AppSettings = AppSettings()): String = buildString {
    if (tab.annotations.prefix.isNotBlank()) {
        appendLine(tab.annotations.prefix); appendLine()
    }
    var blockNumber = 1
    for (block in tab.annotations.blocks) {
        when (block) {
            is AnnBlock.Note -> {
                if (block.text.isNotBlank()) {
                    if (settings.numberAnnotationBlocks) append("${blockNumber++}. ")
                    appendLine(block.text)
                    appendLine()
                }
            }

            is AnnBlock.LogRef -> {
                if (settings.numberAnnotationBlocks) append("${blockNumber++}. ")
                if (block.caption.isNotBlank()) {
                    appendLine(block.caption); appendLine()
                } else if (settings.numberAnnotationBlocks) {
                    appendLine()
                }
                if (block.sourceFilename != null) appendLine("${sourcePrefixLabel(settings)} ${block.sourceFilename}")
                val rows = block.sourceEntries ?: block.logIds.mapNotNull { tab.rmap[it] }
                when (settings.annotationLogBlockStyle) {
                    AnnotationLogBlockStyle.INDENTED ->
                        rows.forEach { r -> appendLine("    ${r.ts}  ${r.level.key}/${r.tag}  ${r.msg}") }

                    AnnotationLogBlockStyle.JIRA_JAVA -> {
                        appendLine("{code:java}")
                        rows.forEach { r -> appendLine("${r.ts}  ${r.level.key}/${r.tag}  ${r.msg}") }
                        appendLine("{code}")
                    }
                }
                appendLine()
            }
        }
    }
    if (tab.annotations.suffix.isNotBlank()) {
        appendLine("---"); appendLine(); append(tab.annotations.suffix)
    }
}
