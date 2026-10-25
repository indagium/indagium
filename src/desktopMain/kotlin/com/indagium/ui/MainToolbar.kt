package com.indagium.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

// The main toolbar: New tab, the panel toggles (Filter, Notes, AI, Video, Compare, Cases), Open with its recent-files arrow, the Layout
// menu and Settings. A horizontal toolbar is the row the window always had; a vertical one is the same buttons stacked in a column (the
// toolbar is docked to a left / right edge, or sits in a vertical tab strip's bar). The groups keep their joined corners: left / middle /
// right of a row become top / middle / bottom of a column.

private val TOOLBAR_GAP = 4.dp
private val VERTICAL_BUTTON_HEIGHT = 32.dp
private val VERTICAL_SETTINGS_HEIGHT = 36.dp
private val VERTICAL_RECENT_HEIGHT = 18.dp
private const val CORNER_DP = 7

private class ToolbarShapes(vertical: Boolean) {
    private val corner = CORNER_DP.dp
    val lead = if (vertical) RoundedCornerShape(topStart = corner, topEnd = corner) else RoundedCornerShape(topStart = corner, bottomStart = corner)
    val middle = RoundedCornerShape(0.dp)
    val tail = if (vertical) RoundedCornerShape(bottomStart = corner, bottomEnd = corner) else RoundedCornerShape(topEnd = corner, bottomEnd = corner)
    val solo = RoundedCornerShape(corner)
}

/**
 * The toolbar for [orientation]. In a vertical toolbar the buttons fill its width; they show their labels only when [labelsFit] (the
 * toolbar is in a wide bar such as a vertical tab strip, and the "Icons only" setting is off). Every click hands keyboard focus back through
 * [onReclaimFocus] (a clicked button otherwise keeps it, CLAUDE.md). [onRecentAnchor] is told where the Recent
 * files arrow sits (root coordinates, dp) so the menu can open next to it. It draws no bar of its own: see [ChromeScaffold] / [TabBar].
 */
@Composable
internal fun MainToolbar(
    state: AppState,
    orientation: ChromeOrientation,
    onReclaimFocus: () -> Unit = {},
    modifier: Modifier = Modifier,
    labelsFit: Boolean = true,
    onRecentAnchor: (TileRect) -> Unit = {},
) {
    val vertical = orientation == ChromeOrientation.VERTICAL
    val tagged = modifier.testTag("main-toolbar")
    if (vertical) {
        Column(tagged.verticalScroll(rememberScrollState())) { ToolbarButtons(state, true, labelsFit, onReclaimFocus, onRecentAnchor) }
    } else {
        Row(tagged, verticalAlignment = Alignment.CenterVertically) { ToolbarButtons(state, false, labelsFit, onReclaimFocus, onRecentAnchor) }
    }
}

@Composable
private fun ToolbarButtons(
    state: AppState,
    vertical: Boolean,
    labelsFit: Boolean,
    onReclaimFocus: () -> Unit,
    onRecentAnchor: (TileRect) -> Unit,
) {
    val shapes = ToolbarShapes(vertical)
    val hasRecentFiles = state.recentFiles.isNotEmpty()
    val showToolbarText = !state.settings.toolbarIconOnlyButtons && (!vertical || labelsFit)
    val homeActive = state.activeTab()?.isCaptureLauncher == true
    val density = LocalDensity.current.density
    // A horizontal button is as tall as the bar, a vertical one as wide as it.
    val fill = if (vertical) Modifier.fillMaxWidth().height(VERTICAL_BUTTON_HEIGHT) else Modifier.fillMaxHeight()
    val gap: @Composable () -> Unit = { Spacer(if (vertical) Modifier.height(TOOLBAR_GAP) else Modifier.width(TOOLBAR_GAP)) }
    gap()
    ToolbarBtn(
        "",
        icon = Icons.Outlined.Add,
        showLabel = false,
        tooltip = "New tab",
        active = homeActive,
        modifier = fill,
        shape = shapes.solo,
    ) { state.openHomeTab(); onReclaimFocus() }
    gap()
    ToolbarBtn(
        "Filter",
        icon = Icons.Outlined.FilterList,
        showLabel = showToolbarText,
        tooltip = "Toggle filter panel",
        active = state.filterVisible,
        modifier = fill,
        shape = shapes.lead,
    ) { state.updateFilterVisible(!state.filterVisible); onReclaimFocus() }
    ToolbarBtn(
        "Notes",
        icon = Icons.AutoMirrored.Outlined.StickyNote2,
        showLabel = showToolbarText,
        tooltip = "Toggle notes panel",
        active = state.annotationVisible,
        modifier = fill,
        shape = shapes.middle,
    ) { state.updateAnnotationVisible(!state.annotationVisible); onReclaimFocus() }
    ToolbarBtn(
        "AI",
        icon = Icons.Outlined.AutoAwesome,
        showLabel = showToolbarText,
        tooltip = "Toggle AI panel",
        active = state.aiPanelVisible,
        modifier = fill,
        shape = shapes.middle,
    ) { state.updateAiPanelVisible(!state.aiPanelVisible); onReclaimFocus() }
    // Capture sessions expose a status card before a video attachment exists; the same global
    // toggle must therefore remain reachable for live tabs as well as imported captures.
    val activeTabCanShowVideo = state.tab(state.activeTabId)?.let {
        it.attachedVideo != null || it.captureSessionId != null
    } == true
    if (activeTabCanShowVideo) {
        ToolbarBtn(
            "Video",
            icon = Icons.Outlined.Movie,
            showLabel = showToolbarText,
            tooltip = "Toggle video in sidebar",
            active = state.videoPanelVisible,
            modifier = fill,
            shape = shapes.middle,
        ) { state.updateVideoPanelVisible(!state.videoPanelVisible); onReclaimFocus() }
    }
    ToolbarBtn(
        "Compare",
        icon = Icons.AutoMirrored.Outlined.CompareArrows,
        showLabel = showToolbarText,
        tooltip = "Toggle compare view",
        active = state.compareMode,
        enabled = state.canCompare,
        modifier = fill,
        shape = shapes.middle,
    ) { state.updateCompareMode(!state.compareMode); onReclaimFocus() }
    ToolbarBtn(
        "Cases",
        icon = Icons.AutoMirrored.Outlined.ManageSearch,
        showLabel = showToolbarText,
        tooltip = "Search past analyses",
        enabled = state.tabs.isNotEmpty(),
        modifier = fill,
        shape = shapes.middle,
    ) { state.activeTab()?.id?.let(state::openCaseLibrary); onReclaimFocus() }
    ToolbarBtn(
        "Open",
        icon = Icons.Outlined.FolderOpen,
        showLabel = showToolbarText,
        tooltip = "Open log file",
        modifier = fill,
        shape = if (hasRecentFiles) shapes.middle else shapes.tail,
    ) { pickLogFileAndOpen(state, "Open Log File"); onReclaimFocus() }
    if (hasRecentFiles) {
        ToolbarBtn(
            "▾",
            active = state.recentMenuOpen,
            tooltip = "Recent files",
            modifier = (if (vertical) Modifier.fillMaxWidth().height(VERTICAL_RECENT_HEIGHT) else Modifier.fillMaxHeight().width(18.dp))
                .onGloballyPositioned { c ->
                    val p = c.positionInRoot()
                    onRecentAnchor(TileRect(p.x / density, p.y / density, c.size.width / density, c.size.height / density))
                },
            shape = shapes.tail,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = if (vertical) 0.dp else 5.dp),
        ) { state.toggleRecentFilesMenu(); onReclaimFocus() }
    }
    gap()
    // The log view's Layout menu (single log view and compare; draws nothing elsewhere), just before Settings.
    LayoutMenuButton(state, onReclaimFocus, vertical = vertical)
    gap()
    ToolbarBtn(
        "⚙",
        tooltip = "Settings",
        modifier = if (vertical) Modifier.fillMaxWidth().height(VERTICAL_SETTINGS_HEIGHT) else Modifier.fillMaxHeight().width(36.dp),
        shape = shapes.solo,
    ) { state.settingsOpen = true; onReclaimFocus() }
    gap()
}
