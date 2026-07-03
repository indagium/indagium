package com.openlog.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.openlog.debug.ControlServer
import com.openlog.model.*
import com.openlog.utils.EntryIdMap
import com.openlog.utils.FileTailer
import com.openlog.utils.MergeSourceFile
import com.openlog.utils.ZipLogCandidate
import com.openlog.utils.buildFilteredCsv
import com.openlog.utils.buildFilteredTxt
import com.openlog.utils.buildMd
import com.openlog.utils.computeCrashSites
import com.openlog.utils.computeItems
import com.openlog.utils.computeStackTraceGroups
import com.openlog.utils.extractCandidate
import com.openlog.utils.listArchiveLogCandidates
import com.openlog.utils.mergeLogs
import com.openlog.utils.parseLogcat
import com.openlog.utils.parseLogcatLines
import com.openlog.utils.passesFilter
import kotlinx.coroutines.*
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private fun mkRmap(data: List<LogEntry>): Map<Int, LogEntry> = EntryIdMap(data)

private fun buildLogAnalysis(data: List<LogEntry>): LogAnalysis {
    val stackGroups = computeStackTraceGroups(data)
    return LogAnalysis(
        tagCounts = data.groupingBy { it.tag }.eachCount(),
        stackTraceGroups = stackGroups,
        crashSites = computeCrashSites(data, stackGroups),
    )
}

fun mkTab(id: String, filename: String, logData: List<LogEntry>) = LogTab(
    id = id, filename = filename, logData = logData, rmap = mkRmap(logData),
    annotations = Annotations(prefix = "From $filename"),
    analysis = buildLogAnalysis(logData),
)

fun emptyWorkspaceTab() = LogTab(
    id = "untitled",
    filename = "Untitled workspace",
    logData = emptyList(),
    rmap = emptyMap(),
    annotations = Annotations(),
)

private var tabCounter = 1

private fun defaultAutosaveFile(): File =
    DesktopStorage.autosaveFile()

internal const val ANNOTATION_PANEL_MIN_WIDTH = 360f
internal const val ANNOTATION_PANEL_MAX_WIDTH = 500f
internal const val FILTER_PANEL_MIN_WIDTH = 140f
internal const val FILTER_PANEL_MAX_WIDTH = 420f
internal const val COMPARE_SPLIT_MIN = 0.2f
internal const val COMPARE_SPLIT_MAX = 0.8f
internal const val MIN_PORT = 1
internal const val MAX_PORT = 65535
internal const val DEFAULT_MCP_PORT = 8991

// Above this size, item computation runs on a background dispatcher with a loading line instead
// of synchronously during composition. 64MB (~500k lines) is roughly where a computeItems pass
// starts exceeding a frame budget by orders of magnitude; the old 512MB threshold left files in
// the 100-512MB range freezing the UI on every filter keystroke.
private const val LARGE_FILE_MODE_BYTES = 64L * 1024L * 1024L

data class PendingSequenceStart(val text: String, val tag: String)

data class PendingFilterLoad(val tabId: String, val targetFilterId: String, val currentFilterId: String?)

data class PendingDuplicateFilterSave(val tabId: String, val existingId: String, val existingName: String, val requestedName: String)

data class AnnotationNavigationRequest(val id: Long, val tabId: String, val logIds: List<Int>)

data class PendingZipPicker(val zipFile: File, val candidates: List<ZipLogCandidate>)

class AppState(
    private val autosaveFile: File = defaultAutosaveFile(),
    restoreOnCreate: Boolean = false,
    private val parser: (File) -> List<LogEntry> = ::parseLogcat,
    private val notesDir: File = DesktopStorage.notesDir(),
    private val archiveCacheDir: File = DesktopStorage.archiveCacheDir(),
    private val autoExportNotes: Boolean = true,
) {
    // ── Settings ────────────────────────────────────────────────────
    var settings by mutableStateOf(AppSettings())

    // ── Layout ──────────────────────────────────────────────────────
    var filterVisible by mutableStateOf(true)
    var annotationVisible by mutableStateOf(true)
    var filterPanelWidth by mutableStateOf(220f)
    var annotationPanelWidth by mutableStateOf(ANNOTATION_PANEL_MIN_WIDTH)
    var compareSplit by mutableStateOf(0.5f)
    var compareFilterRight by mutableStateOf(true)
    var isLoading by mutableStateOf(false)
    val logViewerScrollStateStore = LogViewerScrollStateStore()

    private val ioJob = SupervisorJob()
    private val ioScope = CoroutineScope(ioJob + Dispatchers.IO)
    private val stateLock = Any()
    private var pendingLoads = 0

    // Live tailing (utils/FileTailer.kt) — keyed by tabId. Lives here, not on LogTab, since a
    // running FileTailer/Job isn't data-class-friendly. ioJob.cancel() in close() already cancels
    // every tailer's Job for free, since each is started on ioScope.
    private data class ActiveTail(val tailer: FileTailer, val job: Job)

    private val activeTails = mutableMapOf<String, ActiveTail>()
    private val activeLoads = ConcurrentHashMap<String, Job>()

    // Latest filtered item summary per tab, pushed by LogViewer whenever its (possibly
    // background-computed) item list lands. Selection ops reuse it instead of recomputing the
    // full item list synchronously on the UI thread — on a multi-million-line tab that recompute
    // took seconds per shift-click. May briefly lag the filter during a background recompute,
    // exactly like the on-screen list it mirrors.
    private val visibleItemsByTab = ConcurrentHashMap<String, ItemsSummary>()

    fun noteVisibleItems(tabId: String, summary: ItemsSummary) {
        visibleItemsByTab[tabId] = summary
    }

    // MCP/debug control server (see debug/ControlServer.kt) — same "private resource handle,
    // guarded start, idempotent stop" shape as activeTails above.
    private var controlServer: ControlServer? = null

    // ── Sequences (per-tab, stored in Filter) ────────────────────────
    var sequences: List<SequenceDef>
        get() = activeTab()?.filter?.sequences ?: emptyList()
        set(value) { upFlt(activeTabId) { it.copy(sequences = value) } }

    // ── Tabs ────────────────────────────────────────────────────────
    var tabs by mutableStateOf(emptyList<LogTab>())
    var activeTabId by mutableStateOf("")
    var compareMode by mutableStateOf(false)
    var compareTabId by mutableStateOf("")
    var loadingStatus by mutableStateOf<String?>(null)

    // ── Transient UI ─────────────────────────────────────────────────
    // True right after a keyboard-driven panel focus change (F6/Shift+F6, Cmd+1/2/3/F); set
    // false on any pointer press. Panels only draw their accent focus border while this is
    // true — mirrors the CSS :focus-visible pattern so mouse clicks don't outline the panel.
    var keyboardFocusVisible by mutableStateOf(false)
    var ctx by mutableStateOf<CtxMenuState?>(null)
    var tabCtx by mutableStateOf<TabCtxMenuState?>(null)
    var addAnnRequest by mutableStateOf<AddAnnRequest?>(null)   // dialog to add annotation
    var sfDialog by mutableStateOf(false)
    var sfName by mutableStateOf("")
    var sfTabId by mutableStateOf<String?>(null)
    var savedFilters by mutableStateOf<List<SavedFilter>>(emptyList())
    var tagUsage by mutableStateOf<Map<String, Int>>(emptyMap())
    var settingsOpen by mutableStateOf(false)
    var shortcutsOpen by mutableStateOf(false)
    var mcpInfoOpen by mutableStateOf(false)
    var recentFiles by mutableStateOf<List<String>>(emptyList())
    var recentMenuOpen by mutableStateOf(false)
    var recentNotes by mutableStateOf<List<String>>(emptyList())
    var recentNotesMenuOpen by mutableStateOf(false)
    var cacheClearConfirmOpen by mutableStateOf(false)
    val archiveCachePath: String = archiveCacheDir.absolutePath
    val appCachePath: String = archiveCacheDir.parentFile?.absolutePath ?: archiveCacheDir.absolutePath
    var archiveCacheSizeBytes by mutableStateOf(0L)
        private set
    var pendingSequenceStart by mutableStateOf<PendingSequenceStart?>(null)
    var pendingFilterLoad by mutableStateOf<PendingFilterLoad?>(null)
    var pendingDuplicateFilterSave by mutableStateOf<PendingDuplicateFilterSave?>(null)
    var pendingDeleteFilterId by mutableStateOf<String?>(null)
    var pendingClearFilterTabId by mutableStateOf<String?>(null)
    var activeSavedFilterIds by mutableStateOf<Map<String, String>>(emptyMap())
    var pendingAnnotationNavigation by mutableStateOf<AnnotationNavigationRequest?>(null)
        private set
    var pendingZipPicker by mutableStateOf<PendingZipPicker?>(null)
    var mergeTabsDialogOpen by mutableStateOf(false)

    // Read once into the merge dialog's initial selection when opened via a tab's context menu
    // "Merge…" item — not observed reactively, so a plain var is fine.
    var mergeTabsPreselectedId: String? = null

    val fpState = FilterPanelUiState()

    var newHlPat by mutableStateOf("")
    var newHlRx by mutableStateOf(false)
    var newHlColor by mutableStateOf(HL_COLORS[0])

    var newSeqText by mutableStateOf("")
    var newSeqRegex by mutableStateOf(false)
    var newSeqEndText by mutableStateOf("")
    var newSeqEndRegex by mutableStateOf(false)
    var newSeqTag by mutableStateOf("")
    var newSeqEndTag by mutableStateOf("")
    var newSeqColor by mutableStateOf(SEQ_COLORS[0])
    private var annotationNavigationCounter = 0L

    init {
        refreshArchiveCacheInfo()
        if (restoreOnCreate) restoreAutosave()
    }

    // ── Helpers ─────────────────────────────────────────────────────
    fun close() {
        ioJob.cancel() // also cancels every active FileTailer's Job — each is started on ioScope
        activeTails.clear()
        activeLoads.clear()
        applyControlServerState(enabled = false, port = 0)
        synchronized(stateLock) {
            pendingLoads = 0
            isLoading = false
        }
    }

    // Actually starts/stops the server, independent of whether the transition should be
    // persisted — see setMcpControlEnabled (persists) vs startControlServerForThisSessionOnly
    // (doesn't). Guards against double-starting the same port (ControlServer.start() isn't
    // safely re-callable while already running) and rebinds if the port actually changed.
    private fun applyControlServerState(enabled: Boolean, port: Int) {
        if (enabled) {
            val running = controlServer
            if (running != null && running.boundPort == port) return
            running?.stop()
            controlServer = ControlServer(this, port).also { it.start() }
        } else {
            controlServer?.stop()
            controlServer = null
        }
    }

    // Settings-UI path: persists the toggle (autosaved) AND applies it immediately.
    fun setMcpControlEnabled(enabled: Boolean, port: Int) {
        val clamped = port.coerceIn(MIN_PORT, MAX_PORT)
        if (settings.mcpControlEnabled != enabled || settings.mcpControlPort != clamped) {
            settings = settings.copy(mcpControlEnabled = enabled, mcpControlPort = clamped)
            autosaveNow()
        }
        applyControlServerState(enabled, clamped)
    }

    // Main.kt's OPENLOG_DEBUG_CONTROL/-Dopenlog.debugControl path: an ephemeral dev/CI override
    // for this run only — deliberately never touches persisted settings, so a one-off env-var
    // launch doesn't silently turn the server on for every future normal launch.
    fun startControlServerForThisSessionOnly(port: Int) = applyControlServerState(true, port.coerceIn(MIN_PORT, MAX_PORT))

    // Main.kt's shutdown path — stops the server without touching persisted settings, for the
    // same reason: closing the app must not silently flip a user's saved "enabled" preference to
    // false, or it would never auto-start again on the next launch.
    fun stopControlServerForShutdown() = applyControlServerState(enabled = false, port = 0)

    // Read/mutated by the Settings "Connection info" popup — in-process passthrough to the
    // running ControlServer, no HTTP round trip needed since both live in the same JVM.
    fun connectedMcpClients() = controlServer?.connectedClients() ?: emptyList()

    fun blockMcpClient(id: String) = controlServer?.blockClient(id)

    fun unblockMcpClient(id: String) = controlServer?.unblockClient(id)

    private fun beginLoading(status: String = "Loading file...") = synchronized(stateLock) {
        pendingLoads += 1
        isLoading = true
        loadingStatus = status
    }

    private fun finishLoading() = synchronized(stateLock) {
        if (pendingLoads > 0) pendingLoads -= 1
        isLoading = pendingLoads > 0
        if (!isLoading) loadingStatus = null
    }

    fun updateFilterVisible(visible: Boolean) {
        if (filterVisible == visible) return
        filterVisible = visible
        autosaveNow()
    }

    fun updateAnnotationVisible(visible: Boolean) {
        if (annotationVisible == visible) return
        annotationVisible = visible
        autosaveNow()
    }

    fun updateCompareMode(enabled: Boolean) {
        if (compareMode == enabled) return
        compareMode = enabled
        if (enabled && compareTabId == activeTabId) {
            // Left and right panes must never show the same tab — they'd share one
            // LazyListState (keyed by tab id) and silently fight over scroll ownership.
            compareTabId = tabs.firstOrNull { it.id != activeTabId }?.id ?: ""
        }
        autosaveNow()
    }

    fun updateCompareFilterRight(visible: Boolean) {
        if (compareFilterRight == visible) return
        compareFilterRight = visible
        autosaveNow()
    }

    fun updateFilterPanelWidth(width: Float) {
        val next = width.coerceIn(FILTER_PANEL_MIN_WIDTH, FILTER_PANEL_MAX_WIDTH)
        if (filterPanelWidth == next) return
        filterPanelWidth = next
        autosaveNow()
    }

    fun updateAnnotationPanelWidth(width: Float) {
        val next = width.coerceIn(ANNOTATION_PANEL_MIN_WIDTH, ANNOTATION_PANEL_MAX_WIDTH)
        if (annotationPanelWidth == next) return
        annotationPanelWidth = next
        autosaveNow()
    }

    fun updateCompareSplit(split: Float) {
        val next = split.coerceIn(COMPARE_SPLIT_MIN, COMPARE_SPLIT_MAX)
        if (compareSplit == next) return
        compareSplit = next
        autosaveNow()
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(settings)
        if (settings == next) return
        settings = next
        autosaveNow()
    }

    fun updateFilterPanelUiState(update: FilterPanelUiState.() -> Unit) {
        fpState.update()
        autosaveNow()
    }

    fun upTab(tabId: String, fn: (LogTab) -> LogTab) {
        tabs = tabs.map { if (it.id == tabId) fn(it) else it }
    }

    fun upFlt(tabId: String, fn: (Filter) -> Filter) = upTab(tabId) { it.copy(filter = fn(it.filter)) }

    fun activeTab() = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()

    fun tab(id: String) = tabs.find { it.id == id }

    // ── Filter ──────────────────────────────────────────────────────
    fun toggleLevel(tabId: String, lvl: LogLevel) = upFlt(tabId) { f ->
        f.copy(levels = if (lvl in f.levels) f.levels - lvl else f.levels + lvl)
    }

    fun setFilterMode(tabId: String, mode: FilterMode) = upFlt(tabId) { it.copy(mode = mode) }

    fun toggleTag(tabId: String, tag: String) {
        upFlt(tabId) { f -> f.copy(activeTags = if (tag in f.activeTags) f.activeTags - tag else f.activeTags + tag) }
        tagUsage = tagUsage + (tag to ((tagUsage[tag] ?: 0) + 1))
    }

    fun clearTags(tabId: String) = upFlt(tabId) { it.copy(activeTags = emptySet()) }

    fun setKw(tabId: String, v: String) = upFlt(tabId) { it.copy(kwText = v) }

    fun toggleKwRx(tabId: String) = upFlt(tabId) { it.copy(kwRegex = !it.kwRegex) }

    fun toggleSeq(tabId: String) = upFlt(tabId) { it.copy(seqOn = !it.seqOn) }

    fun clearFilter(tabId: String) {
        upFlt(tabId) { Filter() }
        activeSavedFilterIds = activeSavedFilterIds - tabId
    }

    fun requestClearFilter(tabId: String) {
        pendingClearFilterTabId = tabId
    }

    fun cancelClearFilter() {
        pendingClearFilterTabId = null
    }

    fun confirmClearFilter() {
        val tabId = pendingClearFilterTabId ?: return
        clearFilter(tabId)
        pendingClearFilterTabId = null
    }

    fun toggleExcludeTag(tabId: String, tag: String) = upFlt(tabId) { f ->
        f.copy(excludeTags = if (tag in f.excludeTags) f.excludeTags - tag else f.excludeTags + tag)
    }.also { tagUsage = tagUsage + (tag to ((tagUsage[tag] ?: 0) + 1)) }

    fun setExcludeKw(tabId: String, v: String) = upFlt(tabId) { it.copy(excludeKw = v) }

    fun toggleExcludeKwRx(tabId: String) = upFlt(tabId) { it.copy(excludeKwRegex = !it.excludeKwRegex) }

    fun setKwInTag(tabId: String, v: String) = upFlt(tabId) { it.copy(kwInTag = v) }

    fun toggleKwInTagRx(tabId: String) = upFlt(tabId) { it.copy(kwInTagRegex = !it.kwInTagRegex) }

    fun addPkgPrefix(tabId: String, v: String) {
        if (v.isNotBlank()) upFlt(tabId) {
            val prefix = v.trim()
            it.copy(pkgPrefixes = it.pkgPrefixes + prefix, excludePkgPrefixes = it.excludePkgPrefixes - prefix)
        }
    }

    fun removePkgPrefix(tabId: String, v: String) = upFlt(tabId) { it.copy(pkgPrefixes = it.pkgPrefixes - v) }

    fun addExcludePkgPrefix(tabId: String, v: String) {
        if (v.isNotBlank()) upFlt(tabId) {
            val prefix = v.trim()
            it.copy(excludePkgPrefixes = it.excludePkgPrefixes + prefix, pkgPrefixes = it.pkgPrefixes - prefix)
        }
    }

    fun removeExcludePkgPrefix(tabId: String, v: String) =
        upFlt(tabId) { it.copy(excludePkgPrefixes = it.excludePkgPrefixes - v) }

    fun setPidTidFilter(tabId: String, v: String) = upFlt(tabId) { it.copy(pidTidFilter = v) }

    fun addMessageRule(
        tabId: String,
        include: Boolean,
        pattern: String,
        regex: Boolean,
        tag: String?,
        packagePrefix: String?,
        target: RuleTarget = RuleTarget.MESSAGE,
    ) {
        if (pattern.isBlank()) return
        val rule = MessageRule(
            id = "mr${System.currentTimeMillis()}_${pattern.hashCode()}",
            include = include,
            pattern = pattern,
            regex = regex,
            tag = tag?.trim()?.takeIf { it.isNotBlank() },
            packagePrefix = packagePrefix?.trim()?.takeIf { it.isNotBlank() },
            target = target,
        )
        upFlt(tabId) { it.copy(messageRules = it.messageRules + rule) }
    }

    fun toggleMessageRule(tabId: String, id: String) = upFlt(tabId) { f ->
        f.copy(messageRules = f.messageRules.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
    }

    fun removeMessageRule(tabId: String, id: String) = upFlt(tabId) { f ->
        f.copy(messageRules = f.messageRules.filterNot { it.id == id })
    }

    // ── Highlighters ────────────────────────────────────────────────
    fun addHl(tabId: String, pat: String, rx: Boolean, color: Color) {
        if (pat.isBlank()) return
        upFlt(tabId) { f ->
            f.copy(
                highlighters = f.highlighters + Highlighter(
                    "hl${System.currentTimeMillis()}",
                    pat,
                    rx,
                    color,
                    true
                )
            )
        }
        newHlPat = ""
        newHlColor = HL_COLORS[(HL_COLORS.indexOf(color) + 1) % HL_COLORS.size]
    }

    fun removeHl(tabId: String, id: String) =
        upFlt(tabId) { f -> f.copy(highlighters = f.highlighters.filter { it.id != id }) }

    fun toggleHl(tabId: String, id: String) = upFlt(tabId) { f ->
        f.copy(highlighters = f.highlighters.map { if (it.id == id) it.copy(on = !it.on) else it })
    }

    fun setHighlighterColor(tabId: String, id: String, color: Color) = upFlt(tabId) { f ->
        f.copy(highlighters = f.highlighters.map { if (it.id == id) it.copy(color = color) else it })
    }

    // ── Sequences ───────────────────────────────────────────────────
    private fun nextSequenceColor(from: Color, existing: List<SequenceDef> = sequences): Color {
        val start = SEQ_COLORS.indexOf(from).takeIf { it >= 0 } ?: 0
        val used = existing.map { it.color }.toSet()
        for (offset in 0 until SEQ_COLORS.size) {
            val candidate = SEQ_COLORS[(start + offset) % SEQ_COLORS.size]
            if (candidate !in used) return candidate
        }
        return SEQ_COLORS[start]
    }

    private fun colorAfterSequenceColor(color: Color): Color {
        val start = SEQ_COLORS.indexOf(color).takeIf { it >= 0 } ?: 0
        return nextSequenceColor(SEQ_COLORS[(start + 1) % SEQ_COLORS.size])
    }

    fun addSequence(
        text: String,
        isRegex: Boolean,
        color: Color,
        tag: String? = null,
        endText: String? = null,
        endIsRegex: Boolean = false,
        endTag: String? = null,
    ) {
        if (text.isBlank()) return
        upFlt(activeTabId) { f ->
            val maxP = f.sequences.maxOfOrNull { it.priority } ?: 0
            val assignedColor = nextSequenceColor(color, f.sequences)
            newSeqColor = colorAfterSequenceColor(assignedColor)
            f.copy(
                sequences = f.sequences + SequenceDef(
                    "seq${System.currentTimeMillis()}",
                    text,
                    isRegex,
                    maxP + 1,
                    assignedColor,
                    tag = tag?.trim()?.takeIf { it.isNotBlank() },
                    endMatchText = endText?.takeIf { it.isNotBlank() },
                    endIsRegex = endIsRegex,
                    endTag = endTag?.trim()?.takeIf { it.isNotBlank() },
                )
            )
        }
        newSeqText = ""
        newSeqEndText = ""
        newSeqTag = ""
        newSeqEndTag = ""
    }

    fun updateSequence(
        id: String,
        matchText: String,
        isRegex: Boolean,
        tag: String?,
        endMatchText: String?,
        endIsRegex: Boolean,
        endTag: String?,
    ) {
        if (matchText.isBlank()) return
        upFlt(activeTabId) { f ->
            f.copy(
                sequences = f.sequences.map {
                    if (it.id != id) it else it.copy(
                        matchText = matchText,
                        isRegex = isRegex,
                        tag = tag?.trim()?.takeIf { value -> value.isNotBlank() },
                        endMatchText = endMatchText?.takeIf { value -> value.isNotBlank() },
                        endIsRegex = endIsRegex,
                        endTag = endTag?.trim()?.takeIf { value -> value.isNotBlank() },
                    )
                }
            )
        }
    }

    fun removeSequence(id: String) {
        upFlt(activeTabId) { f -> f.copy(sequences = f.sequences.filter { it.id != id }) }
    }

    fun toggleSequence(id: String) {
        upFlt(activeTabId) { f ->
            f.copy(sequences = f.sequences.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
        }
    }

    fun setSequenceColor(id: String, color: Color) {
        upFlt(activeTabId) { f ->
            f.copy(sequences = f.sequences.map { if (it.id == id) it.copy(color = color) else it })
        }
    }

    fun reorderSequence(fromId: String, toIdx: Int) {
        upFlt(activeTabId) { f ->
            val list = f.sequences.toMutableList()
            val fromIdx = list.indexOfFirst { it.id == fromId }.takeIf { it >= 0 } ?: return@upFlt f
            val item = list.removeAt(fromIdx)
            list.add(toIdx.coerceIn(0, list.size), item)
            f.copy(sequences = list.mapIndexed { i, s -> s.copy(priority = i + 1) })
        }
    }

    fun moveSequenceUp(id: String) {
        upFlt(activeTabId) { f ->
            val idx = f.sequences.indexOfFirst { it.id == id }.takeIf { it > 0 } ?: return@upFlt f
            val list = f.sequences.toMutableList()
            val a = list[idx - 1]; val b = list[idx]
            list[idx - 1] = b.copy(priority = a.priority); list[idx] = a.copy(priority = b.priority)
            f.copy(sequences = list)
        }
    }

    fun moveSequenceDown(id: String) {
        upFlt(activeTabId) { f ->
            val idx = f.sequences.indexOfFirst { it.id == id }.takeIf { it < f.sequences.size - 1 } ?: return@upFlt f
            val list = f.sequences.toMutableList()
            val a = list[idx]; val b = list[idx + 1]
            list[idx] = b.copy(priority = a.priority); list[idx + 1] = a.copy(priority = b.priority)
            f.copy(sequences = list)
        }
    }

    // ── Saved filters ────────────────────────────────────────────────
    fun saveFilter(tabId: String, name: String) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        val existing = savedFilters.find { it.name.trim().equals(cleanName, ignoreCase = true) }
        if (existing != null) {
            pendingDuplicateFilterSave = PendingDuplicateFilterSave(tabId, existing.id, existing.name, cleanName)
            return
        }
        val sf = snapshotFilter(tabId, "sf${System.nanoTime()}_${savedFilters.size}", cleanName) ?: return
        savedFilters = savedFilters + sf
        activeSavedFilterIds = activeSavedFilterIds + (tabId to sf.id)
        sfDialog = false; sfName = ""
        loadPendingFilterAfterSaving(tabId)
    }

    fun confirmReplaceDuplicateFilter() {
        val pending = pendingDuplicateFilterSave ?: return
        val updated = snapshotFilter(pending.tabId, pending.existingId, pending.requestedName) ?: return
        savedFilters = savedFilters.map { if (it.id == pending.existingId) updated else it }
        activeSavedFilterIds = activeSavedFilterIds + (pending.tabId to pending.existingId)
        pendingDuplicateFilterSave = null
        sfDialog = false
        sfName = ""
        loadPendingFilterAfterSaving(pending.tabId)
    }

    fun cancelDuplicateFilterSave() {
        pendingDuplicateFilterSave = null
    }

    fun requestLoadFilter(tabId: String, sf: SavedFilter) {
        val currentId = activeSavedFilterIds[tabId]
        val current = currentId?.let { id -> savedFilters.find { it.id == id } }
        if (current != null && !currentFilterMatches(tabId, current)) {
            pendingFilterLoad = PendingFilterLoad(tabId, sf.id, current.id)
            return
        }
        if (current == null && !currentFilterIsEmpty(tabId)) {
            pendingFilterLoad = PendingFilterLoad(tabId, sf.id, null)
            return
        }
        loadFilter(tabId, sf)
    }

    fun updateCurrentPresetAndLoadPending() {
        val pending = pendingFilterLoad ?: return
        val currentId = pending.currentFilterId ?: return
        val current = savedFilters.find { it.id == currentId } ?: return
        val updated = snapshotFilter(pending.tabId, current.id, current.name) ?: return
        savedFilters = savedFilters.map { if (it.id == currentId) updated else it }
        loadPendingFilter(pending)
    }

    fun cancelPendingFilterLoad() {
        pendingFilterLoad = null
    }

    fun discardPendingFilterChangesAndLoad() {
        val pending = pendingFilterLoad ?: return
        loadPendingFilter(pending)
    }

    fun beginSavePendingFilterAsNew() {
        val pending = pendingFilterLoad ?: return
        sfDialog = true
        sfTabId = pending.tabId
        val currentName = pending.currentFilterId?.let { id -> savedFilters.find { it.id == id }?.name }
        sfName = currentName?.let { "$it copy" } ?: ""
    }

    private fun snapshotFilter(tabId: String, id: String, name: String): SavedFilter? {
        val t = tab(tabId) ?: return null
        val f = t.filter
        return SavedFilter(
            id, name, f.levels, f.activeTags, f.kwText, f.kwRegex,
            f.mode, f.excludeTags, f.excludeKw, f.excludeKwRegex, f.highlighters, f.seqOn,
            f.kwInTag, f.kwInTagRegex, f.pkgPrefixes, f.pidTidFilter, f.sequences, f.messageRules,
            f.excludePkgPrefixes,
        )
    }

    private fun currentFilterMatches(tabId: String, sf: SavedFilter): Boolean {
        val current = snapshotFilter(tabId, sf.id, sf.name) ?: return false
        return current == sf
    }

    private fun currentFilterIsEmpty(tabId: String): Boolean {
        val t = tab(tabId) ?: return true
        return t.filter == Filter()
    }

    private fun loadPendingFilterAfterSaving(tabId: String) {
        val pending = pendingFilterLoad?.takeIf { it.tabId == tabId } ?: return
        loadPendingFilter(pending)
    }

    private fun loadPendingFilter(pending: PendingFilterLoad) {
        val target = savedFilters.find { it.id == pending.targetFilterId } ?: run {
            pendingFilterLoad = null
            return
        }
        pendingFilterLoad = null
        loadFilter(pending.tabId, target)
    }

    fun loadFilter(tabId: String, sf: SavedFilter) {
        upFlt(tabId) { _ -> sf.toFilter() }
        activeSavedFilterIds = activeSavedFilterIds + (tabId to sf.id)
    }

    fun loadFilterById(tabId: String, presetId: String): Boolean {
        val sf = savedFilters.find { it.id == presetId } ?: return false
        if (tab(tabId) == null) return false
        loadFilter(tabId, sf)
        return true
    }

    fun activeSavedFilterId(tabId: String): String? = activeSavedFilterIds[tabId]

    fun requestDeleteSF(id: String) {
        if (savedFilters.any { it.id == id }) pendingDeleteFilterId = id
    }

    fun cancelDeleteSF() {
        pendingDeleteFilterId = null
    }

    fun confirmDeleteSF() {
        val id = pendingDeleteFilterId ?: return
        deleteSF(id)
        pendingDeleteFilterId = null
    }

    private fun deleteSF(id: String) {
        savedFilters = savedFilters.filter { it.id != id }
        activeSavedFilterIds = activeSavedFilterIds.filterValues { it != id }
    }

    fun exportFilters(): String = buildString {
        appendLine("[")
        savedFilters.forEachIndexed { i, sf ->
            appendLine("  {")
            appendLine("    \"id\": \"${sf.id}\",")
            appendLine("    \"name\": ${sf.name.jsonStr()},")
            appendLine("    \"levels\": [${sf.levels.joinToString(",") { "\"${it.key}\"" }}],")
            appendLine("    \"mode\": \"${sf.mode.name}\",")
            appendLine("    \"activeTags\": [${sf.activeTags.joinToString(",") { it.jsonStr() }}],")
            appendLine("    \"excludeTags\": [${sf.excludeTags.joinToString(",") { it.jsonStr() }}],")
            appendLine("    \"kwText\": ${sf.kwText.jsonStr()},")
            appendLine("    \"kwRegex\": ${sf.kwRegex},")
            appendLine("    \"excludeKw\": ${sf.excludeKw.jsonStr()},")
            appendLine("    \"excludeKwRegex\": ${sf.excludeKwRegex},")
            appendLine(
                "    \"highlighters\": [${
                    sf.highlighters.joinToString(",") {
                        it.highlighterToken().jsonStr()
                    }
                }],"
            )
            appendLine("    \"seqOn\": ${sf.seqOn},")
            appendLine("    \"kwInTag\": ${sf.kwInTag.jsonStr()},")
            appendLine("    \"kwInTagRegex\": ${sf.kwInTagRegex},")
            appendLine("    \"pkgPrefixes\": [${sf.pkgPrefixes.joinToString(",") { it.jsonStr() }}],")
            appendLine("    \"excludePkgPrefixes\": [${sf.excludePkgPrefixes.joinToString(",") { it.jsonStr() }}],")
            appendLine("    \"pidTidFilter\": ${sf.pidTidFilter.jsonStr()},")
            appendLine("    \"sequences\": [${sf.sequences.joinToString(",") { it.sequenceToken().jsonStr() }}],")
            appendLine(
                "    \"messageRules\": [${
                    sf.messageRules.joinToString(",") {
                        it.messageRuleToken().jsonStr()
                    }
                }]"
            )
            append("  }")
            if (i < savedFilters.lastIndex) appendLine(",") else appendLine()
        }
        append("]")
    }

    fun importFilters(json: String) {
        val entries = json.jsonObjectArray().getOrElse { return }
        val imported = entries.mapNotNull { obj ->
            val name = obj.stringField("name") ?: return@mapNotNull null
            val levels =
                obj.stringArrayField("levels").mapNotNull { c -> LogLevel.entries.find { it.key.toString() == c } }
                    .toSet()
            val mode =
                runCatching { FilterMode.valueOf(obj.stringField("mode") ?: "TAGS") }.getOrElse { FilterMode.TAGS }
            val activeTags = obj.stringArrayField("activeTags").toSet()
            val excludeTags = obj.stringArrayField("excludeTags").toSet()
            val kwText = obj.stringField("kwText") ?: ""
            val kwRegex = obj.booleanField("kwRegex")
            val excludeKw = obj.stringField("excludeKw") ?: ""
            val excludeKwRegex = obj.booleanField("excludeKwRegex")
            val highlighters = obj.stringArrayField("highlighters").mapNotNull { it.highlighterFromToken() }
            val seqOn = obj["seqOn"] != false
            val kwInTag = obj.stringField("kwInTag") ?: ""
            val kwInTagRegex = obj.booleanField("kwInTagRegex")
            val pkgPrefixes = obj.stringArrayField("pkgPrefixes").toSet()
            val excludePkgPrefixes = obj.stringArrayField("excludePkgPrefixes").toSet()
            val pidTidFilter = obj.stringField("pidTidFilter") ?: ""
            val sequences = obj.stringArrayField("sequences").mapNotNull { it.sequenceFromToken() }
            val messageRules = obj.stringArrayField("messageRules").mapNotNull { it.messageRuleFromToken() }
            SavedFilter(
                "sf${System.currentTimeMillis()}_${name.hashCode()}", name,
                levels.ifEmpty { LogLevel.entries.toSet() }, activeTags, kwText, kwRegex, mode,
                excludeTags, excludeKw, excludeKwRegex, highlighters, seqOn,
                kwInTag, kwInTagRegex, pkgPrefixes, pidTidFilter, sequences, messageRules,
                excludePkgPrefixes,
            )
        }
        savedFilters = savedFilters + imported
    }

    // ── Row selection ────────────────────────────────────────────────
    fun selRow(tabId: String, id: Int, multi: Boolean, range: Boolean) = upTab(tabId) { t ->
        val n = when {
            multi -> if (id in t.selected) t.selected - id else t.selected + id
            range -> {
                // Use the actual visible items list so shift-click doesn't expand through
                // collapsed blocks (same as drag-select which uses visibleIds from LogViewer).
                // Prefer the summary LogViewer already computed; recompute only when no viewer
                // has reported one yet (e.g. headless/control-server callers).
                val visIds = visibleItemsByTab[tabId]?.allIds
                    ?: computeItems(t, true).let { items -> IntArray(items.size) { logItemEntryId(items[it]) } }
                val last = t.selected.lastOrNull { visIds.contains(it) } ?: t.selected.maxOrNull()
                if (last == null) {
                    setOf(id)
                } else {
                    val a = visIds.indexOf(last)
                    val b = visIds.indexOf(id)
                    if (a >= 0 && b >= 0) (minOf(a, b)..maxOf(a, b)).map { visIds[it] }.toSet() else t.selected + id
                }
            }

            else -> if (t.selected == setOf(id)) emptySet() else setOf(id)
        }
        t.copy(selected = n)
    }

    fun selRowRange(tabId: String, fromId: Int, toId: Int) = upTab(tabId) { t ->
        val ids = t.logData.filter { passesFilter(it, t.filter) }.map { it.id }.ifEmpty { t.logData.map { it.id } }
        val a = ids.indexOf(fromId)
        val b = ids.indexOf(toId)
        if (a < 0 || b < 0) return@upTab t
        t.copy(selected = ids.subList(minOf(a, b), maxOf(a, b) + 1).toSet())
    }

    fun setSelectedRows(tabId: String, ids: List<Int>) = upTab(tabId) { t ->
        t.copy(selected = ids.toSet())
    }

    fun clearSelection(tabId: String) = upTab(tabId) { it.copy(selected = emptySet()) }

    fun selectAll(tabId: String) {
        val t = tab(tabId) ?: return
        val ids = visibleItemsByTab[tabId]?.rowIds?.toList()
            ?: computeItems(t, true).filterIsInstance<LogItem.Row>().map { it.entry.id }
        setSelectedRows(tabId, ids)
    }

    fun requestAnnotationNavigation(ownerTabId: String, block: AnnBlock.LogRef) {
        val targetTabId = block.sourceTabId ?: ownerTabId
        if (block.logIds.isEmpty() || tab(targetTabId) == null) return
        if (compareMode && targetTabId != activeTabId) {
            compareTabId = targetTabId
        } else {
            activateTab(targetTabId)
        }
        setSelectedRows(targetTabId, block.logIds)
        annotationNavigationCounter += 1
        pendingAnnotationNavigation = AnnotationNavigationRequest(annotationNavigationCounter, targetTabId, block.logIds)
    }

    fun consumeAnnotationNavigation(id: Long) {
        if (pendingAnnotationNavigation?.id == id) pendingAnnotationNavigation = null
    }

    // Reuses the same jump-to-log-lines mechanism as annotation navigation — a crash-panel entry
    // is just "select and scroll to a log id", the exact thing pendingAnnotationNavigation already
    // does (including auto-expanding whatever collapsed header, sequence or stack-trace, owns it).
    fun requestCrashNavigation(tabId: String, logId: Int) {
        if (tab(tabId) == null) return
        if (compareMode && tabId != activeTabId) {
            compareTabId = tabId
        } else {
            activateTab(tabId)
        }
        setSelectedRows(tabId, listOf(logId))
        annotationNavigationCounter += 1
        pendingAnnotationNavigation = AnnotationNavigationRequest(annotationNavigationCounter, tabId, listOf(logId))
    }

    // ── Sequence expand/collapse ─────────────────────────────────────
    fun toggleGroup(tabId: String, gid: String) = upTab(tabId) { t ->
        t.copy(expanded = if (gid in t.expanded) t.expanded - gid else t.expanded + gid)
    }

    private fun visibleExpandableGroupIds(t: LogTab): Set<String> {
        var expanded = t.expanded
        while (true) {
            fun idsFrom(applyFilter: Boolean): Set<String> =
                computeItems(t.copy(expanded = expanded), applyFilter)
                    .mapNotNull { item ->
                        when (item) {
                            is LogItem.SeqHeader -> item.gid
                            is LogItem.ManualHeader -> item.gid
                            is LogItem.StackTraceHeader -> item.gid
                            is LogItem.Row -> null
                        }
                    }
                    .toSet()

            val next = expanded + idsFrom(applyFilter = true) +
                if (t.showUnfiltered) idsFrom(applyFilter = false) else emptySet()
            if (next == expanded) return expanded
            expanded = next
        }
    }

    fun expandAll(tabId: String) {
        val t = tab(tabId) ?: return
        if (!t.largeFileMode) {
            upTab(tabId) { it.copy(expanded = visibleExpandableGroupIds(it)) }
            return
        }
        // The iterative reveal loop runs computeItems repeatedly — seconds of work on a large
        // tab, so it can't stay on the UI thread. Same background-apply pattern (stateLock) as
        // appendTailedLines; if the filter changed mid-computation the result is a harmless
        // superset of gids, exactly as if the user had expanded before filtering.
        ioScope.launch(Dispatchers.Default) {
            val expanded = visibleExpandableGroupIds(t)
            ensureActive()
            synchronized(stateLock) {
                upTab(tabId) { it.copy(expanded = expanded) }
            }
        }
    }

    fun collapseAll(tabId: String) = upTab(tabId) { it.copy(expanded = emptySet()) }

    fun toggleUnfiltered(tabId: String) = upTab(tabId) { it.copy(showUnfiltered = !it.showUnfiltered) }

    // ── Annotations (block model) ────────────────────────────────────
    fun requestAddAnn(sourceTabId: String, logIds: List<Int>) {
        val targetTabId = if (compareMode && sourceTabId != activeTabId) activeTabId else sourceTabId
        val crossFile = targetTabId != sourceTabId
        addAnnRequest = AddAnnRequest(
            targetTabId = targetTabId,
            sourceTabId = sourceTabId,
            logIds = logIds,
            sourceFilename = if (crossFile) tab(sourceTabId)?.filename else null,
        )
        ctx = null
    }

    fun confirmAddAnn(
        targetTabId: String,
        sourceTabId: String,
        logIds: List<Int>,
        caption: String,
        sourceFilename: String?
    ) {
        val crossFile = sourceTabId != targetTabId
        val sourceEntries = if (crossFile) {
            val rmap = tab(sourceTabId)?.rmap ?: emptyMap()
            logIds.sorted().mapNotNull { rmap[it] }
        } else {
            null
        }
        upAnn(targetTabId) { t ->
            val block = AnnBlock.LogRef(
                id = "r${System.currentTimeMillis()}",
                logIds = logIds.sorted(),
                caption = caption,
                sourceTabId = if (crossFile) sourceTabId else null,
                sourceFilename = if (crossFile) sourceFilename else null,
                sourceEntries = sourceEntries,
            )
            t.copy(annotations = t.annotations.copy(blocks = t.annotations.blocks + block))
        }
        addAnnRequest = null
    }

    fun addNoteBlock(tabId: String, afterId: String? = null) {
        addNoteBlock(tabId, "", afterId)
    }

    fun addNoteBlock(tabId: String, text: String, afterId: String? = null): String? {
        val id = "n${System.nanoTime()}"
        upAnn(tabId) { t ->
            val note = AnnBlock.Note(id, text)
            val blocks = t.annotations.blocks.toMutableList()
            val idx =
                if (afterId != null) (blocks.indexOfFirst { it.id == afterId } + 1).coerceAtLeast(0) else blocks.size
            blocks.add(idx, note)
            t.copy(annotations = t.annotations.copy(blocks = blocks))
        }
        return id.takeIf { tab(tabId)?.annotations?.blocks?.any { block -> block.id == id } == true }
    }

    fun addLogRefBlock(tabId: String, logIds: List<Int>, caption: String = ""): String? {
        val t = tab(tabId) ?: return null
        val cleanIds = logIds.distinct().sorted().filter { it in t.rmap }
        if (cleanIds.isEmpty()) return null
        val id = "r${System.nanoTime()}"
        upAnn(tabId) { tab ->
            val block = AnnBlock.LogRef(id = id, logIds = cleanIds, caption = caption)
            tab.copy(annotations = tab.annotations.copy(blocks = tab.annotations.blocks + block))
        }
        return id
    }

    fun updateBlock(tabId: String, blockId: String, newText: String) = upAnn(tabId) { t ->
        t.copy(annotations = t.annotations.copy(blocks = t.annotations.blocks.map { b ->
            when {
                b.id != blockId -> b
                b is AnnBlock.Note -> b.copy(text = newText)
                b is AnnBlock.LogRef -> b.copy(caption = newText)
                else -> b
            }
        }))
    }

    fun removeBlock(tabId: String, blockId: String) = upAnn(tabId) { t ->
        t.copy(annotations = t.annotations.copy(blocks = t.annotations.blocks.filter { it.id != blockId }))
    }

    fun moveBlock(tabId: String, blockId: String, delta: Int) = upAnn(tabId) { t ->
        val list = t.annotations.blocks.toMutableList()
        val idx = list.indexOfFirst { it.id == blockId }.takeIf { it >= 0 } ?: return@upAnn t
        val to = (idx + delta).coerceIn(0, list.lastIndex)
        val item = list.removeAt(idx); list.add(to, item)
        t.copy(annotations = t.annotations.copy(blocks = list))
    }

    fun setPrefix(tabId: String, v: String) = upAnn(tabId) { t -> t.copy(annotations = t.annotations.copy(prefix = v)) }

    fun setSuffix(tabId: String, v: String) = upAnn(tabId) { t -> t.copy(annotations = t.annotations.copy(suffix = v)) }

    fun toggleMd(tabId: String) = upTab(tabId) { it.copy(showAnnMd = !it.showAnnMd) }

    // ── Context menu shortcuts ───────────────────────────────────────

    // Given raw selected text (which may span ts/pid/tid/level/tag/msg),
    // extract the message portion suitable for pattern matching in entry.msg.
    private fun extractMsgText(sel: String, entry: LogEntry): String {
        val s = sel.trim()
        if (s.isBlank()) return entry.msg
        // Selection ends with the full message but has leading noise: return just the message
        if (s.endsWith(entry.msg) && s.length > entry.msg.length) return entry.msg
        // Selection starts with "tag: " prefix: strip it
        val prefix = "${entry.tag}: "
        if (s.startsWith(prefix)) {
            val rest = s.removePrefix(prefix)
            if (rest.isNotBlank()) return rest
        }
        return s
    }

    fun addTagFilterFromCtx() {
        val c = ctx ?: return; toggleTag(c.tabId, tab(c.tabId)?.rmap?.get(c.entryId)?.tag ?: return); ctx = null
    }

    fun addExcludeTagFromCtx() {
        val c = ctx ?: return; toggleExcludeTag(c.tabId, tab(c.tabId)?.rmap?.get(c.entryId)?.tag ?: return); ctx = null
    }

    fun addHlFromCtx() {
        val c = ctx ?: return
        val entry = tab(c.tabId)?.rmap?.get(c.entryId) ?: return
        val text = if (c.selText.isBlank()) entry.msg else extractMsgText(c.selText, entry)
        addHl(c.tabId, text, false, newHlColor); ctx = null
    }

    fun addHlTagFromCtx() {
        val c = ctx ?: return
        val tag = tab(c.tabId)?.rmap?.get(c.entryId)?.tag ?: return
        addHl(c.tabId, tag, false, newHlColor); ctx = null
    }

    fun addSeqFromCtx() {
        val c = ctx ?: return
        val entry = tab(c.tabId)?.rmap?.get(c.entryId) ?: return
        val text = if (c.selText.isBlank()) entry.msg else extractMsgText(c.selText, entry)
        addSequence(text, false, newSeqColor, entry.tag); ctx = null
    }

    fun collapseToStartFromCtx() {
        val c = ctx ?: return
        val entry = tab(c.tabId)?.rmap?.get(c.entryId) ?: return
        addManualCollapse(c.tabId, entry.id, ManualCollapseDirection.TO_START)
        ctx = null
    }

    fun collapseToEndFromCtx() {
        val c = ctx ?: return
        val entry = tab(c.tabId)?.rmap?.get(c.entryId) ?: return
        addManualCollapse(c.tabId, entry.id, ManualCollapseDirection.TO_END)
        ctx = null
    }

    private fun addManualCollapse(tabId: String, anchorId: Int, direction: ManualCollapseDirection) =
        upTab(tabId) { t ->
            val id = "mc${System.currentTimeMillis()}_${anchorId}_${direction.name}"
            t.copy(manualBlocks = t.manualBlocks + ManualCollapseBlock(id, anchorId, direction))
        }

    fun toggleManualCollapse(tabId: String, id: String) = upTab(tabId) { t ->
        t.copy(manualBlocks = t.manualBlocks.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
    }

    fun removeManualCollapse(tabId: String, id: String) = upTab(tabId) { t ->
        t.copy(manualBlocks = t.manualBlocks.filterNot { it.id == id }, expanded = t.expanded - id)
    }

    fun setSequenceStartFromCtx() {
        val c = ctx ?: return
        val entry = tab(c.tabId)?.rmap?.get(c.entryId) ?: return
        val text = if (c.selText.isBlank()) entry.msg else extractMsgText(c.selText, entry)
        pendingSequenceStart = PendingSequenceStart(text, entry.tag)
        ctx = null
    }

    fun completeSequenceEndFromCtx() {
        val c = ctx ?: return
        val start = pendingSequenceStart ?: return
        val entry = tab(c.tabId)?.rmap?.get(c.entryId) ?: return
        val text = if (c.selText.isBlank()) entry.msg else extractMsgText(c.selText, entry)
        addSequence(start.text, false, newSeqColor, start.tag, text, false, entry.tag)
        pendingSequenceStart = null
        ctx = null
    }

    fun addNegKwFromCtx() {
        val c = ctx ?: return
        val entry = tab(c.tabId)?.rmap?.get(c.entryId) ?: return
        val text = if (c.selText.isBlank()) entry.msg else extractMsgText(c.selText, entry)
        setExcludeKw(c.tabId, text); ctx = null
    }

    fun hideMessagesLikeCtx() {
        val c = ctx ?: return
        val entry = tab(c.tabId)?.rmap?.get(c.entryId) ?: return
        val text = if (c.selText.isBlank()) entry.msg else extractMsgText(c.selText, entry)
        addMessageRule(c.tabId, include = false, pattern = text, regex = false, tag = entry.tag, packagePrefix = null)
        ctx = null
    }

    fun showOnlyMessagesLikeCtx() {
        val c = ctx ?: return
        val entry = tab(c.tabId)?.rmap?.get(c.entryId) ?: return
        val text = if (c.selText.isBlank()) entry.msg else extractMsgText(c.selText, entry)
        // Unscoped, matching the manual "+" message-rule flow exactly: a tag-scoped positive
        // rule only narrows within its own tag and lets every other tag fall through to the
        // base tag/keyword filter (by design — see scopedIncludeMessageRuleNarrowsOnlyMatchingTag),
        // which looks like "no filter at all" when no base tag filter is active — not what
        // "show only messages like this" implies.
        addMessageRule(c.tabId, include = true, pattern = text, regex = false, tag = null, packagePrefix = null)
        ctx = null
    }

    fun addKwFilterFromCtx() {
        val c = ctx ?: return
        val entry = tab(c.tabId)?.rmap?.get(c.entryId) ?: return
        val text = if (c.selText.isBlank()) entry.msg else extractMsgText(c.selText, entry)
        setFilterMode(c.tabId, FilterMode.KEYWORD)
        setKw(c.tabId, text)
        ctx = null
    }

    fun copySelectedLines(tabId: String) {
        copySelectedLines(tabId, null)
    }

    fun copySelectedLines(tabId: String, explicitIds: Set<Int>?) {
        copyToClipboard(selectedLinesText(tabId, explicitIds))
    }

    fun selectedLinesText(tabId: String, explicitIds: Set<Int>? = null): String {
        val t = tab(tabId) ?: return ""
        val ids = explicitIds ?: t.selected
        return ids.sorted().mapNotNull { id -> t.rmap[id] }.joinToString("\n") { e ->
            val pid = if (e.pid > 0) "  ${e.pid.toString().padStart(5)} ${e.tid.toString().padStart(5)}" else ""
            "${e.ts}$pid  ${e.level.key}  ${e.tag}: ${e.msg}"
        }
    }

    // ── Tab management ───────────────────────────────────────────────
    fun addTab() {
        val n = tabCounter++
        val t = emptyWorkspaceTab().copy(id = "t$n")
        tabs = tabs + t; activeTabId = t.id
    }

    fun activateTab(tabId: String) {
        if (tabs.any { it.id == tabId }) activeTabId = tabId
    }

    fun activateOverflowTab(tabId: String) {
        val tab = tabs.find { it.id == tabId } ?: return
        tabs = tabs.filter { it.id != tabId } + tab
        activeTabId = tabId
    }

    fun reorderTabs(fromId: String, beforeId: String?) {
        val from = tabs.find { it.id == fromId } ?: return
        val without = tabs.filter { it.id != fromId }
        val idx = beforeId?.let { id -> without.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: without.size
        tabs = without.take(idx) + from + without.drop(idx)
    }

    fun closeTab(tabId: String) {
        activeLoads.remove(tabId)?.cancel()
        activeTails.remove(tabId)?.job?.cancel()
        visibleItemsByTab.remove(tabId)
        val next = tabs.filter { it.id != tabId }
        if (activeTabId == tabId) activeTabId = next.lastOrNull()?.id ?: ""
        if (compareTabId == tabId) compareTabId = next.firstOrNull()?.id ?: ""
        if (next.isEmpty()) compareMode = false
        logViewerScrollStateStore.removeTab(tabId)
        tabs = next
    }

    // Ships "merge already-open tabs" (v1) — data's already in memory, no re-parsing needed.
    // Runs on ioScope for consistency with the rest of the codebase's bias toward backgrounding
    // anything touching logData, even though mergeLogs() itself is a pure in-memory sort.
    fun mergeTabs(tabIds: List<String>, newTabName: String = "Merged") {
        val sources = tabIds.mapNotNull { id -> tab(id)?.let { MergeSourceFile(it.filename, it.logData) } }
        if (sources.size < 2) return
        val n = tabCounter++
        beginLoading("Merging logs...")
        ioScope.launch {
            try {
                val merged = mergeLogs(sources)
                ensureActive()
                val t = mkTab("t$n", newTabName, merged)
                synchronized(stateLock) {
                    ensureActive()
                    tabs = tabs + t
                    activeTabId = t.id
                }
            } finally {
                finishLoading()
            }
        }
    }

    // Session-only (confirmed): tailing state never persists across a restart — tab.tailing
    // simply isn't written to the autosave token, so it always comes back false. Only tabs backed
    // by a real, currently-existing file path can be tailed (not a zip-extracted or merged tab).
    fun startTailing(tabId: String) {
        if (activeTails.containsKey(tabId)) return
        val t = tab(tabId) ?: return
        val path = t.sourcePath ?: return
        val file = File(path)
        if (!file.isFile) return
        val tailer = FileTailer(file, onNewLines = { newLines -> appendTailedLines(tabId, newLines) })
        val job = tailer.start(ioScope)
        activeTails[tabId] = ActiveTail(tailer, job)
        upTab(tabId) { it.copy(tailing = true) }
    }

    fun stopTailing(tabId: String) {
        activeTails.remove(tabId)?.job?.cancel()
        upTab(tabId) { it.copy(tailing = false) }
        // Content-triggered autosave is suppressed while any tab is actively tailing (see the
        // LaunchedEffect in App.kt) to avoid rewriting a fast-growing logData every ~400ms —
        // explicitly save now that this tab has settled.
        autosaveNow()
    }

    // Runs on whichever thread FileTailer's coroutine flushes from (ioScope / Dispatchers.IO),
    // unlike most upTab callers which are UI-thread-only — wrapped in stateLock so a tailing
    // flush can't race and lose an update against another concurrent background mutation
    // (another tab's tailing flush, or an in-flight openFile/mergeTabs). This does NOT protect
    // against racing a same-instant UI-thread mutation (toggleGroup, selRow, ...) — those already
    // aren't stateLock-guarded anywhere in this class; tailing only closes the background-vs-
    // background gap, matching the existing openFile/mergeTabs precedent.
    private fun appendTailedLines(tabId: String, newRawLines: List<String>) {
        if (newRawLines.isEmpty()) return
        synchronized(stateLock) {
            val t = tab(tabId) ?: return
            val nextId = (t.logData.maxOfOrNull { it.id } ?: 0) + 1
            val newEntries = parseLogcatLines(newRawLines.asSequence(), startId = nextId)
            tabs = tabs.map { cur ->
                if (cur.id == tabId) {
                    val nextData = cur.logData + newEntries
                    cur.copy(
                        logData = nextData,
                        rmap = mkRmap(nextData),
                        analysis = buildLogAnalysis(nextData),
                    )
                } else {
                    cur
                }
            }
        }
    }

    fun openFile(file: File): String? {
        val path = file.absolutePath
        recentFiles = (listOf(path) + recentFiles.filter { it != path }).take(30)
        recentMenuOpen = false
        autosaveNow()
        rememberAutoExportedNoteFor(file.name)
        // Switch to existing tab if this file is already open
        val existing = tabs.find { it.sourcePath == path }
        if (existing != null) {
            activeTabId = existing.id; return existing.id
        }
        val n = tabCounter++  // capture on calling thread before launching
        val tabId = "t$n"
        val largeFile = file.length() >= LARGE_FILE_MODE_BYTES
        beginLoading()
        val job = ioScope.launch(start = CoroutineStart.LAZY) {
            try {
                val logData = runCatching { parser(file) }.getOrElse { emptyList() }
                ensureActive()
                val prefixLabel = settings.annotationPrefixLabel.trim().ifBlank { "From" }
                val t = mkTab(tabId, file.name, logData)
                    .copy(
                        sourcePath = path,
                        annotations = Annotations(prefix = "$prefixLabel ${file.name}"),
                        largeFileMode = largeFile,
                    )
                synchronized(stateLock) {
                    ensureActive()
                    tabs = tabs + t
                    activeTabId = t.id
                }
            } finally {
                activeLoads.remove(tabId)
                finishLoading()
            }
        }
        activeLoads[tabId] = job
        job.start()
        return tabId
    }

    // A single candidate auto-opens (the common case — most bug reports bundle one logcat
    // buffer). 2+ candidates show a picker rather than guessing: Android 11+ bug reports often
    // bundle multiple buffers (main/system/crash/events), and silently picking one wrong is worse
    // than one extra click. 0 candidates is a silent no-op — the zip just isn't a bug report.
    fun openZipFile(file: File) {
        val candidates = listArchiveLogCandidates(file)
        when {
            candidates.size == 1 -> openZipEntry(file, candidates.first())
            candidates.size > 1 -> pendingZipPicker = PendingZipPicker(file, candidates)
        }
    }

    fun openZipEntries(zipFile: File, selected: List<ZipLogCandidate>) {
        selected.forEach { openZipEntry(zipFile, it) }
        pendingZipPicker = null
    }

    fun cancelZipPicker() {
        pendingZipPicker = null
    }

    // Jar-URL-style "!" separator for the source path — for display/dedup only (a zip-extracted
    // tab can't be re-opened from this path directly), matching openFile()'s sourcePath dedup.
    private fun openZipEntry(zipFile: File, candidate: ZipLogCandidate) {
        val path = "${zipFile.absolutePath}!${candidate.entryPath}"
        val existing = tabs.find { it.sourcePath == path }
        if (existing != null) {
            activateTab(existing.id); return
        }
        val n = tabCounter++
        val tabId = "t$n"
        val largeFile = candidate.sizeBytes >= LARGE_FILE_MODE_BYTES
        beginLoading()
        val job = ioScope.launch(start = CoroutineStart.LAZY) {
            try {
                val logData = runCatching { extractCandidate(zipFile, candidate) }.getOrElse { emptyList() }
                ensureActive()
                val prefixLabel = settings.annotationPrefixLabel.trim().ifBlank { "From" }
                val t = mkTab(tabId, candidate.displayName, logData)
                    .copy(
                        sourcePath = path,
                        annotations = Annotations(prefix = "$prefixLabel ${candidate.displayName}"),
                        largeFileMode = largeFile,
                    )
                synchronized(stateLock) {
                    ensureActive()
                    tabs = tabs + t
                    activeTabId = t.id
                }
            } finally {
                activeLoads.remove(tabId)
                finishLoading()
            }
        }
        activeLoads[tabId] = job
        job.start()
    }

    // ── Copy / Save ──────────────────────────────────────────────────
    fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    fun copyAnn(tabId: String) {
        tab(tabId)?.let { copyToClipboard(buildMd(it, settings)) }
    }

    fun exportAnalysisTo(tabId: String, file: File): Boolean {
        val t = tab(tabId) ?: return false
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(buildMd(t, settings))
        }.isSuccess
    }

    fun exportFilteredTo(tabId: String, file: File, csv: Boolean): Boolean {
        val t = tab(tabId) ?: return false
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(if (csv) buildFilteredCsv(t) else buildFilteredTxt(t))
        }.isSuccess
    }

    fun saveAnnotationsTo(tabId: String, file: File): Boolean {
        val t = tab(tabId) ?: return false
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(t.annotations.annotationsToken())
        }.isSuccess
    }

    fun loadAnnotationsFrom(tabId: String, file: File): Boolean {
        val annotations = runCatching { file.readText().annotationsFromToken() }.getOrNull() ?: return false
        upAnn(tabId) { t -> t.copy(annotations = annotations) }
        return tab(tabId)?.annotations == annotations
    }

    fun saveAnalysis(tabId: String) {
        val t = tab(tabId) ?: return
        val dlg = FileDialog(null as Frame?, "Save Analysis", FileDialog.SAVE).apply {
            file = analysisNoteMarkdownName(t.filename)
            settings.defaultSaveDir?.let { directory = it }
            isVisible = true
        }
        val path = dlg.file ?: return
        val dir = dlg.directory ?: return
        settings = settings.copy(defaultSaveDir = dir)
        val saved = File(dir, path)
        ioScope.launch {
            runCatching {
                saved.writeText(buildMd(t, settings))
                File(saved.parent, saved.nameWithoutExtension + ".ann").writeText(t.annotations.annotationsToken())
                rememberRecentNote(saved)
            }
        }
    }

    fun exportFilteredTxt(tabId: String) {
        val t = tab(tabId) ?: return
        val dlg = FileDialog(null as Frame?, "Export Filtered Log", FileDialog.SAVE).apply {
            file = t.filename.substringBeforeLast('.') + "_filtered.txt"
            settings.defaultSaveDir?.let { directory = it }
            isVisible = true
        }
        val path = dlg.file ?: return
        val dir = dlg.directory ?: return
        settings = settings.copy(defaultSaveDir = dir)
        val saved = File(dir, path)
        ioScope.launch { runCatching { saved.writeText(buildFilteredTxt(t)) } }
    }

    fun exportFilteredCsv(tabId: String) {
        val t = tab(tabId) ?: return
        val dlg = FileDialog(null as Frame?, "Export Filtered Log", FileDialog.SAVE).apply {
            file = t.filename.substringBeforeLast('.') + "_filtered.csv"
            settings.defaultSaveDir?.let { directory = it }
            isVisible = true
        }
        val path = dlg.file ?: return
        val dir = dlg.directory ?: return
        settings = settings.copy(defaultSaveDir = dir)
        val saved = File(dir, path)
        ioScope.launch { runCatching { saved.writeText(buildFilteredCsv(t)) } }
    }

    fun openNoteFile(tabId: String, file: File) {
        if (!file.exists()) {
            removeRecentNote(file)
            recentNotesMenuOpen = false
            return
        }
        rememberRecentNote(file)
        recentNotesMenuOpen = false
        // Prefer .ann sidecar — restores exact blocks and structure
        val sidecar = File(file.parent, file.nameWithoutExtension + ".ann")
        if (sidecar.exists()) {
            val annotations = runCatching { sidecar.readText().annotationsFromToken() }.getOrNull()
            if (annotations != null) {
                upTab(tabId) { t -> t.copy(annotations = annotations) }
                return
            }
        }
        // Fallback: load raw text as a single note block (e.g. plain .md without sidecar)
        val text = runCatching { file.readText() }.getOrElse { return }
        upTab(tabId) { t ->
            val block = AnnBlock.Note("n${System.currentTimeMillis()}", text)
            t.copy(annotations = t.annotations.copy(blocks = t.annotations.blocks + block))
        }
    }

    fun openNoteFileAsync(tabId: String, file: File) {
        ioScope.launch { openNoteFile(tabId, file) }
    }

    private fun userNotesDir(): File? {
        val configuredDir = settings.defaultSaveDir?.let(::File) ?: return null
        return configuredDir.takeIf { it.exists() && it.isDirectory }
    }

    private fun activeNotesDir(): File = userNotesDir() ?: notesDir

    private fun noteLookupDirs(): List<File> {
        return listOfNotNull(
            userNotesDir(),
            notesDir,
            DesktopStorage.legacyNotesDir(),
        ).distinctBy { it.absolutePath }
    }

    private fun rememberAutoExportedNoteFor(filename: String) {
        val legacySafeName = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val noteNames = listOf(
            analysisNoteMarkdownName(filename),
            "${legacySafeName}_notes.md",
        )
        val noteFile = noteLookupDirs()
            .asSequence()
            .flatMap { dir -> noteNames.asSequence().map { name -> File(dir, name) } }
            .firstOrNull { it.exists() } ?: return
        if (noteFile.exists()) rememberRecentNote(noteFile)
    }

    private fun rememberRecentNote(file: File) {
        val absPath = file.absolutePath
        recentNotes = (listOf(absPath) + recentNotes.filter { it != absPath }).take(30)
        autosaveNow()
    }

    private fun removeRecentNote(file: File) {
        val absPath = file.absolutePath
        val next = recentNotes.filter { it != absPath }
        if (next == recentNotes) return
        recentNotes = next
        autosaveNow()
    }

    private fun pruneMissingRecentNotes() {
        val next = recentNotes.filter { File(it).exists() }
        if (next == recentNotes) return
        recentNotes = next
        autosaveNow()
    }

    fun toggleRecentNotesMenu() {
        if (!recentNotesMenuOpen) pruneMissingRecentNotes()
        recentNotesMenuOpen = !recentNotesMenuOpen
    }

    private fun autoExportAnnotations(tab: LogTab) {
        if (!autoExportNotes || !settings.autoExportNotes || tab.annotations.blocks.isEmpty()) return
        ioScope.launch {
            runCatching {
                val targetDir = activeNotesDir()
                targetDir.mkdirs()
                val mdFile = File(targetDir, analysisNoteMarkdownName(tab.filename))
                mdFile.writeText(buildMd(tab, settings))
                // Sidecar stores full block state for restoration
                File(targetDir, "${mdFile.nameWithoutExtension}.ann").writeText(tab.annotations.annotationsToken())
                rememberRecentNote(mdFile)
            }
        }
    }

    // Annotation-aware tab updater — auto-exports after any annotation change.
    private fun upAnn(tabId: String, fn: (LogTab) -> LogTab) {
        upTab(tabId, fn)
        tab(tabId)?.let { autoExportAnnotations(it) }
    }

    fun pickSaveFolder() {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dlg = FileDialog(null as Frame?, "Choose Save Folder", FileDialog.LOAD)
            dlg.isVisible = true
            val dir = dlg.directory ?: return
            val file = dlg.file ?: return
            val chosen = java.io.File(dir, file)
            updateSettings { it.copy(defaultSaveDir = chosen.absolutePath) }
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }

    fun exportFiltersToFile() {
        val dlg = FileDialog(null as Frame?, "Export Filters", FileDialog.SAVE).apply {
            file = "openlog_filters.json"; isVisible = true
        }
        val path = dlg.file ?: return
        val dir = dlg.directory ?: return
        val file = File(dir, path)
        ioScope.launch { runCatching { file.writeText(exportFilters()) } }
    }

    fun importFiltersFromFile() {
        val dlg = FileDialog(null as Frame?, "Import Filters", FileDialog.LOAD).apply {
            setFilenameFilter { _, n -> n.endsWith(".json") }; isVisible = true
        }
        val path = dlg.file ?: return
        val dir = dlg.directory ?: return
        importFiltersFromFileAsync(File(dir, path))
    }

    fun importFiltersFromFile(file: File) {
        runCatching { importFilters(file.readText()) }
    }

    fun importFiltersFromFileAsync(file: File) {
        ioScope.launch { importFiltersFromFile(file) }
    }

    // ── Archive cache ────────────────────────────────────────────────
    fun refreshArchiveCacheInfo() {
        archiveCacheSizeBytes = archiveCacheDir.totalFileSize() + notesDir.totalFileSize()
    }

    fun clearArchiveCache() {
        archiveCacheDir.listFiles().orEmpty().forEach { file -> runCatching { file.deleteRecursively() } }
        notesDir.listFiles().orEmpty().forEach { file -> runCatching { file.deleteRecursively() } }
        pruneMissingRecentNotes()
        refreshArchiveCacheInfo()
    }

    fun requestClearCache() {
        refreshArchiveCacheInfo()
        cacheClearConfirmOpen = true
    }

    fun cancelClearCache() {
        cacheClearConfirmOpen = false
    }

    fun confirmClearCache() {
        cacheClearConfirmOpen = false
        clearArchiveCache()
    }

    // ── Autosave ─────────────────────────────────────────────────────
    fun autosaveNow() {
        runCatching {
            autosaveFile.parentFile?.mkdirs()
            autosaveFile.writeText(serializeAutosave())
        }
    }

    private fun restoreAutosave() {
        if (!autosaveFile.exists()) return
        runCatching {
            val lines = autosaveFile.readLines()
            if (lines.firstOrNull() != "openLog2-cache-v1") return
            val (keyLines, tabLines) = splitAutosaveLines(lines.drop(1))
            keyLines.forEach { line ->
                val key = line.substringBefore('\t')
                val value = line.substringAfter('\t', "")
                restoreAutosaveKey(key, value)
            }
            restoreTabsFromAutosave(tabLines)
            pruneMissingRecentNotes()
        }
    }

    private fun splitAutosaveLines(lines: List<String>): Pair<List<String>, List<String>> {
        val idx = lines.indexOf("tabs")
        return if (idx == -1) Pair(lines, emptyList()) else Pair(lines.take(idx), lines.drop(idx + 1))
    }

    private fun restoreAutosaveKey(key: String, value: String) {
        when (key) {
            "settings" -> settingsFromToken(value.unb64())?.let { settings = it }
            "active" -> activeTabId = value.unb64()
            "compare" -> restoreCompareState(value.unb64())
            "sequences" -> { /* legacy global-sequences key — migrated into tab filter; ignored on load */ }
            "saved" -> importFilters(value.unb64())
            "activeFilters" -> activeSavedFilterIds = activeFilterMapFromToken(value.unb64())
            "recent" -> recentFiles = value.pathTokenList()
            "recentNotes" -> recentNotes = value.pathTokenList()
            "filterPanel" -> fpState.restoreFilterPanelToken(value.unb64())
        }
    }

    private fun restoreTabsFromAutosave(tabLines: List<String>) {
        val shells = tabLines.mapNotNull { it.removePrefix("tab\t").tabShellFromToken() }
        tabs = shells.map { it.tab }
        if (tabs.none { it.id == activeTabId }) activeTabId = tabs.firstOrNull()?.id ?: ""
        if (tabs.none { it.id == compareTabId }) compareTabId =
            tabs.getOrNull(1)?.id ?: tabs.firstOrNull()?.id ?: ""
        tabCounter = (tabs.mapNotNull { it.id.removePrefix("t").toIntOrNull() }.maxOrNull() ?: 0) + 1
        // Shells restore synchronously with every user-visible piece of metadata (filter,
        // annotations, expanded/manual blocks); file contents parse in the background. This used
        // to call parseLogcat inline during AppState init — i.e. inside the first composition —
        // so a large tab left open at last quit froze the app for the whole parse on every launch.
        shells.forEach { shell -> scheduleRestoredTabLoad(shell.tab.id, shell.source) }
    }

    private fun scheduleRestoredTabLoad(tabId: String, source: RestoredTabSource) {
        beginLoading("Restoring session...")
        val job = ioScope.launch(start = CoroutineStart.LAZY) {
            try {
                val logData = runCatching { source.load(parser) }.getOrElse { emptyList() }
                ensureActive()
                val rmap = mkRmap(logData)
                val analysis = buildLogAnalysis(logData)
                synchronized(stateLock) {
                    ensureActive()
                    upTab(tabId) { it.copy(logData = logData, rmap = rmap, analysis = analysis) }
                }
            } finally {
                activeLoads.remove(tabId)
                finishLoading()
            }
        }
        activeLoads[tabId] = job
        job.start()
    }

    private fun serializeAutosave(): String = buildString {
        appendLine("openLog2-cache-v1")
        appendLine("settings\t${settings.settingsToken().b64()}")
        appendLine("active\t${activeTabId.b64()}")
        appendLine("compare\t${compareStateToken().b64()}")
        appendLine("saved\t${exportFilters().b64()}")
        appendLine("activeFilters\t${activeFilterMapToken().b64()}")
        appendLine("recent\t${recentFiles.joinToString(",") { it.b64() }.b64()}")
        appendLine("recentNotes\t${recentNotes.joinToString(",") { it.b64() }.b64()}")
        appendLine("filterPanel\t${fpState.filterPanelToken().b64()}")
        appendLine("tabs")
        tabs.forEach { appendLine("tab\t${it.tabToken()}") }
    }
}

// ── JSON helpers (small reader for exported filter files) ─────────────
private fun String.jsonStr(): String = buildString {
    append('"')
    this@jsonStr.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}

private fun Map<String, Any?>.stringField(key: String): String? = this[key] as? String

private fun Map<String, Any?>.booleanField(key: String): Boolean = this[key] == true

private fun Map<String, Any?>.stringArrayField(key: String): List<String> =
    (this[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

private fun String.jsonObjectArray(): Result<List<Map<String, Any?>>> =
    runCatching { JsonReader(this).readObjectArray() }

private class JsonReader(private val text: String) {
    private var pos = 0

    fun readObjectArray(): List<Map<String, Any?>> {
        skipWs()
        expect('[')
        val objects = mutableListOf<Map<String, Any?>>()
        skipWs()
        if (peek() == ']') {
            pos += 1
            return objects
        }
        while (true) {
            objects += readObject()
            skipWs()
            when (peek()) {
                ',' -> {
                    pos += 1
                    skipWs()
                }

                ']' -> {
                    pos += 1
                    return objects
                }

                else -> error("Expected ',' or ']' at $pos")
            }
        }
    }

    private fun readObject(): Map<String, Any?> {
        expect('{')
        val map = linkedMapOf<String, Any?>()
        skipWs()
        if (peek() == '}') {
            pos += 1
            return map
        }
        while (true) {
            val key = readString()
            skipWs()
            expect(':')
            skipWs()
            map[key] = readValue()
            skipWs()
            when (peek()) {
                ',' -> {
                    pos += 1
                    skipWs()
                }

                '}' -> {
                    pos += 1
                    return map
                }

                else -> error("Expected ',' or '}' at $pos")
            }
        }
    }

    private fun readValue(): Any? = when (peek()) {
        '"' -> readString()
        '[' -> readStringArray()
        't' -> {
            expectText("true")
            true
        }

        'f' -> {
            expectText("false")
            false
        }

        else -> error("Unsupported JSON value at $pos")
    }

    private fun readStringArray(): List<String> {
        expect('[')
        val values = mutableListOf<String>()
        skipWs()
        if (peek() == ']') {
            pos += 1
            return values
        }
        while (true) {
            values += readString()
            skipWs()
            when (peek()) {
                ',' -> {
                    pos += 1
                    skipWs()
                }

                ']' -> {
                    pos += 1
                    return values
                }

                else -> error("Expected ',' or ']' at $pos")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (pos < text.length) {
            val ch = text[pos++]
            when (ch) {
                '"' -> return out.toString()
                '\\' -> {
                    val esc = text.getOrNull(pos++) ?: error("Dangling escape at $pos")
                    out.append(
                        when (esc) {
                            '"' -> '"'
                            '\\' -> '\\'
                            '/' -> '/'
                            'b' -> '\b'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> readUnicode()
                            else -> error("Unsupported escape \\$esc at $pos")
                        },
                    )
                }

                else -> out.append(ch)
            }
        }
        error("Unterminated string")
    }

    private fun readUnicode(): Char {
        if (pos + 4 > text.length) error("Short unicode escape at $pos")
        val hex = text.substring(pos, pos + 4)
        pos += 4
        return hex.toInt(16).toChar()
    }

    private fun expect(ch: Char) {
        skipWs()
        if (peek() != ch) error("Expected '$ch' at $pos")
        pos += 1
    }

    private fun expectText(value: String) {
        if (!text.startsWith(value, pos)) error("Expected '$value' at $pos")
        pos += value.length
    }

    private fun peek(): Char = text.getOrNull(pos) ?: error("Unexpected end of JSON")

    private fun skipWs() {
        while (pos < text.length && text[pos].isWhitespace()) pos += 1
    }
}

private fun SequenceDef.sequenceToken(): String =
    listOf(
        id,
        matchText,
        isRegex.toString(),
        priority.toString(),
        color.value.toString(),
        enabled.toString(),
        tag.orEmpty(),
        endMatchText.orEmpty(),
        endIsRegex.toString(),
        endTag.orEmpty(),
    ).joinToString("|") { it.b64() }

private fun String.sequenceFromToken(): SequenceDef? = runCatching {
    val parts = split("|").map { it.unb64() }
    if (parts.size < 10) return@runCatching null
    SequenceDef(
        id = parts[0],
        matchText = parts[1],
        isRegex = parts[2].toBoolean(),
        priority = parts[3].toIntOrNull() ?: 0,
        color = Color(parts[4].toULong()),
        enabled = parts[5].toBoolean(),
        tag = parts[6].takeIf { it.isNotBlank() },
        endMatchText = parts[7].takeIf { it.isNotBlank() },
        endIsRegex = parts[8].toBoolean(),
        endTag = parts[9].takeIf { it.isNotBlank() },
    )
}.getOrNull()

private fun MessageRule.messageRuleToken(): String =
    listOf(
        id,
        include.toString(),
        pattern,
        regex.toString(),
        tag.orEmpty(),
        packagePrefix.orEmpty(),
        enabled.toString(),
        target.name,
    ).joinToString("|") { it.b64() }

private fun String.messageRuleFromToken(): MessageRule? = runCatching {
    val parts = split("|").map { it.unb64() }
    if (parts.size < 7) return@runCatching null
    MessageRule(
        id = parts[0],
        include = parts[1].toBoolean(),
        pattern = parts[2],
        regex = parts[3].toBoolean(),
        tag = parts[4].takeIf { it.isNotBlank() },
        packagePrefix = parts[5].takeIf { it.isNotBlank() },
        enabled = parts[6].toBoolean(),
        target = if (parts.size > 7) runCatching { RuleTarget.valueOf(parts[7]) }.getOrElse { RuleTarget.MESSAGE } else RuleTarget.MESSAGE,
    )
}.getOrNull()

private fun String.fieldToken(): String = if (isEmpty()) "~" else b64()

private fun String.fieldValue(): String = if (this == "~") "" else unb64()

private fun tokenFields(vararg values: String): String = values.joinToString("|") { it.fieldToken() }

private fun String.tokenFields(): List<String> = split("|", limit = Int.MAX_VALUE).map { it.fieldValue() }

private fun analysisNoteMarkdownName(filename: String): String {
    val base = filename.substringBeforeLast('.', filename)
    val safeBase = base.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "analysis" }
    return "${safeBase}_analysis.md"
}

private fun String.tokenList(): List<String> =
    if (isBlank()) emptyList() else unb64().split(",").filter { it.isNotBlank() }

private fun String.pathTokenList(): List<String> =
    tokenList().map { item -> runCatching { item.unb64() }.getOrElse { item } }

private fun AppSettings.settingsToken(): String = tokenFields(
    theme.name,
    fontSize.toString(),
    fontMono.toString(),
    defaultSaveDir.orEmpty(),
    mostUsedTagLimit.toString(),
    filterListRows.toString(),
    visibleTabLimit.toString(),
    autoExportNotes.toString(),
    annotationLogBlockStyle.name,
    numberAnnotationBlocks.toString(),
    annotationPrefixLabel,
    navScrollMargin.toString(),
    mcpControlEnabled.toString(),
    mcpControlPort.toString(),
)

private fun settingsFromToken(token: String): AppSettings? = runCatching {
    val p = token.tokenFields()
    if (p.size < 5) return@runCatching null
    AppSettings(
        theme = runCatching { ThemePreset.valueOf(p[0]) }.getOrElse { ThemePreset.LIGHT },
        fontSize = p[1].toIntOrNull() ?: 12,
        fontMono = p[2].toBoolean(),
        defaultSaveDir = p[3].takeIf { it.isNotBlank() },
        mostUsedTagLimit = p[4].toIntOrNull() ?: 5,
        filterListRows = p.getOrNull(5)?.toIntOrNull()?.coerceIn(1, 20) ?: 5,
        visibleTabLimit = p.getOrNull(6)?.toIntOrNull()?.coerceIn(2, 20) ?: 8,
        autoExportNotes = p.getOrNull(7)?.toBooleanStrictOrNull() ?: true,
        annotationLogBlockStyle = p.getOrNull(8)
            ?.let { runCatching { AnnotationLogBlockStyle.valueOf(it) }.getOrNull() }
            ?: AnnotationLogBlockStyle.INDENTED,
        numberAnnotationBlocks = p.getOrNull(9)?.toBooleanStrictOrNull() ?: false,
        annotationPrefixLabel = p.getOrNull(10)?.takeIf { it.isNotBlank() } ?: "From",
        navScrollMargin = p.getOrNull(11)?.toIntOrNull()?.coerceIn(0, 30) ?: 5,
        mcpControlEnabled = p.getOrNull(12)?.toBooleanStrictOrNull() ?: false,
        mcpControlPort = p.getOrNull(13)?.toIntOrNull()?.coerceIn(MIN_PORT, MAX_PORT) ?: DEFAULT_MCP_PORT,
    )
}.getOrNull()

private fun AppState.compareStateToken(): String = tokenFields(
    compareTabId,
    compareMode.toString(),
    compareFilterRight.toString(),
    filterVisible.toString(),
    annotationVisible.toString(),
    filterPanelWidth.toString(),
    annotationPanelWidth.toString(),
    compareSplit.toString(),
)

private fun AppState.restoreCompareState(token: String) {
    val p = token.tokenFields()
    if (p.size < 8) return
    compareTabId = p[0]
    compareMode = p[1].toBoolean()
    compareFilterRight = p[2].toBoolean()
    filterVisible = p[3].toBoolean()
    annotationVisible = p[4].toBoolean()
    filterPanelWidth = p[5].toFloatOrNull() ?: filterPanelWidth
    annotationPanelWidth = (p[6].toFloatOrNull() ?: annotationPanelWidth).coerceIn(ANNOTATION_PANEL_MIN_WIDTH, ANNOTATION_PANEL_MAX_WIDTH)
    compareSplit = p[7].toFloatOrNull() ?: compareSplit
}

private fun FilterPanelUiState.filterPanelToken(): String = tokenFields(
    hlListExpanded.toString(),
    lvlExpanded.toString(),
    seqExpanded.toString(),
    sfExpanded.toString(),
    incPillsExpanded.toString(),
    incMsgPillsExpanded.toString(),
    excMsgPillsExpanded.toString(),
    crashExpanded.toString(),
)

private fun FilterPanelUiState.restoreFilterPanelToken(token: String) {
    val p = token.tokenFields()
    if (p.size < 7) return
    hlListExpanded = p[0].toBoolean()
    lvlExpanded = p[1].toBoolean()
    seqExpanded = p[2].toBoolean()
    sfExpanded = p[3].toBoolean()
    incPillsExpanded = p[4].toBoolean()
    incMsgPillsExpanded = p[5].toBoolean()
    excMsgPillsExpanded = p[6].toBoolean()
    crashExpanded = p.getOrNull(7)?.toBooleanStrictOrNull() ?: crashExpanded
}

private fun AppState.activeFilterMapToken(): String =
    activeSavedFilterIds.entries.joinToString(",") { tokenFields(it.key, it.value) }

private fun activeFilterMapFromToken(token: String): Map<String, String> =
    if (token.isBlank()) emptyMap()
    else token.split(",").mapNotNull { item ->
        val p = item.tokenFields()
        if (p.size >= 2) p[0] to p[1] else null
    }.toMap()

private fun Highlighter.highlighterToken(): String = tokenFields(
    id,
    pattern,
    regex.toString(),
    color.value.toString(),
    on.toString(),
)

private fun String.highlighterFromToken(): Highlighter? = runCatching {
    val p = tokenFields()
    if (p.size < 5) return@runCatching null
    Highlighter(
        id = p[0],
        pattern = p[1],
        regex = p[2].toBoolean(),
        color = Color(p[3].toULong()),
        on = p[4].toBoolean(),
    )
}.getOrNull()

private fun SavedFilter.savedFilterToken(): String = tokenFields(
    id,
    name,
    levels.joinToString("") { it.key.toString() },
    activeTags.joinToString(",") { it.b64() },
    kwText,
    kwRegex.toString(),
    mode.name,
    excludeTags.joinToString(",") { it.b64() },
    excludeKw,
    excludeKwRegex.toString(),
    highlighters.joinToString(",") { it.highlighterToken().b64() },
    seqOn.toString(),
    kwInTag,
    kwInTagRegex.toString(),
    pkgPrefixes.joinToString(",") { it.b64() },
    pidTidFilter,
    sequences.joinToString(",") { it.sequenceToken().b64() },
    messageRules.joinToString(",") { it.messageRuleToken().b64() },
    excludePkgPrefixes.joinToString(",") { it.b64() },
)

private fun String.savedFilterFromToken(): SavedFilter? = runCatching {
    val p = tokenFields()
    if (p.size < 18) return@runCatching null
    SavedFilter(
        id = p[0],
        name = p[1],
        levels = p[2].mapNotNull { key -> LogLevel.entries.find { it.key == key } }.toSet()
            .ifEmpty { LogLevel.entries.toSet() },
        activeTags = p[3].encodedSet(),
        kwText = p[4],
        kwRegex = p[5].toBoolean(),
        mode = runCatching { FilterMode.valueOf(p[6]) }.getOrElse { FilterMode.TAGS },
        excludeTags = p[7].encodedSet(),
        excludeKw = p[8],
        excludeKwRegex = p[9].toBoolean(),
        highlighters = p[10].encodedList().mapNotNull { it.highlighterFromToken() },
        seqOn = p[11].toBoolean(),
        kwInTag = p[12],
        kwInTagRegex = p[13].toBoolean(),
        pkgPrefixes = p[14].encodedSet(),
        pidTidFilter = p[15],
        sequences = p[16].encodedList().mapNotNull { it.sequenceFromToken() },
        messageRules = p[17].encodedList().mapNotNull { it.messageRuleFromToken() },
        excludePkgPrefixes = p.getOrNull(18)?.encodedSet() ?: emptySet(),
    )
}.getOrNull()

private fun SavedFilter.toFilter(): Filter = Filter(
    levels = levels,
    activeTags = activeTags,
    kwText = kwText,
    kwRegex = kwRegex,
    mode = mode,
    excludeTags = excludeTags,
    excludeKw = excludeKw,
    excludeKwRegex = excludeKwRegex,
    highlighters = highlighters,
    messageRules = messageRules,
    seqOn = seqOn,
    kwInTag = kwInTag,
    kwInTagRegex = kwInTagRegex,
    pkgPrefixes = pkgPrefixes,
    excludePkgPrefixes = excludePkgPrefixes,
    pidTidFilter = pidTidFilter,
    sequences = sequences,
)

private fun String.encodedList(): List<String> =
    if (isBlank()) emptyList() else split(",").filter { it.isNotBlank() }.map { it.unb64() }

private fun String.encodedSet(): Set<String> = encodedList().toSet()

private fun LogEntry.toAnnToken(): String =
    listOf(id.toString(), ts, level.key.toString(), tag, msg, pid.toString(), tid.toString())
        .joinToString("|") { it.b64() }

private fun String.toLogEntryFromAnnToken(): LogEntry? = runCatching {
    val p = split("|").map { it.unb64() }
    if (p.size < 5) return@runCatching null
    LogEntry(
        id = p[0].toIntOrNull() ?: 0,
        ts = p[1],
        level = LogLevel.from(p[2].firstOrNull() ?: 'I'),
        tag = p[3],
        msg = p[4],
        pid = p.getOrNull(5)?.toIntOrNull() ?: 0,
        tid = p.getOrNull(6)?.toIntOrNull() ?: 0,
    )
}.getOrNull()

private fun AnnBlock.annBlockToken(): String = when (this) {
    is AnnBlock.Note -> tokenFields("N", id, text)
    is AnnBlock.LogRef -> tokenFields(
        "R", id,
        logIds.joinToString(","),
        caption,
        sourceTabId.orEmpty(),
        sourceFilename.orEmpty(),
        sourceEntries?.joinToString(";") { it.toAnnToken() }.orEmpty(),
    )
}

private fun String.annBlockFromToken(): AnnBlock? = runCatching {
    val p = tokenFields()
    if (p.size < 3) return@runCatching null
    when (p[0]) {
        "N" -> AnnBlock.Note(p[1], p[2])
        "R" -> AnnBlock.LogRef(
            id = p[1],
            logIds = p[2].split(",").mapNotNull { it.toIntOrNull() },
            caption = p.getOrElse(3) { "" },
            sourceTabId = p.getOrElse(4) { "" }.takeIf { it.isNotBlank() },
            sourceFilename = p.getOrElse(5) { "" }.takeIf { it.isNotBlank() },
            sourceEntries = p.getOrElse(6) { "" }.takeIf { it.isNotBlank() }
                ?.split(";")?.mapNotNull { it.toLogEntryFromAnnToken() },
        )

        else -> null
    }
}.getOrNull()

private fun Annotations.annotationsToken(): String = tokenFields(
    prefix,
    suffix,
    blocks.joinToString(",") { it.annBlockToken().b64() },
)

private fun String.annotationsFromToken(): Annotations? = runCatching {
    val p = tokenFields()
    if (p.size < 3) return@runCatching null
    Annotations(
        prefix = p[0],
        suffix = p[1],
        blocks = p[2].encodedList().mapNotNull { it.annBlockFromToken() },
    )
}.getOrNull()

private fun ManualCollapseBlock.manualBlockToken(): String = tokenFields(
    id,
    anchorId.toString(),
    direction.name,
    color.value.toString(),
    enabled.toString(),
)

private fun String.manualBlockFromToken(): ManualCollapseBlock? = runCatching {
    val p = tokenFields()
    if (p.size < 5) return@runCatching null
    ManualCollapseBlock(
        id = p[0],
        anchorId = p[1].toIntOrNull() ?: return@runCatching null,
        direction = runCatching { ManualCollapseDirection.valueOf(p[2]) }.getOrElse { ManualCollapseDirection.TO_END },
        color = Color(p[3].toULong()),
        enabled = p[4].toBoolean(),
    )
}.getOrNull()

private fun LogTab.tabToken(): String {
    val filter = SavedFilter(
        "tab", "tab", filter.levels, filter.activeTags, filter.kwText, filter.kwRegex,
        filter.mode, filter.excludeTags, filter.excludeKw, filter.excludeKwRegex, filter.highlighters, filter.seqOn,
        filter.kwInTag, filter.kwInTagRegex, filter.pkgPrefixes, filter.pidTidFilter, filter.sequences,
        filter.messageRules, filter.excludePkgPrefixes,
    )
    return tokenFields(
        id,
        filename,
        sourcePath.orEmpty(),
        filter.savedFilterToken(),
        annotations.annotationsToken(),
        showAnnMd.toString(),
        showUnfiltered.toString(),
        expanded.joinToString(",") { it.b64() },
        manualBlocks.joinToString(",") { it.manualBlockToken().b64() },
    )
}

// Metadata-only restore: log content is parsed afterwards on ioScope (scheduleRestoredTabLoad),
// so this must stay cheap — it runs synchronously during AppState init. Source-exists checks
// stay here so tabs whose backing file/archive vanished are dropped before they ever appear.
private class RestoredTabShell(val tab: LogTab, val source: RestoredTabSource)

private sealed class RestoredTabSource {
    abstract val largeFileMode: Boolean

    abstract fun load(parser: (File) -> List<LogEntry>): List<LogEntry>

    data class FileSource(val file: File) : RestoredTabSource() {
        override val largeFileMode: Boolean = file.length() >= LARGE_FILE_MODE_BYTES

        override fun load(parser: (File) -> List<LogEntry>): List<LogEntry> = parser(file)
    }

    data class ArchiveSource(val archiveFile: File, val candidate: ZipLogCandidate) : RestoredTabSource() {
        override val largeFileMode: Boolean = candidate.sizeBytes >= LARGE_FILE_MODE_BYTES

        override fun load(parser: (File) -> List<LogEntry>): List<LogEntry> = extractCandidate(archiveFile, candidate)
    }
}

private fun String.tabShellFromToken(): RestoredTabShell? = runCatching {
    val p = tokenFields()
    if (p.size < 9) return@runCatching null
    val sourcePath = p[2].takeIf { it.isNotBlank() }
    val source = sourcePath?.restoredTabSource() ?: return@runCatching null
    RestoredTabShell(
        LogTab(
            id = p[0],
            filename = p[1],
            logData = emptyList(),
            rmap = emptyMap(),
            filter = p[3].savedFilterFromToken()?.toFilter() ?: Filter(),
            showUnfiltered = p[6].toBoolean(),
            expanded = p[7].encodedSet(),
            annotations = p[4].annotationsFromToken() ?: Annotations(),
            showAnnMd = p[5].toBoolean(),
            manualBlocks = p[8].encodedList().mapNotNull { it.manualBlockFromToken() },
            sourcePath = sourcePath,
            largeFileMode = source.largeFileMode,
        ),
        source,
    )
}.getOrNull()

private fun String.restoredTabSource(): RestoredTabSource? {
    val bangIndex = indexOf('!')
    if (bangIndex > 0) {
        val archiveFile = File(substring(0, bangIndex)).takeIf { it.exists() } ?: return null
        val entryPath = substring(bangIndex + 1).takeIf { it.isNotBlank() } ?: return null
        val candidate = listArchiveLogCandidates(archiveFile).firstOrNull { it.entryPath == entryPath } ?: return null
        return RestoredTabSource.ArchiveSource(archiveFile, candidate)
    }
    return File(this).takeIf { it.exists() }?.let { RestoredTabSource.FileSource(it) }
}

private fun File.totalFileSize(): Long =
    if (!exists()) 0L
    else if (isFile) length()
    else listFiles().orEmpty().sumOf { it.totalFileSize() }

private fun String.b64(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))

private fun String.unb64(): String =
    String(Base64.getUrlDecoder().decode(this), Charsets.UTF_8)
