package com.openlog.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openlog.ai.CustomAiCommand
import com.openlog.model.*
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

// ── Add annotation dialog ─────────────────────────────────────────────
@Composable
internal fun AddAnnDialog(
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

// ── Custom AI command editor ──────────────────────────────────────────
@Composable
internal fun CustomAiCommandEditorDialog(
    state: AppState,
    target: CustomAiCommand,
    onDismiss: () -> Unit,
) {
    val tc = tc()
    val isNew = target.name.isBlank()
    var name by remember(target) { mutableStateOf(target.name) }
    var template by remember(target) { mutableStateOf(target.promptTemplate) }
    var error by remember(target) { mutableStateOf<String?>(null) }

    Column(
        Modifier.width(440.dp).background(tc.p, RoundedCornerShape(8.dp))
            .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppText(
            if (isNew) "Add custom AI command" else "Edit custom AI command",
            color = tc.tx,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText("Name (invoked as /name)", color = tc.td, fontSize = 10.sp)
            InlineField(name, { name = it }, "timeline", Modifier.fillMaxWidth(), fontSize = 12.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText("Prompt template", color = tc.td, fontSize = 10.sp)
            val templateScroll = rememberScrollState()
            // Keep the dialog compact regardless of template length. verticalScroll changes its
            // child's height constraints, so placing it outside the old heightIn modifier allowed
            // a large template to grow the entire dialog before its scrollbar could take effect.
            Box(Modifier.fillMaxWidth().height(260.dp)) {
                BasicTextField(
                    value = template,
                    onValueChange = { template = it },
                    textStyle = TextStyle(color = tc.tx, fontSize = 12.sp, fontFamily = FontFamily.Default, lineHeight = 18.sp),
                    cursorBrush = SolidColor(tc.ac),
                    modifier = Modifier.fillMaxSize()
                        .background(tc.bg, CORNER_MD)
                        .border(1.dp, tc.ac.copy(.5f), CORNER_MD)
                        .padding(10.dp)
                        .verticalScroll(templateScroll),
                    decorationBox = { inner ->
                        if (template.isEmpty()) {
                            AppText(
                                "What should the assistant do when this command is invoked?",
                                color = tc.td,
                                fontSize = 12.sp,
                            )
                        }
                        inner()
                    },
                )
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(templateScroll),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
                    style = appScrollbarStyle(tc),
                )
            }
        }
        error?.let { AppText(it, color = DANGER_RED, fontSize = 10.sp) }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DialogActionButton("Save", active = true) {
                val saveError = state.saveCustomAiCommand(
                    name.trim(),
                    template,
                    previousName = target.name.takeIf { it.isNotBlank() },
                )
                if (saveError != null) error = saveError else onDismiss()
            }
            DialogActionButton("Cancel", active = false, onClick = onDismiss)
        }
    }
}

// ── Per-folder project info editor ────────────────────────────────────
@Composable
internal fun SourceFolderInfoDialog(
    state: AppState,
    path: String,
    onDismiss: () -> Unit,
) {
    val tc = tc()
    val existing = state.settings.sourceFolderInfo[path] ?: SourceFolderInfo()
    var description by remember(path) { mutableStateOf(existing.description) }
    var readmePath by remember(path) { mutableStateOf(existing.readmePath.orEmpty()) }

    Column(
        Modifier.width(440.dp).background(tc.p, RoundedCornerShape(8.dp))
            .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppText("Project info", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        AppText(truncatePathForDisplay(path), color = tc.td, fontSize = 10.sp, fontFamily = MONO, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText("Description", color = tc.td, fontSize = 10.sp)
            BasicTextField(
                value = description,
                onValueChange = { description = it },
                textStyle = TextStyle(color = tc.tx, fontSize = 12.sp, fontFamily = FontFamily.Default, lineHeight = 18.sp),
                cursorBrush = SolidColor(tc.ac),
                modifier = Modifier.fillMaxWidth()
                    .background(tc.bg, CORNER_MD)
                    .border(1.dp, tc.ac.copy(.5f), CORNER_MD)
                    .padding(10.dp).heightIn(min = 80.dp, max = 200.dp),
                decorationBox = { inner ->
                    if (description.isEmpty()) {
                        AppText("What is this project / what should the AI know about it?", color = tc.td, fontSize = 12.sp)
                    }
                    inner()
                },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText("README path (optional)", color = tc.td, fontSize = 10.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InlineField(readmePath, { readmePath = it }, "/path/to/README.md", Modifier.weight(1f), fontSize = 12.sp)
                AppButton(
                    "Browse",
                    onClick = { state.pickReadmeFile()?.let { readmePath = it } },
                    variant = ButtonVariant.Secondary,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DialogActionButton("Save", active = true) {
                state.updateSourceFolderInfo(path, SourceFolderInfo(description, readmePath.trim().ifBlank { null }))
                onDismiss()
            }
            DialogActionButton("Cancel", active = false, onClick = onDismiss)
        }
    }
}

@Composable
internal fun SplitPromptDialog(
    state: AppState,
    pending: PendingSplitPrompt,
    onDismiss: () -> Unit,
) {
    val tc = tc()
    val firstSource = pending.sources.first()
    val isSingleSource = pending.sources.size == 1
    var destination by remember(pending) {
        mutableStateOf(state.defaultSplitDestination(firstSource).absolutePath)
    }
    var selected by remember(pending) { mutableStateOf(emptySet<String>()) }
    var postfixes by remember(pending) {
        mutableStateOf(pending.sources.associate { it.id to "part" })
    }
    var counts by remember(pending) {
        mutableStateOf(pending.sources.associate { it.id to state.defaultSplitPartCount(it) })
    }

    fun chooseDestination() {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dlg = FileDialog(null as Frame?, "Choose Split Destination", FileDialog.LOAD).apply {
                directory = destination
                isVisible = true
            }
            val dir = dlg.directory ?: return
            val file = dlg.file ?: return
            destination = File(dir, file).absolutePath
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }

    fun splitPartOptions(value: Int): List<Int> =
        (listOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 24, 32) + value.coerceAtLeast(1)).distinct().sorted()

    fun confirm(splitIds: Set<String>) {
        state.confirmSplitPrompt(
            modes = pending.sources.associate { source ->
                source.id to if (source.id in splitIds) SplitMode.SPLIT else SplitMode.OPEN_AS_IS
            },
            destinationDir = File(destination),
            postfix = "part",
            partCounts = counts,
            postfixes = postfixes,
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = false)) {
        Column(
            Modifier.width(if (isSingleSource) 520.dp else 620.dp).background(tc.p, RoundedCornerShape(8.dp))
                .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppText(
                if (isSingleSource) "Split large log file" else "Split large log files",
                color = tc.tx,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            AppText(
                if (isSingleSource) {
                    "This file is large. Split it into smaller files or open the original as-is."
                } else {
                    "Choose which files should be split. Unchecked files will open as-is."
                },
                color = tc.td,
                fontSize = 11.sp,
                maxLines = 2,
            )
            Column(
                Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pending.sources.forEach { source ->
                    Column(
                        Modifier.fillMaxWidth().background(tc.bg, CORNER_MD)
                            .border(1.dp, tc.br, CORNER_MD).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (isSingleSource) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AppText(
                                    source.displayName,
                                    color = tc.tx,
                                    fontSize = 12.sp,
                                    fontFamily = MONO,
                                    modifier = Modifier.weight(1f),
                                )
                                AppText(formatByteSize(source.sizeBytes), color = tc.td, fontSize = 11.sp, fontFamily = MONO)
                            }
                        } else {
                            CheckRow(
                                checked = source.id in selected,
                                onToggle = {
                                    selected = if (source.id in selected) selected - source.id else selected + source.id
                                },
                            ) {
                                AppText(
                                    source.displayName,
                                    color = tc.tx,
                                    fontSize = 12.sp,
                                    fontFamily = MONO,
                                    modifier = Modifier.weight(1f),
                                )
                                AppText(formatByteSize(source.sizeBytes), color = tc.td, fontSize = 11.sp, fontFamily = MONO)
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AppText("Parts", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            ListStepper(
                                options = splitPartOptions(counts[source.id] ?: 1),
                                value = counts[source.id] ?: 1,
                                onChange = { value -> counts = counts + (source.id to value) },
                            )
                            AppText("Postfix", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            SplitDialogTextField(
                                value = postfixes[source.id].orEmpty(),
                                onValueChange = { value -> postfixes = postfixes + (source.id to value) },
                                modifier = Modifier.width(if (isSingleSource) 180.dp else 130.dp),
                            )
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppText("Destination", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SplitDialogTextField(
                        value = destination,
                        onValueChange = { destination = it },
                        modifier = Modifier.weight(1f),
                    )
                    AppButton("Browse", onClick = ::chooseDestination, variant = ButtonVariant.Secondary)
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSingleSource) {
                    DialogActionButton("Split", active = true) {
                        confirm(setOf(firstSource.id))
                    }
                    DialogActionButton("Do Not Split", active = false) {
                        confirm(emptySet())
                    }
                } else {
                    DialogActionButton("Split All", active = true) {
                        confirm(pending.sources.map { it.id }.toSet())
                    }
                    DialogActionButton(
                        "Split Selected",
                        active = selected.isNotEmpty(),
                        enabled = selected.isNotEmpty(),
                    ) {
                        confirm(selected)
                    }
                }
                DialogActionButton("Cancel", active = false, onClick = onDismiss)
            }
        }
    }
}

@Composable
internal fun SplitDialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = tc.tx, fontSize = 11.sp, fontFamily = MONO),
        cursorBrush = SolidColor(tc.ac),
        modifier = modifier
            .height(32.dp)
            .background(tc.bg, CORNER_MD)
            .border(1.dp, tc.br, CORNER_MD)
            .padding(horizontal = 8.dp, vertical = 7.dp),
    )
}

// How long isLoading must stay continuously true before the watchdog offers to intervene. Big
// real files legitimately take a few seconds (see docs/perf-large-files.md); 30s is comfortably
// past that so this doesn't fire on normal big-file loads, only on something that's actually
// stuck.
internal const val STUCK_LOADING_PROMPT_DELAY_MS = 30_000L

@Composable
internal fun StuckLoadingDialog(
    status: String?,
    onCancelLoading: () -> Unit,
    onCloseAllTabs: () -> Unit,
    onClearCache: () -> Unit,
    onKeepWaiting: () -> Unit,
) {
    val tc = tc()
    Dialog(onDismissRequest = onKeepWaiting) {
        Column(
            Modifier.width(360.dp).background(tc.p, RoundedCornerShape(8.dp))
                .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(20.dp),
        ) {
            AppText("Still loading…", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            AppText(
                "This has been loading for a while" + (status?.let { " ($it)" } ?: "") +
                    ". If it looks stuck, you can:",
                color = tc.td,
                fontSize = 11.sp,
                maxLines = 4,
            )
            Spacer(Modifier.height(14.dp))
            AppButton("Cancel loading", onClick = onCancelLoading, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            AppButton("Close all tabs", onClick = onCloseAllTabs, isDanger = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            AppButton("Clear cache…", onClick = onClearCache, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            AppButton("Keep waiting", onClick = onKeepWaiting, variant = ButtonVariant.Ghost, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun DialogActionButton(
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
internal fun KeyboardShortcutsDialog(onDismiss: () -> Unit) {
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
