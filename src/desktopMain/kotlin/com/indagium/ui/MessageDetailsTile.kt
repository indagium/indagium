package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.LogEntry
import com.indagium.model.LogTab
import com.indagium.utils.isJsonRecord
import com.indagium.utils.recordInspectionSource

internal fun inspectedMessage(tab: LogTab): LogEntry? {
    val id = tab.inspectedRecordId?.takeIf { it in tab.selected } ?: tab.selected.lastOrNull() ?: tab.inspectedRecordId
    return id?.let(tab.rmap::get)
}

/** What the copy action puts on the clipboard: the record as it was read, or the message for a record without one. */
internal fun messageDetailsCopyText(entry: LogEntry): String = entry.rawRecord ?: entry.msg

/** The copy action's name, for what [messageDetailsCopyText] holds: a JSON record, one source line, or several. */
internal fun messageDetailsCopyLabel(entry: LogEntry): String {
    val text = messageDetailsCopyText(entry)
    return when {
        isJsonRecord(text) -> "Copy JSON"
        text.trimEnd().any { it == '\n' || it == '\r' } -> "Copy record"
        else -> "Copy line"
    }
}

internal fun messageDetailsTreeSource(entry: LogEntry): String = entry.recordInspectionSource()

internal fun messageDetailsShowsParsedFields(entry: LogEntry): Boolean =
    messageDetailsTreeSource(entry) != messageDetailsCopyText(entry)

/** The tile's header action: copies the inspected message's record, the raw JSON where it has one. */
@Composable
internal fun RowScope.MessageDetailsHeaderAction(state: AppState, tab: LogTab) {
    val entry = inspectedMessage(tab) ?: return
    AppButton(
        messageDetailsCopyLabel(entry),
        onClick = { state.copyToClipboard(messageDetailsCopyText(entry)) },
        horizontalPadding = 8.dp,
        modifier = Modifier.testTag("message-details-copy-${entry.id}"),
    )
}

@Composable
private fun MessageDetailsHeader(entry: LogEntry) {
    val colors = tc()
    Row(
        Modifier.fillMaxWidth().testTag("message-details-header-${entry.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppText(entry.ts, color = colors.ts, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("message-details-ts-${entry.id}"))
        if (entry.tag.isNotEmpty()) {
            // The filter panel's neutral tag pill, without its remove button: it only names the record's identity.
            Box(
                Modifier.weight(1f, fill = false)
                    .background(colors.td.copy(.10f), CORNER_SM)
                    .border(1.dp, colors.br, CORNER_SM)
                    .padding(horizontal = 7.dp, vertical = 1.dp)
                    .testTag("message-details-tag-${entry.id}"),
            ) {
                AppText(entry.tag, color = colors.ts, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Follows the selected message; each new message starts with its field tree expanded. The tree is the tile's flex part (TileFlexRegion.kt): in a
 * scrolling column the tile takes its share of the column (by weight, like Notes) up to what the tree needs, outside one it fills the tile.
 */
@Composable
internal fun MessageDetailsTileBody(tab: LogTab) {
    val colors = tc()
    val entry = inspectedMessage(tab)
    if (entry == null) {
        AppText("Select a message to inspect its contents.", color = colors.ts, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
        return
    }
    key(entry.id, entry.rawRecord, entry.msg) {
        val treeSource = remember(entry) { messageDetailsTreeSource(entry) }
        val scroll = rememberScrollState()
        // A drag across the field rows scrolls the tile while the pointer is held above or below it.
        val treeScroller = remember(scroll) { ScrollableRecordTreeScroller(scroll) }
        FlexRegion(scroll, capDp = null, fill = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth()
                    .onGloballyPositioned { treeScroller.viewportCoordinates = it }
                    .verticalScroll(scroll)
                    .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                    .testTag("message-details-content-${entry.id}"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The message is on the selected row and inside the record below, so the header is only when and who.
                MessageDetailsHeader(entry)
                CompositionLocalProvider(LocalRecordTreeScroller provides treeScroller) {
                    StructuredRecordBody(
                        entryId = entry.id,
                        source = treeSource,
                        mono = FontFamily.Monospace,
                        startIndent = 0.dp,
                        initiallyExpanded = true,
                        rootPath = "${entry.id}.details",
                        bodyTag = "message-details-record-${entry.id}",
                        recordTabId = tab.id,
                    )
                }
                if (entry.rawRecord != null && treeSource != entry.rawRecord) MessageDetailsSourceLine(entry)
            }
            // In a matchParentSize box so the scrollbar's fillMaxHeight does not claim the region's whole height.
            Box(Modifier.matchParentSize()) {
                ThinVerticalScrollbar(
                    scroll,
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag("message-details-scrollbar-${entry.id}"),
                )
            }
        }
    }
}

/** The record as read, under a tree of its parsed fields: the line the fields came from. */
@Composable
private fun MessageDetailsSourceLine(entry: LogEntry) {
    val colors = tc()
    Column(Modifier.fillMaxWidth().testTag("message-details-source-${entry.id}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AppText("Source", color = colors.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        SelectionContainer {
            AppText(entry.rawRecord ?: entry.msg, color = colors.ts, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = Int.MAX_VALUE)
        }
    }
}
