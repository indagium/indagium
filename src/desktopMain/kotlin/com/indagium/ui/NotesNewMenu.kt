package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

// The ▾ half of the Notes header's New button and its menu: New note (this tab only), New shared note… and Share this note…. The main half is the
// plain New it always was; the pair is drawn like Open ▾ (NotesActionButtons). The menu keeps keyboard focus while it is open, and dismissing it or
// choosing an item that finishes at once hands the keyboard back to the panel. Items that open a dialog leave the focus to the dialog, which gives it
// back when it closes (SharedNoteDialogRequest.reclaimFocus).

private const val NEW_MENU_WIDTH_DP = 248
private const val NEW_MENU_OFFSET_DP = 34
private const val DISABLED_ITEM_ALPHA = 0.45f

@Composable
internal fun NewMenuHalf(model: NotesActionModel, shared: NotesSharedActions, tc: ThemeColors) {
    val density = LocalDensity.current.density
    var expanded by remember { mutableStateOf(false) }
    val popupFocus = remember { FocusRequester() }
    LaunchedEffect(expanded) {
        if (expanded) {
            withFrameNanos { }
            runCatching { popupFocus.requestFocus() }
        }
    }

    fun dismiss() {
        expanded = false
        model.onReclaimFocus()
    }
    Box {
        AppButton(
            "▾",
            modifier = Modifier.width(18.dp).testTag(NOTES_NEW_MENU_TAG).semantics { contentDescription = "New note options" },
            horizontalPadding = 0.dp,
            shape = RECENT_DROPDOWN_SHAPE,
            onClick = {
                val closing = expanded
                expanded = !expanded
                if (closing) model.onReclaimFocus()
            },
        )
        if (expanded) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, (NEW_MENU_OFFSET_DP * density).roundToInt()),
                onDismissRequest = ::dismiss,
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.width(NEW_MENU_WIDTH_DP.dp)
                        .background(tc.p, RoundedCornerShape(7.dp))
                        .border(1.dp, tc.br, RoundedCornerShape(7.dp))
                        .padding(4.dp)
                        .focusRequester(popupFocus)
                        .focusable()
                        .testTag(NOTES_NEW_POPUP_TAG)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                dismiss()
                                true
                            } else {
                                false
                            }
                        },
                ) {
                    NewMenuItem(
                        tag = NOTES_NEW_ITEM_NEW_TAG,
                        icon = Icons.Outlined.Add,
                        label = "New note",
                        caption = "For this tab only",
                        enabled = !model.locked,
                    ) {
                        expanded = false
                        model.tap { model.mutate(model.onNewAnalysis) }
                    }
                    NewMenuItem(
                        tag = NOTES_NEW_ITEM_SHARED_TAG,
                        icon = Icons.Outlined.Link,
                        label = "New shared note…",
                        caption = "Choose the tabs that write into it",
                        enabled = true,
                    ) {
                        expanded = false
                        shared.onNewSharedNote()
                    }
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(tc.br))
                    NewMenuItem(
                        tag = NOTES_NEW_ITEM_SHARE_TAG,
                        icon = Icons.Outlined.IosShare,
                        label = "Share this note…",
                        caption = "Keeps its text; this tab starts a fresh private note",
                        enabled = !model.locked && shared.canShareThisNote,
                    ) {
                        expanded = false
                        shared.onShareThisNote()
                    }
                }
            }
        }
    }
}

@Composable
private fun NewMenuItem(tag: String, icon: ImageVector, label: String, caption: String, enabled: Boolean, onClick: () -> Unit) {
    val tc = tc()
    HoverBox(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).alpha(if (enabled) 1f else DISABLED_ITEM_ALPHA)
            .testTag(tag)
            .then(if (enabled) Modifier else Modifier.semantics { disabled() }),
        hoverEnabled = enabled,
        onClick = if (enabled) onClick else null,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tc.ts, modifier = Modifier.padding(top = 1.dp).size(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                AppText(label, color = tc.tx, fontSize = 12.sp)
                AppText(caption, color = tc.ts, fontSize = 11.sp, maxLines = 2)
            }
        }
    }
}
