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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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

// Collapse/expand state for the filter panel. Held outside the composable so it survives
// the panel being hidden and re-shown (FilterPanel is removed from the tree when invisible).
class FilterPanelUiState {
    var hlListExpanded      by mutableStateOf(true)
    var lvlExpanded         by mutableStateOf(true)
    var seqExpanded         by mutableStateOf(true)
    var sfExpanded          by mutableStateOf(true)
    var incPillsExpanded    by mutableStateOf(true)
    var incMsgPillsExpanded by mutableStateOf(true)
    var excMsgPillsExpanded by mutableStateOf(true)
}

@OptIn(
    ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)
@Composable
fun FilterPanel(
    tab: LogTab,
    fpState: FilterPanelUiState,
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

    val tagFr = remember { FocusRequester() }
    val msgRuleFr = remember { FocusRequester() }
    val hlFr = remember { FocusRequester() }

    var tagInput by remember { mutableStateOf("") }
    var tagSearch by remember { mutableStateOf("") }
    var tagFieldFocused by remember { mutableStateOf(false) }
    var tagCandidatesHovered by remember { mutableStateOf(false) }
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
    LaunchedEffect(tagFieldFocused, tagCandidatesHovered) {
        if (tagFieldFocused || tagCandidatesHovered) showTagCandidates = true
        else { kotlinx.coroutines.delay(100); if (!tagFieldFocused && !tagCandidatesHovered) showTagCandidates = false }
    }

    // Combined candidates: pkg-prefix matches first (only when search has input), then tag matches.
    // Each entry is (value, isPkg): isPkg=true → add as package prefix; false → include/exclude as tag.
    val combinedTagCandidates = remember(sortedTags, tagSearch, filter.pkgPrefixes, tagUsage, mostUsedTagLimit) {
        val pkgs = packagePrefixCandidates(sortedTags, tagSearch, limit = 4).map { it to true }
        val tags = tagCandidates(
            sortedTags = sortedTags,
            search = tagSearch,
            selectedTags = emptySet(),
            packagePrefixes = filter.pkgPrefixes,
            tagUsage = tagUsage,
            mostUsedLimit = mostUsedTagLimit,
        ).map { it to false }
        pkgs + tags
    }

    // Debounced keyword state — display updates immediately, filter applies after 150ms pause.
    // kwLastSent tracks the last value we pushed so we don't sync our own debounce back as an external change.
    var kwDisplay by remember(tab.id) { mutableStateOf(filter.kwText) }
    var kwLastSent by remember(tab.id) { mutableStateOf(filter.kwText) }
    LaunchedEffect(kwDisplay) {
        val snap = kwDisplay
        kotlinx.coroutines.delay(150)
        if (snap == kwDisplay) { kwLastSent = kwDisplay; onSetKw(kwDisplay) }
    }
    LaunchedEffect(filter.kwText) { if (filter.kwText != kwLastSent) kwDisplay = filter.kwText }

    var msgRuleInput by remember(tab.id) { mutableStateOf(filter.kwInTag) }
    var msgRuleLastSent by remember(tab.id) { mutableStateOf(filter.kwInTag) }
    var msgRuleSearch by remember { mutableStateOf("") }
    var msgRuleFieldFocused by remember { mutableStateOf(false) }
    var showMsgRuleCandidates by remember { mutableStateOf(false) }
    var msgCandidatesHovered by remember { mutableStateOf(false) }
    var msgRuleSelectedIdx by remember { mutableStateOf(-1) }
    var msgRuleSelectedAction by remember { mutableStateOf(0) } // 0 = include, 1 = exclude
    LaunchedEffect(msgRuleInput) {
        val snap = msgRuleInput
        kotlinx.coroutines.delay(120)
        if (snap == msgRuleInput) {
            msgRuleSearch = msgRuleInput
            msgRuleLastSent = msgRuleInput
            onSetKwInTag(msgRuleInput)
        }
    }
    LaunchedEffect(filter.kwInTag) { if (filter.kwInTag != msgRuleLastSent) msgRuleInput = filter.kwInTag }
    LaunchedEffect(msgRuleFieldFocused, msgCandidatesHovered) {
        if (msgRuleFieldFocused || msgCandidatesHovered) showMsgRuleCandidates = true
        else { kotlinx.coroutines.delay(100); if (!msgRuleFieldFocused && !msgCandidatesHovered) showMsgRuleCandidates = false }
    }
    var hlColorPickerOpen   by remember { mutableStateOf(false) }
    var seqAddOpen          by remember { mutableStateOf(false) }
    var seqColorPickerOpen  by remember { mutableStateOf(false) }

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
        // ── Filter mode tabs ──────────────────────────────────────
        Row(Modifier.fillMaxWidth()) {
            listOf("Tags" to FilterMode.TAGS, "Regex" to FilterMode.KEYWORD).forEach { (label, mode) ->
                val active = filter.mode == mode
                Column(
                    Modifier.weight(1f).clickable {
                        onSetFilterMode(mode)
                        if (mode == FilterMode.KEYWORD && !filter.kwRegex) onToggleKwRx()
                    },
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
            val pkgColor = PKG_CYAN
            val exNeg = DANGER_RED
            val totalActive = filter.pkgPrefixes.size + filter.activeTags.size + filter.excludeTags.size
            // ── unified TAGS section header with combined pill count ──
            SectionHeader(
                "TAGS",
                trailing = if (totalActive > 0) ({
                    Row(
                        Modifier.clickable { fpState.incPillsExpanded = !fpState.incPillsExpanded }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        if (filter.pkgPrefixes.isNotEmpty())
                            AppText("${filter.pkgPrefixes.size} pkg", color = pkgColor, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                        if (filter.activeTags.isNotEmpty())
                            AppText("${filter.activeTags.size}+", color = tc.ac, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                        if (filter.excludeTags.isNotEmpty())
                            AppText("${filter.excludeTags.size}−", color = exNeg, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                        AppText(if (fpState.incPillsExpanded) "▾" else "▸", color = tc.ts, fontSize = 10.sp)
                    }
                }) else null,
            )
            // Combined pills for pkg prefixes + included/excluded tags
            if (totalActive > 0 && fpState.incPillsExpanded) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    filter.pkgPrefixes.forEach { pfx -> TagPill(pfx, pkgColor) { onRemovePkgPrefix(pfx) } }
                    filter.activeTags.forEach { tag ->
                        TagPill(displayTagForPrefix(tag, filter.pkgPrefixes).first, tc.ac) { onToggleTag(tag) }
                    }
                    filter.excludeTags.forEach { tag ->
                        TagPill(displayTagForPrefix(tag, filter.pkgPrefixes).first, exNeg) { onToggleExcludeTag(tag) }
                    }
                }
            }
            // ── Unified package prefix / tag search ───────────────
            // Typing a dotted name adds a pkg prefix; typing a tag name adds an include/exclude tag.
            // ←/→ keys switch include vs exclude for tag candidates; Enter with no selection uses the
            // typed text directly (dotted → pkg prefix, plain → include tag).
            InlineField(
                tagInput,
                { tagInput = it; tagSelectedIdx = -1 },
                "pkg prefix or tag…",
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .focusRequester(tagFr)
                    .onFocusChanged { tagFieldFocused = it.isFocused }
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.Tab -> { runCatching { msgRuleFr.requestFocus() }; true }
                            Key.DirectionDown -> { tagSelectedIdx = (tagSelectedIdx + 1).coerceAtMost(combinedTagCandidates.lastIndex); true }
                            Key.DirectionUp -> { tagSelectedIdx = (tagSelectedIdx - 1).coerceAtLeast(-1); true }
                            Key.DirectionRight -> {
                                val c = combinedTagCandidates.getOrNull(tagSelectedIdx)
                                if (c != null && !c.second) { tagSelectedAction = 1; true } else false
                            }
                            Key.DirectionLeft -> {
                                val c = combinedTagCandidates.getOrNull(tagSelectedIdx)
                                if (c != null && !c.second) { tagSelectedAction = 0; true } else false
                            }
                            Key.Enter -> {
                                val c = combinedTagCandidates.getOrNull(tagSelectedIdx)
                                if (c != null) {
                                    if (c.second) onAddPkgPrefix(c.first)
                                    else if (tagSelectedAction == 0) onToggleTag(c.first) else onToggleExcludeTag(c.first)
                                } else if (tagInput.isNotBlank()) {
                                    if (tagInput.contains('.')) onAddPkgPrefix(tagInput) else onToggleTag(tagInput)
                                } else return@onPreviewKeyEvent false
                                tagInput = ""; tagSelectedIdx = -1
                                true
                            }
                            else -> false
                        }
                    },
                onClear = { tagInput = ""; tagSelectedIdx = -1 },
            )
            if (showTagCandidates && combinedTagCandidates.isNotEmpty()) {
                ScrollableItems(combinedTagCandidates.size, maxDp = 220, scrollToIndex = tagSelectedIdx,
                    modifier = Modifier
                        .onPointerEvent(PointerEventType.Enter) { tagCandidatesHovered = true }
                        .onPointerEvent(PointerEventType.Exit)  { tagCandidatesHovered = false }
                ) {
                    combinedTagCandidates.forEachIndexed { idx, (value, isPkg) ->
                        val isRowSelected = idx == tagSelectedIdx
                        if (isPkg) {
                            HoverBox(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                baseBg = if (isRowSelected) tc.abg else Color.Transparent,
                                hoverBg = tc.hv,
                                onClick = { onAddPkgPrefix(value); tagInput = ""; tagSelectedIdx = -1 },
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    AppText("pkg", color = pkgColor.copy(.7f), fontSize = 9.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                                    AppText(value, color = if (isRowSelected) tc.tx else tc.ts, fontSize = 11.sp, fontFamily = MONO,
                                        modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
                                    Box(
                                        Modifier.size(20.dp)
                                            .background(if (isRowSelected) pkgColor.copy(.2f) else Color.Transparent, CORNER_SM)
                                            .border(1.dp, if (isRowSelected) pkgColor else tc.br, CORNER_SM)
                                            .clickable { onAddPkgPrefix(value); tagInput = ""; tagSelectedIdx = -1 },
                                        contentAlignment = Alignment.Center,
                                    ) { AppText("+", color = if (isRowSelected) pkgColor else tc.ts, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                                }
                            }
                        } else {
                            val tag = value
                            val isIncluded = tag in filter.activeTags
                            val isExcluded = tag in filter.excludeTags
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
                                            .background(if (incHighlight) tc.ac.copy(.2f) else if (incKbd) tc.ac.copy(.1f) else Color.Transparent, CORNER_SM)
                                            .border(1.dp, if (incHighlight || incKbd) tc.ac else tc.br, CORNER_SM)
                                            .clickable { onToggleTag(tag) },
                                        contentAlignment = Alignment.Center,
                                    ) { AppText("+", color = if (incHighlight || incKbd) tc.ac else tc.ts, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                                    val exHighlight = isExcluded
                                    val exKbd = isRowSelected && tagSelectedAction == 1
                                    Box(
                                        Modifier.size(20.dp)
                                            .background(if (exHighlight) exNeg.copy(.2f) else if (exKbd) exNeg.copy(.1f) else Color.Transparent, CORNER_SM)
                                            .border(1.dp, if (exHighlight || exKbd) exNeg else tc.br, CORNER_SM)
                                            .clickable { onToggleExcludeTag(tag) },
                                        contentAlignment = Alignment.Center,
                                    ) { AppText("−", color = if (exHighlight || exKbd) exNeg else tc.ts, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                                }
                            }
                        }
                    }
                }
            }
            Divider()
        }

        // ── Regex mode — single pattern field ─────────────────────────
        if (filter.mode == FilterMode.KEYWORD) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                InlineField(kwDisplay, { kwDisplay = it }, "tag or message regex…", Modifier.weight(1f))
                if (kwDisplay.isNotBlank())
                    AppText("×", color = tc.td, fontSize = 14.sp, modifier = Modifier.clickable { kwDisplay = ""; onSetKw("") })
            }
            Divider()
        }

        if (filter.mode == FilterMode.TAGS) {
        // ── Message Rules (combined search + include/exclude) ─────────────
        val msgExNeg = DANGER_RED
        val msgInc = filter.messageRules.filter { it.include }
        val msgExc = filter.messageRules.filter { !it.include }
        SectionHeader(
            "MESSAGE RULES",
            trailing = if (msgInc.isNotEmpty() || msgExc.isNotEmpty()) ({
                if (msgInc.isNotEmpty()) {
                    Row(
                        Modifier.clickable {
                            fpState.incMsgPillsExpanded = !fpState.incMsgPillsExpanded
                            if (fpState.incMsgPillsExpanded) fpState.excMsgPillsExpanded = false
                        }.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        AppText("${msgInc.size} included", color = tc.ac, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                        AppText(if (fpState.incMsgPillsExpanded) "▾" else "▸", color = tc.ac, fontSize = 10.sp)
                    }
                }
                if (msgExc.isNotEmpty()) {
                    Row(
                        Modifier.clickable {
                            fpState.excMsgPillsExpanded = !fpState.excMsgPillsExpanded
                            if (fpState.excMsgPillsExpanded) fpState.incMsgPillsExpanded = false
                        }.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        AppText("${msgExc.size} excluded", color = msgExNeg, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                        AppText(if (fpState.excMsgPillsExpanded) "▾" else "▸", color = msgExNeg, fontSize = 10.sp)
                    }
                }
            }) else null,
        )
        if (msgInc.isNotEmpty() && fpState.incMsgPillsExpanded) {
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
        if (msgExc.isNotEmpty() && fpState.excMsgPillsExpanded) {
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
                    .focusRequester(msgRuleFr)
                    .onFocusChanged { msgRuleFieldFocused = it.isFocused }
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.Tab -> { runCatching { hlFr.requestFocus() }; true }
                            Key.DirectionDown -> { msgRuleSelectedIdx = (msgRuleSelectedIdx + 1).coerceAtMost(unifiedCandidates.lastIndex); true }
                            Key.DirectionUp -> { msgRuleSelectedIdx = (msgRuleSelectedIdx - 1).coerceAtLeast(-1); true }
                            Key.DirectionRight -> { msgRuleSelectedAction = 1; true }
                            Key.DirectionLeft -> { msgRuleSelectedAction = 0; true }
                            Key.Enter -> {
                                val c = unifiedCandidates.getOrNull(msgRuleSelectedIdx)
                                if (c != null) {
                                    onAddMessageRule(msgRuleSelectedAction == 0, c.first, false, null, null, c.second)
                                } else if (msgRuleInput.isNotBlank()) {
                                    // No candidate selected — add typed text directly.
                                    // All-digit input → PID/TID rule; anything else → message rule.
                                    val target = if (msgRuleInput.all { it.isDigit() }) RuleTarget.PID_TID else RuleTarget.MESSAGE
                                    onAddMessageRule(msgRuleSelectedAction == 0, msgRuleInput, false, null, null, target)
                                } else return@onPreviewKeyEvent false
                                msgRuleInput = ""; msgRuleSelectedIdx = -1
                                true
                            }
                            else -> false
                        }
                    },
                onClear = { msgRuleInput = ""; onSetKwInTag("") },
            )
        }
        if (showMsgRuleCandidates && unifiedCandidates.isNotEmpty()) {
            ScrollableItems(unifiedCandidates.size, maxDp = 220, scrollToIndex = msgRuleSelectedIdx,
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
                                    .background(if (incHighlight) tc.ac.copy(.2f) else if (incKbd) tc.ac.copy(.1f) else Color.Transparent, CORNER_SM)
                                    .border(1.dp, if (incHighlight || incKbd) tc.ac else tc.br, CORNER_SM)
                                    .clickable { onAddMessageRule(true, pattern, false, null, null, target); msgRuleInput = "" },
                                contentAlignment = Alignment.Center,
                            ) { AppText("+", color = if (incHighlight || incKbd) tc.ac else tc.ts, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                            val exHighlight = isExcluded
                            val exKbd = isRowSelected && msgRuleSelectedAction == 1
                            Box(
                                Modifier.size(20.dp)
                                    .background(if (exHighlight) msgExNeg.copy(.2f) else if (exKbd) msgExNeg.copy(.1f) else Color.Transparent, CORNER_SM)
                                    .border(1.dp, if (exHighlight || exKbd) msgExNeg else tc.br, CORNER_SM)
                                    .clickable { onAddMessageRule(false, pattern, false, null, null, target); msgRuleInput = "" },
                                contentAlignment = Alignment.Center,
                            ) { AppText("−", color = if (exHighlight || exKbd) msgExNeg else tc.ts, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }
        }
        Divider()
        } // end TAGS-only MESSAGE RULES block

        // ── Highlighters ──────────────────────────────────────────
        // Trailing shows colored dots for each highlighter; clicking collapses/expands the list.
        // The add form is always visible, matching the TAGS section pattern.
        SectionHeader(
            "HIGHLIGHTERS",
            trailing = if (filter.highlighters.isNotEmpty()) ({
                Row(
                    Modifier.clickable { fpState.hlListExpanded = !fpState.hlListExpanded }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    val onCount = filter.highlighters.count { it.on }
                    if (onCount > 0)
                        AppText("$onCount active", color = tc.ac, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                    val offCount = filter.highlighters.size - onCount
                    if (offCount > 0)
                        AppText("$offCount off", color = tc.td, fontSize = 10.sp, fontFamily = UI)
                    AppText(if (fpState.hlListExpanded) "▾" else "▸", color = tc.ts, fontSize = 10.sp)
                }
            }) else null,
        )
        if (filter.highlighters.isNotEmpty() && fpState.hlListExpanded) {
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
            val doAddHl = {
                if (newHlPat.isNotBlank()) {
                    onAddHl(newHlPat, newHlRx, newHlColor)
                    onSetNewHlPat("")
                    val usedColors = (filter.highlighters.map { it.color } + newHlColor).toSet()
                    val idx = HL_COLORS.indexOf(newHlColor)
                    val next = (HL_COLORS.drop(idx + 1) + HL_COLORS).firstOrNull { it !in usedColors }
                        ?: HL_COLORS[(idx + 1) % HL_COLORS.size]
                    onSetNewHlColor(next)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(20.dp)
                        .background(newHlColor, CORNER_SM)
                        .border(1.dp, if (hlColorPickerOpen) tc.tx else tc.br, CORNER_SM)
                        .clickable { hlColorPickerOpen = !hlColorPickerOpen },
                )
                InlineField(
                    newHlPat, onSetNewHlPat, "text or /regex/…",
                    Modifier.weight(1f).focusRequester(hlFr).onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.Enter -> { doAddHl(); true }
                            Key.Tab -> { runCatching { tagFr.requestFocus() }; true }
                            else -> false
                        }
                    },
                    onClear = { onSetNewHlPat("") },
                )
                PillBtn(".*", active = newHlRx, onClick = { onSetNewHlRx(!newHlRx) })
                PillBtn("+ Add", active = newHlPat.isNotBlank(), onClick = doAddHl)
            }
            if (hlColorPickerOpen) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    HL_COLORS.forEach { c ->
                        ColorSwatch(c, c == newHlColor) { onSetNewHlColor(c); hlColorPickerOpen = false }
                    }
                }
            }
        }
        Divider()

        // ── Log Level ─────────────────────────────────────────────
        SectionHeader("LOG LEVEL", expanded = fpState.lvlExpanded, onToggle = { fpState.lvlExpanded = !fpState.lvlExpanded })
        if (fpState.lvlExpanded) {
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
                            .background(if (on) color.copy(.28f) else Color.Transparent, CORNER_MD)
                            .border(1.dp, if (on) color else tc.br.copy(.45f), CORNER_MD)
                            .clickable { onToggleLevel(lvl) },
                        contentAlignment = Alignment.Center,
                    ) {
                        AppText(lvl.key.toString(), color = if (on) color else tc.td.copy(.4f), fontSize = 11.sp, fontFamily = MONO, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Divider()

        // ── Sequences ─────────────────────────────────────────────
        SectionHeader("SEQUENCES", expanded = fpState.seqExpanded, onToggle = { fpState.seqExpanded = !fpState.seqExpanded })
        if (fpState.seqExpanded) {
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
                                AppText("×", color = tc.td, fontSize = 14.sp, modifier = Modifier.clickable { onRemoveSeq(def.id) })
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
                                            Modifier.size(14.dp).background(c, CORNER_SM)
                                                .border(2.dp, if (c == def.color) tc.tx else Color.Transparent, CORNER_SM)
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
                            AppText("×", color = tc.td, fontSize = 14.sp, modifier = Modifier.clickable { onRemoveManualCollapse(block.id) })
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth()
                    .clickable { seqAddOpen = !seqAddOpen }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppText(
                    "+ New sequence…",
                    color = if (seqAddOpen) tc.ac else tc.td,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                AppText(if (seqAddOpen) "▾" else "▸", color = tc.ts, fontSize = 10.sp)
            }
            if (seqAddOpen) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(20.dp)
                                .background(newSeqColor, CORNER_SM)
                                .border(1.dp, if (seqColorPickerOpen) tc.tx else tc.br, CORNER_SM)
                                .clickable { seqColorPickerOpen = !seqColorPickerOpen },
                        )
                        Spacer(Modifier.weight(1f))
                        PillBtn("+ Add", active = newSeqText.isNotBlank()) {
                            onAddSeq(newSeqText, newSeqRegex, newSeqColor, newSeqStartTag, newSeqEndText, newSeqEndRegex, newSeqEndTag)
                            seqAddOpen = false
                            seqColorPickerOpen = false
                        }
                    }
                    if (seqColorPickerOpen) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            SEQ_COLORS.forEach { c ->
                                ColorSwatch(c, c == newSeqColor) { onSetNewSeqColor(c); seqColorPickerOpen = false }
                            }
                        }
                    }
                }
            }
        }
        Divider()

        // ── Saved Filters ─────────────────────────────────────────
        SectionHeader("SAVED FILTERS", expanded = fpState.sfExpanded, onToggle = { fpState.sfExpanded = !fpState.sfExpanded })
        if (fpState.sfExpanded) {
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
                                AppText("×", color = tc.td, fontSize = 14.sp, modifier = Modifier.clickable { onDeleteSF(sf.id) })
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier.fillMaxWidth().border(1.dp, tc.br, CORNER_MD)
                        .clickable(onClick = onOpenSFDialog).padding(vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) { AppText("+ Save current filter…", color = tc.td, fontSize = 11.sp) }
                Box(
                    Modifier.fillMaxWidth().border(1.dp, DANGER_RED.copy(.45f), CORNER_MD)
                        .background(DANGER_RED.copy(.08f), CORNER_MD)
                        .clickable(onClick = onClearFilter).padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { AppText("Clear filters", color = DANGER_RED, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
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
    scrollToIndex: Int = -1,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (itemCount == 0) return
    val h = (itemCount * rowDp).coerceAtMost(maxDp).dp
    val scrollState = rememberScrollState()
    val density = LocalDensity.current.density
    LaunchedEffect(scrollToIndex) {
        if (scrollToIndex >= 0) {
            val rowPx = (rowDp * density).roundToInt()
            val itemTop = scrollToIndex * rowPx
            val itemBot = itemTop + rowPx
            val viewTop = scrollState.value
            val viewBot = viewTop + (maxDp * density).roundToInt()
            when {
                itemTop < viewTop -> scrollState.animateScrollTo(itemTop)
                itemBot > viewBot -> scrollState.animateScrollTo(itemBot - (maxDp * density).roundToInt())
            }
        }
    }
    Box(modifier.fillMaxWidth().height(h)) {
        Column(Modifier.fillMaxSize().verticalScroll(scrollState), content = content)
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
        )
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
            .border(1.dp, if (active) tc.ac else tc.br, CORNER_MD)
            .background(if (active) tc.ac.copy(.15f) else Color.Transparent, CORNER_MD)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) { AppText(label, color = if (active) tc.ac else tc.ts, fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal) }
}

@Composable
private fun TagPill(tag: String, color: Color, onRemove: () -> Unit) {
    Box(
        Modifier.background(color.copy(.13f), CORNER_SM)
            .border(1.dp, color.copy(.27f), CORNER_SM)
            .clickable(onClick = onRemove)
            .padding(start = 7.dp, end = 4.dp, top = 1.dp, bottom = 1.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            AppText(tag, color = color, fontSize = 11.sp, fontFamily = MONO)
            AppText("×", color = color.copy(.7f), fontSize = 14.sp)
        }
    }
}
