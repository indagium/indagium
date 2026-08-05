package com.indagium.utils

import com.indagium.model.AppSettings
import com.indagium.model.LogEntry
import com.indagium.model.LogTab

/**
 * One canonical presentation of a log row for every user-facing copy/export surface.  Keeping
 * the metadata decisions here prevents a copied row, an annotation, and an exported row from
 * silently disagreeing about what the log view currently exposes.
 */
data class LogLinePresentationContext(
    private val tab: LogTab,
    private val settings: AppSettings,
    private val entries: List<LogEntry>,
    private val selection: Set<Int> = tab.selected,
) {
    private val selectedAnchorId = if (settings.copyTimeDelta && tab.showTimeDelta) deltaAnchorId(selection) else null
    private val selectedAnchorTs = selectedAnchorId?.let { tab.rmap[it]?.ts }
    private val indexById = entries.withIndex().associate { (index, entry) -> entry.id to index }

    fun deltaFor(entry: LogEntry): String? {
        if (!settings.copyTimeDelta || !tab.showTimeDelta) return null
        val millis = when {
            selectedAnchorTs != null -> deltaMillis(selectedAnchorTs, entry.ts)
            else -> indexById[entry.id]?.takeIf { it > 0 }?.let { index -> deltaMillis(entries[index - 1].ts, entry.ts) }
        } ?: return null
        return if (selectedAnchorTs != null) formatSignedDelta(millis) else formatDelta(millis)
    }
}

/** Text form in the same left-to-right order as the log viewer: number, Δt, timestamp, PID/TID,
 * level/tag, then message.  A missing PID is a real absence in logcat, not a zero value to copy. */
fun presentLogLine(
    tab: LogTab,
    entry: LogEntry,
    settings: AppSettings,
    context: LogLinePresentationContext? = null,
    allowProcessName: Boolean = true,
): String {
    val fields = buildList {
        if (settings.copyRowNumber && settings.showRowNumbers) add(entry.id.toString())
        context?.deltaFor(entry)?.let(::add)
        add(entry.ts)
        if (settings.copyPidTid && entry.pid > 0) {
            val displayPid = if (settings.copyPidAsName && allowProcessName) {
                tab.analysis.processNames[entry.pid] ?: entry.pid.toString()
            } else {
                entry.pid.toString()
            }
            add("$displayPid ${entry.tid}")
        }
        add("${entry.level.key}/${entry.tag}")
        add(entry.msg)
    }
    return fields.joinToString("  ")
}

/** Markdown keeps the familiar bold timestamp/level treatment while drawing its metadata from
 * the exact same decision path as [presentLogLine]. */
fun presentLogLineMarkdown(
    tab: LogTab,
    entry: LogEntry,
    settings: AppSettings,
    context: LogLinePresentationContext? = null,
    allowProcessName: Boolean = true,
): String {
    val prefix = buildList {
        if (settings.copyRowNumber && settings.showRowNumbers) add(entry.id.toString())
        context?.deltaFor(entry)?.let(::add)
        add(entry.ts)
        if (settings.copyPidTid && entry.pid > 0) {
            val displayPid = if (settings.copyPidAsName && allowProcessName) {
                tab.analysis.processNames[entry.pid] ?: entry.pid.toString()
            } else {
                entry.pid.toString()
            }
            add("$displayPid ${entry.tid}")
        }
    }.joinToString("  ")
    return "**[$prefix] `${entry.level.key}/${entry.tag}`:** ${entry.msg}"
}

/** The CSV schema follows the same visible-column order while retaining the stable minimal
 * `ts,level,tag,msg` form when every optional copy setting is off. */
fun filteredCsvHeader(tab: LogTab, settings: AppSettings): List<String> = buildList {
    if (settings.copyRowNumber && settings.showRowNumbers) add("row_number")
    if (settings.copyTimeDelta && tab.showTimeDelta) add("time_delta")
    add("ts")
    if (settings.copyPidTid) {
        add("pid")
        add("tid")
        if (settings.copyPidAsName) add("pid_name")
    }
    add("level")
    add("tag")
    add("msg")
}

fun filteredCsvValues(
    tab: LogTab,
    entry: LogEntry,
    settings: AppSettings,
    context: LogLinePresentationContext,
): List<String> = buildList {
    if (settings.copyRowNumber && settings.showRowNumbers) add(entry.id.toString())
    if (settings.copyTimeDelta && tab.showTimeDelta) add(context.deltaFor(entry).orEmpty())
    add(entry.ts)
    if (settings.copyPidTid) {
        if (entry.pid > 0) {
            add(entry.pid.toString())
            add(entry.tid.toString())
            if (settings.copyPidAsName) {
                add(tab.analysis.processNames[entry.pid].orEmpty())
            }
        } else {
            add("")
            add("")
            if (settings.copyPidAsName) add("")
        }
    }
    add(entry.level.key.toString())
    add(entry.tag)
    add(entry.msg)
}
