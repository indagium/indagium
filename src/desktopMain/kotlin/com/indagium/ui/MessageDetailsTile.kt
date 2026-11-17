package com.indagium.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.LogEntry
import com.indagium.model.LogTab

internal fun inspectedMessage(tab: LogTab): LogEntry? {
    val id = tab.inspectedRecordId?.takeIf { it in tab.selected } ?: tab.selected.lastOrNull() ?: tab.inspectedRecordId
    return id?.let(tab.rmap::get)
}

/** Follows the selected message; each new message starts with its field tree expanded. */
@Composable
internal fun MessageDetailsTileBody(tab: LogTab) {
    val colors = tc()
    val entry = inspectedMessage(tab)
    if (entry == null) {
        AppText("Select a message to inspect its contents.", color = colors.ts, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
        return
    }
    key(entry.id, entry.rawRecord, entry.msg) {
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                    .testTag("message-details-content-${entry.id}"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppText("${entry.ts}  ${entry.tag}", color = colors.ts, fontSize = 11.sp)
                AppText(entry.msg, color = colors.tx, fontSize = 12.sp, maxLines = 3)
                StructuredRecordBody(
                    entryId = entry.id,
                    source = entry.rawRecord ?: entry.msg,
                    mono = FontFamily.Monospace,
                    startIndent = 0.dp,
                    initiallyExpanded = true,
                    rootPath = "${entry.id}.details",
                    bodyTag = "message-details-record-${entry.id}",
                )
            }
            ThinVerticalScrollbar(
                scroll,
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag("message-details-scrollbar-${entry.id}"),
            )
        }
    }
}
