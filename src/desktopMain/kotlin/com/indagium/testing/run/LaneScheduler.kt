package com.indagium.testing.run

import com.indagium.testing.model.LaneConfig
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// Which lanes of a run work at the same time. Lanes are grouped by device serial: a group runs its lanes one after another
// (a device has one recorder and one screen), different groups run in parallel, and at most MAX_PARALLEL_DEVICES groups
// work at once; the others wait for a permit and show as queued. A lane is isolated from its neighbours: [runLane] must not
// throw (the engine turns a crash into a lane error), so one lane ending badly never stops another. Cancelling the run
// cancels every group.

/** How many different devices a run drives at once. */
const val MAX_PARALLEL_DEVICES = 4

/** The lanes grouped by device serial, in the order each serial first appears; each group keeps its lanes' order. */
internal fun groupLanesBySerial(lanes: List<LaneConfig>): List<List<LaneConfig>> = lanes.groupBy { it.deviceSerial }.values.toList()

/** Runs [runLane] for every lane: groups in parallel (at most [maxParallel] at a time), a group's lanes sequentially. */
internal suspend fun runLaneGroups(lanes: List<LaneConfig>, maxParallel: Int, runLane: suspend (LaneConfig) -> Unit) {
    val permits = Semaphore(maxParallel.coerceAtLeast(1))
    coroutineScope {
        for (group in groupLanesBySerial(lanes)) {
            launch { permits.withPermit { for (lane in group) runLane(lane) } }
        }
    }
}

/** Lanes that share a device, and a device count over the cap, as sentences for the run's warnings and the run dialog. */
internal fun deviceSharingWarnings(lanes: List<LaneConfig>, maxParallel: Int = MAX_PARALLEL_DEVICES): List<String> {
    val groups = groupLanesBySerial(lanes)
    val warnings = ArrayList<String>()
    for (group in groups.filter { it.size > 1 }) {
        val numbers = group.map { lane -> lanes.indexOf(lane) + 1 }
        warnings += "Lanes ${numbers.joinToString(", ")} share the device ${group.first().deviceSerial}; they run one after another."
    }
    if (groups.size > maxParallel) {
        warnings += "${groups.size} devices are used but only $maxParallel run at the same time; the others wait for a free slot."
    }
    return warnings
}

/**
 * Pause all: while set, lanes stop at their next step boundary (after the current step has been finished, before the next
 * one is handed out) and between cases, and carry on when it is cleared.
 */
internal class RunPauseGate {
    private val paused = MutableStateFlow(false)

    val isPaused: Boolean get() = paused.value

    fun setPaused(value: Boolean) {
        paused.value = value
    }

    /** Returns at once unless paused; then suspends (cancellably) until resumed. */
    suspend fun awaitResumed() {
        if (paused.value) paused.first { !it }
    }
}
