package com.indagium.testing.authoring

/** When one probe ran: [seq] identifies its stored state, the times are wall-clock milliseconds. */
internal data class ProbeWindow(val seq: Int, val startedAt: Long, val finishedAt: Long)

/** The probe states attached to one recorded input; null means unknown, never a guess. */
internal data class RowAttachment(val beforeSeq: Int? = null, val afterSeq: Int? = null)

/** When an input happened: a touch gesture spans [startMs]..[endMs]; a key press or text has both equal. */
internal data class InputWindow(val startMs: Long, val endMs: Long)

/**
 * Decides which probe state describes the screen before and after each input.
 *
 * - A probe is the **after** state of the last input that began before the probe's `startedAt`, provided that input had
 *   ended by then (a probe that started mid-gesture saw a moving screen and attaches to nothing). When several probes
 *   follow the same input, the latest-started one (the most settled screen) wins.
 * - The same probe is the **before** state of the next input, but only when that input began after the probe's
 *   `finishedAt`; if it began while the probe was still reading, the screen the probe saw is unknown.
 * - A probe that started before the first input is therefore only the first input's before state.
 *
 * [inputs] are in recorded order; the result has one entry per input.
 */
internal fun attachScreenStates(inputs: List<InputWindow>, probes: List<ProbeWindow>): List<RowAttachment> {
    val after = arrayOfNulls<Int>(inputs.size)
    val before = arrayOfNulls<Int>(inputs.size)
    val latestPerInput = HashMap<Int, ProbeWindow>()
    probes.forEach { probe ->
        val input = inputs.indexOfLast { it.startMs < probe.startedAt }
        if (input >= 0 && inputs[input].endMs >= probe.startedAt) return@forEach
        val best = latestPerInput[input]
        if (best == null || probe.startedAt > best.startedAt || (probe.startedAt == best.startedAt && probe.seq > best.seq)) {
            latestPerInput[input] = probe
        }
    }
    latestPerInput.forEach { (input, probe) ->
        if (input >= 0) after[input] = probe.seq
        val next = input + 1
        if (next < inputs.size && inputs[next].startMs > probe.finishedAt) before[next] = probe.seq
    }
    return inputs.indices.map { RowAttachment(before[it], after[it]) }
}
