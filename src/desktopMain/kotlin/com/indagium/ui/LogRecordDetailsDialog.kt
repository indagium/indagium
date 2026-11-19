package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indagium.model.LogEntry
import com.indagium.utils.buildLogRecordDetails
import com.indagium.utils.isJsonRecord
import com.indagium.utils.recordInspectionSource

private val DETAIL_LABEL_WIDTH = 96.dp
private val FIELD_KEY_WIDTH = 180.dp

/**
 * Everything a profile row carries beyond its visible columns: time, level, component, source
 * lines, every parsed field and the raw record. [onCopy] puts text on the clipboard; [onReclaimFocus]
 * hands keyboard focus back to the log view after a button or the dialog closes (a dismissed dialog
 * does not give it back on its own).
 */
@Composable
internal fun LogRecordDetailsDialog(
    entry: LogEntry,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
    onReclaimFocus: () -> Unit = {},
) {
    val tc = tc()
    val details = remember(entry) { buildLogRecordDetails(entry) }

    fun close() {
        onDismiss()
        onReclaimFocus()
    }
    // Wider than the 580dp default dialog width, so the platform clamp is switched off.
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(640.dp).heightIn(max = 720.dp).background(tc.p, RoundedCornerShape(8.dp))
                .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(18.dp).testTag("log-record-details-dialog"),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            AppText("Record details", color = tc.tx, fontSize = 15.sp)
            Column(
                Modifier.fillMaxWidth().background(tc.p2, RoundedCornerShape(6.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                details.summary.forEach { line ->
                    Row(Modifier.testTag("log-record-summary-${line.key}").semantics(mergeDescendants = true) {}) {
                        AppText(line.label, color = tc.ts, fontSize = 11.sp, modifier = Modifier.width(DETAIL_LABEL_WIDTH))
                        AppText(line.value, color = tc.tx, fontSize = 12.sp, maxLines = 3)
                    }
                }
            }
            val jsonSource = remember(entry) { entry.rawRecord?.takeIf(::isJsonRecord) }
            if (jsonSource != null) {
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    StructuredRecordBody(entry.id, entry.recordInspectionSource(), MONO, startIndent = 0.dp, initiallyExpanded = true)
                }
            } else {
                AppText("Fields (${details.fields.size})", color = tc.ts, fontSize = 11.sp)
                // The values are selectable so a part of one can be copied without the whole record.
                SelectionContainer(Modifier.weight(1f, fill = false)) {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        details.fields.forEach { (key, value) ->
                            Row(
                                Modifier.fillMaxWidth().testTag("log-record-field-$key").semantics(mergeDescendants = true) {},
                                verticalAlignment = Alignment.Top,
                            ) {
                                AppText(key, color = tc.ts, fontSize = 11.sp, fontFamily = MONO, modifier = Modifier.width(FIELD_KEY_WIDTH))
                                AppText(value, color = tc.tx, fontSize = 11.sp, fontFamily = MONO, maxLines = Int.MAX_VALUE)
                            }
                        }
                    }
                }
            }
            details.rawRecord?.let { raw ->
                AppText("Raw record", color = tc.ts, fontSize = 11.sp)
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 200.dp).background(tc.p2, RoundedCornerShape(6.dp))
                        .verticalScroll(rememberScrollState()).padding(10.dp)
                        .testTag("log-record-raw").semantics(mergeDescendants = true) {},
                ) {
                    SelectionContainer {
                        AppText(raw, color = tc.tx, fontSize = 11.sp, fontFamily = MONO, maxLines = Int.MAX_VALUE)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (details.rawRecord != null) {
                    Box(Modifier.testTag("log-record-copy-raw")) {
                        SettingsDialogButton("Copy raw record") {
                            onCopy(details.rawRecord)
                            onReclaimFocus()
                        }
                    }
                }
                Box(Modifier.testTag("log-record-copy-json")) {
                    SettingsDialogButton("Copy fields as JSON") {
                        onCopy(jsonSource ?: details.fieldsJson)
                        onReclaimFocus()
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.testTag("log-record-close")) { SettingsDialogButton("Close", primary = true, onClick = ::close) }
            }
        }
    }
}
