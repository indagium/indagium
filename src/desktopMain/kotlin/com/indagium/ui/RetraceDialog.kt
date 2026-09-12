package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

private val RETRACE_DIALOG_SHAPE = RoundedCornerShape(8.dp)

/** Modal, selectable, non-destructive presentation of one retraced Java/Kotlin exception group. */
@Composable
internal fun RetraceDialog(state: AppState, result: RetraceDialogState) {
    val tc = tc()
    Dialog(onDismissRequest = state::dismissRetraceDialog) {
        Column(
            Modifier.width(760.dp).heightIn(min = 190.dp, max = 650.dp)
                .background(tc.p, RETRACE_DIALOG_SHAPE)
                .border(1.dp, tc.br, RETRACE_DIALOG_SHAPE)
                .padding(18.dp),
        ) {
            AppText("Retraced stack trace", color = tc.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            when (result) {
                is RetraceDialogState.Loading -> {
                    AppText("Retracing with the selected R8 mapping…", color = tc.td, fontSize = 11.sp)
                    Spacer(Modifier.height(18.dp))
                    IndeterminateLoadingLine(Modifier.fillMaxWidth())
                }
                is RetraceDialogState.Success -> {
                    SelectionContainer(
                        Modifier.weight(1f).fillMaxWidth().background(tc.bg, CORNER_SM)
                            .border(1.dp, tc.br.copy(.6f), CORNER_SM)
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        AppText(
                            result.text,
                            color = tc.tx,
                            fontSize = 11.sp,
                            fontFamily = MONO,
                            maxLines = Int.MAX_VALUE,
                        )
                    }
                }
                is RetraceDialogState.Failure -> {
                    AppText(result.message, color = DANGER_RED, fontSize = 11.sp, maxLines = 6)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (result is RetraceDialogState.Success) {
                    AppButton("Copy", onClick = { state.copyToClipboard(result.text) }, variant = ButtonVariant.Secondary)
                    Spacer(Modifier.width(8.dp))
                }
                AppButton("Close", onClick = state::dismissRetraceDialog, variant = ButtonVariant.Primary)
            }
        }
    }
}
