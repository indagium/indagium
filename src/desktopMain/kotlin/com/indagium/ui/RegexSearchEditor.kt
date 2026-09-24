package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The transient multiline regex editor shared by the full FilterPanel and the horizontal
 * FilterBar. Callers own the draft and the Apply side effects so each surface can preserve its
 * existing debounce/sentinel state; this composable owns only the common dialog presentation and
 * the explicit Apply/Cancel/dismiss affordances.
 */
@Composable
internal fun RegexSearchEditor(
    text: String,
    onTextChange: (String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dialogTheme = tc()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(640.dp)
                .background(dialogTheme.p, RoundedCornerShape(8.dp))
                .border(1.dp, dialogTheme.br, RoundedCornerShape(8.dp))
                .padding(20.dp)
                .testTag("regex-editor-dialog"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppText("Edit regex search", color = dialogTheme.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            AppText(
                "Searches the exact text shown in a log row. Apply keeps this as a transient search; save it manually from Saved filters when needed.",
                color = dialogTheme.td,
                fontSize = 11.sp,
                maxLines = 2,
            )
            InlineField(
                value = text,
                onValue = onTextChange,
                placeholder = "regex…",
                modifier = Modifier.fillMaxWidth().height(220.dp).testTag("regex-editor-input"),
                fontSize = 12.sp,
                singleLine = false,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.CenterHorizontally),
            ) {
                AppButton(
                    "Apply",
                    onClick = onApply,
                    variant = ButtonVariant.Primary,
                    modifier = Modifier.testTag("regex-editor-apply"),
                )
                AppButton(
                    "Cancel",
                    onClick = onDismiss,
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.testTag("regex-editor-cancel"),
                )
            }
        }
    }
}
