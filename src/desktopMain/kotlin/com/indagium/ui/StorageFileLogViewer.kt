package com.indagium.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import com.indagium.model.LogTab
import com.indagium.model.SearchScope
import com.indagium.storage.ProjectionViewportController
import com.indagium.storage.ProjectionViewportState
import com.indagium.storage.SourceId
import com.indagium.storage.StackFileSession
import com.indagium.storage.StorageAnalysis
import com.indagium.storage.StorageSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import java.nio.file.Path

private sealed interface StorageFileViewState {
    data object Loading : StorageFileViewState

    data class Ready(
        val controller: ProjectionViewportController,
        val session: StackFileSession,
        val analysis: StorageAnalysis,
    ) : StorageFileViewState

    data class Failed(val error: Exception) : StorageFileViewState
}

/** Experimental file-view owner; each filter/fold generation owns its own cancellable session. */
@Suppress("TooGenericExceptionCaught") // Surface source failures and release a session on setup failure.
@Composable
internal fun StorageFileLogViewer(
    tab: LogTab,
    path: Path,
    modifier: Modifier = Modifier,
    pageRows: Int = 256,
    onSelect: (Long, Int, Boolean, Boolean, Boolean) -> Unit = { _, _, _, _, _ -> },
    onToggleGroup: (String) -> Unit = {},
    selection: StorageSelection? = null,
    onSelectAll: (() -> Unit)? = null,
    onClearSelection: (() -> Unit)? = null,
    onSessionOpened: (StackFileSession) -> Boolean = { true },
    onSessionClosed: (StackFileSession) -> Unit = {},
    onFirstPageReady: (StackFileSession) -> Unit = {},
    onCopySelection: ((StackFileSession, LogTab) -> Unit)? = null,
    onCopyText: (String) -> Unit = {},
    onExportTxt: () -> Unit = {},
    onExportCsv: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onSearchQueryChange: (SearchScope, String) -> Unit = { _, _ -> },
    onSearchToggleCase: (SearchScope) -> Unit = {},
    onSearchNext: (SearchScope) -> Unit = {},
    onSearchPrev: (SearchScope) -> Unit = {},
    onSearchClose: (SearchScope) -> Unit = {},
    onSearchFocused: (SearchScope) -> Unit = {},
    searchNavigation: SearchNavigationRequest? = null,
    onConsumeSearchNavigation: (Long) -> Unit = {},
    tiles: LogViewerTiles? = null,
) {
    val state by produceState<StorageFileViewState>(
        StorageFileViewState.Loading, tab.id, path, tab.filter, tab.expanded, tab.manualBlocks, tab.logFormat, pageRows,
    ) {
        value = StorageFileViewState.Loading
        try {
            val session = StackFileSession.open(
                SourceId(tab.id), path, tab.filter, tab.logFormat, tab.manualBlocks.any { it.enabled },
                expanded = tab.expanded, pageRows = pageRows,
            )
            val controller = try {
                ProjectionViewportController(session, pageRows)
            } catch (error: Exception) {
                session.close()
                throw error
            }
            try {
                currentCoroutineContext().ensureActive()
                if (!onSessionOpened(session)) throw CancellationException("Storage tab was closed")
                value = StorageFileViewState.Ready(controller, session, session.analysis)
                awaitCancellation()
            } finally {
                onSessionClosed(session)
                controller.close()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            value = StorageFileViewState.Failed(error)
        }
    }

    @Composable
    fun Status(message: String) {
        if (tiles == null) {
            AppText(message, color = tc().tx)
        } else {
            tiles.scope.Tile(tiles.logId, tiles.logSlot { AppText(message, color = tc().tx) })
        }
    }
    when (val view = state) {
        StorageFileViewState.Loading -> Status("Loading ${tab.filename}…")
        is StorageFileViewState.Failed -> Status("Could not open log: ${view.error.message.orEmpty()}")
        is StorageFileViewState.Ready -> {
            LaunchedEffect(view.controller) {
                view.controller.state.first { it is ProjectionViewportState.Ready }
                onFirstPageReady(view.session)
            }
            val analysis = tab.analysis.copy(
                tagCounts = view.analysis.tagCounts, processNames = view.analysis.processNames, tagPids = view.analysis.tagPids,
            )
            val generation = view.session.generation
            val currentSelection = selection?.takeIf {
                it.generation.sourceId == SourceId(tab.id) && it.generation.sourceVersion == generation.sourceVersion &&
                    it.generation.queryId == generation.queryId && it.generation.projectionId == generation.projectionId
            }
            LogViewer(
                tab = tab.copy(analysis = analysis), modifier = modifier.fillMaxSize(), onSelRow = { id, multi, range ->
                    onSelect(0, id, multi, range, false)
                },
                onCtxMenu = { _, _, _, _, _, _ -> }, onToggleGroup = onToggleGroup, onClearFilter = {},
                onExpandAll = {}, onCollapseAll = {}, onToggleUnfiltered = {}, onExportTxt = onExportTxt, onExportCsv = onExportCsv,
                onSelectAll = onSelectAll, onClearSelection = onClearSelection,
                onCopySelection = onCopySelection?.let { copy -> { _ -> copy(view.session, tab) } }, onCopyText = onCopyText,
                onOpenSearch = onOpenSearch, onSearchQueryChange = onSearchQueryChange, onSearchToggleCase = onSearchToggleCase,
                onSearchNext = onSearchNext, onSearchPrev = onSearchPrev, onSearchClose = onSearchClose,
                onSearchFocused = onSearchFocused, searchNavigationRequest = searchNavigation,
                onConsumeSearchNavigation = onConsumeSearchNavigation,
                tiles = tiles, storageViewport = view.controller, storageSession = view.session,
                storageSelection = currentSelection, storageOnSelect = onSelect,
            )
        }
    }
}
