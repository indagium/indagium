package com.indagium.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.indagium.model.CtxMenuState
import com.indagium.model.LogTab
import com.indagium.model.SearchScope

/**
 * The log viewer wired to [state] for [tab]: the horizontal filter bar's model and actions, and every LogViewer callback. Pulled out of
 * FileView so the tiled log view and the legacy single-column call bind the log the same way. [tiles] non-null emits the Original and
 * Log tiles into the enclosing tiling surface (see LogViewerTiles.kt); null is today's single Column filling [modifier].
 * [onPanelFocusChanged] reports focus entering the log panel; [onExportCaptureRange] is the toolbar's "Export capture range" (null hides it).
 * [compare] binds the log as one side of the compare view: no horizontal filter bar and no Find scope chip (compare searches each tab's
 * Filtered slot only); [tab] is then the tab as displayed (its filter replaced, see compareDisplayedFilters), its id the real tab's.
 */
@Composable
internal fun BoundLogViewer(
    state: AppState,
    tab: LogTab,
    focusRequester: FocusRequester,
    onPanelFocusChanged: (Boolean) -> Unit,
    onExportCaptureRange: (() -> Unit)?,
    modifier: Modifier = Modifier,
    tiles: LogViewerTiles? = null,
    compare: Boolean = false,
) {
    val filterBarSortedTags = remember(tab.id, tab.analysis.tagCounts) {
        tab.analysis.tagCounts.entries.sortedByDescending { it.value }.map { it.key }
    }
    val filterBarShown = state.filterBarVisible && !compare
    val filterBarModel = if (filterBarShown) {
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
            onOpenFilterPanel = { state.openFilterPanelRevealing(tab.id) },
        )
    }
    LogViewer(
        tab = tab, modifier = modifier,
        settings = state.settings,
        onSelRow = { id, multi, range -> state.selRow(tab.id, id, multi, range) },
        onSelRowRange = { ids -> state.setSelectedRows(tab.id, ids) },
        onCtxMenu = { id, x, y, sel, panelSel, capture ->
            state.ctx = CtxMenuState(tab.id, id, x, y, sel, panelSel, annotationCapture = capture)
        },
        onToggleGroup = { state.toggleGroup(tab.id, it) },
        onClearFilter = { state.requestClearFilter(tab.id) },
        onExpandAll = { state.expandAll(tab.id) },
        onCollapseAll = { state.collapseAll(tab.id) },
        onToggleUnfiltered = { state.toggleUnfiltered(tab.id) },
        onToggleTimeDelta = { state.toggleTimeDelta(tab.id) },
        onOpenSearch = { if (state.isSearchOpen(tab.id)) state.closeSearch(tab.id) else state.openSearch(tab.id) },
        onToggleRowNumbers = { state.updateSettings { it.copy(showRowNumbers = !it.showRowNumbers) } },
        onToggleMinimap = { state.updateSettings { it.copy(showMinimap = !it.showMinimap) } },
        onSetProcessNameMode = { mode -> state.setProcessNameMode(tab.id, mode) },
        onSetTidMapHighlight = { colorKey -> state.setTidMapHighlight(tab.id, colorKey) },
        onExportTxt = { state.exportFilteredTxt(tab.id) },
        onExportCsv = { state.exportFilteredCsv(tab.id) },
        scrollStateStore = state.logViewerScrollStateStore,
        annotationNavigationRequest = state.pendingAnnotationNavigation,
        onConsumeAnnotationNavigation = { state.consumeAnnotationNavigation(it) },
        searchNavigationRequests = SearchScope.entries.mapNotNull { scope ->
            state.searchNavigationFor(tab.id, scope)?.let { scope to it }
        }.toMap(),
        onConsumeSearchNavigation = { state.consumeSearchNavigation(it) },
        onSelectAll = { state.selectAll(tab.id) },
        onClearSelection = { state.clearSelection(tab.id) },
        onCopySelection = { selectedIds -> state.copySelectedLines(tab.id, selectedIds) },
        onAddAnnotation = { capture -> state.requestAddAnn(tab.id, capture) },
        onCopyText = { text -> state.copyToClipboard(text) },
        onLogRowDoubleClick = { id -> state.seekVideoToLogRow(tab.id, id) },
        onLogRowDoubleClickGestureStarted = { state.beginVideoLogDoubleClickGesture(tab.id) },
        onLogRowDoubleClickGestureExpired = { state.endVideoLogDoubleClickGesture(tab.id) },
        navScrollMargin = state.settings.navScrollMargin,
        focusRequester = focusRequester,
        onPanelFocusChanged = { focused ->
            if (focused) state.searchFocusTabId = tab.id
            onPanelFocusChanged(focused)
        },
        keyboardFocusVisible = state.keyboardFocusVisible,
        onVisibleItems = { summary -> state.noteVisibleItems(tab.id, summary) },
        onHoverPanelKey = { key -> state.hoveredLogPanelKey = key },
        onSearchQueryChange = { scope, query -> state.setSearchQuery(tab.id, query, scope) },
        onSearchToggleCase = { scope -> state.toggleSearchCase(tab.id, scope) },
        onSearchNext = { scope -> state.searchNext(tab.id, scope) },
        onSearchPrev = { scope -> state.searchPrev(tab.id, scope) },
        onSearchClose = { scope -> state.closeSearch(tab.id, scope) },
        showSearchScopeChip = !compare,
        onSearchToggleScope = { mode -> state.setSearchMode(tab.id, mode) },
        onToggleSearchSync = {
            state.updateSettings {
                it.copy(syncSplitPanelSelectionAndScroll = !it.syncSplitPanelSelectionAndScroll)
            }
        },
        onSearchFocused = { scope -> state.focusSearch(tab.id, scope) },
        onSearchAllLines = { state.searchAllLines(tab.id) },
        syncSelectionAndScroll = state.settings.syncSplitPanelSelectionAndScroll,
        syncSelectionAndScrollRevision = state.splitSyncRevision,
        filterBar = filterBarModel,
        filterBarActions = filterBarActions,
        filterBarVisible = if (compare) null else state.filterBarVisible,
        onToggleFilterBar = if (compare) null else ({ state.updateFilterBarVisible(!state.filterBarVisible) }),
        onExportCaptureRange = onExportCaptureRange,
        tiles = tiles,
    )
}
