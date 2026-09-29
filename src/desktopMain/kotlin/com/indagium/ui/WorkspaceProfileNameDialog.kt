package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Asks for a workspace profile name: prefilled and selected, Enter saves, Esc cancels (the dialog's
 * own dismiss), and Save stays disabled while the name is blank or already taken ([takenNames],
 * compared case-insensitively).
 */
@Composable
internal fun WorkspaceProfileNameDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    takenNames: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tc = tc()
    var value by remember { mutableStateOf(TextFieldValue(initialName, TextRange(0, initialName.length))) }
    val focus = remember { FocusRequester() }
    val trimmed = value.text.trim()
    val error = when {
        trimmed.isEmpty() -> "Enter a name."
        takenNames.any { it.trim().equals(trimmed, ignoreCase = true) } -> "A profile with this name already exists."
        else -> null
    }
    // Requested on open so typing replaces the prefilled name straight away.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun confirm() {
        if (error == null) onConfirm(trimmed)
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(380.dp).background(tc.p, RoundedCornerShape(8.dp))
                .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(20.dp),
        ) {
            AppText(title, color = tc.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            InlineField(
                value = value,
                onValue = { value = it },
                placeholder = "Profile name",
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("profile-name-field"),
                fontSize = 12.sp,
                onSubmit = ::confirm,
            )
            Spacer(Modifier.height(4.dp))
            // Kept on one fixed-height line so the buttons don't jump when the message appears.
            AppText(error.orEmpty(), color = DANGER_RED, fontSize = 10.sp, modifier = Modifier.height(14.dp))
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialogActionButton(confirmLabel, active = error == null, enabled = error == null) { confirm() }
                DialogActionButton("Cancel", active = false) { onDismiss() }
            }
        }
    }
}
