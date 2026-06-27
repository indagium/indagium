@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.openlog.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import java.awt.Cursor as AwtCursor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.openlog.model.*
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI

@Composable
fun App(state: AppState = remember { AppState(restoreOnCreate = true) }) {
    val theme = themeColors(state.settings.theme)

    CompositionLocalProvider(
        LocalTheme    provides theme,
        LocalFontBase provides state.settings.fontSize,
        LocalUseMono  provides state.settings.fontMono,
    ) {
        val tc = tc()
        LaunchedEffect(state.tabs, state.savedFilters, state.sequences, state.settings, state.activeSavedFilterIds) {
            kotlinx.coroutines.delay(400)
            state.autosaveNow()
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
                            if (file.exists() && file.extension.lowercase() in listOf("log", "txt")) {
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
                .then(if (dc != null) Modifier.pointerHoverIcon(PointerIcon(dc!!), overrideDescendants = true) else Modifier)
        ) {
            Column(Modifier.fillMaxSize()) {
                TabBar(state)
                val activeTab = state.activeTab()
                when {
                    state.tabs.isEmpty() ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            AppText("No files open — click Open to add a log", color = tc.ts, fontSize = 14.sp)
                        }
                    state.compareMode -> CompareView(state)
                    activeTab != null  -> FileView(state, activeTab)
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
                val entry  = ctxTab?.rmap?.get(ctx.entryId)
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
                        val estimatedMenuHeight = 500.dp
                        val x = ctx.x.dp.coerceIn(8.dp, (maxWidth - menuWidth - 8.dp).coerceAtLeast(8.dp))
                        val y = ctx.y.dp.coerceIn(8.dp, (maxHeight - estimatedMenuHeight - 8.dp).coerceAtLeast(8.dp))
                        val menuScroll = rememberScrollState()
                        // panelSelectedIds is non-empty when the right-click came from a panel
                        // with its own local selection (e.g. the "Original" unfiltered panel).
                        val selectedIds = ctx.panelSelectedIds.ifEmpty { ctxTab.selected }
                        val selCount = selectedIds.size
                        // Position is already in dp (converted from px in LogRow)
                        Box(
                            Modifier
                                .offset(x = x, y = y)
                                .background(tc.p, RoundedCornerShape(7.dp))
                                .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                                .width(menuWidth)
                                .heightIn(max = (maxHeight - 16.dp).coerceAtLeast(160.dp))
                                .verticalScroll(menuScroll),
                        ) {
                            Column {
                                Box(
                                    Modifier.fillMaxWidth().background(tc.ac.copy(.12f))
                                        .clickable {
                                            val ids = if (selCount > 1) selectedIds.toSortedSet().toList() else listOf(ctx.entryId)
                                            state.requestAddAnn(ctx.tabId, ids)
                                        }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                ) {
                                    AppText(
                                        if (selCount > 1) "Add annotation for $selCount lines" else "Add annotation",
                                        color = tc.ac,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
                                // Preview row
                                Column(
                                    Modifier.fillMaxWidth().background(tc.p2)
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .border(BorderStroke(1.dp, tc.br)),
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                                        LevelBadge(entry.level)
                                        AppText(entry.tag, color = tc.td, fontSize = 10.sp, fontFamily = MONO,
                                            modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    AppText(entry.msg, color = tc.ts, fontSize = 10.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis)
                                    if (ctx.selText.isNotBlank()) {
                                        Spacer(Modifier.height(2.dp))
                                        AppText("Selected: \"${ctx.selText}\"", color = tc.ac, fontSize = 9.sp)
                                    }
                                }
                                // Selected text actions
                                if (ctx.selText.isNotBlank()) {
                                    CtxItem("◉", "Highlight selection")  { state.addHlFromCtx() }
                                    CtxItem("≡", "Add as sequence")       { state.addSeqFromCtx() }
                                    CtxItem("🔍", "Filter by keyword")    { state.addKwFilterFromCtx() }
                                    CtxItem("⊘", "Exclude keyword")       { state.addNegKwFromCtx() }
                                    Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
                                }
                                if (state.pendingSequenceStart != null) {
                                    CtxItem("⇥", "Complete sequence end") { state.completeSequenceEndFromCtx() }
                                }
                                CtxItem("⇤", "Set sequence start") { state.setSequenceStartFromCtx() }
                                CtxItem("⇡", "Collapse to file start") { state.collapseToStartFromCtx() }
                                CtxItem("⇣", "Collapse to file end") { state.collapseToEndFromCtx() }
                                CtxItem("−m", "Hide messages like this") { state.hideMessagesLikeCtx() }
                                CtxItem("+m", "Show messages like this") { state.showOnlyMessagesLikeCtx() }
                                Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
                                // Tag actions
                                CtxItem("#",  "Include tag")    { state.addTagFilterFromCtx() }
                                CtxItem("◉", "Highlight tag")   { state.addHlTagFromCtx() }
                                CtxItem("−",  "Exclude tag")    { state.addExcludeTagFromCtx() }
                                Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
                                if (selCount > 1) {
                                    CtxItem("⎘", "Copy $selCount selected lines") {
                                        state.copySelectedLines(ctx.tabId); state.ctx = null
                                    }
                                }
                                CtxItem("⎘", "Copy line") {
                                    val pid = if (entry.pid > 0) "  ${entry.pid.toString().padStart(5)} ${entry.tid.toString().padStart(5)}" else ""
                                    state.copyToClipboard("${entry.ts}$pid  ${entry.level.key}  ${entry.tag}: ${entry.msg}")
                                    state.ctx = null
                                }
                                CtxItem("M↓", "Copy as Markdown") {
                                    state.copyToClipboard("**[${entry.ts}] `${entry.level.key}/${entry.tag}`:** ${entry.msg}")
                                    state.ctx = null
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
                            state.confirmAddAnn(req.targetTabId, req.sourceTabId, req.logIds, caption, req.sourceFilename)
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
                        AppText("Save filter preset", color = tc2.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(5.dp))
                        AppText("Saves: levels · tags · keyword · highlighters · sequences",
                            color = tc2.td, fontSize = 11.sp, maxLines = 2)
                        Spacer(Modifier.height(14.dp))
                        InlineField(state.sfName, { state.sfName = it }, "Preset name…", Modifier.fillMaxWidth(), fontSize = 13.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DialogActionButton("Save", active = state.sfName.isNotBlank()) {
                                if (state.sfName.isNotBlank()) state.saveFilter(state.sfTabId ?: return@DialogActionButton, state.sfName)
                            }
                            DialogActionButton("Cancel", active = false) { state.sfDialog = false }
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
                        AppText("Save current filter changes?", color = tc2.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        AppText(
                            "Before loading \"${target?.name ?: "another filter"}\", save the changes to \"${current?.name ?: "current filter"}\".",
                            color = tc2.td,
                            fontSize = 11.sp,
                            maxLines = 3,
                        )
                        Spacer(Modifier.height(14.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                DialogActionButton("Save as new", active = true) { state.beginSavePendingFilterAsNew() }
                                DialogActionButton("Update existing", active = current != null, enabled = current != null) { state.updateCurrentPresetAndLoadPending() }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                DialogActionButton("Do not save", active = true, danger = true) { state.discardPendingFilterChangesAndLoad() }
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            DialogActionButton("Clear filters", active = true, danger = true) { state.confirmClearFilter() }
                            DialogActionButton("Cancel", active = false) { state.cancelClearFilter() }
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
                                AppText("Recent Files (${state.recentFiles.size})", color = tc.ts, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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

        }
    }
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
                    Modifier.background(tc.ac.copy(.15f), RoundedCornerShape(3.dp))
                        .border(1.dp, tc.ac.copy(.3f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) { AppText("from $sourceFilename", color = tc.ac, fontSize = 10.sp, fontFamily = MONO) }
            }
        }

        // Show referenced log lines
        Column(
            Modifier.fillMaxWidth().background(tc.bg, RoundedCornerShape(4.dp))
                .border(1.dp, tc.br, RoundedCornerShape(4.dp)).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            rows.take(5).forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LevelBadge(r.level)
                    AppText(r.tag, color = tc.td, fontSize = 10.sp, fontFamily = mono,
                        modifier = Modifier.width(80.dp), overflow = TextOverflow.Ellipsis)
                    AppText(r.msg, color = tc.ts, fontSize = 10.sp, fontFamily = mono,
                        modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
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
                .background(tc.bg, RoundedCornerShape(4.dp))
                .border(1.dp, tc.ac.copy(.5f), RoundedCornerShape(4.dp))
                .padding(10.dp).defaultMinSize(minHeight = 72.dp),
            decorationBox = { inner ->
                if (caption.isEmpty()) AppText("Add your analysis note here…", color = tc.td, fontSize = 12.sp)
                inner()
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
    val accent = if (danger) Color(0xFFf85149) else tc.ac
    Box(
        Modifier
            .width(132.dp)
            .height(38.dp)
            .border(1.dp, if (active) accent else tc.br, RoundedCornerShape(5.dp))
            .background(if (active) accent.copy(.18f) else Color.Transparent, RoundedCornerShape(5.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppText(label, color = if (active) accent else tc.ts, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── FileView ──────────────────────────────────────────────────────────
@Composable
private fun FileView(state: AppState, tab: LogTab) {
    Row(Modifier.fillMaxSize()) {
        if (state.filterVisible) {
            FilterPanel(
                tab = tab, sequences = state.sequences, savedFilters = state.savedFilters,
                activeSavedFilterId = state.activeSavedFilterId(tab.id),
                tagUsage = state.tagUsage, fpState = state.fpState,
                newHlPat = state.newHlPat, newHlRx = state.newHlRx, newHlColor = state.newHlColor,
                newSeqText = state.newSeqText, newSeqRegex = state.newSeqRegex,
                newSeqEndText = state.newSeqEndText, newSeqEndRegex = state.newSeqEndRegex,
                newSeqStartTag = state.newSeqTag, newSeqEndTag = state.newSeqEndTag,
                newSeqColor = state.newSeqColor,
                onToggleLevel       = { state.toggleLevel(tab.id, it) },
                onSetFilterMode     = { state.setFilterMode(tab.id, it) },
                onToggleTag         = { state.toggleTag(tab.id, it) },
                onClearTags         = { state.clearTags(tab.id) },
                onToggleExcludeTag  = { state.toggleExcludeTag(tab.id, it) },
                onSetKw             = { state.setKw(tab.id, it) },
                onToggleKwRx        = { state.toggleKwRx(tab.id) },
                onSetExcludeKw      = { state.setExcludeKw(tab.id, it) },
                onToggleExcludeKwRx = { state.toggleExcludeKwRx(tab.id) },
                onToggleSeq         = { state.toggleSeq(tab.id) },
                onAddSeq            = { t, r, c, st, et, er, eg -> state.addSequence(t, r, c, st, et, er, eg) },
                onRemoveSeq         = { state.removeSequence(it) },
                onToggleSeqEnabled  = { state.toggleSequence(it) },
                onSetSeqColor       = { id, c -> state.setSequenceColor(id, c) },
                onUpdateSeq         = { id, text, rx, tag, endText, endRx, endTag ->
                    state.updateSequence(id, text, rx, tag, endText, endRx, endTag)
                },
                onToggleManualCollapse = { state.toggleManualCollapse(tab.id, it) },
                onRemoveManualCollapse = { state.removeManualCollapse(tab.id, it) },
                onAddMessageRule    = { include, pattern, regex, tag, prefix, target ->
                    state.addMessageRule(tab.id, include, pattern, regex, tag, prefix, target)
                },
                onToggleMessageRule = { state.toggleMessageRule(tab.id, it) },
                onRemoveMessageRule = { state.removeMessageRule(tab.id, it) },
                onMoveSeqUp         = { state.moveSequenceUp(it) },
                onMoveSeqDown       = { state.moveSequenceDown(it) },
                onSetNewSeqText     = { state.newSeqText = it },
                onSetNewSeqRx       = { state.newSeqRegex = it },
                onSetNewSeqEndText  = { state.newSeqEndText = it },
                onSetNewSeqEndRx    = { state.newSeqEndRegex = it },
                onSetNewSeqStartTag = { state.newSeqTag = it },
                onSetNewSeqEndTag   = { state.newSeqEndTag = it },
                onSetNewSeqColor    = { state.newSeqColor = it },
                onAddHl             = { p, r, c -> state.addHl(tab.id, p, r, c) },
                onRemoveHl          = { state.removeHl(tab.id, it) },
                onToggleHl          = { state.toggleHl(tab.id, it) },
                onSetHlColor        = { id, c -> state.setHighlighterColor(tab.id, id, c) },
                onSetNewHlPat       = { state.newHlPat = it },
                onSetNewHlRx        = { state.newHlRx = it },
                onSetNewHlColor     = { state.newHlColor = it },
                onLoadFilter        = { state.requestLoadFilter(tab.id, it) },
                onDeleteSF          = { state.deleteSF(it) },
                onOpenSFDialog      = { state.sfDialog = true; state.sfTabId = tab.id; state.sfName = "" },
                onSetKwInTag        = { state.setKwInTag(tab.id, it) },
                onToggleKwInTagRx   = { state.toggleKwInTagRx(tab.id) },
                onAddPkgPrefix      = { state.addPkgPrefix(tab.id, it) },
                onRemovePkgPrefix   = { state.removePkgPrefix(tab.id, it) },
                onExportFilters     = { state.exportFiltersToFile() },
                onImportFilters     = { state.importFiltersFromFile() },
                onImportFiltersFromFiles = { files -> files.forEach { state.importFiltersFromFile(it) } },
                onClearFilter       = { state.requestClearFilter(tab.id) },
                mostUsedTagLimit    = state.settings.mostUsedTagLimit,
                width               = state.filterPanelWidth,
            )
            HDivider { delta -> state.filterPanelWidth = (state.filterPanelWidth + delta).coerceIn(140f, 420f) }
        }
        LogViewer(
            tab = tab, sequences = state.sequences, modifier = Modifier.weight(1f),
            onSelRow        = { id, multi, range -> state.selRow(tab.id, id, multi, range) },
            onSelRowRange   = { ids -> state.setSelectedRows(tab.id, ids) },
            onCtxMenu       = { id, x, y, sel, panelSel -> state.ctx = CtxMenuState(tab.id, id, x, y, sel, panelSel) },
            onToggleGroup   = { state.toggleGroup(tab.id, it) },
            onClearFilter   = { state.requestClearFilter(tab.id) },
            onExpandAll     = { state.expandAll(tab.id) },
            onCollapseAll   = { state.collapseAll(tab.id) },
            onToggleUnfiltered = { state.toggleUnfiltered(tab.id) },
        )
        if (state.annotationVisible) {
            HDivider { delta -> state.annotationPanelWidth = (state.annotationPanelWidth - delta).coerceIn(180f, 500f) }
            AnnotationPanel(
                tab                 = tab,
                recentNotes         = state.recentNotes,
                recentNotesMenuOpen = state.recentNotesMenuOpen,
                onToggleMd          = { state.toggleMd(tab.id) },
                onCopy              = { state.copyAnn(tab.id) },
                onSave              = { state.saveAnalysis(tab.id) },
                onToggleRecentNotes = { state.recentNotesMenuOpen = !state.recentNotesMenuOpen },
                onOpenNote          = { state.openNoteFile(tab.id, it) },
                onUpdatePrefix      = { state.setPrefix(tab.id, it) },
                onUpdateSuffix      = { state.setSuffix(tab.id, it) },
                onUpdateBlock       = { blockId, text -> state.updateBlock(tab.id, blockId, text) },
                onRemoveBlock       = { state.removeBlock(tab.id, it) },
                onMoveBlock         = { blockId, d -> state.moveBlock(tab.id, blockId, d) },
                onAddNoteAfter      = { state.addNoteBlock(tab.id, it) },
                width               = state.annotationPanelWidth,
            )
        }
    }
}

// ── CompareView ───────────────────────────────────────────────────────
@Composable
private fun CompareView(state: AppState) {
    val leftTab  = state.tab(state.activeTabId) ?: state.tabs.firstOrNull() ?: return
    val rightTab = state.tab(state.compareTabId) ?: state.tabs.getOrNull(1) ?: state.tabs.first()
    val tc = tc()

    // Right side shows all entries when filter is off; mirrors left filter when on.
    val effectiveRightTab = if (state.compareFilterRight)
        rightTab.copy(filter = leftTab.filter)
    else
        rightTab.copy(filter = Filter())

    @Composable
    fun filterPanelFor(tab: LogTab) {
        if (!state.filterVisible) return
        FilterPanel(
            tab = tab, sequences = state.sequences, savedFilters = state.savedFilters,
            activeSavedFilterId = state.activeSavedFilterId(tab.id), tagUsage = state.tagUsage,
            fpState = state.fpState,
            newHlPat = state.newHlPat, newHlRx = state.newHlRx, newHlColor = state.newHlColor,
            newSeqText = state.newSeqText, newSeqRegex = state.newSeqRegex,
            newSeqEndText = state.newSeqEndText, newSeqEndRegex = state.newSeqEndRegex,
            newSeqStartTag = state.newSeqTag, newSeqEndTag = state.newSeqEndTag,
            newSeqColor = state.newSeqColor,
            onToggleLevel       = { state.toggleLevel(tab.id, it) },
            onSetFilterMode     = { state.setFilterMode(tab.id, it) },
            onToggleTag         = { state.toggleTag(tab.id, it) },
            onClearTags         = { state.clearTags(tab.id) },
            onToggleExcludeTag  = { state.toggleExcludeTag(tab.id, it) },
            onSetKw             = { state.setKw(tab.id, it) },
            onToggleKwRx        = { state.toggleKwRx(tab.id) },
            onSetExcludeKw      = { state.setExcludeKw(tab.id, it) },
            onToggleExcludeKwRx = { state.toggleExcludeKwRx(tab.id) },
            onToggleSeq         = { state.toggleSeq(tab.id) },
            onAddSeq            = { t, r, c, st, et, er, eg -> state.addSequence(t, r, c, st, et, er, eg) },
            onRemoveSeq         = { state.removeSequence(it) },
            onToggleSeqEnabled  = { state.toggleSequence(it) },
            onSetSeqColor       = { id, c -> state.setSequenceColor(id, c) },
            onUpdateSeq         = { id, text, rx, tag, endText, endRx, endTag ->
                state.updateSequence(id, text, rx, tag, endText, endRx, endTag)
            },
            onToggleManualCollapse = { state.toggleManualCollapse(tab.id, it) },
            onRemoveManualCollapse = { state.removeManualCollapse(tab.id, it) },
            onAddMessageRule    = { include, pattern, regex, tag, prefix, target ->
                state.addMessageRule(tab.id, include, pattern, regex, tag, prefix, target)
            },
            onToggleMessageRule = { state.toggleMessageRule(tab.id, it) },
            onRemoveMessageRule = { state.removeMessageRule(tab.id, it) },
            onMoveSeqUp         = { state.moveSequenceUp(it) },
            onMoveSeqDown       = { state.moveSequenceDown(it) },
            onSetNewSeqText     = { state.newSeqText = it },
            onSetNewSeqRx       = { state.newSeqRegex = it },
            onSetNewSeqEndText  = { state.newSeqEndText = it },
            onSetNewSeqEndRx    = { state.newSeqEndRegex = it },
            onSetNewSeqStartTag = { state.newSeqTag = it },
            onSetNewSeqEndTag   = { state.newSeqEndTag = it },
            onSetNewSeqColor    = { state.newSeqColor = it },
            onAddHl             = { p, r, c -> state.addHl(tab.id, p, r, c) },
            onRemoveHl          = { state.removeHl(tab.id, it) },
            onToggleHl          = { state.toggleHl(tab.id, it) },
            onSetHlColor        = { id, c -> state.setHighlighterColor(tab.id, id, c) },
            onSetNewHlPat       = { state.newHlPat = it },
            onSetNewHlRx        = { state.newHlRx = it },
            onSetNewHlColor     = { state.newHlColor = it },
            onLoadFilter        = { state.requestLoadFilter(tab.id, it) },
            onDeleteSF          = { state.deleteSF(it) },
            onOpenSFDialog      = { state.sfDialog = true; state.sfTabId = tab.id; state.sfName = "" },
            onSetKwInTag        = { state.setKwInTag(tab.id, it) },
            onToggleKwInTagRx   = { state.toggleKwInTagRx(tab.id) },
            onAddPkgPrefix      = { state.addPkgPrefix(tab.id, it) },
            onRemovePkgPrefix   = { state.removePkgPrefix(tab.id, it) },
            onExportFilters     = { state.exportFiltersToFile() },
            onImportFilters     = { state.importFiltersFromFile() },
            onImportFiltersFromFiles = { files -> files.forEach { state.importFiltersFromFile(it) } },
            onClearFilter       = { state.requestClearFilter(tab.id) },
            mostUsedTagLimit    = state.settings.mostUsedTagLimit,
            width               = state.filterPanelWidth,
        )
        HDivider { d -> state.filterPanelWidth = (state.filterPanelWidth + d).coerceIn(140f, 420f) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val totalWidthDp = maxWidth.value

        Row(Modifier.fillMaxSize()) {
            // ── Left panel ──────────────────────────────────────────
            Column(
                Modifier
                    .width((totalWidthDp * state.compareSplit).dp)
                    .fillMaxHeight()
                    .border(BorderStroke(1.dp, tc.br.copy(.53f)))
            ) {
                Row(
                    Modifier.fillMaxWidth().background(tc.p2).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.tabs.forEach { t -> PillBtn(t.filename, active = t.id == leftTab.id) { state.activeTabId = t.id } }
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    filterPanelFor(leftTab)
                    LogViewer(
                        tab = leftTab, sequences = state.sequences, modifier = Modifier.weight(1f),
                        onSelRow          = { id, m, r -> state.selRow(leftTab.id, id, m, r) },
                        onSelRowRange     = { ids -> state.setSelectedRows(leftTab.id, ids) },
                        onCtxMenu         = { id, x, y, sel, panelSel -> state.ctx = CtxMenuState(leftTab.id, id, x, y, sel, panelSel) },
                        onToggleGroup     = { state.toggleGroup(leftTab.id, it) },
                        onClearFilter     = { state.requestClearFilter(leftTab.id) },
                        onExpandAll       = { state.expandAll(leftTab.id) },
                        onCollapseAll     = { state.collapseAll(leftTab.id) },
                        onToggleUnfiltered = { state.toggleUnfiltered(leftTab.id) },
                    )
                }
            }

            // ── Split divider ────────────────────────────────────────
            HDivider { delta ->
                val newFrac = (state.compareSplit * totalWidthDp + delta) / totalWidthDp
                state.compareSplit = newFrac.coerceIn(0.2f, 0.8f)
            }

            // ── Right panel ──────────────────────────────────────────
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    Modifier.fillMaxWidth().background(tc.p2).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.tabs.forEach { t -> PillBtn(t.filename, active = t.id == rightTab.id) { state.compareTabId = t.id } }
                    Spacer(Modifier.weight(1f))
                    PillBtn(
                        label  = if (state.compareFilterRight) "⊟ Filter" else "⊞ Filter",
                        active = state.compareFilterRight,
                        onClick = { state.compareFilterRight = !state.compareFilterRight },
                    )
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    LogViewer(
                        tab = effectiveRightTab, sequences = state.sequences, modifier = Modifier.weight(1f),
                        onSelRow          = { id, m, r -> state.selRow(rightTab.id, id, m, r) },
                        onSelRowRange     = { ids -> state.setSelectedRows(rightTab.id, ids) },
                        onCtxMenu         = { id, x, y, sel, panelSel -> state.ctx = CtxMenuState(rightTab.id, id, x, y, sel, panelSel) },
                        onToggleGroup     = { state.toggleGroup(rightTab.id, it) },
                        onClearFilter     = { state.requestClearFilter(rightTab.id) },
                        onExpandAll       = { state.expandAll(rightTab.id) },
                        onCollapseAll     = { state.collapseAll(rightTab.id) },
                        onToggleUnfiltered = { state.toggleUnfiltered(rightTab.id) },
                    )
                    if (state.annotationVisible) {
                        HDivider { d -> state.annotationPanelWidth = (state.annotationPanelWidth - d).coerceIn(180f, 500f) }
                        AnnotationPanel(
                            tab                 = leftTab,
                            headerNote          = leftTab.filename,
                            recentNotes         = state.recentNotes,
                            recentNotesMenuOpen = state.recentNotesMenuOpen,
                            onToggleMd          = { state.toggleMd(leftTab.id) },
                            onCopy              = { state.copyAnn(leftTab.id) },
                            onSave              = { state.saveAnalysis(leftTab.id) },
                            onToggleRecentNotes = { state.recentNotesMenuOpen = !state.recentNotesMenuOpen },
                            onOpenNote          = { state.openNoteFile(leftTab.id, it) },
                            onUpdatePrefix      = { state.setPrefix(leftTab.id, it) },
                            onUpdateSuffix      = { state.setSuffix(leftTab.id, it) },
                            onUpdateBlock       = { bid, t -> state.updateBlock(leftTab.id, bid, t) },
                            onRemoveBlock       = { state.removeBlock(leftTab.id, it) },
                            onMoveBlock         = { bid, d -> state.moveBlock(leftTab.id, bid, d) },
                            onAddNoteAfter      = { state.addNoteBlock(leftTab.id, it) },
                            width               = state.annotationPanelWidth,
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
    Row(
        Modifier.fillMaxWidth().height(36.dp).background(tc.p2).border(BorderStroke(1.dp, tc.br)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabOverflowRow(state = state, modifier = Modifier.weight(1f).fillMaxHeight())
        ToolbarBtn(if (state.filterVisible) "⊟ Filter" else "⊞ Filter", active = state.filterVisible) { state.filterVisible = !state.filterVisible }
        Spacer(Modifier.width(2.dp))
        ToolbarBtn(if (state.annotationVisible) "⊟ Notes" else "⊞ Notes", active = state.annotationVisible) { state.annotationVisible = !state.annotationVisible }
        Spacer(Modifier.width(2.dp))
        ToolbarBtn(if (state.compareMode) "⊟ Compare" else "⊠ Compare", active = state.compareMode) { state.compareMode = !state.compareMode }
        Spacer(Modifier.width(2.dp))
        ToolbarBtn("Open") {
            val fd = FileDialog(null as Frame?, "Open Log File", FileDialog.LOAD)
            fd.setFilenameFilter { _, name -> name.endsWith(".log") || name.endsWith(".txt") }
            fd.isVisible = true
            fd.file?.let { state.openFile(File(fd.directory, it)) }
        }
        if (state.recentFiles.isNotEmpty()) {
            ToolbarBtn("▾", active = state.recentMenuOpen) { state.recentMenuOpen = !state.recentMenuOpen }
        }
        Spacer(Modifier.width(2.dp))
        ToolbarBtn("⚙") { state.settingsOpen = true }
    }
}

// Renders visible tabs and an overflow "▾ N" button for any that don't fit.
// Drag-and-drop reorder: press-and-move >8px to start drag; a 3dp accent line marks the drop point.
@Composable
private fun TabOverflowRow(state: AppState, modifier: Modifier) {
    val tc       = tc()
    val density  = LocalDensity.current.density
    val tabWidths    = remember { mutableStateMapOf<String, Int>() }
    val tabXPx       = remember { mutableStateMapOf<String, Int>() }
    var containerPx  by remember { mutableStateOf(Int.MAX_VALUE) }
    var dragTabId    by remember { mutableStateOf<String?>(null) }
    var dragOffsetX  by remember { mutableStateOf(0f) }
    var dropBeforeId by remember { mutableStateOf<String?>(null) }
    var overflowOpen by remember { mutableStateOf(false) }

    SideEffect {
        val ids = state.tabs.map { it.id }.toSet()
        tabWidths.keys.retainAll(ids); tabXPx.keys.retainAll(ids)
    }

    val (visibleTabs, overflowTabs) = remember(
        state.tabs, state.activeTabId, containerPx, tabWidths.toMap()
    ) {
        if (containerPx == Int.MAX_VALUE || tabWidths.isEmpty()) return@remember state.tabs to emptyList()
        val ovBtnPx = (40 * density).toInt()
        val totalPx = state.tabs.sumOf { tabWidths[it.id] ?: (160 * density).toInt() }
        val needsOv = totalPx > containerPx
        val activeW = tabWidths[state.activeTabId] ?: (160 * density).toInt()
        var budget  = (if (needsOv) containerPx - ovBtnPx else containerPx) - activeW
        val vis = mutableListOf<String>(); val ov = mutableListOf<String>()
        for (tab in state.tabs) {
            if (tab.id == state.activeTabId) continue
            val w = tabWidths[tab.id] ?: (160 * density).toInt()
            if (budget >= w) { vis += tab.id; budget -= w } else ov += tab.id
        }
        vis += state.activeTabId
        val visSet = vis.toSet()
        state.tabs.filter { it.id in visSet } to state.tabs.filter { it.id in ov }
    }

    Row(
        modifier
            .onSizeChanged { containerPx = it.width }
            .pointerInput("tabdrag") {
                var downPos = Offset.Zero
                var downId: String? = null
                var dragging = false
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull() ?: continue
                        when (ev.type) {
                            PointerEventType.Press -> {
                                downPos = ch.position; dragging = false; dragOffsetX = 0f
                                downId = tabXPx.entries
                                    .sortedByDescending { it.value }
                                    .firstOrNull { (_, x) -> ch.position.x.toInt() >= x }?.key
                            }
                            PointerEventType.Move -> {
                                if (downId != null && !dragging && (ch.position - downPos).getDistance() > 8f) {
                                    dragging = true; dragTabId = downId
                                }
                                if (dragging && dragTabId != null) {
                                    ch.consume()
                                    dragOffsetX = ch.position.x - downPos.x
                                    // Drop target = first tab whose center is to the right of the dragged tab's current center
                                    val tabStartX = tabXPx[dragTabId] ?: 0
                                    val tabW      = tabWidths[dragTabId] ?: 0
                                    val dragCenter = (tabStartX + tabW / 2 + dragOffsetX).toInt()
                                    dropBeforeId = tabXPx.entries
                                        .filter { (id, _) -> id != dragTabId }
                                        .sortedBy { it.value }
                                        .firstOrNull { (id, x) ->
                                            dragCenter < x + (tabWidths[id] ?: 0) / 2
                                        }?.key
                                }
                            }
                            PointerEventType.Release -> {
                                if (dragging) {
                                    val from = dragTabId; val to = dropBeforeId
                                    if (from != null && to != null) state.reorderTabs(from, to)
                                }
                                dragTabId = null; dragOffsetX = 0f; dropBeforeId = null
                                downId = null; dragging = false
                            }
                            else -> {}
                        }
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visibleTabs.forEach { tab ->
            val isDragging = tab.id == dragTabId
            Box(
                Modifier.fillMaxHeight()
                    .onSizeChanged { tabWidths[tab.id] = it.width }
                    .onGloballyPositioned { tabXPx[tab.id] = it.positionInRoot().x.toInt() }
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { if (isDragging) translationX = dragOffsetX }
                    .then(if (dropBeforeId == tab.id && !isDragging) Modifier.drawBehind {
                        drawRect(tc.ac, topLeft = Offset.Zero, size = Size(3f, size.height))
                    } else Modifier)
            ) {
                TabItem(
                    tab = tab,
                    isActive = tab.id == state.activeTabId,
                    showClose = true,
                    onClick = { if (dragTabId == null) state.activeTabId = tab.id },
                    onClose = { state.closeTab(tab.id) },
                )
            }
        }
        if (overflowTabs.isNotEmpty()) {
            Box(Modifier.fillMaxHeight()) {
                ToolbarBtn("▾ ${overflowTabs.size}", active = overflowOpen) { overflowOpen = !overflowOpen }
                if (overflowOpen) {
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = androidx.compose.ui.unit.IntOffset(0, (36 * density).toInt()),
                        onDismissRequest = { overflowOpen = false },
                        properties = PopupProperties(focusable = true),
                    ) {
                        Box(
                            Modifier
                                .width(240.dp)
                                .background(tc.p, RoundedCornerShape(7.dp))
                                .border(1.dp, tc.br, RoundedCornerShape(7.dp)),
                        ) {
                            Column(Modifier.padding(vertical = 4.dp)) {
                                overflowTabs.forEach { tab ->
                                    HoverBox(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { state.moveTabToFront(tab.id); overflowOpen = false },
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
    Column(
        Modifier.width(420.dp).background(tc.p, RoundedCornerShape(8.dp))
            .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppText("Settings", color = tc.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppText("THEME", color = tc.td, fontSize = 11.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemePreset.entries.forEach { preset ->
                    val active = state.settings.theme == preset
                    Box(
                        Modifier.border(1.dp, if (active) tc.ac else tc.br, RoundedCornerShape(4.dp))
                            .background(if (active) tc.ac.copy(.15f) else Color.Transparent, RoundedCornerShape(4.dp))
                            .clickable { state.settings = state.settings.copy(theme = preset) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) { AppText(preset.label, color = if (active) tc.ac else tc.ts, fontSize = 12.sp) }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppText("FONT SIZE — ${state.settings.fontSize}sp", color = tc.td, fontSize = 11.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(10, 11, 12, 13, 14, 15, 16).forEach { size ->
                    val active = state.settings.fontSize == size
                    Box(
                        Modifier.border(1.dp, if (active) tc.ac else tc.br, RoundedCornerShape(4.dp))
                            .background(if (active) tc.ac.copy(.15f) else Color.Transparent, RoundedCornerShape(4.dp))
                            .clickable { state.settings = state.settings.copy(fontSize = size) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) { AppText("$size", color = if (active) tc.ac else tc.ts, fontSize = 13.sp) }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppText("FONT FAMILY", color = tc.td, fontSize = 11.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(true to "Monospace", false to "Proportional").forEach { (mono, label) ->
                    val active = state.settings.fontMono == mono
                    Box(
                        Modifier.border(1.dp, if (active) tc.ac else tc.br, RoundedCornerShape(4.dp))
                            .background(if (active) tc.ac.copy(.15f) else Color.Transparent, RoundedCornerShape(4.dp))
                            .clickable { state.settings = state.settings.copy(fontMono = mono) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) { AppText(label, color = if (active) tc.ac else tc.ts, fontSize = 13.sp) }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppText("MOST-USED TAGS — ${state.settings.mostUsedTagLimit}", color = tc.td, fontSize = 11.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 3, 5, 10, 20).forEach { limit ->
                    val active = state.settings.mostUsedTagLimit == limit
                    Box(
                        Modifier.border(1.dp, if (active) tc.ac else tc.br, RoundedCornerShape(4.dp))
                            .background(if (active) tc.ac.copy(.15f) else Color.Transparent, RoundedCornerShape(4.dp))
                            .clickable { state.settings = state.settings.copy(mostUsedTagLimit = limit) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) { AppText(limit.toString(), color = if (active) tc.ac else tc.ts, fontSize = 13.sp) }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppText("DEFAULT SAVE FOLDER", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AppText(state.settings.defaultSaveDir ?: "(not set)", color = tc.ts, fontSize = 11.sp, fontFamily = MONO,
                    modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
                ToolbarBtn("Browse") { state.pickSaveFolder() }
                if (state.settings.defaultSaveDir != null) ToolbarBtn("Clear") { state.settings = state.settings.copy(defaultSaveDir = null) }
            }
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            ToolbarBtn("Done", active = true, onClick = onDismiss)
        }
    }
}

// ── Shared ────────────────────────────────────────────────────────────
@Composable
private fun TabItem(tab: LogTab, isActive: Boolean, showClose: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    val tc = tc()
    var hov by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxHeight()
            .background(if (isActive) tc.bg else if (hov) tc.p else tc.p2)
            .border(BorderStroke(2.dp, if (isActive) tc.ac else Color.Transparent))
            .onPointerEvent(PointerEventType.Enter) { hov = true }
            .onPointerEvent(PointerEventType.Exit)  { hov = false }
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        AppText(
            tab.filename,
            color = if (isActive) tc.tx else tc.ts,
            fontSize = 12.sp,
            fontFamily = MONO,
            modifier = Modifier.widthIn(max = 180.dp),
            overflow = TextOverflow.Ellipsis,
        )
        if (showClose) AppText("×", color = tc.td, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onClose))
    }
}

@Composable
private fun CtxItem(icon: String, label: String, onClick: () -> Unit) {
    HoverBox(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppText(icon, color = LocalTheme.current.td, fontSize = 11.sp, fontFamily = MONO, modifier = Modifier.width(20.dp))
            AppText(label, color = LocalTheme.current.tx, fontSize = 12.sp)
        }
    }
}
