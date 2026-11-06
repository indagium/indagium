package com.indagium.storage

import com.indagium.model.LogEntry
import com.indagium.utils.procStartPidAndName

/** Aggregates metadata during the stack scan without retaining decoded source rows. */
internal class StorageAnalysisAccumulator {
    private val counts = HashMap<String, Int>()
    private val names = LinkedHashMap<Int, String>()
    private val tagPids = HashMap<String, MutableSet<Int>>()

    fun feed(entry: LogEntry) {
        counts[entry.tag] = (counts[entry.tag] ?: 0) + 1
        entry.procStartPidAndName()?.let { (pid, name) -> names[pid] = name }
        if (entry.pid > 0) tagPids.getOrPut(entry.tag) { HashSet() }.add(entry.pid)
    }

    fun finish(): StorageAnalysis = StorageAnalysis(counts.toMap(), names.toMap(), tagPids.mapValues { it.value.toSet() })
}

internal data class StorageAnalysis(
    val tagCounts: Map<String, Int>,
    val processNames: Map<Int, String>,
    val tagPids: Map<String, Set<Int>>,
)
