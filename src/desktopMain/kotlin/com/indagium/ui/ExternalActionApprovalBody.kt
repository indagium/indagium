package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The content of the external-MCP approval dialog for a per-call action (App.kt keeps the buttons and the device wording):
// a title, who is asking, and each labelled field, the exact command first among them, in a scrollable monospace box.

private val FIELD_BOX_MAX_HEIGHT = 140.dp
private const val FIELD_MAX_LINES = 40

@Composable
internal fun ExternalActionApprovalBody(action: ExternalActionDetails) {
    val colors = tc()
    AppText(action.title, color = colors.tx, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    AppText(action.summary, color = colors.td, fontSize = 12.sp, maxLines = 4)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        action.fields.forEach { (label, value) ->
            Column {
                AppText(label, color = colors.ts, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
                Column(
                    Modifier.fillMaxWidth().heightIn(max = FIELD_BOX_MAX_HEIGHT)
                        .background(colors.p2, CORNER_SM).border(1.dp, colors.br, CORNER_SM)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 7.dp, vertical = 4.dp),
                ) {
                    AppText(value, color = colors.tx, fontSize = 12.sp, fontFamily = MONO, maxLines = FIELD_MAX_LINES)
                }
            }
        }
    }
}
