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
import com.indagium.capture.RecorderSnapshot
import com.indagium.capture.RecorderState
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
    // The width this panel actually renders at: min(state.filterPanelWidth, the window-relative
    // guard). The stored value is never rewritten by the guard, same as the annotation sidebar.
    width: Float,
    // The window-relative max RIGHT NOW, re-evaluated on every call. The divider drag below calls
    // this instead of closing over a value computed at the last recomposition — see the comment
    // on the divider callback.
    liveMaxWidth: () -> Float,
    focusRequester: FocusRequester? = null,
    filterBarVisible: Boolean = false,
    filterSearchRequest: FilterSearchRequest? = null,
    onFilterSearchRequestConsumed: (FilterSearchRequest) -> Unit = {},
    onPanelFocusChanged: (Boolean) -> Unit = {},
    captureDeviceEvents: CaptureDeviceEventsModel? = null,
    onCaptureDeviceEventReturnFocus: () -> Unit = {},
    host: SectionHost? = null,
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
        width = width,
        focusRequester = focusRequester,
        filterBarVisible = filterBarVisible,
        filterSearchRequest = filterSearchRequest,
        onFilterSearchRequestConsumed = onFilterSearchRequestConsumed,
        onPanelFocusChanged = onPanelFocusChanged,
        keyboardFocusVisible = state.keyboardFocusVisible,
        captureDeviceEvents = captureDeviceEvents,
        captureDeviceEventsState = state,
        onCaptureDeviceEventReturnFocus = onCaptureDeviceEventReturnFocus,
        host = host,
    )
    // Tiled, the splitters between tiles replace this panel's own right-edge divider and its width guard.
    if (host != null && !host.hostsInColumn) return
    HDivider { delta ->
        // Recompute live instead of closing over `width`/`liveMaxWidth()` as of the last
        // recomposition: the drag gesture can deliver several deltas before recomposition catches
        // up, and state.filterPanelWidth already reflects each prior delta (mutableStateOf writes
        // are synchronous). Starting from the stale rendered width would land every delta on the
        // pre-drag base — the jitter/snap-back FileView's sidebar divider documents.
        val liveMax = liveMaxWidth()
        val liveRendered = minOf(state.filterPanelWidth, liveMax)
        state.updateFilterPanelWidth((liveRendered + delta).coerceAtMost(liveMax))
    }
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
    // A ZIP opened as a log view is an immutable archive surface even if its source happens to
    // still exist in the retained-session store. Keep the prominent stopped-capture strip for a
    // finalized local recording, but route archive cuts through the ordinary Export menu.
    // ".ind" is the Compact extension; ".ind.zip" is a legacy Compact name from an earlier build and matches ".zip".
    val archiveBackedCapture = state.isImportedCaptureSnapshot(tab.id) ||
        tab.captureArchivePath?.let { path ->
            path.endsWith(".zip", ignoreCase = true) || path.endsWith(".ind", ignoreCase = true)
        } == true
    val captureDeviceEvents = rememberCaptureDeviceEvents(state, tab)
    var importedRangeEditorOpen by remember(tab.id) { mutableStateOf(false) }

    // The F6 / panel-focus stops for the tiles that are showing, in the layout's reading order. Original and Log are one stop (the log
    // viewer has a single requester), as are all the filter tiles; Video and the Diagram library have no focus target of their own.
    fun visiblePanelFrs(): List<Pair<KeyboardPanel, FocusRequester>> = buildList {
        state.logViewVisiblePanels(tab).forEach { panel ->
            when (panel) {
                LogViewFocusPanel.FILTERS -> add(KeyboardPanel.FILTERS to filterFr)
                LogViewFocusPanel.ORIGINAL, LogViewFocusPanel.LOG -> if (none { it.second == logViewerFr }) add(KeyboardPanel.LOG_VIEW to logViewerFr)
                LogViewFocusPanel.NOTES -> add(KeyboardPanel.NOTES to annotationFr)
                LogViewFocusPanel.AI -> add(KeyboardPanel.AI to aiFr)
                else -> Unit
            }
        }
    }

    LaunchedEffect(requestedPanelFocus, state.filterVisible, state.annotationVisible, state.aiPanelVisible, state.settings.logViewHidden, tab.id) {
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
            state.captureFinalizationStatus(tab.id) != null ||
            (tab.captureSourceSessionId != null && !archiveBackedCapture)
        ) {
            CaptureStrip(
                state = state,
                tab = tab,
                onReturnFocus = { runCatching { logViewerFr.requestFocus() } },
                deviceEvents = captureDeviceEvents,
            )
        }
        // The panels (filters, log, Notes, AI, Video) are tiles of one tiling surface; see LogViewTiling.kt. The horizontal filter bar
        // (ui/FilterBar.kt) is part of the log tile's toolbar and independently gated by filterBarVisible.
        LogViewTilingHost(
            state = state,
            tab = tab,
            focus = LogViewPanelFocus(
                filters = filterFr,
                log = logViewerFr,
                notes = annotationFr,
                ai = aiFr,
                onPanelFocused = { fr -> focusedPanelIdx = visiblePanelFrs().indexOfFirst { it.second == fr } },
                returnToLog = { runCatching { logViewerFr.requestFocus() } },
            ),
            inputs = LogViewHostInputs(
                filterSearchRequest = filterSearchRequest,
                onFilterSearchRequestConsumed = onFilterSearchRequestConsumed,
                captureDeviceEvents = captureDeviceEvents,
                onExportCaptureRange = if (archiveBackedCapture) {
                    { importedRangeEditorOpen = true }
                } else {
                    null
                },
            ),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        val archiveSession = if (archiveBackedCapture) state.captureSnapshotSessionFor(tab.id) else null
        if (importedRangeEditorOpen && archiveSession != null) {
            CaptureSnapshotPopover(
                state = state,
                tab = tab,
                snapshot = RecorderSnapshot(
                    state = RecorderState.STOPPED,
                    session = archiveSession,
                    logBytes = archiveSession.logFile.length(),
                    indexedRows = tab.logData.size,
                ),
                triggerBounds = null,
                editorTitle = "Export capture range",
                onDismiss = {
                    importedRangeEditorOpen = false
                    runCatching { logViewerFr.requestFocus() }
                },
                onReturnFocus = { runCatching { logViewerFr.requestFocus() } },
            )
        }
        if (archiveBackedCapture && !importedRangeEditorOpen) {
            CaptureExportStatusLine(
                state = state,
                sessionId = archiveSession?.id ?: tab.captureSourceSessionId,
                onReturnFocus = { runCatching { logViewerFr.requestFocus() } },
            )
        }
    }
}
