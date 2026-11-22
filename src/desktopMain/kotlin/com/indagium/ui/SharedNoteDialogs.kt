package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indagium.model.LogTab

// The dialogs of the shared-note flows, drawn by SharedNoteDialogHost (App.kt) from AppState.sharedNoteDialog: the Notes header and the Shared
// badge only set the request, so a dialog survives the header changing shape (its buttons moving to the bar and back) while it is open.

/** Which shared-note dialog is open. [reclaimFocus] gives the keyboard back to whatever opened it once the dialog closes (CLAUDE.md's focus gotcha). */
internal sealed interface SharedNoteDialogRequest {
    val reclaimFocus: () -> Unit

    /** "New shared note" ([shareExistingNote] false) or "Share note" (true: [tabId]'s current note becomes the shared note). */
    data class Share(val tabId: String, val shareExistingNote: Boolean, override val reclaimFocus: () -> Unit = {}) : SharedNoteDialogRequest

    data class Rename(val hostId: String, override val reclaimFocus: () -> Unit = {}) : SharedNoteDialogRequest

    /** Pick open tabs to add to the note. */
    data class AddTabs(val hostId: String, override val reclaimFocus: () -> Unit = {}) : SharedNoteDialogRequest

    /** Confirm ending the sharing; the note goes back into [tabId] as its private note. */
    data class StopSharing(val hostId: String, val tabId: String, override val reclaimFocus: () -> Unit = {}) : SharedNoteDialogRequest

    /** [tabId]'s private note has content: confirm replacing it with the shared note. */
    data class ReplaceNote(val hostId: String, val tabId: String, override val reclaimFocus: () -> Unit = {}) : SharedNoteDialogRequest

    data class Delete(val hostId: String, override val reclaimFocus: () -> Unit = {}) : SharedNoteDialogRequest
}

private const val DIALOG_WIDTH_DP = 560
private const val SMALL_DIALOG_WIDTH_DP = 440
private const val TAB_ROW_HEIGHT_DP = 30
private const val TAB_LIST_MAX_HEIGHT_DP = 220
private const val DEFAULT_NAME_MAX_CHARS = 60

/** Test tag of the share dialog's name field, its primary button and one tab's row (`shared-note-tab-<tabId>`). */
internal const val SHARE_DIALOG_NAME_TAG = "shared-note-name-field"
internal const val SHARE_DIALOG_CONFIRM_TAG = "shared-note-dialog-confirm"

internal fun sharedNoteTabRowTag(tabId: String): String = "shared-note-tab-$tabId"

/** Draws the open shared-note dialog, if any. Compose it once, next to the app's other dialogs. */
@Composable
internal fun SharedNoteDialogHost(state: AppState) {
    val request = state.sharedNoteDialog ?: return

    fun close() {
        state.sharedNoteDialog = null
        request.reclaimFocus()
    }
    when (request) {
        is SharedNoteDialogRequest.Share -> ShareNoteDialog(state, request, ::close)
        is SharedNoteDialogRequest.AddTabs -> AddTabsDialog(state, request, ::close)
        is SharedNoteDialogRequest.Rename -> {
            val name = state.sharedNoteHost(request.hostId)?.sharedNote?.name
            if (name == null) {
                LaunchedEffect(request) { close() }
            } else {
                WorkspaceProfileNameDialog(
                    title = "Rename shared note",
                    confirmLabel = "Rename",
                    initialName = name,
                    takenNames = emptyList(),
                    noun = "note",
                    onConfirm = { newName ->
                        state.renameSharedNote(request.hostId, newName)
                        close()
                    },
                    onDismiss = ::close,
                )
            }
        }
        is SharedNoteDialogRequest.StopSharing -> StopSharingDialog(state, request, ::close)
        is SharedNoteDialogRequest.ReplaceNote -> ReplaceNoteDialog(state, request, ::close)
        is SharedNoteDialogRequest.Delete -> DeleteSharedNoteDialog(state, request, ::close)
    }
}

private fun AppState.noteNotice(title: String, body: String) {
    pendingDiagramNotice = DiagramNotice(title, body)
}

private const val WAITING_DECISION = "A note change is waiting for your decision. Resolve it, then try again."

// ── New shared note / Share note ──────────────────────────────────────────────────────────────

/** The name a shared note starts with when it is made from [tab]'s own note: the note's first line when the user wrote one, else "<file> analysis". */
internal fun AppState.defaultSharedNoteName(tab: LogTab): String {
    val seeded = "${settings.annotationPrefixLabel.trim().ifBlank { "From" }} ${tab.filename}"
    val firstLine = tab.annotations.prefix.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    return firstLine?.takeIf { it != seeded }?.take(DEFAULT_NAME_MAX_CHARS) ?: "${tab.filename} analysis"
}

@Composable
private fun ShareNoteDialog(state: AppState, request: SharedNoteDialogRequest.Share, onClose: () -> Unit) {
    val tc = tc()
    val thisTab = state.tabs.firstOrNull { it.id == request.tabId }
    if (thisTab == null) {
        LaunchedEffect(request) { onClose() }
        return
    }
    // This tab first, then the others in strip order.
    val tabs = listOf(thisTab) + state.tabs.filter { it.id != thisTab.id && !it.isCaptureLauncher }
    val initialName = if (request.shareExistingNote) state.defaultSharedNoteName(thisTab) else ""
    var name by remember(request) { mutableStateOf(TextFieldValue(initialName, TextRange(0, initialName.length))) }
    var ticked by remember(request) { mutableStateOf(setOf(thisTab.id)) }
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(request) { runCatching { nameFocus.requestFocus() } }
    val chosen = tabs.filter { it.id in ticked }
    val canConfirm = chosen.isNotEmpty()

    fun confirm() {
        if (!canConfirm) return
        val ids = chosen.map { it.id }
        val created = if (request.shareExistingNote) {
            state.shareTabNote(thisTab.id, name.text, ids)
        } else {
            state.createSharedNote(name.text, ids)
        }
        if (created == null) {
            state.noteNotice(if (request.shareExistingNote) "Couldn't share the note" else "Couldn't create the shared note", WAITING_DECISION)
        }
        onClose()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(DIALOG_WIDTH_DP.dp).background(tc.p, RoundedCornerShape(8.dp))
                .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(20.dp).testTag("shared-note-dialog"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppText(
                    if (request.shareExistingNote) "Share note" else "New shared note",
                    color = tc.tx,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                AppText("Lines you add from any of these tabs go into this one note.", color = tc.ts, fontSize = 12.sp, maxLines = 2)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppText("Name", color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                SettingsTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
                    placeholder = remember(request) { state.nextDefaultSharedNoteName() },
                    onSubmit = ::confirm,
                    testTag = SHARE_DIALOG_NAME_TAG,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppText("Tabs", color = tc.tx, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                TabCheckList(
                    state = state,
                    tabs = tabs,
                    thisTabId = thisTab.id,
                    ticked = ticked,
                    requiredTabId = thisTab.id.takeIf { request.shareExistingNote },
                    onToggle = { id -> ticked = if (id in ticked) ticked - id else ticked + id },
                )
                AppText("Tabs can be added or removed later from the note's Shared badge.", color = tc.ts, fontSize = 11.sp, maxLines = 2)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsDialogButton("Cancel") { onClose() }
                Box(Modifier.testTag(SHARE_DIALOG_CONFIRM_TAG)) {
                    SettingsDialogButton(
                        if (request.shareExistingNote) "Share note" else "Create shared note",
                        primary = true,
                        enabled = canConfirm,
                    ) { confirm() }
                }
            }
        }
    }
}

/**
 * The bordered list of tabs with a checkbox each: "This tab · <format>" first when [thisTabId] is among them, then "<format>". A tab whose file
 * name another open tab shares carries what tells them apart (the folder or archive, as the tab strip shows it). Each row is one toggle over its
 * whole width: the app's hover tint while the pointer is over it, and a focus ring only while it holds keyboard focus (a click leaves no mark).
 */
@Composable
private fun TabCheckList(
    state: AppState,
    tabs: List<LogTab>,
    thisTabId: String?,
    ticked: Set<String>,
    requiredTabId: String? = null,
    onToggle: (String) -> Unit,
) {
    val tc = tc()
    val shape = RoundedCornerShape(5.dp)
    Column(
        Modifier.fillMaxWidth().heightIn(max = TAB_LIST_MAX_HEIGHT_DP.dp).border(1.dp, tc.br, shape).clip(shape)
            .verticalScroll(rememberScrollState()),
    ) {
        tabs.forEachIndexed { index, tab ->
            TabCheckRow(
                checked = tab.id == requiredTabId || tab.id in ticked,
                name = tab.filename,
                disambiguation = tabDisambiguation(tab, state.tabs),
                trailing = state.tabFormatLabel(tab).let { if (tab.id == thisTabId) "This tab · $it" else it },
                enabled = tab.id != requiredTabId,
                onToggle = { onToggle(tab.id) },
                modifier = Modifier.testTag(sharedNoteTabRowTag(tab.id)),
            )
            if (index < tabs.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br.copy(alpha = ROW_DIVIDER_ALPHA)))
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TabCheckRow(
    checked: Boolean,
    name: String,
    disambiguation: String?,
    trailing: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    // Focus also lands on a row by a click; the ring is for the keyboard, so a press hides it until the next key.
    var pointerFocus by remember { mutableStateOf(false) }
    DisableSelection {
        Row(
            modifier.fillMaxWidth().height(TAB_ROW_HEIGHT_DP.dp)
                .background(if (hovered) tc.hv else Color.Transparent)
                .onPointerEvent(PointerEventType.Press) { pointerFocus = true }
                .onPreviewKeyEvent {
                    pointerFocus = false
                    false
                }
                .hoverable(interaction, enabled = enabled)
                .toggleable(value = checked, interactionSource = interaction, indication = null, enabled = enabled, role = Role.Checkbox) {
                    onToggle()
                }
                .then(if (focused && !pointerFocus) Modifier.border(1.dp, tc.ac, RoundedCornerShape(3.dp)) else Modifier)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CheckboxMark(checked)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                AppText(
                    name, color = if (enabled) tc.tx else tc.ts, fontSize = 12.sp, fontFamily = MONO, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (disambiguation != null) {
                    AppText(
                        " — $disambiguation", color = tc.ts, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            AppText(trailing, color = tc.ts, fontSize = 11.sp, maxLines = 1)
        }
    }
}

private const val ROW_DIVIDER_ALPHA = 0.6f

// ── Add tabs ─────────────────────────────────────────────────────────────────────────────────

@Composable
private fun AddTabsDialog(state: AppState, request: SharedNoteDialogRequest.AddTabs, onClose: () -> Unit) {
    val tc = tc()
    val info = state.sharedNoteHost(request.hostId)?.sharedNote
    if (info == null) {
        LaunchedEffect(request) { onClose() }
        return
    }
    val candidates = state.tabs.filter { !it.isCaptureLauncher && it.id !in info.memberTabIds }
    var ticked by remember(request) { mutableStateOf(emptySet<String>()) }
    val chosen = candidates.filter { it.id in ticked }

    fun add() {
        if (chosen.isEmpty()) return
        state.setSharedNoteMembers(request.hostId, info.memberTabIds + chosen.map { it.id })
        onClose()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(SMALL_DIALOG_WIDTH_DP.dp).background(tc.p, RoundedCornerShape(8.dp))
                .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(20.dp).testTag("shared-note-add-dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppText("Add tabs", color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                AppText(
                    "Tabs you add can put lines into ${info.name}. Each one shows the note from its Notes tile's Open menu.",
                    color = tc.ts,
                    fontSize = 12.sp,
                    maxLines = 3,
                )
            }
            if (candidates.isEmpty()) {
                AppText("Every open tab is already in this note.", color = tc.ts, fontSize = 12.sp)
            } else {
                TabCheckList(
                    state = state,
                    tabs = candidates,
                    thisTabId = null,
                    ticked = ticked,
                    onToggle = { id -> ticked = if (id in ticked) ticked - id else ticked + id },
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsDialogButton("Cancel") { onClose() }
                Box(Modifier.testTag(SHARE_DIALOG_CONFIRM_TAG)) {
                    SettingsDialogButton("Add", primary = true, enabled = chosen.isNotEmpty()) { add() }
                }
            }
        }
    }
}

// ── Stop sharing / replace / delete ──────────────────────────────────────────────────────────

@Composable
private fun StopSharingDialog(state: AppState, request: SharedNoteDialogRequest.StopSharing, onClose: () -> Unit) {
    val info = state.sharedNoteHost(request.hostId)?.sharedNote
    if (info == null) {
        LaunchedEffect(request) { onClose() }
        return
    }
    SettingsConfirmDialog(
        title = "Stop sharing ${info.name}?",
        message = "The note moves back into this tab as its private note. The other tabs go back to their own notes.",
        onDismissRequest = onClose,
    ) {
        SettingsDialogButton("Cancel") { onClose() }
        SettingsDialogButton("Stop sharing", primary = true, danger = true) {
            val tab = state.tab(request.tabId)
            when {
                state.stopSharing(request.hostId, request.tabId) -> onClose()
                tab != null && state.privateNoteHasContent(tab) ->
                    // Same dialog slot, next question: the tab's own note holds something the shared note would replace.
                    state.sharedNoteDialog = SharedNoteDialogRequest.ReplaceNote(request.hostId, request.tabId, request.reclaimFocus)
                else -> {
                    state.noteNotice("Couldn't stop sharing", WAITING_DECISION)
                    onClose()
                }
            }
        }
    }
}

@Composable
private fun ReplaceNoteDialog(state: AppState, request: SharedNoteDialogRequest.ReplaceNote, onClose: () -> Unit) {
    if (state.sharedNoteHost(request.hostId) == null) {
        LaunchedEffect(request) { onClose() }
        return
    }
    SettingsConfirmDialog(
        title = "Replace this tab's note?",
        message = "Its current private note is replaced by the shared note.",
        onDismissRequest = onClose,
    ) {
        SettingsDialogButton("Cancel") { onClose() }
        SettingsDialogButton("Replace note", primary = true, danger = true) {
            if (!state.stopSharing(request.hostId, request.tabId, replaceExisting = true)) {
                state.noteNotice("Couldn't stop sharing", WAITING_DECISION)
            }
            onClose()
        }
    }
}

@Composable
private fun DeleteSharedNoteDialog(state: AppState, request: SharedNoteDialogRequest.Delete, onClose: () -> Unit) {
    val info = state.sharedNoteHost(request.hostId)?.sharedNote
    if (info == null) {
        LaunchedEffect(request) { onClose() }
        return
    }
    SettingsConfirmDialog(
        title = "Delete ${info.name}?",
        message = "The shared note is removed from every tab. Its saved file stays on disk.",
        onDismissRequest = onClose,
    ) {
        SettingsDialogButton("Cancel") { onClose() }
        SettingsDialogButton("Delete shared note", primary = true, danger = true) {
            state.deleteSharedNote(request.hostId)
            onClose()
        }
    }
}
