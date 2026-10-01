package com.indagium.utils

import com.indagium.model.LogEntry

// "Follow the process of a tag": a component often logs under many tags, but the user only knows
// one of them. These pure helpers back the Tags search's pid popover (which processes logged this
// tag, which to pre-select, what rule to add) and the dynamic `tag:<Tag>` PID_TID token that
// utils/Filter.kt's resolvePidTidTokens resolves through LogAnalysis.tagPids.

/** Prefix of the dynamic PID_TID token: `tag:<Tag>` stands for every pid that logged <Tag>. */
const val TAG_PID_TOKEN_PREFIX = "tag:"

/**
 * tag -> pids that logged it, over [data]. Entries without a real pid (`pid <= 0`: the parser's
 * "unknown" value, and DLT rows) are skipped, since a rule on pid 0 would match every unattributed
 * line.
 */
fun computeTagPids(data: List<LogEntry>): Map<String, Set<Int>> {
    val result = HashMap<String, MutableSet<Int>>()
    for (entry in data) {
        if (entry.pid <= 0) continue
        result.getOrPut(entry.tag) { HashSet() } += entry.pid
    }
    return result
}

/**
 * Unions the tag -> pids of [newEntries] onto [base] — the tail batch's incremental counterpart of
 * [computeTagPids], O(batch) rather than O(whole file). Sets are copied on write, never mutated:
 * [base] may already be published in a LogTab other threads are reading.
 */
fun mergeTagPids(base: Map<String, Set<Int>>, newEntries: List<LogEntry>): Map<String, Set<Int>> {
    var merged: HashMap<String, Set<Int>>? = null
    for (entry in newEntries) {
        if (entry.pid <= 0) continue
        val current = (merged ?: base)[entry.tag]
        if (current != null && entry.pid in current) continue
        val target = merged ?: HashMap(base).also { merged = it }
        target[entry.tag] = (current ?: emptySet()) + entry.pid
    }
    return merged ?: base
}

/**
 * One process that logged a given tag, as the popover lists it. [tagLines] counts the lines of
 * that tag, [totalLines] every line of the process, [distinctTags] the tags it logged in all.
 * [firstTs]/[lastTs] are the timestamps of the process's first and last line in log order.
 */
data class TagProcessInfo(
    val pid: Int,
    val name: String?,
    val tagLines: Int,
    val totalLines: Int,
    val distinctTags: Int,
    val firstTs: String,
    val lastTs: String,
)

private class PidStats(var firstTs: String) {
    var lastTs: String = firstTs
    var totalLines = 0
    var tagLines = 0
    val tags = HashSet<String>()
}

/**
 * The processes that logged [tag], most lines of that tag first (ties by pid). One full pass that
 * keeps per-pid stats for every pid, because whether a pid logged [tag] is only known at the end.
 * [cancellationCheck] is polled every [CANCELLATION_CHECK_INTERVAL] rows.
 */
fun tagProcessInfo(
    logData: List<LogEntry>,
    tag: String,
    processNames: Map<Int, String>,
    cancellationCheck: CancellationCheck,
): List<TagProcessInfo> {
    val stats = HashMap<Int, PidStats>()
    var sinceCheck = 0
    for (entry in logData) {
        if (++sinceCheck >= CANCELLATION_CHECK_INTERVAL) {
            sinceCheck = 0
            cancellationCheck()
        }
        if (entry.pid <= 0) continue
        val s = stats.getOrPut(entry.pid) { PidStats(entry.ts) }
        s.lastTs = entry.ts
        s.totalLines++
        s.tags += entry.tag
        if (entry.tag == tag) s.tagLines++
    }
    return stats.asSequence()
        .filter { it.value.tagLines > 0 }
        .map { (pid, s) ->
            TagProcessInfo(pid, processNames[pid], s.tagLines, s.totalLines, s.tags.size, s.firstTs, s.lastTs)
        }
        .sortedWith(compareByDescending<TagProcessInfo> { it.tagLines }.thenBy { it.pid })
        .toList()
}

/**
 * The pids the popover pre-selects: the one with the most lines of the tag (the first of [infos],
 * which [tagProcessInfo] sorts that way) plus every pid with the same process name. Unknown names
 * compare equal (null == null), so all name-less pids are selected together.
 */
fun defaultTagProcessSelection(infos: List<TagProcessInfo>): Set<Int> {
    val top = infos.firstOrNull() ?: return emptySet()
    return infos.filter { it.pid == top.pid || it.name == top.name }.mapTo(LinkedHashSet()) { it.pid }
}

/**
 * Whether [tag] can be written as a `tag:<Tag>` token: resolvePidTidTokens splits a pattern on ','
 * and whitespace, so a tag containing either would be cut in two. Shared by the popover (to disable
 * "keep following") and [tagProcessRulePattern], so the two can never disagree.
 */
fun canFollowTagByToken(tag: String): Boolean = tag.isNotEmpty() && tag.none { it == ',' || it.isWhitespace() }

/**
 * The PID_TID rule pattern for the popover's choice. With [keepFollowing], every pid selected and a
 * tag that [canFollowTagByToken], it is the dynamic `tag:<Tag>` token (a restarted app's new pid is
 * then picked up automatically); otherwise the selected pids, comma-joined ascending
 * (resolvePidTidTokens splits on ',').
 */
fun tagProcessRulePattern(tag: String, selected: Set<Int>, all: Set<Int>, keepFollowing: Boolean): String =
    if (keepFollowing && selected == all && canFollowTagByToken(tag)) {
        "$TAG_PID_TOKEN_PREFIX$tag"
    } else {
        selected.sorted().joinToString(",")
    }
