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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
// tile's one header row while they fit there, and in the panel's bar under it when they do not (NotesTile in LogViewTiling.kt, decided by
// NotesHeaderLayout.kt); wherever the panel is used without a tile it draws them in that bar (AnnotationPanel.kt). Either way they are the same text
// buttons, and every click or dismissed popup hands the keyboard back to the panel (a clickable takes focus, and the panel's shortcuts need it).

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

// One plain action button in [look]: its label with the log toolbar's padding, or less of it.
@Composable
private fun NotesButton(
    action: NotesAction,
    label: String,
    look: NotesButtonLook,
    enabled: Boolean,
    shape: Shape = CORNER_MD,
    onClick: () -> Unit,
) {
    AppButton(
        label,
        onClick = onClick,
        enabled = enabled,
        horizontalPadding = if (look == NotesButtonLook.COMPACT) COMPACT_PADDING else LABEL_PADDING,
        shape = shape,
        modifier = Modifier.testTag(notesButtonTag(action)),
    )
}

/**
 * The buttons in [look], emitted straight into the caller's layout (the tile's header row, or the panel's own wrapping bar). Disabled states are as
 * they always were: Preview needs notes, Save / New / Locate / Open need the notes to be unlocked.
 */
@Composable
internal fun NotesActionButtons(model: NotesActionModel, look: NotesButtonLook = NotesButtonLook.LABELS) {
    val tc = tc()
    NotesButton(NotesAction.PREVIEW, "Preview", look, model.hasBlocks) { model.tap(model.onToggleMd) }
    CopyFormatSplitButton(
        defaultFormat = model.defaultFormat,
        feedback = model.feedback,
        onReclaimFocus = model.onReclaimFocus,
        look = look,
        modifier = Modifier.testTag(notesButtonTag(NotesAction.COPY)),
    )
    NotesButton(NotesAction.SAVE, "Save", look, !model.locked) { model.tap { model.mutate(model.onSave) } }
    NotesButton(NotesAction.NEW, "New", look, !model.locked) { model.tap { model.mutate(model.onNewAnalysis) } }
    // Only when this tab has no log at all (opened via Case Library's "Open notes only," a blank new tab, or its own log going missing): the guided
    // reconnect path for the "note opened without its log" hazard. See AppState.locateLogForTab.
    if (model.hasLocate) {
        NotesButton(NotesAction.LOCATE, "Locate log…", look, !model.locked) { model.tap { model.mutate(model::locateLog) } }
    }
    // Open+▾ is one unit (zero spacing between the halves), so a wrap point never splits the pair.
    Row {
        NotesButton(NotesAction.OPEN, "Open", look, !model.locked, shape = if (model.hasRecent) OPEN_JOINED_SHAPE else CORNER_MD) {
            model.tap { model.mutate(model::openNote) }
        }
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

/** Test tag of the ▾ half of the Copy split button (its main half carries the caller's tag, `notes-button-copy` on the Notes buttons). */
internal const val NOTES_COPY_FORMATS_TAG = "notes-copy-formats"

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
            AppButton(
                feedback.phase.label,
                onClick = { feedback.start(defaultFormat); onReclaimFocus() },
                enabled = feedback.phase != CopyPhase.Copying,
                modifier = modifier,
                horizontalPadding = if (look == NotesButtonLook.COMPACT) COMPACT_PADDING else 8.dp,
                shape = leftShape,
            )
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
