@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.openlog.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.openlog.model.*
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun App() {
    val state = remember { AppState() }
    val theme = themeColors(state.settings.theme)

    CompositionLocalProvider(
        LocalTheme    provides theme,
        LocalFontBase provides state.settings.fontSize,
        LocalUseMono  provides state.settings.fontMono,
    ) {
        val tc = tc()
        Box(Modifier.fillMaxSize().background(tc.bg)) {
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
                Box(
                    Modifier.fillMaxSize().clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { state.ctx = null },
                    )
                ) {
                    if (entry != null) {
                        // Position is already in dp (converted from px in LogRow)
                        Box(
                            Modifier
                                .offset(x = ctx.x.dp, y = ctx.y.dp)
                                .background(tc.p, RoundedCornerShape(7.dp))
                                .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                                .width(270.dp),
                        ) {
                            Column {
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
                                // Tag actions
                                CtxItem("#",  "Include tag")    { state.addTagFilterFromCtx() }
                                CtxItem("◉", "Highlight tag")   { state.addHlTagFromCtx() }
                                CtxItem("−",  "Exclude tag")    { state.addExcludeTagFromCtx() }
                                Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
                                // Annotation
                                val selCount = ctxTab.selected.size
                                if (selCount > 1) {
                                    CtxItem("◆+", "Annotate $selCount selected lines") {
                                        state.requestAddAnn(ctx.tabId, ctxTab.selected.toSortedSet().toList())
                                    }
                                }
                                CtxItem("◆", "Add to annotation") { state.requestAddAnn(ctx.tabId, listOf(ctx.entryId)) }
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
                val ctxTab = state.tab(req.tabId)
                val rows = req.logIds.mapNotNull { ctxTab?.rmap?.get(it) }
                Dialog(onDismissRequest = { state.addAnnRequest = null }) {
                    AddAnnDialog(
                        rows = rows,
                        onConfirm = { caption -> state.confirmAddAnn(req.tabId, req.logIds, caption) },
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
                        AppText("Saves: levels · tags · keyword · highlighters · seqOn",
                            color = tc2.td, fontSize = 11.sp, maxLines = 2)
                        Spacer(Modifier.height(14.dp))
                        InlineField(state.sfName, { state.sfName = it }, "Preset name…", Modifier.fillMaxWidth(), fontSize = 13.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillBtn("Save", active = state.sfName.isNotBlank()) {
                                if (state.sfName.isNotBlank()) state.saveFilter(state.sfTabId ?: return@PillBtn, state.sfName)
                            }
                            PillBtn("Cancel", active = false) { state.sfDialog = false }
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
        AppText("Add annotation", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillBtn("Add annotation", active = true) { onConfirm(caption) }
            PillBtn("Cancel", active = false, onClick = onDismiss)
        }
    }
}

// ── FileView ──────────────────────────────────────────────────────────
@Composable
private fun FileView(state: AppState, tab: LogTab) {
    Row(Modifier.fillMaxSize()) {
        if (state.filterVisible) {
            FilterPanel(
                tab = tab, sequences = state.sequences, savedFilters = state.savedFilters,
                tagUsage = state.tagUsage,
                newHlPat = state.newHlPat, newHlRx = state.newHlRx, newHlColor = state.newHlColor,
                newSeqText = state.newSeqText, newSeqRegex = state.newSeqRegex, newSeqColor = state.newSeqColor,
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
                onAddSeq            = { t, r, c -> state.addSequence(t, r, c) },
                onRemoveSeq         = { state.removeSequence(it) },
                onToggleSeqEnabled  = { state.toggleSequence(it) },
                onSetSeqColor       = { id, c -> state.setSequenceColor(id, c) },
                onMoveSeqUp         = { state.moveSequenceUp(it) },
                onMoveSeqDown       = { state.moveSequenceDown(it) },
                onSetNewSeqText     = { state.newSeqText = it },
                onSetNewSeqRx       = { state.newSeqRegex = it },
                onSetNewSeqColor    = { state.newSeqColor = it },
                onAddHl             = { p, r, c -> state.addHl(tab.id, p, r, c) },
                onRemoveHl          = { state.removeHl(tab.id, it) },
                onToggleHl          = { state.toggleHl(tab.id, it) },
                onSetNewHlPat       = { state.newHlPat = it },
                onSetNewHlRx        = { state.newHlRx = it },
                onSetNewHlColor     = { state.newHlColor = it },
                onLoadFilter        = { state.loadFilter(tab.id, it) },
                onDeleteSF          = { state.deleteSF(it) },
                onOpenSFDialog      = { state.sfDialog = true; state.sfTabId = tab.id; state.sfName = "" },
                onSetKwInTag        = { state.setKwInTag(tab.id, it) },
                onToggleKwInTagRx   = { state.toggleKwInTagRx(tab.id) },
                onAddPkgPrefix      = { state.addPkgPrefix(tab.id, it) },
                onRemovePkgPrefix   = { state.removePkgPrefix(tab.id, it) },
                onSetPidTidFilter   = { state.setPidTidFilter(tab.id, it) },
                onExportFilters     = { state.exportFiltersToFile() },
                onImportFilters     = { state.importFiltersFromFile() },
                onClearFilter       = { state.clearFilter(tab.id) },
                width               = state.filterPanelWidth,
            )
            HDivider { delta -> state.filterPanelWidth = (state.filterPanelWidth + delta).coerceIn(140f, 420f) }
        }
        LogViewer(
            tab = tab, sequences = state.sequences, modifier = Modifier.weight(1f),
            onSelRow        = { id, multi, range -> state.selRow(tab.id, id, multi, range) },
            onSelRowRange   = { from, to -> state.selRowRange(tab.id, from, to) },
            onCtxMenu       = { id, x, y, sel -> state.ctx = CtxMenuState(tab.id, id, x, y, sel) },
            onToggleGroup   = { state.toggleGroup(tab.id, it) },
            onClearFilter   = { state.clearFilter(tab.id) },
            onExpandAll     = { state.expandAll(tab.id) },
            onCollapseAll   = { state.collapseAll(tab.id) },
            onToggleUnfiltered = { state.toggleUnfiltered(tab.id) },
        )
        if (state.annotationVisible) {
            HDivider { delta -> state.annotationPanelWidth = (state.annotationPanelWidth - delta).coerceIn(180f, 500f) }
            AnnotationPanel(
                tab             = tab,
                onToggleMd      = { state.toggleMd(tab.id) },
                onCopy          = { state.copyAnn(tab.id) },
                onSave          = { state.saveAnalysis(tab.id) },
                onUpdatePrefix  = { state.setPrefix(tab.id, it) },
                onUpdateSuffix  = { state.setSuffix(tab.id, it) },
                onUpdateBlock   = { blockId, text -> state.updateBlock(tab.id, blockId, text) },
                onRemoveBlock   = { state.removeBlock(tab.id, it) },
                onMoveBlock     = { blockId, d -> state.moveBlock(tab.id, blockId, d) },
                onAddNoteAfter  = { state.addNoteBlock(tab.id, it) },
                width           = state.annotationPanelWidth,
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
            tab = tab, sequences = state.sequences, savedFilters = state.savedFilters, tagUsage = state.tagUsage,
            newHlPat = state.newHlPat, newHlRx = state.newHlRx, newHlColor = state.newHlColor,
            newSeqText = state.newSeqText, newSeqRegex = state.newSeqRegex, newSeqColor = state.newSeqColor,
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
            onAddSeq            = { t, r, c -> state.addSequence(t, r, c) },
            onRemoveSeq         = { state.removeSequence(it) },
            onToggleSeqEnabled  = { state.toggleSequence(it) },
            onSetSeqColor       = { id, c -> state.setSequenceColor(id, c) },
            onMoveSeqUp         = { state.moveSequenceUp(it) },
            onMoveSeqDown       = { state.moveSequenceDown(it) },
            onSetNewSeqText     = { state.newSeqText = it },
            onSetNewSeqRx       = { state.newSeqRegex = it },
            onSetNewSeqColor    = { state.newSeqColor = it },
            onAddHl             = { p, r, c -> state.addHl(tab.id, p, r, c) },
            onRemoveHl          = { state.removeHl(tab.id, it) },
            onToggleHl          = { state.toggleHl(tab.id, it) },
            onSetNewHlPat       = { state.newHlPat = it },
            onSetNewHlRx        = { state.newHlRx = it },
            onSetNewHlColor     = { state.newHlColor = it },
            onLoadFilter        = { state.loadFilter(tab.id, it) },
            onDeleteSF          = { state.deleteSF(it) },
            onOpenSFDialog      = { state.sfDialog = true; state.sfTabId = tab.id; state.sfName = "" },
            onSetKwInTag        = { state.setKwInTag(tab.id, it) },
            onToggleKwInTagRx   = { state.toggleKwInTagRx(tab.id) },
            onAddPkgPrefix      = { state.addPkgPrefix(tab.id, it) },
            onRemovePkgPrefix   = { state.removePkgPrefix(tab.id, it) },
            onSetPidTidFilter   = { state.setPidTidFilter(tab.id, it) },
            onExportFilters     = { state.exportFiltersToFile() },
            onImportFilters     = { state.importFiltersFromFile() },
            onClearFilter       = { state.clearFilter(tab.id) },
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
                        onSelRowRange     = { f, t -> state.selRowRange(leftTab.id, f, t) },
                        onCtxMenu         = { id, x, y, sel -> state.ctx = CtxMenuState(leftTab.id, id, x, y, sel) },
                        onToggleGroup     = { state.toggleGroup(leftTab.id, it) },
                        onClearFilter     = { state.clearFilter(leftTab.id) },
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
                        onSelRowRange     = { f, t -> state.selRowRange(rightTab.id, f, t) },
                        onCtxMenu         = { id, x, y, sel -> state.ctx = CtxMenuState(rightTab.id, id, x, y, sel) },
                        onToggleGroup     = { state.toggleGroup(rightTab.id, it) },
                        onClearFilter     = { state.clearFilter(rightTab.id) },
                        onExpandAll       = { state.expandAll(rightTab.id) },
                        onCollapseAll     = { state.collapseAll(rightTab.id) },
                        onToggleUnfiltered = { state.toggleUnfiltered(rightTab.id) },
                    )
                    if (state.annotationVisible) {
                        HDivider { d -> state.annotationPanelWidth = (state.annotationPanelWidth - d).coerceIn(180f, 500f) }
                        AnnotationPanel(
                            tab            = rightTab,
                            onToggleMd     = { state.toggleMd(rightTab.id) },
                            onCopy         = { state.copyAnn(rightTab.id) },
                            onSave         = { state.saveAnalysis(rightTab.id) },
                            onUpdatePrefix = { state.setPrefix(rightTab.id, it) },
                            onUpdateSuffix = { state.setSuffix(rightTab.id, it) },
                            onUpdateBlock  = { bid, t -> state.updateBlock(rightTab.id, bid, t) },
                            onRemoveBlock  = { state.removeBlock(rightTab.id, it) },
                            onMoveBlock    = { bid, d -> state.moveBlock(rightTab.id, bid, d) },
                            onAddNoteAfter = { state.addNoteBlock(rightTab.id, it) },
                            width          = state.annotationPanelWidth,
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
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            state.tabs.forEach { tab ->
                TabItem(tab, tab.id == state.activeTabId, state.tabs.size > 1,
                    onClick = { state.activeTabId = tab.id },
                    onClose = { state.closeTab(tab.id) })
            }
        }
        ToolbarBtn(if (state.filterVisible) "⊟ Filter" else "⊞ Filter", active = state.filterVisible) { state.filterVisible = !state.filterVisible }
        Spacer(Modifier.width(2.dp))
        ToolbarBtn(if (state.annotationVisible) "⊟ Notes" else "⊞ Notes", active = state.annotationVisible) { state.annotationVisible = !state.annotationVisible }
        Spacer(Modifier.width(4.dp))
        ToolbarBtn("Open") {
            val fd = FileDialog(null as Frame?, "Open Log File", FileDialog.LOAD)
            fd.setFilenameFilter { _, name -> name.endsWith(".log") || name.endsWith(".txt") }
            fd.isVisible = true
            fd.file?.let { state.openFile(File(fd.directory, it)) }
        }
        ToolbarBtn("+") { state.addTab() }
        ToolbarBtn(if (state.compareMode) "⊟ Compare" else "⊠ Compare", active = state.compareMode) { state.compareMode = !state.compareMode }
        ToolbarBtn("⚙") { state.settingsOpen = true }
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
            AppText("THEME", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemePreset.entries.forEach { preset ->
                    val active = state.settings.theme == preset
                    Box(
                        Modifier.border(1.dp, if (active) tc.ac else tc.br, RoundedCornerShape(4.dp))
                            .background(if (active) tc.ac.copy(.15f) else Color.Transparent, RoundedCornerShape(4.dp))
                            .clickable { state.settings = state.settings.copy(theme = preset) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    ) { AppText(preset.label, color = if (active) tc.ac else tc.ts, fontSize = 10.sp) }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppText("FONT SIZE — ${state.settings.fontSize}sp", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(10, 11, 12, 13, 14, 15, 16).forEach { size ->
                    val active = state.settings.fontSize == size
                    Box(
                        Modifier.border(1.dp, if (active) tc.ac else tc.br, RoundedCornerShape(4.dp))
                            .background(if (active) tc.ac.copy(.15f) else Color.Transparent, RoundedCornerShape(4.dp))
                            .clickable { state.settings = state.settings.copy(fontSize = size) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    ) { AppText("$size", color = if (active) tc.ac else tc.ts, fontSize = 11.sp) }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppText("FONT FAMILY", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(true to "Monospace", false to "Proportional").forEach { (mono, label) ->
                    val active = state.settings.fontMono == mono
                    Box(
                        Modifier.border(1.dp, if (active) tc.ac else tc.br, RoundedCornerShape(4.dp))
                            .background(if (active) tc.ac.copy(.15f) else Color.Transparent, RoundedCornerShape(4.dp))
                            .clickable { state.settings = state.settings.copy(fontMono = mono) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) { AppText(label, color = if (active) tc.ac else tc.ts, fontSize = 11.sp) }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppText("DEFAULT SAVE FOLDER", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AppText(state.settings.defaultSaveDir ?: "(not set)", color = tc.ts, fontSize = 11.sp, fontFamily = MONO,
                    modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
                PillBtn("Browse", active = false) { state.pickSaveFolder() }
                if (state.settings.defaultSaveDir != null) PillBtn("Clear", active = false) { state.settings = state.settings.copy(defaultSaveDir = null) }
            }
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            PillBtn("Done", active = true, onClick = onDismiss)
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
        AppText(tab.filename, color = if (isActive) tc.tx else tc.ts, fontSize = 12.sp, fontFamily = MONO)
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
