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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlog.model.*
import com.openlog.utils.passesFilter
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
    activeSavedFilterId: String?,
    tagUsage: Map<String, Int>,
    newHlPat: String, newHlRx: Boolean, newHlColor: Color,
    newSeqText: String,
    newSeqRegex: Boolean,
    newSeqEndText: String,
    newSeqEndRegex: Boolean,
    newSeqStartTag: String,
    newSeqEndTag: String,
    newSeqColor: Color,
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
    onAddMessageRule: (Boolean, String, Boolean, String?, String?, RuleTarget) -> Unit,
    onToggleMessageRule: (String) -> Unit,
    onRemoveMessageRule: (String) -> Unit,
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
    onSetHlColor: (String, Color) -> Unit,
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
    var tagFieldFocused by remember { mutableStateOf(false) }
    var showTagCandidates by remember { mutableStateOf(false) }
    var tagSelectedIdx by remember { mutableStateOf(-1) }
    var tagSelectedAction by remember { mutableStateOf(0) } // 0 = include, 1 = exclude
    var colorPickerSeqId by remember { mutableStateOf<String?>(null) }
    var colorPickerHlId by remember { mutableStateOf<String?>(null) }
    var editingSeqId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tagInput) {
        kotlinx.coroutines.delay(120)
        tagSearch = tagInput
    }
    LaunchedEffect(tagFieldFocused) {
        if (tagFieldFocused) showTagCandidates = true
        else { kotlinx.coroutines.delay(100); if (!tagFieldFocused) showTagCandidates = false }
    }

    val unifiedTagCandidates = remember(sortedTags, tagSearch, filter.pkgPrefixes, tagUsage, mostUsedTagLimit) {
        tagCandidates(
            sortedTags = sortedTags,
            search = tagSearch,
            selectedTags = emptySet(),
            packagePrefixes = filter.pkgPrefixes,
            tagUsage = tagUsage,
            mostUsedLimit = mostUsedTagLimit,
        )
    }

    // Debounced keyword state — display updates immediately, filter applies after 150ms pause
    var kwDisplay by remember(tab.id) { mutableStateOf(filter.kwText) }
    LaunchedEffect(tab.id, filter.kwText) { if (filter.kwText != kwDisplay) kwDisplay = filter.kwText }
    LaunchedEffect(kwDisplay) { kotlinx.coroutines.delay(150); if (kwDisplay != filter.kwText) onSetKw(kwDisplay) }

    var pkgPillsExpanded by remember { mutableStateOf(true) }
    var incPillsExpanded by remember { mutableStateOf(false) }
    var excPillsExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(filter.activeTags.isEmpty()) {
        if (filter.activeTags.isNotEmpty()) { incPillsExpanded = true; excPillsExpanded = false }
    }
    LaunchedEffect(filter.excludeTags.isEmpty()) {
        if (filter.excludeTags.isNotEmpty()) { excPillsExpanded = true; incPillsExpanded = false }
    }
    var kwExpanded by remember { mutableStateOf(true) }
    var msgRuleInput by remember(tab.id) { mutableStateOf(filter.kwInTag) }
    var msgRuleSearch by remember { mutableStateOf("") }
    var msgRuleFieldFocused by remember { mutableStateOf(false) }
    var showMsgRuleCandidates by remember { mutableStateOf(false) }
    var msgCandidatesHovered by remember { mutableStateOf(false) }
    var msgRuleSelectedIdx by remember { mutableStateOf(-1) }
    var msgRuleSelectedAction by remember { mutableStateOf(0) } // 0 = include, 1 = exclude
    var incMsgPillsExpanded by remember { mutableStateOf(false) }
    var excMsgPillsExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(tab.id, filter.kwInTag) { if (filter.kwInTag != msgRuleInput) msgRuleInput = filter.kwInTag }
    LaunchedEffect(msgRuleInput) {
        kotlinx.coroutines.delay(120)
        msgRuleSearch = msgRuleInput
        if (msgRuleInput != filter.kwInTag) onSetKwInTag(msgRuleInput)
    }
    LaunchedEffect(msgRuleFieldFocused, msgCandidatesHovered) {
        if (msgRuleFieldFocused || msgCandidatesHovered) showMsgRuleCandidates = true
        else { kotlinx.coroutines.delay(100); if (!msgRuleFieldFocused && !msgCandidatesHovered) showMsgRuleCandidates = false }
    }
    LaunchedEffect(filter.messageRules.none { it.include }) {
        if (filter.messageRules.any { it.include }) { incMsgPillsExpanded = true; excMsgPillsExpanded = false }
    }
    LaunchedEffect(filter.messageRules.none { !it.include }) {
        if (filter.messageRules.any { !it.include }) { excMsgPillsExpanded = true; incMsgPillsExpanded = false }
    }
    var hlExpanded       by remember { mutableStateOf(true) }
    var pidExpanded      by remember { mutableStateOf(true) }
    var seqExpanded      by remember { mutableStateOf(true) }
    var sfExpanded       by remember { mutableStateOf(true) }

    // Unified candidates: PIDs when field is blank, message stems + matching PIDs when not blank.
    // Each candidate carries its RuleTarget so the correct rule type is added on click.
    val unifiedCandidates = remember(tab.id, filter, msgRuleSearch) {
        if (msgRuleSearch.isBlank()) emptyList()
        else {
            val baseFilter = filter.copy(kwInTag = "", messageRules = emptyList(), pidTidFilter = "")
            // PIDs only when the search looks like a number
            val pidCandidates = if (msgRuleSearch.any { it.isDigit() })
                tab.logData
                    .filter { entry -> passesFilter(entry, baseFilter) && entry.pid != 0 }
                    .map { it.pid.toString() }.distinct()
                    .filter { it.contains(msgRuleSearch) }
                    .take(3)
                    .map { Pair(it, RuleTarget.PID_TID) }
            else emptyList()
            // Message stem/full candidates
            val matchingMsgs = tab.logData
                .filter { entry -> passesFilter(entry, baseFilter) && entry.msg.contains(msgRuleSearch, ignoreCase = true) }
                .map { it.msg }
            val stems = matchingMsgs.map { msg ->
                val sepIdx = msg.indexOfFirst { it == ':' || it == '(' }
                if (sepIdx > 0) msg.substring(0, sepIdx).trim().takeIf { it.isNotBlank() } ?: msg.take(80)
                else msg.take(80)
            }.distinct()
            val fulls = matchingMsgs.map { it.take(80) }.distinct().filter { it !in stems }
            val msgCandidates = (stems + fulls).take(8 - pidCandidates.size).map { Pair(it, RuleTarget.MESSAGE) }
            pidCandidates + msgCandidates
        }
    }
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
        SectionHeader("LOG LEVEL")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogLevel.entries.forEach { lvl ->
                val on = lvl in filter.levels
                val color = lvl.defaultColor
                Box(
                    Modifier
                        .size(22.dp)
                        .background(if (on) color.copy(.28f) else Color.Transparent, RoundedCornerShape(4.dp))
                        .border(1.dp, if (on) color else tc.br.copy(.45f), RoundedCornerShape(4.dp))
                        .clickable { onToggleLevel(lvl) },
                    contentAlignment = Alignment.Center,
                ) {
                    AppText(lvl.key.toString(), color = if (on) color else tc.td.copy(.4f), fontSize = 11.sp, fontFamily = MONO, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Divider()

        // ── Filter mode tabs ──────────────────────────────────────
        Row(Modifier.fillMaxWidth()) {
            listOf("Tags" to FilterMode.TAGS, "Keyword / Regex" to FilterMode.KEYWORD).forEach { (label, mode) ->
                val active = filter.mode == mode
                Column(
                    Modifier.weight(1f).clickable { onSetFilterMode(mode) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AppText(
                        label,
                        color = if (active) tc.ac else tc.td,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) tc.ac else tc.br))
                }
            }
        }

        // ── Positive: Tags ────────────────────────────────────────
        if (filter.mode == FilterMode.TAGS) {
            // ── Package prefix ───────────────────────────────────
            SectionHeader(
                "PACKAGE / TAG PREFIX",
                trailing = if (filter.pkgPrefixes.isNotEmpty()) ({
                    Row(
                        Modifier.clickable { pkgPillsExpanded = !pkgPillsExpanded }
                            .padding(start = 6.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        AppText("${filter.pkgPrefixes.size} active", color = tc.td, fontSize = 11.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                        Box(
                            Modifier.size(16.dp).background(tc.br.copy(.5f), RoundedCornerShape(3.dp)),
                            contentAlignment = Alignment.Center,
                        ) { AppText(if (pkgPillsExpanded) "▾" else "▸", color = tc.ts, fontSize = 12.sp) }
                    }
                }) else null,
            )
            if (filter.pkgPrefixes.isNotEmpty() && pkgPillsExpanded) {
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
            var pkgSelectedIdx by remember { mutableStateOf(-1) }
            val pkgCandidates = packagePrefixCandidates(sortedTags, pkgInput)
            InlineField(
                pkgInput,
                { pkgInput = it; pkgSelectedIdx = -1 },
                "com.myapp.example…",
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .onPreviewKeyEvent { ev ->
                        when {
                            ev.type != KeyEventType.KeyDown -> false
                            ev.key == Key.DirectionDown -> { pkgSelectedIdx = (pkgSelectedIdx + 1).coerceAtMost(pkgCandidates.lastIndex); true }
                            ev.key == Key.DirectionUp -> { pkgSelectedIdx = (pkgSelectedIdx - 1).coerceAtLeast(-1); true }
                            ev.key == Key.Enter -> {
                                val text = pkgCandidates.getOrNull(pkgSelectedIdx) ?: pkgInput
                                if (text.isNotBlank()) { onAddPkgPrefix(text); pkgInput = ""; pkgSelectedIdx = -1 }
                                true
                            }
                            else -> false
                        }
                    },
            )
            if (pkgCandidates.isNotEmpty()) {
                ScrollableItems(pkgCandidates.size) {
                    pkgCandidates.forEachIndexed { idx, candidate ->
                        val isSelected = idx == pkgSelectedIdx
                        HoverBox(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                            baseBg = if (isSelected) tc.abg else Color.Transparent,
                            hoverBg = tc.hv,
                            onClick = { onAddPkgPrefix(candidate); pkgInput = ""; pkgSelectedIdx = -1 },
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                AppText("+", color = Color(0xFF06b6d4), fontSize = 11.sp)
                                AppText(candidate, color = if (isSelected) tc.tx else tc.ts, fontSize = 11.sp, fontFamily = MONO, modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Divider()

            // ── Tags (combined include + exclude) ─────────────────
            val exNeg = Color(0xFFf85149)
            SectionHeader(
                "TAGS",
                trailing = if (filter.activeTags.isNotEmpty() || filter.excludeTags.isNotEmpty()) ({
                    if (filter.activeTags.isNotEmpty()) {
                        Row(
                            Modifier.clickable {
                                incPillsExpanded = !incPillsExpanded
                                if (incPillsExpanded) excPillsExpanded = false
                            }.padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            AppText("${filter.activeTags.size} included", color = tc.ac, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                            AppText(if (incPillsExpanded) "▾" else "▸", color = tc.ac, fontSize = 10.sp)
                        }
                    }
                    if (filter.excludeTags.isNotEmpty()) {
                        Row(
                            Modifier.clickable {
                                excPillsExpanded = !excPillsExpanded
                                if (excPillsExpanded) incPillsExpanded = false
                            }.padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            AppText("${filter.excludeTags.size} excluded", color = exNeg, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                            AppText(if (excPillsExpanded) "▾" else "▸", color = exNeg, fontSize = 10.sp)
                        }
                    }
                }) else null,
            )

            if (filter.activeTags.isNotEmpty() && incPillsExpanded) {
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

            if (filter.excludeTags.isNotEmpty() && excPillsExpanded) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    filter.excludeTags.forEach { tag ->
                        TagPill(displayTagForPrefix(tag, filter.pkgPrefixes).first, exNeg) { onToggleExcludeTag(tag) }
                    }
                }
            }

            InlineField(
                tagInput,
                { tagInput = it; tagSelectedIdx = -1 },
                "Search tags…",
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .onFocusChanged { tagFieldFocused = it.isFocused }
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.DirectionDown -> { tagSelectedIdx = (tagSelectedIdx + 1).coerceAtMost(unifiedTagCandidates.lastIndex); true }
                            Key.DirectionUp -> { tagSelectedIdx = (tagSelectedIdx - 1).coerceAtLeast(-1); true }
                            Key.DirectionRight -> { tagSelectedAction = 1; true }
                            Key.DirectionLeft -> { tagSelectedAction = 0; true }
                            Key.Enter -> {
                                val tag = unifiedTagCandidates.getOrNull(tagSelectedIdx) ?: return@onPreviewKeyEvent false
                                if (tagSelectedAction == 0) onToggleTag(tag) else onToggleExcludeTag(tag)
                                true
                            }
                            else -> false
                        }
                    },
            )

            if (showTagCandidates && unifiedTagCandidates.isNotEmpty()) {
                ScrollableItems(unifiedTagCandidates.size, maxDp = 220) {
                    unifiedTagCandidates.forEachIndexed { idx, tag ->
                        val isIncluded = tag in filter.activeTags
                        val isExcluded = tag in filter.excludeTags
                        val isRowSelected = idx == tagSelectedIdx
                        val (label, packageLabel) = displayTagForPrefix(tag, filter.pkgPrefixes)
                        HoverBox(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                            baseBg = if (isRowSelected) tc.abg else Color.Transparent,
                            hoverBg = tc.hv,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(Modifier.size(5.dp).background(when {
                                    isIncluded -> tc.ac
                                    isExcluded -> exNeg
                                    else -> tc.td
                                }, RoundedCornerShape(50)))
                                Column(Modifier.weight(1f)) {
                                    AppText(label, color = when {
                                        isIncluded -> tc.tx
                                        isExcluded -> exNeg.copy(.8f)
                                        else -> tc.ts
                                    }, fontSize = 11.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis)
                                    if (packageLabel != null)
                                        AppText(packageLabel, color = tc.td, fontSize = 9.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis)
                                }
                                if ((tagUsage[tag] ?: 0) > 2) AppText("★", color = tc.ac.copy(.8f), fontSize = 9.sp)
                                AppText((tagCounts[tag] ?: 0).toString(), color = tc.td, fontSize = 10.sp, fontFamily = MONO)
                                val incHighlight = isIncluded
                                val incKbd = isRowSelected && tagSelectedAction == 0
                                Box(
                                    Modifier.size(20.dp)
                                        .background(if (incHighlight) tc.ac.copy(.2f) else if (incKbd) tc.ac.copy(.1f) else Color.Transparent, RoundedCornerShape(3.dp))
                                        .border(1.dp, if (incHighlight || incKbd) tc.ac else tc.br, RoundedCornerShape(3.dp))
                                        .clickable { onToggleTag(tag) },
                                    contentAlignment = Alignment.Center,
                                ) { AppText("+", color = if (incHighlight || incKbd) tc.ac else tc.ts, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                                val exHighlight = isExcluded
                                val exKbd = isRowSelected && tagSelectedAction == 1
                                Box(
                                    Modifier.size(20.dp)
                                        .background(if (exHighlight) exNeg.copy(.2f) else if (exKbd) exNeg.copy(.1f) else Color.Transparent, RoundedCornerShape(3.dp))
                                        .border(1.dp, if (exHighlight || exKbd) exNeg else tc.br, RoundedCornerShape(3.dp))
                                        .clickable { onToggleExcludeTag(tag) },
                                    contentAlignment = Alignment.Center,
                                ) { AppText("−", color = if (exHighlight || exKbd) exNeg else tc.ts, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                            }
                        }
                    }
                }
            }
            Divider()
        }

        // ── Positive: Keyword / Regex ─────────────────────────────
        if (filter.mode == FilterMode.KEYWORD) {
            SectionHeader("KEYWORD FILTER", expanded = kwExpanded, onToggle = { kwExpanded = !kwExpanded })
            if (kwExpanded) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    InlineField(kwDisplay, { kwDisplay = it }, if (filter.kwRegex) "/include pattern/…" else "include keyword…", Modifier.weight(1f))
                    PillBtn(".*", active = filter.kwRegex, onClick = onToggleKwRx)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    InlineField(filter.excludeKw, onSetExcludeKw, if (filter.excludeKwRegex) "/exclude pattern/…" else "exclude keyword…", Modifier.weight(1f))
                    PillBtn(".*", active = filter.excludeKwRegex, onClick = onToggleExcludeKwRx)
                }
            }
            Divider()
        }

        // ── Message Rules (combined search + include/exclude) ─────────────
        val msgExNeg = Color(0xFFf85149)
        val msgInc = filter.messageRules.filter { it.include }
        val msgExc = filter.messageRules.filter { !it.include }
        SectionHeader(
            "MESSAGE RULES",
            trailing = if (msgInc.isNotEmpty() || msgExc.isNotEmpty()) ({
                if (msgInc.isNotEmpty()) {
                    Row(
                        Modifier.clickable {
                            incMsgPillsExpanded = !incMsgPillsExpanded
                            if (incMsgPillsExpanded) excMsgPillsExpanded = false
                        }.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        AppText("${msgInc.size} included", color = tc.ac, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                        AppText(if (incMsgPillsExpanded) "▾" else "▸", color = tc.ac, fontSize = 10.sp)
                    }
                }
                if (msgExc.isNotEmpty()) {
                    Row(
                        Modifier.clickable {
                            excMsgPillsExpanded = !excMsgPillsExpanded
                            if (excMsgPillsExpanded) incMsgPillsExpanded = false
                        }.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        AppText("${msgExc.size} excluded", color = msgExNeg, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                        AppText(if (excMsgPillsExpanded) "▾" else "▸", color = msgExNeg, fontSize = 10.sp)
                    }
                }
            }) else null,
        )
        if (msgInc.isNotEmpty() && incMsgPillsExpanded) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                msgInc.forEach { rule ->
                    val label = if (rule.target == RuleTarget.PID_TID) "pid:${rule.pattern}"
                                else if (rule.regex) "/${rule.pattern}/" else rule.pattern
                    TagPill(label, tc.ac) { onRemoveMessageRule(rule.id) }
                }
            }
        }
        if (msgExc.isNotEmpty() && excMsgPillsExpanded) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                msgExc.forEach { rule ->
                    val label = if (rule.target == RuleTarget.PID_TID) "pid:${rule.pattern}"
                                else if (rule.regex) "/${rule.pattern}/" else rule.pattern
                    TagPill(label, msgExNeg) { onRemoveMessageRule(rule.id) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            InlineField(
                msgRuleInput,
                { msgRuleInput = it; msgRuleSelectedIdx = -1 },
                if (filter.kwInTagRegex) "/pattern/…" else "search in messages…",
                Modifier.weight(1f)
                    .onFocusChanged { msgRuleFieldFocused = it.isFocused }
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.DirectionDown -> { msgRuleSelectedIdx = (msgRuleSelectedIdx + 1).coerceAtMost(unifiedCandidates.lastIndex); true }
                            Key.DirectionUp -> { msgRuleSelectedIdx = (msgRuleSelectedIdx - 1).coerceAtLeast(-1); true }
                            Key.DirectionRight -> { msgRuleSelectedAction = 1; true }
                            Key.DirectionLeft -> { msgRuleSelectedAction = 0; true }
                            Key.Enter -> {
                                val c = unifiedCandidates.getOrNull(msgRuleSelectedIdx) ?: return@onPreviewKeyEvent false
                                onAddMessageRule(msgRuleSelectedAction == 0, c.first, false, null, null, c.second)
                                msgRuleInput = ""; msgRuleSelectedIdx = -1
                                true
                            }
                            else -> false
                        }
                    },
            )
            if (msgRuleInput.isNotBlank())
                AppText("×", color = tc.td, fontSize = 14.sp, modifier = Modifier.clickable { msgRuleInput = ""; onSetKwInTag("") })
        }
        if (showMsgRuleCandidates && unifiedCandidates.isNotEmpty()) {
            ScrollableItems(unifiedCandidates.size, maxDp = 220,
                modifier = Modifier
                    .onPointerEvent(PointerEventType.Enter) { msgCandidatesHovered = true }
                    .onPointerEvent(PointerEventType.Exit)  { msgCandidatesHovered = false }
            ) {
                unifiedCandidates.forEachIndexed { idx, (pattern, target) ->
                    val isPid = target == RuleTarget.PID_TID
                    val isIncluded = if (isPid) msgInc.any { it.target == RuleTarget.PID_TID && it.pattern == pattern }
                                     else msgInc.any { it.target == RuleTarget.MESSAGE && it.pattern == pattern && !it.regex }
                    val isExcluded = if (isPid) msgExc.any { it.target == RuleTarget.PID_TID && it.pattern == pattern }
                                     else msgExc.any { it.target == RuleTarget.MESSAGE && it.pattern == pattern && !it.regex }
                    val isRowSelected = idx == msgRuleSelectedIdx
                    HoverBox(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        baseBg = if (isRowSelected) tc.abg else Color.Transparent,
                        hoverBg = tc.hv,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(Modifier.size(5.dp).background(when {
                                isIncluded -> tc.ac
                                isExcluded -> msgExNeg
                                else -> tc.td
                            }, RoundedCornerShape(50)))
                            if (isPid) {
                                AppText("pid", color = tc.td.copy(.7f), fontSize = 9.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(end = 2.dp))
                            }
                            AppText(
                                pattern,
                                color = when {
                                    isIncluded -> tc.tx
                                    isExcluded -> msgExNeg.copy(.8f)
                                    else -> tc.ts
                                },
                                fontSize = 11.sp,
                                fontFamily = MONO,
                                modifier = Modifier.weight(1f),
                                overflow = TextOverflow.Ellipsis,
                            )
                            val incHighlight = isIncluded
                            val incKbd = isRowSelected && msgRuleSelectedAction == 0
                            Box(
                                Modifier.size(20.dp)
                                    .background(if (incHighlight) tc.ac.copy(.2f) else if (incKbd) tc.ac.copy(.1f) else Color.Transparent, RoundedCornerShape(3.dp))
                                    .border(1.dp, if (incHighlight || incKbd) tc.ac else tc.br, RoundedCornerShape(3.dp))
                                    .clickable { onAddMessageRule(true, pattern, false, null, null, target); msgRuleInput = "" },
                                contentAlignment = Alignment.Center,
                            ) { AppText("+", color = if (incHighlight || incKbd) tc.ac else tc.ts, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                            val exHighlight = isExcluded
                            val exKbd = isRowSelected && msgRuleSelectedAction == 1
                            Box(
                                Modifier.size(20.dp)
                                    .background(if (exHighlight) msgExNeg.copy(.2f) else if (exKbd) msgExNeg.copy(.1f) else Color.Transparent, RoundedCornerShape(3.dp))
                                    .border(1.dp, if (exHighlight || exKbd) msgExNeg else tc.br, RoundedCornerShape(3.dp))
                                    .clickable { onAddMessageRule(false, pattern, false, null, null, target); msgRuleInput = "" },
                                contentAlignment = Alignment.Center,
                            ) { AppText("−", color = if (exHighlight || exKbd) msgExNeg else tc.ts, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }
        }
        Divider()

        // ── Highlighters ──────────────────────────────────────────
        SectionHeader("HIGHLIGHTERS", expanded = hlExpanded, onToggle = { hlExpanded = !hlExpanded })
        if (hlExpanded) {
            if (filter.highlighters.isNotEmpty()) {
                Column(Modifier.fillMaxWidth()) {
                    filter.highlighters.forEach { hl ->
                        Column {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    Modifier.size(12.dp).background(hl.color, RoundedCornerShape(2.dp))
                                        .border(2.dp, if (colorPickerHlId == hl.id) tc.tx else hl.color, RoundedCornerShape(2.dp))
                                        .clickable { colorPickerHlId = if (colorPickerHlId == hl.id) null else hl.id },
                                )
                                AppText((if (hl.regex) "/" else "") + hl.pattern + (if (hl.regex) "/i" else ""),
                                    color = if (hl.on) tc.tx else tc.td, fontSize = 11.sp, fontFamily = MONO, modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
                                AppText(if (hl.on) "●" else "○", color = if (hl.on) hl.color else tc.td,
                                    fontSize = 11.sp, modifier = Modifier.clickable { onToggleHl(hl.id) })
                                AppText("×", color = tc.td, fontSize = 14.sp, modifier = Modifier.clickable { onRemoveHl(hl.id) })
                            }
                            if (colorPickerHlId == hl.id) {
                                FlowRow(
                                    Modifier.fillMaxWidth().padding(start = 30.dp, end = 12.dp, bottom = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    HL_COLORS.forEach { c -> ColorSwatch(c, c == hl.color) { onSetHlColor(hl.id, c); colorPickerHlId = null } }
                                }
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    InlineField(
                        newHlPat, onSetNewHlPat, "text or /regex/…",
                        Modifier.weight(1f).onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && ev.key == Key.Enter && newHlPat.isNotBlank()) {
                                onAddHl(newHlPat, newHlRx, newHlColor); true
                            } else false
                        },
                    )
                    PillBtn(".*", active = newHlRx, onClick = { onSetNewHlRx(!newHlRx) })
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    HL_COLORS.forEach { c -> ColorSwatch(c, c == newHlColor) { onSetNewHlColor(c) } }
                }
            }
        }
        Divider()

        // ── Sequences ─────────────────────────────────────────────
        SectionHeader("SEQUENCES", expanded = seqExpanded, onToggle = { seqExpanded = !seqExpanded })
        if (seqExpanded) {
            CheckRow(filter.seqOn, { onToggleSeq() }) {
                AppText("Group sequences", color = tc.ts, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
            if (sequences.isNotEmpty()) {
                var dragId by remember { mutableStateOf<String?>(null) }
                var dragOffsetY by remember { mutableStateOf(0f) }
                val density = LocalDensity.current.density
                Column(Modifier.fillMaxWidth()) {
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
            }
            if (tab.manualBlocks.isNotEmpty()) {
                AppText("COLLAPSED RANGES", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                ScrollableItems(tab.manualBlocks.size) {
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
                                    color = tc.td, fontSize = 9.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            AppText(if (block.enabled) "●" else "○", color = if (block.enabled) block.color else tc.td,
                                fontSize = 11.sp, modifier = Modifier.clickable { onToggleManualCollapse(block.id) })
                            AppText("×", color = tc.td, fontSize = 13.sp, modifier = Modifier.clickable { onRemoveManualCollapse(block.id) })
                        }
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
        }
        Divider()

        // ── Saved Filters ─────────────────────────────────────────
        SectionHeader("SAVED FILTERS", expanded = sfExpanded, onToggle = { sfExpanded = !sfExpanded })
        if (sfExpanded) {
            if (savedFilters.isNotEmpty()) {
                ScrollableItems(savedFilters.size) {
                    savedFilters.forEach { sf ->
                        val active = activeSavedFilterId == sf.id
                        HoverBox(modifier = Modifier.fillMaxWidth(), baseBg = if (active) tc.abg else Color.Transparent) {
                            Row(
                                Modifier.fillMaxWidth().clickable { onLoadFilter(sf) }.padding(horizontal = 12.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                AppText(if (active) "●" else "○", color = if (active) tc.ac else tc.td, fontSize = 12.sp)
                                AppText(sf.name, color = if (active) tc.tx else tc.ts, fontSize = 11.sp, modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
                                AppText("×", color = tc.td, fontSize = 12.sp, modifier = Modifier.clickable { onDeleteSF(sf.id) })
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier.fillMaxWidth().border(1.dp, tc.br, RoundedCornerShape(4.dp))
                        .clickable(onClick = onOpenSFDialog).padding(vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) { AppText("+ Save current filter…", color = tc.td, fontSize = 11.sp) }
                Box(
                    Modifier.fillMaxWidth().border(1.dp, Color(0xFFf85149).copy(.45f), RoundedCornerShape(4.dp))
                        .background(Color(0xFFf85149).copy(.08f), RoundedCornerShape(4.dp))
                        .clickable(onClick = onClearFilter).padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { AppText("Clear filters", color = Color(0xFFf85149), fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillBtn("Export", active = false, onClick = onExportFilters)
                    PillBtn("Import", active = false, onClick = onImportFilters)
                }
                AppText("Drop filter .json here to import", color = tc.td, fontSize = 10.sp)
            }
        }
    }
}

// Bounded scrollable list that works inside a verticalScroll parent.
// heightIn(max=X) breaks inside an unbounded parent; height(X) is reliable.
@Composable
private fun ScrollableItems(
    itemCount: Int,
    rowDp: Int = 28,
    maxDp: Int = 150,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (itemCount == 0) return
    val h = (itemCount * rowDp).coerceAtMost(maxDp).dp
    Column(modifier.fillMaxWidth().height(h).verticalScroll(rememberScrollState()), content = content)
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
