package com.indagium.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

// The Notes tile header's "Shared · N tabs" badge for a tile that shows a shared note, and the popover it opens: which tabs write into the
// note (open ones as checkboxes, closed ones greyed with Reopen), Add tab…, Rename…, Stop sharing… and Delete shared note… The dialogs those
// open are SharedNoteDialogs.kt's.

internal const val SHARED_BADGE_TAG = "notes-shared-badge"
internal const val SHARED_POPOVER_TAG = "notes-shared-popover"

internal fun sharedMemberTag(tabId: String): String = "shared-note-member-$tabId"

internal const val SHARED_REOPEN_TAG_PREFIX = "shared-note-reopen-"
internal const val SHARED_ADD_TAB_TAG = "shared-note-add-tab"
internal const val SHARED_RENAME_TAG = "shared-note-rename"
internal const val SHARED_STOP_TAG = "shared-note-stop"
internal const val SHARED_DELETE_TAG = "shared-note-delete"

/** Card width (dp) from which the badge reads "Shared · N tabs"; narrower, it is the link icon and the count. */
internal const val SHARED_BADGE_TEXT_MIN_DP = 260f

/** Card width (dp) from which the note's name follows the badge, when there is room left in the header for it. */
internal const val SHARED_BADGE_NAME_MIN_DP = 420f

private const val POPOVER_WIDTH_DP = 360
private const val POPOVER_OFFSET_DP = 24
private const val MEMBER_ROW_HEIGHT_DP = 28

/** The badge text for [openMembers] tabs at a card [widthDp] wide. */
internal fun sharedBadgeText(openMembers: Int, widthDp: Float): String =
    if (widthDp >= SHARED_BADGE_TEXT_MIN_DP) "Shared · ${tabCountLabel(openMembers)}" else "$openMembers"

/** The badge and (after it, while there is room) the note's name, for the shared note [hostId] shown in [viewingTabId]'s Notes tile. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SharedNoteBadge(
    state: AppState,
    viewingTabId: String,
    hostId: String,
    widthDp: Float,
    onReclaimFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tc = tc()
    val info = state.sharedNoteHost(hostId)?.sharedNote ?: return
    var open by remember { mutableStateOf(false) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            SettingsBadge(
                text = sharedBadgeText(info.memberTabIds.size, widthDp),
                icon = Icons.Outlined.Link,
                emphasized = true,
                modifier = Modifier.testTag(SHARED_BADGE_TAG),
                onClick = {
                    val closing = open
                    open = !open
                    if (closing) onReclaimFocus()
                },
            )
            if (open) {
                MembersPopover(state, viewingTabId, hostId, onReclaimFocus = onReclaimFocus, onClose = { open = false })
            }
        }
        if (widthDp >= SHARED_BADGE_NAME_MIN_DP) {
            AppText(
                info.name,
                color = tc.ts,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).testTag("notes-shared-name"),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MembersPopover(state: AppState, viewingTabId: String, hostId: String, onReclaimFocus: () -> Unit, onClose: () -> Unit) {
    val tc = tc()
    // Dismissing the popover (Esc, a click outside, unticking this tab) hands the keyboard back to the Notes tile; choosing a dialog action does not.
    val onDismiss = { onClose(); onReclaimFocus() }
    val density = LocalDensity.current.density
    val info = state.sharedNoteHost(hostId)?.sharedNote ?: return
    // A member unticked here stays listed (unticked) until the popover closes, so it can be ticked again; Add tab… is for other tabs.
    var unticked by remember { mutableStateOf(emptySet<String>()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    val listed = (info.memberTabIds + unticked.filter { it !in info.memberTabIds }).mapNotNull { id -> state.tab(id) }
    val shape = RoundedCornerShape(7.dp)
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(0, (POPOVER_OFFSET_DP * density).roundToInt()),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(POPOVER_WIDTH_DP.dp).background(tc.p, shape).border(1.dp, tc.br, shape).padding(10.dp)
                .focusRequester(focus).focusable().testTag(SHARED_POPOVER_TAG)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppText("Tabs writing into this note", color = tc.ts, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Column {
                listed.forEach { tab ->
                    Row(Modifier.fillMaxWidth().height(MEMBER_ROW_HEIGHT_DP.dp), verticalAlignment = Alignment.CenterVertically) {
                        SettingsCheckbox(
                            checked = tab.id in info.memberTabIds,
                            label = tab.filename,
                            monospace = true,
                            testTag = sharedMemberTag(tab.id),
                            modifier = Modifier.weight(1f, fill = false),
                            onChange = { ticked ->
                                val members = info.memberTabIds
                                if (ticked) {
                                    unticked = unticked - tab.id
                                    state.setSharedNoteMembers(hostId, members + tab.id)
                                } else {
                                    unticked = unticked + tab.id
                                    state.setSharedNoteMembers(hostId, members - tab.id)
                                }
                                if (tab.id == viewingTabId) onDismiss()
                            },
                        )
                        if (tab.id == viewingTabId) {
                            AppText("this tab", color = tc.ts, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                info.closedMembers.forEach { closed ->
                    Row(
                        Modifier.fillMaxWidth().height(MEMBER_ROW_HEIGHT_DP.dp).testTag("shared-note-closed-${closed.tabId}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            SettingsCheckbox(
                                checked = true,
                                label = closed.filename,
                                monospace = true,
                                enabled = false,
                                labelColor = tc.ts,
                                modifier = Modifier.weight(1f, fill = false),
                                onChange = {},
                            )
                            AppText("closed", color = tc.ts, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
                        }
                        AccentLink(
                            "Reopen",
                            onClick = { state.launchInBackground { state.reopenSharedNoteMember(hostId, closed.tabId) } },
                            modifier = Modifier.testTag(SHARED_REOPEN_TAG_PREFIX + closed.tabId),
                        )
                    }
                }
            }
            AccentLink(
                "Add tab…",
                onClick = {
                    state.sharedNoteDialog = SharedNoteDialogRequest.AddTabs(hostId, onReclaimFocus)
                    onClose()
                },
                modifier = Modifier.testTag(SHARED_ADD_TAB_TAG),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PopoverButton("Rename…", SHARED_RENAME_TAG) {
                    state.sharedNoteDialog = SharedNoteDialogRequest.Rename(hostId, onReclaimFocus)
                    onClose()
                }
                PopoverButton("Stop sharing…", SHARED_STOP_TAG, danger = true) {
                    state.sharedNoteDialog = SharedNoteDialogRequest.StopSharing(hostId, viewingTabId, onReclaimFocus)
                    onClose()
                }
                PopoverButton("Delete shared note…", SHARED_DELETE_TAG, danger = true) {
                    state.sharedNoteDialog = SharedNoteDialogRequest.Delete(hostId, onReclaimFocus)
                    onClose()
                }
            }
        }
    }
}

@Composable
private fun PopoverButton(label: String, tag: String, danger: Boolean = false, onClick: () -> Unit) {
    AppButton(label, onClick = onClick, isDanger = danger, modifier = Modifier.testTag(tag), verticalPadding = 3.dp)
}
