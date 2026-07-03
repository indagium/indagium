package com.openlog.utils

import androidx.compose.ui.graphics.Color
import com.openlog.model.*
import com.openlog.ui.DANGER_RED
import com.openlog.ui.SEQ_COLORS

// Positive message/PID rules and the kwInTag live-search ADD matches on top of the base
// tag/keyword filter rather than replacing it — an entry passes if it satisfies a positive
// selector OR the base tag/keyword filter. The base filter only contributes when it's actually
// configured (non-empty); an unconfigured base filter would otherwise vacuously pass every
// entry, defeating whatever positive selectors are active.
// Negative rules and exclusions always apply regardless.
fun passesFilter(entry: LogEntry, filter: Filter): Boolean {
    val enabledRules = filter.messageRules.filter { it.enabled && it.pattern.isNotBlank() }
    if (!passesExclusions(entry, filter, enabledRules.filter { !it.include })) return false
    val posRules = enabledRules.filter { it.include }
    val hasKwInTag = filter.kwInTag.isNotBlank()
    val hasPosPidTid = filter.pidTidFilter.isNotBlank()
    if (posRules.isNotEmpty() || hasKwInTag || hasPosPidTid) {
        return matchesPositiveSelectors(entry, posRules, hasKwInTag, hasPosPidTid, filter)
    }
    return passesTagOrKeywordFilter(entry, filter)
}

private fun passesExclusions(entry: LogEntry, filter: Filter, negativeRules: List<MessageRule>): Boolean {
    if (entry.level !in filter.levels) return false
    if (entry.tag in filter.excludeTags) return false
    if (filter.excludePkgPrefixes.any { pfx -> tagMatchesPrefix(entry.tag, pfx) }) return false
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
)

private val computeCacheByTab = java.util.concurrent.ConcurrentHashMap<String, TabComputeCache>()

fun invalidateComputeCache(tabId: String) {
    computeCacheByTab.remove("$tabId#true")
    computeCacheByTab.remove("$tabId#false")
}

// Complexity is inherent: sequence detection, manual-collapse interleaving, and segment
// iteration are all coupled — splitting them would require passing shared mutable state.
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

    fun storeCache() {
        computeCacheByTab[cacheKey] = TabComputeCache(
            logData = tab.logData,
            stackGroupsRef = tab.analysis.stackTraceGroups,
            filter = tab.filter,
            visible = data,
            seqGroups = fullSeqGroups,
            filteredStackGroups = fullFilteredStackGroups,
            seqOwnerBySwallowed = fullSeqOwner,
            seqChildBits = fullSeqChildBits,
        )
    }

    fun cachedStackGroupsFor(segment: List<LogEntry>): List<StackTraceGroup> {
        val cached = tab.analysis.stackTraceGroups
        if (cached.isEmpty()) return computeStackTraceGroups(segment)
        // Nothing filtered out and the segment spans the whole tab: cached groups apply as-is.
        if (segment.size == tab.logData.size) return cached
        if (segment === data) fullFilteredStackGroups?.let { return it }
        val segmentIds = idBitSet(segment)
        val result = cached.mapNotNull { group ->
            if (!segmentIds.get(group.rid)) {
                null
            } else {
                val visibleMembers = group.memberIds.filter { segmentIds.get(it) }
                group.copy(memberIds = visibleMembers).takeIf { visibleMembers.isNotEmpty() }
            }
        }
        if (segment === data) fullFilteredStackGroups = result
        return result
    }

    fun seqGroupsFor(segment: List<LogEntry>): List<SeqGroup> {
        if (!(tab.filter.seqOn && sequences.any { it.enabled })) return emptyList()
        if (segment === data) fullSeqGroups?.let { return it }
        val result = computeSeqGroups(segment, sequences)
        if (segment === data) fullSeqGroups = result
        return result
    }

    fun sequenceItems(segment: List<LogEntry>): List<LogItem> {
        val seqGroups = seqGroupsFor(segment)

        // Stack-trace folding is always-on, independent of user-defined sequences. A sequence with
        // no explicit end pattern can swallow everything up to the next start match (or
        // end-of-log) as unstructured "plain" children — including an exception/ANR block that
        // has nothing to do with the sequence. Render it nested one level inside the sequence's
        // plain children *only while that sequence is already expanded* (a nice "this crash
        // happened during X" grouping); otherwise render it as its own independent, always-visible
        // collapsible block at the top level. Either way it's always present in the current item
        // list without needing to expand anything new to reveal it — crash navigation never has
        // to search for or blindly expand a group, which is what made it slow (and occasionally
        // expand the wrong one) on a real log.
        val allStackGroups = cachedStackGroupsFor(segment)
        val cachedOwner = if (segment === data) fullSeqOwner else null
        val seqOwnerGidBySwallowedId = cachedOwner ?: buildMap<Int, String> {
            seqGroups.forEach { sg -> sg.plain.forEach { id -> put(id, sg.gid) } }
        }.also { if (segment === data) fullSeqOwner = it }
        val stackGroups = allStackGroups.filter { g ->
            val ownerGid = seqOwnerGidBySwallowedId[g.rid]
            ownerGid == null || ownerGid !in tab.expanded
        }
        val nestedStackGroupByRid = (allStackGroups - stackGroups.toSet()).associateBy { it.rid }
        val stackClaimedIds = java.util.BitSet().also { bits ->
            allStackGroups.forEach { g ->
                bits.set(g.rid)
                g.memberIds.forEach(bits::set)
            }
        }

        if (seqGroups.isEmpty() && stackGroups.isEmpty()) return segment.map { LogItem.Row(it, 0) }

        val defMap = sequences.associateBy { it.id }
        // The sequence-child half of skipIds is invariant under `expanded` (memoized); only the
        // stack-member half depends on which owners are expanded, and that part is tiny.
        val cachedChildBits = if (segment === data) fullSeqChildBits else null
        val seqChildBits = cachedChildBits ?: java.util.BitSet().also { bits ->
            seqGroups.forEach { g ->
                g.plain.forEach(bits::set)
                g.nested.forEach { ng ->
                    bits.set(ng.rid)
                    ng.ch.forEach(bits::set)
                }
            }
            if (segment === data) fullSeqChildBits = bits
        }
        val skipIds = (seqChildBits.clone() as java.util.BitSet).also { bits ->
            stackGroups.forEach { g -> g.memberIds.forEach(bits::set) }
        }
        val items = ArrayList<LogItem>(segment.size)

        // A "plain" (unstructured) child of a sequence's swallowed range: usually just a bare Row,
        // but if it's the root of a stack-trace group nested inside this (already-expanded)
        // sequence, fold it as a nested StackTraceHeader instead — skipping its other member
        // lines entirely, since they render inside that header's own expansion.
        fun appendPlainChild(inner: LogEntry, outerColor: Color) {
            val nestedStack = nestedStackGroupByRid[inner.id]
            if (nestedStack != null) {
                val nsExp = nestedStack.gid in tab.expanded
                items += LogItem.StackTraceHeader(inner, nestedStack.gid, 1, nsExp, nestedStack.memberIds.size)
                if (nsExp) {
                    nestedStack.memberIds.forEach { id -> tab.rmap[id]?.let { items += LogItem.Row(it, 2, DANGER_RED) } }
                }
            } else if (!stackClaimedIds.get(inner.id)) {
                items += LogItem.Row(inner, 1, outerColor)
            }
        }

        // An explicit nested sub-sequence within an expanded outer sequence's plain range —
        // renders its own header, plus its children if it's also expanded (skipping any that
        // belong to a stack-trace group, which renders independently elsewhere).
        fun appendNestedSubSequence(entry: LogEntry, ng: NestedSeqGroup, outerColor: Color) {
            val nestedColor = defMap[ng.defId]?.color ?: outerColor
            val nexp = ng.gid in tab.expanded
            items += LogItem.SeqHeader(entry, ng.gid, 1, nexp, ng.ch.size, nestedColor)
            if (nexp) {
                ng.ch.forEach { id -> if (!stackClaimedIds.get(id)) tab.rmap[id]?.let { items += LogItem.Row(it, 2, nestedColor) } }
            }
        }

        // Group roots are sorted by rid and the segment's ids ascend, so a pointer walk replaces
        // two hash lookups per entry (tens of millions of them on a large file).
        var seqPtr = 0
        var stackPtr = 0
        for (entry in segment) {
            while (seqPtr < seqGroups.size && seqGroups[seqPtr].rid < entry.id) seqPtr++
            val sg = seqGroups.getOrNull(seqPtr)?.takeIf { it.rid == entry.id }
            val stg = if (sg == null) {
                while (stackPtr < stackGroups.size && stackGroups[stackPtr].rid < entry.id) stackPtr++
                stackGroups.getOrNull(stackPtr)?.takeIf { it.rid == entry.id }
            } else {
                null
            }
            when {
                sg != null -> {
                    val totalCh = sg.plain.size + sg.nested.sumOf { ng -> 1 + ng.ch.size }
                    val exp = sg.gid in tab.expanded
                    val outerColor = defMap[sg.defId]?.color ?: SEQ_COLORS.first()
                    items += LogItem.SeqHeader(entry, sg.gid, 0, exp, totalCh, outerColor)
                    if (exp) {
                        val plainIds = java.util.BitSet().also { bits -> sg.plain.forEach(bits::set) }
                        val nestedByRoot = sg.nested.associateBy { it.rid }
                        for (inner in segment) {
                            if (plainIds.get(inner.id)) {
                                appendPlainChild(inner, outerColor)
                                continue
                            }
                            val ng = nestedByRoot[inner.id] ?: continue
                            appendNestedSubSequence(inner, ng, outerColor)
                        }
                    }
                }

                stg != null -> {
                    val exp = stg.gid in tab.expanded
                    items += LogItem.StackTraceHeader(entry, stg.gid, 0, exp, stg.memberIds.size)
                    if (exp) {
                        stg.memberIds.forEach { id -> tab.rmap[id]?.let { items += LogItem.Row(it, 1, DANGER_RED) } }
                    }
                }

                skipIds.get(entry.id) -> Unit
                else -> items += LogItem.Row(entry, 0)
            }
        }
        return items
    }

    val manualBlocks = tab.manualBlocks.filter { it.enabled }
    if (manualBlocks.isEmpty()) return sequenceItems(data).also { storeCache() }

    // Ids ascend within data, so anchor lookup is a binary search instead of a boxed id->index map.
    val dataIds = IntArray(data.size) { data[it].id }

    fun indexOfId(id: Int): Int? = java.util.Arrays.binarySearch(dataIds, id).takeIf { it >= 0 }

    data class ManualRange(val block: ManualCollapseBlock, val range: IntRange)

    val ranges = manualBlocks.mapNotNull { block ->
        val anchor = indexOfId(block.anchorId) ?: return@mapNotNull null
        val range = when (block.direction) {
            ManualCollapseDirection.TO_START -> 0..anchor
            ManualCollapseDirection.TO_END -> anchor..data.lastIndex
        }
        ManualRange(block, range)
    }.sortedWith(compareBy<ManualRange> { it.range.first }.thenByDescending { it.range.last })

    val result = mutableListOf<LogItem>()
    val segment = mutableListOf<LogEntry>()

    fun flushSegment() {
        if (segment.isNotEmpty()) {
            result += sequenceItems(segment.toList())
            segment.clear()
        }
    }

    fun nestedManualItems(items: List<LogItem>, manualColor: Color): List<LogItem> =
        items.map { item ->
            when (item) {
                is LogItem.Row -> item.copy(
                    indent = item.indent + 1,
                    groupColor = item.groupColor ?: manualColor,
                )

                is LogItem.SeqHeader -> item.copy(indent = item.indent + 1)
                is LogItem.StackTraceHeader -> item.copy(indent = item.indent + 1)
                is LogItem.ManualHeader -> item
            }
        }

    var i = 0
    while (i < data.size) {
        val manual = ranges.firstOrNull { it.range.first == i }
        if (manual == null) {
            segment += data[i]
            i += 1
            continue
        }
        flushSegment()
        val block = manual.block
        val headerEntry = data[indexOfId(block.anchorId) ?: manual.range.first]
        val expanded = block.id in tab.expanded
        result += LogItem.ManualHeader(
            headerEntry,
            block.id,
            block.direction,
            expanded,
            manual.range.count(),
            block.color
        )
        if (expanded) {
            val expandedItems = sequenceItems(manual.range.map { data[it] })
                .filterNot { item -> item is LogItem.Row && item.entry.id == block.anchorId }
            result += nestedManualItems(expandedItems, block.color)
        }
        i = manual.range.last + 1
    }
    flushSegment()
    storeCache()
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
