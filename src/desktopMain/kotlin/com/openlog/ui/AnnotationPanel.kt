@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.openlog.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.openlog.model.AnnBlock
import com.openlog.model.AppSettings
import com.openlog.model.LogTab
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.math.roundToInt
import java.awt.Cursor as AwtCursor

@Composable
fun AnnotationPanel(
    tab: LogTab,
    settings: AppSettings,
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
    onNavigateLogRef: (AnnBlock.LogRef) -> Unit,
    width: Float,
    focusRequester: FocusRequester? = null,
    onPanelFocusChanged: (Boolean) -> Unit = {},
    keyboardFocusVisible: Boolean = false,
) {
    val tc = tc()
    val mono = monoFont()
    val ann = tab.annotations
    val hasAnnotationBlocks = ann.blocks.isNotEmpty()
    val hasRecentNotes = recentNotes.isNotEmpty()
    val headerButtonModifier = Modifier.height(28.dp)
    var panelFocused by remember { mutableStateOf(false) }
    var prefixFocused by remember { mutableStateOf(false) }
    var suffixFocused by remember { mutableStateOf(false) }
    var blockFieldFocused by remember { mutableStateOf(false) }
    var navIndex by remember(tab.id) { mutableStateOf(0) }
    val prefixFr = remember { FocusRequester() }
    val suffixFr = remember { FocusRequester() }
    val blockFieldRequesters = remember(ann.blocks.map { it.id }) {
        ann.blocks.associate { it.id to FocusRequester() }
    }
    val noteTargets = remember(ann.blocks, hasRecentNotes, hasAnnotationBlocks) {
        annotationKeyboardTargets(
            blockIds = ann.blocks.map { it.id },
            hasRecentNotes = hasRecentNotes,
            hasBlocks = hasAnnotationBlocks,
        )
    }

    fun openNotePicker() {
        val fd = FileDialog(null as Frame?, "Open Note File", FileDialog.LOAD)
        fd.setFilenameFilter { _, n -> n.endsWith(".md") || n.endsWith(".txt") }
        fd.isVisible = true
        fd.file?.let { onOpenNote(File(fd.directory, it)) }
    }

    fun moveNoteFocus(delta: Int) {
        navIndex = rovingMove(noteTargets.map { it.asRovingItem() }, navIndex, delta)
    }

    fun focusedBlockId(): String? = noteTargets.getOrNull(navIndex)
        ?.takeIf { it.kind == KeyboardTargetKind.NoteBlock }
        ?.id
        ?.removePrefix("block:")

    fun activateNoteTarget() {
        val target = noteTargets.getOrNull(navIndex) ?: return
        when (target.kind) {
            KeyboardTargetKind.NotePreview -> if (hasAnnotationBlocks) onToggleMd()
            KeyboardTargetKind.NoteCopy -> onCopy()
            KeyboardTargetKind.NoteSave -> onSave()
            KeyboardTargetKind.NoteOpen -> openNotePicker()
            KeyboardTargetKind.NoteRecentNotes -> if (hasRecentNotes) onToggleRecentNotes()
            KeyboardTargetKind.NotePrefix -> runCatching { prefixFr.requestFocus() }
            KeyboardTargetKind.NoteSuffix -> runCatching { suffixFr.requestFocus() }
            KeyboardTargetKind.NoteAddTextBlock -> {
                val after = if (target.id == "add-at-start") null else ann.blocks.lastOrNull()?.id
                onAddNoteAfter(after)
            }
            KeyboardTargetKind.NoteBlock -> {
                val blockId = target.id.removePrefix("block:")
                val block = ann.blocks.firstOrNull { it.id == blockId }
                if (block is AnnBlock.LogRef) onNavigateLogRef(block)
                else runCatching { blockFieldRequesters[blockId]?.requestFocus() }
            }
            else -> {}
        }
    }

    fun handleBlockShortcut(ev: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val blockId = focusedBlockId() ?: return false
        val idx = ann.blocks.indexOfFirst { it.id == blockId }
        if (idx < 0) return false
        return when {
            ev.isAltPressed && ev.key == Key.DirectionUp -> { onMoveBlock(blockId, -1); true }
            ev.isAltPressed && ev.key == Key.DirectionDown -> { onMoveBlock(blockId, +1); true }
            ev.isCtrlPressed && ev.key == Key.Enter -> { onAddNoteAfter(blockId); true }
            ev.isMetaPressed && ev.key == Key.Enter -> { onAddNoteAfter(blockId); true }
            ev.key == Key.Delete || ev.key == Key.Backspace -> { onRemoveBlock(blockId); true }
            else -> false
        }
    }

    Column(
        Modifier.width(width.dp).fillMaxHeight().background(tc.p)
            .border(BorderStroke(1.dp, if (panelFocused && keyboardFocusVisible) tc.ac else tc.br))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusGroup()
            .focusable()
            .onFocusChanged { panelFocused = it.hasFocus; onPanelFocusChanged(it.hasFocus) }
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val actionPressed = if (isMacOs) ev.isMetaPressed else ev.isCtrlPressed
                when {
                    actionPressed && ev.key == Key.S -> { onSave(); true }
                    actionPressed && ev.key == Key.C -> { onCopy(); true }
                    actionPressed && ev.key == Key.O -> { openNotePicker(); true }
                    prefixFocused || suffixFocused || blockFieldFocused -> {
                        if (ev.key == Key.Escape) {
                            runCatching { focusRequester?.requestFocus() }
                            true
                        } else {
                            false
                        }
                    }
                    handleBlockShortcut(ev) -> true
                    ev.key == Key.DirectionUp -> { moveNoteFocus(-1); true }
                    ev.key == Key.DirectionDown -> { moveNoteFocus(+1); true }
                    ev.key == Key.DirectionLeft -> { moveNoteFocus(-1); true }
                    ev.key == Key.DirectionRight -> { moveNoteFocus(+1); true }
                    ev.key == Key.Enter || ev.key == Key.NumPadEnter || ev.key == Key.Spacebar -> {
                        activateNoteTarget(); true
                    }
                    ev.key == Key.Escape -> {
                        if (recentNotesMenuOpen) onToggleRecentNotes()
                        true
                    }
                    else -> false
                }
            },
    ) {
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
                AppButton("Open Note", onClick = { openNotePicker() }, modifier = headerButtonModifier)
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
            MdPreviewDialog(tab = tab, settings = settings, mono = mono, onCopy = onCopy, onDismiss = onToggleMd)
        }

        val scroll = rememberScrollState()
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 8.dp)) {
                // Prefix
                AnnSection(tc) {
                    AppText("Prefix", color = tc.td, fontSize = 10.sp, fontFamily = UI)
                    Spacer(Modifier.height(3.dp))
                    InlineField(
                        ann.prefix,
                        onUpdatePrefix,
                        "Heading, context…",
                        Modifier.fillMaxWidth()
                            .focusRequester(prefixFr)
                            .onFocusChanged { prefixFocused = it.isFocused },
                        fontSize = 12.sp,
                    )
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
                            focused = noteTargets.getOrNull(navIndex)?.id == "block:${block.id}",
                            fieldFocusRequester = blockFieldRequesters[block.id],
                            onFieldFocusChanged = { blockFieldFocused = it },
                            onUpdate = { onUpdateBlock(block.id, it) },
                            onRemove = { onRemoveBlock(block.id) },
                            onMoveUp = { onMoveBlock(block.id, -1) },
                            onMoveDown = { onMoveBlock(block.id, 1) },
                            onAddBelow = { onAddNoteAfter(block.id) },
                        )
                        is AnnBlock.LogRef -> LogRefBlock(
                            block = block, tab = tab, mono = mono, tc = tc,
                            isFirst = isFirst, isLast = isLast,
                            focused = noteTargets.getOrNull(navIndex)?.id == "block:${block.id}",
                            fieldFocusRequester = blockFieldRequesters[block.id],
                            onFieldFocusChanged = { blockFieldFocused = it },
                            onUpdateCaption = { onUpdateBlock(block.id, it) },
                            onRemove = { onRemoveBlock(block.id) },
                            onMoveUp = { onMoveBlock(block.id, -1) },
                            onMoveDown = { onMoveBlock(block.id, 1) },
                            onAddBelow = { onAddNoteAfter(block.id) },
                            onNavigate = { onNavigateLogRef(block) },
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
                        InlineField(
                            ann.suffix,
                            onUpdateSuffix,
                            "Add follow-up notes…",
                            Modifier.fillMaxWidth()
                                .focusRequester(suffixFr)
                                .onFocusChanged { suffixFocused = it.isFocused },
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style = appScrollbarStyle(tc),
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
        val popupFr = remember { FocusRequester() }
        var selectedIdx by remember(displayNotes) { mutableStateOf(displayNotes.indexOfFirst { File(it).exists() }.coerceAtLeast(0)) }
        LaunchedEffect(Unit) { runCatching { popupFr.requestFocus() } }
        Box(
            Modifier.width(300.dp)
                .background(tc.p, RoundedCornerShape(7.dp))
                .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                .focusRequester(popupFr)
                .focusable()
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.DirectionDown -> {
                            selectedIdx = rovingMove(
                                displayNotes.map { RovingItem(it, File(it).exists()) },
                                selectedIdx,
                                +1,
                                wrap = true,
                            )
                            true
                        }
                        Key.DirectionUp -> {
                            selectedIdx = rovingMove(
                                displayNotes.map { RovingItem(it, File(it).exists()) },
                                selectedIdx,
                                -1,
                                wrap = true,
                            )
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            displayNotes.getOrNull(selectedIdx)
                                ?.let(::File)
                                ?.takeIf { it.exists() }
                                ?.let(onOpenNote)
                            true
                        }
                        Key.Escape -> { onDismiss(); true }
                        else -> false
                    }
                },
        ) {
            val popupScroll = rememberScrollState()
            Box(Modifier.heightIn(max = 260.dp)) {
                Column(Modifier.fillMaxWidth().verticalScroll(popupScroll).padding(vertical = 4.dp)) {
                    displayNotes.forEachIndexed { idx, path ->
                        val file = File(path)
                        val exists = file.exists()
                        TooltipArea(
                            tooltip = {
                                Box(
                                    Modifier
                                        .widthIn(max = 560.dp)
                                        .background(tc.p2, RoundedCornerShape(4.dp))
                                        .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    AppText(path, color = tc.tx, fontSize = 11.sp, fontFamily = MONO, maxLines = 3)
                                }
                            },
                        ) {
                            HoverBox(
                                modifier = Modifier.fillMaxWidth(),
                                forceHover = idx == selectedIdx,
                                onClick = if (exists) ({ onOpenNote(file) }) else null,
                            ) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                                    AppText(
                                        file.name,
                                        color = if (exists) tc.tx else tc.td,
                                        fontSize = 11.sp,
                                        fontFamily = MONO,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    AppText(file.parent ?: path, color = tc.td, fontSize = 9.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(popupScroll),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    style = appScrollbarStyle(tc),
                )
            }
        }
    }
}

// ── Markdown preview dialog ────────────────────────────────────────────
@Composable
private fun MdPreviewDialog(tab: LogTab, settings: AppSettings, mono: FontFamily, onCopy: () -> Unit, onDismiss: () -> Unit) {
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
                AppButton(
                    if (copied) "Copied!" else "Copy",
                    onClick = {
                        onCopy()
                        copied = true
                    },
                    modifier = Modifier.height(28.dp),
                )
                CloseButton(onClick = onDismiss)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp)) {
                    RenderedMarkdownPreview(tab, settings, mono, tc)
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scroll),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp).width(6.dp),
                    style = appScrollbarStyle(tc),
                )
            }
        }
    }
}

@Composable
private fun RenderedMarkdownPreview(tab: LogTab, settings: AppSettings, mono: FontFamily, tc: ThemeColors) {
    val label = settings.annotationPrefixLabel.trim().ifBlank { "From" }
    var blockNumber = 1
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (tab.annotations.prefix.isNotBlank()) {
            AppText(tab.annotations.prefix, color = tc.tx, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        tab.annotations.blocks.forEach { block ->
            when (block) {
                is AnnBlock.Note -> if (block.text.isNotBlank()) {
                    AppText(
                        (if (settings.numberAnnotationBlocks) "${blockNumber++}. " else "") + block.text,
                        color = tc.tx,
                        fontSize = 13.sp,
                        maxLines = Int.MAX_VALUE,
                        overflow = TextOverflow.Clip,
                    )
                }

                is AnnBlock.LogRef -> {
                    val rows = block.sourceEntries ?: block.logIds.mapNotNull { tab.rmap[it] }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (block.caption.isNotBlank() || settings.numberAnnotationBlocks) {
                            AppText(
                                (if (settings.numberAnnotationBlocks) "${blockNumber++}. " else "") + block.caption,
                                color = tc.tx,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip,
                            )
                        }
                        if (block.sourceFilename != null) {
                            AppText("$label ${block.sourceFilename}", color = tc.td, fontSize = 11.sp, fontFamily = mono)
                        }
                        Column(
                            Modifier.fillMaxWidth()
                                .background(tc.bg, CORNER_SM)
                                .border(1.dp, tc.br, CORNER_SM)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            rows.forEach { row ->
                                AppText(
                                    "${row.ts}  ${row.level.key}/${row.tag}  ${row.msg}",
                                    color = tc.ts,
                                    fontSize = 12.sp,
                                    fontFamily = mono,
                                    maxLines = Int.MAX_VALUE,
                                    overflow = TextOverflow.Clip,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (tab.annotations.suffix.isNotBlank()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
            AppText(tab.annotations.suffix, color = tc.tx, fontSize = 13.sp, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip)
        }
    }
}

// ── Note block ─────────────────────────────────────────────────────────
@Composable
private fun NoteBlock(
    block: AnnBlock.Note,
    tc: ThemeColors,
    isFirst: Boolean, isLast: Boolean,
    focused: Boolean,
    fieldFocusRequester: FocusRequester?,
    onFieldFocusChanged: (Boolean) -> Unit,
    onUpdate: (String) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit, onMoveDown: () -> Unit,
    onAddBelow: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .border(BorderStroke(2.dp, if (focused) tc.ac else tc.ac.copy(.35f)))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        BlockControls(tc, "text", tc.ac, isFirst, isLast, onMoveUp, onMoveDown, onRemove, onAddBelow)
        Spacer(Modifier.height(5.dp))
        BasicTextField(
            value = block.text,
            onValueChange = onUpdate,
            textStyle = TextStyle(color = tc.tx, fontSize = 12.sp, fontFamily = FontFamily.Default, lineHeight = 18.sp),
            cursorBrush = SolidColor(tc.ac),
            modifier = Modifier.fillMaxWidth()
                .background(tc.bg, CORNER_SM)
                .border(1.dp, tc.br, CORNER_SM)
                .then(if (fieldFocusRequester != null) Modifier.focusRequester(fieldFocusRequester) else Modifier)
                .onFocusChanged { onFieldFocusChanged(it.isFocused) }
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
    focused: Boolean,
    fieldFocusRequester: FocusRequester?,
    onFieldFocusChanged: (Boolean) -> Unit,
    onUpdateCaption: (String) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit, onMoveDown: () -> Unit,
    onAddBelow: () -> Unit,
    onNavigate: () -> Unit,
) {
    val rows = block.sourceEntries ?: block.logIds.mapNotNull { tab.rmap[it] }
    val borderColor = rows.firstOrNull()?.level?.defaultColor ?: tc.ac

    Column(
        Modifier.fillMaxWidth()
            .border(BorderStroke(2.dp, if (focused) tc.ac else borderColor))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        BlockControls(tc, "log", borderColor, isFirst, isLast, onMoveUp, onMoveDown, onRemove, onAddBelow, onNavigate)
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
                .then(if (fieldFocusRequester != null) Modifier.focusRequester(fieldFocusRequester) else Modifier)
                .onFocusChanged { onFieldFocusChanged(it.isFocused) }
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
    onNavigate: (() -> Unit)? = null,
) {
    val badgeShape = CORNER_SM
    val isNavigationBadge = onNavigate != null
    val badgeModifier = Modifier.height(18.dp)
        .defaultMinSize(minWidth = if (isNavigationBadge) 48.dp else 34.dp)
        .background(typeColor.copy(if (onNavigate != null) .24f else .14f), badgeShape)
        .border(1.dp, typeColor.copy(if (onNavigate != null) .9f else .35f), badgeShape)
        .clip(badgeShape)
        .then(
            if (onNavigate != null) {
                Modifier
                    .pointerHoverIcon(PointerIcon(AwtCursor.getPredefinedCursor(AwtCursor.HAND_CURSOR)))
                    .clickable(onClick = onNavigate)
            } else {
                Modifier
            },
        )
        .padding(horizontal = 6.dp)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            badgeModifier,
            contentAlignment = Alignment.Center,
        ) {
            if (isNavigationBadge) {
                androidx.compose.material3.Text(
                    "$typeLabel ↗",
                    color = typeColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline,
                    maxLines = 1,
                )
            } else {
                AppText(
                    typeLabel,
                    color = typeColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

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
