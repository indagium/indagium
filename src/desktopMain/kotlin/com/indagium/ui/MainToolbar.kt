package com.indagium.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.indagium.ui.holiday.*
import com.indagium.model.TabStripPosition

// The main toolbar: New tab, the panel toggles (Filter, Notes, AI, Video, Compare, Cases), Open with its recent-files arrow, the Layout
// menu and Settings. It comes in three shapes: the row the window always had (a horizontal bar), the same buttons stacked in a column
// (a bar on a left / right edge, or a tile narrower than tall), and a row that wraps onto further lines when it is too long for the room
// (the toolbar inside a vertical tab bar, or in a wide tile). The groups keep their joined corners in the row and the column (left /
// middle / right of a row become top / middle / bottom of a column). The wrapping row has the groups of the bar too, as chunks that wrap
// whole (ToolbarGroupFlow.kt): New, the panel toggles, Compare and Cases, Open with its arrow, Layout, Settings. The toggles, Compare / Cases and
// Open form one joined group in the bar; when that group fits a line it is drawn joined exactly as in the bar, otherwise it falls apart into
// its three chunks, each with its own end corners.

private val TOOLBAR_GAP = 4.dp
private val WRAP_PADDING = 2.dp
private val VERTICAL_BUTTON_HEIGHT = 32.dp
private val VERTICAL_SETTINGS_HEIGHT = 36.dp
private val VERTICAL_RECENT_HEIGHT = 18.dp
private val WRAP_BUTTON_HEIGHT = WRAPPED_BUTTON_HEIGHT_DP.dp
private val WRAP_BUTTON_PADDING = PaddingValues(horizontal = 8.dp, vertical = 5.dp)
private const val CORNER_DP = 7

// A square icon button 36 dp wide holding an 18 dp picture.
internal val PICTURE_ONLY_PADDING = PaddingValues(horizontal = 0.dp, vertical = 5.dp)

/** The toolbar's button-group corner for the current look. */
@Composable
internal fun toolbarCornerDp(): Int = LocalHoliday.current?.theme?.corners?.toolbarDp ?: CORNER_DP

private class ToolbarShapes(vertical: Boolean, cornerDp: Int = CORNER_DP) {
    private val corner = cornerDp.dp
    val lead = if (vertical) RoundedCornerShape(topStart = corner, topEnd = corner) else RoundedCornerShape(topStart = corner, bottomStart = corner)
    val middle = RoundedCornerShape(0.dp)
    val tail = if (vertical) RoundedCornerShape(bottomStart = corner, bottomEnd = corner) else RoundedCornerShape(topEnd = corner, bottomEnd = corner)
    val solo = RoundedCornerShape(corner)
}

// How the buttons are laid out; see the header comment.
private enum class ToolbarMode { BAR, COLUMN, FLOW }

/**
 * The toolbar for [orientation]. A vertical toolbar stacks full-width buttons; a horizontal one with [wrap] flows onto further lines
 * instead of running out of room, its groups wrapping whole ([ToolbarGroupFlow]). Labels follow the "Icons only" setting in every shape
 * ([showLabels] overrides it, for a tile too narrow for them); a label shortens with an ellipsis when the column is narrow. [popupsBeside]
 * opens the Layout menu beside its button rather than below it (the default for a vertical toolbar). Every click hands keyboard focus back
 * through [onReclaimFocus] (a clicked button otherwise keeps it, CLAUDE.md). [onRecentAnchor] is told where the Recent files arrow sits (root
 * coordinates, dp) so the menu can open next to it. [scrolls] false is for a toolbar that is as tall as its buttons (a tile sized by its
 * content): it never scrolls, which would need a bounded height. It draws no bar of its own: see [ChromeScaffold] / [TabBar].
 */
@Composable
internal fun MainToolbar(
    state: AppState,
    orientation: ChromeOrientation,
    onReclaimFocus: () -> Unit = {},
    modifier: Modifier = Modifier,
    onRecentAnchor: (TileRect) -> Unit = {},
    wrap: Boolean = false,
    popupsBeside: Boolean = orientation == ChromeOrientation.VERTICAL,
    showLabels: Boolean? = null,
    scrolls: Boolean = true,
) {
    val mode = when {
        orientation == ChromeOrientation.VERTICAL -> ToolbarMode.COLUMN
        wrap -> ToolbarMode.FLOW
        else -> ToolbarMode.BAR
    }
    val labels = showLabels ?: !state.settings.toolbarIconOnlyButtons
    val tagged = modifier.testTag("main-toolbar")
    val scroll = if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier
    when (mode) {
        ToolbarMode.COLUMN -> Column(tagged.then(scroll)) {
            ToolbarButtons(state, mode, labels, popupsBeside, onReclaimFocus, onRecentAnchor, null)
        }
        ToolbarMode.FLOW -> {
            val flow = remember { ToolbarFlowState() }
            ToolbarGroupFlow(flow, tagged.then(scroll).padding(WRAP_PADDING), TOOLBAR_GAP) {
                ToolbarButtons(state, mode, labels, popupsBeside, onReclaimFocus, onRecentAnchor, flow)
            }
        }
        ToolbarMode.BAR -> Row(tagged, verticalAlignment = Alignment.CenterVertically) {
            ToolbarButtons(state, mode, labels, popupsBeside, onReclaimFocus, onRecentAnchor, null)
        }
    }
}

// The chunks of the wrapping toolbar, in order (ToolbarGroupFlow measures them by these ids): the panel toggles, Compare / Cases and Open
// are the three chunks of the bar's one joined group.
private const val CHUNK_NEW = 0
private const val CHUNK_TOGGLES = 1
private const val CHUNK_TOOLS = 2
private const val CHUNK_OPEN = 3
private const val CHUNK_LAYOUT = 4
private const val CHUNK_SETTINGS = 5

@Composable
private fun ToolbarButtons(
    state: AppState,
    mode: ToolbarMode,
    labels: Boolean,
    popupsBeside: Boolean,
    onReclaimFocus: () -> Unit,
    onRecentAnchor: (TileRect) -> Unit,
    flowState: ToolbarFlowState?,
) {
    val vertical = mode == ToolbarMode.COLUMN
    val flow = mode == ToolbarMode.FLOW
    val shapes = ToolbarShapes(vertical, toolbarCornerDp())
    val hasRecentFiles = state.recentFiles.isNotEmpty()
    val homeActive = state.activeTab()?.isCaptureLauncher == true
    val density = LocalDensity.current.density
    // A bar button is as tall as the bar, a column button as wide as it, a flowing button a fixed height.
    val fillBase = when (mode) {
        ToolbarMode.COLUMN -> Modifier.fillMaxWidth().height(VERTICAL_BUTTON_HEIGHT)
        ToolbarMode.FLOW -> Modifier.height(WRAP_BUTTON_HEIGHT)
        ToolbarMode.BAR -> Modifier.fillMaxHeight()
    }
    val holiday = LocalHoliday.current
    val ornament = holiday?.theme?.toolbarOrnament
    val fill = fillBase.then(if (ornament == null) Modifier else Modifier.drawWithContent {
        drawContent()
        ornament.draw(this, TabStripPosition.TOP, 0f, holiday.isDark)
    })
    val pad = if (flow) WRAP_BUTTON_PADDING else PaddingValues(horizontal = 10.dp, vertical = 5.dp)
    val gap: @Composable () -> Unit = {
        if (!flow) Spacer(if (vertical) Modifier.height(TOOLBAR_GAP) else Modifier.width(TOOLBAR_GAP))
    }
    // Where the joined corners go. In the bar and the column the toggles run from Filter (lead) through Cases (middle) to Open / its arrow
    // (tail). In the wrapping row each chunk is a run of its own, or, while the whole group fits a line, they are one run as in the bar.
    val joined = flowState?.joined == true
    val split = flowState?.split.orEmpty()
    val chunk: @Composable (Int, @Composable () -> Unit) -> Unit = { id, content ->
        if (flow) ToolbarChunk(id, id in split, content) else content()
    }
    val cell: @Composable (String, @Composable () -> Unit) -> Unit = { key, content ->
        if (flow) ToolbarCell(key, content) else content()
    }

    fun shapeOf(id: Int, first: Boolean, last: Boolean): RoundedCornerShape {
        // A chunk too wide for a line is laid out button by button, each standing alone.
        if (id in split) return shapes.solo
        val inGroup = id in CHUNK_TOGGLES..CHUNK_OPEN
        val startsRun = first && !(joined && inGroup && id != CHUNK_TOGGLES)
        val endsRun = last && !(joined && inGroup && id != CHUNK_OPEN)
        return when {
            startsRun && endsRun -> shapes.solo
            startsRun -> shapes.lead
            endsRun -> shapes.tail
            else -> shapes.middle
        }
    }
    val activeTabCanShowVideo = state.tab(state.activeTabId)?.let {
        it.attachedVideo != null || it.captureSessionId != null
    } == true
    gap()
    chunk(CHUNK_NEW) {
        val pack = holidayToolbarIcon(ToolbarIconId.NEW)
        val stickColor = tc().ts.copy(alpha = .55f)
        val stick = LocalHoliday.current?.theme?.newButtonStick == true
        val newButtonModifier = if (stick) fill.drawBehind {
            val length = 5.dp.toPx()
            drawLine(stickColor, Offset(size.width / 2f, size.height - length), Offset(size.width / 2f, size.height), 2.dp.toPx())
        }.padding(bottom = 5.dp) else fill
        ToolbarBtn(
            "",
            icon = pack?.vector ?: Icons.Outlined.Add,
            multicolor = pack != null,
            showLabel = false,
            tooltip = pack?.tooltip ?: "New tab",
            active = homeActive,
            modifier = newButtonModifier,
            shape = shapes.solo,
            contentPadding = pad,
        ) { state.openHomeTab(); onReclaimFocus() }
    }
    gap()
    chunk(CHUNK_TOGGLES) {
        cell("filter") {
            val pack = holidayToolbarIcon(ToolbarIconId.FILTERS)
            ToolbarBtn(
                "Filter",
                icon = pack?.vector ?: Icons.Outlined.FilterList,
                multicolor = pack != null,
                showLabel = labels,
                tooltip = pack?.tooltip ?: "Toggle filter panel",
                active = state.filterVisible,
                modifier = fill,
                shape = if (flow) shapeOf(CHUNK_TOGGLES, first = true, last = false) else shapes.lead,
                contentPadding = pad,
                ellipsize = vertical,
            ) { state.updateFilterVisible(!state.filterVisible); onReclaimFocus() }
        }
        cell("notes") {
            val pack = holidayToolbarIcon(ToolbarIconId.NOTES)
            ToolbarBtn(
                "Notes",
                icon = pack?.vector ?: Icons.AutoMirrored.Outlined.StickyNote2,
                multicolor = pack != null,
                showLabel = labels,
                tooltip = pack?.tooltip ?: "Toggle notes panel",
                active = state.annotationVisible,
                modifier = fill,
                shape = if (flow) shapeOf(CHUNK_TOGGLES, first = false, last = false) else shapes.middle,
                contentPadding = pad,
                ellipsize = vertical,
            ) { state.updateAnnotationVisible(!state.annotationVisible); onReclaimFocus() }
        }
        // The last toggle of the chunk takes its end corners in the wrapping row (the chunk is a run of its own once the group does not fit).
        cell("ai") {
            val pack = holidayToolbarIcon(ToolbarIconId.AI)
            ToolbarBtn(
                "AI",
                icon = pack?.vector ?: Icons.Outlined.AutoAwesome,
                multicolor = pack != null,
                showLabel = labels,
                tooltip = pack?.tooltip ?: "Toggle AI panel",
                active = state.aiPanelVisible,
                modifier = fill,
                shape = if (flow) shapeOf(CHUNK_TOGGLES, first = false, last = !activeTabCanShowVideo) else shapes.middle,
                contentPadding = pad,
                ellipsize = vertical,
            ) { state.updateAiPanelVisible(!state.aiPanelVisible); onReclaimFocus() }
        }
        // Capture sessions expose a status card before a video attachment exists; the same global
        // toggle must therefore remain reachable for live tabs as well as imported captures.
        if (activeTabCanShowVideo) {
            cell("video") {
                val pack = holidayToolbarIcon(ToolbarIconId.VIDEO)
                ToolbarBtn(
                    "Video",
                    icon = pack?.vector ?: Icons.Outlined.Movie,
                    multicolor = pack != null,
                    showLabel = labels,
                    tooltip = pack?.tooltip ?: "Toggle video in sidebar",
                    active = state.videoPanelVisible,
                    modifier = fill,
                    shape = if (flow) shapeOf(CHUNK_TOGGLES, first = false, last = true) else shapes.middle,
                    contentPadding = pad,
                    ellipsize = vertical,
                ) { state.updateVideoPanelVisible(!state.videoPanelVisible); onReclaimFocus() }
            }
        }
    }
    chunk(CHUNK_TOOLS) {
        cell("compare") {
            val pack = holidayToolbarIcon(ToolbarIconId.COMPARE)
            ToolbarBtn(
                "Compare",
                icon = pack?.vector ?: Icons.AutoMirrored.Outlined.CompareArrows,
                multicolor = pack != null,
                showLabel = labels,
                tooltip = pack?.tooltip ?: "Toggle compare view",
                active = state.compareMode,
                enabled = state.canCompare,
                modifier = fill,
                shape = if (flow) shapeOf(CHUNK_TOOLS, first = true, last = false) else shapes.middle,
                contentPadding = pad,
                ellipsize = vertical,
            ) { state.updateCompareMode(!state.compareMode); onReclaimFocus() }
        }
        cell("cases") {
            val pack = holidayToolbarIcon(ToolbarIconId.CASES)
            ToolbarBtn(
                "Cases",
                icon = pack?.vector ?: Icons.AutoMirrored.Outlined.ManageSearch,
                multicolor = pack != null,
                showLabel = labels,
                tooltip = pack?.tooltip ?: "Search past analyses",
                enabled = state.tabs.isNotEmpty(),
                modifier = fill,
                shape = if (flow) shapeOf(CHUNK_TOOLS, first = false, last = true) else shapes.middle,
                contentPadding = pad,
                ellipsize = vertical,
            ) { state.activeTab()?.id?.let(state::openCaseLibrary); onReclaimFocus() }
        }
    }
    chunk(CHUNK_OPEN) {
        OpenButtons(state, mode, labels, fill, pad, hasRecentFiles, onReclaimFocus, onRecentAnchor, density, ::shapeOf)
    }
    gap()
    // The log view's Layout menu (single log view and compare; draws nothing elsewhere), just before Settings.
    chunk(CHUNK_LAYOUT) { LayoutMenuButton(state, onReclaimFocus, vertical = vertical, wrap = flow, menuBeside = popupsBeside) }
    gap()
    chunk(CHUNK_SETTINGS) {
        val pack = holidayToolbarIcon(ToolbarIconId.SETTINGS)
        ToolbarBtn(
            "⚙",
            icon = pack?.vector,
            multicolor = pack != null,
            showLabel = pack == null,
            tooltip = pack?.tooltip ?: "Settings",
            modifier = when (mode) {
                ToolbarMode.COLUMN -> Modifier.fillMaxWidth().height(VERTICAL_SETTINGS_HEIGHT)
                ToolbarMode.FLOW -> Modifier.width(36.dp).height(WRAP_BUTTON_HEIGHT)
                ToolbarMode.BAR -> Modifier.fillMaxHeight().width(36.dp)
            },
            shape = shapes.solo,
            // The picture is wider than the glyph it replaces; the 36 dp button leaves it no side padding.
            contentPadding = if (pack != null) PICTURE_ONLY_PADDING else PaddingValues(horizontal = 10.dp, vertical = 5.dp),
        ) { state.settingsOpen = true; onReclaimFocus() }
    }
    gap()
}

// Open and, once there are recent files, its arrow. [flowShape] (the wrapping row's chunk-aware shapes) is used only there.
@Composable
private fun OpenButtons(
    state: AppState,
    mode: ToolbarMode,
    labels: Boolean,
    fill: Modifier,
    pad: PaddingValues,
    hasRecentFiles: Boolean,
    onReclaimFocus: () -> Unit,
    onRecentAnchor: (TileRect) -> Unit,
    density: Float,
    flowShape: (id: Int, first: Boolean, last: Boolean) -> RoundedCornerShape,
) {
    val vertical = mode == ToolbarMode.COLUMN
    val flow = mode == ToolbarMode.FLOW
    val shapes = ToolbarShapes(vertical, toolbarCornerDp())
    val cell: @Composable (String, @Composable () -> Unit) -> Unit = { key, content ->
        if (flow) ToolbarCell(key, content) else content()
    }
    cell("open") {
        val pack = holidayToolbarIcon(ToolbarIconId.OPEN)
        ToolbarBtn(
            "Open",
            icon = pack?.vector ?: Icons.Outlined.FolderOpen,
            multicolor = pack != null,
            showLabel = labels,
            tooltip = pack?.tooltip ?: "Open log file",
            modifier = fill,
            shape = when {
                flow -> flowShape(CHUNK_OPEN, true, !hasRecentFiles)
                hasRecentFiles -> shapes.middle
                else -> shapes.tail
            },
            contentPadding = pad,
            ellipsize = vertical,
        ) { pickLogFileAndOpen(state, "Open Log File"); onReclaimFocus() }
    }
    if (hasRecentFiles) {
        cell("recent") {
            ToolbarBtn(
                "▾",
                active = state.recentMenuOpen,
                tooltip = "Recent files",
                modifier = when (mode) {
                    ToolbarMode.COLUMN -> Modifier.fillMaxWidth().height(VERTICAL_RECENT_HEIGHT)
                    ToolbarMode.FLOW -> Modifier.width(18.dp).height(WRAP_BUTTON_HEIGHT)
                    ToolbarMode.BAR -> Modifier.fillMaxHeight().width(18.dp)
                }.onGloballyPositioned { c ->
                    val p = c.positionInRoot()
                    onRecentAnchor(TileRect(p.x / density, p.y / density, c.size.width / density, c.size.height / density))
                },
                shape = if (flow) flowShape(CHUNK_OPEN, false, true) else shapes.tail,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = if (vertical) 0.dp else 5.dp),
            ) { state.toggleRecentFilesMenu(); onReclaimFocus() }
        }
    }
}
