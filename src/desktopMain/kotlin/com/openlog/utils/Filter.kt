package com.openlog.utils

import androidx.compose.ui.graphics.Color
import com.openlog.model.*
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
        containsPattern("${entry.tag} ${entry.msg}", filter.excludeKw, filter.excludeKwRegex)) return false
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
                val hay = "${entry.tag} ${entry.msg}"
                containsPattern(hay, filter.kwText, filter.kwRegex)
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

// Complexity is inherent: sequence detection, manual-collapse interleaving, and segment
// iteration are all coupled — splitting them would require passing shared mutable state.
@Suppress("CyclomaticComplexMethod")
fun computeItems(tab: LogTab, applyFilter: Boolean): List<LogItem> {
    val sequences = tab.filter.sequences
    val data = visibleEntries(tab, applyFilter)

    fun sequenceItems(segment: List<LogEntry>): List<LogItem> {
        val seqGroups = if (tab.filter.seqOn && sequences.any { it.enabled })
            computeSeqGroups(segment, sequences)
        else emptyList()

        // Every id a sequence group claims — its own header line plus all children (plain + nested).
        // Used to keep stack-trace folding from re-claiming a line a sequence already owns.
        val seqClaimedIds = buildSet<Int> {
            seqGroups.forEach { g ->
                add(g.rid)
                addAll(g.plain)
                g.nested.forEach { ng -> add(ng.rid); addAll(ng.ch) }
            }
        }

        // Stack-trace folding is always-on, independent of user-defined sequences. Sequence groups
        // take priority on overlap: drop a stack-trace group entirely if any of its lines are
        // already claimed by a sequence, rather than trying to partially split it.
        val stackGroups = computeStackTraceGroups(segment)
            .filter { g -> (g.memberIds + g.rid).none { it in seqClaimedIds } }

        if (seqGroups.isEmpty() && stackGroups.isEmpty()) return segment.map { LogItem.Row(it, 0) }

        val defMap = sequences.associateBy { it.id }
        val seqChildIds = buildSet<Int> {
            seqGroups.forEach { g ->
                addAll(g.plain)
                g.nested.forEach { ng -> add(ng.rid); addAll(ng.ch) }
            }
        }
        val stackChildIds = buildSet<Int> { stackGroups.forEach { addAll(it.memberIds) } }
        val skipIds = seqChildIds + stackChildIds

        val items = mutableListOf<LogItem>()
        for (entry in segment) {
            val sg = seqGroups.find { it.rid == entry.id }
            val stg = if (sg == null) stackGroups.find { it.rid == entry.id } else null
            when {
                sg != null -> {
                    val totalCh = sg.plain.size + sg.nested.sumOf { ng -> 1 + ng.ch.size }
                    val exp = sg.gid in tab.expanded
                    val outerColor = defMap[sg.defId]?.color ?: SEQ_COLORS.first()
                    items += LogItem.SeqHeader(entry, sg.gid, 0, exp, totalCh, outerColor)
                    if (exp) {
                        val plainIds = sg.plain.toSet()
                        val nestedByRoot = sg.nested.associateBy { it.rid }
                        for (inner in segment) {
                            if (inner.id in plainIds) {
                                items += LogItem.Row(inner, 1, outerColor)
                                continue
                            }
                            val ng = nestedByRoot[inner.id] ?: continue
                            val nestedColor = defMap[ng.defId]?.color ?: outerColor
                            val nexp = ng.gid in tab.expanded
                            items += LogItem.SeqHeader(inner, ng.gid, 1, nexp, ng.ch.size, nestedColor)
                            if (nexp) {
                                ng.ch.forEach { id -> tab.rmap[id]?.let { items += LogItem.Row(it, 2, nestedColor) } }
                            }
                        }
                    }
                }

                stg != null -> {
                    val exp = stg.gid in tab.expanded
                    items += LogItem.StackTraceHeader(entry, stg.gid, 0, exp, stg.memberIds.size)
                    if (exp) {
                        stg.memberIds.forEach { id -> tab.rmap[id]?.let { items += LogItem.Row(it, 1) } }
                    }
                }

                entry.id in skipIds -> Unit
                else -> items += LogItem.Row(entry, 0)
            }
        }
        return items
    }

    val manualBlocks = tab.manualBlocks.filter { it.enabled }
    if (manualBlocks.isEmpty()) return sequenceItems(data)

    val indexById = data.withIndex().associate { it.value.id to it.index }

    data class ManualRange(val block: ManualCollapseBlock, val range: IntRange)

    val ranges = manualBlocks.mapNotNull { block ->
        val anchor = indexById[block.anchorId] ?: return@mapNotNull null
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
        val headerEntry = data[indexById[block.anchorId] ?: manual.range.first]
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
