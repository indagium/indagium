@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.openlog.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.LabelOff
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.openlog.generated.BuildInfo
import com.openlog.model.*
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import kotlin.math.roundToInt

private const val TAB_DRAG_SNAP_BIAS = 0.25f

@Composable
fun App(state: AppState = remember { AppState(restoreOnCreate = true) }) {
    val theme = themeColors(state.settings.theme)
    val rootFocusRequester = remember { FocusRequester() }
    var pendingPanelFocus by remember { mutableStateOf<KeyboardPanel?>(null) }
    var filterSearchFocusRequest by remember { mutableStateOf(0) }

    CompositionLocalProvider(
        LocalTheme provides theme,
        LocalFontBase provides state.settings.fontSize,
        LocalUseMono provides state.settings.fontMono,
    ) {
        val tc = tc()
        LaunchedEffect(
            state.tabs,
            state.savedFilters,
            state.settings,
            state.activeSavedFilterIds,
            state.recentFiles,
            state.recentNotes,
        ) {
            kotlinx.coroutines.delay(400)
            state.autosaveNow()
        }
        LaunchedEffect(Unit) {
            runCatching { rootFocusRequester.requestFocus() }
        }
        val dropTarget = remember {
            object : DragAndDropTarget {
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val files = runCatching {
                        (event.dragData() as DragData.FilesList).readFiles()
                    }.getOrElse { return false }
                    files.forEach { uri ->
                        runCatching {
                            val file = File(URI.create(uri))
                            if (file.exists() && com.openlog.utils.isLikelyTextFile(file)) {
                                state.openFile(file)
                            }
                        }
                    }
                    return true
                }
            }
        }
        val dc by dragCursorOverride
        Box(
            Modifier.fillMaxSize().background(tc.bg)
                .dragAndDropTarget(
                    shouldStartDragAndDrop = { event ->
                        runCatching { event.dragData() is DragData.FilesList }.getOrElse { false }
                    },
                    target = dropTarget,
                )
                .then(
                    if (dc != null) Modifier.pointerHoverIcon(
                        PointerIcon(dc!!),
                        overrideDescendants = true
                    ) else Modifier
                )
                .focusRequester(rootFocusRequester)
                .focusable()
                // Any mouse click anywhere hides the keyboard focus-visible outline; panel
                // focus shortcuts below turn it back on. Runs on the Initial pass so it fires
                // before the click's own focus side effects.
                .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) {
                    state.keyboardFocusVisible = false
                }
                .onPreviewKeyEvent { ev ->
                    handleGlobalKey(
                        ev = ev,
                        state = state,
                        onFocusPanel = { panel -> state.keyboardFocusVisible = true; pendingPanelFocus = panel },
                        onFocusFilterSearch = {
                            state.keyboardFocusVisible = true
                            state.updateFilterVisible(true)
                            pendingPanelFocus = KeyboardPanel.FILTERS
                            filterSearchFocusRequest += 1
                        },
                    )
                }
        ) {
            Column(Modifier.fillMaxSize()) {
                TabBar(state)
                val activeTab = state.activeTab()
                when {
                    state.tabs.isEmpty() ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            AppText("No files open — click Open to add a log", color = tc.ts, fontSize = 14.sp)
                        }

                    state.compareMode -> CompareView(
                        state = state,
                        requestedPanelFocus = pendingPanelFocus,
                        filterSearchFocusRequest = filterSearchFocusRequest,
                        onPanelFocusConsumed = { pendingPanelFocus = null },
                    )
                    activeTab != null -> key(activeTab.id) {
                        FileView(
                            state = state,
                            tab = activeTab,
                            requestedPanelFocus = pendingPanelFocus,
                            filterSearchFocusRequest = filterSearchFocusRequest,
                            onPanelFocusConsumed = { pendingPanelFocus = null },
                        )
                    }
                }
            }

            // ── Loading overlay ───────────────────────────────────────
            if (state.isLoading) {
                Box(
                    Modifier.fillMaxSize().background(tc.bg.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center,
                ) {
                    AppText("Loading file…", color = tc.ts, fontSize = 14.sp)
                }
            }

            // ── Context menu ──────────────────────────────────────────
            state.ctx?.let { ctx ->
                val ctxTab = state.tab(ctx.tabId)
                val entry = ctxTab?.rmap?.get(ctx.entryId)
                // Transparent backdrop — no indication (shadow) added
                BoxWithConstraints(
                    Modifier.fillMaxSize().clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { state.ctx = null },
                    )
                ) {
                    if (entry != null) {
                        val menuWidth = 270.dp
                        // panelSelectedIds is non-empty when the right-click came from a panel
                        // with its own local selection (e.g. the "Original" unfiltered panel).
                        val selectedIds = ctx.panelSelectedIds.ifEmpty { ctxTab.selected }
                        val selCount = selectedIds.size
                        // Estimate full menu height from items that will actually render:
                        //   header(37) + divider(9) + preview(63) + 5 items(160) + divider(9)
                        //   + 3 items(96) + divider(9) + 2 items(64) = 447
                        // Selection text adds preview extension(15) + 4 items(128) + divider(9) = 152
                        val estimatedMenuHeight = (447 +
                            (if (ctx.selText.isNotBlank()) 152 else 0) +
                            (if (state.pendingSequenceStart != null) 32 else 0) +
                            (if (selCount > 1) 32 else 0)).dp
                        val menuScroll = rememberScrollState()
                        val x = ctx.x.dp.coerceIn(8.dp, (maxWidth - menuWidth - 8.dp).coerceAtLeast(8.dp))
                        val y = ctx.y.dp.coerceIn(8.dp, (maxHeight - estimatedMenuHeight - 8.dp).coerceAtLeast(8.dp))

                        val menuEntries = buildList {
                            add(
                                CtxMenuEntry.ActionHeader(
                                    if (selCount > 1) "Add annotation for $selCount lines" else "Add annotation",
                                ) {
                                    val ids = if (selCount > 1) selectedIds.toSortedSet().toList() else listOf(ctx.entryId)
                                    state.requestAddAnn(ctx.tabId, ids)
                                },
                            )
                            add(CtxMenuEntry.Divider)
                            add(CtxMenuEntry.Preview)
                            if (ctx.selText.isNotBlank()) {
                                add(CtxMenuEntry.Action(Icons.Outlined.Bookmark, "Highlight selection") { state.addHlFromCtx() })
                                add(CtxMenuEntry.Action(Icons.Outlined.Layers, "Add as sequence") { state.addSeqFromCtx() })
                                add(CtxMenuEntry.Action(Icons.Outlined.Search, "Filter by keyword") { state.addKwFilterFromCtx() })
                                add(CtxMenuEntry.Action(Icons.Outlined.Block, "Exclude keyword") { state.addNegKwFromCtx() })
                                add(CtxMenuEntry.Divider)
                            }
                            if (state.pendingSequenceStart != null) {
                                add(CtxMenuEntry.Action(Icons.Outlined.Flag, "Complete sequence end") { state.completeSequenceEndFromCtx() })
                            }
                            add(CtxMenuEntry.Action(Icons.Outlined.PlayArrow, "Set sequence start") { state.setSequenceStartFromCtx() })
                            add(CtxMenuEntry.Action(Icons.Outlined.ArrowUpward, "Collapse to file start") { state.collapseToStartFromCtx() })
                            add(CtxMenuEntry.Action(Icons.Outlined.ArrowDownward, "Collapse to file end") { state.collapseToEndFromCtx() })
                            add(CtxMenuEntry.Action(Icons.Outlined.VisibilityOff, "Hide messages like this") { state.hideMessagesLikeCtx() })
                            add(CtxMenuEntry.Action(Icons.Outlined.Visibility, "Show messages like this") { state.showOnlyMessagesLikeCtx() })
                            add(CtxMenuEntry.Divider)
                            add(CtxMenuEntry.Action(Icons.AutoMirrored.Outlined.Label, "Include tag") { state.addTagFilterFromCtx() })
                            add(CtxMenuEntry.Action(Icons.Outlined.Bookmark, "Highlight tag") { state.addHlTagFromCtx() })
                            add(CtxMenuEntry.Action(Icons.AutoMirrored.Outlined.LabelOff, "Exclude tag") { state.addExcludeTagFromCtx() })
                            add(CtxMenuEntry.Divider)
                            if (selCount > 1) {
                                add(
                                    CtxMenuEntry.Action(Icons.Outlined.ContentCopy, "Copy $selCount selected lines") {
                                        state.copySelectedLines(ctx.tabId); state.ctx = null
                                    },
                                )
                            }
                            add(
                                CtxMenuEntry.Action(Icons.Outlined.ContentCopy, "Copy line") {
                                    val pid = if (entry.pid > 0) "  ${entry.pid.toString().padStart(5)} ${
                                        entry.tid.toString().padStart(5)
                                    }" else ""
                                    state.copyToClipboard("${entry.ts}$pid  ${entry.level.key}  ${entry.tag}: ${entry.msg}")
                                    state.ctx = null
                                },
                            )
                            add(
                                CtxMenuEntry.Action(Icons.Outlined.Code, "Copy as Markdown") {
                                    state.copyToClipboard("**[${entry.ts}] `${entry.level.key}/${entry.tag}`:** ${entry.msg}")
                                    state.ctx = null
                                },
                            )
                        }
                        val selectableEntries = menuEntries.filter {
                            it is CtxMenuEntry.ActionHeader || it is CtxMenuEntry.Action
                        }
                        var selectedIdx by remember(ctx) { mutableStateOf(0) }
                        val selectedEntry = selectableEntries.getOrNull(selectedIdx)
                        val menuFr = remember(ctx) { FocusRequester() }
                        LaunchedEffect(ctx) { runCatching { menuFr.requestFocus() } }

                        // Position is already in dp (converted from px in LogRow)
                        Box(
                            Modifier
                                .offset(x = x, y = y)
                                .shadow(8.dp, RoundedCornerShape(7.dp))
                                .background(tc.p, RoundedCornerShape(7.dp))
                                .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                                .width(menuWidth)
                                .heightIn(max = (maxHeight - y - 8.dp).coerceAtLeast(160.dp))
                                .verticalScroll(menuScroll)
                                .focusRequester(menuFr)
                                .focusable()
                                .onPreviewKeyEvent { ev ->
                                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (ev.key) {
                                        Key.DirectionDown -> {
                                            selectedIdx = (selectedIdx + 1).mod(selectableEntries.size.coerceAtLeast(1))
                                            true
                                        }
                                        Key.DirectionUp -> {
                                            selectedIdx = (selectedIdx - 1).mod(selectableEntries.size.coerceAtLeast(1))
                                            true
                                        }
                                        Key.Enter, Key.NumPadEnter -> {
                                            selectedEntry?.let {
                                                when (it) {
                                                    is CtxMenuEntry.ActionHeader -> it.onClick()
                                                    is CtxMenuEntry.Action -> it.onClick()
                                                    else -> {}
                                                }
                                            }
                                            true
                                        }
                                        Key.Escape -> { state.ctx = null; true }
                                        else -> false
                                    }
                                },
                        ) {
                            Column {
                                menuEntries.forEach { e ->
                                    when (e) {
                                        is CtxMenuEntry.ActionHeader -> {
                                            HoverBox(
                                                modifier = Modifier.fillMaxWidth(),
                                                baseBg = tc.p2,
                                                forceHover = e === selectedEntry,
                                                onClick = e.onClick,
                                            ) {
                                                Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                                    AppText(e.label, color = tc.ac, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                        }
                                        is CtxMenuEntry.Action ->
                                            CtxItem(e.icon, e.label, highlighted = e === selectedEntry, onClick = e.onClick)
                                        CtxMenuEntry.Divider -> CtxDivider()
                                        CtxMenuEntry.Preview -> Column(
                                            Modifier.fillMaxWidth()
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                                .background(tc.p2, CORNER_MD)
                                                .border(BorderStroke(0.5.dp, tc.br.copy(alpha = 0.5f)), CORNER_MD)
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                        ) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                LevelBadge(entry.level)
                                                AppText(
                                                    entry.tag, color = tc.td, fontSize = 10.sp, fontFamily = MONO,
                                                    modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Spacer(Modifier.height(2.dp))
                                            AppText(
                                                entry.msg,
                                                color = tc.ts,
                                                fontSize = 10.sp,
                                                fontFamily = MONO,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (ctx.selText.isNotBlank()) {
                                                Spacer(Modifier.height(2.dp))
                                                AppText("Selected: \"${ctx.selText}\"", color = tc.ac, fontSize = 9.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Add annotation dialog ─────────────────────────────────
            state.addAnnRequest?.let { req ->
                val rows = req.logIds.mapNotNull { state.tab(req.sourceTabId)?.rmap?.get(it) }
                Dialog(onDismissRequest = { state.addAnnRequest = null }) {
                    AddAnnDialog(
                        rows = rows,
                        sourceFilename = req.sourceFilename,
                        onConfirm = { caption ->
                            state.confirmAddAnn(
                                req.targetTabId,
                                req.sourceTabId,
                                req.logIds,
                                caption,
                                req.sourceFilename
                            )
                        },
                        onDismiss = { state.addAnnRequest = null },
                    )
                }
            }

            // ── Save filter dialog ────────────────────────────────────
            if (state.sfDialog) {
                Dialog(onDismissRequest = { state.sfDialog = false }) {
                    val tc2 = tc()
                    Column(
                        Modifier.width(340.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText(
                            "Save filter preset",
                            color = tc2.tx,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(5.dp))
                        AppText(
                            "Saves: levels · tags · keyword · highlighters · sequences",
                            color = tc2.td, fontSize = 11.sp, maxLines = 2
                        )
                        Spacer(Modifier.height(14.dp))
                        InlineField(
                            state.sfName,
                            { state.sfName = it },
                            "Preset name…",
                            Modifier.fillMaxWidth(),
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                            DialogActionButton("Save", active = state.sfName.isNotBlank()) {
                                if (state.sfName.isNotBlank()) state.saveFilter(
                                    state.sfTabId ?: return@DialogActionButton, state.sfName
                                )
                            }
                            DialogActionButton("Cancel", active = false) { state.sfDialog = false }
                        }
                    }
                }
            }

            state.pendingDuplicateFilterSave?.let { pending ->
                Dialog(onDismissRequest = { state.cancelDuplicateFilterSave() }) {
                    val tc2 = tc()
                    Column(
                        Modifier.width(380.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText("Replace saved filter?", color = tc2.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        AppText(
                            "\"${pending.existingName}\" already exists. Replace it with the current filter settings, or cancel and save with another name.",
                            color = tc2.td,
                            fontSize = 11.sp,
                            maxLines = 3,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DialogActionButton("Replace", active = true, danger = true) { state.confirmReplaceDuplicateFilter() }
                            DialogActionButton("Cancel", active = false) { state.cancelDuplicateFilterSave() }
                        }
                    }
                }
            }

            state.pendingFilterLoad?.takeIf { !state.sfDialog }?.let { pending ->
                val current = pending.currentFilterId?.let { id -> state.savedFilters.find { it.id == id } }
                val target = state.savedFilters.find { it.id == pending.targetFilterId }
                Dialog(onDismissRequest = { state.cancelPendingFilterLoad() }) {
                    val tc2 = tc()
                    Column(
                        Modifier.width(380.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText(
                            "Save current filter changes?",
                            color = tc2.tx,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        AppText(
                            "Before loading \"${target?.name ?: "another filter"}\", save the changes to \"${current?.name ?: "current filter"}\".",
                            color = tc2.td,
                            fontSize = 11.sp,
                            maxLines = 3,
                        )
                        Spacer(Modifier.height(14.dp))
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                DialogActionButton("Save as new", active = true) { state.beginSavePendingFilterAsNew() }
                                DialogActionButton(
                                    "Update existing",
                                    active = current != null,
                                    enabled = current != null
                                ) { state.updateCurrentPresetAndLoadPending() }
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                DialogActionButton(
                                    "Do not save",
                                    active = true,
                                    danger = true
                                ) { state.discardPendingFilterChangesAndLoad() }
                                DialogActionButton("Cancel", active = false) { state.cancelPendingFilterLoad() }
                            }
                        }
                    }
                }
            }

            state.pendingClearFilterTabId?.let { tabId ->
                Dialog(onDismissRequest = { state.cancelClearFilter() }) {
                    val tc2 = tc()
                    val tabName = state.tab(tabId)?.filename ?: "current file"
                    Column(
                        Modifier.width(380.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText("Clear filters?", color = tc2.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        AppText(
                            "Reset levels, tags, keyword rules, highlighters, message rules, and the active preset for \"$tabName\".",
                            color = tc2.td,
                            fontSize = 11.sp,
                            maxLines = 3,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DialogActionButton(
                                "Clear filters",
                                active = true,
                                danger = true
                            ) { state.confirmClearFilter() }
                            DialogActionButton("Cancel", active = false) { state.cancelClearFilter() }
                        }
                    }
                }
            }

            state.pendingDeleteFilterId?.let { filterId ->
                val filterName = state.savedFilters.find { it.id == filterId }?.name ?: "this filter"
                Dialog(onDismissRequest = { state.cancelDeleteSF() }) {
                    val tc2 = tc()
                    Column(
                        Modifier.width(360.dp).background(tc2.p, RoundedCornerShape(8.dp))
                            .border(1.dp, tc2.br, RoundedCornerShape(8.dp)).padding(20.dp),
                    ) {
                        AppText("Delete saved filter?", color = tc2.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        AppText("Delete \"$filterName\" from saved filters.", color = tc2.td, fontSize = 11.sp, maxLines = 2)
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DialogActionButton("Delete", active = true, danger = true) { state.confirmDeleteSF() }
                            DialogActionButton("Cancel", active = false) { state.cancelDeleteSF() }
                        }
                    }
                }
            }

            // ── Recent files popup ────────────────────────────────────
            if (state.recentMenuOpen && state.recentFiles.isNotEmpty()) {
                BoxWithConstraints(
                    Modifier.fillMaxSize().clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { state.recentMenuOpen = false },
                    )
                ) {
                    val menuWidth = 320.dp
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-8).dp, y = 44.dp)
                            .width(menuWidth)
                            .background(tc.p, RoundedCornerShape(7.dp))
                            .border(1.dp, tc.br, RoundedCornerShape(7.dp)),
                    ) {
                        Column {
                            Box(
                                Modifier.fillMaxWidth().background(tc.p2)
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                AppText(
                                    "Recent Files (${state.recentFiles.size})",
                                    color = tc.ts,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
                            // Show 10 items, scrollable; list stores up to 30
                            val displayFiles = state.recentFiles.take(10)
                            val listH = (displayFiles.size * 46).coerceAtMost(460).dp
                            Column(Modifier.fillMaxWidth().height(listH).verticalScroll(rememberScrollState())) {
                                displayFiles.forEach { path ->
                                    val file = File(path)
                                    val exists = file.exists()
                                    HoverBox(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { if (exists) state.openFile(file) },
                                    ) {
                                        Column(
                                            Modifier.fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 7.dp),
                                        ) {
                                            AppText(
                                                file.name,
                                                color = if (exists) tc.tx else tc.td,
                                                fontSize = 12.sp,
                                                fontFamily = MONO,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            AppText(
                                                file.parent ?: path,
                                                color = tc.td,
                                                fontSize = 10.sp,
                                                fontFamily = MONO,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                            if (state.recentFiles.size > 10) {
                                Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
                                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                                    AppText("… ${state.recentFiles.size - 10} more", color = tc.td, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }

            // ── Settings dialog ───────────────────────────────────────
            if (state.settingsOpen) {
                Dialog(onDismissRequest = { state.settingsOpen = false }) {
                    SettingsDialog(state) { state.settingsOpen = false }
                }
            }

            // ── Keyboard shortcuts dialog ─────────────────────────────
            if (state.shortcutsOpen) {
                // usePlatformDefaultWidth defaults to true and silently caps dialog content to
                // ~580dp regardless of any width modifier inside — must disable it for the
                // 3-column layout to actually get the width it asks for.
                Dialog(
                    onDismissRequest = { state.shortcutsOpen = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    KeyboardShortcutsDialog { state.shortcutsOpen = false }
                }
            }
        }
    }
}

private fun handleGlobalKey(
    ev: KeyEvent,
    state: AppState,
    onFocusPanel: (KeyboardPanel) -> Unit,
    onFocusFilterSearch: () -> Unit,
): Boolean {
    if (ev.type != KeyEventType.KeyDown) return false
    if (ev.isCtrlPressed && ev.key == Key.Tab) {
        navigateTab(state, if (ev.isShiftPressed) -1 else +1)
        return true
    }
    if (!ev.isActionKey) return false
    return when {
        ev.isShiftPressed && ev.key == Key.F -> { state.updateFilterVisible(!state.filterVisible); true }
        ev.isShiftPressed && ev.key == Key.A -> { state.updateAnnotationVisible(!state.annotationVisible); true }
        ev.isShiftPressed && ev.key == Key.D -> { state.updateCompareMode(!state.compareMode); true }
        ev.key == Key.F                      -> { onFocusFilterSearch(); true }
        ev.key == Key.One                    -> { state.updateFilterVisible(true); onFocusPanel(KeyboardPanel.FILTERS); true }
        ev.key == Key.Two                    -> { onFocusPanel(KeyboardPanel.LOG_VIEW); true }
        ev.key == Key.Three                  -> { state.updateAnnotationVisible(true); onFocusPanel(KeyboardPanel.NOTES); true }
        ev.key == Key.RightBracket           -> { navigateTab(state, +1); true }
        ev.key == Key.LeftBracket            -> { navigateTab(state, -1); true }
        ev.key == Key.W                      -> { state.activeTab()?.id?.let(state::closeTab); true }
        ev.key == Key.Slash                  -> { state.shortcutsOpen = true; true }
        else -> false
    }
}

private fun navigateTab(state: AppState, delta: Int) {
    val ids = state.tabs.map { it.id }
    val cur = ids.indexOf(state.activeTabId)
    if (cur < 0 || ids.size < 2) return
    state.activateTab(ids[(cur + delta).mod(ids.size)])
}

// ── Add annotation dialog ─────────────────────────────────────────────
@Composable
private fun AddAnnDialog(
    rows: List<LogEntry>,
    sourceFilename: String? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tc = tc()
    val mono = monoFont()
    var caption by remember { mutableStateOf("") }

    Column(
        Modifier.width(440.dp).background(tc.p, RoundedCornerShape(8.dp))
            .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppText("Add annotation", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (sourceFilename != null) {
                Box(
                    Modifier.background(tc.ac.copy(.15f), CORNER_SM)
                        .border(1.dp, tc.ac.copy(.3f), CORNER_SM)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) { AppText("from $sourceFilename", color = tc.ac, fontSize = 10.sp, fontFamily = MONO) }
            }
        }

        // Show referenced log lines
        Column(
            Modifier.fillMaxWidth().background(tc.bg, CORNER_MD)
                .border(1.dp, tc.br, CORNER_MD).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            rows.take(5).forEach { r ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LevelBadge(r.level)
                    AppText(
                        r.tag, color = tc.td, fontSize = 10.sp, fontFamily = mono,
                        modifier = Modifier.width(80.dp), overflow = TextOverflow.Ellipsis
                    )
                    AppText(
                        r.msg, color = tc.ts, fontSize = 10.sp, fontFamily = mono,
                        modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (rows.size > 5) AppText("… and ${rows.size - 5} more lines", color = tc.td, fontSize = 10.sp)
        }

        // Note / caption input
        BasicTextField(
            value = caption,
            onValueChange = { caption = it },
            textStyle = TextStyle(color = tc.tx, fontSize = 12.sp, fontFamily = FontFamily.Default, lineHeight = 18.sp),
            cursorBrush = SolidColor(tc.ac),
            modifier = Modifier.fillMaxWidth()
                .background(tc.bg, CORNER_MD)
                .border(1.dp, tc.ac.copy(.5f), CORNER_MD)
                .padding(10.dp).defaultMinSize(minHeight = 72.dp),
            decorationBox = { inner ->
                if (caption.isEmpty()) AppText("Add your analysis note here…", color = tc.td, fontSize = 12.sp)
                inner()
            },
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DialogActionButton("Add annotation", active = true) { onConfirm(caption) }
            DialogActionButton("Cancel", active = false, onClick = onDismiss)
        }
    }
}

@Composable
private fun DialogActionButton(
    label: String,
    active: Boolean,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tc = tc()
    val accent = if (danger) DANGER_RED else tc.ac
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier
            .width(132.dp)
            .height(38.dp)
            .border(1.dp, if (active) accent else tc.br, shape)
            .background(if (active) accent.copy(.18f) else Color.Transparent, shape)
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppText(label, color = if (active) accent else tc.ts, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BoundFilterPanel(
    state: AppState,
    tab: LogTab,
    focusRequester: FocusRequester? = null,
    focusSearchRequest: Int = 0,
    onPanelFocusChanged: (Boolean) -> Unit = {},
) {
    if (!state.filterVisible) return
    FilterPanel(
        tab = tab, savedFilters = state.savedFilters,
        activeSavedFilterId = state.activeSavedFilterId(tab.id),
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
        onToggleKwRx = { state.toggleKwRx(tab.id) },
        onToggleSeq = { state.toggleSeq(tab.id) },
        onAddSeq = { t, r, c, st, et, er, eg -> state.addSequence(t, r, c, st, et, er, eg) },
        onRemoveSeq = { state.removeSequence(it) },
        onToggleSeqEnabled = { state.toggleSequence(it) },
        onSetSeqColor = { id, c -> state.setSequenceColor(id, c) },
        onUpdateSeq = { id, text, rx, tag, endText, endRx, endTag ->
            state.updateSequence(id, text, rx, tag, endText, endRx, endTag)
        },
        onToggleManualCollapse = { state.toggleManualCollapse(tab.id, it) },
        onRemoveManualCollapse = { state.removeManualCollapse(tab.id, it) },
        onAddMessageRule = { include, pattern, regex, tag, prefix, target ->
            state.addMessageRule(tab.id, include, pattern, regex, tag, prefix, target)
        },
        onRemoveMessageRule = { state.removeMessageRule(tab.id, it) },
        onToggleMessageRuleRegex = { state.toggleKwInTagRx(tab.id) },
        onMoveSeqUp = { state.moveSequenceUp(it) },
        onMoveSeqDown = { state.moveSequenceDown(it) },
        onSetNewSeqText = { state.newSeqText = it },
        onSetNewSeqRx = { state.newSeqRegex = it },
        onSetNewSeqEndText = { state.newSeqEndText = it },
        onSetNewSeqEndRx = { state.newSeqEndRegex = it },
        onSetNewSeqStartTag = { state.newSeqTag = it },
        onSetNewSeqEndTag = { state.newSeqEndTag = it },
        onSetNewSeqColor = { state.newSeqColor = it },
        onAddHl = { p, r, c -> state.addHl(tab.id, p, r, c) },
        onRemoveHl = { state.removeHl(tab.id, it) },
        onToggleHl = { state.toggleHl(tab.id, it) },
        onSetHlColor = { id, c -> state.setHighlighterColor(tab.id, id, c) },
        onSetNewHlPat = { state.newHlPat = it },
        onSetNewHlRx = { state.newHlRx = it },
        onSetNewHlColor = { state.newHlColor = it },
        onLoadFilter = { state.requestLoadFilter(tab.id, it) },
        onDeleteSF = { state.requestDeleteSF(it) },
        onOpenSFDialog = { state.sfDialog = true; state.sfTabId = tab.id; state.sfName = "" },
        onSetKwInTag = { state.setKwInTag(tab.id, it) },
        onAddPkgPrefix = { state.addPkgPrefix(tab.id, it) },
        onRemovePkgPrefix = { state.removePkgPrefix(tab.id, it) },
        onAddExcludePkgPrefix = { state.addExcludePkgPrefix(tab.id, it) },
        onRemoveExcludePkgPrefix = { state.removeExcludePkgPrefix(tab.id, it) },
        onExportFilters = { state.exportFiltersToFile() },
        onImportFilters = { state.importFiltersFromFile() },
        onImportFiltersFromFiles = { files -> files.forEach { state.importFiltersFromFileAsync(it) } },
        onClearFilter = { state.requestClearFilter(tab.id) },
        onUiStateChanged = { state.autosaveNow() },
        mostUsedTagLimit = state.settings.mostUsedTagLimit,
        filterListRows = state.settings.filterListRows,
        width = state.filterPanelWidth,
        focusRequester = focusRequester,
        focusSearchRequest = focusSearchRequest,
        onPanelFocusChanged = onPanelFocusChanged,
        keyboardFocusVisible = state.keyboardFocusVisible,
    )
    HDivider { delta -> state.updateFilterPanelWidth(state.filterPanelWidth + delta) }
}

// ── FileView ──────────────────────────────────────────────────────────
@Composable
private fun FileView(
    state: AppState,
    tab: LogTab,
    requestedPanelFocus: KeyboardPanel? = null,
    filterSearchFocusRequest: Int = 0,
    onPanelFocusConsumed: () -> Unit = {},
) {
    val filterFr = remember { FocusRequester() }
    val logViewerFr = remember { FocusRequester() }
    val annotationFr = remember { FocusRequester() }
    var focusedPanelIdx by remember { mutableStateOf(0) }

    fun visiblePanelFrs(): List<Pair<KeyboardPanel, FocusRequester>> = buildList {
        if (state.filterVisible) add(KeyboardPanel.FILTERS to filterFr)
        add(KeyboardPanel.LOG_VIEW to logViewerFr)
        if (state.annotationVisible) add(KeyboardPanel.NOTES to annotationFr)
    }

    LaunchedEffect(requestedPanelFocus, state.filterVisible, state.annotationVisible, tab.id) {
        val panel = requestedPanelFocus ?: return@LaunchedEffect
        val fr = visiblePanelFrs().firstOrNull { it.first == panel }?.second ?: return@LaunchedEffect
        runCatching { fr.requestFocus() }
        onPanelFocusConsumed()
    }

    Row(
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
        BoundFilterPanel(
            state, tab,
            focusRequester = filterFr,
            focusSearchRequest = filterSearchFocusRequest,
            onPanelFocusChanged = { focused ->
                if (focused) focusedPanelIdx = visiblePanelFrs().indexOfFirst { it.second == filterFr }
            },
        )
        LogViewer(
            tab = tab, modifier = Modifier.weight(1f),
            onSelRow = { id, multi, range -> state.selRow(tab.id, id, multi, range) },
            onSelRowRange = { ids -> state.setSelectedRows(tab.id, ids) },
            onCtxMenu = { id, x, y, sel, panelSel -> state.ctx = CtxMenuState(tab.id, id, x, y, sel, panelSel) },
            onToggleGroup = { state.toggleGroup(tab.id, it) },
            onClearFilter = { state.requestClearFilter(tab.id) },
            onExpandAll = { state.expandAll(tab.id) },
            onCollapseAll = { state.collapseAll(tab.id) },
            onToggleUnfiltered = { state.toggleUnfiltered(tab.id) },
            scrollStateStore = state.logViewerScrollStateStore,
            annotationNavigationRequest = state.pendingAnnotationNavigation,
            onConsumeAnnotationNavigation = { state.consumeAnnotationNavigation(it) },
            onSelectAll = { state.selectAll(tab.id) },
            onClearSelection = { state.clearSelection(tab.id) },
            onCopySelection = { state.copySelectedLines(tab.id) },
            navScrollMargin = state.settings.navScrollMargin,
            focusRequester = logViewerFr,
            onPanelFocusChanged = { focused ->
                if (focused) focusedPanelIdx = visiblePanelFrs().indexOfFirst { it.second == logViewerFr }
            },
            keyboardFocusVisible = state.keyboardFocusVisible,
        )
        if (state.annotationVisible) {
            HDivider { delta ->
                state.updateAnnotationPanelWidth(state.annotationPanelWidth - delta)
            }
            AnnotationPanel(
                tab = tab,
                settings = state.settings,
                recentNotes = state.recentNotes,
                recentNotesMenuOpen = state.recentNotesMenuOpen,
                onToggleMd = { state.toggleMd(tab.id) },
                onCopy = { state.copyAnn(tab.id) },
                onSave = { state.saveAnalysis(tab.id) },
                onToggleRecentNotes = { state.recentNotesMenuOpen = !state.recentNotesMenuOpen },
                onOpenNote = { state.openNoteFileAsync(tab.id, it) },
                onUpdatePrefix = { state.setPrefix(tab.id, it) },
                onUpdateSuffix = { state.setSuffix(tab.id, it) },
                onUpdateBlock = { blockId, text -> state.updateBlock(tab.id, blockId, text) },
                onRemoveBlock = { state.removeBlock(tab.id, it) },
                onMoveBlock = { blockId, d -> state.moveBlock(tab.id, blockId, d) },
                onAddNoteAfter = { state.addNoteBlock(tab.id, it) },
                onNavigateLogRef = { state.requestAnnotationNavigation(tab.id, it) },
                width = state.annotationPanelWidth,
                focusRequester = annotationFr,
                onPanelFocusChanged = { focused ->
                    if (focused) focusedPanelIdx = visiblePanelFrs().indexOfFirst { it.second == annotationFr }
                },
                keyboardFocusVisible = state.keyboardFocusVisible,
            )
        }
    }
}

// ── CompareView ───────────────────────────────────────────────────────
@Composable
private fun CompareView(
    state: AppState,
    requestedPanelFocus: KeyboardPanel? = null,
    filterSearchFocusRequest: Int = 0,
    onPanelFocusConsumed: () -> Unit = {},
) {
    val leftTab = state.tab(state.activeTabId) ?: state.tabs.firstOrNull() ?: return
    val rawRightTab = state.tab(state.compareTabId) ?: state.tabs.getOrNull(1) ?: state.tabs.first()
    // Left and right must never resolve to the same tab: both LogViewers key their scroll
    // state by tab id alone, so two panes on the same tab would share one LazyListState and
    // silently fight over scroll ownership (see LogViewerScrollStateStore).
    val rightTab = if (rawRightTab.id == leftTab.id) {
        state.tabs.firstOrNull { it.id != leftTab.id } ?: rawRightTab
    } else {
        rawRightTab
    }
    val tc = tc()
    val filterFr = remember { FocusRequester() }
    val leftLogFr = remember { FocusRequester() }
    val rightLogFr = remember { FocusRequester() }
    val annotationFr = remember { FocusRequester() }
    var focusedPanelIdx by remember { mutableStateOf(0) }

    fun visiblePanelFrs(): List<Pair<KeyboardPanel, FocusRequester>> = buildList {
        if (state.filterVisible) add(KeyboardPanel.FILTERS to filterFr)
        add(KeyboardPanel.LOG_VIEW to leftLogFr)
        add(KeyboardPanel.LOG_VIEW to rightLogFr)
        if (state.annotationVisible) add(KeyboardPanel.NOTES to annotationFr)
    }

    LaunchedEffect(requestedPanelFocus, state.filterVisible, state.annotationVisible, leftTab.id, rightTab.id) {
        val panel = requestedPanelFocus ?: return@LaunchedEffect
        val fr = visiblePanelFrs().firstOrNull { it.first == panel }?.second ?: return@LaunchedEffect
        runCatching { fr.requestFocus() }
        onPanelFocusConsumed()
    }

    // Right side shows all entries when filter is off; mirrors left filter when on.
    val effectiveRightTab = if (state.compareFilterRight)
        rightTab.copy(filter = leftTab.filter)
    else
        rightTab.copy(filter = Filter())

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val totalWidthDp = maxWidth.value

        Row(
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
            // ── Left panel ──────────────────────────────────────────
            // No local tab picker here — the top TabBar already controls activeTabId (the tab
            // shown on the left), so a second picker for the same state would be redundant.
            Column(
                Modifier
                    .width((totalWidthDp * state.compareSplit).dp)
                    .fillMaxHeight()
                    .border(BorderStroke(1.dp, tc.br.copy(.53f)))
            ) {
                // Empty bar matching the right panel's header height, so both LogViewers start
                // at the same vertical offset — this is a side-by-side diff view.
                Box(Modifier.fillMaxWidth().height(36.dp).background(tc.p2))
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    BoundFilterPanel(
                        state = state,
                        tab = leftTab,
                        focusRequester = filterFr,
                        focusSearchRequest = filterSearchFocusRequest,
                        onPanelFocusChanged = { focused ->
                            if (focused) focusedPanelIdx = visiblePanelFrs().indexOfFirst { it.second == filterFr }
                        },
                    )
                    LogViewer(
                        tab = leftTab, modifier = Modifier.weight(1f),
                        onSelRow = { id, m, r -> state.selRow(leftTab.id, id, m, r) },
                        onSelRowRange = { ids -> state.setSelectedRows(leftTab.id, ids) },
                        onCtxMenu = { id, x, y, sel, panelSel ->
                            state.ctx = CtxMenuState(leftTab.id, id, x, y, sel, panelSel)
                        },
                        onToggleGroup = { state.toggleGroup(leftTab.id, it) },
                        onClearFilter = { state.requestClearFilter(leftTab.id) },
                        onExpandAll = { state.expandAll(leftTab.id) },
                        onCollapseAll = { state.collapseAll(leftTab.id) },
                        onToggleUnfiltered = { state.toggleUnfiltered(leftTab.id) },
                        scrollStateStore = state.logViewerScrollStateStore,
                        annotationNavigationRequest = state.pendingAnnotationNavigation,
                        onConsumeAnnotationNavigation = { state.consumeAnnotationNavigation(it) },
                        onSelectAll = { state.selectAll(leftTab.id) },
                        onClearSelection = { state.clearSelection(leftTab.id) },
                        onCopySelection = { state.copySelectedLines(leftTab.id) },
                        navScrollMargin = state.settings.navScrollMargin,
                        focusRequester = leftLogFr,
                        onPanelFocusChanged = { focused ->
                            if (focused) focusedPanelIdx = visiblePanelFrs().indexOfFirst { it.second == leftLogFr }
                        },
                        keyboardFocusVisible = state.keyboardFocusVisible,
                    )
                }
            }

            // ── Split divider ────────────────────────────────────────
            HDivider { delta ->
                val newFrac = (state.compareSplit * totalWidthDp + delta) / totalWidthDp
                state.updateCompareSplit(newFrac)
            }

            // ── Right panel ──────────────────────────────────────────
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    Modifier.fillMaxWidth().height(36.dp).background(tc.p2).padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.tabs.forEach { t ->
                        PillBtn(t.filename, active = t.id == rightTab.id) {
                            // Picking the tab already shown on the left swaps sides instead of
                            // colliding both panes on the same tab (see LogViewerScrollStateStore).
                            if (t.id == leftTab.id) state.activateTab(rightTab.id)
                            state.compareTabId = t.id
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    PillBtn(
                        label = if (state.compareFilterRight) "⊟ Filter" else "⊞ Filter",
                        active = state.compareFilterRight,
                        onClick = { state.updateCompareFilterRight(!state.compareFilterRight) },
                    )
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    LogViewer(
                        tab = effectiveRightTab, modifier = Modifier.weight(1f),
                        onSelRow = { id, m, r -> state.selRow(rightTab.id, id, m, r) },
                        onSelRowRange = { ids -> state.setSelectedRows(rightTab.id, ids) },
                        onCtxMenu = { id, x, y, sel, panelSel ->
                            state.ctx = CtxMenuState(rightTab.id, id, x, y, sel, panelSel)
                        },
                        onToggleGroup = { state.toggleGroup(rightTab.id, it) },
                        onClearFilter = { state.requestClearFilter(rightTab.id) },
                        onExpandAll = { state.expandAll(rightTab.id) },
                        onCollapseAll = { state.collapseAll(rightTab.id) },
                        onToggleUnfiltered = { state.toggleUnfiltered(rightTab.id) },
                        scrollStateStore = state.logViewerScrollStateStore,
                        annotationNavigationRequest = state.pendingAnnotationNavigation,
                        onConsumeAnnotationNavigation = { state.consumeAnnotationNavigation(it) },
                        onSelectAll = { state.selectAll(rightTab.id) },
                        onClearSelection = { state.clearSelection(rightTab.id) },
                        onCopySelection = { state.copySelectedLines(rightTab.id) },
                        navScrollMargin = state.settings.navScrollMargin,
                        focusRequester = rightLogFr,
                        onPanelFocusChanged = { focused ->
                            if (focused) focusedPanelIdx = visiblePanelFrs().indexOfFirst { it.second == rightLogFr }
                        },
                        keyboardFocusVisible = state.keyboardFocusVisible,
                    )
                    if (state.annotationVisible) {
                        HDivider { d ->
                            state.updateAnnotationPanelWidth(state.annotationPanelWidth - d)
                        }
                        AnnotationPanel(
                            tab = leftTab,
                            settings = state.settings,
                            headerNote = leftTab.filename,
                            recentNotes = state.recentNotes,
                            recentNotesMenuOpen = state.recentNotesMenuOpen,
                            onToggleMd = { state.toggleMd(leftTab.id) },
                            onCopy = { state.copyAnn(leftTab.id) },
                            onSave = { state.saveAnalysis(leftTab.id) },
                            onToggleRecentNotes = { state.recentNotesMenuOpen = !state.recentNotesMenuOpen },
                            onOpenNote = { state.openNoteFileAsync(leftTab.id, it) },
                            onUpdatePrefix = { state.setPrefix(leftTab.id, it) },
                            onUpdateSuffix = { state.setSuffix(leftTab.id, it) },
                            onUpdateBlock = { bid, t -> state.updateBlock(leftTab.id, bid, t) },
                            onRemoveBlock = { state.removeBlock(leftTab.id, it) },
                            onMoveBlock = { bid, d -> state.moveBlock(leftTab.id, bid, d) },
                            onAddNoteAfter = { state.addNoteBlock(leftTab.id, it) },
                            onNavigateLogRef = { state.requestAnnotationNavigation(leftTab.id, it) },
                            width = state.annotationPanelWidth,
                            focusRequester = annotationFr,
                            onPanelFocusChanged = { focused ->
                                if (focused) focusedPanelIdx = visiblePanelFrs().indexOfFirst { it.second == annotationFr }
                            },
                            keyboardFocusVisible = state.keyboardFocusVisible,
                        )
                    }
                }
            }
        }
    }
}

// ── TabBar ────────────────────────────────────────────────────────────
@Composable
private fun TabBar(state: AppState) {
    val tc = tc()
    val toolbarGap = 4.dp
    val leftShape = RoundedCornerShape(topStart = 7.dp, bottomStart = 7.dp)
    val middleShape = RoundedCornerShape(0.dp)
    val rightShape = RoundedCornerShape(topEnd = 7.dp, bottomEnd = 7.dp)
    val standaloneShape = RoundedCornerShape(7.dp)
    val hasRecentFiles = state.recentFiles.isNotEmpty()
    Row(
        Modifier.fillMaxWidth().height(36.dp).background(tc.p2).border(BorderStroke(1.dp, tc.br)).padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabOverflowRow(state = state, modifier = Modifier.weight(1f).fillMaxHeight())
        Spacer(Modifier.width(toolbarGap))
        ToolbarBtn(
            if (state.filterVisible) "⊟ Filter" else "⊞ Filter",
            active = state.filterVisible,
            modifier = Modifier.fillMaxHeight(),
            shape = leftShape,
        ) { state.updateFilterVisible(!state.filterVisible) }
        ToolbarBtn(
            if (state.annotationVisible) "⊟ Notes" else "⊞ Notes",
            active = state.annotationVisible,
            modifier = Modifier.fillMaxHeight(),
            shape = middleShape,
        ) { state.updateAnnotationVisible(!state.annotationVisible) }
        ToolbarBtn(
            if (state.compareMode) "⊟ Compare" else "⊠ Compare",
            active = state.compareMode,
            modifier = Modifier.fillMaxHeight(),
            shape = middleShape,
        ) { state.updateCompareMode(!state.compareMode) }
        ToolbarBtn(
            "Open",
            modifier = Modifier.fillMaxHeight(),
            shape = if (hasRecentFiles) middleShape else rightShape,
        ) {
            val fd = FileDialog(null as Frame?, "Open Log File", FileDialog.LOAD)
            fd.setFilenameFilter { dir, name ->
                val f = File(dir, name)
                f.isDirectory || com.openlog.utils.isLikelyTextFile(f)
            }
            fd.isVisible = true
            fd.file?.let { state.openFile(File(fd.directory, it)) }
        }
        if (hasRecentFiles) {
            ToolbarBtn(
                "▾",
                active = state.recentMenuOpen,
                modifier = Modifier.fillMaxHeight().width(18.dp),
                shape = rightShape,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 5.dp),
            ) { state.recentMenuOpen = !state.recentMenuOpen }
        }
        Spacer(Modifier.width(toolbarGap))
        ToolbarBtn(
            "⚙",
            modifier = Modifier.fillMaxHeight().width(36.dp),
            shape = standaloneShape
        ) { state.settingsOpen = true }
        Spacer(Modifier.width(toolbarGap))
    }
}

// Renders visible tabs and an overflow "▾ N" button for any that don't fit.
// Drag-and-drop reorder: press-and-move >8px to start drag; a 3dp accent line marks the drop point.
internal fun browserTabOrderDuringDrag(
    visibleIds: List<String>,
    draggedId: String?,
    dragStartIndex: Int,
    dragOffsetX: Float,
    tabWidth: Float,
): List<String> {
    val dragged = draggedId?.takeIf { it in visibleIds } ?: return visibleIds
    if (tabWidth <= 0f || dragStartIndex !in visibleIds.indices) return visibleIds
    val sensitivityBias = tabWidth * TAB_DRAG_SNAP_BIAS * dragOffsetX.compareTo(0f)
    val draggedCenter = dragStartIndex * tabWidth + tabWidth / 2f + dragOffsetX + sensitivityBias
    val without = visibleIds.filter { it != dragged }
    val insertAt = without.indexOfFirst { id ->
        val center = visibleIds.indexOf(id) * tabWidth + tabWidth / 2f
        draggedCenter < center
    }.takeIf { it >= 0 } ?: without.size
    return without.take(insertAt) + dragged + without.drop(insertAt)
}

internal fun tabRenderX(
    isDragging: Boolean,
    isJustReleased: Boolean,
    pointerX: Float,
    targetX: Float,
    animatedX: Float,
): Float = when {
    isDragging -> pointerX
    isJustReleased -> targetX
    else -> animatedX
}

internal fun splitTabsForVisibility(
    tabs: List<LogTab>,
    containerPx: Int,
    minTabPx: Int,
    overflowButtonPx: Int,
    visibleTabLimit: Int,
): Pair<List<LogTab>, List<LogTab>> {
    if (containerPx == 0) return tabs to emptyList()
    if (tabs.isEmpty()) return emptyList<LogTab>() to emptyList()
    var n = minOf(tabs.size, visibleTabLimit.coerceIn(1, tabs.size))
    while (n > 1) {
        val avail = if (n < tabs.size) containerPx - overflowButtonPx else containerPx
        if (avail / n >= minTabPx) break
        n--
    }
    return tabs.takeLast(n) to tabs.dropLast(n)
}

internal fun tabOrderAfterVisibleReorder(
    visibleIds: List<String>,
    overflowIds: List<String>,
): List<String> = overflowIds + visibleIds

@Composable
private fun TabOverflowRow(state: AppState, modifier: Modifier) {
    val tc = tc()
    val density = LocalDensity.current.density
    var containerPx by remember { mutableStateOf(0) }
    var tabAreaPx by remember { mutableStateOf(0) }
    var dragTabId by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableStateOf(-1) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    var justReleasedTabId by remember { mutableStateOf<String?>(null) }
    var liveVisualTabIds by remember { mutableStateOf(emptyList<String>()) }
    var overflowOpen by remember { mutableStateOf(false) }

    val minTabPx = (80 * density).toInt()
    val ovBtnPx = (40 * density).toInt()

    val visibleTabLimit = state.settings.visibleTabLimit
    val (visibleTabs, overflowTabs) = remember(state.tabs, state.activeTabId, containerPx, visibleTabLimit) {
        splitTabsForVisibility(
            tabs = state.tabs,
            containerPx = containerPx,
            minTabPx = minTabPx,
            overflowButtonPx = ovBtnPx,
            visibleTabLimit = visibleTabLimit,
        )
    }

    val visibleTabIds = visibleTabs.map { it.id }
    val overflowTabIds = overflowTabs.map { it.id }
    LaunchedEffect(visibleTabIds, dragTabId) {
        if (dragTabId == null) liveVisualTabIds = visibleTabIds
    }
    LaunchedEffect(justReleasedTabId) {
        if (justReleasedTabId != null) {
            kotlinx.coroutines.delay(120)
            justReleasedTabId = null
        }
    }
    val tabWidthPx =
        if (visibleTabs.isNotEmpty() && tabAreaPx > 0) tabAreaPx.toFloat() / visibleTabs.size else minTabPx.toFloat()
    val visualOrderIds =
        liveVisualTabIds.takeIf { it.toSet() == visibleTabIds.toSet() && it.size == visibleTabIds.size }
            ?: visibleTabIds
    val currentVisualOrderIds by rememberUpdatedState(visualOrderIds)
    val currentOverflowTabIds by rememberUpdatedState(overflowTabIds)

    Row(
        modifier
            .onSizeChanged { containerPx = it.width },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.weight(1f).fillMaxHeight()
                .onSizeChanged { tabAreaPx = it.width }
                .pointerInput(visibleTabIds, tabWidthPx) {
                    var downPos = Offset.Zero
                    var downId: String? = null
                    var dragging = false
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull() ?: continue
                            when (ev.type) {
                                PointerEventType.Press -> {
                                    downPos = ch.position; dragging = false
                                    val idx = (ch.position.x / tabWidthPx).toInt()
                                        .coerceIn(0, visibleTabIds.lastIndex.coerceAtLeast(0))
                                    downId = visibleTabIds.getOrNull(idx)
                                }

                                PointerEventType.Move -> {
                                    if (downId != null && !dragging && (ch.position - downPos).getDistance() > 8f) {
                                        dragging = true
                                        dragTabId = downId
                                        justReleasedTabId = null
                                        dragStartIndex = visibleTabIds.indexOf(downId)
                                        dragOffsetX = 0f
                                    }
                                    if (dragging && dragTabId != null) {
                                        ch.consume()
                                        dragOffsetX = ch.position.x - downPos.x
                                        liveVisualTabIds = browserTabOrderDuringDrag(
                                            visibleIds = visibleTabIds,
                                            draggedId = dragTabId,
                                            dragStartIndex = dragStartIndex,
                                            dragOffsetX = dragOffsetX,
                                            tabWidth = tabWidthPx,
                                        )
                                    }
                                }

                                PointerEventType.Release -> {
                                    if (dragging && dragTabId != null) {
                                        justReleasedTabId = dragTabId
                                        val newOrder = tabOrderAfterVisibleReorder(
                                            visibleIds = currentVisualOrderIds,
                                            overflowIds = currentOverflowTabIds,
                                        )
                                        state.tabs = newOrder.mapNotNull { id -> state.tabs.find { it.id == id } }
                                    }
                                    dragTabId = null
                                    dragStartIndex = -1
                                    dragOffsetX = 0f
                                    downId = null
                                    dragging = false
                                }

                                else -> {}
                            }
                        }
                    }
                },
        ) {
            visibleTabs.forEach { tab ->
                key(tab.id) {
                    val isDragging = tab.id == dragTabId
                    val targetIndex = visualOrderIds.indexOf(tab.id).takeIf { it >= 0 } ?: visibleTabs.indexOf(tab)
                    val targetX = targetIndex * tabWidthPx
                    val animatedX by animateFloatAsState(
                        targetValue = targetX,
                        animationSpec = spring(stiffness = 650f, dampingRatio = 0.86f),
                        label = "tab-x-${tab.id}",
                    )
                    val startX = (dragStartIndex.takeIf { it >= 0 } ?: targetIndex) * tabWidthPx
                    val tabX = tabRenderX(
                        isDragging = isDragging,
                        isJustReleased = tab.id == justReleasedTabId,
                        pointerX = startX + dragOffsetX,
                        targetX = targetX,
                        animatedX = animatedX,
                    )
                    Box(
                        Modifier
                            .offset { IntOffset(tabX.roundToInt(), 0) }
                            .width((tabWidthPx / density).dp)
                            .fillMaxHeight()
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                if (isDragging) {
                                    scaleX = 1.02f
                                    scaleY = 1.02f
                                }
                            }
                    ) {
                        TabItem(
                            tab = tab,
                            isActive = tab.id == state.activeTabId,
                            showClose = true,
                            dragging = isDragging,
                            onClick = { if (dragTabId == null) state.activateTab(tab.id) },
                            onClose = { state.closeTab(tab.id) },
                        )
                    }
                }
            }
        }
        if (overflowTabs.isNotEmpty()) {
            Box(Modifier.fillMaxHeight()) {
                ToolbarBtn(
                    "▾ ${overflowTabs.size}",
                    active = overflowOpen,
                    modifier = Modifier.fillMaxHeight(),
                ) { overflowOpen = !overflowOpen }
                if (overflowOpen) {
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(0, (36 * density).toInt()),
                        onDismissRequest = { overflowOpen = false },
                        properties = PopupProperties(focusable = true),
                    ) {
                        Box(
                            Modifier.width(240.dp)
                                .background(tc.p, RoundedCornerShape(7.dp))
                                .border(1.dp, tc.br, RoundedCornerShape(7.dp)),
                        ) {
                            Column(Modifier.padding(vertical = 4.dp)) {
                                overflowTabs.forEach { tab ->
                                    HoverBox(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { state.activateOverflowTab(tab.id); overflowOpen = false },
                                    ) {
                                        AppText(
                                            tab.filename, color = tc.tx, fontSize = 12.sp,
                                            fontFamily = MONO,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Settings dialog ───────────────────────────────────────────────────
@Composable
private fun SettingsDialog(state: AppState, onDismiss: () -> Unit) {
    val tc = tc()
    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.width(580.dp).heightIn(max = 860.dp)
            .clip(shape)
            .background(tc.p)
            .border(1.dp, tc.br, shape),
    ) {
        Column(
            Modifier.verticalScroll(scroll).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText("Settings", color = tc.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                CloseButton(onClick = onDismiss)
            }

            SettingsSectionHeader("Appearance")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppText("Theme", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                ThemeGallery(state)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppText(
                    "Default save folder",
                    color = tc.td,
                    fontSize = 10.sp,
                    fontFamily = UI,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val fullPath = state.settings.defaultSaveDir
                    val pathText: @Composable () -> Unit = {
                        AppText(
                            fullPath?.let { truncatePathForDisplay(it) } ?: "(not set)",
                            color = tc.ts, fontSize = 11.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (fullPath != null) {
                        TooltipArea(
                            tooltip = {
                                Box(
                                    Modifier
                                        .background(tc.p2, RoundedCornerShape(4.dp))
                                        .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    AppText(fullPath, color = tc.tx, fontSize = 11.sp, fontFamily = MONO)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { pathText() }
                    } else {
                        Box(Modifier.weight(1f)) { pathText() }
                    }
                    AppButton("Browse", onClick = { state.pickSaveFolder() })
                    if (fullPath != null) AppButton(
                        "Clear",
                        onClick = { state.updateSettings { it.copy(defaultSaveDir = null) } })
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                CompactSetting("Font family", Modifier.weight(1f)) {
                    SegmentedControl(
                        options = listOf("Monospace", "Proportional"),
                        selectedIndices = setOf(if (state.settings.fontMono) 0 else 1),
                        onToggle = { idx -> state.updateSettings { it.copy(fontMono = idx == 0) } },
                    )
                }
                CompactSetting("Font size", Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    ListStepper(
                        options = (10..16).toList(),
                        value = state.settings.fontSize,
                        onChange = { v -> state.updateSettings { it.copy(fontSize = v) } },
                    )
                }
            }

            SettingsSectionHeader("Editor behavior")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CompactSetting("Visible tabs") {
                    val tabLimits = listOf(4, 6, 8, 10, 12, 16)
                    ListStepper(
                        options = tabLimits,
                        value = state.settings.visibleTabLimit,
                        onChange = { v -> state.updateSettings { it.copy(visibleTabLimit = v) } },
                    )
                }
                CompactSetting("Keyboard scroll margin") {
                    val scrollMargins = listOf(0, 2, 3, 5, 8, 12)
                    ListStepper(
                        options = scrollMargins,
                        value = state.settings.navScrollMargin,
                        onChange = { v -> state.updateSettings { it.copy(navScrollMargin = v) } },
                    )
                }
                CompactSetting("Most-used tags") {
                    val tagLimits = listOf(0, 3, 5, 10, 20)
                    ListStepper(
                        options = tagLimits,
                        value = state.settings.mostUsedTagLimit,
                        onChange = { v -> state.updateSettings { it.copy(mostUsedTagLimit = v) } },
                    )
                }
                CompactSetting("Filter list rows") {
                    val rowLimits = listOf(3, 5, 8, 10, 15)
                    ListStepper(
                        options = rowLimits,
                        value = state.settings.filterListRows,
                        onChange = { v -> state.updateSettings { it.copy(filterListRows = v) } },
                    )
                }
            }

            SettingsSectionHeader("Export & annotations")
            AnnotationSettingsRow(state)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppText(
                    "Annotation file prefix",
                    color = tc.td,
                    fontSize = 10.sp,
                    fontFamily = UI,
                    fontWeight = FontWeight.SemiBold
                )
                InlineField(
                    state.settings.annotationPrefixLabel,
                    { value -> state.updateSettings { it.copy(annotationPrefixLabel = value) } },
                    "From",
                    Modifier.fillMaxWidth(),
                    fontSize = 12.sp,
                )
                val previewLabel = state.settings.annotationPrefixLabel.trim().ifBlank { "From" }
                AppText("Preview: $previewLabel app.log", color = tc.td, fontSize = 10.sp, fontFamily = MONO)
            }

            SettingsSectionHeader("About")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText("Keyboard shortcuts", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                AppButton("Show shortcuts…", onClick = { onDismiss(); state.shortcutsOpen = true }, variant = ButtonVariant.Secondary)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText("Version", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                AppText(BuildInfo.APP_VERSION, color = tc.ts, fontSize = 11.sp, fontFamily = MONO)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText("Author", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                AppText(BuildInfo.APP_AUTHOR, color = tc.ts, fontSize = 11.sp, fontFamily = MONO)
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                AppButton("Done", onClick = onDismiss, variant = ButtonVariant.Primary)
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    val tc = tc()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AppText(title, color = tc.ts, fontSize = 11.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
        Divider()
    }
}

// Keeps the most meaningful (rightmost) path segments and collapses the rest into a leading
// "…/" so long save-folder paths don't overflow or wrap — full path is still shown on hover.
private fun truncatePathForDisplay(path: String, maxChars: Int = 42): String {
    if (path.length <= maxChars) return path
    val segments = path.trimEnd('/').split('/').filter { it.isNotEmpty() }
    var best = ""
    for (i in segments.indices.reversed()) {
        val candidate = segments.subList(i, segments.size).joinToString("/", prefix = "…/")
        if (candidate.length > maxChars) break
        best = candidate
    }
    return best.ifEmpty { "…" + path.takeLast(maxChars - 1) }
}

@Composable
private fun ListStepper(options: List<Int>, value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val tc = tc()
    val index = options.indexOf(value).coerceAtLeast(0)
    Row(
        modifier
            .border(0.5.dp, tc.br, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperButton("−", enabled = index > 0, onClick = { onChange(options[(index - 1).coerceAtLeast(0)]) })
        Box(Modifier.width(0.5.dp).height(28.dp).background(tc.br))
        Box(Modifier.width(44.dp).height(28.dp), contentAlignment = Alignment.Center) {
            AppText("$value", color = tc.tx, fontSize = 12.sp, fontFamily = MONO, fontWeight = FontWeight.Medium)
        }
        Box(Modifier.width(0.5.dp).height(28.dp).background(tc.br))
        StepperButton("+", enabled = index < options.lastIndex, onClick = { onChange(options[(index + 1).coerceAtMost(options.lastIndex)]) })
    }
}

@Composable
private fun StepperButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    val tc = tc()
    var hovered by remember { mutableStateOf(false) }
    Box(
        Modifier
            .width(28.dp).height(28.dp)
            .background(if (hovered && enabled) tc.hv else Color.Transparent)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
        contentAlignment = Alignment.Center,
    ) {
        AppText(symbol, color = if (enabled) tc.tx else tc.td.copy(alpha = .4f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun KeyboardShortcutsDialog(onDismiss: () -> Unit) {
    val tc = tc()
    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(8.dp)
    val groups = keyboardShortcutHelpGroups()

    // Split the groups across 3 columns, balanced by row count, so every shortcut is visible
    // without scrolling instead of relying on an invisible overflow scrollbar.
    val columns = splitShortcutGroupsIntoColumns(groups, 3).filter { it.isNotEmpty() }

    @Composable
    fun PageColumn(page: List<ShortcutHelpGroup>, modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            page.forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText(
                        group.title,
                        color = tc.td,
                        fontSize = 10.sp,
                        fontFamily = UI,
                        fontWeight = FontWeight.SemiBold,
                    )
                    group.rows.forEach { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 1.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .widthIn(min = 130.dp)
                                    .background(tc.p2, RoundedCornerShape(4.dp))
                                    .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                AppText(row.label, color = tc.tx, fontSize = 11.sp, fontFamily = MONO)
                            }
                            AppText(
                                row.description,
                                color = tc.ts,
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    Box(
        Modifier
            .width(1150.dp)
            .heightIn(max = 640.dp)
            .clip(shape)
            .background(tc.p)
            .border(1.dp, tc.br, shape),
    ) {
        Column(
            Modifier.verticalScroll(scroll).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText("Keyboard Shortcuts", color = tc.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                CloseButton(onClick = onDismiss)
            }
            Row(Modifier.fillMaxWidth()) {
                columns.forEachIndexed { idx, page ->
                    PageColumn(
                        page,
                        Modifier.weight(1f).padding(
                            start = if (idx == 0) 0.dp else 20.dp,
                            end = if (idx == columns.lastIndex) 0.dp else 20.dp,
                        ),
                    )
                    if (idx != columns.lastIndex) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(tc.br))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                AppButton("Done", onClick = onDismiss, variant = ButtonVariant.Primary)
            }
        }
    }
}

@Composable
private fun ThemeGallery(state: AppState) {
    val tc = tc()
    val themeScroll = rememberScrollState()
    Box(Modifier.fillMaxWidth().height(148.dp)) {
        FlowRow(
            Modifier.fillMaxWidth().verticalScroll(themeScroll).padding(end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemePreset.entries.forEach { preset ->
                ThemeWindowCard(
                    label = preset.label,
                    colors = themeColors(preset),
                    selected = preset == state.settings.theme,
                    onClick = { state.updateSettings { it.copy(theme = preset) } },
                )
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(themeScroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
            style = appScrollbarStyle(tc),
        )
    }
}

@Composable
private fun ThemeWindowCard(label: String, colors: ThemeColors, selected: Boolean, onClick: () -> Unit) {
    val tc = tc()
    val shape = RoundedCornerShape(8.dp)
    var hovered by remember { mutableStateOf(false) }
    Column(
        Modifier.width(118.dp).height(66.dp)
            .clip(shape)
            .background(if (hovered && !selected) colors.ac.copy(.08f) else colors.bg)
            .border(1.dp, if (selected || hovered) colors.ac else tc.br, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(14.dp).background(colors.p, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(colors.ac, colors.seq1, colors.seq2).forEach { color ->
                Box(Modifier.size(4.dp).background(color, RoundedCornerShape(50)))
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(colors.p2, RoundedCornerShape(4.dp))) {
            Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(4.dp).background(colors.ac, CORNER_SM))
            Row(
                Modifier.align(Alignment.BottomEnd).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(Modifier.size(8.dp).background(colors.seq1, CORNER_SM))
                Box(Modifier.size(8.dp).background(colors.seq2, CORNER_SM))
            }
        }
        AppText(
            text = label,
            color = if (selected) colors.ac else tc.ts,
            fontSize = 9.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
    }
}

@Composable
private fun AnnotationSettingsRow(state: AppState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        CompactSetting("Auto-export") {
            SegmentedControl(
                options = listOf("On", "Off"),
                selectedIndices = setOf(if (state.settings.autoExportNotes) 0 else 1),
                onToggle = { idx -> state.updateSettings { it.copy(autoExportNotes = idx == 0) } },
            )
        }
        CompactSetting("Number blocks") {
            SegmentedControl(
                options = listOf("On", "Off"),
                selectedIndices = setOf(if (state.settings.numberAnnotationBlocks) 0 else 1),
                onToggle = { idx -> state.updateSettings { it.copy(numberAnnotationBlocks = idx == 0) } },
            )
        }
        CompactSetting("Log blocks") {
            val styles = AnnotationLogBlockStyle.entries
            SegmentedControl(
                options = listOf("Indented", "{code:java}"),
                selectedIndices = setOf(styles.indexOf(state.settings.annotationLogBlockStyle)),
                onToggle = { idx -> state.updateSettings { it.copy(annotationLogBlockStyle = styles[idx]) } },
            )
        }
    }
}

@Composable
private fun CompactSetting(
    label: String,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable () -> Unit,
) {
    val tc = tc()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = horizontalAlignment) {
        AppText(label, color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
        content()
    }
}

// ── Shared ────────────────────────────────────────────────────────────
@Composable
private fun TabItem(
    tab: LogTab, isActive: Boolean, showClose: Boolean,
    dragging: Boolean = false, onClick: () -> Unit, onClose: () -> Unit,
) {
    val tc = tc()
    var hov by remember { mutableStateOf(false) }
    val accent = tc.ac
    Row(
        Modifier.fillMaxWidth().height(36.dp)
            .background(if (isActive) tc.bg else if (hov || dragging) tc.p else tc.p2)
            .border(BorderStroke(1.dp, tc.br.copy(alpha = 0.95f)))
            .drawBehind {
                if (isActive) {
                    val stroke = 2.dp.toPx()
                    drawRect(
                        color = accent,
                        topLeft = Offset(0f, size.height - stroke),
                        size = androidx.compose.ui.geometry.Size(size.width, stroke),
                    )
                }
            }
            .onPointerEvent(PointerEventType.Enter) { hov = true }
            .onPointerEvent(PointerEventType.Exit) { hov = false }
            .clickable(onClick = onClick).padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        AppText(
            tab.filename,
            color = if (isActive || dragging) tc.tx else tc.ts,
            fontSize = 12.sp,
            fontFamily = MONO,
            modifier = Modifier.weight(1f),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
        if (showClose) {
            CloseButton(onClick = onClose)
        }
    }
}

// Data-driven context menu entries so keyboard nav (arrow keys) can walk the selectable ones
// without duplicating the conditional logic that decides which items render.
private sealed class CtxMenuEntry {
    data class ActionHeader(val label: String, val onClick: () -> Unit) : CtxMenuEntry()

    data class Action(val icon: ImageVector, val label: String, val onClick: () -> Unit) : CtxMenuEntry()

    object Divider : CtxMenuEntry()

    object Preview : CtxMenuEntry()
}

@Composable
private fun CtxItem(icon: ImageVector, label: String, highlighted: Boolean = false, onClick: () -> Unit) {
    val tc = tc()
    HoverBox(modifier = Modifier.fillMaxWidth(), forceHover = highlighted, onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = tc.td.copy(alpha = 0.65f))
            }
            Spacer(Modifier.width(8.dp))
            AppText(label, color = tc.tx, fontSize = 12.sp, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun CtxDivider() {
    val tc = tc()
    Spacer(Modifier.height(4.dp))
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(tc.br))
    Spacer(Modifier.height(4.dp))
}
