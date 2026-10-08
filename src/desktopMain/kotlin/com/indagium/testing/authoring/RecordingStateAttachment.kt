package com.indagium.testing.authoring

/** When one probe ran: [seq] identifies its stored state, the times are wall-clock milliseconds. */
internal data class ProbeWindow(val seq: Int, val startedAt: Long, val finishedAt: Long)

/** One independently timed screenshot or UI/activity reading. */
internal data class EvidenceWindow(val id: Int, val startedAt: Long, val finishedAt: Long)

/** The probe states attached to one recorded input; null means unknown, never a guess. */
internal data class RowAttachment(val beforeSeq: Int? = null, val afterSeq: Int? = null)

/** When an input happened: a touch gesture spans [startMs]..[endMs]; a key press or text has both equal. */
internal data class InputWindow(val startMs: Long, val endMs: Long)

/**
 * Attaches evidence only when its complete read interval falls strictly between two inputs. This also means a probe that
 * started before a gesture and finished during it, or finished exactly when the next input began, is never treated as a
 * before/after state. In each gap the latest fully eligible reading wins; a newer overlapping read cannot hide an earlier
 * valid one.
 *
 * [inputs] are in recorded order; the result has one entry per input.
 */
internal fun attachScreenStates(inputs: List<InputWindow>, probes: List<ProbeWindow>): List<RowAttachment> {
    val windows = probes.map { EvidenceWindow(it.seq, it.startedAt, it.finishedAt) }
    return attachEvidenceWindows(inputs, windows)
}

internal fun attachEvidenceWindows(inputs: List<InputWindow>, windows: List<EvidenceWindow>): List<RowAttachment> {
    if (inputs.isEmpty()) return emptyList()
    val prefixMaxEnd = LongArray(inputs.size)
    var latestEnd = Long.MIN_VALUE
    inputs.indices.forEach { index ->
        latestEnd = maxOf(latestEnd, inputs[index].endMs)
        prefixMaxEnd[index] = latestEnd
    }
    val suffixMinStart = LongArray(inputs.size)
    var earliestStart = Long.MAX_VALUE
    inputs.indices.reversed().forEach { index ->
        earliestStart = minOf(earliestStart, inputs[index].startMs)
        suffixMinStart[index] = earliestStart
    }
    // Slot 0 is before input 1, slot n is after input n, and each middle slot is the open gap between adjacent inputs.
    val bestPerGap = arrayOfNulls<EvidenceWindow>(inputs.size + 1)
    windows.forEach { window ->
        if (window.finishedAt < window.startedAt) return@forEach
        // Prefix/suffix bounds require separation from every input, including overlapping/non-monotonic windows.
        val gap = when {
            window.finishedAt < suffixMinStart[0] -> 0
            window.startedAt > prefixMaxEnd[inputs.lastIndex] -> inputs.size
            else -> (1 until inputs.size).firstOrNull { gapIndex ->
                window.startedAt > prefixMaxEnd[gapIndex - 1] && window.finishedAt < suffixMinStart[gapIndex]
            } ?: return@forEach
        }
        val best = bestPerGap[gap]
        if (best == null || window.startedAt > best.startedAt || (window.startedAt == best.startedAt && window.id > best.id)) {
            bestPerGap[gap] = window
        }
    }
    val before = arrayOfNulls<Int>(inputs.size)
    val after = arrayOfNulls<Int>(inputs.size)
    bestPerGap.forEachIndexed { gap, window ->
        if (window != null) {
            if (gap > 0) after[gap - 1] = window.id
            if (gap < inputs.size) before[gap] = window.id
        }
    }
    return inputs.indices.map { RowAttachment(before[it], after[it]) }
}
