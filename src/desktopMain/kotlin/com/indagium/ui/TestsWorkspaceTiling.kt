@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.TileAxis
import com.indagium.model.TileNode

/** Compact workspaces keep navigation in its own column; the narrow overlay uses the same four contextual tiles. */
internal val LocalTestsCompactWorkspace = staticCompositionLocalOf { false }
internal val LocalTestsNavigationOverlay = staticCompositionLocalOf { false }

internal const val TESTS_WORKSPACE_COMPACT_NAV_BELOW_DP = 1050f
private const val TESTS_WORKSPACE_STACKED_MIN_DP = 300f
private const val TESTS_WORKSPACE_STACKED_MAX_DP = 560f
private const val TESTS_WORKSPACE_STACKED_HEIGHT_SHARE = 0.64f

/**
 * Adapts each existing Tests screen to one persistent seven-tile surface. Screen content and selection inspectors are each
 * emitted once; the optional workspace header stays fixed while its body scrolls.
 */
@Composable
internal fun TestsWorkspaceTilingHost(
    main: @Composable () -> Unit,
    header: (@Composable RowScope.() -> Unit)?,
    inspector: (@Composable () -> Unit)?,
    evidence: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val ui = LocalTestsUi.current
    val saved = ui.state.settings.currentTestsWorkspaceLayout()
    val density = LocalDensity.current.density
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier.fillMaxSize().background(tc().bg).testTag("tests-tiling-host"),
    ) {
        Box(
            Modifier.fillMaxSize()
                .padding(start = LOG_VIEW_HOST_PAD_DP.dp, top = LOG_VIEW_HOST_PAD_DP.dp)
                .onSizeChanged { sizePx = it },
        ) {
            if (sizePx.width > 0 && sizePx.height > 0) {
                val widthDp = sizePx.width / density
                val heightDp = (sizePx.height / density - LOG_VIEW_HOST_PAD_DP).coerceAtLeast(0f)
                val compact = LocalTestsCompactWorkspace.current || widthDp < TESTS_WORKSPACE_COMPACT_NAV_BELOW_DP
                val navigationOverlay = LocalTestsNavigationOverlay.current
                val layout = if (compact && !navigationOverlay) testsWorkspaceCompactLayout(saved.layout) else saved.layout
                val isVisible: (TestsWorkspaceTileId) -> Boolean = { id ->
                    id !in saved.hidden && (!navigationOverlay || id !in TESTS_WORKSPACE_NAV_TILES)
                }
                val projection = projectTileLayout(layout, isVisible)
                val collapsible = projection?.let { columnTiles(it.root) }.orEmpty()
                val collapsed = saved.collapsed.filterTo(HashSet()) { it in collapsible }
                val callbacks = TilingCallbacks(
                    onCommitLayout = { changedLayout -> commitTestsWorkspaceLayout(ui, saved.copy(layout = changedLayout)) },
                    onHide = { id -> commitTestsWorkspaceLayout(ui, saved.withTileVisible(id, visible = false)) },
                    onToggleCollapse = { id -> commitTestsWorkspaceLayout(ui, saved.withTileCollapsed(id, id !in collapsed)) },
                    onMaximize = { ui.view.maximizedTile = it },
                    onReclaimFocus = ui::reclaimFocus,
                    dropLabel = ::testsWorkspaceDropLabel,
                    defaultDp = ::testsWorkspaceDefaultDp,
                )
                val content: @Composable TileScope<TestsWorkspaceTileId>.() -> Unit = {
                    TestsWorkspaceTiles(
                        ui = ui,
                        collapsible = collapsible,
                        main = main,
                        header = header,
                        inspector = inspector,
                        evidence = evidence,
                    )
                }
                CompositionLocalProvider(LocalInTile provides false) {
                    if (navigationOverlay) {
                        TileStackedSurface(
                            fullLayout = layout,
                            isVisible = isVisible,
                            parked = emptySet(),
                            cardHeightDp = (heightDp * TESTS_WORKSPACE_STACKED_HEIGHT_SHARE)
                                .coerceIn(TESTS_WORKSPACE_STACKED_MIN_DP, TESTS_WORKSPACE_STACKED_MAX_DP),
                            spec = TESTS_WORKSPACE_CHROME_SPEC,
                            callbacks = callbacks,
                            modifier = Modifier.padding(end = LOG_VIEW_HOST_PAD_DP.dp, bottom = LOG_VIEW_HOST_PAD_DP.dp),
                            content = content,
                        )
                    } else {
                        TilingSurface(
                            fullLayout = layout,
                            isVisible = isVisible,
                            collapsed = collapsed,
                            parked = emptySet(),
                            maximized = ui.view.maximizedTile,
                            locked = saved.locked,
                            widthDp = widthDp,
                            heightDp = heightDp,
                            spec = TESTS_WORKSPACE_CHROME_SPEC,
                            callbacks = callbacks,
                            content = content,
                        )
                    }
                }
            }
        }
    }
}

private fun commitTestsWorkspaceLayout(ui: TestsUi, layout: TestsWorkspaceLayoutState) {
    val sanitized = sanitizeTestsWorkspaceLayoutState(layout.layout, layout.hidden, layout.collapsed, layout.locked)
    if (ui.state.settings.currentTestsWorkspaceLayout() != sanitized) {
        ui.state.updateSettings { it.withTestsWorkspaceLayout(sanitized) }
    }
    ui.view.maximizedTile = ui.view.maximizedTile?.takeIf { it !in sanitized.hidden }
}

private fun testsWorkspaceDefaultDp(node: TileNode<TestsWorkspaceTileId>): Float? = when {
    tileLeafSections(node) == listOf(TestsWorkspaceTileId.SUITES, TestsWorkspaceTileId.LIBRARY, TestsWorkspaceTileId.ACTIVITY) ->
        TESTS_WORKSPACE_STANDARD_NAV_WIDTH_DP
    tileLeafSections(node) == listOf(TestsWorkspaceTileId.DETAILS, TestsWorkspaceTileId.EVIDENCE) ->
        TESTS_WORKSPACE_STANDARD_DETAILS_WIDTH_DP
    else -> null
}

private fun testsWorkspaceDropLabel(target: TileDropTarget<TestsWorkspaceTileId>): String = when (target) {
    is TileDropTarget.OnPage -> if (target.edge.axis == TileAxis.ROW) "Full height" else "Full width"
    is TileDropTarget.OnSection -> {
        val title = testsWorkspaceTileTitle(target.section)
        when (target.edge) {
            TileEdge.LEFT -> "Left of $title"
            TileEdge.RIGHT -> "Right of $title"
            TileEdge.TOP -> "Above $title"
            TileEdge.BOTTOM -> "Below $title"
        }
    }
}

@Composable
private fun TileScope<TestsWorkspaceTileId>.TestsWorkspaceTiles(
    ui: TestsUi,
    collapsible: Set<TestsWorkspaceTileId>,
    main: @Composable () -> Unit,
    header: (@Composable RowScope.() -> Unit)?,
    inspector: (@Composable () -> Unit)?,
    evidence: (@Composable () -> Unit)?,
) {
    Tile(
        TestsWorkspaceTileId.SUITES,
        TileSlot(
            icon = Icons.Outlined.FolderOpen,
            title = testsWorkspaceTileTitle(TestsWorkspaceTileId.SUITES),
            summary = ui.library.suites.size.toString(),
            headerAction = { TestsSuitesTileHeaderAction() },
            collapsible = TestsWorkspaceTileId.SUITES in collapsible,
            canHide = testsWorkspaceCanHide(ui, TestsWorkspaceTileId.SUITES),
            maximizable = false,
            sizing = TileBodySizing.FILL,
            body = { TestsSuitesTileContent() },
        ),
    )
    Tile(
        TestsWorkspaceTileId.LIBRARY,
        TileSlot(
            icon = Icons.Outlined.Description,
            title = testsWorkspaceTileTitle(TestsWorkspaceTileId.LIBRARY),
            summary = "${ui.library.sharedSteps.size + ui.library.scripts.size}",
            collapsible = TestsWorkspaceTileId.LIBRARY in collapsible,
            canHide = testsWorkspaceCanHide(ui, TestsWorkspaceTileId.LIBRARY),
            sizing = TileBodySizing.FILL,
            body = { TestsLibraryTileContent() },
        ),
    )
    Tile(
        TestsWorkspaceTileId.ACTIVITY,
        TileSlot(
            icon = Icons.Outlined.AccountTree,
            title = testsWorkspaceTileTitle(TestsWorkspaceTileId.ACTIVITY),
            summary = ui.state.testRuns.size.toString(),
            collapsible = TestsWorkspaceTileId.ACTIVITY in collapsible,
            canHide = testsWorkspaceCanHide(ui, TestsWorkspaceTileId.ACTIVITY),
            sizing = TileBodySizing.FILL,
            body = { TestsActivityTileContent() },
        ),
    )
    Tile(
        TestsWorkspaceTileId.WORKSPACE,
        TileSlot(
            icon = Icons.Outlined.Description,
            title = testsWorkspaceTileTitle(TestsWorkspaceTileId.WORKSPACE),
            summary = testsWorkspaceSelectionSummary(ui),
            collapsible = TestsWorkspaceTileId.WORKSPACE in collapsible,
            hideable = false,
            canHide = false,
            maximizable = true,
            sizing = TileBodySizing.FILL,
            body = {
                Column(Modifier.fillMaxSize()) {
                    if (header != null) {
                        Row(
                            Modifier.fillMaxWidth().widthIn(max = TESTS_CONTENT_MAX_WIDTH).padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("tests-workspace-actions"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            content = header,
                        )
                        Divider()
                    }
                    TestsScrollColumn(Modifier.weight(1f).fillMaxWidth()) { main() }
                }
            },
        ),
    )
    Tile(
        TestsWorkspaceTileId.EXECUTION,
        TileSlot(
            icon = Icons.Outlined.PlayArrow,
            title = testsWorkspaceTileTitle(TestsWorkspaceTileId.EXECUTION),
            summary = null,
            collapsible = TestsWorkspaceTileId.EXECUTION in collapsible,
            canHide = testsWorkspaceCanHide(ui, TestsWorkspaceTileId.EXECUTION),
            sizing = TileBodySizing.FILL,
            body = { TestsScrollColumn(Modifier.fillMaxSize()) { TestsExecutionTile() } },
        ),
    )
    Tile(
        TestsWorkspaceTileId.DETAILS,
        TileSlot(
            icon = Icons.Outlined.Info,
            title = testsWorkspaceTileTitle(TestsWorkspaceTileId.DETAILS),
            collapsible = TestsWorkspaceTileId.DETAILS in collapsible,
            canHide = testsWorkspaceCanHide(ui, TestsWorkspaceTileId.DETAILS),
            maximizable = false,
            sizing = TileBodySizing.FILL,
            body = {
                if (ui.view.recordingReviewOpen) {
                    TestsRecordingReviewDetails()
                } else if (inspector == null) {
                    TestsWorkspaceEmptyTile("No details for this selection.")
                } else {
                    TestsScrollColumn(Modifier.fillMaxSize(), contentModifier = Modifier.padding(INSPECTOR_PADDING_DP)) { inspector() }
                }
            },
        ),
    )
    Tile(
        TestsWorkspaceTileId.EVIDENCE,
        TileSlot(
            icon = Icons.Outlined.Info,
            title = testsWorkspaceTileTitle(TestsWorkspaceTileId.EVIDENCE),
            collapsible = TestsWorkspaceTileId.EVIDENCE in collapsible,
            canHide = testsWorkspaceCanHide(ui, TestsWorkspaceTileId.EVIDENCE),
            maximizable = true,
            sizing = TileBodySizing.FILL,
            body = {
                if (ui.view.recordingReviewOpen) {
                    TestsRecordingReviewEvidence()
                } else if (evidence == null) {
                    TestsWorkspaceEmptyTile("No evidence for this selection.")
                } else {
                    TestsScrollColumn(Modifier.fillMaxSize(), contentModifier = Modifier.padding(INSPECTOR_PADDING_DP)) { evidence() }
                }
            },
        ),
    )
}

private val INSPECTOR_PADDING_DP = 14.dp

private fun testsWorkspaceCanHide(ui: TestsUi, id: TestsWorkspaceTileId): Boolean =
    id != TestsWorkspaceTileId.WORKSPACE && testsWorkspaceVisibleTiles(ui.state.settings.currentTestsWorkspaceLayout()).size > 1

private fun testsWorkspaceSelectionSummary(ui: TestsUi): String? = when (ui.view.nav) {
    TestsNav.Suites -> ui.library.selectedSuiteOrFirst(ui.view.selectedSuiteId)?.name?.takeIf { it.isNotBlank() }
    TestsNav.SharedSteps -> "Shared steps"
    TestsNav.Scripts -> "Scripts"
    TestsNav.Runs -> "Runs"
    TestsNav.Issues -> "Issues"
    TestsNav.AgentExamples -> "Examples"
}

@Composable
private fun TestsWorkspaceEmptyTile(message: String) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        AppText(message, color = tc().td, fontSize = 12.sp)
    }
}
