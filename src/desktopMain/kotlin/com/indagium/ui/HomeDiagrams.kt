package com.indagium.ui

import com.indagium.model.HomeDiagramSegment
import com.indagium.model.HomeDiagramsView
import com.indagium.utils.computeLogFingerprint
import com.indagium.utils.scanArchiveCandidates
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// The New tab's Diagrams section, minus the Compose: which diagrams a segment lists, how a search matches, what a row says and how a
// ticket row opens. HomeDiagramsSection.kt draws it. A diagram's "ticket" is its source log (DiagramSourceIdentity), so "No ticket" lists
// the diagrams drawn without a log, "Ticket" the ones drawn from one.

/** The segment's library filter. */
internal fun HomeDiagramSegment.ticketFilter(): DiagramTicketFilter = when (this) {
    HomeDiagramSegment.NO_TICKET -> DiagramTicketFilter.NO_TICKET
    HomeDiagramSegment.TICKET -> DiagramTicketFilter.TICKET
    HomeDiagramSegment.ALL -> DiagramTicketFilter.ALL
}

internal fun homeDiagramSegmentLabel(segment: HomeDiagramSegment): String = when (segment) {
    HomeDiagramSegment.NO_TICKET -> "No ticket"
    HomeDiagramSegment.TICKET -> "Ticket"
    HomeDiagramSegment.ALL -> "All"
}

internal fun homeDiagramsViewLabels(): List<String> = listOf("Grid", "List")

internal fun homeDiagramsViewIndex(view: HomeDiagramsView): Int = if (view == HomeDiagramsView.GRID) 0 else 1

internal fun homeDiagramsViewAt(index: Int): HomeDiagramsView = if (index == 0) HomeDiagramsView.GRID else HomeDiagramsView.LIST

private const val HOME_DIAGRAM_PARTICIPANTS_SHOWN = 3
private const val HOME_DIAGRAM_NO_PARTICIPANTS = "No participants yet"

/** "Client, Server, Cache +2": the first few participants, then how many more there are; a placeholder for a diagram with none. */
internal fun homeDiagramParticipantsLabel(labels: List<String>): String {
    val names = labels.map { it.trim() }.filter { it.isNotEmpty() }
    if (names.isEmpty()) return HOME_DIAGRAM_NO_PARTICIPANTS
    val shown = names.take(HOME_DIAGRAM_PARTICIPANTS_SHOWN).joinToString(", ")
    val more = names.size - HOME_DIAGRAM_PARTICIPANTS_SHOWN
    return if (more > 0) "$shown +$more" else shown
}

/** True when every word of [query] (case-insensitive) occurs in the diagram's title, its participants or its ticket label. A blank query matches all. */
internal fun homeDiagramMatches(summary: DiagramLibrarySummary, query: String): Boolean {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val haystack = (listOf(summary.title, summary.ticketLabel) + summary.participantLabels).joinToString("\n").lowercase()
    return words.all { it in haystack }
}

/**
 * What one row of the section shows. [ticketLabel] is blank unless the row has a ticket and the segment shows it; [noTicketBadge] marks a
 * log-free row in All.
 */
internal data class HomeDiagramRow(
    val id: String,
    val title: String,
    val participants: String,
    val updated: String,
    val ticketLabel: String,
    val noTicketBadge: Boolean,
    /** The diagram's last change; with [id] it names the revision a Grid preview was drawn from. */
    val updatedAt: Long = 0L,
)

private const val HOME_DIAGRAM_UNTITLED = "Untitled diagram"

/** The rows of [segment] for [query], newest update first (the order of [summaries] is kept, which [Seq3Session.listLibrary] gives). */
internal fun homeDiagramRows(
    summaries: List<DiagramLibrarySummary>,
    segment: HomeDiagramSegment,
    query: String,
    nowMs: Long,
): List<HomeDiagramRow> {
    val filter = segment.ticketFilter()
    return summaries
        .filter { filter.accepts(it.source) && homeDiagramMatches(it, query) }
        .map { summary ->
            HomeDiagramRow(
                id = summary.id,
                title = summary.title.ifBlank { HOME_DIAGRAM_UNTITLED },
                participants = homeDiagramParticipantsLabel(summary.participantLabels),
                updated = "Updated " + formatRelativeTime(nowMs - summary.updatedAt, summary.updatedAt),
                ticketLabel = if (segment != HomeDiagramSegment.NO_TICKET && summary.hasTicket) summary.ticketLabel else "",
                noTicketBadge = segment == HomeDiagramSegment.ALL && !summary.hasTicket,
                updatedAt = summary.updatedAt,
            )
        }
}

private const val HOME_DIAGRAM_GRID_MIN_CARD_DP = 140f
private const val HOME_DIAGRAM_GRID_MAX_COLUMNS = 6

/** How many cards sit per row in a section [availableWidthDp] wide with [gapDp] between cards: as many as keep each at least a minimum width, 1 to 6. */
internal fun homeDiagramGridColumns(availableWidthDp: Float, gapDp: Float = 8f): Int {
    if (!availableWidthDp.isFinite()) return 1
    val fit = ((availableWidthDp + gapDp) / (HOME_DIAGRAM_GRID_MIN_CARD_DP + gapDp)).toInt()
    return fit.coerceIn(1, HOME_DIAGRAM_GRID_MAX_COLUMNS)
}

/**
 * The line shown instead of the list when [segment] has no rows for [query]; [offersNew] is true when "New diagram" is the way forward
 * (nothing was searched for).
 */
internal data class HomeDiagramsEmpty(val message: String, val offersNew: Boolean)

internal fun homeDiagramsEmpty(segment: HomeDiagramSegment, query: String): HomeDiagramsEmpty {
    val trimmed = query.trim()
    if (trimmed.isNotEmpty()) return HomeDiagramsEmpty("Nothing matches \"$trimmed\"", offersNew = false)
    val message = when (segment) {
        HomeDiagramSegment.NO_TICKET -> "No diagrams without a ticket yet"
        HomeDiagramSegment.TICKET -> "No diagrams with a ticket yet. Diagrams drawn from a log show up here."
        HomeDiagramSegment.ALL -> "No diagrams yet"
    }
    return HomeDiagramsEmpty(message, offersNew = true)
}

// ── Where a ticket's log is ─────────────────────────────────────────

/** A ticket's log on disk: a plain file, or an entry inside an archive (source path `<archive>!<entry>`). */
internal sealed interface HomeDiagramLogFile {
    data class Plain(val file: File) : HomeDiagramLogFile

    data class ArchiveEntry(val archive: File, val entryPath: String) : HomeDiagramLogFile
}

/**
 * Reads a diagram's source path as a log on disk. A path that is a file is a plain log (checked first, so a file name with a "!" in it
 * stays one); otherwise each "!" is tried as the archive/entry split, and the first prefix that is a file wins. Null when neither
 * exists. [isFile] is a parameter so this stays testable without the disk.
 */
internal fun resolveDiagramLogFile(sourcePath: String, isFile: (File) -> Boolean = File::isFile): HomeDiagramLogFile? {
    if (sourcePath.isBlank()) return null
    File(sourcePath).takeIf(isFile)?.let { return HomeDiagramLogFile.Plain(it) }
    var bang = sourcePath.indexOf('!')
    while (bang >= 0) {
        val archive = File(sourcePath.substring(0, bang))
        val entry = sourcePath.substring(bang + 1)
        if (entry.isNotEmpty() && isFile(archive)) return HomeDiagramLogFile.ArchiveEntry(archive, entry)
        bang = sourcePath.indexOf('!', bang + 1)
    }
    return null
}

/** Why a ticket diagram opened without its log, for the notice. */
internal enum class HomeDiagramLogProblem { MISSING, CHANGED, NOT_OPENED }

internal fun homeDiagramLogProblemNotice(problem: HomeDiagramLogProblem, ticketLabel: String): DiagramNotice {
    val log = ticketLabel.ifBlank { "the source log" }
    return when (problem) {
        HomeDiagramLogProblem.MISSING -> DiagramNotice(
            "Source log not found",
            "The log this diagram was drawn from ($log) is not open and was not found on disk. " +
                "The saved diagram was opened without it.",
        )
        HomeDiagramLogProblem.CHANGED -> DiagramNotice(
            "Source log has changed",
            "$log is no longer the log this diagram was drawn from. The saved diagram was opened without it.",
        )
        HomeDiagramLogProblem.NOT_OPENED -> DiagramNotice(
            "Source log not opened",
            "$log could not be opened. The saved diagram was opened without it.",
        )
    }
}

private const val HOME_DIAGRAM_LOG_POLL_MS = 100L
private const val HOME_DIAGRAM_LOG_LOAD_TIMEOUT_MS = 10 * 60_000L

/**
 * Opens a diagram from the New tab's Diagrams section.
 *
 * - A diagram without a ticket opens in its own Diagram tab ([Seq3Session.openLibraryItemWithoutLog]).
 * - A ticket diagram that is already open is brought to the front; when it was left without its log (that log closed) and the log is
 *   open again, it is relinked to it.
 * - Otherwise its log is found by the library's source identity: an open tab with the same path and content fingerprint, else the
 *   file (or archive entry) on disk, opened like any other file and awaited. The diagram then opens against that tab.
 * - When the log is gone, has changed since the diagram was saved, or cannot be opened, the saved diagram opens without a log and a
 *   notice says why ([homeDiagramLogProblemNotice]).
 *
 * Every successful open closes the New tab it started from ([AppState.closeLauncherAfterOpen]), like opening a log does.
 */
internal class HomeDiagramOpener(private val state: AppState) {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** True while [open] is still finding or loading the log of the diagram [id]; a second click on it is ignored meanwhile. */
    fun isOpening(id: String): Boolean = id in inFlight

    /** Starts opening [summary]; a diagram without a ticket opens before this returns, a ticket one on the app's IO scope. */
    fun open(summary: DiagramLibrarySummary, launcherTabId: String?) {
        if (!summary.hasTicket) {
            openWithoutLog(summary, launcherTabId, problem = null)
            return
        }
        if (!inFlight.add(summary.id)) return
        state.launchInBackground {
            try {
                openTicket(summary, launcherTabId)
            } finally {
                inFlight.remove(summary.id)
            }
        }
    }

    /** The whole open of a ticket diagram, suspending while its log loads. Public to the tests; the UI goes through [open]. */
    suspend fun openTicket(summary: DiagramLibrarySummary, launcherTabId: String?) {
        val id = summary.id
        val sessions = state.seq3Sessions
        sessions.sessions.firstOrNull { it.libraryItemId == id }?.let { existing ->
            if (existing.sourceTabId == null) matchingOpenLog(summary.source)?.let { tabId -> sessions.relink(existing.id, tabId) }
            sessions.activate(existing.id)
            state.closeLauncherAfterOpen(launcherTabId)
            return
        }
        when (val found = findLog(summary.source, launcherTabId)) {
            is LogSearch.Found -> {
                if (sessions.openLibraryItem(id, found.tabId)) {
                    state.closeLauncherAfterOpen(launcherTabId)
                } else {
                    openWithoutLog(summary, launcherTabId, HomeDiagramLogProblem.NOT_OPENED)
                }
            }
            is LogSearch.Problem -> openWithoutLog(summary, launcherTabId, found.problem)
        }
    }

    private fun openWithoutLog(summary: DiagramLibrarySummary, launcherTabId: String?, problem: HomeDiagramLogProblem?) {
        val origin = launcherTabId?.takeIf { state.tab(it) != null }?.let { TabRef.Log(it) }
        if (state.seq3Sessions.openLibraryItemWithoutLog(summary.id, origin) == null) {
            state.pendingDiagramNotice = DiagramNotice(
                "Couldn't open diagram",
                "\"${summary.title.ifBlank { HOME_DIAGRAM_UNTITLED }}\" could not be read from the diagram library.",
            )
            return
        }
        if (problem != null) state.pendingDiagramNotice = homeDiagramLogProblemNotice(problem, summary.ticketLabel)
        state.closeLauncherAfterOpen(launcherTabId)
    }

    private sealed interface LogSearch {
        data class Found(val tabId: String) : LogSearch

        data class Problem(val problem: HomeDiagramLogProblem) : LogSearch
    }

    private fun isLogTabOfPath(tab: com.indagium.model.LogTab, path: String): Boolean =
        !tab.isCaptureLauncher && (tab.sourcePath ?: tab.filename) == path

    /** The id of an open tab holding exactly this log (same path, same content fingerprint), or null. */
    private suspend fun matchingOpenLog(source: DiagramSourceIdentity): String? {
        val candidates = state.tabs.filter { isLogTabOfPath(it, source.sourcePath) }
        return candidates.firstOrNull { verified(it.id, source) == true }?.id
    }

    /** True when the tab, once loaded, is the log of [source]; false when it is another revision; null when it never loaded. */
    private suspend fun verified(tabId: String, source: DiagramSourceIdentity): Boolean? {
        val loaded = withTimeoutOrNull(HOME_DIAGRAM_LOG_LOAD_TIMEOUT_MS) { state.awaitTabLoaded(tabId) } ?: return null
        return computeLogFingerprint(loaded.logData) == source.contentFingerprint
    }

    private suspend fun findLog(source: DiagramSourceIdentity, launcherTabId: String?): LogSearch {
        val open = state.tabs.firstOrNull { isLogTabOfPath(it, source.sourcePath) }
        if (open != null) return outcomeOf(verified(open.id, source), open.id)
        val origin = launcherTabId?.takeIf { state.tab(it) != null }?.let { TabRef.Log(it) }
        val tabId = when (val log = resolveDiagramLogFile(source.sourcePath)) {
            null -> return LogSearch.Problem(HomeDiagramLogProblem.MISSING)
            is HomeDiagramLogFile.Plain -> state.openPath(log.file, origin)
            is HomeDiagramLogFile.ArchiveEntry -> {
                val candidate = scanArchiveCandidates(log.archive).logCandidates.firstOrNull { it.entryPath == log.entryPath }
                    ?: return LogSearch.Problem(HomeDiagramLogProblem.MISSING)
                state.openArchiveEntry(log.archive, candidate, origin)
            }
        } ?: return LogSearch.Problem(HomeDiagramLogProblem.NOT_OPENED)
        val deadline = System.currentTimeMillis() + HOME_DIAGRAM_LOG_LOAD_TIMEOUT_MS
        while (state.tab(tabId) == null && state.isLoadInFlight(tabId) && System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            delay(HOME_DIAGRAM_LOG_POLL_MS)
        }
        if (state.tab(tabId) == null) return LogSearch.Problem(HomeDiagramLogProblem.NOT_OPENED)
        return outcomeOf(verified(tabId, source), tabId)
    }

    private fun outcomeOf(verified: Boolean?, tabId: String): LogSearch = when (verified) {
        true -> LogSearch.Found(tabId)
        false -> LogSearch.Problem(HomeDiagramLogProblem.CHANGED)
        null -> LogSearch.Problem(HomeDiagramLogProblem.NOT_OPENED)
    }
}
