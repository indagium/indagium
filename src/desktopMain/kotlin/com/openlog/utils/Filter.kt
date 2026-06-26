package com.openlog.utils

import androidx.compose.ui.graphics.Color
import com.openlog.model.*

fun passesFilter(entry: LogEntry, filter: Filter): Boolean {
    if (entry.level !in filter.levels) return false
    if (entry.tag in filter.excludeTags) return false
    if (filter.excludeKw.isNotBlank()) {
        val hay = "${entry.tag} ${entry.msg}"
        val excluded = if (filter.excludeKwRegex)
            runCatching { Regex(filter.excludeKw, RegexOption.IGNORE_CASE).containsMatchIn(hay) }.getOrElse { false }
        else hay.contains(filter.excludeKw, ignoreCase = true)
        if (excluded) return false
    }
    // PID / TID filter — comma-separated list, entry passes if its pid OR tid matches any
    if (filter.pidTidFilter.isNotBlank()) {
        val tokens = filter.pidTidFilter.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        val pidStr = entry.pid.toString(); val tidStr = entry.tid.toString()
        if (tokens.none { it == pidStr || it == tidStr }) return false
    }
    val basePass = when (filter.mode) {
        FilterMode.TAGS -> {
            val prefixPass = filter.pkgPrefixes.isEmpty() ||
                filter.pkgPrefixes.any { pfx -> entry.tag == pfx || entry.tag.startsWith("$pfx.") }
            val activeTagPass = filter.activeTags.isEmpty() || entry.tag in filter.activeTags
            val tagPass = prefixPass && activeTagPass
            if (!tagPass) return false
            // Secondary message keyword filter within the tag result set
            if (filter.kwInTag.isNotBlank()) {
                val msgPass = if (filter.kwInTagRegex)
                    runCatching { Regex(filter.kwInTag, RegexOption.IGNORE_CASE).containsMatchIn(entry.msg) }.getOrElse { false }
                else entry.msg.contains(filter.kwInTag, ignoreCase = true)
                if (!msgPass) return false
            }
            true
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
    if (!basePass) return false
    return passesMessageRules(entry, filter.messageRules)
}

private fun passesMessageRules(entry: LogEntry, rules: List<MessageRule>): Boolean {
    val enabled = rules.filter { it.enabled && it.pattern.isNotBlank() && ruleScopeMatches(entry, it) }
    val includeRules = enabled.filter { it.include }
    if (includeRules.isNotEmpty() && includeRules.none { rulePatternMatches(entry, it) }) return false
    if (enabled.any { !it.include && rulePatternMatches(entry, it) }) return false
    return true
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
                val outerColor = defMap[sg.defId]?.color ?: Color(0xFF8957e5)
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
            manual.range.forEach { idx ->
                if (data[idx].id != block.anchorId) result += LogItem.Row(data[idx], 1, block.color)
            }
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
