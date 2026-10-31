package com.indagium.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
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
import com.indagium.model.CustomLogViewLayout
import com.indagium.model.DEFAULT_HOME_OPEN_ACTIONS_HEIGHT_DP
import com.indagium.model.HomeSectionId
import com.indagium.model.LogTab
import com.indagium.model.LogViewScope
import com.indagium.model.LogViewSectionId
import com.indagium.model.LogViewTileGroup
import com.indagium.model.TileNode

// The Layout menu: a dashboard button in the tab strip (just before Settings) that opens a popup with the five single-view presets (the two
// compare presets in compare mode) as thumbnails, the user's saved layouts as a second row of thumbnails (right-click for Rename / Export /
// Delete, a dashed "+" saves the current view; they wrap onto further rows), one strip naming the current layout (a saved one offers
// Update while Modified, Revert, Rename…, Export… and Delete…; a preset offers Revert and "Save as new…"), a checklist of the panels
// that currently apply and a "Lock tiles" switch. The menu scrolls with the app's thin scrollbar when it is taller than the space. The
// button carries a small warning dot: in the log view and compare when the layout is Modified against its preset or saved layout
// (never right after applying or saving it), on the New tab, the Tests tab and a diagram when the hidden panels differ from the default. Every click and the
// dismissal hand keyboard focus back to the root (CLAUDE.md: a clickable or a dismissed Popup steals it and never returns it).

private const val MENU_WIDTH_DP = 320
private const val LOG_LAYOUT_MENU_WIDTH_DP = 400
private const val THUMB_WIDTH_DP = 66
private const val THUMB_HEIGHT_DP = 38
private const val THUMB_VIRTUAL_WIDTH = 1200f
private const val THUMB_VIRTUAL_HEIGHT = 720f
private const val THUMB_FILL_ALPHA = 0.55f
private const val THUMB_LOG_FILL_ALPHA = 0.9f
private const val MODIFIED_DOT_DP = 7
private const val THUMB_FILTER_BAR_HEIGHT = 90f
private const val THUMB_FILTER_BAR_GAP = 36f
private const val MENU_GAP_PX = 4
private const val MENU_MAX_HEIGHT_DP = 640
private const val MENU_WINDOW_MARGIN_DP = 24
private const val MENU_MIN_HEIGHT_DP = 160

/**
 * Keeps a popup menu shorter than the window it opens in. A popup's content is measured against the window, so the
 * incoming max height is the window height: without this a 640 dp menu in a short window hangs off the bottom edge,
 * where scrolling can never bring its last rows into view. The position provider then keeps the shorter menu inside.
 */
private fun Modifier.capHeightToWindow(): Modifier = layout { measurable, constraints ->
    val maxHeight = if (constraints.hasBoundedHeight) {
        val floor = minOf(MENU_MIN_HEIGHT_DP.dp.roundToPx(), constraints.maxHeight)
        (constraints.maxHeight - MENU_WINDOW_MARGIN_DP.dp.roundToPx()).coerceAtLeast(floor)
    } else {
        constraints.maxHeight
    }
    val placeable = measurable.measure(constraints.copy(minHeight = minOf(constraints.minHeight, maxHeight), maxHeight = maxHeight))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

private const val STRIP_NAME_MAX_DP = 150

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
 * The toolbar button, with the "modified" dot. Draws nothing where the menu does not apply (see [layoutMenuTab]). In a [vertical]
 * toolbar it fills the toolbar's width, and its menu opens beside it ([menuBeside]) rather than below. In a [wrap]ping toolbar (a row of
 * buttons that flows onto more lines, with 32 dp buttons) it is a fixed-size button.
 */
@Composable
internal fun LayoutMenuButton(state: AppState, onReclaimFocus: () -> Unit, vertical: Boolean = false, wrap: Boolean = false, menuBeside: Boolean = vertical) {
    val activeTab = state.activeTab()
    val onLogSurface = state.activeSurface.let { it == null || it is ActiveSurface.Log }
    val home = activeTab?.isCaptureLauncher == true && onLogSurface
    val tests = state.activeSurface is ActiveSurface.Tests && state.testsTabOpen
    val tab = if (home) null else layoutMenuTab(state)
    val diagramSessionId = state.layoutMenuDiagramSessionId()
    val diagramView = diagramSessionId?.let(state.seq3Sessions::viewState)
    if (!home && !tests && tab == null && diagramView == null) return
    val tc = tc()
    var open by remember { mutableStateOf(false) }
    val ctx = tab?.let(state::layoutMenuContext)
    val testsLayout = if (tests) state.settings.currentTestsWorkspaceLayout() else null
    // Log view and compare: the layout no longer matches its selected preset or saved layout. Elsewhere: the hidden panels differ from
    // that surface's default (nothing hidden on the New tab and the Tests tab, the Source tile on a diagram).
    val modified = when {
        home -> state.settings.homeHiddenSections.isNotEmpty()
        testsLayout != null -> testsLayout.hidden.toSet() != testsWorkspaceDefaultState().hidden.toSet()
        diagramView != null -> diagramView.diagramHiddenTiles.toSet() != DiagramWorkspacePreset.STANDARD.hidden
        ctx?.compare == true -> state.compareLayoutModified()
        ctx != null -> state.logViewLayoutModified()
        else -> false
    }
    Box(if (vertical) Modifier.fillMaxWidth() else if (wrap) Modifier else Modifier.fillMaxHeight()) {
        ToolbarBtn(
            "",
            icon = Icons.Outlined.Dashboard,
            showLabel = false,
            tooltip = if (modified) "Layout — modified" else "Layout",
            active = open,
            modifier = when {
                vertical -> Modifier.fillMaxWidth().height(36.dp)
                wrap -> Modifier.width(36.dp).height(WRAPPED_BUTTON_HEIGHT_DP.dp)
                else -> Modifier.fillMaxHeight().width(36.dp)
            }.testTag("layout-menu-button"),
            shape = RoundedCornerShape(7.dp),
        ) { open = !open }
        if (modified) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-3).dp, y = 3.dp)
                    .size(MODIFIED_DOT_DP.dp)
                    .background(tc.warn, CircleShape)
                    .testTag("layout-menu-modified-dot")
                    .semantics { contentDescription = "Layout — modified" },
            )
        }
        if (open) {
            val dismiss = {
                open = false
                onReclaimFocus()
            }
            if (home) {
                HomeLayoutMenuPopup(state, vertical = menuBeside, onReclaimFocus = onReclaimFocus, onDismiss = dismiss)
            } else if (testsLayout != null) {
                TestsWorkspaceLayoutMenuPopup(state, testsLayout, vertical = menuBeside, onReclaimFocus = onReclaimFocus, onDismiss = dismiss)
            } else if (diagramView != null) {
                DiagramLayoutMenuPopup(state, diagramView, vertical = menuBeside, onReclaimFocus = onReclaimFocus, onDismiss = dismiss)
            } else {
                tab?.let { LayoutMenuPopup(state, it, vertical = menuBeside, onReclaimFocus = onReclaimFocus, onDismiss = dismiss) }
            }
        }
    }
}

/** Tests workspace version of the Layout menu: native tile presets, visibility, lock and reset controls. */
@Composable
private fun TestsWorkspaceLayoutMenuPopup(
    state: AppState,
    layout: TestsWorkspaceLayoutState,
    onReclaimFocus: () -> Unit,
    onDismiss: () -> Unit,
    vertical: Boolean = false,
) {
    val tc = tc()
    val shape = RoundedCornerShape(8.dp)
    val visible = testsWorkspaceVisibleTiles(layout)
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
                TestsWorkspacePreset.entries.chunked(2).forEach { rowPresets ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowPresets.forEach { preset ->
                            TestsWorkspacePresetThumb(preset, active = testsWorkspacePresetActive(layout, preset)) {
                                state.updateSettings { it.withTestsWorkspaceLayout(applyTestsWorkspacePreset(preset)) }
                                state.testsView.maximizedTile = null
                                onReclaimFocus()
                            }
                        }
                    }
                }
            }
            Divider()
            MenuLabel("Panels")
            TestsWorkspaceTileId.entries.forEach { id ->
                val isVisible = id in visible
                CheckRow(
                    checked = isVisible,
                    onToggle = {
                        state.updateSettings { settings ->
                            settings.withTestsWorkspaceLayout(settings.currentTestsWorkspaceLayout().withTileVisible(id, !isVisible))
                        }
                        if (isVisible && state.testsView.maximizedTile == id) state.testsView.maximizedTile = null
                        onReclaimFocus()
                    },
                    modifier = Modifier.testTag("tests-layout-menu-panel-${id.key}"),
                    enabled = id != TestsWorkspaceTileId.WORKSPACE && (!isVisible || visible.size > 1),
                ) {
                    AppText(testsWorkspaceTileTitle(id), color = tc.tx, fontSize = 12.sp)
                }
            }
            CheckRow(
                checked = layout.locked,
                onToggle = {
                    state.updateSettings { it.withTestsWorkspaceLayout(layout.copy(locked = !layout.locked)) }
                    onReclaimFocus()
                },
                modifier = Modifier.testTag("tests-layout-menu-lock"),
            ) { AppText("Lock tile dragging", color = tc.tx, fontSize = 12.sp) }
            Divider()
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) {
                    state.updateSettings { it.withTestsWorkspaceLayout(testsWorkspaceDefaultState()) }
                    state.testsView.maximizedTile = null
                    onReclaimFocus()
                }.padding(horizontal = 12.dp, vertical = 8.dp).testTag("tests-layout-menu-reset"),
            ) {
                AppText("Reset to Standard", color = tc.ac, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun TestsWorkspacePresetThumb(preset: TestsWorkspacePreset, active: Boolean, onClick: () -> Unit) {
    val tc = tc()
    val fill = if (active) tc.ac else tc.td
    val geometry = remember(preset) {
        tileGeometry(preset.layout, THUMB_VIRTUAL_WIDTH, THUMB_VIRTUAL_HEIGHT, TESTS_WORKSPACE_CHROME_SPEC.geometrySpec())
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            Modifier
                .size(THUMB_WIDTH_DP.dp, THUMB_HEIGHT_DP.dp)
                .clip(CORNER_MD)
                .background(if (active) tc.ac.copy(alpha = 0.12f) else Color.Transparent)
                .border(1.dp, if (active) tc.ac else tc.br, CORNER_MD)
                .testTag("tests-workspace-preset-${preset.name}")
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

/**
 * The popup itself: presets, saved layouts, the current-layout strip, Panels, Lock tiles. [onDismiss] must also reclaim focus;
 * [onReclaimFocus] follows every click.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    var saveLayoutDialog by remember { mutableStateOf(false) }
    var deleteLayoutRequest by remember { mutableStateOf<CustomLogViewLayout?>(null) }
    var renameLayoutRequest by remember { mutableStateOf<CustomLogViewLayout?>(null) }
    // The saved layout whose right-click actions (Rename…, Export…, Delete…) are showing under the thumbnails.
    var actionsLayoutId by remember { mutableStateOf<String?>(null) }
    val ctx = state.layoutMenuContext(tab)
    val compare = ctx.compare
    val settings = state.settings
    val hidden = if (compare) settings.compareHidden else settings.logViewHidden
    Popup(
        popupPositionProvider = remember(vertical) { LayoutMenuPositionProvider(vertical) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        ScrollColumnWithBar(
            Modifier.capHeightToWindow().width((if (compare) MENU_WIDTH_DP else LOG_LAYOUT_MENU_WIDTH_DP).dp)
                .heightIn(max = MENU_MAX_HEIGHT_DP.dp)
                .background(tc.p, shape).border(1.dp, tc.br, shape).testTag("layout-menu-popup"),
            contentPadding = PaddingValues(vertical = 8.dp),
            // The rows carry their own 12 dp side padding, which leaves room for the 6 dp thumb.
            endGutter = 0.dp,
            scrollbarTag = "layout-menu-scrollbar",
        ) {
            val selectedCustom = if (compare) null else settings.customLogViewLayouts.firstOrNull { it.id == settings.lastCustomLogViewLayoutId }
            MenuLabel("Layout presets")
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogViewLayoutPreset.forScope(if (compare) LogViewScope.COMPARE else LogViewScope.SINGLE).forEach { preset ->
                    val presetActive = if (compare) state.comparePresetActive(preset) else state.logViewPresetActive(preset) && selectedCustom == null
                    PresetThumb(preset, active = presetActive) {
                        if (compare) state.applyComparePreset(preset) else state.applyLogViewPreset(preset)
                        actionsLayoutId = null
                        onReclaimFocus()
                    }
                }
            }
            if (!compare) {
                MenuLabel("Saved layouts")
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    settings.customLogViewLayouts.forEach { layout ->
                        val isSelected = settings.lastCustomLogViewLayoutId == layout.id
                        val isActive = isSelected && state.matchesCustomLogViewLayout(layout)
                        SavedLayoutThumb(
                            layout,
                            active = isActive,
                            modified = isSelected && !isActive,
                            onClick = {
                                state.applySavedLogViewLayout(layout.id)
                                actionsLayoutId = null
                                onReclaimFocus()
                            },
                            onSecondaryClick = {
                                actionsLayoutId = layout.id.takeUnless { it == actionsLayoutId }
                                onReclaimFocus()
                            },
                        )
                    }
                    AddLayoutThumb {
                        saveLayoutDialog = true
                    }
                }
                settings.customLogViewLayouts.firstOrNull { it.id == actionsLayoutId }?.let { layout ->
                    SavedLayoutActions(
                        layout,
                        onRename = {
                            renameLayoutRequest = layout
                            actionsLayoutId = null
                            onReclaimFocus()
                        },
                        onExport = {
                            state.exportLogViewLayout(layout)
                            actionsLayoutId = null
                            onReclaimFocus()
                        },
                        onDelete = {
                            deleteLayoutRequest = layout
                            actionsLayoutId = null
                            onReclaimFocus()
                        },
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    AccentLink("Import…", onClick = {
                        state.importLogViewLayout()
                        onReclaimFocus()
                    }, modifier = Modifier.testTag("layout-menu-import"))
                }
                CurrentLayoutStrip(
                    state = state,
                    selectedCustom = selectedCustom,
                    onSaveAsNew = { saveLayoutDialog = true },
                    onRename = { layout ->
                        renameLayoutRequest = layout
                        actionsLayoutId = null
                        onReclaimFocus()
                    },
                    onExport = { layout ->
                        state.exportLogViewLayout(layout)
                        actionsLayoutId = null
                        onReclaimFocus()
                    },
                    onDelete = { layout ->
                        deleteLayoutRequest = layout
                        actionsLayoutId = null
                        onReclaimFocus()
                    },
                    onReclaimFocus = onReclaimFocus,
                )
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
            if (compare) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) {
                            state.resetCompareLayout()
                            onReclaimFocus()
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("layout-menu-reset"),
                ) { AppText("Reset to ${state.lastComparePresetOrGrid().label}", color = tc.ac, fontSize = 12.sp) }
            }
        }
        if (saveLayoutDialog) {
            WorkspaceProfileNameDialog(
                title = "Save current log layout",
                confirmLabel = "Save",
                initialName = uniqueLogViewLayoutName("My layout", state.customLogViewLayoutNames()),
                takenNames = state.customLogViewLayoutNames(),
                noun = "layout",
                onConfirm = { name ->
                    state.saveCurrentAsLogViewLayout(name)
                    saveLayoutDialog = false
                    onReclaimFocus()
                },
                onDismiss = {
                    saveLayoutDialog = false
                    onReclaimFocus()
                },
            )
        }
        renameLayoutRequest?.let { layout ->
            WorkspaceProfileNameDialog(
                title = "Rename saved layout",
                confirmLabel = "Rename",
                initialName = layout.name,
                takenNames = state.customLogViewLayoutNames() - layout.name,
                noun = "layout",
                onConfirm = { name ->
                    state.renameSavedLogViewLayout(layout.id, name)
                    renameLayoutRequest = null
                    onReclaimFocus()
                },
                onDismiss = {
                    renameLayoutRequest = null
                    onReclaimFocus()
                },
            )
        }
        deleteLayoutRequest?.let { layout ->
            SettingsConfirmDialog(
                title = "Delete saved layout?",
                message = "Delete “${layout.name}”? This only removes the saved layout.",
                onDismissRequest = {
                    deleteLayoutRequest = null
                    onReclaimFocus()
                },
            ) {
                DialogActionButton("Delete", active = true, danger = true) {
                    state.deleteSavedLogViewLayout(layout.id)
                    deleteLayoutRequest = null
                    onReclaimFocus()
                }
                DialogActionButton("Cancel", active = false) {
                    deleteLayoutRequest = null
                    onReclaimFocus()
                }
            }
        }
    }
}

/**
 * One strip under the thumbnails naming the layout the view started from ("‹name›", plus "· Modified" in the warning colour once the
 * view no longer matches it). A saved layout offers Update (write the view into it; only while Modified), Revert, Rename…, Export… and
 * Delete…; a preset offers Revert and "Save as new…". Revert is always available, so tile sizes (which the preset match ignores) can still
 * be put back. The actions wrap onto a second line when the menu is too narrow for all of them next to the name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrentLayoutStrip(
    state: AppState,
    selectedCustom: CustomLogViewLayout?,
    onSaveAsNew: () -> Unit,
    onRename: (CustomLogViewLayout) -> Unit,
    onExport: (CustomLogViewLayout) -> Unit,
    onDelete: (CustomLogViewLayout) -> Unit,
    onReclaimFocus: () -> Unit,
) {
    val tc = tc()
    val preset = state.lastLogViewPresetOrClassic()
    val name = selectedCustom?.name ?: preset.label
    val modified = state.logViewLayoutModified()
    val stripShape = RoundedCornerShape(6.dp)
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .background(if (modified) tc.warnBg else tc.p2, stripShape)
            .padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp)
            .testTag("layout-menu-strip"),
        verticalArrangement = Arrangement.Center,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AppText(
                name, color = tc.tx, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = STRIP_NAME_MAX_DP.dp),
            )
            if (modified) {
                AppText(" · Modified", color = tc.warn, fontSize = 11.sp, maxLines = 1, modifier = Modifier.testTag("layout-menu-modified-label"))
            }
        }
        if (selectedCustom != null && modified) {
            AppButton(
                "Update",
                onClick = {
                    state.updateSavedLogViewLayoutFromCurrent(selectedCustom.id)
                    onReclaimFocus()
                },
                variant = ButtonVariant.Ghost,
                horizontalPadding = 7.dp,
                tooltip = "Update ${selectedCustom.name} with the current view",
                modifier = Modifier.height(24.dp).testTag("layout-menu-update-existing"),
                textColor = tc.ac,
            )
        }
        AppButton(
            "Revert",
            onClick = {
                state.resetLogViewLayout()
                onReclaimFocus()
            },
            variant = ButtonVariant.Ghost,
            horizontalPadding = 7.dp,
            tooltip = "Put the layout back to ${state.lastLogViewResetLabel()}",
            modifier = Modifier.height(24.dp).testTag("layout-menu-reset"),
            textColor = tc.ac,
        )
        if (selectedCustom == null) {
            AppButton(
                "Save as new…",
                onClick = onSaveAsNew,
                variant = ButtonVariant.Ghost,
                horizontalPadding = 7.dp,
                modifier = Modifier.height(24.dp).testTag("layout-menu-save-as-new"),
                textColor = tc.ac,
            )
        } else {
            AppButton(
                "Rename…",
                onClick = { onRename(selectedCustom) },
                variant = ButtonVariant.Ghost,
                horizontalPadding = 7.dp,
                modifier = Modifier.height(24.dp).testTag("layout-menu-strip-rename"),
                textColor = tc.ac,
            )
            AppButton(
                "Export…",
                onClick = { onExport(selectedCustom) },
                variant = ButtonVariant.Ghost,
                horizontalPadding = 7.dp,
                modifier = Modifier.height(24.dp).testTag("layout-menu-strip-export"),
                textColor = tc.ac,
            )
            AppButton(
                "Delete…",
                onClick = { onDelete(selectedCustom) },
                variant = ButtonVariant.Ghost,
                horizontalPadding = 7.dp,
                modifier = Modifier.height(24.dp).testTag("layout-menu-strip-delete"),
                textColor = DANGER_RED,
            )
        }
    }
}

/** The right-click actions of one saved layout, shown as a row under the thumbnails. */
@Composable
private fun SavedLayoutActions(layout: CustomLogViewLayout, onRename: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).testTag("layout-menu-actions-${layout.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(layout.name, color = tc.ts, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        AccentLink("Rename…", onClick = onRename, modifier = Modifier.testTag("layout-menu-rename-${layout.id}"))
        AccentLink("Export…", onClick = onExport, modifier = Modifier.testTag("layout-menu-export-${layout.id}"))
        HoverBox(modifier = Modifier.clip(RoundedCornerShape(4.dp)).testTag("layout-menu-delete-${layout.id}"), onClick = onDelete) {
            Box(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) { AppText("Delete…", color = DANGER_RED, fontSize = 11.sp, fontFamily = UI) }
        }
    }
}

@Composable
private fun MenuLabel(text: String) {
    AppText(text, color = tc().td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
}

// A layout's arrangement as a thumbnail: the tree minus its hidden tiles laid out on a virtual host with the real geometry rules (so
// pinned columns and Log level keep their proportions), scaled down; the log tile is drawn stronger, and a thin bar above it when the
// layout shows the filter bar. Presets and saved layouts share this drawing; so does Settings › General's Layout row.

/** The tiles a preset shows, as a tree for [LayoutThumbCanvas]; null when nothing is left. */
internal fun presetThumbTree(preset: LogViewLayoutPreset): TileNode<LogViewSectionId>? =
    preset.hidden.fold(preset.layout(LogViewLegacySizes()) as LogViewNode?) { acc, id -> acc?.let { removeTile(it, id) } }

/** The tiles a saved layout shows: its tree minus the hidden list, the tiles of a switched-off panel group and the toolbar. */
internal fun savedLayoutThumbTree(layout: CustomLogViewLayout): TileNode<LogViewSectionId>? {
    val groupOff = LogViewSectionId.entries.filter {
        when (it.group) {
            LogViewTileGroup.NONE -> false
            LogViewTileGroup.FILTERS -> !layout.filterVisible
            LogViewTileGroup.NOTES -> !layout.annotationVisible
            LogViewTileGroup.AI -> !layout.aiPanelVisible
            LogViewTileGroup.VIDEO -> !layout.videoPanelVisible
        }
    }
    val compareOnly = LogViewSectionId.entries.filter { it.scope == LogViewScope.COMPARE }
    return (layout.hidden + groupOff + compareOnly + LogViewSectionId.TOOLBAR).distinct()
        .fold(layout.tree as LogViewNode?) { acc, id -> acc?.let { removeTile(it, id) } }
}

/** The thumbnail drawing alone (no border or label): [tree]'s cards filled with [fill], and the filter bar above the log when [filterBar]. */
@Composable
internal fun LayoutThumbCanvas(tree: TileNode<LogViewSectionId>?, filterBar: Boolean, fill: Color, modifier: Modifier = Modifier) {
    val geometry = remember(tree) { tree?.let { tileGeometry(it, THUMB_VIRTUAL_WIDTH, THUMB_VIRTUAL_HEIGHT, LOG_VIEW_TILE_SPEC) } }
    Canvas(modifier) {
        val sx = size.width / THUMB_VIRTUAL_WIDTH
        val sy = size.height / THUMB_VIRTUAL_HEIGHT
        val inset = 0.6.dp.toPx()

        fun card(x: Float, y: Float, w: Float, h: Float, alpha: Float) = drawRoundRect(
            color = fill.copy(alpha = alpha),
            topLeft = Offset(x * sx + inset, y * sy + inset),
            size = Size((w * sx - 2 * inset).coerceAtLeast(1f), (h * sy - 2 * inset).coerceAtLeast(1f)),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
        geometry?.cards?.forEach { c ->
            val r = c.rect
            if (filterBar && c.section == LogViewSectionId.LOG) {
                card(r.x, r.y, r.w, THUMB_FILTER_BAR_HEIGHT, THUMB_FILL_ALPHA)
                val shift = THUMB_FILTER_BAR_HEIGHT + THUMB_FILTER_BAR_GAP
                card(r.x, r.y + shift, r.w, (r.h - shift).coerceAtLeast(1f), THUMB_LOG_FILL_ALPHA)
            } else {
                card(r.x, r.y, r.w, r.h, if (c.section.isLogTile) THUMB_LOG_FILL_ALPHA else THUMB_FILL_ALPHA)
            }
        }
    }
}

// The framed, labelled, clickable thumbnail the Layout menu's rows are made of. [active] fills it with the accent colour; [outlined]
// gives a selected-but-modified one the accent border without the fill; [modifiedDotTag] draws the small warning dot.
@Composable
private fun LayoutThumb(
    label: String,
    tag: String,
    tree: TileNode<LogViewSectionId>?,
    filterBar: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    outlined: Boolean = false,
    modifiedDotTag: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
) {
    val tc = tc()
    val fill = if (active || outlined) tc.ac else tc.td
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.size(THUMB_WIDTH_DP.dp, THUMB_HEIGHT_DP.dp)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CORNER_MD)
                    .background(if (active) tc.ac.copy(alpha = 0.12f) else Color.Transparent)
                    .border(1.dp, if (active || outlined) tc.ac else tc.br, CORNER_MD)
                    .testTag(tag)
                    .semantics {
                        contentDescription = label
                        selected = active
                    }
                    .then(if (onSecondaryClick == null) Modifier else Modifier.secondaryClick(onSecondaryClick))
                    .clickable(role = Role.Button, onClick = onClick)
                    .padding(3.dp),
            ) { LayoutThumbCanvas(tree, filterBar, fill, Modifier.fillMaxSize()) }
            if (modifiedDotTag != null) {
                // A sibling of the clickable thumbnail (not inside it) so the dot keeps its own semantics node.
                Box(
                    Modifier.align(Alignment.TopEnd).padding(2.dp).size(MODIFIED_DOT_DP.dp).background(tc.warn, CircleShape)
                        .testTag(modifiedDotTag)
                        .semantics { contentDescription = "Modified saved layout" },
                )
            }
        }
        Box(Modifier.width(THUMB_WIDTH_DP.dp), contentAlignment = Alignment.Center) {
            AppText(label, color = if (active) tc.ac else tc.ts, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PresetThumb(preset: LogViewLayoutPreset, active: Boolean, onClick: () -> Unit) {
    val tree = remember(preset) { presetThumbTree(preset) }
    LayoutThumb(preset.label, "layout-menu-preset-${preset.name}", tree, preset.showsFilterBar, active, onClick)
}

/** A saved layout's thumbnail. [active]: the view matches it; [modified]: it is the selected layout but the view has moved on. */
@Composable
private fun SavedLayoutThumb(
    layout: CustomLogViewLayout,
    active: Boolean,
    modified: Boolean,
    onClick: () -> Unit,
    onSecondaryClick: () -> Unit,
) {
    val tree = remember(layout) { savedLayoutThumbTree(layout) }
    Box(Modifier.testTag("layout-menu-custom-${layout.id}")) {
        LayoutThumb(
            label = layout.name,
            tag = "layout-menu-apply-${layout.id}",
            tree = tree,
            filterBar = layout.filterBarVisible == true,
            active = active,
            onClick = onClick,
            outlined = modified,
            modifiedDotTag = if (modified) "layout-menu-status-${layout.id}" else null,
            onSecondaryClick = onSecondaryClick,
        )
    }
}

/** The dashed "+" thumbnail that saves the current view as a new layout. */
@Composable
private fun AddLayoutThumb(onClick: () -> Unit) {
    val tc = tc()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            Modifier
                .size(THUMB_WIDTH_DP.dp, THUMB_HEIGHT_DP.dp)
                .clip(CORNER_MD)
                .testTag("layout-menu-save-current")
                .semantics { contentDescription = "Save current layout" }
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(
                    color = tc.td,
                    cornerRadius = CornerRadius(6.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            AppText("+", color = tc.td, fontSize = 18.sp)
        }
        Box(Modifier.width(THUMB_WIDTH_DP.dp), contentAlignment = Alignment.Center) {
            AppText("Save current…", color = tc.ts, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// Calls [onClick] on a secondary-button press and consumes it, leaving the primary click alone.
private fun Modifier.secondaryClick(onClick: () -> Unit): Modifier = pointerInput(onClick) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                onClick()
            }
        }
    }
}
