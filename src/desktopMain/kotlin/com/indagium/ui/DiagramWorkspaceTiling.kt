package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.indagium.model.TileAxis
import com.indagium.model.TileLeaf
import com.indagium.model.TileNode
import com.indagium.model.TileSplit

/** Stable ids stored with each diagram session's view state. */
internal enum class DiagramWorkspaceSectionId(val key: String) {
    MESSAGES("messages"),
    LIFELINES("lifelines"),
    ARTIFACTS("artifacts"),
    PREVIEW("preview"),
    ;

    companion object {
        fun fromKey(key: String?): DiagramWorkspaceSectionId? = entries.firstOrNull { it.key == key }
    }
}

internal typealias DiagramWorkspaceLayout = TileNode<DiagramWorkspaceSectionId>

private const val DIAGRAM_MESSAGES_TILE_WEIGHT = 1.6f
private const val DIAGRAM_NARROW_PREVIEW_WEIGHT = 1.5f

/** A preset changes the saved tree and weight distribution; panels keep their place when hidden. */
internal enum class DiagramWorkspacePreset(
    val label: String,
    val layout: DiagramWorkspaceLayout,
    val collapsed: Set<DiagramWorkspaceSectionId>,
) {
    STANDARD(
        "Standard",
        diagramWorkspaceLayout(sideWeight = 0.9f, previewWeight = 2.2f),
        setOf(DiagramWorkspaceSectionId.ARTIFACTS),
    ),
    CANVAS_FOCUS(
        "Canvas focus",
        diagramWorkspaceLayout(sideWeight = 0.65f, previewWeight = 3f),
        emptySet(),
    ),
}

internal fun diagramWorkspaceLayout(sideWeight: Float = 0.9f, previewWeight: Float = 2.2f): DiagramWorkspaceLayout =
    TileSplit(
        TileAxis.ROW,
        listOf(
            TileSplit(
                TileAxis.COLUMN,
                listOf(
                    TileLeaf(DiagramWorkspaceSectionId.MESSAGES, DIAGRAM_MESSAGES_TILE_WEIGHT),
                    TileLeaf(DiagramWorkspaceSectionId.LIFELINES, 1f),
                    TileLeaf(DiagramWorkspaceSectionId.ARTIFACTS, 1f),
                ),
                sideWeight,
            ),
            TileLeaf(DiagramWorkspaceSectionId.PREVIEW, previewWeight),
        ),
    )

internal fun diagramWorkspaceDefaultLayout(): DiagramWorkspaceLayout = DiagramWorkspacePreset.STANDARD.layout

/** Reorders the flattened narrow layout so the preview remains the first card on a small window. */
internal fun diagramWorkspaceNarrowLayout(layout: DiagramWorkspaceLayout): DiagramWorkspaceLayout {
    val leaves = tileLeafSections(layout)
    val preview = leaves.firstOrNull { it == DiagramWorkspaceSectionId.PREVIEW } ?: return layout
    val side = leaves.filterNot { it == preview }
    return TileSplit(TileAxis.COLUMN, listOf(TileLeaf(preview, DIAGRAM_NARROW_PREVIEW_WEIGHT)) + side.map { TileLeaf(it) })
}

internal data class DiagramWorkspaceLayoutState(
    val layout: DiagramWorkspaceLayout,
    val hidden: List<DiagramWorkspaceSectionId>,
    val collapsed: List<DiagramWorkspaceSectionId>,
)

/** Keeps every stable tile exactly once and guarantees that at least the preview stays visible. */
internal fun sanitizeDiagramWorkspaceLayoutState(
    layout: DiagramWorkspaceLayout?,
    hidden: List<DiagramWorkspaceSectionId>,
    collapsed: List<DiagramWorkspaceSectionId>,
): DiagramWorkspaceLayoutState {
    val seen = mutableSetOf<DiagramWorkspaceSectionId>()

    fun unique(node: TileNode<DiagramWorkspaceSectionId>): TileNode<DiagramWorkspaceSectionId>? = when (node) {
        is TileLeaf -> if (seen.add(node.section)) node else null
        is TileSplit -> {
            val children = node.children.mapNotNull(::unique)
            when (children.size) {
                0 -> null
                1 -> children.single()
                else -> node.copy(children = children)
            }
        }
    }
    val tree = unique(layout ?: diagramWorkspaceDefaultLayout())
    val missing = DiagramWorkspaceSectionId.entries.filter { it !in seen }
    val completed = missing.fold(tree ?: diagramWorkspaceDefaultLayout()) { acc, id -> appendTile(acc, id) }
    val validHidden = hidden.distinct().filter { it in DiagramWorkspaceSectionId.entries }
    val cleanHidden = if (validHidden.size == DiagramWorkspaceSectionId.entries.size) {
        validHidden - DiagramWorkspaceSectionId.PREVIEW
    } else {
        validHidden
    }
    val cleanCollapsed = collapsed.distinct().filter { it in DiagramWorkspaceSectionId.entries }
    return DiagramWorkspaceLayoutState(completed, cleanHidden, cleanCollapsed)
}

internal fun diagramWorkspacePresetActive(view: Seq3ViewState, preset: DiagramWorkspacePreset): Boolean =
    view.diagramTileLayout == preset.layout && view.diagramHiddenTiles.isEmpty() && view.diagramCollapsedTiles.toSet() == preset.collapsed

internal fun applyDiagramWorkspacePreset(view: Seq3ViewState, preset: DiagramWorkspacePreset) {
    view.diagramTileLayout = preset.layout
    view.diagramHiddenTiles = emptyList()
    view.diagramCollapsedTiles = preset.collapsed.toList()
    setLegacyDiagramPaneOpen(view, DiagramWorkspaceSectionId.MESSAGES, true)
    setLegacyDiagramPaneOpen(view, DiagramWorkspaceSectionId.LIFELINES, true)
    setLegacyDiagramPaneOpen(view, DiagramWorkspaceSectionId.ARTIFACTS, true)
    DiagramWorkspaceSectionId.entries.forEach { id -> setLegacyDiagramTileExpanded(view, id, id !in preset.collapsed) }
    view.diagramLastPreset = preset
}

internal fun diagramWorkspaceVisibleTiles(view: Seq3ViewState): Set<DiagramWorkspaceSectionId> =
    tileLeafSections(view.diagramTileLayout).filterNot { it in view.diagramHiddenTiles }.toSet()

internal fun setDiagramWorkspaceTileVisible(view: Seq3ViewState, id: DiagramWorkspaceSectionId, visible: Boolean): Boolean {
    val currentlyVisible = diagramWorkspaceVisibleTiles(view)
    if (visible) {
        if (id in currentlyVisible) return false
        view.diagramHiddenTiles = view.diagramHiddenTiles - id
        setLegacyDiagramPaneOpen(view, id, true)
        return true
    }
    if (id !in currentlyVisible || currentlyVisible.size <= 1) return false
    view.diagramHiddenTiles = (view.diagramHiddenTiles + id).distinct()
    setLegacyDiagramPaneOpen(view, id, false)
    return true
}

private const val DIAGRAM_HOST_PAD_DP = 6
private const val DIAGRAM_STACK_BELOW_DP = 760f
private const val DIAGRAM_STACKED_HEIGHT_SHARE = 0.72f
private const val DIAGRAM_STACKED_MIN_HEIGHT_DP = 260f
private const val DIAGRAM_STACKED_MAX_HEIGHT_DP = 520f

internal val DIAGRAM_WORKSPACE_CHROME_SPEC = TileChromeSpec<DiagramWorkspaceSectionId>(
    headerDp = LOG_VIEW_TILE_HEADER_DP,
    titleSp = 12f,
    gapRowDp = LOG_VIEW_GAP_ROW_DP,
    gapColumnDp = LOG_VIEW_GAP_COLUMN_DP,
    cornerDp = 8,
    minTileDp = 160f,
    minPairShare = 0.1f,
    tagPrefix = "diagram-tile",
    keyOf = { it.key },
    scrollMinDp = 96f,
    scrollMaxDp = 2000f,
    scrolls = { it != DiagramWorkspaceSectionId.PREVIEW },
    loneColumn = { it != DiagramWorkspaceSectionId.PREVIEW },
    scrollEdgeRoomDp = DIAGRAM_HOST_PAD_DP.toFloat(),
    scrollEndHandle = true,
    scrollEndHandleOuterRoomDp = DIAGRAM_HOST_PAD_DP.toFloat(),
)

internal fun diagramWorkspaceTitle(id: DiagramWorkspaceSectionId): String = when (id) {
    DiagramWorkspaceSectionId.MESSAGES -> "Messages"
    DiagramWorkspaceSectionId.LIFELINES -> "Lifelines"
    DiagramWorkspaceSectionId.ARTIFACTS -> "Artifacts"
    DiagramWorkspaceSectionId.PREVIEW -> "Diagram preview"
}

private fun diagramWorkspaceSlot(
    id: DiagramWorkspaceSectionId,
    session: Seq3WorkspaceSession,
    canHide: Boolean,
    headerAction: (@Composable RowScope.() -> Unit)?,
    body: @Composable () -> Unit,
): TileSlot {
    val summary = when (id) {
        DiagramWorkspaceSectionId.MESSAGES -> session.document.messages.size
        DiagramWorkspaceSectionId.LIFELINES -> session.document.lifelines.size
        DiagramWorkspaceSectionId.ARTIFACTS -> session.document.fragments.size + session.document.notes.size +
            session.document.delays.size + session.document.manualActivations.size
        DiagramWorkspaceSectionId.PREVIEW -> null
    }
    return TileSlot(
        icon = when (id) {
            DiagramWorkspaceSectionId.MESSAGES -> Icons.Outlined.Description
            DiagramWorkspaceSectionId.LIFELINES -> Icons.Outlined.Groups
            DiagramWorkspaceSectionId.ARTIFACTS -> Icons.AutoMirrored.Outlined.StickyNote2
            DiagramWorkspaceSectionId.PREVIEW -> Icons.Outlined.AccountTree
        },
        title = diagramWorkspaceTitle(id),
        summary = summary?.toString(),
        collapsible = true,
        hideable = true,
        canHide = canHide,
        maximizable = true,
        sizing = TileBodySizing.FILL,
        headerAction = headerAction,
        body = { _ -> body() },
    )
}

/**
 * Diagram equivalent of [LogViewTilingHost]: the saved view tree is the actual layout source for four live tiles, not a set of
 * decorative cards around the old fixed sidebar. Narrow windows get a scrollable stack with Preview first; wide windows retain the
 * familiar left-side authoring panes and right-side canvas.
 */
@Composable
internal fun DiagramWorkspaceTilingHost(
    state: AppState,
    session: Seq3WorkspaceSession,
    view: Seq3ViewState,
    onReclaimFocus: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable TileScope<DiagramWorkspaceSectionId>.(DiagramWorkspaceSectionId) -> Unit,
) {
    val tc = tc()
    val density = LocalDensity.current.density
    var sizePx by remember(session.id) { mutableStateOf(IntSize.Zero) }
    val layoutState = sanitizeDiagramWorkspaceLayoutState(view.diagramTileLayout, view.diagramHiddenTiles, view.diagramCollapsedTiles)
    val visible = layoutState.layout.let(::tileLeafSections).filterNot { it in layoutState.hidden }.toSet()
    val callbacks = TilingCallbacks<DiagramWorkspaceSectionId>(
        onCommitLayout = { tree ->
            view.diagramTileLayout = sanitizeDiagramWorkspaceLayoutState(tree, view.diagramHiddenTiles, view.diagramCollapsedTiles).layout
            view.diagramLastPreset = DiagramWorkspacePreset.entries.firstOrNull { it.layout == tree } ?: view.diagramLastPreset
            state.autosaveInBackground()
        },
        onHide = { id ->
            if (setDiagramWorkspaceTileVisible(view, id, false)) {
                state.autosaveInBackground()
            }
        },
        onToggleCollapse = { id ->
            val collapsed = id !in view.diagramCollapsedTiles
            view.diagramCollapsedTiles = if (collapsed) view.diagramCollapsedTiles + id else view.diagramCollapsedTiles - id
            setLegacyDiagramTileExpanded(view, id, !collapsed)
            state.autosaveInBackground()
        },
        onMaximize = { view.maximizedDiagramTile = it },
        onReclaimFocus = onReclaimFocus,
        dropLabel = { diagramWorkspaceDropLabel(it) },
    )

    Box(
        modifier.fillMaxSize().background(tc.bg),
    ) {
        // Same host padding / right scrollbar gutter / bottom resize gutter as the log view. Measure with onSizeChanged because
        // Popup-based editors inside the tiles must stay under the same composition hierarchy.
        Box(
            Modifier.fillMaxSize()
                .padding(start = DIAGRAM_HOST_PAD_DP.dp, top = DIAGRAM_HOST_PAD_DP.dp)
                .onSizeChanged { sizePx = it }
                .testTag("diagram-tiling-host"),
        ) {
            val widthDp = sizePx.width / density
            val heightDp = (sizePx.height / density - DIAGRAM_HOST_PAD_DP).coerceAtLeast(0f)
            if (sizePx.width > 0 && sizePx.height > 0) {
                val emitTiles: @Composable TileScope<DiagramWorkspaceSectionId>.() -> Unit = {
                    DiagramWorkspaceSectionId.entries.forEach { id ->
                        val headerAction: (@Composable RowScope.() -> Unit)? = when (id) {
                            DiagramWorkspaceSectionId.MESSAGES -> ({ Seq3QueueHeaderActions(state, session, view) })
                            DiagramWorkspaceSectionId.LIFELINES -> ({ Seq3LifelinesHeaderActions(state, session, view) })
                            DiagramWorkspaceSectionId.ARTIFACTS -> ({ Seq3ArtifactsHeaderActions(state, session, view) })
                            DiagramWorkspaceSectionId.PREVIEW -> null
                        }
                        val slot = diagramWorkspaceSlot(id, session, visible.size > 1, headerAction) { content(id) }
                        Tile(id, slot)
                    }
                }
                if (widthDp < DIAGRAM_STACK_BELOW_DP) {
                    TileStackedSurface(
                        fullLayout = diagramWorkspaceNarrowLayout(layoutState.layout),
                        isVisible = { it in visible },
                        parked = emptySet(),
                        cardHeightDp = (heightDp * DIAGRAM_STACKED_HEIGHT_SHARE)
                            .coerceIn(DIAGRAM_STACKED_MIN_HEIGHT_DP, DIAGRAM_STACKED_MAX_HEIGHT_DP),
                        spec = DIAGRAM_WORKSPACE_CHROME_SPEC,
                        callbacks = callbacks,
                        heightOf = { id ->
                            if (id == DiagramWorkspaceSectionId.PREVIEW) {
                                (heightDp * 0.78f).coerceIn(DIAGRAM_STACKED_MIN_HEIGHT_DP, DIAGRAM_STACKED_MAX_HEIGHT_DP)
                            } else {
                                null
                            }
                        },
                        modifier = Modifier.padding(end = DIAGRAM_HOST_PAD_DP.dp, bottom = DIAGRAM_HOST_PAD_DP.dp),
                        content = emitTiles,
                    )
                } else {
                    TilingSurface(
                        fullLayout = layoutState.layout,
                        isVisible = { it in visible },
                        collapsed = layoutState.collapsed.toSet(),
                        parked = emptySet(),
                        maximized = view.maximizedDiagramTile,
                        locked = view.diagramTilesLocked,
                        widthDp = widthDp,
                        heightDp = heightDp,
                        spec = DIAGRAM_WORKSPACE_CHROME_SPEC,
                        callbacks = callbacks,
                        content = emitTiles,
                    )
                }
            }
        }
    }
}

internal fun setLegacyDiagramPaneOpen(view: Seq3ViewState, id: DiagramWorkspaceSectionId, open: Boolean) {
    when (id) {
        DiagramWorkspaceSectionId.MESSAGES -> view.messagesSectionOpen = open
        DiagramWorkspaceSectionId.LIFELINES -> view.lifelinesSectionOpen = open
        DiagramWorkspaceSectionId.ARTIFACTS -> view.artifactsSectionOpen = open
        DiagramWorkspaceSectionId.PREVIEW -> Unit
    }
}

internal fun setLegacyDiagramTileExpanded(view: Seq3ViewState, id: DiagramWorkspaceSectionId, expanded: Boolean) {
    when (id) {
        DiagramWorkspaceSectionId.MESSAGES -> view.messagesExpanded = expanded
        DiagramWorkspaceSectionId.LIFELINES -> view.lifelinesExpanded = expanded
        DiagramWorkspaceSectionId.ARTIFACTS -> view.artifactsExpanded = expanded
        DiagramWorkspaceSectionId.PREVIEW -> Unit
    }
}

private fun diagramWorkspaceDropLabel(target: TileDropTarget<DiagramWorkspaceSectionId>): String = when (target) {
    is TileDropTarget.OnSection -> "Move ${diagramWorkspaceTitle(target.section)} ${target.edge.name.lowercase()}"
    is TileDropTarget.OnPage -> "Move tile to ${target.edge.name.lowercase()} edge"
}
