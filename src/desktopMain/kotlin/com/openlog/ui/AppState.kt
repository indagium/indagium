package com.openlog.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.openlog.model.*
import com.openlog.utils.buildMd
import com.openlog.utils.computeSeqGroups
import com.openlog.utils.parseLogcat
import com.openlog.utils.passesFilter
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import kotlinx.coroutines.*

fun mkTab(id: String, filename: String, logData: List<LogEntry>) = LogTab(
    id = id, filename = filename, logData = logData, rmap = mkRmap(logData),
    annotations = Annotations(prefix = "## $filename"),
)

private var tabCounter = 3

class AppState {
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
    var tabs by mutableStateOf(listOf(
        mkTab("a", "login_crash.log", LOG_A),
        mkTab("b", "boot_seq.log",    LOG_B),
    ))
    var activeTabId  by mutableStateOf("a")
    var compareMode  by mutableStateOf(false)
    var compareTabId by mutableStateOf("b")

    // ── Transient UI ─────────────────────────────────────────────────
    var ctx           by mutableStateOf<CtxMenuState?>(null)
    var addAnnRequest by mutableStateOf<AddAnnRequest?>(null)   // dialog to add annotation
    var sfDialog      by mutableStateOf(false)
    var sfName        by mutableStateOf("")
    var sfTabId       by mutableStateOf<String?>(null)
    var savedFilters  by mutableStateOf<List<SavedFilter>>(emptyList())
    var tagUsage      by mutableStateOf<Map<String, Int>>(emptyMap())
    var settingsOpen  by mutableStateOf(false)

    var newHlPat   by mutableStateOf("")
    var newHlRx    by mutableStateOf(false)
    var newHlColor by mutableStateOf(HL_COLORS[0])

    var newSeqText  by mutableStateOf("")
    var newSeqRegex by mutableStateOf(false)
    var newSeqColor by mutableStateOf(SEQ_COLORS[0])

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
    fun clearFilter(tabId: String) = upFlt(tabId) { Filter() }
    fun toggleExcludeTag(tabId: String, tag: String) = upFlt(tabId) { f ->
        f.copy(excludeTags = if (tag in f.excludeTags) f.excludeTags - tag else f.excludeTags + tag)
    }
    fun setExcludeKw(tabId: String, v: String) = upFlt(tabId) { it.copy(excludeKw = v) }
    fun toggleExcludeKwRx(tabId: String) = upFlt(tabId) { it.copy(excludeKwRegex = !it.excludeKwRegex) }
    fun setKwInTag(tabId: String, v: String) = upFlt(tabId) { it.copy(kwInTag = v) }
    fun toggleKwInTagRx(tabId: String) = upFlt(tabId) { it.copy(kwInTagRegex = !it.kwInTagRegex) }
    fun addPkgPrefix(tabId: String, v: String) { if (v.isNotBlank()) upFlt(tabId) { it.copy(pkgPrefixes = it.pkgPrefixes + v.trim()) } }
    fun removePkgPrefix(tabId: String, v: String) = upFlt(tabId) { it.copy(pkgPrefixes = it.pkgPrefixes - v) }
    fun setPidTidFilter(tabId: String, v: String) = upFlt(tabId) { it.copy(pidTidFilter = v) }

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

    // ── Sequences ───────────────────────────────────────────────────
    fun addSequence(text: String, isRegex: Boolean, color: Color) {
        if (text.isBlank()) return
        val maxP = sequences.maxOfOrNull { it.priority } ?: 0
        sequences = sequences + SequenceDef("seq${System.currentTimeMillis()}", text, isRegex, maxP + 1, color)
        newSeqText = ""
        newSeqColor = SEQ_COLORS[(SEQ_COLORS.indexOf(color) + 1) % SEQ_COLORS.size]
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
        val t = tab(tabId) ?: return; val f = t.filter
        savedFilters = savedFilters + SavedFilter(
            "sf${System.currentTimeMillis()}", name, f.levels, f.activeTags, f.kwText, f.kwRegex,
            f.mode, f.excludeTags, f.excludeKw, f.excludeKwRegex, f.highlighters, f.seqOn,
        )
        sfDialog = false; sfName = ""
    }
    fun loadFilter(tabId: String, sf: SavedFilter) = upFlt(tabId) { _ ->
        Filter(sf.levels, sf.activeTags, sf.kwText, sf.kwRegex, sf.mode, sf.excludeTags, sf.excludeKw, sf.excludeKwRegex, sf.highlighters, sf.seqOn)
    }
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
            appendLine("    \"seqOn\": ${sf.seqOn}")
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
            SavedFilter(
                "sf${System.currentTimeMillis()}_${name.hashCode()}", name,
                levels.ifEmpty { LogLevel.entries.toSet() }, activeTags, kwText, kwRegex, mode,
                excludeTags, excludeKw, excludeKwRegex, emptyList(), seqOn,
            )
        }
        savedFilters = savedFilters + imported
    }

    // ── Row selection ────────────────────────────────────────────────
    fun selRow(tabId: String, id: Int, multi: Boolean, range: Boolean) = upTab(tabId) { t ->
        val n = when {
            multi -> if (id in t.selected) t.selected - id else t.selected + id
            range -> {
                val visIds = t.logData.filter { passesFilter(it, t.filter) }.map { it.id }.ifEmpty { t.logData.map { it.id } }
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
    fun clearSelection(tabId: String) = upTab(tabId) { it.copy(selected = emptySet()) }

    // ── Sequence expand/collapse ─────────────────────────────────────
    fun toggleGroup(tabId: String, gid: String) = upTab(tabId) { t ->
        t.copy(expanded = if (gid in t.expanded) t.expanded - gid else t.expanded + gid)
    }
    fun expandAll(tabId: String) = upTab(tabId) { t ->
        val groups = computeSeqGroups(t.logData, sequences)
        t.copy(expanded = (groups.map { it.gid } + groups.flatMap { g -> g.nested.map { it.gid } }).toSet())
    }
    fun collapseAll(tabId: String) = upTab(tabId) { it.copy(expanded = emptySet()) }
    fun toggleUnfiltered(tabId: String) = upTab(tabId) { it.copy(showUnfiltered = !it.showUnfiltered) }

    // ── Annotations (block model) ────────────────────────────────────
    fun requestAddAnn(tabId: String, logIds: List<Int>) {
        addAnnRequest = AddAnnRequest(tabId, logIds)
        ctx = null
    }
    fun confirmAddAnn(tabId: String, logIds: List<Int>, caption: String) {
        upTab(tabId) { t ->
            val lines = logIds.sorted().mapNotNull { t.rmap[it] }.joinToString("\n") { e ->
                val pid = if (e.pid > 0) "  ${e.pid.toString().padStart(5)} ${e.tid.toString().padStart(5)}" else ""
                "${e.ts}$pid  ${e.level.key}  ${e.tag}: ${e.msg}"
            }
            val body = if (caption.isNotBlank()) "$caption\n\n```\n$lines\n```" else "```\n$lines\n```"
            val block = AnnBlock.Note("n${System.currentTimeMillis()}", body)
            t.copy(annotations = t.annotations.copy(blocks = t.annotations.blocks + block))
        }
        addAnnRequest = null
    }
    fun addNoteBlock(tabId: String, afterId: String? = null) {
        upTab(tabId) { t ->
            val note = AnnBlock.Note("n${System.currentTimeMillis()}", "")
            val blocks = t.annotations.blocks.toMutableList()
            val idx = if (afterId != null) (blocks.indexOfFirst { it.id == afterId } + 1).coerceAtLeast(0) else blocks.size
            blocks.add(idx, note)
            t.copy(annotations = t.annotations.copy(blocks = blocks))
        }
    }
    fun updateBlock(tabId: String, blockId: String, newText: String) = upTab(tabId) { t ->
        t.copy(annotations = t.annotations.copy(blocks = t.annotations.blocks.map { b ->
            when {
                b.id != blockId -> b
                b is AnnBlock.Note   -> b.copy(text = newText)
                b is AnnBlock.LogRef -> b.copy(caption = newText)
                else -> b
            }
        }))
    }
    fun removeBlock(tabId: String, blockId: String) = upTab(tabId) { t ->
        t.copy(annotations = t.annotations.copy(blocks = t.annotations.blocks.filter { it.id != blockId }))
    }
    fun moveBlock(tabId: String, blockId: String, delta: Int) = upTab(tabId) { t ->
        val list = t.annotations.blocks.toMutableList()
        val idx = list.indexOfFirst { it.id == blockId }.takeIf { it >= 0 } ?: return@upTab t
        val to = (idx + delta).coerceIn(0, list.lastIndex)
        val item = list.removeAt(idx); list.add(to, item)
        t.copy(annotations = t.annotations.copy(blocks = list))
    }
    fun setPrefix(tabId: String, v: String) = upTab(tabId) { t -> t.copy(annotations = t.annotations.copy(prefix = v)) }
    fun setSuffix(tabId: String, v: String) = upTab(tabId) { t -> t.copy(annotations = t.annotations.copy(suffix = v)) }
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
        addSequence(text, false, newSeqColor); ctx = null
    }
    fun addNegKwFromCtx() {
        val c = ctx ?: return
        val entry = tab(c.tabId)?.rmap?.get(c.entryId) ?: return
        val text = if (c.selText.isBlank()) entry.msg else extractMsgText(c.selText, entry)
        setExcludeKw(c.tabId, text); ctx = null
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
        val t = mkTab("t$n", "sample_$n.log", LOG_A.take(20))
        tabs = tabs + t; activeTabId = t.id
    }
    fun closeTab(tabId: String) {
        val next = tabs.filter { it.id != tabId }; if (next.isEmpty()) return
        if (activeTabId == tabId) activeTabId = next.last().id
        if (compareTabId == tabId) compareTabId = next.first().id
        tabs = next
    }
    fun openFile(file: File) {
        isLoading = true
        ioScope.launch {
            val logData = runCatching { parseLogcat(file) }.getOrElse { emptyList() }
            val n = tabCounter++
            val t = mkTab("t$n", file.name, logData)
            withContext(Dispatchers.Main) {
                tabs = tabs + t
                activeTabId = t.id
                isLoading = false
            }
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
        File(dir + path).writeText(buildMd(t))
    }
    fun pickSaveFolder() {
        val dlg = FileDialog(null as Frame?, "Choose Save Folder", FileDialog.LOAD).apply { isVisible = true }
        dlg.directory?.let { settings = settings.copy(defaultSaveDir = it) }
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
        runCatching { importFilters(File(dir + path).readText()) }
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
