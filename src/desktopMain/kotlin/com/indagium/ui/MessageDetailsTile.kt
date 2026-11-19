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
import com.indagium.model.LogFieldRole
import com.indagium.model.LogTab
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
        '\n' in text.trimEnd() -> "Copy record"
        else -> "Copy line"
    }
}

/**
 * Whether [raw] is a JSON object or array: the tree then shows it as it was read. A line that only
 * starts with "[" (dmesg's "[    1.500000] …") is text, so an array must parse.
 */
internal fun isJsonRecord(raw: String?): Boolean = when (raw?.firstOrNull { !it.isWhitespace() }) {
    '{' -> true
    '[' -> runCatching { Json.parseToJsonElement(raw) is JsonArray }.getOrDefault(false)
    else -> false
}

/**
 * What the tile's tree shows for [entry]: a JSON record as read; for a record parsed from text (syslog,
 * logfmt, CSV, an access log), its parsed fields as one object, keys as the profile names them; for
 * anything else, the text itself.
 */
internal fun messageDetailsTreeSource(entry: LogEntry): String {
    val raw = entry.rawRecord
    if (isJsonRecord(raw)) return raw!!
    val fields = distinctRecordFields(entry.profileFields.filterValues { it.isNotBlank() })
    if (entry.recordMetadata?.usesProfilePresentation != true || fields.isEmpty()) return raw ?: entry.msg
    return buildJsonObject { fields.forEach { (key, value) -> put(profileFieldKey(key), value) } }.toString()
}

/**
 * A text record's fields with each value once. A parser keeps a value under its source key (logfmt's
 * `msg`, a CSV header's `time`) and again under the role it maps to (MESSAGE, TIMESTAMP); the role copy
 * goes when a source key holds the same value, so the tree uses the names of the Source line under it.
 * Structured data listed key by key (sd.id.key) drops its one-string copy.
 */
internal fun distinctRecordFields(fields: Map<String, String>): Map<String, String> {
    val sourceValues = fields.filterKeys { it !in ROLE_FIELD_KEYS }.values.toHashSet()
    val hasStructuredKeys = fields.keys.any { it.startsWith("sd.") }
    return fields.filter { (key, value) ->
        !(key in ROLE_FIELD_KEYS && value in sourceValues) && !(hasStructuredKeys && key.equals("structured_data", ignoreCase = true))
    }
}

/** Whether the tree shows parsed fields rather than the record as read, so the source line goes under it. */
internal fun messageDetailsShowsParsedFields(entry: LogEntry): Boolean =
    !isJsonRecord(entry.rawRecord) && messageDetailsTreeSource(entry) != (entry.rawRecord ?: entry.msg)

private val ROLE_FIELD_KEYS = LogFieldRole.entries.associate { it.name to it.name.lowercase() }

// A field the profile maps to a role is stored under the role's name (PROCESS_ID); the tree names it as the row's columns do.
private fun profileFieldKey(key: String): String = ROLE_FIELD_KEYS[key] ?: key

/**
 * Whether the Message details tile has anything to add for a profile tab: a JSON record, a record of
 * several lines, or a parsed field the row does not show (an RFC 5424 host or structured data, an access
 * log's referrer, a logfmt key). A sample of rows decides, once per set of rows, so the tile does not come
 * and go with the selection. A syslog 3164 tab shows host and process this way; a dmesg tab, whose row is
 * the whole record, gets no tile.
 */
internal fun profileRecordsAddDetail(tabId: String, rows: List<LogEntry>): Boolean = synchronized(detailCache) {
    detailCache[tabId]?.takeIf { it.first === rows }?.let { return it.second }
    val sample = if (rows.size <= DETAIL_SAMPLE_ROWS) rows else rows.subList(0, DETAIL_SAMPLE_ROWS)
    val result = sample.any(::recordAddsDetail)
    detailCache[tabId] = rows to result
    result
}

private fun recordAddsDetail(entry: LogEntry): Boolean {
    val metadata = entry.recordMetadata?.takeIf { it.usesProfilePresentation } ?: return false
    val raw = metadata.rawRecord ?: entry.msg
    if (isJsonRecord(raw) || '\n' in raw.trimEnd()) return true
    val shown = metadata.displayColumns.mapTo(HashSet()) { it.value.trim() }
    return metadata.profileFields.values.any { value -> value.isNotBlank() && value.trim() !in shown && value.trim() != raw.trim() }
}

private const val DETAIL_SAMPLE_ROWS = 200
private const val MAX_DETAIL_CACHE = 16

private val detailCache = object : LinkedHashMap<String, Pair<List<LogEntry>, Boolean>>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<List<LogEntry>, Boolean>>?): Boolean =
        size > MAX_DETAIL_CACHE
}

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
                        source = messageDetailsTreeSource(entry),
                        mono = FontFamily.Monospace,
                        startIndent = 0.dp,
                        initiallyExpanded = true,
                        rootPath = "${entry.id}.details",
                        bodyTag = "message-details-record-${entry.id}",
                        recordTabId = tab.id,
                    )
                }
                if (messageDetailsShowsParsedFields(entry)) MessageDetailsSourceLine(entry)
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
