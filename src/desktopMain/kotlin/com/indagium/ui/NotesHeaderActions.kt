package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.indagium.model.AnnotationCopyFormat
import com.indagium.model.LogTab
import kotlinx.coroutines.delay
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.math.roundToInt

// The Notes actions (Preview, Copy ▾, Save, New, Locate log…, Open ▾) and the Copy feedback they share. In the Notes TILE they are part of the
// tile's one header row (NotesTile in LogViewTiling.kt, laid out by NotesHeaderLayout.kt), as the log toolbar is the Log tile's header; wherever the
// panel is used without a tile it draws them in a bar of its own (AnnotationPanel.kt). Either way they are the same buttons, and every click or
// dismissed popup hands the keyboard back to the panel (a clickable takes focus, and the panel's shortcuts need it).

/** The Notes actions' inputs; the values and callbacks are those the panel has always been given (see BoundAnnotationPanel). */
@Suppress("LongParameterList") // a flat bundle of the values and callbacks the panel always took one by one
internal class NotesActionModel(
    val tab: LogTab,
    val defaultFormat: AnnotationCopyFormat,
    val feedback: CopyFeedbackState,
    val locked: Boolean,
    val recentNotes: List<String>,
    val recentMenuOpen: Boolean,
    val activeNotePath: String?,
    val onToggleMd: () -> Unit,
    val onSave: () -> Unit,
    val onNewAnalysis: () -> Unit,
    val onToggleRecentNotes: () -> Unit,
    val onOpenNote: (File) -> Unit,
    val onLocateLog: (File) -> Unit,
    val onReclaimFocus: () -> Unit,
) {
    val hasBlocks: Boolean get() = tab.annotations.blocks.isNotEmpty()
    val hasRecent: Boolean get() = recentNotes.isNotEmpty()
    val hasLocate: Boolean get() = tab.logData.isEmpty()

    fun tap(action: () -> Unit) {
        action()
        onReclaimFocus()
    }

    // An edit is ignored while the notes are locked (a capture is running, say), like every other notes edit.
    fun mutate(action: () -> Unit) {
        if (notesMutationAllowed(locked)) action()
    }

    fun openNote() = pickNoteFile(onOpenNote)

    fun locateLog() = pickLogFileToLocate(onLocateLog)
}

/** The "Open Note File" picker; hands the pick to [onPick]. */
internal fun pickNoteFile(onPick: (File) -> Unit) {
    val fd = FileDialog(null as Frame?, "Open Note File", FileDialog.LOAD)
    fd.setFilenameFilter { _, n -> n.endsWith(".md") || n.endsWith(".txt") || n.endsWith(".ann") }
    fd.isVisible = true
    fd.file?.let { onPick(File(fd.directory, it)) }
}

// No extension filter, unlike the note picker: this picks a LOG file, and platform pickers don't reliably invoke setFilenameFilter (see TabBar's own
// "Open Log File" picker's comment); AppState.locateLogForTab validates the pick itself.
internal fun pickLogFileToLocate(onPick: (File) -> Unit) {
    val fd = FileDialog(null as Frame?, "Locate Log File", FileDialog.LOAD)
    fd.isVisible = true
    fd.file?.let { onPick(File(fd.directory, it)) }
}

// Open+▾ split-button shapes: the same joined-pair idea as TabBar's log-file Open/▾, at the 4 dp radius every other AppButton here has.
private val OPEN_JOINED_SHAPE = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)
private val RECENT_DROPDOWN_SHAPE = RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)

private val LABEL_PADDING = 10.dp
private val COMPACT_PADDING = 6.dp

/** Test tag of an action's button on the row: `notes-button-<id>` (the Copy and Open split buttons carry it on their main half). */
internal fun notesButtonTag(action: NotesAction): String = "notes-button-${action.id}"

// One plain action button in [look]: its label with the log toolbar's padding or less of it, or an icon with a tooltip (the log toolbar's narrow form).
@Composable
private fun NotesButton(
    action: NotesAction,
    label: String,
    icon: ImageVector,
    look: NotesButtonLook,
    enabled: Boolean,
    shape: Shape = CORNER_MD,
    onClick: () -> Unit,
) {
    val tag = Modifier.testTag(notesButtonTag(action))
    if (look == NotesButtonLook.ICONS) {
        AppButton(
            "",
            onClick = onClick,
            enabled = enabled,
            leadingIcon = icon,
            horizontalPadding = COMPACT_PADDING,
            shape = shape,
            tooltip = label,
            modifier = tag.semantics { contentDescription = label },
        )
    } else {
        AppButton(
            label,
            onClick = onClick,
            enabled = enabled,
            horizontalPadding = if (look == NotesButtonLook.COMPACT) COMPACT_PADDING else LABEL_PADDING,
            shape = shape,
            modifier = tag,
        )
    }
}

/**
 * The buttons of [layout] (the [NotesHeaderLayout.folded] ones are left out and live in the "⋯" menu instead), emitted straight into the caller's layout
 * (a header row, or the panel's own wrapping bar). Disabled states are as they always were: Preview needs notes, Save / New / Locate / Open need the
 * notes to be unlocked.
 */
@Composable
internal fun NotesActionButtons(model: NotesActionModel, layout: NotesHeaderLayout = NotesHeaderLayout.Full) {
    val tc = tc()
    val look = layout.look

    fun shown(action: NotesAction) = action !in layout.folded
    if (shown(NotesAction.PREVIEW)) {
        NotesButton(NotesAction.PREVIEW, "Preview", Icons.Outlined.Visibility, look, model.hasBlocks) { model.tap(model.onToggleMd) }
    }
    if (shown(NotesAction.COPY)) {
        CopyFormatSplitButton(
            defaultFormat = model.defaultFormat,
            feedback = model.feedback,
            onReclaimFocus = model.onReclaimFocus,
            look = look,
            modifier = Modifier.testTag(notesButtonTag(NotesAction.COPY)),
        )
    }
    if (shown(NotesAction.SAVE)) {
        NotesButton(NotesAction.SAVE, "Save", Icons.Outlined.Save, look, !model.locked) { model.tap { model.mutate(model.onSave) } }
    }
    if (shown(NotesAction.NEW)) {
        NotesButton(NotesAction.NEW, "New", Icons.AutoMirrored.Outlined.NoteAdd, look, !model.locked) { model.tap { model.mutate(model.onNewAnalysis) } }
    }
    // Only when this tab has no log at all (opened via Case Library's "Open notes only," a blank new tab, or its own log going missing): the guided
    // reconnect path for the "note opened without its log" hazard. See AppState.locateLogForTab.
    if (model.hasLocate && shown(NotesAction.LOCATE)) {
        NotesButton(NotesAction.LOCATE, "Locate log…", Icons.Outlined.FindInPage, look, !model.locked) { model.tap { model.mutate(model::locateLog) } }
    }
    // Open+▾ is one unit (zero spacing between the halves), so a wrap point never splits the pair.
    if (shown(NotesAction.OPEN)) {
        Row {
            NotesButton(
                NotesAction.OPEN, "Open", Icons.Outlined.FolderOpen, look, !model.locked,
                shape = if (model.hasRecent) OPEN_JOINED_SHAPE else CORNER_MD,
            ) { model.tap { model.mutate(model::openNote) } }
            // Hidden (not disabled) when there's no history: a dropdown arrow with nothing behind it is dead chrome.
            if (model.hasRecent) {
                Box {
                    AppButton(
                        "▾",
                        modifier = Modifier.width(18.dp),
                        horizontalPadding = 0.dp,
                        shape = RECENT_DROPDOWN_SHAPE,
                        onClick = { model.tap(model.onToggleRecentNotes) },
                    )
                    if (model.recentMenuOpen) RecentNotes(model, tc)
                }
            }
        }
    }
    if (layout.overflow) NotesActionsOverflow(model, layout.folded)
}

@Composable
private fun RecentNotes(model: NotesActionModel, tc: ThemeColors) {
    RecentNotesPopup(
        recentNotes = model.recentNotes,
        activeNotePath = model.activeNotePath,
        onOpenNote = { file -> model.tap { model.mutate { model.onOpenNote(file) } } },
        onDismiss = { model.tap(model.onToggleRecentNotes) },
        tc = tc,
    )
}

/** Test tag of the ▾ half of the Copy split button (its main half carries the caller's tag, `notes-button-copy` on the Notes header). */
internal const val NOTES_COPY_FORMATS_TAG = "notes-copy-formats"

/** Test tag of the "⋯" button that holds the folded Notes actions in a narrow header. */
internal const val NOTES_ACTIONS_OVERFLOW_TAG = "notes-actions-overflow"

/** Test tag of an entry of that menu: `notes-action-<id>`. */
internal fun notesActionTag(id: String): String = "notes-action-$id"

// The "⋯" button and its menu: the [folded] actions (each copy format for Copy, the recent notes for Open), in the order of the buttons.
@Composable
private fun NotesActionsOverflow(model: NotesActionModel, folded: List<NotesAction>) {
    val tc = tc()
    val density = LocalDensity.current.density
    var open by remember { mutableStateOf(false) }
    Box {
        AppButton(
            "",
            onClick = { open = !open; model.onReclaimFocus() },
            variant = if (open) ButtonVariant.Primary else ButtonVariant.Secondary,
            leadingIcon = Icons.Outlined.MoreHoriz,
            horizontalPadding = COMPACT_PADDING,
            tooltip = "Notes actions",
            modifier = Modifier.testTag(NOTES_ACTIONS_OVERFLOW_TAG).semantics { contentDescription = "Notes actions" },
        )
        if (open) {
            val dismiss = { open = false; model.onReclaimFocus() }
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, (TILE_TOOLBAR_HEIGHT_DP * density).roundToInt()),
                onDismissRequest = dismiss,
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.width(OVERFLOW_MENU_WIDTH_DP.dp)
                        .background(tc.p, RoundedCornerShape(7.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                        .padding(vertical = 4.dp),
                ) {
                    NotesAction.entries.filter { it in folded }.forEach { action -> OverflowEntries(model, action, folded, dismiss) }
                }
            }
        }
        // The recent notes list opens at the menu's button, like Open's arrow does at its own.
        if (model.recentMenuOpen && NotesAction.OPEN in folded) RecentNotes(model, tc)
    }
}

@Composable
private fun OverflowEntries(model: NotesActionModel, action: NotesAction, folded: List<NotesAction>, dismiss: () -> Unit) {
    when (action) {
        NotesAction.PREVIEW -> MenuRow("preview", "Preview", model.hasBlocks, dismiss) { model.onToggleMd() }
        NotesAction.COPY -> AnnotationCopyFormat.entries.forEach { format ->
            val label = "Copy as ${format.label}" + if (format == model.defaultFormat) " (default)" else ""
            MenuRow("copy-${format.name.lowercase()}", label, model.feedback.phase != CopyPhase.Copying, dismiss) { model.feedback.start(format) }
        }
        NotesAction.SAVE -> MenuRow("save", "Save", !model.locked, dismiss) { model.mutate(model.onSave) }
        NotesAction.NEW -> MenuRow("new", "New", !model.locked, dismiss) { model.mutate(model.onNewAnalysis) }
        NotesAction.LOCATE -> MenuRow("locate", "Locate log…", !model.locked, dismiss) { model.mutate(model::locateLog) }
        NotesAction.OPEN -> {
            MenuRow("open", "Open…", !model.locked, dismiss) { model.mutate(model::openNote) }
            if (model.hasRecent && NotesAction.OPEN in folded) MenuRow("recent", "Recent notes…", !model.locked, dismiss) { model.onToggleRecentNotes() }
        }
    }
}

private const val OVERFLOW_MENU_WIDTH_DP = 200

@Composable
private fun MenuRow(id: String, label: String, enabled: Boolean, dismiss: () -> Unit, action: () -> Unit) {
    val tc = tc()
    HoverBox(
        modifier = Modifier.fillMaxWidth().testTag(notesActionTag(id)),
        hoverEnabled = enabled,
        onClick = if (enabled) ({ dismiss(); action() }) else null,
    ) {
        AppText(
            label,
            color = if (enabled) tc.tx else tc.td.copy(.5f),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

internal enum class CopyPhase(val label: String) {
    Idle("Copy"),
    Copying("Copying…"),
    Copied("Copied!"),
    Failed("Copy failed"),
}

/** The Copy button's Idle → Copying… → Copied!/Copy failed → Idle cycle for one tab's notes. One
 * instance serves every copy surface of the panel, so a keyboard copy lights the same button up. */
@Stable
internal class CopyFeedbackState(
    private val request: (AnnotationCopyFormat, (Boolean) -> Unit) -> Unit,
) {
    var phase by mutableStateOf(CopyPhase.Idle)
        private set

    // Ignored while a copy is in flight: a second build of the same (possibly large) payload would
    // only queue behind the first and race it onto the clipboard.
    fun start(format: AnnotationCopyFormat) {
        if (phase == CopyPhase.Copying) return
        phase = CopyPhase.Copying
        request(format) { ok -> phase = if (ok) CopyPhase.Copied else CopyPhase.Failed }
    }

    fun reset() {
        if (phase != CopyPhase.Copying) phase = CopyPhase.Idle
    }
}

@Composable
internal fun rememberCopyFeedback(
    tabId: String,
    onCopyFormat: (AnnotationCopyFormat, (Boolean) -> Unit) -> Unit,
): CopyFeedbackState {
    val latestRequest by rememberUpdatedState(onCopyFormat)
    val state = remember(tabId) { CopyFeedbackState { format, done -> latestRequest(format, done) } }
    LaunchedEffect(state.phase) {
        if (state.phase == CopyPhase.Copied || state.phase == CopyPhase.Failed) {
            delay(COPIED_FEEDBACK_MS)
            state.reset()
        }
    }
    return state
}

@Composable
internal fun CopyFormatSplitButton(
    defaultFormat: AnnotationCopyFormat,
    feedback: CopyFeedbackState,
    modifier: Modifier = Modifier,
    onReclaimFocus: () -> Unit = {},
    look: NotesButtonLook = NotesButtonLook.LABELS,
) {
    val tc = tc()
    val density = LocalDensity.current.density
    val leftShape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)
    val rightShape = RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            if (look == NotesButtonLook.ICONS) {
                // The icon follows the Idle → Copying… → Copied! cycle (a wider label would push the row's other buttons around).
                AppButton(
                    "",
                    onClick = { feedback.start(defaultFormat); onReclaimFocus() },
                    enabled = feedback.phase != CopyPhase.Copying,
                    leadingIcon = when (feedback.phase) {
                        CopyPhase.Idle -> Icons.Outlined.ContentCopy
                        CopyPhase.Copying -> Icons.Outlined.HourglassEmpty
                        CopyPhase.Copied -> Icons.Outlined.Check
                        CopyPhase.Failed -> Icons.Outlined.ErrorOutline
                    },
                    horizontalPadding = COMPACT_PADDING,
                    shape = leftShape,
                    tooltip = feedback.phase.label,
                    modifier = modifier.semantics { contentDescription = feedback.phase.label },
                )
            } else {
                AppButton(
                    feedback.phase.label,
                    onClick = { feedback.start(defaultFormat); onReclaimFocus() },
                    enabled = feedback.phase != CopyPhase.Copying,
                    modifier = modifier,
                    horizontalPadding = if (look == NotesButtonLook.COMPACT) COMPACT_PADDING else 8.dp,
                    shape = leftShape,
                )
            }
            AppButton(
                "▾",
                onClick = { expanded = !expanded; onReclaimFocus() },
                enabled = feedback.phase != CopyPhase.Copying,
                modifier = Modifier.width(18.dp).testTag(NOTES_COPY_FORMATS_TAG),
                horizontalPadding = 0.dp,
                shape = rightShape,
            )
        }
        if (expanded) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, (34 * density).roundToInt()),
                onDismissRequest = { expanded = false; onReclaimFocus() },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.width(176.dp)
                        .background(tc.p, RoundedCornerShape(7.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                        .padding(vertical = 4.dp),
                ) {
                    AnnotationCopyFormat.entries.forEach { format ->
                        HoverBox(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                expanded = false
                                feedback.start(format)
                                onReclaimFocus()
                            },
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppText(format.label, color = tc.tx, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                if (format == defaultFormat) AppText("default", color = tc.td, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
