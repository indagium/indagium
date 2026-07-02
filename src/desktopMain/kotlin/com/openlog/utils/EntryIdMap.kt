package com.openlog.utils

import com.openlog.model.LogEntry

// Read-only Map<Int, LogEntry> view over a tab's logData, replacing the HashMap id→entry copy
// that used to add ~70 bytes/entry of pure overhead (≈700MB on a 10M-line file). Valid because
// entry ids are strictly increasing in every construction path: LogParser assigns startId..n,
// mergeLogs re-ids sequentially, and tailing appends from max+1.
class EntryIdMap(private val data: List<LogEntry>) : AbstractMap<Int, LogEntry>() {
    override fun containsKey(key: Int): Boolean = get(key) != null

    override fun get(key: Int): LogEntry? {
        if (data.isEmpty()) return null
        // Dense fast path: ids are usually exactly firstId..firstId+n-1.
        val guess = key - data.first().id
        if (guess in data.indices && data[guess].id == key) return data[guess]
        var lo = 0
        var hi = data.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val id = data[mid].id
            when {
                id < key -> lo = mid + 1
                id > key -> hi = mid - 1
                else -> return data[mid]
            }
        }
        return null
    }

    override val entries: Set<Map.Entry<Int, LogEntry>>
        get() = object : AbstractSet<Map.Entry<Int, LogEntry>>() {
            override val size: Int get() = data.size

            override fun iterator(): Iterator<Map.Entry<Int, LogEntry>> =
                data.asSequence()
                    .map { entry -> java.util.AbstractMap.SimpleImmutableEntry(entry.id, entry) as Map.Entry<Int, LogEntry> }
                    .iterator()
        }
}
