package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.LogEntry
import com.indagium.model.LogFormat

private const val MAX_JSON_CHARS = 256 * 1024
private const val MAX_JSON_DEPTH = 32
private const val MAX_JSON_CHILDREN = 128
private const val MAX_JSON_STRING_CHARS = 8 * 1024
private const val MAX_RECORD_PREVIEW_CHARS = 180
private const val MAX_RAW_LINES = 80
private const val MAX_RAW_LINE_CHARS = 600
private const val MAX_VALUE_PREVIEW_CHARS = 220
private const val MIN_KEY_CHARS = 4
private const val MAX_KEY_CHARS = 28
private const val KEY_CHAR_WIDTH_DP = 6.7f
private const val KEY_GAP_DP = 14f
private const val INDENT_PER_DEPTH_DP = 16
private val NODE_ROW_HEIGHT = 16.dp
private val HEX_SUMMARY_GAP = 8.dp

/** Width a row gives the disclosure arrow, arrow or not: the 24dp button and its 2dp gap. */
internal val STRUCTURED_DISCLOSURE_SLOT = 26.dp

/** Returns the profile/DLT payload eligible for the inline record disclosure. */
internal fun expandableRecordSource(entry: LogEntry, logFormat: LogFormat): String? {
    val raw = entry.rawRecord
    if (raw != null && entry.recordMetadata?.usesProfilePresentation == true) {
        val first = raw.dropWhile(Char::isWhitespace).firstOrNull()
        if (raw.contains('\n') || raw.contains('\r') || first == '{' || first == '[') return raw
    }
    if (logFormat == LogFormat.DLT && (entry.msg.contains('\n') || entry.msg.contains('\r'))) {
        return raw ?: entry.msg
    }
    return null
}

/** Normalizes embedded physical breaks and keeps collapsed profile/DLT summaries compact. */
internal fun compactRecordSummary(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.AnnotatedString {
    if (text.text.length <= MAX_RECORD_PREVIEW_CHARS && '\n' !in text.text && '\r' !in text.text) return text
    val source = text.subSequence(0, minOf(text.length, MAX_RECORD_PREVIEW_CHARS))
    val normalizedText = source.text.map { if (it == '\n' || it == '\r') ' ' else it }.joinToString("")
    return androidx.compose.ui.text.buildAnnotatedString {
        append(normalizedText)
        source.spanStyles.forEach { addStyle(it.item, it.start, it.end) }
        val truncated = text.length > MAX_RECORD_PREVIEW_CHARS
        // A paragraph style that ran to the end of the preview must also cover the " …" after it, or
        // that suffix would become a paragraph of its own and drop to a new line.
        val suffix = " …"
        val paragraphEnd = normalizedText.length + if (truncated) suffix.length else 0
        source.paragraphStyles.forEach {
            addStyle(it.item, it.start, if (it.end >= normalizedText.length) paragraphEnd else it.end)
        }
        if (truncated) {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = Color.Unspecified))
            append(suffix)
            pop()
        }
    }
}

/**
 * Whether a row's disclosure chevron is drawn: only while the row is hovered or selected, or while
 * the record is open (so an open record always shows how to close it). A hidden chevron still holds
 * its slot, stays clickable and keeps its semantics.
 */
internal fun structuredDisclosureVisible(hovered: Boolean, selected: Boolean, expanded: Boolean): Boolean =
    hovered || selected || expanded

/** Root disclosure chevron. Its click is kept separate from the log row's selection gesture. */
@Composable
internal fun StructuredRecordDisclosure(
    entryId: Int,
    expanded: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onBoundsChanged: (Float, Float) -> Unit = { _, _ -> },
    onClick: () -> Unit,
) {
    val tc = tc()
    CollapseChevron(
        expanded = expanded,
        color = color.takeUnless { it == Color.Unspecified } ?: tc.ts,
        onClick = onClick,
        modifier = modifier.size(width = 24.dp, height = 16.dp)
            .alpha(if (visible) 1f else 0f)
            .onGloballyPositioned { coordinates ->
                val left = coordinates.positionInRoot().x
                onBoundsChanged(left, left + coordinates.size.width)
            }
            .semantics { contentDescription = if (expanded) "Collapse record" else "Expand record" }
            .testTag("structured-record-disclosure-$entryId"),
    )
}

/** Inline body used under the one logical parent row. The tree is parsed only after expansion. */
@Composable
internal fun StructuredRecordBody(
    entryId: Int,
    source: String,
    mono: FontFamily,
    startIndent: androidx.compose.ui.unit.Dp = 46.dp,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    rootPath: String = "$entryId.root",
    bodyTag: String = "structured-record-body-$entryId",
) {
    val tc = tc()
    val tree = remember(source) { runCatching { BoundedJsonReader(source).parseRoot() }.getOrNull() }
    val keyColumn = remember(tree) { tree?.let { structuredKeyColumnDp(it) } ?: 0f }
    SelectionContainer(
        modifier = modifier.fillMaxWidth()
            .padding(start = startIndent, end = 12.dp, bottom = 5.dp)
            .testTag(bodyTag),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (tree is StructuredJsonValue.ObjectValue || tree is StructuredJsonValue.ArrayValue) {
                StructuredContainerRows(
                    tree,
                    path = rootPath,
                    depth = 0,
                    mono = mono,
                    tc = tc,
                    initiallyExpanded = initiallyExpanded,
                    keyColumnDp = keyColumn,
                )
            } else {
                RawRecordLines(entryId = entryId, source = source, mono = mono, tc = tc)
            }
        }
    }
}

@Composable
private fun StructuredContainerRows(
    value: StructuredJsonValue,
    path: String,
    depth: Int,
    mono: FontFamily,
    tc: ThemeColors,
    initiallyExpanded: Boolean,
    keyColumnDp: Float,
) {
    val children = when (value) {
        is StructuredJsonValue.ObjectValue -> value.fields.map { it.first to it.second }
        is StructuredJsonValue.ArrayValue -> value.values.mapIndexed { index, child -> "[$index]" to child }
        else -> emptyList()
    }
    val truncated = when (value) {
        is StructuredJsonValue.ObjectValue -> value.truncated
        is StructuredJsonValue.ArrayValue -> value.truncated
        else -> false
    }
    // One key column for the whole tree (keyColumnDp, measured from the row's own start), so every
    // value starts at the same x whatever its depth; a row already spends depth * indent of it.
    val keyWidth = (keyColumnDp - depth * INDENT_PER_DEPTH_DP).dp
    children.forEach { (label, child) ->
        val childPath = "$path/$label"
        val container = child is StructuredJsonValue.ObjectValue || child is StructuredJsonValue.ArrayValue
        val multilineText = child is StructuredJsonValue.StringValue && recordLines(child.value).size > 1
        // Bytes spelled in hex read as a dump, not one long wrapped run.
        val hexBytes = remember(childPath, child) { (child as? StructuredJsonValue.StringValue)?.let { parseHexBytes(it.value) } }
        val expandable = container || multilineText || hexBytes != null
        var expanded by remember(childPath) { mutableStateOf(initiallyExpanded) }
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = (depth * INDENT_PER_DEPTH_DP).dp, end = 3.dp)
                // One node per field: a screen reader hears "key value" together.
                .semantics(mergeDescendants = true) {}
                .testTag("structured-record-node-${pathTag(childPath)}"),
            verticalAlignment = Alignment.Top,
        ) {
            if (expandable) {
                Box(
                    Modifier.size(width = 20.dp, height = NODE_ROW_HEIGHT)
                        .clickable { expanded = !expanded }
                        .testTag("structured-record-node-toggle-${pathTag(childPath)}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                        contentDescription = if (expanded) "Collapse $label" else "Expand $label",
                        tint = tc.ts,
                        modifier = Modifier.size(14.dp),
                    )
                }
            } else {
                // Same height as a toggle, so rows with and without one are evenly spaced.
                Spacer(Modifier.size(width = 20.dp, height = NODE_ROW_HEIGHT))
            }
            AppText(
                label,
                color = tc.ts,
                fontSize = 11.sp,
                fontFamily = mono,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(keyWidth),
            )
            if (hexBytes != null) {
                StructuredHexSummary(hexBytes, mono, tc, wrap = initiallyExpanded, modifier = Modifier.weight(1f))
            } else {
                AppText(
                    if (initiallyExpanded && child is StructuredJsonValue.StringValue && !multilineText) child.value else scalarOrContainerSummary(child),
                    color = jsonValueColor(child, tc),
                    fontSize = 11.sp,
                    fontFamily = mono,
                    maxLines = if (initiallyExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (expandable && expanded) {
            when {
                container -> StructuredContainerRows(child, childPath, depth + 1, mono, tc, initiallyExpanded, keyColumnDp)
                hexBytes != null -> StructuredHexDump(
                    entryId = path.substringBefore('.').toIntOrNull() ?: 0,
                    path = childPath,
                    bytes = hexBytes,
                    depth = depth + 1,
                    mono = mono,
                    tc = tc,
                )
                child is StructuredJsonValue.StringValue -> StructuredStringLines(
                    entryId = path.substringBefore('.').toIntOrNull() ?: 0,
                    path = childPath,
                    value = child.value,
                    depth = depth + 1,
                    mono = mono,
                    tc = tc,
                    fullValues = initiallyExpanded,
                )
            }
        }
    }
    if (truncated) {
        AppText(
            "… more fields omitted",
            color = tc.ts,
            fontSize = 10.sp,
            fontFamily = mono,
            modifier = Modifier.padding(start = (depth * 16 + 20).dp, top = 2.dp),
        )
    }
}

@Composable
private fun RawRecordLines(entryId: Int, source: String, mono: FontFamily, tc: ThemeColors) {
    val lines = remember(source) { source.lineSequence().take(MAX_RAW_LINES + 1).toList() }
    lines.take(MAX_RAW_LINES).forEachIndexed { index, line ->
        AppText(
            line.take(MAX_RAW_LINE_CHARS),
            color = tc.tx,
            fontSize = 11.sp,
            fontFamily = mono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
                .padding(start = 20.dp)
                .testTag("structured-record-raw-line-$entryId-$index"),
        )
    }
    if (lines.size > MAX_RAW_LINES) {
        AppText(
            "… ${lines.size - MAX_RAW_LINES} more lines omitted",
            color = tc.ts,
            fontSize = 10.sp,
            fontFamily = mono,
            modifier = Modifier.padding(start = 20.dp),
        )
    }
}

@Composable
private fun StructuredStringLines(
    entryId: Int,
    path: String,
    value: String,
    depth: Int,
    mono: FontFamily,
    tc: ThemeColors,
    fullValues: Boolean = false,
) {
    val lines = remember(value) { recordLines(value) }
    val visible = lines.take(MAX_RAW_LINES)
    Column(Modifier.padding(start = (depth * 16 + 20).dp)) {
        visible.forEachIndexed { index, line ->
            Row(
                Modifier.fillMaxWidth()
                    .semantics(mergeDescendants = true) {}
                    .testTag("structured-record-string-line-$entryId-${pathTag(path)}-$index"),
                verticalAlignment = Alignment.Top,
            ) {
                AppText(
                    (index + 1).toString(),
                    color = tc.ts,
                    fontSize = 10.sp,
                    fontFamily = mono,
                    modifier = Modifier.width(30.dp),
                )
                AppText(
                    if (fullValues) line else line.take(MAX_RAW_LINE_CHARS),
                    color = tc.tx,
                    fontSize = 11.sp,
                    fontFamily = mono,
                    maxLines = if (fullValues) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (lines.size > MAX_RAW_LINES) {
            AppText("… ${lines.size - MAX_RAW_LINES} more lines omitted", color = tc.ts, fontSize = 10.sp)
        }
    }
}

/** A hex field's line: the bytes as quoted text when they decode to printable UTF-8, then "hex · N bytes" in dim text. */
@Composable
private fun StructuredHexSummary(bytes: ByteArray, mono: FontFamily, tc: ThemeColors, wrap: Boolean, modifier: Modifier = Modifier) {
    val decoded = remember(bytes) { decodeHexText(bytes) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(HEX_SUMMARY_GAP), verticalAlignment = Alignment.Top) {
        if (decoded != null) {
            AppText(
                "\"${escapeJsonPreview(decoded)}\"",
                color = tc.tx,
                fontSize = 11.sp,
                fontFamily = mono,
                maxLines = if (wrap) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        AppText(hexByteCountLabel(bytes.size), color = tc.td, fontSize = 11.sp, fontFamily = mono, maxLines = 1)
    }
}

/** The expanded hex field: offset, sixteen lowercase byte pairs and the printable-ASCII column per line, on a subtle background. */
@Composable
private fun StructuredHexDump(entryId: Int, path: String, bytes: ByteArray, depth: Int, mono: FontFamily, tc: ThemeColors) {
    val lines = remember(bytes) { hexDumpLines(bytes) }
    // A full line is about 70 characters, wider than a narrow tile: the lines scroll sideways inside their box rather than lose the ASCII column.
    Box(
        Modifier.padding(start = (depth * INDENT_PER_DEPTH_DP + 20).dp, end = 3.dp, top = 2.dp, bottom = 2.dp)
            .fillMaxWidth()
            .background(tc.bg, CORNER_SM)
            .testTag("structured-record-hex-${pathTag(path)}"),
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) {
            lines.take(MAX_RAW_LINES).forEachIndexed { index, line ->
                androidx.compose.material3.Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = tc.td)) { append(line.offset) }
                        append("  ${line.padded}  ${line.ascii}")
                    },
                    color = tc.tx,
                    fontSize = 11.sp,
                    fontFamily = mono,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.testTag("structured-record-hex-line-$entryId-${pathTag(path)}-$index"),
                )
            }
            if (lines.size > MAX_RAW_LINES) {
                AppText("… ${lines.size - MAX_RAW_LINES} more lines omitted", color = tc.ts, fontSize = 10.sp, fontFamily = mono)
            }
        }
    }
}

private fun recordLines(value: String): List<String> =
    value.replace("\r\n", "\n").replace('\r', '\n').split('\n')

private fun scalarOrContainerSummary(value: StructuredJsonValue): String = when (value) {
    is StructuredJsonValue.ObjectValue -> "{${value.fields.size} fields}"
    is StructuredJsonValue.ArrayValue -> "[${value.values.size} items]"
    is StructuredJsonValue.StringValue -> if (recordLines(value.value).size > 1) {
        "${recordLines(value.value).size} lines"
    } else {
        "\"${escapeJsonPreview(value.value)}\""
    }
    is StructuredJsonValue.NumberValue -> value.value
    is StructuredJsonValue.BooleanValue -> value.value.toString()
    StructuredJsonValue.NullValue -> "null"
}

private fun escapeJsonPreview(value: String): String = buildString {
    value.take(MAX_VALUE_PREVIEW_CHARS).forEach { char ->
        append(when (char) {
            '\\' -> "\\\\"
            '"' -> "\\\""
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> char.toString()
        })
    }
    if (value.length > MAX_VALUE_PREVIEW_CHARS) append('…')
}

private fun jsonValueColor(value: StructuredJsonValue, tc: ThemeColors): Color = when (value) {
    is StructuredJsonValue.ObjectValue, is StructuredJsonValue.ArrayValue -> tc.td
    is StructuredJsonValue.StringValue, is StructuredJsonValue.NumberValue, is StructuredJsonValue.BooleanValue -> tc.tx
    StructuredJsonValue.NullValue -> tc.td
}

/**
 * Width, in dp from a row's start, of the key column shared by every row of one tree: the widest
 * (indent of its depth + clamped key text + gap) over all nodes, array labels "[0]" included. The
 * whole parsed tree is measured, so toggling a node never moves the values.
 */
private fun structuredKeyColumnDp(root: StructuredJsonValue): Float {
    var widest = 0f

    fun visit(value: StructuredJsonValue, depth: Int) {
        val children = when (value) {
            is StructuredJsonValue.ObjectValue -> value.fields
            is StructuredJsonValue.ArrayValue -> value.values.mapIndexed { index, child -> "[$index]" to child }
            else -> return
        }
        children.forEach { (label, child) ->
            val width = depth * INDENT_PER_DEPTH_DP +
                label.length.coerceIn(MIN_KEY_CHARS, MAX_KEY_CHARS) * KEY_CHAR_WIDTH_DP + KEY_GAP_DP
            if (width > widest) widest = width
            visit(child, depth + 1)
        }
    }
    visit(root, 0)
    return widest
}

private fun pathTag(path: String): String = path.map { if (it.isLetterOrDigit()) it else '-' }
    .joinToString("").replace(Regex("-+"), "-").trim('-')

private sealed interface StructuredJsonValue {
    data class ObjectValue(val fields: List<Pair<String, StructuredJsonValue>>, val truncated: Boolean) : StructuredJsonValue

    data class ArrayValue(val values: List<StructuredJsonValue>, val truncated: Boolean) : StructuredJsonValue

    data class StringValue(val value: String) : StructuredJsonValue

    data class NumberValue(val value: String) : StructuredJsonValue

    data class BooleanValue(val value: Boolean) : StructuredJsonValue

    data object NullValue : StructuredJsonValue
}

/** Small bounded parser so expanding an untrusted record cannot recurse without limit or allocate unbounded UI state. */
private class BoundedJsonReader(private val source: String) {
    private var cursor = 0
    private var nodeCount = 0

    fun parseRoot(): StructuredJsonValue? {
        if (source.length > MAX_JSON_CHARS) return null
        skipWhitespace()
        val value = parseValue(0)
        skipWhitespace()
        return value?.takeIf { cursor == source.length }
    }

    private fun parseValue(depth: Int): StructuredJsonValue? {
        if (depth > MAX_JSON_DEPTH || ++nodeCount > MAX_JSON_CHILDREN * MAX_JSON_DEPTH) return null
        skipWhitespace()
        return when (peek()) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> parseString()?.let(StructuredJsonValue::StringValue)
            't' -> literal("true")?.let { StructuredJsonValue.BooleanValue(true) }
            'f' -> literal("false")?.let { StructuredJsonValue.BooleanValue(false) }
            'n' -> literal("null")?.let { StructuredJsonValue.NullValue }
            '-', in '0'..'9' -> parseNumber()?.let(StructuredJsonValue::NumberValue)
            else -> null
        }
    }

    private fun parseObject(depth: Int): StructuredJsonValue.ObjectValue? {
        cursor++
        skipWhitespace()
        if (consume('}')) return StructuredJsonValue.ObjectValue(emptyList(), truncated = false)
        val fields = mutableListOf<Pair<String, StructuredJsonValue>>()
        var truncated = false
        while (true) {
            skipWhitespace()
            val key = parseString() ?: return null
            skipWhitespace()
            if (!consume(':')) return null
            val value = parseValue(depth + 1) ?: return null
            if (fields.size < MAX_JSON_CHILDREN) fields += key to value else truncated = true
            skipWhitespace()
            if (consume('}')) break
            if (!consume(',')) return null
            if (fields.size >= MAX_JSON_CHILDREN) {
                // Consume the rest using the same grammar, but skip materializing additional nodes.
                truncated = true
            }
            if (fields.size >= MAX_JSON_CHILDREN && source.getOrNull(cursor)?.let { it == '}' } == true) {
                cursor++
                break
            }
        }
        return StructuredJsonValue.ObjectValue(fields, truncated)
    }

    private fun parseArray(depth: Int): StructuredJsonValue.ArrayValue? {
        cursor++
        skipWhitespace()
        if (consume(']')) return StructuredJsonValue.ArrayValue(emptyList(), truncated = false)
        val values = mutableListOf<StructuredJsonValue>()
        var truncated = false
        while (true) {
            val value = parseValue(depth + 1) ?: return null
            if (values.size < MAX_JSON_CHILDREN) values += value else truncated = true
            skipWhitespace()
            if (consume(']')) break
            if (!consume(',')) return null
            if (values.size >= MAX_JSON_CHILDREN) truncated = true
        }
        return StructuredJsonValue.ArrayValue(values, truncated)
    }

    @Suppress("ReturnCount") // Each malformed-input case ends the scan where it is found.
    private fun parseString(): String? {
        if (!consume('"')) return null
        val result = StringBuilder()
        while (cursor < source.length) {
            val char = source[cursor++]
            when {
                char == '"' -> return result.toString()
                char == '\\' -> {
                    val escaped = source.getOrNull(cursor++) ?: return null
                    when (escaped) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            val hex = source.substringOrNull(cursor, cursor + 4) ?: return null
                            val code = hex.toIntOrNull(16) ?: return null
                            result.append(code.toChar())
                            cursor += 4
                        }
                        else -> return null
                    }
                }
                char.code < 0x20 -> return null
                else -> result.append(char)
            }
            if (result.length > MAX_JSON_STRING_CHARS) return null
        }
        return null
    }

    private fun parseNumber(): String? {
        val start = cursor
        if (peek() == '-') cursor++
        if (peek() == '0') {
            cursor++
        } else {
            if (peek() !in '1'..'9') return null
            while (peek() in '0'..'9') cursor++
        }
        if (peek() == '.') {
            cursor++
            if (peek() !in '0'..'9') return null
            while (peek() in '0'..'9') cursor++
        }
        if (peek() == 'e' || peek() == 'E') {
            cursor++
            if (peek() == '+' || peek() == '-') cursor++
            if (peek() !in '0'..'9') return null
            while (peek() in '0'..'9') cursor++
        }
        return source.substring(start, cursor)
    }

    private fun literal(value: String): String? {
        if (!source.startsWith(value, cursor)) return null
        cursor += value.length
        return value
    }

    private fun skipWhitespace() {
        while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') cursor++
    }

    private fun peek(): Char = source.getOrNull(cursor) ?: '\u0000'

    private fun consume(char: Char): Boolean {
        if (peek() != char) return false
        cursor++
        return true
    }

    private fun String.substringOrNull(start: Int, end: Int): String? =
        if (start >= 0 && end <= length) substring(start, end) else null
}
