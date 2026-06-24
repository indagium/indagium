package com.openlog.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterPanel(
    tab: LogTab,
    sequences: List<SequenceDef>,
    savedFilters: List<SavedFilter>,
    tagUsage: Map<String, Int>,
    newHlPat: String, newHlRx: Boolean, newHlColor: Color,
    newSeqText: String, newSeqRegex: Boolean, newSeqColor: Color,
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
    onAddSeq: (String, Boolean, Color) -> Unit,
    onRemoveSeq: (String) -> Unit,
    onToggleSeqEnabled: (String) -> Unit,
    onSetSeqColor: (String, Color) -> Unit,
    onMoveSeqUp: (String) -> Unit,
    onMoveSeqDown: (String) -> Unit,
    onSetNewSeqText: (String) -> Unit,
    onSetNewSeqRx: (Boolean) -> Unit,
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
    onClearFilter: () -> Unit,
    width: Float,
) {
    val tc = tc()
    val filter = tab.filter

    // Tags sorted by frequency in log data; show top 10 unless user is searching
    val tagCounts  = remember(tab.id) { tab.logData.groupBy { it.tag }.mapValues { it.value.size } }
    val sortedTags = remember(tab.id, tagCounts) { tagCounts.entries.sortedByDescending { it.value }.map { it.key } }

    var tagSearch by remember { mutableStateOf("") }
    var colorPickerSeqId by remember { mutableStateOf<String?>(null) }

    // When searching show all matches; otherwise top 10 + any already-active tags
    val filteredTags = if (tagSearch.isBlank())
        (sortedTags.take(10) + filter.activeTags).distinct()
    else
        sortedTags.filter { it.contains(tagSearch, ignoreCase = true) }

    // Debounced keyword state — display updates immediately, filter applies after 150ms pause
    var kwDisplay by remember(tab.id) { mutableStateOf(filter.kwText) }
    LaunchedEffect(tab.id, filter.kwText) { if (filter.kwText != kwDisplay) kwDisplay = filter.kwText }
    LaunchedEffect(kwDisplay) { kotlinx.coroutines.delay(150); if (kwDisplay != filter.kwText) onSetKw(kwDisplay) }

    val scroll = rememberScrollState()
    Column(
        Modifier.width(width.dp).fillMaxHeight().background(tc.p)
            .border(BorderStroke(1.dp, tc.br)).verticalScroll(scroll),
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
                        TagPill(tag, tc.ac) { onToggleTag(tag) }
                    }
                }
            }

            // Tag search
            InlineField(tagSearch, { tagSearch = it }, "Search tags…", Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))

            filteredTags.forEach { tag ->
                val active = tag in filter.activeTags
                HoverBox(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    baseBg = if (active) tc.abg else Color.Transparent,
                    hoverBg = if (active) tc.abg else tc.hv,
                    onClick = { onToggleTag(tag) },
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(5.dp).background(if (active) tc.ac else tc.td, RoundedCornerShape(50)))
                        AppText(tag, color = if (active) tc.tx else tc.ts, fontSize = 11.sp, fontFamily = MONO,
                            modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
                        if ((tagUsage[tag] ?: 0) > 2) AppText("★", color = tc.ac.copy(.8f), fontSize = 9.sp)
                        AppText(tab.logData.count { it.tag == tag }.toString(), color = tc.td, fontSize = 10.sp, fontFamily = MONO)
                    }
                }
            }
            Divider()

            // ── Negative: Exclude Tags ────────────────────────────
            val exNeg = Color(0xFFf85149)
            SectionHeader("EXCLUDE TAGS")
            if (filter.excludeTags.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    filter.excludeTags.forEach { tag -> TagPill(tag, exNeg) { onToggleExcludeTag(tag) } }
                }
            }
            // Search box for exclude tags
            var exTagSearch by remember { mutableStateOf("") }
            InlineField(exTagSearch, { exTagSearch = it }, "Search to exclude…",
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
            val exCandidates = sortedTags
                .filter { it !in filter.activeTags }
                .let { list -> if (exTagSearch.isBlank()) list else list.filter { it.contains(exTagSearch, ignoreCase = true) } }
            exCandidates.take(10).forEach { tag ->
                val excluded = tag in filter.excludeTags
                HoverBox(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    baseBg = if (excluded) exNeg.copy(.12f) else Color.Transparent,
                    hoverBg = tc.hv,
                    onClick = { onToggleExcludeTag(tag) },
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AppText("−", color = if (excluded) exNeg else tc.td, fontSize = 12.sp)
                        AppText(tag, color = if (excluded) exNeg else tc.ts, fontSize = 11.sp, fontFamily = MONO,
                            modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
                        AppText(tab.logData.count { it.tag == tag }.toString(), color = tc.td, fontSize = 10.sp, fontFamily = MONO)
                    }
                }
            }
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
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
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
                            (if (def.isRegex) "/" else "") + def.matchText + (if (def.isRegex) "/" else ""),
                            color = if (def.enabled) tc.tx else tc.td,
                            fontSize = 11.sp, fontFamily = MONO, modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis,
                        )
                        AppText(if (def.enabled) "●" else "○", color = if (def.enabled) def.color else tc.td,
                            fontSize = 11.sp, modifier = Modifier.clickable { onToggleSeqEnabled(def.id) })
                        AppText("×", color = tc.td, fontSize = 13.sp, modifier = Modifier.clickable { onRemoveSeq(def.id) })
                    }
                    if (colorPickerSeqId == def.id) {
                        Row(Modifier.fillMaxWidth().padding(start = 30.dp, end = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                InlineField(newSeqText, onSetNewSeqText, "boundary text…", Modifier.weight(1f))
                PillBtn(".*", active = newSeqRegex) { onSetNewSeqRx(!newSeqRegex) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SEQ_COLORS.take(6).forEach { c -> ColorSwatch(c, c == newSeqColor) { onSetNewSeqColor(c) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SEQ_COLORS.drop(6).forEach { c -> ColorSwatch(c, c == newSeqColor) { onSetNewSeqColor(c) } }
                    Spacer(Modifier.weight(1f))
                    PillBtn("+ Add", active = newSeqText.isNotBlank()) { onAddSeq(newSeqText, newSeqRegex, newSeqColor) }
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
        }
    }
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
    val tc = tc()
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
