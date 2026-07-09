package com.openlog.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.draganddrop.dragAndDropTarget
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.openlog.model.*
import com.openlog.utils.computeCrashSites
import com.openlog.utils.computeStackTraceGroups
import com.openlog.utils.containsPattern
import com.openlog.utils.crashSitesForCategory
import com.openlog.utils.firstRegexMatch
import com.openlog.utils.passesFilter
import java.io.File
import java.net.URI
import kotlin.math.roundToInt

// A message-rule search suggestion. inScope marks whether it's within the currently active
// tag/package filter — out-of-scope candidates are shown after in-scope ones and dimmed, so
// matches unrelated to the active filter are still discoverable instead of vanishing.
private data class MsgCandidate(val pattern: String, val target: RuleTarget, val inScope: Boolean)

internal data class PendingMessageRuleDraft(
    val include: Boolean,
    val pattern: String,
    val regex: Boolean,
    val target: RuleTarget,
)

internal data class MessageRuleScopeOption(
    val label: String,
    val tag: String? = null,
    val packagePrefix: String? = null,
) {
    val isAll: Boolean get() = tag == null && packagePrefix == null
}

private const val LARGE_FILE_CANDIDATE_SCAN_LIMIT = 50_000
private const val SEQUENCE_DRAG_SNAP_BIAS = 0.25f

internal fun sequenceOrderDuringDrag(
    visibleIds: List<String>,
    draggedId: String?,
    dragStartIndex: Int,
    dragOffsetY: Float,
    rowHeight: Float,
): List<String> {
    val dragged = draggedId?.takeIf { it in visibleIds } ?: return visibleIds
    if (rowHeight <= 0f || dragStartIndex !in visibleIds.indices) return visibleIds
    val sensitivityBias = rowHeight * SEQUENCE_DRAG_SNAP_BIAS * dragOffsetY.compareTo(0f)
    val draggedCenter = dragStartIndex * rowHeight + rowHeight / 2f + dragOffsetY + sensitivityBias
    val without = visibleIds.filter { it != dragged }
    val insertAt = without.indexOfFirst { id ->
        val center = visibleIds.indexOf(id) * rowHeight + rowHeight / 2f
        draggedCenter < center
    }.takeIf { it >= 0 } ?: without.size
    return without.take(insertAt) + dragged + without.drop(insertAt)
}

internal fun sequenceRenderY(
    isDragging: Boolean,
    isJustReleased: Boolean,
    pointerY: Float,
    targetY: Float,
    animatedY: Float,
): Float = when {
    isDragging -> pointerY
    isJustReleased -> targetY
    else -> animatedY
}

internal fun sequenceRowBaseBackground(isDragging: Boolean, enabled: Boolean, theme: ThemeColors): Color = when {
    isDragging -> theme.p
    !enabled -> theme.hv
    else -> Color.Transparent
}

internal fun shouldSyncSequenceVisualOrder(dragId: String?, justReleasedSequenceId: String?): Boolean =
    dragId == null && justReleasedSequenceId == null

// Collapse/expand state for the filter panel. Held outside the composable so it survives
// the panel being hidden and re-shown (FilterPanel is removed from the tree when invisible).
class FilterPanelUiState {
    var hlListExpanded      by mutableStateOf(true)
    var lvlExpanded         by mutableStateOf(true)
    var seqExpanded         by mutableStateOf(true)
    var crashExpanded       by mutableStateOf(true)
    var crashCategory       by mutableStateOf(CrashCategory.ALL)
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
    savedFilters: List<SavedFilter>,
    activeFilterItemId: String?,
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
    onToggleExcludeTag: (String) -> Unit,
    onSetKw: (String) -> Unit,
    onStartRegexSearch: () -> Unit,
    onSetKwHighlightEnabled: (Boolean) -> Unit,
    onSetKwHighlightColor: (Color) -> Unit,
    onToggleSeq: () -> Unit,
    onAddSeq: (String, Boolean, Color, String?, String?, Boolean, String?) -> Unit,
    onRemoveSeq: (String) -> Unit,
    onToggleSeqEnabled: (String) -> Unit,
    onSetSeqColor: (String, Color) -> Unit,
    onUpdateSeq: (String, String, Boolean, String?, String?, Boolean, String?) -> Unit,
    onToggleManualCollapse: (String) -> Unit,
    onRemoveManualCollapse: (String) -> Unit,
    onSetManualBlockColor: (String, Color) -> Unit,
    onAddMessageRule: (Boolean, String, Boolean, String?, String?, RuleTarget) -> Unit,
    onRemoveMessageRule: (String) -> Unit,
    onToggleMessageRuleRegex: () -> Unit,
    onMoveSeqUp: (String) -> Unit,
    onMoveSeqDown: (String) -> Unit,
    onReorderSeq: (String, Int) -> Unit,
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
    onRenameSF: (String) -> Unit,
    onOpenSFDialog: () -> Unit,
    onSetKwInTag: (String) -> Unit,
    onAddPkgPrefix: (String) -> Unit,
    onRemovePkgPrefix: (String) -> Unit,
    onAddExcludePkgPrefix: (String) -> Unit,
    onRemoveExcludePkgPrefix: (String) -> Unit,
    onExportFilters: () -> Unit,
    onImportFilters: () -> Unit,
    onImportFiltersFromFiles: (List<File>) -> Unit,
    onClearFilter: () -> Unit,
    onNavigateCrash: (CrashSite) -> Unit,
    onUiStateChanged: () -> Unit = {},
    mostUsedTagLimit: Int,
    filterListRows: Int,
    width: Float,
    focusRequester: FocusRequester? = null,
    focusSearchRequest: Int = 0,
    ctrlFTarget: CtrlFTarget = CtrlFTarget.KEYWORD_REGEX,
    onPanelFocusChanged: (Boolean) -> Unit = {},
    keyboardFocusVisible: Boolean = false,
) {
    val tc = tc()
    val filter = tab.filter
    var panelFocused by remember { mutableStateOf(false) }

    // While the background analysis is pending this must NOT fall through to the synchronous
    // computation — that's a multi-second full-file scan and this runs during composition.
    val allCrashSites = remember(tab.id, tab.analysis.crashSites, tab.analysis.pending, tab.logData) {
        when {
            tab.analysis.pending -> emptyList()
            tab.analysis.crashSites.isNotEmpty() -> tab.analysis.crashSites
            else -> {
                val stackGroups = computeStackTraceGroups(tab.logData)
                computeCrashSites(tab.logData, stackGroups)
            }
        }
    }
    val crashSites = remember(allCrashSites, fpState.crashCategory) {
        crashSitesForCategory(allCrashSites, fpState.crashCategory)
    }

    // Tags sorted by frequency in log data; default suggestions come from user tag usage.
    val tagCounts = remember(tab.id, tab.analysis.tagCounts, tab.logData) {
        tab.analysis.tagCounts.ifEmpty { tab.logData.groupingBy { it.tag }.eachCount() }
    }
    val sortedTags = remember(tab.id, tagCounts) { tagCounts.entries.sortedByDescending { it.value }.map { it.key } }

    val tagFr = remember { FocusRequester() }
    val kwFr = remember { FocusRequester() }
    val msgRuleFr = remember { FocusRequester() }
    val msgRuleScopeFr = remember { FocusRequester() }
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
    var kwHighlightColorPickerOpen by remember { mutableStateOf(false) }
    var regexEditorOpen by remember { mutableStateOf(false) }
    var regexEditorText by remember { mutableStateOf("") }
    var colorPickerManualId by remember { mutableStateOf<String?>(null) }
    var editingSeqId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tagInput) {
        kotlinx.coroutines.delay(120)
        tagSearch = tagInput
    }
    LaunchedEffect(tagFieldFocused, tagCandidatesHovered) {
        if (tagFieldFocused || tagCandidatesHovered) {
            showTagCandidates = true
        } else { kotlinx.coroutines.delay(100); if (!tagFieldFocused && !tagCandidatesHovered) showTagCandidates = false }
    }

    // Combined candidates: pkg-prefix matches first (only when search has input), then tag matches.
    // Each entry is (value, isPkg): isPkg=true → add as package prefix; false → include/exclude as tag.
    val combinedTagCandidates = remember(
        sortedTags,
        tagSearch,
        filter.pkgPrefixes,
        filter.excludePkgPrefixes,
        tagUsage,
        mostUsedTagLimit,
    ) {
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
    var kwFieldFocused by remember { mutableStateOf(false) }
    LaunchedEffect(kwDisplay) {
        val snap = kwDisplay
        kotlinx.coroutines.delay(if (tab.largeFileMode) 350 else 150)
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
    var msgRuleScopeOpen by remember(tab.id) { mutableStateOf(false) }
    var msgRuleScopeSearch by remember(tab.id) { mutableStateOf("") }
    var msgRuleScopeFieldFocused by remember { mutableStateOf(false) }
    var msgRuleScopeSelectedIdx by remember(tab.id) { mutableStateOf(0) }
    var pendingMessageRule by remember(tab.id) { mutableStateOf<PendingMessageRuleDraft?>(null) }
    var pendingSearchFocusTarget by remember { mutableStateOf<CtrlFTarget?>(null) }
    LaunchedEffect(msgRuleInput) {
        val snap = msgRuleInput
        kotlinx.coroutines.delay(if (tab.largeFileMode) 350 else 120)
        if (snap == msgRuleInput) {
            msgRuleSearch = msgRuleInput
            msgRuleLastSent = msgRuleInput
            // kwInTag is a Tags-mode-only secondary filter (see Filter.kt). The message-rule field
            // is not rendered in Regex mode, so typing here should only ever update Tags-mode state.
            if (filter.mode == FilterMode.TAGS) onSetKwInTag(msgRuleInput)
        }
    }
    LaunchedEffect(filter.kwInTag) { if (filter.kwInTag != msgRuleLastSent) msgRuleInput = filter.kwInTag }
    LaunchedEffect(msgRuleFieldFocused, msgCandidatesHovered) {
        if (msgRuleFieldFocused || msgCandidatesHovered) {
            showMsgRuleCandidates = true
        } else { kotlinx.coroutines.delay(100); if (!msgRuleFieldFocused && !msgCandidatesHovered) showMsgRuleCandidates = false }
    }
    var hlColorPickerOpen   by remember { mutableStateOf(false) }
    var hlFieldFocused by remember { mutableStateOf(false) }
    var seqAddOpen          by remember { mutableStateOf(false) }
    var seqColorPickerOpen  by remember { mutableStateOf(false) }
    var navIndex by remember(tab.id) { mutableStateOf(0) }

    val navTargets = remember(
        tab.filter,
        tab.manualBlocks,
        savedFilters,
    ) {
        filterKeyboardTargets(
            levelCount = LogLevel.entries.size,
            sequenceIds = tab.filter.sequences.map { it.id },
            manualCollapseIds = tab.manualBlocks.map { it.id },
            savedFilterIds = savedFilters.map { it.id },
        )
    }

    fun moveFilterFocus(delta: Int) {
        navIndex = rovingMove(navTargets.map { it.asRovingItem() }, navIndex, delta)
    }

    fun focusCurrentTarget() {
        when (navTargets.getOrNull(navIndex)?.kind) {
            KeyboardTargetKind.FilterTagInput -> runCatching { tagFr.requestFocus() }
            KeyboardTargetKind.FilterMessageInput -> runCatching { msgRuleFr.requestFocus() }
            KeyboardTargetKind.FilterHighlighterInput -> runCatching { hlFr.requestFocus() }
            else -> runCatching { focusRequester?.requestFocus() }
        }
    }

    fun activateFilterTarget(spaceActivation: Boolean = false) {
        val target = navTargets.getOrNull(navIndex) ?: return
        when (target.kind) {
            KeyboardTargetKind.FilterModeTags -> onSetFilterMode(FilterMode.TAGS)
            KeyboardTargetKind.FilterModeRegex -> {
                onStartRegexSearch()
                runCatching { kwFr.requestFocus() }
            }
            KeyboardTargetKind.FilterTagInput -> runCatching { tagFr.requestFocus() }
            KeyboardTargetKind.FilterMessageInput -> runCatching { msgRuleFr.requestFocus() }
            KeyboardTargetKind.FilterHighlighterInput -> runCatching { hlFr.requestFocus() }
            KeyboardTargetKind.FilterSection -> when (target.id) {
                "filter-section-levels" -> { fpState.lvlExpanded = !fpState.lvlExpanded; onUiStateChanged() }
                "filter-section-sequences" -> { fpState.seqExpanded = !fpState.seqExpanded; onUiStateChanged() }
                "filter-section-saved" -> { fpState.sfExpanded = !fpState.sfExpanded; onUiStateChanged() }
            }
            KeyboardTargetKind.FilterLogLevel -> target.id.removePrefix("level-").toIntOrNull()
                ?.let { idx -> LogLevel.entries.getOrNull(idx)?.let(onToggleLevel) }
            KeyboardTargetKind.FilterSequence -> {
                val id = target.id.removePrefix("sequence:")
                if (spaceActivation) onToggleSeqEnabled(id) else editingSeqId = if (editingSeqId == id) null else id
            }
            KeyboardTargetKind.FilterManualCollapse -> onToggleManualCollapse(target.id.removePrefix("manual:"))
            KeyboardTargetKind.FilterNewSequence -> seqAddOpen = !seqAddOpen
            KeyboardTargetKind.FilterSavedFilter -> savedFilters
                .firstOrNull { it.id == target.id.removePrefix("saved:") }
                ?.let(onLoadFilter)
            KeyboardTargetKind.FilterSaveCurrent -> onOpenSFDialog()
            KeyboardTargetKind.FilterClearFilters -> onClearFilter()
            KeyboardTargetKind.FilterExportFilters -> onExportFilters()
            KeyboardTargetKind.FilterImportFilters -> onImportFilters()
            else -> {}
        }
    }

    fun deleteFilterTarget(): Boolean {
        val target = navTargets.getOrNull(navIndex) ?: return false
        return when (target.kind) {
            KeyboardTargetKind.FilterSequence -> {
                onRemoveSeq(target.id.removePrefix("sequence:")); true
            }
            KeyboardTargetKind.FilterManualCollapse -> {
                onRemoveManualCollapse(target.id.removePrefix("manual:")); true
            }
            KeyboardTargetKind.FilterSavedFilter -> {
                onDeleteSF(target.id.removePrefix("saved:")); true
            }
            else -> false
        }
    }

    fun moveSequenceTarget(delta: Int): Boolean {
        val target = navTargets.getOrNull(navIndex) ?: return false
        if (target.kind != KeyboardTargetKind.FilterSequence) return false
        val id = target.id.removePrefix("sequence:")
        if (delta < 0) onMoveSeqUp(id) else onMoveSeqDown(id)
        return true
    }

    LaunchedEffect(focusSearchRequest) {
        if (focusSearchRequest > 0) {
            when (ctrlFTarget) {
                CtrlFTarget.TAGS -> {
                    if (filter.mode != FilterMode.TAGS) {
                        pendingSearchFocusTarget = CtrlFTarget.TAGS
                        onSetFilterMode(FilterMode.TAGS)
                    } else {
                        runCatching { tagFr.requestFocus() }
                    }
                }
                CtrlFTarget.KEYWORD_REGEX -> {
                    if (filter.mode != FilterMode.KEYWORD) {
                        pendingSearchFocusTarget = CtrlFTarget.KEYWORD_REGEX
                        onStartRegexSearch()
                    } else {
                        runCatching { kwFr.requestFocus() }
                    }
                }
                CtrlFTarget.MESSAGE_RULE -> {
                    if (filter.mode != FilterMode.TAGS) {
                        pendingSearchFocusTarget = CtrlFTarget.MESSAGE_RULE
                        onSetFilterMode(FilterMode.TAGS)
                    } else {
                        runCatching { msgRuleFr.requestFocus() }
                    }
                }
            }
        }
    }
    LaunchedEffect(filter.mode, pendingSearchFocusTarget) {
        when (pendingSearchFocusTarget) {
            CtrlFTarget.TAGS -> if (filter.mode == FilterMode.TAGS) {
                runCatching { tagFr.requestFocus() }
                pendingSearchFocusTarget = null
            }
            CtrlFTarget.KEYWORD_REGEX -> if (filter.mode == FilterMode.KEYWORD) {
                runCatching { kwFr.requestFocus() }
                pendingSearchFocusTarget = null
            }
            CtrlFTarget.MESSAGE_RULE -> if (filter.mode == FilterMode.TAGS) {
                runCatching { msgRuleFr.requestFocus() }
                pendingSearchFocusTarget = null
            }
            null -> Unit
        }
    }

    // Unified candidates: PIDs when field is blank, message stems + matching PIDs when not blank.
    // In-scope results (within the active tag/package filter) come first, then out-of-scope
    // ones (still respecting level/exclude filters, just not the tag/package restriction) —
    // so a search unrelated to the active filter still surfaces results instead of showing
    // nothing, making it easy to spot and then relax the filter if that's what's wanted.
    val unifiedCandidates = remember(tab.id, tab.largeFileMode, filter, msgRuleSearch) {
        if (msgRuleSearch.isBlank()) {
            emptyList()
        } else {
            val candidateEntries = if (tab.largeFileMode) {
                tab.logData.asSequence().take(LARGE_FILE_CANDIDATE_SCAN_LIMIT).toList()
            } else {
                tab.logData
            }
            val baseFilter = filter.copy(kwInTag = "", messageRules = emptyList(), pidTidFilter = "")
            val relaxedFilter = baseFilter.copy(activeTags = emptySet(), pkgPrefixes = emptySet())
            // PIDs only when the search looks like a number
            val pidCandidates = if (msgRuleSearch.any { it.isDigit() })
                candidateEntries
                    .filter { entry -> passesFilter(entry, relaxedFilter) && entry.pid != 0 }
                    .map { it.pid.toString() }.distinct()
                    .filter { it.contains(msgRuleSearch) }
                    .take(3)
                    .map { MsgCandidate(it, RuleTarget.PID_TID, inScope = true) }
            else emptyList()

            fun matchingMsgsOf(entries: List<LogEntry>) = entries
                .filter { containsPattern(it.msg, msgRuleSearch, regex = filter.kwInTagRegex) }
                .map { it.msg }

            val inScopeMsgs = matchingMsgsOf(candidateEntries.filter { passesFilter(it, baseFilter) })
            val outOfScopeMsgs = matchingMsgsOf(
                candidateEntries.filter { !passesFilter(it, baseFilter) && passesFilter(it, relaxedFilter) },
            )

            // In regex mode, lead with what the pattern actually matched (e.g. "avc.*denied"
            // against "avc: denied : word 1 word 2" proposes "avc: denied" first) instead of
            // the plain-text stem heuristic, which only makes sense for non-regex prefix search.
            fun stemsAndFulls(msgs: List<String>): List<String> {
                val leads = if (filter.kwInTagRegex) {
                    msgs.mapNotNull { msg -> firstRegexMatch(msg, msgRuleSearch)?.take(80) }.distinct()
                } else {
                    msgs.map { msg ->
                        val sepIdx = msg.indexOfFirst { it == ':' || it == '(' }
                        if (sepIdx > 0) msg.substring(0, sepIdx).trim().takeIf { it.isNotBlank() } ?: msg.take(80)
                        else msg.take(80)
                    }.distinct()
                }
                val fulls = msgs.map { it.take(80) }.distinct().filter { it !in leads }
                return leads + fulls
            }

            val inScopeCandidates = stemsAndFulls(inScopeMsgs).map { MsgCandidate(it, RuleTarget.MESSAGE, inScope = true) }
            val inScopePatterns = inScopeCandidates.map { it.pattern }.toSet()
            val outOfScopeCandidates = stemsAndFulls(outOfScopeMsgs)
                .filter { it !in inScopePatterns }
                .map { MsgCandidate(it, RuleTarget.MESSAGE, inScope = false) }
            val msgCandidates = (inScopeCandidates + outOfScopeCandidates).take(8 - pidCandidates.size)
            pidCandidates + msgCandidates
        }
    }
    // Tags/prefixes the scope picker offers are narrowed to ones the pending rule's pattern
    // actually occurs under — an "All" rule for "timeout" shouldn't list every tag in the file,
    // only ones that have ever logged something matching "timeout". Falls back to the full tag
    // list if nothing matched (e.g. scan-limit truncation on a huge file) so the picker never
    // goes empty.
    val relevantScopeTags = remember(tab.id, tab.largeFileMode, pendingMessageRule) {
        val pending = pendingMessageRule
        if (pending == null) {
            null
        } else {
            val candidateEntries = if (tab.largeFileMode) {
                tab.logData.asSequence().take(LARGE_FILE_CANDIDATE_SCAN_LIMIT).toList()
            } else {
                tab.logData
            }
            if (pending.target == RuleTarget.PID_TID) {
                val tokens = pending.pattern.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
                candidateEntries.asSequence()
                    .filter { entry -> tokens.any { it == entry.pid.toString() || it == entry.tid.toString() } }
                    .map { it.tag }.toSet()
            } else {
                candidateEntries.asSequence()
                    .filter { entry -> containsPattern(entry.msg, pending.pattern, pending.regex) }
                    .map { it.tag }.toSet()
            }
        }
    }
    val scopeOptionTags = remember(sortedTags, relevantScopeTags) {
        if (relevantScopeTags.isNullOrEmpty()) sortedTags else sortedTags.filter { it in relevantScopeTags }
    }
    val msgRuleScopeOptions = remember(scopeOptionTags, msgRuleScopeSearch) {
        messageRuleScopeOptions(scopeOptionTags, msgRuleScopeSearch)
    }
    val searchedMsgRuleScopeOptions = remember(msgRuleScopeOptions) { msgRuleScopeOptions.drop(1) }

    fun openMessageRuleScopeChooser(include: Boolean, pattern: String, regex: Boolean, target: RuleTarget) {
        pendingMessageRule = PendingMessageRuleDraft(include, pattern, regex, target)
        msgRuleScopeOpen = true
        msgRuleScopeSearch = ""
        msgRuleScopeSelectedIdx = 0
        showMsgRuleCandidates = false
    }

    fun cancelPendingMessageRule() {
        pendingMessageRule = null
        msgRuleScopeOpen = false
        msgRuleScopeSearch = ""
        msgRuleScopeSelectedIdx = 0
        msgRuleInput = ""
        // Clear the debounced search value synchronously too — otherwise it still holds the old
        // query for the debounce delay after refocusing, and unifiedCandidates (keyed on it)
        // briefly renders the stale dropdown before the debounce catches up and clears it.
        msgRuleSearch = ""
        msgRuleSelectedIdx = -1
        msgRuleSelectedAction = 0
        onSetKwInTag("")
        runCatching { msgRuleFr.requestFocus() }
    }

    fun commitPendingMessageRule(scope: MessageRuleScopeOption) {
        val pending = pendingMessageRule ?: return
        onAddMessageRule(pending.include, pending.pattern, pending.regex, scope.tag, scope.packagePrefix, pending.target)
        pendingMessageRule = null
        msgRuleScopeOpen = false
        msgRuleScopeSearch = ""
        msgRuleScopeSelectedIdx = 0
        msgRuleInput = ""
        msgRuleSearch = ""
        msgRuleSelectedIdx = -1
        msgRuleSelectedAction = 0
        runCatching { msgRuleFr.requestFocus() }
    }

    fun clearTagSearch() {
        tagInput = inputValueAfterEscape(tagInput, escapePressed = true)
        tagSearch = ""
        tagSelectedIdx = -1
        showTagCandidates = false
    }
    LaunchedEffect(msgRuleScopeOpen) {
        if (msgRuleScopeOpen) runCatching { msgRuleScopeFr.requestFocus() }
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
            .border(BorderStroke(1.dp, if (panelFocused && keyboardFocusVisible) tc.ac else tc.br))
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    runCatching { event.dragData() is DragData.FilesList }.getOrElse { false }
                },
                target = filterDropTarget,
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusGroup()
            .focusable()
            .onFocusChanged { panelFocused = it.hasFocus; onPanelFocusChanged(it.hasFocus) }
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val fieldFocused = tagFieldFocused || msgRuleFieldFocused || msgRuleScopeFieldFocused || hlFieldFocused || kwFieldFocused
                if (fieldFocused) {
                    if (ev.key == Key.Escape) {
                        if (tagFieldFocused) {
                            clearTagSearch()
                        } else if (msgRuleFieldFocused || msgRuleScopeFieldFocused) {
                            cancelPendingMessageRule()
                        } else if (kwFieldFocused) {
                            kwDisplay = ""; onSetKw("")
                        } else if (hlFieldFocused) {
                            onSetNewHlPat("")
                        } else {
                            runCatching { focusRequester?.requestFocus() }
                        }
                        true
                    } else {
                        false
                    }
                } else {
                    when {
                        ev.isAltPressed && ev.key == Key.DirectionUp -> moveSequenceTarget(-1)
                        ev.isAltPressed && ev.key == Key.DirectionDown -> moveSequenceTarget(+1)
                        ev.key == Key.DirectionUp -> { moveFilterFocus(-1); true }
                        ev.key == Key.DirectionDown -> { moveFilterFocus(+1); true }
                        ev.key == Key.DirectionLeft -> { moveFilterFocus(-1); true }
                        ev.key == Key.DirectionRight -> { moveFilterFocus(+1); true }
                        ev.key == Key.Enter || ev.key == Key.NumPadEnter -> { activateFilterTarget(); true }
                        ev.key == Key.Spacebar -> { activateFilterTarget(spaceActivation = true); true }
                        ev.key == Key.Delete || ev.key == Key.Backspace -> deleteFilterTarget()
                        ev.key == Key.Escape -> {
                            colorPickerSeqId = null
                            colorPickerHlId = null
                            colorPickerManualId = null
                            hlColorPickerOpen = false
                            seqColorPickerOpen = false
                            editingSeqId = null
                            true
                        }
                        else -> false
                    }
                }
            }
            .verticalScroll(scroll),
    ) {
        // ── Filter mode tabs ──────────────────────────────────────
        Row(Modifier.fillMaxWidth()) {
            listOf("Tags" to FilterMode.TAGS, "Regex" to FilterMode.KEYWORD).forEach { (label, mode) ->
                val active = filter.mode == mode
                Column(
                    Modifier.weight(1f).clickable {
                        if (mode == FilterMode.KEYWORD) onStartRegexSearch() else onSetFilterMode(mode)
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
            val totalActive = filter.pkgPrefixes.size + filter.excludePkgPrefixes.size + filter.activeTags.size + filter.excludeTags.size
            // ── unified TAGS section header with combined pill count ──
            SectionHeader(
                "Tags",
                trailing = if (totalActive > 0) ({
                    Row(
                        Modifier.hoverPill().clickable {
                            fpState.incPillsExpanded = !fpState.incPillsExpanded
                            onUiStateChanged()
                        }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        if (filter.pkgPrefixes.isNotEmpty())
                            AppText("${filter.pkgPrefixes.size} pkg", color = pkgColor, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                        if (filter.excludePkgPrefixes.isNotEmpty())
                            AppText("${filter.excludePkgPrefixes.size} pkg−",
                                color = exNeg, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
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
                BoundedScrollBox(minOf(totalActive, filterListRows)) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        filter.pkgPrefixes.forEach { pfx -> TagPill(pfx, pkgColor) { onRemovePkgPrefix(pfx) } }
                        filter.excludePkgPrefixes.forEach { pfx -> TagPill(pfx, exNeg) { onRemoveExcludePkgPrefix(pfx) } }
                        filter.activeTags.forEach { tag ->
                            TagPill(displayTagForPrefix(tag, filter.pkgPrefixes).first, tc.ac) { onToggleTag(tag) }
                        }
                        filter.excludeTags.forEach { tag ->
                            TagPill(displayTagForPrefix(tag, filter.pkgPrefixes).first, exNeg) { onToggleExcludeTag(tag) }
                        }
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
                            Key.Escape -> { clearTagSearch(); true }
                            Key.DirectionRight -> {
                                val c = combinedTagCandidates.getOrNull(tagSelectedIdx)
                                if (c != null) { tagSelectedAction = 1; true } else {
                                    false
                                }
                            }
                            Key.DirectionLeft -> {
                                val c = combinedTagCandidates.getOrNull(tagSelectedIdx)
                                if (c != null) { tagSelectedAction = 0; true } else {
                                    false
                                }
                            }
                            Key.Enter -> {
                                val c = combinedTagCandidates.getOrNull(tagSelectedIdx)
                                if (c != null) {
                                    if (c.second) {
                                        if (tagSelectedAction == 0) onAddPkgPrefix(c.first) else onAddExcludePkgPrefix(c.first)
                                    } else if (tagSelectedAction == 0) {
                                        onToggleTag(c.first)
                                    } else {
                                        onToggleExcludeTag(c.first)
                                    }
                                } else if (tagInput.isNotBlank()) {
                                    if (tagInput.contains('.')) onAddPkgPrefix(tagInput) else onToggleTag(tagInput)
                                } else {
                                    return@onPreviewKeyEvent false
                                }
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
                            val isIncluded = value in filter.pkgPrefixes
                            val isExcluded = value in filter.excludePkgPrefixes
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
                                    AppText("pkg", color = when {
                                        isExcluded -> exNeg.copy(.8f)
                                        isIncluded -> pkgColor.copy(.8f)
                                        else -> pkgColor.copy(.7f)
                                    }, fontSize = 9.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.width(26.dp))
                                    FullTextHint(value, modifier = Modifier.weight(1f), forceShow = isRowSelected) { onTextLayout ->
                                        AppText(value, color = when {
                                            isExcluded -> exNeg.copy(.85f)
                                            isRowSelected || isIncluded -> tc.tx
                                            else -> tc.ts
                                        }, fontSize = 11.sp, fontFamily = MONO,
                                            modifier = Modifier.fillMaxWidth(), overflow = TextOverflow.Ellipsis, maxLines = 1,
                                            onTextLayout = onTextLayout)
                                    }
                                    Spacer(Modifier.width(26.dp))
                                    val incKbd = isRowSelected && tagSelectedAction == 0
                                    Box(
                                        Modifier.size(20.dp)
                                            .background(
                                                if (isIncluded) pkgColor.copy(.2f) else if (incKbd) pkgColor.copy(.1f)
                                                else Color.Transparent, CORNER_SM)
                                            .border(1.dp, if (isIncluded || incKbd) pkgColor else tc.br, CORNER_SM)
                                            .clickable { onAddPkgPrefix(value); tagInput = ""; tagSelectedIdx = -1 },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        AppText("+", color = if (isIncluded || incKbd) pkgColor else tc.ts,
                                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    val exKbd = isRowSelected && tagSelectedAction == 1
                                    Box(
                                        Modifier.size(20.dp)
                                            .background(if (isExcluded) exNeg.copy(.2f) else if (exKbd) exNeg.copy(.1f) else Color.Transparent, CORNER_SM)
                                            .border(1.dp, if (isExcluded || exKbd) exNeg else tc.br, CORNER_SM)
                                            .clickable { onAddExcludePkgPrefix(value); tagInput = ""; tagSelectedIdx = -1 },
                                        contentAlignment = Alignment.Center,
                                    ) { AppText("−", color = if (isExcluded || exKbd) exNeg else tc.ts, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
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
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(Modifier.width(26.dp), contentAlignment = Alignment.CenterStart) {
                                        Box(Modifier.size(5.dp).background(when {
                                            isIncluded -> tc.ac
                                            isExcluded -> exNeg
                                            else -> tc.td
                                        }, RoundedCornerShape(50)))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        FullTextHint(tag, modifier = Modifier.fillMaxWidth(), forceShow = isRowSelected) { onTextLayout ->
                                            AppText(label, color = when {
                                                isIncluded -> tc.tx
                                                isExcluded -> exNeg.copy(.8f)
                                                else -> tc.ts
                                            }, fontSize = 11.sp, fontFamily = MONO,
                                                modifier = Modifier.fillMaxWidth(), overflow = TextOverflow.Ellipsis, maxLines = 1,
                                                onTextLayout = onTextLayout)
                                        }
                                        if (packageLabel != null)
                                            AppText(packageLabel, color = tc.td, fontSize = 9.sp,
                                                fontFamily = MONO, overflow = TextOverflow.Ellipsis, maxLines = 1)
                                    }
                                    AppText((tagCounts[tag] ?: 0).toString(), color = tc.td, fontSize = 10.sp, fontFamily = MONO,
                                        modifier = Modifier.width(26.dp), overflow = TextOverflow.Clip)
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
                InlineField(
                    kwDisplay,
                    { kwDisplay = it },
                    "visible log row regex…",
                    Modifier.weight(1f)
                        .focusRequester(kwFr)
                        .onFocusChanged { kwFieldFocused = it.isFocused },
                )
                SquareIconButton(
                    "⤢",
                    fontSize = 12.sp,
                    onClick = {
                        regexEditorText = kwDisplay
                        regexEditorOpen = true
                    },
                    size = 16.dp,
                )
                if (kwDisplay.isNotBlank())
                    SquareIconButton("×", fontSize = 12.sp, onClick = { kwDisplay = ""; onSetKw("") }, size = 16.dp)
            }
            Divider()
        }

        if (filter.mode == FilterMode.TAGS) {
            // ── Message Rules (combined search + include/exclude) ─────────────
            // Message Rules are a Tags-mode tool. Regex mode is deliberately just the single
            // kwText/kwRegex field above; persisted KEYWORD-mode rules are hidden and inert.
            val msgExNeg = DANGER_RED
            val msgInc = filter.messageRules.filter { it.include && it.mode == FilterMode.TAGS }
            val msgExc = filter.messageRules.filter { !it.include && it.mode == FilterMode.TAGS }
            SectionHeader(
                "Message rules",
                trailing = if (msgInc.isNotEmpty() || msgExc.isNotEmpty()) ({
                    if (msgInc.isNotEmpty()) {
                        Row(
                            Modifier.hoverPill().clickable {
                                fpState.incMsgPillsExpanded = !fpState.incMsgPillsExpanded
                                if (fpState.incMsgPillsExpanded) fpState.excMsgPillsExpanded = false
                                onUiStateChanged()
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
                            Modifier.hoverPill().clickable {
                                fpState.excMsgPillsExpanded = !fpState.excMsgPillsExpanded
                                if (fpState.excMsgPillsExpanded) fpState.incMsgPillsExpanded = false
                                onUiStateChanged()
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
                BoundedScrollBox(minOf(msgInc.size, filterListRows)) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        msgInc.forEach { rule ->
                            TagPill(messageRulePillLabel(rule), tc.ac) { onRemoveMessageRule(rule.id) }
                        }
                    }
                }
            }
            if (msgExc.isNotEmpty() && fpState.excMsgPillsExpanded) {
                BoundedScrollBox(minOf(msgExc.size, filterListRows)) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        msgExc.forEach { rule ->
                            TagPill(messageRulePillLabel(rule), msgExNeg) { onRemoveMessageRule(rule.id) }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InlineField(
                    msgRuleInput,
                    { msgRuleInput = it; msgRuleSelectedIdx = -1 },
                    if (filter.kwInTagRegex) "/pattern/…" else "search in messages…",
                    Modifier.weight(1f)
                        .focusRequester(msgRuleFr)
                        .onFocusChanged { msgRuleFieldFocused = it.isFocused }
                        .onPreviewKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val hasActionCandidate = unifiedCandidates.getOrNull(msgRuleSelectedIdx) != null
                            if (!messageRuleInputConsumesKey(ev.key, hasActionCandidate)) return@onPreviewKeyEvent false
                            when (ev.key) {
                                Key.Tab -> { runCatching { hlFr.requestFocus() }; true }
                                Key.DirectionDown -> { msgRuleSelectedIdx = (msgRuleSelectedIdx + 1).coerceAtMost(unifiedCandidates.lastIndex); true }
                                Key.DirectionUp -> { msgRuleSelectedIdx = (msgRuleSelectedIdx - 1).coerceAtLeast(-1); true }
                                Key.DirectionLeft -> { msgRuleSelectedAction = 0; true }
                                Key.DirectionRight -> { msgRuleSelectedAction = 1; true }
                                Key.Escape -> { cancelPendingMessageRule(); true }
                                Key.Enter, Key.NumPadEnter -> {
                                    val c = unifiedCandidates.getOrNull(msgRuleSelectedIdx)
                                    if (c != null) {
                                        openMessageRuleScopeChooser(msgRuleSelectedAction == 0, c.pattern, false, c.target)
                                    } else if (msgRuleInput.isNotBlank()) {
                                        // No candidate selected — add typed text directly.
                                        // All-digit input → PID/TID rule; anything else → message rule.
                                        val spec = messageRuleInputSpec(msgRuleInput, regexMode = filter.kwInTagRegex)
                                        openMessageRuleScopeChooser(msgRuleSelectedAction == 0, spec.pattern, spec.regex, spec.target)
                                    } else {
                                        return@onPreviewKeyEvent false
                                    }
                                    true
                                }
                                else -> false
                            }
                        },
                    onClear = { msgRuleInput = ""; onSetKwInTag("") },
                )
                PillBtn(".*", active = filter.kwInTagRegex, onClick = onToggleMessageRuleRegex)
            }
            if (msgRuleScopeOpen) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AppText(
                            messageRuleScopePrompt(pendingMessageRule?.include ?: true),
                            color = if (pendingMessageRule?.include == false) msgExNeg else tc.ac,
                            fontSize = 10.sp,
                            fontFamily = UI,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        SquareIconButton("×", fontSize = 12.sp, onClick = {
                            cancelPendingMessageRule()
                        })
                    }
                    pendingMessageRule?.let { pending ->
                        FullTextHint(pendingMessageRulePatternLabel(pending)) { onTextLayout ->
                            AppText(
                                pendingMessageRulePatternLabel(pending),
                                color = tc.tx,
                                fontSize = 11.sp,
                                fontFamily = MONO,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                                onTextLayout = onTextLayout,
                            )
                        }
                    }
                    HoverBox(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        baseBg = if (msgRuleScopeSelectedIdx == 0) tc.abg else Color.Transparent,
                        hoverBg = tc.hv,
                        onClick = { commitPendingMessageRule(messageRuleAllScope()) },
                    ) {
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppText(
                                "All",
                                color = if (msgRuleScopeSelectedIdx == 0) tc.tx else tc.ts,
                                fontSize = 11.sp,
                                fontFamily = MONO,
                            )
                        }
                    }
                    InlineField(
                        msgRuleScopeSearch,
                        { msgRuleScopeSearch = it; msgRuleScopeSelectedIdx = 0 },
                        "scope tag or prefix…",
                        Modifier.fillMaxWidth()
                            .focusRequester(msgRuleScopeFr)
                            .onFocusChanged { msgRuleScopeFieldFocused = it.isFocused }
                            .onPreviewKeyEvent { ev ->
                                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (ev.key) {
                                    Key.DirectionDown -> {
                                        msgRuleScopeSelectedIdx =
                                            (msgRuleScopeSelectedIdx + 1).coerceAtMost(msgRuleScopeOptions.lastIndex)
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        msgRuleScopeSelectedIdx =
                                            (msgRuleScopeSelectedIdx - 1).coerceAtLeast(0)
                                        true
                                    }
                                    Key.Enter, Key.NumPadEnter -> {
                                        msgRuleScopeOptions.getOrNull(msgRuleScopeSelectedIdx)?.let { commitPendingMessageRule(it) }
                                        true
                                    }
                                    Key.Escape -> { cancelPendingMessageRule(); true }
                                    else -> false
                                }
                            },
                        onClear = { msgRuleScopeSearch = ""; msgRuleScopeSelectedIdx = 0 },
                    )
                    ScrollableItems(searchedMsgRuleScopeOptions.size, maxDp = 140, scrollToIndex = msgRuleScopeSelectedIdx - 1) {
                        searchedMsgRuleScopeOptions.forEachIndexed { idx, scope ->
                            val optionIndex = idx + 1
                            HoverBox(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                baseBg = if (msgRuleScopeSelectedIdx == optionIndex) tc.abg else Color.Transparent,
                                hoverBg = tc.hv,
                                onClick = { commitPendingMessageRule(scope) },
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    AppText(
                                        when {
                                            scope.isAll -> "all"
                                            scope.packagePrefix != null -> "pkg"
                                            else -> "tag"
                                        },
                                        color = tc.td,
                                        fontSize = 9.sp,
                                        fontFamily = UI,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.width(26.dp),
                                    )
                                    FullTextHint(
                                        scope.label,
                                        modifier = Modifier.weight(1f),
                                        forceShow = msgRuleScopeSelectedIdx == optionIndex,
                                    ) { onTextLayout ->
                                        AppText(
                                            scope.label,
                                            color = if (msgRuleScopeSelectedIdx == optionIndex) tc.tx else tc.ts,
                                            fontSize = 11.sp,
                                            fontFamily = MONO,
                                            modifier = Modifier.fillMaxWidth(),
                                            overflow = TextOverflow.Ellipsis,
                                            maxLines = 1,
                                            onTextLayout = onTextLayout,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!msgRuleScopeOpen && showMsgRuleCandidates && unifiedCandidates.isNotEmpty()) {
                ScrollableItems(unifiedCandidates.size, maxDp = 220, scrollToIndex = msgRuleSelectedIdx,
                    modifier = Modifier
                        .onPointerEvent(PointerEventType.Enter) { msgCandidatesHovered = true }
                        .onPointerEvent(PointerEventType.Exit)  { msgCandidatesHovered = false }
                ) {
                    unifiedCandidates.forEachIndexed { idx, cand ->
                        val (pattern, target, inScope) = cand
                        val isPid = target == RuleTarget.PID_TID
                        val isIncluded = if (isPid) {
                            msgInc.any { it.target == RuleTarget.PID_TID && it.pattern == pattern }
                        } else {
                            msgInc.any { it.target == RuleTarget.MESSAGE && it.pattern == pattern && !it.regex }
                        }
                        val isExcluded = if (isPid) {
                            msgExc.any { it.target == RuleTarget.PID_TID && it.pattern == pattern }
                        } else {
                            msgExc.any { it.target == RuleTarget.MESSAGE && it.pattern == pattern && !it.regex }
                        }
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
                                if (!inScope) {
                                    AppText("other", color = tc.td.copy(.7f), fontSize = 9.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(end = 2.dp))
                                }
                                FullTextHint(pattern, modifier = Modifier.weight(1f), forceShow = isRowSelected) { onTextLayout ->
                                    AppText(
                                        pattern,
                                        color = when {
                                            isIncluded -> tc.tx
                                            isExcluded -> msgExNeg.copy(.8f)
                                            !inScope -> tc.td
                                            else -> tc.ts
                                        },
                                        fontSize = 11.sp,
                                        fontFamily = MONO,
                                        modifier = Modifier.fillMaxWidth(),
                                        overflow = TextOverflow.Ellipsis,
                                        onTextLayout = onTextLayout,
                                    )
                                }
                                val incHighlight = isIncluded
                                val incKbd = isRowSelected && msgRuleSelectedAction == 0
                                Box(
                                    Modifier.size(20.dp)
                                        .background(if (incHighlight) tc.ac.copy(.2f) else if (incKbd) tc.ac.copy(.1f) else Color.Transparent, CORNER_SM)
                                        .border(1.dp, if (incHighlight || incKbd) tc.ac else tc.br, CORNER_SM)
                                        .clickable { openMessageRuleScopeChooser(true, pattern, false, target) },
                                    contentAlignment = Alignment.Center,
                                ) { AppText("+", color = if (incHighlight || incKbd) tc.ac else tc.ts, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                                val exHighlight = isExcluded
                                val exKbd = isRowSelected && msgRuleSelectedAction == 1
                                Box(
                                    Modifier.size(20.dp)
                                        .background(if (exHighlight) msgExNeg.copy(.2f) else if (exKbd) msgExNeg.copy(.1f) else Color.Transparent, CORNER_SM)
                                        .border(1.dp, if (exHighlight || exKbd) msgExNeg else tc.br, CORNER_SM)
                                        .clickable { openMessageRuleScopeChooser(false, pattern, false, target) },
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
        val regexHighlightAvailable =
            filter.mode == FilterMode.KEYWORD && filter.kwRegex && filter.kwText.isNotBlank()
        val displayedHighlighterCount = filter.highlighters.size + if (regexHighlightAvailable) 1 else 0
        val enabledHighlighterCount = filter.highlighters.count { it.on } +
            if (regexHighlightAvailable && filter.kwHighlightEnabled) 1 else 0
        SectionHeader(
            "Highlighters",
            trailing = if (displayedHighlighterCount > 0) ({
                Row(
                    Modifier.hoverPill().clickable {
                        fpState.hlListExpanded = !fpState.hlListExpanded
                        onUiStateChanged()
                    }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (enabledHighlighterCount > 0)
                        AppText("$enabledHighlighterCount active", color = tc.ac, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                    val offCount = displayedHighlighterCount - enabledHighlighterCount
                    if (offCount > 0)
                        AppText("$offCount off", color = tc.td, fontSize = 10.sp, fontFamily = UI)
                    AppText(if (fpState.hlListExpanded) "▾" else "▸", color = tc.ts, fontSize = 10.sp)
                }
            }) else null,
        )
        if (displayedHighlighterCount > 0 && fpState.hlListExpanded) {
            BoundedScrollBox(minOf(displayedHighlighterCount, filterListRows), rowDp = 30) {
                if (regexHighlightAvailable) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ColorPickerSwatch(
                                color = filter.kwHighlightColor,
                                pickerOpen = kwHighlightColorPickerOpen,
                                onClick = { kwHighlightColorPickerOpen = !kwHighlightColorPickerOpen },
                            )
                            AppText(
                                "/${filter.kwText}/i",
                                color = if (filter.kwHighlightEnabled) tc.tx else tc.td,
                                fontSize = 11.sp,
                                fontFamily = MONO,
                                modifier = Modifier.weight(1f),
                                overflow = TextOverflow.Ellipsis,
                            )
                            RoundIndicator(
                                active = filter.kwHighlightEnabled,
                                color = filter.kwHighlightColor,
                                onClick = { onSetKwHighlightEnabled(!filter.kwHighlightEnabled) },
                            )
                        }
                        if (kwHighlightColorPickerOpen) {
                            FlowRow(
                                Modifier.fillMaxWidth().padding(start = 30.dp, end = 12.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                HL_COLORS.forEach { color ->
                                    ColorSwatch(color, color == filter.kwHighlightColor) {
                                        onSetKwHighlightColor(color)
                                        kwHighlightColorPickerOpen = false
                                    }
                                }
                            }
                        }
                    }
                }
                filter.highlighters.forEach { hl ->
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ColorPickerSwatch(
                                color = hl.color, pickerOpen = colorPickerHlId == hl.id,
                                onClick = { colorPickerHlId = if (colorPickerHlId == hl.id) null else hl.id },
                            )
                            AppText((if (hl.regex) "/" else "") + hl.pattern + (if (hl.regex) "/i" else ""),
                                color = if (hl.on) tc.tx else tc.td, fontSize = 11.sp, fontFamily = MONO,
                                modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
                            RoundIndicator(active = hl.on, color = hl.color, onClick = { onToggleHl(hl.id) })
                            SquareIconButton("×", fontSize = 14.sp, onClick = { onRemoveHl(hl.id) })
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
                    Modifier.weight(1f)
                        .focusRequester(hlFr)
                        .onFocusChanged { hlFieldFocused = it.isFocused }
                        .onPreviewKeyEvent { ev ->
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
                AppButton("+ Add", onClick = doAddHl, variant = ButtonVariant.Ghost, enabled = newHlPat.isNotBlank())
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
        SectionHeader("Log level", expanded = fpState.lvlExpanded, onToggle = {
            fpState.lvlExpanded = !fpState.lvlExpanded
            onUiStateChanged()
        })
        if (fpState.lvlExpanded) {
            val levels = LogLevel.entries
            SegmentedControl(
                options = levels.map { it.key.toString() },
                selectedIndices = filter.levels.map { levels.indexOf(it) }.toSet(),
                onToggle = { idx -> onToggleLevel(levels[idx]) },
                selectedColors = levels.map { it.defaultColor },
                fillWidth = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Divider()

        // ── Sequences ─────────────────────────────────────────────
        SectionHeader("Sequences", expanded = fpState.seqExpanded, onToggle = {
            fpState.seqExpanded = !fpState.seqExpanded
            onUiStateChanged()
        })
        if (fpState.seqExpanded) {
            CheckRow(filter.seqOn, { onToggleSeq() }) {
                AppText("Group sequences", color = tc.ts, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
            if (tab.filter.sequences.isNotEmpty()) {
                var dragId by remember { mutableStateOf<String?>(null) }
                var dragStartIndex by remember { mutableStateOf(-1) }
                var dragStartTopY by remember { mutableStateOf(0f) }
                var dragOffsetY by remember { mutableStateOf(0f) }
                var justReleasedSequenceId by remember { mutableStateOf<String?>(null) }
                var liveVisualSequenceIds by remember { mutableStateOf(emptyList<String>()) }
                val density = LocalDensity.current.density
                val sequenceIds = tab.filter.sequences.map { it.id }
                LaunchedEffect(sequenceIds, dragId, justReleasedSequenceId) {
                    if (shouldSyncSequenceVisualOrder(dragId, justReleasedSequenceId)) {
                        liveVisualSequenceIds = sequenceIds
                    }
                }
                LaunchedEffect(justReleasedSequenceId) {
                    if (justReleasedSequenceId != null) {
                        kotlinx.coroutines.delay(120)
                        justReleasedSequenceId = null
                    }
                }
                val visualSequenceIds =
                    liveVisualSequenceIds.takeIf { it.toSet() == sequenceIds.toSet() && it.size == sequenceIds.size }
                        ?: sequenceIds
                val currentVisualSequenceIds = rememberUpdatedState(visualSequenceIds)
                val currentDragId = rememberUpdatedState(dragId)
                val rowHeightPx = 28f * density
                val rowHeightDp = (rowHeightPx / density).dp
                Box(
                    Modifier.fillMaxWidth()
                        .height(rowHeightDp * sequenceIds.size)
                        .pointerInput(sequenceIds, rowHeightPx) {
                            var downPos = Offset.Zero
                            var downId: String? = null
                            var dragging = false
                            awaitPointerEventScope {
                                while (true) {
                                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                                    val ch = ev.changes.firstOrNull() ?: continue
                                    when (ev.type) {
                                        PointerEventType.Press -> {
                                            downPos = ch.position
                                            dragging = false
                                            val idx = (ch.position.y / rowHeightPx).toInt()
                                                .coerceIn(0, sequenceIds.lastIndex.coerceAtLeast(0))
                                            downId = sequenceIds.getOrNull(idx)
                                        }

                                        PointerEventType.Move -> {
                                            if (downId != null && !dragging && (ch.position - downPos).getDistance() > 8f) {
                                                val id = downId ?: continue
                                                dragging = true
                                                dragId = id
                                                dragStartIndex = sequenceIds.indexOf(id)
                                                dragStartTopY = dragStartIndex * rowHeightPx
                                                dragOffsetY = 0f
                                                justReleasedSequenceId = null
                                                liveVisualSequenceIds = sequenceIds
                                            }
                                            if (dragging && dragId != null) {
                                                ch.consume()
                                                dragOffsetY = ch.position.y - downPos.y
                                                liveVisualSequenceIds = sequenceOrderDuringDrag(
                                                    visibleIds = sequenceIds,
                                                    draggedId = dragId,
                                                    dragStartIndex = dragStartIndex,
                                                    dragOffsetY = dragOffsetY,
                                                    rowHeight = rowHeightPx,
                                                )
                                            }
                                        }

                                        PointerEventType.Release -> {
                                            if (dragging && dragId != null) {
                                                val releasedId = currentDragId.value ?: dragId
                                                val releasedOrder = currentVisualSequenceIds.value
                                                val targetIdx = releasedOrder.indexOf(releasedId)
                                                if (releasedId != null && targetIdx >= 0 && targetIdx != sequenceIds.indexOf(releasedId)) {
                                                    liveVisualSequenceIds = releasedOrder
                                                    onReorderSeq(releasedId, targetIdx)
                                                }
                                                justReleasedSequenceId = releasedId
                                            }
                                            dragId = null
                                            dragStartIndex = -1
                                            dragStartTopY = 0f
                                            dragOffsetY = 0f
                                            downId = null
                                            dragging = false
                                        }

                                        else -> {}
                                    }
                                }
                            }
                        }
                ) {
                    tab.filter.sequences.forEach { def ->
                        key(def.id) {
                            val isDragging = dragId == def.id
                            val targetIndex = visualSequenceIds.indexOf(def.id).takeIf { it >= 0 }
                                ?: tab.filter.sequences.indexOf(def)
                            val targetY = targetIndex * rowHeightPx
                            val animatedY by animateFloatAsState(
                                targetValue = targetY,
                                animationSpec = spring(stiffness = 650f, dampingRatio = 0.86f),
                                label = "sequence-y-${def.id}",
                            )
                            val sequenceY = sequenceRenderY(
                                isDragging = isDragging,
                                isJustReleased = justReleasedSequenceId == def.id,
                                pointerY = dragStartTopY + dragOffsetY,
                                targetY = targetY,
                                animatedY = animatedY,
                            )
                            Column(
                                Modifier.fillMaxWidth()
                                    .height(rowHeightDp)
                                    .offset { IntOffset(0, sequenceY.roundToInt()) }
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .graphicsLayer {
                                        if (isDragging) {
                                            scaleX = 1.02f
                                            scaleY = 1.02f
                                        }
                                    }
                            ) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .fillMaxHeight()
                                        .background(sequenceRowBaseBackground(isDragging, def.enabled, tc))
                                        .background(if (isDragging) tc.ac.copy(.12f) else Color.Transparent)
                                        .padding(horizontal = 12.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    AppText("⠿", color = tc.td, fontSize = 12.sp)
                                    AppText("${targetIndex + 1}", color = tc.td, fontSize = 9.sp, fontFamily = MONO, modifier = Modifier.width(12.dp))
                                    ColorPickerSwatch(
                                        color = if (def.enabled) def.color else tc.br, size = 10.dp,
                                        pickerOpen = colorPickerSeqId == def.id,
                                        onClick = { colorPickerSeqId = if (colorPickerSeqId == def.id) null else def.id },
                                    )
                                    AppText(
                                        sequenceLabel(def),
                                        color = if (def.enabled) tc.tx else tc.td,
                                        fontSize = 11.sp, fontFamily = MONO, modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis,
                                    )
                                    SquareIconButton("✎", fontSize = 11.sp, onClick = {
                                        editingSeqId = if (editingSeqId == def.id) null else def.id
                                    })
                                    RoundIndicator(active = def.enabled, color = def.color, onClick = { onToggleSeqEnabled(def.id) })
                                    SquareIconButton("×", fontSize = 14.sp, onClick = { onRemoveSeq(def.id) })
                                }
                            }
                        }
                    }
                }
                tab.filter.sequences.firstOrNull { it.id == editingSeqId }?.let { def ->
                    SequenceEditor(def, onUpdateSeq, onCancel = { editingSeqId = null })
                }
                tab.filter.sequences.firstOrNull { it.id == colorPickerSeqId }?.let { def ->
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
            if (tab.manualBlocks.isNotEmpty()) {
                AppText("Collapsed ranges", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold,
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
                            ColorPickerSwatch(
                                color = if (block.enabled) block.color else tc.br, size = 10.dp,
                                pickerOpen = colorPickerManualId == block.id,
                                onClick = { colorPickerManualId = if (colorPickerManualId == block.id) null else block.id },
                            )
                            val direction = when (block.direction) {
                                ManualCollapseDirection.TO_START -> "to start"
                                ManualCollapseDirection.TO_END -> "to end"
                                ManualCollapseDirection.RANGE -> "selection"
                            }
                            Column(Modifier.weight(1f)) {
                                AppText(direction, color = if (block.enabled) tc.tx else tc.td, fontSize = 11.sp, fontFamily = MONO)
                                AppText(
                                    listOfNotNull(entry?.tag, entry?.msg).joinToString(": "),
                                    color = tc.td, fontSize = 9.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            RoundIndicator(active = block.enabled, color = block.color, onClick = { onToggleManualCollapse(block.id) })
                            SquareIconButton("×", fontSize = 14.sp, onClick = { onRemoveManualCollapse(block.id) })
                        }
                    }
                }
                tab.manualBlocks.firstOrNull { it.id == colorPickerManualId }?.let { block ->
                    FlowRow(
                        Modifier.fillMaxWidth().padding(start = 30.dp, end = 12.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SEQ_COLORS.forEach { c ->
                            Box(
                                Modifier.size(14.dp).background(c, CORNER_SM)
                                    .border(2.dp, if (c == block.color) tc.tx else Color.Transparent, CORNER_SM)
                                    .clickable { onSetManualBlockColor(block.id, c); colorPickerManualId = null },
                            )
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
                    InlineField(newSeqStartTag, onSetNewSeqStartTag, "start tag (optional)…", Modifier.fillMaxWidth())
                    InlineField(newSeqEndTag, onSetNewSeqEndTag, "end tag (optional)…", Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            AppText("Color", color = tc.td, fontSize = 10.sp, fontFamily = UI)
                            Box(
                                Modifier.size(20.dp)
                                    .border(0.5.dp, if (seqColorPickerOpen) tc.tx else tc.br, CORNER_SM)
                                    .background(newSeqColor, CORNER_SM)
                                    .clickable { seqColorPickerOpen = !seqColorPickerOpen },
                            )
                        }
                        AppButton("+ Add", onClick = {
                            onAddSeq(newSeqText, newSeqRegex, newSeqColor, newSeqStartTag, newSeqEndText, newSeqEndRegex, newSeqEndTag)
                            seqAddOpen = false
                            seqColorPickerOpen = false
                        }, variant = ButtonVariant.Ghost, enabled = newSeqText.isNotBlank())
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

        // ── Crashes & ANRs ────────────────────────────────────────
        // Same row-limit/collapse shape as Highlighters — always-on detection, not a user-defined
        // list, so there's nothing to add/remove here, only to browse and jump from.
        SectionHeader(
            "Crashes",
            trailing = if (crashSites.isNotEmpty()) ({
                AppText("${crashSites.size}", color = tc.td, fontSize = 10.sp, fontFamily = UI)
            }) else null,
            expanded = fpState.crashExpanded,
            onToggle = {
                fpState.crashExpanded = !fpState.crashExpanded
                onUiStateChanged()
            },
        )
        if (fpState.crashExpanded) {
            val crashCategoryCounts = remember(allCrashSites) {
                CrashCategory.entries.associateWith { c -> crashSitesForCategory(allCrashSites, c).size }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                CrashCategoryDropdown(
                    category = fpState.crashCategory,
                    counts = crashCategoryCounts,
                    onSelect = { category ->
                        fpState.crashCategory = category
                        onUiStateChanged()
                    },
                )
            }
            if (crashSites.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AppText("◆", color = tc.td.copy(.33f), fontSize = 18.sp)
                    AppText(
                        if (tab.analysis.pending) "Analyzing crashes…" else "None found in this category",
                        color = tc.td,
                        fontSize = 10.sp,
                        maxLines = 2,
                    )
                }
            } else {
                BoundedScrollBox(minOf(crashSites.size, filterListRows), rowDp = 44) {
                    crashSites.forEach { site ->
                        CrashSiteRow(site, tc, onClick = { onNavigateCrash(site) })
                    }
                }
            }
        }
        Divider()

        // ── Saved Filters ─────────────────────────────────────────
        SectionHeader("Saved filters", expanded = fpState.sfExpanded, onToggle = {
            fpState.sfExpanded = !fpState.sfExpanded
            onUiStateChanged()
        })
        if (fpState.sfExpanded) {
            if (savedFilters.isNotEmpty()) {
                ScrollableItems(savedFilters.size) {
                    savedFilters.forEach { sf ->
                        val active = activeFilterItemId == sf.id
                        HoverBox(modifier = Modifier.fillMaxWidth(), baseBg = if (active) tc.abg else Color.Transparent) {
                            Row(
                                Modifier.fillMaxWidth().clickable { onLoadFilter(sf) }.padding(horizontal = 12.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                // Own click handler (separate from the row's onLoadFilter click below):
                                // clicking while already active clears the live filter — the same effect
                                // as "Clear filters" — without deleting this saved preset.
                                RoundIndicator(
                                    active = active, color = tc.ac,
                                    onClick = { if (active) onClearFilter() else onLoadFilter(sf) },
                                )
                                TooltipArea(
                                    tooltip = {
                                        Box(
                                            Modifier.background(tc.p2, RoundedCornerShape(4.dp))
                                                .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                        ) {
                                            AppText(sf.name, color = tc.tx, fontSize = 11.sp)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    AppText(
                                        sf.name,
                                        color = if (active) tc.tx else tc.ts,
                                        fontSize = 11.sp,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                SquareIconButton("✎", fontSize = 12.sp, onClick = { onRenameSF(sf.id) })
                                SquareIconButton("×", fontSize = 14.sp, onClick = { onDeleteSF(sf.id) })
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppButton("+ Save current filter…", onClick = onOpenSFDialog, variant = ButtonVariant.Ghost, modifier = Modifier.fillMaxWidth())
                AppButton("Clear filters", onClick = onClearFilter, variant = ButtonVariant.Secondary, isDanger = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                    AppButton("Export", onClick = onExportFilters)
                    AppButton("Import", onClick = onImportFilters)
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AppText("Drop filter .json here to import", color = tc.td, fontSize = 10.sp)
                }
            }
        }
    }

    if (regexEditorOpen) {
        Dialog(onDismissRequest = { regexEditorOpen = false }) {
            val dialogTheme = tc()
            Column(
                Modifier.width(640.dp)
                    .background(dialogTheme.p, RoundedCornerShape(8.dp))
                    .border(1.dp, dialogTheme.br, RoundedCornerShape(8.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AppText("Edit regex search", color = dialogTheme.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                AppText(
                    "Searches the exact text shown in a log row. Apply keeps this as a transient search; save it manually from Saved filters when needed.",
                    color = dialogTheme.td,
                    fontSize = 11.sp,
                    maxLines = 2,
                )
                InlineField(
                    value = regexEditorText,
                    onValue = { regexEditorText = it },
                    placeholder = "regex…",
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    fontSize = 12.sp,
                    singleLine = false,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    AppButton("Apply", onClick = {
                        kwDisplay = regexEditorText
                        onSetKw(regexEditorText)
                        regexEditorOpen = false
                    }, variant = ButtonVariant.Primary)
                    AppButton("Cancel", onClick = { regexEditorOpen = false }, variant = ButtonVariant.Secondary)
                }
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
            style = appScrollbarStyle(tc()),
        )
    }
}

@Composable
private fun BoundedScrollBox(
    rowLimit: Int,
    rowDp: Int = 28,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val h = (rowLimit * rowDp).dp
    val scrollState = rememberScrollState()
    Box(modifier.fillMaxWidth().height(h)) {
        Column(Modifier.fillMaxSize().verticalScroll(scrollState), content = content)
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
            style = appScrollbarStyle(tc()),
        )
    }
}

private val NATIVE_CRASH_COLOR = Color(0xFF8957e5)

private fun CrashSite.accentColor(): Color = when {
    kind == CrashKind.NATIVE_CRASH -> NATIVE_CRASH_COLOR
    kind == CrashKind.ANR -> LogLevel.W.defaultColor
    isFatal -> DANGER_RED
    else -> LogLevel.D.defaultColor
}

private fun CrashSite.kindLabel(): String = when {
    kind == CrashKind.NATIVE_CRASH -> "Native crash"
    kind == CrashKind.ANR -> "ANR"
    isFatal -> "Fatal exception"
    else -> "Exception"
}

private fun CrashCategory.label(): String = when (this) {
    CrashCategory.ALL -> "All"
    CrashCategory.CRASHES -> "Crashes"
    CrashCategory.ANRS -> "ANRs"
    CrashCategory.FATAL_EXCEPTIONS -> "Fatal Exceptions"
    CrashCategory.EXCEPTIONS -> "Exceptions"
    CrashCategory.OTHERS -> "Others"
}

// Hand-rolled dropdown (matching this file's/App.kt's convention of custom-styled Popups rather
// than Material's default DropdownMenu chrome) — a clickable field showing the current category
// that opens a themed option list on click. The popup's width is measured from the field itself
// (rather than a fixed guess) so it never leaves a gap at the edge showing the crash list behind it.
@Composable
private fun CrashCategoryDropdown(
    category: CrashCategory,
    counts: Map<CrashCategory, Int>,
    onSelect: (CrashCategory) -> Unit,
) {
    val tc = tc()
    val density = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    var fieldWidth by remember { mutableStateOf(0.dp) }
    // The Popup's own dismissOnClickOutside also fires for a click back on the field itself (it's
    // "outside" the popup's bounds) — without this guard, that dismiss (closing it) and the
    // field's own onClick (toggling it) can both fire for the same press, netting out to "stayed
    // open" instead of the intended close. Suppressing the toggle for a moment after a dismiss
    // makes a click on the field while open reliably close it instead of racing back open.
    var suppressToggleUntilMs by remember { mutableStateOf(0L) }
    Box(
        Modifier.fillMaxWidth().onGloballyPositioned { coords ->
            fieldWidth = with(density) { coords.size.width.toDp() }
        },
    ) {
        HoverBox(
            modifier = Modifier.fillMaxWidth().height(26.dp)
                .clip(CORNER_SM)
                .background(tc.p2, CORNER_SM)
                .border(1.dp, tc.br, CORNER_SM),
            onClick = {
                if (System.currentTimeMillis() >= suppressToggleUntilMs) open = !open
            },
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AppText(category.label(), color = tc.tx, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                AppText(if (open) "▲" else "▼", color = tc.td, fontSize = 9.sp)
            }
        }
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(density) { 30.dp.roundToPx() }),
                onDismissRequest = {
                    open = false
                    suppressToggleUntilMs = System.currentTimeMillis() + 200
                },
                properties = PopupProperties(focusable = false),
            ) {
                Column(
                    Modifier.width(fieldWidth)
                        .shadow(8.dp, RoundedCornerShape(8.dp))
                        .background(tc.p, RoundedCornerShape(8.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(8.dp))
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    CrashCategory.entries.forEach { c ->
                        val active = c == category
                        HoverBox(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)),
                            baseBg = if (active) tc.abg else Color.Transparent,
                            onClick = { open = false; onSelect(c) },
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppText(
                                    c.label(),
                                    color = if (active) tc.ac else tc.tx,
                                    fontSize = 11.sp,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                AppText("${counts[c] ?: 0}", color = if (active) tc.ac else tc.td, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CrashSiteRow(
    site: CrashSite,
    tc: ThemeColors,
    onClick: () -> Unit,
) {
    val accent = site.accentColor()
    HoverBox(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(
            Modifier.fillMaxWidth()
                .border(BorderStroke(1.dp, tc.br.copy(.4f)))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.background(accent.copy(.15f), CORNER_SM).border(1.dp, accent.copy(.4f), CORNER_SM)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) { AppText(site.kindLabel(), color = accent, fontSize = 9.sp, fontWeight = FontWeight.SemiBold) }
                AppText(site.entry.ts, color = tc.td, fontSize = 9.sp, fontFamily = MONO)
                AppText(site.entry.tag, color = tc.td, fontSize = 9.sp, fontFamily = MONO,
                    modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
            }
            AppText(
                site.entry.msg, color = tc.tx, fontSize = 11.sp, fontFamily = MONO,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun FullTextHint(
    text: String,
    modifier: Modifier = Modifier,
    forceShow: Boolean = false,
    content: @Composable BoxScope.((TextLayoutResult) -> Unit) -> Unit,
) {
    val tc = tc()
    val density = LocalDensity.current
    var hovered by remember { mutableStateOf(false) }
    var isOverflowing by remember(text) { mutableStateOf(false) }
    var anchorHeightPx by remember { mutableStateOf(0) }
    Box(
        modifier
            .onSizeChanged { anchorHeightPx = it.height }
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
    ) {
        content { result -> isOverflowing = result.hasVisualOverflow }
        if ((hovered || forceShow) && isOverflowing) {
            // Popup(alignment, offset) aligns matching corners of anchor and popup — TopStart
            // means "popup's top-left = anchor's top-left", NOT "popup below anchor". Placing it
            // below requires shifting by the anchor's own *measured* height (device px, matching
            // offset's unit) plus a gap; a guessed constant (the previous approach, and an even
            // more wrong alignment=BottomStart before that) either overlaps the anchor on some
            // densities/text sizes or — with BottomStart — aligns the popup's own bottom-left to
            // the anchor's bottom-left, making the popup extend upward and fully cover the anchor.
            // Either overlap makes the popup the topmost hit-test target at the cursor, which
            // fires Exit on the anchor, hides the popup, then Enter fires again — rapid flicker.
            val gapPx = with(density) { 4.dp.roundToPx() }
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, anchorHeightPx + gapPx),
                properties = PopupProperties(focusable = false),
            ) {
                Box(
                    Modifier.widthIn(max = 520.dp)
                        .background(tc.p, RoundedCornerShape(5.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(5.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    AppText(text, color = tc.tx, fontSize = 11.sp, fontFamily = MONO, maxLines = 3, overflow = TextOverflow.Clip)
                }
            }
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

internal fun messageRuleAllScope(): MessageRuleScopeOption = MessageRuleScopeOption("All")

internal fun messageRuleScopeLabel(tag: String?, packagePrefix: String?): String = when {
    !tag.isNullOrBlank() -> tag
    !packagePrefix.isNullOrBlank() -> "$packagePrefix.*"
    else -> "All"
}

internal fun messageRuleScopeOptions(
    sortedTags: List<String>,
    search: String,
    limit: Int = 8,
): List<MessageRuleScopeOption> {
    val needle = search.trim()
    val prefixes = if (needle.isBlank()) emptyList() else packagePrefixCandidates(sortedTags, needle, limit = 4)
    val tags = sortedTags.asSequence()
        .filter { tag -> needle.isBlank() || tag.contains(needle, ignoreCase = true) }
        .filter { tag -> tag !in prefixes }
        .take(limit.coerceAtLeast(0))
        .map { tag -> MessageRuleScopeOption(label = tag, tag = tag) }
        .toList()
    return listOf(messageRuleAllScope()) +
        prefixes.map { prefix -> MessageRuleScopeOption(label = "$prefix.*", packagePrefix = prefix) } +
        tags
}

private fun ruleTargetPatternLabel(pattern: String, regex: Boolean, target: RuleTarget): String = when (target) {
    RuleTarget.PID_TID -> "pid:$pattern"
    RuleTarget.MESSAGE -> if (regex) "/$pattern/" else pattern
}

internal fun messageRulePillLabel(rule: MessageRule): String {
    val pattern = ruleTargetPatternLabel(rule.pattern, rule.regex, rule.target)
    val scope = messageRuleScopeLabel(rule.tag, rule.packagePrefix)
    // "→" rather than ".*" as the scope/pattern separator — a package-prefix scope label already
    // ends in ".*" (e.g. "com.app.*"), so using ".*" again here made scoped-prefix pills read as
    // "com.app.* .* pattern", two unrelated ".*" tokens back to back with no visual distinction.
    return if (scope == "All") pattern else "$scope → $pattern"
}

internal fun pendingMessageRulePatternLabel(pending: PendingMessageRuleDraft): String =
    ruleTargetPatternLabel(pending.pattern, pending.regex, pending.target)

internal fun messageRuleScopePrompt(include: Boolean): String =
    if (include) "Add + rule to" else "Add - rule to"

internal fun inputValueAfterEscape(value: String, escapePressed: Boolean): String =
    if (escapePressed) "" else value

data class MessageRuleInputSpec(
    val pattern: String,
    val regex: Boolean,
    val target: RuleTarget,
)

fun messageRuleInputSpec(input: String, regexMode: Boolean): MessageRuleInputSpec {
    val trimmed = input.trim()
    if (trimmed.isNotEmpty() && trimmed.all { it.isDigit() }) {
        return MessageRuleInputSpec(trimmed, regex = false, target = RuleTarget.PID_TID)
    }
    slashWrappedRegex(trimmed)?.let { pattern ->
        return MessageRuleInputSpec(pattern, regex = true, target = RuleTarget.MESSAGE)
    }
    return MessageRuleInputSpec(trimmed, regex = regexMode, target = RuleTarget.MESSAGE)
}

internal fun messageRuleInputConsumesKey(key: Key, hasActionCandidate: Boolean = false): Boolean =
    key == Key.Tab ||
        key == Key.DirectionDown ||
        key == Key.DirectionUp ||
        (hasActionCandidate && (key == Key.DirectionLeft || key == Key.DirectionRight)) ||
        key == Key.Escape ||
        key == Key.Enter ||
        key == Key.NumPadEnter

private fun slashWrappedRegex(input: String): String? {
    if (!input.startsWith("/") || input.length < 2) return null
    val end = input.lastIndexOf('/')
    if (end <= 0) return null
    val suffix = input.substring(end + 1)
    if (suffix.isNotEmpty() && suffix != "i") return null
    return input.substring(1, end).takeIf { it.isNotBlank() }
}

@Composable
private fun TagPill(tag: String, color: Color, onRemove: () -> Unit) {
    BoxWithConstraints {
        // Cap the text to the pill's actual available width (from the enclosing FlowRow), not a
        // guessed constant — the filter panel can be resized down to 140dp (FILTER_PANEL_MIN_WIDTH),
        // narrower than a fixed 260dp cap, which let the trailing × render past the panel's own
        // edge and get clipped instead of the text truncating to make room for it.
        val textCap = (maxWidth - 32.dp).coerceAtLeast(40.dp)
        Box(
            Modifier.background(color.copy(.13f), CORNER_SM)
                .border(1.dp, color.copy(.27f), CORNER_SM)
                .clip(CORNER_SM)
                .clickable(onClick = onRemove)
                .padding(start = 7.dp, end = 4.dp, top = 1.dp, bottom = 1.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                FullTextHint(tag) { onTextLayout ->
                    AppText(
                        tag, color = color, fontSize = 11.sp, fontFamily = MONO,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = textCap),
                        onTextLayout = onTextLayout,
                    )
                }
                AppText("×", color = color.copy(.7f), fontSize = 14.sp)
            }
        }
    }
}
