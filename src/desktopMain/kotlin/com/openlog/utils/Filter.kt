package com.openlog.utils

import androidx.compose.ui.graphics.Color
import com.openlog.model.*
import com.openlog.ui.SEQ_COLORS

// When positive message/PID rules (or the kwInTag live-search) are active they act as the
// sole positive filter — every matching entry is shown from any tag, overriding the tag filter.
// The tag/keyword filter only applies when NO positive rules are set.
// Negative rules and exclusions always apply regardless.
fun passesFilter(entry: LogEntry, filter: Filter): Boolean {
    // ── Mandatory exclusions ─────────────────────────────────────────
    if (entry.level !in filter.levels) return false
    if (entry.tag in filter.excludeTags) return false
    if (filter.excludeKw.isNotBlank()) {
        val hay = "${entry.tag} ${entry.msg}"
        val excl = if (filter.excludeKwRegex)
            runCatching { Regex(filter.excludeKw, RegexOption.IGNORE_CASE).containsMatchIn(hay) }.getOrElse { false }
        else hay.contains(filter.excludeKw, ignoreCase = true)
        if (excl) return false
    }
    val enabledRules = filter.messageRules.filter { it.enabled && it.pattern.isNotBlank() }
    for (rule in enabledRules.filter { !it.include }) {
        if (ruleScopeMatches(entry, rule) && matchesRule(entry, rule)) return false
    }

    // ── Positive rules override tag filter ───────────────────────────
    val posRules = enabledRules.filter { it.include }
    val hasKwInTag = filter.kwInTag.isNotBlank()
    val hasPosPidTid = filter.pidTidFilter.isNotBlank()
    if (posRules.isNotEmpty() || hasKwInTag || hasPosPidTid) {
        if (posRules.any { rule -> ruleScopeMatches(entry, rule) && matchesRule(entry, rule) }) return true
        if (hasKwInTag) {
            val kwPass = if (filter.kwInTagRegex)
                runCatching { Regex(filter.kwInTag, RegexOption.IGNORE_CASE).containsMatchIn(entry.msg) }.getOrElse { false }
            else entry.msg.contains(filter.kwInTag, ignoreCase = true)
            if (kwPass) return true
        }
        if (hasPosPidTid) {
            val tokens = filter.pidTidFilter.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
            if (tokens.any { it == entry.pid.toString() || it == entry.tid.toString() }) return true
        }
        return false
    }

    // ── No positive rules: apply tag / keyword filter ────────────────
    return when (filter.mode) {
        FilterMode.TAGS -> {
            if (filter.activeTags.isEmpty() && filter.pkgPrefixes.isEmpty()) true
            else {
                val prefixPass = filter.pkgPrefixes.isEmpty() ||
                    filter.pkgPrefixes.any { pfx -> entry.tag == pfx || entry.tag.startsWith("$pfx.") }
                val activeTagPass = filter.activeTags.isEmpty() || entry.tag in filter.activeTags
                prefixPass && activeTagPass
            }
        }
        FilterMode.KEYWORD -> {
            if (filter.kwText.isBlank()) true
            else {
                val hay = "${entry.tag} ${entry.msg}"
                if (filter.kwRegex) runCatching { Regex(filter.kwText, RegexOption.IGNORE_CASE).containsMatchIn(hay) }.getOrElse { false }
                else hay.contains(filter.kwText, ignoreCase = true)
            }
        }
    }
}

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
    if (rule.regex) runCatching { Regex(rule.pattern, RegexOption.IGNORE_CASE).containsMatchIn(entry.msg) }.getOrElse { false }
    else entry.msg.contains(rule.pattern, ignoreCase = true)

fun computeItems(tab: LogTab, sequences: List<SequenceDef>, applyFilter: Boolean): List<LogItem> {
    // Filter first, then detect sequences in filtered data only
    val data = if (applyFilter) tab.logData.filter { passesFilter(it, tab.filter) } else tab.logData

    fun sequenceItems(segment: List<LogEntry>): List<LogItem> {
        val seqGroups = if (tab.filter.seqOn && sequences.any { it.enabled })
            computeSeqGroups(segment, sequences)
        else emptyList()

        if (seqGroups.isEmpty()) return segment.map { LogItem.Row(it, 0) }

        val defMap = sequences.associateBy { it.id }
        val skipIds = buildSet<Int> {
            seqGroups.forEach { g ->
                addAll(g.plain)
                g.nested.forEach { ng -> add(ng.rid); addAll(ng.ch) }
            }
        }

        val items = mutableListOf<LogItem>()
        for (entry in segment) {
            val sg = seqGroups.find { it.rid == entry.id }
            if (sg != null) {
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
                continue
            }
            if (entry.id in skipIds) continue
            items += LogItem.Row(entry, 0)
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
        result += LogItem.ManualHeader(headerEntry, block.id, block.direction, expanded, manual.range.count(), block.color)
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

fun buildMd(tab: LogTab): String = buildString {
    if (tab.annotations.prefix.isNotBlank()) { appendLine(tab.annotations.prefix); appendLine() }
    for (block in tab.annotations.blocks) {
        when (block) {
            is AnnBlock.Note -> {
                if (block.text.isNotBlank()) { appendLine(block.text); appendLine() }
            }
            is AnnBlock.LogRef -> {
                if (block.caption.isNotBlank()) { appendLine(block.caption); appendLine() }
                if (block.sourceFilename != null) appendLine("*(from ${block.sourceFilename})*")
                val rows = block.sourceEntries ?: block.logIds.mapNotNull { tab.rmap[it] }
                rows.forEach { r -> appendLine("    ${r.ts}  ${r.level.key}/${r.tag}  ${r.msg}") }
                appendLine()
            }
        }
    }
    if (tab.annotations.suffix.isNotBlank()) {
        appendLine("---"); appendLine(); append(tab.annotations.suffix)
    }
}
