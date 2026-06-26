package com.openlog.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlog.model.*
import java.io.File
import java.net.URI

@OptIn(
    ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)
@Composable
fun FilterPanel(
    tab: LogTab,
    sequences: List<SequenceDef>,
    savedFilters: List<SavedFilter>,
    tagUsage: Map<String, Int>,
    newHlPat: String, newHlRx: Boolean, newHlColor: Color,
    newSeqText: String,
    newSeqRegex: Boolean,
    newSeqEndText: String,
    newSeqEndRegex: Boolean,
    newSeqStartTag: String,
    newSeqEndTag: String,
    newSeqColor: Color,
    newMsgRulePattern: String,
    newMsgRuleRegex: Boolean,
    newMsgRuleInclude: Boolean,
    newMsgRuleTag: String,
    newMsgRulePrefix: String,
    onToggleLevel: (LogLevel) -> Unit,
    onSetFilterMode: (FilterMode) -> Unit,
    onToggleTag: (String) -> Unit,
    onClearTags: () -> Unit,
    onToggleExcludeTag: (String) -> Unit,
    onSetKw: (String) -> Unit,
    onToggleKwRx: () -> Unit,
    onSetExcludeKw: (String) -> Unit,
    onToggleExcludeKwRx: () -> Unit,
    onToggleSeq: () -> Unit,
    onAddSeq: (String, Boolean, Color, String?, String?, Boolean, String?) -> Unit,
    onRemoveSeq: (String) -> Unit,
    onToggleSeqEnabled: (String) -> Unit,
    onSetSeqColor: (String, Color) -> Unit,
    onUpdateSeq: (String, String, Boolean, String?, String?, Boolean, String?) -> Unit,
    onToggleManualCollapse: (String) -> Unit,
    onRemoveManualCollapse: (String) -> Unit,
    onAddMessageRule: (Boolean, String, Boolean, String?, String?) -> Unit,
    onToggleMessageRule: (String) -> Unit,
    onRemoveMessageRule: (String) -> Unit,
    onSetNewMsgRulePattern: (String) -> Unit,
    onSetNewMsgRuleRegex: (Boolean) -> Unit,
    onSetNewMsgRuleInclude: (Boolean) -> Unit,
    onSetNewMsgRuleTag: (String) -> Unit,
    onSetNewMsgRulePrefix: (String) -> Unit,
    onMoveSeqUp: (String) -> Unit,
    onMoveSeqDown: (String) -> Unit,
    onSetNewSeqText: (String) -> Unit,
    onSetNewSeqRx: (Boolean) -> Unit,
    onSetNewSeqEndText: (String) -> Unit,
    onSetNewSeqEndRx: (Boolean) -> Unit,
    onSetNewSeqStartTag: (String) -> Unit,
    onSetNewSeqEndTag: (String) -> Unit,
    onSetNewSeqColor: (Color) -> Unit,
    onAddHl: (String, Boolean, Color) -> Unit,
    onRemoveHl: (String) -> Unit,
    onToggleHl: (String) -> Unit,
    onSetNewHlPat: (String) -> Unit,
    onSetNewHlRx: (Boolean) -> Unit,
    onSetNewHlColor: (Color) -> Unit,
    onLoadFilter: (SavedFilter) -> Unit,
    onDeleteSF: (String) -> Unit,
    onOpenSFDialog: () -> Unit,
    onSetKwInTag: (String) -> Unit,
    onToggleKwInTagRx: () -> Unit,
    onAddPkgPrefix: (String) -> Unit,
    onRemovePkgPrefix: (String) -> Unit,
    onSetPidTidFilter: (String) -> Unit,
    onExportFilters: () -> Unit,
    onImportFilters: () -> Unit,
    onImportFiltersFromFiles: (List<File>) -> Unit,
    onClearFilter: () -> Unit,
    mostUsedTagLimit: Int,
    width: Float,
) {
    val tc = tc()
    val filter = tab.filter

    // Tags sorted by frequency in log data; default suggestions come from user tag usage.
    val tagCounts  = remember(tab.id) { tab.logData.groupBy { it.tag }.mapValues { it.value.size } }
    val sortedTags = remember(tab.id, tagCounts) { tagCounts.entries.sortedByDescending { it.value }.map { it.key } }

    var tagInput by remember { mutableStateOf("") }
    var tagSearch by remember { mutableStateOf("") }
    var exTagInput by remember { mutableStateOf("") }
    var exTagSearch by remember { mutableStateOf("") }
    var colorPickerSeqId by remember { mutableStateOf<String?>(null) }
    var editingSeqId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tagInput) {
        kotlinx.coroutines.delay(120)
        tagSearch = tagInput
    }
    LaunchedEffect(exTagInput) {
        kotlinx.coroutines.delay(120)
        exTagSearch = exTagInput
    }

    val filteredTags = remember(sortedTags, tagSearch, filter.activeTags, filter.pkgPrefixes, tagUsage, mostUsedTagLimit) {
        tagCandidates(
            sortedTags = sortedTags,
            search = tagSearch,
            selectedTags = filter.activeTags,
            packagePrefixes = filter.pkgPrefixes,
            tagUsage = tagUsage,
            mostUsedLimit = mostUsedTagLimit,
        )
    }
    val exCandidates = remember(sortedTags, exTagSearch, filter.excludeTags, filter.pkgPrefixes, filter.activeTags, tagUsage, mostUsedTagLimit) {
        tagCandidates(
            sortedTags = sortedTags,
            search = exTagSearch,
            selectedTags = filter.excludeTags,
            packagePrefixes = filter.pkgPrefixes,
            tagUsage = tagUsage,
            excludeTags = filter.activeTags,
            mostUsedLimit = mostUsedTagLimit,
        )
    }

    // Debounced keyword state — display updates immediately, filter applies after 150ms pause
    var kwDisplay by remember(tab.id) { mutableStateOf(filter.kwText) }
    LaunchedEffect(tab.id, filter.kwText) { if (filter.kwText != kwDisplay) kwDisplay = filter.kwText }
    LaunchedEffect(kwDisplay) { kotlinx.coroutines.delay(150); if (kwDisplay != filter.kwText) onSetKw(kwDisplay) }

    val scroll = rememberScrollState()
    val filterDropTarget = remember(onImportFiltersFromFiles) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = runCatching { (event.dragData() as DragData.FilesList).readFiles() }.getOrElse { return false }
                    .mapNotNull { uri -> runCatching { File(URI.create(uri)) }.getOrNull() }
                    .filter { it.exists() && it.extension.equals("json", ignoreCase = true) }
                if (files.isEmpty()) return false
                onImportFiltersFromFiles(files)
                return true
            }
        }
    }
    Column(
        Modifier.width(width.dp).fillMaxHeight().background(tc.p)
            .border(BorderStroke(1.dp, tc.br))
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    runCatching { event.dragData() is DragData.FilesList }.getOrElse { false }
                },
                target = filterDropTarget,
            )
            .verticalScroll(scroll),
    ) {
        // ── Log Level ─────────────────────────────────────────────
        SectionHeader("LOG LEVEL", trailing = {
            AppText("clear", color = tc.td, fontSize = 10.sp, modifier = Modifier.clickable(onClick = onClearFilter))
        })
        LogLevel.entries.forEach { lvl ->
            val on = lvl in filter.levels
            val cnt = tab.logData.count { it.level == lvl }
            Row(
                Modifier.fillMaxWidth().clickable { onToggleLevel(lvl) }.padding(horizontal = 12.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.Checkbox(
                    checked = on, onCheckedChange = { onToggleLevel(lvl) },
                    colors = androidx.compose.material3.CheckboxDefaults.colors(
                        checkedColor = lvl.defaultColor, uncheckedColor = tc.td, checkmarkColor = tc.bg),
                    modifier = Modifier.size(16.dp),
                )
                LevelBadge(lvl)
                AppText(lvl.label, color = tc.ts, fontSize = 12.sp, modifier = Modifier.weight(1f))
                AppText(cnt.toString(), color = tc.td, fontSize = 11.sp, fontFamily = MONO)
            }
        }
        Divider()

        // ── Filter mode toggle ────────────────────────────────────
        SectionHeader("FILTER MODE")
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ModeBtn("Tags", filter.mode == FilterMode.TAGS) { onSetFilterMode(FilterMode.TAGS) }
            ModeBtn("Keyword/Regex", filter.mode == FilterMode.KEYWORD) { onSetFilterMode(FilterMode.KEYWORD) }
        }
        Divider()

        // ── Positive: Tags ────────────────────────────────────────
        if (filter.mode == FilterMode.TAGS) {
            // ── Package prefix ───────────────────────────────────
            SectionHeader("PACKAGE / TAG PREFIX")
            if (filter.pkgPrefixes.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    filter.pkgPrefixes.forEach { pfx ->
                        TagPill(pfx, Color(0xFF06b6d4)) { onRemovePkgPrefix(pfx) }
                    }
                }
            }
            var pkgInput by remember { mutableStateOf("") }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                InlineField(pkgInput, { pkgInput = it }, "com.myapp.example…", Modifier.weight(1f))
                PillBtn("+ Add", active = pkgInput.isNotBlank()) {
                    onAddPkgPrefix(pkgInput); pkgInput = ""
                }
            }
            packagePrefixCandidates(sortedTags, pkgInput).forEach { candidate ->
                HoverBox(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    onClick = { onAddPkgPrefix(candidate); pkgInput = "" },
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AppText("+", color = Color(0xFF06b6d4), fontSize = 11.sp)
                        AppText(candidate, color = tc.ts, fontSize = 11.sp, fontFamily = MONO, modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Divider()

            SectionHeader("INCLUDE TAGS", trailing = if (filter.activeTags.isNotEmpty()) ({
                AppText("clear", color = tc.td, fontSize = 10.sp, modifier = Modifier.clickable(onClick = onClearTags))
            }) else null)

            if (filter.activeTags.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    filter.activeTags.forEach { tag ->
                        TagPill(displayTagForPrefix(tag, filter.pkgPrefixes).first, tc.ac) { onToggleTag(tag) }
                    }
                }
            }

            // Tag search
            InlineField(tagInput, { tagInput = it }, "Search tags…", Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))

            filteredTags.forEach { tag ->
                val active = tag in filter.activeTags
                HoverBox(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    baseBg = if (active) tc.abg else Color.Transparent,
                    hoverBg = if (active) tc.abg else tc.hv,
                    onClick = { onToggleTag(tag) },
                ) {
                    val (label, packageLabel) = displayTagForPrefix(tag, filter.pkgPrefixes)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(5.dp).background(if (active) tc.ac else tc.td, RoundedCornerShape(50)))
                        Column(Modifier.weight(1f)) {
                            AppText(label, color = if (active) tc.tx else tc.ts, fontSize = 11.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis)
                            if (packageLabel != null)
                                AppText(packageLabel, color = tc.td, fontSize = 9.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis)
                        }
                        if ((tagUsage[tag] ?: 0) > 2) AppText("★", color = tc.ac.copy(.8f), fontSize = 9.sp)
                        AppText((tagCounts[tag] ?: 0).toString(), color = tc.td, fontSize = 10.sp, fontFamily = MONO)
                    }
                }
            }
            Divider()

            // ── Negative: Exclude Tags ────────────────────────────
            val exNeg = Color(0xFFf85149)
            SectionHeader("EXCLUDE TAGS")
            if (filter.excludeTags.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    filter.excludeTags.forEach { tag -> TagPill(displayTagForPrefix(tag, filter.pkgPrefixes).first, exNeg) { onToggleExcludeTag(tag) } }
                }
            }
            // Search box for exclude tags
            InlineField(exTagInput, { exTagInput = it }, "Search to exclude…",
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
            exCandidates.forEach { tag ->
                val excluded = tag in filter.excludeTags
                HoverBox(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    baseBg = if (excluded) exNeg.copy(.12f) else Color.Transparent,
                    hoverBg = tc.hv,
                    onClick = { onToggleExcludeTag(tag) },
                ) {
                    val (label, packageLabel) = displayTagForPrefix(tag, filter.pkgPrefixes)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AppText("−", color = if (excluded) exNeg else tc.td, fontSize = 12.sp)
                        Column(Modifier.weight(1f)) {
                            AppText(label, color = if (excluded) exNeg else tc.ts, fontSize = 11.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis)
                            if (packageLabel != null)
                                AppText(packageLabel, color = tc.td, fontSize = 9.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis)
                        }
                        AppText((tagCounts[tag] ?: 0).toString(), color = tc.td, fontSize = 10.sp, fontFamily = MONO)
                    }
                }
            }

            // ── Message keyword within tag result set ─────────────
            SectionHeader("MESSAGE CONTAINS (in tag results)")
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                InlineField(filter.kwInTag, onSetKwInTag, if (filter.kwInTagRegex) "/pattern/…" else "keyword…", Modifier.weight(1f))
                PillBtn(".*", active = filter.kwInTagRegex, onClick = onToggleKwInTagRx)
                if (filter.kwInTag.isNotBlank())
                    AppText("×", color = tc.td, fontSize = 14.sp, modifier = Modifier.clickable { onSetKwInTag("") })
            }
            Divider()

        }

        // ── Positive: Keyword / Regex ─────────────────────────────
        if (filter.mode == FilterMode.KEYWORD) {
            SectionHeader("INCLUDE KEYWORD")
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                InlineField(kwDisplay, { kwDisplay = it }, if (filter.kwRegex) "/pattern/…" else "keyword…", Modifier.weight(1f))
                PillBtn(".*", active = filter.kwRegex, onClick = onToggleKwRx)
            }
            // Negative keyword
            SectionHeader("EXCLUDE KEYWORD")
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                InlineField(filter.excludeKw, onSetExcludeKw, "exclude pattern…", Modifier.weight(1f))
                PillBtn(".*", active = filter.excludeKwRegex, onClick = onToggleExcludeKwRx)
            }
            Divider()
        }

        // ── Explicit include/exclude message rules ───────────────
        SectionHeader("MESSAGE RULES")
        filter.messageRules.forEach { rule ->
            Row(
                Modifier.fillMaxWidth()
                    .background(if (rule.enabled) Color.Transparent else tc.hv)
                    .padding(horizontal = 12.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                AppText(if (rule.include) "+" else "−", color = if (rule.include) Color(0xFF3fb950) else Color(0xFFf85149), fontSize = 12.sp)
                Column(Modifier.weight(1f)) {
                    AppText(
                        messageRuleLabel(rule),
                        color = if (rule.enabled) tc.tx else tc.td,
                        fontSize = 11.sp,
                        fontFamily = MONO,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AppText(
                        messageRuleScope(rule, filter.pkgPrefixes),
                        color = tc.td,
                        fontSize = 9.sp,
                        fontFamily = MONO,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AppText(if (rule.enabled) "●" else "○", color = if (rule.enabled) tc.ac else tc.td,
                    fontSize = 11.sp, modifier = Modifier.clickable { onToggleMessageRule(rule.id) })
                AppText("×", color = tc.td, fontSize = 13.sp, modifier = Modifier.clickable { onRemoveMessageRule(rule.id) })
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                InlineField(newMsgRulePattern, onSetNewMsgRulePattern, "message text…", Modifier.weight(1f))
                PillBtn(".*", active = newMsgRuleRegex) { onSetNewMsgRuleRegex(!newMsgRuleRegex) }
                PillBtn(if (newMsgRuleInclude) "Include" else "Exclude", active = newMsgRuleInclude) {
                    onSetNewMsgRuleInclude(!newMsgRuleInclude)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                InlineField(newMsgRuleTag, onSetNewMsgRuleTag, "exact tag scope…", Modifier.weight(1f))
                InlineField(newMsgRulePrefix, onSetNewMsgRulePrefix, "package prefix…", Modifier.weight(1f))
            }
            PillBtn("+ Add message rule", active = newMsgRulePattern.isNotBlank()) {
                onAddMessageRule(
                    newMsgRuleInclude,
                    newMsgRulePattern,
                    newMsgRuleRegex,
                    newMsgRuleTag,
                    newMsgRulePrefix,
                )
            }
        }
        Divider()

        // ── Highlighters ──────────────────────────────────────────
        SectionHeader("HIGHLIGHTERS")
        filter.highlighters.forEach { hl ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier.size(10.dp).background(hl.color, RoundedCornerShape(2.dp))
                        .border(2.dp, if (hl.on) hl.color else tc.br, RoundedCornerShape(2.dp))
                        .clickable { onToggleHl(hl.id) },
                )
                AppText((if (hl.regex) "/" else "") + hl.pattern + (if (hl.regex) "/i" else ""),
                    color = tc.tx, fontSize = 11.sp, fontFamily = MONO, modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
                AppText("×", color = tc.td, fontSize = 14.sp, modifier = Modifier.clickable { onRemoveHl(hl.id) })
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                InlineField(newHlPat, onSetNewHlPat, "text or /regex/…", Modifier.weight(1f))
                PillBtn(".*", active = newHlRx, onClick = { onSetNewHlRx(!newHlRx) })
                PillBtn("+ Add", active = newHlPat.isNotBlank()) { onAddHl(newHlPat, newHlRx, newHlColor) }
            }
            // Color picker row — selected color has bright border
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                HL_COLORS.forEach { c -> ColorSwatch(c, c == newHlColor) { onSetNewHlColor(c) } }
            }
        }
        Divider()

        // ── PID / TID filter ──────────────────────────────────────
        SectionHeader("PID / TID FILTER")
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            InlineField(filter.pidTidFilter, onSetPidTidFilter, "1234, 5678…", Modifier.weight(1f))
            if (filter.pidTidFilter.isNotBlank())
                AppText("×", color = tc.td, fontSize = 14.sp, modifier = Modifier.clickable { onSetPidTidFilter("") })
        }
        Divider()

        // ── Sequences ─────────────────────────────────────────────
        SectionHeader("SEQUENCES")
        CheckRow(filter.seqOn, { onToggleSeq() }) {
            AppText("Group sequences", color = tc.ts, fontSize = 12.sp, modifier = Modifier.weight(1f))
        }
        if (sequences.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            // Drag-and-drop state
            var dragId by remember { mutableStateOf<String?>(null) }
            var dragOffsetY by remember { mutableStateOf(0f) }
            val density = LocalDensity.current.density

            sequences.forEachIndexed { idx, def ->
                val isDragging = dragId == def.id
                Column(
                    Modifier.fillMaxWidth()
                        .then(if (isDragging) Modifier.offset { IntOffset(0, dragOffsetY.roundToInt()) } else Modifier)
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (isDragging) tc.ac.copy(.12f) else if (!def.enabled) tc.hv else Color.Transparent)
                            .padding(horizontal = 12.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        // Drag handle
                        AppText("⠿", color = tc.td, fontSize = 12.sp,
                            modifier = Modifier.pointerInput(def.id) {
                                detectDragGestures(
                                    onDragStart = { dragId = def.id; dragOffsetY = 0f },
                                    onDragEnd = {
                                        val rowH = 28f * density
                                        val steps = (dragOffsetY / rowH).roundToInt()
                                        val targetIdx = (idx + steps).coerceIn(0, sequences.lastIndex)
                                        if (targetIdx != idx) {
                                            repeat(kotlin.math.abs(targetIdx - idx)) {
                                                if (targetIdx < idx) onMoveSeqUp(def.id)
                                                else onMoveSeqDown(def.id)
                                            }
                                        }
                                        dragId = null; dragOffsetY = 0f
                                    },
                                    onDragCancel = { dragId = null; dragOffsetY = 0f },
                                    onDrag = { ch, d -> ch.consume(); dragOffsetY += d.y },
                                )
                            })
                        AppText("${idx + 1}", color = tc.td, fontSize = 9.sp, fontFamily = MONO, modifier = Modifier.width(12.dp))
                        Box(
                            Modifier.size(10.dp)
                                .background(if (def.enabled) def.color else tc.br, RoundedCornerShape(2.dp))
                                .clickable { colorPickerSeqId = if (colorPickerSeqId == def.id) null else def.id },
                        )
                        AppText(
                            sequenceLabel(def),
                            color = if (def.enabled) tc.tx else tc.td,
                            fontSize = 11.sp, fontFamily = MONO, modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis,
                        )
                        AppText("✎", color = tc.td, fontSize = 11.sp, modifier = Modifier.clickable {
                            editingSeqId = if (editingSeqId == def.id) null else def.id
                        })
                        AppText(if (def.enabled) "●" else "○", color = if (def.enabled) def.color else tc.td,
                            fontSize = 11.sp, modifier = Modifier.clickable { onToggleSeqEnabled(def.id) })
                        AppText("×", color = tc.td, fontSize = 13.sp, modifier = Modifier.clickable { onRemoveSeq(def.id) })
                    }
                    if (editingSeqId == def.id) {
                        SequenceEditor(def, onUpdateSeq, onCancel = { editingSeqId = null })
                    }
                    if (colorPickerSeqId == def.id) {
                        FlowRow(
                            Modifier.fillMaxWidth().padding(start = 30.dp, end = 12.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            SEQ_COLORS.forEach { c ->
                                Box(
                                    Modifier.size(14.dp).background(c, RoundedCornerShape(3.dp))
                                        .border(2.dp, if (c == def.color) tc.tx else Color.Transparent, RoundedCornerShape(3.dp))
                                        .clickable { onSetSeqColor(def.id, c); colorPickerSeqId = null },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (tab.manualBlocks.isNotEmpty()) {
            SectionHeader("COLLAPSED RANGES")
            tab.manualBlocks.forEach { block ->
                val entry = tab.rmap[block.anchorId]
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (block.enabled) Color.Transparent else tc.hv)
                        .padding(horizontal = 12.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(Modifier.size(10.dp).background(if (block.enabled) block.color else tc.br, RoundedCornerShape(2.dp)))
                    val direction = if (block.direction == ManualCollapseDirection.TO_START) "to start" else "to end"
                    Column(Modifier.weight(1f)) {
                        AppText(direction, color = if (block.enabled) tc.tx else tc.td, fontSize = 11.sp, fontFamily = MONO)
                        AppText(
                            listOfNotNull(entry?.tag, entry?.msg).joinToString(": "),
                            color = tc.td,
                            fontSize = 9.sp,
                            fontFamily = MONO,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    AppText(if (block.enabled) "●" else "○", color = if (block.enabled) block.color else tc.td,
                        fontSize = 11.sp, modifier = Modifier.clickable { onToggleManualCollapse(block.id) })
                    AppText("×", color = tc.td, fontSize = 13.sp, modifier = Modifier.clickable { onRemoveManualCollapse(block.id) })
                }
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                InlineField(newSeqText, onSetNewSeqText, "start message…", Modifier.weight(1f))
                PillBtn(".*", active = newSeqRegex) { onSetNewSeqRx(!newSeqRegex) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                InlineField(newSeqEndText, onSetNewSeqEndText, "end message (optional)…", Modifier.weight(1f))
                PillBtn(".*", active = newSeqEndRegex) { onSetNewSeqEndRx(!newSeqEndRegex) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                InlineField(newSeqStartTag, onSetNewSeqStartTag, "start tag (optional)…", Modifier.weight(1f))
                InlineField(newSeqEndTag, onSetNewSeqEndTag, "end tag (optional)…", Modifier.weight(1f))
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SEQ_COLORS.forEach { c -> ColorSwatch(c, c == newSeqColor) { onSetNewSeqColor(c) } }
                PillBtn("+ Add", active = newSeqText.isNotBlank()) {
                    onAddSeq(newSeqText, newSeqRegex, newSeqColor, newSeqStartTag, newSeqEndText, newSeqEndRegex, newSeqEndTag)
                }
            }
        }
        Divider()

        // ── Saved Filters ─────────────────────────────────────────
        SectionHeader("SAVED FILTERS")
        savedFilters.forEach { sf ->
            HoverBox(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AppText("▸", color = tc.td, fontSize = 10.sp)
                    AppText(sf.name, color = tc.ts, fontSize = 11.sp, modifier = Modifier.weight(1f).clickable { onLoadFilter(sf) }, overflow = TextOverflow.Ellipsis)
                    AppText("×", color = tc.td, fontSize = 12.sp, modifier = Modifier.clickable { onDeleteSF(sf.id) })
                }
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier.fillMaxWidth().border(1.dp, tc.br, RoundedCornerShape(4.dp))
                    .clickable(onClick = onOpenSFDialog).padding(vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) { AppText("+ Save current filter…", color = tc.td, fontSize = 11.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillBtn("Export", active = false, onClick = onExportFilters)
                PillBtn("Import", active = false, onClick = onImportFilters)
            }
            AppText("Drop filter .json here to import", color = tc.td, fontSize = 10.sp)
        }
    }
}

@Composable
private fun SequenceEditor(
    def: SequenceDef,
    onUpdateSeq: (String, String, Boolean, String?, String?, Boolean, String?) -> Unit,
    onCancel: () -> Unit,
) {
    var startText by remember(def.id) { mutableStateOf(def.matchText) }
    var startRegex by remember(def.id) { mutableStateOf(def.isRegex) }
    var startTag by remember(def.id) { mutableStateOf(def.tag ?: "") }
    var endText by remember(def.id) { mutableStateOf(def.endMatchText ?: "") }
    var endRegex by remember(def.id) { mutableStateOf(def.endIsRegex) }
    var endTag by remember(def.id) { mutableStateOf(def.endTag ?: "") }

    Column(
        Modifier.fillMaxWidth().padding(start = 30.dp, end = 12.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            InlineField(startText, { startText = it }, "start message…", Modifier.weight(1f))
            PillBtn(".*", active = startRegex) { startRegex = !startRegex }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            InlineField(endText, { endText = it }, "end message…", Modifier.weight(1f))
            PillBtn(".*", active = endRegex) { endRegex = !endRegex }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            InlineField(startTag, { startTag = it }, "start tag…", Modifier.weight(1f))
            InlineField(endTag, { endTag = it }, "end tag…", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            PillBtn("Save", active = startText.isNotBlank()) {
                onUpdateSeq(def.id, startText, startRegex, startTag, endText, endRegex, endTag)
                onCancel()
            }
            PillBtn("Cancel", active = false, onClick = onCancel)
        }
    }
}

private fun sequenceLabel(def: SequenceDef): String {
    val start = scopedSequencePart(def.tag, def.matchText, def.isRegex)
    val endText = def.endMatchText?.takeIf { it.isNotBlank() } ?: return start
    return "$start -> ${scopedSequencePart(def.endTag, endText, def.endIsRegex)}"
}

private fun scopedSequencePart(tag: String?, text: String, isRegex: Boolean): String {
    val pattern = if (isRegex) "/$text/" else text
    return (tag?.let { "$it: " } ?: "") + pattern
}

private fun messageRuleLabel(rule: MessageRule): String {
    val pattern = if (rule.regex) "/${rule.pattern}/" else rule.pattern
    return (if (rule.include) "show only " else "hide ") + pattern
}

private fun messageRuleScope(rule: MessageRule, prefixes: Set<String>): String {
    rule.tag?.takeIf { it.isNotBlank() }?.let { tag ->
        val (label, pkg) = displayTagForPrefix(tag, prefixes)
        return if (pkg == null) "tag: $label" else "tag: $pkg / $label"
    }
    rule.packagePrefix?.takeIf { it.isNotBlank() }?.let { return "package: $it" }
    return "all tags"
}

@Composable
private fun ModeBtn(label: String, active: Boolean, onClick: () -> Unit) {
    val tc = tc()
    Box(
        Modifier
            .border(1.dp, if (active) tc.ac else tc.br, RoundedCornerShape(4.dp))
            .background(if (active) tc.ac.copy(.15f) else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) { AppText(label, color = if (active) tc.ac else tc.ts, fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal) }
}

@Composable
private fun TagPill(tag: String, color: Color, onRemove: () -> Unit) {
    Box(
        Modifier.background(color.copy(.13f), RoundedCornerShape(3.dp))
            .border(1.dp, color.copy(.27f), RoundedCornerShape(3.dp))
            .clickable(onClick = onRemove)
            .padding(start = 7.dp, end = 4.dp, top = 1.dp, bottom = 1.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            AppText(tag, color = color, fontSize = 11.sp, fontFamily = MONO)
            AppText("×", color = color.copy(.7f), fontSize = 13.sp)
        }
    }
}
