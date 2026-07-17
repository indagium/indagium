package com.openlog.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.openlog.model.*

internal data class FilterSearchRequest(
    val nonce: Long,
    val tabId: String,
    val target: CtrlFTarget,
)

internal fun filterSearchTargetForTab(request: FilterSearchRequest?, tabId: String): CtrlFTarget? =
    request?.takeIf { it.tabId == tabId }?.target

internal fun consumeFilterSearchRequest(
    pending: FilterSearchRequest?,
    consumed: FilterSearchRequest,
): FilterSearchRequest? = if (pending?.nonce == consumed.nonce) null else pending

@Composable
internal fun BoundFilterPanel(
    state: AppState,
    tab: LogTab,
    focusRequester: FocusRequester? = null,
    filterSearchRequest: FilterSearchRequest? = null,
    onFilterSearchRequestConsumed: (FilterSearchRequest) -> Unit = {},
    onPanelFocusChanged: (Boolean) -> Unit = {},
) {
    if (!state.filterVisible) return
    FilterPanel(
        tab = tab, savedFilters = state.savedFiltersForTab(tab.id),
        savedFilterFolders = state.savedFilterFolders,
        activeFilterItemId = state.activeFilterItemId(tab.id),
        tagUsage = state.tagUsage, fpState = state.fpState,
        newHlPat = state.newHlPat, newHlRx = state.newHlRx, newHlColor = state.newHlColor,
        newSeqText = state.newSeqText, newSeqRegex = state.newSeqRegex,
        newSeqEndText = state.newSeqEndText, newSeqEndRegex = state.newSeqEndRegex,
        newSeqStartTag = state.newSeqTag, newSeqEndTag = state.newSeqEndTag,
        newSeqColor = state.newSeqColor,
        onToggleLevel = { state.toggleLevel(tab.id, it) },
        onSetFilterMode = { state.setFilterMode(tab.id, it) },
        onToggleTag = { state.toggleTag(tab.id, it) },
        onToggleExcludeTag = { state.toggleExcludeTag(tab.id, it) },
        onSetKw = { state.setKw(tab.id, it) },
        onStartRegexSearch = { state.startRegexSearch(tab.id) },
        onSetKwHighlightEnabled = { state.setKwHighlightEnabled(tab.id, it) },
        onSetKwHighlightColor = { state.setKwHighlightColor(tab.id, it) },
        onToggleSeq = { state.toggleSeq(tab.id) },
        onAddSeq = { t, r, c, st, et, er, eg -> state.addSequence(t, r, c, st, et, er, eg) },
        onRemoveSeq = { state.removeSequence(it) },
        onToggleSeqEnabled = { state.toggleSequence(it) },
        onSetSeqColor = { id, c -> state.setSequenceColor(id, c) },
        onUpdateSeq = { id, text, rx, tag, endText, endRx, endTag ->
            state.updateSequence(id, text, rx, tag, endText, endRx, endTag)
        },
        onToggleManualCollapse = { state.toggleManualCollapse(tab.id, it) },
        onRemoveManualCollapse = { state.removeManualCollapse(tab.id, it) },
        onSetManualBlockColor = { id, c -> state.setManualBlockColor(tab.id, id, c) },
        onAddMessageRule = { include, pattern, regex, tag, prefix, target ->
            state.addMessageRule(tab.id, include, pattern, regex, tag, prefix, target)
        },
        onRemoveMessageRule = { state.removeMessageRule(tab.id, it) },
        onToggleMessageRuleRegex = { state.toggleKwInTagRx(tab.id) },
        onMoveSeqUp = { state.moveSequenceUp(it) },
        onMoveSeqDown = { state.moveSequenceDown(it) },
        onReorderSeq = { id, index -> state.reorderSequence(id, index) },
        onSetNewSeqText = { state.newSeqText = it },
        onSetNewSeqRx = { state.newSeqRegex = it },
        onSetNewSeqEndText = { state.newSeqEndText = it },
        onSetNewSeqEndRx = { state.newSeqEndRegex = it },
        onSetNewSeqStartTag = { state.newSeqTag = it },
        onSetNewSeqEndTag = { state.newSeqEndTag = it },
        onSetNewSeqColor = { state.newSeqColor = it },
        onAddHl = { p, r, c -> state.addHl(tab.id, p, r, c) },
        onRemoveHl = { state.removeHl(tab.id, it) },
        onToggleHl = { state.toggleHl(tab.id, it) },
        onSetHlColor = { id, c -> state.setHighlighterColor(tab.id, id, c) },
        onSetNewHlPat = { state.newHlPat = it },
        onSetNewHlRx = { state.newHlRx = it },
        onSetNewHlColor = { state.newHlColor = it },
        onLoadFilter = { state.requestLoadFilter(tab.id, it) },
        onDeleteSF = { state.requestDeleteSF(it) },
        onRenameSF = { state.beginRenameFilter(it) },
        onToggleSFFavorite = { state.toggleSavedFilterFavorite(it) },
        onMoveSFToFolder = { id, folderId -> state.moveSavedFilter(id, folderId) },
        onReorderSFWithinFolder = { id, index -> state.reorderSavedFilterWithinFolder(id, index) },
        onReorderSFFolder = { id, index -> state.reorderSavedFilterFolder(id, index) },
        onCreateSFFolder = { state.createSavedFilterFolder(it) },
        onRenameSFFolder = { id, name -> state.renameSavedFilterFolder(id, name) },
        onDeleteSFFolder = { state.requestDeleteSavedFilterFolder(it) },
        onOpenSFDialog = {
            state.sfDialog = true
            state.sfTabId = tab.id
            state.sfName = ""
            state.sfFolderId = null
        },
        onSetKwInTag = { state.setKwInTag(tab.id, it) },
        onAddPkgPrefix = { state.addPkgPrefix(tab.id, it) },
        onRemovePkgPrefix = { state.removePkgPrefix(tab.id, it) },
        onAddExcludePkgPrefix = { state.addExcludePkgPrefix(tab.id, it) },
        onRemoveExcludePkgPrefix = { state.removeExcludePkgPrefix(tab.id, it) },
        onExportFilters = { state.beginExportFilters() },
        onImportFilters = { state.importFiltersFromFile() },
        onImportFiltersFromFiles = { files -> state.importFiltersFromFilesAsync(files) },
        onClearFilter = { state.requestClearFilter(tab.id) },
        onNavigateCrash = { site -> state.requestCrashNavigation(tab.id, site.entry.id) },
        onUiStateChanged = { state.autosaveNow() },
        mostUsedTagLimit = state.settings.mostUsedTagLimit,
        filterListRows = state.settings.filterListRows,
        width = state.filterPanelWidth,
        focusRequester = focusRequester,
        filterSearchRequest = filterSearchRequest,
        onFilterSearchRequestConsumed = onFilterSearchRequestConsumed,
        onPanelFocusChanged = onPanelFocusChanged,
        keyboardFocusVisible = state.keyboardFocusVisible,
    )
    HDivider { delta -> state.updateFilterPanelWidth(state.filterPanelWidth + delta) }
}

// ── FileView ──────────────────────────────────────────────────────────
@Composable
internal fun FileView(
    state: AppState,
    tab: LogTab,
    requestedPanelFocus: KeyboardPanel? = null,
    filterSearchRequest: FilterSearchRequest? = null,
    onFilterSearchRequestConsumed: (FilterSearchRequest) -> Unit = {},
    onPanelFocusConsumed: () -> Unit = {},
) {
    val filterFr = remember { FocusRequester() }
    val logViewerFr = remember { FocusRequester() }
    val annotationFr = remember { FocusRequester() }
    val aiFr = remember { FocusRequester() }
    var focusedPanelIdx by remember { mutableStateOf(0) }

    fun visiblePanelFrs(): List<Pair<KeyboardPanel, FocusRequester>> = buildList {
        if (state.filterVisible) add(KeyboardPanel.FILTERS to filterFr)
        add(KeyboardPanel.LOG_VIEW to logViewerFr)
        if (state.annotationVisible) add(KeyboardPanel.NOTES to annotationFr)
        if (state.aiPanelVisible) add(KeyboardPanel.AI to aiFr)
    }

    LaunchedEffect(requestedPanelFocus, state.filterVisible, state.annotationVisible, state.aiPanelVisible, tab.id) {
        val panel = requestedPanelFocus ?: return@LaunchedEffect
        val fr = visiblePanelFrs().firstOrNull { it.first == panel }?.second ?: return@LaunchedEffect
        runCatching { fr.requestFocus() }
        onPanelFocusConsumed()
    }

    Row(
        Modifier.fillMaxSize().onPreviewKeyEvent { ev ->
            if (ev.type == KeyEventType.KeyDown && ev.key == Key.F6) {
                val frs = visiblePanelFrs()
                if (frs.isNotEmpty()) {
                    val delta = if (ev.isShiftPressed) -1 else 1
                    val next = (focusedPanelIdx + delta).mod(frs.size)
                    state.keyboardFocusVisible = true
                    runCatching { frs[next].second.requestFocus() }
                }
                true
            } else {
                false
            }
        },
    ) {
        BoundFilterPanel(
            state, tab,
            focusRequester = filterFr,
            filterSearchRequest = filterSearchRequest,
            onFilterSearchRequestConsumed = onFilterSearchRequestConsumed,
            onPanelFocusChanged = { focused ->
                if (focused) focusedPanelIdx = visiblePanelFrs().indexOfFirst { it.second == filterFr }
            },
        )
        LogViewer(
            tab = tab, modifier = Modifier.weight(1f),
            settings = state.settings,
            onSelRow = { id, multi, range -> state.selRow(tab.id, id, multi, range) },
            onSelRowRange = { ids -> state.setSelectedRows(tab.id, ids) },
            onCtxMenu = { id, x, y, sel, panelSel -> state.ctx = CtxMenuState(tab.id, id, x, y, sel, panelSel) },
            onToggleGroup = { state.toggleGroup(tab.id, it) },
            onClearFilter = { state.requestClearFilter(tab.id) },
            onExpandAll = { state.expandAll(tab.id) },
            onCollapseAll = { state.collapseAll(tab.id) },
            onToggleUnfiltered = { state.toggleUnfiltered(tab.id) },
            onExportTxt = { state.exportFilteredTxt(tab.id) },
            onExportCsv = { state.exportFilteredCsv(tab.id) },
            scrollStateStore = state.logViewerScrollStateStore,
            annotationNavigationRequest = state.pendingAnnotationNavigation,
            onConsumeAnnotationNavigation = { state.consumeAnnotationNavigation(it) },
            onSelectAll = { state.selectAll(tab.id) },
            onClearSelection = { state.clearSelection(tab.id) },
            onCopySelection = { selectedIds -> state.copySelectedLines(tab.id, selectedIds) },
            onCopyText = { text -> state.copyToClipboard(text) },
            navScrollMargin = state.settings.navScrollMargin,
            focusRequester = logViewerFr,
            onPanelFocusChanged = { focused ->
                if (focused) focusedPanelIdx = visiblePanelFrs().indexOfFirst { it.second == logViewerFr }
            },
            keyboardFocusVisible = state.keyboardFocusVisible,
            onVisibleItems = { summary -> state.noteVisibleItems(tab.id, summary) },
            onHoverPanelKey = { key -> state.hoveredLogPanelKey = key },
        )
        if (state.annotationVisible || state.aiPanelVisible) {
            HDivider { delta ->
                state.updateAnnotationPanelWidth(state.annotationPanelWidth - delta)
            }
            RightSidebarPanel(
                state = state,
                tab = tab,
                width = state.annotationPanelWidth,
                aiFocusRequester = aiFr,
                onAiPanelFocusChanged = { focused ->
                    if (focused) focusedPanelIdx = visiblePanelFrs().indexOfFirst { it.second == aiFr }
                },
            ) {
                AnnotationPanel(
                    tab = tab,
                    settings = state.settings,
                    recentNotes = state.recentNotesForTab(tab),
                    recentNotesMenuOpen = state.recentNotesMenuOpen,
                    onToggleMd = { state.toggleMd(tab.id) },
                    onCopy = { state.copyAnn(tab.id) },
                    onSave = { state.saveAnalysis(tab.id) },
                    onToggleRecentNotes = { state.toggleRecentNotesMenu() },
                    onOpenNote = { state.openNoteFileAsync(tab.id, it) },
                    onUpdatePrefix = { state.setPrefix(tab.id, it) },
                    onUpdateSuffix = { state.setSuffix(tab.id, it) },
                    onUpdateIssueDescription = { state.setIssueDescription(tab.id, it) },
                    onUpdateBlock = { blockId, text -> state.updateBlock(tab.id, blockId, text) },
                    onRemoveBlock = { state.removeBlock(tab.id, it) },
                    onMoveBlock = { blockId, d -> state.moveBlock(tab.id, blockId, d) },
                    onReorderBlock = { blockId, idx -> state.reorderBlock(tab.id, blockId, idx) },
                    onAddNoteAfter = { state.addNoteBlock(tab.id, it) },
                    onNavigateLogRef = { state.requestAnnotationNavigation(tab.id, it) },
                    width = state.annotationPanelWidth,
                    focusRequester = annotationFr,
                    onPanelFocusChanged = { focused ->
                        if (focused) focusedPanelIdx = visiblePanelFrs().indexOfFirst { it.second == annotationFr }
                    },
                    keyboardFocusVisible = state.keyboardFocusVisible,
                    scrollStateStore = state.logViewerScrollStateStore,
                    highlightedBlockId = state.aiEvidenceNoteTarget?.takeIf { it.tabId == tab.id }?.blockId,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
