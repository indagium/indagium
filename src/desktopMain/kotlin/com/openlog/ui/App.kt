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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
                .then(
                    if (dc != null) Modifier.pointerHoverIcon(
                        PointerIcon(dc!!),
                        overrideDescendants = true
                    ) else Modifier
                )
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
                    activeTab != null -> key(activeTab.id) { FileView(state, activeTab) }
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
                        // Position is already in dp (converted from px in LogRow)
                        Box(
                            Modifier
                                .offset(x = x, y = y)
                                .shadow(8.dp, RoundedCornerShape(7.dp))
                                .background(tc.p, RoundedCornerShape(7.dp))
                                .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                                .width(menuWidth)
                                .heightIn(max = (maxHeight - y - 8.dp).coerceAtLeast(160.dp))
                                .verticalScroll(menuScroll),
                        ) {
                            Column {
                                Box(
                                    Modifier.fillMaxWidth().background(tc.p2)
                                        .clickable {
                                            val ids = if (selCount > 1) selectedIds.toSortedSet().toList() else listOf(
                                                ctx.entryId
                                            )
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
                                CtxDivider()
                                // Preview row
                                Column(
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
                                // Selected text actions
                                if (ctx.selText.isNotBlank()) {
                                    CtxItem(Icons.Outlined.Bookmark, "Highlight selection") { state.addHlFromCtx() }
                                    CtxItem(Icons.Outlined.Layers, "Add as sequence") { state.addSeqFromCtx() }
                                    CtxItem(Icons.Outlined.Search, "Filter by keyword") { state.addKwFilterFromCtx() }
                                    CtxItem(Icons.Outlined.Block, "Exclude keyword") { state.addNegKwFromCtx() }
                                    CtxDivider()
                                }
                                if (state.pendingSequenceStart != null) {
                                    CtxItem(
                                        Icons.Outlined.Flag,
                                        "Complete sequence end"
                                    ) { state.completeSequenceEndFromCtx() }
                                }
                                CtxItem(
                                    Icons.Outlined.PlayArrow,
                                    "Set sequence start"
                                ) { state.setSequenceStartFromCtx() }
                                CtxItem(
                                    Icons.Outlined.ArrowUpward,
                                    "Collapse to file start"
                                ) { state.collapseToStartFromCtx() }
                                CtxItem(
                                    Icons.Outlined.ArrowDownward,
                                    "Collapse to file end"
                                ) { state.collapseToEndFromCtx() }
                                CtxItem(
                                    Icons.Outlined.VisibilityOff,
                                    "Hide messages like this"
                                ) { state.hideMessagesLikeCtx() }
                                CtxItem(
                                    Icons.Outlined.Visibility,
                                    "Show messages like this"
                                ) { state.showOnlyMessagesLikeCtx() }
                                CtxDivider()
                                // Tag actions
                                CtxItem(
                                    Icons.AutoMirrored.Outlined.Label,
                                    "Include tag"
                                ) { state.addTagFilterFromCtx() }
                                CtxItem(Icons.Outlined.Bookmark, "Highlight tag") { state.addHlTagFromCtx() }
                                CtxItem(
                                    Icons.AutoMirrored.Outlined.LabelOff,
                                    "Exclude tag"
                                ) { state.addExcludeTagFromCtx() }
                                CtxDivider()
                                if (selCount > 1) {
                                    CtxItem(Icons.Outlined.ContentCopy, "Copy $selCount selected lines") {
                                        state.copySelectedLines(ctx.tabId); state.ctx = null
                                    }
                                }
                                CtxItem(Icons.Outlined.ContentCopy, "Copy line") {
                                    val pid = if (entry.pid > 0) "  ${entry.pid.toString().padStart(5)} ${
                                        entry.tid.toString().padStart(5)
                                    }" else ""
                                    state.copyToClipboard("${entry.ts}$pid  ${entry.level.key}  ${entry.tag}: ${entry.msg}")
                                    state.ctx = null
                                }
                                CtxItem(Icons.Outlined.Code, "Copy as Markdown") {
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
    val accent = if (danger) DANGER_RED else tc.ac
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

@Composable
private fun BoundFilterPanel(state: AppState, tab: LogTab) {
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
    )
    HDivider { delta -> state.updateFilterPanelWidth(state.filterPanelWidth + delta) }
}

// ── FileView ──────────────────────────────────────────────────────────
@Composable
private fun FileView(state: AppState, tab: LogTab) {
    Row(Modifier.fillMaxSize()) {
        BoundFilterPanel(state, tab)
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
                width = state.annotationPanelWidth,
            )
        }
    }
}

// ── CompareView ───────────────────────────────────────────────────────
@Composable
private fun CompareView(state: AppState) {
    val leftTab = state.tab(state.activeTabId) ?: state.tabs.firstOrNull() ?: return
    val rightTab = state.tab(state.compareTabId) ?: state.tabs.getOrNull(1) ?: state.tabs.first()
    val tc = tc()

    // Right side shows all entries when filter is off; mirrors left filter when on.
    val effectiveRightTab = if (state.compareFilterRight)
        rightTab.copy(filter = leftTab.filter)
    else
        rightTab.copy(filter = Filter())

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
                    state.tabs.forEach { t ->
                        PillBtn(
                            t.filename,
                            active = t.id == leftTab.id
                        ) { state.activateTab(t.id) }
                    }
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    BoundFilterPanel(state, leftTab)
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
                    Modifier.fillMaxWidth().background(tc.p2).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.tabs.forEach { t ->
                        PillBtn(t.filename, active = t.id == rightTab.id) {
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
                            width = state.annotationPanelWidth,
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
            fd.setFilenameFilter { _, name -> name.endsWith(".log") || name.endsWith(".txt") }
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppText("Theme", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                ThemeGallery(state)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppText("Font size", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                    AppText("${state.settings.fontSize}sp", color = tc.td, fontSize = 10.sp, fontFamily = MONO)
                }
                val fontSizes = listOf(10, 11, 12, 13, 14, 15, 16)
                SegmentedControl(
                    options = fontSizes.map { it.toString() },
                    selectedIndices = setOf(fontSizes.indexOf(state.settings.fontSize)),
                    onToggle = { idx -> state.updateSettings { it.copy(fontSize = fontSizes[idx]) } },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppText("Font family", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                SegmentedControl(
                    options = listOf("Monospace", "Proportional"),
                    selectedIndices = setOf(if (state.settings.fontMono) 0 else 1),
                    onToggle = { idx -> state.updateSettings { it.copy(fontMono = idx == 0) } },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppText(
                        "Most-used tags",
                        color = tc.td,
                        fontSize = 10.sp,
                        fontFamily = UI,
                        fontWeight = FontWeight.SemiBold
                    )
                    AppText("${state.settings.mostUsedTagLimit}", color = tc.td, fontSize = 10.sp, fontFamily = MONO)
                }
                val tagLimits = listOf(0, 3, 5, 10, 20)
                SegmentedControl(
                    options = tagLimits.map { it.toString() },
                    selectedIndices = setOf(tagLimits.indexOf(state.settings.mostUsedTagLimit)),
                    onToggle = { idx -> state.updateSettings { it.copy(mostUsedTagLimit = tagLimits[idx]) } },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppText(
                        "Filter list rows",
                        color = tc.td,
                        fontSize = 10.sp,
                        fontFamily = UI,
                        fontWeight = FontWeight.SemiBold
                    )
                    AppText("${state.settings.filterListRows}", color = tc.td, fontSize = 10.sp, fontFamily = MONO)
                }
                val rowLimits = listOf(3, 5, 8, 10, 15)
                SegmentedControl(
                    options = rowLimits.map { it.toString() },
                    selectedIndices = setOf(rowLimits.indexOf(state.settings.filterListRows)),
                    onToggle = { idx -> state.updateSettings { it.copy(filterListRows = rowLimits[idx]) } },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppText(
                        "Visible tabs",
                        color = tc.td,
                        fontSize = 10.sp,
                        fontFamily = UI,
                        fontWeight = FontWeight.SemiBold
                    )
                    AppText("${state.settings.visibleTabLimit}", color = tc.td, fontSize = 10.sp, fontFamily = MONO)
                }
                val tabLimits = listOf(4, 6, 8, 10, 12, 16)
                SegmentedControl(
                    options = tabLimits.map { it.toString() },
                    selectedIndices = setOf(tabLimits.indexOf(state.settings.visibleTabLimit)),
                    onToggle = { idx -> state.updateSettings { it.copy(visibleTabLimit = tabLimits[idx]) } },
                )
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
                    AppText(
                        state.settings.defaultSaveDir ?: "(not set)", color = tc.ts, fontSize = 11.sp, fontFamily = MONO,
                        modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis
                    )
                    AppButton("Browse", onClick = { state.pickSaveFolder() })
                    if (state.settings.defaultSaveDir != null) AppButton(
                        "Clear",
                        onClick = { state.updateSettings { it.copy(defaultSaveDir = null) } })
                }
            }
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
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText("Version", color = tc.td, fontSize = 10.sp, fontFamily = UI, fontWeight = FontWeight.SemiBold)
                AppText(BuildInfo.APP_VERSION, color = tc.ts, fontSize = 11.sp, fontFamily = MONO)
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
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CompactSetting("Auto-export", Modifier.weight(1f)) {
            SegmentedControl(
                options = listOf("On", "Off"),
                selectedIndices = setOf(if (state.settings.autoExportNotes) 0 else 1),
                onToggle = { idx -> state.updateSettings { it.copy(autoExportNotes = idx == 0) } },
            )
        }
        CompactSetting("Log blocks", Modifier.weight(1.45f)) {
            val styles = AnnotationLogBlockStyle.entries
            SegmentedControl(
                options = listOf("Indented", "{code:java}"),
                selectedIndices = setOf(styles.indexOf(state.settings.annotationLogBlockStyle)),
                onToggle = { idx -> state.updateSettings { it.copy(annotationLogBlockStyle = styles[idx]) } },
            )
        }
        CompactSetting("Number blocks", Modifier.weight(1f)) {
            SegmentedControl(
                options = listOf("On", "Off"),
                selectedIndices = setOf(if (state.settings.numberAnnotationBlocks) 0 else 1),
                onToggle = { idx -> state.updateSettings { it.copy(numberAnnotationBlocks = idx == 0) } },
            )
        }
    }
}

@Composable
private fun CompactSetting(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val tc = tc()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

@Composable
private fun CtxItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    val tc = tc()
    HoverBox(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
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
