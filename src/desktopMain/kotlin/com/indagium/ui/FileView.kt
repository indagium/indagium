package com.indagium.ui

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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.indagium.model.*

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
    filterBarVisible: Boolean = false,
    filterSearchRequest: FilterSearchRequest? = null,
    onFilterSearchRequestConsumed: (FilterSearchRequest) -> Unit = {},
    onPanelFocusChanged: (Boolean) -> Unit = {},
) {
    if (!state.filterVisible) return
    // Grouped into one data class rather than five more lambdas on this already-~90-parameter
    // call (see LogCompositionActions' own doc) — remember(state, tab.id) so it stays == across
    // recompositions that don't change tab identity, which is what keeps FilterPanel skippable.
    // Each lambda re-reads state.tab(tab.id) itself rather than closing over the `tab` parameter
    // above, since remember's keys (state, tab.id) don't capture a fresh `tab` on every
    // recomposition — closing over it here would leave these lambdas holding a stale rmap/logData
    // snapshot from whenever the bag was first built for this tab.id.
    val logCompositionActions = remember(state, tab.id) {
        fun sampleFor(template: MessageTemplate): String =
            state.tab(tab.id)?.rmap?.get(template.firstEntryId)?.msg ?: template.template
        // Hide/Show only/Highlight toggle (Stage 2c): each press either applies or removes the
        // corresponding rule/highlighter for this row, decided by AppState.toggleMessageRuleForTemplate
        // / toggleHighlightForTemplate — see those functions' doc for the shared "same shape" check
        // that also drives the panel's active/inactive button rendering.
        LogCompositionActions(
            onExpand = { state.requestMessageComposition(tab.id) },
            // Kick the rescan off immediately rather than letting the panel's debounced
            // filter-watcher notice 400ms later: that debounce exists for typing in a message-rule
            // box, and a button press is a discrete action that should not wait it out.
            // requestMessageComposition is single-flight, so the debounced call that follows for
            // the same filter is a no-op.
            onHide = { template ->
                state.toggleMessageRuleForTemplate(tab.id, template, include = false)
                state.requestMessageComposition(tab.id)
            },
            onShowOnly = { template ->
                state.toggleMessageRuleForTemplate(tab.id, template, include = true)
                state.requestMessageComposition(tab.id)
            },
            onHighlight = { template -> state.toggleHighlightForTemplate(tab.id, template) },
            onHighlightMode = { template, wholeLine -> state.toggleHighlightForTemplate(tab.id, template, wholeLine) },
            onGoToFirst = { template -> state.requestLineNavigation(tab.id, template.firstEntryId) },
        )
    }
    val highlighterCustomColors = customHighlightColors(state.settings.highlighterCustomColors)
    val highlighterPaletteColumns = state.settings.highlighterPaletteColumns
    val customColorEditorExpanded = state.settings.highlighterCustomColorEditorExpanded
    val highlighterActions = remember(state, tab.id, highlighterCustomColors, highlighterPaletteColumns, customColorEditorExpanded) {
        HighlighterActions(
            onAdd = { pattern, regex, color, wholeLine, target, tag, backgroundEnabled, textColor, fontFamily, bold, italic ->
                state.addHl(
                    tab.id, pattern, regex, color, wholeLine = wholeLine, target = target, tag = tag,
                    backgroundEnabled = backgroundEnabled, textColor = textColor, fontFamily = fontFamily, bold = bold, italic = italic,
                )
            },
            onRemove = { state.removeHl(tab.id, it) },
            onToggle = { state.toggleHl(tab.id, it) },
            onSetColor = { id, c -> state.setHighlighterColor(tab.id, id, c) },
            onUpdate = { id, transform -> state.updateHighlighter(tab.id, id, transform) },
            onSetNewPattern = { state.newHlPat = it },
            onSetNewRegex = { state.newHlRx = it },
            onSetNewColor = { state.newHlColor = it },
            onSetKwHighlightEnabled = { state.setKwHighlightEnabled(tab.id, it) },
            onSetKwHighlightColor = { state.setKwHighlightColor(tab.id, it) },
            onRequestMessageComposition = { state.requestMessageComposition(tab.id) },
            customColors = highlighterCustomColors,
            paletteColumns = highlighterPaletteColumns,
            onSaveCustomColor = { color ->
                val hex = highlightColorHex(color)
                state.updateSettings { settings ->
                    val current = customHighlightColors(settings.highlighterCustomColors)
                    if (hex in current.map(::highlightColorHex) || current.size >= MAX_CUSTOM_HIGHLIGHT_COLORS) {
                        settings
                    } else {
                        settings.copy(highlighterCustomColors = (current.map(::highlightColorHex) + hex).take(MAX_CUSTOM_HIGHLIGHT_COLORS))
                    }
                }
            },
            onDeleteCustomColor = { color ->
                state.updateSettings { settings ->
                    settings.copy(
                        highlighterCustomColors = settings.highlighterCustomColors.filterNot {
                            parseHighlightHex(it) == color
                        },
                    )
                }
            },
            onPaletteColumnsChange = { columns ->
                state.updateSettings { it.copy(highlighterPaletteColumns = if (columns >= 10) 10 else 5) }
            },
            customColorEditorExpanded = customColorEditorExpanded,
            onCustomColorEditorExpandedChange = { expanded ->
                state.updateSettings { it.copy(highlighterCustomColorEditorExpanded = expanded) }
            },
        )
    }
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
        onToggleSeq = { state.toggleSeq(tab.id) },
        onAddSeq = { t, r, c, st, et, er, eg -> state.addSequence(tab.id, t, r, c, st, et, er, eg) },
        onRemoveSeq = { state.removeSequence(tab.id, it) },
        onToggleSeqEnabled = { state.toggleSequence(tab.id, it) },
        onSetSeqColor = { id, c -> state.setSequenceColor(tab.id, id, c) },
        onUpdateSeq = { id, text, rx, tag, endText, endRx, endTag ->
            state.updateSequence(tab.id, id, text, rx, tag, endText, endRx, endTag)
        },
        onSetSeqScoped = { id, scoped -> state.setSequenceScoped(tab.id, id, scoped) },
        onScopeCrossingThreadPair = { hint -> state.scopeCrossingThreadPair(tab.id, hint) },
        onToggleManualCollapse = { state.toggleManualCollapse(tab.id, it) },
        onRemoveManualCollapse = { state.removeManualCollapse(tab.id, it) },
        onSetManualBlockColor = { id, c -> state.setManualBlockColor(tab.id, id, c) },
        onAddMessageRule = { include, pattern, regex, tag, prefix, target ->
            state.addMessageRule(tab.id, include, pattern, regex, tag, prefix, target)
        },
        onRemoveMessageRule = { state.removeMessageRule(tab.id, it) },
        onToggleMessageRuleRegex = { state.toggleKwInTagRx(tab.id) },
        onMoveSeqUp = { state.moveSequenceUp(tab.id, it) },
        onMoveSeqDown = { state.moveSequenceDown(tab.id, it) },
        onReorderSeq = { id, index -> state.reorderSequence(tab.id, id, index) },
        onSetNewSeqText = { state.newSeqText = it },
        onSetNewSeqRx = { state.newSeqRegex = it },
        onSetNewSeqEndText = { state.newSeqEndText = it },
        onSetNewSeqEndRx = { state.newSeqEndRegex = it },
        onSetNewSeqStartTag = { state.newSeqTag = it },
        onSetNewSeqEndTag = { state.newSeqEndTag = it },
        onSetNewSeqColor = { state.newSeqColor = it },
        highlighterActions = highlighterActions,
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
        onUnhandledFileDrop = { files -> state.openDroppedFiles(files) },
        onClearFilter = { state.requestClearFilter(tab.id) },
        onNavigateCrash = { site -> state.requestLineNavigation(tab.id, site.entry.id) },
        onChooseRetraceMapping = { state.chooseRetraceMapping(tab.id) },
        onClearRetraceMapping = { state.clearRetraceMapping(tab.id) },
        onRetraceIssue = { groupGid -> state.retraceIssue(tab.id, groupGid) },
        logCompositionActions = logCompositionActions,
        onUiStateChanged = { state.autosaveNow() },
        mostUsedTagLimit = state.settings.mostUsedTagLimit,
        filterListRows = state.settings.filterListRows,
        customIssueRules = state.settings.customIssueRules,
        width = state.filterPanelWidth,
        focusRequester = focusRequester,
        filterBarVisible = filterBarVisible,
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

    Column(
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
        // captureSourceSessionId keeps the strip mounted after a stop finishes and
        // captureFinalizationStatus clears — otherwise Save ZIP/Open folder vanished the instant
        // finalization succeeded, the exact moment a user is most likely to want to export.
        if (tab.captureSessionId != null ||
            state.captureFinalizationStatus(tab.id) != null || tab.captureSourceSessionId != null
        ) {
            CaptureStrip(
                state = state,
                tab = tab,
                onReturnFocus = { runCatching { logViewerFr.requestFocus() } },
            )
        }
        // Tracked so the right sidebar's rendered width can be clamped to what this row actually
        // has available (annotationPanelEffectiveMaxWidth below) — the stored annotationPanelWidth
        // alone (now allowed up to ANNOTATION_PANEL_MAX_WIDTH, for a big capture mirror) would
        // otherwise happily squeeze LogViewer's weight(1f) share to nothing on a narrow window.
        var rowWidthPx by remember { mutableStateOf(0) }
        val rowDensity = LocalDensity.current
        Row(Modifier.weight(1f).fillMaxWidth().onSizeChanged { rowWidthPx = it.width }) {
            BoundFilterPanel(
                state, tab,
                focusRequester = filterFr,
                filterBarVisible = state.filterBarVisible,
                filterSearchRequest = filterSearchRequest,
                onFilterSearchRequestConsumed = onFilterSearchRequestConsumed,
                onPanelFocusChanged = { focused ->
                    if (focused) focusedPanelIdx = visiblePanelFrs().indexOfFirst { it.second == filterFr }
                },
            )
            // Horizontal filter bar (ui/FilterBar.kt) — independently gated by filterBarVisible.
            // BoundFilterPanel remains mounted whenever filterVisible is true; FilterPanel hides only
            // its duplicate Tags/Regex/message-rule controls while this bar is active. The model is a
            // plain data class of values recomputed per recomposition (cheap, and content-equality is
            // what keeps LogViewer skippable); actions holds lambdas and is remembered below.
            val filterBarSortedTags = remember(tab.id, tab.analysis.tagCounts) {
                tab.analysis.tagCounts.entries.sortedByDescending { it.value }.map { it.key }
            }
            val filterBarModel = if (state.filterBarVisible) {
                FilterBarModel(
                    sortedTags = filterBarSortedTags,
                    tagUsage = state.tagUsage,
                    mostUsedTagLimit = state.settings.mostUsedTagLimit,
                    regexHistory = state.regexPatternHistory,
                    showRegexFilterSummary = state.settings.showRegexFilterSummary,
                )
            } else {
                null
            }
            val filterBarActions = remember(state, tab.id) {
                FilterBarActions(
                    onSetFilterMode = { mode -> state.setFilterMode(tab.id, mode) },
                    onStartRegexSearch = { state.startRegexSearch(tab.id) },
                    onToggleTag = { state.toggleTag(tab.id, it) },
                    onToggleExcludeTag = { state.toggleExcludeTag(tab.id, it) },
                    onAddPkgPrefix = { state.addPkgPrefix(tab.id, it) },
                    onRemovePkgPrefix = { state.removePkgPrefix(tab.id, it) },
                    onAddExcludePkgPrefix = { state.addExcludePkgPrefix(tab.id, it) },
                    onRemoveExcludePkgPrefix = { state.removeExcludePkgPrefix(tab.id, it) },
                    onSetKwInTag = { state.setKwInTag(tab.id, it) },
                    onToggleKwInTagRegex = { state.toggleKwInTagRx(tab.id) },
                    onSetKw = { state.setKw(tab.id, it) },
                    onAddMessageRule = { include, pattern, regex, tag, prefix, target ->
                        state.addMessageRule(tab.id, include, pattern, regex, tag, prefix, target)
                    },
                    onRemoveMessageRule = { state.removeMessageRule(tab.id, it) },
                    onRememberRegexPattern = { state.rememberRegexPattern(it) },
                    onClearRegexHistory = { state.clearRegexPatternHistory() },
                    onOpenFilterPanel = { state.updateFilterVisible(true) },
                )
            }
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
                onToggleTimeDelta = { state.toggleTimeDelta(tab.id) },
                onOpenSearch = { if (tab.search.active) state.closeSearch(tab.id) else state.openSearch(tab.id) },
                onToggleRowNumbers = { state.updateSettings { it.copy(showRowNumbers = !it.showRowNumbers) } },
                onToggleMinimap = { state.updateSettings { it.copy(showMinimap = !it.showMinimap) } },
                onSetProcessNameMode = { mode -> state.setProcessNameMode(tab.id, mode) },
                onSetTidMapHighlight = { colorKey -> state.setTidMapHighlight(tab.id, colorKey) },
                onExportTxt = { state.exportFilteredTxt(tab.id) },
                onExportCsv = { state.exportFilteredCsv(tab.id) },
                scrollStateStore = state.logViewerScrollStateStore,
                annotationNavigationRequest = state.pendingAnnotationNavigation,
                onConsumeAnnotationNavigation = { state.consumeAnnotationNavigation(it) },
                searchNavigationRequest = state.pendingSearchNavigation,
                onConsumeSearchNavigation = { state.consumeSearchNavigation(it) },
                onSelectAll = { state.selectAll(tab.id) },
                onClearSelection = { state.clearSelection(tab.id) },
                onCopySelection = { selectedIds -> state.copySelectedLines(tab.id, selectedIds) },
                onAddAnnotation = { ids -> state.requestAddAnn(tab.id, ids) },
                onCopyText = { text -> state.copyToClipboard(text) },
                onLogRowDoubleClick = { id -> state.seekVideoToLogRow(tab.id, id) },
                onLogRowDoubleClickGestureStarted = { state.beginVideoLogDoubleClickGesture(tab.id) },
                onLogRowDoubleClickGestureExpired = { state.endVideoLogDoubleClickGesture(tab.id) },
                navScrollMargin = state.settings.navScrollMargin,
                focusRequester = logViewerFr,
                onPanelFocusChanged = { focused ->
                    if (focused) {
                        focusedPanelIdx = visiblePanelFrs().indexOfFirst { it.second == logViewerFr }
                        state.searchFocusTabId = tab.id
                    }
                },
                keyboardFocusVisible = state.keyboardFocusVisible,
                onVisibleItems = { summary -> state.noteVisibleItems(tab.id, summary) },
                onHoverPanelKey = { key -> state.hoveredLogPanelKey = key },
                onSearchQueryChange = { query -> state.setSearchQuery(tab.id, query) },
                onSearchToggleCase = { state.toggleSearchCase(tab.id) },
                onSearchNext = { state.searchNext(tab.id) },
                onSearchPrev = { state.searchPrev(tab.id) },
                onSearchClose = { state.closeSearch(tab.id) },
                showSearchScopeChip = true,
                onSearchToggleScope = {
                    val next = if (tab.search.scope == SearchScope.FILTERED) SearchScope.UNFILTERED else SearchScope.FILTERED
                    state.setSearchScope(tab.id, next)
                },
                filterBar = filterBarModel,
                filterBarActions = filterBarActions,
                filterBarVisible = state.filterBarVisible,
                onToggleFilterBar = { state.updateFilterBarVisible(!state.filterBarVisible) },
            )
            val liveStatusSidebarVisible = state.videoPanelVisible &&
                (tab.attachedVideo != null || tab.captureSessionId != null)
            val notesVisibleForTab = state.annotationVisible
            if (notesVisibleForTab || state.aiPanelVisible || liveStatusSidebarVisible) {
                val rowWidthDp = with(rowDensity) { rowWidthPx.toDp().value }
                val annotationEffectiveMax = annotationPanelEffectiveMaxWidth(
                    availableRowWidth = rowWidthDp,
                    filterVisible = state.filterVisible,
                    filterPanelWidth = state.filterPanelWidth,
                )
                // The rendered width only ever clamps DOWN from the stored value — the stored
                // value itself is left alone so widening the window again restores it without a
                // redrag. Both the sidebar's own width and the divider drag below are computed
                // from this same rendered value, never from the raw (possibly much larger) stored
                // annotationPanelWidth, so dragging tracks the mouse from wherever the sidebar's
                // edge actually is on screen right now.
                val annotationRenderedWidth = minOf(state.annotationPanelWidth, annotationEffectiveMax)
                HDivider { delta ->
                    // Recompute live instead of closing over annotationRenderedWidth/
                    // annotationEffectiveMax above: those are plain vals frozen at whatever this
                    // composable's last recomposition happened to see, but HDivider's drag gesture
                    // (Components.kt) can fire several deltas before recomposition catches up, and
                    // state.annotationPanelWidth already reflects each prior delta immediately
                    // (mutableStateOf writes are synchronous even when recomposition is deferred).
                    // Closing over the stale locals here reproduced the drag jitter/snap-back —
                    // every delta landed on the same pre-drag base instead of the mouse's actual
                    // running position.
                    val liveEffectiveMax = annotationPanelEffectiveMaxWidth(
                        availableRowWidth = with(rowDensity) { rowWidthPx.toDp().value },
                        filterVisible = state.filterVisible,
                        filterPanelWidth = state.filterPanelWidth,
                    )
                    val liveRenderedWidth = minOf(state.annotationPanelWidth, liveEffectiveMax)
                    state.updateAnnotationPanelWidth((liveRenderedWidth - delta).coerceAtMost(liveEffectiveMax))
                }
                RightSidebarPanel(
                    state = state,
                    tab = tab,
                    width = annotationRenderedWidth,
                    aiFocusRequester = aiFr,
                    onAiPanelFocusChanged = { focused ->
                        if (focused) focusedPanelIdx = visiblePanelFrs().indexOfFirst { it.second == aiFr }
                    },
                    notesVisible = notesVisibleForTab,
                    notesContent = {
                        AnnotationPanel(
                            tab = tab,
                            settings = state.settings,
                            recentNotes = state.recentNotesForTab(tab),
                            recentNotesMenuOpen = state.recentNotesMenuOpen,
                            activeNotePath = state.activeNoteFilePath(tab),
                            onToggleMd = { state.toggleMd(tab.id) },
                            onCopy = { state.copyAnn(tab.id) },
                            onCopyFormat = { state.copyAnnotationFormat(tab.id, it) },
                            onCopyImage = { block -> state.copyImageToClipboard(block.bytes, block.provenance) },
                            onCopyDiagramImage = { png, fallback -> state.copyImageToClipboard(png, fallback) },
                            onExportFrames = { state.exportAnnotationFrames(tab.id) },
                            onSave = { state.saveAnalysis(tab.id) },
                            onNewAnalysis = { state.newAnalysis(tab.id) },
                            onToggleRecentNotes = { state.toggleRecentNotesMenu() },
                            onOpenNote = { state.openNoteFileAsync(tab.id, it) },
                            onLocateLog = { state.locateLogForTab(tab.id, it) },
                            showUnverifiedRelinkNotice = state.logRelinkUnverifiedTabId == tab.id,
                            onDismissUnverifiedRelinkNotice = { state.dismissLogRelinkUnverifiedNotice() },
                            onUpdatePrefix = { state.setPrefix(tab.id, it) },
                            onUpdateSuffix = { state.setSuffix(tab.id, it) },
                            onUpdateIssueDescription = { state.setIssueDescription(tab.id, it) },
                            onUpdateBlock = { blockId, text -> state.updateBlock(tab.id, blockId, text) },
                            onRemoveBlock = { state.removeBlock(tab.id, it) },
                            onMoveBlock = { blockId, d -> state.moveBlock(tab.id, blockId, d) },
                            onReorderBlock = { blockId, idx -> state.reorderBlock(tab.id, blockId, idx) },
                            onAddNoteAfter = { state.addNoteBlock(tab.id, it) },
                            onAddImage = { bytes, provenance, after -> state.addImageBlock(tab.id, bytes, provenance, after) },
                            onUnhandledFileDrop = { files -> state.openDroppedFiles(files) },
                            onNavigateLogRef = { state.requestAnnotationNavigation(tab.id, it) },
                            onNavigateVideoFrame = { state.navigateToVideoFrame(tab.id, it) },
                            onEditDiagram = { blockId -> state.seq3Sessions.beginEdit(tab.id, blockId) },
                            onNavigateDiagramLine = { entryId -> state.navigateToLogLine(tab.id, entryId) },
                            onImportLinkedDiagram = { blockId, source, dialect, confirm ->
                                val diagramId = (tab.annotations.blocks.filterIsInstance<com.indagium.model.AnnBlock.Note>()
                                    .firstOrNull { it.id == blockId }?.text?.let { text -> com.indagium.diagram3.parseSeq3Note(text) }
                                    ?.attachment?.diagramId)
                                val session = diagramId?.let { id -> state.seq3Sessions.sessions.singleOrNull { it.libraryItemId == id } }
                                    ?: state.seq3Sessions.sessions.singleOrNull { it.confirmedBlockId == blockId }
                                if (session == null) {
                                    state.pendingDiagramNotice = DiagramNotice(
                                        "Couldn't import diagram edits",
                                        "This linked diagram is not open in a workspace.",
                                    )
                                    com.indagium.diagram3.Seq3SourceImportResult.Failure(emptyList())
                                } else {
                                    state.seq3Sessions.importSource(session.id, source, dialect, confirm)
                                }
                            },
                            diagramLibraryItems = state.seq3Sessions.libraryForTab(tab),
                            onCreateDiagram = {
                                state.seq3Sessions.begin(tab.id, tab.selected)
                            },
                            onCreateDiagramFromNotes = { state.seq3Sessions.beginFromNotes(tab.id) },
                            // seq3NotesSelection is a cheap list walk, but keyed on the two inputs it
                            // actually reads so it isn't rerun on every recomposition (e.g. a selection
                            // drag or an unrelated panel resize) — see that function's own doc.
                            notesDiagramSummary = remember(tab.annotations, tab.logData) { seq3NotesSelection(tab) },
                            onOpenDiagramLibraryItem = { id -> state.seq3Sessions.openLibraryItem(id, tab.id) },
                            onDeleteDiagramLibraryItem = { id -> state.seq3Sessions.deleteLibraryItem(id) },
                            width = annotationRenderedWidth,
                            focusRequester = annotationFr,
                            onPanelFocusChanged = { focused ->
                                if (focused) focusedPanelIdx = visiblePanelFrs().indexOfFirst { it.second == annotationFr }
                            },
                            keyboardFocusVisible = state.keyboardFocusVisible,
                            scrollStateStore = state.logViewerScrollStateStore,
                            highlightedBlockId = state.aiEvidenceNoteTarget?.takeIf { it.tabId == tab.id }?.blockId,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                    videoContent = if (state.videoPanelVisible && tab.captureSessionId != null) {
                        { CaptureCard(state = state, tab = tab) }
                    } else if (state.videoPanelVisible && tab.attachedVideo != null) {
                        { BoundVideoPanel(state = state, tab = tab, modifier = Modifier.fillMaxSize()) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}
