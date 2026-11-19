package com.indagium.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogTab
import com.indagium.utils.hasRecordDetails
import com.indagium.utils.isAppendOnlyExtensionOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Which structured records of one tab are open. Not persisted: the state is [defaultExpanded] plus the rows that differ from it, so
 * "Expand all" and "Collapse all" are two constants rather than a set the size of the log, and a row is open when its default and its
 * override disagree ([overrides] holds the ids a row's own click flipped).
 */
internal data class RecordExpansion(val defaultExpanded: Boolean = false, val overrides: Set<Int> = emptySet()) {
    fun isExpanded(entryId: Int): Boolean = defaultExpanded != (entryId in overrides)

    fun toggled(entryId: Int): RecordExpansion =
        copy(overrides = if (entryId in overrides) overrides - entryId else overrides + entryId)

    /** True while no record can still be opened. */
    val allExpanded: Boolean get() = defaultExpanded && overrides.isEmpty()

    /** True while some record may be open. */
    val anyExpanded: Boolean get() = defaultExpanded || overrides.isNotEmpty()

    val isInitial: Boolean get() = this == RecordExpansion()

    companion object {
        val ALL_EXPANDED = RecordExpansion(defaultExpanded = true)
        val ALL_COLLAPSED = RecordExpansion()
    }
}

private data class RecordAvailability(val rows: List<LogEntry>, val available: Boolean)

private val recordAvailability = object : LinkedHashMap<String, RecordAvailability>(16, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RecordAvailability>?): Boolean = size > 16
}

internal fun cachedRecordAvailability(tab: LogTab): Boolean? = synchronized(recordAvailability) {
    recordAvailability[tab.id]?.takeIf { it.rows === tab.logData }?.available
}

private inline fun scanRecordAvailability(
    tabId: String,
    rows: List<LogEntry>,
    format: LogFormat,
    checkCancellation: () -> Unit,
): Boolean {
    val previous = synchronized(recordAvailability) { recordAvailability[tabId] }
    if (previous?.rows === rows) return previous.available
    val extendsPrevious = previous != null && rows.isAppendOnlyExtensionOf(previous.rows)
    val start = if (extendsPrevious) requireNotNull(previous).rows.size else 0
    val found = (extendsPrevious && previous.available) || (start until rows.size).any { index ->
        if (index % 256 == 0) checkCancellation()
        canExpandRecord(rows[index], format)
    }
    synchronized(recordAvailability) { recordAvailability[tabId] = RecordAvailability(rows, found) }
    return found
}

internal fun profileRecordsAddDetail(tabId: String, rows: List<LogEntry>, format: LogFormat = LogFormat.LOGCAT): Boolean =
    scanRecordAvailability(tabId, rows, format) {}

@Composable
internal fun rememberRecordAvailability(tab: LogTab): Boolean = key(tab.id, tab.logFormat) {
    val cached = cachedRecordAvailability(tab)
    val available by produceState(cached ?: tab.logData.firstOrNull()?.hasRecordDetails() == true, tab.id, tab.logData) {
        value = cached ?: withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            scanRecordAvailability(tab.id, tab.logData, tab.logFormat, context::ensureActive)
        }
    }
    cached ?: available
}
