@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.openlog.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.openlog.model.AnnBlock
import com.openlog.model.LogTab
import com.openlog.utils.buildMd
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.math.roundToInt

@Composable
fun AnnotationPanel(
    tab: LogTab,
    headerNote: String? = null,
    recentNotes: List<String> = emptyList(),
    recentNotesMenuOpen: Boolean = false,
    onToggleMd: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onToggleRecentNotes: () -> Unit,
    onOpenNote: (File) -> Unit,
    onUpdatePrefix: (String) -> Unit,
    onUpdateSuffix: (String) -> Unit,
    onUpdateBlock: (String, String) -> Unit,
    onRemoveBlock: (String) -> Unit,
    onMoveBlock: (String, Int) -> Unit,
    onAddNoteAfter: (String?) -> Unit,
    width: Float,
) {
    val tc = tc()
    val mono = monoFont()
    val ann = tab.annotations
    val hasAnnotationBlocks = ann.blocks.isNotEmpty()
    val hasRecentNotes = recentNotes.isNotEmpty()
    val headerButtonModifier = Modifier.height(28.dp)

    Column(Modifier.width(width.dp).fillMaxHeight().background(tc.p).border(BorderStroke(1.dp, tc.br))) {
        // Header row 1: title + action buttons
        Box(
            Modifier.fillMaxWidth().height(if (headerNote != null) 46.dp else 36.dp).background(tc.p2)
                .border(BorderStroke(1.dp, tc.br)).padding(horizontal = 12.dp),
        ) {
            if (headerNote != null) {
                AppText(
                    headerNote,
                    color = tc.td,
                    fontSize = 9.sp,
                    fontFamily = MONO,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterStart).widthIn(max = 88.dp),
                )
            }
            Row(
                Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            ) {
                AppButton("Preview", onClick = onToggleMd, enabled = hasAnnotationBlocks, modifier = headerButtonModifier)
                AppButton("Copy", onClick = onCopy, modifier = headerButtonModifier)
                AppButton("Save", onClick = onSave, modifier = headerButtonModifier)
                AppButton("Open Note", onClick = {
                    val fd = FileDialog(null as Frame?, "Open Note File", FileDialog.LOAD)
                    fd.setFilenameFilter { _, n -> n.endsWith(".md") || n.endsWith(".txt") }
                    fd.isVisible = true
                    fd.file?.let { onOpenNote(File(fd.directory, it)) }
                }, modifier = headerButtonModifier)
                Box {
                    AppButton(
                        "▾ ${recentNotes.size}",
                        enabled = hasRecentNotes,
                        modifier = headerButtonModifier.widthIn(min = 40.dp),
                        onClick = onToggleRecentNotes,
                    )
                    if (recentNotesMenuOpen && hasRecentNotes) {
                        RecentNotesPopup(
                            recentNotes = recentNotes,
                            onOpenNote = onOpenNote,
                            onDismiss = onToggleRecentNotes,
                            tc = tc,
                        )
                    }
                }
            }
        }
        // Inline preview popup
        if (tab.showAnnMd && hasAnnotationBlocks) {
            MdPreviewDialog(tab = tab, mono = mono, onCopy = onCopy, onDismiss = onToggleMd)
        }

        val scroll = rememberScrollState()
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 8.dp)) {
                // Prefix
                AnnSection(tc) {
                    AppText("Prefix", color = tc.td, fontSize = 10.sp, fontFamily = UI)
                    Spacer(Modifier.height(3.dp))
                    InlineField(ann.prefix, onUpdatePrefix, "Heading, context…", Modifier.fillMaxWidth(), fontSize = 12.sp)
                }

                if (ann.blocks.isEmpty()) {
                    // Add note button + empty state
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                            .border(1.dp, tc.br, CORNER_MD)
                            .clickable { onAddNoteAfter(null) }.padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) { AppText("+ Add text block", color = tc.td, fontSize = 11.sp) }
                    Column(
                        Modifier.fillMaxWidth().padding(40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppText("◆", color = tc.td.copy(.33f), fontSize = 22.sp)
                        AppText("Right-click a log line\nto annotate it", color = tc.td, fontSize = 11.sp, maxLines = 2)
                    }
                }

                ann.blocks.forEachIndexed { idx, block ->
                    val isFirst = idx == 0
                    val isLast  = idx == ann.blocks.lastIndex

                    when (block) {
                        is AnnBlock.Note -> NoteBlock(
                            block = block, tc = tc, isFirst = isFirst, isLast = isLast,
                            onUpdate = { onUpdateBlock(block.id, it) },
                            onRemove = { onRemoveBlock(block.id) },
                            onMoveUp = { onMoveBlock(block.id, -1) },
                            onMoveDown = { onMoveBlock(block.id, 1) },
                            onAddBelow = { onAddNoteAfter(block.id) },
                        )
                        is AnnBlock.LogRef -> LogRefBlock(
                            block = block, tab = tab, mono = mono, tc = tc,
                            isFirst = isFirst, isLast = isLast,
                            onUpdateCaption = { onUpdateBlock(block.id, it) },
                            onRemove = { onRemoveBlock(block.id) },
                            onMoveUp = { onMoveBlock(block.id, -1) },
                            onMoveDown = { onMoveBlock(block.id, 1) },
                            onAddBelow = { onAddNoteAfter(block.id) },
                        )
                    }
                }

                if (ann.blocks.isNotEmpty()) {
                    // Global + text block button
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                            .border(1.dp, tc.br, CORNER_MD)
                            .clickable { onAddNoteAfter(ann.blocks.last().id) }.padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) { AppText("+ Add text block", color = tc.td, fontSize = 11.sp) }

                    // Suffix
                    AnnSection(tc) {
                        AppText("Next steps", color = tc.td, fontSize = 10.sp, fontFamily = UI)
                        Spacer(Modifier.height(3.dp))
                        InlineField(ann.suffix, onUpdateSuffix, "Add follow-up notes…", Modifier.fillMaxWidth(), fontSize = 11.sp)
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun RecentNotesPopup(
    recentNotes: List<String>,
    onOpenNote: (File) -> Unit,
    onDismiss: () -> Unit,
    tc: ThemeColors,
) {
    val density = LocalDensity.current.density
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, (34 * density).roundToInt()),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val displayNotes = recentNotes.take(10)
        Box(
            Modifier.width(300.dp)
                .background(tc.p, RoundedCornerShape(7.dp))
                .border(1.dp, tc.br, RoundedCornerShape(7.dp)),
        ) {
            val popupScroll = rememberScrollState()
            Box(Modifier.heightIn(max = 260.dp)) {
                Column(Modifier.fillMaxWidth().verticalScroll(popupScroll).padding(vertical = 4.dp)) {
                    displayNotes.forEach { path ->
                        val file = File(path)
                        val exists = file.exists()
                        HoverBox(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = if (exists) ({ onOpenNote(file) }) else null,
                        ) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                                AppText(file.name, color = if (exists) tc.tx else tc.td, fontSize = 11.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis)
                                AppText(file.parent ?: path, color = tc.td, fontSize = 9.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(popupScroll),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

// ── Markdown preview dialog ────────────────────────────────────────────
@Composable
private fun MdPreviewDialog(tab: LogTab, mono: FontFamily, onCopy: () -> Unit, onDismiss: () -> Unit) {
    val tc = tc()
    var copied by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.75f).fillMaxHeight(0.8f)
                .background(tc.p, RoundedCornerShape(8.dp))
                .border(1.dp, tc.br, RoundedCornerShape(8.dp)),
        ) {
            Row(
                Modifier.fillMaxWidth().height(40.dp).background(tc.p2, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppText("Markdown Preview", color = tc.ts, fontSize = 13.sp, modifier = Modifier.weight(1f))
                PillBtn(if (copied) "Copied!" else "Copy", active = copied) {
                    onCopy()
                    copied = true
                }
                AppText("×", color = tc.td, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onDismiss))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp)) {
                AppText(buildMd(tab), color = tc.tx, fontSize = 12.sp, fontFamily = mono,
                    maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip)
            }
        }
    }
}


// ── Note block ─────────────────────────────────────────────────────────
@Composable
private fun NoteBlock(
    block: AnnBlock.Note,
    tc: ThemeColors,
    isFirst: Boolean, isLast: Boolean,
    onUpdate: (String) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit, onMoveDown: () -> Unit,
    onAddBelow: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .border(BorderStroke(2.dp, tc.ac.copy(.35f)))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        BlockControls(tc, "T", tc.ac, isFirst, isLast, onMoveUp, onMoveDown, onRemove, onAddBelow)
        Spacer(Modifier.height(5.dp))
        BasicTextField(
            value = block.text,
            onValueChange = onUpdate,
            textStyle = TextStyle(color = tc.tx, fontSize = 12.sp, fontFamily = FontFamily.Default, lineHeight = 18.sp),
            cursorBrush = SolidColor(tc.ac),
            modifier = Modifier.fillMaxWidth()
                .background(tc.bg, CORNER_SM)
                .border(1.dp, tc.br, CORNER_SM)
                .padding(8.dp).defaultMinSize(minHeight = 60.dp),
            decorationBox = { inner ->
                if (block.text.isEmpty()) AppText("Write your note here…", color = tc.td, fontSize = 12.sp)
                inner()
            },
        )
    }
}

// ── LogRef block ───────────────────────────────────────────────────────
@Composable
private fun LogRefBlock(
    block: AnnBlock.LogRef,
    tab: LogTab,
    mono: FontFamily,
    tc: ThemeColors,
    isFirst: Boolean, isLast: Boolean,
    onUpdateCaption: (String) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit, onMoveDown: () -> Unit,
    onAddBelow: () -> Unit,
) {
    val rows = block.sourceEntries ?: block.logIds.mapNotNull { tab.rmap[it] }
    val borderColor = rows.firstOrNull()?.level?.defaultColor ?: tc.ac

    Column(
        Modifier.fillMaxWidth()
            .border(BorderStroke(2.dp, borderColor))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        BlockControls(tc, "◆", borderColor, isFirst, isLast, onMoveUp, onMoveDown, onRemove, onAddBelow)
        if (block.sourceFilename != null) {
            Spacer(Modifier.height(3.dp))
            Box(
                Modifier.background(tc.ac.copy(.12f), CORNER_SM)
                    .border(1.dp, tc.ac.copy(.25f), CORNER_SM)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) { AppText("from ${block.sourceFilename}", color = tc.ac, fontSize = 9.sp, fontFamily = MONO) }
        }
        Spacer(Modifier.height(5.dp))
        BasicTextField(
            value = block.caption,
            onValueChange = onUpdateCaption,
            textStyle = TextStyle(color = tc.tx, fontSize = 12.sp, fontFamily = FontFamily.Default, lineHeight = 18.sp),
            cursorBrush = SolidColor(tc.ac),
            modifier = Modifier.fillMaxWidth()
                .background(tc.bg, CORNER_SM)
                .border(1.dp, tc.br, CORNER_SM)
                .padding(8.dp).defaultMinSize(minHeight = 52.dp),
            decorationBox = { inner ->
                if (block.caption.isEmpty()) AppText("Add a note or analysis…", color = tc.td, fontSize = 12.sp)
                inner()
            },
        )
        Spacer(Modifier.height(6.dp))

        // Referenced log lines shown BELOW the text
        Column(
            Modifier.fillMaxWidth()
                .background(tc.bg.copy(.7f), CORNER_SM)
                .border(1.dp, tc.br.copy(.6f), CORNER_SM)
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            rows.forEach { r ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText(r.ts, color = tc.td, fontSize = 9.sp, fontFamily = mono, modifier = Modifier.width(75.dp))
                    LevelBadge(r.level)
                    AppText(r.tag, color = tc.ts, fontSize = 9.sp, fontFamily = mono,
                        modifier = Modifier.width(80.dp), overflow = TextOverflow.Ellipsis)
                    AppText(r.msg, color = tc.ts, fontSize = 9.sp, fontFamily = mono,
                        modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ── Block controls (move / delete / add note) ──────────────────────────
@Composable
private fun BlockControls(
    tc: ThemeColors,
    typeLabel: String, typeColor: Color,
    isFirst: Boolean, isLast: Boolean,
    onMoveUp: () -> Unit, onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onAddBelow: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(16.dp).background(typeColor.copy(.2f), CORNER_SM)
                .border(1.dp, typeColor.copy(.4f), CORNER_SM),
            contentAlignment = Alignment.Center,
        ) { AppText(typeLabel, color = typeColor, fontSize = 9.sp) }

        Spacer(Modifier.weight(1f))

        if (!isFirst) AppText("↑", color = tc.td, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onMoveUp))
        if (!isLast)  AppText("↓", color = tc.td, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onMoveDown))
        AppText("+ note", color = tc.td, fontSize = 10.sp, modifier = Modifier.clickable(onClick = onAddBelow))
        AppText("×", color = tc.td, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onRemove))
    }
}

@Composable
private fun AnnSection(tc: ThemeColors, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(BorderStroke(1.dp, tc.br.copy(.33f))).padding(horizontal = 12.dp, vertical = 8.dp),
        content = content,
    )
}
