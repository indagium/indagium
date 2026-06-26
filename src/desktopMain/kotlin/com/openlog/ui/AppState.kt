package com.openlog.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.openlog.model.*
import com.openlog.utils.buildMd
import com.openlog.utils.computeItems
import com.openlog.utils.computeSeqGroups
import com.openlog.utils.parseLogcat
import com.openlog.utils.passesFilter
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.Base64
import kotlinx.coroutines.*

private fun mkRmap(data: List<LogEntry>): Map<Int, LogEntry> = data.associateBy { it.id }

fun mkTab(id: String, filename: String, logData: List<LogEntry>) = LogTab(
    id = id, filename = filename, logData = logData, rmap = mkRmap(logData),
    annotations = Annotations(prefix = "## $filename"),
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
    File(System.getProperty("user.home"), ".openlog2/autosave.cache")

data class PendingSequenceStart(val text: String, val tag: String)
data class PendingFilterLoad(val tabId: String, val targetFilterId: String, val currentFilterId: String?)

class AppState(
    private val autosaveFile: File = defaultAutosaveFile(),
    restoreOnCreate: Boolean = false,
) {
    // ── Settings ────────────────────────────────────────────────────
    var settings by mutableStateOf(AppSettings())

    // ── Layout ──────────────────────────────────────────────────────
    var filterVisible        by mutableStateOf(true)
    var annotationVisible    by mutableStateOf(true)
    var filterPanelWidth     by mutableStateOf(220f)
    var annotationPanelWidth by mutableStateOf(300f)
    var compareSplit         by mutableStateOf(0.5f)
    var compareFilterRight   by mutableStateOf(true)
    var isLoading            by mutableStateOf(false)

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Sequences ───────────────────────────────────────────────────
    var sequences by mutableStateOf(emptyList<SequenceDef>())

    // ── Tabs ────────────────────────────────────────────────────────
    var tabs by mutableStateOf(emptyList<LogTab>())
    var activeTabId  by mutableStateOf("")
    var compareMode  by mutableStateOf(false)
    var compareTabId by mutableStateOf("")

    // ── Transient UI ─────────────────────────────────────────────────
    var ctx           by mutableStateOf<CtxMenuState?>(null)
    var addAnnRequest by mutableStateOf<AddAnnRequest?>(null)   // dialog to add annotation
    var sfDialog      by mutableStateOf(false)
    var sfName        by mutableStateOf("")
    var sfTabId       by mutableStateOf<String?>(null)
    var savedFilters  by mutableStateOf<List<SavedFilter>>(emptyList())
    var tagUsage      by mutableStateOf<Map<String, Int>>(emptyMap())
    var settingsOpen  by mutableStateOf(false)
    var recentFiles    by mutableStateOf<List<String>>(emptyList())
    var recentMenuOpen by mutableStateOf(false)
    var recentNotes    by mutableStateOf<List<String>>(emptyList())
    var recentNotesMenuOpen by mutableStateOf(false)
    var pendingSequenceStart by mutableStateOf<PendingSequenceStart?>(null)
    var pendingFilterLoad by mutableStateOf<PendingFilterLoad?>(null)
    var pendingClearFilterTabId by mutableStateOf<String?>(null)
    var activeSavedFilterIds by mutableStateOf<Map<String, String>>(emptyMap())

    var newHlPat   by mutableStateOf("")
    var newHlRx    by mutableStateOf(false)
    var newHlColor by mutableStateOf(HL_COLORS[0])

    var newSeqText  by mutableStateOf("")
    var newSeqRegex by mutableStateOf(false)
    var newSeqEndText  by mutableStateOf("")
    var newSeqEndRegex by mutableStateOf(false)
    var newSeqTag by mutableStateOf("")
    var newSeqEndTag by mutableStateOf("")
    var newSeqColor by mutableStateOf(SEQ_COLORS[0])
    var newMsgRulePattern by mutableStateOf("")
    var newMsgRuleRegex by mutableStateOf(false)
    var newMsgRuleInclude by mutableStateOf(false)
    var newMsgRuleTag by mutableStateOf("")
    var newMsgRulePrefix by mutableStateOf("")

    init {
        if (restoreOnCreate) restoreAutosave()
    }

    // ── Helpers ─────────────────────────────────────────────────────
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
    fun requestClearFilter(tabId: String) { pendingClearFilterTabId = tabId }
    fun cancelClearFilter() { pendingClearFilterTabId = null }
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
    fun addPkgPrefix(tabId: String, v: String) { if (v.isNotBlank()) upFlt(tabId) { it.copy(pkgPrefixes = it.pkgPrefixes + v.trim()) } }
    fun removePkgPrefix(tabId: String, v: String) = upFlt(tabId) { it.copy(pkgPrefixes = it.pkgPrefixes - v) }
    fun setPidTidFilter(tabId: String, v: String) = upFlt(tabId) { it.copy(pidTidFilter = v) }
    fun addMessageRule(
        tabId: String,
        include: Boolean,
        pattern: String,
        regex: Boolean,
        tag: String?,
        packagePrefix: String?,
    ) {
        if (pattern.isBlank()) return
        val rule = MessageRule(
            id = "mr${System.currentTimeMillis()}_${pattern.hashCode()}",
            include = include,
            pattern = pattern,
            regex = regex,
            tag = tag?.trim()?.takeIf { it.isNotBlank() },
            packagePrefix = packagePrefix?.trim()?.takeIf { it.isNotBlank() },
        )
        upFlt(tabId) { it.copy(messageRules = it.messageRules + rule) }
        newMsgRulePattern = ""
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
        upFlt(tabId) { f -> f.copy(highlighters = f.highlighters + Highlighter("hl${System.currentTimeMillis()}", pat, rx, color, true)) }
        newHlPat = ""
        newHlColor = HL_COLORS[(HL_COLORS.indexOf(color) + 1) % HL_COLORS.size]
    }
    fun removeHl(tabId: String, id: String) = upFlt(tabId) { f -> f.copy(highlighters = f.highlighters.filter { it.id != id }) }
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
        val maxP = sequences.maxOfOrNull { it.priority } ?: 0
        val assignedColor = nextSequenceColor(color)
        sequences = sequences + SequenceDef(
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
        newSeqText = ""
        newSeqEndText = ""
        newSeqTag = ""
        newSeqEndTag = ""
        newSeqColor = colorAfterSequenceColor(assignedColor)
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
        sequences = sequences.map {
            if (it.id != id) it else it.copy(
                matchText = matchText,
                isRegex = isRegex,
                tag = tag?.trim()?.takeIf { value -> value.isNotBlank() },
                endMatchText = endMatchText?.takeIf { value -> value.isNotBlank() },
                endIsRegex = endIsRegex,
                endTag = endTag?.trim()?.takeIf { value -> value.isNotBlank() },
            )
        }
    }
    fun removeSequence(id: String) { sequences = sequences.filter { it.id != id } }
    fun toggleSequence(id: String) { sequences = sequences.map { if (it.id == id) it.copy(enabled = !it.enabled) else it } }
    fun setSequenceColor(id: String, color: Color) { sequences = sequences.map { if (it.id == id) it.copy(color = color) else it } }
    fun reorderSequence(fromId: String, toIdx: Int) {
        val list = sequences.toMutableList()
        val fromIdx = list.indexOfFirst { it.id == fromId }.takeIf { it >= 0 } ?: return
        val item = list.removeAt(fromIdx)
        val safeIdx = toIdx.coerceIn(0, list.size)
        list.add(safeIdx, item)
        sequences = list.mapIndexed { i, s -> s.copy(priority = i + 1) }
    }
    fun moveSequenceUp(id: String) {
        val idx = sequences.indexOfFirst { it.id == id }.takeIf { it > 0 } ?: return
        val list = sequences.toMutableList(); val a = list[idx - 1]; val b = list[idx]
        list[idx - 1] = b.copy(priority = a.priority); list[idx] = a.copy(priority = b.priority)
        sequences = list
    }
    fun moveSequenceDown(id: String) {
        val idx = sequences.indexOfFirst { it.id == id }.takeIf { it < sequences.size - 1 } ?: return
        val list = sequences.toMutableList(); val a = list[idx]; val b = list[idx + 1]
        list[idx] = b.copy(priority = a.priority); list[idx + 1] = a.copy(priority = b.priority)
        sequences = list
    }

    // ── Saved filters ────────────────────────────────────────────────
    fun saveFilter(tabId: String, name: String) {
        val sf = snapshotFilter(tabId, "sf${System.nanoTime()}_${savedFilters.size}", name) ?: return
        savedFilters = savedFilters + sf
        activeSavedFilterIds = activeSavedFilterIds + (tabId to sf.id)
        sfDialog = false; sfName = ""
        loadPendingFilterAfterSaving(tabId)
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
        val t = tab(tabId) ?: return null; val f = t.filter
        return SavedFilter(
            id, name, f.levels, f.activeTags, f.kwText, f.kwRegex,
            f.mode, f.excludeTags, f.excludeKw, f.excludeKwRegex, f.highlighters, f.seqOn,
            f.kwInTag, f.kwInTagRegex, f.pkgPrefixes, f.pidTidFilter, sequences, f.messageRules,
        )
    }
    private fun currentFilterMatches(tabId: String, sf: SavedFilter): Boolean {
        val current = snapshotFilter(tabId, sf.id, sf.name) ?: return false
        return current == sf
    }
    private fun currentFilterIsEmpty(tabId: String): Boolean {
        val current = snapshotFilter(tabId, "", "") ?: return true
        val empty = SavedFilter("", "", LogLevel.entries.toSet(), emptySet(), "", false, FilterMode.TAGS,
            emptySet(), "", false, emptyList(), true, "", false, emptySet(), "", emptyList(), emptyList())
        return current == empty
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
        sequences = sf.sequences
        upFlt(tabId) { _ ->
            Filter(
                levels = sf.levels,
                activeTags = sf.activeTags,
                kwText = sf.kwText,
                kwRegex = sf.kwRegex,
                mode = sf.mode,
                excludeTags = sf.excludeTags,
                excludeKw = sf.excludeKw,
                excludeKwRegex = sf.excludeKwRegex,
                highlighters = sf.highlighters,
                seqOn = sf.seqOn,
                kwInTag = sf.kwInTag,
                kwInTagRegex = sf.kwInTagRegex,
                pkgPrefixes = sf.pkgPrefixes,
                pidTidFilter = sf.pidTidFilter,
                messageRules = sf.messageRules,
            )
        }
        activeSavedFilterIds = activeSavedFilterIds + (tabId to sf.id)
    }
    fun activeSavedFilterId(tabId: String): String? = activeSavedFilterIds[tabId]
    fun deleteSF(id: String) { savedFilters = savedFilters.filter { it.id != id } }

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
            appendLine("    \"seqOn\": ${sf.seqOn},")
            appendLine("    \"kwInTag\": ${sf.kwInTag.jsonStr()},")
            appendLine("    \"kwInTagRegex\": ${sf.kwInTagRegex},")
            appendLine("    \"pkgPrefixes\": [${sf.pkgPrefixes.joinToString(",") { it.jsonStr() }}],")
            appendLine("    \"pidTidFilter\": ${sf.pidTidFilter.jsonStr()},")
            appendLine("    \"sequences\": [${sf.sequences.joinToString(",") { it.sequenceToken().jsonStr() }}],")
            appendLine("    \"messageRules\": [${sf.messageRules.joinToString(",") { it.messageRuleToken().jsonStr() }}]")
            append("  }")
            if (i < savedFilters.lastIndex) appendLine(",") else appendLine()
        }
        append("]")
    }

    fun importFilters(json: String) {
        // Simple parser: find name/levels/mode/etc. per entry
        val entries = json.split("\\{".toRegex()).drop(1)
        val imported = entries.mapNotNull { chunk ->
            val name = chunk.jsonField("name") ?: return@mapNotNull null
            val levels = chunk.jsonArrayStr("levels").mapNotNull { c -> LogLevel.entries.find { it.key.toString() == c } }.toSet()
            val mode = runCatching { FilterMode.valueOf(chunk.jsonField("mode") ?: "TAGS") }.getOrElse { FilterMode.TAGS }
            val activeTags = chunk.jsonArrayStr("activeTags").toSet()
            val excludeTags = chunk.jsonArrayStr("excludeTags").toSet()
            val kwText = chunk.jsonField("kwText") ?: ""
            val kwRegex = chunk.jsonField("kwRegex") == "true"
            val excludeKw = chunk.jsonField("excludeKw") ?: ""
            val excludeKwRegex = chunk.jsonField("excludeKwRegex") == "true"
            val seqOn = chunk.jsonField("seqOn") != "false"
            val kwInTag = chunk.jsonField("kwInTag") ?: ""
            val kwInTagRegex = chunk.jsonField("kwInTagRegex") == "true"
            val pkgPrefixes = chunk.jsonArrayStr("pkgPrefixes").toSet()
            val pidTidFilter = chunk.jsonField("pidTidFilter") ?: ""
            val sequences = chunk.jsonArrayStr("sequences").mapNotNull { it.sequenceFromToken() }
            val messageRules = chunk.jsonArrayStr("messageRules").mapNotNull { it.messageRuleFromToken() }
            SavedFilter(
                "sf${System.currentTimeMillis()}_${name.hashCode()}", name,
                levels.ifEmpty { LogLevel.entries.toSet() }, activeTags, kwText, kwRegex, mode,
                excludeTags, excludeKw, excludeKwRegex, emptyList(), seqOn,
                kwInTag, kwInTagRegex, pkgPrefixes, pidTidFilter, sequences, messageRules,
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
                val visIds = computeItems(t, sequences, true).map { item ->
                    when (item) {
                        is LogItem.Row -> item.entry.id
                        is LogItem.SeqHeader -> item.entry.id
                        is LogItem.ManualHeader -> item.entry.id
                    }
                }
                val last = t.selected.lastOrNull { it in visIds.toSet() } ?: t.selected.maxOrNull()
                if (last == null) setOf(id)
                else {
                    val a = visIds.indexOf(last); val b = visIds.indexOf(id)
                    if (a >= 0 && b >= 0) visIds.subList(minOf(a, b), maxOf(a, b) + 1).toSet() else t.selected + id
                }
            }
            else  -> if (t.selected == setOf(id)) emptySet() else setOf(id)
        }
        t.copy(selected = n)
    }
    fun selRowRange(tabId: String, fromId: Int, toId: Int) = upTab(tabId) { t ->
        val ids = t.logData.filter { passesFilter(it, t.filter) }.map { it.id }.ifEmpty { t.logData.map { it.id } }
        val a = ids.indexOf(fromId); val b = ids.indexOf(toId)
        if (a < 0 || b < 0) return@upTab t
        t.copy(selected = ids.subList(minOf(a, b), maxOf(a, b) + 1).toSet())
    }
    fun setSelectedRows(tabId: String, ids: List<Int>) = upTab(tabId) { t ->
        t.copy(selected = ids.toSet())
    }
    fun clearSelection(tabId: String) = upTab(tabId) { it.copy(selected = emptySet()) }

    // ── Sequence expand/collapse ─────────────────────────────────────
    fun toggleGroup(tabId: String, gid: String) = upTab(tabId) { t ->
        t.copy(expanded = if (gid in t.expanded) t.expanded - gid else t.expanded + gid)
    }
    fun expandAll(tabId: String) = upTab(tabId) { t ->
        val groups = computeSeqGroups(t.logData, sequences)
        t.copy(expanded = (groups.map { it.gid } + groups.flatMap { g -> g.nested.map { it.gid } } + t.manualBlocks.map { it.id }).toSet())
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
    fun confirmAddAnn(targetTabId: String, sourceTabId: String, logIds: List<Int>, caption: String, sourceFilename: String?) {
        val crossFile = sourceTabId != targetTabId
        val sourceEntries = if (crossFile) {
            val rmap = tab(sourceTabId)?.rmap ?: emptyMap()
            logIds.sorted().mapNotNull { rmap[it] }
        } else null
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
        upAnn(tabId) { t ->
            val note = AnnBlock.Note("n${System.currentTimeMillis()}", "")
            val blocks = t.annotations.blocks.toMutableList()
            val idx = if (afterId != null) (blocks.indexOfFirst { it.id == afterId } + 1).coerceAtLeast(0) else blocks.size
            blocks.add(idx, note)
            t.copy(annotations = t.annotations.copy(blocks = blocks))
        }
    }
    fun updateBlock(tabId: String, blockId: String, newText: String) = upAnn(tabId) { t ->
        t.copy(annotations = t.annotations.copy(blocks = t.annotations.blocks.map { b ->
            when {
                b.id != blockId -> b
                b is AnnBlock.Note   -> b.copy(text = newText)
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
    private fun addManualCollapse(tabId: String, anchorId: Int, direction: ManualCollapseDirection) = upTab(tabId) { t ->
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
        addMessageRule(c.tabId, include = true, pattern = text, regex = false, tag = entry.tag, packagePrefix = null)
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
        val t = tab(tabId) ?: return
        val text = t.selected.sorted().mapNotNull { id -> t.rmap[id] }.joinToString("\n") { e ->
            val pid = if (e.pid > 0) "  ${e.pid.toString().padStart(5)} ${e.tid.toString().padStart(5)}" else ""
            "${e.ts}$pid  ${e.level.key}  ${e.tag}: ${e.msg}"
        }
        copyToClipboard(text)
    }

    // ── Tab management ───────────────────────────────────────────────
    fun addTab() {
        val n = tabCounter++
        val t = emptyWorkspaceTab().copy(id = "t$n")
        tabs = tabs + t; activeTabId = t.id
    }
    fun closeTab(tabId: String) {
        val next = tabs.filter { it.id != tabId }
        if (activeTabId == tabId) activeTabId = next.lastOrNull()?.id ?: ""
        if (compareTabId == tabId) compareTabId = next.firstOrNull()?.id ?: ""
        if (next.isEmpty()) compareMode = false
        tabs = next
    }
    fun openFile(file: File) {
        val n = tabCounter++  // capture on calling thread before launching
        isLoading = true
        val path = file.absolutePath
        recentFiles = (listOf(path) + recentFiles.filter { it != path }).take(30)
        recentMenuOpen = false
        ioScope.launch {
            val logData = runCatching { parseLogcat(file) }.getOrElse { emptyList() }
            val t = mkTab("t$n", file.name, logData).copy(sourcePath = path)
            // Compose MutableState is snapshot-safe to write from any thread;
            // recomposition is automatically scheduled on the UI thread.
            tabs = tabs + t
            activeTabId = t.id
            isLoading = false
        }
    }

    // ── Copy / Save ──────────────────────────────────────────────────
    fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
    fun copyAnn(tabId: String) { tab(tabId)?.let { copyToClipboard(buildMd(it)) } }
    fun saveAnalysis(tabId: String) {
        val t = tab(tabId) ?: return
        val dlg = FileDialog(null as Frame?, "Save Analysis", FileDialog.SAVE).apply {
            file = t.filename.substringBeforeLast('.') + "_analysis.md"
            settings.defaultSaveDir?.let { directory = it }
            isVisible = true
        }
        val path = dlg.file ?: return; val dir = dlg.directory ?: return
        settings = settings.copy(defaultSaveDir = dir)
        val saved = File(dir + path)
        saved.writeText(buildMd(t))
        File(saved.parent, saved.nameWithoutExtension + ".ann").writeText(t.annotations.annotationsToken())
        val absPath = saved.absolutePath
        recentNotes = (listOf(absPath) + recentNotes.filter { it != absPath }).take(30)
    }
    fun openNoteFile(tabId: String, file: File) {
        val absPath = file.absolutePath
        recentNotes = (listOf(absPath) + recentNotes.filter { it != absPath }).take(30)
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

    private val notesDir = File(System.getProperty("user.home"), ".openlog2/notes")

    private fun autoExportAnnotations(tab: LogTab) {
        if (tab.annotations.blocks.isEmpty()) return
        ioScope.launch {
            runCatching {
                notesDir.mkdirs()
                val safeName = tab.filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val mdFile = File(notesDir, "${safeName}_notes.md")
                mdFile.writeText(buildMd(tab))
                // Sidecar stores full block state for restoration
                File(notesDir, "${safeName}_notes.ann").writeText(tab.annotations.annotationsToken())
                val absPath = mdFile.absolutePath
                recentNotes = (listOf(absPath) + recentNotes.filter { it != absPath }).take(30)
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
            settings = settings.copy(defaultSaveDir = chosen.absolutePath)
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }
    fun exportFiltersToFile() {
        val dlg = FileDialog(null as Frame?, "Export Filters", FileDialog.SAVE).apply {
            file = "openlog_filters.json"; isVisible = true
        }
        val path = dlg.file ?: return; val dir = dlg.directory ?: return
        File(dir + path).writeText(exportFilters())
    }
    fun importFiltersFromFile() {
        val dlg = FileDialog(null as Frame?, "Import Filters", FileDialog.LOAD).apply {
            setFilenameFilter { _, n -> n.endsWith(".json") }; isVisible = true
        }
        val path = dlg.file ?: return; val dir = dlg.directory ?: return
        importFiltersFromFile(File(dir + path))
    }
    fun importFiltersFromFile(file: File) {
        runCatching { importFilters(file.readText()) }
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
            val tabLines = mutableListOf<String>()
            var inTabs = false
            lines.drop(1).forEach { line ->
                if (line == "tabs") {
                    inTabs = true
                    return@forEach
                }
                if (inTabs) {
                    tabLines += line
                    return@forEach
                }
                val key = line.substringBefore('\t')
                val value = line.substringAfter('\t', "")
                when (key) {
                    "settings" -> settingsFromToken(value.unb64())?.let { settings = it }
                    "active" -> activeTabId = value.unb64()
                    "compare" -> restoreCompareState(value.unb64())
                    "sequences" -> sequences = value.tokenList().mapNotNull { it.sequenceFromToken() }
                    "saved" -> importFilters(value.unb64())
                    "activeFilters" -> activeSavedFilterIds = activeFilterMapFromToken(value.unb64())
                    "recent" -> recentFiles = value.tokenList()
                    "recentNotes" -> recentNotes = value.tokenList()
                }
            }
            tabs = tabLines.mapNotNull { it.removePrefix("tab\t").tabFromToken() }
            if (tabs.none { it.id == activeTabId }) activeTabId = tabs.firstOrNull()?.id ?: ""
            if (tabs.none { it.id == compareTabId }) compareTabId = tabs.getOrNull(1)?.id ?: tabs.firstOrNull()?.id ?: ""
            tabCounter = (tabs.mapNotNull { it.id.removePrefix("t").toIntOrNull() }.maxOrNull() ?: 0) + 1
        }
    }

    private fun serializeAutosave(): String = buildString {
        appendLine("openLog2-cache-v1")
        appendLine("settings\t${settings.settingsToken().b64()}")
        appendLine("active\t${activeTabId.b64()}")
        appendLine("compare\t${compareStateToken().b64()}")
        appendLine("sequences\t${sequences.joinToString(",") { it.sequenceToken() }.b64()}")
        appendLine("saved\t${exportFilters().b64()}")
        appendLine("activeFilters\t${activeFilterMapToken().b64()}")
        appendLine("recent\t${recentFiles.joinToString(",") { it.b64() }.b64()}")
        appendLine("recentNotes\t${recentNotes.joinToString(",") { it.b64() }.b64()}")
        appendLine("tabs")
        tabs.forEach { appendLine("tab\t${it.tabToken()}") }
    }
}

// ── JSON helpers (no external dep) ──────────────────────────────────
private fun String.jsonStr() = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
private fun String.jsonField(key: String): String? =
    Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"|\"$key\"\\s*:\\s*(true|false)").find(this)?.let {
        it.groupValues[1].ifEmpty { it.groupValues[2] }
    }
private fun String.jsonArrayStr(key: String): List<String> =
    Regex("\"$key\"\\s*:\\s*\\[([^]]*)]").find(this)?.groupValues?.get(1)
        ?.split(",")?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() } ?: emptyList()

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
    )
}.getOrNull()

private fun String.fieldToken(): String = if (isEmpty()) "~" else b64()
private fun String.fieldValue(): String = if (this == "~") "" else unb64()
private fun tokenFields(vararg values: String): String = values.joinToString("|") { it.fieldToken() }
private fun String.tokenFields(): List<String> = split("|", limit = Int.MAX_VALUE).map { it.fieldValue() }
private fun String.tokenList(): List<String> =
    if (isBlank()) emptyList() else unb64().split(",").filter { it.isNotBlank() }

private fun AppSettings.settingsToken(): String = tokenFields(
    theme.name,
    fontSize.toString(),
    fontMono.toString(),
    defaultSaveDir.orEmpty(),
    mostUsedTagLimit.toString(),
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
    annotationPanelWidth = p[6].toFloatOrNull() ?: annotationPanelWidth
    compareSplit = p[7].toFloatOrNull() ?: compareSplit
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
)

private fun String.savedFilterFromToken(): SavedFilter? = runCatching {
    val p = tokenFields()
    if (p.size < 18) return@runCatching null
    SavedFilter(
        id = p[0],
        name = p[1],
        levels = p[2].mapNotNull { key -> LogLevel.entries.find { it.key == key } }.toSet().ifEmpty { LogLevel.entries.toSet() },
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
    pidTidFilter = pidTidFilter,
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
    val filter = SavedFilter("tab", "tab", filter.levels, filter.activeTags, filter.kwText, filter.kwRegex,
        filter.mode, filter.excludeTags, filter.excludeKw, filter.excludeKwRegex, filter.highlighters, filter.seqOn,
        filter.kwInTag, filter.kwInTagRegex, filter.pkgPrefixes, filter.pidTidFilter, emptyList(), filter.messageRules)
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

private fun String.tabFromToken(): LogTab? = runCatching {
    val p = tokenFields()
    if (p.size < 9) return@runCatching null
    val sourcePath = p[2].takeIf { it.isNotBlank() }
    val data = sourcePath
        ?.let { path -> File(path).takeIf { it.exists() } }
        ?.let { file -> runCatching { parseLogcat(file) }.getOrElse { emptyList() } }
        ?: emptyList()
    LogTab(
        id = p[0],
        filename = p[1],
        logData = data,
        rmap = mkRmap(data),
        filter = p[3].savedFilterFromToken()?.toFilter() ?: Filter(),
        showUnfiltered = p[6].toBoolean(),
        expanded = p[7].encodedSet(),
        annotations = p[4].annotationsFromToken() ?: Annotations(),
        showAnnMd = p[5].toBoolean(),
        manualBlocks = p[8].encodedList().mapNotNull { it.manualBlockFromToken() },
        sourcePath = sourcePath,
    )
}.getOrNull()

private fun String.b64(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))

private fun String.unb64(): String =
    String(Base64.getUrlDecoder().decode(this), Charsets.UTF_8)
