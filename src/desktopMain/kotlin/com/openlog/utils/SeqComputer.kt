package com.openlog.utils

import com.openlog.model.*
import java.util.BitSet

// Flat model: every enabled SequenceDef can start a group. When multiple defs match the
// same entry, the one with the lowest priority number wins. Groups are non-nested.
//
// Everything below is a single O(n·d) scan plus near-linear post-processing — the previous
// implementation recomputed "next start match" by rescanning forward per candidate and compared
// every candidate pair to find parents, which was O(candidates²): a pattern matching ~5% of a
// 10M-line file produced ~500k candidates and never finished.
private class SeqCandidate(val idx: Int, val def: SequenceDef) {
    var endExclusive: Int = 0
    var parent: Int = -1
}

private class SeqScan(logData: List<LogEntry>, enabled: List<SequenceDef>, cancellationCheck: CancellationCheck) {
    val candidates = ArrayList<SeqCandidate>()

    // def.id -> ascending indices of entries matching that def's end pattern
    val endIdxByDef = HashMap<String, MutableList<Int>>()

    init {
        val endDefs = enabled.filter { !it.endMatchText.isNullOrBlank() }
        var sinceCancellationCheck = 0
        for (idx in logData.indices) {
            // The dominant O(n·d) scan in computeSeqGroups (P-01) — see the class doc above.
            // Periodically give a cancelled caller a chance to stop instead of scanning the whole
            // file on a superseded computation.
            if (++sinceCancellationCheck >= CANCELLATION_CHECK_INTERVAL) {
                sinceCancellationCheck = 0
                cancellationCheck()
            }
            val entry = logData[idx]
            val hay = "${entry.tag} ${entry.msg}"
            val startDef = enabled.firstOrNull { matchesSeqText(entry, hay, it.matchText, it.isRegex, it.tag) }
            if (startDef != null) candidates += SeqCandidate(idx, startDef)
            for (def in endDefs) {
                val endText = def.endMatchText ?: continue
                if (matchesSeqText(entry, hay, endText, def.endIsRegex, def.endTag)) {
                    endIdxByDef.getOrPut(def.id) { mutableListOf() } += idx
                }
            }
        }
    }
}

private fun matchesSeqText(entry: LogEntry, hay: String, text: String, isRegex: Boolean, tag: String?): Boolean {
    if (tag != null && entry.tag != tag) return false
    return containsPattern(hay, text, isRegex)
}

// First element of the ascending list strictly greater than idx, or null.
private fun List<Int>.firstAfter(idx: Int): Int? {
    var lo = 0
    var hi = size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid] <= idx) lo = mid + 1 else hi = mid
    }
    return getOrNull(lo)
}

fun computeSeqGroups(
    logData: List<LogEntry>,
    defs: List<SequenceDef>,
    cancellationCheck: CancellationCheck = CancellationCheck {},
): List<SeqGroup> {
    val enabled = defs.filter { it.enabled }.sortedBy { it.priority }
    if (enabled.isEmpty()) return emptyList()

    val scan = SeqScan(logData, enabled, cancellationCheck)
    val candidates = scan.candidates
    if (candidates.isEmpty()) return emptyList()

    // Same fallback for two different situations: no end pattern configured at all, and an end
    // pattern that's configured but never actually appears in this log (e.g. the log was cut
    // before the matching event, or it just never happened). Both stop at the next start match of
    // any def, not literal end-of-log — if it fell through to end-of-log instead, two unrelated
    // occurrences of the same def (each independently failing to find their own end) would land on
    // the exact same fallback endExclusive, and the parent assignment's `<=` comparison would then
    // treat the later one as nested inside the earlier one purely by coincidence of a shared
    // fallback, not because it's actually contained within it.
    // A candidate exists exactly where some enabled def start-matches, so "next start match" is
    // just the next candidate's index.
    val startIdxs = candidates.map { it.idx }
    val nextSameDefStartIdxs = nextSameDefStartIdxs(candidates)
    candidates.forEachIndexed { ci, c ->
        val fallback = startIdxs.getOrNull(ci + 1) ?: logData.size
        val rawEndIdx = if (c.def.endMatchText.isNullOrBlank()) {
            null
        } else {
            scan.endIdxByDef[c.def.id]?.firstAfter(c.idx)
        }
        val nextSameDefStartIdx = nextSameDefStartIdxs[ci]
        val endIdx = rawEndIdx?.takeIf { end ->
            nextSameDefStartIdx < 0 || end < nextSameDefStartIdx
        }
        c.endExclusive = if (endIdx != null) endIdx + 1 else fallback
    }

    assignParents(candidates)

    val childrenByParent = HashMap<Int, MutableList<Int>>()
    candidates.forEachIndexed { ci, c ->
        if (c.parent >= 0) childrenByParent.getOrPut(c.parent) { mutableListOf() } += ci
    }

    // Entry ids inside the candidate's range not covered by any direct child's range.
    fun childIds(ci: Int): List<Int> {
        val c = candidates[ci]
        val offset = c.idx
        val covered = BitSet(c.endExclusive - offset)
        childrenByParent[ci]?.forEach { childCi ->
            val child = candidates[childCi]
            covered.set(child.idx - offset, child.endExclusive - offset)
        }
        return (c.idx + 1 until c.endExclusive)
            .filter { !covered.get(it - offset) }
            .map { logData[it].id }
    }

    return candidates.indices
        .filter { candidates[it].parent < 0 }
        .map { rootCi ->
            val root = candidates[rootCi]
            SeqGroup(
                gid = "sg_${root.def.id}_${logData[root.idx].id}",
                rid = logData[root.idx].id,
                plain = childIds(rootCi),
                nested = childrenByParent[rootCi].orEmpty().map { childCi ->
                    val child = candidates[childCi]
                    NestedSeqGroup(
                        gid = "sg_${child.def.id}_${logData[child.idx].id}",
                        rid = logData[child.idx].id,
                        ch = childIds(childCi),
                        defId = child.def.id,
                        endExclusive = child.endExclusive,
                    )
                },
                defId = root.def.id,
                endExclusive = root.endExclusive,
            )
        }
}

private fun nextSameDefStartIdxs(candidates: List<SeqCandidate>): IntArray {
    val nextIdxByDef = HashMap<String, Int>()
    val result = IntArray(candidates.size) { -1 }
    for (ci in candidates.indices.reversed()) {
        val c = candidates[ci]
        result[ci] = nextIdxByDef[c.def.id] ?: -1
        nextIdxByDef[c.def.id] = c.idx
    }
    return result
}

// Parent = the smallest-length earlier candidate whose range fully contains this one
// (parent.idx < child.idx && child.endExclusive <= parent.endExclusive), matching the previous
// all-pairs definition exactly. A sweep in idx order keeps every still-possible parent on a
// stack: a candidate popped (or buried) once its endExclusive falls at/before the current idx can
// never contain a later candidate, because a later candidate's range starts after it ends.
private fun assignParents(candidates: List<SeqCandidate>) {
    val stack = ArrayList<Int>()
    for (ci in candidates.indices) {
        val c = candidates[ci]
        while (stack.isNotEmpty() && candidates[stack.last()].endExclusive <= c.idx) {
            stack.removeAt(stack.lastIndex)
        }
        // Crossing ranges can leave dead entries buried mid-stack (ended, but not on top); they
        // fail the containment test below since c.endExclusive > c.idx >= their endExclusive.
        var bestLen = Int.MAX_VALUE
        for (si in stack) {
            val p = candidates[si]
            if (c.endExclusive <= p.endExclusive) {
                val len = p.endExclusive - p.idx
                if (len < bestLen) {
                    bestLen = len
                    c.parent = si
                }
            }
        }
        stack.add(ci)
    }
}
