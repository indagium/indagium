package com.openlog.utils

import com.openlog.model.*

// Flat model: every enabled SequenceDef can start a group. When multiple defs match the
// same entry, the one with the lowest priority number wins. Groups are non-nested.
fun computeSeqGroups(logData: List<LogEntry>, defs: List<SequenceDef>): List<SeqGroup> {
    val enabled = defs.filter { it.enabled }.sortedBy { it.priority }
    if (enabled.isEmpty()) return emptyList()

    fun matchesText(entry: LogEntry, text: String, isRegex: Boolean, tag: String?): Boolean {
        if (tag != null && entry.tag != tag) return false
        val haystack = "${entry.tag} ${entry.msg}"
        return containsPattern(haystack, text, isRegex)
    }

    fun matchesStart(entry: LogEntry, def: SequenceDef) =
        matchesText(entry, def.matchText, def.isRegex, def.tag)

    fun matchesEnd(entry: LogEntry, def: SequenceDef): Boolean {
        val endText = def.endMatchText?.takeIf { it.isNotBlank() } ?: return false
        return matchesText(entry, endText, def.endIsRegex, def.endTag)
    }

    data class Candidate(val idx: Int, val endExclusive: Int, val def: SequenceDef)

    // Same fallback for two different situations: no end pattern configured at all, and an end
    // pattern that's configured but never actually appears in this log (e.g. the log was cut
    // before the matching event, or it just never happened). Both stop at the next start match of
    // any def, not literal end-of-log — if it fell through to end-of-log instead, two unrelated
    // occurrences of the same def (each independently failing to find their own end) would land on
    // the exact same fallback endExclusive, and parentByChild's `<=` comparison would then treat
    // the later one as nested inside the earlier one purely by coincidence of a shared fallback,
    // not because it's actually contained within it.
    fun nextStartMatchOrEnd(idx: Int): Int =
        (idx + 1 until logData.size).firstOrNull { nextIdx ->
            enabled.any { matchesStart(logData[nextIdx], it) }
        } ?: logData.size

    fun endExclusiveFor(idx: Int, def: SequenceDef): Int {
        if (def.endMatchText.isNullOrBlank()) return nextStartMatchOrEnd(idx)
        val endIdx = (idx + 1 until logData.size).firstOrNull { matchesEnd(logData[it], def) }
        return if (endIdx != null) endIdx + 1 else nextStartMatchOrEnd(idx)
    }

    val candidates = logData.indices.mapNotNull { idx ->
        val def = enabled.firstOrNull { matchesStart(logData[idx], it) } ?: return@mapNotNull null
        Candidate(idx, endExclusiveFor(idx, def), def)
    }
    if (candidates.isEmpty()) return emptyList()

    val parentByChild = candidates.associateWith { child ->
        candidates
            .filter { parent -> parent.idx < child.idx && child.endExclusive <= parent.endExclusive }
            .minByOrNull { parent -> parent.endExclusive - parent.idx }
    }

    fun childRanges(parent: Candidate): List<Candidate> =
        candidates.filter { parentByChild[it] == parent }.sortedBy { it.idx }

    fun childIds(candidate: Candidate, children: List<Candidate>): List<Int> {
        val childRanges = children.map { it.idx until it.endExclusive }
        return (candidate.idx + 1 until candidate.endExclusive)
            .filter { idx -> childRanges.none { idx in it } }
            .map { logData[it].id }
    }

    return candidates
        .filter { parentByChild[it] == null }
        .sortedBy { it.idx }
        .map { candidate ->
            val children = childRanges(candidate)
            SeqGroup(
                gid = "sg_${candidate.def.id}_${logData[candidate.idx].id}",
                rid = logData[candidate.idx].id,
                plain = childIds(candidate, children),
                nested = children.map { child ->
                    NestedSeqGroup(
                        gid = "sg_${child.def.id}_${logData[child.idx].id}",
                        rid = logData[child.idx].id,
                        ch = childIds(child, childRanges(child)),
                        defId = child.def.id,
                    )
                },
                defId = candidate.def.id,
            )
        }
}
