package com.indagium.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.indagium.model.DEFAULT_HOME_OPEN_ACTIONS_HEIGHT_DP
import com.indagium.model.HomeSectionId
import com.indagium.model.LogTab
import com.indagium.model.LogViewScope
import com.indagium.model.LogViewSectionId

// The Layout menu: a dashboard button in the tab strip (just before Settings) that opens a popup with the four single-view presets (the two
// compare presets in compare mode) as
// thumbnails, a checklist of the panels that currently apply, a "Lock tiles" switch and "Reset to <preset>". The button carries a small
// badge counting the applicable tiles hidden through the menu or a tile's hide button. Every click and the dismissal hand keyboard focus
// back to the root (CLAUDE.md: a clickable or a dismissed Popup steals it and never returns it).

private const val MENU_WIDTH_DP = 320
private const val THUMB_WIDTH_DP = 66
private const val THUMB_HEIGHT_DP = 38
private const val THUMB_VIRTUAL_WIDTH = 1200f
private const val THUMB_VIRTUAL_HEIGHT = 720f
private const val THUMB_FILL_ALPHA = 0.55f
private const val THUMB_LOG_FILL_ALPHA = 0.9f
private const val BADGE_SIZE_DP = 14
private const val MENU_GAP_PX = 4

/** Height (dp) of a button in a wrapping toolbar (MainToolbar's flow layout), the same as a vertical toolbar's buttons. */
internal const val WRAPPED_BUTTON_HEIGHT_DP = 32

/** The active log tab when the Layout menu applies (a single log view or the compare view); null on the New tab, a diagram and the tests tab. */
internal fun layoutMenuTab(state: AppState): LogTab? {
    val tab = state.activeTab() ?: return null
    val onLogSurface = state.activeSurface.let { it == null || it is ActiveSurface.Log }
    return tab.takeIf { !it.isCaptureLauncher && onLogSurface }
}

private fun AppState.layoutMenuDiagramSessionId(): String? =
    (activeSurface as? ActiveSurface.Diagram3)?.sessionId

/** The tile context the menu reads: the compare view's when compare is on (and there are two tabs), else the single view's for [tab]. */
private fun AppState.layoutMenuContext(tab: LogTab): LogViewTileContext {
    val pair = compareTabs()?.takeIf { compareMode }
    return if (pair != null) compareTileContext(pair.first, pair.second) else logViewTileContext(tab)
}

private val ORIGINAL_TILES = setOf(LogViewSectionId.ORIGINAL, LogViewSectionId.ORIGINAL_A, LogViewSectionId.ORIGINAL_B)

/**
 * The tiles the Panels checklist offers for [ctx]: every applicable tile except the log tiles, the toolbar tile (never hideable) and the
 * Original tiles (each has its own ×).
 */
internal fun layoutMenuPanels(ctx: LogViewTileContext): List<LogViewSectionId> =
    LogViewSectionId.entries.filter { !it.neverHidden && it !in ORIGINAL_TILES && logViewTileApplicable(it, ctx) }

/**
 * The toolbar button, with the hidden-tile badge. Draws nothing where the menu does not apply (see [layoutMenuTab]). In a [vertical]
 * toolbar it fills the toolbar's width, and its menu opens beside it ([menuBeside]) rather than below. In a [wrap]ping toolbar (a row of
 * buttons that flows onto more lines, with 32 dp buttons) it is a fixed-size button.
 */
@Composable
internal fun LayoutMenuButton(state: AppState, onReclaimFocus: () -> Unit, vertical: Boolean = false, wrap: Boolean = false, menuBeside: Boolean = vertical) {
    val activeTab = state.activeTab()
    val onLogSurface = state.activeSurface.let { it == null || it is ActiveSurface.Log }
    val home = activeTab?.isCaptureLauncher == true && onLogSurface
    val tab = if (home) null else layoutMenuTab(state)
    val diagramSessionId = state.layoutMenuDiagramSessionId()
    val diagramView = diagramSessionId?.let(state.seq3Sessions::viewState)
    if (!home && tab == null && diagramView == null) return
    val tc = tc()
    var open by remember { mutableStateOf(false) }
    val ctx = tab?.let(state::layoutMenuContext)
    val badge = when {
        home -> state.settings.homeHiddenSections.size
        diagramView != null -> diagramView.diagramHiddenTiles.size
        ctx?.compare == true -> state.compareBadgeCount(ctx)
        ctx != null -> state.logViewBadgeCount(ctx)
        else -> 0
    }
    Box(if (vertical) Modifier.fillMaxWidth() else if (wrap) Modifier else Modifier.fillMaxHeight()) {
        ToolbarBtn(
            "",
            icon = Icons.Outlined.Dashboard,
            showLabel = false,
            tooltip = "Layout",
            active = open,
            modifier = when {
                vertical -> Modifier.fillMaxWidth().height(36.dp)
                wrap -> Modifier.width(36.dp).height(WRAPPED_BUTTON_HEIGHT_DP.dp)
                else -> Modifier.fillMaxHeight().width(36.dp)
            }.testTag("layout-menu-button"),
            shape = RoundedCornerShape(7.dp),
        ) { open = !open }
        if (badge > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-1).dp, y = 1.dp)
                    .size(BADGE_SIZE_DP.dp)
                    .background(tc.ac, CircleShape)
                    .testTag("layout-menu-badge")
                    .semantics(mergeDescendants = true) {},
                contentAlignment = Alignment.Center,
            ) { AppText(badge.toString(), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
        }
        if (open) {
            val dismiss = {
                open = false
                onReclaimFocus()
            }
            if (home) {
                HomeLayoutMenuPopup(state, vertical = menuBeside, onReclaimFocus = onReclaimFocus, onDismiss = dismiss)
            } else if (diagramView != null) {
                DiagramLayoutMenuPopup(state, diagramView, vertical = menuBeside, onReclaimFocus = onReclaimFocus, onDismiss = dismiss)
            } else {
                tab?.let { LayoutMenuPopup(state, it, vertical = menuBeside, onReclaimFocus = onReclaimFocus, onDismiss = dismiss) }
            }
        }
    }
}

// Below the button (above it when there is no room below), right edge aligned with it, kept inside the window. From a vertical
// toolbar: beside the button instead, on the side with room (right of it, else left), top aligned.
private class LayoutMenuPositionProvider(private val vertical: Boolean) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        if (vertical) {
            val right = anchorBounds.right + MENU_GAP_PX
            val x = if (right <= maxX) right else (anchorBounds.left - MENU_GAP_PX - popupContentSize.width).coerceAtLeast(0)
            return IntOffset(x, anchorBounds.top.coerceIn(0, maxY))
        }
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, maxX)
        val below = anchorBounds.bottom + MENU_GAP_PX
        val above = anchorBounds.top - MENU_GAP_PX - popupContentSize.height
        // Above the button when the toolbar is on the bottom edge and there is no room below.
        val y = if (below > maxY && above >= 0) above else below.coerceIn(0, maxY)
        return IntOffset(x, y)
    }
}

/** Home/New tab version of the toolbar Layout menu: presets and the section visibility checklist. */
@Composable
private fun HomeLayoutMenuPopup(
    state: AppState,
    onReclaimFocus: () -> Unit,
    onDismiss: () -> Unit,
    vertical: Boolean = false,
) {
    val tc = tc()
    val shape = RoundedCornerShape(8.dp)
    val settings = state.settings
    val visible = homeLeafSections(settings.homeLayout).toSet()
    Popup(
        popupPositionProvider = remember(vertical) { LayoutMenuPositionProvider(vertical) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(MENU_WIDTH_DP.dp).background(tc.p, shape).border(1.dp, tc.br, shape)
                .padding(vertical = 8.dp).testTag("layout-menu-popup"),
        ) {
            MenuLabel("Layout presets")
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HomeLayoutPreset.entries.chunked(3).forEach { rowPresets ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowPresets.forEach { preset ->
                            HomePresetThumb(preset, active = preset.isActiveIn(settings)) {
                                state.updateSettings { it.withHomeLayoutPreset(preset) }
                                onReclaimFocus()
                            }
                        }
                    }
                }
            }
            Divider()
            MenuLabel("Panels")
            HomeSectionId.entries.forEach { id ->
                val isVisible = id in visible
                CheckRow(
                    checked = isVisible,
                    onToggle = {
                        state.updateSettings {
                            if (isVisible) it.withHomeSectionHidden(id) else it.withHomeSectionShown(id)
                        }
                        onReclaimFocus()
                    },
                    modifier = Modifier.testTag("home-layout-menu-panel-${id.key}"),
                    enabled = !isVisible || visible.size > 1,
                ) {
                    AppText(homeSectionTitle(id), color = tc.tx, fontSize = 12.sp)
                }
            }
            Divider()
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) {
                    state.updateSettings {
                        it.withHomeLayoutPreset(HomeLayoutPreset.DEFAULT)
                            .withHomeOpenActionsHeightDp(DEFAULT_HOME_OPEN_ACTIONS_HEIGHT_DP)
                    }
                    onReclaimFocus()
                }.padding(horizontal = 12.dp, vertical = 8.dp).testTag("home-layout-menu-reset"),
            ) {
                AppText("Reset to Default", color = tc.ac, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun HomePresetThumb(preset: HomeLayoutPreset, active: Boolean, onClick: () -> Unit) {
    val tc = tc()
    val fill = if (active) tc.ac else tc.td
    val geometry = remember(preset) { homeGeometry(preset.layout, THUMB_VIRTUAL_WIDTH, THUMB_VIRTUAL_HEIGHT, 8f) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            Modifier
                .size(THUMB_WIDTH_DP.dp, THUMB_HEIGHT_DP.dp)
                .clip(CORNER_MD)
                .background(if (active) tc.ac.copy(alpha = 0.12f) else Color.Transparent)
                .border(1.dp, if (active) tc.ac else tc.br, CORNER_MD)
                .testTag("home-preset-${preset.name}")
                .semantics {
                    contentDescription = preset.label
                    selected = active
                }
                .clickable(role = Role.Button, onClick = onClick)
                .padding(3.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val sx = size.width / THUMB_VIRTUAL_WIDTH
                val sy = size.height / THUMB_VIRTUAL_HEIGHT
                geometry.cards.forEach { card ->
                    drawRoundRect(
                        color = fill,
                        topLeft = Offset(card.rect.x * sx, card.rect.y * sy),
                        size = Size(card.rect.w * sx, card.rect.h * sy),
                        cornerRadius = CornerRadius(1.5.dp.toPx()),
                    )
                }
            }
        }
        AppText(preset.label, color = tc.ts, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Diagram workspace version of the Layout menu; the session-scoped view tree and visibility
 *  flags travel with the same per-session autosave token as the older queue preferences. */
@Composable
private fun DiagramLayoutMenuPopup(
    state: AppState,
    view: Seq3ViewState,
    onReclaimFocus: () -> Unit,
    onDismiss: () -> Unit,
    vertical: Boolean = false,
) {
    val tc = tc()
    val shape = RoundedCornerShape(8.dp)
    val visible = diagramWorkspaceVisibleTiles(view)
    Popup(
        popupPositionProvider = remember(vertical) { LayoutMenuPositionProvider(vertical) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(MENU_WIDTH_DP.dp).background(tc.p, shape).border(1.dp, tc.br, shape)
                .padding(vertical = 8.dp).testTag("layout-menu-popup"),
        ) {
            MenuLabel("Layout presets")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DiagramWorkspacePreset.entries.forEach { preset ->
                    DiagramPresetThumb(preset, active = diagramWorkspacePresetActive(view, preset)) {
                        applyDiagramWorkspacePreset(view, preset)
                        state.autosaveInBackground()
                        onReclaimFocus()
                    }
                }
            }
            Divider()
            MenuLabel("Panels")
            DiagramWorkspaceSectionId.entries.forEach { id ->
                val isVisible = id in visible
                Box(Modifier.testTag("diagram-layout-menu-panel-${id.key}")) {
                    CheckRow(
                        checked = isVisible,
                        onToggle = {
                            if (setDiagramWorkspaceTileVisible(view, id, !isVisible)) state.autosaveInBackground()
                            onReclaimFocus()
                        },
                        enabled = !isVisible || visible.size > 1,
                    ) { AppText(diagramWorkspaceTitle(id), color = tc.tx, fontSize = 12.sp) }
                }
            }
            Divider()
            Box(Modifier.testTag("diagram-layout-menu-lock")) {
                CheckRow(view.diagramTilesLocked, {
                    view.diagramTilesLocked = !view.diagramTilesLocked
                    state.autosaveInBackground()
                    onReclaimFocus()
                }) { AppText("Lock tiles", color = tc.tx, fontSize = 12.sp) }
            }
            val resetTo = view.diagramLastPreset
            Row(
                Modifier.fillMaxWidth()
                    .clickable(role = Role.Button) {
                        applyDiagramWorkspacePreset(view, resetTo)
                        state.autosaveInBackground()
                        onReclaimFocus()
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("diagram-layout-menu-reset"),
            ) { AppText("Reset to ${resetTo.label}", color = tc.ac, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun DiagramPresetThumb(preset: DiagramWorkspacePreset, active: Boolean, onClick: () -> Unit) {
    val tc = tc()
    val fill = if (active) tc.ac else tc.td
    val geometry = remember(preset) {
        tileGeometry(
            preset.layout,
            THUMB_VIRTUAL_WIDTH,
            THUMB_VIRTUAL_HEIGHT,
            DIAGRAM_WORKSPACE_CHROME_SPEC.geometrySpec(),
            scrolls = DIAGRAM_WORKSPACE_CHROME_SPEC.scrolls,
        )
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            Modifier
                .size(THUMB_WIDTH_DP.dp, THUMB_HEIGHT_DP.dp)
                .clip(CORNER_MD)
                .background(if (active) tc.ac.copy(alpha = 0.12f) else Color.Transparent)
                .border(1.dp, if (active) tc.ac else tc.br, CORNER_MD)
                .testTag("diagram-layout-menu-preset-${preset.name}")
                .semantics {
                    contentDescription = preset.label
                    selected = active
                }
                .clickable(role = Role.Button, onClick = onClick)
                .padding(3.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val sx = size.width / THUMB_VIRTUAL_WIDTH
                val sy = size.height / THUMB_VIRTUAL_HEIGHT
                geometry.cards.forEach { card ->
                    val r = card.rect
                    drawRoundRect(
                        color = fill.copy(alpha = if (card.section == DiagramWorkspaceSectionId.PREVIEW) THUMB_LOG_FILL_ALPHA else THUMB_FILL_ALPHA),
                        topLeft = Offset(r.x * sx, r.y * sy),
                        size = Size((r.w * sx - 1f).coerceAtLeast(1f), (r.h * sy - 1f).coerceAtLeast(1f)),
                        cornerRadius = CornerRadius(1.dp.toPx()),
                    )
                }
            }
        }
        Box(Modifier.width(THUMB_WIDTH_DP.dp), contentAlignment = Alignment.Center) {
            AppText(preset.label, color = if (active) tc.ac else tc.ts, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The popup itself: presets, Panels, Lock tiles, Reset. [onDismiss] must also reclaim focus; [onReclaimFocus] follows every click. */
@Composable
internal fun LayoutMenuPopup(
    state: AppState,
    tab: LogTab,
    onReclaimFocus: () -> Unit,
    onDismiss: () -> Unit,
    vertical: Boolean = false,
) {
    val tc = tc()
    val shape = RoundedCornerShape(8.dp)
    val ctx = state.layoutMenuContext(tab)
    val compare = ctx.compare
    val settings = state.settings
    val hidden = if (compare) settings.compareHidden else settings.logViewHidden
    Popup(
        popupPositionProvider = remember(vertical) { LayoutMenuPositionProvider(vertical) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(MENU_WIDTH_DP.dp).background(tc.p, shape).border(1.dp, tc.br, shape).padding(vertical = 8.dp).testTag("layout-menu-popup"),
        ) {
            MenuLabel("Layout presets")
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogViewLayoutPreset.forScope(if (compare) LogViewScope.COMPARE else LogViewScope.SINGLE).forEach { preset ->
                    PresetThumb(preset, active = if (compare) state.comparePresetActive(preset) else state.logViewPresetActive(preset)) {
                        if (compare) state.applyComparePreset(preset) else state.applyLogViewPreset(preset)
                        onReclaimFocus()
                    }
                }
            }
            Divider()
            MenuLabel("Panels")
            layoutMenuPanels(ctx).forEach { id ->
                val visible = logViewTileVisible(id, ctx, hidden)
                Box(Modifier.testTag("layout-menu-panel-${id.key}")) {
                    CheckRow(visible, {
                        if (visible) state.hideLogViewTile(id) else state.showLogViewTile(id)
                        onReclaimFocus()
                    }) { AppText(logViewTileTitle(id), color = tc.tx, fontSize = 12.sp) }
                }
            }
            Divider()
            Box(Modifier.testTag("layout-menu-lock")) {
                CheckRow(settings.logViewTilesLocked, {
                    state.toggleLogViewTilesLocked()
                    onReclaimFocus()
                }) { AppText("Lock tiles", color = tc.tx, fontSize = 12.sp) }
            }
            val resetTo = if (compare) state.lastComparePresetOrGrid() else state.lastLogViewPresetOrClassic()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) {
                        if (compare) state.resetCompareLayout() else state.resetLogViewLayout()
                        onReclaimFocus()
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("layout-menu-reset"),
            ) { AppText("Reset to ${resetTo.label}", color = tc.ac, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun MenuLabel(text: String) {
    AppText(text, color = tc().td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
}

// A preset's arrangement as a thumbnail: the tree minus its hidden tiles laid out on a virtual host with the real geometry rules (so
// pinned columns and Log level keep their proportions), scaled down; the log tile is drawn stronger.
@Composable
private fun PresetThumb(preset: LogViewLayoutPreset, active: Boolean, onClick: () -> Unit) {
    val tc = tc()
    val fill = if (active) tc.ac else tc.td
    val tree = remember(preset) {
        preset.hidden.fold(preset.layout(LogViewLegacySizes()) as LogViewNode?) { acc, id -> acc?.let { removeTile(it, id) } }
    }
    val geometry = remember(tree) { tree?.let { tileGeometry(it, THUMB_VIRTUAL_WIDTH, THUMB_VIRTUAL_HEIGHT, LOG_VIEW_TILE_SPEC) } }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            Modifier
                .size(THUMB_WIDTH_DP.dp, THUMB_HEIGHT_DP.dp)
                .clip(CORNER_MD)
                .background(if (active) tc.ac.copy(alpha = 0.12f) else Color.Transparent)
                .border(1.dp, if (active) tc.ac else tc.br, CORNER_MD)
                .testTag("layout-menu-preset-${preset.name}")
                .semantics {
                    contentDescription = preset.label
                    selected = active
                }
                .clickable(role = Role.Button, onClick = onClick)
                .padding(3.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val sx = size.width / THUMB_VIRTUAL_WIDTH
                val sy = size.height / THUMB_VIRTUAL_HEIGHT
                val inset = 0.6.dp.toPx()
                geometry?.cards?.forEach { card ->
                    val r = card.rect
                    drawRoundRect(
                        color = fill.copy(alpha = if (card.section.isLogTile) THUMB_LOG_FILL_ALPHA else THUMB_FILL_ALPHA),
                        topLeft = Offset(r.x * sx + inset, r.y * sy + inset),
                        size = Size((r.w * sx - 2 * inset).coerceAtLeast(1f), (r.h * sy - 2 * inset).coerceAtLeast(1f)),
                        cornerRadius = CornerRadius(1.dp.toPx()),
                    )
                }
            }
        }
        Box(Modifier.width(THUMB_WIDTH_DP.dp), contentAlignment = Alignment.Center) {
            AppText(preset.label, color = if (active) tc.ac else tc.ts, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
