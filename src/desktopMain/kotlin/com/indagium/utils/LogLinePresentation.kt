package com.indagium.utils

import com.indagium.model.AnnBlock
import com.indagium.model.AppSettings
import com.indagium.model.LegacyLogRefPreferences
import com.indagium.model.LogEntry
import com.indagium.model.LogItem
import com.indagium.model.LogTab
import com.indagium.model.ProcessNameMode
import com.indagium.model.entry

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
    private val selectedAnchor = selectedAnchorId?.let { tab.rmap[it] }

    // Selected copies already have a baseline, so they do not need the full visible-entry index.
    // Likewise, when Δt copying is disabled no caller can observe the index. Keep this allocation
    // on the filtered/export path only when Δt is enabled and the ordinary previous-visible-row
    // baseline is actually required.
    private val indexById = if (settings.copyTimeDelta && tab.showTimeDelta && selectedAnchor == null) {
        entries.withIndex().associate { (index, entry) -> entry.id to index }
    } else {
        null
    }

    fun deltaFor(entry: LogEntry): String? {
        if (!settings.copyTimeDelta || !tab.showTimeDelta) return null
        val millis = when {
            selectedAnchor != null -> deltaMillis(selectedAnchor, entry)
            else -> indexById?.get(entry.id)?.takeIf { it > 0 }?.let { index -> deltaMillis(entries[index - 1], entry) }
        } ?: return null
        return if (selectedAnchor != null) formatSignedDelta(millis) else formatDelta(millis)
    }
}

/**
 * One line-presentation context for a whole copy/export build, created lazily so the full-log
 * [visibleEntries] re-filter runs at most once (and not at all when no block needs Δt). The HTML and
 * Markdown builders of one clipboard copy share the same instance.
 */
fun sharedLogLineContext(tab: LogTab, settings: AppSettings): Lazy<LogLinePresentationContext> =
    lazy { LogLinePresentationContext(tab, settings, visibleEntries(tab)) }

/** The context a local-source log block needs, or null when it cannot affect the output: Δt is the
 * only thing the context contributes, so skip it (and its visible-row scan) unless that is on. */
internal fun annotationLogLineContext(
    tab: LogTab,
    settings: AppSettings,
    localSource: Boolean,
    shared: Lazy<LogLinePresentationContext>,
): LogLinePresentationContext? =
    if (localSource && settings.copyTimeDelta && tab.showTimeDelta) shared.value else null

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

/**
 * Freeze just the selected excerpt and the metadata needed to show it. [displayedEntries] must be
 * the originating panel's actual LogItem order (headers included); for a plain programmatic caller
 * use [captureFilteredAnnotationLogRef], which materializes the tab's current filtered view.
 * The settings parameter remains for caller compatibility; annotation options are independent of
 * global copy preferences.
 */
@Suppress("UNUSED_PARAMETER")
fun captureAnnotationLogRef(
    tab: LogTab,
    targetIds: List<Int>,
    selection: Set<Int>,
    displayedEntries: List<LogEntry>,
    settings: AppSettings,
    fallbackEntries: List<LogEntry> = emptyList(),
): AnnBlock.LogRefCapture {
    // Log ids and the viewer's displayed item order are ascending. Resolve only the bounded
    // excerpt and use binary search for no-selection predecessors; opening any row context menu
    // must not scan/copy a multi-million-row ItemList on the UI thread.
    val ordered = targetIds.distinct().sorted().mapNotNull { id ->
        tab.rmap[id] ?: findDisplayedEntry(displayedEntries, id) ?: findDisplayedEntry(fallbackEntries, id)
    }
    val anchorId = selection.minOrNull()
    val anchor = anchorId?.let(tab.rmap::get)
    val capturedRows = ordered.map { entry ->
        val deltaText = if (anchorId != null) {
            anchor?.let { deltaMillis(it, entry) }?.let(::formatSignedDelta)
                ?: UNKNOWN_ANNOTATION_DELTA
        } else {
            val displayedIndex = findDisplayedIndex(displayedEntries, entry.id)
            displayedEntries.getOrNull(displayedIndex - 1)?.let { deltaMillis(it, entry) }?.let(::formatDelta)
                ?: UNKNOWN_ANNOTATION_DELTA
        }
        val candidate = tab.analysis.processNames[entry.pid]?.takeIf { entry.pid > 0 }
        val displayed = candidate.takeIf {
            when (tab.processNameMode) {
                ProcessNameMode.OFF -> false
                ProcessNameMode.ALL -> true
                ProcessNameMode.MANUAL -> entry.pid in tab.manualProcessNamePicks
            }
        }
        AnnBlock.LogRefRowPresentation(
            logId = entry.id,
            rowNumberText = entry.id.toString(),
            timeDeltaText = deltaText,
            displayedProcessName = displayed,
            processNameCandidate = candidate,
        )
    }
    return AnnBlock.LogRefCapture(
        rows = ordered.toList(),
        presentation = AnnBlock.LogRefPresentation(
            // New annotation choices are explicit, independent of source-view visibility and
            // global copy preferences. Keep all captured values above so any option can be enabled
            // in the editor without consulting mutable tab state.
            options = AnnBlock.LogRefPresentationOptions(),
            rows = capturedRows,
        ),
    )
}

/** Current filtered-view fallback for MCP and other callers without a panel context. */
fun captureFilteredAnnotationLogRef(
    tab: LogTab,
    targetIds: List<Int>,
    settings: AppSettings,
): AnnBlock.LogRefCapture = captureAnnotationLogRef(
    tab = tab,
    targetIds = targetIds,
    selection = tab.selected,
    displayedEntries = computeItems(tab, applyFilter = true).asLogEntryView(),
    settings = settings,
)

/** A zero-copy, random-access view over the rows and headers that ItemList actually renders. */
fun List<LogItem>.asLogEntryView(): List<LogEntry> = object : AbstractList<LogEntry>() {
    override val size: Int get() = this@asLogEntryView.size

    override fun get(index: Int): LogEntry = this@asLogEntryView[index].entry
}

private fun findDisplayedIndex(entries: List<LogEntry>, id: Int): Int {
    var low = 0
    var high = entries.lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        when {
            entries[mid].id < id -> low = mid + 1
            entries[mid].id > id -> high = mid - 1
            else -> return mid
        }
    }
    return -1
}

private fun findDisplayedEntry(entries: List<LogEntry>, id: Int): LogEntry? =
    entries.getOrNull(findDisplayedIndex(entries, id))

/** Freeze an old LogRef once using the copy choices that applied when the note was opened. */
fun freezeLegacyLogRefPresentation(
    rows: List<LogEntry>,
    tab: LogTab,
    preferences: LegacyLogRefPreferences,
): AnnBlock.LogRefPresentation = AnnBlock.LogRefPresentation(
    options = AnnBlock.LogRefPresentationOptions(
        // Older notes contain no baseline; never fabricate historical values.
        timeDelta = false,
        rowNumber = preferences.copyRowNumber && preferences.showRowNumbers,
        pidTid = preferences.copyPidTid,
        processName = preferences.copyPidTid && preferences.copyPidAsName,
        allProcessNameCandidates = preferences.copyPidTid && preferences.copyPidAsName,
    ),
    rows = rows.map { entry ->
        val candidate = tab.analysis.processNames[entry.pid]?.takeIf { entry.pid > 0 }
        AnnBlock.LogRefRowPresentation(
            logId = entry.id,
            rowNumberText = entry.id.toString(),
            processNameCandidate = candidate,
        )
    },
)

private const val UNKNOWN_ANNOTATION_DELTA = "—"

/** One renderer for every annotation surface. A snapshotted block never reads live settings,
 * selections, or process-name maps; those values have already been copied into its presentation. */
fun presentAnnotationLogLine(
    entry: LogEntry,
    presentation: AnnBlock.LogRefPresentation,
): String {
    val options = presentation.options
    val row = presentation.row(entry.id)
    return buildList {
        if (options.rowNumber) add(row?.rowNumberText ?: entry.id.toString())
        if (options.timeDelta) add(row?.timeDeltaText ?: UNKNOWN_ANNOTATION_DELTA)
        add(entry.ts)
        if (options.pidTid && entry.pid > 0) {
            val name = if (options.processName) {
                row?.displayedProcessName ?: row?.processNameCandidate.takeIf { options.allProcessNameCandidates }
            } else {
                null
            }
            add("${name ?: entry.pid} ${entry.tid}")
        }
        add("${entry.level.key}/${entry.tag}")
        add(entry.msg)
    }.joinToString("  ")
}

/** Compatibility renderer used only for transient legacy blocks before their open/restore
 * migration has resolved rows. New blocks always carry a presentation snapshot. */
fun presentAnnotationLogLine(
    block: AnnBlock.LogRef,
    entry: LogEntry,
    tab: LogTab,
    settings: AppSettings,
    context: LogLinePresentationContext? = null,
    allowProcessName: Boolean = true,
): String = block.presentation?.let { presentAnnotationLogLine(entry, it) }
    ?: presentLogLine(tab, entry, settings, context, allowProcessName)

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
    if (tab.logFormat == com.indagium.model.LogFormat.DLT) {
        add("dlt_ecu_id")
        add("dlt_app_id")
        add("dlt_context_id")
        add("dlt_message_type")
        add("dlt_timestamp")
        add("dlt_timestamp_source")
    }
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
    if (tab.logFormat == com.indagium.model.LogFormat.DLT) {
        add(entry.dltEcuId.orEmpty())
        add(entry.dltAppId.orEmpty())
        add(entry.dltContextId.orEmpty())
        add(entry.dltMessageType.orEmpty())
        add(entry.dltTimestamp?.toString().orEmpty())
        add(entry.dltTimestampSource.orEmpty())
    }
    add(entry.msg)
}
